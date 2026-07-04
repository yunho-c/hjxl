// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class DistanceParamsLookupSpec extends AnyFreeSpec with Matchers with ChiselSim {
  "DistanceParamsLookup emits libjxl-tiny distance parameters for supported Q8 distances" in {
    simulate(new DistanceParamsLookup) { dut =>
      for (entry <- DistanceParamsLookup.Entries) {
        withClue(s"distanceQ8=${entry.distanceQ8}") {
          dut.io.distanceQ8.poke(entry.distanceQ8.U)
          dut.clock.step()
          dut.io.supported.expect(true.B)
          dut.io.params.scaleQ16.expect(entry.scaleQ16.U)
          dut.io.params.invQacQ16.expect(entry.invQacQ16.U)
          dut.io.params.quantDc.expect(entry.quantDc.U)
          dut.io.params.xQmMultiplierQ16.expect(entry.xQmMultiplierQ16.U)
          dut.io.params.epfIters.expect(entry.epfIters.U)
          for (channel <- 0 until 3) {
            dut.io.params.invDcFactorQ16(channel).expect(entry.invDcFactorQ16(channel).U)
          }
        }
      }
    }
  }

  "DistanceParamsLookup falls back to distance 1 for unsupported distances" in {
    simulate(new DistanceParamsLookup) { dut =>
      val fallback = DistanceParamsLookup.Default
      dut.io.distanceQ8.poke(333.U)
      dut.clock.step()
      dut.io.supported.expect(false.B)
      dut.io.params.scaleQ16.expect(fallback.scaleQ16.U)
      dut.io.params.invQacQ16.expect(fallback.invQacQ16.U)
      dut.io.params.quantDc.expect(fallback.quantDc.U)
      dut.io.params.xQmMultiplierQ16.expect(fallback.xQmMultiplierQ16.U)
      dut.io.params.epfIters.expect(fallback.epfIters.U)
      for (channel <- 0 until 3) {
        dut.io.params.invDcFactorQ16(channel).expect(fallback.invDcFactorQ16(channel).U)
      }
    }
  }
}
