package fringe.fringeZynq.bigIP
import fringe.FringeGlobals
import fringe.bigIP.BigIP
import chisel3._
import chisel3.util._
import scala.collection.mutable.Set

class BigIPZynq extends BigIP with ZynqBlackBoxes {
  def divide(dividend: UInt, divisor: UInt, latency: Int): UInt = {
    val m = Module(new Divider(dividend.getWidth, divisor.getWidth, false, latency))
    m.io.dividend := dividend
    m.io.divisor := divisor
    m.io.out
  }

  def divide(dividend: SInt, divisor: SInt, latency: Int): SInt = {
    val m = Module(new Divider(dividend.getWidth, divisor.getWidth, true, latency))
    m.io.dividend := dividend.asUInt
    m.io.divisor := divisor.asUInt
    m.io.out.asSInt
  }

}


