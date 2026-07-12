// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class FramePreparedAcTokenTraceStageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 16)
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim

  private case class PreparedBlock(quantized: Vector[Vector[Int]], numNonzeros: Vector[Int])

  private def pokeConfig(dut: FramePreparedAcTokenTraceStage, width: Int, height: Int): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(QuantizeDct8x8Block.DefaultScaleQ16.U)
    dut.io.config.fixedInvQacQ16.poke(QuantizeDct8x8Block.DefaultInvQacQ16.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.fixedYtox.poke(0.S)
    dut.io.config.fixedYtob.poke(0.S)
    dut.io.config.enableXyb.poke(false.B)
    dut.io.config.enableDct.poke(false.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(true.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.AcTokens.U)
  }

  private def packSigned(value: Int): Int =
    if (value < 0) -2 * value - 1 else 2 * value

  private def nonzeroContext(nonzeros: Int, blockContext: Int): Int = {
    val bucket = if (nonzeros < 8) nonzeros else if (nonzeros >= 64) 36 else 4 + nonzeros / 2
    bucket * Tokenize.NumBlockContexts + blockContext
  }

  private def zeroDensityContext(nonzerosLeft: Int, scanIndex: Int, prev: Boolean, blockContext: Int): Int = {
    val offset = Tokenize.NumBlockContexts * Tokenize.NonzeroBuckets +
      Tokenize.ZeroDensityContextCount * blockContext
    offset +
      (Tokenize.CoeffNumNonzeroContext(nonzerosLeft) + Tokenize.CoeffFreqContext(scanIndex)) * 2 +
      (if (prev) 1 else 0)
  }

  private def expectedCoefficientTokens(
      quantized: Seq[Int],
      numNonzeros: Int,
      channel: Int
  ): Seq[(Int, Int)] = {
    var remaining = numNonzeros
    var prev = numNonzeros <= blockSize / 16
    val blockContext = Tokenize.DctBlockContextByChannel(channel)
    val tokens = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
    var scanIndex = 1
    while (remaining != 0 && scanIndex < blockSize) {
      val coeff = quantized(Tokenize.DctCoeffOrder(scanIndex))
      tokens += zeroDensityContext(remaining, scanIndex, prev, blockContext) -> packSigned(coeff)
      prev = coeff != 0
      if (prev) {
        remaining -= 1
      }
      scanIndex += 1
    }
    tokens.toSeq
  }

  private def expectedTokens(blocks: Seq[PreparedBlock], xBlocks: Int): Seq[(Int, Int)] = {
    val channelOrder = Seq(1, 0, 2)
    blocks.zipWithIndex.flatMap { case (block, blockOrdinal) =>
      val blockX = blockOrdinal % xBlocks
      val blockY = blockOrdinal / xBlocks
      channelOrder.flatMap { channel =>
        val west = if (blockX == 0) block.numNonzeros(channel) else blocks(blockOrdinal - 1).numNonzeros(channel)
        val north = if (blockY == 0) block.numNonzeros(channel) else blocks(blockOrdinal - xBlocks).numNonzeros(channel)
        val predicted =
          if (blockX == 0) {
            if (blockY == 0) 32 else north
          } else if (blockY == 0) {
            west
          } else {
            (north + west + 1) >> 1
          }
        val prefix =
          nonzeroContext(predicted, Tokenize.DctBlockContextByChannel(channel)) ->
            block.numNonzeros(channel)
        prefix +: expectedCoefficientTokens(block.quantized(channel), block.numNonzeros(channel), channel)
      }
    }
  }

  private def quantized(values: (Int, Int)*): Vector[Int] =
    values.foldLeft(Vector.fill(blockSize)(0)) { case (coefficients, (index, value)) =>
      coefficients.updated(index, value)
    }

  private def zeroBlock: PreparedBlock =
    PreparedBlock(
      quantized = Vector(quantized(), quantized(), quantized()),
      numNonzeros = Vector(0, 0, 0)
    )

  private def waitForTraceValid(dut: FramePreparedAcTokenTraceStage): Unit = {
    var cycles = 0
    while (dut.io.trace.valid.peekValue().asBigInt == 0 && cycles < 16) {
      dut.clock.step()
      cycles += 1
    }
    cycles must be < 16
  }

  private def waitForInputReady(dut: FramePreparedAcTokenTraceStage): Unit = {
    var cycles = 0
    while (dut.io.input.ready.peekValue().asBigInt == 0 && cycles < 16) {
      dut.clock.step()
      cycles += 1
    }
    cycles must be < 16
  }

  "FramePreparedAcTokenTraceStage tokenizes prepared AC blocks with frame nonzero prediction" in {
    simulate(new FramePreparedAcTokenTraceStage(config)) { dut =>
      val width = 9
      val height = 9
      val xBlocks = 2
      val blocks = Seq(
        PreparedBlock(
          quantized = Vector(
            quantized(1 -> 2),
            quantized(1 -> -3, 16 -> 1),
            quantized()
          ),
          numNonzeros = Vector(1, 2, 0)
        ),
        PreparedBlock(
          quantized = Vector(
            quantized(),
            quantized(8 -> 4),
            quantized(1 -> -1)
          ),
          numNonzeros = Vector(0, 1, 1)
        ),
        PreparedBlock(
          quantized = Vector(
            quantized(2 -> 5),
            quantized(),
            quantized(1 -> 1, 8 -> -2)
          ),
          numNonzeros = Vector(1, 0, 2)
        ),
        PreparedBlock(
          quantized = Vector(
            quantized(1 -> -4, 9 -> 1),
            quantized(16 -> 2),
            quantized()
          ),
          numNonzeros = Vector(2, 1, 0)
        )
      )
      val expected = expectedTokens(blocks, xBlocks)

      pokeConfig(dut, width, height)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for (block <- blocks) {
        dut.io.input.valid.poke(true.B)
        for (channel <- 0 until 3) {
          dut.io.input.bits.numNonzeros(channel).poke(block.numNonzeros(channel).U)
          for (i <- 0 until blockSize) {
            dut.io.input.bits.quantized(channel)(i).poke(block.quantized(channel)(i).S)
          }
        }
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)

      for (((context, value), ordinal) <- expected.zipWithIndex) {
        waitForTraceValid(dut)
        withClue(s"AC token $ordinal context=$context value=$value") {
          dut.io.trace.bits.stage.expect(TraceStage.AcTokens.U)
          dut.io.trace.bits.group.expect(ordinal.U)
          dut.io.trace.bits.index.expect(context.U)
          dut.io.trace.bits.value.expect(value.S)
          dut.io.traceLast.expect((ordinal == expected.length - 1).B)
        }
        dut.clock.step()
      }

      dut.io.trace.valid.expect(false.B)
      dut.io.input.ready.expect(false.B)
      waitForInputReady(dut)
      dut.io.input.ready.expect(true.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "FramePreparedAcTokenTraceStage preserves block counts for exact 72px capacity" in {
    val exactCapacity = HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 8)
    simulate(new FramePreparedAcTokenTraceStage(exactCapacity)) { dut =>
      val width = 72
      val height = 8
      val xBlocks = 9
      val blocks = Seq.fill(xBlocks)(zeroBlock)
      val expected = expectedTokens(blocks, xBlocks)

      pokeConfig(dut, width, height)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for (block <- blocks) {
        dut.io.input.valid.poke(true.B)
        for (channel <- 0 until 3) {
          dut.io.input.bits.numNonzeros(channel).poke(block.numNonzeros(channel).U)
          for (i <- 0 until blockSize) {
            dut.io.input.bits.quantized(channel)(i).poke(block.quantized(channel)(i).S)
          }
        }
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)

      for (((context, value), ordinal) <- expected.zipWithIndex) {
        waitForTraceValid(dut)
        withClue(s"exact-capacity AC token $ordinal context=$context value=$value") {
          dut.io.trace.bits.stage.expect(TraceStage.AcTokens.U)
          dut.io.trace.bits.group.expect(ordinal.U)
          dut.io.trace.bits.index.expect(context.U)
          dut.io.trace.bits.value.expect(value.S)
          dut.io.traceLast.expect((ordinal == expected.length - 1).B)
        }
        dut.clock.step()
      }

      dut.io.trace.valid.expect(false.B)
      waitForInputReady(dut)
      dut.io.input.ready.expect(true.B)
      dut.io.overflow.expect(false.B)
    }
  }
}
