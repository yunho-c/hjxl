// See README.md for license details.

package hjxl

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.sys.process.Process
import scala.sys.process.ProcessLogger

class StreamTraceToolSpec extends AnyFreeSpec with Matchers {
  private case class CommandResult(exitCode: Int, output: String)

  private def requireNumpy(): Unit =
    assume(
      Process(Seq("python3", "-c", "import numpy")).! == 0,
      "python3 with numpy is required for stream-trace integration tests"
    )

  private def runCommand(command: Seq[String]): CommandResult = {
    val output = scala.collection.mutable.ArrayBuffer.empty[String]
    val logger = ProcessLogger(line => output += line, line => output += line)
    val exitCode = Process(command, TestPaths.repoRoot.toFile, "PYTHONDONTWRITEBYTECODE" -> "1").!(logger)
    CommandResult(exitCode, output.mkString("\n"))
  }

  private def pack(stage: Int, group: Int, index: Long, value: Int): BigInt = {
    val rawValue = BigInt(value & 0xffffffffL)
    BigInt(stage & 0xff) |
      (BigInt(group & 0xffff) << 8) |
      (BigInt(index & 0xffffffffL) << 24) |
      (rawValue << 56)
  }

  private def expectSuccess(command: Seq[String]): String = {
    val result = runCommand(command)
    withClue(result.output) {
      result.exitCode mustBe 0
    }
    result.output
  }

  private def readNpyAsJson(path: Path): String =
    expectSuccess(
      Seq(
        "python3",
        "-c",
        "import json, sys, numpy as np\n" +
          "print(json.dumps(np.load(sys.argv[1]).tolist(), separators=(',', ':')))\n",
        path.toString
      )
    ).trim

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

  private def preparedBlockFixtureJson: String = {
    val coefficientsX = (0 until 64).mkString("[", ",", "]")
    val coefficientsY = (100 until 164).mkString("[", ",", "]")
    val coefficientsB = (-64 until 0).mkString("[", ",", "]")
    val zeroCoefficients = Seq.fill(64)(0).mkString("[", ",", "]")
    s"""{
       |  "format": "hjxl.dct_only_prepared_blocks.v1",
       |  "coefficient_fraction_bits": 16,
       |  "image": {"x_blocks": 1, "y_blocks": 1},
       |  "blocks": [
       |    {
       |      "block_index": 0,
       |      "inputs": {
       |        "coefficient_fraction_bits": 16,
       |        "quant": 5,
       |        "scale_q16": 1234,
       |        "inv_qac_q16": 5678,
       |        "inv_dc_factor_q16": [11, 22, 33],
       |        "x_qm_multiplier_q16": 44,
       |        "ytox": -2,
       |        "ytob": 3,
       |        "coefficients_q": [$coefficientsX, $coefficientsY, $coefficientsB]
       |      },
       |      "expected": {
       |        "quantized_ac": [$zeroCoefficients, $zeroCoefficients, $zeroCoefficients],
       |        "quantized_dc": [0, 0, 0],
       |        "num_nonzeros": [0, 0, 0]
       |      }
       |    }
       |  ]
       |}""".stripMargin
  }

  "hjxl_stream_trace.py decodes packed stream words into StageTrace CSV" in {
    val temp = Files.createTempDirectory("hjxl-stream-trace-")
    val streamCsv = temp.resolve("stream.csv")
    val traceCsv = temp.resolve("trace.csv")
    Files.writeString(
      streamCsv,
      "tdata,tlast\n" +
        s"0x${pack(TraceStage.AcTokens, 3, 4, -7).toString(16)},0\n" +
        s"0x${pack(TraceStage.AcTokens, 4, 5, 11).toString(16)},1\n"
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

    result.exitCode mustBe 0
    result.output must include("decoded 2 stream trace rows")
    Files.readString(traceCsv).replace("\r\n", "\n") mustBe
      "stage,group,index,value\n12,3,4,-7\n12,4,5,11\n"
  }

  "hjxl_prepared_blocks.py emits packed prepared-DCT stream words" in {
    val temp = Files.createTempDirectory("hjxl-prepared-block-stream-")
    val fixture = temp.resolve("prepared.json")
    val streamCsv = temp.resolve("prepared-stream.csv")
    Files.writeString(fixture, preparedBlockFixtureJson)

    val output = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--prepared-json",
        fixture.toString,
        "--input-stream-csv",
        streamCsv.toString
      )
    )
    output mustBe ""

