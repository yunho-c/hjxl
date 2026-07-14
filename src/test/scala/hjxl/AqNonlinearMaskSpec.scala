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

class AqNonlinearMaskSpec extends AnyFreeSpec with Matchers with ChiselSim {
  import AqNonlinearMaskFixedPoint._

  private val config = HjxlConfig(maxFrameWidth = 24, maxFrameHeight = 16)
  private val libjxlTinyRoot =
    Path.of(sys.env.getOrElse("LIBJXL_TINY", "/Users/yunhocho/GitHub/libjxl-tiny"))
  private val evaluationCycles = 3 * (DividerBits + 2)

  private case class RgbRow(x: Int, y: Int, r: Int, g: Int, b: Int)
  private case class MaskRow(
      block: Int,
      blockX: Int,
      blockY: Int,
      fuzzyErosionQ16: BigInt,
      nonlinearMaskQ24: BigInt,
      fixedNonlinearMaskQ24: BigInt,
      quantizedXybFuzzyErosionQ16: BigInt,
      quantizedXybNonlinearMaskQ24: BigInt
  )

  private def requireReferenceTools(): Unit = {
    assume(
      Files.isDirectory(libjxlTinyRoot.resolve("python")),
      s"libjxl-tiny Python checkout not found at $libjxlTinyRoot"
    )
    assume(
      Process(Seq("python3", "-c", "import numpy")).! == 0,
      "python3 with numpy is required for AQ nonlinear-mask fixtures"
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
      "block,block_x,block_y,fuzzy_erosion_q16,nonlinear_mask_q24," +
        "fixed_nonlinear_mask_q24,quantized_xyb_fuzzy_erosion_q16," +
        "quantized_xyb_nonlinear_mask_q24"
    lines.toArray(new Array[String](0)).drop(1).toSeq.map { line =>
      val columns = line.split(",", -1)
      columns.length mustBe 8
      MaskRow(
        block = columns(0).toInt,
        blockX = columns(1).toInt,
        blockY = columns(2).toInt,
        fuzzyErosionQ16 = BigInt(columns(3)),
        nonlinearMaskQ24 = BigInt(columns(4)),
        fixedNonlinearMaskQ24 = BigInt(columns(5)),
        quantizedXybFuzzyErosionQ16 = BigInt(columns(6)),
        quantizedXybNonlinearMaskQ24 = BigInt(columns(7))
      )
    }
  }

  private def generateFixture(
      pattern: String,
      width: Int,
      height: Int
  ): (Seq[RgbRow], Seq[MaskRow]) = {
    val xybCsv = Files.createTempFile(s"hjxl-aq-nonlinear-$pattern-xyb-", ".csv")
    val maskCsv = Files.createTempFile(s"hjxl-aq-nonlinear-$pattern-", ".csv")
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
        "--aq-nonlinear-mask-q24-csv",
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
      dut: FramePreparedAqNonlinearMaskTraceStage,
      value: BigInt
  ): Unit = {
    dut.io.input.valid.poke(true.B)
    dut.io.input.bits.poke(value.U)
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
    dut.io.input.valid.poke(false.B)
  }

  private def waitForPreparedOutput(
      dut: FramePreparedAqNonlinearMaskTraceStage,
      clue: String
  ): Unit = {
    var cycles = 0
    while (dut.io.trace.valid.peekValue().asBigInt == 0 && cycles <= evaluationCycles) {
      dut.clock.step()
      cycles += 1
    }
    withClue(clue) {
      cycles mustBe evaluationCycles
    }
  }

  private def referenceMaskQ24(erosionQ16: BigInt): BigInt = {
    def f32(value: Double): Float = value.toFloat
    val erosion = f32(erosionQ16.toDouble / (BigInt(1) << InputFractionBits).toDouble)
    val value1 = math.max(f32(erosion * f32(0.74760422233706747)), f32(1e-3))
    val value1Squared = f32(value1 * value1)
    val value2 = f32(f32(1.0) / f32(value1 + f32(305.04035728311436)))
    val value3 = f32(f32(1.0) / f32(value1Squared + f32(2.1925739705298404)))
    val offset4 = f32(f32(0.25) * f32(2.1925739705298404))
    val value4 = f32(f32(1.0) / f32(value1Squared + offset4))
    val result = f32(
      f32(-0.74174993) +
        f32(f32(3.2353257320940401) * value4) +
        f32(f32(12.906028311180409) * value2) +
        f32(f32(5.0220313103171232) * value3)
    )
    BigInt(Math.round(result.toDouble * (BigInt(1) << OutputFractionBits).toDouble))
  }

  private def signedTraceValue(value: BigInt): BigInt = BigInt(value.toInt)

  "AqNonlinearMaskEvaluator matches the Q24 model across positive Q16 input" in {
    val directed = Seq[BigInt](
      0,
      1,
      66,
      655,
      6554,
      BigInt(1) << InputFractionBits,
      BigInt(5) << InputFractionBits,
      BigInt(100) << InputFractionBits,
      MaximumInput
    )
    val random = new Random(5L)
    val cases = directed ++ Seq.fill(64)(BigInt(random.nextInt(Int.MaxValue)))

    simulate(new AqNonlinearMaskEvaluator) { dut =>
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
        while (dut.io.output.valid.peekValue().asBigInt == 0 && cycles <= evaluationCycles) {
          dut.clock.step()
          cycles += 1
        }
        withClue(s"AQ nonlinear-mask evaluator case $index") {
          cycles mustBe evaluationCycles
          val expected = maskQ24(erosion)
          dut.io.output.bits.expect(expected.S)
          (expected - referenceMaskQ24(erosion)).abs must be <= BigInt(32)
        }

        val stable = signedTraceValue(dut.io.output.bits.peekValue().asBigInt)
        dut.clock.step(2)
        dut.io.output.valid.expect(true.B)
        dut.io.output.bits.expect(stable.S)
        dut.io.output.ready.poke(true.B)
        dut.clock.step()
        dut.io.output.ready.poke(false.B)
        dut.io.busy.expect(false.B)
      }
    }
  }

