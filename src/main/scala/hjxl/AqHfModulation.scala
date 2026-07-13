// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

object AqHfModulationFixedPoint {
  val BlockDim = HjxlConstants.BlockDim
  val SamplesPerBlock = BlockDim * BlockDim
  val EdgesPerBlock = 2 * BlockDim * (BlockDim - 1)
  val XybFractionBits = RgbToXybApprox.OutputFractionBits
  val XybValueBits = 32
  val SeedFractionBits = AqNonlinearMaskFixedPoint.OutputFractionBits
  val SeedValueBits = AqNonlinearMaskFixedPoint.OutputValueBits
  val OutputFractionBits = SeedFractionBits
  val OutputValueBits = SeedValueBits
  val MultiplierFractionBits = 32
  val MagnitudeMultiplierQ32 = BigInt(76895992)
  val ProductShift = XybFractionBits + MultiplierFractionBits - OutputFractionBits
  val EdgeAccumulatorBits = 40

  val MinimumSigned32: BigInt = -(BigInt(1) << 31)
  val MaximumSigned32: BigInt = (BigInt(1) << 31) - 1
  val MaximumDifference: BigInt = (BigInt(1) << XybValueBits) - 1
  val MaximumEdgeTotal: BigInt = MaximumDifference * EdgesPerBlock

  require(ProductShift > 0, "AQ HF modulation product must be right shifted")
  require(
    MaximumEdgeTotal.bitLength <= EdgeAccumulatorBits,
    "AQ HF modulation edge accumulator is too small"
  )

  private def requireSigned32(value: BigInt, label: String): Unit =
    require(
      value >= MinimumSigned32 && value <= MaximumSigned32,
      s"$label must fit signed 32 bits"
    )

  def edgeTotalQ12(xybYQ12: Seq[BigInt]): BigInt = {
    require(xybYQ12.length == SamplesPerBlock, "AQ HF modulation requires one 8x8 Y block")
    xybYQ12.foreach(value => requireSigned32(value, "AQ HF modulation Y sample"))

    var total = BigInt(0)
    for (y <- 0 until BlockDim; x <- 0 until BlockDim) {
      val center = xybYQ12(y * BlockDim + x)
      if (x + 1 < BlockDim) {
        total += (center - xybYQ12(y * BlockDim + x + 1)).abs
      }
      if (y + 1 < BlockDim) {
        total += (center - xybYQ12((y + 1) * BlockDim + x)).abs
      }
    }
    require(total <= MaximumEdgeTotal, "AQ HF modulation edge total exceeds its bound")
    total
  }

  def modulateQ24(seedQ24: BigInt, xybYQ12: Seq[BigInt]): BigInt = {
    requireSigned32(seedQ24, "AQ HF modulation seed")
    val edgeTotal = edgeTotalQ12(xybYQ12)
    val magnitudeQ24 =
      (edgeTotal * MagnitudeMultiplierQ32 + (BigInt(1) << (ProductShift - 1))) >> ProductShift
    (seedQ24 - magnitudeQ24).max(MinimumSigned32).min(MaximumSigned32)
  }
}

class AqHfModulationBlockInput extends Bundle {
  import AqHfModulationFixedPoint._

  val seedQ24 = SInt(SeedValueBits.W)
  val xybYQ12 = Vec(SamplesPerBlock, SInt(XybValueBits.W))
}

/** One padded 8x8 XYB block paired with the nonlinear AQ seed that starts the
  * cumulative per-block modulation pipeline.
  *
  * The RGB-connected scheduler keeps block identity and frame completion with
  * the samples so HF, color, and later gamma stages can share one XYB capture.
  */
class AqModulationBlockInput extends Bundle {
  import AqHfModulationFixedPoint._

  val seedQ24 = SInt(SeedValueBits.W)
  val distanceQ8 = UInt(16.W)
  val fixedRawQuant = UInt(8.W)
  val blockIndex = UInt(32.W)
  val blockLast = Bool()
  val xybXQ12 = Vec(SamplesPerBlock, SInt(XybValueBits.W))
  val xybYQ12 = Vec(SamplesPerBlock, SInt(XybValueBits.W))
  val xybBQ12 = Vec(SamplesPerBlock, SInt(XybValueBits.W))
}

