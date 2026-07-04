// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

class PreparedAcBlockTraceInput(c: HjxlConfig) extends Bundle {
  val numNonzeros = Vec(3, UInt(8.W))
  val quantized = Vec(3, Vec(HjxlConstants.BlockDim * HjxlConstants.BlockDim, SInt(c.traceValueBits.W)))
}

/** Emits AC tokens from prepared all-DCT quantized AC blocks.
  *
  * Input blocks are supplied in raster block order. Each block carries X/Y/B
  * quantized AC coefficients and nonzero counts; this scheduler predicts
  * nonzero counts from west/north blocks and delegates per-block Y/X/B token
  * sequencing to `DctOnlyAcBlockTokenTraceStage`.
  */
class FramePreparedAcTokenTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  private val blockDim = HjxlConstants.BlockDim
  private val blockSize = blockDim * blockDim
  private val maxXBlocks = c.maxFrameWidth / blockDim
  private val maxYBlocks = c.maxFrameHeight / blockDim
  private val maxBlocks = maxXBlocks * maxYBlocks
  private val blockCountBits = log2Ceil(maxBlocks + 1)
  private val blockIndexBits = math.max(1, log2Ceil(maxBlocks))
  private val widthBits = log2Ceil(c.maxFrameWidth + 1)
  private val heightBits = log2Ceil(c.maxFrameHeight + 1)

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new PreparedAcBlockTraceInput(c)))
    val trace = Decoupled(new StageTrace(c))
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val quantized = Reg(Vec(maxBlocks, Vec(3, Vec(blockSize, SInt(c.traceValueBits.W)))))
  val numNonzeros = Reg(Vec(maxBlocks, Vec(3, UInt(8.W))))

  val receiving :: startBlock :: emitBlock :: Nil = Enum(3)
  val state = RegInit(receiving)
  val received = RegInit(0.U(blockCountBits.W))
  val blockOrdinal = RegInit(0.U(blockCountBits.W))
  val tokenOrdinal = RegInit(0.U(c.groupBits.W))
  val xBlocks = RegInit(0.U(32.W))
  val yBlocks = RegInit(0.U(32.W))
  val totalBlocks = RegInit(0.U(32.W))
  val overflow = RegInit(false.B)

  private def ceilDiv(value: UInt, divisor: Int): UInt =
    (value + (divisor - 1).U) / divisor.U

  private def blockIndex(value: UInt): UInt =
    value(blockIndexBits - 1, 0)

  val configWidth = io.config.xsize(widthBits - 1, 0)
  val configHeight = io.config.ysize(heightBits - 1, 0)
  val nextXBlocks = ceilDiv(configWidth, blockDim)
  val nextYBlocks = ceilDiv(configHeight, blockDim)
  val nextTotalBlocks = nextXBlocks * nextYBlocks
  val configOutOfRange =
    io.config.xsize === 0.U || io.config.ysize === 0.U ||
      io.config.xsize > c.maxFrameWidth.U || io.config.ysize > c.maxFrameHeight.U ||
      nextXBlocks > maxXBlocks.U || nextYBlocks > maxYBlocks.U

  val xBlocksSafe = Mux(xBlocks === 0.U, 1.U, xBlocks)
  val blockX = blockOrdinal - (blockOrdinal / xBlocksSafe) * xBlocksSafe
  val blockY = blockOrdinal / xBlocksSafe
  val westOrdinal = blockOrdinal - 1.U
  val northOrdinal = blockOrdinal - xBlocksSafe

  val currentIndex = blockIndex(blockOrdinal)
  val westIndex = blockIndex(westOrdinal)
  val northIndex = blockIndex(northOrdinal)

  val predictedNonzeros = Wire(Vec(3, UInt(8.W)))
  for (channel <- 0 until 3) {
    val current = numNonzeros(currentIndex)(channel)
    val west = numNonzeros(westIndex)(channel)
    val north = numNonzeros(northIndex)(channel)
    predictedNonzeros(channel) := Mux(
      blockX === 0.U,
      Mux(blockY === 0.U, 32.U, north),
      Mux(blockY === 0.U, west, (north +& west + 1.U) >> 1)
    )
  }

  val blockTokens = Module(new DctOnlyAcBlockTokenTraceStage(c))
  blockTokens.io.input.valid := state === startBlock
  blockTokens.io.input.bits.group := tokenOrdinal
  blockTokens.io.input.bits.predictedNonzeros := predictedNonzeros
  blockTokens.io.input.bits.numNonzeros := numNonzeros(currentIndex)
  blockTokens.io.input.bits.quantized := quantized(currentIndex)
  blockTokens.io.trace.ready := io.trace.ready && state === emitBlock

  io.input.ready := state === receiving && !configOutOfRange
  io.trace.valid := state === emitBlock && blockTokens.io.trace.valid
  io.trace.bits := blockTokens.io.trace.bits
  io.busy := state =/= receiving || received =/= 0.U
  io.overflow := overflow || configOutOfRange

  when(configOutOfRange) {
    received := 0.U
    state := receiving
  }.elsewhen(io.input.fire) {
    quantized(blockIndex(received)) := io.input.bits.quantized
    numNonzeros(blockIndex(received)) := io.input.bits.numNonzeros
    val nextReceived = received + 1.U
    received := nextReceived

    when(nextReceived === nextTotalBlocks) {
      state := startBlock
      blockOrdinal := 0.U
      tokenOrdinal := 0.U
      xBlocks := nextXBlocks
      yBlocks := nextYBlocks
      totalBlocks := nextTotalBlocks
    }
  }

  when(state === startBlock && blockTokens.io.input.fire) {
    state := emitBlock
  }

  when(io.trace.fire) {
    tokenOrdinal := io.trace.bits.group + 1.U
  }

  when(state === emitBlock && !blockTokens.io.busy && !blockTokens.io.trace.valid) {
    val nextBlock = blockOrdinal + 1.U
    when(nextBlock === totalBlocks) {
      state := receiving
      received := 0.U
      blockOrdinal := 0.U
      tokenOrdinal := 0.U
      xBlocks := 0.U
      yBlocks := 0.U
      totalBlocks := 0.U
    }.otherwise {
      blockOrdinal := nextBlock
      state := startBlock
    }
  }

  when(io.input.fire && received >= maxBlocks.U) {
    overflow := true.B
  }
}
