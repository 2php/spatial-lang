package spatial.banking

import argon.analysis._
import argon.core._
import forge._
import spatial.aliases._
import spatial.metadata._
import spatial.utils._
import argon.nodes._
import spatial.lang.DataConversionOps

import scala.collection.mutable

object Affine {
  def unapply(pattern: IndexPattern): Option[(Array[Int], Seq[Exp[Index]], Int)] = pattern match {
    case SymbolicAffine(products, offset) =>
      val asOpt = products.map(p => p.a.getEval{case Literal(c) => c.toInt })
      val (b,bFunc) = offset.partialEval{case Literal(c) => c.toInt}
      if (asOpt.forall(_.isDefined)) {
        val as = asOpt.map(_.get).toArray
        val is = products.map(_.i)

        // TODO: Should be a simpler way to identify this. This seems excessively complicated
        bFunc match {
          case Some(SumOfProds(prods)) =>
            val pe = prods.map{
              case Left(s)  => Some((1,s))
              case Right(f) =>
                val (a,x) = f.partialEval{case Literal(c) => c.toInt }
                x match {
                  case Some(p: Prod) if p.x.length == 1 => p.x.head match {
                    case Left(s) => Some((a,s))
                    case _ => None
                  }
                  case _ => None
                }
            }
            if (pe.forall(_.isDefined)) {
              val ofs = pe.map(_.get)
              val xas = ofs.map(_._1)
              val xs  = ofs.map(_._2)
              Some((as ++ xas, is ++ xs, b))
            }
            else None

          case None => Some((as,is,b))
          case _ => None
        }
      }
      else None
    case _ => None
  }
}

/**
  * Abstract class for any banking strategy
  * (Currently, the only strategy is (possibly hierarchical) mod banking)
  */
sealed abstract class Banking {
  def nBanks: Int
  def stride: Int
  def dims: Seq[Int]
  @internal def bankAddress(ndAddr: Seq[Exp[Index]]): Exp[Index]
  @internal def constBankAddress(ndAddr: Seq[Int]): Int
}

/**
  * Banking address function (alpha*A / B) mod N
  */
case class ModBanking(N: Int, B: Int, alpha: Seq[Int], dims: Seq[Int]) extends Banking {
  override def nBanks: Int = N
  override def stride: Int = B

  @internal def bankAddress(ndAddr: Seq[Exp[Index]]): Exp[Index] = {
    val mults = alpha.zip(dims).map{case (a,dim) => wrap(ndAddr(dim))*a }
    val addr  = (spatial.lang.Math.sumTree(mults) / B) % N
    addr.s
  }
  @internal def constBankAddress(ndAddr: Seq[Int]): Int = {
    (alpha.zip(dims).map{case (a,dim) => ndAddr(dim)*a }.sum / B) % N
  }
}
object ModBanking {
  def Unit(nDims: Int) = ModBanking(1, 1, Seq.fill(nDims)(1), Seq.fill(nDims)(1))
}

object RegBank { def apply(): Seq[ModBanking] = Seq(ModBanking(1, 1, Seq(1), Seq(1))) }


/**
  * Used during memory analysis to track intermediate results
  */
case class InstanceGroup(
  var reads:    Seq[Set[AccessMatrix]], // All reads within this group
  var writes:   Seq[Set[AccessMatrix]], // All writes within this group
  var ctrls:    Set[Ctrl],              // Set of controllers these accesses are in
  var metapipe: Option[Ctrl],           // Controller if at least some accesses require n-buffering
  var banking:  Seq[Banking],           // Banking information
  var depth:    Int,                    // Depth of n-buffer
  var cost:     Int,                    // Cost estimate of this configuration
  var ports:    Map[Access,Int]         // Ports
) {
  // This is an accumulating group if there exists an accumulating write and a read in the same controller
  @stateful def isAcc: Boolean = {
    writes.exists{wrs => wrs.exists{wr =>
      isAccum(wr.access.node) && reads.exists{rds => rds.exists{rd => rd.access.ctrl == wr.access.ctrl }}
    }}
  }
}

/**
  * Used internally in memory analysis to track finalized results
  */
case class MemoryInstance(
  reads:    Seq[Set[UnrolledAccess]],      // All reads within this group
  writes:   Seq[Set[UnrolledAccess]],      // All writes within this group
  metapipe: Option[Ctrl],                  // Controller if at least some accesses require n-buffering
  banking:  Seq[Banking],                  // Banking information
  depth:    Int,                           // Depth of n-buffer
  ports:    Map[Access,Set[Int]],          // Ports
  isAccum:  Boolean                        // Flag whether this instance is an accumulator
)

/**
  * Abbreviated version of MemoryInstance for use outside memory analysis
  */
