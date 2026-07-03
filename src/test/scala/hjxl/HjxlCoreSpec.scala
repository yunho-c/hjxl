// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class HjxlCoreSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 8, maxFrameHeight = 8)

  private def pokeConfig(
      dut: HjxlCore,
      enableXyb: Boolean,
      enableDct: Boolean = false,
      enableQuant: Boolean = false
  ): Unit = {
    dut.io.config.xsize.poke(1.U)
    dut.io.config.ysize.poke(1.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(256.U)
    dut.io.config.enableXyb.poke(enableXyb.B)
    dut.io.config.enableDct.poke(enableDct.B)
    dut.io.config.enableQuant.poke(enableQuant.B)
    dut.io.config.enableTokenize.poke(false.B)
  }

  private def driveOnePixel(dut: HjxlCore): Unit = {
    dut.io.input.valid.poke(true.B)
    dut.io.input.bits.x.poke(0.U)
    dut.io.input.bits.y.poke(0.U)
    dut.io.input.bits.r.poke(64.S)
    dut.io.input.bits.g.poke(128.S)
    dut.io.input.bits.b.poke(192.S)
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
    dut.io.input.valid.poke(false.B)
  }

  "HjxlCore routes to padded trace when XYB is disabled" in {
    simulate(new HjxlCore(config)) { dut =>
      pokeConfig(dut, enableXyb = false)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      driveOnePixel(dut)
      dut.io.trace.ready.poke(true.B)
      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.stage.expect(TraceStage.InputPadded.U)
      dut.io.trace.bits.index.expect(0.U)
      dut.io.trace.bits.value.expect(64.S)
    }
  }

  "HjxlCore routes to XYB trace when XYB is enabled" in {
    simulate(new HjxlCore(config)) { dut =>
      pokeConfig(dut, enableXyb = true)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      driveOnePixel(dut)
      dut.io.trace.ready.poke(true.B)
      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.stage.expect(TraceStage.Xyb.U)
      dut.io.trace.bits.index.expect(0.U)
    }
  }

  "HjxlCore routes to raw DCT trace when DCT is enabled" in {
    simulate(new HjxlCore(config)) { dut =>
      pokeConfig(dut, enableXyb = true, enableDct = true)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      driveOnePixel(dut)
      dut.io.trace.ready.poke(true.B)
      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.stage.expect(TraceStage.RawDct8x8.U)
      dut.io.trace.bits.index.expect(0.U)
    }
  }

  "HjxlCore routes to AC strategy trace when quantization metadata is enabled" in {
    simulate(new HjxlCore(config)) { dut =>
      pokeConfig(dut, enableXyb = true, enableQuant = true)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      driveOnePixel(dut)
      dut.io.trace.ready.poke(true.B)
      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.stage.expect(TraceStage.AcStrategy.U)
      dut.io.trace.bits.index.expect(0.U)
      dut.io.trace.bits.value.expect(AcStrategyCode.encoded(AcStrategyCode.Dct, isFirstBlock = true).S)
    }
  }

  "HjxlCore gives raw DCT trace priority over AC strategy trace" in {
    simulate(new HjxlCore(config)) { dut =>
      pokeConfig(dut, enableXyb = true, enableDct = true, enableQuant = true)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      driveOnePixel(dut)
      dut.io.trace.ready.poke(true.B)
      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.stage.expect(TraceStage.RawDct8x8.U)
      dut.io.trace.bits.index.expect(0.U)
    }
  }
}
