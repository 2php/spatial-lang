package spatial.codegen.pirgen
import spatial.SpatialExp
import org.virtualized.SourceContext

import scala.collection.mutable

trait PIRAllocation extends PIRTraversal {
  val IR: SpatialExp with PIRCommonExp
  import IR.{println => _, _}

  override val name = "PIR CU Allocation"

  // -- State
  var top: Option[Symbol] = None
  var mapping = mutable.HashMap[Symbol, List[PCU]]()

  private def controllersHack(pipe: Symbol): List[Symbol] = pipe match {
    case Def(_:ParallelPipe) => childrenOf(pipe).flatMap{child => controllersHack(child)}
    case _ => List(pipe)
  }
  // Give top controller or first controller below which is not a Parallel
  private def topControllerHack(access: Access, ctrl: Ctrl): Ctrl = ctrl.node match {
    case pipe@Def(ParallelPipe(en, _)) =>
      topControllerHack(access, childContaining(ctrl, access))
    case _ => ctrl
  }

  // ISSUE #2: Assumes linear stage order
  def pipeDependencies(pipe: Symbol): List[Symbol] = parentOf(pipe) match {
    case Some(parent@Def(_:ParallelPipe)) => pipeDependencies(parent)
    case Some(parent) =>
      val childs = childrenOf(parent).map{child => controllersHack(child) }
      val idx = childs.indexWhere(_ contains pipe )
      if (idx > 0) childs(idx-1)
      else Nil
    case None => Nil
  }

  def addIterators(cu: PCU, cc: Exp[CounterChain], inds: Seq[Seq[Exp[Index]]], valids: Seq[Seq[Exp[Bool]]]) {
    val cchain = cu.cchains.find(_.name == quote(cc)).getOrElse(throw new Exception(s"Cannot find counterchain $cc in $cu"))
    inds.zipWithIndex.foreach{case (is, i) =>
      is.foreach{index => cu.addReg(index, CounterReg(cchain, i)) }
    }
    valids.zipWithIndex.foreach{case (es, i) =>
      es.foreach{e => cu.addReg(e, ValidReg(cchain, i)) }
    }
  }

  def allocateCChains(cu: PCU, pipe: Symbol) {
    def allocateCounter(start: Symbol, end: Symbol, stride: Symbol) = {
      val min = cu.getOrElse(start){ allocateLocal(start, pipe) }
      val max = cu.getOrElse(end){ allocateLocal(end, pipe) }
      val step = cu.getOrElse(stride){ allocateLocal(stride, pipe) }
      CUCounter(localScalar(min), localScalar(max), localScalar(step))
    }

    parentHack(pipe).foreach{parent => copyIterators(cu, allocateCU(parent)) }

    val Def(rhs) = pipe
    val ccs = syms(rhs).collect{
      case cc@Def(CounterChainNew(ctrs)) =>
        val counters = ctrs.collect{case Def(CounterNew(start,end,stride,_)) => allocateCounter(start, end, stride) }
        CChainInstance(quote(cc), counters)
    }
    cu.cchains ++= ccs
  }

  def initCU(cu: PCU, pipe: Symbol) {
    allocateCChains(cu, pipe)
    cu.deps ++= pipeDependencies(pipe).map(allocateCU)
  }

  def allocateComputeUnit(pipe: Symbol): PCU = mapping.getOrElseUpdate(pipe, {
    val parent = parentHack(pipe).map(allocateCU)

    val style = pipe match {
      case Def(_:UnitPipe) => UnitCU
      case Def(_:Hwblock)  => UnitCU
      case Def(_:BurstLoad[_]) => UnitCU
      case Def(_:BurstStore[_]) => UnitCU
      case Def(_:Scatter[_]) => StreamCU
      case Def(_:Gather[_]) => StreamCU
      case _ if styleOf(pipe) == SeqPipe && isInnerPipe(pipe) => UnitCU
      case _ => typeToStyle(styleOf(pipe))
    }

    val cu = PseudoComputeUnit(quote(pipe), pipe, style)
    cu.parent = parent
    initCU(cu, pipe)

    pipe match {
      case Def(e:UnrolledForeach)      => addIterators(cu, e.cchain, e.iters, e.valids)
      case Def(e:UnrolledReduce[_,_])  => addIterators(cu, e.cchain, e.iters, e.valids)
      case Def(_:UnitPipe | _:Hwblock) => cu.cchains += UnitCChain(quote(pipe)+"_unitcc")
      case Def(_:BurstLoad[_] | _:BurstStore[_]) => cu.cchains += UnitCChain(quote(pipe)+"_unitcc")
      case Def(e:Scatter[_]) =>
      case Def(e:Gather[_]) =>
      case _ =>
    }
    if (top.isEmpty && parent.isEmpty) top = Some(pipe)

    val Def(d) = pipe
    dbg(s"Allocating CU $cu for $pipe = $d")

    List(cu)
  }).head

