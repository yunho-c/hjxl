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
    val symbolStem = symbolPrefix.toLowerCase
    val cSource = header.getParent.resolve(s"$sourceStem-header-smoke.c")
    val cxxSource = header.getParent.resolve(s"$sourceStem-header-smoke.cc")
    val sourceText =
      s"""#include "${cIncludePath(header)}"
         |
         |int main(void) {
         |  const uint8_t word[16] = {
         |    0x05, 0x34, 0x12, 0xef, 0xcd, 0xab, 0x89, 0xfd,
         |    0xff, 0xff, 0xff, 0, 0, 0, 0, 0
         |  };
         |  const uint8_t min_word[16] = {
         |    0x06, 0x78, 0x56, 0x01, 0xef, 0xcd, 0xab, 0x00,
         |    0x00, 0x00, 0x80, 0, 0, 0, 0, 0
         |  };
         |  ${symbolStem}_stage_trace_t trace = ${symbolStem}_decode_trace_word(word);
         |  if (${symbolPrefix}_AXI_LITE_WRITES[0].strb != 0x0f ||
         |      ${symbolPrefix}_AXI_LITE_WRITE_COUNT != 9u ||
         |      ${symbolPrefix}_AXI_LITE_WRITES[7].address != ${symbolPrefix}_REG_FIXED_YTOX ||
         |      ${symbolPrefix}_AXI_LITE_WRITES[7].data != 0xfffffff9u ||
         |      ${symbolPrefix}_AXI_LITE_WRITES[7].strb != 0x0fu ||
         |      ${symbolPrefix}_AXI_LITE_WRITES[8].address != ${symbolPrefix}_REG_FIXED_YTOB ||
         |      ${symbolPrefix}_AXI_LITE_WRITES[8].data != 0x0000000bu ||
         |      ${symbolPrefix}_AXI_LITE_WRITES[8].strb != 0x0fu ||
         |      trace.stage != 5u ||
         |      trace.group != 0x1234u ||
         |      trace.index != 0x89abcdefu ||
         |      trace.value != -3) {
         |    return 1;
         |  }
         |  trace = ${symbolStem}_decode_trace_word(min_word);
         |  return (int)(trace.stage != 6u ||
         |               trace.group != 0x5678u ||
         |               trace.index != 0xabcdef01u ||
         |               trace.value != (-2147483647 - 1));
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
       |  "image": {"x_blocks": 1, "y_blocks": 1, "x_tiles": 1, "y_tiles": 1},
       |  "blocks": [
       |    {
       |      "block_index": 0,
       |      "block_x": 0,
       |      "block_y": 0,
       |      "tile_x": 0,
       |      "tile_y": 0,
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

  private def preparedTokenFixtureJson: String = {
    val zeroCoefficients = Seq.fill(64)(0).mkString("[", ",", "]")
    s"""{
       |  "format": "hjxl.fixed_dct_only_prepared_token_inputs.v1",
       |  "image": {
       |    "xsize": 8,
       |    "ysize": 8,
       |    "x_blocks": 1,
       |    "y_blocks": 1,
       |    "x_tiles": 1,
       |    "y_tiles": 1
       |  },
       |  "fixed_raw_quant": 200,
       |  "fixed_ytox": -7,
       |  "fixed_ytob": 11,
       |  "dc_samples": [11, -2, 7],
       |  "ac_blocks": [
       |    {
       |      "block_index": 0,
       |      "block_x": 0,
       |      "block_y": 0,
       |      "tile_x": 0,
       |      "tile_y": 0,
       |      "num_nonzeros": [0, 0, 0],
       |      "quantized": [$zeroCoefficients, $zeroCoefficients, $zeroCoefficients]
       |    }
       |  ]
       |}""".stripMargin
  }

  "hjxl_host_metadata_smoke.py validates host bundle and replay metadata" in {
    val output = expectSuccess(Seq("python3", "tools/hjxl_host_metadata_smoke.py"))
    output must include("host metadata smoke passed")
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

    Files.writeString(streamCsv, "tdata,tlast\nbad,1\n")
    val invalidData = runCommand(
      Seq(
        "python3",
        "tools/hjxl_stream_trace.py",
        "--stream-csv",
        streamCsv.toString,
        "--trace-csv",
        traceCsv.toString
      )
    )
    invalidData.exitCode mustBe 1
    invalidData.output must include("tdata must be an integer")
    invalidData.output must not include "Traceback"

    Files.writeString(
      streamCsv,
      s"tdata,tlast\n0x${pack(TraceStage.AcTokens, 3, 4, -7).toString(16)},maybe\n"
    )
    val invalidLast = runCommand(
      Seq(
        "python3",
        "tools/hjxl_stream_trace.py",
        "--stream-csv",
        streamCsv.toString,
        "--trace-csv",
        traceCsv.toString
      )
    )
    invalidLast.exitCode mustBe 1
    invalidLast.output must include("tlast must be boolean")
    invalidLast.output must not include "Traceback"
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
    val overrideStreamCsv = temp.resolve("prepared-override-stream.csv")
    val overrideControlCsv = temp.resolve("prepared-override-control.csv")
    val overrideManifestJson = temp.resolve("prepared-override-manifest.json")
    val estimatedStreamCsv = temp.resolve("prepared-estimated-cfl-stream.csv")
    val estimatedControlCsv = temp.resolve("prepared-estimated-cfl-control.csv")
    val estimatedManifestJson = temp.resolve("prepared-estimated-cfl-manifest.json")
    val estimatedHeader = temp.resolve("prepared-estimated-cfl.h")
    val estimatedHostBundleDir = temp.resolve("prepared-estimated-cfl-host-bundle")
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
      "28,526,15",
      "32,0,15",
      "36,0,15"
    )

    val manifest = Files.readString(manifestJson).replace("\r\n", "\n")
    manifest must include("\"format\": \"hjxl.prepared_dct_stream_manifest.v1\"")
    manifest must include("\"xsize\": 8")
    manifest must include("\"ysize\": 8")
    manifest must include("\"x_blocks\": 1")
    manifest must include("\"y_blocks\": 1")
    manifest must include("\"x_tiles\": 1")
    manifest must include("\"y_tiles\": 1")
    manifest must include("\"word_count\": 201")
    manifest must include("\"block_count\": 1")
    manifest must include("\"words_per_block\": 201")
    manifest must include("\"coefficient_fraction_bits\": 16")
    manifest must include("\"status_control\": 0")
    manifest must include("\"unsupported_distance\": 3")
    manifest must include("\"clear_protocol_error_write_bit\": 0")
    manifest must include("\"flags\": 526")
    manifest must include("\"fixed_ytox\": 0")
    manifest must include("\"fixed_ytob\": 0")
    manifest must include("\"token_select\": \"ac-tokens\"")
    manifest must include("\"target\"")
    manifest must include("\"variant\": \"direct\"")
    manifest must include("\"stream_shell\": \"HjxlPreparedDctAxiStreamCore\"")
    manifest must include("\"controlled_shell\": \"HjxlPreparedDctAxiLiteStreamCore\"")
    manifest must include("\"kv260_top\": \"HjxlKv260PreparedDctTop\"")
    manifest must include("\"trace_route\"")
    manifest must include("\"name\": \"prepared-dct-quantize-token\"")
    manifest must include("\"stage\": null")
    manifest must include("\"focused\": true")

    val overrideOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--prepared-json",
        fixture.toString,
        "--input-stream-csv",
        overrideStreamCsv.toString,
        "--axi-lite-csv",
        overrideControlCsv.toString,
        "--manifest-json",
        overrideManifestJson.toString,
        "--flags",
        "0x20"
      )
    )
    overrideOutput mustBe ""
    val overrideControlRows =
      Files.readString(overrideControlCsv).replace("\r\n", "\n").trim.split("\n").toVector
    overrideControlRows must contain("28,32,15")
    val overrideManifest = Files.readString(overrideManifestJson).replace("\r\n", "\n")
    overrideManifest must include("\"flags\": 32")
    overrideManifest must include("\"token_select\": \"flags-override\"")

    val estimatedOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--prepared-json",
        fixture.toString,
        "--input-stream-csv",
        estimatedStreamCsv.toString,
        "--axi-lite-csv",
        estimatedControlCsv.toString,
        "--manifest-json",
        estimatedManifestJson.toString,
        "--target-variant",
        "estimated-cfl"
      )
    )
    estimatedOutput mustBe ""
    val estimatedValidateOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--validate-manifest",
        estimatedManifestJson.toString
      )
    )
    estimatedValidateOutput must include(s"validated $estimatedManifestJson")
    val estimatedManifest = Files.readString(estimatedManifestJson).replace("\r\n", "\n")
    estimatedManifest must include("\"variant\": \"estimated-cfl\"")
    estimatedManifest must include("\"stream_shell\": \"HjxlPreparedCflDctAxiStreamCore\"")
    estimatedManifest must include("\"controlled_shell\": \"HjxlPreparedCflDctAxiLiteStreamCore\"")
    estimatedManifest must include("\"kv260_top\": \"HjxlKv260PreparedCflDctTop\"")

    val invalidEstimatedManifest = estimatedManifest.replace(
      "\"stream_shell\": \"HjxlPreparedCflDctAxiStreamCore\"",
      "\"stream_shell\": \"HjxlPreparedDctAxiStreamCore\""
    )
    Files.writeString(estimatedManifestJson, invalidEstimatedManifest)
    val invalidEstimatedTarget = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--validate-manifest",
        estimatedManifestJson.toString
      )
    )
    invalidEstimatedTarget.exitCode mustBe 1
    invalidEstimatedTarget.output must include("target.stream_shell does not match target.variant")
    invalidEstimatedTarget.output must not include "Traceback"
    Files.writeString(estimatedManifestJson, estimatedManifest)

    val estimatedHeaderOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_manifest_header.py",
        "--manifest-json",
        estimatedManifestJson.toString,
        "--header",
        estimatedHeader.toString,
        "--symbol-prefix",
        "HJXL_PREPARED_CFL"
      )
    )
    estimatedHeaderOutput must include(s"wrote $estimatedHeader")
    val estimatedHeaderText = Files.readString(estimatedHeader).replace("\r\n", "\n")
    estimatedHeaderText must include("#define HJXL_PREPARED_CFL_TARGET_VARIANT \"estimated-cfl\"")
    estimatedHeaderText must include(
      "#define HJXL_PREPARED_CFL_TARGET_STREAM_SHELL \"HjxlPreparedCflDctAxiStreamCore\""
    )
    estimatedHeaderText must include(
      "#define HJXL_PREPARED_CFL_TARGET_CONTROLLED_SHELL \"HjxlPreparedCflDctAxiLiteStreamCore\""
    )
    estimatedHeaderText must include("#define HJXL_PREPARED_CFL_TARGET_KV260_TOP \"HjxlKv260PreparedCflDctTop\"")

    expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--manifest-json",
        estimatedManifestJson.toString,
        "--output-dir",
        estimatedHostBundleDir.toString,
        "--name",
        "prepared-estimated-cfl",
        "--symbol-prefix",
        "HJXL_PREPARED_CFL_BUNDLE"
      )
    )
    val estimatedIndex = Files.readString(
      estimatedHostBundleDir.resolve("prepared-estimated-cfl-bundle.json")
    ).replace("\r\n", "\n")
    estimatedIndex must include("\"variant\": \"estimated-cfl\"")
    estimatedIndex must include("\"stream_shell\": \"HjxlPreparedCflDctAxiStreamCore\"")
    estimatedIndex must include("\"controlled_shell\": \"HjxlPreparedCflDctAxiLiteStreamCore\"")
    estimatedIndex must include("\"kv260_top\": \"HjxlKv260PreparedCflDctTop\"")
    val estimatedReplayPlan = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--describe-bundle",
        estimatedHostBundleDir.resolve("prepared-estimated-cfl-bundle.json").toString
      )
    )
    estimatedReplayPlan must include("\"variant\": \"estimated-cfl\"")
    estimatedReplayPlan must include("\"stream_shell\": \"HjxlPreparedCflDctAxiStreamCore\"")
    estimatedReplayPlan must include("\"controlled_shell\": \"HjxlPreparedCflDctAxiLiteStreamCore\"")
    estimatedReplayPlan must include("\"kv260_top\": \"HjxlKv260PreparedCflDctTop\"")
    val estimatedReplayPlanJson = temp.resolve("prepared-estimated-cfl-replay-plan.json")
    Files.writeString(estimatedReplayPlanJson, estimatedReplayPlan)
    val estimatedPreflightOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        estimatedReplayPlanJson.toString,
        "--preflight-only",
        "--expect-target-interface",
        "prepared_dct_axi_stream",
        "--expect-target-variant",
        "estimated-cfl",
        "--expect-target-stream-shell",
        "HjxlPreparedCflDctAxiStreamCore",
        "--expect-target-controlled-shell",
        "HjxlPreparedCflDctAxiLiteStreamCore",
        "--expect-target-kv260-top",
        "HjxlKv260PreparedCflDctTop",
        "--expect-target-input-stream",
        "prepared DCT-only block words with internally estimated CFL maps",
        "--expect-target-input-keep-enforced",
        "1",
        "--expect-input-coefficient-fraction-bits",
        "16",
        "--expect-input-word-count",
        "201",
        "--expect-input-byte-count",
        "804",
        "--expect-trace-route-name",
        "prepared-dct-quantize-token",
        "--expect-trace-route-stage",
        "-1",
        "--expect-trace-route-focused",
        "true"
      )
    )
    estimatedPreflightOutput must include("validated replay preflight prepared-estimated-cfl")

    val invalidEstimatedPreflight = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        estimatedReplayPlanJson.toString,
        "--preflight-only",
        "--expect-target-variant",
        "direct"
      )
    )
    invalidEstimatedPreflight.exitCode mustBe 1
    invalidEstimatedPreflight.output must include(
      "replay plan target variant expected 'direct', got 'estimated-cfl'"
    )
    invalidEstimatedPreflight.output must not include "Traceback"

    val invalidEstimatedNoKv260Top = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        estimatedReplayPlanJson.toString,
        "--preflight-only",
        "--expect-target-no-kv260-top"
      )
    )
    invalidEstimatedNoKv260Top.exitCode mustBe 1
    invalidEstimatedNoKv260Top.output must include(
      "replay plan target kv260_top expected null, got 'HjxlKv260PreparedCflDctTop'"
    )
    invalidEstimatedNoKv260Top.output must not include "Traceback"

    val outOfRangeFlags = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--prepared-json",
        fixture.toString,
        "--input-stream-csv",
        temp.resolve("bad-flags-stream.csv").toString,
        "--flags",
        "0x100000000"
      )
    )
    outOfRangeFlags.exitCode mustBe 1
    outOfRangeFlags.output must include("--flags must fit in uint32")
    outOfRangeFlags.output must not include "Traceback"

    val outOfRangeFixedRawQuant = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--prepared-json",
        fixture.toString,
        "--input-stream-csv",
        temp.resolve("bad-raw-quant-stream.csv").toString,
        "--fixed-raw-quant",
        "256"
      )
    )
    outOfRangeFixedRawQuant.exitCode mustBe 1
    outOfRangeFixedRawQuant.output must include("--fixed-raw-quant must fit in uint8")
    outOfRangeFixedRawQuant.output must not include "Traceback"

    val outOfRangeFixedYtob = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--prepared-json",
        fixture.toString,
        "--input-stream-csv",
        temp.resolve("bad-ytob-stream.csv").toString,
        "--fixed-ytob",
        "128"
      )
    )
    outOfRangeFixedYtob.exitCode mustBe 1
    outOfRangeFixedYtob.output must include("--fixed-ytob must fit in signed 8-bit")
    outOfRangeFixedYtob.output must not include "Traceback"

    val validateOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--validate-manifest",
        manifestJson.toString
      )
    )
    validateOutput must include(s"validated $manifestJson")

    val originalStreamCsv = Files.readString(streamCsv)
    Files.writeString(streamCsv, originalStreamCsv.replaceFirst("1234,0", "4321,0"))
    val invalidStreamData = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--validate-manifest",
        manifestJson.toString
      )
    )
    invalidStreamData.exitCode mustBe 1
    invalidStreamData.output must include("stream row 1 data 4321 does not match source prepared JSON value 1234")
    Files.writeString(streamCsv, originalStreamCsv)

    val originalControlCsv = Files.readString(controlCsv)
    Files.writeString(
      controlCsv,
      originalControlCsv.replaceFirst("""(?m)^4,8,15$""", "bad,8,15")
    )
    val invalidControlAddress = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--validate-manifest",
        manifestJson.toString
      )
    )
    invalidControlAddress.exitCode mustBe 1
    invalidControlAddress.output must include("address must be an integer")
    invalidControlAddress.output must not include "Traceback"

    Files.writeString(
      controlCsv,
      originalControlCsv.replaceFirst("""(?m)^4,8,15$""", "4,bad,15")
    )
    val invalidControlData = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--validate-manifest",
        manifestJson.toString
      )
    )
    invalidControlData.exitCode mustBe 1
    invalidControlData.output must include("data must be an integer")
    invalidControlData.output must not include "Traceback"

    Files.writeString(
      controlCsv,
      originalControlCsv.replaceFirst("""(?m)^4,8,15$""", "4,8,bad")
    )
    val invalidControlStrobe = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--validate-manifest",
        manifestJson.toString
      )
    )
    invalidControlStrobe.exitCode mustBe 1
    invalidControlStrobe.output must include("strb must be an integer")
    invalidControlStrobe.output must not include "Traceback"
    Files.writeString(controlCsv, originalControlCsv)

    val originalManifestJson = Files.readString(manifestJson)
    Files.writeString(manifestJson, originalManifestJson.replace("\"coefficient_fraction_bits\": 16", "\"coefficient_fraction_bits\": 12"))
    val invalidCoefficientScale = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--validate-manifest",
        manifestJson.toString
      )
    )
    invalidCoefficientScale.exitCode mustBe 1
    invalidCoefficientScale.output must include(
      "stream coefficient_fraction_bits 12 does not match source prepared JSON value 16"
    )
    Files.writeString(manifestJson, originalManifestJson)

    val originalFixtureJson = Files.readString(fixture)
    Files.writeString(fixture, originalFixtureJson.replace("\"x_tiles\": 1", "\"x_tiles\": 2"))
    val invalidSourceTileGrid = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--validate-manifest",
        manifestJson.toString
      )
    )
    invalidSourceTileGrid.exitCode mustBe 1
    invalidSourceTileGrid.output must include("image.x_tiles does not match declared dimensions")
    Files.writeString(fixture, originalFixtureJson)

    Files.writeString(fixture, originalFixtureJson.replace("\"block_x\": 0", "\"block_x\": 1"))
    val invalidBlockCoordinate = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--validate-manifest",
        manifestJson.toString
      )
    )
    invalidBlockCoordinate.exitCode mustBe 1
    invalidBlockCoordinate.output must include("block 0 block_x does not match raster order")
    Files.writeString(fixture, originalFixtureJson)

    Files.writeString(fixture, originalFixtureJson.replace("\"tile_x\": 0", "\"tile_x\": 1"))
    val invalidTileCoordinate = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--validate-manifest",
        manifestJson.toString
      )
    )
    invalidTileCoordinate.exitCode mustBe 1
    invalidTileCoordinate.output must include("block 0 tile_x does not match 64x64 tile grid")
    Files.writeString(fixture, originalFixtureJson)

    Files.writeString(
      fixture,
      originalFixtureJson.replace(
        "\"coefficients_q\": [[0,1",
        "\"coefficients_q\": [[1.5,1"
      )
    )
    val invalidCoefficientInteger = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--validate-manifest",
        manifestJson.toString
      )
    )
    invalidCoefficientInteger.exitCode mustBe 1
    invalidCoefficientInteger.output must include(
      "block 0 inputs.coefficients_q[0][0] must be an integer"
    )
    invalidCoefficientInteger.output must not include "Traceback"
    Files.writeString(fixture, originalFixtureJson)

    Files.writeString(
      fixture,
      originalFixtureJson.replace(
        "\"coefficients_q\": [[0,1",
        "\"coefficients_q\": [[2147483648,1"
      )
    )
    val outOfRangeCoefficient = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--validate-manifest",
        manifestJson.toString
      )
    )
    outOfRangeCoefficient.exitCode mustBe 1
    outOfRangeCoefficient.output must include(
      "block 0 inputs.coefficients_q[0][0] outside signed 32-bit range"
    )
    outOfRangeCoefficient.output must not include "Traceback"
    Files.writeString(fixture, originalFixtureJson)

    Files.writeString(
      fixture,
      originalFixtureJson.replace("\"ytox\": -2", "\"ytox\": 1.5")
    )
    val invalidScalarInteger = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--validate-manifest",
        manifestJson.toString
      )
    )
    invalidScalarInteger.exitCode mustBe 1
    invalidScalarInteger.output must include(
      "block 0 inputs.ytox must be an integer"
    )
    invalidScalarInteger.output must not include "Traceback"
    Files.writeString(fixture, originalFixtureJson)

    Files.writeString(
      fixture,
      originalFixtureJson.replace(
        "\"scale_q16\": 1234",
        "\"scale_q16\": 65536"
      )
    )
    val outOfRangeScale = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--validate-manifest",
        manifestJson.toString
      )
    )
    outOfRangeScale.exitCode mustBe 1
    outOfRangeScale.output must include(
      "block 0 inputs.scale_q16 outside uint16 range"
    )
    outOfRangeScale.output must not include "Traceback"
    Files.writeString(fixture, originalFixtureJson)

    Files.writeString(
      fixture,
      originalFixtureJson.replace("\"ytox\": -2", "\"ytox\": 128")
    )
    val outOfRangeCfl = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_blocks.py",
        "--validate-manifest",
        manifestJson.toString
      )
    )
    outOfRangeCfl.exitCode mustBe 1
    outOfRangeCfl.output must include(
      "block 0 inputs.ytox outside signed 8-bit range"
    )
    outOfRangeCfl.output must not include "Traceback"
    Files.writeString(fixture, originalFixtureJson)

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
    headerText must include("#define HJXL_PREPARED_TARGET_VARIANT \"direct\"")
    headerText must include("#define HJXL_PREPARED_TARGET_STREAM_SHELL \"HjxlPreparedDctAxiStreamCore\"")
    headerText must include("#define HJXL_PREPARED_TARGET_CONTROLLED_SHELL \"HjxlPreparedDctAxiLiteStreamCore\"")
    headerText must include("#define HJXL_PREPARED_TARGET_KV260_TOP \"HjxlKv260PreparedDctTop\"")
    headerText must include("#define HJXL_PREPARED_TRACE_ROUTE_NAME \"prepared-dct-quantize-token\"")
    headerText must include("#define HJXL_PREPARED_TRACE_ROUTE_STAGE -1")
    headerText must include("#define HJXL_PREPARED_TRACE_ROUTE_FOCUSED 1u")
    headerText must include("} hjxl_prepared_stage_trace_t;")
    headerText must include("static inline hjxl_prepared_stage_trace_t hjxl_prepared_decode_trace_word")
    headerText must include("static inline int32_t hjxl_prepared_sign_extend_trace_value")
    headerText must include("#define HJXL_PREPARED_STREAM_WORD_COUNT 201u")
    headerText must include("#define HJXL_PREPARED_FRAME_XSIZE 8u")
    headerText must include("#define HJXL_PREPARED_FRAME_YSIZE 8u")
    headerText must include("#define HJXL_PREPARED_FRAME_X_BLOCKS 1u")
    headerText must include("#define HJXL_PREPARED_FRAME_Y_BLOCKS 1u")
    headerText must include("#define HJXL_PREPARED_FRAME_BLOCK_COUNT 1u")
    headerText must include("#define HJXL_PREPARED_FRAME_X_TILES 1u")
    headerText must include("#define HJXL_PREPARED_FRAME_Y_TILES 1u")
    headerText must include("#define HJXL_PREPARED_FRAME_TILE_COUNT 1u")
    headerText must include("#define HJXL_PREPARED_INPUT_DATA_BITS 32u")
    headerText must include("#define HJXL_PREPARED_COEFFICIENT_FRACTION_BITS 16u")
    headerText must include("#define HJXL_PREPARED_STREAM_WORD_BYTES 4u")
    headerText must include("#define HJXL_PREPARED_INPUT_TKEEP_MASK 0x0000000fu")
    headerText must include("#define HJXL_PREPARED_INPUT_TKEEP_ENFORCED 1u")
    headerText must include("#define HJXL_PREPARED_STREAM_BYTE_COUNT 804u")
    headerText must include("#define HJXL_PREPARED_AXI_LITE_ADDR_BITS 8u")
    headerText must include("#define HJXL_PREPARED_AXI_LITE_DATA_BITS 32u")
    headerText must include("#define HJXL_PREPARED_AXI_LITE_STRB_BITS 4u")
    headerText must include("#define HJXL_PREPARED_TRACE_STAGE_SHIFT 0u")
    headerText must include("#define HJXL_PREPARED_TRACE_GROUP_SHIFT 8u")
    headerText must include("#define HJXL_PREPARED_TRACE_INDEX_SHIFT 24u")
    headerText must include("#define HJXL_PREPARED_TRACE_VALUE_SHIFT 56u")
    headerText must include("#define HJXL_PREPARED_TRACE_STAGE_BYTE_OFFSET 0u")
    headerText must include("#define HJXL_PREPARED_TRACE_GROUP_BYTE_OFFSET 1u")
    headerText must include("#define HJXL_PREPARED_TRACE_INDEX_BYTE_OFFSET 3u")
    headerText must include("#define HJXL_PREPARED_TRACE_VALUE_BYTE_OFFSET 7u")
    headerText must include("#define HJXL_PREPARED_TRACE_STAGE_MASK 0x000000ffu")
    headerText must include("#define HJXL_PREPARED_TRACE_GROUP_MASK 0x0000ffffu")
    headerText must include("#define HJXL_PREPARED_TRACE_INDEX_MASK 0xffffffffu")
    headerText must include("#define HJXL_PREPARED_TRACE_VALUE_MASK 0xffffffffu")
    headerText must include("#define HJXL_PREPARED_TRACE_PACKED_BITS 88u")
    headerText must include("#define HJXL_PREPARED_TRACE_PACKED_BYTES 11u")
    headerText must include("#define HJXL_PREPARED_TRACE_TKEEP_MASK 0x000007ffu")
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
      "HJXL_PREPARED_INPUT_TKEEP_MASK == ((1u << HJXL_PREPARED_STREAM_WORD_BYTES) - 1u)"
    )
    headerText must include(
      "HJXL_PREPARED_AXI_LITE_STRB_BITS == HJXL_PREPARED_AXI_LITE_DATA_BITS / 8u"
    )
    headerText must include(
      "HJXL_PREPARED_TRACE_VALUE_SHIFT == HJXL_PREPARED_TRACE_INDEX_SHIFT + HJXL_PREPARED_TRACE_INDEX_BITS"
    )
    headerText must include(
      "HJXL_PREPARED_TRACE_PACKED_BITS == HJXL_PREPARED_TRACE_VALUE_SHIFT + HJXL_PREPARED_TRACE_VALUE_BITS"
    )
    headerText must include(
      "HJXL_PREPARED_TRACE_VALUE_BITS > 0u && HJXL_PREPARED_TRACE_VALUE_BITS <= 32u"
    )
    headerText must include(
      "HJXL_PREPARED_TRACE_STAGE_SHIFT % 8u == 0u"
    )
    headerText must include(
      "HJXL_PREPARED_TRACE_VALUE_BYTE_OFFSET == HJXL_PREPARED_TRACE_VALUE_SHIFT / 8u"
    )
    headerText must include(
      "sizeof(HJXL_PREPARED_AXI_LITE_WRITES) / sizeof(HJXL_PREPARED_AXI_LITE_WRITES[0]) == " +
        "HJXL_PREPARED_AXI_LITE_WRITE_COUNT"
    )
    headerText must include(
      "HJXL_PREPARED_TRACE_PACKED_BYTES == (HJXL_PREPARED_TRACE_PACKED_BITS + 7u) / 8u"
    )
    headerText must include(
      "HJXL_PREPARED_TRACE_TKEEP_MASK == ((1u << HJXL_PREPARED_TRACE_PACKED_BYTES) - 1u)"
    )
    headerText must include(
      "sizeof(HJXL_PREPARED_SUPPORTED_DISTANCE_Q8) / sizeof(HJXL_PREPARED_SUPPORTED_DISTANCE_Q8[0]) == " +
        "HJXL_PREPARED_SUPPORTED_DISTANCE_Q8_COUNT"
    )
    compileGeneratedHeaderSmoke(header, "HJXL_PREPARED")

    Files.writeString(manifestJson, originalManifestJson.replace("\"word_count\": 201", "\"word_count\": \"bad\""))
    val invalidHeaderWordCount = runCommand(
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
    invalidHeaderWordCount.exitCode mustBe 1
    invalidHeaderWordCount.output must include("stream.word_count must be an integer")
    invalidHeaderWordCount.output must not include "Traceback"
    Files.writeString(manifestJson, originalManifestJson)

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

    val originalPreparedStreamCsv = Files.readString(streamCsv)
    Files.writeString(streamCsv, originalPreparedStreamCsv.replaceFirst("5,0", "bad,0"))
    val invalidBufferData = runCommand(
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
    invalidBufferData.exitCode mustBe 1
    invalidBufferData.output must include("data must be an integer")
    invalidBufferData.output must not include "Traceback"

    Files.writeString(streamCsv, originalPreparedStreamCsv.replaceFirst("5,0", "5,maybe"))
    val invalidBufferLast = runCommand(
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
    invalidBufferLast.exitCode mustBe 1
    invalidBufferLast.output must include("last must be boolean")
    invalidBufferLast.output must not include "Traceback"
    Files.writeString(streamCsv, originalPreparedStreamCsv)

    Files.writeString(manifestJson, originalManifestJson.replace("\"word_count\": 201", "\"word_count\": \"bad\""))
    val invalidBufferWordCount = runCommand(
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
    invalidBufferWordCount.exitCode mustBe 1
    invalidBufferWordCount.output must include("stream.word_count must be an integer")
    invalidBufferWordCount.output must not include "Traceback"
    Files.writeString(manifestJson, originalManifestJson)

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
    Files.readString(preparedGeneratedReplayPlanJson).replace("\r\n", "\n") must include("\"frame\"")
    Files.readString(preparedGeneratedReplayPlanJson).replace("\r\n", "\n") must include("\"xsize\": 8")
    Files.readString(preparedGeneratedReplayPlanJson).replace("\r\n", "\n") must include("\"ysize\": 8")
    Files.readString(preparedGeneratedReplayPlanJson).replace("\r\n", "\n") must include("\"padded_xsize\": 8")
    Files.readString(preparedGeneratedReplayPlanJson).replace("\r\n", "\n") must include("\"block_count\": 1")
    Files.readString(preparedGeneratedReplayPlanJson).replace("\r\n", "\n") must include("\"input_keep_mask\": 15")
    Files.readString(preparedGeneratedReplayPlanJson).replace("\r\n", "\n") must include(
      "\"coefficient_fraction_bits\": 16"
    )
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
    Files.readString(hostBundleDir.resolve("prepared.h")).replace("\r\n", "\n") must include(
      "#define HJXL_PREPARED_BUNDLE_FRAME_TILE_COUNT 1u"
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
    preparedIndex must include("\"variant\": \"direct\"")
    preparedIndex must include("\"controlled_shell\": \"HjxlPreparedDctAxiLiteStreamCore\"")
    preparedIndex must include("\"kv260_top\": \"HjxlKv260PreparedDctTop\"")
    preparedIndex must include("\"distance\"")
    preparedIndex must include("\"frame\"")
    preparedIndex must include("\"xsize\": 8")
    preparedIndex must include("\"ysize\": 8")
    preparedIndex must include("\"x_blocks\": 1")
    preparedIndex must include("\"y_blocks\": 1")
    preparedIndex must include("\"padded_xsize\": 8")
    preparedIndex must include("\"padded_ysize\": 8")
    preparedIndex must include("\"block_count\": 1")
    preparedIndex must include("\"x_tiles\": 1")
    preparedIndex must include("\"y_tiles\": 1")
    preparedIndex must include("\"tile_count\": 1")
    preparedIndex must include("\"fallback_q8\": 256")
    preparedIndex must include("\"supported_q8\"")
    preparedIndex must include("\"trace\"")
    preparedIndex must include("\"stage_shift\": 0")
    preparedIndex must include("\"group_shift\": 8")
    preparedIndex must include("\"index_shift\": 24")
    preparedIndex must include("\"trace_value_shift\": 56")
    preparedIndex must include("\"stage_byte_offset\": 0")
    preparedIndex must include("\"group_byte_offset\": 1")
    preparedIndex must include("\"index_byte_offset\": 3")
    preparedIndex must include("\"trace_value_byte_offset\": 7")
    preparedIndex must include("\"stage_mask\": 255")
    preparedIndex must include("\"group_mask\": 65535")
    preparedIndex must include("\"packed_bits\": 88")
    preparedIndex must include("\"tkeep_mask\": 2047")
    preparedIndex must include("\"default_capture_word_bytes\": 16")
    preparedIndex must include("\"byte_count\": 804")
    preparedIndex must include("\"coefficient_fraction_bits\": 16")
    preparedIndex must include("\"input_data_bytes\": 4")
    preparedIndex must include("\"input_keep_mask\": 15")
    preparedIndex must include("\"word_count\": 201")
    preparedIndex must include("\"axi_lite\"")
    preparedIndex must include("\"addr_bits\": 8")
    preparedIndex must include("\"data_bits\": 32")
    preparedIndex must include("\"register_map\"")
    preparedIndex must include("\"status_control\": 0")
    preparedIndex must include("\"distance_q8\": 12")
    preparedIndex must include("\"flags\": 28")
    preparedIndex must include("\"fixed_ytox\": 32")
    preparedIndex must include("\"fixed_ytob\": 36")
    preparedIndex must include("\"strb_bits\": 4")
    preparedIndex must include("\"axi_lite_write_count\": 9")

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
    preparedReplayPlan must include("\"coefficient_fraction_bits\": 16")
    preparedReplayPlan must include("\"input_data_bits\": 32")
    preparedReplayPlan must include("\"input_keep_mask\": 15")
    preparedReplayPlan must include("\"target\"")
    preparedReplayPlan must include("\"interface\": \"prepared_dct_axi_stream\"")
    preparedReplayPlan must include("\"variant\": \"direct\"")
    preparedReplayPlan must include("\"stream_shell\": \"HjxlPreparedDctAxiStreamCore\"")
    preparedReplayPlan must include("\"controlled_shell\": \"HjxlPreparedDctAxiLiteStreamCore\"")
    preparedReplayPlan must include("\"kv260_top\": \"HjxlKv260PreparedDctTop\"")
    preparedReplayPlan must include("\"trace\"")
    preparedReplayPlan must include("\"stage_shift\": 0")
    preparedReplayPlan must include("\"group_shift\": 8")
    preparedReplayPlan must include("\"index_shift\": 24")
    preparedReplayPlan must include("\"trace_value_shift\": 56")
    preparedReplayPlan must include("\"stage_byte_offset\": 0")
    preparedReplayPlan must include("\"group_byte_offset\": 1")
    preparedReplayPlan must include("\"index_byte_offset\": 3")
    preparedReplayPlan must include("\"trace_value_byte_offset\": 7")
    preparedReplayPlan must include("\"stage_mask\": 255")
    preparedReplayPlan must include("\"group_mask\": 65535")
    preparedReplayPlan must include("\"packed_bits\": 88")
    preparedReplayPlan must include("\"packed_bytes\": 11")
    preparedReplayPlan must include("\"tkeep_mask\": 2047")
    preparedReplayPlan must include("\"default_capture_word_bytes\": 16")
    preparedReplayPlan must include("\"distance\"")
    preparedReplayPlan must include("\"frame\"")
    preparedReplayPlan must include("\"xsize\": 8")
    preparedReplayPlan must include("\"ysize\": 8")
    preparedReplayPlan must include("\"x_blocks\": 1")
    preparedReplayPlan must include("\"y_blocks\": 1")
    preparedReplayPlan must include("\"padded_xsize\": 8")
    preparedReplayPlan must include("\"padded_ysize\": 8")
    preparedReplayPlan must include("\"block_count\": 1")
    preparedReplayPlan must include("\"x_tiles\": 1")
    preparedReplayPlan must include("\"y_tiles\": 1")
    preparedReplayPlan must include("\"tile_count\": 1")
    preparedReplayPlan must include("\"fallback_q8\": 256")
    preparedReplayPlan must include("\"supported_q8\"")
    preparedReplayPlan must include("64")
    preparedReplayPlan must include("2048")
    preparedReplayPlan must include("\"status_bits\"")
    preparedReplayPlan must include("\"protocol_error\": 0")
    preparedReplayPlan must include("\"busy\": 1")
    preparedReplayPlan must include("\"overflow\": 2")
    preparedReplayPlan must include("\"unsupported_distance\": 3")
    preparedReplayPlan must include("\"clear_protocol_error_write_bit\": 0")
    preparedReplayPlan must include("\"addr_bits\": 8")
    preparedReplayPlan must include("\"data_bits\": 32")
    preparedReplayPlan must include("\"register_map\"")
    preparedReplayPlan must include("\"status_control\": 0")
    preparedReplayPlan must include("\"xsize\": 4")
    preparedReplayPlan must include("\"ysize\": 8")
    preparedReplayPlan must include("\"distance_q8\": 12")
    preparedReplayPlan must include("\"fixed_point_scale\": 16")
    preparedReplayPlan must include("\"fixed_inv_qac_q16\": 20")
    preparedReplayPlan must include("\"fixed_raw_quant\": 24")
    preparedReplayPlan must include("\"flags\": 28")
    preparedReplayPlan must include("\"fixed_ytox\": 32")
    preparedReplayPlan must include("\"fixed_ytob\": 36")
    preparedReplayPlan must include("\"strb_bits\": 4")
    preparedReplayPlan must include("\"write_count\": 9")
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
    Files.readString(preparedReplayPlanJson).replace("\r\n", "\n") must include("\"frame\"")
    Files.readString(preparedReplayPlanJson).replace("\r\n", "\n") must include("\"block_count\": 1")
    Files.readString(preparedReplayPlanJson).replace("\r\n", "\n") must include("\"input_data_bits\": 32")
    Files.readString(preparedReplayPlanJson).replace("\r\n", "\n") must include("\"input_keep_mask\": 15")
    Files.readString(preparedReplayPlanJson).replace("\r\n", "\n") must include("\"register_map\"")
    Files.readString(preparedReplayPlanJson).replace("\r\n", "\n") must include("\"flags\": 28")
    Files.readString(preparedReplayPlanJson).replace("\r\n", "\n") must include(
      "\"coefficient_fraction_bits\": 16"
    )
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

    Files.writeString(
      preparedReplayPlanJson,
      originalPreparedReplayPlan.replace("\"byte_count\": 804", "\"byte_count\": \"bad\"")
    )
    val invalidPreparedReplayPlanByteCount = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-replay-plan",
        preparedReplayPlanJson.toString
      )
    )
    invalidPreparedReplayPlanByteCount.exitCode mustBe 1
    invalidPreparedReplayPlanByteCount.output must include(
      "replay plan.stream.byte_count must be an integer"
    )
    invalidPreparedReplayPlanByteCount.output must not include "Traceback"
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
        """(?s),\n  "target": \{\n.*?\n  \},\n""",
        ",\n"
      )
      .replaceFirst(
        """(?s),\n  "trace": \{\n.*?\n  \}\n""",
        "\n"
      )
      .replaceFirst(
        """(?sm)^  "distance": \{\n    "fallback_q8": 256,\n    "supported_q8": \[\n      64,\n      128,\n      256,\n      512,\n      1024,\n      2048\n    \]\n  \},\n""",
        ""
      )
      .replaceFirst(
        """(?sm)^  "frame": \{\n    "block_count": 1,\n    "padded_xsize": 8,\n    "padded_ysize": 8,\n    "tile_count": 1,\n    "x_blocks": 1,\n    "x_tiles": 1,\n    "xsize": 8,\n    "y_blocks": 1,\n    "y_tiles": 1,\n    "ysize": 8\n  \},\n""",
        ""
      )
      .replaceFirst(
        """(?sm)^  "status_bits": \{\n    "busy": 1,\n    "clear_protocol_error_write_bit": 0,\n    "overflow": 2,\n    "protocol_error": 0,\n    "unsupported_distance": 3\n  \},\n""",
        ""
      )
      .replaceFirst("""(?m)^    "coefficient_fraction_bits": 16,\n""", "")
      .replaceFirst("""(?m)^    "input_data_bits": 32,\n""", "")
      .replaceFirst("""(?m)^    "input_keep_mask": 15,\n""", "")
      .replaceFirst("""(?m)^    "addr_bits": 8,\n""", "")
      .replaceFirst("""(?m)^    "data_bits": 32,\n""", "")
      .replaceFirst(
        """(?sm)^    "register_map": \{\n    "distance_q8": 12,\n    "fixed_inv_qac_q16": 20,\n    "fixed_point_scale": 16,\n    "fixed_raw_quant": 24,\n    "fixed_ytob": 36,\n    "fixed_ytox": 32,\n    "flags": 28,\n    "status_control": 0,\n    "xsize": 4,\n    "ysize": 8\n  \},\n""",
        ""
      )
      .replaceFirst("""(?m)^    "strb_bits": 4,\n""", "")
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
    Files.writeString(
      preparedBundleControl,
      originalPreparedBundleControl.replaceFirst("""(?m)^4,8,15$""", "bad,8,15")
    )
    val invalidPreparedBundleControlAddress = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        hostBundleDir.resolve("prepared-bundle.json").toString
      )
    )
    invalidPreparedBundleControlAddress.exitCode mustBe 1
    invalidPreparedBundleControlAddress.output must include("address must be an integer")
    invalidPreparedBundleControlAddress.output must not include "Traceback"

    Files.writeString(
      preparedBundleControl,
      originalPreparedBundleControl.replaceFirst("""(?m)^4,8,15$""", "4,bad,15")
    )
    val invalidPreparedBundleControlData = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        hostBundleDir.resolve("prepared-bundle.json").toString
      )
    )
    invalidPreparedBundleControlData.exitCode mustBe 1
    invalidPreparedBundleControlData.output must include("data must be an integer")
    invalidPreparedBundleControlData.output must not include "Traceback"

    Files.writeString(
      preparedBundleControl,
      originalPreparedBundleControl.replaceFirst("""(?m)^4,8,15$""", "4,8,bad")
    )
    val invalidPreparedBundleControlStrobe = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        hostBundleDir.resolve("prepared-bundle.json").toString
      )
    )
    invalidPreparedBundleControlStrobe.exitCode mustBe 1
    invalidPreparedBundleControlStrobe.output must include("strb must be an integer")
    invalidPreparedBundleControlStrobe.output must not include "Traceback"
    Files.writeString(preparedBundleControl, originalPreparedBundleControl)

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
    invalidPreparedBundleControl.output must include("flags expected 526, got 525")
    Files.writeString(preparedBundleControl, originalPreparedBundleControl)

    val preparedBundleIndex = hostBundleDir.resolve("prepared-bundle.json")
    val originalPreparedIndex = Files.readString(preparedBundleIndex)
    Files.writeString(
      preparedBundleIndex,
      originalPreparedIndex.replaceFirst("\"word_count\": 201", "\"word_count\": \"bad\"")
    )
    val invalidPreparedBundleWordCount = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        preparedBundleIndex.toString
      )
    )
    invalidPreparedBundleWordCount.exitCode mustBe 1
    invalidPreparedBundleWordCount.output must include("stream.word_count must be an integer")
    invalidPreparedBundleWordCount.output must not include "Traceback"
    Files.writeString(preparedBundleIndex, originalPreparedIndex)

    Files.writeString(
      preparedBundleIndex,
      originalPreparedIndex.replaceFirst("\"word_count\": 201", "\"word_count\": 201.5")
    )
    val invalidPreparedBundleFloatWordCount = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        preparedBundleIndex.toString
      )
    )
    invalidPreparedBundleFloatWordCount.exitCode mustBe 1
    invalidPreparedBundleFloatWordCount.output must include("stream.word_count must be an integer")
    invalidPreparedBundleFloatWordCount.output must not include "Traceback"
    Files.writeString(preparedBundleIndex, originalPreparedIndex)

    Files.writeString(
      preparedBundleIndex,
      originalPreparedIndex.replaceFirst("\"block_count\": 1", "\"block_count\": \"bad\"")
    )
    val invalidPreparedBundleFrameBlockCount = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        preparedBundleIndex.toString
      )
    )
    invalidPreparedBundleFrameBlockCount.exitCode mustBe 1
    invalidPreparedBundleFrameBlockCount.output must include("frame.block_count must be an integer")
    invalidPreparedBundleFrameBlockCount.output must not include "Traceback"
    Files.writeString(preparedBundleIndex, originalPreparedIndex)

    Files.writeString(
      preparedBundleIndex,
      originalPreparedIndex.replaceFirst("\"stage_bits\": 8", "\"stage_bits\": \"bad\"")
    )
    val invalidPreparedBundleTraceStageBits = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        preparedBundleIndex.toString
      )
    )
    invalidPreparedBundleTraceStageBits.exitCode mustBe 1
    invalidPreparedBundleTraceStageBits.output must include("trace.stage_bits must be an integer")
    invalidPreparedBundleTraceStageBits.output must not include "Traceback"
    Files.writeString(preparedBundleIndex, originalPreparedIndex)

    Files.writeString(
      preparedBundleIndex,
      originalPreparedIndex.replaceFirst("\"flags\": 28", "\"flags\": \"bad\"")
    )
    val invalidPreparedBundleRegisterMapFlags = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        preparedBundleIndex.toString
      )
    )
    invalidPreparedBundleRegisterMapFlags.exitCode mustBe 1
    invalidPreparedBundleRegisterMapFlags.output must include(
      "axi_lite.register_map.flags must be an integer"
    )
    invalidPreparedBundleRegisterMapFlags.output must not include "Traceback"
    Files.writeString(preparedBundleIndex, originalPreparedIndex)

    Files.writeString(
      preparedBundleIndex,
      originalPreparedIndex.replaceFirst(
        """(?s),\n  "target": \{\n.*?\n  \}\n""",
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
      originalPreparedIndex.replaceFirst(
        """(?s),\n  "frame": \{\n.*?\n  \}""",
        ""
      )
    )
    val legacyPreparedBundleFrameOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        preparedBundleIndex.toString
      )
    )
    legacyPreparedBundleFrameOutput must include("validated host bundle prepared with 201 stream words")
    Files.writeString(preparedBundleIndex, originalPreparedIndex)

    Files.writeString(
      preparedBundleIndex,
      originalPreparedIndex.replaceFirst(
        """(?s),\n  "trace": \{\n.*?\n  \}""",
        ""
      )
    )
    val legacyPreparedBundleTraceOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        preparedBundleIndex.toString
      )
    )
    legacyPreparedBundleTraceOutput must include("validated host bundle prepared with 201 stream words")
    Files.writeString(preparedBundleIndex, originalPreparedIndex)

    Files.writeString(
      preparedBundleIndex,
      originalPreparedIndex.replaceFirst(
        """(?s),\n  "axi_lite": \{\n    "addr_bits": 8,\n    "data_bits": 32,\n    "register_map": \{\n.*?\n    \},\n    "strb_bits": 4,\n    "write_count": 9\n  \}""",
        ""
      )
    )
    val legacyPreparedBundleAxiLiteOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        preparedBundleIndex.toString
      )
    )
    legacyPreparedBundleAxiLiteOutput must include("validated host bundle prepared with 201 stream words")
    Files.writeString(preparedBundleIndex, originalPreparedIndex)

    Files.writeString(
      preparedBundleIndex,
      originalPreparedIndex.replace("\"data_bits\": 32", "\"data_bits\": 64")
    )
    val invalidPreparedAxiLite = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        preparedBundleIndex.toString
      )
    )
    invalidPreparedAxiLite.exitCode mustBe 1
    invalidPreparedAxiLite.output must include("axi_lite metadata does not match RTL control plane")
    Files.writeString(preparedBundleIndex, originalPreparedIndex)

    Files.writeString(
      preparedBundleIndex,
      originalPreparedIndex.replace("\"flags\": 28", "\"flags\": 32")
    )
    val invalidPreparedRegisterMap = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        preparedBundleIndex.toString
      )
    )
    invalidPreparedRegisterMap.exitCode mustBe 1
    invalidPreparedRegisterMap.output must include("axi_lite metadata does not match RTL control plane")
    Files.writeString(preparedBundleIndex, originalPreparedIndex)

    Files.writeString(
      preparedBundleIndex,
      originalPreparedIndex.replace("\"tkeep_mask\": 2047", "\"tkeep_mask\": 1023")
    )
    val invalidPreparedTrace = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        preparedBundleIndex.toString
      )
    )
    invalidPreparedTrace.exitCode mustBe 1
    invalidPreparedTrace.output must include("trace metadata does not match RTL trace packing")
    Files.writeString(preparedBundleIndex, originalPreparedIndex)

    Files.writeString(
      preparedBundleIndex,
      originalPreparedIndex.replace("\"block_count\": 1", "\"block_count\": 2")
    )
    val invalidPreparedFrame = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        preparedBundleIndex.toString
      )
    )
    invalidPreparedFrame.exitCode mustBe 1
    invalidPreparedFrame.output must include("frame metadata does not match source manifest")
    Files.writeString(preparedBundleIndex, originalPreparedIndex)

    Files.writeString(
      preparedBundleIndex,
      originalPreparedIndex.replaceFirst("""(?m)^    "coefficient_fraction_bits": 16,\n""", "")
    )
    val legacyPreparedBundleCoefficientScaleOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        preparedBundleIndex.toString
      )
    )
    legacyPreparedBundleCoefficientScaleOutput must include("validated host bundle prepared with 201 stream words")
    Files.writeString(preparedBundleIndex, originalPreparedIndex)

    Files.writeString(
      preparedBundleIndex,
      originalPreparedIndex.replaceFirst("""(?m)^    "input_keep_mask": 15,\n""", "")
    )
    val legacyPreparedBundleKeepMaskOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        preparedBundleIndex.toString
      )
    )
    legacyPreparedBundleKeepMaskOutput must include("validated host bundle prepared with 201 stream words")
    Files.writeString(preparedBundleIndex, originalPreparedIndex)

    Files.writeString(
      preparedBundleIndex,
      originalPreparedIndex.replace("\"input_keep_mask\": 15", "\"input_keep_mask\": 7")
    )
    val invalidPreparedKeepMask = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        preparedBundleIndex.toString
      )
    )
    invalidPreparedKeepMask.exitCode mustBe 1
    invalidPreparedKeepMask.output must include("stream.input_keep_mask does not match source manifest")
    Files.writeString(preparedBundleIndex, originalPreparedIndex)

    Files.writeString(
      preparedBundleIndex,
      originalPreparedIndex.replace("\"coefficient_fraction_bits\": 16", "\"coefficient_fraction_bits\": 12")
    )
    val invalidPreparedCoefficientScale = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        preparedBundleIndex.toString
      )
    )
    invalidPreparedCoefficientScale.exitCode mustBe 1
    invalidPreparedCoefficientScale.output must include("stream.coefficient_fraction_bits does not match source manifest")
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

    val preparedBundleStreamCsv = hostBundleDir.resolve("prepared-stream.csv")
    val originalPreparedBundleStreamCsv = Files.readString(preparedBundleStreamCsv)
    Files.writeString(preparedBundleStreamCsv, originalPreparedBundleStreamCsv.replaceFirst("1234,0", "4321,0"))
    val invalidPreparedBundleStreamData = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        hostBundleDir.resolve("prepared-bundle.json").toString
      )
    )
    invalidPreparedBundleStreamData.exitCode mustBe 1
    invalidPreparedBundleStreamData.output must include(
      "stream row 1 data 4321 does not match source prepared JSON value 1234"
    )
    Files.writeString(preparedBundleStreamCsv, originalPreparedBundleStreamCsv)

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

  "hjxl_prepared_token_inputs.py validates prepared-token fixture format and coordinates" in {
    val temp = Files.createTempDirectory("hjxl-prepared-token-inputs-")
    val fixture = temp.resolve("prepared-token-inputs.json")
    val dcCsv = temp.resolve("prepared-dc.csv")
    val acCsv = temp.resolve("prepared-ac.csv")
    Files.writeString(fixture, preparedTokenFixtureJson)

    val output = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_prepared_token_inputs.py",
        "--prepared-json",
        fixture.toString,
        "--dc-csv",
        dcCsv.toString,
        "--ac-csv",
        acCsv.toString
      )
    )
    output mustBe ""
    Files.readString(dcCsv).replace("\r\n", "\n") mustBe
      "ordinal,value\n0,11\n1,-2\n2,7\n"
    val acRows = Files.readString(acCsv).replace("\r\n", "\n").trim.split("\n").toVector
    acRows.length mustBe 4
    acRows.head mustBe "block,channel,nonzeros,coefficients"
    acRows.tail.map(_.split(",", -1).take(3).mkString(",")) mustBe Seq(
      "0,0,0",
      "0,1,0",
      "0,2,0"
    )

    val originalFixtureJson = Files.readString(fixture)
    Files.writeString(fixture, originalFixtureJson.replace("\"x_tiles\": 1", "\"x_tiles\": 2"))
    val invalidTileGrid = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_token_inputs.py",
        "--prepared-json",
        fixture.toString,
        "--dc-csv",
        dcCsv.toString
      )
    )
    invalidTileGrid.exitCode mustBe 1
    invalidTileGrid.output must include("image.x_tiles does not match declared dimensions")

    Files.writeString(fixture, originalFixtureJson.replace("\"fixed_ytox\": -7", "\"fixed_ytox\": -129"))
    val invalidFixedYtox = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_token_inputs.py",
        "--prepared-json",
        fixture.toString,
        "--dc-csv",
        dcCsv.toString
      )
    )
    invalidFixedYtox.exitCode mustBe 1
    invalidFixedYtox.output must include("fixed_ytox outside signed 8-bit range")
    invalidFixedYtox.output must not include "Traceback"

    Files.writeString(fixture, originalFixtureJson.replace("\"fixed_raw_quant\": 200", "\"fixed_raw_quant\": 0"))
    val invalidFixedRawQuant = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_token_inputs.py",
        "--prepared-json",
        fixture.toString,
        "--dc-csv",
        dcCsv.toString
      )
    )
    invalidFixedRawQuant.exitCode mustBe 1
    invalidFixedRawQuant.output must include("fixed_raw_quant outside 1..255 range")
    invalidFixedRawQuant.output must not include "Traceback"

    Files.writeString(
      fixture,
      originalFixtureJson.replace(
        "\"format\": \"hjxl.fixed_dct_only_prepared_token_inputs.v1\"",
        "\"format\": \"hjxl.fixed_dct_only_prepared_token_inputs.v0\""
      )
    )
    val invalidFormat = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_token_inputs.py",
        "--prepared-json",
        fixture.toString,
        "--dc-csv",
        dcCsv.toString
      )
    )
    invalidFormat.exitCode mustBe 1
    invalidFormat.output must include("expected format 'hjxl.fixed_dct_only_prepared_token_inputs.v1'")

    Files.writeString(
      fixture,
      originalFixtureJson.replace("\"xsize\": 8", "\"xsize\": null")
    )
    val invalidImageInteger = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_token_inputs.py",
        "--prepared-json",
        fixture.toString,
        "--dc-csv",
        dcCsv.toString
      )
    )
    invalidImageInteger.exitCode mustBe 1
    invalidImageInteger.output must include("image.xsize must be an integer")
    invalidImageInteger.output must not include "Traceback"

    Files.writeString(
      fixture,
      originalFixtureJson.replace("\"xsize\": 8", "\"xsize\": 8.0")
    )
    val invalidImageFloat = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_token_inputs.py",
        "--prepared-json",
        fixture.toString,
        "--dc-csv",
        dcCsv.toString
      )
    )
    invalidImageFloat.exitCode mustBe 1
    invalidImageFloat.output must include("image.xsize must be an integer")
    invalidImageFloat.output must not include "Traceback"

    Files.writeString(
      fixture,
      originalFixtureJson.replace(
        "\"dc_samples\": [11, -2, 7]",
        "\"dc_samples\": [11, null, 7]"
      )
    )
    val invalidDcSample = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_token_inputs.py",
        "--prepared-json",
        fixture.toString,
        "--dc-csv",
        dcCsv.toString
      )
    )
    invalidDcSample.exitCode mustBe 1
    invalidDcSample.output must include("dc_samples[1] must be an integer")
    invalidDcSample.output must not include "Traceback"

    Files.writeString(
      fixture,
      originalFixtureJson.replace(
        "\"dc_samples\": [11, -2, 7]",
        "\"dc_samples\": [11, 2147483648, 7]"
      )
    )
    val outOfRangeDcSample = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_token_inputs.py",
        "--prepared-json",
        fixture.toString,
        "--dc-csv",
        dcCsv.toString
      )
    )
    outOfRangeDcSample.exitCode mustBe 1
    outOfRangeDcSample.output must include("dc_samples[1] outside signed 32-bit range")
    outOfRangeDcSample.output must not include "Traceback"

    Files.writeString(
      fixture,
      originalFixtureJson.replace("\"quantized\": [[0,0", "\"quantized\": [[1.5,0")
    )
    val invalidCoefficientInteger = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_token_inputs.py",
        "--prepared-json",
        fixture.toString,
        "--dc-csv",
        dcCsv.toString
      )
    )
    invalidCoefficientInteger.exitCode mustBe 1
    invalidCoefficientInteger.output must include("AC block 0 channel 0 coefficient 0 must be an integer")
    invalidCoefficientInteger.output must not include "Traceback"

    Files.writeString(
      fixture,
      originalFixtureJson.replace("\"quantized\": [[0,0", "\"quantized\": [[-2147483649,0")
    )
    val outOfRangeCoefficient = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_token_inputs.py",
        "--prepared-json",
        fixture.toString,
        "--dc-csv",
        dcCsv.toString
      )
    )
    outOfRangeCoefficient.exitCode mustBe 1
    outOfRangeCoefficient.output must include(
      "AC block 0 channel 0 coefficient 0 outside signed 32-bit range"
    )
    outOfRangeCoefficient.output must not include "Traceback"

    Files.writeString(fixture, originalFixtureJson.replace("\"block_x\": 0", "\"block_x\": 1"))
    val invalidBlockCoordinate = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_token_inputs.py",
        "--prepared-json",
        fixture.toString,
        "--dc-csv",
        dcCsv.toString
      )
    )
    invalidBlockCoordinate.exitCode mustBe 1
    invalidBlockCoordinate.output must include("AC block 0 block_x does not match raster order")

    Files.writeString(fixture, originalFixtureJson.replace("\"tile_x\": 0", "\"tile_x\": 1"))
    val invalidTileCoordinate = runCommand(
      Seq(
        "python3",
        "tools/hjxl_prepared_token_inputs.py",
        "--prepared-json",
        fixture.toString,
        "--dc-csv",
        dcCsv.toString
      )
    )
    invalidTileCoordinate.exitCode mustBe 1
    invalidTileCoordinate.output must include("AC block 0 tile_x does not match 64x64 tile grid")
  }

  "hjxl_quant_trace_to_prepared_tokens.py emits prepared-token JSON fixtures" in {
    val temp = Files.createTempDirectory("hjxl-quant-trace-prepared-json-")
    val traceCsv = temp.resolve("quant-trace.csv")
    val dcCsv = temp.resolve("prepared-dc.csv")
    val acCsv = temp.resolve("prepared-ac.csv")
    val preparedJson = temp.resolve("prepared-token-inputs.json")
    val roundtripDcCsv = temp.resolve("roundtrip-dc.csv")
    val roundtripAcCsv = temp.resolve("roundtrip-ac.csv")
    val invalidTraceCsv = temp.resolve("invalid-quant-trace.csv")
    def zeroCoefficients(base: Int): Seq[String] = (0 until 64).map { index =>
      s"${TraceStage.QuantizedAc},0,${base + index},0"
    }
    val quantizedAcRows =
      zeroCoefficients(0) ++ zeroCoefficients(64) ++ zeroCoefficients(128)
    val traceText =
      (
        Seq(
          "stage,group,index,value",
          s"${TraceStage.QuantDc},0,0,11",
          s"${TraceStage.QuantDc},0,1,-2",
          s"${TraceStage.QuantDc},0,2,7"
        ) ++
          quantizedAcRows ++
          Seq(
            s"${TraceStage.NumNonzeros},0,0,0",
            s"${TraceStage.NumNonzeros},0,1,0",
            s"${TraceStage.NumNonzeros},0,2,0"
          )
      ).mkString("", "\n", "\n")
    Files.writeString(traceCsv, traceText)

    val output = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_quant_trace_to_prepared_tokens.py",
        "--trace-csv",
        traceCsv.toString,
        "--width",
        "8",
        "--height",
        "8",
        "--dc-csv",
        dcCsv.toString,
        "--ac-csv",
        acCsv.toString,
        "--prepared-json",
        preparedJson.toString
      )
    )
    output mustBe ""
    Files.readString(dcCsv).replace("\r\n", "\n") mustBe
      "ordinal,value\n0,-2\n1,11\n2,7\n"
    val prepared = Files.readString(preparedJson).replace("\r\n", "\n")
    prepared must include("\"format\": \"hjxl.fixed_dct_only_prepared_token_inputs.v1\"")
    prepared must include("\"x_tiles\": 1")
    prepared must include("\"y_tiles\": 1")
    prepared must include("\"block_x\": 0")
    prepared must include("\"tile_x\": 0")

    val roundtripOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_prepared_token_inputs.py",
        "--prepared-json",
        preparedJson.toString,
        "--dc-csv",
        roundtripDcCsv.toString,
        "--ac-csv",
        roundtripAcCsv.toString
      )
    )
    roundtripOutput mustBe ""
    Files.readString(roundtripDcCsv).replace("\r\n", "\n") mustBe
      Files.readString(dcCsv).replace("\r\n", "\n")
    Files.readString(roundtripAcCsv).replace("\r\n", "\n") mustBe
      Files.readString(acCsv).replace("\r\n", "\n")

    Files.writeString(invalidTraceCsv, "stage,group,index,value\nBogusStage,0,0,0\n")
    val invalidStage = runCommand(
      Seq(
        "python3",
        "tools/hjxl_quant_trace_to_prepared_tokens.py",
        "--trace-csv",
        invalidTraceCsv.toString,
        "--width",
        "8",
        "--height",
        "8",
        "--dc-csv",
        dcCsv.toString
      )
    )
    invalidStage.exitCode mustBe 1
    invalidStage.output must include("stage must be a trace stage name or integer")
    invalidStage.output must not include "Traceback"

    Files.writeString(
      invalidTraceCsv,
      s"stage,group,index,value\n${TraceStage.QuantDc},bad,0,0\n"
    )
    val invalidGroup = runCommand(
      Seq(
        "python3",
        "tools/hjxl_quant_trace_to_prepared_tokens.py",
        "--trace-csv",
        invalidTraceCsv.toString,
        "--width",
        "8",
        "--height",
        "8",
        "--dc-csv",
        dcCsv.toString
      )
    )
    invalidGroup.exitCode mustBe 1
    invalidGroup.output must include("group must be an integer")
    invalidGroup.output must not include "Traceback"

    Files.writeString(
      invalidTraceCsv,
      traceText.replace(
        s"${TraceStage.QuantDc},0,0,11",
        s"${TraceStage.QuantDc},0,0,2147483648"
      )
    )
    val outOfRangeDc = runCommand(
      Seq(
        "python3",
        "tools/hjxl_quant_trace_to_prepared_tokens.py",
        "--trace-csv",
        invalidTraceCsv.toString,
        "--width",
        "8",
        "--height",
        "8",
        "--dc-csv",
        dcCsv.toString
      )
    )
    outOfRangeDc.exitCode mustBe 1
    outOfRangeDc.output must include(
      "QuantDc block 0 channel 0 value 2147483648 outside signed 32-bit range"
    )
    outOfRangeDc.output must not include "Traceback"

    Files.writeString(
      invalidTraceCsv,
      traceText.replace(
        s"${TraceStage.QuantizedAc},0,0,0",
        s"${TraceStage.QuantizedAc},0,0,-2147483649"
      )
    )
    val outOfRangeAc = runCommand(
      Seq(
        "python3",
        "tools/hjxl_quant_trace_to_prepared_tokens.py",
        "--trace-csv",
        invalidTraceCsv.toString,
        "--width",
        "8",
        "--height",
        "8",
        "--dc-csv",
        dcCsv.toString
      )
    )
    outOfRangeAc.exitCode mustBe 1
    outOfRangeAc.output must include(
      "QuantizedAc block 0 channel 0 coefficient 0 value -2147483649 outside signed 32-bit range"
    )
    outOfRangeAc.output must not include "Traceback"

    Files.writeString(invalidTraceCsv, traceText + s"${TraceStage.QuantDc},0,0,99\n")
    val duplicateDc = runCommand(
      Seq(
        "python3",
        "tools/hjxl_quant_trace_to_prepared_tokens.py",
        "--trace-csv",
        invalidTraceCsv.toString,
        "--width",
        "8",
        "--height",
        "8",
        "--dc-csv",
        dcCsv.toString
      )
    )
    duplicateDc.exitCode mustBe 1
    duplicateDc.output must include("duplicate QuantDc block 0 channel 0")
    duplicateDc.output must not include "Traceback"

    Files.writeString(
      invalidTraceCsv,
      traceText.replace(
        s"${TraceStage.NumNonzeros},0,0,0",
        s"${TraceStage.NumNonzeros},0,0,1"
      )
    )
    val inconsistentNonzeros = runCommand(
      Seq(
        "python3",
        "tools/hjxl_quant_trace_to_prepared_tokens.py",
        "--trace-csv",
        invalidTraceCsv.toString,
        "--width",
        "8",
        "--height",
        "8",
        "--dc-csv",
        dcCsv.toString
      )
    )
    inconsistentNonzeros.exitCode mustBe 1
    inconsistentNonzeros.output must include(
      "block 0 channel 0 declares 1 nonzeros but coefficients contain 0"
    )
    inconsistentNonzeros.output must not include "Traceback"

    Files.writeString(
      invalidTraceCsv,
      traceText.replace(s"${TraceStage.QuantizedAc},0,63,0\n", "")
    )
    val missingCoefficient = runCommand(
      Seq(
        "python3",
        "tools/hjxl_quant_trace_to_prepared_tokens.py",
        "--trace-csv",
        invalidTraceCsv.toString,
        "--width",
        "8",
        "--height",
        "8",
        "--dc-csv",
        dcCsv.toString
      )
    )
    missingCoefficient.exitCode mustBe 1
    missingCoefficient.output must include("missing QuantizedAc block 0 channel 0 coefficients [63]")
    missingCoefficient.output must not include "Traceback"
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
        "7",
        "--fixed-ytox",
        "-7",
        "--fixed-ytob",
        "11"
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
      "28,519,15",
      "32,4294967289,15",
      "36,11,15"
    )

    val rgbManifest = Files.readString(manifestJson).replace("\r\n", "\n")
    rgbManifest must include("\"fixed_ytox\": -7")
    rgbManifest must include("\"fixed_ytob\": 11")

    val outOfRangeRgbFixedRawQuant = runCommand(
      Seq(
        "python3",
        "tools/hjxl_rgb_stream.py",
        "--pfm",
        pfm.toString,
        "--stream-csv",
        temp.resolve("bad-rgb-raw-quant-stream.csv").toString,
        "--fixed-raw-quant",
        "256"
      )
    )
    outOfRangeRgbFixedRawQuant.exitCode mustBe 1
    outOfRangeRgbFixedRawQuant.output must include("--fixed-raw-quant must fit in uint8")
    outOfRangeRgbFixedRawQuant.output must not include "Traceback"

    val outOfRangeRgbFixedYtox = runCommand(
      Seq(
        "python3",
        "tools/hjxl_rgb_stream.py",
        "--pfm",
        pfm.toString,
        "--stream-csv",
        temp.resolve("bad-rgb-ytox-stream.csv").toString,
        "--fixed-ytox",
        "-129"
      )
    )
    outOfRangeRgbFixedYtox.exitCode mustBe 1
    outOfRangeRgbFixedYtox.output must include("--fixed-ytox must fit in signed 8-bit")
    outOfRangeRgbFixedYtox.output must not include "Traceback"

    val outOfRangeRgbInvQac = runCommand(
      Seq(
        "python3",
        "tools/hjxl_rgb_stream.py",
        "--pfm",
        pfm.toString,
        "--stream-csv",
        temp.resolve("bad-rgb-inv-qac-stream.csv").toString,
        "--fixed-inv-qac-q16",
        "4294967296"
      )
    )
    outOfRangeRgbInvQac.exitCode mustBe 1
    outOfRangeRgbInvQac.output must include("--fixed-inv-qac-q16 must fit in uint32")
    outOfRangeRgbInvQac.output must not include "Traceback"

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
    manifest must include("\"trace_route\"")
    manifest must include("\"name\": \"all\"")
    manifest must include("\"stage\": null")
    manifest must include("\"focused\": false")

    val validateOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_rgb_stream.py",
        "--validate-manifest",
        manifestJson.toString
      )
    )
    validateOutput must include(s"validated $manifestJson")

    val originalRgbManifestJson = Files.readString(manifestJson)
    Files.writeString(manifestJson, originalRgbManifestJson.replace("\"width\": 2", "\"width\": 2.5"))
    val invalidRgbManifestFloatWidth = runCommand(
      Seq(
        "python3",
        "tools/hjxl_rgb_stream.py",
        "--validate-manifest",
        manifestJson.toString
      )
    )
    invalidRgbManifestFloatWidth.exitCode mustBe 1
    invalidRgbManifestFloatWidth.output must include("image.width must be an integer")
    invalidRgbManifestFloatWidth.output must not include "Traceback"
    Files.writeString(manifestJson, originalRgbManifestJson)

    val originalRgbStreamCsv = Files.readString(streamCsv)
    Files.writeString(streamCsv, originalRgbStreamCsv.replaceFirst("""(?m)^[0-9]+,0$""", "bad,0"))
    val invalidRgbStreamData = runCommand(
      Seq(
        "python3",
        "tools/hjxl_rgb_stream.py",
        "--validate-manifest",
        manifestJson.toString
      )
    )
    invalidRgbStreamData.exitCode mustBe 1
    invalidRgbStreamData.output must include("data must be an integer")
    invalidRgbStreamData.output must not include "Traceback"

    Files.writeString(
      streamCsv,
      originalRgbStreamCsv.replaceFirst("""(?m)^([0-9]+),0$""", "$1,maybe")
    )
    val invalidRgbStreamLast = runCommand(
      Seq(
        "python3",
        "tools/hjxl_rgb_stream.py",
        "--validate-manifest",
        manifestJson.toString
      )
    )
    invalidRgbStreamLast.exitCode mustBe 1
    invalidRgbStreamLast.output must include("last must be boolean")
    invalidRgbStreamLast.output must not include "Traceback"
    Files.writeString(streamCsv, originalRgbStreamCsv)

    val originalRgbControlCsv = Files.readString(controlCsv)
    Files.writeString(
      controlCsv,
      originalRgbControlCsv.replaceFirst("""(?m)^4,2,15$""", "bad,2,15")
    )
    val invalidRgbControlAddress = runCommand(
      Seq(
        "python3",
        "tools/hjxl_rgb_stream.py",
        "--validate-manifest",
        manifestJson.toString
      )
    )
    invalidRgbControlAddress.exitCode mustBe 1
    invalidRgbControlAddress.output must include("address must be an integer")
    invalidRgbControlAddress.output must not include "Traceback"

    Files.writeString(
      controlCsv,
      originalRgbControlCsv.replaceFirst("""(?m)^4,2,15$""", "4,bad,15")
    )
    val invalidRgbControlData = runCommand(
      Seq(
        "python3",
        "tools/hjxl_rgb_stream.py",
        "--validate-manifest",
        manifestJson.toString
      )
    )
    invalidRgbControlData.exitCode mustBe 1
    invalidRgbControlData.output must include("data must be an integer")
    invalidRgbControlData.output must not include "Traceback"

    Files.writeString(
      controlCsv,
      originalRgbControlCsv.replaceFirst("""(?m)^4,2,15$""", "4,2,bad")
    )
    val invalidRgbControlStrobe = runCommand(
      Seq(
        "python3",
        "tools/hjxl_rgb_stream.py",
        "--validate-manifest",
        manifestJson.toString
      )
    )
    invalidRgbControlStrobe.exitCode mustBe 1
    invalidRgbControlStrobe.output must include("strb must be an integer")
    invalidRgbControlStrobe.output must not include "Traceback"
    Files.writeString(controlCsv, originalRgbControlCsv)

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
    headerText must include("#define HJXL_RGB_TRACE_ROUTE_NAME \"all\"")
    headerText must include("#define HJXL_RGB_TRACE_ROUTE_STAGE -1")
    headerText must include("#define HJXL_RGB_TRACE_ROUTE_FOCUSED 0u")
    headerText must include("} hjxl_rgb_stage_trace_t;")
    headerText must include("static inline hjxl_rgb_stage_trace_t hjxl_rgb_decode_trace_word")
    headerText must include("static inline int32_t hjxl_rgb_sign_extend_trace_value")
    headerText must include("#define HJXL_RGB_STREAM_WORD_COUNT 4u")
    headerText must include("#define HJXL_RGB_FRAME_XSIZE 2u")
    headerText must include("#define HJXL_RGB_FRAME_YSIZE 2u")
    headerText must include("#define HJXL_RGB_FRAME_X_BLOCKS 1u")
    headerText must include("#define HJXL_RGB_FRAME_Y_BLOCKS 1u")
    headerText must include("#define HJXL_RGB_FRAME_BLOCK_COUNT 1u")
    headerText must include("#define HJXL_RGB_FRAME_X_TILES 1u")
    headerText must include("#define HJXL_RGB_FRAME_Y_TILES 1u")
    headerText must include("#define HJXL_RGB_FRAME_TILE_COUNT 1u")
    headerText must include("#define HJXL_RGB_INPUT_DATA_BITS 48u")
    headerText must include("#define HJXL_RGB_STREAM_WORD_BYTES 6u")
    headerText must include("#define HJXL_RGB_INPUT_TKEEP_MASK 0x0000003fu")
    headerText must include("#define HJXL_RGB_INPUT_TKEEP_ENFORCED 0u")
    headerText must include("#define HJXL_RGB_STREAM_BYTE_COUNT 24u")
    headerText must include("#define HJXL_RGB_AXI_LITE_ADDR_BITS 8u")
    headerText must include("#define HJXL_RGB_AXI_LITE_DATA_BITS 32u")
    headerText must include("#define HJXL_RGB_AXI_LITE_STRB_BITS 4u")
    headerText must include("#define HJXL_RGB_TRACE_STAGE_SHIFT 0u")
    headerText must include("#define HJXL_RGB_TRACE_GROUP_SHIFT 8u")
    headerText must include("#define HJXL_RGB_TRACE_INDEX_SHIFT 24u")
    headerText must include("#define HJXL_RGB_TRACE_VALUE_SHIFT 56u")
    headerText must include("#define HJXL_RGB_TRACE_STAGE_BYTE_OFFSET 0u")
    headerText must include("#define HJXL_RGB_TRACE_GROUP_BYTE_OFFSET 1u")
    headerText must include("#define HJXL_RGB_TRACE_INDEX_BYTE_OFFSET 3u")
    headerText must include("#define HJXL_RGB_TRACE_VALUE_BYTE_OFFSET 7u")
    headerText must include("#define HJXL_RGB_TRACE_STAGE_MASK 0x000000ffu")
    headerText must include("#define HJXL_RGB_TRACE_GROUP_MASK 0x0000ffffu")
    headerText must include("#define HJXL_RGB_TRACE_INDEX_MASK 0xffffffffu")
    headerText must include("#define HJXL_RGB_TRACE_VALUE_MASK 0xffffffffu")
    headerText must include("#define HJXL_RGB_TRACE_PACKED_BITS 88u")
    headerText must include("#define HJXL_RGB_TRACE_PACKED_BYTES 11u")
    headerText must include("#define HJXL_RGB_TRACE_TKEEP_MASK 0x000007ffu")
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
      "HJXL_RGB_INPUT_TKEEP_MASK == ((1u << HJXL_RGB_STREAM_WORD_BYTES) - 1u)"
    )
    headerText must include(
      "HJXL_RGB_AXI_LITE_STRB_BITS == HJXL_RGB_AXI_LITE_DATA_BITS / 8u"
    )
    headerText must include(
      "HJXL_RGB_TRACE_VALUE_SHIFT == HJXL_RGB_TRACE_INDEX_SHIFT + HJXL_RGB_TRACE_INDEX_BITS"
    )
    headerText must include(
      "HJXL_RGB_TRACE_PACKED_BITS == HJXL_RGB_TRACE_VALUE_SHIFT + HJXL_RGB_TRACE_VALUE_BITS"
    )
    headerText must include(
      "HJXL_RGB_TRACE_VALUE_BITS > 0u && HJXL_RGB_TRACE_VALUE_BITS <= 32u"
    )
    headerText must include(
      "HJXL_RGB_TRACE_STAGE_SHIFT % 8u == 0u"
    )
    headerText must include(
      "HJXL_RGB_TRACE_VALUE_BYTE_OFFSET == HJXL_RGB_TRACE_VALUE_SHIFT / 8u"
    )
    headerText must include(
      "sizeof(HJXL_RGB_AXI_LITE_WRITES) / sizeof(HJXL_RGB_AXI_LITE_WRITES[0]) == HJXL_RGB_AXI_LITE_WRITE_COUNT"
    )
    headerText must include(
      "HJXL_RGB_TRACE_PACKED_BYTES == (HJXL_RGB_TRACE_PACKED_BITS + 7u) / 8u"
    )
    headerText must include(
      "HJXL_RGB_TRACE_TKEEP_MASK == ((1u << HJXL_RGB_TRACE_PACKED_BYTES) - 1u)"
    )
    headerText must include(
      "sizeof(HJXL_RGB_SUPPORTED_DISTANCE_Q8) / sizeof(HJXL_RGB_SUPPORTED_DISTANCE_Q8[0]) == " +
        "HJXL_RGB_SUPPORTED_DISTANCE_Q8_COUNT"
    )
    compileGeneratedHeaderSmoke(header, "HJXL_RGB")

    Files.writeString(manifestJson, originalRgbManifestJson.replace("\"pixel_bits\": 16", "\"pixel_bits\": \"bad\""))
    val invalidHeaderPixelBits = runCommand(
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
    invalidHeaderPixelBits.exitCode mustBe 1
    invalidHeaderPixelBits.output must include("stream.pixel_bits must be an integer")
    invalidHeaderPixelBits.output must not include "Traceback"
    Files.writeString(manifestJson, originalRgbManifestJson)

    Files.writeString(manifestJson, originalRgbManifestJson.replace("\"pixel_bits\": 16", "\"pixel_bits\": 16.5"))
    val invalidHeaderFloatPixelBits = runCommand(
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
    invalidHeaderFloatPixelBits.exitCode mustBe 1
    invalidHeaderFloatPixelBits.output must include("stream.pixel_bits must be an integer")
    invalidHeaderFloatPixelBits.output must not include "Traceback"
    Files.writeString(manifestJson, originalRgbManifestJson)

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

    Files.writeString(manifestJson, originalRgbManifestJson.replace("\"pixel_bits\": 16", "\"pixel_bits\": \"bad\""))
    val invalidRgbBufferPixelBits = runCommand(
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
    invalidRgbBufferPixelBits.exitCode mustBe 1
    invalidRgbBufferPixelBits.output must include("stream.pixel_bits must be an integer")
    invalidRgbBufferPixelBits.output must not include "Traceback"
    Files.writeString(manifestJson, originalRgbManifestJson)

    Files.writeString(manifestJson, originalRgbManifestJson.replace("\"pixel_bits\": 16", "\"pixel_bits\": 16.5"))
    val invalidRgbBufferFloatPixelBits = runCommand(
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
    invalidRgbBufferFloatPixelBits.exitCode mustBe 1
    invalidRgbBufferFloatPixelBits.output must include("stream.pixel_bits must be an integer")
    invalidRgbBufferFloatPixelBits.output must not include "Traceback"
    Files.writeString(manifestJson, originalRgbManifestJson)

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
    Files.readString(hostBundleDir.resolve("rgb.h")).replace("\r\n", "\n") must include(
      "#define HJXL_RGB_BUNDLE_FRAME_TILE_COUNT 1u"
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
      "\"axi_lite_write_count\": 9"
    )
    Files.readString(hostBundleDir.resolve("rgb-bundle.json")).replace("\r\n", "\n") must include(
      "\"checksums\""
    )
    Files.readString(hostBundleDir.resolve("rgb-bundle.json")).replace("\r\n", "\n") must include(
      "\"byte_count\": 24"
    )
    Files.readString(hostBundleDir.resolve("rgb-bundle.json")).replace("\r\n", "\n") must include(
      "\"frame\""
    )
    Files.readString(hostBundleDir.resolve("rgb-bundle.json")).replace("\r\n", "\n") must include(
      "\"xsize\": 2"
    )
    Files.readString(hostBundleDir.resolve("rgb-bundle.json")).replace("\r\n", "\n") must include(
      "\"ysize\": 2"
    )
    Files.readString(hostBundleDir.resolve("rgb-bundle.json")).replace("\r\n", "\n") must include(
      "\"padded_xsize\": 8"
    )
    Files.readString(hostBundleDir.resolve("rgb-bundle.json")).replace("\r\n", "\n") must include(
      "\"padded_ysize\": 8"
    )
    Files.readString(hostBundleDir.resolve("rgb-bundle.json")).replace("\r\n", "\n") must include(
      "\"block_count\": 1"
    )
    Files.readString(hostBundleDir.resolve("rgb-bundle.json")).replace("\r\n", "\n") must include(
      "\"x_tiles\": 1"
    )
    Files.readString(hostBundleDir.resolve("rgb-bundle.json")).replace("\r\n", "\n") must include(
      "\"y_tiles\": 1"
    )
    Files.readString(hostBundleDir.resolve("rgb-bundle.json")).replace("\r\n", "\n") must include(
      "\"tile_count\": 1"
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
    Files.writeString(
      rgbBundleControl,
      originalRgbBundleControl.replaceFirst("""(?m)^4,2,15$""", "4,2,bad")
    )
    val invalidRgbBundleControlStrobe = runCommand(
      Seq(
        "python3",
        "tools/hjxl_host_bundle.py",
        "--validate-bundle",
        hostBundleDir.resolve("rgb-bundle.json").toString
      )
    )
    invalidRgbBundleControlStrobe.exitCode mustBe 1
    invalidRgbBundleControlStrobe.output must include("strb must be an integer")
    invalidRgbBundleControlStrobe.output must not include "Traceback"
    Files.writeString(rgbBundleControl, originalRgbBundleControl)

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
    invalidRgbBundleControl.output must include("flags expected 519, got 518")
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
    noLastReplayPlan must include("\"frame\"")
    noLastReplayPlan must include("\"xsize\": 2")
    noLastReplayPlan must include("\"padded_xsize\": 8")

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
    val preflightSummaryJson = temp.resolve("preflight-summary.json")
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
        "--trace-route",
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
    Files.readString(replayPlan).replace("\r\n", "\n") must include("\"trace_route\"")
    Files.readString(replayPlan).replace("\r\n", "\n") must include("\"name\": \"ac-tokens\"")
    Files.readString(replayPlan).replace("\r\n", "\n") must include("\"stage\": 12")
    Files.readString(replayPlan).replace("\r\n", "\n") must include("\"focused\": true")

    val preflightOutput = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--preflight-only",
        "--expect-target-interface",
        "rgb_axi_stream",
        "--expect-target-variant",
        "rgb",
        "--expect-target-stream-shell",
        "HjxlAxiStreamCore",
        "--expect-target-controlled-shell",
        "HjxlAxiLiteStreamCore",
        "--expect-trace-route-name",
        "ac-tokens",
        "--expect-trace-route-stage",
        "12",
        "--expect-trace-route-focused",
        "true",
        "--expect-frame-xsize",
        width.toString,
        "--expect-frame-ysize",
        height.toString,
        "--expect-frame-block-count",
        "2",
        "--expect-frame-tile-count",
        "1",
        "--expect-status-unsupported-distance-bit",
        "3",
        "--expect-input-word-count",
        (width * height).toString,
        "--expect-input-byte-count",
        expectedInputByteCount.toString,
        "--expect-axi-lite-write-count",
        "9",
        "--expect-reg-flags",
        "0x1c",
        "--summary-json",
        preflightSummaryJson.toString
      )
    )
    preflightOutput must include("validated replay preflight capture: 16x8 distance=1")
    preflightOutput must include("stream_words=128")
    preflightOutput must include("axi_lite_writes=9")
    val preflightSummary = Files.readString(preflightSummaryJson).replace("\r\n", "\n")
    preflightSummary must include("\"format\": \"hjxl.replay_preflight_summary.v1\"")
    preflightSummary must include("\"artifacts\"")
    preflightSummary must include("\"source_manifest\"")
    preflightSummary must include("\"stream_bin\"")
    preflightSummary must include("\"stream_bin_resolved\"")
    preflightSummary must include("\"checksums\"")
    preflightSummary must include("\"sha256\"")
    preflightSummary must include("\"capture_defaults\"")
    preflightSummary must include("\"trace_route\"")
    preflightSummary must include("\"name\": \"ac-tokens\"")
    preflightSummary must include("\"stream_word_bytes\": 16")
    preflightSummary must include("\"register_map\"")
    preflightSummary must include("\"flags\": 28")

    val invalidPreflight = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--preflight-only",
        "--expect-reg-flags",
        "0x20"
      )
    )
    invalidPreflight.exitCode mustBe 1
    invalidPreflight.output must include("replay plan AXI-Lite register_map flags expected 32, got 28")
    invalidPreflight.output must not include "Traceback"

    val invalidTraceRoute = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--preflight-only",
        "--expect-trace-route-name",
        "raw-quant-field"
      )
    )
    invalidTraceRoute.exitCode mustBe 1
    invalidTraceRoute.output must include("replay plan trace_route name expected 'raw-quant-field', got 'ac-tokens'")
    invalidTraceRoute.output must not include "Traceback"

    val invalidTraceRouteFocused = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--preflight-only",
        "--expect-trace-route-name",
        "ac-tokens",
        "--expect-trace-route-focused",
        "false"
      )
    )
    invalidTraceRouteFocused.exitCode mustBe 1
    invalidTraceRouteFocused.output must include("replay plan trace_route focused expected False, got True")
    invalidTraceRouteFocused.output must not include "Traceback"

    val originalCaptureReplayPlan = Files.readString(replayPlan)
    Files.writeString(
      replayPlan,
      originalCaptureReplayPlan.replace(
        s""""byte_count": $expectedInputByteCount""",
        s""""byte_count": ${expectedInputByteCount}.0"""
      )
    )
    val invalidReplayPlanFloatByteCount = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--preflight-only"
      )
    )
    invalidReplayPlanFloatByteCount.exitCode mustBe 1
    invalidReplayPlanFloatByteCount.output must include("replay plan.stream.byte_count must be an integer")
    invalidReplayPlanFloatByteCount.output must not include "Traceback"
    Files.writeString(replayPlan, originalCaptureReplayPlan)

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
        "--expect-target-variant",
        "rgb",
        "--expect-target-stream-shell",
        "HjxlAxiStreamCore",
        "--expect-target-controlled-shell",
        "HjxlAxiLiteStreamCore",
        "--expect-target-no-kv260-top",
        "--expect-target-input-stream",
        "raster RGB pixels",
        "--expect-target-input-keep-enforced",
        "0",
        "--expect-frame-xsize",
        width.toString,
        "--expect-frame-ysize",
        height.toString,
        "--expect-frame-x-blocks",
        "2",
        "--expect-frame-y-blocks",
        "1",
        "--expect-frame-padded-xsize",
        "16",
        "--expect-frame-padded-ysize",
        "8",
        "--expect-frame-block-count",
        "2",
        "--expect-frame-x-tiles",
        "1",
        "--expect-frame-y-tiles",
        "1",
        "--expect-frame-tile-count",
        "1",
        "--expect-status-protocol-error-bit",
        "0",
        "--expect-status-busy-bit",
        "1",
        "--expect-status-overflow-bit",
        "2",
        "--expect-status-unsupported-distance-bit",
        "3",
        "--expect-control-clear-protocol-error-bit",
        "0",
        "--expect-trace-stage-bits",
        "8",
        "--expect-trace-group-bits",
        "16",
        "--expect-trace-index-bits",
        "32",
        "--expect-trace-value-bits",
        "32",
        "--expect-trace-stage-shift",
        "0",
        "--expect-trace-group-shift",
        "8",
        "--expect-trace-index-shift",
        "24",
        "--expect-trace-value-shift",
        "56",
        "--expect-trace-stage-byte-offset",
        "0",
        "--expect-trace-group-byte-offset",
        "1",
        "--expect-trace-index-byte-offset",
        "3",
        "--expect-trace-value-byte-offset",
        "7",
        "--expect-trace-stage-mask",
        "0xff",
        "--expect-trace-group-mask",
        "0xffff",
        "--expect-trace-index-mask",
        "0xffffffff",
        "--expect-trace-value-mask",
        "0xffffffff",
        "--expect-trace-packed-bits",
        "88",
        "--expect-trace-packed-bytes",
        "11",
        "--expect-trace-keep-mask",
        "0x7ff",
        "--expect-capture-word-bytes",
        "16",
        "--expect-input-word-count",
        (width * height).toString,
        "--expect-input-data-bits",
        "48",
        "--expect-input-word-bytes",
        "6",
        "--expect-input-keep-mask",
        "0x3f",
        "--expect-input-byte-count",
        expectedInputByteCount.toString,
        "--expect-axi-lite-addr-bits",
        "8",
        "--expect-axi-lite-data-bits",
        "32",
        "--expect-axi-lite-strb-bits",
        "4",
        "--expect-axi-lite-write-count",
        "9",
        "--expect-reg-status-control",
        "0x00",
        "--expect-reg-xsize",
        "0x04",
        "--expect-reg-ysize",
        "0x08",
        "--expect-reg-distance-q8",
        "0x0c",
        "--expect-reg-fixed-point-scale",
        "0x10",
        "--expect-reg-fixed-inv-qac-q16",
        "0x14",
        "--expect-reg-fixed-raw-quant",
        "0x18",
        "--expect-reg-flags",
        "0x1c",
        "--expect-reg-fixed-ytox",
        "0x20",
        "--expect-reg-fixed-ytob",
        "0x24",
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
    summary must include("\"artifacts\"")
    summary must include("\"source_manifest\"")
    summary must include("\"stream_bin\"")
    summary must include("\"stream_bin_resolved\"")
    summary must include("\"checksums\"")
    summary must include("\"sha256\"")
    summary must include("\"target\"")
    summary must include("\"interface\": \"rgb_axi_stream\"")
    summary must include("\"controlled_shell\": \"HjxlAxiLiteStreamCore\"")
    summary must include("\"trace_route\"")
    summary must include("\"name\": \"ac-tokens\"")
    summary must include("\"stage\": 12")
    summary must include("\"frame\"")
    summary must include("\"xsize\": 16")
    summary must include("\"ysize\": 8")
    summary must include("\"x_blocks\": 2")
    summary must include("\"y_blocks\": 1")
    summary must include("\"padded_xsize\": 16")
    summary must include("\"padded_ysize\": 8")
    summary must include("\"block_count\": 2")
    summary must include("\"x_tiles\": 1")
    summary must include("\"y_tiles\": 1")
    summary must include("\"tile_count\": 1")
    summary must include("\"status_bits\"")
    summary must include("\"protocol_error\": 0")
    summary must include("\"busy\": 1")
    summary must include("\"overflow\": 2")
    summary must include("\"unsupported_distance\": 3")
    summary must include("\"clear_protocol_error_write_bit\": 0")
    summary must include("\"trace\"")
    summary must include("\"stage_shift\": 0")
    summary must include("\"group_shift\": 8")
    summary must include("\"index_shift\": 24")
    summary must include("\"trace_value_shift\": 56")
    summary must include("\"stage_byte_offset\": 0")
    summary must include("\"group_byte_offset\": 1")
    summary must include("\"index_byte_offset\": 3")
    summary must include("\"trace_value_byte_offset\": 7")
    summary must include("\"stage_mask\": 255")
    summary must include("\"group_mask\": 65535")
    summary must include("\"packed_bytes\": 11")
    summary must include("\"tkeep_mask\": 2047")
    summary must include("\"default_capture_word_bytes\": 16")
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
    summary must include("\"input_stream\"")
    summary must include("\"word_count\": 128")
    summary must include("\"input_data_bits\": 48")
    summary must include("\"word_bytes\": 6")
    summary must include("\"input_keep_mask\": 63")
    summary must include("\"byte_count\": 768")
    summary must include("\"axi_lite\"")
    summary must include("\"addr_bits\": 8")
    summary must include("\"data_bits\": 32")
    summary must include("\"register_map\"")
    summary must include("\"status_control\": 0")
    summary must include("\"distance_q8\": 12")
    summary must include("\"flags\": 28")
    summary must include("\"fixed_ytox\": 32")
    summary must include("\"fixed_ytob\": 36")
    summary must include("\"strb_bits\": 4")
    summary must include("\"write_count\": 9")

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

    val invalidInputByteCount = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--stream-bin",
        captureBin.toString,
        "--last-bin",
        captureLast.toString,
        "--expect-input-byte-count",
        (expectedInputByteCount - 1).toString,
        "--expect-codestream-bin",
        expectedCodestream.toString
      )
    )
    invalidInputByteCount.exitCode mustBe 1
    invalidInputByteCount.output must include(
      s"replay plan stream byte_count expected ${expectedInputByteCount - 1}, got $expectedInputByteCount"
    )
    invalidInputByteCount.output must not include "Traceback"

    val invalidInputDataBits = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--stream-bin",
        captureBin.toString,
        "--last-bin",
        captureLast.toString,
        "--expect-input-data-bits",
        "32",
        "--expect-codestream-bin",
        expectedCodestream.toString
      )
    )
    invalidInputDataBits.exitCode mustBe 1
    invalidInputDataBits.output must include("replay plan stream input_data_bits expected 32, got 48")
    invalidInputDataBits.output must not include "Traceback"

    val invalidInputKeepMask = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--stream-bin",
        captureBin.toString,
        "--last-bin",
        captureLast.toString,
        "--expect-input-keep-mask",
        "0xf",
        "--expect-codestream-bin",
        expectedCodestream.toString
      )
    )
    invalidInputKeepMask.exitCode mustBe 1
    invalidInputKeepMask.output must include("replay plan stream input_keep_mask expected 15, got 63")
    invalidInputKeepMask.output must not include "Traceback"

    val invalidFrameBlockCount = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--stream-bin",
        captureBin.toString,
        "--last-bin",
        captureLast.toString,
        "--expect-frame-block-count",
        "1",
        "--expect-codestream-bin",
        expectedCodestream.toString
      )
    )
    invalidFrameBlockCount.exitCode mustBe 1
    invalidFrameBlockCount.output must include("replay plan frame block_count expected 1, got 2")
    invalidFrameBlockCount.output must not include "Traceback"

    val invalidStatusBit = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--stream-bin",
        captureBin.toString,
        "--last-bin",
        captureLast.toString,
        "--expect-status-unsupported-distance-bit",
        "4",
        "--expect-codestream-bin",
        expectedCodestream.toString
      )
    )
    invalidStatusBit.exitCode mustBe 1
    invalidStatusBit.output must include("replay plan status_bits unsupported_distance expected 4, got 3")
    invalidStatusBit.output must not include "Traceback"

    val invalidStatusNegative = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--stream-bin",
        captureBin.toString,
        "--last-bin",
        captureLast.toString,
        "--expect-status-protocol-error-bit",
        "-1",
        "--expect-codestream-bin",
        expectedCodestream.toString
      )
    )
    invalidStatusNegative.exitCode mustBe 1
    invalidStatusNegative.output must include("--expect-status-protocol-error-bit must be nonnegative")
    invalidStatusNegative.output must not include "Traceback"

    val invalidAxiLiteDataBits = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--stream-bin",
        captureBin.toString,
        "--last-bin",
        captureLast.toString,
        "--expect-axi-lite-data-bits",
        "64",
        "--expect-codestream-bin",
        expectedCodestream.toString
      )
    )
    invalidAxiLiteDataBits.exitCode mustBe 1
    invalidAxiLiteDataBits.output must include("replay plan AXI-Lite data_bits expected 64, got 32")
    invalidAxiLiteDataBits.output must not include "Traceback"

    val invalidRegisterMapFlags = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--stream-bin",
        captureBin.toString,
        "--last-bin",
        captureLast.toString,
        "--expect-reg-flags",
        "0x20",
        "--expect-codestream-bin",
        expectedCodestream.toString
      )
    )
    invalidRegisterMapFlags.exitCode mustBe 1
    invalidRegisterMapFlags.output must include("replay plan AXI-Lite register_map flags expected 32, got 28")
    invalidRegisterMapFlags.output must not include "Traceback"

    val invalidRegisterMapNegative = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--stream-bin",
        captureBin.toString,
        "--last-bin",
        captureLast.toString,
        "--expect-reg-status-control",
        "-1",
        "--expect-codestream-bin",
        expectedCodestream.toString
      )
    )
    invalidRegisterMapNegative.exitCode mustBe 1
    invalidRegisterMapNegative.output must include("--expect-reg-status-control must be nonnegative")
    invalidRegisterMapNegative.output must not include "Traceback"

    val invalidTraceCaptureWordBytes = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--stream-bin",
        captureBin.toString,
        "--last-bin",
        captureLast.toString,
        "--expect-capture-word-bytes",
        "8",
        "--expect-codestream-bin",
        expectedCodestream.toString
      )
    )
    invalidTraceCaptureWordBytes.exitCode mustBe 1
    invalidTraceCaptureWordBytes.output must include(
      "replay plan trace default_capture_word_bytes expected 8, got 16"
    )
    invalidTraceCaptureWordBytes.output must not include "Traceback"

    val invalidTraceKeepMask = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--stream-bin",
        captureBin.toString,
        "--last-bin",
        captureLast.toString,
        "--expect-trace-keep-mask",
        "0x3ff",
        "--expect-codestream-bin",
        expectedCodestream.toString
      )
    )
    invalidTraceKeepMask.exitCode mustBe 1
    invalidTraceKeepMask.output must include("replay plan trace tkeep_mask expected 1023, got 2047")
    invalidTraceKeepMask.output must not include "Traceback"

    val invalidTraceKeepMaskArgument = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--stream-bin",
        captureBin.toString,
        "--last-bin",
        captureLast.toString,
        "--expect-trace-keep-mask",
        "bad",
        "--expect-codestream-bin",
        expectedCodestream.toString
      )
    )
    invalidTraceKeepMaskArgument.exitCode mustBe 2
    invalidTraceKeepMaskArgument.output must include("argument --expect-trace-keep-mask: must be an integer")
    invalidTraceKeepMaskArgument.output must not include "Traceback"

    val invalidTraceValueShift = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--stream-bin",
        captureBin.toString,
        "--last-bin",
        captureLast.toString,
        "--expect-trace-value-shift",
        "48",
        "--expect-codestream-bin",
        expectedCodestream.toString
      )
    )
    invalidTraceValueShift.exitCode mustBe 1
    invalidTraceValueShift.output must include("replay plan trace trace_value_shift expected 48, got 56")
    invalidTraceValueShift.output must not include "Traceback"

    val invalidTraceIndexByteOffset = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--stream-bin",
        captureBin.toString,
        "--last-bin",
        captureLast.toString,
        "--expect-trace-index-byte-offset",
        "4",
        "--expect-codestream-bin",
        expectedCodestream.toString
      )
    )
    invalidTraceIndexByteOffset.exitCode mustBe 1
    invalidTraceIndexByteOffset.output must include("replay plan trace index_byte_offset expected 4, got 3")
    invalidTraceIndexByteOffset.output must not include "Traceback"

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

    val invalidTargetVariant = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--stream-bin",
        captureBin.toString,
        "--last-bin",
        captureLast.toString,
        "--expect-target-variant",
        "estimated-cfl",
        "--expect-codestream-bin",
        expectedCodestream.toString
      )
    )
    invalidTargetVariant.exitCode mustBe 1
    invalidTargetVariant.output must include("replay plan target variant expected 'estimated-cfl', got 'rgb'")
    invalidTargetVariant.output must not include "Traceback"

    val invalidTargetStreamShell = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--stream-bin",
        captureBin.toString,
        "--last-bin",
        captureLast.toString,
        "--expect-target-stream-shell",
        "HjxlPreparedCflDctAxiStreamCore",
        "--expect-codestream-bin",
        expectedCodestream.toString
      )
    )
    invalidTargetStreamShell.exitCode mustBe 1
    invalidTargetStreamShell.output must include(
      "replay plan target stream_shell expected 'HjxlPreparedCflDctAxiStreamCore', got 'HjxlAxiStreamCore'"
    )
    invalidTargetStreamShell.output must not include "Traceback"

    val conflictingTargetKv260Top = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--stream-bin",
        captureBin.toString,
        "--last-bin",
        captureLast.toString,
        "--expect-target-kv260-top",
        "HjxlKv260PreparedDctTop",
        "--expect-target-no-kv260-top",
        "--expect-codestream-bin",
        expectedCodestream.toString
      )
    )
    conflictingTargetKv260Top.exitCode mustBe 2
    conflictingTargetKv260Top.output must include(
      "--expect-target-kv260-top and --expect-target-no-kv260-top are mutually exclusive"
    )
    conflictingTargetKv260Top.output must not include "Traceback"

    val invalidTargetInputKeep = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--stream-bin",
        captureBin.toString,
        "--last-bin",
        captureLast.toString,
        "--expect-target-input-keep-enforced",
        "1",
        "--expect-codestream-bin",
        expectedCodestream.toString
      )
    )
    invalidTargetInputKeep.exitCode mustBe 1
    invalidTargetInputKeep.output must include(
      "replay plan target input_keep_enforced expected 1, got 0"
    )
    invalidTargetInputKeep.output must not include "Traceback"

    val missingCoefficientScale = runCommand(
      Seq(
        "python3",
        "tools/hjxl_replay_capture.py",
        "--replay-plan-json",
        replayPlan.toString,
        "--stream-bin",
        captureBin.toString,
        "--last-bin",
        captureLast.toString,
        "--expect-input-coefficient-fraction-bits",
        "16",
        "--expect-codestream-bin",
        expectedCodestream.toString
      )
    )
    missingCoefficientScale.exitCode mustBe 1
    missingCoefficientScale.output must include(
      "replay plan stream metadata is missing coefficient_fraction_bits"
    )
    missingCoefficientScale.output must not include "Traceback"

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

    Files.writeString(traceCsv, "stage,group,index,value\nBogusStage,0,0,0\n")
    val invalidStage = runCommand(
      Seq(
        "python3",
        "tools/hjxl_trace_tokens.py",
        "--trace-csv",
        traceCsv.toString,
        "--dc-tokens-npy",
        dcTokens.toString,
        "--width",
        "8",
        "--height",
        "8"
      )
    )
    invalidStage.exitCode mustBe 1
    invalidStage.output must include("stage must be a trace stage name or integer")
    invalidStage.output must not include "Traceback"

    Files.writeString(
      traceCsv,
      s"stage,group,index,value\n${TraceStage.DcTokens},0,0,bad\n"
    )
    val invalidValue = runCommand(
      Seq(
        "python3",
        "tools/hjxl_trace_tokens.py",
        "--trace-csv",
        traceCsv.toString,
        "--dc-tokens-npy",
        dcTokens.toString,
        "--width",
        "8",
        "--height",
        "8"
      )
    )
    invalidValue.exitCode mustBe 1
    invalidValue.output must include("value must be an integer")
    invalidValue.output must not include "Traceback"

    Files.writeString(
      traceCsv,
      s"stage,group,index,value\n${TraceStage.DcTokens},0,0,${1L << 32}\n"
    )
    val oversizedTokenValue = runCommand(
      Seq(
        "python3",
        "tools/hjxl_trace_tokens.py",
        "--trace-csv",
        traceCsv.toString,
        "--dc-tokens-npy",
        dcTokens.toString,
        "--width",
        "8",
        "--height",
        "8"
      )
    )
    oversizedTokenValue.exitCode mustBe 1
    oversizedTokenValue.output must include("packed token value 4294967296 outside uint32")
    oversizedTokenValue.output must not include "Traceback"

    val invalidStrategyDimensions = runCommand(
      Seq(
        "python3",
        "tools/hjxl_trace_tokens.py",
        "--trace-csv",
        traceCsv.toString,
        "--ac-strategy-npy",
        strategy.toString,
        "--width",
        "0",
        "--height",
        "8"
      )
    )
    invalidStrategyDimensions.exitCode mustBe 1
    invalidStrategyDimensions.output must include("--ac-strategy-npy requires positive --width and --height")
    invalidStrategyDimensions.output must not include "Traceback"

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

  "hjxl_trace_tokens.py extracts raw quant and CFL metadata grids" in {
    requireNumpy()

    val temp = Files.createTempDirectory("hjxl-trace-metadata-grids-")
    val traceCsv = temp.resolve("trace.csv")
    val rawQuant = temp.resolve("raw-quant.npy")
    val ytox = temp.resolve("ytox.npy")
    val ytob = temp.resolve("ytob.npy")
    Files.writeString(
      traceCsv,
      "stage,group,index,value\n" +
        s"${TraceStage.RawQuantField},0,0,5\n" +
        s"${TraceStage.RawQuantField},0,1,200\n" +
        s"${TraceStage.RawQuantField},0,2,255\n" +
        s"${TraceStage.RawQuantField},0,3,11\n" +
        s"${TraceStage.YtoxMap},0,0,-3\n" +
        s"${TraceStage.YtobMap},0,0,2\n"
    )

    expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_trace_tokens.py",
        "--trace-csv",
        traceCsv.toString,
        "--raw-quant-field-npy",
        rawQuant.toString,
        "--ytox-map-npy",
        ytox.toString,
        "--ytob-map-npy",
        ytob.toString,
        "--width",
        "9",
        "--height",
        "9"
      )
    )

    readNpyAsJson(rawQuant) mustBe "[[5,200],[255,11]]"
    readNpyAsJson(ytox) mustBe "[[-3]]"
    readNpyAsJson(ytob) mustBe "[[2]]"

    Files.writeString(
      traceCsv,
      "stage,group,index,value\n" +
        s"${TraceStage.YtoxMap},0,0,128\n" +
        s"${TraceStage.YtoxMap},0,1,0\n"
    )
    val invalidCfl = runCommand(
      Seq(
        "python3",
        "tools/hjxl_trace_tokens.py",
        "--trace-csv",
        traceCsv.toString,
        "--ytox-map-npy",
        ytox.toString,
        "--width",
        "65",
        "--height",
        "8"
      )
    )
    invalidCfl.exitCode mustBe 1
    invalidCfl.output must include("Y-to-X CFL map value 128 outside int8")
    invalidCfl.output must not include "Traceback"

    Files.writeString(
      traceCsv,
      "stage,group,index,value\n" +
        s"${TraceStage.RawQuantField},0,0,256\n"
    )
    val invalidRawQuant = runCommand(
      Seq(
        "python3",
        "tools/hjxl_trace_tokens.py",
        "--trace-csv",
        traceCsv.toString,
        "--raw-quant-field-npy",
        rawQuant.toString,
        "--width",
        "1",
        "--height",
        "1"
      )
    )
    invalidRawQuant.exitCode mustBe 1
    invalidRawQuant.output must include("raw quant-field value 256 outside uint8")
    invalidRawQuant.output must not include "Traceback"

    val invalidMetadataDimensions = runCommand(
      Seq(
        "python3",
        "tools/hjxl_trace_tokens.py",
        "--trace-csv",
        traceCsv.toString,
        "--raw-quant-field-npy",
        rawQuant.toString,
        "--width",
        "9",
        "--height",
        "0"
      )
    )
    invalidMetadataDimensions.exitCode mustBe 1
    invalidMetadataDimensions.output must include("--raw-quant-field-npy requires positive --width and --height")
    invalidMetadataDimensions.output must not include "Traceback"

    Files.writeString(
      traceCsv,
      "stage,group,index,value\n" +
        s"${TraceStage.RawQuantField},0,0,5\n"
    )
    val missingRawQuant = runCommand(
      Seq(
        "python3",
        "tools/hjxl_trace_tokens.py",
        "--trace-csv",
        traceCsv.toString,
        "--raw-quant-field-npy",
        rawQuant.toString,
        "--width",
        "9",
        "--height",
        "8"
      )
    )
    missingRawQuant.exitCode mustBe 1
    missingRawQuant.output must include("missing raw quant-field trace indices")
    missingRawQuant.output must not include "Traceback"
  }

  "hjxl_trace_to_codestream.py accepts packed stream trace inputs" in {
    requireNumpy()

    val temp = Files.createTempDirectory("hjxl-stream-trace-to-codestream-")
    val streamCsv = temp.resolve("stream.csv")
    val streamBin = temp.resolve("stream.bin")
    val lastBin = temp.resolve("last.bin")
    val traceCsv = temp.resolve("trace.csv")
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

    Files.writeString(traceCsv, "stage,group,index,value\nBogusStage,0,0,0\n")
    val invalidStage = runCommand(
      Seq(
        "python3",
        "tools/hjxl_trace_to_codestream.py",
        "--trace-csv",
        traceCsv.toString,
        "--width",
        "8",
        "--height",
        "8",
        "--codestream-bin",
        codestream.toString
      )
    )
    invalidStage.exitCode mustBe 1
    invalidStage.output must include("stage must be a trace stage name or integer")
    invalidStage.output must not include "Traceback"

    Files.writeString(
      traceCsv,
      s"stage,group,index,value\n${TraceStage.DcTokens},0,0,bad\n"
    )
    val invalidValue = runCommand(
      Seq(
        "python3",
        "tools/hjxl_trace_to_codestream.py",
        "--trace-csv",
        traceCsv.toString,
        "--width",
        "8",
        "--height",
        "8",
        "--codestream-bin",
        codestream.toString
      )
    )
    invalidValue.exitCode mustBe 1
    invalidValue.output must include("value must be an integer")
    invalidValue.output must not include "Traceback"
  }
}
