// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

object AqColorModulationFixedPoint {
  val BlockDim = HjxlConstants.BlockDim
  val SamplesPerBlock = BlockDim * BlockDim
  val XybFractionBits = RgbToXybApprox.OutputFractionBits
  val XybValueBits = 32
  val SeedFractionBits = AqHfModulationFixedPoint.OutputFractionBits
  val SeedValueBits = AqHfModulationFixedPoint.OutputValueBits
  val OutputFractionBits = SeedFractionBits
  val OutputValueBits = SeedValueBits

  val CoverageFractionBits = 16
  val CoverageScaleShift = CoverageFractionBits - XybFractionBits
  val CoverageAccumulatorBits = 20
  val DistanceFractionBits = 10
  val MaximumActiveDistanceQ8 = 4 << 8

  val RedRampStartQ16: BigInt = 480
  val RedRampLengthQ16: BigInt = 1273
  val BlueRampStartQ16: BigInt = 17677
  val BlueRampLengthQ16: BigInt = 5694
  val RedCoverageCapQ16: BigInt = 38962
  val BlueCoverageCapQ16: BigInt = 174311

  // These fold kStrengthMul into the reference baseline and coverage factors.
  // The remaining distance scale is max(0, 1 - distance / 4), represented in
  // Q10 as max(0, 1024 - distanceQ8).
  val BaselineMagnitudeCombinedQ24: BigInt = 335218
  val RedCombinedQ24: BigInt = 7152598
  val BlueCombinedQ24: BigInt = 1193632

  val MinimumSigned32: BigInt = -(BigInt(1) << 31)
  val MaximumSigned32: BigInt = (BigInt(1) << 31) - 1
  val MaximumRedCoverageQ16: BigInt = SamplesPerBlock * RedRampLengthQ16
  val MaximumBlueCoverageQ16: BigInt = SamplesPerBlock * BlueRampLengthQ16

  require(CoverageScaleShift >= 0, "AQ color coverage must not discard XYB precision")
  require(
    MaximumBlueCoverageQ16.bitLength <= CoverageAccumulatorBits,
    "AQ color coverage accumulator is too small"
  )

  private def requireSigned32(value: BigInt, label: String): Unit =
    require(
      value >= MinimumSigned32 && value <= MaximumSigned32,
      s"$label must fit signed 32 bits"
    )

  private def clampedCoverageQ16(valueQ16: BigInt, startQ16: BigInt, lengthQ16: BigInt): BigInt =
    (valueQ16 - startQ16).max(BigInt(0)).min(lengthQ16)

  private def distanceScaledQ24(combinedQ24: BigInt, distanceDeltaQ10: BigInt): BigInt =
    (combinedQ24 * distanceDeltaQ10 + (BigInt(1) << (DistanceFractionBits - 1))) >>
      DistanceFractionBits

  def coverageQ16(
      xybXQ12: Seq[BigInt],
      xybYQ12: Seq[BigInt],
      xybBQ12: Seq[BigInt]
  ): (BigInt, BigInt) = {
    require(
      Seq(xybXQ12, xybYQ12, xybBQ12).forall(_.length == SamplesPerBlock),
      "AQ color modulation requires one 8x8 XYB block"
    )
    (xybXQ12 ++ xybYQ12 ++ xybBQ12).foreach(value =>
      requireSigned32(value, "AQ color modulation sample")
    )

    var redCoverage = BigInt(0)
    var blueCoverage = BigInt(0)
    for (sample <- 0 until SamplesPerBlock) {
      val xQ16 = xybXQ12(sample) << CoverageScaleShift
      val bMinusYQ16 = (xybBQ12(sample) - xybYQ12(sample)) << CoverageScaleShift
      redCoverage += clampedCoverageQ16(xQ16, RedRampStartQ16, RedRampLengthQ16)
      blueCoverage += clampedCoverageQ16(
        bMinusYQ16,
        BlueRampStartQ16,
        BlueRampLengthQ16
      )
    }
    require(redCoverage <= MaximumRedCoverageQ16, "AQ red coverage exceeds its bound")
    require(blueCoverage <= MaximumBlueCoverageQ16, "AQ blue coverage exceeds its bound")
    (redCoverage, blueCoverage)
  }

