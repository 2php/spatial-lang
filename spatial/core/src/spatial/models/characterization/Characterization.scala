package spatial.models.characterization

import java.lang.{String => JString}

import spatial._
import spatial.dsl._

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

object Characterization extends SpatialCompiler {
  type SpatialProg = () => Unit
  type NamedSpatialProg = (JString, SpatialProg)

  implicit class Zip4[A,B,C,D](x: Tuple4[List[A],List[B],List[C],List[D]]) {
    def zipped: Iterable[Tuple4[A,B,C,D]] = x._1.zip(x._2).zip(x._3).zip(x._4).map{case (((a,b),c),d) => (a,b,c,d) }
  }


  //To implement by Richard
  def area(dir: JString): Map[JString, scala.Int] = {
    Thread.sleep(1000)
    Map(("question of the universe", 42))
  }

  val NUM_PAR_SYNTH: scala.Int = 2

  sealed trait Benchmark {
    def prefix: JString
    def N: Int
    def name: JString = s"${prefix}_$N"
    def eval(): Unit
  }

  case class StaticOp[T:Type:Bits](prefix: JString, f: () => T)(val N: scala.Int) extends Benchmark {
    def eval(): Unit = {
      val outs = List.fill(N)(ArgOut[T])

      Accel {
        outs.foreach{reg => reg := f() }
      }
    }
  }

  case class UnOp[T:Type:Bits](prefix: JString, f: T => T)(val N: scala.Int) extends Benchmark {
    def eval(): Unit = {
      val outs = List.fill(N)(ArgOut[T])
      val ins = List.fill(N)(ArgIn[T])

      ins.foreach(setArg(_, zero[T]))

      Accel {
        ins.zip(outs).foreach { case (in, out) =>
          out := f(in.value)
        }
      }
    }
  }

  def BinOp[T:Type:Bits](prefix: JString, f: (T, T) => T)(N: scala.Int): Benchmark = Bin2Op[T,T](prefix, f)(N)
  case class Bin2Op[T:Type:Bits,R:Type:Bits](prefix: JString, f: (T,T) => R)(val N: scala.Int) extends Benchmark {
    def eval(): Unit = {
      val outs = List.fill(N)(ArgOut[R])
      val insA = List.fill(N)(ArgIn[T])
      val insB = List.fill(N)(ArgIn[T])

      (insA:::insB).foreach(setArg(_, zero[T]))

      Accel {
        (insA, insB, outs).zipped.foreach { case (inA, inB, out) =>
          out := f(inA.value, inB.value)
        }
      }
    }
  }

  def TriOp[T:Type:Bits](prefix: JString, f: (T,T,T) => T)(N: scala.Int): Benchmark = Tri4Op[T,T,T,T](prefix,f)(N)
  case class Tri4Op[A:Type:Bits,B:Type:Bits,C:Type:Bits,R:Type:Bits](prefix: JString, f: (A,B,C) => R)(val N: scala.Int) extends Benchmark {
    def eval(): Unit = {
      val outs = List.fill(N)(ArgOut[R])
      val insA = List.fill(N)(ArgIn[A])
      val insB = List.fill(N)(ArgIn[B])
      val insC = List.fill(N)(ArgIn[C])

      insA.foreach(setArg(_, zero[A]))
      insB.foreach(setArg(_, zero[B]))
      insC.foreach(setArg(_, zero[C]))

      Accel {
        (insA,insB,insC,outs).zipped.foreach { case (inA, inB, inC, out) =>
          out := f(inA.value, inB.value, inC.value)
        }
      }
    }
  }


  case class MetaProgGen(name: JString, Ns: Seq[scala.Int], benchmark: scala.Int => Benchmark) {
    def expand: List[NamedSpatialProg] = Ns.toList.map{n => benchmark(n) }
                                            .map{x => (name + "_" + x.name, () => x.eval()) }
  }

  def bitOps: Seq[scala.Int => Benchmark] = Seq(
    UnOp[Bit]("Not", !_),
    BinOp[Bit]("And", _&&_),
    BinOp[Bit]("Or", _||_),
    BinOp[Bit]("XOr", _^_),
    BinOp[Bit]("Eql", _===_)
  )

  def fixPtOps[S:Int,I:Int,F:Int]: Seq[scala.Int => Benchmark] = Seq(
    UnOp[FixPt[S,I,F]]("Inv", ~_),
    UnOp[FixPt[S,I,F]]("Neg", -_),
    UnOp[FixPt[S,I,F]]("Abs", {x => abs(x) }),
    BinOp[FixPt[S,I,F]]("Min", {(x,y) => min(x,y) }),
    BinOp[FixPt[S,I,F]]("Add", _+_),
    BinOp[FixPt[S,I,F]]("Sub", _-_),
    BinOp[FixPt[S,I,F]]("Mul", _*_),
    BinOp[FixPt[S,I,F]]("Div", _/_),
    BinOp[FixPt[S,I,_0]]("Mod", {(a,b) => wrap(argon.lang.FixPt.mod(a.s,b.s)) }), // HACK - wasn't getting % for some reason
    BinOp[FixPt[S,I,F]]("Or",  _|_),
    BinOp[FixPt[S,I,F]]("And", _&_),
    BinOp[FixPt[S,I,F]]("XOr", _^_),
    Bin2Op[FixPt[S,I,F],Bit]("Lt", _<_),
    Bin2Op[FixPt[S,I,F],Bit]("Leq", _<=_),
    Bin2Op[FixPt[S,I,F],Bit]("Neq", _=!=_), // No clue if these will be virtualized or not
    Bin2Op[FixPt[S,I,F],Bit]("Eql", _===_)
  )

