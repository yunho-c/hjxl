// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

object QuantizeVarDctBlock {
  val MaxCoefficients = 2 * HjxlConstants.BlockDim * HjxlConstants.BlockDim
  val DctCoefficients = HjxlConstants.BlockDim * HjxlConstants.BlockDim
  val RectangularCoefficients = MaxCoefficients
  val MaxCoveredBlocks = 2

  val Dct16To2ScaleFractionBits = 32
  val Dct16To2ScaleQ32: Long = 3873047726L

  val RectangularThresholdXQ12: Seq[Int] = Seq(2351, 2904, 3006, 3170)
  val RectangularThresholdYQ12: Seq[Int] = Seq(2351, 2576, 2679, 2843)
  val RectangularThresholdBQ12: Seq[Int] = Seq(2351, 3047, 3047, 3047)

  val Dct2YWeightsQ32: Seq[Long] = Seq(
    2965821, 3326219, 3730411, 4183720, 4692121, 5262294, 5901752, 6618916,
    7423240, 8325290, 9336955, 10214779, 10755350, 11324531, 11923830, 12554868,
    3792030, 3889746, 4150831, 4526532, 4990636, 5534541, 6158396, 6866850,
    7667252, 8568995, 9583231, 10324282, 10861150, 11427441, 12024529, 12653851,
    4848411, 4912846, 5101504, 5403800, 5809330, 6311560, 6908538, 7602234,
    8397828, 9303092, 10151593, 10647955, 11175059, 11733654, 12324764, 12949554,
    6199068, 6254275, 6418946, 6690758, 7067212, 7546943, 8130495, 8820697,
    9622720, 10246503, 10693456, 11174136, 11688513, 12236957, 12820103, 13438894,
    7925998, 7979018, 8138068, 8403242, 8775091, 9255052, 9845858, 10249914,
    10603758, 10995696, 11424702, 11890200, 12392033, 12930331, 13505594, 14118565,
    10065381, 10089579, 10161900, 10281558, 10447370, 10657920, 10911716, 11207339,
    11543546, 11919350, 12334064, 12787248, 13278813, 13808927, 14378027, 14986818,
    11241410, 11263943, 11331404, 11443409, 11599360, 11798534, 12040159, 12323434,
    12647700, 13012415, 13417223, 13861966, 14346696, 14871714, 15437457, 16044648,
    12554868, 12576443, 12641106, 12748673, 12898862, 13091327, 13325705, 13601641,
    13918852, 14277141, 14676463, 15116846, 15598558, 16122001, 16687771, 17296630
  )

  require(Dct2YWeightsQ32.length == RectangularCoefficients)
}

class VarDctAcChannelInput(c: HjxlConfig) extends Bundle {
  val strategy = UInt(2.W)
  val coefficients = Vec(QuantizeVarDctBlock.MaxCoefficients, SInt(c.traceValueBits.W))
  val channel = UInt(2.W)
  val quant = UInt(8.W)
  val scaleQ16 = UInt(16.W)
  val qmMultiplierQ16 = UInt(32.W)
}

class VarDctAcChannelOutput(c: HjxlConfig) extends Bundle {
  val quantized = Vec(QuantizeVarDctBlock.MaxCoefficients, SInt(c.traceValueBits.W))
  val numNonzeros = UInt(8.W)
}

class VarDctYRoundtripInput(c: HjxlConfig) extends Bundle {
  val strategy = UInt(2.W)
  val coefficients = Vec(QuantizeVarDctBlock.MaxCoefficients, SInt(c.traceValueBits.W))
  val quant = UInt(8.W)
  val scaleQ16 = UInt(16.W)
  val invQacQ16 = UInt(32.W)
}

class VarDctYRoundtripOutput(c: HjxlConfig) extends Bundle {
  val quantized = Vec(QuantizeVarDctBlock.MaxCoefficients, SInt(c.traceValueBits.W))
  val roundtripped = Vec(QuantizeVarDctBlock.MaxCoefficients, SInt(c.traceValueBits.W))
  val numNonzeros = UInt(8.W)
}

