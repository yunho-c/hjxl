// See README.md for license details.

package hjxl

import _root_.circt.stage.ChiselStage
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

class FrameAqDctOnlyQuantizeTraceStageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val blockDim = HjxlConstants.BlockDim
  private val blockSize = blockDim * blockDim
  private val config = HjxlConfig(maxFrameWidth = 24, maxFrameHeight = 16)
  private val libjxlTinyRoot =
    Path.of(sys.env.getOrElse("LIBJXL_TINY", "/Users/yunhocho/GitHub/libjxl-tiny"))

  private case class RgbRow(
      x: Int,
      y: Int,
      r: Int,
      g: Int,
      b: Int,
      xybXQ12: Int,
      xybYQ12: Int,
      xybBQ12: Int
  )
  private case class FinalRow(block: Int, blockX: Int, blockY: Int, rawQuant: Int)
  private case class PreparedBlock(
      block: Int,
      coefficients: Seq[Seq[Int]],
      quant: Int,
      scaleQ16: Int,
      invQacQ16: BigInt,
      invDcFactorQ16: Seq[Int],
      xQmMultiplierQ16: Int,
      ytox: Int,
      ytob: Int,
      last: Boolean
  )
  private case class CflMap(tile: Int, ytox: Int, ytob: Int)
  private case class TraceRow(stage: Int, group: BigInt, index: BigInt, value: BigInt)

  private def requireReferenceTools(): Unit = {
    assume(Files.isDirectory(libjxlTinyRoot.resolve("python")))
    assume(Process(Seq("python3", "-c", "import numpy")).! == 0)
  }

  private def runTool(command: Seq[String]): Unit = {
    val output = scala.collection.mutable.ArrayBuffer.empty[String]
    val logger = ProcessLogger(line => output += line, line => output += line)
    val exitCode = Process(
      command,
      TestPaths.repoRoot.toFile,
      "LIBJXL_TINY" -> libjxlTinyRoot.toString,
      "PYTHONDONTWRITEBYTECODE" -> "1"
    ).!(logger)
    withClue(output.mkString("\n")) {
      exitCode mustBe 0
    }
  }

  private def generateFixture(
      pattern: String,
      width: Int,
      height: Int,
      distanceQ8: Int
  ): (Seq[RgbRow], Seq[FinalRow]) = {
    val xybCsv = Files.createTempFile(s"hjxl-aq-dct-$pattern-xyb-", ".csv")
    val finalCsv = Files.createTempFile(s"hjxl-aq-dct-$pattern-final-", ".csv")
    runTool(
      Seq(
        "python3",
        "tools/hjxl_reference.py",
        "--width",
        width.toString,
        "--height",
        height.toString,
        "--pattern",
        pattern,
        "--distance",
        (distanceQ8.toDouble / 256.0).toString,
        "--xyb-q12-csv",
        xybCsv.toString,
        "--aq-final-map-q24-csv",
        finalCsv.toString
      )
    )

    val xybLines = Files.readAllLines(xybCsv, StandardCharsets.UTF_8).asScala
    xybLines.head mustBe "raster,x,y,r_q8,g_q8,b_q8,xyb_x_q12,xyb_y_q12,xyb_b_q12"
    val rgb = xybLines.tail.map { line =>
      val values = line.split(",", -1)
      RgbRow(
        values(1).toInt,
        values(2).toInt,
        values(3).toInt,
        values(4).toInt,
        values(5).toInt,
        values(6).toInt,
        values(7).toInt,
        values(8).toInt
      )
    }.filter(row => row.x < width && row.y < height).toSeq

    val finalLines = Files.readAllLines(finalCsv, StandardCharsets.UTF_8).asScala
    finalLines.head.split(",", -1).last mustBe "input_q8_fixed_raw_quant"
    val rows = finalLines.tail.map { line =>
      val values = line.split(",", -1)
      FinalRow(values(0).toInt, values(1).toInt, values(2).toInt, values(17).toInt)
    }.toSeq
    rgb -> rows
  }

  private def readNpyFlatInts(path: Path): Seq[Int] = {
    val output = Process(
      Seq(
        "python3",
        "-c",
        "import numpy as np,sys; print(','.join(str(int(v)) for v in np.load(sys.argv[1]).reshape(-1)))",
        path.toString
      ),
      TestPaths.repoRoot.toFile,
      "PYTHONDONTWRITEBYTECODE" -> "1"
    ).!!.trim
    if (output.isEmpty) Seq.empty else output.split(",", -1).map(_.toInt).toSeq
  }

  private def referenceCflMaps(
      pattern: String,
      width: Int,
      height: Int,
      distanceQ8: Int
  ): Seq[CflMap] = {
    val ytoxPath = Files.createTempFile(s"hjxl-aq-cfl-$pattern-ytox-", ".npy")
    val ytobPath = Files.createTempFile(s"hjxl-aq-cfl-$pattern-ytob-", ".npy")
    runTool(
      Seq(
        "python3",
        "tools/hjxl_reference.py",
        "--width",
        width.toString,
        "--height",
        height.toString,
        "--pattern",
        pattern,
        "--distance",
        (distanceQ8.toDouble / 256.0).toString,
        "--dct-only-ytox-map-npy",
        ytoxPath.toString,
        "--dct-only-ytob-map-npy",
        ytobPath.toString
      )
    )
    val ytox = readNpyFlatInts(ytoxPath)
    val ytob = readNpyFlatInts(ytobPath)
    ytox.length mustBe ytob.length
    ytox.indices.map(tile => CflMap(tile, ytox(tile), ytob(tile)))
  }

  private def dct2(values: Seq[Long]): Seq[Long] =
    Seq(values(0) + values(1), values(0) - values(1))

  private def mulQ12(value: Long, coefficient: Int): Long =
    (value * coefficient.toLong) >> Dct8Approx.FractionBits

  private def dct4(values: Seq[Long]): Seq[Long] = {
    val even = dct2(Seq(values(0) + values(3), values(1) + values(2)))
    val odd = dct2(
      Seq(
        mulQ12(values(0) - values(3), Dct8Approx.Wc4(0)),
        mulQ12(values(1) - values(2), Dct8Approx.Wc4(1))
      )
    ).toArray
    odd(0) = mulQ12(odd(0), Dct8Approx.Sqrt2) + odd(1)
    Seq(even(0), odd(0), even(1), odd(1))
  }

  private def dct8(values: Seq[Long]): Seq[Long] = {
    val even = dct4((0 until 4).map(index => values(index) + values(7 - index)))
    val odd = dct4(
      (0 until 4).map(index => mulQ12(values(index) - values(7 - index), Dct8Approx.Wc8(index)))
    ).toArray
    odd(0) = mulQ12(odd(0), Dct8Approx.Sqrt2) + odd(1)
    for (index <- 1 until 3) {
      odd(index) = odd(index) + odd(index + 1)
    }
    Seq(even(0), odd(0), even(1), odd(1), even(2), odd(2), even(3), odd(3))
  }

  private def dct8x8(values: Seq[Int]): Seq[Int] = {
    def at(data: Seq[Long], y: Int, x: Int): Long = data(y * blockDim + x)
    val source = values.map(_.toLong)
    val columns = Seq.tabulate(blockDim) { x =>
      dct8((0 until blockDim).map(y => at(source, y, x))).map(_ >> 3)
    }
    val intermediate = Seq.tabulate(blockSize) { index =>
      val y = index / blockDim
      val x = index % blockDim
      columns(x)(y)
    }
    val rows = Seq.tabulate(blockDim) { y =>
      dct8((0 until blockDim).map(x => at(intermediate, y, x))).map(_ >> 3)
    }
    (for {
      coefficientX <- 0 until blockDim
      sourceY <- 0 until blockDim
    } yield rows(sourceY)(coefficientX).toInt)
  }

  private def expectedBlocks(
      rgb: Seq[RgbRow],
      finalRows: Seq[FinalRow],
      width: Int,
      height: Int,
      distanceQ8: Int,
      fixedPointScale: Int = 0,
      fixedRawQuant: Int = 0,
      fixedInvQacQ16: BigInt = 0,
      ytox: Int = 0,
      ytob: Int = 0
  ): Seq[PreparedBlock] = {
    val entry = DistanceParamsLookup.Entries.find(_.distanceQ8 == distanceQ8).get
    val pixels = rgb.map(row => (row.y * width + row.x) -> row).toMap
    val xBlocks = (width + 7) / 8
    val yBlocks = (height + 7) / 8
    val scaleQ16 = if (fixedPointScale == 0) entry.scaleQ16 else fixedPointScale
    for (blockY <- 0 until yBlocks; blockX <- 0 until xBlocks) yield {
      val block = blockY * xBlocks + blockX
      val samples = Seq.tabulate(blockSize) { sample =>
        val localX = sample % blockDim
        val localY = sample / blockDim
        val sourceX = math.min(blockX * blockDim + localX, width - 1)
        val sourceY = math.min(blockY * blockDim + localY, height - 1)
        val pixel = pixels(sourceY * width + sourceX)
        RgbToXybApprox.rgbToXybQ12(pixel.r, pixel.g, pixel.b)
      }
      val coefficients = (0 until 3).map { channel =>
        dct8x8(samples.map(sample => sample.productElement(channel).asInstanceOf[Int]))
      }
      val adaptiveQuant = finalRows.find(_.block == block).get.rawQuant
      val quant = if (fixedRawQuant == 0) adaptiveQuant else fixedRawQuant
      val reciprocal =
        if (fixedRawQuant == 0) AdaptiveInvQacFixedPoint.reciprocalQ16(scaleQ16, quant)
        else fixedInvQacQ16
      PreparedBlock(
        block = block,
        coefficients = coefficients,
        quant = quant,
        scaleQ16 = scaleQ16,
        invQacQ16 = reciprocal,
        invDcFactorQ16 = entry.invDcFactorQ16,
        xQmMultiplierQ16 = entry.xQmMultiplierQ16,
        ytox = ytox,
        ytob = ytob,
        last = block == xBlocks * yBlocks - 1
      )
    }
  }

  private def roundDivBy84(value: BigInt): BigInt = {
    val rounded = (value.abs + 42) / 84
    if (value < 0) -rounded else rounded
  }

  private def expectedCflMultiplier(
      samples: Seq[(Int, Int, Int, Int)],
      useBWeights: Boolean
  ): Int = {
    val (sumAa, sumAb) = samples.foldLeft(BigInt(0) -> BigInt(0)) {
      case ((aaAcc, abAcc), (coefficient, yQ12, xQ12, bQ12)) =>
        val weight = BigInt(
          if (useBWeights) CflWeightMatrix.BInvQ16(coefficient)
          else CflWeightMatrix.XInvQ16(coefficient)
        )
        val modelQ16 = ((BigInt(yQ12) << 4) * weight) >> 16
        val signalQ16 = (
          (BigInt(if (useBWeights) bQ12 else xQ12) << 4) * weight
        ) >> 16
        val aQ16 = roundDivBy84(modelQ16)
        val bQ16 = (if (useBWeights) modelQ16 else BigInt(0)) - signalQ16
        (aaAcc + ((aQ16 * aQ16) >> 16)) -> (abAcc + ((aQ16 * bQ16) >> 16))
    }
    val denominator = sumAa + samples.size * CflMultiplierEstimator.RegularizerQ16
    val numerator = -sumAb
    val roundedAbs = (numerator.abs + denominator / 2) / denominator
    val rounded = if (numerator < 0) -roundedAbs else roundedAbs
    rounded.max(-128).min(127).toInt
  }

  private def expectedCflMaps(
      blocks: Seq[PreparedBlock],
      width: Int,
      height: Int
  ): Seq[CflMap] = {
    val xBlocks = (width + blockDim - 1) / blockDim
    val yBlocks = (height + blockDim - 1) / blockDim
    val blocksPerTile = HjxlConstants.TileDim / blockDim
    val xTiles = (width + HjxlConstants.TileDim - 1) / HjxlConstants.TileDim
    val yTiles = (height + HjxlConstants.TileDim - 1) / HjxlConstants.TileDim
    for (tileY <- 0 until yTiles; tileX <- 0 until xTiles) yield {
      val samples = for {
        blockY <- tileY * blocksPerTile until math.min((tileY + 1) * blocksPerTile, yBlocks)
        blockX <- tileX * blocksPerTile until math.min((tileX + 1) * blocksPerTile, xBlocks)
        coefficient <- 0 until blockSize
      } yield {
        val block = blocks(blockY * xBlocks + blockX)
        (
          coefficient,
          block.coefficients(1)(coefficient),
          block.coefficients(0)(coefficient),
          block.coefficients(2)(coefficient)
        )
      }
      val tile = tileY * xTiles + tileX
      CflMap(
        tile,
        expectedCflMultiplier(samples, useBWeights = false),
        expectedCflMultiplier(samples, useBWeights = true)
      )
    }
  }

  private def pokeConfig(
      frameConfig: FrameConfig,
      width: Int,
      height: Int,
      distanceQ8: Int,
      fixedPointScale: Int = 0,
      fixedRawQuant: Int = 0,
      fixedInvQacQ16: BigInt = 0,
      ytox: Int = 0,
      ytob: Int = 0,
      tokenize: Boolean = false
  ): Unit = {
    frameConfig.xsize.poke(width.U)
    frameConfig.ysize.poke(height.U)
    frameConfig.distanceQ8.poke(distanceQ8.U)
    frameConfig.fixedPointScale.poke(fixedPointScale.U)
    frameConfig.fixedInvQacQ16.poke(fixedInvQacQ16.U)
    frameConfig.fixedRawQuant.poke(fixedRawQuant.U)
    frameConfig.fixedYtox.poke(ytox.S)
    frameConfig.fixedYtob.poke(ytob.S)
    frameConfig.enableXyb.poke(true.B)
    frameConfig.enableDct.poke(true.B)
    frameConfig.enableQuant.poke(true.B)
    frameConfig.enableTokenize.poke(tokenize.B)
    frameConfig.tokenSelect.poke(TokenTraceSelect.AcTokens.U)
  }

  private def driveRgb(
      input: chisel3.util.DecoupledIO[RgbPixel],
      clock: Clock,
      rows: Seq[RgbRow]
  ): Unit = {
    for ((row, index) <- rows.zipWithIndex) {
      input.valid.poke(true.B)
      input.bits.x.poke(row.x.U)
      input.bits.y.poke(row.y.U)
      input.bits.r.poke(row.r.S)
      input.bits.g.poke(row.g.S)
      input.bits.b.poke(row.b.S)
      var cycles = 0
      while (!input.ready.peek().litToBoolean && cycles < 100) {
        clock.step()
        cycles += 1
      }
      withClue(s"RGB input $index") {
        cycles must be < 100
      }
      clock.step()
    }
    input.valid.poke(false.B)
  }

  private def expectPreparedBlock(
      bits: AqDctOnlyBlockOutput,
      expected: PreparedBlock
  ): Unit = {
    bits.blockIndex.expect(expected.block.U)
    bits.blockLast.expect(expected.last.B)
    bits.quantize.quant.expect(expected.quant.U)
    bits.quantize.scaleQ16.expect(expected.scaleQ16.U)
    bits.quantize.invQacQ16.expect(expected.invQacQ16.U)
    bits.quantize.xQmMultiplierQ16.expect(expected.xQmMultiplierQ16.U)
    bits.quantize.ytox.expect(expected.ytox.S)
    bits.quantize.ytob.expect(expected.ytob.S)
    for (channel <- 0 until 3) {
      bits.quantize.invDcFactorQ16(channel).expect(expected.invDcFactorQ16(channel).U)
      for (coefficient <- 0 until blockSize) {
        bits.quantize.coefficients(channel)(coefficient)
          .expect(expected.coefficients(channel)(coefficient).S)
      }
    }
  }

  private def pokePreparedBlock(bits: DctOnlyQuantizeBlockInput, block: PreparedBlock): Unit = {
    bits.quant.poke(block.quant.U)
    bits.scaleQ16.poke(block.scaleQ16.U)
    bits.invQacQ16.poke(block.invQacQ16.U)
    bits.xQmMultiplierQ16.poke(block.xQmMultiplierQ16.U)
    bits.ytox.poke(block.ytox.S)
    bits.ytob.poke(block.ytob.S)
    for (channel <- 0 until 3) {
      bits.invDcFactorQ16(channel).poke(block.invDcFactorQ16(channel).U)
      for (coefficient <- 0 until blockSize) {
        bits.coefficients(channel)(coefficient).poke(block.coefficients(channel)(coefficient).S)
      }
    }
  }

  private def collectPreparedQuantized(
      blocks: Seq[PreparedBlock],
      width: Int,
      height: Int,
      distanceQ8: Int
  ): Seq[TraceRow] = {
    val observed = scala.collection.mutable.ArrayBuffer.empty[TraceRow]
    simulate(new FramePreparedDctOnlyQuantizeTraceStage(config, Some(Dct8Approx.FractionBits))) { dut =>
      pokeConfig(dut.io.config, width, height, distanceQ8)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()
      var nextBlock = 0
      var done = false
      var cycles = 0
      while (!done && cycles < 20000) {
        if (nextBlock < blocks.length) {
          dut.io.input.valid.poke(true.B)
          pokePreparedBlock(dut.io.input.bits, blocks(nextBlock))
        } else {
          dut.io.input.valid.poke(false.B)
        }
        if (dut.io.trace.valid.peek().litToBoolean) {
          observed += TraceRow(
            dut.io.trace.bits.stage.peekValue().asBigInt.toInt,
            dut.io.trace.bits.group.peekValue().asBigInt,
            dut.io.trace.bits.index.peekValue().asBigInt,
            dut.io.trace.bits.value.peekValue().asBigInt
          )
          done = dut.io.traceLast.peek().litToBoolean
        }
        val accepted = nextBlock < blocks.length && dut.io.input.ready.peek().litToBoolean
        dut.clock.step()
        if (accepted) nextBlock += 1
        cycles += 1
      }
      withClue("prepared quantized trace completion") {
        done mustBe true
        nextBlock mustBe blocks.length
      }
    }
    observed.toSeq
  }

  private def collectPreparedTokens(
      blocks: Seq[PreparedBlock],
      width: Int,
      height: Int,
      distanceQ8: Int
  ): Seq[TraceRow] = {
    val observed = scala.collection.mutable.ArrayBuffer.empty[TraceRow]
    simulate(new FramePreparedDctOnlyQuantizeTokenTraceStage(config, Some(Dct8Approx.FractionBits))) { dut =>
      pokeConfig(dut.io.config, width, height, distanceQ8, tokenize = true)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()
      var nextBlock = 0
      var done = false
      var cycles = 0
      while (!done && cycles < 30000) {
        if (nextBlock < blocks.length) {
          dut.io.input.valid.poke(true.B)
          pokePreparedBlock(dut.io.input.bits, blocks(nextBlock))
        } else {
          dut.io.input.valid.poke(false.B)
        }
        if (dut.io.trace.valid.peek().litToBoolean) {
          observed += TraceRow(
            dut.io.trace.bits.stage.peekValue().asBigInt.toInt,
            dut.io.trace.bits.group.peekValue().asBigInt,
            dut.io.trace.bits.index.peekValue().asBigInt,
            dut.io.trace.bits.value.peekValue().asBigInt
          )
          done = dut.io.traceLast.peek().litToBoolean
        }
        val accepted = nextBlock < blocks.length && dut.io.input.ready.peek().litToBoolean
        dut.clock.step()
        if (accepted) nextBlock += 1
        cycles += 1
      }
      withClue("prepared token trace completion") {
        done mustBe true
        nextBlock mustBe blocks.length
      }
    }
    observed.toSeq
  }

  private def collectRgbQuantized(
      rgb: Seq[RgbRow],
      width: Int,
      height: Int,
      distanceQ8: Int
  ): Seq[TraceRow] = {
    val observed = scala.collection.mutable.ArrayBuffer.empty[TraceRow]
    simulate(new FrameAqDctOnlyQuantizeTraceStage(config)) { dut =>
      pokeConfig(dut.io.config, width, height, distanceQ8)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()
      driveRgb(dut.io.input, dut.clock, rgb)
      var done = false
      var cycles = 0
      while (!done && cycles < 30000) {
        if (dut.io.trace.valid.peek().litToBoolean) {
          observed += TraceRow(
            dut.io.trace.bits.stage.peekValue().asBigInt.toInt,
            dut.io.trace.bits.group.peekValue().asBigInt,
            dut.io.trace.bits.index.peekValue().asBigInt,
            dut.io.trace.bits.value.peekValue().asBigInt
          )
          done = dut.io.traceLast.peek().litToBoolean
        }
        dut.clock.step()
        cycles += 1
      }
      done mustBe true
      dut.io.overflow.expect(false.B)
    }
    observed.toSeq
  }

  private def collectRgbTokens(
      rgb: Seq[RgbRow],
      width: Int,
      height: Int,
      distanceQ8: Int
  ): Seq[TraceRow] = {
    val observed = scala.collection.mutable.ArrayBuffer.empty[TraceRow]
    simulate(new FrameAqDctOnlyQuantizeTokenTraceStage(config)) { dut =>
      pokeConfig(dut.io.config, width, height, distanceQ8, tokenize = true)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()
      driveRgb(dut.io.input, dut.clock, rgb)
      var done = false
      var cycles = 0
      while (!done && cycles < 40000) {
        if (dut.io.trace.valid.peek().litToBoolean) {
          val held = TraceRow(
            dut.io.trace.bits.stage.peekValue().asBigInt.toInt,
            dut.io.trace.bits.group.peekValue().asBigInt,
            dut.io.trace.bits.index.peekValue().asBigInt,
            dut.io.trace.bits.value.peekValue().asBigInt
          )
          if (observed.isEmpty) {
            dut.io.trace.ready.poke(false.B)
            dut.clock.step(3)
            dut.io.trace.valid.expect(true.B)
            dut.io.trace.bits.stage.expect(held.stage.U)
            dut.io.trace.bits.group.expect(held.group.U)
            dut.io.trace.bits.index.expect(held.index.U)
            dut.io.trace.bits.value.expect(held.value.S)
            dut.io.trace.ready.poke(true.B)
          }
          observed += held
          done = dut.io.traceLast.peek().litToBoolean
        }
        dut.clock.step()
        cycles += 1
      }
      done mustBe true
      dut.io.overflow.expect(false.B)
    }
    observed.toSeq
  }

  private def collectRgbCflMaps(
      rgb: Seq[RgbRow],
      width: Int,
      height: Int,
      distanceQ8: Int,
      activeConfig: HjxlConfig,
      traceStageFilter: Option[Int] = None
  ): Seq[TraceRow] = {
    val observed = scala.collection.mutable.ArrayBuffer.empty[TraceRow]
    simulate(new FrameAqCflMapTraceStage(activeConfig, traceStageFilter)) { dut =>
      pokeConfig(dut.io.config, width, height, distanceQ8)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()
      driveRgb(dut.io.input, dut.clock, rgb)
      var done = false
      var cycles = 0
      while (!done && cycles < 100000) {
        if (dut.io.trace.valid.peek().litToBoolean) {
          val row = TraceRow(
            dut.io.trace.bits.stage.peekValue().asBigInt.toInt,
            dut.io.trace.bits.group.peekValue().asBigInt,
            dut.io.trace.bits.index.peekValue().asBigInt,
            dut.io.trace.bits.value.peekValue().asBigInt
          )
          if (observed.isEmpty) {
            dut.io.trace.ready.poke(false.B)
            dut.clock.step(3)
            dut.io.trace.valid.expect(true.B)
            dut.io.trace.bits.stage.expect(row.stage.U)
            dut.io.trace.bits.index.expect(row.index.U)
            dut.io.trace.bits.value.expect(row.value.S)
            dut.io.trace.ready.poke(true.B)
          }
          observed += row
          done = dut.io.traceLast.peek().litToBoolean
        }
        dut.clock.step()
        cycles += 1
      }
      withClue(s"RGB CFL map completion filter=$traceStageFilter") {
        done mustBe true
      }
      dut.io.overflow.expect(false.B)
    }
    observed.toSeq
  }

  private def collectPreparedCflQuantized(
      blocks: Seq[PreparedBlock],
      width: Int,
      height: Int,
      distanceQ8: Int
  ): Seq[TraceRow] = {
    val observed = scala.collection.mutable.ArrayBuffer.empty[TraceRow]
    simulate(
      new FramePreparedCflDctOnlyQuantizeTraceStage(config, Some(Dct8Approx.FractionBits))
    ) { dut =>
      pokeConfig(dut.io.config, width, height, distanceQ8)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()
      var nextBlock = 0
      var done = false
      var cycles = 0
      while (!done && cycles < 50000) {
        if (nextBlock < blocks.length) {
          dut.io.input.valid.poke(true.B)
          pokePreparedBlock(dut.io.input.bits, blocks(nextBlock))
        } else {
          dut.io.input.valid.poke(false.B)
        }
        if (dut.io.trace.valid.peek().litToBoolean) {
          observed += TraceRow(
            dut.io.trace.bits.stage.peekValue().asBigInt.toInt,
            dut.io.trace.bits.group.peekValue().asBigInt,
            dut.io.trace.bits.index.peekValue().asBigInt,
            dut.io.trace.bits.value.peekValue().asBigInt
          )
          done = dut.io.traceLast.peek().litToBoolean
        }
        val accepted = nextBlock < blocks.length && dut.io.input.ready.peek().litToBoolean
        dut.clock.step()
        if (accepted) nextBlock += 1
        cycles += 1
      }
      done mustBe true
      nextBlock mustBe blocks.length
    }
    observed.toSeq
  }

  private def collectRgbCflQuantized(
      rgb: Seq[RgbRow],
      width: Int,
      height: Int,
      distanceQ8: Int
  ): Seq[TraceRow] = {
    val observed = scala.collection.mutable.ArrayBuffer.empty[TraceRow]
    simulate(new FrameAqCflDctOnlyQuantizeTraceStage(config)) { dut =>
      pokeConfig(dut.io.config, width, height, distanceQ8)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()
      driveRgb(dut.io.input, dut.clock, rgb)
      var done = false
      var cycles = 0
      while (!done && cycles < 50000) {
        if (dut.io.trace.valid.peek().litToBoolean) {
          observed += TraceRow(
            dut.io.trace.bits.stage.peekValue().asBigInt.toInt,
            dut.io.trace.bits.group.peekValue().asBigInt,
            dut.io.trace.bits.index.peekValue().asBigInt,
            dut.io.trace.bits.value.peekValue().asBigInt
          )
          done = dut.io.traceLast.peek().litToBoolean
        }
        dut.clock.step()
        cycles += 1
      }
      done mustBe true
      dut.io.overflow.expect(false.B)
    }
    observed.toSeq
  }

  private def collectPreparedCflTokens(
      blocks: Seq[PreparedBlock],
      width: Int,
      height: Int,
      distanceQ8: Int
  ): Seq[TraceRow] = {
    val observed = scala.collection.mutable.ArrayBuffer.empty[TraceRow]
    simulate(
      new FramePreparedCflDctOnlyQuantizeTokenTraceStage(config, Some(Dct8Approx.FractionBits))
    ) { dut =>
      pokeConfig(dut.io.config, width, height, distanceQ8, tokenize = true)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()
      var nextBlock = 0
      var done = false
      var cycles = 0
      while (!done && cycles < 60000) {
        if (nextBlock < blocks.length) {
          dut.io.input.valid.poke(true.B)
          pokePreparedBlock(dut.io.input.bits, blocks(nextBlock))
        } else {
          dut.io.input.valid.poke(false.B)
        }
        if (dut.io.trace.valid.peek().litToBoolean) {
          observed += TraceRow(
            dut.io.trace.bits.stage.peekValue().asBigInt.toInt,
            dut.io.trace.bits.group.peekValue().asBigInt,
            dut.io.trace.bits.index.peekValue().asBigInt,
            dut.io.trace.bits.value.peekValue().asBigInt
          )
          done = dut.io.traceLast.peek().litToBoolean
        }
        val accepted = nextBlock < blocks.length && dut.io.input.ready.peek().litToBoolean
        dut.clock.step()
        if (accepted) nextBlock += 1
        cycles += 1
      }
      done mustBe true
      nextBlock mustBe blocks.length
    }
    observed.toSeq
  }

  private def collectRgbCflTokens(
      rgb: Seq[RgbRow],
      width: Int,
      height: Int,
      distanceQ8: Int
  ): Seq[TraceRow] = {
    val observed = scala.collection.mutable.ArrayBuffer.empty[TraceRow]
    simulate(new FrameAqCflDctOnlyQuantizeTokenTraceStage(config)) { dut =>
      pokeConfig(dut.io.config, width, height, distanceQ8, tokenize = true)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()
      driveRgb(dut.io.input, dut.clock, rgb)
      var done = false
      var cycles = 0
      while (!done && cycles < 60000) {
        if (dut.io.trace.valid.peek().litToBoolean) {
          observed += TraceRow(
            dut.io.trace.bits.stage.peekValue().asBigInt.toInt,
            dut.io.trace.bits.group.peekValue().asBigInt,
            dut.io.trace.bits.index.peekValue().asBigInt,
            dut.io.trace.bits.value.peekValue().asBigInt
          )
          done = dut.io.traceLast.peek().litToBoolean
        }
        dut.clock.step()
        cycles += 1
      }
      done mustBe true
      dut.io.overflow.expect(false.B)
    }
    observed.toSeq
  }

  private def collectRgbCflMetadata(
      rgb: Seq[RgbRow],
      width: Int,
      height: Int,
      distanceQ8: Int
  ): Seq[TraceRow] = {
    val observed = scala.collection.mutable.ArrayBuffer.empty[TraceRow]
    simulate(new FrameAqCflDctOnlyAcMetadataTokenTraceStage(config)) { dut =>
      pokeConfig(dut.io.config, width, height, distanceQ8, tokenize = true)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()
      driveRgb(dut.io.input, dut.clock, rgb)
      var done = false
      var cycles = 0
      while (!done && cycles < 50000) {
        if (dut.io.trace.valid.peek().litToBoolean) {
          observed += TraceRow(
            dut.io.trace.bits.stage.peekValue().asBigInt.toInt,
            dut.io.trace.bits.group.peekValue().asBigInt,
            dut.io.trace.bits.index.peekValue().asBigInt,
            dut.io.trace.bits.value.peekValue().asBigInt
          )
          done = dut.io.traceLast.peek().litToBoolean
        }
        dut.clock.step()
        cycles += 1
      }
      done mustBe true
      dut.io.overflow.expect(false.B)
    }
    observed.toSeq
  }

  "AdaptiveInvQacQ16 matches nearest division over supported scales and bytes" in {
    simulate(new AdaptiveInvQacQ16) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.output.ready.poke(true.B)
      dut.clock.step()
      val samples = for {
        scaleQ16 <- DistanceParamsLookup.Entries.map(_.scaleQ16) ++ Seq(1, 256, 4096, 65535)
        quant <- 1 to 255
      } yield scaleQ16 -> quant
      for (((scaleQ16, quant), sample) <- samples.zipWithIndex) {
        val expected = AdaptiveInvQacFixedPoint.reciprocalQ16(scaleQ16, quant)
        dut.io.input.bits.scaleQ16.poke(scaleQ16.U)
        dut.io.input.bits.rawQuant.poke(quant.U)
        dut.io.input.valid.poke(true.B)
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
        dut.io.input.valid.poke(false.B)
        dut.clock.step(33)
        withClue(s"reciprocal sample $sample scale=$scaleQ16 quant=$quant") {
          dut.io.output.valid.expect(true.B)
          dut.io.output.bits.expect(expected.U)
        }
        if (sample == 0) {
          dut.io.output.ready.poke(false.B)
          dut.clock.step(3)
          dut.io.output.valid.expect(true.B)
          dut.io.output.bits.expect(expected.U)
          dut.io.output.ready.poke(true.B)
        }
        dut.clock.step()
      }

      dut.io.input.bits.scaleQ16.poke(0.U)
      dut.io.input.bits.rawQuant.poke(0.U)
      dut.io.input.valid.poke(true.B)
      dut.clock.step()
      dut.io.input.valid.poke(false.B)
      dut.io.output.valid.expect(true.B)
      dut.io.output.bits.expect(AdaptiveInvQacFixedPoint.MaximumQ16.U)
    }
  }

  "FrameAqDctOnlyBlockStage emits adaptive raw quant and exact shared Q12 DCT blocks" in {
    requireReferenceTools()
    val width = 17
    val height = 9
    val distanceQ8 = 512
    val (rgb, finalRows) = generateFixture("gradient", width, height, distanceQ8)
    val expected = expectedBlocks(rgb, finalRows, width, height, distanceQ8)

    simulate(new FrameAqDctOnlyBlockStage(config)) { dut =>
      pokeConfig(dut.io.config, width, height, distanceQ8)
      dut.io.input.valid.poke(false.B)
      dut.io.output.ready.poke(true.B)
      dut.clock.step()
      driveRgb(dut.io.input, dut.clock, rgb)
      for ((block, index) <- expected.zipWithIndex) {
        var cycles = 0
        while (!dut.io.output.valid.peek().litToBoolean && cycles < 3000) {
          dut.clock.step()
          cycles += 1
        }
        withClue(s"adaptive DCT block $index") {
          cycles must be < 3000
          expectPreparedBlock(dut.io.output.bits, block)
        }
        if (index == 0) {
          dut.io.output.ready.poke(false.B)
          dut.clock.step(3)
          dut.io.output.valid.expect(true.B)
          expectPreparedBlock(dut.io.output.bits, block)
          dut.io.output.ready.poke(true.B)
        }
        dut.clock.step()
      }
      dut.io.busy.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "FrameAqDctOnlyBlockStage preserves fixed overrides and recomputes adaptive custom-scale reciprocals" in {
    requireReferenceTools()
    val width = 1
    val height = 1
    val distanceQ8 = 256
    val (rgb, finalRows) = generateFixture("constant", width, height, distanceQ8)

    def runCase(
        fixedPointScale: Int,
        fixedRawQuant: Int,
        fixedInvQacQ16: BigInt,
        ytox: Int,
        ytob: Int
    ): Unit = {
      val expected = expectedBlocks(
        rgb,
        finalRows,
        width,
        height,
        distanceQ8,
        fixedPointScale = fixedPointScale,
        fixedRawQuant = fixedRawQuant,
        fixedInvQacQ16 = fixedInvQacQ16,
        ytox = ytox,
        ytob = ytob
      ).head

      simulate(new FrameAqDctOnlyBlockStage(config)) { dut =>
        pokeConfig(
          dut.io.config,
          width,
          height,
          distanceQ8,
          fixedPointScale = fixedPointScale,
          fixedRawQuant = fixedRawQuant,
          fixedInvQacQ16 = fixedInvQacQ16,
          ytox = ytox,
          ytob = ytob
        )
        dut.io.input.valid.poke(false.B)
        dut.io.output.ready.poke(true.B)
        dut.clock.step()
        driveRgb(dut.io.input, dut.clock, rgb)

        // Live controls become next-frame state after the first pixel. The
        // block source must retain every active-frame scalar through AQ.
        pokeConfig(
          dut.io.config,
          width = 2,
          height = 1,
          distanceQ8 = 512,
          fixedPointScale = 123,
          fixedRawQuant = 29,
          fixedInvQacQ16 = 456,
          ytox = 3,
          ytob = -4
        )

        var cycles = 0
        while (!dut.io.output.valid.peek().litToBoolean && cycles < 3000) {
          dut.clock.step()
          cycles += 1
        }
        withClue(s"fixedPointScale=$fixedPointScale fixedRawQuant=$fixedRawQuant") {
          cycles must be < 3000
          expectPreparedBlock(dut.io.output.bits, expected)
        }
        dut.clock.step()
        dut.io.busy.expect(false.B)
      }
    }

    runCase(
      fixedPointScale = 777,
      fixedRawQuant = 13,
      fixedInvQacQ16 = 424242,
      ytox = -7,
      ytob = 11
    )
    runCase(
      fixedPointScale = 4096,
      fixedRawQuant = 0,
      fixedInvQacQ16 = 123,
      ytox = -4,
      ytob = 5
    )
  }

  "RGB adaptive quantized traces and complete tokens match the prepared handoff" in {
    requireReferenceTools()
    val width = 9
    val height = 1
    val distanceQ8 = 256
    val (rgb, finalRows) = generateFixture("gradient", width, height, distanceQ8)
    val blocks = expectedBlocks(rgb, finalRows, width, height, distanceQ8)

    val preparedQuantized = collectPreparedQuantized(blocks, width, height, distanceQ8)
    val rgbQuantized = collectRgbQuantized(rgb, width, height, distanceQ8)
    rgbQuantized mustBe preparedQuantized

    val preparedTokens = collectPreparedTokens(blocks, width, height, distanceQ8)
    val rgbTokens = collectRgbTokens(rgb, width, height, distanceQ8)
    rgbTokens mustBe preparedTokens
    rgbTokens.map(_.stage).distinct mustBe Seq(
      TraceStage.DcTokens,
      TraceStage.AcStrategy,
      TraceStage.AcMetadataTokens,
      TraceStage.AcTokens
    )

    val expectedMetadata = preparedTokens.filter(_.stage == TraceStage.AcMetadataTokens)
    val observedMetadata = scala.collection.mutable.ArrayBuffer.empty[TraceRow]
    simulate(new FrameAqDctOnlyAcMetadataTokenTraceStage(config)) { dut =>
      pokeConfig(dut.io.config, width, height, distanceQ8, tokenize = true)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()
      driveRgb(dut.io.input, dut.clock, rgb)
      var done = false
      var cycles = 0
      while (!done && cycles < 30000) {
        if (dut.io.trace.valid.peek().litToBoolean) {
          observedMetadata += TraceRow(
            dut.io.trace.bits.stage.peekValue().asBigInt.toInt,
            dut.io.trace.bits.group.peekValue().asBigInt,
            dut.io.trace.bits.index.peekValue().asBigInt,
            dut.io.trace.bits.value.peekValue().asBigInt
          )
          done = dut.io.traceLast.peek().litToBoolean
        }
        dut.clock.step()
        cycles += 1
      }
      done mustBe true
    }
    observedMetadata.toSeq mustBe expectedMetadata
  }

  "RGB Q12 CFL maps match the fixed model and stay close to libjxl-tiny" in {
    requireReferenceTools()
    val width = 8
    val height = 8
    val cases = Seq(
      "constant" -> 256,
      "gradient" -> 256,
      "checkerboard" -> 512,
      "impulse" -> 128,
      "random" -> 2048
    )
    for ((pattern, distanceQ8) <- cases) {
      val (rgb, finalRows) = generateFixture(pattern, width, height, distanceQ8)
      val blocks = expectedBlocks(rgb, finalRows, width, height, distanceQ8)
      val expected = expectedCflMaps(blocks, width, height)
      val reference = referenceCflMaps(pattern, width, height, distanceQ8)
      expected.length mustBe reference.length
      for ((fixed, oracle) <- expected.zip(reference)) {
        withClue(s"$pattern tile ${fixed.tile} Y-to-X") {
          math.abs(fixed.ytox - oracle.ytox) must be <= 2
        }
        withClue(s"$pattern tile ${fixed.tile} Y-to-B") {
          math.abs(fixed.ytob - oracle.ytob) must be <= 2
        }
      }

      val observed = collectRgbCflMaps(rgb, width, height, distanceQ8, config)
      observed mustBe expected.flatMap { map =>
        Seq(
          TraceRow(TraceStage.YtoxMap, 0, map.tile, map.ytox),
          TraceRow(TraceStage.YtobMap, 0, map.tile, map.ytob)
        )
      }
    }
  }

  "RGB estimated-CFL quantized traces, metadata, and tokens match the prepared handoff" in {
    requireReferenceTools()
    val width = 8
    val height = 8
    val distanceQ8 = 256
    val (rgb, finalRows) = generateFixture("gradient", width, height, distanceQ8)
    val blocks = expectedBlocks(rgb, finalRows, width, height, distanceQ8)

    val preparedQuantized = collectPreparedCflQuantized(blocks, width, height, distanceQ8)
    collectRgbCflQuantized(rgb, width, height, distanceQ8) mustBe preparedQuantized

    val preparedTokens = collectPreparedCflTokens(blocks, width, height, distanceQ8)
    val rgbTokens = collectRgbCflTokens(rgb, width, height, distanceQ8)
    rgbTokens mustBe preparedTokens
    collectRgbCflMetadata(rgb, width, height, distanceQ8) mustBe
      preparedTokens.filter(_.stage == TraceStage.AcMetadataTokens)
  }

  "focused RGB CFL maps preserve two tiles across a 65-pixel boundary" in {
    requireReferenceTools()
    val width = 65
    val height = 1
    val distanceQ8 = 2048
    val activeConfig = HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 8)
    val (rgb, finalRows) = generateFixture("gradient", width, height, distanceQ8)
    val blocks = expectedBlocks(rgb, finalRows, width, height, distanceQ8)
    val expected = expectedCflMaps(blocks, width, height)
    expected.length mustBe 2

    val ytox = collectRgbCflMaps(
      rgb,
      width,
      height,
      distanceQ8,
      activeConfig,
      Some(TraceStage.YtoxMap)
    )
    ytox mustBe expected.map(map => TraceRow(TraceStage.YtoxMap, 0, map.tile, map.ytox))

    val ytob = collectRgbCflMaps(
      rgb,
      width,
      height,
      distanceQ8,
      activeConfig,
      Some(TraceStage.YtobMap)
    )
    ytob mustBe expected.map(map => TraceRow(TraceStage.YtobMap, 0, map.tile, map.ytob))
  }

  "FrameAqDctOnlyBlockStage preserves nine blocks across a 65-pixel boundary" in {
    requireReferenceTools()
    val width = 65
    val height = 1
    val distanceQ8 = 2048
    val (rgb, finalRows) = generateFixture("random", width, height, distanceQ8)
    val expected = expectedBlocks(rgb, finalRows, width, height, distanceQ8)
    expected.length mustBe 9

    simulate(new FrameAqDctOnlyBlockStage(HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 8))) { dut =>
      pokeConfig(dut.io.config, width, height, distanceQ8)
      dut.io.input.valid.poke(false.B)
      dut.io.output.ready.poke(true.B)
      dut.clock.step()
      driveRgb(dut.io.input, dut.clock, rgb)
      for ((block, index) <- expected.zipWithIndex) {
        var cycles = 0
        while (!dut.io.output.valid.peek().litToBoolean && cycles < 3000) {
          dut.clock.step()
          cycles += 1
        }
        cycles must be < 3000
        dut.io.output.bits.blockIndex.expect(index.U)
        dut.io.output.bits.quantize.quant.expect(block.quant.U)
        dut.io.output.bits.blockLast.expect((index == 8).B)
        dut.clock.step()
      }
      dut.io.busy.expect(false.B)
    }
  }
}

