package spatial.codegen.pirgen

import spatial.SpatialConfig

import argon.codegen.FileGen
import argon.core.Config
import argon.core._

import scala.language.postfixOps
import scala.sys.process._

trait PIRFileGen extends FileGen {

  override protected def emitMain[S:Type](b: Block[S]): Unit = emitBlock(b)

  override protected def emitFileHeader() {
    emit("import pir._")
    emit("import pir.node._")
    emit("import arch._")
    emit("import pirc.enums._")
    emit("")
    open(s"""object ${Config.name} extends PIRApp {""")
    //emit(s"""override val arch = SN_4x4""")
    open(s"""def main(top:Top) = {""")

    super.emitFileHeader()
  }

  override protected def emitFileFooter() {
    emit(s"")
    close("}")
    close("}")

    super.emitFileFooter()
  }

  override protected def process[S:Type](b: Block[S]): Block[S] = {
    super.process(b)
    //TODO: Cannot treat this as a dependency because postprocess is called before stream is closed
    if (sys.env.get("PIR_HOME").isDefined && sys.env("PIR_HOME") != "") {
      // what should be the cleaner way of doing this?
      val dir = SpatialConfig.pirsrc 
      var cmd = s"mkdir -p $dir"
      info(cmd)
      cmd.!

      cmd = s"cp ${Config.genDir}/pir/main.scala $dir/${Config.name}.scala"
      info(cmd)
      cmd.!
    }
    else {
      warn("Set PIR_HOME environment variable to automatically copy app")
    }
    b
  }

}
