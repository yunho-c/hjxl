// See README.md for license details.

package hjxl

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util.DecoupledIO
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

class AqFinalModulationSpec extends AnyFreeSpec with Matchers with ChiselSim {
  import AqFinalModulationFixedPoint._

  private val config = HjxlConfig(maxFrameWidth = 24, maxFrameHeight = 16)
  private val libjxlTinyRoot =
    Path.of(sys.env.getOrElse("LIBJXL_TINY", "/Users/yunhocho/GitHub/libjxl-tiny"))

  private case class XybRow(x: Int, y: Int, r: Int, g: Int, b: Int)
  private case class FinalRow(
      block: Int,
      blockX: Int,
      blockY: Int,
      distanceQ8: Int,
      gammaQ24: BigInt,
      aqMapQ24: BigInt,
      fixedGammaQ24: BigInt,
      fixedMapQ24: BigInt,
      quantizedXybMapQ24: BigInt,
      fixedQuantizedXybMapQ24: BigInt,
      inputQ8FixedGammaQ24: BigInt,
      inputQ8FixedMapQ24: BigInt,
      scaleQ24: BigInt,
      dampenQ24: BigInt,
      invGlobalScaleQ24: BigInt,
      referenceRawQuant: Int,
      fixedRawQuant: Int,
      inputQ8FixedRawQuant: Int
  )

