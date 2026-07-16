// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

object AqContrastFixedPoint {
  val InputFractionBits = RgbToXybApprox.OutputFractionBits
  val GammaFractionBits = 16
  val OutputFractionBits = 16

  val MatchGammaOffsetQ12: Int = math.round(0.019 * (1 << InputFractionBits)).toInt
  val FineLimitQ12 = 1 << (InputFractionBits - 4)
  val CoarseLimitQ12 = 1 << InputFractionBits
  val HdrLimitQ12 = 8 << InputFractionBits
  val CoarseStepBits = 4
  val GammaValueBits = 25

  val XDifferenceMultiplierQ16: Int =
    math.round(23.426802998210313 * (1 << GammaFractionBits)).toInt
  val MaskingSqrtMultiplierQ16: BigInt =
    BigInt(math.round(math.sqrt(211.50759899638012 * 1e8) * (1L << GammaFractionBits)))
  val MaskingOffsetQ40: BigInt =
    (BigDecimal(26.481471032459346) * BigDecimal(BigInt(1) << 40))
      .setScale(0, BigDecimal.RoundingMode.HALF_UP)
      .toBigInt

  val MaximumXybQ12 = (8 << InputFractionBits) - 1
  val MinimumXybQ12 = -(8 << InputFractionBits)
  val MaximumTraceValue = (BigInt(1) << 31) - 1
  val MaximumMaskingInput = (BigInt(1) << 74) - 1

  private val gammaScale = 1 << GammaFractionBits

  private def referenceGammaRatio(value: Double): Double = {
    val epsilon = 1e-2
    val sgMul = 226.0480446705883
    val sgMul2 = 1.0 / 73.377132366608819
    val log2 = 0.693147181
    val sgRetMul = sgMul2 * 18.6580932135 * log2
    val sgVOffset = 7.14672470003
    val v = math.max(value, 0.0)
    val numeratorMultiplier = sgRetMul * 3.0 * sgMul
    val valueOffset = sgVOffset * log2 + epsilon
    val denominatorMultiplier = log2 * sgMul
    val numerator = numeratorMultiplier * v * v + epsilon
    val denominator = denominatorMultiplier * v * v * v + valueOffset
    denominator / numerator
  }

  val FineGammaTable: Seq[Int] =
    (0 to FineLimitQ12).map { valueQ12 =>
      math.round(referenceGammaRatio(valueQ12.toDouble / (1 << InputFractionBits)) * gammaScale).toInt
    }

  val CoarseGammaTable: Seq[Int] =
    (FineLimitQ12 to CoarseLimitQ12 by (1 << CoarseStepBits)).map { valueQ12 =>
      math.round(referenceGammaRatio(valueQ12.toDouble / (1 << InputFractionBits)) * gammaScale).toInt
    }

  private val gammaAtOne = referenceGammaRatio(1.0)
  private val gammaAtEight = referenceGammaRatio(8.0)
  val HdrSlopeQ16: Int = math.round((gammaAtEight - gammaAtOne) / 7.0 * gammaScale).toInt
  val HdrInterceptQ16: Int = math.round((gammaAtOne - HdrSlopeQ16.toDouble / gammaScale) * gammaScale).toInt

  private def roundShift(value: BigInt, shift: Int): BigInt =
    (value + (BigInt(1) << (shift - 1))) >> shift

  private def integerSqrt(value: BigInt): BigInt = {
    require(value >= 0, "integer square root input must be nonnegative")
    if (value == 0) {
      BigInt(0)
    } else {
      var root = BigInt(1) << ((value.bitLength + 1) / 2)
      var next = (root + value / root) >> 1
      while (next < root) {
        root = next
        next = (root + value / root) >> 1
      }
      root
    }
  }

