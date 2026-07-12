// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class FramePadTraceStageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 8, maxFrameHeight = 8)

  private def pokeConfig(dut: FramePadTraceStage, width: Int, height: Int): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(256.U)
    dut.io.config.fixedInvQacQ16.poke(QuantizeDct8x8Block.invQacQ16For(256).U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.fixedYtox.poke(0.S)
    dut.io.config.fixedYtob.poke(0.S)
    dut.io.config.enableXyb.poke(false.B)
    dut.io.config.enableDct.poke(false.B)
    dut.io.config.enableQuant.poke(false.B)
    dut.io.config.enableTokenize.poke(false.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.Dc.U)
  }

  private def drivePixel(
      dut: FramePadTraceStage,
      x: Int,
      y: Int,
      r: Int,
      g: Int,
      b: Int
  ): Unit = {
    dut.io.input.valid.poke(true.B)
    dut.io.input.bits.x.poke(x.U)
    dut.io.input.bits.y.poke(y.U)
    dut.io.input.bits.r.poke(r.S)
    dut.io.input.bits.g.poke(g.S)
    dut.io.input.bits.b.poke(b.S)
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
  }

  private def expectedChannel(values: Seq[Seq[Int]]): Seq[Int] = {
    val width = values.head.length
    val height = values.length
    val paddedWidth = 8
    val paddedHeight = 8
    for {
      y <- 0 until paddedHeight
      x <- 0 until paddedWidth
    } yield values(math.min(y, height - 1))(math.min(x, width - 1))
  }

  "FramePadTraceStage pads a small frame by repeating right and bottom edges" in {
    simulate(new FramePadTraceStage(config)) { dut =>
      pokeConfig(dut, width = 2, height = 2)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      drivePixel(dut, x = 0, y = 0, r = 1, g = 10, b = 100)
      drivePixel(dut, x = 1, y = 0, r = 2, g = 20, b = 200)
      drivePixel(dut, x = 0, y = 1, r = 3, g = 30, b = 300)
      drivePixel(dut, x = 1, y = 1, r = 4, g = 40, b = 400)
      dut.io.input.valid.poke(false.B)

      val expected =
        expectedChannel(Seq(Seq(1, 2), Seq(3, 4))) ++
          expectedChannel(Seq(Seq(10, 20), Seq(30, 40))) ++
          expectedChannel(Seq(Seq(100, 200), Seq(300, 400)))

      dut.io.trace.ready.poke(true.B)
      for ((value, index) <- expected.zipWithIndex) {
        dut.io.trace.valid.expect(true.B)
        dut.io.trace.bits.stage.expect(TraceStage.InputPadded.U)
        dut.io.trace.bits.index.expect(index.U)
        dut.io.trace.bits.value.expect(value.S)
        dut.io.traceLast.expect((index == expected.length - 1).B)
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.input.ready.expect(true.B)
      dut.io.overflow.expect(false.B)
    }
  }
}
