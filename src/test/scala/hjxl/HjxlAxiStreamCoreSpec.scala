// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.sys.process.Process
import scala.sys.process.ProcessLogger

class HjxlAxiStreamCoreSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private case class CommandResult(exitCode: Int, output: String)
  private case class StreamWord(data: BigInt, last: Boolean)

  private val config = HjxlConfig(maxFrameWidth = 8, maxFrameHeight = 8)

  private def runCommand(command: Seq[String]): CommandResult = {
    val output = scala.collection.mutable.ArrayBuffer.empty[String]
    val logger = ProcessLogger(line => output += line, line => output += line)
    val exitCode = Process(command, TestPaths.repoRoot.toFile, "PYTHONDONTWRITEBYTECODE" -> "1").!(logger)
    CommandResult(exitCode, output.mkString("\n"))
  }

  private def requireNumpy(): Unit =
    assume(
      Process(Seq("python3", "-c", "import numpy")).! == 0,
      "python3 with numpy is required for AXI-stream metadata extraction tests"
    )

  private def expectCommandSuccess(command: Seq[String]): String = {
    val result = runCommand(command)
    withClue(result.output) {
      result.exitCode mustBe 0
    }
    result.output
  }

  private def readNpyAsJson(path: Path): String =
    expectCommandSuccess(
      Seq(
        "python3",
        "-c",
        "import json, sys, numpy as np\n" +
          "print(json.dumps(np.load(sys.argv[1]).tolist(), separators=(',', ':')))\n",
        path.toString
      )
    ).trim

  private def pokeConfig(dut: HjxlAxiStreamCore, width: Int, height: Int): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(0.U)
    dut.io.config.fixedInvQacQ16.poke(0.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.fixedYtox.poke(0.S)
    dut.io.config.fixedYtob.poke(0.S)
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

  private def writeRgbPfm(path: Path, width: Int, height: Int, topDownPixels: Seq[(Float, Float, Float)]): Unit = {
    topDownPixels.length mustBe width * height
    val header = s"PF\n$width $height\n-1.0\n".getBytes("US-ASCII")
    val data = ByteBuffer.allocate(width * height * 3 * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (row <- (0 until height).reverse; x <- 0 until width) {
      val (r, g, b) = topDownPixels(row * width + x)
      data.putFloat(r)
      data.putFloat(g)
      data.putFloat(b)
    }
    Files.write(path, header ++ data.array())
  }

  private def readStreamCsv(path: Path): Seq[StreamWord] =
    Files.readString(path).replace("\r\n", "\n").trim.split("\n").toVector.drop(1).map { line =>
      val columns = line.split(",", -1)
      StreamWord(BigInt(columns(0)), columns(1).toInt != 0)
    }

  private def unpackTraceData(data: BigInt): (Int, Int, Int, Int) = {
    val stage = (data & 0xff).toInt
    val group = ((data >> 8) & 0xffff).toInt
    val index = ((data >> 24) & 0xffffffffL).toInt
    val rawValue = (data >> 56) & 0xffffffffL
    val value = if ((rawValue & 0x80000000L) != 0) (rawValue - (1L << 32)).toInt else rawValue.toInt
    (stage, group, index, value)
  }

  private def captureSingleMetadataRoute(
      traceRoute: Int,
      fixedRawQuant: Int = 0,
      fixedYtox: Int = 0,
      fixedYtob: Int = 0
  ): Seq[StreamWord] = {
    val captured = scala.collection.mutable.ArrayBuffer.empty[StreamWord]
    simulate(new HjxlAxiStreamCore(config, traceRoute = traceRoute)) { dut =>
      pokeConfig(dut, width = 1, height = 1)
      dut.io.config.enableQuant.poke(true.B)
      dut.io.config.fixedRawQuant.poke(fixedRawQuant.U)
      dut.io.config.fixedYtox.poke(fixedYtox.S)
      dut.io.config.fixedYtob.poke(fixedYtob.S)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      drivePixel(dut, rgb(10, 20, 30), last = true)
      dut.io.input.valid.poke(false.B)
      dut.io.protocolError.expect(false.B)

      dut.io.trace.ready.poke(true.B)
      var waitCycles = 0
      while (!dut.io.trace.valid.peek().litToBoolean && waitCycles < 3000) {
        dut.clock.step()
        waitCycles += 1
      }
      withClue(s"metadata route $traceRoute trace latency") {
        waitCycles must be < 3000
      }
      dut.io.trace.valid.expect(true.B)
      captured += StreamWord(
        dut.io.trace.bits.data.peekValue().asBigInt,
        dut.io.trace.bits.last.peekValue().asBigInt.testBit(0)
      )
      dut.io.trace.bits.last.expect(true.B)
      dut.clock.step()
      dut.io.trace.valid.expect(false.B)
    }
    captured.toSeq
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

  "HjxlAxiStreamCore snapshots the complete configuration through the final frame trace" in {
    simulate(new HjxlAxiStreamCore(config, traceRoute = TraceStage.RawQuantField)) { dut =>
      pokeConfig(dut, width = 2, height = 1)
      dut.io.config.enableQuant.poke(true.B)
      dut.io.config.fixedRawQuant.poke(7.U)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      drivePixel(dut, rgb(10, 20, 30), last = false)
      dut.io.busy.expect(true.B)

      // These live inputs are the next-frame configuration. Changing route,
      // dimensions, and quantization fields must not split the active frame
      // across schedulers or alter its buffered/emitted trace.
      dut.io.config.xsize.poke(1.U)
      dut.io.config.ysize.poke(1.U)
      dut.io.config.distanceQ8.poke(512.U)
      dut.io.config.fixedPointScale.poke(123.U)
      dut.io.config.fixedInvQacQ16.poke(456.U)
      dut.io.config.fixedRawQuant.poke(7.U)
      dut.io.config.fixedYtox.poke((-4).S)
      dut.io.config.fixedYtob.poke(5.S)
      dut.io.config.enableXyb.poke(true.B)
      dut.io.config.enableDct.poke(false.B)
      dut.io.config.enableQuant.poke(false.B)
      dut.io.config.enableTokenize.poke(false.B)
      dut.io.config.tokenSelect.poke(TokenTraceSelect.AcMetadata.U)

      drivePixel(dut, rgb(40, 50, 60), last = true)
      dut.io.input.valid.poke(false.B)
      dut.io.protocolError.expect(false.B)

      // Restore a valid focused-route selection, but with different metadata,
      // before draining the first frame. These are still next-frame values.
      dut.io.config.enableXyb.poke(false.B)
      dut.io.config.enableQuant.poke(true.B)
      dut.io.config.fixedRawQuant.poke(9.U)
      dut.io.trace.ready.poke(true.B)

      dut.io.trace.valid.expect(true.B)
      val (firstStage, firstGroup, firstIndex, firstValue) =
        unpackTraceData(dut.io.trace.bits.data.peekValue().asBigInt)
      firstStage must be(TraceStage.RawQuantField)
      firstGroup must be(0)
      firstIndex must be(0)
      firstValue must be(7)
      dut.io.trace.bits.last.expect(true.B)
      dut.clock.step()
      dut.io.trace.valid.expect(false.B)
      dut.io.busy.expect(false.B)

      // The updated live configuration becomes active at the next accepted
      // frame boundary.
      drivePixel(dut, rgb(70, 80, 90), last = true)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.valid.expect(true.B)
      val (stage, group, index, value) = unpackTraceData(dut.io.trace.bits.data.peekValue().asBigInt)
      stage must be(TraceStage.RawQuantField)
      group must be(0)
      index must be(0)
      value must be(9)
      dut.io.trace.bits.last.expect(true.B)
      dut.clock.step()
      dut.io.trace.valid.expect(false.B)
      dut.io.busy.expect(false.B)
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

  "HjxlAxiStreamCore consumes RGB stream CSV generated from PFM" in {
    val temp = Files.createTempDirectory("hjxl-axi-rgb-stream-input-")
    val pfm = temp.resolve("input.pfm")
    val inputStreamCsv = temp.resolve("rgb-input-stream.csv")
    val outputStreamCsv = temp.resolve("rtl-output-stream.csv")
    val traceCsv = temp.resolve("decoded-trace.csv")

    writeRgbPfm(
      pfm,
      width = 2,
      height = 1,
      topDownPixels = Seq((0.0f, 0.5f, 1.0f), (0.25f, 0.75f, -0.25f))
    )

    val convertResult = runCommand(
      Seq(
        "python3",
        "tools/hjxl_rgb_stream.py",
        "--pfm",
        pfm.toString,
        "--stream-csv",
        inputStreamCsv.toString
      )
    )
    withClue(convertResult.output) {
      convertResult.exitCode mustBe 0
    }
    convertResult.output must include("wrote 2 RGB stream words for 2x1")
    val inputRows = readStreamCsv(inputStreamCsv)
    inputRows mustBe Seq(StreamWord(rgb(0, 128, 256), last = false), StreamWord(rgb(64, 192, -64), last = true))

    val captured = scala.collection.mutable.ArrayBuffer.empty[StreamWord]
    simulate(new HjxlAxiStreamCore(config, traceRoute = TraceStage.InputPadded)) { dut =>
      pokeConfig(dut, width = 2, height = 1)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for (row <- inputRows) {
        drivePixel(dut, row.data, row.last)
      }
      dut.io.input.valid.poke(false.B)
      dut.io.protocolError.expect(false.B)

      dut.io.trace.ready.poke(true.B)
      for (_ <- 0 until 192) {
        dut.io.trace.valid.expect(true.B)
        captured += StreamWord(
          dut.io.trace.bits.data.peekValue().asBigInt,
          dut.io.trace.bits.last.peekValue().asBigInt.testBit(0)
        )
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
    }

    Files.writeString(
      outputStreamCsv,
      "data,last\n" + captured.map(row => s"${row.data},${if (row.last) 1 else 0}").mkString("\n") + "\n"
    )
    val decodeResult = runCommand(
      Seq(
        "python3",
        "tools/hjxl_stream_trace.py",
        "--stream-csv",
        outputStreamCsv.toString,
        "--trace-csv",
        traceCsv.toString,
        "--require-final-last"
      )
    )
    withClue(decodeResult.output) {
      decodeResult.exitCode mustBe 0
    }
    decodeResult.output must include("decoded 192 stream trace rows")

    val rows = Files.readString(traceCsv).replace("\r\n", "\n").trim.split("\n").toVector
    rows.head mustBe "stage,group,index,value"
    rows(1) mustBe "0,0,0,0"
    rows(2) mustBe "0,0,1,64"
    rows(65) mustBe "0,0,64,128"
    rows(66) mustBe "0,0,65,192"
    rows(129) mustBe "0,0,128,256"
    rows(130) mustBe "0,0,129,-64"
    rows.last mustBe "0,0,191,-64"
  }

  "HjxlAxiStreamCore asserts output TLAST for focused adaptive AC strategy traces" in {
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
      var waitCycles = 0
      while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < 5000) {
        dut.clock.step()
        waitCycles += 1
      }
      withClue("focused AC-strategy output latency") {
        waitCycles must be < 5000
      }
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

  "HjxlAxiStreamCore packs the selected AQ contrast grid and final TLAST" in {
    simulate(new HjxlAxiStreamCore(config, traceRoute = TraceStage.AqContrast)) { dut =>
      pokeConfig(dut, width = 1, height = 1)
      dut.io.config.enableXyb.poke(true.B)
      dut.io.config.enableQuant.poke(true.B)
      dut.io.config.tokenSelect.poke(TokenTraceSelect.AqContrast.U)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      drivePixel(dut, rgb(10, 20, 30), last = true)
      dut.io.input.valid.poke(false.B)
      dut.io.protocolError.expect(false.B)

      for (cell <- 0 until 4) {
        var waitCycles = 0
        while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < 24) {
          dut.clock.step()
          waitCycles += 1
        }
        withClue(s"AQ contrast stream cell $cell") {
          waitCycles must be < 24
          val (stage, group, index, value) =
            unpackTraceData(dut.io.trace.bits.data.peekValue().asBigInt)
          stage must be(TraceStage.AqContrast)
          group must be(0)
          index must be(cell)
          value must be >= 0
          dut.io.trace.bits.last.expect((cell == 3).B)
        }
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.busy.expect(false.B)
    }
  }

  "HjxlAxiStreamCore packs the focused AQ fuzzy-erosion block and TLAST" in {
    simulate(new HjxlAxiStreamCore(config, traceRoute = TraceStage.AqFuzzyErosion)) { dut =>
      pokeConfig(dut, width = 1, height = 1)
      dut.io.config.enableXyb.poke(true.B)
      dut.io.config.enableQuant.poke(true.B)
      dut.io.config.tokenSelect.poke(TokenTraceSelect.AqContrast.U)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      drivePixel(dut, rgb(10, 20, 30), last = true)
      dut.io.input.valid.poke(false.B)
      dut.io.protocolError.expect(false.B)

      var waitCycles = 0
      while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < 128) {
        dut.clock.step()
        waitCycles += 1
      }
      withClue("AQ fuzzy-erosion stream block") {
        waitCycles must be < 128
        val (stage, group, index, value) =
          unpackTraceData(dut.io.trace.bits.data.peekValue().asBigInt)
        stage must be(TraceStage.AqFuzzyErosion)
        group must be(0)
        index must be(0)
        value must be >= 0
        dut.io.trace.bits.last.expect(true.B)
      }
      dut.clock.step()
      dut.io.trace.valid.expect(false.B)
      dut.io.busy.expect(false.B)
    }
  }

  "HjxlAxiStreamCore packs the focused AQ strategy-mask block and TLAST" in {
    simulate(new HjxlAxiStreamCore(config, traceRoute = TraceStage.AqStrategyMask)) { dut =>
      pokeConfig(dut, width = 1, height = 1)
      dut.io.config.enableXyb.poke(true.B)
      dut.io.config.enableQuant.poke(true.B)
      dut.io.config.tokenSelect.poke(TokenTraceSelect.AqContrast.U)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      drivePixel(dut, rgb(10, 20, 30), last = true)
      dut.io.input.valid.poke(false.B)
      dut.io.protocolError.expect(false.B)

      var waitCycles = 0
      while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < 192) {
        dut.clock.step()
        waitCycles += 1
      }
      withClue("AQ strategy-mask stream block") {
        waitCycles must be < 192
        val (stage, group, index, value) =
          unpackTraceData(dut.io.trace.bits.data.peekValue().asBigInt)
        stage must be(TraceStage.AqStrategyMask)
        group must be(0)
        index must be(0)
        value must be >= 0
        dut.io.trace.bits.last.expect(true.B)
      }
      dut.clock.step()
      dut.io.trace.valid.expect(false.B)
      dut.io.busy.expect(false.B)
    }
  }

  "HjxlAxiStreamCore packs the focused signed AQ nonlinear-mask block and TLAST" in {
    simulate(new HjxlAxiStreamCore(config, traceRoute = TraceStage.AqNonlinearMask)) { dut =>
      pokeConfig(dut, width = 1, height = 1)
      dut.io.config.enableXyb.poke(true.B)
      dut.io.config.enableQuant.poke(true.B)
      dut.io.config.tokenSelect.poke(TokenTraceSelect.AqContrast.U)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      drivePixel(dut, rgb(10, 20, 30), last = true)
      dut.io.input.valid.poke(false.B)
      dut.io.protocolError.expect(false.B)

      var waitCycles = 0
      while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < 320) {
        dut.clock.step()
        waitCycles += 1
      }
      withClue("AQ nonlinear-mask stream block") {
        waitCycles must be < 320
        val (stage, group, index, value) =
          unpackTraceData(dut.io.trace.bits.data.peekValue().asBigInt)
        stage must be(TraceStage.AqNonlinearMask)
        group must be(0)
        index must be(0)
        value must be < 0
        dut.io.trace.bits.last.expect(true.B)
      }
      dut.clock.step()
      dut.io.trace.valid.expect(false.B)
      dut.io.busy.expect(false.B)
    }
  }

  "HjxlAxiStreamCore packs the focused signed AQ HF-modulation block and TLAST" in {
    simulate(new HjxlAxiStreamCore(config, traceRoute = TraceStage.AqHfModulation)) { dut =>
      pokeConfig(dut, width = 1, height = 1)
      dut.io.config.enableXyb.poke(true.B)
      dut.io.config.enableQuant.poke(true.B)
      dut.io.config.tokenSelect.poke(TokenTraceSelect.AqContrast.U)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      drivePixel(dut, rgb(10, 20, 30), last = true)
      dut.io.input.valid.poke(false.B)
      dut.io.protocolError.expect(false.B)

      var waitCycles = 0
      while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < 400) {
        dut.clock.step()
        waitCycles += 1
      }
      withClue("AQ HF-modulation stream block") {
        waitCycles must be < 400
        val (stage, group, index, value) =
          unpackTraceData(dut.io.trace.bits.data.peekValue().asBigInt)
        stage must be(TraceStage.AqHfModulation)
        group must be(0)
        index must be(0)
        value must be < 0
        dut.io.trace.bits.last.expect(true.B)
      }
      dut.clock.step()
      dut.io.trace.valid.expect(false.B)
      dut.io.busy.expect(false.B)
    }
  }

  "HjxlAxiStreamCore packs focused AQ color modulation and TLAST" in {
    simulate(new HjxlAxiStreamCore(config, traceRoute = TraceStage.AqColorModulation)) { dut =>
      pokeConfig(dut, width = 1, height = 1)
      dut.io.config.enableXyb.poke(true.B)
      dut.io.config.enableQuant.poke(true.B)
      dut.io.config.tokenSelect.poke(TokenTraceSelect.AqContrast.U)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      drivePixel(dut, rgb(10, 20, 30), last = true)
      dut.io.input.valid.poke(false.B)
      dut.io.protocolError.expect(false.B)

      var waitCycles = 0
      while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < 500) {
        dut.clock.step()
        waitCycles += 1
      }
      withClue("AQ color-modulation stream block") {
        waitCycles must be < 500
        val (stage, group, index, value) =
          unpackTraceData(dut.io.trace.bits.data.peekValue().asBigInt)
        stage must be(TraceStage.AqColorModulation)
        group must be(0)
        index must be(0)
        value must be < 0
        dut.io.trace.bits.last.expect(true.B)
      }
      dut.clock.step()
      dut.io.trace.valid.expect(false.B)
      dut.io.busy.expect(false.B)
    }
  }

  "HjxlAxiStreamCore packs focused AQ gamma modulation and TLAST" in {
    simulate(new HjxlAxiStreamCore(config, traceRoute = TraceStage.AqGammaModulation)) { dut =>
      pokeConfig(dut, width = 1, height = 1)
      dut.io.config.enableXyb.poke(true.B)
      dut.io.config.enableQuant.poke(true.B)
      dut.io.config.tokenSelect.poke(TokenTraceSelect.AqContrast.U)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      drivePixel(dut, rgb(10, 20, 30), last = true)
      dut.io.input.valid.poke(false.B)
      dut.io.protocolError.expect(false.B)

      var waitCycles = 0
      while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < 700) {
        dut.clock.step()
        waitCycles += 1
      }
      withClue("AQ gamma-modulation stream block") {
        waitCycles must be < 700
        val (stage, group, index, value) =
          unpackTraceData(dut.io.trace.bits.data.peekValue().asBigInt)
        stage must be(TraceStage.AqGammaModulation)
        group must be(0)
        index must be(0)
        value must be < 0
        dut.io.trace.bits.last.expect(true.B)
      }
      dut.clock.step()
      dut.io.trace.valid.expect(false.B)
      dut.io.busy.expect(false.B)
    }
  }

  "HjxlAxiStreamCore packs focused AQ final-map output and TLAST" in {
    simulate(new HjxlAxiStreamCore(config, traceRoute = TraceStage.AqFinalMap)) { dut =>
      pokeConfig(dut, width = 1, height = 1)
      dut.io.config.enableXyb.poke(true.B)
      dut.io.config.enableQuant.poke(true.B)
      dut.io.config.tokenSelect.poke(TokenTraceSelect.AqContrast.U)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      drivePixel(dut, rgb(10, 20, 30), last = true)
      dut.io.input.valid.poke(false.B)
      dut.io.protocolError.expect(false.B)

      var waitCycles = 0
      while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < 800) {
        dut.clock.step()
        waitCycles += 1
      }
      withClue("AQ final-map stream block") {
        waitCycles must be < 800
        val (stage, group, index, value) =
          unpackTraceData(dut.io.trace.bits.data.peekValue().asBigInt)
        stage must be(TraceStage.AqFinalMap)
        group must be(0)
        index must be(0)
        value must be > 0
        dut.io.trace.bits.last.expect(true.B)
      }
      dut.clock.step()
      dut.io.trace.valid.expect(false.B)
      dut.io.busy.expect(false.B)
    }
  }

  "HjxlAxiStreamCore packs adaptive RGB quantized traces with final-only TLAST" in {
    simulate(new HjxlAxiStreamCore(config, traceRoute = TraceStage.QuantizedAc)) { dut =>
      pokeConfig(dut, width = 1, height = 1)
      dut.io.config.enableXyb.poke(true.B)
      dut.io.config.enableDct.poke(true.B)
      dut.io.config.enableQuant.poke(true.B)
      dut.io.trace.ready.poke(true.B)
      dut.io.input.valid.poke(false.B)
      dut.clock.step()

      drivePixel(dut, rgb(64, 128, 192), last = true)
      dut.io.input.valid.poke(false.B)
      dut.io.protocolError.expect(false.B)

      var waitCycles = 0
      while (!dut.io.trace.valid.peek().litToBoolean && waitCycles < 2000) {
        dut.clock.step()
        waitCycles += 1
      }
      withClue("adaptive quantized AXI first trace") {
        waitCycles must be < 2000
      }

      val firstData = dut.io.trace.bits.data.peekValue().asBigInt
      dut.io.trace.ready.poke(false.B)
      dut.clock.step(3)
      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.data.expect(firstData.U)
      dut.io.trace.bits.last.expect(false.B)
      dut.io.trace.ready.poke(true.B)

      for (ordinal <- 0 until 198) {
        dut.io.trace.valid.expect(true.B)
        val (stage, group, index, _) = unpackTraceData(dut.io.trace.bits.data.peekValue().asBigInt)
        val expectedStage =
          if (ordinal < 192) TraceStage.QuantizedAc
          else if (ordinal < 195) TraceStage.QuantDc
          else TraceStage.NumNonzeros
        stage mustBe expectedStage
        group mustBe 0
        if (ordinal == 0) index mustBe 0
        if (ordinal == 197) index mustBe 2
        dut.io.trace.bits.last.expect((ordinal == 197).B)
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.busy.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "HjxlAxiStreamCore packs adaptive AC-metadata tokens with estimated RGB CFL" in {
    simulate(new HjxlAxiStreamCore(config, traceRoute = TraceStage.AcMetadataTokens)) { dut =>
      pokeConfig(dut, width = 1, height = 1)
      dut.io.config.enableXyb.poke(true.B)
      dut.io.config.enableQuant.poke(true.B)
      dut.io.config.enableTokenize.poke(true.B)
      dut.io.config.tokenSelect.poke(TokenTraceSelect.AcMetadata.U)
      dut.io.config.fixedYtox.poke((-7).S)
      dut.io.config.fixedYtob.poke(11.S)
      dut.io.trace.ready.poke(true.B)
      dut.io.input.valid.poke(false.B)
      dut.clock.step()

      drivePixel(dut, rgb(64, 128, 192), last = true)
      dut.io.input.valid.poke(false.B)
      dut.io.protocolError.expect(false.B)

      var waitCycles = 0
      while (!dut.io.trace.valid.peek().litToBoolean && waitCycles < 2000) {
        dut.clock.step()
        waitCycles += 1
      }
      withClue("adaptive AC-metadata AXI first trace") {
        waitCycles must be < 2000
      }

      val expected = Seq(
        (0, 2, 0, false),
        (1, 1, 0, false),
        (2, 10, 0, false),
        (3, 6, 10, false),
        (4, 0, 8, true)
      )
      for ((groupExpected, indexExpected, valueExpected, lastExpected) <- expected) {
        dut.io.trace.valid.expect(true.B)
        val (stage, group, index, value) = unpackTraceData(dut.io.trace.bits.data.peekValue().asBigInt)
        stage mustBe TraceStage.AcMetadataTokens
        group mustBe groupExpected
        index mustBe indexExpected
        value mustBe valueExpected
        dut.io.trace.bits.last.expect(lastExpected.B)
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.busy.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "HjxlAxiStreamCore metadata trace routes feed host metadata-grid extraction" in {
    requireNumpy()

    val temp = Files.createTempDirectory("hjxl-axi-stream-core-metadata-")
    val rawStreamCsv = temp.resolve("raw-quant-stream.csv")
    val ytoxStreamCsv = temp.resolve("ytox-stream.csv")
    val ytobStreamCsv = temp.resolve("ytob-stream.csv")
    val rawQuant = temp.resolve("raw-quant.npy")
    val ytox = temp.resolve("ytox.npy")
    val ytob = temp.resolve("ytob.npy")
    val expectedRawQuant = temp.resolve("expected-raw-quant.npy")
    val expectedYtox = temp.resolve("expected-ytox.npy")
    val expectedYtob = temp.resolve("expected-ytob.npy")
    val rawCaptured = captureSingleMetadataRoute(TraceStage.RawQuantField, fixedRawQuant = 200)
    val ytoxCaptured = captureSingleMetadataRoute(TraceStage.YtoxMap, fixedYtox = -7, fixedYtob = 11)
    val ytobCaptured = captureSingleMetadataRoute(TraceStage.YtobMap, fixedYtox = -7, fixedYtob = 11)

    Files.writeString(
      rawStreamCsv,
      "data,last\n" + rawCaptured.map(row => s"${row.data},${if (row.last) 1 else 0}").mkString("\n") + "\n"
    )
    Files.writeString(
      ytoxStreamCsv,
      "data,last\n" + ytoxCaptured.map(row => s"${row.data},${if (row.last) 1 else 0}").mkString("\n") + "\n"
    )
    Files.writeString(
      ytobStreamCsv,
      "data,last\n" + ytobCaptured.map(row => s"${row.data},${if (row.last) 1 else 0}").mkString("\n") + "\n"
    )

    expectCommandSuccess(
      Seq(
        "python3",
        "tools/hjxl_trace_tokens.py",
        "--stream-csv",
        rawStreamCsv.toString,
        "--raw-quant-field-npy",
        rawQuant.toString,
        "--width",
        "1",
        "--height",
        "1",
        "--require-stream-final-last"
      )
    )
    expectCommandSuccess(
      Seq(
        "python3",
        "tools/hjxl_trace_tokens.py",
        "--stream-csv",
        ytoxStreamCsv.toString,
        "--ytox-map-npy",
        ytox.toString,
        "--width",
        "1",
        "--height",
        "1",
        "--require-stream-final-last"
      )
    )
    expectCommandSuccess(
      Seq(
        "python3",
        "tools/hjxl_trace_tokens.py",
        "--stream-csv",
        ytobStreamCsv.toString,
        "--ytob-map-npy",
        ytob.toString,
        "--width",
        "1",
        "--height",
        "1",
        "--require-stream-final-last"
      )
    )
    expectCommandSuccess(
      Seq(
        "python3",
        "tools/hjxl_reference.py",
        "--width",
        "1",
        "--height",
        "1",
        "--pattern",
        "constant",
        "--fixed-raw-quant",
        "200",
        "--fixed-dct-only-raw-quant-field-npy",
        expectedRawQuant.toString,
        "--dct-only-ytox-map-npy",
        expectedYtox.toString,
        "--dct-only-ytob-map-npy",
        expectedYtob.toString
      )
    )

    readNpyAsJson(rawQuant) mustBe "[[200]]"
    readNpyAsJson(ytox) mustBe "[[0]]"
    readNpyAsJson(ytob) mustBe "[[0]]"

    expectCommandSuccess(
      Seq(
        "python3",
        "tools/hjxl_compare_tokens.py",
        "--expected-raw-quant-field-npy",
        expectedRawQuant.toString,
        "--actual-raw-quant-field-npy",
        rawQuant.toString,
        "--expected-ytox-map-npy",
        expectedYtox.toString,
        "--actual-ytox-map-npy",
        ytox.toString,
        "--expected-ytob-map-npy",
        expectedYtob.toString,
        "--actual-ytob-map-npy",
        ytob.toString
      )
    ) must include("artifacts match")
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
