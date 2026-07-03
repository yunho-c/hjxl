// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class Dct8x8ApproxSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val blockDim = HjxlConstants.BlockDim
  private val blockSize = blockDim * blockDim
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

  private def scaledDct8x8(values: Seq[Int]): Seq[Int] = {
    val block = values.map(_.toDouble / scale)
    def at(y: Int, x: Int): Double = block(y * blockDim + x)

    val columnPass = Array.ofDim[Double](blockDim, blockDim)
    for (x <- 0 until blockDim) {
      val transformed = dct1d((0 until blockDim).map(y => at(y, x))).map(_ / blockDim)
      for (y <- 0 until blockDim) {
        columnPass(y)(x) = transformed(y)
      }
    }

    val coeff = Array.ofDim[Double](blockDim, blockDim)
    for (sourceY <- 0 until blockDim) {
      val transformed = dct1d((0 until blockDim).map(x => columnPass(sourceY)(x))).map(_ / blockDim)
      for (coefficientX <- 0 until blockDim) {
        coeff(coefficientX)(sourceY) = transformed(coefficientX)
      }
    }

    coeff.flatten.toSeq.map(toQ)
  }

  private def expectBlock(dut: Dct8x8Approx, values: Seq[Int]): Unit = {
    val expected = scaledDct8x8(values)
    dut.io.input.valid.poke(true.B)
    dut.io.output.ready.poke(true.B)
    for (i <- 0 until blockSize) {
      dut.io.input.bits(i).poke(values(i).S)
    }
    dut.io.output.valid.expect(true.B)
    for (i <- 0 until blockSize) {
      val actual = dut.io.output.bits(i).peekValue().asBigInt.toInt
      math.abs(actual - expected(i)) must be <= 64
    }
    dut.clock.step()
  }

  "Dct8x8Approx matches libjxl-tiny scaled 8x8 DCT layout within fixed-point tolerance" in {
    simulate(new Dct8x8Approx()) { dut =>
      expectBlock(dut, Seq.fill(blockSize)(toQ(1.0)))
      expectBlock(dut, Seq.tabulate(blockSize)(i => toQ((i % blockDim).toDouble / blockDim)))
      expectBlock(dut, Seq.tabulate(blockSize)(i => (i % 11 - 5) * 73))
    }
  }
}
