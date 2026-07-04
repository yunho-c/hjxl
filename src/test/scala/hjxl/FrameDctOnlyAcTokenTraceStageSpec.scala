// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class FrameDctOnlyAcTokenTraceStageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 8)
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim
  private val recordsPerBlock = 3 * blockSize + 3 + 3

  private case class QuantizedBlock(quantized: Vector[Vector[Int]], numNonzeros: Vector[Int])

  private def pokeQuantConfig(dut: FrameDctOnlyQuantizeTraceStage, width: Int, height: Int): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(QuantizeDct8x8Block.DefaultScaleQ16.U)
    dut.io.config.fixedInvQacQ16.poke(QuantizeDct8x8Block.DefaultInvQacQ16.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.enableXyb.poke(true.B)
    dut.io.config.enableDct.poke(true.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(false.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.Dc.U)
  }

  private def pokeTokenConfig(dut: FrameDctOnlyAcTokenTraceStage, width: Int, height: Int): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(QuantizeDct8x8Block.DefaultScaleQ16.U)
    dut.io.config.fixedInvQacQ16.poke(QuantizeDct8x8Block.DefaultInvQacQ16.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.enableXyb.poke(true.B)
    dut.io.config.enableDct.poke(true.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(true.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.AcTokens.U)
  }

  private def pixelValue(x: Int, y: Int): Int =
    96 + x * 19 + y * 7

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

  private def expectedTokens(blocks: Seq[QuantizedBlock], xBlocks: Int): Seq[(Int, Int)] = {
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

  private def collectQuantizedBlocks(width: Int, height: Int): Seq[QuantizedBlock] = {
    val xBlocks = (width + 7) / 8
    val yBlocks = (height + 7) / 8
    val quantized = Array.fill(xBlocks * yBlocks, 3, blockSize)(0)
    val nonzeros = Array.fill(xBlocks * yBlocks, 3)(0)

    simulate(new FrameDctOnlyQuantizeTraceStage(config)) { dut =>
      pokeQuantConfig(dut, width, height)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for (y <- 0 until height; x <- 0 until width) {
        dut.io.input.valid.poke(true.B)
        dut.io.input.bits.x.poke(x.U)
        dut.io.input.bits.y.poke(y.U)
        val value = pixelValue(x, y)
        dut.io.input.bits.r.poke(value.S)
        dut.io.input.bits.g.poke((value + 23).S)
        dut.io.input.bits.b.poke((value + 47).S)
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.input.valid.poke(false.B)

      dut.io.trace.ready.poke(true.B)
      for (record <- 0 until xBlocks * yBlocks * recordsPerBlock) {
        withClue(s"quant record $record") {
          dut.io.trace.valid.expect(true.B)
          val block = dut.io.trace.bits.group.peekValue().asBigInt.toInt
          val stage = dut.io.trace.bits.stage.peekValue().asBigInt.toInt
          val index = dut.io.trace.bits.index.peekValue().asBigInt.toInt
          val value = dut.io.trace.bits.value.peekValue().asBigInt.toInt
          stage match {
            case TraceStage.QuantizedAc =>
              quantized(block)(index / blockSize)(index % blockSize) = value
            case TraceStage.NumNonzeros =>
              nonzeros(block)(index) = value
            case TraceStage.QuantDc =>
            case other => fail(s"unexpected quant trace stage $other")
          }
        }
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.overflow.expect(false.B)
    }

    quantized.indices.map { block =>
      QuantizedBlock(
        quantized(block).map(_.toVector).toVector,
        nonzeros(block).toVector
      )
    }
  }

  private def waitForTraceValid(dut: FrameDctOnlyAcTokenTraceStage): Unit = {
    var cycles = 0
    while (dut.io.trace.valid.peekValue().asBigInt == 0 && cycles < 16) {
      dut.clock.step()
      cycles += 1
    }
    cycles must be < 16
  }

  private def waitForInputReady(dut: FrameDctOnlyAcTokenTraceStage): Unit = {
    var cycles = 0
    while (dut.io.input.ready.peekValue().asBigInt == 0 && cycles < 16) {
      dut.clock.step()
      cycles += 1
    }
    cycles must be < 16
  }

  "FrameDctOnlyAcTokenTraceStage matches libjxl-tiny fixed AC-token oracle for a constant frame" in {
    simulate(new FrameDctOnlyAcTokenTraceStage(config)) { dut =>
      val width = 9
      val height = 1
      // Generated by:
      // python3 tools/hjxl_reference.py --width 9 --height 1 --pattern constant \
      //   --fixed-dct-only-ac-tokens-npy build-codex/oracle/constant-9x1-fixed-ac.npy
      val expected = Seq(
        (80, 0),
        (82, 0),
        (82, 0),
        (0, 0),
        (2, 0),
        (2, 0)
      )

      pokeTokenConfig(dut, width, height)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for (x <- 0 until width) {
        dut.io.input.valid.poke(true.B)
        dut.io.input.bits.x.poke(x.U)
        dut.io.input.bits.y.poke(0.U)
        dut.io.input.bits.r.poke(64.S)
        dut.io.input.bits.g.poke(128.S)
        dut.io.input.bits.b.poke(192.S)
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.input.valid.poke(false.B)

      dut.io.trace.ready.poke(true.B)
      for (((context, value), ordinal) <- expected.zipWithIndex) {
        waitForTraceValid(dut)
        withClue(s"oracle AC token $ordinal context=$context value=$value") {
          dut.io.trace.bits.stage.expect(TraceStage.AcTokens.U)
          dut.io.trace.bits.group.expect(ordinal.U)
          dut.io.trace.bits.index.expect(context.U)
          dut.io.trace.bits.value.expect(value.S)
        }
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      waitForInputReady(dut)
      dut.io.input.ready.expect(true.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "FrameDctOnlyAcTokenTraceStage emits full AC token streams for fixed DCT-only blocks" in {
    val width = 16
    val height = 8
    val blocks = collectQuantizedBlocks(width, height)
    blocks.map(_.numNonzeros.sum).sum must be > 0
    val expected = expectedTokens(blocks, xBlocks = 2)

    simulate(new FrameDctOnlyAcTokenTraceStage(config)) { dut =>
      pokeTokenConfig(dut, width, height)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for (y <- 0 until height; x <- 0 until width) {
        dut.io.input.valid.poke(true.B)
        dut.io.input.bits.x.poke(x.U)
        dut.io.input.bits.y.poke(y.U)
        val value = pixelValue(x, y)
        dut.io.input.bits.r.poke(value.S)
        dut.io.input.bits.g.poke((value + 23).S)
        dut.io.input.bits.b.poke((value + 47).S)
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
}
