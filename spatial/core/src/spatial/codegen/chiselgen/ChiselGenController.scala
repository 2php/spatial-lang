package spatial.codegen.chiselgen

import argon.core._
import spatial.aliases._
import spatial.metadata._
import spatial.nodes._
import spatial.utils._
import spatial.targets.DE1._


trait ChiselGenController extends ChiselGenCounter{
  var hwblock_sym: List[Exp[Any]] = List()

  /* Set of control nodes which already have their enable signal emitted */
  var enDeclaredSet = Set.empty[Exp[Any]]

  /* Set of control nodes which already have their done signal emitted */
  var doneDeclaredSet = Set.empty[Exp[Any]]

  var instrumentCounters: List[(Exp[_], Int)] = List()

  /* For every iter we generate, we track the children it may be used in.
     Given that we are quoting one of these, look up if it has a map entry,
     and keep getting parents of the currentController until we find a match or 
     get to the very top
  */

  private def emitNestedLoop(cchain: Exp[CounterChain], iters: Seq[Bound[Index]])(func: => Unit): Unit = {
    for (i <- iters.indices)
      open(src"$cchain($i).foreach{case (is,vs) => is.zip(vs).foreach{case (${iters(i)},v) => if (v) {")

    func

    iters.indices.foreach{_ => close("}}}") }
  }

  def emitParallelizedLoop(iters: Seq[Seq[Bound[Index]]], cchain: Exp[CounterChain], suffix: String = "") = {
    val Def(CounterChainNew(counters)) = cchain

    iters.zipWithIndex.foreach{ case (is, i) =>
      if (is.size == 1) { // This level is not parallelized, so assign the iter as-is
        emit(src"${is(0)}${suffix}.raw := ${counters(i)}${suffix}(0).r")
        val w = cchainWidth(counters(i))
        emitGlobalWire(src"val ${is(0)}${suffix} = Wire(new FixedPoint(true,$w,0))")
      } else { // This level IS parallelized, index into the counters correctly
        is.zipWithIndex.foreach{ case (iter, j) =>
          emit(src"${iter}${suffix}.raw := ${counters(i)}${suffix}($j).r")
          val w = cchainWidth(counters(i))
          emitGlobalWire(src"val ${iter}${suffix} = Wire(new FixedPoint(true,$w,0))")
        }
      }
    }
  }

  def createInstrumentation(lhs: Sym[Any]): Unit = {
    if (spatialConfig.enableInstrumentation) {
      val ctx = s"${lhs.ctx}"
      emitInstrumentation(src"""// Instrumenting $lhs, context: ${ctx}, depth: ${controllerStack.length}""")
      emitInstrumentation(src"""val ${lhs}_cycles = Module(new InstrumentationCounter())""")
      emitInstrumentation(src"${lhs}_cycles.io.enable := ${swap(lhs,En)}")
      emitInstrumentation(src"""val ${lhs}_iters = Module(new InstrumentationCounter())""")
      emitInstrumentation(src"${lhs}_iters.io.enable := Utils.risingEdge(${swap(lhs, Done)})")
      emitInstrumentation(src"""io.argOuts(io_numArgOuts_reg + io_numArgIOs_reg + 2 * ${instrumentCounters.length}).bits := ${lhs}_cycles.io.count""")
      emitInstrumentation(src"""io.argOuts(io_numArgOuts_reg + io_numArgIOs_reg + 2 * ${instrumentCounters.length}).valid := ${swap(hwblock_sym.head, Done)}""")
      emitInstrumentation(src"""io.argOuts(io_numArgOuts_reg + io_numArgIOs_reg + 2 * ${instrumentCounters.length} + 1).bits := ${lhs}_iters.io.count""")
      emitInstrumentation(src"""io.argOuts(io_numArgOuts_reg + io_numArgIOs_reg + 2 * ${instrumentCounters.length} + 1).valid := ${swap(hwblock_sym.head, Done)}""")
      instrumentCounters = instrumentCounters :+ (lhs, controllerStack.length)
    }
  }

  def emitValids(lhs: Exp[Any], cchain: Exp[CounterChain], iters: Seq[Seq[Bound[Index]]], valids: Seq[Seq[Bound[Bit]]], suffix: String = "") {
    // Need to recompute ctr data because of multifile 5
    val ctrs = cchain match { case Def(CounterChainNew(ctrs)) => ctrs }
    val counter_data = ctrs.map{ ctr => ctr match {
      case Def(CounterNew(start, end, step, par)) => 
        val w = cchainWidth(ctr)
        (start,end) match { 
          case (Exact(s), Exact(e)) => (src"${s}.FP(true, $w, 0)", src"${e}.FP(true, $w, 0)", src"$step", {src"$par"}.split('.').take(1)(0), src"$w")
          case _ => (src"$start", src"$end", src"$step", {src"$par"}.split('.').take(1)(0), src"$w")
        }
      case Def(Forever()) => 
        ("0.S", "999.S", "1.S", "1", "32") 
    }}

    valids.zip(iters).zipWithIndex.foreach{ case ((layer,count), i) =>
      layer.zip(count).foreach{ case (v, c) =>
        // emitGlobalWire(s"//${validPassMap}")
        emitGlobalModule(src"val ${v}${suffix} = Wire(Bool())")
        emit(src"${v}${suffix} := Mux(${counter_data(i)._3} >= 0.S, ${c}${suffix} < ${counter_data(i)._2}, ${c}${suffix} > ${counter_data(i)._2}) // TODO: Generate these inside counter")
        if (styleOf(lhs) == MetaPipe & childrenOf(lhs).length > 1) {
          emitGlobalModule(src"""val ${v}${suffix}_chain = Module(new NBufFF(${childrenOf(lhs).size}, 1))""")
          childrenOf(lhs).indices.drop(1).foreach{i => emitGlobalModule(src"""val ${v}${suffix}_chain_read_$i = ${v}${suffix}_chain.read(${i}) === 1.U(1.W)""")}
          withStream(getStream("BufferControlCxns")) {
            childrenOf(lhs).zipWithIndex.foreach{ case (s, i) =>
              emitGlobalWireMap(src"${s}_done", "Wire(Bool())")
              emitGlobalWireMap(src"${s}_en", "Wire(Bool())")
              emit(src"""${v}${suffix}_chain.connectStageCtrl(${swap(s, Done)}.D(1,rr), ${swap(s,En)}, List($i))""")
            }
          }
          emit(src"""${v}${suffix}_chain.chain_pass(${v}${suffix}, ${lhs}_sm.io.output.ctr_inc)""")
        }
      }
    }
    // Console.println(s"map is $validPassMap")
  }

  def createValidsPassMap(lhs: Exp[Any], cchain: Exp[CounterChain], iters: Seq[Seq[Bound[Index]]], valids: Seq[Seq[Bound[Bit]]], suffix: String = "") {
    if (levelOf(lhs) != InnerControl) {
      valids.zip(iters).zipWithIndex.foreach{ case ((layer,count), i) =>
        layer.zip(count).foreach{ case (v, c) =>
          validPassMap += ((v, suffix) -> childrenOf(lhs))
        }
      }
    }
  }

