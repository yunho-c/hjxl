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

class AqGammaModulationSpec extends AnyFreeSpec with Matchers with ChiselSim {
  import AqGammaModulationFixedPoint._

  private val config = HjxlConfig(maxFrameWidth = 24, maxFrameHeight = 16)
  private val libjxlTinyRoot =
    Path.of(sys.env.getOrElse("LIBJXL_TINY", "/Users/yunhocho/GitHub/libjxl-tiny"))
  private val evaluationCycles = SamplesPerBlock * 2

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
  private case class GammaRow(
      block: Int,
      blockX: Int,
      blockY: Int,
      distanceQ8: Int,
      gammaModulationQ24: BigInt,
      fixedColorModulationQ24: BigInt,
      fixedGammaModulationQ24: BigInt,
      quantizedXybGammaModulationQ24: BigInt,
      fixedQuantizedXybGammaModulationQ24: BigInt,
      inputQ8FixedColorModulationQ24: BigInt,
      inputQ8FixedGammaModulationQ24: BigInt
  )

  private def requireReferenceTools(): Unit = {
    assume(
      Files.isDirectory(libjxlTinyRoot.resolve("python")),
      s"libjxl-tiny Python checkout not found at $libjxlTinyRoot"
    )
    assume(
      Process(Seq("python3", "-c", "import numpy")).! == 0,
      "python3 with numpy is required for AQ gamma-modulation fixtures"
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
    lines.asScala.drop(1).map { line =>
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
    }.toSeq
  }

  private def readGammaRows(path: Path): Seq[GammaRow] = {
    val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
    lines.get(0) mustBe
      "block,block_x,block_y,distance_q8,color_modulation_q24," +
        "gamma_modulation_q24,fixed_color_modulation_q24," +
        "fixed_gamma_modulation_q24,quantized_xyb_gamma_modulation_q24," +
        "fixed_quantized_xyb_gamma_modulation_q24," +
        "input_q8_fixed_color_modulation_q24,input_q8_fixed_gamma_modulation_q24"
    lines.asScala.drop(1).map { line =>
      val columns = line.split(",", -1)
      columns.length mustBe 12
      GammaRow(
        block = columns(0).toInt,
        blockX = columns(1).toInt,
        blockY = columns(2).toInt,
        distanceQ8 = columns(3).toInt,
        gammaModulationQ24 = BigInt(columns(5)),
        fixedColorModulationQ24 = BigInt(columns(6)),
        fixedGammaModulationQ24 = BigInt(columns(7)),
        quantizedXybGammaModulationQ24 = BigInt(columns(8)),
        fixedQuantizedXybGammaModulationQ24 = BigInt(columns(9)),
        inputQ8FixedColorModulationQ24 = BigInt(columns(10)),
        inputQ8FixedGammaModulationQ24 = BigInt(columns(11))
      )
    }.toSeq
  }

  private def generateFixture(
      pattern: String,
      width: Int,
      height: Int,
      distanceQ8: Int = 256
  ): (Seq[XybRow], Seq[GammaRow]) = {
    val xybCsv = Files.createTempFile(s"hjxl-aq-gamma-$pattern-xyb-", ".csv")
    val gammaCsv = Files.createTempFile(s"hjxl-aq-gamma-$pattern-", ".csv")
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
        "--aq-gamma-modulation-q24-csv",
        gammaCsv.toString
      )
    )
    (readXybRows(xybCsv), readGammaRows(gammaCsv))
  }

  private def paddedWidth(width: Int): Int = (width + BlockDim - 1) / BlockDim * BlockDim

  private def xybBlock(
      rows: Seq[XybRow],
      width: Int,
      blockX: Int,
      blockY: Int
  ): (Seq[BigInt], Seq[BigInt]) = {
    val stride = paddedWidth(width)
    val selected = for (localY <- 0 until BlockDim; localX <- 0 until BlockDim) yield
      rows((blockY * BlockDim + localY) * stride + blockX * BlockDim + localX)
    (selected.map(_.xybXQ12), selected.map(_.xybYQ12))
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
      input: AqGammaModulationBlockInput,
      seedQ24: BigInt,
      block: (Seq[BigInt], Seq[BigInt])
  ): Unit = {
    input.seedQ24.poke(seedQ24.S)
    for (sample <- 0 until SamplesPerBlock) {
      input.xybXQ12(sample).poke(block._1(sample).S)
      input.xybYQ12(sample).poke(block._2(sample).S)
    }
  }

  private def drivePreparedInput(
      dut: FramePreparedAqGammaModulationTraceStage,
      seedQ24: BigInt,
      block: (Seq[BigInt], Seq[BigInt])
  ): Unit = {
    dut.io.input.valid.poke(true.B)
    pokeBlockInput(dut.io.input.bits, seedQ24, block)
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
    dut.io.input.valid.poke(false.B)
  }

  private def waitForPreparedOutput(
      dut: FramePreparedAqGammaModulationTraceStage,
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

  private def inverseRatioFloat(value: Float): Float = {
    val epsilon = 1e-2f
    val sgMul = 226.0480446705883f
    val sgMul2 = (1.0 / 73.377132366608819).toFloat
    val log2 = 0.693147181f
    val returnMul = (sgMul2 * 18.6580932135f * log2).toFloat
    val normalized = math.max(value, 0.0f)
    val squared = (normalized * normalized).toFloat
    val numerator = ((returnMul * 3.0f * sgMul).toFloat * squared + epsilon).toFloat
    val denominator =
      ((log2 * sgMul).toFloat * normalized * squared + (7.14672470003f * log2 + epsilon).toFloat).toFloat
    (numerator / denominator).toFloat
  }

  private def fastLog2Float(value: Float): Float = {
    val p = Seq(-1.8503833400518310e-06f, 1.4287160470083755f, 0.74245873327820566f)
    val q = Seq(0.99032814277590719f, 1.0096718572241148f, 0.17409343003366823f)
    val bits = java.lang.Float.floatToRawIntBits(value)
    val exponent = (bits - 0x3f2aaaab) >> 23
    val mantissa = java.lang.Float.intBitsToFloat(bits - (exponent << 23))
    val reduced = (mantissa - 1.0f).toFloat
    var numerator = (p(2) * reduced + p(1)).toFloat
    var denominator = (q(2) * reduced + q(1)).toFloat
    numerator = (numerator * reduced + p(0)).toFloat
    denominator = (denominator * reduced + q(0)).toFloat
    (numerator / denominator + exponent.toFloat).toFloat
  }

  private def floatReferenceQ24(
      seedQ24: BigInt,
      block: (Seq[BigInt], Seq[BigInt])
  ): BigInt = {
    var ratio = 0.0f
    for (sample <- 0 until SamplesPerBlock) {
      val x = (block._1(sample).toFloat / 4096.0f).toFloat
      val y = (block._2(sample).toFloat / 4096.0f).toFloat
      val inY = (y + 0.16f).toFloat
      val red = inverseRatioFloat((inY - x).toFloat)
      val green = inverseRatioFloat((inY + x).toFloat)
      ratio = (ratio + (0.5f * (red + green).toFloat).toFloat).toFloat
    }
    ratio = (ratio * (1.0f / 64.0f)).toFloat
    val multiplier = (-0.15526878023684174f * 0.693147180559945f).toFloat
    val seed = (seedQ24.toFloat / (BigInt(1) << SeedFractionBits).toFloat).toFloat
    val result = (seed + (multiplier * fastLog2Float(ratio)).toFloat).toFloat
    BigInt(Math.round(result.toDouble * (BigInt(1) << OutputFractionBits).toDouble))
  }

  private def signedTraceValue(value: BigInt): BigInt = BigInt(value.toInt)

  "AqInverseGammaRatioQ20 matches its piecewise fixed model and clamps explicitly" in {
    val random = new Random(0x524154494fL)
    val seamValues =
      (0 to FineLimitQ12) ++
        (FineLimitQ12 to CoarseLimitQ12 by (1 << CoarseStepBits)).flatMap(v => Seq(v - 1, v, v + 1)) ++
        (CoarseLimitQ12 to MaximumRatioInputQ12 by (1 << HdrStepBits)).flatMap(v => Seq(v - 1, v, v + 1)) ++
        Seq.fill(1000)(random.nextInt(MaximumRatioInputQ12 + 1)) ++
        Seq(-100000, MaximumRatioInputQ12 + 1, 1000000)
    simulate(new AqInverseGammaRatioQ20) { dut =>
      for (input <- seamValues.distinct) {
        val expected = inverseGammaRatioQ20(input)
        dut.io.valueQ12.poke(input.S)
        dut.io.output.expect(expected.U)
        val clamped = math.max(0, math.min(MaximumRatioInputQ12, input))
        val floatQ20 = BigInt(
          Math.round(inverseRatioFloat(clamped.toFloat / 4096.0f).toDouble * (1 << RatioFractionBits))
        )
        withClue(s"inverse ratio inputQ12=$input") {
          (BigInt(expected) - floatQ20).abs must be <= BigInt(96)
        }
      }
    }
  }

  "AqFastLog2Q20 matches its normalized fixed model across ratio-table seams" in {
    val random = new Random(0x4c4f4732L)
    val tableValues = (FineRatioTable ++ CoarseRatioTable ++ HdrRatioTable).distinct
    val samples = tableValues ++ Seq.fill(2000)(
      MinimumRatioQ20 + random.nextInt(MaximumRatioQ20 - MinimumRatioQ20 + 1)
    )
    simulate(new AqFastLog2Q20) { dut =>
      for (input <- samples) {
        val expected = fastLog2Q20(input)
        dut.io.input.poke(input.U)
        dut.io.output.expect(expected.S)
        val floatQ20 = BigInt(
          Math.round(fastLog2Float(input.toFloat / (1 << RatioFractionBits)).toDouble * (1 << LogFractionBits))
        )
        withClue(s"fast log inputQ20=$input") {
          (expected - floatQ20).abs must be <= BigInt(8)
        }
      }
    }
  }

  "AqGammaModulationBlock matches its fixed model, float32 tolerance, and 128-cycle latency" in {
    val random = new Random(0x47414d4d41L)
    def constantCase(responseQ12: Int, seed: BigInt = 0) =
      (seed, (Seq.fill(SamplesPerBlock)(BigInt(0)), Seq.fill(SamplesPerBlock)(BigInt(responseQ12 - InputOffsetQ12))))
    def randomCase() = {
      val first = Seq.fill(SamplesPerBlock)(random.nextInt(21640) - 639)
      val second = Seq.fill(SamplesPerBlock)(random.nextInt(21640) - 639)
      val x = (first zip second).map { case (left, right) => BigInt((left - right) >> 1) }
      val y = (first zip second).map { case (left, right) => BigInt((left + right) >> 1) }
      (BigInt(random.nextInt(60000001) - 30000000), (x, y))
    }
    val cases = Seq(
      constantCase(0),
      constantCase(1),
      constantCase(FineLimitQ12),
      constantCase(FineLimitQ12 + 1),
      constantCase(CoarseLimitQ12),
      constantCase(CoarseLimitQ12 + 1),
      constantCase(MaximumRatioInputQ12),
      constantCase(0, MaximumSigned32),
      constantCase(MaximumRatioInputQ12, MinimumSigned32)
    ) ++ Seq.fill(24)(randomCase())

    simulate(new AqGammaModulationBlock) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.output.ready.poke(true.B)
      dut.clock.step()

      for (((seed, block), caseIndex) <- cases.zipWithIndex) {
        val expected = modulateQ24(seed, block._1, block._2)
        if (seed != MaximumSigned32 && seed != MinimumSigned32) {
          val floatReference = floatReferenceQ24(seed, block)
          withClue(s"fixed-to-float gamma case $caseIndex") {
            (expected - floatReference).abs must be <= BigInt(32768)
          }
        }
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
        withClue(s"gamma block case $caseIndex") {
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

  "FramePreparedAqGammaModulationTraceStage matches independent fixtures" in {
    requireReferenceTools()
    val width = 17
    val height = 9
    val fixtures = Seq("constant", "gradient", "checkerboard", "impulse", "random").map {
      pattern => pattern -> generateFixture(pattern, width, height)
    }

    simulate(new FramePreparedAqGammaModulationTraceStage(config)) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      pokeConfig(dut.io.config, width, height)
      dut.clock.step()

      for ((pattern, (xybRows, expectedRows)) <- fixtures) {
        pokeConfig(dut.io.config, width, height)
        for ((expected, outputIndex) <- expectedRows.zipWithIndex) {
          val block = xybBlock(xybRows, width, expected.blockX, expected.blockY)
          drivePreparedInput(dut, expected.inputQ8FixedColorModulationQ24, block)
          if (outputIndex == 0) {
            pokeConfig(dut.io.config, width = 0, height = 0, distanceQ8 = 0)
          }
          waitForPreparedOutput(dut, s"$pattern prepared gamma modulation $outputIndex")
          dut.io.trace.bits.stage.expect(TraceStage.AqGammaModulation.U)
          dut.io.trace.bits.group.expect(0.U)
          dut.io.trace.bits.index.expect(expected.block.U)
          dut.io.trace.bits.value.expect(expected.inputQ8FixedGammaModulationQ24.S)
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

  "FrameAqGammaModulationTraceStage reuses one XYB capture for cumulative RGB output" in {
    requireReferenceTools()
    val width = 17
    val height = 9
    val fixtures = Seq("constant", "gradient", "checkerboard", "impulse", "random").map {
      pattern => pattern -> generateFixture(pattern, width, height)
    }

    simulate(new FrameAqGammaModulationTraceStage(config)) { dut =>
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
          val limit = if (outputIndex == 0) 1800 else 600
          while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < limit) {
            dut.clock.step()
            waitCycles += 1
          }
          withClue(s"$pattern RGB gamma modulation $outputIndex") {
            waitCycles must be < limit
            dut.io.trace.bits.stage.expect(TraceStage.AqGammaModulation.U)
            dut.io.trace.bits.group.expect(0.U)
            dut.io.trace.bits.index.expect(expected.block.U)
            val actual = signedTraceValue(dut.io.trace.bits.value.peekValue().asBigInt)
            val fixedReference = expected.inputQ8FixedGammaModulationQ24
            val allowed = (fixedReference.abs / 50).max(BigInt(1) << 15)
            (actual - fixedReference).abs must be <= allowed
            (actual - expected.quantizedXybGammaModulationQ24).abs must be <= allowed
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

  "FrameAqGammaModulationTraceStage preserves samples across a 64-pixel tile boundary" in {
    requireReferenceTools()
    val width = 65
    val height = 1
    val (xybRows, expectedRows) = generateFixture("gradient", width, height)
    val rgb = originalRgb(xybRows, width, height)
    expectedRows.length mustBe 9

    simulate(
      new FrameAqGammaModulationTraceStage(HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 8))
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
        val limit = if (outputIndex == 0) 1900 else 600
        while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < limit) {
          dut.clock.step()
          waitCycles += 1
        }
        withClue(s"cross-tile gamma modulation $outputIndex") {
          waitCycles must be < limit
          dut.io.trace.bits.index.expect(outputIndex.U)
          expected.blockX mustBe outputIndex
          expected.blockY mustBe 0
          val actual = signedTraceValue(dut.io.trace.bits.value.peekValue().asBigInt)
          val reference = expected.inputQ8FixedGammaModulationQ24
          val allowed = (reference.abs / 50).max(BigInt(1) << 15)
          (actual - reference).abs must be <= allowed
          dut.io.traceLast.expect((outputIndex == expectedRows.length - 1).B)
        }
        dut.clock.step()
      }
      dut.io.busy.expect(false.B)
    }
  }

  "FramePreparedAqGammaModulationTraceStage preserves two-dimensional tiled order" in {
    requireReferenceTools()
    val width = 65
    val height = 65
    val (xybRows, expectedRows) = generateFixture("random", width, height, distanceQ8 = 512)
    expectedRows.length mustBe 9 * 9

    simulate(
      new FramePreparedAqGammaModulationTraceStage(
        HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 72)
      )
    ) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      pokeConfig(dut.io.config, width, height, distanceQ8 = 512)
      dut.clock.step()

      for ((expected, outputIndex) <- expectedRows.zipWithIndex) {
        val block = xybBlock(xybRows, width, expected.blockX, expected.blockY)
        drivePreparedInput(dut, expected.inputQ8FixedColorModulationQ24, block)
        waitForPreparedOutput(dut, s"two-dimensional gamma modulation $outputIndex")
        dut.io.trace.bits.stage.expect(TraceStage.AqGammaModulation.U)
        dut.io.trace.bits.index.expect(outputIndex.U)
        dut.io.trace.bits.value.expect(expected.inputQ8FixedGammaModulationQ24.S)
        expected.blockX mustBe outputIndex % 9
        expected.blockY mustBe outputIndex / 9
        dut.io.traceLast.expect((outputIndex == expectedRows.length - 1).B)
        dut.clock.step()
      }
      dut.io.busy.expect(false.B)
    }
  }
}

class AqGammaModulationElaborationSpec extends AnyFreeSpec with Matchers {
  "FrameAqGammaModulationTraceStage elaborates one sequential cumulative chain" in {
    val outputDir = Files.createTempDirectory("hjxl-aq-gamma-elaboration-")
    ChiselStage.emitSystemVerilogFile(
      new FrameAqGammaModulationTraceStage(),
      args = Array("--target-dir", outputDir.toString),
      firtoolOpts = Array(
        "-disable-all-randomization",
        "-strip-debug-info",
        "-default-layer-specialization=enable"
      )
    )
    val files = Files
      .walk(outputDir)
      .iterator()
      .asScala
      .filter(path => path.getFileName.toString.endsWith(".sv"))
      .map(path => path.getFileName.toString -> Files.readString(path))
      .toMap

    files.keySet must contain allOf (
      "FrameAqGammaModulationTraceStage.sv",
      "FrameAqModulationBlockStage.sv",
      "AqHfColorModulationBlockPipeline.sv",
      "AqHfModulationBlock.sv",
      "AqColorModulationBlock.sv",
      "AqGammaModulationBlock.sv",
      "AqInverseGammaRatioQ20.sv",
      "AqFastLog2Q20.sv",
      "FrameAqNonlinearMaskTraceStage.sv",
      "FrameAqContrastTraceStage.sv",
      "RgbToXybApprox.sv"
    )
    files("AqGammaModulationBlock.sv") must include("selectGreen")
    files("AqGammaModulationBlock.sv") must include("ratioSum")
    files("AqGammaModulationBlock.sv") must not include " / "
    files("AqInverseGammaRatioQ20.sv") must not include " / "
    files("AqFastLog2Q20.sv") must not include " / "
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
