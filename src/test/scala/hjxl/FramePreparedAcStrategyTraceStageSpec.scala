// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.jdk.CollectionConverters._
import scala.sys.process.Process
import scala.sys.process.ProcessLogger

class FramePreparedAcStrategyTraceStageSpec
    extends AnyFreeSpec
    with Matchers
    with ChiselSim {
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim
  private val libjxlTinyRoot =
    Path.of(sys.env.getOrElse("LIBJXL_TINY", "/Users/yunhocho/GitHub/libjxl-tiny"))
  private val dctFirst = AcStrategyCode.encoded(AcStrategyCode.Dct, isFirstBlock = true)
  private val horizontalFirst =
    AcStrategyCode.encoded(AcStrategyCode.Dct8x16, isFirstBlock = true)
  private val horizontalContinuation =
    AcStrategyCode.encoded(AcStrategyCode.Dct8x16, isFirstBlock = false)

  private case class OracleBlock(
      xyb: Seq[Seq[Int]],
      dct8x8: Seq[Seq[Int]],
      aqMapQ24: BigInt,
      strategyMaskQ16: BigInt,
      distanceQ8: Int
  )

  private case class OracleRgb(x: Int, y: Int, rQ8: Int, gQ8: Int, bQ8: Int)

  private case class OracleFixture(
      blocks: Seq[OracleBlock],
      rgb: Seq[OracleRgb],
      decision: Seq[Int]
  )

  private def requireReferenceTools(): Unit = {
    assume(
      Files.isDirectory(libjxlTinyRoot.resolve("python")),
      s"libjxl-tiny Python checkout not found at $libjxlTinyRoot"
    )
    assume(
      Process(Seq("python3", "-c", "import numpy")).! == 0,
      "python3 with numpy is required for frame AC-strategy fixtures"
    )
  }

  private def oracleFixture(pattern: String, distance: Double): OracleFixture = {
    requireReferenceTools()
    val xybPath = Files.createTempFile(s"hjxl-frame-strategy-$pattern-", ".npy")
    val rgbPath = Files.createTempFile(s"hjxl-frame-strategy-$pattern-rgb-", ".csv")
    val costPath = Files.createTempFile(s"hjxl-frame-strategy-$pattern-", ".csv")
    val output = scala.collection.mutable.ArrayBuffer.empty[String]
    val logger = ProcessLogger(line => output += line, line => output += line)
    val exitCode = Process(
      Seq(
        "python3",
        "tools/hjxl_reference.py",
        "--width",
        "16",
        "--height",
        "16",
        "--pattern",
        pattern,
        "--distance",
        distance.toString,
        "--xyb-npy",
        xybPath.toString,
        "--xyb-q12-csv",
        rgbPath.toString,
        "--ac-strategy-cost-q16-csv",
        costPath.toString
      ),
      TestPaths.repoRoot.toFile,
      "LIBJXL_TINY" -> libjxlTinyRoot.toString,
      "PYTHONDONTWRITEBYTECODE" -> "1"
    ).!(logger)
    withClue(output.mkString("\n")) {
      exitCode mustBe 0
    }

    val xybValues = Process(
      Seq(
        "python3",
        "-c",
        "import numpy as np,sys; a=np.rint(np.load(sys.argv[1])*(1<<12)).astype(np.int64); print(','.join(str(int(v)) for v in a.reshape(-1)))",
        xybPath.toString
      ),
      TestPaths.repoRoot.toFile,
      "PYTHONDONTWRITEBYTECODE" -> "1"
    ).!!.trim.split(",", -1).map(_.toInt).toSeq
    xybValues.length mustBe 3 * 16 * 16

    val lines = Files.readAllLines(costPath, StandardCharsets.UTF_8).asScala.toSeq
    lines.head must startWith("candidate,strategy,block_x,block_y,coefficient")
    val rows = lines.tail.map(_.split(",", -1))
    val dctCandidates = rows.filter(row => row(0).toInt < 4).groupBy(_(0).toInt)
    dctCandidates.keySet mustBe (0 until 4).toSet

    val blocks = (0 until 4).map { block =>
      val ordered = dctCandidates(block).sortBy(_(4).toInt)
      ordered.map(_(4).toInt) mustBe (0 until blockSize)
      val blockX = block % 2
      val blockY = block / 2
      val xyb = (0 until 3).map { channel =>
        (0 until blockSize).map { sample =>
          val x = blockX * 8 + sample % 8
          val y = blockY * 8 + sample / 8
          xybValues(channel * 16 * 16 + y * 16 + x)
        }
      }
      OracleBlock(
        xyb = xyb,
        dct8x8 = (0 until 3).map(channel => ordered.map(_(5 + channel).toInt)),
        aqMapQ24 = BigInt(ordered.head(8)),
        strategyMaskQ16 = BigInt(ordered.head(9)),
        distanceQ8 = ordered.head(10).toInt
      )
    }
    val rgbLines = Files.readAllLines(rgbPath, StandardCharsets.UTF_8).asScala.toSeq
    rgbLines.head mustBe "raster,x,y,r_q8,g_q8,b_q8,xyb_x_q12,xyb_y_q12,xyb_b_q12"
    val rgb = rgbLines.tail.map { line =>
      val row = line.split(",", -1)
      OracleRgb(row(1).toInt, row(2).toInt, row(3).toInt, row(4).toInt, row(5).toInt)
    }.filter(row => row.x < 16 && row.y < 16)
    OracleFixture(blocks, rgb, rows.head(19).split(":", -1).map(_.toInt).toSeq)
  }

  private def pokeConfig(
      dut: FramePreparedAcStrategyTraceStage,
      width: Int,
      height: Int
  ): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(0.U)
    dut.io.config.fixedInvQacQ16.poke(0.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.fixedYtox.poke(0.S)
    dut.io.config.fixedYtob.poke(0.S)
    dut.io.config.enableXyb.poke(true.B)
    dut.io.config.enableDct.poke(false.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(false.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.Dc.U)
  }

  private def feedZeroBlocks(
      dut: FramePreparedAcStrategyTraceStage,
      count: Int,
      distanceQ8: Int = 256
  ): Unit = {
    dut.io.input.valid.poke(true.B)
    dut.io.input.bits.aqMapQ24.poke((BigInt(1) << 24).U)
    dut.io.input.bits.strategyMaskQ16.poke(0.U)
    dut.io.input.bits.distanceQ8.poke(distanceQ8.U)
    for (channel <- 0 until 3; sample <- 0 until blockSize) {
      dut.io.input.bits.xyb(channel)(sample).poke(0.S)
      dut.io.input.bits.dct8x8(channel)(sample).poke(0.S)
    }
    for (block <- 0 until count) {
      dut.io.input.bits.last.poke((block == count - 1).B)
      var waitCycles = 0
      while (!dut.io.input.ready.peek().litToBoolean && waitCycles < 8) {
        dut.clock.step()
        waitCycles += 1
      }
      withClue(s"prepared block $block input wait: ") {
        waitCycles must be < 8
      }
      dut.clock.step()
    }
    dut.io.input.valid.poke(false.B)
  }

  private def feedOracleBlocks(
      dut: FramePreparedAcStrategyTraceStage,
      blocks: Seq[OracleBlock]
  ): Unit = {
    dut.io.input.valid.poke(true.B)
    for ((block, blockIndex) <- blocks.zipWithIndex) {
      dut.io.input.bits.aqMapQ24.poke(block.aqMapQ24.U)
      dut.io.input.bits.strategyMaskQ16.poke(block.strategyMaskQ16.U)
      dut.io.input.bits.distanceQ8.poke(block.distanceQ8.U)
      dut.io.input.bits.last.poke((blockIndex == blocks.length - 1).B)
      for (channel <- 0 until 3; sample <- 0 until blockSize) {
        dut.io.input.bits.xyb(channel)(sample).poke(block.xyb(channel)(sample).S)
        dut.io.input.bits.dct8x8(channel)(sample).poke(block.dct8x8(channel)(sample).S)
      }
      dut.io.input.ready.expect(true.B)
      dut.clock.step()
    }
    dut.io.input.valid.poke(false.B)
  }

  private def collect(
      dut: FramePreparedAcStrategyTraceStage,
      expected: Seq[Int],
      stallFirstTrace: Boolean = false
  ): Unit = {
    dut.io.trace.ready.poke((!stallFirstTrace).B)
    var waitCycles = 0
    while (!dut.io.trace.valid.peek().litToBoolean && waitCycles < 100000) {
      dut.clock.step()
      waitCycles += 1
    }
    withClue("strategy trace wait: ") {
      waitCycles must be < 100000
    }

    if (stallFirstTrace) {
      val held = (
        dut.io.trace.bits.stage.peekValue().asBigInt,
        dut.io.trace.bits.group.peekValue().asBigInt,
        dut.io.trace.bits.index.peekValue().asBigInt,
        dut.io.trace.bits.value.peekValue().asBigInt
      )
      dut.clock.step(3)
      dut.io.trace.valid.expect(true.B)
      (
        dut.io.trace.bits.stage.peekValue().asBigInt,
        dut.io.trace.bits.group.peekValue().asBigInt,
        dut.io.trace.bits.index.peekValue().asBigInt,
        dut.io.trace.bits.value.peekValue().asBigInt
      ) mustBe held
      dut.io.trace.ready.poke(true.B)
    }

    expected.zipWithIndex.foreach { case (value, index) =>
      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.stage.expect(TraceStage.AcStrategy.U)
      dut.io.trace.bits.group.expect(0.U)
      dut.io.trace.bits.index.expect(index.U)
      dut.io.trace.bits.value.expect(value.S)
      dut.io.traceLast.expect((index == expected.length - 1).B)
      dut.clock.step()
    }
    dut.io.trace.valid.expect(false.B)
    dut.io.busy.expect(false.B)
  }

  private def pokeCoreConfig(dut: HjxlCore, width: Int, height: Int): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(0.U)
    dut.io.config.fixedInvQacQ16.poke(0.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.fixedYtox.poke(0.S)
    dut.io.config.fixedYtob.poke(0.S)
    dut.io.config.enableXyb.poke(true.B)
    dut.io.config.enableDct.poke(false.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(false.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.Dc.U)
  }

  private def feedRgb(dut: HjxlCore, rgb: Seq[OracleRgb]): Unit = {
    dut.io.input.valid.poke(true.B)
    for (pixel <- rgb) {
      dut.io.input.bits.x.poke(pixel.x.U)
      dut.io.input.bits.y.poke(pixel.y.U)
      dut.io.input.bits.r.poke(pixel.rQ8.S)
      dut.io.input.bits.g.poke(pixel.gQ8.S)
      dut.io.input.bits.b.poke(pixel.bQ8.S)
      var waitCycles = 0
      while (!dut.io.input.ready.peek().litToBoolean && waitCycles < 1000) {
        dut.clock.step()
        waitCycles += 1
      }
      withClue(s"RGB pixel (${pixel.x}, ${pixel.y}) input wait: ") {
        waitCycles must be < 1000
      }
      dut.clock.step()
    }
    dut.io.input.valid.poke(false.B)
  }

  "complete zero-valued 2x2 regions select horizontal rectangles and hold traces" in {
    val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 16)
    simulate(new FramePreparedAcStrategyTraceStage(config)) { dut =>
      pokeConfig(dut, width = 16, height = 16)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      feedZeroBlocks(dut, count = 4)
      collect(
        dut,
        Seq(horizontalFirst, horizontalContinuation, horizontalFirst, horizontalContinuation),
        stallFirstTrace = true
      )
      dut.io.overflow.expect(false.B)
      dut.io.unsupportedDistance.expect(false.B)
    }
  }

  "the prepared frame boundary matches independent constant and gradient decisions" in {
    val fixtures = Seq("constant", "gradient").map(pattern => pattern -> oracleFixture(pattern, 1.0))
    fixtures.head._2.decision mustBe
      Seq(horizontalFirst, horizontalContinuation, horizontalFirst, horizontalContinuation)
    val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 16)
    simulate(new FramePreparedAcStrategyTraceStage(config)) { dut =>
      pokeConfig(dut, width = 16, height = 16)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      for ((pattern, fixture) <- fixtures) {
        feedOracleBlocks(dut, fixture.blocks)
        withClue(s"$pattern prepared frame decision: ") {
          collect(dut, fixture.decision)
          dut.io.overflow.expect(false.B)
          dut.io.unsupportedDistance.expect(false.B)
        }
      }
    }
  }

  "the focused core route generates the adaptive strategy map from RGB" in {
    val fixture = oracleFixture(pattern = "constant", distance = 1.0)
    val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 16)
    simulate(new HjxlCore(config, traceRoute = TraceStage.AcStrategy)) { dut =>
      pokeCoreConfig(dut, width = 16, height = 16)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      feedRgb(dut, fixture.rgb)
      var waitCycles = 0
      while (!dut.io.trace.valid.peek().litToBoolean && waitCycles < 100000) {
        dut.clock.step()
        waitCycles += 1
      }
      withClue("focused RGB strategy trace wait: ") {
        waitCycles must be < 100000
      }
      fixture.decision.zipWithIndex.foreach { case (value, index) =>
        dut.io.trace.valid.expect(true.B)
        dut.io.trace.bits.stage.expect(TraceStage.AcStrategy.U)
        dut.io.trace.bits.group.expect(0.U)
        dut.io.trace.bits.index.expect(index.U)
        dut.io.trace.bits.value.expect(value.S)
        dut.io.traceLast.expect((index == fixture.decision.length - 1).B)
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.busy.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "incomplete block rows and columns remain ordinary DCT" in {
    val config = HjxlConfig(maxFrameWidth = 24, maxFrameHeight = 24)
    simulate(new FramePreparedAcStrategyTraceStage(config)) { dut =>
      pokeConfig(dut, width = 24, height = 24)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      feedZeroBlocks(dut, count = 9)
      collect(
        dut,
        Seq(
          horizontalFirst,
          horizontalContinuation,
          dctFirst,
          horizontalFirst,
          horizontalContinuation,
          dctFirst,
          dctFirst,
          dctFirst,
          dctFirst
        )
      )
      dut.io.overflow.expect(false.B)
    }
  }

  "the final one-block-wide tile does not form a cross-tile rectangle" in {
    val config = HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 16)
    simulate(new FramePreparedAcStrategyTraceStage(config)) { dut =>
      pokeConfig(dut, width = 72, height = 16)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      feedZeroBlocks(dut, count = 18)
      val completeTileRow = Seq.fill(4)(Seq(horizontalFirst, horizontalContinuation)).flatten
      collect(dut, completeTileRow ++ Seq(dctFirst) ++ completeTileRow ++ Seq(dctFirst))
      dut.io.overflow.expect(false.B)
    }
  }

  "unsupported prepared distances report fallback even without a complete region" in {
    val config = HjxlConfig(maxFrameWidth = 8, maxFrameHeight = 8)
    simulate(new FramePreparedAcStrategyTraceStage(config)) { dut =>
      pokeConfig(dut, width = 8, height = 8)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      feedZeroBlocks(dut, count = 1, distanceQ8 = 300)
      var waitCycles = 0
      while (!dut.io.trace.valid.peek().litToBoolean && waitCycles < 100000) {
        dut.clock.step()
        waitCycles += 1
      }
      waitCycles must be < 100000
      dut.io.unsupportedDistance.expect(true.B)
      collect(dut, Seq(dctFirst))
      dut.io.overflow.expect(false.B)
    }
  }
}
