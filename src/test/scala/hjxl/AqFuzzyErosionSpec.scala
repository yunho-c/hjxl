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

class AqFuzzyErosionSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 24, maxFrameHeight = 16)
  private val libjxlTinyRoot =
    Path.of(sys.env.getOrElse("LIBJXL_TINY", "/Users/yunhocho/GitHub/libjxl-tiny"))

  private case class RgbRow(x: Int, y: Int, r: Int, g: Int, b: Int)
  private case class ContrastRow(cell: Int, cellX: Int, cellY: Int, valueQ16: BigInt)
  private case class ErosionRow(block: Int, blockX: Int, blockY: Int, valueQ16: BigInt)

  private def requireReferenceTools(): Unit = {
    assume(
      Files.isDirectory(libjxlTinyRoot.resolve("python")),
      s"libjxl-tiny Python checkout not found at $libjxlTinyRoot"
    )
    assume(
      Process(Seq("python3", "-c", "import numpy")).! == 0,
      "python3 with numpy is required for AQ fuzzy-erosion fixtures"
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

  private def readContrastRows(path: Path): Seq[ContrastRow] = {
    val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
    lines.get(0) mustBe
      "cell,cell_x,cell_y,pre_erosion_q16,quantized_xyb_pre_erosion_q16"
    lines.toArray(new Array[String](0)).drop(1).toSeq.map { line =>
      val columns = line.split(",", -1)
      columns.length mustBe 5
      ContrastRow(columns(0).toInt, columns(1).toInt, columns(2).toInt, BigInt(columns(3)))
    }
  }

  private def readErosionRows(path: Path): Seq[ErosionRow] = {
    val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
    lines.get(0) mustBe
      "block,block_x,block_y,fuzzy_erosion_q16,quantized_xyb_fuzzy_erosion_q16"
    lines.toArray(new Array[String](0)).drop(1).toSeq.map { line =>
      val columns = line.split(",", -1)
      columns.length mustBe 5
      ErosionRow(columns(0).toInt, columns(1).toInt, columns(2).toInt, BigInt(columns(3)))
    }
  }

  private def generateFixture(
      pattern: String,
      width: Int,
      height: Int
  ): (Seq[RgbRow], Seq[ContrastRow], Seq[ErosionRow]) = {
    val xybCsv = Files.createTempFile(s"hjxl-aq-erosion-$pattern-xyb-", ".csv")
    val contrastCsv = Files.createTempFile(s"hjxl-aq-erosion-$pattern-contrast-", ".csv")
    val erosionCsv = Files.createTempFile(s"hjxl-aq-erosion-$pattern-", ".csv")
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
        "--aq-contrast-q16-csv",
        contrastCsv.toString,
        "--aq-fuzzy-erosion-q16-csv",
        erosionCsv.toString
      )
    )
    (readRgbRows(xybCsv, width, height), readContrastRows(contrastCsv), readErosionRows(erosionCsv))
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

  "AqFuzzyErosionSample selects four minima and matches the weighted Q16 model" in {
    import AqFuzzyErosionFixedPoint._
    val maximum = MaximumValue
    val directed = Seq(
      Seq.fill(NeighborhoodSize)(BigInt(0)),
      Seq.fill(NeighborhoodSize)(maximum),
      Seq[BigInt](900, 100, 800, 200, 500, 700, 300, 600, 400),
      Seq[BigInt](5, 5, 5, 9, 5, 9, 9, 9, 9)
    )
    val random = new Random(3L)
    val cases = directed ++ Seq.fill(256) {
      Seq.fill(NeighborhoodSize)(BigInt(random.nextInt(1 << 22)))
    }

    simulate(new AqFuzzyErosionSample()) { dut =>
      for ((neighborhood, index) <- cases.zipWithIndex) {
        val center = neighborhood(4)
        dut.io.center.poke(center.U)
        for ((value, neighbor) <- neighborhood.zip(dut.io.neighborhood)) {
          neighbor.poke(value.U)
        }
        dut.clock.step()
        withClue(s"fuzzy erosion neighborhood $index") {
          dut.io.weightedSum.expect(weightedSumQ16(center, neighborhood).U)
        }
      }
    }
  }

  "FramePreparedAqFuzzyErosionTraceStage matches the Q16 libjxl-tiny seam exactly" in {
    requireReferenceTools()
    val width = 17
    val height = 9
    val fixtures = Seq("constant", "gradient", "checkerboard", "impulse", "random").map { pattern =>
      val (_, contrast, erosion) = generateFixture(pattern, width, height)
      contrast.length mustBe 24
      erosion.length mustBe 6
      pattern -> (contrast, erosion)
    }

    simulate(new FramePreparedAqFuzzyErosionTraceStage(config)) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      pokeConfig(dut.io.config, width, height)
      dut.clock.step()

      for ((pattern, (contrast, expectedRows)) <- fixtures) {
        pokeConfig(dut.io.config, width, height)
        for ((row, inputIndex) <- contrast.zipWithIndex) {
          dut.io.input.valid.poke(true.B)
          dut.io.input.bits.poke(row.valueQ16.U)
          withClue(s"$pattern prepared contrast cell $inputIndex") {
            dut.io.input.ready.expect(true.B)
          }
          dut.clock.step()
          if (inputIndex == 0) {
            pokeConfig(dut.io.config, width = 0, height = 0)
          }
        }
        dut.io.input.valid.poke(false.B)
        dut.io.overflow.expect(false.B)

        for ((expected, outputIndex) <- expectedRows.zipWithIndex) {
          var waitCycles = 0
          while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < 8) {
            dut.clock.step()
            waitCycles += 1
          }
          withClue(s"$pattern prepared erosion block $outputIndex") {
            waitCycles must be < 8
            dut.io.trace.bits.stage.expect(TraceStage.AqFuzzyErosion.U)
            dut.io.trace.bits.group.expect(0.U)
            dut.io.trace.bits.index.expect(expected.block.U)
            expected.blockX mustBe outputIndex % 3
            expected.blockY mustBe outputIndex / 3
            val actual = dut.io.trace.bits.value.peekValue().asBigInt
            (actual - expected.valueQ16).abs must be <= BigInt(1)
            dut.io.traceLast.expect((outputIndex == expectedRows.length - 1).B)
          }
          dut.io.trace.ready.poke(false.B)
          val stableValue = dut.io.trace.bits.value.peekValue().asBigInt
          dut.clock.step(2)
          dut.io.trace.valid.expect(true.B)
          dut.io.trace.bits.value.expect(stableValue.S)
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

  "FrameAqFuzzyErosionTraceStage composes RGB contrast with block erosion" in {
    requireReferenceTools()
    val width = 17
    val height = 9
    val fixtures = Seq("constant", "gradient", "checkerboard", "impulse", "random").map { pattern =>
      val (rgb, _, erosion) = generateFixture(pattern, width, height)
      rgb.length mustBe width * height
      pattern -> (rgb, erosion)
    }

    simulate(new FrameAqFuzzyErosionTraceStage(config)) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
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
        dut.io.overflow.expect(false.B)

        for ((expected, outputIndex) <- expectedRows.zipWithIndex) {
          var waitCycles = 0
          val limit = if (outputIndex == 0) 450 else 8
          while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < limit) {
            dut.clock.step()
            waitCycles += 1
          }
          withClue(s"$pattern RGB erosion block $outputIndex") {
            waitCycles must be < limit
            dut.io.trace.bits.stage.expect(TraceStage.AqFuzzyErosion.U)
            dut.io.trace.bits.group.expect(0.U)
            dut.io.trace.bits.index.expect(expected.block.U)
            val actual = dut.io.trace.bits.value.peekValue().asBigInt
            val error = (actual - expected.valueQ16).abs
            val allowed = (expected.valueQ16 / 50).max(BigInt(1 << 15))
            error must be <= allowed
            dut.io.traceLast.expect((outputIndex == expectedRows.length - 1).B)
          }
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

  "FrameAqFuzzyErosionTraceStage preserves the horizontal grid across a 64-pixel tile boundary" in {
    requireReferenceTools()
    val width = 65
    val height = 1
    val (rgb, _, expectedRows) = generateFixture("gradient", width, height)
    expectedRows.length mustBe 9

    simulate(new FrameAqFuzzyErosionTraceStage(HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 8))) { dut =>
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
        val limit = if (outputIndex == 0) 650 else 8
        while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < limit) {
          dut.clock.step()
          waitCycles += 1
        }
        withClue(s"cross-tile fuzzy erosion block $outputIndex") {
          waitCycles must be < limit
          dut.io.trace.bits.stage.expect(TraceStage.AqFuzzyErosion.U)
          dut.io.trace.bits.index.expect(outputIndex.U)
          expected.blockX mustBe outputIndex
          expected.blockY mustBe 0
          val actual = dut.io.trace.bits.value.peekValue().asBigInt
          val error = (actual - expected.valueQ16).abs
          val allowed = (expected.valueQ16 / 50).max(BigInt(1 << 15))
          error must be <= allowed
          dut.io.traceLast.expect((outputIndex == expectedRows.length - 1).B)
        }
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.busy.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "FramePreparedAqFuzzyErosionTraceStage preserves 72x72 traversal with stripe-local neighborhoods" in {
    requireReferenceTools()
    val width = 65
    val height = 65
    val (_, contrast, expectedRows) = generateFixture("gradient", width, height)
    contrast.length mustBe 18 * 18
    expectedRows.length mustBe 9 * 9

    simulate(
      new FramePreparedAqFuzzyErosionTraceStage(HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 72))
    ) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      pokeConfig(dut.io.config, width, height)
      dut.clock.step()

      for (row <- contrast) {
        dut.io.input.valid.poke(true.B)
        dut.io.input.bits.poke(row.valueQ16.U)
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.input.valid.poke(false.B)

      for ((expected, outputIndex) <- expectedRows.zipWithIndex) {
        var waitCycles = 0
        while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < 8) {
          dut.clock.step()
          waitCycles += 1
        }
        withClue(s"two-dimensional fuzzy erosion block $outputIndex") {
          waitCycles must be < 8
          dut.io.trace.bits.stage.expect(TraceStage.AqFuzzyErosion.U)
          dut.io.trace.bits.index.expect(outputIndex.U)
          expected.blockX mustBe outputIndex % 9
          expected.blockY mustBe outputIndex / 9
          val actual = dut.io.trace.bits.value.peekValue().asBigInt
          (actual - expected.valueQ16).abs must be <= BigInt(1)
          dut.io.traceLast.expect((outputIndex == expectedRows.length - 1).B)
        }
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.busy.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
  }
}

class AqFuzzyErosionElaborationSpec extends AnyFreeSpec with Matchers {
  "the RGB fuzzy-erosion top emits the composed and prepared RTL interfaces" in {
    val targetDir = Files.createTempDirectory("hjxl-aq-fuzzy-erosion-elaborate-")
    ChiselStage.emitSystemVerilogFile(
      new FrameAqFuzzyErosionTraceStage(HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 8)),
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
      "FrameAqFuzzyErosionTraceStage.sv",
      "FramePreparedAqFuzzyErosionTraceStage.sv",
      "AqFuzzyErosionSample.sv",
      "FrameAqContrastTraceStage.sv"
    )
    val top = files("FrameAqFuzzyErosionTraceStage.sv")
    top must include("module FrameAqFuzzyErosionTraceStage")
    top must include("io_input_valid")
    top must include("io_trace_valid")
    top must include("io_traceLast")
    top must include("io_busy")
    top must include("io_overflow")
    files("FramePreparedAqFuzzyErosionTraceStage.sv") must include(
      "module FramePreparedAqFuzzyErosionTraceStage"
    )
    files("AqFuzzyErosionSample.sv") must include("output [33:0] io_weightedSum")
  }
}
