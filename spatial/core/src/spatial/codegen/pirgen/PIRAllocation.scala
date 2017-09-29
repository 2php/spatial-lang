package spatial.codegen.pirgen

import argon.core._
import argon.nodes._
import spatial.aliases._
import spatial.metadata._
import spatial.nodes._
import spatial.utils._
import org.virtualized.SourceContext

import scala.collection.mutable

class PIRAllocation(mapping:mutable.Map[Expr, List[PCU]])(implicit val codegen:PIRCodegen) extends PIRTraversal {
  override val name = "PIR CU Allocation"
  var IR = codegen.IR

  // -- State
  var top: Option[Expr] = None
  val readerCUs = mutable.Map[Expr, List[PCU]]()
  val allocated = mutable.ListBuffer[Expr]()

  def addIterators(cu: PCU, cchain: CChainInstance, inds: Seq[Seq[Exp[Index]]], valids: Seq[Seq[Exp[Bit]]]) {
    inds.zipWithIndex.foreach{case (is, ci) =>
      is.zipWithIndex.foreach{ case (index, ii) => cu.getOrElseUpdate(index)(CounterReg(cchain, ci, ii)) }
    }
    valids.zipWithIndex.foreach{case (vs, ci) =>
      vs.zipWithIndex.foreach{ case (v, vi) => cu.getOrElseUpdate(v)(ValidReg(cchain, ci, vi)) }
    }
  }

  def cchainOf(pipe:Expr) = {
    pipe match {
      case Def(UnrolledForeach(en, cchain, func, iters, valids)) => 
        Some((cchain, iters, valids))
      case Def(UnrolledReduce(en, cchain, accum, func, iters, valids)) =>
        Some((cchain, iters, valids))
      case _ => None
    }
  }

  def allocateCChains(pipe: Expr) = {
    val cu = allocateCU(pipe)
    pipe match {
      case Def(_:UnitPipe | _:Hwblock) => 
        val cc = UnitCChain(s"${pipe}_unit")
        cu.cchains += cc
      case _ =>
    }
    cchainOf(pipe).foreach { case (cchain, iters, valids) =>
      dbgblk(s"Allocate cchain ${qdef(cchain)} for $pipe") {
        def allocateCounter(start: Expr, end: Expr, stride: Expr, par:Int) = {
          dbgs(s"counter start:${qdef(start)}, end:${qdef(end)}, stride:${qdef(stride)}, par:$par")
          val min = allocateLocal(cu, start)
          val max = allocateLocal(cu, end)
          val step = allocateLocal(cu, stride)
          CUCounter(min, max, step, par)
        }
        val Def(CounterChainNew(ctrs)) = cchain
        val counters = ctrs.collect{case ctr@Def(CounterNew(start,end,stride,_)) => 
          val par = getConstant(parFactorsOf(ctr).head).get.asInstanceOf[Int]
          allocateCounter(start, end, stride, par)
        }
        val cc = CChainInstance(quote(cchain), cchain, counters)
        cu.cchains += cc
        addIterators(cu, cc, iters, valids)
      }
    }
  }

  def getCUStyle(exp:Expr):CUStyle = exp match {
    case Def(FringeDenseLoad(dram, _, _))  => 
      FringeCU(allocateDRAM(dram), MemLoad)
    case Def(FringeDenseStore(dram, _, _, _))  => 
      FringeCU(allocateDRAM(dram), MemStore)
    case Def(FringeSparseLoad(dram, _, _))  => 
      FringeCU(allocateDRAM(dram), MemGather)
    case Def(FringeSparseStore(dram, _, _))  => 
      FringeCU(allocateDRAM(dram), MemScatter)
    //case Def(SwitchCase(body)) =>
    case _ if isAccess(exp) => getCUStyle(parentOf(exp).get)
    case _ if isControlNode(exp) => styleOf(exp) match {
      case SeqPipe if isInnerControl(exp) => PipeCU
      case SeqPipe if isOuterControl(exp) => SequentialCU
      case InnerPipe                       => PipeCU
      case MetaPipe if isInnerControl(exp) => PipeCU // TODO: shouldn't happen
      case MetaPipe if isOuterControl(exp) => MetaPipeCU
      case StreamPipe                      => StreamCU
      case ForkSwitch                      => StreamCU 
      case ForkJoin                        => throw new Exception("ForkJoin is not supported in PIR")
    }
  }

