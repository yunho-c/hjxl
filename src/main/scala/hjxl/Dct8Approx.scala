// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

object Dct8Approx {
  val FractionBits = 12
  private val Scale = 1 << FractionBits

  val Sqrt2: Int = math.round(1.41421356237 * Scale).toInt
  val Wc8: Seq[Int] = Seq(
    0.5097955791041592,
    0.6013448869350453,
    0.8999762231364156,
    2.5629154477415055
  ).map(v => math.round(v * Scale).toInt)
  val Wc4: Seq[Int] = Seq(
    0.541196100146197,
    1.3065629648763764
  ).map(v => math.round(v * Scale).toInt)

  def fit(value: SInt, width: Int): SInt = value.asSInt.pad(width)

  def mulQ(value: SInt, coefficient: Int): SInt =
    ((value * coefficient.S) >> FractionBits).asSInt

  def dct2(values: Seq[SInt], width: Int): Seq[SInt] =
    Seq(values(0) + values(1), values(0) - values(1)).map(fit(_, width))

  def dct4(values: Seq[SInt], width: Int): Seq[SInt] = {
    val evenIn = Seq(values(0) + values(3), values(1) + values(2)).map(fit(_, width))
    val oddIn = Seq(
      mulQ(values(0) - values(3), Wc4(0)),
      mulQ(values(1) - values(2), Wc4(1))
    ).map(fit(_, width))
    val even = dct2(evenIn, width)
    val oddTmp = dct2(oddIn, width).toArray
    oddTmp(0) = fit(mulQ(oddTmp(0), Sqrt2) + oddTmp(1), width)
    Seq(even(0), oddTmp(0), even(1), oddTmp(1)).map(fit(_, width))
  }

  def dct8(values: Seq[SInt], width: Int): Seq[SInt] = {
    val evenIn = (0 until 4).map(i => fit(values(i) + values(7 - i), width))
    val oddIn = (0 until 4).map(i => fit(mulQ(values(i) - values(7 - i), Wc8(i)), width))
    val even = dct4(evenIn, width)
    val oddTmp = dct4(oddIn, width).toArray
    oddTmp(0) = fit(mulQ(oddTmp(0), Sqrt2) + oddTmp(1), width)
    for (i <- 1 until 3) {
      oddTmp(i) = fit(oddTmp(i) + oddTmp(i + 1), width)
    }
    Seq(even(0), oddTmp(0), even(1), oddTmp(1), even(2), oddTmp(2), even(3), oddTmp(3))
      .map(fit(_, width))
  }
}

/** Fixed-point 1D DCT-8 primitive matching libjxl-tiny's scaled DCT structure.
  *
  * Inputs and outputs use Q12. This module is not a full block transform: the
  * 8x8 stage will use it first over columns with an additional 1/8 scale, then
  * over rows/columns according to libjxl-tiny's layout rules.
  */
class Dct8Approx(c: HjxlConfig = HjxlConfig()) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(Vec(8, SInt(c.traceValueBits.W))))
    val output = Decoupled(Vec(8, SInt(c.traceValueBits.W)))
  })

  val computed = Dct8Approx.dct8(io.input.bits, c.traceValueBits)

  io.input.ready := io.output.ready
  io.output.valid := io.input.valid
  for (i <- 0 until 8) {
    io.output.bits(i) := computed(i)
  }
}
