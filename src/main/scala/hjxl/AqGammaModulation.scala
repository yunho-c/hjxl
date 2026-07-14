// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

object AqGammaModulationFixedPoint {
  val BlockDim = HjxlConstants.BlockDim
  val SamplesPerBlock = BlockDim * BlockDim
  val XybFractionBits = RgbToXybApprox.OutputFractionBits
  val XybValueBits = 32
  val SeedFractionBits = AqColorModulationFixedPoint.OutputFractionBits
  val SeedValueBits = AqColorModulationFixedPoint.OutputValueBits
  val OutputFractionBits = SeedFractionBits
  val OutputValueBits = SeedValueBits

  val InputOffsetQ12 = 655
  val MaximumRatioInputQ12 = 8 << XybFractionBits
  val RatioInputBits = XybValueBits + 2
  val RatioFractionBits = 20
  val RatioValueBits = 21
  val RatioAccumulatorBits = 29
  val FineLimitQ12 = 1 << (XybFractionBits - 4)
  val CoarseLimitQ12 = 1 << XybFractionBits
  val CoarseStepBits = 4
  val HdrStepBits = 6

  val LogFractionBits = 20
  val LogValueBits = 26
  val LogMantissaIndexBits = 8
  val LogInterpolationBits = LogFractionBits - LogMantissaIndexBits

  val GammaMultiplierQ24 = -1805633
  val MinimumSigned32: BigInt = -(BigInt(1) << 31)
  val MaximumSigned32: BigInt = (BigInt(1) << 31) - 1

  private val ratioScale = 1 << RatioFractionBits
  private val logScale = 1 << LogFractionBits

  private def referenceInverseGammaRatio(value: Double): Double = {
    val epsilon = 1e-2f
    val sgMul = 226.0480446705883f
    val sgMul2 = (1.0 / 73.377132366608819).toFloat
    val log2 = 0.693147181f
    val sgRetMul = (sgMul2 * 18.6580932135f * log2).toFloat
    val sgVOffset = 7.14672470003f
    val v = math.max(value.toFloat, 0.0f)
    val valueSquared = (v * v).toFloat
    val numeratorMultiplier = (sgRetMul * 3.0f * sgMul).toFloat
    val numerator = (numeratorMultiplier * valueSquared + epsilon).toFloat
    val denominatorMultiplier = (log2 * sgMul).toFloat
    val denominator =
      (denominatorMultiplier * v * valueSquared + (sgVOffset * log2 + epsilon).toFloat).toFloat
    (numerator / denominator).toFloat.toDouble
  }

  private def referenceFastLog2(value: Double): Double = {
    val p = Seq(-1.8503833400518310e-06f, 1.4287160470083755f, 0.74245873327820566f)
    val q = Seq(0.99032814277590719f, 1.0096718572241148f, 0.17409343003366823f)
    val input = value.toFloat
    val inputBits = java.lang.Float.floatToRawIntBits(input)
    val exponentBits = inputBits - 0x3f2aaaab
    val exponent = exponentBits >> 23
    val mantissa = java.lang.Float.intBitsToFloat(inputBits - (exponent << 23))
    val reduced = (mantissa - 1.0f).toFloat
    var numerator = (p(2) * reduced + p(1)).toFloat
    var denominator = (q(2) * reduced + q(1)).toFloat
    numerator = (numerator * reduced + p(0)).toFloat
    denominator = (denominator * reduced + q(0)).toFloat
    (numerator / denominator + exponent.toFloat).toFloat.toDouble
  }

  private def ratioTableValue(valueQ12: Int): Int =
    math.round(referenceInverseGammaRatio(valueQ12.toDouble / (1 << XybFractionBits)) * ratioScale).toInt

  val FineRatioTable: Seq[Int] =
    (0 to FineLimitQ12).map(ratioTableValue)
  val CoarseRatioTable: Seq[Int] =
    (FineLimitQ12 to CoarseLimitQ12 by (1 << CoarseStepBits)).map(ratioTableValue)
  val HdrRatioTable: Seq[Int] =
    (CoarseLimitQ12 to MaximumRatioInputQ12 by (1 << HdrStepBits)).map(ratioTableValue)
  val FastLog2MantissaTable: Seq[Int] =
    (0 to (1 << LogMantissaIndexBits)).map { index =>
      math.max(
        0,
        math.round(
          referenceFastLog2(1.0 + index.toDouble / (1 << LogMantissaIndexBits)) * logScale
        ).toInt
      )
    }

