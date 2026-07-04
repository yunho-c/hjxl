// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** Quantizes prepared DCT-only blocks and emits fixed all-DCT token traces.
  *
  * This is the first direct RTL bridge from the prepared-DCT quantization
  * boundary to logical token emission. Input blocks arrive in raster order.
  * The stage buffers quantized DC/AC/nonzero outputs plus prepared metadata,
  * then feeds the lower-level prepared DC, AC-metadata, and AC-token schedulers
  * in libjxl-tiny order.
  */
class FramePreparedDctOnlyQuantizeTokenTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  private val blockDim = HjxlConstants.BlockDim
  private val blockSize = blockDim * blockDim
  private val maxXBlocks = c.maxFrameWidth / blockDim
  private val maxYBlocks = c.maxFrameHeight / blockDim
  private val maxBlocks = maxXBlocks * maxYBlocks
  private val blockCountBits = log2Ceil(maxBlocks + 1)
  private val blockIndexBits = math.max(1, log2Ceil(maxBlocks))
  private val sampleCountBits = log2Ceil(maxBlocks * 3 + 1)
  private val widthBits = log2Ceil(c.maxFrameWidth + 1)
  private val heightBits = log2Ceil(c.maxFrameHeight + 1)

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new DctOnlyQuantizeBlockInput(c)))
    val trace = Decoupled(new StageTrace(c))
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val quantizedAc = Reg(Vec(maxBlocks, Vec(3, Vec(blockSize, SInt(c.traceValueBits.W)))))
  val quantizedDc = Reg(Vec(maxBlocks, Vec(3, SInt(c.traceValueBits.W))))
  val numNonzeros = Reg(Vec(maxBlocks, Vec(3, UInt(8.W))))
  val rawQuant = Reg(Vec(maxBlocks, UInt(8.W)))
  val ytox = Reg(Vec(maxBlocks, SInt(8.W)))
  val ytob = Reg(Vec(maxBlocks, SInt(8.W)))

  val receiving :: feedDc :: feedMetadata :: feedAc :: emitDc :: emitStrategy :: emitMetadata :: emitAc :: Nil = Enum(8)
  val state = RegInit(receiving)
  val receivedBlocks = RegInit(0.U(blockCountBits.W))
  val totalBlocks = RegInit(0.U(blockCountBits.W))
  val dcSample = RegInit(0.U(sampleCountBits.W))
  val metadataBlock = RegInit(0.U(blockCountBits.W))
  val acBlock = RegInit(0.U(blockCountBits.W))
  val emitIndex = RegInit(0.U(32.W))
  val latchedConfig = Reg(new FrameConfig(c))
  val overflow = RegInit(false.B)

  private def ceilDivBlock(value: UInt): UInt =
    (value + (blockDim - 1).U) >> log2Ceil(blockDim)

  private def blockIndex(value: UInt): UInt =
    value(blockIndexBits - 1, 0)

  val transactionActive = state =/= receiving || receivedBlocks =/= 0.U
  val activeConfig = Wire(new FrameConfig(c))
  activeConfig := Mux(transactionActive, latchedConfig, io.config)

  val configWidth = activeConfig.xsize(widthBits - 1, 0)
  val configHeight = activeConfig.ysize(heightBits - 1, 0)
  val nextXBlocks = ceilDivBlock(configWidth)
  val nextYBlocks = ceilDivBlock(configHeight)
  val nextTotalBlocks = nextXBlocks * nextYBlocks
  val activeTotalBlocks = Mux(receivedBlocks === 0.U && state === receiving, nextTotalBlocks, totalBlocks)
  val totalDcSamples = activeTotalBlocks * 3.U
  val xTilesRaw = (configWidth + (HjxlConstants.TileDim - 1).U) / HjxlConstants.TileDim.U
  val yTilesRaw = (configHeight + (HjxlConstants.TileDim - 1).U) / HjxlConstants.TileDim.U
  val xTiles = Mux(xTilesRaw === 0.U, 1.U, xTilesRaw)
  val yTiles = Mux(yTilesRaw === 0.U, 1.U, yTilesRaw)
  val metadataTokenCount = xTiles * yTiles * 2.U + activeTotalBlocks * 3.U
  val configOutOfRange =
    activeConfig.xsize === 0.U || activeConfig.ysize === 0.U ||
      activeConfig.xsize > c.maxFrameWidth.U || activeConfig.ysize > c.maxFrameHeight.U ||
      nextXBlocks > maxXBlocks.U || nextYBlocks > maxYBlocks.U

  val quantizer = Module(new DctOnlyQuantizeBlock(c))
  quantizer.io.input.valid :=
    io.input.valid && state === receiving && !configOutOfRange && receivedBlocks < activeTotalBlocks
  quantizer.io.input.bits := io.input.bits
  quantizer.io.output.ready := true.B

  val dcTokens = Module(new FramePreparedDcTokenTraceStage(c))
  dcTokens.io.config := activeConfig
  dcTokens.io.trace.ready := io.trace.ready && state === emitDc

  val metadataTokens = Module(new FramePreparedAcMetadataTokenTraceStage(c))
  metadataTokens.io.config := activeConfig
  metadataTokens.io.trace.ready := io.trace.ready && state === emitMetadata

  val acTokens = Module(new FramePreparedAcTokenTraceStage(c))
  acTokens.io.config := activeConfig
  acTokens.io.trace.ready := io.trace.ready && state === emitAc

  val dcChannel = Mux(
    dcSample < activeTotalBlocks,
    1.U,
    Mux(dcSample < activeTotalBlocks * 2.U, 0.U, 2.U)
  )
  val dcBlock = Mux(
    dcSample < activeTotalBlocks,
    dcSample,
    Mux(dcSample < activeTotalBlocks * 2.U, dcSample - activeTotalBlocks, dcSample - activeTotalBlocks * 2.U)
  )
  dcTokens.io.input.valid := state === feedDc
  dcTokens.io.input.bits := quantizedDc(blockIndex(dcBlock))(dcChannel)

  metadataTokens.io.input.valid := state === feedMetadata
  metadataTokens.io.input.bits.rawQuant := rawQuant(blockIndex(metadataBlock))
  metadataTokens.io.input.bits.ytox := ytox(blockIndex(metadataBlock))
  metadataTokens.io.input.bits.ytob := ytob(blockIndex(metadataBlock))

  acTokens.io.input.valid := state === feedAc
  acTokens.io.input.bits.numNonzeros := numNonzeros(blockIndex(acBlock))
  acTokens.io.input.bits.quantized := quantizedAc(blockIndex(acBlock))

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
  io.busy :=
    state =/= receiving || receivedBlocks =/= 0.U || dcTokens.io.busy ||
      metadataTokens.io.busy || acTokens.io.busy
  io.overflow :=
    overflow || configOutOfRange || dcTokens.io.overflow || metadataTokens.io.overflow || acTokens.io.overflow

  when(configOutOfRange && state === receiving) {
    receivedBlocks := 0.U
    totalBlocks := 0.U
  }.elsewhen(io.input.fire) {
    val index = blockIndex(receivedBlocks)
    quantizedAc(index) := quantizer.io.output.bits.quantizedAc
    quantizedDc(index) := quantizer.io.output.bits.quantizedDc
    numNonzeros(index) := quantizer.io.output.bits.numNonzeros
    rawQuant(index) := io.input.bits.quant
    ytox(index) := io.input.bits.ytox
    ytob(index) := io.input.bits.ytob

    when(receivedBlocks === 0.U) {
      latchedConfig := io.config
      totalBlocks := nextTotalBlocks(blockCountBits - 1, 0)
    }

    val nextReceived = receivedBlocks + 1.U
    receivedBlocks := nextReceived
    when(nextReceived === activeTotalBlocks) {
      state := feedDc
      dcSample := 0.U
      metadataBlock := 0.U
      acBlock := 0.U
      emitIndex := 0.U
    }
  }

  when(state === feedDc && dcTokens.io.input.fire) {
    val nextSample = dcSample + 1.U
    dcSample := nextSample
    when(nextSample === totalDcSamples) {
      state := feedMetadata
      metadataBlock := 0.U
    }
  }

  when(state === feedMetadata && metadataTokens.io.input.fire) {
    val nextBlock = metadataBlock + 1.U
    metadataBlock := nextBlock
    when(nextBlock === totalBlocks) {
      state := feedAc
      acBlock := 0.U
    }
  }

  when(state === feedAc && acTokens.io.input.fire) {
    val nextBlock = acBlock + 1.U
    acBlock := nextBlock
    when(nextBlock === totalBlocks) {
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
    dcSample := 0.U
    metadataBlock := 0.U
    acBlock := 0.U
    emitIndex := 0.U
  }

  when(io.input.fire && receivedBlocks >= maxBlocks.U) {
    overflow := true.B
  }
}
