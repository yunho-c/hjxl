// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

object QuantizeDct8x8Block {
  val InvMatrixFractionBits = 16
  val ScaleFractionBits = 16
  val QmFractionBits = 16

  val DefaultRawQuant = 5
  val DefaultScaleQ16 = DistanceParamsLookup.Default.scaleQ16
  val DefaultInvQacQ16 = DistanceParamsLookup.Default.invQacQ16
  val DefaultQmMultiplierQ16 = 1 << QmFractionBits
  val DefaultInvDcFactorQ16: Seq[Int] = DistanceParamsLookup.Default.invDcFactorQ16

  def invQacQ16For(scaleQ16: Int, rawQuant: Int = DefaultRawQuant): Int =
    ((BigInt(1) << 32) / (BigInt(scaleQ16) * BigInt(rawQuant))).toInt

  val DctXInvQ16: Seq[Long] = Seq(
    0, 206438240, 205734432, 173580464, 146452064, 123563264, 104251904, 87958504,
    206438240, 206438208, 197644048, 168858816, 143419936, 121501336, 102795848, 86902568,
    205734432, 197644048, 178716208, 156605648, 135160208, 115734224, 98657424, 83869496,
    173580464, 168858816, 156605648, 140535856, 123563264, 107290176, 92430272, 79219120,
    146452064, 143419936, 135160208, 123563264, 110512008, 97348872, 84858856, 69507016,
    123563264, 121501336, 115734224, 107290176, 97348872, 86902568, 76643696, 51508872,
    104251904, 102795848, 98657424, 92430272, 84858856, 76643696, 54965184, 36571552,
    87958504, 86902568, 83869496, 79219120, 69507016, 51508872, 36571552, 25077688
  )

  val DctYInvQ16: Seq[Long] = Seq(
    0, 36700132, 36602540, 32059826, 28080906, 24595806, 21543242, 18869526,
    36700132, 36700124, 35475252, 31377774, 27626534, 24275182, 21308262, 18692656,
    36602540, 35475252, 32797080, 29587730, 26377868, 23371944, 20636350, 18181960,
    32059826, 31377774, 29587730, 27192376, 24595806, 22031282, 19613446, 17390946,
    28080906, 27626534, 26377868, 24595806, 22545458, 20422610, 18348984, 16391852,
    24595806, 24275182, 23371944, 22031282, 20422610, 18692656, 16948494, 15259739,
    21543242, 21308262, 20636350, 19613446, 18348984, 16948494, 15498261, 14061320,
    18869526, 18692656, 18181960, 17390946, 16391852, 15259739, 14061320, 12849758
  )

  val DctBInvQ16: Seq[Long] = Seq(
    0, 19264944, 11106382, 7825810, 5592405, 5592401, 5475586, 3858222,
    19264944, 15309101, 10225394, 7393602, 5592404, 5592401, 5319209, 3763414,
    11106382, 10225394, 8310282, 6330810, 5592404, 5592400, 4887470, 3497828,
    7825810, 7393602, 6330810, 5592404, 5592401, 5592398, 4273197, 3110020,
    5592405, 5592404, 5592404, 5592401, 5592398, 4754866, 3583360, 2583396,
    5592401, 5592401, 5592400, 5592398, 4754866, 3763414, 2905322, 1914451,
    5475586, 5319209, 4887470, 4273197, 3583360, 2905322, 2042913, 1359270,
    3858222, 3763414, 3497828, 3110020, 2583396, 1914451, 1359270, 932073
  )

  val ThresholdXQ12: Seq[Int] = Seq(2376, 2929, 3031, 3195)
  val ThresholdYQ12: Seq[Int] = Seq(2376, 2601, 2703, 2867)
  val ThresholdBQ12: Seq[Int] = Seq(2376, 3072, 3072, 3072)

