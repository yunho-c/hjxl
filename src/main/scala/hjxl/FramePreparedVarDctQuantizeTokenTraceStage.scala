// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** Combines validated quantized VarDCT owners into the complete logical token trace.
  *
  * The input contract is the output contract of `FramePreparedVarDctQuantizeStage`:
  * first blocks only, raster ownership, valid shape metadata, and a final marker
  * on the owner that completes the raster coverage map. The dedicated metadata
  * and AC schedulers recheck that geometry while this layer reconstructs the
  * full DC and encoded-strategy grids required by their preceding trace phases.
  */
class FramePreparedVarDctTokenTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  private val blockDim = HjxlConstants.BlockDim
  private val maxXBlocks = c.maxFrameWidth / blockDim
  private val maxYBlocks = c.maxFrameHeight / blockDim
  private val maxBlocks = maxXBlocks * maxYBlocks
  private val blockBits = math.max(1, log2Ceil(maxBlocks))
  private val countBits = math.max(1, log2Ceil(maxBlocks + 1))
  private val sampleBits = math.max(1, log2Ceil(maxBlocks * 3 + 1))

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new PreparedVarDctQuantizedFrameBlock(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  private def ceilDiv(value: UInt, divisor: Int): UInt =
    (value +& (divisor - 1).U) / divisor.U
  private def blockIndex(value: UInt): UInt = value(blockBits - 1, 0)
  private def blockAt[T <: Data](values: Vec[T], value: UInt): T =
    if (maxBlocks == 1) values(0) else values(blockIndex(value))

  val quantizedDc = Reg(Vec(maxBlocks, Vec(3, SInt(c.traceValueBits.W))))
  val strategyGrid = Reg(Vec(maxBlocks, UInt(3.W)))
  val receiving :: feedDc :: emitDc :: emitStrategy :: emitMetadata :: emitAc :: Nil = Enum(6)
  val state = RegInit(receiving)
  val frameActive = RegInit(false.B)
  val latchedConfig = Reg(new FrameConfig(c))
  val xBlocks = RegInit(0.U(countBits.W))
  val totalBlocks = RegInit(0.U(countBits.W))
  val dcSample = RegInit(0.U(sampleBits.W))
  val emitIndex = RegInit(0.U(countBits.W))
  val localOverflow = RegInit(false.B)

  val nextXBlocks = ceilDiv(io.config.xsize, blockDim)
  val nextYBlocks = ceilDiv(io.config.ysize, blockDim)
  val nextTotalBlocks = nextXBlocks * nextYBlocks
  val configOutOfRange =
    io.config.xsize === 0.U || io.config.ysize === 0.U ||
      io.config.xsize > c.maxFrameWidth.U || io.config.ysize > c.maxFrameHeight.U ||
      nextXBlocks > maxXBlocks.U || nextYBlocks > maxYBlocks.U
  val activeConfig = Mux(frameActive || state =/= receiving, latchedConfig, io.config)
  val activeXBlocks = Mux(frameActive, xBlocks, nextXBlocks)
  val activeTotalBlocks = Mux(frameActive, totalBlocks, nextTotalBlocks)

  val metadata = Module(new FramePreparedVarDctAcMetadataTokenTraceStage(c))
  val ac = Module(new FramePreparedVarDctAcTokenTraceStage(c))
  metadata.io.config := activeConfig
  ac.io.config := activeConfig
  metadata.io.trace.ready := io.trace.ready && state === emitMetadata
  ac.io.trace.ready := io.trace.ready && state === emitAc

  val accepting = state === receiving && !configOutOfRange
  metadata.io.input.valid := accepting && io.input.valid && ac.io.input.ready
  ac.io.input.valid := accepting && io.input.valid && metadata.io.input.ready
  io.input.ready := accepting && metadata.io.input.ready && ac.io.input.ready
  metadata.io.input.bits.blockX := io.input.bits.blockX
  metadata.io.input.bits.blockY := io.input.bits.blockY
  metadata.io.input.bits.blockOrdinal := io.input.bits.blockOrdinal
  metadata.io.input.bits.last := io.input.bits.last
  metadata.io.input.bits.strategy := io.input.bits.result.strategy
  metadata.io.input.bits.rawQuant := io.input.bits.rawQuant
  metadata.io.input.bits.ytox := io.input.bits.ytox
  metadata.io.input.bits.ytob := io.input.bits.ytob
  ac.io.input.bits := io.input.bits

  assert(
    metadata.io.input.fire === ac.io.input.fire,
    "VarDCT metadata and AC schedulers must accept owners atomically"
  )

  val dcTokens = Module(new FramePreparedDcTokenTraceStage(c))
  dcTokens.io.config := activeConfig
  dcTokens.io.trace.ready := io.trace.ready && state === emitDc
  val totalDcSamples = totalBlocks * 3.U
  val dcChannel = Mux(dcSample < totalBlocks, 1.U, Mux(dcSample < totalBlocks * 2.U, 0.U, 2.U))
  val dcBlock = Mux(
    dcSample < totalBlocks,
    dcSample,
    Mux(dcSample < totalBlocks * 2.U, dcSample - totalBlocks, dcSample - totalBlocks * 2.U)
  )
  dcTokens.io.input.valid := state === feedDc
  dcTokens.io.input.bits := blockAt(quantizedDc, dcBlock)(dcChannel)

  val strategyTrace = Wire(new StageTrace(c))
  strategyTrace.stage := TraceStage.AcStrategy.U
  strategyTrace.group := 0.U
  strategyTrace.index := emitIndex
  strategyTrace.value := Cat(0.U(1.W), blockAt(strategyGrid, emitIndex)).asSInt.pad(c.traceValueBits)

  io.trace.valid := MuxCase(
    false.B,
    Seq(
      (state === emitDc) -> dcTokens.io.trace.valid,
      (state === emitStrategy) -> true.B,
      (state === emitMetadata) -> metadata.io.trace.valid,
      (state === emitAc) -> ac.io.trace.valid
    )
  )
  io.trace.bits := MuxCase(
    0.U.asTypeOf(new StageTrace(c)),
    Seq(
      (state === emitDc) -> dcTokens.io.trace.bits,
      (state === emitStrategy) -> strategyTrace,
      (state === emitMetadata) -> metadata.io.trace.bits,
      (state === emitAc) -> ac.io.trace.bits
    )
  )
  io.traceLast := state === emitAc && ac.io.traceLast
  io.busy :=
    state =/= receiving || frameActive || dcTokens.io.busy || metadata.io.busy || ac.io.busy
  io.overflow :=
    localOverflow || configOutOfRange || dcTokens.io.overflow || metadata.io.overflow || ac.io.overflow

  when(configOutOfRange && state === receiving) {
    localOverflow := true.B
  }

  when(io.input.fire) {
    assert(
      metadata.io.input.fire && ac.io.input.fire,
      "an accepted VarDCT owner must reach both owning frame stores"
    )
    val ownerOrdinal = io.input.bits.blockOrdinal
    val strategy = io.input.bits.result.strategy
    val isVertical = strategy === AcStrategyCode.Dct16x8.U
    val isHorizontal = strategy === AcStrategyCode.Dct8x16.U
    val isRectangular = isVertical || isHorizontal
    val secondOrdinal = Mux(isVertical, ownerOrdinal + activeXBlocks, ownerOrdinal + 1.U)
    val encodedOwner = Cat(strategy, 1.U(1.W))
    val encodedContinuation = Cat(strategy, 0.U(1.W))

    when(!frameActive) {
      frameActive := true.B
      latchedConfig := io.config
      xBlocks := nextXBlocks(countBits - 1, 0)
      totalBlocks := nextTotalBlocks(countBits - 1, 0)
    }
    for (channel <- 0 until 3) {
      blockAt(quantizedDc, ownerOrdinal)(channel) := io.input.bits.result.quantizedDc(channel)(0)
      when(isRectangular) {
        blockAt(quantizedDc, secondOrdinal)(channel) := io.input.bits.result.quantizedDc(channel)(1)
      }
    }
    blockAt(strategyGrid, ownerOrdinal) := encodedOwner
    when(isRectangular) {
      blockAt(strategyGrid, secondOrdinal) := encodedContinuation
    }

    when(io.input.bits.last) {
      state := feedDc
      dcSample := 0.U
      emitIndex := 0.U
    }
  }

  when(state === feedDc && dcTokens.io.input.fire) {
    val nextSample = dcSample + 1.U
    dcSample := nextSample
    when(nextSample === totalDcSamples) {
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
  when(state === emitMetadata && !metadata.io.busy && !metadata.io.trace.valid) {
    state := emitAc
    emitIndex := 0.U
  }
  when(state === emitAc && !ac.io.busy && !ac.io.trace.valid) {
    state := receiving
    frameActive := false.B
    xBlocks := 0.U
    totalBlocks := 0.U
    dcSample := 0.U
    emitIndex := 0.U
  }
}

/** Direct prepared VarDCT quantize-to-token composition. */
class FramePreparedVarDctQuantizeTokenTraceStage(
    c: HjxlConfig = HjxlConfig(),
    coefficientFractionBits: Int = 0
) extends Module {
  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new PreparedVarDctFrameBlock(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val quantizer = Module(new FramePreparedVarDctQuantizeStage(c, coefficientFractionBits))
  val tokens = Module(new FramePreparedVarDctTokenTraceStage(c))
  quantizer.io.config := io.config
  tokens.io.config := io.config
  quantizer.io.input <> io.input
  tokens.io.input <> quantizer.io.output
  io.trace <> tokens.io.trace
  io.traceLast := tokens.io.traceLast
  io.busy := quantizer.io.busy || tokens.io.busy
  io.overflow := quantizer.io.overflow || tokens.io.overflow
}
