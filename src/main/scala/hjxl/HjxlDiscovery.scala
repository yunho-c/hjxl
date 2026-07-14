// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util.MuxCase

/** Stable control-plane discovery values and route identity helpers. */
object HjxlDiscovery {
  val RgbCapabilities: BigInt =
    BigInt(HjxlAbiGenerated.Discovery.CapabilityProfile.Rgb)

  val PreparedDirectCapabilities: BigInt =
    BigInt(HjxlAbiGenerated.Discovery.CapabilityProfile.PreparedDirect)

  val PreparedEstimatedCflCapabilities: BigInt =
    BigInt(HjxlAbiGenerated.Discovery.CapabilityProfile.PreparedEstimatedCfl)

  def maxFrameGeometry(c: HjxlConfig): BigInt = {
    require(c.maxFrameWidth <= 0xffff, "discovery maxFrameWidth must fit in 16 bits")
    require(c.maxFrameHeight <= 0xffff, "discovery maxFrameHeight must fit in 16 bits")
    (BigInt(c.maxFrameHeight) << 16) | BigInt(c.maxFrameWidth)
  }

  /** Route selected by the ordinary all-route RGB shell's current config.
    *
    * A focused elaboration reports its compile-time stage directly. The
    * default all-route shell intentionally omits the heavy full-AC scheduler,
    * so an AcTokens selection with DCT+quant enabled resolves to QuantizedAc,
    * matching `HjxlCore`'s actual selector behavior.
    */
  def rgbConfiguredRoute(
      traceRoute: Int,
      enableXyb: Bool,
      enableDct: Bool,
      enableQuant: Bool,
      enableTokenize: Bool,
      tokenSelect: UInt
  ): UInt = {
    if (traceRoute != HjxlCoreTraceRoute.All) {
      val discoveredRoute =
        if (traceRoute == HjxlCoreTraceRoute.AqVarDctTokens) TraceStage.AcTokens
        else traceRoute
      discoveredRoute.U(HjxlAbiGenerated.Trace.StageBits.W)
    } else {
      val useDcTokens =
        enableDct && enableQuant && enableTokenize && tokenSelect === TokenTraceSelect.Dc.U
      val useAcMetadata =
        enableQuant && enableTokenize && tokenSelect === TokenTraceSelect.AcMetadata.U
      val useQuantizedAc = enableDct && enableQuant && !useDcTokens && !useAcMetadata
      val useRawDct = enableDct && !useQuantizedAc && !useDcTokens && !useAcMetadata
      val useAqContrast =
        enableXyb && enableQuant && !enableTokenize && !enableDct &&
          tokenSelect === TokenTraceSelect.AqContrast.U
      val useAcStrategy = enableQuant && !enableTokenize && !enableDct && !useAqContrast
      val useXyb =
        enableXyb && !useRawDct && !useQuantizedAc && !useDcTokens &&
          !useAcMetadata && !useAcStrategy && !useAqContrast

      MuxCase(
        TraceStage.InputPadded.U,
        Seq(
          useDcTokens -> TraceStage.DcTokens.U,
          useAcMetadata -> TraceStage.AcMetadataTokens.U,
          useQuantizedAc -> TraceStage.QuantizedAc.U,
          useRawDct -> TraceStage.RawDct8x8.U,
          useAqContrast -> TraceStage.AqContrast.U,
          useAcStrategy -> TraceStage.AcStrategy.U,
          useXyb -> TraceStage.Xyb.U
        )
      )
    }
  }
}

/** Holds the route selected at frame start while the stream remains busy. */
class HjxlActiveRouteTracker extends Module {
  val io = IO(new Bundle {
    val configuredRoute = Input(UInt(HjxlAbiGenerated.Trace.StageBits.W))
    val inputFire = Input(Bool())
    val busy = Input(Bool())
    val route = Output(UInt(HjxlAbiGenerated.Trace.StageBits.W))
  })

  val activeRoute = RegInit(TraceStage.InputPadded.U(HjxlAbiGenerated.Trace.StageBits.W))
  when(io.inputFire && !io.busy) {
    activeRoute := io.configuredRoute
  }
  io.route := Mux(io.busy, activeRoute, io.configuredRoute)
}