  def modulateQ24(
      seedQ24: BigInt,
      distanceQ8: Int,
      xybXQ12: Seq[BigInt],
      xybYQ12: Seq[BigInt],
      xybBQ12: Seq[BigInt]
  ): BigInt = {
    requireSigned32(seedQ24, "AQ color modulation seed")
    require(distanceQ8 >= 0 && distanceQ8 <= 0xffff, "AQ color distance must fit unsigned Q8")
    val (rawRedCoverage, rawBlueCoverage) = coverageQ16(xybXQ12, xybYQ12, xybBQ12)
    val distanceDeltaQ10 = BigInt(MaximumActiveDistanceQ8 - distanceQ8).max(BigInt(0))
    if (distanceDeltaQ10 == 0) {
      seedQ24
    } else {
      val redCoverage = rawRedCoverage.min(RedCoverageCapQ16)
      val blueCoverage = rawBlueCoverage.min(BlueCoverageCapQ16)
      val baselineMagnitude = distanceScaledQ24(
        BaselineMagnitudeCombinedQ24,
        distanceDeltaQ10
      )
      val redCoefficient = distanceScaledQ24(RedCombinedQ24, distanceDeltaQ10)
      val blueCoefficient = distanceScaledQ24(BlueCombinedQ24, distanceDeltaQ10)
      val redContribution =
        (redCoverage * redCoefficient + (BigInt(1) << (CoverageFractionBits - 1))) >>
          CoverageFractionBits
      val blueContribution =
        (blueCoverage * blueCoefficient + (BigInt(1) << (CoverageFractionBits - 1))) >>
          CoverageFractionBits
      (seedQ24 - baselineMagnitude + redContribution + blueContribution)
        .max(MinimumSigned32)
        .min(MaximumSigned32)
    }
  }
}

class AqColorModulationBlockInput extends Bundle {
  import AqColorModulationFixedPoint._

  val seedQ24 = SInt(SeedValueBits.W)
  val distanceQ8 = UInt(16.W)
  val xybXQ12 = Vec(SamplesPerBlock, SInt(XybValueBits.W))
  val xybYQ12 = Vec(SamplesPerBlock, SInt(XybValueBits.W))
  val xybBQ12 = Vec(SamplesPerBlock, SInt(XybValueBits.W))
}

/** Applies libjxl-tiny's distance-dependent red/blue AQ modulation to one
  * cumulative HF seed.
  *
  * One XYB sample is examined per cycle. Coverage thresholds and caps use Q16;
  * the distance-folded baseline and coverage coefficients use Q24. The
  * reference's ratio divisions are folded into constants, so this block has no
  * runtime divider and has an externally observed latency of exactly 64 cycles.
  */
