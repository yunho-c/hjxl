// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.util.Random

class AcStrategyDecisionSelectorSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val costBits = 16

  private case class Costs(dct8x8: Seq[Int], dct16x8: Seq[Int], dct8x16: Seq[Int]) {
    require(dct8x8.length == 4)
    require(dct16x8.length == 2)
    require(dct8x16.length == 2)
  }

  private val dctFirst = AcStrategyCode.encoded(AcStrategyCode.Dct, isFirstBlock = true)
  private val verticalFirst = AcStrategyCode.encoded(AcStrategyCode.Dct16x8, isFirstBlock = true)
  private val verticalContinuation =
    AcStrategyCode.encoded(AcStrategyCode.Dct16x8, isFirstBlock = false)
  private val horizontalFirst =
    AcStrategyCode.encoded(AcStrategyCode.Dct8x16, isFirstBlock = true)
  private val horizontalContinuation =
    AcStrategyCode.encoded(AcStrategyCode.Dct8x16, isFirstBlock = false)

  private def reference(costs: Costs): Seq[Int] = {
    val verticalDct = Seq(
      costs.dct8x8(0) + costs.dct8x8(2),
      costs.dct8x8(1) + costs.dct8x8(3)
    )
    val horizontalDct = Seq(
      costs.dct8x8(0) + costs.dct8x8(1),
      costs.dct8x8(2) + costs.dct8x8(3)
    )
    val verticalWins = costs.dct16x8.zip(verticalDct).map {
      case (rectangle, dct) => rectangle < dct
    }
    val horizontalWins = costs.dct8x16.zip(horizontalDct).map {
      case (rectangle, dct) => rectangle < dct
    }
    val verticalCost = costs.dct16x8.zip(verticalDct).map {
      case (rectangle, dct) => math.min(rectangle, dct)
    }.sum
    val horizontalCost = costs.dct8x16.zip(horizontalDct).map {
      case (rectangle, dct) => math.min(rectangle, dct)
    }.sum
    val decision = Array.fill(4)(dctFirst)
    if (verticalCost < horizontalCost) {
      if (verticalWins(0)) {
        decision(0) = verticalFirst
        decision(2) = verticalContinuation
      }
      if (verticalWins(1)) {
        decision(1) = verticalFirst
        decision(3) = verticalContinuation
      }
    } else {
      if (horizontalWins(0)) {
        decision(0) = horizontalFirst
        decision(1) = horizontalContinuation
      }
      if (horizontalWins(1)) {
        decision(2) = horizontalFirst
        decision(3) = horizontalContinuation
      }
    }
    decision.toSeq
  }

  private def poke(dut: AcStrategyDecisionSelector, costs: Costs): Unit = {
    costs.dct8x8.zipWithIndex.foreach { case (value, index) =>
      dut.io.input.bits.dct8x8(index).poke(value.U)
    }
    costs.dct16x8.zipWithIndex.foreach { case (value, index) =>
      dut.io.input.bits.dct16x8(index).poke(value.U)
    }
    costs.dct8x16.zipWithIndex.foreach { case (value, index) =>
      dut.io.input.bits.dct8x16(index).poke(value.U)
    }
  }

  private def expectCase(dut: AcStrategyDecisionSelector, costs: Costs): Unit = {
    val expected = reference(costs)
    poke(dut, costs)
    dut.io.input.valid.poke(true.B)
    dut.io.output.ready.poke(false.B)
    dut.io.input.ready.expect(false.B)
    dut.io.output.valid.expect(true.B)
    expected.zipWithIndex.foreach { case (value, index) =>
      dut.io.output.bits(index).expect(value.U)
    }
    dut.clock.step(2)
    expected.zipWithIndex.foreach { case (value, index) =>
      dut.io.output.bits(index).expect(value.U)
    }
    dut.io.output.ready.poke(true.B)
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
    dut.io.input.valid.poke(false.B)
  }

  "AcStrategyDecisionSelector matches strict libjxl-tiny orientation and subregion decisions" in {
    val directed = Seq(
      Costs(Seq(100, 100, 100, 100), Seq(50, 60), Seq(500, 500)),
      Costs(Seq(100, 100, 100, 100), Seq(500, 500), Seq(50, 60)),
      Costs(Seq(100, 100, 100, 100), Seq(50, 500), Seq(300, 300)),
      Costs(Seq(100, 100, 100, 100), Seq(300, 300), Seq(50, 500)),
      Costs(Seq(100, 100, 100, 100), Seq(200, 200), Seq(200, 200)),
      Costs(Seq(65535, 65535, 65535, 65535), Seq(65535, 65535), Seq(65535, 65535))
    )
    val random = new Random(0x5a17L)
    val randomized = Seq.fill(512) {
      Costs(
        Seq.fill(4)(random.nextInt(1 << costBits)),
        Seq.fill(2)(random.nextInt(1 << costBits)),
        Seq.fill(2)(random.nextInt(1 << costBits))
      )
    }

    simulate(new AcStrategyDecisionSelector(costBits = costBits)) { dut =>
      (directed ++ randomized).foreach(expectCase(dut, _))
    }
  }

  "aggregate ties choose horizontal candidates and exact subregion ties retain DCT" in {
    val aggregateTie = Costs(
      dct8x8 = Seq(10, 10, 10, 10),
      dct16x8 = Seq(5, 35),
      dct8x16 = Seq(5, 35)
    )
    val subregionTie = Costs(
      dct8x8 = Seq(10, 10, 10, 10),
      dct16x8 = Seq(20, 20),
      dct8x16 = Seq(20, 20)
    )

    reference(aggregateTie) mustBe
      Seq(horizontalFirst, horizontalContinuation, dctFirst, dctFirst)
    reference(subregionTie) mustBe Seq.fill(4)(dctFirst)
    simulate(new AcStrategyDecisionSelector(costBits = costBits)) { dut =>
      expectCase(dut, aggregateTie)
      expectCase(dut, subregionTie)
    }
  }
}
