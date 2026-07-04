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

class HjxlAxiLiteStreamCoreSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private case class CommandResult(exitCode: Int, output: String)
  private case class StreamWord(data: BigInt, last: Boolean)
  private case class AxiWrite(address: Int, data: BigInt, strb: Int)

  private val config = HjxlConfig(maxFrameWidth = 8, maxFrameHeight = 8)

  private def runCommand(command: Seq[String]): CommandResult = {
    val output = scala.collection.mutable.ArrayBuffer.empty[String]
    val logger = ProcessLogger(line => output += line, line => output += line)
    val exitCode = Process(command, TestPaths.repoRoot.toFile, "PYTHONDONTWRITEBYTECODE" -> "1").!(logger)
    CommandResult(exitCode, output.mkString("\n"))
  }

  private def init(dut: HjxlAxiLiteStreamCore): Unit = {
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

  private def axiWrite(dut: HjxlAxiLiteStreamCore, address: Int, data: BigInt, strb: Int = 0xf): Int = {
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

  private def axiRead(dut: HjxlAxiLiteStreamCore, address: Int): (BigInt, Int) = {
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

  private def drivePixel(dut: HjxlAxiLiteStreamCore, data: BigInt, last: Boolean): Unit = {
    dut.io.input.valid.poke(true.B)
    dut.io.input.bits.data.poke(data.U)
    dut.io.input.bits.last.poke(last.B)
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
    dut.io.input.valid.poke(false.B)
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

  private def readAxiLiteCsv(path: Path): Seq[AxiWrite] =
    Files.readString(path).replace("\r\n", "\n").trim.split("\n").toVector.drop(1).map { line =>
      val columns = line.split(",", -1)
      AxiWrite(Integer.decode(columns(0)), BigInt(columns(1)), Integer.decode(columns(2)))
    }

  "HjxlAxiLiteStreamCore exposes writable configuration registers" in {
    simulate(new HjxlAxiLiteStreamCore(config, traceRoute = TraceStage.InputPadded)) { dut =>
      init(dut)

      axiWrite(dut, HjxlAxiLiteRegister.Xsize, 0x1234) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.Ysize, 0x5678) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.DistanceQ8, 0x3456) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.FixedPointScale, 0x789a) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.FixedInvQacQ16, 0x89abcdefL) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.FixedRawQuant, 0xee) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.Flags, 0x20d) must be(AxiLiteResponse.Okay)

      axiRead(dut, HjxlAxiLiteRegister.Xsize) must be(BigInt(0x1234) -> AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.Ysize) must be(BigInt(0x5678) -> AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.DistanceQ8) must be(BigInt(0x3456) -> AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.FixedPointScale) must be(BigInt(0x789a) -> AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.FixedInvQacQ16) must be(BigInt(0x89abcdefL) -> AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.FixedRawQuant) must be(BigInt(0xee) -> AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.Flags) must be(BigInt(0x20d) -> AxiLiteResponse.Okay)
    }
  }

  "HjxlAxiLiteStreamCore honors byte strobes" in {
    simulate(new HjxlAxiLiteStreamCore(config, traceRoute = TraceStage.InputPadded)) { dut =>
      init(dut)

      axiWrite(dut, HjxlAxiLiteRegister.Xsize, 0xabcd0000L, strb = 0xc) must be(AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.Xsize) must be(BigInt(1) -> AxiLiteResponse.Okay)

      axiWrite(dut, HjxlAxiLiteRegister.Xsize, 0x00001234, strb = 0x3) must be(AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.Xsize) must be(BigInt(0x1234) -> AxiLiteResponse.Okay)
    }
  }

  "HjxlAxiLiteStreamCore reports decode errors for unmapped registers" in {
    simulate(new HjxlAxiLiteStreamCore(config, traceRoute = TraceStage.InputPadded)) { dut =>
      init(dut)

      axiWrite(dut, 0x40, 0x1234) must be(AxiLiteResponse.Decerr)
      axiRead(dut, 0x40) must be(BigInt(0) -> AxiLiteResponse.Decerr)
    }
  }

  "HjxlAxiLiteStreamCore exposes busy and overflow status bits" in {
    simulate(new HjxlAxiLiteStreamCore(config, traceRoute = TraceStage.InputPadded)) { dut =>
      init(dut)

      axiWrite(dut, HjxlAxiLiteRegister.Xsize, 2) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.Ysize, 1) must be(AxiLiteResponse.Okay)
      drivePixel(dut, rgb(10, 20, 30), last = false)
      dut.io.busy.expect(true.B)
      axiRead(dut, HjxlAxiLiteRegister.StatusControl) must be(BigInt(2) -> AxiLiteResponse.Okay)
    }

    simulate(new HjxlAxiLiteStreamCore(config, traceRoute = TraceStage.InputPadded)) { dut =>
      init(dut)

      axiWrite(dut, HjxlAxiLiteRegister.Xsize, 9) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.Ysize, 1) must be(AxiLiteResponse.Okay)
      dut.io.overflow.expect(true.B)
      axiRead(dut, HjxlAxiLiteRegister.StatusControl) must be(BigInt(4) -> AxiLiteResponse.Okay)
    }
  }

  "HjxlAxiLiteStreamCore consumes PFM-derived RGB streams after AXI-Lite configuration" in {
    val temp = Files.createTempDirectory("hjxl-axi-lite-rgb-stream-input-")
    val pfm = temp.resolve("input.pfm")
    val inputStreamCsv = temp.resolve("rgb-input-stream.csv")
    val controlCsv = temp.resolve("rgb-control.csv")
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
        inputStreamCsv.toString,
        "--axi-lite-csv",
        controlCsv.toString
      )
    )
    withClue(convertResult.output) {
      convertResult.exitCode mustBe 0
    }
    val inputRows = readStreamCsv(inputStreamCsv)
    inputRows mustBe Seq(StreamWord(rgb(0, 128, 256), last = false), StreamWord(rgb(64, 192, -64), last = true))

    val captured = scala.collection.mutable.ArrayBuffer.empty[StreamWord]
    simulate(new HjxlAxiLiteStreamCore(config, traceRoute = TraceStage.InputPadded)) { dut =>
      init(dut)
      for (write <- readAxiLiteCsv(controlCsv)) {
        axiWrite(dut, write.address, write.data, write.strb) must be(AxiLiteResponse.Okay)
      }
      axiRead(dut, HjxlAxiLiteRegister.Xsize) must be(BigInt(2) -> AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.Ysize) must be(BigInt(1) -> AxiLiteResponse.Okay)

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

  "HjxlAxiLiteStreamCore clears stream protocol errors through status/control" in {
    simulate(new HjxlAxiLiteStreamCore(config, traceRoute = TraceStage.InputPadded)) { dut =>
      init(dut)

      axiWrite(dut, HjxlAxiLiteRegister.Xsize, 2) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.Ysize, 1) must be(AxiLiteResponse.Okay)

      drivePixel(dut, rgb(10, 20, 30), last = true)
      dut.io.protocolError.expect(true.B)
      val (status, statusResp) = axiRead(dut, HjxlAxiLiteRegister.StatusControl)
      statusResp must be(AxiLiteResponse.Okay)
      (status & 1) must be(BigInt(1))

      axiWrite(dut, HjxlAxiLiteRegister.StatusControl, 1) must be(AxiLiteResponse.Okay)
      dut.io.protocolError.expect(false.B)
      val (clearedStatus, clearedStatusResp) = axiRead(dut, HjxlAxiLiteRegister.StatusControl)
      clearedStatusResp must be(AxiLiteResponse.Okay)
      (clearedStatus & 1) must be(BigInt(0))
    }
  }
}