class VarDctChromaResidualInput(c: HjxlConfig) extends Bundle {
  val strategy = UInt(2.W)
  val coefficients = Vec(QuantizeVarDctBlock.MaxCoefficients, SInt(c.traceValueBits.W))
  val reconstructedY = Vec(QuantizeVarDctBlock.MaxCoefficients, SInt(c.traceValueBits.W))
  val channel = UInt(2.W)
  val cflMultiplier = SInt(8.W)
  val quant = UInt(8.W)
  val scaleQ16 = UInt(16.W)
  val qmMultiplierQ16 = UInt(32.W)
}

class VarDctChromaResidualOutput(c: HjxlConfig) extends Bundle {
  val residual = Vec(QuantizeVarDctBlock.MaxCoefficients, SInt(c.traceValueBits.W))
  val quantized = Vec(QuantizeVarDctBlock.MaxCoefficients, SInt(c.traceValueBits.W))
  val numNonzeros = UInt(8.W)
}

class VarDctQuantizeBlockInput(c: HjxlConfig) extends Bundle {
  val strategy = UInt(2.W)
  val coefficients = Vec(3, Vec(QuantizeVarDctBlock.MaxCoefficients, SInt(c.traceValueBits.W)))
  val quant = UInt(8.W)
  val scaleQ16 = UInt(16.W)
  val invQacQ16 = UInt(32.W)
  val invDcFactorQ16 = Vec(3, UInt(32.W))
  val xQmMultiplierQ16 = UInt(32.W)
  val ytox = SInt(8.W)
  val ytob = SInt(8.W)
}

/** First-block-owned quantization result for one DCT/16x8/8x16 transform.
  *
  * `quantizedAc` and `numNonzeros` belong to the first block. Rectangular
  * strategies use all 128 AC slots and report the raw count used by the token
  * prefix. `quantizedDc` and `shiftedNumNonzeros` instead describe the one or
  * two covered 8x8 cells in raster order; a frame scheduler maps the second
  * cell to below for 16x8 and right for 8x16.
  */
class VarDctQuantizeBlockOutput(c: HjxlConfig) extends Bundle {
  val strategy = UInt(2.W)
  val coefficientCount = UInt(8.W)
  val coveredBlocks = UInt(2.W)
  val quantizedAc = Vec(3, Vec(QuantizeVarDctBlock.MaxCoefficients, SInt(c.traceValueBits.W)))
  val quantizedDc = Vec(3, Vec(QuantizeVarDctBlock.MaxCoveredBlocks, SInt(c.traceValueBits.W)))
  val numNonzeros = Vec(3, UInt(8.W))
  val shiftedNumNonzeros = Vec(3, UInt(8.W))
}

class VarDctQuantizeTraceInput(c: HjxlConfig) extends Bundle {
  val group = UInt(c.groupBits.W)
  val quantize = new VarDctQuantizeBlockInput(c)
}

