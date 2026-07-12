// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class FramePreparedCflDctOnlyQuantizeTraceStageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim
  private val q16 = BigInt(1) << 16
  private val recordsPerBlock = 3 * blockSize + 3 + 3

  private case class PreparedBlock(
      quant: Int,
      scaleQ16: Int,
      invQacQ16: Int,
      invDcFactorQ16: Vector[Int],
      xQmMultiplierQ16: Int,
      coefficients: Vector[Vector[BigInt]]
  )
  private case class TileCoefficientSample(
      index: Int,
      yQ16: BigInt,
      xQ16: BigInt,
      bQ16: BigInt
  )
  private case class ExpectedTrace(stage: Int, group: Int, index: Int, value: Int)

  private def configFor(width: Int, height: Int): HjxlConfig =
    HjxlConfig(
      maxFrameWidth = if (width > HjxlConstants.TileDim) HjxlConstants.TileDim * 2 else HjxlConstants.TileDim,
      maxFrameHeight = if (height > HjxlConstants.TileDim) HjxlConstants.TileDim * 2 else HjxlConstants.TileDim
    )

  private def pokeConfig(dut: FramePreparedCflDctOnlyQuantizeTraceStage, width: Int, height: Int): Unit = {
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
    dut.io.config.enableTokenize.poke(false.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.Dc.U)
  }

  private def preparedBlock(blockOrdinal: Int): PreparedBlock = {
    val channels = Vector.tabulate(3) { channel =>
      Vector.tabulate(blockSize) { coefficient =>
        if (coefficient == 0) {
          BigInt(18 + blockOrdinal * 3 + channel) * q16
        } else {
          val sign = if ((coefficient + blockOrdinal) % 2 == 0) 1 else -1
          val y = BigInt(sign * (84 + blockOrdinal + coefficient % 5)) * q16
          channel match {
            case 0 => y - BigInt(2 + blockOrdinal % 5) * q16
            case 1 => y
            case _ => y - BigInt(5 + coefficient % 7) * q16
          }
        }
      }
    }
    val scale = 256 + blockOrdinal * 3
    val quant = 4 + blockOrdinal % 5
    PreparedBlock(
      quant = quant,
      scaleQ16 = scale,
      invQacQ16 = QuantizeDct8x8Block.invQacQ16For(scale, quant),
      invDcFactorQ16 = QuantizeDct8x8Block.DefaultInvDcFactorQ16.toVector,
      xQmMultiplierQ16 = QuantizeDct8x8Block.DefaultQmMultiplierQ16,
      coefficients = channels
    )
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
    val yBlocks = (height + HjxlConstants.BlockDim - 1) / HjxlConstants.BlockDim
    val xTiles = (width + HjxlConstants.TileDim - 1) / HjxlConstants.TileDim
    val blocksPerTile = HjxlConstants.TileDim / HjxlConstants.BlockDim
    val tileX = tileIndex % xTiles
    val tileY = tileIndex / xTiles
    val tileBaseBlockX = tileX * blocksPerTile
    val tileBaseBlockY = tileY * blocksPerTile
    val validXBlocks = math.min(blocksPerTile, xBlocks - tileBaseBlockX)
    val validYBlocks = math.min(blocksPerTile, yBlocks - tileBaseBlockY)

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

  private def cflMaps(blocks: Seq[PreparedBlock], width: Int, height: Int): (Vector[Int], Vector[Int]) = {
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

  private def blockTile(width: Int, blockOrdinal: Int): Int = {
    val xBlocks = (width + HjxlConstants.BlockDim - 1) / HjxlConstants.BlockDim
    val blocksPerTile = HjxlConstants.TileDim / HjxlConstants.BlockDim
    val blockX = blockOrdinal % xBlocks
    val blockY = blockOrdinal / xBlocks
    val xTiles = (width + HjxlConstants.TileDim - 1) / HjxlConstants.TileDim
    (blockY / blocksPerTile) * xTiles + (blockX / blocksPerTile)
  }

  private def expectedBlockTrace(
      block: PreparedBlock,
      blockOrdinal: Int,
      ytox: Int,
      ytob: Int
  ): Seq[ExpectedTrace] = {
    var expected = Seq.empty[ExpectedTrace]
    val config = configFor(width = HjxlConstants.TileDim, height = HjxlConstants.TileDim)
    simulate(new DctOnlyQuantizeBlock(config, config.preparedDctCoefficientFractionBits)) { dut =>
      dut.io.input.valid.poke(true.B)
      dut.io.output.ready.poke(true.B)
      dut.io.input.bits.quant.poke(block.quant.U)
      dut.io.input.bits.scaleQ16.poke(block.scaleQ16.U)
      dut.io.input.bits.invQacQ16.poke(block.invQacQ16.U)
      dut.io.input.bits.xQmMultiplierQ16.poke(block.xQmMultiplierQ16.U)
      dut.io.input.bits.ytox.poke(ytox.S)
      dut.io.input.bits.ytob.poke(ytob.S)
      for (channel <- 0 until 3) {
        dut.io.input.bits.invDcFactorQ16(channel).poke(block.invDcFactorQ16(channel).U)
        for (coefficient <- 0 until blockSize) {
          dut.io.input.bits.coefficients(channel)(coefficient).poke(block.coefficients(channel)(coefficient).S)
        }
      }
      dut.io.output.valid.expect(true.B)

      val traces = scala.collection.mutable.ArrayBuffer.empty[ExpectedTrace]
      for (channel <- 0 until 3) {
        for (coefficient <- 0 until blockSize) {
          traces += ExpectedTrace(
            TraceStage.QuantizedAc,
            blockOrdinal,
            channel * blockSize + coefficient,
            dut.io.output.bits.quantizedAc(channel)(coefficient).peekValue().asBigInt.toInt
          )
        }
      }
      for (channel <- 0 until 3) {
        traces += ExpectedTrace(
          TraceStage.QuantDc,
          blockOrdinal,
          channel,
          dut.io.output.bits.quantizedDc(channel).peekValue().asBigInt.toInt
        )
      }
      for (channel <- 0 until 3) {
        traces += ExpectedTrace(
          TraceStage.NumNonzeros,
          blockOrdinal,
          channel,
          dut.io.output.bits.numNonzeros(channel).peekValue().asBigInt.toInt
        )
      }
      expected = traces.toSeq
    }
    expected
  }

  private def expectedTraces(blocks: Seq[PreparedBlock], width: Int, height: Int): Seq[ExpectedTrace] = {
    val (ytox, ytob) = cflMaps(blocks, width, height)
    blocks.zipWithIndex.flatMap { case (block, blockOrdinal) =>
      val tile = blockTile(width, blockOrdinal)
      expectedBlockTrace(block, blockOrdinal, ytox(tile), ytob(tile))
    }
  }

  private def feedBlock(dut: FramePreparedCflDctOnlyQuantizeTraceStage, block: PreparedBlock): Unit = {
    dut.io.input.valid.poke(true.B)
    dut.io.input.bits.quant.poke(block.quant.U)
    dut.io.input.bits.scaleQ16.poke(block.scaleQ16.U)
    dut.io.input.bits.invQacQ16.poke(block.invQacQ16.U)
    dut.io.input.bits.xQmMultiplierQ16.poke(block.xQmMultiplierQ16.U)
    dut.io.input.bits.ytox.poke(99.S)
    dut.io.input.bits.ytob.poke((-99).S)
    for (channel <- 0 until 3) {
      dut.io.input.bits.invDcFactorQ16(channel).poke(block.invDcFactorQ16(channel).U)
      for (coefficient <- 0 until blockSize) {
        dut.io.input.bits.coefficients(channel)(coefficient).poke(block.coefficients(channel)(coefficient).S)
      }
    }
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
  }

  private def waitForTraceValid(dut: FramePreparedCflDctOnlyQuantizeTraceStage, clue: String): Unit = {
    var cycles = 0
    while (dut.io.trace.valid.peekValue().asBigInt == 0 && cycles < 8192) {
      dut.clock.step()
      cycles += 1
    }
    withClue(clue) {
      cycles must be < 8192
    }
  }

  private def expectEstimatedQuantize(width: Int, height: Int): Unit = {
    val xBlocks = (width + HjxlConstants.BlockDim - 1) / HjxlConstants.BlockDim
    val yBlocks = (height + HjxlConstants.BlockDim - 1) / HjxlConstants.BlockDim
    val blocks = Seq.tabulate(xBlocks * yBlocks)(preparedBlock)
    val expected = expectedTraces(blocks, width, height)
    expected.length mustBe blocks.length * recordsPerBlock

    simulate(new FramePreparedCflDctOnlyQuantizeTraceStage(configFor(width, height))) { dut =>
      pokeConfig(dut, width, height)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for (block <- blocks) {
        feedBlock(dut, block)
      }
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)

      for ((trace, ordinal) <- expected.zipWithIndex) {
        waitForTraceValid(dut, s"quant trace $ordinal")
        dut.io.trace.bits.stage.expect(trace.stage.U)
        dut.io.trace.bits.group.expect(trace.group.U)
        dut.io.trace.bits.index.expect(trace.index.U)
        dut.io.trace.bits.value.expect(trace.value.S)
        dut.io.traceLast.expect((ordinal == expected.length - 1).B)
        dut.clock.step()
      }

      dut.io.trace.valid.expect(false.B)
      dut.io.input.ready.expect(true.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "FramePreparedCflDctOnlyQuantizeTraceStage quantizes with horizontal estimated CFL maps" in {
    expectEstimatedQuantize(width = 72, height = 8)
  }

  "FramePreparedCflDctOnlyQuantizeTraceStage quantizes with vertical estimated CFL maps" in {
    expectEstimatedQuantize(width = 8, height = 72)
  }
}