class FrameAqDctOnlyQuantizeElaborationSpec extends AnyFreeSpec with Matchers {
  private val firtoolOpts = Array(
    "-disable-all-randomization",
    "-strip-debug-info",
    "-default-layer-specialization=enable"
  )

  private def emittedFiles(module: => RawModule, prefix: String): Map[String, String] = {
    val outputDir = Files.createTempDirectory(prefix)
    ChiselStage.emitSystemVerilogFile(
      module,
      args = Array("--target-dir", outputDir.toString),
      firtoolOpts = firtoolOpts
    )
    val stream = Files.walk(outputDir)
    try {
      stream.iterator().asScala
        .filter(path => path.getFileName.toString.endsWith(".sv"))
        .map(path => path.getFileName.toString -> Files.readString(path))
        .toMap
    } finally {
      stream.close()
    }
  }

  "the RGB adaptive quantize-to-token top elaborates one converter and three DCT blocks" in {
    val files = emittedFiles(
      new FrameAqDctOnlyQuantizeTokenTraceStage(),
      "hjxl-aq-dct-token-elaboration-"
    )

    files.keySet must contain allOf (
      "FrameAqDctOnlyQuantizeTokenTraceStage.sv",
      "FrameAqDctOnlyBlockStage.sv",
      "FrameAqDctBlockStage.sv",
      "AdaptiveInvQacQ16.sv",
      "FrameAqFinalMapPipeline.sv",
      "Dct8x8Approx.sv",
      "FramePreparedDctOnlyQuantizeTokenTraceStage.sv",
      "RgbToXybApprox.sv"
    )
    files("AdaptiveInvQacQ16.sv") must not include " / "
    val text = files.values.mkString("\n")
    val converterInstances = """RgbToXybApprox\s+\w+\s*\(""".r.findAllMatchIn(text).length
    converterInstances mustBe 1
    val dctInstances = """Dct8x8Approx\s+\w+\s*\(""".r.findAllMatchIn(text).length
    dctInstances mustBe 3
  }

