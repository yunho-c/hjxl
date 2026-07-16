// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

object AqFuzzyErosionFixedPoint {
  val FractionBits = AqContrastFixedPoint.OutputFractionBits
  val ValueBits = 31
  val NeighborhoodSize = 9
  val MinimumCount = 4
  val SamplesPerBlock = 4
  val Divisor = 20
  val MaximumValue: BigInt = (BigInt(1) << ValueBits) - 1

  def weightedSumQ16(center: BigInt, neighborhood: Seq[BigInt]): BigInt = {
    require(neighborhood.length == NeighborhoodSize, "fuzzy erosion requires a 3x3 neighborhood")
    require(center >= 0 && center <= MaximumValue, "fuzzy erosion center must fit the Q16 domain")
    require(
      neighborhood.forall(value => value >= 0 && value <= MaximumValue),
      "fuzzy erosion neighborhood must fit the Q16 domain"
    )
    center + neighborhood.sorted.take(MinimumCount).sum
  }

  def blockQ16(weightedSums: Seq[BigInt]): BigInt = {
    require(weightedSums.length == SamplesPerBlock, "fuzzy erosion block requires one 2x2 sample group")
    ((weightedSums.sum + Divisor / 2) / Divisor).min(MaximumValue)
  }
}

/** The weighted numerator for one quarter-resolution fuzzy-erosion sample.
  *
  * The result is the center plus the four smallest values in the clamped 3x3
  * neighborhood. The center is also present in `neighborhood`, matching
  * libjxl-tiny's intentional center-plus-minima weighting. The frame scheduler
  * accumulates four such numerators and divides once by 20, which is
  * mathematically equivalent to the reference's 0.05 weighting while avoiding
  * intermediate Q16 rounding loss.
  */
class AqFuzzyErosionSample(valueBits: Int = AqFuzzyErosionFixedPoint.ValueBits) extends Module {
  import AqFuzzyErosionFixedPoint._

  require(valueBits >= ValueBits, "fuzzy erosion input must contain the positive signed-32 Q16 domain")

  val io = IO(new Bundle {
    val center = Input(UInt(valueBits.W))
    val neighborhood = Input(Vec(NeighborhoodSize, UInt(valueBits.W)))
    val weightedSum = Output(UInt((valueBits + 3).W))
  })

  var minima: Seq[UInt] = Seq.fill(MinimumCount)(((BigInt(1) << valueBits) - 1).U(valueBits.W))
  for (candidate <- io.neighborhood) {
    val previous = minima
    minima = Seq.tabulate(MinimumCount) { index =>
      val next = Wire(UInt(valueBits.W))
      if (index == 0) {
        next := Mux(candidate < previous.head, candidate, previous.head)
      } else {
        next := Mux(
          candidate < previous(index - 1),
          previous(index - 1),
          Mux(candidate < previous(index), candidate, previous(index))
        )
      }
      next
    }
  }

  val sumWidth = valueBits + 3
  val weightedSum = minima.foldLeft(io.center.pad(sumWidth)) { (sum, value) =>
    sum +& value.pad(sumWidth)
  }
  io.weightedSum := weightedSum
}

/** Buffers prepared row-major Q16 contrast cells and emits one eroded value per
  * padded 8x8 block.
  */
