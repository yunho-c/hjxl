// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

class PreparedAcStrategy2x2Output extends Bundle {
  val decision = Vec(4, UInt(3.W))
  val costs = new AcStrategyCandidateCosts(64)
  val unsupportedDistance = Bool()
  val arithmeticOverflow = Bool()
}

/** Scores and selects one complete prepared 2x2-block AC-strategy region.
  *
  * Inputs arrive in the same order used by libjxl-tiny's search: four raster
  * 8x8 candidates, left/right 16x8 candidates, then top/bottom 8x16
  * candidates. Each record carries canonical Q12 coefficients and its covered
  * AQ/mask maxima. The module retains all eight scaled costs, applies the exact
  * orientation/subregion decision, and holds the result under backpressure.
  */
class PreparedAcStrategy2x2Selector(coefficientBits: Int = 32) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new AcStrategyCandidateCostInput(coefficientBits)))
    val output = Decoupled(new PreparedAcStrategy2x2Output)
    val busy = Output(Bool())
  })

  val evaluator = Module(new AcStrategyCandidateCostEvaluator(coefficientBits))
  val selector = Module(new AcStrategyDecisionSelector(costBits = 64))

  val candidateIndex = RegInit(0.U(3.W))
  val costs = Reg(new AcStrategyCandidateCosts(64))
  val unsupportedDistance = RegInit(false.B)
  val arithmeticOverflow = RegInit(false.B)
  val complete = RegInit(false.B)

  val expectedStrategy = Mux(
    candidateIndex < 4.U,
    AcStrategyCode.Dct.U,
    Mux(candidateIndex < 6.U, AcStrategyCode.Dct16x8.U, AcStrategyCode.Dct8x16.U)
  )

  evaluator.io.input.valid := io.input.valid && !complete
  evaluator.io.input.bits := io.input.bits
  io.input.ready := evaluator.io.input.ready && !complete
  when(io.input.fire) {
    assert(
      io.input.bits.strategy === expectedStrategy,
      "prepared AC-strategy candidates must use DCT,DCT,DCT,DCT,16x8,16x8,8x16,8x16 order"
    )
  }

  evaluator.io.output.ready := !complete
  when(evaluator.io.output.fire) {
    when(candidateIndex < 4.U) {
      costs.dct8x8(candidateIndex(1, 0)) := evaluator.io.output.bits.scaledCostQ16
    }.elsewhen(candidateIndex < 6.U) {
      costs.dct16x8(candidateIndex(0)) := evaluator.io.output.bits.scaledCostQ16
    }.otherwise {
      costs.dct8x16(candidateIndex(0)) := evaluator.io.output.bits.scaledCostQ16
    }
    unsupportedDistance := unsupportedDistance || evaluator.io.output.bits.unsupportedDistance
    arithmeticOverflow := arithmeticOverflow || evaluator.io.output.bits.arithmeticOverflow
    when(candidateIndex === 7.U) {
      candidateIndex := 0.U
      complete := true.B
    }.otherwise {
      candidateIndex := candidateIndex + 1.U
    }
  }

  selector.io.input.valid := complete
  selector.io.input.bits := costs
  selector.io.output.ready := io.output.ready
  io.output.valid := selector.io.output.valid
  io.output.bits.decision := selector.io.output.bits
  io.output.bits.costs := costs
  io.output.bits.unsupportedDistance := unsupportedDistance
  io.output.bits.arithmeticOverflow := arithmeticOverflow

  when(io.output.fire) {
    complete := false.B
    unsupportedDistance := false.B
    arithmeticOverflow := false.B
  }

  io.busy := evaluator.io.busy || candidateIndex =/= 0.U || complete
}
