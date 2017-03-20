package spatial.api

import argon.core.Staging
import spatial.SpatialExp

// N by B
// N par P
// N by B par P
// M until N
// M until N par P
// M until N by B
// M until N by B par P

// valid to use "[Range] by B" unless range is already strided
// may require a Range which is not a StridedRange?
// :: this distinction is silly, only using one type

trait RangeLowPriorityImplicits { this: RangeApi =>
  // Have to make this a lower priority, otherwise seems to prefer this + Range infix op over the implicit class on Index
  implicit def index2range(x: Index)(implicit ctx: SrcCtx): Range = index_to_range(x)
}

trait RangeApi extends RangeExp with MemoryApi with RangeLowPriorityImplicits {
  this: SpatialExp =>

  def *()(implicit ctx: SrcCtx) = Wildcard()

  implicit class IndexRangeOps(x: Index) {
    def by(step: Int)(implicit ctx: SrcCtx): Range = range_alloc(None, x, Some(lift(step)), None)
    def par(p: Int)(implicit ctx: SrcCtx): Range = range_alloc(None, x, None, Some(lift(p)))
    def until(end: Int)(implicit ctx: SrcCtx): Range = range_alloc(Some(x), lift(end), None, None)

    def by(step: Index)(implicit ctx: SrcCtx): Range = range_alloc(None, x, Some(step), None)
    def par(p: Index)(implicit ctx: SrcCtx): Range = range_alloc(None, x, None, Some(p))
    def until(end: Index)(implicit ctx: SrcCtx): Range = range_alloc(Some(x), end, None, None)

    def ::(start: Index)(implicit ctx: SrcCtx): Range = range_alloc(Some(start), x, None, None)
  }

  implicit class intWrapper(x: scala.Int) {
    def until(end: Index)(implicit ctx: SrcCtx): Range = range_alloc(Some(lift(x)), end, None, None)
    def by(step: Index)(implicit ctx: SrcCtx): Range = range_alloc(None, lift(x), Some(step), None)
    def par(p: Index)(implicit ctx: SrcCtx): Range = range_alloc(None, lift(x), None, Some(p))

    def until(end: scala.Int)(implicit ctx: SrcCtx): Range = range_alloc(Some(lift(x)), lift(end), None, None)
    def by(step: scala.Int)(implicit ctx: SrcCtx): Range = range_alloc(None, lift(x), Some(lift(step)), None)
    def par(p: scala.Int)(implicit ctx: SrcCtx): Range = range_alloc(None, lift(x), None, Some(lift(p)))

    def ::(start: Index)(implicit ctx: SrcCtx): Range = range_alloc(Some(start), lift(x), None, None)
    def ::(start: scala.Int)(implicit ctx: SrcCtx): Range = range_alloc(Some(lift(start)), lift(x), None, None)
  }

  // Implicitly get value of register to use in counter definitions
  implicit def regToIndexRange(x: Reg[Index])(implicit ctx: SrcCtx): IndexRangeOps = IndexRangeOps(x.value)

  def Range(start: Index, end: Index, stride: Index, par: Index): Range = {
    range_alloc(Some(start), end, Some(stride), Some(par))
  }
}




trait RangeExp extends Staging with MemoryExp {
  this: SpatialExp =>

  case class Wildcard()

  case class Range(start: Option[Index], end: Index, step: Option[Index], p: Option[Index], isUnit: Boolean) {
    def by(step: Index)(implicit ctx: SrcCtx): Range = Range(start, end, Some(step), p, isUnit = false)
    def par(p: Index)(implicit ctx: SrcCtx): Range = Range(start, end, step, Some(p), isUnit = false)

    def ::(start2: Index)(implicit ctx: SrcCtx): Range = Range(Some(start2), end, start, p, isUnit = false)

    def foreach(func: Index => Void)(implicit ctx: SrcCtx): Void = {
      val i = fresh[Index]
      val fBlk = () => func(wrap(i)).s
      val begin  = start.map(_.s).getOrElse(int32(0))
      val stride = step.map(_.s).getOrElse(int32(1))
      Void(range_foreach(begin, end.s, stride, fBlk(), i))
    }

    def length(implicit ctx: SrcCtx) = (start, end, step) match {
      case (None, e, None) => e
      case (Some(s), e, None) => e - s
      case (None, e, Some(st)) => (e + st - 1) / st
      case (Some(s), e, Some(st)) => (e - s + st - 1) / st
    }
  }

  protected def range_alloc(start: Option[Index], end: Index, stride: Option[Index], par: Option[Index], isUnit: Boolean = false) = {
    Range(start,end,stride,par,isUnit)
  }
  protected def index_to_range(x: Index)(implicit ctx: SrcCtx) = range_alloc(Some(x), x + 1, None, None, isUnit = true)

  /** IR Nodes **/
  case class RangeForeach(
    start: Exp[Index],
    end:   Exp[Index],
    step:  Exp[Index],
    func:  Block[Void],
    i:     Bound[Index]
  ) extends Op[Void] {
    def mirror(f:Tx) = range_foreach(f(start),f(end),f(step),f(func),i)
    override def inputs = syms(start,end,step) ++ syms(func)
    override def freqs  = normal(start) ++ normal(end) ++ normal(step) ++ hot(func)
    override def binds  = i +: super.binds
  }

  /*case class RangeReduce[T](
    start:  Exp[Index],
    end:    Exp[Index],
    step:   Exp[Index],
    func:   Block[T],
    reduce: Block[T],
    rV:     (Bound[Index],Bound[Index]),
    i:      Bound[Index]
  )*/

  /** Constructors **/
  def range_foreach(start: Exp[Index], end: Exp[Index], step: Exp[Index], func: => Exp[Void], i: Bound[Index])(implicit ctx: SrcCtx) = {
    val fBlk = stageBlock { func }
    val effects = fBlk.summary
    stageEffectful(RangeForeach(start, end, step, fBlk, i), effects)(ctx)
  }

}