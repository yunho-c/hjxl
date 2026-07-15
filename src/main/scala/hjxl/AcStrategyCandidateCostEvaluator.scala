// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

class AcStrategyCandidateCostInput(coefficientBits: Int) extends Bundle {
  require(coefficientBits > 0, "AC-strategy coefficient width must be positive")

  val strategy = UInt(2.W)
  val distanceQ8 = UInt(16.W)
  val quantQ24 = UInt(32.W)
  val maskQ16 = UInt(32.W)
  val ytox = SInt(8.W)
  val ytob = SInt(8.W)

  /** Canonical coefficient order. DCT uses entries 0..63; rectangular
    * candidates use all 128 entries.
    */
  val coefficients = Vec(3, Vec(128, SInt(coefficientBits.W)))
}

class AcStrategyCandidateCostOutput extends Bundle {
  val strategy = UInt(2.W)
  val estimateQ16 = UInt(64.W)
  val scaledCostQ16 = UInt(64.W)
  val unsupportedDistance = Bool()
  val arithmeticOverflow = Bool()
}

/** Sequential fixed-point implementation of libjxl-tiny `estimate_entropy`.
  *
  * One transformed coefficient is evaluated per cycle. Inputs use the common
  * `coefficientFractionBits` scale, together with the maximum Q24 AQ value and
  * Q16 strategy mask over the candidate's covered blocks and the tile CFL
  * multipliers. The output also applies the distance-dependent outer multiplier
  * used by `find_best_16x16_transform`; ordinary DCT receives the reference's
  * +3 bias.
  * Unsupported Q8 distances use the shared distance-1 fallback and are
  * reported explicitly.
  */
