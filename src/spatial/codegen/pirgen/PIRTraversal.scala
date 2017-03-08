package spatial.codegen.pirgen

import spatial.SpatialConfig
import spatial.api._
import spatial.analysis.SpatialTraversal
import argon.traversal.{CompilerPass, BlockTraversal}

import scala.collection.mutable
import scala.reflect.runtime.universe._

import spatial.SpatialExp

trait PIRTraversal extends SpatialTraversal {
  val IR: SpatialExp with PIRCommonExp
  import IR.{println => _, _}

  val LANES = SpatialConfig.numLanes         // Number of SIMD lanes per CU
  val REDUCE_STAGES = (Math.log(LANES)/Math.log(2)).toInt  // Number of stages required to reduce across all lanes

  var listing = false
  var listingSaved = false
  var tablevel = 0 // Doesn't change tab level with traversal of block
  def dbgs(s:Any):Unit = dbg(s"${"  "*tablevel}${if (listing) "- " else ""}$s")
  def dbgblk[T](s:String)(block: =>T) = {
    dbgs(s + " {")
    tablevel += 1
    listingSaved = listing
    listing = false
    val res = block
    tablevel -=1
    dbgs(s"}")
    listing = listingSaved
    res
  }
  def dbgl[T](s:String)(block: => T) = {
    dbgs(s)
    tablevel += 1
    listing = true
    val res = block
    listing = false
    tablevel -=1
    res
  }
  def dbgpcu(pcu:PseudoComputeUnit) = {
    dbgblk(s"${qdef(pcu.pipe)} -> ${pcu.name}") {
      dbgl(s"regs:") {
        for ((s,r) <- pcu.regTable) { dbgs(s"$s -> $r") }
      }
      dbgl(s"cchains:") {
        pcu.cchains.foreach { cchain => dbgs(s"$cchain") }
      }
      dbgl(s"MEMs:") {
        for ((sym, mem) <- pcu.mems) {
          dbgs(s"""$mem (reader: ${mem.reader}, mode: ${mem.mode}) ${qdef(sym)}""")
        }
      }
      dbgl(s"Write stages:") {
        for ((k,v) <- pcu.writeStages) {
          dbgs(s"Memories: " + k.mkString(", "))
          for (stage <- v._2) dbgs(s"  $stage")
        }
      }
      dbgl(s"Read stages:") {
        pcu.readStages.foreach { case (k,v) =>
          dbgs(s"Memories:" + k.mkString(", "))
          for (stage <- v._2) dbgs(s"  $stage")
        }
      }
      dbgl(s"Compute stages:") { pcu.computeStages.foreach { stage => dbgs(s"$stage") } }
    }
  }


  def quote(x: Symbol):String = s"${composed.get(x).fold("") {o => s"${quote(o)}_"} }$x"
  def qdef(lhs:Symbol):String = {
    val rhs = lhs match {
      case Def(e:UnrolledForeach) => 
        s"UnrolledForeach(iters=(${e.iters.mkString(",")}), valids=(${e.valids.mkString(",")}))"
      case Def(e:UnrolledReduce[_,_]) => 
        s"UnrolledReduce(iters=(${e.iters.mkString(",")}), valids=(${e.valids.mkString(",")}))"
      //case Def(BurstLoad(dram, fifo, ofs, ctr, i)) =>
        //s"BurstLoad(dram=$dram, fifo=$fifo, ofs=$ofs, ctr=$ctr, i=$i)"
      //case Def(BurstStore(dram, fifo, ofs, ctr, i)) =>
        //s"BurstStore(dram=$dram, fifo=$fifo, ofs=$ofs, ctr=$ctr, i=$i)"
      case Def(d) if isControlNode(lhs) => s"${d.getClass.getSimpleName}(binds=${d.binds})"
      case Op(rhs) => s"$rhs"
      case Def(rhs) => s"$rhs"
      case lhs if (composed.contains(lhs)) => s"$lhs -> ${qdef(composed(lhs))}"
      case lhs => s"$lhs"
    }
    s"$lhs = $rhs"
  }

