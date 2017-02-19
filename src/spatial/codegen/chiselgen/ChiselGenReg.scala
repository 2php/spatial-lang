package spatial.codegen.chiselgen

import argon.codegen.chiselgen.ChiselCodegen
import spatial.api.RegExp
import spatial.SpatialConfig
import spatial.SpatialExp

trait ChiselGenReg extends ChiselCodegen {
  val IR: RegExp with SpatialExp
  import IR._

  var argIns: List[Sym[Reg[_]]] = List()
  var argOuts: List[Sym[Reg[_]]] = List()
  private var nbufs: List[(Sym[Reg[_]], Int)]  = List()

  override def quote(s: Exp[_]): String = {
    if (SpatialConfig.enableNaming) {
      s match {
        case lhs: Sym[_] =>
          val Op(rhs) = lhs
          rhs match {
            case ArgInNew(_)=> s"x${lhs.id}_argin"
            case ArgOutNew(_) => s"x${lhs.id}_argout"
            case RegNew(_) => s"x${lhs.id}_reg"
            case RegRead(reg:Sym[_]) => s"x${lhs.id}_readx${reg.id}"
            case RegWrite(reg:Sym[_],_,_) => s"x${lhs.id}_writex${reg.id}"
            case _ => super.quote(s)
          }
        case _ => super.quote(s)
      }
    } else {
      super.quote(s)
    }
  } 

  override protected def remap(tp: Staged[_]): String = tp match {
    case tp: RegType[_] => src"Array[${tp.typeArguments.head}]"
    case _ => super.remap(tp)
  }

  override protected def emitNode(lhs: Sym[_], rhs: Op[_]): Unit = rhs match {
    case ArgInNew(init)  => 
      argIns = argIns :+ lhs.asInstanceOf[Sym[Reg[_]]]
      emit(src"val $lhs = Array($init)")
    case ArgOutNew(init) => 
      argOuts = argOuts :+ lhs.asInstanceOf[Sym[Reg[_]]]
      emit(src"val $lhs = Array($init)")
    case RegNew(init)    => 
      val duplicates = duplicatesOf(lhs)  
      duplicates.zipWithIndex.foreach{ case (d, i) => 
        reduceType(lhs) match {
          case Some(fps: ReduceFunction) => 
            if (d.isAccum) {
              emitGlobal(src"""val ${lhs}_${i} = Module(new UIntAccum(32,"add"))""")
            } else {
              if (d.depth > 1) {
                nbufs = nbufs :+ (lhs.asInstanceOf[Sym[Reg[_]]], i)
                emitGlobal(src"val ${lhs}_${i} = Module(new NBufFF(${d.depth}, 32)) // ${nameOf(lhs).getOrElse("")}")
              } else {
                emitGlobal(src"val ${lhs}_${i} = Module(new FF(32)) // ${nameOf(lhs).getOrElse("")}")
              }              
            }
          case _ =>
            if (d.depth > 1) {
              nbufs = nbufs :+ (lhs.asInstanceOf[Sym[Reg[_]]], i)
              emitGlobal(src"val ${lhs}_${i} = Module(new NBufFF(${d.depth}, 32)) // ${nameOf(lhs).getOrElse("")}")
            } else {
              emitGlobal(src"val ${lhs}_${i} = Module(new FF(32)) // ${nameOf(lhs).getOrElse("")}")
            }
        } // TODO: Figure out which reg is really the accum
      }
    case RegRead(reg)    => 
      if (isArgIn(reg)) {
        emitGlobal(src"""val $lhs = io.ArgIn.ports(${argIns.indexOf(reg)})""")
      } else {
        val inst = dispatchOf(lhs, reg).head // Reads should only have one index
        val port = portsOf(lhs, reg, inst)
        val duplicates = duplicatesOf(reg)
        if (duplicates(inst).isAccum) {
          reduceType(reg) match {
            case Some(fps: ReduceFunction) => 
              fps match {
                case FixPtSum =>
                  emit(src"""val ${lhs} = ${reg}_initval // get reset value that was created by reduce controller""")
                case _ => 
                  emit(src"""TODO: Emit reduction for some other kind of reduction function, not sure how to read""")
              }
            case _ =>
              emit(s"TODO: Instance $inst of reg $reg is an accum, but has no reduction function associated with it!")
          }
        } else {
          emit(src"""val ${lhs} = ${reg}_${inst}.read(${port.head})""")
        }
      }


    case RegWrite(reg,v,en) => 
      val parent = writersOf(reg).find{_.node == lhs}.get.ctrlNode
      if (isArgOut(reg)) {
        emit(src"""val $reg = Reg(init = 0.U) // HW-accessible register""")
        emit(src"""$reg := Mux($en & ${parent}_en, $v, $reg)""")
        emit(src"""io.ArgOut.ports(${argOuts.indexOf(reg)}) := $reg // ${nameOf(reg).getOrElse("")}""")
      } else {         
        reduceType(reg) match {
          case Some(fps: ReduceFunction) => // is an accumulator
            duplicatesOf(reg).zipWithIndex.foreach { case (dup, ii) =>
              fps match {
                case FixPtSum =>
                  if (dup.isAccum) {
                    emit(src"""${reg}_${ii}.io.next := ${v}""")
                    emit(src"""${reg}_${ii}.io.enable := ${reg}_wren""")
                    emit(src"""${reg}_${ii}.io.reset := Utils.delay(${reg}_resetter, 2)""")
                    emit(src"""${reg} := ${reg}_${ii}.io.output""")
                    emitGlobal(src"""val ${reg} = Wire(UInt())""")
                  } else {
                    val ports = portsOf(lhs, reg, ii) // Port only makes sense if it is not the accumulating duplicate
                    emit(src"""${reg}_${ii}.write($reg, $en & Utils.delay(${reg}_wren,1) /* TODO: This delay actually depends on latency of reduction function */, false.B, List(${ports.mkString(",")}))""")
                  }
                case _ =>
                  emit(src"""TODO: Emit reduction for some other kind of reduction function, not sure how to specialize""")
              }
            }
          case _ => // Not an accum
            duplicatesOf(reg).zipWithIndex.foreach { case (dup, ii) =>
              val ports = portsOf(lhs, reg, ii) // Port only makes sense if it is not the accumulating duplicate
              emit(src"""${reg}_${ii}.write($v, $en & ${parent}_datapath_en, false.B, List(${ports.mkString(",")}))""")
            }
        }
      }

    case _ => super.emitNode(lhs, rhs)
  }

