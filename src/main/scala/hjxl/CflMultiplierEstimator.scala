// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** Quantizes libjxl-tiny's regularized CFL least-squares result to int8.
  *
  * Upstream tile logic should accumulate Q16 `sum(a * a)` and `sum(a * b)`
  * terms from chroma-from-luma's weighted AC coefficients. This primitive keeps
  * the division/round/clamp boundary isolated so the future tile estimator can
  * be validated separately from DCT accumulation.
  */
class CflMultiplierEstimator(
    sumAaQ16Width: Int = 32,
    sumAbQ16Width: Int = 32,
    countBits: Int = 16
) extends Module {
  require(sumAaQ16Width > 0, "sumAaQ16Width must be positive")
  require(sumAbQ16Width > 0, "sumAbQ16Width must be positive")
  require(countBits > 0, "countBits must be positive")

  val io = IO(new Bundle {
    val sumAaQ16 = Input(UInt(sumAaQ16Width.W))
    val sumAbQ16 = Input(SInt(sumAbQ16Width.W))
    val sampleCount = Input(UInt(countBits.W))
    val multiplier = Output(SInt(8.W))
  })

  private val RegularizerQ16 = CflMultiplierEstimator.RegularizerQ16
  private val regularizerBits = log2Ceil(RegularizerQ16 + 1)
  private val denomWidth = math.max(sumAaQ16Width, countBits + regularizerBits) + 1
  private val numeratorWidth = math.max(sumAbQ16Width + 1, denomWidth + 8)

  val regularizerTerm = io.sampleCount * RegularizerQ16.U(regularizerBits.W)
  val denominator = io.sumAaQ16.pad(denomWidth) + regularizerTerm.pad(denomWidth)
  val safeDenominator = Mux(denominator === 0.U, 1.U, denominator)

  val numerator = -io.sumAbQ16.pad(numeratorWidth)
  val numeratorAbs = Mux(numerator < 0.S, (-numerator).asUInt, numerator.asUInt)
  val roundedAbs = (numeratorAbs + (safeDenominator >> 1)) / safeDenominator
  val rounded = Mux(numerator < 0.S, -roundedAbs.asSInt, roundedAbs.asSInt)
  val clamped = Mux(
    rounded > 127.S,
    127.S(8.W),
    Mux(rounded < -128.S, (-128).S(8.W), rounded(7, 0).asSInt)
  )

  io.multiplier := Mux(io.sampleCount === 0.U, 0.S, clamped)
}

object CflMultiplierEstimator {
  // round(0.0005 * 2^16), matching CFL's regularization term in Q16.
  val RegularizerQ16 = 33
}

object CflWeightMatrix {
  val XInvQ16: Seq[Int] = Seq(
    0, 206438240, 205734432, 173580464, 146452064, 123563264, 104251904, 87958504,
    206438240, 206438208, 197644048, 168858816, 143419936, 121501336, 102795848, 86902568,
    205734432, 197644048, 178716208, 156605648, 135160208, 115734224, 98657424, 83869496,
    173580464, 168858816, 156605648, 140535856, 123563264, 107290176, 92430272, 79219120,
    146452064, 143419936, 135160208, 123563264, 110512008, 97348872, 84858856, 69507016,
    123563264, 121501336, 115734224, 107290176, 97348872, 86902568, 76643696, 51508872,
    104251904, 102795848, 98657424, 92430272, 84858856, 76643696, 54965184, 36571552,
    87958504, 86902568, 83869496, 79219120, 69507016, 51508872, 36571552, 25077688
  )

  val BInvQ16: Seq[Int] = Seq(
    0, 19264944, 11106382, 7825810, 5592405, 5592401, 5475586, 3858222,
    19264944, 15309101, 10225394, 7393602, 5592404, 5592401, 5319209, 3763414,
    11106382, 10225394, 8310282, 6330810, 5592404, 5592400, 4887470, 3497828,
    7825810, 7393602, 6330810, 5592404, 5592401, 5592398, 4273197, 3110020,
    5592405, 5592404, 5592404, 5592401, 5592398, 4754866, 3583360, 2583396,
    5592401, 5592401, 5592400, 5592398, 4754866, 3763414, 2905322, 1914451,
    5475586, 5319209, 4887470, 4273197, 3583360, 2905322, 2042913, 1359270,
    3858222, 3763414, 3497828, 3110020, 2583396, 1914451, 1359270, 932073
  )

