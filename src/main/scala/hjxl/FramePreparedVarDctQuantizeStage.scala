// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** One first-block-owned prepared VarDCT record.
  *
  * Records arrive in raster order over first blocks only. Continuation cells
  * are represented by the owning rectangle and must not be sent separately.
  */
class PreparedVarDctFrameBlock(c: HjxlConfig) extends Bundle {
  val blockX = UInt(c.coordBits.W)
  val blockY = UInt(c.coordBits.W)
  val last = Bool()
  val quantize = new VarDctQuantizeBlockInput(c)
}

/** Quantized first-block record retained for metadata and token schedulers. */
class PreparedVarDctQuantizedFrameBlock(c: HjxlConfig) extends Bundle {
  val blockX = UInt(c.coordBits.W)
  val blockY = UInt(c.coordBits.W)
  val blockOrdinal = UInt(c.groupBits.W)
  val last = Bool()
  val rawQuant = UInt(8.W)
  val ytox = SInt(8.W)
  val ytob = SInt(8.W)
  val result = new VarDctQuantizeBlockOutput(c)
}

/** Validates a first-block-only frame stream and quantizes each owned shape.
  *
  * A small coverage map makes continuation ownership explicit. It rejects
  * overlaps, gaps before the current owner, out-of-bounds rectangles, and an
  * early or missing final marker. Valid records flow atomically through the
  * reusable block quantizer, so backpressure cannot advance frame ownership.
  */
