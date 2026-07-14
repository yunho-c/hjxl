// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

class VarDctAcCoefficientOwner(c: HjxlConfig) extends Bundle {
  val coefficientCount = UInt(8.W)
  val quantized = Vec(3, Vec(QuantizeVarDctBlock.MaxCoefficients, SInt(c.traceValueBits.W)))
}

class VarDctAcCoefficientReadRequest(c: HjxlConfig) extends Bundle {
  private val maxBlocks =
    (c.maxFrameWidth / HjxlConstants.BlockDim) * (c.maxFrameHeight / HjxlConstants.BlockDim)
  val owner = UInt(math.max(1, log2Ceil(maxBlocks)).W)
  val coefficientCount = UInt(8.W)
}

/** Narrow synchronous store for first-block-owned 64/128 coefficient records. */
class VarDctAcCoefficientFrameStore(c: HjxlConfig = HjxlConfig()) extends Module {
  private val maxCoefficients = QuantizeVarDctBlock.MaxCoefficients
  private val maxBlocks =
    (c.maxFrameWidth / HjxlConstants.BlockDim) * (c.maxFrameHeight / HjxlConstants.BlockDim)
  private val ownerBits = math.max(1, log2Ceil(maxBlocks))
  private val coefficientBits = log2Ceil(maxCoefficients)
  private val issueBits = log2Ceil(maxCoefficients + 1)
  private val memoryDepth = maxBlocks * maxCoefficients
  private val addressBits = math.max(1, log2Ceil(memoryDepth))

  val io = IO(new Bundle {
    val writeOwner = Input(UInt(ownerBits.W))
    val write = Flipped(Decoupled(new VarDctAcCoefficientOwner(c)))
    val readRequest = Flipped(Decoupled(new VarDctAcCoefficientReadRequest(c)))
    val readResponse = Decoupled(new VarDctAcCoefficientOwner(c))
    val busy = Output(Bool())
  })

  val memory = SyncReadMem(memoryDepth, Vec(3, SInt(c.traceValueBits.W)))
  val buffer = Reg(new VarDctAcCoefficientOwner(c))
  val idle :: writing :: reading :: responding :: Nil = Enum(4)
  val state = RegInit(idle)
  val owner = Reg(UInt(ownerBits.W))
  val coefficientCount = Reg(UInt(8.W))
  val coefficient = RegInit(0.U(coefficientBits.W))
  val readIssue = RegInit(0.U(issueBits.W))
  val readCaptureIndex = Reg(UInt(coefficientBits.W))

  val writeAddressWide = owner * maxCoefficients.U + coefficient
  val writeAddress = writeAddressWide(addressBits - 1, 0)
  val writeData = Wire(Vec(3, SInt(c.traceValueBits.W)))
  for (channel <- 0 until 3) {
    writeData(channel) := buffer.quantized(channel)(coefficient)
  }

  val readEnable = state === reading && readIssue < coefficientCount
  val readAddressWide = owner * maxCoefficients.U + readIssue
  val readAddress = readAddressWide(addressBits - 1, 0)
  val readData = memory.read(readAddress, readEnable)
  val readCaptureValid = RegNext(readEnable, false.B)

  io.write.ready := state === idle
  io.readRequest.ready := state === idle && !io.write.valid
  io.readResponse.valid := state === responding
  io.readResponse.bits := buffer
  io.busy := state =/= idle

  when(io.write.fire) {
    assert(io.writeOwner < maxBlocks.U, "VarDCT AC owner write must fit the frame store")
    assert(
      io.write.bits.coefficientCount === 64.U || io.write.bits.coefficientCount === 128.U,
      "VarDCT AC owner must contain 64 or 128 coefficients"
    )
    buffer := io.write.bits
    owner := io.writeOwner
    coefficientCount := io.write.bits.coefficientCount
    coefficient := 0.U
    state := writing
  }

  when(state === writing) {
    memory.write(writeAddress, writeData)
    when(coefficient === coefficientCount - 1.U) {
      coefficient := 0.U
      state := idle
    }.otherwise {
      coefficient := coefficient + 1.U
    }
  }

