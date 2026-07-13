// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

object Dct16Approx {
  val Wc16: Seq[Int] = Seq(
    0.5024192861881557,
    0.5224986149396889,
    0.5669440348163577,
    0.6468217833599901,
    0.7881546234512502,
    1.060677685990347,
    1.7224470982383342,
    5.101148618689155
  ).map(value => math.round(value * (1 << Dct8Approx.FractionBits)).toInt)

  /** Recursive 16-point kernel used by libjxl-tiny's scaled rectangular DCTs. */
  def dct16(values: Seq[SInt], width: Int): Seq[SInt] = {
    require(values.length == 16, "DCT-16 requires exactly 16 samples")

    val evenIn = (0 until 8).map(i => Dct8Approx.fit(values(i) + values(15 - i), width))
    val oddIn = (0 until 8).map { i =>
      Dct8Approx.fit(Dct8Approx.mulQ(values(i) - values(15 - i), Wc16(i)), width)
    }
    val even = Dct8Approx.dct8(evenIn, width)
    val odd = Dct8Approx.dct8(oddIn, width).toArray
    odd(0) = Dct8Approx.fit(Dct8Approx.mulQ(odd(0), Dct8Approx.Sqrt2) + odd(1), width)
    for (i <- 1 until 7) {
      odd(i) = Dct8Approx.fit(odd(i) + odd(i + 1), width)
    }

    (0 until 8).flatMap(i => Seq(even(i), odd(i))).map(Dct8Approx.fit(_, width))
  }
}

/** Fixed-point Q12 1D DCT-16 primitive matching libjxl-tiny's recursive
  * transform structure.
  */
class Dct16Approx(c: HjxlConfig = HjxlConfig()) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(Vec(16, SInt(c.traceValueBits.W))))
    val output = Decoupled(Vec(16, SInt(c.traceValueBits.W)))
  })

  val computed = Dct16Approx.dct16(io.input.bits, c.traceValueBits)

  io.input.ready := io.output.ready
  io.output.valid := io.input.valid
  for (i <- 0 until 16) {
    io.output.bits(i) := computed(i)
  }
}
