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
  val RawDct8x8 = 2
  val RawQuantField = 3
  val YtoxMap = 4
  val YtobMap = 5
  val AcStrategy = 6
  val QuantDc = 7
  val QuantizedAc = 8
  val NumNonzeros = 9
  val DcTokens = 10
  val AcMetadataTokens = 11
  val AcTokens = 12
}

object TokenTraceSelect {
  val Dc = 0
  val AcMetadata = 1
  val AcNonzero = 2
}

object AcStrategyCode {
  val Dct = 0
  val Dct16x8 = 1
  val Dct8x16 = 2

  def encoded(rawStrategy: Int, isFirstBlock: Boolean): Int =
    (rawStrategy << 1) | (if (isFirstBlock) 1 else 0)
}
