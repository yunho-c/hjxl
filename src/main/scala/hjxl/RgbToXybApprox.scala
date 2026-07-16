// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._
import scala.collection.concurrent.TrieMap

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

  def negBias(outputFractionBits: Int): Int =
    math.round(-0.15595420054 * (1 << outputFractionBits)).toInt

  private val normalizedCbrtTableCache = TrieMap.empty[Int, Seq[Int]]

  def normalizedCbrtTable(outputFractionBits: Int): Seq[Int] =
    normalizedCbrtTableCache.getOrElseUpdate(
      outputFractionBits,
      (CbrtTableMin to CbrtTableMax).map { index =>
        math
          .round(
            math.cbrt(index.toDouble / (1 << CbrtLutFractionBits)) *
              (1 << outputFractionBits)
          )
          .toInt
      }
    )

  val NormalizedCbrtTable: Seq[Int] = normalizedCbrtTable(OutputFractionBits)

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
  def cbrtPlusBias(absorbanceQ24: BigInt, outputFractionBits: Int): Int = {
    require(outputFractionBits >= OutputFractionBits, "XYB output precision cannot be below Q12")
    val table = normalizedCbrtTable(outputFractionBits)
    val negativeBias = negBias(outputFractionBits)
    if (absorbanceQ24 <= 0) {
      negativeBias
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
        ((BigInt(table(base)) * (denominator - fraction) +
          BigInt(table(base + 1)) * fraction + denominator / 2) >>
          CbrtInterpolationBits).toInt
      val scaled =
        if (exponent < 0) (interpolated + (1 << (-exponent - 1))) >> -exponent
        else interpolated << exponent
      scaled + negativeBias
    }
  }


  def cbrtPlusBiasQ12(absorbanceQ24: BigInt): Int =
    cbrtPlusBias(absorbanceQ24, OutputFractionBits)

  /** Pure-Scala bit-accurate model used by downstream fixed-path tests. */
  def rgbToXyb(
      rQ8: Int,
      gQ8: Int,
      bQ8: Int,
      outputFractionBits: Int
  ): (Int, Int, Int) = {
    val tm0 = cbrtPlusBias(
      mixedAbsorbanceQ24(rQ8, gQ8, bQ8, M00, M01, M02),
      outputFractionBits
    )
    val tm1 = cbrtPlusBias(
      mixedAbsorbanceQ24(rQ8, gQ8, bQ8, M10, M11, M12),
      outputFractionBits
    )
    val tm2 = cbrtPlusBias(
      mixedAbsorbanceQ24(rQ8, gQ8, bQ8, M20, M21, M22),
      outputFractionBits
    )
    ((tm0 - tm1) >> 1, (tm0 + tm1) >> 1, tm2)
  }

  def rgbToXybQ12(rQ8: Int, gQ8: Int, bQ8: Int): (Int, Int, Int) =
    rgbToXyb(rQ8, gQ8, bQ8, OutputFractionBits)
}

