// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import java.nio.file.Files
import java.nio.file.Path
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.sys.process.Process

class DistanceParamsLookupSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val libjxlTinyRoot =
    Path.of(sys.env.getOrElse("LIBJXL_TINY", "/Users/yunhocho/GitHub/libjxl-tiny"))

  private def hostDistanceMetadata(): (Int, Seq[Int]) = {
    val script =
      """import json, sys
        |sys.path.insert(0, "tools")
        |from hjxl_manifest_header import distance_metadata
        |print(json.dumps(distance_metadata(), separators=(",", ":")))
        |""".stripMargin
    val output = Process(
      Seq("python3", "-c", script),
      TestPaths.repoRoot.toFile,
      "PYTHONDONTWRITEBYTECODE" -> "1"
    ).!!.trim
    val fallback = """"fallback_q8":(\d+)""".r
      .findFirstMatchIn(output)
      .getOrElse(fail(s"missing fallback_q8 in host distance metadata: $output"))
      .group(1)
      .toInt
    val supportedText = """"supported_q8":\[(.*?)\]""".r
      .findFirstMatchIn(output)
      .getOrElse(fail(s"missing supported_q8 in host distance metadata: $output"))
      .group(1)
    val supported =
      if (supportedText.isEmpty) Seq.empty
      else supportedText.split(",").toSeq.map(_.toInt)
    fallback -> supported
  }

  "DistanceParamsLookup emits libjxl-tiny distance parameters for supported Q8 distances" in {
    simulate(new DistanceParamsLookup) { dut =>
      for (entry <- DistanceParamsLookup.Entries) {
        withClue(s"distanceQ8=${entry.distanceQ8}") {
          dut.io.distanceQ8.poke(entry.distanceQ8.U)
          dut.clock.step()
          dut.io.supported.expect(true.B)
          dut.io.params.scaleQ16.expect(entry.scaleQ16.U)
          dut.io.params.invQacQ16.expect(entry.invQacQ16.U)
          dut.io.params.aqScaleQ24.expect(entry.aqScaleQ24.U)
          dut.io.params.aqDampenQ24.expect(entry.aqDampenQ24.U)
          dut.io.params.aqInvGlobalScaleQ24.expect(entry.aqInvGlobalScaleQ24.U)
          dut.io.params.quantDc.expect(entry.quantDc.U)
          dut.io.params.xQmMultiplierQ16.expect(entry.xQmMultiplierQ16.U)
          dut.io.params.epfIters.expect(entry.epfIters.U)
          for (channel <- 0 until 3) {
            dut.io.params.invDcFactorQ16(channel).expect(entry.invDcFactorQ16(channel).U)
          }
        }
      }
    }
  }

  "DistanceParamsLookup falls back to distance 1 for unsupported distances" in {
    simulate(new DistanceParamsLookup) { dut =>
      val fallback = DistanceParamsLookup.Default
      dut.io.distanceQ8.poke(333.U)
      dut.clock.step()
      dut.io.supported.expect(false.B)
      dut.io.params.scaleQ16.expect(fallback.scaleQ16.U)
      dut.io.params.invQacQ16.expect(fallback.invQacQ16.U)
      dut.io.params.aqScaleQ24.expect(fallback.aqScaleQ24.U)
      dut.io.params.aqDampenQ24.expect(fallback.aqDampenQ24.U)
      dut.io.params.aqInvGlobalScaleQ24.expect(fallback.aqInvGlobalScaleQ24.U)
      dut.io.params.quantDc.expect(fallback.quantDc.U)
      dut.io.params.xQmMultiplierQ16.expect(fallback.xQmMultiplierQ16.U)
      dut.io.params.epfIters.expect(fallback.epfIters.U)
      for (channel <- 0 until 3) {
        dut.io.params.invDcFactorQ16(channel).expect(fallback.invDcFactorQ16(channel).U)
      }
    }
  }

  "host distance metadata mirrors the RTL lookup table" in {
    val (fallback, supported) = hostDistanceMetadata()

    fallback mustBe DistanceParamsLookup.Default.distanceQ8
    supported mustBe DistanceParamsLookup.Entries.map(_.distanceQ8)
  }

  "adaptive-quantization distance scalars mirror libjxl-tiny" in {
    assume(Files.isDirectory(libjxlTinyRoot.resolve("python")))
    val distances = DistanceParamsLookup.Entries.map(_.distanceQ8).mkString(",")
    val script =
      s"""import sys
         |sys.path.insert(0, "tools")
         |from hjxl_reference import fixed_aq_final_scalars_q24
         |for q8 in [$distances]:
         |    print(q8, *fixed_aq_final_scalars_q24(q8 / 256.0))
         |""".stripMargin
    val rows = Process(
      Seq("python3", "-c", script),
      TestPaths.repoRoot.toFile,
      "LIBJXL_TINY" -> libjxlTinyRoot.toString,
      "PYTHONDONTWRITEBYTECODE" -> "1"
    ).!!.linesIterator.map { line =>
      val values = line.split(" ").map(_.toLong)
      values.length mustBe 4
      values
    }.toSeq

    for ((entry, values) <- DistanceParamsLookup.Entries.zip(rows)) {
      values(0) mustBe entry.distanceQ8.toLong
      values(1) mustBe entry.aqScaleQ24.toLong
      values(2) mustBe entry.aqDampenQ24.toLong
      values(3) mustBe entry.aqInvGlobalScaleQ24
    }
  }
}
