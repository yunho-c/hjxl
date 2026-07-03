// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class QuantizeDct8x8BlockSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val blockDim = HjxlConstants.BlockDim
  private val blockSize = blockDim * blockDim
  private val halfQ12 = BigInt(1) << (Dct8Approx.FractionBits - 1)

  private def thresholdIndex(coefficient: Int): Int = {
    val x = coefficient % blockDim
    val y = coefficient / blockDim
    (if (y >= blockDim / 2) 2 else 0) + (if (x >= blockDim / 2) 1 else 0)
  }

  private def invTable(channel: Int): Seq[Long] = channel match {
    case 0 => QuantizeDct8x8Block.DctXInvQ16
    case 1 => QuantizeDct8x8Block.DctYInvQ16
    case 2 => QuantizeDct8x8Block.DctBInvQ16
  }

  private def thresholdTable(channel: Int): Seq[Int] = channel match {
    case 0 => QuantizeDct8x8Block.ThresholdXQ12
    case 1 => QuantizeDct8x8Block.ThresholdYQ12
    case 2 => QuantizeDct8x8Block.ThresholdBQ12
  }

  private def roundQ12(value: BigInt): BigInt =
    if (value >= 0) (value + halfQ12) >> Dct8Approx.FractionBits
    else -((value.abs + halfQ12) >> Dct8Approx.FractionBits)

  private def model(
      coefficients: Seq[Int],
      channel: Int,
      quant: Int,
      scaleQ16: Int,
      qmMultiplierQ16: Int
  ): Seq[Int] = {
    val inv = invTable(channel)
    val thresholds = thresholdTable(channel)
    coefficients.zipWithIndex.map { case (coefficient, i) =>
      val weightedQ12 =
        (BigInt(coefficient) * BigInt(inv(i))) >> QuantizeDct8x8Block.InvMatrixFractionBits
      val valueQ12 =
        (weightedQ12 * BigInt(scaleQ16) * BigInt(quant) * BigInt(qmMultiplierQ16)) >>
          (QuantizeDct8x8Block.ScaleFractionBits + QuantizeDct8x8Block.QmFractionBits)
      if (valueQ12.abs >= thresholds(thresholdIndex(i))) roundQ12(valueQ12).toInt else 0
    }
  }

  private def adjustedYQ16(value: Int): BigInt = {
    val absValue = math.abs(value)
    if (absValue == 0) {
      BigInt(0)
    } else {
      val magnitude =
        if (absValue == 1) BigInt(QuantizeDct8x8Block.QuantBiasYQ16)
        else (BigInt(absValue) << QuantizeDct8x8Block.BiasFractionBits) -
          BigInt(QuantizeDct8x8Block.QuantBiasOtherNumeratorQ16) / BigInt(absValue)
      if (value < 0) -magnitude else magnitude
    }
  }

  private def yRoundtripModel(
      coefficients: Seq[Int],
      quant: Int,
      scaleQ16: Int,
      invQacQ16: Int
  ): (Seq[Int], Seq[Int], Int) = {
    val quantized = model(
      coefficients,
      channel = 1,
      quant,
      scaleQ16,
      QuantizeDct8x8Block.DefaultQmMultiplierQ16
    )
    val shift =
      QuantizeDct8x8Block.BiasFractionBits +
        QuantizeDct8x8Block.WeightFractionBits +
        QuantizeDct8x8Block.InvQacFractionBits -
        Dct8Approx.FractionBits
    val roundtripped = quantized.zipWithIndex.map { case (value, index) =>
      ((adjustedYQ16(value) * BigInt(QuantizeDct8x8Block.DctYWeightsQ32(index)) * BigInt(invQacQ16)) >> shift).toInt
    }
    val numNonzeros = quantized.zipWithIndex.count { case (value, index) => index != 0 && value != 0 }
    (quantized, roundtripped, numNonzeros)
  }

  private def chromaResidualModel(
      coefficients: Seq[Int],
      reconstructedY: Seq[Int],
      channel: Int,
      cflMultiplier: Int,
      quant: Int,
      scaleQ16: Int,
      qmMultiplierQ16: Int
  ): (Seq[Int], Seq[Int], Int) = {
    val base = if (channel == 2) BigInt(1) << QuantizeDct8x8Block.CflFactorFractionBits else BigInt(0)
    val cflFactorQ16 =
      base +
        (BigInt(cflMultiplier) * (BigInt(1) << QuantizeDct8x8Block.CflFactorFractionBits)) /
        BigInt(QuantizeDct8x8Block.ColorFactorDenominator)
    val residual = coefficients.zip(reconstructedY).map { case (coefficient, y) =>
      val predicted = (cflFactorQ16 * BigInt(y)) >> QuantizeDct8x8Block.CflFactorFractionBits
      (BigInt(coefficient) - predicted).toInt
    }
    val quantized = model(residual, channel, quant, scaleQ16, qmMultiplierQ16)
    val numNonzeros = quantized.zipWithIndex.count { case (value, index) => index != 0 && value != 0 }
    (residual, quantized, numNonzeros)
  }

  private def roundFixedToInt(value: BigInt, fractionBits: Int): BigInt = {
    val half = BigInt(1) << (fractionBits - 1)
    if (value >= 0) (value + half) >> fractionBits
    else -((value.abs + half) >> fractionBits)
  }

  private def quantizeDcModel(
      coefficient: Int,
      channel: Int,
      invDcFactorQ16: Int,
      quantizedYDc: Int
  ): Int = {
    val base = BigInt(coefficient) * BigInt(invDcFactorQ16)
    val bCorrection =
      if (channel == 2) {
        BigInt(quantizedYDc) *
          BigInt(QuantizeDct8x8Block.BQuantDcFromYFactorQ16) <<
          Dct8Approx.FractionBits
      } else {
        BigInt(0)
      }
    roundFixedToInt(
      base - bCorrection,
      QuantizeDct8x8Block.DcProductFractionBits
    ).toInt
  }

  private case class DctOnlyBlockModel(
      quantizedAc: Seq[Seq[Int]],
      quantizedDc: Seq[Int],
      numNonzeros: Seq[Int]
  )

  private def dctOnlyBlockModel(
      coefficients: Seq[Seq[Int]],
      quant: Int,
      scaleQ16: Int,
      invQacQ16: Int,
      invDcFactorQ16: Seq[Int],
      xQmMultiplierQ16: Int,
      ytox: Int,
      ytob: Int
  ): DctOnlyBlockModel = {
    val (quantizedY, reconstructedY, yNonzeros) =
      yRoundtripModel(coefficients(1), quant, scaleQ16, invQacQ16)
    val (xResidual, quantizedX, xNonzeros) =
      chromaResidualModel(
        coefficients(0),
        reconstructedY,
        channel = 0,
        ytox,
        quant,
        scaleQ16,
        xQmMultiplierQ16
      )
    val (bResidual, quantizedB, bNonzeros) =
      chromaResidualModel(
        coefficients(2),
        reconstructedY,
        channel = 2,
        ytob,
        quant,
        scaleQ16,
        QuantizeDct8x8Block.DefaultQmMultiplierQ16
      )
    val yDc = quantizeDcModel(coefficients(1)(0), 1, invDcFactorQ16(1), 0)
    val xDc = quantizeDcModel(xResidual(0), 0, invDcFactorQ16(0), yDc)
    val bDc = quantizeDcModel(bResidual(0), 2, invDcFactorQ16(2), yDc)
    DctOnlyBlockModel(
      quantizedAc = Seq(quantizedX, quantizedY, quantizedB),
      quantizedDc = Seq(xDc, yDc, bDc),
      numNonzeros = Seq(xNonzeros, yNonzeros, bNonzeros)
    )
  }

  private def exercise(
      dut: QuantizeDct8x8Block,
      coefficients: Seq[Int],
      channel: Int,
      quant: Int,
      scaleQ16: Int,
      qmMultiplierQ16: Int
  ): Unit = {
    val expected = model(coefficients, channel, quant, scaleQ16, qmMultiplierQ16)
    dut.io.input.valid.poke(true.B)
    dut.io.output.ready.poke(true.B)
    dut.io.input.bits.channel.poke(channel.U)
    dut.io.input.bits.quant.poke(quant.U)
    dut.io.input.bits.scaleQ16.poke(scaleQ16.U)
    dut.io.input.bits.qmMultiplierQ16.poke(qmMultiplierQ16.U)
    for (i <- 0 until blockSize) {
      dut.io.input.bits.coefficients(i).poke(coefficients(i).S)
    }

    dut.io.output.valid.expect(true.B)
    for (i <- 0 until blockSize) {
      dut.io.output.bits.quantized(i).expect(expected(i).S)
    }
    dut.io.output.bits.numNonzeros.expect(expected.zipWithIndex.count { case (value, index) => index != 0 && value != 0 }.U)
    dut.clock.step()
  }

  private def feedTraceInput(
      dut: DctQuantizeTraceStage,
      coefficients: Seq[Int],
      group: Int,
      channel: Int,
      quant: Int,
      scaleQ16: Int,
      qmMultiplierQ16: Int
  ): Unit = {
    dut.io.input.valid.poke(true.B)
    dut.io.input.bits.group.poke(group.U)
    dut.io.input.bits.quantize.channel.poke(channel.U)
    dut.io.input.bits.quantize.quant.poke(quant.U)
    dut.io.input.bits.quantize.scaleQ16.poke(scaleQ16.U)
    dut.io.input.bits.quantize.qmMultiplierQ16.poke(qmMultiplierQ16.U)
    for (i <- 0 until blockSize) {
      dut.io.input.bits.quantize.coefficients(i).poke(coefficients(i).S)
    }
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
    dut.io.input.valid.poke(false.B)
  }

  "QuantizeDct8x8Block quantizes DCT-only AC coefficients and counts nonzeros" in {
    val coefficients = Seq.tabulate(blockSize) { i =>
      if (i == 0) 18000 else ((i % 13) - 6) * (700 + i * 17)
    }

    simulate(new QuantizeDct8x8Block()) { dut =>
      exercise(
        dut,
        coefficients,
        channel = 1,
        quant = 5,
        scaleQ16 = 7340,
        qmMultiplierQ16 = QuantizeDct8x8Block.DefaultQmMultiplierQ16
      )
      exercise(
        dut,
        coefficients.map(_ / 2),
        channel = 0,
        quant = 6,
        scaleQ16 = 7340,
        qmMultiplierQ16 = (QuantizeDct8x8Block.DefaultQmMultiplierQ16 * 5) / 4
      )
      exercise(
        dut,
        coefficients.map(_ * 2),
        channel = 2,
        quant = 4,
        scaleQ16 = 14680,
        qmMultiplierQ16 = QuantizeDct8x8Block.DefaultQmMultiplierQ16
      )
    }
  }

  "DctQuantizeTraceStage emits quantized AC coefficients followed by nonzero count" in {
    val coefficients = Seq.tabulate(blockSize) { i =>
      if (i == 0) -21000 else ((i % 9) - 4) * (500 + i * 23)
    }
    val group = 7
    val channel = 2
    val quant = 4
    val scaleQ16 = 14680
    val qmMultiplierQ16 = QuantizeDct8x8Block.DefaultQmMultiplierQ16
    val expected = model(coefficients, channel, quant, scaleQ16, qmMultiplierQ16)
    val expectedNonzeros = expected.zipWithIndex.count { case (value, index) => index != 0 && value != 0 }

    simulate(new DctQuantizeTraceStage()) { dut =>
      dut.io.trace.ready.poke(false.B)
      feedTraceInput(dut, coefficients, group, channel, quant, scaleQ16, qmMultiplierQ16)
      dut.io.input.ready.expect(false.B)
      dut.io.trace.valid.expect(true.B)

      dut.io.trace.ready.poke(true.B)
      for (i <- 0 until blockSize) {
        dut.io.trace.valid.expect(true.B)
        dut.io.trace.bits.stage.expect(TraceStage.QuantizedAc.U)
        dut.io.trace.bits.group.expect(group.U)
        dut.io.trace.bits.index.expect((channel * blockSize + i).U)
        dut.io.trace.bits.value.expect(expected(i).S)
        dut.clock.step()
      }
      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.stage.expect(TraceStage.NumNonzeros.U)
      dut.io.trace.bits.group.expect(group.U)
      dut.io.trace.bits.index.expect(channel.U)
      dut.io.trace.bits.value.expect(expectedNonzeros.S)
      dut.clock.step()

      dut.io.trace.valid.expect(false.B)
      dut.io.input.ready.expect(true.B)
    }
  }

  "QuantizeRoundtripYDct8x8Block reconstructs quantized Y coefficients" in {
    val coefficients = Seq.tabulate(blockSize) { i =>
      if (i == 0) 24000 else ((i % 17) - 8) * (900 + i * 29)
    }
    val quant = 5
    val scaleQ16 = 7340
    val invQacQ16 = 117043
    val (expectedQuantized, expectedRoundtrip, expectedNonzeros) =
      yRoundtripModel(coefficients, quant, scaleQ16, invQacQ16)

    simulate(new QuantizeRoundtripYDct8x8Block()) { dut =>
      dut.io.input.valid.poke(true.B)
      dut.io.output.ready.poke(true.B)
      dut.io.input.bits.quant.poke(quant.U)
      dut.io.input.bits.scaleQ16.poke(scaleQ16.U)
      dut.io.input.bits.invQacQ16.poke(invQacQ16.U)
      for (i <- 0 until blockSize) {
        dut.io.input.bits.coefficients(i).poke(coefficients(i).S)
      }

      dut.io.output.valid.expect(true.B)
      for (i <- 0 until blockSize) {
        dut.io.output.bits.quantized(i).expect(expectedQuantized(i).S)
        dut.io.output.bits.roundtripped(i).expect(expectedRoundtrip(i).S)
      }
      dut.io.output.bits.numNonzeros.expect(expectedNonzeros.U)
    }
  }

  "QuantizeChromaResidualDct8x8Block subtracts CFL prediction before X/B quantization" in {
    val xCoefficients = Seq.tabulate(blockSize) { i =>
      if (i == 0) 15000 else ((i % 11) - 5) * (620 + i * 19)
    }
    val bCoefficients = Seq.tabulate(blockSize) { i =>
      if (i == 0) -18000 else ((i % 7) - 3) * (740 + i * 31)
    }
    val reconstructedY = Seq.tabulate(blockSize) { i =>
      if (i == 0) 12000 else ((i % 13) - 6) * (210 + i * 11)
    }
    val scaleQ16 = 7340
    val quant = 5
    val xQmMultiplier = (QuantizeDct8x8Block.DefaultQmMultiplierQ16 * 5) / 4
    val bQmMultiplier = QuantizeDct8x8Block.DefaultQmMultiplierQ16
    val (expectedXResidual, expectedXQuantized, expectedXNonzeros) =
      chromaResidualModel(
        xCoefficients,
        reconstructedY,
        channel = 0,
        cflMultiplier = -4,
        quant,
        scaleQ16,
        xQmMultiplier
      )
    val (expectedBResidual, expectedBQuantized, expectedBNonzeros) =
      chromaResidualModel(
        bCoefficients,
        reconstructedY,
        channel = 2,
        cflMultiplier = -21,
        quant,
        scaleQ16,
        bQmMultiplier
      )

    simulate(new QuantizeChromaResidualDct8x8Block()) { dut =>
      def expectCase(
          coefficients: Seq[Int],
          channel: Int,
          cflMultiplier: Int,
          qmMultiplierQ16: Int,
          expectedResidual: Seq[Int],
          expectedQuantized: Seq[Int],
          expectedNonzeros: Int
      ): Unit = {
        dut.io.input.valid.poke(true.B)
        dut.io.output.ready.poke(true.B)
        dut.io.input.bits.channel.poke(channel.U)
        dut.io.input.bits.cflMultiplier.poke(cflMultiplier.S)
        dut.io.input.bits.quant.poke(quant.U)
        dut.io.input.bits.scaleQ16.poke(scaleQ16.U)
        dut.io.input.bits.qmMultiplierQ16.poke(qmMultiplierQ16.U)
        for (i <- 0 until blockSize) {
          dut.io.input.bits.coefficients(i).poke(coefficients(i).S)
          dut.io.input.bits.reconstructedY(i).poke(reconstructedY(i).S)
        }

        dut.io.output.valid.expect(true.B)
        for (i <- 0 until blockSize) {
          dut.io.output.bits.residual(i).expect(expectedResidual(i).S)
          dut.io.output.bits.quantized(i).expect(expectedQuantized(i).S)
        }
        dut.io.output.bits.numNonzeros.expect(expectedNonzeros.U)
        dut.clock.step()
      }

      expectCase(
        xCoefficients,
        channel = 0,
        cflMultiplier = -4,
        xQmMultiplier,
        expectedXResidual,
        expectedXQuantized,
        expectedXNonzeros
      )
      expectCase(
        bCoefficients,
        channel = 2,
        cflMultiplier = -21,
        bQmMultiplier,
        expectedBResidual,
        expectedBQuantized,
        expectedBNonzeros
      )
    }
  }

  "QuantizeDcDct8x8Block quantizes DC coefficients with B-from-Y correction" in {
    val cases = Seq(
      (1, 17000, 37584640, 0),
      (0, -9000, 300809830, 42),
      (2, 11000, 18792320, -37)
    )

    simulate(new QuantizeDcDct8x8Block()) { dut =>
      for ((channel, coefficient, invDcFactorQ16, quantizedYDc) <- cases) {
        val expected = quantizeDcModel(coefficient, channel, invDcFactorQ16, quantizedYDc)
        dut.io.input.valid.poke(true.B)
        dut.io.output.ready.poke(true.B)
        dut.io.input.bits.channel.poke(channel.U)
        dut.io.input.bits.dcCoefficient.poke(coefficient.S)
        dut.io.input.bits.invDcFactorQ16.poke(invDcFactorQ16.U)
        dut.io.input.bits.quantizedYDc.poke(quantizedYDc.S)

        dut.io.output.valid.expect(true.B)
        dut.io.output.bits.quantizedDc.expect(expected.S)
        dut.clock.step()
      }
    }
  }

  "DctOnlyQuantizeBlock emits prepared DCT-only AC/DC/nonzero outputs for all channels" in {
    val coefficients = Seq.tabulate(3) { channel =>
      Seq.tabulate(blockSize) { i =>
        if (i == 0) {
          Seq(15000, 24000, -18000)(channel)
        } else {
          ((i % (11 + channel * 2)) - (5 + channel)) * (520 + channel * 90 + i * (17 + channel * 5))
        }
      }
    }
    val quant = 5
    val scaleQ16 = 7340
    val invQacQ16 = 117043
    val invDcFactorQ16 = Seq(300809830, 37584640, 18792320)
    val xQmMultiplierQ16 = (QuantizeDct8x8Block.DefaultQmMultiplierQ16 * 5) / 4
    val ytox = -4
    val ytob = -21
    val expected =
      dctOnlyBlockModel(coefficients, quant, scaleQ16, invQacQ16, invDcFactorQ16, xQmMultiplierQ16, ytox, ytob)

    simulate(new DctOnlyQuantizeBlock()) { dut =>
      dut.io.input.valid.poke(true.B)
      dut.io.output.ready.poke(true.B)
      dut.io.input.bits.quant.poke(quant.U)
      dut.io.input.bits.scaleQ16.poke(scaleQ16.U)
      dut.io.input.bits.invQacQ16.poke(invQacQ16.U)
      dut.io.input.bits.xQmMultiplierQ16.poke(xQmMultiplierQ16.U)
      dut.io.input.bits.ytox.poke(ytox.S)
      dut.io.input.bits.ytob.poke(ytob.S)
      for (channel <- 0 until 3) {
        dut.io.input.bits.invDcFactorQ16(channel).poke(invDcFactorQ16(channel).U)
        for (i <- 0 until blockSize) {
          dut.io.input.bits.coefficients(channel)(i).poke(coefficients(channel)(i).S)
        }
      }

      dut.io.output.valid.expect(true.B)
      for (channel <- 0 until 3) {
        dut.io.output.bits.quantizedDc(channel).expect(expected.quantizedDc(channel).S)
        dut.io.output.bits.numNonzeros(channel).expect(expected.numNonzeros(channel).U)
        for (i <- 0 until blockSize) {
          dut.io.output.bits.quantizedAc(channel)(i).expect(expected.quantizedAc(channel)(i).S)
        }
      }
    }
  }

  "DctOnlyQuantizeTraceStage emits prepared block quantization traces" in {
    val coefficients = Seq.tabulate(3) { channel =>
      Seq.tabulate(blockSize) { i =>
        if (i == 0) {
          Seq(-13000, 21000, 9000)(channel)
        } else {
          ((i % (9 + channel * 3)) - (4 + channel)) * (480 + channel * 110 + i * (13 + channel * 7))
        }
      }
    }
    val group = 11
    val quant = 6
    val scaleQ16 = 7340
    val invQacQ16 = 93634
    val invDcFactorQ16 = Seq(300809830, 37584640, 18792320)
    val xQmMultiplierQ16 = (QuantizeDct8x8Block.DefaultQmMultiplierQ16 * 5) / 4
    val ytox = 3
    val ytob = -17
    val expected =
      dctOnlyBlockModel(coefficients, quant, scaleQ16, invQacQ16, invDcFactorQ16, xQmMultiplierQ16, ytox, ytob)

    simulate(new DctOnlyQuantizeTraceStage()) { dut =>
      dut.io.trace.ready.poke(false.B)
      dut.io.input.valid.poke(true.B)
      dut.io.input.bits.group.poke(group.U)
      dut.io.input.bits.quantize.quant.poke(quant.U)
      dut.io.input.bits.quantize.scaleQ16.poke(scaleQ16.U)
      dut.io.input.bits.quantize.invQacQ16.poke(invQacQ16.U)
      dut.io.input.bits.quantize.xQmMultiplierQ16.poke(xQmMultiplierQ16.U)
      dut.io.input.bits.quantize.ytox.poke(ytox.S)
      dut.io.input.bits.quantize.ytob.poke(ytob.S)
      for (channel <- 0 until 3) {
        dut.io.input.bits.quantize.invDcFactorQ16(channel).poke(invDcFactorQ16(channel).U)
        for (i <- 0 until blockSize) {
          dut.io.input.bits.quantize.coefficients(channel)(i).poke(coefficients(channel)(i).S)
        }
      }
      dut.io.input.ready.expect(true.B)
      dut.clock.step()
      dut.io.input.valid.poke(false.B)
      dut.io.input.ready.expect(false.B)
      dut.io.trace.valid.expect(true.B)

      dut.io.trace.ready.poke(true.B)
      for (channel <- 0 until 3) {
        for (i <- 0 until blockSize) {
          val index = channel * blockSize + i
          dut.io.trace.valid.expect(true.B)
          dut.io.trace.bits.stage.expect(TraceStage.QuantizedAc.U)
          dut.io.trace.bits.group.expect(group.U)
          dut.io.trace.bits.index.expect(index.U)
          dut.io.trace.bits.value.expect(expected.quantizedAc(channel)(i).S)
          dut.clock.step()
        }
      }
      for (channel <- 0 until 3) {
        dut.io.trace.valid.expect(true.B)
        dut.io.trace.bits.stage.expect(TraceStage.QuantDc.U)
        dut.io.trace.bits.group.expect(group.U)
        dut.io.trace.bits.index.expect(channel.U)
        dut.io.trace.bits.value.expect(expected.quantizedDc(channel).S)
        dut.clock.step()
      }
      for (channel <- 0 until 3) {
        dut.io.trace.valid.expect(true.B)
        dut.io.trace.bits.stage.expect(TraceStage.NumNonzeros.U)
        dut.io.trace.bits.group.expect(group.U)
        dut.io.trace.bits.index.expect(channel.U)
        dut.io.trace.bits.value.expect(expected.numNonzeros(channel).S)
        dut.clock.step()
      }

      dut.io.trace.valid.expect(false.B)
      dut.io.input.ready.expect(true.B)
    }
  }
}
