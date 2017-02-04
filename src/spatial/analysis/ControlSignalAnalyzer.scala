package spatial.analysis

import org.virtualized.SourceContext

/**
  * (1)  Sets parent control nodes of local memories
  * (2)  Sets parent control nodes of controllers
  * (3)  Sets children control nodes of controllers
  * (4)  Sets reader control nodes of locally read memories
  * (5)  Sets writer control nodes of locally written memories
  * (6)  Flags accumulators
  * (7)  Records list of local memories
  * (8)  Records set of metapipes
  * (9)  Set parallelization factors of memory readers and writers relative to memory
  * (10) Sets written set of controllers
  * (11) Determines the top controller
  */
trait ControlSignalAnalyzer extends SpatialTraversal {
  import IR._

  override val name = "Control Signal Analyzer"

  // --- State
  var level = 0
  var controller: Option[Ctrl] = None
  var pendingReads: Map[Exp[_], List[Exp[_]]] = Map.empty
  var pendingExtReads: Set[Exp[_]] = Set.empty
  var unrollFactors: List[Const[Index]] = Nil

  var localMems: List[Exp[_]] = Nil
  var metapipes: List[Exp[_]] = Nil
  var top: Option[Exp[_]] = None

  override protected def preprocess[S:Staged](block: Block[S]) = {
    localMems = Nil
    metapipes = Nil
    top = None
    level = 0
    controller = None
    pendingExtReads = Set.empty
    pendingReads = Map.empty
    unrollFactors = Nil
    metadata.clearAll[Writers]
    metadata.clearAll[Readers]
    metadata.clearAll[Children]
    metadata.clearAll[WrittenMems]
    metadata.clearAll[ReadUsers]
    super.preprocess(block)
  }

  override protected def postprocess[S:Staged](block: Block[S]) = {
    top match {
      case Some(ctrl@Op(Hwblock(_))) =>
      case _ => new NoTopError(ctxOrHere(block.result))
    }
    if (top.isDefined) {
      // After setting all other readers/external uses, check to see if
      // any reads external to the accel block are unused. Parent for these is top
      pendingExtReads.foreach { case reader@LocalReader(reads) =>
        reads.foreach { case (mem, addr, en) =>
          if (!readersOf(mem).exists(_.node == reader)) {
            dbg(c"Adding external reader $reader to list of readers for $mem")
            readersOf(mem) = readersOf(mem) :+ (reader, (top.get, false))
          }
        }
      }
    }
    dbg("Local memories: ")
    localMems.foreach{mem => dbg(c"  $mem")}
    super.postprocess(block)
  }

  def visitCtrl(ctrl: Ctrl)(blk: => Unit): Unit = {
    level += 1
    val prevCtrl = controller
    val prevReads = pendingReads

    controller = Some(ctrl)
    blk

    controller = prevCtrl
    pendingReads = prevReads
    level -= 1
  }

  def visitCtrl(ctrl: Ctrl, inds: Seq[Bound[Index]], cchain: Exp[CounterChain])(blk: => Unit): Unit = {
    val prevUnrollFactors = unrollFactors
    val factors = parFactorsOf(cchain)

    // ASSUMPTION: Currently only parallelizes by innermost loop
    inds.zip(factors).foreach{case (i,f) => parFactorOf(i) = f }
    unrollFactors ++= factors.lastOption

    visitCtrl(ctrl)(blk)

    unrollFactors = prevUnrollFactors
  }

  /** Helper methods **/
  def appendReader(reader: Exp[_], ctrl: Ctrl) = {
    val LocalReader(reads) = reader
    reads.foreach{case (mem, addr, en) =>
      readersOf(mem) = readersOf(mem) :+ (reader, ctrl)
      dbg(c"Added reader $reader of $mem in $ctrl")
    }
  }
  def addPendingReader(reader: Exp[_]) = {
    dbg(c"Adding pending read $reader")
    pendingReads += reader -> List(reader)
  }
  def addPendingExternalReader(reader: Exp[_]) = {
    dbg(c"Added pending external read: $reader")
    pendingExtReads += reader
  }

  def addReader(reader: Exp[_], ctrl: Ctrl) = {
    if (isInnerControl(ctrl))
      appendReader(reader, ctrl)
    else
      addPendingReader(reader)
  }

  def appendWriter(writer: Exp[_], ctrl: Ctrl) = {
    val LocalWriter(writes) = writer
    writes.foreach{case (mem,value,addr,en) =>
      writersOf(mem) = writersOf(mem) :+ (writer,ctrl)      // (5)
      writtenIn(ctrl) = writtenIn(ctrl) :+ mem              // (10)
      value.foreach{v => isAccum(mem) = isAccum(mem) || (v dependsOn mem)  }              // (6)
      addr.foreach{is => isAccum(mem) = isAccum(mem) || is.exists(i => i dependsOn mem) } // (6)
      en.foreach{e => isAccum(mem) = isAccum(mem) || (e dependsOn mem) }                  // (6)

      dbg(c"Added writer $writer of $mem in $ctrl")
    }
  }

  def addWriter(writer: Exp[_], ctrl: Ctrl) = {
    if (isInnerControl(ctrl))
      appendWriter(writer, ctrl)
    else {
      val mem = LocalWriter.unapply(writer).get.head._1
      throw new ExternalWriteError(mem, writer)(ctxOrHere(writer))
    }
  }