    val rows = Files.readString(streamCsv).replace("\r\n", "\n").trim.split("\n").toVector
    rows.head mustBe "data,last"
    rows.length mustBe 1 + 201
    rows(1) mustBe "5,0"
    rows(2) mustBe "1234,0"
    rows(3) mustBe "5678,0"
    rows(4) mustBe "11,0"
    rows(5) mustBe "22,0"
    rows(6) mustBe "33,0"
    rows(7) mustBe "44,0"
    rows(8) mustBe "4294967294,0"
    rows(9) mustBe "3,0"
    rows(10) mustBe "0,0"
    rows(73) mustBe "63,0"
    rows(74) mustBe "100,0"
    rows(137) mustBe "163,0"
    rows(138) mustBe "4294967232,0"
    rows.last mustBe "4294967295,1"
    rows.drop(1).dropRight(1).count(_.endsWith(",1")) mustBe 0
  }

  "hjxl_rgb_stream.py emits packed raster RGB stream words from PFM" in {
    val temp = Files.createTempDirectory("hjxl-rgb-stream-")
    val pfm = temp.resolve("input.pfm")
    val streamCsv = temp.resolve("rgb-stream.csv")
    val controlCsv = temp.resolve("rgb-control.csv")
    val manifestJson = temp.resolve("rgb-manifest.json")
    writeRgbPfm(
      pfm,
      width = 2,
      height = 2,
      topDownPixels = Seq(
        (0.0f, 0.5f, 1.0f),
        (0.25f, 0.75f, -0.25f),
        (1.25f, 0.0f, 0.125f),
        (0.1f, 0.2f, 0.3f)
      )
    )

    val output = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_rgb_stream.py",
        "--pfm",
        pfm.toString,
        "--stream-csv",
        streamCsv.toString,
        "--axi-lite-csv",
        controlCsv.toString,
        "--manifest-json",
        manifestJson.toString,
        "--enable-xyb",
        "--enable-dct",
        "--enable-quant",
        "--token-select",
        "ac-tokens",
        "--distance-q8",
        "512",
        "--fixed-point-scale",
        "123",
        "--fixed-inv-qac-q16",
        "456",
        "--fixed-raw-quant",
        "7"
      )
    )
    output must include("wrote 4 RGB stream words for 2x2")
    output must include("wrote AXI-Lite config writes")
    output must include("wrote manifest")

    def pack(r: Int, g: Int, b: Int): BigInt =
      BigInt(r & 0xffff) | (BigInt(g & 0xffff) << 16) | (BigInt(b & 0xffff) << 32)

    val rows = Files.readString(streamCsv).replace("\r\n", "\n").trim.split("\n").toVector
    rows.head mustBe "data,last"
    rows.tail mustBe Seq(
      s"${pack(0, 128, 256)},0",
      s"${pack(64, 192, -64)},0",
      s"${pack(320, 0, 32)},0",
      s"${pack(26, 51, 77)},1"
    )

    val controlRows = Files.readString(controlCsv).replace("\r\n", "\n").trim.split("\n").toVector
    controlRows mustBe Seq(
      "address,data,strb",
      "4,2,15",
      "8,2,15",
      "12,512,15",
      "16,123,15",
      "20,456,15",
      "24,7,15",
      "28,519,15"
    )

    val manifest = Files.readString(manifestJson).replace("\r\n", "\n")
    manifest must include("\"format\": \"hjxl.rgb_stream_manifest.v1\"")
    manifest must include("\"width\": 2")
    manifest must include("\"height\": 2")
    manifest must include("\"word_count\": 4")
    manifest must include("\"pixel_bits\": 16")
    manifest must include("\"fraction_bits\": 8")
    manifest must include("\"xsize\": 4")
    manifest must include("\"ysize\": 8")
    manifest must include("\"status_control\": 0")
    manifest must include("\"clear_protocol_error_write_bit\": 0")
    manifest must include("\"flags\": 519")
    manifest must include("\"token_select\": \"ac-tokens\"")

    val validateOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_rgb_stream.py",
        "--validate-manifest",
        manifestJson.toString
      )
    )
    validateOutput must include(s"validated $manifestJson")

    val corruptedRows = Files.readString(streamCsv).replace("\r\n", "\n").trim.split("\n").toVector
    Files.writeString(streamCsv, (corruptedRows.dropRight(1) :+ corruptedRows.last.replace(",1", ",0")).mkString("\n") + "\n")
    val invalid = runCommand(
      Seq(
        "python3",
        "tools/hjxl_rgb_stream.py",
        "--validate-manifest",
        manifestJson.toString
      )
    )
    invalid.exitCode mustBe 1
    invalid.output must include("final stream row does not assert last")
  }

  "hjxl_stream_trace.py rejects early TLAST in single-frame mode" in {
    val temp = Files.createTempDirectory("hjxl-stream-trace-early-last-")
    val streamCsv = temp.resolve("stream.csv")
    Files.writeString(
      streamCsv,
      "data,last\n" +
        s"${pack(TraceStage.InputPadded, 0, 0, 10)},1\n" +
        s"${pack(TraceStage.InputPadded, 0, 1, 20)},0\n"
    )

    val result = runCommand(
      Seq(
        "python3",
        "tools/hjxl_stream_trace.py",
        "--stream-csv",
        streamCsv.toString,
        "--require-final-last"
      )
    )

    result.exitCode mustBe 1
    result.output must include("TLAST asserted before final row")
  }

  "hjxl_stream_trace.py rejects missing final TLAST in single-frame mode" in {
    val temp = Files.createTempDirectory("hjxl-stream-trace-missing-last-")
    val streamCsv = temp.resolve("stream.csv")
    Files.writeString(
      streamCsv,
      "data,last\n" +
        s"${pack(TraceStage.InputPadded, 0, 0, 10)},0\n" +
        s"${pack(TraceStage.InputPadded, 0, 1, 20)},0\n"
    )

    val result = runCommand(
      Seq(
        "python3",
        "tools/hjxl_stream_trace.py",
        "--stream-csv",
        streamCsv.toString,
        "--require-final-last"
      )
    )

    result.exitCode mustBe 1
    result.output must include("final stream row does not assert TLAST")
  }

  "decoded stream traces feed hjxl_trace_tokens.py" in {
    requireNumpy()

    val temp = Files.createTempDirectory("hjxl-stream-trace-to-tokens-")
    val streamCsv = temp.resolve("stream.csv")
    val traceCsv = temp.resolve("trace.csv")
    val dcTokens = temp.resolve("dc.npy")
    val strategy = temp.resolve("strategy.npy")
    val directDcTokens = temp.resolve("direct-dc.npy")
    val directStrategy = temp.resolve("direct-strategy.npy")
    Files.writeString(
      streamCsv,
      "data,last\n" +
        s"${pack(TraceStage.DcTokens, 0, 5, 7)},0\n" +
        s"${pack(TraceStage.DcTokens, 1, 6, 8)},0\n" +
        s"${pack(TraceStage.AcStrategy, 0, 0, AcStrategyCode.encoded(AcStrategyCode.Dct, isFirstBlock = true))},1\n"
    )

    expectSuccess(
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
    expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_trace_tokens.py",
        "--trace-csv",
        traceCsv.toString,
        "--dc-tokens-npy",
        dcTokens.toString,
        "--ac-strategy-npy",
        strategy.toString,
        "--width",
        "8",
        "--height",
        "8"
      )
    )

    readNpyAsJson(dcTokens) mustBe "[[5,7],[6,8]]"
    readNpyAsJson(strategy) mustBe "[[1]]"

    expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_trace_tokens.py",
        "--stream-csv",
        streamCsv.toString,
        "--dc-tokens-npy",
        directDcTokens.toString,
        "--ac-strategy-npy",
        directStrategy.toString,
        "--width",
        "8",
        "--height",
        "8",
        "--require-stream-final-last"
      )
    )
    readNpyAsJson(directDcTokens) mustBe "[[5,7],[6,8]]"
    readNpyAsJson(directStrategy) mustBe "[[1]]"
  }

  "hjxl_trace_to_codestream.py accepts packed stream trace inputs" in {
    requireNumpy()

    val temp = Files.createTempDirectory("hjxl-stream-trace-to-codestream-")
    val streamCsv = temp.resolve("stream.csv")
    val codestream = temp.resolve("out.jxl")
    Files.writeString(
      streamCsv,
      "data,last\n" +
        s"${pack(TraceStage.DcTokens, 0, 0, 0)},0\n" +
        s"${pack(TraceStage.AcMetadataTokens, 0, 0, 0)},0\n" +
        s"${pack(TraceStage.AcTokens, 0, 0, 0)},1\n"
    )

    val result = runCommand(
      Seq(
        "python3",
        "tools/hjxl_trace_to_codestream.py",
        "--stream-csv",
        streamCsv.toString,
        "--width",
        "8",
        "--height",
        "8",
        "--codestream-bin",
        codestream.toString,
        "--require-stream-final-last"
      )
    )

    result.exitCode mustBe 1
    result.output must include("missing AC strategy trace indices")
  }
}
