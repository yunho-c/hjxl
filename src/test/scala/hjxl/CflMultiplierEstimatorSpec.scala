// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class CflMultiplierEstimatorSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private case class WeightedSample(modelQ16: BigInt, signalQ16: BigInt, baseOne: Boolean)
  private case class CoefficientSample(
      index: Int,
      modelQ16: BigInt,
      signalQ16: BigInt,
      useBWeights: Boolean
  )
  private case class TileCoefficientSample(
      index: Int,
      yQ16: BigInt,
      xQ16: BigInt,
      bQ16: BigInt
  )

  private def expectMultiplier(
      sumAaQ16: Int,
      sumAbQ16: Int,
      sampleCount: Int,
      expected: Int
  ): Unit = {
    simulate(new CflMultiplierEstimator()) { dut =>
      dut.io.sumAaQ16.poke(sumAaQ16.U)
      dut.io.sumAbQ16.poke(sumAbQ16.S)
      dut.io.sampleCount.poke(sampleCount.U)
      dut.clock.step()
      dut.io.multiplier.expect(expected.S)
    }
  }

  private def roundDivBy84(value: BigInt): BigInt = {
    val absValue = value.abs
    val rounded = (absValue + 42) / 84
    if (value < 0) -rounded else rounded
  }

  private def expectedWeightedSums(samples: Seq[WeightedSample]): (BigInt, BigInt, Int, Int) = {
    val (sumAa, sumAb) = samples.foldLeft(BigInt(0) -> BigInt(0)) {
      case ((aaAcc, abAcc), sample) =>
        val aQ16 = roundDivBy84(sample.modelQ16)
        val bQ16 = (if (sample.baseOne) sample.modelQ16 else BigInt(0)) - sample.signalQ16
        val aaTerm = (aQ16 * aQ16) >> 16
        val abTerm = (aQ16 * bQ16) >> 16
        (aaAcc + aaTerm) -> (abAcc + abTerm)
    }
    (sumAa, sumAb, samples.size, expectedMultiplier(sumAa, sumAb, samples.size))
  }

  private def expectedMultiplier(sumAaQ16: BigInt, sumAbQ16: BigInt, sampleCount: Int): Int = {
    if (sampleCount == 0) {
      0
    } else {
      val denominator = sumAaQ16 + sampleCount * CflMultiplierEstimator.RegularizerQ16
      val numerator = -sumAbQ16
      val roundedAbs = (numerator.abs + denominator / 2) / denominator
      val rounded = if (numerator < 0) -roundedAbs else roundedAbs
      rounded.max(-128).min(127).toInt
    }
  }

  private def expectWeightedAccumulator(samples: Seq[WeightedSample]): Unit = {
    val (sumAa, sumAb, count, multiplier) = expectedWeightedSums(samples)
    simulate(new CflWeightedSumAccumulator()) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.output.ready.poke(true.B)
      dut.clock.step()

      for ((sample, index) <- samples.zipWithIndex) {
        dut.io.input.valid.poke(true.B)
        dut.io.input.bits.modelQ16.poke(sample.modelQ16.S)
        dut.io.input.bits.signalQ16.poke(sample.signalQ16.S)
        dut.io.input.bits.baseOne.poke(sample.baseOne.B)
        dut.io.input.bits.last.poke((index == samples.size - 1).B)
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.input.valid.poke(false.B)

      dut.io.output.valid.expect(true.B)
      dut.io.output.bits.sumAaQ16.expect(sumAa.U)
      dut.io.output.bits.sumAbQ16.expect(sumAb.S)
      dut.io.output.bits.sampleCount.expect(count.U)
      dut.io.output.bits.multiplier.expect(multiplier.S)
      dut.clock.step()
      dut.io.output.valid.expect(false.B)
    }
  }

  private def expectCoefficientWeight(
      index: Int,
      modelCoeffQ16: BigInt,
      signalCoeffQ16: BigInt,
      useBWeights: Boolean,
      last: Boolean
  ): Unit = {
    val weight =
      if (useBWeights) CflWeightMatrix.BInvQ16(index) else CflWeightMatrix.XInvQ16(index)
    val expectedModel = (modelCoeffQ16 * weight) >> 16
    val expectedSignal = (signalCoeffQ16 * weight) >> 16
    simulate(new CflCoefficientSampleWeight()) { dut =>
      dut.io.coefficientIndex.poke(index.U)
      dut.io.modelCoeffQ16.poke(modelCoeffQ16.S)
      dut.io.signalCoeffQ16.poke(signalCoeffQ16.S)
      dut.io.useBWeights.poke(useBWeights.B)
      dut.io.last.poke(last.B)
      dut.clock.step()
      dut.io.output.modelQ16.expect(expectedModel.S)
      dut.io.output.signalQ16.expect(expectedSignal.S)
      dut.io.output.baseOne.expect(useBWeights.B)
      dut.io.output.last.expect(last.B)
    }
  }

  private def weightCoefficientSample(sample: CoefficientSample): WeightedSample = {
    val weight =
      if (sample.useBWeights) CflWeightMatrix.BInvQ16(sample.index)
      else CflWeightMatrix.XInvQ16(sample.index)
    WeightedSample(
      modelQ16 = (sample.modelQ16 * weight) >> 16,
      signalQ16 = (sample.signalQ16 * weight) >> 16,
      baseOne = sample.useBWeights
    )
  }

  private def expectCoefficientAccumulator(samples: Seq[CoefficientSample]): Unit = {
    val weighted = samples.map(weightCoefficientSample)
    val (sumAa, sumAb, count, multiplier) = expectedWeightedSums(weighted)
    simulate(new CflCoefficientSumAccumulator()) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.output.ready.poke(true.B)
      dut.clock.step()

      for ((sample, ordinal) <- samples.zipWithIndex) {
        dut.io.input.valid.poke(true.B)
        dut.io.input.bits.coefficientIndex.poke(sample.index.U)
        dut.io.input.bits.modelCoeffQ16.poke(sample.modelQ16.S)
        dut.io.input.bits.signalCoeffQ16.poke(sample.signalQ16.S)
        dut.io.input.bits.useBWeights.poke(sample.useBWeights.B)
        dut.io.input.bits.last.poke((ordinal == samples.size - 1).B)
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.input.valid.poke(false.B)

      dut.io.output.valid.expect(true.B)
      dut.io.output.bits.sumAaQ16.expect(sumAa.U)
      dut.io.output.bits.sumAbQ16.expect(sumAb.S)
      dut.io.output.bits.sampleCount.expect(count.U)
      dut.io.output.bits.multiplier.expect(multiplier.S)
      dut.clock.step()
      dut.io.output.valid.expect(false.B)
    }
  }

  private def expectedCoefficientSums(samples: Seq[CoefficientSample]): (BigInt, BigInt, Int, Int) =
    expectedWeightedSums(samples.map(weightCoefficientSample))

  private def expectTileEstimator(samples: Seq[TileCoefficientSample]): Unit = {
    val xSamples = samples.map(sample =>
      CoefficientSample(sample.index, sample.yQ16, sample.xQ16, useBWeights = false)
    )
    val bSamples = samples.map(sample =>
      CoefficientSample(sample.index, sample.yQ16, sample.bQ16, useBWeights = true)
    )
    val (xSumAa, xSumAb, xCount, xMultiplier) = expectedCoefficientSums(xSamples)
    val (bSumAa, bSumAb, bCount, bMultiplier) = expectedCoefficientSums(bSamples)
    simulate(new CflTileCoefficientEstimator()) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.output.ready.poke(true.B)
      dut.clock.step()

      for ((sample, ordinal) <- samples.zipWithIndex) {
        dut.io.input.valid.poke(true.B)
        dut.io.input.bits.coefficientIndex.poke(sample.index.U)
        dut.io.input.bits.yCoeffQ16.poke(sample.yQ16.S)
        dut.io.input.bits.xCoeffQ16.poke(sample.xQ16.S)
        dut.io.input.bits.bCoeffQ16.poke(sample.bQ16.S)
        dut.io.input.bits.last.poke((ordinal == samples.size - 1).B)
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.input.valid.poke(false.B)

      dut.io.output.valid.expect(true.B)
      dut.io.output.bits.x.sumAaQ16.expect(xSumAa.U)
      dut.io.output.bits.x.sumAbQ16.expect(xSumAb.S)
      dut.io.output.bits.x.sampleCount.expect(xCount.U)
      dut.io.output.bits.x.multiplier.expect(xMultiplier.S)
      dut.io.output.bits.b.sumAaQ16.expect(bSumAa.U)
      dut.io.output.bits.b.sumAbQ16.expect(bSumAb.S)
      dut.io.output.bits.b.sampleCount.expect(bCount.U)
      dut.io.output.bits.b.multiplier.expect(bMultiplier.S)
      dut.clock.step()
      dut.io.output.valid.expect(false.B)
    }
  }

  private def expectTileTrace(tileIndex: Int, samples: Seq[TileCoefficientSample]): Unit = {
    val xSamples = samples.map(sample =>
      CoefficientSample(sample.index, sample.yQ16, sample.xQ16, useBWeights = false)
    )
    val bSamples = samples.map(sample =>
      CoefficientSample(sample.index, sample.yQ16, sample.bQ16, useBWeights = true)
    )
    val (_, _, _, xMultiplier) = expectedCoefficientSums(xSamples)
    val (_, _, _, bMultiplier) = expectedCoefficientSums(bSamples)
    simulate(new CflTileCoefficientTraceStage()) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for ((sample, ordinal) <- samples.zipWithIndex) {
        dut.io.input.valid.poke(true.B)
        dut.io.input.bits.tileIndex.poke(tileIndex.U)
        dut.io.input.bits.coefficient.coefficientIndex.poke(sample.index.U)
        dut.io.input.bits.coefficient.yCoeffQ16.poke(sample.yQ16.S)
        dut.io.input.bits.coefficient.xCoeffQ16.poke(sample.xQ16.S)
        dut.io.input.bits.coefficient.bCoeffQ16.poke(sample.bQ16.S)
        dut.io.input.bits.coefficient.last.poke((ordinal == samples.size - 1).B)
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.input.valid.poke(false.B)

      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.stage.expect(TraceStage.YtoxMap.U)
      dut.io.trace.bits.group.expect(0.U)
      dut.io.trace.bits.index.expect(tileIndex.U)
      dut.io.trace.bits.value.expect(xMultiplier.S)
      dut.io.traceLast.expect(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.stage.expect(TraceStage.YtobMap.U)
      dut.io.trace.bits.group.expect(0.U)
      dut.io.trace.bits.index.expect(tileIndex.U)
      dut.io.trace.bits.value.expect(bMultiplier.S)
      dut.io.traceLast.expect(true.B)
      dut.clock.step()
      dut.io.trace.valid.expect(false.B)
    }
  }

  "CflWeightMatrix preserves selected libjxl-tiny inverse weights in Q16" in {
    CflWeightMatrix.XInvQ16.length mustBe 64
    CflWeightMatrix.BInvQ16.length mustBe 64
    CflWeightMatrix.XInvQ16(0) mustBe 0
    CflWeightMatrix.BInvQ16(0) mustBe 0
    CflWeightMatrix.XInvQ16(1) mustBe 206438240
    CflWeightMatrix.XInvQ16(63) mustBe 25077688
    CflWeightMatrix.BInvQ16(1) mustBe 19264944
    CflWeightMatrix.BInvQ16(63) mustBe 932073
  }

  "CflMultiplierEstimator rounds regularized positive multipliers" in {
    val denominator = 967 + CflMultiplierEstimator.RegularizerQ16
    expectMultiplier(
      sumAaQ16 = 967,
      sumAbQ16 = -(denominator * 3 + denominator / 2),
      sampleCount = 1,
      expected = 4
    )
  }

  "CflMultiplierEstimator rounds regularized negative multipliers" in {
    val denominator = 967 + CflMultiplierEstimator.RegularizerQ16
    expectMultiplier(
      sumAaQ16 = 967,
      sumAbQ16 = denominator * 3 + denominator / 2,
      sampleCount = 1,
      expected = -4
    )
  }

  "CflMultiplierEstimator uses the CFL sample-count regularizer" in {
    val denominator = 65536 + 64 * CflMultiplierEstimator.RegularizerQ16
    expectMultiplier(
      sumAaQ16 = 65536,
      sumAbQ16 = -(denominator * 7),
      sampleCount = 64,
      expected = 7
    )
  }

  "CflMultiplierEstimator returns zero without samples" in {
    expectMultiplier(
      sumAaQ16 = 65536,
      sumAbQ16 = -65536 * 5,
      sampleCount = 0,
      expected = 0
    )
  }

  "CflMultiplierEstimator clamps to signed byte range" in {
    val denominator = 967 + CflMultiplierEstimator.RegularizerQ16
    expectMultiplier(
      sumAaQ16 = 967,
      sumAbQ16 = -(denominator * 200),
      sampleCount = 1,
      expected = 127
    )
    expectMultiplier(
      sumAaQ16 = 967,
      sumAbQ16 = denominator * 200,
      sampleCount = 1,
      expected = -128
    )
  }

  "CflWeightedSumAccumulator emits X-channel least-squares sums and multiplier" in {
    val q16 = BigInt(1) << 16
    expectWeightedAccumulator(
      Seq(
        WeightedSample(modelQ16 = 84 * q16, signalQ16 = 2 * q16, baseOne = false),
        WeightedSample(modelQ16 = -42 * q16, signalQ16 = 1 * q16, baseOne = false)
      )
    )
  }

  "CflWeightedSumAccumulator emits B-channel base-one sums and multiplier" in {
    val q16 = BigInt(1) << 16
    expectWeightedAccumulator(
      Seq(
        WeightedSample(modelQ16 = 84 * q16, signalQ16 = 80 * q16, baseOne = true),
        WeightedSample(modelQ16 = 42 * q16, signalQ16 = 41 * q16, baseOne = true),
        WeightedSample(modelQ16 = 0, signalQ16 = 3 * q16, baseOne = true)
      )
    )
  }

  "CflCoefficientSampleWeight applies X inverse weights to Q16 coefficients" in {
    val q16 = BigInt(1) << 16
    expectCoefficientWeight(
      index = 1,
      modelCoeffQ16 = 2 * q16,
      signalCoeffQ16 = -3 * q16,
      useBWeights = false,
      last = false
    )
  }

  "CflCoefficientSampleWeight applies B inverse weights and base-one metadata" in {
    val q16 = BigInt(1) << 16
    expectCoefficientWeight(
      index = 63,
      modelCoeffQ16 = -4 * q16,
      signalCoeffQ16 = 5 * q16,
      useBWeights = true,
      last = true
    )
  }

  "CflCoefficientSampleWeight zeros DC samples through the matrix" in {
    val q16 = BigInt(1) << 16
    expectCoefficientWeight(
      index = 0,
      modelCoeffQ16 = 100 * q16,
      signalCoeffQ16 = -100 * q16,
      useBWeights = false,
      last = true
    )
  }

  "CflCoefficientSumAccumulator streams X coefficient pairs through weighting and accumulation" in {
    val q16 = BigInt(1) << 16
    expectCoefficientAccumulator(
      Seq(
        CoefficientSample(index = 1, modelQ16 = 2 * q16, signalQ16 = -1 * q16, useBWeights = false),
        CoefficientSample(
          index = 7,
          modelQ16 = -3 * q16,
          signalQ16 = 2 * q16,
          useBWeights = false
        ),
        CoefficientSample(
          index = 0,
          modelQ16 = 20 * q16,
          signalQ16 = 9 * q16,
          useBWeights = false
        )
      )
    )
  }

  "CflCoefficientSumAccumulator streams B coefficient pairs through weighting and accumulation" in {
    val q16 = BigInt(1) << 16
    expectCoefficientAccumulator(
      Seq(
        CoefficientSample(
          index = 1,
          modelQ16 = 84 * q16,
          signalQ16 = 80 * q16,
          useBWeights = true
        ),
        CoefficientSample(
          index = 8,
          modelQ16 = -42 * q16,
          signalQ16 = -38 * q16,
          useBWeights = true
        ),
        CoefficientSample(
          index = 63,
          modelQ16 = 7 * q16,
          signalQ16 = 6 * q16,
          useBWeights = true
        )
      )
    )
  }

  "CflTileCoefficientEstimator emits Y-to-X and Y-to-B multipliers from one coefficient stream" in {
    val q16 = BigInt(1) << 16
    expectTileEstimator(
      Seq(
        TileCoefficientSample(index = 0, yQ16 = 10 * q16, xQ16 = 5 * q16, bQ16 = 7 * q16),
        TileCoefficientSample(
          index = 1,
          yQ16 = 84 * q16,
          xQ16 = 80 * q16,
          bQ16 = 76 * q16
        ),
        TileCoefficientSample(
          index = 8,
          yQ16 = -42 * q16,
          xQ16 = -40 * q16,
          bQ16 = -39 * q16
        ),
        TileCoefficientSample(index = 63, yQ16 = 7 * q16, xQ16 = 6 * q16, bQ16 = 5 * q16)
      )
    )
  }

  "CflTileCoefficientTraceStage emits Ytox and Ytob tile trace rows" in {
    val q16 = BigInt(1) << 16
    expectTileTrace(
      tileIndex = 3,
      Seq(
        TileCoefficientSample(index = 0, yQ16 = 10 * q16, xQ16 = 5 * q16, bQ16 = 7 * q16),
        TileCoefficientSample(
          index = 1,
          yQ16 = 84 * q16,
          xQ16 = 80 * q16,
          bQ16 = 76 * q16
        ),
        TileCoefficientSample(
          index = 8,
          yQ16 = -42 * q16,
          xQ16 = -40 * q16,
          bQ16 = -39 * q16
        ),
        TileCoefficientSample(index = 63, yQ16 = 7 * q16, xQ16 = 6 * q16, bQ16 = 5 * q16)
      )
    )
  }
}
