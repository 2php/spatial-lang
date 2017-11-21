package spatial.banking

import argon.analysis._
import argon.core._
import spatial.aliases._
import spatial.metadata._
import spatial.nodes._
import spatial.utils._
import org.virtualized.SourceContext

import scala.collection.mutable.{ArrayBuffer,HashMap,HashSet}

/**
  * Helper class for configuring banking and buffering of an addressable memory
  */
class MemoryConfigurer(val mem: Exp[_], val strategy: BankingStrategy)(implicit val IR: State) extends MemoryChecks {
  protected val dims: Seq[Int] = constDimsOf(mem)
  protected val lastIndex: HashMap[Exp[Index],Exp[Index]] = new HashMap[Exp[Index],Exp[Index]]
  protected val inVector: HashSet[Exp[Index]] = new HashSet[Exp[Index]]
  protected val unrolledRand= new HashMap[Exp[Index],Map[Seq[Int],Exp[Index]]]

  def unrolledRandomAddresses(access: Access, x: Option[Exp[Index]]): (Map[Seq[Int],Exp[Index]], Int) = {
    val is = x match {
      case Some(addr) => iteratorsBetween(ctrlOf(addr), ctrlOf(mem))
      case None       => iteratorsBetween(access.ctrl, ctrlOf(mem))
    }
    if (x.isDefined && unrolledRand.contains(x.get)) {
      val map = unrolledRand(x.get)
      (map, is.length)
    }
    else {
      val ps = is.map{i => parFactorOf(i).toInt }
      // TODO: Annoying to have to call fresh here..
      val xs = multiLoop(ps).map{id => id -> fresh[Index]}.toMap
      x.foreach{x => unrolledRand += x -> xs }
      (xs, is.length)
    }
  }

  /**
    * Convert this compact access matrix to multiple unrolled access matrices
    * by simulating loop parallelization/unrolling
    */
  def unroll(matrix: CompactMatrix, indices: Seq[Exp[Index]]): Seq[AccessMatrix] = {
    val is = iteratorsBetween(matrix.access.ctrl, ctrlOf(mem))
    val ps = is.map{i => parFactorOf(i).toInt }
    dbg("")
    dbg(s"  Simulating unrolling of access ${matrix.access}")
    dbg(s"  Iterators between access and memory: " + is.zip(ps).map{case (i,p) => c"$i ($p)"}.mkString(", "))

    def expand(vector: AccessVector, id: Seq[Int]): AccessVector = vector match {
      case RandomVector(_,uroll,len) =>
        val xp = uroll.apply(id.take(len))
        RandomVector(Some(xp),uroll,len)

      // Note that there's three sets of iterators here:
      //  is      - iterators defined between the memory and this access
      //  inds    - iterators used by this affine access
      //  indices - iterators used by ALL accesses to this memory
      case AffineVector(as,inds,b) =>
        val unrolled = indices.map{i =>
          val idxAccess = inds.indexOf(i)
          val idxHierarchy = is.indexOf(i)
          val a_orig = if (idxAccess >= 0) as(idxAccess) else 0
          val p = if (idxHierarchy >= 0) ps(idxHierarchy) else 0
          val n = if (idxHierarchy >= 0) id(idxHierarchy) else 0
          val a = a_orig*p
          val b_i = a_orig*n
          (a, b_i)
        }
        val as2 = unrolled.map(_._1).toArray
        val b2 = unrolled.map(_._2).sum + b
        val uvec = AffineVector(as2,indices,b2)
        uvec
    }

    // Fake unrolling
    // e.g. change 2i + 3 with par(i) = 2 into
    // 4i + 0*2 + 3 = 4i + 3
    // 4i + 1*2 + 3 = 4i + 5
    multiLoop(ps).map{id =>
      dbg(s"  Unroll: " + is.zip(id).map{case (i,xid) => c"$i [$xid]" }.mkString(", "))
      val uvectors = matrix.vectors.map{vector => expand(vector, id) }
      val unrollId = id ++ matrix.vecId
      AccessMatrix(uvectors, matrix.access, indices, unrollId)
    }.toSeq
  }


