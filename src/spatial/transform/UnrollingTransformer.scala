package spatial.transform

import argon.transform.ForwardTransformer
import spatial.SpatialExp
import org.virtualized.SourceContext

trait UnrollingTransformer extends ForwardTransformer { self =>
  val IR: SpatialExp
  import IR._

  override val name = "Unrolling Transformer"

  def strMeta(e: Exp[_]): Unit = metadata.get(e).foreach{m => dbgs(c" - ${m._1}: ${m._2}") }

  var cloneFuncs: List[Exp[_] => Unit] = Nil
  def duringClone[T](func: Exp[_] => Unit)(blk: => T): T = {
    val prevCloneFuncs = cloneFuncs
    cloneFuncs ::= func
    val result = blk
    cloneFuncs = prevCloneFuncs
    result
  }
  def inReduction[T](blk: => T): T = duringClone{e => /*if (SpatialConfig.genCGRA) reduceType(e) = None*/ () }{ blk }

  var validBit: Option[Exp[Bool]] = None
  def withValid[T](valid: Exp[Bool])(blk: => T): T = {
    val prevValid = validBit
    validBit = Some(valid)
    val result = blk
    validBit = prevValid
    result
  }
  def globalValid = validBit.getOrElse(bool(true))

  /**
    * Helper class for unrolling
    * Tracks multiple substitution contexts in 'contexts' array
    **/
  case class Unroller(cchain: Exp[CounterChain], inds: Seq[Bound[Index]], isInnerLoop: Boolean) {
    // Don't unroll inner loops for CGRA generation
    val Ps = parFactorsOf(cchain).map{case Exact(c) => c.toInt } /*if (isInnerLoop && SpatialConfig.genCGRA) inds.map{i => 1} */
    val P = Ps.product
    val N = Ps.length
    val prods = List.tabulate(N){i => Ps.slice(i+1,N).product }
    val indices: Seq[Seq[Bound[Index]]]    = Ps.map{p => List.fill(p){ fresh[Index] }}
    val indexValids: Seq[Seq[Bound[Bool]]] = Ps.map{p => List.fill(p){ fresh[Bool] }}

    // Valid bits corresponding to each lane
    lazy val valids = List.tabulate(P){p =>
      val laneIdxValids = indexValids.zip(parAddr(p)).map{case (vec,i) => vec(i)}
      (laneIdxValids ++ validBit).reduce{(a,b) => bool_and(a,b) }
    }

    def size = P

    def parAddr(p: Int) = List.tabulate(N){d => (p / prods(d)) % Ps(d) }

    // Substitution for each duplication "lane"
    val contexts = Array.tabulate(P){p =>
      val inds2 = indices.zip(parAddr(p)).map{case (vec, i) => vec(i) }
      Map.empty[Exp[Any],Exp[Any]] ++ inds.zip(inds2)
    }

    def inLane[A](i: Int)(block: => A): A = {
      val save = subst
      withSubstRules(contexts(i)) {
        withValid(valids(i)) {
          val result = block
          // Retain only the substitutions added within this scope
          contexts(i) ++= subst.filterNot(save contains _._1)
          result
        }
      }
    }

    def map[A](block: Int => A): List[A] = List.tabulate(P){p => inLane(p){ block(p) } }

    def foreach(block: Int => Unit) { map(block) }

    def vectorize[T:Bits](block: Int => Exp[T])(implicit ctx: SrcCtx): Exp[Vector[T]] = {
      IR.vectorize(map(block))
    }

    // --- Each unrolling rule should do at least one of three things:
    // 1. Split a given vector as the substitution for the single original symbol
    def duplicate[A](s: Sym[A], d: Op[A]): List[Exp[_]] = map{_ =>
      val s2 = cloneOp(s, d)
      register(s -> s2)
      s2
    }
    // 2. Make later stages depend on the given substitution across all lanes
    // NOTE: This assumes that the node has no meaningful return value (i.e. all are Pipeline or Unit)
    // Bad things can happen here if you're not careful!
    def split[T:Bits](orig: Sym[_], vec: Exp[Vector[T]])(implicit ctx: SrcCtx): List[Exp[T]] = map{p =>
      val element = vector_apply[T](vec, p)
      register(orig -> element)
      element
    }
    // 3. Create an unrolled clone of symbol s for each lane
    def unify(orig: Exp[_], unrolled: Exp[_]): List[Exp[_]] = {
      foreach{p => register(orig -> unrolled) }
      List(unrolled)
    }

    // Same symbol for all lanes
    def isCommon(e: Exp[_]) = contexts.map{p => f(e)}.forall{e2 => e2 == f(e)}
  }

