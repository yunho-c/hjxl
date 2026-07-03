// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class FrameDctOnlyQuantizeTraceStageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 8)
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim
  private val recordsPerBlock = 3 * blockSize + 3 + 3

  private def pokeConfig(dut: FrameDctOnlyQuantizeTraceStage, width: Int, height: Int): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(QuantizeDct8x8Block.DefaultScaleQ16.U)
    dut.io.config.enableXyb.poke(true.B)
    dut.io.config.enableDct.poke(true.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(false.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.Dc.U)
  }

  private def drivePixel(dut: FrameDctOnlyQuantizeTraceStage, x: Int, y: Int, value: Int): Unit = {
    dut.io.input.valid.poke(true.B)
    dut.io.input.bits.x.poke(x.U)
    dut.io.input.bits.y.poke(y.U)
    dut.io.input.bits.r.poke(value.S)
    dut.io.input.bits.g.poke(value.S)
    dut.io.input.bits.b.poke(value.S)
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
  }

  private def mixedQ8(r: Int, g: Int, b: Int, m0: Int, m1: Int, m2: Int): Int = {
    val sum = r * m0 + g * m1 + b * m2
    val rounded = (sum + (1 << (RgbToXybApprox.CoefficientFractionBits - 1))) >>
      RgbToXybApprox.CoefficientFractionBits
    math.min(RgbToXybApprox.LutInputMax, rounded + RgbToXybApprox.BiasQ8)
  }

  private def xybExact(r: Int, g: Int, b: Int): (Int, Int, Int) = {
    val tm0 = RgbToXybApprox.CbrtPlusBiasTable(
      mixedQ8(r, g, b, RgbToXybApprox.M00, RgbToXybApprox.M01, RgbToXybApprox.M02)
    )
    val tm1 = RgbToXybApprox.CbrtPlusBiasTable(
      mixedQ8(r, g, b, RgbToXybApprox.M10, RgbToXybApprox.M11, RgbToXybApprox.M12)
    )
    val tm2 = RgbToXybApprox.CbrtPlusBiasTable(
      mixedQ8(r, g, b, RgbToXybApprox.M20, RgbToXybApprox.M21, RgbToXybApprox.M22)
    )
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

  "FrameDctOnlyQuantizeTraceStage emits fixed-quant traces for padded DCT-only blocks" in {
    simulate(new FrameDctOnlyQuantizeTraceStage(config)) { dut =>
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

      val expectedDc = expectedQuantizedDc(gray)
      dut.io.trace.ready.poke(true.B)
      for (block <- 0 until 2) {
        for (index <- 0 until 3 * blockSize) {
          dut.io.trace.valid.expect(true.B)
          dut.io.trace.bits.stage.expect(TraceStage.QuantizedAc.U)
          dut.io.trace.bits.group.expect(block.U)
          dut.io.trace.bits.index.expect(index.U)
          dut.io.trace.bits.value.expect(0.S)
          dut.clock.step()
        }
        for (channel <- 0 until 3) {
          dut.io.trace.valid.expect(true.B)
          dut.io.trace.bits.stage.expect(TraceStage.QuantDc.U)
          dut.io.trace.bits.group.expect(block.U)
          dut.io.trace.bits.index.expect(channel.U)
          dut.io.trace.bits.value.expect(expectedDc(channel).S)
          dut.clock.step()
        }
        for (channel <- 0 until 3) {
          dut.io.trace.valid.expect(true.B)
          dut.io.trace.bits.stage.expect(TraceStage.NumNonzeros.U)
          dut.io.trace.bits.group.expect(block.U)
          dut.io.trace.bits.index.expect(channel.U)
          dut.io.trace.bits.value.expect(0.S)
          dut.clock.step()
        }
      }

      dut.io.trace.valid.expect(false.B)
      dut.io.input.ready.expect(true.B)
      dut.io.overflow.expect(false.B)
      recordsPerBlock must be(198)
    }
  }
}
