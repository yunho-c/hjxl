// See README.md for license details.

package hjxl

import _root_.circt.stage.ChiselStage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.jdk.CollectionConverters._

class HjxlAxiStreamElaborationSpec extends AnyFreeSpec with Matchers {
  private val config = HjxlConfig(maxFrameWidth = 8, maxFrameHeight = 8)
  private val firtoolOpts = Array(
    "-disable-all-randomization",
    "-strip-debug-info",
    "-default-layer-specialization=enable"
  )

  private def emittedSystemVerilog(route: Int): String = {
    val targetDir = Files.createTempDirectory("hjxl-axi-stream-elaborate-")
    ChiselStage.emitSystemVerilogFile(
      new HjxlAxiStreamCore(config, traceRoute = route),
      args = Array("--target-dir", targetDir.toString),
      firtoolOpts = firtoolOpts
    )
    val stream = Files.walk(targetDir)
    try {
      val files = stream
        .iterator()
        .asScala
        .filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".sv"))
        .toVector
        .map { path =>
          path.getFileName.toString -> Files.readString(path, StandardCharsets.UTF_8)
        }
      files.map(_._1) must contain("HjxlAxiStreamCore.sv")
      files.map { case (name, text) => s"// $name\n$text" }.mkString("\n")
    } finally {
      stream.close()
    }
  }

  private def expectStreamPorts(systemVerilog: String): Unit = {
    val expectedPorts = Seq(
      "io_config_xsize",
      "io_config_ysize",
      "io_config_distanceQ8",
      "io_clearProtocolError",
      "io_input_ready",
      "io_input_valid",
      "io_input_bits_data",
      "io_input_bits_last",
      "io_trace_ready",
      "io_trace_valid",
      "io_trace_bits_data",
      "io_trace_bits_last",
      "io_busy",
      "io_overflow",
      "io_protocolError"
    )
    for (port <- expectedPorts) {
      withClue(s"missing generated AXI-stream port $port") {
        systemVerilog must include(port)
      }
    }
  }

  "HjxlAxiStreamCore emits the hardware-facing stream shell" in {
    val text = emittedSystemVerilog(HjxlCoreTraceRoute.All)
    text must include("module HjxlAxiStreamCore")
    expectStreamPorts(text)
    text must not include "module FrameAqCflDctOnlyQuantizeTokenTraceStage"
  }

  "HjxlAxiStreamCore focused AC-token route includes the full AC-token scheduler" in {
    val text = emittedSystemVerilog(TraceStage.AcTokens)
    text must include("module HjxlAxiStreamCore")
    expectStreamPorts(text)
    text must include("module FrameAqCflDctOnlyQuantizeTokenTraceStage")
    text must include("module FramePreparedCflDctOnlyQuantizeTokenTraceStage")
    text must include("module CflTileCoefficientEstimator")
    text must include("module AdaptiveInvQacQ16")
    text must not include "module FrameDctOnlyAcTokenTraceStage"
  }
}
