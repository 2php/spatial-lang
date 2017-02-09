package spatial.api

import argon.core.Staging
import argon.ops.{ArrayApi, VoidExp}
import org.virtualized.virtualize
import spatial.SpatialApi

trait StagedUtilApi extends StagedUtilExp with ArrayApi {
  this: SpatialApi =>

  @virtualize
  def printArray[T:Staged](array: Array[T], header: String = "")(implicit ctx: SrcCtx): Void = {
    println(header)
    (0 until array.length) foreach { i => print( textify(array(i)) + " ") } // Have to use textify here...
    println("")
  }

  implicit def insert_void[T:Staged](x: T)(implicit ctx: SrcCtx): Void = unit2void(())

}
trait StagedUtilExp extends Staging with VoidExp
