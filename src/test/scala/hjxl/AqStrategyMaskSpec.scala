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
import scala.util.Random

class AqStrategyMaskSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 24, maxFrameHeight = 16)
  private val libjxlTinyRoot =
    Path.of(sys.env.getOrElse("LIBJXL_TINY", "/Users/yunhocho/GitHub/libjxl-tiny"))

  private case class RgbRow(x: Int, y: Int, r: Int, g: Int, b: Int)
  private case class MaskRow(
      block: Int,
      blockX: Int,
      blockY: Int,
      fuzzyErosionQ16: BigInt,
      strategyMaskQ16: BigInt,
      fixedStrategyMaskQ16: BigInt
  )

  private def requireReferenceTools(): Unit = {
    assume(
      Files.isDirectory(libjxlTinyRoot.resolve("python")),
      s"libjxl-tiny Python checkout not found at $libjxlTinyRoot"
    )
    assume(
      Process(Seq("python3", "-c", "import numpy")).! == 0,
      "python3 with numpy is required for AQ strategy-mask fixtures"
    )
  }

  private def runTool(command: Seq[String]): Unit = {
    val output = scala.collection.mutable.ArrayBuffer.empty[String]
    val logger = ProcessLogger(line => output += line, line => output += line)
    val exitCode = Process(
      command,
      TestPaths.repoRoot.toFile,
      "LIBJXL_TINY" -> libjxlTinyRoot.toString,
      "PYTHONDONTWRITEBYTECODE" -> "1"
    ).!(logger)
    withClue(output.mkString("\n")) {
      exitCode mustBe 0
    }
  }

  private def readRgbRows(path: Path, width: Int, height: Int): Seq[RgbRow] = {
    val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
    lines.get(0) mustBe
      "raster,x,y,r_q8,g_q8,b_q8,xyb_x_q12,xyb_y_q12,xyb_b_q12"
    lines.toArray(new Array[String](0)).drop(1).toSeq.flatMap { line =>
      val columns = line.split(",", -1)
      columns.length mustBe 9
      val x = columns(1).toInt
      val y = columns(2).toInt
      Option.when(x < width && y < height)(
        RgbRow(x, y, columns(3).toInt, columns(4).toInt, columns(5).toInt)
      )
    }
  }

  private def readMaskRows(path: Path): Seq[MaskRow] = {
    val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
    lines.get(0) mustBe
      "block,block_x,block_y,fuzzy_erosion_q16,strategy_mask_q16,fixed_strategy_mask_q16," +
        "quantized_xyb_fuzzy_erosion_q16,quantized_xyb_strategy_mask_q16"
    lines.toArray(new Array[String](0)).drop(1).toSeq.map { line =>
      val columns = line.split(",", -1)
      columns.length mustBe 8
      MaskRow(
        block = columns(0).toInt,
        blockX = columns(1).toInt,
        blockY = columns(2).toInt,
        fuzzyErosionQ16 = BigInt(columns(3)),
        strategyMaskQ16 = BigInt(columns(4)),
        fixedStrategyMaskQ16 = BigInt(columns(5))
      )
    }
  }

  private def generateFixture(
      pattern: String,
      width: Int,
      height: Int
  ): (Seq[RgbRow], Seq[MaskRow]) = {
    val xybCsv = Files.createTempFile(s"hjxl-aq-mask-$pattern-xyb-", ".csv")
    val maskCsv = Files.createTempFile(s"hjxl-aq-mask-$pattern-", ".csv")
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
        "--xyb-q12-csv",
        xybCsv.toString,
        "--aq-strategy-mask-q16-csv",
        maskCsv.toString
      )
    )
    (readRgbRows(xybCsv, width, height), readMaskRows(maskCsv))
  }

  private def pokeConfig(frameConfig: FrameConfig, width: Int, height: Int): Unit = {
    frameConfig.xsize.poke(width.U)
    frameConfig.ysize.poke(height.U)
    frameConfig.distanceQ8.poke(256.U)
    frameConfig.fixedPointScale.poke(0.U)
    frameConfig.fixedInvQacQ16.poke(0.U)
    frameConfig.fixedRawQuant.poke(0.U)
    frameConfig.fixedYtox.poke(0.S)
    frameConfig.fixedYtob.poke(0.S)
    frameConfig.enableXyb.poke(true.B)
    frameConfig.enableDct.poke(false.B)
    frameConfig.enableQuant.poke(true.B)
    frameConfig.enableTokenize.poke(false.B)
    frameConfig.tokenSelect.poke(TokenTraceSelect.AqContrast.U)
  }

  private def drivePreparedInput(
      dut: FramePreparedAqStrategyMaskTraceStage,
      value: BigInt
  ): Unit = {
    dut.io.input.valid.poke(true.B)
    dut.io.input.bits.poke(value.U)
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
    dut.io.input.valid.poke(false.B)
  }

  private def waitForPreparedOutput(
      dut: FramePreparedAqStrategyMaskTraceStage,
      clue: String
  ): Int = {
    var cycles = 0
    while (dut.io.trace.valid.peekValue().asBigInt == 0 && cycles < 48) {
      dut.clock.step()
      cycles += 1
    }
    withClue(clue) {
      cycles mustBe AqStrategyMaskFixedPoint.DividendBits
    }
    cycles
  }

  "AqStrategyMaskReciprocal matches its rational Q16 model without a combinational divider" in {
    import AqStrategyMaskFixedPoint._
    val directed = Seq[BigInt](
      0,
      1,
      10,
      100,
      1000,
      Scale,
      5 * Scale,
      100 * Scale,
      MaximumValue
    )
    val random = new Random(4L)
    val cases = directed ++ Seq.fill(128)(BigInt(random.nextInt(1 << 27)))

    simulate(new AqStrategyMaskReciprocal) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.output.ready.poke(false.B)
      dut.clock.step()

      for ((erosion, index) <- cases.zipWithIndex) {
        dut.io.input.valid.poke(true.B)
        dut.io.input.bits.poke(erosion.U)
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
        dut.io.input.valid.poke(false.B)

        var cycles = 0
        while (dut.io.output.valid.peekValue().asBigInt == 0 && cycles < 48) {
          dut.clock.step()
          cycles += 1
        }
        withClue(s"strategy-mask reciprocal case $index") {
          cycles mustBe DividendBits
          val expected = maskQ16(erosion)
          dut.io.output.bits.expect(expected.U)
          val erosionFloat = erosion.toFloat / Scale.toFloat
          val reference = Math.round((1.0f / (erosionFloat + 0.001f)) * Scale.toFloat)
          (expected - BigInt(reference)).abs must be <= BigInt(4)
        }

        val stable = dut.io.output.bits.peekValue().asBigInt
        dut.clock.step(2)
        dut.io.output.valid.expect(true.B)
        dut.io.output.bits.expect(stable.U)
        dut.io.output.ready.poke(true.B)
        dut.clock.step()
        dut.io.output.ready.poke(false.B)
        dut.io.busy.expect(false.B)
      }
    }
  }

  "FramePreparedAqStrategyMaskTraceStage matches the full reference mask exactly" in {
    requireReferenceTools()
    val width = 17
    val height = 9
    val fixtures = Seq("constant", "gradient", "checkerboard", "impulse", "random").map {
      pattern =>
        val (_, rows) = generateFixture(pattern, width, height)
        rows.length mustBe 6
        rows.foreach(row => row.fixedStrategyMaskQ16 mustBe row.strategyMaskQ16)
        pattern -> rows
    }

    simulate(new FramePreparedAqStrategyMaskTraceStage(config)) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      pokeConfig(dut.io.config, width, height)
      dut.clock.step()

      for ((pattern, rows) <- fixtures) {
        pokeConfig(dut.io.config, width, height)
        for ((row, rowIndex) <- rows.zipWithIndex) {
          drivePreparedInput(dut, row.fuzzyErosionQ16)
          if (rowIndex == 0) {
            pokeConfig(dut.io.config, width = 0, height = 0)
          }
          waitForPreparedOutput(dut, s"$pattern prepared strategy mask $rowIndex")
          dut.io.trace.bits.stage.expect(TraceStage.AqStrategyMask.U)
          dut.io.trace.bits.group.expect(0.U)
          dut.io.trace.bits.index.expect(row.block.U)
          dut.io.trace.bits.value.expect(row.fixedStrategyMaskQ16.S)
          dut.io.traceLast.expect((rowIndex == rows.length - 1).B)

          val stable = dut.io.trace.bits.value.peekValue().asBigInt
          dut.clock.step(2)
          dut.io.trace.valid.expect(true.B)
          dut.io.trace.bits.value.expect(stable.S)
          dut.io.trace.ready.poke(true.B)
          dut.clock.step()
          dut.io.trace.ready.poke(false.B)
        }
        dut.io.trace.valid.expect(false.B)
        dut.io.busy.expect(false.B)
        dut.io.overflow.expect(true.B)
      }
    }
  }

  "FrameAqStrategyMaskTraceStage composes the RGB AQ stages through the strategy mask" in {
    requireReferenceTools()
    val width = 17
    val height = 9
    val fixtures = Seq("constant", "gradient", "checkerboard", "impulse", "random").map {
      pattern =>
        val (rgb, rows) = generateFixture(pattern, width, height)
        rgb.length mustBe width * height
        pattern -> (rgb, rows)
    }

    simulate(new FrameAqStrategyMaskTraceStage(config)) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      pokeConfig(dut.io.config, width, height)
      dut.clock.step()

      for ((pattern, (rgb, expectedRows)) <- fixtures) {
        pokeConfig(dut.io.config, width, height)
        for ((row, inputIndex) <- rgb.zipWithIndex) {
          dut.io.input.valid.poke(true.B)
          dut.io.input.bits.x.poke(row.x.U)
          dut.io.input.bits.y.poke(row.y.U)
          dut.io.input.bits.r.poke(row.r.S)
          dut.io.input.bits.g.poke(row.g.S)
          dut.io.input.bits.b.poke(row.b.S)
          withClue(s"$pattern RGB input $inputIndex") {
            dut.io.input.ready.expect(true.B)
          }
          dut.clock.step()
          if (inputIndex == 0) {
            pokeConfig(dut.io.config, width = 0, height = 0)
          }
        }
        dut.io.input.valid.poke(false.B)

        for ((expected, outputIndex) <- expectedRows.zipWithIndex) {
          var waitCycles = 0
          val limit = if (outputIndex == 0) 550 else 64
          while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < limit) {
            dut.clock.step()
            waitCycles += 1
          }
          withClue(s"$pattern RGB strategy mask $outputIndex") {
            waitCycles must be < limit
            dut.io.trace.bits.stage.expect(TraceStage.AqStrategyMask.U)
            dut.io.trace.bits.group.expect(0.U)
            dut.io.trace.bits.index.expect(expected.block.U)
            val actual = dut.io.trace.bits.value.peekValue().asBigInt
            val error = (actual - expected.strategyMaskQ16).abs
            val allowed = (expected.strategyMaskQ16 / 50).max(BigInt(8))
            error must be <= allowed
            dut.io.traceLast.expect((outputIndex == expectedRows.length - 1).B)
          }
          dut.clock.step()
        }
        dut.io.trace.valid.expect(false.B)
        dut.io.busy.expect(false.B)
      }
    }
  }

  "FrameAqStrategyMaskTraceStage preserves the mask across a 64-pixel tile boundary" in {
    requireReferenceTools()
    val width = 65
    val height = 1
    val (rgb, expectedRows) = generateFixture("gradient", width, height)
    expectedRows.length mustBe 9

    simulate(
      new FrameAqStrategyMaskTraceStage(HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 8))
    ) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      pokeConfig(dut.io.config, width, height)
      dut.clock.step()

      for (row <- rgb) {
        dut.io.input.valid.poke(true.B)
        dut.io.input.bits.x.poke(row.x.U)
        dut.io.input.bits.y.poke(row.y.U)
        dut.io.input.bits.r.poke(row.r.S)
        dut.io.input.bits.g.poke(row.g.S)
        dut.io.input.bits.b.poke(row.b.S)
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.input.valid.poke(false.B)

      for ((expected, outputIndex) <- expectedRows.zipWithIndex) {
        var waitCycles = 0
        val limit = if (outputIndex == 0) 750 else 64
        while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < limit) {
          dut.clock.step()
          waitCycles += 1
        }
        withClue(s"cross-tile strategy mask $outputIndex") {
          waitCycles must be < limit
          dut.io.trace.bits.index.expect(outputIndex.U)
          expected.blockX mustBe outputIndex
          expected.blockY mustBe 0
          val actual = dut.io.trace.bits.value.peekValue().asBigInt
          val error = (actual - expected.strategyMaskQ16).abs
          error must be <= (expected.strategyMaskQ16 / 50).max(BigInt(8))
          dut.io.traceLast.expect((outputIndex == expectedRows.length - 1).B)
        }
        dut.clock.step()
      }
      dut.io.busy.expect(false.B)
    }
  }

  "FramePreparedAqStrategyMaskTraceStage preserves two-dimensional tiled mask order" in {
    requireReferenceTools()
    val width = 65
    val height = 65
    val (_, expectedRows) = generateFixture("random", width, height)
    expectedRows.length mustBe 9 * 9
    expectedRows.foreach(row => row.fixedStrategyMaskQ16 mustBe row.strategyMaskQ16)

    simulate(
      new FramePreparedAqStrategyMaskTraceStage(HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 72))
    ) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      pokeConfig(dut.io.config, width, height)
      dut.clock.step()

      for ((expected, outputIndex) <- expectedRows.zipWithIndex) {
        drivePreparedInput(dut, expected.fuzzyErosionQ16)
        waitForPreparedOutput(dut, s"two-dimensional strategy mask $outputIndex")
        dut.io.trace.bits.stage.expect(TraceStage.AqStrategyMask.U)
        dut.io.trace.bits.index.expect(outputIndex.U)
        dut.io.trace.bits.value.expect(expected.fixedStrategyMaskQ16.S)
        expected.blockX mustBe outputIndex % 9
        expected.blockY mustBe outputIndex / 9
        dut.io.traceLast.expect((outputIndex == expectedRows.length - 1).B)
        dut.clock.step()
      }
      dut.io.busy.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
  }
}