  def emitValidsDummy(iters: Seq[Seq[Bound[Index]]], valids: Seq[Seq[Bound[Bit]]], suffix: String = "") {
    valids.zip(iters).zipWithIndex.foreach{ case ((layer,count), i) =>
      layer.zip(count).foreach{ case (v, c) =>
        emit(src"val ${v}${suffix} = true.B")
      }
    }
  }

  def emitRegChains(controller: Sym[Any], inds:Seq[Bound[Index]], cchain:Exp[CounterChain]) = {
    val stages = childrenOf(controller)
    val Def(CounterChainNew(counters)) = cchain
    var maxw = 32 min counters.map(cchainWidth(_)).reduce{_*_}
    val par = counters.map{case Def(CounterNew(_,_,_,Exact(p))) => p}
    val ctrMapping = par.indices.map{i => par.dropRight(par.length - i).sum}
    inds.zipWithIndex.foreach { case (idx,index) =>
      val this_counter = ctrMapping.filter(_ <= index).length - 1
      val this_width = cchainWidth(counters(this_counter))
      // emitGlobalModule(src"""val ${idx}_chain = Module(new NBufFF(${stages.size}, ${this_width}))""")
      // stages.indices.foreach{i => emitGlobalModule(src"""val ${idx}_chain_read_$i = ${idx}_chain.read(${i})""")}
      withStream(getStream("BufferControlCxns")) {
        stages.zipWithIndex.foreach{ case (s, i) =>
          emitGlobalWireMap(src"${s}_done", "Wire(Bool())")
          emitGlobalWireMap(src"${s}_en", "Wire(Bool())")
          emit(src"""${idx}_chain.connectStageCtrl(${swap(s, Done)}.D(1,rr), ${swap(s, En)}, List($i))""")
        }
      }
      emit(src"""${idx}_chain.chain_pass(${idx}, ${controller}_sm.io.output.ctr_inc)""")
      // Associate bound sym with both ctrl node and that ctrl node's cchain
    }
  }

  def allocateRegChains(controller: Sym[Any], inds:Seq[Bound[Index]], cchain:Exp[CounterChain]) = {
    val stages = childrenOf(controller)
    val Def(CounterChainNew(counters)) = cchain
    var maxw = 32 min counters.map(cchainWidth(_)).reduce{_*_}
    val par = counters.map{case Def(CounterNew(_,_,_,Exact(p))) => p}
    val ctrMapping = par.indices.map{i => par.dropRight(par.length - i).sum}
    inds.zipWithIndex.foreach { case (idx,index) =>
      val this_counter = ctrMapping.filter(_ <= index).length - 1
      val this_width = cchainWidth(counters(this_counter))
      emitGlobalModule(src"""val ${idx}_chain = Module(new NBufFF(${stages.size}, ${this_width}))""")
      stages.indices.foreach{i => emitGlobalModule(src"""val ${idx}_chain_read_$i = ${idx}_chain.read(${i})""")}
    }
  }

  protected def isStreamChild(lhs: Exp[_]): Boolean = {
    var nextLevel: Option[Exp[_]] = Some(lhs)
    var result = false
    while (nextLevel.isDefined) {
      if (styleOf(nextLevel.get) == StreamPipe) {
        result = true
        nextLevel = None
      } else {
        nextLevel = parentOf(nextLevel.get)
      }
    }
    result
  }

  protected def findCtrlAncestor(lhs: Exp[_]): Option[Exp[_]] = {
    var nextLevel: Option[Exp[_]] = Some(lhs)
    var keep_looking = true
    while (keep_looking & nextLevel.isDefined) {
      nextLevel.get match {
        case Def(_:UnrolledReduce[_,_]) => nextLevel = None; keep_looking = false
        case Def(_:UnrolledForeach) => nextLevel = None; keep_looking = false
        case Def(_:Hwblock) => nextLevel = None; keep_looking = false
        case Def(_:UnitPipe) => nextLevel = None; keep_looking = false
        case Def(_:ParallelPipe) => nextLevel = None; keep_looking = false 
        case Def(_:StateMachine[_]) => keep_looking = false 
        case _ => nextLevel = parentOf(nextLevel.get)
      }
    }
    nextLevel
  }

  protected def hasCopiedCounter(lhs: Exp[_]): Boolean = {
    if (parentOf(lhs).isDefined) {
      val parent = parentOf(lhs).get
      val hasCChain = parent match {
        case Def(UnitPipe(_,_)) => false
        case _ => true
      }
      if (styleOf(parent) == StreamPipe & hasCChain) {
        true
      } else {
        false
      }
    } else {
      false
    }
  }

  protected def emitStandardSignals(lhs: Exp[_]): Unit = {
    emitGlobalWireMap(src"""${swap(lhs, Done)}""", """Wire(Bool())""")
    emitGlobalWireMap(src"""${swap(lhs, En)}""", """Wire(Bool())""")
    emitGlobalWireMap(src"""${swap(lhs, BaseEn)}""", """Wire(Bool())""")
    emitGlobalWireMap(src"""${lhs}_mask""", """Wire(Bool())""")
    emitGlobalWireMap(src"""${lhs}_resetter""", """Wire(Bool())""")
    emitGlobalWireMap(src"""${lhs}_datapath_en""", """Wire(Bool())""")
    emitGlobalWireMap(src"""${lhs}_ctr_trivial""", """Wire(Bool())""")
  }

  protected def isImmediateStreamChild(lhs: Exp[_]): Boolean = {
    var result = false
    if (parentOf(lhs).isDefined) {
      if (styleOf(parentOf(lhs).get) == StreamPipe) {
        result = true
      } else {
        result = false
      }
    } else {
      result = false
    }
    result
  }

  override protected def quote(e: Exp[_]): String = e match {
    case Def(_: Hwblock) => "RootController"
    case _ => super.quote(e) // All others
  }

  override protected def name(s: Dyn[_]): String = s match {
    case Def(_: Hwblock)          => s"RootController"
    case Def(_: UnitPipe)         => s"${s}_UnitPipe"
    case Def(_: OpForeach)        => s"${s}_ForEach"
    case Def(_: OpReduce[_])      => s"${s}_Reduce"
    case Def(_: OpMemReduce[_,_]) => s"${s}_MemReduce"
    case Def(_: Switch[_])        => s"${s}_switch"
    case Def(_: SwitchCase[_])    => s"${s}_switchcase"
    case _ => super.name(s)
  } 

