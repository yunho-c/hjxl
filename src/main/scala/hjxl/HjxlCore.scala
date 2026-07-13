// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

object HjxlCoreTraceRoute {
  val All = -1
}

class HjxlCore(c: HjxlConfig = HjxlConfig(), traceRoute: Int = HjxlCoreTraceRoute.All) extends Module {
  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  private def includes(stage: Int): Boolean =
    traceRoute == HjxlCoreTraceRoute.All || traceRoute == stage

  private val includeInput = includes(TraceStage.InputPadded)
  private val includeXyb = includes(TraceStage.Xyb)
  private val includeDct = includes(TraceStage.RawDct8x8)
  private val includeRawQuant = traceRoute == TraceStage.RawQuantField
  private val includeYtoxMap = includes(TraceStage.YtoxMap)
  private val includeYtobMap = includes(TraceStage.YtobMap)
  private val includeQuant = includes(TraceStage.QuantizedAc)
  private val includeDcToken = includes(TraceStage.DcTokens)
  private val includeAcMetadataToken = includes(TraceStage.AcMetadataTokens)
  private val includeAcToken = traceRoute == TraceStage.AcTokens
  private val includeAcStrategy = includes(TraceStage.AcStrategy)
  private val includeAqContrast = includes(TraceStage.AqContrast)
  private val includeAqFuzzyErosion = traceRoute == TraceStage.AqFuzzyErosion
  private val includeAqStrategyMask = traceRoute == TraceStage.AqStrategyMask
  private val includeAqNonlinearMask = traceRoute == TraceStage.AqNonlinearMask
  private val includeAqHfModulation = traceRoute == TraceStage.AqHfModulation
  private val includeAqColorModulation = traceRoute == TraceStage.AqColorModulation
  private val includeAqGammaModulation = traceRoute == TraceStage.AqGammaModulation
  private val includeAqFinalMap = traceRoute == TraceStage.AqFinalMap

  val inputTrace = if (includeInput) Some(Module(new FramePadTraceStage(c))) else None
  val xybTrace = if (includeXyb) Some(Module(new FrameXybTraceStage(c))) else None
  val dctTrace = if (includeDct) Some(Module(new FrameDct8x8TraceStage(c))) else None
  val rawQuantTrace = if (includeRawQuant) Some(Module(new FrameRawQuantFieldTraceStage(c))) else None
  val ytoxTrace = if (includeYtoxMap) Some(Module(new FrameCflMapTraceStage(c, TraceStage.YtoxMap))) else None
  val ytobTrace = if (includeYtobMap) Some(Module(new FrameCflMapTraceStage(c, TraceStage.YtobMap))) else None
  val quantTrace = if (includeQuant) Some(Module(new FrameDctOnlyQuantizeTraceStage(c))) else None
  val dcTokenTrace = if (includeDcToken) Some(Module(new FrameDctOnlyDcTokenTraceStage(c))) else None
  val acMetadataTokenTrace =
    if (includeAcMetadataToken) Some(Module(new FrameDctOnlyAcMetadataTokenTraceStage(c))) else None
  val acTokenTrace = if (includeAcToken) Some(Module(new FrameDctOnlyAcTokenTraceStage(c))) else None
  val acStrategyTrace = if (includeAcStrategy) Some(Module(new FrameAcStrategyTraceStage(c))) else None
  val aqContrastTrace = if (includeAqContrast) Some(Module(new FrameAqContrastTraceStage(c))) else None
  val aqFuzzyErosionTrace =
    if (includeAqFuzzyErosion) Some(Module(new FrameAqFuzzyErosionTraceStage(c))) else None
  val aqStrategyMaskTrace =
    if (includeAqStrategyMask) Some(Module(new FrameAqStrategyMaskTraceStage(c))) else None
  val aqNonlinearMaskTrace =
    if (includeAqNonlinearMask) Some(Module(new FrameAqNonlinearMaskTraceStage(c))) else None
  val aqHfModulationTrace =
    if (includeAqHfModulation) Some(Module(new FrameAqHfModulationTraceStage(c))) else None
  val aqColorModulationTrace =
    if (includeAqColorModulation) Some(Module(new FrameAqColorModulationTraceStage(c))) else None
  val aqGammaModulationTrace =
    if (includeAqGammaModulation) Some(Module(new FrameAqGammaModulationTraceStage(c))) else None
  val aqFinalMapTrace =
    if (includeAqFinalMap) Some(Module(new FrameAqFinalMapTraceStage(c))) else None

