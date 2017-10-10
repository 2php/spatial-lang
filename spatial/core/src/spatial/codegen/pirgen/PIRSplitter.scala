package spatial.codegen.pirgen

import argon.core._
import spatial.SpatialConfig

import scala.collection.mutable

class PIRSplitter(implicit val codegen:PIRCodegen) extends PIRSplitting with PIRRetiming {
  override val name = "PIR Splitting"
  override val recurse = Always
  var IR = codegen.IR

  val splittingMap = mutable.HashMap[CU, List[CU]]()

  lazy val PCUMax = CUCost(
    sIn=SpatialConfig.sIn_PCU,
    sOut=SpatialConfig.sOut_PCU,
    vIn=SpatialConfig.vIn_PCU,
    vOut=SpatialConfig.vOut_PCU,
    comp=STAGES,
    regsMax = SpatialConfig.regs_PCU
  )
  lazy val PMUMax = MUCost(
    sIn=SpatialConfig.sIn_PMU,
    sOut=SpatialConfig.sOut_PMU,
    vIn=SpatialConfig.vIn_PMU,
    vOut=SpatialConfig.vOut_PMU,
    comp=READ_WRITE,
    regsMax = SpatialConfig.regs_PMU
  )

  override def preprocess[S:Type](b: Block[S]): Block[S] = {
    super.preprocess(b)
  }

  override def postprocess[S:Type](b: Block[S]): Block[S] = {
    splittingMap.values.foreach { _.foreach { cu => swapRef(cu) } }
    mappingOf.transform { case (lhs, cus) =>
      cus.flatMap { 
        case cu:CU => splittingMap.getOrElse(cu, mutable.Set(cu))
        case x => mutable.Set(x)
      }
    }
    super.postprocess(b)
  }

  override def process[S:Type](b: Block[S]) = {
    try {
      visitBlock(b)
    } catch {case e: SplitException =>
      error("Failed splitting")
      error(e.msg)
      sys.exit(-1)
    }
    dbgs(s"\n\n//----------- Finishing PIRSplitter ------------- //")
    dbgs(s"Mapping:")
    splittingMap.foreach { case (cu, cus) =>
      dbgs(s"${cu} -> [${cus.mkString(",")}]")
    }
    b
  }

  override protected def visit(lhs: Sym[_], rhs: Op[_]) {
    mappingOf.getT[CU](lhs).foreach { _.foreach { cu =>
        splittingMap += cu -> split(cu)
      }
    }
  }

  def split(cu: CU): List[CU] = dbgblk(s"split($cu)") {
    if (cu.allStages.nonEmpty) {
      val others = mutable.ArrayBuffer[CU]()
      others ++= splittingMap.values.flatten

      val cus = splitCU(cu, PCUMax, PMUMax, others)
      retime(cus, others)

      cus.foreach{cu =>
        val cost = getUtil(cu, others)
        others += cu
      }

      cus
    }
    else List(cu)
  }

  def getParent(cus:List[CU]):CU = {
    if (cus.size==1) return cus.head
    val parents = cus.filter { _.style == StreamCU }
    assert(parents.size==1)
    parents.head
  }

  def swapToParent(cu:CU) = splittingMap.get(cu).map(getParent).getOrElse(cu)

  def swapRef(cu:CU): Unit = dbgblk(s"swapRef($cu)") {
    cu.cchains.foreach{cchain => swapCU_cchain(cchain) }
    cu.parent = cu.parent.map{parent => swapToParent(parent) }
    cu.mems.foreach{mem => swapCU_mem(mem) }
    cu.allStages.foreach{stage => swapCU_stage(stage) }

    def swapCU_cchain(cchain: CUCChain): Unit = cchain match {
      case cc: CChainCopy => cc.owner = swapToParent(cc.owner)
      case _ => // No action
    }

    def swapCU_stage(stage:Stage) = {
      stage match {
        case stage:ReduceStage => stage.accParent = swapToParent(stage.accParent)
        case stage =>
      }
      stage.inputMems.foreach(swapCU_reg)
    }

    def swapCU_reg(reg: LocalComponent): Unit = reg match {
      case CounterReg(cc,i,iter) => swapCU_cchain(cc)
      case ValidReg(cc,i,valid) => swapCU_cchain(cc)
      case _ =>
    }

    def swapCU_mem(mem: CUMemory) {
      mem.readAddr.foreach{case reg:LocalComponent => swapCU_reg(reg); case _ => }
      mem.writeAddr.foreach{case reg:LocalComponent => swapCU_reg(reg); case _ => }
      consumerOf(mem) =  consumerOf(mem).flatMap { case (reader, consumer) => 
        val newReaders = mem.mode match {
          case SRAMMode =>
            val addrInputs = globalInputs(mem)
            dbgs(s"addrInputs($mem) = $addrInputs")
            splittingMap(reader).filter { reader =>
              val addrOutputs = globalOutputs(reader)
              dbgs(s"addrOutputs($reader) = $addrOutputs")
              (addrInputs intersect addrOutputs).nonEmpty
            }
          case _ => List(cu) // LocalMem
        }
        val newConsumer = swapToParent(consumer)
        newReaders.map { reader => (reader, newConsumer) }
      }
      producerOf(mem) =  producerOf(mem).flatMap { case (writer, producer) => 
        val dataInputs = globalInputs(mem.writeAddr)
        dbgs(s"dataInputs($mem) = $dataInputs")
        val newWriters = splittingMap(writer).filter { writer =>
          val dataOutputs = globalOutputs(writer)
          dbgs(s"dataOutputs($writer) = $dataOutputs")
          (dataOutputs intersect dataInputs).nonEmpty
        }
        val newProducer = swapToParent(producer)
        newWriters.map { writer => (writer, newProducer) }
      }
    }
  }

}
