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

class DctRectangularApproxSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val libjxlTinyRoot =
    Path.of(sys.env.getOrElse("LIBJXL_TINY", "/Users/yunhocho/GitHub/libjxl-tiny"))

  private case class Fixture(
      kind: String,
      name: String,
      input: Seq[Int],
      expected: Seq[Int],
      fixedExpected: Seq[Int]
  )

  private def requireReferenceTools(): Unit = {
    assume(
      Files.isDirectory(libjxlTinyRoot.resolve("python")),
      s"libjxl-tiny Python checkout not found at $libjxlTinyRoot"
    )
    assume(
      Process(Seq("python3", "-c", "import numpy")).! == 0,
      "python3 with numpy is required for scaled-DCT fixtures"
    )
  }

  private def fixtures(): Seq[Fixture] = {
    requireReferenceTools()
    val csv = Files.createTempFile("hjxl-scaled-dct-q12-", ".csv")
    val output = scala.collection.mutable.ArrayBuffer.empty[String]
    val logger = ProcessLogger(line => output += line, line => output += line)
    val exitCode = Process(
      Seq(
        "python3",
        "tools/hjxl_reference.py",
        "--scaled-dct-q12-csv",
        csv.toString
      ),
      TestPaths.repoRoot.toFile,
      "LIBJXL_TINY" -> libjxlTinyRoot.toString,
      "PYTHONDONTWRITEBYTECODE" -> "1"
    ).!(logger)
    withClue(output.mkString("\n")) {
      exitCode mustBe 0
    }

    val lines = Files.readAllLines(csv, StandardCharsets.UTF_8).asScala.toSeq
    lines.head mustBe
      "kind,fixture,index,input_q12,coefficient_q12,fixed_coefficient_q12"
    val rows = lines.tail.map { line =>
      val columns = line.split(",", -1)
      columns.length mustBe 6
      (
        columns(0),
        columns(1),
        columns(2).toInt,
        columns(3).toInt,
        columns(4).toInt,
        columns(5).toInt
      )
    }
    rows.groupBy(row => (row._1, row._2)).toSeq.sortBy(_._1).map {
      case ((kind, name), grouped) =>
        val ordered = grouped.sortBy(_._3)
        ordered.map(_._3) mustBe (0 until ordered.length)
        Fixture(kind, name, ordered.map(_._4), ordered.map(_._5), ordered.map(_._6))
    }
  }

  private def expectDct16(dut: Dct16Approx, fixture: Fixture): Unit = {
    fixture.input.length mustBe 16
    dut.io.input.valid.poke(true.B)
    dut.io.output.ready.poke(false.B)
    for (index <- fixture.input.indices) {
      dut.io.input.bits(index).poke(fixture.input(index).S)
    }
    dut.io.input.ready.expect(false.B)
    dut.io.output.valid.expect(true.B)
    val stalled = fixture.input.indices.map { index =>
      dut.io.output.bits(index).peekValue().asBigInt
    }
    dut.clock.step(2)
    fixture.input.indices.map { index =>
      dut.io.output.bits(index).peekValue().asBigInt
    } mustBe stalled

    dut.io.output.ready.poke(true.B)
    dut.io.input.ready.expect(true.B)
    for (index <- fixture.expected.indices) {
      val actual = dut.io.output.bits(index).peekValue().asBigInt.toInt
      withClue(s"${fixture.kind}/${fixture.name} coefficient $index: ") {
        actual mustBe fixture.fixedExpected(index)
        math.abs(actual - fixture.expected(index)) must be <= 16
      }
    }
    dut.clock.step()
    dut.io.input.valid.poke(false.B)
  }

  private def expectRectangle(
      dut: DctRectangularApprox,
      fixture: Fixture,
      tolerance: Int
  ): Unit = {
    fixture.input.length mustBe 128
    dut.io.input.valid.poke(true.B)
    dut.io.output.ready.poke(false.B)
    for (index <- fixture.input.indices) {
      dut.io.input.bits(index).poke(fixture.input(index).S)
    }
    dut.io.input.ready.expect(false.B)
    dut.io.output.valid.expect(true.B)
    val stalled = fixture.input.indices.map { index =>
      dut.io.output.bits(index).peekValue().asBigInt
    }
    dut.clock.step(2)
    fixture.input.indices.map { index =>
      dut.io.output.bits(index).peekValue().asBigInt
    } mustBe stalled

    dut.io.output.ready.poke(true.B)
    dut.io.input.ready.expect(true.B)
    for (index <- fixture.expected.indices) {
      val actual = dut.io.output.bits(index).peekValue().asBigInt.toInt
      withClue(s"${fixture.kind}/${fixture.name} coefficient $index: ") {
        actual mustBe fixture.fixedExpected(index)
        math.abs(actual - fixture.expected(index)) must be <= tolerance
      }
    }
    dut.clock.step()
    dut.io.input.valid.poke(false.B)
  }

  "Dct16Approx matches the independent libjxl-tiny recursive transform" in {
    val cases = fixtures().filter(_.kind == "dct16")
    cases.map(_.name).toSet mustBe
      Set("constant", "ramp", "alternating", "impulse", "signed")
    simulate(new Dct16Approx()) { dut =>
      cases.foreach(expectDct16(dut, _))
    }
  }

  "Dct16x8Approx emits libjxl-tiny's transposed canonical 8x16 layout" in {
    val cases = fixtures().filter(_.kind == "dct16x8")
    cases.map(_.name).toSet mustBe
      Set("constant", "x-ramp", "y-ramp", "impulse", "signed")
    simulate(new Dct16x8Approx()) { dut =>
      cases.foreach(expectRectangle(dut, _, tolerance = 2))
    }
  }

  "Dct8x16Approx emits libjxl-tiny's canonical 8x16 layout" in {
    val cases = fixtures().filter(_.kind == "dct8x16")
    cases.map(_.name).toSet mustBe
      Set("constant", "x-ramp", "y-ramp", "impulse", "signed")
    simulate(new Dct8x16Approx()) { dut =>
      cases.foreach(expectRectangle(dut, _, tolerance = 2))
    }
  }
}

class DctRectangularApproxElaborationSpec extends AnyFreeSpec with Matchers {
  private def emittedTop(module: => RawModule): String = {
    val targetDir = Files.createTempDirectory("hjxl-rectangular-dct-elaborate-")
    ChiselStage.emitSystemVerilogFile(
      module,
      args = Array("--target-dir", targetDir.toString),
      firtoolOpts = Array(
        "-disable-all-randomization",
        "-strip-debug-info",
        "-default-layer-specialization=enable"
      )
    )
    val files = Files.walk(targetDir)
    try {
      val systemVerilog = files.iterator().asScala
        .filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".sv"))
        .toSeq
      systemVerilog.length mustBe 1
      Files.readString(systemVerilog.head, StandardCharsets.UTF_8)
    } finally {
      files.close()
    }
  }

  "both rectangular transform orientations elaborate with their full stream surfaces" in {
    val vertical = emittedTop(new Dct16x8Approx())
    vertical must include("module Dct16x8Approx")
    vertical must include("io_input_bits_127")
    vertical must include("io_output_bits_127")
    vertical must include("io_input_ready")
    vertical must include("io_output_valid")

    val horizontal = emittedTop(new Dct8x16Approx())
    horizontal must include("module Dct8x16Approx")
    horizontal must include("io_input_bits_127")
    horizontal must include("io_output_bits_127")
    horizontal must include("io_input_ready")
    horizontal must include("io_output_valid")
  }
}