/** AC quantizer shared by ordinary and two-block rectangular transforms. */
class QuantizeVarDctAcChannel(
    c: HjxlConfig = HjxlConfig(),
    coefficientFractionBits: Int = Dct8Approx.FractionBits
) extends Module {
  require(coefficientFractionBits > 0, "coefficientFractionBits must be positive")
  private val maxCoefficients = QuantizeVarDctBlock.MaxCoefficients
  private val productBits = 128
  private val halfCoefficient = BigInt(1) << (coefficientFractionBits - 1)
  private val totalProductFractionBits =
    QuantizeDct8x8Block.ScaleFractionBits + QuantizeDct8x8Block.QmFractionBits

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new VarDctAcChannelInput(c)))
    val output = Decoupled(new VarDctAcChannelOutput(c))
  })

  private def paddedDct(values: Seq[Long]): Vec[SInt] =
    VecInit((values ++ Seq.fill[Long](64)(0L)).map(_.S(64.W)))

  private def rectangular(values: Seq[Int]): Vec[SInt] =
    VecInit(values.map(_.S(64.W)))

  private def thresholdIndex(coefficient: Int, width: Int, height: Int): Int = {
    val x = coefficient % width
    val y = coefficient / width
    (if (y >= height / 2) 2 else 0) + (if (x >= width / 2) 1 else 0)
  }

  private def rescaleThreshold(valueQ12: Int): BigInt =
    if (coefficientFractionBits >= Dct8Approx.FractionBits) {
      BigInt(valueQ12) << (coefficientFractionBits - Dct8Approx.FractionBits)
    } else {
      BigInt(valueQ12) >> (Dct8Approx.FractionBits - coefficientFractionBits)
    }

  private def roundCoefficient(value: SInt): SInt = {
    val magnitude = Mux(value < 0.S, -value, value)
    val roundedMagnitude = (magnitude + halfCoefficient.S) >> coefficientFractionBits
    Mux(value < 0.S, -roundedMagnitude, roundedMagnitude).asSInt
  }

  private val dctX = paddedDct(QuantizeDct8x8Block.DctXInvQ16)
  private val dctY = paddedDct(QuantizeDct8x8Block.DctYInvQ16)
  private val dctB = paddedDct(QuantizeDct8x8Block.DctBInvQ16)
  private val rectangularX = rectangular(AcStrategyCostTables.Dct2XInvQ16)
  private val rectangularY = rectangular(AcStrategyCostTables.Dct2YInvQ16)
  private val rectangularB = rectangular(AcStrategyCostTables.Dct2BInvQ16)

  private val isDct = io.input.bits.strategy === AcStrategyCode.Dct.U
  private val quant = Cat(0.U(1.W), io.input.bits.quant).asSInt
  private val scale = Cat(0.U(1.W), io.input.bits.scaleQ16).asSInt
  private val qmMultiplier = Cat(0.U(1.W), io.input.bits.qmMultiplierQ16).asSInt

  io.input.ready := io.output.ready
  io.output.valid := io.input.valid

  private val quantized = Seq.tabulate(maxCoefficients) { i =>
    val dctInv = MuxLookup(io.input.bits.channel, dctY(i))(
      Seq(0.U -> dctX(i), 1.U -> dctY(i), 2.U -> dctB(i))
    )
    val rectangularInv = MuxLookup(io.input.bits.channel, rectangularY(i))(
      Seq(0.U -> rectangularX(i), 1.U -> rectangularY(i), 2.U -> rectangularB(i))
    )
    val inverseWeight = Mux(isDct, dctInv, rectangularInv)
    val weighted =
      (io.input.bits.coefficients(i).pad(productBits) * inverseWeight.pad(productBits)) >>
        QuantizeDct8x8Block.InvMatrixFractionBits
    val product =
      weighted.asSInt.pad(productBits) * scale.pad(productBits) *
        quant.pad(productBits) * qmMultiplier.pad(productBits)
    val value = (product >> totalProductFractionBits).asSInt
    val magnitude = Mux(value < 0.S, -value, value)
    val dctThresholdIndex = thresholdIndex(i, 8, 8)
    val rectangularThresholdIndex = thresholdIndex(i, 16, 8)
    val dctThreshold = MuxLookup(
      io.input.bits.channel,
      rescaleThreshold(QuantizeDct8x8Block.ThresholdYQ12(dctThresholdIndex)).S
    )(
      Seq(
        0.U -> rescaleThreshold(QuantizeDct8x8Block.ThresholdXQ12(dctThresholdIndex)).S,
        1.U -> rescaleThreshold(QuantizeDct8x8Block.ThresholdYQ12(dctThresholdIndex)).S,
        2.U -> rescaleThreshold(QuantizeDct8x8Block.ThresholdBQ12(dctThresholdIndex)).S
      )
    )
    val rectangularThreshold = MuxLookup(
      io.input.bits.channel,
      rescaleThreshold(QuantizeVarDctBlock.RectangularThresholdYQ12(rectangularThresholdIndex)).S
    )(
      Seq(
        0.U -> rescaleThreshold(QuantizeVarDctBlock.RectangularThresholdXQ12(rectangularThresholdIndex)).S,
        1.U -> rescaleThreshold(QuantizeVarDctBlock.RectangularThresholdYQ12(rectangularThresholdIndex)).S,
        2.U -> rescaleThreshold(QuantizeVarDctBlock.RectangularThresholdBQ12(rectangularThresholdIndex)).S
      )
    )
    val active = !isDct || i.U < QuantizeVarDctBlock.DctCoefficients.U
    val threshold = Mux(isDct, dctThreshold, rectangularThreshold)
    Mux(active && magnitude >= threshold, roundCoefficient(value), 0.S)
  }

  for (i <- 0 until maxCoefficients) {
    io.output.bits.quantized(i) := quantized(i)(c.traceValueBits - 1, 0).asSInt
  }
  io.output.bits.numNonzeros := PopCount(quantized.zipWithIndex.map { case (value, i) =>
    val outsideLlf = Mux(isDct, i.U =/= 0.U, i.U >= 2.U)
    outsideLlf && value =/= 0.S
  })

  when(io.input.fire) {
    assert(
      io.input.bits.strategy <= AcStrategyCode.Dct8x16.U,
      "VarDCT AC quantizer received an unsupported strategy"
    )
  }
}

