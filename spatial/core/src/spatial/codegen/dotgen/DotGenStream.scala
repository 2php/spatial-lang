package spatial.codegen.dotgen

import argon.codegen.dotgen.DotCodegen
import spatial.SpatialExp

trait DotGenStream extends DotCodegen with DotGenReg {
  val IR: SpatialExp
  import IR._

  override def attr(n:Exp[_]) = n match {
    case n if isStreamIn(n) => super.attr(n).shape(box).style(filled).color(gold)
    case n if isStreamOut(n) => super.attr(n).shape(box).style(filled).color(gold)
    case n => super.attr(n)
  }

  override protected def emitNode(lhs: Sym[_], rhs: Op[_]): Unit = rhs match {
    case StreamInNew(bus) if fringeOf(lhs).nonEmpty => emitVert(lhs)
    case StreamOutNew(bus) if fringeOf(lhs).nonEmpty => emitVert(lhs)
    case StreamRead(stream, en) => emitMemRead(lhs)
    case StreamWrite(stream, data, en) => emitMemWrite(lhs)
    case _ => super.emitNode(lhs, rhs)
  }

  override protected def emitFileFooter() {
    super.emitFileFooter()
  }

}