  // HACK: Skip parallel pipes in PIR gen
  def parentHack(x: Symbol): Option[Symbol] = parentOf(x) match {
    case Some(pipe@Def(_:ParallelPipe)) => parentHack(pipe)
    case parentOpt => parentOpt
  }

  // --- Allocating
  def decomposed:mutable.Map[Symbol, Seq[(String, Symbol)]]
  def composed:mutable.Map[Symbol, Symbol]

  def compose(dsym:Symbol) = composed.get(dsym).getOrElse(dsym)

  def decompose[T](sym:Symbol, fields:Seq[T])(implicit ev:TypeTag[T]):Seq[Symbol] = {
    decomposeWithFields(sym, fields).map(_._2)
  }

  def decomposeWithFields[T](sym:Symbol, fields:Seq[T])(implicit ev:TypeTag[T]):Seq[(String, Symbol)] = {
    if (fields.size<=1) {
      Seq(("N/A", sym))
    } else {
      decomposed.getOrElseUpdate(sym, {
        fields.map { f => 
          val (field, dsym) = f match {
            case field if typeOf[T] =:= typeOf[String] => 
              (field.asInstanceOf[String], fresh[Int32]) 
            case (field, exp) if typeOf[T] =:= typeOf[(String, Symbol)] => 
              (field.asInstanceOf[String], exp.asInstanceOf[Symbol])
          }
          composed += dsym -> sym
          (field, dsym)
        }
      })
    }
  }

  def decomposeBus(bus:Bus, mem:Symbol) = bus match {
    case BurstCmdBus => decompose(mem, Seq("offset", "size", "isLoad"))
    case BurstAckBus => decompose(mem, Seq()) 
    case bus:BurstDataBus[_] => decompose(mem, Seq()) 
    case bus:BurstFullDataBus[_] => decompose(mem, Seq("data", "valid")) //?
    case GatherAddrBus => decompose(mem, Seq())
    case bus:GatherDataBus[_] => decompose(mem, Seq())
    case bus:ScatterCmdBus[_] => decompose(mem, Seq("data", "valid")) //?
    case ScatterAckBus => List(mem) 
    case _ => throw new Exception(s"Don't know how to decompose bus ${bus}")
  }

  def decompose(sym:Symbol):Seq[Symbol] = sym match {
    case Def(StreamInNew(bus)) => decomposeBus(bus, sym) 
    case Def(StreamOutNew(bus)) => decomposeBus(bus, sym)
    case Def(SimpleStruct(elems)) => decompose(sym, elems)
    case mem if isMem(mem) => List(mem) // TODO:Handle mem of composite type here
    case ParLocalReader(reads) => 
      val (mem, _, _) = reads.head
      val fields =  mem.tp.typeArguments(0) match {
        case s:StructType[_] => s.fields.map(_._1)
        case _ => Seq()
      }
      decompose(sym, decompose(mem, fields))
    case ParLocalWriter(writes) =>
      val (mem, value, _, _) = writes.head
      val fieldNames = value.flatMap{ value => decomposed.get(value)}.getOrElse(Seq()).map(_._1)
      decompose(sym, fieldNames)
    case _ => decomposed.get(sym).map{ _.map(_._2) }.getOrElse(Seq(sym))
  }

  def getMatchedDecomposed(dele:Symbol, ele:Symbol):Symbol = {
    val i = decompose(compose(dele)).indexOf(dele)
    decompose(ele)(i)
  }

  def isLocallyWritten(dmem:Symbol, dreader:Symbol, cu:Option[PseudoComputeUnit] = None) = {
    if (isArgIn(compose(dmem)) || isStreamIn(dmem) || isGetDRAMAddress(dmem)) {
      false
    } else {
      val writer = writerOf(compose(dmem))
      val pipe = parentOf(compose(dreader)).get
      writer.ctrlNode == pipe && cu.fold(true) { cu => cu.pipe == pipe }
    }
  }

