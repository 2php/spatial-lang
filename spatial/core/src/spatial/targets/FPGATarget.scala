package spatial.targets

import argon.core.State
import spatial.analysis.{AreaAnalyzer, LatencyAnalyzer}
import spatial.models._

case class Pin(name: String) {
  override def toString: String = name
}

abstract class Bus {
  def length: Int
}

case class PinBus(valid: Pin, data: Seq[Pin]) extends Bus {
  override def toString: String = "Bus(" + valid.toString + ": " + data.mkString(", ") + ")"
  def length: Int = data.length
}

object Bus {
  def apply(valid: Pin, data: Pin*) = PinBus(valid, data)
  def apply(valid: String, data: String*) = PinBus(Pin(valid), data.map(Pin(_)))
}

// TODO: Hack - remove later
case object GPInput extends Bus { val length = 32 }
case object GPOutput extends Bus { val length = 32 }

abstract class FPGATarget {
  def name: String    // FPGA name
  def burstSize: Int  // Size of DRAM burst (in bits)

  val FIELDS: Array[String] // Area resource fields
  val DSP_CUTOFF: Int       // Smallest integer addition (in bits) which uses DSPs

  lazy implicit val AREA_CONFIG: AreaConfig[Double] = AreaConfig[Double](FIELDS, 0.0)
  lazy implicit val MODEL_CONFIG: AreaConfig[NodeModel] = AreaConfig[NodeModel](FIELDS, Right(0.0))
  lazy implicit val LINEAR_CONFIG: AreaConfig[LinearModel] = AreaConfig[LinearModel](FIELDS, LinearModel(Seq(Prod(0,Nil)),Set.empty))

  def areaModel: AreaModel        // Area model for this target
  def latencyModel: LatencyModel  // Latency model for this target
  def capacity: Area              // Device resource maximum, in terms of FIELDS

  final def areaAnalyzer(state: State): AreaAnalyzer = AreaAnalyzer(state, areaModel, latencyModel)
  final def cycleAnalyzer(state: State): LatencyAnalyzer = LatencyAnalyzer(state, latencyModel)
}

object Targets {
  var targets: Set[FPGATarget] = Set.empty
  targets += DefaultTarget

  lazy val Default = spatial.targets.DefaultTarget
  lazy val DE1 = spatial.targets.DE1
  lazy val F1 = spatial.targets.AWS_F1
}