  def allocateCU(exp: Expr): PCU = getOrElseUpdate(mapping, exp, dbgblk(s"allocateCU($exp)") {
    if (isControlNode(exp)) {
      dbgs(s"isInnerControl = ${isInnerControl(exp)}")
      dbgs(s"styleOf = ${styleOf(exp)}")
    }
    dbgs(s"parent = ${parentOf(exp)}")
    dbgs(s"parent.parent = ${parentOf(exp).map{ p => parentOf(p) }}")
    val parent = if (isAccess(exp)) parentOf(parentOf(exp).get).map(allocateCU)
                 else               parentOf(exp).map(allocateCU)

    val style = getCUStyle(exp)

    val cu = PseudoComputeUnit(quote(exp), exp, style)
    cu.parent = parent

    cu.innerPar = getInnerPar(exp)
 
    if (top.isEmpty && parent.isEmpty) top = Some(exp)

    List(cu)
  }).head

  def allocateMemoryCU(dsram:Expr):List[PCU] = {
    val cus = getOrElseUpdate(mapping, dsram, { 
      val sram = compose(dsram)
      val parentCU = parentOf(sram).map(allocateCU)
      val writers = getWriters(sram)
      dbgblk(s"Allocating memory cu for ${qdef(sram)}, writers:$writers") {
        duplicatesOf(sram).zipWithIndex.flatten { case (m, i) =>
          m match {
            case m@BankedMemory(dims, depth, isAccum) =>
              dbgs(s"BankedMemory # banks:${dims.map { 
                case Banking(strides, banks, _) => s"(strides=$strides, banks=$banks)"
              }.mkString(",")}")
              val outerDims = dims.dropRight(1) // Assume last dimension is the inner dimension
              val totalOuterBanks = outerDims.map{_.banks}.product
              dbgs(s"totalOuterBanks=$totalOuterBanks")
              List.tabulate(totalOuterBanks) { bank =>
                val cu = PseudoComputeUnit(s"${quote(dsram)}_dsp${i}_bank${bank}", dsram, MemoryCU(i, bank))
                dbgs(s"Allocating MCU duplicates $cu for ${quote(dsram)}, duplicateId=$i")
                cu.parent = parentCU
                val psram = createSRAM(dsram, m, i, cu)
                cu
              }
            case DiagonalMemory(strides, banks, depth, isAccum) =>
              throw new Exception(s"Plasticine doesn't support diagonal banking at the moment!")
          }
        }.toList
      }
    })
    cus
  }

  def copyBounds(exps: Seq[Expr], cu:PseudoComputeUnit) = {
    val allExps = exps ++ exps.flatMap{x => getDef(x).map(_.expInputs).getOrElse(Nil) }

    allExps.foreach {
      case b:Bound[_] => 
        if (cu.get(b).isEmpty) {
          val fromPipe = parentOf(b).getOrElse(throw new Exception(s"$b doesn't have parent"))
          val fromCUs = mapping(fromPipe)
          assert(fromCUs.size==1) // parent of bounds must be a controller in spatial
          val fromCU = fromCUs.head 
          val (cchain, iters, valids) = cchainOf(fromPipe).getOrElse {
            throw new Exception(s"fromPipe=$fromPipe doesn't have counterChain but bound=$b's parent is $fromPipe")
          }
          val inds = List(iters, valids).filter{ _.exists(_.contains(b)) }.head
          val ctrIdx = inds.zipWithIndex.filter { case (inds, idx) => inds.contains(b) }.head._2
          val iterIdx = inds.filter{_.contains(b)}.head.indexOf(b)
          copyIterators(cu, fromCU, Some(ctrIdx -> iterIdx))
        }
      case _ => 
    }
  }

  /*
   * Schedule stages of PCU corresponding to pipe
   * */
  def allocateStages(pipe: Expr, func: Block[Any]):PseudoComputeUnit = dbgblk(s"allocateStages ${qdef(pipe)}") {
    val cu = allocateCU(pipe)

    val stms = getStms(pipe) 

    val localCompute = symsUsedInCalcExps(stms)(Seq(func.result), func.effectful)
    copyBounds(localCompute , cu)

    // Sanity check
    val trueComputation = localCompute.filterNot{case Exact(_) => true; case s => isRegisterRead(s)}
    if (isOuterControl(pipe)) {
      if (trueComputation.nonEmpty) {
        warn(s"Outer control $pipe has compute stages: ")
        trueComputation.foreach{case lhs@Def(rhs) => warn(s"$lhs = $rhs")}
      }
    }
    else { // Only inner pipes have stages
      cu.computeStages ++= localCompute.map{ s => 
        val isReduce = (s match {
          case Def(RegRead(_)) => false
          case Def(RegWrite(_,_,_)) => false
          case s => reduceType(s).isDefined
        }) && !isBlockReduce(func)
        DefStage(s, isReduce = isReduce)
      }
      dbgl(s"prescheduled stages for $cu:") {
        cu.computeStages.foreach {
          case s@DefStage(op, _) => dbgs(s"${qdef(op)} reduceType=${reduceType(op)}")
          case s => dbgs(s"$s")
        }
      }
    }
    cu
  }

  def allocateSwitchControl(exp:Expr, selects:Seq[Expr], cases:Seq[Expr]) = {
    val cu = allocateCU(exp)
    selects.zip(cases).foreach { case (sel, switchCase) => 
      cu.switchTable += CUBit(s"$sel") -> allocateCU(switchCase)
    }
  }

  def createSRAM(dmem:Expr, inst:Memory, i:Int, cu:PCU):CUMemory = {
    val cuMem = getOrElseUpdate(cu.memMap, dmem, {
      val cuMem = CUMemory(quote(dmem), dmem, cu)
      cuMem.mode = SRAMMode
      cuMem.size = constDimsOf(compose(dmem).asInstanceOf[Exp[SRAM[_]]]).product / inst.totalBanks
      inst match {
        case BankedMemory(dims, depth, isAccum) =>
          dims.last match { case Banking(stride, banks, _) =>
            // Inner loop dimension 
            if (banks > 1) {
              assert(banks<=16, s"Plasticine only support banking <= 16 within PMU banks=$banks")
              cuMem.banking = Some(Strided(stride, banks)) 
            } else {
              dbgs(s"createSRAM bank=1 stride=${stride}")
              cuMem.banking = Some(NoBanks)
            }
          }
        case DiagonalMemory(strides, banks, depth, isAccum) =>
          throw new Exception(s"Plasticine doesn't support diagonal banking at the moment!")
      }
      cuMem.bufferDepth = inst.depth
      dbgs(s"Add sram=$cuMem to cu=$cu")
      cuMem
    })
    cuMem
  }

  def createRetimingFIFO(exp:Expr, isScalar:Boolean, cu:PCU):CUMemory = {
    val cuMem = getOrElseUpdate(cu.memMap, exp, {
      val cuMem = CUMemory(quote(exp), exp, cu)
      cuMem.mode = if (isScalar) ScalarFIFOMode else VectorFIFOMode
      cuMem.size = 1
      dbgs(s"Add fifo=$cuMem to cu=$cu")
      cuMem
    })
    cuMem
  }

  def createLocalMem(dmem: Expr, dreader: Expr, cu: PCU): CUMemory =  {
    val mem = compose(dmem)
    val reader = compose(dreader)
    val cuMem = getOrElseUpdate(cu.memMap, dmem, {
      val cuMem = CUMemory(quote(dmem), dmem, cu)
      mem match {
        case mem if isReg(mem) => //TODO: Consider initValue of Reg?
          cuMem.size = 1
          cuMem.mode = ScalarBufferMode
          cuMem.bufferDepth = getDuplicate(dmem, dreader).depth
        case mem if isGetDRAMAddress(mem) =>
          cuMem.size = 1
          cuMem.mode = ScalarBufferMode
          cuMem.bufferDepth = 1
        case mem if isFIFO(mem) =>
          cuMem.size = stagedSizeOf(mem.asInstanceOf[Exp[FIFO[Any]]]) match { case Exact(d) => d.toInt }
          cuMem.mode = if (getInnerPar(reader)==1) ScalarFIFOMode else VectorFIFOMode
        case mem if isStream(mem) =>
          cuMem.size = 1
          val accesses = (if (isStreamIn(mem)) readersOf(mem) else writersOf(mem)).map{ _.node }.toSet
          assert(accesses.size==1, s"assume single access ctrlNode for StreamIn but found ${accesses}")
          cuMem.mode = if (getInnerPar(accesses.head)==1) ScalarFIFOMode else VectorFIFOMode
      }
      dbgs(s"Add mem=$cuMem mode=${cuMem.mode} to cu=$cu")
      cuMem
    })
    cuMem
  }

  def createFringeMem(dmem:Expr, fringe:Expr, cu:PCU):CUMemory = {
    val mem = compose(dmem) // streamOut
    val cuMem = getOrElseUpdate(cu.memMap, dmem, {
      val cuMem = CUMemory(quote(dmem), dmem, cu)
      cuMem.size = 1
      val writers = writersOf(mem).map{_.ctrlNode}.toSet
      assert(writers.size==1, s"Assume single writer to $mem but found ${writers.size}")
      cuMem.mode = if (getInnerPar(writers.head)==1) ScalarFIFOMode else VectorFIFOMode
      dbgs(s"Add fifo=$cuMem mode=${cuMem.mode} to cu=$cu")
      cuMem
    })
    cuMem
  }

  /*
   * @param mem original memory Expr
   * Allocate local memory inside the reader
   * */
  def allocateLocalMem(mem:Expr):Unit = if (allocated.contains(mem)) return else dbgblk(s"allocateLocalMem($mem)"){
    allocated += mem
    var readers = getReaders(mem) 
    readers.foreach { reader => 
      dbgblk(s"reader=$reader") {
        dbgs(s"mem=$mem, dmems=[${decompose(mem).mkString(",")}] dreaders=${decompose(reader).mkString(",")}")
        val dreaders = reader match {
          case reader if isFringe(reader) => decompose(mem).map { m => reader }
          case reader => decompose(reader)
        }
        decompose(mem).zip(dreaders).foreach { case (dmem, dreader) => 
          val bus = mem match {
            case mem if isArgIn(mem) => Some(InputArg(s"${mem.name.getOrElse(quote(dmem))}", dmem))
            case mem@Def(GetDRAMAddress(dram)) => Some(DramAddress(s"${dram.name.getOrElse(quote(dmem))}", dram, mem))
            case _ => None
          }
          bus.foreach { b => globals += b }
          getReaderCUs(reader).foreach { readerCU =>
            val localWritten = isLocallyWritten(dmem, dreader, readerCU)
            if (!localWritten) { // Write to FIFO/StreamOut/RemoteReg
              // Allocate local mem in the readerCU
              createLocalMem(dmem, dreader, readerCU)
              // Set writeport of the local mem who doesn't have a writer (ArgIn and GetDRAMAddress)
              bus.foreach { bus => readerCU.memMap(dmem).writePort += bus }
            } else { // Local reg accumulation
              allocateLocal(readerCU, dmem)
            }
          }
        }
      }
    }
  }

  /*
   * @param reader the reader symbol
   * @return list of CUs where the reader symbol is used in calculation. In case a
   * load/regRead/fifoDeq is used for both data calculation and address calculation for remote
   * memory, this function returns both the PCU and MCUs
   * */
  def getReaderCUs(reader: Expr): List[PseudoComputeUnit] = if (readerCUs.contains(reader)) readerCUs(reader) else
    dbgblk(s"getReaderCUs ${qdef(reader)}") {
      val readerCUs = mutable.Set[PseudoComputeUnit]()
      if (isFringe(reader)) { readerCUs += allocateCU(reader) } // Fringe is considered to be a reader of the stream
      else {
        parentOf(reader).foreach { pipe => // RegRead outside HwBlock doesn't have parent
          dbgs(s"parentOf($reader) = ${qdef(pipe)}")
          val stms = getStms(pipe)
          def addParentCU(s: Expr, d:Def, mem: Expr, ind: Option[Seq[Expr]]) = {
            val indSyms = ind.map { ind => symsUsedInCalcExps(stms)(Seq(), ind) }.getOrElse(Nil)
            if (indSyms.contains(reader) && isRemoteMem(mem)) {
              readerCUs ++= decompose(mem).zip(decompose(s)).flatMap { case (dm, da) => getMCUforAccess(dm, da) }
            }
            else if (d.allInputs.contains(reader) || (s==reader && isInnerControl(pipe)) ) { //RegRead can occur outside user
              readerCUs += allocateCU(pipe)
            }
          }
          dbgl(s"$pipe's stms:") { stms.foreach { stm => dbgs(s"$stm") } }
          stms.foreach {
            case TP(s, d@ParLocalReader(reads)) =>
              val (mem, inds, _) = reads.head
              addParentCU(s, d, mem, inds.map{_.head})
            case TP(s, d@ParLocalWriter(writes)) =>
              val (mem, _, inds, _) = writes.head
              addParentCU(s, d, mem, inds.map{_.head})
            case TP(s@Def(_:CounterNew), d) if d.allInputs.contains(reader) => readerCUs ++= getReaderCUs(s)
            case TP(s@Def(_:CounterChainNew), d) if d.allInputs.contains(reader) => readerCUs ++= getReaderCUs(s)
            case TP(s, d) if d.allInputs.contains(reader) & isControlNode(s) => readerCUs += allocateCU(s)
            case TP(s, d) if d.allInputs.contains(reader) => readerCUs += allocateCU(pipe) // Include pipe only if it's used 
            case TP(s, d) => 
          }
        }
      }
      dbgl(s"ReaderCUs:") {
        readerCUs.foreach { cu => dbgs(s"$cu") }
      }
      this.readerCUs += reader -> readerCUs.toList
      readerCUs.toList
    }

  /**
   * @param dwriter decomposed writer
   * @return If value/data of the writer is from a load of SRAM, returns the MCU, otherwise returns the
   * PCU
   **/
  def getWriterCU(dwriter:Expr) = dbgblk(s"getWriterCU(writer=$dwriter)") {
    val writer = compose(dwriter)
    val pipe = parentOf(writer).get 
    allocateCU(pipe)
  }

  def getMCUforAccess(dmem:Expr, daccess:Expr):List[PCU] = dbgblk(s"getMCUforAccess($dmem, $daccess)") {
    val mem = compose(dmem)
    val access = compose(daccess)
    dbgs(s"mem=$mem access=$access")
    var cus = allocateMemoryCU(dmem)
    val insts = if (isReader(access)) {
      val instId = dispatchOf(access, mem).head
      val inst = getDuplicate(mem, access) 
      cus = cus.filter{_.style match { case MemoryCU(`instId`, _) => true; case _ => false } }
      List(inst)
    } else { // isWriter
      duplicatesOf(mem)
    }

    val addr = access match {
      case ParLocalReader(List((_, Some(addr), _))) => addr
      case ParLocalWriter(List((_, _, Some(addr), _))) => addr
    }

    val banks = insts.flatMap { inst =>
      inst match {
        case m@BankedMemory(dims, depth, isAccum) =>
          val inds = Seq.tabulate(dims.size) { i => addr.map { _(i) } }
          dbgs(s"addr=$addr inds=$inds")
          dbgs(s"BankedMemory # banks:${dims.map { 
            case Banking(strides, banks, _) => s"(strides=$strides, banks=$banks)"
          }.mkString(",")}")
          val bankInds = inds.dropRight(1).zip(dims.dropRight(1)).zipWithIndex.map { 
            case ((vinds, Banking(stride, banks, _)), dim) if vinds.toSet.size>1 =>
              dbgs(s"dim=$dim vinds=${vinds} all banks=${banks}")
              (0 until banks).map { b => (b, banks)}.toList
            case ((vinds, Banking(stride, banks, _)), dim) if vinds.toSet.size==1 =>
              val vind = vinds.head
              dbgs(s"ctrlOf($vind)=${ctrlOf(vind)}")
              val bankInds = ctrlOf(vind) match {
                case Some((ctrl, _)) => 
                  val parIdxs = itersOf(ctrl).get.map { iters => 
                    (iters.indexOf(vind), iters.size)
                  }.filter { _._1 >= 0 }
                  dbgs(s"itersOf($ctrl)=${itersOf(ctrl)}")
                  assert(parIdxs.size == 1 , s"$ctrl doesn't belong to $ctrl but ctrlOf($vind) = $ctrl!")
                  val (iterIdx, iterPar) = parIdxs.head
                  if (iterPar==1) {
                    (0 until banks).map { b => (b, banks)}.toList
                  } else {
                    List((iterIdx, banks))
                  }
                case None => 
                  (0 until banks).map { b => (b, banks)}.toList
              }
              dbgs(s"dim=$dim banks=${bankInds}")
              bankInds
          }
          dbgs(s"bankInds=$bankInds")
          def indComb(inds:List[List[(Int, Int)]], prevDims:List[(Int, Int)]):List[Int] = { 
            if (inds.isEmpty) {
              val (inds, banks) = prevDims.unzip
              List(flattenND(inds, banks)); 
            } else {
              val headDim::restDims = inds 
              headDim.flatMap { bank => indComb(restDims, prevDims :+ bank) }
            }
          }
          val banks = indComb(bankInds.toList, Nil)
          dbgs(s"access=$access uses banks=$banks for inst=$inst")
          banks
        case DiagonalMemory(strides, banks, depth, isAccum) =>
          throw new Exception(s"Plasticine doesn't support diagonal banking at the moment!")
      }
    }
    
    cus = cus.filter{_.style match { case MemoryCU(_, bank) => banks.contains(bank); case _ => false } }
    
    cus
  } 

  def prescheduleLocalMemRead(mem: Expr, reader:Expr) = {
    dbgblk(s"prescheduleLocalMemRead(reader=$reader, mem=${quote(mem)})") {
      getReaderCUs(reader).foreach { readerCU =>
        decompose(mem).zip(decompose(reader)).foreach { case (dmem, dreader) =>
          val locallyWritten = isLocallyWritten(dmem, dreader, readerCU)
          dbgs(s"$mem readerCU:$readerCU dreader:$dreader")
          if (locallyWritten) {
            val reg = readerCU.get(dmem).get // Accumulator should be allocated during RegNew
            readerCU.addReg(dreader, reg)
          } else {
            val pmem = readerCU.memMap(dmem)
            readerCU.addReg(dreader, MemLoadReg(pmem))
          }
        }
      }
    }
  }

  def prescheduleLocalMemWrite(mem: Expr, writer:Expr) = {
    dbgblk(s"prescheduleLocalMemWrite(writer=$writer, mem=${quote(mem)})") {
      val remoteReaders = getRemoteReaders(mem, writer)
      dbgs(s"remoteReaders:${remoteReaders.mkString(",")}")
      if (remoteReaders.nonEmpty || isArgOut(mem)) {
        allocateLocalMem(mem)
        decompose(mem).zip(decompose(writer)).foreach { case (dmem, dwriter) =>
          dbgs(s"dmem:$dmem, dwriter:$dwriter")
          dbgs(s"isArgOut=${isArgOut(mem)} isStreamOut=${isStreamOut(mem)} isReg=${isReg(mem)}")
          dbgs(s"isFIFO=${isFIFO(mem)} isStream=${isStream(mem)} getInnerPar=${getInnerPar(writer)}")
          val bus = mem match {
            case mem if isArgOut(mem) => OutputArg(s"${quote(dmem)}_${quote(dwriter)}") 
            case mem if isReg(mem) => CUScalar(s"${quote(dmem)}_${quote(dwriter)}")
            case mem if isFIFO(mem) & getInnerPar(writer)==1 => CUScalar(s"${quote(dmem)}_${quote(dwriter)}")
            case mem if isStream(mem) & getInnerPar(writer)==1 => CUScalar(s"${quote(dmem)}_${quote(dwriter)}")
            case mem => CUVector(s"${quote(dmem)}_${quote(dwriter)}")
          }
          globals += bus
          val output = bus match {
            case bus:ScalarBus => ScalarOut(bus)
            case bus:VectorBus => VectorOut(bus)
            case bus:BitBus => BitOut(bus)
          }
          val writerCU = getWriterCU(dwriter) 
          writerCU.addReg(dwriter, output)
          dbgs(s"Add dwriter:$dwriter to writerCU:$writerCU")
          remoteReaders.foreach { reader =>
            getReaderCUs(reader).foreach { readerCU =>
              val locallyWritten = isLocallyWritten(dmem, reader, readerCU)
              if (!locallyWritten) {
                dbgs(s"set ${quote(dmem)}.writePort = $bus in readerCU=$readerCU reader=$reader")
                readerCU.memMap(dmem).writePort += bus
              }
            }
          }
        }
      }
    }
  }

  def allocateRemoteMemAddrCalc(mem:Expr, access:Expr, addrCU:PCU) = {
    val parBy1 = getInnerPar(access)==1
    val pipe = parentOf(access).get
    val stms = getStms(pipe)
    val addr = access match {
      case ParLocalReader((_, addrs, _)::_) => addrs.get.head // Assume SIMD
      case ParLocalWriter((_, _, addrs, _)::_) => addrs.get.head // addrs Option[LANE[DIM[]]]
    }

    val indexExps = expsUsedInCalcExps(stms)(Seq(), addr)
    val indexSyms = indexExps.collect { case s:Sym[_] => s }

    val (flatAddr, flatStages) = flattenNDIndices(addr, stagedDimsOf(mem.asInstanceOf[Exp[SRAM[_]]]))

    dbgl(s"$mem") {
      dbgs(s"addr:$addr")
      dbgs(s"indexExps:[${indexExps.mkString(",")}]")
      dbgs(s"indexSyms:[${indexSyms.mkString(",")}]")
    }

    copyBounds(indexExps ++ addr, addrCU)

    // PseudoStages
    val indexStages = indexSyms.map{s => DefStage(s) }
    val addrStages = indexStages ++ flatStages
    dbgl(s"addrStages:") { addrStages.foreach { stage => dbgs(s"$stage") } }
    addrCU.computeStages ++= addrStages
   
    val postfix = access match {
      case _ if isReader(access) => "ra" 
      case _ if isWriter(access) => "wa"
    }
    val bus = if (parBy1) {
      val bus = CUScalar(s"${quote(mem)}_${quote(access)}_$postfix")
      addrCU.addReg(flatAddr, ScalarOut(bus))
      bus
    } else {
      val bus = CUVector(s"${quote(mem)}_${quote(access)}_$postfix")
      addrCU.addReg(flatAddr, VectorOut(bus))
      bus
    }

    (bus, flatAddr)
  }

  def prescheduleRemoteMemRead(mem: Expr, reader:Expr) = {
    dbgblk(s"Allocating remote memory read: ${qdef(reader)}") {
      val parBy1 = getInnerPar(reader)==1
      val readerCUs = getReaderCUs(reader)
      val addrCU = allocateCU(reader)
      val (addrBus, flatAddr) = allocateRemoteMemAddrCalc(mem, reader, addrCU)
      decompose(mem).zip(decompose(reader)).foreach { case (dmem, dreader) =>
        val sramCUs = getMCUforAccess(dmem, dreader)
        sramCUs.foreach { sramCU =>
          val dataBus = if (parBy1) CUScalar(s"${quote(dmem)}_${sramCU.name}_data") 
                        else        CUVector(s"${quote(dmem)}_${sramCU.name}_data")

          // Set up PMUs connections
          val sram = sramCU.memMap(mem)
          // Wire up readAddr
          val addrFifo = createRetimingFIFO(flatAddr, parBy1, sramCU)
          addrFifo.writePort += addrBus
          sram.readAddr += MemLoadReg(addrFifo)
          // Wire up readPort
          sram.readPort = Some(dataBus)
          dbgs(s"sram=$sram readPort=$dataBus readAddr=$addrBus")

          // Setup readerCUs connections
          readerCUs.foreach { readerCU =>
            val fifo = createRetimingFIFO(dreader, parBy1, readerCU) 
            fifo.writePort += dataBus
            readerCU.addReg(dreader, MemLoadReg(fifo))
            dbgs(s"readerCU = $readerCU reads from fifo=$fifo dataBus=$dataBus")
          }
        }
      }
    }
  }

  def prescheduleRemoteMemWrite(mem: Expr, writer:Expr) = {
    dbgblk(s"Allocating remote memory write: ${qdef(writer)}") {
      val parBy1 = getInnerPar(writer)==1
      val writerCU = getWriterCU(writer) 
      val addrCU = allocateCU(writer)
      val (addrBus, flatAddr) = allocateRemoteMemAddrCalc(mem, writer, addrCU)

      decompose(mem).zip(decompose(writer)).foreach { case (dmem, dwriter) =>

        // Setup writerCU connections
        val dataBus = if (parBy1) CUScalar(s"${quote(dmem)}_${quote(dwriter)}_data")
                      else        CUVector(s"${quote(dmem)}_${quote(dwriter)}_data")
        dataBus match {
          case bus:CUScalar => writerCU.addReg(dwriter, ScalarOut(bus))
          case bus:CUVector => writerCU.addReg(dwriter, VectorOut(bus))
          case _ =>
        }

        // Setup PMUs connections
        val sramCUs = getMCUforAccess(dmem, dwriter) 
        sramCUs.foreach { sramCU =>
          val sram = sramCU.memMap(mem)
          // Wire up writeAddr
          val addrFifo = createRetimingFIFO(flatAddr, parBy1, sramCU)
          addrFifo.writePort += addrBus
          sram.writeAddr += MemLoadReg(addrFifo)
          val dataFifo = createRetimingFIFO(dwriter, parBy1, sramCU)
          // Wire up writePort
          dataFifo.writePort += dataBus 
          sram.writePort += LocalReadBus(dataFifo)
          dbgs(s"sram=$sram writePort=$dataFifo dataBus=$dataBus writeAddr=$addrBus")
        }
      }
    }
  }

  def allocateFringe(fringe: Expr, dram: Expr, streamIns: List[Expr], streamOuts: List[Expr]) = {
    val cu = allocateCU(fringe)
    val FringeCU(dram, mode) = cu.style
    streamIns.foreach { streamIn =>
      val readers = readersOf(streamIn)
      val readerCUs = readers.map(_.node).flatMap(getReaderCUs)
      val dmems = decomposeWithFields(streamIn) match {
        case Right(dmems) if dmems.size==1 => dmems
        case Right(dmems) => throw new Exception(s"PIR don't support struct load/gather ${qdef(fringe)}") 
      }
      dmems.foreach { 
        case ("ack", _) => //PIR doesn't uses contorl in spatial
        case (field, dmem) =>
          val readerPar = getInnerPar(readers.head.node)
          dbgs(s"fringe:$fringe $field reader:${readers.head} par=${readerPar}")
          val bus = if (readerPar==1) CUScalar(s"${quote(dmem)}_${quote(fringe)}_$field")
                    else CUVector(s"${quote(dmem)}_${quote(fringe)}_$field")
          cu.fringeGlobals += field -> bus
          globals += bus
          readerCUs.foreach { _.memMap(dmem).writePort += bus }
      }
    }
    streamOuts.foreach { streamOut =>
      decompose(streamOut).foreach { mem => createFringeMem(mem, fringe, cu) }
    }
  }

  override protected def visit(lhs: Sym[_], rhs: Op[_]) = {
    dbgl(s"Visiting ${qdef(lhs)}") {
      rhs match {
        case Hwblock(func,_) =>
          allocateCU(lhs)
          allocateCChains(lhs) 

        case UnitPipe(en, func) =>
          allocateCU(lhs)
          allocateStages(lhs, func)
          allocateCChains(lhs) 

        case UnrolledForeach(en, cchain, func, iters, valids) =>
          allocateCU(lhs)
          allocateStages(lhs, func)
          allocateCChains(lhs) 

        case UnrolledReduce(en, cchain, accum, func, iters, valids) =>
          allocateCU(lhs)
          allocateStages(lhs, func)
          allocateCChains(lhs) 

        case Switch(body, selects, cases) =>
          allocateCU(lhs)
          allocateSwitchControl(lhs, selects, cases)

        case SwitchCase(body) =>
          allocateCU(lhs)

        case _ if isFringe(lhs) =>
          val dram = rhs.allInputs.filter { e => isDRAM(e) }.head
          val streamIns = rhs.allInputs.filter { e => isStreamIn(e) }.toList
          val streamOuts = rhs.allInputs.filter { e => isStreamOut(e) }.toList
          allocateFringe(lhs, dram, streamIns, streamOuts)

        case _ if isLocalMem(lhs) =>
          allocateLocalMem(lhs)
          if (isGetDRAMAddress(lhs)) prescheduleLocalMemRead(lhs, lhs) //Hack: GetDRAMAddress is both the mem and the reader

        case _ if isRemoteMem(lhs) =>
          decompose(lhs).foreach { dmem => allocateMemoryCU(dmem) }
          
        case SimpleStruct(_) => decompose(lhs)

        case ParLocalReader(reads)  =>
          val (mem, _, _) = reads.head
          if (isLocalMemAccess(lhs)) { // RegRead, FIFODeq, StreamDeq
            prescheduleLocalMemRead(mem, lhs)
          } else { // SRAMLoad
            prescheduleRemoteMemRead(mem, lhs)
          }

        case ParLocalWriter(writes)  => 
          val (mem, value, addrs, ens) = writes.head
          if (isLocalMemAccess(lhs)) { // RegWrite, FIFOEnq, StreamEnq
            prescheduleLocalMemWrite(mem, lhs)
          } else { // SRAMStore
            prescheduleRemoteMemWrite(mem, lhs)
          }

        // Something bad happened if these are still in the IR
        case _:ParallelPipe => throw new Exception(s"Disallowed op $lhs = $rhs")
        case _:OpForeach => throw new Exception(s"Disallowed compact op $lhs = $rhs")
        case _:OpReduce[_] => throw new Exception(s"Disallowed compact op $lhs = $rhs")
        case _:OpMemReduce[_,_] => throw new Exception(s"Disallowed compact op $lhs = $rhs")
        case _ => 
      }
    }
    super.visit(lhs, rhs)
  }

  override def preprocess[S:Type](b: Block[S]): Block[S] = {
    top = None
    readerCUs.clear()
    super.preprocess(b)
  }

  override def postprocess[S:Type](b: Block[S]): Block[S] = {
    dbgs(s"\n\n//----------- Finishing Allocation ------------- //")
    dbgblk(s"decomposition") {
      dbgs(s"decomposed.keys=${decomposed.keys.toList.mkString(s",")}")
      decomposed.foreach { case(s, dss) => dbgs(s"${qdef(s)} -> [${dss.mkString(",")}]") }
    }
    dbgblk(s"composition") {
      dbgs(s"composed.keys=${composed.keys.toList.mkString(s",")}")
      composed.foreach { case(ds, s) => dbgs(s"${qdef(ds)}")}
    }
    dbgs(s"// ----- CU Allocation ----- //")
    mapping.foreach { case (sym, cus) =>
      cus.foreach { cu => dbgpcu(cu) }
    }

    super.postprocess(b)
  }
}
