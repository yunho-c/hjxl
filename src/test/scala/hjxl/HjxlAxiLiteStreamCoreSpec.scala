// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class HjxlAxiLiteStreamCoreSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 8, maxFrameHeight = 8)

  private def init(dut: HjxlAxiLiteStreamCore): Unit = {
    dut.io.control.aw.valid.poke(false.B)
    dut.io.control.aw.bits.addr.poke(0.U)
    dut.io.control.w.valid.poke(false.B)
    dut.io.control.w.bits.data.poke(0.U)
    dut.io.control.w.bits.strb.poke(0.U)
    dut.io.control.b.ready.poke(false.B)
    dut.io.control.ar.valid.poke(false.B)
    dut.io.control.ar.bits.addr.poke(0.U)
    dut.io.control.r.ready.poke(false.B)
    dut.io.input.valid.poke(false.B)
    dut.io.input.bits.data.poke(0.U)
    dut.io.input.bits.last.poke(false.B)
    dut.io.trace.ready.poke(false.B)
    dut.clock.step()
  }

  private def axiWrite(dut: HjxlAxiLiteStreamCore, address: Int, data: BigInt, strb: Int = 0xf): Int = {
    dut.io.control.aw.valid.poke(true.B)
    dut.io.control.aw.bits.addr.poke(address.U)
    dut.io.control.w.valid.poke(true.B)
    dut.io.control.w.bits.data.poke(data.U)
    dut.io.control.w.bits.strb.poke(strb.U)
    dut.io.control.aw.ready.expect(true.B)
    dut.io.control.w.ready.expect(true.B)
    dut.clock.step()

    dut.io.control.aw.valid.poke(false.B)
    dut.io.control.w.valid.poke(false.B)
    dut.clock.step()

    dut.io.control.b.valid.expect(true.B)
    val resp = dut.io.control.b.bits.resp.peekValue().asBigInt.toInt
    dut.io.control.b.ready.poke(true.B)
    dut.clock.step()
    dut.io.control.b.ready.poke(false.B)
    resp
  }

  private def axiRead(dut: HjxlAxiLiteStreamCore, address: Int): (BigInt, Int) = {
    dut.io.control.ar.valid.poke(true.B)
    dut.io.control.ar.bits.addr.poke(address.U)
    dut.io.control.ar.ready.expect(true.B)
    dut.clock.step()

    dut.io.control.ar.valid.poke(false.B)
    dut.io.control.r.valid.expect(true.B)
    val data = dut.io.control.r.bits.data.peekValue().asBigInt
    val resp = dut.io.control.r.bits.resp.peekValue().asBigInt.toInt
    dut.io.control.r.ready.poke(true.B)
    dut.clock.step()
    dut.io.control.r.ready.poke(false.B)
    data -> resp
  }

  private def drivePixel(dut: HjxlAxiLiteStreamCore, data: BigInt, last: Boolean): Unit = {
    dut.io.input.valid.poke(true.B)
    dut.io.input.bits.data.poke(data.U)
    dut.io.input.bits.last.poke(last.B)
    dut.io.input.ready.expect(true.B)
    dut.clock.step()
    dut.io.input.valid.poke(false.B)
  }

  private def rgb(r: Int, g: Int, b: Int): BigInt =
    BigInt(r & 0xffff) | (BigInt(g & 0xffff) << 16) | (BigInt(b & 0xffff) << 32)

  "HjxlAxiLiteStreamCore exposes writable configuration registers" in {
    simulate(new HjxlAxiLiteStreamCore(config, traceRoute = TraceStage.InputPadded)) { dut =>
      init(dut)

      axiWrite(dut, HjxlAxiLiteRegister.Xsize, 0x1234) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.Ysize, 0x5678) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.DistanceQ8, 0x3456) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.FixedPointScale, 0x789a) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.FixedInvQacQ16, 0x89abcdefL) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.FixedRawQuant, 0xee) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.Flags, 0x20d) must be(AxiLiteResponse.Okay)

      axiRead(dut, HjxlAxiLiteRegister.Xsize) must be(BigInt(0x1234) -> AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.Ysize) must be(BigInt(0x5678) -> AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.DistanceQ8) must be(BigInt(0x3456) -> AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.FixedPointScale) must be(BigInt(0x789a) -> AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.FixedInvQacQ16) must be(BigInt(0x89abcdefL) -> AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.FixedRawQuant) must be(BigInt(0xee) -> AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.Flags) must be(BigInt(0x20d) -> AxiLiteResponse.Okay)
    }
  }

  "HjxlAxiLiteStreamCore honors byte strobes" in {
    simulate(new HjxlAxiLiteStreamCore(config, traceRoute = TraceStage.InputPadded)) { dut =>
      init(dut)

      axiWrite(dut, HjxlAxiLiteRegister.Xsize, 0xabcd0000L, strb = 0xc) must be(AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.Xsize) must be(BigInt(1) -> AxiLiteResponse.Okay)

      axiWrite(dut, HjxlAxiLiteRegister.Xsize, 0x00001234, strb = 0x3) must be(AxiLiteResponse.Okay)
      axiRead(dut, HjxlAxiLiteRegister.Xsize) must be(BigInt(0x1234) -> AxiLiteResponse.Okay)
    }
  }

  "HjxlAxiLiteStreamCore reports decode errors for unmapped registers" in {
    simulate(new HjxlAxiLiteStreamCore(config, traceRoute = TraceStage.InputPadded)) { dut =>
      init(dut)

      axiWrite(dut, 0x40, 0x1234) must be(AxiLiteResponse.Decerr)
      axiRead(dut, 0x40) must be(BigInt(0) -> AxiLiteResponse.Decerr)
    }
  }

  "HjxlAxiLiteStreamCore clears stream protocol errors through status/control" in {
    simulate(new HjxlAxiLiteStreamCore(config, traceRoute = TraceStage.InputPadded)) { dut =>
      init(dut)

      axiWrite(dut, HjxlAxiLiteRegister.Xsize, 2) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.Ysize, 1) must be(AxiLiteResponse.Okay)

      drivePixel(dut, rgb(10, 20, 30), last = true)
      dut.io.protocolError.expect(true.B)
      axiRead(dut, HjxlAxiLiteRegister.StatusControl) must be(BigInt(1) -> AxiLiteResponse.Okay)

      axiWrite(dut, HjxlAxiLiteRegister.StatusControl, 1) must be(AxiLiteResponse.Okay)
      dut.io.protocolError.expect(false.B)
      axiRead(dut, HjxlAxiLiteRegister.StatusControl) must be(BigInt(0) -> AxiLiteResponse.Okay)
    }
  }
}