  override def transform[A:Staged](lhs: Sym[A], rhs: Op[A])(implicit ctx: SrcCtx): Exp[A] = (rhs match {
    case e:OpForeach        => unrollForeachNode(lhs, e)
    case e:OpReduce[_]      => unrollReduceNode(lhs, e)
    case e:OpMemReduce[_,_] => unrollMemReduceNode(lhs, e)
    case e:Scatter[_]       => unrollScatterNode(lhs, e)
    case e:Gather[_]        => unrollGatherNode(lhs, e)
    case _ => super.transform(lhs, rhs)
  }).asInstanceOf[Exp[A]]


  /**
    * Create duplicates of the given node or special case, vectorized version
    * NOTE: Only can be used within reify scope
    **/
  def unroll[T](lhs: Sym[T], rhs: Op[T], lanes: Unroller)(implicit ctx: SrcCtx): List[Exp[_]] = rhs match {
    // Account for the edge case with FIFO writing
    case e@FIFOEnq(fifo, data, en) if lanes.isCommon(fifo) =>
      dbgs(s"Unrolling $lhs = $rhs")
      val datas  = lanes.vectorize{p => f(data)}(mbits(e.bT), ctx)
      val enables = lanes.vectorize{p => bool_and( f(en), globalValid) }
      val lhs2 = par_fifo_enq(f(fifo), datas, enables)(e.bT,ctx)

      transferMetadata(lhs, lhs2)
      cloneFuncs.foreach{func => func(lhs2) }
      lanes.unify(lhs, lhs2)

    case e@FIFODeq(fifo, en, z) if lanes.isCommon(fifo) =>
      dbgs(s"Unrolling $lhs = $rhs")
      val enables = lanes.vectorize{p => bool_and(f(en), globalValid) }
      val lhs2 = par_fifo_deq(f(fifo), enables, f(z))(mbits(e.bT),ctx)

      transferMetadata(lhs, lhs2)
      cloneFuncs.foreach{func => func(lhs2) }
      lanes.split(lhs, lhs2)(mbits(e.bT), ctx)

    // TODO: Assuming dims and ofs are not needed for now
    case e@SRAMStore(sram,dims,inds,ofs,data,en) if lanes.isCommon(sram) =>
      dbgs(s"Unrolling $lhs = $rhs")
      strMeta(lhs)

      val addrs  = lanes.map{p => inds.map(f(_)) }
      val values = lanes.vectorize{p => f(data)}(mbits(e.bT), ctx)
      val ens    = lanes.vectorize{p => bool_and(f(en), globalValid) }
      val lhs2   = par_sram_store(f(sram), addrs, values, ens)(e.bT, ctx)

      transferMetadata(lhs, lhs2)
      cloneFuncs.foreach{func => func(lhs2) }

      dbgs(s"Created ${str(lhs2)}")
      strMeta(lhs2)

      lanes.unify(lhs, lhs2)

    // TODO: Assuming dims and ofs are not needed for now
    case e@SRAMLoad(sram,dims,inds,ofs) if lanes.isCommon(sram) =>
      dbgs(s"Unrolling $lhs = $rhs")
      strMeta(lhs)

      val addrs = lanes.map{p => inds.map(f(_)) }
      val lhs2 = par_sram_load(f(sram), addrs)(mbits(e.bT), ctx)

      transferMetadata(lhs, lhs2)
      cloneFuncs.foreach{func => func(lhs2) }

      dbgs(s"Created ${str(lhs2)}")
      strMeta(lhs2)

      lanes.split(lhs, lhs2)(mbits(e.bT),ctx)

    case e: OpForeach        => unrollControllers(lhs,rhs,lanes){ unrollForeachNode(lhs, e) }
    case e: OpReduce[_]      => unrollControllers(lhs,rhs,lanes){ unrollReduceNode(lhs, e) }
    case e: OpMemReduce[_,_] => unrollControllers(lhs,rhs,lanes){ unrollMemReduceNode(lhs, e) }
    case e: Scatter[_]       => unrollControllers(lhs,rhs,lanes){ unrollScatterNode(lhs, e) }
    case e: Gather[_]        => unrollControllers(lhs,rhs,lanes){ unrollGatherNode(lhs, e) }
    case _ if isControlNode(lhs) => unrollControllers(lhs,rhs,lanes){ cloneOp(lhs, rhs) }

    case e: RegNew[_] =>
      dbgs(s"Duplicating $lhs = $rhs")
      val dups = lanes.duplicate(lhs, rhs)
      dbgs(s"  Created registers: ")
      lanes.foreach{p => dbgs(s"  $p: $lhs -> ${f(lhs)}") }
      dups

    case _ =>
      dbgs(s"Duplicating $lhs = $rhs")
      val dups = lanes.duplicate(lhs, rhs)
      dups
  }


