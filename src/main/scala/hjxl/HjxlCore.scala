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
  inputTrace.io.config := io.config
  inputTrace.io.input <> io.input
  io.trace <> inputTrace.io.trace
}
