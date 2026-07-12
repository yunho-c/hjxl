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
- 8-pixel-high all-DCT frames at distance 1 defaults;
- no AXI-Lite transaction overhead, DMA, memory traffic, or clock crossings.

Run the baseline with:

```sh
sbt 'testOnly hjxl.HjxlPreparedDctThroughputSpec'
```

The test prints machine-readable `HJXL_THROUGHPUT` CSV rows and asserts every
field below exactly. An intentional microarchitecture change may lower a cycle
count, but the test and this document must be updated together with an
explanation.

## Baseline measured 2026-07-12

| Case | Blocks | Input words | Input span | Input stalls | First-output latency | Output words | Output span | Output bubbles | Total cycles | Time at 200 MHz |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Zero 8x8 | 1 | 201 | 201 | 0 | 7 | 12 | 18 | 6 | 225 | 1.125 us |
| Three-AC 8x8 | 1 | 201 | 201 | 0 | 7 | 15 | 23 | 8 | 230 | 1.150 us |
| Zero 16x8 | 2 | 402 | 403 | 1 | 12 | 22 | 36 | 14 | 450 | 2.250 us |

Cycle zero is the first accepted input word. Input span runs through the final
accepted input word, first-output latency is measured from that final input to
the first accepted trace, output span covers the first through final accepted
trace, and total cycles includes both endpoints. Output bubbles are cycles in
the output span without a valid trace beat.

The zero cases emit three DC tokens, one strategy record, five metadata tokens,
and three AC nonzero-prefix tokens per one-block frame, with shared tile
metadata accounting for the two-block total. The three-AC case injects one AC
coefficient in each channel and adds three coefficient tokens.

## Interpretation

Startup and drain cost are visible in the seven-cycle first-output latency and
six output bubbles for one zero block. With two blocks, the prepared parser
incurs one input stall between blocks and the measured total remains exactly
225 cycles per block for this zero-content, single-tile case.

Steady-state input demand is at least 201 cycles per block because the ABI is a
32-bit word stream. At the unproven 200 MHz synthesis target this corresponds
to an ideal 800 MB/s input bus and about 995 thousand prepared blocks/s before
parser stalls and drain cost. The complete observed zero-frame rate is lower:
about 889 thousand blocks/s from 225 cycles per block.

Output work is content-dependent. Three nonzero AC coefficients add three trace
beats and five total cycles in the one-block fixture. Dense or adverse
coefficient distributions can expand further, so zero-content timing is not a
worst-case throughput claim.

Do not extrapolate these small-frame figures directly to camera-frame FPS. The
current whole-frame register-array architecture, tile-boundary metadata,
variable token expansion, host transfer behavior, and unmeasured Vivado clock
rate all matter. The next performance step is to add larger multi-tile fixtures
and synthesis resource/timing evidence, then replace register-array storage
before setting an end-to-end FPS target.
