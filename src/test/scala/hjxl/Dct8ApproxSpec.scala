// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class Dct8ApproxSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val scale = 1 << Dct8Approx.FractionBits
  private val sqrt2 = 1.41421356237
  private val wc4 = Seq(0.541196100146197, 1.3065629648763764)
  private val wc8 = Seq(0.5097955791041592, 0.6013448869350453, 0.8999762231364156, 2.5629154477415055)

  private def toQ(value: Double): Int = math.round(value * scale).toInt

  private def dct1d(values: Seq[Double]): Seq[Double] = {
    val n = values.length
    if (n == 1) {
      values
    } else if (n == 2) {
      Seq(values(0) + values(1), values(0) - values(1))
    } else {
      val half = n / 2
      val multipliers = if (n == 4) wc4 else wc8
      val tmp = Array.fill(n)(0.0)
      for (i <- 0 until half) {
        tmp(i) = values(i) + values(n - i - 1)
        tmp(half + i) = values(i) - values(n - i - 1)
      }
      val even = dct1d(tmp.take(half).toIndexedSeq)
      val odd = dct1d(tmp.drop(half).zip(multipliers).map { case (value, multiplier) => value * multiplier }.toIndexedSeq)
        .toArray
      odd(0) = odd(0) * sqrt2 + odd(1)
      for (i <- 1 until half - 1) {
        odd(i) = odd(i) + odd(i + 1)
      }
      val out = Array.fill(n)(0.0)
      for (i <- 0 until half) {
        out(2 * i) = even(i)
        out(2 * i + 1) = odd(i)
      }
      out.toSeq
    }
  }

  private def expectVector(dut: Dct8Approx, values: Seq[Int]): Unit = {
    val expected = dct1d(values.map(_.toDouble / scale)).map(toQ)
    dut.io.input.valid.poke(true.B)
    dut.io.output.ready.poke(true.B)
    for (i <- 0 until 8) {
      dut.io.input.bits(i).poke(values(i).S)
    }
    dut.io.output.valid.expect(true.B)
    for (i <- 0 until 8) {
      val actual = dut.io.output.bits(i).peekValue().asBigInt.toInt
      math.abs(actual - expected(i)) must be <= 10
    }
    dut.clock.step()
  }

  "Dct8Approx matches the libjxl-tiny 1D DCT structure within fixed-point tolerance" in {
    simulate(new Dct8Approx()) { dut =>
      expectVector(dut, Seq.fill(8)(toQ(1.0)))
      expectVector(dut, (0 until 8).map(i => toQ(i.toDouble / 8.0)))
      expectVector(dut, Seq(5, -3, 9, 12, -4, 6, 0, 2).map(_.toInt * 64))
    }
  }
}
