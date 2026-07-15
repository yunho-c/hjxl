// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** One raster 8x8 block at the prepared frame-strategy boundary.
  *
  * Q12 XYB samples are retained for candidate scoring. By default their
  * matching Q12 ordinary-DCT coefficients are reused for CFL estimation and
  * selected-owner quantization; an optional higher-precision sideband can
  * replace only those downstream values. AQ and mask values remain per-block
  * inputs whose covered maxima feed candidate scoring. `rawQuant` is the
  * pre-strategy byte; after searching the frame, the scheduler applies
  * `adjust_quant_field` to it.
  */
class PreparedAcStrategyFrameBlock(
    c: HjxlConfig,
    quantizationCoefficientFractionBits: Int = Dct8Approx.FractionBits
) extends Bundle {
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim
  require(
    quantizationCoefficientFractionBits >= Dct8Approx.FractionBits,
    "prepared AC-strategy quantization precision cannot be below Q12"
  )

  val xyb = Vec(3, Vec(blockSize, SInt(c.traceValueBits.W)))
  val dct8x8 = Vec(3, Vec(blockSize, SInt(c.traceValueBits.W)))
  val quantizationXyb =
    if (quantizationCoefficientFractionBits > Dct8Approx.FractionBits)
      Some(Vec(3, Vec(blockSize, SInt(c.traceValueBits.W))))
    else None
  val quantizationDct8x8 =
    if (quantizationCoefficientFractionBits > Dct8Approx.FractionBits)
      Some(Vec(3, Vec(blockSize, SInt(c.traceValueBits.W))))
    else None
  val aqMapQ24 = UInt(AqFinalModulationFixedPoint.ValueBits.W)
  val strategyMaskQ16 = UInt(AqStrategyMaskFixedPoint.ValueBits.W)
  val rawQuant = UInt(8.W)
  val distanceQ8 = UInt(16.W)
  val scaleQ16 = UInt(16.W)
  val fixedInvQacQ16 = UInt(32.W)
  val adaptiveRawQuant = Bool()
  val invDcFactorQ16 = Vec(3, UInt(32.W))
  val xQmMultiplierQ16 = UInt(32.W)
  val last = Bool()
}

/** One raster cell selected by the prepared AC-strategy scheduler.
  *
  * This sideband is stable whenever the matching strategy trace is valid. It
  * keeps the trace route useful on its own while exposing the already-buffered
  * frame state needed by the downstream first-block owner adapter. For a
  * rectangular first block, `coveredXyb(1)` is the below/right continuation
  * block in transform order; ordinary DCT uses only `coveredXyb(0)` and
  * `dct8x8`.
  */
class PreparedAcStrategySelectedCell(
    c: HjxlConfig,
    quantizationCoefficientFractionBits: Int = Dct8Approx.FractionBits
) extends Bundle {
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim
  require(
    quantizationCoefficientFractionBits >= Dct8Approx.FractionBits,
    "selected AC-strategy quantization precision cannot be below Q12"
  )

  val blockX = UInt(c.coordBits.W)
  val blockY = UInt(c.coordBits.W)
  val blockIndex = UInt(c.groupBits.W)
  val encodedStrategy = UInt(3.W)
  val adjustedRawQuant = UInt(8.W)
  val ownerLast = Bool()
  val distanceQ8 = UInt(16.W)
  val scaleQ16 = UInt(16.W)
  val fixedInvQacQ16 = UInt(32.W)
  val adaptiveRawQuant = Bool()
  val invDcFactorQ16 = Vec(3, UInt(32.W))
  val xQmMultiplierQ16 = UInt(32.W)
  val ytox = SInt(8.W)
  val ytob = SInt(8.W)
  val dct8x8 = Vec(3, Vec(blockSize, SInt(c.traceValueBits.W)))
  val coveredXyb = Vec(2, Vec(3, Vec(blockSize, SInt(c.traceValueBits.W))))
  val quantizationDct8x8 =
    if (quantizationCoefficientFractionBits > Dct8Approx.FractionBits)
      Some(Vec(3, Vec(blockSize, SInt(c.traceValueBits.W))))
    else None
  val quantizationCoveredXyb =
    if (quantizationCoefficientFractionBits > Dct8Approx.FractionBits)
      Some(Vec(2, Vec(3, Vec(blockSize, SInt(c.traceValueBits.W)))))
    else None
}

/** Searches AC strategy for a complete prepared frame and emits a raster map.
  *
  * The scheduler first buffers raster blocks, estimates one CFL pair per
  * 64x64 tile from ordinary-DCT coefficients, then visits tile-local 2x2 block
  * regions in libjxl-tiny order. Every region feeds four 8x8, two 16x8, and two
  * 8x16 candidates through `PreparedAcStrategy2x2Selector`. Incomplete rows or
  * columns at tile edges remain ordinary DCT, matching the reference loop. A
  * final raster pass raises both raw-quant bytes in each selected rectangle to
  * their maximum and emits that byte beside the strategy trace.
  */