  private def beneathForever(lhs: Sym[Any]):Boolean = { // TODO: Make a counterOf() method that will just grab me Some(counter) that I can check
    if (parentOf(lhs).isDefined) {
      val parent = parentOf(lhs).get
      parent match {
        case Def(Hwblock(_,isFrvr)) => true
        case Def(e: ParallelPipe) => false
        case Def(e: UnitPipe) => false
        case Def(e: OpForeach) => e.cchain match {
          case Def(Forever()) => true
          case _ => false
        }
        case Def(e: OpReduce[_]) => e.cchain match {
          case Def(Forever()) => true
          case _ => false
        }
        case Def(e: OpMemReduce[_,_]) => false
        case Def(e: UnrolledForeach) => e.cchain match {
          case Def(Forever()) => true
          case _ => false
        }
        case Def(e: UnrolledReduce[_,_]) => e.cchain match {
          case Def(Forever()) => true
          case _ => false
        }
        case _ => false 
      }
    } else {
      false
    }
  }

  // Method for looking ahead to see if any streamEnablers need to be remapped fifo signals
  def remappedEns(node: Exp[Any], ens: List[Exp[Any]]): String = {
    var previousLevel: Exp[_] = node
    var nextLevel: Option[Exp[_]] = Some(parentOf(node).get)
    var result = ens.map(quote)
    while (nextLevel.isDefined) {
      if (styleOf(nextLevel.get) == StreamPipe) {
        nextLevel.get match {
          case Def(UnrolledForeach(_,_,_,_,e)) => 
            ens.foreach{ my_en =>
              e.foreach{ their_en =>
                if (src"${my_en}" == src"${their_en}" & !src"${my_en}".contains("true")) {
                  // Hacky way to avoid double-suffixing
                  if (!src"$my_en".contains(src"_copy${previousLevel}")) {  
                    result = result.filter{a => !src"$a".contains(src"$my_en")} :+ src"${my_en}_copy${previousLevel}"
                  }
                }
              }
            }
          case _ => // do nothing
          // case Def(UnitPipe(e,_)) => 
          //   ens.map{ my_en => 
          //     e.map{ their_en => 
          //       if (src"${my_en}" == src"${their_en}" & !src"${my_en}".contains("true")) {
          //         result = result.filter{a => src"$a" != src"$my_en"} :+ src"${my_en}_copy${previousLevel}"
          //       }
          //     }
          //   }
        }
        nextLevel = None
      } else {
        previousLevel = nextLevel.get
        nextLevel = parentOf(nextLevel.get)
      }
    }
    result.mkString("&")

  }


  def getStreamEnablers(c: Exp[Any]): String = {
      // If we are inside a stream pipe, the following may be set
      // Add 1 to latency of fifo checks because SM takes one cycle to get into the done state
      val lat = bodyLatency.sum(c)
      val readiers = listensTo(c).distinct.map{ pt => pt.memory match {
        case fifo @ Def(FIFONew(size)) => // In case of unaligned load, a full fifo should not necessarily halt the stream
          pt.access match {
            case Def(FIFODeq(_,en)) => src"(~$fifo.io.empty.D(${lat} + 1, rr) | ~${remappedEns(pt.access,List(en))})"
            case Def(ParFIFODeq(_,ens)) => src"""(~$fifo.io.empty.D(${lat} + 1, rr) | ~(${remappedEns(pt.access, ens.toList)}))"""
          }
        case fifo @ Def(FILONew(size)) => src"~$fifo.io.empty.D(${lat} + 1, rr)"
        case fifo @ Def(StreamInNew(bus)) => bus match {
          case SliderSwitch => ""
          case _ => src"${swap(fifo, Valid)}"
        }
        case fifo => src"${fifo}_en" // parent node
      }}.filter(_ != "").mkString(" & ")
      val holders = pushesTo(c).distinct.map { pt => pt.memory match {
        case fifo @ Def(FIFONew(size)) => // In case of unaligned load, a full fifo should not necessarily halt the stream
          pt.access match {
            case Def(FIFOEnq(_,_,en)) => src"(~$fifo.io.full.D(${lat} + 1, rr) | ~${remappedEns(pt.access,List(en))})"
            case Def(ParFIFOEnq(_,_,ens)) => src"""(~$fifo.io.full.D(${lat} + 1, rr) | ~(${remappedEns(pt.access, ens.toList)}))"""
          }
        case fifo @ Def(FILONew(size)) => // In case of unaligned load, a full fifo should not necessarily halt the stream
          pt.access match {
            case Def(FILOPush(_,_,en)) => src"(~$fifo.io.full.D(${lat} + 1, rr) | ~${remappedEns(pt.access,List(en))})"
            case Def(ParFILOPush(_,_,ens)) => src"""(~$fifo.io.full.D(${lat} + 1, rr) | ~(${remappedEns(pt.access, ens.toList)}))"""
          }
        case fifo @ Def(StreamOutNew(bus)) => src"${swap(fifo,Ready)}"
        case fifo @ Def(BufferedOutNew(_, bus)) => src"" //src"~${fifo}_waitrequest"        
      }}.filter(_ != "").mkString(" & ")

      val hasHolders = if (holders != "") "&" else ""
      val hasReadiers = if (readiers != "") "&" else ""

      src"${hasHolders} ${holders} ${hasReadiers} ${readiers}"

  }

