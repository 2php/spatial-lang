package spatial.codegen.chiselgen

import argon.codegen.chiselgen.ChiselCodegen
import spatial.api.FIFOExp
import spatial.api.DRAMTransferExp
import spatial.SpatialConfig
import spatial.SpatialExp

trait ChiselGenFIFO extends ChiselCodegen {
  val IR: SpatialExp
  import IR._

  override protected def bitWidth(tp: Staged[_]): Int = {
    tp match { 
      case Bits(bitEv) => bitEv.length
      // case x: StructType[_] => x.fields.head._2 match {
      //   case _: IssuedCmd => 96
      //   case _ => super.bitWidth(tp)
      // }
      case _ => super.bitWidth(tp)
    }
  }

  override def quote(s: Exp[_]): String = {
    if (SpatialConfig.enableNaming) {
      s match {
        case lhs: Sym[_] =>
          val Op(rhs) = lhs
          rhs match {
            case e: FIFONew[_] =>
              s"x${lhs.id}_Fifo"
            case FIFOEnq(fifo:Sym[_],_,_) =>
              s"x${lhs.id}_enqTo${fifo.id}"
            case FIFODeq(fifo:Sym[_],_,_) =>
              s"x${lhs.id}_deqFrom${fifo.id}"
            case _ =>
              super.quote(s)
          }
        case _ =>
          super.quote(s)
      }
    } else {
      super.quote(s)
    }
  } 

  override protected def remap(tp: Staged[_]): String = tp match {
    case tp: FIFOType[_] => src"chisel.collection.mutable.Queue[${tp.child}]"
    case _ => super.remap(tp)
  }

  // override protected def vecSize(tp: Staged[_]): Int = tp.typeArguments.head match {
  //   case tp: Vector[_] => 1
  //   case _ => 1
  // }

  override protected def emitNode(lhs: Sym[_], rhs: Op[_]): Unit = rhs match {
    case op@FIFONew(size)   => 
      val rPar = readersOf(lhs).map { r => 
        r.node match {
          case Def(_: FIFODeq[_]) => 1
          case Def(a@ParFIFODeq(q,ens,_)) => ens match {
            case Op(ListVector(elems)) => elems.length
            case _ => 1
          }
        }
      }.reduce{scala.math.max(_,_)}
      val wPar = writersOf(lhs).map { w =>
        w.node match {
          case Def(_: FIFOEnq[_]) => 1
          case Def(a@ParFIFOEnq(q,ens,_)) => ens match {
            case Op(ListVector(elems)) => elems.length
            case _ => 1
          }
        }
      }.reduce{scala.math.max(_,_)}
      val width = bitWidth(lhs.tp.typeArguments.head)
      emit(src"""val ${lhs}_wdata = Wire(Vec($wPar, UInt(${width}.W)))""")
      emit(src"""val ${lhs}_readEn = Wire(Bool())""")
      emit(src"""val ${lhs}_writeEn = Wire(Bool())""")
      emitGlobal(src"""val ${lhs} = Module(new FIFO($rPar, $wPar, $size, $width)) // ${nameOf(lhs).getOrElse("")}""".replace(".U(32.W)",""))
      emit(src"""val ${lhs}_rdata = ${lhs}.io.out""")
      emit(src"""${lhs}.io.in := ${lhs}_wdata""")
      emit(src"""${lhs}.io.pop := ${lhs}_readEn""")
      emit(src"""${lhs}.io.push := ${lhs}_writeEn""")

    case FIFOEnq(fifo,v,en) => 
      val writer = writersOf(fifo).head.ctrlNode  // Not using 'en' or 'shuffle'
      emit(src"""${fifo}_writeEn := ${writer}_ctr_en & $en """)
      fifo.tp.typeArguments.head match { 
        case FixPtType(s,d,f) => if (hasFracBits(fifo.tp.typeArguments.head)) {
            emit(src"""${fifo}_wdata := Vec(List(${v}.number))""")
          } else {
            emit(src"""${fifo}_wdata := Vec(List(${v}))""")
          }
        case _ => emit(src"""${fifo}_wdata := Vec(List(${v}))""")
      }


    case FIFODeq(fifo,en,z) => 
      val reader = readersOf(fifo).head.ctrlNode  // Assuming that each fifo has a unique reader
      emit(src"""${fifo}_readEn := ${reader}_ctr_en & $en""")
      fifo.tp.typeArguments.head match { 
        case FixPtType(s,d,f) => if (hasFracBits(fifo.tp.typeArguments.head)) {
            emit(s"""val ${quote(lhs)} = Utils.FixedPoint($s,$d,$f,${quote(fifo)}_rdata(0))""")
          } else {
            emit(src"""val ${lhs} = ${fifo}_rdata(0)""")
          }
        case _ => emit(src"""val ${lhs} = ${fifo}_rdata(0)""")
      }

    case _ => super.emitNode(lhs, rhs)
  }
}