  def configure(): Unit = {
    dbg("")
    dbg("")
    dbg("-----------------------------------")
    dbg(u"Inferring instances for on-chip memory $mem (${mem.ctx})")
    dbg(c"${str(mem)}")

    val writers = writersOf(mem)
    val readers = readersOf(mem)
    checkAccesses(readers, writers)

    val instances = bank(readers, writers)

    finalize(instances)
  }

  /**
    * Calculate the physical instances required to efficiently support the given accesses.
    */
  def bank(readers: Seq[Access], writers: Seq[Access]): Seq[MemoryInstance] = {
    val (readMatrices, writeMatrices, domain) = createAccessMatrices(readers, writers)
    val readGroups = createGroups(readMatrices, domain)
    val writeGroups = createGroups(writeMatrices, domain)

    if (config.verbosity > 0) {
      dbg("")
      dbg(s"  Grouping accesses resulted in the following: ")
      if (readGroups.isEmpty) dbg("  <No Read Groups>") else dbg(s"  ${readGroups.length} Read Group(s):")
      readGroups.zipWithIndex.foreach { case (grp, i) =>
        dbg(s"    Read Group $i: ")
        grp.foreach {read => dbg(s"""      ${str(read.access.node)} [${read.id.mkString(", ")}]""") }
      }
      if (writeGroups.isEmpty) dbg("  <No Write Groups>") else dbg(s"  ${writeGroups.length} Write Group(s):")
      writeGroups.zipWithIndex.foreach { case (grp, i) =>
        dbg(s"    Write Group $i: ")
        grp.foreach {write => dbg(s"""      ${str(write.access.node)} [${write.id.mkString(", ")}]""") }
      }
    }

    val instances = if (readers.nonEmpty) {
      dbg(s"  Merging read groups...")
      mergeReadGroups(readGroups.map(_.toSet), writeGroups, domain)
    }
    else if (writers.nonEmpty) {
      dbg(s"  Merging write groups...")
      mergeWriteGroups(writeGroups, domain)
    }
    else {
      dbg(s"  No accesses?")
      // There's no writers or readers... so whatever?
      val banking = ModBanking(1,1,dims.map{_ => 1}, List.tabulate(dims.length){i => i})
      Seq(InstanceGroup(Nil,Nil,Set.empty,None,Seq(banking),1,1,Map.empty))
    }
    instances.map(instanceGroupToMemoryInstance)
  }

  /**
    * Complete memory analysis by adding banking and buffering metadata to the memory and
    * all associated accesses.
    */
  def finalize(instances: Seq[MemoryInstance]): Unit = {
    printInstances(instances)

    val duplicates = instances.zipWithIndex.map{
      case (MemoryInstance(reads,writes,mp,banking,depth,ports,isAccum), x) =>
        val uaccesses = reads ++ writes
        val accesses  = uaccesses.map(_.access)

        uaccesses.foreach{a => dispatchOf.add(a.uaccess, mem, x) }
        accesses.foreach{access => portsOf(access, mem, x) = ports(access) }

        Memory(banking,depth,isAccum)
    }

    duplicatesOf(mem) = duplicates
  }

  def printInstances(instances: Seq[MemoryInstance]): Unit = {
    dbg("")
    dbg("")
    dbg(u"  SUMMARY for memory $mem:")
    dbg(u"${str(mem)}")
    dbg(mem.ctx.lineContent.getOrElse(""))
    dbg("")
    instances.foreach(printInstance)
  }

  def printInstance(instance: MemoryInstance): Unit = {
    val MemoryInstance(reads,writes,mp,banking,depth,ports,isAccum) = instance
    dbg(s"  isAccum:    $isAccum")
    dbg(s"  Depth:      $depth")
    dbg(s"  Banking:    $banking")
    dbg(s"  Controller: ${mp.map{c=>u"$c"}.getOrElse("---")}")
    dbg(s"  Buffer Ports: ")
    (0 until depth).foreach{port =>
      writes.filter{w => ports(w.access).contains(port) }
              .foreach{w => dbg(c"    $port [WR] $w") }
      reads.filter{r => ports(r.access).contains(port) }
             .foreach{r => dbg(c"    $port [RD] $r") }
    }
  }

