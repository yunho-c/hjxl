// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class HjxlPreparedDctThroughputSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 8)

  private case class Measurement(
      name: String,
      blocks: Int,
      inputWords: Int,
      inputSpanCycles: Int,
      inputStallCycles: Int,
      firstOutputLatencyCycles: Int,
      outputWords: Int,
      outputSpanCycles: Int,
      outputBubbleCycles: Int,
      totalCycles: Int
  ) {
    def csv: String =
      Seq(
        name,
        blocks,
        inputWords,
        inputSpanCycles,
        inputStallCycles,
        firstOutputLatencyCycles,
        outputWords,
        outputSpanCycles,
        outputBubbleCycles,
        totalCycles
      ).mkString(",")
  }

  private def pokeConfig(dut: HjxlPreparedDctAxiStreamCore, width: Int): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(8.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(0.U)
    dut.io.config.fixedInvQacQ16.poke(0.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.fixedYtox.poke(0.S)
    dut.io.config.fixedYtob.poke(0.S)
    dut.io.config.enableXyb.poke(false.B)
    dut.io.config.enableDct.poke(true.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(true.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.AcTokens.U)
    dut.io.clearProtocolError.poke(false.B)
  }

  private def blockWords(acCoefficientQ16: Int = 0): Seq[BigInt] = {
    val scalars = Seq(
      BigInt(1),
      BigInt(QuantizeDct8x8Block.DefaultScaleQ16),
      BigInt(QuantizeDct8x8Block.DefaultInvQacQ16),
      BigInt(QuantizeDct8x8Block.DefaultInvDcFactorQ16(0)),
      BigInt(QuantizeDct8x8Block.DefaultInvDcFactorQ16(1)),
      BigInt(QuantizeDct8x8Block.DefaultInvDcFactorQ16(2)),
      BigInt(QuantizeDct8x8Block.DefaultQmMultiplierQ16),
      BigInt(0),
      BigInt(0)
    )
    val coefficients =
      for {
        _ <- 0 until 3
        coefficient <- 0 until 64
      } yield if (coefficient == 1) BigInt(acCoefficientQ16) else BigInt(0)
    scalars ++ coefficients
  }

  private def measureFrame(
      dut: HjxlPreparedDctAxiStreamCore,
      name: String,
      width: Int,
      blocks: Seq[Seq[BigInt]]
  ): Measurement = {
    pokeConfig(dut, width)
    val words = blocks.flatten
    words.length mustBe blocks.length * PreparedDctStreamLayout.WordsPerBlock

    var cycle = 0
    var sourceIndex = 0
    var firstInputCycle = -1
    var lastInputCycle = -1
    var inputStallCycles = 0
    var firstOutputCycle = -1
    var lastOutputCycle = -1
    var outputWords = 0
    var sawLast = false

    dut.io.trace.ready.poke(true.B)
    while (!sawLast && cycle < 20000) {
      val presentingInput = sourceIndex < words.length
      dut.io.input.valid.poke(presentingInput.B)
      dut.io.input.bits.data.poke((if (presentingInput) words(sourceIndex) else BigInt(0)).U)
      dut.io.input.bits.last.poke((presentingInput && sourceIndex == words.length - 1).B)

      val inputReady = dut.io.input.ready.peekValue().asBigInt != 0
      if (presentingInput && inputReady) {
        if (firstInputCycle < 0) firstInputCycle = cycle
        lastInputCycle = cycle
        sourceIndex += 1
      } else if (presentingInput) {
        inputStallCycles += 1
      }

      if (dut.io.trace.valid.peekValue().asBigInt != 0) {
        if (firstOutputCycle < 0) firstOutputCycle = cycle
        lastOutputCycle = cycle
        outputWords += 1
        sawLast = dut.io.trace.bits.last.peekValue().asBigInt != 0
      }

      dut.clock.step()
      cycle += 1
    }

    withClue(s"$name did not accept every input word") {
      sourceIndex mustBe words.length
    }
    withClue(s"$name did not emit final TLAST") {
      sawLast mustBe true
      cycle must be < 20000
    }
    dut.io.input.valid.poke(false.B)
    dut.io.protocolError.expect(false.B)
    dut.io.overflow.expect(false.B)

    var retirementCycles = 0
    while (dut.io.busy.peekValue().asBigInt != 0 && retirementCycles < 16) {
      dut.clock.step()
      retirementCycles += 1
    }
    withClue(s"$name did not retire after final TLAST") {
      retirementCycles must be < 16
    }

    val inputSpan = lastInputCycle - firstInputCycle + 1
    val outputSpan = lastOutputCycle - firstOutputCycle + 1
    Measurement(
      name = name,
      blocks = blocks.length,
      inputWords = words.length,
      inputSpanCycles = inputSpan,
      inputStallCycles = inputStallCycles,
      firstOutputLatencyCycles = firstOutputCycle - lastInputCycle,
      outputWords = outputWords,
      outputSpanCycles = outputSpan,
      outputBubbleCycles = outputSpan - outputWords,
      totalCycles = lastOutputCycle - firstInputCycle + 1
    )
  }

  "the direct prepared-DCT stream has measured cycle and token-expansion budgets" in {
    simulate(new HjxlPreparedDctAxiStreamCore(config)) { dut =>
      pokeConfig(dut, width = 8)
      dut.io.input.valid.poke(false.B)
      dut.io.input.bits.data.poke(0.U)
      dut.io.input.bits.last.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      val zeroOne = measureFrame(dut, "zero-8x8", width = 8, Seq(blockWords()))
      val impulseOne =
        measureFrame(dut, "three-ac-8x8", width = 8, Seq(blockWords(acCoefficientQ16 = 1 << 20)))
      val zeroTwo = measureFrame(dut, "zero-16x8", width = 16, Seq(blockWords(), blockWords()))

      println(
        "HJXL_THROUGHPUT name,blocks,input_words,input_span_cycles,input_stall_cycles," +
          "first_output_latency_cycles,output_words,output_span_cycles,output_bubble_cycles,total_cycles"
      )
      Seq(zeroOne, impulseOne, zeroTwo).foreach(result => println(s"HJXL_THROUGHPUT ${result.csv}"))

      zeroOne mustBe Measurement("zero-8x8", 1, 201, 201, 0, 7, 12, 18, 6, 225)
      impulseOne mustBe Measurement("three-ac-8x8", 1, 201, 201, 0, 7, 15, 23, 8, 230)
      zeroTwo mustBe Measurement("zero-16x8", 2, 402, 403, 1, 12, 22, 36, 14, 450)
    }
  }
}
