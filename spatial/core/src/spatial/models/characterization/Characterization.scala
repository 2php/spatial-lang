package spatial.models.characterization

import spatial._
import argon.core.Config
import argon.util.Report._

import java.io.{File, PrintWriter}
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import sys.process._

trait AllBenchmarks
    extends Benchmarks with SpatialCompiler
    with FIFOs
    with Primitives
    with RegFiles
    with Regs
    with SRAMs 

object Characterization extends AllBenchmarks {
  def area(dir: JString): Map[JString, scala.Double] = {
    val output = Seq("~/spatial-lang/bin/scrape.py", dir).!!
    val pairs = output.split("\n").map(_.split(","))
    val map = pairs.map { case Array(k, v) => k -> v.toDouble }.toMap
    map
  }

  val pw = new PrintWriter(new File("characterization.csv"))

  def storeArea(name: JString, area: Map[JString, scala.Double]) = {
    pw.synchronized {
      area.foreach { case (comp, v) => pw.println(name + ',' + comp +',' + v) }
    }
  }

  val NUM_PAR_SYNTH: scala.Int = 2
  val stagingArgs = scala.Array("--synth")

  def main(args: scala.Array[JString]) {
    val programs: Seq[NamedSpatialProg] = gens.flatMap(_.expand)

    println("Number of programs: " + programs.length)

    var i = 1458
    val prev = programs.take(i).map{x => x._1 }

    val chiseled = prev ++ programs.drop(i).flatMap{x => //programs.slice(6,7).map{x =>
      val name = x._1
      initConfig(stagingArgs)
      Config.name = name
      Config.genDir = s"${Config.cwd}/gen/$name"
      Config.logDir = s"${Config.cwd}/logs/$name"
      Config.verbosity = -2
      Config.showWarn = false
      Console.print(s"Compiling #$i: " + name + "...")
      resetState()
      _IR.useBasicBlocks = true // experimental for faster scheduling
      val result = try {
        compileProgram(x._2)
        Console.println("done")
        Some(x._1)
      }
      catch {case e:Throwable =>
        Console.println("fail")
        Config.verbosity = 4
        withLog(Config.logDir,"exception.log") {
          log(e.getMessage)
          log(e.getCause)
          e.getStackTrace.foreach{line => log("  " + line) }
        }
        None
      }
      i += 1
      result
    }

    val exec = java.util.concurrent.Executors.newFixedThreadPool(NUM_PAR_SYNTH)
    implicit val ec = ExecutionContext.fromExecutor(exec)

    val workers = chiseled.map(x => Future {
       storeArea(x, area(x))
     })

    try {
      workers.foreach(Await.ready(_, Duration.Inf))
    } catch {
      case e: Throwable =>
        e.printStackTrace
    } finally {
      Console.println("COMPLETED")
      exec.shutdown()
      pw.close()
    }
  }

}