  /*
   * @return readers of dmem that are remotely read 
   * */
  def getRemoteReaders(dmem:Symbol, dwriter:Symbol):List[Symbol] = {
    val mem = compose(dmem)
    if (isStreamOut(mem)) { getReaders(mem) }
    else {
      readersOf(dmem).filter { reader => reader.ctrlNode!=parentOf(compose(dwriter)).get }.map(_.node)
    }
  }

  def getReaders(dmem:Symbol):List[Symbol] = {
    val mem = compose(dmem)
    if (isGetDRAMAddress(mem)) List(mem) // GetDRAMAddress is both the mem and the reader
    else if (isStreamOut(mem)) fringeOf(mem).map(f => List(f)).getOrElse(Nil) // Fringe is a reader of the stramOut
    else readersOf(mem).map{_.node}
  }

  def writerOf(mem:Symbol):Access = {
    val writers = writersOf(mem)
    assert(writers.size==1, s"Plasticine only support single writer mem=${qdef(mem)} writers=${writers.mkString(",")}")
    writers.head
  }

  def globals:mutable.Set[GlobalComponent]

  def allocateDRAM(ctrl: Symbol, dram: Symbol, mode: OffchipMemoryMode): MemoryController = {
    val region = OffChip(quote(dram))
    val mc = MemoryController(quote(ctrl), region, mode, parentHack(ctrl).get)
    globals += mc
    globals += region
    mc
  }

  def allocateDRAM(dram:Symbol): OffChip = { //FIXME
    val region = OffChip(quote(dram))
    if (!globals.contains(region)) {
      globals += region
      region
    } else {
      region
    }
  }

  private def allocateReg(reg: Symbol, pipe: Symbol, read: Option[Symbol] = None, write: Option[Symbol] = None): LocalComponent = {
    val isLocallyRead = isReadInPipe(reg, pipe, read)
    val isLocallyWritten = isWrittenInPipe(reg, pipe, write)

    if (isLocallyRead && isLocallyWritten && isInnerAccum(reg)) {
      ReduceReg()
    }
    else if (isLocallyRead && isLocallyWritten && isAccum(reg)) {
      val init = ConstReg(extractConstant(resetValue(reg.asInstanceOf[Exp[Reg[Any]]])))
      AccumReg(init)
    }
    else if (!isLocallyRead) {
      if (isUnitPipe(pipe) || isInnerAccum(reg)) {
        val bus = CUScalar(quote(reg))
        globals += bus
        ScalarOut(bus)
      }
      else {
        val bus = CUVector(quote(reg))
        globals += bus
        VectorOut(bus)
      }
    }
    else if (!isLocallyWritten) {
      if (isWrittenByUnitPipe(reg) || isInnerAccum(reg)) {
        val bus = CUScalar(quote(reg))
        globals += bus
        ScalarIn(bus).asInstanceOf[LocalComponent]  // Weird scala type error here
      }
      else {
        val bus = CUVector(quote(reg))
        globals += bus
        VectorIn(bus).asInstanceOf[LocalComponent]  // Weird scala type error here
      }
    }
    else {
      TempReg()
    }
  }

  def allocateLocal(x: Symbol): LocalComponent = x match {
    case c if isConstant(c) => ConstReg(extractConstant(x))
    case _ => TempReg()
  }

  def const(x:Symbol) = x match {
    case c if isConstant(c) => ConstReg(extractConstant(x))
    case _ => throw new Exception(s"${qdef(x)} ${x.tp} is not a constant")
  }

  def foreachSymInBlock(b: Block[Any])(func: Sym[_] => Unit) = {
    def sfunc(stms:Seq[Stm]) = {
      stms.foreach { case Stm(lhs, rhs) => func(lhs.head) }
    }
    traverseStmsInBlock(b, sfunc _)
  }