  def allocateCU(pipe: Symbol): PCU = pipe match {
    case Def(_:Hwblock)             => allocateComputeUnit(pipe)
    case Def(_:UnrolledForeach)     => allocateComputeUnit(pipe)
    case Def(_:UnrolledReduce[_,_]) => allocateComputeUnit(pipe)
    case Def(_:UnitPipe)            => allocateComputeUnit(pipe)

    case Def(_:BurstLoad[_])  => allocateComputeUnit(pipe)
    case Def(_:BurstStore[_]) => allocateComputeUnit(pipe)
    case Def(_:Scatter[_])    => allocateComputeUnit(pipe)
    case Def(_:Gather[_])     => allocateComputeUnit(pipe)

    case Def(d) => throw new Exception(s"Don't know how to generate CU for\n  $pipe = $d")
  }

  def allocateMemoryCU(sram:Symbol):List[PCU] = mapping.getOrElseUpdate(sram, {
    val Def(d) = sram

    val readAccesses = readersOf(sram).groupBy { read => 
      val id = dispatchOf(read, sram)
      assert(id.size==1)
      id.head
    }

    val writeAccesses = writersOf(sram)
    assert(writeAccesses.size==1, s"Plasticine currently only supports single writer at the moment $sram writeAccesses:$writeAccesses")
    val writeAccess = writeAccesses.head
    dbg(s"Allocating memory cu for $sram = $d, writeAccess:${writeAccess}")
    duplicatesOf(sram).zipWithIndex.map{ case (mem, i) => 

      val readAccess = readAccesses(i).head
      val cu = PseudoComputeUnit(s"${quote(sram)}_dsp${i}", sram, MemoryCU(i))
      dbg(s"  Allocating MCU duplicates $cu for $sram, readAccess:$readAccess")
      //val readerCU = allocateCU(readAccess.ctrlNode)
      //val parent = if (readAccess.ctrlNode==writeAccess.ctrlNode) //Not multibuffered
        //parentHack(readAccess.ctrlNode).map(allocateCU)
      //else {
        //parentHack(topControllerOf(readAccess, sram, i).get.node).map(allocateCU)
      //}
      cu.parent = None

      //initCU(cu, pipe)

      //val readerCU = allocateCU(readAccess.ctrlNode)
      val psram = allocateMem(sram, readAccess.node, cu)

      //pipe match {
        //case Def(e:UnrolledForeach)      => addIterators(cu, e.cchain, e.iters, e.valids)
        //case Def(e:UnrolledReduce[_,_])  => addIterators(cu, e.cchain, e.iters, e.valids)
        //case Def(_:UnitPipe | _:Hwblock) => cu.cchains += UnitCChain(quote(pipe)+"_unitcc")
        //case _ =>
      //}

      cu
    }.toList
  })

  def prescheduleRegisterRead(reg: Symbol, reader: Symbol, pipe: Option[Symbol]) = {
    dbg(s"  Allocating register read: $reader")
    // Register reads may be used by more than one pipe
    readersOf(reg).filter(_.node == reader).map(_.ctrlNode).foreach{readCtrl =>
      val isCurrentPipe = pipe.exists(_ == readCtrl)
      val isLocallyWritten = isWrittenInPipe(reg, readCtrl)

      if (!isCurrentPipe || !isLocallyWritten) {
        val readerCU = allocateCU(readCtrl)
        dbg(s"    Adding stage $reader of $reg to reader $readerCU")
        readerCU.computeStages += DefStage(reader)
      }
    }
  }

