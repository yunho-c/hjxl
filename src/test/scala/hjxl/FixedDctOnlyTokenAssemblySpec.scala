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

class FixedDctOnlyTokenAssemblySpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 8)
  private val width = 16
  private val height = 8
  private val pattern = "gradient"
  private val libjxlTinyRoot =
    Path.of(sys.env.getOrElse("LIBJXL_TINY", "/Users/yunhocho/GitHub/libjxl-tiny"))

  private case class PreparedAcBlock(numNonzeros: Seq[Int], quantized: Seq[Seq[Int]])
  private case class CollectedTrace(stage: Int, group: Int, index: Int, value: Int)
  private def requireReferenceTools(): Unit = {
    assume(
      Files.isDirectory(libjxlTinyRoot.resolve("python")),
      s"libjxl-tiny Python checkout not found at $libjxlTinyRoot"
    )
    assume(
      Process(Seq("python3", "-c", "import numpy")).! == 0,
      "python3 with numpy is required for token-input bitstream assembly"
    )
  }

  private def pokeConfig(dut: FramePreparedTokenTraceStage): Unit = {
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
    while (remaining != 0 && scanIndex < HjxlConstants.BlockDim * HjxlConstants.BlockDim) {
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

  private def metadataTokenCount(width: Int, height: Int): Int = {
    val blockDim = HjxlConstants.BlockDim
    val tileDim = HjxlConstants.TileDim
    val xBlocks = (width + blockDim - 1) / blockDim
    val yBlocks = (height + blockDim - 1) / blockDim
    val xTiles = (width + tileDim - 1) / tileDim
    val yTiles = (height + tileDim - 1) / tileDim
    xTiles * yTiles * 2 + xBlocks * yBlocks * 3
  }

  private def collectPreparedTokenTraces(
      dcSamples: Seq[Int],
      acBlocks: Seq[PreparedAcBlock]
  ): Seq[CollectedTrace] = {
    val traces = scala.collection.mutable.ArrayBuffer.empty[CollectedTrace]
    simulate(new FramePreparedTokenTraceStage(config)) { dut =>
      pokeConfig(dut)
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
          for (i <- 0 until HjxlConstants.BlockDim * HjxlConstants.BlockDim) {
            dut.io.acInput.bits.quantized(channel)(i).poke(block.quantized(channel)(i).S)
          }
        }
        dut.io.acInput.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.acInput.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)

      val expectedTraceCount = dcSamples.length + acBlocks.length + metadataTokenCount(width, height) +
        acTokenCount(acBlocks)
      for (_ <- 0 until expectedTraceCount) {
        var cycles = 0
        while (dut.io.trace.valid.peekValue().asBigInt == 0 && cycles < 16) {
          dut.clock.step()
          cycles += 1
        }
        cycles must be < 16
        traces += CollectedTrace(
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

  private def runReferenceHelper(args: Seq[String]): Unit = {
    val output = scala.collection.mutable.ArrayBuffer.empty[String]
    val logger = ProcessLogger(line => output += line, line => output += line)
    val exitCode = Process(
      Seq("python3", "tools/hjxl_reference.py") ++ args,
      TestPaths.repoRoot.toFile,
      "LIBJXL_TINY" -> libjxlTinyRoot.toString
    ).!(logger)
    withClue(output.mkString("\n")) {
      exitCode mustBe 0
    }
  }

  private def runTraceConverter(args: Seq[String]): Unit = {
    val output = scala.collection.mutable.ArrayBuffer.empty[String]
    val logger = ProcessLogger(line => output += line, line => output += line)
    val exitCode = Process(
      Seq("python3", "tools/hjxl_trace_tokens.py") ++ args,
      TestPaths.repoRoot.toFile
    ).!(logger)
    withClue(output.mkString("\n")) {
      exitCode mustBe 0
    }
  }

  private def runPreparedInputConverter(args: Seq[String]): Unit = {
    val output = scala.collection.mutable.ArrayBuffer.empty[String]
    val logger = ProcessLogger(line => output += line, line => output += line)
    val exitCode = Process(
      Seq("python3", "tools/hjxl_prepared_token_inputs.py") ++ args,
      TestPaths.repoRoot.toFile
    ).!(logger)
    withClue(output.mkString("\n")) {
      exitCode mustBe 0
    }
  }

  private def readPreparedDcCsv(path: Path): Seq[Int] =
    Files.readAllLines(path, StandardCharsets.UTF_8).toArray(new Array[String](0)).drop(1).toSeq.map { line =>
      line.split(",", -1)(1).toInt
    }

  private def readPreparedAcCsv(path: Path): Seq[PreparedAcBlock] = {
    val rows = Files.readAllLines(path, StandardCharsets.UTF_8).toArray(new Array[String](0)).drop(1).toSeq.map { line =>
      val columns = line.split(",", -1)
      val block = columns(0).toInt
      val channel = columns(1).toInt
      val nonzeros = columns(2).toInt
      val coefficients = columns(3).split(" ").filter(_.nonEmpty).map(_.toInt).toVector
      (block, channel, nonzeros, coefficients)
    }

    rows
      .groupBy(_._1)
      .toSeq
      .sortBy(_._1)
      .map { case (_, blockRows) =>
        val byChannel = blockRows.sortBy(_._2)
        PreparedAcBlock(
          numNonzeros = byChannel.map(_._3),
          quantized = byChannel.map(_._4)
        )
      }
  }

  private def writeTraceCsv(path: Path, traces: Seq[CollectedTrace]): Unit = {
    val rows = traces.map { trace =>
      s"${trace.stage},${trace.group},${trace.index},${trace.value}"
    }
    Files.writeString(
      path,
      ("stage,group,index,value" +: rows).mkString("", "\n", "\n"),
      StandardCharsets.UTF_8,
    )
  }

  "RTL logical tokens can be consumed by the host bitstream assembler" in {
    requireReferenceTools()

    val temp = Files.createTempDirectory("hjxl-token-assembly-")
    val preparedJson = temp.resolve("prepared-token-inputs.json")
    val preparedDcCsv = temp.resolve("prepared-dc.csv")
    val preparedAcCsv = temp.resolve("prepared-ac.csv")
    val traceCsv = temp.resolve("rtl-trace.csv")
    val dcTokens = temp.resolve("rtl-dc.npy")
    val acMetadataTokens = temp.resolve("rtl-acmeta.npy")
    val acTokens = temp.resolve("rtl-ac.npy")
    val acStrategy = temp.resolve("rtl-ac-strategy.npy")
    val tokenFrame = temp.resolve("token-frame.bin")
    val tokenCodestream = temp.resolve("token.jxl")
    val directFrame = temp.resolve("direct-frame.bin")
    val directCodestream = temp.resolve("direct.jxl")

    runReferenceHelper(
      Seq(
        "--width",
        width.toString,
        "--height",
        height.toString,
        "--pattern",
        pattern,
        "--fixed-dct-only-prepared-token-inputs-json",
        preparedJson.toString
      )
    )
    runPreparedInputConverter(
      Seq(
        "--prepared-json",
        preparedJson.toString,
        "--dc-csv",
        preparedDcCsv.toString,
        "--ac-csv",
        preparedAcCsv.toString
      )
    )

    val preparedDc = readPreparedDcCsv(preparedDcCsv)
    val preparedAc = readPreparedAcCsv(preparedAcCsv)
    preparedAc.map(_.numNonzeros.sum).sum must be > 0

    writeTraceCsv(
      traceCsv,
      collectPreparedTokenTraces(preparedDc, preparedAc),
    )

    runTraceConverter(
      Seq(
        "--trace-csv",
        traceCsv.toString,
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

    runReferenceHelper(
      Seq(
        "--width",
        width.toString,
        "--height",
        height.toString,
        "--pattern",
        pattern,
        "--fixed-dct-only-frame-bin",
        directFrame.toString,
        "--fixed-dct-only-codestream-bin",
        directCodestream.toString,
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
      )
    )

    Files.readAllBytes(tokenFrame).toSeq mustBe Files.readAllBytes(directFrame).toSeq
    Files.readAllBytes(tokenCodestream).toSeq mustBe Files.readAllBytes(directCodestream).toSeq
  }
}
