package spatial

import java.io._

// TODO: Asynchronous error response
// TODO: Should give an iterator rather than the reader directly
case class Subproc(args: String*)(react: (String,BufferedReader) => Option[String]) {
  private var reader: BufferedReader = _
  private var writer: BufferedWriter = _
  private var logger: BufferedReader = _
  private var p: Process = _

  private def println(x: String): Unit = {
    writer.write(x)
    writer.newLine()
    writer.flush()
  }

  def run(dir: Option[String] = None): Unit = if (p eq null) {
    val pb = new ProcessBuilder(args:_*)
    dir.foreach{d => pb.directory(new File(d)) }
    p = pb.start()
    reader = new BufferedReader(new InputStreamReader(p.getInputStream))
    writer = new BufferedWriter(new OutputStreamWriter(p.getOutputStream))
    logger = new BufferedReader(new InputStreamReader(p.getErrorStream))
  } else {
    throw new Exception(s"Cannot run process $args while it is already running.")
  }

  def block(dir: Option[String] = None): Int = {
    if (p eq null) run(dir)
    while (true) {
      // Otherwise react to the stdout of the subprocess
      val input = reader.readLine()
      if (input ne null) {
        val response = react(input,reader)
        response.foreach{r => println(r) }
      }
    }
    p.exitValue()
  }

}