  val QuantBiasYQ16 = 60945
  val QuantBiasOtherNumeratorQ16 = 9503
  val WeightFractionBits = 32
  val BiasFractionBits = 16
  val InvQacFractionBits = 16
  val CflFactorFractionBits = 16
  val ColorFactorDenominator = 84
  val DcFactorFractionBits = 16
  val DcProductFractionBits = Dct8Approx.FractionBits + DcFactorFractionBits
  val BQuantDcFromYFactorQ16 = 1 << (DcFactorFractionBits - 1)

  val DctYWeightsQ32: Seq[Long] = Seq(
    7669586, 7669590, 7690040, 8779679, 10023714, 11444023, 13065581, 14916908,
    7669590, 7669592, 7934404, 8970521, 10188574, 11595175, 13209664, 15058051,
    7690040, 7934404, 8582318, 9513233, 10670877, 12043285, 13639766, 15481003,
    8779679, 8970521, 9513233, 10351246, 11444023, 12776151, 14351123, 16185145,
    10023714, 10188574, 10670877, 11444023, 12484775, 13782517, 15340085, 17171640,
    11444023, 11595175, 12043285, 12776151, 13782517, 15058051, 16607669, 18445596,
    13065581, 13209664, 13639766, 14351123, 15340085, 16607669, 18161714, 20017678,
    14916908, 15058051, 15481003, 16185145, 17171640, 18445596, 20017678, 21905080
  )
}

class DctQuantizeInput(c: HjxlConfig) extends Bundle {
  val coefficients = Vec(HjxlConstants.BlockDim * HjxlConstants.BlockDim, SInt(c.traceValueBits.W))
  val channel = UInt(2.W)
  val quant = UInt(8.W)
  val scaleQ16 = UInt(16.W)
  val qmMultiplierQ16 = UInt(32.W)
}

class DctQuantizeOutput(c: HjxlConfig) extends Bundle {
  val quantized = Vec(HjxlConstants.BlockDim * HjxlConstants.BlockDim, SInt(c.traceValueBits.W))
  val numNonzeros = UInt(8.W)
}

class DctQuantizeTraceInput(c: HjxlConfig) extends Bundle {
  val group = UInt(c.groupBits.W)
  val quantize = new DctQuantizeInput(c)
}

class DctYRoundtripInput(c: HjxlConfig) extends Bundle {
  val coefficients = Vec(HjxlConstants.BlockDim * HjxlConstants.BlockDim, SInt(c.traceValueBits.W))
  val quant = UInt(8.W)
  val scaleQ16 = UInt(16.W)
  val invQacQ16 = UInt(32.W)
}

class DctYRoundtripOutput(c: HjxlConfig) extends Bundle {
  val quantized = Vec(HjxlConstants.BlockDim * HjxlConstants.BlockDim, SInt(c.traceValueBits.W))
  val roundtripped = Vec(HjxlConstants.BlockDim * HjxlConstants.BlockDim, SInt(c.traceValueBits.W))
  val numNonzeros = UInt(8.W)
}

class DctChromaResidualInput(c: HjxlConfig) extends Bundle {
  val coefficients = Vec(HjxlConstants.BlockDim * HjxlConstants.BlockDim, SInt(c.traceValueBits.W))
  val reconstructedY = Vec(HjxlConstants.BlockDim * HjxlConstants.BlockDim, SInt(c.traceValueBits.W))
  val channel = UInt(2.W)
  val cflMultiplier = SInt(8.W)
  val quant = UInt(8.W)
  val scaleQ16 = UInt(16.W)
  val qmMultiplierQ16 = UInt(32.W)
}

class DctChromaResidualOutput(c: HjxlConfig) extends Bundle {
  val residual = Vec(HjxlConstants.BlockDim * HjxlConstants.BlockDim, SInt(c.traceValueBits.W))
  val quantized = Vec(HjxlConstants.BlockDim * HjxlConstants.BlockDim, SInt(c.traceValueBits.W))
  val numNonzeros = UInt(8.W)
}

