// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** Emits all fixed all-DCT logical token trace streams from prepared token data.
  *
  * The caller first supplies prepared quantized DC samples in libjxl-tiny token
  * order, then prepared quantized AC blocks in raster order. After both inputs
  * are buffered by the exact prepared DC/AC schedulers, this module emits the
  * trace streams consumed by the host assembly boundary: DC tokens, AC strategy
  * grid, AC metadata tokens, and AC coefficient tokens.
  */
class FramePreparedTokenTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  private val blockDim = HjxlConstants.BlockDim
  private val tileDim = HjxlConstants.TileDim
  private val maxXBlocks = c.maxFrameWidth / blockDim
  private val maxYBlocks = c.maxFrameHeight / blockDim
  private val maxBlocks = maxXBlocks * maxYBlocks
  private val maxSamples = maxBlocks * 3
  private val countBits = log2Ceil(maxSamples + 1)
  private val widthBits = log2Ceil(c.maxFrameWidth + 1)
  private val heightBits = log2Ceil(c.maxFrameHeight + 1)

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val dcInput = Flipped(Decoupled(SInt(c.traceValueBits.W)))
    val acInput = Flipped(Decoupled(new PreparedAcBlockTraceInput(c)))
    val trace = Decoupled(new StageTrace(c))
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val receiveDc :: receiveAc :: emitDc :: emitStrategy :: emitMetadata :: emitAc :: Nil = Enum(6)
  val state = RegInit(receiveDc)
  val dcReceived = RegInit(0.U(countBits.W))
  val acReceived = RegInit(0.U(countBits.W))
  val emitIndex = RegInit(0.U(32.W))
  val latchedConfig = Reg(new FrameConfig(c))
  val transactionActive = state =/= receiveDc || dcReceived =/= 0.U
  val activeConfig = Wire(new FrameConfig(c))
  activeConfig := Mux(transactionActive, latchedConfig, io.config)

  private def ceilDiv(value: UInt, divisor: Int): UInt =
    (value + (divisor - 1).U) / divisor.U

  private def ceilToBlock(value: UInt): UInt =
    ceilDiv(value, blockDim) * blockDim.U

  val configWidth = activeConfig.xsize(widthBits - 1, 0)
  val configHeight = activeConfig.ysize(heightBits - 1, 0)
  val paddedWidth = ceilToBlock(configWidth)(widthBits - 1, 0)
  val paddedHeight = ceilToBlock(configHeight)(heightBits - 1, 0)
  val xBlocks = paddedWidth >> log2Ceil(blockDim)
  val yBlocks = paddedHeight >> log2Ceil(blockDim)
  val totalBlocks = xBlocks * yBlocks
  val totalDcSamples = totalBlocks * 3.U
  val xTilesRaw = ceilDiv(configWidth, tileDim)
  val yTilesRaw = ceilDiv(configHeight, tileDim)
  val xTiles = Mux(xTilesRaw === 0.U, 1.U, xTilesRaw)
  val yTiles = Mux(yTilesRaw === 0.U, 1.U, yTilesRaw)
  val totalTiles = xTiles * yTiles
  val metadataTokenCount = totalTiles * 2.U + totalBlocks * 3.U
  val configOutOfRange =
    activeConfig.xsize === 0.U || activeConfig.ysize === 0.U ||
      activeConfig.xsize > c.maxFrameWidth.U || activeConfig.ysize > c.maxFrameHeight.U ||
      paddedWidth > c.maxFrameWidth.U || paddedHeight > c.maxFrameHeight.U

  val dcTokens = Module(new FramePreparedDcTokenTraceStage(c))
  dcTokens.io.config := activeConfig
  dcTokens.io.input.bits := io.dcInput.bits
  dcTokens.io.input.valid := io.dcInput.valid && state === receiveDc
  dcTokens.io.trace.ready := io.trace.ready && state === emitDc

  val acTokens = Module(new FramePreparedAcTokenTraceStage(c))
  acTokens.io.config := activeConfig
  acTokens.io.input.bits := io.acInput.bits
  acTokens.io.input.valid := io.acInput.valid && state === receiveAc
  acTokens.io.trace.ready := io.trace.ready && state === emitAc

  val strategyTrace = Wire(new StageTrace(c))
  strategyTrace.stage := TraceStage.AcStrategy.U
  strategyTrace.group := 0.U
  strategyTrace.index := emitIndex
  strategyTrace.value := AcStrategyCode.encoded(AcStrategyCode.Dct, isFirstBlock = true).S

  val cflTokenCount = totalTiles * 2.U
  val strategyStart = cflTokenCount
  val quantStart = strategyStart + totalBlocks
  val blockMetadataStart = quantStart + totalBlocks
  val isCfl = emitIndex < strategyStart
  val isStrategy = emitIndex >= strategyStart && emitIndex < quantStart
  val isQuant = emitIndex >= quantStart && emitIndex < blockMetadataStart
  val cflMap = emitIndex / Mux(totalTiles === 0.U, 1.U, totalTiles)
  val quantOrdinal = emitIndex - quantStart
  val selectedRawQuant = Mux(
    activeConfig.fixedRawQuant === 0.U,
    QuantizeDct8x8Block.DefaultRawQuant.U,
    activeConfig.fixedRawQuant
  )
  val selectedQuantField = selectedRawQuant - 1.U
  val quantLeft = Mux(quantOrdinal === 0.U, Tokenize.DctStrategyCode.U, selectedQuantField)
  val quantCurrent = Cat(0.U(1.W), selectedQuantField).asSInt
  val quantResidual = quantCurrent - Cat(0.U(1.W), quantLeft).asSInt

  val metadataTrace = Wire(new StageTrace(c))
  metadataTrace.stage := TraceStage.AcMetadataTokens.U
  metadataTrace.group := emitIndex(c.groupBits - 1, 0)
  metadataTrace.index := Mux(
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
  metadataTrace.value := MuxCase(
    blockMetadataValue,
    Seq(
      isCfl -> cflOrStrategyValue,
      isStrategy -> cflOrStrategyValue,
      isQuant -> quantValue
    )
  )

  io.dcInput.ready := state === receiveDc && dcTokens.io.input.ready && !configOutOfRange
  io.acInput.ready := state === receiveAc && acTokens.io.input.ready && !configOutOfRange
  io.trace.valid := MuxCase(
    false.B,
    Seq(
      (state === emitDc) -> dcTokens.io.trace.valid,
      (state === emitStrategy) -> true.B,
      (state === emitMetadata) -> true.B,
      (state === emitAc) -> acTokens.io.trace.valid
    )
  )
  io.trace.bits := MuxCase(
    0.U.asTypeOf(new StageTrace(c)),
    Seq(
      (state === emitDc) -> dcTokens.io.trace.bits,
      (state === emitStrategy) -> strategyTrace,
      (state === emitMetadata) -> metadataTrace,
      (state === emitAc) -> acTokens.io.trace.bits
    )
  )
  io.busy := state =/= receiveDc || dcReceived =/= 0.U || acReceived =/= 0.U
  io.overflow := configOutOfRange || dcTokens.io.overflow || acTokens.io.overflow

  when(configOutOfRange) {
    state := receiveDc
    dcReceived := 0.U
    acReceived := 0.U
    emitIndex := 0.U
  }.elsewhen(io.dcInput.fire) {
    when(dcReceived === 0.U) {
      latchedConfig := io.config
    }
    val nextDcReceived = dcReceived + 1.U
    dcReceived := nextDcReceived
    when(nextDcReceived === totalDcSamples) {
      state := receiveAc
      acReceived := 0.U
    }
  }.elsewhen(io.acInput.fire) {
    val nextAcReceived = acReceived + 1.U
    acReceived := nextAcReceived
    when(nextAcReceived === totalBlocks) {
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

  when(state === emitMetadata && io.trace.fire) {
    val nextEmit = emitIndex + 1.U
    emitIndex := nextEmit
    when(nextEmit === metadataTokenCount) {
      state := emitAc
      emitIndex := 0.U
    }
  }

  when(state === emitAc && !acTokens.io.busy && !acTokens.io.trace.valid) {
    state := receiveDc
    dcReceived := 0.U
    acReceived := 0.U
    emitIndex := 0.U
  }
}
