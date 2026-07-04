// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.sys.process.Process
import scala.sys.process.ProcessLogger

class FramePreparedDctOnlyQuantizeTraceStageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 8)
  private val width = 16
  private val height = 8
  private val pattern = "gradient"
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim
  private val recordsPerBlock = 3 * blockSize + 3 + 3
  private val libjxlTinyRoot =
    Path.of(sys.env.getOrElse("LIBJXL_TINY", "/Users/yunhocho/GitHub/libjxl-tiny"))

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
  private case class PreparedAcBlock(numNonzeros: Seq[Int], quantized: Seq[Seq[Int]])

  private def requireReferenceTools(): Unit = {
    assume(
      Files.isDirectory(libjxlTinyRoot.resolve("python")),
      s"libjxl-tiny Python checkout not found at $libjxlTinyRoot"
    )
    assume(
      Process(Seq("python3", "-c", "import numpy")).! == 0,
      "python3 with numpy is required for prepared-block fixtures"
    )
  }

  private def runTool(command: Seq[String], extraEnv: (String, String)*): Unit = {
    val output = scala.collection.mutable.ArrayBuffer.empty[String]
    val logger = ProcessLogger(line => output += line, line => output += line)
    val exitCode = Process(command, TestPaths.repoRoot.toFile, extraEnv: _*).!(logger)
    withClue(output.mkString("\n")) {
      exitCode mustBe 0
    }
  }

  private def parseInts(value: String): Seq[Int] =
    value.split(" ").filter(_.nonEmpty).map(_.toInt).toVector

  private def readInputCsv(path: Path): Seq[PreparedBlock] =
    Files.readAllLines(path, StandardCharsets.UTF_8).toArray(new Array[String](0)).drop(1).toSeq.map { line =>
      val columns = line.split(",", -1)
      val invDcFactor = parseInts(columns(4))
      val coefficients = Seq(parseInts(columns(8)), parseInts(columns(9)), parseInts(columns(10)))
      invDcFactor.length mustBe 3
      coefficients.foreach(_.length mustBe blockSize)
      PreparedBlock(
        quant = columns(1).toInt,
        scaleQ16 = columns(2).toInt,
        invQacQ16 = columns(3).toInt,
        invDcFactorQ16 = invDcFactor,
        xQmMultiplierQ16 = columns(5).toInt,
        ytox = columns(6).toInt,
        ytob = columns(7).toInt,
        coefficients = coefficients
      )
    }

  private def readExpectedTraceCsv(path: Path): Seq[ExpectedTrace] =
    Files.readAllLines(path, StandardCharsets.UTF_8).toArray(new Array[String](0)).drop(1).toSeq.map { line =>
      val columns = line.split(",", -1)
      ExpectedTrace(
        stage = columns(0).toInt,
        group = columns(1).toInt,
        index = columns(2).toInt,
        value = columns(3).toInt
      )
    }

  private def convertTokenNpyToCsv(npyPath: Path, csvPath: Path): Unit = {
    runTool(
      Seq(
        "python3",
        "-c",
        "import csv, sys, numpy as np\n" +
          "tokens = np.load(sys.argv[1])\n" +
          "with open(sys.argv[2], 'w', newline='') as handle:\n" +
          "    writer = csv.writer(handle)\n" +
          "    writer.writerow(['context', 'value'])\n" +
          "    for context, value in tokens:\n" +
          "        writer.writerow([int(context), int(value)])\n",
        npyPath.toString,
        csvPath.toString
      )
    )
  }

  private def readTokenCsvAsTrace(path: Path, stage: Int): Seq[ExpectedTrace] =
    Files.readAllLines(path, StandardCharsets.UTF_8).toArray(new Array[String](0)).drop(1).toSeq.zipWithIndex.map {
      case (line, group) =>
        val columns = line.split(",", -1)
        ExpectedTrace(stage, group, columns(0).toInt, columns(1).toInt)
    }

  private def pokeConfig(dut: FramePreparedDctOnlyQuantizeTraceStage): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(0.U)
    dut.io.config.fixedInvQacQ16.poke(0.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.enableXyb.poke(false.B)
    dut.io.config.enableDct.poke(true.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(false.B)
    dut.io.config.tokenSelect.poke(0.U)
  }

  private def driveInput(
      dut: FramePreparedDctOnlyQuantizeTraceStage,
      block: PreparedBlock
  ): Unit = {
    dut.io.input.valid.poke(true.B)
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

    var cycles = 0
    while (dut.io.input.ready.peekValue().asBigInt == 0 && cycles < 256) {
      dut.clock.step()
      cycles += 1
    }
    cycles must be < 256
    dut.clock.step()
    dut.io.input.valid.poke(false.B)
  }

  private def expectTraceRows(
      dut: FramePreparedDctOnlyQuantizeTraceStage,
      rows: Seq[ExpectedTrace],
      baseOrdinal: Int,
      observedRows: scala.collection.mutable.ArrayBuffer[ExpectedTrace]
  ): Unit = {
    for ((trace, localOrdinal) <- rows.zipWithIndex) {
      var cycles = 0
      while (dut.io.trace.valid.peekValue().asBigInt == 0 && cycles < 256) {
        dut.clock.step()
        cycles += 1
      }
      val observedStage = dut.io.trace.bits.stage.peekValue().asBigInt.toInt
      val observedGroup = dut.io.trace.bits.group.peekValue().asBigInt.toInt
      val observedIndex = dut.io.trace.bits.index.peekValue().asBigInt.toInt
      val observedValue = dut.io.trace.bits.value.peekValue().asBigInt.toInt
      observedRows += ExpectedTrace(observedStage, observedGroup, observedIndex, observedValue)
      withClue(s"trace ordinal ${baseOrdinal + localOrdinal}") {
        cycles must be < 256
        observedStage mustBe trace.stage
        observedGroup mustBe trace.group
        observedIndex mustBe trace.index
        withClue(s" value observed=$observedValue expected=${trace.value}") {
          observedValue mustBe trace.value
        }
      }
      dut.clock.step()
    }
  }

  private def writeTraceCsv(path: Path, rows: Seq[ExpectedTrace]): Unit = {
    val body = rows.map { row =>
      s"${row.stage},${row.group},${row.index},${row.value}"
    }
    Files.writeString(
      path,
      ("stage,group,index,value" +: body).mkString("", "\n", "\n"),
      StandardCharsets.UTF_8,
    )
  }

  private def readDcCsv(path: Path): Seq[Int] =
    Files.readAllLines(path, StandardCharsets.UTF_8).toArray(new Array[String](0)).drop(1).toSeq.map { line =>
      line.split(",", -1)(1).toInt
    }

  private case class AcCsvRow(block: Int, channel: Int, nonzeros: Int, coefficients: Seq[Int])

  private def readAcCsv(path: Path): Seq[AcCsvRow] =
    Files.readAllLines(path, StandardCharsets.UTF_8).toArray(new Array[String](0)).drop(1).toSeq.map { line =>
      val columns = line.split(",", -1)
      AcCsvRow(
        block = columns(0).toInt,
        channel = columns(1).toInt,
        nonzeros = columns(2).toInt,
        coefficients = parseInts(columns(3))
      )
    }

  private def readPreparedAcBlocks(path: Path): Seq[PreparedAcBlock] = {
    readAcCsv(path)
      .groupBy(_.block)
      .toSeq
      .sortBy(_._1)
      .map { case (_, rows) =>
        val byChannel = rows.sortBy(_.channel)
        byChannel.map(_.channel) mustBe Seq(0, 1, 2)
        PreparedAcBlock(
          numNonzeros = byChannel.map(_.nonzeros),
          quantized = byChannel.map(_.coefficients)
        )
      }
  }

  private def byTraceKey(rows: Seq[ExpectedTrace]): Map[(Int, Int, Int), Int] =
    rows.map(row => (row.stage, row.group, row.index) -> row.value).toMap

  private def pokeTokenConfig(dut: FramePreparedTokenTraceStage): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(QuantizeDct8x8Block.DefaultScaleQ16.U)
    dut.io.config.fixedInvQacQ16.poke(QuantizeDct8x8Block.DefaultInvQacQ16.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.enableXyb.poke(false.B)
    dut.io.config.enableDct.poke(false.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(true.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.AcTokens.U)
  }

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

  private def acTokenCount(acBlocks: Seq[PreparedAcBlock]): Int = {
    val channelOrder = Seq(1, 0, 2)
    acBlocks.map { block =>
      channelOrder.map { channel =>
        1 + acCoefficientTokenCount(block.quantized(channel), block.numNonzeros(channel))
      }.sum
    }.sum
  }

  private def packSigned(value: Int): Int =
    if (value < 0) (-value << 1) - 1 else value << 1

  private def strategyContext(left: Int): Int =
    if (left > 11) 7 else if (left > 5) 8 else if (left > 3) 9 else 10

  private def quantFieldContext(left: Int): Int =
    if (left > 11) 3 else if (left > 5) 4 else if (left > 3) 5 else 6

  private def expectedPreparedMetadata(blocks: Seq[PreparedBlock]): Seq[ExpectedTrace] = {
    val pairs = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
    val first = blocks.head
    pairs += 2 -> packSigned(first.ytox)
    pairs += 1 -> packSigned(first.ytob)

    var strategyLeft = 0
    for (_ <- blocks) {
      val current = Tokenize.DctStrategyCode
      pairs += strategyContext(strategyLeft) -> packSigned(current)
      strategyLeft = current
    }

    var quantLeft = Tokenize.DctStrategyCode
    for (block <- blocks) {
      val current = block.quant - 1
      pairs += quantFieldContext(quantLeft) -> packSigned(current - quantLeft)
      quantLeft = current
    }

    for (_ <- blocks) {
      pairs += 0 -> packSigned(Tokenize.DefaultBlockMetadata)
    }

    pairs.zipWithIndex.map { case ((context, value), group) =>
      ExpectedTrace(TraceStage.AcMetadataTokens, group, context, value)
    }.toSeq
  }

  private def metadataTokenCount: Int = {
    val xBlocks = (width + HjxlConstants.BlockDim - 1) / HjxlConstants.BlockDim
    val yBlocks = (height + HjxlConstants.BlockDim - 1) / HjxlConstants.BlockDim
    val xTiles = (width + HjxlConstants.TileDim - 1) / HjxlConstants.TileDim
    val yTiles = (height + HjxlConstants.TileDim - 1) / HjxlConstants.TileDim
    xTiles * yTiles * 2 + xBlocks * yBlocks * 3
  }

  private def collectTokenTraces(
      dcSamples: Seq[Int],
      acBlocks: Seq[PreparedAcBlock]
  ): Seq[ExpectedTrace] = {
    val traces = scala.collection.mutable.ArrayBuffer.empty[ExpectedTrace]
    simulate(new FramePreparedTokenTraceStage(config)) { dut =>
      pokeTokenConfig(dut)
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

      for (block <- acBlocks) {
        dut.io.acInput.valid.poke(true.B)
        for (channel <- 0 until 3) {
          dut.io.acInput.bits.numNonzeros(channel).poke(block.numNonzeros(channel).U)
          for (i <- 0 until blockSize) {
            dut.io.acInput.bits.quantized(channel)(i).poke(block.quantized(channel)(i).S)
          }
        }
        dut.io.acInput.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.acInput.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)

      val expectedTraceCount =
        dcSamples.length + acBlocks.length + metadataTokenCount + acTokenCount(acBlocks)
      for (ordinal <- 0 until expectedTraceCount) {
        var cycles = 0
        while (dut.io.trace.valid.peekValue().asBigInt == 0 && cycles < 256) {
          dut.clock.step()
          cycles += 1
        }
        withClue(s"prepared-token trace ordinal $ordinal") {
          cycles must be < 256
        }
        traces += ExpectedTrace(
          stage = dut.io.trace.bits.stage.peekValue().asBigInt.toInt,
          group = dut.io.trace.bits.group.peekValue().asBigInt.toInt,
          index = dut.io.trace.bits.index.peekValue().asBigInt.toInt,
          value = dut.io.trace.bits.value.peekValue().asBigInt.toInt
        )
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
    traces.toSeq
  }

  private def pokeCombinedConfig(dut: FramePreparedDctOnlyQuantizeTokenTraceStage): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(QuantizeDct8x8Block.DefaultScaleQ16.U)
    dut.io.config.fixedInvQacQ16.poke(QuantizeDct8x8Block.DefaultInvQacQ16.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.enableXyb.poke(false.B)
    dut.io.config.enableDct.poke(true.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(true.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.AcTokens.U)
  }

  private def driveCombinedInput(
      dut: FramePreparedDctOnlyQuantizeTokenTraceStage,
      block: PreparedBlock
  ): Unit = {
    dut.io.input.valid.poke(true.B)
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
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
    dut.io.input.valid.poke(false.B)
  }

  private def zeroPreparedBlock(quant: Int = 1): PreparedBlock =
    PreparedBlock(
      quant = quant,
      scaleQ16 = QuantizeDct8x8Block.DefaultScaleQ16,
      invQacQ16 = QuantizeDct8x8Block.DefaultInvQacQ16,
      invDcFactorQ16 = QuantizeDct8x8Block.DefaultInvDcFactorQ16,
      xQmMultiplierQ16 = QuantizeDct8x8Block.DefaultQmMultiplierQ16,
      ytox = 0,
      ytob = 0,
      coefficients = Seq.fill(3)(Seq.fill(blockSize)(0))
    )

  private def pokeCombinedInputBits(
      dut: FramePreparedDctOnlyQuantizeTokenTraceStage,
      block: PreparedBlock
  ): Unit = {
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

  private def peekCombinedTrace(dut: FramePreparedDctOnlyQuantizeTokenTraceStage): ExpectedTrace =
    ExpectedTrace(
      stage = dut.io.trace.bits.stage.peekValue().asBigInt.toInt,
      group = dut.io.trace.bits.group.peekValue().asBigInt.toInt,
      index = dut.io.trace.bits.index.peekValue().asBigInt.toInt,
      value = dut.io.trace.bits.value.peekValue().asBigInt.toInt
    )

  private def waitForCombinedTrace(
      dut: FramePreparedDctOnlyQuantizeTokenTraceStage,
      clue: String,
      maxCycles: Int = 512
  ): Unit = {
    var cycles = 0
    while (dut.io.trace.valid.peekValue().asBigInt == 0 && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    withClue(clue) {
      cycles must be < maxCycles
    }
  }

  private def zeroCombinedTraceCount(blockCount: Int): Int =
    blockCount * 3 + blockCount + metadataTokenCount + blockCount * 3

  private def expectCombinedIdle(
      dut: FramePreparedDctOnlyQuantizeTokenTraceStage,
      clue: String
  ): Unit = {
    var cycles = 0
    while (dut.io.input.ready.peekValue().asBigInt == 0 && cycles < 32) {
      withClue(s"$clue idle wait cycle $cycles") {
        dut.io.trace.valid.expect(false.B)
      }
      dut.clock.step()
      cycles += 1
    }
    withClue(clue) {
      cycles must be < 32
    }
    dut.io.trace.valid.expect(false.B)
    dut.io.input.ready.expect(true.B)
    dut.io.busy.expect(false.B)
    dut.io.overflow.expect(false.B)
  }

  private def collectCombinedTokenTraces(
      blocks: Seq[PreparedBlock],
      expectedTraceCount: Int
  ): Seq[ExpectedTrace] = {
    val traces = scala.collection.mutable.ArrayBuffer.empty[ExpectedTrace]
    simulate(new FramePreparedDctOnlyQuantizeTokenTraceStage(config)) { dut =>
      pokeCombinedConfig(dut)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for (block <- blocks) {
        driveCombinedInput(dut, block)
      }
      dut.io.trace.ready.poke(true.B)

      for (ordinal <- 0 until expectedTraceCount) {
        waitForCombinedTrace(dut, s"combined token trace ordinal $ordinal")
        dut.io.traceLast.expect((ordinal == expectedTraceCount - 1).B)
        traces += peekCombinedTrace(dut)
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
    traces.toSeq
  }

  "FramePreparedDctOnlyQuantizeTraceStage emits libjxl-tiny prepared-block quantization traces" in {
    requireReferenceTools()

    val temp = Files.createTempDirectory("hjxl-prepared-quantize-")
    val preparedJson = temp.resolve("prepared-blocks.json")
    val inputCsv = temp.resolve("prepared-blocks.csv")
    val expectedCsv = temp.resolve("expected-trace.csv")
    val acMetadataNpy = temp.resolve("ac-metadata.npy")
    val acMetadataCsv = temp.resolve("ac-metadata.csv")
    val oracleDcTokens = temp.resolve("oracle-dc.npy")
    val oracleAcTokens = temp.resolve("oracle-ac.npy")
    val oracleAcStrategy = temp.resolve("oracle-ac-strategy.npy")
    val directDctOnlyFrame = temp.resolve("direct-dct-only-frame.bin")
    val directDctOnlyCodestream = temp.resolve("direct-dct-only.jxl")
    val oracleTokenFrame = temp.resolve("oracle-token-frame.bin")
    val oracleTokenCodestream = temp.resolve("oracle-token.jxl")
    val observedTraceCsv = temp.resolve("observed-quant-trace.csv")
    val preparedDcCsv = temp.resolve("prepared-dc.csv")
    val preparedAcCsv = temp.resolve("prepared-ac.csv")
    val combinedTraceCsv = temp.resolve("combined-token-trace.csv")
    val dcTokens = temp.resolve("combined-dc.npy")
    val acMetadataTokens = temp.resolve("combined-acmeta.npy")
    val acTokens = temp.resolve("combined-ac.npy")
    val acStrategy = temp.resolve("combined-ac-strategy.npy")
    val tokenFrame = temp.resolve("combined-frame.bin")
    val tokenCodestream = temp.resolve("combined.jxl")
    val traceAssemblerFrame = temp.resolve("combined-trace-assembler-frame.bin")
    val traceAssemblerCodestream = temp.resolve("combined-trace-assembler.jxl")

    runTool(
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
        "--dct-only-ac-metadata-tokens-npy",
        acMetadataNpy.toString,
        "--dct-only-dc-tokens-npy",
        oracleDcTokens.toString,
        "--dct-only-ac-tokens-npy",
        oracleAcTokens.toString,
        "--default-ac-strategy-npy",
        oracleAcStrategy.toString,
        "--dct-only-frame-bin",
        directDctOnlyFrame.toString,
        "--dct-only-codestream-bin",
        directDctOnlyCodestream.toString,
        "--token-input-dc-tokens-npy",
        oracleDcTokens.toString,
        "--token-input-ac-metadata-tokens-npy",
        acMetadataNpy.toString,
        "--token-input-ac-tokens-npy",
        oracleAcTokens.toString,
        "--token-input-ac-strategy-npy",
        oracleAcStrategy.toString,
        "--token-input-frame-bin",
        oracleTokenFrame.toString,
        "--token-input-codestream-bin",
        oracleTokenCodestream.toString
      ),
      "LIBJXL_TINY" -> libjxlTinyRoot.toString
    )
    Files.readAllBytes(oracleTokenFrame).toSeq mustBe Files.readAllBytes(directDctOnlyFrame).toSeq
    Files.readAllBytes(oracleTokenCodestream).toSeq mustBe Files.readAllBytes(directDctOnlyCodestream).toSeq
    convertTokenNpyToCsv(acMetadataNpy, acMetadataCsv)
    runTool(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--prepared-json",
        preparedJson.toString,
        "--input-csv",
        inputCsv.toString,
        "--expected-trace-csv",
        expectedCsv.toString
      )
    )

    val blocks = readInputCsv(inputCsv)
    val expected = readExpectedTraceCsv(expectedCsv)
    val expectedAcMetadata = readTokenCsvAsTrace(acMetadataCsv, TraceStage.AcMetadataTokens)
    val observed = scala.collection.mutable.ArrayBuffer.empty[ExpectedTrace]
    blocks.length mustBe 2
    expected.count(_.stage == TraceStage.QuantizedAc) mustBe blocks.length * 3 * blockSize
    expected.count(_.stage == TraceStage.NumNonzeros) mustBe blocks.length * 3

    simulate(new FramePreparedDctOnlyQuantizeTraceStage(config)) { dut =>
      pokeConfig(dut)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      for (((block, rows), blockIndex) <- blocks.zip(expected.grouped(recordsPerBlock)).zipWithIndex) {
        driveInput(dut, block)
        expectTraceRows(dut, rows, blockIndex * recordsPerBlock, observed)
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.input.ready.expect(true.B)
      dut.io.overflow.expect(false.B)
    }

    writeTraceCsv(observedTraceCsv, observed.toSeq)
    runTool(
      Seq(
        "python3",
        "tools/hjxl_quant_trace_to_prepared_tokens.py",
        "--trace-csv",
        observedTraceCsv.toString,
        "--width",
        width.toString,
        "--height",
        height.toString,
        "--dc-csv",
        preparedDcCsv.toString,
        "--ac-csv",
        preparedAcCsv.toString
      )
    )

    val observedByKey = byTraceKey(observed.toSeq)
    readDcCsv(preparedDcCsv) mustBe Seq(1, 0, 2).flatMap { channel =>
      blocks.indices.map { block =>
        observedByKey((TraceStage.QuantDc, block, channel))
      }
    }

    val acRows = readAcCsv(preparedAcCsv)
    acRows.length mustBe blocks.length * 3
    for (row <- acRows) {
      row.coefficients.length mustBe blockSize
      row.nonzeros mustBe observedByKey((TraceStage.NumNonzeros, row.block, row.channel))
      for (coefficient <- 0 until blockSize) {
        row.coefficients(coefficient) mustBe
          observedByKey((TraceStage.QuantizedAc, row.block, row.channel * blockSize + coefficient))
      }
    }

    val preparedDc = readDcCsv(preparedDcCsv)
    val preparedAc = readPreparedAcBlocks(preparedAcCsv)
    preparedDc.length mustBe blocks.length * 3
    preparedAc.length mustBe blocks.length
    preparedAc.map(_.numNonzeros.sum).sum must be > 0

    val tokenTraces = collectTokenTraces(preparedDc, preparedAc)
    tokenTraces.count(_.stage == TraceStage.DcTokens) mustBe preparedDc.length
    tokenTraces.count(_.stage == TraceStage.AcStrategy) mustBe blocks.length
    tokenTraces.count(_.stage == TraceStage.AcMetadataTokens) mustBe metadataTokenCount
    tokenTraces.count(_.stage == TraceStage.AcTokens) mustBe acTokenCount(preparedAc)
    tokenTraces.filter(_.stage == TraceStage.DcTokens).map(_.group) mustBe preparedDc.indices
    tokenTraces.filter(_.stage == TraceStage.AcTokens).map(_.group) mustBe
      (0 until acTokenCount(preparedAc))

    val combinedTokenTraces = collectCombinedTokenTraces(blocks, tokenTraces.length)
    combinedTokenTraces.filter(_.stage == TraceStage.DcTokens) mustBe
      tokenTraces.filter(_.stage == TraceStage.DcTokens)
    combinedTokenTraces.filter(_.stage == TraceStage.AcStrategy) mustBe
      tokenTraces.filter(_.stage == TraceStage.AcStrategy)
    combinedTokenTraces.filter(_.stage == TraceStage.AcTokens) mustBe
      tokenTraces.filter(_.stage == TraceStage.AcTokens)
    combinedTokenTraces.filter(_.stage == TraceStage.AcMetadataTokens) mustBe
      expectedAcMetadata
    expectedPreparedMetadata(blocks) mustBe expectedAcMetadata

    writeTraceCsv(combinedTraceCsv, combinedTokenTraces)
    runTool(
      Seq(
        "python3",
        "tools/hjxl_trace_tokens.py",
        "--trace-csv",
        combinedTraceCsv.toString,
        "--width",
        width.toString,
        "--height",
        height.toString,
        "--dc-tokens-npy",
        dcTokens.toString,
        "--ac-metadata-tokens-npy",
        acMetadataTokens.toString,
        "--ac-tokens-npy",
        acTokens.toString,
        "--ac-strategy-npy",
        acStrategy.toString
      )
    )
    runTool(
      Seq(
        "python3",
        "tools/hjxl_compare_tokens.py",
        "--expected-dc-tokens-npy",
        oracleDcTokens.toString,
        "--actual-dc-tokens-npy",
        dcTokens.toString,
        "--expected-ac-metadata-tokens-npy",
        acMetadataNpy.toString,
        "--actual-ac-metadata-tokens-npy",
        acMetadataTokens.toString,
        "--expected-ac-tokens-npy",
        oracleAcTokens.toString,
        "--actual-ac-tokens-npy",
        acTokens.toString,
        "--expected-ac-strategy-npy",
        oracleAcStrategy.toString,
        "--actual-ac-strategy-npy",
        acStrategy.toString
      )
    )
    runTool(
      Seq(
        "python3",
        "tools/hjxl_reference.py",
        "--width",
        width.toString,
        "--height",
        height.toString,
        "--pattern",
        pattern,
        "--token-input-dc-tokens-npy",
        dcTokens.toString,
        "--token-input-ac-metadata-tokens-npy",
        acMetadataTokens.toString,
        "--token-input-ac-tokens-npy",
        acTokens.toString,
        "--token-input-ac-strategy-npy",
        acStrategy.toString,
        "--token-input-frame-bin",
        tokenFrame.toString,
        "--token-input-codestream-bin",
        tokenCodestream.toString
      ),
      "LIBJXL_TINY" -> libjxlTinyRoot.toString
    )
    val frameBytes = Files.readAllBytes(tokenFrame).toSeq
    val codestreamBytes = Files.readAllBytes(tokenCodestream).toSeq
    frameBytes mustBe Files.readAllBytes(directDctOnlyFrame).toSeq
    codestreamBytes mustBe Files.readAllBytes(directDctOnlyCodestream).toSeq
    codestreamBytes.take(2) mustBe Seq(0xff.toByte, 0x0a.toByte)

    runTool(
      Seq(
        "python3",
        "tools/hjxl_trace_to_codestream.py",
        "--trace-csv",
        combinedTraceCsv.toString,
        "--width",
        width.toString,
        "--height",
        height.toString,
        "--frame-bin",
        traceAssemblerFrame.toString,
        "--codestream-bin",
        traceAssemblerCodestream.toString
      ),
      "LIBJXL_TINY" -> libjxlTinyRoot.toString
    )
    Files.readAllBytes(traceAssemblerFrame).toSeq mustBe Files.readAllBytes(directDctOnlyFrame).toSeq
    Files.readAllBytes(traceAssemblerCodestream).toSeq mustBe Files.readAllBytes(directDctOnlyCodestream).toSeq
  }

  "FramePreparedDctOnlyQuantizeTokenTraceStage holds trace bits under output backpressure" in {
    val blocks = Seq(zeroPreparedBlock(), zeroPreparedBlock())
    val expectedTraceCount = zeroCombinedTraceCount(blocks.length)

    simulate(new FramePreparedDctOnlyQuantizeTokenTraceStage(config)) { dut =>
      pokeCombinedConfig(dut)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      blocks.foreach(driveCombinedInput(dut, _))
      dut.io.input.ready.expect(false.B)

      waitForCombinedTrace(dut, "first combined token trace under backpressure")
      val firstTrace = peekCombinedTrace(dut)
      for (cycle <- 0 until 5) {
        withClue(s"backpressured cycle $cycle") {
          dut.io.trace.valid.expect(true.B)
          peekCombinedTrace(dut) mustBe firstTrace
          dut.io.traceLast.expect(false.B)
        }
        dut.clock.step()
      }

      dut.io.trace.ready.poke(true.B)
      for (ordinal <- 0 until expectedTraceCount) {
        waitForCombinedTrace(dut, s"combined token trace drain ordinal $ordinal")
        dut.io.traceLast.expect((ordinal == expectedTraceCount - 1).B)
        dut.clock.step()
      }

      expectCombinedIdle(dut, "backpressured combined token wrapper")
    }
  }

  "FramePreparedDctOnlyQuantizeTokenTraceStage rejects a new frame until buffered token emission drains" in {
    val blocks = Seq(zeroPreparedBlock(), zeroPreparedBlock())
    val pendingBlock = zeroPreparedBlock(quant = 2)
    val expectedTraceCount = zeroCombinedTraceCount(blocks.length)

    simulate(new FramePreparedDctOnlyQuantizeTokenTraceStage(config)) { dut =>
      pokeCombinedConfig(dut)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      driveCombinedInput(dut, blocks.head)
      dut.io.input.ready.expect(true.B)
      driveCombinedInput(dut, blocks(1))
      dut.io.input.ready.expect(false.B)
      dut.io.busy.expect(true.B)

      dut.io.input.valid.poke(true.B)
      pokeCombinedInputBits(dut, pendingBlock)
      for (cycle <- 0 until 4) {
        withClue(s"extra input cycle $cycle") {
          dut.io.input.ready.expect(false.B)
        }
        dut.clock.step()
      }
      dut.io.input.valid.poke(false.B)

      dut.io.trace.ready.poke(true.B)
      for (ordinal <- 0 until expectedTraceCount) {
        waitForCombinedTrace(dut, s"combined token trace lifecycle ordinal $ordinal")
        dut.io.traceLast.expect((ordinal == expectedTraceCount - 1).B)
        dut.clock.step()
      }

      expectCombinedIdle(dut, "combined token wrapper after rejected extra input")
    }
  }
}