case class Memory(
  banking: Seq[Banking],  // Banking information
  depth:   Int,           // Buffer depth
  isAccum: Boolean        // Flags whether this instance is an accumulator
) {
  def nBanks: Seq[Int] = banking.map(_.nBanks)
  def totalBanks: Int = banking.map(_.nBanks).product

  @internal def bankAddress(addr: Seq[Exp[Index]]): Seq[Exp[Index]] = banking.map(_.bankAddress(addr))
  @internal def constBankAddress(addr: Seq[Int]): Seq[Int] = banking.map(_.constBankAddress(addr))

  @internal def bankOffset(mem: Exp[_], addr: Seq[Exp[Index]]): Exp[Index] = {
    val w = constDimsOf(mem)
    val d = w.length
    val n = banking.map(_.nBanks).product
    val inst = instanceOf(mem)
    val dims = constDimsOf(mem)
    val nBanks = inst.nBanks.product
    val maxAddr = Math.ceil(dims.product.toDouble).toInt
    val width = bitsToAddress(maxAddr).toInt
    implicit val ibits: INT[Any] = INT.from[Any](width)
    val tp = new FixPtType[TRUE,Any,_0](BOOL[TRUE],ibits, INT[_0])
    val bits = tp.getBits.get
    if (banking.length == 1) {
      val b = banking.head.stride

      spatial.lang.Math.sumTree((0 until d).map{t =>
        val xt = wrap(addr(t)).asInternal(tp, bits, ctx, state)
        if (t < d - 1) { xt * (w.slice(t+1,d-1).product * math.ceil(w(d-1).toDouble / (n*b)).toInt * b) }
        else           { (xt / (n*b)) * b + xt % b }
      }).asInternal[Index].s
    }
    else if (banking.length == w.length) {
      val b = banking.map(_.stride)
      val n = banking.map(_.nBanks)
      val dims = (0 until d).map{t => (t+1 until d).map{k => math.ceil(w(k)/n(k)).toInt }.product }

      spatial.lang.Math.sumTree((0 until d).map{t =>
        val xt = wrap(addr(t)).asInternal(tp, bits, ctx, state)
        ( ( xt/(b(t)*n(t)) )*b(t) + xt%b(t) ) * dims(t)
      }).asInternal[Index].s
    }
    else {
      // TODO: Bank address for mixed dimension groups
      throw new Exception("Bank address calculation for arbitrary dim groupings unknown")
    }
  }

  @internal def constBankOffset(mem: Exp[_], addr: Seq[Int]): Int = {
    val w = constDimsOf(mem)
    val d = w.length
    val n = banking.map(_.nBanks).product
    val b = banking.find(_.dims.contains(d-1)).map(_.stride).getOrElse(1)
    (0 until d).map{t =>
      val xt = addr(t)
      if (t < d - 1) { xt * (w.slice(t+1,d-2).product * math.ceil(w(d-1).toDouble / (n*b)).toInt * b) }
      else           { (xt / (n*b)) * b + xt % b }
    }.sum
  }
}


/**
  * Metadata for duplicates of a single coherent memory.
  */
case class Duplicates(dups: Seq[Memory]) extends Metadata[Duplicates] { def mirror(f:Tx) = this }
@data object duplicatesOf {
  def apply(mem: Exp[_]): Seq[Memory] = metadata[Duplicates](mem).map(_.dups).getOrElse(Nil)
  def update(mem: Exp[_], dups: Seq[Memory]): Unit = metadata.add(mem, Duplicates(dups))
}

@data object instanceOf {
  def apply(mem: Exp[_]): Memory = metadata[Duplicates](mem).map(_.dups).flatMap(_.headOption).getOrElse{
    bug(mem.ctx, c"No instance available for symbol $mem (no duplicates)")
    bug(mem.ctx)
    null
  }
  def update(mem: Exp[_], dup: Memory): Unit = metadata.add(mem, Duplicates(Seq(dup)))
}


/**
  * Metadata for determining which memory duplicate(s) an access should connect to.
  */
case class AccessDispatch(mapping: mutable.HashMap[Exp[_], mutable.HashMap[Seq[Int],Set[Int]]]) extends Metadata[AccessDispatch] {
  def mirror(f:Tx) = AccessDispatch(mapping.map{case (mem,idxs) => f(mem) -> idxs })
}
@data object dispatchOf {
  private def getOrAdd(access: Exp[_]): mutable.HashMap[Exp[_],mutable.HashMap[Seq[Int],Set[Int]]] = {
    metadata[AccessDispatch](access).map(_.mapping) match {
      case Some(map) => map
      case None =>
        val map = mutable.HashMap.empty[Exp[_],mutable.HashMap[Seq[Int],Set[Int]]]
        metadata.add(access, AccessDispatch(map))
        map
    }
  }
  private def getOrAdd(access: Exp[_], mem: Exp[_]): mutable.HashMap[Seq[Int],Set[Int]] = {
    val map = getOrAdd(access)
    if (map.contains(mem)) map(mem) else {
      val innerMap = mutable.HashMap.empty[Seq[Int],Set[Int]]
      map += mem -> innerMap
      innerMap
    }
  }

