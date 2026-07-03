// See README.md for license details.

package hjxl

/** Static configuration for the first libjxl-tiny-compatible RTL path. */
case class HjxlConfig(
    pixelBits: Int = 16,
    coordBits: Int = 16,
    groupBits: Int = 16,
    traceValueBits: Int = 32,
    maxFrameWidth: Int = 32,
    maxFrameHeight: Int = 32
) {
  require(pixelBits > 0, "pixelBits must be positive")
  require(coordBits > 0, "coordBits must be positive")
  require(groupBits > 0, "groupBits must be positive")
  require(traceValueBits > 0, "traceValueBits must be positive")
  require(maxFrameWidth > 0, "maxFrameWidth must be positive")
  require(maxFrameHeight > 0, "maxFrameHeight must be positive")
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
  val InputPadded = 0
  val Xyb = 1
  val RawQuantField = 2
  val YtoxMap = 3
  val YtobMap = 4
  val AcStrategy = 5
  val QuantDc = 6
  val QuantizedAc = 7
  val NumNonzeros = 8
  val DcTokens = 9
  val AcMetadataTokens = 10
  val AcTokens = 11
}
