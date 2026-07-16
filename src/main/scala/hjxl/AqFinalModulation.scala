// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

object AqFinalModulationFixedPoint {
  val FractionBits = AdaptiveQuantizationFixedPoint.FractionBits
  val ValueBits = AdaptiveQuantizationFixedPoint.ValueBits
  val OneQ24 = BigInt(1) << FractionBits
  val Log2eQ24 = BigInt(24204406)
  val PowTableIndexBits = 8
  val PowInterpolationBits = FractionBits - PowTableIndexBits
  val PowTableValueBits = 26
  val MaximumUnsigned32 = (BigInt(1) << ValueBits) - 1

  private val scale = BigInt(1) << FractionBits

  private def referenceFastPow2(value: Double): Double = {
    val input = value.toFloat
    val floorValue = math.floor(input.toDouble).toInt
    val exponentBits = (floorValue + 127) << 23
    val exponent = java.lang.Float.intBitsToFloat(exponentBits)
    val fraction = (input - floorValue.toFloat).toFloat
    var numerator = (fraction + 1.01749063e1f).toFloat
    numerator = (numerator * fraction + 4.88687798e1f).toFloat
    numerator = (numerator * fraction + 9.85506591e1f).toFloat
    numerator = (numerator * exponent).toFloat
    var denominator = (fraction * 2.10242958e-1f - 2.22328856e-2f).toFloat
    denominator = (denominator * fraction - 1.94414990e1f).toFloat
    denominator = (denominator * fraction + 9.85506633e1f).toFloat
    (numerator / denominator).toFloat.toDouble
  }

  val FastPow2FractionTable: Seq[BigInt] =
    (0 to (1 << PowTableIndexBits)).map { index =>
      BigInt(
        math.floor(
          referenceFastPow2(index.toDouble / (1 << PowTableIndexBits)) * scale.toDouble + 0.5
        ).toLong
      )
    }

  require(FastPow2FractionTable.forall(_.bitLength <= PowTableValueBits))

  private def roundShiftSigned(value: BigInt, shift: Int): BigInt =
    if (value >= 0) {
      (value + (BigInt(1) << (shift - 1))) >> shift
    } else {
      -(((-value) + (BigInt(1) << (shift - 1))) >> shift)
    }

  private def roundedMultiplyQ24(left: BigInt, right: BigInt): BigInt =
    ((left * right) + (BigInt(1) << (FractionBits - 1))) >> FractionBits

  /** Approximate `fast_pow2f(seed * log2(e))` as unsigned Q24.
    *
    * The full signed-Q24 seed domain is accepted. Results below one Q24 LSB
    * round to zero and results above the unsigned 32-bit carrier saturate.
    */
  def fastExpQ24(seedQ24: BigInt): BigInt = {
    require(
      seedQ24 >= -(BigInt(1) << 31) && seedQ24 < (BigInt(1) << 31),
      "AQ final-modulation seed must fit signed 32-bit Q24"
    )
    val log2Q24 = roundShiftSigned(seedQ24 * Log2eQ24, FractionBits)
    val exponent = (log2Q24 >> FractionBits).toInt
    val fraction = log2Q24 - (BigInt(exponent) << FractionBits)
    val index = (fraction >> PowInterpolationBits).toInt
    val interpolation = fraction & ((BigInt(1) << PowInterpolationBits) - 1)
    val interpolationScale = BigInt(1) << PowInterpolationBits
    val mantissa =
      (FastPow2FractionTable(index) * (interpolationScale - interpolation) +
        FastPow2FractionTable(index + 1) * interpolation + interpolationScale / 2) >>
        PowInterpolationBits
    val shifted =
      if (exponent >= 0) mantissa << exponent
      else if (-exponent > PowTableValueBits) BigInt(0)
      else (mantissa + (BigInt(1) << (-exponent - 1))) >> -exponent
    shifted.min(MaximumUnsigned32)
  }