  def allocateWrittenSRAM(writer: Symbol, mem: Symbol, writerCU: PCU, stages: List[PseudoStage]) {
    val sramCUs = allocateMemoryCU(mem)
    dbg(s"  Allocating written SRAM $mem")
    dbg(s"    writer   = $writer")
    dbg(s"    writerCU = $writerCU")
    dbg(s"    sramCUs = $sramCUs")

    sramCUs.zipWithIndex.foreach{ case (sramCU, i) => 
      copyIterators(sramCU, writerCU)
      //val bus = if (writerCU.isUnit) CUScalar(s"${quote(mem)}_${i}_wt") else CUVector(s"${quote(mem)}_${i}_wt")
      val bus = CUVector(s"${quote(mem)}_${i}_wt") //TODO Writes to sram is alwasy using vector bus
      globals += bus
      sramCU.srams.foreach { _.writePort = Some(bus) }
      if (stages.nonEmpty) {
        dbg(s"    $sramCU Write stages: ")
        stages.foreach{stage => dbg(s"      $stage")}
        sramCU.writeStages(sramCU.srams.toList) = (writerCU.pipe,stages)
      }
    }

    //val srams = readersOf(mem).map{reader =>
      //val readerCU = allocateCU(reader.ctrlNode)
      //copyIterators(readerCU, writerCU)

      //val sram = allocateMem(mem, reader.node, readerCU)
      //if (readerCU == writerCU) {
        //sram.writePort = Some(LocalVectorBus)
      //}
      //else {
        //val bus = if (writerCU.isUnit) CUScalar(quote(mem)) else CUVector(quote(mem))
        //globals += bus
        //sram.writePort = Some(bus)
      //}

      //dbg(s"  Allocating written SRAM $mem")
      //dbg(s"    writer   = $writer")
      //dbg(s"    writerCU = $writerCU")
      //dbg(s"    readerCU = $readerCU")

      //(readerCU, sram)
    //}

    //if (stages.nonEmpty) {
      //dbg(s"    Write stages: ")
      //stages.foreach{stage => dbg(s"      $stage")}

      //val groups = srams.groupBy(_._1).mapValues(_.map(_._2))
      //for ((readerCU,srams) <- groups if readerCU != writerCU) {
        //dbg(s"""  Adding write stages to $readerCU for SRAMs: ${srams.mkString(", ")}""")
        //readerCU.writeStages(srams) = (writerCU.pipe,stages)
      //}
    //}
  }
  def allocateReadSRAM(reader: Symbol, mem: Symbol, readerCU: PCU, stages:List[PseudoStage]) = {
    val sramCUs = allocateMemoryCU(mem)
    val dispatch = dispatchOf(reader, mem).head
    val sramCU = sramCUs(dispatch)
    copyIterators(sramCU, readerCU)
    val bus = CUVector(s"${quote(mem)}_${dispatch}_rd") //TODO Reads to sram is always using vector bus
    globals += bus
    sramCU.srams.foreach{ _.readPort = Some(bus) } //each sramCU should only have a single sram
    if (stages.nonEmpty) {
      dbg(s"    $sramCU Read stages: ")
      stages.foreach{stage => dbg(s"      $stage")}
      sramCU.readStages(sramCU.srams.toList) = (readerCU.pipe,stages)
    }

    dbg(s"  Allocating read SRAM $mem")
    dbg(s"    reader   = $reader")
    dbg(s"    readerCU = $readerCU")
    sramCU.srams.head
  }