class FramePreparedAqFuzzyErosionTraceStage(
    c: HjxlConfig = HjxlConfig(),
    valueBits: Int = AqFuzzyErosionFixedPoint.ValueBits
) extends Module {
  import AqFuzzyErosionFixedPoint._

  private val cellDim = 4
  private val stripeCells = HjxlConstants.TileDim / cellDim
  private val stripeCellShift = log2Ceil(stripeCells)
  private val maxCellsX = c.maxFrameWidth / cellDim
  private val maxCellsY = c.maxFrameHeight / cellDim
  private val maxCells = maxCellsX * maxCellsY
  private val maxBlocks = (c.maxFrameWidth / HjxlConstants.BlockDim) *
    (c.maxFrameHeight / HjxlConstants.BlockDim)
  private val cellIndexBits = log2Ceil(maxCells)
  private val cellCountBits = log2Ceil(maxCells + 1)
  private val widthBits = log2Ceil(c.maxFrameWidth + 1)
  private val heightBits = log2Ceil(c.maxFrameHeight + 1)

  require(c.traceValueBits == 32, "AQ fuzzy-erosion trace packing requires signed 32-bit values")
  require(valueBits == ValueBits, "AQ fuzzy erosion currently requires positive signed-32 Q16 inputs")
  require(maxCells >= SamplesPerBlock, "configured frame must contain one 2x2 contrast-cell group")
  require(maxBlocks > 0, "configured frame must contain a padded block")

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(UInt(valueBits.W)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val cells = Reg(Vec(maxCells, UInt(valueBits.W)))
  val receiving :: eroding :: Nil = Enum(2)
  val state = RegInit(receiving)
  val receivedCells = RegInit(0.U(cellCountBits.W))
  val activeCellCount = RegInit(0.U(cellCountBits.W))
  val cellsX = RegInit(0.U(widthBits.W))
  val cellsY = RegInit(0.U(heightBits.W))
  val blocksX = RegInit(0.U(widthBits.W))
  val totalBlocks = RegInit(0.U(32.W))
  val blockOrdinal = RegInit(0.U(32.W))
  val currentBlockX = RegInit(0.U(widthBits.W))
  val currentBlockY = RegInit(0.U(heightBits.W))
  val sampleOrdinal = RegInit(0.U(log2Ceil(SamplesPerBlock).W))
  val blockAccumulator = RegInit(0.U((valueBits + 5).W))
  val outputValid = RegInit(false.B)
  val outputValue = RegInit(0.U(valueBits.W))
  val outputLast = RegInit(false.B)

  private def ceilToBlock(value: UInt): UInt = {
    val block = HjxlConstants.BlockDim.U
    ((value +& (block - 1.U)) / block) * block
  }

  val configWidth = io.config.xsize(widthBits - 1, 0)
  val configHeight = io.config.ysize(heightBits - 1, 0)
  val nextPaddedWidth = ceilToBlock(configWidth)(widthBits - 1, 0)
  val nextPaddedHeight = ceilToBlock(configHeight)(heightBits - 1, 0)
  val nextCellsX = nextPaddedWidth >> log2Ceil(cellDim)
  val nextCellsY = nextPaddedHeight >> log2Ceil(cellDim)
  val nextCellCount = nextCellsX * nextCellsY
  val nextBlocksX = nextPaddedWidth >> log2Ceil(HjxlConstants.BlockDim)
  val nextBlockCount = nextBlocksX * (nextPaddedHeight >> log2Ceil(HjxlConstants.BlockDim))
  val configOutOfRange =
    io.config.xsize === 0.U || io.config.ysize === 0.U ||
      io.config.xsize > c.maxFrameWidth.U || io.config.ysize > c.maxFrameHeight.U ||
      nextPaddedWidth > c.maxFrameWidth.U || nextPaddedHeight > c.maxFrameHeight.U
  val acceptingNewFrame = state === receiving && receivedCells === 0.U
  val selectedCellCount = Mux(acceptingNewFrame, nextCellCount, activeCellCount)

  io.input.ready := state === receiving && (!acceptingNewFrame || !configOutOfRange)
  io.trace.valid := outputValid
  io.trace.bits.stage := TraceStage.AqFuzzyErosion.U
  io.trace.bits.group := 0.U
  io.trace.bits.index := blockOrdinal
  io.trace.bits.value := Cat(0.U(1.W), outputValue).asSInt
  io.traceLast := outputValid && outputLast
  io.busy := state === eroding || receivedCells =/= 0.U || outputValid
  io.overflow := acceptingNewFrame && configOutOfRange

  when(io.input.fire) {
    val receiveIndex = receivedCells(cellIndexBits - 1, 0)
    cells(receiveIndex) := io.input.bits
    val nextReceived = receivedCells + 1.U

    when(acceptingNewFrame) {
      activeCellCount := nextCellCount(cellCountBits - 1, 0)
      cellsX := nextCellsX
      cellsY := nextCellsY
      blocksX := nextBlocksX
      totalBlocks := nextBlockCount
    }

    when(nextReceived === selectedCellCount) {
      state := eroding
      receivedCells := 0.U
      blockOrdinal := 0.U
      currentBlockX := 0.U
      currentBlockY := 0.U
      sampleOrdinal := 0.U
      blockAccumulator := 0.U
    }.otherwise {
      receivedCells := nextReceived
    }
  }

  val localX = sampleOrdinal(0)
  val localY = sampleOrdinal(1)
  val centerX = (currentBlockX << 1) + localX
  val centerY = (currentBlockY << 1) + localY
  val leftX = Mux(centerX === 0.U, centerX, centerX - 1.U)
  val rightX = Mux(centerX + 1.U >= cellsX, centerX, centerX + 1.U)
  val stripeBaseCellY = (centerY >> stripeCellShift) << stripeCellShift
  val stripeEndCandidate = stripeBaseCellY + stripeCells.U
  val stripeEndCellY = Mux(stripeEndCandidate < cellsY, stripeEndCandidate, cellsY)
  val upY = Mux(centerY === stripeBaseCellY, centerY, centerY - 1.U)
  val downY = Mux(centerY + 1.U >= stripeEndCellY, centerY, centerY + 1.U)

  private def cellAt(x: UInt, y: UInt): UInt = {
    val index = y * cellsX + x
    cells(index(cellIndexBits - 1, 0))
  }

  val erosion = Module(new AqFuzzyErosionSample(valueBits))
  erosion.io.center := cellAt(centerX, centerY)
  val neighborhoodCoordinates = Seq(
    leftX -> upY,
    centerX -> upY,
    rightX -> upY,
    leftX -> centerY,
    centerX -> centerY,
    rightX -> centerY,
    leftX -> downY,
    centerX -> downY,
    rightX -> downY
  )
  for (((x, y), index) <- neighborhoodCoordinates.zipWithIndex) {
    erosion.io.neighborhood(index) := cellAt(x, y)
  }

  when(state === eroding && !outputValid) {
    val nextAccumulator = blockAccumulator +& erosion.io.weightedSum
    when(sampleOrdinal === (SamplesPerBlock - 1).U) {
      val roundedBlock = (nextAccumulator + (Divisor / 2).U) / Divisor.U
      outputValue := Mux(
        roundedBlock > MaximumValue.U,
        MaximumValue.U,
        roundedBlock(valueBits - 1, 0)
      )
      outputValid := true.B
      outputLast := blockOrdinal === totalBlocks - 1.U
      sampleOrdinal := 0.U
      blockAccumulator := 0.U
    }.otherwise {
      sampleOrdinal := sampleOrdinal + 1.U
      blockAccumulator := nextAccumulator(valueBits + 4, 0)
    }
  }

  when(io.trace.fire) {
    outputValid := false.B
    outputLast := false.B
    when(outputLast) {
      state := receiving
      activeCellCount := 0.U
      cellsX := 0.U
      cellsY := 0.U
      blocksX := 0.U
      totalBlocks := 0.U
      blockOrdinal := 0.U
      currentBlockX := 0.U
      currentBlockY := 0.U
    }.otherwise {
      blockOrdinal := blockOrdinal + 1.U
      when(currentBlockX + 1.U === blocksX) {
        currentBlockX := 0.U
        currentBlockY := currentBlockY + 1.U
      }.otherwise {
        currentBlockX := currentBlockX + 1.U
      }
    }
  }
}

/** RGB-connected fuzzy-erosion path composed from the contrast and prepared
  * erosion stages.
  */
class FrameAqFuzzyErosionTraceStage(
    c: HjxlConfig = HjxlConfig(),
    xybOutputFractionBits: Int = RgbToXybApprox.OutputFractionBits
) extends Module {
  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val xybAccepted = Output(Valid(new XybPixel(c)))
    val analysisXybAccepted =
      if (xybOutputFractionBits > RgbToXybApprox.OutputFractionBits)
        Some(Output(Valid(new XybPixel(c))))
      else None
    val quantizationXybAccepted =
      if (xybOutputFractionBits > RgbVarDctFixedPoint.AnalysisXybFractionBits)
        Some(Output(Valid(new XybPixel(c))))
      else None
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val contrast = Module(new FrameAqContrastTraceStage(c, xybOutputFractionBits))
  val erosion = Module(new FramePreparedAqFuzzyErosionTraceStage(c))
  val latchedConfig = Reg(new FrameConfig(c))
  val frameActive = RegInit(false.B)
  val contrastDone = RegInit(false.B)
  val activeConfig = Wire(new FrameConfig(c))
  activeConfig := Mux(frameActive, latchedConfig, io.config)

  contrast.io.config := activeConfig
  contrast.io.input.bits := io.input.bits
  contrast.io.input.valid := io.input.valid && !contrastDone
  io.input.ready := contrast.io.input.ready && !contrastDone
  io.xybAccepted := contrast.io.xybAccepted
  io.analysisXybAccepted.foreach(_ := contrast.io.analysisXybAccepted.get)
  io.quantizationXybAccepted.foreach(_ := contrast.io.quantizationXybAccepted.get)

  erosion.io.config := activeConfig
  erosion.io.input.valid := contrast.io.trace.valid
  erosion.io.input.bits := contrast.io.trace.bits.value.asUInt(30, 0)
  contrast.io.trace.ready := erosion.io.input.ready

  io.trace.valid := erosion.io.trace.valid
  io.trace.bits := erosion.io.trace.bits
  erosion.io.trace.ready := io.trace.ready
  io.traceLast := erosion.io.traceLast
  io.busy := frameActive || contrast.io.busy || erosion.io.busy
  io.overflow := contrast.io.overflow || erosion.io.overflow

  when(io.input.fire && !frameActive) {
    latchedConfig := io.config
    frameActive := true.B
  }
  when(contrast.io.trace.fire && contrast.io.traceLast) {
    contrastDone := true.B
  }
  when(io.trace.fire && io.traceLast) {
    frameActive := false.B
    contrastDone := false.B
  }
}
