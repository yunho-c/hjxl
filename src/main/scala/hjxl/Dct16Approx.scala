// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

object Dct16Approx {
  private val Wc16Values = Seq(
    0.5024192861881557,
    0.5224986149396889,
    0.5669440348163577,
    0.6468217833599901,
    0.7881546234512502,
    1.060677685990347,
    1.7224470982383342,
    5.101148618689155
  )
  val Wc16: Seq[Int] = Wc16Values.map(value =>
    math.round(value * (1 << Dct8Approx.FractionBits)).toInt
  )

  private def wc16(fractionBits: Int): Seq[Int] = Wc16Values.map(value =>
    math.round(value * (1 << fractionBits)).toInt
  )

  /** Recursive 16-point kernel used by libjxl-tiny's scaled rectangular DCTs. */
  def dct16(
      values: Seq[SInt],
      width: Int,
      coefficientFractionBits: Int = Dct8Approx.FractionBits
  ): Seq[SInt] = {
    require(values.length == 16, "DCT-16 requires exactly 16 samples")
    require(
      coefficientFractionBits > 0 && coefficientFractionBits < 29,
      "DCT-16 coefficient precision must fit Int"
    )

    val coefficients = wc16(coefficientFractionBits)
    val evenIn = (0 until 8).map(i => Dct8Approx.fit(values(i) + values(15 - i), width))
    val oddIn = (0 until 8).map { i =>
      Dct8Approx.fit(
        Dct8Approx.mulQ(
          values(i) - values(15 - i),
          coefficients(i),
          coefficientFractionBits
        ),
        width
      )
    }
    val even = Dct8Approx.dct8(evenIn, width, coefficientFractionBits)
    val odd = Dct8Approx.dct8(oddIn, width, coefficientFractionBits).toArray
    odd(0) = Dct8Approx.fit(
      Dct8Approx.mulQ(
        odd(0),
        Dct8Approx.sqrt2(coefficientFractionBits),
        coefficientFractionBits
      ) + odd(1),
      width
    )
    for (i <- 1 until 7) {
      odd(i) = Dct8Approx.fit(odd(i) + odd(i + 1), width)
    }

    (0 until 8).flatMap(i => Seq(even(i), odd(i))).map(Dct8Approx.fit(_, width))
  }
}

/** Fixed-point 1D DCT-16 primitive matching libjxl-tiny's recursive transform
  * structure at the caller-selected coefficient precision.
  */
class Dct16Approx(
    c: HjxlConfig = HjxlConfig(),
    coefficientFractionBits: Int = Dct8Approx.FractionBits
) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(Vec(16, SInt(c.traceValueBits.W))))
    val output = Decoupled(Vec(16, SInt(c.traceValueBits.W)))
  })

  val computed = Dct16Approx.dct16(io.input.bits, c.traceValueBits, coefficientFractionBits)

  io.input.ready := io.output.ready
  io.output.valid := io.input.valid
  for (i <- 0 until 16) {
    io.output.bits(i) := computed(i)
  }
}