  def unrollControllers[T](lhs: Sym[T], rhs: Op[T], lanes: Unroller)(unroll: => Exp[_]) = {
    dbgs(s"Unrolling controller:")
    dbgs(s"$lhs = $rhs")
    if (lanes.size > 1) {
      val lhs2= op_parallel_pipe {
        lanes.foreach{p =>
          dbgs(s"$lhs duplicate ${p+1}/${lanes.size}")
          unroll
        }
        void
      }
      lanes.unify(lhs, lhs2)
    }
    else {
      dbgs(s"$lhs duplicate 1/1")
      val first = lanes.inLane(0){ unroll }
      lanes.unify(lhs, first)
    }
  }


  /**
    * Unrolls purely independent loop iterations
    * NOTE: The func block should already have been mirrored to update dependencies prior to unrolling
    */
  def unrollMap[T:Staged](func: Block[T], lanes: Unroller)(implicit ctx: SrcCtx): List[Exp[T]] = {
    val origResult = func.result

    tab += 1
    mangleBlock(func, {stms =>
      stms.foreach{case TP(lhs,rhs) => unroll(lhs, rhs, lanes)(ctxOrHere(lhs)) }
    })
    tab -= 1

    // Get the list of duplicates for the original result of this block
    lanes.map{p => f(origResult) }
  }


  def unrollForeach (
    lhs:    Exp[_],
    cchain: Exp[CounterChain],
    func:   Block[Void],
    iters:  Seq[Bound[Index]]
  )(implicit ctx: SrcCtx) = {
    dbgs(s"Unrolling foreach $lhs")

    val lanes = Unroller(cchain, iters, isInnerControl(lhs))
    val is = lanes.indices
    val vs = lanes.indexValids

    val blk = stageBlock { unrollMap(func, lanes); void }


    val effects = blk.summary
    val lhs2 = stageEffectful(UnrolledForeach(cchain, blk, is, vs), effects.star)(ctx)
    transferMetadata(lhs, lhs2)

    dbgs(s"Created foreach ${str(lhs2)}")
    lhs2
  }
  def unrollForeachNode(lhs: Sym[_], rhs: OpForeach)(implicit ctx: SrcCtx) = {
    val OpForeach(cchain, func, iters) = rhs
    unrollForeach(lhs, f(cchain), func, iters)
  }


  def unrollReduceTree[T:Bits](
    inputs: Seq[Exp[T]],
    valids: Seq[Exp[Bool]],
    zero:   Option[Exp[T]],
    reduce: (Exp[T], Exp[T]) => Exp[T]
  )(implicit ctx: SrcCtx): Exp[T] = zero match {
    case Some(z) =>
      val validInputs = inputs.zip(valids).map{case (in,v) => math_mux(v, in, z) }
      reduceTree(validInputs){(x: Exp[T], y: Exp[T]) => reduce(x,y) }

    case None =>
      // ASSUMPTION: If any values are invalid, they are at the end of the list (corresponding to highest index values)
      // TODO: This may be incorrect if we parallelize by more than the innermost iterator
      val inputsWithValid = inputs.zip(valids)
      reduceTree(inputsWithValid){(x: (Exp[T], Exp[Bool]), y: (Exp[T],Exp[Bool])) =>
        val res = reduce(x._1, y._1)
        (math_mux(y._2, res, x._1), bool_or(x._2, y._2)) // res is valid if x or y is valid
      }._1
  }