  val useDcTokenTrace =
    if (includeDcToken) {
      io.config.enableDct && io.config.enableQuant && io.config.enableTokenize &&
        io.config.tokenSelect === TokenTraceSelect.Dc.U
    } else {
      false.B
    }
  val useAcMetadataTokenTrace =
    if (includeAcMetadataToken) {
      io.config.enableQuant && io.config.enableTokenize &&
        io.config.tokenSelect === TokenTraceSelect.AcMetadata.U
    } else {
      false.B
    }
  val useAcTokenTrace =
    if (includeAcToken) {
      io.config.enableDct && io.config.enableQuant && io.config.enableTokenize &&
        io.config.tokenSelect === TokenTraceSelect.AcTokens.U
    } else {
      false.B
    }
  val useQuantTrace =
    if (includeQuant) {
      io.config.enableDct && io.config.enableQuant && !useDcTokenTrace &&
        !useAcMetadataTokenTrace && !useAcTokenTrace
    } else {
      false.B
    }
  val useDctTrace =
    if (includeDct) {
      io.config.enableDct && !useQuantTrace && !useDcTokenTrace &&
        !useAcMetadataTokenTrace && !useAcTokenTrace
    } else {
      false.B
    }
  val useAqContrastTrace =
    if (includeAqContrast) {
      io.config.enableXyb && io.config.enableQuant && !io.config.enableDct &&
        !io.config.enableTokenize && io.config.tokenSelect === TokenTraceSelect.AqContrast.U
    } else {
      false.B
    }
  val useAqFuzzyErosionTrace =
    if (includeAqFuzzyErosion) {
      io.config.enableXyb && io.config.enableQuant && !io.config.enableDct &&
        !io.config.enableTokenize && io.config.tokenSelect === TokenTraceSelect.AqContrast.U
    } else {
      false.B
    }
  val useAqStrategyMaskTrace =
    if (includeAqStrategyMask) {
      io.config.enableXyb && io.config.enableQuant && !io.config.enableDct &&
        !io.config.enableTokenize && io.config.tokenSelect === TokenTraceSelect.AqContrast.U
    } else {
      false.B
    }
  val useAqNonlinearMaskTrace =
    if (includeAqNonlinearMask) {
      io.config.enableXyb && io.config.enableQuant && !io.config.enableDct &&
        !io.config.enableTokenize && io.config.tokenSelect === TokenTraceSelect.AqContrast.U
    } else {
      false.B
    }
  val useAqHfModulationTrace =
    if (includeAqHfModulation) {
      io.config.enableXyb && io.config.enableQuant && !io.config.enableDct &&
        !io.config.enableTokenize && io.config.tokenSelect === TokenTraceSelect.AqContrast.U
    } else {
      false.B
    }
  val useAqColorModulationTrace =
    if (includeAqColorModulation) {
      io.config.enableXyb && io.config.enableQuant && !io.config.enableDct &&
        !io.config.enableTokenize && io.config.tokenSelect === TokenTraceSelect.AqContrast.U
    } else {
      false.B
    }
  val useAqGammaModulationTrace =
    if (includeAqGammaModulation) {
      io.config.enableXyb && io.config.enableQuant && !io.config.enableDct &&
        !io.config.enableTokenize && io.config.tokenSelect === TokenTraceSelect.AqContrast.U
    } else {
      false.B
    }
  val useAqFinalMapTrace =
    if (includeAqFinalMap) {
      io.config.enableXyb && io.config.enableQuant && !io.config.enableDct &&
        !io.config.enableTokenize && io.config.tokenSelect === TokenTraceSelect.AqContrast.U
    } else {
      false.B
    }
  val useAcStrategyTrace =
    if (includeAcStrategy) {
      io.config.enableQuant && !io.config.enableTokenize && !io.config.enableDct &&
        !useAqContrastTrace
    } else {
      false.B
    }
  val useRawQuantTrace =
    if (includeRawQuant) {
      io.config.enableQuant && !io.config.enableTokenize && !io.config.enableDct &&
        !useAqContrastTrace && !useAcStrategyTrace
    } else {
      false.B
    }
  val useYtoxTrace =
    if (includeYtoxMap) {
      io.config.enableQuant && !io.config.enableTokenize && !io.config.enableDct &&
        !useAqContrastTrace && !useAcStrategyTrace && !useRawQuantTrace
    } else {
      false.B
    }
  val useYtobTrace =
    if (includeYtobMap) {
      io.config.enableQuant && !io.config.enableTokenize && !io.config.enableDct &&
        !useAqContrastTrace && !useAcStrategyTrace && !useRawQuantTrace && !useYtoxTrace
    } else {
      false.B
    }
  val useXybTrace =
    if (includeXyb) {
      io.config.enableXyb && !useDctTrace && !useQuantTrace && !useDcTokenTrace &&
        !useAcMetadataTokenTrace && !useAcTokenTrace && !useAcStrategyTrace &&
        !useRawQuantTrace && !useYtoxTrace && !useYtobTrace && !useAqContrastTrace
    } else {
      false.B
    }
  val useInputTrace =
    if (includeInput) {
      !useXybTrace && !useDctTrace && !useQuantTrace && !useDcTokenTrace &&
        !useAcMetadataTokenTrace && !useAcTokenTrace && !useAcStrategyTrace &&
        !useRawQuantTrace && !useYtoxTrace && !useYtobTrace && !useAqContrastTrace
    } else {
      false.B
    }