class DctQuantizeDcInput(c: HjxlConfig) extends Bundle {
  val dcCoefficient = SInt(c.traceValueBits.W)
  val channel = UInt(2.W)
  val invDcFactorQ16 = UInt(32.W)
  val quantizedYDc = SInt(c.traceValueBits.W)
}

class DctQuantizeDcOutput(c: HjxlConfig) extends Bundle {
  val quantizedDc = SInt(c.traceValueBits.W)
}

class DctOnlyQuantizeBlockInput(c: HjxlConfig) extends Bundle {
  val coefficients = Vec(3, Vec(HjxlConstants.BlockDim * HjxlConstants.BlockDim, SInt(c.traceValueBits.W)))
  val quant = UInt(8.W)
  val scaleQ16 = UInt(16.W)
  val invQacQ16 = UInt(32.W)
  val invDcFactorQ16 = Vec(3, UInt(32.W))
  val xQmMultiplierQ16 = UInt(32.W)
  val ytox = SInt(8.W)
  val ytob = SInt(8.W)
}

class DctOnlyQuantizeBlockOutput(c: HjxlConfig) extends Bundle {
  val quantizedAc = Vec(3, Vec(HjxlConstants.BlockDim * HjxlConstants.BlockDim, SInt(c.traceValueBits.W)))
  val quantizedDc = Vec(3, SInt(c.traceValueBits.W))
  val numNonzeros = Vec(3, UInt(8.W))
}

class DctOnlyQuantizeTraceInput(c: HjxlConfig) extends Bundle {
  val group = UInt(c.groupBits.W)
  val quantize = new DctOnlyQuantizeBlockInput(c)
}

/** Quantizes one DCT-only 8x8 block with libjxl-tiny's AC quantization rules.
  *
  * Inputs are scaled DCT coefficients in Q12, `scaleQ16` is the distance-derived
  * AC scale from libjxl-tiny's `compute_distance_params`, and `quant` is the
  * block's adjusted raw quant field value. This primitive deliberately does not
  * compute AQ, CFL, rectangular transforms, or DC planes; it is the reusable
  * DCT-only AC quantization kernel those stages will feed.
  */
class QuantizeDct8x8Block(c: HjxlConfig = HjxlConfig()) extends Module {
  private val blockDim = HjxlConstants.BlockDim
  private val blockSize = blockDim * blockDim
  private val productBits = 128
  private val halfQ12 = 1 << (Dct8Approx.FractionBits - 1)
  private val totalProductFractionBits =
    QuantizeDct8x8Block.ScaleFractionBits +
      QuantizeDct8x8Block.QmFractionBits

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new DctQuantizeInput(c)))
    val output = Decoupled(new DctQuantizeOutput(c))
  })

  private def constTable(values: Seq[Long]): Vec[SInt] =
    VecInit(values.map(_.S(64.W)))

  private val invX = constTable(QuantizeDct8x8Block.DctXInvQ16)
  private val invY = constTable(QuantizeDct8x8Block.DctYInvQ16)
  private val invB = constTable(QuantizeDct8x8Block.DctBInvQ16)

  private def thresholdIndex(coefficient: Int): Int = {
    val x = coefficient % blockDim
    val y = coefficient / blockDim
    (if (y >= blockDim / 2) 2 else 0) + (if (x >= blockDim / 2) 1 else 0)
  }

  private def roundQ12(value: SInt): SInt = {
    val magnitude = Mux(value < 0.S, -value, value)
    val roundedMagnitude = (magnitude + halfQ12.S) >> Dct8Approx.FractionBits
    Mux(value < 0.S, -roundedMagnitude, roundedMagnitude).asSInt
  }

  private val quant = Cat(0.U(1.W), io.input.bits.quant).asSInt
  private val scale = Cat(0.U(1.W), io.input.bits.scaleQ16).asSInt
  private val qmMultiplier = Cat(0.U(1.W), io.input.bits.qmMultiplierQ16).asSInt

  io.input.ready := io.output.ready
  io.output.valid := io.input.valid

  private val quantized = Seq.tabulate(blockSize) { i =>
    val inv = MuxLookup(io.input.bits.channel, invY(i))(
      Seq(
        0.U -> invX(i),
        1.U -> invY(i),
        2.U -> invB(i)
      )
    )
    val weightedQ12 =
      (io.input.bits.coefficients(i).pad(productBits) * inv.pad(productBits)) >>
        QuantizeDct8x8Block.InvMatrixFractionBits
    val product =
      weightedQ12.asSInt.pad(productBits) *
        scale.pad(productBits) *
        quant.pad(productBits) *
        qmMultiplier.pad(productBits)
    val valueQ12 = (product >> totalProductFractionBits).asSInt
    val absValueQ12 = Mux(valueQ12 < 0.S, -valueQ12, valueQ12)
    val threshold = MuxLookup(io.input.bits.channel, QuantizeDct8x8Block.ThresholdYQ12(thresholdIndex(i)).S)(
      Seq(
        0.U -> QuantizeDct8x8Block.ThresholdXQ12(thresholdIndex(i)).S,
        1.U -> QuantizeDct8x8Block.ThresholdYQ12(thresholdIndex(i)).S,
        2.U -> QuantizeDct8x8Block.ThresholdBQ12(thresholdIndex(i)).S
      )
    )
    Mux(absValueQ12 >= threshold, roundQ12(valueQ12), 0.S)
  }

  for (i <- 0 until blockSize) {
    io.output.bits.quantized(i) := quantized(i)(c.traceValueBits - 1, 0).asSInt
  }
  io.output.bits.numNonzeros := PopCount(quantized.zipWithIndex.map {
    case (_, 0) => false.B
    case (value, _) => value =/= 0.S
  })
}

