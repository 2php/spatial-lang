package spatial.codegen.pirgen

import scala.collection.mutable
import scala.util.{Try, Success, Failure}
import scala.reflect.ClassTag

trait MetadataMaps extends MMap { 
  metadatas += this
  def info(n:K):String = { s"${name}($n)=${get(n)}" }
  def reset = map.clear
}

  // Mapping Mem[Struct(Seq(fieldName, T))] -> Seq((fieldName, Mem[T]))
object decomposed extends MOneToOneMap with MetadataMaps {
  type K = Expr
  type V = Seq[(String, Expr)]
}

  // Mapping Mem[T] -> Mem[Struct(Seq(fieldName, T))]
object composed extends MOneToOneMap with MetadataMaps {
  type K = Expr
  type V = Expr 
}

object mappingOf extends MBiOneToManyMap with MetadataMaps {
  type K = Expr
  type V = PIR

  def apply(v:V) = imap(v)
  def to[T](k:K):mutable.Set[T] = map(k).map{ _.asInstanceOf[T] }
  def getOrElseUpdate[T<:V](k:K)(v: => VV):mutable.Set[T] = {
    super.getOrElseUpdate(k)(v).map{_.asInstanceOf[T]}
  }
  def getT[T](k:K)(implicit ev:ClassTag[T]) = get(k).map { _.collect {case x:T => x} }
}

object readerCUsOf extends MOneToOneMap with MetadataMaps {
  type K = Expr
  type V = List[CU]
}

object innerDimOf extends MOneToOneMap with MetadataMaps {
  type K = Expr
  type V = Int
}

object bankOf extends MOneToOneMap with MetadataMaps {
  type K = CUMemory
  type V = Int
}

object instOf extends MOneToOneMap with MetadataMaps {
  type K = CUMemory
  type V = Int
}

object producerOf extends MOneToManyMap with MetadataMaps {
  type K = CUMemory
  type V = (CU, CU) // (writer, producer)
  override def apply(k:K):VV = map.getOrElse(k, mutable.Set[V]())
}

object consumerOf extends MOneToManyMap with MetadataMaps {
  type K = CUMemory
  type V = (CU, CU) // (reader, consumer)
  override def apply(k:K):VV = map.getOrElse(k, mutable.Set[V]())
}
