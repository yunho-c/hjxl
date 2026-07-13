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
    text must not include "module FrameDctOnlyAcTokenTraceStage"
    text must not include "module FrameAqFuzzyErosionTraceStage"
    text must not include "module FrameAqStrategyMaskTraceStage"
    text must not include "module FrameAqNonlinearMaskTraceStage"
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

  "HjxlCore focused raw-quant route elaborates the raw quant-field scheduler" in {
    val files = emittedSystemVerilog(TraceStage.RawQuantField)
    val text = combinedText(files)
    text must include("module HjxlCore")
    text must include("module FrameRawQuantFieldTraceStage")
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
