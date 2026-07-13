// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

class PreparedAcBlockTraceInput(c: HjxlConfig) extends Bundle {
  val numNonzeros = Vec(3, UInt(8.W))
  val quantized = Vec(3, Vec(HjxlConstants.BlockDim * HjxlConstants.BlockDim, SInt(c.traceValueBits.W)))
}

class PreparedAcCoefficientBlock(c: HjxlConfig) extends Bundle {
  val quantized = Vec(3, Vec(HjxlConstants.BlockDim * HjxlConstants.BlockDim, SInt(c.traceValueBits.W)))
}

/** Narrow synchronous frame store for prepared quantized AC coefficients.
  *
  * The structured prepared boundary presents all three 8x8 channel blocks at
  * once, but retaining that shape for an entire frame creates a large register
  * array. This store captures one block locally, serializes one X/Y/B
  * coefficient triplet per cycle into a 96-bit-wide `SyncReadMem`, and reverses
  * the process on readback. The surrounding 201-word packed input parser gives
  * the 64-cycle write drain enough time to finish before the next block is
  * presented, so the frozen stream input rate is unchanged.
  */
class PreparedAcCoefficientFrameStore(c: HjxlConfig = HjxlConfig()) extends Module {
  private val blockDim = HjxlConstants.BlockDim
  private val blockSize = blockDim * blockDim
  private val maxBlocks = (c.maxFrameWidth / blockDim) * (c.maxFrameHeight / blockDim)
  private val blockIndexBits = math.max(1, log2Ceil(maxBlocks))
  private val coefficientIndexBits = log2Ceil(blockSize)
  private val readIssueBits = log2Ceil(blockSize + 1)
  private val memoryDepth = maxBlocks * blockSize
  private val memoryAddressBits = math.max(1, log2Ceil(memoryDepth))

  val io = IO(new Bundle {
    val writeBlock = Input(UInt(blockIndexBits.W))
    val write = Flipped(Decoupled(new PreparedAcCoefficientBlock(c)))
    val readRequest = Flipped(Decoupled(UInt(blockIndexBits.W)))
    val readResponse = Decoupled(new PreparedAcCoefficientBlock(c))
    val busy = Output(Bool())
  })

  val coefficientMemory = SyncReadMem(memoryDepth, Vec(3, SInt(c.traceValueBits.W)))
  val blockBuffer = Reg(new PreparedAcCoefficientBlock(c))

  val idle :: writing :: reading :: responding :: Nil = Enum(4)
  val state = RegInit(idle)
  val writeBlock = Reg(UInt(blockIndexBits.W))
  val writeCoefficient = RegInit(0.U(coefficientIndexBits.W))
  val readBlock = Reg(UInt(blockIndexBits.W))
  val readIssue = RegInit(0.U(readIssueBits.W))
  val readCaptureIndex = Reg(UInt(coefficientIndexBits.W))

  val writeAddressWide = writeBlock * blockSize.U + writeCoefficient
  val writeAddress = writeAddressWide(memoryAddressBits - 1, 0)
  val writeData = Wire(Vec(3, SInt(c.traceValueBits.W)))
  for (channel <- 0 until 3) {
    writeData(channel) := blockBuffer.quantized(channel)(writeCoefficient)
  }

  val readEnable = state === reading && readIssue < blockSize.U
  val readAddressWide = readBlock * blockSize.U + readIssue
  val readAddress = readAddressWide(memoryAddressBits - 1, 0)
  val readData = coefficientMemory.read(readAddress, readEnable)
  val readCaptureValid = RegNext(readEnable, false.B)

  io.write.ready := state === idle
  io.readRequest.ready := state === idle && !io.write.valid
  io.readResponse.valid := state === responding
  io.readResponse.bits := blockBuffer
  io.busy := state =/= idle

  when(io.write.fire) {
    assert(io.writeBlock < maxBlocks.U, "prepared AC write block must fit the frame store")
    blockBuffer := io.write.bits
    writeBlock := io.writeBlock
    writeCoefficient := 0.U
    state := writing
  }

  when(state === writing) {
    coefficientMemory.write(writeAddress, writeData)
    when(writeCoefficient === (blockSize - 1).U) {
      writeCoefficient := 0.U
      state := idle
    }.otherwise {
      writeCoefficient := writeCoefficient + 1.U
    }
  }

  when(io.readRequest.fire) {
    assert(io.readRequest.bits < maxBlocks.U, "prepared AC read block must fit the frame store")
    readBlock := io.readRequest.bits
    readIssue := 0.U
    state := reading
  }

  when(readEnable) {
    readCaptureIndex := readIssue(coefficientIndexBits - 1, 0)
    readIssue := readIssue + 1.U
  }

  when(readCaptureValid) {
    for (channel <- 0 until 3) {
      blockBuffer.quantized(channel)(readCaptureIndex) := readData(channel)
    }
    when(readCaptureIndex === (blockSize - 1).U) {
      state := responding
    }
  }

  when(io.readResponse.fire) {
    state := idle
  }
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
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val numNonzeros = Reg(Vec(maxBlocks, Vec(3, UInt(8.W))))