/** Quantizes and reconstructs Y for the selected 64- or 128-coefficient shape. */
class QuantizeRoundtripYVarDctBlock(
    c: HjxlConfig = HjxlConfig(),
    coefficientFractionBits: Int = Dct8Approx.FractionBits
) extends Module {
  require(coefficientFractionBits > 0, "coefficientFractionBits must be positive")
  private val maxCoefficients = QuantizeVarDctBlock.MaxCoefficients
  private val productBits = 128
  private val roundtripShift =
    QuantizeDct8x8Block.BiasFractionBits + QuantizeDct8x8Block.WeightFractionBits +
      QuantizeDct8x8Block.InvQacFractionBits - coefficientFractionBits

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new VarDctYRoundtripInput(c)))
    val output = Decoupled(new VarDctYRoundtripOutput(c))
  })

  val quantizer = Module(new QuantizeVarDctAcChannel(c, coefficientFractionBits))
  val idle :: reconstructing :: responding :: Nil = Enum(3)
  val state = RegInit(idle)
  val strategy = Reg(UInt(2.W))
  val invQac = Reg(UInt(32.W))
  val coefficientCount = Reg(UInt(8.W))
  val coefficientIndex = RegInit(0.U(7.W))
  val quantized = Reg(Vec(maxCoefficients, SInt(c.traceValueBits.W)))
  val roundtripped = Reg(Vec(maxCoefficients, SInt(c.traceValueBits.W)))
  val numNonzeros = Reg(UInt(8.W))

  quantizer.io.input.valid := io.input.valid && state === idle
  quantizer.io.input.bits.strategy := io.input.bits.strategy
  quantizer.io.input.bits.coefficients := io.input.bits.coefficients
  quantizer.io.input.bits.channel := 1.U
  quantizer.io.input.bits.quant := io.input.bits.quant
  quantizer.io.input.bits.scaleQ16 := io.input.bits.scaleQ16
  quantizer.io.input.bits.qmMultiplierQ16 := QuantizeDct8x8Block.DefaultQmMultiplierQ16.U
  quantizer.io.output.ready := state === idle

  private val dctWeights = VecInit(
    (QuantizeDct8x8Block.DctYWeightsQ32 ++ Seq.fill[Long](64)(0L)).map(_.S(64.W))
  )
  private val rectangularWeights = VecInit(QuantizeVarDctBlock.Dct2YWeightsQ32.map(_.S(64.W)))
  private val invQacSigned = Cat(0.U(1.W), invQac).asSInt
  private val isDct = strategy === AcStrategyCode.Dct.U

  private def adjustedQuantQ16(value: SInt): SInt = {
    val absValue = Mux(value < 0.S, -value, value).asUInt
    val absQ16 = Cat(absValue, 0.U(QuantizeDct8x8Block.BiasFractionBits.W)).asSInt
    val numerator = QuantizeDct8x8Block.QuantBiasOtherNumeratorQ16.U
    val correction = Mux(absValue === 0.U, 0.U, numerator / absValue)
    val magnitude = Mux(
      absValue === 0.U,
      0.S,
      Mux(absValue === 1.U, QuantizeDct8x8Block.QuantBiasYQ16.S, absQ16 - correction.asSInt)
    )
    Mux(value < 0.S, -magnitude, magnitude).asSInt
  }

  val currentQuantized = quantized(coefficientIndex)
  val currentWeight = Mux(isDct, dctWeights(coefficientIndex), rectangularWeights(coefficientIndex))
  val currentAdjustedQ16 = adjustedQuantQ16(currentQuantized)
  val currentProduct =
    currentAdjustedQ16.pad(productBits) * currentWeight.pad(productBits) * invQacSigned.pad(productBits)
  val currentRoundtripped = Dct8Approx.fit(
    (currentProduct >> roundtripShift).asSInt,
    c.traceValueBits
  )

  io.input.ready := state === idle && quantizer.io.input.ready
  io.output.valid := state === responding
  io.output.bits.quantized := quantized
  io.output.bits.roundtripped := roundtripped
  io.output.bits.numNonzeros := numNonzeros

  when(io.input.fire) {
    strategy := io.input.bits.strategy
    invQac := io.input.bits.invQacQ16
    coefficientCount := Mux(
      io.input.bits.strategy === AcStrategyCode.Dct.U,
      QuantizeVarDctBlock.DctCoefficients.U,
      QuantizeVarDctBlock.RectangularCoefficients.U
    )
    coefficientIndex := 0.U
    quantized := quantizer.io.output.bits.quantized
    roundtripped := VecInit(Seq.fill(maxCoefficients)(0.S(c.traceValueBits.W)))
    numNonzeros := quantizer.io.output.bits.numNonzeros
    state := reconstructing
  }

  when(state === reconstructing) {
    roundtripped(coefficientIndex) := currentRoundtripped
    when(coefficientIndex === coefficientCount - 1.U) {
      coefficientIndex := 0.U
      state := responding
    }.otherwise {
      coefficientIndex := coefficientIndex + 1.U
    }
  }

  when(io.output.fire) {
    strategy := 0.U
    invQac := 0.U
    coefficientCount := 0.U
    coefficientIndex := 0.U
    state := idle
  }
}

