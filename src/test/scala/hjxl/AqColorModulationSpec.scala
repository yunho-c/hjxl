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

class AqColorModulationSpec extends AnyFreeSpec with Matchers with ChiselSim {
  import AqColorModulationFixedPoint._

  private val config = HjxlConfig(maxFrameWidth = 24, maxFrameHeight = 16)
  private val libjxlTinyRoot =
    Path.of(sys.env.getOrElse("LIBJXL_TINY", "/Users/yunhocho/GitHub/libjxl-tiny"))
  private val evaluationCycles = SamplesPerBlock

  private case class XybRow(
      x: Int,
      y: Int,
      r: Int,
      g: Int,
      b: Int,
      xybXQ12: BigInt,
      xybYQ12: BigInt,
      xybBQ12: BigInt
  )
  private case class ColorRow(
      block: Int,
      blockX: Int,
      blockY: Int,
      distanceQ8: Int,
      colorModulationQ24: BigInt,
      fixedHfModulationQ24: BigInt,
      fixedColorModulationQ24: BigInt,
      quantizedXybColorModulationQ24: BigInt,
      fixedQuantizedXybColorModulationQ24: BigInt,
      inputQ8FixedHfModulationQ24: BigInt,
      inputQ8FixedColorModulationQ24: BigInt
  )

  private def requireReferenceTools(): Unit = {
    assume(
      Files.isDirectory(libjxlTinyRoot.resolve("python")),
      s"libjxl-tiny Python checkout not found at $libjxlTinyRoot"
    )
    assume(
      Process(Seq("python3", "-c", "import numpy")).! == 0,
      "python3 with numpy is required for AQ color-modulation fixtures"
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

  private def readXybRows(path: Path): Seq[XybRow] = {
    val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
    lines.get(0) mustBe
      "raster,x,y,r_q8,g_q8,b_q8,xyb_x_q12,xyb_y_q12,xyb_b_q12"
    lines.toArray(new Array[String](0)).drop(1).toSeq.map { line =>
      val columns = line.split(",", -1)
      columns.length mustBe 9
      XybRow(
        x = columns(1).toInt,
        y = columns(2).toInt,
        r = columns(3).toInt,
        g = columns(4).toInt,
        b = columns(5).toInt,
        xybXQ12 = BigInt(columns(6)),
        xybYQ12 = BigInt(columns(7)),
        xybBQ12 = BigInt(columns(8))
      )
    }
  }

  private def readColorRows(path: Path): Seq[ColorRow] = {
    val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
    lines.get(0) mustBe
      "block,block_x,block_y,distance_q8,hf_modulation_q24," +
        "color_modulation_q24,fixed_hf_modulation_q24,fixed_color_modulation_q24," +
        "quantized_xyb_hf_modulation_q24,quantized_xyb_color_modulation_q24," +
        "fixed_quantized_xyb_hf_modulation_q24," +
        "fixed_quantized_xyb_color_modulation_q24," +
        "input_q8_fixed_hf_modulation_q24,input_q8_fixed_color_modulation_q24"
    lines.toArray(new Array[String](0)).drop(1).toSeq.map { line =>
      val columns = line.split(",", -1)
      columns.length mustBe 14
      ColorRow(
        block = columns(0).toInt,
        blockX = columns(1).toInt,
        blockY = columns(2).toInt,
        distanceQ8 = columns(3).toInt,
        colorModulationQ24 = BigInt(columns(5)),
        fixedHfModulationQ24 = BigInt(columns(6)),
        fixedColorModulationQ24 = BigInt(columns(7)),
        quantizedXybColorModulationQ24 = BigInt(columns(9)),
        fixedQuantizedXybColorModulationQ24 = BigInt(columns(11)),
        inputQ8FixedHfModulationQ24 = BigInt(columns(12)),
        inputQ8FixedColorModulationQ24 = BigInt(columns(13))
      )
    }
  }

  private def generateFixture(
      pattern: String,
      width: Int,
      height: Int,
      distanceQ8: Int = 256
  ): (Seq[XybRow], Seq[ColorRow]) = {
    val xybCsv = Files.createTempFile(s"hjxl-aq-color-$pattern-xyb-", ".csv")
    val colorCsv = Files.createTempFile(s"hjxl-aq-color-$pattern-", ".csv")
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
        "--distance",
        (distanceQ8.toDouble / 256.0).toString,
        "--xyb-q12-csv",
        xybCsv.toString,
        "--aq-color-modulation-q24-csv",
        colorCsv.toString
      )
    )
    (readXybRows(xybCsv), readColorRows(colorCsv))
  }