  // (1, 7)
  def addAllocation(alloc: Exp[_], ctrl: Exp[_]) = {
    dbg(c"Setting parent of $alloc to $ctrl")
    parentOf(alloc) = ctrl
    if (isLocalMemory(alloc)) {
      dbg(c"Registered local memory $alloc")
      localMems ::= alloc
    }
  }

  // (2, 3)
  def addChild(child: Exp[_], ctrl: Exp[_]) = {
    dbg(c"Setting parent of $child to $ctrl")
    parentOf(child) = ctrl
    childrenOf(ctrl) = childrenOf(ctrl) :+ child
  }


  /** Common method for all nodes **/
  def addCommonControlData(lhs: Sym[_], rhs: Op[_]) = {
    // Set total unrolling factors of this node's scope + internal unrolling factors in this node
    unrollFactorsOf(lhs) = unrollFactors ++ parFactorsOf(lhs) // (9)

    if (controller.isDefined) {
      val ctrl: Ctrl   = controller.get
      val parent: Ctrl = if (isControlNode(lhs)) (lhs, false) else ctrl

      val readers = rhs.inputs.flatMap{sym => pendingReads.getOrElse(sym, Nil) }
      if (readers.nonEmpty) {
        readers.foreach{reader =>
          if (rhs.inputs contains reader) usersOf(reader) = usersOf(reader) :+ lhs
        }

        if (isAllocation(lhs)) {
          pendingReads += lhs -> readers
        }
        else {
          readers.foreach{reader => appendReader(reader, parent) }
        }
      }

      if (isAllocation(lhs)) addAllocation(lhs, parent.node)  // (1, 7)
      if (isReader(lhs)) addReader(lhs, parent)               // (4)
      if (isWriter(lhs)) addWriter(lhs, parent)               // (5, 6, 10)
    }
    else {
      if (isReader(lhs)) addPendingExternalReader(lhs)
      if (isAllocation(lhs) && (isArgIn(lhs) || isArgOut(lhs))) localMems ::= lhs // (7)
    }

    if (isControlNode(lhs)) {
      if (controller.isDefined) addChild(lhs, controller.get.node) // (2, 3)
      else {
        top = Some(lhs) // (11)
        pendingExtReads.foreach{reader => addPendingReader(reader) }
      }
      if (isMetaPipe(lhs)) metapipes ::= lhs // (8)
    }
  }

  def addChildDependencyData(lhs: Sym[_], block: Block[_]): Unit = if (isOuterControl(lhs)) {
    withInnerStms(availStms diff block.inputs.map(stmOf)) {
      val children = childrenOf(lhs)
      dbg(c"parent: $lhs")
      val allDeps = Map(children.map { child =>
        dbg(c"  child: $child")
        val schedule = getCustomSchedule(availStms, List(child))
        schedule.foreach{stm => dbg(c"    $stm")}
        child -> schedule.flatMap(_.lhs).filter { e => children.contains(e) && e != child }
      }: _*)

      dbg(c"dependencies: ")
      allDeps.foreach { case (child, deps) =>
        val fringe = deps diff deps.flatMap(allDeps)
        ctrlDepsOf(child) = fringe.toSet
        dbg(c"  $child ($fringe)")
      }
    }
  }

  override protected def visit(lhs: Sym[_], rhs: Op[_]): Unit = {
    addCommonControlData(lhs, rhs)
    analyze(lhs, rhs)
  }

  protected def analyze(lhs: Sym[_], rhs: Op[_]): Unit = rhs match {
    case Hwblock(blk) =>
      visitCtrl((lhs,false)){ visitBlock(blk) }
      addChildDependencyData(lhs, blk)

    case UnitPipe(blk) =>
      visitCtrl((lhs,false)){ visitBlock(blk) }
      addChildDependencyData(lhs, blk)

    case OpForeach(cchain,func,iters) =>
      visitCtrl((lhs,false),iters,cchain){ visitBlock(func) }
      addChildDependencyData(lhs, func)

    case OpReduce(cchain,accum,map,ld,reduce,store,rV,iters) =>
      visitCtrl((lhs,false), iters, cchain){
        visitBlock(map)
        visitCtrl((lhs,true)) {
          visitBlock(ld)
          visitBlock(reduce)
          visitBlock(store)
        }
      }

      // HACK: Handle the one case where we allow scalar communication between blocks
      if (isOuterControl(lhs)) map.result match {
        case read @ Op(RegRead(reg)) =>
          readersOf(reg) = readersOf(reg) :+ (read, (lhs,true))
        case _ => // Nothing
      }

      isAccum(accum) = true
      parentOf(accum) = lhs
      addChildDependencyData(lhs, map)

    case OpMemReduce(cchainMap,cchainRed,accum,map,ldRes,ldAcc,reduce,store,rV,itersMap,itersRed) =>
      visitCtrl((lhs,false), itersMap, cchainMap) {
        visitBlock(map)
        visitCtrl((lhs,true), itersRed, cchainRed) {
          visitBlock(ldAcc)
          visitBlock(ldRes)
          visitBlock(reduce)
          visitBlock(store)
        }
      }
      isAccum(accum) = true
      parentOf(accum) = lhs
      addChildDependencyData(lhs, map)

    case e: CoarseBurst[_,_] =>
      e.iters.foreach{i => parFactorOf(i) = int32(1) }
      parFactorsOf(lhs).headOption.foreach{p => parFactorOf(e.iters.last) = p }

    case e: Scatter[_] => parFactorOf(e.i) = parFactorsOf(lhs).head
    case e: Gather[_]  => parFactorOf(e.i) = parFactorsOf(lhs).head

    case _ => super.visit(lhs, rhs)
  }

}