  def gammaRatioQ16(centerYQ12: Int): Int = {
    val adjusted = math.max(0, centerYQ12 + MatchGammaOffsetQ12)
    if (adjusted <= FineLimitQ12) {
      FineGammaTable(adjusted)
    } else if (adjusted < CoarseLimitQ12) {
      val relative = adjusted - FineLimitQ12
      val base = relative >> CoarseStepBits
      val fraction = relative & ((1 << CoarseStepBits) - 1)
      val scale = 1 << CoarseStepBits
      (CoarseGammaTable(base) * (scale - fraction) +
        CoarseGammaTable(base + 1) * fraction + scale / 2) >> CoarseStepBits
    } else if (adjusted == CoarseLimitQ12) {
      CoarseGammaTable.last
    } else {
      val clamped = math.min(adjusted, HdrLimitQ12)
      ((BigInt(clamped) * HdrSlopeQ16 + (1 << (InputFractionBits - 1))) >> InputFractionBits).toInt +
        HdrInterceptQ16
    }
  }

  def contrastPixelQ16(
      centerXQ12: Int,
      centerYQ12: Int,
      leftXQ12: Int,
      rightXQ12: Int,
      upXQ12: Int,
      downXQ12: Int,
      leftYQ12: Int,
      rightYQ12: Int,
      upYQ12: Int,
      downYQ12: Int
  ): BigInt = {
    def clamp(value: Int): BigInt =
      BigInt(math.max(MinimumXybQ12, math.min(MaximumXybQ12, value)))

    val centerX = clamp(centerXQ12)
    val centerY = clamp(centerYQ12)
    val xNeighborSum = clamp(leftXQ12) + clamp(rightXQ12) + clamp(upXQ12) + clamp(downXQ12)
    val yNeighborSum = clamp(leftYQ12) + clamp(rightYQ12) + clamp(upYQ12) + clamp(downYQ12)
    val gamma = BigInt(gammaRatioQ16(centerY.toInt))
    val scaledXQ20 = roundShift((centerX * 4 - xNeighborSum) * gamma, 10)
    val scaledYQ20 = roundShift((centerY * 4 - yNeighborSum) * gamma, 10)
    val differenceQ40 =
      scaledYQ20 * scaledYQ20 +
        roundShift(scaledXQ20 * scaledXQ20 * XDifferenceMultiplierQ16, GammaFractionBits)
    val differenceQ24 = roundShift(differenceQ40, 16)
    val maskingInput =
      (differenceQ24 * MaskingSqrtMultiplierQ16 + MaskingOffsetQ40).min(MaximumMaskingInput)
    val root = integerSqrt(maskingInput)
    roundShift(root, 6).min(MaximumTraceValue)
  }

  def contrastCellQ16(values: Seq[BigInt]): BigInt = {
    require(values.length == 16, "contrast cell requires one 4x4 group")
    roundShift(values.sum, 2).min(MaximumTraceValue)
  }
}

/** Combinational unsigned integer square root. */
class UnsignedIntegerSquareRoot(inputBits: Int) extends Module {
  require(inputBits > 0, "inputBits must be positive")

  private val rootBits = (inputBits + 1) / 2
  private val paddedBits = rootBits * 2
  private val remainderBits = paddedBits + 2

  val io = IO(new Bundle {
    val input = Input(UInt(inputBits.W))
    val output = Output(UInt(rootBits.W))
  })

  val paddedInput = if (inputBits == paddedBits) io.input else Cat(0.U(1.W), io.input)
  val remainders = Wire(Vec(rootBits + 1, UInt(remainderBits.W)))
  val roots = Wire(Vec(rootBits + 1, UInt((rootBits + 1).W)))
  remainders(0) := 0.U
  roots(0) := 0.U

  for (step <- 0 until rootBits) {
    val pairIndex = rootBits - step - 1
    val pair = paddedInput(2 * pairIndex + 1, 2 * pairIndex)
    val shiftedRemainder = Cat(remainders(step)(remainderBits - 3, 0), pair)
    val trial = ((roots(step) << 2) | 1.U).pad(remainderBits)
    val take = shiftedRemainder >= trial
    remainders(step + 1) := Mux(take, shiftedRemainder - trial, shiftedRemainder)
    roots(step + 1) := Cat(roots(step)(rootBits - 1, 0), take)
  }

  io.output := roots(rootBits)(rootBits - 1, 0)
}

/** Computes one libjxl-tiny AQ contrast contribution from Q12 X/Y neighbors. */
class AqContrastPixel(inputBits: Int = 32) extends Module {
  import AqContrastFixedPoint._

  require(inputBits >= 16, "AQ contrast input must contain the supported Q12 range")

