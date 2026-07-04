// See README.md for license details.

package hjxl

import _root_.circt.stage.ChiselStage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.jdk.CollectionConverters._

class HjxlAxiLiteStreamElaborationSpec extends AnyFreeSpec with Matchers {
  private val config = HjxlConfig(maxFrameWidth = 8, maxFrameHeight = 8)
  private val firtoolOpts = Array(
    "-disable-all-randomization",
    "-strip-debug-info",
    "-default-layer-specialization=enable"
  )

  private def emittedSystemVerilog(route: Int): String = {
    val targetDir = Files.createTempDirectory("hjxl-axi-lite-stream-elaborate-")
    ChiselStage.emitSystemVerilogFile(
      new HjxlAxiLiteStreamCore(config, traceRoute = route),
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
      files.map(_._1) must contain("HjxlAxiLiteStreamCore.sv")
      files.map { case (name, text) => s"// $name\n$text" }.mkString("\n")
    } finally {
      stream.close()
    }
  }

  private def expectAxiLiteStreamPorts(systemVerilog: String): Unit = {
    val expectedPorts = Seq(
      "io_control_aw_ready",
      "io_control_aw_valid",
      "io_control_aw_bits_addr",
      "io_control_w_ready",
      "io_control_w_valid",
      "io_control_w_bits_data",
      "io_control_w_bits_strb",
      "io_control_b_ready",
      "io_control_b_valid",
      "io_control_b_bits_resp",
      "io_control_ar_ready",
      "io_control_ar_valid",
      "io_control_ar_bits_addr",
      "io_control_r_ready",
      "io_control_r_valid",
      "io_control_r_bits_data",
      "io_control_r_bits_resp",
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
      "io_protocolError",
      "io_unsupportedDistance"
    )
    for (port <- expectedPorts) {
      withClue(s"missing generated AXI-Lite stream port $port") {
        systemVerilog must include(port)
      }
    }
  }

  "HjxlAxiLiteStreamCore emits AXI-Lite control and AXI-stream data ports" in {
    val text = emittedSystemVerilog(HjxlCoreTraceRoute.All)
    text must include("module HjxlAxiLiteStreamCore")
    expectAxiLiteStreamPorts(text)
    text must not include "module FrameDctOnlyAcTokenTraceStage"
  }

  "HjxlAxiLiteStreamCore focused AC-token route includes the full AC-token scheduler" in {
    val text = emittedSystemVerilog(TraceStage.AcTokens)
    text must include("module HjxlAxiLiteStreamCore")
    expectAxiLiteStreamPorts(text)
    text must include("module FrameDctOnlyAcTokenTraceStage")
  }
}
