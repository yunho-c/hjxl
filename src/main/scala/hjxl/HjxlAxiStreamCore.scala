// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

class AxiStreamWord(dataBits: Int) extends Bundle {
  val data = UInt(dataBits.W)
  val last = Bool()
}

/** AXI4-Stream-shaped shell around `HjxlCore`.
  *
  * The input stream is raster RGB. Within `input.bits.data`,
  * bits `[pixelBits-1:0]` are R, `[2*pixelBits-1:pixelBits]` are G, and
  * `[3*pixelBits-1:2*pixelBits]` are B. The wrapper generates the `x/y`
  * coordinates consumed by `HjxlCore`.
  *
  * The trace output packs one `StageTrace` row as `{value, index, group, stage}`,
  * with `stage` in the low eight bits. `trace.bits.last` is asserted for each
  * route's final frame trace word, including the variable-length full AC-token
  * route.
  */
class HjxlAxiStreamCore(c: HjxlConfig = HjxlConfig(), traceRoute: Int = HjxlCoreTraceRoute.All) extends Module {
  val pixelDataBits = c.pixelBits * 3
  val traceDataBits = 8 + c.groupBits + 32 + c.traceValueBits

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val clearProtocolError = Input(Bool())
    val input = Flipped(Decoupled(new AxiStreamWord(pixelDataBits)))
    val trace = Decoupled(new AxiStreamWord(traceDataBits))
    val protocolError = Output(Bool())
  })

  val core = Module(new HjxlCore(c, traceRoute))
  core.io.config := io.config

  val x = RegInit(0.U(c.coordBits.W))
  val y = RegInit(0.U(c.coordBits.W))
  val inputFrameActive = RegInit(false.B)
  val inputWidth = RegInit(0.U(c.coordBits.W))
  val inputHeight = RegInit(0.U(c.coordBits.W))
  val protocolError = RegInit(false.B)

  val configWidth = io.config.xsize
  val configHeight = io.config.ysize
  val activeInputWidth = Mux(inputFrameActive, inputWidth, configWidth)
  val activeInputHeight = Mux(inputFrameActive, inputHeight, configHeight)
  val lastX = Mux(activeInputWidth === 0.U, 0.U, activeInputWidth - 1.U)
  val lastY = Mux(activeInputHeight === 0.U, 0.U, activeInputHeight - 1.U)
  val expectedLast = x === lastX && y === lastY

  core.io.input.valid := io.input.valid
  io.input.ready := core.io.input.ready
  core.io.input.bits.x := x
  core.io.input.bits.y := y
  core.io.input.bits.r := io.input.bits.data(c.pixelBits - 1, 0).asSInt
  core.io.input.bits.g := io.input.bits.data((2 * c.pixelBits) - 1, c.pixelBits).asSInt
  core.io.input.bits.b := io.input.bits.data((3 * c.pixelBits) - 1, 2 * c.pixelBits).asSInt

  when(io.clearProtocolError) {
    protocolError := false.B
  }

  when(io.input.fire) {
    when(!inputFrameActive) {
      inputFrameActive := true.B
      inputWidth := configWidth
      inputHeight := configHeight
    }
    when(io.input.bits.last =/= expectedLast) {
      protocolError := true.B
    }
    when(expectedLast) {
      x := 0.U
      y := 0.U
      inputFrameActive := false.B
    }.elsewhen(x === lastX) {
      x := 0.U
      y := y + 1.U
    }.otherwise {
      x := x + 1.U
    }
  }

  io.trace.valid := core.io.trace.valid
  core.io.trace.ready := io.trace.ready
  io.trace.bits.data := Cat(
    core.io.trace.bits.value.asUInt,
    core.io.trace.bits.index,
    core.io.trace.bits.group,
    core.io.trace.bits.stage
  )
  io.trace.bits.last := core.io.trace.valid && core.io.traceLast
  io.protocolError := protocolError
}