  def unrollReduceAccumulate[T:Bits](
    inputs: Seq[Exp[T]],          // Symbols to be reduced
    valids: Seq[Exp[Bool]],       // Data valid bits corresponding to inputs
    zero:   Option[Exp[T]],       // Optional zero value
    rFunc:  Block[T],             // Reduction function
    load:   Block[T],             // Load function from accumulator
    store:  Block[Void],          // Store function to accumulator
    rV:     (Bound[T], Bound[T])  // Bound symbols used to reify rFunc
  )(implicit ctx: SrcCtx) = {
    def reduce(x: Exp[T], y: Exp[T]) = withSubstScope(rV._1 -> x, rV._2 -> y){ inlineBlock(rFunc) }

    val treeResult = unrollReduceTree[T](inputs, valids, zero, reduce)

    val accValue = inReduction{ inlineBlock(load) }
    val res2 = reduce(treeResult, accValue)
    isReduceResult(res2) = true
    isReduceStarter(accValue) = true

    inReduction{ withSubstScope(rFunc.result -> res2){ inlineBlock(store) } }
  }

  def unrollReduce[T](
    lhs:    Exp[_],               // Original pipe symbol
    cchain: Exp[CounterChain],    // Counterchain
    accum:  Exp[Reg[T]],          // Accumulator
    zero:   Option[Exp[T]],       // Optional identity value for reduction
    fold:   Boolean,              // [Unused]
    load:   Block[T],             // Load function for accumulator
    store:  Block[Void],          // Store function for accumulator
    func:   Block[T],             // Map function
    rFunc:  Block[T],             // Reduce function
    rV:     (Bound[T],Bound[T]),  // Bound symbols used to reify rFunc
    iters:  Seq[Bound[Index]]     // Bound iterators for map loop
  )(implicit ctx: SrcCtx, bT: Bits[T]) = {
    dbgs(s"Unrolling pipe-fold $lhs")
    val lanes = Unroller(cchain, iters, isInnerControl(lhs))
    val inds2 = lanes.indices
    val vs = lanes.indexValids
    val mC = typ[Reg[T]]

    val blk = stageBlock {
      dbgs("Unrolling map")
      val values = unrollMap(func, lanes)(bT,ctx)
      val valids = lanes.valids

      if (isOuterControl(lhs)) {
        dbgs("Unrolling unit pipe reduce")
        Pipe { Void(unrollReduceAccumulate[T](values, valids, zero, rFunc, load, store, rV)) }
      }
      else {
        dbgs("Unrolling inner reduce")
        unrollReduceAccumulate[T](values, valids, zero, rFunc, load, store, rV)
      }
      void
    }
    val rV2 = (fresh[T],fresh[T])
    val rFunc2 = withSubstScope(rV._1 -> rV2._1, rV._2 -> rV2._2){ transformBlock(rFunc) }

    val effects = blk.summary
    val lhs2 = stageEffectful(UnrolledReduce(cchain, accum, blk, rFunc2, inds2, vs, rV2)(bT,mC), effects.star)(ctx)
    transferMetadata(lhs, lhs2)
    dbgs(s"Created reduce ${str(lhs2)}")
    lhs2
  }
  def unrollReduceNode[T](lhs: Sym[_], rhs: OpReduce[T])(implicit ctx: SrcCtx) = {
    val OpReduce(cchain,accum,map,load,reduce,store,rV,iters) = rhs
    unrollReduce[T](lhs, f(cchain), f(accum), None, true, load, store, map, reduce, rV, iters)(ctx, rhs.bT)
  }



