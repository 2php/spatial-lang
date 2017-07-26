package fringe

import chisel3._
import chisel3.util._
import axi4._

class MAGToAXI4Bridge(val addrWidth: Int, val dataWidth: Int, val tagWidth: Int) extends Module {
  val idBits = 5
  val p = new AXI4BundleParameters(addrWidth, dataWidth, idBits)

  Predef.assert(dataWidth == 512, s"ERROR: Unsupported data width $dataWidth in MAGToAXI4Bridge")

  val io = IO(new Bundle {
    val in = Flipped(new DRAMStream(32, 16))  // hardcoding stuff here
    val M_AXI = new AXI4Inlined(p)
  })

  val size = io.in.cmd.bits.size
  // AR
//  io.M_AXI.ARID     := 0.U
  io.M_AXI.ARID     := io.in.cmd.bits.streamId
  io.M_AXI.ARUSER   := io.in.cmd.bits.streamId
  io.M_AXI.ARADDR   := io.in.cmd.bits.addr
  io.M_AXI.ARLEN    := size - 1.U
  io.M_AXI.ARSIZE   := 6.U  // 110, for 64-byte burst size
  io.M_AXI.ARBURST  := 1.U  // INCR mode
  io.M_AXI.ARLOCK   := 0.U
  io.M_AXI.ARCACHE  := 3.U  // Xilinx recommended value
  io.M_AXI.ARPROT   := 0.U  // Xilinx recommended value
  io.M_AXI.ARQOS    := 0.U
  io.M_AXI.ARVALID  := io.in.cmd.valid & ~io.in.cmd.bits.isWr
  io.in.cmd.ready   := Mux(io.in.cmd.bits.isWr, io.M_AXI.AWREADY, io.M_AXI.ARREADY)

  // AW
//  io.M_AXI.AWID     := 0.U
  io.M_AXI.AWID     := io.in.cmd.bits.streamId
  io.M_AXI.AWUSER   := io.in.cmd.bits.streamId
  io.M_AXI.AWADDR   := io.in.cmd.bits.addr
  io.M_AXI.AWLEN    := size - 1.U
  io.M_AXI.AWSIZE   := 6.U  // 110, for 64-byte burst size
  io.M_AXI.AWBURST  := 1.U  // INCR mode
  io.M_AXI.AWLOCK   := 0.U
  io.M_AXI.AWCACHE  := 3.U  // Xilinx recommended value
  io.M_AXI.AWPROT   := 0.U  // Xilinx recommended value
  io.M_AXI.AWQOS    := 0.U
  io.M_AXI.AWVALID  := io.in.cmd.valid & io.in.cmd.bits.isWr

  // W
  io.M_AXI.WDATA    := io.in.wdata.bits.wdata.reverse.reduce{ Cat(_,_) }
  io.M_AXI.WSTRB    := Fill(64, 1.U)
  io.M_AXI.WLAST    := io.in.wdata.bits.wlast
  io.M_AXI.WVALID   := io.in.wdata.valid
  io.in.wdata.ready := io.M_AXI.WREADY

  // R
//  io.M_AXI.RID  // Not using this
  val rdataAsVec = Vec(List.tabulate(16) { i =>
      io.M_AXI.RDATA(512 - 1 - i*32, 512 - 1 - i*32 - 31)
  }.reverse)
  io.in.rresp.bits.rdata := rdataAsVec

//  io.M_AXI.RRESP
//  io.M_AXI.RLAST
////  io.M_AXI.RUSER
  io.M_AXI.RREADY := io.in.rresp.ready

  // B
  io.M_AXI.BREADY := io.in.wresp.ready

  // MAG Response channel is currently shared between reads and writes
  io.in.rresp.valid := io.M_AXI.RVALID
  io.in.wresp.valid := io.M_AXI.BVALID

  // Construct the response tag, with streamId in the MSB
  io.in.rresp.bits.streamId := io.M_AXI.RID
  io.in.wresp.bits.streamId := io.M_AXI.BID

  io.in.rresp.bits.tag := Cat(io.M_AXI.RID(tagWidth-1, 0), Fill(io.in.rresp.bits.tag.getWidth - tagWidth,  0.U))
  io.in.wresp.bits.tag := Cat(io.M_AXI.BID(tagWidth-1, 0), Fill(io.in.wresp.bits.tag.getWidth - tagWidth,  0.U))
}
