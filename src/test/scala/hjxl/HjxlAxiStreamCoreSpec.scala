// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import java.nio.file.Files
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.sys.process.Process
import scala.sys.process.ProcessLogger

class HjxlAxiStreamCoreSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private case class CommandResult(exitCode: Int, output: String)

  private val config = HjxlConfig(maxFrameWidth = 8, maxFrameHeight = 8)

  private def runCommand(command: Seq[String]): CommandResult = {
    val output = scala.collection.mutable.ArrayBuffer.empty[String]
    val logger = ProcessLogger(line => output += line, line => output += line)
    val exitCode = Process(command, TestPaths.repoRoot.toFile, "PYTHONDONTWRITEBYTECODE" -> "1").!(logger)
    CommandResult(exitCode, output.mkString("\n"))
  }

  private def pokeConfig(dut: HjxlAxiStreamCore, width: Int, height: Int): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(0.U)
    dut.io.config.fixedInvQacQ16.poke(0.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.enableXyb.poke(false.B)
    dut.io.config.enableDct.poke(false.B)
    dut.io.config.enableQuant.poke(false.B)
    dut.io.config.enableTokenize.poke(false.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.Dc.U)
    dut.io.clearProtocolError.poke(false.B)
  }

  private def drivePixel(dut: HjxlAxiStreamCore, data: BigInt, last: Boolean): Unit = {
    dut.io.input.valid.poke(true.B)
    dut.io.input.bits.data.poke(data.U)
    dut.io.input.bits.last.poke(last.B)
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
  }

  private def rgb(r: Int, g: Int, b: Int): BigInt =
    BigInt(r & 0xffff) | (BigInt(g & 0xffff) << 16) | (BigInt(b & 0xffff) << 32)

  private def unpackTraceData(data: BigInt): (Int, Int, Int, Int) = {
    val stage = (data & 0xff).toInt
    val group = ((data >> 8) & 0xffff).toInt
    val index = ((data >> 24) & 0xffffffffL).toInt
    val rawValue = (data >> 56) & 0xffffffffL
    val value = if ((rawValue & 0x80000000L) != 0) (rawValue - (1L << 32)).toInt else rawValue.toInt
    (stage, group, index, value)
  }

  "HjxlAxiStreamCore generates raster coordinates and packs trace rows" in {
    simulate(new HjxlAxiStreamCore(config, traceRoute = TraceStage.InputPadded)) { dut =>
      pokeConfig(dut, width = 2, height = 1)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      drivePixel(dut, rgb(10, 20, 30), last = false)
      drivePixel(dut, rgb(40, 50, 60), last = true)
      dut.io.input.valid.poke(false.B)
      dut.io.protocolError.expect(false.B)

      dut.io.trace.ready.poke(true.B)
      for ((expectedValue, expectedIndex) <- Seq(10 -> 0, 40 -> 1)) {
        dut.io.trace.valid.expect(true.B)
        val (stage, group, index, value) = unpackTraceData(dut.io.trace.bits.data.peekValue().asBigInt)
        stage must be(TraceStage.InputPadded)
        group must be(0)
        index must be(expectedIndex)
        value must be(expectedValue)
        dut.io.trace.bits.last.expect(false.B)
        dut.clock.step()
      }

      for (expectedIndex <- 2 until 192) {
        dut.io.trace.valid.expect(true.B)
        val (stage, group, index, _) = unpackTraceData(dut.io.trace.bits.data.peekValue().asBigInt)
        stage must be(TraceStage.InputPadded)
        group must be(0)
        index must be(expectedIndex)
        dut.io.trace.bits.last.expect((expectedIndex == 191).B)
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
    }
  }

  "HjxlAxiStreamCore packed output is decoded by the host stream tool" in {
    val captured = scala.collection.mutable.ArrayBuffer.empty[(BigInt, Boolean)]

    simulate(new HjxlAxiStreamCore(config, traceRoute = TraceStage.InputPadded)) { dut =>
      pokeConfig(dut, width = 2, height = 1)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      drivePixel(dut, rgb(10, 20, 30), last = false)
      drivePixel(dut, rgb(40, 50, 60), last = true)
      dut.io.input.valid.poke(false.B)

      dut.io.trace.ready.poke(true.B)
      for (_ <- 0 until 192) {
        dut.io.trace.valid.expect(true.B)
        captured += (dut.io.trace.bits.data.peekValue().asBigInt -> dut.io.trace.bits.last.peekValue().asBigInt.testBit(0))
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
    }

    val temp = Files.createTempDirectory("hjxl-axi-stream-core-decode-")
    val streamCsv = temp.resolve("stream.csv")
    val traceCsv = temp.resolve("trace.csv")
    Files.writeString(
      streamCsv,
      "data,last\n" + captured.map { case (data, last) => s"$data,${if (last) 1 else 0}" }.mkString("\n") + "\n"
    )

    val result = runCommand(
      Seq(
        "python3",
        "tools/hjxl_stream_trace.py",
        "--stream-csv",
        streamCsv.toString,
        "--trace-csv",
        traceCsv.toString,
        "--require-final-last"
      )
    )
    withClue(result.output) {
      result.exitCode mustBe 0
    }
    result.output must include("decoded 192 stream trace rows")

    val rows = Files.readString(traceCsv).replace("\r\n", "\n").trim.split("\n").toVector
    rows.head mustBe "stage,group,index,value"
    rows(1) mustBe "0,0,0,10"
    rows(2) mustBe "0,0,1,40"
    rows.last mustBe "0,0,191,60"
  }

  "HjxlAxiStreamCore asserts output TLAST for fixed-size AC strategy traces" in {
    simulate(new HjxlAxiStreamCore(config, traceRoute = TraceStage.AcStrategy)) { dut =>
      pokeConfig(dut, width = 2, height = 1)
      dut.io.config.enableQuant.poke(true.B)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      drivePixel(dut, rgb(10, 20, 30), last = false)
      drivePixel(dut, rgb(40, 50, 60), last = true)
      dut.io.input.valid.poke(false.B)

      dut.io.trace.ready.poke(true.B)
      dut.io.trace.valid.expect(true.B)
      val (stage, group, index, value) = unpackTraceData(dut.io.trace.bits.data.peekValue().asBigInt)
      stage must be(TraceStage.AcStrategy)
      group must be(0)
      index must be(0)
      value must be(AcStrategyCode.encoded(AcStrategyCode.Dct, isFirstBlock = true))
      dut.io.trace.bits.last.expect(true.B)
      dut.clock.step()
      dut.io.trace.valid.expect(false.B)
    }
  }

  "HjxlAxiStreamCore reports mismatched input TLAST" in {
    simulate(new HjxlAxiStreamCore(config, traceRoute = TraceStage.InputPadded)) { dut =>
      pokeConfig(dut, width = 2, height = 1)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      drivePixel(dut, rgb(10, 20, 30), last = true)
      dut.io.protocolError.expect(true.B)

      dut.io.input.valid.poke(false.B)
      dut.io.clearProtocolError.poke(true.B)
      dut.clock.step()
      dut.io.protocolError.expect(false.B)
    }
  }
}
