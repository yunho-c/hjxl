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

class AcStrategyCandidateCostEvaluatorSpec
    extends AnyFreeSpec
    with Matchers
    with ChiselSim {
  private val libjxlTinyRoot =
    Path.of(sys.env.getOrElse("LIBJXL_TINY", "/Users/yunhocho/GitHub/libjxl-tiny"))

  private case class Candidate(
      index: Int,
      strategy: Int,
      coefficients: Seq[Seq[Int]],
      quantQ24: BigInt,
      maskQ16: BigInt,
      distanceQ8: Int,
      ytox: Int,
      ytob: Int,
      estimateQ16: BigInt,
      scaledCostQ16: BigInt,
      referenceScaledCost: Double
  )

  private case class Fixture(
      pattern: String,
      candidates: Seq[Candidate],
      referenceDecision: Seq[Int],
      fixedDecision: Seq[Int],
      schedulerFixedDecision: Seq[Int]
  )

  private case class CsvRow(
      candidate: Int,
      strategy: Int,
      coefficient: Int,
      xQ12: Int,
      yQ12: Int,
      bQ12: Int,
      quantQ24: BigInt,
      maskQ16: BigInt,
      distanceQ8: Int,
      ytox: Int,
      ytob: Int,
      estimateQ16: BigInt,
      scaledCostQ16: BigInt,
      referenceScaledCost: Double,
      referenceDecision: Seq[Int],
      fixedDecision: Seq[Int],
      schedulerFixedDecision: Seq[Int]
  )

  private def requireReferenceTools(): Unit = {
    assume(
      Files.isDirectory(libjxlTinyRoot.resolve("python")),
      s"libjxl-tiny Python checkout not found at $libjxlTinyRoot"
    )
    assume(
      Process(Seq("python3", "-c", "import numpy")).! == 0,
      "python3 with numpy is required for AC-strategy cost fixtures"
    )
  }

  private def decision(text: String): Seq[Int] = text.split(":", -1).map(_.toInt).toSeq

  private def fixture(pattern: String, distance: Double): Fixture = {
    requireReferenceTools()
    val csv = Files.createTempFile(s"hjxl-ac-strategy-$pattern-", ".csv")
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
        "--ac-strategy-cost-q16-csv",
        csv.toString
      ),
      TestPaths.repoRoot.toFile,
      "LIBJXL_TINY" -> libjxlTinyRoot.toString,
      "PYTHONDONTWRITEBYTECODE" -> "1"
    ).!(logger)
    withClue(output.mkString("\n")) {
      exitCode mustBe 0
    }

    val lines = Files.readAllLines(csv, StandardCharsets.UTF_8).asScala.toSeq
    lines.head mustBe
      "candidate,strategy,block_x,block_y,coefficient,x_q12,y_q12,b_q12," +
        "quant_q24,mask_q16,distance_q8,ytox,ytob,fixed_estimate_q16," +
        "fixed_scaled_cost_q16,reference_estimate,reference_scaled_cost," +
        "reference_decision,fixed_decision,scheduler_fixed_decision"
    val rows = lines.tail.map { line =>
      val columns = line.split(",", -1)
      columns.length mustBe 20
      CsvRow(
        candidate = columns(0).toInt,
        strategy = columns(1).toInt,
        coefficient = columns(4).toInt,
        xQ12 = columns(5).toInt,
        yQ12 = columns(6).toInt,
        bQ12 = columns(7).toInt,
        quantQ24 = BigInt(columns(8)),
        maskQ16 = BigInt(columns(9)),
        distanceQ8 = columns(10).toInt,
        ytox = columns(11).toInt,
        ytob = columns(12).toInt,
        estimateQ16 = BigInt(columns(13)),
        scaledCostQ16 = BigInt(columns(14)),
        referenceScaledCost = columns(16).toDouble,
        referenceDecision = decision(columns(17)),
        fixedDecision = decision(columns(18)),
        schedulerFixedDecision = decision(columns(19))
      )
    }
    rows.length mustBe 768

    val candidates = rows.groupBy(_.candidate).toSeq.sortBy(_._1).map {
      case (candidateIndex, grouped) =>
        val ordered = grouped.sortBy(_.coefficient)
        val first = ordered.head
        val coefficientCount = if (first.strategy == AcStrategyCode.Dct) 64 else 128
        ordered.map(_.coefficient) mustBe (0 until coefficientCount)
        grouped.foreach { row =>
          row.strategy mustBe first.strategy
          row.quantQ24 mustBe first.quantQ24
          row.maskQ16 mustBe first.maskQ16
          row.distanceQ8 mustBe first.distanceQ8
          row.ytox mustBe first.ytox
          row.ytob mustBe first.ytob
          row.estimateQ16 mustBe first.estimateQ16
          row.scaledCostQ16 mustBe first.scaledCostQ16
        }
        Candidate(
          index = candidateIndex,
          strategy = first.strategy,
          coefficients = Seq(
            ordered.map(_.xQ12),
            ordered.map(_.yQ12),
            ordered.map(_.bQ12)
          ),
          quantQ24 = first.quantQ24,
          maskQ16 = first.maskQ16,
          distanceQ8 = first.distanceQ8,
          ytox = first.ytox,
          ytob = first.ytob,
          estimateQ16 = first.estimateQ16,
          scaledCostQ16 = first.scaledCostQ16,
          referenceScaledCost = first.referenceScaledCost
        )
    }
    candidates.map(_.index) mustBe (0 until 8)
    Fixture(
      pattern,
      candidates,
      rows.head.referenceDecision,
      rows.head.fixedDecision,
      rows.head.schedulerFixedDecision
    )
  }

  private def pokeCandidate(
      dut: AcStrategyCandidateCostEvaluator,
      candidate: Candidate,
      distanceOverride: Option[Int] = None
  ): Unit = {
    dut.io.input.bits.strategy.poke(candidate.strategy.U)
    dut.io.input.bits.distanceQ8.poke(distanceOverride.getOrElse(candidate.distanceQ8).U)
    dut.io.input.bits.quantQ24.poke(candidate.quantQ24.U)
    dut.io.input.bits.maskQ16.poke(candidate.maskQ16.U)
    dut.io.input.bits.ytox.poke(candidate.ytox.S)
    dut.io.input.bits.ytob.poke(candidate.ytob.S)
    for {
      channel <- 0 until 3
      coefficient <- 0 until 128
    } {
      val value = candidate.coefficients(channel).lift(coefficient).getOrElse(0)
      dut.io.input.bits.coefficients(channel)(coefficient).poke(value.S)
    }
  }

  private def pokeCandidate(
      dut: PreparedAcStrategy2x2Selector,
      candidate: Candidate
  ): Unit = {
    dut.io.input.bits.strategy.poke(candidate.strategy.U)
    dut.io.input.bits.distanceQ8.poke(candidate.distanceQ8.U)
    dut.io.input.bits.quantQ24.poke(candidate.quantQ24.U)
    dut.io.input.bits.maskQ16.poke(candidate.maskQ16.U)
    dut.io.input.bits.ytox.poke(candidate.ytox.S)
    dut.io.input.bits.ytob.poke(candidate.ytob.S)
    for {
      channel <- 0 until 3
      coefficient <- 0 until 128
    } {
      val value = candidate.coefficients(channel).lift(coefficient).getOrElse(0)
      dut.io.input.bits.coefficients(channel)(coefficient).poke(value.S)
    }
  }

  private def evaluate(
      dut: AcStrategyCandidateCostEvaluator,
      candidate: Candidate,
      distanceOverride: Option[Int] = None,
      expectUnsupported: Boolean = false,
      expectOverflow: Boolean = false
  ): (BigInt, BigInt) = {
    dut.io.output.ready.poke(false.B)
    dut.io.input.valid.poke(true.B)
    pokeCandidate(dut, candidate, distanceOverride)
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
    dut.io.input.valid.poke(false.B)
    dut.io.input.ready.expect(false.B)

    val expectedLatency = if (candidate.strategy == AcStrategyCode.Dct) 192 else 384
    var latency = 0
    while (!dut.io.output.valid.peek().litToBoolean && latency <= expectedLatency) {
      dut.clock.step()
      latency += 1
    }
    withClue(s"${candidate.index}/${candidate.strategy} latency: ") {
      latency mustBe expectedLatency
    }
    dut.io.output.bits.strategy.expect(candidate.strategy.U)
    dut.io.output.bits.unsupportedDistance.expect(expectUnsupported.B)
    dut.io.output.bits.arithmeticOverflow.expect(expectOverflow.B)
    val estimate = dut.io.output.bits.estimateQ16.peekValue().asBigInt
    val scaled = dut.io.output.bits.scaledCostQ16.peekValue().asBigInt

    dut.clock.step(2)
    dut.io.output.valid.expect(true.B)
    dut.io.output.bits.estimateQ16.expect(estimate.U)
    dut.io.output.bits.scaledCostQ16.expect(scaled.U)
    dut.io.output.ready.poke(true.B)
    dut.clock.step()
    dut.io.output.valid.expect(false.B)
    (estimate, scaled)
  }

  "the prepared evaluator matches the independent fixed-point oracle" in {
    val fixtures = Seq(
      fixture("constant", 0.25),
      fixture("gradient", 1.0),
      fixture("checkerboard", 8.0),
      fixture("impulse", 4.0),
      fixture("random", 0.5)
    )
    fixtures.foreach(_.candidates.length mustBe 8)

    simulate(new AcStrategyCandidateCostEvaluator()) { dut =>
      fixtures.foreach { current =>
        val scaledCosts = current.candidates.map { candidate =>
          val (estimate, scaled) = evaluate(dut, candidate)
          withClue(s"${current.pattern} candidate ${candidate.index}: ") {
            estimate mustBe candidate.estimateQ16
            scaled mustBe candidate.scaledCostQ16
            val relativeDifference = math.abs(
              scaled.toDouble / (1 << 16) - candidate.referenceScaledCost
            ) / math.max(1.0, math.abs(candidate.referenceScaledCost))
            relativeDifference must be <= 0.12
          }
          scaled
        }
        AcStrategyCandidateCostEvaluatorSpec.fixedDecision(scaledCosts) mustBe
          current.fixedDecision
      }
    }
  }

  "the evaluator preserves Q12 costs when equivalent coefficients use Q16" in {
    val current = fixture("gradient", 1.0)
    simulate(
      new AcStrategyCandidateCostEvaluator(
        coefficientBits = 32,
        coefficientFractionBits = 16
      )
    ) { dut =>
      current.candidates.foreach { candidate =>
        val q16Candidate = candidate.copy(
          coefficients = candidate.coefficients.map(_.map(_ << 4))
        )
        val (estimate, scaled) = evaluate(dut, q16Candidate)
        withClue(s"Q16-scaled candidate ${candidate.index}: ") {
          estimate mustBe candidate.estimateQ16
          scaled mustBe candidate.scaledCostQ16
        }
      }
    }
  }

  "the distance-cost lookup covers every supported point and the distance-1 fallback" in {
    simulate(new AcStrategyCostParamsLookup) { dut =>
      AcStrategyCostTables.DistanceEntries.foreach { entry =>
        dut.io.distanceQ8.poke(entry.distanceQ8.U)
        dut.io.supported.expect(true.B)
        dut.io.params.cost1Q16.expect(entry.cost1Q16.U)
        dut.io.params.dct8MultiplierQ16.expect(entry.dct8MultiplierQ16.U)
        dut.io.params.rectangularMultiplierQ16.expect(entry.rectangularMultiplierQ16.U)
      }
      dut.io.distanceQ8.poke(300.U)
      dut.io.supported.expect(false.B)
      val fallback = AcStrategyCostTables.DefaultDistanceEntry
      dut.io.params.cost1Q16.expect(fallback.cost1Q16.U)
      dut.io.params.dct8MultiplierQ16.expect(fallback.dct8MultiplierQ16.U)
      dut.io.params.rectangularMultiplierQ16.expect(fallback.rectangularMultiplierQ16.U)
    }
  }

  "unsupported distances use distance 1 and report the fallback" in {
    val candidate = fixture("gradient", 1.0).candidates.head
    simulate(new AcStrategyCandidateCostEvaluator()) { dut =>
      val supported = evaluate(dut, candidate)
      val unsupported = evaluate(
        dut,
        candidate,
        distanceOverride = Some(300),
        expectUnsupported = true
      )
      unsupported mustBe supported
    }
  }

  "extreme prepared inputs saturate and report arithmetic overflow" in {
    val candidate = Candidate(
      index = 0,
      strategy = AcStrategyCode.Dct,
      coefficients = Seq.fill(3)(Seq.fill(64)(Int.MaxValue)),
      quantQ24 = (BigInt(1) << 32) - 1,
      maskQ16 = (BigInt(1) << 32) - 1,
      distanceQ8 = 256,
      ytox = -128,
      ytob = 127,
      estimateQ16 = 0,
      scaledCostQ16 = 0,
      referenceScaledCost = 0.0
    )
    simulate(new AcStrategyCandidateCostEvaluator()) { dut =>
      evaluate(dut, candidate, expectOverflow = true)
    }
  }

  "the prepared 2x2 boundary sequences all candidates and holds the exact decision" in {
    val current = fixture("gradient", 1.0)
    simulate(new PreparedAcStrategy2x2Selector()) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.output.ready.poke(false.B)
      current.candidates.foreach { candidate =>
        var waitCycles = 0
        while (!dut.io.input.ready.peek().litToBoolean && waitCycles < 400) {
          dut.clock.step()
          waitCycles += 1
        }
        withClue(s"candidate ${candidate.index} input wait: ") {
          waitCycles must be < 400
        }
        pokeCandidate(dut, candidate)
        dut.io.input.valid.poke(true.B)
        dut.clock.step()
        dut.io.input.valid.poke(false.B)
      }

      var outputWait = 0
      while (!dut.io.output.valid.peek().litToBoolean && outputWait < 400) {
        dut.clock.step()
        outputWait += 1
      }
      outputWait must be < 400
      dut.io.input.ready.expect(false.B)
      dut.io.output.bits.unsupportedDistance.expect(false.B)
      dut.io.output.bits.arithmeticOverflow.expect(false.B)
      current.fixedDecision.zipWithIndex.foreach { case (value, index) =>
        dut.io.output.bits.decision(index).expect(value.U)
      }
      current.candidates.zipWithIndex.foreach { case (candidate, index) =>
        val actual =
          if (index < 4) dut.io.output.bits.costs.dct8x8(index)
          else if (index < 6) dut.io.output.bits.costs.dct16x8(index - 4)
          else dut.io.output.bits.costs.dct8x16(index - 6)
        actual.expect(candidate.scaledCostQ16.U)
      }
      val stalledDecision = (0 until 4).map { index =>
        dut.io.output.bits.decision(index).peekValue().asBigInt
      }
      dut.clock.step(2)
      (0 until 4).map { index =>
        dut.io.output.bits.decision(index).peekValue().asBigInt
      } mustBe stalledDecision

      dut.io.output.ready.poke(true.B)
      dut.clock.step()
      dut.io.output.valid.expect(false.B)
      dut.io.input.ready.expect(true.B)
    }
  }
}

