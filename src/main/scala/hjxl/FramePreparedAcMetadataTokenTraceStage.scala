// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

class PreparedAcMetadataBlockInput(c: HjxlConfig) extends Bundle {
  val rawQuant = UInt(8.W)
  val ytox = SInt(8.W)
  val ytob = SInt(8.W)
}

/** Emits AC-metadata tokens from prepared all-DCT quant/CFL metadata.
  *
  * Input blocks are supplied in raster order. `rawQuant` is stored per block;
  * `ytox` and `ytob` are stored per tile, with repeated writes from blocks in
  * the same tile expected to carry the same value.
  */
class FramePreparedAcMetadataTokenTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  private val blockDim = HjxlConstants.BlockDim
  private val tileDim = HjxlConstants.TileDim
  private val tileBlocks = tileDim / blockDim
  private val maxXBlocks = c.maxFrameWidth / blockDim
  private val maxYBlocks = c.maxFrameHeight / blockDim
  private val maxBlocks = maxXBlocks * maxYBlocks
  private val maxXTiles = (c.maxFrameWidth + tileDim - 1) / tileDim
  private val maxYTiles = (c.maxFrameHeight + tileDim - 1) / tileDim
  private val maxTiles = maxXTiles * maxYTiles
  private val blockCountBits = log2Ceil(maxBlocks + 1)
  private val blockIndexBits = math.max(1, log2Ceil(maxBlocks))
  private val tileIndexBits = math.max(1, log2Ceil(maxTiles))
  private val widthBits = log2Ceil(c.maxFrameWidth + 1)
  private val heightBits = log2Ceil(c.maxFrameHeight + 1)

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new PreparedAcMetadataBlockInput(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val rawQuant = Reg(Vec(maxBlocks, UInt(8.W)))
  val ytox = Reg(Vec(maxTiles, SInt(8.W)))
  val ytob = Reg(Vec(maxTiles, SInt(8.W)))

  val receiving :: emitting :: Nil = Enum(2)
  val state = RegInit(receiving)
  val received = RegInit(0.U(blockCountBits.W))
  val emitIndex = RegInit(0.U(32.W))
  val xBlocks = RegInit(0.U(32.W))
  val totalBlocks = RegInit(0.U(32.W))
  val xTiles = RegInit(0.U(32.W))
  val totalTiles = RegInit(0.U(32.W))
  val overflow = RegInit(false.B)

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

  val configWidth = io.config.xsize(widthBits - 1, 0)
  val configHeight = io.config.ysize(heightBits - 1, 0)
  val nextXBlocks = ceilDiv(configWidth, blockDim)
  val nextYBlocks = ceilDiv(configHeight, blockDim)
  val nextTotalBlocks = nextXBlocks * nextYBlocks
  val nextXTilesRaw = ceilDiv(configWidth, tileDim)
  val nextYTilesRaw = ceilDiv(configHeight, tileDim)
  val nextXTiles = Mux(nextXTilesRaw === 0.U, 1.U, nextXTilesRaw)
  val nextYTiles = Mux(nextYTilesRaw === 0.U, 1.U, nextYTilesRaw)
  val nextTotalTiles = nextXTiles * nextYTiles
  val configOutOfRange =
    io.config.xsize === 0.U || io.config.ysize === 0.U ||
      io.config.xsize > c.maxFrameWidth.U || io.config.ysize > c.maxFrameHeight.U ||
      nextXBlocks > maxXBlocks.U || nextYBlocks > maxYBlocks.U ||
      nextXTiles > maxXTiles.U || nextYTiles > maxYTiles.U

  val receiveXBlocksSafe = Mux(nextXBlocks === 0.U, 1.U, nextXBlocks)
  val receiveBlockX = received - (received / receiveXBlocksSafe) * receiveXBlocksSafe
  val receiveBlockY = received / receiveXBlocksSafe
  val receiveTile = (receiveBlockY / tileBlocks.U) * nextXTiles + (receiveBlockX / tileBlocks.U)

  val cflTokenCount = totalTiles * 2.U
  val strategyStart = cflTokenCount
  val quantStart = strategyStart + totalBlocks
  val blockMetadataStart = quantStart + totalBlocks
  val totalTokens = blockMetadataStart + totalBlocks
  val isCfl = emitIndex < strategyStart
  val isStrategy = emitIndex >= strategyStart && emitIndex < quantStart
  val isQuant = emitIndex >= quantStart && emitIndex < blockMetadataStart
  val cflMap = emitIndex / Mux(totalTiles === 0.U, 1.U, totalTiles)
  val cflOrdinal = emitIndex - cflMap * Mux(totalTiles === 0.U, 1.U, totalTiles)
  val quantOrdinal = emitIndex - quantStart
  val tileX = cflOrdinal - (cflOrdinal / Mux(xTiles === 0.U, 1.U, xTiles)) * Mux(xTiles === 0.U, 1.U, xTiles)
  val tileY = cflOrdinal / Mux(xTiles === 0.U, 1.U, xTiles)
  val westTile = cflOrdinal - 1.U
  val northTile = cflOrdinal - Mux(xTiles === 0.U, 1.U, xTiles)
  val northwestTile = northTile - 1.U
  val currentCfl = Mux(cflMap === 0.U, ytoxAt(cflOrdinal), ytobAt(cflOrdinal))
  val westCfl = Mux(
    tileX === 0.U,
    Mux(tileY === 0.U, 0.S, Mux(cflMap === 0.U, ytoxAt(northTile), ytobAt(northTile))),
    Mux(cflMap === 0.U, ytoxAt(westTile), ytobAt(westTile))
  )
  val northCfl = Mux(
    tileY === 0.U,
    westCfl,
    Mux(cflMap === 0.U, ytoxAt(northTile), ytobAt(northTile))
  )
  val northwestCfl = Mux(
    tileX === 0.U || tileY === 0.U,
    westCfl,
    Mux(cflMap === 0.U, ytoxAt(northwestTile), ytobAt(northwestTile))
  )
  val cflResidual = currentCfl - DcTokenize.clampedGradient(northCfl, westCfl, northwestCfl)

  val rawQuantCurrent = rawQuant(blockIndex(quantOrdinal))
  val quantCurrent = Cat(0.U(1.W), rawQuantCurrent - 1.U).asSInt
  val quantLeftValue = Mux(
    quantOrdinal === 0.U,
    Tokenize.DctStrategyCode.U,
    rawQuant(blockIndex(quantOrdinal - 1.U)) - 1.U
  )
  val quantResidual = quantCurrent - Cat(0.U(1.W), quantLeftValue).asSInt

  io.input.ready := state === receiving && !configOutOfRange
  io.trace.valid := state === emitting
  io.traceLast := state === emitting && totalTokens =/= 0.U && emitIndex === totalTokens - 1.U
  io.busy := state =/= receiving || received =/= 0.U
  io.overflow := overflow || configOutOfRange
  io.trace.bits.stage := TraceStage.AcMetadataTokens.U
  io.trace.bits.group := emitIndex(c.groupBits - 1, 0)
  io.trace.bits.index := Mux(
    isCfl,
    2.U - cflMap,
    Mux(isStrategy, Tokenize.strategyContext(Tokenize.DctStrategyCode.U), Mux(isQuant, Tokenize.quantFieldContext(quantLeftValue), 0.U))
  )
  val strategyValue = Tokenize.packSigned(0.S, c.traceValueBits)
  val cflValue = Tokenize.packSigned(cflResidual, c.traceValueBits)
  val quantValue = Tokenize.packSigned(quantResidual, c.traceValueBits)
  val blockMetadataValue = Tokenize.packSigned(Tokenize.DefaultBlockMetadata.S, c.traceValueBits)
  io.trace.bits.value := MuxCase(
    blockMetadataValue,
    Seq(
      isCfl -> cflValue,
      isStrategy -> strategyValue,
      isQuant -> quantValue
    )
  )

  when(configOutOfRange) {
    received := 0.U
    state := receiving
  }.elsewhen(io.input.fire) {
    rawQuant(blockIndex(received)) := io.input.bits.rawQuant
    if (maxTiles == 1) {
      ytox(0) := io.input.bits.ytox
      ytob(0) := io.input.bits.ytob
    } else {
      ytox(tileIndex(receiveTile)) := io.input.bits.ytox
      ytob(tileIndex(receiveTile)) := io.input.bits.ytob
    }
    val nextReceived = received + 1.U
    received := nextReceived
    when(nextReceived === nextTotalBlocks) {
      state := emitting
      emitIndex := 0.U
      xBlocks := nextXBlocks
      totalBlocks := nextTotalBlocks
      xTiles := nextXTiles
      totalTiles := nextTotalTiles
    }
  }

  when(io.trace.fire) {
    val nextEmit = emitIndex + 1.U
    emitIndex := nextEmit
    when(nextEmit === totalTokens) {
      state := receiving
      received := 0.U
      emitIndex := 0.U
      xBlocks := 0.U
      totalBlocks := 0.U
      xTiles := 0.U
      totalTiles := 0.U
    }
  }

  when(io.input.fire && received >= maxBlocks.U) {
    overflow := true.B
  }
}
