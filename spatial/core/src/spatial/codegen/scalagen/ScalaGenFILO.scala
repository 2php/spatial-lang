package spatial.codegen.scalagen

import spatial.SpatialExp
import spatial.api.FILOExp

trait ScalaGenFILO extends ScalaGenMemories {
  val IR: SpatialExp
  import IR._

  override protected def remap(tp: Type[_]): String = tp match {
    case tp: FILOType[_] => src"scala.collection.mutable.Stack[${tp.child}]"
    case _ => super.remap(tp)
  }

  override protected def emitNode(lhs: Sym[_], rhs: Op[_]): Unit = rhs match {
    case op@FILONew(size)    => emitMem(lhs, src"$lhs = new scala.collection.mutable.Stack[${op.mT}] // size: $size")
    case FILOPush(filo,v,en)  => emit(src"val $lhs = if ($en) $filo.push($v)")
    case FILOEmpty(filo)  => emit(src"val $lhs = $filo.isEmpty")
    case FILOFull(filo)  => 
      val Def(FILONew(size)) = filo 
      emit(src"val $lhs = ${filo}.size >= $size ")
    case op@FILOPop(filo,en) => emit(src"val $lhs = if ($en && $filo.nonEmpty) $filo.pop() else ${invalid(op.mT)}")
    case _ => super.emitNode(lhs, rhs)
  }
}