  def unrollMemReduce[T,C[T]](
    lhs:   Exp[_],                  // Original pipe symbol
    cchainMap: Exp[CounterChain],   // Map counterchain
    cchainRed: Exp[CounterChain],   // Reduction counterchain
    accum:    Exp[C[T]],            // Accumulator (external)
    zero:     Option[Exp[T]],       // Optional identity value for reduction
    fold:     Boolean,              // [Unused]
    func:     Block[C[T]],          // Map function
    loadRes:  Block[T],             // Load function for intermediate values
    loadAcc:  Block[T],             // Load function for accumulator
    rFunc:    Block[T],             // Reduction function
    storeAcc: Block[Void],          // Store function for accumulator
    rV:       (Bound[T],Bound[T]),  // Bound symbol used to reify rFunc
    itersMap: Seq[Bound[Index]],    // Bound iterators for map loop
    itersRed: Seq[Bound[Index]]     // Bound iterators for reduce loop
  )(implicit ctx: SrcCtx, bT: Bits[T], mC: Staged[C[T]]) = {
    dbgs(s"Unrolling accum-fold $lhs")

    def reduce(x: Exp[T], y: Exp[T]) = withSubstScope(rV._1 -> x, rV._2 -> y){ inlineBlock(rFunc)(bT) }

    val mapLanes = Unroller(cchainMap, itersMap, isInnerLoop = false)
    val isMap2 = mapLanes.indices
    val mvs = mapLanes.indexValids
    val partial = func.result

    val blk = stageBlock {
      dbgs(s"[Accum-fold $lhs] Unrolling map")
      val mems = unrollMap(func, mapLanes)
      val mvalids = mapLanes.valids

      if (isUnitCounterChain(cchainRed)) {
        dbgs(s"[Accum-fold $lhs] Unrolling unit pipe reduction")
        Pipe {
          val values = inReduction{ mems.map{mem => withSubstScope(partial -> mem){ inlineBlock(loadRes)(bT) }} }
          inReduction{ unrollReduceAccumulate[T](values, mvalids, zero, rFunc, loadAcc, storeAcc, rV) }
          Void(void)
        }
      }
      else {
        dbgs(s"[Accum-fold $lhs] Unrolling pipe-reduce reduction")
        tab += 1

        val reduceLanes = Unroller(cchainRed, itersRed, true)
        val isRed2 = reduceLanes.indices
        val rvs = reduceLanes.indexValids
        reduceLanes.foreach{p =>
          dbgs(s"Lane #$p")
          itersRed.foreach{i => dbgs(s"  $i -> ${f(i)}") }
        }

        val rBlk = stageBlock {
          dbgs(c"[Accum-fold $lhs] Unrolling map loads")
          dbgs(c"  memories: $mems")

          val values: Seq[Seq[Exp[T]]] = inReduction {
            mems.map{mem =>
              withSubstScope(partial -> mem) {
                unrollMap(loadRes, reduceLanes)(mbits(bT), ctx)
              }
            }
          }

          dbgs(s"[Accum-fold $lhs] Unrolling accum loads")
          reduceLanes.foreach{p =>
            dbgs(s"Lane #$p")
            itersRed.foreach{i => dbgs(s"  $i -> ${f(i)}") }
          }

          val accValues = inReduction{ unrollMap(loadAcc, reduceLanes)(mbits(bT),ctx) }

          dbgs(s"[Accum-fold $lhs] Unrolling reduction trees and cycles")
          reduceLanes.foreach{p =>
            val laneValid = reduceLanes.valids(p)

            dbgs(s"Lane #$p:")
            tab += 1
            val inputs = values.map(_.apply(p)) // The pth value of each vector load
            val valids = mvalids.map{mvalid => bool_and(mvalid, laneValid) }

            dbgs("Valids:")
            valids.foreach{s => dbgs(s"  ${str(s)}")}

            dbgs("Inputs:")
            inputs.foreach{s => dbgs(s"  ${str(s)}") }

            val accValue = accValues(p)
            val res2 = inReduction {
              val treeResult = unrollReduceTree(inputs, valids, zero, reduce)
              reduce(treeResult, accValue)
            }
            isReduceResult(res2) = true
            isReduceStarter(accValue) = true
            register(rFunc.result -> res2)  // Lane-specific substitution

            tab -= 1
          }

          dbgs(s"[Accum-fold $lhs] Unrolling accumulator store")
          inReduction{ unrollMap(storeAcc, reduceLanes) }
          void
        }

        val effects = rBlk.summary
        val rpipe = stageEffectful(UnrolledForeach(cchainRed, rBlk, isRed2, rvs), effects.star)(ctx)
        styleOf(rpipe) = InnerPipe
        tab -= 1
      }
      void
    }

    val rV2 = (fresh[T],fresh[T])
    val rFunc2 = withSubstScope(rV._1 -> rV2._1, rV._2 -> rV2._2){ transformBlock(rFunc) }

    val effects = blk.summary

    val lhs2 = stageEffectful(UnrolledReduce(cchainMap, accum, blk, rFunc2, isMap2, mvs, rV2)(bT,mC), effects.star)(ctx)
    transferMetadata(lhs, lhs2)

    dbgs(s"[Accum-fold] Created reduce ${str(lhs2)}")
    lhs2
  }
  def unrollMemReduceNode[T,C[T]](lhs: Sym[_], rhs: OpMemReduce[T,C])(implicit ctx: SrcCtx) = {
    val OpMemReduce(cchainMap,cchainRed,accum,func,loadRes,loadAcc,reduce,storeAcc,rV,itersMap,itersRed) = rhs
    unrollMemReduce(lhs,f(cchainMap),f(cchainRed),f(accum),None,true,func,loadRes,loadAcc,reduce,storeAcc,rV,itersMap,itersRed)(ctx,rhs.bT,rhs.mC)
  }

