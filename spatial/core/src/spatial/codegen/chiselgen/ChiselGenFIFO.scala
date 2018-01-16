package spatial.codegen.chiselgen

import argon.core._
import argon.nodes._
import spatial.aliases._
import spatial.metadata._
import spatial.nodes._
import spatial.utils._

trait ChiselGenFIFO extends ChiselGenSRAM {

  override protected def spatialNeedsFPType(tp: Type[_]): Boolean = tp match { // FIXME: Why doesn't overriding needsFPType work here?!?!
    case FixPtType(s,d,f) => if (s) true else if (f == 0) false else true
    case IntType()  => false
    case LongType() => false
    case FloatType() => true
    case DoubleType() => true
    case _ => super.needsFPType(tp)
  }

  override protected def bitWidth(tp: Type[_]): Int = tp match {
    case Bits(bitEv) => bitEv.length
    // case x: StructType[_] => x.fields.head._2 match {
    //   case _: IssuedCmd => 96
    //   case _ => super.bitWidth(tp)
    // }
    case _ => super.bitWidth(tp)
  }

  override protected def name(s: Dyn[_]): String = s match {
    case Def(_: FIFONew[_])                => s"""${s}_${s.name.getOrElse("fifo").replace("$","")}"""
    case Def(BankedFIFOEnq(fifo:Sym[_],_,_))     => s"${s}_enqTo${fifo.id}"
    case Def(BankedFIFODeq(fifo:Sym[_],_))       => s"${s}_deqFrom${fifo.id}"
    case Def(FIFOEmpty(fifo:Sym[_]))       => s"${s}_isEmpty${fifo.id}"
    case Def(FIFOFull(fifo:Sym[_]))        => s"${s}_isFull${fifo.id}"
    case Def(FIFOAlmostEmpty(fifo:Sym[_])) => s"${s}_isAlmostEmpty${fifo.id}"
    case Def(FIFOAlmostFull(fifo:Sym[_]))  => s"${s}_isAlmostFull${fifo.id}"
    case Def(FIFONumel(fifo:Sym[_]))       => s"${s}_numel${fifo.id}"
    case _ => super.name(s)
  } 

  override protected def remap(tp: Type[_]): String = tp match {
    case tp: FIFOType[_] => src"chisel.collection.mutable.Queue[${tp.child}]"
    case _ => super.remap(tp)
  }

  // override protected def vecSize(tp: Type[_]): Int = tp.typeArguments.head match {
  //   case tp: Vector[_] => 1
  //   case _ => 1
  // }

