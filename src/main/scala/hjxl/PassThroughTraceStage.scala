// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** First integration shell: accepts RGB samples and emits traceable input values.
  *
  * This deliberately does not claim to encode JPEG XL yet. It establishes the
  * ready/valid contract and a deterministic trace stream that later stages can
  * replace incrementally.
  */
class PassThroughTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val trace = Decoupled(new StageTrace(c))
  })

  val pending = RegInit(false.B)
  val pixel = Reg(new RgbPixel(c))
  val channel = RegInit(0.U(2.W))
  val index = RegInit(0.U(32.W))

  io.input.ready := !pending
  io.trace.valid := pending
  io.trace.bits.stage := TraceStage.InputPadded.U
  io.trace.bits.group := 0.U
  io.trace.bits.index := index + channel
  io.trace.bits.value := MuxLookup(channel, pixel.r)(
    Seq(
      0.U -> pixel.r,
      1.U -> pixel.g,
      2.U -> pixel.b
    )
  )

  when(io.input.fire) {
    pixel := io.input.bits
    pending := true.B
    channel := 0.U
  }

  when(io.trace.fire) {
    when(channel === 2.U) {
      pending := false.B
      channel := 0.U
      index := index + 3.U
    }.otherwise {
      channel := channel + 1.U
    }
  }

  when(io.config.xsize === 0.U || io.config.ysize === 0.U) {
    pending := false.B
    channel := 0.U
    index := 0.U
  }
}
