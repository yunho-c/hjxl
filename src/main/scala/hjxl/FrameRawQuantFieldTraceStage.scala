// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** Emits an explicitly overridden fixed raw-quant field for a padded frame.
  *
  * Every padded 8x8 block receives the same adjusted raw quant selected from
  * `FrameConfig.fixedRawQuant`; zero retains the historical DCT-only default
  * when this fixed stage is instantiated directly.
  */
class FrameFixedRawQuantFieldTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
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
  val activeRawQuant = RegInit(QuantizeDct8x8Block.DefaultRawQuant.U(8.W))
  val overflow = RegInit(false.B)

  private def ceilToBlock(value: UInt): UInt = {
    val block = HjxlConstants.BlockDim.U
    ((value +& (block - 1.U)) / block) * block
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

  val selectedRawQuant = Mux(
    io.config.fixedRawQuant === 0.U,
    QuantizeDct8x8Block.DefaultRawQuant.U,
    io.config.fixedRawQuant
  )

  io.input.ready := state === receiving && !configOutOfRange
  io.trace.valid := state === emitting
  io.busy := state === emitting || received =/= 0.U
  io.overflow := overflow || configOutOfRange
  io.trace.bits.stage := TraceStage.RawQuantField.U
  io.trace.bits.group := 0.U
  io.trace.bits.index := emitIndex
  io.trace.bits.value := Cat(0.U(1.W), activeRawQuant).asSInt.pad(c.traceValueBits)
  io.traceLast := io.trace.valid && totalBlocks =/= 0.U && emitIndex === totalBlocks - 1.U

  when(configOutOfRange) {
    received := 0.U
    state := receiving
    activeRawQuant := QuantizeDct8x8Block.DefaultRawQuant.U
  }.elsewhen(io.input.fire) {
    when(received === 0.U) {
      activeRawQuant := selectedRawQuant
    }
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
      activeRawQuant := QuantizeDct8x8Block.DefaultRawQuant.U
    }
  }

  when(io.input.fire && received >= numPixels.U) {
    overflow := true.B
  }
}

/** Selects real RGB adaptive quantization unless `fixedRawQuant` overrides it.
  *
  * Selection is captured with the first accepted pixel and remains stable
  * until the final trace beat. This preserves the inexpensive fixed metadata
  * path for focused experiments while making zero mean the real AQ path.
  */
class FrameRawQuantFieldTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val fixed = Module(new FrameFixedRawQuantFieldTraceStage(c))
  val adaptive = Module(new FrameAqRawQuantTraceStage(c))
  fixed.io.config := io.config
  adaptive.io.config := io.config
  fixed.io.input.bits := io.input.bits
  adaptive.io.input.bits := io.input.bits

  val frameActive = RegInit(false.B)
  val selectedFixed = RegInit(false.B)
  val useFixed = Mux(frameActive, selectedFixed, io.config.fixedRawQuant =/= 0.U)

  fixed.io.input.valid := io.input.valid && useFixed
  adaptive.io.input.valid := io.input.valid && !useFixed
  io.input.ready := Mux(useFixed, fixed.io.input.ready, adaptive.io.input.ready)

  io.trace.valid := Mux(useFixed, fixed.io.trace.valid, adaptive.io.trace.valid)
  io.trace.bits := Mux(useFixed, fixed.io.trace.bits, adaptive.io.trace.bits)
  io.traceLast := Mux(useFixed, fixed.io.traceLast, adaptive.io.traceLast)
  fixed.io.trace.ready := io.trace.ready && useFixed
  adaptive.io.trace.ready := io.trace.ready && !useFixed
  io.busy := frameActive || fixed.io.busy || adaptive.io.busy
  io.overflow := Mux(useFixed, fixed.io.overflow, adaptive.io.overflow)

  when(io.input.fire && !frameActive) {
    frameActive := true.B
    selectedFixed := io.config.fixedRawQuant =/= 0.U
  }
  when(io.trace.fire && io.traceLast) {
    frameActive := false.B
    selectedFixed := false.B
  }
}

/** Selects strategy-adjusted RGB AQ unless `fixedRawQuant` overrides it.
  *
  * Unlike `FrameRawQuantFieldTraceStage`, the adaptive branch waits for the
  * focused AC-strategy search and applies libjxl-tiny's `adjust_quant_field`
  * maximum over every selected rectangular transform. The fixed branch stays
  * inexpensive because a uniform override is already adjustment-invariant.
  */
class FrameAdjustedRawQuantFieldTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val fixed = Module(new FrameFixedRawQuantFieldTraceStage(c))
  val adaptive = Module(new FrameAqAdjustedRawQuantTraceStage(c))
  fixed.io.config := io.config
  adaptive.io.config := io.config
  fixed.io.input.bits := io.input.bits
  adaptive.io.input.bits := io.input.bits

  val frameActive = RegInit(false.B)
  val selectedFixed = RegInit(false.B)
  val useFixed = Mux(frameActive, selectedFixed, io.config.fixedRawQuant =/= 0.U)

  fixed.io.input.valid := io.input.valid && useFixed
  adaptive.io.input.valid := io.input.valid && !useFixed
  io.input.ready := Mux(useFixed, fixed.io.input.ready, adaptive.io.input.ready)

  io.trace.valid := Mux(useFixed, fixed.io.trace.valid, adaptive.io.trace.valid)
  io.trace.bits := Mux(useFixed, fixed.io.trace.bits, adaptive.io.trace.bits)
  io.traceLast := Mux(useFixed, fixed.io.traceLast, adaptive.io.traceLast)
  fixed.io.trace.ready := io.trace.ready && useFixed
  adaptive.io.trace.ready := io.trace.ready && !useFixed
  io.busy := frameActive || fixed.io.busy || adaptive.io.busy
  io.overflow := Mux(useFixed, fixed.io.overflow, adaptive.io.overflow)

  when(io.input.fire && !frameActive) {
    frameActive := true.B
    selectedFixed := io.config.fixedRawQuant =/= 0.U
  }
  when(io.trace.fire && io.traceLast) {
    frameActive := false.B
    selectedFixed := false.B
  }
}
