// See README.md for license details.

package hjxl

import java.nio.file.Files
import java.nio.file.Path
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.sys.process.Process
import scala.sys.process.ProcessLogger

class TokenCompareToolSpec extends AnyFreeSpec with Matchers {
  private case class CommandResult(exitCode: Int, output: String)

  private def requireNumpy(): Unit =
    assume(
      Process(Seq("python3", "-c", "import numpy")).! == 0,
      "python3 with numpy is required for token-comparator tests"
    )

  private def runCommand(command: Seq[String]): CommandResult = {
    val output = scala.collection.mutable.ArrayBuffer.empty[String]
    val logger = ProcessLogger(line => output += line, line => output += line)
    val exitCode = Process(command, TestPaths.repoRoot.toFile, "PYTHONDONTWRITEBYTECODE" -> "1").!(logger)
    CommandResult(exitCode, output.mkString("\n"))
  }

  private def writeNpy(path: Path, values: String, dtype: String): Unit = {
    val result = runCommand(
      Seq(
        "python3",
        "-c",
        "import json, sys, numpy as np\n" +
          "np.save(sys.argv[1], np.asarray(json.loads(sys.argv[2]), dtype=sys.argv[3]))\n",
        path.toString,
        values,
        dtype
      )
    )
    withClue(result.output) {
      result.exitCode mustBe 0
    }
  }

  "hjxl_compare_tokens.py accepts matching token and strategy artifacts" in {
    requireNumpy()

    val temp = Files.createTempDirectory("hjxl-compare-pass-")
    val expectedTokens = temp.resolve("expected-acmeta.npy")
    val actualTokens = temp.resolve("actual-acmeta.npy")
    val expectedStrategy = temp.resolve("expected-strategy.npy")
    val actualStrategy = temp.resolve("actual-strategy.npy")

    writeNpy(expectedTokens, "[[2, 0], [10, 3], [5, 8]]", "uint32")
    writeNpy(actualTokens, "[[2, 0], [10, 3], [5, 8]]", "uint32")
    writeNpy(expectedStrategy, "[[1, 0], [0, 0]]", "uint8")
    writeNpy(actualStrategy, "[[1, 0], [0, 0]]", "uint8")

    val result = runCommand(
      Seq(
        "python3",
        "tools/hjxl_compare_tokens.py",
        "--expected-ac-metadata-tokens-npy",
        expectedTokens.toString,
        "--actual-ac-metadata-tokens-npy",
        actualTokens.toString,
        "--expected-ac-strategy-npy",
        expectedStrategy.toString,
        "--actual-ac-strategy-npy",
        actualStrategy.toString
      )
    )

    result.exitCode mustBe 0
    result.output must include("token artifacts match")
  }

  "hjxl_compare_tokens.py reports the first token mismatch" in {
    requireNumpy()

    val temp = Files.createTempDirectory("hjxl-compare-mismatch-")
    val expectedTokens = temp.resolve("expected-ac.npy")
    val actualTokens = temp.resolve("actual-ac.npy")

    writeNpy(expectedTokens, "[[1, 0], [2, 7], [3, 11]]", "uint32")
    writeNpy(actualTokens, "[[1, 0], [2, 8], [4, 11]]", "uint32")

    val result = runCommand(
      Seq(
        "python3",
        "tools/hjxl_compare_tokens.py",
        "--expected-ac-tokens-npy",
        expectedTokens.toString,
        "--actual-ac-tokens-npy",
        actualTokens.toString
      )
    )

    result.exitCode mustBe 1
    result.output must include("ac-tokens-npy: 2 mismatches")
    result.output must include("first at ordinal 1")
    result.output must include("actual=(2, 8)")
    result.output must include("expected=(2, 7)")
  }

  "hjxl_compare_tokens.py rejects malformed token arrays" in {
    requireNumpy()

    val temp = Files.createTempDirectory("hjxl-compare-shape-")
    val expectedTokens = temp.resolve("expected-dc.npy")
    val actualTokens = temp.resolve("actual-dc.npy")

    writeNpy(expectedTokens, "[1, 2, 3]", "uint32")
    writeNpy(actualTokens, "[[1, 2], [3, 4]]", "uint32")

    val result = runCommand(
      Seq(
        "python3",
        "tools/hjxl_compare_tokens.py",
        "--expected-dc-tokens-npy",
        expectedTokens.toString,
        "--actual-dc-tokens-npy",
        actualTokens.toString
      )
    )

    result.exitCode mustBe 1
    result.output must include("expected dc-tokens-npy: expected shape (n, 2)")
  }
}
