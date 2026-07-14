// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

object AqNonlinearMaskFixedPoint {
  val InputFractionBits = AqFuzzyErosionFixedPoint.FractionBits
  val InputValueBits = AqFuzzyErosionFixedPoint.ValueBits
  val OutputFractionBits = 24
  val OutputValueBits = 32
  val DividerBits = 56

  val BaseQ24: BigInt = -12444499
  val Mul4Q24: BigInt = 54279760
  val Mul2Q24: BigInt = 216527232
  val Offset2Q24: BigInt = 5117727744L
  val Mul3Q24: BigInt = 84255704
  val Offset3Q24: BigInt = 36785288
  val Offset4Q24: BigInt = 9196322
  val Mul0Q24: BigInt = 12542718
  val MinimumQ24: BigInt = 16777

  val MaximumInput: BigInt = (BigInt(1) << InputValueBits) - 1
  val MaximumOutput: BigInt = (BigInt(1) << (OutputValueBits - 1)) - 1
  val MinimumOutput: BigInt = -(BigInt(1) << (OutputValueBits - 1))

  private val inputRoundingBias = BigInt(1) << (InputFractionBits - 1)
  private val squareRoundingBias = BigInt(1) << (OutputFractionBits - 1)

  private def roundedDivide(numerator: BigInt, denominator: BigInt): BigInt = {
    require(numerator >= 0, "AQ nonlinear-mask numerator must be nonnegative")
    require(denominator > 0, "AQ nonlinear-mask denominator must be positive")
    (numerator + denominator / 2) / denominator
  }

  def maskQ24(fuzzyErosionQ16: BigInt): BigInt = {
    require(
      fuzzyErosionQ16 >= 0 && fuzzyErosionQ16 <= MaximumInput,
      "AQ fuzzy-erosion input must fit positive signed-32 Q16"
    )
    val value1Q24 =
      (((fuzzyErosionQ16 * Mul0Q24) + inputRoundingBias) >> InputFractionBits).max(MinimumQ24)
    val value1SquaredQ24 =
      ((value1Q24 * value1Q24) + squareRoundingBias) >> OutputFractionBits
    val result =
      BaseQ24 +
        roundedDivide(Mul4Q24 << OutputFractionBits, value1SquaredQ24 + Offset4Q24) +
        roundedDivide(Mul2Q24 << OutputFractionBits, value1Q24 + Offset2Q24) +
        roundedDivide(Mul3Q24 << OutputFractionBits, value1SquaredQ24 + Offset3Q24)
    require(
      result >= MinimumOutput && result <= MaximumOutput,
      "AQ nonlinear-mask result must fit signed-32 Q24"
    )
    result
  }

  val MaximumValue1Q24: BigInt =
    (((MaximumInput * Mul0Q24) + inputRoundingBias) >> InputFractionBits).max(MinimumQ24)
  val MaximumValue1SquaredQ24: BigInt =
    ((MaximumValue1Q24 * MaximumValue1Q24) + squareRoundingBias) >> OutputFractionBits
}

class AqNonlinearMaskDivideRequest extends Bundle {
  import AqNonlinearMaskFixedPoint.DividerBits

  val dividend = UInt(DividerBits.W)
  val divisor = UInt(DividerBits.W)
}

/** Shared rounded unsigned divider for the three rational terms in
  * libjxl-tiny's `_compute_mask` function.
  */