/** Quantizes Y AC and reconstructs the coefficient-domain roundtrip.
  *
  * libjxl-tiny quantizes Y first, applies quantization-bias adjustment, then
  * dequantizes it back to coefficient space. X/B quantization subtracts CFL
  * prediction from that reconstructed Y, so this is the next block primitive
  * needed after raw DCT-only coefficient quantization.
  */
class QuantizeRoundtripYDct8x8Block(c: HjxlConfig = HjxlConfig()) extends Module {
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim
  private val productBits = 128
  private val roundtripShift =
    QuantizeDct8x8Block.BiasFractionBits +
      QuantizeDct8x8Block.WeightFractionBits +
      QuantizeDct8x8Block.InvQacFractionBits -
      Dct8Approx.FractionBits

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new DctYRoundtripInput(c)))
    val output = Decoupled(new DctYRoundtripOutput(c))
  })

  val yQuantizer = Module(new QuantizeDct8x8Block(c))
  yQuantizer.io.input.valid := io.input.valid
  yQuantizer.io.input.bits.coefficients := io.input.bits.coefficients
  yQuantizer.io.input.bits.channel := 1.U
  yQuantizer.io.input.bits.quant := io.input.bits.quant
  yQuantizer.io.input.bits.scaleQ16 := io.input.bits.scaleQ16
  yQuantizer.io.input.bits.qmMultiplierQ16 := QuantizeDct8x8Block.DefaultQmMultiplierQ16.U
  yQuantizer.io.output.ready := io.output.ready

  private val weights = VecInit(QuantizeDct8x8Block.DctYWeightsQ32.map(_.S(64.W)))
  private val invQac = Cat(0.U(1.W), io.input.bits.invQacQ16).asSInt

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

  io.input.ready := yQuantizer.io.input.ready
  io.output.valid := yQuantizer.io.output.valid

  for (i <- 0 until blockSize) {
    val adjustedQ16 = adjustedQuantQ16(yQuantizer.io.output.bits.quantized(i))
    val product =
      adjustedQ16.pad(productBits) *
        weights(i).pad(productBits) *
        invQac.pad(productBits)
    io.output.bits.quantized(i) := yQuantizer.io.output.bits.quantized(i)
    io.output.bits.roundtripped(i) := Dct8Approx.fit((product >> roundtripShift).asSInt, c.traceValueBits)
  }
  io.output.bits.numNonzeros := yQuantizer.io.output.bits.numNonzeros
}