  /**
    * Register all of the indices for all associated accesses.
    * Attempt to find the minimum and maximum value for each counter index, allowing
    * for the min/max to be affine functions of other counter values.
    * If the function is not analyzable, assume Int.min or Int.max for min and max, respectively.
    *
    * TODO: The bounds logic should eventually be moved elsewhere (to ScalarAnalyzer)?
    */
  def getIterDomain(indices: Seq[Exp[Index]]): IndexDomain = indices.zipWithIndex.flatMap{case (i,iIdx) =>
    def sparseBound(i: Option[IndexPattern], default: Int): AffineVector = i match {
      case Some(Affine(as,is,b)) => AffineVector(as,is,b).remap(indices)
      case _ => AffineVector(Array.empty,Nil,default).remap(indices)
    }
    val (min,max) = ctrOf(i) match {
      case Some(ctr) =>
        val min = sparseBound(accessPatternOf(counterStart(ctr)).headOption, Integer.MIN_VALUE)
        val max = sparseBound(accessPatternOf(counterEnd(ctr)).headOption, Integer.MAX_VALUE)
        (min,max)
      case _ => (sparseBound(None,Integer.MIN_VALUE), sparseBound(None, Integer.MAX_VALUE))
    }
    // a0*i0 + ... + aN*iN + b <= iX  |->  -a0*i0 + ... + iX + ... + -aN*iN - b >= 0
    val minA = min.as.zipWithIndex.map{case (x,d) => if (d == iIdx) 1 else -x}
    val minC = -min.b
    // a0*i0 + ... + aN*iN + b >= iX  |->  a0*i0 + ... + -iX + ... + aN*iN + b >= 0
    val maxA = max.as; maxA(iIdx) = -1
    val maxC = max.b
    Seq(minA :+ minC, maxA :+ maxC)
  }.toArray


  /**
    * Returns an approximation of the cost for the given banking strategy.
    */
  def cost(banking: Seq[ModBanking], depth: Int): Int = {
    val totalBanks = banking.map(_.N).product
    depth * totalBanks
  }

  /**
    * A write MAY be seen by a reader if it may precede the reader and the addresses may intersect
    *
    * TODO: Should factor in branches of switches being mutually exclusive here
    */
  def precedingWrites(reader: AccessMatrix, writers: Set[AccessMatrix], domain: IndexDomain): (Seq[AccessMatrix], Seq[AccessMatrix]) = {
    val (before, after) =
      writers.filter{writer => reader.intersectsSpace(writer,domain) && writer.access.mayPrecede(reader.access) }
             .partition{writer => !writer.access.mayFollow(reader.access) }

    (before.toSeq, after.toSeq)
  }

  /**
    * Tests if a given write is entirely overwritten by a subsequent write prior to being read by the reader
    *
    * This occurs when another write w MUST follow that write and w contains ALL of the addresses in the original write
    */
  def isKilled(write: AccessMatrix, others: Seq[AccessMatrix], reader: AccessMatrix, domain: IndexDomain): Boolean = {
    others.exists{w => w.access.mustFollow(write.access, reader.access) && w.containsSpace(write,domain) }
  }

  /**
    * Returns the subset of writers which may be visible to this set of readers
    */
  def reachingWrites(readGroups: Seq[Set[AccessMatrix]], writeGroups: Seq[Set[AccessMatrix]], domain: IndexDomain): Seq[Set[AccessMatrix]] = {
    var remainingWrites: Set[AccessMatrix] = Set.empty
    writeGroups.foreach{grp => remainingWrites ++= grp }
    var reachingWrites: Set[AccessMatrix] = Set.empty
    readGroups.foreach{grp => grp.foreach{reader =>
      val (before, after) = precedingWrites(reader, remainingWrites, domain)

      val reachingBefore = before.zipWithIndex.filterNot{case (wr,i) => isKilled(wr, before.drop(i+1), reader, domain) }.map(_._1)
      val reachingAfter  = after.zipWithIndex.filterNot{case (wr,i) => isKilled(wr, after.drop(i+1) ++ before, reader, domain) }.map(_._1)
      val reaching = reachingBefore ++ reachingAfter
      remainingWrites --= reaching
      reachingWrites ++= reaching
    }}
    writeGroups.map{grp =>
      grp intersect reachingWrites
    }.filterNot(_.isEmpty)
  }

