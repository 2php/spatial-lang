package spatial

import spatial.codegen.targets._

object SpatialConfig {
  import argon.Config._

  lazy val HOME = sys.env("SPATIAL_HOME")
  var target: FPGATarget = DefaultTarget

  var enableDSE: Boolean = getProperty("spatial.dse", "false") == "true"
  var enableScala: Boolean = getProperty("spatial.scala", "true") == "true"
  var enableChisel: Boolean = getProperty("spatial.chisel", "false") == "true"
  var enableNaming: Boolean = getProperty("spatial.naming", "false") == "true"
}