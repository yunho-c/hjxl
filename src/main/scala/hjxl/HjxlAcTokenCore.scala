// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** Compile-time top wrapper for the fixed DCT-only full AC-token trace path.
  *
  * `HjxlCore` intentionally remains a small runtime trace multiplexer. The full
  * AC-token frame scheduler is heavy enough that placing it in that multiplexer
  * makes unrelated top-level Verilator tests impractical. Use this wrapper when
  * elaborating or integrating the full AC-token stream directly.
  */
class HjxlAcTokenCore(c: HjxlConfig = HjxlConfig()) extends Module {
  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val trace = Decoupled(new StageTrace(c))
  })

  val acTokens = Module(new FrameDctOnlyAcTokenTraceStage(c))
  acTokens.io.config := io.config
  acTokens.io.input <> io.input
  io.trace <> acTokens.io.trace
}
