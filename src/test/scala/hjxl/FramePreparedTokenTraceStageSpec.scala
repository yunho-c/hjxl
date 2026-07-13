// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class FramePreparedTokenTraceStageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 16)
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim

  private case class PreparedBlock(quantized: Vector[Vector[Int]], numNonzeros: Vector[Int])
  private case class ExpectedTrace(stage: Int, group: Int, index: Int, value: Int)

  private def pokeConfig(
      dut: FramePreparedTokenTraceStage,
      width: Int,
      height: Int,
      fixedYtox: Int = 0,
      fixedYtob: Int = 0
  ): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(QuantizeDct8x8Block.DefaultScaleQ16.U)
    dut.io.config.fixedInvQacQ16.poke(QuantizeDct8x8Block.DefaultInvQacQ16.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.fixedYtox.poke(fixedYtox.S)
    dut.io.config.fixedYtob.poke(fixedYtob.S)
    dut.io.config.enableXyb.poke(false.B)
    dut.io.config.enableDct.poke(false.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(true.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.AcTokens.U)
  }

  private def waitForTraceValid(dut: FramePreparedTokenTraceStage): Unit = {
    var cycles = 0
    while (dut.io.trace.valid.peekValue().asBigInt == 0 && cycles < blockSize * 3) {
      dut.clock.step()
      cycles += 1
    }
    cycles must be < blockSize * 3
  }

  private def waitForAcInputReady(dut: FramePreparedTokenTraceStage): Unit = {
    var cycles = 0
    while (dut.io.acInput.ready.peekValue().asBigInt == 0 && cycles < blockSize + 2) {
      dut.clock.step()
      cycles += 1
    }
    cycles must be < blockSize + 2
  }

  private def feedConstantPreparedInputs(dut: FramePreparedTokenTraceStage, xBlocks: Int, yBlocks: Int): Unit = {
    val dcSamples = Seq(352, 352, -23, -23, 19, 19)
    for (sample <- dcSamples) {
      dut.io.dcInput.valid.poke(true.B)
      dut.io.dcInput.bits.poke(sample.S)
      dut.io.dcInput.ready.expect(true.B)
      dut.clock.step()
    }
    dut.io.dcInput.valid.poke(false.B)

    for (_ <- 0 until xBlocks * yBlocks) {
      dut.io.acInput.valid.poke(true.B)
      for (channel <- 0 until 3) {
        dut.io.acInput.bits.numNonzeros(channel).poke(0.U)
        for (i <- 0 until blockSize) {
          dut.io.acInput.bits.quantized(channel)(i).poke(0.S)
        }
      }
      waitForAcInputReady(dut)
      dut.io.acInput.ready.expect(true.B)
      dut.clock.step()
    }
    dut.io.acInput.valid.poke(false.B)
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

  private def acCoefficientTokenCount(quantized: Seq[Int], numNonzeros: Int): Int = {
    var remaining = numNonzeros
    var scanIndex = 1
    var count = 0
    while (remaining != 0 && scanIndex < blockSize) {
      if (quantized(Tokenize.DctCoeffOrder(scanIndex)) != 0) {
        remaining -= 1
      }
      count += 1
      scanIndex += 1
    }
    count
  }

  private def acTokenCount(blocks: Seq[PreparedBlock]): Int = {
    val channelOrder = Seq(1, 0, 2)
    blocks.map { block =>
      channelOrder.map { channel =>
        1 + acCoefficientTokenCount(block.quantized(channel), block.numNonzeros(channel))
      }.sum
    }.sum
  }

  "FramePreparedTokenTraceStage emits fixed-token trace streams from prepared DC and AC inputs" in {
    simulate(new FramePreparedTokenTraceStage(config)) { dut =>
      val width = 9
      val height = 1
      val xBlocks = 2
      val yBlocks = 1
      val dcSamples = Seq(352, 352, -23, -23, 19, 19)
      val expected =
        Seq(
          ExpectedTrace(TraceStage.DcTokens, 0, 26, 704),
          ExpectedTrace(TraceStage.DcTokens, 1, 13, 0),
          ExpectedTrace(TraceStage.DcTokens, 2, 26, 45),
          ExpectedTrace(TraceStage.DcTokens, 3, 33, 0),
          ExpectedTrace(TraceStage.DcTokens, 4, 26, 38),
          ExpectedTrace(TraceStage.DcTokens, 5, 21, 0),
          ExpectedTrace(TraceStage.AcStrategy, 0, 0, 1),
          ExpectedTrace(TraceStage.AcStrategy, 0, 1, 1),
          ExpectedTrace(TraceStage.AcMetadataTokens, 0, 2, 13),
          ExpectedTrace(TraceStage.AcMetadataTokens, 1, 1, 22),
          ExpectedTrace(TraceStage.AcMetadataTokens, 2, 10, 0),
          ExpectedTrace(TraceStage.AcMetadataTokens, 3, 10, 0),
          ExpectedTrace(TraceStage.AcMetadataTokens, 4, 6, 8),
          ExpectedTrace(TraceStage.AcMetadataTokens, 5, 5, 0),
          ExpectedTrace(TraceStage.AcMetadataTokens, 6, 0, 8),
          ExpectedTrace(TraceStage.AcMetadataTokens, 7, 0, 8),
          ExpectedTrace(TraceStage.AcTokens, 0, 80, 0),
          ExpectedTrace(TraceStage.AcTokens, 1, 82, 0),
          ExpectedTrace(TraceStage.AcTokens, 2, 82, 0),
          ExpectedTrace(TraceStage.AcTokens, 3, 0, 0),
          ExpectedTrace(TraceStage.AcTokens, 4, 2, 0),
          ExpectedTrace(TraceStage.AcTokens, 5, 2, 0)
        )

      pokeConfig(dut, width, height, fixedYtox = -7, fixedYtob = 11)
      dut.io.dcInput.valid.poke(false.B)
      dut.io.acInput.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      feedConstantPreparedInputs(dut, xBlocks, yBlocks)
      dut.io.trace.ready.poke(true.B)

      for ((trace, ordinal) <- expected.zipWithIndex) {
        waitForTraceValid(dut)
        withClue(s"prepared token trace $ordinal") {
          dut.io.trace.bits.stage.expect(trace.stage.U)
          dut.io.trace.bits.group.expect(trace.group.U)
          dut.io.trace.bits.index.expect(trace.index.U)
          dut.io.trace.bits.value.expect(trace.value.S)
          dut.io.traceLast.expect((ordinal == expected.length - 1).B)
        }
        dut.clock.step()
      }

      dut.io.trace.valid.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "FramePreparedTokenTraceStage holds trace output stable under backpressure" in {
    simulate(new FramePreparedTokenTraceStage(config)) { dut =>
      val width = 9
      val height = 1
      val xBlocks = 2
      val yBlocks = 1

      pokeConfig(dut, width, height)
      dut.io.dcInput.valid.poke(false.B)
      dut.io.acInput.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      feedConstantPreparedInputs(dut, xBlocks, yBlocks)

      waitForTraceValid(dut)
      dut.io.trace.bits.stage.expect(TraceStage.DcTokens.U)
      dut.io.trace.bits.group.expect(0.U)
      dut.io.trace.bits.index.expect(26.U)
      dut.io.trace.bits.value.expect(704.S)
      dut.io.traceLast.expect(false.B)

      for (_ <- 0 until 3) {
        dut.clock.step()
        dut.io.trace.valid.expect(true.B)
        dut.io.trace.bits.stage.expect(TraceStage.DcTokens.U)
        dut.io.trace.bits.group.expect(0.U)
        dut.io.trace.bits.index.expect(26.U)
        dut.io.trace.bits.value.expect(704.S)
        dut.io.traceLast.expect(false.B)
      }

      dut.io.trace.ready.poke(true.B)
      dut.clock.step()
      waitForTraceValid(dut)
      dut.io.trace.bits.stage.expect(TraceStage.DcTokens.U)
      dut.io.trace.bits.group.expect(1.U)
      dut.io.trace.bits.index.expect(13.U)
      dut.io.trace.bits.value.expect(0.S)
      dut.io.traceLast.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "FramePreparedTokenTraceStage enforces DC-before-AC input sequencing" in {
    simulate(new FramePreparedTokenTraceStage(config)) { dut =>
      val width = 9
      val height = 1
      val dcSamples = Seq(352, 352, -23, -23, 19, 19)

      pokeConfig(dut, width, height)
      dut.io.dcInput.valid.poke(false.B)
      dut.io.acInput.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      dut.io.dcInput.ready.expect(true.B)
      dut.io.acInput.valid.poke(true.B)
      for (channel <- 0 until 3) {
        dut.io.acInput.bits.numNonzeros(channel).poke(0.U)
        for (i <- 0 until blockSize) {
          dut.io.acInput.bits.quantized(channel)(i).poke(0.S)
        }
      }
      dut.io.acInput.ready.expect(false.B)

      for (sample <- dcSamples) {
        dut.io.dcInput.valid.poke(true.B)
        dut.io.dcInput.bits.poke(sample.S)
        dut.io.dcInput.ready.expect(true.B)
        dut.io.acInput.ready.expect(false.B)
        dut.clock.step()
      }
      dut.io.dcInput.valid.poke(false.B)

      dut.io.dcInput.ready.expect(false.B)
      dut.io.acInput.ready.expect(true.B)
      dut.io.acInput.valid.poke(false.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "FramePreparedTokenTraceStage latches frame config for an in-flight transaction" in {
    simulate(new FramePreparedTokenTraceStage(config)) { dut =>
      val dcSamples = Seq(352, 352, -23, -23, 19, 19)

      pokeConfig(dut, width = 9, height = 1)
      dut.io.dcInput.valid.poke(false.B)
      dut.io.acInput.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      dut.io.dcInput.valid.poke(true.B)
      dut.io.dcInput.bits.poke(dcSamples.head.S)
      dut.io.dcInput.ready.expect(true.B)
      dut.clock.step()

      pokeConfig(dut, width = 8, height = 8)
      for (sample <- dcSamples.tail) {
        dut.io.dcInput.valid.poke(true.B)
        dut.io.dcInput.bits.poke(sample.S)
        dut.io.dcInput.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.dcInput.valid.poke(false.B)

      for (_ <- 0 until 2) {
        dut.io.acInput.valid.poke(true.B)
        for (channel <- 0 until 3) {
          dut.io.acInput.bits.numNonzeros(channel).poke(0.U)
          for (i <- 0 until blockSize) {
            dut.io.acInput.bits.quantized(channel)(i).poke(0.S)
          }
        }
        waitForAcInputReady(dut)
        dut.io.acInput.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.acInput.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)

      for (ordinal <- dcSamples.indices) {
        waitForTraceValid(dut)
        dut.io.trace.bits.stage.expect(TraceStage.DcTokens.U)
        dut.io.trace.bits.group.expect(ordinal.U)
        dut.clock.step()
      }
      for (index <- 0 until 2) {
        waitForTraceValid(dut)
        dut.io.trace.bits.stage.expect(TraceStage.AcStrategy.U)
        dut.io.trace.bits.index.expect(index.U)
        dut.clock.step()
      }
      dut.io.overflow.expect(false.B)
    }
  }

  "FramePreparedTokenTraceStage preserves nonzero prepared AC token streams after metadata" in {
    simulate(new FramePreparedTokenTraceStage(config)) { dut =>
      val width = 9
      val height = 9
      val xBlocks = 2
      val yBlocks = 2
      val dcSamples = Seq(
        10, 12, 20, 25,
        -3, -1, 4, 7,
        50, 49, 51, 60
      )
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
      val dcTokenCount = dcSamples.length
      val strategyCount = xBlocks * yBlocks
      val metadataCount = 2 + strategyCount * 3
      val expectedAcTokenCount = acTokenCount(blocks)
      expectedAcTokenCount must be > strategyCount * 3

      pokeConfig(dut, width, height)
      dut.io.dcInput.valid.poke(false.B)
      dut.io.acInput.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for (sample <- dcSamples) {
        dut.io.dcInput.valid.poke(true.B)
        dut.io.dcInput.bits.poke(sample.S)
        dut.io.dcInput.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.dcInput.valid.poke(false.B)

      for (block <- blocks) {
        dut.io.acInput.valid.poke(true.B)
        for (channel <- 0 until 3) {
          dut.io.acInput.bits.numNonzeros(channel).poke(block.numNonzeros(channel).U)
          for (i <- 0 until blockSize) {
            dut.io.acInput.bits.quantized(channel)(i).poke(block.quantized(channel)(i).S)
          }
        }
        waitForAcInputReady(dut)
        dut.io.acInput.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.acInput.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)

      val traces = scala.collection.mutable.ArrayBuffer.empty[ExpectedTrace]
      val traceLastValues = scala.collection.mutable.ArrayBuffer.empty[Boolean]
      for (ordinal <- 0 until dcTokenCount + strategyCount + metadataCount + expectedAcTokenCount) {
        waitForTraceValid(dut)
        traces += ExpectedTrace(
          dut.io.trace.bits.stage.peekValue().asBigInt.toInt,
          dut.io.trace.bits.group.peekValue().asBigInt.toInt,
          dut.io.trace.bits.index.peekValue().asBigInt.toInt,
          dut.io.trace.bits.value.peekValue().asBigInt.toInt
        )
        traceLastValues += dut.io.traceLast.peekValue().asBigInt.testBit(0)
        dut.clock.step()
      }

      traces.take(dcTokenCount).forall(_.stage == TraceStage.DcTokens) mustBe true
      traces.slice(dcTokenCount, dcTokenCount + strategyCount).zipWithIndex.foreach { case (trace, index) =>
        trace.stage mustBe TraceStage.AcStrategy
        trace.index mustBe index
      }
      traces
        .slice(dcTokenCount + strategyCount, dcTokenCount + strategyCount + metadataCount)
        .forall(_.stage == TraceStage.AcMetadataTokens) mustBe true
      val acTraces = traces.drop(dcTokenCount + strategyCount + metadataCount)
      acTraces.map(_.stage).forall(_ == TraceStage.AcTokens) mustBe true
      acTraces.map(_.group) mustBe (0 until expectedAcTokenCount)
      acTraces.exists(_.value != 0) mustBe true
      traceLastValues.zipWithIndex.foreach { case (traceLast, ordinal) =>
        withClue(s"prepared combined traceLast at output ordinal $ordinal") {
          traceLast mustBe (ordinal == traces.length - 1)
        }
      }

      dut.io.trace.valid.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "FramePreparedTokenTraceStage preserves stream partitions for exact 72px capacity" in {
    val exactCapacity = HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 8)
    simulate(new FramePreparedTokenTraceStage(exactCapacity)) { dut =>
      val width = 72
      val height = 8
      val xBlocks = 9
      val yBlocks = 1
      val xTiles = 2
      val yTiles = 1
      val dcSamples =
        Seq(10, 12, 14, 13, 17, 19, 18, 20, 21) ++
          Seq(-3, -1, 0, 2, 1, 4, 6, 5, 7) ++
          Seq(50, 49, 51, 53, 52, 55, 56, 58, 57)
      val blocks = Seq.fill(xBlocks * yBlocks)(zeroBlock)
      val dcTokenCount = dcSamples.length
      val strategyCount = xBlocks * yBlocks
      val metadataCount = xTiles * yTiles * 2 + strategyCount * 3
      val expectedAcTokenCount = acTokenCount(blocks)
      expectedAcTokenCount mustBe strategyCount * 3

      pokeConfig(dut, width, height, fixedYtox = -3, fixedYtob = 4)
      dut.io.dcInput.valid.poke(false.B)
      dut.io.acInput.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for (sample <- dcSamples) {
        dut.io.dcInput.valid.poke(true.B)
        dut.io.dcInput.bits.poke(sample.S)
        dut.io.dcInput.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.dcInput.valid.poke(false.B)

      for (block <- blocks) {
        dut.io.acInput.valid.poke(true.B)
        for (channel <- 0 until 3) {
          dut.io.acInput.bits.numNonzeros(channel).poke(block.numNonzeros(channel).U)
          for (i <- 0 until blockSize) {
            dut.io.acInput.bits.quantized(channel)(i).poke(block.quantized(channel)(i).S)
          }
        }
        waitForAcInputReady(dut)
        dut.io.acInput.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.acInput.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)

      val totalTraceCount = dcTokenCount + strategyCount + metadataCount + expectedAcTokenCount
      val traces = scala.collection.mutable.ArrayBuffer.empty[ExpectedTrace]
      val traceLastValues = scala.collection.mutable.ArrayBuffer.empty[Boolean]
      for (ordinal <- 0 until totalTraceCount) {
        waitForTraceValid(dut)
        traces += ExpectedTrace(
          dut.io.trace.bits.stage.peekValue().asBigInt.toInt,
          dut.io.trace.bits.group.peekValue().asBigInt.toInt,
          dut.io.trace.bits.index.peekValue().asBigInt.toInt,
          dut.io.trace.bits.value.peekValue().asBigInt.toInt
        )
        traceLastValues += dut.io.traceLast.peekValue().asBigInt.testBit(0)
        dut.clock.step()
      }

      traces.take(dcTokenCount).forall(_.stage == TraceStage.DcTokens) mustBe true
      traces.slice(dcTokenCount, dcTokenCount + strategyCount).zipWithIndex.foreach { case (trace, index) =>
        trace.stage mustBe TraceStage.AcStrategy
        trace.index mustBe index
      }
      val metadataTraces = traces.slice(dcTokenCount + strategyCount, dcTokenCount + strategyCount + metadataCount)
      metadataTraces.map(_.stage).forall(_ == TraceStage.AcMetadataTokens) mustBe true
      metadataTraces.length mustBe 31
      metadataTraces.take(4).map(_.index) mustBe Seq(2, 2, 1, 1)
      val acTraces = traces.drop(dcTokenCount + strategyCount + metadataCount)
      acTraces.map(_.stage).forall(_ == TraceStage.AcTokens) mustBe true
      acTraces.map(_.group) mustBe (0 until expectedAcTokenCount)
      traceLastValues.zipWithIndex.foreach { case (traceLast, ordinal) =>
        withClue(s"exact-capacity combined traceLast at output ordinal $ordinal") {
          traceLast mustBe (ordinal == traces.length - 1)
        }
      }

      dut.io.trace.valid.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
  }
}