  private def requireReferenceTools(): Unit = {
    assume(
      Files.isDirectory(libjxlTinyRoot.resolve("python")),
      s"libjxl-tiny Python checkout not found at $libjxlTinyRoot"
    )
    assume(
      Process(Seq("python3", "-c", "import numpy")).! == 0,
      "python3 with numpy is required for AQ final-map fixtures"
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
        b = columns(5).toInt
      )
    }.toSeq
  }

  private def readFinalRows(path: Path): Seq[FinalRow] = {
    val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
    lines.get(0) mustBe
      "block,block_x,block_y,distance_q8,gamma_modulation_q24,aq_map_q24," +
        "fixed_gamma_modulation_q24,fixed_aq_map_q24,quantized_xyb_aq_map_q24," +
        "fixed_quantized_xyb_aq_map_q24,input_q8_fixed_gamma_modulation_q24," +
        "input_q8_fixed_aq_map_q24,aq_scale_q24,aq_dampen_q24," +
        "inv_global_scale_q24,reference_raw_quant,fixed_raw_quant," +
        "input_q8_fixed_raw_quant"
    lines.asScala.drop(1).map { line =>
      val columns = line.split(",", -1)
      columns.length mustBe 18
      FinalRow(
        block = columns(0).toInt,
        blockX = columns(1).toInt,
        blockY = columns(2).toInt,
        distanceQ8 = columns(3).toInt,
        gammaQ24 = BigInt(columns(4)),
        aqMapQ24 = BigInt(columns(5)),
        fixedGammaQ24 = BigInt(columns(6)),
        fixedMapQ24 = BigInt(columns(7)),
        quantizedXybMapQ24 = BigInt(columns(8)),
        fixedQuantizedXybMapQ24 = BigInt(columns(9)),
        inputQ8FixedGammaQ24 = BigInt(columns(10)),
        inputQ8FixedMapQ24 = BigInt(columns(11)),
        scaleQ24 = BigInt(columns(12)),
        dampenQ24 = BigInt(columns(13)),
        invGlobalScaleQ24 = BigInt(columns(14)),
        referenceRawQuant = columns(15).toInt,
        fixedRawQuant = columns(16).toInt,
        inputQ8FixedRawQuant = columns(17).toInt
      )
    }.toSeq
  }

  private def generateFixture(
      pattern: String,
      width: Int,
      height: Int,
      distanceQ8: Int = 256
  ): (Seq[XybRow], Seq[FinalRow]) = {
    val xybCsv = Files.createTempFile(s"hjxl-aq-final-$pattern-xyb-", ".csv")
    val finalCsv = Files.createTempFile(s"hjxl-aq-final-$pattern-", ".csv")
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
        "--aq-final-map-q24-csv",
        finalCsv.toString
      )
    )
    readXybRows(xybCsv) -> readFinalRows(finalCsv)
  }

  private def pokeConfig(
      frameConfig: FrameConfig,
      width: Int,
      height: Int,
      distanceQ8: Int,
      fixedRawQuant: Int = 0,
      aqTraceFlags: Boolean = true
  ): Unit = {
    frameConfig.xsize.poke(width.U)
    frameConfig.ysize.poke(height.U)
    frameConfig.distanceQ8.poke(distanceQ8.U)
    frameConfig.fixedPointScale.poke(0.U)
    frameConfig.fixedInvQacQ16.poke(0.U)
    frameConfig.fixedRawQuant.poke(fixedRawQuant.U)
    frameConfig.fixedYtox.poke(0.S)
    frameConfig.fixedYtob.poke(0.S)
    frameConfig.enableXyb.poke(aqTraceFlags.B)
    frameConfig.enableDct.poke(false.B)
    frameConfig.enableQuant.poke(true.B)
    frameConfig.enableTokenize.poke(false.B)
    frameConfig.tokenSelect.poke(
      (if (aqTraceFlags) TokenTraceSelect.AqContrast else TokenTraceSelect.Dc).U
    )
  }

  private def driveRgb(
      input: DecoupledIO[RgbPixel],
      clock: Clock,
      rows: Seq[XybRow],
      width: Int,
      height: Int
  ): Unit = {
    val pixels = rows.filter(row => row.x < width && row.y < height)
    for ((row, index) <- pixels.zipWithIndex) {
      input.valid.poke(true.B)
      input.bits.x.poke(row.x.U)
      input.bits.y.poke(row.y.U)
      input.bits.r.poke(row.r.S)
      input.bits.g.poke(row.g.S)
      input.bits.b.poke(row.b.S)
      withClue(s"RGB input $index") {
        input.ready.expect(true.B)
      }
      clock.step()
    }
    input.valid.poke(false.B)
  }

  private def fastPow2Float(value: Float): Float = {
    val floorValue = math.floor(value.toDouble).toInt
    val exponent = java.lang.Float.intBitsToFloat((floorValue + 127) << 23)
    val fraction = (value - floorValue.toFloat).toFloat
    var numerator = (fraction + 1.01749063e1f).toFloat
    numerator = (numerator * fraction + 4.88687798e1f).toFloat
    numerator = (numerator * fraction + 9.85506591e1f).toFloat
    numerator = (numerator * exponent).toFloat
    var denominator = (fraction * 2.10242958e-1f - 2.22328856e-2f).toFloat
    denominator = (denominator * fraction - 1.94414990e1f).toFloat
    denominator = (denominator * fraction + 9.85506633e1f).toFloat
    (numerator / denominator).toFloat
  }

  "AqFastExpQ24 matches its fixed model across normalized exponent seams" in {
    val random = new Random(0x45585032L)
    val seams = for {
      integer <- -8 to 7
      fraction <- Seq(-1, 0, 1, (1 << 16) - 1, 1 << 16, (1 << 16) + 1)
    } yield BigInt(integer) * OneQ24 + fraction
    val samples =
      seams ++ Seq(BigInt(Int.MinValue), BigInt(Int.MaxValue)) ++
        Seq.fill(3000)(BigInt(random.nextInt()))

    simulate(new AqFastExpQ24) { dut =>
      for ((seed, index) <- samples.zipWithIndex) {
        val expected = fastExpQ24(seed)
        dut.io.seedQ24.poke(seed.S)
        dut.io.outputQ24.expect(expected.U)
        if (seed >= -8 * OneQ24 && seed <= 4 * OneQ24) {
          val seedFloat = (seed.toFloat / OneQ24.toFloat).toFloat
          val reference = BigInt(
            Math.round(
              fastPow2Float((seedFloat * 1.442695041f).toFloat).toDouble * OneQ24.toDouble
            )
          )
          withClue(s"fast exp sample $index seedQ24=$seed") {
            (expected - reference).abs must be <= BigInt(2048)
          }
        }
      }
    }
  }

  "AqFinalModulationBlock matches fixed and float oracles with stable backpressure" in {
    requireReferenceTools()
    val rows = Seq(64, 128, 256, 512, 1024, 2048, 1792, 3584).flatMap { distanceQ8 =>
      generateFixture("random", 17, 9, distanceQ8)._2.take(2)
    }

    simulate(new AqFinalModulationBlock) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.output.ready.poke(true.B)
      dut.clock.step()
      for ((row, index) <- rows.zipWithIndex) {
        val expected = modulateQ24(row.fixedGammaQ24, row.scaleQ24, row.dampenQ24)
        expected mustBe row.fixedMapQ24
        dut.io.input.bits.seedQ24.poke(row.fixedGammaQ24.S)
        dut.io.input.bits.scaleQ24.poke(row.scaleQ24.U)
        dut.io.input.bits.dampenQ24.poke(row.dampenQ24.U)
        dut.io.input.valid.poke(true.B)
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
        dut.io.input.valid.poke(false.B)
        dut.io.output.valid.expect(true.B)
        dut.io.output.bits.expect(expected.U)
        val seed = (row.fixedGammaQ24.toFloat / OneQ24.toFloat).toFloat
        val scale = (row.scaleQ24.toFloat / OneQ24.toFloat).toFloat
        val dampen = (row.dampenQ24.toFloat / OneQ24.toFloat).toFloat
        val base = (0.5f * scale).toFloat
        val sameInputFloat = BigInt(
          Math.round(
            ((fastPow2Float((seed * 1.442695041f).toFloat) * scale * dampen +
              (1.0f - dampen) * base).toFloat.toDouble * OneQ24.toDouble)
          )
        )
        withClue(s"final-map fixed-operation tolerance $index") {
          (expected - sameInputFloat).abs must be <= BigInt(128)
        }
        withClue(s"final-map float tolerance $index") {
          (expected - row.aqMapQ24).abs must be <= BigInt(32768)
        }
        if (index == 0) {
          dut.io.output.ready.poke(false.B)
          dut.clock.step(3)
          dut.io.output.valid.expect(true.B)
          dut.io.output.bits.expect(expected.U)
          dut.io.input.ready.expect(false.B)
          dut.io.output.ready.poke(true.B)
        }
        dut.clock.step()
      }
      dut.io.busy.expect(false.B)
    }
  }

  "FramePreparedAqFinalModulationTraceStage preserves exact raster order" in {
    requireReferenceTools()
    val width = 17
    val height = 9
    val rows = generateFixture("checkerboard", width, height, distanceQ8 = 2048)._2

    simulate(new FramePreparedAqFinalModulationTraceStage(config)) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      pokeConfig(dut.io.config, width, height, 2048)
      dut.clock.step()
      for ((row, index) <- rows.zipWithIndex) {
        dut.io.input.bits.seedQ24.poke(row.fixedGammaQ24.S)
        dut.io.input.bits.scaleQ24.poke(row.scaleQ24.U)
        dut.io.input.bits.dampenQ24.poke(row.dampenQ24.U)
        dut.io.input.valid.poke(true.B)
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
        dut.io.input.valid.poke(false.B)
        dut.io.trace.valid.expect(true.B)
        dut.io.trace.bits.stage.expect(TraceStage.AqFinalMap.U)
        dut.io.trace.bits.index.expect(index.U)
        dut.io.trace.bits.value.expect(row.fixedMapQ24.S)
        dut.io.traceLast.expect((index == rows.length - 1).B)
        if (index == 0) {
          dut.io.trace.ready.poke(false.B)
          dut.clock.step(3)
          dut.io.trace.valid.expect(true.B)
          dut.io.trace.bits.value.expect(row.fixedMapQ24.S)
          dut.io.trace.ready.poke(true.B)
        }
        dut.clock.step()
      }
      dut.io.busy.expect(false.B)
      dut.io.overflow.expect(false.B)

      pokeConfig(dut.io.config, config.maxFrameWidth + 1, 1, 256)
      dut.clock.step()
      dut.io.overflow.expect(true.B)
      dut.io.input.ready.expect(false.B)
    }
  }

  "FrameAqFinalMapTraceStage completes the shared RGB AQ chain" in {
    requireReferenceTools()
    val width = 17
    val height = 9
    val fixtures = Seq("constant", "gradient", "checkerboard", "impulse", "random").map {
      pattern => pattern -> generateFixture(pattern, width, height)
    }

    simulate(new FrameAqFinalMapTraceStage(config)) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()
      for ((pattern, (rgb, expectedRows)) <- fixtures) {
        pokeConfig(dut.io.config, width, height, 256)
        driveRgb(dut.io.input, dut.clock, rgb, width, height)
        for ((expected, index) <- expectedRows.zipWithIndex) {
          var cycles = 0
          while (!dut.io.trace.valid.peek().litToBoolean && cycles < 2200) {
            dut.clock.step()
            cycles += 1
          }
          withClue(s"$pattern final AQ map $index") {
            cycles must be < 2200
            dut.io.trace.bits.stage.expect(TraceStage.AqFinalMap.U)
            dut.io.trace.bits.index.expect(index.U)
            val actual = dut.io.trace.bits.value.peekValue().asBigInt
            val reference = expected.inputQ8FixedMapQ24
            val allowed = (reference / 50).max(BigInt(1) << 15)
            (actual - reference).abs must be <= allowed
            dut.io.traceLast.expect((index == expectedRows.length - 1).B)
          }
          if (pattern == "constant" && index == 0) {
            val held = dut.io.trace.bits.value.peekValue().asBigInt
            dut.io.trace.ready.poke(false.B)
            dut.clock.step(3)
            dut.io.trace.valid.expect(true.B)
            dut.io.trace.bits.value.expect(held)
            dut.io.trace.ready.poke(true.B)
          }
          dut.clock.step()
        }
        dut.io.busy.expect(false.B)
      }
    }
  }

  "FrameAqFinalMapTraceStage applies the shared distance-1 fallback consistently" in {
    requireReferenceTools()
    val width = 2
    val height = 1
    val (rgb, rows) = generateFixture("constant", width, height, distanceQ8 = 256)
    rows.length mustBe 1
    simulate(new FrameAqFinalMapTraceStage(config)) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      pokeConfig(dut.io.config, width, height, distanceQ8 = 333)
      dut.clock.step()
      driveRgb(dut.io.input, dut.clock, rgb, width, height)
      var cycles = 0
      while (!dut.io.trace.valid.peek().litToBoolean && cycles < 2200) {
        dut.clock.step()
        cycles += 1
      }
      cycles must be < 2200
      val actual = dut.io.trace.bits.value.peekValue().asBigInt
      val reference = rows.head.inputQ8FixedMapQ24
      val allowed = (reference / 50).max(BigInt(1) << 15)
      (actual - reference).abs must be <= allowed
      dut.io.traceLast.expect(true.B)
      dut.clock.step()
      dut.io.busy.expect(false.B)
    }
  }

  "FramePreparedAqFinalModulationTraceStage preserves 65x65 raster order" in {
    requireReferenceTools()
    val width = 65
    val height = 65
    val rows = generateFixture("random", width, height, distanceQ8 = 512)._2
    rows.length mustBe 9 * 9
    simulate(
      new FramePreparedAqFinalModulationTraceStage(
        HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 72)
      )
    ) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      pokeConfig(dut.io.config, width, height, 512)
      dut.clock.step()
      for ((row, index) <- rows.zipWithIndex) {
        dut.io.input.bits.seedQ24.poke(row.fixedGammaQ24.S)
        dut.io.input.bits.scaleQ24.poke(row.scaleQ24.U)
        dut.io.input.bits.dampenQ24.poke(row.dampenQ24.U)
        dut.io.input.valid.poke(true.B)
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
        dut.io.input.valid.poke(false.B)
        dut.io.trace.valid.expect(true.B)
        dut.io.trace.bits.index.expect(index.U)
        dut.io.trace.bits.value.expect(row.fixedMapQ24.S)
        row.blockX mustBe index % 9
        row.blockY mustBe index / 9
        dut.io.traceLast.expect((index == rows.length - 1).B)
        dut.clock.step()
      }
      dut.io.busy.expect(false.B)
    }
  }

  "FrameRawQuantFieldTraceStage emits adaptive bytes at zero and preserves fixed override" in {
    requireReferenceTools()
    val width = 17
    val height = 9
    val fixtures = Seq(
      ("gradient", 128),
      ("random", 256),
      ("checkerboard", 2048)
    ).map { case (pattern, distanceQ8) =>
      (pattern, distanceQ8, generateFixture(pattern, width, height, distanceQ8))
    }

    simulate(new FrameRawQuantFieldTraceStage(config)) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()
      for ((pattern, distanceQ8, (rgb, expectedRows)) <- fixtures) {
        pokeConfig(dut.io.config, width, height, distanceQ8, aqTraceFlags = false)
        driveRgb(dut.io.input, dut.clock, rgb, width, height)
        for ((expected, index) <- expectedRows.zipWithIndex) {
          var cycles = 0
          while (!dut.io.trace.valid.peek().litToBoolean && cycles < 2200) {
            dut.clock.step()
            cycles += 1
          }
          withClue(s"$pattern adaptive raw quant $index") {
            cycles must be < 2200
            dut.io.trace.bits.stage.expect(TraceStage.RawQuantField.U)
            dut.io.trace.bits.index.expect(index.U)
            val actual = dut.io.trace.bits.value.peekValue().asBigInt.toInt
            actual mustBe expected.inputQ8FixedRawQuant
            dut.io.traceLast.expect((index == expectedRows.length - 1).B)
          }
          dut.clock.step()
        }
        dut.io.busy.expect(false.B)
      }

      val (rgb, rows) = generateFixture("constant", 2, 1)
      pokeConfig(dut.io.config, 2, 1, 256, fixedRawQuant = 207, aqTraceFlags = false)
      driveRgb(dut.io.input, dut.clock, rgb, 2, 1)
      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.value.expect(207.S)
      dut.io.traceLast.expect(true.B)
      dut.clock.step()
      dut.io.busy.expect(false.B)
      rows.length mustBe 1
    }
  }

  "FrameAqFinalMapTraceStage preserves the 65-pixel tile boundary" in {
    requireReferenceTools()
    val width = 65
    val height = 1
    val (rgb, rows) = generateFixture("gradient", width, height, distanceQ8 = 2048)
    rows.length mustBe 9
    simulate(new FrameAqFinalMapTraceStage(HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 8))) {
      dut =>
        dut.io.input.valid.poke(false.B)
        dut.io.trace.ready.poke(true.B)
        pokeConfig(dut.io.config, width, height, 2048)
        driveRgb(dut.io.input, dut.clock, rgb, width, height)
        for ((row, index) <- rows.zipWithIndex) {
          var cycles = 0
          while (!dut.io.trace.valid.peek().litToBoolean && cycles < 2300) {
            dut.clock.step()
            cycles += 1
          }
          cycles must be < 2300
          dut.io.trace.bits.index.expect(index.U)
          val actual = dut.io.trace.bits.value.peekValue().asBigInt
          val allowed = (row.inputQ8FixedMapQ24 / 50).max(BigInt(1) << 15)
          (actual - row.inputQ8FixedMapQ24).abs must be <= allowed
          dut.io.traceLast.expect((index == rows.length - 1).B)
          dut.clock.step()
        }
        dut.io.busy.expect(false.B)
    }
  }
}

