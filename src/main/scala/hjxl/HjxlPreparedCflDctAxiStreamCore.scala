// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** AXI4-Stream-shaped prepared-DCT token shell with internal CFL estimation.
  *
  * This shell uses the same packed input/output contract as
  * `HjxlPreparedDctAxiStreamCore`, but routes prepared raster blocks through
  * `FramePreparedCflDctOnlyQuantizeTokenTraceStage`. Input `ytox`/`ytob` words
  * are accepted to preserve the stream layout, but the wrapped stage estimates
  * tile CFL maps from the prepared coefficients and uses those maps for
  * quantization and AC metadata tokenization.
  */
class HjxlPreparedCflDctAxiStreamCore(c: HjxlConfig = HjxlConfig()) extends Module {
  require(c.traceValueBits <= 32, "prepared-DCT stream coefficients are 32-bit words")

  private val blockDim = HjxlConstants.BlockDim
  private val blockSize = blockDim * blockDim
  private val maxXBlocks = c.maxFrameWidth / blockDim
  private val maxYBlocks = c.maxFrameHeight / blockDim
  private val maxBlocks = maxXBlocks * maxYBlocks
  private val blockCountBits = log2Ceil(maxBlocks + 1)
  private val wordIndexBits = log2Ceil(PreparedDctStreamLayout.WordsPerBlock)
  private val widthBits = log2Ceil(c.maxFrameWidth + 1)
  private val heightBits = log2Ceil(c.maxFrameHeight + 1)

  val inputDataBits = 32
  val traceDataBits =
    HjxlAbiGenerated.Trace.StageBits + c.groupBits + HjxlAbiGenerated.Trace.IndexBits + c.traceValueBits

  val io = IO(new HjxlPreparedDctAxiStreamCoreIO(c, inputDataBits, traceDataBits))

  private def ceilDivBlock(value: UInt): UInt =
    (value +& (blockDim - 1).U) >> log2Ceil(blockDim)

  private def signWord(value: UInt): SInt =
    value(c.traceValueBits - 1, 0).asSInt

  val stage = Module(new FramePreparedCflDctOnlyQuantizeTokenTraceStage(c))

  val latchedConfig = Reg(new FrameConfig(c))
  val frameActive = RegInit(false.B)
  val blockInput = Reg(new DctOnlyQuantizeBlockInput(c))
  val blockValid = RegInit(false.B)
  val wordIndex = RegInit(0.U(wordIndexBits.W))
  val receivedBlocks = RegInit(0.U(blockCountBits.W))
  val submittedBlocks = RegInit(0.U(blockCountBits.W))
  val frameInputsComplete = RegInit(false.B)
  val protocolError = RegInit(false.B)

  val activeConfig = Wire(new FrameConfig(c))
  activeConfig := Mux(frameActive, latchedConfig, io.config)
  stage.io.config := activeConfig

  val configWidth = activeConfig.xsize(widthBits - 1, 0)
  val configHeight = activeConfig.ysize(heightBits - 1, 0)
  val xBlocks = ceilDivBlock(configWidth)
  val yBlocks = ceilDivBlock(configHeight)
  val totalBlocks = xBlocks * yBlocks
  val configOutOfRange =
    activeConfig.xsize === 0.U || activeConfig.ysize === 0.U ||
      activeConfig.xsize > c.maxFrameWidth.U || activeConfig.ysize > c.maxFrameHeight.U ||
      xBlocks > maxXBlocks.U || yBlocks > maxYBlocks.U

  stage.io.input.valid := blockValid
  stage.io.input.bits := blockInput

  when(stage.io.input.fire) {
    blockValid := false.B
    submittedBlocks := submittedBlocks + 1.U
  }

  val finalWordInBlock = wordIndex === (PreparedDctStreamLayout.WordsPerBlock - 1).U
  val finalBlock = receivedBlocks === (totalBlocks - 1.U)
  val expectedLast = finalWordInBlock && finalBlock
  val canAcceptInput = !blockValid && !frameInputsComplete && !configOutOfRange

  io.input.ready := canAcceptInput

  when(io.clearProtocolError) {
    protocolError := false.B
  }

  when(io.input.fire) {
    when(!frameActive) {
      frameActive := true.B
      latchedConfig := io.config
    }

    when(io.input.bits.last =/= expectedLast) {
      protocolError := true.B
    }

    switch(wordIndex) {
      is(PreparedDctStreamLayout.Quant.U) {
        blockInput.quant := io.input.bits.data(7, 0)
      }
      is(PreparedDctStreamLayout.ScaleQ16.U) {
        blockInput.scaleQ16 := io.input.bits.data(15, 0)
      }
      is(PreparedDctStreamLayout.InvQacQ16.U) {
        blockInput.invQacQ16 := io.input.bits.data
      }
      is(PreparedDctStreamLayout.InvDcFactorQ16Base.U) {
        blockInput.invDcFactorQ16(0) := io.input.bits.data
      }
      is((PreparedDctStreamLayout.InvDcFactorQ16Base + 1).U) {
        blockInput.invDcFactorQ16(1) := io.input.bits.data
      }
      is((PreparedDctStreamLayout.InvDcFactorQ16Base + 2).U) {
        blockInput.invDcFactorQ16(2) := io.input.bits.data
      }
      is(PreparedDctStreamLayout.XQmMultiplierQ16.U) {
        blockInput.xQmMultiplierQ16 := io.input.bits.data
      }
      is(PreparedDctStreamLayout.Ytox.U) {
        blockInput.ytox := io.input.bits.data(7, 0).asSInt
      }
      is(PreparedDctStreamLayout.Ytob.U) {
        blockInput.ytob := io.input.bits.data(7, 0).asSInt
      }
    }

    when(wordIndex >= PreparedDctStreamLayout.CoefficientBase.U) {
      val coefficientWord = wordIndex - PreparedDctStreamLayout.CoefficientBase.U
      val channel = coefficientWord >> log2Ceil(blockSize)
      val coefficient = coefficientWord(log2Ceil(blockSize) - 1, 0)
      blockInput.coefficients(channel)(coefficient) := signWord(io.input.bits.data)
    }

    when(finalWordInBlock) {
      blockValid := true.B
      wordIndex := 0.U
      receivedBlocks := receivedBlocks + 1.U
      when(finalBlock) {
        frameInputsComplete := true.B
      }
    }.otherwise {
      wordIndex := wordIndex + 1.U
    }
  }

  when(frameInputsComplete && !blockValid && !stage.io.busy && !stage.io.trace.valid) {
    frameActive := false.B
    frameInputsComplete := false.B
    receivedBlocks := 0.U
    submittedBlocks := 0.U
    wordIndex := 0.U
  }

  io.trace.valid := stage.io.trace.valid
  stage.io.trace.ready := io.trace.ready
  io.trace.bits.data := Cat(
    stage.io.trace.bits.value.asUInt,
    stage.io.trace.bits.index,
    stage.io.trace.bits.group,
    stage.io.trace.bits.stage
  )
  io.trace.bits.last := stage.io.trace.valid && stage.io.traceLast

  io.busy := frameActive || blockValid || stage.io.busy || stage.io.trace.valid
  io.overflow := configOutOfRange || stage.io.overflow || submittedBlocks > totalBlocks
  io.protocolError := protocolError
}
