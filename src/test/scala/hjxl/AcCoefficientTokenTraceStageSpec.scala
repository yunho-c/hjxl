// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class AcCoefficientTokenTraceStageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim

  private def packSigned(value: Int): Int =
    if (value < 0) -2 * value - 1 else 2 * value

  private def zeroDensityContext(nonzerosLeft: Int, scanIndex: Int, prev: Boolean, blockContext: Int): Int = {
    val offset = Tokenize.NumBlockContexts * Tokenize.NonzeroBuckets +
      Tokenize.ZeroDensityContextCount * blockContext
    offset +
      (Tokenize.CoeffNumNonzeroContext(nonzerosLeft) + Tokenize.CoeffFreqContext(scanIndex)) * 2 +
      (if (prev) 1 else 0)
  }

  private def nonzeroContext(nonzeros: Int, blockContext: Int): Int = {
    val bucket = if (nonzeros < 8) nonzeros else if (nonzeros >= 64) 36 else 4 + nonzeros / 2
    bucket * Tokenize.NumBlockContexts + blockContext
  }

  private def expectedTokens(
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

  private def expectedBlockTokens(
      quantized: Seq[Int],
      predictedNonzeros: Int,
      numNonzeros: Int,
      channel: Int
  ): Seq[(Int, Int)] = {
    val prefix = nonzeroContext(predictedNonzeros, Tokenize.DctBlockContextByChannel(channel)) -> numNonzeros
    prefix +: expectedTokens(quantized, numNonzeros, channel)
  }

  private def waitForTraceValid(dut: DctOnlyAcBlockTokenTraceStage): Unit = {
    var cycles = 0
    while (dut.io.trace.valid.peekValue().asBigInt == 0 && cycles < 8) {
      dut.clock.step()
      cycles += 1
    }
    cycles must be < 8
  }

  "AcCoefficientTokenTraceStage emits DCT scan-order coefficient tokens until nonzeros are consumed" in {
    val quantized = Seq.fill(blockSize)(0).updated(1, 2).updated(16, -3).updated(5, 1)
    val channel = 1
    val numNonzeros = 3
    val group = 40
    val expected = expectedTokens(quantized, numNonzeros, channel)
    expected.length must be(15)

    simulate(new AcCoefficientTokenTraceStage()) { dut =>
      dut.io.trace.ready.poke(false.B)
      dut.io.input.valid.poke(true.B)
      dut.io.input.bits.group.poke(group.U)
      dut.io.input.bits.channel.poke(channel.U)
      dut.io.input.bits.numNonzeros.poke(numNonzeros.U)
      for (i <- 0 until blockSize) {
        dut.io.input.bits.quantized(i).poke(quantized(i).S)
      }
      dut.io.input.ready.expect(true.B)
      dut.clock.step()
      dut.io.input.valid.poke(false.B)
      dut.io.input.ready.expect(false.B)

      dut.io.trace.ready.poke(true.B)
      for (((context, value), ordinal) <- expected.zipWithIndex) {
        withClue(s"token $ordinal context=$context value=$value") {
          dut.io.trace.valid.expect(true.B)
          dut.io.trace.bits.stage.expect(TraceStage.AcTokens.U)
          dut.io.trace.bits.group.expect((group + ordinal).U)
          dut.io.trace.bits.index.expect(context.U)
          dut.io.trace.bits.value.expect(value.S)
        }
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.input.ready.expect(true.B)
    }
  }

  "AcCoefficientTokenTraceStage accepts all-zero blocks without emitting coefficient tokens" in {
    simulate(new AcCoefficientTokenTraceStage()) { dut =>
      dut.io.trace.ready.poke(true.B)
      dut.io.input.valid.poke(true.B)
      dut.io.input.bits.group.poke(7.U)
      dut.io.input.bits.channel.poke(2.U)
      dut.io.input.bits.numNonzeros.poke(0.U)
      for (i <- 0 until blockSize) {
        dut.io.input.bits.quantized(i).poke(0.S)
      }
      dut.io.input.ready.expect(true.B)
      dut.clock.step()
      dut.io.input.valid.poke(false.B)
      dut.io.trace.valid.expect(false.B)
      dut.io.input.ready.expect(true.B)
    }
  }

  "AcBlockTokenTraceStage emits nonzero prefix followed by coefficient scan tokens" in {
    val quantized = Seq.fill(blockSize)(0).updated(1, 2).updated(16, -3).updated(5, 1)
    val channel = 1
    val predictedNonzeros = 32
    val numNonzeros = 3
    val group = 12
    val prefix = nonzeroContext(predictedNonzeros, Tokenize.DctBlockContextByChannel(channel)) -> numNonzeros
    val expected = prefix +: expectedTokens(quantized, numNonzeros, channel)

    simulate(new AcBlockTokenTraceStage()) { dut =>
      dut.io.trace.ready.poke(false.B)
      dut.io.input.valid.poke(true.B)
      dut.io.input.bits.group.poke(group.U)
      dut.io.input.bits.channel.poke(channel.U)
      dut.io.input.bits.predictedNonzeros.poke(predictedNonzeros.U)
      dut.io.input.bits.numNonzeros.poke(numNonzeros.U)
      for (i <- 0 until blockSize) {
        dut.io.input.bits.quantized(i).poke(quantized(i).S)
      }
      dut.io.input.ready.expect(true.B)
      dut.clock.step()
      dut.io.input.valid.poke(false.B)

      dut.io.trace.ready.poke(true.B)
      for (((context, value), ordinal) <- expected.zipWithIndex) {
        withClue(s"token $ordinal context=$context value=$value") {
          dut.io.trace.valid.expect(true.B)
          dut.io.trace.bits.stage.expect(TraceStage.AcTokens.U)
          dut.io.trace.bits.group.expect((group + ordinal).U)
          dut.io.trace.bits.index.expect(context.U)
          dut.io.trace.bits.value.expect(value.S)
        }
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.input.ready.expect(false.B)
      dut.clock.step()
      dut.io.input.ready.expect(true.B)
    }
  }

  "AcBlockTokenTraceStage emits only the nonzero prefix for all-zero blocks" in {
    val channel = 2
    val predictedNonzeros = 0
    val group = 5
    val expectedContext = nonzeroContext(predictedNonzeros, Tokenize.DctBlockContextByChannel(channel))

    simulate(new AcBlockTokenTraceStage()) { dut =>
      dut.io.trace.ready.poke(true.B)
      dut.io.input.valid.poke(true.B)
      dut.io.input.bits.group.poke(group.U)
      dut.io.input.bits.channel.poke(channel.U)
      dut.io.input.bits.predictedNonzeros.poke(predictedNonzeros.U)
      dut.io.input.bits.numNonzeros.poke(0.U)
      for (i <- 0 until blockSize) {
        dut.io.input.bits.quantized(i).poke(0.S)
      }
      dut.io.input.ready.expect(true.B)
      dut.clock.step()
      dut.io.input.valid.poke(false.B)

      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.stage.expect(TraceStage.AcTokens.U)
      dut.io.trace.bits.group.expect(group.U)
      dut.io.trace.bits.index.expect(expectedContext.U)
      dut.io.trace.bits.value.expect(0.S)
      dut.clock.step()
      dut.io.trace.valid.expect(false.B)
      dut.io.input.ready.expect(true.B)
    }
  }

  "DctOnlyAcBlockTokenTraceStage emits complete Y/X/B block token streams" in {
    val yQuantized = Seq.fill(blockSize)(0).updated(1, 2).updated(16, -3).updated(5, 1)
    val xQuantized = Seq.fill(blockSize)(0).updated(1, -1)
    val bQuantized = Seq.fill(blockSize)(0)
    val quantized = Seq(xQuantized, yQuantized, bQuantized)
    val predicted = Seq(0, 32, 5)
    val nonzeros = Seq(1, 3, 0)
    val group = 80
    val expected =
      expectedBlockTokens(yQuantized, predicted(1), nonzeros(1), channel = 1) ++
        expectedBlockTokens(xQuantized, predicted(0), nonzeros(0), channel = 0) ++
        expectedBlockTokens(bQuantized, predicted(2), nonzeros(2), channel = 2)

    simulate(new DctOnlyAcBlockTokenTraceStage()) { dut =>
      dut.io.trace.ready.poke(false.B)
      dut.io.input.valid.poke(true.B)
      dut.io.input.bits.group.poke(group.U)
      for (channel <- 0 until 3) {
        dut.io.input.bits.predictedNonzeros(channel).poke(predicted(channel).U)
        dut.io.input.bits.numNonzeros(channel).poke(nonzeros(channel).U)
        for (i <- 0 until blockSize) {
          dut.io.input.bits.quantized(channel)(i).poke(quantized(channel)(i).S)
        }
      }
      dut.io.input.ready.expect(true.B)
      dut.clock.step()
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)

      for (((context, value), ordinal) <- expected.zipWithIndex) {
        waitForTraceValid(dut)
        withClue(s"token $ordinal context=$context value=$value") {
          dut.io.trace.bits.stage.expect(TraceStage.AcTokens.U)
          dut.io.trace.bits.group.expect((group + ordinal).U)
          dut.io.trace.bits.index.expect(context.U)
          dut.io.trace.bits.value.expect(value.S)
        }
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.input.ready.expect(false.B)
      dut.clock.step()
      dut.io.input.ready.expect(true.B)
    }
  }
}
