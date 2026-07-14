// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

class PreparedVarDctAcMetadataOwner(c: HjxlConfig) extends Bundle {
  val blockX = UInt(c.coordBits.W)
  val blockY = UInt(c.coordBits.W)
  val blockOrdinal = UInt(c.groupBits.W)
  val last = Bool()
  val strategy = UInt(2.W)
  val rawQuant = UInt(8.W)
  val ytox = SInt(8.W)
  val ytob = SInt(8.W)
}

/** Emits first-block-aware VarDCT AC metadata.
  *
  * Strategy and quant-field tokens are emitted once per owner. Continuation
  * cells are represented by the selected shape, while the fixed block-metadata
  * literal remains one token per raster cell as required by libjxl-tiny.
  */
class FramePreparedVarDctAcMetadataTokenTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  private val blockDim = HjxlConstants.BlockDim
  private val tileDim = HjxlConstants.TileDim
  private val tileBlocks = tileDim / blockDim
  private val maxXBlocks = c.maxFrameWidth / blockDim
  private val maxYBlocks = c.maxFrameHeight / blockDim
  private val maxBlocks = maxXBlocks * maxYBlocks
  private val maxXTiles = (c.maxFrameWidth + tileDim - 1) / tileDim
  private val maxYTiles = (c.maxFrameHeight + tileDim - 1) / tileDim
  private val maxTiles = maxXTiles * maxYTiles
  private val blockBits = math.max(1, log2Ceil(maxBlocks))
  private val blockCountBits = math.max(1, log2Ceil(maxBlocks + 1))
  private val tileBits = math.max(1, log2Ceil(maxTiles))

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new PreparedVarDctAcMetadataOwner(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  private def ceilDiv(value: UInt, divisor: Int): UInt =
    (value +& (divisor - 1).U) / divisor.U
  private def blockIndex(value: UInt): UInt = value(blockBits - 1, 0)
  private def blockAt[T <: Data](values: Vec[T], value: UInt): T =
    if (maxBlocks == 1) values(0) else values(blockIndex(value))
  private def tileIndex(value: UInt): UInt = value(tileBits - 1, 0)
  private def tileSeenAt(value: UInt): Bool =
    if (maxTiles == 1) tileSeen(0) else tileSeen(tileIndex(value))
  private def ytoxAt(value: UInt): SInt =
    if (maxTiles == 1) ytox(0) else ytox(tileIndex(value))
  private def ytobAt(value: UInt): SInt =
    if (maxTiles == 1) ytob(0) else ytob(tileIndex(value))

  val ownerStrategy = Reg(Vec(maxBlocks, UInt(2.W)))
  val ownerRawQuant = Reg(Vec(maxBlocks, UInt(8.W)))
  val ytox = Reg(Vec(maxTiles, SInt(8.W)))
  val ytob = Reg(Vec(maxTiles, SInt(8.W)))
  val tileSeen = RegInit(VecInit(Seq.fill(maxTiles)(false.B)))
  val covered = RegInit(VecInit(Seq.fill(maxBlocks)(false.B)))

  val receiving :: emitting :: Nil = Enum(2)
  val state = RegInit(receiving)
  val frameActive = RegInit(false.B)
  val xBlocks = RegInit(0.U(blockCountBits.W))
  val yBlocks = RegInit(0.U(blockCountBits.W))
  val totalBlocks = RegInit(0.U(blockCountBits.W))
  val xTiles = RegInit(0.U(blockCountBits.W))
  val totalTiles = RegInit(0.U(blockCountBits.W))
  val ownerCount = RegInit(0.U(blockCountBits.W))
  val emitIndex = RegInit(0.U(32.W))
  val overflow = RegInit(false.B)

  val nextXBlocks = ceilDiv(io.config.xsize, blockDim)
  val nextYBlocks = ceilDiv(io.config.ysize, blockDim)
  val nextTotalBlocks = nextXBlocks * nextYBlocks
  val nextXTilesRaw = ceilDiv(io.config.xsize, tileDim)
  val nextYTilesRaw = ceilDiv(io.config.ysize, tileDim)
  val nextXTiles = Mux(nextXTilesRaw === 0.U, 1.U, nextXTilesRaw)
  val nextYTiles = Mux(nextYTilesRaw === 0.U, 1.U, nextYTilesRaw)
  val nextTotalTiles = nextXTiles * nextYTiles
  val configOutOfRange =
    io.config.xsize === 0.U || io.config.ysize === 0.U ||
      io.config.xsize > c.maxFrameWidth.U || io.config.ysize > c.maxFrameHeight.U ||
      nextXBlocks > maxXBlocks.U || nextYBlocks > maxYBlocks.U ||
      nextXTiles > maxXTiles.U || nextYTiles > maxYTiles.U

  val activeXBlocks = Mux(frameActive, xBlocks, nextXBlocks)
  val activeYBlocks = Mux(frameActive, yBlocks, nextYBlocks)
  val activeTotalBlocks = Mux(frameActive, totalBlocks, nextTotalBlocks)
  val activeXTiles = Mux(frameActive, xTiles, nextXTiles)
  val strategy = io.input.bits.strategy
  val isVertical = strategy === AcStrategyCode.Dct16x8.U
  val isHorizontal = strategy === AcStrategyCode.Dct8x16.U
  val isRectangular = isVertical || isHorizontal
  val ownerOrdinalWide = io.input.bits.blockY * activeXBlocks + io.input.bits.blockX
  val ownerOrdinal = ownerOrdinalWide(blockCountBits - 1, 0)
  val secondOrdinal = Mux(isVertical, ownerOrdinal + activeXBlocks, ownerOrdinal + 1.U)
  val ownerTile =
    (io.input.bits.blockY / tileBlocks.U) * activeXTiles + (io.input.bits.blockX / tileBlocks.U)
  val ownerTileAddress = tileIndex(ownerTile)
  val coordinateInBounds =
    io.input.bits.blockX < activeXBlocks && io.input.bits.blockY < activeYBlocks
  val rectangleInBounds = Mux(
    isVertical,
    io.input.bits.blockY + 1.U < activeYBlocks,
    Mux(isHorizontal, io.input.bits.blockX + 1.U < activeXBlocks, true.B)
  )
  val ownerAlreadyCovered = Mux(coordinateInBounds, blockAt(covered, ownerOrdinal), true.B)
  val secondAlreadyCovered =
    Mux(isRectangular && rectangleInBounds, blockAt(covered, secondOrdinal), false.B)
  val earlierUncovered = (0 until maxBlocks).map { index =>
    index.U < ownerOrdinal && index.U < activeTotalBlocks && !covered(index)
  }.reduce(_ || _)
  val tileConsistent =
    !tileSeenAt(ownerTile) ||
      (ytoxAt(ownerTile) === io.input.bits.ytox && ytobAt(ownerTile) === io.input.bits.ytob)
  val geometryValid =
    !configOutOfRange && strategy <= AcStrategyCode.Dct8x16.U && coordinateInBounds && rectangleInBounds &&
      io.input.bits.blockOrdinal === ownerOrdinal && !ownerAlreadyCovered && !secondAlreadyCovered &&
      !earlierUncovered && tileConsistent

  val coveredAfter = Wire(Vec(maxBlocks, Bool()))
  coveredAfter := covered
  when(geometryValid) {
    blockAt(coveredAfter, ownerOrdinal) := true.B
    when(isRectangular) {
      blockAt(coveredAfter, secondOrdinal) := true.B
    }
  }
  val allCoveredAfter = (0 until maxBlocks).map { index =>
    index.U >= activeTotalBlocks || coveredAfter(index)
  }.reduce(_ && _)
  val validRecord = geometryValid && io.input.bits.last === allCoveredAfter

  val cflTokenCount = totalTiles * 2.U
  val strategyStart = cflTokenCount
  val quantStart = strategyStart + ownerCount
  val blockMetadataStart = quantStart + ownerCount
  val totalTokens = blockMetadataStart + totalBlocks
  val isCfl = emitIndex < strategyStart
  val isStrategy = emitIndex >= strategyStart && emitIndex < quantStart
  val isQuant = emitIndex >= quantStart && emitIndex < blockMetadataStart
  val cflMap = emitIndex / Mux(totalTiles === 0.U, 1.U, totalTiles)
  val cflOrdinal = emitIndex - cflMap * Mux(totalTiles === 0.U, 1.U, totalTiles)
  val ownerOrdinalInPhase = Mux(isStrategy, emitIndex - strategyStart, emitIndex - quantStart)
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

  val strategyCode = Tokenize.strategyCode(blockAt(ownerStrategy, ownerOrdinalInPhase))
  val strategyLeft = Mux(
    ownerOrdinalInPhase === 0.U,
    0.U,
    Tokenize.strategyCode(blockAt(ownerStrategy, ownerOrdinalInPhase - 1.U))
  )
  val quantCurrent = Cat(0.U(1.W), blockAt(ownerRawQuant, ownerOrdinalInPhase) - 1.U).asSInt
  val quantLeft = Mux(
    ownerOrdinalInPhase === 0.U,
    Tokenize.strategyCode(ownerStrategy(0)),
    blockAt(ownerRawQuant, ownerOrdinalInPhase - 1.U) - 1.U
  )
  val quantResidual = quantCurrent - Cat(0.U(1.W), quantLeft).asSInt

  io.input.ready := state === receiving
  io.trace.valid := state === emitting
  io.trace.bits.stage := TraceStage.AcMetadataTokens.U
  io.trace.bits.group := emitIndex(c.groupBits - 1, 0)
  io.trace.bits.index := Mux(
    isCfl,
    2.U - cflMap,
    Mux(isStrategy, Tokenize.strategyContext(strategyLeft), Mux(isQuant, Tokenize.quantFieldContext(quantLeft), 0.U))
  )
  io.trace.bits.value := MuxCase(
    Tokenize.packSigned(Tokenize.DefaultBlockMetadata.S, c.traceValueBits),
    Seq(
      isCfl -> Tokenize.packSigned(cflResidual, c.traceValueBits),
      isStrategy -> Tokenize.packSigned(Cat(0.U(1.W), strategyCode).asSInt, c.traceValueBits),
      isQuant -> Tokenize.packSigned(quantResidual, c.traceValueBits)
    )
  )
  io.traceLast := state === emitting && totalTokens =/= 0.U && emitIndex === totalTokens - 1.U
  io.busy := state =/= receiving || frameActive
  io.overflow := overflow || configOutOfRange

  when(io.input.fire) {
    when(validRecord) {
      blockAt(ownerStrategy, ownerCount) := strategy
      blockAt(ownerRawQuant, ownerCount) := io.input.bits.rawQuant
      if (maxTiles == 1) {
        ytox(0) := io.input.bits.ytox
        ytob(0) := io.input.bits.ytob
        tileSeen(0) := true.B
      } else {
        ytox(ownerTileAddress) := io.input.bits.ytox
        ytob(ownerTileAddress) := io.input.bits.ytob
        tileSeen(ownerTileAddress) := true.B
      }
      covered := coveredAfter
      ownerCount := ownerCount + 1.U
      when(!frameActive) {
        frameActive := true.B
        xBlocks := nextXBlocks(blockCountBits - 1, 0)
        yBlocks := nextYBlocks(blockCountBits - 1, 0)
        totalBlocks := nextTotalBlocks(blockCountBits - 1, 0)
        xTiles := nextXTiles(blockCountBits - 1, 0)
        totalTiles := nextTotalTiles(blockCountBits - 1, 0)
      }
      when(allCoveredAfter) {
        state := emitting
        emitIndex := 0.U
      }
    }.otherwise {
      overflow := true.B
      frameActive := false.B
      xBlocks := 0.U
      yBlocks := 0.U
      totalBlocks := 0.U
      xTiles := 0.U
      totalTiles := 0.U
      ownerCount := 0.U
      covered := VecInit(Seq.fill(maxBlocks)(false.B))
      tileSeen := VecInit(Seq.fill(maxTiles)(false.B))
    }
  }

  when(io.trace.fire) {
    val nextEmit = emitIndex + 1.U
    emitIndex := nextEmit
    when(nextEmit === totalTokens) {
      state := receiving
      frameActive := false.B
      xBlocks := 0.U
      yBlocks := 0.U
      totalBlocks := 0.U
      xTiles := 0.U
      totalTiles := 0.U
      ownerCount := 0.U
      emitIndex := 0.U
      covered := VecInit(Seq.fill(maxBlocks)(false.B))
      tileSeen := VecInit(Seq.fill(maxTiles)(false.B))
    }
  }

  when(io.input.fire && ownerCount >= maxBlocks.U) {
    overflow := true.B
  }
}
