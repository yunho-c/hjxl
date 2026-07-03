// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** Emits the nonzero-count prefix tokens of AC coefficient tokenization.
  *
  * libjxl-tiny emits one nonzero-count token before the coefficient scan for
  * every first block and channel. This stage implements that mandatory prefix
  * for the current fixed all-DCT frame path. It does not emit coefficient-value
  * AC tokens yet.
  */
class FrameDctOnlyAcNonzeroTokenTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
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

  val distanceParams = Module(new DistanceParamsLookup)
  distanceParams.io.distanceQ8 := io.config.distanceQ8

  private def ceilToBlock(value: UInt): UInt = {
    val block = blockDim.U
    ((value + (block - 1.U)) / block) * block
  }

  private def quantizedCountsForBlock(blockX: UInt, blockY: UInt): (Seq[UInt], Bool) = {
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

    val scaleQ16 = Mux(
      io.config.fixedPointScale === 0.U,
      distanceParams.io.params.scaleQ16,
      io.config.fixedPointScale
    )
    val quant = QuantizeDct8x8Block.DefaultRawQuant.U(8.W)
    val invQacQ16 = (BigInt(1) << 32).U(64.W) / (scaleQ16 * quant)

    val quantizer = Module(new DctOnlyQuantizeBlock(c))
    quantizer.io.input.valid := state === emitting
    quantizer.io.output.ready := true.B
    quantizer.io.input.bits.quant := quant
    quantizer.io.input.bits.scaleQ16 := scaleQ16
    quantizer.io.input.bits.invQacQ16 := invQacQ16(31, 0)
    quantizer.io.input.bits.xQmMultiplierQ16 := distanceParams.io.params.xQmMultiplierQ16
    quantizer.io.input.bits.ytox := 0.S
    quantizer.io.input.bits.ytob := 0.S
    for (channel <- 0 until 3) {
      quantizer.io.input.bits.invDcFactorQ16(channel) := distanceParams.io.params.invDcFactorQ16(channel)
    }
    for (i <- 0 until blockSize) {
      quantizer.io.input.bits.coefficients(0)(i) := dctX.io.output.bits(i)
      quantizer.io.input.bits.coefficients(1)(i) := dctY.io.output.bits(i)
      quantizer.io.input.bits.coefficients(2)(i) := dctB.io.output.bits(i)
    }

    (
      Seq(
        quantizer.io.output.bits.numNonzeros(0),
        quantizer.io.output.bits.numNonzeros(1),
        quantizer.io.output.bits.numNonzeros(2)
      ),
      dctX.io.output.valid && dctY.io.output.valid && dctB.io.output.valid && quantizer.io.output.valid
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
  val blockOrdinal = emitIndex / 3.U
  val channelOrdinal = emitIndex - blockOrdinal * 3.U
  val blockX = blockOrdinal - (blockOrdinal / xBlocksSafe) * xBlocksSafe
  val blockY = blockOrdinal / xBlocksSafe
  val channel = MuxLookup(channelOrdinal, 2.U)(
    Seq(
      0.U -> 1.U,
      1.U -> 0.U,
      2.U -> 2.U
    )
  )
  val channelSafe = channel(1, 0)

  val westBlockX = Mux(blockX === 0.U, blockX, blockX - 1.U)
  val northBlockY = Mux(blockY === 0.U, blockY, blockY - 1.U)
  val (currentCounts, currentValid) = quantizedCountsForBlock(blockX, blockY)
  val (westCounts, westValid) = quantizedCountsForBlock(westBlockX, blockY)
  val (northCounts, northValid) = quantizedCountsForBlock(blockX, northBlockY)

  val current = VecInit(currentCounts)(channelSafe)
  val west = VecInit(westCounts)(channelSafe)
  val north = VecInit(northCounts)(channelSafe)
  val predicted = Mux(
    blockX === 0.U,
    Mux(blockY === 0.U, 32.U, north),
    Mux(blockY === 0.U, west, (north + west + 1.U) >> 1)
  )
  val blockContext = VecInit(Tokenize.DctBlockContextByChannel.map(_.U(2.W)))(channelSafe)

  io.input.ready := state === receiving && !configOutOfRange
  io.trace.valid := state === emitting && currentValid && westValid && northValid
  io.busy := state === emitting || received =/= 0.U
  io.overflow := overflow || configOutOfRange
  io.trace.bits.stage := TraceStage.AcTokens.U
  io.trace.bits.group := emitIndex(c.groupBits - 1, 0)
  io.trace.bits.index := Tokenize.nonzeroContext(predicted, blockContext)
  io.trace.bits.value := current.asSInt.pad(c.traceValueBits)

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
