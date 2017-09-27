package spatial

import argon.ArgonArgParser
import argon.core.Config
import argon.util.Report._

class SpatialArgParser extends ArgonArgParser {

  override def scriptName = "spatial"
  override def description = "CLI for spatial"
  //not sur yet if we must optional() // TODO: ???


  parser.opt[Unit]("synth").action{ (_,_) =>
    SpatialConfig.enableSynth = true
    SpatialConfig.enableSim = false
    SpatialConfig.enableInterpret = false        
  }.text("enable codegen to chisel + cpp (Synthesis) (disable sim) [false]")

  parser.opt[Unit]("retiming").action{ (_,_) =>
    SpatialConfig.enableRetiming = true
  }.text("enable retiming [false]")

  parser.opt[Unit]("retime").action{ (_,_) =>
    SpatialConfig.enableRetiming = true
  }.text("enable retiming [false]")

  parser.opt[Unit]("sim").action { (_,_) =>
    SpatialConfig.enableSim = true
    SpatialConfig.enableInterpret = false
    SpatialConfig.enableSynth = false            
  }.text("enable codegen to Scala (Simulation) (disable synth) [true]")

  parser.opt[Unit]("interpreter").action { (_,_) =>
    SpatialConfig.enableInterpret = true
    SpatialConfig.enableSim = false
    SpatialConfig.enableSynth = false        
  }.text("enable interpreter")

  parser.arg[String]("args...").unbounded().optional().action( (x, _) => {
    SpatialConfig.inputs = Array(x) ++ SpatialConfig.inputs  
  }).text("args inputs for the interpreter")
  
  parser.opt[String]("fpga").action( (x,_) =>
    SpatialConfig.targetName = x
  ).text("Set name of FPGA target [Default]")

  parser.opt[Unit]("dse").action( (_,_) =>
    SpatialConfig.enableDSE = true
  ).text("enables design space exploration [false]")

  parser.opt[Unit]("bruteforce").action { (_, _) =>
    SpatialConfig.enableDSE = true
    SpatialConfig.heuristicDSE = false
    SpatialConfig.bruteForceDSE = true
    SpatialConfig.experimentDSE = false
  }
  parser.opt[Unit]("heuristic").action { (_, _) =>
    SpatialConfig.enableDSE = true
    SpatialConfig.heuristicDSE = true
    SpatialConfig.bruteForceDSE = false
    SpatialConfig.experimentDSE = false
  }
  parser.opt[Unit]("experiment").action { (_, _) =>
    SpatialConfig.enableDSE = true
    SpatialConfig.heuristicDSE = true
    SpatialConfig.bruteForceDSE = false
    SpatialConfig.experimentDSE = true
  }

  parser.opt[Unit]("retiming").action( (_,_) =>
    SpatialConfig.enableRetiming = true
  ).text("enables inner pipeline retiming [false]")

  parser.opt[Unit]("retime").action( (_,_) =>
    SpatialConfig.enableRetiming = true
  ).text("enables inner pipeline retiming [false]")

  parser.opt[Unit]("naming").action( (_,_) =>
    SpatialConfig.enableNaming = true
  ).text("generates the debug name for all syms, rather than \"x${s.id}\" only'")

  parser.opt[Unit]("syncMem").action { (_,_) => // Must necessarily turn on retiming
    SpatialConfig.enableSyncMem = true
    SpatialConfig.enableRetiming = true
  }.text("Turns all SRAMs into fringe.SRAM (i.e. latched read addresses)")

  parser.opt[Unit]("instrumentation").action { (_,_) => // Must necessarily turn on retiming
    SpatialConfig.enableInstrumentation = true
  }.text("Turns on counters for each loop to assist in balancing pipelines")

  parser.opt[Unit]("instrument").action { (_,_) => // Must necessarily turn on retiming
    SpatialConfig.enableInstrumentation = true
  }.text("Turns on counters for each loop to assist in balancing pipelines")

  parser.opt[Unit]("cheapFifo").action { (_,_) => // Must necessarily turn on retiming
    SpatialConfig.useCheapFifos = true
  }.text("Turns on cheap fifos where accesses must be multiples of each other and not have lane-enables")
  parser.opt[Unit]("cheapFifos").action { (_,_) => // Must necessarily turn on retiming
    SpatialConfig.useCheapFifos = true
  }.text("Turns on cheap fifos where accesses must be multiples of each other and not have lane-enables")

  parser.opt[Unit]("tree").action( (_,_) =>
    SpatialConfig.enableTree = true
  ).text("enables logging of controller tree for visualizing app structure")

  parser.opt[Unit]("dot").action( (_,_) =>
    SpatialConfig.enableDot = true
  ).text("enables dot generation")

  parser.opt[Unit]("pir").action { (_,_) =>
    SpatialConfig.enableSim = false
    SpatialConfig.enableSynth = false
    SpatialConfig.enablePIR = true
  }.text("enables PIR generation")

  parser.opt[String]("pirsrc").action { (x, c) =>
    SpatialConfig.pirsrc = x
  }.text("copy directory for generated pir source")

  parser.opt[Unit]("cgra+").action{ (_,_) =>
    SpatialConfig.enableSim = false
    SpatialConfig.enableSynth = false
    SpatialConfig.enablePIR = true
    SpatialConfig.enableSplitting = true
  }

  parser.opt[Unit]("cgra*").action{ (_,_) =>
    SpatialConfig.enableSim = false
    SpatialConfig.enableSynth = false
    SpatialConfig.enablePIR = true
    SpatialConfig.enableArchDSE = true
    SpatialConfig.enableSplitting = true
  }

  parser.opt[Unit]("pirsim").action{ (_,_) =>
    warn("Here be dragons.")
    SpatialConfig.enableSim = false
    SpatialConfig.enableSynth = true
    SpatialConfig.enablePIR = false
    SpatialConfig.enablePIRSim = true
    SpatialConfig.enableSplitting = true
  }

  parser.opt[Int]("threads").action{ (t,_) =>
    SpatialConfig.threads = t
  }

  parser.opt[Unit]("fast").action{ (_,_) =>
    SpatialConfig.useBasicBlocks = true
  }.text("[EXPERIMENTAL] Use basic blocks")

  parser.opt[Unit]("xfast").action{ (_,_) =>
    SpatialConfig.useBasicBlocks = true
    argon.core.Config.verbosity = -2
  }.text("[EXPERIMENTAL] Use basic blocks")


  parser.opt[Unit]("affine").action{ (_,_) =>
    SpatialConfig.useAffine = true
    Config.useAffine = true
  }

}