  when(io.readRequest.fire) {
    assert(io.readRequest.bits.owner < maxBlocks.U, "VarDCT AC owner read must fit the frame store")
    assert(
      io.readRequest.bits.coefficientCount === 64.U || io.readRequest.bits.coefficientCount === 128.U,
      "VarDCT AC read must request 64 or 128 coefficients"
    )
    owner := io.readRequest.bits.owner
    coefficientCount := io.readRequest.bits.coefficientCount
    buffer.coefficientCount := io.readRequest.bits.coefficientCount
    readIssue := 0.U
    state := reading
  }

  when(readEnable) {
    readCaptureIndex := readIssue(coefficientBits - 1, 0)
    readIssue := readIssue + 1.U
  }

  when(readCaptureValid) {
    for (channel <- 0 until 3) {
      buffer.quantized(channel)(readCaptureIndex) := readData(channel)
    }
    when(readCaptureIndex === coefficientCount - 1.U) {
      state := responding
    }
  }

  when(io.readResponse.fire) {
    state := idle
  }
}

/** Emits exact AC tokens from first-block-owned prepared VarDCT results.
  *
  * The input geometry is checked independently of the quantizer. Shifted
  * nonzero counts are replicated into both covered cells for prediction, while
  * each owner's raw count and 64/128 coefficients are tokenized only once.
  */
class FramePreparedVarDctAcTokenTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  private val blockDim = HjxlConstants.BlockDim
  private val maxXBlocks = c.maxFrameWidth / blockDim
  private val maxYBlocks = c.maxFrameHeight / blockDim
  private val maxBlocks = maxXBlocks * maxYBlocks
  private val blockBits = math.max(1, log2Ceil(maxBlocks))
  private val countBits = math.max(1, log2Ceil(maxBlocks + 1))

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new PreparedVarDctQuantizedFrameBlock(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  private def ceilDiv(value: UInt, divisor: Int): UInt =
    (value +& (divisor - 1).U) / divisor.U

  private def blockIndex(value: UInt): UInt = value(blockBits - 1, 0)
  private def ownerIndex(value: UInt): UInt = value(blockBits - 1, 0)
  private def blockAt[T <: Data](values: Vec[T], value: UInt): T =
    if (maxBlocks == 1) values(0) else values(blockIndex(value))

  val ownerBlockOrdinal = Reg(Vec(maxBlocks, UInt(countBits.W)))
  val ownerStrategy = Reg(Vec(maxBlocks, UInt(2.W)))
  val ownerCoefficientCount = Reg(Vec(maxBlocks, UInt(8.W)))
  val ownerCoveredBlocks = Reg(Vec(maxBlocks, UInt(2.W)))
  val ownerNumNonzeros = Reg(Vec(maxBlocks, Vec(3, UInt(8.W))))
  val predictionMap = Reg(Vec(maxBlocks, Vec(3, UInt(8.W))))
  val covered = RegInit(VecInit(Seq.fill(maxBlocks)(false.B)))

  val receiving :: waitForFinalWrite :: requestOwner :: waitForOwner :: emitOwner :: Nil = Enum(5)
  val state = RegInit(receiving)
  val frameActive = RegInit(false.B)
  val xBlocks = RegInit(0.U(countBits.W))
  val yBlocks = RegInit(0.U(countBits.W))
  val totalBlocks = RegInit(0.U(countBits.W))
  val ownerCount = RegInit(0.U(countBits.W))
  val emitOwnerOrdinal = RegInit(0.U(countBits.W))
  val tokenOrdinal = RegInit(0.U(c.groupBits.W))
  val overflow = RegInit(false.B)

  val nextXBlocks = ceilDiv(io.config.xsize, blockDim)
  val nextYBlocks = ceilDiv(io.config.ysize, blockDim)
  val nextTotalBlocks = nextXBlocks * nextYBlocks
  val configOutOfRange =
    io.config.xsize === 0.U || io.config.ysize === 0.U ||
      io.config.xsize > c.maxFrameWidth.U || io.config.ysize > c.maxFrameHeight.U ||
      nextXBlocks > maxXBlocks.U || nextYBlocks > maxYBlocks.U || nextTotalBlocks > maxBlocks.U
  val activeXBlocks = Mux(frameActive, xBlocks, nextXBlocks)
  val activeYBlocks = Mux(frameActive, yBlocks, nextYBlocks)
  val activeTotalBlocks = Mux(frameActive, totalBlocks, nextTotalBlocks)

  val strategy = io.input.bits.result.strategy
  val isDct = strategy === AcStrategyCode.Dct.U
  val isVertical = strategy === AcStrategyCode.Dct16x8.U
  val isHorizontal = strategy === AcStrategyCode.Dct8x16.U
  val isRectangular = isVertical || isHorizontal
  val ownerOrdinalWide = io.input.bits.blockY * activeXBlocks + io.input.bits.blockX
  val inputOwnerOrdinal = ownerOrdinalWide(countBits - 1, 0)
  val secondOrdinal = Mux(isVertical, inputOwnerOrdinal + activeXBlocks, inputOwnerOrdinal + 1.U)
  val coordinateInBounds =
    io.input.bits.blockX < activeXBlocks && io.input.bits.blockY < activeYBlocks
  val rectangleInBounds = Mux(
    isVertical,
    io.input.bits.blockY + 1.U < activeYBlocks,
    Mux(isHorizontal, io.input.bits.blockX + 1.U < activeXBlocks, true.B)
  )
  val supportedStrategy = strategy <= AcStrategyCode.Dct8x16.U
  val shapeMatches =
    io.input.bits.result.coefficientCount === Mux(isDct, 64.U, 128.U) &&
      io.input.bits.result.coveredBlocks === Mux(isDct, 1.U, 2.U)
  val ordinalMatches = io.input.bits.blockOrdinal === inputOwnerOrdinal
  val ownerAlreadyCovered = Mux(coordinateInBounds, blockAt(covered, inputOwnerOrdinal), true.B)
  val secondAlreadyCovered =
    Mux(isRectangular && rectangleInBounds, blockAt(covered, secondOrdinal), false.B)
  val earlierUncovered = (0 until maxBlocks).map { index =>
    index.U < inputOwnerOrdinal && index.U < activeTotalBlocks && !covered(index)
  }.reduce(_ || _)
  val geometryValid =
    !configOutOfRange && supportedStrategy && coordinateInBounds && rectangleInBounds && shapeMatches &&
      ordinalMatches && !ownerAlreadyCovered && !secondAlreadyCovered && !earlierUncovered

  val coveredAfter = Wire(Vec(maxBlocks, Bool()))
  coveredAfter := covered
  when(geometryValid) {
    blockAt(coveredAfter, inputOwnerOrdinal) := true.B
    when(isRectangular) {
      blockAt(coveredAfter, secondOrdinal) := true.B
    }
  }
  val allCoveredAfter = (0 until maxBlocks).map { index =>
    index.U >= activeTotalBlocks || coveredAfter(index)
  }.reduce(_ && _)
  val validRecord = geometryValid && io.input.bits.last === allCoveredAfter

  val coefficientStore = Module(new VarDctAcCoefficientFrameStore(c))
  coefficientStore.io.writeOwner := ownerIndex(ownerCount)
  coefficientStore.io.write.valid := state === receiving && io.input.valid && validRecord
  coefficientStore.io.write.bits.coefficientCount := io.input.bits.result.coefficientCount
  coefficientStore.io.write.bits.quantized := io.input.bits.result.quantizedAc
  coefficientStore.io.readRequest.valid := state === requestOwner
  coefficientStore.io.readRequest.bits.owner := ownerIndex(emitOwnerOrdinal)
  coefficientStore.io.readRequest.bits.coefficientCount :=
    blockAt(ownerCoefficientCount, emitOwnerOrdinal)

  val currentBlockOrdinal = blockAt(ownerBlockOrdinal, emitOwnerOrdinal)
  val xBlocksSafe = Mux(xBlocks === 0.U, 1.U, xBlocks)
  val blockX = currentBlockOrdinal - (currentBlockOrdinal / xBlocksSafe) * xBlocksSafe
  val blockY = currentBlockOrdinal / xBlocksSafe
  val westOrdinal = currentBlockOrdinal - 1.U
  val northOrdinal = currentBlockOrdinal - xBlocksSafe
  val predicted = Wire(Vec(3, UInt(8.W)))
  for (channel <- 0 until 3) {
    val west = blockAt(predictionMap, westOrdinal)(channel)
    val north = blockAt(predictionMap, northOrdinal)(channel)
    predicted(channel) := Mux(
      blockX === 0.U,
      Mux(blockY === 0.U, 32.U, north),
      Mux(blockY === 0.U, west, (north +& west + 1.U) >> 1)
    )
  }

  val ownerTokens = Module(new VarDctAcOwnerTokenTraceStage(c))
  ownerTokens.io.input.valid := state === waitForOwner && coefficientStore.io.readResponse.valid
  ownerTokens.io.input.bits.group := tokenOrdinal
  ownerTokens.io.input.bits.strategy := blockAt(ownerStrategy, emitOwnerOrdinal)
  ownerTokens.io.input.bits.coefficientCount := blockAt(ownerCoefficientCount, emitOwnerOrdinal)
  ownerTokens.io.input.bits.coveredBlocks := blockAt(ownerCoveredBlocks, emitOwnerOrdinal)
  ownerTokens.io.input.bits.predictedNonzeros := predicted
  ownerTokens.io.input.bits.numNonzeros := blockAt(ownerNumNonzeros, emitOwnerOrdinal)
  ownerTokens.io.input.bits.quantized := coefficientStore.io.readResponse.bits.quantized
  ownerTokens.io.trace.ready := io.trace.ready && state === emitOwner
  coefficientStore.io.readResponse.ready := state === waitForOwner && ownerTokens.io.input.ready

  io.input.ready := state === receiving && coefficientStore.io.write.ready
  io.trace.valid := state === emitOwner && ownerTokens.io.trace.valid
  io.trace.bits := ownerTokens.io.trace.bits
  io.traceLast :=
    state === emitOwner && ownerCount =/= 0.U && emitOwnerOrdinal === ownerCount - 1.U && ownerTokens.io.traceLast
  io.busy := state =/= receiving || frameActive || coefficientStore.io.busy
  io.overflow := overflow || configOutOfRange

  when(io.input.fire) {
    when(validRecord) {
      blockAt(ownerBlockOrdinal, ownerCount) := inputOwnerOrdinal
      blockAt(ownerStrategy, ownerCount) := strategy
      blockAt(ownerCoefficientCount, ownerCount) := io.input.bits.result.coefficientCount
      blockAt(ownerCoveredBlocks, ownerCount) := io.input.bits.result.coveredBlocks
      blockAt(ownerNumNonzeros, ownerCount) := io.input.bits.result.numNonzeros
      blockAt(predictionMap, inputOwnerOrdinal) := io.input.bits.result.shiftedNumNonzeros
      when(isRectangular) {
        blockAt(predictionMap, secondOrdinal) := io.input.bits.result.shiftedNumNonzeros
      }
      covered := coveredAfter
      ownerCount := ownerCount + 1.U
      when(!frameActive) {
        frameActive := true.B
        xBlocks := nextXBlocks(countBits - 1, 0)
        yBlocks := nextYBlocks(countBits - 1, 0)
        totalBlocks := nextTotalBlocks(countBits - 1, 0)
      }
      when(allCoveredAfter) {
        state := waitForFinalWrite
        emitOwnerOrdinal := 0.U
        tokenOrdinal := 0.U
      }
    }.otherwise {
      overflow := true.B
      frameActive := false.B
      xBlocks := 0.U
      yBlocks := 0.U
      totalBlocks := 0.U
      ownerCount := 0.U
      covered := VecInit(Seq.fill(maxBlocks)(false.B))
    }
  }

  when(state === waitForFinalWrite && !coefficientStore.io.busy) {
    state := requestOwner
  }
  when(state === requestOwner && coefficientStore.io.readRequest.fire) {
    state := waitForOwner
  }
  when(state === waitForOwner && ownerTokens.io.input.fire) {
    state := emitOwner
  }
  when(io.trace.fire) {
    tokenOrdinal := io.trace.bits.group + 1.U
  }
  when(state === emitOwner && !ownerTokens.io.busy && !ownerTokens.io.trace.valid) {
    val nextOwner = emitOwnerOrdinal + 1.U
    when(nextOwner === ownerCount) {
      state := receiving
      frameActive := false.B
      xBlocks := 0.U
      yBlocks := 0.U
      totalBlocks := 0.U
      ownerCount := 0.U
      emitOwnerOrdinal := 0.U
      tokenOrdinal := 0.U
      covered := VecInit(Seq.fill(maxBlocks)(false.B))
    }.otherwise {
      emitOwnerOrdinal := nextOwner
      state := requestOwner
    }
  }

  when(io.input.fire && ownerCount >= maxBlocks.U) {
    overflow := true.B
  }
}