  inputTrace.foreach { stage =>
    stage.io.config := io.config
    stage.io.input.bits := io.input.bits
    stage.io.input.valid := io.input.valid && useInputTrace
    stage.io.trace.ready := io.trace.ready && useInputTrace
  }
  xybTrace.foreach { stage =>
    stage.io.config := io.config
    stage.io.input.bits := io.input.bits
    stage.io.input.valid := io.input.valid && useXybTrace
    stage.io.trace.ready := io.trace.ready && useXybTrace
  }
  dctTrace.foreach { stage =>
    stage.io.config := io.config
    stage.io.input.bits := io.input.bits
    stage.io.input.valid := io.input.valid && useDctTrace
    stage.io.trace.ready := io.trace.ready && useDctTrace
  }
  rawQuantTrace.foreach { stage =>
    stage.io.config := io.config
    stage.io.input.bits := io.input.bits
    stage.io.input.valid := io.input.valid && useRawQuantTrace
    stage.io.trace.ready := io.trace.ready && useRawQuantTrace
  }
  ytoxTrace.foreach { stage =>
    stage.io.config := io.config
    stage.io.input.bits := io.input.bits
    stage.io.input.valid := io.input.valid && useYtoxTrace
    stage.io.trace.ready := io.trace.ready && useYtoxTrace
  }
  ytobTrace.foreach { stage =>
    stage.io.config := io.config
    stage.io.input.bits := io.input.bits
    stage.io.input.valid := io.input.valid && useYtobTrace
    stage.io.trace.ready := io.trace.ready && useYtobTrace
  }
  quantTrace.foreach { stage =>
    stage.io.config := io.config
    stage.io.input.bits := io.input.bits
    stage.io.input.valid := io.input.valid && useQuantTrace
    stage.io.trace.ready := io.trace.ready && useQuantTrace
  }
  dcTokenTrace.foreach { stage =>
    stage.io.config := io.config
    stage.io.input.bits := io.input.bits
    stage.io.input.valid := io.input.valid && useDcTokenTrace
    stage.io.trace.ready := io.trace.ready && useDcTokenTrace
  }
  acMetadataTokenTrace.foreach { stage =>
    stage.io.config := io.config
    stage.io.input.bits := io.input.bits
    stage.io.input.valid := io.input.valid && useAcMetadataTokenTrace
    stage.io.trace.ready := io.trace.ready && useAcMetadataTokenTrace
  }
  acTokenTrace.foreach { stage =>
    stage.io.config := io.config
    stage.io.input.bits := io.input.bits
    stage.io.input.valid := io.input.valid && useAcTokenTrace
    stage.io.trace.ready := io.trace.ready && useAcTokenTrace
  }
  acStrategyTrace.foreach { stage =>
    stage.io.config := io.config
    stage.io.input.bits := io.input.bits
    stage.io.input.valid := io.input.valid && useAcStrategyTrace
    stage.io.trace.ready := io.trace.ready && useAcStrategyTrace
  }
  aqContrastTrace.foreach { stage =>
    stage.io.config := io.config
    stage.io.input.bits := io.input.bits
    stage.io.input.valid := io.input.valid && useAqContrastTrace
    stage.io.trace.ready := io.trace.ready && useAqContrastTrace
  }
  aqFuzzyErosionTrace.foreach { stage =>
    stage.io.config := io.config
    stage.io.input.bits := io.input.bits
    stage.io.input.valid := io.input.valid && useAqFuzzyErosionTrace
    stage.io.trace.ready := io.trace.ready && useAqFuzzyErosionTrace
  }
  aqStrategyMaskTrace.foreach { stage =>
    stage.io.config := io.config
    stage.io.input.bits := io.input.bits
    stage.io.input.valid := io.input.valid && useAqStrategyMaskTrace
    stage.io.trace.ready := io.trace.ready && useAqStrategyMaskTrace
  }
  aqNonlinearMaskTrace.foreach { stage =>
    stage.io.config := io.config
    stage.io.input.bits := io.input.bits
    stage.io.input.valid := io.input.valid && useAqNonlinearMaskTrace
    stage.io.trace.ready := io.trace.ready && useAqNonlinearMaskTrace
  }
  aqHfModulationTrace.foreach { stage =>
    stage.io.config := io.config
    stage.io.input.bits := io.input.bits
    stage.io.input.valid := io.input.valid && useAqHfModulationTrace
    stage.io.trace.ready := io.trace.ready && useAqHfModulationTrace
  }
  aqColorModulationTrace.foreach { stage =>
    stage.io.config := io.config
    stage.io.input.bits := io.input.bits
    stage.io.input.valid := io.input.valid && useAqColorModulationTrace
    stage.io.trace.ready := io.trace.ready && useAqColorModulationTrace
  }
  aqGammaModulationTrace.foreach { stage =>
    stage.io.config := io.config
    stage.io.input.bits := io.input.bits
    stage.io.input.valid := io.input.valid && useAqGammaModulationTrace
    stage.io.trace.ready := io.trace.ready && useAqGammaModulationTrace
  }
  aqFinalMapTrace.foreach { stage =>
    stage.io.config := io.config
    stage.io.input.bits := io.input.bits
    stage.io.input.valid := io.input.valid && useAqFinalMapTrace
    stage.io.trace.ready := io.trace.ready && useAqFinalMapTrace
  }

