// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

class AcCoefficientTokenTraceInput(c: HjxlConfig) extends Bundle {
  val group = UInt(c.groupBits.W)
  val channel = UInt(2.W)
  val numNonzeros = UInt(8.W)
  val quantized = Vec(HjxlConstants.BlockDim * HjxlConstants.BlockDim, SInt(c.traceValueBits.W))
}

class AcBlockTokenTraceInput(c: HjxlConfig) extends Bundle {
  val group = UInt(c.groupBits.W)
  val channel = UInt(2.W)
  val predictedNonzeros = UInt(8.W)
  val numNonzeros = UInt(8.W)
  val quantized = Vec(HjxlConstants.BlockDim * HjxlConstants.BlockDim, SInt(c.traceValueBits.W))
}

class DctOnlyAcBlockTokenTraceInput(c: HjxlConfig) extends Bundle {
  val group = UInt(c.groupBits.W)
  val predictedNonzeros = Vec(3, UInt(8.W))
  val numNonzeros = Vec(3, UInt(8.W))
  val quantized = Vec(3, Vec(HjxlConstants.BlockDim * HjxlConstants.BlockDim, SInt(c.traceValueBits.W)))
}

/** Emits a complete prepared ordinary-DCT AC token stream for one X/Y/B block.
  *
  * Channels are emitted in libjxl-tiny order: Y, X, then B. The caller supplies
  * the predicted nonzero count per channel from frame neighbors, the actual
  * nonzero count, and quantized coefficients.
  */
class DctOnlyAcBlockTokenTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new DctOnlyAcBlockTokenTraceInput(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
  })

  val idle :: startChannel :: emitChannel :: Nil = Enum(3)
  val state = RegInit(idle)
  val input = Reg(new DctOnlyAcBlockTokenTraceInput(c))
  val channelOrdinal = RegInit(0.U(2.W))
  val nextGroup = Reg(UInt(c.groupBits.W))
  val channelTokens = Module(new AcBlockTokenTraceStage(c))

  val channel = MuxLookup(channelOrdinal, 2.U)(
    Seq(
      0.U -> 1.U,
      1.U -> 0.U,
      2.U -> 2.U
    )
  )
  val channelSafe = channel(1, 0)

  channelTokens.io.input.valid := state === startChannel
  channelTokens.io.input.bits.group := nextGroup
  channelTokens.io.input.bits.channel := channel
  channelTokens.io.input.bits.predictedNonzeros := input.predictedNonzeros(channelSafe)
  channelTokens.io.input.bits.numNonzeros := input.numNonzeros(channelSafe)
  channelTokens.io.input.bits.quantized := input.quantized(channelSafe)
  channelTokens.io.trace.ready := io.trace.ready && state === emitChannel

  io.input.ready := state === idle
  io.trace.valid := state === emitChannel && channelTokens.io.trace.valid
  io.trace.bits := channelTokens.io.trace.bits
  io.traceLast := state === emitChannel && channelOrdinal === 2.U && channelTokens.io.traceLast
  io.busy := state =/= idle

  when(io.input.fire) {
    input := io.input.bits
    channelOrdinal := 0.U
    nextGroup := io.input.bits.group
    state := startChannel
  }

  when(state === startChannel && channelTokens.io.input.ready) {
    state := emitChannel
  }

  when(io.trace.fire) {
    nextGroup := io.trace.bits.group + 1.U
  }

  when(state === emitChannel && !channelTokens.io.busy && !channelTokens.io.trace.valid) {
    when(channelOrdinal === 2.U) {
      state := idle
    }.otherwise {
      channelOrdinal := channelOrdinal + 1.U
      state := startChannel
    }
  }
}

/** Emits the complete ordinary-DCT AC token stream for one prepared block/channel.
  *
  * The first token is the nonzero-count prefix predicted from neighboring
  * blocks. Remaining tokens, if any, are coefficient scan/value tokens emitted
  * by `AcCoefficientTokenTraceStage`.
  */
class AcBlockTokenTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new AcBlockTokenTraceInput(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
  })

  val idle :: emitPrefix :: emitCoefficients :: Nil = Enum(3)
  val state = RegInit(idle)
  val input = Reg(new AcBlockTokenTraceInput(c))
  val coefficientTokens = Module(new AcCoefficientTokenTraceStage(c))

  coefficientTokens.io.input.valid := state === emitPrefix && io.trace.ready && input.numNonzeros =/= 0.U
  coefficientTokens.io.input.bits.group := input.group + 1.U
  coefficientTokens.io.input.bits.channel := input.channel
  coefficientTokens.io.input.bits.numNonzeros := input.numNonzeros
  coefficientTokens.io.input.bits.quantized := input.quantized
  coefficientTokens.io.trace.ready := io.trace.ready && state === emitCoefficients

  val blockContext = VecInit(Tokenize.DctBlockContextByChannel.map(_.U(2.W)))(input.channel)
  val prefixContext = Tokenize.nonzeroContext(input.predictedNonzeros, blockContext)

  val prefixTrace = Wire(new StageTrace(c))
  prefixTrace.stage := TraceStage.AcTokens.U
  prefixTrace.group := input.group
  prefixTrace.index := prefixContext
  prefixTrace.value := input.numNonzeros.asSInt.pad(c.traceValueBits)

  io.input.ready := state === idle
  io.trace.valid := Mux(state === emitPrefix, true.B, coefficientTokens.io.trace.valid && state === emitCoefficients)
  io.busy := state =/= idle
  io.trace.bits := Mux(state === emitPrefix, prefixTrace, coefficientTokens.io.trace.bits)
  io.traceLast := Mux(state === emitPrefix, input.numNonzeros === 0.U, coefficientTokens.io.traceLast)

  when(io.input.fire) {
    input := io.input.bits
    state := emitPrefix
  }

  when(state === emitPrefix && io.trace.fire) {
    state := Mux(input.numNonzeros === 0.U, idle, emitCoefficients)
  }

  when(state === emitCoefficients && !coefficientTokens.io.busy && !coefficientTokens.io.trace.valid) {
    state := idle
  }
}

/** Emits DCT-only AC coefficient value tokens for one prepared quantized block.
  *
  * This stage assumes ordinary 8x8 DCT. The caller must emit the nonzero-count
  * prefix token separately, then feed the same quantized block here. The stage
  * follows libjxl-tiny's DCT scan order and zero-density context formula,
  * emitting signed packed coefficient tokens until all nonzero AC coefficients
  * have been consumed. `input.group` is the first output token ordinal.
  */
class AcCoefficientTokenTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new AcCoefficientTokenTraceInput(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
  })

  val idle :: emitting :: Nil = Enum(2)
  val state = RegInit(idle)
  val baseGroup = Reg(UInt(c.groupBits.W))
  val channel = Reg(UInt(2.W))
  val remaining = Reg(UInt(8.W))
  val scanIndex = Reg(UInt(log2Ceil(blockSize).W))
  val emitted = Reg(UInt(log2Ceil(blockSize).W))
  val prevNonzero = Reg(Bool())
  val quantized = Reg(Vec(blockSize, SInt(c.traceValueBits.W)))

  val order = VecInit(Tokenize.DctCoeffOrder.map(_.U(log2Ceil(blockSize).W)))
  val coefficientOffset = order(scanIndex)
  val coefficient = quantized(coefficientOffset)
  val blockContext = VecInit(Tokenize.DctBlockContextByChannel.map(_.U(2.W)))(channel)
  val context =
    Tokenize.zeroDensityContextsOffset(blockContext) +
      Tokenize.zeroDensityContext(remaining, scanIndex, prevNonzero)
  val packedCoefficient = Tokenize.packSigned(coefficient, c.traceValueBits)

  io.input.ready := state === idle
  io.trace.valid := state === emitting && remaining =/= 0.U
  io.busy := state =/= idle
  io.trace.bits.stage := TraceStage.AcTokens.U
  io.trace.bits.group := baseGroup + emitted
  io.trace.bits.index := context
  io.trace.bits.value := packedCoefficient
  io.traceLast := io.trace.valid && coefficient =/= 0.S && remaining === 1.U

  when(io.input.fire) {
    baseGroup := io.input.bits.group
    channel := io.input.bits.channel
    remaining := io.input.bits.numNonzeros
    scanIndex := 1.U
    emitted := 0.U
    prevNonzero := io.input.bits.numNonzeros <= (blockSize / 16).U
    quantized := io.input.bits.quantized
    state := Mux(io.input.bits.numNonzeros === 0.U, idle, emitting)
  }

  when(io.trace.fire) {
    val isNonzero = coefficient =/= 0.S
    val nextRemaining = remaining - isNonzero.asUInt
    val nextScanIndex = scanIndex + 1.U
    remaining := nextRemaining
    scanIndex := nextScanIndex
    emitted := emitted + 1.U
    prevNonzero := isNonzero
    when(nextRemaining === 0.U || nextScanIndex === blockSize.U) {
      state := idle
      remaining := 0.U
    }
  }
}
