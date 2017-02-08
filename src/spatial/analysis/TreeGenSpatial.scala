package spatial.analysis

import argon.traversal.Traversal
import spatial.SpatialExp
import argon.Config
import java.io.PrintWriter

trait TreeGenSpatial extends SpatialTraversal {
  import IR._

  // def getStages(blks: Block[_]*): Seq[Sym[_]] = blks.flatMap(blockContents).flatMap(_.lhs)

  // def getPrimitiveNodes(blks: Block[_]*): Seq[Sym[_]] = getStages(blks:_*).filter(isPrimitiveNode)
  // def getControlNodes(blks: Block[_]*): Seq[Sym[_]] = getStages(blks:_*).filter(isControlNode)
  // def getAllocations(blks: Block[_]*): Seq[Sym[_]] = getStages(blks:_*).filter(isAllocation)

  // def hasPrimitiveNodes(blks: Block[_]*): Boolean = blks.exists{blk => getControlNodes(blk).nonEmpty }
  // def hasControlNodes(blks: Block[_]*): Boolean = blks.exists{blk => getControlNodes(blk).nonEmpty }

  var controller_tree: PrintWriter = _
  val table_init = """<TABLE BORDER="3" CELLPADDING="10" CELLSPACING="10">"""

  override def preprocess[S:Staged](block: Block[S]): Block[S] = {
    controller_tree = { new PrintWriter(Config.genDir + "/controller_tree.html") }
  	controller_tree.write("""<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<link rel="stylesheet" href="http://code.jquery.com/mobile/1.4.5/jquery.mobile-1.4.5.min.css">
<script src="http://code.jquery.com/jquery-1.11.3.min.js"></script>
<script src="http://code.jquery.com/mobile/1.4.5/jquery.mobile-1.4.5.min.js"></script>
</head><body>

  <div data-role="main" class="ui-content">
    <h2>Controller Diagram for """)
    controller_tree.write(Config.name)
    controller_tree.write("""</h2>
<TABLE BORDER="3" CELLPADDING="10" CELLSPACING="10">""")
  	super.preprocess(block)
  }

  override def postprocess[S:Staged](block: Block[S]): Block[S] = { 
    controller_tree.write(s"""  </TABLE>
</body>
</html>""")
    controller_tree.close
    super.postprocess(block)
  }

  def print_stage_prefix(title: String, ctr: String, node: String, inner: Boolean = false) {
    controller_tree.write(s"""<TD><font size = "6">$title<br><b>$node</b></font><br><font size = "1">$ctr</font> """)
    if (!inner) {
      controller_tree.write(s"""<div data-role="collapsible">
      <h4> </h4>${table_init}""")
    }
  }
  def print_stage_suffix(name: String, inner: Boolean = false) {
    if (!inner) {
      controller_tree.write("""</TABLE></div>""")
    }
    controller_tree.write(s"</TD><!-- Close $name -->")
  }

  override protected def visit(sym: Sym[_], rhs: Op[_]): Unit = rhs match {
    case Hwblock(func) =>
      val inner = styleOf(sym) match { 
      	case InnerPipe => true
      	case _ => false
      }
      print_stage_prefix(s"Hwblock",s"",s"$sym", inner)
      val children = getControlNodes(func)
      children.foreach { s =>
        val Op(d) = s
        visit(s,d)
      }
      print_stage_suffix(s"$sym", inner)

    case BurstLoad(dram, fifo, ofs, ctr, i) =>
      print_stage_prefix(s"BurstLoad",s"",s"$sym", true)
      print_stage_suffix(s"$sym", true)

    case BurstStore(dram, fifo, ofs, ctr, i) =>
      print_stage_prefix(s"BurstStore",s"",s"$sym", true)
      print_stage_suffix(s"$sym", true)

    case UnitPipe(func) =>
      val inner = styleOf(sym) match { 
      	case InnerPipe => true
      	case _ => false
      }
      print_stage_prefix(s"Unitpipe",s"",s"$sym", inner)
      val children = getControlNodes(func)
      children.foreach { s =>
        val Op(d) = s
        visit(s,d)
      }
      print_stage_suffix(s"$sym", inner)

    case OpForeach(cchain, func, iters) =>
      val inner = styleOf(sym) match { 
      	case InnerPipe => true
      	case _ => false
      }
      print_stage_prefix(s"OpForeach",s"",s"$sym", inner)
      val children = getControlNodes(func)
      children.foreach { s =>
        val Op(d) = s
        visit(s,d)
      }
      print_stage_suffix(s"$sym", inner)

    case OpReduce(cchain, accum, map, load, reduce, store, rV, iters) =>
      val inner = styleOf(sym) match { 
      	case InnerPipe => true
      	case _ => false
      }
      print_stage_prefix(s"OpReduce",s"",s"$sym", inner)
      print_stage_suffix(s"$sym", inner)

    case OpMemReduce(cchainMap,cchainRed,accum,map,loadRes,loadAcc,reduce,storeAcc,rV,itersMap,itersRed) =>
      val inner = styleOf(sym) match { 
      	case InnerPipe => true
      	case _ => false
      }
      print_stage_prefix(s"OpMemReduce",s"",s"$sym", inner)
      print_stage_suffix(s"$sym", inner)

    case UnrolledForeach(cchain,func,iters,valids) =>
      val inner = styleOf(sym) match { 
      	case InnerPipe => true
      	case _ => false
      }
      print_stage_prefix(s"UnrolledForeach",s"",s"$sym", inner)
      val children = getControlNodes(func)
      children.foreach { s =>
        val Op(d) = s
        visit(s,d)
      }
      print_stage_suffix(s"$sym", inner)

    case UnrolledReduce(cchain,_,func,_,iters,valids,_) =>
      val inner = styleOf(sym) match { 
      	case InnerPipe => true
      	case _ => false
      }
      print_stage_prefix(s"UnrolledReduce",s"",s"$sym", inner)
      val children = getControlNodes(func)
      children.foreach { s =>
        val Op(d) = s
        visit(s,d)
      }
      print_stage_suffix(s"$sym", inner)

    case Gather(dram, local, addrs, ctr, i)  =>

    case Scatter(dram, local, addrs, ctr, i) =>


    case _ =>
  }

}