  val receiving :: waitForFinalWrite :: requestBlock :: waitForBlock :: emitBlock :: Nil = Enum(5)
  val state = RegInit(receiving)
  val received = RegInit(0.U(blockCountBits.W))
  val blockOrdinal = RegInit(0.U(blockCountBits.W))
  val tokenOrdinal = RegInit(0.U(c.groupBits.W))
  val xBlocks = RegInit(0.U(32.W))
  val yBlocks = RegInit(0.U(32.W))
  val totalBlocks = RegInit(0.U(32.W))
  val prefetchRequested = RegInit(false.B)
  val overflow = RegInit(false.B)

  private def ceilDiv(value: UInt, divisor: Int): UInt =
    (value +& (divisor - 1).U) / divisor.U

  private def blockIndex(value: UInt): UInt =
    value(blockIndexBits - 1, 0)

  private def nonzerosAt(value: UInt) =
    if (maxBlocks == 1) numNonzeros(0) else numNonzeros(blockIndex(value))

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

  val predictedNonzeros = Wire(Vec(3, UInt(8.W)))
  for (channel <- 0 until 3) {
    val current = nonzerosAt(blockOrdinal)(channel)
    val west = nonzerosAt(westOrdinal)(channel)
    val north = nonzerosAt(northOrdinal)(channel)
    predictedNonzeros(channel) := Mux(
      blockX === 0.U,
      Mux(blockY === 0.U, 32.U, north),
      Mux(blockY === 0.U, west, (north +& west + 1.U) >> 1)
    )
  }

  val coefficientStore = Module(new PreparedAcCoefficientFrameStore(c))
  coefficientStore.io.writeBlock := blockIndex(received)
  coefficientStore.io.write.valid := state === receiving && io.input.valid && !configOutOfRange
  coefficientStore.io.write.bits.quantized := io.input.bits.quantized
  val nextBlockOrdinal = blockOrdinal + 1.U
  val hasNextBlock = totalBlocks =/= 0.U && nextBlockOrdinal < totalBlocks
  val initialReadRequest = state === requestBlock
  val prefetchReadRequest = state === emitBlock && hasNextBlock && !prefetchRequested
  coefficientStore.io.readRequest.valid := initialReadRequest || prefetchReadRequest
  coefficientStore.io.readRequest.bits := Mux(initialReadRequest, currentIndex, blockIndex(nextBlockOrdinal))

  val blockTokens = Module(new DctOnlyAcBlockTokenTraceStage(c))
  blockTokens.io.input.valid := state === waitForBlock && coefficientStore.io.readResponse.valid
  blockTokens.io.input.bits.group := tokenOrdinal
  blockTokens.io.input.bits.predictedNonzeros := predictedNonzeros
  blockTokens.io.input.bits.numNonzeros := nonzerosAt(blockOrdinal)
  blockTokens.io.input.bits.quantized := coefficientStore.io.readResponse.bits.quantized
  blockTokens.io.trace.ready := io.trace.ready && state === emitBlock
  coefficientStore.io.readResponse.ready := state === waitForBlock && blockTokens.io.input.ready

  io.input.ready := state === receiving && !configOutOfRange && coefficientStore.io.write.ready
  io.trace.valid := state === emitBlock && blockTokens.io.trace.valid
  io.trace.bits := blockTokens.io.trace.bits
  io.traceLast := state === emitBlock && totalBlocks =/= 0.U && blockOrdinal === totalBlocks - 1.U && blockTokens.io.traceLast
  io.busy := state =/= receiving || received =/= 0.U || coefficientStore.io.busy
  io.overflow := overflow || configOutOfRange

  when(configOutOfRange) {
    received := 0.U
    state := receiving
    prefetchRequested := false.B
  }.elsewhen(io.input.fire) {
    if (maxBlocks == 1) {
      numNonzeros(0) := io.input.bits.numNonzeros
    } else {
      numNonzeros(blockIndex(received)) := io.input.bits.numNonzeros
    }
    val nextReceived = received + 1.U
    received := nextReceived

    when(nextReceived === nextTotalBlocks) {
      state := waitForFinalWrite
      blockOrdinal := 0.U
      tokenOrdinal := 0.U
      xBlocks := nextXBlocks
      yBlocks := nextYBlocks
      totalBlocks := nextTotalBlocks
    }
  }

  when(state === waitForFinalWrite && !coefficientStore.io.busy) {
    state := requestBlock
  }

  when(state === requestBlock && coefficientStore.io.readRequest.fire) {
    state := waitForBlock
  }

  when(prefetchReadRequest && coefficientStore.io.readRequest.fire) {
    prefetchRequested := true.B
  }

  when(state === waitForBlock && blockTokens.io.input.fire) {
    state := emitBlock
    prefetchRequested := false.B
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
      prefetchRequested := false.B
    }.otherwise {
      blockOrdinal := nextBlock
      state := Mux(
        prefetchRequested || (prefetchReadRequest && coefficientStore.io.readRequest.fire),
        waitForBlock,
        requestBlock
      )
    }
  }

  when(io.input.fire && received >= maxBlocks.U) {
    overflow := true.B
  }
}
