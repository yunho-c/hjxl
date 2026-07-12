// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class FrameCflMapTraceStageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 128, maxFrameHeight = 128)

  private def pokeConfig(
      dut: FrameCflMapTraceStage,
      width: Int,
      height: Int,
      fixedYtox: Int = 0,
      fixedYtob: Int = 0
  ): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(256.U)
    dut.io.config.fixedInvQacQ16.poke(QuantizeDct8x8Block.invQacQ16For(256).U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.fixedYtox.poke(fixedYtox.S)
    dut.io.config.fixedYtob.poke(fixedYtob.S)
    dut.io.config.enableXyb.poke(true.B)
    dut.io.config.enableDct.poke(false.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(false.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.Dc.U)
  }

  private def drivePixel(dut: FrameCflMapTraceStage, x: Int, y: Int): Unit = {
    dut.io.input.valid.poke(true.B)
    dut.io.input.bits.x.poke(x.U)
    dut.io.input.bits.y.poke(y.U)
    dut.io.input.bits.r.poke((x + y).S)
    dut.io.input.bits.g.poke((x * 2 + y).S)
    dut.io.input.bits.b.poke((x + y * 2).S)
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
  }

  private def expectFixedCflMap(
      traceStage: Int,
      width: Int,
      height: Int,
      expectedTiles: Int,
      expectedValue: Int = 0,
      fixedYtox: Int = 0,
      fixedYtob: Int = 0,
      stageConfig: HjxlConfig = config
  ): Unit = {
    simulate(new FrameCflMapTraceStage(stageConfig, traceStage)) { dut =>
      pokeConfig(dut, width, height, fixedYtox = fixedYtox, fixedYtob = fixedYtob)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for {
        y <- 0 until height
        x <- 0 until width
      } drivePixel(dut, x, y)
      dut.io.input.valid.poke(false.B)

      dut.io.trace.ready.poke(true.B)
      for (index <- 0 until expectedTiles) {
        dut.io.trace.valid.expect(true.B)
        dut.io.trace.bits.stage.expect(traceStage.U)
        dut.io.trace.bits.group.expect(0.U)
        dut.io.trace.bits.index.expect(index.U)
        dut.io.trace.bits.value.expect(expectedValue.S)
        dut.io.traceLast.expect((index == expectedTiles - 1).B)
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.input.ready.expect(true.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "FrameCflMapTraceStage emits fixed zero Y-to-X values for each tile" in {
    expectFixedCflMap(TraceStage.YtoxMap, width = 65, height = 9, expectedTiles = 2)
  }

  "FrameCflMapTraceStage emits fixed zero Y-to-B values for each tile" in {
    expectFixedCflMap(TraceStage.YtobMap, width = 9, height = 65, expectedTiles = 2)
  }

  "FrameCflMapTraceStage emits configured signed fixed CFL values" in {
    expectFixedCflMap(
      TraceStage.YtoxMap,
      width = 8,
      height = 8,
      expectedTiles = 1,
      expectedValue = -7,
      fixedYtox = -7,
      fixedYtob = 11
    )
    expectFixedCflMap(
      TraceStage.YtobMap,
      width = 8,
      height = 8,
      expectedTiles = 1,
      expectedValue = 11,
      fixedYtox = -7,
      fixedYtob = 11
    )
  }

  "FrameCflMapTraceStage preserves tile counts for non-tile-aligned max dimensions" in {
    val exactCapacity = HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 72)
    expectFixedCflMap(
      TraceStage.YtoxMap,
      width = 72,
      height = 8,
      expectedTiles = 2,
      fixedYtox = -3,
      expectedValue = -3,
      stageConfig = exactCapacity
    )
    expectFixedCflMap(
      TraceStage.YtobMap,
      width = 8,
      height = 72,
      expectedTiles = 2,
      fixedYtob = 4,
      expectedValue = 4,
      stageConfig = exactCapacity
    )
  }
}
