package spatial.codegen.chiselgen

import argon.core._
import spatial.aliases._
import spatial.metadata._
import spatial.nodes._
import spatial.utils._

//import scala.collection.mutable.HashMap

trait ChiselGenDRAM extends ChiselGenSRAM with ChiselGenStructs {
  var loadsList = List[Exp[_]]()
  var storesList = List[Exp[_]]()
  var loadParMapping = List[String]()
  var storeParMapping = List[String]()
  var dramsList = List[Exp[_]]()
  // var loadParMapping = HashMap[Int, (Int,Int)]() 
  // var storeParMapping = HashMap[Int, (Int,Int)]() 

  override protected def name(s: Dyn[_]): String = s match {
    case Def(_: DRAMNew[_,_]) => s"${s}_dram"
    case _ => super.name(s)
  }

  override protected def remap(tp: Type[_]): String = tp match {
    case tp: DRAMType[_] => src"Array[${tp.child}]"
    case _ => super.remap(tp)
  }

  protected def getLastChild(lhs: Exp[_]): Exp[_] = {
    var nextLevel = childrenOf(lhs)
    if (nextLevel.isEmpty) {
      lhs
    } else {
      getLastChild(nextLevel.last)
    }
  }

  override protected def emitNode(lhs: Sym[_], rhs: Op[_]): Unit = rhs match {
    case op@DRAMNew(dims,zero) =>
    dramsList = dramsList :+ lhs
      if (argMapping(lhs) == (-1,-1,-1)) {
        throw new spatial.UnusedDRAMException(lhs, lhs.name.getOrElse("noname"))
      }

    case GetDRAMAddress(dram) =>
      val id = argMapping(dram).argInId
      emit(src"""val $lhs = io.argIns($id)""")

    case FringeDenseLoad(dram,cmdStream,dataStream) =>
      // Get parallelization of datastream
      emit(src"// This transfer belongs in channel ${transferChannel(parentOf(lhs).get)}")
      val par = readersOf(dataStream).head.node match {
        case Def(e@ParStreamRead(strm, ens)) => ens.length
        case _ => 1
      }

      val id = loadsList.length
      loadParMapping = loadParMapping :+ s"StreamParInfo(${bitWidth(dram.tp.typeArguments.head)}, ${par}, ${transferChannel(parentOf(lhs).get)})" 
      loadsList = loadsList :+ dram

      // TODO: Investigate this _enq business
      val turnstiling_stage = getLastChild(parentOf(lhs).get)
      emitGlobalWire(src"""val ${turnstiling_stage}_enq = io.memStreams.loads(${id}).rdata.valid""")

      // Connect the streams to their IO interface signals
      emit(src"""${dataStream}.zip(io.memStreams.loads($id).rdata.bits).foreach{case (a,b) => a.r := Utils.getRetimed(b, ${symDelay(readersOf(dataStream).head.node)})}""")
      emit(src"""${swap(dataStream, NowValid)} := io.memStreams.loads($id).rdata.valid""")
      emit(src"""${swap(dataStream, Valid)} := ${swap(dataStream, NowValid)}.D(${symDelay(readersOf(dataStream).head.node)})""")
      emit(src"${swap(cmdStream, Ready)} := io.memStreams.loads($id).cmd.ready.D(${symDelay(writersOf(cmdStream).head.node)})")

      // Connect the IO interface signals to their streams
      val (addrMSB, addrLSB)  = tupCoordinates(cmdStream.tp.typeArguments.head, "offset")
      val (sizeMSB, sizeLSB)  = tupCoordinates(cmdStream.tp.typeArguments.head, "size")
      val (isLdMSB, isLdLSB)  = tupCoordinates(cmdStream.tp.typeArguments.head, "isLoad")
      val bug241_backoff = (math.random*7).toInt
      emit(src"io.memStreams.loads($id).rdata.ready := ${swap(dataStream, Ready)}/* & ~${turnstiling_stage}_inhibitor*/")
      emit(src"io.memStreams.loads($id).cmd.bits.addr := Utils.getRetimed(${cmdStream}($addrMSB,$addrLSB), ${bug241_backoff})")
      emit(src"io.memStreams.loads($id).cmd.bits.size := Utils.getRetimed(${cmdStream}($sizeMSB,$sizeLSB), ${bug241_backoff})")
      emit(src"io.memStreams.loads($id).cmd.valid :=  ${swap(cmdStream, Valid)}.D(${bug241_backoff}, rr)")
      emit(src"io.memStreams.loads($id).cmd.bits.isWr := Utils.getRetimed(~${cmdStream}($isLdMSB,$isLdLSB), ${bug241_backoff})")
      emit(src"io.memStreams.loads($id).cmd.bits.isSparse := 0.U")

    case FringeSparseLoad(dram,addrStream,dataStream) =>
      // Get parallelization of datastream
      emit(src"// This transfer belongs in channel ${transferChannel(parentOf(lhs).get)}")
      val par = readersOf(dataStream).head.node match {
        case Def(e@ParStreamRead(strm, ens)) => ens.length
        case _ => 1
      }
      assert(par == 1, s"Unsupported par '$par' for sparse loads! Must be 1 currently")

      val id = loadsList.length
      loadParMapping = loadParMapping :+ s"StreamParInfo(${bitWidth(dram.tp.typeArguments.head)}, ${par}, ${transferChannel(parentOf(lhs).get)})" 
      loadsList = loadsList :+ dram
      val turnstiling_stage = getLastChild(parentOf(lhs).get)
      emitGlobalWire(src"""val ${turnstiling_stage}_enq = io.memStreams.loads(${id}).rdata.valid""")

      emit(src"""${dataStream}.zip(io.memStreams.loads($id).rdata.bits).foreach{case (a,b) => a.r := Utils.getRetimed(b, ${symDelay(readersOf(dataStream).head.node)})}""")
      emit(src"""${swap(dataStream, NowValid)} := io.memStreams.loads($id).rdata.valid""")
      emit(src"""${swap(dataStream, Valid)} := ${swap(dataStream, NowValid)}.D(${symDelay(readersOf(dataStream).head.node)})""")
      emit(src"${swap(addrStream, Ready)} := io.memStreams.loads($id).cmd.ready.D(${symDelay(writersOf(addrStream).head.node)})")
      emit(src"io.memStreams.loads($id).rdata.ready := ${swap(dataStream, Ready)}")
      emit(src"io.memStreams.loads($id).cmd.bits.addr := ${addrStream}(0).r // TODO: Is sparse addr stream always a vec?")
      emit(src"io.memStreams.loads($id).cmd.bits.size := 1.U")
      emit(src"io.memStreams.loads($id).cmd.valid :=  ${swap(addrStream, Valid)}")
      emit(src"io.memStreams.loads($id).cmd.bits.isWr := false.B")
      emit(src"io.memStreams.loads($id).cmd.bits.isSparse := 1.U")

    case FringeDenseStore(dram,cmdStream,dataStream,ackStream) =>
      // Get parallelization of datastream
      emit(src"// This transfer belongs in channel ${transferChannel(parentOf(lhs).get)}")
      val par = writersOf(dataStream).head.node match {
        case Def(e@ParStreamWrite(_, _, ens)) => ens.length
        case _ => 1
      }

      val id = storesList.length
      storeParMapping = storeParMapping :+ s"StreamParInfo(${bitWidth(dram.tp.typeArguments.head)}, ${par}, ${transferChannel(parentOf(lhs).get)})" 
      storesList = storesList :+ dram

      // Connect streams to their IO interface signals
      emit(src"""${swap(dataStream, Ready)} := io.memStreams.stores($id).wdata.ready""")

      // Connect IO interface signals to their streams
      val (dataMSB, dataLSB) = tupCoordinates(dataStream.tp.typeArguments.head, "_1")
      val (addrMSB, addrLSB)  = tupCoordinates(cmdStream.tp.typeArguments.head, "offset")
      val (sizeMSB, sizeLSB)  = tupCoordinates(cmdStream.tp.typeArguments.head, "size")
      val (isLdMSB, isLdLSB)  = tupCoordinates(cmdStream.tp.typeArguments.head, "isLoad")
      val bug241_backoff = (math.random*7).toInt
      emit(src"""io.memStreams.stores($id).wdata.bits.zip(${dataStream}).foreach{case (wport, wdata) => wport := wdata($dataMSB,$dataLSB) }""")
      emit(src"""io.memStreams.stores($id).wdata.valid := ${swap(dataStream, Valid)}""")
      emit(src"io.memStreams.stores($id).cmd.bits.addr := Utils.getRetimed(${cmdStream}($addrMSB,$addrLSB), ${bug241_backoff})")
      emit(src"io.memStreams.stores($id).cmd.bits.size := Utils.getRetimed(${cmdStream}($sizeMSB,$sizeLSB), ${bug241_backoff})")
      emit(src"io.memStreams.stores($id).cmd.valid :=  ${swap(cmdStream, Valid)}.D(${bug241_backoff}, rr)")
      emit(src"io.memStreams.stores($id).cmd.bits.isWr := Utils.getRetimed(~${cmdStream}($isLdMSB,$isLdLSB), ${bug241_backoff})")
      emit(src"io.memStreams.stores($id).cmd.bits.isSparse := 0.U")
      emit(src"${swap(cmdStream, Ready)} := io.memStreams.stores($id).wdata.ready.D(${symDelay(writersOf(cmdStream).head.node)})")
      emit(src"""${swap(ackStream, NowValid)} := io.memStreams.stores($id).wresp.valid""")
      emit(src"""${swap(ackStream, Valid)} := ${swap(ackStream, NowValid)}.D(${symDelay(readersOf(ackStream).head.node)})""")
      emit(src"""io.memStreams.stores($id).wresp.ready := ${swap(ackStream, Ready)}""")

    case FringeSparseStore(dram,cmdStream,ackStream) =>
      // Get parallelization of datastream
      emit(src"// This transfer belongs in channel ${transferChannel(parentOf(lhs).get)}")
      val par = writersOf(cmdStream).head.node match {
        case Def(e@ParStreamWrite(_, _, ens)) => ens.length
        case _ => 1
      }
      Predef.assert(par == 1, s"Unsupported par '$par', only par=1 currently supported")

      val id = storesList.length
      storeParMapping = storeParMapping :+ s"StreamParInfo(${bitWidth(dram.tp.typeArguments.head)}, ${par}, ${transferChannel(parentOf(lhs).get)})"
      storesList = storesList :+ dram

      val (addrMSB, addrLSB) = tupCoordinates(cmdStream.tp.typeArguments.head, "_2")
      val (dataMSB, dataLSB) = tupCoordinates(cmdStream.tp.typeArguments.head, "_1")
      emit(src"io.memStreams.stores($id).wdata.bits.zip(${cmdStream}).foreach{case (wport, wdata) => wport := wdata($dataMSB, $dataLSB)}")
      emit(src"io.memStreams.stores($id).wdata.valid := ${swap(cmdStream, Valid)}")
      emit(src"io.memStreams.stores($id).cmd.bits.addr := ${cmdStream}(0)($addrMSB, $addrLSB) // TODO: Is this always a vec of size 1?")
      emit(src"io.memStreams.stores($id).cmd.bits.size := 1.U")
      emit(src"io.memStreams.stores($id).cmd.valid :=  ${swap(cmdStream, Valid)}")
      emit(src"io.memStreams.stores($id).cmd.bits.isWr := 1.U")
      emit(src"io.memStreams.stores($id).cmd.bits.isSparse := 1.U")
      emit(src"${swap(cmdStream, Ready)} := io.memStreams.stores($id).wdata.ready.D(${symDelay(writersOf(cmdStream).head.node)})")
      emit(src"""${swap(ackStream, NowValid)} := io.memStreams.stores($id).wresp.valid""")
      emit(src"""${swap(ackStream, Valid)} := ${swap(ackStream, NowValid)}.D(${symDelay(readersOf(ackStream).head.node)})""")
      emit(src"""io.memStreams.stores($id).wresp.ready := ${swap(ackStream, Ready)}""")

    case _ => super.emitNode(lhs, rhs)
  }