class AqStrategyMaskElaborationSpec extends AnyFreeSpec with Matchers {
  "the RGB AQ strategy-mask top emits a sequential reciprocal pipeline" in {
    val targetDir = Files.createTempDirectory("hjxl-aq-strategy-mask-elaborate-")
    ChiselStage.emitSystemVerilogFile(
      new FrameAqStrategyMaskTraceStage(HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 8)),
      args = Array("--target-dir", targetDir.toString),
      firtoolOpts = Array(
        "-disable-all-randomization",
        "-strip-debug-info",
        "-default-layer-specialization=enable"
      )
    )
    val stream = Files.walk(targetDir)
    val files = try {
      stream.iterator().asScala
        .filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".sv"))
        .map(path => path.getFileName.toString -> Files.readString(path, StandardCharsets.UTF_8))
        .toMap
    } finally {
      stream.close()
    }
    files.keySet must contain allOf (
      "FrameAqStrategyMaskTraceStage.sv",
      "FramePreparedAqStrategyMaskTraceStage.sv",
      "AqStrategyMaskReciprocal.sv",
      "FrameAqFuzzyErosionTraceStage.sv",
      "FrameAqContrastTraceStage.sv"
    )
    val top = files("FrameAqStrategyMaskTraceStage.sv")
    top must include("module FrameAqStrategyMaskTraceStage")
    top must include("io_traceLast")
    top must include("io_busy")
    top must include("io_overflow")
    val reciprocal = files("AqStrategyMaskReciprocal.sv")
    reciprocal must include("module AqStrategyMaskReciprocal")
    reciprocal must include("iterationsRemaining")
    reciprocal must not include " / "
  }
}
