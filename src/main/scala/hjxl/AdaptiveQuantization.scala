// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

object AdaptiveQuantizationFixedPoint {
  val FractionBits = 24
  val ValueBits = 32
}

/** Converts a prepared adaptive-quantization map value to an adjusted raw quant.
  *
  * Both inputs are unsigned fixed-point values with `fractionBits` fractional
  * bits. The multiply, nearest-integer rounding, and [1, 255] clamp reproduce
  * libjxl-tiny's final adaptive-quantization conversion after the floating-point
  * image heuristics have produced the AQ map.
  */
class AqMapToRawQuant(
    fractionBits: Int = AdaptiveQuantizationFixedPoint.FractionBits,
    valueBits: Int = AdaptiveQuantizationFixedPoint.ValueBits
) extends Module {
  require(fractionBits > 0, "fractionBits must be positive")
  require(valueBits >= fractionBits, "valueBits must contain the fixed-point fraction")

  val io = IO(new Bundle {
    val aqMapQ = Input(UInt(valueBits.W))
    val invGlobalScaleQ = Input(UInt(valueBits.W))
    val rawQuant = Output(UInt(8.W))
  })

  private val productShift = 2 * fractionBits
  private val roundingBias = (BigInt(1) << (productShift - 1)).U

  val product = io.aqMapQ * io.invGlobalScaleQ
  val rounded = (product +& roundingBias) >> productShift
  val roundedByte = rounded.pad(8)(7, 0)

  io.rawQuant := Mux(
    rounded === 0.U,
    1.U,
    Mux(rounded > 255.U, 255.U, roundedByte)
  )
}

/** Streams a prepared AQ map into exact raw-quant-field traces.
  *
  * The host supplies one unsigned Q24 AQ value per padded 8x8 block in raster
  * order plus the frame's unsigned Q24 inverse global AC scale. This boundary
  * deliberately excludes libjxl-tiny's contrast, erosion, HF, color, and gamma
  * AQ heuristics; it makes their final fixed-point conversion independently
  * traceable before those image-dependent stages move into RTL.
  *
  * Frame dimensions and inverse scale are captured with the first accepted
  * block. A one-entry output register preserves Decoupled stability under
  * trace backpressure.
  */
class FramePreparedAqRawQuantTraceStage(
    c: HjxlConfig = HjxlConfig(),
    fractionBits: Int = AdaptiveQuantizationFixedPoint.FractionBits,
    valueBits: Int = AdaptiveQuantizationFixedPoint.ValueBits
) extends Module {
  private val blockDim = HjxlConstants.BlockDim
  private val maxXBlocks = c.maxFrameWidth / blockDim
  private val maxYBlocks = c.maxFrameHeight / blockDim
  private val maxBlocks = maxXBlocks * maxYBlocks
  private val widthBits = log2Ceil(c.maxFrameWidth + 1)
  private val heightBits = log2Ceil(c.maxFrameHeight + 1)

  require(maxBlocks > 0, "configured frame must contain at least one block")
  require(BigInt(maxBlocks) <= BigInt("ffffffff", 16), "prepared AQ block count must fit the trace index")

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val invGlobalScaleQ = Input(UInt(valueBits.W))
    val input = Flipped(Decoupled(UInt(valueBits.W)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

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

  val frameActive = RegInit(false.B)
  val activeTotalBlocks = RegInit(0.U(32.W))
  val activeInvGlobalScaleQ = RegInit(0.U(valueBits.W))
  val blockIndex = RegInit(0.U(32.W))

  val outputValid = RegInit(false.B)
  val outputBits = Reg(new StageTrace(c))
  val outputLast = RegInit(false.B)

  val converter = Module(new AqMapToRawQuant(fractionBits, valueBits))
  converter.io.aqMapQ := io.input.bits
  converter.io.invGlobalScaleQ := Mux(frameActive, activeInvGlobalScaleQ, io.invGlobalScaleQ)

  val selectedTotalBlocks = Mux(frameActive, activeTotalBlocks, nextTotalBlocks)
  val acceptingNewFrame = !frameActive && !outputValid
  val finalOutputBuffered = outputValid && outputLast
  val outputCanAdvance = !outputValid || io.trace.ready

  io.input.ready := outputCanAdvance && !finalOutputBuffered && (frameActive || !configOutOfRange)
  io.trace.valid := outputValid
  io.trace.bits := outputBits
  io.traceLast := outputValid && outputLast
  io.busy := frameActive || outputValid
  io.overflow := acceptingNewFrame && configOutOfRange

  when(io.input.fire) {
    val inputIsLast = blockIndex === selectedTotalBlocks - 1.U

    outputValid := true.B
    outputBits.stage := TraceStage.RawQuantField.U
    outputBits.group := 0.U
    outputBits.index := blockIndex
    outputBits.value := Cat(0.U(1.W), converter.io.rawQuant).asSInt.pad(c.traceValueBits)
    outputLast := inputIsLast

    when(!frameActive) {
      activeTotalBlocks := nextTotalBlocks
      activeInvGlobalScaleQ := io.invGlobalScaleQ
      when(nextTotalBlocks === 1.U) {
        frameActive := false.B
        blockIndex := 0.U
      }.otherwise {
        frameActive := true.B
        blockIndex := 1.U
      }
    }.elsewhen(inputIsLast) {
      frameActive := false.B
      activeTotalBlocks := 0.U
      activeInvGlobalScaleQ := 0.U
      blockIndex := 0.U
    }.otherwise {
      blockIndex := blockIndex + 1.U
    }
  }.elsewhen(io.trace.fire) {
    outputValid := false.B
    outputLast := false.B
  }
}
