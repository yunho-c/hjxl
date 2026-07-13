// See README.md for license details.

package hjxl

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.jdk.CollectionConverters._
import scala.sys.process.Process
import scala.sys.process.ProcessLogger

class HjxlPreparedDctAxiStreamCoreSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 8)
  private val width = 16
  private val height = 8
  private val pattern = "gradient"
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim
  private val libjxlTinyRoot =
    Path.of(sys.env.getOrElse("LIBJXL_TINY", "/Users/yunhocho/GitHub/libjxl-tiny"))

  private case class CommandResult(exitCode: Int, output: String)

  private case class PreparedBlock(
      quant: Int,
      scaleQ16: Int,
      invQacQ16: Int,
      invDcFactorQ16: Seq[Int],
      xQmMultiplierQ16: Int,
      ytox: Int,
      ytob: Int,
      coefficients: Seq[Seq[Int]]
  )

  private case class ExpectedTrace(stage: Int, group: Int, index: Int, value: Int)
  private case class StreamWord(data: BigInt, last: Boolean)

  private def requireReferenceTools(): Unit = {
    assume(
      Files.isDirectory(libjxlTinyRoot.resolve("python")),
      s"libjxl-tiny Python checkout not found at $libjxlTinyRoot"
    )
    assume(
      Process(Seq("python3", "-c", "import numpy")).! == 0,
      "python3 with numpy is required for prepared-DCT stream oracle tests"
    )
  }

  private def runCommand(command: Seq[String], extraEnv: (String, String)*): CommandResult = {
    val output = scala.collection.mutable.ArrayBuffer.empty[String]
    val logger = ProcessLogger(line => output += line, line => output += line)
    val exitCode = Process(command, TestPaths.repoRoot.toFile, extraEnv: _*).!(logger)
    CommandResult(exitCode, output.mkString("\n"))
  }

  private def expectSuccess(command: Seq[String], extraEnv: (String, String)*): String = {
    val result = runCommand(command, extraEnv: _*)
    withClue(result.output) {
      result.exitCode mustBe 0
    }
    result.output
  }

  private def readStreamCsv(path: Path): Seq[StreamWord] =
    Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toVector.drop(1).map { line =>
      val columns = line.split(",", -1)
      StreamWord(BigInt(columns(0)), columns(1).toInt != 0)
    }

  private def writeStreamCsv(path: Path, rows: Seq[StreamWord]): Unit =
    Files.writeString(
      path,
      "data,last\n" + rows.map(row => s"${row.data},${if (row.last) 1 else 0}").mkString("\n") + "\n"
    )

  private def zeroPreparedBlock(quant: Int, ytox: Int = 0, ytob: Int = 0): PreparedBlock =
    PreparedBlock(
      quant = quant,
      scaleQ16 = QuantizeDct8x8Block.DefaultScaleQ16,
      invQacQ16 = QuantizeDct8x8Block.DefaultInvQacQ16,
      invDcFactorQ16 = QuantizeDct8x8Block.DefaultInvDcFactorQ16,
      xQmMultiplierQ16 = QuantizeDct8x8Block.DefaultQmMultiplierQ16,
      ytox = ytox,
      ytob = ytob,
      coefficients = Seq.fill(3)(Seq.fill(blockSize)(0))
    )

  private def metadataTokenCount: Int = {
    val xBlocks = (width + HjxlConstants.BlockDim - 1) / HjxlConstants.BlockDim
    val yBlocks = (height + HjxlConstants.BlockDim - 1) / HjxlConstants.BlockDim
    val blocks = xBlocks * yBlocks
    val xTiles = math.max(1, (width + HjxlConstants.TileDim - 1) / HjxlConstants.TileDim)
    val yTiles = math.max(1, (height + HjxlConstants.TileDim - 1) / HjxlConstants.TileDim)
    xTiles * yTiles * 2 + blocks * 3
  }

  private def zeroCombinedTraceCount(blockCount: Int): Int =
    blockCount * 3 + blockCount + metadataTokenCount + blockCount * 3

  private def pokeConfig(dut: FramePreparedDctOnlyQuantizeTokenTraceStage): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(QuantizeDct8x8Block.DefaultScaleQ16.U)
    dut.io.config.fixedInvQacQ16.poke(QuantizeDct8x8Block.DefaultInvQacQ16.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.fixedYtox.poke(0.S)
    dut.io.config.fixedYtob.poke(0.S)
    dut.io.config.enableXyb.poke(false.B)
    dut.io.config.enableDct.poke(true.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(true.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.AcTokens.U)
  }

  private def pokeConfig(dut: HjxlPreparedDctAxiStreamCore): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    pokeSharedConfig(dut)
  }

  private def pokeConfig(dut: HjxlPreparedDctAxiStreamCore, frameWidth: Int, frameHeight: Int): Unit = {
    dut.io.config.xsize.poke(frameWidth.U)
    dut.io.config.ysize.poke(frameHeight.U)
    pokeSharedConfig(dut)
  }

  private def pokeSharedConfig(dut: HjxlPreparedDctAxiStreamCore): Unit = {
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(QuantizeDct8x8Block.DefaultScaleQ16.U)
    dut.io.config.fixedInvQacQ16.poke(QuantizeDct8x8Block.DefaultInvQacQ16.U)
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

  private def pokeBlock(dut: FramePreparedDctOnlyQuantizeTokenTraceStage, block: PreparedBlock): Unit = {
    dut.io.input.bits.quant.poke(block.quant.U)
    dut.io.input.bits.scaleQ16.poke(block.scaleQ16.U)
    dut.io.input.bits.invQacQ16.poke(block.invQacQ16.U)
    dut.io.input.bits.xQmMultiplierQ16.poke(block.xQmMultiplierQ16.U)
    dut.io.input.bits.ytox.poke(block.ytox.S)
    dut.io.input.bits.ytob.poke(block.ytob.S)
    for (channel <- 0 until 3) {
      dut.io.input.bits.invDcFactorQ16(channel).poke(block.invDcFactorQ16(channel).U)
      for (i <- 0 until blockSize) {
        dut.io.input.bits.coefficients(channel)(i).poke(block.coefficients(channel)(i).S)
      }
    }
  }

  private def collectDirectTraces(blocks: Seq[PreparedBlock], expectedTraceCount: Int): Seq[ExpectedTrace] = {
    val traces = scala.collection.mutable.ArrayBuffer.empty[ExpectedTrace]
    simulate(new FramePreparedDctOnlyQuantizeTokenTraceStage(config)) { dut =>
      pokeConfig(dut)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for (block <- blocks) {
        dut.io.input.valid.poke(true.B)
        pokeBlock(dut, block)
        waitForReady(dut.io.input.ready, dut.clock, "direct prepared-DCT block input")
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)

      for (ordinal <- 0 until expectedTraceCount) {
        waitForTrace(dut.io.trace.valid, dut.clock, s"direct trace $ordinal")
        dut.io.traceLast.expect((ordinal == expectedTraceCount - 1).B)
        traces += ExpectedTrace(
          dut.io.trace.bits.stage.peekValue().asBigInt.toInt,
          dut.io.trace.bits.group.peekValue().asBigInt.toInt,
          dut.io.trace.bits.index.peekValue().asBigInt.toInt,
          dut.io.trace.bits.value.peekValue().asBigInt.toInt
        )
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
    traces.toSeq
  }

  private def packSignedWord(value: Int): BigInt =
    BigInt(value) & ((BigInt(1) << 32) - 1)

  private def streamWords(block: PreparedBlock): Seq[BigInt] =
    Seq(
      BigInt(block.quant),
      BigInt(block.scaleQ16),
      BigInt(block.invQacQ16),
      BigInt(block.invDcFactorQ16(0)),
      BigInt(block.invDcFactorQ16(1)),
      BigInt(block.invDcFactorQ16(2)),
      BigInt(block.xQmMultiplierQ16),
      packSignedWord(block.ytox),
      packSignedWord(block.ytob)
    ) ++ block.coefficients.flatten.map(packSignedWord)

  private def unpackTraceData(data: BigInt): ExpectedTrace = {
    val stage = (data & 0xff).toInt
    val group = ((data >> 8) & 0xffff).toInt
    val index = ((data >> 24) & 0xffffffffL).toInt
    val rawValue = (data >> 56) & 0xffffffffL
    val value = if ((rawValue & 0x80000000L) != 0) (rawValue - (1L << 32)).toInt else rawValue.toInt
    ExpectedTrace(stage, group, index, value)
  }

  private def waitForTrace(valid: Bool, clock: Clock, clue: String): Unit = {
    var cycles = 0
    while (valid.peekValue().asBigInt == 0 && cycles < 512) {
      clock.step()
      cycles += 1
    }
    withClue(clue) {
      cycles must be < 512
    }
  }

  private def waitForReady(ready: Bool, clock: Clock, clue: String): Unit = {
    var cycles = 0
    while (ready.peekValue().asBigInt == 0 && cycles < blockSize + 2) {
      clock.step()
      cycles += 1
    }
    withClue(clue) {
      cycles must be < blockSize + 2
    }
  }

  private def driveStreamWord(
      dut: HjxlPreparedDctAxiStreamCore,
      data: BigInt,
      last: Boolean,
      clue: String
  ): Unit = {
    dut.io.input.valid.poke(true.B)
    dut.io.input.bits.data.poke(data.U)
    dut.io.input.bits.last.poke(last.B)
    var cycles = 0
    while (dut.io.input.ready.peekValue().asBigInt == 0 && cycles < 64) {
      dut.clock.step()
      cycles += 1
    }
    withClue(clue) {
      cycles must be < 64
    }
    dut.clock.step()
  }

  "HjxlPreparedDctAxiStreamCore unpacks prepared blocks and packs token traces" in {
    val blocks = Seq(zeroPreparedBlock(quant = 1, ytox = -2, ytob = 3), zeroPreparedBlock(quant = 2))
    val expectedTraceCount = zeroCombinedTraceCount(blocks.length)
    val expected = collectDirectTraces(blocks, expectedTraceCount)
    val observed = scala.collection.mutable.ArrayBuffer.empty[ExpectedTrace]

    simulate(new HjxlPreparedDctAxiStreamCore(config)) { dut =>
      pokeConfig(dut)
      dut.io.input.valid.poke(false.B)
      dut.io.input.bits.data.poke(0.U)
      dut.io.input.bits.last.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      val words = blocks.flatMap(streamWords)
      words.length mustBe blocks.length * PreparedDctStreamLayout.WordsPerBlock
      for ((word, ordinal) <- words.zipWithIndex) {
        driveStreamWord(dut, word, last = ordinal == words.length - 1, s"prepared stream input word $ordinal")
      }
      dut.io.input.valid.poke(false.B)
      dut.io.protocolError.expect(false.B)

      dut.io.trace.ready.poke(true.B)
      for (ordinal <- 0 until expectedTraceCount) {
        waitForTrace(dut.io.trace.valid, dut.clock, s"stream trace $ordinal")
        dut.io.trace.bits.last.expect((ordinal == expectedTraceCount - 1).B)
        observed += unpackTraceData(dut.io.trace.bits.data.peekValue().asBigInt)
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.overflow.expect(false.B)
    }

    observed.toSeq mustBe expected
  }

  "HjxlPreparedDctAxiStreamCore preserves stream partitions for exact 72px capacity" in {
    val exactConfig = HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 8)
    val frameWidth = 72
    val frameHeight = 8
    val xBlocks = 9
    val yBlocks = 1
    val xTiles = 2
    val yTiles = 1
    val blocks = Seq.fill(xBlocks * yBlocks)(zeroPreparedBlock(quant = 1))
    val expectedDcTokenCount = blocks.length * 3
    val expectedStrategyCount = blocks.length
    val expectedMetadataCount = xTiles * yTiles * 2 + blocks.length * 3
    val expectedAcTokenCount = blocks.length * 3
    val expectedTraceCount =
      expectedDcTokenCount + expectedStrategyCount + expectedMetadataCount + expectedAcTokenCount
    val observed = scala.collection.mutable.ArrayBuffer.empty[ExpectedTrace]
    val traceLastValues = scala.collection.mutable.ArrayBuffer.empty[Boolean]

    simulate(new HjxlPreparedDctAxiStreamCore(exactConfig)) { dut =>
      pokeConfig(dut, frameWidth, frameHeight)
      dut.io.input.valid.poke(false.B)
      dut.io.input.bits.data.poke(0.U)
      dut.io.input.bits.last.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      val words = blocks.flatMap(streamWords)
      words.length mustBe blocks.length * PreparedDctStreamLayout.WordsPerBlock
      for ((word, ordinal) <- words.zipWithIndex) {
        driveStreamWord(dut, word, last = ordinal == words.length - 1, s"exact-capacity prepared stream input word $ordinal")
      }
      dut.io.input.valid.poke(false.B)
      dut.io.protocolError.expect(false.B)

      dut.io.trace.ready.poke(true.B)
      for (ordinal <- 0 until expectedTraceCount) {
        waitForTrace(dut.io.trace.valid, dut.clock, s"exact-capacity stream trace $ordinal")
        observed += unpackTraceData(dut.io.trace.bits.data.peekValue().asBigInt)
        traceLastValues += dut.io.trace.bits.last.peekValue().asBigInt.testBit(0)
        dut.clock.step()
      }

      observed.take(expectedDcTokenCount).map(_.stage).forall(_ == TraceStage.DcTokens) mustBe true
      observed
        .slice(expectedDcTokenCount, expectedDcTokenCount + expectedStrategyCount)
        .zipWithIndex
        .foreach { case (trace, index) =>
          trace.stage mustBe TraceStage.AcStrategy
          trace.index mustBe index
        }
      val metadataStart = expectedDcTokenCount + expectedStrategyCount
      val acStart = metadataStart + expectedMetadataCount
      val metadataTraces = observed.slice(metadataStart, acStart)
      metadataTraces.map(_.stage).forall(_ == TraceStage.AcMetadataTokens) mustBe true
      metadataTraces.length mustBe 31
      metadataTraces.take(4).map(_.index) mustBe Seq(2, 2, 1, 1)
      val acTraces = observed.drop(acStart)
      acTraces.map(_.stage).forall(_ == TraceStage.AcTokens) mustBe true
      acTraces.map(_.group) mustBe (0 until expectedAcTokenCount)
      traceLastValues.zipWithIndex.foreach { case (traceLast, ordinal) =>
        withClue(s"exact-capacity stream TLAST at output ordinal $ordinal") {
          traceLast mustBe (ordinal == expectedTraceCount - 1)
        }
      }

      dut.io.trace.valid.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "HjxlPreparedDctAxiStreamCore stream traces assemble to libjxl-tiny codestream bytes" in {
    requireReferenceTools()

    val temp = Files.createTempDirectory("hjxl-prepared-dct-stream-codestream-")
    val preparedJson = temp.resolve("prepared-blocks.json")
    val inputStreamCsv = temp.resolve("prepared-input-stream.csv")
    val outputStreamCsv = temp.resolve("rtl-token-stream.csv")
    val directFrame = temp.resolve("direct-frame.bin")
    val directCodestream = temp.resolve("direct.jxl")
    val assembledFrame = temp.resolve("assembled-frame.bin")
    val assembledCodestream = temp.resolve("assembled.jxl")

    expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_reference.py",
        "--width",
        width.toString,
        "--height",
        height.toString,
        "--pattern",
        pattern,
        "--dct-only-prepared-blocks-json",
        preparedJson.toString,
        "--dct-only-frame-bin",
        directFrame.toString,
        "--dct-only-codestream-bin",
        directCodestream.toString
      ),
      "LIBJXL_TINY" -> libjxlTinyRoot.toString,
      "PYTHONDONTWRITEBYTECODE" -> "1"
    )

    expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--prepared-json",
        preparedJson.toString,
        "--input-stream-csv",
        inputStreamCsv.toString
      ),
      "PYTHONDONTWRITEBYTECODE" -> "1"
    )

    val inputRows = readStreamCsv(inputStreamCsv)
    inputRows.length mustBe 2 * PreparedDctStreamLayout.WordsPerBlock
    inputRows.dropRight(1).exists(_.last) mustBe false
    inputRows.last.last mustBe true

    val outputRows = scala.collection.mutable.ArrayBuffer.empty[StreamWord]
    simulate(new HjxlPreparedDctAxiStreamCore(config)) { dut =>
      pokeConfig(dut)
      dut.io.input.valid.poke(false.B)
      dut.io.input.bits.data.poke(0.U)
      dut.io.input.bits.last.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for ((row, ordinal) <- inputRows.zipWithIndex) {
        driveStreamWord(dut, row.data, row.last, s"oracle prepared stream input word $ordinal")
      }
      dut.io.input.valid.poke(false.B)
      dut.io.protocolError.expect(false.B)

      dut.io.trace.ready.poke(true.B)
      var cycles = 0
      var sawLast = false
      while (!sawLast && cycles < 8192) {
        if (dut.io.trace.valid.peekValue().asBigInt != 0) {
          val data = dut.io.trace.bits.data.peekValue().asBigInt
          val last = dut.io.trace.bits.last.peekValue().asBigInt != 0
          outputRows += StreamWord(data, last)
          sawLast = last
        }
        dut.clock.step()
        cycles += 1
      }
      withClue("prepared-DCT token stream did not terminate with TLAST") {
        sawLast mustBe true
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
    outputRows.nonEmpty mustBe true
    outputRows.dropRight(1).exists(_.last) mustBe false
    outputRows.last.last mustBe true
    writeStreamCsv(outputStreamCsv, outputRows.toSeq)

    expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_trace_to_codestream.py",
        "--stream-csv",
        outputStreamCsv.toString,
        "--require-stream-final-last",
        "--width",
        width.toString,
        "--height",
        height.toString,
        "--frame-bin",
        assembledFrame.toString,
        "--codestream-bin",
        assembledCodestream.toString,
        "--expect-frame-bin",
        directFrame.toString,
        "--expect-codestream-bin",
        directCodestream.toString
      ),
      "LIBJXL_TINY" -> libjxlTinyRoot.toString,
      "PYTHONDONTWRITEBYTECODE" -> "1"
    )

    Files.readAllBytes(assembledFrame).toSeq mustBe Files.readAllBytes(directFrame).toSeq
    Files.readAllBytes(assembledCodestream).toSeq mustBe Files.readAllBytes(directCodestream).toSeq
  }

  "HjxlPreparedDctAxiStreamCore reports and clears prepared input TLAST mismatches" in {
    simulate(new HjxlPreparedDctAxiStreamCore(config)) { dut =>
      pokeConfig(dut)
      dut.io.input.valid.poke(false.B)
      dut.io.input.bits.data.poke(0.U)
      dut.io.input.bits.last.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      dut.io.input.valid.poke(true.B)
      dut.io.input.bits.data.poke(1.U)
      dut.io.input.bits.last.poke(true.B)
      dut.io.input.ready.expect(true.B)
      dut.clock.step()
      dut.io.input.valid.poke(false.B)
      dut.io.protocolError.expect(true.B)

      dut.io.clearProtocolError.poke(true.B)
      dut.clock.step()
      dut.io.clearProtocolError.poke(false.B)
      dut.io.protocolError.expect(false.B)
    }
  }

  "HjxlPreparedDctAxiStreamCore emits the prepared stream shell" in {
    val targetDir = Files.createTempDirectory("hjxl-prepared-dct-stream-elaborate-")
    ChiselStage.emitSystemVerilogFile(
      new HjxlPreparedDctAxiStreamCore(config),
      args = Array("--target-dir", targetDir.toString),
      firtoolOpts = Array(
        "-disable-all-randomization",
        "-strip-debug-info",
        "-default-layer-specialization=enable"
      )
    )
    val stream = Files.walk(targetDir)
    try {
      val text = stream
        .iterator()
        .asScala
        .filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".sv"))
        .map(path => Files.readString(path, StandardCharsets.UTF_8))
        .mkString("\n")
      text must include("module HjxlPreparedDctAxiStreamCore")
      for (
        port <- Seq(
          "io_config_xsize",
          "io_clearProtocolError",
          "io_input_ready",
          "io_input_valid",
          "io_input_bits_data",
          "io_input_bits_last",
          "io_trace_ready",
          "io_trace_valid",
          "io_trace_bits_data",
          "io_trace_bits_last",
          "io_busy",
          "io_overflow",
          "io_protocolError"
        )
      ) {
        withClue(s"missing generated prepared stream port $port") {
          text must include(port)
        }
      }
    } finally {
      stream.close()
    }
  }
}
