// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class PassThroughTraceStageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private def pokeConfig(dut: PassThroughTraceStage): Unit = {
    dut.io.config.xsize.poke(1.U)
    dut.io.config.ysize.poke(1.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(256.U)
    dut.io.config.fixedInvQacQ16.poke(QuantizeDct8x8Block.invQacQ16For(256).U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.fixedYtox.poke(0.S)
    dut.io.config.fixedYtob.poke(0.S)
    dut.io.config.enableXyb.poke(true.B)
    dut.io.config.enableDct.poke(false.B)
    dut.io.config.enableQuant.poke(false.B)
    dut.io.config.enableTokenize.poke(false.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.Dc.U)
  }

  "PassThroughTraceStage emits one trace sample per RGB channel" in {
    simulate(new PassThroughTraceStage()) { dut =>
      pokeConfig(dut)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      dut.io.input.ready.expect(true.B)
      dut.io.input.valid.poke(true.B)
      dut.io.input.bits.x.poke(0.U)
      dut.io.input.bits.y.poke(0.U)
      dut.io.input.bits.r.poke(10.S)
      dut.io.input.bits.g.poke((-2).S)
      dut.io.input.bits.b.poke(31.S)
      dut.clock.step()

      dut.io.input.valid.poke(false.B)
      dut.io.input.ready.expect(false.B)
      dut.io.trace.ready.poke(true.B)

      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.stage.expect(TraceStage.InputPadded.U)
      dut.io.trace.bits.index.expect(0.U)
      dut.io.trace.bits.value.expect(10.S)
      dut.clock.step()

      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.index.expect(1.U)
      dut.io.trace.bits.value.expect((-2).S)
      dut.clock.step()

      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.index.expect(2.U)
      dut.io.trace.bits.value.expect(31.S)
      dut.clock.step()

      dut.io.trace.valid.expect(false.B)
      dut.io.input.ready.expect(true.B)
    }
  }
}
