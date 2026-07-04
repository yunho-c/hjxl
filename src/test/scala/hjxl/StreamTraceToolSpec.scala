// See README.md for license details.

package hjxl

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.sys.process.Process
import scala.sys.process.ProcessLogger

class StreamTraceToolSpec extends AnyFreeSpec with Matchers {
  private val libjxlTinyRoot =
    Path.of(sys.env.getOrElse("LIBJXL_TINY", "/Users/yunhocho/GitHub/libjxl-tiny"))

  private case class CommandResult(exitCode: Int, output: String)

  private def requireNumpy(): Unit =
    assume(
      Process(Seq("python3", "-c", "import numpy")).! == 0,
      "python3 with numpy is required for stream-trace integration tests"
    )

  private def requireReferenceTools(): Unit = {
    assume(
      Files.isDirectory(libjxlTinyRoot.resolve("python")),
      s"libjxl-tiny Python checkout not found at $libjxlTinyRoot"
    )
    requireNumpy()
  }

  private def runCommand(command: Seq[String]): CommandResult = {
    val output = scala.collection.mutable.ArrayBuffer.empty[String]
    val logger = ProcessLogger(line => output += line, line => output += line)
    val exitCode = Process(
      command,
      TestPaths.repoRoot.toFile,
      "LIBJXL_TINY" -> libjxlTinyRoot.toString,
      "PYTHONDONTWRITEBYTECODE" -> "1"
    ).!(logger)
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

  private def commandAvailable(command: String): Boolean =
    Process(Seq("/bin/sh", "-c", s"command -v $command >/dev/null 2>&1")).! == 0

  private def cIncludePath(path: Path): String =
    path.toRealPath().toString.replace("\\", "\\\\").replace("\"", "\\\"")

  private def compileGeneratedHeaderSmoke(header: Path, symbolPrefix: String): Unit = {
    val sourceStem = symbolPrefix.toLowerCase.replaceAll("[^a-z0-9_]+", "_")
    val cSource = header.getParent.resolve(s"$sourceStem-header-smoke.c")
    val cxxSource = header.getParent.resolve(s"$sourceStem-header-smoke.cc")
    val sourceText =
      s"""#include "${cIncludePath(header)}"
         |
         |int main(void) {
         |  return (int)${symbolPrefix}_AXI_LITE_WRITES[0].strb != 0x0f;
         |}
         |""".stripMargin
    Files.writeString(cSource, sourceText)
    Files.writeString(cxxSource, sourceText)

    if (commandAvailable("cc")) {
      expectSuccess(Seq("cc", "-std=c11", "-fsyntax-only", cSource.toString))
    }
    if (commandAvailable("c++")) {
      expectSuccess(Seq("c++", "-std=c++11", "-fsyntax-only", cxxSource.toString))
    }
  }

  private def copyDirectory(source: Path, target: Path): Unit = {
    val paths = Files.walk(source)
    try {
      paths.forEach { path =>
        val destination = target.resolve(source.relativize(path))
        if (Files.isDirectory(path)) {
          Files.createDirectories(destination)
        } else {
          Files.createDirectories(destination.getParent)
          Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
        }
      }
    } finally {
      paths.close()
    }
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

  private def writePackedTokenCapture(
      dcTokens: Path,
      acMetadataTokens: Path,
      acTokens: Path,
      acStrategy: Path,
      streamBin: Path,
      lastBin: Path
  ): Unit = {
    val script =
      """import sys, numpy as np
        |dc = np.load(sys.argv[1])
        |acmeta = np.load(sys.argv[2])
        |ac = np.load(sys.argv[3])
        |strategy = np.load(sys.argv[4]).reshape(-1)
        |stream_path = sys.argv[5]
        |last_path = sys.argv[6]
        |
        |def pack(stage, group, index, value):
        |    return (
        |        (int(stage) & 0xff)
        |        | ((int(group) & 0xffff) << 8)
        |        | ((int(index) & 0xffffffff) << 24)
        |        | ((int(value) & 0xffffffff) << 56)
        |    )
        |
        |words = []
        |for group, (context, value) in enumerate(dc):
        |    words.append(pack(10, group, int(context), int(value)))
        |for index, value in enumerate(strategy):
        |    words.append(pack(6, 0, index, int(value)))
        |for group, (context, value) in enumerate(acmeta):
        |    words.append(pack(11, group, int(context), int(value)))
        |for group, (context, value) in enumerate(ac):
        |    words.append(pack(12, group, int(context), int(value)))
        |
        |with open(stream_path, "wb") as handle:
        |    for word in words:
        |        handle.write(int(word).to_bytes(11, byteorder="little", signed=False))
        |        handle.write(bytes(5))
        |with open(last_path, "wb") as handle:
        |    handle.write(bytes([0] * (len(words) - 1) + [1]))
        |""".stripMargin
    expectSuccess(
      Seq(
        "python3",
        "-c",
        script,
        dcTokens.toString,
        acMetadataTokens.toString,
        acTokens.toString,
        acStrategy.toString,
        streamBin.toString,
        lastBin.toString
      )
    )
  }

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

  "hjxl_stream_trace.py decodes little-endian binary trace captures" in {
    val temp = Files.createTempDirectory("hjxl-stream-trace-bin-")
    val streamBin = temp.resolve("stream.bin")
    val paddedStreamBin = temp.resolve("stream-padded.bin")
    val lastBin = temp.resolve("last.bin")
    val paddedTraceCsv = temp.resolve("trace-padded.csv")
    val traceCsv = temp.resolve("trace.csv")
    val words = Seq(
      pack(TraceStage.DcTokens, 1, 2, -3),
      pack(TraceStage.AcStrategy, 4, 5, 6)
    )
    val data = ByteBuffer.allocate(words.length * 11).order(ByteOrder.LITTLE_ENDIAN)
    for (word <- words) {
      var shifted = word
      for (_ <- 0 until 11) {
        data.put((shifted & 0xff).toByte)
        shifted = shifted >> 8
      }
    }
    Files.write(streamBin, data.array())
    Files.write(lastBin, Array[Byte](0, 1))
    val paddedData = ByteBuffer.allocate(words.length * 16).order(ByteOrder.LITTLE_ENDIAN)
    for (word <- words) {
      var shifted = word
      for (_ <- 0 until 11) {
        paddedData.put((shifted & 0xff).toByte)
        shifted = shifted >> 8
      }
      for (_ <- 0 until 5) {
        paddedData.put(0.toByte)
      }
    }
    Files.write(paddedStreamBin, paddedData.array())

    val result = runCommand(
      Seq(
        "python3",
        "tools/hjxl_stream_trace.py",
        "--stream-bin",
        streamBin.toString,
        "--last-bin",
        lastBin.toString,
        "--trace-csv",
        traceCsv.toString,
        "--require-final-last"
      )
    )

    result.exitCode mustBe 0
    result.output must include("decoded 2 stream trace rows")
    Files.readString(traceCsv).replace("\r\n", "\n") mustBe
      "stage,group,index,value\n10,1,2,-3\n6,4,5,6\n"

    expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_stream_trace.py",
        "--stream-bin",
        paddedStreamBin.toString,
        "--last-bin",
        lastBin.toString,
        "--stream-word-bytes",
        "16",
        "--trace-csv",
        paddedTraceCsv.toString,
        "--require-final-last"
      )
    )
    Files.readString(paddedTraceCsv).replace("\r\n", "\n") mustBe
      "stage,group,index,value\n10,1,2,-3\n6,4,5,6\n"
  }

