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
| Zero 8x8 | 1 | 1 | 201 | 201 | 0 | 7 | 12 | 18 | 6 | 225 | 1.125 us |
| Three-AC 8x8 | 1 | 1 | 201 | 201 | 0 | 7 | 15 | 23 | 8 | 230 | 1.150 us |
| Zero 16x8 | 2 | 1 | 402 | 403 | 1 | 12 | 22 | 36 | 14 | 450 | 2.250 us |
| Zero 72x72 | 81 | 4 | 16,281 | 16,361 | 80 | 407 | 818 | 1,464 | 646 | 18,231 | 91.155 us |
| Dense-AC 72x72 | 81 | 4 | 16,281 | 16,361 | 80 | 407 | 16,127 | 17,015 | 888 | 33,782 | 168.910 us |

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

Startup and drain cost are visible in the seven-cycle first-output latency and
six output bubbles for one zero block. The prepared parser incurs one input
stall between blocks. At 81 blocks this creates 80 stalls, while the internal
feed phases increase final-input-to-first-output latency to 407 cycles. The
zero 72x72 result remains close to the tiny baseline at 225.07 cycles/block,
showing that tile metadata has little effect when AC output is sparse.

Steady-state input demand is at least 201 cycles per block because the ABI is a
32-bit word stream. At the unproven 200 MHz synthesis target this corresponds
to an ideal 800 MB/s input bus and about 995 thousand prepared blocks/s before
parser stalls and drain cost. The complete observed zero-frame rate is lower:
about 889 thousand blocks/s from 225 cycles per block.

Output work is content-dependent. Three nonzero AC coefficients add three trace
beats and five total cycles in the one-block fixture. The dense multi-tile case
expands to 417.06 cycles/block and 168.910 microseconds per 72x72 frame at the
unproven 200 MHz target, versus 225.07 cycles/block and 91.155 microseconds for
the zero frame. This bounds logical trace expansion for the current ordinary-
DCT scheduler, but it is not a bound on DMA time, host entropy assembly, or
future non-DCT strategies.

Do not extrapolate these small-frame figures directly to camera-frame FPS. The
current whole-frame register-array architecture, host transfer behavior, and
unmeasured Vivado clock rate all matter. The multi-tile and maximum-density
simulation gaps are now covered; the next performance step is synthesis
resource/timing evidence and replacement of register-array storage before
setting an end-to-end FPS target.
