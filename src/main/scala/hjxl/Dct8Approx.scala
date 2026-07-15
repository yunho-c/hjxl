// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

object Dct8Approx {
  val FractionBits = 12
  private val Scale = 1 << FractionBits
  private val Sqrt2Value = 1.41421356237
  private val Wc8Values = Seq(
    0.5097955791041592,
    0.6013448869350453,
    0.8999762231364156,
    2.5629154477415055
  )
  private val Wc4Values = Seq(0.541196100146197, 1.3065629648763764)

  private def scaled(value: Double, fractionBits: Int): Int = {
    require(fractionBits > 0 && fractionBits < 29, "DCT coefficient precision must fit Int")
    math.round(value * (1 << fractionBits)).toInt
  }

  val Sqrt2: Int = scaled(Sqrt2Value, FractionBits)
  val Wc8: Seq[Int] = Wc8Values.map(scaled(_, FractionBits))
  val Wc4: Seq[Int] = Wc4Values.map(scaled(_, FractionBits))

  private[hjxl] def sqrt2(fractionBits: Int): Int = scaled(Sqrt2Value, fractionBits)
  private def wc8(fractionBits: Int): Seq[Int] = Wc8Values.map(scaled(_, fractionBits))
  private def wc4(fractionBits: Int): Seq[Int] = Wc4Values.map(scaled(_, fractionBits))

  def fit(value: SInt, width: Int): SInt = value.asSInt.pad(width)

  def mulQ(
      value: SInt,
      coefficient: Int,
      coefficientFractionBits: Int = FractionBits
  ): SInt =
    ((value * coefficient.S) >> coefficientFractionBits).asSInt

  def dct2(values: Seq[SInt], width: Int): Seq[SInt] =
    Seq(values(0) + values(1), values(0) - values(1)).map(fit(_, width))

  def dct4(
      values: Seq[SInt],
      width: Int,
      coefficientFractionBits: Int = FractionBits
  ): Seq[SInt] = {
    val coefficients = wc4(coefficientFractionBits)
    val evenIn = Seq(values(0) + values(3), values(1) + values(2)).map(fit(_, width))
    val oddIn = Seq(
      mulQ(values(0) - values(3), coefficients(0), coefficientFractionBits),
      mulQ(values(1) - values(2), coefficients(1), coefficientFractionBits)
    ).map(fit(_, width))
    val even = dct2(evenIn, width)
    val oddTmp = dct2(oddIn, width).toArray
    oddTmp(0) = fit(
      mulQ(oddTmp(0), sqrt2(coefficientFractionBits), coefficientFractionBits) + oddTmp(1),
      width
    )
    Seq(even(0), oddTmp(0), even(1), oddTmp(1)).map(fit(_, width))
  }

  def dct8(
      values: Seq[SInt],
      width: Int,
      coefficientFractionBits: Int = FractionBits
  ): Seq[SInt] = {
    val coefficients = wc8(coefficientFractionBits)
    val evenIn = (0 until 4).map(i => fit(values(i) + values(7 - i), width))
    val oddIn = (0 until 4).map(i =>
      fit(
        mulQ(values(i) - values(7 - i), coefficients(i), coefficientFractionBits),
        width
      )
    )
    val even = dct4(evenIn, width, coefficientFractionBits)
    val oddTmp = dct4(oddIn, width, coefficientFractionBits).toArray
    oddTmp(0) = fit(
      mulQ(oddTmp(0), sqrt2(coefficientFractionBits), coefficientFractionBits) + oddTmp(1),
      width
    )
    for (i <- 1 until 3) {
      oddTmp(i) = fit(oddTmp(i) + oddTmp(i + 1), width)
    }
    Seq(even(0), oddTmp(0), even(1), oddTmp(1), even(2), oddTmp(2), even(3), oddTmp(3))
      .map(fit(_, width))
  }
}

/** Fixed-point 1D DCT-8 primitive matching libjxl-tiny's scaled DCT structure.
  *
  * Inputs and outputs retain the caller's scale; transform constants use
  * `coefficientFractionBits`. This module is not a full block transform: the
  * 8x8 stage uses it first over columns with an additional 1/8 scale, then over
  * rows according to libjxl-tiny's layout rules.
  */
class Dct8Approx(
    c: HjxlConfig = HjxlConfig(),
    coefficientFractionBits: Int = Dct8Approx.FractionBits
) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(Vec(8, SInt(c.traceValueBits.W))))
    val output = Decoupled(Vec(8, SInt(c.traceValueBits.W)))
  })

  val computed = Dct8Approx.dct8(io.input.bits, c.traceValueBits, coefficientFractionBits)

  io.input.ready := io.output.ready
  io.output.valid := io.input.valid
  for (i <- 0 until 8) {
    io.output.bits(i) := computed(i)
  }
}