  /** Apply the final scale/dampen/add step to one cumulative gamma seed. */
  def modulateQ24(seedQ24: BigInt, scaleQ24: BigInt, dampenQ24: BigInt): BigInt = {
    require(scaleQ24 >= 0 && scaleQ24 <= MaximumUnsigned32, "AQ scale must fit unsigned Q24")
    require(dampenQ24 >= 0 && dampenQ24 <= OneQ24, "AQ dampen must be in Q24 [0, 1]")
    val powerQ24 = fastExpQ24(seedQ24)
    val baseLevelQ24 = (scaleQ24 + 1) >> 1
    val multiplierQ24 = roundedMultiplyQ24(scaleQ24, dampenQ24)
    val addQ24 = roundedMultiplyQ24(OneQ24 - dampenQ24, baseLevelQ24)
    (roundedMultiplyQ24(powerQ24, multiplierQ24) + addQ24).min(MaximumUnsigned32)
  }
}

/** Normalized table approximation of `fast_pow2f(seed * log2(e))`. */
class AqFastExpQ24 extends Module {
  import AqFinalModulationFixedPoint._

  val io = IO(new Bundle {
    val seedQ24 = Input(SInt(ValueBits.W))
    val outputQ24 = Output(UInt(ValueBits.W))
  })

  private val logValueBits = 35
  private val productValueBits = 60

  val product = Wire(SInt(productValueBits.W))
  product := (io.seedQ24 * Log2eQ24.S).pad(productValueBits)
  val productNegative = product < 0.S
  val productMagnitude = Mux(productNegative, -product, product).asUInt
  val roundedMagnitude =
    (productMagnitude +& (BigInt(1) << (FractionBits - 1)).U) >> FractionBits
  val log2Q24 = Wire(SInt(logValueBits.W))
  log2Q24 := Mux(productNegative, -roundedMagnitude.asSInt, roundedMagnitude.asSInt)

  val exponent = log2Q24 >> FractionBits
  val fraction = log2Q24.asUInt(FractionBits - 1, 0)
  val tableIndex = fraction(FractionBits - 1, PowInterpolationBits).pad(PowTableIndexBits + 1)
  val interpolation = fraction(PowInterpolationBits - 1, 0)
  val interpolationScale = 1 << PowInterpolationBits
  val table = VecInit(FastPow2FractionTable.map(_.U(PowTableValueBits.W)))
  val weighted =
    table(tableIndex) * (interpolationScale.U - interpolation) +&
      table(tableIndex + 1.U) * interpolation + (interpolationScale / 2).U
  val mantissa = (weighted >> PowInterpolationBits)(PowTableValueBits - 1, 0)

  val leftShift = exponent.asUInt(2, 0)
  val shiftedLeft = mantissa.pad(ValueBits + 8) << leftShift
  val leftSaturated = Mux(
    shiftedLeft > MaximumUnsigned32.U,
    MaximumUnsigned32.U,
    shiftedLeft(ValueBits - 1, 0)
  )

  val negativeShift = (-exponent).asUInt
  val negativeShiftTooLarge = negativeShift > PowTableValueBits.U
  val roundingBiases = VecInit(
    (1 to PowTableValueBits).map(shift => (BigInt(1) << (shift - 1)).U(PowTableValueBits.W))
  )
  val boundedNegativeShift = Wire(UInt(5.W))
  boundedNegativeShift := Mux(
    negativeShift === 0.U,
    1.U,
    Mux(
      negativeShiftTooLarge,
      PowTableValueBits.U,
      negativeShift(4, 0)
    )
  )
  val rightBias = roundingBiases((boundedNegativeShift - 1.U)(4, 0))
  val shiftedRight = (mantissa +& rightBias) >> boundedNegativeShift

  io.outputQ24 := Mux(
    exponent >= 8.S,
    MaximumUnsigned32.U,
    Mux(
      exponent >= 0.S,
      leftSaturated,
      Mux(negativeShiftTooLarge, 0.U, shiftedRight.pad(ValueBits))
    )
  )
}

class AqFinalModulationBlockInput extends Bundle {
  import AqFinalModulationFixedPoint._

  val seedQ24 = SInt(ValueBits.W)
  val scaleQ24 = UInt(ValueBits.W)
  val dampenQ24 = UInt((FractionBits + 1).W)
}

