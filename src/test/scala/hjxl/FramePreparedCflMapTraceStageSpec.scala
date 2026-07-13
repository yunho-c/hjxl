// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class FramePreparedCflMapTraceStageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim
  private val q16 = BigInt(1) << 16

  private case class PreparedBlock(coefficients: Vector[Vector[BigInt]])
  private case class TileCoefficientSample(
      index: Int,
      yQ16: BigInt,
      xQ16: BigInt,
      bQ16: BigInt
  )

  private def pokeConfig(dut: FramePreparedCflMapTraceStage, width: Int, height: Int): Unit = {
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
          BigInt(10 + blockOrdinal + channel) * q16
        } else {
          val sign = if ((coefficient + blockOrdinal) % 2 == 0) 1 else -1
          val y = BigInt(sign * (84 + blockOrdinal + coefficient % 5)) * q16
          channel match {
            case 0 => y - BigInt(3 + blockOrdinal % 4) * q16
            case 1 => y
            case _ => y - BigInt(5 + coefficient % 7) * q16
          }
        }
      }
    }
    PreparedBlock(channels)
  }

  private def roundDivBy84(value: BigInt): BigInt = {
    val absValue = value.abs
    val rounded = (absValue + 42) / 84
    if (value < 0) -rounded else rounded
  }

  private def toQ16(value: BigInt, coefficientFractionBits: Int): BigInt =
    if (coefficientFractionBits < 16) value << (16 - coefficientFractionBits)
    else if (coefficientFractionBits > 16) value >> (coefficientFractionBits - 16)
    else value

  private def expectedMultiplier(
      samples: Seq[TileCoefficientSample],
      useBWeights: Boolean,
      coefficientFractionBits: Int
  ): Int = {
    val (sumAa, sumAb) = samples.foldLeft(BigInt(0) -> BigInt(0)) {
      case ((aaAcc, abAcc), sample) =>
        val weight =
          if (useBWeights) CflWeightMatrix.BInvQ16(sample.index)
          else CflWeightMatrix.XInvQ16(sample.index)
        val modelQ16 = (toQ16(sample.yQ16, coefficientFractionBits) * weight) >> 16
        val signalQ16 = (
          toQ16(if (useBWeights) sample.bQ16 else sample.xQ16, coefficientFractionBits) * weight
        ) >> 16
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

  private def feedBlock(dut: FramePreparedCflMapTraceStage, block: PreparedBlock): Unit = {
    dut.io.input.valid.poke(true.B)
    dut.io.input.bits.quant.poke(0.U)
    dut.io.input.bits.scaleQ16.poke(0.U)
    dut.io.input.bits.invQacQ16.poke(0.U)
    dut.io.input.bits.xQmMultiplierQ16.poke(0.U)
    dut.io.input.bits.ytox.poke(0.S)
    dut.io.input.bits.ytob.poke(0.S)
    for (channel <- 0 until 3) {
      dut.io.input.bits.invDcFactorQ16(channel).poke(0.U)
      for (coefficient <- 0 until blockSize) {
        dut.io.input.bits.coefficients(channel)(coefficient).poke(block.coefficients(channel)(coefficient).S)
      }
    }
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
  }

  private def waitForTraceValid(dut: FramePreparedCflMapTraceStage, clue: String): Unit = {
    var cycles = 0
    while (dut.io.trace.valid.peekValue().asBigInt == 0 && cycles < 16384) {
      dut.clock.step()
      cycles += 1
    }
    withClue(clue) {
      cycles must be < 16384
    }
  }

  private def expectPreparedCfl(
      width: Int,
      height: Int,
      maxWidth: Int = 0,
      maxHeight: Int = 0,
      coefficientFractionBits: Int = 16,
      traceStageFilter: Option[Int] = None
  ): Unit = {
    val config = HjxlConfig(
      maxFrameWidth =
        if (maxWidth != 0) maxWidth
        else if (width > HjxlConstants.TileDim) HjxlConstants.TileDim * 2
        else HjxlConstants.TileDim,
      maxFrameHeight =
        if (maxHeight != 0) maxHeight
        else if (height > HjxlConstants.TileDim) HjxlConstants.TileDim * 2
        else HjxlConstants.TileDim
    )
    val xBlocks = (width + HjxlConstants.BlockDim - 1) / HjxlConstants.BlockDim
    val yBlocks = (height + HjxlConstants.BlockDim - 1) / HjxlConstants.BlockDim
    val xTiles = (width + HjxlConstants.TileDim - 1) / HjxlConstants.TileDim
    val yTiles = (height + HjxlConstants.TileDim - 1) / HjxlConstants.TileDim
    val coefficientShift = 16 - coefficientFractionBits
    val blocks = Seq.tabulate(xBlocks * yBlocks)(preparedBlock).map { block =>
      PreparedBlock(
        block.coefficients.map(
          _.map(value => if (coefficientShift >= 0) value >> coefficientShift else value << -coefficientShift)
        )
      )
    }
    val expected = (0 until xTiles * yTiles).flatMap { tile =>
      val samples = tileSamples(blocks, width, height, tile)
      Seq(
        (
          TraceStage.YtoxMap,
          tile,
          expectedMultiplier(
            samples,
            useBWeights = false,
            coefficientFractionBits = coefficientFractionBits
          )
        ),
        (
          TraceStage.YtobMap,
          tile,
          expectedMultiplier(
            samples,
            useBWeights = true,
            coefficientFractionBits = coefficientFractionBits
          )
        )
      ).filter(row => traceStageFilter.forall(_ == row._1))
    }

    simulate(
      new FramePreparedCflMapTraceStage(
        config,
        coefficientFractionBitsOverride = Some(coefficientFractionBits),
        traceStageFilter = traceStageFilter
      )
    ) { dut =>
      pokeConfig(dut, width, height)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for (block <- blocks) {
        feedBlock(dut, block)
      }
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)

      for (((stage, tile, value), ordinal) <- expected.zipWithIndex) {
        waitForTraceValid(dut, s"CFL trace $ordinal")
        dut.io.trace.bits.stage.expect(stage.U)
        dut.io.trace.bits.group.expect(0.U)
        dut.io.trace.bits.index.expect(tile.U)
        dut.io.trace.bits.value.expect(value.S)
        dut.io.traceLast.expect((ordinal == expected.length - 1).B)
        dut.clock.step()
      }

      dut.io.trace.valid.expect(false.B)
      var drainCycles = 0
      while (!dut.io.input.ready.peek().litToBoolean && drainCycles < 3) {
        dut.clock.step()
        drainCycles += 1
      }
      withClue("filtered map scheduler drain") {
        drainCycles must be < 3
      }
      dut.io.overflow.expect(false.B)
    }
  }

  "FramePreparedCflMapTraceStage emits one prepared tile CFL map pair" in {
    expectPreparedCfl(width = 8, height = 8)
  }

  "FramePreparedCflMapTraceStage maps raster blocks into horizontal CFL tiles" in {
    expectPreparedCfl(width = 72, height = 8)
  }

  "FramePreparedCflMapTraceStage maps raster blocks into vertical CFL tiles" in {
    expectPreparedCfl(width = 8, height = 72)
  }

  "FramePreparedCflMapTraceStage maps raster blocks into two-dimensional CFL tiles" in {
    expectPreparedCfl(width = 72, height = 72)
  }

  "FramePreparedCflMapTraceStage preserves tile counts for non-tile-aligned max dimensions" in {
    expectPreparedCfl(width = 72, height = 8, maxWidth = 72, maxHeight = 72)
    expectPreparedCfl(width = 8, height = 72, maxWidth = 72, maxHeight = 72)
  }

  "FramePreparedCflMapTraceStage rescales native Q12 coefficients for CFL estimation" in {
    expectPreparedCfl(width = 8, height = 8, coefficientFractionBits = Dct8Approx.FractionBits)
  }

  "FramePreparedCflMapTraceStage filters either map with selected-stream TLAST" in {
    expectPreparedCfl(
      width = 8,
      height = 8,
      coefficientFractionBits = Dct8Approx.FractionBits,
      traceStageFilter = Some(TraceStage.YtoxMap)
    )
    expectPreparedCfl(
      width = 8,
      height = 8,
      coefficientFractionBits = Dct8Approx.FractionBits,
      traceStageFilter = Some(TraceStage.YtobMap)
    )
  }
}