class AqNonlinearMaskRoundedDivider extends Module {
  import AqNonlinearMaskFixedPoint.DividerBits

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new AqNonlinearMaskDivideRequest))
    val response = Decoupled(UInt(DividerBits.W))
    val busy = Output(Bool())
  })

  private val iterationBits = log2Ceil(DividerBits + 1)
  val running = RegInit(false.B)
  val iterationsRemaining = RegInit(0.U(iterationBits.W))
  val dividend = RegInit(0.U(DividerBits.W))
  val divisor = RegInit(0.U(DividerBits.W))
  val remainder = RegInit(0.U((DividerBits + 1).W))
  val quotient = RegInit(0.U(DividerBits.W))
  val outputValid = RegInit(false.B)
  val outputValue = RegInit(0.U(DividerBits.W))

  io.request.ready := !running && !outputValid
  io.response.valid := outputValid
  io.response.bits := outputValue
  io.busy := running || outputValid

  when(io.response.fire) {
    outputValid := false.B
  }

  when(io.request.fire) {
    assert(io.request.bits.divisor =/= 0.U, "AQ nonlinear-mask divisor must be nonzero")
    running := true.B
    iterationsRemaining := DividerBits.U
    dividend := io.request.bits.dividend
    divisor := io.request.bits.divisor
    remainder := 0.U
    quotient := 0.U
  }

  when(running) {
    val shiftedRemainder = Cat(remainder(DividerBits - 1, 0), dividend(DividerBits - 1))
    val extendedDivisor = divisor.pad(DividerBits + 1)
    val subtract = shiftedRemainder >= extendedDivisor
    val nextRemainder = Mux(subtract, shiftedRemainder - extendedDivisor, shiftedRemainder)
    val nextQuotient = Cat(quotient(DividerBits - 2, 0), subtract)

    dividend := Cat(dividend(DividerBits - 2, 0), 0.U(1.W))
    remainder := nextRemainder
    quotient := nextQuotient
    iterationsRemaining := iterationsRemaining - 1.U

    when(iterationsRemaining === 1.U) {
      val doubledRemainder = nextRemainder << 1
      val roundUp = doubledRemainder >= extendedDivisor.pad(DividerBits + 2)
      val roundedQuotient = nextQuotient +& roundUp
      outputValue := Mux(
        roundedQuotient(DividerBits),
        Fill(DividerBits, 1.U(1.W)),
        roundedQuotient(DividerBits - 1, 0)
      )
      outputValid := true.B
      running := false.B
      iterationsRemaining := 0.U
    }
  }
}

/** Evaluates `_compute_mask` for one positive Q16 fuzzy-erosion value.
  *
  * Constants and intermediates use Q24. One 56-cycle restoring divider is
  * shared across the three rational terms, so one input takes 168 divide
  * iterations without inferring a combinational division operator. The signed
  * Q24 result is the log-domain seed consumed by HF/color/gamma modulation; it
  * is not the pre-modulation AC-strategy mask.
  */
class AqNonlinearMaskEvaluator extends Module {
  import AqNonlinearMaskFixedPoint._