  val io = IO(new Bundle {
    val centerX = Input(SInt(inputBits.W))
    val centerY = Input(SInt(inputBits.W))
    val leftX = Input(SInt(inputBits.W))
    val rightX = Input(SInt(inputBits.W))
    val upX = Input(SInt(inputBits.W))
    val downX = Input(SInt(inputBits.W))
    val leftY = Input(SInt(inputBits.W))
    val rightY = Input(SInt(inputBits.W))
    val upY = Input(SInt(inputBits.W))
    val downY = Input(SInt(inputBits.W))
    val output = Output(UInt(31.W))
  })

  private def clampQ12(value: SInt): SInt = {
    val clamped = Wire(SInt(16.W))
    clamped := Mux(
      value > MaximumXybQ12.S,
      MaximumXybQ12.S,
      Mux(value < MinimumXybQ12.S, MinimumXybQ12.S, value)
    )
    clamped
  }

  private def sum4(a: SInt, b: SInt, c: SInt, d: SInt): SInt =
    a +& b +& c +& d

  private def roundShiftSigned(value: SInt, shift: Int): SInt =
    (value +& (BigInt(1) << (shift - 1)).S) >> shift

  val centerX = clampQ12(io.centerX)
  val centerY = clampQ12(io.centerY)
  val leftX = clampQ12(io.leftX)
  val rightX = clampQ12(io.rightX)
  val upX = clampQ12(io.upX)
  val downX = clampQ12(io.downX)
  val leftY = clampQ12(io.leftY)
  val rightY = clampQ12(io.rightY)
  val upY = clampQ12(io.upY)
  val downY = clampQ12(io.downY)

  val gamma = Module(new AqGammaRatioQ16(inputBits = 16))
  gamma.io.centerYQ12 := centerY

  val differenceXQ14 = (centerX << 2) -& sum4(leftX, rightX, upX, downX)
  val differenceYQ14 = (centerY << 2) -& sum4(leftY, rightY, upY, downY)
  val scaledXFull = roundShiftSigned(differenceXQ14 * gamma.io.output.zext, 10)
  val scaledYFull = roundShiftSigned(differenceYQ14 * gamma.io.output.zext, 10)
  val scaledXQ20 = scaledXFull
  val scaledYQ20 = scaledYFull
  val squareXQ40 = (scaledXQ20 * scaledXQ20).asUInt
  val squareYQ40 = (scaledYQ20 * scaledYQ20).asUInt
  val weightedXWide = squareXQ40 * XDifferenceMultiplierQ16.U
  val weightedXQ40 = ((weightedXWide + (1 << 15).U) >> GammaFractionBits)(77, 0)
  val differenceQ40 = squareYQ40.pad(78) +& weightedXQ40
  val differenceQ24 = ((differenceQ40 + (1 << 15).U) >> 16).pad(64)
  val maskingInputWide =
    differenceQ24 * MaskingSqrtMultiplierQ16.U +& MaskingOffsetQ40.U
  val maskingInput = Mux(
    maskingInputWide > MaximumMaskingInput.U,
    MaximumMaskingInput.U(74.W),
    maskingInputWide(73, 0)
  )
  val squareRoot = Module(new UnsignedIntegerSquareRoot(74))
  squareRoot.io.input := maskingInput
  val roundedOutput = (squareRoot.io.output +& 32.U) >> 6
  io.output := Mux(roundedOutput > MaximumTraceValue.U, MaximumTraceValue.U, roundedOutput(30, 0))
}

/** Piecewise gamma-ratio approximation used by the AQ contrast stage. */
class AqGammaRatioQ16(inputBits: Int = 16) extends Module {
  import AqContrastFixedPoint._

  require(inputBits >= 16, "gamma input must contain the supported Q12 range")

  val io = IO(new Bundle {
    val centerYQ12 = Input(SInt(inputBits.W))
    val output = Output(UInt(GammaValueBits.W))
  })

  val adjustedSigned = io.centerYQ12 +& MatchGammaOffsetQ12.S
  val adjusted = Mux(adjustedSigned <= 0.S, 0.U, adjustedSigned.asUInt)
  val fineInput = Mux(adjusted > FineLimitQ12.U, FineLimitQ12.U, adjusted)(8, 0)
  val fineTable = VecInit(FineGammaTable.map(_.U(GammaValueBits.W)))
  val fineValue = fineTable(fineInput)