  "the RGB adaptive metadata top omits DCT and reciprocal hardware" in {
    val files = emittedFiles(
      new FrameAqDctOnlyAcMetadataTokenTraceStage(),
      "hjxl-aq-metadata-elaboration-"
    )
    files.keySet must contain allOf (
      "FrameAqDctOnlyAcMetadataTokenTraceStage.sv",
      "FrameAqFinalMapPipeline.sv",
      "AqMapToRawQuant.sv",
      "FramePreparedAcMetadataTokenTraceStage.sv",
      "RgbToXybApprox.sv"
    )
    files.keySet must not contain "FrameAqDctOnlyBlockStage.sv"
    files.keySet must not contain "AdaptiveInvQacQ16.sv"
    files.keySet must not contain "Dct8x8Approx.sv"
    val text = files.values.mkString("\n")
    val converterInstances = """RgbToXybApprox\s+\w+\s*\(""".r.findAllMatchIn(text).length
    converterInstances mustBe 1
  }

  "the estimated-CFL RGB token top reuses one converter, three DCTs, and one reciprocal" in {
    val files = emittedFiles(
      new FrameAqCflDctOnlyQuantizeTokenTraceStage(),
      "hjxl-aq-cfl-token-elaboration-"
    )
    files.keySet must contain allOf (
      "FrameAqCflDctOnlyQuantizeTokenTraceStage.sv",
      "FrameAqDctBlockStage.sv",
      "FrameAqDctOnlyBlockStage.sv",
      "AdaptiveInvQacQ16.sv",
      "FramePreparedCflMapTraceStage.sv",
      "CflTileCoefficientEstimator.sv",
      "Dct8x8Approx.sv",
      "RgbToXybApprox.sv"
    )
    files("AdaptiveInvQacQ16.sv") must not include " / "
    val text = files.values.mkString("\n")
    val converterInstances = """RgbToXybApprox\s+\w+\s*\(""".r.findAllMatchIn(text).length
    converterInstances mustBe 1
    val dctInstances = """Dct8x8Approx\s+\w+\s*\(""".r.findAllMatchIn(text).length
    dctInstances mustBe 3
    val reciprocalInstances = """AdaptiveInvQacQ16\s+\w+\s*\(""".r.findAllMatchIn(text).length
    reciprocalInstances mustBe 1
  }

  "the estimated-CFL RGB metadata top omits reciprocal hardware but retains three DCTs" in {
    val files = emittedFiles(
      new FrameAqCflDctOnlyAcMetadataTokenTraceStage(),
      "hjxl-aq-cfl-metadata-elaboration-"
    )
    files.keySet must contain allOf (
      "FrameAqCflDctOnlyAcMetadataTokenTraceStage.sv",
      "FrameAqDctBlockStage.sv",
      "FramePreparedCflMapTraceStage.sv",
      "FramePreparedAcMetadataTokenTraceStage.sv",
      "Dct8x8Approx.sv",
      "RgbToXybApprox.sv"
    )
    files.keySet must not contain "AdaptiveInvQacQ16.sv"
    val text = files.values.mkString("\n")
    val converterInstances = """RgbToXybApprox\s+\w+\s*\(""".r.findAllMatchIn(text).length
    converterInstances mustBe 1
    val dctInstances = """Dct8x8Approx\s+\w+\s*\(""".r.findAllMatchIn(text).length
    dctInstances mustBe 3
  }
}