/** Range-normalized unsigned Q24 cube root with configurable signed output precision. */
class CbrtApprox(
    inputBits: Int,
    outputBits: Int,
    outputFractionBits: Int = RgbToXybApprox.OutputFractionBits,
    includeQ12Output: Boolean = false,
    includeQ18Output: Boolean = false
) extends Module {
  import RgbToXybApprox._

  require(inputBits >= NormalizedBits, "cube-root input must contain the normalized Q24 range")
  require(outputFractionBits >= OutputFractionBits, "cube-root output precision cannot be below Q12")
  require(
    outputBits >= outputFractionBits + 6,
    "cube-root output must contain the scaled signed range"
  )
  require(
    !includeQ12Output || outputFractionBits > OutputFractionBits,
    "the secondary Q12 cube-root output requires a higher-precision primary output"
  )
  require(
    !includeQ18Output || outputFractionBits > 18,
    "the secondary Q18 cube-root output requires a primary output above Q18"
  )

  private val tableBits = outputFractionBits + 2
  private val scaledRootBits = outputFractionBits + 5
  private val tableValues = normalizedCbrtTable(outputFractionBits)
  private val negativeBias = negBias(outputFractionBits)

  val io = IO(new Bundle {
    val input = Input(UInt(inputBits.W))
    val output = Output(SInt(outputBits.W))
    val outputQ12 =
      if (includeQ12Output) Some(Output(SInt(outputBits.W))) else None
    val outputQ18 =
      if (includeQ18Output) Some(Output(SInt(outputBits.W))) else None
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
  val table = VecInit(tableValues.map(_.U(tableBits.W)))
  val low = table(tableOffset)
  val high = table(tableOffset + 1.U)
  val interpolationScale = BigInt(1) << CbrtInterpolationBits
  val interpolatedWide =
    low * (interpolationScale.U - fraction) +&
      high * fraction + (interpolationScale / 2).U
  val normalizedRoot = (interpolatedWide >> CbrtInterpolationBits)(tableBits - 1, 0)

  val scaledRoot = Wire(UInt(scaledRootBits.W))
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
  val withBias = scaledRoot.zext +& negativeBias.S
  io.output := Mux(io.input === 0.U, negativeBias.S(outputBits.W), withBias.pad(outputBits))

  private def secondaryOutput(secondaryFractionBits: Int): SInt = {
    require(
      secondaryFractionBits >= OutputFractionBits && secondaryFractionBits < outputFractionBits,
      "secondary cube-root precision must be below the primary output"
    )
    val shift = outputFractionBits - secondaryFractionBits
    val secondaryTable = normalizedCbrtTable(secondaryFractionBits)
    // Rounding the primary ROM entries reproduces all but a handful of the
    // lower-precision entries. A one-bit correction ROM retains each exact
    // lower-precision contract without duplicating the full cube-root table.
    val corrections = tableValues.zip(secondaryTable).map {
      case (highPrecision, secondary) =>
        ((highPrecision + (1 << (shift - 1))) >> shift) - secondary
    }
    require(
      corrections.forall(correction => correction >= 0 && correction <= 1),
      s"Q$secondaryFractionBits cube-root table correction must fit one bit"
    )
    val correctionTable = VecInit(corrections.map(_.U(1.W)))
    val roundBias = BigInt(1) << (shift - 1)
    val secondaryTableBits = secondaryFractionBits + 2
    val lowSecondary =
      (((low + roundBias.U) >> shift) - correctionTable(tableOffset))(
        secondaryTableBits - 1,
        0
      )
    val highSecondary =
      (((high + roundBias.U) >> shift) - correctionTable(tableOffset + 1.U))(
        secondaryTableBits - 1,
        0
      )
    val interpolatedWideSecondary =
      lowSecondary * (interpolationScale.U - fraction) +&
        highSecondary * fraction + (interpolationScale / 2).U
    val normalizedRootSecondary =
      (interpolatedWideSecondary >> CbrtInterpolationBits)(secondaryTableBits - 1, 0)
    val scaledRootSecondary = Wire(UInt((secondaryFractionBits + 4).W))
    scaledRootSecondary := MuxLookup(
      scaleCode,
      normalizedRootSecondary << CbrtMaxExponent
    )(
      CbrtExponents.zipWithIndex.map { case (exponent, code) =>
        code.U -> {
          if (exponent < 0) {
            (normalizedRootSecondary + (BigInt(1) << (-exponent - 1)).U) >> -exponent
          } else {
            normalizedRootSecondary << exponent
          }
        }
      }
    )
    val secondaryBias = negBias(secondaryFractionBits)
    val withBiasSecondary = scaledRootSecondary.zext +& secondaryBias.S
    Mux(
      io.input === 0.U,
      secondaryBias.S(outputBits.W),
      withBiasSecondary.pad(outputBits)
    )
  }

  io.outputQ12.foreach(_ := secondaryOutput(OutputFractionBits))
  io.outputQ18.foreach(_ := secondaryOutput(18))
}

class CbrtApproxQ12(inputBits: Int, outputBits: Int)
    extends CbrtApprox(inputBits, outputBits, RgbToXybApprox.OutputFractionBits)

/** Approximate libjxl-tiny RGB-to-XYB for signed Q8 linear RGB samples.
  *
  * The signed matrix multiply retains Q24 absorbance precision and clamps only
  * after adding the opsin bias, matching the reference ordering. A normalized
  * cube-root table covers the full positive signed-16/Q8 input range without
  * the former high-value saturation. Outputs default to signed Q12; the live
  * VarDCT route requests a Q21 primary with exact Q18 and Q12 secondary taps.
  */
class RgbToXybApprox(
    c: HjxlConfig = HjxlConfig(),
    outputFractionBits: Int = RgbToXybApprox.OutputFractionBits,
    includeQ12Output: Boolean = false,
    includeQ18Output: Boolean = false
) extends Module {
  import RgbToXybApprox._

  private val coefficientBits = CoefficientFractionBits + 1
  private val absorbanceBits = c.pixelBits + AbsorbanceFractionBits - InputFractionBits + 1
  private val mixedShift = CoefficientFractionBits + InputFractionBits - AbsorbanceFractionBits

  require(c.pixelBits > InputFractionBits, "RGB input must contain an integer bit")
  require(mixedShift > 0, "mixed absorbance must be right-shifted into Q24")
  require(outputFractionBits >= OutputFractionBits, "XYB output precision cannot be below Q12")
  require(
    c.traceValueBits >= outputFractionBits + 6,
    "XYB output precision must fit the configured value width"
  )
  require(
    !includeQ12Output || outputFractionBits > OutputFractionBits,
    "the secondary Q12 XYB output requires a higher-precision primary output"
  )
  require(
    !includeQ18Output || outputFractionBits > 18,
    "the secondary Q18 XYB output requires a primary output above Q18"
  )

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val output = Decoupled(new XybPixel(c))
    val outputQ12 =
      if (includeQ12Output) Some(Output(Valid(new XybPixel(c)))) else None
    val outputQ18 =
      if (includeQ18Output) Some(Output(Valid(new XybPixel(c)))) else None
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

  val cbrt0 = Module(
    new CbrtApprox(
      absorbanceBits,
      c.traceValueBits,
      outputFractionBits,
      includeQ12Output,
      includeQ18Output
    )
  )
  val cbrt1 = Module(
    new CbrtApprox(
      absorbanceBits,
      c.traceValueBits,
      outputFractionBits,
      includeQ12Output,
      includeQ18Output
    )
  )
  val cbrt2 = Module(
    new CbrtApprox(
      absorbanceBits,
      c.traceValueBits,
      outputFractionBits,
      includeQ12Output,
      includeQ18Output
    )
  )
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
  io.outputQ12.foreach { outputQ12 =>
    outputQ12.valid := io.output.fire
    outputQ12.bits.x := io.input.bits.x
    outputQ12.bits.y := io.input.bits.y
    outputQ12.bits.xybX := (cbrt0.io.outputQ12.get - cbrt1.io.outputQ12.get) >> 1
    outputQ12.bits.xybY := (cbrt0.io.outputQ12.get +& cbrt1.io.outputQ12.get) >> 1
    outputQ12.bits.xybB := cbrt2.io.outputQ12.get
  }
  io.outputQ18.foreach { outputQ18 =>
    outputQ18.valid := io.output.fire
    outputQ18.bits.x := io.input.bits.x
    outputQ18.bits.y := io.input.bits.y
    outputQ18.bits.xybX := (cbrt0.io.outputQ18.get - cbrt1.io.outputQ18.get) >> 1
    outputQ18.bits.xybY := (cbrt0.io.outputQ18.get +& cbrt1.io.outputQ18.get) >> 1
    outputQ18.bits.xybB := cbrt2.io.outputQ18.get
  }
}
