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
      tokenSelect: Int = TokenTraceSelect.Dc,
      fixedYtox: Int = 0,
      fixedYtob: Int = 0
  ): Unit = {
    dut.io.config.xsize.poke(1.U)
    dut.io.config.ysize.poke(1.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(256.U)
    dut.io.config.fixedInvQacQ16.poke(QuantizeDct8x8Block.invQacQ16For(256).U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.fixedYtox.poke(fixedYtox.S)
    dut.io.config.fixedYtob.poke(fixedYtob.S)
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

  private def waitForTraceValid(dut: HjxlCore): Unit = {
    var cycles = 0
    while (dut.io.trace.valid.peekValue().asBigInt == 0 && cycles < 32) {
      dut.clock.step()
      cycles += 1
    }
    cycles must be < 32
  }

  private def expectTraceLastOnlyOnFinalBeat(
      dut: HjxlCore,
      totalBeats: Int,
      finalStage: Int,
      finalGroup: Int,
      finalIndex: Int
  ): Unit = {
    for (ordinal <- 0 until totalBeats) {
      if (ordinal > 0) {
        dut.clock.step()
      }
      dut.io.trace.valid.expect(true.B)
      dut.io.traceLast.expect((ordinal == totalBeats - 1).B)
    }
    dut.io.trace.bits.stage.expect(finalStage.U)
    dut.io.trace.bits.group.expect(finalGroup.U)
    dut.io.trace.bits.index.expect(finalIndex.U)
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
      dut.io.traceLast.expect(false.B)
      for (_ <- 1 until 191) {
        dut.clock.step()
        dut.io.traceLast.expect(false.B)
      }
      dut.clock.step()
      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.stage.expect(TraceStage.InputPadded.U)
      dut.io.trace.bits.index.expect(191.U)
      dut.io.traceLast.expect(true.B)
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
      expectTraceLastOnlyOnFinalBeat(dut, totalBeats = 192, finalStage = TraceStage.Xyb, finalGroup = 0, finalIndex = 191)
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
      expectTraceLastOnlyOnFinalBeat(
        dut,
        totalBeats = 192,
        finalStage = TraceStage.RawDct8x8,
        finalGroup = 0,
        finalIndex = 191
      )
    }
  }

  "HjxlCore routes XYB plus quantization to the AQ contrast grid" in {
    simulate(new HjxlCore(config, traceRoute = TraceStage.AqContrast)) { dut =>
      pokeConfig(
        dut,
        enableXyb = true,
        enableQuant = true,
        tokenSelect = TokenTraceSelect.AqContrast
      )
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      driveOnePixel(dut)
      dut.io.trace.ready.poke(true.B)
      for (cell <- 0 until 4) {
        waitForTraceValid(dut)
        dut.io.trace.bits.stage.expect(TraceStage.AqContrast.U)
        dut.io.trace.bits.group.expect(0.U)
        dut.io.trace.bits.index.expect(cell.U)
        dut.io.traceLast.expect((cell == 3).B)
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.busy.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "HjxlCore exposes the RGB fuzzy-erosion stage in a focused build" in {
    simulate(new HjxlCore(config, traceRoute = TraceStage.AqFuzzyErosion)) { dut =>
      pokeConfig(
        dut,
        enableXyb = true,
        enableQuant = true,
        tokenSelect = TokenTraceSelect.AqContrast
      )
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      driveOnePixel(dut)
      var waitCycles = 0
      while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < 128) {
        dut.clock.step()
        waitCycles += 1
      }
      waitCycles must be < 128
      dut.io.trace.bits.stage.expect(TraceStage.AqFuzzyErosion.U)
      dut.io.trace.bits.group.expect(0.U)
      dut.io.trace.bits.index.expect(0.U)
      dut.io.trace.bits.value.peekValue().asBigInt must be >= BigInt(0)
      dut.io.traceLast.expect(true.B)
      dut.clock.step()
      dut.io.trace.valid.expect(false.B)
      dut.io.busy.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "HjxlCore exposes the RGB AQ strategy mask in a focused build" in {
    simulate(new HjxlCore(config, traceRoute = TraceStage.AqStrategyMask)) { dut =>
      pokeConfig(
        dut,
        enableXyb = true,
        enableQuant = true,
        tokenSelect = TokenTraceSelect.AqContrast
      )
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      driveOnePixel(dut)
      var waitCycles = 0
      while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < 192) {
        dut.clock.step()
        waitCycles += 1
      }
      waitCycles must be < 192
      dut.io.trace.bits.stage.expect(TraceStage.AqStrategyMask.U)
      dut.io.trace.bits.group.expect(0.U)
      dut.io.trace.bits.index.expect(0.U)
      dut.io.trace.bits.value.peekValue().asBigInt must be >= BigInt(0)
      dut.io.traceLast.expect(true.B)
      dut.clock.step()
      dut.io.trace.valid.expect(false.B)
      dut.io.busy.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "HjxlCore exposes the signed RGB AQ nonlinear mask in a focused build" in {
    simulate(new HjxlCore(config, traceRoute = TraceStage.AqNonlinearMask)) { dut =>
      pokeConfig(
        dut,
        enableXyb = true,
        enableQuant = true,
        tokenSelect = TokenTraceSelect.AqContrast
      )
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      driveOnePixel(dut)
      var waitCycles = 0
      while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < 320) {
        dut.clock.step()
        waitCycles += 1
      }
      waitCycles must be < 320
      dut.io.trace.bits.stage.expect(TraceStage.AqNonlinearMask.U)
      dut.io.trace.bits.group.expect(0.U)
      dut.io.trace.bits.index.expect(0.U)
      dut.io.trace.bits.value.peekValue().asBigInt.toInt must be < 0
      dut.io.traceLast.expect(true.B)
      dut.clock.step()
      dut.io.trace.valid.expect(false.B)
      dut.io.busy.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "HjxlCore exposes the signed RGB AQ HF modulation in a focused build" in {
    simulate(new HjxlCore(config, traceRoute = TraceStage.AqHfModulation)) { dut =>
      pokeConfig(
        dut,
        enableXyb = true,
        enableQuant = true,
        tokenSelect = TokenTraceSelect.AqContrast
      )
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      driveOnePixel(dut)
      var waitCycles = 0
      while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < 400) {
        dut.clock.step()
        waitCycles += 1
      }
      waitCycles must be < 400
      dut.io.trace.bits.stage.expect(TraceStage.AqHfModulation.U)
      dut.io.trace.bits.group.expect(0.U)
      dut.io.trace.bits.index.expect(0.U)
      dut.io.trace.bits.value.peekValue().asBigInt.toInt must be < 0
      dut.io.traceLast.expect(true.B)
      dut.clock.step()
      dut.io.trace.valid.expect(false.B)
      dut.io.busy.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "HjxlCore exposes cumulative RGB AQ color modulation in a focused build" in {
    simulate(new HjxlCore(config, traceRoute = TraceStage.AqColorModulation)) { dut =>
      pokeConfig(
        dut,
        enableXyb = true,
        enableQuant = true,
        tokenSelect = TokenTraceSelect.AqContrast
      )
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      driveOnePixel(dut)
      var waitCycles = 0
      while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < 500) {
        dut.clock.step()
        waitCycles += 1
      }
      waitCycles must be < 500
      dut.io.trace.bits.stage.expect(TraceStage.AqColorModulation.U)
      dut.io.trace.bits.group.expect(0.U)
      dut.io.trace.bits.index.expect(0.U)
      dut.io.trace.bits.value.peekValue().asBigInt.toInt must be < 0
      dut.io.traceLast.expect(true.B)
      dut.clock.step()
      dut.io.trace.valid.expect(false.B)
      dut.io.busy.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "HjxlCore exposes cumulative RGB AQ gamma modulation in a focused build" in {
    simulate(new HjxlCore(config, traceRoute = TraceStage.AqGammaModulation)) { dut =>
      pokeConfig(
        dut,
        enableXyb = true,
        enableQuant = true,
        tokenSelect = TokenTraceSelect.AqContrast
      )
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      driveOnePixel(dut)
      var waitCycles = 0
      while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < 700) {
        dut.clock.step()
        waitCycles += 1
      }
      waitCycles must be < 700
      dut.io.trace.bits.stage.expect(TraceStage.AqGammaModulation.U)
      dut.io.trace.bits.group.expect(0.U)
      dut.io.trace.bits.index.expect(0.U)
      dut.io.trace.bits.value.peekValue().asBigInt.toInt must be < 0
      dut.io.traceLast.expect(true.B)
      dut.clock.step()
      dut.io.trace.valid.expect(false.B)
      dut.io.busy.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "HjxlCore exposes the completed RGB AQ map in a focused build" in {
    simulate(new HjxlCore(config, traceRoute = TraceStage.AqFinalMap)) { dut =>
      pokeConfig(
        dut,
        enableXyb = true,
        enableQuant = true,
        tokenSelect = TokenTraceSelect.AqContrast
      )
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      driveOnePixel(dut)
      var waitCycles = 0
      while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < 800) {
        dut.clock.step()
        waitCycles += 1
      }
      waitCycles must be < 800
      dut.io.trace.bits.stage.expect(TraceStage.AqFinalMap.U)
      dut.io.trace.bits.group.expect(0.U)
      dut.io.trace.bits.index.expect(0.U)
      dut.io.trace.bits.value.peekValue().asBigInt must be > BigInt(0)
      dut.io.traceLast.expect(true.B)
      dut.clock.step()
      dut.io.trace.valid.expect(false.B)
      dut.io.busy.expect(false.B)
      dut.io.overflow.expect(false.B)
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
      dut.io.traceLast.expect(true.B)
    }
  }

  "HjxlCore routes to raw quant-field trace when the focused raw-quant route is selected" in {
    simulate(new HjxlCore(config, traceRoute = TraceStage.RawQuantField)) { dut =>
      pokeConfig(dut, enableXyb = true, enableQuant = true)
      dut.io.config.fixedRawQuant.poke(11.U)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      driveOnePixel(dut)
      dut.io.trace.ready.poke(true.B)
      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.stage.expect(TraceStage.RawQuantField.U)
      dut.io.trace.bits.index.expect(0.U)
      dut.io.trace.bits.value.expect(11.S)
      dut.io.traceLast.expect(true.B)
    }
  }

  "HjxlCore focused raw quant route uses adaptive quantization when the override is zero" in {
    simulate(new HjxlCore(config, traceRoute = TraceStage.RawQuantField)) { dut =>
      pokeConfig(dut, enableXyb = true, enableQuant = true)
      dut.io.config.fixedRawQuant.poke(0.U)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      driveOnePixel(dut)
      var waitCycles = 0
      while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < 800) {
        dut.clock.step()
        waitCycles += 1
      }
      waitCycles must be < 800
      dut.io.trace.bits.stage.expect(TraceStage.RawQuantField.U)
      dut.io.trace.bits.index.expect(0.U)
      dut.io.trace.bits.value.peekValue().asBigInt must be > BigInt(0)
      dut.io.traceLast.expect(true.B)
    }
  }

  "HjxlCore routes to Y-to-X CFL map trace when the focused Ytox route is selected" in {
    simulate(new HjxlCore(config, traceRoute = TraceStage.YtoxMap)) { dut =>
      pokeConfig(dut, enableXyb = true, enableQuant = true, fixedYtox = -7, fixedYtob = 11)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      driveOnePixel(dut)
      dut.io.trace.ready.poke(true.B)
      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.stage.expect(TraceStage.YtoxMap.U)
      dut.io.trace.bits.index.expect(0.U)
      dut.io.trace.bits.value.expect((-7).S)
      dut.io.traceLast.expect(true.B)
    }
  }

  "HjxlCore routes to Y-to-B CFL map trace when the focused Ytob route is selected" in {
    simulate(new HjxlCore(config, traceRoute = TraceStage.YtobMap)) { dut =>
      pokeConfig(dut, enableXyb = true, enableQuant = true, fixedYtox = -7, fixedYtob = 11)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      driveOnePixel(dut)
      dut.io.trace.ready.poke(true.B)
      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.stage.expect(TraceStage.YtobMap.U)
      dut.io.trace.bits.index.expect(0.U)
      dut.io.trace.bits.value.expect(11.S)
      dut.io.traceLast.expect(true.B)
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
      expectTraceLastOnlyOnFinalBeat(
        dut,
        totalBeats = 198,
        finalStage = TraceStage.NumNonzeros,
        finalGroup = 0,
        finalIndex = 2
      )
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
      pokeConfig(
        dut,
        enableXyb = true,
        enableQuant = true,
        enableTokenize = true,
        tokenSelect = TokenTraceSelect.AcMetadata,
        fixedYtox = -7,
        fixedYtob = 11
      )
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      driveOnePixel(dut)
      dut.io.trace.ready.poke(true.B)
      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.stage.expect(TraceStage.AcMetadataTokens.U)
      dut.io.trace.bits.group.expect(0.U)
      dut.io.trace.bits.index.expect(2.U)
      dut.io.trace.bits.value.expect(13.S)
      dut.io.traceLast.expect(false.B)
      val expectedRemaining = Seq(
        (1, 1, 22, false),
        (2, 10, 0, false),
        (3, 6, 8, false),
        (4, 0, 8, true)
      )
      for ((group, index, value, traceLast) <- expectedRemaining) {
        dut.clock.step()
        dut.io.trace.valid.expect(true.B)
        dut.io.trace.bits.stage.expect(TraceStage.AcMetadataTokens.U)
        dut.io.trace.bits.group.expect(group.U)
        dut.io.trace.bits.index.expect(index.U)
        dut.io.trace.bits.value.expect(value.S)
        dut.io.traceLast.expect(traceLast.B)
      }
    }
  }

  "HjxlCore routes to full AC token traces when the focused AC-token route is selected" in {
    simulate(new HjxlCore(config, traceRoute = TraceStage.AcTokens)) { dut =>
      pokeConfig(
        dut,
        enableXyb = true,
        enableDct = true,
        enableQuant = true,
        enableTokenize = true,
        tokenSelect = TokenTraceSelect.AcTokens
      )
      dut.io.config.fixedPointScale.poke(QuantizeDct8x8Block.DefaultScaleQ16.U)
      dut.io.config.fixedInvQacQ16.poke(QuantizeDct8x8Block.DefaultInvQacQ16.U)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      driveOnePixel(dut)
      dut.io.trace.ready.poke(true.B)

      val expected = Seq(
        (0, 80, 0),
        (1, 82, 0),
        (2, 82, 0)
      )
      for (((group, context, value), ordinal) <- expected.zipWithIndex) {
        waitForTraceValid(dut)
        withClue(s"AC token route beat $ordinal") {
          dut.io.trace.bits.stage.expect(TraceStage.AcTokens.U)
          dut.io.trace.bits.group.expect(group.U)
          dut.io.trace.bits.index.expect(context.U)
          dut.io.trace.bits.value.expect(value.S)
          dut.io.traceLast.expect((ordinal == expected.length - 1).B)
        }
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
  }
}