  val MinimumRatioQ20 = (FineRatioTable ++ CoarseRatioTable ++ HdrRatioTable).min
  val MaximumRatioQ20 = (FineRatioTable ++ CoarseRatioTable ++ HdrRatioTable).max
  val MaximumRatioSumQ20: BigInt = BigInt(MaximumRatioQ20) * SamplesPerBlock * 2

  require(MinimumRatioQ20 > 0, "AQ gamma ratio table must remain positive")
  require(BigInt(MaximumRatioQ20).bitLength <= RatioValueBits, "AQ gamma ratio output is too narrow")
  require(MaximumRatioSumQ20.bitLength <= RatioAccumulatorBits, "AQ gamma ratio accumulator is too narrow")

  private def requireSigned32(value: BigInt, label: String): Unit =
    require(value >= MinimumSigned32 && value <= MaximumSigned32, s"$label must fit signed 32 bits")

  private def interpolate(table: Seq[Int], relative: Int, stepBits: Int): Int = {
    val base = relative >> stepBits
    if (base >= table.length - 1) {
      table.last
    } else {
      val fraction = relative & ((1 << stepBits) - 1)
      val scale = 1 << stepBits
      (table(base) * (scale - fraction) + table(base + 1) * fraction + scale / 2) >> stepBits
    }
  }

  def inverseGammaRatioQ20(valueQ12: BigInt): Int = {
    val clamped = valueQ12.max(BigInt(0)).min(BigInt(MaximumRatioInputQ12)).toInt
    if (clamped <= FineLimitQ12) {
      FineRatioTable(clamped)
    } else if (clamped <= CoarseLimitQ12) {
      interpolate(CoarseRatioTable, clamped - FineLimitQ12, CoarseStepBits)
    } else {
      interpolate(HdrRatioTable, clamped - CoarseLimitQ12, HdrStepBits)
    }
  }

  def fastLog2Q20(valueQ20: BigInt): BigInt = {
    require(valueQ20 > 0 && valueQ20 <= MaximumRatioQ20, "AQ gamma log input is out of range")
    val leadingIndex = valueQ20.bitLength - 1
    val normalized = valueQ20 << (LogFractionBits - leadingIndex)
    val relative = normalized - (BigInt(1) << LogFractionBits)
    val index = (relative >> LogInterpolationBits).toInt
    val fraction = (relative & ((BigInt(1) << LogInterpolationBits) - 1)).toInt
    val scale = BigInt(1) << LogInterpolationBits
    val mantissa =
      (BigInt(FastLog2MantissaTable(index)) * (scale - fraction) +
        BigInt(FastLog2MantissaTable(index + 1)) * fraction + scale / 2) >> LogInterpolationBits
    BigInt(leadingIndex - RatioFractionBits) * logScale + mantissa
  }

  private def roundShiftSigned(value: BigInt, shift: Int): BigInt =
    if (value >= 0) {
      (value + (BigInt(1) << (shift - 1))) >> shift
    } else {
      -(((-value) + (BigInt(1) << (shift - 1))) >> shift)
    }

  def modulateQ24(
      seedQ24: BigInt,
      xybXQ12: Seq[BigInt],
      xybYQ12: Seq[BigInt]
  ): BigInt = {
    requireSigned32(seedQ24, "AQ gamma modulation seed")
    require(
      xybXQ12.length == SamplesPerBlock && xybYQ12.length == SamplesPerBlock,
      "AQ gamma modulation requires one 8x8 X/Y block"
    )
    (xybXQ12 ++ xybYQ12).foreach(value => requireSigned32(value, "AQ gamma modulation sample"))
    val ratioSum = (xybXQ12 zip xybYQ12).map { case (x, y) =>
      val inY = y + InputOffsetQ12
      BigInt(inverseGammaRatioQ20(inY - x)) + inverseGammaRatioQ20(inY + x)
    }.sum
    require(ratioSum <= MaximumRatioSumQ20, "AQ gamma ratio sum exceeds its bound")
    val averageRatioQ20 = (ratioSum + SamplesPerBlock) >> 7
    val logQ20 = fastLog2Q20(averageRatioQ20)
    val contributionQ24 = roundShiftSigned(logQ20 * GammaMultiplierQ24, LogFractionBits)
    (seedQ24 + contributionQ24).max(MinimumSigned32).min(MaximumSigned32)
  }
}