class FramePreparedVarDctQuantizeStage(
    c: HjxlConfig = HjxlConfig(),
    coefficientFractionBits: Int = 0
) extends Module {
  private val blockDim = HjxlConstants.BlockDim
  private val maxXBlocks = c.maxFrameWidth / blockDim
  private val maxYBlocks = c.maxFrameHeight / blockDim
  private val maxBlocks = maxXBlocks * maxYBlocks
  private val blockAddressBits = math.max(1, log2Ceil(maxBlocks))
  private val blockCountBits = math.max(1, log2Ceil(maxBlocks + 1))
  private val activeCoefficientFractionBits =
    if (coefficientFractionBits == 0) c.preparedDctCoefficientFractionBits else coefficientFractionBits

  require(maxBlocks > 0, "prepared VarDCT frame must contain a block")
  require(activeCoefficientFractionBits > 0, "coefficientFractionBits must be positive")

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new PreparedVarDctFrameBlock(c)))
    val output = Decoupled(new PreparedVarDctQuantizedFrameBlock(c))
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  private def ceilDiv(value: UInt, divisor: Int): UInt =
    (value +& (divisor - 1).U) / divisor.U

  val frameActive = RegInit(false.B)
  val xBlocks = RegInit(0.U(blockCountBits.W))
  val yBlocks = RegInit(0.U(blockCountBits.W))
  val totalBlocks = RegInit(0.U(blockCountBits.W))
  val covered = RegInit(VecInit(Seq.fill(maxBlocks)(false.B)))
  val overflow = RegInit(false.B)
  val resultPending = RegInit(false.B)
  val pendingBlockX = Reg(UInt(c.coordBits.W))
  val pendingBlockY = Reg(UInt(c.coordBits.W))
  val pendingBlockOrdinal = Reg(UInt(c.groupBits.W))
  val pendingLast = Reg(Bool())
  val pendingRawQuant = Reg(UInt(8.W))
  val pendingYtox = Reg(SInt(8.W))
  val pendingYtob = Reg(SInt(8.W))

  val nextXBlocks = ceilDiv(io.config.xsize, blockDim)
  val nextYBlocks = ceilDiv(io.config.ysize, blockDim)
  val nextTotalBlocks = nextXBlocks * nextYBlocks
  val configOutOfRange =
    io.config.xsize === 0.U || io.config.ysize === 0.U ||
      io.config.xsize > c.maxFrameWidth.U || io.config.ysize > c.maxFrameHeight.U ||
      nextXBlocks > maxXBlocks.U || nextYBlocks > maxYBlocks.U ||
      nextTotalBlocks > maxBlocks.U

  val activeXBlocks = Mux(frameActive, xBlocks, nextXBlocks)
  val activeYBlocks = Mux(frameActive, yBlocks, nextYBlocks)
  val activeTotalBlocks = Mux(frameActive, totalBlocks, nextTotalBlocks)
  val ownerOrdinalWide = io.input.bits.blockY * activeXBlocks + io.input.bits.blockX
  val ownerOrdinal = ownerOrdinalWide(blockCountBits - 1, 0)
  val ownerAddress = ownerOrdinal(blockAddressBits - 1, 0)
  val strategy = io.input.bits.quantize.strategy
  val isVertical = strategy === AcStrategyCode.Dct16x8.U
  val isHorizontal = strategy === AcStrategyCode.Dct8x16.U
  val isRectangular = isVertical || isHorizontal
  val secondOrdinal = Mux(isVertical, ownerOrdinal + activeXBlocks, ownerOrdinal + 1.U)
  val secondAddress = secondOrdinal(blockAddressBits - 1, 0)

  val coveredBefore = Wire(Vec(maxBlocks, Bool()))
  for (index <- 0 until maxBlocks) {
    coveredBefore(index) := Mux(frameActive, covered(index), false.B)
  }

  val coordinateInBounds =
    io.input.bits.blockX < activeXBlocks && io.input.bits.blockY < activeYBlocks
  val rectangleInBounds =
    Mux(
      isVertical,
      io.input.bits.blockY + 1.U < activeYBlocks,
      Mux(isHorizontal, io.input.bits.blockX + 1.U < activeXBlocks, true.B)
    )
  val supportedStrategy = strategy <= AcStrategyCode.Dct8x16.U
  val ownerAlreadyCovered = Mux(coordinateInBounds, coveredBefore(ownerAddress), true.B)
  val secondAlreadyCovered =
    Mux(isRectangular && rectangleInBounds, coveredBefore(secondAddress), false.B)
  val earlierUncovered = (0 until maxBlocks).map { index =>
    index.U < ownerOrdinal && index.U < activeTotalBlocks && !coveredBefore(index)
  }.reduce(_ || _)

  val geometryValid =
    !configOutOfRange && supportedStrategy && coordinateInBounds && rectangleInBounds &&
      !ownerAlreadyCovered && !secondAlreadyCovered && !earlierUncovered

  val coveredAfter = Wire(Vec(maxBlocks, Bool()))
  coveredAfter := coveredBefore
  when(geometryValid) {
    coveredAfter(ownerAddress) := true.B
    when(isRectangular) {
      coveredAfter(secondAddress) := true.B
    }
  }
  val allCoveredAfter = (0 until maxBlocks).map { index =>
    index.U >= activeTotalBlocks || coveredAfter(index)
  }.reduce(_ && _)
  val lastMatches = io.input.bits.last === allCoveredAfter
  val validRecord = geometryValid && lastMatches

  val quantizer = Module(new VarDctQuantizeBlock(c, activeCoefficientFractionBits))
  quantizer.io.input.valid := io.input.valid && validRecord
  quantizer.io.input.bits := io.input.bits.quantize
  quantizer.io.output.ready := io.output.ready

  // Invalid records are consumed only when the quantizer is idle as well. This
  // keeps a malformed following beat from discarding ownership state while a
  // previously accepted result is still pending.
  io.input.ready := quantizer.io.input.ready
  io.output.valid := quantizer.io.output.valid
  io.output.bits.blockX := pendingBlockX
  io.output.bits.blockY := pendingBlockY
  io.output.bits.blockOrdinal := pendingBlockOrdinal
  io.output.bits.last := pendingLast
  io.output.bits.rawQuant := pendingRawQuant
  io.output.bits.ytox := pendingYtox
  io.output.bits.ytob := pendingYtob
  io.output.bits.result := quantizer.io.output.bits
  io.busy := frameActive || resultPending || (io.input.valid && validRecord)
  io.overflow := overflow

  when(io.input.fire) {
    when(validRecord) {
      resultPending := true.B
      pendingBlockX := io.input.bits.blockX
      pendingBlockY := io.input.bits.blockY
      pendingBlockOrdinal := ownerOrdinal
      pendingLast := allCoveredAfter
      pendingRawQuant := io.input.bits.quantize.quant
      pendingYtox := io.input.bits.quantize.ytox
      pendingYtob := io.input.bits.quantize.ytob
      when(allCoveredAfter) {
        frameActive := false.B
        xBlocks := 0.U
        yBlocks := 0.U
        totalBlocks := 0.U
        covered := VecInit(Seq.fill(maxBlocks)(false.B))
      }.otherwise {
        frameActive := true.B
        when(!frameActive) {
          xBlocks := nextXBlocks(blockCountBits - 1, 0)
          yBlocks := nextYBlocks(blockCountBits - 1, 0)
          totalBlocks := nextTotalBlocks(blockCountBits - 1, 0)
        }
        covered := coveredAfter
      }
    }.otherwise {
      overflow := true.B
      frameActive := false.B
      xBlocks := 0.U
      yBlocks := 0.U
      totalBlocks := 0.U
      covered := VecInit(Seq.fill(maxBlocks)(false.B))
    }
  }

  when(io.output.fire) {
    resultPending := false.B
  }
}
