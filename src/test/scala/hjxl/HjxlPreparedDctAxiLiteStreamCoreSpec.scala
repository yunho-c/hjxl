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

class HjxlPreparedDctAxiLiteStreamCoreSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 8)
  private val width = 16
  private val height = 8
  private val pattern = "gradient"
  private val libjxlTinyRoot =
    Path.of(sys.env.getOrElse("LIBJXL_TINY", "/Users/yunhocho/GitHub/libjxl-tiny"))

  private case class CommandResult(exitCode: Int, output: String)
  private case class StreamWord(data: BigInt, last: Boolean)

  private def requireReferenceTools(): Unit = {
    assume(
      Files.isDirectory(libjxlTinyRoot.resolve("python")),
      s"libjxl-tiny Python checkout not found at $libjxlTinyRoot"
    )
    assume(
      Process(Seq("python3", "-c", "import numpy")).! == 0,
      "python3 with numpy is required for controlled prepared-DCT stream oracle tests"
    )
  }

  private def runCommand(command: Seq[String], extraEnv: (String, String)*): CommandResult = {
    val output = scala.collection.mutable.ArrayBuffer.empty[String]
    val logger = ProcessLogger(line => output += line, line => output += line)
    val exitCode = Process(command, TestPaths.repoRoot.toFile, extraEnv: _*).!(logger)
    CommandResult(exitCode, output.mkString("\n"))
  }

  private def expectSuccess(command: Seq[String], extraEnv: (String, String)*): String = {
    val result = runCommand(command, extraEnv: _*)
    withClue(result.output) {
      result.exitCode mustBe 0
    }
    result.output
  }

  private def readStreamCsv(path: Path): Seq[StreamWord] =
    Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toVector.drop(1).map { line =>
      val columns = line.split(",", -1)
      StreamWord(BigInt(columns(0)), columns(1).toInt != 0)
    }

  private def writeStreamCsv(path: Path, rows: Seq[StreamWord]): Unit =
    Files.writeString(
      path,
      "data,last\n" + rows.map(row => s"${row.data},${if (row.last) 1 else 0}").mkString("\n") + "\n"
    )

  private def init(dut: HjxlPreparedDctAxiLiteStreamCore): Unit = {
    dut.io.control.aw.valid.poke(false.B)
    dut.io.control.aw.bits.addr.poke(0.U)
    dut.io.control.w.valid.poke(false.B)
    dut.io.control.w.bits.data.poke(0.U)
    dut.io.control.w.bits.strb.poke(0.U)
    dut.io.control.b.ready.poke(false.B)
    dut.io.control.ar.valid.poke(false.B)
    dut.io.control.ar.bits.addr.poke(0.U)
    dut.io.control.r.ready.poke(false.B)
    dut.io.input.valid.poke(false.B)
    dut.io.input.bits.data.poke(0.U)
    dut.io.input.bits.last.poke(false.B)
    dut.io.trace.ready.poke(false.B)
    dut.clock.step()
  }

  private def axiWrite(
      dut: HjxlPreparedDctAxiLiteStreamCore,
      address: Int,
      data: BigInt,
      strb: Int = 0xf
  ): Int = {
    dut.io.control.aw.valid.poke(true.B)
    dut.io.control.aw.bits.addr.poke(address.U)
    dut.io.control.w.valid.poke(true.B)
    dut.io.control.w.bits.data.poke(data.U)
    dut.io.control.w.bits.strb.poke(strb.U)
    dut.io.control.aw.ready.expect(true.B)
    dut.io.control.w.ready.expect(true.B)
    dut.clock.step()

    dut.io.control.aw.valid.poke(false.B)
    dut.io.control.w.valid.poke(false.B)
    dut.clock.step()

    dut.io.control.b.valid.expect(true.B)
    val resp = dut.io.control.b.bits.resp.peekValue().asBigInt.toInt
    dut.io.control.b.ready.poke(true.B)
    dut.clock.step()
    dut.io.control.b.ready.poke(false.B)
    resp
  }

  private def axiRead(dut: HjxlPreparedDctAxiLiteStreamCore, address: Int): (BigInt, Int) = {
    dut.io.control.ar.valid.poke(true.B)
    dut.io.control.ar.bits.addr.poke(address.U)
    dut.io.control.ar.ready.expect(true.B)
    dut.clock.step()

    dut.io.control.ar.valid.poke(false.B)
    dut.io.control.r.valid.expect(true.B)
    val data = dut.io.control.r.bits.data.peekValue().asBigInt
    val resp = dut.io.control.r.bits.resp.peekValue().asBigInt.toInt
    dut.io.control.r.ready.poke(true.B)
    dut.clock.step()
    dut.io.control.r.ready.poke(false.B)
    data -> resp
  }

  private def driveStreamWord(
      dut: HjxlPreparedDctAxiLiteStreamCore,
      data: BigInt,
      last: Boolean,
      clue: String
  ): Unit = {
    dut.io.input.valid.poke(true.B)
    dut.io.input.bits.data.poke(data.U)
    dut.io.input.bits.last.poke(last.B)
    var cycles = 0
    while (dut.io.input.ready.peekValue().asBigInt == 0 && cycles < 64) {
      dut.clock.step()
      cycles += 1
    }
    withClue(clue) {
      cycles must be < 64
    }
    dut.clock.step()
  }

  private def zeroPreparedBlockWords(quant: Int): Seq[BigInt] =
    Seq(
      BigInt(quant),
      BigInt(QuantizeDct8x8Block.DefaultScaleQ16),
      BigInt(QuantizeDct8x8Block.DefaultInvQacQ16),
      BigInt(QuantizeDct8x8Block.DefaultInvDcFactorQ16(0)),
      BigInt(QuantizeDct8x8Block.DefaultInvDcFactorQ16(1)),
      BigInt(QuantizeDct8x8Block.DefaultInvDcFactorQ16(2)),
      BigInt(QuantizeDct8x8Block.DefaultQmMultiplierQ16),
      BigInt(0),
      BigInt(0)
    ) ++ Seq.fill(PreparedDctStreamLayout.CoefficientWords)(BigInt(0))

  private def zeroCombinedTraceCount(blockCount: Int): Int = {
    val metadataTokenCount = 1 * 1 * 2 + blockCount * 3
    blockCount * 3 + blockCount + metadataTokenCount + blockCount * 3
  }

  "HjxlPreparedDctAxiLiteStreamCore exposes the common configuration register map" in {
    simulate(new HjxlPreparedDctAxiLiteStreamCore(config)) { dut =>
      init(dut)

      axiWrite(dut, HjxlAxiLiteRegister.Xsize, 16) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.Ysize, 8) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.DistanceQ8, 512) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.FixedPointScale, 0x1234) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.FixedInvQacQ16, 0x56789abcL) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.FixedRawQuant, 7) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.Flags, 0x20e) must be(AxiLiteResponse.Okay)

      axiRead(dut, HjxlAxiLiteRegister.Xsize) must be(BigInt(16) -> AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.Ysize) must be(BigInt(8) -> AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.DistanceQ8) must be(BigInt(512) -> AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.FixedPointScale) must be(BigInt(0x1234) -> AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.FixedInvQacQ16) must be(BigInt(0x56789abcL) -> AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.FixedRawQuant) must be(BigInt(7) -> AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.Flags) must be(BigInt(0x20e) -> AxiLiteResponse.Okay)
      axiWrite(dut, 0x40, 0) must be(AxiLiteResponse.Decerr)
      axiRead(dut, 0x40) must be(BigInt(0) -> AxiLiteResponse.Decerr)
    }
  }

  "HjxlPreparedDctAxiLiteStreamCore uses AXI-Lite size registers for prepared stream framing" in {
    val words = zeroPreparedBlockWords(1) ++ zeroPreparedBlockWords(2)
    val expectedTraceCount = zeroCombinedTraceCount(blockCount = 2)
    simulate(new HjxlPreparedDctAxiLiteStreamCore(config)) { dut =>
      init(dut)
      axiWrite(dut, HjxlAxiLiteRegister.Xsize, 16) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.Ysize, 8) must be(AxiLiteResponse.Okay)

      for ((word, ordinal) <- words.zipWithIndex) {
        driveStreamWord(dut, word, last = ordinal == words.length - 1, s"controlled prepared word $ordinal")
      }
      dut.io.input.valid.poke(false.B)
      dut.io.protocolError.expect(false.B)

      dut.io.trace.ready.poke(true.B)
      for (ordinal <- 0 until expectedTraceCount) {
        var cycles = 0
        while (dut.io.trace.valid.peekValue().asBigInt == 0 && cycles < 512) {
          dut.clock.step()
          cycles += 1
        }
        withClue(s"controlled trace $ordinal") {
          cycles must be < 512
        }
        dut.io.trace.bits.last.expect((ordinal == expectedTraceCount - 1).B)
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "HjxlPreparedDctAxiLiteStreamCore controlled stream assembles to libjxl-tiny codestream bytes" in {
    requireReferenceTools()

    val temp = Files.createTempDirectory("hjxl-prepared-dct-axi-lite-stream-codestream-")
    val preparedJson = temp.resolve("prepared-blocks.json")
    val inputStreamCsv = temp.resolve("prepared-input-stream.csv")
    val outputStreamCsv = temp.resolve("rtl-token-stream.csv")
    val directFrame = temp.resolve("direct-frame.bin")
    val directCodestream = temp.resolve("direct.jxl")
    val assembledFrame = temp.resolve("assembled-frame.bin")
    val assembledCodestream = temp.resolve("assembled.jxl")

    expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_reference.py",
        "--width",
        width.toString,
        "--height",
        height.toString,
        "--pattern",
        pattern,
        "--dct-only-prepared-blocks-json",
        preparedJson.toString,
        "--dct-only-frame-bin",
        directFrame.toString,
        "--dct-only-codestream-bin",
        directCodestream.toString
      ),
      "LIBJXL_TINY" -> libjxlTinyRoot.toString,
      "PYTHONDONTWRITEBYTECODE" -> "1"
    )

    expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--prepared-json",
        preparedJson.toString,
        "--input-stream-csv",
        inputStreamCsv.toString
      ),
      "PYTHONDONTWRITEBYTECODE" -> "1"
    )

    val inputRows = readStreamCsv(inputStreamCsv)
    inputRows.length mustBe 2 * PreparedDctStreamLayout.WordsPerBlock
    inputRows.dropRight(1).exists(_.last) mustBe false
    inputRows.last.last mustBe true

    val outputRows = scala.collection.mutable.ArrayBuffer.empty[StreamWord]
    simulate(new HjxlPreparedDctAxiLiteStreamCore(config)) { dut =>
      init(dut)
      axiWrite(dut, HjxlAxiLiteRegister.Xsize, width) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.Ysize, height) must be(AxiLiteResponse.Okay)

      for ((row, ordinal) <- inputRows.zipWithIndex) {
        driveStreamWord(dut, row.data, row.last, s"controlled oracle stream input word $ordinal")
      }
      dut.io.input.valid.poke(false.B)
      dut.io.protocolError.expect(false.B)

      dut.io.trace.ready.poke(true.B)
      var cycles = 0
      var sawLast = false
      while (!sawLast && cycles < 8192) {
        if (dut.io.trace.valid.peekValue().asBigInt != 0) {
          val data = dut.io.trace.bits.data.peekValue().asBigInt
          val last = dut.io.trace.bits.last.peekValue().asBigInt != 0
          outputRows += StreamWord(data, last)
          sawLast = last
        }
        dut.clock.step()
        cycles += 1
      }
      withClue("controlled prepared-DCT token stream did not terminate with TLAST") {
        sawLast mustBe true
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
    outputRows.nonEmpty mustBe true
    outputRows.dropRight(1).exists(_.last) mustBe false
    outputRows.last.last mustBe true
    writeStreamCsv(outputStreamCsv, outputRows.toSeq)

    expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_trace_to_codestream.py",
        "--stream-csv",
        outputStreamCsv.toString,
        "--require-stream-final-last",
        "--width",
        width.toString,
        "--height",
        height.toString,
        "--frame-bin",
        assembledFrame.toString,
        "--codestream-bin",
        assembledCodestream.toString,
        "--expect-frame-bin",
        directFrame.toString,
        "--expect-codestream-bin",
        directCodestream.toString
      ),
      "LIBJXL_TINY" -> libjxlTinyRoot.toString,
      "PYTHONDONTWRITEBYTECODE" -> "1"
    )

    Files.readAllBytes(assembledFrame).toSeq mustBe Files.readAllBytes(directFrame).toSeq
    Files.readAllBytes(assembledCodestream).toSeq mustBe Files.readAllBytes(directCodestream).toSeq
  }

  "HjxlPreparedDctAxiLiteStreamCore clears prepared stream protocol errors through AXI-Lite" in {
    simulate(new HjxlPreparedDctAxiLiteStreamCore(config)) { dut =>
      init(dut)
      axiWrite(dut, HjxlAxiLiteRegister.Xsize, 16) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.Ysize, 8) must be(AxiLiteResponse.Okay)

      driveStreamWord(dut, data = 1, last = true, clue = "early prepared TLAST")
      dut.io.input.valid.poke(false.B)
      dut.io.protocolError.expect(true.B)
      axiRead(dut, HjxlAxiLiteRegister.StatusControl) must be(BigInt(1) -> AxiLiteResponse.Okay)

      axiWrite(dut, HjxlAxiLiteRegister.StatusControl, 1) must be(AxiLiteResponse.Okay)
      dut.io.protocolError.expect(false.B)
      axiRead(dut, HjxlAxiLiteRegister.StatusControl) must be(BigInt(0) -> AxiLiteResponse.Okay)
    }
  }

  "HjxlPreparedDctAxiLiteStreamCore emits the controlled prepared stream shell" in {
    val targetDir = Files.createTempDirectory("hjxl-prepared-dct-axi-lite-stream-elaborate-")
    ChiselStage.emitSystemVerilogFile(
      new HjxlPreparedDctAxiLiteStreamCore(config),
      args = Array("--target-dir", targetDir.toString),
      firtoolOpts = Array(
        "-disable-all-randomization",
        "-strip-debug-info",
        "-default-layer-specialization=enable"
      )
    )
    val stream = Files.walk(targetDir)
    try {
      val text = stream
        .iterator()
        .asScala
        .filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".sv"))
        .map(path => Files.readString(path, StandardCharsets.UTF_8))
        .mkString("\n")
      text must include("module HjxlPreparedDctAxiLiteStreamCore")
      for (
        port <- Seq(
          "io_control_aw_ready",
          "io_control_aw_valid",
          "io_control_w_ready",
          "io_control_w_valid",
          "io_control_b_valid",
          "io_control_ar_ready",
          "io_control_r_valid",
          "io_input_ready",
          "io_input_valid",
          "io_input_bits_data",
          "io_input_bits_last",
          "io_trace_ready",
          "io_trace_valid",
          "io_trace_bits_data",
          "io_trace_bits_last",
          "io_busy",
          "io_overflow",
          "io_protocolError"
        )
      ) {
        withClue(s"missing generated controlled prepared stream port $port") {
          text must include(port)
        }
      }
    } finally {
      stream.close()
    }
  }
}