class FramePreparedAcStrategyTraceStage(
    c: HjxlConfig = HjxlConfig(),
    quantizationCoefficientFractionBits: Int = Dct8Approx.FractionBits
) extends Module {
  private val blockDim = HjxlConstants.BlockDim
  private val blockSize = blockDim * blockDim
  private val blocksPerTile = HjxlConstants.TileDim / blockDim
  private val maxXBlocks = c.maxFrameWidth / blockDim
  private val maxYBlocks = c.maxFrameHeight / blockDim
  private val maxBlocks = maxXBlocks * maxYBlocks
  private val maxXTiles = (maxXBlocks + blocksPerTile - 1) / blocksPerTile
  private val maxYTiles = (maxYBlocks + blocksPerTile - 1) / blocksPerTile
  private val maxTiles = maxXTiles * maxYTiles
  private val blockIndexBits = math.max(1, log2Ceil(maxBlocks))
  private val tileIndexBits = math.max(1, log2Ceil(maxTiles))
  private val widthBits = log2Ceil(c.maxFrameWidth + 1)
  private val heightBits = log2Ceil(c.maxFrameHeight + 1)
  private val estimatorCoefficientBits = c.traceValueBits + 4
  private val estimatorWeightedBits = math.max(48, estimatorCoefficientBits + 16)
  private val estimatorSumBits = math.max(64, estimatorWeightedBits + 16)

  require(maxBlocks > 0, "prepared AC-strategy frame must contain a block")
  require(maxTiles > 0, "prepared AC-strategy frame must contain a tile")

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(
      Decoupled(new PreparedAcStrategyFrameBlock(c, quantizationCoefficientFractionBits))
    )
    val trace = Decoupled(new StageTrace(c))
    val selected =
      Output(new PreparedAcStrategySelectedCell(c, quantizationCoefficientFractionBits))
    val adjustedRawQuant = Output(UInt(8.W))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
    val unsupportedDistance = Output(Bool())
  })

  private def ceilDivBlock(value: UInt): UInt =
    (value +& (blockDim - 1).U) >> log2Ceil(blockDim)

  private def ceilDivTile(value: UInt): UInt =
    (value +& (HjxlConstants.TileDim - 1).U) / HjxlConstants.TileDim.U

  private def minUInt(a: UInt, b: UInt): UInt = Mux(a < b, a, b)
  private def maxUInt(a: UInt, b: UInt): UInt = Mux(a > b, a, b)

  val xyb = Reg(Vec(maxBlocks, Vec(3, Vec(blockSize, SInt(c.traceValueBits.W)))))
  val dct8x8 = Reg(Vec(maxBlocks, Vec(3, Vec(blockSize, SInt(c.traceValueBits.W)))))
  val quantizationXyb =
    if (quantizationCoefficientFractionBits > Dct8Approx.FractionBits)
      Some(Reg(Vec(maxBlocks, Vec(3, Vec(blockSize, SInt(c.traceValueBits.W))))))
    else None
  val quantizationDct8x8 =
    if (quantizationCoefficientFractionBits > Dct8Approx.FractionBits)
      Some(Reg(Vec(maxBlocks, Vec(3, Vec(blockSize, SInt(c.traceValueBits.W))))))
    else None
  val aqMapQ24 = Reg(Vec(maxBlocks, UInt(AqFinalModulationFixedPoint.ValueBits.W)))
  val strategyMaskQ16 = Reg(Vec(maxBlocks, UInt(AqStrategyMaskFixedPoint.ValueBits.W)))
  val adjustedRawQuant = Reg(Vec(maxBlocks, UInt(8.W)))
  val decisions = Reg(Vec(maxBlocks, UInt(3.W)))
  val ytoxMap = Reg(Vec(maxTiles, SInt(8.W)))
  val ytobMap = Reg(Vec(maxTiles, SInt(8.W)))

  private def blockAddress(index: UInt): UInt =
    if (maxBlocks == 1) 0.U else index(blockIndexBits - 1, 0)

  private def tileAddress(index: UInt): UInt =
    if (maxTiles == 1) 0.U else index(tileIndexBits - 1, 0)

  private def storedXyb(block: UInt, channel: Int, sample: Int): SInt =
    xyb(blockAddress(block))(channel)(sample)

  private def storedDct(block: UInt, channel: Int, coefficient: UInt): SInt =
    dct8x8(blockAddress(block))(channel)(coefficient)

  private def storedQuantizationXyb(block: UInt, channel: Int, sample: Int): SInt =
    quantizationXyb
      .map(_(blockAddress(block))(channel)(sample))
      .getOrElse(storedXyb(block, channel, sample))

  private def storedQuantizationDct(block: UInt, channel: Int, coefficient: UInt): SInt =
    quantizationDct8x8
      .map(_(blockAddress(block))(channel)(coefficient))
      .getOrElse(storedDct(block, channel, coefficient))

  private def storedAq(block: UInt): UInt = aqMapQ24(blockAddress(block))
  private def storedMask(block: UInt): UInt = strategyMaskQ16(blockAddress(block))

  val Seq(
    receiving,
    cflStreaming,
    cflWaiting,
    scanRegion,
    feedCandidates,
    waitDecision,
    adjustingQuant,
    emitting
  ) = Enum(8)
  val state = RegInit(receiving)
  val receivedBlock = RegInit(0.U(32.W))
  val xBlocks = RegInit(0.U(32.W))
  val yBlocks = RegInit(0.U(32.W))
  val xTiles = RegInit(0.U(32.W))
  val totalBlocks = RegInit(0.U(32.W))
  val totalTiles = RegInit(0.U(32.W))
  val activeDistanceQ8 = RegInit(0.U(16.W))
  val activeScaleQ16 = RegInit(0.U(16.W))
  val activeFixedInvQacQ16 = RegInit(0.U(32.W))
  val activeAdaptiveRawQuant = RegInit(false.B)
  val activeInvDcFactorQ16 = Reg(Vec(3, UInt(32.W)))
  val activeXQmMultiplierQ16 = RegInit(0.U(32.W))
  val tileOrdinal = RegInit(0.U(32.W))
  val tileSampleOrdinal = RegInit(0.U(32.W))
  val regionX = RegInit(0.U(32.W))
  val regionY = RegInit(0.U(32.W))
  val candidateIndex = RegInit(0.U(3.W))
  val adjustIndex = RegInit(0.U(32.W))
  val adjustX = RegInit(0.U(32.W))
  val emitIndex = RegInit(0.U(32.W))
  val protocolOverflow = RegInit(false.B)
  val arithmeticOverflow = RegInit(false.B)
  val unsupportedDistance = RegInit(false.B)

  val configWidth = io.config.xsize(widthBits - 1, 0)
  val configHeight = io.config.ysize(heightBits - 1, 0)
  val nextXBlocks = ceilDivBlock(configWidth)
  val nextYBlocks = ceilDivBlock(configHeight)
  val nextTotalBlocks = nextXBlocks * nextYBlocks
  val nextXTilesRaw = ceilDivTile(configWidth)
  val nextYTilesRaw = ceilDivTile(configHeight)
  val nextXTiles = Mux(nextXTilesRaw === 0.U, 1.U, nextXTilesRaw)
  val nextYTiles = Mux(nextYTilesRaw === 0.U, 1.U, nextYTilesRaw)
  val configOutOfRange =
    io.config.xsize === 0.U || io.config.ysize === 0.U ||
      io.config.xsize > c.maxFrameWidth.U || io.config.ysize > c.maxFrameHeight.U ||
      nextXBlocks > maxXBlocks.U || nextYBlocks > maxYBlocks.U ||
      nextXTiles > maxXTiles.U || nextYTiles > maxYTiles.U

  val acceptingFirstBlock = state === receiving && receivedBlock === 0.U
  val selectedTotalBlocks = Mux(acceptingFirstBlock, nextTotalBlocks, totalBlocks)
  val inputDistance = Module(new AcStrategyCostParamsLookup)
  inputDistance.io.distanceQ8 := io.input.bits.distanceQ8
  io.input.ready :=
    state === receiving &&
      (!acceptingFirstBlock || !configOutOfRange) &&
      receivedBlock < selectedTotalBlocks &&
      receivedBlock < maxBlocks.U

  when(io.input.fire) {
    val store = blockAddress(receivedBlock)
    for (channel <- 0 until 3; sample <- 0 until blockSize) {
      xyb(store)(channel)(sample) := io.input.bits.xyb(channel)(sample)
      dct8x8(store)(channel)(sample) := io.input.bits.dct8x8(channel)(sample)
      quantizationXyb.foreach(
        _(store)(channel)(sample) := io.input.bits.quantizationXyb.get(channel)(sample)
      )
      quantizationDct8x8.foreach(
        _(store)(channel)(sample) := io.input.bits.quantizationDct8x8.get(channel)(sample)
      )
    }
    aqMapQ24(store) := io.input.bits.aqMapQ24
    strategyMaskQ16(store) := io.input.bits.strategyMaskQ16
    adjustedRawQuant(store) := io.input.bits.rawQuant
    decisions(store) := AcStrategyCode.encoded(AcStrategyCode.Dct, isFirstBlock = true).U

    val expectedLast = receivedBlock === selectedTotalBlocks - 1.U
    assert(io.input.bits.last === expectedLast, "prepared AC-strategy block TLAST mismatch")
    when(io.input.bits.last =/= expectedLast) {
      protocolOverflow := true.B
    }
    when(acceptingFirstBlock) {
      xBlocks := nextXBlocks
      yBlocks := nextYBlocks
      xTiles := nextXTiles
      totalBlocks := nextTotalBlocks
      totalTiles := nextXTiles * nextYTiles
      activeDistanceQ8 := io.input.bits.distanceQ8
      activeScaleQ16 := io.input.bits.scaleQ16
      activeFixedInvQacQ16 := io.input.bits.fixedInvQacQ16
      activeAdaptiveRawQuant := io.input.bits.adaptiveRawQuant
      activeInvDcFactorQ16 := io.input.bits.invDcFactorQ16
      activeXQmMultiplierQ16 := io.input.bits.xQmMultiplierQ16
      unsupportedDistance := !inputDistance.io.supported
    }.otherwise {
      assert(
        io.input.bits.distanceQ8 === activeDistanceQ8,
        "prepared AC-strategy distance changed within a frame"
      )
      assert(
        io.input.bits.scaleQ16 === activeScaleQ16 &&
          io.input.bits.fixedInvQacQ16 === activeFixedInvQacQ16 &&
          io.input.bits.adaptiveRawQuant === activeAdaptiveRawQuant &&
          io.input.bits.invDcFactorQ16.asUInt === activeInvDcFactorQ16.asUInt &&
          io.input.bits.xQmMultiplierQ16 === activeXQmMultiplierQ16,
        "prepared AC-strategy quantization parameters changed within a frame"
      )
    }

    when(expectedLast) {
      receivedBlock := 0.U
      tileOrdinal := 0.U
      tileSampleOrdinal := 0.U
      state := cflStreaming
    }.otherwise {
      receivedBlock := receivedBlock + 1.U
    }
  }

  val xTilesSafe = Mux(xTiles === 0.U, 1.U, xTiles)
  val tileX = tileOrdinal - (tileOrdinal / xTilesSafe) * xTilesSafe
  val tileY = tileOrdinal / xTilesSafe
  val tileBaseBlockX = tileX * blocksPerTile.U
  val tileBaseBlockY = tileY * blocksPerTile.U
  val remainingXBlocks = Mux(xBlocks > tileBaseBlockX, xBlocks - tileBaseBlockX, 0.U)
  val remainingYBlocks = Mux(yBlocks > tileBaseBlockY, yBlocks - tileBaseBlockY, 0.U)
  val tileValidXBlocks = minUInt(remainingXBlocks, blocksPerTile.U)
  val tileValidYBlocks = minUInt(remainingYBlocks, blocksPerTile.U)
  val tileValidXBlocksSafe = Mux(tileValidXBlocks === 0.U, 1.U, tileValidXBlocks)
  val tileSampleCount = tileValidXBlocks * tileValidYBlocks * blockSize.U
  val localBlockOrdinal = tileSampleOrdinal / blockSize.U
  val cflCoefficientIndex = tileSampleOrdinal - localBlockOrdinal * blockSize.U
  val localBlockX =
    localBlockOrdinal - (localBlockOrdinal / tileValidXBlocksSafe) * tileValidXBlocksSafe
  val localBlockY = localBlockOrdinal / tileValidXBlocksSafe
  val cflBlockX = tileBaseBlockX + localBlockX
  val cflBlockY = tileBaseBlockY + localBlockY
  val cflBlockIndex = cflBlockY * xBlocks + cflBlockX
  val cflCoefficientIndexSafe = cflCoefficientIndex(log2Ceil(blockSize) - 1, 0)

  private val quantizationToQ16Shift = 16 - quantizationCoefficientFractionBits

  private def coefficientQ16(value: SInt): SInt =
    if (quantizationToQ16Shift > 0)
      (value << quantizationToQ16Shift).asSInt.pad(estimatorCoefficientBits)
    else if (quantizationToQ16Shift < 0)
      (value >> -quantizationToQ16Shift).asSInt.pad(estimatorCoefficientBits)
    else value.pad(estimatorCoefficientBits)

  val cflEstimator = Module(
    new CflTileCoefficientEstimator(
      coefficientBits = estimatorCoefficientBits,
      weightedBits = estimatorWeightedBits,
      sumBits = estimatorSumBits
    )
  )
  cflEstimator.io.input.valid := state === cflStreaming && tileSampleCount =/= 0.U
  cflEstimator.io.input.bits.coefficientIndex := cflCoefficientIndexSafe
  cflEstimator.io.input.bits.yCoeffQ16 :=
    coefficientQ16(storedQuantizationDct(cflBlockIndex, 1, cflCoefficientIndexSafe))
  cflEstimator.io.input.bits.xCoeffQ16 :=
    coefficientQ16(storedQuantizationDct(cflBlockIndex, 0, cflCoefficientIndexSafe))
  cflEstimator.io.input.bits.bCoeffQ16 :=
    coefficientQ16(storedQuantizationDct(cflBlockIndex, 2, cflCoefficientIndexSafe))
  cflEstimator.io.input.bits.last := tileSampleOrdinal === tileSampleCount - 1.U
  cflEstimator.io.output.ready := state === cflWaiting

  when(cflEstimator.io.input.fire) {
    when(tileSampleOrdinal === tileSampleCount - 1.U) {
      tileSampleOrdinal := 0.U
      state := cflWaiting
    }.otherwise {
      tileSampleOrdinal := tileSampleOrdinal + 1.U
    }
  }

  when(cflEstimator.io.output.fire) {
    val tileStore = tileAddress(tileOrdinal)
    ytoxMap(tileStore) := cflEstimator.io.output.bits.x.multiplier
    ytobMap(tileStore) := cflEstimator.io.output.bits.b.multiplier
    when(tileOrdinal === totalTiles - 1.U) {
      tileOrdinal := 0.U
      regionX := 0.U
      regionY := 0.U
      state := scanRegion
    }.otherwise {
      tileOrdinal := tileOrdinal + 1.U
      tileSampleOrdinal := 0.U
      state := cflStreaming
    }
  }

  val regionExists = tileValidXBlocks >= 2.U && tileValidYBlocks >= 2.U
  when(state === scanRegion) {
    when(regionExists) {
      candidateIndex := 0.U
      state := feedCandidates
    }.elsewhen(tileOrdinal + 1.U < totalTiles) {
      tileOrdinal := tileOrdinal + 1.U
      regionX := 0.U
      regionY := 0.U
    }.otherwise {
      adjustIndex := 0.U
      adjustX := 0.U
      state := adjustingQuant
    }
  }

  val regionBlockX = tileBaseBlockX + regionX
  val regionBlockY = tileBaseBlockY + regionY
  val topLeftBlock = regionBlockY * xBlocks + regionBlockX
  val topRightBlock = topLeftBlock + 1.U
  val bottomLeftBlock = topLeftBlock + xBlocks
  val bottomRightBlock = bottomLeftBlock + 1.U
  val dctCandidateBlock = MuxLookup(candidateIndex, topLeftBlock)(
    Seq(
      0.U -> topLeftBlock,
      1.U -> topRightBlock,
      2.U -> bottomLeftBlock,
      3.U -> bottomRightBlock
    )
  )

  val verticalTopBlock = Mux(candidateIndex(0), topRightBlock, topLeftBlock)
  val verticalBottomBlock = Mux(candidateIndex(0), bottomRightBlock, bottomLeftBlock)
  val horizontalLeftBlock = Mux(candidateIndex(0), bottomLeftBlock, topLeftBlock)
  val horizontalRightBlock = Mux(candidateIndex(0), bottomRightBlock, topRightBlock)

  val verticalDct = Seq.fill(3)(Module(new Dct16x8Approx(c)))
  val horizontalDct = Seq.fill(3)(Module(new Dct8x16Approx(c)))
  for (channel <- 0 until 3) {
    verticalDct(channel).io.input.valid :=
      state === feedCandidates && candidateIndex >= 4.U && candidateIndex < 6.U
    horizontalDct(channel).io.input.valid :=
      state === feedCandidates && candidateIndex >= 6.U
    for (sample <- 0 until 128) {
      val verticalY = sample / 8
      val verticalX = sample % 8
      val verticalSourceBlock =
        if (verticalY < 8) verticalTopBlock else verticalBottomBlock
      val verticalSourceSample = (verticalY % 8) * 8 + verticalX
      verticalDct(channel).io.input.bits(sample) :=
        storedXyb(verticalSourceBlock, channel, verticalSourceSample)

      val horizontalY = sample / 16
      val horizontalX = sample % 16
      val horizontalSourceBlock =
        if (horizontalX < 8) horizontalLeftBlock else horizontalRightBlock
      val horizontalSourceSample = horizontalY * 8 + (horizontalX % 8)
      horizontalDct(channel).io.input.bits(sample) :=
        storedXyb(horizontalSourceBlock, channel, horizontalSourceSample)
    }
  }

  val selector = Module(new PreparedAcStrategy2x2Selector(c.traceValueBits))
  val verticalValid = verticalDct.map(_.io.output.valid).reduce(_ && _)
  val horizontalValid = horizontalDct.map(_.io.output.valid).reduce(_ && _)
  val selectedCoefficientsValid = Mux(
    candidateIndex < 4.U,
    true.B,
    Mux(candidateIndex < 6.U, verticalValid, horizontalValid)
  )
  selector.io.input.valid := state === feedCandidates && selectedCoefficientsValid
  selector.io.input.bits.strategy := Mux(
    candidateIndex < 4.U,
    AcStrategyCode.Dct.U,
    Mux(candidateIndex < 6.U, AcStrategyCode.Dct16x8.U, AcStrategyCode.Dct8x16.U)
  )
  selector.io.input.bits.distanceQ8 := activeDistanceQ8

  val candidateAq = MuxLookup(candidateIndex, storedAq(topLeftBlock))(
    Seq(
      0.U -> storedAq(topLeftBlock),
      1.U -> storedAq(topRightBlock),
      2.U -> storedAq(bottomLeftBlock),
      3.U -> storedAq(bottomRightBlock),
      4.U -> maxUInt(storedAq(topLeftBlock), storedAq(bottomLeftBlock)),
      5.U -> maxUInt(storedAq(topRightBlock), storedAq(bottomRightBlock)),
      6.U -> maxUInt(storedAq(topLeftBlock), storedAq(topRightBlock)),
      7.U -> maxUInt(storedAq(bottomLeftBlock), storedAq(bottomRightBlock))
    )
  )
  val candidateMask = MuxLookup(candidateIndex, storedMask(topLeftBlock))(
    Seq(
      0.U -> storedMask(topLeftBlock),
      1.U -> storedMask(topRightBlock),
      2.U -> storedMask(bottomLeftBlock),
      3.U -> storedMask(bottomRightBlock),
      4.U -> maxUInt(storedMask(topLeftBlock), storedMask(bottomLeftBlock)),
      5.U -> maxUInt(storedMask(topRightBlock), storedMask(bottomRightBlock)),
      6.U -> maxUInt(storedMask(topLeftBlock), storedMask(topRightBlock)),
      7.U -> maxUInt(storedMask(bottomLeftBlock), storedMask(bottomRightBlock))
    )
  )
  selector.io.input.bits.quantQ24 := candidateAq
  selector.io.input.bits.maskQ16 := candidateMask
  selector.io.input.bits.ytox := ytoxMap(tileAddress(tileOrdinal))
  selector.io.input.bits.ytob := ytobMap(tileAddress(tileOrdinal))
  for (channel <- 0 until 3; coefficient <- 0 until 128) {
    val dctValue =
      if (coefficient < blockSize) {
        storedDct(dctCandidateBlock, channel, coefficient.U)
      } else {
        0.S(c.traceValueBits.W)
      }
    selector.io.input.bits.coefficients(channel)(coefficient) := Mux(
      candidateIndex < 4.U,
      dctValue,
      Mux(
        candidateIndex < 6.U,
        verticalDct(channel).io.output.bits(coefficient),
        horizontalDct(channel).io.output.bits(coefficient)
      )
    )
  }
  for (dct <- verticalDct) {
    dct.io.output.ready :=
      selector.io.input.ready && candidateIndex >= 4.U && candidateIndex < 6.U
  }
  for (dct <- horizontalDct) {
    dct.io.output.ready := selector.io.input.ready && candidateIndex >= 6.U
  }
  selector.io.output.ready := state === waitDecision

  when(selector.io.input.fire) {
    when(candidateIndex === 7.U) {
      candidateIndex := 0.U
      state := waitDecision
    }.otherwise {
      candidateIndex := candidateIndex + 1.U
    }
  }

  when(selector.io.output.fire) {
    decisions(blockAddress(topLeftBlock)) := selector.io.output.bits.decision(0)
    decisions(blockAddress(topRightBlock)) := selector.io.output.bits.decision(1)
    decisions(blockAddress(bottomLeftBlock)) := selector.io.output.bits.decision(2)
    decisions(blockAddress(bottomRightBlock)) := selector.io.output.bits.decision(3)
    arithmeticOverflow := arithmeticOverflow || selector.io.output.bits.arithmeticOverflow
    unsupportedDistance := unsupportedDistance || selector.io.output.bits.unsupportedDistance

    when(regionX + 3.U < tileValidXBlocks) {
      regionX := regionX + 2.U
      state := scanRegion
    }.elsewhen(regionY + 3.U < tileValidYBlocks) {
      regionX := 0.U
      regionY := regionY + 2.U
      state := scanRegion
    }.elsewhen(tileOrdinal + 1.U < totalTiles) {
      tileOrdinal := tileOrdinal + 1.U
      regionX := 0.U
      regionY := 0.U
      state := scanRegion
    }.otherwise {
      adjustIndex := 0.U
      adjustX := 0.U
      state := adjustingQuant
    }
  }

  val adjustmentDecision = decisions(blockAddress(adjustIndex))
  val adjustmentRawStrategy = adjustmentDecision(2, 1)
  val adjustmentIsFirst = adjustmentDecision(0)
  val adjustmentRightIndex = adjustIndex + 1.U
  val adjustmentBelowIndex = adjustIndex + xBlocks
  val adjustmentCurrentQuant = adjustedRawQuant(blockAddress(adjustIndex))
  val adjustmentRightQuant = adjustedRawQuant(blockAddress(adjustmentRightIndex))
  val adjustmentBelowQuant = adjustedRawQuant(blockAddress(adjustmentBelowIndex))
  val horizontalAdjustedQuant = maxUInt(adjustmentCurrentQuant, adjustmentRightQuant)
  val verticalAdjustedQuant = maxUInt(adjustmentCurrentQuant, adjustmentBelowQuant)
  val horizontalInBounds =
    adjustmentRightIndex < totalBlocks && adjustX + 1.U < xBlocks
  val verticalInBounds = adjustmentBelowIndex < totalBlocks

  when(state === adjustingQuant) {
    when(adjustmentIsFirst) {
      when(adjustmentRawStrategy === AcStrategyCode.Dct8x16.U) {
        assert(horizontalInBounds, "horizontal AC strategy exceeds adjusted raw-quant field")
        when(horizontalInBounds) {
          adjustedRawQuant(blockAddress(adjustIndex)) := horizontalAdjustedQuant
          adjustedRawQuant(blockAddress(adjustmentRightIndex)) := horizontalAdjustedQuant
        }.otherwise {
          protocolOverflow := true.B
        }
      }.elsewhen(adjustmentRawStrategy === AcStrategyCode.Dct16x8.U) {
        assert(verticalInBounds, "vertical AC strategy exceeds adjusted raw-quant field")
        when(verticalInBounds) {
          adjustedRawQuant(blockAddress(adjustIndex)) := verticalAdjustedQuant
          adjustedRawQuant(blockAddress(adjustmentBelowIndex)) := verticalAdjustedQuant
        }.otherwise {
          protocolOverflow := true.B
        }
      }.elsewhen(adjustmentRawStrategy =/= AcStrategyCode.Dct.U) {
        assert(false.B, "unsupported AC strategy in adjusted raw-quant field")
        protocolOverflow := true.B
      }
    }

    when(adjustIndex === totalBlocks - 1.U) {
      adjustIndex := 0.U
      adjustX := 0.U
      emitIndex := 0.U
      state := emitting
    }.otherwise {
      adjustIndex := adjustIndex + 1.U
      when(adjustX + 1.U === xBlocks) {
        adjustX := 0.U
      }.otherwise {
        adjustX := adjustX + 1.U
      }
    }
  }

  io.trace.valid := state === emitting
  io.trace.bits.stage := TraceStage.AcStrategy.U
  io.trace.bits.group := 0.U
  io.trace.bits.index := emitIndex
  io.trace.bits.value :=
    Cat(0.U(1.W), decisions(blockAddress(emitIndex))).asSInt.pad(c.traceValueBits)
  io.adjustedRawQuant := adjustedRawQuant(blockAddress(emitIndex))
  val emitXBlocksSafe = Mux(xBlocks === 0.U, 1.U, xBlocks)
  val emitX = emitIndex - (emitIndex / emitXBlocksSafe) * emitXBlocksSafe
  val emitY = emitIndex / emitXBlocksSafe
  val emitDecision = decisions(blockAddress(emitIndex))
  val emitRawStrategy = emitDecision(2, 1)
  val emitSecondIndex = Mux(
    emitRawStrategy === AcStrategyCode.Dct16x8.U,
    emitIndex + xBlocks,
    Mux(emitRawStrategy === AcStrategyCode.Dct8x16.U, emitIndex + 1.U, emitIndex)
  )
  val emitTileX = emitX >> log2Ceil(blocksPerTile)
  val emitTileY = emitY >> log2Ceil(blocksPerTile)
  val emitTileIndex = emitTileY * xTiles + emitTileX
  val laterOwnerExists = (0 until maxBlocks).map { index =>
    index.U > emitIndex && index.U < totalBlocks && decisions(index)(0)
  }.reduce(_ || _)

  io.selected.blockX := emitX
  io.selected.blockY := emitY
  io.selected.blockIndex := emitIndex
  io.selected.encodedStrategy := emitDecision
  io.selected.adjustedRawQuant := adjustedRawQuant(blockAddress(emitIndex))
  io.selected.ownerLast := emitDecision(0) && !laterOwnerExists
  io.selected.distanceQ8 := activeDistanceQ8
  io.selected.scaleQ16 := activeScaleQ16
  io.selected.fixedInvQacQ16 := activeFixedInvQacQ16
  io.selected.adaptiveRawQuant := activeAdaptiveRawQuant
  io.selected.invDcFactorQ16 := activeInvDcFactorQ16
  io.selected.xQmMultiplierQ16 := activeXQmMultiplierQ16
  io.selected.ytox := ytoxMap(tileAddress(emitTileIndex))
  io.selected.ytob := ytobMap(tileAddress(emitTileIndex))
  for (channel <- 0 until 3; sample <- 0 until blockSize) {
    io.selected.dct8x8(channel)(sample) := storedDct(emitIndex, channel, sample.U)
    io.selected.coveredXyb(0)(channel)(sample) := storedXyb(emitIndex, channel, sample)
    io.selected.coveredXyb(1)(channel)(sample) :=
      storedXyb(emitSecondIndex, channel, sample)
    io.selected.quantizationDct8x8.foreach(
      _(channel)(sample) := storedQuantizationDct(emitIndex, channel, sample.U)
    )
    io.selected.quantizationCoveredXyb.foreach { selectedXyb =>
      selectedXyb(0)(channel)(sample) :=
        storedQuantizationXyb(emitIndex, channel, sample)
      selectedXyb(1)(channel)(sample) :=
        storedQuantizationXyb(emitSecondIndex, channel, sample)
    }
  }
  io.traceLast := io.trace.valid && emitIndex === totalBlocks - 1.U
  io.busy := state =/= receiving || receivedBlock =/= 0.U || selector.io.busy
  io.overflow := protocolOverflow || arithmeticOverflow || (acceptingFirstBlock && configOutOfRange)
  io.unsupportedDistance := unsupportedDistance

  when(io.trace.fire) {
    when(io.traceLast) {
      state := receiving
      emitIndex := 0.U
      xBlocks := 0.U
      yBlocks := 0.U
      xTiles := 0.U
      totalBlocks := 0.U
      totalTiles := 0.U
      activeDistanceQ8 := 0.U
      activeScaleQ16 := 0.U
      activeFixedInvQacQ16 := 0.U
      activeAdaptiveRawQuant := false.B
      activeXQmMultiplierQ16 := 0.U
      tileOrdinal := 0.U
      tileSampleOrdinal := 0.U
      regionX := 0.U
      regionY := 0.U
      adjustIndex := 0.U
      adjustX := 0.U
      protocolOverflow := false.B
      arithmeticOverflow := false.B
      unsupportedDistance := false.B
    }.otherwise {
      emitIndex := emitIndex + 1.U
    }
  }
}

