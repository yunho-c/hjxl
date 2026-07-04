// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** Emits AC-metadata tokens for the current fixed all-DCT quantization path.
  *
  * This stage mirrors libjxl-tiny's `ac_metadata_tokens` order for the current
  * hardware subset: zero Y-to-X/Y-to-B CFL tile maps, all-DCT first-block
  * strategy tokens, fixed raw quant-field tokens from `FrameConfig`, and fixed
  * block metadata literals. Token traces use `trace.group = token ordinal`,
  * `trace.index = context`, and `trace.value = packed residual/literal`.
  */
class FrameDctOnlyAcMetadataTokenTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  private val blockDim = HjxlConstants.BlockDim
  private val tileDim = HjxlConstants.TileDim
  private val numPixels = c.maxFrameWidth * c.maxFrameHeight
  private val frameCountBits = log2Ceil(numPixels + 1)
  private val widthBits = log2Ceil(c.maxFrameWidth + 1)
  private val heightBits = log2Ceil(c.maxFrameHeight + 1)

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val receiving :: emitting :: Nil = Enum(2)
  val state = RegInit(receiving)
  val received = RegInit(0.U(frameCountBits.W))
  val emitIndex = RegInit(0.U(32.W))
  val totalBlocks = RegInit(0.U(32.W))
  val totalTiles = RegInit(0.U(32.W))
  val overflow = RegInit(false.B)

  private def ceilDiv(value: UInt, divisor: Int): UInt =
    (value + (divisor - 1).U) / divisor.U

  private def ceilToBlock(value: UInt): UInt =
    ceilDiv(value, blockDim) * blockDim.U

  val configWidth = io.config.xsize(widthBits - 1, 0)
  val configHeight = io.config.ysize(heightBits - 1, 0)
  val expectedPixels = configWidth * configHeight
  val nextPaddedWidth = ceilToBlock(configWidth)(widthBits - 1, 0)
  val nextPaddedHeight = ceilToBlock(configHeight)(heightBits - 1, 0)
  val nextXBlocks = nextPaddedWidth >> log2Ceil(blockDim)
  val nextYBlocks = nextPaddedHeight >> log2Ceil(blockDim)
  val nextXTilesRaw = ceilDiv(configWidth, tileDim)
  val nextYTilesRaw = ceilDiv(configHeight, tileDim)
  val nextXTiles = Mux(nextXTilesRaw === 0.U, 1.U, nextXTilesRaw)
  val nextYTiles = Mux(nextYTilesRaw === 0.U, 1.U, nextYTilesRaw)
  val configOutOfRange =
    io.config.xsize === 0.U || io.config.ysize === 0.U ||
      io.config.xsize > c.maxFrameWidth.U || io.config.ysize > c.maxFrameHeight.U ||
      nextPaddedWidth > c.maxFrameWidth.U || nextPaddedHeight > c.maxFrameHeight.U

  val cflTokenCount = totalTiles * 2.U
  val strategyStart = cflTokenCount
  val quantStart = strategyStart + totalBlocks
  val blockMetadataStart = quantStart + totalBlocks
  val totalTokens = blockMetadataStart + totalBlocks

  val isCfl = emitIndex < strategyStart
  val isStrategy = emitIndex >= strategyStart && emitIndex < quantStart
  val isQuant = emitIndex >= quantStart && emitIndex < blockMetadataStart
  val cflMap = emitIndex / Mux(totalTiles === 0.U, 1.U, totalTiles)
  val quantOrdinal = emitIndex - quantStart
  val selectedRawQuant = Mux(
    io.config.fixedRawQuant === 0.U,
    QuantizeDct8x8Block.DefaultRawQuant.U,
    io.config.fixedRawQuant
  )
  val selectedQuantField = selectedRawQuant - 1.U
  val quantLeft = Mux(quantOrdinal === 0.U, Tokenize.DctStrategyCode.U, selectedQuantField)
  val quantCurrent = Cat(0.U(1.W), selectedQuantField).asSInt
  val quantResidual = quantCurrent - Cat(0.U(1.W), quantLeft).asSInt

  io.input.ready := state === receiving && !configOutOfRange
  io.trace.valid := state === emitting
  io.traceLast := state === emitting && totalTokens =/= 0.U && emitIndex === totalTokens - 1.U
  io.busy := state === emitting || received =/= 0.U
  io.overflow := overflow || configOutOfRange
  io.trace.bits.stage := TraceStage.AcMetadataTokens.U
  io.trace.bits.group := emitIndex(c.groupBits - 1, 0)
  io.trace.bits.index := Mux(
    isCfl,
    Mux(cflMap === 0.U, 2.U, 1.U),
    Mux(
      isStrategy,
      Tokenize.strategyContext(Tokenize.DctStrategyCode.U),
      Mux(isQuant, Tokenize.quantFieldContext(quantLeft), 0.U)
    )
  )
  val cflOrStrategyValue = 0.S(c.traceValueBits.W)
  val quantValue = Tokenize.packSigned(quantResidual, c.traceValueBits)
  val blockMetadataValue = Tokenize.packSigned(Tokenize.DefaultBlockMetadata.S, c.traceValueBits)
  io.trace.bits.value := MuxCase(
    blockMetadataValue,
    Seq(
      isCfl -> cflOrStrategyValue,
      isStrategy -> cflOrStrategyValue,
      isQuant -> quantValue
    )
  )

  when(configOutOfRange) {
    received := 0.U
    state := receiving
  }.elsewhen(io.input.fire) {
    val nextReceived = received + 1.U
    received := nextReceived
    when(nextReceived === expectedPixels) {
      state := emitting
      emitIndex := 0.U
      totalBlocks := nextXBlocks * nextYBlocks
      totalTiles := nextXTiles * nextYTiles
    }
  }

  when(io.trace.fire) {
    val nextEmit = emitIndex + 1.U
    emitIndex := nextEmit
    when(nextEmit === totalTokens) {
      state := receiving
      received := 0.U
      emitIndex := 0.U
      totalBlocks := 0.U
      totalTiles := 0.U
    }
  }

  when(io.input.fire && received >= numPixels.U) {
    overflow := true.B
  }
}
