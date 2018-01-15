package spatial.codegen.chiselgen

import argon.core._
import argon.nodes._
import spatial.aliases._
import spatial.metadata._
import spatial.nodes._
import spatial.utils._


trait ChiselGenFILO extends ChiselGenSRAM {

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
    case Def(_: FILONew[_])                => s"""${s}_${s.name.getOrElse("filo").replace("$","")}"""
    case Def(FILOPush(fifo:Sym[_],_,_))    => s"${s}_pushTo${fifo.id}"
    case Def(FILOPop(fifo:Sym[_],_))       => s"${s}_popFrom${fifo.id}"
    case Def(FILOEmpty(fifo:Sym[_]))       => s"${s}_isEmpty${fifo.id}"
    case Def(FILOFull(fifo:Sym[_]))        => s"${s}_isFull${fifo.id}"
    case Def(FILOAlmostEmpty(fifo:Sym[_])) => s"${s}_isAlmostEmpty${fifo.id}"
    case Def(FILOAlmostFull(fifo:Sym[_]))  => s"${s}_isAlmostFull${fifo.id}"
    case Def(FILONumel(fifo:Sym[_]))       => s"${s}_numel${fifo.id}"
    case _ => super.name(s)
  } 

  override protected def remap(tp: Type[_]): String = tp match {
    case tp: FILOType[_] => src"chisel.collection.mutable.Queue[${tp.child}]"
    case _ => super.remap(tp)
  }

  // override protected def vecSize(tp: Type[_]): Int = tp.typeArguments.head match {
  //   case tp: Vector[_] => 1
  //   case _ => 1
  // }

  override protected def emitNode(lhs: Sym[_], rhs: Op[_]): Unit = rhs match {
    case op@FILONew(_) =>
      val size = constSizeOf(lhs)
      // ASSERT that all pars are the same!
      val rPar = readersOf(lhs).map { r => 
        r.node match {
          case Def(_: FILOPop[_]) => 1
          // case Def(a@ParFILOPop(q,ens)) => ens.length
        }
      }.max
      val wPar = writersOf(lhs).map { w =>
        w.node match {
          case Def(_: FILOPush[_]) => 1
          // case Def(a@ParFILOPush(q,_,ens)) => ens.length
        }
      }.max
      val width = bitWidth(lhs.tp.typeArguments.head)
      emitGlobalModule(src"""val $lhs = Module(new FILO($rPar, $wPar, $size, ${writersOf(lhs).length}, ${readersOf(lhs).length}, $width)) // ${lhs.name.getOrElse("")}""")

    case BankedFILOPush(filo,data,ens) =>
      val en = ens.map(quote).mkString("&")
      val writer = writersOf(filo).find{_.node == lhs}.get.ctrlNode
      val enabler = src"${swap(writer, DatapathEn)}"
      val datacsv = data.map{d => src"${d}.r"}.mkString(",")
      if (ens.length == 1) emit(src"""${filo}.connectPushPort(Vec(List(${datacsv})), ${DL(src"$enabler & ~${swap(writer, Inhibitor)} & ${swap(writer, IIDone)}", src"${enableRetimeMatch(ens.head, lhs)}.toInt", true)} & $en)""")
      else emit(src"""${filo}.connectPushPort(Vec(List(${datacsv})), ${DL(src"$enabler & ~${swap(writer, Inhibitor)} & ${swap(writer, IIDone)}", src"${enableRetimeMatch(ens.head, lhs)}.toInt", true)} & $en)""")


    case BankedFILOPop(filo,ens) =>
      val en = ens.map(quote).mkString("&")
      val reader = readersOf(filo).find{_.node == lhs}.get.ctrlNode
      emit(src"val ${lhs} = Wire(${newWire(lhs.tp)})")
      if (ens.length == 1) {
        emit(src"""val ${lhs}_vec = ${quote(filo)}.connectPopPort((${swap(reader, DatapathEn)} & ~${swap(reader, Inhibitor)} & ${swap(reader, IIDone)}).D(${enableRetimeMatch(ens.head, lhs)}.toInt) & $en).reverse""")
        emit(src"""(0 until ${ens.length}).foreach{ i => ${lhs}(i).r := ${lhs}_vec(i) }""")
      } else {
        emit(src"""val ${lhs}_vec = ${quote(filo)}.connectPopPort(${DL(src"${swap(reader, DatapathEn)} & ~${swap(reader, Inhibitor)} & ${swap(reader, IIDone)}", src"${enableRetimeMatch(ens.head, lhs)}.toInt", true)} & $en).reverse""")
        emit(src"""(0 until ${ens.length}).foreach{ i => ${lhs}(i).r := ${lhs}_vec(i) }""")
      }

    case FILOPeek(filo) => emit(src"val $lhs = Wire(${newWire(lhs.tp)}); $lhs.r := $filo.io.out(0).r")
    case FILOEmpty(filo) => emit(src"val $lhs = $filo.io.empty")
    case FILOFull(filo) => emit(src"val $lhs = $filo.io.full")
    case FILOAlmostEmpty(filo) => emit(src"val $lhs = $filo.io.almostEmpty")
    case FILOAlmostFull(filo) => emit(src"val $lhs = $filo.io.almostFull")
    case FILONumel(filo) => emit(src"val $lhs = $filo.io.numel")

    case _ => super.emitNode(lhs, rhs)
  }
}
