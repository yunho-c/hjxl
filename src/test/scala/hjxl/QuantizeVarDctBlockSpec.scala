// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.sys.process.Process
import scala.sys.process.ProcessLogger
import scala.jdk.CollectionConverters._

class QuantizeVarDctBlockSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(preparedDctCoefficientFractionBits = 16)
  private val maxCoefficients = QuantizeVarDctBlock.MaxCoefficients
  private val libjxlTinyRoot =
    Path.of(sys.env.getOrElse("LIBJXL_TINY", "/Users/yunhocho/GitHub/libjxl-tiny"))

  private case class Fixture(
      label: String,
      strategy: Int,
      coefficientCount: Int,
      coveredBlocks: Int,
      quant: Int,
      scaleQ16: Int,
      invQacQ16: Long,
      invDcFactorQ16: Seq[Long],
      xQmMultiplierQ16: Long,
      ytox: Int,
      ytob: Int,
      coefficients: Seq[Seq[Int]],
      quantizedAc: Seq[Seq[Int]],
      quantizedDc: Seq[Seq[Int]],
      numNonzeros: Seq[Int],
      shiftedNumNonzeros: Seq[Int],
      referenceQuantizedAc: Seq[Seq[Int]],
      referenceQuantizedDc: Seq[Seq[Int]],
      referenceNumNonzeros: Seq[Int],
      referenceShiftedNumNonzeros: Seq[Int]
  )

  private case class Trace(stage: Int, group: Int, index: Int, value: Int, last: Boolean)

  private def requireReferenceTools(): Unit = {
    assume(
      Files.isDirectory(libjxlTinyRoot.resolve("python")),
      s"libjxl-tiny Python checkout not found at $libjxlTinyRoot"
    )
    assume(
      Process(Seq("python3", "-c", "import numpy")).! == 0,
      "python3 with numpy is required for VarDCT quantization fixtures"
    )
  }

  private def parseInts(value: String): Seq[Int] =
    value.split(" ").filter(_.nonEmpty).map(_.toInt).toVector

  private def parseLongs(value: String): Seq[Long] =
    value.split(" ").filter(_.nonEmpty).map(_.toLong).toVector

  private def loadFixtures(
      pattern: String,
      distance: String,
      rawQuant: Int,
      ytox: Int,
      ytob: Int
  ): Seq[Fixture] = {
    requireReferenceTools()
    val csv = Files.createTempFile("hjxl-var-dct-quantize-", ".csv")
    val output = scala.collection.mutable.ArrayBuffer.empty[String]
    val logger = ProcessLogger(line => output += line, line => output += line)
    val command = Seq(
      "python3",
      TestPaths.repoRoot.resolve("tools/hjxl_reference.py").toString,
      "--width",
      "16",
      "--height",
      "16",
      "--pattern",
      pattern,
      "--distance",
      distance,
      "--fixed-raw-quant",
      rawQuant.toString,
      "--fixed-ytox",
      ytox.toString,
      "--fixed-ytob",
      ytob.toString,
      "--var-dct-quantize-q16-csv",
      csv.toString
    )
    val exitCode = Process(
      command,
      TestPaths.repoRoot.toFile,
      "LIBJXL_TINY" -> libjxlTinyRoot.toString
    ).!(logger)
    withClue(output.mkString("\n")) {
      exitCode mustBe 0
    }

    val lines = Files.readAllLines(csv, StandardCharsets.UTF_8).toArray(new Array[String](0))
    lines.head must include("strategy,coefficient_count,covered_blocks")
    lines.drop(1).toSeq.map { line =>
      val columns = line.split(",", -1)
      columns.length mustBe 29
      val coefficientCount = columns(1).toInt
      val coveredBlocks = columns(2).toInt
      val coefficients = (10 to 12).map(index => parseInts(columns(index)))
      val quantizedAc = (13 to 15).map(index => parseInts(columns(index)))
      val quantizedDc = (16 to 18).map(index => parseInts(columns(index)))
      val referenceQuantizedAc = (21 to 23).map(index => parseInts(columns(index)))
      val referenceQuantizedDc = (24 to 26).map(index => parseInts(columns(index)))
      coefficients.foreach(_.length mustBe coefficientCount)
      quantizedAc.foreach(_.length mustBe coefficientCount)
      quantizedDc.foreach(_.length mustBe coveredBlocks)
      referenceQuantizedAc.foreach(_.length mustBe coefficientCount)
      referenceQuantizedDc.foreach(_.length mustBe coveredBlocks)
      Fixture(
        label = s"$pattern-distance-$distance-quant-$rawQuant-cfl-$ytox-$ytob",
        strategy = columns(0).toInt,
        coefficientCount = coefficientCount,
        coveredBlocks = coveredBlocks,
        quant = columns(3).toInt,
        scaleQ16 = columns(4).toInt,
        invQacQ16 = columns(5).toLong,
        invDcFactorQ16 = parseLongs(columns(6)),
        xQmMultiplierQ16 = columns(7).toLong,
        ytox = columns(8).toInt,
        ytob = columns(9).toInt,
        coefficients = coefficients,
        quantizedAc = quantizedAc,
        quantizedDc = quantizedDc,
        numNonzeros = parseInts(columns(19)),
        shiftedNumNonzeros = parseInts(columns(20)),
        referenceQuantizedAc = referenceQuantizedAc,
        referenceQuantizedDc = referenceQuantizedDc,
        referenceNumNonzeros = parseInts(columns(27)),
        referenceShiftedNumNonzeros = parseInts(columns(28))
      )
    }
  }

  private lazy val oracleFixtures: Seq[Fixture] = Seq(
    ("constant", "0.5", 3, 0, 0),
    ("checkerboard", "1", 5, -9, 7),
    ("gradient", "4", 11, 23, -17),
    ("impulse", "8", 7, -32, 31)
  ).flatMap { case (pattern, distance, rawQuant, ytox, ytob) =>
    loadFixtures(pattern, distance, rawQuant, ytox, ytob)
  }

  private def checkerboardFixture(strategy: Int): Fixture =
    oracleFixtures.find(fixture => fixture.label.startsWith("checkerboard") && fixture.strategy == strategy).get

  private def pokeInput(dut: VarDctQuantizeBlock, fixture: Fixture): Unit = {
    dut.io.input.bits.strategy.poke(fixture.strategy.U)
    dut.io.input.bits.quant.poke(fixture.quant.U)
    dut.io.input.bits.scaleQ16.poke(fixture.scaleQ16.U)
    dut.io.input.bits.invQacQ16.poke(fixture.invQacQ16.U)
    dut.io.input.bits.xQmMultiplierQ16.poke(fixture.xQmMultiplierQ16.U)
    dut.io.input.bits.ytox.poke(fixture.ytox.S)
    dut.io.input.bits.ytob.poke(fixture.ytob.S)
    for (channel <- 0 until 3) {
      dut.io.input.bits.invDcFactorQ16(channel).poke(fixture.invDcFactorQ16(channel).U)
      for (coefficient <- 0 until maxCoefficients) {
        val value =
          if (coefficient < fixture.coefficientCount) fixture.coefficients(channel)(coefficient)
          else 0
        dut.io.input.bits.coefficients(channel)(coefficient).poke(value.S)
      }
    }
  }

  private def expectOutput(dut: VarDctQuantizeBlock, fixture: Fixture): Unit = {
    dut.io.output.valid.expect(true.B)
    dut.io.output.bits.strategy.expect(fixture.strategy.U)
    dut.io.output.bits.coefficientCount.expect(fixture.coefficientCount.U)
    dut.io.output.bits.coveredBlocks.expect(fixture.coveredBlocks.U)
    for (channel <- 0 until 3) {
      for (coefficient <- 0 until maxCoefficients) {
        val expected =
          if (coefficient < fixture.coefficientCount) fixture.quantizedAc(channel)(coefficient)
          else 0
        withClue(
          s"${fixture.label} strategy ${fixture.strategy} channel $channel coefficient $coefficient: "
        ) {
          dut.io.output.bits.quantizedAc(channel)(coefficient).peekValue().asBigInt.toInt mustBe expected
        }
      }
      for (covered <- 0 until QuantizeVarDctBlock.MaxCoveredBlocks) {
        val expected =
          if (covered < fixture.coveredBlocks) fixture.quantizedDc(channel)(covered)
          else 0
        withClue(
          s"${fixture.label} strategy ${fixture.strategy} channel $channel DC cell $covered: "
        ) {
          dut.io.output.bits.quantizedDc(channel)(covered).peekValue().asBigInt.toInt mustBe expected
        }
      }
      dut.io.output.bits.numNonzeros(channel).expect(fixture.numNonzeros(channel).U)
      dut.io.output.bits.shiftedNumNonzeros(channel).expect(
        fixture.shiftedNumNonzeros(channel).U
      )
    }
  }

  private def pokeTraceInput(dut: VarDctQuantizeTraceStage, fixture: Fixture, group: Int): Unit = {
    dut.io.input.bits.group.poke(group.U)
    dut.io.input.bits.quantize.strategy.poke(fixture.strategy.U)
    dut.io.input.bits.quantize.quant.poke(fixture.quant.U)
    dut.io.input.bits.quantize.scaleQ16.poke(fixture.scaleQ16.U)
    dut.io.input.bits.quantize.invQacQ16.poke(fixture.invQacQ16.U)
    dut.io.input.bits.quantize.xQmMultiplierQ16.poke(fixture.xQmMultiplierQ16.U)
    dut.io.input.bits.quantize.ytox.poke(fixture.ytox.S)
    dut.io.input.bits.quantize.ytob.poke(fixture.ytob.S)
    for (channel <- 0 until 3) {
      dut.io.input.bits.quantize.invDcFactorQ16(channel).poke(
        fixture.invDcFactorQ16(channel).U
      )
      for (coefficient <- 0 until maxCoefficients) {
        val value =
          if (coefficient < fixture.coefficientCount) fixture.coefficients(channel)(coefficient)
          else 0
        dut.io.input.bits.quantize.coefficients(channel)(coefficient).poke(value.S)
      }
    }
  }

  private def pokeConfig(dut: FramePreparedVarDctQuantizeStage): Unit = {
    dut.io.config.xsize.poke(16.U)
    dut.io.config.ysize.poke(16.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(0.U)
    dut.io.config.fixedInvQacQ16.poke(0.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.fixedYtox.poke(0.S)
    dut.io.config.fixedYtob.poke(0.S)
    dut.io.config.enableXyb.poke(false.B)
    dut.io.config.enableDct.poke(true.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(false.B)
    dut.io.config.tokenSelect.poke(0.U)
  }

  private def pokeFrameInput(
      dut: FramePreparedVarDctQuantizeStage,
      fixture: Fixture,
      blockX: Int,
      blockY: Int,
      last: Boolean
  ): Unit = {
    dut.io.input.bits.blockX.poke(blockX.U)
    dut.io.input.bits.blockY.poke(blockY.U)
    dut.io.input.bits.last.poke(last.B)
    dut.io.input.bits.quantize.strategy.poke(fixture.strategy.U)
    dut.io.input.bits.quantize.quant.poke(fixture.quant.U)
    dut.io.input.bits.quantize.scaleQ16.poke(fixture.scaleQ16.U)
    dut.io.input.bits.quantize.invQacQ16.poke(fixture.invQacQ16.U)
    dut.io.input.bits.quantize.xQmMultiplierQ16.poke(fixture.xQmMultiplierQ16.U)
    dut.io.input.bits.quantize.ytox.poke(fixture.ytox.S)
    dut.io.input.bits.quantize.ytob.poke(fixture.ytob.S)
    for (channel <- 0 until 3) {
      dut.io.input.bits.quantize.invDcFactorQ16(channel).poke(
        fixture.invDcFactorQ16(channel).U
      )
      for (coefficient <- 0 until maxCoefficients) {
        val value =
          if (coefficient < fixture.coefficientCount) fixture.coefficients(channel)(coefficient)
          else 0
        dut.io.input.bits.quantize.coefficients(channel)(coefficient).poke(value.S)
      }
    }
  }

  private def expectFrameOutput(
      dut: FramePreparedVarDctQuantizeStage,
      fixture: Fixture,
      blockX: Int,
      blockY: Int,
      ordinal: Int,
      last: Boolean
  ): Unit = {
    dut.io.output.valid.expect(true.B)
    dut.io.output.bits.blockX.expect(blockX.U)
    dut.io.output.bits.blockY.expect(blockY.U)
    dut.io.output.bits.blockOrdinal.expect(ordinal.U)
    dut.io.output.bits.last.expect(last.B)
    dut.io.output.bits.rawQuant.expect(fixture.quant.U)
    dut.io.output.bits.ytox.expect(fixture.ytox.S)
    dut.io.output.bits.ytob.expect(fixture.ytob.S)
    dut.io.output.bits.result.strategy.expect(fixture.strategy.U)
    dut.io.output.bits.result.coefficientCount.expect(fixture.coefficientCount.U)
    dut.io.output.bits.result.coveredBlocks.expect(fixture.coveredBlocks.U)
    for (channel <- 0 until 3) {
      for (coefficient <- 0 until maxCoefficients) {
        val expected =
          if (coefficient < fixture.coefficientCount) fixture.quantizedAc(channel)(coefficient)
          else 0
        dut.io.output.bits.result.quantizedAc(channel)(coefficient).expect(expected.S)
      }
      for (covered <- 0 until QuantizeVarDctBlock.MaxCoveredBlocks) {
        val expected =
          if (covered < fixture.coveredBlocks) fixture.quantizedDc(channel)(covered)
          else 0
        dut.io.output.bits.result.quantizedDc(channel)(covered).expect(expected.S)
      }
      dut.io.output.bits.result.numNonzeros(channel).expect(fixture.numNonzeros(channel).U)
      dut.io.output.bits.result.shiftedNumNonzeros(channel).expect(
        fixture.shiftedNumNonzeros(channel).U
      )
    }
  }

  "the first-block primitive matches libjxl-tiny for DCT and both rectangular orientations" in {
    val cases = oracleFixtures
    cases.map(_.strategy).toSet mustBe
      Set(AcStrategyCode.Dct, AcStrategyCode.Dct16x8, AcStrategyCode.Dct8x16)
    cases.map(_.label).toSet.size mustBe 4
    simulate(new VarDctQuantizeBlock(config, coefficientFractionBits = 16)) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.output.ready.poke(false.B)
      dut.clock.step()

      cases.foreach { fixture =>
        pokeInput(dut, fixture)
        dut.io.input.valid.poke(true.B)
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
        dut.io.input.valid.poke(false.B)
        dut.io.input.ready.expect(false.B)

        var waitCycles = 0
        while (dut.io.output.valid.peekValue().asBigInt == 0 && waitCycles < 136) {
          dut.clock.step()
          waitCycles += 1
        }
        withClue(s"${fixture.label} strategy ${fixture.strategy} output latency: ") {
          waitCycles must be < 136
        }
        val minimumLatency =
          if (fixture.coefficientCount == QuantizeVarDctBlock.DctCoefficients) 63 else 127
        waitCycles must be >= minimumLatency
        expectOutput(dut, fixture)
        dut.clock.step(3)
        expectOutput(dut, fixture)

        dut.io.output.ready.poke(true.B)
        dut.io.input.ready.expect(false.B)
        expectOutput(dut, fixture)
        dut.clock.step()
        dut.io.output.valid.expect(false.B)
        dut.io.input.ready.expect(true.B)
        dut.io.output.ready.poke(false.B)
        dut.clock.step()
      }
    }
  }

  "a Q19 luma-DC sideband preserves Q18 AC while resolving a half-step DC boundary" in {
    case class Result(ac: Vector[Vector[Int]], yDc: Vector[Int])

    def run(lumaDcFractionBits: Int): Result = {
      var result = Option.empty[Result]
      simulate(
        new VarDctQuantizeBlock(
          config,
          coefficientFractionBits = RgbVarDctFixedPoint.AnalysisXybFractionBits,
          lumaDcCoefficientFractionBits = lumaDcFractionBits
        )
      ) { dut =>
        dut.io.input.valid.poke(false.B)
        dut.io.output.ready.poke(false.B)
        dut.clock.step()

        dut.io.input.bits.strategy.poke(AcStrategyCode.Dct16x8.U)
        dut.io.input.bits.quant.poke(3.U)
        dut.io.input.bits.scaleQ16.poke(7340.U)
        dut.io.input.bits.invQacQ16.poke(195048.U)
        dut.io.input.bits.invDcFactorQ16(0).poke(300646400.U)
        dut.io.input.bits.invDcFactorQ16(1).poke(37580800.U)
        dut.io.input.bits.invDcFactorQ16(2).poke(18790400.U)
        dut.io.input.bits.xQmMultiplierQ16.poke(65536.U)
        dut.io.input.bits.ytox.poke((-3).S)
        dut.io.input.bits.ytob.poke((-21).S)
        for (channel <- 0 until 3; coefficient <- 0 until maxCoefficients) {
          val value =
            if (channel == 1 && coefficient == 0) 163829
            else if (channel == 1 && coefficient == 1) -571
            else 0
          dut.io.input.bits.coefficients(channel)(coefficient).poke(value.S)
        }
        dut.io.input.bits.lumaDcCoefficients.foreach { coefficients =>
          coefficients(0).poke(327658.S)
          coefficients(1).poke((-1141).S)
        }

        dut.io.input.valid.poke(true.B)
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
        dut.io.input.valid.poke(false.B)

        var cycles = 0
        while (!dut.io.output.valid.peek().litToBoolean && cycles < 136) {
          dut.clock.step()
          cycles += 1
        }
        cycles must be < 136
        result = Some(
          Result(
            ac = dut.io.output.bits.quantizedAc
              .map(_.map(_.peekValue().asBigInt.toInt).toVector)
              .toVector,
            yDc = dut.io.output.bits.quantizedDc(1)
              .map(_.peekValue().asBigInt.toInt)
              .toVector
          )
        )
        dut.io.output.ready.poke(true.B)
        dut.clock.step()
      }
      result.getOrElse(fail("VarDCT quantizer did not produce a result"))
    }

    val q18 = run(RgbVarDctFixedPoint.AnalysisXybFractionBits)
    val split = run(RgbVarDctFixedPoint.LumaDcXybFractionBits)
    q18.ac mustBe split.ac
    q18.yDc mustBe Vector(357, 360)
    split.yDc mustBe Vector(357, 359)
  }

  "the frozen Q16 seam stays within one AC integer of native-float quantization" in {
    val acDeltas = for {
      fixture <- oracleFixtures
      channel <- 0 until 3
      coefficient <- 0 until fixture.coefficientCount
    } yield math.abs(
      fixture.quantizedAc(channel)(coefficient) -
        fixture.referenceQuantizedAc(channel)(coefficient)
    )
    val dcDeltas = for {
      fixture <- oracleFixtures
      channel <- 0 until 3
      covered <- 0 until fixture.coveredBlocks
    } yield math.abs(
      fixture.quantizedDc(channel)(covered) -
        fixture.referenceQuantizedDc(channel)(covered)
    )

    acDeltas.max must be <= 1
    dcDeltas.max mustBe 0
    oracleFixtures.foreach { fixture =>
      fixture.numNonzeros mustBe fixture.referenceNumNonzeros
      fixture.shiftedNumNonzeros mustBe fixture.referenceShiftedNumNonzeros
    }
  }

  "the trace wrapper emits owner AC, covered-cell DC, and distinct raw/shifted counts" in {
    val fixture = checkerboardFixture(AcStrategyCode.Dct16x8)
    val group = 11
    val expected = {
      val ac = for {
        channel <- 0 until 3
        coefficient <- 0 until fixture.coefficientCount
      } yield Trace(
        TraceStage.QuantizedAc,
        group,
        channel * fixture.coefficientCount + coefficient,
        fixture.quantizedAc(channel)(coefficient),
        last = false
      )
      val dc = for {
        channel <- 0 until 3
        covered <- 0 until fixture.coveredBlocks
      } yield Trace(
        TraceStage.QuantDc,
        group,
        channel * fixture.coveredBlocks + covered,
        fixture.quantizedDc(channel)(covered),
        last = false
      )
      val raw = (0 until 3).map { channel =>
        Trace(TraceStage.NumNonzeros, group, channel, fixture.numNonzeros(channel), last = false)
      }
      val shifted = (0 until 3).map { channel =>
        Trace(
          TraceStage.NumNonzeros,
          group,
          3 + channel,
          fixture.shiftedNumNonzeros(channel),
          last = channel == 2
        )
      }
      ac ++ dc ++ raw ++ shifted
    }

    simulate(new VarDctQuantizeTraceStage(config, coefficientFractionBits = 16)) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()
      pokeTraceInput(dut, fixture, group)
      dut.io.input.valid.poke(true.B)
      dut.io.input.ready.expect(true.B)
      dut.clock.step()
      dut.io.input.valid.poke(false.B)

      var quantizeCycles = 0
      while (dut.io.trace.valid.peekValue().asBigInt == 0 && quantizeCycles < 136) {
        dut.io.busy.expect(true.B)
        dut.clock.step()
        quantizeCycles += 1
      }
      quantizeCycles must be < 136

      expected.zipWithIndex.foreach { case (row, ordinal) =>
        val stall = ordinal % 13 == 5
        dut.io.trace.ready.poke((!stall).B)
        dut.io.trace.valid.expect(true.B)
        dut.io.trace.bits.stage.expect(row.stage.U)
        dut.io.trace.bits.group.expect(row.group.U)
        dut.io.trace.bits.index.expect(row.index.U)
        dut.io.trace.bits.value.expect(row.value.S)
        dut.io.traceLast.expect(row.last.B)
        if (stall) {
          dut.clock.step(2)
          dut.io.trace.valid.expect(true.B)
          dut.io.trace.bits.stage.expect(row.stage.U)
          dut.io.trace.bits.group.expect(row.group.U)
          dut.io.trace.bits.index.expect(row.index.U)
          dut.io.trace.bits.value.expect(row.value.S)
          dut.io.traceLast.expect(row.last.B)
          dut.io.trace.ready.poke(true.B)
        }
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.traceLast.expect(false.B)
      dut.io.busy.expect(false.B)
    }
  }

  "the prepared frame boundary accepts first blocks only and maps both rectangle orientations" in {
    val dct = checkerboardFixture(AcStrategyCode.Dct)
    val horizontal = checkerboardFixture(AcStrategyCode.Dct8x16)
    val vertical = checkerboardFixture(AcStrategyCode.Dct16x8)

    def runFrame(
        rectangle: Fixture,
        owners: Seq[(Fixture, Int, Int, Int, Boolean)],
        secondCell: Int
    ): Unit = {
      simulate(new FramePreparedVarDctQuantizeStage(config, coefficientFractionBits = 16)) { dut =>
        pokeConfig(dut)
        dut.io.input.valid.poke(false.B)
        dut.io.output.ready.poke(false.B)
        dut.clock.step()

        val dcMap = Array.fill(3, 4)(Option.empty[Int])
        val nonzeroMap = Array.fill(3, 4)(Option.empty[Int])

        owners.foreach { case (fixture, blockX, blockY, ordinal, last) =>
          pokeFrameInput(dut, fixture, blockX, blockY, last)
          dut.io.input.valid.poke(true.B)
          dut.io.input.ready.expect(true.B)
          dut.clock.step()
          dut.io.input.valid.poke(false.B)
          dut.io.input.ready.expect(false.B)

          var waitCycles = 0
          while (dut.io.output.valid.peekValue().asBigInt == 0 && waitCycles < 136) {
            dut.io.busy.expect(true.B)
            dut.clock.step()
            waitCycles += 1
          }
          waitCycles must be < 136
          expectFrameOutput(dut, fixture, blockX, blockY, ordinal, last)
          dut.clock.step(2)
          expectFrameOutput(dut, fixture, blockX, blockY, ordinal, last)

          val coveredOrdinals =
            if (fixture.strategy == AcStrategyCode.Dct) Seq(ordinal)
            else Seq(ordinal, secondCell)
          for (channel <- 0 until 3; (cell, coveredIndex) <- coveredOrdinals.zipWithIndex) {
            dcMap(channel)(cell) = Some(fixture.quantizedDc(channel)(coveredIndex))
            nonzeroMap(channel)(cell) = Some(fixture.shiftedNumNonzeros(channel))
          }

          dut.io.output.ready.poke(true.B)
          dut.io.input.ready.expect(false.B)
          dut.clock.step()
          dut.io.output.ready.poke(false.B)
          dut.io.input.ready.expect(true.B)
          dut.clock.step()
        }

        dcMap.foreach(_.forall(_.nonEmpty) mustBe true)
        nonzeroMap.foreach(_.forall(_.nonEmpty) mustBe true)
        for (channel <- 0 until 3) {
          dcMap(channel)(0) mustBe Some(rectangle.quantizedDc(channel)(0))
          dcMap(channel)(secondCell) mustBe Some(rectangle.quantizedDc(channel)(1))
          nonzeroMap(channel)(0) mustBe Some(rectangle.shiftedNumNonzeros(channel))
          nonzeroMap(channel)(secondCell) mustBe Some(rectangle.shiftedNumNonzeros(channel))
        }
        dut.io.busy.expect(false.B)
        dut.io.overflow.expect(false.B)
      }
    }

    runFrame(
      horizontal,
      Seq(
        (horizontal, 0, 0, 0, false),
        (dct, 0, 1, 2, false),
        (dct, 1, 1, 3, true)
      ),
      secondCell = 1
    )
    runFrame(
      vertical,
      Seq(
        (vertical, 0, 0, 0, false),
        (dct, 1, 0, 1, false),
        (dct, 1, 1, 3, true)
      ),
      secondCell = 2
    )
  }

  "the prepared frame boundary drops malformed ownership and reports overflow" in {
    val dct = checkerboardFixture(AcStrategyCode.Dct)
    simulate(new FramePreparedVarDctQuantizeStage(config, coefficientFractionBits = 16)) { dut =>
      pokeConfig(dut)
      dut.io.output.ready.poke(true.B)
      dut.io.input.valid.poke(false.B)
      dut.clock.step()

      // Block one cannot arrive before the uncovered raster origin.
      pokeFrameInput(dut, dct, blockX = 1, blockY = 0, last = false)
      dut.io.input.valid.poke(true.B)
      dut.io.input.ready.expect(true.B)
      dut.io.output.valid.expect(false.B)
      dut.clock.step()
      dut.io.input.valid.poke(false.B)
      dut.io.overflow.expect(true.B)
      dut.io.busy.expect(false.B)

      // The malformed frame is discarded, so a complete all-DCT frame can
      // start again immediately while the sticky diagnostic remains asserted.
      val owners = Seq((0, 0, false), (1, 0, false), (0, 1, false), (1, 1, true))
      owners.zipWithIndex.foreach { case ((blockX, blockY, last), ordinal) =>
        pokeFrameInput(dut, dct, blockX, blockY, last)
        dut.io.input.valid.poke(true.B)
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
        dut.io.input.valid.poke(false.B)

        var waitCycles = 0
        while (dut.io.output.valid.peekValue().asBigInt == 0 && waitCycles < 72) {
          dut.clock.step()
          waitCycles += 1
        }
        waitCycles must be < 72
        dut.io.output.bits.blockOrdinal.expect(ordinal.U)
        dut.io.output.bits.last.expect(last.B)
        dut.clock.step()
      }
      dut.io.busy.expect(false.B)
      dut.io.overflow.expect(true.B)
    }
  }
}

class QuantizeVarDctElaborationSpec extends AnyFreeSpec with Matchers {
  "the first-block prepared frame boundary elaborates with its variable-shape surface" in {
    val targetDir = Files.createTempDirectory("hjxl-var-dct-quantize-elaborate-")
    ChiselStage.emitSystemVerilogFile(
      new FramePreparedVarDctQuantizeStage(
        HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 16)
      ),
      args = Array("--target-dir", targetDir.toString),
      firtoolOpts = Array(
        "-disable-all-randomization",
        "-strip-debug-info",
        "-default-layer-specialization=enable"
      )
    )
    val files = Files.walk(targetDir)
    val text = try {
      files.iterator().asScala
        .filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".sv"))
        .map(path => Files.readString(path, StandardCharsets.UTF_8))
        .mkString("\n")
    } finally {
      files.close()
    }

    text must include("module FramePreparedVarDctQuantizeStage")
    text must include("module VarDctQuantizeBlock")
    text must include("module QuantizeRoundtripYVarDctBlock")
    text must include("module QuantizeChromaResidualVarDctBlock")
    text must include("io_input_bits_quantize_coefficients_2_127")
    text must include("io_output_bits_result_quantizedAc_2_127")
    text must include("io_output_bits_result_quantizedDc_2_1")
    text must include("io_output_bits_result_shiftedNumNonzeros_2")
    text must include("io_output_bits_blockOrdinal")
    text must include("io_output_bits_last")
    text must include("io_busy")
    text must include("io_overflow")
    "32'h251F /".r.findAllMatchIn(text).size mustBe 1
  }
}
