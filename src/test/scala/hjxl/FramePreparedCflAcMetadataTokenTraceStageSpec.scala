// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class FramePreparedCflAcMetadataTokenTraceStageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim
  private val q16 = BigInt(1) << 16

  private case class PreparedBlock(rawQuant: Int, coefficients: Vector[Vector[BigInt]])
  private case class TileCoefficientSample(
      index: Int,
      yQ16: BigInt,
      xQ16: BigInt,
      bQ16: BigInt
  )

  private def configFor(width: Int, height: Int): HjxlConfig =
    HjxlConfig(
      maxFrameWidth = if (width > HjxlConstants.TileDim) HjxlConstants.TileDim * 2 else HjxlConstants.TileDim,
      maxFrameHeight = if (height > HjxlConstants.TileDim) HjxlConstants.TileDim * 2 else HjxlConstants.TileDim
    )

  private def pokeConfig(dut: FramePreparedCflAcMetadataTokenTraceStage, width: Int, height: Int): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(0.U)
    dut.io.config.fixedInvQacQ16.poke(0.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.fixedYtox.poke(0.S)
    dut.io.config.fixedYtob.poke(0.S)
    dut.io.config.enableXyb.poke(true.B)
    dut.io.config.enableDct.poke(true.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(true.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.AcMetadata.U)
  }

  private def preparedBlock(blockOrdinal: Int): PreparedBlock = {
    val channels = Vector.tabulate(3) { channel =>
      Vector.tabulate(blockSize) { coefficient =>
        if (coefficient == 0) {
          BigInt(20 + blockOrdinal + channel) * q16
        } else {
          val sign = if ((coefficient + blockOrdinal) % 2 == 0) 1 else -1
          val y = BigInt(sign * (84 + blockOrdinal + coefficient % 5)) * q16
          channel match {
            case 0 => y - BigInt(2 + blockOrdinal % 5) * q16
            case 1 => y
            case _ => y - BigInt(4 + coefficient % 7) * q16
          }
        }
      }
    }
    PreparedBlock(rawQuant = 3 + (blockOrdinal % 6), coefficients = channels)
  }

  private def roundDivBy84(value: BigInt): BigInt = {
    val absValue = value.abs
    val rounded = (absValue + 42) / 84
    if (value < 0) -rounded else rounded
  }

  private def expectedMultiplier(samples: Seq[TileCoefficientSample], useBWeights: Boolean): Int = {
    val (sumAa, sumAb) = samples.foldLeft(BigInt(0) -> BigInt(0)) {
      case ((aaAcc, abAcc), sample) =>
        val weight =
          if (useBWeights) CflWeightMatrix.BInvQ16(sample.index)
          else CflWeightMatrix.XInvQ16(sample.index)
        val modelQ16 = (sample.yQ16 * weight) >> 16
        val signalQ16 = ((if (useBWeights) sample.bQ16 else sample.xQ16) * weight) >> 16
        val aQ16 = roundDivBy84(modelQ16)
        val bQ16 = (if (useBWeights) modelQ16 else BigInt(0)) - signalQ16
        val aaTerm = (aQ16 * aQ16) >> 16
        val abTerm = (aQ16 * bQ16) >> 16
        (aaAcc + aaTerm) -> (abAcc + abTerm)
    }
    val denominator = sumAa + samples.size * CflMultiplierEstimator.RegularizerQ16
    val numerator = -sumAb
    val roundedAbs = (numerator.abs + denominator / 2) / denominator
    val rounded = if (numerator < 0) -roundedAbs else roundedAbs
    rounded.max(-128).min(127).toInt
  }

  private def tileSamples(
      blocks: Seq[PreparedBlock],
      width: Int,
      height: Int,
      tileIndex: Int
  ): Seq[TileCoefficientSample] = {
    val xBlocks = (width + HjxlConstants.BlockDim - 1) / HjxlConstants.BlockDim
    val xTiles = (width + HjxlConstants.TileDim - 1) / HjxlConstants.TileDim
    val blocksPerTile = HjxlConstants.TileDim / HjxlConstants.BlockDim
    val tileX = tileIndex % xTiles
    val tileY = tileIndex / xTiles
    val tileBaseBlockX = tileX * blocksPerTile
    val tileBaseBlockY = tileY * blocksPerTile
    val validXBlocks = math.min(blocksPerTile, xBlocks - tileBaseBlockX)
    val validYBlocks = math.min(blocksPerTile, (height + HjxlConstants.BlockDim - 1) / HjxlConstants.BlockDim - tileBaseBlockY)

    for {
      localY <- 0 until validYBlocks
      localX <- 0 until validXBlocks
      coefficient <- 0 until blockSize
    } yield {
      val block = blocks((tileBaseBlockY + localY) * xBlocks + tileBaseBlockX + localX)
      TileCoefficientSample(
        index = coefficient,
        yQ16 = block.coefficients(1)(coefficient),
        xQ16 = block.coefficients(0)(coefficient),
        bQ16 = block.coefficients(2)(coefficient)
      )
    }
  }

  private def expectedCflMaps(blocks: Seq[PreparedBlock], width: Int, height: Int): (Vector[Int], Vector[Int]) = {
    val tileCount =
      ((width + HjxlConstants.TileDim - 1) / HjxlConstants.TileDim) *
        ((height + HjxlConstants.TileDim - 1) / HjxlConstants.TileDim)
    val ytox = Vector.tabulate(tileCount) { tile =>
      expectedMultiplier(tileSamples(blocks, width, height, tile), useBWeights = false)
    }
    val ytob = Vector.tabulate(tileCount) { tile =>
      expectedMultiplier(tileSamples(blocks, width, height, tile), useBWeights = true)
    }
    (ytox, ytob)
  }

  private def clampedGradient(north: Int, west: Int, northwest: Int): Int = {
    val minValue = math.min(north, west)
    val maxValue = math.max(north, west)
    if (northwest < minValue) maxValue else if (northwest > maxValue) minValue else north + west - northwest
  }

  private def packSigned(value: Int): Int =
    if (value < 0) (-value << 1) - 1 else value << 1

  private def strategyContext(left: Int): Int =
    if (left > 11) 7 else if (left > 5) 8 else if (left > 3) 9 else 10

  private def quantFieldContext(left: Int): Int =
    if (left > 11) 3 else if (left > 5) 4 else if (left > 3) 5 else 6

  private def expectedMetadataTokens(blocks: Seq[PreparedBlock], width: Int, height: Int): Seq[(Int, Int)] = {
    val xTiles = (width + HjxlConstants.TileDim - 1) / HjxlConstants.TileDim
    val yTiles = (height + HjxlConstants.TileDim - 1) / HjxlConstants.TileDim
    val tileCount = xTiles * yTiles
    val (ytox, ytob) = expectedCflMaps(blocks, width, height)
    val tokens = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]

    for ((cflMap, context) <- Seq(ytox -> 2, ytob -> 1)) {
      for (tile <- 0 until tileCount) {
        val tileX = tile % xTiles
        val tileY = tile / xTiles
        val west = if (tileX == 0) {
          if (tileY == 0) 0 else cflMap(tile - xTiles)
        } else {
          cflMap(tile - 1)
        }
        val north = if (tileY == 0) west else cflMap(tile - xTiles)
        val northwest =
          if (tileX == 0 || tileY == 0) west else cflMap(tile - xTiles - 1)
        val residual = cflMap(tile) - clampedGradient(north, west, northwest)
        tokens += context -> packSigned(residual)
      }
    }

    for (_ <- blocks) {
      tokens += strategyContext(Tokenize.DctStrategyCode) -> packSigned(0)
    }

    var quantLeft = Tokenize.DctStrategyCode
    for (block <- blocks) {
      val current = block.rawQuant - 1
      tokens += quantFieldContext(quantLeft) -> packSigned(current - quantLeft)
      quantLeft = current
    }

    for (_ <- blocks) {
      tokens += 0 -> packSigned(Tokenize.DefaultBlockMetadata)
    }
    tokens.toSeq
  }

  private def feedBlock(dut: FramePreparedCflAcMetadataTokenTraceStage, block: PreparedBlock): Unit = {
    dut.io.input.valid.poke(true.B)
    dut.io.input.bits.quant.poke(block.rawQuant.U)
    dut.io.input.bits.scaleQ16.poke(0.U)
    dut.io.input.bits.invQacQ16.poke(0.U)
    dut.io.input.bits.xQmMultiplierQ16.poke(0.U)
    dut.io.input.bits.ytox.poke(99.S)
    dut.io.input.bits.ytob.poke((-99).S)
    for (channel <- 0 until 3) {
      dut.io.input.bits.invDcFactorQ16(channel).poke(0.U)
      for (coefficient <- 0 until blockSize) {
        dut.io.input.bits.coefficients(channel)(coefficient).poke(block.coefficients(channel)(coefficient).S)
      }
    }
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
  }

  private def waitForTraceValid(dut: FramePreparedCflAcMetadataTokenTraceStage, clue: String): Unit = {
    var cycles = 0
    while (dut.io.trace.valid.peekValue().asBigInt == 0 && cycles < 8192) {
      dut.clock.step()
      cycles += 1
    }
    withClue(clue) {
      cycles must be < 8192
    }
  }

  private def expectEstimatedMetadata(width: Int, height: Int): Unit = {
    val xBlocks = (width + HjxlConstants.BlockDim - 1) / HjxlConstants.BlockDim
    val yBlocks = (height + HjxlConstants.BlockDim - 1) / HjxlConstants.BlockDim
    val blocks = Seq.tabulate(xBlocks * yBlocks)(preparedBlock)
    val expected = expectedMetadataTokens(blocks, width, height)

    simulate(new FramePreparedCflAcMetadataTokenTraceStage(configFor(width, height))) { dut =>
      pokeConfig(dut, width, height)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for (block <- blocks) {
        feedBlock(dut, block)
      }
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)

      for (((context, value), ordinal) <- expected.zipWithIndex) {
        waitForTraceValid(dut, s"metadata token $ordinal")
        dut.io.trace.bits.stage.expect(TraceStage.AcMetadataTokens.U)
        dut.io.trace.bits.group.expect(ordinal.U)
        dut.io.trace.bits.index.expect(context.U)
        dut.io.trace.bits.value.expect(value.S)
        dut.io.traceLast.expect((ordinal == expected.length - 1).B)
        dut.clock.step()
      }

      dut.io.trace.valid.expect(false.B)
      dut.io.input.ready.expect(true.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "FramePreparedCflAcMetadataTokenTraceStage emits metadata tokens with horizontal estimated CFL maps" in {
    expectEstimatedMetadata(width = 72, height = 8)
  }

  "FramePreparedCflAcMetadataTokenTraceStage emits metadata tokens with vertical estimated CFL maps" in {
    expectEstimatedMetadata(width = 8, height = 72)
  }

  "FramePreparedCflAcMetadataTokenTraceStage emits metadata tokens with two-dimensional estimated CFL maps" in {
    expectEstimatedMetadata(width = 72, height = 72)
  }
}
