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

class AqHfModulationSpec extends AnyFreeSpec with Matchers with ChiselSim {
  import AqHfModulationFixedPoint._

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
      xybYQ12: BigInt
  )
  private case class HfRow(
      block: Int,
      blockX: Int,
      blockY: Int,
      fixedNonlinearMaskQ24: BigInt,
      fixedHfModulationQ24: BigInt,
      quantizedXybHfModulationQ24: BigInt,
      fixedQuantizedXybHfModulationQ24: BigInt,
      inputQ8FixedNonlinearMaskQ24: BigInt,
      inputQ8FixedHfModulationQ24: BigInt
  )

  private def requireReferenceTools(): Unit = {
    assume(
      Files.isDirectory(libjxlTinyRoot.resolve("python")),
      s"libjxl-tiny Python checkout not found at $libjxlTinyRoot"
    )
    assume(
      Process(Seq("python3", "-c", "import numpy")).! == 0,
      "python3 with numpy is required for AQ HF-modulation fixtures"
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
        xybYQ12 = BigInt(columns(7))
      )
    }
  }

  private def readHfRows(path: Path): Seq[HfRow] = {
    val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
    lines.get(0) mustBe
      "block,block_x,block_y,fuzzy_erosion_q16,nonlinear_mask_q24," +
        "hf_modulation_q24,fixed_nonlinear_mask_q24,fixed_hf_modulation_q24," +
        "quantized_xyb_fuzzy_erosion_q16,quantized_xyb_nonlinear_mask_q24," +
        "quantized_xyb_hf_modulation_q24,fixed_quantized_xyb_hf_modulation_q24," +
        "input_q8_fuzzy_erosion_q16,input_q8_fixed_nonlinear_mask_q24," +
        "input_q8_fixed_hf_modulation_q24"
    lines.toArray(new Array[String](0)).drop(1).toSeq.map { line =>
      val columns = line.split(",", -1)
      columns.length mustBe 15
      HfRow(
        block = columns(0).toInt,
        blockX = columns(1).toInt,
        blockY = columns(2).toInt,
        fixedNonlinearMaskQ24 = BigInt(columns(6)),
        fixedHfModulationQ24 = BigInt(columns(7)),
        quantizedXybHfModulationQ24 = BigInt(columns(10)),
        fixedQuantizedXybHfModulationQ24 = BigInt(columns(11)),
        inputQ8FixedNonlinearMaskQ24 = BigInt(columns(13)),
        inputQ8FixedHfModulationQ24 = BigInt(columns(14))
      )
    }
  }

  private def generateFixture(
      pattern: String,
      width: Int,
      height: Int
  ): (Seq[XybRow], Seq[HfRow]) = {
    val xybCsv = Files.createTempFile(s"hjxl-aq-hf-$pattern-xyb-", ".csv")
    val hfCsv = Files.createTempFile(s"hjxl-aq-hf-$pattern-", ".csv")
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
        "--aq-hf-modulation-q24-csv",
        hfCsv.toString
      )
    )
    (readXybRows(xybCsv), readHfRows(hfCsv))
  }

  private def paddedWidth(width: Int): Int = (width + BlockDim - 1) / BlockDim * BlockDim

  private def yBlock(rows: Seq[XybRow], width: Int, blockX: Int, blockY: Int): Seq[BigInt] = {
    val stride = paddedWidth(width)
    for (localY <- 0 until BlockDim; localX <- 0 until BlockDim) yield {
      rows((blockY * BlockDim + localY) * stride + blockX * BlockDim + localX).xybYQ12
    }
  }

  private def originalRgb(rows: Seq[XybRow], width: Int, height: Int): Seq[XybRow] =
    rows.filter(row => row.x < width && row.y < height)

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

  private def pokeBlockInput(
      input: AqHfModulationBlockInput,
      seedQ24: BigInt,
      block: Seq[BigInt]
  ): Unit = {
    block.length mustBe SamplesPerBlock
    input.seedQ24.poke(seedQ24.S)
    for ((sample, index) <- block.zipWithIndex) {
      input.xybYQ12(index).poke(sample.S)
    }
  }

  private def drivePreparedInput(
      dut: FramePreparedAqHfModulationTraceStage,
      seedQ24: BigInt,
      block: Seq[BigInt]
  ): Unit = {
    dut.io.input.valid.poke(true.B)
    pokeBlockInput(dut.io.input.bits, seedQ24, block)
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
    dut.io.input.valid.poke(false.B)
  }

  private def waitForPreparedOutput(
      dut: FramePreparedAqHfModulationTraceStage,
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

  private def floatReferenceQ24(seedQ24: BigInt, block: Seq[BigInt]): BigInt = {
    var total = 0.0f
    for (y <- 0 until BlockDim; x <- 0 until BlockDim) {
      val center = block(y * BlockDim + x).toFloat / (1 << XybFractionBits).toFloat
      if (x + 1 < BlockDim) {
        val right = block(y * BlockDim + x + 1).toFloat / (1 << XybFractionBits).toFloat
        total = (total + math.abs((center - right).toFloat)).toFloat
      }
      if (y + 1 < BlockDim) {
        val down = block((y + 1) * BlockDim + x).toFloat / (1 << XybFractionBits).toFloat
        total = (total + math.abs((center - down).toFloat)).toFloat
      }
    }
    val seed = seedQ24.toFloat / (BigInt(1) << SeedFractionBits).toFloat
    val result = (total * (-2.0052193233688884 / EdgesPerBlock).toFloat + seed).toFloat
    BigInt(Math.round(result.toDouble * (BigInt(1) << OutputFractionBits).toDouble))
  }

  "AqHfModulationBlock matches the Q24 model with exact 64-cycle latency" in {
    val horizontalRamp = Seq.tabulate(SamplesPerBlock)(index => BigInt(index % BlockDim) * 257)
    edgeTotalQ12(horizontalRamp) mustBe BigInt(56 * 257)
    val extremes = Seq.tabulate(SamplesPerBlock) { index =>
      if ((index & 1) == 0) MinimumSigned32 else MaximumSigned32
    }
    val random = new Random(7L)
    val randomCases = Seq.fill(32) {
      val seed = BigInt(random.nextInt(1 << 27) - (1 << 26))
      val block = Seq.fill(SamplesPerBlock)(BigInt(random.nextInt(1 << 16) - (1 << 15)))
      seed -> block
    }
    val cases = Seq(
      BigInt(0) -> Seq.fill(SamplesPerBlock)(BigInt(0)),
      BigInt(-1234567) -> horizontalRamp,
      MaximumSigned32 -> extremes,
      MinimumSigned32 -> extremes.reverse
    ) ++ randomCases

    simulate(new AqHfModulationBlock) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.output.ready.poke(false.B)
      dut.clock.step()

      for (((seed, block), caseIndex) <- cases.zipWithIndex) {
        dut.io.input.valid.poke(true.B)
        pokeBlockInput(dut.io.input.bits, seed, block)
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
        dut.io.input.valid.poke(false.B)

        var cycles = 0
        while (dut.io.output.valid.peekValue().asBigInt == 0 && cycles <= evaluationCycles) {
          dut.clock.step()
          cycles += 1
        }
        withClue(s"AQ HF modulation block case $caseIndex") {
          cycles mustBe evaluationCycles
          val expected = modulateQ24(seed, block)
          dut.io.output.bits.expect(expected.S)
          if (caseIndex >= 4) {
            (expected - floatReferenceQ24(seed, block)).abs must be <= BigInt(32)
          }
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

  "FramePreparedAqHfModulationTraceStage matches independent fixtures" in {
    requireReferenceTools()
    val width = 17
    val height = 9
    val fixtures = Seq("constant", "gradient", "checkerboard", "impulse", "random").map {
      pattern => pattern -> generateFixture(pattern, width, height)
    }

    simulate(new FramePreparedAqHfModulationTraceStage(config)) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      pokeConfig(dut.io.config, width, height)
      dut.clock.step()

      for ((pattern, (xybRows, expectedRows)) <- fixtures) {
        pokeConfig(dut.io.config, width, height)
        for ((expected, outputIndex) <- expectedRows.zipWithIndex) {
          val block = yBlock(xybRows, width, expected.blockX, expected.blockY)
          modulateQ24(expected.inputQ8FixedNonlinearMaskQ24, block) mustBe
            expected.inputQ8FixedHfModulationQ24
          drivePreparedInput(dut, expected.inputQ8FixedNonlinearMaskQ24, block)
          if (outputIndex == 0) {
            pokeConfig(dut.io.config, width = 0, height = 0)
          }
          waitForPreparedOutput(dut, s"$pattern prepared HF modulation $outputIndex")
          dut.io.trace.bits.stage.expect(TraceStage.AqHfModulation.U)
          dut.io.trace.bits.group.expect(0.U)
          dut.io.trace.bits.index.expect(expected.block.U)
          dut.io.trace.bits.value.expect(expected.inputQ8FixedHfModulationQ24.S)
          dut.io.traceLast.expect((outputIndex == expectedRows.length - 1).B)

          val stable = signedTraceValue(dut.io.trace.bits.value.peekValue().asBigInt)
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

  "FrameAqHfModulationTraceStage reuses XYB and emits cumulative RGB-path seeds" in {
    requireReferenceTools()
    val width = 17
    val height = 9
    val fixtures = Seq("constant", "gradient", "checkerboard", "impulse", "random").map {
      pattern => pattern -> generateFixture(pattern, width, height)
    }

    simulate(new FrameAqHfModulationTraceStage(config)) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      pokeConfig(dut.io.config, width, height)
      dut.clock.step()

      for ((pattern, (xybRows, expectedRows)) <- fixtures) {
        pokeConfig(dut.io.config, width, height)
        val rgb = originalRgb(xybRows, width, height)
        rgb.length mustBe width * height
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
            pokeConfig(dut.io.config, width = 0, height = 0)
          }
        }
        dut.io.input.valid.poke(false.B)
        dut.io.xybAccepted.valid.expect(false.B)

        for ((expected, outputIndex) <- expectedRows.zipWithIndex) {
          var waitCycles = 0
          val limit = if (outputIndex == 0) 1000 else 300
          while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < limit) {
            dut.clock.step()
            waitCycles += 1
          }
          withClue(s"$pattern RGB HF modulation $outputIndex") {
            waitCycles must be < limit
            dut.io.trace.bits.stage.expect(TraceStage.AqHfModulation.U)
            dut.io.trace.bits.group.expect(0.U)
            dut.io.trace.bits.index.expect(expected.block.U)
            val actual = signedTraceValue(dut.io.trace.bits.value.peekValue().asBigInt)
            val fixedReference = expected.inputQ8FixedHfModulationQ24
            val allowed = (fixedReference.abs / 50).max(BigInt(1) << 15)
            (actual - fixedReference).abs must be <= allowed
            (actual - expected.quantizedXybHfModulationQ24).abs must be <= allowed
            dut.io.traceLast.expect((outputIndex == expectedRows.length - 1).B)
          }
          dut.clock.step()
        }
        dut.io.trace.valid.expect(false.B)
        dut.io.busy.expect(false.B)
      }
    }
  }

  "FrameAqHfModulationTraceStage preserves Y edges across a 64-pixel tile boundary" in {
    requireReferenceTools()
    val width = 65
    val height = 1
    val (xybRows, expectedRows) = generateFixture("gradient", width, height)
    val rgb = originalRgb(xybRows, width, height)
    expectedRows.length mustBe 9

    simulate(
      new FrameAqHfModulationTraceStage(HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 8))
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
        val limit = if (outputIndex == 0) 1200 else 300
        while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < limit) {
          dut.clock.step()
          waitCycles += 1
        }
        withClue(s"cross-tile HF modulation $outputIndex") {
          waitCycles must be < limit
          dut.io.trace.bits.index.expect(outputIndex.U)
          expected.blockX mustBe outputIndex
          expected.blockY mustBe 0
          val actual = signedTraceValue(dut.io.trace.bits.value.peekValue().asBigInt)
          val reference = expected.inputQ8FixedHfModulationQ24
          val allowed = (reference.abs / 50).max(BigInt(1) << 15)
          (actual - reference).abs must be <= allowed
          dut.io.traceLast.expect((outputIndex == expectedRows.length - 1).B)
        }
        dut.clock.step()
      }
      dut.io.busy.expect(false.B)
    }
  }

  "FramePreparedAqHfModulationTraceStage preserves two-dimensional tiled order" in {
    requireReferenceTools()
    val width = 65
    val height = 65
    val (xybRows, expectedRows) = generateFixture("random", width, height)
    expectedRows.length mustBe 9 * 9

    simulate(
      new FramePreparedAqHfModulationTraceStage(
        HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 72)
      )
    ) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      pokeConfig(dut.io.config, width, height)
      dut.clock.step()

      for ((expected, outputIndex) <- expectedRows.zipWithIndex) {
        val block = yBlock(xybRows, width, expected.blockX, expected.blockY)
        drivePreparedInput(dut, expected.inputQ8FixedNonlinearMaskQ24, block)
        waitForPreparedOutput(dut, s"two-dimensional HF modulation $outputIndex")
        dut.io.trace.bits.stage.expect(TraceStage.AqHfModulation.U)
        dut.io.trace.bits.index.expect(outputIndex.U)
        dut.io.trace.bits.value.expect(expected.inputQ8FixedHfModulationQ24.S)
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

class AqHfModulationElaborationSpec extends AnyFreeSpec with Matchers {
  "the RGB AQ HF-modulation top emits one converter and a sequential edge walker" in {
    val targetDir = Files.createTempDirectory("hjxl-aq-hf-modulation-elaborate-")
    ChiselStage.emitSystemVerilogFile(
      new FrameAqHfModulationTraceStage(HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 8)),
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
      "FrameAqHfModulationTraceStage.sv",
      "FrameAqModulationBlockStage.sv",
      "AqHfModulationBlock.sv",
      "FrameAqNonlinearMaskTraceStage.sv",
      "FrameAqContrastTraceStage.sv",
      "RgbToXybApprox.sv"
    )
    val top = files("FrameAqHfModulationTraceStage.sv")
    top must include("module FrameAqHfModulationTraceStage")
    top must include("io_traceLast")
    top must include("io_busy")
    top must include("io_overflow")
    top must include("io_xybAccepted_valid")
    val block = files("AqHfModulationBlock.sv")
    block must include("module AqHfModulationBlock")
    block must include("sampleOrdinal")
    block must include("edgeTotal")
    block must not include " / "
    val scheduler = files("FrameAqModulationBlockStage.sv")
    scheduler must include("xybY")
    val allSystemVerilog = files.values.mkString("\n")
    val converterInstances = """RgbToXybApprox\s+\w+\s*\(""".r
      .findAllMatchIn(allSystemVerilog)
      .length
    converterInstances mustBe 1
  }
}
