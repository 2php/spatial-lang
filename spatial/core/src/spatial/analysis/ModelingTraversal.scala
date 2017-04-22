package spatial.analysis

import spatial.models.LatencyModel

import scala.collection.mutable

trait ModelingTraversal extends SpatialTraversal { traversal =>
  import IR._

  lazy val latencyModel = new LatencyModel{val IR: traversal.IR.type = traversal.IR }

  protected override def preprocess[S: Type](block: Block[S]) = {
    // latencyOf.updateModel(target.latencyModel) // TODO: Update latency model with target-specific values
    inHwScope = false
    inReduce = false
    super.preprocess(block)
  }

  // --- State
  var inHwScope = false // In hardware scope
  var inReduce = false  // In tight reduction cycle (accumulator update)
  def latencyOf(e: Exp[_]) = if (inHwScope) latencyModel(e, inReduce) else 0L

  // TODO: Could optimize further with dynamic programming
  def latencyOfPipe(b: Block[_]): Long = {
    val scope = getStages(b)
    val paths = mutable.HashMap[Exp[_],Long]()
    //debug(s"Pipe latency $b:")

    def quickDFS(cur: Exp[_]): Long = cur match {
      case Def(d) if scope.contains(cur) && !isGlobal(cur) =>
        //debug(s"Visit $cur in quickDFS")
        val deps = exps(d)
        if (deps.isEmpty) {
          if (effectsOf(cur).isPure) warn(cur.ctx, s"[Compiler] $cur = $d has no dependencies but is not global?")
          latencyOf(cur)
        }
        else {
          latencyOf(cur) + deps.map{e => paths.getOrElseUpdate(e, quickDFS(e))}.max
        }
      case _ => 0L
    }
    if (scope.isEmpty) 0L else exps(b).map{e => paths.getOrElseUpdate(e, quickDFS(e)) }.max
  }
  def latencyOfCycle(b: Block[Any]): Long = {
    val outerReduce = inReduce
    inReduce = true
    val out = latencyOfPipe(b)
    inReduce = outerReduce
    out
  }

  class GetOrElseUpdateFix[K,V](x: mutable.Map[K,V]) {
    def getOrElseAdd(k: K, v: => V): V = if (x.contains(k)) x(k) else { val value = v; x(k) = value; value }
  }
  implicit def getOrUpdateFix[K,V](x: mutable.Map[K,V]): GetOrElseUpdateFix[K,V] = new GetOrElseUpdateFix[K,V](x)

  // Not a true traversal. Should it be?
  def pipeDelaysAndGaps(b: Block[_], oos: Map[Exp[_],Long] = Map.empty) = {
    val scope = getStages(b).filterNot(s => isGlobal(s)).filter{e => e.tp == VoidType || Bits.unapply(e.tp).isDefined }
    val delays = mutable.HashMap[Exp[_],Long]() ++ scope.map{node => node -> 0L}
    val paths  = mutable.HashMap[Exp[_],Long]() ++ oos

    def fullDFS(cur: Exp[_]): Long = cur match {
      case Def(d) if scope.contains(cur) =>
        val deps = exps(d) filter (scope contains _)

        if (deps.nonEmpty) {
          val dlys = deps.map{e => paths.getOrElseAdd(e, fullDFS(e)) }
          val critical = dlys.max

          deps.zip(dlys).foreach{ case(dep, path) =>
            if (path < critical && (critical - path) > delays(dep))
              delays(dep) = critical - path
          }
          dbgs(c"${str(cur)} [delay = max(" + dlys.mkString(", ") + s") + ${latencyOf(cur)}]")
          critical + latencyOf(cur)
        }
        else latencyOf(cur)

      case s => paths.getOrElse(s, 0L) // Get preset out of scope delay
      // Otherwise assume 0 offset
    }
    if (scope.nonEmpty) {
      val deps = exps(b) filter (scope contains _)
      deps.foreach{e => paths.getOrElseAdd(e, fullDFS(e)) }
    }

    val delaysOut = Map[Exp[_],Long]() ++ delays
    val pathsOut = Map[Exp[_],Long]() ++ paths
    (pathsOut, delaysOut)
  }

  def pipeDelays(b: Block[_], oos: Map[Exp[_],Long] = Map.empty) = pipeDelaysAndGaps(b, oos)._1
  def pipeGaps(b: Block[_], oos: Map[Exp[_],Long] = Map.empty) = pipeDelaysAndGaps(b, oos)._2

}

