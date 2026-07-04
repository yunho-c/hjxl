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
  private val includeQuant = includes(TraceStage.QuantizedAc)
  private val includeDcToken = includes(TraceStage.DcTokens)
  private val includeAcMetadataToken = includes(TraceStage.AcMetadataTokens)
  private val includeAcToken = traceRoute == TraceStage.AcTokens
  private val includeAcStrategy = includes(TraceStage.AcStrategy)

  val inputTrace = if (includeInput) Some(Module(new FramePadTraceStage(c))) else None
  val xybTrace = if (includeXyb) Some(Module(new FrameXybTraceStage(c))) else None
  val dctTrace = if (includeDct) Some(Module(new FrameDct8x8TraceStage(c))) else None
  val quantTrace = if (includeQuant) Some(Module(new FrameDctOnlyQuantizeTraceStage(c))) else None
  val dcTokenTrace = if (includeDcToken) Some(Module(new FrameDctOnlyDcTokenTraceStage(c))) else None
  val acMetadataTokenTrace =
    if (includeAcMetadataToken) Some(Module(new FrameDctOnlyAcMetadataTokenTraceStage(c))) else None
  val acTokenTrace = if (includeAcToken) Some(Module(new FrameDctOnlyAcTokenTraceStage(c))) else None
  val acStrategyTrace = if (includeAcStrategy) Some(Module(new FrameAcStrategyTraceStage(c))) else None

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
  val useAcStrategyTrace =
    if (includeAcStrategy) {
      io.config.enableQuant && !io.config.enableTokenize && !io.config.enableDct
    } else {
      false.B
    }
  val useXybTrace =
    if (includeXyb) {
      io.config.enableXyb && !useDctTrace && !useQuantTrace && !useDcTokenTrace &&
        !useAcMetadataTokenTrace && !useAcTokenTrace && !useAcStrategyTrace
    } else {
      false.B
    }
  val useInputTrace =
    if (includeInput) {
      !useXybTrace && !useDctTrace && !useQuantTrace && !useDcTokenTrace &&
        !useAcMetadataTokenTrace && !useAcTokenTrace && !useAcStrategyTrace
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

  val inactiveTrace = WireDefault(0.U.asTypeOf(new StageTrace(c)))

  io.input.ready := MuxCase(
    inputTrace.map(_.io.input.ready).getOrElse(false.B),
    Seq(
      useDcTokenTrace -> dcTokenTrace.map(_.io.input.ready).getOrElse(false.B),
      useAcMetadataTokenTrace -> acMetadataTokenTrace.map(_.io.input.ready).getOrElse(false.B),
      useAcTokenTrace -> acTokenTrace.map(_.io.input.ready).getOrElse(false.B),
      useQuantTrace -> quantTrace.map(_.io.input.ready).getOrElse(false.B),
      useDctTrace -> dctTrace.map(_.io.input.ready).getOrElse(false.B),
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
      useQuantTrace -> quantTrace.map(_.io.trace.valid).getOrElse(false.B),
      useDctTrace -> dctTrace.map(_.io.trace.valid).getOrElse(false.B),
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
      useQuantTrace -> quantTrace.map(_.io.trace.bits).getOrElse(inactiveTrace),
      useDctTrace -> dctTrace.map(_.io.trace.bits).getOrElse(inactiveTrace),
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
      useQuantTrace -> quantTrace.map(_.io.traceLast).getOrElse(false.B),
      useDcTokenTrace -> dcTokenTrace.map(_.io.traceLast).getOrElse(false.B),
      useAcMetadataTokenTrace -> acMetadataTokenTrace.map(_.io.traceLast).getOrElse(false.B),
      useAcTokenTrace -> acTokenTrace.map(_.io.traceLast).getOrElse(false.B),
      useAcStrategyTrace -> acStrategyTrace.map(_.io.traceLast).getOrElse(false.B)
    )
  )
  io.busy := MuxCase(
    false.B,
    Seq(
      useInputTrace -> inputTrace.map(_.io.busy).getOrElse(false.B),
      useXybTrace -> xybTrace.map(_.io.busy).getOrElse(false.B),
      useDctTrace -> dctTrace.map(_.io.busy).getOrElse(false.B),
      useQuantTrace -> quantTrace.map(_.io.busy).getOrElse(false.B),
      useDcTokenTrace -> dcTokenTrace.map(_.io.busy).getOrElse(false.B),
      useAcMetadataTokenTrace -> acMetadataTokenTrace.map(_.io.busy).getOrElse(false.B),
      useAcTokenTrace -> acTokenTrace.map(_.io.busy).getOrElse(false.B),
      useAcStrategyTrace -> acStrategyTrace.map(_.io.busy).getOrElse(false.B)
    )
  )
  io.overflow := MuxCase(
    false.B,
    Seq(
      useInputTrace -> inputTrace.map(_.io.overflow).getOrElse(false.B),
      useXybTrace -> xybTrace.map(_.io.overflow).getOrElse(false.B),
      useDctTrace -> dctTrace.map(_.io.overflow).getOrElse(false.B),
      useQuantTrace -> quantTrace.map(_.io.overflow).getOrElse(false.B),
      useDcTokenTrace -> dcTokenTrace.map(_.io.overflow).getOrElse(false.B),
      useAcMetadataTokenTrace -> acMetadataTokenTrace.map(_.io.overflow).getOrElse(false.B),
      useAcTokenTrace -> acTokenTrace.map(_.io.overflow).getOrElse(false.B),
      useAcStrategyTrace -> acStrategyTrace.map(_.io.overflow).getOrElse(false.B)
    )
  )
}