  val coarseClamped = Mux(adjusted > CoarseLimitQ12.U, CoarseLimitQ12.U, adjusted)
  val coarseRelative = coarseClamped - FineLimitQ12.U
  val coarseBaseRaw = coarseRelative >> CoarseStepBits
  val coarseBase = Mux(
    coarseBaseRaw >= (CoarseGammaTable.length - 1).U,
    (CoarseGammaTable.length - 2).U,
    coarseBaseRaw
  )(7, 0)
  val coarseFraction = coarseRelative(CoarseStepBits - 1, 0)
  val coarseTable = VecInit(CoarseGammaTable.map(_.U(GammaValueBits.W)))
  val coarseLow = coarseTable(coarseBase)
  val coarseHigh = coarseTable(coarseBase + 1.U)
  val coarseScale = 1 << CoarseStepBits
  val coarseInterpolated =
    (coarseLow * (coarseScale.U - coarseFraction) +&
      coarseHigh * coarseFraction + (coarseScale / 2).U) >> CoarseStepBits
  val coarseValue = Mux(adjusted === CoarseLimitQ12.U, coarseTable.last, coarseInterpolated)

  val hdrInput = Mux(adjusted > HdrLimitQ12.U, HdrLimitQ12.U, adjusted)
  val hdrProduct = hdrInput * HdrSlopeQ16.U
  val hdrValue =
    ((hdrProduct + (1 << (InputFractionBits - 1)).U) >> InputFractionBits) +& HdrInterceptQ16.U

  io.output := Mux(
    adjusted <= FineLimitQ12.U,
    fineValue,
    Mux(adjusted <= CoarseLimitQ12.U, coarseValue, hdrValue.pad(GammaValueBits))
  )
}

/** Buffers approximate XYB from one RGB frame and emits Q16 AQ contrast cells.
  *
  * Each output is the quarter-resolution `pre_erosion` value for one 4x4 pixel
  * cell, in row-major order over the block-padded frame. Horizontal tile halos
  * share the global grid, while vertical neighborhoods clamp at each 64-pixel
  * AC-group stripe boundary to match libjxl-tiny's 256x64 stripe conversion.
  */
