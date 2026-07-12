# KV260 physical-feasibility flow

This directory contains the first reproducible physical-feasibility gate for
the frozen near-term FPGA target, `HjxlKv260PreparedDctTop`. It performs
out-of-context synthesis for the KV260's commercial Kria K26 device, writes a
post-synthesis checkpoint, and produces utilization, timing, clock,
methodology, and constraint reports.

The target identifiers are:

- device: `xck26-sfvc784-2LV-c`
- board: `xilinx.com:kv260_som:part0:1.4`

They follow Xilinx's
[KV260 SOM board definition](https://github.com/Xilinx/XilinxBoardStore/blob/2022.2/boards/Xilinx/kv260_som/1.4/board.xml).
The flow continues with the exact device part if the optional board files are
not installed because this top is synthesized out of context and has no carrier
pin assignments.

## Timing contract

The initial gate targets 200 MHz with these explicit assumptions:

- `ap_clk` has a 5.000 ns period and 0.200 ns clock uncertainty.
- Every AXI signal is synchronous to `ap_clk`.
- The containing block design receives 1.000 ns for input-side logic/routing
  and 1.000 ns for output-side logic/routing.
- `ap_rst_n` is asynchronous and excluded from synchronous I/O timing.

Any later clock-domain crossing invalidates this contract until the XDC is
updated. This is an out-of-context estimate, not proof that a complete KV260
design will close timing.

## Resource budget

The accelerator may consume at most 70% of each reported K26 resource class
(LUT, register, BRAM, URAM, and DSP) in this first gate. The remaining 30% is
reserved for the Zynq MPSoC block design, AXI interconnect, DMA, clock/reset
logic, and implementation headroom. Post-synthesis worst slack must be
non-negative at 200 MHz. Treat either threshold violation as an architecture
finding, not as a reason to weaken the gate without measured justification.

Vivado's `utilization.rpt` supplies device-relative percentages. The threshold
is currently reviewed manually because no synthesis result has yet been
produced on this machine.

## Run

Generate the selected top and validate the inputs without requiring Vivado:

```sh
sbt 'runMain hjxl.ElaborateKv260PreparedDctTop'
tclsh fpga/vivado/synth.tcl --preflight-only
```

Preflight validates the generated file list, every referenced SystemVerilog
file, the selected top declaration, the clock value, and XDC Tcl syntax. CI
regenerates the top and runs this command on every normal test workflow.

Then run synthesis in an AMD Vivado environment:

```sh
vivado -mode batch -source fpga/vivado/synth.tcl
```

Results are written beneath `build/vivado/kv260-prepared-dct/`, which is
gitignored. Negative slack fails the batch by default; use
`--allow-negative-slack` only to preserve exploratory reports while diagnosing
a known timing failure.

Expected outputs include:

- `post_synth.dcp`
- `summary.txt`
- `utilization.rpt` and `utilization_hierarchical.rpt`
- `timing_summary.rpt` and `timing_paths.rpt`
- `check_timing.rpt`, `methodology.rpt`, and `clock_utilization.rpt`

This flow does not create an MPSoC block design, place or route the design,
generate a bitstream/XSA, integrate DMA, or prove execution on a KV260. Those
remain separate bring-up milestones after this synthesis gate has real timing
and resource evidence.
