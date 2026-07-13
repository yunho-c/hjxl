// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

private class HjxlRouteDiscoveryHarness(traceRoute: Int = HjxlCoreTraceRoute.All) extends Module {
  val io = IO(new Bundle {
    val enableXyb = Input(Bool())
    val enableDct = Input(Bool())
    val enableQuant = Input(Bool())
    val enableTokenize = Input(Bool())
    val tokenSelect = Input(UInt(2.W))
    val route = Output(UInt(HjxlAbiGenerated.Trace.StageBits.W))
  })
  io.route := HjxlDiscovery.rgbConfiguredRoute(
    traceRoute,
    io.enableXyb,
    io.enableDct,
    io.enableQuant,
    io.enableTokenize,
    io.tokenSelect
  )
}

class HjxlDiscoverySpec extends AnyFreeSpec with Matchers with ChiselSim {
  private case class RouteConfig(
      xyb: Boolean = false,
      dct: Boolean = false,
      quant: Boolean = false,
      tokenize: Boolean = false,
      tokenSelect: Int = TokenTraceSelect.Dc
  )

  private def expectRoute(dut: HjxlRouteDiscoveryHarness, config: RouteConfig, stage: Int): Unit = {
    dut.io.enableXyb.poke(config.xyb.B)
    dut.io.enableDct.poke(config.dct.B)
    dut.io.enableQuant.poke(config.quant.B)
    dut.io.enableTokenize.poke(config.tokenize.B)
    dut.io.tokenSelect.poke(config.tokenSelect.U)
    dut.io.route.expect(stage.U)
  }

  "RGB discovery mirrors the default HjxlCore route priorities" in {
    simulate(new HjxlRouteDiscoveryHarness) { dut =>
      expectRoute(dut, RouteConfig(), TraceStage.InputPadded)
      expectRoute(dut, RouteConfig(xyb = true), TraceStage.Xyb)
      expectRoute(dut, RouteConfig(xyb = true, quant = true), TraceStage.AcStrategy)
      expectRoute(
        dut,
        RouteConfig(xyb = true, quant = true, tokenSelect = TokenTraceSelect.AqContrast),
        TraceStage.AqContrast
      )
      expectRoute(dut, RouteConfig(dct = true), TraceStage.RawDct8x8)
      expectRoute(dut, RouteConfig(dct = true, quant = true), TraceStage.QuantizedAc)
      expectRoute(
        dut,
        RouteConfig(dct = true, quant = true, tokenize = true, tokenSelect = TokenTraceSelect.Dc),
        TraceStage.DcTokens
      )
      expectRoute(
        dut,
        RouteConfig(quant = true, tokenize = true, tokenSelect = TokenTraceSelect.AcMetadata),
        TraceStage.AcMetadataTokens
      )
      expectRoute(dut, RouteConfig(quant = true), TraceStage.AcStrategy)
      expectRoute(
        dut,
        RouteConfig(dct = true, quant = true, tokenize = true, tokenSelect = TokenTraceSelect.AcTokens),
        TraceStage.QuantizedAc
      )
    }
  }

  "focused discovery reports its compile-time trace stage" in {
    simulate(new HjxlRouteDiscoveryHarness(traceRoute = TraceStage.RawQuantField)) { dut =>
      expectRoute(
        dut,
        RouteConfig(xyb = true, dct = true, quant = true, tokenize = true, tokenSelect = TokenTraceSelect.AcTokens),
        TraceStage.RawQuantField
      )
    }
  }

  "active-route discovery holds the frame-start route while busy" in {
    simulate(new HjxlActiveRouteTracker) { dut =>
      dut.io.configuredRoute.poke(TraceStage.AcStrategy.U)
      dut.io.inputFire.poke(false.B)
      dut.io.busy.poke(false.B)
      dut.io.route.expect(TraceStage.AcStrategy.U)

      dut.io.inputFire.poke(true.B)
      dut.clock.step()
      dut.io.inputFire.poke(false.B)
      dut.io.busy.poke(true.B)
      dut.io.configuredRoute.poke(TraceStage.InputPadded.U)
      dut.io.route.expect(TraceStage.AcStrategy.U)

      dut.io.busy.poke(false.B)
      dut.io.route.expect(TraceStage.InputPadded.U)
    }
  }

  "discovery capability masks and geometry remain stable" in {
    HjxlDiscovery.RgbCapabilities mustBe BigInt(249)
    HjxlDiscovery.PreparedDirectCapabilities mustBe BigInt(1018)
    HjxlDiscovery.PreparedEstimatedCflCapabilities mustBe BigInt(510)
    HjxlDiscovery.maxFrameGeometry(HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 16)) mustBe BigInt(0x00100048)
  }
}