  require(XInvQ16.length == HjxlConstants.BlockDim * HjxlConstants.BlockDim)
  require(BInvQ16.length == HjxlConstants.BlockDim * HjxlConstants.BlockDim)
}

class CflWeightedSample(valueBits: Int) extends Bundle {
  val modelQ16 = SInt(valueBits.W)
  val signalQ16 = SInt(valueBits.W)
  val baseOne = Bool()
  val last = Bool()
}

class CflWeightedSums(sumBits: Int, countBits: Int) extends Bundle {
  val sumAaQ16 = UInt(sumBits.W)
  val sumAbQ16 = SInt(sumBits.W)
  val sampleCount = UInt(countBits.W)
  val multiplier = SInt(8.W)
}

class CflCoefficientSample(coefficientBits: Int) extends Bundle {
  val coefficientIndex = UInt(log2Ceil(HjxlConstants.BlockDim * HjxlConstants.BlockDim).W)
  val modelCoeffQ16 = SInt(coefficientBits.W)
  val signalCoeffQ16 = SInt(coefficientBits.W)
  val useBWeights = Bool()
  val last = Bool()
}

class CflTileCoefficientSample(coefficientBits: Int) extends Bundle {
  val coefficientIndex = UInt(log2Ceil(HjxlConstants.BlockDim * HjxlConstants.BlockDim).W)
  val yCoeffQ16 = SInt(coefficientBits.W)
  val xCoeffQ16 = SInt(coefficientBits.W)
  val bCoeffQ16 = SInt(coefficientBits.W)
  val last = Bool()
}

class CflTileMultipliers(sumBits: Int, countBits: Int) extends Bundle {
  val x = new CflWeightedSums(sumBits, countBits)
  val b = new CflWeightedSums(sumBits, countBits)
}

class CflTileCoefficientTraceInput(coefficientBits: Int) extends Bundle {
  val tileIndex = UInt(32.W)
  val coefficient = new CflTileCoefficientSample(coefficientBits)
}

/** Applies libjxl-tiny's CFL inverse-weight matrix to one DCT coefficient pair.
  *
  * The output is the pre-weighted `values_m`/`values_s` sample consumed by
  * `CflWeightedSumAccumulator`. Coefficient index 0 has a zero weight, matching
  * the software path where CFL ignores DC.
  */
class CflCoefficientSampleWeight(
    coefficientBits: Int = 32,
    weightBits: Int = 32,
    outputBits: Int = 48
) extends Module {
  require(coefficientBits > 0, "coefficientBits must be positive")
  require(weightBits > 0, "weightBits must be positive")
  require(
    outputBits >= coefficientBits + weightBits - 16,
    "outputBits must fit Q16 products"
  )

  val io = IO(new Bundle {
    val coefficientIndex = Input(UInt(log2Ceil(HjxlConstants.BlockDim * HjxlConstants.BlockDim).W))
    val modelCoeffQ16 = Input(SInt(coefficientBits.W))
    val signalCoeffQ16 = Input(SInt(coefficientBits.W))
    val useBWeights = Input(Bool())
    val last = Input(Bool())
    val output = Output(new CflWeightedSample(outputBits))
  })

  val xWeights = VecInit(CflWeightMatrix.XInvQ16.map(_.S(weightBits.W)))
  val bWeights = VecInit(CflWeightMatrix.BInvQ16.map(_.S(weightBits.W)))
  val weight = Mux(io.useBWeights, bWeights(io.coefficientIndex), xWeights(io.coefficientIndex))
  val modelProduct = io.modelCoeffQ16 * weight
  val signalProduct = io.signalCoeffQ16 * weight

  io.output.modelQ16 := (modelProduct >> 16).pad(outputBits)
  io.output.signalQ16 := (signalProduct >> 16).pad(outputBits)
  io.output.baseOne := io.useBWeights
  io.output.last := io.last
}

/** Accumulates pre-weighted CFL AC coefficient samples into estimator sums.
  *
  * `modelQ16` and `signalQ16` are the weighted luma/chroma coefficient streams
  * from libjxl-tiny's `values_m`/`values_s`, quantized to Q16. For X, `baseOne`
  * is false and the residual term is `-signal`; for B, `baseOne` is true and
  * the residual term is `model - signal`.
  */