  def copyIterators(destCU: AbstractComputeUnit, srcCU: AbstractComputeUnit): Map[CUCChain,CUCChain] = {
    if (destCU != srcCU) {
      val cchainCopies = srcCU.cchains.toList.map{
        case cc@CChainCopy(name, inst, owner) => cc -> cc
        case cc@CChainInstance(name, ctrs)    => cc -> CChainCopy(name, cc, srcCU)
        case cc@UnitCChain(name)              => cc -> CChainCopy(name, cc, srcCU)
      }
      val cchainMapping = Map[CUCChain,CUCChain](cchainCopies:_*)

      destCU.cchains ++= cchainCopies.map(_._2)
      dbgs(s"copying iterators from ${srcCU.name} to ${destCU.name} destCU.cchains:[${destCU.cchains.mkString(",")}]")

      // FIXME: Shouldn't need to use getOrElse here
      srcCU.iterators.foreach{ case (iter,CounterReg(cchain,idx)) =>
        val reg = CounterReg(cchainMapping.getOrElse(cchain,cchain),idx)
        destCU.addReg(iter, reg)
        //dbgs(s"$iter -> $reg")
      }
      srcCU.valids.foreach{case (iter, ValidReg(cchain,idx)) =>
        val reg = ValidReg(cchainMapping.getOrElse(cchain,cchain), idx) 
        destCU.addReg(iter, reg)
        //dbgs(s"$iter -> $reg")
      }
      cchainMapping
    }
    else Map.empty[CUCChain,CUCChain]
  }

  // Build a schedule as usual, except for depencies on write addresses
  def symsOnlyUsedInWriteAddr(stms: Seq[Stm])(results: Seq[Symbol], exps: List[Symbol]) = {
    def mysyms(rhs: Symbol) = rhs match {
      case Def(rhs) => rhs match {
        case LocalWriter(writes) =>
          val addrs = writes.flatMap{case (mem,value,addr,en) => addr.filterNot{a => a == value }}
          syms(rhs) filterNot (addrs contains _)
        case _ => syms(rhs)
      }
      case _ => syms(rhs)
    }
    val scopeIndex = makeScopeIndex(stms)
    def deps(x: Symbol) = orderedInputs(mysyms(x), scopeIndex)

    val xx = schedule(results.flatMap(deps))(t => deps(t))

    exps.filterNot{
      case sym: Sym[_] => xx.exists(_.lhs.contains(sym))
      case b: Bound[_] => false
      case c: Const[_] => false
    }
  }

  // HACK: Rip apart the block, looking only for true data dependencies and necessary effects dependencies
  def symsOnlyUsedInWriteAddrOrEn(stms: Seq[Stm])(results: Seq[Symbol], exps: List[Symbol]) = {
    def mysyms(rhs: Any) = rhs match {
      case Def(d) => d match {
        case SRAMStore(sram,dims,is,ofs,data,en) => syms(sram) ++ syms(data) //++ syms(es)
        case ParSRAMStore(sram,addr,data,ens) => syms(sram) ++ syms(data) //++ syms(es)
        case FIFOEnq(fifo, data, en)          => syms(fifo) ++ syms(data)
        case ParFIFOEnq(fifo, data, ens) => syms(fifo) ++ syms(data)
        case _ => d.allInputs //syms(d)
      }
      case _ => syms(rhs)
    }
    val scopeIndex = makeScopeIndex(stms)
    def deps(x: Any) = orderedInputs(mysyms(x), scopeIndex)

    val xx = schedule(results.flatMap(deps))(t => deps(t))

    //xx.reverse.foreach{case TP(s,d) => debug(s"  $s = $d")}

    // Remove all parts of schedule which are used to calculate values
    exps.filterNot{
      case sym: Sym[_] => xx.exists(_.lhs.contains(sym))
      case b: Bound[_] => false
      case c: Const[_] => false
    }
  }

