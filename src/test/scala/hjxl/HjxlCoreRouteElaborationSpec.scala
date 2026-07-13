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
    text must include("module FrameAqContrastTraceStage")
    text must include("module FrameAqDctOnlyQuantizeTraceStage")
    text must include("module FrameAqDctOnlyAcMetadataTokenTraceStage")
    text must include("module FrameAqDctOnlyBlockStage")
    text must include("module AdaptiveInvQacQ16")
    text must not include "module FrameDctOnlyAcTokenTraceStage"
    text must not include "module FrameAqStrategyMaskTraceStage"
    text must not include "module FrameAqHfModulationTraceStage"
    text must not include "module FrameAqColorModulationTraceStage"
    text must not include "module FrameAqGammaModulationTraceStage"
  }

  "HjxlCore focused quantized route uses one adaptive block source" in {
    val files = emittedSystemVerilog(TraceStage.QuantizedAc)
    val text = combinedText(files)
    text must include("module HjxlCore")
    text must include("module FrameAqDctOnlyQuantizeTraceStage")
    text must include("module FrameAqDctOnlyBlockStage")
    text must include("module FrameAqFinalMapPipeline")
    text must include("module AdaptiveInvQacQ16")
    text must include("module FramePreparedDctOnlyQuantizeTraceStage")
    text must include("module Dct8x8Approx")
    text must not include "module FrameDctOnlyQuantizeTraceStage"
    text must not include "module FrameAqDctOnlyAcMetadataTokenTraceStage"
    val converterInstances = """RgbToXybApprox\s+\w+\s*\(""".r.findAllMatchIn(text).length
    converterInstances mustBe 1
    val dctInstances = """Dct8x8Approx\s+\w+\s*\(""".r.findAllMatchIn(text).length
    dctInstances mustBe 3
  }

  "HjxlCore focused AC-metadata route avoids DCT and reciprocal hardware" in {
    val files = emittedSystemVerilog(TraceStage.AcMetadataTokens)
    val text = combinedText(files)
    text must include("module HjxlCore")
    text must include("module FrameAqDctOnlyAcMetadataTokenTraceStage")
    text must include("module FrameAqFinalMapPipeline")
    text must include("module AqMapToRawQuant")
    text must include("module FramePreparedAcMetadataTokenTraceStage")
    text must not include "module FrameAqDctOnlyBlockStage"
    text must not include "module AdaptiveInvQacQ16"
    text must not include "module Dct8x8Approx"
    val converterInstances = """RgbToXybApprox\s+\w+\s*\(""".r.findAllMatchIn(text).length
    converterInstances mustBe 1
  }

  "HjxlCore focused AQ-contrast route elaborates only the image contrast scheduler" in {
    val files = emittedSystemVerilog(TraceStage.AqContrast)
    val text = combinedText(files)
    text must include("module HjxlCore")
    text must include("module FrameAqContrastTraceStage")
    text must include("module AqContrastPixel")
    text must include("module UnsignedIntegerSquareRoot")
    text must include("io_trace_bits_stage")
    text must include("io_traceLast")
    text must not include "module FrameDct8x8TraceStage"
    text must not include "module FrameRawQuantFieldTraceStage"
  }

  "HjxlCore focused AQ fuzzy-erosion route composes contrast and erosion schedulers" in {
    val files = emittedSystemVerilog(TraceStage.AqFuzzyErosion)
    val text = combinedText(files)
    text must include("module HjxlCore")
    text must include("module FrameAqFuzzyErosionTraceStage")
    text must include("module FramePreparedAqFuzzyErosionTraceStage")
    text must include("module FrameAqContrastTraceStage")
    text must include("module AqFuzzyErosionSample")
    text must include("io_traceLast")
    text must not include "module FrameDct8x8TraceStage"
    text must not include "module FrameAcStrategyTraceStage"
  }

  "HjxlCore focused AQ strategy-mask route composes the full pre-strategy chain" in {
    val files = emittedSystemVerilog(TraceStage.AqStrategyMask)
    val text = combinedText(files)
    text must include("module HjxlCore")
    text must include("module FrameAqStrategyMaskTraceStage")
    text must include("module FramePreparedAqStrategyMaskTraceStage")
    text must include("module AqStrategyMaskReciprocal")
    text must include("module FrameAqFuzzyErosionTraceStage")
    text must include("module FrameAqContrastTraceStage")
    text must include("io_traceLast")
    text must not include "module FrameDct8x8TraceStage"
    text must not include "module FrameAcStrategyTraceStage"
  }

  "HjxlCore focused AQ nonlinear-mask route composes the modulation-seed chain" in {
    val files = emittedSystemVerilog(TraceStage.AqNonlinearMask)
    val text = combinedText(files)
    text must include("module HjxlCore")
    text must include("module FrameAqNonlinearMaskTraceStage")
    text must include("module FramePreparedAqNonlinearMaskTraceStage")
    text must include("module AqNonlinearMaskEvaluator")
    text must include("module AqNonlinearMaskRoundedDivider")
    text must include("module FrameAqFuzzyErosionTraceStage")
    text must include("module FrameAqContrastTraceStage")
    text must include("io_traceLast")
    text must not include "module FrameDct8x8TraceStage"
    text must not include "module FrameAcStrategyTraceStage"
  }

  "HjxlCore focused AQ HF-modulation route composes the Y-detail chain" in {
    val files = emittedSystemVerilog(TraceStage.AqHfModulation)
    val text = combinedText(files)
    text must include("module HjxlCore")
    text must include("module FrameAqHfModulationTraceStage")
    text must include("module FrameAqModulationBlockStage")
    text must include("module AqHfModulationBlock")
    text must include("module FrameAqNonlinearMaskTraceStage")
    text must include("module AqNonlinearMaskEvaluator")
    text must include("module FrameAqContrastTraceStage")
    text must include("io_traceLast")
    text must not include "module FrameDct8x8TraceStage"
    text must not include "module FrameAcStrategyTraceStage"
  }

  "HjxlCore focused AQ color-modulation route composes one shared XYB block chain" in {
    val files = emittedSystemVerilog(TraceStage.AqColorModulation)
    val text = combinedText(files)
    text must include("module HjxlCore")
    text must include("module FrameAqColorModulationTraceStage")
    text must include("module FrameAqModulationBlockStage")
    text must include("module AqHfModulationBlock")
    text must include("module AqColorModulationBlock")
    text must include("module FrameAqNonlinearMaskTraceStage")
    text must include("module AqNonlinearMaskEvaluator")
    text must include("module FrameAqContrastTraceStage")
    text must include("io_traceLast")
    text must not include "module FrameDct8x8TraceStage"
    text must not include "module FrameAcStrategyTraceStage"
    val converterInstances = """RgbToXybApprox\s+\w+\s*\(""".r
      .findAllMatchIn(text)
      .length
    converterInstances mustBe 1
  }

  "HjxlCore focused AQ gamma-modulation route composes one shared cumulative chain" in {
    val files = emittedSystemVerilog(TraceStage.AqGammaModulation)
    val text = combinedText(files)
    text must include("module HjxlCore")
    text must include("module FrameAqGammaModulationTraceStage")
    text must include("module FrameAqModulationBlockStage")
    text must include("module AqHfColorModulationBlockPipeline")
    text must include("module AqHfModulationBlock")
    text must include("module AqColorModulationBlock")
    text must include("module AqGammaModulationBlock")
    text must include("module AqInverseGammaRatioQ20")
    text must include("module AqFastLog2Q20")
    text must include("module FrameAqNonlinearMaskTraceStage")
    text must include("module FrameAqContrastTraceStage")
    text must include("io_traceLast")
    text must not include "module FrameDct8x8TraceStage"
    text must not include "module FrameAcStrategyTraceStage"
    val converterInstances = """RgbToXybApprox\s+\w+\s*\(""".r
      .findAllMatchIn(text)
      .length
    converterInstances mustBe 1
  }

  "HjxlCore focused AQ final-map route completes one shared cumulative chain" in {
    val files = emittedSystemVerilog(TraceStage.AqFinalMap)
    val text = combinedText(files)
    text must include("module HjxlCore")
    text must include("module FrameAqFinalMapTraceStage")
    text must include("module FrameAqFinalMapPipeline")
    text must include("module AqHfColorGammaModulationBlockPipeline")
    text must include("module AqFinalModulationBlock")
    text must include("module AqFastExpQ24")
    text must include("module DistanceParamsLookup")
    text must include("io_traceLast")
    text must not include "module FrameDct8x8TraceStage"
    text must not include "module FrameAcStrategyTraceStage"
    val converterInstances = """RgbToXybApprox\s+\w+\s*\(""".r
      .findAllMatchIn(text)
      .length
    converterInstances mustBe 1
  }

  "HjxlCore focused raw-quant route selects adaptive AQ with an explicit fixed override" in {
    val files = emittedSystemVerilog(TraceStage.RawQuantField)
    val text = combinedText(files)
    text must include("module HjxlCore")
    text must include("module FrameRawQuantFieldTraceStage")
    text must include("module FrameFixedRawQuantFieldTraceStage")
    text must include("module FrameAqRawQuantTraceStage")
    text must include("module FrameAqFinalMapPipeline")
    text must include("module AqMapToRawQuant")
    text must include("io_trace_bits_stage")
    text must include("io_traceLast")
    text must not include "module FrameAcStrategyTraceStage"
  }

  "HjxlCore focused CFL-map routes elaborate the fixed CFL map scheduler" in {
    for (route <- Seq(TraceStage.YtoxMap, TraceStage.YtobMap)) {
      val files = emittedSystemVerilog(route)
      val text = combinedText(files)
      text must include("module HjxlCore")
      text must include("module FrameCflMapTraceStage")
      text must include("io_trace_bits_stage")
      text must include("io_traceLast")
      text must not include "module FrameAcStrategyTraceStage"
      text must not include "module FrameRawQuantFieldTraceStage"
    }
  }
}
