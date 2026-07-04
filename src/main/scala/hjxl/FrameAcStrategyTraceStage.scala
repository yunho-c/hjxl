// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** Emits the current DCT-only AC strategy map for a padded frame.
  *
  * This is a metadata trace for the current transform path. Every padded 8x8
  * block is marked as an ordinary DCT first block, using libjxl-tiny's
  * `(raw_strategy << 1) | is_first_block` encoding.
  */
class FrameAcStrategyTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  private val numPixels = c.maxFrameWidth * c.maxFrameHeight
  private val frameCountBits = log2Ceil(numPixels + 1)
  private val widthBits = log2Ceil(c.maxFrameWidth + 1)
  private val heightBits = log2Ceil(c.maxFrameHeight + 1)

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val receiving :: emitting :: Nil = Enum(2)
  val state = RegInit(receiving)
  val received = RegInit(0.U(frameCountBits.W))
  val emitIndex = RegInit(0.U(32.W))
  val totalBlocks = RegInit(0.U(32.W))
  val overflow = RegInit(false.B)

  private def ceilToBlock(value: UInt): UInt = {
    val block = HjxlConstants.BlockDim.U
    ((value + (block - 1.U)) / block) * block
  }

  val configWidth = io.config.xsize(widthBits - 1, 0)
  val configHeight = io.config.ysize(heightBits - 1, 0)
  val expectedPixels = configWidth * configHeight
  val nextPaddedWidth = ceilToBlock(configWidth)(widthBits - 1, 0)
  val nextPaddedHeight = ceilToBlock(configHeight)(heightBits - 1, 0)
  val configOutOfRange =
    io.config.xsize === 0.U || io.config.ysize === 0.U ||
      io.config.xsize > c.maxFrameWidth.U || io.config.ysize > c.maxFrameHeight.U ||
      nextPaddedWidth > c.maxFrameWidth.U || nextPaddedHeight > c.maxFrameHeight.U

  io.input.ready := state === receiving && !configOutOfRange
  io.trace.valid := state === emitting
  io.busy := state === emitting || received =/= 0.U
  io.overflow := overflow || configOutOfRange
  io.trace.bits.stage := TraceStage.AcStrategy.U
  io.trace.bits.group := 0.U
  io.trace.bits.index := emitIndex
  io.trace.bits.value := AcStrategyCode.encoded(AcStrategyCode.Dct, isFirstBlock = true).S
  io.traceLast := io.trace.valid && totalBlocks =/= 0.U && emitIndex === totalBlocks - 1.U

  when(configOutOfRange) {
    received := 0.U
    state := receiving
  }.elsewhen(io.input.fire) {
    val nextReceived = received + 1.U
    received := nextReceived
    when(nextReceived === expectedPixels) {
      state := emitting
      emitIndex := 0.U
      totalBlocks := (nextPaddedWidth >> log2Ceil(HjxlConstants.BlockDim)) *
        (nextPaddedHeight >> log2Ceil(HjxlConstants.BlockDim))
    }
  }

  when(io.trace.fire) {
    val nextEmit = emitIndex + 1.U
    emitIndex := nextEmit
    when(nextEmit === totalBlocks) {
      state := receiving
      received := 0.U
      emitIndex := 0.U
      totalBlocks := 0.U
    }
  }

  when(io.input.fire && received >= numPixels.U) {
    overflow := true.B
  }
}
