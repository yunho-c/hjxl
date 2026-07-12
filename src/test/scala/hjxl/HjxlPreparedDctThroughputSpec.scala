// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class HjxlPreparedDctThroughputSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val blockDim = HjxlConstants.BlockDim
  private val config = HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 72)
  private val traceStageMask = (BigInt(1) << HjxlAbiGenerated.Trace.StageBits) - 1

  private def ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

  private def frameBlockCount(width: Int, height: Int): Int =
    ceilDiv(width, blockDim) * ceilDiv(height, blockDim)

  private case class Measurement(
      name: String,
      width: Int,
      height: Int,
      blocks: Int,
      tiles: Int,
      inputWords: Int,
      inputSpanCycles: Int,
      inputStallCycles: Int,
      firstOutputLatencyCycles: Int,
      outputWords: Int,
      dcWords: Int,
      strategyWords: Int,
      metadataWords: Int,
      acWords: Int,
      outputSpanCycles: Int,
      outputBubbleCycles: Int,
      totalCycles: Int
  ) {
    def csv: String =
      Seq(
        name,
        s"${width}x$height",
        blocks,
        tiles,
        inputWords,
        inputSpanCycles,
        inputStallCycles,
        firstOutputLatencyCycles,
        outputWords,
        dcWords,
        strategyWords,
        metadataWords,
        acWords,
        outputSpanCycles,
        outputBubbleCycles,
        totalCycles
      ).mkString(",")
  }

  private def pokeConfig(dut: HjxlPreparedDctAxiStreamCore, width: Int, height: Int): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
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

  private def blockWords(acCoefficientQ16: Int = 0, denseAc: Boolean = false): Seq[BigInt] = {
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
      } yield {
        val selected = if (denseAc) coefficient != 0 else coefficient == 1
        if (selected) BigInt(acCoefficientQ16) else BigInt(0)
      }
    scalars ++ coefficients
  }

  private def measureFrame(
      dut: HjxlPreparedDctAxiStreamCore,
      name: String,
      width: Int,
      height: Int,
      blocks: Seq[Seq[BigInt]]
  ): Measurement = {
    pokeConfig(dut, width, height)
    val words = blocks.flatten
    val expectedBlocks = frameBlockCount(width, height)
    blocks.length mustBe expectedBlocks
    words.length mustBe expectedBlocks * PreparedDctStreamLayout.WordsPerBlock
    val tiles =
      ceilDiv(width, HjxlConstants.TileDim) * ceilDiv(height, HjxlConstants.TileDim)
    val cycleLimit = words.length + blocks.length * 1024 + 1000

    var cycle = 0
    var sourceIndex = 0
    var firstInputCycle = -1
    var lastInputCycle = -1
    var inputStallCycles = 0
    var firstOutputCycle = -1
    var lastOutputCycle = -1
    var outputWords = 0
    var dcWords = 0
    var strategyWords = 0
    var metadataWords = 0
    var acWords = 0
    var sawLast = false

    dut.io.trace.ready.poke(true.B)
    while (!sawLast && cycle < cycleLimit) {
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
        val traceStage = (dut.io.trace.bits.data.peekValue().asBigInt & traceStageMask).toInt
        traceStage match {
          case TraceStage.DcTokens         => dcWords += 1
          case TraceStage.AcStrategy       => strategyWords += 1
          case TraceStage.AcMetadataTokens => metadataWords += 1
          case TraceStage.AcTokens         => acWords += 1
          case other                       => fail(s"$name emitted unexpected trace stage $other at cycle $cycle")
        }
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
      cycle must be < cycleLimit
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
      width = width,
      height = height,
      blocks = blocks.length,
      tiles = tiles,
      inputWords = words.length,
      inputSpanCycles = inputSpan,
      inputStallCycles = inputStallCycles,
      firstOutputLatencyCycles = firstOutputCycle - lastInputCycle,
      outputWords = outputWords,
      dcWords = dcWords,
      strategyWords = strategyWords,
      metadataWords = metadataWords,
      acWords = acWords,
      outputSpanCycles = outputSpan,
      outputBubbleCycles = outputSpan - outputWords,
      totalCycles = lastOutputCycle - firstInputCycle + 1
    )
  }

  "the direct prepared-DCT stream has measured cycle and token-expansion budgets" in {
    simulate(new HjxlPreparedDctAxiStreamCore(config)) { dut =>
      pokeConfig(dut, width = 8, height = 8)
      dut.io.input.valid.poke(false.B)
      dut.io.input.bits.data.poke(0.U)
      dut.io.input.bits.last.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      val zeroOne = measureFrame(dut, "zero-8x8", width = 8, height = 8, Seq(blockWords()))
      val impulseOne =
        measureFrame(
          dut,
          "three-ac-8x8",
          width = 8,
          height = 8,
          Seq(blockWords(acCoefficientQ16 = 1 << 20))
        )
      val zeroTwo =
        measureFrame(dut, "zero-16x8", width = 16, height = 8, Seq(blockWords(), blockWords()))
      val multiTileBlockCount = frameBlockCount(width = 72, height = 72)
      val multiTileBlocks = Seq.fill(multiTileBlockCount)(blockWords())
      val zeroMultiTile =
        measureFrame(dut, "zero-72x72", width = 72, height = 72, multiTileBlocks)
      val denseBlock = blockWords(acCoefficientQ16 = 1 << 20, denseAc = true)
      val denseMultiTile =
        measureFrame(
          dut,
          "dense-ac-72x72",
          width = 72,
          height = 72,
          Seq.fill(multiTileBlockCount)(denseBlock)
        )

      println(
        "HJXL_THROUGHPUT name,frame,blocks,tiles,input_words,input_span_cycles,input_stall_cycles," +
          "first_output_latency_cycles,output_words,dc_words,strategy_words,metadata_words,ac_words," +
          "output_span_cycles,output_bubble_cycles,total_cycles"
      )
      Seq(zeroOne, impulseOne, zeroTwo, zeroMultiTile, denseMultiTile)
        .foreach(result => println(s"HJXL_THROUGHPUT ${result.csv}"))

      zeroOne mustBe Measurement("zero-8x8", 8, 8, 1, 1, 201, 201, 0, 5, 12, 3, 1, 5, 3, 18, 6, 223)
      impulseOne mustBe
        Measurement("three-ac-8x8", 8, 8, 1, 1, 201, 201, 0, 5, 15, 3, 1, 5, 6, 23, 8, 228)
      zeroTwo mustBe
        Measurement("zero-16x8", 16, 8, 2, 1, 402, 403, 1, 8, 22, 6, 2, 8, 6, 36, 14, 446)
      zeroMultiTile mustBe
        Measurement(
          "zero-72x72",
          72,
          72,
          81,
          4,
          16281,
          16361,
          80,
          245,
          818,
          243,
          81,
          251,
          243,
          1464,
          646,
          18069
        )
      denseMultiTile mustBe
        Measurement(
          "dense-ac-72x72",
          72,
          72,
          81,
          4,
          16281,
          16361,
          80,
          245,
          16127,
          243,
          81,
          251,
          15552,
          17015,
          888,
          33620
        )
    }
  }
}
