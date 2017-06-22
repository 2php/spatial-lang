package spatial.lang.static

import argon.core._
import forge._
import spatial.lang.Math

trait MathApi { this: SpatialApi =>

  @api def mux[T:Type:Bits](select: Bit, a: T, b: T): T = Math.mux(select, a, b)

  @api def abs[S:BOOL,I:INT,F:INT](x: FixPt[S,I,F]) = Math.abs(x)

  @api def abs[G:INT,E:INT](x: FltPt[G,E]) = Math.abs(x)
  @api def log[G:INT,E:INT](x: FltPt[G,E]) = Math.log(x)
  @api def exp[G:INT,E:INT](x: FltPt[G,E]) = Math.exp(x)
  /** Natural exponential computed with Taylor Expansion **/
  // @api def exp_taylor[S:BOOL,I:INT,F:INT](x: FixPt[S,I,F], center: Int, degree: Int) = Math.exp_taylor(x, center, degree)

  /** Taylor expansion for sin and cos from -pi to pi **/
  @api def sin_taylor[S:BOOL,I:INT,F:INT](x: FixPt[S,I,F]): FixPt[S,I,F] = {
    x - x*x*x/6 + x*x*x*x*x/120 //- x*x*x*x*x*x*x/5040
  }
  @api def cos_taylor[S:BOOL,I:INT,F:INT](x: FixPt[S,I,F]): FixPt[S,I,F] = {
    1 - x*x/2 + x*x*x*x/24 //- x*x*x*x*x*x/720
  }
  /** Taylor expansion for natural exponential to third degree **/
  @api def exp_taylor[S:BOOL,I:INT,F:INT](x: FixPt[S,I,F]): FixPt[S,I,F] = {
    mux(x < -3.5.to[FixPt[S,I,F]], 0.to[FixPt[S,I,F]], mux(x < -1.2.to[FixPt[S,I,F]], x*0.1.to[FixPt[S,I,F]] + 0.35.to[FixPt[S,I,F]], 1 + x + x*x/2 + x*x*x/6 + x*x*x*x/24 + x*x*x*x*x/120))
  }
  /** Square root **/
  @api def sqrt[G:INT,E:INT](x: FltPt[G,E]): FltPt[G,E] = wrap(Math.flt_sqrt(x.s))
  @api def sqrt_approx[S:BOOL,I:INT,F:INT](x: FixPt[S,I,F]): FixPt[S,I,F] = {
    // I don't care how inefficient this is, it is just a placeholder for backprop until we implement floats
    mux(x < 2.to[FixPt[S,I,F]], 1 + (x-1)/2 -(x-1)*(x-1)/8+(x-1)*(x-1)*(x-1)/16, // 3rd order taylor for values up to 2
      mux(x < 10.to[FixPt[S,I,F]], x*0.22.to[FixPt[S,I,F]] + 1, // Linearize
        mux( x < 100.to[FixPt[S,I,F]], x*0.08.to[FixPt[S,I,F]] + 2.5.to[FixPt[S,I,F]], // Linearize
          mux( x < 1000.to[FixPt[S,I,F]], x*0.028.to[FixPt[S,I,F]] + 8, // Linearize
            mux( x < 10000.to[FixPt[S,I,F]], x*0.008.to[FixPt[S,I,F]] + 20, // Linearize
              mux( x < 100000.to[FixPt[S,I,F]], x*0.003.to[FixPt[S,I,F]] + 60, x*0.0002.to[FixPt[S,I,F]] + 300))))))
  }

  @api def floor[S:BOOL,I:INT,F:INT](x: FixPt[S,I,F]) = Math.floor(x)
  @api def ceil[S:BOOL,I:INT,F:INT](x: FixPt[S,I,F]) = Math.ceil(x)

  // TODO: These should probably be added to Num instead
  @api def abs[T:Type:Num](x: T) = Math.abs(x)

  @api def exp[T:Type:Num](x: T) = Math.exp(x)

  @api def min[T:Type:Bits:Order](a: T, b: T): T = Math.min(a, b)
  @api def max[T:Type:Bits:Order](a: T, b: T): T = Math.max(a, b)

  /** Trigonometric functions **/
  @api def sin[G:INT,E:INT](x: FltPt[G,E]): FltPt[G,E] = Math.sin(x)
  @api def cos[G:INT,E:INT](x: FltPt[G,E]): FltPt[G,E] = Math.cos(x)
  @api def tan[G:INT,E:INT](x: FltPt[G,E]): FltPt[G,E] = Math.tan(x)
  @api def sinh[G:INT,E:INT](x: FltPt[G,E]): FltPt[G,E] = Math.sinh(x)
  @api def cosh[G:INT,E:INT](x: FltPt[G,E]): FltPt[G,E] = Math.cosh(x)
  @api def tanh[G:INT,E:INT](x: FltPt[G,E]): FltPt[G,E] = Math.tanh(x)
  @api def asin[G:INT,E:INT](x: FltPt[G,E]): FltPt[G,E] = Math.asin(x)
  @api def acos[G:INT,E:INT](x: FltPt[G,E]): FltPt[G,E] = Math.acos(x)
  @api def atan[G:INT,E:INT](x: FltPt[G,E]): FltPt[G,E] = Math.atan(x)
  val PI = Math.PI

  @api def pow[G:INT,E:INT](base: FltPt[G,E], exp:FltPt[G,E]): FltPt[G,E] = Math.pow(base, exp)
  @api def pow[T:Type:Num](base: T, exp: scala.Int): T = Math.pow(base, exp)

  implicit class MathInfixOps[T:Type:Num](x: T) {
    @api def **(exp: scala.Int): T = pow(x, exp)
  }
}
