# Prepared-DCT performance baseline

This document records the first deterministic cycle baseline for the frozen
near-term FPGA target's direct prepared-DCT stream core. It is a simulation
regression, not measured FPGA performance.

## Measurement contract

`HjxlPreparedDctThroughputSpec` drives
`HjxlPreparedDctAxiStreamCore` with:

- continuous input `valid` whenever a source word remains;
- continuous output `ready`;
- Q16 prepared coefficients and the 201-word direct block ABI;
- all-DCT frames at distance 1 defaults;
- no AXI-Lite transaction overhead, DMA, memory traffic, or clock crossings.

The profiling elaboration is parameterized for up to 72x72 pixels so that one
case crosses both the horizontal and vertical 64-pixel CFL tile boundaries.
That is a performance-lab configuration of the direct prepared core, not a
claim that the frozen default 32x32 KV260 elaboration has changed or that either
configuration is physically viable.

Run the baseline with:

```sh
sbt 'testOnly hjxl.HjxlPreparedDctThroughputSpec'
```

The test prints machine-readable `HJXL_THROUGHPUT` CSV rows and asserts every
field below exactly. An intentional microarchitecture change may lower a cycle
count, but the test and this document must be updated together with an
explanation.

## Baseline measured 2026-07-12

| Case | Blocks | Tiles | Input words | Input span | Input stalls | First-output latency | Output words | Output span | Output bubbles | Total cycles | Time at 200 MHz |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Zero 8x8 | 1 | 1 | 201 | 201 | 0 | 5 | 12 | 137 | 125 | 342 | 1.710 us |
| Three-AC 8x8 | 1 | 1 | 201 | 201 | 0 | 5 | 15 | 142 | 127 | 347 | 1.735 us |
| Zero 16x8 | 2 | 1 | 402 | 403 | 1 | 8 | 22 | 201 | 179 | 611 | 3.055 us |
| Zero 72x72 | 81 | 4 | 16,281 | 16,361 | 80 | 245 | 818 | 5,888 | 5,070 | 22,493 | 112.465 us |
| Dense-AC 72x72 | 81 | 4 | 16,281 | 16,361 | 80 | 245 | 16,127 | 17,015 | 888 | 33,620 | 168.100 us |

Cycle zero is the first accepted input word. Input span runs through the final
accepted input word, first-output latency is measured from that final input to
the first accepted trace, output span covers the first through final accepted
trace, and total cycles includes both endpoints. Output bubbles are cycles in
the output span without a valid trace beat.

The zero cases emit three DC tokens, one strategy record, tile/block metadata,
and three AC nonzero-prefix tokens per block. The three-AC case injects one AC
coefficient in each channel and adds three coefficient tokens. The dense case
sets all 63 AC positions nonzero in all three channels of every block. Its
15,552 AC trace rows are therefore the maximum ordinary-DCT coefficient-token
population for this 81-block geometry: `81 * 3 * (1 prefix + 63 values)`.
Together with 243 DC, 81 strategy, and 251 metadata rows, that produces the
measured 16,127 output words.

## Interpretation

The five-cycle first-output latency still reflects the DC path; AC storage does
not delay the beginning of the trace stream. The prepared parser incurs one
input stall between blocks. At 81 blocks this creates 80 stalls, while the
internal DC plane-reorder feed phase increases final-input-to-first-output
latency to 245 cycles. Those input figures are unchanged by the AC memory:
`PreparedAcCoefficientFrameStore` serializes each accepted block into 64
96-bit writes, which complete before the 201-word parser can present the next
block.

Readback is content-sensitive because the scheduler prefetches the next 64-word
block while current AC tokens emit. A zero block provides too little output
work to hide the read, so the 72x72 sparse case measures 277.69 cycles/block
and 5,070 output bubbles. A dense block provides enough work to hide every
subsequent prefetch; its 17,015-cycle output span and 33,620-cycle frame total
are unchanged from the previous register-array baseline. This is an explicit
area/latency tradeoff rather than an unexplained regression.

Steady-state input demand is at least 201 cycles per block because the ABI is a
32-bit word stream. At the unproven 200 MHz synthesis target this corresponds
to an ideal 800 MB/s input bus and about 995 thousand prepared blocks/s before
parser stalls and drain cost. The complete observed 72x72 zero-frame rate is
about 720 thousand blocks/s from 277.69 cycles per block.

Output work is content-dependent. Three nonzero AC coefficients add three trace
beats and five total cycles in the one-block fixture. The dense multi-tile case
expands to 415.06 cycles/block and 168.100 microseconds per 72x72 frame at the
unproven 200 MHz target, versus 277.69 cycles/block and 112.465 microseconds for
the zero frame. This bounds logical trace expansion for the current ordinary-
DCT scheduler, but it is not a bound on DMA time, host entropy assembly, or
future non-DCT strategies.

Do not extrapolate these small-frame figures directly to camera-frame FPS. The
remaining whole-frame register arrays, host transfer behavior, and unmeasured
Vivado clock rate all matter. The largest AC coefficient plane on the frozen
direct path now emits as a 1024x96 synchronous memory at default capacity, but
actual BRAM inference, utilization, and timing still require Vivado. The next
performance step is that physical evidence plus conversion of the remaining
frame-scaled stores before setting an end-to-end FPS target.

## Adaptive RGB integration note — 2026-07-13

The RGB adaptive path is functionally connected to quantization and logical
tokens, but it does not yet have a locked cycle baseline comparable to the
prepared-DCT table above. Its per-block inverse AC scale is a 33-cycle restoring
divider, while HF, color, and gamma AQ modulation already use sequential block
walkers. The quantized and combined-token hierarchies each elaborate one
RGB-to-XYB converter and three `Dct8x8Approx` instances; the AC-metadata-only
hierarchy deliberately contains one converter and no DCT or reciprocal module.

Current generated-RTL sizes are 37 files/46,043 lines for adaptive quantized
traces, 28 files/33,198 lines for adaptive AC metadata, and 45 files/51,960
lines for the combined token top. These are complexity indicators, not area or
timing measurements. Before making RGB throughput claims, add a deterministic
phase/cycle regression for sparse and dense multi-block frames and then obtain
Vivado utilization and timing evidence; the prepared-DCT numbers must not be
used as a proxy for this substantially larger hierarchy.
