package spatial.transform

import argon.transform.ForwardTransformer
import spatial.SpatialExp

trait TransferSpecialization extends ForwardTransformer {
  val IR: SpatialExp
  import IR._

  override val name = "Transfer Specialization"
  verbosity = 3

  override def transform[T: Staged](lhs: Sym[T], rhs: Op[T])(implicit ctx: SrcCtx): Exp[T] = rhs match {
    case e: CoarseBurst[_,_] => e.expand(f).asInstanceOf[Exp[T]]
    case _ => super.transform(lhs, rhs)
  }

}