/** Subtracts reconstructed-Y CFL prediction and quantizes an X or B block.
  *
  * For X, libjxl-tiny uses `x - ytox / 84 * reconstructedY`. For B it uses
  * `b - (1 + ytob / 84) * reconstructedY`. The residual is then quantized with
  * the same DCT-only AC quantizer as standalone X/B blocks.
  */
class QuantizeChromaResidualDct8x8Block(c: HjxlConfig = HjxlConfig()) extends Module {
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim
  private val productBits = 96

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new DctChromaResidualInput(c)))
    val output = Decoupled(new DctChromaResidualOutput(c))
  })

  private val baseFactorQ16 =
    Mux(io.input.bits.channel === 2.U, (1 << QuantizeDct8x8Block.CflFactorFractionBits).S, 0.S)
  private val cflFactorQ16 =
    baseFactorQ16 +
      ((io.input.bits.cflMultiplier.pad(32) * (1 << QuantizeDct8x8Block.CflFactorFractionBits).S) /
        QuantizeDct8x8Block.ColorFactorDenominator.S)

  private val residual = Seq.tabulate(blockSize) { i =>
    val predicted =
      (cflFactorQ16.pad(productBits) * io.input.bits.reconstructedY(i).pad(productBits)) >>
        QuantizeDct8x8Block.CflFactorFractionBits
    Dct8Approx.fit(io.input.bits.coefficients(i) - predicted.asSInt, c.traceValueBits)
  }

  val quantizer = Module(new QuantizeDct8x8Block(c))
  quantizer.io.input.valid := io.input.valid
  quantizer.io.input.bits.coefficients := VecInit(residual)
  quantizer.io.input.bits.channel := io.input.bits.channel
  quantizer.io.input.bits.quant := io.input.bits.quant
  quantizer.io.input.bits.scaleQ16 := io.input.bits.scaleQ16
  quantizer.io.input.bits.qmMultiplierQ16 := io.input.bits.qmMultiplierQ16
  quantizer.io.output.ready := io.output.ready

  io.input.ready := quantizer.io.input.ready
  io.output.valid := quantizer.io.output.valid
  for (i <- 0 until blockSize) {
    io.output.bits.residual(i) := residual(i)
    io.output.bits.quantized(i) := quantizer.io.output.bits.quantized(i)
  }
  io.output.bits.numNonzeros := quantizer.io.output.bits.numNonzeros
}

/** Quantizes one DCT-only DC coefficient.
  *
  * For Y this is `round_away(dcY * inv_factor[Y])`. For X/B, callers should
  * pass the CFL residual DC coefficient; B additionally subtracts half of the
  * already-quantized Y DC, matching libjxl-tiny's DC storage convention.
  */
class QuantizeDcDct8x8Block(c: HjxlConfig = HjxlConfig()) extends Module {
  private val productBits = 128
  private val half = BigInt(1) << (QuantizeDct8x8Block.DcProductFractionBits - 1)

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new DctQuantizeDcInput(c)))
    val output = Decoupled(new DctQuantizeDcOutput(c))
  })

  private def roundFixedToInt(value: SInt): SInt = {
    val magnitude = Mux(value < 0.S, -value, value)
    val roundedMagnitude =
      (magnitude + half.S) >> QuantizeDct8x8Block.DcProductFractionBits
    Mux(value < 0.S, -roundedMagnitude, roundedMagnitude).asSInt
  }

  private val invDcFactor = Cat(0.U(1.W), io.input.bits.invDcFactorQ16).asSInt
  private val baseProduct =
    io.input.bits.dcCoefficient.pad(productBits) * invDcFactor.pad(productBits)
  private val bCorrection =
    (io.input.bits.quantizedYDc.pad(productBits) *
      QuantizeDct8x8Block.BQuantDcFromYFactorQ16.S) << Dct8Approx.FractionBits
  private val correctedProduct =
    baseProduct - Mux(io.input.bits.channel === 2.U, bCorrection, 0.S(productBits.W))

  io.input.ready := io.output.ready
  io.output.valid := io.input.valid
  io.output.bits.quantizedDc := Dct8Approx.fit(roundFixedToInt(correctedProduct.asSInt), c.traceValueBits)
}

