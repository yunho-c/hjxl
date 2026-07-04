// See README.md for license details.

package hjxl

import _root_.circt.stage.ChiselStage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.jdk.CollectionConverters._

class HjxlKv260PreparedDctTopElaborationSpec extends AnyFreeSpec with Matchers {
  private val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 8)
  private val firtoolOpts = Array(
    "-disable-all-randomization",
    "-strip-debug-info",
    "-default-layer-specialization=enable"
  )

  private def emittedSystemVerilog: String = {
    val targetDir = Files.createTempDirectory("hjxl-kv260-prepared-dct-top-elaborate-")
    ChiselStage.emitSystemVerilogFile(
      new HjxlKv260PreparedDctTop(config),
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
      files.map(_._1) must contain("HjxlKv260PreparedDctTop.sv")
      files.map { case (name, text) => s"// $name\n$text" }.mkString("\n")
    } finally {
      stream.close()
    }
  }

  "HjxlKv260PreparedDctTop emits flat Vivado-style AXI ports" in {
    val text = emittedSystemVerilog
    text must include("module HjxlKv260PreparedDctTop")
    for (
      port <- Seq(
        "ap_clk",
        "ap_rst_n",
        "s_axi_control_awaddr",
        "s_axi_control_awvalid",
        "s_axi_control_awready",
        "s_axi_control_wdata",
        "s_axi_control_wstrb",
        "s_axi_control_wvalid",
        "s_axi_control_wready",
        "s_axi_control_bresp",
        "s_axi_control_bvalid",
        "s_axi_control_bready",
        "s_axi_control_araddr",
        "s_axi_control_arvalid",
        "s_axi_control_arready",
        "s_axi_control_rdata",
        "s_axi_control_rresp",
        "s_axi_control_rvalid",
        "s_axi_control_rready",
        "s_axis_input_tdata",
        "s_axis_input_tvalid",
        "s_axis_input_tready",
        "s_axis_input_tlast",
        "m_axis_trace_tdata",
        "m_axis_trace_tkeep",
        "m_axis_trace_tvalid",
        "m_axis_trace_tready",
        "m_axis_trace_tlast",
        "busy",
        "overflow",
        "protocol_error"
      )
    ) {
      withClue(s"missing generated KV260 top port $port") {
        text must include(port)
      }
    }
    text must include("output [127:0] m_axis_trace_tdata")
    text must include("output [15:0]")
    text must include("module HjxlPreparedDctAxiLiteStreamCore")
  }
}
