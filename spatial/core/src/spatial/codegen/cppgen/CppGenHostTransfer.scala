package spatial.codegen.cppgen

import argon.nodes._
import spatial.compiler._
import spatial.metadata._
import spatial.nodes._
import spatial.utils._
import spatial.SpatialConfig

trait CppGenHostTransfer extends CppGenSRAM  {

  override protected def spatialNeedsFPType(tp: Type[_]): Boolean = tp match { // FIXME: Why doesn't overriding needsFPType work here?!?!
      case FixPtType(s,d,f) => if (s) true else if (f == 0) false else true
      case IntType()  => false
      case LongType() => false
      case FloatType() => true
      case DoubleType() => true
      case _ => super.needsFPType(tp)
  }

  override def quote(s: Exp[_]): String = {
  	if (SpatialConfig.enableNaming) {
	    s match {
	      case lhs: Sym[_] =>
	        lhs match {
	          case Def(SetArg(reg:Sym[_],_)) => s"x${lhs.id}_set${reg.id}"
	          case Def(GetArg(reg:Sym[_])) => s"x${lhs.id}_get${reg.id}"
	          case Def(SetMem(_,_)) => s"x${lhs.id}_setMem"
	          case Def(GetMem(_,_)) => s"x${lhs.id}_getMem"
	          case _ => super.quote(s)
	        }
	      case _ =>
	        super.quote(s)
	    }
    } else {
    	super.quote(s)
    }
  } 

  override protected def emitNode(lhs: Sym[_], rhs: Op[_]): Unit = rhs match {
    case SetArg(reg, v) => 
      reg.tp.typeArguments.head match {
        case FixPtType(s,d,f) => if (f != 0) {
            emit(src"c1->setArg(${argMapping(reg)._2}, $v * ((int64_t)1 << $f), ${isHostIO(reg)}); // $lhs")
            emit(src"$reg = $v;")
          } else {
            emit(src"c1->setArg(${argMapping(reg)._2}, $v, ${isHostIO(reg)}); // $lhs")
            emit(src"$reg = $v;")
          }
        case _ => 
            emit(src"c1->setArg(${argMapping(reg)._2}, $v, ${isHostIO(reg)}); // $lhs")
            emit(src"$reg = $v;")

      }
    case GetArg(reg)    => 
      reg.tp.typeArguments.head match {
        case FixPtType(s,d,f) => 
          emit(src"int64_t ${lhs}_tmp = c1->getArg(${argMapping(reg)._3}, ${isHostIO(reg)});")            
          emit(src"bool ${lhs}_sgned = $s & ((${lhs}_tmp & ((int64_t)1 << ${d+f-1})) > 0); // Determine sign")
          emit(src"if (${lhs}_sgned) ${lhs}_tmp = ${lhs}_tmp | ~(((int64_t)1 << ${d+f})-1); // Sign-extend if necessary")
          emit(src"${lhs.tp} ${lhs} = (${lhs.tp}) ${lhs}_tmp / ((int64_t)1 << $f);")            
        case _ => 
          emit(src"${lhs.tp} $lhs = (${lhs.tp}) c1->getArg(${argMapping(reg)._3}, ${isHostIO(reg)});")
        }
    case SetMem(dram, data) => 
      val rawtp = remapIntType(dram.tp.typeArguments.head)
      if (spatialNeedsFPType(dram.tp.typeArguments.head)) {
        dram.tp.typeArguments.head match { 
          case FixPtType(s,d,f) => 
            emit(src"vector<${rawtp}>* ${dram}_rawified = new vector<${rawtp}>((*${data}).size());")
            open(src"for (int ${dram}_rawified_i = 0; ${dram}_rawified_i < (*${data}).size(); ${dram}_rawified_i++) {")
              emit(src"(*${dram}_rawified)[${dram}_rawified_i] = (${rawtp}) ((*${data})[${dram}_rawified_i] * ((${rawtp})1 << $f));")
            close("}")
            emit(src"c1->memcpy($dram, &(*${dram}_rawified)[0], (*${dram}_rawified).size() * sizeof(${rawtp}));")
          case _ => emit(src"c1->memcpy($dram, &(*${data})[0], (*${data}).size() * sizeof(${rawtp}));")
        }
      } else {
        emit(src"c1->memcpy($dram, &(*${data})[0], (*${data}).size() * sizeof(${rawtp}));")
      }
    case GetMem(dram, data) => 
      val rawtp = remapIntType(dram.tp.typeArguments.head)
      if (spatialNeedsFPType(dram.tp.typeArguments.head)) {
        dram.tp.typeArguments.head match { 
          case FixPtType(s,d,f) => 
            emit(src"vector<${rawtp}>* ${data}_rawified = new vector<${rawtp}>((*${data}).size());")
            emit(src"c1->memcpy(&(*${data}_rawified)[0], $dram, (*${data}_rawified).size() * sizeof(${rawtp}));")
            open(src"for (int ${data}_i = 0; ${data}_i < (*${data}).size(); ${data}_i++) {")
              emit(src"${rawtp} ${data}_tmp = (*${data}_rawified)[${data}_i];")
              emit(src"(*${data})[${data}_i] = (double) ${data}_tmp / ((${rawtp})1 << $f);")
            close("}")
          case _ => emit(src"c1->memcpy(&(*$data)[0], $dram, (*${data}).size() * sizeof(${rawtp}));")
        }
      } else {
        emit(src"c1->memcpy(&(*$data)[0], $dram, (*${data}).size() * sizeof(${rawtp}));")
      }
    case _ => super.emitNode(lhs, rhs)
  }



}
