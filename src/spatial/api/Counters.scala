package spatial.api

import spatial.{SpatialApi, SpatialExp, SpatialOps}

trait CounterOps extends RangeOps {
  this: SpatialOps =>

  type Counter <: CounterOps
  type CounterChain <: CounterChainOps

  protected trait CounterOps
  protected trait CounterChainOps

  def CounterChain(counters: Counter*)(implicit ctx: SrcCtx): CounterChain
  def Counter(start: Index, end: Index, step: Index, par: Index)(implicit ctx: SrcCtx): Counter

  implicit def range2counter(range: Range)(implicit ctx: SrcCtx): Counter

  implicit val CounterType: Staged[Counter]
  implicit val CounterChainType: Staged[CounterChain]
}
trait CounterApi extends CounterOps with RangeApi { this: SpatialApi => }


trait CounterExp extends CounterOps with RangeExp with SpatialExceptions {
  this: SpatialExp =>

  /** API **/
  case class Counter(s: Exp[Counter]) extends CounterOps
  case class CounterChain(s: Exp[CounterChain]) extends CounterChainOps

  def CounterChain(counters: Counter*)(implicit ctx: SrcCtx): CounterChain = CounterChain(counterchain_new(unwrap(counters)))
  def Counter(start: Index, end: Index, step: Index, par: Index)(implicit ctx: SrcCtx): Counter = {
    counter(start, end, step, Some(par))
  }

  implicit def range2counter(range: Range)(implicit ctx: SrcCtx): Counter = {
    val start = range.start.getOrElse(lift[Int,Index](0))
    val end = range.end
    val step = range.step.getOrElse(lift[Int,Index](1))
    val par = range.p
    counter(start, end, step, par)
  }

  def extractParFactor(par: Option[Index])(implicit ctx: SrcCtx): Const[Index] = par.map(_.s) match {
    case Some(x: Const[_]) if isIndexType(x.tp) => x.asInstanceOf[Const[Index]]
    case None => intParam(1)
    case Some(x) => new InvalidParallelFactorError(x)(ctx); intParam(1)
  }

  def counter(start: Index, end: Index, step: Index, par: Option[Index])(implicit ctx: SrcCtx): Counter = {
    val p = extractParFactor(par)
    Counter(counter_new(start.s, end.s, step.s, p))
  }

  /** Staged Types **/
  implicit object CounterType extends Staged[Counter] {
    override def wrapped(x: Exp[Counter]) = Counter(x)
    override def unwrapped(x: Counter) = x.s
    override def typeArguments = Nil
    override def isPrimitive = false
    override def stagedClass = classOf[Counter]
  }
  implicit object CounterChainType extends Staged[CounterChain] {
    override def wrapped(x: Exp[CounterChain]) = CounterChain(x)
    override def unwrapped(x: CounterChain) = x.s
    override def typeArguments = Nil
    override def isPrimitive = false
    override def stagedClass = classOf[CounterChain]
  }



  /** IR Nodes **/
  case class CounterNew(start: Exp[Index], end: Exp[Index], step: Exp[Index], par: Const[Index]) extends Op[Counter] {
    def mirror(f:Tx) = counter_new(f(start), f(end), f(step), par)
  }
  case class CounterChainNew(counters: Seq[Exp[Counter]]) extends Op[CounterChain] {
    def mirror(f:Tx) = counterchain_new(f(counters))
  }

  /** Smart constructors **/
  def counter_new(start: Exp[Index], end: Exp[Index], step: Exp[Index], par: Const[Index])(implicit ctx: SrcCtx): Sym[Counter] = {
    val counter = stageCold(CounterNew(start,end,step,par))(ctx)
    par match {
      case Const(0) =>
        warn(ctx)
        warn(ctx, u"Counter $counter has parallelization of 0")
      case _ =>
    }
    step match {
      case Const(0) =>
        warn(ctx)
        warn(ctx, u"Counter $counter has step of 0")
      case _ =>
    }
    counter
  }
  def counterchain_new(counters: Seq[Exp[Counter]])(implicit ctx: SrcCtx) = stageCold(CounterChainNew(counters))(ctx)

  /** Internals **/
  def isUnitCounter(x: Exp[Counter]): Boolean = x match {
    case Op(CounterNew(Const(0), Const(1), Const(1), _)) => true
    case _ => false
  }

  def isUnitCounterChain(x: Exp[CounterChain]): Boolean = x match {
    case Op(CounterChainNew(ctrs)) => ctrs.forall(isUnitCounter)
    case _ => false
  }
}