  "FramePreparedAqNonlinearMaskTraceStage matches the independent reference fixtures" in {
    requireReferenceTools()
    val width = 17
    val height = 9
    val fixtures = Seq("constant", "gradient", "checkerboard", "impulse", "random").map {
      pattern =>
        val (_, rows) = generateFixture(pattern, width, height)
        rows.length mustBe 6
        rows.foreach { row =>
          (row.fixedNonlinearMaskQ24 - row.nonlinearMaskQ24).abs must be <= BigInt(32)
        }
        pattern -> rows
    }

    simulate(new FramePreparedAqNonlinearMaskTraceStage(config)) { dut =>
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
          waitForPreparedOutput(dut, s"$pattern prepared nonlinear mask $rowIndex")
          dut.io.trace.bits.stage.expect(TraceStage.AqNonlinearMask.U)
          dut.io.trace.bits.group.expect(0.U)
          dut.io.trace.bits.index.expect(row.block.U)
          dut.io.trace.bits.value.expect(row.fixedNonlinearMaskQ24.S)
          val expectedStrategyMask = AqStrategyMaskFixedPoint.maskQ16(row.fuzzyErosionQ16)
          dut.io.strategyMaskQ16.expect(expectedStrategyMask.U)
          dut.io.traceLast.expect((rowIndex == rows.length - 1).B)

          val stable = signedTraceValue(dut.io.trace.bits.value.peekValue().asBigInt)
          val stableStrategyMask = dut.io.strategyMaskQ16.peekValue().asBigInt
          dut.clock.step(2)
          dut.io.trace.valid.expect(true.B)
          dut.io.trace.bits.value.expect(stable.S)
          dut.io.strategyMaskQ16.expect(stableStrategyMask.U)
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

  "FrameAqNonlinearMaskTraceStage composes the RGB AQ path into signed Q24 seeds" in {
    requireReferenceTools()
    val width = 17
    val height = 9
    val fixtures = Seq("constant", "gradient", "checkerboard", "impulse", "random").map {
      pattern =>
        val (rgb, rows) = generateFixture(pattern, width, height)
        rgb.length mustBe width * height
        pattern -> (rgb, rows)
    }

    val observedErosionByPattern = scala.collection.mutable.Map.empty[String, Seq[BigInt]]
    simulate(new FrameAqFuzzyErosionTraceStage(config)) { dut =>
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
          withClue(s"$pattern erosion-source RGB input $inputIndex") {
            dut.io.input.ready.expect(true.B)
          }
          dut.clock.step()
        }
        dut.io.input.valid.poke(false.B)

        val observed = expectedRows.indices.map { outputIndex =>
          var waitCycles = 0
          val limit = if (outputIndex == 0) 450 else 8
          while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < limit) {
            dut.clock.step()
            waitCycles += 1
          }
          withClue(s"$pattern erosion source block $outputIndex") {
            waitCycles must be < limit
            dut.io.trace.bits.index.expect(outputIndex.U)
          }
          val value = dut.io.trace.bits.value.peekValue().asBigInt
          dut.clock.step()
          value
        }
        observedErosionByPattern(pattern) = observed
        dut.io.trace.valid.expect(false.B)
        dut.io.busy.expect(false.B)
      }
    }