/** Subtracts reconstructed-Y CFL prediction and quantizes X or B. */
class QuantizeChromaResidualVarDctBlock(
    c: HjxlConfig = HjxlConfig(),
    coefficientFractionBits: Int = Dct8Approx.FractionBits
) extends Module {
  require(coefficientFractionBits > 0, "coefficientFractionBits must be positive")
  private val maxCoefficients = QuantizeVarDctBlock.MaxCoefficients
  private val productBits = 96

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new VarDctChromaResidualInput(c)))
    val output = Decoupled(new VarDctChromaResidualOutput(c))
  })

  private val baseFactorQ16 =
    Mux(io.input.bits.channel === 2.U, (1 << QuantizeDct8x8Block.CflFactorFractionBits).S, 0.S)
  private val cflFactorQ16 =
    baseFactorQ16 +
      ((io.input.bits.cflMultiplier.pad(32) *
        (1 << QuantizeDct8x8Block.CflFactorFractionBits).S) /
        QuantizeDct8x8Block.ColorFactorDenominator.S)

  private val residual = Seq.tabulate(maxCoefficients) { i =>
    val predicted =
      (cflFactorQ16.pad(productBits) * io.input.bits.reconstructedY(i).pad(productBits)) >>
        QuantizeDct8x8Block.CflFactorFractionBits
    Dct8Approx.fit(io.input.bits.coefficients(i) - predicted.asSInt, c.traceValueBits)
  }

  val quantizer = Module(new QuantizeVarDctAcChannel(c, coefficientFractionBits))
  quantizer.io.input.valid := io.input.valid
  quantizer.io.input.bits.strategy := io.input.bits.strategy
  quantizer.io.input.bits.coefficients := VecInit(residual)
  quantizer.io.input.bits.channel := io.input.bits.channel
  quantizer.io.input.bits.quant := io.input.bits.quant
  quantizer.io.input.bits.scaleQ16 := io.input.bits.scaleQ16
  quantizer.io.input.bits.qmMultiplierQ16 := io.input.bits.qmMultiplierQ16
  quantizer.io.output.ready := io.output.ready

  io.input.ready := quantizer.io.input.ready
  io.output.valid := quantizer.io.output.valid
  for (i <- 0 until maxCoefficients) {
    io.output.bits.residual(i) := residual(i)
    io.output.bits.quantized(i) := quantizer.io.output.bits.quantized(i)
  }
  io.output.bits.numNonzeros := quantizer.io.output.bits.numNonzeros
}