  /**
    * Converts a 1-dimensional partial access with optional address `addr` to an AccessVector
    * Simulates unrolling for random accesses to model each distinct random access as an "iterator"
    */
  def indexPatternToAccessVector(access: Access, addr: Option[Exp[Index]], pattern: IndexPattern, isVecOfs: Boolean): AccessVector = pattern match {
    case Affine(as, is, b)  => AffineVector(as, is, b)
    case _ =>
      addr.foreach{a => lastIndex += a -> pattern.lastIndex }
      if (isVecOfs) addr.foreach{a => inVector += a }
      val (uroll,len) = unrolledRandomAddresses(access, addr)
      RandomVector(addr,uroll,len)
  }

  def accessPatternToCompactMatrix(access: Access, addr: Option[Seq[Exp[Index]]]): Seq[CompactMatrix] = {
    val vectors = accessPatternOf(access.node).zipWithIndex.map { case (pattern, i) =>
      indexPatternToAccessVector(access, addr.map(_.apply(i)), pattern, isVecOfs = false)
    }
    dbg(s"  ${str(access.node)}: Found ${vectors.length} vectors: ")
    vectors.foreach{v => dbg("    " + v.toString) }
    Seq(CompactMatrix(vectors.toArray, access, None))
  }

  /**
    * Return the access pattern of this access as one or more CompactMatrices
    */
  def getAccessVector(access: Access): Seq[CompactMatrix] = access.node match {
    case Def(d: VectorAccess[_]) =>
      Seq.tabulate(d.accessWidth){ vecId =>
        val addr = d.address
        val vectors = accessPatternOf(access.node).zipWithIndex.map { case (pattern, i) =>
          indexPatternToAccessVector(access, addr.map(_.apply(i)), pattern, isVecOfs = i == d.axis)
        }
        CompactMatrix(vectors.toArray, access, Some(vecId))
      }

    case Def(d: EnabledAccess[_]) => accessPatternToCompactMatrix(access, d.address)
    case _                        => accessPatternToCompactMatrix(access, None)
  }

  /**
    * Converts streaming accesses to 1D accesses
    *
    * NOTE: This only works if accesses to this memory are (viewed as) 1D
    */
  protected def getStreamingAccessVectors(readers: Seq[Access], writers: Seq[Access]): (Seq[CompactMatrix], Seq[CompactMatrix]) = {
    if (readers.nonEmpty || writers.nonEmpty) {
      if (dims.length != 1) {
        bug(mem.ctx, "Cannot create streaming accesses on memory with more than one dimension")
        bug(s"${str(mem)}")
        readers.foreach { rd => bug(s"${str(rd.node)}") }
        writers.foreach { wr => bug(s"${str(wr.node)}") }
      }
      def createVector(access: Access): CompactMatrix  = {
        /**
          * Fake the access pattern of this streaming access as being an affine function of the iterators
          * This is done such that all unrolled vectors will be distinct, to avoid tripping up the analyzer
          *
          * Foreach(N par 4){i =>
          *   Foreach(M par 6){j =>
          *     x(6i + j) --> 24i + 6j + [0,24)
          */
        val is = iteratorsBetween(access.ctrl, ctrlOf(mem))
        val ps = is.map{i => parFactorOf(i).toInt }
        val as = Array.tabulate(is.length){i => ps.drop(i + 1).product }
        CompactMatrix(Array(AffineVector(as, is, 0)), access, None)
      }

      val readVectors = readers.map(createVector)
      val writeVectors = writers.map(createVector)
      (readVectors, writeVectors)
    }
    else (Nil,Nil)
  }

