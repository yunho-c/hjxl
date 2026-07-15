// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** Quantizes prepared DCT-only blocks and emits fixed all-DCT token traces.
  *
  * This is the first direct RTL bridge from the prepared-DCT quantization
  * boundary to logical token emission. Input blocks arrive in raster order.
  * Quantized DC, AC/nonzero outputs, and prepared metadata stream directly into
  * the dedicated frame schedulers that own those stores. The DC owner accepts
  * raster X/Y/B triplets and emits plane-ordered tokens, avoiding both a
  * duplicate frame array and a post-input reorder-copy phase in this
  * orchestration layer.
  */
class FramePreparedDctOnlyQuantizeTokenTraceStage(
    c: HjxlConfig = HjxlConfig(),
    coefficientFractionBitsOverride: Option[Int] = None
) extends Module {
  private val activeCoefficientFractionBits =
    coefficientFractionBitsOverride.getOrElse(c.preparedDctCoefficientFractionBits)
  require(activeCoefficientFractionBits > 0, "coefficientFractionBits must be positive")
  private val blockDim = HjxlConstants.BlockDim
  private val maxXBlocks = c.maxFrameWidth / blockDim
  private val maxYBlocks = c.maxFrameHeight / blockDim
  private val maxBlocks = maxXBlocks * maxYBlocks
  private val blockCountBits = log2Ceil(maxBlocks + 1)
  private val widthBits = log2Ceil(c.maxFrameWidth + 1)
  private val heightBits = log2Ceil(c.maxFrameHeight + 1)

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new DctOnlyQuantizeBlockInput(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val receiving :: emitDc :: emitStrategy :: emitMetadata :: emitAc :: Nil = Enum(5)
  val state = RegInit(receiving)
  val receivedBlocks = RegInit(0.U(blockCountBits.W))
  val totalBlocks = RegInit(0.U(blockCountBits.W))
  val emitIndex = RegInit(0.U(32.W))
  val latchedConfig = Reg(new FrameConfig(c))
  val overflow = RegInit(false.B)

  private def ceilDivBlock(value: UInt): UInt =
    (value +& (blockDim - 1).U) >> log2Ceil(blockDim)

  val transactionActive = state =/= receiving || receivedBlocks =/= 0.U
  val activeConfig = Wire(new FrameConfig(c))
  activeConfig := Mux(transactionActive, latchedConfig, io.config)

  val configWidth = activeConfig.xsize(widthBits - 1, 0)
  val configHeight = activeConfig.ysize(heightBits - 1, 0)
  val nextXBlocks = ceilDivBlock(configWidth)
  val nextYBlocks = ceilDivBlock(configHeight)
  val nextTotalBlocks = nextXBlocks * nextYBlocks
  val activeTotalBlocks = Mux(receivedBlocks === 0.U && state === receiving, nextTotalBlocks, totalBlocks)
  val configOutOfRange =
    activeConfig.xsize === 0.U || activeConfig.ysize === 0.U ||
      activeConfig.xsize > c.maxFrameWidth.U || activeConfig.ysize > c.maxFrameHeight.U ||
      nextXBlocks > maxXBlocks.U || nextYBlocks > maxYBlocks.U

  val quantizer = Module(new DctOnlyQuantizeBlock(c, activeCoefficientFractionBits))
  quantizer.io.input.valid :=
    io.input.valid && state === receiving && !configOutOfRange && receivedBlocks < activeTotalBlocks
  quantizer.io.input.bits := io.input.bits

  val dcTokens = Module(new FramePreparedDcBlockTokenTraceStage(c))
  dcTokens.io.config := activeConfig
  dcTokens.io.trace.ready := io.trace.ready && state === emitDc

  val metadataTokens = Module(new FramePreparedAcMetadataTokenTraceStage(c))
  metadataTokens.io.config := activeConfig
  metadataTokens.io.trace.ready := io.trace.ready && state === emitMetadata

  val acTokens = Module(new FramePreparedAcTokenTraceStage(c))
  acTokens.io.config := activeConfig
  acTokens.io.trace.ready := io.trace.ready && state === emitAc

  // DC values, AC coefficients/nonzero counts, and per-block metadata already
  // have frame-sized storage in their dedicated schedulers. Fork each
  // combinational quantizer result into those owners atomically instead of
  // duplicating complete frames in this orchestration layer.
  val quantizedBlockValid = state === receiving && quantizer.io.output.valid
  dcTokens.io.input.valid :=
    quantizedBlockValid && metadataTokens.io.input.ready && acTokens.io.input.ready
  metadataTokens.io.input.valid :=
    quantizedBlockValid && dcTokens.io.input.ready && acTokens.io.input.ready
  acTokens.io.input.valid :=
    quantizedBlockValid && dcTokens.io.input.ready && metadataTokens.io.input.ready
  quantizer.io.output.ready :=
    dcTokens.io.input.ready && metadataTokens.io.input.ready && acTokens.io.input.ready
  dcTokens.io.input.bits := quantizer.io.output.bits.quantizedDc
  metadataTokens.io.input.bits.rawQuant := io.input.bits.quant
  metadataTokens.io.input.bits.ytox := io.input.bits.ytox
  metadataTokens.io.input.bits.ytob := io.input.bits.ytob
  acTokens.io.input.bits.numNonzeros := quantizer.io.output.bits.numNonzeros
  acTokens.io.input.bits.quantized := quantizer.io.output.bits.quantizedAc

  assert(
    dcTokens.io.input.fire === metadataTokens.io.input.fire &&
      metadataTokens.io.input.fire === acTokens.io.input.fire,
    "prepared DC, metadata, and AC schedulers must accept quantized blocks atomically"
  )
  when(io.input.fire) {
    assert(
      dcTokens.io.input.fire && metadataTokens.io.input.fire && acTokens.io.input.fire,
      "an accepted prepared block must reach all owning frame stores"
    )
  }

  val strategyTrace = Wire(new StageTrace(c))
  strategyTrace.stage := TraceStage.AcStrategy.U
  strategyTrace.group := 0.U
  strategyTrace.index := emitIndex
  strategyTrace.value := AcStrategyCode.encoded(AcStrategyCode.Dct, isFirstBlock = true).S

  io.input.ready :=
    state === receiving && !configOutOfRange && receivedBlocks < activeTotalBlocks && quantizer.io.input.ready
  io.trace.valid := MuxCase(
    false.B,
    Seq(
      (state === emitDc) -> dcTokens.io.trace.valid,
      (state === emitStrategy) -> true.B,
      (state === emitMetadata) -> metadataTokens.io.trace.valid,
      (state === emitAc) -> acTokens.io.trace.valid
    )
  )
  io.trace.bits := MuxCase(
    0.U.asTypeOf(new StageTrace(c)),
    Seq(
      (state === emitDc) -> dcTokens.io.trace.bits,
      (state === emitStrategy) -> strategyTrace,
      (state === emitMetadata) -> metadataTokens.io.trace.bits,
      (state === emitAc) -> acTokens.io.trace.bits
    )
  )
  io.traceLast := state === emitAc && acTokens.io.traceLast
  io.busy :=
    state =/= receiving || receivedBlocks =/= 0.U || dcTokens.io.busy ||
      metadataTokens.io.busy || acTokens.io.busy
  io.overflow :=
    overflow || configOutOfRange || dcTokens.io.overflow || metadataTokens.io.overflow || acTokens.io.overflow

  when(configOutOfRange && state === receiving) {
    receivedBlocks := 0.U
    totalBlocks := 0.U
  }.elsewhen(io.input.fire) {
    when(receivedBlocks === 0.U) {
      latchedConfig := io.config
      totalBlocks := nextTotalBlocks(blockCountBits - 1, 0)
    }

    val nextReceived = receivedBlocks + 1.U
    receivedBlocks := nextReceived
    when(nextReceived === activeTotalBlocks) {
      state := emitDc
      emitIndex := 0.U
    }
  }

  when(state === emitDc && !dcTokens.io.busy && !dcTokens.io.trace.valid) {
    state := emitStrategy
    emitIndex := 0.U
  }

  when(state === emitStrategy && io.trace.fire) {
    val nextEmit = emitIndex + 1.U
    emitIndex := nextEmit
    when(nextEmit === totalBlocks) {
      state := emitMetadata
      emitIndex := 0.U
    }
  }

  when(state === emitMetadata && !metadataTokens.io.busy && !metadataTokens.io.trace.valid) {
    state := emitAc
    emitIndex := 0.U
  }

  when(state === emitAc && !acTokens.io.busy && !acTokens.io.trace.valid) {
    state := receiving
    receivedBlocks := 0.U
    totalBlocks := 0.U
    emitIndex := 0.U
  }

  when(io.input.fire && receivedBlocks >= maxBlocks.U) {
    overflow := true.B
  }
}