  require(
    MaximumValue1SquaredQ24.bitLength <= DividerBits,
    "AQ nonlinear-mask squared intermediate width is too small"
  )
  require(
    (MaximumValue1SquaredQ24 + Offset3Q24).bitLength <= DividerBits,
    "AQ nonlinear-mask denominator width is too small"
  )
  require(
    Seq(Mul4Q24, Mul2Q24, Mul3Q24).forall(value => (value << OutputFractionBits).bitLength <= DividerBits),
    "AQ nonlinear-mask dividend width is too small"
  )
  require(maskQ24(0) <= MaximumOutput, "AQ nonlinear-mask positive output width is too small")
  require(maskQ24(MaximumInput) >= MinimumOutput, "AQ nonlinear-mask negative output width is too small")

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(UInt(InputValueBits.W)))
    val output = Decoupled(SInt(OutputValueBits.W))
    val busy = Output(Bool())
  })

  val idle :: issueMul4 :: waitMul4 :: issueMul2 :: waitMul2 :: issueMul3 :: waitMul3 :: emit :: Nil =
    Enum(8)
  val state = RegInit(idle)
  val denominator4 = RegInit(0.U(DividerBits.W))
  val denominator2 = RegInit(0.U(DividerBits.W))
  val denominator3 = RegInit(0.U(DividerBits.W))
  val term4 = RegInit(0.U(DividerBits.W))
  val term2 = RegInit(0.U(DividerBits.W))
  val outputValue = RegInit(0.S(OutputValueBits.W))

  val divider = Module(new AqNonlinearMaskRoundedDivider)
  divider.io.request.valid := false.B
  divider.io.request.bits.dividend := 0.U
  divider.io.request.bits.divisor := 1.U
  divider.io.response.ready := false.B

  io.input.ready := state === idle
  io.output.valid := state === emit
  io.output.bits := outputValue
  io.busy := state =/= idle

  val scaledValue1 = io.input.bits * Mul0Q24.U
  val roundedValue1 = (scaledValue1 +& (BigInt(1) << (InputFractionBits - 1)).U) >> InputFractionBits
  val value1 = Mux(roundedValue1 < MinimumQ24.U, MinimumQ24.U, roundedValue1)
  val squaredValue1 = value1 * value1
  val roundedSquaredValue1 =
    (squaredValue1 +& (BigInt(1) << (OutputFractionBits - 1)).U) >> OutputFractionBits
  val value1Squared = roundedSquaredValue1(DividerBits - 1, 0)

  when(io.input.fire) {
    denominator4 := (value1Squared +& Offset4Q24.U)(DividerBits - 1, 0)
    denominator2 := (value1.pad(DividerBits) +& Offset2Q24.U)(DividerBits - 1, 0)
    denominator3 := (value1Squared +& Offset3Q24.U)(DividerBits - 1, 0)
    state := issueMul4
  }

  when(state === issueMul4) {
    divider.io.request.valid := true.B
    divider.io.request.bits.dividend := (Mul4Q24 << OutputFractionBits).U
    divider.io.request.bits.divisor := denominator4
    when(divider.io.request.fire) {
      state := waitMul4
    }
  }
  when(state === waitMul4) {
    divider.io.response.ready := true.B
    when(divider.io.response.fire) {
      term4 := divider.io.response.bits
      state := issueMul2
    }
  }
  when(state === issueMul2) {
    divider.io.request.valid := true.B
    divider.io.request.bits.dividend := (Mul2Q24 << OutputFractionBits).U
    divider.io.request.bits.divisor := denominator2
    when(divider.io.request.fire) {
      state := waitMul2
    }
  }
  when(state === waitMul2) {
    divider.io.response.ready := true.B
    when(divider.io.response.fire) {
      term2 := divider.io.response.bits
      state := issueMul3
    }
  }
  when(state === issueMul3) {
    divider.io.request.valid := true.B
    divider.io.request.bits.dividend := (Mul3Q24 << OutputFractionBits).U
    divider.io.request.bits.divisor := denominator3
    when(divider.io.request.fire) {
      state := waitMul3
    }
  }
  when(state === waitMul3) {
    divider.io.response.ready := true.B
    when(divider.io.response.fire) {
      val sum = Wire(SInt(64.W))
      sum := BaseQ24.S(64.W) + term4.pad(64).asSInt + term2.pad(64).asSInt +
        divider.io.response.bits.pad(64).asSInt
      outputValue := Mux(
        sum > MaximumOutput.S(64.W),
        MaximumOutput.S(OutputValueBits.W),
        Mux(
          sum < MinimumOutput.S(64.W),
          MinimumOutput.S(OutputValueBits.W),
          sum(OutputValueBits - 1, 0).asSInt
        )
      )
      state := emit
    }
  }
  when(io.output.fire) {
    state := idle
  }
}

/** Streams prepared positive Q16 fuzzy-erosion values into signed-Q24
  * nonlinear AQ modulation seeds in padded-block raster order.
  */
class FramePreparedAqNonlinearMaskTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  import AqNonlinearMaskFixedPoint._

  private val blockDim = HjxlConstants.BlockDim
  private val maxXBlocks = c.maxFrameWidth / blockDim
  private val maxYBlocks = c.maxFrameHeight / blockDim
  private val maxBlocks = maxXBlocks * maxYBlocks
  private val widthBits = log2Ceil(c.maxFrameWidth + 1)
  private val heightBits = log2Ceil(c.maxFrameHeight + 1)

  require(c.traceValueBits == OutputValueBits, "AQ nonlinear-mask trace requires signed 32-bit values")
  require(maxBlocks > 0, "configured frame must contain at least one block")
  require(BigInt(maxBlocks) <= BigInt("ffffffff", 16), "AQ nonlinear-mask block count must fit trace index")

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(UInt(InputValueBits.W)))
    val trace = Decoupled(new StageTrace(c))
    val strategyMaskQ16 = Output(UInt(AqStrategyMaskFixedPoint.ValueBits.W))
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

  val evaluator = Module(new AqNonlinearMaskEvaluator)
  val strategyMask = Module(new AqStrategyMaskReciprocal)
  val selectedTotalBlocks = Mux(frameActive, activeTotalBlocks, nextTotalBlocks)
  val inputAllowed = frameActive || !configOutOfRange
  evaluator.io.input.bits := io.input.bits
  evaluator.io.input.valid :=
    io.input.valid && strategyMask.io.input.ready && inputAllowed && !activeTraceLast
  strategyMask.io.input.bits := io.input.bits
  strategyMask.io.input.valid :=
    io.input.valid && evaluator.io.input.ready && inputAllowed && !activeTraceLast
  io.input.ready :=
    evaluator.io.input.ready && strategyMask.io.input.ready && inputAllowed && !activeTraceLast

  io.trace.valid := evaluator.io.output.valid && strategyMask.io.output.valid
  io.trace.bits.stage := TraceStage.AqNonlinearMask.U
  io.trace.bits.group := 0.U
  io.trace.bits.index := activeTraceIndex
  io.trace.bits.value := evaluator.io.output.bits
  io.strategyMaskQ16 := strategyMask.io.output.bits
  evaluator.io.output.ready := io.trace.ready && strategyMask.io.output.valid
  strategyMask.io.output.ready := io.trace.ready && evaluator.io.output.valid
  io.traceLast := io.trace.valid && activeTraceLast
  io.busy := frameActive || evaluator.io.busy || strategyMask.io.busy
  io.overflow := !frameActive && evaluator.io.input.ready && configOutOfRange

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

/** RGB-connected `_compute_mask` path composed after the shared contrast and
  * fuzzy-erosion stages.
  */
class FrameAqNonlinearMaskTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  import AqNonlinearMaskFixedPoint.InputValueBits

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val xybAccepted = Output(Valid(new XybPixel(c)))
    val trace = Decoupled(new StageTrace(c))
    val strategyMaskQ16 = Output(UInt(AqStrategyMaskFixedPoint.ValueBits.W))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val fuzzyErosion = Module(new FrameAqFuzzyErosionTraceStage(c))
  val nonlinearMask = Module(new FramePreparedAqNonlinearMaskTraceStage(c))
  val latchedConfig = Reg(new FrameConfig(c))
  val frameActive = RegInit(false.B)
  val fuzzyErosionDone = RegInit(false.B)
  val activeConfig = Wire(new FrameConfig(c))
  activeConfig := Mux(frameActive, latchedConfig, io.config)

  fuzzyErosion.io.config := activeConfig
  fuzzyErosion.io.input.bits := io.input.bits
  fuzzyErosion.io.input.valid := io.input.valid && !fuzzyErosionDone
  io.input.ready := fuzzyErosion.io.input.ready && !fuzzyErosionDone
  io.xybAccepted := fuzzyErosion.io.xybAccepted

  nonlinearMask.io.config := activeConfig
  nonlinearMask.io.input.valid := fuzzyErosion.io.trace.valid
  nonlinearMask.io.input.bits := fuzzyErosion.io.trace.bits.value.asUInt(InputValueBits - 1, 0)
  fuzzyErosion.io.trace.ready := nonlinearMask.io.input.ready

  io.trace.valid := nonlinearMask.io.trace.valid
  io.trace.bits := nonlinearMask.io.trace.bits
  io.strategyMaskQ16 := nonlinearMask.io.strategyMaskQ16
  nonlinearMask.io.trace.ready := io.trace.ready
  io.traceLast := nonlinearMask.io.traceLast
  io.busy := frameActive || fuzzyErosion.io.busy || nonlinearMask.io.busy
  io.overflow := fuzzyErosion.io.overflow || nonlinearMask.io.overflow

  when(io.input.fire && !frameActive) {
    latchedConfig := io.config
    frameActive := true.B
  }
  when(fuzzyErosion.io.trace.fire && fuzzyErosion.io.traceLast) {
    fuzzyErosionDone := true.B
  }
  when(io.trace.fire && io.traceLast) {
    frameActive := false.B
    fuzzyErosionDone := false.B
  }
}
