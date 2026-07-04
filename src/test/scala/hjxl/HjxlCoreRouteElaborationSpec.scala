// See README.md for license details.

package hjxl

import _root_.circt.stage.ChiselStage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.jdk.CollectionConverters._

class HjxlCoreRouteElaborationSpec extends AnyFreeSpec with Matchers {
  private val config = HjxlConfig(maxFrameWidth = 8, maxFrameHeight = 8)
  private val firtoolOpts = Array(
    "-disable-all-randomization",
    "-strip-debug-info",
    "-default-layer-specialization=enable"
  )

  private def emittedSystemVerilog(route: Int): Seq[(String, String)] = {
    val targetDir = Files.createTempDirectory("hjxl-core-route-elaborate-")
    ChiselStage.emitSystemVerilogFile(
      new HjxlCore(config, traceRoute = route),
      args = Array("--target-dir", targetDir.toString),
      firtoolOpts = firtoolOpts
    )
    val stream = Files.walk(targetDir)
    try {
      stream
        .iterator()
        .asScala
        .filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".sv"))
        .toVector
        .map { path =>
          path.getFileName.toString -> Files.readString(path, StandardCharsets.UTF_8)
        }
    } finally {
      stream.close()
    }
  }

  private def combinedText(files: Seq[(String, String)]): String =
    files.map { case (name, text) => s"// $name\n$text" }.mkString("\n")

  "HjxlCore focused AC-token route elaborates the full AC-token scheduler" in {
    val files = emittedSystemVerilog(TraceStage.AcTokens)
    files.map(_._1) must contain("HjxlCore.sv")
    val text = combinedText(files)
    text must include("module HjxlCore")
    text must include("module FrameDctOnlyAcTokenTraceStage")
    text must include("io_config_tokenSelect")
    text must include("io_trace_bits_stage")
    text must include("io_trace_bits_value")
    text must include("io_traceLast")
    text must include("io_busy")
    text must include("io_overflow")
  }

  "HjxlCore default all-route elaboration keeps the full AC-token scheduler out" in {
    val files = emittedSystemVerilog(HjxlCoreTraceRoute.All)
    val text = combinedText(files)
    text must include("module HjxlCore")
    text must include("io_traceLast")
    text must include("io_busy")
    text must include("io_overflow")
    text must not include "module FrameDctOnlyAcTokenTraceStage"
  }
}
