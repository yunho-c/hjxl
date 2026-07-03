// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

object RgbToXybApprox {
  val InputFractionBits = 8
  val OutputFractionBits = 12
  val CoefficientFractionBits = 12
  val LutInputFractionBits = 10
  val CbrtTableFractionBits = InputFractionBits
  val CbrtTableInputMax = 511
  val LutInterpolationBits = LutInputFractionBits - CbrtTableFractionBits
  val LutInputMax = (CbrtTableInputMax << LutInterpolationBits) | ((1 << LutInterpolationBits) - 1)
  val LutInputBits = log2Ceil(LutInputMax + 1)

  private val coefficientScale = 1 << CoefficientFractionBits
  private val lutInputScale = 1 << LutInputFractionBits
  private val cbrtTableInputScale = 1 << CbrtTableFractionBits
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
  val BiasQLut: Int = math.round(0.0037930732552754493 * lutInputScale).toInt
  val NegBiasQ12: Int = math.round(-0.15595420054 * outputScale).toInt

  val CbrtPlusBiasTable: Seq[Int] =
    (0 to CbrtTableInputMax).map { i =>
      math.round((math.cbrt(i.toDouble / cbrtTableInputScale) - 0.15595420054) * outputScale).toInt
    }

  def cbrtPlusBiasFromLutIndex(index: Int): Int = {
    val clamped = math.max(0, math.min(LutInputMax, index))
    val base = clamped >> LutInterpolationBits
    val fraction = clamped & ((1 << LutInterpolationBits) - 1)
    val next = math.min(CbrtTableInputMax, base + 1)
    val scale = 1 << LutInterpolationBits
    (CbrtPlusBiasTable(base) * (scale - fraction) + CbrtPlusBiasTable(next) * fraction + scale / 2) >>
      LutInterpolationBits
  }
}

/** Approximate libjxl-tiny RGB-to-XYB for Q8 linear RGB samples.
  *
  * Inputs are signed Q8 linear RGB samples. Negative mixed absorbance values are
  * clamped to zero, matching libjxl-tiny before cube-rooting. Outputs are signed
  * Q12 XYB samples. This is a traceable hardware approximation; tests compare
  * against the Python formula with fixed tolerances.
  */
class RgbToXybApprox(c: HjxlConfig = HjxlConfig()) extends Module {
  import RgbToXybApprox._

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val output = Decoupled(new XybPixel(c))
  })

  private def clampInput(value: SInt): UInt = {
    Mux(value < 0.S, 0.U, value.asUInt)
  }

  private def mixedLutIndex(r: UInt, g: UInt, b: UInt, m0: Int, m1: Int, m2: Int): UInt = {
    val shift = CoefficientFractionBits + InputFractionBits - LutInputFractionBits
    val sum = r * m0.U + g * m1.U + b * m2.U
    val rounded = (sum + (1 << (shift - 1)).U) >> shift
    val biased = rounded + BiasQLut.U
    Mux(biased > LutInputMax.U, LutInputMax.U, biased)(LutInputBits - 1, 0)
  }

  private def cbrtLut(index: UInt): SInt = {
    val table = VecInit(CbrtPlusBiasTable.map(_.S(c.traceValueBits.W)))
    val base = index >> LutInterpolationBits
    val fraction = index(LutInterpolationBits - 1, 0)
    val next = Mux(base === CbrtTableInputMax.U, base, base + 1.U)
    val low = table(base)
    val high = table(next)
    val delta = high - low
    MuxLookup(fraction, low)(
      Seq(
        0.U -> low,
        1.U -> (low + ((delta + 2.S) >> 2)).asSInt,
        2.U -> (low + ((delta + 1.S) >> 1)).asSInt,
        3.U -> (high - ((delta + 2.S) >> 2)).asSInt
      )
    )
  }

  val r = clampInput(io.input.bits.r)
  val g = clampInput(io.input.bits.g)
  val b = clampInput(io.input.bits.b)

  val tm0 = cbrtLut(mixedLutIndex(r, g, b, M00, M01, M02))
  val tm1 = cbrtLut(mixedLutIndex(r, g, b, M10, M11, M12))
  val tm2 = cbrtLut(mixedLutIndex(r, g, b, M20, M21, M22))

  io.input.ready := io.output.ready
  io.output.valid := io.input.valid
  io.output.bits.x := io.input.bits.x
  io.output.bits.y := io.input.bits.y
  io.output.bits.xybX := (tm0 - tm1) >> 1
  io.output.bits.xybY := (tm0 + tm1) >> 1
  io.output.bits.xybB := tm2
}
