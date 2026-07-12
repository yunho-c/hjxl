// See README.md for license details.

package hjxl

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.jdk.CollectionConverters._

class HjxlKv260PreparedCflDctTopElaborationSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 8)
  private val firtoolOpts = Array(
    "-disable-all-randomization",
    "-strip-debug-info",
    "-default-layer-specialization=enable"
  )

  private class Kv260CflHarness(c: HjxlConfig) extends Module {
    val io = IO(new Bundle {
      val awaddr = Input(UInt(8.W))
      val awvalid = Input(Bool())
      val awready = Output(Bool())
      val wdata = Input(UInt(32.W))
      val wstrb = Input(UInt(4.W))
      val wvalid = Input(Bool())
      val wready = Output(Bool())
      val bresp = Output(UInt(2.W))
      val bvalid = Output(Bool())
      val bready = Input(Bool())
      val araddr = Input(UInt(8.W))
      val arvalid = Input(Bool())
      val arready = Output(Bool())
      val rdata = Output(UInt(32.W))
      val rresp = Output(UInt(2.W))
      val rvalid = Output(Bool())
      val rready = Input(Bool())
      val inputData = Input(UInt(32.W))
      val inputTkeep = Input(UInt(4.W))
      val inputValid = Input(Bool())
      val inputReady = Output(Bool())
      val inputLast = Input(Bool())
      val traceReady = Input(Bool())
      val traceData = Output(UInt(128.W))
      val traceTkeep = Output(UInt(16.W))
      val traceValid = Output(Bool())
      val traceLast = Output(Bool())
      val busy = Output(Bool())
      val overflow = Output(Bool())
      val protocolError = Output(Bool())
      val unsupportedDistance = Output(Bool())
    })

    val top = Module(new HjxlKv260PreparedCflDctTop(c))
    top.ap_clk := clock
    top.ap_rst_n := !reset.asBool
    top.s_axi_control_awaddr := io.awaddr
    top.s_axi_control_awvalid := io.awvalid
    io.awready := top.s_axi_control_awready
    top.s_axi_control_wdata := io.wdata
    top.s_axi_control_wstrb := io.wstrb
    top.s_axi_control_wvalid := io.wvalid
    io.wready := top.s_axi_control_wready
    io.bresp := top.s_axi_control_bresp
    io.bvalid := top.s_axi_control_bvalid
    top.s_axi_control_bready := io.bready
    top.s_axi_control_araddr := io.araddr
    top.s_axi_control_arvalid := io.arvalid
    io.arready := top.s_axi_control_arready
    io.rdata := top.s_axi_control_rdata
    io.rresp := top.s_axi_control_rresp
    io.rvalid := top.s_axi_control_rvalid
    top.s_axi_control_rready := io.rready
    top.s_axis_input_tdata := io.inputData
    top.s_axis_input_tkeep := io.inputTkeep
    top.s_axis_input_tvalid := io.inputValid
    io.inputReady := top.s_axis_input_tready
    top.s_axis_input_tlast := io.inputLast
    top.m_axis_trace_tready := io.traceReady
    io.traceData := top.m_axis_trace_tdata
    io.traceTkeep := top.m_axis_trace_tkeep
    io.traceValid := top.m_axis_trace_tvalid
    io.traceLast := top.m_axis_trace_tlast
    io.busy := top.busy
    io.overflow := top.overflow
    io.protocolError := top.protocol_error
    io.unsupportedDistance := top.unsupported_distance
  }

  private case class TraceFields(stage: Int, group: Int, index: Long, value: Int)

  private def unpackTraceData(data: BigInt): TraceFields = {
    val stage = (data & 0xff).toInt
    val group = ((data >> 8) & 0xffff).toInt
    val index = ((data >> 24) & 0xffffffffL).toLong
    val rawValue = (data >> 56) & 0xffffffffL
    val value = if ((rawValue & 0x80000000L) != 0) {
      (rawValue - (1L << 32)).toInt
    } else {
      rawValue.toInt
    }
    TraceFields(stage, group, index, value)
  }

  private def init(dut: Kv260CflHarness): Unit = {
    dut.io.awaddr.poke(0.U)
    dut.io.awvalid.poke(false.B)
    dut.io.wdata.poke(0.U)
    dut.io.wstrb.poke(0.U)
    dut.io.wvalid.poke(false.B)
    dut.io.bready.poke(false.B)
    dut.io.araddr.poke(0.U)
    dut.io.arvalid.poke(false.B)
    dut.io.rready.poke(false.B)
    dut.io.inputData.poke(0.U)
    dut.io.inputTkeep.poke("hf".U)
    dut.io.inputValid.poke(false.B)
    dut.io.inputLast.poke(false.B)
    dut.io.traceReady.poke(false.B)
    dut.clock.step()
  }

  private def zeroPreparedBlockWords(quant: Int): Seq[BigInt] =
    Seq(
      BigInt(quant),
      BigInt(QuantizeDct8x8Block.DefaultScaleQ16),
      BigInt(QuantizeDct8x8Block.DefaultInvQacQ16),
      BigInt(QuantizeDct8x8Block.DefaultInvDcFactorQ16(0)),
      BigInt(QuantizeDct8x8Block.DefaultInvDcFactorQ16(1)),
      BigInt(QuantizeDct8x8Block.DefaultInvDcFactorQ16(2)),
      BigInt(QuantizeDct8x8Block.DefaultQmMultiplierQ16),
      BigInt(99),
      BigInt(-99) & ((BigInt(1) << 32) - 1)
    ) ++ Seq.fill(PreparedDctStreamLayout.CoefficientWords)(BigInt(0))

  private def zeroCombinedTraceCount(blockCount: Int, tileCount: Int = 1): Int = {
    val metadataTokenCount = tileCount * 2 + blockCount * 3
    blockCount * 3 + blockCount + metadataTokenCount + blockCount * 3
  }

  private def axiWrite(dut: Kv260CflHarness, address: Int, data: BigInt, strb: Int = 0xf): Int = {
    dut.io.awaddr.poke(address.U)
    dut.io.awvalid.poke(true.B)
    dut.io.wdata.poke(data.U)
    dut.io.wstrb.poke(strb.U)
    dut.io.wvalid.poke(true.B)
    dut.io.awready.expect(true.B)
    dut.io.wready.expect(true.B)
    dut.clock.step()

    dut.io.awvalid.poke(false.B)
    dut.io.wvalid.poke(false.B)
    dut.clock.step()

    dut.io.bvalid.expect(true.B)
    val response = dut.io.bresp.peekValue().asBigInt.toInt
    dut.io.bready.poke(true.B)
    dut.clock.step()
    dut.io.bready.poke(false.B)
    response
  }

  private def axiRead(dut: Kv260CflHarness, address: Int): (BigInt, Int) = {
    dut.io.araddr.poke(address.U)
    dut.io.arvalid.poke(true.B)
    dut.io.arready.expect(true.B)
    dut.clock.step()

    dut.io.arvalid.poke(false.B)
    dut.io.rvalid.expect(true.B)
    val data = dut.io.rdata.peekValue().asBigInt
    val response = dut.io.rresp.peekValue().asBigInt.toInt
    dut.io.rready.poke(true.B)
    dut.clock.step()
    dut.io.rready.poke(false.B)
    data -> response
  }

  private def driveInputWord(
      dut: Kv260CflHarness,
      data: BigInt,
      last: Boolean,
      clue: String,
      keep: Int = 0xf
  ): Unit = {
    dut.io.inputData.poke(data.U)
    dut.io.inputTkeep.poke(keep.U)
    dut.io.inputLast.poke(last.B)
    dut.io.inputValid.poke(true.B)
    var cycles = 0
    while (dut.io.inputReady.peekValue().asBigInt == 0 && cycles < 64) {
      dut.clock.step()
      cycles += 1
    }
    withClue(clue) {
      cycles must be < 64
    }
    dut.clock.step()
    dut.io.inputValid.poke(false.B)
    dut.io.inputTkeep.poke("hf".U)
    dut.io.inputLast.poke(false.B)
  }

  private def emittedSystemVerilog: String = {
    val targetDir = Files.createTempDirectory("hjxl-kv260-prepared-cfl-dct-top-elaborate-")
    ChiselStage.emitSystemVerilogFile(
      new HjxlKv260PreparedCflDctTop(config),
      args = Array("--target-dir", targetDir.toString),
      firtoolOpts = firtoolOpts
    )
    val stream = Files.walk(targetDir)
    try {
      val files = stream
        .iterator()
        .asScala
        .filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".sv"))
        .toVector
        .map { path =>
          path.getFileName.toString -> Files.readString(path, StandardCharsets.UTF_8)
        }
      files.map(_._1) must contain("HjxlKv260PreparedCflDctTop.sv")
      files.map { case (name, text) => s"// $name\n$text" }.mkString("\n")
    } finally {
      stream.close()
    }
  }

  "HjxlKv260PreparedCflDctTop emits flat Vivado-style AXI ports" in {
    val text = emittedSystemVerilog
    text must include("module HjxlKv260PreparedCflDctTop")
    for (
      port <- Seq(
        "ap_clk",
        "ap_rst_n",
        "s_axi_control_awaddr",
        "s_axi_control_awvalid",
        "s_axi_control_awready",
        "s_axi_control_wdata",
        "s_axi_control_wstrb",
        "s_axi_control_wvalid",
        "s_axi_control_wready",
        "s_axi_control_bresp",
        "s_axi_control_bvalid",
        "s_axi_control_bready",
        "s_axi_control_araddr",
        "s_axi_control_arvalid",
        "s_axi_control_arready",
        "s_axi_control_rdata",
        "s_axi_control_rresp",
        "s_axi_control_rvalid",
        "s_axi_control_rready",
        "s_axis_input_tdata",
        "s_axis_input_tkeep",
        "s_axis_input_tvalid",
        "s_axis_input_tready",
        "s_axis_input_tlast",
        "m_axis_trace_tdata",
        "m_axis_trace_tkeep",
        "m_axis_trace_tvalid",
        "m_axis_trace_tready",
        "m_axis_trace_tlast",
        "busy",
        "overflow",
        "protocol_error",
        "unsupported_distance"
      )
    ) {
      withClue(s"missing generated estimated-CFL KV260 top port $port") {
        text must include(port)
      }
    }
    val normalizedText = text.replaceAll("\\s+", " ")
    text must include("output [127:0] m_axis_trace_tdata")
    normalizedText must include("input [3:0] s_axis_input_tkeep")
    text must include("assign m_axis_trace_tdata = {40'h0, _core_io_trace_bits_data};")
    text must include("assign m_axis_trace_tkeep = 16'h7FF;")
    text must include("module HjxlPreparedCflDctAxiLiteStreamCore")
    text must include("module HjxlPreparedCflDctAxiStreamCore")
  }

  "HjxlKv260PreparedCflDctTop forwards status and a complete stream to padded trace TLAST" in {
    val words = zeroPreparedBlockWords(quant = 1)
    val expectedTraceCount = zeroCombinedTraceCount(blockCount = 1)
    simulate(new Kv260CflHarness(config)) { dut =>
      init(dut)

      dut.io.traceTkeep.expect("h7ff".U)
      axiWrite(dut, HjxlAxiLiteRegister.Xsize, 8) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.Ysize, 8) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.DistanceQ8, 333) must be(AxiLiteResponse.Okay)
      dut.io.unsupportedDistance.expect(true.B)
      axiRead(dut, HjxlAxiLiteRegister.StatusControl) must be(BigInt(8) -> AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.DistanceQ8, 256) must be(AxiLiteResponse.Okay)
      dut.io.unsupportedDistance.expect(false.B)

      for ((word, ordinal) <- words.zipWithIndex) {
        driveInputWord(
          dut,
          data = word,
          last = ordinal == words.length - 1,
          clue = s"KV260 estimated-CFL prepared frame word $ordinal"
        )
      }
      dut.io.protocolError.expect(false.B)

      dut.io.traceReady.poke(true.B)
      var observed = 0
      var cycles = 0
      val observedTraces = scala.collection.mutable.ArrayBuffer.empty[TraceFields]
      while (observed < expectedTraceCount && cycles < 4096) {
        if (dut.io.traceValid.peekValue().asBigInt != 0) {
          dut.io.traceTkeep.expect("h7ff".U)
          val data = dut.io.traceData.peekValue().asBigInt
          withClue(s"estimated-CFL KV260 trace word $observed should be zero padded above packed 88-bit payload") {
            (data >> 88) mustBe BigInt(0)
          }
          observedTraces += unpackTraceData(data)
          dut.io.traceLast.expect((observed == expectedTraceCount - 1).B)
          observed += 1
        }
        dut.clock.step()
        cycles += 1
      }
      withClue("estimated-CFL KV260 flat prepared stream did not emit the expected token trace count") {
        observed mustBe expectedTraceCount
      }
      observedTraces.map(_.stage).toSeq mustBe
        Seq.fill(3)(TraceStage.DcTokens) ++
          Seq(TraceStage.AcStrategy) ++
          Seq.fill(5)(TraceStage.AcMetadataTokens) ++
          Seq.fill(3)(TraceStage.AcTokens)
      dut.io.traceValid.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "HjxlKv260PreparedCflDctTop preserves estimated-CFL stream framing for exact 72px capacity" in {
    val exactConfig = HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 8)
    val blockCount = 9
    val tileCount = 2
    val words = Seq.tabulate(blockCount)(index => zeroPreparedBlockWords(quant = 1 + index % 3)).flatten
    val expectedTraceCount = zeroCombinedTraceCount(blockCount = blockCount, tileCount = tileCount)
    simulate(new Kv260CflHarness(exactConfig)) { dut =>
      init(dut)
      axiWrite(dut, HjxlAxiLiteRegister.Xsize, 72) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.Ysize, 8) must be(AxiLiteResponse.Okay)

      for ((word, ordinal) <- words.zipWithIndex) {
        driveInputWord(
          dut,
          data = word,
          last = ordinal == words.length - 1,
          clue = s"exact-capacity estimated-CFL KV260 prepared word $ordinal"
        )
      }
      dut.io.protocolError.expect(false.B)

      dut.io.traceReady.poke(true.B)
      var observed = 0
      var cycles = 0
      val observedTraces = scala.collection.mutable.ArrayBuffer.empty[TraceFields]
      while (observed < expectedTraceCount && cycles < 16384) {
        if (dut.io.traceValid.peekValue().asBigInt != 0) {
          dut.io.traceTkeep.expect("h7ff".U)
          val data = dut.io.traceData.peekValue().asBigInt
          withClue(s"exact-capacity estimated-CFL KV260 trace word $observed should be zero padded above packed 88-bit payload") {
            (data >> 88) mustBe BigInt(0)
          }
          observedTraces += unpackTraceData(data)
          dut.io.traceLast.expect((observed == expectedTraceCount - 1).B)
          observed += 1
        }
        dut.clock.step()
        cycles += 1
      }
      withClue("exact-capacity estimated-CFL KV260 stream did not emit the expected token trace count") {
        observed mustBe expectedTraceCount
      }
      observedTraces.count(_.stage == TraceStage.DcTokens) mustBe blockCount * 3
      observedTraces.count(_.stage == TraceStage.AcStrategy) mustBe blockCount
      observedTraces.count(_.stage == TraceStage.AcMetadataTokens) mustBe tileCount * 2 + blockCount * 3
      observedTraces.count(_.stage == TraceStage.AcTokens) mustBe blockCount * 3
      dut.io.traceValid.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "HjxlKv260PreparedCflDctTop reports and clears partial input keep masks" in {
    simulate(new Kv260CflHarness(config)) { dut =>
      init(dut)
      axiWrite(dut, HjxlAxiLiteRegister.Xsize, 16) must be(AxiLiteResponse.Okay)
      axiWrite(dut, HjxlAxiLiteRegister.Ysize, 8) must be(AxiLiteResponse.Okay)

      driveInputWord(dut, data = 1, last = false, keep = 0x7, clue = "estimated-CFL KV260 partial keep")
      dut.io.protocolError.expect(true.B)
      val (status, statusResponse) = axiRead(dut, HjxlAxiLiteRegister.StatusControl)
      statusResponse must be(AxiLiteResponse.Okay)
      (status & 1) must be(BigInt(1))
      dut.io.busy.expect(false.B)

      axiWrite(dut, HjxlAxiLiteRegister.StatusControl, 1) must be(AxiLiteResponse.Okay)
      dut.io.protocolError.expect(false.B)
      axiRead(dut, HjxlAxiLiteRegister.StatusControl) must be(BigInt(0) -> AxiLiteResponse.Okay)
    }
  }
}