  /**
    * Convert read and write accesses to AccessMatrices by simulating unrolling of parallel loops
    */
  def createAccessMatrices(readers: Seq[Access], writers: Seq[Access]): (Seq[AccessMatrix],Seq[AccessMatrix],IndexDomain) = {
    val (addrReaders,streamReaders) = readers.partition(rd => !isAccessWithoutAddress(rd.node))
    val (addrWriters,streamWriters) = writers.partition(wr => !isAccessWithoutAddress(wr.node))
    val (streamReadVectors, streamWriteVectors) = getStreamingAccessVectors(streamReaders,streamWriters)

    val readDenseMatrices = addrReaders.flatMap(getAccessVector) ++ streamReadVectors
    val writeDenseMatrices = addrWriters.flatMap(getAccessVector) ++ streamWriteVectors
    val indices = (readDenseMatrices ++ writeDenseMatrices).flatMap(_.indices).distinct.sortBy{case s:Dyn[_] => s.id; case _ => 0}

    dbg(s"  Found the following compact access matrices: ")
    dbg(s"  Reads: ")
    readDenseMatrices.foreach{m =>
      m.printWithTab("    ")
      accessPatternOf.get(m.access.node).foreach{a => a.zipWithIndex.foreach{case (p,i) => dbg(s"     $i: $p") }}
    }
    dbg("")
    dbg(s"  Writes: ")
    writeDenseMatrices.foreach{m =>
      m.printWithTab("    ")
      accessPatternOf.get(m.access.node).foreach{a => a.zipWithIndex.foreach{case (p,i) => dbg(s"     $i: $p") }}
    }
    dbg("")

    val domain = getIterDomain(indices)
    val readMatrices = readDenseMatrices.flatMap{mat => unroll(mat, indices) }
    val writeMatrices = writeDenseMatrices.flatMap{mat => unroll(mat, indices) }

    dbg(s"  Pseudo-unrolling resulted in the following access matrices: ")
    dbg(s"  Reads: ")
    readMatrices.foreach{m => m.printWithTab("    ") }
    dbg("")
    dbg(s"  Writes:")
    writeMatrices.foreach{m => m.printWithTab("     ") }

    (readMatrices, writeMatrices, domain)
  }

  protected def createGroups(accessors: Seq[AccessMatrix], domain: IndexDomain): Seq[Set[AccessMatrix]] = {
    accessors.groupBy(_.access.ctrl).toSeq.flatMap{case (_,accesses) =>
      val groups = ArrayBuffer[ArrayBuffer[AccessMatrix]]()
      accesses.foreach{access =>
        val grpId = groups.indexWhere{grp => grp.forall{r => !access.intersects(r, domain) } }
        if (grpId != -1) groups(grpId) += access  else groups += ArrayBuffer(access)
      }
      groups
    }.map(_.toSet)
  }

  protected def instanceGroupToMemoryInstance(instance: InstanceGroup): MemoryInstance = {
    val InstanceGroup(rds, wrs, _, mp, banking, depth, _, ports) = instance

    val reads   = rds.flatten.map{_.access}
    val writes  = wrs.flatten.map{_.access}
    val accesses = reads ++ writes
    val ureads  = rds.flatten.map{_.unrolledAccess}.toSet
    val uwrites = wrs.flatten.map{_.unrolledAccess}.toSet

    // A memory is an accumulator if a writer depends on a reader in the same pipe
    // or if this memory is used as an accumulator by a Reduce or MemReduce
    // and at least one of the writers is in the same control node as the reader
    val isImperativeAccum = reads.exists{read => writes.exists{w => readDepsOf(w.node).contains(read.node) }}
    val isReduceAccum = mem match {
      case s: Dyn[_] => s.dependents.exists{
        case Def(e: OpReduce[_])      => e.accum == s && reads.exists{read => writes.exists(_.ctrl == read.ctrl)}
        case Def(e: OpMemReduce[_,_]) => e.accum == s && reads.exists{read => writes.exists(_.ctrl == read.ctrl)}
        case _ => false
      }
      case _ => false
    }
    val isAccum = isImperativeAccum || isReduceAccum

    val muxedPorts = mp match {
      // Metapipelined case: partition accesses based on whether they're n-buffered or time multiplexed w/ buffer
      case Some(parent) =>
        val (nbuf, tmux) = accesses.partition{access => lca(access.ctrl, parent).get == parent }
        val allPorts = List.tabulate(depth){i=>i}.toSet
        (nbuf.map{a => a -> Set(ports(a)) } ++ tmux.map{a => a -> allPorts}).toMap

      // Time-multiplexed case:
      case None =>
        ports.map{case (key, port) => key -> Set(port)}
    }

    MemoryInstance(
      reads = ureads,
      writes = uwrites,
      metapipe = mp,
      banking = banking,
      depth = depth,
      ports = muxedPorts,
      isAccum = isAccum
    )
  }