  private def paddedWidth(width: Int): Int = (width + BlockDim - 1) / BlockDim * BlockDim

  private def xybBlock(
      rows: Seq[XybRow],
      width: Int,
      blockX: Int,
      blockY: Int
  ): (Seq[BigInt], Seq[BigInt], Seq[BigInt]) = {
    val stride = paddedWidth(width)
    val selected = for (localY <- 0 until BlockDim; localX <- 0 until BlockDim) yield
      rows((blockY * BlockDim + localY) * stride + blockX * BlockDim + localX)
    (
      selected.map(_.xybXQ12),
      selected.map(_.xybYQ12),
      selected.map(_.xybBQ12)
    )
  }

  private def originalRgb(rows: Seq[XybRow], width: Int, height: Int): Seq[XybRow] =
    rows.filter(row => row.x < width && row.y < height)

  private def pokeConfig(
      frameConfig: FrameConfig,
      width: Int,
      height: Int,
      distanceQ8: Int = 256
  ): Unit = {
    frameConfig.xsize.poke(width.U)
    frameConfig.ysize.poke(height.U)
    frameConfig.distanceQ8.poke(distanceQ8.U)
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

  private def pokeBlockInput(
      input: AqColorModulationBlockInput,
      seedQ24: BigInt,
      distanceQ8: Int,
      block: (Seq[BigInt], Seq[BigInt], Seq[BigInt])
  ): Unit = {
    input.seedQ24.poke(seedQ24.S)
    input.distanceQ8.poke(distanceQ8.U)
    for (sample <- 0 until SamplesPerBlock) {
      input.xybXQ12(sample).poke(block._1(sample).S)
      input.xybYQ12(sample).poke(block._2(sample).S)
      input.xybBQ12(sample).poke(block._3(sample).S)
    }
  }

  private def drivePreparedInput(
      dut: FramePreparedAqColorModulationTraceStage,
      seedQ24: BigInt,
      distanceQ8: Int,
      block: (Seq[BigInt], Seq[BigInt], Seq[BigInt])
  ): Unit = {
    dut.io.input.valid.poke(true.B)
    pokeBlockInput(dut.io.input.bits, seedQ24, distanceQ8, block)
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
    dut.io.input.valid.poke(false.B)
  }

  private def waitForPreparedOutput(
      dut: FramePreparedAqColorModulationTraceStage,
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

  private def signedTraceValue(value: BigInt): BigInt = BigInt(value.toInt)

  private def floatReferenceQ24(
      seedQ24: BigInt,
      distanceQ8: Int,
      block: (Seq[BigInt], Seq[BigInt], Seq[BigInt])
  ): BigInt = {
    val distance = (distanceQ8.toFloat / 256.0f).toFloat
    val strength = (2.177823400325309f * (1.0f - 0.25f * distance).toFloat).toFloat
    if (strength < 0.0f) {
      seedQ24
    } else {
      var redCoverage = 0.0f
      var blueCoverage = 0.0f
      for (sample <- 0 until SamplesPerBlock) {
        val x = (block._1(sample).toFloat / 4096.0f).toFloat
        val y = (block._2(sample).toFloat / 4096.0f).toFloat
        val b = (block._3(sample).toFloat / 4096.0f).toFloat
        val pixelX = math.max(0.0f, (x - 0.0073200141118951231f).toFloat)
        val pixelB = math.max(0.0f, (b - (y + 0.26973418507870539f).toFloat).toFloat)
        redCoverage = (redCoverage + math.min(pixelX, 0.019421555948474039f)).toFloat
        blueCoverage = (blueCoverage + math.min(pixelB, 0.086890611400405895f)).toFloat
      }
      val ratio = 30.610615782142737f
      val redStrength = (strength * 5.992297772961519f).toFloat
      val overallRed = (math.min(redCoverage, (ratio * 0.019421555948474039f).toFloat) *
        (redStrength / ratio).toFloat).toFloat
      val overallBlue = (math.min(blueCoverage, (ratio * 0.086890611400405895f).toFloat) *
        (strength / ratio).toFloat).toFloat
      val seed = (seedQ24.toFloat / (BigInt(1) << SeedFractionBits).toFloat).toFloat
      val result = (overallRed + overallBlue +
        (seed + (strength * -0.009174542291185913f).toFloat).toFloat).toFloat
      BigInt(Math.round(result.toDouble * (BigInt(1) << OutputFractionBits).toDouble))
    }
  }

  "AqColorModulationBlock matches its fixed model, float32 tolerance, and 64-cycle latency" in {
    val random = new Random(0x434f4c4f52L)
    val distancesQ8 = Seq(0, 64, 128, 256, 333, 512, 768, 1024, 1025, 2048)
    def randomCase(distanceQ8: Int) = {
      val samples = Seq.fill(3)(Seq.fill(SamplesPerBlock)(BigInt(random.nextInt(16385) - 8192)))
      (
        BigInt(random.nextInt(60000001) - 30000000),
        distanceQ8,
        (samples(0), samples(1), samples(2))
      )
    }
    val cases = distancesQ8.map(randomCase) ++ Seq.fill(8)(
      randomCase(distancesQ8(random.nextInt(distancesQ8.length)))
    ) ++ Seq(
      (
        MaximumSigned32,
        0,
        (Seq.fill(SamplesPerBlock)(BigInt(8192)), Seq.fill(SamplesPerBlock)(BigInt(-8192)), Seq.fill(SamplesPerBlock)(BigInt(8192)))
      ),
      (
        MinimumSigned32,
        0,
        (Seq.fill(SamplesPerBlock)(BigInt(0)), Seq.fill(SamplesPerBlock)(BigInt(0)), Seq.fill(SamplesPerBlock)(BigInt(0)))
      ),
      (
        BigInt(123456789),
        2048,
        (Seq.fill(SamplesPerBlock)(BigInt(8192)), Seq.fill(SamplesPerBlock)(BigInt(-8192)), Seq.fill(SamplesPerBlock)(BigInt(8192)))
      )
    )

    simulate(new AqColorModulationBlock) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.output.ready.poke(true.B)
      dut.clock.step()

      for (((seed, distanceQ8, block), caseIndex) <- cases.zipWithIndex) {
        val expected = modulateQ24(seed, distanceQ8, block._1, block._2, block._3)
        if (seed != MaximumSigned32 && seed != MinimumSigned32) {
          val floatReference = floatReferenceQ24(seed, distanceQ8, block)
          withClue(s"fixed-to-float case $caseIndex distanceQ8=$distanceQ8") {
            (expected - floatReference).abs must be <= BigInt(2048)
          }
        }
        dut.io.input.valid.poke(true.B)
        pokeBlockInput(dut.io.input.bits, seed, distanceQ8, block)
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
        dut.io.input.valid.poke(false.B)

        var cycles = 0
        while (dut.io.output.valid.peekValue().asBigInt == 0 && cycles <= evaluationCycles) {
          dut.clock.step()
          cycles += 1
        }
        withClue(s"color block case $caseIndex") {
          cycles mustBe evaluationCycles
          dut.io.output.bits.expect(expected.S)
        }
        if (caseIndex == 0) {
          dut.io.output.ready.poke(false.B)
          dut.clock.step(3)
          dut.io.output.valid.expect(true.B)
          dut.io.output.bits.expect(expected.S)
          dut.io.input.ready.expect(false.B)
          dut.io.output.ready.poke(true.B)
        }
        dut.clock.step()
      }
      dut.io.busy.expect(false.B)
    }
  }

  "FramePreparedAqColorModulationTraceStage matches independent fixtures" in {
    requireReferenceTools()
    val width = 17
    val height = 9
    val fixtures = Seq("constant", "gradient", "checkerboard", "impulse", "random").map {
      pattern => pattern -> generateFixture(pattern, width, height)
    }

    simulate(new FramePreparedAqColorModulationTraceStage(config)) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      pokeConfig(dut.io.config, width, height)
      dut.clock.step()

      for ((pattern, (xybRows, expectedRows)) <- fixtures) {
        pokeConfig(dut.io.config, width, height)
        for ((expected, outputIndex) <- expectedRows.zipWithIndex) {
          val block = xybBlock(xybRows, width, expected.blockX, expected.blockY)
          drivePreparedInput(
            dut,
            expected.inputQ8FixedHfModulationQ24,
            expected.distanceQ8,
            block
          )
          if (outputIndex == 0) {
            pokeConfig(dut.io.config, width = 0, height = 0, distanceQ8 = 0)
          }
          waitForPreparedOutput(dut, s"$pattern prepared color modulation $outputIndex")
          dut.io.trace.bits.stage.expect(TraceStage.AqColorModulation.U)
          dut.io.trace.bits.group.expect(0.U)
          dut.io.trace.bits.index.expect(expected.block.U)
          dut.io.trace.bits.value.expect(expected.inputQ8FixedColorModulationQ24.S)
          dut.io.traceLast.expect((outputIndex == expectedRows.length - 1).B)
          if (outputIndex == 0) {
            val held = dut.io.trace.bits.value.peekValue().asBigInt
            dut.io.trace.ready.poke(false.B)
            dut.clock.step(3)
            dut.io.trace.valid.expect(true.B)
            dut.io.trace.bits.value.expect(held)
            dut.io.input.ready.expect(false.B)
            dut.io.trace.ready.poke(true.B)
          }
          dut.clock.step()
        }
        pokeConfig(dut.io.config, width, height)
        dut.io.busy.expect(false.B)
        dut.io.overflow.expect(false.B)
      }

      pokeConfig(dut.io.config, width = config.maxFrameWidth + 1, height = 1)
      dut.clock.step()
      dut.io.overflow.expect(true.B)
      dut.io.input.ready.expect(false.B)
    }
  }

