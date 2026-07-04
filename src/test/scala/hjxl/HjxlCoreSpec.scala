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
      enableQuant: Boolean = false,
      enableTokenize: Boolean = false,
      tokenSelect: Int = TokenTraceSelect.Dc
  ): Unit = {
    dut.io.config.xsize.poke(1.U)
    dut.io.config.ysize.poke(1.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(256.U)
    dut.io.config.fixedInvQacQ16.poke(QuantizeDct8x8Block.invQacQ16For(256).U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.enableXyb.poke(enableXyb.B)
    dut.io.config.enableDct.poke(enableDct.B)
    dut.io.config.enableQuant.poke(enableQuant.B)
    dut.io.config.enableTokenize.poke(enableTokenize.B)
    dut.io.config.tokenSelect.poke(tokenSelect.U)
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
    simulate(new HjxlCore(config, traceRoute = TraceStage.InputPadded)) { dut =>
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
    simulate(new HjxlCore(config, traceRoute = TraceStage.Xyb)) { dut =>
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
    simulate(new HjxlCore(config, traceRoute = TraceStage.RawDct8x8)) { dut =>
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
    simulate(new HjxlCore(config, traceRoute = TraceStage.AcStrategy)) { dut =>
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

  "HjxlCore routes to DCT-only quantized traces when DCT and quantization are enabled" in {
    simulate(new HjxlCore(config, traceRoute = TraceStage.QuantizedAc)) { dut =>
      pokeConfig(dut, enableXyb = true, enableDct = true, enableQuant = true)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      driveOnePixel(dut)
      dut.io.trace.ready.poke(true.B)
      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.stage.expect(TraceStage.QuantizedAc.U)
      dut.io.trace.bits.index.expect(0.U)
    }
  }

  "HjxlCore routes to DC token traces when tokenization is enabled" in {
    simulate(new HjxlCore(config, traceRoute = TraceStage.DcTokens)) { dut =>
      pokeConfig(dut, enableXyb = true, enableDct = true, enableQuant = true, enableTokenize = true)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      driveOnePixel(dut)
      dut.io.trace.ready.poke(true.B)
      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.stage.expect(TraceStage.DcTokens.U)
      dut.io.trace.bits.group.expect(0.U)
      dut.io.traceLast.expect(false.B)
      dut.clock.step()
      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.stage.expect(TraceStage.DcTokens.U)
      dut.io.trace.bits.group.expect(1.U)
      dut.io.traceLast.expect(false.B)
      dut.clock.step()
      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.stage.expect(TraceStage.DcTokens.U)
      dut.io.trace.bits.group.expect(2.U)
      dut.io.traceLast.expect(true.B)
    }
  }

  "HjxlCore routes to AC metadata token traces when quantized tokenization is enabled without DCT" in {
    simulate(new HjxlCore(config, traceRoute = TraceStage.AcMetadataTokens)) { dut =>
      pokeConfig(dut, enableXyb = true, enableQuant = true, enableTokenize = true, tokenSelect = TokenTraceSelect.AcMetadata)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      driveOnePixel(dut)
      dut.io.trace.ready.poke(true.B)
      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.stage.expect(TraceStage.AcMetadataTokens.U)
      dut.io.trace.bits.group.expect(0.U)
      dut.io.trace.bits.index.expect(2.U)
      dut.io.trace.bits.value.expect(0.S)
      dut.io.traceLast.expect(false.B)
      for (ordinal <- 1 until 4) {
        dut.clock.step()
        dut.io.trace.valid.expect(true.B)
        dut.io.trace.bits.stage.expect(TraceStage.AcMetadataTokens.U)
        dut.io.trace.bits.group.expect(ordinal.U)
        dut.io.traceLast.expect(false.B)
      }
      dut.clock.step()
      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.stage.expect(TraceStage.AcMetadataTokens.U)
      dut.io.trace.bits.group.expect(4.U)
      dut.io.traceLast.expect(true.B)
    }
  }
}
