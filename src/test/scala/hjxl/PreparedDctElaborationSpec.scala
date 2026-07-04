// See README.md for license details.

package hjxl

import _root_.circt.stage.ChiselStage
import chisel3._
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.jdk.CollectionConverters._

class PreparedDctElaborationSpec extends AnyFreeSpec with Matchers {
  private val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 8)
  private val firtoolOpts = Array(
    "-disable-all-randomization",
    "-strip-debug-info",
    "-default-layer-specialization=enable"
  )

  private def emittedSystemVerilog(targetDir: Path): Seq[(Path, String)] = {
    val stream = Files.walk(targetDir)
    try {
      stream
        .iterator()
        .asScala
        .filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".sv"))
        .toVector
        .map(path => path -> Files.readString(path, StandardCharsets.UTF_8))
    } finally {
      stream.close()
    }
  }

  private def elaborateAndRead(
      moduleName: String,
      moduleFactory: => RawModule
  ): String = {
    val targetDir = Files.createTempDirectory("hjxl-elaborate-")
    ChiselStage.emitSystemVerilogFile(
      moduleFactory,
      args = Array("--target-dir", targetDir.toString),
      firtoolOpts = firtoolOpts
    )

    val modules = emittedSystemVerilog(targetDir)
    modules.map(_._1.getFileName.toString) must contain(s"$moduleName.sv")
    val text = modules
      .find(_._1.getFileName.toString == s"$moduleName.sv")
      .map(_._2)
      .getOrElse(fail(s"missing emitted SystemVerilog for $moduleName"))
    text must include(s"module $moduleName")
    text
  }

  private def expectPreparedDctPorts(systemVerilog: String): Unit = {
    val expectedPorts = Seq(
      "io_config_xsize",
      "io_config_ysize",
      "io_config_distanceQ8",
      "io_input_ready",
      "io_input_valid",
      "io_input_bits_coefficients_0_0",
      "io_input_bits_coefficients_1_63",
      "io_input_bits_coefficients_2_63",
      "io_input_bits_quant",
      "io_input_bits_scaleQ16",
      "io_input_bits_invQacQ16",
      "io_input_bits_invDcFactorQ16_0",
      "io_input_bits_invDcFactorQ16_1",
      "io_input_bits_invDcFactorQ16_2",
      "io_input_bits_xQmMultiplierQ16",
      "io_input_bits_ytox",
      "io_input_bits_ytob",
      "io_trace_ready",
      "io_trace_valid",
      "io_trace_bits_stage",
      "io_trace_bits_group",
      "io_trace_bits_index",
      "io_trace_bits_value",
      "io_busy",
      "io_overflow"
    )
    for (port <- expectedPorts) {
      withClue(s"missing generated port $port") {
        systemVerilog must include(port)
      }
    }
  }

  "prepared DCT quantization tops emit SystemVerilog" in {
    val quantize = elaborateAndRead(
      "FramePreparedDctOnlyQuantizeTraceStage",
      new FramePreparedDctOnlyQuantizeTraceStage(config)
    )
    expectPreparedDctPorts(quantize)

    val quantizeTokens = elaborateAndRead(
      "FramePreparedDctOnlyQuantizeTokenTraceStage",
      new FramePreparedDctOnlyQuantizeTokenTraceStage(config)
    )
    expectPreparedDctPorts(quantizeTokens)
  }
}
