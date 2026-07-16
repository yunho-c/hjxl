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
import scala.util.Random

class AqContrastSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 24, maxFrameHeight = 16)
  private val libjxlTinyRoot =
    Path.of(sys.env.getOrElse("LIBJXL_TINY", "/Users/yunhocho/GitHub/libjxl-tiny"))

  private case class Neighborhood(
      centerX: Int,
      centerY: Int,
      leftX: Int,
      rightX: Int,
      upX: Int,
      downX: Int,
      leftY: Int,
      rightY: Int,
      upY: Int,
      downY: Int
  )

  private case class RgbRow(x: Int, y: Int, r: Int, g: Int, b: Int)
  private case class ContrastRow(
      cell: Int,
      cellX: Int,
      cellY: Int,
      referenceQ16: BigInt,
      quantizedXybReferenceQ16: BigInt
  )

  private def requireReferenceTools(): Unit = {
    assume(
      Files.isDirectory(libjxlTinyRoot.resolve("python")),
      s"libjxl-tiny Python checkout not found at $libjxlTinyRoot"
    )
    assume(
      Process(Seq("python3", "-c", "import numpy")).! == 0,
      "python3 with numpy is required for AQ contrast fixtures"
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
      ContrastRow(
        cell = columns(0).toInt,
        cellX = columns(1).toInt,
        cellY = columns(2).toInt,
        referenceQ16 = BigInt(columns(3)),
        quantizedXybReferenceQ16 = BigInt(columns(4))
      )
    }
  }

  private def generateFixture(
      pattern: String,
      width: Int,
      height: Int
  ): (Seq[RgbRow], Seq[ContrastRow]) = {
    val xybCsv = Files.createTempFile(s"hjxl-aq-contrast-$pattern-xyb-", ".csv")
    val contrastCsv = Files.createTempFile(s"hjxl-aq-contrast-$pattern-", ".csv")
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
        contrastCsv.toString
      ),
      "LIBJXL_TINY" -> libjxlTinyRoot.toString
    )
    readRgbRows(xybCsv, width, height) -> readContrastRows(contrastCsv)
  }

  private def referenceGamma(value: Double): Double = {
    val epsilon = 1e-2
    val sgMul = 226.0480446705883
    val sgMul2 = 1.0 / 73.377132366608819
    val log2 = 0.693147181
    val sgRetMul = sgMul2 * 18.6580932135 * log2
    val v = math.max(value, 0.0)
    val numerator = sgRetMul * 3.0 * sgMul * v * v + epsilon
    val denominator = log2 * sgMul * v * v * v + 7.14672470003 * log2 + epsilon
    denominator / numerator
  }

  private def referenceContrast(neighborhood: Neighborhood): Double = {
    import neighborhood._
    val scale = 1 << AqContrastFixedPoint.InputFractionBits
    val gamma = referenceGamma(
      centerY.toDouble / scale +
        AqContrastFixedPoint.MatchGammaOffsetQ12.toDouble / scale
    )
    val differenceX =
      (centerX * 4L - leftX - rightX - upX - downX).toDouble / (4.0 * scale) * gamma
    val differenceY =
      (centerY * 4L - leftY - rightY - upY - downY).toDouble / (4.0 * scale) * gamma
    val difference = differenceY * differenceY + 23.426802998210313 * differenceX * differenceX
    0.25 * math.sqrt(
      difference * math.sqrt(211.50759899638012 * 1e8) + 26.481471032459346
    )
  }

  private def model(neighborhood: Neighborhood): BigInt = {
    import neighborhood._
    AqContrastFixedPoint.contrastPixelQ16(
      centerX,
      centerY,
      leftX,
      rightX,
      upX,
      downX,
      leftY,
      rightY,
      upY,
      downY
    )
  }

  private def integerSqrt(value: BigInt): BigInt = {
    if (value == 0) {
      BigInt(0)
    } else {
      var root = BigInt(1) << ((value.bitLength + 1) / 2)
      var next = (root + value / root) >> 1
      while (next < root) {
        root = next
        next = (root + value / root) >> 1
      }
      root
    }
  }

  private def pokeNeighborhood(dut: AqContrastPixel, neighborhood: Neighborhood): Unit = {
    import neighborhood._
    dut.io.centerX.poke(centerX.S)
    dut.io.centerY.poke(centerY.S)
    dut.io.leftX.poke(leftX.S)
    dut.io.rightX.poke(rightX.S)
    dut.io.upX.poke(upX.S)
    dut.io.downX.poke(downX.S)
    dut.io.leftY.poke(leftY.S)
    dut.io.rightY.poke(rightY.S)
    dut.io.upY.poke(upY.S)
    dut.io.downY.poke(downY.S)
  }

  private def pokeConfig(dut: FrameAqContrastTraceStage, width: Int, height: Int): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(0.U)
    dut.io.config.fixedInvQacQ16.poke(0.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.fixedYtox.poke(0.S)
    dut.io.config.fixedYtob.poke(0.S)
    dut.io.config.enableXyb.poke(true.B)
    dut.io.config.enableDct.poke(false.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(false.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.AqContrast.U)
  }

  "UnsignedIntegerSquareRoot returns floor(sqrt(input))" in {
    val random = new Random(1L)
    val randomValues = Seq.fill(128) {
      val low = BigInt(random.nextLong()) & ((BigInt(1) << 64) - 1)
      val high = BigInt(random.nextInt(1 << 10)) << 64
      high | low
    }
    val values = Seq[BigInt](
      0,
      1,
      2,
      3,
      4,
      15,
      16,
      17,
      (BigInt(1) << 40) - 1,
      BigInt(1) << 40,
      AqContrastFixedPoint.MaximumMaskingInput
    ) ++ randomValues
    simulate(new UnsignedIntegerSquareRoot(74)) { dut =>
      for (value <- values) {
        dut.io.input.poke(value.U)
        dut.clock.step()
        val expected = integerSqrt(value)
        dut.io.output.expect(expected.U)
        expected * expected must be <= value
        (expected + 1) * (expected + 1) must be > value
      }
    }
  }

  "AqGammaRatioQ16 matches the piecewise fixed-point model at every seam" in {
    val adjustedValues = Seq(
      0,
      1,
      AqContrastFixedPoint.FineLimitQ12 - 1,
      AqContrastFixedPoint.FineLimitQ12,
      AqContrastFixedPoint.FineLimitQ12 + 1,
      AqContrastFixedPoint.CoarseLimitQ12 - 1,
      AqContrastFixedPoint.CoarseLimitQ12,
      AqContrastFixedPoint.CoarseLimitQ12 + 1,
      AqContrastFixedPoint.HdrLimitQ12
    )
    simulate(new AqGammaRatioQ16()) { dut =>
      for (adjusted <- adjustedValues) {
        val centerY = adjusted - AqContrastFixedPoint.MatchGammaOffsetQ12
        dut.io.centerYQ12.poke(centerY.S)
        dut.clock.step()
        dut.io.output.expect(AqContrastFixedPoint.gammaRatioQ16(centerY).U)
      }
    }
  }

  "AqContrastPixel matches its bit-accurate model and stays close to the reference formula" in {
    val directed = Seq(
      Neighborhood(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
      Neighborhood(100, 800, 90, 110, 95, 105, 780, 820, 790, 810),
      Neighborhood(-300, -78, -290, -310, -280, -320, -90, -60, -70, -80),
      Neighborhood(700, 4096, 650, 750, 690, 710, 4000, 4200, 4050, 4150),
      Neighborhood(1500, 16000, 1400, 1600, 1450, 1550, 15800, 16200, 15900, 16100),
      Neighborhood(40000, 40000, -40000, 40000, -40000, 40000, -40000, 40000, -40000, 40000)
    )
    val hardwareRandom = new Random(2L)
    val exactCases = directed ++ Seq.fill(128) {
      val centerX = hardwareRandom.nextInt(2049) - 1024
      val centerY = hardwareRandom.nextInt(21121) - 640
      def nearby(center: Int): Int = center + hardwareRandom.nextInt(257) - 128
      Neighborhood(
        centerX,
        centerY,
        nearby(centerX),
        nearby(centerX),
        nearby(centerX),
        nearby(centerX),
        nearby(centerY),
        nearby(centerY),
        nearby(centerY),
        nearby(centerY)
      )
    }
    simulate(new AqContrastPixel()) { dut =>
      for ((neighborhood, index) <- exactCases.zipWithIndex) {
        pokeNeighborhood(dut, neighborhood)
        dut.clock.step()
        withClue(s"exact AQ contrast neighborhood $index") {
          dut.io.output.expect(model(neighborhood).U)
        }
      }
    }

    val random = new Random(0L)
    var maximumRelativeError = 0.0
    var maximumAbsoluteError = 0.0
    var worstCase = ""
    for (_ <- 0 until 20000) {
      val centerX = random.nextInt(2049) - 1024
      val centerY = random.nextInt(21121) - 640
      def nearby(center: Int): Int = center + random.nextInt(257) - 128
      val neighborhood = Neighborhood(
        centerX,
        centerY,
        nearby(centerX),
        nearby(centerX),
        nearby(centerX),
        nearby(centerX),
        nearby(centerY),
        nearby(centerY),
        nearby(centerY),
        nearby(centerY)
      )
      val actual = model(neighborhood).toDouble / (1 << AqContrastFixedPoint.OutputFractionBits)
      val expected = referenceContrast(neighborhood)
      val absoluteError = math.abs(actual - expected)
      val relativeError = absoluteError / math.max(expected, 1.0)
      if (relativeError > maximumRelativeError) {
        maximumRelativeError = relativeError
        worstCase = s"neighborhood=$neighborhood actual=$actual expected=$expected"
      }
      maximumAbsoluteError = math.max(maximumAbsoluteError, absoluteError)
    }
    withClue(worstCase) {
      maximumRelativeError must be <= 0.015
      maximumAbsoluteError must be <= 0.5
    }
  }

  "FrameAqContrastTraceStage matches libjxl-tiny contrast grids and preserves frame controls" in {
    requireReferenceTools()
    val width = 17
    val height = 9
    val fixtures = Seq("constant", "gradient", "checkerboard", "impulse", "random").map { pattern =>
      val (rgb, contrast) = generateFixture(pattern, width, height)
      rgb.length mustBe width * height
      rgb.map(row => (row.x, row.y)) mustBe
        (for (y <- 0 until height; x <- 0 until width) yield (x, y))
      contrast.length mustBe 24
      contrast.map(_.cell) mustBe contrast.indices
      pattern -> (rgb, contrast)
    }

    simulate(new FrameAqContrastTraceStage(config)) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      pokeConfig(dut, width, height)
      dut.clock.step()

      for (((pattern, (rgb, expectedRows)), fixtureIndex) <- fixtures.zipWithIndex) {
        pokeConfig(dut, width, height)
        for ((row, inputIndex) <- rgb.zipWithIndex) {
          dut.io.input.valid.poke(true.B)
          dut.io.input.bits.x.poke(row.x.U)
          dut.io.input.bits.y.poke(row.y.U)
          dut.io.input.bits.r.poke(row.r.S)
          dut.io.input.bits.g.poke(row.g.S)
          dut.io.input.bits.b.poke(row.b.S)
          withClue(s"$pattern input $inputIndex") {
            dut.io.input.ready.expect(true.B)
          }
          dut.clock.step()
        }
        dut.io.input.valid.poke(false.B)

        // Frame geometry is captured at the first input beat.
        pokeConfig(dut, width = 0, height = 0)
        dut.io.overflow.expect(false.B)

        for ((expected, outputIndex) <- expectedRows.zipWithIndex) {
          var waitCycles = 0
          while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < 24) {
            dut.clock.step()
            waitCycles += 1
          }
          withClue(s"$pattern output $outputIndex did not arrive") {
            waitCycles must be < 24
          }
          dut.io.trace.ready.poke(false.B)
          val stableValue = dut.io.trace.bits.value.peekValue().asBigInt
          dut.clock.step(3)
          dut.io.trace.valid.expect(true.B)
          dut.io.trace.bits.value.expect(stableValue.S)
          dut.io.trace.bits.stage.expect(TraceStage.AqContrast.U)
          dut.io.trace.bits.group.expect(0.U)
          dut.io.trace.bits.index.expect(expected.cell.U)
          dut.io.traceLast.expect((outputIndex == expectedRows.length - 1).B)
          expected.cellX mustBe outputIndex % 6
          expected.cellY mustBe outputIndex / 6

          val actual = stableValue
          val error = (actual - expected.referenceQ16).abs
          val allowed = (expected.referenceQ16 / 50).max(BigInt(1 << 15))
          withClue(
            s"$pattern cell ${expected.cell} actual=$actual " +
              s"reference=${expected.referenceQ16} quantized=${expected.quantizedXybReferenceQ16}"
          ) {
            error must be <= allowed
          }
          dut.io.trace.ready.poke(true.B)
          dut.clock.step()
          dut.io.trace.ready.poke(false.B)
        }
        dut.io.trace.valid.expect(false.B)
        dut.io.busy.expect(false.B)
        dut.io.overflow.expect(true.B)
        withClue(s"completed AQ contrast fixture $fixtureIndex") {
          pattern.nonEmpty mustBe true
        }
      }
    }
  }

  "FrameAqContrastTraceStage keeps one horizontal cell grid across a 64-pixel tile boundary" in {
    requireReferenceTools()
    val width = 65
    val height = 1
    val (rgb, expectedRows) = generateFixture("gradient", width, height)
    expectedRows.length mustBe 36

    simulate(new FrameAqContrastTraceStage(HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 8))) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      pokeConfig(dut, width, height)
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
        while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < 24) {
          dut.clock.step()
          waitCycles += 1
        }
        withClue(s"tile-boundary AQ contrast output $outputIndex") {
          waitCycles must be < 24
          dut.io.trace.bits.stage.expect(TraceStage.AqContrast.U)
          dut.io.trace.bits.index.expect(outputIndex.U)
          dut.io.traceLast.expect((outputIndex == expectedRows.length - 1).B)
          expected.cellX mustBe outputIndex % 18
          expected.cellY mustBe outputIndex / 18
          val actual = dut.io.trace.bits.value.peekValue().asBigInt
          val error = (actual - expected.referenceQ16).abs
          val allowed = (expected.referenceQ16 / 50).max(BigInt(1 << 15))
          error must be <= allowed
        }
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.busy.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "FrameAqContrastTraceStage clamps vertical neighborhoods at the 64-row stripe boundary" in {
    requireReferenceTools()
    val width = 65
    val height = 65
    val (rgb, expectedRows) = generateFixture("gradient", width, height)
    expectedRows.length mustBe 18 * 18

    simulate(new FrameAqContrastTraceStage(HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 72))) {
      dut =>
        dut.io.input.valid.poke(false.B)
        dut.io.trace.ready.poke(true.B)
        pokeConfig(dut, width, height)
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
          while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < 24) {
            dut.clock.step()
            waitCycles += 1
          }
          withClue(s"stripe-boundary AQ contrast output $outputIndex") {
            waitCycles must be < 24
            dut.io.trace.bits.stage.expect(TraceStage.AqContrast.U)
            dut.io.trace.bits.index.expect(outputIndex.U)
            dut.io.traceLast.expect((outputIndex == expectedRows.length - 1).B)
            expected.cellX mustBe outputIndex % 18
            expected.cellY mustBe outputIndex / 18
            val actual = dut.io.trace.bits.value.peekValue().asBigInt
            val error = (actual - expected.referenceQ16).abs
            val allowed = (expected.referenceQ16 / 50).max(BigInt(1 << 15))
            error must be <= allowed
          }
          dut.clock.step()
        }
        dut.io.trace.valid.expect(false.B)
        dut.io.busy.expect(false.B)
        dut.io.overflow.expect(false.B)
    }
  }
}
