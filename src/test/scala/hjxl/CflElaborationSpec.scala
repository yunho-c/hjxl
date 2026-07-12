// See README.md for license details.

package hjxl

import _root_.circt.stage.ChiselStage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class CflElaborationSpec extends AnyFreeSpec with Matchers {
  private val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 8)
  private val firtoolOpts = Array(
    "-disable-all-randomization",
    "-strip-debug-info",
    "-default-layer-specialization=enable"
  )

  "CflTileCoefficientTraceStage emits standalone SystemVerilog ports" in {
    val targetDir = Files.createTempDirectory("hjxl-cfl-elaborate-")
    val systemVerilogPath = targetDir.resolve("CflTileCoefficientTraceStage.sv")
    ChiselStage.emitSystemVerilogFile(
      new CflTileCoefficientTraceStage(config),
      args = Array("--target-dir", targetDir.toString),
      firtoolOpts = firtoolOpts
    )

    Files.exists(systemVerilogPath) mustBe true
    val systemVerilog = Files.readString(systemVerilogPath, StandardCharsets.UTF_8)
    systemVerilog must include("module CflTileCoefficientTraceStage")

    val expectedPorts = Seq(
      "io_input_ready",
      "io_input_valid",
      "io_input_bits_tileIndex",
      "io_input_bits_coefficient_coefficientIndex",
      "io_input_bits_coefficient_yCoeffQ16",
      "io_input_bits_coefficient_xCoeffQ16",
      "io_input_bits_coefficient_bCoeffQ16",
      "io_input_bits_coefficient_last",
      "io_trace_ready",
      "io_trace_valid",
      "io_trace_bits_stage",
      "io_trace_bits_group",
      "io_trace_bits_index",
      "io_trace_bits_value",
      "io_traceLast",
      "io_busy"
    )
    for (port <- expectedPorts) {
      withClue(s"missing generated port $port") {
        systemVerilog must include(port)
      }
    }
  }

  "FramePreparedCflMapTraceStage emits standalone SystemVerilog ports" in {
    val targetDir = Files.createTempDirectory("hjxl-prepared-cfl-elaborate-")
    val systemVerilogPath = targetDir.resolve("FramePreparedCflMapTraceStage.sv")
    ChiselStage.emitSystemVerilogFile(
      new FramePreparedCflMapTraceStage(config),
      args = Array("--target-dir", targetDir.toString),
      firtoolOpts = firtoolOpts
    )

    Files.exists(systemVerilogPath) mustBe true
    val systemVerilog = Files.readString(systemVerilogPath, StandardCharsets.UTF_8)
    systemVerilog must include("module FramePreparedCflMapTraceStage")

    val expectedPorts = Seq(
      "io_config_xsize",
      "io_config_ysize",
      "io_input_ready",
      "io_input_valid",
      "io_input_bits_coefficients_0_0",
      "io_input_bits_coefficients_1_63",
      "io_input_bits_coefficients_2_63",
      "io_input_bits_quant",
      "io_input_bits_scaleQ16",
      "io_input_bits_invQacQ16",
      "io_input_bits_ytox",
      "io_input_bits_ytob",
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
      withClue(s"missing generated port $port") {
        systemVerilog must include(port)
      }
    }
  }

  "FramePreparedCflAcMetadataTokenTraceStage emits standalone SystemVerilog ports" in {
    val targetDir = Files.createTempDirectory("hjxl-prepared-cfl-acmeta-elaborate-")
    val systemVerilogPath = targetDir.resolve("FramePreparedCflAcMetadataTokenTraceStage.sv")
    ChiselStage.emitSystemVerilogFile(
      new FramePreparedCflAcMetadataTokenTraceStage(config),
      args = Array("--target-dir", targetDir.toString),
      firtoolOpts = firtoolOpts
    )

    Files.exists(systemVerilogPath) mustBe true
    val systemVerilog = Files.readString(systemVerilogPath, StandardCharsets.UTF_8)
    systemVerilog must include("module FramePreparedCflAcMetadataTokenTraceStage")

    val expectedPorts = Seq(
      "io_config_xsize",
      "io_config_ysize",
      "io_input_ready",
      "io_input_valid",
      "io_input_bits_coefficients_0_0",
      "io_input_bits_coefficients_1_63",
      "io_input_bits_coefficients_2_63",
      "io_input_bits_quant",
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
      withClue(s"missing generated port $port") {
        systemVerilog must include(port)
      }
    }
  }

  "FramePreparedCflDctOnlyQuantizeTraceStage emits standalone SystemVerilog ports" in {
    val targetDir = Files.createTempDirectory("hjxl-prepared-cfl-quantize-elaborate-")
    val systemVerilogPath = targetDir.resolve("FramePreparedCflDctOnlyQuantizeTraceStage.sv")
    ChiselStage.emitSystemVerilogFile(
      new FramePreparedCflDctOnlyQuantizeTraceStage(config),
      args = Array("--target-dir", targetDir.toString),
      firtoolOpts = firtoolOpts
    )

    Files.exists(systemVerilogPath) mustBe true
    val systemVerilog = Files.readString(systemVerilogPath, StandardCharsets.UTF_8)
    systemVerilog must include("module FramePreparedCflDctOnlyQuantizeTraceStage")

    val expectedPorts = Seq(
      "io_config_xsize",
      "io_config_ysize",
      "io_input_ready",
      "io_input_valid",
      "io_input_bits_coefficients_0_0",
      "io_input_bits_coefficients_1_63",
      "io_input_bits_coefficients_2_63",
      "io_input_bits_quant",
      "io_input_bits_scaleQ16",
      "io_input_bits_invQacQ16",
      "io_input_bits_xQmMultiplierQ16",
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
      withClue(s"missing generated port $port") {
        systemVerilog must include(port)
      }
    }
  }

  "FramePreparedCflDctOnlyQuantizeTokenTraceStage emits standalone SystemVerilog ports" in {
    val targetDir = Files.createTempDirectory("hjxl-prepared-cfl-quantize-tokens-elaborate-")
    val systemVerilogPath = targetDir.resolve("FramePreparedCflDctOnlyQuantizeTokenTraceStage.sv")
    ChiselStage.emitSystemVerilogFile(
      new FramePreparedCflDctOnlyQuantizeTokenTraceStage(config),
      args = Array("--target-dir", targetDir.toString),
      firtoolOpts = firtoolOpts
    )

    Files.exists(systemVerilogPath) mustBe true
    val systemVerilog = Files.readString(systemVerilogPath, StandardCharsets.UTF_8)
    systemVerilog must include("module FramePreparedCflDctOnlyQuantizeTokenTraceStage")

    val expectedPorts = Seq(
      "io_config_xsize",
      "io_config_ysize",
      "io_input_ready",
      "io_input_valid",
      "io_input_bits_coefficients_0_0",
      "io_input_bits_coefficients_1_63",
      "io_input_bits_coefficients_2_63",
      "io_input_bits_quant",
      "io_input_bits_scaleQ16",
      "io_input_bits_invQacQ16",
      "io_input_bits_xQmMultiplierQ16",
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
      withClue(s"missing generated port $port") {
        systemVerilog must include(port)
      }
    }
  }
}
