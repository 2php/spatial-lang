package spatial.codegen.chiselgen

import argon.core._
import argon.nodes._
import spatial.banking._
import spatial.metadata._
import spatial.nodes._
import spatial.utils._


trait ChiselGenRegFile extends ChiselGenSRAM {
  private var nbufs: List[Sym[_]]  = List()

  override protected def name(s: Dyn[_]): String = s match {
    case Def(_: RegFileNew[_,_]) => s"""${s}_${s.name.getOrElse("regfile")}"""
    case Def(_: LUTNew[_,_])     => s"""${s}_${s.name.getOrElse("lut")}"""
    case _ => super.name(s)
  } 

  override protected def remap(tp: Type[_]): String = tp match {
    case tp: RegFileType[_] => src"Array[${tp.child}]"
    case _ => super.remap(tp)
  }

  def fracBits(x: Type[_]): Int = x match {
    case FixPtType(_,_,f) => f
    case _ => 0
  }

  override protected def emitNode(lhs: Sym[_], rhs: Op[_]): Unit = rhs match {
    case op@RegFileNew(dims, inits) =>
      val initVals = if (inits.isDefined) {
        getConstValues(inits.get).toList.map{a => src"${a}d"}.mkString(",")
      } else { "None"}

      val initString = if (inits.isDefined) src"Some(List(${initVals}))" else "None"
      val f = lhs.tp.typeArguments.head match {
        case a: FixPtType[_,_,_] => a.fracBits
        case _ => 0
      }
      val width = bitWidth(lhs.tp.typeArguments.head)
      val mem = instanceOf(lhs)
      val writerInfo = writersOf(lhs).zipWithIndex.map { case (w, ii) =>
        val port = portsOf(w, lhs, 0).head
        w.node match {
          case Def(_: RegFileStore[_]) => (port, 1, 1)
          case Def(_: RegFileShiftIn[_]) => (port, 1, 1)
          case Def(op: RegFileVectorShiftIn[_]) => (port, op.addr.length, op.len) // Get stride
          case Def(op: BankedRegFileStore[_]) => (port, op.ens.length, 1)
        }
      }

      if (writerInfo.isEmpty) {warn(s"RegFile $lhs has no writers!")}
      val parInfo = writerInfo.groupBy(_._1).map{case (k,v) => src"($k -> ${v.map{_._2}.sum})"}
      val stride = if (writerInfo.isEmpty) 1 else writerInfo.map(_._3).max
      val depth = mem.depth
      if (depth == 1) {
        emitGlobalModule(src"""val $lhs = Module(new templates.ShiftRegFile(List(${getConstValues(dims)}), $initString, $stride, ${if (writerInfo.isEmpty) 1 else writerInfo.map{_._2}.sum}, false, $width, $f))""")
        emitGlobalModule(src"$lhs.io.dump_en := false.B")
      } else {
        appPropertyStats += HasNBufRegFile
        nbufs = nbufs :+ lhs
        emitGlobalModule(src"""val $lhs = Module(new NBufShiftRegFile(List(${getConstValues(dims)}), $initString, $stride, $depth, Map(${parInfo.mkString(",")}), $width, $f))""")
      }
      resettersOf(lhs).indices.foreach{ ii => emitGlobalWire(src"""val ${lhs}_manual_reset_$ii = Wire(Bool())""")}
      if (resettersOf(lhs).nonEmpty) {
        emitGlobalModule(src"""val ${lhs}_manual_reset = ${resettersOf(lhs).indices.map{ii => src"${lhs}_manual_reset_$ii"}.mkString(" | ")}""")
        emitGlobalModule(src"""$lhs.io.reset := ${lhs}_manual_reset | reset.toBool""")
      } else {emitGlobalModule(src"$lhs.io.reset := reset.toBool")}

    case RegFileReset(rf,en) => 
      val parent = parentOf(lhs).get
      val id = resettersOf(rf).map{_._1}.indexOf(lhs)
      duplicatesOf(rf).indices.foreach{i => emit(src"${rf}_${i}_manual_reset_$id := $en & ${DL(swap(parent, DatapathEn), enableRetimeMatch(en, lhs), true)} ")}
      
    // case op@RegFileLoad(rf,inds,en) =>
      // val dispatch = dispatchOf(lhs, rf).toList.head
      // val port = portsOf(lhs, rf, dispatch).toList.head
      // val addr = inds.map{i => src"${i}.r"}.mkString(",")
      // emitGlobalWireMap(src"""${lhs}""",src"""Wire(${newWire(lhs.tp)})""")
      // emit(src"""${lhs}.r := ${rf}_${dispatch}.readValue(List($addr), $port)""")

    // case op@RegFileStore(rf,inds,data,en) =>
    //   val width = bitWidth(rf.tp.typeArguments.head)
    //   val parent = writersOf(rf).find{_.node == lhs}.get.ctrlNode
    //   val enable = src"""${swap(parent, DatapathEn)} & ~${swap(parent, Inhibitor)} & ${swap(parent, IIDone)}"""
    //   emit(s"""// Assemble multidimW vector""")
    //   emit(src"""val ${lhs}_wVec = Wire(Vec(1, new multidimRegW(${inds.length}, List(${constDimsOf(rf)}), ${width}))) """)
    //   emit(src"""${lhs}_wVec(0).data := ${data}.r""")
    //   emit(src"""${lhs}_wVec(0).en := ${en} & ${DL(enable, enableRetimeMatch(en, lhs), true)}""")
    //   inds.zipWithIndex.foreach{ case(ind,j) => 
    //     emit(src"""${lhs}_wVec(0).addr($j) := ${ind}.r // Assume always an int""")
    //   }
    //   emit(src"""${lhs}_wVec(0).shiftEn := false.B""")
    //   duplicatesOf(rf).zipWithIndex.foreach{ case (mem, i) =>
    //     emit(src"""${rf}_$i.connectWPort(${lhs}_wVec, List(${portsOf(lhs, rf, i)})) """)
    //   }

    case _:RegFileLoad[_] => throw new Exception(s"Cannot generate unbanked RegFile load.\n${str(lhs)}")
    case _:RegFileStore[_] => throw new Exception(s"Cannot generate unbanked RegFile store.\n${str(lhs)}")

    case BankedRegFileLoad(rf, bank, ofs, ens) => //FIXME: Not correct for more than par=1
      val port = portsOf(lhs, rf).toList.head._2
      val addr = ofs.map{i => src"${i}.r"}.mkString(",")
      emitGlobalWireMap(src"""${lhs}""",src"""Wire(${newWire(lhs.tp)})""")
      ofs.zipWithIndex.foreach{case (o,i) => 
        emit(src"""${lhs}($i).r := ${rf}.readValue(${o}.r, $port)""")
      }

    // TODO
    case BankedRegFileStore(rf, data, bank, ofs, ens) => //FIXME: Not correct for more than par=1
      val width = bitWidth(rf.tp.typeArguments.head)
      val parent = writersOf(rf).find{_.node == lhs}.get.ctrlNode
      val enable = src"""${swap(parent, DatapathEn)} & ~${swap(parent, Inhibitor)} & ${swap(parent, IIDone)}"""
      emit(s"""// Assemble multidimW vector""")
      emit(src"""val ${lhs}_wVec = Wire(Vec(1, new multidimRegW(${ofs.length}, List(${constDimsOf(rf)}), ${width}))) """)
      emit(src"""${lhs}_wVec(0).data := ${data}.r""")
      emit(src"""${lhs}_wVec(0).en := ${ens.toList.mkString("List(", ",",")")}.reduce{_&&_} & ${DL(enable, enableRetimeMatch(ens.head, lhs), true)}""")
      ofs.zipWithIndex.foreach{ case(ind,j) => 
        emit(src"""${lhs}_wVec(0).addr($j) := ${ind}.r // Assume always an int""")
      }
      emit(src"""${lhs}_wVec(0).shiftEn := false.B""")
      emit(src"""${rf}.connectWPort(${lhs}_wVec, List(${portsOf(lhs, rf).toList})) """)

    case op@RegFileShiftIn(rf,data,addr,en,axis) =>
      val width = bitWidth(op.mT)
      val parent = ctrlOf(lhs).node
      val enable = src"""${swap(parent, DatapathEn)} & ~${swap(parent, Inhibitor)}"""
      emit(s"""// Assemble multidimW vector""")
      emit(src"""val ${lhs}_wVec = Wire(Vec(1, new multidimRegW(${addr.length}, List(${constDimsOf(rf)}), ${width}))) """)
      emit(src"""${lhs}_wVec(0).data := ${data}.r""")
      emit(src"""${lhs}_wVec(0).shiftEn := ${en} & ${DL(enable, enableRetimeMatch(en, lhs), true)}""")
      addr.zipWithIndex.foreach{ case(ind,j) => 
        emit(src"""${lhs}_wVec(0).addr($j) := ${ind}.r // Assume always an int""")
      }
      emit(src"""${lhs}_wVec(0).en := false.B""")
      emit(src"""$rf.connectShiftPort(${lhs}_wVec, List(${portsOf(lhs, rf, 0)})) """)

    case op@RegFileVectorShiftIn(rf,data,addr,en,axis,len) =>
      val width = bitWidth(op.mT)
      val parent = ctrlOf(lhs).node
      val enable = src"""${swap(parent, DatapathEn)} & ~${swap(parent, Inhibitor)}"""
      emit(s"""// Assemble multidimW vectors""")
      emit(src"""val ${lhs}_wVec = Wire(Vec(${addr.length}, new multidimRegW(${addr.length}, List(${constDimsOf(rf)}), ${width}))) """)
      open(src"""for (${lhs}_i <- 0 until ${data}.length) {""")
        emit(src"""${lhs}_wVec(${lhs}_i).data := ${data}(${lhs}_i).r""")
        emit(src"""${lhs}_wVec(${lhs}_i).shiftEn := ${en} & ${DL(enable, enableRetimeMatch(en, lhs), true)}""")
        // inds.zipWithIndex.foreach{ case(ind,j) => 
        //   emit(src"""${lhs}_wVec(${lhs}_i).addr($j) := ${ind}.r // Assume always an int""")
        // }
        emit(src"""${lhs}_wVec(${lhs}_i).en := false.B""")
      close(src"}")
      emit(src"""$rf.connectShiftPort(${lhs}_wVec, List(${portsOf(lhs, rf)})) """)

    case op@LUTNew(dims, init) =>
      appPropertyStats += HasLUT
      val width = bitWidth(lhs.tp.typeArguments.head)
      val f = lhs.tp.typeArguments.head match {
        case a: FixPtType[_,_,_] => a.fracBits
        case _ => 0
      }
      val lut_consts = if (width == 1) {
        getConstValues(init).toList.map{a => if (a == true) "1.0" else "0.0"}.mkString(",")
      } else {
        getConstValues(init).toList.map{a => src"${a}d"}.mkString(",")
      }

      val numReaders = readersOf(lhs).length
      emitGlobalModule(src"""val $lhs = Module(new LUT(List($dims), List($lut_consts), $numReaders, $width, $f))""")
      
    case op@BankedLUTLoad(lut,bank,ofs,ens) => 
      val ensquote = ens.map(quote(_)).mkString("List(", ",", ")")
      val idquote = src"${lhs}_id"
      emitGlobalWireMap(src"""$lhs""",src"""Wire(${newWire(lhs.tp)})""")
      val parent = parentOf(lhs).get
      ens.zipWithIndex.foreach{case (en, i) => 
        emit(src"""val ${idquote}_$i = ${lut}.connectRPort(List(${bank(i)}.r, ${ofs(i)}.r), $en & ${DL(swap(parent, DatapathEn), enableRetimeMatch(ens.head, lhs), true)})""")
        emit(src"""${lhs}($i).r := ${lut}.io.data_out(${idquote}_$i)""")
      }


    case LUTLoad(lut,addr,en) =>
      // val idquote = src"${lhs}_id"
      // emitGlobalWireMap(src"""$lhs""",src"""Wire(${newWire(lhs.tp)})""")
      // val parent = parentOf(lhs).get
      // emit(src"""val ${idquote} = ${lut}_${dispatch}.connectRPort(List(${inds.map{a => src"${a}.r"}}), $en & ${DL(swap(parent, DatapathEn), enableRetimeMatch(en, lhs), true)})""")
      // emit(src"""${lhs}.raw := ${lut}_${dispatch}.io.data_out(${idquote}).raw""")

    case VarRegNew(init)       =>
    case VarRegRead(reg)       => 
    case VarRegWrite(reg,v,en) => 
    case Print(x)   => 
    case Println(x) => 
    case PrintIf(_,_) =>
    case PrintlnIf(_,_) =>

    case _ => super.emitNode(lhs, rhs)
  }


  override protected def emitFileFooter() {
    withStream(getStream("BufferControlCxns")) {
      nbufs.foreach{mem =>
        val info = bufferControlInfo(mem)
        info.zipWithIndex.foreach{ case (inf, port) => 
          emit(src"""${mem}.connectStageCtrl(${DL(swap(quote(inf._1), Done), 1, true)}, ${swap(quote(inf._1), BaseEn)}, List(${port})) ${inf._2}""")
        }
      }
    }

    super.emitFileFooter()
  }

}