  /*
   * @param allStms all statements in which to search for symbols
   * @param results produced expression to be considered
   * @param effectful effectful symbols to be considered for searching input symbols
   * Get symbols used to calculate results and effectful excluding symbols that are 
   * - used for address calculation and control calculation for FIFOs. 
   * - Doesn't correspond to a PIR stage
   * - Read access of local memories if the read access is not locally used 
   *   (used by stms that produces results or effectful) 
   * */
  def expsUsedInCalcExps(allStms:Seq[Stm])(results:Seq[Symbol], effectful:Seq[Symbol] = Nil):List[Symbol] = {
    dbgblk(s"symsUsedInCalcExps"){
      def mysyms(lhs: Any):List[Symbol] = lhs match {
        case Def(d) => mysyms(d) 
        case SRAMStore(sram,dims,is,ofs,data,en) => syms(sram) ++ syms(data) //++ syms(es)
        case ParSRAMStore(sram,addr,data,ens) => syms(sram) ++ syms(data) //++ syms(es)
        case FIFOEnq(fifo, data, en)          => syms(fifo) ++ syms(data)
        case ParFIFOEnq(fifo, data, ens) => syms(fifo) ++ syms(data)
        case FIFODeq(fifo, en, zero) => syms(fifo) ++ syms(zero)
        case ParFIFODeq(fifo, ens, zero) => syms(fifo) ++ syms(zero)
        case StreamEnq(stream, data, en) => syms(stream) ++ syms(data)
        case ParStreamEnq(stream, data, ens) => syms(stream) ++ syms(data)
        case StreamDeq(stream, ens, zero) => syms(stream) ++ syms(zero)
        case ParStreamDeq(stream, ens, zero) => syms(stream) ++ syms(zero)
        case d:Def => d.allInputs //syms(d)
        case _ => syms(lhs)
      }
      val scopeIndex = makeScopeIndex(allStms)
      def deps(x: Symbol):List[Stm] = orderedInputs(mysyms(x), scopeIndex)

      dbgs(s"results=${results.mkString(",")}")
      dbgs(s"effectful=[${effectful.mkString(",")}]")
      var stms = schedule((results++effectful).flatMap(deps) ++ orderedInputs(effectful, scopeIndex)){ next => 
        dbgs(s"$next inputs=[${deps(next).mkString(",")}] isStage=${isStage(next)}")
        deps(next)
      }
      stms = stms.filter{ case TP(s,d) => isStage(s) }
      stms.map{case TP(s,d) => s}
    }
  }

  def symsUsedInCalcExps(allStms:Seq[Stm])(results:Seq[Symbol], effectful:Seq[Symbol] = Nil):List[Sym[_]] = {
    expsUsedInCalcExps(allStms)(results, effectful).collect{ case s:Sym[_] => s }
  }

  def filterStmUseExp(allStms:Seq[Stm])(exp:Symbol):Seq[Stm] = {
    allStms.filter { case TP(s,d) => d.allInputs.contains(exp) }
  }

  def getStms(pipe:Symbol) = pipe match {
    case Def(Hwblock(func,_)) => blockContents(func)
    case Def(UnitPipe(en, func)) => blockContents(func)
    case Def(UnrolledForeach(en, cchain, func, iters, valids)) => blockContents(func)
    case Def(UnrolledReduce(en, cchain, accum, func, reduce, iters, valids, rV)) => blockContents(func)
    case _ => throw new Exception(s"Don't know how to get stms pipe=${qdef(pipe)}")
  }

  // --- Transformation functions
  def removeComputeStages(cu: CU, remove: Set[Stage]) {
    val ctx = ComputeContext(cu)
    val stages = mutable.ArrayBuffer[Stage]()
    stages ++= ctx.stages
    cu.computeStages.clear()

    stages.foreach{
      case stage@MapStage(op,ins,outs) if !remove.contains(stage) =>
        stage.ins = ins.map{case LocalRef(i,reg) => ctx.refIn(reg) }
        stage.outs = outs.map{case LocalRef(i,reg) => ctx.refOut(reg) }
        ctx.addStage(stage)

      case stage@ReduceStage(op,init,in,acc) if !remove.contains(stage) =>
        ctx.addStage(stage)

      case _ => // This stage is being removed! Ignore it!
    }
  }


