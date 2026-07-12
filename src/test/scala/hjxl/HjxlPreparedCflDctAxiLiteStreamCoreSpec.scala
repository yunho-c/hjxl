// See README.md for license details.

package hjxl

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.jdk.CollectionConverters._

class HjxlPreparedCflDctAxiLiteStreamCoreSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val width = 72
  private val height = 8
  private val config = HjxlConfig(maxFrameWidth = width, maxFrameHeight = height)
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim
  private val q16 = BigInt(1) << 16

  private case class PreparedBlock(
      quant: Int,
      scaleQ16: Int,
      invQacQ16: Int,
      invDcFactorQ16: Vector[Int],
      xQmMultiplierQ16: Int,
      ytox: Int,
      ytob: Int,
      coefficients: Vector[Vector[BigInt]]
  )
  private case class StreamWord(data: BigInt, last: Boolean)

  private def preparedBlock(blockOrdinal: Int): PreparedBlock = {
    val channels = Vector.tabulate(3) { channel =>
      Vector.tabulate(blockSize) { coefficient =>
        if (coefficient == 0) {
          BigInt(18 + blockOrdinal * 3 + channel) * q16
        } else {
          val sign = if ((coefficient + blockOrdinal) % 2 == 0) 1 else -1
          val y = BigInt(sign * (84 + blockOrdinal + coefficient % 5)) * q16
          channel match {
            case 0 => y - BigInt(2 + blockOrdinal % 5) * q16
            case 1 => y
            case _ => y - BigInt(5 + coefficient % 7) * q16
          }
        }
      }
    }
    val scale = 256 + blockOrdinal * 3
    val quant = 4 + blockOrdinal % 5
    PreparedBlock(
      quant = quant,
      scaleQ16 = scale,
      invQacQ16 = QuantizeDct8x8Block.invQacQ16For(scale, quant),
      invDcFactorQ16 = QuantizeDct8x8Block.DefaultInvDcFactorQ16.toVector,
      xQmMultiplierQ16 = QuantizeDct8x8Block.DefaultQmMultiplierQ16,
      ytox = 99,
      ytob = -99,
      coefficients = channels
    )
  }

  private def init(dut: HjxlPreparedCflDctAxiLiteStreamCore): Unit = {
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
    dut.io.inputKeep.poke("hf".U)
    dut.io.input.bits.data.poke(0.U)
    dut.io.input.bits.last.poke(false.B)
    dut.io.trace.ready.poke(false.B)
    dut.clock.step()
  }

  private def pokeConfig(dut: HjxlPreparedCflDctAxiStreamCore): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(0.U)
    dut.io.config.fixedInvQacQ16.poke(0.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.fixedYtox.poke(0.S)
    dut.io.config.fixedYtob.poke(0.S)
    dut.io.config.enableXyb.poke(true.B)
    dut.io.config.enableDct.poke(true.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(true.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.AcTokens.U)
    dut.io.clearProtocolError.poke(false.B)
  }

  private def axiWrite(
      dut: HjxlPreparedCflDctAxiLiteStreamCore,
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

  private def axiRead(dut: HjxlPreparedCflDctAxiLiteStreamCore, address: Int): (BigInt, Int) = {
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

  private def packSignedWord(value: BigInt): BigInt =
    value & ((BigInt(1) << 32) - 1)

  private def streamWords(block: PreparedBlock): Seq[BigInt] =
    Seq(
      BigInt(block.quant),
      BigInt(block.scaleQ16),
      BigInt(block.invQacQ16),
      BigInt(block.invDcFactorQ16(0)),
      BigInt(block.invDcFactorQ16(1)),
      BigInt(block.invDcFactorQ16(2)),
      BigInt(block.xQmMultiplierQ16),
      packSignedWord(BigInt(block.ytox)),
      packSignedWord(BigInt(block.ytob))
    ) ++ block.coefficients.flatten.map(packSignedWord)

  private def driveStreamWord(
      dut: HjxlPreparedCflDctAxiStreamCore,
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

  private def driveStreamWord(
      dut: HjxlPreparedCflDctAxiLiteStreamCore,
      data: BigInt,
      last: Boolean,
      clue: String,
      keep: Int = 0xf
  ): Unit = {
    dut.io.input.valid.poke(true.B)
    dut.io.inputKeep.poke(keep.U)
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

  private def collectDirectStream(words: Seq[BigInt]): Seq[StreamWord] = {
    val rows = scala.collection.mutable.ArrayBuffer.empty[StreamWord]
    simulate(new HjxlPreparedCflDctAxiStreamCore(config)) { dut =>
      pokeConfig(dut)
      dut.io.input.valid.poke(false.B)
      dut.io.input.bits.data.poke(0.U)
      dut.io.input.bits.last.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for ((word, ordinal) <- words.zipWithIndex) {
        driveStreamWord(dut, word, last = ordinal == words.length - 1, s"direct estimated-CFL word $ordinal")
      }
      dut.io.input.valid.poke(false.B)
      dut.io.protocolError.expect(false.B)
      dut.io.trace.ready.poke(true.B)
      collectUntilLast(dut.io.trace.valid, dut.io.trace.bits.last, dut.clock, () => {
        StreamWord(dut.io.trace.bits.data.peekValue().asBigInt, dut.io.trace.bits.last.peekValue().asBigInt != 0)
      }, rows)
      dut.io.trace.valid.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
    rows.toSeq
  }

  private def collectUntilLast(
      valid: Bool,
      last: Bool,
      clock: Clock,
      peek: () => StreamWord,
      rows: scala.collection.mutable.ArrayBuffer[StreamWord]
  ): Unit = {
    var done = false
    var records = 0
    var idleCycles = 0
    while (!done && records < 10000 && idleCycles < 8192) {
      if (valid.peekValue().asBigInt != 0) {
        val row = peek()
        rows += row
        done = row.last || last.peekValue().asBigInt != 0
        records += 1
        idleCycles = 0
      } else {
        idleCycles += 1
      }
      clock.step()
    }
    records must be < 10000
    idleCycles must be < 8192
    done mustBe true
  }

  "HjxlPreparedCflDctAxiLiteStreamCore exposes the common configuration register map" in {
    simulate(new HjxlPreparedCflDctAxiLiteStreamCore(config)) { dut =>
      init(dut)

      axiWrite(dut, HjxlAxiLiteRegister.Xsize, width) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.Ysize, height) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.DistanceQ8, 512) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.FixedYtox, 0xf8) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.FixedYtob, 9) must be(AxiLiteResponse.Okay)

      axiRead(dut, HjxlAxiLiteRegister.Xsize) must be(BigInt(width) -> AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.Ysize) must be(BigInt(height) -> AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.DistanceQ8) must be(BigInt(512) -> AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.FixedYtox) must be(BigInt(0xf8) -> AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.FixedYtob) must be(BigInt(9) -> AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.Identity) must be(
        BigInt(HjxlAbiGenerated.Discovery.Identity) -> AxiLiteResponse.Okay
      )
      axiRead(dut, HjxlAxiLiteRegister.Capabilities) must be(
        HjxlDiscovery.PreparedEstimatedCflCapabilities -> AxiLiteResponse.Okay
      )
      axiRead(dut, HjxlAxiLiteRegister.MaxFrameGeometry) must be(
        BigInt(0x00080048) -> AxiLiteResponse.Okay
      )
      axiRead(dut, HjxlAxiLiteRegister.ActiveRoute) must be(
        BigInt(HjxlAbiGenerated.Discovery.Route.PreparedEstimatedCfl) -> AxiLiteResponse.Okay
      )
      axiRead(dut, HjxlAxiLiteRegister.BuildId) must be(
        BigInt(HjxlAbiGenerated.Discovery.BuildId) -> AxiLiteResponse.Okay
      )
      axiWrite(dut, HjxlAxiLiteRegister.ActiveRoute, 0) must be(AxiLiteResponse.Decerr)
      axiWrite(dut, 0x40, 0) must be(AxiLiteResponse.Decerr)
      axiRead(dut, 0x40) must be(BigInt(0) -> AxiLiteResponse.Decerr)
    }
  }

  "HjxlPreparedCflDctAxiLiteStreamCore matches the direct estimated-CFL stream shell" in {
    val xBlocks = (width + HjxlConstants.BlockDim - 1) / HjxlConstants.BlockDim
    val yBlocks = (height + HjxlConstants.BlockDim - 1) / HjxlConstants.BlockDim
    val words = Seq.tabulate(xBlocks * yBlocks)(preparedBlock).flatMap(streamWords)
    val expected = collectDirectStream(words)
    val observed = scala.collection.mutable.ArrayBuffer.empty[StreamWord]

    simulate(new HjxlPreparedCflDctAxiLiteStreamCore(config)) { dut =>
      init(dut)
      axiWrite(dut, HjxlAxiLiteRegister.Xsize, width) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.Ysize, height) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.Flags, 0x20f) must be(AxiLiteResponse.Okay)

      for ((word, ordinal) <- words.zipWithIndex) {
        driveStreamWord(dut, word, last = ordinal == words.length - 1, s"controlled estimated-CFL word $ordinal")
      }
      dut.io.input.valid.poke(false.B)
      dut.io.protocolError.expect(false.B)

      dut.io.trace.ready.poke(true.B)
      collectUntilLast(dut.io.trace.valid, dut.io.trace.bits.last, dut.clock, () => {
        StreamWord(dut.io.trace.bits.data.peekValue().asBigInt, dut.io.trace.bits.last.peekValue().asBigInt != 0)
      }, observed)
      dut.io.trace.valid.expect(false.B)
      dut.io.overflow.expect(false.B)
    }

    observed.toSeq mustBe expected
  }

  "HjxlPreparedCflDctAxiLiteStreamCore reports and clears invalid input keep masks" in {
    simulate(new HjxlPreparedCflDctAxiLiteStreamCore(config)) { dut =>
      init(dut)
      axiWrite(dut, HjxlAxiLiteRegister.Xsize, 8) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.Ysize, 8) must be(AxiLiteResponse.Okay)

      driveStreamWord(dut, data = 1, last = false, clue = "partial estimated-CFL keep", keep = 0x7)
      dut.io.input.valid.poke(false.B)
      dut.io.protocolError.expect(true.B)
      val (status, statusResp) = axiRead(dut, HjxlAxiLiteRegister.StatusControl)
      statusResp must be(AxiLiteResponse.Okay)
      (status & 1) must be(BigInt(1))
      dut.io.busy.expect(false.B)

      axiWrite(dut, HjxlAxiLiteRegister.StatusControl, 1) must be(AxiLiteResponse.Okay)
      dut.io.protocolError.expect(false.B)
      axiRead(dut, HjxlAxiLiteRegister.StatusControl) must be(BigInt(0) -> AxiLiteResponse.Okay)
    }
  }

  "HjxlPreparedCflDctAxiLiteStreamCore emits the controlled estimated-CFL prepared stream shell" in {
    val targetDir = Files.createTempDirectory("hjxl-prepared-cfl-dct-axi-lite-stream-elaborate-")
    ChiselStage.emitSystemVerilogFile(
      new HjxlPreparedCflDctAxiLiteStreamCore(config),
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
      text must include("module HjxlPreparedCflDctAxiLiteStreamCore")
      text must include("module HjxlPreparedCflDctAxiStreamCore")
      for (
        port <- Seq(
          "io_control_aw_ready",
          "io_control_aw_valid",
          "io_control_w_ready",
          "io_control_w_valid",
          "io_control_b_valid",
          "io_control_ar_ready",
          "io_control_r_valid",
          "io_inputKeep",
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
          "io_protocolError",
          "io_unsupportedDistance"
        )
      ) {
        withClue(s"missing generated controlled estimated-CFL port $port") {
          text must include(port)
        }
      }
    } finally {
      stream.close()
    }
  }
}
