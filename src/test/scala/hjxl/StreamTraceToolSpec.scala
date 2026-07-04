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
    headerText must include("#define HJXL_PREPARED_STREAM_WORD_COUNT 201u")
    headerText must include("#define HJXL_PREPARED_INPUT_DATA_BITS 32u")
    headerText must include("#define HJXL_PREPARED_STREAM_WORD_BYTES 4u")
    headerText must include("#define HJXL_PREPARED_STREAM_BYTE_COUNT 804u")
    headerText must include("#define HJXL_PREPARED_REG_XSIZE 0x00000004u")
    headerText must include("#define HJXL_PREPARED_STATUS_BUSY_BIT 1u")
    headerText must include("{ 0x0000001cu, 0x0000020eu, 0x0000000fu }, /* flags */")
    headerText must include(
      "HJXL_PREPARED_STREAM_BYTE_COUNT == HJXL_PREPARED_STREAM_WORD_COUNT * HJXL_PREPARED_STREAM_WORD_BYTES"
    )
    headerText must include(
      "sizeof(HJXL_PREPARED_AXI_LITE_WRITES) / sizeof(HJXL_PREPARED_AXI_LITE_WRITES[0]) == " +
        "HJXL_PREPARED_AXI_LITE_WRITE_COUNT"
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
    val preparedIndex = Files.readString(hostBundleDir.resolve("prepared-bundle.json")).replace("\r\n", "\n")
    preparedIndex must include("\"format\": \"hjxl.host_bundle.v1\"")
    preparedIndex must include("\"checksums\"")
    preparedIndex must include("\"source_manifest\": \"prepared-manifest.json\"")
    preparedIndex must include("\"header\": \"prepared.h\"")
    preparedIndex must include("\"stream_bin\": \"prepared-stream.bin\"")
    preparedIndex must include("\"manifest_format\": \"hjxl.prepared_dct_stream_manifest.v1\"")
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
    headerText must include("#define HJXL_RGB_STREAM_WORD_COUNT 4u")
    headerText must include("#define HJXL_RGB_INPUT_DATA_BITS 48u")
    headerText must include("#define HJXL_RGB_STREAM_WORD_BYTES 6u")
    headerText must include("#define HJXL_RGB_STREAM_BYTE_COUNT 24u")
    headerText must include("#define HJXL_RGB_REG_FLAGS 0x0000001cu")
    headerText must include("{ 0x00000004u, 0x00000002u, 0x0000000fu }, /* xsize */")
    headerText must include("{ 0x0000001cu, 0x00000207u, 0x0000000fu }, /* flags */")
    headerText must include(
      "HJXL_RGB_STREAM_BYTE_COUNT == HJXL_RGB_STREAM_WORD_COUNT * HJXL_RGB_STREAM_WORD_BYTES"
    )
    headerText must include(
      "sizeof(HJXL_RGB_AXI_LITE_WRITES) / sizeof(HJXL_RGB_AXI_LITE_WRITES[0]) == HJXL_RGB_AXI_LITE_WRITE_COUNT"
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