  private def initializeSRAM(sram: CUMemory, mem: Symbol, read: Symbol, cu: PCU) {
    val reader = readersOf(mem).find(_.node == read).get

    val instIndex = dispatchOf(reader, mem).head
    val instance = duplicatesOf(mem).apply(instIndex)

    // Find first writer corresponding to this reader
    val writers = writersOf(mem).filter{writer => dispatchOf(writer,mem).contains(instIndex) }
    if (writers.length > 1) {
      throw new Exception(s"$mem: $writers: PIR currently cannot handle multiple writers")
    }
    val writer = writers.headOption

    val writerCU = writer.map{w => allocateCU(w.ctrlNode) }
    val swapWritePipe = writer.flatMap{w => topControllerOf(w, mem, instIndex) }
    val swapReadPipe  = topControllerOf(reader, mem, instIndex)

    val swapWriteCU = (writer, swapWritePipe) match {
      case (Some(write), Some(ctrl)) =>
        val topCtrl = topControllerHack(write, ctrl)
        Some(allocateCU(topCtrl.node))
      case _ => None
    }
    val swapReadCU = swapReadPipe.map{ctrl =>
        val topCtrl = topControllerHack(reader, ctrl)
        allocateCU(topCtrl.node)
    }

    val remoteWriteCtrl = writerCU.flatMap{cu => cu.cchains.find{case _:UnitCChain | _:CChainInstance => true; case _ => false}}
    val remoteSwapWriteCtrl = swapWriteCU.flatMap{cu => cu.cchains.find{case _:UnitCChain | _:CChainInstance => true; case _ => false}}
    val remoteSwapReadCtrl = swapReadCU.flatMap{cu => cu.cchains.find{case _:UnitCChain | _:CChainInstance => true; case _ => false}}

    val readCtrl = cu.cchains.find{case _:UnitCChain | _:CChainInstance => true; case _ => false}
    val writeCtrl = remoteWriteCtrl.flatMap{cc => cu.cchains.find(_.name == cc.name) }
    val swapWrite = remoteSwapWriteCtrl.flatMap{cc => cu.cchains.find(_.name == cc.name) }
    val swapRead  = remoteSwapReadCtrl.flatMap{cc => cu.cchains.find(_.name == cc.name) }

    val writeIter = writeCtrl.flatMap{cc => cu.innermostIter(cc) }
    val readIter  = readCtrl.flatMap{cc => cu.innermostIter(cc) }

    val banking = if (isFIFO(mem)) Strided(1) else {
      val readBanking  = bank(mem, read, cu.isUnit)
      val writeBanking = writer.map{w => bank(mem, w.node, writerCU.get.isUnit) }.getOrElse(NoBanks)
      mergeBanking(writeBanking, readBanking)
    }

    sram.writeCtrl = writeCtrl
    sram.swapWrite = swapWrite
    sram.swapRead  = swapRead
    sram.banking   = Some(banking)
    sram.bufferDepth = instance.depth
    if (isFIFO(mem)) sram.mode = FIFOMode
  }
  
  def allocateMem(mem: Symbol, reader: Symbol, cu: PCU): CUMemory = {
//    if (!isBuffer(mem))
//      throw new Exception(s"Cannot allocate SRAM for non-buffer symbol $mem")
    cu.srams.find{sram => sram.mem == mem && sram.reader == reader}.getOrElse{
      val name = s"${quote(mem)}_${quote(reader)}"
      val size = mem match {
        case m if isSRAM(m) => dimsOf(m.asInstanceOf[Exp[SRAM[_]]]).product
        case m if isFIFO(m) => sizeOf(m.asInstanceOf[Exp[FIFO[Any]]]) match { case Exact(d) => d.toInt }
      }
      val sram = CUMemory(name, size, mem, reader)
      cu.srams += sram
      sram
    }
  }


