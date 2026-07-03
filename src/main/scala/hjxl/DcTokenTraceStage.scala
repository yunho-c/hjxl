// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

class DcTokenTraceInput(c: HjxlConfig) extends Bundle {
  val group = UInt(c.groupBits.W)
  val current = SInt(c.traceValueBits.W)
  val west = SInt(c.traceValueBits.W)
  val north = SInt(c.traceValueBits.W)
  val northwest = SInt(c.traceValueBits.W)
}

/** Tokenizes one prepared quantized-DC sample with libjxl-tiny's predictor.
  *
  * This is the reusable boundary after quantized DC planes exist. It intentionally
  * does not compute XYB, DCT, or DC quantization; callers provide the current
  * quantized value and its west/north/northwest predictor neighbors.
  */
class DcTokenTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new DcTokenTraceInput(c)))
    val trace = Decoupled(new StageTrace(c))
  })

  val guess = DcTokenize.clampedGradient(io.input.bits.north, io.input.bits.west, io.input.bits.northwest)
  val residual = io.input.bits.current - guess
  val gradPropSigned =
    (DcTokenize.GradRangeMid.S + io.input.bits.north + io.input.bits.west - io.input.bits.northwest).asSInt
  val gradProp = Mux(
    gradPropSigned < DcTokenize.GradRangeMin.S,
    DcTokenize.GradRangeMin.U,
    Mux(gradPropSigned > DcTokenize.GradRangeMax.S, DcTokenize.GradRangeMax.U, gradPropSigned.asUInt)
  )

  io.input.ready := io.trace.ready
  io.trace.valid := io.input.valid
  io.trace.bits.stage := TraceStage.DcTokens.U
  io.trace.bits.group := io.input.bits.group
  io.trace.bits.index := DcTokenize.gradientContext(gradProp)
  io.trace.bits.value := DcTokenize.packSigned(residual, c.traceValueBits)
}