/** Prepared DCT-only quantized block for one 8x8 X/Y/B coefficient triplet.
  *
  * This composes the block primitives in libjxl-tiny order: quantize and
  * reconstruct Y, subtract reconstructed-Y CFL prediction from X/B, quantize
  * X/B residuals, and emit AC/DC/nonzero results for all three channels.
  */
class DctOnlyQuantizeBlock(c: HjxlConfig = HjxlConfig()) extends Module {
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new DctOnlyQuantizeBlockInput(c)))
    val output = Decoupled(new DctOnlyQuantizeBlockOutput(c))
  })

  val yRoundtrip = Module(new QuantizeRoundtripYDct8x8Block(c))
  val xResidual = Module(new QuantizeChromaResidualDct8x8Block(c))
  val bResidual = Module(new QuantizeChromaResidualDct8x8Block(c))
  val yDc = Module(new QuantizeDcDct8x8Block(c))
  val xDc = Module(new QuantizeDcDct8x8Block(c))
  val bDc = Module(new QuantizeDcDct8x8Block(c))

  yRoundtrip.io.input.valid := io.input.valid
  yRoundtrip.io.input.bits.coefficients := io.input.bits.coefficients(1)
  yRoundtrip.io.input.bits.quant := io.input.bits.quant
  yRoundtrip.io.input.bits.scaleQ16 := io.input.bits.scaleQ16
  yRoundtrip.io.input.bits.invQacQ16 := io.input.bits.invQacQ16
  yRoundtrip.io.output.ready := io.output.ready

  xResidual.io.input.valid := io.input.valid
  xResidual.io.input.bits.coefficients := io.input.bits.coefficients(0)
  xResidual.io.input.bits.reconstructedY := yRoundtrip.io.output.bits.roundtripped
  xResidual.io.input.bits.channel := 0.U
  xResidual.io.input.bits.cflMultiplier := io.input.bits.ytox
  xResidual.io.input.bits.quant := io.input.bits.quant
  xResidual.io.input.bits.scaleQ16 := io.input.bits.scaleQ16
  xResidual.io.input.bits.qmMultiplierQ16 := io.input.bits.xQmMultiplierQ16
  xResidual.io.output.ready := io.output.ready

  bResidual.io.input.valid := io.input.valid
  bResidual.io.input.bits.coefficients := io.input.bits.coefficients(2)
  bResidual.io.input.bits.reconstructedY := yRoundtrip.io.output.bits.roundtripped
  bResidual.io.input.bits.channel := 2.U
  bResidual.io.input.bits.cflMultiplier := io.input.bits.ytob
  bResidual.io.input.bits.quant := io.input.bits.quant
  bResidual.io.input.bits.scaleQ16 := io.input.bits.scaleQ16
  bResidual.io.input.bits.qmMultiplierQ16 := QuantizeDct8x8Block.DefaultQmMultiplierQ16.U
  bResidual.io.output.ready := io.output.ready

  yDc.io.input.valid := io.input.valid
  yDc.io.input.bits.dcCoefficient := io.input.bits.coefficients(1)(0)
  yDc.io.input.bits.channel := 1.U
  yDc.io.input.bits.invDcFactorQ16 := io.input.bits.invDcFactorQ16(1)
  yDc.io.input.bits.quantizedYDc := 0.S
  yDc.io.output.ready := io.output.ready

  xDc.io.input.valid := io.input.valid
  xDc.io.input.bits.dcCoefficient := xResidual.io.output.bits.residual(0)
  xDc.io.input.bits.channel := 0.U
  xDc.io.input.bits.invDcFactorQ16 := io.input.bits.invDcFactorQ16(0)
  xDc.io.input.bits.quantizedYDc := yDc.io.output.bits.quantizedDc
  xDc.io.output.ready := io.output.ready

  bDc.io.input.valid := io.input.valid
  bDc.io.input.bits.dcCoefficient := bResidual.io.output.bits.residual(0)
  bDc.io.input.bits.channel := 2.U
  bDc.io.input.bits.invDcFactorQ16 := io.input.bits.invDcFactorQ16(2)
  bDc.io.input.bits.quantizedYDc := yDc.io.output.bits.quantizedDc
  bDc.io.output.ready := io.output.ready

  io.input.ready :=
    yRoundtrip.io.input.ready && xResidual.io.input.ready && bResidual.io.input.ready &&
      yDc.io.input.ready && xDc.io.input.ready && bDc.io.input.ready
  io.output.valid :=
    yRoundtrip.io.output.valid && xResidual.io.output.valid && bResidual.io.output.valid &&
      yDc.io.output.valid && xDc.io.output.valid && bDc.io.output.valid

  for (i <- 0 until blockSize) {
    io.output.bits.quantizedAc(0)(i) := xResidual.io.output.bits.quantized(i)
    io.output.bits.quantizedAc(1)(i) := yRoundtrip.io.output.bits.quantized(i)
    io.output.bits.quantizedAc(2)(i) := bResidual.io.output.bits.quantized(i)
  }
  io.output.bits.quantizedDc(0) := xDc.io.output.bits.quantizedDc
  io.output.bits.quantizedDc(1) := yDc.io.output.bits.quantizedDc
  io.output.bits.quantizedDc(2) := bDc.io.output.bits.quantizedDc
  io.output.bits.numNonzeros(0) := xResidual.io.output.bits.numNonzeros
  io.output.bits.numNonzeros(1) := yRoundtrip.io.output.bits.numNonzeros
  io.output.bits.numNonzeros(2) := bResidual.io.output.bits.numNonzeros
}

