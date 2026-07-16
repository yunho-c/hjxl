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

class RgbToXybApproxSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val outputScale = 1 << RgbToXybApprox.OutputFractionBits
  private val libjxlTinyRoot =
    Path.of(sys.env.getOrElse("LIBJXL_TINY", "/Users/yunhocho/GitHub/libjxl-tiny"))

  private case class XybOracleRow(
      raster: Int,
      x: Int,
      y: Int,
      rQ8: Int,
      gQ8: Int,
      bQ8: Int,
      xQ12: Int,
      yQ12: Int,
      bQ12: Int
  )

  private def requireReferenceTools(): Unit = {
    assume(
      Files.isDirectory(libjxlTinyRoot.resolve("python")),
      s"libjxl-tiny Python checkout not found at $libjxlTinyRoot"
    )
    assume(
      Process(Seq("python3", "-c", "import numpy")).! == 0,
      "python3 with numpy is required for XYB fixtures"
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

  private def readOracleCsv(path: Path): Seq[XybOracleRow] = {
    val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
    lines.get(0) mustBe
      "raster,x,y,r_q8,g_q8,b_q8,xyb_x_q12,xyb_y_q12,xyb_b_q12"
    lines.toArray(new Array[String](0)).drop(1).toSeq.map { line =>
      val columns = line.split(",", -1)
      columns.length mustBe 9
      XybOracleRow(
        raster = columns(0).toInt,
        x = columns(1).toInt,
        y = columns(2).toInt,
        rQ8 = columns(3).toInt,
        gQ8 = columns(4).toInt,
        bQ8 = columns(5).toInt,
        xQ12 = columns(6).toInt,
        yQ12 = columns(7).toInt,
        bQ12 = columns(8).toInt
      )
    }
  }

  private def reference(rQ8: Int, gQ8: Int, bQ8: Int): (Int, Int, Int) = {
    def source(value: Int): Double =
      value.toDouble / (1 << RgbToXybApprox.InputFractionBits)
    val r = source(rQ8)
    val g = source(gQ8)
    val b = source(bQ8)
    val bias = 0.0037930732552754493
    val negBias = -0.15595420054
    def cbrt(value: Double): Double = math.cbrt(math.max(value, 0.0)) + negBias
    val mixed0 = 0.30 * r + (1.0 - 0.30 - 0.078) * g + 0.078 * b + bias
    val mixed1 = 0.23 * r + (1.0 - 0.23 - 0.078) * g + 0.078 * b + bias
    val mixed2 = 0.24342268924547819 * r + 0.20476744424496821 * g +
      (1.0 - 0.24342268924547819 - 0.20476744424496821) * b + bias
    val tm0 = cbrt(mixed0)
    val tm1 = cbrt(mixed1)
    val tm2 = cbrt(mixed2)
    (
      math.round(0.5 * (tm0 - tm1) * outputScale).toInt,
      math.round(0.5 * (tm0 + tm1) * outputScale).toInt,
      math.round(tm2 * outputScale).toInt
    )
  }

  private def expectClose(actual: BigInt, expected: Int, tolerance: Int): Unit =
    math.abs(actual.toInt - expected) must be <= tolerance

  "CbrtApproxQ12 matches the normalized fixed-point model across scale boundaries" in {
    val samples =
      Seq[BigInt](0, 1) ++
        RgbToXybApprox.CbrtScaleThresholds.flatMap(threshold => Seq(threshold - 1, threshold)) ++
        Seq((BigInt(1) << 31) - 1)
    simulate(new CbrtApproxQ12(inputBits = 33, outputBits = 32)) { dut =>
      for (sample <- samples) {
        dut.io.input.poke(sample.U)
        dut.clock.step()
        dut.io.output.expect(RgbToXybApprox.cbrtPlusBiasQ12(sample).S)
      }
    }
  }

  "RgbToXybApprox preserves signed mixing and the full positive Q8 input range" in {
    simulate(new RgbToXybApprox()) { dut =>
      val vectors = Seq(
        (0, 0, 0),
        (2, 1, 2),
        (64, 128, 192),
        (256, 256, 256),
        (-16, 32, 128),
        (-256, 256, 0),
        (1024, 1024, 1024),
        (4096, 0, 0),
        (0, 4096, 0),
        (0, 0, 4096),
        (32767, 32767, 32767),
        (32767, 0, 0),
        (0, 32767, 0),
        (0, 0, 32767),
        (-4096, 4096, 2048)
      )
      dut.io.output.ready.poke(true.B)

      for (((r, g, b), index) <- vectors.zipWithIndex) {
        val exact = RgbToXybApprox.rgbToXybQ12(r, g, b)
        val expected = reference(r, g, b)
        dut.io.input.valid.poke(true.B)
        dut.io.input.bits.x.poke(index.U)
        dut.io.input.bits.y.poke((index + 1).U)
        dut.io.input.bits.r.poke(r.S)
        dut.io.input.bits.g.poke(g.S)
        dut.io.input.bits.b.poke(b.S)
        dut.io.output.valid.expect(true.B)
        dut.io.output.bits.x.expect(index.U)
        dut.io.output.bits.y.expect((index + 1).U)
        dut.io.output.bits.xybX.expect(exact._1.S)
        dut.io.output.bits.xybY.expect(exact._2.S)
        dut.io.output.bits.xybB.expect(exact._3.S)
        withClue(s"independent XYB reference for vector $index ($r,$g,$b)") {
          expectClose(dut.io.output.bits.xybX.peekValue().asBigInt, expected._1, 5)
          expectClose(dut.io.output.bits.xybY.peekValue().asBigInt, expected._2, 5)
          expectClose(dut.io.output.bits.xybB.peekValue().asBigInt, expected._3, 5)
        }
        dut.clock.step()
      }

      dut.io.output.ready.poke(false.B)
      dut.io.input.ready.expect(false.B)
    }
  }

  "RgbToXybApprox preserves exact Q12 and Q16 outputs from one conversion" in {
    val fractionBits = 16
    val random = new Random(1L)
    val vectors = Seq((64, 128, 192)) ++ Seq.fill(256)(
      (
        random.nextInt(1 << 16) - (1 << 15),
        random.nextInt(1 << 16) - (1 << 15),
        random.nextInt(1 << 16) - (1 << 15)
      )
    )
    RgbToXybApprox.rgbToXyb(64, 128, 192, fractionBits) mustBe
      (-322, 40259, 44461)

    simulate(
      new RgbToXybApprox(
        outputFractionBits = fractionBits,
        includeQ12Output = true
      )
    ) { dut =>
      dut.io.output.ready.poke(true.B)
      for (((r, g, b), index) <- vectors.zipWithIndex) {
        val expectedQ12 = RgbToXybApprox.rgbToXybQ12(r, g, b)
        val expectedQ16 = RgbToXybApprox.rgbToXyb(r, g, b, fractionBits)
        dut.io.input.valid.poke(true.B)
        dut.io.input.bits.x.poke(index.U)
        dut.io.input.bits.y.poke((index + 1).U)
        dut.io.input.bits.r.poke(r.S)
        dut.io.input.bits.g.poke(g.S)
        dut.io.input.bits.b.poke(b.S)
        dut.io.output.valid.expect(true.B)
        dut.io.output.bits.xybX.expect(expectedQ16._1.S)
        dut.io.output.bits.xybY.expect(expectedQ16._2.S)
        dut.io.output.bits.xybB.expect(expectedQ16._3.S)
        dut.io.outputQ12.get.valid.expect(true.B)
        dut.io.outputQ12.get.bits.x.expect(index.U)
        dut.io.outputQ12.get.bits.y.expect((index + 1).U)
        dut.io.outputQ12.get.bits.xybX.expect(expectedQ12._1.S)
        dut.io.outputQ12.get.bits.xybY.expect(expectedQ12._2.S)
        dut.io.outputQ12.get.bits.xybB.expect(expectedQ12._3.S)
        dut.clock.step()
      }
      dut.io.output.ready.poke(false.B)
      dut.io.outputQ12.get.valid.expect(false.B)
    }
  }

  "the high-precision converter preserves exact Q12 and Q18 sidebands" in {
    val primaryFractionBits = RgbVarDctFixedPoint.QuantizationXybFractionBits
    val secondaryFractionBits = RgbVarDctFixedPoint.AnalysisXybFractionBits
    val random = new Random(2L)
    val vectors = Seq((64, 128, 192)) ++ Seq.fill(256)(
      (
        random.nextInt(1 << 16) - (1 << 15),
        random.nextInt(1 << 16) - (1 << 15),
        random.nextInt(1 << 16) - (1 << 15)
      )
    )

    simulate(
      new RgbToXybApprox(
        outputFractionBits = primaryFractionBits,
        includeQ12Output = true,
        includeQ18Output = true
      )
    ) { dut =>
      dut.io.output.ready.poke(true.B)
      for (((r, g, b), index) <- vectors.zipWithIndex) {
        val expectedQ12 = RgbToXybApprox.rgbToXybQ12(r, g, b)
        val expectedQ18 =
          RgbToXybApprox.rgbToXyb(r, g, b, secondaryFractionBits)
        val expectedPrimary =
          RgbToXybApprox.rgbToXyb(r, g, b, primaryFractionBits)
        dut.io.input.valid.poke(true.B)
        dut.io.input.bits.x.poke(index.U)
        dut.io.input.bits.y.poke((index + 1).U)
        dut.io.input.bits.r.poke(r.S)
        dut.io.input.bits.g.poke(g.S)
        dut.io.input.bits.b.poke(b.S)
        dut.io.output.valid.expect(true.B)
        dut.io.output.bits.xybX.expect(expectedPrimary._1.S)
        dut.io.output.bits.xybY.expect(expectedPrimary._2.S)
        dut.io.output.bits.xybB.expect(expectedPrimary._3.S)
        dut.io.outputQ18.get.valid.expect(true.B)
        dut.io.outputQ18.get.bits.xybX.expect(expectedQ18._1.S)
        dut.io.outputQ18.get.bits.xybY.expect(expectedQ18._2.S)
        dut.io.outputQ18.get.bits.xybB.expect(expectedQ18._3.S)
        dut.io.outputQ12.get.valid.expect(true.B)
        dut.io.outputQ12.get.bits.xybX.expect(expectedQ12._1.S)
        dut.io.outputQ12.get.bits.xybY.expect(expectedQ12._2.S)
        dut.io.outputQ12.get.bits.xybB.expect(expectedQ12._3.S)
        dut.clock.step()
      }
      dut.io.output.ready.poke(false.B)
      dut.io.outputQ18.get.valid.expect(false.B)
      dut.io.outputQ12.get.valid.expect(false.B)
    }
  }

  "the fixed-point model stays within five Q12 units across signed-16 Q8 RGB" in {
    val random = new Random(0L)
    var maximumError = 0
    var worstCase = ""
    for (_ <- 0 until 100000) {
      val rgb = Seq.fill(3)(random.nextInt(1 << 16) - (1 << 15))
      val actual = RgbToXybApprox.rgbToXybQ12(rgb(0), rgb(1), rgb(2))
      val expected = reference(rgb(0), rgb(1), rgb(2))
      val error = Seq(
        math.abs(actual._1 - expected._1),
        math.abs(actual._2 - expected._2),
        math.abs(actual._3 - expected._3)
      ).max
      if (error > maximumError) {
        maximumError = error
        worstCase = s"rgb=$rgb actual=$actual expected=$expected"
      }
    }
    withClue(worstCase) {
      maximumError must be <= 5
    }
  }

  "RgbToXybApprox matches libjxl-tiny Q12 fixture exports" in {
    requireReferenceTools()
    val width = 17
    val height = 9
    val rows = Seq("gradient", "checkerboard", "random").flatMap { pattern =>
      val csv = Files.createTempFile(s"hjxl-xyb-$pattern-", ".csv")
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
          csv.toString
        ),
        "LIBJXL_TINY" -> libjxlTinyRoot.toString
      )
      val fixtureRows = readOracleCsv(csv)
      fixtureRows.length mustBe 24 * 16
      fixtureRows.map(_.raster) mustBe fixtureRows.indices
      fixtureRows
    }

    simulate(new RgbToXybApprox()) { dut =>
      dut.io.output.ready.poke(true.B)
      for ((row, ordinal) <- rows.zipWithIndex) {
        dut.io.input.valid.poke(true.B)
        dut.io.input.bits.x.poke(row.x.U)
        dut.io.input.bits.y.poke(row.y.U)
        dut.io.input.bits.r.poke(row.rQ8.S)
        dut.io.input.bits.g.poke(row.gQ8.S)
        dut.io.input.bits.b.poke(row.bQ8.S)
        withClue(s"libjxl-tiny XYB row $ordinal") {
          dut.io.output.valid.expect(true.B)
          dut.io.output.bits.x.expect(row.x.U)
          dut.io.output.bits.y.expect(row.y.U)
          expectClose(dut.io.output.bits.xybX.peekValue().asBigInt, row.xQ12, 2)
          expectClose(dut.io.output.bits.xybY.peekValue().asBigInt, row.yQ12, 2)
          expectClose(dut.io.output.bits.xybB.peekValue().asBigInt, row.bQ12, 2)
        }
        dut.clock.step()
      }
    }
  }
}
