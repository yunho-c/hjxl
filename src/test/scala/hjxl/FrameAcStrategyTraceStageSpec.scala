// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class FrameAcStrategyTraceStageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 16)

  private def pokeConfig(dut: FrameAcStrategyTraceStage, width: Int, height: Int): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(256.U)
    dut.io.config.fixedInvQacQ16.poke(QuantizeDct8x8Block.invQacQ16For(256).U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.enableXyb.poke(true.B)
    dut.io.config.enableDct.poke(false.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(false.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.Dc.U)
  }

  private def drivePixel(dut: FrameAcStrategyTraceStage, x: Int, y: Int): Unit = {
    dut.io.input.valid.poke(true.B)
    dut.io.input.bits.x.poke(x.U)
    dut.io.input.bits.y.poke(y.U)
    dut.io.input.bits.r.poke((x + y).S)
    dut.io.input.bits.g.poke((x * 2 + y).S)
    dut.io.input.bits.b.poke((x + y * 2).S)
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
  }

  "FrameAcStrategyTraceStage emits DCT-first strategy for each padded block" in {
    simulate(new FrameAcStrategyTraceStage(config)) { dut =>
      val width = 9
      val height = 9
      pokeConfig(dut, width, height)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for {
        y <- 0 until height
        x <- 0 until width
      } drivePixel(dut, x, y)
      dut.io.input.valid.poke(false.B)

      val expectedBlocks = 4
      val expectedStrategy = AcStrategyCode.encoded(AcStrategyCode.Dct, isFirstBlock = true)
      dut.io.trace.ready.poke(true.B)
      for (index <- 0 until expectedBlocks) {
        dut.io.trace.valid.expect(true.B)
        dut.io.trace.bits.stage.expect(TraceStage.AcStrategy.U)
        dut.io.trace.bits.group.expect(0.U)
        dut.io.trace.bits.index.expect(index.U)
        dut.io.trace.bits.value.expect(expectedStrategy.S)
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.input.ready.expect(true.B)
      dut.io.overflow.expect(false.B)
    }
  }
}