    simulate(new FrameAqNonlinearMaskTraceStage(config)) { dut =>
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

        for (((expected, observedErosion), outputIndex) <-
            expectedRows.zip(observedErosionByPattern(pattern)).zipWithIndex) {
          var waitCycles = 0
          val limit = if (outputIndex == 0) 750 else 200
          while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < limit) {
            dut.clock.step()
            waitCycles += 1
          }
          withClue(s"$pattern RGB nonlinear mask $outputIndex") {
            waitCycles must be < limit
            dut.io.trace.bits.stage.expect(TraceStage.AqNonlinearMask.U)
            dut.io.trace.bits.group.expect(0.U)
            dut.io.trace.bits.index.expect(expected.block.U)
            val actual = signedTraceValue(dut.io.trace.bits.value.peekValue().asBigInt)
            val reference = expected.quantizedXybNonlinearMaskQ24
            val error = (actual - reference).abs
            val allowed = (reference.abs / 50).max(BigInt(1) << 14)
            error must be <= allowed
            dut.io.strategyMaskQ16.expect(
              AqStrategyMaskFixedPoint.maskQ16(observedErosion).U
            )
            dut.io.traceLast.expect((outputIndex == expectedRows.length - 1).B)
          }
          dut.clock.step()
        }
        dut.io.trace.valid.expect(false.B)
        dut.io.busy.expect(false.B)
      }
    }
  }

  "FrameAqNonlinearMaskTraceStage preserves the seed across a 64-pixel tile boundary" in {
    requireReferenceTools()
    val width = 65
    val height = 1
    val (rgb, expectedRows) = generateFixture("gradient", width, height)
    expectedRows.length mustBe 9

    simulate(
      new FrameAqNonlinearMaskTraceStage(HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 8))
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
        val limit = if (outputIndex == 0) 900 else 200
        while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < limit) {
          dut.clock.step()
          waitCycles += 1
        }
        withClue(s"cross-tile nonlinear mask $outputIndex") {
          waitCycles must be < limit
          dut.io.trace.bits.index.expect(outputIndex.U)
          expected.blockX mustBe outputIndex
          expected.blockY mustBe 0
          val actual = signedTraceValue(dut.io.trace.bits.value.peekValue().asBigInt)
          val reference = expected.quantizedXybNonlinearMaskQ24
          val allowed = (reference.abs / 50).max(BigInt(1) << 14)
          (actual - reference).abs must be <= allowed
          dut.io.traceLast.expect((outputIndex == expectedRows.length - 1).B)
        }
        dut.clock.step()
      }
      dut.io.busy.expect(false.B)
    }
  }

  "FramePreparedAqNonlinearMaskTraceStage preserves two-dimensional tiled order" in {
    requireReferenceTools()
    val width = 65
    val height = 65
    val (_, expectedRows) = generateFixture("random", width, height)
    expectedRows.length mustBe 9 * 9

    simulate(
      new FramePreparedAqNonlinearMaskTraceStage(
        HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 72)
      )
    ) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      pokeConfig(dut.io.config, width, height)
      dut.clock.step()

      for ((expected, outputIndex) <- expectedRows.zipWithIndex) {
        drivePreparedInput(dut, expected.fuzzyErosionQ16)
        waitForPreparedOutput(dut, s"two-dimensional nonlinear mask $outputIndex")
        dut.io.trace.bits.stage.expect(TraceStage.AqNonlinearMask.U)
        dut.io.trace.bits.index.expect(outputIndex.U)
        dut.io.trace.bits.value.expect(expected.fixedNonlinearMaskQ24.S)
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

class AqNonlinearMaskElaborationSpec extends AnyFreeSpec with Matchers {
  "the RGB AQ nonlinear-mask top emits one shared sequential divider" in {
    val targetDir = Files.createTempDirectory("hjxl-aq-nonlinear-mask-elaborate-")
    ChiselStage.emitSystemVerilogFile(
      new FrameAqNonlinearMaskTraceStage(HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 8)),
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
      "FrameAqNonlinearMaskTraceStage.sv",
      "FramePreparedAqNonlinearMaskTraceStage.sv",
      "AqNonlinearMaskEvaluator.sv",
      "AqNonlinearMaskRoundedDivider.sv",
      "FrameAqFuzzyErosionTraceStage.sv",
      "FrameAqContrastTraceStage.sv"
    )
    val top = files("FrameAqNonlinearMaskTraceStage.sv")
    top must include("module FrameAqNonlinearMaskTraceStage")
    top must include("io_traceLast")
    top must include("io_busy")
    top must include("io_overflow")
    val evaluator = files("AqNonlinearMaskEvaluator.sv")
    evaluator must include("module AqNonlinearMaskEvaluator")
    evaluator must include("AqNonlinearMaskRoundedDivider")
    evaluator must not include " / "
    val divider = files("AqNonlinearMaskRoundedDivider.sv")
    divider must include("module AqNonlinearMaskRoundedDivider")
    divider must include("iterationsRemaining")
    divider must not include " / "
  }
}