  def swapBus(cus: Iterable[CU], orig: GlobalBus, swap: GlobalBus) = cus.foreach{cu =>
    cu.allStages.foreach{stage => swapBus_stage(stage) }
    cu.srams.foreach{sram => swapBus_sram(sram) }
    cu.cchains.foreach{cc => swapBus_cchain(cc) }

    def swapBus_stage(stage: Stage): Unit = stage match {
      case stage@MapStage(op, ins, outs) =>
        stage.ins = ins.map{ref => swapBus_ref(ref) }
        stage.outs = outs.map{ref => swapBus_ref(ref) }
      case stage:ReduceStage => // No action
    }
    def swapBus_ref(ref: LocalRef): LocalRef = ref match {
      case LocalRef(i,reg) => LocalRef(i, swapBus_reg(reg))
    }
    def swapBus_reg(reg: LocalComponent): LocalComponent = (reg,swap) match {
      case (ScalarIn(`orig`),  swap: ScalarBus) => ScalarIn(swap)
      case (ScalarOut(`orig`), swap: ScalarBus) => ScalarOut(swap)
      case (VectorIn(`orig`),  swap: VectorBus) => VectorIn(swap)
      case (VectorOut(`orig`), swap: VectorBus) => VectorOut(swap)

      case (ScalarIn(x), _)  if x != orig => reg
      case (ScalarOut(x), _) if x != orig => reg
      case (VectorIn(x), _)  if x != orig => reg
      case (VectorOut(x), _) if x != orig => reg

      case (_:LocalPort[_], _) => throw new Exception(s"$swap is not a valid replacement for $orig")
      case _ => reg
    }

    def swapBus_writeAddr(addr: WriteAddr): WriteAddr = addr match {
      case reg: LocalComponent => swapBus_reg(reg).asInstanceOf[WriteAddr]
      case _ => addr
    }
    def swapBus_readAddr(addr: ReadAddr): ReadAddr = addr match {
      case reg: LocalComponent => swapBus_reg(reg).asInstanceOf[ReadAddr]
      case _ => addr
    }
    def swapBus_localScalar(sc: LocalScalar): LocalScalar = sc match {
      case reg: LocalComponent => swapBus_reg(reg).asInstanceOf[LocalScalar]
      case _ => sc
    }

    def swapBus_sram(sram: CUMemory): Unit = {
      sram.writePort = sram.writePort.map{case `orig` => swap; case vec => vec}
      sram.readPort = sram.readPort.map{case `orig` => swap; case vec => vec}
      sram.readAddr = sram.readAddr.map{reg => swapBus_readAddr(reg)}
      sram.writeAddr = sram.writeAddr.map{reg => swapBus_writeAddr(reg)}
      sram.writeStart = sram.writeStart.map{reg => swapBus_localScalar(reg)}
      sram.writeEnd = sram.writeEnd.map{reg => swapBus_localScalar(reg)}
    }
    def swapBus_cchain(cchain: CUCChain): Unit = cchain match {
      case cc: CChainInstance => cc.counters.foreach{ctr => swapBus_ctr(ctr)}
      case _ => // No action
    }
    def swapBus_ctr(ctr: CUCounter): Unit = {
      ctr.start = swapBus_localScalar(ctr.start)
      ctr.end = swapBus_localScalar(ctr.end)
      ctr.stride = swapBus_localScalar(ctr.stride)
    }
  }


  def swapCUs(cus: Iterable[CU], mapping: Map[ACU, ACU]): Unit = cus.foreach {cu =>
    cu.cchains.foreach{cchain => swapCU_cchain(cchain) }
    cu.parent = cu.parent.map{parent => mapping.getOrElse(parent,parent) }
    cu.deps = cu.deps.map{dep => mapping.getOrElse(dep, dep) }
    cu.srams.foreach{sram => swapCU_sram(sram) }
    cu.allStages.foreach{stage => stage.inputMems.foreach(swapCU_reg) }

    def swapCU_cchain(cchain: CUCChain): Unit = cchain match {
      case cc: CChainCopy => cc.owner = mapping.getOrElse(cc.owner,cc.owner)
      case _ => // No action
    }
    def swapCU_reg(reg: LocalComponent): Unit = reg match {
      case CounterReg(cc,i) => swapCU_cchain(cc)
      case ValidReg(cc,i) => swapCU_cchain(cc)
      case _ =>
    }

    def swapCU_sram(sram: CUMemory) {
      sram.swapWrite.foreach(swapCU_cchain)
      sram.swapRead.foreach(swapCU_cchain)
      sram.writeCtrl.foreach(swapCU_cchain)
      sram.readAddr.foreach{case reg:LocalComponent => swapCU_reg(reg); case _ => }
      sram.writeAddr.foreach{case reg:LocalComponent => swapCU_reg(reg); case _ => }
    }
  }

