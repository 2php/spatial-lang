package spatial.lang

import argon.core._
import forge._

/** Addressable, potentially multi-dimensional hardware memories **/
trait Mem[T,C[_]] {
  @api def load(mem: C[T], is: Seq[Index], en: Bit): T
  @api def store(mem: C[T], is: Seq[Index], v: T, en: Bit): MUnit
  @api def iterators(mem: C[T]): Seq[Counter]

  def par(mem: C[T]): Option[Index]
}