/** Converts one cumulative signed-Q24 gamma seed into an unsigned-Q24 AQ map. */
class AqFinalModulationBlock extends Module {
  import AqFinalModulationFixedPoint._

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new AqFinalModulationBlockInput))
    val output = Decoupled(UInt(ValueBits.W))
    val busy = Output(Bool())
  })

  val outputValid = RegInit(false.B)
  val outputValue = RegInit(0.U(ValueBits.W))
  io.input.ready := !outputValid || io.output.ready
  io.output.valid := outputValid
  io.output.bits := outputValue
  io.busy := outputValid

  val power = Module(new AqFastExpQ24)
  power.io.seedQ24 := io.input.bits.seedQ24

  private def roundedMultiplyQ24(left: UInt, right: UInt): UInt = {
    val product = left * right
    val shifted = (product +& (BigInt(1) << (FractionBits - 1)).U) >> FractionBits
    Mux(shifted > MaximumUnsigned32.U, MaximumUnsigned32.U, shifted(ValueBits - 1, 0))
  }

  assert(
    !io.input.valid || io.input.bits.dampenQ24 <= OneQ24.U,
    "AQ final-modulation dampen exceeds one"
  )
  val baseLevelQ24 = ((io.input.bits.scaleQ24 +& 1.U) >> 1)(ValueBits - 1, 0)
  val multiplierQ24 = roundedMultiplyQ24(io.input.bits.scaleQ24, io.input.bits.dampenQ24)
  val addQ24 = roundedMultiplyQ24(OneQ24.U - io.input.bits.dampenQ24, baseLevelQ24)
  val powerTermQ24 = roundedMultiplyQ24(power.io.outputQ24, multiplierQ24)
  val result = powerTermQ24 +& addQ24
  val nextOutput = Mux(
    result > MaximumUnsigned32.U,
    MaximumUnsigned32.U,
    result(ValueBits - 1, 0)
  )

  when(io.output.fire && !io.input.fire) {
    outputValid := false.B
  }
  when(io.input.fire) {
    outputValid := true.B
    outputValue := nextOutput
  }
}

/** Applies final AQ modulation to prepared seeds in padded-block raster order. */
class FramePreparedAqFinalModulationTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  import AqFinalModulationFixedPoint._

  private val blockDim = HjxlConstants.BlockDim
  private val maxXBlocks = c.maxFrameWidth / blockDim
  private val maxYBlocks = c.maxFrameHeight / blockDim
  private val maxBlocks = maxXBlocks * maxYBlocks
  private val widthBits = log2Ceil(c.maxFrameWidth + 1)
  private val heightBits = log2Ceil(c.maxFrameHeight + 1)

  require(c.traceValueBits == ValueBits, "AQ final-map trace requires 32-bit values")
  require(maxBlocks > 0, "configured frame must contain at least one block")

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new AqFinalModulationBlockInput))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  private def ceilDivBlock(value: UInt): UInt =
    (value +& (blockDim - 1).U) >> log2Ceil(blockDim)

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

  val block = Module(new AqFinalModulationBlock)
  val selectedTotalBlocks = Mux(frameActive, activeTotalBlocks, nextTotalBlocks)
  val inputAllowed = frameActive || !configOutOfRange
  block.io.input.bits := io.input.bits
  block.io.input.valid := io.input.valid && inputAllowed && !activeTraceLast
  io.input.ready := block.io.input.ready && inputAllowed && !activeTraceLast

  io.trace.valid := block.io.output.valid
  io.trace.bits.stage := TraceStage.AqFinalMap.U
  io.trace.bits.group := 0.U
  io.trace.bits.index := activeTraceIndex
  io.trace.bits.value := block.io.output.bits.asSInt
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

class AqFinalMapFrameOutput(includeQuantizationPrecision: Boolean = false) extends Bundle {
  import AqFinalModulationFixedPoint._