  // --- Context for creating/modifying CUs
  abstract class CUContext(val cu: ComputeUnit) {
    private val refs = mutable.HashMap[Symbol,LocalRef]()
    private var readAccums: Set[AccumReg] = Set.empty

    def pipe: Symbol
    def stages: mutable.ArrayBuffer[Stage]
    def addStage(stage: Stage): Unit
    def isWriteContext: Boolean
    def init(): Unit

    def isUnit = cu.isUnit

    def stageNum: Int = stages.count{case stage:MapStage => true; case _ => false} + 1
    def controlStageNum: Int = controlStages.length
    def prevStage: Option[Stage] = stages.lastOption
    def mapStages: Iterator[MapStage] = stages.iterator.collect{case stage:MapStage => stage}

    def controlStages: mutable.ArrayBuffer[Stage] = cu.controlStages
    def addControlStage(stage: Stage): Unit = cu.controlStages += stage

    def addReg(x: Symbol, reg: LocalComponent) {
      //debug(s"  $x -> $reg")
      cu.addReg(x, reg)
    }
    def addRef(x: Symbol, ref: LocalRef) { refs += x -> ref }
    def getReg(x: Symbol): Option[LocalComponent] = cu.get(x)
    def reg(x: Symbol): LocalComponent = {
      cu.get(x).getOrElse(throw new Exception(s"No register defined for $x in $cu"))
    }

    // Add a stage which bypasses x to y
    def bypass(x: LocalComponent, y: LocalComponent) {
      val stage = MapStage(PIRBypass, List(refIn(x)), List(refOut(y)))
      addStage(stage)
    }

    def ref(reg: LocalComponent, out: Boolean, stage: Int = stageNum): LocalRef = reg match {
      // If the previous stage computed the read address for this load, use the registered output
      // of the memory directly. Otherwise, use the previous stage
      case MemLoadReg(sram) =>
        /*debug(s"Referencing SRAM $sram in stage $stage")
        debug(s"  Previous stage: $prevStage")
        debug(s"  SRAM read addr: ${sram.readAddr}")*/
        if (prevStage.isEmpty || sram.mode == FIFOMode)
          LocalRef(-1, reg)
        else {
          if (sram.mode != FIFOMode && sram.readAddr.isDefined) {
            if (prevStage.get.outputMems.contains(sram.readAddr.get))
              LocalRef(-1, reg)
            else
              LocalRef(stage-1,reg)
          }
          else
            throw new Exception(s"No address defined for SRAM $sram")
        }

      case reg: CounterReg if isWriteContext && prevStage.isEmpty =>
        //debug(s"Referencing counter $reg in first write stage")
        LocalRef(-1, reg)

      case reg: AccumReg if isUnreadAccum(reg) =>
        //debug(s"First reference to accumulator $reg in stage $stage")
        readAccums += reg
        LocalRef(stage, reg)
      case _ if out =>
        //debug(s"Referencing output register $reg in stage $stage")
        LocalRef(stage, reg)
      case _ =>
        //debug(s"Referencing input register $reg in stage $stage")
        LocalRef(stage-1, reg)
    }
    def refIn(reg: LocalComponent, stage: Int = stageNum) = ref(reg, out = false, stage)
    def refOut(reg: LocalComponent, stage: Int = stageNum) = ref(reg, out = true, stage)

