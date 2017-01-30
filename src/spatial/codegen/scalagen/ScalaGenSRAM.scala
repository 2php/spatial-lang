package spatial.codegen.scalagen

import argon.codegen.scalagen.ScalaCodegen
import spatial.api.SRAMExp

trait ScalaGenSRAM extends ScalaCodegen {
  val IR: SRAMExp
  import IR._

  override protected def remap(tp: Staged[_]): String = tp match {
    case tp: SRAMType[_] => src"Array[${tp.bits}]"
    case _ => super.remap(tp)
  }

  def flattenAddress(dims: Seq[Exp[Index]], indices: Seq[Exp[Index]], ofs: Option[Exp[Index]]): String = {
    val strides = List.tabulate(dims.length){i => (dims.drop(i+1).map(quote) :+ "1").mkString("*") }
    indices.zip(strides).map{case (i,s) => src"$i*$s" }.mkString(" + ") + ofs.map{o => src" + $o"}.getOrElse("")
  }

  override protected def emitNode(lhs: Sym[_], rhs: Op[_]): Unit = rhs match {
    case op@SRAMNew(dims) => emit(src"""val $lhs = new Array[${op.bT}](${dims.map(quote).mkString("*")})""")
    case SRAMLoad(sram, dims, is, ofs) =>
      emit(src"val $lhs = $sram.apply(${flattenAddress(dims,is,Some(ofs))})")

    case SRAMStore(sram, dims, is, ofs, v, en) =>
      emit(src"val $lhs = if ($en) $sram.update(${flattenAddress(dims,is,Some(ofs))}, $v)")

    case _ => super.emitNode(lhs, rhs)
  }
}
