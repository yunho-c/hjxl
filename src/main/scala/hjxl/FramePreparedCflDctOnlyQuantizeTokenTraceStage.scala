// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** Quantizes prepared DCT-only blocks with estimated CFL maps and emits tokens.
  *
  * This is the estimated-CFL counterpart to
  * `FramePreparedDctOnlyQuantizeTokenTraceStage`. Input blocks are supplied in
  * raster order. The stage estimates tile CFL maps from prepared coefficients,
  * quantizes blocks with those maps, then emits DC, strategy, AC-metadata, and
  * AC token traces in the host-assembler order.
  */
class FramePreparedCflDctOnlyQuantizeTokenTraceStage(
    c: HjxlConfig = HjxlConfig(),
    coefficientFractionBitsOverride: Option[Int] = None
) extends Module {
  private val activeCoefficientFractionBits =
    coefficientFractionBitsOverride.getOrElse(c.preparedDctCoefficientFractionBits)
  require(activeCoefficientFractionBits > 0, "coefficientFractionBits must be positive")
  private val blockDim = HjxlConstants.BlockDim
  private val blockSize = blockDim * blockDim
  private val tileDim = HjxlConstants.TileDim
  private val tileBlocks = tileDim / blockDim
  private val maxXBlocks = c.maxFrameWidth / blockDim
  private val maxYBlocks = c.maxFrameHeight / blockDim
  private val maxBlocks = maxXBlocks * maxYBlocks
  private val maxXTiles = (maxXBlocks + tileBlocks - 1) / tileBlocks
  private val maxYTiles = (maxYBlocks + tileBlocks - 1) / tileBlocks
  private val maxTiles = maxXTiles * maxYTiles
  private val blockCountBits = log2Ceil(maxBlocks + 1)
  private val blockIndexBits = math.max(1, log2Ceil(maxBlocks))
  private val tileIndexBits = math.max(1, log2Ceil(maxTiles))
  private val sampleCountBits = log2Ceil(maxBlocks * 3 + 1)
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

  private def ceilDiv(value: UInt, divisor: Int): UInt =
    (value +& (divisor - 1).U) / divisor.U

  private def blockIndex(value: UInt): UInt =
    value(blockIndexBits - 1, 0)

  private def tileIndex(value: UInt): UInt =
    value(tileIndexBits - 1, 0)

  private def ytoxAt(value: UInt): SInt =
    if (maxTiles == 1) ytox(0) else ytox(tileIndex(value))

  private def ytobAt(value: UInt): SInt =
    if (maxTiles == 1) ytob(0) else ytob(tileIndex(value))

  val coefficients = Reg(Vec(maxBlocks, Vec(3, Vec(blockSize, SInt(c.traceValueBits.W)))))
  val quant = Reg(Vec(maxBlocks, UInt(8.W)))
  val scaleQ16 = Reg(Vec(maxBlocks, UInt(16.W)))
  val invQacQ16 = Reg(Vec(maxBlocks, UInt(32.W)))
  val invDcFactorQ16 = Reg(Vec(maxBlocks, Vec(3, UInt(32.W))))
  val xQmMultiplierQ16 = Reg(Vec(maxBlocks, UInt(32.W)))
  val ytox = Reg(Vec(maxTiles, SInt(8.W)))
  val ytob = Reg(Vec(maxTiles, SInt(8.W)))
  val quantizedAc = Reg(Vec(maxBlocks, Vec(3, Vec(blockSize, SInt(c.traceValueBits.W)))))
  val quantizedDc = Reg(Vec(maxBlocks, Vec(3, SInt(c.traceValueBits.W))))
  val numNonzeros = Reg(Vec(maxBlocks, Vec(3, UInt(8.W))))

  private def coefficientsAt(value: UInt) =
    if (maxBlocks == 1) coefficients(0) else coefficients(blockIndex(value))

  private def quantAt(value: UInt): UInt =
    if (maxBlocks == 1) quant(0) else quant(blockIndex(value))

  private def scaleAt(value: UInt): UInt =
    if (maxBlocks == 1) scaleQ16(0) else scaleQ16(blockIndex(value))

  private def inverseAcAt(value: UInt): UInt =
    if (maxBlocks == 1) invQacQ16(0) else invQacQ16(blockIndex(value))

  private def inverseDcAt(value: UInt) =
    if (maxBlocks == 1) invDcFactorQ16(0) else invDcFactorQ16(blockIndex(value))

  private def xMultiplierAt(value: UInt): UInt =
    if (maxBlocks == 1) xQmMultiplierQ16(0) else xQmMultiplierQ16(blockIndex(value))

  private def quantizedDcAt(block: UInt, channel: UInt): SInt =
    if (maxBlocks == 1) quantizedDc(0)(channel) else quantizedDc(blockIndex(block))(channel)

  private def quantizedAcAt(value: UInt) =
    if (maxBlocks == 1) quantizedAc(0) else quantizedAc(blockIndex(value))

  private def nonzerosAt(value: UInt) =
    if (maxBlocks == 1) numNonzeros(0) else numNonzeros(blockIndex(value))

  val states = Enum(10)
  val receiving :: captureCfl :: quantizeBlocks :: feedDc :: feedMetadata :: feedAc :: emitDc :: emitStrategy :: emitMetadata :: emitAc :: Nil = states
  val state = RegInit(receiving)
  val receivedBlocks = RegInit(0.U(blockCountBits.W))
  val totalBlocks = RegInit(0.U(blockCountBits.W))
  val quantizeBlock = RegInit(0.U(blockCountBits.W))
  val dcSample = RegInit(0.U(sampleCountBits.W))
  val metadataBlock = RegInit(0.U(blockCountBits.W))
  val acBlock = RegInit(0.U(blockCountBits.W))
  val emitIndex = RegInit(0.U(32.W))
  val xBlocks = RegInit(0.U(32.W))
  val xTiles = RegInit(0.U(32.W))
  val latchedConfig = Reg(new FrameConfig(c))

  val transactionActive = state =/= receiving || receivedBlocks =/= 0.U
  val activeConfig = Wire(new FrameConfig(c))
  activeConfig := Mux(transactionActive, latchedConfig, io.config)

  val configWidth = activeConfig.xsize(widthBits - 1, 0)
  val configHeight = activeConfig.ysize(heightBits - 1, 0)
  val nextXBlocks = ceilDiv(configWidth, blockDim)
  val nextYBlocks = ceilDiv(configHeight, blockDim)
  val nextTotalBlocks = nextXBlocks * nextYBlocks
  val nextXTilesRaw = ceilDiv(configWidth, tileDim)
  val nextYTilesRaw = ceilDiv(configHeight, tileDim)
  val nextXTiles = Mux(nextXTilesRaw === 0.U, 1.U, nextXTilesRaw)
  val nextYTiles = Mux(nextYTilesRaw === 0.U, 1.U, nextYTilesRaw)
  val activeTotalBlocks = Mux(receivedBlocks === 0.U && state === receiving, nextTotalBlocks, totalBlocks)
  val totalDcSamples = activeTotalBlocks * 3.U
  val metadataTokenCount = nextXTiles * nextYTiles * 2.U + activeTotalBlocks * 3.U
  val configOutOfRange =
    activeConfig.xsize === 0.U || activeConfig.ysize === 0.U ||
      activeConfig.xsize > c.maxFrameWidth.U || activeConfig.ysize > c.maxFrameHeight.U ||
      nextXBlocks > maxXBlocks.U || nextYBlocks > maxYBlocks.U ||
      nextXTiles > maxXTiles.U || nextYTiles > maxYTiles.U

  val cflMaps = Module(
    new FramePreparedCflMapTraceStage(c, Some(activeCoefficientFractionBits))
  )
  cflMaps.io.config := activeConfig
  cflMaps.io.input.valid :=
    state === receiving && io.input.valid && !configOutOfRange && receivedBlocks < nextTotalBlocks
  cflMaps.io.input.bits := io.input.bits
  cflMaps.io.trace.ready := state === captureCfl

  val quantizer = Module(new DctOnlyQuantizeBlock(c, activeCoefficientFractionBits))
  val quantizeTile = CflTileGeometry.blockTile(quantizeBlock, xBlocks, xTiles)
  quantizer.io.input.valid := state === quantizeBlocks
  quantizer.io.input.bits.coefficients := coefficientsAt(quantizeBlock)
  quantizer.io.input.bits.quant := quantAt(quantizeBlock)
  quantizer.io.input.bits.scaleQ16 := scaleAt(quantizeBlock)
  quantizer.io.input.bits.invQacQ16 := inverseAcAt(quantizeBlock)
  quantizer.io.input.bits.invDcFactorQ16 := inverseDcAt(quantizeBlock)
  quantizer.io.input.bits.xQmMultiplierQ16 := xMultiplierAt(quantizeBlock)
  quantizer.io.input.bits.ytox := ytoxAt(quantizeTile)
  quantizer.io.input.bits.ytob := ytobAt(quantizeTile)
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
  dcTokens.io.input.bits := quantizedDcAt(dcBlock, dcChannel)

  val metadataTile = CflTileGeometry.blockTile(metadataBlock, xBlocks, xTiles)
  metadataTokens.io.input.valid := state === feedMetadata
  metadataTokens.io.input.bits.rawQuant := quantAt(metadataBlock)
  metadataTokens.io.input.bits.ytox := ytoxAt(metadataTile)
  metadataTokens.io.input.bits.ytob := ytobAt(metadataTile)

  acTokens.io.input.valid := state === feedAc
  acTokens.io.input.bits.numNonzeros := nonzerosAt(acBlock)
  acTokens.io.input.bits.quantized := quantizedAcAt(acBlock)

  val strategyTrace = Wire(new StageTrace(c))
  strategyTrace.stage := TraceStage.AcStrategy.U
  strategyTrace.group := 0.U
  strategyTrace.index := emitIndex
  strategyTrace.value := AcStrategyCode.encoded(AcStrategyCode.Dct, isFirstBlock = true).S

  io.input.ready :=
    state === receiving &&
      !configOutOfRange &&
      receivedBlocks < nextTotalBlocks &&
      cflMaps.io.input.ready
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
    state =/= receiving || receivedBlocks =/= 0.U || cflMaps.io.busy ||
      dcTokens.io.busy || metadataTokens.io.busy || acTokens.io.busy
  io.overflow :=
    configOutOfRange || cflMaps.io.overflow || dcTokens.io.overflow ||
      metadataTokens.io.overflow || acTokens.io.overflow

  when(configOutOfRange && state === receiving) {
    receivedBlocks := 0.U
    totalBlocks := 0.U
  }.elsewhen(io.input.fire) {
    val index = blockIndex(receivedBlocks)
    if (maxBlocks == 1) {
      coefficients(0) := io.input.bits.coefficients
      quant(0) := io.input.bits.quant
      scaleQ16(0) := io.input.bits.scaleQ16
      invQacQ16(0) := io.input.bits.invQacQ16
      invDcFactorQ16(0) := io.input.bits.invDcFactorQ16
      xQmMultiplierQ16(0) := io.input.bits.xQmMultiplierQ16
    } else {
      coefficients(index) := io.input.bits.coefficients
      quant(index) := io.input.bits.quant
      scaleQ16(index) := io.input.bits.scaleQ16
      invQacQ16(index) := io.input.bits.invQacQ16
      invDcFactorQ16(index) := io.input.bits.invDcFactorQ16
      xQmMultiplierQ16(index) := io.input.bits.xQmMultiplierQ16
    }

    when(receivedBlocks === 0.U) {
      latchedConfig := io.config
      totalBlocks := nextTotalBlocks(blockCountBits - 1, 0)
      xBlocks := nextXBlocks
      xTiles := nextXTiles
    }

    val nextReceived = receivedBlocks + 1.U
    receivedBlocks := nextReceived
    when(nextReceived === nextTotalBlocks) {
      state := captureCfl
    }
  }

  when(cflMaps.io.trace.fire) {
    when(cflMaps.io.trace.bits.stage === TraceStage.YtoxMap.U) {
      if (maxTiles == 1) {
        ytox(0) := cflMaps.io.trace.bits.value(7, 0).asSInt
      } else {
        ytox(tileIndex(cflMaps.io.trace.bits.index)) := cflMaps.io.trace.bits.value(7, 0).asSInt
      }
    }.elsewhen(cflMaps.io.trace.bits.stage === TraceStage.YtobMap.U) {
      if (maxTiles == 1) {
        ytob(0) := cflMaps.io.trace.bits.value(7, 0).asSInt
      } else {
        ytob(tileIndex(cflMaps.io.trace.bits.index)) := cflMaps.io.trace.bits.value(7, 0).asSInt
      }
    }
    when(cflMaps.io.traceLast) {
      state := quantizeBlocks
      quantizeBlock := 0.U
    }
  }

  when(state === quantizeBlocks && quantizer.io.input.fire) {
    if (maxBlocks == 1) {
      quantizedAc(0) := quantizer.io.output.bits.quantizedAc
      quantizedDc(0) := quantizer.io.output.bits.quantizedDc
      numNonzeros(0) := quantizer.io.output.bits.numNonzeros
    } else {
      val index = blockIndex(quantizeBlock)
      quantizedAc(index) := quantizer.io.output.bits.quantizedAc
      quantizedDc(index) := quantizer.io.output.bits.quantizedDc
      numNonzeros(index) := quantizer.io.output.bits.numNonzeros
    }
    val nextBlock = quantizeBlock + 1.U
    quantizeBlock := nextBlock
    when(nextBlock === totalBlocks) {
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
    quantizeBlock := 0.U
    dcSample := 0.U
    metadataBlock := 0.U
    acBlock := 0.U
    emitIndex := 0.U
    xBlocks := 0.U
    xTiles := 0.U
  }
}
