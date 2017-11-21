package spatial.dse

import argon.core._
import forge._
import spatial.aliases._
import spatial.metadata._

trait SpaceGenerator {
  final val PRUNE: Boolean = false

  implicit class ToRange(x: (Int,Int,Int)) {
    def toRange: Range = x._1 to x._3 by x._2
  }

  def domain(p: Param[Index], restricts: Iterable[Restrict])(implicit ir: State): Domain[Int] = {
    if (restricts.nonEmpty) {
      Domain.restricted(
        name   = p.name.getOrElse(c"$p"),
        range  = domainOf(p).toRange,
        setter = {(v: Int, state: State) => p.setValue(FixedPoint(v))(state) },
        getter = {(state: State) => p.value(state).asInstanceOf[FixedPoint].toInt },
        cond   = {state => restricts.forall(_.evaluate()(state)) },
        tp     = Ordinal
      )
    }
    else {
      Domain(
        name  = p.name.getOrElse(c"$p"),
        range = domainOf(p).toRange,
        setter = { (v: Int, state: State) => p.setValue(FixedPoint(v))(state) },
        getter = { (state: State) => p.value(state).asInstanceOf[FixedPoint].toInt },
        tp     = Ordinal
      )
    }
  }

  def createIntSpace(params: Seq[Param[Index]], restrict: Set[Restrict])(implicit ir: State): Seq[Domain[Int]] = {
    if (PRUNE) {
      val pruneSingle = params.map { p =>
        val restricts = restrict.filter(_.dependsOnlyOn(p))
        p -> domain(p, restricts)
      }
      pruneSingle.map(_._2)
    }
    else {
      params.map{p => domain(p, Nil) }
    }
  }

  def createCtrlSpace(metapipes: Seq[Exp[_]])(implicit ir: State): Seq[Domain[Int]] = {
    metapipes.map{mp =>
      Domain.apply(
        name    = mp.name.getOrElse(c"$mp"),
        range   = 0 to 1,
        setter  = {(c: Int, state:State) => if (c == 1) styleOf.set(mp, MetaPipe)(state)
                                            else   styleOf.set(mp, SeqPipe)(state) },
        getter  = {(state: State) => if (styleOf(mp)(state) == MetaPipe) 1 else 0 },
        tp      = Categorical
      )
    }
  }
}