  def prescheduleStages(pipe: Symbol, func: Block[Any]) {
    val cu = allocateCU(pipe)

    val remotelyAddedStages = cu.computeStages // Stages added prior to traversing this pipe
    val remotelyAddedStms = remotelyAddedStages.flatMap(_.output).flatMap{
      case s: Sym[_] => Some(stmOf(s))
      case _ => None
    }

    //Hack Check if func is inside block reduce
    val reduceSRAM = func.summary.reads.intersect(func.summary.writes).filter(isSRAM).nonEmpty

    val stms = remotelyAddedStms ++ blockContents(func)
    val stages = stms.map{case TP(lhs,rhs) => lhs}

    var remoteStages: Set[Exp[Any]] = Set.empty   // Stages to ignore (goes on different CU)

    // HACK: Ignore write address for SRAMs written from popping the result of tile loads
    // (Skipping the vector packing/unpacking nonsense in between)
    def useFifoOnWrite(mem: Exp[Any], value: Exp[Any]): Boolean = value match { //TODO how about gather??
      case Def(FIFODeq(fifo, en, _))     =>
        dbg(s"      $value = pop($fifo) [${writersOf(fifo)}]")
        writersOf(fifo).forall{writer => writer.node match {case Def(_:BurstLoad[_]) => true; case _ => false }}
      case Def(ParFIFODeq(fifo, ens, _)) =>
        dbg(s"      $value = pop($fifo) [${writersOf(fifo)}]")
        writersOf(fifo).forall{writer => writer.node match {case Def(_:BurstLoad[_]) => true; case _ => false }}
      case Def(ListVector(elems))    =>
        dbg(s"      $value = vector")
        useFifoOnWrite(mem, elems.head)
      case Def(VectorApply(vector,index))     =>
        dbg(s"      $value = vec")
        useFifoOnWrite(mem, vector)
      case _ =>
        dbg(s"      $value -> no FIFO-on-write")
        //dbg(s"Written value is $value = $d: FIFO-on-write disallowed")
        false
    }

    // HACK! Determine start and end bounds for enable on FIFO
    def getFifoBounds(en: Option[Exp[Any]]): (Option[Exp[Any]], Option[Exp[Any]]) = en match {
      case Some( Def(And( Def(FixLeq(start,i1)), Def(FixLt(i2,end)) )) ) => (Some(start), Some(end))
      case Some( Def(And(x, y))) =>
        val xBnd = getFifoBounds(Some(x))
        val yBnd = getFifoBounds(Some(y))

        if (xBnd._1.isDefined) xBnd else yBnd

      case Some( Def(ListVector(en) )) => getFifoBounds(Some(en.head))
      case Some( Def(VectorApply(vector,index))) => getFifoBounds(Some(vector))
      case _ => (None, None)
    }

    cu.computeStages.clear() // Clear stages so we don't duplicate existing stages

    foreachSymInBlock(func){
      // NOTE: Writers always appear to occur in the associated writer controller
      // However, register reads may appear outside their corresponding controller
      case writer@ParLocalWriter(writes) if !isControlNode(writer) =>
        val rhs = writer match {case Def(d) => d; case _ => null }
        dbg(s"  $writer = $rhs [WRITER]")

        writes.foreach{case (mem, value, addrs, ens) =>
          dbg(s"    Checking if $mem write can be implemented as FIFO-on-write:")
          val addr = addrs.map{_.head}
          val writeAsFIFO = value.exists{v => useFifoOnWrite(mem, v) }

          if ((isBuffer(mem) || isFIFO(mem)) && writeAsFIFO) {
            // This entire section is a hack to support FIFO-on-write for burst loads
            val enableComputation = ens.map{e => getScheduleForAddress(stms)(Seq(e)) }.getOrElse(Nil)
            val enableSyms = enableComputation.map{case TP(s,d) => s} ++ ens
            dbg(s"    write(mem=$mem, value=$value, addr=$addr, ens=$ens):")
            dbg(s"    ens:$ens enableSyms:${enableSyms.mkString(",")}")

            val (start,end) = getFifoBounds(ens)

            val startX = start match {case start@Some(Def(_:RegRead[_])) => start; case _ => None }
            val endX = end match {case end@Some(Def(_:RegRead[_])) => end; case _ => None }

            val remoteWriteStage = FifoOnWriteStage(mem, startX, endX)
            val enStages = startX.map{s => DefStage(s) }.toList ++ endX.map{s => DefStage(s) }.toList
            dbg(s"    boundStages:")
            enStages.foreach { stage => dbg(s"      $stage") }

            allocateWrittenSRAM(writer, mem, cu, enStages ++ List(remoteWriteStage))

            //TODO consider parSRAMStore, localWrite addr will be None 
            val indexComputation = addr.map{is => getScheduleForAddress(stms)(is) }.getOrElse(Nil)
            val indexSyms = indexComputation.map{case TP(s,d) => s}
            remoteStages ++= symsOnlyUsedInWriteAddrOrEn(stms)(func.result +: func.effectful, indexSyms ++ enableSyms)
          }
          else if (isBuffer(mem)) {
            val indexComputation:List[Stm] = addr.map{is => getScheduleForAddress(stms)(is) }.getOrElse(Nil)
            val indexSyms:List[Symbol] = indexComputation.map{case TP(s,d) => s }
            val indexStages:List[PseudoStage] = indexSyms.map{s => DefStage(s) }
            val flatOpt = addr.map{is => flattenNDIndices(is, stagedDimsOf(mem.asInstanceOf[Exp[SRAM[_]]])) }
            val ad = flatOpt.map(_._1) // sym of flatten  addr
            val remoteWriteStage = ad.map{a => AddrStage(mem, a) }
            val addrStages = indexStages ++ flatOpt.map(_._2).getOrElse(Nil) ++ remoteWriteStage

            allocateWrittenSRAM(writer, mem, cu, addrStages)

            val isLocallyRead = isReadInPipe(mem, pipe)
            // Currently have to duplicate if used in both address and compute
            if (indexSyms.nonEmpty && !isLocallyRead) {
              //dbg(s"  Checking if symbols calculating ${addr.get} are used in current scope $pipe")
              remoteStages ++= symsOnlyUsedInWriteAddr(stms)(func.result +: func.effectful, indexSyms)
            }
          }
        }

      case reader@ParLocalReader(reads) if !isControlNode(reader) =>
        val rhs = reader match {case Def(d) => d; case _ => null }
        dbg(s"  $reader = $rhs [READER]")

        dbg(s"  reads:")
        reads.foreach{ case (mem,addrs,ens) =>
          val addr = addrs.map{_.head}
          dbg(s"    $mem $addr $ens")
          if (isReg(mem)) {
            prescheduleRegisterRead(mem, reader, Some(pipe))
            val isLocallyRead = isReadInPipe(mem, pipe, Some(reader))
            val isLocallyWritten = isWrittenInPipe(mem, pipe)
            //dbg(s"  isLocallyRead: $isLocallyRead, isLocallyWritten: $isLocallyWritten")
            if (!isLocallyWritten || !isLocallyRead || isInnerAccum(mem)) remoteStages += reader
          }
          else if (isBuffer(mem)) {
            val sram = mem.asInstanceOf[Exp[SRAM[_]]]

            val indexComputation:List[Stm] = addr.map{is => getScheduleForAddress(stms)(is) }.getOrElse(Nil)
            val indexSyms:List[Symbol] = indexComputation.map{case TP(s,d) => s }
            val indexStages:List[PseudoStage] = indexSyms.map{s => DefStage(s) }
            val flatOpt = addr.map{is => flattenNDIndices(is, stagedDimsOf(mem.asInstanceOf[Exp[SRAM[_]]])) }
            val ad = flatOpt.map(_._1) // sym of flatten  addr
            val remoteReadStage = ad.map{a => AddrStage(mem, a) }
            val addrStages = indexStages ++ flatOpt.map(_._2).getOrElse(Nil) ++ remoteReadStage

            allocateReadSRAM(reader, sram, cu, addrStages)
          }
        }

      case lhs@Op(rhs) => //TODO
        dbg(s"  $lhs = $rhs [OTHER]")
        visit(lhs, rhs)
      case lhs@Def(rhs) =>
        dbg(s"  $lhs = $rhs [OTHER]")
        visitFat(List(lhs), rhs)
    }

    val localCompute = stages.filter{s => (isPrimitiveNode(s) || isRegisterRead(s) || isGlobal(s) || isVector(s)) && !remoteStages.contains(s) }

    // Sanity check
    val trueComputation = localCompute.filterNot{case Exact(_) => true; case s => isRegisterRead(s)}
    if (isOuterControl(pipe) && trueComputation.nonEmpty) {
      warn(s"Outer control $pipe has compute stages: ")
      trueComputation.foreach{case lhs@Def(rhs) => warn(s"  $lhs = $rhs")}
    }

    cu.computeStages ++= localCompute.map{s => 
      val isReduce = (s match {
        case Def(RegRead(_)) => false
        case Def(RegWrite(_,_,_)) => false
        case s => reduceType(s).isDefined
      }) && !reduceSRAM
      DefStage(s, isReduce = isReduce)
    }

    dbg(s"  remoteStages for $cu:")
    remoteStages.foreach { s => 
      val Def(d) = s
      dbg(s"    $s = $d")
    }
    dbg(s"  prescheduled stages for $cu:")
    cu.computeStages.foreach { s =>
      s match {
        case s@DefStage(op, _) => dbg(s"    $s reduceType=${reduceType(op)}")
        case s => dbg(s"    $s")
      }
    }
  }