class AcStrategyCandidateCostEvaluatorElaborationSpec
    extends AnyFreeSpec
    with Matchers {
  "the complete prepared 2x2 scoring boundary elaborates with its diagnostic surface" in {
    val targetDir = Files.createTempDirectory("hjxl-ac-strategy-cost-elaborate-")
    ChiselStage.emitSystemVerilogFile(
      new PreparedAcStrategy2x2Selector(),
      args = Array("--target-dir", targetDir.toString),
      firtoolOpts = Array(
        "-disable-all-randomization",
        "-strip-debug-info",
        "-default-layer-specialization=enable"
      )
    )
    val files = Files.walk(targetDir)
    try {
      val systemVerilogFiles = files.iterator().asScala
        .filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".sv"))
        .toSeq
      systemVerilogFiles must not be empty
      val systemVerilog = systemVerilogFiles
        .map(path => Files.readString(path, StandardCharsets.UTF_8))
        .mkString("\n")
      systemVerilog must include("module PreparedAcStrategy2x2Selector")
      systemVerilog must include("module AcStrategyCandidateCostEvaluator")
      systemVerilog must include("module AcStrategyDecisionSelector")
      systemVerilog must include("io_input_bits_coefficients_2_127")
      systemVerilog must include("io_output_bits_costs_dct8x16_1")
      systemVerilog must include("io_output_bits_unsupportedDistance")
      systemVerilog must include("io_output_bits_arithmeticOverflow")
    } finally {
      files.close()
    }
  }
}