  def emitController(sym:Sym[Any], cchain:Option[Exp[CounterChain]], iters:Option[Seq[Bound[Index]]], isFSM: Boolean = false) {

    val hasStreamIns = listensTo(sym).distinct.map{_.memory}.exists{
      case Def(StreamInNew(SliderSwitch)) => false
      case Def(StreamInNew(_))            => true
      case _ => false
    }

    val isInner = levelOf(sym) match {
      case InnerControl => true
      case OuterControl => false
      case _ => false
    }
    val smStr = if (isInner) {
      if (isStreamChild(sym) & hasStreamIns ) {
        "Streaminner"
      } else {
        "Innerpipe"
      }
    } else {
      styleOf(sym) match {
        case MetaPipe => s"Metapipe"
        case StreamPipe => "Streampipe"
        case InnerPipe => throw new spatial.OuterLevelInnerStyleException(src"$sym")
        case SeqPipe => s"Seqpipe"
        case ForkJoin => s"Parallel"
        case ForkSwitch => s"Match"
      }
    }

    emit(src"""//  ---- ${if (isInner) {"INNER: "} else {"OUTER: "}}Begin ${smStr} $sym Controller ----""")

    /* State Machine Instatiation */
    // IO
    var hasForever = false
    disableSplit = true
    val numIter = if (cchain.isDefined) {
      val Def(CounterChainNew(counters)) = cchain.get
      var maxw = 32 min counters.map(cchainWidth(_)).reduce{_*_}
      counters.zipWithIndex.map {case (ctr,i) =>
        ctr match {
          case Def(CounterNew(start, end, step, par)) => 
            val w = cchainWidth(ctr)
            (start, end, step, par) match {
              /*
                  (e - s) / (st * p) + Mux( (e - s) % (st * p) === 0, 0, 1)
                     1          1              1          1    
                          1                         1
                          .                                     1
                          .            1
                                     1
                  Issue # 199           
              */
              case (Exact(s), Exact(e), Exact(st), Exact(p)) => 
                emit(src"val ${sym}${i}_range = Utils.getRetimed(${e}.S(${32 min 2*w}.W) - ${s}.S(${32 min 2*w}.W), 1)")
                emit(src"val ${sym}${i}_jump = Utils.getRetimed(${st}.S(${32 min 2*w}.W) *-* ${p}.S(${32 min 2*w}.W), 1)")
                emit(src"val ${sym}${i}_hops = (${sym}${i}_range /-/ ${sym}${i}_jump).asUInt")
                emit(src"val ${sym}${i}_leftover = ${sym}${i}_range %-% ${sym}${i}_jump")
                emit(src"val ${sym}${i}_evenfit = Utils.getRetimed(${sym}${i}_leftover.asUInt === 0.U, 1)")
                emit(src"val ${sym}${i}_adjustment = Utils.getRetimed(Mux(${sym}${i}_evenfit, 0.U, 1.U), 1)")
                emitGlobalWire(src"val ${sym}_level${i}_iters = Wire(UInt((${32 min 2*w}.W)))")
              case (Exact(s), Exact(e), _, Exact(p)) => 
                emit("// TODO: Figure out how to make this one cheaper!")
                emit(src"val ${sym}${i}_range = Utils.getRetimed(${e}.S(${32 min 2*w}.W) - ${s}.S(${32 min 2*w}.W), 1)")
                emit(src"val ${sym}${i}_jump = Utils.getRetimed(${step} *-* ${p}.S(${w}.W), 1)")
                emit(src"val ${sym}${i}_hops = (${sym}${i}_range /-/ ${sym}${i}_jump).asUInt")
                emit(src"val ${sym}${i}_leftover = ${sym}${i}_range %-% ${sym}${i}_jump")
                emit(src"val ${sym}${i}_evenfit = Utils.getRetimed(${sym}${i}_leftover.asUInt === 0.U, 1)")
                emit(src"val ${sym}${i}_adjustment = Utils.getRetimed(Mux(${sym}${i}_evenfit, 0.U, 1.U), 1)")
                emitGlobalWire(src"val ${sym}_level${i}_iters = Wire(UInt((${32 min 2*w}.W)))")
              case _ => 
                emit(src"val ${sym}${i}_range = Utils.getRetimed(${end} - ${start}, 1)")
                emit(src"val ${sym}${i}_jump = Utils.getRetimed(${step} *-* ${par}, 1)")
                emit(src"val ${sym}${i}_hops = ${sym}${i}_range /-/ ${sym}${i}_jump")
                emit(src"val ${sym}${i}_leftover = ${sym}${i}_range %-% ${sym}${i}_jump")
                emit(src"val ${sym}${i}_evenfit = Utils.getRetimed(${sym}${i}_leftover === 0.U, 1)")
                emit(src"val ${sym}${i}_adjustment = Utils.getRetimed(Mux(${sym}${i}_evenfit, 0.U, 1.U), 1)")
                emitGlobalWire(src"val ${sym}_level${i}_iters = Wire(UInt((32.W)))")
            }
            emit(src"""${sym}_level${i}_iters := Utils.getRetimed(${sym}${i}_hops + ${sym}${i}_adjustment, 1).r""")
            src"${sym}_level${i}_iters"
          case Def(Forever()) =>
            hasForever = true
            // need to change the outer pipe counter interface!!
            emitGlobalWire(src"val ${sym}_level${i}_iters = Wire(UInt(32.W))")
            emit(src"""${sym}_level${i}_iters := 0.U // Count forever""")
            src"${sym}_level${i}_iters"
        }
      }
    } else { 
      List("1.U") // Unit pipe:
    }
    disableSplit = false
    // Special match if this is HWblock, there is no forever cchain, just the forever flag
    sym match {
      case Def(Hwblock(_,isFrvr)) => if (isFrvr) hasForever = true
      case _ =>;
    }

    val constrArg = if (isInner) {s"${isFSM}"} else {s"${childrenOf(sym).length}, isFSM = ${isFSM}"}

    val lat = bodyLatency.sum(sym)
    emitStandardSignals(sym)
    createInstrumentation(sym)

    // Pass done signal upward and grab your own en signals if this is a switchcase child
    if (parentOf(sym).isDefined) {
      parentOf(sym).get match {
        case Def(SwitchCase(_)) => 
          emit(src"""${swap(parentOf(sym).get, Done)} := ${swap(sym, Done)}""")
          val streamAddition = getStreamEnablers(sym)
          emit(src"""${swap(sym, En)} := ${swap(parentOf(sym).get, En)} ${streamAddition}""")  
          emit(src"""${swap(sym, Resetter)} := ${swap(parentOf(sym).get, Resetter)}""")

        case _ =>
          // no sniffing to be done
      }
    }

    val stw = sym match{case Def(StateMachine(_,_,_,_,_,s)) => bitWidth(s.tp); case _ => 32}
    val ctrdepth = if (cchain.isDefined) {cchain.get match {case Def(CounterChainNew(ctrs)) => ctrs.length; case _ => 0}} else 0
    val static = if (cchain.isDefined) {
      cchain.get match {
        case Def(CounterChainNew(ctrs)) => 
          ctrs.map{c => c match {
            case Def(CounterNew(s, e, str, p)) => 
              val s_static = s match {
                case Def(RegRead(reg)) => reg match {case Def(ArgInNew(_)) => true; case _ => false}
                case b: Bound[_] => false
                case _ => isGlobal(s)
              }
              val e_static = e match {
                case Def(RegRead(reg)) => reg match {case Def(ArgInNew(_)) => true; case _ => false}
                case b: Bound[_] => false
                case _ => isGlobal(s)
              }
              val str_static = str match {
                case Def(RegRead(reg)) => reg match {case Def(ArgInNew(_)) => true; case _ => false}
                case b: Bound[_] => false
                case _ => isGlobal(s)
              }
              s_static && e_static && str_static
          }}.reduce{_&&_}
      }
    } else true
    emit(s"""// This is now global: val ${quote(sym)}_retime = ${lat} // Inner loop? ${isInner}, II = ${iiOf(sym)}""")
    emitGlobalWire(s"""val ${quote(sym)}_retime = ${lat} // Inner loop? ${isInner}, II = ${iiOf(sym)}""")
    emit(src"// This is now global: val ${sym}_sm = Module(new ${smStr}(${constrArg.mkString}, ctrDepth = $ctrdepth, stateWidth = ${stw}, retime = ${sym}_retime, staticNiter = $static))")
    emitGlobalModule(src"val ${sym}_sm = Module(new ${smStr}(${constrArg.mkString}, ctrDepth = $ctrdepth, stateWidth = ${stw}, retime = ${sym}_retime, staticNiter = $static))")
    emit(src"""${sym}_sm.io.input.enable := ${swap(sym, En)} & retime_released""")
    if (isFSM) {
      emitGlobalWireMap(src"${sym}_inhibitor", "Wire(Bool())") // hacky but oh well
      emit(src"""${swap(sym, Done)} := (${sym}_sm.io.output.done & ~${swap(sym, Inhibitor)}.D(2,rr)).D(${sym}_retime,rr)""")      
    } else {
      emit(src"""${swap(sym, Done)} := ${sym}_sm.io.output.done.D(${sym}_retime,rr)""")
    }
    emitGlobalWireMap(src"""${swap(sym, RstEn)}""", """Wire(Bool())""") // TODO: Is this legal?
    emit(src"""${swap(sym, RstEn)} := ${sym}_sm.io.output.rst_en // Generally used in inner pipes""")
    emit(src"""${sym}_sm.io.input.numIter := (${numIter.mkString(" *-* ")}).raw.asUInt // Unused for inner and parallel""")
    emit(src"""${sym}_sm.io.input.rst := ${swap(sym, Resetter)} // generally set by parent""")

    if (isStreamChild(sym) & hasStreamIns & beneathForever(sym)) {
      emit(src"""${swap(sym, DatapathEn)} := ${swap(sym, En)} & ~${swap(sym, CtrTrivial)} // Immediate parent has forever counter, so never mask out datapath_en""")    
    } else if ((isStreamChild(sym) & hasStreamIns & cchain.isDefined)) { // for FSM or hasStreamIns, tie en directly to datapath_en
      emit(src"""${swap(sym, DatapathEn)} := ${swap(sym, En)} & ~${swap(sym, Done)} & ~${swap(sym, CtrTrivial)} ${getNowValidLogic(sym)}""")  
    } else if (isFSM) { // for FSM or hasStreamIns, tie en directly to datapath_en
      emit(src"""${swap(sym, DatapathEn)} := ${swap(sym, En)} & ~${swap(sym, Done)} & ~${swap(sym, CtrTrivial)} & ~${sym}_sm.io.output.done ${getNowValidLogic(sym)}""")  
    } else if ((isStreamChild(sym) & hasStreamIns)) { // _done used to be commented out but I'm not sure why
      emit(src"""${swap(sym, DatapathEn)} := ${swap(sym, En)} & ~${swap(sym, Done)} & ~${swap(sym, CtrTrivial)} ${getNowValidLogic(sym)} """)  
    } else {
      emit(src"""${swap(sym, DatapathEn)} := ${sym}_sm.io.output.ctr_inc & ~${swap(sym, Done)} & ~${swap(sym, CtrTrivial)}""")
    }
    
    /* Counter Signals for controller (used for determining done) */
    if (smStr != "Parallel" & smStr != "Streampipe") {
      if (cchain.isDefined) {
        if (!isForever(cchain.get)) {
          emitGlobalWireMap(src"""${swap(cchain.get, En)}""", """Wire(Bool())""") 
          sym match { 
            case Def(n: UnrolledReduce[_,_]) => // These have II
              emit(src"""${swap(cchain.get, En)} := ${sym}_sm.io.output.ctr_inc & ${swap(sym, IIDone)}""")
            case Def(n: UnrolledForeach) => 
              if (isStreamChild(sym) & hasStreamIns) {
                emit(src"${swap(cchain.get, En)} := ${swap(sym, DatapathEn)} & ${swap(sym, IIDone)} & ~${swap(sym, Inhibitor)} ${getNowValidLogic(sym)}") 
              } else {
                emit(src"${swap(cchain.get, En)} := ${sym}_sm.io.output.ctr_inc & ${swap(sym, IIDone)}// Should probably also add inhibitor")
              }             
            case _ => // If parent is stream, use the fine-grain enable, otherwise use ctr_inc from sm
              if (isStreamChild(sym) & hasStreamIns) {
                emit(src"${swap(cchain.get, En)} := ${swap(sym, DatapathEn)} & ~${swap(sym, Inhibitor)} ${getNowValidLogic(sym)}") 
              } else {
                emit(src"${swap(cchain.get, En)} := ${sym}_sm.io.output.ctr_inc // Should probably also add inhibitor")
              } 
          }
          emit(src"""// ---- Counter Connections for $smStr ${sym} (${cchain.get}) ----""")
          val ctr = cchain.get
          if (isStreamChild(sym) & hasStreamIns) {
            emit(src"""${swap(ctr, Resetter)} := ${swap(sym, Done)}.D(1,rr) // Do not use rst_en for stream kiddo""")
          } else {
            emit(src"""${swap(ctr, Resetter)} := ${swap(sym, RstEn)}.D(0,rr) // changed on 9/19""")
          }
          if (isInner) { 
            // val dlay = if (spatialConfig.enableRetiming || spatialConfig.enablePIRSim) {src"1 + ${sym}_retime"} else "1"
            emit(src"""${sym}_sm.io.input.ctr_done := Utils.delay(${swap(ctr, Done)}, 1)""")
          }

        }
      } else {
        emit(src"""// ---- Single Iteration for $smStr ${sym} ----""")
        if (isInner) { 
          emitGlobalWireMap(src"${sym}_ctr_en", "Wire(Bool())")
          if (isStreamChild(sym) & hasStreamIns) {
            emit(src"""${sym}_sm.io.input.ctr_done := Utils.delay(${swap(sym, En)} & ~${swap(sym, Done)}, 1) // Disallow consecutive dones from stream inner""")
            emit(src"""${swap(sym, CtrEn)} := ${swap(sym, Done)} // stream kiddo""")
          } else {
            emit(src"""${sym}_sm.io.input.ctr_done := Utils.delay(Utils.risingEdge(${sym}_sm.io.output.ctr_inc), 1)""")
            emit(src"""${swap(sym, CtrEn)} := ${sym}_sm.io.output.ctr_inc""")            
          }
        } else {
          emit(s"// TODO: How to properly emit for non-innerpipe unit counter?  Probably doesn't matter")
        }
      }
    }

    val hsi = if (isInner & hasStreamIns) "true.B" else "false.B"
    val hf = if (hasForever) "true.B" else "false.B"
    emit(src"${sym}_sm.io.input.hasStreamIns := $hsi")
    emit(src"${sym}_sm.io.input.forever := $hf")

    // emitChildrenCxns(sym, cchain, iters, isFSM)
    /* Create reg chain mapping */
    if (iters.isDefined) {
      if (smStr == "Metapipe" & childrenOf(sym).length > 1) {
        childrenOf(sym).foreach{ 
          case stage @ Def(s:UnrolledForeach) => cchainPassMap += (s.cchain -> stage)
          case stage @ Def(s:UnrolledReduce[_,_]) => cchainPassMap += (s.cchain -> stage)
          case _ =>
        }
        iters.get.foreach{ idx => 
          itersMap += (idx -> childrenOf(sym).toList)
        }

      }
    }
  }

