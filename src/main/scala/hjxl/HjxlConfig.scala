// See README.md for license details.

package hjxl

/** Static configuration for the first libjxl-tiny-compatible RTL path. */
case class HjxlConfig(
    pixelBits: Int = 16,
    coordBits: Int = 16,
    groupBits: Int = HjxlAbiGenerated.Trace.GroupBits,
    traceValueBits: Int = HjxlAbiGenerated.Trace.ValueBits,
    maxFrameWidth: Int = 32,
    maxFrameHeight: Int = 32,
    preparedDctCoefficientFractionBits: Int = 16
) {
  require(pixelBits > 0, "pixelBits must be positive")
  require(coordBits > 0, "coordBits must be positive")
  require(groupBits > 0, "groupBits must be positive")
  require(traceValueBits > 0, "traceValueBits must be positive")
  require(maxFrameWidth > 0, "maxFrameWidth must be positive")
  require(maxFrameHeight > 0, "maxFrameHeight must be positive")
  require(preparedDctCoefficientFractionBits > 0, "preparedDctCoefficientFractionBits must be positive")
  require(
    preparedDctCoefficientFractionBits < traceValueBits,
    "preparedDctCoefficientFractionBits must fit in traceValueBits"
  )
  require(maxFrameWidth % HjxlConstants.BlockDim == 0, "maxFrameWidth must be block-aligned")
  require(maxFrameHeight % HjxlConstants.BlockDim == 0, "maxFrameHeight must be block-aligned")
}

object HjxlConstants {
  val BlockDim = 8
  val TileDim = 64
  val GroupDim = 256
  val DcGroupDim = GroupDim * BlockDim
}

object TraceStage {
  val InputPadded = HjxlAbiGenerated.Trace.Stage.InputPadded
  val Xyb = HjxlAbiGenerated.Trace.Stage.Xyb
  val RawDct8x8 = HjxlAbiGenerated.Trace.Stage.RawDct8x8
  val RawQuantField = HjxlAbiGenerated.Trace.Stage.RawQuantField
  val YtoxMap = HjxlAbiGenerated.Trace.Stage.YtoxMap
  val YtobMap = HjxlAbiGenerated.Trace.Stage.YtobMap
  val AcStrategy = HjxlAbiGenerated.Trace.Stage.AcStrategy
  val QuantDc = HjxlAbiGenerated.Trace.Stage.QuantDc
  val QuantizedAc = HjxlAbiGenerated.Trace.Stage.QuantizedAc
  val NumNonzeros = HjxlAbiGenerated.Trace.Stage.NumNonzeros
  val DcTokens = HjxlAbiGenerated.Trace.Stage.DcTokens
  val AcMetadataTokens = HjxlAbiGenerated.Trace.Stage.AcMetadataTokens
  val AcTokens = HjxlAbiGenerated.Trace.Stage.AcTokens
  val AqContrast = HjxlAbiGenerated.Trace.Stage.AqContrast
}

object TokenTraceSelect {
  val Dc = HjxlAbiGenerated.TokenSelect.Dc
  val AcMetadata = HjxlAbiGenerated.TokenSelect.AcMetadata
  val AcTokens = HjxlAbiGenerated.TokenSelect.AcTokens
  val AqContrast = HjxlAbiGenerated.TokenSelect.AqContrast
  val AcNonzero = AcTokens
}

object AcStrategyCode {
  val Dct = 0
  val Dct16x8 = 1
  val Dct8x16 = 2

  def encoded(rawStrategy: Int, isFirstBlock: Boolean): Int =
    (rawStrategy << 1) | (if (isFirstBlock) 1 else 0)
}