  override protected def emitNode(lhs: Sym[_], rhs: Op[_]): Unit = rhs match {
    case op@FIFONew(_)   =>
      val size = constSizeOf(lhs)
      val width = bitWidth(op.mT)
      val rPars = readWidths(lhs)
      val wPars = writeWidths(lhs)
      // TODO: Can we dynamically choose cheap fifos if all sizes are the same?
      if (spatialConfig.useCheapFifos) {
        // ASSERT that all pars are the same!
        // Console.println(s"Working on $lhs, readers ${readersOf(lhs)}")
        if (wPars.distinct.length != 1 || rPars.distinct.length != 1) {
          error(u"Cannot use cheap fifos option for FIFO $lhs")
          error(lhs.ctx)
        }
        val wPar = wPars.headOption.getOrElse(1)
        val rPar = rPars.headOption.getOrElse(1)
        emitGlobalModule(src"""val $lhs = Module(new FIFO($rPar, $wPar, $size, ${writersOf(lhs).length}, ${readersOf(lhs).length}, $width)) // ${lhs.name.getOrElse("")}""")
      }
      else {
        // ASSERT that all pars are the same!
        // Console.println(s"Working on $lhs, readers ${readersOf(lhs)}")
        emitGlobalModule(src"""val $lhs = Module(new GeneralFIFO(List($rPars), List($wPars), $size, $width)) // ${lhs.name.getOrElse("")}""")
        appPropertyStats += HasGeneralFifo
      }


    case BankedFIFODeq(fifo,ens) =>
      val reader = readersOf(fifo).find{_.node == lhs}.get.ctrlNode
      val bug202delay = reader match {
        case Def(op@SwitchCase(_)) => 
          if (Bits.unapply(op.mT).isDefined & listensTo(reader).distinct.length == 0) src"${symDelay(parentOf(reader).get)}.toInt" else src"${enableRetimeMatch(ens.last, lhs)}.toInt" 
        case _ => src"${enableRetimeMatch(ens.last, lhs)}.toInt" 
      }
      val invisibleEnable = src"${swap(reader, DatapathEn)} & ~${swap(reader, Inhibitor)} & ${swap(reader, IIDone)}"
      emit(src"val $lhs = Wire(${newWire(lhs.tp)})")
      if (ens.length == 1) {
        if (spatialConfig.useCheapFifos) {
          emit(src"""${lhs}(0).r := ${fifo}.connectDeqPort(${swap(reader, En)} & ${DL(invisibleEnable, bug202delay, true)} & ${ens.map(quote(_)).mkString("List(", ",", ")")}.reduce{_&&_}).apply(0)""")
        } else {
          emit(src"""${lhs}(0).r := ${fifo}.connectDeqPort(Vec(List(${swap(reader, En)} & ${DL(invisibleEnable, bug202delay, true)} & ${ens.map(quote(_)).mkString("List(", ",", ")")}.reduce{_&&_}))).apply(0)""")
        }
      } else {
        if(spatialConfig.useCheapFifos) {
          val en = ens.map(quote).mkString("&")
          emit(src"""val ${lhs}_vec = ${quote(fifo)}.connectDeqPort(${DL(src"${swap(reader, DatapathEn)} & ~${swap(reader, Inhibitor)} & ${swap(reader, IIDone)}", src"${enableRetimeMatch(ens.head, lhs)}.toInt", true)} & $en)""")  
        } else {
          val en = ens.map{i => src"$i & ${DL(src"${swap(reader, DatapathEn)} & ~${swap(reader, Inhibitor)} & ${swap(reader, IIDone)}", src"${enableRetimeMatch(i, lhs)}.toInt", true)}"}
          emit(src"""val ${lhs}_vec = ${quote(fifo)}.connectDeqPort(Vec(List($en)))""")  
        }
        emit(src"""(0 until ${ens.length}).foreach{ i => ${lhs}(i).r := ${lhs}_vec(i) }""")
      }

    case BankedFIFOEnq(fifo,data,ens) =>
      val writer = writersOf(fifo).find{_.node == lhs}.get.ctrlNode
      val enabler = src"${swap(writer, DatapathEn)}"
      val datacsv = data.map{d => src"${d}.r"}.mkString(",")
      if (ens.length == 1) {
        if (spatialConfig.useCheapFifos) {
          val en = ens.map(quote).mkString("&")
          emit(src"""${fifo}.connectEnqPort(Vec(List(${datacsv})), ${DL(src"$enabler & ~${swap(writer, Inhibitor)} & ${swap(writer, IIDone)}", src"${enableRetimeMatch(ens.head, lhs)}.toInt", true)} & $en)""")  
        } else {
          val en = ens.map{i => src"$i & ${DL(src"$enabler & ~${swap(writer, Inhibitor)} & ${swap(writer, IIDone)}", src"${enableRetimeMatch(i, lhs)}.toInt", true)}"}
          emit(src"""${fifo}.connectEnqPort(Vec(List(${datacsv})), Vec(List($en)))""")
        }
      } else {
        if (spatialConfig.useCheapFifos) {
          val en = ens.map(quote).mkString("&")
          emit(src"""${fifo}.connectEnqPort(Vec(List(${datacsv})), ${DL(src"$enabler & ~${swap(writer, Inhibitor)} & ${swap(writer, IIDone)}", src"${enableRetimeMatch(ens.head, lhs)}.toInt", true)} & $en)""")  
        } else {
          val en = ens.map{i => src"$i & ${DL(src"$enabler & ~${swap(writer, Inhibitor)} & ${swap(writer, IIDone)}", src"${enableRetimeMatch(i, lhs)}.toInt", true)}"}
          emit(src"""${fifo}.connectEnqPort(Vec(List(${datacsv})), Vec(List($en)))""")
        }
      }


    case FIFOPeek(fifo) => emit(src"val $lhs = Wire(${newWire(lhs.tp)}); $lhs.r := $fifo.io.out(0).r")
    case FIFOEmpty(fifo) => emitGlobalWire(src"val $lhs = Wire(Bool())"); emit(src"$lhs := $fifo.io.empty")
    case FIFOFull(fifo) => emitGlobalWire(src"val $lhs = Wire(Bool())"); emit(src"$lhs := $fifo.io.full")
    case FIFOAlmostEmpty(fifo) => emitGlobalWire(src"val $lhs = Wire(Bool())"); emit(src"$lhs := $fifo.io.almostEmpty")
    case FIFOAlmostFull(fifo) => emitGlobalWire(src"val $lhs = Wire(Bool())"); emit(src"$lhs := $fifo.io.almostFull")
    case FIFONumel(fifo) => emit(src"val $lhs = $fifo.io.numel")

    case _ => super.emitNode(lhs, rhs)
  }
}