class FrameAqContrastTraceStage(
    c: HjxlConfig = HjxlConfig(),
    xybOutputFractionBits: Int = RgbToXybApprox.OutputFractionBits
) extends Module {
  import AqContrastFixedPoint._

  private val cellDim = 4
  private val samplesPerCell = cellDim * cellDim
  private val stripeDim = HjxlConstants.TileDim
  private val stripeShift = log2Ceil(stripeDim)
  private val numPixels = c.maxFrameWidth * c.maxFrameHeight
  private val frameIndexBits = log2Ceil(numPixels)
  private val frameCountBits = log2Ceil(numPixels + 1)
  private val widthBits = log2Ceil(c.maxFrameWidth + 1)
  private val heightBits = log2Ceil(c.maxFrameHeight + 1)

  require(c.traceValueBits == 32, "AQ contrast trace packing currently requires signed 32-bit values")
  require(
    xybOutputFractionBits >= RgbToXybApprox.OutputFractionBits,
    "AQ contrast XYB precision cannot be below Q12"
  )

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

  val xybX = Reg(Vec(numPixels, SInt(c.traceValueBits.W)))
  val xybY = Reg(Vec(numPixels, SInt(c.traceValueBits.W)))

  val receiving :: contrasting :: Nil = Enum(2)
  val state = RegInit(receiving)
  val received = RegInit(0.U(frameCountBits.W))
  val activePixelCount = RegInit(0.U(frameCountBits.W))
  val latchedWidth = RegInit(0.U(widthBits.W))
  val latchedHeight = RegInit(0.U(heightBits.W))
  val paddedWidth = RegInit(0.U(widthBits.W))
  val paddedHeight = RegInit(0.U(heightBits.W))
  val cellsX = RegInit(0.U(widthBits.W))
  val totalCells = RegInit(0.U(32.W))
  val cellOrdinal = RegInit(0.U(32.W))
  val currentCellX = RegInit(0.U(widthBits.W))
  val currentCellY = RegInit(0.U(heightBits.W))
  val sampleOrdinal = RegInit(0.U(log2Ceil(samplesPerCell).W))
  val cellAccumulator = RegInit(0.U(35.W))
  val outputValid = RegInit(false.B)
  val outputValue = RegInit(0.U(31.W))
  val outputLast = RegInit(false.B)

  private def ceilToBlock(value: UInt): UInt = {
    val block = HjxlConstants.BlockDim.U
    ((value +& (block - 1.U)) / block) * block
  }

  val configWidth = io.config.xsize(widthBits - 1, 0)
  val configHeight = io.config.ysize(heightBits - 1, 0)
  val nextPaddedWidth = ceilToBlock(configWidth)(widthBits - 1, 0)
  val nextPaddedHeight = ceilToBlock(configHeight)(heightBits - 1, 0)
  val nextPixelCount = configWidth * configHeight
  val configOutOfRange =
    io.config.xsize === 0.U || io.config.ysize === 0.U ||
      io.config.xsize > c.maxFrameWidth.U || io.config.ysize > c.maxFrameHeight.U ||
      nextPaddedWidth > c.maxFrameWidth.U || nextPaddedHeight > c.maxFrameHeight.U
  val acceptingNewFrame = state === receiving && received === 0.U
  val canReceive = state === receiving && (!acceptingNewFrame || !configOutOfRange)
  val selectedPixelCount = Mux(acceptingNewFrame, nextPixelCount, activePixelCount)

  private val hasHigherPrecisionOutput =
    xybOutputFractionBits > RgbToXybApprox.OutputFractionBits
  val converter = Module(
    new RgbToXybApprox(
      c,
      outputFractionBits = xybOutputFractionBits,
      includeQ12Output = hasHigherPrecisionOutput,
      includeQ18Output =
        xybOutputFractionBits > RgbVarDctFixedPoint.AnalysisXybFractionBits
    )
  )
  converter.io.input.bits := io.input.bits
  converter.io.input.valid := io.input.valid && canReceive
  converter.io.output.ready := canReceive
  io.input.ready := converter.io.input.ready && canReceive
  io.xybAccepted.valid := converter.io.output.fire
  io.xybAccepted.bits := converter.io.outputQ12
    .map(_.bits)
    .getOrElse(converter.io.output.bits)
  io.analysisXybAccepted.foreach { accepted =>
    accepted := converter.io.outputQ18.getOrElse(converter.io.output)
  }
  io.quantizationXybAccepted.foreach { accepted =>
    accepted.valid := converter.io.output.fire
    accepted.bits := converter.io.output.bits
  }

  val cellBaseX = (currentCellX << log2Ceil(cellDim))(widthBits - 1, 0)
  val cellBaseY = (currentCellY << log2Ceil(cellDim))(heightBits - 1, 0)
  val localX = sampleOrdinal(log2Ceil(cellDim) - 1, 0)
  val localY = sampleOrdinal >> log2Ceil(cellDim)
  val sampleX = (cellBaseX + localX)(widthBits - 1, 0)
  val sampleY = (cellBaseY + localY)(heightBits - 1, 0)
  val leftX = Mux(sampleX === 0.U, sampleX, sampleX - 1.U)
  val rightX = Mux(sampleX + 1.U >= paddedWidth, sampleX, sampleX + 1.U)
  val stripeBaseY = (sampleY >> stripeShift) << stripeShift
  val stripeEndCandidate = stripeBaseY + stripeDim.U
  val stripeEndY = Mux(stripeEndCandidate < paddedHeight, stripeEndCandidate, paddedHeight)
  val upY = Mux(sampleY === stripeBaseY, sampleY, sampleY - 1.U)
  val downY = Mux(sampleY + 1.U >= stripeEndY, sampleY, sampleY + 1.U)

  private def frameSample(samples: Vec[SInt], paddedX: UInt, paddedY: UInt): SInt = {
    val sourceX = Mux(paddedX >= latchedWidth, latchedWidth - 1.U, paddedX)
    val sourceY = Mux(paddedY >= latchedHeight, latchedHeight - 1.U, paddedY)
    val index = sourceY * latchedWidth + sourceX
    samples(index(frameIndexBits - 1, 0))
  }

  val contrast = Module(new AqContrastPixel(c.traceValueBits))
  contrast.io.centerX := frameSample(xybX, sampleX, sampleY)
  contrast.io.centerY := frameSample(xybY, sampleX, sampleY)
  contrast.io.leftX := frameSample(xybX, leftX, sampleY)
  contrast.io.rightX := frameSample(xybX, rightX, sampleY)
  contrast.io.upX := frameSample(xybX, sampleX, upY)
  contrast.io.downX := frameSample(xybX, sampleX, downY)
  contrast.io.leftY := frameSample(xybY, leftX, sampleY)
  contrast.io.rightY := frameSample(xybY, rightX, sampleY)
  contrast.io.upY := frameSample(xybY, sampleX, upY)
  contrast.io.downY := frameSample(xybY, sampleX, downY)

  io.trace.valid := outputValid
  io.trace.bits.stage := TraceStage.AqContrast.U
  io.trace.bits.group := 0.U
  io.trace.bits.index := cellOrdinal
  io.trace.bits.value := Cat(0.U(1.W), outputValue).asSInt
  io.traceLast := outputValid && outputLast
  io.busy := state === contrasting || received =/= 0.U || outputValid
  io.overflow := acceptingNewFrame && configOutOfRange

  when(converter.io.output.fire) {
    val receiveIndex = received(frameIndexBits - 1, 0)
    xybX(receiveIndex) := converter.io.outputQ12
      .map(_.bits.xybX)
      .getOrElse(converter.io.output.bits.xybX)
    xybY(receiveIndex) := converter.io.outputQ12
      .map(_.bits.xybY)
      .getOrElse(converter.io.output.bits.xybY)
    val nextReceived = received + 1.U

    when(acceptingNewFrame) {
      latchedWidth := configWidth
      latchedHeight := configHeight
      paddedWidth := nextPaddedWidth
      paddedHeight := nextPaddedHeight
      activePixelCount := nextPixelCount(frameCountBits - 1, 0)
      cellsX := nextPaddedWidth >> log2Ceil(cellDim)
      totalCells :=
        (nextPaddedWidth >> log2Ceil(cellDim)) * (nextPaddedHeight >> log2Ceil(cellDim))
    }

    when(nextReceived === selectedPixelCount) {
      state := contrasting
      received := 0.U
      cellOrdinal := 0.U
      currentCellX := 0.U
      currentCellY := 0.U
      sampleOrdinal := 0.U
      cellAccumulator := 0.U
    }.otherwise {
      received := nextReceived
    }
  }

  when(state === contrasting && !outputValid) {
    val nextAccumulator = cellAccumulator +& contrast.io.output
    when(sampleOrdinal === (samplesPerCell - 1).U) {
      val roundedCell = (nextAccumulator + 2.U) >> 2
      outputValue := Mux(
        roundedCell > MaximumTraceValue.U,
        MaximumTraceValue.U,
        roundedCell(30, 0)
      )
      outputValid := true.B
      outputLast := cellOrdinal === totalCells - 1.U
      sampleOrdinal := 0.U
      cellAccumulator := 0.U
    }.otherwise {
      sampleOrdinal := sampleOrdinal + 1.U
      cellAccumulator := nextAccumulator(34, 0)
    }
  }

  when(io.trace.fire) {
    outputValid := false.B
    outputLast := false.B
    when(outputLast) {
      state := receiving
      activePixelCount := 0.U
      latchedWidth := 0.U
      latchedHeight := 0.U
      paddedWidth := 0.U
      paddedHeight := 0.U
      cellsX := 0.U
      totalCells := 0.U
      cellOrdinal := 0.U
      currentCellX := 0.U
      currentCellY := 0.U
    }.otherwise {
      cellOrdinal := cellOrdinal + 1.U
      when(currentCellX + 1.U === cellsX) {
        currentCellX := 0.U
        currentCellY := currentCellY + 1.U
      }.otherwise {
        currentCellX := currentCellX + 1.U
      }
    }
  }
}
