package spatial.nodes

import forge._
import spatial.compiler._

case class FILOType[T:Bits](child: Type[T]) extends Type[FILO[T]] {
  override def wrapped(x: Exp[FILO[T]]) = FILO(x)(child,bits[T])
  override def typeArguments = List(child)
  override def stagedClass = classOf[FILO[T]]
  override def isPrimitive = false
}

// --- Memory
class FILOIsMemory[T:Type:Bits] extends Mem[T,FILO] {
  @api def load(mem: FILO[T], is: Seq[Index], en: Bit)(implicit ctx: SrcCtx): T = mem.pop(en)
  @api def store(mem: FILO[T], is: Seq[Index], data: T, en: Bit)(implicit ctx: SrcCtx): Void = mem.push(data, en)

  @api def iterators(mem: FILO[T])(implicit ctx: SrcCtx): Seq[Counter] = Seq(Counter(0,sizeOf(mem),1,1))
}


/** IR Nodes **/
case class FILONew[T:Type:Bits](size: Exp[Index]) extends Op2[T,FILO[T]] {
  def mirror(f:Tx) = FILO.alloc[T](f(size))
  val mT = typ[T]
  val bT = bits[T]
}
case class FILOPush[T:Type:Bits](filo: Exp[FILO[T]], data: Exp[T], en: Exp[Bit]) extends EnabledOp[Void](en) {
  def mirror(f:Tx) = FILO.push(f(filo),f(data),f(en))
  val mT = typ[T]
  val bT = bits[T]
}
case class FILOPop[T:Type:Bits](filo: Exp[FILO[T]], en: Exp[Bit]) extends EnabledOp[T](en) {
  def mirror(f:Tx) = FILO.pop(f(filo), f(en))
  val mT = typ[T]
  val bT = bits[T]
}
case class FILOEmpty[T:Type:Bits](filo: Exp[FILO[T]]) extends Op[Bit] {
  def mirror(f:Tx) = FILO.is_empty(f(filo))
  val mT = typ[T]
  val bT = bits[T]
}
case class FILOFull[T:Type:Bits](filo: Exp[FILO[T]]) extends Op[Bit] {
  def mirror(f:Tx) = FILO.is_full(f(filo))
  val mT = typ[T]
  val bT = bits[T]
}
case class FILOAlmostEmpty[T:Type:Bits](filo: Exp[FILO[T]]) extends Op[Bit] {
  def mirror(f:Tx) = FILO.is_almost_empty(f(filo))
  val mT = typ[T]
  val bT = bits[T]
}
case class FILOAlmostFull[T:Type:Bits](filo: Exp[FILO[T]]) extends Op[Bit] {
  def mirror(f:Tx) = FILO.is_almost_full(f(filo))
  val mT = typ[T]
  val bT = bits[T]
}
case class FILONumel[T:Type:Bits](filo: Exp[FILO[T]]) extends Op[Index] {
  def mirror(f:Tx) = FILO.numel(f(filo))
  val mT = typ[T]
  val bT = bits[T]
}