object AcStrategyCandidateCostEvaluatorSpec {
  private val dctFirst = AcStrategyCode.encoded(AcStrategyCode.Dct, isFirstBlock = true)
  private val verticalFirst =
    AcStrategyCode.encoded(AcStrategyCode.Dct16x8, isFirstBlock = true)
  private val verticalContinuation =
    AcStrategyCode.encoded(AcStrategyCode.Dct16x8, isFirstBlock = false)
  private val horizontalFirst =
    AcStrategyCode.encoded(AcStrategyCode.Dct8x16, isFirstBlock = true)
  private val horizontalContinuation =
    AcStrategyCode.encoded(AcStrategyCode.Dct8x16, isFirstBlock = false)

  def fixedDecision(costs: Seq[BigInt]): Seq[Int] = {
    require(costs.length == 8)
    val verticalDct = Seq(costs(0) + costs(2), costs(1) + costs(3))
    val horizontalDct = Seq(costs(0) + costs(1), costs(2) + costs(3))
    val verticalCost = costs.slice(4, 6).zip(verticalDct).map {
      case (rectangle, dct) => rectangle.min(dct)
    }.sum
    val horizontalCost = costs.slice(6, 8).zip(horizontalDct).map {
      case (rectangle, dct) => rectangle.min(dct)
    }.sum
    val result = Array.fill(4)(dctFirst)
    if (verticalCost < horizontalCost) {
      for (column <- 0 until 2 if costs(4 + column) < verticalDct(column)) {
        result(column) = verticalFirst
        result(column + 2) = verticalContinuation
      }
    } else {
      for (row <- 0 until 2 if costs(6 + row) < horizontalDct(row)) {
        result(row * 2) = horizontalFirst
        result(row * 2 + 1) = horizontalContinuation
      }
    }
    result.toSeq
  }
}
