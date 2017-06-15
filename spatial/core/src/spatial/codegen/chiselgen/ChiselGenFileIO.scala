package spatial.codegen.chiselgen

import argon.codegen.chiselgen.ChiselCodegen
import argon.internals._
import spatial.compiler._
import spatial.nodes._

trait ChiselGenFileIO extends ChiselCodegen  {

  override protected def emitNode(lhs: Sym[_], rhs: Op[_]): Unit = rhs match {
    case OpenFile(filename, isWr) => 
    case CloseFile(file) =>
    case ReadTokens(file, delim) =>
    case WriteTokens(file, delim, len, token, i) =>

    case _ => super.emitNode(lhs, rhs)
  }



}
