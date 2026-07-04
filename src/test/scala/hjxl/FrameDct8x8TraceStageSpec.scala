// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class FrameDct8x8TraceStageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 16)
  private val blockDim = HjxlConstants.BlockDim
  private val blockSize = blockDim * blockDim
  private val outputScale = 1 << RgbToXybApprox.OutputFractionBits
  private val dctScale = 1 << Dct8Approx.FractionBits
  private val sqrt2 = 1.41421356237
  private val wc4 = Seq(0.541196100146197, 1.3065629648763764)
  private val wc8 = Seq(0.5097955791041592, 0.6013448869350453, 0.8999762231364156, 2.5629154477415055)

  private def pokeConfig(dut: FrameDct8x8TraceStage, width: Int, height: Int): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(256.U)
    dut.io.config.fixedInvQacQ16.poke(QuantizeDct8x8Block.invQacQ16For(256).U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.enableXyb.poke(true.B)
    dut.io.config.enableDct.poke(true.B)
    dut.io.config.enableQuant.poke(false.B)
    dut.io.config.enableTokenize.poke(false.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.Dc.U)
  }

  private def drivePixel(
      dut: FrameDct8x8TraceStage,
      x: Int,
      y: Int,
      r: Int,
      g: Int,
      b: Int
  ): Unit = {
    dut.io.input.valid.poke(true.B)
    dut.io.input.bits.x.poke(x.U)
    dut.io.input.bits.y.poke(y.U)
    dut.io.input.bits.r.poke(r.S)
    dut.io.input.bits.g.poke(g.S)
    dut.io.input.bits.b.poke(b.S)
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
  }

  private def xybReference(rQ8: Int, gQ8: Int, bQ8: Int): Seq[Int] = {
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
    Seq(
      math.round(0.5 * (tm0 - tm1) * outputScale).toInt,
      math.round(0.5 * (tm0 + tm1) * outputScale).toInt,
      math.round(tm2 * outputScale).toInt
    )
  }

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
    val block = values.map(_.toDouble / dctScale)
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
    coeff.flatten.toSeq.map(v => math.round(v * dctScale).toInt)
  }

  private def expectedBlocks(pixels: Seq[Seq[(Int, Int, Int)]]): Seq[Seq[Int]] = {
    val width = pixels.head.length
    val height = pixels.length
    val paddedWidth = ((width + blockDim - 1) / blockDim) * blockDim
    val paddedHeight = ((height + blockDim - 1) / blockDim) * blockDim
    for {
      blockY <- 0 until paddedHeight / blockDim
      blockX <- 0 until paddedWidth / blockDim
    } yield {
      val xybBlock = for {
        localY <- 0 until blockDim
        localX <- 0 until blockDim
        sourceY = math.min(blockY * blockDim + localY, height - 1)
        sourceX = math.min(blockX * blockDim + localX, width - 1)
        pixel = pixels(sourceY)(sourceX)
      } yield xybReference(pixel._1, pixel._2, pixel._3)

      (0 until 3).flatMap { channel =>
        scaledDct8x8(xybBlock.map(_(channel)))
      }
    }
  }

  private def expectBlocks(dut: FrameDct8x8TraceStage, blocks: Seq[Seq[Int]]): Unit = {
    dut.io.trace.ready.poke(true.B)
    val lastBlock = blocks.length - 1
    for ((coefficients, blockIndex) <- blocks.zipWithIndex) {
      val lastCoefficient = coefficients.length - 1
      for ((value, index) <- coefficients.zipWithIndex) {
        dut.io.trace.valid.expect(true.B)
        dut.io.trace.bits.stage.expect(TraceStage.RawDct8x8.U)
        dut.io.trace.bits.group.expect(blockIndex.U)
        dut.io.trace.bits.index.expect(index.U)
        math.abs(dut.io.trace.bits.value.peekValue().asBigInt.toInt - value) must be <= 80
        dut.io.traceLast.expect((blockIndex == lastBlock && index == lastCoefficient).B)
        dut.clock.step()
      }
    }
    dut.io.trace.valid.expect(false.B)
    dut.io.input.ready.expect(true.B)
    dut.io.overflow.expect(false.B)
  }

  "FrameDct8x8TraceStage emits one block of channel-first raw DCT coefficients" in {
    simulate(new FrameDct8x8TraceStage(config)) { dut =>
      pokeConfig(dut, width = 2, height = 2)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      val pixels = Seq(
        Seq((0, 64, 128), (32, 96, 160)),
        Seq((64, 128, 192), (128, 192, 256))
      )
      for {
        y <- pixels.indices
        x <- pixels.head.indices
        pixel = pixels(y)(x)
      } {
        drivePixel(dut, x = x, y = y, r = pixel._1, g = pixel._2, b = pixel._3)
      }
      dut.io.input.valid.poke(false.B)

      expectBlocks(dut, expectedBlocks(pixels))
    }
  }

  "FrameDct8x8TraceStage emits all padded 8x8 blocks in raster order" in {
    simulate(new FrameDct8x8TraceStage(config)) { dut =>
      val width = 9
      val height = 9
      pokeConfig(dut, width = width, height = height)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      val pixels = Seq.tabulate(height, width) { (y, x) =>
        val r = (x * 17 + y * 3) & 0xff
        val g = (x * 5 + y * 19 + 32) & 0xff
        val b = (x * 11 + y * 7 + 64) & 0xff
        (r, g, b)
      }
      for {
        y <- pixels.indices
        x <- pixels.head.indices
        pixel = pixels(y)(x)
      } {
        drivePixel(dut, x = x, y = y, r = pixel._1, g = pixel._2, b = pixel._3)
      }
      dut.io.input.valid.poke(false.B)

      val expected = expectedBlocks(pixels)
      expected.length must be(4)
      expectBlocks(dut, expected)
    }
  }
}
