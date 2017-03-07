package spatial.codegen.cppgen

import argon.codegen.cppgen.CppCodegen
import spatial.api.HostTransferExp
import spatial.SpatialConfig

trait CppGenHostTransfer extends CppCodegen  {
  val IR: HostTransferExp
  import IR._

  var settedArgs: List[Sym[Reg[_]]] = List()

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
      emit(src"interface.ArgIns[${settedArgs.length}] = (${reg.tp}*) $v; // $lhs", forceful = true)
      settedArgs = settedArgs :+ lhs.asInstanceOf[Sym[Reg[_]]]
      emit(src"${reg.tp} $reg = $v;")
    case GetArg(reg)    => emit(src"${lhs.tp} $lhs = *$reg;", forceful = true)
    case SetMem(dram, data) => 
      setMems = setMems :+ src"$dram"
      emit(src"${data.tp}* $dram = $data;", forceful = true)
    case GetMem(dram, data) => 
      setMems = setMems :+ src"$dram"
      emit(src"$data = $dram;", forceful = true)
    case _ => super.emitNode(lhs, rhs)
  }



}
