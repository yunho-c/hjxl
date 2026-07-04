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

class FramePreparedAcMetadataTokenTraceStageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 128, maxFrameHeight = 8)
  private val libjxlTinyRoot =
    Path.of(sys.env.getOrElse("LIBJXL_TINY", "/Users/yunhocho/GitHub/libjxl-tiny"))

  private case class MetadataBlock(rawQuant: Int, ytox: Int, ytob: Int)

  private def requireReferenceTools(): Unit = {
    assume(
      Files.isDirectory(libjxlTinyRoot.resolve("python")),
      s"libjxl-tiny Python checkout not found at $libjxlTinyRoot"
    )
    assume(
      Process(Seq("python3", "-c", "import numpy")).! == 0,
      "python3 with numpy is required for metadata fixtures"
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

  private def pokeConfig(dut: FramePreparedAcMetadataTokenTraceStage, width: Int, height: Int): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(0.U)
    dut.io.config.fixedInvQacQ16.poke(0.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.enableXyb.poke(false.B)
    dut.io.config.enableDct.poke(true.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(true.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.AcMetadata.U)
  }

  private def packSigned(value: Int): Int =
    if (value < 0) (-value << 1) - 1 else value << 1

  private def strategyContext(left: Int): Int =
    if (left > 11) 7 else if (left > 5) 8 else if (left > 3) 9 else 10

  private def quantFieldContext(left: Int): Int =
    if (left > 11) 3 else if (left > 5) 4 else if (left > 3) 5 else 6

  private def expectedTokens(blocks: Seq[MetadataBlock]): Seq[(Int, Int)] = {
    val ytoxByTile = Seq(blocks.head.ytox, blocks.last.ytox)
    val ytobByTile = Seq(blocks.head.ytob, blocks.last.ytob)
    val tokens = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]

    tokens += 2 -> packSigned(ytoxByTile(0))
    tokens += 2 -> packSigned(ytoxByTile(1) - ytoxByTile(0))
    tokens += 1 -> packSigned(ytobByTile(0))
    tokens += 1 -> packSigned(ytobByTile(1) - ytobByTile(0))

    var strategyLeft = Tokenize.DctStrategyCode
    for (_ <- blocks) {
      tokens += strategyContext(strategyLeft) -> packSigned(Tokenize.DctStrategyCode)
      strategyLeft = Tokenize.DctStrategyCode
    }

    var quantLeft = Tokenize.DctStrategyCode
    for (block <- blocks) {
      val current = block.rawQuant - 1
      tokens += quantFieldContext(quantLeft) -> packSigned(current - quantLeft)
      quantLeft = current
    }

    for (_ <- blocks) {
      tokens += 0 -> packSigned(Tokenize.DefaultBlockMetadata)
    }
    tokens.toSeq
  }

  private def readPreparedBlocksCsv(path: Path): Seq[MetadataBlock] =
    Files.readAllLines(path, StandardCharsets.UTF_8).toArray(new Array[String](0)).drop(1).toSeq.map { line =>
      val columns = line.split(",", -1)
      MetadataBlock(
        rawQuant = columns(1).toInt,
        ytox = columns(6).toInt,
        ytob = columns(7).toInt
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

  private def readTokenCsv(path: Path): Seq[(Int, Int)] =
    Files.readAllLines(path, StandardCharsets.UTF_8).toArray(new Array[String](0)).drop(1).toSeq.map { line =>
      val columns = line.split(",", -1)
      (columns(0).toInt, columns(1).toInt)
    }

  private def collectMetadataTokens(
      width: Int,
      height: Int,
      blocks: Seq[MetadataBlock]
  ): Seq[(Int, Int)] = {
    val tokens = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
    simulate(new FramePreparedAcMetadataTokenTraceStage(config)) { dut =>
      pokeConfig(dut, width, height)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for (block <- blocks) {
        dut.io.input.valid.poke(true.B)
        dut.io.input.bits.rawQuant.poke(block.rawQuant.U)
        dut.io.input.bits.ytox.poke(block.ytox.S)
        dut.io.input.bits.ytob.poke(block.ytob.S)
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)

      val expectedCount =
        2 * ((width + HjxlConstants.TileDim - 1) / HjxlConstants.TileDim) *
          ((height + HjxlConstants.TileDim - 1) / HjxlConstants.TileDim) +
          3 * blocks.length
      for (ordinal <- 0 until expectedCount) {
        withClue(s"metadata token $ordinal") {
          dut.io.trace.valid.expect(true.B)
          dut.io.trace.bits.stage.expect(TraceStage.AcMetadataTokens.U)
          dut.io.trace.bits.group.expect(ordinal.U)
        }
        tokens += ((
          dut.io.trace.bits.index.peekValue().asBigInt.toInt,
          dut.io.trace.bits.value.peekValue().asBigInt.toInt
        ))
        dut.clock.step()
      }

      dut.io.trace.valid.expect(false.B)
      dut.io.input.ready.expect(true.B)
      dut.io.overflow.expect(false.B)
    }
    tokens.toSeq
  }

  "FramePreparedAcMetadataTokenTraceStage emits prepared multi-tile metadata tokens" in {
    val width = 72
    val height = 8
    val blocks = Seq(
      MetadataBlock(4, -3, 5),
      MetadataBlock(6, -3, 5),
      MetadataBlock(5, -3, 5),
      MetadataBlock(5, -3, 5),
      MetadataBlock(9, -3, 5),
      MetadataBlock(3, -3, 5),
      MetadataBlock(7, -3, 5),
      MetadataBlock(7, -3, 5),
      MetadataBlock(2, 4, -6),
    )
    val expected = expectedTokens(blocks)

    simulate(new FramePreparedAcMetadataTokenTraceStage(config)) { dut =>
      pokeConfig(dut, width, height)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for (block <- blocks) {
        dut.io.input.valid.poke(true.B)
        dut.io.input.bits.rawQuant.poke(block.rawQuant.U)
        dut.io.input.bits.ytox.poke(block.ytox.S)
        dut.io.input.bits.ytob.poke(block.ytob.S)
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)

      for (((context, value), ordinal) <- expected.zipWithIndex) {
        withClue(s"metadata token $ordinal") {
          dut.io.trace.valid.expect(true.B)
          dut.io.trace.bits.stage.expect(TraceStage.AcMetadataTokens.U)
          dut.io.trace.bits.group.expect(ordinal.U)
          dut.io.trace.bits.index.expect(context.U)
          dut.io.trace.bits.value.expect(value.S)
        }
        dut.clock.step()
      }

      dut.io.trace.valid.expect(false.B)
      dut.io.input.ready.expect(true.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "FramePreparedAcMetadataTokenTraceStage matches libjxl-tiny all-DCT metadata tokens for a multi-tile fixture" in {
    requireReferenceTools()

    val width = 72
    val height = 8
    val temp = Files.createTempDirectory("hjxl-prepared-acmeta-")
    val preparedJson = temp.resolve("prepared-blocks.json")
    val preparedCsv = temp.resolve("prepared-blocks.csv")
    val oracleNpy = temp.resolve("acmeta.npy")
    val oracleCsv = temp.resolve("acmeta.csv")

    runTool(
      Seq(
        "python3",
        "tools/hjxl_reference.py",
        "--width",
        width.toString,
        "--height",
        height.toString,
        "--pattern",
        "gradient",
        "--dct-only-prepared-blocks-json",
        preparedJson.toString,
        "--dct-only-ac-metadata-tokens-npy",
        oracleNpy.toString
      ),
      "LIBJXL_TINY" -> libjxlTinyRoot.toString
    )
    runTool(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--prepared-json",
        preparedJson.toString,
        "--input-csv",
        preparedCsv.toString
      )
    )
    convertTokenNpyToCsv(oracleNpy, oracleCsv)

    val blocks = readPreparedBlocksCsv(preparedCsv)
    blocks.length mustBe 9
    collectMetadataTokens(width, height, blocks) mustBe readTokenCsv(oracleCsv)
  }
}
