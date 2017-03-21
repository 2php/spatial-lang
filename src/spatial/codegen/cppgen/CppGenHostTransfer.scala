package spatial.codegen.cppgen

import argon.codegen.cppgen.CppCodegen
import spatial.api.HostTransferExp
import spatial.SpatialConfig
import spatial.api.RegExp
import spatial.SpatialExp
import spatial.analysis.SpatialMetadataExp


trait CppGenHostTransfer extends CppCodegen  {
  val IR: SpatialExp
  import IR._


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
            emit(src"c1->setArg(${argMapping(reg)._1}, $v * (2 << $f)); // $lhs", forceful = true)
            emit(src"${reg.tp} $reg = $v;")
          } else {
            emit(src"c1->setArg(${argMapping(reg)._1}, $v); // $lhs", forceful = true)
            emit(src"${reg.tp} $reg = $v;")
          }
        case _ => 
            emit(src"c1->setArg(${argMapping(reg)._1}, $v); // $lhs", forceful = true)
            emit(src"${reg.tp} $reg = $v;")

      }
    case GetArg(reg)    => 
      reg.tp.typeArguments.head match {
        case FixPtType(s,d,f) => if (f != 0) {
            emit(src"${lhs.tp} $lhs = (${lhs.tp}) c1->getArg(${argMapping(reg)._1}) / (2 << $f);", forceful = true)            
          } else {
            emit(src"${lhs.tp} $lhs = (${lhs.tp}) c1->getArg(${argMapping(reg)._1});", forceful = true)
          }
        case _ => 
            emit(src"${lhs.tp} $lhs = (${lhs.tp}) c1->getArg(${argMapping(reg)._1});", forceful = true)
        }
    case SetMem(dram, data) => 
      emit(src"c1->memcpy($dram, &(*${data})[0], (*${data}).size() * sizeof(int32_t));", forceful = true)
    case GetMem(dram, data) => 
      emit(src"c1->memcpy(&(*$data)[0], $dram, (*${data}).size() * sizeof(int32_t));", forceful = true)
    case _ => super.emitNode(lhs, rhs)
  }



}
