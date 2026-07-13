// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

class AcStrategyCandidateCosts(costBits: Int) extends Bundle {
  require(costBits > 0, "AC-strategy cost width must be positive")

  /** Raster order: top-left, top-right, bottom-left, bottom-right. */
  val dct8x8 = Vec(4, UInt(costBits.W))

  /** Left and right 16x8-pixel candidates. */
  val dct16x8 = Vec(2, UInt(costBits.W))

  /** Top and bottom 8x16-pixel candidates. */
  val dct8x16 = Vec(2, UInt(costBits.W))
}

/** Exact libjxl-tiny strategy decision for one complete 2x2 block region.
  *
  * Candidate costs may use any common unsigned fixed-point scale. The selector
  * first chooses the cheaper aggregate rectangular orientation, with ties going
  * to 8x16, and then replaces only the rectangles that strictly beat their two
  * constituent 8x8 costs. Output is raster ordered and uses
  * `(rawStrategy << 1) | isFirstBlock`.
  */
class AcStrategyDecisionSelector(costBits: Int = 48) extends Module {
  require(costBits > 0, "AC-strategy cost width must be positive")

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new AcStrategyCandidateCosts(costBits)))
    val output = Decoupled(Vec(4, UInt(3.W)))
  })

  val verticalDct = Wire(Vec(2, UInt((costBits + 1).W)))
  verticalDct(0) := io.input.bits.dct8x8(0) +& io.input.bits.dct8x8(2)
  verticalDct(1) := io.input.bits.dct8x8(1) +& io.input.bits.dct8x8(3)
  val horizontalDct = Wire(Vec(2, UInt((costBits + 1).W)))
  horizontalDct(0) := io.input.bits.dct8x8(0) +& io.input.bits.dct8x8(1)
  horizontalDct(1) := io.input.bits.dct8x8(2) +& io.input.bits.dct8x8(3)

  val verticalWins = Wire(Vec(2, Bool()))
  val horizontalWins = Wire(Vec(2, Bool()))
  for (index <- 0 until 2) {
    verticalWins(index) := io.input.bits.dct16x8(index) < verticalDct(index)
    horizontalWins(index) := io.input.bits.dct8x16(index) < horizontalDct(index)
  }

  val verticalBest = Wire(Vec(2, UInt((costBits + 1).W)))
  val horizontalBest = Wire(Vec(2, UInt((costBits + 1).W)))
  for (index <- 0 until 2) {
    verticalBest(index) := Mux(
      verticalWins(index),
      io.input.bits.dct16x8(index).pad(costBits + 1),
      verticalDct(index)
    )
    horizontalBest(index) := Mux(
      horizontalWins(index),
      io.input.bits.dct8x16(index).pad(costBits + 1),
      horizontalDct(index)
    )
  }
  val verticalCost = verticalBest(0) +& verticalBest(1)
  val horizontalCost = horizontalBest(0) +& horizontalBest(1)
  val chooseVertical = verticalCost < horizontalCost

  val dctFirst = AcStrategyCode.encoded(AcStrategyCode.Dct, isFirstBlock = true).U(3.W)
  val verticalFirst =
    AcStrategyCode.encoded(AcStrategyCode.Dct16x8, isFirstBlock = true).U(3.W)
  val verticalContinuation =
    AcStrategyCode.encoded(AcStrategyCode.Dct16x8, isFirstBlock = false).U(3.W)
  val horizontalFirst =
    AcStrategyCode.encoded(AcStrategyCode.Dct8x16, isFirstBlock = true).U(3.W)
  val horizontalContinuation =
    AcStrategyCode.encoded(AcStrategyCode.Dct8x16, isFirstBlock = false).U(3.W)

  val decision = Wire(Vec(4, UInt(3.W)))
  decision.foreach(_ := dctFirst)
  when(chooseVertical) {
    when(verticalWins(0)) {
      decision(0) := verticalFirst
      decision(2) := verticalContinuation
    }
    when(verticalWins(1)) {
      decision(1) := verticalFirst
      decision(3) := verticalContinuation
    }
  }.otherwise {
    when(horizontalWins(0)) {
      decision(0) := horizontalFirst
      decision(1) := horizontalContinuation
    }
    when(horizontalWins(1)) {
      decision(2) := horizontalFirst
      decision(3) := horizontalContinuation
    }
  }

  io.input.ready := io.output.ready
  io.output.valid := io.input.valid
  io.output.bits := decision
}
