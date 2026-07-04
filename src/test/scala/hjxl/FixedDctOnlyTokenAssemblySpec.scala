// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.sys.process.Process
import scala.sys.process.ProcessLogger

class FixedDctOnlyTokenAssemblySpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 8)
  private val width = 9
  private val height = 1
  private val libjxlTinyRoot =
    Path.of(sys.env.getOrElse("LIBJXL_TINY", "/Users/yunhocho/GitHub/libjxl-tiny"))

  private case class PreparedDc(current: Int, west: Int, north: Int, northwest: Int)

  private def requireReferenceTools(): Unit = {
    assume(
      Files.isDirectory(libjxlTinyRoot.resolve("python")),
      s"libjxl-tiny Python checkout not found at $libjxlTinyRoot"
    )
    assume(
      Process(Seq("python3", "-c", "import numpy")).! == 0,
      "python3 with numpy is required for token-input bitstream assembly"
    )
  }

  private def pokeConfig(dut: FrameDctOnlyAcMetadataTokenTraceStage): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(QuantizeDct8x8Block.DefaultScaleQ16.U)
    dut.io.config.fixedInvQacQ16.poke(QuantizeDct8x8Block.DefaultInvQacQ16.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.enableXyb.poke(false.B)
    dut.io.config.enableDct.poke(false.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(true.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.AcMetadata.U)
  }

  private def pokeConfig(dut: FrameDctOnlyAcTokenTraceStage): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(QuantizeDct8x8Block.DefaultScaleQ16.U)
    dut.io.config.fixedInvQacQ16.poke(QuantizeDct8x8Block.DefaultInvQacQ16.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.enableXyb.poke(true.B)
    dut.io.config.enableDct.poke(true.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(true.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.AcTokens.U)
  }

  private def driveConstantPixel(dut: FrameDctOnlyAcMetadataTokenTraceStage, x: Int): Unit = {
    dut.io.input.valid.poke(true.B)
    dut.io.input.bits.x.poke(x.U)
    dut.io.input.bits.y.poke(0.U)
    dut.io.input.bits.r.poke(64.S)
    dut.io.input.bits.g.poke(128.S)
    dut.io.input.bits.b.poke(192.S)
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
  }

  private def driveConstantPixel(dut: FrameDctOnlyAcTokenTraceStage, x: Int): Unit = {
    dut.io.input.valid.poke(true.B)
    dut.io.input.bits.x.poke(x.U)
    dut.io.input.bits.y.poke(0.U)
    dut.io.input.bits.r.poke(64.S)
    dut.io.input.bits.g.poke(128.S)
    dut.io.input.bits.b.poke(192.S)
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
  }

  private def collectPreparedDcTokens(): Seq[(Int, Int)] = {
    val yDc = 352
    val xDc = -23
    val bDc = 19
    val prepared = Seq(yDc, yDc, xDc, xDc, bDc, bDc).grouped(2).flatMap {
      case Seq(first, second) =>
        Seq(
          PreparedDc(current = first, west = 0, north = 0, northwest = 0),
          PreparedDc(current = second, west = first, north = first, northwest = first)
        )
    }.toSeq

    val tokens = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
    simulate(new DcTokenTraceStage(config)) { dut =>
      dut.io.trace.ready.poke(true.B)
      for ((sample, ordinal) <- prepared.zipWithIndex) {
        dut.io.input.valid.poke(true.B)
        dut.io.input.bits.group.poke(ordinal.U)
        dut.io.input.bits.current.poke(sample.current.S)
        dut.io.input.bits.west.poke(sample.west.S)
        dut.io.input.bits.north.poke(sample.north.S)
        dut.io.input.bits.northwest.poke(sample.northwest.S)
        dut.io.input.ready.expect(true.B)
        dut.io.trace.valid.expect(true.B)
        dut.io.trace.bits.stage.expect(TraceStage.DcTokens.U)
        dut.io.trace.bits.group.expect(ordinal.U)
        tokens += (
          dut.io.trace.bits.index.peekValue().asBigInt.toInt ->
            dut.io.trace.bits.value.peekValue().asBigInt.toInt
        )
        dut.clock.step()
      }
      dut.io.input.valid.poke(false.B)
      dut.io.trace.valid.expect(false.B)
    }
    tokens.toSeq
  }

  private def collectAcMetadataTokens(): Seq[(Int, Int)] = {
    val tokens = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
    simulate(new FrameDctOnlyAcMetadataTokenTraceStage(config)) { dut =>
      pokeConfig(dut)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for (x <- 0 until width) {
        driveConstantPixel(dut, x)
      }
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)

      for (ordinal <- 0 until 8) {
        dut.io.trace.valid.expect(true.B)
        dut.io.trace.bits.stage.expect(TraceStage.AcMetadataTokens.U)
        dut.io.trace.bits.group.expect(ordinal.U)
        tokens += (
          dut.io.trace.bits.index.peekValue().asBigInt.toInt ->
            dut.io.trace.bits.value.peekValue().asBigInt.toInt
        )
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
    tokens.toSeq
  }

  private def collectAcTokens(): Seq[(Int, Int)] = {
    val tokens = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
    simulate(new FrameDctOnlyAcTokenTraceStage(config)) { dut =>
      pokeConfig(dut)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for (x <- 0 until width) {
        driveConstantPixel(dut, x)
      }
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)

      for (ordinal <- 0 until 6) {
        var cycles = 0
        while (dut.io.trace.valid.peekValue().asBigInt == 0 && cycles < 16) {
          dut.clock.step()
          cycles += 1
        }
        cycles must be < 16
        dut.io.trace.bits.stage.expect(TraceStage.AcTokens.U)
        dut.io.trace.bits.group.expect(ordinal.U)
        tokens += (
          dut.io.trace.bits.index.peekValue().asBigInt.toInt ->
            dut.io.trace.bits.value.peekValue().asBigInt.toInt
        )
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
    tokens.toSeq
  }

  private def npyHeader(descr: String, shape: String): Array[Byte] = {
    val magic = Array(0x93.toByte, 'N'.toByte, 'U'.toByte, 'M'.toByte, 'P'.toByte, 'Y'.toByte)
    val prefixLength = magic.length + 2 + 2
    val rawHeader = s"{'descr': '$descr', 'fortran_order': False, 'shape': $shape, }"
    val padding = (16 - ((prefixLength + rawHeader.length + 1) % 16)) % 16
    val header = (rawHeader + (" " * padding) + "\n").getBytes(StandardCharsets.US_ASCII)
    val output = ByteBuffer.allocate(prefixLength + header.length).order(ByteOrder.LITTLE_ENDIAN)
    output.put(magic)
    output.put(1.toByte)
    output.put(0.toByte)
    output.putShort(header.length.toShort)
    output.put(header)
    output.array()
  }

  private def writeNpyU32Pairs(path: Path, tokens: Seq[(Int, Int)]): Unit = {
    val body = ByteBuffer.allocate(tokens.length * 2 * java.lang.Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN)
    for ((context, value) <- tokens) {
      body.putInt(context)
      body.putInt(value)
    }
    Files.write(path, npyHeader("<u4", s"(${tokens.length}, 2)") ++ body.array())
  }

  private def writeNpyU8(path: Path, values: Seq[Seq[Int]]): Unit = {
    val rows = values.length
    val cols = values.headOption.map(_.length).getOrElse(0)
    val body = ByteBuffer.allocate(rows * cols)
    values.flatten.foreach(value => body.put(value.toByte))
    Files.write(path, npyHeader("|u1", s"($rows, $cols)") ++ body.array())
  }

  private def runReferenceHelper(args: Seq[String]): Unit = {
    val output = scala.collection.mutable.ArrayBuffer.empty[String]
    val logger = ProcessLogger(line => output += line, line => output += line)
    val exitCode = Process(
      Seq("python3", "tools/hjxl_reference.py") ++ args,
      Path.of(".").toFile,
      "LIBJXL_TINY" -> libjxlTinyRoot.toString
    ).!(logger)
    withClue(output.mkString("\n")) {
      exitCode mustBe 0
    }
  }

  "RTL logical tokens can be consumed by the host bitstream assembler" in {
    requireReferenceTools()

    val temp = Files.createTempDirectory("hjxl-token-assembly-")
    val dcTokens = temp.resolve("rtl-dc.npy")
    val acMetadataTokens = temp.resolve("rtl-acmeta.npy")
    val acTokens = temp.resolve("rtl-ac.npy")
    val acStrategy = temp.resolve("rtl-ac-strategy.npy")
    val tokenFrame = temp.resolve("token-frame.bin")
    val tokenCodestream = temp.resolve("token.jxl")
    val directFrame = temp.resolve("direct-frame.bin")
    val directCodestream = temp.resolve("direct.jxl")

    writeNpyU32Pairs(dcTokens, collectPreparedDcTokens())
    writeNpyU32Pairs(acMetadataTokens, collectAcMetadataTokens())
    writeNpyU32Pairs(acTokens, collectAcTokens())
    writeNpyU8(acStrategy, Seq(Seq(1, 1)))

    runReferenceHelper(
      Seq(
        "--width",
        width.toString,
        "--height",
        height.toString,
        "--pattern",
        "constant",
        "--fixed-dct-only-frame-bin",
        directFrame.toString,
        "--fixed-dct-only-codestream-bin",
        directCodestream.toString,
        "--token-input-dc-tokens-npy",
        dcTokens.toString,
        "--token-input-ac-metadata-tokens-npy",
        acMetadataTokens.toString,
        "--token-input-ac-tokens-npy",
        acTokens.toString,
        "--token-input-ac-strategy-npy",
        acStrategy.toString,
        "--token-input-frame-bin",
        tokenFrame.toString,
        "--token-input-codestream-bin",
        tokenCodestream.toString
      )
    )

    Files.readAllBytes(tokenFrame).toSeq mustBe Files.readAllBytes(directFrame).toSeq
    Files.readAllBytes(tokenCodestream).toSeq mustBe Files.readAllBytes(directCodestream).toSeq
  }
}
