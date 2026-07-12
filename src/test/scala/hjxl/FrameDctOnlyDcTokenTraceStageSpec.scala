// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class FrameDctOnlyDcTokenTraceStageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 8)

  private def pokeConfig(dut: FrameDctOnlyDcTokenTraceStage, width: Int, height: Int): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(QuantizeDct8x8Block.DefaultScaleQ16.U)
    dut.io.config.fixedInvQacQ16.poke(QuantizeDct8x8Block.DefaultInvQacQ16.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.fixedYtox.poke(0.S)
    dut.io.config.fixedYtob.poke(0.S)
    dut.io.config.enableXyb.poke(true.B)
    dut.io.config.enableDct.poke(true.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(true.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.Dc.U)
  }

  private def drivePixel(dut: FrameDctOnlyDcTokenTraceStage, x: Int, y: Int, value: Int): Unit = {
    dut.io.input.valid.poke(true.B)
    dut.io.input.bits.x.poke(x.U)
    dut.io.input.bits.y.poke(y.U)
    dut.io.input.bits.r.poke(value.S)
    dut.io.input.bits.g.poke(value.S)
    dut.io.input.bits.b.poke(value.S)
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
  }

  private def mixedLutIndex(r: Int, g: Int, b: Int, m0: Int, m1: Int, m2: Int): Int = {
    val shift = RgbToXybApprox.CoefficientFractionBits +
      RgbToXybApprox.InputFractionBits - RgbToXybApprox.LutInputFractionBits
    val sum = r * m0 + g * m1 + b * m2
    val rounded = (sum + (1 << (shift - 1))) >> shift
    math.min(RgbToXybApprox.LutInputMax, rounded + RgbToXybApprox.BiasQLut)
  }

  private def xybExact(r: Int, g: Int, b: Int): (Int, Int, Int) = {
    val tm0 = RgbToXybApprox.cbrtPlusBiasFromLutIndex(mixedLutIndex(r, g, b, RgbToXybApprox.M00, RgbToXybApprox.M01, RgbToXybApprox.M02))
    val tm1 = RgbToXybApprox.cbrtPlusBiasFromLutIndex(mixedLutIndex(r, g, b, RgbToXybApprox.M10, RgbToXybApprox.M11, RgbToXybApprox.M12))
    val tm2 = RgbToXybApprox.cbrtPlusBiasFromLutIndex(mixedLutIndex(r, g, b, RgbToXybApprox.M20, RgbToXybApprox.M21, RgbToXybApprox.M22))
    ((tm0 - tm1) >> 1, (tm0 + tm1) >> 1, tm2)
  }

  private def roundFixedToInt(value: BigInt, fractionBits: Int): BigInt = {
    val half = BigInt(1) << (fractionBits - 1)
    if (value >= 0) (value + half) >> fractionBits
    else -((value.abs + half) >> fractionBits)
  }

  private def quantizeDcModel(coefficient: Int, channel: Int, invDcFactorQ16: Int, quantizedYDc: Int): Int = {
    val base = BigInt(coefficient) * BigInt(invDcFactorQ16)
    val bCorrection =
      if (channel == 2) {
        BigInt(quantizedYDc) *
          BigInt(QuantizeDct8x8Block.BQuantDcFromYFactorQ16) <<
          Dct8Approx.FractionBits
      } else {
        BigInt(0)
      }
    roundFixedToInt(
      base - bCorrection,
      QuantizeDct8x8Block.DcProductFractionBits
    ).toInt
  }

  private def expectedQuantizedDc(gray: Int): Seq[Int] = {
    val (xybX, xybY, xybB) = xybExact(gray, gray, gray)
    val yDc = quantizeDcModel(xybY, 1, QuantizeDct8x8Block.DefaultInvDcFactorQ16(1), 0)
    val xDc = quantizeDcModel(xybX, 0, QuantizeDct8x8Block.DefaultInvDcFactorQ16(0), yDc)
    val bDc = quantizeDcModel(xybB, 2, QuantizeDct8x8Block.DefaultInvDcFactorQ16(2), yDc)
    Seq(xDc, yDc, bDc)
  }

  private def clampedGradient(north: Int, west: Int, northwest: Int): Int = {
    val minValue = math.min(north, west)
    val maxValue = math.max(north, west)
    val gradient = north + west - northwest
    if (northwest < minValue) maxValue else if (northwest > maxValue) minValue else gradient
  }

  private def packSigned(value: Int): Int =
    if (value < 0) -2 * value - 1 else 2 * value

  private def gradientContext(gradProp: Int): Int = {
    val thresholds = Seq(
      (12, 44), (120, 43), (257, 40), (321, 39), (385, 38), (417, 37), (449, 36), (465, 35),
      (481, 34), (489, 33), (497, 32), (501, 31), (505, 30), (508, 29), (509, 28), (511, 27),
      (512, 26), (513, 42), (515, 41), (517, 25), (519, 24), (523, 23), (527, 22), (535, 21),
      (543, 20), (559, 19), (575, 18), (607, 17), (639, 16), (703, 15), (767, 14), (904, 13),
      (1012, 12)
    )
    thresholds.collectFirst { case (limit, context) if gradProp <= limit => context }.getOrElse(11)
  }

  private def expectedTokens(blockDcByChannel: Seq[Int], blocks: Int): Seq[(Int, Int)] = {
    val channelOrder = Seq(1, 0, 2)
    channelOrder.flatMap { channel =>
      val plane = Seq.fill(blocks)(blockDcByChannel(channel))
      plane.indices.map { x =>
        val left = if (x == 0) 0 else plane(x - 1)
        val top = left
        val topLeft = left
        val guess = clampedGradient(top, left, topLeft)
        val residual = plane(x) - guess
        val gradProp = math.max(DcTokenize.GradRangeMin, math.min(DcTokenize.GradRangeMax, DcTokenize.GradRangeMid + top + left - topLeft))
        (gradientContext(gradProp), packSigned(residual))
      }
    }
  }

  "FrameDctOnlyDcTokenTraceStage emits DC residual tokens in Y/X/B raster order" in {
    simulate(new FrameDctOnlyDcTokenTraceStage(config)) { dut =>
      val width = 9
      val height = 1
      val gray = 256
      pokeConfig(dut, width, height)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for (x <- 0 until width) {
        drivePixel(dut, x, y = 0, gray)
      }
      dut.io.input.valid.poke(false.B)

      val expected = expectedTokens(expectedQuantizedDc(gray), blocks = 2)
      dut.io.trace.ready.poke(true.B)
      for (((context, value), ordinal) <- expected.zipWithIndex) {
        dut.io.trace.valid.expect(true.B)
        dut.io.trace.bits.stage.expect(TraceStage.DcTokens.U)
        dut.io.trace.bits.group.expect(ordinal.U)
        dut.io.trace.bits.index.expect(context.U)
        dut.io.trace.bits.value.expect(value.S)
        dut.io.traceLast.expect((ordinal == expected.length - 1).B)
        dut.clock.step()
      }

      dut.io.trace.valid.expect(false.B)
      dut.io.input.ready.expect(true.B)
      dut.io.overflow.expect(false.B)
    }
  }
}
