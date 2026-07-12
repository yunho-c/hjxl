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

  private def readNpyAsJson(path: Path): String = {
    val result = runCommand(
      Seq(
        "python3",
        "-c",
        "import json, sys, numpy as np\n" +
          "print(json.dumps(np.load(sys.argv[1]).tolist(), separators=(',', ':')))\n",
        path.toString
      )
    )
    withClue(result.output) {
      result.exitCode mustBe 0
    }
    result.output.trim
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
    result.output must include("artifacts match")
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
    val expectedStrategy = temp.resolve("expected-strategy.npy")
    val actualStrategy = temp.resolve("actual-strategy.npy")

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

    writeNpy(expectedTokens, "[[1, 0]]", "uint32")
    writeNpy(actualTokens, "[[1, 0.5]]", "float32")
    val floatTokenResult = runCommand(
      Seq(
        "python3",
        "tools/hjxl_compare_tokens.py",
        "--expected-dc-tokens-npy",
        expectedTokens.toString,
        "--actual-dc-tokens-npy",
        actualTokens.toString
      )
    )

    floatTokenResult.exitCode mustBe 1
    floatTokenResult.output must include("actual dc-tokens-npy: expected integer token pairs")

    writeNpy(actualTokens, "[[1, -1]]", "int32")
    val negativeTokenResult = runCommand(
      Seq(
        "python3",
        "tools/hjxl_compare_tokens.py",
        "--expected-dc-tokens-npy",
        expectedTokens.toString,
        "--actual-dc-tokens-npy",
        actualTokens.toString
      )
    )

    negativeTokenResult.exitCode mustBe 1
    negativeTokenResult.output must include("actual dc-tokens-npy: negative token value -1 at row 0")

    writeNpy(actualTokens, s"[[1, ${1L << 32}]]", "int64")
    val oversizedTokenResult = runCommand(
      Seq(
        "python3",
        "tools/hjxl_compare_tokens.py",
        "--expected-dc-tokens-npy",
        expectedTokens.toString,
        "--actual-dc-tokens-npy",
        actualTokens.toString
      )
    )

    oversizedTokenResult.exitCode mustBe 1
    oversizedTokenResult.output must include("actual dc-tokens-npy: token value 4294967296 outside uint32 at row 0")

    writeNpy(expectedStrategy, "[[0]]", "uint8")
    writeNpy(actualStrategy, "[[256]]", "int16")
    val strategyResult = runCommand(
      Seq(
        "python3",
        "tools/hjxl_compare_tokens.py",
        "--expected-ac-strategy-npy",
        expectedStrategy.toString,
        "--actual-ac-strategy-npy",
        actualStrategy.toString
      )
    )

    strategyResult.exitCode mustBe 1
    strategyResult.output must include("actual ac-strategy-npy: AC strategy value 256 outside uint8")
  }

  "hjxl_compare_tokens.py accepts matching metadata grids" in {
    requireNumpy()

    val temp = Files.createTempDirectory("hjxl-compare-metadata-pass-")
    val expectedRawQuant = temp.resolve("expected-raw-quant.npy")
    val actualRawQuant = temp.resolve("actual-raw-quant.npy")
    val expectedYtox = temp.resolve("expected-ytox.npy")
    val actualYtox = temp.resolve("actual-ytox.npy")
    val expectedYtob = temp.resolve("expected-ytob.npy")
    val actualYtob = temp.resolve("actual-ytob.npy")

    writeNpy(expectedRawQuant, "[[5, 200], [255, 11]]", "uint8")
    writeNpy(actualRawQuant, "[[5, 200], [255, 11]]", "uint8")
    writeNpy(expectedYtox, "[[-3, 4]]", "int8")
    writeNpy(actualYtox, "[[-3, 4]]", "int8")
    writeNpy(expectedYtob, "[[2, -5]]", "int8")
    writeNpy(actualYtob, "[[2, -5]]", "int8")

    val result = runCommand(
      Seq(
        "python3",
        "tools/hjxl_compare_tokens.py",
        "--expected-raw-quant-field-npy",
        expectedRawQuant.toString,
        "--actual-raw-quant-field-npy",
        actualRawQuant.toString,
        "--expected-ytox-map-npy",
        expectedYtox.toString,
        "--actual-ytox-map-npy",
        actualYtox.toString,
        "--expected-ytob-map-npy",
        expectedYtob.toString,
        "--actual-ytob-map-npy",
        actualYtob.toString
      )
    )

    result.exitCode mustBe 0
    result.output must include("artifacts match")
  }

  "hjxl_reference.py emits fixed metadata grids for comparator oracles" in {
    requireNumpy()

    val temp = Files.createTempDirectory("hjxl-fixed-metadata-oracle-")
    val rawQuant = temp.resolve("fixed-raw-quant.npy")
    val ytox = temp.resolve("fixed-ytox.npy")
    val ytob = temp.resolve("fixed-ytob.npy")
    val expectedRawQuant = temp.resolve("expected-raw-quant.npy")
    val expectedYtox = temp.resolve("expected-ytox.npy")
    val expectedYtob = temp.resolve("expected-ytob.npy")

    val referenceResult = runCommand(
      Seq(
        "python3",
        "tools/hjxl_reference.py",
        "--width",
        "9",
        "--height",
        "65",
        "--pattern",
        "constant",
        "--fixed-raw-quant",
        "200",
        "--fixed-ytox",
        "-7",
        "--fixed-ytob",
        "11",
        "--fixed-dct-only-raw-quant-field-npy",
        rawQuant.toString,
        "--fixed-dct-only-ytox-map-npy",
        ytox.toString,
        "--fixed-dct-only-ytob-map-npy",
        ytob.toString
      )
    )
    withClue(referenceResult.output) {
      referenceResult.exitCode mustBe 0
    }
    readNpyAsJson(rawQuant) mustBe "[[200,200],[200,200],[200,200],[200,200],[200,200],[200,200],[200,200],[200,200],[200,200]]"
    readNpyAsJson(ytox) mustBe "[[-7],[-7]]"
    readNpyAsJson(ytob) mustBe "[[11],[11]]"

    writeNpy(expectedRawQuant, "[[200,200],[200,200],[200,200],[200,200],[200,200],[200,200],[200,200],[200,200],[200,200]]", "uint8")
    writeNpy(expectedYtox, "[[-7],[-7]]", "int8")
    writeNpy(expectedYtob, "[[11],[11]]", "int8")

    val compareResult = runCommand(
      Seq(
        "python3",
        "tools/hjxl_compare_tokens.py",
        "--expected-raw-quant-field-npy",
        expectedRawQuant.toString,
        "--actual-raw-quant-field-npy",
        rawQuant.toString,
        "--expected-ytox-map-npy",
        expectedYtox.toString,
        "--actual-ytox-map-npy",
        ytox.toString,
        "--expected-ytob-map-npy",
        expectedYtob.toString,
        "--actual-ytob-map-npy",
        ytob.toString
      )
    )

    compareResult.exitCode mustBe 0
    compareResult.output must include("artifacts match")

    val outOfRangeFixedYtox = runCommand(
      Seq(
        "python3",
        "tools/hjxl_reference.py",
        "--fixed-ytox",
        "-129",
        "--fixed-dct-only-ytox-map-npy",
        temp.resolve("bad-ytox.npy").toString
      )
    )
    outOfRangeFixedYtox.exitCode mustBe 1
    outOfRangeFixedYtox.output must include("--fixed-ytox must fit in signed 8-bit")
    outOfRangeFixedYtox.output must not include "Traceback"
  }

  "hjxl_compare_tokens.py reports metadata grid mismatches" in {
    requireNumpy()

    val temp = Files.createTempDirectory("hjxl-compare-metadata-mismatch-")
    val expectedRawQuant = temp.resolve("expected-raw-quant.npy")
    val actualRawQuant = temp.resolve("actual-raw-quant.npy")

    writeNpy(expectedRawQuant, "[[5, 7], [9, 11]]", "uint8")
    writeNpy(actualRawQuant, "[[5, 7], [9, 12]]", "uint8")

    val result = runCommand(
      Seq(
        "python3",
        "tools/hjxl_compare_tokens.py",
        "--expected-raw-quant-field-npy",
        expectedRawQuant.toString,
        "--actual-raw-quant-field-npy",
        actualRawQuant.toString
      )
    )

    result.exitCode mustBe 1
    result.output must include("raw-quant-field-npy: 1 mismatches")
    result.output must include("first at (1, 1)")
    result.output must include("actual=12 expected=11")
  }

  "hjxl_compare_tokens.py rejects malformed metadata grids" in {
    requireNumpy()

    val temp = Files.createTempDirectory("hjxl-compare-metadata-shape-")
    val expectedRawQuant = temp.resolve("expected-raw-quant.npy")
    val actualRawQuant = temp.resolve("actual-raw-quant.npy")
    val expectedYtox = temp.resolve("expected-ytox.npy")
    val actualYtox = temp.resolve("actual-ytox.npy")

    writeNpy(expectedYtox, "[1, 2, 3]", "int8")
    writeNpy(actualYtox, "[[1, 2, 3]]", "int8")

    val result = runCommand(
      Seq(
        "python3",
        "tools/hjxl_compare_tokens.py",
        "--expected-ytox-map-npy",
        expectedYtox.toString,
        "--actual-ytox-map-npy",
        actualYtox.toString
      )
    )

    result.exitCode mustBe 1
    result.output must include("expected ytox-map-npy: expected 2D Y-to-X CFL map grid")

    writeNpy(expectedRawQuant, "[[200]]", "uint8")
    writeNpy(actualRawQuant, "[[-56]]", "int16")
    val rawQuantResult = runCommand(
      Seq(
        "python3",
        "tools/hjxl_compare_tokens.py",
        "--expected-raw-quant-field-npy",
        expectedRawQuant.toString,
        "--actual-raw-quant-field-npy",
        actualRawQuant.toString
      )
    )

    rawQuantResult.exitCode mustBe 1
    rawQuantResult.output must include("actual raw-quant-field-npy: raw quant-field value -56 outside uint8")

    writeNpy(actualRawQuant, "[[200.0]]", "float32")
    val rawQuantFloatResult = runCommand(
      Seq(
        "python3",
        "tools/hjxl_compare_tokens.py",
        "--expected-raw-quant-field-npy",
        expectedRawQuant.toString,
        "--actual-raw-quant-field-npy",
        actualRawQuant.toString
      )
    )

    rawQuantFloatResult.exitCode mustBe 1
    rawQuantFloatResult.output must include("actual raw-quant-field-npy: expected integer raw quant-field grid")

    writeNpy(expectedYtox, "[[-128]]", "int8")
    writeNpy(actualYtox, "[[128]]", "int16")
    val cflResult = runCommand(
      Seq(
        "python3",
        "tools/hjxl_compare_tokens.py",
        "--expected-ytox-map-npy",
        expectedYtox.toString,
        "--actual-ytox-map-npy",
        actualYtox.toString
      )
    )

    cflResult.exitCode mustBe 1
    cflResult.output must include("actual ytox-map-npy: Y-to-X CFL map value 128 outside int8")
  }
}
