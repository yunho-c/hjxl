// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class HjxlCoreStatusSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 8, maxFrameHeight = 8)

  private def pokeConfig(dut: HjxlCore, width: Int, height: Int): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(256.U)
    dut.io.config.fixedInvQacQ16.poke(QuantizeDct8x8Block.invQacQ16For(256).U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.enableXyb.poke(false.B)
    dut.io.config.enableDct.poke(false.B)
    dut.io.config.enableQuant.poke(false.B)
    dut.io.config.enableTokenize.poke(false.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.Dc.U)
  }

  private def drivePixel(dut: HjxlCore, r: Int = 10, g: Int = 20, b: Int = 30): Unit = {
    dut.io.input.valid.poke(true.B)
    dut.io.input.bits.x.poke(0.U)
    dut.io.input.bits.y.poke(0.U)
    dut.io.input.bits.r.poke(r.S)
    dut.io.input.bits.g.poke(g.S)
    dut.io.input.bits.b.poke(b.S)
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
    dut.io.input.valid.poke(false.B)
  }

  "HjxlCore forwards busy from the selected trace route" in {
    simulate(new HjxlCore(config, traceRoute = TraceStage.InputPadded)) { dut =>
      pokeConfig(dut, width = 2, height = 1)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.io.busy.expect(false.B)
      dut.clock.step()

      drivePixel(dut)
      dut.io.busy.expect(true.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "HjxlCore forwards overflow from the selected trace route" in {
    simulate(new HjxlCore(config, traceRoute = TraceStage.InputPadded)) { dut =>
      pokeConfig(dut, width = 9, height = 1)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      dut.io.input.ready.expect(false.B)
      dut.io.busy.expect(false.B)
      dut.io.overflow.expect(true.B)
    }
  }
}
