// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

object AqStrategyMaskFixedPoint {
  val FractionBits = AqFuzzyErosionFixedPoint.FractionBits
  val ValueBits = AqFuzzyErosionFixedPoint.ValueBits
  val OffsetDenominator = 1000
  val Scale: BigInt = BigInt(1) << FractionBits
  val Numerator: BigInt = Scale * Scale * OffsetDenominator
  val DenominatorBits = 41
  val DividendBits = 42
  val MaximumValue: BigInt = (BigInt(1) << ValueBits) - 1

  def denominator(fuzzyErosionQ16: BigInt): BigInt = {
    require(
      fuzzyErosionQ16 >= 0 && fuzzyErosionQ16 <= MaximumValue,
      "AQ fuzzy-erosion input must fit positive signed-32 Q16"
    )
    fuzzyErosionQ16 * OffsetDenominator + Scale
  }

  def maskQ16(fuzzyErosionQ16: BigInt): BigInt = {
    val divisor = denominator(fuzzyErosionQ16)
    ((Numerator + divisor / 2) / divisor).min(MaximumValue)
  }
}

/** Converts one positive Q16 fuzzy-erosion value into the Q16 reciprocal mask
  * consumed by AC-strategy scoring.
  *
  * The reference expression is `1 / (erosion + 0.001)`. Representing the
  * decimal offset as the exact rational 1/1000 gives the integer expression
  * `round(2^32 * 1000 / (erosionQ16 * 1000 + 2^16))`. A 42-cycle restoring
  * divider evaluates it without inferring a wide combinational divider.
  */
class AqStrategyMaskReciprocal extends Module {
  import AqStrategyMaskFixedPoint._

  require(Numerator.bitLength <= DividendBits, "AQ strategy-mask numerator width is too small")
  require(
    denominator(MaximumValue).bitLength <= DenominatorBits,
    "AQ strategy-mask denominator width is too small"
  )
  require(maskQ16(0) <= MaximumValue, "AQ strategy-mask output width is too small")

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(UInt(ValueBits.W)))
    val output = Decoupled(UInt(ValueBits.W))
    val busy = Output(Bool())
  })

  private val iterationBits = log2Ceil(DividendBits + 1)
  val running = RegInit(false.B)
  val iterationsRemaining = RegInit(0.U(iterationBits.W))
  val dividend = RegInit(0.U(DividendBits.W))
  val divisor = RegInit(0.U(DenominatorBits.W))
  val remainder = RegInit(0.U(DividendBits.W))
  val quotient = RegInit(0.U(DividendBits.W))
  val outputValid = RegInit(false.B)
  val outputValue = RegInit(0.U(ValueBits.W))

  io.input.ready := !running && !outputValid
  io.output.valid := outputValid
  io.output.bits := outputValue
  io.busy := running || outputValid

  when(io.output.fire) {
    outputValid := false.B
  }

  when(io.input.fire) {
    val scaledInput = io.input.bits * OffsetDenominator.U
    val inputDivisor = scaledInput +& Scale.U
    running := true.B
    iterationsRemaining := DividendBits.U
    dividend := Numerator.U(DividendBits.W)
    divisor := inputDivisor(DenominatorBits - 1, 0)
    remainder := 0.U
    quotient := 0.U
  }

  when(running) {
    val shiftedRemainder = Cat(remainder(DenominatorBits - 1, 0), dividend(DividendBits - 1))
    val extendedDivisor = divisor.pad(DividendBits)
    val subtract = shiftedRemainder >= extendedDivisor
    val nextRemainder = Mux(subtract, shiftedRemainder - extendedDivisor, shiftedRemainder)
    val nextQuotient = Cat(quotient(DividendBits - 2, 0), subtract)

    dividend := Cat(dividend(DividendBits - 2, 0), 0.U(1.W))
    remainder := nextRemainder
    quotient := nextQuotient
    iterationsRemaining := iterationsRemaining - 1.U

    when(iterationsRemaining === 1.U) {
      val doubledRemainder = nextRemainder << 1
      val roundUp = doubledRemainder >= extendedDivisor.pad(DividendBits + 1)
      val roundedQuotient = nextQuotient +& roundUp
      outputValue := Mux(
        roundedQuotient > MaximumValue.U,
        MaximumValue.U,
        roundedQuotient(ValueBits - 1, 0)
      )
      outputValid := true.B
      running := false.B
      iterationsRemaining := 0.U
    }
  }
}