/** RGB-connected adaptive AC-strategy map.
  *
  * The shared AQ/DCT source supplies the same Q12 XYB blocks, Q12 ordinary-DCT
  * coefficients, final AQ field, and pre-nonlinear strategy mask used by the
  * fixed-point candidate scorer. The prepared scheduler owns full-frame
  * buffering, tile CFL estimation, rectangular transforms, and map emission.
  */
class FrameAqAcStrategyTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val trace = Decoupled(new StageTrace(c))
    val adjustedRawQuant = Output(UInt(8.W))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val blocks = Module(new FrameAqDctBlockStage(c))
  val strategy = Module(new FramePreparedAcStrategyTraceStage(c))
  val draining = RegInit(false.B)

  blocks.io.config := io.config
  blocks.io.input.bits := io.input.bits
  blocks.io.input.valid := io.input.valid && !draining
  io.input.ready := blocks.io.input.ready && !draining

  strategy.io.config := io.config
  strategy.io.input.valid := blocks.io.output.valid
  strategy.io.input.bits.xyb := blocks.io.output.bits.xyb
  strategy.io.input.bits.dct8x8 := blocks.io.output.bits.coefficients
  strategy.io.input.bits.aqMapQ24 := blocks.io.output.bits.aqMapQ24
  strategy.io.input.bits.strategyMaskQ16 := blocks.io.output.bits.strategyMaskQ16
  strategy.io.input.bits.rawQuant := blocks.io.output.bits.quant
  strategy.io.input.bits.distanceQ8 := blocks.io.output.bits.distanceQ8
  strategy.io.input.bits.scaleQ16 := blocks.io.output.bits.scaleQ16
  strategy.io.input.bits.fixedInvQacQ16 := blocks.io.output.bits.fixedInvQacQ16
  strategy.io.input.bits.adaptiveRawQuant := blocks.io.output.bits.adaptiveRawQuant
  strategy.io.input.bits.invDcFactorQ16 := blocks.io.output.bits.invDcFactorQ16
  strategy.io.input.bits.xQmMultiplierQ16 := blocks.io.output.bits.xQmMultiplierQ16
  strategy.io.input.bits.last := blocks.io.output.bits.blockLast
  blocks.io.output.ready := strategy.io.input.ready

  io.trace.valid := strategy.io.trace.valid
  io.trace.bits := strategy.io.trace.bits
  io.adjustedRawQuant := strategy.io.adjustedRawQuant
  strategy.io.trace.ready := io.trace.ready
  io.traceLast := strategy.io.traceLast
  io.busy := draining || blocks.io.busy || strategy.io.busy
  io.overflow := blocks.io.overflow || strategy.io.overflow

  when(blocks.io.output.fire && blocks.io.output.bits.blockLast) {
    draining := true.B
  }
  when(io.trace.fire && io.traceLast) {
    draining := false.B
  }
}

/** Focused RGB raw-quant route after adaptive strategy adjustment.
  *
  * The underlying strategy scheduler emits one aligned adjusted byte beside
  * each raster strategy record. This adapter changes only the trace stage and
  * value, preserving the scheduler's index, framing, backpressure, and status.
  */
class FrameAqAdjustedRawQuantTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val strategy = Module(new FrameAqAcStrategyTraceStage(c))
  strategy.io.config := io.config
  strategy.io.input.bits := io.input.bits
  strategy.io.input.valid := io.input.valid
  io.input.ready := strategy.io.input.ready

  io.trace.valid := strategy.io.trace.valid
  io.trace.bits.stage := TraceStage.RawQuantField.U
  io.trace.bits.group := strategy.io.trace.bits.group
  io.trace.bits.index := strategy.io.trace.bits.index
  io.trace.bits.value :=
    Cat(0.U(1.W), strategy.io.adjustedRawQuant).asSInt.pad(c.traceValueBits)
  strategy.io.trace.ready := io.trace.ready
  io.traceLast := strategy.io.traceLast
  io.busy := strategy.io.busy
  io.overflow := strategy.io.overflow
}