class AcStrategyCandidateCostEvaluator(
    coefficientBits: Int = 32,
    coefficientFractionBits: Int = Dct8Approx.FractionBits
) extends Module {
  import AcStrategyCostTables._

  require(coefficientBits >= 16, "AC-strategy coefficient inputs must be at least 16 bits")
  require(
    coefficientFractionBits > 0 && coefficientFractionBits < coefficientBits,
    "AC-strategy coefficient fractional precision must fit its signed input"
  )

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new AcStrategyCandidateCostInput(coefficientBits)))
    val output = Decoupled(new AcStrategyCandidateCostOutput)
    val busy = Output(Bool())
  })

  private def roundShiftSigned(value: SInt, shift: Int): SInt = {
    val negative = value < 0.S
    val magnitude = Mux(negative, -value, value).asUInt
    val rounded = (magnitude +& (BigInt(1) << (shift - 1)).U) >> shift
    Mux(negative, -rounded.asSInt, rounded.asSInt)
  }

  private def roundShiftUnsigned(value: UInt, shift: Int): UInt =
    (value +& (BigInt(1) << (shift - 1)).U) >> shift

  private def overflowAbove(value: UInt, width: Int): Bool =
    if (value.getWidth <= width) false.B else value(value.getWidth - 1, width).orR

  private def saturateUnsigned(value: UInt, width: Int): UInt =
    if (value.getWidth <= width) {
      value.pad(width)
    } else {
      Mux(
        overflowAbove(value, width),
        ((BigInt(1) << width) - 1).U(width.W),
        value(width - 1, 0)
      )
    }

  private val dctX = VecInit(
    (QuantizeDct8x8Block.DctXInvQ16 ++ Seq.fill[Long](64)(0L)).map(_.U(32.W))
  )
  private val dctY = VecInit(
    (QuantizeDct8x8Block.DctYInvQ16 ++ Seq.fill[Long](64)(0L)).map(_.U(32.W))
  )
  private val dctB = VecInit(
    (QuantizeDct8x8Block.DctBInvQ16 ++ Seq.fill[Long](64)(0L)).map(_.U(32.W))
  )
  private val rectangularX = VecInit(Dct2XInvQ16.map(_.U(32.W)))
  private val rectangularY = VecInit(Dct2YInvQ16.map(_.U(32.W)))
  private val rectangularB = VecInit(Dct2BInvQ16.map(_.U(32.W)))
  private val cflFactorDelta = VecInit(CflFactorDeltaQ16.map(_.S(20.W)))
  private val nbitsCost = VecInit(NbitsCostQ16.map(_.U(24.W)))

  val active = RegInit(false.B)
  val latched = Reg(new AcStrategyCandidateCostInput(coefficientBits))
  val coefficientIndex = RegInit(0.U(7.W))
  val channel = RegInit(0.U(2.W))
  val coefficientCount = RegInit(0.U(8.W))
  val channelNonzeros = RegInit(0.U(8.W))
  val entropyQ16 = RegInit(0.U(64.W))
  val infoLossQ16 = RegInit(0.U(32.W))
  val infoLoss2Q32 = RegInit(0.U(48.W))
  val arithmeticOverflow = RegInit(false.B)

  val outputValid = RegInit(false.B)
  val outputBits = Reg(new AcStrategyCandidateCostOutput)

  io.input.ready := !active && !outputValid
  io.output.valid := outputValid
  io.output.bits := outputBits
  io.busy := active || outputValid

  when(io.output.fire) {
    outputValid := false.B
  }

  when(io.input.fire) {
    assert(
      io.input.bits.strategy <= AcStrategyCode.Dct8x16.U,
      "AC-strategy cost evaluator received an unsupported strategy"
    )
    latched := io.input.bits
    coefficientIndex := 0.U
    channel := 0.U
    coefficientCount := Mux(io.input.bits.strategy === AcStrategyCode.Dct.U, 64.U, 128.U)
    channelNonzeros := 0.U
    entropyQ16 := 0.U
    infoLossQ16 := 0.U
    infoLoss2Q32 := 0.U
    arithmeticOverflow := false.B
    active := true.B
  }

  val distanceParams = Module(new AcStrategyCostParamsLookup)
  distanceParams.io.distanceQ8 := latched.distanceQ8

  val isDct = latched.strategy === AcStrategyCode.Dct.U
  val inverseWeight = Mux(
    isDct,
    MuxLookup(channel, dctY(coefficientIndex))(
      Seq(0.U -> dctX(coefficientIndex), 1.U -> dctY(coefficientIndex), 2.U -> dctB(coefficientIndex))
    ),
    MuxLookup(channel, rectangularY(coefficientIndex))(
      Seq(
        0.U -> rectangularX(coefficientIndex),
        1.U -> rectangularY(coefficientIndex),
        2.U -> rectangularB(coefficientIndex)
      )
    )
  )

  val xFactorQ16 = cflFactorDelta(latched.ytox.asUInt)
  val bFactorQ16 = Scale.S(20.W) + cflFactorDelta(latched.ytob.asUInt)
  val cflFactorQ16 = MuxLookup(channel, 0.S(20.W))(
    Seq(0.U -> xFactorQ16, 1.U -> 0.S(20.W), 2.U -> bFactorQ16)
  )
  val coefficient = latched.coefficients(channel)(coefficientIndex)
  val yCoefficient = latched.coefficients(1)(coefficientIndex)
  val coefficientWithCflFraction = coefficient.pad(64) << FractionBits
  val predictionWithCflFraction = yCoefficient.pad(64) * cflFactorQ16.pad(64)
  val residualWithCflFraction = coefficientWithCflFraction -& predictionWithCflFraction
  val valueProduct =
    residualWithCflFraction * inverseWeight.zext * latched.quantQ24.zext
  val valueQ16 = roundShiftSigned(valueProduct, coefficientFractionBits + 40)
  val valueMagnitude = Mux(valueQ16 < 0.S, -valueQ16, valueQ16).asUInt
  val integerMagnitude = valueMagnitude >> FractionBits
  val fraction = valueMagnitude(FractionBits - 1, 0)
  val roundUp = fraction > (Scale / 2).U ||
    (fraction === (Scale / 2).U && integerMagnitude(0))
  val roundedMagnitude = integerMagnitude +& roundUp
  val quantizedOverflow = overflowAbove(roundedMagnitude, 32)
  val quantizedMagnitude = saturateUnsigned(roundedMagnitude, 32)
  val nonzero = quantizedMagnitude =/= 0.U

  val distanceToNextInteger = Scale.U(17.W) - fraction.pad(17)
  val coefficientLossQ16 = Mux(
    fraction > (Scale / 2).U,
    distanceToNextInteger,
    fraction.pad(17)
  )
  val coefficientLoss2Q32 = coefficientLossQ16 * coefficientLossQ16

  val coefficientRoot = Module(new UnsignedIntegerSquareRoot(64))
  coefficientRoot.io.input := Cat(quantizedMagnitude, 0.U(32.W))
  val sqrtEntropyQ16 = roundShiftUnsigned(
    coefficientRoot.io.output * CostDeltaQ16.U,
    FractionBits
  )
  val coefficientEntropyQ16 =
    sqrtEntropyQ16 +& Mux(quantizedMagnitude >= 2.U, Cost2Q16.U, 0.U)

  val entropyAfterCoefficient = entropyQ16 +& coefficientEntropyQ16.pad(64)
  val nonzerosAfterCoefficient = channelNonzeros +& nonzero
  val nonzeroCostQ16 = nonzerosAfterCoefficient * distanceParams.io.params.cost1Q16
  val channelOverheadQ16 = nonzeroCostQ16 +& nbitsCost(nonzerosAfterCoefficient(7, 0))
  val entropyAfterChannel = entropyAfterCoefficient +& channelOverheadQ16
  val infoLossAfterCoefficient = infoLossQ16 +& coefficientLossQ16.pad(32)
  val infoLoss2AfterCoefficient = infoLoss2Q32 +& coefficientLoss2Q32.pad(48)

  val finalCoefficientInChannel = coefficientIndex === coefficientCount - 1.U
  val finalCandidateCoefficient = finalCoefficientInChannel && channel === 2.U

  val scaledInfoLoss2 = Wire(UInt(50.W))
  scaledInfoLoss2 := Mux(
    isDct,
    infoLoss2AfterCoefficient.pad(50),
    (infoLoss2AfterCoefficient << 1).pad(50)
  )
  val infoLossRoot = Module(new UnsignedIntegerSquareRoot(50))
  infoLossRoot.io.input := scaledInfoLoss2
  val infoLossTerm1Q16 = infoLossAfterCoefficient * InfoLossMultiplier.U
  val infoLossTerm2Q16 = roundShiftUnsigned(
    infoLossRoot.io.output * InfoLoss2MultiplierQ16.U,
    FractionBits
  )
  val infoLossScoreQ16 = infoLossTerm1Q16 +& infoLossTerm2Q16
  val maskedInfoLossQ16 = roundShiftUnsigned(
    latched.maskQ16 * infoLossScoreQ16,
    FractionBits
  )
  val estimateQ16 = entropyAfterChannel +& maskedInfoLossQ16
  val biasedEstimateQ16 = estimateQ16 +& Mux(isDct, (3 * Scale).U, 0.U)
  val outerMultiplierQ16 = Mux(
    isDct,
    distanceParams.io.params.dct8MultiplierQ16,
    distanceParams.io.params.rectangularMultiplierQ16
  )
  val scaledCostQ16 = roundShiftUnsigned(
    biasedEstimateQ16 * outerMultiplierQ16,
    FractionBits
  )

  when(active) {
    val stepOverflow = arithmeticOverflow || quantizedOverflow ||
      overflowAbove(entropyAfterCoefficient, 64) ||
      overflowAbove(infoLossAfterCoefficient, 32) ||
      overflowAbove(infoLoss2AfterCoefficient, 48)

    when(finalCandidateCoefficient) {
      val finalOverflow = stepOverflow ||
        overflowAbove(entropyAfterChannel, 64) ||
        overflowAbove(estimateQ16, 64) ||
        overflowAbove(scaledCostQ16, 64)
      outputBits.strategy := latched.strategy
      outputBits.estimateQ16 := saturateUnsigned(estimateQ16, 64)
      outputBits.scaledCostQ16 := saturateUnsigned(scaledCostQ16, 64)
      outputBits.unsupportedDistance := !distanceParams.io.supported
      outputBits.arithmeticOverflow := finalOverflow
      outputValid := true.B
      active := false.B
      coefficientIndex := 0.U
      channel := 0.U
      coefficientCount := 0.U
      channelNonzeros := 0.U
      entropyQ16 := 0.U
      infoLossQ16 := 0.U
      infoLoss2Q32 := 0.U
      arithmeticOverflow := false.B
    }.elsewhen(finalCoefficientInChannel) {
      entropyQ16 := saturateUnsigned(entropyAfterChannel, 64)
      infoLossQ16 := saturateUnsigned(infoLossAfterCoefficient, 32)
      infoLoss2Q32 := saturateUnsigned(infoLoss2AfterCoefficient, 48)
      arithmeticOverflow := stepOverflow || overflowAbove(entropyAfterChannel, 64)
      coefficientIndex := 0.U
      channel := channel + 1.U
      channelNonzeros := 0.U
    }.otherwise {
      entropyQ16 := saturateUnsigned(entropyAfterCoefficient, 64)
      infoLossQ16 := saturateUnsigned(infoLossAfterCoefficient, 32)
      infoLoss2Q32 := saturateUnsigned(infoLoss2AfterCoefficient, 48)
      arithmeticOverflow := stepOverflow
      coefficientIndex := coefficientIndex + 1.U
      channelNonzeros := nonzerosAfterCoefficient(7, 0)
    }
  }
}