  def prescheduleBurstTransfer(pipe: Symbol, mem: Symbol, ofs: Symbol, len: Symbol, mode: OffchipMemoryMode) = {
    // Ofs and len must either be constants or results of reading registers written in another controller
    val ofsWriter = ofs match {case Def(RegRead(reg)) if writersOf(reg).nonEmpty => Some(writersOf(reg).head); case _ => None }
    val lenWriter = len match {case Def(RegRead(reg)) if writersOf(reg).nonEmpty => Some(writersOf(reg).head); case _ => None }

    var ofsCUOpt = ofsWriter.map{writer => allocateCU(writer.ctrlNode)}
    var lenCUOpt = lenWriter.map{writer => allocateCU(writer.ctrlNode)}

    if (ofsCUOpt.isEmpty && lenCUOpt.isEmpty) {
      val cu = allocateCU(pipe)
      cu.deps = Set()
      ofsCUOpt = Some(cu)
      lenCUOpt = Some(cu)
    }
    else if (lenCUOpt.isEmpty && ofsCUOpt.isDefined) lenCUOpt = ofsCUOpt
    else if (lenCUOpt.isDefined && ofsCUOpt.isEmpty) ofsCUOpt = lenCUOpt
    val lenCU = lenCUOpt.get
    val ofsCU = ofsCUOpt.get

    val dram = allocateDRAM(pipe, mem, mode)

    val mcOfs = fresh[Index]
    ofsCU.addReg(mcOfs, ScalarOut(PIRDRAMOffset(dram)))
    val ofsReg = ofs match {case Def(RegRead(reg)) => reg; case ofs => ofs }

    val mcLen = fresh[Index]
    lenCU.addReg(mcLen, ScalarOut(PIRDRAMLength(dram)))
    val lenReg = len match {case Def(RegRead(reg)) => reg; case len => len }

    ofsCU.computeStages += OpStage(PIRBypass, List(ofsReg), mcOfs)
    lenCU.computeStages += OpStage(PIRBypass, List(lenReg), mcLen)

    // HACK- no dependents of ofsCU
    mapping.values.foreach{ cus =>
      cus.foreach { cu =>
        cu.deps = cu.deps.filterNot{dep => dep == ofsCU }
      }
    }
  }