class AqColorModulationBlock extends Module {
  import AqColorModulationFixedPoint._

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new AqColorModulationBlockInput))
    val output = Decoupled(SInt(OutputValueBits.W))
    val busy = Output(Bool())
  })

  private val sampleBits = log2Ceil(SamplesPerBlock)
  private val coverageInputBits = XybValueBits + 1 + CoverageScaleShift
  private val adjustedCoverageBits = coverageInputBits + 1
  private val resultBits = 40

  val samplesX = Reg(Vec(SamplesPerBlock, SInt(XybValueBits.W)))
  val samplesY = Reg(Vec(SamplesPerBlock, SInt(XybValueBits.W)))
  val samplesB = Reg(Vec(SamplesPerBlock, SInt(XybValueBits.W)))
  val seed = RegInit(0.S(SeedValueBits.W))
  val distanceDeltaQ10 = RegInit(0.U((DistanceFractionBits + 1).W))
  val sampleOrdinal = RegInit(0.U(sampleBits.W))
  val redCoverage = RegInit(0.U(CoverageAccumulatorBits.W))
  val blueCoverage = RegInit(0.U(CoverageAccumulatorBits.W))
  val running = RegInit(false.B)
  val outputValid = RegInit(false.B)
  val outputValue = RegInit(0.S(OutputValueBits.W))

  io.input.ready := !running && !outputValid
  io.output.valid := outputValid
  io.output.bits := outputValue
  io.busy := running || outputValid

  when(io.output.fire) {
    outputValid := false.B
  }

  val inputDistanceDelta = Wire(UInt((DistanceFractionBits + 1).W))
  inputDistanceDelta := Mux(
    io.input.bits.distanceQ8 <= MaximumActiveDistanceQ8.U,
    MaximumActiveDistanceQ8.U - io.input.bits.distanceQ8,
    0.U
  )

  when(io.input.fire) {
    samplesX := io.input.bits.xybXQ12
    samplesY := io.input.bits.xybYQ12
    samplesB := io.input.bits.xybBQ12
    seed := io.input.bits.seedQ24
    distanceDeltaQ10 := inputDistanceDelta
    sampleOrdinal := 0.U
    redCoverage := 0.U
    blueCoverage := 0.U
    running := true.B
  }

  private def clampedCoverage(valueQ16: SInt, startQ16: BigInt, lengthQ16: BigInt): UInt = {
    val adjusted = Wire(SInt(adjustedCoverageBits.W))
    adjusted := valueQ16 -& startQ16.S(coverageInputBits.W)
    Mux(
      adjusted <= 0.S,
      0.U(CoverageAccumulatorBits.W),
      Mux(
        adjusted >= lengthQ16.S(adjustedCoverageBits.W),
        lengthQ16.U(CoverageAccumulatorBits.W),
        adjusted(CoverageAccumulatorBits - 1, 0).asUInt
      )
    )
  }

  private def distanceScaledQ24(combinedQ24: BigInt): UInt = {
    val product = distanceDeltaQ10 * combinedQ24.U
    (product +& (BigInt(1) << (DistanceFractionBits - 1)).U) >> DistanceFractionBits
  }

  when(running) {
    val xQ16 = Wire(SInt(coverageInputBits.W))
    val bMinusYQ16 = Wire(SInt(coverageInputBits.W))
    xQ16 := samplesX(sampleOrdinal) << CoverageScaleShift
    bMinusYQ16 := (samplesB(sampleOrdinal) -& samplesY(sampleOrdinal)) << CoverageScaleShift
    val redSample = clampedCoverage(xQ16, RedRampStartQ16, RedRampLengthQ16)
    val blueSample = clampedCoverage(
      bMinusYQ16,
      BlueRampStartQ16,
      BlueRampLengthQ16
    )
    val nextRedWide = redCoverage +& redSample
    val nextBlueWide = blueCoverage +& blueSample
    assert(nextRedWide <= MaximumRedCoverageQ16.U, "AQ red coverage accumulator overflow")
    assert(nextBlueWide <= MaximumBlueCoverageQ16.U, "AQ blue coverage accumulator overflow")
    val nextRed = nextRedWide(CoverageAccumulatorBits - 1, 0)
    val nextBlue = nextBlueWide(CoverageAccumulatorBits - 1, 0)

    when(sampleOrdinal === (SamplesPerBlock - 1).U) {
      val cappedRed = Mux(
        nextRed > RedCoverageCapQ16.U,
        RedCoverageCapQ16.U(CoverageAccumulatorBits.W),
        nextRed
      )
      val cappedBlue = Mux(
        nextBlue > BlueCoverageCapQ16.U,
        BlueCoverageCapQ16.U(CoverageAccumulatorBits.W),
        nextBlue
      )
      val baselineMagnitude = distanceScaledQ24(BaselineMagnitudeCombinedQ24)
      val redCoefficient = distanceScaledQ24(RedCombinedQ24)
      val blueCoefficient = distanceScaledQ24(BlueCombinedQ24)
      val redProduct = cappedRed * redCoefficient
      val blueProduct = cappedBlue * blueCoefficient
      val redContribution =
        (redProduct +& (BigInt(1) << (CoverageFractionBits - 1)).U) >> CoverageFractionBits
      val blueContribution =
        (blueProduct +& (BigInt(1) << (CoverageFractionBits - 1)).U) >> CoverageFractionBits
      val result = Wire(SInt(resultBits.W))
      result := seed.pad(resultBits) - baselineMagnitude.pad(resultBits).asSInt +
        redContribution.pad(resultBits).asSInt + blueContribution.pad(resultBits).asSInt
      outputValue := Mux(
        result > MaximumSigned32.S(resultBits.W),
        MaximumSigned32.S(OutputValueBits.W),
        Mux(
          result < MinimumSigned32.S(resultBits.W),
          MinimumSigned32.S(OutputValueBits.W),
          result(OutputValueBits - 1, 0).asSInt
        )
      )
      sampleOrdinal := 0.U
      redCoverage := 0.U
      blueCoverage := 0.U
      running := false.B
      outputValid := true.B
    }.otherwise {
      sampleOrdinal := sampleOrdinal + 1.U
      redCoverage := nextRed
      blueCoverage := nextBlue
    }
  }
}

