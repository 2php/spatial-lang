package spatial.codegen.pirgen

import argon.core._
import spatial.nodes._
import spatial.utils._
import spatial.metadata._

trait PIRGenAccess extends PIRCodegen with PIRGenMem {

  def emitDependency(sym:LhsSym, rhs:Op[_]):Unit = {
    val lhs = compose(sym.dlhs)
    if (depsOf(lhs).nonEmpty) {// anti dependency
      val deps = depsOf(lhs).filter { e => isAccess(e) }.toList
      if (deps.nonEmpty) emit(s"antiDepsOf($sym)=$deps")
    }
  }

  override protected def emitNode(lhs: Sym[_], rhs: Op[_]): Unit = {
    rhs match {
      // SRAMs, RegFile, LUT
      case ParLocalReader((mem, Some(addrs::_), _)::_) =>
        val instId::Nil = getDispatches(mem, lhs)
        decompose(lhs).zip(decompose(mem)).foreach { case (dlhs, dmem) =>
          val banks = staticBanksOf((lhs, instId)).map { bankId => LhsMem(dmem, instId, bankId) }
          emit(dlhs, s"LoadBanks($banks, ${quote(addrs)})", rhs)
          emitDependency(dlhs, rhs)
        }
      case ParLocalWriter((mem, Some(value::_), Some(addrs::_), _)::_) =>
        val instIds = getDispatches(mem, lhs).toList
        decompose(lhs).zip(decompose(mem)).zip(decompose(value)).foreach { case ((dlhs, dmem), dvalue) =>
          val mems = instIds.map { instId =>
            staticBanksOf((lhs, instId)).map { bankId => LhsMem(dmem, instId, bankId) }.toList
          }
          emit(dlhs, s"StoreBanks($mems, ${quote(addrs)}, ${quote(dvalue)})", rhs)
          emitDependency(dlhs, rhs)
        }

      // Reg, FIFO, Stream
      case ParLocalReader((mem, None, _)::_) =>
        val instId::Nil = getDispatches(mem, lhs)
        decompose(lhs).zip(decompose(mem)).foreach { case (dlhs, dmem) =>
          val mem = LhsMem(dmem, instId)
          emit(dlhs, s"ReadMem($mem)", rhs)
          emitDependency(dlhs, rhs)
        }
      case ParLocalWriter((mem, Some(value::_), None, _)::_) =>
        val instIds = getDispatches(mem, lhs)
        decompose(lhs).zip(decompose(mem)).zip(decompose(value)).foreach { case ((dlhs, dmem), dvalue) =>
          instIds.foreach { instId =>
            val sym = LhsSym(dlhs, Some(s"${LhsMem(dmem, instId)}"))
            emit(sym, s"WriteMem(${LhsMem(dmem, instId)}, ${quote(dvalue)})", rhs)
            emitDependency(sym, rhs)
          }
        }

      case FIFOPeek(mem) => 
        decompose(lhs).zip(decompose(mem)).foreach { case (dlhs, dmem) =>
          emit(dlhs, s"FIFOPeek(${LhsMem(dmem, 0)})", rhs)
          emitDependency(dlhs, rhs)
        }
      case FIFOEmpty(mem) =>
        decompose(lhs).zip(decompose(mem)).foreach { case (dlhs, dmem) =>
          emit(dlhs, s"FIFOEmpty(${LhsMem(dmem, 0)})", rhs)
          emitDependency(dlhs, rhs)
        }
      case FIFOFull(mem) => 
        decompose(lhs).zip(decompose(mem)).foreach { case (dlhs, dmem) =>
          emit(dlhs, s"FIFOFull(${LhsMem(dmem, 0)})", rhs)
          emitDependency(dlhs, rhs)
        }
      //case FIFOAlmostEmpty(mem) =>
        //decompose(lhs).zip(decompose(mem)).foreach { case (dlhs, dmem) =>
          //emit(dlhs, s"FIFOAlmostEmpty(${LhsMem(dmem)})", rhs)
        //}
      //case FIFOAlmostFull(mem) => 
        //decompose(lhs).zip(decompose(mem)).foreach { case (dlhs, dmem) =>
          //emit(dlhs, s"FIFOAlmostFull(${LhsMem(dmem)})", rhs)
        //}
      case FIFONumel(mem) => 
        emitDependency(lhs, rhs)
        decompose(lhs).zip(decompose(mem)).foreach { case (dlhs, dmem) =>
          emit(dlhs, s"FIFONumel(${LhsMem(dmem, 0)})", rhs)
          emitDependency(dlhs, rhs)
        }
      case _ => super.emitNode(lhs, rhs)
    }
  }

}

