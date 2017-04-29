package spatial.codegen.pirgen

import spatial.SpatialExp
import spatial.SpatialConfig

import scala.collection.mutable

trait PIRSplitter extends PIRSplitting with PIRRetiming {
  val IR: SpatialExp with PIRCommonExp
  import IR._

  override val name = "PIR Splitting"
  override val recurse = Always

  val mappingIn  = mutable.HashMap[Expr, List[CU]]()
  val mappingOut = mutable.HashMap[Expr, List[List[CU]]]()

  lazy val PCUMax = CUCost(
    sIn=SpatialConfig.sIn_PCU,
    sOut=SpatialConfig.sOut_PCU,
    vIn=SpatialConfig.vIn_PCU,
    vOut=SpatialConfig.vOut_PCU,
    comp=STAGES,
    regsMax = SpatialConfig.regs_PCU
  )
  lazy val PMUMax = MUCost(
    sIn=SpatialConfig.sIn_PMU,
    sOut=SpatialConfig.sOut_PMU,
    vIn=SpatialConfig.vIn_PMU,
    vOut=SpatialConfig.vOut_PMU,
    comp=READ_WRITE,
    regsMax = SpatialConfig.regs_PMU
  )

  override def process[S:Type](b: Block[S]) = {
    try {
      visitBlock(b)

      val cuMapping = mappingIn.keys.flatMap{k =>
        mappingIn(k).zip(mappingOut(k)).map { case (cuIn, cuOuts) =>
          if (cuOuts.isEmpty)
            throw new Exception(c"$k was split into 0 CUs?")

          cuIn.asInstanceOf[ACU] -> cuOuts.head.asInstanceOf[ACU]
        }
      }.toMap
      swapCUs(cuMapping)
    }
    catch {case e: SplitException =>
      error("Failed splitting")
      error(e.msg)
      sys.exit(-1)
    }
    b
  }

  override protected def visit(lhs: Sym[_], rhs: Op[_]) {
    if (mappingIn.contains(lhs))
      mappingOut(lhs) = mappingIn(lhs).map(split)
  }

  def split(cu: CU): List[CU] = {
    if (cu.allStages.nonEmpty) {
      val others = mutable.ArrayBuffer[CU]()
      others ++= mappingOut.values.flatten.flatten

      val cus = splitCU(cu, PCUMax, PMUMax, others)
      retime(cus, others)

      cus.foreach{cu =>
        val cost = getUtil(cu, others)
        others += cu
      }

      cus
    }
    else List(cu)
  }

}
