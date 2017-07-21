package spatial.lang

import argon.core._
import forge._
import spatial.metadata._
import spatial.nodes._
import spatial.utils._

trait RegFile[T] { this: Template[_] =>
  def s: Exp[RegFile[T]]

  @internal def ranges: Seq[Range] = stagedDimsOf(s).map{d => Range.alloc(None, wrap(d), None, None)}
}
object RegFile {
  @api def apply[T:Type:Bits](cols: Index): RegFile1[T] = wrap(alloc[T,RegFile1](None, cols.s))
  @api def apply[T:Type:Bits](cols: Int, inits: List[T]): RegFile1[T] = {
    checkDims(Seq(cols), inits)
    wrap(alloc[T,RegFile1](Some(unwrap(inits)),FixPt.int32(cols)))
  }
  @api def apply[T:Type:Bits](rows: Index, cols: Index): RegFile2[T] = wrap(alloc[T,RegFile2](None,rows.s,cols.s))
  @api def apply[T:Type:Bits](rows: Int, cols: Int, inits: List[T]): RegFile2[T] = {
    checkDims(Seq(cols, rows), inits)
    wrap(alloc[T,RegFile2](Some(unwrap(inits)),FixPt.int32(rows),FixPt.int32(cols)))
  }
  @api def apply[T:Type:Bits](dim0: Index, dim1: Index, dim2: Index): RegFile3[T] = wrap(alloc[T,RegFile3](None,dim0.s, dim1.s, dim2.s))
  @api def apply[T:Type:Bits](dim0: Int, dim1: Int, dim2: Int, inits: List[T]): RegFile3[T] = {
    checkDims(Seq(dim0,dim1,dim2), inits)
    wrap(alloc[T,RegFile3](Some(unwrap(inits)),FixPt.int32(dim0), FixPt.int32(dim1), FixPt.int32(dim2)))
  }

  @internal def checkDims(dims: Seq[Int], elems: Seq[_]) = {
    if ((dims.product) != elems.length) {
      error(ctx, c"Specified dimensions of the RegFile do not match the number of supplied elements (${dims.product} != ${elems.length})")
      error(ctx)
    }
  }

  @api def buffer[T:Type:Bits](cols: Index): RegFile1[T] = {
    val rf = alloc[T,RegFile1](cols.s)
    isExtraBufferable.enableOn(rf)
    wrap(rf)
  }
  @api def buffer[T:Type:Bits](rows: Index, cols: Index): RegFile2[T] = {
    val rf = alloc[T,RegFile2](rows.s,cols.s)
    isExtraBufferable.enableOn(rf)
    wrap(rf)
  }
  @api def buffer[T:Type:Bits](dim0: Index, dim1: Index, dim2: Index): RegFile3[T] = {
    val rf = alloc[T,RegFile3](dim0.s, dim1.s, dim2.s)
    isExtraBufferable.enableOn(rf)
    wrap(rf)
  }


  /** Constructors **/
  @internal def alloc[T:Type:Bits,C[_]<:RegFile[_]](inits: Option[Seq[Exp[T]]], dims: Exp[Index]*)(implicit cT: Type[C[T]]) = {
    stageMutable(RegFileNew[T,C](dims, inits))(ctx)
  }

  @internal def load[T:Type:Bits](reg: Exp[RegFile[T]], inds: Seq[Exp[Index]], en: Exp[Bit]) = {
    stage(RegFileLoad(reg, inds, en))(ctx)
  }

  @internal def store[T:Type:Bits](reg: Exp[RegFile[T]], inds: Seq[Exp[Index]], data: Exp[T], en: Exp[Bit]) = {
    stageWrite(reg)(RegFileStore(reg, inds, data, en))(ctx)
  }

  @internal def reset[T:Type:Bits](rf: Exp[RegFile[T]], en: Exp[Bit]): Sym[MUnit] = {
    stageWrite(rf)( RegFileReset(rf, en) )(ctx)
  }

  @internal def shift_in[T:Type:Bits](
    reg:  Exp[RegFile[T]],
    inds: Seq[Exp[Index]],
    dim:  Int,
    data: Exp[T],
    en:   Exp[Bit]
  ) = {
    stageWrite(reg)(RegFileShiftIn(reg, inds, dim, data, en))(ctx)
  }

  @internal def par_shift_in[T:Type:Bits](
    reg:  Exp[RegFile[T]],
    inds: Seq[Exp[Index]],
    dim:  Int,
    data: Exp[Vector[T]],
    en:   Exp[Bit]
  ) = {
    stageWrite(reg)(ParRegFileShiftIn(reg, inds, dim, data, en))(ctx)
  }

  @internal def par_load[T:Type:Bits](
    reg:  Exp[RegFile[T]],
    inds: Seq[Seq[Exp[Index]]],
    ens:  Seq[Exp[Bit]]
  ) = {
    implicit val vT = VectorN.typeFromLen[T](ens.length)
    stage(ParRegFileLoad(reg, inds, ens))(ctx)
  }

  @internal def par_store[T:Type:Bits](
    reg:  Exp[RegFile[T]],
    inds: Seq[Seq[Exp[Index]]],
    data: Seq[Exp[T]],
    ens:  Seq[Exp[Bit]]
  ) = {
    stageWrite(reg)(ParRegFileStore(reg, inds, data, ens))(ctx)
  }
}