    def addOutputFor(e: Symbol)(prev: LocalComponent, out: LocalComponent): Unit = addOutput(prev, out, Some(e))
    def addOutput(prev: LocalComponent, out: LocalComponent): Unit = addOutput(prev, out, None)
    def addOutput(prev: LocalComponent, out: LocalComponent, e: Option[Symbol]): Unit = {
      mapStages.find{stage => stage.outputMems.contains(prev) } match {
        case Some(stage) =>
          stage.outs ::= refOut(out, mapStages.indexOf(stage) + 1)
        case None =>
          bypass(prev, out)
      }
      if (e.isDefined) addReg(e.get, out)
      else cu.regs += out // No mapping, only list
    }

    // Get memory in this CU associated with the given reader
    def mem(mem: Symbol, reader: Symbol): CUMemory = {
      memOption(mem,reader).getOrElse(throw new Exception(s"Cannot find sram ($mem,$reader) in cu $cu"))
    }

    def memOption(mem: Symbol, reader: Symbol): Option[CUMemory] = {
      cu.srams.find{sram => sram.mem == mem && sram.reader == reader}
    }

    // A CU can have multiple SRAMs for a given mem symbol, one for each local read
    def memories(mem: Symbol) = cu.srams.filter(_.mem == mem)


    // HACK: Keep track of first read of accum reg (otherwise can use the wrong stage)
    private def isUnreadAccum(reg: LocalComponent) = reg match {
      case reg: AccumReg => !readAccums.contains(reg)
      case _ => false
    }
  }


  case class ComputeContext(override val cu: ComputeUnit) extends CUContext(cu) {
    def stages = cu.computeStages
    def addStage(stage: Stage) { cu.computeStages += stage }
    def isWriteContext = false
    def pipe = cu.pipe
    def init() = {}
  }
  case class WriteContext(override val cu: ComputeUnit, pipe: Symbol, srams: List[CUMemory]) extends CUContext(cu) {
    def init() { cu.writeStages += srams -> mutable.ArrayBuffer[Stage]() }
    def stages = cu.writeStages(srams)
    def addStage(stage: Stage) { cu.writeStages(srams) += stage }
    def isWriteContext = true
  }
  case class ReadContext(override val cu: ComputeUnit, pipe: Symbol, srams: List[CUMemory]) extends CUContext(cu) {
    def init() { cu.readStages += srams -> mutable.ArrayBuffer[Stage]() }
    def stages = cu.readStages(srams)
    def addStage(stage: Stage) { cu.readStages(srams) += stage }
    def isWriteContext = false 
  }

  // Given result register type A, reroute to type B as necessary
  def propagateReg(exp: Symbol, a: LocalComponent, b: LocalComponent, ctx: CUContext):LocalComponent = (a,b) match {
    case (a:ScalarOut, b:ScalarOut) => a
    case (a:VectorOut, b:VectorOut) => a
    case (a:FeedbackDataReg, b:FeedbackDataReg) => a
    case (_:ReduceReg | _:AccumReg, _:ReduceReg | _:AccumReg) => a

    // Propagating from read addr wire to another read addr wire is ok (but should usually never happen)
    case (a:ReadAddrWire, b:ReadAddrWire) => ctx.addOutputFor(exp)(a,b); b
    case (a,b) if !isReadable(a) => throw new Exception(s"Cannot propagate for $exp from output-only $a")
    case (a,b) if !isWritable(b) => throw new Exception(s"Cannot propagate for $exp to input-only $b")

    // Prefer reading original over a new temporary register
    case (a, b:TempReg) => a

    // Special cases: don't propagate to write/read wires from counters or constants
    case (_:CounterReg | _:ConstReg, _:WriteAddrWire | _:ReadAddrWire) => a

    // General case for outputs: Don't add mapping for exp to output
    case (a,b) if !isReadable(b) => ctx.addOutput(a,b); b

    // General case for all others: add output + mapping
    case (a,b) => ctx.addOutputFor(exp)(a,b); b
  }

}
