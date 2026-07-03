// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** Emits scaled DCT coefficients from padded approximate XYB blocks.
  *
  * This is an early trace stage for the VarDCT path. It buffers one bounded RGB
  * frame, applies the same right/bottom padding policy as `FramePadTraceStage`,
  * converts each padded 8x8 block to approximate Q12 XYB, applies the scaled
  * 8x8 DCT per channel, and emits block-major coefficients. `trace.group` is
  * the raster block index and `trace.index` is `channel * 64 + coefficient`.
  */
class FrameDct8x8TraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  private val blockDim = HjxlConstants.BlockDim
  private val blockSize = blockDim * blockDim
  private val coefficientsPerBlock = blockSize * 3
  private val numPixels = c.maxFrameWidth * c.maxFrameHeight
  private val frameIndexBits = log2Ceil(numPixels)
  private val frameCountBits = log2Ceil(numPixels + 1)
  private val widthBits = log2Ceil(c.maxFrameWidth + 1)
  private val heightBits = log2Ceil(c.maxFrameHeight + 1)

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val trace = Decoupled(new StageTrace(c))
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
  val xBlocks = RegInit(0.U(32.W))
  val totalBlocks = RegInit(0.U(32.W))
  val overflow = RegInit(false.B)

  private def ceilToBlock(value: UInt): UInt = {
    val block = blockDim.U
    ((value + (block - 1.U)) / block) * block
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

  val xBlocksSafe = Mux(xBlocks === 0.U, 1.U, xBlocks)
  val emitBlock = emitIndex / coefficientsPerBlock.U
  val withinBlockIndex = emitIndex - emitBlock * coefficientsPerBlock.U
  val blockX = emitBlock - (emitBlock / xBlocksSafe) * xBlocksSafe
  val blockY = emitBlock / xBlocksSafe
  val blockBaseX = blockX << log2Ceil(blockDim)
  val blockBaseY = blockY << log2Ceil(blockDim)

  val xybPixels = Seq.tabulate(blockSize) { i =>
    val localX = (i % blockDim).U(widthBits.W)
    val localY = (i / blockDim).U(heightBits.W)
    val paddedX = (blockBaseX + localX)(widthBits - 1, 0)
    val paddedY = (blockBaseY + localY)(heightBits - 1, 0)
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
    xyb.io.output.ready := true.B
    xyb.io.output.bits
  }

  val dctX = Module(new Dct8x8Approx(c))
  val dctY = Module(new Dct8x8Approx(c))
  val dctB = Module(new Dct8x8Approx(c))
  val dcts = Seq(dctX, dctY, dctB)
  for (dct <- dcts) {
    dct.io.input.valid := state === emitting
    dct.io.output.ready := true.B
  }
  for (i <- 0 until blockSize) {
    dctX.io.input.bits(i) := xybPixels(i).xybX
    dctY.io.input.bits(i) := xybPixels(i).xybY
    dctB.io.input.bits(i) := xybPixels(i).xybB
  }

  val emitChannel = withinBlockIndex / blockSize.U
  val coefficientIndex = withinBlockIndex - emitChannel * blockSize.U
  val coefficientIndexFixed = coefficientIndex(log2Ceil(blockSize) - 1, 0)
  val sample = MuxLookup(emitChannel, dctX.io.output.bits(coefficientIndexFixed))(
    Seq(
      0.U -> dctX.io.output.bits(coefficientIndexFixed),
      1.U -> dctY.io.output.bits(coefficientIndexFixed),
      2.U -> dctB.io.output.bits(coefficientIndexFixed)
    )
  )

  io.input.ready := state === receiving && !configOutOfRange
  io.trace.valid := state === emitting
  io.busy := state === emitting || received =/= 0.U
  io.overflow := overflow || configOutOfRange
  io.trace.bits.stage := TraceStage.RawDct8x8.U
  io.trace.bits.group := emitBlock(c.groupBits - 1, 0)
  io.trace.bits.index := withinBlockIndex
  io.trace.bits.value := sample.asSInt.pad(c.traceValueBits)

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
      xBlocks := nextPaddedWidth >> log2Ceil(blockDim)
      totalBlocks := (nextPaddedWidth >> log2Ceil(blockDim)) * (nextPaddedHeight >> log2Ceil(blockDim))
    }
  }

  when(io.trace.fire) {
    val nextEmit = emitIndex + 1.U
    emitIndex := nextEmit
    when(nextEmit === totalBlocks * coefficientsPerBlock.U) {
      state := receiving
      received := 0.U
      emitIndex := 0.U
      xBlocks := 0.U
      totalBlocks := 0.U
    }
  }

  when(io.input.fire && received >= numPixels.U) {
    overflow := true.B
  }
}
