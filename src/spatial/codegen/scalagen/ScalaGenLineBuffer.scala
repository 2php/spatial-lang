package spatial.codegen.scalagen

import spatial.SpatialConfig
import spatial.api.LineBufferExp

trait ScalaGenLineBuffer extends ScalaGenMemories {
  val IR: LineBufferExp
  import IR._

  dependencies ::= AlwaysDep(s"${SpatialConfig.HOME}/src/spatial/codegen/scalagen/resources/LineBuffer.scala")

  override protected def remap(tp: Staged[_]): String = tp match {
    case tp: LineBufferType[_] => src"LineBuffer[${tp.child}]"
    case _ => super.remap(tp)
  }

  override protected def emitNode(lhs: Sym[_], rhs: Op[_]): Unit = rhs match {
    case op@LineBufferNew(rows, cols) =>
      emit(src"val $lhs = LineBuffer[${op.mT}]($rows, $cols, ${invalid(op.mT)})")
    case op@LineBufferRowSlice(lb,row,len,col) =>
      open(src"val $lhs = Array.tabulate($len){i => ")
        oobApply(op.mT, lb, lhs, Seq(row,col)){ emit(src"$lb.apply($row+i,$col)") }
      close("}")
    case op@LineBufferColSlice(lb,row,col,len) =>
      open(src"val $lhs = Array.tabulate($len){i =>")
        oobApply(op.mT, lb, lhs, Seq(row,col)){ emit(src"$lb.apply($row,$col+i)") }
      close("}")

    case op@LineBufferLoad(lb,row,col,en) =>
      open(src"val $lhs = {")
        oobApply(op.mT, lb, lhs, Seq(row,col)){ emit(src"if ($en) $lb.apply($row, $col) else ${invalid(op.mT)}") }
      close("}")

    case op@LineBufferStore(lb,col,data,en) =>
      open(src"val $lhs = {")
        oobUpdate(op.mT, lb, lhs, Seq(col), pre = "-, "){ emit(src"if ($en) $lb.store($col, $data)") }
      close("}")
    case _ => super.emitNode(lhs, rhs)
  }

}
