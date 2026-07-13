// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** Estimates prepared-frame CFL maps from raster DCT-only block coefficients.
  *
  * This is the first frame-level scheduler for real CFL map arithmetic. It
  * accepts the prepared DCT-only block payload in raster order, buffers the
  * X/Y/B coefficients, then streams each 64x64 tile through
  * `CflTileCoefficientTraceStage`. Prepared inputs remain Q16 by default; an
  * explicit fractional-width override is rescaled at the estimator boundary.
  * The generated trace order is one `YtoxMap` row followed by one `YtobMap`
  * row per tile unless a compile-time stage filter selects one map.
  */
class FramePreparedCflMapTraceStage(
    c: HjxlConfig = HjxlConfig(),
    coefficientFractionBitsOverride: Option[Int] = None,
    traceStageFilter: Option[Int] = None
) extends Module {
  private val activeCoefficientFractionBits =
    coefficientFractionBitsOverride.getOrElse(c.preparedDctCoefficientFractionBits)
  require(activeCoefficientFractionBits > 0, "coefficientFractionBits must be positive")
  require(
    activeCoefficientFractionBits < c.traceValueBits,
    "coefficientFractionBits must fit in traceValueBits"
  )
  traceStageFilter.foreach { stage =>
    require(
      stage == TraceStage.YtoxMap || stage == TraceStage.YtobMap,
      "traceStageFilter must select YtoxMap or YtobMap"
    )
  }
  private val blockDim = HjxlConstants.BlockDim
  private val blockSize = blockDim * blockDim
  private val tileDim = HjxlConstants.TileDim
  private val blocksPerTile = tileDim / blockDim
  private val maxXBlocks = c.maxFrameWidth / blockDim
  private val maxYBlocks = c.maxFrameHeight / blockDim
  private val maxBlocks = maxXBlocks * maxYBlocks
  private val maxXTiles = (maxXBlocks + blocksPerTile - 1) / blocksPerTile
  private val maxYTiles = (maxYBlocks + blocksPerTile - 1) / blocksPerTile
  private val maxTiles = maxXTiles * maxYTiles
  private val blockIndexBits = math.max(1, log2Ceil(maxBlocks))
  private val widthBits = log2Ceil(c.maxFrameWidth + 1)
  private val heightBits = log2Ceil(c.maxFrameHeight + 1)
  private val estimatorFractionBits = 16
  private val estimatorCoefficientBits =
    c.traceValueBits + math.max(0, estimatorFractionBits - activeCoefficientFractionBits)
  private val weightedBits = math.max(48, estimatorCoefficientBits + 16)
  private val sumBits = math.max(64, weightedBits + 16)

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new DctOnlyQuantizeBlockInput(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  private def ceilDivBlock(value: UInt): UInt =
    (value +& (blockDim - 1).U) >> log2Ceil(blockDim)

  private def ceilDivTile(value: UInt): UInt =
    (value +& (tileDim - 1).U) / tileDim.U

  private def minUInt(a: UInt, b: UInt): UInt = Mux(a < b, a, b)

  private def coefficientForEstimator(value: SInt): SInt = {
    val scaled =
      if (activeCoefficientFractionBits < estimatorFractionBits) {
        value << (estimatorFractionBits - activeCoefficientFractionBits)
      } else if (activeCoefficientFractionBits > estimatorFractionBits) {
        value >> (activeCoefficientFractionBits - estimatorFractionBits)
      } else {
        value
      }
    scaled.asSInt.pad(estimatorCoefficientBits)
  }

  val coefficients = Reg(Vec(maxBlocks, Vec(3, Vec(blockSize, SInt(c.traceValueBits.W)))))

  private def storedCoefficient(block: UInt, channel: Int, coefficient: UInt): SInt =
    if (maxBlocks == 1) coefficients(0)(channel)(coefficient)
    else coefficients(block(blockIndexBits - 1, 0))(channel)(coefficient)

  val receiving :: streamingTile :: drainingTile :: Nil = Enum(3)
  val state = RegInit(receiving)
  val receivedBlock = RegInit(0.U(32.W))
  val xBlocks = RegInit(0.U(32.W))
  val yBlocks = RegInit(0.U(32.W))
  val xTiles = RegInit(0.U(32.W))
  val totalTiles = RegInit(0.U(32.W))
  val tileOrdinal = RegInit(0.U(32.W))
  val sampleOrdinal = RegInit(0.U(32.W))
  val overflow = RegInit(false.B)

  val configWidth = io.config.xsize(widthBits - 1, 0)
  val configHeight = io.config.ysize(heightBits - 1, 0)
  val nextXBlocks = ceilDivBlock(configWidth)
  val nextYBlocks = ceilDivBlock(configHeight)
  val nextTotalBlocks = nextXBlocks * nextYBlocks
  val nextXTilesRaw = ceilDivTile(configWidth)
  val nextYTilesRaw = ceilDivTile(configHeight)
  val nextXTiles = Mux(nextXTilesRaw === 0.U, 1.U, nextXTilesRaw)
  val nextYTiles = Mux(nextYTilesRaw === 0.U, 1.U, nextYTilesRaw)
  val configOutOfRange =
    io.config.xsize === 0.U || io.config.ysize === 0.U ||
      io.config.xsize > c.maxFrameWidth.U || io.config.ysize > c.maxFrameHeight.U ||
      nextXBlocks > maxXBlocks.U || nextYBlocks > maxYBlocks.U ||
      nextXTiles > maxXTiles.U || nextYTiles > maxYTiles.U

  val xTilesSafe = Mux(xTiles === 0.U, 1.U, xTiles)
  val tileX = tileOrdinal - (tileOrdinal / xTilesSafe) * xTilesSafe
  val tileY = tileOrdinal / xTilesSafe
  val tileBaseBlockX = tileX * blocksPerTile.U
  val tileBaseBlockY = tileY * blocksPerTile.U
  val remainingXBlocks = Mux(xBlocks > tileBaseBlockX, xBlocks - tileBaseBlockX, 0.U)
  val remainingYBlocks = Mux(yBlocks > tileBaseBlockY, yBlocks - tileBaseBlockY, 0.U)
  val tileValidXBlocks = minUInt(remainingXBlocks, blocksPerTile.U)
  val tileValidYBlocks = minUInt(remainingYBlocks, blocksPerTile.U)
  val tileValidXBlocksSafe = Mux(tileValidXBlocks === 0.U, 1.U, tileValidXBlocks)
  val tileSampleCount = tileValidXBlocks * tileValidYBlocks * blockSize.U
  val localBlockOrdinal = sampleOrdinal / blockSize.U
  val coefficientIndex = sampleOrdinal - localBlockOrdinal * blockSize.U
  val localBlockX = localBlockOrdinal - (localBlockOrdinal / tileValidXBlocksSafe) * tileValidXBlocksSafe
  val localBlockY = localBlockOrdinal / tileValidXBlocksSafe
  val blockX = tileBaseBlockX + localBlockX
  val blockY = tileBaseBlockY + localBlockY
  val blockIndex = blockY * xBlocks + blockX
  val coefficientIndexSafe = coefficientIndex(log2Ceil(blockSize) - 1, 0)

  val tileTrace = Module(
    new CflTileCoefficientTraceStage(
      c,
      coefficientBits = estimatorCoefficientBits,
      weightedBits = weightedBits,
      sumBits = sumBits
    )
  )

  tileTrace.io.input.valid := state === streamingTile && tileSampleCount =/= 0.U
  tileTrace.io.input.bits.tileIndex := tileOrdinal
  tileTrace.io.input.bits.coefficient.coefficientIndex := coefficientIndexSafe
  tileTrace.io.input.bits.coefficient.yCoeffQ16 :=
    coefficientForEstimator(storedCoefficient(blockIndex, 1, coefficientIndexSafe))
  tileTrace.io.input.bits.coefficient.xCoeffQ16 :=
    coefficientForEstimator(storedCoefficient(blockIndex, 0, coefficientIndexSafe))
  tileTrace.io.input.bits.coefficient.bCoeffQ16 :=
    coefficientForEstimator(storedCoefficient(blockIndex, 2, coefficientIndexSafe))
  tileTrace.io.input.bits.coefficient.last := sampleOrdinal === tileSampleCount - 1.U
  val selectedTrace = traceStageFilter match {
    case Some(stage) => tileTrace.io.trace.bits.stage === stage.U
    case None        => true.B
  }
  tileTrace.io.trace.ready :=
    state === drainingTile && Mux(selectedTrace, io.trace.ready, true.B)

  io.input.ready :=
    state === receiving &&
      !configOutOfRange &&
      receivedBlock < nextTotalBlocks &&
      receivedBlock < maxBlocks.U
  io.trace.valid := state === drainingTile && tileTrace.io.trace.valid && selectedTrace
  io.trace.bits := tileTrace.io.trace.bits
  io.traceLast := (traceStageFilter match {
    case Some(_) => io.trace.valid && tileOrdinal === totalTiles - 1.U
    case None    => io.trace.valid && tileTrace.io.traceLast && tileOrdinal === totalTiles - 1.U
  })
  io.busy := state =/= receiving || receivedBlock =/= 0.U || tileTrace.io.busy
  io.overflow := overflow || configOutOfRange

  when(configOutOfRange && state === receiving) {
    receivedBlock := 0.U
  }.elsewhen(io.input.fire) {
    val storeIndex = receivedBlock(blockIndexBits - 1, 0)
    for (channel <- 0 until 3) {
      for (coefficient <- 0 until blockSize) {
        if (maxBlocks == 1) {
          coefficients(0)(channel)(coefficient) := io.input.bits.coefficients(channel)(coefficient)
        } else {
          coefficients(storeIndex)(channel)(coefficient) := io.input.bits.coefficients(channel)(coefficient)
        }
      }
    }

    val nextReceivedBlock = receivedBlock + 1.U
    when(nextReceivedBlock === nextTotalBlocks) {
      state := streamingTile
      receivedBlock := 0.U
      xBlocks := nextXBlocks
      yBlocks := nextYBlocks
      xTiles := nextXTiles
      totalTiles := nextXTiles * nextYTiles
      tileOrdinal := 0.U
      sampleOrdinal := 0.U
    }.otherwise {
      receivedBlock := nextReceivedBlock
    }
  }

  when(tileTrace.io.input.fire) {
    when(sampleOrdinal === tileSampleCount - 1.U) {
      sampleOrdinal := 0.U
      state := drainingTile
    }.otherwise {
      sampleOrdinal := sampleOrdinal + 1.U
    }
  }

  when(tileTrace.io.trace.fire && tileTrace.io.traceLast) {
    val nextTileOrdinal = tileOrdinal + 1.U
    when(nextTileOrdinal === totalTiles) {
      state := receiving
      tileOrdinal := 0.U
      totalTiles := 0.U
      xBlocks := 0.U
      yBlocks := 0.U
      xTiles := 0.U
    }.otherwise {
      state := streamingTile
      tileOrdinal := nextTileOrdinal
      sampleOrdinal := 0.U
    }
  }
}