  protected def emitChildrenCxns(sym:Sym[Any], cchain:Option[Exp[CounterChain]], iters:Option[Seq[Bound[Index]]], isFSM: Boolean = false): Unit = {
    val hasStreamIns = listensTo(sym).distinct.map{_.memory}.exists{
      case Def(StreamInNew(SliderSwitch)) => false
      case Def(StreamInNew(_))            => true
      case _ => false
    }

    val isInner = levelOf(sym) match {
      case InnerControl => true
      case OuterControl => false
      case _ => false
    }
    val smStr = if (isInner) {
      if (isStreamChild(sym) & hasStreamIns ) {
        "Streaminner"
      } else {
        "Innerpipe"
      }
    } else {
      styleOf(sym) match {
        case MetaPipe => s"Metapipe"
        case StreamPipe => "Streampipe"
        case InnerPipe => throw new spatial.OuterLevelInnerStyleException(src"$sym")
        case SeqPipe => s"Seqpipe"
        case ForkJoin => s"Parallel"
        case ForkSwitch => s"Match"
      }
    }

    /* Control Signals to Children Controllers */
    if (!isInner) {
      emit(src"""// ---- Begin $smStr ${sym} Children Signals ----""")
      childrenOf(sym).zipWithIndex.foreach { case (c, idx) =>
        if (smStr == "Streampipe" & cchain.isDefined) {
          emit(src"""${sym}_sm.io.input.stageDone(${idx}) := ${swap(src"${cchain.get}_copy${c}", Done)};""")
        } else {
          emit(src"""${sym}_sm.io.input.stageDone(${idx}) := ${swap(c, Done)};""")
        }

        emit(src"""${sym}_sm.io.input.stageMask(${idx}) := ${swap(c, Mask)};""")

        val streamAddition = getStreamEnablers(c)

        emit(src"""${swap(c, BaseEn)} := ${sym}_sm.io.output.stageEnable(${idx}).D(1,rr) & ~${swap(c, Done)}.D(1,rr)""")  
        emit(src"""${swap(c, En)} := ${swap(c, BaseEn)} ${streamAddition}""")  

        // If this is a stream controller, need to set up counter copy for children
        if (smStr == "Streampipe" & cchain.isDefined) {
          emitGlobalWireMap(src"""${swap(src"${cchain.get}_copy${c}", En)}""", """Wire(Bool())""") 
          val Def(CounterChainNew(ctrs)) = cchain.get
          // val stream_respeck = c match {case Def(UnitPipe(_,_)) => getNowValidLogic(c); case _ => ""}          
          val unitKid = c match {case Def(UnitPipe(_,_)) => true; case _ => false}
          val snooping = getNowValidLogic(c).replace(" ", "") != ""
          val innerKid = levelOf(c) == InnerControl
          val signalHandle = if (unitKid & innerKid & snooping) { // If this is a unit pipe that listens, we just need to snoop the now_valid & _ready overlap
            src"true.B ${getReadyLogic(c)} ${getNowValidLogic(c)}"
          } else if (innerKid) { // Otherwise, use the done & ~inhibit
            src"${swap(c, Done)} /* & ~${swap(c, Inhibitor)} */"
          } else {
            src"${swap(c, Done)}"
          }
          // emit copied cchain is now responsibility of child
          // emitCounterChain(cchain.get, ctrs, src"_copy$c")
          emit(src"""${swap(src"${cchain.get}_copy${c}", En)} := ${signalHandle}""")
          emit(src"""${swap(src"${cchain.get}_copy${c}", Resetter)} := ${sym}_sm.io.output.rst_en.D(1,rr)""")
        }
        if (c match { case Def(StateMachine(_,_,_,_,_,_)) => true; case _ => false}) { // If this is an fsm, we want it to reset with each iteration, not with the reset of the parent
          emit(src"""${swap(c, Resetter)} := ${sym}_sm.io.output.rst_en | ${swap(c, Done)}.D(1,rr)""")
        } else {
          emit(src"""${swap(c, Resetter)} := ${sym}_sm.io.output.rst_en""")  
        }
        
      }
    }
    /* Emit reg chains */
    if (iters.isDefined) {
      if (smStr == "Metapipe" & childrenOf(sym).length > 1) {
        emitRegChains(sym, iters.get, cchain.get)
      }
    }

  }

