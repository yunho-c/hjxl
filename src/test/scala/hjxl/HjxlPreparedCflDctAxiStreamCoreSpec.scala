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

class HjxlPreparedCflDctAxiStreamCoreSpec extends AnyFreeSpec with Matchers with ChiselSim {
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
  private case class ExpectedTrace(stage: Int, group: Int, index: Int, value: Int)

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

  private def pokeConfig(dut: FramePreparedCflDctOnlyQuantizeTokenTraceStage): Unit = {
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

  private def pokeBlock(input: DctOnlyQuantizeBlockInput, block: PreparedBlock): Unit = {
    input.quant.poke(block.quant.U)
    input.scaleQ16.poke(block.scaleQ16.U)
    input.invQacQ16.poke(block.invQacQ16.U)
    input.xQmMultiplierQ16.poke(block.xQmMultiplierQ16.U)
    input.ytox.poke(block.ytox.S)
    input.ytob.poke(block.ytob.S)
    for (channel <- 0 until 3) {
      input.invDcFactorQ16(channel).poke(block.invDcFactorQ16(channel).U)
      for (coefficient <- 0 until blockSize) {
        input.coefficients(channel)(coefficient).poke(block.coefficients(channel)(coefficient).S)
      }
    }
  }

  private def collectDirectTraces(blocks: Seq[PreparedBlock]): Seq[ExpectedTrace] = {
    val traces = scala.collection.mutable.ArrayBuffer.empty[ExpectedTrace]
    simulate(new FramePreparedCflDctOnlyQuantizeTokenTraceStage(config)) { dut =>
      pokeConfig(dut)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for (block <- blocks) {
        dut.io.input.valid.poke(true.B)
        pokeBlock(dut.io.input.bits, block)
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)

      collectUntilLast(
        valid = dut.io.trace.valid,
        last = dut.io.traceLast,
        clock = dut.clock,
        peek = () =>
          ExpectedTrace(
            dut.io.trace.bits.stage.peekValue().asBigInt.toInt,
            dut.io.trace.bits.group.peekValue().asBigInt.toInt,
            dut.io.trace.bits.index.peekValue().asBigInt.toInt,
            dut.io.trace.bits.value.peekValue().asBigInt.toInt
          ),
        traces = traces
      )
      dut.io.overflow.expect(false.B)
    }
    traces.toSeq
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

  private def unpackTraceData(data: BigInt): ExpectedTrace = {
    val stage = (data & 0xff).toInt
    val group = ((data >> 8) & 0xffff).toInt
    val index = ((data >> 24) & 0xffffffffL).toInt
    val rawValue = (data >> 56) & 0xffffffffL
    val value = if ((rawValue & 0x80000000L) != 0) (rawValue - (1L << 32)).toInt else rawValue.toInt
    ExpectedTrace(stage, group, index, value)
  }

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

  private def collectUntilLast(
      valid: Bool,
      last: Bool,
      clock: Clock,
      peek: () => ExpectedTrace,
      traces: scala.collection.mutable.ArrayBuffer[ExpectedTrace]
  ): Unit = {
    var done = false
    var records = 0
    var idleCycles = 0
    while (!done && records < 10000 && idleCycles < 8192) {
      if (valid.peekValue().asBigInt != 0) {
        traces += peek()
        done = last.peekValue().asBigInt != 0
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

  "HjxlPreparedCflDctAxiStreamCore unpacks prepared blocks and packs estimated-CFL token traces" in {
    val xBlocks = (width + HjxlConstants.BlockDim - 1) / HjxlConstants.BlockDim
    val yBlocks = (height + HjxlConstants.BlockDim - 1) / HjxlConstants.BlockDim
    val blocks = Seq.tabulate(xBlocks * yBlocks)(preparedBlock)
    val expected = collectDirectTraces(blocks)
    val observed = scala.collection.mutable.ArrayBuffer.empty[ExpectedTrace]

    simulate(new HjxlPreparedCflDctAxiStreamCore(config)) { dut =>
      pokeConfig(dut)
      dut.io.input.valid.poke(false.B)
      dut.io.input.bits.data.poke(0.U)
      dut.io.input.bits.last.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      val words = blocks.flatMap(streamWords)
      words.length mustBe blocks.length * PreparedDctStreamLayout.WordsPerBlock
      for ((word, ordinal) <- words.zipWithIndex) {
        driveStreamWord(dut, word, last = ordinal == words.length - 1, s"prepared CFL stream input word $ordinal")
      }
      dut.io.input.valid.poke(false.B)
      dut.io.protocolError.expect(false.B)

      dut.io.trace.ready.poke(true.B)
      collectUntilLast(
        valid = dut.io.trace.valid,
        last = dut.io.trace.bits.last,
        clock = dut.clock,
        peek = () => unpackTraceData(dut.io.trace.bits.data.peekValue().asBigInt),
        traces = observed
      )
      dut.io.trace.valid.expect(false.B)
      dut.io.overflow.expect(false.B)
    }

    observed.toSeq mustBe expected
  }

  "HjxlPreparedCflDctAxiStreamCore emits the estimated-CFL prepared stream shell" in {
    val targetDir = Files.createTempDirectory("hjxl-prepared-cfl-dct-stream-elaborate-")
    ChiselStage.emitSystemVerilogFile(
      new HjxlPreparedCflDctAxiStreamCore(config),
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
      text must include("module HjxlPreparedCflDctAxiStreamCore")
      text must include("FramePreparedCflDctOnlyQuantizeTokenTraceStage")
      for (
        port <- Seq(
          "io_config_xsize",
          "io_clearProtocolError",
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
        withClue(s"missing generated prepared CFL stream port $port") {
          text must include(port)
        }
      }
    } finally {
      stream.close()
    }
  }
}