/** Trace wrapper for one prepared DCT-only quantized X/Y/B block. */
class DctOnlyQuantizeTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim
  private val acCount = 3 * blockSize
  private val dcCount = 3
  private val numNonzerosCount = 3
  private val outputCount = acCount + dcCount + numNonzerosCount

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new DctOnlyQuantizeTraceInput(c)))
    val trace = Decoupled(new StageTrace(c))
    val busy = Output(Bool())
  })

  val quantizer = Module(new DctOnlyQuantizeBlock(c))
  val idle :: emitting :: Nil = Enum(2)
  val state = RegInit(idle)
  val emitIndex = RegInit(0.U(log2Ceil(outputCount + 1).W))
  val group = Reg(UInt(c.groupBits.W))
  val quantizedAc = Reg(Vec(3, Vec(blockSize, SInt(c.traceValueBits.W))))
  val quantizedDc = Reg(Vec(3, SInt(c.traceValueBits.W)))
  val numNonzeros = Reg(Vec(3, UInt(8.W)))

  quantizer.io.input.valid := io.input.valid && state === idle
  quantizer.io.input.bits := io.input.bits.quantize
  quantizer.io.output.ready := true.B

  val isAc = emitIndex < acCount.U
  val isDc = emitIndex >= acCount.U && emitIndex < (acCount + dcCount).U
  val acChannel = emitIndex / blockSize.U
  val acCoefficient = emitIndex - acChannel * blockSize.U
  val dcChannel = emitIndex - acCount.U
  val nnzChannel = emitIndex - (acCount + dcCount).U
  val acChannelSafe = Mux(isAc, acChannel, 0.U)(1, 0)
  val acCoefficientSafe = Mux(isAc, acCoefficient, 0.U)(log2Ceil(blockSize) - 1, 0)
  val dcChannelSafe = Mux(isDc, dcChannel, 0.U)(1, 0)
  val nnzChannelSafe = Mux(!isAc && !isDc, nnzChannel, 0.U)(1, 0)

  io.input.ready := state === idle && quantizer.io.input.ready
  io.trace.valid := state === emitting
  io.busy := state =/= idle
  io.trace.bits.group := group
  io.trace.bits.stage := Mux(
    isAc,
    TraceStage.QuantizedAc.U,
    Mux(isDc, TraceStage.QuantDc.U, TraceStage.NumNonzeros.U)
  )
  io.trace.bits.index := Mux(
    isAc,
    emitIndex,
    Mux(isDc, dcChannel, nnzChannel)
  )
  io.trace.bits.value := Mux(
    isAc,
    quantizedAc(acChannelSafe)(acCoefficientSafe),
    Mux(
      isDc,
      quantizedDc(dcChannelSafe),
      numNonzeros(nnzChannelSafe).asSInt.pad(c.traceValueBits)
    )
  )

  when(io.input.fire) {
    group := io.input.bits.group
    quantizedAc := quantizer.io.output.bits.quantizedAc
    quantizedDc := quantizer.io.output.bits.quantizedDc
    numNonzeros := quantizer.io.output.bits.numNonzeros
    emitIndex := 0.U
    state := emitting
  }

  when(io.trace.fire) {
    val nextEmit = emitIndex + 1.U
    emitIndex := nextEmit
    when(nextEmit === outputCount.U) {
      state := idle
      emitIndex := 0.U
    }
  }
}

