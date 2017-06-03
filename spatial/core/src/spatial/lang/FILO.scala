package spatial.lang

import forge._
import spatial.nodes._

case class FILO[T:Type:Bits](s: Exp[FILO[T]]) extends Template[FILO[T]] {
  @api def push(data: T): Void = this.push(data, true)
  @api def push(data: T, en: Bit): Void = MUnit(FILO.push(this.s, data.s, en.s))

  @api def pop(): T = this.pop(true)
  @api def pop(en: Bit): T = wrap(FILO.pop(this.s, en.s))

  @api def empty(): Bit = wrap(FILO.is_empty(this.s))
  @api def full(): Bit = wrap(FILO.is_full(this.s))
  @api def almostEmpty(): Bit = wrap(FILO.is_almost_empty(this.s))
  @api def almostFull(): Bit = wrap(FILO.is_almost_full(this.s))
  @api def numel(): Index = wrap(FILO.numel(this.s))

  //@api def load(dram: DRAM1[T]): Void = dense_transfer(dram.toTile(this.ranges), this, isLoad = true)
  @api def load(dram: DRAMDenseTile1[T]): Void = dense_transfer(dram, this, isLoad = true)
  // @api def gather(dram: DRAMSparseTile[T]): Void = copy_sparse(dram, this, isLoad = true)

  @internal def ranges: Seq[Range] = Seq(Range.alloc(None, wrap(sizeOf(s)),None,None))
}

object FILO {
  /** Static methods **/
  implicit def filoType[T:Type:Bits]: Type[FILO[T]] = FILOType(typ[T])
  implicit def filoIsMemory[T:Type:Bits]: Mem[T, FILO] = new FILOIsMemory[T]

  @api def apply[T:Type:Bits](size: Index): FILO[T] = FILO(FILO.alloc[T](size.s))


  /** Constructors **/
  @internal def alloc[T:Type:Bits](size: Exp[Index]): Exp[FILO[T]] = {
    stageMutable(FILONew[T](size))(ctx)
  }
  @internal def push[T:Type:Bits](filo: Exp[FILO[T]], data: Exp[T], en: Exp[Bit]): Exp[Void] = {
    stageWrite(filo)(FILOPush(filo, data, en))(ctx)
  }
  @internal def pop[T:Type:Bits](filo: Exp[FILO[T]], en: Exp[Bit]): Exp[T] = {
    stageWrite(filo)(FILOPop(filo,en))(ctx)
  }
  @internal def is_empty[T:Type:Bits](filo: Exp[FILO[T]]): Exp[Bit] = {
    stage(FILOEmpty(filo))(ctx)
  }
  @internal def is_full[T:Type:Bits](filo: Exp[FILO[T]]): Exp[Bit] = {
    stage(FILOFull(filo))(ctx)
  }
  @internal def is_almost_empty[T:Type:Bits](filo: Exp[FILO[T]]): Exp[Bit] = {
    stage(FILOAlmostEmpty(filo))(ctx)
  }
  @internal def is_almost_full[T:Type:Bits](filo: Exp[FILO[T]]): Exp[Bit] = {
    stage(FILOAlmostFull(filo))(ctx)
  }
  @internal def numel[T:Type:Bits](filo: Exp[FILO[T]]): Exp[Index] = {
    stage(FILONumel(filo))(ctx)
  }
}