  val aqMapQ24 = UInt(ValueBits.W)
  val strategyMaskQ16 = UInt(AqStrategyMaskFixedPoint.ValueBits.W)
  val distanceQ8 = UInt(16.W)
  val invGlobalScaleQ24 = UInt(ValueBits.W)
  val scaleQ16 = UInt(16.W)
  val fixedInvQacQ16 = UInt(32.W)
  val fixedRawQuant = UInt(8.W)
  val invDcFactorQ16 = Vec(3, UInt(32.W))
  val xQmMultiplierQ16 = UInt(32.W)
  val fixedYtox = SInt(8.W)
  val fixedYtob = SInt(8.W)
  val blockIndex = UInt(32.W)
  val blockLast = Bool()
  val xybXQ12 = Vec(AqHfModulationFixedPoint.SamplesPerBlock, SInt(AqHfModulationFixedPoint.XybValueBits.W))
  val xybYQ12 = Vec(AqHfModulationFixedPoint.SamplesPerBlock, SInt(AqHfModulationFixedPoint.XybValueBits.W))
  val xybBQ12 = Vec(AqHfModulationFixedPoint.SamplesPerBlock, SInt(AqHfModulationFixedPoint.XybValueBits.W))
  val precisionXyb =
    if (includeQuantizationPrecision)
      Some(
        Vec(
          3,
          Vec(
            AqHfModulationFixedPoint.SamplesPerBlock,
            SInt(AqHfModulationFixedPoint.XybValueBits.W)
          )
        )
      )
    else None
  val quantizationXyb =
    if (includeQuantizationPrecision)
      Some(
        Vec(
          3,
          Vec(
            AqHfModulationFixedPoint.SamplesPerBlock,
            SInt(AqHfModulationFixedPoint.XybValueBits.W)
          )
        )
      )
    else None
}

/** Shared RGB pipeline from raster pixels through the final per-block AQ map.
  *
  * Unsupported distances consistently use the shared distance-1 fallback for
  * every cumulative operation. The AXI-Lite shell reports that fallback through
  * its existing status bit. The focused VarDCT specialization stores the
  * converter's exact Q18 analysis tap plus its Q21 quantization output here
  * and reads one padded block beside the completed Q12 AQ context; ordinary
  * routes do not elaborate either store.
  */
