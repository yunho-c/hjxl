// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

class VarDctAcCoefficientTokenTraceInput(c: HjxlConfig) extends Bundle {
  val group = UInt(c.groupBits.W)
  val channel = UInt(2.W)
  val strategy = UInt(2.W)
  val coefficientCount = UInt(8.W)
  val coveredBlocks = UInt(2.W)
  val numNonzeros = UInt(8.W)
  val quantized = Vec(QuantizeVarDctBlock.MaxCoefficients, SInt(c.traceValueBits.W))
}

class VarDctAcBlockTokenTraceInput(c: HjxlConfig) extends Bundle {
  val group = UInt(c.groupBits.W)
  val channel = UInt(2.W)
  val strategy = UInt(2.W)
  val coefficientCount = UInt(8.W)
  val coveredBlocks = UInt(2.W)
  val predictedNonzeros = UInt(8.W)
  val numNonzeros = UInt(8.W)
  val quantized = Vec(QuantizeVarDctBlock.MaxCoefficients, SInt(c.traceValueBits.W))
}

class VarDctAcOwnerTokenTraceInput(c: HjxlConfig) extends Bundle {
  val group = UInt(c.groupBits.W)
  val strategy = UInt(2.W)
  val coefficientCount = UInt(8.W)
  val coveredBlocks = UInt(2.W)
  val predictedNonzeros = Vec(3, UInt(8.W))
  val numNonzeros = Vec(3, UInt(8.W))
  val quantized = Vec(3, Vec(QuantizeVarDctBlock.MaxCoefficients, SInt(c.traceValueBits.W)))
}

/** Emits variable-shape AC coefficient value tokens for one channel owner.
  *
  * Ordinary DCT starts after one DC coefficient and uses the 64-entry scan.
  * Both rectangular orientations share the canonical 128-entry scan and skip
  * the two low-frequency coefficients represented in the two-cell DC image.
  */
class VarDctAcCoefficientTokenTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  private val maxCoefficients = QuantizeVarDctBlock.MaxCoefficients
  private val coefficientIndexBits = log2Ceil(maxCoefficients)

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new VarDctAcCoefficientTokenTraceInput(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
  })

  val idle :: emitting :: Nil = Enum(2)
  val state = RegInit(idle)
  val baseGroup = Reg(UInt(c.groupBits.W))
  val channel = Reg(UInt(2.W))
  val strategy = Reg(UInt(2.W))
  val coefficientCount = Reg(UInt(8.W))
  val coveredBlocks = Reg(UInt(2.W))
  val remaining = Reg(UInt(8.W))
  // Keep one extra bit for the 127 -> 128 termination comparison. A seven-bit
  // index alone would wrap before it could prove a complete rectangular scan.
  val scanIndex = Reg(UInt((coefficientIndexBits + 1).W))
  val emitted = Reg(UInt(8.W))
  val prev = Reg(Bool())
  val quantized = Reg(Vec(maxCoefficients, SInt(c.traceValueBits.W)))

  val dctOrder = VecInit(
    (Tokenize.DctCoeffOrder ++ Seq.fill(maxCoefficients - Tokenize.DctCoeffOrder.length)(0))
      .map(_.U(coefficientIndexBits.W))
  )
  val rectangularOrder = VecInit(Tokenize.RectangularCoeffOrder.map(_.U(coefficientIndexBits.W)))
  val scanAddress = scanIndex(coefficientIndexBits - 1, 0)
  val coefficientOffset = Mux(strategy === AcStrategyCode.Dct.U, dctOrder(scanAddress), rectangularOrder(scanAddress))
  val coefficient = quantized(coefficientOffset)
  val blockContext = Tokenize.blockContext(channel, strategy)
  val log2CoveredBlocks = Mux(coveredBlocks === 2.U, 1.U, 0.U)
  val context =
    Tokenize.zeroDensityContextsOffset(blockContext) +
      Tokenize.zeroDensityContext(remaining, scanIndex, coveredBlocks, log2CoveredBlocks, prev)
  val packedCoefficient = Tokenize.packSigned(coefficient, c.traceValueBits)

  io.input.ready := state === idle
  io.trace.valid := state === emitting && remaining =/= 0.U
  io.trace.bits.stage := TraceStage.AcTokens.U
  io.trace.bits.group := baseGroup + emitted
  io.trace.bits.index := context
  io.trace.bits.value := packedCoefficient
  io.traceLast := io.trace.valid && coefficient =/= 0.S && remaining === 1.U
  io.busy := state =/= idle

  when(io.input.fire) {
    val isDct = io.input.bits.strategy === AcStrategyCode.Dct.U
    assert(io.input.bits.strategy <= AcStrategyCode.Dct8x16.U, "unsupported VarDCT AC strategy")
    assert(
      io.input.bits.coefficientCount === Mux(isDct, 64.U, 128.U),
      "VarDCT coefficient count must match the strategy"
    )
    assert(
      io.input.bits.coveredBlocks === Mux(isDct, 1.U, 2.U),
      "VarDCT covered-block count must match the strategy"
    )
    assert(
      io.input.bits.numNonzeros <= io.input.bits.coefficientCount - io.input.bits.coveredBlocks,
      "VarDCT nonzero count exceeds the AC payload"
    )
    baseGroup := io.input.bits.group
    channel := io.input.bits.channel
    strategy := io.input.bits.strategy
    coefficientCount := io.input.bits.coefficientCount
    coveredBlocks := io.input.bits.coveredBlocks
    remaining := io.input.bits.numNonzeros
    scanIndex := io.input.bits.coveredBlocks.pad(coefficientIndexBits + 1)
    emitted := 0.U
    prev := io.input.bits.numNonzeros <= (io.input.bits.coefficientCount >> 4)
    quantized := io.input.bits.quantized
    state := Mux(io.input.bits.numNonzeros === 0.U, idle, emitting)
  }

  when(io.trace.fire) {
    val isNonzero = coefficient =/= 0.S
    val nextRemaining = remaining - isNonzero.asUInt
    val nextScanIndex = scanIndex +& 1.U
    remaining := nextRemaining
    scanIndex := nextScanIndex(coefficientIndexBits, 0)
    emitted := emitted + 1.U
    prev := isNonzero
    when(nextRemaining === 0.U || nextScanIndex === coefficientCount) {
      assert(nextRemaining === 0.U, "VarDCT coefficient scan ended with nonzeros remaining")
      state := idle
      remaining := 0.U
    }
  }
}

