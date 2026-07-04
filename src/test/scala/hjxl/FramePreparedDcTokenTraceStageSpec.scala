// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class FramePreparedDcTokenTraceStageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 16)

  private def pokeConfig(dut: FramePreparedDcTokenTraceStage, width: Int, height: Int): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(QuantizeDct8x8Block.DefaultScaleQ16.U)
    dut.io.config.fixedInvQacQ16.poke(QuantizeDct8x8Block.DefaultInvQacQ16.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.enableXyb.poke(false.B)
    dut.io.config.enableDct.poke(false.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(true.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.Dc.U)
  }

  private def clampedGradient(north: Int, west: Int, northwest: Int): Int = {
    val minValue = math.min(north, west)
    val maxValue = math.max(north, west)
    val gradient = north + west - northwest
    if (northwest < minValue) maxValue else if (northwest > maxValue) minValue else gradient
  }

  private def packSigned(value: Int): Int =
    if (value < 0) -2 * value - 1 else 2 * value

  private def gradientContext(gradProp: Int): Int = {
    val thresholds = Seq(
      (12, 44), (120, 43), (257, 40), (321, 39), (385, 38), (417, 37), (449, 36), (465, 35),
      (481, 34), (489, 33), (497, 32), (501, 31), (505, 30), (508, 29), (509, 28), (511, 27),
      (512, 26), (513, 42), (515, 41), (517, 25), (519, 24), (523, 23), (527, 22), (535, 21),
      (543, 20), (559, 19), (575, 18), (607, 17), (639, 16), (703, 15), (767, 14), (904, 13),
      (1012, 12)
    )
    thresholds.collectFirst { case (limit, context) if gradProp <= limit => context }.getOrElse(11)
  }

  private def expectedTokens(planes: Seq[Seq[Int]], xBlocks: Int): Seq[(Int, Int)] =
    planes.flatMap { plane =>
      plane.indices.map { ordinal =>
        val blockX = ordinal % xBlocks
        val blockY = ordinal / xBlocks
        val west = if (blockX == 0) plane(ordinal) else plane(ordinal - 1)
        val north = if (blockY == 0) plane(ordinal) else plane(ordinal - xBlocks)
        val northwest = if (blockX == 0 || blockY == 0) plane(ordinal) else plane(ordinal - xBlocks - 1)
        val left = if (blockX != 0) west else if (blockY != 0) north else 0
        val top = if (blockY != 0) north else left
        val topLeft = if (blockX != 0 && blockY != 0) northwest else left
        val guess = clampedGradient(top, left, topLeft)
        val residual = plane(ordinal) - guess
        val gradProp = math.max(
          DcTokenize.GradRangeMin,
          math.min(DcTokenize.GradRangeMax, DcTokenize.GradRangeMid + top + left - topLeft)
        )
        gradientContext(gradProp) -> packSigned(residual)
      }
    }

  "FramePreparedDcTokenTraceStage tokenizes prepared DC planes in Y/X/B raster order" in {
    simulate(new FramePreparedDcTokenTraceStage(config)) { dut =>
      val width = 9
      val height = 9
      val xBlocks = 2
      val planes = Seq(
        Seq(10, 12, 20, 25),
        Seq(-3, -1, 4, 7),
        Seq(50, 49, 51, 60)
      )
      val samples = planes.flatten
      val expected = expectedTokens(planes, xBlocks)

      pokeConfig(dut, width, height)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for (sample <- samples) {
        dut.io.input.valid.poke(true.B)
        dut.io.input.bits.poke(sample.S)
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)

      for (((context, value), ordinal) <- expected.zipWithIndex) {
        withClue(s"DC token $ordinal context=$context value=$value") {
          dut.io.trace.valid.expect(true.B)
          dut.io.trace.bits.stage.expect(TraceStage.DcTokens.U)
          dut.io.trace.bits.group.expect(ordinal.U)
          dut.io.trace.bits.index.expect(context.U)
          dut.io.trace.bits.value.expect(value.S)
        }
        dut.clock.step()
      }

      dut.io.trace.valid.expect(false.B)
      dut.io.input.ready.expect(true.B)
      dut.io.overflow.expect(false.B)
    }
  }
}
