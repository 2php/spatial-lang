package spatial.codegen.chiselgen

import spatial.api.DRAMExp
import spatial.SpatialConfig

trait ChiselGenDRAM extends ChiselGenSRAM {
  val IR: DRAMExp
  import IR._

  override def quote(s: Exp[_]): String = {
    if (SpatialConfig.enableNaming) {
      s match {
        case lhs: Sym[_] =>
          val Op(rhs) = lhs
          rhs match {
            case e: Gather[_]=> 
              s"x${lhs.id}_gath"
            case e: Scatter[_] =>
              s"x${lhs.id}_scat"
            case e: BurstLoad[_] =>
              s"x${lhs.id}_load"
            case e: BurstStore[_] =>
              s"x${lhs.id}_store"
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

  override protected def remap(tp: Staged[_]): String = tp match {
    case tp: DRAMType[_] => src"Array[${tp.child}]"
    case _ => super.remap(tp)
  }

  override protected def emitNode(lhs: Sym[_], rhs: Op[_]): Unit = rhs match {
    case op@DRAMNew(dims) => emit(src"""val $lhs = new Array[${op.mA}](${dims.map(quote).mkString("*")})""")
    case Gather(dram, local, addrs, ctr, i)  =>
      open(src"val $lhs = {")
      open(src"$ctr.foreach{case (is,vs) => is.zip(vs).foreach{case ($i,v) => if (v) {")
      emit(src"$local.update($i, $dram.apply($addrs.apply($i)) )")
      close("}}}")
      close("}")
    case Scatter(dram, local, addrs, ctr, i) =>
      open(src"val $lhs = {")
      open(src"$ctr.foreach{case (is,vs) => is.zip(vs).foreach{case ($i,v) => if (v) {")
      emit(src"$dram.update($addrs.apply($i), $local.apply($i))")
      close("}}}")
      close("}")

    case BurstLoad(dram, fifo, ofs, ctr, i)  =>
      open(src"val $lhs = {")
      open(src"$ctr.foreach{case (is,vs) => is.zip(vs).foreach{case ($i,v) => if (v) {")
      emit(src"$fifo.enqueue( $dram.apply($ofs + $i) )")
      close("}}}")
      close("}")

    case BurstStore(dram, fifo, ofs, ctr, i) =>
      open(src"val $lhs = {")
      open(src"$ctr.foreach{case (is,vs) => is.zip(vs).foreach{case ($i,v) => if (v) {")
      emit(src"$dram.update($ofs + $i, $fifo.dequeue() )")
      close("}}}")
      close("}")

    case _ => super.emitNode(lhs, rhs)
  }

}