/** Applies color modulation to prepared cumulative HF seeds and prepared XYB
  * blocks in padded-block raster order.
  */
class FramePreparedAqColorModulationTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  import AqColorModulationFixedPoint._

  private val maxXBlocks = c.maxFrameWidth / BlockDim
  private val maxYBlocks = c.maxFrameHeight / BlockDim
  private val maxBlocks = maxXBlocks * maxYBlocks
  private val widthBits = log2Ceil(c.maxFrameWidth + 1)
  private val heightBits = log2Ceil(c.maxFrameHeight + 1)

  require(c.traceValueBits == OutputValueBits, "AQ color modulation trace requires signed 32-bit values")
  require(maxBlocks > 0, "configured frame must contain at least one block")
  require(BigInt(maxBlocks) <= BigInt("ffffffff", 16), "AQ color modulation block count must fit trace index")

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new AqColorModulationBlockInput))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  private def ceilDivBlock(value: UInt): UInt =
    (value +& (BlockDim - 1).U) >> log2Ceil(BlockDim)

  val configWidth = io.config.xsize(widthBits - 1, 0)
  val configHeight = io.config.ysize(heightBits - 1, 0)
  val nextXBlocks = ceilDivBlock(configWidth)
  val nextYBlocks = ceilDivBlock(configHeight)
  val nextTotalBlocks = nextXBlocks * nextYBlocks
  val configOutOfRange =
    io.config.xsize === 0.U || io.config.ysize === 0.U ||
      io.config.xsize > c.maxFrameWidth.U || io.config.ysize > c.maxFrameHeight.U ||
      nextXBlocks > maxXBlocks.U || nextYBlocks > maxYBlocks.U

  val frameActive = RegInit(false.B)
  val activeTotalBlocks = RegInit(0.U(32.W))
  val activeDistanceQ8 = RegInit(0.U(16.W))
  val nextBlockIndex = RegInit(0.U(32.W))
  val activeTraceIndex = RegInit(0.U(32.W))
  val activeTraceLast = RegInit(false.B)

  val block = Module(new AqColorModulationBlock)
  val selectedTotalBlocks = Mux(frameActive, activeTotalBlocks, nextTotalBlocks)
  val selectedDistanceQ8 = Mux(frameActive, activeDistanceQ8, io.config.distanceQ8)
  val inputAllowed = frameActive || !configOutOfRange
  block.io.input.bits := io.input.bits
  block.io.input.valid := io.input.valid && inputAllowed && !activeTraceLast
  io.input.ready := block.io.input.ready && inputAllowed && !activeTraceLast

  io.trace.valid := block.io.output.valid
  io.trace.bits.stage := TraceStage.AqColorModulation.U
  io.trace.bits.group := 0.U
  io.trace.bits.index := activeTraceIndex
  io.trace.bits.value := block.io.output.bits
  block.io.output.ready := io.trace.ready
  io.traceLast := block.io.output.valid && activeTraceLast
  io.busy := frameActive || block.io.busy
  io.overflow := !frameActive && block.io.input.ready && configOutOfRange

  when(io.input.fire) {
    assert(
      io.input.bits.distanceQ8 === selectedDistanceQ8,
      "AQ color modulation distance changed within a prepared frame"
    )
    val inputIsLast = nextBlockIndex === selectedTotalBlocks - 1.U
    activeTraceIndex := nextBlockIndex
    activeTraceLast := inputIsLast
    when(!frameActive) {
      frameActive := true.B
      activeTotalBlocks := nextTotalBlocks
      activeDistanceQ8 := io.config.distanceQ8
    }
    when(!inputIsLast) {
      nextBlockIndex := nextBlockIndex + 1.U
    }
  }

  when(io.trace.fire) {
    activeTraceLast := false.B
    when(activeTraceLast) {
      frameActive := false.B
      activeTotalBlocks := 0.U
      activeDistanceQ8 := 0.U
      nextBlockIndex := 0.U
      activeTraceIndex := 0.U
    }
  }
}