  protected def emitCopiedCChain(self: Exp[_]): Unit = {
    val parent = parentOf(self)
    if (parent.isDefined) {
      if (levelOf(parent.get) != InnerControl && styleOf(parent.get) == StreamPipe) {
        parent.get match {
          case Def(UnrolledForeach(_,cchain,_,_,_)) => 
            val Def(CounterChainNew(ctrs)) = cchain
            emitCounterChain(cchain, src"_copy${self}")
            // connectCtrTrivial(cchain, src"_copy${self}")
          case Def(UnrolledReduce(_,cchain,_,_,_,_)) => 
            val Def(CounterChainNew(ctrs)) = cchain
            emitCounterChain(cchain, src"_copy${self}")
            // connectCtrTrivial(cchain, src"_copy${self}")
          case _ => // Emit nothing
        }
      }
    }

  }

  protected def connectCtrTrivial(lhs: Exp[_], suffix: String = ""): Unit = {
    val ctrl = usersOf(lhs).head._1
    if (suffix != "") { // emitting for a copied ctr
      emit(src"// this trivial signal will be assigned multiple times but each should be the same")
      emit(src"""${swap(ctrl, CtrTrivial)} := ${swap(controllerStack.tail.head, CtrTrivial)}.D(1,rr) | ${lhs}${suffix}_stops.zip(${lhs}${suffix}_starts).map{case (stop,start) => (stop-start).asUInt}.reduce{_*-*_}.asUInt === 0.U""")
    } else {
      emit(src"""${swap(ctrl, CtrTrivial)} := ${swap(controllerStack.tail.head, CtrTrivial)}.D(1,rr) | ${lhs}${suffix}_stops.zip(${lhs}${suffix}_starts).map{case (stop,start) => (stop-start).asUInt}.reduce{_*-*_}.asUInt === 0.U""")
    }
  }

