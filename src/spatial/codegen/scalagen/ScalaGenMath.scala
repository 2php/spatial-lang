package spatial.codegen.scalagen

import argon.codegen.scalagen.ScalaCodegen
import spatial.spec.MathExp

trait ScalaGenMath extends ScalaCodegen {
  val IR: MathExp
  import IR._

  override protected def emitNode(lhs: Sym[_], rhs: Op[_]): Unit = rhs match {
    case FixAbs(x)  => emit(src"val $lhs = if ($x < 0) -$x else $x")

    case FltAbs(x)  => emit(src"val $lhs = if ($x < 0) -$x else $x")
    case FltLog(x)  => x.tp match {
      case DoubleType() => emit(src"val $lhs = Math.log($x)")
      case FloatType()  => emit(src"val $lhs = Math.log($x.toDouble).toFloat")
    }
    case FltExp(x)  => x.tp match {
      case DoubleType() => emit(src"val $lhs = Math.exp($x)")
      case FloatType()  => emit(src"val $lhs = Math.exp($x.toDouble).toFloat")
    }
    case FltSqrt(x) => x.tp match {
      case DoubleType() => emit(src"val $lhs = Math.sqrt($x)")
      case FloatType()  => emit(src"val $lhs = Math.sqrt($x.toDouble).toFloat")
    }

    case Mux(sel, a, b) => emit(src"val $lhs = if ($sel) $a else $b")

    // Assumes < and > are defined on runtime type...
    case Min(a, b) => emit(src"val $lhs = if ($a < $b) $a else $b")
    case Max(a, b) => emit(src"val $lhs = if ($a > $b) $a else $b")

    case _ => super.emitNode(lhs, rhs)
  }

}