/** Adds libjxl-tiny's per-block high-frequency modulation to one nonlinear AQ
  * seed.
  *
  * The datapath walks one 8x8 sample per cycle and accumulates the 112 internal
  * horizontal and vertical absolute differences. It therefore uses one pair
  * of subtract/absolute-value datapaths instead of a wide combinational edge
  * reduction tree. The externally observed latency is exactly 64 cycles.
  */
class AqHfModulationBlock extends Module {
  import AqHfModulationFixedPoint._

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new AqHfModulationBlockInput))
    val output = Decoupled(SInt(OutputValueBits.W))
    val busy = Output(Bool())
  })

  private val sampleBits = log2Ceil(SamplesPerBlock)
  private val productBits = EdgeAccumulatorBits + MagnitudeMultiplierQ32.bitLength
  private val resultBits = productBits + 2

  val samples = Reg(Vec(SamplesPerBlock, SInt(XybValueBits.W)))
  val seed = RegInit(0.S(SeedValueBits.W))
  val sampleOrdinal = RegInit(0.U(sampleBits.W))
  val edgeTotal = RegInit(0.U(EdgeAccumulatorBits.W))
  val running = RegInit(false.B)
  val outputValid = RegInit(false.B)
  val outputValue = RegInit(0.S(OutputValueBits.W))

  io.input.ready := !running && !outputValid
  io.output.valid := outputValid
  io.output.bits := outputValue
  io.busy := running || outputValid

  when(io.output.fire) {
    outputValid := false.B
  }

  when(io.input.fire) {
    samples := io.input.bits.xybYQ12
    seed := io.input.bits.seedQ24
    sampleOrdinal := 0.U
    edgeTotal := 0.U
    running := true.B
  }

  private def absoluteDifference(left: SInt, right: SInt): UInt = {
    val difference = left -& right
    Mux(difference < 0.S, -difference, difference).asUInt
  }

  when(running) {
    val localX = sampleOrdinal(log2Ceil(BlockDim) - 1, 0)
    val localY = sampleOrdinal >> log2Ceil(BlockDim)
    val hasRight = localX =/= (BlockDim - 1).U
    val hasDown = localY =/= (BlockDim - 1).U
    val rightOrdinal = Mux(hasRight, sampleOrdinal + 1.U, sampleOrdinal)
    val downOrdinal = Mux(hasDown, sampleOrdinal + BlockDim.U, sampleOrdinal)
    val center = samples(sampleOrdinal)
    val rightMagnitude = Mux(
      hasRight,
      absoluteDifference(center, samples(rightOrdinal)),
      0.U((XybValueBits + 1).W)
    )
    val downMagnitude = Mux(
      hasDown,
      absoluteDifference(center, samples(downOrdinal)),
      0.U((XybValueBits + 1).W)
    )
    val sampleMagnitude = rightMagnitude +& downMagnitude
    val accumulated = edgeTotal +& sampleMagnitude.pad(EdgeAccumulatorBits)
    assert(!accumulated(EdgeAccumulatorBits), "AQ HF modulation edge accumulator overflow")
    val nextEdgeTotal = accumulated(EdgeAccumulatorBits - 1, 0)

    when(sampleOrdinal === (SamplesPerBlock - 1).U) {
      val product = nextEdgeTotal * MagnitudeMultiplierQ32.U
      val roundedProduct = product +& (BigInt(1) << (ProductShift - 1)).U
      val magnitudeQ24 = roundedProduct >> ProductShift
      val result = Wire(SInt(resultBits.W))
      result := seed.pad(resultBits) - magnitudeQ24.pad(resultBits).asSInt
      outputValue := Mux(
        result > MaximumSigned32.S(resultBits.W),
        MaximumSigned32.S(OutputValueBits.W),
        Mux(
          result < MinimumSigned32.S(resultBits.W),
          MinimumSigned32.S(OutputValueBits.W),
          result(OutputValueBits - 1, 0).asSInt
        )
      )
      sampleOrdinal := 0.U
      edgeTotal := 0.U
      running := false.B
      outputValid := true.B
    }.otherwise {
      sampleOrdinal := sampleOrdinal + 1.U
      edgeTotal := nextEdgeTotal
    }
  }
}

/** Applies HF modulation to prepared nonlinear seeds and prepared Q12 Y blocks
  * in padded-block raster order.
  */
class FramePreparedAqHfModulationTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  import AqHfModulationFixedPoint._

  private val maxXBlocks = c.maxFrameWidth / BlockDim
  private val maxYBlocks = c.maxFrameHeight / BlockDim
  private val maxBlocks = maxXBlocks * maxYBlocks
  private val widthBits = log2Ceil(c.maxFrameWidth + 1)
  private val heightBits = log2Ceil(c.maxFrameHeight + 1)

  require(c.traceValueBits == OutputValueBits, "AQ HF modulation trace requires signed 32-bit values")
  require(maxBlocks > 0, "configured frame must contain at least one block")
  require(BigInt(maxBlocks) <= BigInt("ffffffff", 16), "AQ HF modulation block count must fit trace index")

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new AqHfModulationBlockInput))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  private def ceilDivBlock(value: UInt): UInt =
    (value +& (BlockDim - 1).U) >> log2Ceil(BlockDim)

  val configWidth = io.config.xsize(widthBits - 1, 0)
  val configHeight = io.config.ysize(heightBits - 1, 0)
  val nextXBlocks = ceilDivBlock(configWidth)
  val nextYBlocks = ceilDivBlock(configHeight)
  val nextTotalBlocks = nextXBlocks * nextYBlocks
  val configOutOfRange =
    io.config.xsize === 0.U || io.config.ysize === 0.U ||
      io.config.xsize > c.maxFrameWidth.U || io.config.ysize > c.maxFrameHeight.U ||
      nextXBlocks > maxXBlocks.U || nextYBlocks > maxYBlocks.U

  val frameActive = RegInit(false.B)
  val activeTotalBlocks = RegInit(0.U(32.W))
  val nextBlockIndex = RegInit(0.U(32.W))
  val activeTraceIndex = RegInit(0.U(32.W))
  val activeTraceLast = RegInit(false.B)

  val block = Module(new AqHfModulationBlock)
  val selectedTotalBlocks = Mux(frameActive, activeTotalBlocks, nextTotalBlocks)
  val inputAllowed = frameActive || !configOutOfRange
  block.io.input.bits := io.input.bits
  block.io.input.valid := io.input.valid && inputAllowed && !activeTraceLast
  io.input.ready := block.io.input.ready && inputAllowed && !activeTraceLast

  io.trace.valid := block.io.output.valid
  io.trace.bits.stage := TraceStage.AqHfModulation.U
  io.trace.bits.group := 0.U
  io.trace.bits.index := activeTraceIndex
  io.trace.bits.value := block.io.output.bits
  block.io.output.ready := io.trace.ready
  io.traceLast := block.io.output.valid && activeTraceLast
  io.busy := frameActive || block.io.busy
  io.overflow := !frameActive && block.io.input.ready && configOutOfRange

  when(io.input.fire) {
    val inputIsLast = nextBlockIndex === selectedTotalBlocks - 1.U
    activeTraceIndex := nextBlockIndex
    activeTraceLast := inputIsLast
    when(!frameActive) {
      frameActive := true.B
      activeTotalBlocks := nextTotalBlocks
    }
    when(!inputIsLast) {
      nextBlockIndex := nextBlockIndex + 1.U
    }
  }

  when(io.trace.fire) {
    activeTraceLast := false.B
    when(activeTraceLast) {
      frameActive := false.B
      activeTotalBlocks := 0.U
      nextBlockIndex := 0.U
      activeTraceIndex := 0.U
    }
  }
}

/** Shared RGB-to-per-block scheduler for cumulative AQ modulation stages.
  *
  * The nonlinear-mask path exposes each accepted approximate XYB sample as a
  * passive tap. This module stores that frame once, applies right/bottom edge
  * replication, and emits nonlinear seeds paired with complete padded XYB
  * blocks. It deliberately holds the frame boundary until `frameDone`, so a
  * downstream HF/color/gamma pipeline cannot overlap the next frame with the
  * final block still in flight.
  */