class FrameAqFinalMapPipeline(
    c: HjxlConfig = HjxlConfig(),
    includeQuantizationPrecision: Boolean = false
) extends Module {
  private val numPixels = c.maxFrameWidth * c.maxFrameHeight
  private val frameIndexBits = log2Ceil(numPixels)
  private val frameCountBits = log2Ceil(numPixels + 1)
  private val widthBits = log2Ceil(c.maxFrameWidth + 1)
  private val heightBits = log2Ceil(c.maxFrameHeight + 1)

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val xybAccepted = Output(Valid(new XybPixel(c)))
    val output = Decoupled(new AqFinalMapFrameOutput(includeQuantizationPrecision))
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val inputDistance = Module(new DistanceParamsLookup)
  inputDistance.io.distanceQ8 := io.config.distanceQ8
  val effectiveConfig = Wire(new FrameConfig(c))
  effectiveConfig := io.config
  effectiveConfig.distanceQ8 := Mux(
    inputDistance.io.supported,
    io.config.distanceQ8,
    DistanceParamsLookup.Distance1Q8.U
  )

  val scheduler = Module(
    new FrameAqModulationBlockStage(
      c,
      includeQuantizationPrecision = includeQuantizationPrecision
    )
  )
  scheduler.io.config := effectiveConfig
  scheduler.io.input <> io.input
  io.xybAccepted := scheduler.io.xybAccepted

  val precisionFrame =
    if (includeQuantizationPrecision)
      Some(Reg(Vec(numPixels, Vec(3, SInt(AqHfModulationFixedPoint.XybValueBits.W)))))
    else None
  val quantizationFrame =
    if (includeQuantizationPrecision)
      Some(Reg(Vec(numPixels, Vec(3, SInt(AqHfModulationFixedPoint.XybValueBits.W)))))
    else None
  val precisionReceived =
    if (includeQuantizationPrecision) Some(RegInit(0.U(frameCountBits.W))) else None
  val precisionWidth =
    if (includeQuantizationPrecision) Some(RegInit(0.U(widthBits.W))) else None
  val precisionHeight =
    if (includeQuantizationPrecision) Some(RegInit(0.U(heightBits.W))) else None
  val precisionBlocksX =
    if (includeQuantizationPrecision) Some(RegInit(0.U(widthBits.W))) else None

  scheduler.io.analysisXybAccepted.foreach { accepted =>
    when(accepted.valid) {
      val quantization = scheduler.io.quantizationXybAccepted.get
      val received = precisionReceived.get
      assert(received < numPixels.U, "AQ final-map precision frame store overflow")
      assert(quantization.valid, "AQ final-map Q21 quantization sideband missed a Q18 sample")
      assert(
        quantization.bits.x === accepted.bits.x && quantization.bits.y === accepted.bits.y,
        "AQ final-map Q18/Q21 sideband coordinates diverged"
      )
      val target = precisionFrame.get(received(frameIndexBits - 1, 0))
      target(0) := accepted.bits.xybX
      target(1) := accepted.bits.xybY
      target(2) := accepted.bits.xybB
      val quantizationTarget = quantizationFrame.get(received(frameIndexBits - 1, 0))
      quantizationTarget(0) := quantization.bits.xybX
      quantizationTarget(1) := quantization.bits.xybY
      quantizationTarget(2) := quantization.bits.xybB
      when(received === 0.U) {
        precisionWidth.get := effectiveConfig.xsize(widthBits - 1, 0)
        precisionHeight.get := effectiveConfig.ysize(heightBits - 1, 0)
        precisionBlocksX.get :=
          ((effectiveConfig.xsize(widthBits - 1, 0) +& (HjxlConstants.BlockDim - 1).U) >>
            log2Ceil(HjxlConstants.BlockDim))
      }
      precisionReceived.get := received + 1.U
    }
  }

  private def frameSourceIndex(blockIndex: UInt, sample: Int): UInt = {
    val safeBlocksX = Mux(precisionBlocksX.get === 0.U, 1.U, precisionBlocksX.get)
    val blockX = blockIndex - (blockIndex / safeBlocksX) * safeBlocksX
    val blockY = blockIndex / safeBlocksX
    val paddedX =
      (blockX << log2Ceil(HjxlConstants.BlockDim)) + (sample % HjxlConstants.BlockDim).U
    val paddedY =
      (blockY << log2Ceil(HjxlConstants.BlockDim)) + (sample / HjxlConstants.BlockDim).U
    val sourceX = Mux(
      paddedX >= precisionWidth.get,
      precisionWidth.get - 1.U,
      paddedX
    )
    val sourceY = Mux(
      paddedY >= precisionHeight.get,
      precisionHeight.get - 1.U,
      paddedY
    )
    sourceY * precisionWidth.get + sourceX
  }

  private def precisionFrameSample(blockIndex: UInt, sample: Int, channel: Int): SInt =
    precisionFrame.get(frameSourceIndex(blockIndex, sample)(frameIndexBits - 1, 0))(channel)

  private def quantizationFrameSample(blockIndex: UInt, sample: Int, channel: Int): SInt =
    quantizationFrame
      .get(frameSourceIndex(blockIndex, sample)(frameIndexBits - 1, 0))(channel)

  val cumulative = Module(new AqHfColorGammaModulationBlockPipeline)
  cumulative.io.input <> scheduler.io.block

  val selectedDistance = Module(new DistanceParamsLookup)
  selectedDistance.io.distanceQ8 := cumulative.io.output.bits.distanceQ8
  assert(
    !cumulative.io.output.valid || selectedDistance.io.supported,
    "AQ final-map pipeline received an unsupported effective distance"
  )

  val finalModulation = Module(new AqFinalModulationBlock)
  finalModulation.io.input.bits.seedQ24 := cumulative.io.output.bits.valueQ24
  finalModulation.io.input.bits.scaleQ24 := selectedDistance.io.params.aqScaleQ24
  finalModulation.io.input.bits.dampenQ24 := selectedDistance.io.params.aqDampenQ24
  finalModulation.io.input.valid := cumulative.io.output.valid
  cumulative.io.output.ready := finalModulation.io.input.ready

  val contextValid = RegInit(false.B)
  val strategyMaskQ16 = RegInit(0.U(AqStrategyMaskFixedPoint.ValueBits.W))
  val distanceQ8 = RegInit(0.U(16.W))
  val invGlobalScaleQ24 = RegInit(0.U(32.W))
  val scaleQ16 = RegInit(0.U(16.W))
  val fixedInvQacQ16 = RegInit(0.U(32.W))
  val fixedRawQuant = RegInit(0.U(8.W))
  val invDcFactorQ16 = Reg(Vec(3, UInt(32.W)))
  val xQmMultiplierQ16 = RegInit(0.U(32.W))
  val fixedYtox = RegInit(0.S(8.W))
  val fixedYtob = RegInit(0.S(8.W))
  val blockIndex = RegInit(0.U(32.W))
  val blockLast = RegInit(false.B)
  val xybXQ12 = Reg(Vec(AqHfModulationFixedPoint.SamplesPerBlock, SInt(AqHfModulationFixedPoint.XybValueBits.W)))
  val xybYQ12 = Reg(Vec(AqHfModulationFixedPoint.SamplesPerBlock, SInt(AqHfModulationFixedPoint.XybValueBits.W)))
  val xybBQ12 = Reg(Vec(AqHfModulationFixedPoint.SamplesPerBlock, SInt(AqHfModulationFixedPoint.XybValueBits.W)))

  when(finalModulation.io.input.fire) {
    assert(!contextValid, "AQ final-map pipeline accepted overlapping metadata")
    contextValid := true.B
    strategyMaskQ16 := cumulative.io.output.bits.strategyMaskQ16
    distanceQ8 := cumulative.io.output.bits.distanceQ8
    invGlobalScaleQ24 := selectedDistance.io.params.aqInvGlobalScaleQ24
    scaleQ16 := Mux(
      cumulative.io.output.bits.fixedPointScale === 0.U,
      selectedDistance.io.params.scaleQ16,
      cumulative.io.output.bits.fixedPointScale
    )
    fixedInvQacQ16 := cumulative.io.output.bits.fixedInvQacQ16
    fixedRawQuant := cumulative.io.output.bits.fixedRawQuant
    invDcFactorQ16 := selectedDistance.io.params.invDcFactorQ16
    xQmMultiplierQ16 := selectedDistance.io.params.xQmMultiplierQ16
    fixedYtox := cumulative.io.output.bits.fixedYtox
    fixedYtob := cumulative.io.output.bits.fixedYtob
    blockIndex := cumulative.io.output.bits.blockIndex
    blockLast := cumulative.io.output.bits.blockLast
    xybXQ12 := cumulative.io.output.bits.xybXQ12
    xybYQ12 := cumulative.io.output.bits.xybYQ12
    xybBQ12 := cumulative.io.output.bits.xybBQ12
  }

  io.output.valid := finalModulation.io.output.valid && contextValid
  io.output.bits.aqMapQ24 := finalModulation.io.output.bits
  io.output.bits.strategyMaskQ16 := strategyMaskQ16
  io.output.bits.distanceQ8 := distanceQ8
  io.output.bits.invGlobalScaleQ24 := invGlobalScaleQ24
  io.output.bits.scaleQ16 := scaleQ16
  io.output.bits.fixedInvQacQ16 := fixedInvQacQ16
  io.output.bits.fixedRawQuant := fixedRawQuant
  io.output.bits.invDcFactorQ16 := invDcFactorQ16
  io.output.bits.xQmMultiplierQ16 := xQmMultiplierQ16
  io.output.bits.fixedYtox := fixedYtox
  io.output.bits.fixedYtob := fixedYtob
  io.output.bits.blockIndex := blockIndex
  io.output.bits.blockLast := blockLast
  io.output.bits.xybXQ12 := xybXQ12
  io.output.bits.xybYQ12 := xybYQ12
  io.output.bits.xybBQ12 := xybBQ12
  io.output.bits.precisionXyb.foreach { precisionBlock =>
    assert(
      !io.output.valid ||
        precisionReceived.get === precisionWidth.get * precisionHeight.get,
      "AQ final-map precision sideband is incomplete at block output"
    )
    for {
      channel <- 0 until 3
      sample <- 0 until AqHfModulationFixedPoint.SamplesPerBlock
    } {
      precisionBlock(channel)(sample) :=
        precisionFrameSample(blockIndex, sample, channel)
    }
  }
  io.output.bits.quantizationXyb.foreach { quantizationBlock =>
    for {
      channel <- 0 until 3
      sample <- 0 until AqHfModulationFixedPoint.SamplesPerBlock
    } {
      quantizationBlock(channel)(sample) :=
        quantizationFrameSample(blockIndex, sample, channel)
    }
  }
  finalModulation.io.output.ready := io.output.ready && contextValid
  scheduler.io.frameDone := io.output.fire && blockLast
  io.busy := scheduler.io.busy || cumulative.io.busy || finalModulation.io.busy || contextValid
  io.overflow := scheduler.io.overflow

  when(io.output.fire) {
    assert(contextValid, "AQ final-map pipeline emitted without metadata")
    contextValid := false.B
    strategyMaskQ16 := 0.U
    distanceQ8 := 0.U
    invGlobalScaleQ24 := 0.U
    scaleQ16 := 0.U
    fixedInvQacQ16 := 0.U
    fixedRawQuant := 0.U
    xQmMultiplierQ16 := 0.U
    fixedYtox := 0.S
    fixedYtob := 0.S
    blockLast := false.B
    when(io.output.bits.blockLast) {
      precisionReceived.foreach(_ := 0.U)
      precisionWidth.foreach(_ := 0.U)
      precisionHeight.foreach(_ := 0.U)
      precisionBlocksX.foreach(_ := 0.U)
    }
  }
}

