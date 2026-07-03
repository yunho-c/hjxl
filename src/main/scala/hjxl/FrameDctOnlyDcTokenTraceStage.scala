// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** Emits DC residual tokens from the fixed-parameter DCT-only frame path.
  *
  * Token traces use `trace.stage = DcTokens`, `trace.group = token ordinal`,
  * `trace.index = token context`, and `trace.value = packed residual`.
  * Tokens are emitted in libjxl-tiny DC order: Y plane, X plane, then B plane,
  * each in raster block order.
  */
class FrameDctOnlyDcTokenTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  private val blockDim = HjxlConstants.BlockDim
  private val blockSize = blockDim * blockDim
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

  private def quantizedDcForBlock(blockX: UInt, blockY: UInt): (Seq[SInt], Bool) = {
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

    val yDc = Module(new QuantizeDcDct8x8Block(c))
    val xDc = Module(new QuantizeDcDct8x8Block(c))
    val bDc = Module(new QuantizeDcDct8x8Block(c))
    val dcModules = Seq(xDc, yDc, bDc)
    for (dc <- dcModules) {
      dc.io.input.valid := state === emitting
      dc.io.output.ready := true.B
    }

    yDc.io.input.bits.dcCoefficient := dctY.io.output.bits(0)
    yDc.io.input.bits.channel := 1.U
    yDc.io.input.bits.invDcFactorQ16 := QuantizeDct8x8Block.DefaultInvDcFactorQ16(1).U
    yDc.io.input.bits.quantizedYDc := 0.S

    xDc.io.input.bits.dcCoefficient := dctX.io.output.bits(0)
    xDc.io.input.bits.channel := 0.U
    xDc.io.input.bits.invDcFactorQ16 := QuantizeDct8x8Block.DefaultInvDcFactorQ16(0).U
    xDc.io.input.bits.quantizedYDc := yDc.io.output.bits.quantizedDc

    bDc.io.input.bits.dcCoefficient := dctB.io.output.bits(0)
    bDc.io.input.bits.channel := 2.U
    bDc.io.input.bits.invDcFactorQ16 := QuantizeDct8x8Block.DefaultInvDcFactorQ16(2).U
    bDc.io.input.bits.quantizedYDc := yDc.io.output.bits.quantizedDc

    (
      Seq(xDc.io.output.bits.quantizedDc, yDc.io.output.bits.quantizedDc, bDc.io.output.bits.quantizedDc),
      dctX.io.output.valid && dctY.io.output.valid && dctB.io.output.valid &&
        xDc.io.output.valid && yDc.io.output.valid && bDc.io.output.valid
    )
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
  val totalBlocksSafe = Mux(totalBlocks === 0.U, 1.U, totalBlocks)
  val plane = emitIndex / totalBlocksSafe
  val blockOrdinal = emitIndex - plane * totalBlocksSafe
  val blockX = blockOrdinal - (blockOrdinal / xBlocksSafe) * xBlocksSafe
  val blockY = blockOrdinal / xBlocksSafe
  val channel = MuxLookup(plane, 2.U)(
    Seq(
      0.U -> 1.U,
      1.U -> 0.U,
      2.U -> 2.U
    )
  )
  val channelSafe = channel(1, 0)

  val westBlockX = Mux(blockX === 0.U, blockX, blockX - 1.U)
  val northBlockY = Mux(blockY === 0.U, blockY, blockY - 1.U)
  val northwestBlockX = westBlockX
  val northwestBlockY = northBlockY

  val (currentDc, currentValid) = quantizedDcForBlock(blockX, blockY)
  val (westDc, westValid) = quantizedDcForBlock(westBlockX, blockY)
  val (northDc, northValid) = quantizedDcForBlock(blockX, northBlockY)
  val (northwestDc, northwestValid) = quantizedDcForBlock(northwestBlockX, northwestBlockY)

  val current = VecInit(currentDc)(channelSafe)
  val westCandidate = VecInit(westDc)(channelSafe)
  val northCandidate = VecInit(northDc)(channelSafe)
  val northwestCandidate = VecInit(northwestDc)(channelSafe)

  val left = Mux(blockX =/= 0.U, westCandidate, Mux(blockY =/= 0.U, northCandidate, 0.S))
  val top = Mux(blockY =/= 0.U, northCandidate, left)
  val topLeft = Mux(blockX =/= 0.U && blockY =/= 0.U, northwestCandidate, left)
  val guess = DcTokenize.clampedGradient(top, left, topLeft)
  val residual = current - guess
  val gradPropSigned = (DcTokenize.GradRangeMid.S + top + left - topLeft).asSInt
  val gradProp = Mux(
    gradPropSigned < DcTokenize.GradRangeMin.S,
    DcTokenize.GradRangeMin.U,
    Mux(gradPropSigned > DcTokenize.GradRangeMax.S, DcTokenize.GradRangeMax.U, gradPropSigned.asUInt)
  )

  io.input.ready := state === receiving && !configOutOfRange
  io.trace.valid := state === emitting && currentValid && westValid && northValid && northwestValid
  io.busy := state === emitting || received =/= 0.U
  io.overflow := overflow || configOutOfRange
  io.trace.bits.stage := TraceStage.DcTokens.U
  io.trace.bits.group := emitIndex(c.groupBits - 1, 0)
  io.trace.bits.index := DcTokenize.gradientContext(gradProp)
  io.trace.bits.value := DcTokenize.packSigned(residual, c.traceValueBits)

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
    when(nextEmit === totalBlocks * 3.U) {
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
