// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** Emits one fixed CFL map for each 64x64 tile in a padded frame.
  *
  * This stage provides the current trace shape for libjxl-tiny's per-tile
  * Y-to-X and Y-to-B chroma-from-luma maps. Real CFL estimation will replace
  * the scalar fixed-map values later.
  */
class FrameCflMapTraceStage(c: HjxlConfig = HjxlConfig(), traceStage: Int) extends Module {
  require(
    traceStage == TraceStage.YtoxMap || traceStage == TraceStage.YtobMap,
    "FrameCflMapTraceStage only supports YtoxMap or YtobMap"
  )

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
  val totalTiles = RegInit(0.U(32.W))
  val overflow = RegInit(false.B)

  private def ceilDiv(value: UInt, divisor: Int): UInt =
    (value +& (divisor - 1).U) / divisor.U

  private def ceilToBlock(value: UInt): UInt = {
    val block = HjxlConstants.BlockDim.U
    ((value +& (block - 1.U)) / block) * block
  }

  val configWidth = io.config.xsize(widthBits - 1, 0)
  val configHeight = io.config.ysize(heightBits - 1, 0)
  val expectedPixels = configWidth * configHeight
  val nextPaddedWidth = ceilToBlock(configWidth)(widthBits - 1, 0)
  val nextPaddedHeight = ceilToBlock(configHeight)(heightBits - 1, 0)
  val nextXTilesRaw = ceilDiv(configWidth, HjxlConstants.TileDim)
  val nextYTilesRaw = ceilDiv(configHeight, HjxlConstants.TileDim)
  val nextXTiles = Mux(nextXTilesRaw === 0.U, 1.U, nextXTilesRaw)
  val nextYTiles = Mux(nextYTilesRaw === 0.U, 1.U, nextYTilesRaw)
  val configOutOfRange =
    io.config.xsize === 0.U || io.config.ysize === 0.U ||
      io.config.xsize > c.maxFrameWidth.U || io.config.ysize > c.maxFrameHeight.U ||
      nextPaddedWidth > c.maxFrameWidth.U || nextPaddedHeight > c.maxFrameHeight.U

  io.input.ready := state === receiving && !configOutOfRange
  io.trace.valid := state === emitting
  io.busy := state === emitting || received =/= 0.U
  io.overflow := overflow || configOutOfRange
  io.trace.bits.stage := traceStage.U
  io.trace.bits.group := 0.U
  io.trace.bits.index := emitIndex
  io.trace.bits.value :=
    (if (traceStage == TraceStage.YtoxMap) io.config.fixedYtox else io.config.fixedYtob)
      .pad(c.traceValueBits)
  io.traceLast := io.trace.valid && totalTiles =/= 0.U && emitIndex === totalTiles - 1.U

  when(configOutOfRange) {
    received := 0.U
    state := receiving
  }.elsewhen(io.input.fire) {
    val nextReceived = received + 1.U
    received := nextReceived
    when(nextReceived === expectedPixels) {
      state := emitting
      emitIndex := 0.U
      totalTiles := nextXTiles * nextYTiles
    }
  }

  when(io.trace.fire) {
    val nextEmit = emitIndex + 1.U
    emitIndex := nextEmit
    when(nextEmit === totalTiles) {
      state := receiving
      received := 0.U
      emitIndex := 0.U
      totalTiles := 0.U
    }
  }

  when(io.input.fire && received >= numPixels.U) {
    overflow := true.B
  }
}