class FrameAqModulationBlockStage(
    c: HjxlConfig = HjxlConfig(),
    includeColorChannels: Boolean = true
) extends Module {
  import AqHfModulationFixedPoint._

  private val numPixels = c.maxFrameWidth * c.maxFrameHeight
  private val frameIndexBits = log2Ceil(numPixels)
  private val frameCountBits = log2Ceil(numPixels + 1)
  private val widthBits = log2Ceil(c.maxFrameWidth + 1)
  private val heightBits = log2Ceil(c.maxFrameHeight + 1)

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val xybAccepted = Output(Valid(new XybPixel(c)))
    val block = Decoupled(new AqModulationBlockInput)
    val frameDone = Input(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val xybX = if (includeColorChannels) Some(Reg(Vec(numPixels, SInt(XybValueBits.W)))) else None
  val xybY = Reg(Vec(numPixels, SInt(XybValueBits.W)))
  val xybB = if (includeColorChannels) Some(Reg(Vec(numPixels, SInt(XybValueBits.W)))) else None
  val latchedConfig = Reg(new FrameConfig(c))
  val frameActive = RegInit(false.B)
  val finalBlockIssued = RegInit(false.B)
  val received = RegInit(0.U(frameCountBits.W))
  val activePixelCount = RegInit(0.U(frameCountBits.W))
  val latchedWidth = RegInit(0.U(widthBits.W))
  val latchedHeight = RegInit(0.U(heightBits.W))
  val blocksX = RegInit(0.U(widthBits.W))
  val currentBlockX = RegInit(0.U(widthBits.W))
  val currentBlockY = RegInit(0.U(heightBits.W))

  private def ceilDivBlock(value: UInt): UInt =
    (value +& (BlockDim - 1).U) >> log2Ceil(BlockDim)

  val activeConfig = Wire(new FrameConfig(c))
  activeConfig := Mux(frameActive, latchedConfig, io.config)
  val configWidth = activeConfig.xsize(widthBits - 1, 0)
  val configHeight = activeConfig.ysize(heightBits - 1, 0)
  val nextPixelCount = configWidth * configHeight
  val selectedPixelCount = Mux(frameActive, activePixelCount, nextPixelCount)
  val acceptingPixels = !frameActive || received < activePixelCount

  val nonlinear = Module(new FrameAqNonlinearMaskTraceStage(c))
  nonlinear.io.config := activeConfig
  nonlinear.io.input.bits := io.input.bits
  nonlinear.io.input.valid := io.input.valid && acceptingPixels
  io.input.ready := nonlinear.io.input.ready && acceptingPixels
  io.xybAccepted := nonlinear.io.xybAccepted

  when(io.input.fire && !frameActive) {
    latchedConfig := io.config
    latchedWidth := io.config.xsize(widthBits - 1, 0)
    latchedHeight := io.config.ysize(heightBits - 1, 0)
    activePixelCount :=
      (io.config.xsize(widthBits - 1, 0) * io.config.ysize(heightBits - 1, 0))(frameCountBits - 1, 0)
    blocksX := ceilDivBlock(io.config.xsize(widthBits - 1, 0))
    frameActive := true.B
  }

  when(nonlinear.io.xybAccepted.valid) {
    assert(received < selectedPixelCount, "AQ modulation scheduler accepted too many XYB pixels")
    xybX.foreach(_(received(frameIndexBits - 1, 0)) := nonlinear.io.xybAccepted.bits.xybX)
    xybY(received(frameIndexBits - 1, 0)) := nonlinear.io.xybAccepted.bits.xybY
    xybB.foreach(_(received(frameIndexBits - 1, 0)) := nonlinear.io.xybAccepted.bits.xybB)
    received := received + 1.U
  }

  private def frameSample(samples: Vec[SInt], paddedX: UInt, paddedY: UInt): SInt = {
    val sourceX = Mux(paddedX >= latchedWidth, latchedWidth - 1.U, paddedX)
    val sourceY = Mux(paddedY >= latchedHeight, latchedHeight - 1.U, paddedY)
    val index = sourceY * latchedWidth + sourceX
    samples(index(frameIndexBits - 1, 0))
  }

  io.block.bits.seedQ24 := nonlinear.io.trace.bits.value
  io.block.bits.distanceQ8 := latchedConfig.distanceQ8
  io.block.bits.fixedRawQuant := latchedConfig.fixedRawQuant
  io.block.bits.blockIndex := nonlinear.io.trace.bits.index
  io.block.bits.blockLast := nonlinear.io.traceLast
  for (sample <- 0 until SamplesPerBlock) {
    val localX = sample % BlockDim
    val localY = sample / BlockDim
    val paddedX = (currentBlockX << log2Ceil(BlockDim)) + localX.U
    val paddedY = (currentBlockY << log2Ceil(BlockDim)) + localY.U
    io.block.bits.xybXQ12(sample) := xybX
      .map(frameSample(_, paddedX, paddedY))
      .getOrElse(0.S(XybValueBits.W))
    io.block.bits.xybYQ12(sample) := frameSample(xybY, paddedX, paddedY)
    io.block.bits.xybBQ12(sample) := xybB
      .map(frameSample(_, paddedX, paddedY))
      .getOrElse(0.S(XybValueBits.W))
  }
  val frameComplete = frameActive && received === activePixelCount
  io.block.valid := nonlinear.io.trace.valid && frameComplete && !finalBlockIssued
  nonlinear.io.trace.ready := io.block.ready && frameComplete && !finalBlockIssued

  when(io.block.fire) {
    val expectedIndex = currentBlockY * blocksX + currentBlockX
    assert(
      nonlinear.io.trace.bits.index === expectedIndex,
      "AQ modulation nonlinear-seed order does not match the XYB-block traversal"
    )
    when(nonlinear.io.traceLast) {
      finalBlockIssued := true.B
    }.otherwise {
      when(currentBlockX + 1.U === blocksX) {
        currentBlockX := 0.U
        currentBlockY := currentBlockY + 1.U
      }.otherwise {
        currentBlockX := currentBlockX + 1.U
      }
    }
  }

  io.busy := frameActive || nonlinear.io.busy
  io.overflow := nonlinear.io.overflow

  when(io.frameDone) {
    assert(frameActive, "AQ modulation frame completion requires an active frame")
    assert(finalBlockIssued, "AQ modulation frame completed before its final block was issued")
    frameActive := false.B
    finalBlockIssued := false.B
    received := 0.U
    activePixelCount := 0.U
    latchedWidth := 0.U
    latchedHeight := 0.U
    blocksX := 0.U
    currentBlockX := 0.U
    currentBlockY := 0.U
  }
}

/** RGB-connected high-frequency AQ modulation path.
  *
  * This focused route specializes the shared modulation scheduler to retain
  * only Y, then emits the result of `_hf_modulation` without changing its
  * compact trace IO. Wider cumulative routes retain X/Y/B in the same
  * scheduler instead of repeating RGB-to-XYB conversion.
  */
class FrameAqHfModulationTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  import AqHfModulationFixedPoint._

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val xybAccepted = Output(Valid(new XybPixel(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val scheduler = Module(new FrameAqModulationBlockStage(c, includeColorChannels = false))
  scheduler.io.config := io.config
  scheduler.io.input <> io.input
  io.xybAccepted := scheduler.io.xybAccepted

  val block = Module(new AqHfModulationBlock)
  block.io.input.bits.seedQ24 := scheduler.io.block.bits.seedQ24
  block.io.input.bits.xybYQ12 := scheduler.io.block.bits.xybYQ12
  block.io.input.valid := scheduler.io.block.valid
  scheduler.io.block.ready := block.io.input.ready

  val activeBlockValid = RegInit(false.B)
  val activeBlockIndex = RegInit(0.U(32.W))
  val activeBlockLast = RegInit(false.B)

  when(scheduler.io.block.fire) {
    assert(!activeBlockValid, "AQ HF modulation accepted overlapping block metadata")
    activeBlockValid := true.B
    activeBlockIndex := scheduler.io.block.bits.blockIndex
    activeBlockLast := scheduler.io.block.bits.blockLast
  }

  io.trace.valid := block.io.output.valid
  io.trace.bits.stage := TraceStage.AqHfModulation.U
  io.trace.bits.group := 0.U
  io.trace.bits.index := activeBlockIndex
  io.trace.bits.value := block.io.output.bits
  block.io.output.ready := io.trace.ready
  io.traceLast := block.io.output.valid && activeBlockLast
  scheduler.io.frameDone := io.trace.fire && activeBlockLast
  io.busy := scheduler.io.busy || block.io.busy || activeBlockValid
  io.overflow := scheduler.io.overflow

  when(io.trace.fire) {
    assert(activeBlockValid, "AQ HF modulation emitted without block metadata")
    activeBlockValid := false.B
    activeBlockLast := false.B
  }
}