class CflWeightedSumAccumulator(
    valueBits: Int = 32,
    sumBits: Int = 48,
    countBits: Int = 16
) extends Module {
  require(valueBits > 0, "valueBits must be positive")
  require(sumBits > valueBits, "sumBits must leave room for accumulated products")
  require(countBits > 0, "countBits must be positive")

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new CflWeightedSample(valueBits)))
    val output = Decoupled(new CflWeightedSums(sumBits, countBits))
  })

  private def roundDivBy84(value: SInt): SInt = {
    val widened = value.pad(value.getWidth + 1)
    val absValue = Mux(widened < 0.S, (-widened).asUInt, widened.asUInt)
    val rounded = (absValue + 42.U) / 84.U
    Mux(widened < 0.S, -rounded.asSInt, rounded.asSInt).asSInt
  }

  val sumAa = RegInit(0.U(sumBits.W))
  val sumAb = RegInit(0.S(sumBits.W))
  val sampleCount = RegInit(0.U(countBits.W))
  val result = Reg(new CflWeightedSums(sumBits, countBits))
  val resultValid = RegInit(false.B)

  val aQ16 = roundDivBy84(io.input.bits.modelQ16)
  val baseTerm = Mux(io.input.bits.baseOne, io.input.bits.modelQ16, 0.S(valueBits.W))
  val bQ16 = baseTerm.pad(valueBits + 1) - io.input.bits.signalQ16.pad(valueBits + 1)
  val aaProduct = aQ16 * aQ16
  val abProduct = aQ16 * bQ16
  val aaTerm = (aaProduct >> 16).asUInt
  val abTerm = abProduct >> 16
  val nextSumAa = sumAa + aaTerm.pad(sumBits)
  val nextSumAb = sumAb + abTerm.pad(sumBits)
  val nextSampleCount = sampleCount + 1.U

  val estimator = Module(new CflMultiplierEstimator(sumBits, sumBits, countBits))
  estimator.io.sumAaQ16 := nextSumAa
  estimator.io.sumAbQ16 := nextSumAb
  estimator.io.sampleCount := nextSampleCount

  io.input.ready := !resultValid
  io.output.valid := resultValid
  io.output.bits := result

  when(io.output.fire) {
    resultValid := false.B
  }

  when(io.input.fire) {
    when(io.input.bits.last) {
      result.sumAaQ16 := nextSumAa
      result.sumAbQ16 := nextSumAb
      result.sampleCount := nextSampleCount
      result.multiplier := estimator.io.multiplier
      resultValid := true.B
      sumAa := 0.U
      sumAb := 0.S
      sampleCount := 0.U
    }.otherwise {
      sumAa := nextSumAa
      sumAb := nextSumAb
      sampleCount := nextSampleCount
    }
  }
}

/** Streams DCT coefficient pairs through CFL weighting, accumulation, and fit.
  *
  * This is the current closest RTL boundary to libjxl-tiny's per-tile
  * `compute_chroma_from_luma` arithmetic before tile traversal exists. Feed all
  * coefficient pairs for one X or B tile with `useBWeights` fixed for that tile
  * and assert `last` on the final coefficient.
  */
class CflCoefficientSumAccumulator(
    coefficientBits: Int = 32,
    weightedBits: Int = 48,
    sumBits: Int = 64,
    countBits: Int = 16
) extends Module {
  require(coefficientBits > 0, "coefficientBits must be positive")
  require(
    weightedBits >= coefficientBits + 32 - 16,
    "weightedBits must fit weighted Q16 coefficients"
  )
  require(sumBits > weightedBits, "sumBits must leave room for accumulated products")
  require(countBits > 0, "countBits must be positive")

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new CflCoefficientSample(coefficientBits)))
    val output = Decoupled(new CflWeightedSums(sumBits, countBits))
  })

  val weight = Module(
    new CflCoefficientSampleWeight(
      coefficientBits = coefficientBits,
      outputBits = weightedBits
    )
  )
  val accumulator = Module(
    new CflWeightedSumAccumulator(
      valueBits = weightedBits,
      sumBits = sumBits,
      countBits = countBits
    )
  )

  weight.io.coefficientIndex := io.input.bits.coefficientIndex
  weight.io.modelCoeffQ16 := io.input.bits.modelCoeffQ16
  weight.io.signalCoeffQ16 := io.input.bits.signalCoeffQ16
  weight.io.useBWeights := io.input.bits.useBWeights
  weight.io.last := io.input.bits.last

  accumulator.io.input.valid := io.input.valid
  accumulator.io.input.bits := weight.io.output
  io.input.ready := accumulator.io.input.ready
  io.output <> accumulator.io.output
}

