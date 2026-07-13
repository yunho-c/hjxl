// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class FramePreparedCflDctOnlyQuantizeTokenTraceStageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim
  private val q16 = BigInt(1) << 16

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

  private def pokeConfig(dut: FramePreparedDctOnlyQuantizeTokenTraceStage, width: Int, height: Int): Unit = {
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
    dut.io.config.tokenSelect.poke(TokenTraceSelect.AcTokens.U)
  }

  private def pokeConfig(dut: FramePreparedCflDctOnlyQuantizeTokenTraceStage, width: Int, height: Int): Unit = {
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
    dut.io.config.tokenSelect.poke(TokenTraceSelect.AcTokens.U)
  }

  private def pokeInputBits(
      input: DctOnlyQuantizeBlockInput,
      block: PreparedBlock,
      ytox: Int,
      ytob: Int
  ): Unit = {
    input.quant.poke(block.quant.U)
    input.scaleQ16.poke(block.scaleQ16.U)
    input.invQacQ16.poke(block.invQacQ16.U)
    input.xQmMultiplierQ16.poke(block.xQmMultiplierQ16.U)
    input.ytox.poke(ytox.S)
    input.ytob.poke(ytob.S)
    for (channel <- 0 until 3) {
      input.invDcFactorQ16(channel).poke(block.invDcFactorQ16(channel).U)
      for (coefficient <- 0 until blockSize) {
        input.coefficients(channel)(coefficient).poke(block.coefficients(channel)(coefficient).S)
      }
    }
  }

  private def collectDirect(
      blocks: Seq[PreparedBlock],
      width: Int,
      height: Int,
      ytox: Seq[Int],
      ytob: Seq[Int]
  ): Seq[ExpectedTrace] = {
    val traces = scala.collection.mutable.ArrayBuffer.empty[ExpectedTrace]
    val config = configFor(width, height)
    simulate(new FramePreparedDctOnlyQuantizeTokenTraceStage(config)) { dut =>
      pokeConfig(dut, width, height)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for ((block, ordinal) <- blocks.zipWithIndex) {
        val tile = blockTile(width, ordinal)
        dut.io.input.valid.poke(true.B)
        pokeInputBits(dut.io.input.bits, block, ytox(tile), ytob(tile))
        var cycles = 0
        while (dut.io.input.ready.peekValue().asBigInt == 0 && cycles < blockSize + 2) {
          dut.clock.step()
          cycles += 1
        }
        withClue(s"direct prepared block $ordinal input readiness") {
          cycles must be < blockSize + 2
        }
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      collectUntilLast(
        valid = dut.io.trace.valid,
        last = dut.io.traceLast,
        clock = dut.clock,
        peek = () =>
          ExpectedTrace(
            dut.io.trace.bits.stage.peekValue().asBigInt.toInt,
            dut.io.trace.bits.group.peekValue().asBigInt.toInt,
            dut.io.trace.bits.index.peekValue().asBigInt.toInt,
            dut.io.trace.bits.value.peekValue().asBigInt.toInt
          ),
        traces = traces
      )
      dut.io.overflow.expect(false.B)
    }
    traces.toSeq
  }

  private def collectEstimated(blocks: Seq[PreparedBlock], width: Int, height: Int): Seq[ExpectedTrace] = {
    val traces = scala.collection.mutable.ArrayBuffer.empty[ExpectedTrace]
    val config = configFor(width, height)
    simulate(new FramePreparedCflDctOnlyQuantizeTokenTraceStage(config)) { dut =>
      pokeConfig(dut, width, height)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for (block <- blocks) {
        dut.io.input.valid.poke(true.B)
        pokeInputBits(dut.io.input.bits, block, ytox = 99, ytob = -99)
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      collectUntilLast(
        valid = dut.io.trace.valid,
        last = dut.io.traceLast,
        clock = dut.clock,
        peek = () =>
          ExpectedTrace(
            dut.io.trace.bits.stage.peekValue().asBigInt.toInt,
            dut.io.trace.bits.group.peekValue().asBigInt.toInt,
            dut.io.trace.bits.index.peekValue().asBigInt.toInt,
            dut.io.trace.bits.value.peekValue().asBigInt.toInt
          ),
        traces = traces
      )
      dut.io.overflow.expect(false.B)
    }
    traces.toSeq
  }

  private def collectUntilLast(
      valid: Bool,
      last: Bool,
      clock: Clock,
      peek: () => ExpectedTrace,
      traces: scala.collection.mutable.ArrayBuffer[ExpectedTrace]
  ): Unit = {
    var done = false
    var records = 0
    var idleCycles = 0
    while (!done && records < 10000 && idleCycles < 8192) {
      if (valid.peekValue().asBigInt != 0) {
        traces += peek()
        done = last.peekValue().asBigInt != 0
        records += 1
        idleCycles = 0
      } else {
        idleCycles += 1
      }
      clock.step()
    }
    records must be < 10000
    idleCycles must be < 8192
    done mustBe true
  }

  private def expectEstimatedMatchesDirect(width: Int, height: Int): Unit = {
    val xBlocks = (width + HjxlConstants.BlockDim - 1) / HjxlConstants.BlockDim
    val yBlocks = (height + HjxlConstants.BlockDim - 1) / HjxlConstants.BlockDim
    val blocks = Seq.tabulate(xBlocks * yBlocks)(preparedBlock)
    val (ytox, ytob) = cflMaps(blocks, width, height)

    val expected = collectDirect(blocks, width, height, ytox, ytob)
    val observed = collectEstimated(blocks, width, height)
    observed mustBe expected
  }

  "FramePreparedCflDctOnlyQuantizeTokenTraceStage matches direct token wrapper fed horizontal estimated CFL maps" in {
    expectEstimatedMatchesDirect(width = 72, height = 8)
  }

  "FramePreparedCflDctOnlyQuantizeTokenTraceStage matches direct token wrapper fed vertical estimated CFL maps" in {
    expectEstimatedMatchesDirect(width = 8, height = 72)
  }
}
