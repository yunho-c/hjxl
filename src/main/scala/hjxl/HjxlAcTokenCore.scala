// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** Compile-time top wrapper for the fixed DCT-only full AC-token trace path.
  *
  * The full AC-token frame scheduler is heavy enough that default `HjxlCore`
  * elaboration keeps it out of the all-route runtime multiplexer. Use this
  * wrapper when elaborating or integrating the full AC-token stream directly,
  * or use `new HjxlCore(traceRoute = TraceStage.AcTokens)` for a focused core
  * IO shell around the same route.
  */
class HjxlAcTokenCore(c: HjxlConfig = HjxlConfig()) extends Module {
  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
  })

  val acTokens = Module(new FrameDctOnlyAcTokenTraceStage(c))
  acTokens.io.config := io.config
  acTokens.io.input <> io.input
  io.trace <> acTokens.io.trace
  io.traceLast := acTokens.io.traceLast
}