class AqGammaModulationBlockInput extends Bundle {
  import AqGammaModulationFixedPoint._

  val seedQ24 = SInt(SeedValueBits.W)
  val xybXQ12 = Vec(SamplesPerBlock, SInt(XybValueBits.W))
  val xybYQ12 = Vec(SamplesPerBlock, SInt(XybValueBits.W))
}

/** Piecewise inverse gamma-ratio lookup used by `_gamma_modulation`.
  *
  * The supported Q12 input range is clamped to [0, 8]. Values through 1/16 use
  * exact Q12 entries, values through 1 use 1/256-spaced interpolation, and the
  * HDR tail uses 1/64-spaced interpolation.
  */
class AqInverseGammaRatioQ20 extends Module {
  import AqGammaModulationFixedPoint._

  val io = IO(new Bundle {
    val valueQ12 = Input(SInt(RatioInputBits.W))
    val output = Output(UInt(RatioValueBits.W))
  })

  val clamped = Wire(UInt(16.W))
  clamped := Mux(
    io.valueQ12 <= 0.S,
    0.U,
    Mux(
      io.valueQ12 >= MaximumRatioInputQ12.S,
      MaximumRatioInputQ12.U,
      io.valueQ12.asUInt
    )
  )

  val fineTable = VecInit(FineRatioTable.map(_.U(RatioValueBits.W)))
  val fineValue = fineTable(clamped(8, 0))

  private def interpolatedValue(
      tableValues: Seq[Int],
      relative: UInt,
      stepBits: Int
  ): UInt = {
    val table = VecInit(tableValues.map(_.U(RatioValueBits.W)))
    val rawBase = relative >> stepBits
    val baseBits = math.max(1, log2Ceil(tableValues.length))
    val base = Mux(
      rawBase >= (tableValues.length - 1).U,
      (tableValues.length - 2).U,
      rawBase
    )(baseBits - 1, 0)
    val fraction = relative(stepBits - 1, 0)
    val scale = 1 << stepBits
    val weighted =
      table(base) * (scale.U - fraction) +& table(base + 1.U) * fraction + (scale / 2).U
    val interpolated = (weighted >> stepBits)(RatioValueBits - 1, 0)
    Mux(rawBase >= (tableValues.length - 1).U, table.last, interpolated)
  }

  val coarseRelative = clamped - FineLimitQ12.U
  val coarseValue = interpolatedValue(CoarseRatioTable, coarseRelative, CoarseStepBits)
  val hdrRelative = clamped - CoarseLimitQ12.U
  val hdrValue = interpolatedValue(HdrRatioTable, hdrRelative, HdrStepBits)

  io.output := Mux(
    clamped <= FineLimitQ12.U,
    fineValue,
    Mux(clamped <= CoarseLimitQ12.U, coarseValue, hdrValue)
  )
}

/** Normalized piecewise-linear approximation of libjxl-tiny's `fast_log2f`.
  * The input and output both use Q20.
  */
class AqFastLog2Q20 extends Module {
  import AqGammaModulationFixedPoint._

  val io = IO(new Bundle {
    val input = Input(UInt(RatioValueBits.W))
    val output = Output(SInt(LogValueBits.W))
  })

  assert(io.input =/= 0.U, "AQ gamma log input must be positive")
  assert(io.input <= MaximumRatioQ20.U, "AQ gamma log input exceeds the ratio bound")

  val leadingZeros = PriorityEncoder(Reverse(io.input))
  val leadingIndex = (RatioValueBits - 1).U - leadingZeros
  val shiftLeft = RatioFractionBits.U - leadingIndex
  val normalizedWide = io.input << shiftLeft
  val normalized = normalizedWide(RatioFractionBits, 0)
  val relative = normalized - (BigInt(1) << RatioFractionBits).U
  val tableIndex = relative(RatioFractionBits - 1, LogInterpolationBits).pad(
    LogMantissaIndexBits + 1
  )
  val fraction = relative(LogInterpolationBits - 1, 0)
  val table = VecInit(FastLog2MantissaTable.map(_.U((LogFractionBits + 1).W)))
  val scale = 1 << LogInterpolationBits
  val weighted =
    table(tableIndex) * (scale.U - fraction) +&
      table(tableIndex + 1.U) * fraction + (scale / 2).U
  val mantissa = (weighted >> LogInterpolationBits)(LogFractionBits, 0)
  val exponent = leadingIndex.zext - RatioFractionBits.S
  val exponentQ20 = exponent << LogFractionBits
  io.output := exponentQ20.pad(LogValueBits) + mantissa.zext.pad(LogValueBits)
}

