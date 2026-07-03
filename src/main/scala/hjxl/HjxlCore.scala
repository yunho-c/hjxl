// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

class HjxlCore(c: HjxlConfig = HjxlConfig()) extends Module {
  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val trace = Decoupled(new StageTrace(c))
  })

  val inputTrace = Module(new FramePadTraceStage(c))
  val xybTrace = Module(new FrameXybTraceStage(c))
  val dctTrace = Module(new FrameDct8x8TraceStage(c))
  val quantTrace = Module(new FrameDctOnlyQuantizeTraceStage(c))
  val dcTokenTrace = Module(new FrameDctOnlyDcTokenTraceStage(c))
  val acMetadataTokenTrace = Module(new FrameDctOnlyAcMetadataTokenTraceStage(c))
  val acStrategyTrace = Module(new FrameAcStrategyTraceStage(c))
  val useDcTokenTrace =
    io.config.enableDct && io.config.enableQuant && io.config.enableTokenize &&
      io.config.tokenSelect === TokenTraceSelect.Dc.U
  val useAcMetadataTokenTrace =
    io.config.enableQuant && io.config.enableTokenize &&
      io.config.tokenSelect === TokenTraceSelect.AcMetadata.U
  val useQuantTrace =
    io.config.enableDct && io.config.enableQuant && !useDcTokenTrace && !useAcMetadataTokenTrace
  val useDctTrace =
    io.config.enableDct && !useQuantTrace && !useDcTokenTrace && !useAcMetadataTokenTrace
  val useAcStrategyTrace = io.config.enableQuant && !io.config.enableTokenize && !io.config.enableDct
  val useXybTrace =
    io.config.enableXyb && !useDctTrace && !useQuantTrace && !useDcTokenTrace &&
      !useAcMetadataTokenTrace && !useAcStrategyTrace

  inputTrace.io.config := io.config
  xybTrace.io.config := io.config
  dctTrace.io.config := io.config
  quantTrace.io.config := io.config
  dcTokenTrace.io.config := io.config
  acMetadataTokenTrace.io.config := io.config
  acStrategyTrace.io.config := io.config

  inputTrace.io.input.bits := io.input.bits
  inputTrace.io.input.valid :=
    io.input.valid && !useXybTrace && !useDctTrace && !useQuantTrace && !useDcTokenTrace &&
      !useAcMetadataTokenTrace && !useAcStrategyTrace
  xybTrace.io.input.bits := io.input.bits
  xybTrace.io.input.valid := io.input.valid && useXybTrace
  dctTrace.io.input.bits := io.input.bits
  dctTrace.io.input.valid := io.input.valid && useDctTrace
  quantTrace.io.input.bits := io.input.bits
  quantTrace.io.input.valid := io.input.valid && useQuantTrace
  dcTokenTrace.io.input.bits := io.input.bits
  dcTokenTrace.io.input.valid := io.input.valid && useDcTokenTrace
  acMetadataTokenTrace.io.input.bits := io.input.bits
  acMetadataTokenTrace.io.input.valid := io.input.valid && useAcMetadataTokenTrace
  acStrategyTrace.io.input.bits := io.input.bits
  acStrategyTrace.io.input.valid := io.input.valid && useAcStrategyTrace
  io.input.ready := MuxCase(
    inputTrace.io.input.ready,
    Seq(
      useDcTokenTrace -> dcTokenTrace.io.input.ready,
      useAcMetadataTokenTrace -> acMetadataTokenTrace.io.input.ready,
      useQuantTrace -> quantTrace.io.input.ready,
      useDctTrace -> dctTrace.io.input.ready,
      useAcStrategyTrace -> acStrategyTrace.io.input.ready,
      useXybTrace -> xybTrace.io.input.ready
    )
  )

  inputTrace.io.trace.ready :=
    io.trace.ready && !useXybTrace && !useDctTrace && !useQuantTrace && !useDcTokenTrace &&
      !useAcMetadataTokenTrace && !useAcStrategyTrace
  xybTrace.io.trace.ready := io.trace.ready && useXybTrace
  dctTrace.io.trace.ready := io.trace.ready && useDctTrace
  quantTrace.io.trace.ready := io.trace.ready && useQuantTrace
  dcTokenTrace.io.trace.ready := io.trace.ready && useDcTokenTrace
  acMetadataTokenTrace.io.trace.ready := io.trace.ready && useAcMetadataTokenTrace
  acStrategyTrace.io.trace.ready := io.trace.ready && useAcStrategyTrace
  io.trace.valid := MuxCase(
    inputTrace.io.trace.valid,
    Seq(
      useDcTokenTrace -> dcTokenTrace.io.trace.valid,
      useAcMetadataTokenTrace -> acMetadataTokenTrace.io.trace.valid,
      useQuantTrace -> quantTrace.io.trace.valid,
      useDctTrace -> dctTrace.io.trace.valid,
      useAcStrategyTrace -> acStrategyTrace.io.trace.valid,
      useXybTrace -> xybTrace.io.trace.valid
    )
  )
  io.trace.bits := MuxCase(
    inputTrace.io.trace.bits,
    Seq(
      useDcTokenTrace -> dcTokenTrace.io.trace.bits,
      useAcMetadataTokenTrace -> acMetadataTokenTrace.io.trace.bits,
      useQuantTrace -> quantTrace.io.trace.bits,
      useDctTrace -> dctTrace.io.trace.bits,
      useAcStrategyTrace -> acStrategyTrace.io.trace.bits,
      useXybTrace -> xybTrace.io.trace.bits
    )
  )
}
