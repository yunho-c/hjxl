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
import scala.sys.process.Process
import scala.sys.process.ProcessLogger

class AdaptiveQuantizationSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val fractionBits = AdaptiveQuantizationFixedPoint.FractionBits
  private val scale = BigInt(1) << fractionBits
  private val config = HjxlConfig(maxFrameWidth = 24, maxFrameHeight = 16)
  private val libjxlTinyRoot =
    Path.of(sys.env.getOrElse("LIBJXL_TINY", "/Users/yunhocho/GitHub/libjxl-tiny"))

  private case class AqRow(
      block: Int,
      blockX: Int,
      blockY: Int,
      aqMapQ24: BigInt,
      invGlobalScaleQ24: BigInt,
      referenceRawQuant: Int,
      fixedRawQuant: Int
  )

  private def requireReferenceTools(): Unit = {
    assume(
      Files.isDirectory(libjxlTinyRoot.resolve("python")),
      s"libjxl-tiny Python checkout not found at $libjxlTinyRoot"
    )
    assume(
      Process(Seq("python3", "-c", "import numpy")).! == 0,
      "python3 with numpy is required for adaptive-quantization fixtures"
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

  private def readAqRows(path: Path): Seq[AqRow] = {
    val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
    lines.get(0) mustBe
      "block,block_x,block_y,aq_map_q24,inv_global_scale_q24,reference_raw_quant,fixed_raw_quant"
    lines.toArray(new Array[String](0)).drop(1).toSeq.map { line =>
      val columns = line.split(",", -1)
      columns.length mustBe 7
      AqRow(
        block = columns(0).toInt,
        blockX = columns(1).toInt,
        blockY = columns(2).toInt,
        aqMapQ24 = BigInt(columns(3)),
        invGlobalScaleQ24 = BigInt(columns(4)),
        referenceRawQuant = columns(5).toInt,
        fixedRawQuant = columns(6).toInt
      )
    }
  }

  private def pokeConfig(
      dut: FramePreparedAqRawQuantTraceStage,
      width: Int,
      height: Int
  ): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(0.U)
    dut.io.config.fixedInvQacQ16.poke(0.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.fixedYtox.poke(0.S)
    dut.io.config.fixedYtob.poke(0.S)
    dut.io.config.enableXyb.poke(false.B)
    dut.io.config.enableDct.poke(true.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(false.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.AcMetadata.U)
  }

  private def bufferInput(
      dut: FramePreparedAqRawQuantTraceStage,
      aqMapQ24: BigInt
  ): Unit = {
    dut.io.trace.ready.poke(false.B)
    dut.io.input.valid.poke(true.B)
    dut.io.input.bits.poke(aqMapQ24.U)
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
    dut.io.input.valid.poke(false.B)
  }

  private def expectBufferedTrace(
      dut: FramePreparedAqRawQuantTraceStage,
      index: Int,
      rawQuant: Int,
      last: Boolean
  ): Unit = {
    dut.io.trace.valid.expect(true.B)
    dut.io.trace.bits.stage.expect(TraceStage.RawQuantField.U)
    dut.io.trace.bits.group.expect(0.U)
    dut.io.trace.bits.index.expect(index.U)
    dut.io.trace.bits.value.expect(rawQuant.S)
    dut.io.traceLast.expect(last.B)
  }

  "AqMapToRawQuant rounds the Q24 product and clamps to the byte domain" in {
    simulate(new AqMapToRawQuant()) { dut =>
      val cases = Seq(
        (BigInt(0), scale, 1),
        (scale / 2, 3 * scale, 2),
        (4 * scale, scale, 4),
        (255 * scale, 2 * scale, 255)
      )
      for (((aqMap, invScale, expected), index) <- cases.zipWithIndex) {
        withClue(s"fixed-point case $index") {
          dut.io.aqMapQ.poke(aqMap.U)
          dut.io.invGlobalScaleQ.poke(invScale.U)
          dut.clock.step()
          dut.io.rawQuant.expect(expected.U)
        }
      }
    }
  }

  "FramePreparedAqRawQuantTraceStage matches libjxl-tiny AQ fixtures exactly" in {
    requireReferenceTools()
    val width = 17
    val height = 9
    val fixtureSpecs = Seq(
      ("gradient", "0.5"),
      ("checkerboard", "1.0"),
      ("random", "8.0")
    )
    val fixtures = fixtureSpecs.map { case (pattern, distance) =>
      val csv = Files.createTempFile(s"hjxl-aq-$pattern-", ".csv")
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
          distance,
          "--dct-only-aq-map-q24-csv",
          csv.toString
        ),
        "LIBJXL_TINY" -> libjxlTinyRoot.toString
      )
      val rows = readAqRows(csv)
      rows.map(_.block) mustBe rows.indices
      rows.foreach(row => row.fixedRawQuant mustBe row.referenceRawQuant)
      rows
    }

    simulate(new FramePreparedAqRawQuantTraceStage(config)) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      pokeConfig(dut, width, height)
      dut.clock.step()

      for ((rows, fixtureIndex) <- fixtures.zipWithIndex) {
        rows.length mustBe 6
        pokeConfig(dut, width, height)
        dut.io.invGlobalScaleQ.poke(rows.head.invGlobalScaleQ24.U)
        for ((row, rowIndex) <- rows.zipWithIndex) {
          withClue(s"AQ fixture $fixtureIndex row $rowIndex") {
            row.blockX mustBe rowIndex % 3
            row.blockY mustBe rowIndex / 3
            bufferInput(dut, row.aqMapQ24)
            expectBufferedTrace(
              dut,
              index = row.block,
              rawQuant = row.referenceRawQuant,
              last = rowIndex == rows.length - 1
            )
            dut.io.trace.ready.poke(true.B)
            dut.clock.step()
            dut.io.trace.ready.poke(false.B)
          }
        }
        dut.io.trace.valid.expect(false.B)
        dut.io.busy.expect(false.B)
        dut.io.overflow.expect(false.B)
      }
    }
  }

  "FramePreparedAqRawQuantTraceStage holds backpressured traces and snapshots frame controls" in {
    simulate(new FramePreparedAqRawQuantTraceStage(config)) { dut =>
      pokeConfig(dut, width = 16, height = 8)
      dut.io.invGlobalScaleQ.poke(scale.U)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      bufferInput(dut, 4 * scale)
      expectBufferedTrace(dut, index = 0, rawQuant = 4, last = false)

      pokeConfig(dut, width = 8, height = 8)
      dut.io.invGlobalScaleQ.poke((3 * scale).U)
      dut.io.input.valid.poke(false.B)
      for (cycle <- 0 until 4) {
        withClue(s"backpressured cycle $cycle") {
          dut.io.input.ready.expect(false.B)
          expectBufferedTrace(dut, index = 0, rawQuant = 4, last = false)
        }
        dut.clock.step()
      }

      dut.io.input.valid.poke(true.B)
      dut.io.input.bits.poke((scale / 2).U)
      dut.io.trace.ready.poke(true.B)
      dut.io.input.ready.expect(true.B)
      dut.clock.step()
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)

      // The first frame retained its original two-block geometry and Q24 scale.
      expectBufferedTrace(dut, index = 1, rawQuant = 1, last = true)
      dut.io.input.ready.expect(false.B)
      dut.clock.step(3)
      expectBufferedTrace(dut, index = 1, rawQuant = 1, last = true)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      // The next frame observes the controls changed while the first was active.
      dut.io.trace.ready.poke(false.B)
      bufferInput(dut, scale / 2)
      expectBufferedTrace(dut, index = 0, rawQuant = 2, last = true)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()
      dut.io.trace.valid.expect(false.B)
      dut.io.busy.expect(false.B)

      pokeConfig(dut, width = 0, height = 8)
      dut.io.input.valid.poke(true.B)
      dut.io.input.ready.expect(false.B)
      dut.io.overflow.expect(true.B)
    }
  }
}

