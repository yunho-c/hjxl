// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** Emits complete AC coefficient-token traces for the fixed DCT-only frame path.
  *
  * This stage extends `FrameDctOnlyAcNonzeroTokenTraceStage` by feeding each
  * quantized X/Y/B raster block into `DctOnlyAcBlockTokenTraceStage`, so each
  * block emits the nonzero-count prefixes and the coefficient scan/value tokens.
  * It intentionally keeps the same fixed quantization defaults as
  * `FrameDctOnlyQuantizeTraceStage`: fixed adjusted raw quant, zero CFL, and
  * distance-derived scalar parameters from `DistanceParamsLookup`, with
  * `fixedPointScale` plus `fixedInvQacQ16` still able to override only the AC
  * scale path.
  */
class FrameDctOnlyAcTokenTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
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

  val receiving :: startBlock :: emitBlock :: Nil = Enum(3)
  val state = RegInit(receiving)
  val received = RegInit(0.U(frameCountBits.W))
  val blockOrdinal = RegInit(0.U(32.W))
  val tokenOrdinal = RegInit(0.U(c.groupBits.W))
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

  private def quantizedBlockFor(blockX: UInt, blockY: UInt): (Vec[Vec[SInt]], Vec[UInt], Bool) = {
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
      xyb.io.input.valid := state === startBlock
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
      dct.io.input.valid := state === startBlock
      dct.io.output.ready := true.B
    }
    for (i <- 0 until blockSize) {
      dctX.io.input.bits(i) := xybPixels(i).xybX
      dctY.io.input.bits(i) := xybPixels(i).xybY
      dctB.io.input.bits(i) := xybPixels(i).xybB
    }

    val acScale = Module(new AcQuantScaleSelector(c))
    acScale.io.config := io.config
    acScale.io.distance := distanceParams.io.params

    val quantizer = Module(new DctOnlyQuantizeBlock(c))
    quantizer.io.input.valid := state === startBlock
    quantizer.io.output.ready := true.B
    quantizer.io.input.bits.quant := acScale.io.params.rawQuant
    quantizer.io.input.bits.scaleQ16 := acScale.io.params.scaleQ16
    quantizer.io.input.bits.invQacQ16 := acScale.io.params.invQacQ16
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

    val quantized = Wire(Vec(3, Vec(blockSize, SInt(c.traceValueBits.W))))
    val numNonzeros = Wire(Vec(3, UInt(8.W)))
    for (channel <- 0 until 3) {
      numNonzeros(channel) := quantizer.io.output.bits.numNonzeros(channel)
      for (i <- 0 until blockSize) {
        quantized(channel)(i) := quantizer.io.output.bits.quantizedAc(channel)(i)
      }
    }

    (
      quantized,
      numNonzeros,
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
  val blockX = blockOrdinal - (blockOrdinal / xBlocksSafe) * xBlocksSafe
  val blockY = blockOrdinal / xBlocksSafe
  val westBlockX = Mux(blockX === 0.U, blockX, blockX - 1.U)
  val northBlockY = Mux(blockY === 0.U, blockY, blockY - 1.U)

  val (currentQuantized, currentNonzeros, currentValid) = quantizedBlockFor(blockX, blockY)
  val (_, westNonzeros, westValid) = quantizedBlockFor(westBlockX, blockY)
  val (_, northNonzeros, northValid) = quantizedBlockFor(blockX, northBlockY)
  val predictedNonzeros = Wire(Vec(3, UInt(8.W)))
  for (channel <- 0 until 3) {
    predictedNonzeros(channel) := Mux(
      blockX === 0.U,
      Mux(blockY === 0.U, 32.U, northNonzeros(channel)),
      Mux(blockY === 0.U, westNonzeros(channel), (northNonzeros(channel) +& westNonzeros(channel) + 1.U) >> 1)
    )
  }

  val blockTokens = Module(new DctOnlyAcBlockTokenTraceStage(c))
  blockTokens.io.input.valid := state === startBlock && currentValid && westValid && northValid
  blockTokens.io.input.bits.group := tokenOrdinal
  blockTokens.io.input.bits.predictedNonzeros := predictedNonzeros
  blockTokens.io.input.bits.numNonzeros := currentNonzeros
  blockTokens.io.input.bits.quantized := currentQuantized
  blockTokens.io.trace.ready := io.trace.ready && state === emitBlock

  io.input.ready := state === receiving && !configOutOfRange
  io.trace.valid := state === emitBlock && blockTokens.io.trace.valid
  io.trace.bits := blockTokens.io.trace.bits
  io.busy := state =/= receiving || received =/= 0.U
  io.overflow := overflow || configOutOfRange

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
      state := startBlock
      blockOrdinal := 0.U
      tokenOrdinal := 0.U
      latchedWidth := configWidth
      latchedHeight := configHeight
      xBlocks := nextPaddedWidth >> log2Ceil(blockDim)
      totalBlocks := (nextPaddedWidth >> log2Ceil(blockDim)) * (nextPaddedHeight >> log2Ceil(blockDim))
    }
  }

  when(state === startBlock && blockTokens.io.input.fire) {
    state := emitBlock
  }

  when(io.trace.fire) {
    tokenOrdinal := io.trace.bits.group + 1.U
  }

  when(state === emitBlock && !blockTokens.io.busy && !blockTokens.io.trace.valid) {
    val nextBlock = blockOrdinal + 1.U
    when(nextBlock === totalBlocks) {
      state := receiving
      received := 0.U
      blockOrdinal := 0.U
      tokenOrdinal := 0.U
      xBlocks := 0.U
      totalBlocks := 0.U
    }.otherwise {
      blockOrdinal := nextBlock
      state := startBlock
    }
  }

  when(io.input.fire && received >= numPixels.U) {
    overflow := true.B
  }
}
