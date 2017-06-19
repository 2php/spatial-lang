package spatial.codegen.cppgen

import argon.codegen.cppgen.CppCodegen
import argon.core._
import spatial.aliases._
import spatial.nodes._
import spatial.SpatialConfig

trait CppGenFIFO extends CppCodegen {

  override def quote(s: Exp[_]): String = {
    if (SpatialConfig.enableNaming) {
      s match {
        case lhs: Sym[_] =>
          lhs match {
            case Def(e: FIFONew[_]) =>
              s"""x${lhs.id}_${lhs.name.getOrElse("fifo")}"""
            case Def(FIFOEnq(fifo:Sym[_],_,_)) =>
              s"x${lhs.id}_enqTo${fifo.id}"
            case Def(FIFODeq(fifo:Sym[_],_)) =>
              s"x${lhs.id}_deqFrom${fifo.id}"
            case _ =>
              super.quote(s)
          }
        case _ =>
          super.quote(s)
      }
    } else {
      super.quote(s)
    }
  } 

  override protected def remap(tp: Type[_]): String = tp match {
    case tp: FIFOType[_] => src"cpp.collection.mutable.Queue[${tp.child}]"
    case _ => super.remap(tp)
  }

  override protected def emitNode(lhs: Sym[_], rhs: Op[_]): Unit = rhs match {
    case _ => super.emitNode(lhs, rhs)
  }
}