  "hjxl_prepared_blocks.py emits packed prepared-DCT stream words" in {
    val temp = Files.createTempDirectory("hjxl-prepared-block-stream-")
    val fixture = temp.resolve("prepared.json")
    val streamCsv = temp.resolve("prepared-stream.csv")
    val controlCsv = temp.resolve("prepared-control.csv")
    val manifestJson = temp.resolve("prepared-manifest.json")
    val header = temp.resolve("prepared-bundle.h")
    val streamBin = temp.resolve("prepared-stream.bin")
    val lastBin = temp.resolve("prepared-last.bin")
    val hostBundleDir = temp.resolve("prepared-host-bundle")
    Files.writeString(fixture, preparedBlockFixtureJson)

    val output = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--prepared-json",
        fixture.toString,
        "--input-stream-csv",
        streamCsv.toString,
        "--axi-lite-csv",
        controlCsv.toString,
        "--manifest-json",
        manifestJson.toString
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

    val controlRows = Files.readString(controlCsv).replace("\r\n", "\n").trim.split("\n").toVector
    controlRows mustBe Seq(
      "address,data,strb",
      "4,8,15",
      "8,8,15",
      "12,256,15",
      "16,0,15",
      "20,0,15",
      "24,0,15",
      "28,526,15"
    )

    val manifest = Files.readString(manifestJson).replace("\r\n", "\n")
    manifest must include("\"format\": \"hjxl.prepared_dct_stream_manifest.v1\"")
    manifest must include("\"xsize\": 8")
    manifest must include("\"ysize\": 8")
    manifest must include("\"x_blocks\": 1")
    manifest must include("\"y_blocks\": 1")
    manifest must include("\"word_count\": 201")
    manifest must include("\"block_count\": 1")
    manifest must include("\"words_per_block\": 201")
    manifest must include("\"status_control\": 0")
    manifest must include("\"unsupported_distance\": 3")
    manifest must include("\"clear_protocol_error_write_bit\": 0")
    manifest must include("\"flags\": 526")
    manifest must include("\"token_select\": \"ac-tokens\"")

    val validateOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--validate-manifest",
        manifestJson.toString
      )
    )
    validateOutput must include(s"validated $manifestJson")

    val headerOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_manifest_header.py",
        "--manifest-json",
        manifestJson.toString,
        "--header",
        header.toString,
        "--symbol-prefix",
        "HJXL_PREPARED"
      )
    )
    headerOutput must include(s"wrote $header")
    val headerText = Files.readString(header).replace("\r\n", "\n")
    headerText must include("#define HJXL_PREPARED_MANIFEST_FORMAT \"hjxl.prepared_dct_stream_manifest.v1\"")
    headerText must include("#define HJXL_PREPARED_TARGET_INTERFACE \"prepared_dct_axi_stream\"")
    headerText must include("#define HJXL_PREPARED_TARGET_STREAM_SHELL \"HjxlPreparedDctAxiStreamCore\"")
    headerText must include("#define HJXL_PREPARED_TARGET_CONTROLLED_SHELL \"HjxlPreparedDctAxiLiteStreamCore\"")
    headerText must include("#define HJXL_PREPARED_TARGET_KV260_TOP \"HjxlKv260PreparedDctTop\"")
    headerText must include("#define HJXL_PREPARED_STREAM_WORD_COUNT 201u")
    headerText must include("#define HJXL_PREPARED_INPUT_DATA_BITS 32u")
    headerText must include("#define HJXL_PREPARED_STREAM_WORD_BYTES 4u")
    headerText must include("#define HJXL_PREPARED_STREAM_BYTE_COUNT 804u")
    headerText must include("#define HJXL_PREPARED_TRACE_PACKED_BITS 88u")
    headerText must include("#define HJXL_PREPARED_TRACE_PACKED_BYTES 11u")
    headerText must include("#define HJXL_PREPARED_KV260_TRACE_CAPTURE_WORD_BYTES 16u")
    headerText must include("#define HJXL_PREPARED_DISTANCE_FALLBACK_Q8 256u")
    headerText must include("#define HJXL_PREPARED_SUPPORTED_DISTANCE_Q8_COUNT 6u")
    headerText must include("static const uint32_t HJXL_PREPARED_SUPPORTED_DISTANCE_Q8[] = {")
    headerText must include("64u, 128u, 256u, 512u, 1024u, 2048u")
    headerText must include("#define HJXL_PREPARED_REG_XSIZE 0x00000004u")
    headerText must include("#define HJXL_PREPARED_STATUS_BUSY_BIT 1u")
    headerText must include("#define HJXL_PREPARED_STATUS_UNSUPPORTED_DISTANCE_BIT 3u")
    headerText must include("{ 0x0000001cu, 0x0000020eu, 0x0000000fu }, /* flags */")
    headerText must include(
      "HJXL_PREPARED_STREAM_BYTE_COUNT == HJXL_PREPARED_STREAM_WORD_COUNT * HJXL_PREPARED_STREAM_WORD_BYTES"
    )
    headerText must include(
      "sizeof(HJXL_PREPARED_AXI_LITE_WRITES) / sizeof(HJXL_PREPARED_AXI_LITE_WRITES[0]) == " +
        "HJXL_PREPARED_AXI_LITE_WRITE_COUNT"
    )
    headerText must include(
      "HJXL_PREPARED_TRACE_PACKED_BYTES == (HJXL_PREPARED_TRACE_PACKED_BITS + 7u) / 8u"
    )
    headerText must include(
      "sizeof(HJXL_PREPARED_SUPPORTED_DISTANCE_Q8) / sizeof(HJXL_PREPARED_SUPPORTED_DISTANCE_Q8[0]) == " +
        "HJXL_PREPARED_SUPPORTED_DISTANCE_Q8_COUNT"
    )
    compileGeneratedHeaderSmoke(header, "HJXL_PREPARED")

    val bufferOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_stream_buffer.py",
        "--manifest-json",
        manifestJson.toString,
        "--stream-bin",
        streamBin.toString,
        "--last-bin",
        lastBin.toString
      )
    )
    bufferOutput must include(s"wrote 201 stream words (4 bytes/word) to $streamBin")
    Files.readAllBytes(streamBin).map(_ & 0xff).take(12).toSeq mustBe Seq(
      5, 0, 0, 0,
      210, 4, 0, 0,
      46, 22, 0, 0
    )
    Files.readAllBytes(streamBin).map(_ & 0xff).slice(28, 32).toSeq mustBe Seq(254, 255, 255, 255)
    val preparedLastBytes = Files.readAllBytes(lastBin).map(_ & 0xff).toSeq
    preparedLastBytes.length mustBe 201
    preparedLastBytes.dropRight(1).sum mustBe 0
    preparedLastBytes.last mustBe 1

    val preparedGeneratedReplayPlanJson = temp.resolve("prepared-generated-replay-plan.json")
    val hostBundleOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--manifest-json",
        manifestJson.toString,
        "--output-dir",
        hostBundleDir.toString,
        "--name",
        "prepared",
        "--symbol-prefix",
        "HJXL_PREPARED_BUNDLE",
        "--replay-plan-json",
        preparedGeneratedReplayPlanJson.toString
      )
    )
    hostBundleOutput must include(s"wrote host bundle prepared with 201 stream words to $hostBundleDir")
    hostBundleOutput must include(s"wrote host replay plan to $preparedGeneratedReplayPlanJson")
    Files.readString(preparedGeneratedReplayPlanJson).replace("\r\n", "\n") must include(
      "\"format\": \"hjxl.host_replay_plan.v1\""
    )
    Files.readString(preparedGeneratedReplayPlanJson).replace("\r\n", "\n") must include("\"byte_count\": 804")
    Files.exists(hostBundleDir.resolve("prepared.h")) mustBe true
    Files.exists(hostBundleDir.resolve("prepared-stream.bin")) mustBe true
    Files.exists(hostBundleDir.resolve("prepared-last.bin")) mustBe true
    Files.exists(hostBundleDir.resolve("prepared-manifest.json")) mustBe true
    Files.exists(hostBundleDir.resolve("prepared-stream.csv")) mustBe true
    Files.exists(hostBundleDir.resolve("prepared-control.csv")) mustBe true
    compileGeneratedHeaderSmoke(hostBundleDir.resolve("prepared.h"), "HJXL_PREPARED_BUNDLE")
    Files.readString(hostBundleDir.resolve("prepared.h")).replace("\r\n", "\n") must include(
      "#define HJXL_PREPARED_BUNDLE_TARGET_INTERFACE \"prepared_dct_axi_stream\""
    )
    val preparedIndex = Files.readString(hostBundleDir.resolve("prepared-bundle.json")).replace("\r\n", "\n")
    preparedIndex must include("\"format\": \"hjxl.host_bundle.v1\"")
    preparedIndex must include("\"checksums\"")
    preparedIndex must include("\"source_manifest\": \"prepared-manifest.json\"")
    preparedIndex must include("\"header\": \"prepared.h\"")
    preparedIndex must include("\"stream_bin\": \"prepared-stream.bin\"")
    preparedIndex must include("\"manifest_format\": \"hjxl.prepared_dct_stream_manifest.v1\"")
    preparedIndex must include("\"target\"")
    preparedIndex must include("\"interface\": \"prepared_dct_axi_stream\"")
    preparedIndex must include("\"controlled_shell\": \"HjxlPreparedDctAxiLiteStreamCore\"")
    preparedIndex must include("\"kv260_top\": \"HjxlKv260PreparedDctTop\"")
    preparedIndex must include("\"distance\"")
    preparedIndex must include("\"fallback_q8\": 256")
    preparedIndex must include("\"supported_q8\"")
    preparedIndex must include("\"byte_count\": 804")
    preparedIndex must include("\"input_data_bytes\": 4")
    preparedIndex must include("\"word_count\": 201")
    preparedIndex must include("\"axi_lite_write_count\": 7")

    val preparedBundleValidateOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        hostBundleDir.resolve("prepared-bundle.json").toString
      )
    )
    preparedBundleValidateOutput must include("validated host bundle prepared with 201 stream words")
    val invalidValidateBundleReplayPlanOutput = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        hostBundleDir.resolve("prepared-bundle.json").toString,
        "--replay-plan-json",
        temp.resolve("invalid-validate-bundle-plan.json").toString
      )
    )
    invalidValidateBundleReplayPlanOutput.exitCode mustBe 1
    invalidValidateBundleReplayPlanOutput.output must include(
      "--replay-plan-json cannot be used with --validate-bundle"
    )

    val preparedReplayPlan = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--describe-bundle",
        hostBundleDir.resolve("prepared-bundle.json").toString
      )
    )
    preparedReplayPlan must include("\"format\": \"hjxl.host_replay_plan.v1\"")
    preparedReplayPlan must include("\"checksums\"")
    preparedReplayPlan must include("\"sha256\"")
    preparedReplayPlan must include(s""""bundle_dir": "${cIncludePath(hostBundleDir)}"""")
    preparedReplayPlan must include("\"header\": \"prepared.h\"")
    preparedReplayPlan must include(s""""header_resolved": "${cIncludePath(hostBundleDir.resolve("prepared.h"))}"""")
    preparedReplayPlan must include("\"stream_bin\": \"prepared-stream.bin\"")
    preparedReplayPlan must include(
      s""""stream_bin_resolved": "${cIncludePath(hostBundleDir.resolve("prepared-stream.bin"))}""""
    )
    preparedReplayPlan must include("\"source_manifest\": \"prepared-manifest.json\"")
    preparedReplayPlan must include(
      s""""source_manifest_resolved": "${cIncludePath(hostBundleDir.resolve("prepared-manifest.json"))}""""
    )
    preparedReplayPlan must include("\"stream_csv\": \"prepared-stream.csv\"")
    preparedReplayPlan must include(
      s""""stream_csv_resolved": "${cIncludePath(hostBundleDir.resolve("prepared-stream.csv"))}""""
    )
    preparedReplayPlan must include("\"axi_lite_csv\": \"prepared-control.csv\"")
    preparedReplayPlan must include(
      s""""axi_lite_csv_resolved": "${cIncludePath(hostBundleDir.resolve("prepared-control.csv"))}""""
    )
    preparedReplayPlan must include("\"byte_count\": 804")
    preparedReplayPlan must include("\"target\"")
    preparedReplayPlan must include("\"interface\": \"prepared_dct_axi_stream\"")
    preparedReplayPlan must include("\"stream_shell\": \"HjxlPreparedDctAxiStreamCore\"")
    preparedReplayPlan must include("\"controlled_shell\": \"HjxlPreparedDctAxiLiteStreamCore\"")
    preparedReplayPlan must include("\"kv260_top\": \"HjxlKv260PreparedDctTop\"")
    preparedReplayPlan must include("\"trace\"")
    preparedReplayPlan must include("\"packed_bits\": 88")
    preparedReplayPlan must include("\"packed_bytes\": 11")
    preparedReplayPlan must include("\"default_capture_word_bytes\": 16")
    preparedReplayPlan must include("\"distance\"")
    preparedReplayPlan must include("\"fallback_q8\": 256")
    preparedReplayPlan must include("\"supported_q8\"")
    preparedReplayPlan must include("64")
    preparedReplayPlan must include("2048")
    preparedReplayPlan must include("\"write_count\": 7")
    preparedReplayPlan must include("\"register\": \"flags\"")
    preparedReplayPlan must include("\"data\": 526")
    val preparedReplayPlanJson = temp.resolve("prepared-replay-plan.json")
    val preparedReplayPlanFileOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--describe-bundle",
        hostBundleDir.resolve("prepared-bundle.json").toString,
        "--replay-plan-json",
        preparedReplayPlanJson.toString
      )
    )
    preparedReplayPlanFileOutput must include(s"wrote host replay plan to $preparedReplayPlanJson")
    Files.readString(preparedReplayPlanJson).replace("\r\n", "\n") must include(
      "\"format\": \"hjxl.host_replay_plan.v1\""
    )
    Files.readString(preparedReplayPlanJson).replace("\r\n", "\n") must include("\"byte_count\": 804")
    Files.readString(preparedReplayPlanJson).replace("\r\n", "\n") must include(
      "\"interface\": \"prepared_dct_axi_stream\""
    )
    val preparedReplayPlanValidateOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-replay-plan",
        preparedReplayPlanJson.toString
      )
    )
    preparedReplayPlanValidateOutput must include("validated host replay plan prepared with 201 stream words")
    val invalidValidateReplayPlanOutput = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-replay-plan",
        preparedReplayPlanJson.toString,
        "--replay-plan-json",
        temp.resolve("invalid-validate-replay-plan.json").toString
      )
    )
    invalidValidateReplayPlanOutput.exitCode mustBe 1
    invalidValidateReplayPlanOutput.output must include(
      "--replay-plan-json cannot be used with --validate-replay-plan"
    )
    val originalPreparedReplayPlan = Files.readString(preparedReplayPlanJson)
    Files.writeString(preparedReplayPlanJson, originalPreparedReplayPlan.replace("\"byte_count\": 804", "\"byte_count\": 803"))
    val invalidPreparedReplayPlan = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-replay-plan",
        preparedReplayPlanJson.toString
      )
    )
    invalidPreparedReplayPlan.exitCode mustBe 1
    invalidPreparedReplayPlan.output must include("replay plan does not match described bundle")
    Files.writeString(preparedReplayPlanJson, originalPreparedReplayPlan)
    val relativePreparedBundleIndex = TestPaths.repoRoot.relativize(hostBundleDir.resolve("prepared-bundle.json"))
    val preparedReplayPlanFromRelativeIndex = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--describe-bundle",
        relativePreparedBundleIndex.toString
      )
    )
    preparedReplayPlanFromRelativeIndex must include(s""""bundle_dir": "${cIncludePath(hostBundleDir)}"""")
    preparedReplayPlanFromRelativeIndex must include(s""""bundle_index": "$relativePreparedBundleIndex"""")
    preparedReplayPlanFromRelativeIndex must include(
      s""""bundle_index_resolved": "${cIncludePath(hostBundleDir.resolve("prepared-bundle.json"))}""""
    )
    preparedReplayPlanFromRelativeIndex must include(
      s""""stream_bin_resolved": "${cIncludePath(hostBundleDir.resolve("prepared-stream.bin"))}""""
    )
    val movedReplayPlanJson = temp.resolve("moved-replay-plans").resolve("prepared-replay-plan.json")
    val movedReplayPlanOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--describe-bundle",
        relativePreparedBundleIndex.toString,
        "--replay-plan-json",
        movedReplayPlanJson.toString
      )
    )
    movedReplayPlanOutput must include(s"wrote host replay plan to $movedReplayPlanJson")
    Files.readString(movedReplayPlanJson).replace("\r\n", "\n") must include(
      s""""bundle_index": "$relativePreparedBundleIndex""""
    )
    val movedReplayPlanValidateOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-replay-plan",
        movedReplayPlanJson.toString
      )
    )
    movedReplayPlanValidateOutput must include("validated host replay plan prepared with 201 stream words")
    val legacyReplayPlanJson = hostBundleDir.resolve("prepared-legacy-replay-plan.json")
    val legacyReplayPlan = preparedReplayPlanFromRelativeIndex
      .replace(s""""bundle_index": "$relativePreparedBundleIndex"""", """"bundle_index": "prepared-bundle.json"""")
      .replaceFirst("""(?m)^  "bundle_index_resolved": "[^"]+",\n""", "")
      .replaceFirst(
        """(?s),\n  "target": \{\n    "controlled_shell": "[^"]+",\n    "input_stream": "[^"]+",\n    "interface": "[^"]+",\n    "kv260_top": (?:"[^"]+"|null),\n    "stream_shell": "[^"]+"\n  \},\n""",
        ",\n"
      )
      .replaceFirst(
        """(?s),\n  "trace": \{\n    "default_capture_word_bytes": 16,\n    "group_bits": 16,\n    "index_bits": 32,\n    "packed_bits": 88,\n    "packed_bytes": 11,\n    "stage_bits": 8,\n    "trace_value_bits": 32\n  \}\n""",
        "\n"
      )
      .replaceFirst(
        """(?sm)^  "distance": \{\n    "fallback_q8": 256,\n    "supported_q8": \[\n      64,\n      128,\n      256,\n      512,\n      1024,\n      2048\n    \]\n  \},\n""",
        ""
      )
    Files.writeString(legacyReplayPlanJson, legacyReplayPlan)
    val legacyReplayPlanValidateOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-replay-plan",
        legacyReplayPlanJson.toString
      )
    )
    legacyReplayPlanValidateOutput must include("validated host replay plan prepared with 201 stream words")

    val relocatedPreparedBundleDir = temp.resolve("relocated-prepared-host-bundle")
    copyDirectory(hostBundleDir, relocatedPreparedBundleDir)
    val relocatedPreparedBundleOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        relocatedPreparedBundleDir.resolve("prepared-bundle.json").toString
      )
    )
    relocatedPreparedBundleOutput must include("validated host bundle prepared with 201 stream words")

    val preparedBundleControl = hostBundleDir.resolve("prepared-control.csv")
    val originalPreparedBundleControl = Files.readString(preparedBundleControl)
    Files.writeString(preparedBundleControl, originalPreparedBundleControl.replace("28,526,15", "28,525,15"))
    val invalidPreparedBundleControl = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        hostBundleDir.resolve("prepared-bundle.json").toString
      )
    )
    invalidPreparedBundleControl.exitCode mustBe 1
    invalidPreparedBundleControl.output must include("AXI-Lite control rows do not match source manifest")
    Files.writeString(preparedBundleControl, originalPreparedBundleControl)

    val preparedBundleIndex = hostBundleDir.resolve("prepared-bundle.json")
    val originalPreparedIndex = Files.readString(preparedBundleIndex)
    Files.writeString(
      preparedBundleIndex,
      originalPreparedIndex.replaceFirst(
        """(?s),\n  "target": \{\n    "controlled_shell": "[^"]+",\n    "input_stream": "[^"]+",\n    "interface": "[^"]+",\n    "kv260_top": (?:"[^"]+"|null),\n    "stream_shell": "[^"]+"\n  \}\n""",
        "\n"
      )
    )
    val legacyPreparedBundleOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        preparedBundleIndex.toString
      )
    )
    legacyPreparedBundleOutput must include("validated host bundle prepared with 201 stream words")
    Files.writeString(preparedBundleIndex, originalPreparedIndex)

    Files.writeString(
      preparedBundleIndex,
      originalPreparedIndex.replaceFirst(
        """(?sm)^  "distance": \{\n    "fallback_q8": 256,\n    "supported_q8": \[\n      64,\n      128,\n      256,\n      512,\n      1024,\n      2048\n    \]\n  \},\n""",
        ""
      )
    )
    val legacyPreparedBundleDistanceOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        preparedBundleIndex.toString
      )
    )
    legacyPreparedBundleDistanceOutput must include("validated host bundle prepared with 201 stream words")
    Files.writeString(preparedBundleIndex, originalPreparedIndex)

    Files.writeString(
      preparedBundleIndex,
      originalPreparedIndex.replace("\"fallback_q8\": 256", "\"fallback_q8\": 512")
    )
    val invalidPreparedDistance = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        preparedBundleIndex.toString
      )
    )
    invalidPreparedDistance.exitCode mustBe 1
    invalidPreparedDistance.output must include("distance metadata does not match RTL lookup")
    Files.writeString(preparedBundleIndex, originalPreparedIndex)

    Files.writeString(
      preparedBundleIndex,
      originalPreparedIndex.replace("\"interface\": \"prepared_dct_axi_stream\"", "\"interface\": \"rgb_axi_stream\"")
    )
    val invalidPreparedTarget = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        preparedBundleIndex.toString
      )
    )
    invalidPreparedTarget.exitCode mustBe 1
    invalidPreparedTarget.output must include("target metadata does not match source manifest")
    Files.writeString(preparedBundleIndex, originalPreparedIndex)

    Files.writeString(preparedBundleIndex, originalPreparedIndex.replace("\"byte_count\": 804", "\"byte_count\": 803"))
    val invalidPreparedByteCount = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        preparedBundleIndex.toString
      )
    )
    invalidPreparedByteCount.exitCode mustBe 1
    invalidPreparedByteCount.output must include("stream.byte_count does not match source manifest")
    Files.writeString(preparedBundleIndex, originalPreparedIndex)

    Files.writeString(
      preparedBundleIndex,
      originalPreparedIndex.replaceFirst(
        "\"stream_bin\"\\s*:\\s*\"[0-9a-f]{64}\"",
        "\"stream_bin\": \"0000000000000000000000000000000000000000000000000000000000000000\""
      )
    )
    val invalidPreparedChecksum = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        preparedBundleIndex.toString
      )
    )
    invalidPreparedChecksum.exitCode mustBe 1
    invalidPreparedChecksum.output must include("checksum mismatch for stream_bin")
    Files.writeString(preparedBundleIndex, originalPreparedIndex)

    val corruptedRows = Files.readString(streamCsv).replace("\r\n", "\n").trim.split("\n").toVector
    Files.writeString(streamCsv, (corruptedRows.dropRight(1) :+ corruptedRows.last.replace(",1", ",0")).mkString("\n") + "\n")
    val bundleAfterSourceCorruption = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        hostBundleDir.resolve("prepared-bundle.json").toString
      )
    )
    bundleAfterSourceCorruption must include("validated host bundle prepared with 201 stream words")
    val invalid = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--validate-manifest",
        manifestJson.toString
      )
    )
    invalid.exitCode mustBe 1
    invalid.output must include("final stream row does not assert last")
  }

  "hjxl_rgb_stream.py emits packed raster RGB stream words from PFM" in {
    val temp = Files.createTempDirectory("hjxl-rgb-stream-")
    val pfm = temp.resolve("input.pfm")
    val streamCsv = temp.resolve("rgb-stream.csv")
    val controlCsv = temp.resolve("rgb-control.csv")
    val manifestJson = temp.resolve("rgb-manifest.json")
    val header = temp.resolve("rgb-bundle.h")
    val streamBin = temp.resolve("rgb-stream.bin")
    val lastBin = temp.resolve("rgb-last.bin")
    val hostBundleDir = temp.resolve("rgb-host-bundle")
    val hostBundleNoLastDir = temp.resolve("rgb-host-bundle-no-last")
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
    manifest must include("\"unsupported_distance\": 3")
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

    expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_manifest_header.py",
        "--manifest-json",
        manifestJson.toString,
        "--header",
        header.toString,
        "--symbol-prefix",
        "HJXL_RGB"
      )
    )
    val headerText = Files.readString(header).replace("\r\n", "\n")
    headerText must include("#define HJXL_RGB_MANIFEST_FORMAT \"hjxl.rgb_stream_manifest.v1\"")
    headerText must include("#define HJXL_RGB_TARGET_INTERFACE \"rgb_axi_stream\"")
    headerText must include("#define HJXL_RGB_TARGET_STREAM_SHELL \"HjxlAxiStreamCore\"")
    headerText must include("#define HJXL_RGB_TARGET_CONTROLLED_SHELL \"HjxlAxiLiteStreamCore\"")
    headerText must include("#define HJXL_RGB_TARGET_KV260_TOP \"\"")
    headerText must include("#define HJXL_RGB_STREAM_WORD_COUNT 4u")
    headerText must include("#define HJXL_RGB_INPUT_DATA_BITS 48u")
    headerText must include("#define HJXL_RGB_STREAM_WORD_BYTES 6u")
    headerText must include("#define HJXL_RGB_STREAM_BYTE_COUNT 24u")
    headerText must include("#define HJXL_RGB_TRACE_PACKED_BITS 88u")
    headerText must include("#define HJXL_RGB_TRACE_PACKED_BYTES 11u")
    headerText must include("#define HJXL_RGB_KV260_TRACE_CAPTURE_WORD_BYTES 16u")
    headerText must include("#define HJXL_RGB_DISTANCE_FALLBACK_Q8 256u")
    headerText must include("#define HJXL_RGB_SUPPORTED_DISTANCE_Q8_COUNT 6u")
    headerText must include("static const uint32_t HJXL_RGB_SUPPORTED_DISTANCE_Q8[] = {")
    headerText must include("64u, 128u, 256u, 512u, 1024u, 2048u")
    headerText must include("#define HJXL_RGB_REG_FLAGS 0x0000001cu")
    headerText must include("#define HJXL_RGB_STATUS_UNSUPPORTED_DISTANCE_BIT 3u")
    headerText must include("{ 0x00000004u, 0x00000002u, 0x0000000fu }, /* xsize */")
    headerText must include("{ 0x0000001cu, 0x00000207u, 0x0000000fu }, /* flags */")
    headerText must include(
      "HJXL_RGB_STREAM_BYTE_COUNT == HJXL_RGB_STREAM_WORD_COUNT * HJXL_RGB_STREAM_WORD_BYTES"
    )
    headerText must include(
      "sizeof(HJXL_RGB_AXI_LITE_WRITES) / sizeof(HJXL_RGB_AXI_LITE_WRITES[0]) == HJXL_RGB_AXI_LITE_WRITE_COUNT"
    )
    headerText must include(
      "HJXL_RGB_TRACE_PACKED_BYTES == (HJXL_RGB_TRACE_PACKED_BITS + 7u) / 8u"
    )
    headerText must include(
      "sizeof(HJXL_RGB_SUPPORTED_DISTANCE_Q8) / sizeof(HJXL_RGB_SUPPORTED_DISTANCE_Q8[0]) == " +
        "HJXL_RGB_SUPPORTED_DISTANCE_Q8_COUNT"
    )
    compileGeneratedHeaderSmoke(header, "HJXL_RGB")

    expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_stream_buffer.py",
        "--manifest-json",
        manifestJson.toString,
        "--stream-bin",
        streamBin.toString,
        "--last-bin",
        lastBin.toString
      )
    )
    Files.readAllBytes(streamBin).map(_ & 0xff).take(12).toSeq mustBe Seq(
      0, 0, 128, 0, 0, 1,
      64, 0, 192, 0, 192, 255
    )
    Files.readAllBytes(streamBin).length mustBe 4 * 6
    Files.readAllBytes(lastBin).map(_ & 0xff).toSeq mustBe Seq(0, 0, 0, 1)

    expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--manifest-json",
        manifestJson.toString,
        "--output-dir",
        hostBundleDir.toString,
        "--name",
        "rgb",
        "--symbol-prefix",
        "HJXL_RGB_BUNDLE"
      )
    )
    Files.readString(hostBundleDir.resolve("rgb.h")).replace("\r\n", "\n") must include(
      "#define HJXL_RGB_BUNDLE_INPUT_DATA_BITS 48u"
    )
    Files.readString(hostBundleDir.resolve("rgb.h")).replace("\r\n", "\n") must include(
      "#define HJXL_RGB_BUNDLE_STREAM_BYTE_COUNT 24u"
    )
    Files.exists(hostBundleDir.resolve("rgb-manifest.json")) mustBe true
    Files.exists(hostBundleDir.resolve("rgb-stream.csv")) mustBe true
    Files.exists(hostBundleDir.resolve("rgb-control.csv")) mustBe true
    Files.readAllBytes(hostBundleDir.resolve("rgb-stream.bin")).length mustBe 24
    Files.readAllBytes(hostBundleDir.resolve("rgb-last.bin")).map(_ & 0xff).toSeq mustBe Seq(0, 0, 0, 1)
    compileGeneratedHeaderSmoke(hostBundleDir.resolve("rgb.h"), "HJXL_RGB_BUNDLE")
    Files.readString(hostBundleDir.resolve("rgb.h")).replace("\r\n", "\n") must include(
      "#define HJXL_RGB_BUNDLE_TARGET_INTERFACE \"rgb_axi_stream\""
    )
    Files.readString(hostBundleDir.resolve("rgb-bundle.json")).replace("\r\n", "\n") must include(
      "\"axi_lite_write_count\": 7"
    )
    Files.readString(hostBundleDir.resolve("rgb-bundle.json")).replace("\r\n", "\n") must include(
      "\"checksums\""
    )
    Files.readString(hostBundleDir.resolve("rgb-bundle.json")).replace("\r\n", "\n") must include(
      "\"byte_count\": 24"
    )
    Files.readString(hostBundleDir.resolve("rgb-bundle.json")).replace("\r\n", "\n") must include(
      "\"source_manifest\": \"rgb-manifest.json\""
    )
    Files.readString(hostBundleDir.resolve("rgb-bundle.json")).replace("\r\n", "\n") must include(
      "\"interface\": \"rgb_axi_stream\""
    )
    Files.readString(hostBundleDir.resolve("rgb-bundle.json")).replace("\r\n", "\n") must include(
      "\"controlled_shell\": \"HjxlAxiLiteStreamCore\""
    )
    Files.readString(hostBundleDir.resolve("rgb-bundle.json")).replace("\r\n", "\n") must include(
      "\"kv260_top\": null"
    )

    val rgbBundleValidateOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        hostBundleDir.resolve("rgb-bundle.json").toString
      )
    )
    rgbBundleValidateOutput must include("validated host bundle rgb with 4 stream words")

    val relocatedRgbBundleDir = temp.resolve("relocated-rgb-host-bundle")
    copyDirectory(hostBundleDir, relocatedRgbBundleDir)
    val relocatedRgbBundleOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        relocatedRgbBundleDir.resolve("rgb-bundle.json").toString
      )
    )
    relocatedRgbBundleOutput must include("validated host bundle rgb with 4 stream words")

    val rgbBundleControl = hostBundleDir.resolve("rgb-control.csv")
    val originalRgbBundleControl = Files.readString(rgbBundleControl)
    Files.writeString(rgbBundleControl, originalRgbBundleControl.replace("28,519,15", "28,518,15"))
    val invalidRgbBundleControl = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        hostBundleDir.resolve("rgb-bundle.json").toString
      )
    )
    invalidRgbBundleControl.exitCode mustBe 1
    invalidRgbBundleControl.output must include("AXI-Lite control rows do not match source manifest")
    Files.writeString(rgbBundleControl, originalRgbBundleControl)

    Files.write(hostBundleDir.resolve("rgb-last.bin"), Array[Byte](0, 0, 1, 1))
    val invalidBundle = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        hostBundleDir.resolve("rgb-bundle.json").toString
      )
    )
    invalidBundle.exitCode mustBe 1
    invalidBundle.output must include("TLAST sidecar does not match source manifest")

    val noLastBundleOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--manifest-json",
        manifestJson.toString,
        "--output-dir",
        hostBundleNoLastDir.toString,
        "--name",
        "rgb-no-last",
        "--symbol-prefix",
        "HJXL_RGB_NO_LAST",
        "--no-last-bin"
      )
    )
    noLastBundleOutput must include(s"wrote host bundle rgb-no-last with 4 stream words to $hostBundleNoLastDir")
    Files.exists(hostBundleNoLastDir.resolve("rgb-no-last-last.bin")) mustBe false
    compileGeneratedHeaderSmoke(hostBundleNoLastDir.resolve("rgb-no-last.h"), "HJXL_RGB_NO_LAST")
    val noLastIndex = Files.readString(hostBundleNoLastDir.resolve("rgb-no-last-bundle.json")).replace("\r\n", "\n")
    noLastIndex must include("\"last_bin\": null")
    noLastIndex must not include "\"last_bin\": \""
    val noLastValidateOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        hostBundleNoLastDir.resolve("rgb-no-last-bundle.json").toString
      )
    )
    noLastValidateOutput must include("validated host bundle rgb-no-last with 4 stream words")
    val noLastReplayPlan = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--describe-bundle",
        hostBundleNoLastDir.resolve("rgb-no-last-bundle.json").toString
      )
    )
    noLastReplayPlan must include("\"stream_bin\": \"rgb-no-last-stream.bin\"")
    noLastReplayPlan must include("\"last_bin\": null")
    noLastReplayPlan must include("\"last_bin_resolved\": null")
    noLastReplayPlan must include("\"byte_count\": 24")
    noLastReplayPlan must include("\"interface\": \"rgb_axi_stream\"")
    noLastReplayPlan must include("\"stream_shell\": \"HjxlAxiStreamCore\"")
    noLastReplayPlan must include("\"controlled_shell\": \"HjxlAxiLiteStreamCore\"")
    noLastReplayPlan must include("\"default_capture_word_bytes\": 16")

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

  "hjxl_replay_capture.py validates a KV260-style captured token stream from a replay plan" in {
    requireReferenceTools()

    val temp = Files.createTempDirectory("hjxl-replay-capture-")
    val width = 16
    val height = 8
    val pfm = temp.resolve("input.pfm")
    val streamCsv = temp.resolve("rgb-stream.csv")
    val controlCsv = temp.resolve("rgb-control.csv")
    val manifestJson = temp.resolve("rgb-manifest.json")
    val bundleDir = temp.resolve("host-bundle")
    val replayPlan = temp.resolve("replay-plan.json")
    val unsupportedStreamCsv = temp.resolve("rgb-stream-unsupported-distance.csv")
    val unsupportedControlCsv = temp.resolve("rgb-control-unsupported-distance.csv")
    val unsupportedManifestJson = temp.resolve("rgb-manifest-unsupported-distance.json")
    val unsupportedBundleDir = temp.resolve("host-bundle-unsupported-distance")
    val unsupportedReplayPlan = temp.resolve("replay-plan-unsupported-distance.json")
    val dcTokens = temp.resolve("dc.npy")
    val acMetadataTokens = temp.resolve("acmeta.npy")
    val acTokens = temp.resolve("ac.npy")
    val acStrategy = temp.resolve("strategy.npy")
    val expectedCodestream = temp.resolve("expected.jxl")
    val captureBin = temp.resolve("capture.bin")
    val captureLast = temp.resolve("capture-last.bin")
    val rawCaptureBin = temp.resolve("capture-raw.bin")
    val rawCaptureLast = temp.resolve("capture-raw-last.bin")
    val assembledCodestream = temp.resolve("assembled.jxl")
    val bundleIndexCodestream = temp.resolve("bundle-index-assembled.jxl")
    val rawCodestream = temp.resolve("raw-assembled.jxl")
    val unsupportedDistanceCodestream = temp.resolve("unsupported-distance-assembled.jxl")
    val capturedDcTokens = temp.resolve("captured-dc.npy")
    val summaryJson = temp.resolve("summary.json")
    val rawSummaryJson = temp.resolve("raw-summary.json")
    val unsupportedDistanceSummaryJson = temp.resolve("unsupported-distance-summary.json")
    val expectedInputByteCount = width * height * 6

    val pixels = for {
      y <- 0 until height
      x <- 0 until width
    } yield {
      val base = (x + y).toFloat / (width + height).toFloat
      (base, x.toFloat / width.toFloat, y.toFloat / height.toFloat)
    }
    writeRgbPfm(pfm, width = width, height = height, topDownPixels = pixels)

    expectSuccess(
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
        "--enable-dct",
        "--enable-quant",
        "--enable-tokenize",
        "--token-select",
        "ac-tokens",
        "--distance-q8",
        "256"
      )
    )
    expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--manifest-json",
        manifestJson.toString,
        "--output-dir",
        bundleDir.toString,
        "--name",
        "capture",
        "--symbol-prefix",
        "HJXL_CAPTURE",
        "--replay-plan-json",
        replayPlan.toString
      )
    )
    Files.readString(replayPlan).replace("\r\n", "\n") must include("\"data\": 16")
    Files.readString(replayPlan).replace("\r\n", "\n") must include("\"data\": 8")

    expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_reference.py",
        "--width",
        width.toString,
        "--height",
        height.toString,
        "--pattern",
        "gradient",
        "--fixed-dct-only-dc-tokens-npy",
        dcTokens.toString,
        "--fixed-dct-only-ac-metadata-tokens-npy",
        acMetadataTokens.toString,
        "--fixed-dct-only-ac-tokens-npy",
        acTokens.toString,
        "--default-ac-strategy-npy",
        acStrategy.toString,
        "--fixed-dct-only-codestream-bin",
        expectedCodestream.toString
      )
    )
    writePackedTokenCapture(dcTokens, acMetadataTokens, acTokens, acStrategy, captureBin, captureLast)
    Files.readAllBytes(captureBin).length % 16 mustBe 0
    val rawPackedScript =
      """import sys
        |raw = open(sys.argv[1], "rb").read()
        |with open(sys.argv[2], "wb") as handle:
        |    handle.write(b"".join(raw[index:index + 11] for index in range(0, len(raw), 16)))
        |""".stripMargin
    expectSuccess(
      Seq(
        "python3",
        "-c",
        rawPackedScript,
        captureBin.toString,
        rawCaptureBin.toString
      )
    )
    Files.copy(captureLast, rawCaptureLast)

    val captureOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--stream-bin",
        captureBin.toString,
        "--last-bin",
        captureLast.toString,
        "--codestream-bin",
        assembledCodestream.toString,
        "--expect-codestream-bin",
        expectedCodestream.toString,
        "--expect-target-interface",
        "rgb_axi_stream",
        "--expect-target-controlled-shell",
        "HjxlAxiLiteStreamCore",
        "--dc-tokens-npy",
        capturedDcTokens.toString,
        "--summary-json",
        summaryJson.toString
      )
    )
    captureOutput must include("validated capture for replay capture: 16x8 distance=1")
    captureOutput must include("requested_distance=1")
    captureOutput must include("distance_supported=true")
    Files.readAllBytes(assembledCodestream).toSeq mustBe Files.readAllBytes(expectedCodestream).toSeq
    Files.readAllBytes(capturedDcTokens).toSeq mustBe Files.readAllBytes(dcTokens).toSeq
    val summary = Files.readString(summaryJson).replace("\r\n", "\n")
    summary must include("\"format\": \"hjxl.capture_summary.v1\"")
    summary must include("\"target\"")
    summary must include("\"interface\": \"rgb_axi_stream\"")
    summary must include("\"controlled_shell\": \"HjxlAxiLiteStreamCore\"")
    summary must include("\"group_bits\": 16")
    summary must include("\"trace_value_bits\": 32")
    summary must include("\"stream_word_bytes\": 16")
    summary must include("\"requires_final_last\": true")
    summary must include("\"width\": 16")
    summary must include("\"height\": 8")
    summary must include("\"distance\": 1.0")
    summary must include("\"requested_distance\": 1.0")
    summary must include("\"requested\": 256")
    summary must include("\"effective\": 256")
    summary must include("\"supported\": true")

    val bundleIndexOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--bundle-index",
        bundleDir.resolve("capture-bundle.json").toString,
        "--stream-bin",
        captureBin.toString,
        "--last-bin",
        captureLast.toString,
        "--codestream-bin",
        bundleIndexCodestream.toString,
        "--expect-codestream-bin",
        expectedCodestream.toString
      )
    )
    bundleIndexOutput must include("validated capture for replay capture: 16x8 distance=1")
    Files.readAllBytes(bundleIndexCodestream).toSeq mustBe Files.readAllBytes(expectedCodestream).toSeq

    val rawCaptureOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--stream-bin",
        rawCaptureBin.toString,
        "--last-bin",
        rawCaptureLast.toString,
        "--stream-word-bytes",
        "11",
        "--codestream-bin",
        rawCodestream.toString,
        "--expect-codestream-bin",
        expectedCodestream.toString,
        "--summary-json",
        rawSummaryJson.toString
      )
    )
    rawCaptureOutput must include("validated capture for replay capture: 16x8 distance=1")
    Files.readAllBytes(rawCodestream).toSeq mustBe Files.readAllBytes(expectedCodestream).toSeq
    Files.readString(rawSummaryJson).replace("\r\n", "\n") must include("\"stream_word_bytes\": 11")
    Files.readString(rawSummaryJson).replace("\r\n", "\n") must include("\"interface\": \"rgb_axi_stream\"")

    expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_rgb_stream.py",
        "--pfm",
        pfm.toString,
        "--stream-csv",
        unsupportedStreamCsv.toString,
        "--axi-lite-csv",
        unsupportedControlCsv.toString,
        "--manifest-json",
        unsupportedManifestJson.toString,
        "--enable-dct",
        "--enable-quant",
        "--enable-tokenize",
        "--token-select",
        "ac-tokens",
        "--distance-q8",
        "333"
      )
    )
    expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--manifest-json",
        unsupportedManifestJson.toString,
        "--output-dir",
        unsupportedBundleDir.toString,
        "--name",
        "capture-unsupported",
        "--symbol-prefix",
        "HJXL_CAPTURE_UNSUPPORTED",
        "--replay-plan-json",
        unsupportedReplayPlan.toString
      )
    )
    val unsupportedDistanceOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        unsupportedReplayPlan.toString,
        "--stream-bin",
        captureBin.toString,
        "--last-bin",
        captureLast.toString,
        "--codestream-bin",
        unsupportedDistanceCodestream.toString,
        "--expect-codestream-bin",
        expectedCodestream.toString,
        "--summary-json",
        unsupportedDistanceSummaryJson.toString
      )
    )
    unsupportedDistanceOutput must include("validated capture for replay capture-unsupported: 16x8 distance=1")
    unsupportedDistanceOutput must include("requested_distance=1.30078")
    unsupportedDistanceOutput must include("distance_supported=false")
    Files.readAllBytes(unsupportedDistanceCodestream).toSeq mustBe Files.readAllBytes(expectedCodestream).toSeq
    val unsupportedDistanceSummary = Files.readString(unsupportedDistanceSummaryJson).replace("\r\n", "\n")
    unsupportedDistanceSummary must include("\"distance\": 1.0")
    unsupportedDistanceSummary must include("\"requested_distance\": 1.30078125")
    unsupportedDistanceSummary must include("\"requested\": 333")
    unsupportedDistanceSummary must include("\"effective\": 256")
    unsupportedDistanceSummary must include("\"supported\": false")

    val strictUnsupportedDistance = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        unsupportedReplayPlan.toString,
        "--stream-bin",
        captureBin.toString,
        "--last-bin",
        captureLast.toString,
        "--require-supported-distance",
        "--expect-codestream-bin",
        expectedCodestream.toString
      )
    )
    strictUnsupportedDistance.exitCode mustBe 1
    strictUnsupportedDistance.output must include("distance_q8 333 is not supported by RTL distance lookup")
    strictUnsupportedDistance.output must not include "Traceback"

    val invalidWordBytes = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--stream-bin",
        captureBin.toString,
        "--last-bin",
        captureLast.toString,
        "--stream-word-bytes",
        "0",
        "--expect-codestream-bin",
        expectedCodestream.toString
      )
    )
    invalidWordBytes.exitCode mustBe 1
    invalidWordBytes.output must include("--stream-word-bytes must be positive")
    invalidWordBytes.output must not include "Traceback"

    val invalidTarget = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--stream-bin",
        captureBin.toString,
        "--last-bin",
        captureLast.toString,
        "--expect-target-interface",
        "prepared_dct_axi_stream",
        "--expect-codestream-bin",
        expectedCodestream.toString
      )
    )
    invalidTarget.exitCode mustBe 1
    invalidTarget.output must include("replay plan target interface expected 'prepared_dct_axi_stream', got 'rgb_axi_stream'")
    invalidTarget.output must not include "Traceback"

    val originalReplayPlan = Files.readString(replayPlan)
    Files.writeString(
      replayPlan,
      originalReplayPlan.replace(
        s""""byte_count": $expectedInputByteCount""",
        s""""byte_count": ${expectedInputByteCount - 1}"""
      )
    )
    val stalePlanResult = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--stream-bin",
        captureBin.toString,
        "--last-bin",
        captureLast.toString,
        "--expect-codestream-bin",
        expectedCodestream.toString
      )
    )
    stalePlanResult.exitCode mustBe 1
    stalePlanResult.output must include("replay plan does not match described bundle")
    stalePlanResult.output must not include "Traceback"
    Files.writeString(replayPlan, originalReplayPlan)
  }

  "hjxl_replay_capture.py rejects missing capture inputs before loading a plan" in {
    val temp = Files.createTempDirectory("hjxl-replay-capture-missing-input-")
    val result = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--bundle-index",
        temp.resolve("missing-bundle.json").toString,
        "--summary-json",
        temp.resolve("summary.json").toString
      )
    )

    result.exitCode mustBe 1
    result.output must include("at least one --trace-csv, --stream-csv, or --stream-bin input is required")
    result.output must not include "Traceback"
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
    val streamBin = temp.resolve("stream.bin")
    val lastBin = temp.resolve("last.bin")
    val traceCsv = temp.resolve("trace.csv")
    val dcTokens = temp.resolve("dc.npy")
    val binaryDcTokens = temp.resolve("dc-binary.npy")
    val strategy = temp.resolve("strategy.npy")
    val binaryStrategy = temp.resolve("strategy-binary.npy")
    val directDcTokens = temp.resolve("direct-dc.npy")
    val directStrategy = temp.resolve("direct-strategy.npy")
    val packedRows = Seq(
      pack(TraceStage.DcTokens, 0, 5, 7),
      pack(TraceStage.DcTokens, 1, 6, 8),
      pack(TraceStage.AcStrategy, 0, 0, AcStrategyCode.encoded(AcStrategyCode.Dct, isFirstBlock = true))
    )
    Files.writeString(
      streamCsv,
      "data,last\n" +
        s"${packedRows(0)},0\n" +
        s"${packedRows(1)},0\n" +
        s"${packedRows(2)},1\n"
    )
    val binaryData = ByteBuffer.allocate(packedRows.length * 11).order(ByteOrder.LITTLE_ENDIAN)
    for (word <- packedRows) {
      var shifted = word
      for (_ <- 0 until 11) {
        binaryData.put((shifted & 0xff).toByte)
        shifted = shifted >> 8
      }
    }
    Files.write(streamBin, binaryData.array())
    Files.write(lastBin, Array[Byte](0, 0, 1))

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
        "--stream-bin",
        streamBin.toString,
        "--last-bin",
        lastBin.toString,
        "--dc-tokens-npy",
        binaryDcTokens.toString,
        "--ac-strategy-npy",
        binaryStrategy.toString,
        "--width",
        "8",
        "--height",
        "8",
        "--require-stream-final-last"
      )
    )
    readNpyAsJson(binaryDcTokens) mustBe "[[5,7],[6,8]]"
    readNpyAsJson(binaryStrategy) mustBe "[[1]]"

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
    val streamBin = temp.resolve("stream.bin")
    val lastBin = temp.resolve("last.bin")
    val codestream = temp.resolve("out.jxl")
    val binaryCodestream = temp.resolve("out-binary.jxl")
    val packedRows = Seq(
      pack(TraceStage.DcTokens, 0, 0, 0),
      pack(TraceStage.AcMetadataTokens, 0, 0, 0),
      pack(TraceStage.AcTokens, 0, 0, 0)
    )
    Files.writeString(
      streamCsv,
      "data,last\n" +
        s"${packedRows(0)},0\n" +
        s"${packedRows(1)},0\n" +
        s"${packedRows(2)},1\n"
    )
    val binaryData = ByteBuffer.allocate(packedRows.length * 11).order(ByteOrder.LITTLE_ENDIAN)
    for (word <- packedRows) {
      var shifted = word
      for (_ <- 0 until 11) {
        binaryData.put((shifted & 0xff).toByte)
        shifted = shifted >> 8
      }
    }
    Files.write(streamBin, binaryData.array())
    Files.write(lastBin, Array[Byte](0, 0, 1))

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

    val binaryResult = runCommand(
      Seq(
        "python3",
        "tools/hjxl_trace_to_codestream.py",
        "--stream-bin",
        streamBin.toString,
        "--last-bin",
        lastBin.toString,
        "--width",
        "8",
        "--height",
        "8",
        "--codestream-bin",
        binaryCodestream.toString,
        "--require-stream-final-last"
      )
    )
    binaryResult.exitCode mustBe 1
    binaryResult.output must include("missing AC strategy trace indices")
  }
}
