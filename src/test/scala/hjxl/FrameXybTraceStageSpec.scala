// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class FrameXybTraceStageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 8, maxFrameHeight = 8)
  private val outputScale = 1 << RgbToXybApprox.OutputFractionBits

  private def pokeConfig(dut: FrameXybTraceStage, width: Int, height: Int): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(256.U)
    dut.io.config.enableXyb.poke(true.B)
    dut.io.config.enableDct.poke(false.B)
    dut.io.config.enableQuant.poke(false.B)
    dut.io.config.enableTokenize.poke(false.B)
  }

  private def drivePixel(
      dut: FrameXybTraceStage,
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

  private def reference(rQ8: Int, gQ8: Int, bQ8: Int): Seq[Int] = {
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

  private def expectedComponent(pixels: Seq[Seq[(Int, Int, Int)]], component: Int): Seq[Int] = {
    val width = pixels.head.length
    val height = pixels.length
    val paddedWidth = 8
    val paddedHeight = 8
    for {
      y <- 0 until paddedHeight
      x <- 0 until paddedWidth
      pixel = pixels(math.min(y, height - 1))(math.min(x, width - 1))
    } yield reference(pixel._1, pixel._2, pixel._3)(component)
  }

  "FrameXybTraceStage pads RGB input and emits channel-first XYB samples" in {
    simulate(new FrameXybTraceStage(config)) { dut =>
      pokeConfig(dut, width = 2, height = 2)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      drivePixel(dut, x = 0, y = 0, r = 0, g = 64, b = 128)
      drivePixel(dut, x = 1, y = 0, r = 32, g = 96, b = 160)
      drivePixel(dut, x = 0, y = 1, r = 64, g = 128, b = 192)
      drivePixel(dut, x = 1, y = 1, r = 128, g = 192, b = 256)
      dut.io.input.valid.poke(false.B)

      val pixels = Seq(
        Seq((0, 64, 128), (32, 96, 160)),
        Seq((64, 128, 192), (128, 192, 256))
      )
      val expected =
        expectedComponent(pixels, 0) ++ expectedComponent(pixels, 1) ++ expectedComponent(pixels, 2)

      dut.io.trace.ready.poke(true.B)
      for ((value, index) <- expected.zipWithIndex) {
        dut.io.trace.valid.expect(true.B)
        dut.io.trace.bits.stage.expect(TraceStage.Xyb.U)
        dut.io.trace.bits.index.expect(index.U)
        math.abs(dut.io.trace.bits.value.peekValue().asBigInt.toInt - value) must be <= 6
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.input.ready.expect(true.B)
      dut.io.overflow.expect(false.B)
    }
  }
}
