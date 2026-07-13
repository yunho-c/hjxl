// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

object RgbToXybApprox {
  val InputFractionBits = 8
  val OutputFractionBits = 12
  val CoefficientFractionBits = 26
  val AbsorbanceFractionBits = 24
  val CbrtLutFractionBits = 5
  val CbrtInterpolationBits = AbsorbanceFractionBits - CbrtLutFractionBits
  val CbrtTableMin = 1 << CbrtLutFractionBits
  val CbrtTableMax = 8 << CbrtLutFractionBits
  val CbrtTableBits = 14
  val NormalizedBits = AbsorbanceFractionBits + 3
  val CbrtMinExponent = -8
  val CbrtMaxExponent = 2
  val CbrtExponents: Seq[Int] = CbrtMinExponent to CbrtMaxExponent
  val CbrtScaleThresholds: Seq[BigInt] =
    CbrtExponents.dropRight(1).map { exponent =>
      BigInt(1) << (AbsorbanceFractionBits + 3 * (exponent + 1))
    }

  private val coefficientScale = 1 << CoefficientFractionBits
  private val absorbanceScale = 1 << AbsorbanceFractionBits
  private val outputScale = 1 << OutputFractionBits

  val M00: Int = math.round(0.30 * coefficientScale).toInt
  val M02: Int = math.round(0.078 * coefficientScale).toInt
  val M01: Int = coefficientScale - M00 - M02
  val M10: Int = math.round(0.23 * coefficientScale).toInt
  val M12: Int = math.round(0.078 * coefficientScale).toInt
  val M11: Int = coefficientScale - M10 - M12
  val M20: Int = math.round(0.24342268924547819 * coefficientScale).toInt
  val M21: Int = math.round(0.20476744424496821 * coefficientScale).toInt
  val M22: Int = coefficientScale - M20 - M21
  val BiasQAbsorbance: Int = math.round(0.0037930732552754493 * absorbanceScale).toInt
  val NegBiasQ12: Int = math.round(-0.15595420054 * outputScale).toInt

  val NormalizedCbrtTable: Seq[Int] =
    (CbrtTableMin to CbrtTableMax).map { index =>
      math.round(math.cbrt(index.toDouble / (1 << CbrtLutFractionBits)) * outputScale).toInt
    }

  private def roundShift(value: BigInt, shift: Int): BigInt =
    (value + (BigInt(1) << (shift - 1))) >> shift

  private def mixedAbsorbanceQ24(
      rQ8: Int,
      gQ8: Int,
      bQ8: Int,
      m0: Int,
      m1: Int,
      m2: Int
  ): BigInt = {
    val product = BigInt(rQ8) * m0 + BigInt(gQ8) * m1 + BigInt(bQ8) * m2
    val shift = CoefficientFractionBits + InputFractionBits - AbsorbanceFractionBits
    (roundShift(product, shift) + BiasQAbsorbance).max(BigInt(0))
  }

  /** Pure-Scala model of the normalized cube-root datapath. */
  def cbrtPlusBiasQ12(absorbanceQ24: BigInt): Int = {
    if (absorbanceQ24 <= 0) {
      NegBiasQ12
    } else {
      val scaleCode = CbrtScaleThresholds.indexWhere(absorbanceQ24 < _)
      val exponent =
        if (scaleCode < 0) CbrtMaxExponent else CbrtExponents(scaleCode)
      val normalized =
        if (exponent < 0) absorbanceQ24 << (-3 * exponent)
        else absorbanceQ24 >> (3 * exponent)
      val index = (normalized >> CbrtInterpolationBits).toInt
      val fraction = (normalized & ((BigInt(1) << CbrtInterpolationBits) - 1)).toInt
      val base = math.max(CbrtTableMin, math.min(CbrtTableMax - 1, index)) - CbrtTableMin
      val denominator = BigInt(1) << CbrtInterpolationBits
      val interpolated =
        ((BigInt(NormalizedCbrtTable(base)) * (denominator - fraction) +
          BigInt(NormalizedCbrtTable(base + 1)) * fraction + denominator / 2) >>
          CbrtInterpolationBits).toInt
      val scaled =
        if (exponent < 0) (interpolated + (1 << (-exponent - 1))) >> -exponent
        else interpolated << exponent
      scaled + NegBiasQ12
    }
  }

  /** Pure-Scala bit-accurate model used by downstream fixed-path tests. */
  def rgbToXybQ12(rQ8: Int, gQ8: Int, bQ8: Int): (Int, Int, Int) = {
    val tm0 = cbrtPlusBiasQ12(mixedAbsorbanceQ24(rQ8, gQ8, bQ8, M00, M01, M02))
    val tm1 = cbrtPlusBiasQ12(mixedAbsorbanceQ24(rQ8, gQ8, bQ8, M10, M11, M12))
    val tm2 = cbrtPlusBiasQ12(mixedAbsorbanceQ24(rQ8, gQ8, bQ8, M20, M21, M22))
    ((tm0 - tm1) >> 1, (tm0 + tm1) >> 1, tm2)
  }
}

/** Range-normalized unsigned Q24 cube root with signed Q12 output. */
class CbrtApproxQ12(inputBits: Int, outputBits: Int) extends Module {
  import RgbToXybApprox._

