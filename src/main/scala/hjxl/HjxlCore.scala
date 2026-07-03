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
  val acStrategyTrace = Module(new FrameAcStrategyTraceStage(c))
  val useDctTrace = io.config.enableDct
  val useAcStrategyTrace = io.config.enableQuant && !useDctTrace
  val useXybTrace = io.config.enableXyb && !useDctTrace && !useAcStrategyTrace

  inputTrace.io.config := io.config
  xybTrace.io.config := io.config
  dctTrace.io.config := io.config
  acStrategyTrace.io.config := io.config

  inputTrace.io.input.bits := io.input.bits
  inputTrace.io.input.valid := io.input.valid && !useXybTrace && !useDctTrace && !useAcStrategyTrace
  xybTrace.io.input.bits := io.input.bits
  xybTrace.io.input.valid := io.input.valid && useXybTrace
  dctTrace.io.input.bits := io.input.bits
  dctTrace.io.input.valid := io.input.valid && useDctTrace
  acStrategyTrace.io.input.bits := io.input.bits
  acStrategyTrace.io.input.valid := io.input.valid && useAcStrategyTrace
  io.input.ready := MuxCase(
    inputTrace.io.input.ready,
    Seq(
      useDctTrace -> dctTrace.io.input.ready,
      useAcStrategyTrace -> acStrategyTrace.io.input.ready,
      useXybTrace -> xybTrace.io.input.ready
    )
  )

  inputTrace.io.trace.ready := io.trace.ready && !useXybTrace && !useDctTrace && !useAcStrategyTrace
  xybTrace.io.trace.ready := io.trace.ready && useXybTrace
  dctTrace.io.trace.ready := io.trace.ready && useDctTrace
  acStrategyTrace.io.trace.ready := io.trace.ready && useAcStrategyTrace
  io.trace.valid := MuxCase(
    inputTrace.io.trace.valid,
    Seq(
      useDctTrace -> dctTrace.io.trace.valid,
      useAcStrategyTrace -> acStrategyTrace.io.trace.valid,
      useXybTrace -> xybTrace.io.trace.valid
    )
  )
  io.trace.bits := MuxCase(
    inputTrace.io.trace.bits,
    Seq(
      useDctTrace -> dctTrace.io.trace.bits,
      useAcStrategyTrace -> acStrategyTrace.io.trace.bits,
      useXybTrace -> xybTrace.io.trace.bits
    )
  )
}