case class RegFile1[T:Type:Bits](s: Exp[RegFile1[T]]) extends Template[RegFile1[T]] with RegFile[T] {
  @api def apply(i: Index): T = wrap(RegFile.load(s, Seq(i.s), Bit.const(true)))
  @api def update(i: Index, data: T): MUnit = MUnit(RegFile.store(s, Seq(i.s), data.s, Bit.const(true)))

  @api def <<=(data: T): MUnit = wrap(RegFile.shift_in(s, Seq(int32(0)), 0, data.s, Bit.const(true)))
  @api def <<=(data: Vector[T]): MUnit = wrap(RegFile.par_shift_in(s, Seq(int32(0)), 0, data.s, Bit.const(true)))

  @api def load(dram: DRAM1[T]): MUnit = DRAMTransfers.dense_transfer(dram.toTile(ranges), this, isLoad = true)
  @api def load(dram: DRAMDenseTile1[T]): MUnit = DRAMTransfers.dense_transfer(dram, this, isLoad = true)

  @api def reset(cond: Bit): MUnit = wrap(RegFile.reset(this.s, cond.s))
  @api def reset: MUnit = wrap(RegFile.reset(this.s, Bit.const(true)))
}
object RegFile1 {
  implicit def regFile1Type[T:Type:Bits]: Type[RegFile1[T]] = RegFile1Type(typ[T])
  implicit def regfile1IsMemory[T:Type:Bits]: Mem[T,RegFile1] = new RegFileIsMemory[T,RegFile1]
}

case class RegFile2[T:Type:Bits](s: Exp[RegFile2[T]]) extends Template[RegFile2[T]] with RegFile[T] {
  @api def apply(r: Index, c: Index): T = wrap(RegFile.load(s, Seq(r.s, c.s), Bit.const(true)))
  @api def update(r: Index, c: Index, data: T): MUnit = MUnit(RegFile.store(s, Seq(r.s, c.s), data.s, Bit.const(true)))

  @api def apply(i: Index, y: Wildcard) = RegFileView(s, Seq(i,lift[Int,Index](0)), 1)
  @api def apply(y: Wildcard, i: Index) = RegFileView(s, Seq(lift[Int,Index](0),i), 0)

  @api def load(dram: DRAM2[T]): MUnit = DRAMTransfers.dense_transfer(dram.toTile(ranges), this, isLoad = true)
  @api def load(dram: DRAMDenseTile2[T]): MUnit = DRAMTransfers.dense_transfer(dram, this, isLoad = true)

  @api def reset(cond: Bit): MUnit = wrap(RegFile.reset(this.s, cond.s))
  @api def reset: MUnit = wrap(RegFile.reset(this.s, Bit.const(true)))
}
object RegFile2 {
  implicit def regFile2Type[T:Type:Bits]: Type[RegFile2[T]] = RegFile2Type(typ[T])
  implicit def regfile2IsMemory[T:Type:Bits]: Mem[T,RegFile2] = new RegFileIsMemory[T,RegFile2]
}

case class RegFile3[T:Type:Bits](s: Exp[RegFile3[T]]) extends Template[RegFile3[T]] with RegFile[T] {
  @api def apply(dim0: Index, dim1: Index, dim2: Index): T = wrap(RegFile.load(s, Seq(dim0.s, dim1.s, dim2.s), Bit.const(true)))
  @api def update(dim0: Index, dim1: Index, dim2: Index, data: T): MUnit = MUnit(RegFile.store(s, Seq(dim0.s, dim1.s, dim2.s), data.s, Bit.const(true)))

  @api def apply(i: Index, j: Index, y: Wildcard) = RegFileView(s, Seq(i,j,lift[Int,Index](0)), 2)
  @api def apply(i: Index, y: Wildcard, j: Index) = RegFileView(s, Seq(i,lift[Int,Index](0),j), 1)
  @api def apply(y: Wildcard, i: Index, j: Index) = RegFileView(s, Seq(lift[Int,Index](0),i,j), 0)

  @api def load(dram: DRAM3[T]): MUnit = DRAMTransfers.dense_transfer(dram.toTile(ranges), this, isLoad = true)
  @api def load(dram: DRAMDenseTile3[T]): MUnit = DRAMTransfers.dense_transfer(dram, this, isLoad = true)

  @api def reset(cond: Bit): MUnit = wrap(RegFile.reset(this.s, cond.s))
  @api def reset: MUnit = wrap(RegFile.reset(this.s, Bit.const(true)))
}
object RegFile3 {
  implicit def regFile3Type[T:Type:Bits]: Type[RegFile3[T]] = RegFile3Type(typ[T])
  implicit def regfile3IsMemory[T:Type:Bits]: Mem[T,RegFile3] = new RegFileIsMemory[T,RegFile3]
}

case class RegFileView[T:Type:Bits](s: Exp[RegFile[T]], i: Seq[Index], dim: Int) {
  @api def <<=(data: T): MUnit = wrap(RegFile.shift_in(s, unwrap(i), dim, data.s, Bit.const(true)))
  @api def <<=(data: Vector[T]): MUnit = wrap(RegFile.par_shift_in(s, unwrap(i), dim, data.s, Bit.const(true)))
}

