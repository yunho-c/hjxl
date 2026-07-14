// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** One raster 8x8 block at the prepared frame-strategy boundary.
  *
  * Q12 XYB samples are retained for rectangular transforms, while the matching
  * Q12 ordinary-DCT coefficients are reused for DCT candidate scoring and tile
  * CFL estimation. AQ and mask values are the per-block values before
  * `AdjustQuantField`; the scheduler takes the required maximum for each
  * candidate shape.
  */
class PreparedAcStrategyFrameBlock(c: HjxlConfig) extends Bundle {
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim

  val xyb = Vec(3, Vec(blockSize, SInt(c.traceValueBits.W)))
  val dct8x8 = Vec(3, Vec(blockSize, SInt(c.traceValueBits.W)))
  val aqMapQ24 = UInt(AqFinalModulationFixedPoint.ValueBits.W)
  val strategyMaskQ16 = UInt(AqStrategyMaskFixedPoint.ValueBits.W)
  val distanceQ8 = UInt(16.W)
  val last = Bool()
}

/** Searches AC strategy for a complete prepared frame and emits a raster map.
  *
  * The scheduler first buffers raster blocks, estimates one CFL pair per
  * 64x64 tile from ordinary-DCT coefficients, then visits tile-local 2x2 block
  * regions in libjxl-tiny order. Every region feeds four 8x8, two 16x8, and two
  * 8x16 candidates through `PreparedAcStrategy2x2Selector`. Incomplete rows or
  * columns at tile edges remain ordinary DCT, matching the reference loop.
  */
class FramePreparedAcStrategyTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
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
    val input = Flipped(Decoupled(new PreparedAcStrategyFrameBlock(c)))
    val trace = Decoupled(new StageTrace(c))
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
  val aqMapQ24 = Reg(Vec(maxBlocks, UInt(AqFinalModulationFixedPoint.ValueBits.W)))
  val strategyMaskQ16 = Reg(Vec(maxBlocks, UInt(AqStrategyMaskFixedPoint.ValueBits.W)))
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

  private def storedAq(block: UInt): UInt = aqMapQ24(blockAddress(block))
  private def storedMask(block: UInt): UInt = strategyMaskQ16(blockAddress(block))

  val Seq(
    receiving,
    cflStreaming,
    cflWaiting,
    scanRegion,
    feedCandidates,
    waitDecision,
    emitting
  ) = Enum(7)
  val state = RegInit(receiving)
  val receivedBlock = RegInit(0.U(32.W))
  val xBlocks = RegInit(0.U(32.W))
  val yBlocks = RegInit(0.U(32.W))
  val xTiles = RegInit(0.U(32.W))
  val totalBlocks = RegInit(0.U(32.W))
  val totalTiles = RegInit(0.U(32.W))
  val activeDistanceQ8 = RegInit(0.U(16.W))
  val tileOrdinal = RegInit(0.U(32.W))
  val tileSampleOrdinal = RegInit(0.U(32.W))
  val regionX = RegInit(0.U(32.W))
  val regionY = RegInit(0.U(32.W))
  val candidateIndex = RegInit(0.U(3.W))
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
    }
    aqMapQ24(store) := io.input.bits.aqMapQ24
    strategyMaskQ16(store) := io.input.bits.strategyMaskQ16
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
      unsupportedDistance := !inputDistance.io.supported
    }.otherwise {
      assert(
        io.input.bits.distanceQ8 === activeDistanceQ8,
        "prepared AC-strategy distance changed within a frame"
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

  private def coefficientQ16(value: SInt): SInt =
    (value << 4).asSInt.pad(estimatorCoefficientBits)

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
    coefficientQ16(storedDct(cflBlockIndex, 1, cflCoefficientIndexSafe))
  cflEstimator.io.input.bits.xCoeffQ16 :=
    coefficientQ16(storedDct(cflBlockIndex, 0, cflCoefficientIndexSafe))
  cflEstimator.io.input.bits.bCoeffQ16 :=
    coefficientQ16(storedDct(cflBlockIndex, 2, cflCoefficientIndexSafe))
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
      emitIndex := 0.U
      state := emitting
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
      emitIndex := 0.U
      state := emitting
    }
  }

  io.trace.valid := state === emitting
  io.trace.bits.stage := TraceStage.AcStrategy.U
  io.trace.bits.group := 0.U
  io.trace.bits.index := emitIndex
  io.trace.bits.value :=
    Cat(0.U(1.W), decisions(blockAddress(emitIndex))).asSInt.pad(c.traceValueBits)
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
      tileOrdinal := 0.U
      tileSampleOrdinal := 0.U
      regionX := 0.U
      regionY := 0.U
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
  strategy.io.input.bits.distanceQ8 := blocks.io.output.bits.distanceQ8
  strategy.io.input.bits.last := blocks.io.output.bits.blockLast
  blocks.io.output.ready := strategy.io.input.ready

  io.trace.valid := strategy.io.trace.valid
  io.trace.bits := strategy.io.trace.bits
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