/** Trace wrapper for one prepared DCT-only quantization block.
  *
  * This wrapper is a simulation/verification boundary: upstream software or a
  * future AQ/CFL stage provides the adjusted quant parameters and coefficients,
  * and the wrapper emits trace records in the same shape later frame stages
  * should use.
  */
class DctQuantizeTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim
  private val outputCount = blockSize + 1

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new DctQuantizeTraceInput(c)))
    val trace = Decoupled(new StageTrace(c))
    val busy = Output(Bool())
  })

  val quantizer = Module(new QuantizeDct8x8Block(c))
  val idle :: emitting :: Nil = Enum(2)
  val state = RegInit(idle)
  val emitIndex = RegInit(0.U(log2Ceil(outputCount + 1).W))
  val group = Reg(UInt(c.groupBits.W))
  val channel = Reg(UInt(2.W))
  val quantized = Reg(Vec(blockSize, SInt(c.traceValueBits.W)))
  val numNonzeros = Reg(UInt(8.W))

  quantizer.io.input.valid := io.input.valid && state === idle
  quantizer.io.input.bits := io.input.bits.quantize
  quantizer.io.output.ready := true.B

  io.input.ready := state === idle
  io.trace.valid := state === emitting
  io.busy := state =/= idle
  io.trace.bits.stage := Mux(emitIndex < blockSize.U, TraceStage.QuantizedAc.U, TraceStage.NumNonzeros.U)
  io.trace.bits.group := group
  io.trace.bits.index := Mux(
    emitIndex < blockSize.U,
    channel * blockSize.U + emitIndex,
    channel
  )
  io.trace.bits.value := Mux(
    emitIndex < blockSize.U,
    quantized(emitIndex(log2Ceil(blockSize) - 1, 0)),
    numNonzeros.asSInt.pad(c.traceValueBits)
  )

  when(io.input.fire) {
    group := io.input.bits.group
    channel := io.input.bits.quantize.channel
    quantized := quantizer.io.output.bits.quantized
    numNonzeros := quantizer.io.output.bits.numNonzeros
    emitIndex := 0.U
    state := emitting
  }

  when(io.trace.fire) {
    val nextEmit = emitIndex + 1.U
    emitIndex := nextEmit
    when(nextEmit === outputCount.U) {
      state := idle
      emitIndex := 0.U
    }
  }
}
