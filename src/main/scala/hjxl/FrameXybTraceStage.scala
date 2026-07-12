// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** Buffers one small RGB frame and emits padded XYB samples.
  *
  * Output order is channel-first: all X samples, then all Y samples, then all B
  * samples, each in row-major order over the padded frame. The padded RGB source
  * coordinates match `FramePadTraceStage`.
  */
class FrameXybTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  private val numPixels = c.maxFrameWidth * c.maxFrameHeight
  private val frameIndexBits = log2Ceil(numPixels)
  private val frameCountBits = log2Ceil(numPixels + 1)
  private val widthBits = log2Ceil(c.maxFrameWidth + 1)
  private val heightBits = log2Ceil(c.maxFrameHeight + 1)

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val red = Reg(Vec(numPixels, SInt(c.pixelBits.W)))
  val green = Reg(Vec(numPixels, SInt(c.pixelBits.W)))
  val blue = Reg(Vec(numPixels, SInt(c.pixelBits.W)))

  val receiving :: emitting :: Nil = Enum(2)
  val state = RegInit(receiving)
  val received = RegInit(0.U(frameCountBits.W))
  val emitIndex = RegInit(0.U(32.W))
  val latchedWidth = RegInit(0.U(widthBits.W))
  val latchedHeight = RegInit(0.U(heightBits.W))
  val paddedWidth = RegInit(0.U(widthBits.W))
  val paddedPixelCount = RegInit(0.U(32.W))
  val overflow = RegInit(false.B)

  private def ceilToBlock(value: UInt): UInt = {
    val block = HjxlConstants.BlockDim.U
    ((value +& (block - 1.U)) / block) * block
  }

  val configWidth = io.config.xsize(widthBits - 1, 0)
  val configHeight = io.config.ysize(heightBits - 1, 0)
  val activeWidth = Mux(state === receiving, configWidth, latchedWidth)
  val activeHeight = Mux(state === receiving, configHeight, latchedHeight)
  val expectedPixels = activeWidth * activeHeight
  val nextPaddedWidth = ceilToBlock(configWidth)(widthBits - 1, 0)
  val nextPaddedHeight = ceilToBlock(configHeight)(heightBits - 1, 0)
  val configOutOfRange =
    io.config.xsize === 0.U || io.config.ysize === 0.U ||
      io.config.xsize > c.maxFrameWidth.U || io.config.ysize > c.maxFrameHeight.U ||
      nextPaddedWidth > c.maxFrameWidth.U || nextPaddedHeight > c.maxFrameHeight.U

  val emitChannel = emitIndex / paddedPixelCount
  val withinChannelIndex = emitIndex - emitChannel * paddedPixelCount
  val paddedY = withinChannelIndex / paddedWidth
  val paddedX = withinChannelIndex - paddedY * paddedWidth
  val sourceX = Mux(paddedX >= latchedWidth, latchedWidth - 1.U, paddedX)
  val sourceY = Mux(paddedY >= latchedHeight, latchedHeight - 1.U, paddedY)
  val sourceIndex = sourceY * latchedWidth + sourceX
  val sourceIndexFixed = sourceIndex(frameIndexBits - 1, 0)

  val xyb = Module(new RgbToXybApprox(c))
  xyb.io.input.valid := state === emitting
  xyb.io.input.bits.x := paddedX
  xyb.io.input.bits.y := paddedY
  xyb.io.input.bits.r := red(sourceIndexFixed)
  xyb.io.input.bits.g := green(sourceIndexFixed)
  xyb.io.input.bits.b := blue(sourceIndexFixed)
  xyb.io.output.ready := io.trace.ready

  val sample = MuxLookup(emitChannel, xyb.io.output.bits.xybX)(
    Seq(
      0.U -> xyb.io.output.bits.xybX,
      1.U -> xyb.io.output.bits.xybY,
      2.U -> xyb.io.output.bits.xybB
    )
  )

  io.input.ready := state === receiving && !configOutOfRange
  io.trace.valid := xyb.io.output.valid
  io.busy := state === emitting || received =/= 0.U
  io.overflow := overflow || configOutOfRange
  io.trace.bits.stage := TraceStage.Xyb.U
  io.trace.bits.group := 0.U
  io.trace.bits.index := emitIndex
  io.trace.bits.value := sample.asSInt.pad(c.traceValueBits)
  io.traceLast := io.trace.valid && paddedPixelCount =/= 0.U && emitIndex === paddedPixelCount * 3.U - 1.U

  when(configOutOfRange) {
    received := 0.U
    state := receiving
  }.elsewhen(io.input.fire) {
    val receiveIndex = received(frameIndexBits - 1, 0)
    red(receiveIndex) := io.input.bits.r
    green(receiveIndex) := io.input.bits.g
    blue(receiveIndex) := io.input.bits.b
    val nextReceived = received + 1.U
    received := nextReceived

    when(nextReceived === expectedPixels) {
      state := emitting
      emitIndex := 0.U
      latchedWidth := configWidth
      latchedHeight := configHeight
      paddedWidth := nextPaddedWidth
      paddedPixelCount := nextPaddedWidth * nextPaddedHeight
    }
  }

  when(io.trace.fire) {
    val nextEmit = emitIndex + 1.U
    emitIndex := nextEmit
    when(nextEmit === paddedPixelCount * 3.U) {
      state := receiving
      received := 0.U
      emitIndex := 0.U
      paddedPixelCount := 0.U
    }
  }

  when(io.input.fire && received >= numPixels.U) {
    overflow := true.B
  }
}
