package spatial.targets

import spatial.models._
import spatial.models.altera._

object Plasticine extends FPGATarget {
  def name = "Default"
  val burstSize = 512 // in bits

  //TODO: No model for plasticine yet
  override type Area[T] = AlteraArea[T]
  override type Sum[T]  = AlteraAreaSummary[T]
  def areaMetric: AreaMetric[Area] = AlteraAreaMetric
  override lazy val areaModel: AreaModel[Area,Sum] = new StratixVAreaModel
  override lazy val latencyModel: LatencyModel = new StratixVLatencyModel
  def capacity: AlteraAreaSummary[Double] = AlteraAreaSummary(alms=262400, regs=524800, dsps=1963, bram=2567, channels=13)
}