  "FrameAqColorModulationTraceStage reuses one XYB capture for cumulative RGB output" in {
    requireReferenceTools()
    val width = 17
    val height = 9
    val fixtures = Seq("constant", "gradient", "checkerboard", "impulse", "random").map {
      pattern => pattern -> generateFixture(pattern, width, height)
    }

    simulate(new FrameAqColorModulationTraceStage(config)) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      pokeConfig(dut.io.config, width, height)
      dut.clock.step()

      for ((pattern, (xybRows, expectedRows)) <- fixtures) {
        pokeConfig(dut.io.config, width, height)
        val rgb = originalRgb(xybRows, width, height)
        for ((row, inputIndex) <- rgb.zipWithIndex) {
          dut.io.input.valid.poke(true.B)
          dut.io.input.bits.x.poke(row.x.U)
          dut.io.input.bits.y.poke(row.y.U)
          dut.io.input.bits.r.poke(row.r.S)
          dut.io.input.bits.g.poke(row.g.S)
          dut.io.input.bits.b.poke(row.b.S)
          withClue(s"$pattern RGB input $inputIndex") {
            dut.io.input.ready.expect(true.B)
            dut.io.xybAccepted.valid.expect(true.B)
          }
          dut.clock.step()
          if (inputIndex == 0) {
            pokeConfig(dut.io.config, width = 0, height = 0, distanceQ8 = 0)
          }
        }
        dut.io.input.valid.poke(false.B)
        dut.io.xybAccepted.valid.expect(false.B)

        for ((expected, outputIndex) <- expectedRows.zipWithIndex) {
          var waitCycles = 0
          val limit = if (outputIndex == 0) 1400 else 400
          while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < limit) {
            dut.clock.step()
            waitCycles += 1
          }
          withClue(s"$pattern RGB color modulation $outputIndex") {
            waitCycles must be < limit
            dut.io.trace.bits.stage.expect(TraceStage.AqColorModulation.U)
            dut.io.trace.bits.group.expect(0.U)
            dut.io.trace.bits.index.expect(expected.block.U)
            val actual = signedTraceValue(dut.io.trace.bits.value.peekValue().asBigInt)
            val fixedReference = expected.inputQ8FixedColorModulationQ24
            val allowed = (fixedReference.abs / 50).max(BigInt(1) << 15)
            (actual - fixedReference).abs must be <= allowed
            (actual - expected.quantizedXybColorModulationQ24).abs must be <= allowed
            dut.io.traceLast.expect((outputIndex == expectedRows.length - 1).B)
          }
          if (pattern == "constant" && outputIndex == 0) {
            val held = dut.io.trace.bits.value.peekValue().asBigInt
            dut.io.trace.ready.poke(false.B)
            dut.clock.step(3)
            dut.io.trace.valid.expect(true.B)
            dut.io.trace.bits.index.expect(expected.block.U)
            dut.io.trace.bits.value.expect(held)
            dut.io.input.ready.expect(false.B)
            dut.io.trace.ready.poke(true.B)
          }
          dut.clock.step()
        }
        dut.io.trace.valid.expect(false.B)
        dut.io.busy.expect(false.B)
      }
    }
  }

  "FrameAqColorModulationTraceStage preserves color samples across a 64-pixel tile boundary" in {
    requireReferenceTools()
    val width = 65
    val height = 1
    val (xybRows, expectedRows) = generateFixture("gradient", width, height)
    val rgb = originalRgb(xybRows, width, height)
    expectedRows.length mustBe 9

    simulate(
      new FrameAqColorModulationTraceStage(HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 8))
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
        val limit = if (outputIndex == 0) 1500 else 400
        while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < limit) {
          dut.clock.step()
          waitCycles += 1
        }
        withClue(s"cross-tile color modulation $outputIndex") {
          waitCycles must be < limit
          dut.io.trace.bits.index.expect(outputIndex.U)
          expected.blockX mustBe outputIndex
          expected.blockY mustBe 0
          val actual = signedTraceValue(dut.io.trace.bits.value.peekValue().asBigInt)
          val reference = expected.inputQ8FixedColorModulationQ24
          val allowed = (reference.abs / 50).max(BigInt(1) << 15)
          (actual - reference).abs must be <= allowed
          dut.io.traceLast.expect((outputIndex == expectedRows.length - 1).B)
        }
        dut.clock.step()
      }
      dut.io.busy.expect(false.B)
    }
  }

  "FramePreparedAqColorModulationTraceStage preserves two-dimensional tiled order" in {
    requireReferenceTools()
    val width = 65
    val height = 65
    val (xybRows, expectedRows) = generateFixture("random", width, height, distanceQ8 = 512)
    expectedRows.length mustBe 9 * 9

    simulate(
      new FramePreparedAqColorModulationTraceStage(
        HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 72)
      )
    ) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      pokeConfig(dut.io.config, width, height, distanceQ8 = 512)
      dut.clock.step()

      for ((expected, outputIndex) <- expectedRows.zipWithIndex) {
        val block = xybBlock(xybRows, width, expected.blockX, expected.blockY)
        drivePreparedInput(
          dut,
          expected.inputQ8FixedHfModulationQ24,
          expected.distanceQ8,
          block
        )
        waitForPreparedOutput(dut, s"two-dimensional color modulation $outputIndex")
        dut.io.trace.bits.stage.expect(TraceStage.AqColorModulation.U)
        dut.io.trace.bits.index.expect(outputIndex.U)
        dut.io.trace.bits.value.expect(expected.inputQ8FixedColorModulationQ24.S)
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

class AqColorModulationElaborationSpec extends AnyFreeSpec with Matchers {
  "the RGB AQ color-modulation top emits one converter and sequential HF/color walkers" in {
    val targetDir = Files.createTempDirectory("hjxl-aq-color-modulation-elaborate-")
    ChiselStage.emitSystemVerilogFile(
      new FrameAqColorModulationTraceStage(HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 8)),
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
      "FrameAqColorModulationTraceStage.sv",
      "FrameAqModulationBlockStage.sv",
      "AqHfModulationBlock.sv",
      "AqColorModulationBlock.sv",
      "FrameAqNonlinearMaskTraceStage.sv",
      "FrameAqContrastTraceStage.sv",
      "RgbToXybApprox.sv"
    )
    val top = files("FrameAqColorModulationTraceStage.sv")
    top must include("module FrameAqColorModulationTraceStage")
    top must include("io_traceLast")
    top must include("io_busy")
    top must include("io_overflow")
    top must include("io_xybAccepted_valid")
    val color = files("AqColorModulationBlock.sv")
    color must include("module AqColorModulationBlock")
    color must include("sampleOrdinal")
    color must include("redCoverage")
    color must include("blueCoverage")
    color must not include " / "
    val allSystemVerilog = files.values.mkString("\n")
    val converterInstances = """RgbToXybApprox\s+\w+\s*\(""".r
      .findAllMatchIn(allSystemVerilog)
      .length
    converterInstances mustBe 1
    val schedulerInstances = """FrameAqModulationBlockStage\s+\w+\s*\(""".r
      .findAllMatchIn(allSystemVerilog)
      .length
    schedulerInstances mustBe 1
  }
}
