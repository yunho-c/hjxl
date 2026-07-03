// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class FrameDctOnlyAcMetadataTokenTraceStageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 8)

  private def pokeConfig(dut: FrameDctOnlyAcMetadataTokenTraceStage, width: Int, height: Int): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(QuantizeDct8x8Block.DefaultScaleQ16.U)
    dut.io.config.enableXyb.poke(false.B)
    dut.io.config.enableDct.poke(false.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(true.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.AcMetadata.U)
  }

  private def drivePixel(dut: FrameDctOnlyAcMetadataTokenTraceStage, x: Int, y: Int): Unit = {
    dut.io.input.valid.poke(true.B)
    dut.io.input.bits.x.poke(x.U)
    dut.io.input.bits.y.poke(y.U)
    dut.io.input.bits.r.poke((x * 17 + y).S)
    dut.io.input.bits.g.poke((x * 3 + y * 5).S)
    dut.io.input.bits.b.poke((x + y * 11).S)
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
  }

  "FrameDctOnlyAcMetadataTokenTraceStage emits fixed-path AC metadata tokens" in {
    simulate(new FrameDctOnlyAcMetadataTokenTraceStage(config)) { dut =>
      val width = 9
      val height = 1
      pokeConfig(dut, width, height)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for (x <- 0 until width) {
        drivePixel(dut, x, y = 0)
      }
      dut.io.input.valid.poke(false.B)

      val expected = Seq(
        (2, 0), // Y-to-X CFL tile map residual.
        (1, 0), // Y-to-B CFL tile map residual.
        (10, 0), // First all-DCT strategy token.
        (10, 0), // Second all-DCT strategy token.
        (6, 8), // First raw-quant token: (5 - 1) - strategy_code(DCT).
        (5, 0), // Subsequent raw-quant token predicted from previous value.
        (0, 8), // Fixed block metadata literal.
        (0, 8)
      )

      dut.io.trace.ready.poke(true.B)
      for (((context, value), ordinal) <- expected.zipWithIndex) {
        withClue(s"token $ordinal context=$context value=$value") {
          dut.io.trace.valid.expect(true.B)
          dut.io.trace.bits.stage.expect(TraceStage.AcMetadataTokens.U)
          dut.io.trace.bits.group.expect(ordinal.U)
          dut.io.trace.bits.index.expect(context.U)
          dut.io.trace.bits.value.expect(value.S)
        }
        dut.clock.step()
      }

      dut.io.trace.valid.expect(false.B)
      dut.io.input.ready.expect(true.B)
      dut.io.overflow.expect(false.B)
    }
  }
}
