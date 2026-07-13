// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** Schedules prepared DCT-only blocks through the block quantizer.
  *
  * This is the exact frame-level boundary after scaled X/Y/B DCT coefficients,
  * adjusted raw quant values, CFL multipliers, and distance-derived scalar
  * parameters are known. Blocks are accepted in raster order and emitted as the
  * same quantized trace records used by downstream token stages.
  */
class FramePreparedDctOnlyQuantizeTraceStage(
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

  val child = Module(new DctOnlyQuantizeTraceStage(c, activeCoefficientFractionBits))
  val idle :: active :: Nil = Enum(2)
  val state = RegInit(idle)
  val currentBlock = RegInit(0.U(blockCountBits.W))
  val totalBlocks = RegInit(0.U(blockCountBits.W))
  val overflow = RegInit(false.B)

  private def ceilDivBlock(value: UInt): UInt =
    (value +& (blockDim - 1).U) >> log2Ceil(blockDim)

  val configWidth = io.config.xsize(widthBits - 1, 0)
  val configHeight = io.config.ysize(heightBits - 1, 0)
  val nextXBlocks = ceilDivBlock(configWidth)
  val nextYBlocks = ceilDivBlock(configHeight)
  val nextTotalBlocks = nextXBlocks * nextYBlocks
  val configOutOfRange =
    io.config.xsize === 0.U || io.config.ysize === 0.U ||
      io.config.xsize > c.maxFrameWidth.U || io.config.ysize > c.maxFrameHeight.U ||
      nextXBlocks > maxXBlocks.U || nextYBlocks > maxYBlocks.U

  val accepting =
    !configOutOfRange &&
      (state === idle || currentBlock < totalBlocks) &&
      child.io.input.ready

  child.io.input.valid := io.input.valid && accepting
  child.io.input.bits.group := currentBlock
  child.io.input.bits.quantize := io.input.bits
  child.io.trace.ready := io.trace.ready

  io.input.ready := accepting
  io.trace.valid := child.io.trace.valid
  io.trace.bits := child.io.trace.bits
  io.traceLast :=
    child.io.trace.valid &&
      totalBlocks =/= 0.U &&
      currentBlock === totalBlocks - 1.U &&
      child.io.trace.bits.stage === TraceStage.NumNonzeros.U &&
      child.io.trace.bits.index === 2.U
  io.busy := state === active || child.io.busy
  io.overflow := overflow || configOutOfRange

  val childLastTrace =
    child.io.trace.fire &&
      child.io.trace.bits.stage === TraceStage.NumNonzeros.U &&
      child.io.trace.bits.index === 2.U

  when(configOutOfRange && state === idle) {
    currentBlock := 0.U
    totalBlocks := 0.U
  }.elsewhen(io.input.fire) {
    when(state === idle) {
      state := active
      totalBlocks := nextTotalBlocks(blockCountBits - 1, 0)
    }
  }

  when(childLastTrace) {
    val nextBlock = currentBlock + 1.U
    currentBlock := nextBlock
    when(nextBlock === totalBlocks) {
      state := idle
      currentBlock := 0.U
      totalBlocks := 0.U
    }
  }

  when(io.input.fire && currentBlock >= maxBlocks.U) {
    overflow := true.B
  }
}