/** Quantizes one first-block-owned DCT/16x8/8x16 X/Y/B coefficient triplet. */
class VarDctQuantizeBlock(
    c: HjxlConfig = HjxlConfig(),
    coefficientFractionBits: Int = Dct8Approx.FractionBits
) extends Module {
  require(coefficientFractionBits > 0, "coefficientFractionBits must be positive")
  private val maxCoefficients = QuantizeVarDctBlock.MaxCoefficients
  private val productBits = 128
  private val dcProductFractionBits = coefficientFractionBits + QuantizeDct8x8Block.DcFactorFractionBits

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new VarDctQuantizeBlockInput(c)))
    val output = Decoupled(new VarDctQuantizeBlockOutput(c))
  })

  val idle :: waitForY :: Nil = Enum(2)
  val state = RegInit(idle)
  val input = Reg(new VarDctQuantizeBlockInput(c))

  private def roundShiftSigned(value: SInt, shift: Int): SInt = {
    val negative = value < 0.S
    val magnitude = Mux(negative, -value, value)
    val rounded = (magnitude + (BigInt(1) << (shift - 1)).S) >> shift
    Mux(negative, -rounded, rounded).asSInt
  }

  private def lowFrequencyPair(coefficients: Vec[SInt], strategy: UInt): Vec[SInt] = {
    val result = Wire(Vec(2, SInt(c.traceValueBits.W)))
    val scaledLow1 = roundShiftSigned(
      coefficients(1).pad(96) * QuantizeVarDctBlock.Dct16To2ScaleQ32.S(64.W),
      QuantizeVarDctBlock.Dct16To2ScaleFractionBits
    )
    val isDct = strategy === AcStrategyCode.Dct.U
    result(0) := Dct8Approx.fit(
      Mux(isDct, coefficients(0), coefficients(0) + scaledLow1),
      c.traceValueBits
    )
    result(1) := Dct8Approx.fit(
      Mux(isDct, 0.S, coefficients(0) - scaledLow1),
      c.traceValueBits
    )
    result
  }

  private def quantizeDc(
      coefficient: SInt,
      channel: Int,
      invDcFactorQ16: UInt,
      quantizedYDc: SInt
  ): SInt = {
    val invDcFactor = Cat(0.U(1.W), invDcFactorQ16).asSInt
    val baseProduct = coefficient.pad(productBits) * invDcFactor.pad(productBits)
    val bCorrection =
      (quantizedYDc.pad(productBits) * QuantizeDct8x8Block.BQuantDcFromYFactorQ16.S) <<
        coefficientFractionBits
    val corrected = baseProduct - Mux(channel.U === 2.U, bCorrection, 0.S(productBits.W))
    Dct8Approx.fit(roundShiftSigned(corrected.asSInt, dcProductFractionBits), c.traceValueBits)
  }

  val yRoundtrip = Module(new QuantizeRoundtripYVarDctBlock(c, coefficientFractionBits))
  val xResidual = Module(new QuantizeChromaResidualVarDctBlock(c, coefficientFractionBits))
  val bResidual = Module(new QuantizeChromaResidualVarDctBlock(c, coefficientFractionBits))

  yRoundtrip.io.input.valid := io.input.valid && state === idle
  yRoundtrip.io.input.bits.strategy := io.input.bits.strategy
  yRoundtrip.io.input.bits.coefficients := io.input.bits.coefficients(1)
  yRoundtrip.io.input.bits.quant := io.input.bits.quant
  yRoundtrip.io.input.bits.scaleQ16 := io.input.bits.scaleQ16
  yRoundtrip.io.input.bits.invQacQ16 := io.input.bits.invQacQ16
  yRoundtrip.io.output.ready :=
    state === waitForY && io.output.ready && xResidual.io.input.ready && bResidual.io.input.ready

  xResidual.io.input.valid := state === waitForY && yRoundtrip.io.output.valid
  xResidual.io.input.bits.strategy := input.strategy
  xResidual.io.input.bits.coefficients := input.coefficients(0)
  xResidual.io.input.bits.reconstructedY := yRoundtrip.io.output.bits.roundtripped
  xResidual.io.input.bits.channel := 0.U
  xResidual.io.input.bits.cflMultiplier := input.ytox
  xResidual.io.input.bits.quant := input.quant
  xResidual.io.input.bits.scaleQ16 := input.scaleQ16
  xResidual.io.input.bits.qmMultiplierQ16 := input.xQmMultiplierQ16
  xResidual.io.output.ready := io.output.ready

  bResidual.io.input.valid := state === waitForY && yRoundtrip.io.output.valid
  bResidual.io.input.bits.strategy := input.strategy
  bResidual.io.input.bits.coefficients := input.coefficients(2)
  bResidual.io.input.bits.reconstructedY := yRoundtrip.io.output.bits.roundtripped
  bResidual.io.input.bits.channel := 2.U
  bResidual.io.input.bits.cflMultiplier := input.ytob
  bResidual.io.input.bits.quant := input.quant
  bResidual.io.input.bits.scaleQ16 := input.scaleQ16
  bResidual.io.input.bits.qmMultiplierQ16 := QuantizeDct8x8Block.DefaultQmMultiplierQ16.U
  bResidual.io.output.ready := io.output.ready

  io.input.ready := state === idle && yRoundtrip.io.input.ready
  io.output.valid :=
    state === waitForY && yRoundtrip.io.output.valid &&
      xResidual.io.output.valid && bResidual.io.output.valid

  val yDcCoefficients = lowFrequencyPair(input.coefficients(1), input.strategy)
  val xDcCoefficients = lowFrequencyPair(xResidual.io.output.bits.residual, input.strategy)
  val bDcCoefficients = lowFrequencyPair(bResidual.io.output.bits.residual, input.strategy)

  val quantizedYDc = Wire(Vec(2, SInt(c.traceValueBits.W)))
  for (covered <- 0 until QuantizeVarDctBlock.MaxCoveredBlocks) {
    quantizedYDc(covered) :=
      quantizeDc(yDcCoefficients(covered), 1, input.invDcFactorQ16(1), 0.S)
  }

  io.output.bits.strategy := input.strategy
  io.output.bits.coefficientCount := Mux(
    input.strategy === AcStrategyCode.Dct.U,
    QuantizeVarDctBlock.DctCoefficients.U,
    QuantizeVarDctBlock.RectangularCoefficients.U
  )
  io.output.bits.coveredBlocks := Mux(
    input.strategy === AcStrategyCode.Dct.U,
    1.U,
    QuantizeVarDctBlock.MaxCoveredBlocks.U
  )
  for (channel <- 0 until 3; i <- 0 until maxCoefficients) {
    val quantized = channel match {
      case 0 => xResidual.io.output.bits.quantized(i)
      case 1 => yRoundtrip.io.output.bits.quantized(i)
      case 2 => bResidual.io.output.bits.quantized(i)
    }
    io.output.bits.quantizedAc(channel)(i) := quantized
  }
  for (covered <- 0 until QuantizeVarDctBlock.MaxCoveredBlocks) {
    io.output.bits.quantizedDc(0)(covered) :=
      quantizeDc(xDcCoefficients(covered), 0, input.invDcFactorQ16(0), quantizedYDc(covered))
    io.output.bits.quantizedDc(1)(covered) := quantizedYDc(covered)
    io.output.bits.quantizedDc(2)(covered) :=
      quantizeDc(bDcCoefficients(covered), 2, input.invDcFactorQ16(2), quantizedYDc(covered))
  }
  io.output.bits.numNonzeros(0) := xResidual.io.output.bits.numNonzeros
  io.output.bits.numNonzeros(1) := yRoundtrip.io.output.bits.numNonzeros
  io.output.bits.numNonzeros(2) := bResidual.io.output.bits.numNonzeros
  for (channel <- 0 until 3) {
    io.output.bits.shiftedNumNonzeros(channel) := Mux(
      input.strategy === AcStrategyCode.Dct.U,
      io.output.bits.numNonzeros(channel),
      (io.output.bits.numNonzeros(channel) +& 1.U) >> 1
    )
  }

  when(io.input.fire) {
    assert(
      io.input.bits.strategy <= AcStrategyCode.Dct8x16.U,
      "VarDCT quantizer received an unsupported strategy"
    )
    input := io.input.bits
    state := waitForY
  }

  when(io.output.fire) {
    state := idle
  }
}