  def fltPtOps[G:Int,E:INT]: Seq[scala.Int => Benchmark] = Seq(
    UnOp[FltPt[G,E]]("Neg", -_),
    UnOp[FltPt[G,E]]("Abs", {x => abs(x) }),
    BinOp[FltPt[G,E]]("Min", {(x,y) => min(x,y) }),
    BinOp[FltPt[G,E]]("Add", _+_),
    BinOp[FltPt[G,E]]("Sub", _-_),
    BinOp[FltPt[G,E]]("Mul", _*_),
    BinOp[FltPt[G,E]]("Div", _/_),
    Bin2Op[FltPt[G,E],Bit]("Lt",_<_),
    Bin2Op[FltPt[G,E],Bit]("Leq",_<=_),
    Bin2Op[FltPt[G,E],Bit]("Neq",_=!=_),
    Bin2Op[FltPt[G,E],Bit]("Eql",_===_)
  )

  // TODO: This should eventually be merged into mathOps
  def fixPtRoundOps[S:INT,I:INT,F:INT]: Seq[scala.Int => Benchmark] = Seq(
    UnOp[FixPt[S,I,F]]("Ceil", {x => ceil(x) }),
    UnOp[FixPt[S,I,F]]("Floor", {x => floor(x) })
  )

  var gens: List[MetaProgGen] = Nil
  // If sbt does it, that makes it ok!
  gens ::= bitOps.map{prog => MetaProgGen("Bit", Seq(50,100,200,400), prog) }
  gens ::= fixPtOps[TRUE,_8,_0].map{prog => MetaProgGen("Int8", Seq(50,100,200,400), prog) }
  gens ::= fixPtOps[TRUE,_16,_0].map{prog => MetaProgGen("Int16", Seq(50,100,200,400), prog) }
  gens ::= fixPtOps[TRUE,_32,_0].map{prog => MetaProgGen("Int32", Seq(50,100,200,400), prog) }
  gens ::= fixPtOps[TRUE,_8,_0].map{prog => MetaProgGen("Int64", Seq(50,100,200,400), prog) }
  gens ::= fltPtOps[_53,_11].map{prog => MetaProgGen("Float", Seq(50,100,200,400), prog) }

  gens ::= fixPtRoundOps[TRUE, _8, _8].map{prog => MetaProgGen("Q8_8", Seq(50,100,200,400), prog) }
  gens ::= fixPtRoundOps[TRUE,_16,_16].map{prog => MetaProgGen("Q16_16", Seq(50,100,200,400), prog) }
  gens ::= fixPtRoundOps[TRUE,_32,_32].map{prog => MetaProgGen("Q32_32", Seq(50,100,200,400), prog) }

  gens +:= MetaProgGen("Mux8", Seq(50,100,200,400), Tri4Op[Bit,Int8,Int8,Int8]("Mux8", {(s,a,b) => mux(s,a,b) }))
  gens +:= MetaProgGen("Mux16", Seq(50,100,200,400), Tri4Op[Bit,Int16,Int16,Int16]("Mux16", {(s,a,b) => mux(s,a,b) }))
  gens +:= MetaProgGen("Mux32", Seq(50,100,200,400), Tri4Op[Bit,Int32,Int32,Int32]("Mux32", {(s,a,b) => mux(s,a,b) }))
  gens +:= MetaProgGen("Mux64", Seq(50,100,200,400), Tri4Op[Bit,Int64,Int64,Int64]("Mux64", {(s,a,b) => mux(s,a,b) }))

  val programs: Seq[NamedSpatialProg] = gens.flatMap(_.expand)

  val stagingArgs = scala.Array("--synth")

  def storeArea(name: JString, area: Map[JString, scala.Int]) = {
    Console.println(name, area)
  }

  def main(args: scala.Array[JString]) {
    val chiseled = programs.map(x => {
      //compileProgram(x._2)
      Thread.sleep(1000)
      Console.println(x._1 + " chisel generated ")
      x._1
    })

    val exec = java.util.concurrent.Executors.newFixedThreadPool(NUM_PAR_SYNTH)
    implicit val ec = ExecutionContext.fromExecutor(exec)

    val workers = chiseled.map(x => Future {
      storeArea(x, area(x))
    })

    workers.foreach(Await.ready(_, Duration.Inf))
    Console.println("COMPLETED")
    exec.shutdown()
  }

}
