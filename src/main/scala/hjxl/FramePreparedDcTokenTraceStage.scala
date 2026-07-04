// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** Emits DC residual tokens from prepared quantized DC planes.
  *
  * Input samples are quantized DC values in libjxl-tiny token order: all Y
  * blocks in raster order, then X, then B. This frame scheduler is the exact
  * boundary after quantized DC planes exist; it does not compute RGB-to-XYB,
  * DCT, or quantization.
  */
class FramePreparedDcTokenTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  private val blockDim = HjxlConstants.BlockDim
  private val maxXBlocks = c.maxFrameWidth / blockDim
  private val maxYBlocks = c.maxFrameHeight / blockDim
  private val maxBlocks = maxXBlocks * maxYBlocks
  private val maxSamples = maxBlocks * 3
  private val sampleCountBits = log2Ceil(maxSamples + 1)
  private val widthBits = log2Ceil(c.maxFrameWidth + 1)
  private val heightBits = log2Ceil(c.maxFrameHeight + 1)

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(SInt(c.traceValueBits.W)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val samples = Reg(Vec(maxSamples, SInt(c.traceValueBits.W)))

  val receiving :: emitting :: Nil = Enum(2)
  val state = RegInit(receiving)
  val received = RegInit(0.U(sampleCountBits.W))
  val emitIndex = RegInit(0.U(sampleCountBits.W))
  val xBlocks = RegInit(0.U(32.W))
  val yBlocks = RegInit(0.U(32.W))
  val totalBlocks = RegInit(0.U(32.W))
  val totalSamples = RegInit(0.U(32.W))
  val overflow = RegInit(false.B)

  private def ceilDiv(value: UInt, divisor: Int): UInt =
    (value + (divisor - 1).U) / divisor.U

  val configWidth = io.config.xsize(widthBits - 1, 0)
  val configHeight = io.config.ysize(heightBits - 1, 0)
  val nextXBlocks = ceilDiv(configWidth, blockDim)
  val nextYBlocks = ceilDiv(configHeight, blockDim)
  val nextTotalBlocks = nextXBlocks * nextYBlocks
  val nextTotalSamples = nextTotalBlocks * 3.U
  val configOutOfRange =
    io.config.xsize === 0.U || io.config.ysize === 0.U ||
      io.config.xsize > c.maxFrameWidth.U || io.config.ysize > c.maxFrameHeight.U ||
      nextXBlocks > maxXBlocks.U || nextYBlocks > maxYBlocks.U

  val xBlocksSafe = Mux(xBlocks === 0.U, 1.U, xBlocks)
  val totalBlocksSafe = Mux(totalBlocks === 0.U, 1.U, totalBlocks)
  val plane = emitIndex / totalBlocksSafe
  val blockOrdinal = emitIndex - plane * totalBlocksSafe
  val blockX = blockOrdinal - (blockOrdinal / xBlocksSafe) * xBlocksSafe
  val blockY = blockOrdinal / xBlocksSafe
  val planeBase = plane * totalBlocksSafe

  val westBlockX = Mux(blockX === 0.U, blockX, blockX - 1.U)
  val northBlockY = Mux(blockY === 0.U, blockY, blockY - 1.U)
  val westOrdinal = blockY * xBlocksSafe + westBlockX
  val northOrdinal = northBlockY * xBlocksSafe + blockX
  val northwestOrdinal = northBlockY * xBlocksSafe + westBlockX

  val current = samples(emitIndex)
  val westCandidate = samples((planeBase + westOrdinal)(sampleCountBits - 1, 0))
  val northCandidate = samples((planeBase + northOrdinal)(sampleCountBits - 1, 0))
  val northwestCandidate = samples((planeBase + northwestOrdinal)(sampleCountBits - 1, 0))

  val left = Mux(blockX =/= 0.U, westCandidate, Mux(blockY =/= 0.U, northCandidate, 0.S))
  val top = Mux(blockY =/= 0.U, northCandidate, left)
  val topLeft = Mux(blockX =/= 0.U && blockY =/= 0.U, northwestCandidate, left)

  val dcTokens = Module(new DcTokenTraceStage(c))
  dcTokens.io.input.valid := state === emitting
  dcTokens.io.input.bits.group := emitIndex
  dcTokens.io.input.bits.current := current
  dcTokens.io.input.bits.west := left
  dcTokens.io.input.bits.north := top
  dcTokens.io.input.bits.northwest := topLeft
  dcTokens.io.trace.ready := io.trace.ready

  io.input.ready := state === receiving && !configOutOfRange
  io.trace.valid := dcTokens.io.trace.valid
  io.trace.bits := dcTokens.io.trace.bits
  io.traceLast := state === emitting && totalSamples =/= 0.U && emitIndex === totalSamples - 1.U && dcTokens.io.trace.valid
  io.busy := state =/= receiving || received =/= 0.U
  io.overflow := overflow || configOutOfRange

  when(configOutOfRange) {
    received := 0.U
    state := receiving
  }.elsewhen(io.input.fire) {
    samples(received) := io.input.bits
    val nextReceived = received + 1.U
    received := nextReceived

    when(nextReceived === nextTotalSamples) {
      state := emitting
      emitIndex := 0.U
      xBlocks := nextXBlocks
      yBlocks := nextYBlocks
      totalBlocks := nextTotalBlocks
      totalSamples := nextTotalSamples
    }
  }

  when(io.trace.fire) {
    val nextEmit = emitIndex + 1.U
    emitIndex := nextEmit
    when(nextEmit === totalSamples) {
      state := receiving
      received := 0.U
      emitIndex := 0.U
      xBlocks := 0.U
      yBlocks := 0.U
      totalBlocks := 0.U
      totalSamples := 0.U
    }
  }

  when(io.input.fire && received >= maxSamples.U) {
    overflow := true.B
  }
}
