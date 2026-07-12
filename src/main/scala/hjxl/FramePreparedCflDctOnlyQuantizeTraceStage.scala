// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** Quantizes prepared DCT-only blocks with internally estimated CFL maps.
  *
  * The input contract matches `FramePreparedDctOnlyQuantizeTraceStage`, but the
  * caller-supplied `ytox`/`ytob` fields are ignored. This stage first estimates
  * one CFL pair per 64x64 tile from the prepared DCT coefficients, then replays
  * buffered blocks through `DctOnlyQuantizeTraceStage` with the estimated tile
  * multipliers substituted into each block.
  */
class FramePreparedCflDctOnlyQuantizeTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  private val blockDim = HjxlConstants.BlockDim
  private val blockSize = blockDim * blockDim
  private val tileDim = HjxlConstants.TileDim
  private val tileBlocks = tileDim / blockDim
  private val maxXBlocks = c.maxFrameWidth / blockDim
  private val maxYBlocks = c.maxFrameHeight / blockDim
  private val maxBlocks = maxXBlocks * maxYBlocks
  private val maxXTiles = (maxXBlocks + tileBlocks - 1) / tileBlocks
  private val maxYTiles = (maxYBlocks + tileBlocks - 1) / tileBlocks
  private val maxTiles = maxXTiles * maxYTiles
  private val blockCountBits = log2Ceil(maxBlocks + 1)
  private val blockIndexBits = math.max(1, log2Ceil(maxBlocks))
  private val tileIndexBits = math.max(1, log2Ceil(maxTiles))
  private val widthBits = log2Ceil(c.maxFrameWidth + 1)
  private val heightBits = log2Ceil(c.maxFrameHeight + 1)

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new DctOnlyQuantizeBlockInput(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  private def ceilDiv(value: UInt, divisor: Int): UInt =
    (value +& (divisor - 1).U) / divisor.U

  private def blockIndex(value: UInt): UInt =
    value(blockIndexBits - 1, 0)

  private def tileIndex(value: UInt): UInt =
    value(tileIndexBits - 1, 0)

  private def ytoxAt(value: UInt): SInt =
    if (maxTiles == 1) ytox(0) else ytox(tileIndex(value))

  private def ytobAt(value: UInt): SInt =
    if (maxTiles == 1) ytob(0) else ytob(tileIndex(value))

  val coefficients = Reg(Vec(maxBlocks, Vec(3, Vec(blockSize, SInt(c.traceValueBits.W)))))
  val quant = Reg(Vec(maxBlocks, UInt(8.W)))
  val scaleQ16 = Reg(Vec(maxBlocks, UInt(16.W)))
  val invQacQ16 = Reg(Vec(maxBlocks, UInt(32.W)))
  val invDcFactorQ16 = Reg(Vec(maxBlocks, Vec(3, UInt(32.W))))
  val xQmMultiplierQ16 = Reg(Vec(maxBlocks, UInt(32.W)))
  val ytox = Reg(Vec(maxTiles, SInt(8.W)))
  val ytob = Reg(Vec(maxTiles, SInt(8.W)))

  val receiving :: captureCfl :: feedQuantize :: emitQuantize :: Nil = Enum(4)
  val state = RegInit(receiving)
  val receivedBlocks = RegInit(0.U(blockCountBits.W))
  val quantizeBlock = RegInit(0.U(blockCountBits.W))
  val totalBlocks = RegInit(0.U(blockCountBits.W))
  val xBlocks = RegInit(0.U(32.W))
  val xTiles = RegInit(0.U(32.W))
  val latchedConfig = Reg(new FrameConfig(c))

  val transactionActive = state =/= receiving || receivedBlocks =/= 0.U
  val activeConfig = Wire(new FrameConfig(c))
  activeConfig := Mux(transactionActive, latchedConfig, io.config)

  val configWidth = activeConfig.xsize(widthBits - 1, 0)
  val configHeight = activeConfig.ysize(heightBits - 1, 0)
  val nextXBlocks = ceilDiv(configWidth, blockDim)
  val nextYBlocks = ceilDiv(configHeight, blockDim)
  val nextTotalBlocks = nextXBlocks * nextYBlocks
  val nextXTilesRaw = ceilDiv(configWidth, tileDim)
  val nextYTilesRaw = ceilDiv(configHeight, tileDim)
  val nextXTiles = Mux(nextXTilesRaw === 0.U, 1.U, nextXTilesRaw)
  val nextYTiles = Mux(nextYTilesRaw === 0.U, 1.U, nextYTilesRaw)
  val configOutOfRange =
    activeConfig.xsize === 0.U || activeConfig.ysize === 0.U ||
      activeConfig.xsize > c.maxFrameWidth.U || activeConfig.ysize > c.maxFrameHeight.U ||
      nextXBlocks > maxXBlocks.U || nextYBlocks > maxYBlocks.U ||
      nextXTiles > maxXTiles.U || nextYTiles > maxYTiles.U

  val cflMaps = Module(new FramePreparedCflMapTraceStage(c))
  cflMaps.io.config := activeConfig
  cflMaps.io.input.valid :=
    state === receiving && io.input.valid && !configOutOfRange && receivedBlocks < nextTotalBlocks
  cflMaps.io.input.bits := io.input.bits
  cflMaps.io.trace.ready := state === captureCfl

  val quantizer = Module(new DctOnlyQuantizeTraceStage(c, c.preparedDctCoefficientFractionBits))
  val quantizeTile = CflTileGeometry.blockTile(quantizeBlock, xBlocks, xTiles)
  val quantizeIndex = blockIndex(quantizeBlock)

  quantizer.io.input.valid := state === feedQuantize
  quantizer.io.input.bits.group := quantizeBlock
  quantizer.io.input.bits.quantize.coefficients := coefficients(quantizeIndex)
  quantizer.io.input.bits.quantize.quant := quant(quantizeIndex)
  quantizer.io.input.bits.quantize.scaleQ16 := scaleQ16(quantizeIndex)
  quantizer.io.input.bits.quantize.invQacQ16 := invQacQ16(quantizeIndex)
  quantizer.io.input.bits.quantize.invDcFactorQ16 := invDcFactorQ16(quantizeIndex)
  quantizer.io.input.bits.quantize.xQmMultiplierQ16 := xQmMultiplierQ16(quantizeIndex)
  quantizer.io.input.bits.quantize.ytox := ytoxAt(quantizeTile)
  quantizer.io.input.bits.quantize.ytob := ytobAt(quantizeTile)
  quantizer.io.trace.ready := state === emitQuantize && io.trace.ready

  io.input.ready :=
    state === receiving &&
      !configOutOfRange &&
      receivedBlocks < nextTotalBlocks &&
      cflMaps.io.input.ready
  io.trace.valid := state === emitQuantize && quantizer.io.trace.valid
  io.trace.bits := quantizer.io.trace.bits
  val quantizerLastTrace =
    quantizer.io.trace.valid &&
      quantizer.io.trace.bits.stage === TraceStage.NumNonzeros.U &&
      quantizer.io.trace.bits.index === 2.U
  io.traceLast := io.trace.valid && quantizerLastTrace && quantizeBlock === totalBlocks - 1.U
  io.busy :=
    state =/= receiving || receivedBlocks =/= 0.U || cflMaps.io.busy || quantizer.io.busy
  io.overflow := configOutOfRange || cflMaps.io.overflow

  when(configOutOfRange && state === receiving) {
    receivedBlocks := 0.U
    totalBlocks := 0.U
  }.elsewhen(io.input.fire) {
    val index = blockIndex(receivedBlocks)
    coefficients(index) := io.input.bits.coefficients
    quant(index) := io.input.bits.quant
    scaleQ16(index) := io.input.bits.scaleQ16
    invQacQ16(index) := io.input.bits.invQacQ16
    invDcFactorQ16(index) := io.input.bits.invDcFactorQ16
    xQmMultiplierQ16(index) := io.input.bits.xQmMultiplierQ16

    when(receivedBlocks === 0.U) {
      latchedConfig := io.config
      totalBlocks := nextTotalBlocks(blockCountBits - 1, 0)
      xBlocks := nextXBlocks
      xTiles := nextXTiles
    }

    val nextReceived = receivedBlocks + 1.U
    receivedBlocks := nextReceived
    when(nextReceived === nextTotalBlocks) {
      state := captureCfl
    }
  }

  when(cflMaps.io.trace.fire) {
    when(cflMaps.io.trace.bits.stage === TraceStage.YtoxMap.U) {
      if (maxTiles == 1) {
        ytox(0) := cflMaps.io.trace.bits.value(7, 0).asSInt
      } else {
        ytox(tileIndex(cflMaps.io.trace.bits.index)) := cflMaps.io.trace.bits.value(7, 0).asSInt
      }
    }.elsewhen(cflMaps.io.trace.bits.stage === TraceStage.YtobMap.U) {
      if (maxTiles == 1) {
        ytob(0) := cflMaps.io.trace.bits.value(7, 0).asSInt
      } else {
        ytob(tileIndex(cflMaps.io.trace.bits.index)) := cflMaps.io.trace.bits.value(7, 0).asSInt
      }
    }
    when(cflMaps.io.traceLast) {
      state := feedQuantize
      quantizeBlock := 0.U
    }
  }

  when(state === feedQuantize && quantizer.io.input.fire) {
    state := emitQuantize
  }

  when(state === emitQuantize && quantizer.io.trace.fire && quantizerLastTrace) {
    val nextBlock = quantizeBlock + 1.U
    when(nextBlock === totalBlocks) {
      state := receiving
      receivedBlocks := 0.U
      totalBlocks := 0.U
      quantizeBlock := 0.U
      xBlocks := 0.U
      xTiles := 0.U
    }.otherwise {
      quantizeBlock := nextBlock
      state := feedQuantize
    }
  }
}