/** RGB-connected trace of the completed unsigned-Q24 adaptive-quantization map. */
class FrameAqFinalMapTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val xybAccepted = Output(Valid(new XybPixel(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val pipeline = Module(new FrameAqFinalMapPipeline(c))
  pipeline.io.config := io.config
  pipeline.io.input <> io.input
  io.xybAccepted := pipeline.io.xybAccepted

  io.trace.valid := pipeline.io.output.valid
  io.trace.bits.stage := TraceStage.AqFinalMap.U
  io.trace.bits.group := 0.U
  io.trace.bits.index := pipeline.io.output.bits.blockIndex
  io.trace.bits.value := pipeline.io.output.bits.aqMapQ24.asSInt
  io.traceLast := pipeline.io.output.valid && pipeline.io.output.bits.blockLast
  pipeline.io.output.ready := io.trace.ready
  io.busy := pipeline.io.busy
  io.overflow := pipeline.io.overflow
}

/** RGB-connected adaptive raw-quant field using the completed final AQ map. */
class FrameAqRawQuantTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val pipeline = Module(new FrameAqFinalMapPipeline(c))
  pipeline.io.config := io.config
  pipeline.io.input <> io.input

  val converter = Module(new AqMapToRawQuant)
  converter.io.aqMapQ := pipeline.io.output.bits.aqMapQ24
  converter.io.invGlobalScaleQ := pipeline.io.output.bits.invGlobalScaleQ24

  io.trace.valid := pipeline.io.output.valid
  io.trace.bits.stage := TraceStage.RawQuantField.U
  io.trace.bits.group := 0.U
  io.trace.bits.index := pipeline.io.output.bits.blockIndex
  io.trace.bits.value := Cat(0.U(1.W), converter.io.rawQuant).asSInt.pad(c.traceValueBits)
  io.traceLast := pipeline.io.output.valid && pipeline.io.output.bits.blockLast
  pipeline.io.output.ready := io.trace.ready
  io.busy := pipeline.io.busy
  io.overflow := pipeline.io.overflow
}
