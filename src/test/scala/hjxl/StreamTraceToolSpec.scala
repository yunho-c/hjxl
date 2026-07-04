// See README.md for license details.

package hjxl

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
