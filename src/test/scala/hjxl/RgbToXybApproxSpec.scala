// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class RgbToXybApproxSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val outputScale = 1 << RgbToXybApprox.OutputFractionBits

  private def reference(rQ8: Int, gQ8: Int, bQ8: Int): (Int, Int, Int) = {
    def source(v: Int): Double = math.max(v, 0).toDouble / (1 << RgbToXybApprox.InputFractionBits)
    val r = source(rQ8)
    val g = source(gQ8)
    val b = source(bQ8)
    val bias = 0.0037930732552754493
    val negBias = -0.15595420054
    def cbrt(v: Double): Double = math.cbrt(math.max(v, 0.0)) + negBias
    val mixed0 = 0.30 * r + (1.0 - 0.30 - 0.078) * g + 0.078 * b + bias
    val mixed1 = 0.23 * r + (1.0 - 0.23 - 0.078) * g + 0.078 * b + bias
    val mixed2 = 0.24342268924547819 * r + 0.20476744424496821 * g +
      (1.0 - 0.24342268924547819 - 0.20476744424496821) * b + bias
    val tm0 = cbrt(mixed0)
    val tm1 = cbrt(mixed1)
    val tm2 = cbrt(mixed2)
    (
      math.round(0.5 * (tm0 - tm1) * outputScale).toInt,
      math.round(0.5 * (tm0 + tm1) * outputScale).toInt,
      math.round(tm2 * outputScale).toInt
    )
  }

  private def expectClose(actual: BigInt, expected: Int, tolerance: Int): Unit = {
    math.abs(actual.toInt - expected) must be <= tolerance
  }

  "RgbToXybApprox converts Q8 linear RGB to approximate Q12 XYB" in {
    simulate(new RgbToXybApprox()) { dut =>
      val vectors = Seq(
        (0, 0, 0),
        (64, 128, 192),
        (256, 256, 256),
        (-16, 32, 128)
      )
      dut.io.output.ready.poke(true.B)

      for (((r, g, b), index) <- vectors.zipWithIndex) {
        val expected = reference(r, g, b)
        dut.io.input.valid.poke(true.B)
        dut.io.input.bits.x.poke(index.U)
        dut.io.input.bits.y.poke((index + 1).U)
        dut.io.input.bits.r.poke(r.S)
        dut.io.input.bits.g.poke(g.S)
        dut.io.input.bits.b.poke(b.S)
        dut.io.output.valid.expect(true.B)
        dut.io.output.bits.x.expect(index.U)
        dut.io.output.bits.y.expect((index + 1).U)
        expectClose(dut.io.output.bits.xybX.peekValue().asBigInt, expected._1, 6)
        expectClose(dut.io.output.bits.xybY.peekValue().asBigInt, expected._2, 6)
        expectClose(dut.io.output.bits.xybB.peekValue().asBigInt, expected._3, 6)
        dut.clock.step()
      }
    }
  }
}
