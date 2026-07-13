// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class PreparedAcCoefficientFrameStoreSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 8)
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim

  private def coefficients(seed: Int): Vector[Vector[Int]] =
    Vector.tabulate(3, blockSize) { (channel, coefficient) =>
      val magnitude = seed + channel * 1000 + coefficient
      if ((channel + coefficient) % 2 == 0) magnitude else -magnitude
    }

  private def pokeBlock(dut: PreparedAcCoefficientFrameStore, values: Vector[Vector[Int]]): Unit = {
    for {
      channel <- 0 until 3
      coefficient <- 0 until blockSize
    } {
      dut.io.write.bits.quantized(channel)(coefficient).poke(values(channel)(coefficient).S)
    }
  }

  private def expectBlock(dut: PreparedAcCoefficientFrameStore, values: Vector[Vector[Int]]): Unit = {
    for {
      channel <- 0 until 3
      coefficient <- 0 until blockSize
    } {
      dut.io.readResponse.bits.quantized(channel)(coefficient).expect(values(channel)(coefficient).S)
    }
  }

  private def waitForWriteReady(dut: PreparedAcCoefficientFrameStore): Int = {
    var cycles = 0
    while (dut.io.write.ready.peekValue().asBigInt == 0 && cycles < blockSize + 2) {
      dut.clock.step()
      cycles += 1
    }
    cycles must be <= blockSize
    cycles
  }

  private def readBlock(
      dut: PreparedAcCoefficientFrameStore,
      block: Int,
      expected: Vector[Vector[Int]]
  ): Unit = {
    dut.io.readRequest.bits.poke(block.U)
    dut.io.readRequest.valid.poke(true.B)
    dut.io.readRequest.ready.expect(true.B)
    dut.clock.step()
    dut.io.readRequest.valid.poke(false.B)

    var cycles = 0
    while (dut.io.readResponse.valid.peekValue().asBigInt == 0 && cycles < blockSize + 3) {
      dut.io.busy.expect(true.B)
      dut.clock.step()
      cycles += 1
    }
    cycles mustBe blockSize + 1

    dut.io.readResponse.valid.expect(true.B)
    dut.io.write.ready.expect(false.B)
    expectBlock(dut, expected)
    dut.clock.step(3)
    dut.io.readResponse.valid.expect(true.B)
    expectBlock(dut, expected)

    dut.io.readResponse.ready.poke(true.B)
    dut.clock.step()
    dut.io.readResponse.ready.poke(false.B)
    dut.io.busy.expect(false.B)
    dut.io.write.ready.expect(true.B)
  }

  "PreparedAcCoefficientFrameStore serializes blocks into narrow synchronous storage" in {
    simulate(new PreparedAcCoefficientFrameStore(config)) { dut =>
      val first = coefficients(seed = 10)
      val second = coefficients(seed = 10000)

      dut.io.writeBlock.poke(0.U)
      dut.io.write.valid.poke(false.B)
      dut.io.readRequest.bits.poke(0.U)
      dut.io.readRequest.valid.poke(false.B)
      dut.io.readResponse.ready.poke(false.B)
      dut.clock.step()

      for ((values, block) <- Seq(first, second).zipWithIndex) {
        pokeBlock(dut, values)
        dut.io.writeBlock.poke(block.U)
        dut.io.write.valid.poke(true.B)
        dut.io.write.ready.expect(true.B)
        dut.clock.step()
        dut.io.write.valid.poke(false.B)
        dut.io.busy.expect(true.B)
        waitForWriteReady(dut) mustBe blockSize
      }

      readBlock(dut, block = 1, expected = second)
      readBlock(dut, block = 0, expected = first)
    }
  }
}