  def prescheduleGather(pipe: Symbol, mem: Symbol, local: Exp[SRAM[_]], addrs: Symbol, len: Symbol) {
    val cu = allocateCU(pipe)
    val dram = allocateDRAM(pipe, mem, MemGather)

    val n = cu.getOrElse(len){ allocateLocal(len, pipe) }
    val ctr = CUCounter(ConstReg("0i"), localScalar(n), ConstReg("1i"))
    val cc  = CChainInstance(quote(pipe)+"_cc", List(ctr))
    cu.cchains += cc
    val i = CounterReg(cc, 0)
    cu.regs += i


    val addr = allocateReadSRAM(pipe, addrs, cu, Nil)
    addr.readAddr = Some(i)

    val addrIn = fresh[Int32]
    cu.addReg(addrIn, SRAMReadReg(addr))

    val addrOut = fresh[Int32]
    cu.addReg(addrOut, VectorOut(PIRDRAMAddress(dram)))

    cu.computeStages += OpStage(PIRBypass, List(addrIn), addrOut)

    readersOf(local).foreach{reader =>
      val readerCU = allocateCU(reader.ctrlNode)
      copyIterators(readerCU, cu)

      val sram = allocateMem(local, reader.node, readerCU)
      sram.mode = FIFOOnWriteMode
      sram.writeAddr = None
      sram.writePort = Some(PIRDRAMDataIn(dram))
    }
  }

  def prescheduleScatter(pipe: Symbol, mem: Symbol, local: Exp[SRAM[_]], addrs: Symbol, len: Symbol) {
    val cu = allocateCU(pipe)
    val dram = allocateDRAM(pipe, mem, MemScatter)

    val n = cu.getOrElse(len){ allocateLocal(len, pipe) }
    val ctr = CUCounter(ConstReg("0i"), localScalar(n), ConstReg("1i"))
    val cc  = CChainInstance(quote(pipe)+"_cc", List(ctr))
    cu.cchains += cc
    val i = CounterReg(cc, 0)
    cu.regs += i

    val addr = allocateReadSRAM(pipe, addrs, cu, Nil)
    val data = allocateReadSRAM(pipe, local, cu, Nil)
    addr.readAddr = Some(i)
    data.readAddr = Some(i)

    val addrIn = fresh[Int32]
    val dataIn = fresh[Int32]
    cu.addReg(addrIn, SRAMReadReg(addr))
    cu.addReg(dataIn, SRAMReadReg(data))

    val addrOut = fresh[Int32]
    val dataOut = fresh[Int32]
    cu.addReg(addrOut, VectorOut(PIRDRAMAddress(dram)))
    cu.addReg(dataOut, VectorOut(PIRDRAMDataOut(dram)))

    cu.computeStages += OpStage(PIRBypass, List(addrIn), addrOut)
    cu.computeStages += OpStage(PIRBypass, List(dataIn), dataOut)
  }