  require(inputBits >= NormalizedBits, "cube-root input must contain the normalized Q24 range")
  require(outputBits >= 18, "cube-root output must contain the signed Q12 range")

  val io = IO(new Bundle {
    val input = Input(UInt(inputBits.W))
    val output = Output(SInt(outputBits.W))
  })

  val safeInput = Mux(io.input === 0.U, 1.U, io.input)
  val scaleCode = MuxCase(
    (CbrtExponents.length - 1).U(4.W),
    CbrtScaleThresholds.zipWithIndex.map { case (threshold, code) =>
      (safeInput < threshold.U(inputBits.W)) -> code.U
    }
  )
  val normalizedWide = MuxLookup(scaleCode, safeInput >> (3 * CbrtMaxExponent))(
    CbrtExponents.zipWithIndex.map { case (exponent, code) =>
      code.U -> {
        if (exponent < 0) safeInput << (-3 * exponent)
        else safeInput >> (3 * exponent)
      }
    }
  )
  val normalized = normalizedWide(NormalizedBits - 1, 0)
  val tableIndex = normalized >> CbrtInterpolationBits
  val tableOffset = tableIndex - CbrtTableMin.U
  val fraction = normalized(CbrtInterpolationBits - 1, 0)
  val table = VecInit(NormalizedCbrtTable.map(_.U(CbrtTableBits.W)))
  val low = table(tableOffset)
  val high = table(tableOffset + 1.U)
  val interpolationScale = BigInt(1) << CbrtInterpolationBits
  val interpolatedWide =
    low * (interpolationScale.U - fraction) +&
      high * fraction + (interpolationScale / 2).U
  val normalizedRoot = (interpolatedWide >> CbrtInterpolationBits)(CbrtTableBits - 1, 0)

  val scaledRoot = Wire(UInt(16.W))
  scaledRoot := MuxLookup(scaleCode, normalizedRoot << CbrtMaxExponent)(
    CbrtExponents.zipWithIndex.map { case (exponent, code) =>
      code.U -> {
        if (exponent < 0) {
          (normalizedRoot + (BigInt(1) << (-exponent - 1)).U) >> -exponent
        } else {
          normalizedRoot << exponent
        }
      }
    }
  )
  val withBias = scaledRoot.zext +& NegBiasQ12.S
  io.output := Mux(io.input === 0.U, NegBiasQ12.S(outputBits.W), withBias.pad(outputBits))
}

/** Approximate libjxl-tiny RGB-to-XYB for signed Q8 linear RGB samples.
  *
  * The signed matrix multiply retains Q24 absorbance precision and clamps only
  * after adding the opsin bias, matching the reference ordering. A normalized
  * cube-root table covers the full positive signed-16/Q8 input range without
  * the former high-value saturation. Outputs are signed Q12 XYB samples.
  */
class RgbToXybApprox(c: HjxlConfig = HjxlConfig()) extends Module {
  import RgbToXybApprox._

  private val coefficientBits = CoefficientFractionBits + 1
  private val absorbanceBits = c.pixelBits + AbsorbanceFractionBits - InputFractionBits + 1
  private val mixedShift = CoefficientFractionBits + InputFractionBits - AbsorbanceFractionBits

  require(c.pixelBits > InputFractionBits, "RGB input must contain an integer bit")
  require(mixedShift > 0, "mixed absorbance must be right-shifted into Q24")

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val output = Decoupled(new XybPixel(c))
  })

  private def mixedAbsorbance(m0: Int, m1: Int, m2: Int): UInt = {
    val product0 = io.input.bits.r * m0.S(coefficientBits.W)
    val product1 = io.input.bits.g * m1.S(coefficientBits.W)
    val product2 = io.input.bits.b * m2.S(coefficientBits.W)
    val sum = product0 +& product1 +& product2
    val rounded = (sum +& (BigInt(1) << (mixedShift - 1)).S) >> mixedShift
    val biased = rounded +& BiasQAbsorbance.S
    val maxAbsorbance = ((BigInt(1) << absorbanceBits) - 1).U
    Mux(
      biased <= 0.S,
      0.U(absorbanceBits.W),
      Mux(biased.asUInt > maxAbsorbance, maxAbsorbance, biased.asUInt(absorbanceBits - 1, 0))
    )
  }

  val cbrt0 = Module(new CbrtApproxQ12(absorbanceBits, c.traceValueBits))
  val cbrt1 = Module(new CbrtApproxQ12(absorbanceBits, c.traceValueBits))
  val cbrt2 = Module(new CbrtApproxQ12(absorbanceBits, c.traceValueBits))
  cbrt0.io.input := mixedAbsorbance(M00, M01, M02)
  cbrt1.io.input := mixedAbsorbance(M10, M11, M12)
  cbrt2.io.input := mixedAbsorbance(M20, M21, M22)

  io.input.ready := io.output.ready
  io.output.valid := io.input.valid
  io.output.bits.x := io.input.bits.x
  io.output.bits.y := io.input.bits.y
  io.output.bits.xybX := (cbrt0.io.output - cbrt1.io.output) >> 1
  io.output.bits.xybY := (cbrt0.io.output +& cbrt1.io.output) >> 1
  io.output.bits.xybB := cbrt2.io.output
}