  protected def bankGroups(readGroups: Seq[Set[AccessMatrix]], writeGroups: Seq[Set[AccessMatrix]], domain: IndexDomain): InstanceGroup = {
    val reads  = readGroups.flatten.map(_.access)
    val writes = writeGroups.flatten.map(_.access)
    val ctrls  = reads.map(_.ctrl).toSet
    val groupWrites = if (readGroups.nonEmpty) reachingWrites(readGroups, writeGroups, domain) else writeGroups
    val groupBanking = strategy.bankAccesses(mem, readGroups, groupWrites, domain)
    // TODO: Multiple metapipe parents should cause backtrack eventually
    val (groupMetapipe, groupPorts) = findMetaPipe(mem, reads, writes)
    val groupDepth = groupPorts.values.max + 1
    val groupCost = cost(groupBanking, groupDepth)
    InstanceGroup(readGroups,groupWrites,ctrls,groupMetapipe,groupBanking,groupDepth,groupCost,groupPorts)
  }

  protected def crossControlCompatible(candidate: Set[Ctrl], ctrls: Set[Ctrl]): Boolean = {
    candidate.forall{c => !ctrls.contains(c) && !ctrls.exists{c2 => lca(c,c2).exists(isParallel) } } &&
    findAllCtrlMetaPipes(mem, candidate ++ ctrls).size <= 1
  }

  /**
    * Greedily banks and merges groups of readers into banked memory instances.
    * NOTE: Total worst case runtime is O(n**2) for n groups of readers.
    * TODO: Add exhaustive implementation after pruning?
    */
  protected def mergeReadGroups(readGroups: Seq[Set[AccessMatrix]], writeGroups: Seq[Set[AccessMatrix]], domain: IndexDomain): Seq[InstanceGroup] = {
    val instances = ArrayBuffer[InstanceGroup]()

    readGroups.foreach{group =>
      val i = bankGroups(Seq(group),writeGroups,domain)

      var instIdx = 0
      var mergedIntoInstance = false
      while (instIdx < instances.length && !mergedIntoInstance) {
        val inst = instances(instIdx)
        // We already separated out the reads which can't be banked in the same controller, so skip groups which
        // contain any of the same read controllers
        if (crossControlCompatible(i.ctrls,inst.ctrls)) {
          val i2 = bankGroups(group +: inst.reads, writeGroups, domain)

          // Merging buffers is only allowed if explicitly enabled (note: this is for PIR)
          if (inst.metapipe.isEmpty || i.metapipe.isEmpty || spatialConfig.enableBufferCoalescing) {
            if (i2.cost < i.cost + inst.cost) {
              instances(instIdx) = i2
              mergedIntoInstance = true
            }
          }
        }
        instIdx += 1
      }
      if (!mergedIntoInstance) instances += i
    }
    instances
  }

  /**
    * Greedily bank and merge writers into banked memory instances.
    * NOTE: This version is only used if there are no readers.
    * TODO: Assumes all writes are reaching for now (to some unknown reader outside of Accel)
    */
  protected def mergeWriteGroups(writeGroups: Seq[Set[AccessMatrix]], domain: IndexDomain): Seq[InstanceGroup] = {
    val instance = bankGroups(Nil, writeGroups, domain)
    Seq(instance)
  }


}
