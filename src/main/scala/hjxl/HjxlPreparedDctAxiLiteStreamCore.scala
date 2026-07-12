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
  * experiments. The input stream is a full-word 32-bit payload stream; a beat
  * with an input keep mask other than 0xF is consumed by this wrapper and
  * reported as a protocol error without advancing the prepared-word parser.
  * Writes while busy update the readable register bank for the next frame;
  * `HjxlPreparedDctAxiStreamCore` snapshots the complete configuration on its
  * first accepted word and holds it until the active trace stream completes.
  */
class HjxlPreparedDctAxiLiteStreamCore(
    c: HjxlConfig = HjxlConfig(),
    axiAddrBits: Int = HjxlAbiGenerated.AxiLite.AddrBits,
    estimatedCfl: Boolean = false
) extends Module {
  require(axiAddrBits >= 6, "axiAddrBits must address the complete register map")
  require(c.coordBits <= 32, "AXI-Lite xsize/ysize registers are 32 bits")

  private val dataBits = HjxlAbiGenerated.AxiLite.DataBits
  private val wordAddrBits = axiAddrBits - 2

  val inputDataBits = HjxlAbiGenerated.PreparedDctStream.WordBits
  private val inputWordBytes = inputDataBits / 8
  val traceDataBits =
    HjxlAbiGenerated.Trace.StageBits + c.groupBits + HjxlAbiGenerated.Trace.IndexBits + c.traceValueBits

  val io = IO(new Bundle {
    val control = new AxiLiteSlave(axiAddrBits, dataBits)
    val inputKeep = Input(UInt(inputWordBytes.W))
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
  val fixedYtox = RegInit(0.U(8.W))
  val fixedYtob = RegInit(0.U(8.W))
  val enableXyb = RegInit(false.B)
  val enableDct = RegInit(true.B)
  val enableQuant = RegInit(true.B)
  val enableTokenize = RegInit(true.B)
  val tokenSelect = RegInit(TokenTraceSelect.AcTokens.U(2.W))

  val distanceStatus = Module(new DistanceParamsLookup)
  distanceStatus.io.distanceQ8 := distanceQ8

  val streamIo =
    if (estimatedCfl) {
      val stream = Module(new HjxlPreparedCflDctAxiStreamCore(c))
      stream.io
    } else {
      val stream = Module(new HjxlPreparedDctAxiStreamCore(c))
      stream.io
    }
  val inputKeepError = RegInit(false.B)
  val inputKeepFull = io.inputKeep === ((BigInt(1) << inputWordBytes) - 1).U(inputWordBytes.W)

  streamIo.config.xsize := xsize
  streamIo.config.ysize := ysize
  streamIo.config.distanceQ8 := distanceQ8
  streamIo.config.fixedPointScale := fixedPointScale
  streamIo.config.fixedInvQacQ16 := fixedInvQacQ16
  streamIo.config.fixedRawQuant := fixedRawQuant
  streamIo.config.fixedYtox := fixedYtox.asSInt
  streamIo.config.fixedYtob := fixedYtob.asSInt
  streamIo.config.enableXyb := enableXyb
  streamIo.config.enableDct := enableDct
  streamIo.config.enableQuant := enableQuant
  streamIo.config.enableTokenize := enableTokenize
  streamIo.config.tokenSelect := tokenSelect

  streamIo.input.valid := io.input.valid && inputKeepFull
  io.input.ready := Mux(inputKeepFull, streamIo.input.ready, true.B)
  streamIo.input.bits := io.input.bits

  io.trace.valid := streamIo.trace.valid
  streamIo.trace.ready := io.trace.ready
  io.trace.bits := streamIo.trace.bits
  val protocolErrorStatus = streamIo.protocolError || inputKeepError
  io.busy := streamIo.busy
  io.overflow := streamIo.overflow
  io.protocolError := protocolErrorStatus
  io.unsupportedDistance := !distanceStatus.io.supported

  val clearProtocolError = WireDefault(false.B)
  streamIo.clearProtocolError := clearProtocolError

  when(clearProtocolError) {
    inputKeepError := false.B
  }

  when(io.input.fire && !inputKeepFull) {
    inputKeepError := true.B
  }

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
      is(word(HjxlAxiLiteRegister.FixedYtox)) {
        writeOkay := true.B
        fixedYtox := mergeWrite(fixedYtox.pad(dataBits), wData, wStrb)(7, 0)
      }
      is(word(HjxlAxiLiteRegister.FixedYtob)) {
        writeOkay := true.B
        fixedYtob := mergeWrite(fixedYtob.pad(dataBits), wData, wStrb)(7, 0)
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
        streamIo.overflow,
        streamIo.busy,
        protocolErrorStatus
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
    is(word(HjxlAxiLiteRegister.FixedYtox)) {
      readOkay := true.B
      readData := fixedYtox.pad(dataBits)
    }
    is(word(HjxlAxiLiteRegister.FixedYtob)) {
      readOkay := true.B
      readData := fixedYtob.pad(dataBits)
    }
  }

  when(io.control.ar.fire) {
    rValid := true.B
    rData := readData
    rResp := Mux(readOkay, AxiLiteResponse.Okay.U, AxiLiteResponse.Decerr.U)
  }
}

/** AXI4-Lite controlled wrapper around `HjxlPreparedCflDctAxiStreamCore`.
  *
  * This preserves the prepared-DCT AXI-Lite register map and stream ABI, but
  * uses the estimated-CFL prepared stream shell internally. Incoming `ytox` and
  * `ytob` words remain part of the input stream for compatibility; tile CFL
  * maps are estimated from prepared coefficients before tokenization.
  */
class HjxlPreparedCflDctAxiLiteStreamCore(
    c: HjxlConfig = HjxlConfig(),
    axiAddrBits: Int = HjxlAbiGenerated.AxiLite.AddrBits
) extends HjxlPreparedDctAxiLiteStreamCore(c, axiAddrBits, estimatedCfl = true)