  override protected def emitNode(lhs: Sym[_], rhs: Op[_]): Unit = rhs match {
    case Hwblock(func,isForever) =>
      hwblock_sym = hwblock_sym :+ lhs.asInstanceOf[Exp[_]]
      controllerStack.push(lhs)
      toggleEn() // turn on
      val streamAddition = getStreamEnablers(lhs)
      emitController(lhs, None, None)
      emit(s"""${swap(lhs, En)} := io.enable & !io.done ${streamAddition}""")
      emit(s"""${swap(lhs, Resetter)} := reset.toBool""")
      emit(src"""${swap(lhs, CtrTrivial)} := false.B""")
      emitGlobalWireMap(src"""${lhs}_II_done""", """Wire(Bool())""")
      if (iiOf(lhs) <= 1) {
        emit(src"""${swap(lhs, IIDone)} := true.B""")
      } else {
        emit(src"""val ${lhs}_IICtr = Module(new RedxnCtr(2 + Utils.log2Up(${lhs}_retime)));""")
        emit(src"""${swap(lhs, IIDone)} := ${lhs}_IICtr.io.output.done | ${swap(lhs, CtrTrivial)}""")
        emit(src"""${lhs}_IICtr.io.input.enable := ${swap(lhs,En)}""")
        emit(src"""${lhs}_IICtr.io.input.stop := ${iiOf(lhs)}.S // ${lhs}_retime.S""")
        emit(src"""${lhs}_IICtr.io.input.reset := reset.toBool | ${swap(lhs, IIDone)}.D(1)""")
        emit(src"""${lhs}_IICtr.io.input.saturate := false.B""")       
      }
      emit(src"""val retime_counter = Module(new SingleCounter(1, Some(0), Some(max_retime), Some(1), Some(0))) // Counter for masking out the noise that comes out of ShiftRegister in the first few cycles of the app""")
      // emit(src"""retime_counter.io.input.start := 0.S; retime_counter.io.input.stop := (max_retime.S); retime_counter.io.input.stride := 1.S; retime_counter.io.input.gap := 0.S""")
      emit(src"""retime_counter.io.input.saturate := true.B; retime_counter.io.input.reset := reset.toBool; retime_counter.io.input.enable := true.B;""")
      emitGlobalWire(src"""val retime_released = RegInit(false.B)""")
      emitGlobalWire(src"""val rr = retime_released // Shorthand""")
      emit(src"""retime_released := retime_counter.io.output.done """)
      topLayerTraits = childrenOf(lhs).map { c => src"$c" }
      if (levelOf(lhs) == InnerControl) emitInhibitor(lhs, None, None, None)
      // Emit unit counter for this
      emit(s"""val done_latch = Module(new SRFF())""")
      emit(s"""done_latch.io.input.set := ${swap(lhs, Done)}""")
      emit(s"""done_latch.io.input.reset := ${swap(lhs, Resetter)}""")
      emit(s"""done_latch.io.input.asyn_reset := ${swap(lhs, Resetter)}""")
      emit(s"""io.done := done_latch.io.output.data""")
      // if (isForever) emit(s"""${quote(lhs)}_sm.io.input.forever := true.B""")

      emitBlock(func)
      emitChildrenCxns(lhs, None, None)
      emitCopiedCChain(lhs)

      toggleEn() // turn off
      controllerStack.pop()

    case UnitPipe(ens,func) =>
      emitGlobalWireMap(src"${lhs}_II_done", "Wire(Bool())")
      val parent_kernel = controllerStack.head 
      controllerStack.push(lhs)
      emitGlobalWireMap(src"${lhs}_inhibitor", "Wire(Bool())") // hack
      emitController(lhs, None, None)
      emit(src"""${swap(lhs, CtrTrivial)} := ${swap(controllerStack.tail.head, CtrTrivial)}.D(1,rr) | false.B""")
      emitGlobalWire(src"""${swap(lhs, IIDone)} := true.B""")
      if (levelOf(lhs) == InnerControl) emitInhibitor(lhs, None, None, None)
      withSubStream(src"${lhs}", src"${parent_kernel}", levelOf(lhs) == InnerControl) {
        emit(s"// Controller Stack: ${controllerStack.tail}")
        emitBlock(func)
      }
      emitChildrenCxns(lhs, None, None)
      emitCopiedCChain(lhs)
      // emit(s"//${validPassMap} and ${cchainPassMap} on $ens")
      val en = if (ens.isEmpty) "true.B" else ens.map(quote).mkString(" && ")
      emit(src"${swap(lhs, Mask)} := $en")
      controllerStack.pop()

    case ParallelPipe(ens,func) =>
      emitGlobalWireMap(src"${lhs}_II_done", "Wire(Bool())")
      val parent_kernel = controllerStack.head
      controllerStack.push(lhs)
      emitController(lhs, None, None)
      emit(src"""${swap(lhs, CtrTrivial)} := ${swap(controllerStack.tail.head, CtrTrivial)}.D(1,rr) | false.B""")
      emitGlobalWire(src"""${swap(lhs, IIDone)} := true.B""")
      withSubStream(src"${lhs}", src"${parent_kernel}", levelOf(lhs) == InnerControl) {
        emit(s"// Controller Stack: ${controllerStack.tail}")
        emitBlock(func)
      } 
      emitChildrenCxns(lhs, None, None)
      emitCopiedCChain(lhs)
      val en = if (ens.isEmpty) "true.B" else ens.map(quote).mkString(" && ")
      emit(src"${swap(lhs, Mask)} := $en")
      controllerStack.pop()

    case op@Switch(body,selects,cases) =>
      // emitBlock(body)
      val parent_kernel = controllerStack.head 
      controllerStack.push(lhs)
      emitStandardSignals(lhs)
      createInstrumentation(lhs)
      emitGlobalWireMap(src"""${lhs}_II_done""", """Wire(Bool())""")
      emit(src"""${swap(lhs, IIDone)} := ${swap(parent_kernel, IIDone)}""")
      // emit(src"""//${swap(lhs, BaseEn)} := ${swap(parent_kernel, BaseEn)} // Set by parent""")
      emit(src"""${swap(lhs, Mask)} := true.B // No enable associated with switch, never mask it""")
      emit(src"""//${swap(lhs, Resetter)} := ${swap(parent_kernel, Resetter)} // Set by parent""")
      emit(src"""${swap(lhs, DatapathEn)} := ${swap(parent_kernel, DatapathEn)} // Not really used probably""")
      emit(src"""${swap(lhs, CtrTrivial)} := ${swap(parent_kernel, CtrTrivial)} | false.B""")
      parentOf(lhs).get match {  // This switch is a condition of another switchcase
        case Def(SwitchCase(_)) => 
          emit(src"""${swap(parentOf(lhs).get, Done)} := ${swap(lhs, Done)} // Route through""")
          emit(src"""${swap(lhs, En)} := ${swap(parent_kernel, En)}""")
        // case Def(e: StateMachine[_]) =>
        //   if (levelOf(parentOf(lhs).get) == InnerControl) emit(src"""${swap(lhs,En)} := ${swap(parent_kernel,En)}""")
        case _ => 
          if (levelOf(parentOf(lhs).get) == InnerControl) {
            emit(src"""${swap(lhs, En)} := ${swap(parent_kernel, En)} // Parent is inner, so doesn't know about me :(""")
          } else {
            emit(src"""//${swap(lhs, En)} := ${swap(parent_kernel, En)} // Parent should have set me""")            
          }

      }

      if (levelOf(lhs) == InnerControl) { // If inner, don't worry about condition mutation
        selects.indices.foreach{i => 
          emitGlobalWire(src"""val ${cases(i)}_switch_select = Wire(Bool())""")
          emit(src"""${cases(i)}_switch_select := ${selects(i)}""")
        }
      } else { // If outer, latch in selects in case the body mutates the condition
        selects.indices.foreach{i => 
          emit(src"""val ${cases(i)}_switch_sel_reg = RegInit(false.B)""")
          emit(src"""${cases(i)}_switch_sel_reg := Mux(Utils.risingEdge(${swap(lhs, En)}), ${selects(i)}, ${cases(i)}_switch_sel_reg)""")
          emitGlobalWire(src"""val ${cases(i)}_switch_select = Wire(Bool())""")
          emit(src"""${cases(i)}_switch_select := Mux(Utils.risingEdge(${swap(lhs, En)}), ${selects(i)}, ${cases(i)}_switch_sel_reg)""")
        }
      }

      withSubStream(src"${lhs}", src"${parent_kernel}", levelOf(lhs) == InnerControl) {
        emit(s"// Controller Stack: ${controllerStack.tail}")
        if (Bits.unapply(op.mT).isDefined) {
          emit(src"val ${lhs}_onehot_selects = Wire(Vec(${selects.length}, Bool()))")
          emit(src"val ${lhs}_data_options = Wire(Vec(${selects.length}, ${newWire(lhs.tp)}))")
          selects.indices.foreach { i =>
            emit(src"${lhs}_onehot_selects($i) := ${cases(i)}_switch_select")
            emit(src"${lhs}_data_options($i) := ${cases(i)}")
          }
          emitGlobalWire(src"val $lhs = Wire(${newWire(lhs.tp)})")
          emit(src"$lhs := Mux1H(${lhs}_onehot_selects, ${lhs}_data_options).r")

          cases.collect{case s: Sym[_] => stmOf(s)}.foreach{ stm => 
            visitStm(stm)
            // Probably need to match on type of stm and grab the return values
          }
          if (levelOf(lhs) == InnerControl) {
            emit(src"""${swap(lhs, Done)} := ${swap(parent_kernel, Done)}""")
          } else {
            val anyCaseDone = cases.map{c => 
              emitGlobalWireMap(src"${c}_done", "Wire(Bool())") // Lazy
              src"${swap(c, Done)}"
            }.mkString(" | ")
            emit(src"""${swap(lhs, Done)} := $anyCaseDone // Safe to assume Switch is done when ANY child is done?""")
          }

        } else {
          // If this is an innerpipe, we need to route the done of the parent downward.  If this is an outerpipe, we need to route the dones of children upward
          if (levelOf(lhs) == InnerControl) {
            emit(src"""${swap(lhs, Done)} := ${swap(parent_kernel, Done)}""")
            cases.collect{case s: Sym[_] => stmOf(s)}.foreach(visitStm)
          } else {
            val anyCaseDone = cases.map{c => 
              emitGlobalWireMap(src"${c}_done", "Wire(Bool())") // Lazy
              src"${swap(c, Done)}"
            }.mkString(" | ")
            emit(src"""${swap(lhs, Done)} := $anyCaseDone // Safe to assume Switch is done when ANY child is done?""")
            cases.collect{case s: Sym[_] => stmOf(s)}.foreach(visitStm)
          }
        }
      }
      controllerStack.pop()


    case op@SwitchCase(body) =>
      // open(src"val $lhs = {")
      val parent_kernel = controllerStack.head 
      controllerStack.push(lhs)
      emitStandardSignals(lhs)
      createInstrumentation(lhs)
      emit(src"""${swap(lhs,En)} := ${swap(parent_kernel,En)} & ${lhs}_switch_select""")
      // emit(src"""${swap(lhs, BaseEn)} := ${swap(parent_kernel, BaseEn)} & ${lhs}_switch_select""")
      emitGlobalWireMap(src"""${lhs}_II_done""", """Wire(Bool())""")
      emit(src"""${swap(lhs, IIDone)} := ${swap(parent_kernel, IIDone)}""")
      emit(src"""${swap(lhs, Mask)} := true.B // No enable associated with switch, never mask it""")
      emit(src"""${swap(lhs, Resetter)} := ${swap(parent_kernel, Resetter)}""")
      emit(src"""${swap(lhs, DatapathEn)} := ${swap(parent_kernel, DatapathEn)} // & ${lhs}_switch_select // Do not include switch_select because this signal is retimed""")
      emit(src"""${swap(lhs, CtrTrivial)} := ${swap(parent_kernel, CtrTrivial)} | false.B""")
      if (levelOf(lhs) == InnerControl) {
        val realctrl = findCtrlAncestor(lhs) // TODO: I don't think this search is needed anymore
        emitInhibitor(lhs, None, None, parentOf(parent_kernel))
      }
      withSubStream(src"${lhs}", src"${parent_kernel}", levelOf(lhs) == InnerControl) {
        emit(s"// Controller Stack: ${controllerStack.tail}")
        // if (blockContents(body).length > 0) {
        // if (childrenOf(lhs).count(isControlNode) == 1) { // This is an outer pipe
        if (childrenOf(lhs).count(isControlNode) > 1) {// More than one control node is children
          throw new Exception(s"Seems like something is messed up with switch cases ($lhs).  Please put a pipe around your multiple controllers inside the if statement, maybe?")
        } else if (levelOf(lhs) == OuterControl & childrenOf(lhs).count(isControlNode) == 1) { // This is an outer pipe
          emitBlock(body)
        } else if (levelOf(lhs) == OuterControl & childrenOf(lhs).count(isControlNode) == 0) {
          emitBlock(body)
          emit(src"""${swap(lhs, Done)} := ${swap(lhs,En)} // Route through""")
        } else if (levelOf(lhs) == InnerControl) { // Body contains only primitives
          emitBlock(body)
          emit(src"""${swap(lhs, Done)} := ${swap(parent_kernel, Done)} // Route through""")
        }
        val returns_const = lhs match {
          case Const(_) => false
          case _ => true
        }
        if (Bits.unapply(op.mT).isDefined & returns_const) {
          emitGlobalWire(src"val $lhs = Wire(${newWire(lhs.tp)})")
          emit(src"$lhs.r := ${body.result}.r")
        }
      }
      // val en = if (ens.isEmpty) "true.B" else ens.map(quote).mkString(" && ")
      // emit(src"${lhs}_mask := $en")
      controllerStack.pop()

      // close("}")

    case _:OpForeach   => throw new Exception("Should not be emitting chisel for Op ctrl node")
    case _:OpReduce[_] => throw new Exception("Should not be emitting chisel for Op ctrl node")
    case _:OpMemReduce[_,_] => throw new Exception("Should not be emitting chisel for Op ctrl node")

    case _ => super.emitNode(lhs, rhs)
  }

  override protected def emitFileFooter() {
    withStream(getStream("Instantiator")) {
      emit("")
      emit("// Instrumentation")
      emit(s"val numArgOuts_instr = ${instrumentCounters.length*2}")
      instrumentCounters.zipWithIndex.foreach { case(p,i) =>
        val depth = " "*p._2
        emit(src"""// ${depth}${quote(p._1)}""")
      }
    }

    withStream(getStream("IOModule")) {
      emit("// Instrumentation")
      emit(s"val io_numArgOuts_instr = ${instrumentCounters.length*2}")

    }

    super.emitFileFooter()
  }

}