/** Focused trace wrapper for one first-block-owned VarDCT quantization result.
  *
  * AC indices use `channel * coefficientCount + coefficient`. DC indices use
  * `channel * coveredBlocks + coveredCell`. Raw nonzero counts use indices
  * 0..2 and the shifted prediction-map counts use indices 3..5.
  */
class VarDctQuantizeTraceStage(
    c: HjxlConfig = HjxlConfig(),
    coefficientFractionBits: Int = Dct8Approx.FractionBits
) extends Module {
  private val maxCoefficients = QuantizeVarDctBlock.MaxCoefficients
  private val maxOutputCount = 3 * maxCoefficients + 3 * 2 + 6

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new VarDctQuantizeTraceInput(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
  })

  val quantizer = Module(new VarDctQuantizeBlock(c, coefficientFractionBits))
  val idle :: waitForQuantizer :: emitting :: Nil = Enum(3)
  val state = RegInit(idle)
  val emitIndex = RegInit(0.U(log2Ceil(maxOutputCount + 1).W))
  val group = Reg(UInt(c.groupBits.W))
  val result = Reg(new VarDctQuantizeBlockOutput(c))

  quantizer.io.input.valid := io.input.valid && state === idle
  quantizer.io.input.bits := io.input.bits.quantize
  quantizer.io.output.ready := state === waitForQuantizer

  val coefficientCountSafe = Mux(result.coefficientCount === 0.U, 1.U, result.coefficientCount)
  val coveredBlocksSafe = Mux(result.coveredBlocks === 0.U, 1.U, result.coveredBlocks)
  val acCount = result.coefficientCount * 3.U
  val dcCount = result.coveredBlocks * 3.U
  val totalCount = acCount + dcCount + 6.U
  val isAc = emitIndex < acCount
  val isDc = emitIndex >= acCount && emitIndex < acCount + dcCount
  val acChannel = emitIndex / coefficientCountSafe
  val acCoefficient = emitIndex - acChannel * coefficientCountSafe
  val dcLocal = emitIndex - acCount
  val dcChannel = dcLocal / coveredBlocksSafe
  val dcCovered = dcLocal - dcChannel * coveredBlocksSafe
  val nonzeroLocal = emitIndex - acCount - dcCount
  val nonzeroChannel = Mux(nonzeroLocal < 3.U, nonzeroLocal, nonzeroLocal - 3.U)
  val shiftedNonzero = nonzeroLocal >= 3.U

  io.input.ready := state === idle && quantizer.io.input.ready
  io.trace.valid := state === emitting
  io.trace.bits.stage := Mux(
    isAc,
    TraceStage.QuantizedAc.U,
    Mux(isDc, TraceStage.QuantDc.U, TraceStage.NumNonzeros.U)
  )
  io.trace.bits.group := group
  io.trace.bits.index := Mux(isAc, emitIndex, Mux(isDc, dcLocal, nonzeroLocal))
  io.trace.bits.value := Mux(
    isAc,
    result.quantizedAc(acChannel(1, 0))(acCoefficient(6, 0)),
    Mux(
      isDc,
      result.quantizedDc(dcChannel(1, 0))(dcCovered(0)),
      Mux(
        shiftedNonzero,
        result.shiftedNumNonzeros(nonzeroChannel(1, 0)),
        result.numNonzeros(nonzeroChannel(1, 0))
      ).asSInt.pad(c.traceValueBits)
    )
  )
  io.traceLast := io.trace.valid && emitIndex === totalCount - 1.U
  io.busy := state =/= idle

  when(io.input.fire) {
    group := io.input.bits.group
    emitIndex := 0.U
    state := waitForQuantizer
  }

  when(state === waitForQuantizer && quantizer.io.output.fire) {
    result := quantizer.io.output.bits
    state := emitting
  }

  when(io.trace.fire) {
    when(io.traceLast) {
      emitIndex := 0.U
      state := idle
    }.otherwise {
      emitIndex := emitIndex + 1.U
    }
  }
}