  override protected def visit(lhs: Sym[_], rhs: Op[_]) = rhs match {
    case RegRead(reg) if isArgIn(reg) =>
      prescheduleRegisterRead(reg, lhs, None)

    case Hwblock(func,_) =>
      prescheduleStages(lhs, func)

    case UnitPipe(en, func) =>
      prescheduleStages(lhs, func)

    case UnrolledForeach(en, cchain, func, iters, valids) =>
      prescheduleStages(lhs, func)

    case UnrolledReduce(en, cchain, accum, func, reduce, iters, valids, rV) =>
      prescheduleStages(lhs, func)

    case BurstLoad(dram, fifo, ofs, ctr, i) =>
      val Def(CounterNew(start,end,step,par)) = ctr
      prescheduleBurstTransfer(lhs, dram, ofs, end, MemLoad)

    case BurstStore(dram, fifo, ofs, ctr, i) =>
      val Def(CounterNew(start,end,step,par)) = ctr
      prescheduleBurstTransfer(lhs, dram, ofs, end, MemStore)

    case Scatter(dram, local, addrs, ctr, i) =>
      val Def(CounterNew(start,end,step,par)) = ctr
      prescheduleScatter(lhs, dram, local, addrs, end)

    case Gather(dram, local, addrs, ctr, i) =>
      val Def(CounterNew(start,end,step,par)) = ctr
      prescheduleGather(lhs, dram, local, addrs, end)

    case SRAMNew(dimensions) => 

      duplicatesOf(lhs).zipWithIndex.foreach{ case (mem, i) => 
        mem match {
          case BankedMemory(dims, depth, isAccum) =>
            allocateMemoryCU(lhs)
            //val strides = s"""List(${dims.map(_.banks).mkString(",")})"""
            //val numWriters = writersOf(lhs).filter{ write => dispatchOf(write, lhs) contains i }.distinct.length
            //val numReaders = readersOf(lhs).filter{ read => dispatchOf(read, lhs) contains i }.distinct.length
          case DiagonalMemory(strides, banks, depth, isAccum) =>
            Console.println(s"NOT SUPPORTED, MAKE EXCEPTION FOR THIS!")
        }
      }

    // Something bad happened if these are still in the IR
    case _:OpForeach => throw new Exception(s"Disallowed compact op $lhs = $rhs")
    case _:OpReduce[_] => throw new Exception(s"Disallowed compact op $lhs = $rhs")
    case _:OpMemReduce[_,_] => throw new Exception(s"Disallowed compact op $lhs = $rhs")
    case _ => super.visit(lhs, rhs)
  }

  override def preprocess[S:Staged](b: Block[S]): Block[S] = {
    top = None
    mapping.clear()
    globals = Set.empty
    super.preprocess(b)
  }

  override def postprocess[S:Staged](b: Block[S]): Block[S] = {
    val cus = mapping.values.flatten
    val owner = cus.flatMap{cu => cu.srams.map{sram =>
      (sram.reader, sram.mem) -> (cu, sram)
    }}.toMap

    for (cu <- cus) {
      var nSRAMs = cu.srams.size
      var prevN = -1
      while (nSRAMs != prevN) {
        prevN = nSRAMs
        addSRAMsReadInWriteStages()
        nSRAMs = cu.srams.size
      }

      def addSRAMsReadInWriteStages() {
        val writeStages = cu.writeStages.values.flatMap(_._2).toList

        val newWrites = writeStages.foreach{
          case DefStage(reader@LocalReader(reads), _) => reads foreach {
            case (mem,addr,en) =>
              if (isBuffer(mem) && !cu.srams.exists{sram => sram.reader == reader && sram.mem == mem}) {
                val (ownerCU,orig) = owner((reader, mem))
                val copy = orig.copyMem(orig.name+"_"+quote(cu.pipe))
                cu.srams += copy

                val key = ownerCU.writeStages.keys.find{key => key.contains(orig)}

                if (key.isDefined) {
                  cu.writeStages += List(copy) -> ownerCU.writeStages(key.get)
                }
                dbg(s"Adding SRAM $copy ($reader, $mem) read in write stage of $cu")
              }
          }
          case _ =>
        }
      }


    }
    for (cu <- cus) {
      for (sram <- cu.srams) {
        initializeSRAM(sram, sram.mem, sram.reader, cu)
      }
    }

    super.postprocess(b)
  }
}
