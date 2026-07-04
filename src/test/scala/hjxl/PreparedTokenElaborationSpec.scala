// See README.md for license details.

package hjxl

import _root_.circt.stage.ChiselStage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.jdk.CollectionConverters._

class PreparedTokenElaborationSpec extends AnyFreeSpec with Matchers {
  private val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 16)
  private val firtoolOpts = Array(
    "-disable-all-randomization",
    "-strip-debug-info",
    "-default-layer-specialization=enable"
  )

  private def emittedSystemVerilog(moduleName: String, generator: => chisel3.RawModule): String = {
    val targetDir = Files.createTempDirectory("hjxl-prepared-token-elaborate-")
    ChiselStage.emitSystemVerilogFile(
      generator,
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
      val module = files.find(_.getFileName.toString == s"$moduleName.sv").getOrElse(fail(s"missing $moduleName.sv"))
      Files.readString(module, StandardCharsets.UTF_8)
    } finally {
      stream.close()
    }
  }

  private def expectTracePorts(moduleName: String, systemVerilog: String): Unit = {
    systemVerilog must include(s"module $moduleName")
    val expectedPorts = Seq(
      "io_trace_ready",
      "io_trace_valid",
      "io_trace_bits_stage",
      "io_trace_bits_group",
      "io_trace_bits_index",
      "io_trace_bits_value",
      "io_traceLast",
      "io_busy",
      "io_overflow"
    )
    for (port <- expectedPorts) {
      withClue(s"missing generated prepared-token port $port in $moduleName") {
        systemVerilog must include(port)
      }
    }
  }

  "prepared token tops expose frame-delimited trace outputs" in {
    expectTracePorts(
      "FramePreparedDcTokenTraceStage",
      emittedSystemVerilog("FramePreparedDcTokenTraceStage", new FramePreparedDcTokenTraceStage(config))
    )
    expectTracePorts(
      "FramePreparedAcMetadataTokenTraceStage",
      emittedSystemVerilog("FramePreparedAcMetadataTokenTraceStage", new FramePreparedAcMetadataTokenTraceStage(config))
    )
    expectTracePorts(
      "FramePreparedAcTokenTraceStage",
      emittedSystemVerilog("FramePreparedAcTokenTraceStage", new FramePreparedAcTokenTraceStage(config))
    )
    expectTracePorts(
      "FramePreparedTokenTraceStage",
      emittedSystemVerilog("FramePreparedTokenTraceStage", new FramePreparedTokenTraceStage(config))
    )
  }
}