class AdaptiveQuantizationElaborationSpec extends AnyFreeSpec with Matchers {
  "the prepared AQ trace stage emits a narrow standalone RTL interface" in {
    val targetDir = Files.createTempDirectory("hjxl-prepared-aq-elaborate-")
    ChiselStage.emitSystemVerilogFile(
      new FramePreparedAqRawQuantTraceStage(HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 8)),
      args = Array("--target-dir", targetDir.toString),
      firtoolOpts = Array(
        "-disable-all-randomization",
        "-strip-debug-info",
        "-default-layer-specialization=enable"
      )
    )

    val top = Files.readString(
      targetDir.resolve("FramePreparedAqRawQuantTraceStage.sv"),
      StandardCharsets.UTF_8
    )
    val primitive = Files.readString(targetDir.resolve("AqMapToRawQuant.sv"), StandardCharsets.UTF_8)
    top must include("module FramePreparedAqRawQuantTraceStage")
    top must include("io_invGlobalScaleQ")
    top must include("io_input_ready")
    top must include("io_input_valid")
    top must include("io_input_bits")
    top must include("io_trace_bits_value")
    top must include("io_traceLast")
    top must include("io_busy")
    top must include("io_overflow")
    top must include("AqMapToRawQuant converter")
    primitive must include("module AqMapToRawQuant")
    primitive must include("io_aqMapQ")
    primitive must include("io_invGlobalScaleQ")
    primitive must include("io_rawQuant")
  }
}
