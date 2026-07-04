// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class AcQuantScaleSelectorSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private def pokeCommonConfig(dut: AcQuantScaleSelector): Unit = {
    dut.io.config.xsize.poke(8.U)
    dut.io.config.ysize.poke(8.U)
    dut.io.config.distanceQ8.poke(DistanceParamsLookup.Distance1Q8.U)
    dut.io.config.enableXyb.poke(true.B)
    dut.io.config.enableDct.poke(true.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(false.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.Dc.U)
  }

  private def pokeDistance(dut: AcQuantScaleSelector, entry: DistanceParamsEntry): Unit = {
    dut.io.distance.scaleQ16.poke(entry.scaleQ16.U)
    dut.io.distance.invQacQ16.poke(entry.invQacQ16.U)
    dut.io.distance.quantDc.poke(entry.quantDc.U)
    dut.io.distance.xQmMultiplierQ16.poke(entry.xQmMultiplierQ16.U)
    dut.io.distance.epfIters.poke(entry.epfIters.U)
    for (channel <- 0 until 3) {
      dut.io.distance.invDcFactorQ16(channel).poke(entry.invDcFactorQ16(channel).U)
    }
  }

  "AcQuantScaleSelector uses distance-derived scale and reciprocal when fixed scale is zero" in {
    simulate(new AcQuantScaleSelector()) { dut =>
      val distance2 = DistanceParamsLookup.Entries.find(_.distanceQ8 == 512).get
      pokeCommonConfig(dut)
      pokeDistance(dut, distance2)
      dut.io.config.fixedPointScale.poke(0.U)
      dut.io.config.fixedInvQacQ16.poke(12345.U)
      dut.io.config.fixedRawQuant.poke(0.U)
      dut.clock.step()

      dut.io.params.scaleQ16.expect(distance2.scaleQ16.U)
      dut.io.params.invQacQ16.expect(distance2.invQacQ16.U)
      dut.io.params.rawQuant.expect(QuantizeDct8x8Block.DefaultRawQuant.U)
    }
  }

  "AcQuantScaleSelector uses explicit scale and reciprocal overrides when fixed scale is nonzero" in {
    simulate(new AcQuantScaleSelector()) { dut =>
      pokeCommonConfig(dut)
      pokeDistance(dut, DistanceParamsLookup.Default)
      dut.io.config.fixedPointScale.poke(4096.U)
      dut.io.config.fixedInvQacQ16.poke(QuantizeDct8x8Block.invQacQ16For(4096).U)
      dut.io.config.fixedRawQuant.poke(0.U)
      dut.clock.step()

      dut.io.params.scaleQ16.expect(4096.U)
      dut.io.params.invQacQ16.expect(QuantizeDct8x8Block.invQacQ16For(4096).U)
      dut.io.params.rawQuant.expect(QuantizeDct8x8Block.DefaultRawQuant.U)
    }
  }

  "AcQuantScaleSelector uses explicit reciprocal when fixed raw quant overrides the default" in {
    simulate(new AcQuantScaleSelector()) { dut =>
      val rawQuant = 7
      val explicitReciprocal = QuantizeDct8x8Block.invQacQ16For(
        DistanceParamsLookup.Default.scaleQ16,
        rawQuant
      )
      pokeCommonConfig(dut)
      pokeDistance(dut, DistanceParamsLookup.Default)
      dut.io.config.fixedPointScale.poke(0.U)
      dut.io.config.fixedInvQacQ16.poke(explicitReciprocal.U)
      dut.io.config.fixedRawQuant.poke(rawQuant.U)
      dut.clock.step()

      dut.io.params.scaleQ16.expect(DistanceParamsLookup.Default.scaleQ16.U)
      dut.io.params.invQacQ16.expect(explicitReciprocal.U)
      dut.io.params.rawQuant.expect(rawQuant.U)
    }
  }
}