/** Adds libjxl-tiny's gamma response to one cumulative color-modulated seed.
  *
  * One inverse ratio is evaluated per cycle, alternating R- and G-derived
  * values for each of 64 pixels. The block therefore has an externally
  * observed latency of exactly 128 cycles and contains no runtime divider.
  */
class AqGammaModulationBlock extends Module {
  import AqGammaModulationFixedPoint._

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new AqGammaModulationBlockInput))
    val output = Decoupled(SInt(OutputValueBits.W))
    val busy = Output(Bool())
  })

  private val sampleBits = log2Ceil(SamplesPerBlock)
  private val resultBits = 56

  val samplesX = Reg(Vec(SamplesPerBlock, SInt(XybValueBits.W)))
  val samplesY = Reg(Vec(SamplesPerBlock, SInt(XybValueBits.W)))
  val seed = RegInit(0.S(SeedValueBits.W))
  val sampleOrdinal = RegInit(0.U(sampleBits.W))
  val selectGreen = RegInit(false.B)
  val ratioSum = RegInit(0.U(RatioAccumulatorBits.W))
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

  when(io.input.fire) {
    samplesX := io.input.bits.xybXQ12
    samplesY := io.input.bits.xybYQ12
    seed := io.input.bits.seedQ24
    sampleOrdinal := 0.U
    selectGreen := false.B
    ratioSum := 0.U
    running := true.B
  }

  val adjustedY = samplesY(sampleOrdinal) +& InputOffsetQ12.S
  val ratioInput = Mux(
    selectGreen,
    adjustedY +& samplesX(sampleOrdinal),
    adjustedY -& samplesX(sampleOrdinal)
  )
  val ratio = Module(new AqInverseGammaRatioQ20)
  ratio.io.valueQ12 := ratioInput
  val nextRatioSumWide = ratioSum +& ratio.io.output.pad(RatioAccumulatorBits)
  assert(!nextRatioSumWide(RatioAccumulatorBits), "AQ gamma ratio accumulator overflow")
  val nextRatioSum = nextRatioSumWide(RatioAccumulatorBits - 1, 0)
  val roundedAverageWide = (nextRatioSum +& SamplesPerBlock.U) >> 7
  val roundedAverage = roundedAverageWide(RatioValueBits - 1, 0)
  val log = Module(new AqFastLog2Q20)
  log.io.input := Mux(running && selectGreen, roundedAverage, MinimumRatioQ20.U)

  val logProduct = log.io.output * GammaMultiplierQ24.S
  val logProductNegative = logProduct < 0.S
  val logProductMagnitude = Mux(logProductNegative, -logProduct, logProduct).asUInt
  val roundedContributionMagnitude =
    (logProductMagnitude +& (BigInt(1) << (LogFractionBits - 1)).U) >> LogFractionBits
  val contribution = Mux(
    logProductNegative,
    -roundedContributionMagnitude.asSInt,
    roundedContributionMagnitude.asSInt
  )

  when(running) {
    when(!selectGreen) {
      ratioSum := nextRatioSum
      selectGreen := true.B
    }.otherwise {
      when(sampleOrdinal === (SamplesPerBlock - 1).U) {
        val result = Wire(SInt(resultBits.W))
        result := seed.pad(resultBits) + contribution.pad(resultBits)
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
        selectGreen := false.B
        ratioSum := 0.U
        running := false.B
        outputValid := true.B
      }.otherwise {
        sampleOrdinal := sampleOrdinal + 1.U
        selectGreen := false.B
        ratioSum := nextRatioSum
      }
    }
  }
}

/** Applies gamma modulation to prepared cumulative color seeds and prepared
  * Q12 X/Y blocks in padded-block raster order.
  */
class FramePreparedAqGammaModulationTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  import AqGammaModulationFixedPoint._

  private val maxXBlocks = c.maxFrameWidth / BlockDim
  private val maxYBlocks = c.maxFrameHeight / BlockDim
  private val maxBlocks = maxXBlocks * maxYBlocks
  private val widthBits = log2Ceil(c.maxFrameWidth + 1)
  private val heightBits = log2Ceil(c.maxFrameHeight + 1)

  require(c.traceValueBits == OutputValueBits, "AQ gamma modulation trace requires signed 32-bit values")
  require(maxBlocks > 0, "configured frame must contain at least one block")
  require(BigInt(maxBlocks) <= BigInt("ffffffff", 16), "AQ gamma modulation block count must fit trace index")

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new AqGammaModulationBlockInput))
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
  val nextBlockIndex = RegInit(0.U(32.W))
  val activeTraceIndex = RegInit(0.U(32.W))
  val activeTraceLast = RegInit(false.B)

  val block = Module(new AqGammaModulationBlock)
  val selectedTotalBlocks = Mux(frameActive, activeTotalBlocks, nextTotalBlocks)
  val inputAllowed = frameActive || !configOutOfRange
  block.io.input.bits := io.input.bits
  block.io.input.valid := io.input.valid && inputAllowed && !activeTraceLast
  io.input.ready := block.io.input.ready && inputAllowed && !activeTraceLast

  io.trace.valid := block.io.output.valid
  io.trace.bits.stage := TraceStage.AqGammaModulation.U
  io.trace.bits.group := 0.U
  io.trace.bits.index := activeTraceIndex
  io.trace.bits.value := block.io.output.bits
  block.io.output.ready := io.trace.ready
  io.traceLast := block.io.output.valid && activeTraceLast
  io.busy := frameActive || block.io.busy
  io.overflow := !frameActive && block.io.input.ready && configOutOfRange

  when(io.input.fire) {
    val inputIsLast = nextBlockIndex === selectedTotalBlocks - 1.U
    activeTraceIndex := nextBlockIndex
    activeTraceLast := inputIsLast
    when(!frameActive) {
      frameActive := true.B
      activeTotalBlocks := nextTotalBlocks
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
      nextBlockIndex := 0.U
      activeTraceIndex := 0.U
    }
  }
}

class AqHfColorGammaModulationBlockOutput extends Bundle {
  import AqGammaModulationFixedPoint._

  val valueQ24 = SInt(OutputValueBits.W)
  val strategyMaskQ16 = UInt(AqStrategyMaskFixedPoint.ValueBits.W)
  val distanceQ8 = UInt(16.W)
  val fixedPointScale = UInt(16.W)
  val fixedInvQacQ16 = UInt(32.W)
  val fixedRawQuant = UInt(8.W)
  val fixedYtox = SInt(8.W)
  val fixedYtob = SInt(8.W)
  val blockIndex = UInt(32.W)
  val blockLast = Bool()
  val xybXQ12 = Vec(AqHfModulationFixedPoint.SamplesPerBlock, SInt(AqHfModulationFixedPoint.XybValueBits.W))
  val xybYQ12 = Vec(AqHfModulationFixedPoint.SamplesPerBlock, SInt(AqHfModulationFixedPoint.XybValueBits.W))
  val xybBQ12 = Vec(AqHfModulationFixedPoint.SamplesPerBlock, SInt(AqHfModulationFixedPoint.XybValueBits.W))
}