/** Estimates both tile CFL multipliers from a single Y/X/B coefficient stream.
  *
  * Feed all 8x8 DCT coefficient triples for one 64x64 tile, with `last` set on
  * the final coefficient. The `x.multiplier` result is the Y-to-X CFL value and
  * `b.multiplier` is the Y-to-B value.
  */
class CflTileCoefficientEstimator(
    coefficientBits: Int = 32,
    weightedBits: Int = 48,
    sumBits: Int = 64,
    countBits: Int = 16
) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new CflTileCoefficientSample(coefficientBits)))
    val output = Decoupled(new CflTileMultipliers(sumBits, countBits))
  })

  val xAccumulator = Module(
    new CflCoefficientSumAccumulator(
      coefficientBits = coefficientBits,
      weightedBits = weightedBits,
      sumBits = sumBits,
      countBits = countBits
    )
  )
  val bAccumulator = Module(
    new CflCoefficientSumAccumulator(
      coefficientBits = coefficientBits,
      weightedBits = weightedBits,
      sumBits = sumBits,
      countBits = countBits
    )
  )

  xAccumulator.io.input.valid := io.input.valid && bAccumulator.io.input.ready
  xAccumulator.io.input.bits.coefficientIndex := io.input.bits.coefficientIndex
  xAccumulator.io.input.bits.modelCoeffQ16 := io.input.bits.yCoeffQ16
  xAccumulator.io.input.bits.signalCoeffQ16 := io.input.bits.xCoeffQ16
  xAccumulator.io.input.bits.useBWeights := false.B
  xAccumulator.io.input.bits.last := io.input.bits.last

  bAccumulator.io.input.valid := io.input.valid && xAccumulator.io.input.ready
  bAccumulator.io.input.bits.coefficientIndex := io.input.bits.coefficientIndex
  bAccumulator.io.input.bits.modelCoeffQ16 := io.input.bits.yCoeffQ16
  bAccumulator.io.input.bits.signalCoeffQ16 := io.input.bits.bCoeffQ16
  bAccumulator.io.input.bits.useBWeights := true.B
  bAccumulator.io.input.bits.last := io.input.bits.last

  io.input.ready := xAccumulator.io.input.ready && bAccumulator.io.input.ready
  io.output.valid := xAccumulator.io.output.valid && bAccumulator.io.output.valid
  io.output.bits.x := xAccumulator.io.output.bits
  io.output.bits.b := bAccumulator.io.output.bits
  xAccumulator.io.output.ready := io.output.ready && bAccumulator.io.output.valid
  bAccumulator.io.output.ready := io.output.ready && xAccumulator.io.output.valid
}

/** Emits Y-to-X and Y-to-B CFL map trace rows from one tile coefficient stream.
  *
  * This wrapper bridges the tile arithmetic boundary into the project's
  * `StageTrace` format. It emits `TraceStage.YtoxMap` first, then
  * `TraceStage.YtobMap`, both indexed by the input tile ordinal.
  */
class CflTileCoefficientTraceStage(
    c: HjxlConfig = HjxlConfig(),
    coefficientBits: Int = 32,
    weightedBits: Int = 48,
    sumBits: Int = 64,
    countBits: Int = 16
) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new CflTileCoefficientTraceInput(coefficientBits)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
  })

  val estimator = Module(
    new CflTileCoefficientEstimator(
      coefficientBits = coefficientBits,
      weightedBits = weightedBits,
      sumBits = sumBits,
      countBits = countBits
    )
  )

  val tileIndex = RegInit(0.U(32.W))
  val emitYtob = RegInit(false.B)

  estimator.io.input.valid := io.input.valid
  estimator.io.input.bits := io.input.bits.coefficient
  io.input.ready := estimator.io.input.ready

  when(io.input.fire && io.input.bits.coefficient.last) {
    tileIndex := io.input.bits.tileIndex
  }

  estimator.io.output.ready := io.trace.fire && emitYtob
  io.trace.valid := estimator.io.output.valid
  io.trace.bits.stage := Mux(emitYtob, TraceStage.YtobMap.U, TraceStage.YtoxMap.U)
  io.trace.bits.group := 0.U
  io.trace.bits.index := tileIndex
  io.trace.bits.value := Mux(
    emitYtob,
    estimator.io.output.bits.b.multiplier,
    estimator.io.output.bits.x.multiplier
  ).pad(c.traceValueBits)
  io.traceLast := io.trace.valid && emitYtob
  io.busy := estimator.io.output.valid || emitYtob

  when(io.trace.fire) {
    emitYtob := !emitYtob
  }
}
