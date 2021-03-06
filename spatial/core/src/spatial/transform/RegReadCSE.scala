package spatial.transform

import argon.core._
import argon.transform.ForwardTransformer
import spatial.aliases._
import spatial.metadata._
import spatial.nodes._
import spatial.utils._

case class RegReadCSE(var IR: State) extends ForwardTransformer {
  override val name = "Register Read CSE"

  // Mechanism to track duplicates that are no longer needed due to CSE'd register reads
  var csedDuplicates = Map[Exp[_], Seq[Int]]()
  private def removeDuplicates(reg: Exp[_], dups: Seq[Int]) = {
    csedDuplicates += reg -> (dups ++ csedDuplicates.getOrElse(reg, Nil))
  }

  override protected def postprocess[T:Type](block: Block[T]): Block[T] = {
    // Remove CSE'd register duplicates from the metadata
    /*for ((k,v) <- subst) {
      dbg(c"$k -> $v")
    }*/

    for ((reg,csed) <- csedDuplicates) {
      val orig = duplicatesOf(reg)
      //val csedCounts = csed.groupBy{x=> x}.mapValues(_.length)
      val duplicates = orig.zipWithIndex.filter{case (dup,i) => !csed.contains(i) }
      /*val removed = csedCounts.getOrElse(i,0)
        if (removed >= dup.)
      }*/
      duplicatesOf(reg) = duplicates.map(_._1)

      val remap = duplicates.map(_._2).zipWithIndex

      val mapping = remap.toMap

      val writers = writersOf(reg).map{case (n,c) => (f(n), (f(c._1),c._2)) }
      val readers = readersOf(reg).map{case (n,c) => (f(n), (f(c._1),c._2)) }
      val accesses = (writers ++ readers).map(_.node).distinct

      dbg("")
      dbg(u"$reg")
      dbg(c"  CSEd duplicates: $csed")
      dbg(c"  Mapping: ")
      remap.foreach{case (i,i2) => dbg(c"  $i -> $i2") }
      accesses.foreach{access =>
        val origDispatch = dispatchOf.get(access, reg)
        val origPorts = portsOf.get(access,reg)
        val csedDispatch = origDispatch.map{orig => orig.flatMap{o => mapping.get(o)} }
        val csedPorts = origPorts.map{orig => orig.flatMap{case (i,ps) => mapping.get(i).map{i2 => i2 -> ps }} }

        csedDispatch.foreach{ds => dispatchOf(access, reg) = ds }
        csedPorts.foreach{ps => portsOf.set(access, reg, ps) }

        dbg(u"  ${str(access)}: ")
        dbg(u"    " + origDispatch.map(_.toString).getOrElse("") + " => " + csedDispatch.map(_.toString).getOrElse(""))
        dbg(u"    " + origPorts.map(_.toString).getOrElse("") + " => " + csedPorts.map(_.toString).getOrElse(""))
      }
    }

    super.postprocess(block)
  }

  var inInnerCtrl: Boolean = false
  def inInner[A](x: => A): A = {
    val prev = inInnerCtrl
    inInnerCtrl = true
    val result = x
    inInnerCtrl = prev
    result
  }

  // TODO: This creates unused register duplicates in metadata if the inner loop in question was previously unrolled
  // How to handle this?
  override def transform[T:Type](lhs: Sym[T], rhs: Op[T])(implicit ctx: SrcCtx): Exp[T] = rhs match {
    case e@RegRead(reg) if inInnerCtrl =>
      dbg(c"Found reg read $lhs = $rhs")
      val rhs2 = RegRead(f(reg))(typ[T],mbits(e.bT)) // Note that this hasn't been staged yet, only created the node
      val effects = effectsOf(lhs).mirror(f)
      val deps = depsOf(lhs).map(f(_))

      dbg(c"  rhs2 = $rhs2")
      dbg(c"  effects = $effects")
      dbg(c"  deps = $deps")

      val symsWithSameDef = state.defCache.getOrElse(rhs2, Nil) intersect state.context
      val symsWithSameEffects = symsWithSameDef.find{case Effectful(u2, es) => u2 == effects && es == deps }

      dbg(c"  def cache: ${state.defCache.getOrElse(rhs2,Nil)}")
      dbg(c"  context:")
      state.context.foreach{s => dbg(c"    ${str(s)} [effects = ${effectsOf(s)}, deps = ${depsOf(s)}]")}
      dbg(c"  syms with same def: $symsWithSameDef")
      dbg(c"  syms with same effects: $symsWithSameEffects")

      val lhs2 = symsWithSameEffects match {
        case Some(lhs2) =>
          lhs2.addCtx(ctx)
          // Dispatch doesn't necessarily need to be defined yet
          dispatchOf.get(lhs,reg) match {
            case Some(dups) =>
              val same = dispatchOf(lhs2,f(reg))
              val csed = dups diff same
              dbg(c"  Dups: " + dups.mkString(", "))
              dbg(c"  Same: " + same.mkString(", "))
              dbg(c"  CSEd: " + csed.mkString(", "))
              removeDuplicates(f(reg), csed.toSeq)
            case None => // No action
          }
          lhs2.asInstanceOf[Exp[T]]

        case None =>
          val lhs2 = mirror(lhs,rhs)
          getDef(lhs2).foreach{d => state.defCache += d -> syms(lhs2).toList }
          lhs2
      }
      dbg(c"  ${str(lhs2)}")
      lhs2

    case _ if isInnerControl(lhs) =>
      dbgs(str(lhs))
      inInner{ super.transform(lhs,rhs) }
    case _ =>
      dbgs(str(lhs))
      super.transform(lhs,rhs)
  }
}
