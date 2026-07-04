// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class FrameDctOnlyAcNonzeroTokenTraceStageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 8)

  private def pokeConfig(dut: FrameDctOnlyAcNonzeroTokenTraceStage, width: Int, height: Int): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(QuantizeDct8x8Block.DefaultScaleQ16.U)
    dut.io.config.fixedInvQacQ16.poke(QuantizeDct8x8Block.DefaultInvQacQ16.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.enableXyb.poke(true.B)
    dut.io.config.enableDct.poke(true.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(true.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.AcNonzero.U)
  }

  private def drivePixel(dut: FrameDctOnlyAcNonzeroTokenTraceStage, x: Int, y: Int, value: Int): Unit = {
    dut.io.input.valid.poke(true.B)
    dut.io.input.bits.x.poke(x.U)
    dut.io.input.bits.y.poke(y.U)
    dut.io.input.bits.r.poke(value.S)
    dut.io.input.bits.g.poke(value.S)
    dut.io.input.bits.b.poke(value.S)
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
  }

  private def nonzeroContext(nonzeros: Int, blockContext: Int): Int = {
    val bucket = if (nonzeros < 8) nonzeros else if (nonzeros >= 64) 36 else 4 + nonzeros / 2
    bucket * Tokenize.NumBlockContexts + blockContext
  }

  "FrameDctOnlyAcNonzeroTokenTraceStage emits AC nonzero-count tokens in block/channel order" in {
    simulate(new FrameDctOnlyAcNonzeroTokenTraceStage(config)) { dut =>
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

      val channelOrder = Seq(1, 0, 2)
      val expected = for {
        block <- 0 until 2
        channel <- channelOrder
      } yield {
        val predicted = if (block == 0) 32 else 0
        (nonzeroContext(predicted, Tokenize.DctBlockContextByChannel(channel)), 0)
      }

      dut.io.trace.ready.poke(true.B)
      for (((context, value), ordinal) <- expected.zipWithIndex) {
        withClue(s"token $ordinal context=$context value=$value") {
          dut.io.trace.valid.expect(true.B)
          dut.io.trace.bits.stage.expect(TraceStage.AcTokens.U)
          dut.io.trace.bits.group.expect(ordinal.U)
          dut.io.trace.bits.index.expect(context.U)
          dut.io.trace.bits.value.expect(value.S)
          dut.io.traceLast.expect((ordinal == expected.length - 1).B)
        }
        dut.clock.step()
      }

      dut.io.trace.valid.expect(false.B)
      dut.io.input.ready.expect(true.B)
      dut.io.overflow.expect(false.B)
    }
  }
}
