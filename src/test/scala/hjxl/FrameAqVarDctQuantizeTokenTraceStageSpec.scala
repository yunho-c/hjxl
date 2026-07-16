// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.jdk.CollectionConverters._
import scala.sys.process.Process
import scala.sys.process.ProcessLogger

class FrameAqVarDctQuantizeTokenTraceStageSpec
    extends AnyFreeSpec
    with Matchers
    with ChiselSim {
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim
  private val maxCoefficients = QuantizeVarDctBlock.MaxCoefficients
  private val libjxlTinyRoot =
    Path.of(sys.env.getOrElse("LIBJXL_TINY", "/Users/yunhocho/GitHub/libjxl-tiny"))

  private case class ExpectedTrace(
      stage: Int,
      group: BigInt,
      index: BigInt,
      value: BigInt,
      last: Boolean
  )

  private case class RgbInput(x: Int, y: Int, rQ8: Int, gQ8: Int, bQ8: Int)

  private def pokeConfig(
      dut: FramePreparedAcStrategyVarDctQuantizeTokenTraceStage,
      width: Int = 16,
      height: Int = 16
  ): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(0.U)
    dut.io.config.fixedInvQacQ16.poke(0.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.fixedYtox.poke(0.S)
    dut.io.config.fixedYtob.poke(0.S)
    dut.io.config.enableXyb.poke(true.B)
    dut.io.config.enableDct.poke(true.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(true.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.AcTokens.U)
  }

  private def pokeRgbConfig(dut: FrameAqVarDctQuantizeTokenTraceStage): Unit = {
    dut.io.config.xsize.poke(16.U)
    dut.io.config.ysize.poke(16.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(1.U)
    dut.io.config.fixedInvQacQ16.poke(1.U)
    dut.io.config.fixedRawQuant.poke(5.U)
    dut.io.config.fixedYtox.poke(0.S)
    dut.io.config.fixedYtob.poke(0.S)
    dut.io.config.enableXyb.poke(true.B)
    dut.io.config.enableDct.poke(true.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(true.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.AcTokens.U)
  }

  private def pokeAdaptiveRgbConfig(dut: FrameAqVarDctQuantizeTokenTraceStage): Unit = {
    dut.io.config.xsize.poke(16.U)
    dut.io.config.ysize.poke(16.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(0.U)
    dut.io.config.fixedInvQacQ16.poke(0.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.fixedYtox.poke(0.S)
    dut.io.config.fixedYtob.poke(0.S)
    dut.io.config.enableXyb.poke(true.B)
    dut.io.config.enableDct.poke(true.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(true.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.AcTokens.U)
  }

  private def pokeAxiConfig(dut: HjxlAxiStreamCore): Unit = {
    dut.io.config.xsize.poke(8.U)
    dut.io.config.ysize.poke(8.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(1.U)
    dut.io.config.fixedInvQacQ16.poke(1.U)
    dut.io.config.fixedRawQuant.poke(5.U)
    dut.io.config.fixedYtox.poke(0.S)
    dut.io.config.fixedYtob.poke(0.S)
    dut.io.config.enableXyb.poke(true.B)
    dut.io.config.enableDct.poke(true.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(true.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.AcTokens.U)
    dut.io.clearProtocolError.poke(false.B)
  }

  private def zeroFixture(): (Seq[Int], Seq[ExpectedTrace]) = {
    assume(
      Files.isDirectory(libjxlTinyRoot.resolve("python")),
      s"libjxl-tiny Python checkout not found at $libjxlTinyRoot"
    )
    val directory = Files.createTempDirectory("hjxl-strategy-var-dct-zero-")
    val output = scala.collection.mutable.ArrayBuffer.empty[String]
    val logger = ProcessLogger(line => output += line, line => output += line)
    val exitCode = Process(
      Seq(
        "python3",
        "tools/hjxl_reference.py",
        "--width",
        "16",
        "--height",
        "16",
        "--pattern",
        "constant",
        "--strategy-var-dct-zero-fixture-dir",
        directory.toString
      ),
      TestPaths.repoRoot.toFile,
      "LIBJXL_TINY" -> libjxlTinyRoot.toString,
      "PYTHONDONTWRITEBYTECODE" -> "1"
    ).!(logger)
    withClue(output.mkString("\n")) {
      exitCode mustBe 0
    }

    val blockLines = Files.readAllLines(directory.resolve("blocks.csv"), StandardCharsets.UTF_8)
      .asScala
      .toSeq
    blockLines.head must startWith("block,last,raw_quant")
    val rawQuants = blockLines.tail.map(_.split(",", -1)(2).toInt)

    val sectionToStage = Map(
      "dc" -> TraceStage.DcTokens,
      "strategy" -> TraceStage.AcStrategy,
      "metadata" -> TraceStage.AcMetadataTokens,
      "ac" -> TraceStage.AcTokens
    )
    val traceLines = Files.readAllLines(directory.resolve("trace.csv"), StandardCharsets.UTF_8)
      .asScala
      .toSeq
    traceLines.head mustBe "section,group,index,value,last"
    val traces = traceLines.tail.map { line =>
      val row = line.split(",", -1)
      ExpectedTrace(
        sectionToStage(row(0)),
        BigInt(row(1)),
        BigInt(row(2)),
        BigInt(row(3)),
        row(4) == "1"
      )
    }
    (rawQuants, traces)
  }

  private def runTool(command: Seq[String]): String = {
    val output = scala.collection.mutable.ArrayBuffer.empty[String]
    val logger = ProcessLogger(line => output += line, line => output += line)
    val exitCode = Process(
      command,
      TestPaths.repoRoot.toFile,
      "LIBJXL_TINY" -> libjxlTinyRoot.toString,
      "PYTHONDONTWRITEBYTECODE" -> "1"
    ).!(logger)
    val text = output.mkString("\n")
    withClue(text) {
      exitCode mustBe 0
    }
    text
  }

  private def writeTraceCsv(path: Path, traces: Seq[ExpectedTrace]): Unit = {
    val rows = traces.map { trace =>
      s"${trace.stage},${trace.group},${trace.index},${trace.value}"
    }
    Files.writeString(
      path,
      ("stage,group,index,value" +: rows).mkString("", "\n", "\n"),
      StandardCharsets.UTF_8
    )
  }

  private def readRgbInputs(path: Path, width: Int, height: Int): Seq[RgbInput] = {
    val lines = Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toSeq
    lines.head mustBe "raster,x,y,r_q8,g_q8,b_q8,xyb_x_q12,xyb_y_q12,xyb_b_q12"
    lines.tail
      .map { line =>
        val row = line.split(",", -1)
        RgbInput(row(1).toInt, row(2).toInt, row(3).toInt, row(4).toInt, row(5).toInt)
      }
      .filter(row => row.x < width && row.y < height)
  }

  "the selected-cell adapter maps a horizontal owner and waits for its adjusted reciprocal" in {
    val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 16)
    simulate(new AcStrategySelectedCellToVarDctOwnerStage(config)) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.output.ready.poke(true.B)
      dut.clock.step()

      val scaleQ16 = 4096
      val rawQuant = 5
      dut.io.input.valid.poke(true.B)
      dut.io.input.bits.blockX.poke(0.U)
      dut.io.input.bits.blockY.poke(0.U)
      dut.io.input.bits.blockIndex.poke(0.U)
      dut.io.input.bits.encodedStrategy.poke(
        AcStrategyCode.encoded(AcStrategyCode.Dct8x16, isFirstBlock = true).U
      )
      dut.io.input.bits.adjustedRawQuant.poke(rawQuant.U)
      dut.io.input.bits.ownerLast.poke(true.B)
      dut.io.input.bits.distanceQ8.poke(256.U)
      dut.io.input.bits.scaleQ16.poke(scaleQ16.U)
      dut.io.input.bits.fixedInvQacQ16.poke(0.U)
      dut.io.input.bits.adaptiveRawQuant.poke(true.B)
      dut.io.input.bits.invDcFactorQ16.foreach(_.poke(1.U))
      dut.io.input.bits.xQmMultiplierQ16.poke((1 << 16).U)
      dut.io.input.bits.ytox.poke(0.S)
      dut.io.input.bits.ytob.poke(0.S)
      for (channel <- 0 until 3; sample <- 0 until blockSize) {
        dut.io.input.bits.dct8x8(channel)(sample).poke(0.S)
        dut.io.input.bits.coveredXyb(0)(channel)(sample).poke(1024.S)
        dut.io.input.bits.coveredXyb(1)(channel)(sample).poke(1024.S)
      }

      var waitCycles = 0
      while (!dut.io.output.valid.peek().litToBoolean && waitCycles < 40) {
        dut.io.input.ready.expect(false.B)
        dut.clock.step()
        waitCycles += 1
      }
      withClue("adaptive selected-owner reciprocal wait: ") {
        waitCycles must be < 40
      }
      dut.io.output.bits.blockX.expect(0.U)
      dut.io.output.bits.blockY.expect(0.U)
      dut.io.output.bits.last.expect(true.B)
      dut.io.output.bits.quantize.strategy.expect(AcStrategyCode.Dct8x16.U)
      dut.io.output.bits.quantize.quant.expect(rawQuant.U)
      dut.io.output.bits.quantize.invQacQ16.expect(
        AdaptiveInvQacFixedPoint.reciprocalQ16(scaleQ16, rawQuant).U
      )
      for (channel <- 0 until 3) {
        dut.io.output.bits.quantize.coefficients(channel)(0).expect(1024.S)
        for (coefficient <- 1 until maxCoefficients) {
          dut.io.output.bits.quantize.coefficients(channel)(coefficient).expect(0.S)
        }
      }
      dut.clock.step()
      dut.io.input.bits.blockX.poke(1.U)
      dut.io.input.bits.blockY.poke(0.U)
      dut.io.input.bits.encodedStrategy.poke(
        AcStrategyCode.encoded(AcStrategyCode.Dct16x8, isFirstBlock = true).U
      )
      dut.io.input.bits.fixedInvQacQ16.poke(9.U)
      dut.io.input.bits.adaptiveRawQuant.poke(false.B)
      for (channel <- 0 until 3; sample <- 0 until blockSize) {
        dut.io.input.bits.coveredXyb(0)(channel)(sample).poke((-512).S)
        dut.io.input.bits.coveredXyb(1)(channel)(sample).poke((-512).S)
      }
      dut.io.output.valid.expect(true.B)
      dut.io.output.bits.quantize.strategy.expect(AcStrategyCode.Dct16x8.U)
      dut.io.output.bits.quantize.invQacQ16.expect(9.U)
      for (channel <- 0 until 3) {
        dut.io.output.bits.quantize.coefficients(channel)(0).expect((-512).S)
        for (coefficient <- 1 until maxCoefficients) {
          dut.io.output.bits.quantize.coefficients(channel)(coefficient).expect(0.S)
        }
      }
      dut.clock.step()
      dut.io.input.valid.poke(false.B)
      dut.io.output.valid.expect(false.B)
      dut.io.busy.expect(false.B)
    }
  }

  "prepared strategy search feeds exact first-block VarDCT tokens under stalls" in {
    val (rawQuants, expected) = zeroFixture()
    rawQuants mustBe Seq(3, 7, 11, 13)
    expected.length mustBe 32
    val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 16)
    simulate(new FramePreparedAcStrategyVarDctQuantizeTokenTraceStage(config)) { dut =>
      pokeConfig(dut)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      dut.io.input.valid.poke(true.B)
      for (block <- 0 until 4) {
        dut.io.input.bits.aqMapQ24.poke((BigInt(1) << 24).U)
        dut.io.input.bits.strategyMaskQ16.poke(0.U)
        dut.io.input.bits.rawQuant.poke(rawQuants(block).U)
        dut.io.input.bits.distanceQ8.poke(256.U)
        dut.io.input.bits.scaleQ16.poke(1.U)
        dut.io.input.bits.fixedInvQacQ16.poke(1.U)
        dut.io.input.bits.adaptiveRawQuant.poke(false.B)
        dut.io.input.bits.invDcFactorQ16.foreach(_.poke(1.U))
        dut.io.input.bits.xQmMultiplierQ16.poke((1 << 16).U)
        dut.io.input.bits.last.poke((block == 3).B)
        for (channel <- 0 until 3; sample <- 0 until blockSize) {
          dut.io.input.bits.xyb(channel)(sample).poke(0.S)
          dut.io.input.bits.dct8x8(channel)(sample).poke(0.S)
        }
        var waitCycles = 0
        while (!dut.io.input.ready.peek().litToBoolean && waitCycles < 8) {
          dut.clock.step()
          waitCycles += 1
        }
        waitCycles must be < 8
        dut.clock.step()
      }
      dut.io.input.valid.poke(false.B)

      var waitCycles = 0
      while (!dut.io.trace.valid.peek().litToBoolean && waitCycles < 100000) {
        dut.clock.step()
        waitCycles += 1
      }
      withClue("prepared strategy-to-token first trace wait: ") {
        waitCycles must be < 100000
      }

      expected.zipWithIndex.foreach { case (trace, ordinal) =>
        dut.io.trace.ready.poke(true.B)
        var interTraceCycles = 0
        while (!dut.io.trace.valid.peek().litToBoolean && interTraceCycles < 256) {
          dut.clock.step()
          interTraceCycles += 1
        }
        withClue(s"trace $ordinal inter-beat wait: ") {
          interTraceCycles must be < 256
        }
        val ready = ordinal % 4 != 1
        dut.io.trace.ready.poke(ready.B)
        if (!ready) {
          val held = (
            dut.io.trace.bits.stage.peekValue().asBigInt,
            dut.io.trace.bits.group.peekValue().asBigInt,
            dut.io.trace.bits.index.peekValue().asBigInt,
            dut.io.trace.bits.value.peekValue().asBigInt,
            dut.io.traceLast.peek().litToBoolean
          )
          dut.clock.step()
          (
            dut.io.trace.bits.stage.peekValue().asBigInt,
            dut.io.trace.bits.group.peekValue().asBigInt,
            dut.io.trace.bits.index.peekValue().asBigInt,
            dut.io.trace.bits.value.peekValue().asBigInt,
            dut.io.traceLast.peek().litToBoolean
          ) mustBe held
          dut.io.trace.ready.poke(true.B)
        }
        dut.io.trace.valid.expect(true.B)
        dut.io.trace.bits.stage.expect(trace.stage.U)
        dut.io.trace.bits.group.expect(trace.group.U)
        dut.io.trace.bits.index.expect(trace.index.U)
        dut.io.trace.bits.value.expect(trace.value.S)
        dut.io.traceLast.expect(trace.last.B)
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      var drainCycles = 0
      while (dut.io.busy.peek().litToBoolean && drainCycles < 8) {
        dut.clock.step()
        drainCycles += 1
      }
      withClue("prepared post-TLAST drain: ") {
        drainCycles must be < 8
      }
      dut.io.busy.expect(false.B)
      dut.io.overflow.expect(false.B)
      dut.io.unsupportedDistance.expect(false.B)
    }
  }

  private def verifyRgbCodestream(
      pattern: String,
      expectedStrategyValues: Seq[Int],
      expectedCodestreamBytes: Int
  ): Unit = {
    assume(
      Files.isDirectory(libjxlTinyRoot.resolve("python")),
      s"libjxl-tiny Python checkout not found at $libjxlTinyRoot"
    )
    val temp = Files.createTempDirectory(s"hjxl-rgb-var-dct-$pattern-codestream-")
    val expectedDc = temp.resolve("expected-dc.npy")
    val expectedMetadata = temp.resolve("expected-metadata.npy")
    val expectedAc = temp.resolve("expected-ac.npy")
    val expectedStrategy = temp.resolve("expected-strategy.npy")
    val expectedCodestream = temp.resolve("expected.jxl")
    val nativeCodestream = temp.resolve("native.jxl")
    val rgbCsv = temp.resolve("rgb.csv")
    val traceCsv = temp.resolve("rtl-trace.csv")
    val actualDc = temp.resolve("actual-dc.npy")
    val actualMetadata = temp.resolve("actual-metadata.npy")
    val actualAc = temp.resolve("actual-ac.npy")
    val actualStrategy = temp.resolve("actual-strategy.npy")
    val actualCodestream = temp.resolve("actual.jxl")

    runTool(
      Seq(
        "python3",
        "tools/hjxl_reference.py",
        "--width",
        "16",
        "--height",
        "16",
        "--pattern",
        pattern,
        "--quantize-input-q8",
        "--xyb-q12-csv",
        rgbCsv.toString,
        "--jxl",
        nativeCodestream.toString,
        "--var-dct-dc-tokens-npy",
        expectedDc.toString,
        "--var-dct-ac-metadata-tokens-npy",
        expectedMetadata.toString,
        "--var-dct-ac-tokens-npy",
        expectedAc.toString,
        "--var-dct-ac-strategy-npy",
        expectedStrategy.toString,
        "--var-dct-codestream-bin",
        expectedCodestream.toString
      )
    )
    Files.readAllBytes(expectedCodestream).toSeq mustBe
      Files.readAllBytes(nativeCodestream).toSeq
    Files.size(expectedCodestream) mustBe expectedCodestreamBytes.toLong
    val inputs = readRgbInputs(rgbCsv, width = 16, height = 16)
    inputs.length mustBe 16 * 16

    val observed = scala.collection.mutable.ArrayBuffer.empty[ExpectedTrace]
    val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 16)
    simulate(new FrameAqVarDctQuantizeTokenTraceStage(config)) { dut =>
      pokeAdaptiveRgbConfig(dut)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      dut.io.input.valid.poke(true.B)
      for (input <- inputs) {
        dut.io.input.bits.x.poke(input.x.U)
        dut.io.input.bits.y.poke(input.y.U)
        dut.io.input.bits.r.poke(input.rQ8.S)
        dut.io.input.bits.g.poke(input.gQ8.S)
        dut.io.input.bits.b.poke(input.bQ8.S)
        var waitCycles = 0
        while (!dut.io.input.ready.peek().litToBoolean && waitCycles < 16) {
          dut.clock.step()
          waitCycles += 1
        }
        waitCycles must be < 16
        dut.clock.step()
      }
      dut.io.input.valid.poke(false.B)

      var cycles = 0
      var finished = false
      while (!finished && cycles < 200000) {
        val ready = cycles % 5 != 2
        dut.io.trace.ready.poke(ready.B)
        if (dut.io.trace.valid.peek().litToBoolean && ready) {
          observed += ExpectedTrace(
            dut.io.trace.bits.stage.peekValue().asBigInt.toInt,
            dut.io.trace.bits.group.peekValue().asBigInt,
            dut.io.trace.bits.index.peekValue().asBigInt,
            dut.io.trace.bits.value.peekValue().asBigInt,
            dut.io.traceLast.peek().litToBoolean
          )
          finished = dut.io.traceLast.peek().litToBoolean
        }
        dut.clock.step()
        cycles += 1
      }
      withClue(s"nonzero-AC $pattern RGB VarDCT completion wait: ") {
        finished mustBe true
      }
      dut.io.overflow.expect(false.B)
      dut.io.unsupportedDistance.expect(false.B)
    }

    observed.filter(_.stage == TraceStage.AcStrategy).map(_.value) mustBe
      expectedStrategyValues.map(BigInt(_))
    observed.filter(_.stage == TraceStage.DcTokens).exists(_.value != 0) mustBe true
    observed.filter(_.stage == TraceStage.AcTokens).exists(_.value != 0) mustBe true
    writeTraceCsv(traceCsv, observed.toSeq)

    val assemblyOutput = runTool(
      Seq(
        "python3",
        "tools/hjxl_trace_to_codestream.py",
        "--trace-csv",
        traceCsv.toString,
        "--width",
        "16",
        "--height",
        "16",
        "--distance",
        "1.0",
        "--dc-tokens-npy",
        actualDc.toString,
        "--ac-metadata-tokens-npy",
        actualMetadata.toString,
        "--ac-tokens-npy",
        actualAc.toString,
        "--ac-strategy-npy",
        actualStrategy.toString,
        "--codestream-bin",
        actualCodestream.toString,
        "--expect-codestream-bin",
        expectedCodestream.toString
      )
    )
    assemblyOutput must include("assembled trace:")
    runTool(
      Seq(
        "python3",
        "tools/hjxl_compare_tokens.py",
        "--expected-dc-tokens-npy",
        expectedDc.toString,
        "--actual-dc-tokens-npy",
        actualDc.toString,
        "--expected-ac-metadata-tokens-npy",
        expectedMetadata.toString,
        "--actual-ac-metadata-tokens-npy",
        actualMetadata.toString,
        "--expected-ac-tokens-npy",
        expectedAc.toString,
        "--actual-ac-tokens-npy",
        actualAc.toString,
        "--expected-ac-strategy-npy",
        expectedStrategy.toString,
        "--actual-ac-strategy-npy",
        actualStrategy.toString
      )
    )
    Files.readAllBytes(actualCodestream).toSeq mustBe
      Files.readAllBytes(expectedCodestream).toSeq
  }

  "a nonzero-AC Q8 RGB impulse assembles to the native VarDCT codestream" in {
    verifyRgbCodestream(
      pattern = "impulse",
      expectedStrategyValues = Seq(5, 4, 1, 1),
      expectedCodestreamBytes = 197
    )
  }

  "a nonzero-AC Q8 RGB gradient assembles to the native VarDCT codestream" in {
    verifyRgbCodestream(
      pattern = "gradient",
      expectedStrategyValues = Seq(1, 1, 5, 4),
      expectedCodestreamBytes = 230
    )
  }

  "a nonzero-AC Q8 RGB checkerboard assembles to the native VarDCT codestream" in {
    verifyRgbCodestream(
      pattern = "checkerboard",
      expectedStrategyValues = Seq(5, 4, 5, 4),
      expectedCodestreamBytes = 256
    )
  }

  "the RGB route reaches every logical-token phase and drains before another frame" in {
    val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 16)
    simulate(new FrameAqVarDctQuantizeTokenTraceStage(config)) { dut =>
      pokeRgbConfig(dut)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      dut.io.input.valid.poke(true.B)
      for (y <- 0 until 16; x <- 0 until 16) {
        dut.io.input.bits.x.poke(x.U)
        dut.io.input.bits.y.poke(y.U)
        dut.io.input.bits.r.poke(0.S)
        dut.io.input.bits.g.poke(0.S)
        dut.io.input.bits.b.poke(0.S)
        var waitCycles = 0
        while (!dut.io.input.ready.peek().litToBoolean && waitCycles < 16) {
          dut.clock.step()
          waitCycles += 1
        }
        waitCycles must be < 16
        dut.clock.step()
      }
      dut.io.input.valid.poke(false.B)

      val stages = scala.collection.mutable.ArrayBuffer.empty[Int]
      val strategyValues = scala.collection.mutable.ArrayBuffer.empty[Int]
      var cycles = 0
      var finished = false
      while (!finished && cycles < 200000) {
        val ready = cycles % 5 != 2
        dut.io.trace.ready.poke(ready.B)
        if (dut.io.trace.valid.peek().litToBoolean && ready) {
          val stage = dut.io.trace.bits.stage.peekValue().asBigInt.toInt
          stages += stage
          if (stage == TraceStage.AcStrategy) {
            strategyValues += dut.io.trace.bits.value.peekValue().asBigInt.toInt
          }
          finished = dut.io.traceLast.peek().litToBoolean
        }
        dut.clock.step()
        cycles += 1
      }
      withClue("RGB VarDCT token completion wait: ") {
        finished mustBe true
      }
      stages.count(_ == TraceStage.DcTokens) mustBe 12
      stages.count(_ == TraceStage.AcStrategy) mustBe 4
      stages must contain(TraceStage.AcMetadataTokens)
      stages must contain(TraceStage.AcTokens)
      strategyValues.length mustBe 4
      strategyValues.forall(value => value >= 0 && value <= 5) mustBe true
      var drainCycles = 0
      while (dut.io.busy.peek().litToBoolean && drainCycles < 8) {
        dut.clock.step()
        drainCycles += 1
      }
      withClue("RGB post-TLAST drain: ") {
        drainCycles must be < 8
      }
      dut.io.busy.expect(false.B)
      dut.io.overflow.expect(false.B)
      dut.io.unsupportedDistance.expect(false.B)
      dut.io.input.ready.expect(true.B)
      dut.io.config.distanceQ8.poke(300.U)
      dut.io.unsupportedDistance.expect(true.B)
    }
  }

  "the focused AXI stream route packs mixed token stages with final-only TLAST" in {
    val config = HjxlConfig(maxFrameWidth = 8, maxFrameHeight = 8)
    simulate(new HjxlAxiStreamCore(config, traceRoute = HjxlCoreTraceRoute.AqVarDctTokens)) { dut =>
      pokeAxiConfig(dut)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      dut.io.input.valid.poke(true.B)
      dut.io.input.bits.data.poke(0.U)
      for (pixel <- 0 until 64) {
        dut.io.input.bits.last.poke((pixel == 63).B)
        var waitCycles = 0
        while (!dut.io.input.ready.peek().litToBoolean && waitCycles < 16) {
          dut.clock.step()
          waitCycles += 1
        }
        waitCycles must be < 16
        dut.clock.step()
      }
      dut.io.input.valid.poke(false.B)
      dut.io.protocolError.expect(false.B)

      val stages = scala.collection.mutable.ArrayBuffer.empty[Int]
      var cycles = 0
      var finished = false
      while (!finished && cycles < 100000) {
        val ready = cycles % 4 != 1
        dut.io.trace.ready.poke(ready.B)
        if (dut.io.trace.valid.peek().litToBoolean) {
          val packed = dut.io.trace.bits.data.peekValue().asBigInt
          val stage = (packed & 0xff).toInt
          if (ready) {
            stages += stage
            finished = dut.io.trace.bits.last.peek().litToBoolean
          }
        }
        dut.clock.step()
        cycles += 1
      }
      withClue("focused AXI VarDCT token completion wait: ") {
        finished mustBe true
      }
      stages.count(_ == TraceStage.DcTokens) mustBe 3
      stages.count(_ == TraceStage.AcStrategy) mustBe 1
      stages must contain(TraceStage.AcMetadataTokens)
      stages must contain(TraceStage.AcTokens)
      dut.io.protocolError.expect(false.B)
      var drainCycles = 0
      while (dut.io.busy.peek().litToBoolean && drainCycles < 8) {
        dut.clock.step()
        drainCycles += 1
      }
      drainCycles must be < 8
      dut.io.busy.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "the selected-cell adapter rejects an unsupported first-block strategy" in {
    val config = HjxlConfig(maxFrameWidth = 8, maxFrameHeight = 8)
    assertThrows[chisel3.simulator.Exceptions.AssertionFailed] {
      simulate(new AcStrategySelectedCellToVarDctOwnerStage(config)) { dut =>
        dut.io.input.valid.poke(true.B)
        dut.io.output.ready.poke(true.B)
        dut.io.input.bits.blockX.poke(0.U)
        dut.io.input.bits.blockY.poke(0.U)
        dut.io.input.bits.blockIndex.poke(0.U)
        dut.io.input.bits.encodedStrategy.poke(7.U)
        dut.io.input.bits.adjustedRawQuant.poke(5.U)
        dut.io.input.bits.ownerLast.poke(true.B)
        dut.io.input.bits.distanceQ8.poke(256.U)
        dut.io.input.bits.scaleQ16.poke(1.U)
        dut.io.input.bits.fixedInvQacQ16.poke(1.U)
        dut.io.input.bits.adaptiveRawQuant.poke(false.B)
        dut.io.input.bits.invDcFactorQ16.foreach(_.poke(1.U))
        dut.io.input.bits.xQmMultiplierQ16.poke((1 << 16).U)
        dut.io.input.bits.ytox.poke(0.S)
        dut.io.input.bits.ytob.poke(0.S)
        for (channel <- 0 until 3; sample <- 0 until blockSize) {
          dut.io.input.bits.dct8x8(channel)(sample).poke(0.S)
          dut.io.input.bits.coveredXyb(0)(channel)(sample).poke(0.S)
          dut.io.input.bits.coveredXyb(1)(channel)(sample).poke(0.S)
        }
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
      }
    }
  }
}

class FrameAqVarDctQuantizeTokenElaborationSpec extends AnyFreeSpec with Matchers {
  "the RGB VarDCT token top elaborates one shared AQ/DCT strategy source" in {
    val text = ChiselStage.emitSystemVerilog(
      new FrameAqVarDctQuantizeTokenTraceStage(
        HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 16)
      ),
      firtoolOpts = Array(
        "-disable-all-randomization",
        "-strip-debug-info",
        "-default-layer-specialization=enable"
      )
    )
    text must include("module FrameAqVarDctQuantizeTokenTraceStage")
    text must include("module FrameAqDctBlockStage")
    text must include("module FramePreparedAcStrategyTraceStage")
    text must include("module AcStrategySelectedCellToVarDctOwnerStage")
    text must include("module FramePreparedVarDctQuantizeTokenTraceStage")
    text must include("module FramePreparedVarDctTokenTraceStage")
    text must not include "module FrameAqDctBlockStage_1"
  }
}
