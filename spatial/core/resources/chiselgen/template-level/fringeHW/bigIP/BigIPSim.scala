package fringe.bigIP
import chisel3._
import chisel3.util._

class BigIPSim extends BigIP {
  def divide(dividend: UInt, divisor: UInt, latency: Int): UInt = {
    getConst(divisor) match { // Use combinational Verilog divider and ignore latency if divisor is constant
      case Some(bigNum) => dividend / bigNum.U
      case None => ShiftRegister(dividend/divisor, latency)
    }
  }

  def divide(dividend: SInt, divisor: SInt, latency: Int): SInt = {
    getConst(divisor) match { // Use combinational Verilog divider and ignore latency if divisor is constant
      case Some(bigNum) => dividend / bigNum.S
      case None => ShiftRegister(dividend/divisor, latency)
    }
  }

  def mod(dividend: UInt, divisor: UInt, latency: Int): UInt = {
    getConst(divisor) match { // Use combinational Verilog divider and ignore latency if divisor is constant
      case Some(bigNum) => dividend % bigNum.U
      case None => ShiftRegister(dividend % divisor, latency)
    }
  }

  def mod(dividend: SInt, divisor: SInt, latency: Int): SInt = {
    getConst(divisor) match { // Use combinational Verilog divider and ignore latency if divisor is constant
      case Some(bigNum) => dividend % bigNum.S
      case None => ShiftRegister(dividend % divisor, latency)
    }
  }

}