/** Prefixes one channel's variable-shape coefficient scan with its raw count. */
class VarDctAcBlockTokenTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new VarDctAcBlockTokenTraceInput(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
  })

  val idle :: emitPrefix :: emitCoefficients :: Nil = Enum(3)
  val state = RegInit(idle)
  val input = Reg(new VarDctAcBlockTokenTraceInput(c))
  val coefficientTokens = Module(new VarDctAcCoefficientTokenTraceStage(c))

  coefficientTokens.io.input.valid := state === emitPrefix && io.trace.ready && input.numNonzeros =/= 0.U
  coefficientTokens.io.input.bits.group := input.group + 1.U
  coefficientTokens.io.input.bits.channel := input.channel
  coefficientTokens.io.input.bits.strategy := input.strategy
  coefficientTokens.io.input.bits.coefficientCount := input.coefficientCount
  coefficientTokens.io.input.bits.coveredBlocks := input.coveredBlocks
  coefficientTokens.io.input.bits.numNonzeros := input.numNonzeros
  coefficientTokens.io.input.bits.quantized := input.quantized
  coefficientTokens.io.trace.ready := io.trace.ready && state === emitCoefficients

  val blockContext = Tokenize.blockContext(input.channel, input.strategy)
  val prefixTrace = Wire(new StageTrace(c))
  prefixTrace.stage := TraceStage.AcTokens.U
  prefixTrace.group := input.group
  prefixTrace.index := Tokenize.nonzeroContext(input.predictedNonzeros, blockContext)
  prefixTrace.value := input.numNonzeros.asSInt.pad(c.traceValueBits)

  io.input.ready := state === idle
  io.trace.valid := Mux(state === emitPrefix, true.B, state === emitCoefficients && coefficientTokens.io.trace.valid)
  io.trace.bits := Mux(state === emitPrefix, prefixTrace, coefficientTokens.io.trace.bits)
  io.traceLast := Mux(state === emitPrefix, input.numNonzeros === 0.U, coefficientTokens.io.traceLast)
  io.busy := state =/= idle

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

/** Emits Y, X, then B AC token streams for one first-block owner. */
class VarDctAcOwnerTokenTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new VarDctAcOwnerTokenTraceInput(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
  })

  val idle :: startChannel :: emitChannel :: Nil = Enum(3)
  val state = RegInit(idle)
  val input = Reg(new VarDctAcOwnerTokenTraceInput(c))
  val channelOrdinal = RegInit(0.U(2.W))
  val nextGroup = Reg(UInt(c.groupBits.W))
  val channelTokens = Module(new VarDctAcBlockTokenTraceStage(c))

  val channel = MuxLookup(channelOrdinal, 2.U)(Seq(0.U -> 1.U, 1.U -> 0.U, 2.U -> 2.U))
  val channelSafe = channel(1, 0)
  channelTokens.io.input.valid := state === startChannel
  channelTokens.io.input.bits.group := nextGroup
  channelTokens.io.input.bits.channel := channel
  channelTokens.io.input.bits.strategy := input.strategy
  channelTokens.io.input.bits.coefficientCount := input.coefficientCount
  channelTokens.io.input.bits.coveredBlocks := input.coveredBlocks
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