/** RGB-connected cumulative AQ path through `_color_modulation`.
  *
  * One shared scheduler captures XYB and emits prepared blocks. The HF block
  * consumes Y first; its cumulative output and the retained block context then
  * feed the color block. No second RGB-to-XYB converter or frame buffer is
  * instantiated.
  */
class FrameAqColorModulationTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  import AqColorModulationFixedPoint._

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val xybAccepted = Output(Valid(new XybPixel(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val scheduler = Module(new FrameAqModulationBlockStage(c))
  scheduler.io.config := io.config
  scheduler.io.input <> io.input
  io.xybAccepted := scheduler.io.xybAccepted

  val hf = Module(new AqHfModulationBlock)
  val pendingContext = Reg(new AqModulationBlockInput)
  val pendingContextValid = RegInit(false.B)

  hf.io.input.bits.seedQ24 := scheduler.io.block.bits.seedQ24
  hf.io.input.bits.xybYQ12 := scheduler.io.block.bits.xybYQ12
  hf.io.input.valid := scheduler.io.block.valid && !pendingContextValid
  scheduler.io.block.ready := hf.io.input.ready && !pendingContextValid

  when(scheduler.io.block.fire) {
    assert(!pendingContextValid, "AQ color modulation accepted overlapping block context")
    pendingContext := scheduler.io.block.bits
    pendingContextValid := true.B
  }

  val color = Module(new AqColorModulationBlock)
  color.io.input.bits.seedQ24 := hf.io.output.bits
  color.io.input.bits.distanceQ8 := pendingContext.distanceQ8
  color.io.input.bits.xybXQ12 := pendingContext.xybXQ12
  color.io.input.bits.xybYQ12 := pendingContext.xybYQ12
  color.io.input.bits.xybBQ12 := pendingContext.xybBQ12
  color.io.input.valid := hf.io.output.valid && pendingContextValid
  hf.io.output.ready := color.io.input.ready && pendingContextValid

  val activeBlockValid = RegInit(false.B)
  val activeBlockIndex = RegInit(0.U(32.W))
  val activeBlockLast = RegInit(false.B)

  when(color.io.input.fire) {
    assert(pendingContextValid, "AQ color modulation lost its XYB block context")
    assert(!activeBlockValid, "AQ color modulation accepted overlapping output metadata")
    activeBlockValid := true.B
    activeBlockIndex := pendingContext.blockIndex
    activeBlockLast := pendingContext.blockLast
    pendingContextValid := false.B
  }

  io.trace.valid := color.io.output.valid
  io.trace.bits.stage := TraceStage.AqColorModulation.U
  io.trace.bits.group := 0.U
  io.trace.bits.index := activeBlockIndex
  io.trace.bits.value := color.io.output.bits
  color.io.output.ready := io.trace.ready
  io.traceLast := color.io.output.valid && activeBlockLast
  scheduler.io.frameDone := io.trace.fire && activeBlockLast
  io.busy := scheduler.io.busy || hf.io.busy || color.io.busy ||
    pendingContextValid || activeBlockValid
  io.overflow := scheduler.io.overflow

  when(io.trace.fire) {
    assert(activeBlockValid, "AQ color modulation emitted without block metadata")
    activeBlockValid := false.B
    activeBlockLast := false.B
  }
}
