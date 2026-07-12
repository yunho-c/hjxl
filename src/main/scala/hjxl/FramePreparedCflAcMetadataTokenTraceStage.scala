// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** Emits AC-metadata tokens using CFL maps estimated from prepared DCT blocks.
  *
  * This wrapper keeps the current prepared-block input contract but ignores the
  * caller-supplied scalar CFL fields. It estimates one Y-to-X/Y-to-B pair per
  * 64x64 tile from the prepared X/Y/B DCT coefficients, then feeds those maps
  * plus the prepared raw-quant field into `FramePreparedAcMetadataTokenTraceStage`.
  */
class FramePreparedCflAcMetadataTokenTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  private val blockDim = HjxlConstants.BlockDim
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

  val rawQuant = Reg(Vec(maxBlocks, UInt(8.W)))
  val ytox = Reg(Vec(maxTiles, SInt(8.W)))
  val ytob = Reg(Vec(maxTiles, SInt(8.W)))

  val receiving :: captureCfl :: feedMetadata :: emitMetadata :: Nil = Enum(4)
  val state = RegInit(receiving)
  val receivedBlocks = RegInit(0.U(blockCountBits.W))
  val totalBlocks = RegInit(0.U(blockCountBits.W))
  val metadataBlock = RegInit(0.U(blockCountBits.W))
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

  val metadataTokens = Module(new FramePreparedAcMetadataTokenTraceStage(c))
  metadataTokens.io.config := activeConfig
  metadataTokens.io.trace.ready := state === emitMetadata && io.trace.ready

  val metadataTile = CflTileGeometry.blockTile(metadataBlock, xBlocks, xTiles)

  metadataTokens.io.input.valid := state === feedMetadata
  metadataTokens.io.input.bits.rawQuant := rawQuant(blockIndex(metadataBlock))
  metadataTokens.io.input.bits.ytox := ytoxAt(metadataTile)
  metadataTokens.io.input.bits.ytob := ytobAt(metadataTile)

  io.input.ready :=
    state === receiving &&
      !configOutOfRange &&
      receivedBlocks < nextTotalBlocks &&
      cflMaps.io.input.ready
  io.trace.valid := state === emitMetadata && metadataTokens.io.trace.valid
  io.trace.bits := metadataTokens.io.trace.bits
  io.traceLast := state === emitMetadata && metadataTokens.io.traceLast
  io.busy :=
    state =/= receiving || receivedBlocks =/= 0.U || cflMaps.io.busy || metadataTokens.io.busy
  io.overflow := configOutOfRange || cflMaps.io.overflow || metadataTokens.io.overflow

  when(configOutOfRange && state === receiving) {
    receivedBlocks := 0.U
    totalBlocks := 0.U
  }.elsewhen(io.input.fire) {
    when(receivedBlocks === 0.U) {
      latchedConfig := io.config
      totalBlocks := nextTotalBlocks(blockCountBits - 1, 0)
      xBlocks := nextXBlocks
      xTiles := nextXTiles
    }
    rawQuant(blockIndex(receivedBlocks)) := io.input.bits.quant

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
      state := feedMetadata
      metadataBlock := 0.U
    }
  }

  when(state === feedMetadata && metadataTokens.io.input.fire) {
    val nextBlock = metadataBlock + 1.U
    metadataBlock := nextBlock
    when(nextBlock === totalBlocks) {
      state := emitMetadata
    }
  }

  when(state === emitMetadata && metadataTokens.io.trace.fire && metadataTokens.io.traceLast) {
    state := receiving
    receivedBlocks := 0.U
    totalBlocks := 0.U
    metadataBlock := 0.U
    xBlocks := 0.U
    xTiles := 0.U
  }
}