  override protected def emitFileFooter() {

    val intersect = loadsList.distinct.intersect(storesList.distinct)

    val num_unusedDrams = dramsList.length - loadsList.distinct.length - storesList.distinct.length + intersect.length

    withStream(getStream("Instantiator")) {
      emit("")
      emit(s"// Memory streams")
      emit(src"""val loadStreamInfo = List($loadParMapping) """)
      emit(src"""val storeStreamInfo = List($storeParMapping) """)
      emit(src"""val numArgIns_mem = ${loadsList.distinct.length} /*from loads*/ + ${storesList.distinct.length} /*from stores*/ - ${intersect.length} /*from bidirectional ${intersect}*/ + ${num_unusedDrams} /* from unused DRAMs */""")
      emit(src"""// $loadsList $storesList)""")
    }

    withStream(getStream("IOModule")) {
      emit("// Memory Streams")
      emit(src"""val io_loadStreamInfo = List($loadParMapping) """)
      emit(src"""val io_storeStreamInfo = List($storeParMapping) """)
      emit(src"val io_numArgIns_mem = ${loadsList.distinct.length} /*from loads*/ + ${storesList.distinct.length} /*from stores*/ - ${intersect.length} /*from bidirectional ${intersect}*/ + ${num_unusedDrams} /* from unused DRAMs */")
 
    }

    super.emitFileFooter()
  }

}
