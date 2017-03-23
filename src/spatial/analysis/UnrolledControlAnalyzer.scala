package spatial.analysis
import scala.collection.mutable.HashMap

trait UnrolledControlAnalyzer extends ControlSignalAnalyzer {
  import IR._

  override val name = "Unrolled Control Analyzer"

  var memStreams = Set[(Exp[_], String)]()
  var argPorts = Set[(Exp[_], String)]()

  private def visitUnrolled(ctrl: Exp[_])(blk: => Unit) = {
    visitCtrl((ctrl,false))(blk)
  }

  override protected def preprocess[S:Staged](block: Block[S]) = {
    memStreams = Set[(Exp[_], String)]()
    argPorts = Set[(Exp[_], String)]()
    super.preprocess(block)
  }

  override def addCommonControlData(lhs: Sym[_], rhs: Op[_]) = {
    rhs match {
      case GetMem(dram, _) => memStreams += ((dram, "output"))
      case SetMem(dram, _) => memStreams += ((dram, "input"))
      case e: ArgInNew[_] => argPorts += ((lhs, "input"))
      case e: ArgOutNew[_] => argPorts += ((lhs, "output"))
      case _ =>
    }
    super.addCommonControlData(lhs, rhs)
  }

  override protected def analyze(lhs: Sym[_], rhs: Op[_]) = rhs match {
    case e: UnrolledForeach =>
      visitUnrolled(lhs){ visitBlock(e.func) }

    case e: UnrolledReduce[_,_] =>
      visitUnrolled(lhs){ visitBlock(e.func) }
      isAccum(e.accum) = true
      parentOf(e.accum) = lhs

    case _ => super.analyze(lhs,rhs)
  }
}