class AqFinalModulationElaborationSpec extends AnyFreeSpec with Matchers {
  "FrameAqFinalMapTraceStage elaborates one divider-free cumulative chain" in {
    val outputDir = Files.createTempDirectory("hjxl-aq-final-elaboration-")
    ChiselStage.emitSystemVerilogFile(
      new FrameAqFinalMapTraceStage(),
      args = Array("--target-dir", outputDir.toString),
      firtoolOpts = Array(
        "-disable-all-randomization",
        "-strip-debug-info",
        "-default-layer-specialization=enable"
      )
    )
    val stream = Files.walk(outputDir)
    val files = try {
      stream.iterator().asScala
        .filter(path => path.getFileName.toString.endsWith(".sv"))
        .map(path => path.getFileName.toString -> Files.readString(path))
        .toMap
    } finally {
      stream.close()
    }

    files.keySet must contain allOf (
      "FrameAqFinalMapTraceStage.sv",
      "FrameAqFinalMapPipeline.sv",
      "AqHfColorGammaModulationBlockPipeline.sv",
      "AqFinalModulationBlock.sv",
      "AqFastExpQ24.sv",
      "DistanceParamsLookup.sv",
      "RgbToXybApprox.sv"
    )
    files("AqFastExpQ24.sv") must not include " / "
    files("AqFinalModulationBlock.sv") must not include " / "
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
