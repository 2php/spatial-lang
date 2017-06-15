package spatial.nodes

import argon.internals._
import spatial.compiler._


/** IR Nodes **/
case class PrintIf(en: Exp[MBoolean], x: Exp[MString]) extends EnabledOp[MUnit](en) {
  def mirror(f:Tx) = DebuggingOps.printIf(f(en),f(x))
}
case class PrintlnIf(en: Exp[MBoolean], x: Exp[MString]) extends EnabledOp[MUnit](en) {
  def mirror(f:Tx) = DebuggingOps.printlnIf(f(en),f(x))
}
case class AssertIf(en: Exp[MBoolean], cond: Exp[MBoolean], msg: Option[Exp[MString]]) extends EnabledOp[MUnit](en) {
  def mirror(f:Tx) = DebuggingOps.assertIf(f(en),f(cond),f(msg))
}