  def getUnsafe(access: Exp[_], mem: Exp[_]): Map[Seq[Int],Set[Int]] = {
    metadata[AccessDispatch](access).map(_.mapping).flatMap(_.get(mem)).map(_.toMap).getOrElse(Map.empty)
  }

  def get(access: UAccess, mem: Exp[_]): Option[Set[Int]] = {
    metadata[AccessDispatch](access.node).map(_.mapping).flatMap(_.get(mem)).flatMap(_.get(access.id))
  }

  def apply(access: UAccess, mem: Exp[_]): Set[Int] = {
    dispatchOf.get(access, mem).getOrElse{
      bug(c"Access $access had no dispatch information for memory $mem")
      bug(access.node.ctx)
      Set.empty
    }
  }

  /**
    * Sets the dispatches of the unrolled access for the given memory to the given indices
    */
  def update(access: UAccess, mem: Exp[_], dispatches: Set[Int]): Unit = {
    getOrAdd(access.node, mem) += access.id -> dispatches
  }

  def add(access: UAccess, mem: Exp[_], dispatch: Int): Unit = {
    val map = getOrAdd(access.node, mem)
    map += access.id -> (map.getOrElse(access.id, Set.empty[Int]) + dispatch)
  }

  /*def apply(access: Access, mem: Exp[_]): Set[Int] = { dispatchOf(access.node, mem) }
  def get(access: Access, mem: Exp[_]): Option[Set[Int]] = { dispatchOf.get(access.node, mem) }
  def update(access: Access, mem: Exp[_], idxs: Set[Int]) { dispatchOf(access.node, mem) = idxs }
  def add(access: Access, mem: Exp[_], idx: Int) { dispatchOf.add(access.node, mem, idx) }*/

  //def clear(access: Access, mem: Exp[_]): Unit = { dispatchOf(access, mem) = Set[Int]() }
}


case class UnusedAccess(flag: Boolean) extends Metadata[UnusedAccess] {
  def mirror(f:Tx) = this
}
@data object isUnusedAccess {
  def apply(access: Exp[_]): Boolean = metadata[UnusedAccess](access).exists(_.flag)
  def update(access: Exp[_], flag: Boolean): Unit = metadata.add(access, UnusedAccess(flag))
}

/**
  * Metadata for which n-buffered ports a given access should connect to. Ports should either be:
  * - Undefined (for unbuffered cases)
  * - A single port (for buffered cases)
  * - All ports of the given memory (for writes time multiplexed with the buffer)
  */
case class PortIndex(mapping: Map[Exp[_], Map[Int, Set[Int]]]) extends Metadata[PortIndex] {
  def mirror(f:Tx) = PortIndex(mapping.map{case (mem,idxs) => f(mem) -> idxs })
}
@data object portsOf {
  private def get(access: Exp[_]): Option[Map[Exp[_], Map[Int, Set[Int]]]] = metadata[PortIndex](access).map(_.mapping)

  def get(access: Exp[_], mem: Exp[_]): Option[Map[Int, Set[Int]]] = portsOf.get(access).flatMap(_.get(mem))

  // Get all port mappings for this access for the given memory
  def apply(access: Exp[_], mem: Exp[_]): Map[Int, Set[Int]] = {
    portsOf.get(access, mem).getOrElse { throw new spatial.UndefinedPortsException(access, mem, None) }
  }

  // Get ports for this access for the given memory and instance index
  def apply(access: Exp[_], mem: Exp[_], idx: Int): Set[Int] = {
    val ports = portsOf(access, mem)
    ports.getOrElse(idx, throw new spatial.UndefinedPortsException(access, mem, Some(idx)))
  }

  // Override all ports for this access for the given memory and instance index
  def update(access: Exp[_], mem: Exp[_], idx: Int, ports: Set[Int]): Unit = portsOf.get(access) match {
    case Some(map) =>
      val newMap = map.filterKeys(_ != mem) + (mem -> (map.getOrElse(mem,Map.empty) + (idx -> ports)))
      metadata.add(access, PortIndex(newMap))
    case None =>
      metadata.add(access, PortIndex(Map(mem -> Map(idx -> ports))))
  }