/** Shared cumulative prepared-block pipeline through HF, color, and gamma. */
class AqHfColorGammaModulationBlockPipeline extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new AqModulationBlockInput))
    val output = Decoupled(new AqHfColorGammaModulationBlockOutput)
    val busy = Output(Bool())
  })

  val color = Module(new AqHfColorModulationBlockPipeline)
  color.io.input <> io.input

  val gamma = Module(new AqGammaModulationBlock)
  gamma.io.input.bits.seedQ24 := color.io.output.bits.valueQ24
  gamma.io.input.bits.xybXQ12 := color.io.output.bits.xybXQ12
  gamma.io.input.bits.xybYQ12 := color.io.output.bits.xybYQ12
  gamma.io.input.valid := color.io.output.valid
  color.io.output.ready := gamma.io.input.ready

  val contextValid = RegInit(false.B)
  val strategyMaskQ16 = RegInit(0.U(AqStrategyMaskFixedPoint.ValueBits.W))
  val distanceQ8 = RegInit(0.U(16.W))
  val fixedPointScale = RegInit(0.U(16.W))
  val fixedInvQacQ16 = RegInit(0.U(32.W))
  val fixedRawQuant = RegInit(0.U(8.W))
  val fixedYtox = RegInit(0.S(8.W))
  val fixedYtob = RegInit(0.S(8.W))
  val blockIndex = RegInit(0.U(32.W))
  val blockLast = RegInit(false.B)
  val xybXQ12 = Reg(Vec(AqHfModulationFixedPoint.SamplesPerBlock, SInt(AqHfModulationFixedPoint.XybValueBits.W)))
  val xybYQ12 = Reg(Vec(AqHfModulationFixedPoint.SamplesPerBlock, SInt(AqHfModulationFixedPoint.XybValueBits.W)))
  val xybBQ12 = Reg(Vec(AqHfModulationFixedPoint.SamplesPerBlock, SInt(AqHfModulationFixedPoint.XybValueBits.W)))

  when(gamma.io.input.fire) {
    assert(!contextValid, "AQ HF/color/gamma pipeline accepted overlapping metadata")
    contextValid := true.B
    strategyMaskQ16 := color.io.output.bits.strategyMaskQ16
    distanceQ8 := color.io.output.bits.distanceQ8
    fixedPointScale := color.io.output.bits.fixedPointScale
    fixedInvQacQ16 := color.io.output.bits.fixedInvQacQ16
    fixedRawQuant := color.io.output.bits.fixedRawQuant
    fixedYtox := color.io.output.bits.fixedYtox
    fixedYtob := color.io.output.bits.fixedYtob
    blockIndex := color.io.output.bits.blockIndex
    blockLast := color.io.output.bits.blockLast
    xybXQ12 := color.io.output.bits.xybXQ12
    xybYQ12 := color.io.output.bits.xybYQ12
    xybBQ12 := color.io.output.bits.xybBQ12
  }

  io.output.valid := gamma.io.output.valid && contextValid
  io.output.bits.valueQ24 := gamma.io.output.bits
  io.output.bits.strategyMaskQ16 := strategyMaskQ16
  io.output.bits.distanceQ8 := distanceQ8
  io.output.bits.fixedPointScale := fixedPointScale
  io.output.bits.fixedInvQacQ16 := fixedInvQacQ16
  io.output.bits.fixedRawQuant := fixedRawQuant
  io.output.bits.fixedYtox := fixedYtox
  io.output.bits.fixedYtob := fixedYtob
  io.output.bits.blockIndex := blockIndex
  io.output.bits.blockLast := blockLast
  io.output.bits.xybXQ12 := xybXQ12
  io.output.bits.xybYQ12 := xybYQ12
  io.output.bits.xybBQ12 := xybBQ12
  gamma.io.output.ready := io.output.ready && contextValid
  io.busy := color.io.busy || gamma.io.busy || contextValid

  when(io.output.fire) {
    assert(contextValid, "AQ HF/color/gamma pipeline emitted without metadata")
    contextValid := false.B
    strategyMaskQ16 := 0.U
    distanceQ8 := 0.U
    fixedPointScale := 0.U
    fixedInvQacQ16 := 0.U
    fixedRawQuant := 0.U
    fixedYtox := 0.S
    fixedYtob := 0.S
    blockLast := false.B
  }
}

/** RGB-connected cumulative AQ path through `_gamma_modulation`.
  *
  * The shared frame scheduler captures XYB once. The reusable cumulative block
  * pipeline retains block metadata without a second converter or frame buffer.
  */
class FrameAqGammaModulationTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {

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

  val pipeline = Module(new AqHfColorGammaModulationBlockPipeline)
  pipeline.io.input <> scheduler.io.block

  io.trace.valid := pipeline.io.output.valid
  io.trace.bits.stage := TraceStage.AqGammaModulation.U
  io.trace.bits.group := 0.U
  io.trace.bits.index := pipeline.io.output.bits.blockIndex
  io.trace.bits.value := pipeline.io.output.bits.valueQ24
  pipeline.io.output.ready := io.trace.ready
  io.traceLast := pipeline.io.output.valid && pipeline.io.output.bits.blockLast
  scheduler.io.frameDone := io.trace.fire && pipeline.io.output.bits.blockLast
  io.busy := scheduler.io.busy || pipeline.io.busy
  io.overflow := scheduler.io.overflow
}
