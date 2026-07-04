// See README.md for license details.

package hjxl

import java.nio.file.Files
import java.nio.file.Path
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.sys.process.Process
import scala.sys.process.ProcessLogger

class TraceToCodestreamToolSpec extends AnyFreeSpec with Matchers {
  private val width = 16
  private val height = 8
  private val pattern = "gradient"
  private val libjxlTinyRoot =
    Path.of(sys.env.getOrElse("LIBJXL_TINY", "/Users/yunhocho/GitHub/libjxl-tiny"))

  private case class CommandResult(exitCode: Int, output: String)

  private def requireReferenceTools(): Unit = {
    assume(
      Files.isDirectory(libjxlTinyRoot.resolve("python")),
      s"libjxl-tiny Python checkout not found at $libjxlTinyRoot"
    )
    assume(
      Process(Seq("python3", "-c", "import numpy")).! == 0,
      "python3 with numpy is required for trace-to-codestream tests"
    )
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

  private def expectSuccess(command: Seq[String]): String = {
    val result = runCommand(command)
    withClue(result.output) {
      result.exitCode mustBe 0
    }
    result.output
  }

  private def writeSplitTraceCsv(
      dcTokens: Path,
      acMetadataTokens: Path,
      acTokens: Path,
      acStrategy: Path,
      metadataTraceCsv: Path,
      acTraceCsv: Path
  ): Unit = {
    val script =
      """import csv, sys, numpy as np
        |dc = np.load(sys.argv[1])
        |acmeta = np.load(sys.argv[2])
        |ac = np.load(sys.argv[3])
        |strategy = np.load(sys.argv[4]).reshape(-1)
        |metadata_path = sys.argv[5]
        |ac_path = sys.argv[6]
        |with open(metadata_path, "w", newline="") as handle:
        |    writer = csv.writer(handle)
        |    writer.writerow(["stage", "group", "index", "value"])
        |    for group, (context, value) in enumerate(dc):
        |        writer.writerow([10, group, int(context), int(value)])
        |    for index, value in enumerate(strategy):
        |        writer.writerow([6, 0, index, int(value)])
        |    for group, (context, value) in enumerate(acmeta):
        |        writer.writerow([11, group, int(context), int(value)])
        |with open(ac_path, "w", newline="") as handle:
        |    writer = csv.writer(handle)
        |    writer.writerow(["stage", "group", "index", "value"])
        |    for group, (context, value) in enumerate(ac):
        |        writer.writerow([12, group, int(context), int(value)])
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
        metadataTraceCsv.toString,
        acTraceCsv.toString
      )
    )
  }

  "hjxl_trace_to_codestream.py assembles multi-file StageTrace dumps into oracle bytes" in {
    requireReferenceTools()

    val temp = Files.createTempDirectory("hjxl-trace-to-codestream-")
    val dcTokens = temp.resolve("dc.npy")
    val acMetadataTokens = temp.resolve("acmeta.npy")
    val acTokens = temp.resolve("ac.npy")
    val acStrategy = temp.resolve("strategy.npy")
    val metadataTraceCsv = temp.resolve("metadata-trace.csv")
    val acTraceCsv = temp.resolve("ac-trace.csv")
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
        "--fixed-dct-only-dc-tokens-npy",
        dcTokens.toString,
        "--fixed-dct-only-ac-metadata-tokens-npy",
        acMetadataTokens.toString,
        "--fixed-dct-only-ac-tokens-npy",
        acTokens.toString,
        "--default-ac-strategy-npy",
        acStrategy.toString,
        "--fixed-dct-only-frame-bin",
        directFrame.toString,
        "--fixed-dct-only-codestream-bin",
        directCodestream.toString
      )
    )
    writeSplitTraceCsv(dcTokens, acMetadataTokens, acTokens, acStrategy, metadataTraceCsv, acTraceCsv)

    val output = expectSuccess(
      Seq(
        "python3",
        "tools/hjxl_trace_to_codestream.py",
        "--trace-csv",
        metadataTraceCsv.toString,
        "--trace-csv",
        acTraceCsv.toString,
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
      )
    )

    output must include("assembled trace:")
    Files.readAllBytes(assembledFrame).toSeq mustBe Files.readAllBytes(directFrame).toSeq
    Files.readAllBytes(assembledCodestream).toSeq mustBe Files.readAllBytes(directCodestream).toSeq
  }

  "hjxl_trace_to_codestream.py reports expected byte mismatches" in {
    requireReferenceTools()

    val temp = Files.createTempDirectory("hjxl-trace-to-codestream-byte-mismatch-")
    val dcTokens = temp.resolve("dc.npy")
    val acMetadataTokens = temp.resolve("acmeta.npy")
    val acTokens = temp.resolve("ac.npy")
    val acStrategy = temp.resolve("strategy.npy")
    val metadataTraceCsv = temp.resolve("metadata-trace.csv")
    val acTraceCsv = temp.resolve("ac-trace.csv")
    val wrongCodestream = temp.resolve("wrong.jxl")

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
        "--fixed-dct-only-dc-tokens-npy",
        dcTokens.toString,
        "--fixed-dct-only-ac-metadata-tokens-npy",
        acMetadataTokens.toString,
        "--fixed-dct-only-ac-tokens-npy",
        acTokens.toString,
        "--default-ac-strategy-npy",
        acStrategy.toString
      )
    )
    writeSplitTraceCsv(dcTokens, acMetadataTokens, acTokens, acStrategy, metadataTraceCsv, acTraceCsv)
    Files.write(wrongCodestream, Array[Byte](0x00, 0x01, 0x02))

    val result = runCommand(
      Seq(
        "python3",
        "tools/hjxl_trace_to_codestream.py",
        "--trace-csv",
        metadataTraceCsv.toString,
        "--trace-csv",
        acTraceCsv.toString,
        "--width",
        width.toString,
        "--height",
        height.toString,
        "--expect-codestream-bin",
        wrongCodestream.toString
      )
    )

    result.exitCode mustBe 1
    result.output must include("codestream mismatch")
    result.output must include("actual_len=")
    result.output must include("expected_len=3")
  }

  "hjxl_trace_to_codestream.py rejects missing AC strategy rows" in {
    requireReferenceTools()

    val temp = Files.createTempDirectory("hjxl-trace-to-codestream-missing-strategy-")
    val traceCsv = temp.resolve("trace.csv")
    val codestream = temp.resolve("out.jxl")
    Files.writeString(
      traceCsv,
      "stage,group,index,value\n10,0,0,0\n11,0,0,0\n12,0,0,0\n",
    )

    val result = runCommand(
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

    result.exitCode mustBe 1
    result.output must include("missing AC strategy trace indices")
  }
}