  override protected def emitFileFooter() {
    withStream(getStream("BufferControlCxns")) {
      nbufs.foreach{ case (mem, i) => 
        // TODO: Does david figure out which controllers' signals connect to which ports on the nbuf already? This is kind of complicated
        val readers = readersOf(mem)
        val writers = writersOf(mem)
        val readPorts = readers.filter{reader => dispatchOf(reader, mem).contains(i) }.groupBy{a => portsOf(a, mem, i) }
        val writePorts = writers.filter{writer => dispatchOf(writer, mem).contains(i) }.groupBy{a => portsOf(a, mem, i) }
        val allSiblings = childrenOf(parentOf(readPorts.map{case (_, readers) => readers.flatMap{a => topControllerOf(a,mem,i)}.head}.head.node).get)
        val readSiblings = readPorts.map{case (_,r) => r.flatMap{ a => topControllerOf(a, mem, i)}}.filter{case l => l.length > 0}.map{case all => all.head.node}
        val writeSiblings = writePorts.map{case (_,r) => r.flatMap{ a => topControllerOf(a, mem, i)}}.filter{case l => l.length > 0}.map{case all => all.head.node}
        val writePortsNumbers = writeSiblings.map{ sw => allSiblings.indexOf(sw) }
        val readPortsNumbers = readSiblings.map{ sr => allSiblings.indexOf(sr) }
        val firstActivePort = math.min( readPortsNumbers.min, writePortsNumbers.min )
        val lastActivePort = math.max( readPortsNumbers.max, writePortsNumbers.max )
        val numStagesInbetween = lastActivePort - firstActivePort

        (0 to numStagesInbetween).foreach { port =>
          val ctrlId = port + firstActivePort
          val node = allSiblings(ctrlId)
          val rd = if (readPortsNumbers.toList.contains(ctrlId)) {"read"} else ""
          val wr = if (writePortsNumbers.toList.contains(ctrlId)) {"write"} else ""
          val empty = if (rd == "" & wr == "") "empty" else ""
          emit(src"""${mem}_${i}.connectStageCtrl(${quote(node)}_done, ${quote(node)}_en, List(${port})) /*$rd $wr $empty*/""")
        }
      }
    }

    withStream(getStream("IOModule")) {
      open(s"""class ArgInBundle() extends Bundle{""")
      emit(s"""val ports = Vec(${argIns.length}, Input(UInt(32.W)))""")
      argIns.zipWithIndex.map { case(p,i) => 
        emit(s"""//  ${quote(p)} = argIns($i) ( ${nameOf(p).getOrElse("")} )""")
      // argInsByName = argInsByName :+ s"${quote(p)}"
      }
      close("}")

      open(s"""class ArgOutBundle() extends Bundle{""")
      emit(s"""val ports = Vec(${argOuts.length}, Output(UInt(32.W)))""")
      argOuts.zipWithIndex.map { case(p,i) => 
        emit(s"""//  ${quote(p)} = argOuts($i) ( ${nameOf(p).getOrElse("")} )""")
      // argOutsByName = argOutsByName :+ s"${quote(p)}"
      }
      close("}")
    }

    super.emitFileFooter()
  }
}