/** Streams prepared block-resolution Q16 fuzzy-erosion values into the Q16
  * strategy mask consumed by AC-strategy scoring.
  */
class FramePreparedAqStrategyMaskTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  import AqStrategyMaskFixedPoint._

  private val blockDim = HjxlConstants.BlockDim
  private val maxXBlocks = c.maxFrameWidth / blockDim
  private val maxYBlocks = c.maxFrameHeight / blockDim
  private val maxBlocks = maxXBlocks * maxYBlocks
  private val widthBits = log2Ceil(c.maxFrameWidth + 1)
  private val heightBits = log2Ceil(c.maxFrameHeight + 1)

  require(c.traceValueBits == 32, "AQ strategy-mask trace requires signed 32-bit values")
  require(maxBlocks > 0, "configured frame must contain at least one block")
  require(BigInt(maxBlocks) <= BigInt("ffffffff", 16), "AQ strategy-mask block count must fit trace index")

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(UInt(ValueBits.W)))
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

  val reciprocal = Module(new AqStrategyMaskReciprocal)
  val selectedTotalBlocks = Mux(frameActive, activeTotalBlocks, nextTotalBlocks)
  val inputAllowed = frameActive || !configOutOfRange
  reciprocal.io.input.bits := io.input.bits
  reciprocal.io.input.valid := io.input.valid && inputAllowed && !activeTraceLast
  io.input.ready := reciprocal.io.input.ready && inputAllowed && !activeTraceLast

  io.trace.valid := reciprocal.io.output.valid
  io.trace.bits.stage := TraceStage.AqStrategyMask.U
  io.trace.bits.group := 0.U
  io.trace.bits.index := activeTraceIndex
  io.trace.bits.value := Cat(0.U(1.W), reciprocal.io.output.bits).asSInt
  reciprocal.io.output.ready := io.trace.ready
  io.traceLast := reciprocal.io.output.valid && activeTraceLast
  io.busy := frameActive || reciprocal.io.busy
  io.overflow := !frameActive && reciprocal.io.input.ready && configOutOfRange

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

/** RGB-connected AQ strategy-mask path composed from the contrast, fuzzy
  * erosion, and prepared reciprocal stages.
  */
class FrameAqStrategyMaskTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  import AqStrategyMaskFixedPoint.ValueBits

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val fuzzyErosion = Module(new FrameAqFuzzyErosionTraceStage(c))
  val strategyMask = Module(new FramePreparedAqStrategyMaskTraceStage(c))
  val latchedConfig = Reg(new FrameConfig(c))
  val frameActive = RegInit(false.B)
  val fuzzyErosionDone = RegInit(false.B)
  val activeConfig = Wire(new FrameConfig(c))
  activeConfig := Mux(frameActive, latchedConfig, io.config)

  fuzzyErosion.io.config := activeConfig
  fuzzyErosion.io.input.bits := io.input.bits
  fuzzyErosion.io.input.valid := io.input.valid && !fuzzyErosionDone
  io.input.ready := fuzzyErosion.io.input.ready && !fuzzyErosionDone

  strategyMask.io.config := activeConfig
  strategyMask.io.input.valid := fuzzyErosion.io.trace.valid
  strategyMask.io.input.bits := fuzzyErosion.io.trace.bits.value.asUInt(ValueBits - 1, 0)
  fuzzyErosion.io.trace.ready := strategyMask.io.input.ready

  io.trace.valid := strategyMask.io.trace.valid
  io.trace.bits := strategyMask.io.trace.bits
  strategyMask.io.trace.ready := io.trace.ready
  io.traceLast := strategyMask.io.traceLast
  io.busy := frameActive || fuzzyErosion.io.busy || strategyMask.io.busy
  io.overflow := fuzzyErosion.io.overflow || strategyMask.io.overflow

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
