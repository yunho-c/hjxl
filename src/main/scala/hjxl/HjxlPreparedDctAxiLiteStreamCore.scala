// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** AXI4-Lite controlled wrapper around `HjxlPreparedDctAxiStreamCore`.
  *
  * This keeps the same register map as `HjxlAxiLiteStreamCore` so the RGB
  * stream shell and the prepared-DCT stream shell share one host control shape.
  * The prepared-DCT stream consumes `xsize`/`ysize` and status/control directly:
  * status bit 0 is protocol error, bit 1 is busy, bit 2 is overflow, and bit 3
  * reports unsupported distance fallback. The remaining `FrameConfig` fields
  * are preserved for a uniform top-level interface and future prepared-path
  * experiments.
  */
class HjxlPreparedDctAxiLiteStreamCore(
    c: HjxlConfig = HjxlConfig(),
    axiAddrBits: Int = 8
) extends Module {
  require(axiAddrBits >= 5, "axiAddrBits must address the complete register map")
  require(c.coordBits <= 32, "AXI-Lite xsize/ysize registers are 32 bits")

  private val dataBits = 32
  private val wordAddrBits = axiAddrBits - 2

  val inputDataBits = 32
  val traceDataBits = 8 + c.groupBits + 32 + c.traceValueBits

  val io = IO(new Bundle {
    val control = new AxiLiteSlave(axiAddrBits, dataBits)
    val input = Flipped(Decoupled(new AxiStreamWord(inputDataBits)))
    val trace = Decoupled(new AxiStreamWord(traceDataBits))
    val busy = Output(Bool())
    val overflow = Output(Bool())
    val protocolError = Output(Bool())
    val unsupportedDistance = Output(Bool())
  })

  private def word(byteAddress: Int): UInt =
    (byteAddress / 4).U(wordAddrBits.W)

  private def strobeMask(strb: UInt): UInt =
    Cat((0 until dataBits / 8).reverse.map(i => Fill(8, strb(i))))

  private def mergeWrite(oldValue: UInt, writeValue: UInt, strb: UInt): UInt = {
    val mask = strobeMask(strb)
    (oldValue & ~mask).asUInt | (writeValue & mask).asUInt
  }

  private def flagsWord(
      enableXyb: Bool,
      enableDct: Bool,
      enableQuant: Bool,
      enableTokenize: Bool,
      tokenSelect: UInt
  ): UInt =
    Cat(0.U(22.W), tokenSelect, 0.U(4.W), enableTokenize, enableQuant, enableDct, enableXyb)

  val xsize = RegInit(1.U(c.coordBits.W))
  val ysize = RegInit(1.U(c.coordBits.W))
  val distanceQ8 = RegInit(256.U(16.W))
  val fixedPointScale = RegInit(0.U(16.W))
  val fixedInvQacQ16 = RegInit(0.U(32.W))
  val fixedRawQuant = RegInit(0.U(8.W))
  val enableXyb = RegInit(false.B)
  val enableDct = RegInit(true.B)
  val enableQuant = RegInit(true.B)
  val enableTokenize = RegInit(true.B)
  val tokenSelect = RegInit(TokenTraceSelect.AcTokens.U(2.W))

  val distanceStatus = Module(new DistanceParamsLookup)
  distanceStatus.io.distanceQ8 := distanceQ8

  val stream = Module(new HjxlPreparedDctAxiStreamCore(c))
  stream.io.config.xsize := xsize
  stream.io.config.ysize := ysize
  stream.io.config.distanceQ8 := distanceQ8
  stream.io.config.fixedPointScale := fixedPointScale
  stream.io.config.fixedInvQacQ16 := fixedInvQacQ16
  stream.io.config.fixedRawQuant := fixedRawQuant
  stream.io.config.enableXyb := enableXyb
  stream.io.config.enableDct := enableDct
  stream.io.config.enableQuant := enableQuant
  stream.io.config.enableTokenize := enableTokenize
  stream.io.config.tokenSelect := tokenSelect

  stream.io.input.valid := io.input.valid
  io.input.ready := stream.io.input.ready
  stream.io.input.bits := io.input.bits

  io.trace.valid := stream.io.trace.valid
  stream.io.trace.ready := io.trace.ready
  io.trace.bits := stream.io.trace.bits
  io.busy := stream.io.busy
  io.overflow := stream.io.overflow
  io.protocolError := stream.io.protocolError
  io.unsupportedDistance := !distanceStatus.io.supported

  val clearProtocolError = WireDefault(false.B)
  stream.io.clearProtocolError := clearProtocolError

  val awPending = RegInit(false.B)
  val awAddr = Reg(UInt(axiAddrBits.W))
  val wPending = RegInit(false.B)
  val wData = Reg(UInt(dataBits.W))
  val wStrb = Reg(UInt((dataBits / 8).W))
  val bValid = RegInit(false.B)
  val bResp = RegInit(AxiLiteResponse.Okay.U(2.W))

  io.control.aw.ready := !awPending
  io.control.w.ready := !wPending
  io.control.b.valid := bValid
  io.control.b.bits.resp := bResp

  when(io.control.aw.fire) {
    awPending := true.B
    awAddr := io.control.aw.bits.addr
  }

  when(io.control.w.fire) {
    wPending := true.B
    wData := io.control.w.bits.data
    wStrb := io.control.w.bits.strb
  }

  when(io.control.b.fire) {
    bValid := false.B
  }

  val writeReady = awPending && wPending && !bValid
  val writeWord = awAddr(axiAddrBits - 1, 2)
  val writeOkay = WireDefault(false.B)

  when(writeReady) {
    awPending := false.B
    wPending := false.B
    bValid := true.B

    switch(writeWord) {
      is(word(HjxlAxiLiteRegister.StatusControl)) {
        writeOkay := true.B
        when(wStrb(0) && wData(HjxlStatusControlBit.ClearProtocolError)) {
          clearProtocolError := true.B
        }
      }
      is(word(HjxlAxiLiteRegister.Xsize)) {
        writeOkay := true.B
        xsize := mergeWrite(xsize.pad(dataBits), wData, wStrb)(c.coordBits - 1, 0)
      }
      is(word(HjxlAxiLiteRegister.Ysize)) {
        writeOkay := true.B
        ysize := mergeWrite(ysize.pad(dataBits), wData, wStrb)(c.coordBits - 1, 0)
      }
      is(word(HjxlAxiLiteRegister.DistanceQ8)) {
        writeOkay := true.B
        distanceQ8 := mergeWrite(distanceQ8.pad(dataBits), wData, wStrb)(15, 0)
      }
      is(word(HjxlAxiLiteRegister.FixedPointScale)) {
        writeOkay := true.B
        fixedPointScale := mergeWrite(fixedPointScale.pad(dataBits), wData, wStrb)(15, 0)
      }
      is(word(HjxlAxiLiteRegister.FixedInvQacQ16)) {
        writeOkay := true.B
        fixedInvQacQ16 := mergeWrite(fixedInvQacQ16, wData, wStrb)
      }
      is(word(HjxlAxiLiteRegister.FixedRawQuant)) {
        writeOkay := true.B
        fixedRawQuant := mergeWrite(fixedRawQuant.pad(dataBits), wData, wStrb)(7, 0)
      }
      is(word(HjxlAxiLiteRegister.Flags)) {
        writeOkay := true.B
        val mergedFlags =
          mergeWrite(
            flagsWord(enableXyb, enableDct, enableQuant, enableTokenize, tokenSelect),
            wData,
            wStrb
          )
        enableXyb := mergedFlags(0)
        enableDct := mergedFlags(1)
        enableQuant := mergedFlags(2)
        enableTokenize := mergedFlags(3)
        tokenSelect := mergedFlags(9, 8)
      }
    }

    bResp := Mux(writeOkay, AxiLiteResponse.Okay.U, AxiLiteResponse.Decerr.U)
  }

  val rValid = RegInit(false.B)
  val rData = Reg(UInt(dataBits.W))
  val rResp = RegInit(AxiLiteResponse.Okay.U(2.W))

  io.control.ar.ready := !rValid
  io.control.r.valid := rValid
  io.control.r.bits.data := rData
  io.control.r.bits.resp := rResp

  when(io.control.r.fire) {
    rValid := false.B
  }

  val readWord = io.control.ar.bits.addr(axiAddrBits - 1, 2)
  val readData = WireDefault(0.U(dataBits.W))
  val readOkay = WireDefault(false.B)

  switch(readWord) {
    is(word(HjxlAxiLiteRegister.StatusControl)) {
      readOkay := true.B
      readData := Cat(
        0.U((dataBits - 4).W),
        io.unsupportedDistance,
        stream.io.overflow,
        stream.io.busy,
        stream.io.protocolError
      )
    }
    is(word(HjxlAxiLiteRegister.Xsize)) {
      readOkay := true.B
      readData := xsize.pad(dataBits)
    }
    is(word(HjxlAxiLiteRegister.Ysize)) {
      readOkay := true.B
      readData := ysize.pad(dataBits)
    }
    is(word(HjxlAxiLiteRegister.DistanceQ8)) {
      readOkay := true.B
      readData := distanceQ8.pad(dataBits)
    }
    is(word(HjxlAxiLiteRegister.FixedPointScale)) {
      readOkay := true.B
      readData := fixedPointScale.pad(dataBits)
    }
    is(word(HjxlAxiLiteRegister.FixedInvQacQ16)) {
      readOkay := true.B
      readData := fixedInvQacQ16
    }
    is(word(HjxlAxiLiteRegister.FixedRawQuant)) {
      readOkay := true.B
      readData := fixedRawQuant.pad(dataBits)
    }
    is(word(HjxlAxiLiteRegister.Flags)) {
      readOkay := true.B
      readData := flagsWord(enableXyb, enableDct, enableQuant, enableTokenize, tokenSelect)
    }
  }

  when(io.control.ar.fire) {
    rValid := true.B
    rData := readData
    rResp := Mux(readOkay, AxiLiteResponse.Okay.U, AxiLiteResponse.Decerr.U)
  }
}