  val inactiveTrace = WireDefault(0.U.asTypeOf(new StageTrace(c)))

  io.input.ready := MuxCase(
    inputTrace.map(_.io.input.ready).getOrElse(false.B),
    Seq(
      useDcTokenTrace -> dcTokenTrace.map(_.io.input.ready).getOrElse(false.B),
      useAcMetadataTokenTrace -> acMetadataTokenTrace.map(_.io.input.ready).getOrElse(false.B),
      useAcTokenTrace -> acTokenTrace.map(_.io.input.ready).getOrElse(false.B),
      useAqFinalMapTrace -> aqFinalMapTrace.map(_.io.input.ready).getOrElse(false.B),
      useAqGammaModulationTrace -> aqGammaModulationTrace.map(_.io.input.ready).getOrElse(false.B),
      useAqColorModulationTrace -> aqColorModulationTrace.map(_.io.input.ready).getOrElse(false.B),
      useAqHfModulationTrace -> aqHfModulationTrace.map(_.io.input.ready).getOrElse(false.B),
      useAqNonlinearMaskTrace -> aqNonlinearMaskTrace.map(_.io.input.ready).getOrElse(false.B),
      useAqStrategyMaskTrace -> aqStrategyMaskTrace.map(_.io.input.ready).getOrElse(false.B),
      useAqFuzzyErosionTrace -> aqFuzzyErosionTrace.map(_.io.input.ready).getOrElse(false.B),
      useAqContrastTrace -> aqContrastTrace.map(_.io.input.ready).getOrElse(false.B),
      useQuantTrace -> quantTrace.map(_.io.input.ready).getOrElse(false.B),
      useDctTrace -> dctTrace.map(_.io.input.ready).getOrElse(false.B),
      useRawQuantTrace -> rawQuantTrace.map(_.io.input.ready).getOrElse(false.B),
      useYtoxTrace -> ytoxTrace.map(_.io.input.ready).getOrElse(false.B),
      useYtobTrace -> ytobTrace.map(_.io.input.ready).getOrElse(false.B),
      useAcStrategyTrace -> acStrategyTrace.map(_.io.input.ready).getOrElse(false.B),
      useXybTrace -> xybTrace.map(_.io.input.ready).getOrElse(false.B)
    )
  )
  io.trace.valid := MuxCase(
    inputTrace.map(_.io.trace.valid).getOrElse(false.B),
    Seq(
      useDcTokenTrace -> dcTokenTrace.map(_.io.trace.valid).getOrElse(false.B),
      useAcMetadataTokenTrace -> acMetadataTokenTrace.map(_.io.trace.valid).getOrElse(false.B),
      useAcTokenTrace -> acTokenTrace.map(_.io.trace.valid).getOrElse(false.B),
      useAqFinalMapTrace -> aqFinalMapTrace.map(_.io.trace.valid).getOrElse(false.B),
      useAqGammaModulationTrace -> aqGammaModulationTrace.map(_.io.trace.valid).getOrElse(false.B),
      useAqColorModulationTrace -> aqColorModulationTrace.map(_.io.trace.valid).getOrElse(false.B),
      useAqHfModulationTrace -> aqHfModulationTrace.map(_.io.trace.valid).getOrElse(false.B),
      useAqNonlinearMaskTrace -> aqNonlinearMaskTrace.map(_.io.trace.valid).getOrElse(false.B),
      useAqStrategyMaskTrace -> aqStrategyMaskTrace.map(_.io.trace.valid).getOrElse(false.B),
      useAqFuzzyErosionTrace -> aqFuzzyErosionTrace.map(_.io.trace.valid).getOrElse(false.B),
      useAqContrastTrace -> aqContrastTrace.map(_.io.trace.valid).getOrElse(false.B),
      useQuantTrace -> quantTrace.map(_.io.trace.valid).getOrElse(false.B),
      useDctTrace -> dctTrace.map(_.io.trace.valid).getOrElse(false.B),
      useRawQuantTrace -> rawQuantTrace.map(_.io.trace.valid).getOrElse(false.B),
      useYtoxTrace -> ytoxTrace.map(_.io.trace.valid).getOrElse(false.B),
      useYtobTrace -> ytobTrace.map(_.io.trace.valid).getOrElse(false.B),
      useAcStrategyTrace -> acStrategyTrace.map(_.io.trace.valid).getOrElse(false.B),
      useXybTrace -> xybTrace.map(_.io.trace.valid).getOrElse(false.B)
    )
  )
  io.trace.bits := MuxCase(
    inputTrace.map(_.io.trace.bits).getOrElse(inactiveTrace),
    Seq(
      useDcTokenTrace -> dcTokenTrace.map(_.io.trace.bits).getOrElse(inactiveTrace),
      useAcMetadataTokenTrace -> acMetadataTokenTrace.map(_.io.trace.bits).getOrElse(inactiveTrace),
      useAcTokenTrace -> acTokenTrace.map(_.io.trace.bits).getOrElse(inactiveTrace),
      useAqFinalMapTrace -> aqFinalMapTrace.map(_.io.trace.bits).getOrElse(inactiveTrace),
      useAqGammaModulationTrace -> aqGammaModulationTrace.map(_.io.trace.bits).getOrElse(inactiveTrace),
      useAqColorModulationTrace -> aqColorModulationTrace.map(_.io.trace.bits).getOrElse(inactiveTrace),
      useAqHfModulationTrace -> aqHfModulationTrace.map(_.io.trace.bits).getOrElse(inactiveTrace),
      useAqNonlinearMaskTrace -> aqNonlinearMaskTrace.map(_.io.trace.bits).getOrElse(inactiveTrace),
      useAqStrategyMaskTrace -> aqStrategyMaskTrace.map(_.io.trace.bits).getOrElse(inactiveTrace),
      useAqFuzzyErosionTrace -> aqFuzzyErosionTrace.map(_.io.trace.bits).getOrElse(inactiveTrace),
      useAqContrastTrace -> aqContrastTrace.map(_.io.trace.bits).getOrElse(inactiveTrace),
      useQuantTrace -> quantTrace.map(_.io.trace.bits).getOrElse(inactiveTrace),
      useDctTrace -> dctTrace.map(_.io.trace.bits).getOrElse(inactiveTrace),
      useRawQuantTrace -> rawQuantTrace.map(_.io.trace.bits).getOrElse(inactiveTrace),
      useYtoxTrace -> ytoxTrace.map(_.io.trace.bits).getOrElse(inactiveTrace),
      useYtobTrace -> ytobTrace.map(_.io.trace.bits).getOrElse(inactiveTrace),
      useAcStrategyTrace -> acStrategyTrace.map(_.io.trace.bits).getOrElse(inactiveTrace),
      useXybTrace -> xybTrace.map(_.io.trace.bits).getOrElse(inactiveTrace)
    )
  )
  io.traceLast := MuxCase(
    false.B,
    Seq(
      useInputTrace -> inputTrace.map(_.io.traceLast).getOrElse(false.B),
      useXybTrace -> xybTrace.map(_.io.traceLast).getOrElse(false.B),
      useDctTrace -> dctTrace.map(_.io.traceLast).getOrElse(false.B),
      useRawQuantTrace -> rawQuantTrace.map(_.io.traceLast).getOrElse(false.B),
      useYtoxTrace -> ytoxTrace.map(_.io.traceLast).getOrElse(false.B),
      useYtobTrace -> ytobTrace.map(_.io.traceLast).getOrElse(false.B),
      useQuantTrace -> quantTrace.map(_.io.traceLast).getOrElse(false.B),
      useDcTokenTrace -> dcTokenTrace.map(_.io.traceLast).getOrElse(false.B),
      useAcMetadataTokenTrace -> acMetadataTokenTrace.map(_.io.traceLast).getOrElse(false.B),
      useAcTokenTrace -> acTokenTrace.map(_.io.traceLast).getOrElse(false.B),
      useAqFinalMapTrace -> aqFinalMapTrace.map(_.io.traceLast).getOrElse(false.B),
      useAqGammaModulationTrace -> aqGammaModulationTrace.map(_.io.traceLast).getOrElse(false.B),
      useAqColorModulationTrace -> aqColorModulationTrace.map(_.io.traceLast).getOrElse(false.B),
      useAqHfModulationTrace -> aqHfModulationTrace.map(_.io.traceLast).getOrElse(false.B),
      useAqNonlinearMaskTrace -> aqNonlinearMaskTrace.map(_.io.traceLast).getOrElse(false.B),
      useAqStrategyMaskTrace -> aqStrategyMaskTrace.map(_.io.traceLast).getOrElse(false.B),
      useAqFuzzyErosionTrace -> aqFuzzyErosionTrace.map(_.io.traceLast).getOrElse(false.B),
      useAqContrastTrace -> aqContrastTrace.map(_.io.traceLast).getOrElse(false.B),
      useAcStrategyTrace -> acStrategyTrace.map(_.io.traceLast).getOrElse(false.B)
    )
  )
  io.busy := MuxCase(
    false.B,
    Seq(
      useInputTrace -> inputTrace.map(_.io.busy).getOrElse(false.B),
      useXybTrace -> xybTrace.map(_.io.busy).getOrElse(false.B),
      useDctTrace -> dctTrace.map(_.io.busy).getOrElse(false.B),
      useRawQuantTrace -> rawQuantTrace.map(_.io.busy).getOrElse(false.B),
      useYtoxTrace -> ytoxTrace.map(_.io.busy).getOrElse(false.B),
      useYtobTrace -> ytobTrace.map(_.io.busy).getOrElse(false.B),
      useQuantTrace -> quantTrace.map(_.io.busy).getOrElse(false.B),
      useDcTokenTrace -> dcTokenTrace.map(_.io.busy).getOrElse(false.B),
      useAcMetadataTokenTrace -> acMetadataTokenTrace.map(_.io.busy).getOrElse(false.B),
      useAcTokenTrace -> acTokenTrace.map(_.io.busy).getOrElse(false.B),
      useAqFinalMapTrace -> aqFinalMapTrace.map(_.io.busy).getOrElse(false.B),
      useAqGammaModulationTrace -> aqGammaModulationTrace.map(_.io.busy).getOrElse(false.B),
      useAqColorModulationTrace -> aqColorModulationTrace.map(_.io.busy).getOrElse(false.B),
      useAqHfModulationTrace -> aqHfModulationTrace.map(_.io.busy).getOrElse(false.B),
      useAqNonlinearMaskTrace -> aqNonlinearMaskTrace.map(_.io.busy).getOrElse(false.B),
      useAqStrategyMaskTrace -> aqStrategyMaskTrace.map(_.io.busy).getOrElse(false.B),
      useAqFuzzyErosionTrace -> aqFuzzyErosionTrace.map(_.io.busy).getOrElse(false.B),
      useAqContrastTrace -> aqContrastTrace.map(_.io.busy).getOrElse(false.B),
      useAcStrategyTrace -> acStrategyTrace.map(_.io.busy).getOrElse(false.B)
    )
  )
  io.overflow := MuxCase(
    false.B,
    Seq(
      useInputTrace -> inputTrace.map(_.io.overflow).getOrElse(false.B),
      useXybTrace -> xybTrace.map(_.io.overflow).getOrElse(false.B),
      useDctTrace -> dctTrace.map(_.io.overflow).getOrElse(false.B),
      useRawQuantTrace -> rawQuantTrace.map(_.io.overflow).getOrElse(false.B),
      useYtoxTrace -> ytoxTrace.map(_.io.overflow).getOrElse(false.B),
      useYtobTrace -> ytobTrace.map(_.io.overflow).getOrElse(false.B),
      useQuantTrace -> quantTrace.map(_.io.overflow).getOrElse(false.B),
      useDcTokenTrace -> dcTokenTrace.map(_.io.overflow).getOrElse(false.B),
      useAcMetadataTokenTrace -> acMetadataTokenTrace.map(_.io.overflow).getOrElse(false.B),
      useAcTokenTrace -> acTokenTrace.map(_.io.overflow).getOrElse(false.B),
      useAqFinalMapTrace -> aqFinalMapTrace.map(_.io.overflow).getOrElse(false.B),
      useAqGammaModulationTrace -> aqGammaModulationTrace.map(_.io.overflow).getOrElse(false.B),
      useAqColorModulationTrace -> aqColorModulationTrace.map(_.io.overflow).getOrElse(false.B),
      useAqHfModulationTrace -> aqHfModulationTrace.map(_.io.overflow).getOrElse(false.B),
      useAqNonlinearMaskTrace -> aqNonlinearMaskTrace.map(_.io.overflow).getOrElse(false.B),
      useAqStrategyMaskTrace -> aqStrategyMaskTrace.map(_.io.overflow).getOrElse(false.B),
      useAqFuzzyErosionTrace -> aqFuzzyErosionTrace.map(_.io.overflow).getOrElse(false.B),
      useAqContrastTrace -> aqContrastTrace.map(_.io.overflow).getOrElse(false.B),
      useAcStrategyTrace -> acStrategyTrace.map(_.io.overflow).getOrElse(false.B)
    )
  )
}