  // Override all ports for this access for the given memory for all instance indices
  def update(access: Exp[_], mem: Exp[_], ports: Map[Int, Set[Int]]): Unit = {
    ports.foreach{case (idx,portSet) => portsOf(access, mem, idx) = portSet }
  }

  def apply(access: Access, mem: Exp[_]): Map[Int,Set[Int]] = portsOf(access.node, mem)
  def get(access: Access, mem: Exp[_]): Option[Map[Int,Set[Int]]] = portsOf.get(access.node, mem)
  def apply(access: Access, mem: Exp[_], idx: Int): Set[Int] = { portsOf(access.node, mem, idx) }
  def update(access: Access, mem: Exp[_], idx: Int, ports: Set[Int]): Unit = { portsOf(access.node, mem, idx) = ports }
  def update(access: Access, mem: Exp[_], ports: Map[Int,Set[Int]]): Unit = { portsOf(access.node, mem) = ports }

  def set(access: Exp[_], mem: Exp[_], ports: Map[Int,Set[Int]]): Unit = portsOf.get(access) match {
    case Some(map) =>
      val newMap = map.filterKeys(_ != mem) + (mem -> ports)
      metadata.add(access, PortIndex(newMap))
    case None =>
      metadata.add(access, PortIndex(Map(mem -> ports)))
  }
}

case class MultiplexIndex(mapping: mutable.Map[(Exp[_],Seq[Int]), Int]) extends Metadata[MultiplexIndex] {
  def mirror(f:Tx) = MultiplexIndex(mapping.map{case ((mem,id),idx) => (f(mem),id) -> idx })
}
@data object muxIndexOf {
  private def get(access: Exp[_]): Option[mutable.Map[(Exp[_],Seq[Int]), Int]] = metadata[MultiplexIndex](access).map(_.mapping)
  private def getOrElseAdd(access: Exp[_]): mutable.Map[(Exp[_],Seq[Int]),Int] = get(access) match {
    case None =>
      val newMap = mutable.HashMap.empty[(Exp[_],Seq[Int]),Int]
      metadata.add(access, MultiplexIndex(newMap))
      newMap
    case Some(map) => map
  }

  def apply(access: Exp[_], id: Seq[Int], mem: Exp[_]): Int = get(access).flatMap(_.get((mem,id))).getOrElse(0)
  def update(access: Exp[_], id: Seq[Int], mem: Exp[_], idx: Int): Unit = {
    getOrElseAdd(access) += ((mem,id) -> idx)
  }
  def getMem(access: Exp[_], mem: Exp[_]): Seq[Int] = get(access) match {
    case None      => Nil
    case Some(map) => map.keys.filter(_._1 == mem).map{k => map(k) }.toSeq
  }

  def apply(access: UnrolledAccess, mem: Exp[_]): Int = apply(access.node, access.id, mem)
  def update(access: UnrolledAccess, mem: Exp[_], idx: Int): Unit = update(access.node, access.id, mem, idx)
}


/**
  * Metadata for the controller determining the done signal for a buffered read or write
  * Set per memory and per instance index
  */
case class TopController(mapping: Map[Exp[_], Ctrl]) extends Metadata[TopController] {
  def mirror(f:Tx) = TopController(mapping.map{case (mem,ctrl) => f(mem) -> mirrorCtrl(ctrl,f) })
}
@data object topControllerOf {
  private def get(access: Exp[_]): Option[Map[Exp[_],Ctrl]] = metadata[TopController](access).map(_.mapping)

  // Get the top controller for the given access, memory, and instance index
  def apply(access: Exp[_], mem: Exp[_]): Option[Ctrl] = {
    topControllerOf.get(access).flatMap(_.get(mem))
  }

  // Set top controller for the given access, memory, and instance index
  def update(access: Exp[_], mem: Exp[_], ctrl: Ctrl): Unit = topControllerOf.get(access) match {
    case Some(map) =>
      val newMap = map.filterKeys(_ != mem) + (mem -> ctrl)
      metadata.add(access, TopController(newMap))
    case None =>
      metadata.add(access, TopController(Map(mem -> ctrl)))
  }

  def update(access: Access, mem: Exp[_], ctrl: Ctrl): Unit = { topControllerOf(access.node, mem) = ctrl }
  def apply(access: Access, mem: Exp[_]): Option[Ctrl] = { topControllerOf(access.node, mem) }
}


case class VerboseBanking(flag: Boolean) extends Metadata[VerboseBanking] {
  def mirror(f:Tx) = this
}
@data object bankVerbose {
  def get(x: Exp[_]): Boolean = metadata[VerboseBanking](x).exists(_.flag)
  def apply[T<:MetaAny[T]](x: T): Unit = metadata.add(x.s, VerboseBanking(true))
}
