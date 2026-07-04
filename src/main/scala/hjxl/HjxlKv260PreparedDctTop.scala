// See README.md for license details.

package hjxl

import chisel3._

/** Vivado-friendly top around the prepared-DCT AXI-Lite/AXI-stream core.
  *
  * This wrapper keeps the tested `HjxlPreparedDctAxiLiteStreamCore` behavior,
  * but exposes flat AXI-style port names for block-design integration. The
  * reset is active-low to match the common `ap_clk` / `ap_rst_n` convention.
  * Trace output TDATA is padded to `traceBusBytes`; TKEEP marks the low bytes
  * that contain the packed `{value,index,group,stage}` trace word.
  */
class HjxlKv260PreparedDctTop(
    c: HjxlConfig = HjxlConfig(),
    axiAddrBits: Int = 8,
    traceBusBytes: Int = 16
) extends RawModule {
  private val controlDataBits = 32
  private val controlStrobeBits = controlDataBits / 8
  private val inputDataBits = 32
  private val traceDataBits = 8 + c.groupBits + 32 + c.traceValueBits
  private val traceWordBytes = (traceDataBits + 7) / 8
  private val traceBusBits = traceBusBytes * 8
  require(traceBusBytes >= traceWordBytes, "traceBusBytes must fit the packed trace word")

  val ap_clk = IO(Input(Clock()))
  val ap_rst_n = IO(Input(Bool()))

  val s_axi_control_awaddr = IO(Input(UInt(axiAddrBits.W)))
  val s_axi_control_awvalid = IO(Input(Bool()))
  val s_axi_control_awready = IO(Output(Bool()))
  val s_axi_control_wdata = IO(Input(UInt(controlDataBits.W)))
  val s_axi_control_wstrb = IO(Input(UInt(controlStrobeBits.W)))
  val s_axi_control_wvalid = IO(Input(Bool()))
  val s_axi_control_wready = IO(Output(Bool()))
  val s_axi_control_bresp = IO(Output(UInt(2.W)))
  val s_axi_control_bvalid = IO(Output(Bool()))
  val s_axi_control_bready = IO(Input(Bool()))
  val s_axi_control_araddr = IO(Input(UInt(axiAddrBits.W)))
  val s_axi_control_arvalid = IO(Input(Bool()))
  val s_axi_control_arready = IO(Output(Bool()))
  val s_axi_control_rdata = IO(Output(UInt(controlDataBits.W)))
  val s_axi_control_rresp = IO(Output(UInt(2.W)))
  val s_axi_control_rvalid = IO(Output(Bool()))
  val s_axi_control_rready = IO(Input(Bool()))

  val s_axis_input_tdata = IO(Input(UInt(inputDataBits.W)))
  val s_axis_input_tvalid = IO(Input(Bool()))
  val s_axis_input_tready = IO(Output(Bool()))
  val s_axis_input_tlast = IO(Input(Bool()))

  val m_axis_trace_tdata = IO(Output(UInt(traceBusBits.W)))
  val m_axis_trace_tkeep = IO(Output(UInt(traceBusBytes.W)))
  val m_axis_trace_tvalid = IO(Output(Bool()))
  val m_axis_trace_tready = IO(Input(Bool()))
  val m_axis_trace_tlast = IO(Output(Bool()))

  val busy = IO(Output(Bool()))
  val overflow = IO(Output(Bool()))
  val protocol_error = IO(Output(Bool()))

  withClockAndReset(ap_clk, !ap_rst_n) {
    val core = Module(new HjxlPreparedDctAxiLiteStreamCore(c, axiAddrBits))

    core.io.control.aw.bits.addr := s_axi_control_awaddr
    core.io.control.aw.valid := s_axi_control_awvalid
    s_axi_control_awready := core.io.control.aw.ready

    core.io.control.w.bits.data := s_axi_control_wdata
    core.io.control.w.bits.strb := s_axi_control_wstrb
    core.io.control.w.valid := s_axi_control_wvalid
    s_axi_control_wready := core.io.control.w.ready

    s_axi_control_bresp := core.io.control.b.bits.resp
    s_axi_control_bvalid := core.io.control.b.valid
    core.io.control.b.ready := s_axi_control_bready

    core.io.control.ar.bits.addr := s_axi_control_araddr
    core.io.control.ar.valid := s_axi_control_arvalid
    s_axi_control_arready := core.io.control.ar.ready

    s_axi_control_rdata := core.io.control.r.bits.data
    s_axi_control_rresp := core.io.control.r.bits.resp
    s_axi_control_rvalid := core.io.control.r.valid
    core.io.control.r.ready := s_axi_control_rready

    core.io.input.bits.data := s_axis_input_tdata
    core.io.input.bits.last := s_axis_input_tlast
    core.io.input.valid := s_axis_input_tvalid
    s_axis_input_tready := core.io.input.ready

    m_axis_trace_tdata := core.io.trace.bits.data.pad(traceBusBits)
    m_axis_trace_tkeep := ((BigInt(1) << traceWordBytes) - 1).U(traceBusBytes.W)
    m_axis_trace_tlast := core.io.trace.bits.last
    m_axis_trace_tvalid := core.io.trace.valid
    core.io.trace.ready := m_axis_trace_tready

    busy := core.io.busy
    overflow := core.io.overflow
    protocol_error := core.io.protocolError
  }
}