  // TODO: Method for parallelizing scatter and gather will likely have to change soon
  def unrollScatter[T:Bits](
    lhs:    Exp[_],
    mem:    Exp[DRAM[T]],
    local:  Exp[SRAM[T]],
    addrs:  Exp[SRAM[Index]],
    ctr:    Exp[Counter]
  )(implicit ctx: SrcCtx) = {
    val par = parFactorsOf(ctr).head match {case Final(c) => c.toInt }
    op_parallel_pipe {
      (0 until par).foreach{i =>
        val scatter = stageWrite(mem)(Scatter[T](mem,local,addrs,ctr,fresh[Index]))(ctx)
        transferMetadata(lhs, scatter)
      }
      void
    }
  }
  def unrollScatterNode[T](lhs: Sym[_], rhs: Scatter[T])(implicit ctx: SrcCtx) = {
    val Scatter(mem, local, addrs, ctr, _) = rhs
    unrollScatter(lhs, f(mem), f(local), f(addrs), f(ctr))(rhs.bT, ctx)
  }

  def unrollGather[T:Bits](
    lhs:   Exp[_],
    mem:   Exp[DRAM[T]],
    local: Exp[SRAM[T]],
    addrs: Exp[SRAM[Index]],
    ctr:   Exp[Counter]
  )(implicit ctx: SrcCtx) = {
    val par = parFactorsOf(ctr).head match {case Final(c) => c.toInt }
    op_parallel_pipe {
      (0 until par).foreach{i =>
        val gather = stageWrite(local)(Gather[T](mem,local,addrs,ctr,fresh[Index]))(ctx)
        transferMetadata(lhs, gather)
      }
      void
    }
  }
  def unrollGatherNode[T](lhs: Sym[_], rhs: Gather[T])(implicit ctx: SrcCtx) = {
    val Gather(mem, local, addrs, ctr, i) = rhs
    unrollGather(lhs, f(mem), f(local), f(addrs), f(ctr))(rhs.bT, ctx)
  }

  def cloneOp[A](lhs: Sym[A], rhs: Op[A]): Exp[A] = {
    def cloneOrMirror(lhs: Sym[A], rhs: Op[A])(implicit mA: Staged[A], ctx: SrcCtx): Exp[A] = (rhs match {
      case e@SRAMStore(sram, dims, inds, ofs, data, en) =>
        val en2 = bool_and(f(en), globalValid)
        sram_store(f(sram), f(dims), f(inds), f(ofs), f(data), en2)(mbits(e.bT), ctx)

      case e@RegWrite(reg, data, en) =>
        val en2 = bool_and(f(en), globalValid)
        reg_write(f(reg), f(data), en2)(mbits(e.bT), ctx)

      case e@FIFODeq(fifo, en, z) =>
        val en2 = bool_and(f(en), globalValid)
        fifo_deq(f(fifo), en2, f(z))(mbits(e.bT), ctx)

      case _ => mirror(lhs, rhs)
    }).asInstanceOf[Exp[A]]

    dbgs(c"Cloning $lhs = $rhs")
    strMeta(lhs)

    val (lhs2, isNew) = transferMetadataIfNew(lhs){ cloneOrMirror(lhs, rhs)(mtyp(lhs.tp), ctxOrHere(lhs)) }
    if (isNew) cloneFuncs.foreach{func => func(lhs2) }
    dbgs(c"Created ${str(lhs2)}")
    strMeta(lhs2)

    lhs2
  }
}
