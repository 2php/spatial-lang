package spatial.interpreter

import argon.core._
import argon.nodes._
import spatial.nodes._
import argon.interpreter.{Interpreter => AInterpreter}
import math.BigDecimal
//import collection.mutable.Queue
import java.util.concurrent.{ LinkedBlockingQueue => Queue }
import java.util.concurrent.TimeUnit
import spatial.SpatialConfig

object IStream {
  var streamsIn = Map[String, Queue[Any]]()
  var streamsOut = Map[String, Queue[Any]]()

  def addStreamIn(name: String) =
    streamsIn += ((name, new Queue[Any]()))

  def addStreamOut(name: String) =
    streamsOut += ((name, new Queue[Any]()))
}

trait IStream extends AInterpreter {


  override def matchNode  = super.matchNode.orElse {

    case StreamInNew(bus) =>
      val k = bus.toString
      if (!IStream.streamsIn.contains(k))
        IStream.addStreamIn(k)

      IStream.streamsIn(k)

    case StreamOutNew(bus) =>
      val k = bus.toString
      if (!IStream.streamsOut.contains(k))
        IStream.addStreamOut(k)

      IStream.streamsOut(k)
      

    case StreamRead(a: Sym[_], b) =>
      val q = eval[Queue[Any]](a)
      var v: Any = null

      while (v == null && !Interpreter.closed) {
        if (SpatialConfig.debug)
          println("Waiting for new input in " + a + "...")

        v = q.poll(1000, TimeUnit.MILLISECONDS)

        if (v == null && SpatialConfig.debug) {
          println("No new input after 1s. q to quit or any key to continue waiting")
          if (io.StdIn.readLine() == "q")
            System.exit(0)
        }
          
      }

      eval[Any](v)

    case StreamWrite(a, EAny(b), EBoolean(cond)) =>
      if (cond) {
        val q = eval[Queue[Any]](a)
        q.put(b)
        println("Push " + b + " to " + a)
      }
      

  }

}


