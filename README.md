# hjxl

Chisel RTL for a hardware-accelerated JPEG XL encoder targeting practical
parity with
[libjxl-tiny](https://github.com/libjxl/libjxl-tiny), not the full JPEG XL
encoder surface.

## Project status

`hjxl` is a verification-led research prototype, not yet a complete or
board-proven FPGA encoder.

| Area | Current state |
| --- | --- |
| Strongest validated boundary | Host-prepared, all-DCT coefficients through quantization and logical token traces, followed by host-side codestream assembly |
| RGB-input pipeline | Frame padding, approximate XYB/DCT, adaptive per-block raw quantization, and RGB-derived tile CFL through quantized traces, AC metadata, and a combined all-DCT logical-token path; focused routes now generate a tile-correct adaptive 8x8/16x8/8x16 strategy map and its strategy-adjusted raw-quant field from the same AQ/CFL data, but downstream rectangular quantization/tokens and RGB-token codestream proof remain incomplete |
| Hardware output | Trace/token records; entropy optimization and final JPEG XL bitstream assembly remain host software responsibilities |
| Near-term FPGA top | `HjxlKv260PreparedDctTop`, the direct prepared-DCT variant, is frozen as the first synthesis and bring-up target |
| Physical validation | Chisel simulation and generated SystemVerilog are covered; Vivado synthesis, timing closure, resource use, bitstream generation, and KV260 execution have not been demonstrated |

The estimated-CFL top remains an experimental sibling until the direct top has
physical feasibility data. See
[`docs/quality-assessment-2026-07-12.md`](docs/quality-assessment-2026-07-12.md)
for the evidence, limitations, and prioritized roadmap behind this status, and
[`docs/performance.md`](docs/performance.md) for the direct prepared-DCT cycle
baseline.

Current RTL status: the top-level buffers a small RGB frame and emits one of
several trace streams: libjxl-tiny-compatible `input_padded`, padded
channel-first XYB when only `enableXyb` is set, raster 8x8-block raw scaled-DCT
coefficients when `enableDct` is set, adaptive-raw-quant DCT-only block records
when `enableDct && enableQuant` are set, DC residual tokens when
`enableDct && enableQuant && enableTokenize && tokenSelect=Dc` are set,
estimated-CFL AC-metadata tokens carrying the adaptive raw-quant field when
`enableQuant && enableTokenize && tokenSelect=AcMetadata` are set, or the
AC strategy map when only `enableQuant` is set. The default all-route shell
retains its inexpensive all-DCT map; a compile-time focused
`traceRoute = TraceStage.AcStrategy` build instead performs the adaptive
tile-local search described below. When `enableXyb && enableQuant` are set without DCT
or tokenization and `tokenSelect=AqContrast`, the RGB core emits the first
image-dependent adaptive-quantization seam: global quarter-resolution
`AqContrast` cells corresponding to libjxl-tiny's pre-erosion contrast grid.
A focused `traceRoute = TraceStage.AqFuzzyErosion` build instead composes that
stage with the next AQ step and emits one erosion-derived Q16 value per padded
8x8 block. A focused `traceRoute = TraceStage.AqStrategyMask` build continues
through the pre-modulation reciprocal mask consumed by AC-strategy scoring. A
focused `traceRoute = TraceStage.AqNonlinearMask` build instead applies
libjxl-tiny's separate `_compute_mask` function to the erosion grid and emits
the signed-Q24 log-domain seed used by final AQ-map modulation. A focused
`traceRoute = TraceStage.AqHfModulation` build continues through the first
per-block modulation, subtracting the weighted sum of the 112 internal
horizontal/vertical Y edges from each seed. A focused
`traceRoute = TraceStage.AqColorModulation` build then applies the reference's
distance-dependent baseline plus capped red/blue coverage terms. A focused
`traceRoute = TraceStage.AqGammaModulation` build continues through the final
log-domain per-block modulation by averaging the red/green inverse gamma
responses. A focused `traceRoute = TraceStage.AqFinalMap` build exponentiates
that seed, applies the distance-derived Q24 scale and high-distance damping,
and emits the completed unsigned-Q24 AQ map. A focused
`traceRoute = TraceStage.RawQuantField` core now converts that RGB-derived map
to a raw-quant byte for each padded 8x8 block, runs the adaptive strategy search,
and raises both bytes in each selected rectangle to their local maximum; a nonzero
`fixedRawQuant` selects the retained fixed override instead. `AqMapToRawQuant` and
`FramePreparedAqRawQuantTraceStage` retain the narrow prepared adaptive-
quantization seam: the host supplies libjxl-tiny's image-dependent AQ map and
inverse global AC scale as unsigned Q24 values, and RTL performs the exact
nearest-integer conversion, `[1, 255]` clamp, raster trace ordering, and frame
delimiter. `FrameAqContrastTraceStage` now owns the upstream RGB-to-XYB/local-
contrast pass, using a piecewise Q16 gamma-ratio approximation and integer
square root before emitting one Q16 value per 4x4 padded-pixel cell.
`AqFuzzyErosionSample`, `FramePreparedAqFuzzyErosionTraceStage`, and
`FrameAqFuzzyErosionTraceStage` implement the four-minimum 3x3 erosion and 2x2
cell-to-block reduction, with a one-LSB prepared Q16 seam and the composed RGB
route. `AqStrategyMaskReciprocal` then evaluates the Q16 strategy mask with a
42-cycle restoring divider, and its prepared and RGB-composed frame stages
emit `AqStrategyMask` block traces. The shared nonlinear/final-map chain now
also carries that exact mask as a sideband into the focused AC-strategy
scheduler. `AqNonlinearMaskEvaluator`
evaluates the other erosion-derived branch in signed Q24, sharing one 56-cycle
restoring divider
across its three rational terms; prepared and RGB-composed frame stages emit
`AqNonlinearMask` traces. `AqHfModulationBlock` then walks one Q12 Y block over
64 cycles, and its prepared/RGB frame stages emit cumulative signed-Q24
`AqHfModulation` traces. `FrameAqModulationBlockStage` is the shared
RGB-to-padded-block boundary: it captures the existing converter's accepted XYB
stream once and holds frame completion until the cumulative modulation chain
finishes. The focused HF build stores only Y; the color build also retains X/B.
The gamma build uses the same X/Y/B scheduler storage.
`AqColorModulationBlock` walks the same 8x8 block over 64 cycles, accumulates
Q16 red and blue coverage, applies capped distance-folded Q24 coefficients, and
emits cumulative `AqColorModulation` traces without a runtime divider.
`AqHfColorModulationBlockPipeline` preserves the block's X/Y context for the
next stage; `AqGammaModulationBlock` alternates red- and green-derived inverse
ratio lookups over 128 cycles, averages them in Q20, applies a normalized Q20
`fast_log2f` approximation, and emits cumulative signed-Q24
`AqGammaModulation` traces. `AqHfColorGammaModulationBlockPipeline` retains the
X/Y/B block plus the remaining distance/configuration metadata;
`AqFastExpQ24` uses normalized fractional
`fast_pow2f` interpolation, and `AqFinalModulationBlock` applies Q24
scale/dampen/add arithmetic to emit `AqFinalMap`. `FrameAqFinalMapPipeline`
shares that completed chain between the final-map trace, pre-strategy raw-quant
conversion, and downstream DCT quantization without repeating RGB-to-XYB or
frame storage. `FrameAqDctBlockStage` converts each completed AQ block to one
native-Q12 all-DCT record without elaborating reciprocal hardware.
`FrameAqDctOnlyBlockStage` enriches that record with the matching per-block
inverse AC scale from the 33-cycle `AdaptiveInvQacQ16` restoring divider. Nonzero
`fixedRawQuant` retains the explicit byte/`fixedInvQacQ16` experiment contract;
zero selects adaptive raw quant and ignores the fixed reciprocal.
`FrameAqCflMapTraceStage` buffers those DCT records and estimates one signed
Y-to-X/Y-to-B pair per 64x64 tile. The estimator consumes Q16 internally, so
the RGB Q12 values are widened at that boundary while the existing prepared
Q16 ABI remains unchanged. `FrameAqCflDctOnlyQuantizeTraceStage` and
`FrameAqCflDctOnlyAcMetadataTokenTraceStage` feed the resulting maps plus
adaptive raw quant through the exact prepared quantization/metadata schedulers;
the metadata route omits the unused reciprocal. The combined
`FrameAqCflDctOnlyQuantizeTokenTraceStage` emits DC, all-DCT strategy, adaptive
AC-metadata, and AC logical tokens, and supplies the focused core AC-token
route. Focused `TraceStage.YtoxMap` and `TraceStage.YtobMap` routes expose the
estimated maps directly. The earlier `FrameAqDctOnly*` and
`FrameCflMapTraceStage` wrappers remain fixed-CFL diagnostics. Later stages
still need to consume the selected rectangles and strategy-adjusted raw quant
in quantization/metadata/tokens, plus entropy coding, bitstream assembly, and an
RGB-token-to-codestream parity proof. `Dct16Approx`, `Dct16x8Approx`, and
`Dct8x16Approx` now provide the Q12 recursive kernel and both canonical
rectangular coefficient layouts required by scoring and later quantization.
`AcStrategyCandidateCostEvaluator` walks prepared canonical Q12 X/Y/B
coefficients one per cycle, approximates libjxl-tiny's entropy/loss score in
fixed point, and applies the supported distance multiplier. The
`PreparedAcStrategy2x2Selector` sequences four 8x8, two 16x8, and two 8x16
candidates into `AcStrategyDecisionSelector`, which implements the reference's
exact orientation, strict replacement, tie, and first/continuation semantics.
`FramePreparedAcStrategyTraceStage` buffers prepared raster Q12 XYB/DCT blocks
and their raw-quant bytes, fits CFL once per 64x64 tile, visits only complete
tile-local 2x2 regions, forms candidate AQ/mask maxima, computes rectangular
transforms, then applies the reference post-search quant-field adjustment before
emitting the full raster strategy grid and an aligned adjusted-byte sideband;
incomplete tile/frame rows and columns stay DCT.
`FrameAqAcStrategyTraceStage` composes that boundary after the shared RGB AQ/DCT
block source. `FrameAqAdjustedRawQuantTraceStage` adapts the same result into a
`RawQuantField` trace, and `FrameAdjustedRawQuantFieldTraceStage` preserves the
cheap uniform fixed override. Focused core routes select these stages without
adding the heavy search to the default all-route shell. The strategy and
adjusted-quant results do not yet feed the all-DCT quantization or token
schedulers.
`tools/hjxl_reference.py --scaled-dct-q12-csv ...` exports independent signed
transform fixtures plus the exact fixed transform result;
`--ac-strategy-cost-q16-csv ...` exports the prepared candidate-cost seam,
fixed-model result, float reference cost, scorer decision, and the exact
scheduler-fixed decision including Q12 rectangular transforms and fixed CFL.
The fixed arithmetic is exact against its oracle, but Q12 input rounding can
change near-symmetric orientation choices.
Standalone fixed-point primitives also cover approximate RGB-to-XYB, 1D DCT-8,
and the scaled 8x8 DCT block layout used by libjxl-tiny. The RGB-to-XYB primitive
applies the signed matrix at Q26, clamps
the biased mixed absorbance at Q24, normalizes it by powers of eight, and
linearly interpolates a 225-entry Q5 cube-root table before emitting Q12 XYB
samples. Unlike the original range-limited LUT, this covers the full positive
signed-16/Q8 RGB domain without saturating absorbance above 2.0.
`DistanceParamsLookup` provides the first hardware distance-parameter boundary
for common Q8 distances `64`, `128`, `256`, `512`, `1024`, and `2048`, with
unsupported values falling back to distance 1 and AXI-Lite status bit 3
reporting that fallback. RGB adaptive quantization now uses the lookup's AC/DC
scalars and recomputes the inverse AC scale for each adaptive raw-quant byte;
the retained fixed schedulers still use the raw-quant-5 reciprocal. The same
lookup carries the exact Q24 AQ scale, damping, and
inverse global AC scale, so unsupported distances fall back consistently across
the whole final AQ path. `fixedPointScale` plus
`fixedInvQacQ16` remain AC-scale overrides for trace experiments. `fixedRawQuant`
can override the adaptive raw-quant route with one global byte, and
`fixedYtox`/`fixedYtob` provide signed 8-bit scalar CFL overrides for retained
fixed-path diagnostics; current core quantization, AC-metadata, focused CFL-map,
and focused AC-token routes estimate their tile maps from RGB-derived DCTs.
`AcQuantScaleSelector` is the small reusable RTL boundary that chooses between
lookup parameters and explicit override parameters before frame schedulers feed
`DctOnlyQuantizeBlock`.
`CflCoefficientSampleWeight` applies libjxl-tiny's X/B inverse-weight matrices
to Q16 DCT coefficient pairs, `CflWeightedSumAccumulator` forms the
least-squares `sum(a*a)`/`sum(a*b)` terms, and `CflMultiplierEstimator`
isolates the final regularized rounded/clamped signed 8-bit CFL multiplier
quantization. `CflCoefficientSumAccumulator` composes those pieces behind a
streaming coefficient-pair interface, and `CflTileCoefficientEstimator` consumes
Y/X/B coefficient triples to produce both tile CFL multipliers for future tile
traversal. `CflTileCoefficientTraceStage` bridges that tile arithmetic into
`YtoxMap`/`YtobMap` `StageTrace` rows. `FramePreparedCflMapTraceStage` now
buffers prepared raster DCT-only blocks, schedules their coefficients by 64x64
tile, and emits real prepared-frame CFL map traces; its 72x72 regression covers
2x2 tile traversal and map output order directly. `CflTileGeometry` centralizes
the matching block-to-tile ordinal mapping for the estimated-CFL metadata and
quantization consumers.
`FramePreparedCflAcMetadataTokenTraceStage` consumes the same prepared blocks,
estimates CFL maps internally, and emits AC-metadata tokens using those
estimated maps instead of caller-supplied scalar CFL fields.
`FramePreparedCflDctOnlyQuantizeTraceStage` now replays prepared blocks through
quantization with internally estimated tile CFL multipliers, so prepared
quantized AC/DC/nonzero traces can exercise the real CFL residual path.
`FramePreparedCflDctOnlyQuantizeTokenTraceStage` combines estimated-CFL
quantization and estimated-CFL AC metadata into the direct prepared
quantize-to-token trace stream.
There is also a standalone DCT-only 8x8 AC quantization primitive that consumes
the adjusted raw quant value and distance-derived AC scale. Prepared DCT blocks
can be run through `DctQuantizeTraceStage` to emit `QuantizedAc` and
`NumNonzeros` traces for block-level validation. `QuantizeRoundtripYDct8x8Block`
adds the Y-channel
quantize/dequantize roundtrip used before chroma residual quantization, and
`QuantizeChromaResidualDct8x8Block` applies the X/B CFL residual step before
quantizing chroma coefficients. `QuantizeDcDct8x8Block` covers the DCT-only
quantized DC coefficient path, including B's correction from quantized Y DC.
`DctOnlyQuantizeBlock` composes those pieces for one prepared X/Y/B 8x8 DCT
block and emits quantized AC, quantized DC, and nonzero counts for all channels.
`DctOnlyQuantizeTraceStage` exposes the same prepared-block result as trace
records for simulation. `FramePreparedDctOnlyQuantizeTraceStage` schedules
complete prepared DCT-only blocks in raster order through that quantizer and
emits frame-shaped `QuantizedAc`, `QuantDc`, and `NumNonzeros` traces with
`traceLast` on the final prepared quantization record. The
prepared-DCT frame boundary uses Q16 DCT coefficients by default so its CFL and
quantization path can match libjxl-tiny's float reference on the oracle fixture;
the new RGB adaptive handoff explicitly reuses the same prepared schedulers at
Q12.
`FramePreparedDctOnlyQuantizeTokenTraceStage` bridges the same prepared-DCT
input boundary directly into fixed all-DCT logical token traces by internally
retaining DC values for plane reordering while streaming quantized AC/nonzero
data and metadata into their owning prepared frame schedulers. This avoids a
second full-frame AC store in the orchestration layer. It also exposes
`traceLast` on the final AC-token trace beat.
The combined prepared-token boundary exposes `traceLast` on the final AC-token
trace beat, so generated prepared-token RTL has a concrete frame delimiter for
host capture.
Standalone prepared DC, AC-metadata, and AC-token tops also expose `traceLast`,
and `PreparedTokenElaborationSpec` checks that generated trace port surface.
`FrameDctOnlyQuantizeTraceStage` remains as a focused fixed-parameter diagnostic.
The `HjxlCore` quantized route now selects
`FrameAqCflDctOnlyQuantizeTraceStage`, while the combined adaptive
estimated-CFL token wrapper reuses the exact prepared token schedulers
downstream.
`FrameDctOnlyDcTokenTraceStage` emits the first logical token stream: DC
predictor contexts and packed residuals in libjxl-tiny Y/X/B plane order, with
`traceLast` on the final DC token.
`DcTokenTraceStage` exposes the same DC predictor/token packing as a prepared
single-sample boundary once quantized DC planes already exist, and
`FramePreparedDcTokenTraceStage` schedules complete prepared quantized DC planes
through that exact token boundary in libjxl-tiny Y/X/B raster order.
`FrameDctOnlyAcMetadataTokenTraceStage` emits fixed-path scalar CFL, AC-strategy,
quant-field, and block-metadata tokens, with `traceLast` on the final metadata
token.
`FramePreparedAcMetadataTokenTraceStage` emits the same AC-metadata token
substream from prepared per-block raw quant values and per-tile CFL
multipliers, so prepared-DCT quantization can carry coherent metadata into the
token boundary.
`FrameDctOnlyAcNonzeroTokenTraceStage` is a directly tested standalone frame
stage for the first `AcTokens` substream: nonzero-count contexts and values for
each block/channel, with `traceLast` on the final prefix token.
`AcCoefficientTokenTraceStage` emits prepared-block AC
coefficient scan tokens with libjxl-tiny's DCT coefficient order and
zero-density contexts, and `AcBlockTokenTraceStage` combines the nonzero prefix
with those coefficient tokens for one prepared block/channel.
`DctOnlyAcBlockTokenTraceStage` sequences the prepared Y/X/B channel streams
for one all-DCT block. `FramePreparedAcTokenTraceStage` schedules complete
prepared quantized AC blocks through that exact token boundary, predicting
nonzero counts from west/north block history. Its frame-scaled coefficient
store is a serialized 96-bit-wide synchronous memory: each address holds the
X/Y/B triplet for one coefficient position, while one-block buffering adapts
the structured input and token interfaces. Read prefetch overlaps the next
block load with current token emission. `FramePreparedTokenTraceStage`
combines the exact prepared DC and AC schedulers with fixed all-DCT AC
strategy/metadata trace generation using `FrameConfig.fixedRawQuant`,
`fixedYtox`, and `fixedYtob`, producing the four logical trace streams consumed
by the host bitstream assembly boundary. `FrameDctOnlyAcTokenTraceStage` is the
first full RGB-input standalone frame scheduler for `AcTokens`: it emits
nonzero prefixes and coefficient scan/value tokens for every fixed all-DCT
raster block/channel. It remains directly tested and exposed through the
dedicated fixed-diagnostic `HjxlAcTokenCore` wrapper. `HjxlCore` instead uses
`FrameAqCflDctOnlyQuantizeTokenTraceStage` when elaborated with
`traceRoute = TraceStage.AcTokens`, keeping that adaptive estimated-CFL route
out of the default all-route shell.
`HjxlCore` also accepts an optional compile-time `traceRoute` parameter used by
simulation tests to instantiate only the selected route.
`HjxlCoreRouteElaborationSpec` checks that the focused AC-token core includes
the heavy scheduler while the default all-route shell omits it, and that the
focused final-map/raw-quant routes elaborate one shared cumulative AQ chain
while the fixed raw-quant override remains available; focused CFL-map routes
elaborate `FrameAqCflMapTraceStage` without the AC-strategy or reciprocal
routes. The retained direct AC-token scheduler behavior is covered by
`FrameDctOnlyAcTokenTraceStageSpec`. The
default still builds the all-route integration shell.
Every current frame trace scheduler/top exposes a `traceLast` sideband on its
final frame trace beat. `HjxlAxiStreamCore` is the first hardware-facing shell:
it accepts raster RGB pixels on an AXI4-Stream-shaped input, generates core
`x/y` coordinates, packs `StageTrace` rows onto an output stream, and maps
`traceLast` to output TLAST for single-frame capture.
`HjxlAxiLiteStreamCore` wraps that stream shell with a minimal 32-bit AXI-Lite
control/status register map for KV260-style integration while keeping the same
AXI4-Stream data path. The stream shell snapshots the complete configuration on
the first accepted frame beat and retains it through the accepted final trace
beat. AXI-Lite writes during `busy` therefore update readable shadow registers
for the next frame without changing the active route, geometry, or parameters.
`HjxlPreparedDctAxiLiteStreamCore` applies the same control-plane shape to the
prepared-DCT quantize-to-token stream path, which is currently the strongest
RTL-to-codestream parity boundary. It also validates full-word input keep masks
before advancing the prepared-word parser.
`HjxlKv260PreparedDctTop` wraps that prepared-DCT AXI-Lite stream core with
flat Vivado-style `ap_clk`/`ap_rst_n`, `s_axi_control_*`, `s_axis_input_*`, and
`m_axis_trace_*` ports for the current KV260-oriented top-level shape. Its
prepared input exposes `s_axis_input_tkeep` for AXIS compatibility and expects
full 32-bit beats (`0xF`); partial masks raise the same sticky protocol-error
status bit as malformed TLAST. Its trace output is padded to a 128-bit AXIS bus
by default, with `m_axis_trace_tkeep` marking the low 11 bytes that carry the
packed trace word.
`HjxlKv260PreparedCflDctTop` is the matching flat Vivado-style wrapper for the
estimated-CFL prepared-DCT route. It keeps the same ports and trace padding as
the current KV260 top, but uses `HjxlPreparedCflDctAxiLiteStreamCore`
internally.

The frozen direct top has a reproducible 200 MHz out-of-context Vivado
synthesis and reporting flow under [`fpga/vivado/`](fpga/vivado/). Generate the
RTL and run its toolchain-independent preflight with:

```sh
sbt 'runMain hjxl.ElaborateKv260PreparedDctTop'
tclsh fpga/vivado/synth.tcl --preflight-only
```

In a Vivado environment, run:

```sh
vivado -mode batch -source fpga/vivado/synth.tcl
```

This writes timing, utilization, methodology, clock, and constraint reports
under the gitignored `build/vivado/` tree. The flow is an out-of-context
feasibility gate; it does not claim place-and-route, bitstream, DMA, or board
execution.

## Requirements

- JDK 21 or newer
- sbt, or the checked-in Mill bootstrap script
- Verilator for simulator-backed tests

## Build

Run the RTL test suite with sbt:

```sh
sbt test
```

Or with Mill:

```sh
./mill hjxl.test
```

CI sets `HJXL_REPO_ROOT`, installs Verilator and `python3-numpy`, checks out
the libjxl-tiny Python-port commit
`07f2dfe11a1a9f621052e75db5feffb0f58f44bd` from
`https://github.com/yunho-c/libjxl-tiny.git` into `$LIBJXL_TINY`, checks Python
helper syntax with `py_compile`, then runs both `sbt test` and `./mill _.test`.
Keep NumPy/libjxl-tiny-backed oracle/tool tests written so they run under that
environment rather than silently depending on a local-only Python setup. Use
`HJXL_REPO_ROOT=$PWD` for local Mill runs that invoke Python helper scripts.

Generate the current top-level SystemVerilog:

```sh
sbt 'runMain hjxl.Elaborate'
```

Generate the standalone RGB-input AQ contrast, fuzzy-erosion, strategy-mask,
nonlinear-mask, cumulative modulation/final-map/raw-quant, and prepared final
operation/conversion tops:

```sh
sbt 'runMain hjxl.ElaborateAqContrast'
sbt 'runMain hjxl.ElaborateAqFuzzyErosion'
sbt 'runMain hjxl.ElaborateAqStrategyMask'
sbt 'runMain hjxl.ElaborateAqNonlinearMask'
sbt 'runMain hjxl.ElaborateAqHfModulation'
sbt 'runMain hjxl.ElaborateAqColorModulation'
sbt 'runMain hjxl.ElaborateAqGammaModulation'
sbt 'runMain hjxl.ElaborateAqFinalMap'
sbt 'runMain hjxl.ElaborateAqRawQuant'
sbt 'runMain hjxl.ElaborateAqAdjustedRawQuant'
sbt 'runMain hjxl.ElaboratePreparedAcStrategy'
sbt 'runMain hjxl.ElaborateAqAcStrategy'
sbt 'runMain hjxl.ElaborateCoreRawQuant'
sbt 'runMain hjxl.ElaborateAqDctOnlyQuantize'
sbt 'runMain hjxl.ElaborateAqDctOnlyAcMetadataTokens'
sbt 'runMain hjxl.ElaborateAqDctOnlyQuantizeTokens'
sbt 'runMain hjxl.ElaborateAqCflMaps'
sbt 'runMain hjxl.ElaborateAqCflDctOnlyQuantize'
sbt 'runMain hjxl.ElaborateAqCflDctOnlyAcMetadataTokens'
sbt 'runMain hjxl.ElaborateAqCflDctOnlyQuantizeTokens'
sbt 'runMain hjxl.ElaboratePreparedAqFinalModulation'
sbt 'runMain hjxl.ElaboratePreparedAqRawQuant'
```

Generate the standalone full AC-token trace top-level:

```sh
sbt 'runMain hjxl.ElaborateAcTokens'
```

Generate the public `HjxlCore` IO shell with only the full AC-token route
or adaptive AC-strategy trace route instantiated:

```sh
sbt 'runMain hjxl.ElaborateCoreAcTokens'
sbt 'runMain hjxl.ElaborateCoreAcStrategy'
```

Generate AXI4-Stream-shaped shells:

```sh
sbt 'runMain hjxl.ElaborateAxiStream'
sbt 'runMain hjxl.ElaborateAxiStreamCoreAcTokens'
```

Generate AXI4-Lite-controlled AXI4-Stream shells:

```sh
sbt 'runMain hjxl.ElaborateAxiLiteStream'
sbt 'runMain hjxl.ElaborateAxiLiteStreamCoreAcTokens'
```

Generate the exact prepared-token trace top-levels:

```sh
sbt 'runMain hjxl.ElaboratePreparedDctOnlyQuantize'
sbt 'runMain hjxl.ElaboratePreparedDctOnlyQuantizeTokens'
sbt 'runMain hjxl.ElaboratePreparedDctAxiStream'
sbt 'runMain hjxl.ElaboratePreparedDctAxiLiteStream'
sbt 'runMain hjxl.ElaborateKv260PreparedDctTop'
sbt 'runMain hjxl.ElaborateKv260PreparedCflDctTop'
sbt 'runMain hjxl.ElaboratePreparedDcTokens'
sbt 'runMain hjxl.ElaboratePreparedAcMetadataTokens'
sbt 'runMain hjxl.ElaboratePreparedAcTokens'
sbt 'runMain hjxl.ElaboratePreparedTokens'
```

Generate the standalone CFL tile coefficient trace bridge:

```sh
sbt 'runMain hjxl.ElaborateCflTileCoefficientTrace'
sbt 'runMain hjxl.ElaboratePreparedCflMapTrace'
sbt 'runMain hjxl.ElaboratePreparedCflDctOnlyQuantize'
sbt 'runMain hjxl.ElaboratePreparedCflDctOnlyQuantizeTokens'
sbt 'runMain hjxl.ElaboratePreparedCflDctAxiStream'
sbt 'runMain hjxl.ElaboratePreparedCflDctAxiLiteStream'
sbt 'runMain hjxl.ElaboratePreparedCflAcMetadataTokens'
```

The tile-coefficient top consumes caller-supplied tile coefficient streams. The
prepared-frame top consumes prepared raster DCT-only blocks, buffers their
coefficients, and emits `YtoxMap`/`YtobMap` trace rows. The prepared CFL
AC-metadata top estimates the same maps internally and feeds them into
AC-metadata tokenization. The prepared CFL quantization top estimates the maps
internally and feeds them into DCT-only quantization; the two estimated-CFL paths
are also combined in the prepared CFL quantize-to-token wrapper and its packed
AXI-stream shell.

Generate a small libjxl-tiny reference fixture:

```sh
python3 tools/hjxl_reference.py --width 17 --height 9 --pattern gradient \
  --fixed-raw-quant 5 \
  --fixed-ytox 0 \
  --fixed-ytob 0 \
  --pfm build-codex/fixtures/gradient-17x9.pfm \
  --input-padded-npy build-codex/fixtures/gradient-17x9-input-padded.npy \
  --xyb-npy build-codex/fixtures/gradient-17x9-xyb.npy \
  --xyb-q12-csv build-codex/fixtures/gradient-17x9-xyb-q12.csv \
  --aq-contrast-q16-csv build-codex/fixtures/gradient-17x9-aq-contrast-q16.csv \
  --aq-fuzzy-erosion-q16-csv build-codex/fixtures/gradient-17x9-aq-fuzzy-erosion-q16.csv \
  --aq-strategy-mask-q16-csv build-codex/fixtures/gradient-17x9-aq-strategy-mask-q16.csv \
  --aq-nonlinear-mask-q24-csv build-codex/fixtures/gradient-17x9-aq-nonlinear-mask-q24.csv \
  --aq-hf-modulation-q24-csv build-codex/fixtures/gradient-17x9-aq-hf-modulation-q24.csv \
  --aq-color-modulation-q24-csv build-codex/fixtures/gradient-17x9-aq-color-modulation-q24.csv \
  --aq-gamma-modulation-q24-csv build-codex/fixtures/gradient-17x9-aq-gamma-modulation-q24.csv \
  --aq-final-map-q24-csv build-codex/fixtures/gradient-17x9-aq-final-map-q24.csv \
  --dct8x8-npy build-codex/fixtures/gradient-17x9-dct8x8.npy \
  --default-ac-strategy-npy build-codex/fixtures/gradient-17x9-ac-strategy.npy \
  --raw-quant-field-npy build-codex/fixtures/gradient-17x9-raw-qf.npy \
  --libjxl-ac-strategy-npy build-codex/fixtures/gradient-17x9-libjxl-ac-strategy.npy \
  --ytox-map-npy build-codex/fixtures/gradient-17x9-ytox.npy \
  --ytob-map-npy build-codex/fixtures/gradient-17x9-ytob.npy \
  --dct-only-raw-quant-field-npy build-codex/fixtures/gradient-17x9-dct-only-raw-qf.npy \
  --dct-only-aq-map-q24-csv build-codex/fixtures/gradient-17x9-dct-only-aq-q24.csv \
  --dct-only-ytox-map-npy build-codex/fixtures/gradient-17x9-dct-only-ytox.npy \
  --dct-only-ytob-map-npy build-codex/fixtures/gradient-17x9-dct-only-ytob.npy \
  --dct-only-quantized-ac-npy build-codex/fixtures/gradient-17x9-dct-only-qac.npy \
  --dct-only-num-nonzeros-npy build-codex/fixtures/gradient-17x9-dct-only-nnz.npy \
  --dct-only-quant-dc-npy build-codex/fixtures/gradient-17x9-dct-only-qdc.npy \
  --dct-only-ac-metadata-tokens-npy build-codex/fixtures/gradient-17x9-dct-only-acmeta-tokens.npy \
  --dct-only-dc-tokens-npy build-codex/fixtures/gradient-17x9-dct-only-dc-tokens.npy \
  --dct-only-ac-tokens-npy build-codex/fixtures/gradient-17x9-dct-only-ac-tokens.npy \
  --dct-only-prepared-blocks-json build-codex/fixtures/gradient-17x9-dct-only-prepared-blocks.json \
  --dct-only-frame-bin build-codex/fixtures/gradient-17x9-dct-only-frame.bin \
  --dct-only-codestream-bin build-codex/fixtures/gradient-17x9-dct-only.jxl \
  --distance-params-json build-codex/fixtures/distance-1-params.json \
  --fixed-dct-only-dc-tokens-npy build-codex/fixtures/gradient-17x9-fixed-dc-tokens.npy \
  --fixed-dct-only-ac-metadata-tokens-npy build-codex/fixtures/gradient-17x9-fixed-acmeta-tokens.npy \
  --fixed-dct-only-ac-tokens-npy build-codex/fixtures/gradient-17x9-fixed-ac-tokens.npy \
  --fixed-dct-only-raw-quant-field-npy build-codex/fixtures/gradient-17x9-fixed-raw-qf.npy \
  --fixed-dct-only-ytox-map-npy build-codex/fixtures/gradient-17x9-fixed-ytox.npy \
  --fixed-dct-only-ytob-map-npy build-codex/fixtures/gradient-17x9-fixed-ytob.npy \
  --fixed-dct-only-prepared-token-inputs-json build-codex/fixtures/gradient-17x9-prepared-token-inputs.json \
  --fixed-dct-only-frame-bin build-codex/fixtures/gradient-17x9-fixed-frame.bin \
  --fixed-dct-only-codestream-bin build-codex/fixtures/gradient-17x9-fixed.jxl \
  --jxl build-codex/fixtures/gradient-17x9.jxl
```

Set `LIBJXL_TINY` if the reference checkout is not at
`/Users/yunhocho/GitHub/libjxl-tiny`.
The fixed all-DCT oracle flags default to raw quant 5 and zero CFL maps;
`--fixed-ytox` and `--fixed-ytob` may override the scalar Y-to-X/Y-to-B CFL map
values with signed 8-bit integers.

The `--xyb-q12-csv` artifact first rounds the deterministic RGB fixture to the
RTL's signed Q8 input contract, then records the padded Q8 pixels beside
libjxl-tiny's signed Q12 XYB results. `RgbToXybApproxSpec` requires the RTL to
stay within two Q12 units on gradient, checkerboard, and random fixtures. It
also checks every normalization boundary and a deterministic 100,000-vector
full signed-16/Q8 sweep against an independent formula with a five-unit bound.

The `--aq-contrast-q16-csv` artifact records libjxl-tiny's global quarter-
resolution pre-erosion contrast cells in Q16, both from its native float32 XYB
and from XYB rounded to Q12. The exporter first checks its reconstructed native
grid exactly against the `pre_erosion` array passed into libjxl-tiny's real
fuzzy-erosion call. `AqContrastSpec` checks the gamma-table seams,
integer square root, saturation, fixed-point pixel model, 4x4 traversal,
frame-control lifetime, backpressure, and five RGB fixture families. The
RGB-connected frame output stays within two percent of the native libjxl-tiny
cell values; this is an intermediate AQ seam, not a final AQ-map or raw-quant
parity claim.

The `--aq-fuzzy-erosion-q16-csv` artifact records the block-resolution result
immediately after fuzzy erosion, from both native and Q12-rounded XYB. Its
independent reconstruction is checked exactly against the array returned by
libjxl-tiny's real fuzzy-erosion call. `AqFuzzyErosionSpec` verifies the
four-minimum neighborhood arithmetic, exact prepared-Q16 traversal within one
LSB, backpressure/config lifetime, five RGB families within two percent, a
65x1 RGB tile crossing, and prepared 65x65 input padded to a two-dimensional
72x72 grid. This remains pre-modulation AQ state, not the final `aq_map`.

The `--aq-strategy-mask-q16-csv` artifact records the reciprocal mask used only
by AC-strategy scoring. The exporter checks an independent full-frame
reconstruction against both the real AQ return value and the stitched 64x64
tile calls used by the encoder, then includes the rational fixed-Q16 result.
`AqStrategyMaskSpec` proves exact prepared-Q16 output for the fixture domain,
five composed RGB families within two percent, 65x1 and 65x65 traversal, and
the fixed 42-cycle divider latency. This is distinct from `_compute_mask`,
which begins the final AQ-map modulations.

The `--aq-nonlinear-mask-q24-csv` artifact records the signed log-domain seed
returned by `_compute_mask`. The exporter independently reconstructs the
float32 formula, compares it to the real private reference function, checks
full-frame equality against stitched 64x64 AQ calls, and emits the matching
Q16-input/Q24-output integer model. `AqNonlinearMaskSpec` covers directed and
randomized positive-Q16 inputs, exact 174-cycle shared-divider latency, signed
prepared traces, five composed RGB families, and 65x1/65x65 traversal. This is
still not the final `aq_map`: HF, color, and gamma terms are added to this seed
before the power/scale step.

The `--aq-hf-modulation-q24-csv` artifact records the cumulative signed-Q24
seed after `_hf_modulation`. Its independent float32 reconstruction sums the
same 112 internal Y edges in reference order, checks the real private function,
and proves full-frame equality against stitched 64x64 AQ calls. It also emits
the Q32-coefficient fixed model for native, Q12-rounded, and signed-Q8 input
fixtures. `AqHfModulationSpec` checks exact prepared results, 64-cycle latency,
full signed-input saturation, output stability, five composed RGB families,
65x1 crossing, and 65x65 raster order. This focused trace is the input to the
now-connected color route and still precedes gamma and the final power/scale
step.

The `--aq-color-modulation-q24-csv` artifact records the cumulative signed-Q24
seed after `_color_modulation` for the requested Q8 distance. Its independent
float32 reconstruction is checked against the real private function and against
stitched 64x64 AQ calls. Q16 ramp coverage plus distance-folded Q24 coefficients
form the fixed oracle, including the exact early return above distance 4.
`AqColorModulationSpec` covers all distance regimes, full signed XYB inputs and
saturation, exact 64-cycle prepared output, backpressure/config lifetime, five
RGB families, and 65x1/65x65 traversal. The composed elaboration contains one
shared modulation scheduler and one RGB-to-XYB converter.

The `--aq-gamma-modulation-q24-csv` artifact records the cumulative signed-Q24
seed after `_gamma_modulation`. Its independent float32 reconstruction checks
the real private function and stitched 64x64 calls. The fixed contract clamps
the two Q12 response inputs to `[0, 8]`, uses fine/coarse/HDR inverse-ratio
interpolation plus a normalized Q20 `fast_log2f` table, and preserves signed-Q24
saturation. `AqGammaModulationSpec` covers table seams and clamps, exact
128-cycle prepared output, float32 error, backpressure/config lifetime, five RGB
families, and 65x1/65x65 traversal. The composed elaboration contains one shared
scheduler, one reusable HF/color pipeline, and one RGB-to-XYB converter. The
completed final-map pipeline consumes this stage directly.

The `--aq-final-map-q24-csv` artifact records the cumulative gamma seed, the
completed unsigned-Q24 AQ map, the distance-derived scale/dampen/inverse-global-
scale triplet, and reference/fixed raw-quant bytes. Its independent float32
power/scale reconstruction matches libjxl-tiny's final AQ map exactly before
quantization. The fixed model uses a 257-entry normalized `fast_pow2f` table,
Q24 interpolation/multiplication, explicit unsigned saturation, and the existing
nearest/clamped raw-quant converter. `AqFinalModulationSpec` covers exponent
seams, high-distance damping, exact prepared output and fixed-model raw bytes, five RGB
families, unsupported-distance fallback, backpressure, 65x1, and 65x65 order.
`fixedRawQuant=0` selects this adaptive route; a nonzero value remains the
explicit fixed override.

The `--dct-only-aq-map-q24-csv` artifact records each real all-DCT AQ-map
sample, the frame-level inverse global AC scale, libjxl-tiny's raw-quant byte,
and the independently fixed-point-converted byte. Both fixed-point inputs use
unsigned Q24. `AdaptiveQuantizationSpec` exercises these rows through
`FramePreparedAqRawQuantTraceStage` and requires exact byte parity. This remains
the narrow prepared seam used to isolate final conversion arithmetic even
though the RGB path is now connected end to end through the same converter.

Assemble a frame and bare codestream from precomputed logical token arrays:

```sh
python3 tools/hjxl_reference.py --width 17 --height 9 --distance 1.0 \
  --token-input-dc-tokens-npy build-codex/fixtures/gradient-17x9-fixed-dc-tokens.npy \
  --token-input-ac-metadata-tokens-npy build-codex/fixtures/gradient-17x9-fixed-acmeta-tokens.npy \
  --token-input-ac-tokens-npy build-codex/fixtures/gradient-17x9-fixed-ac-tokens.npy \
  --token-input-ac-strategy-npy build-codex/fixtures/gradient-17x9-ac-strategy.npy \
  --token-input-frame-bin build-codex/fixtures/gradient-17x9-token-frame.bin \
  --token-input-codestream-bin build-codex/fixtures/gradient-17x9-token.jxl
```

Token input arrays are validated before assembly: token arrays must have shape
`(n, 2)` with nonnegative `uint32` context/value entries, and the AC strategy
grid must be a 2D integer array in the `uint8` range. Do not rely on NumPy dtype
wrapping at this host boundary.

Convert RTL or simulator `StageTrace` CSV dumps into those token arrays:

```sh
python3 tools/hjxl_trace_tokens.py \
  --trace-csv build-codex/traces/gradient-17x9-stage-trace.csv \
  --width 17 --height 9 \
  --dc-tokens-npy build-codex/traces/gradient-17x9-rtl-dc-tokens.npy \
  --ac-metadata-tokens-npy build-codex/traces/gradient-17x9-rtl-acmeta-tokens.npy \
  --ac-tokens-npy build-codex/traces/gradient-17x9-rtl-ac-tokens.npy \
  --ac-strategy-npy build-codex/traces/gradient-17x9-rtl-ac-strategy.npy \
  --raw-quant-field-npy build-codex/traces/gradient-17x9-rtl-raw-qf.npy \
  --ytox-map-npy build-codex/traces/gradient-17x9-rtl-ytox.npy \
  --ytob-map-npy build-codex/traces/gradient-17x9-rtl-ytob.npy
```

The CSV must have `stage,group,index,value` columns. Token rows use `group` as
the token ordinal and are written as nonnegative `uint32` `(context, value)`
NumPy arrays. AC strategy rows use `index` as the raster block ordinal and are
reshaped using positive `--width` and `--height`. Raw quant-field rows use block
ordinals and are written as a `uint8` 8x8-block grid; Y-to-X/Y-to-B CFL rows use
64x64 tile ordinals and are written as `int8` tile grids. Grid outputs reject
non-positive image dimensions before writing NumPy artifacts.

For generated AXI-stream shells, input pixels are raster ordered. Pack
`input.bits.data` as `R` in bits `[15:0]`, `G` in `[31:16]`, and `B` in
`[47:32]` for the default 16-bit pixel configuration. The wrapper checks input
`last` against `FrameConfig.xsize * FrameConfig.ysize` and exposes a sticky
`protocolError`; pulse `clearProtocolError` to clear it without resetting the
core. It also forwards active-route `busy` and `overflow` status for host
polling and fault reporting. The AXI-Lite controlled shell also reports an
unsupported-distance status bit when `distanceQ8` falls back to the distance-1
lookup entry. Output trace words are packed as
`{value,index,group,stage}`, with
`stage` in the low eight bits. Output `last` is asserted on the final trace
word for each current route: padded input, XYB, raw DCT, quantized traces, DC
tokens, AC-metadata tokens, AC strategy, AQ contrast, focused AQ fuzzy erosion,
focused AQ strategy mask, focused signed AQ nonlinear mask, and the variable-
length full AC-token route all expose a scheduler `traceLast` sideband that the
stream wrapper carries to TLAST.

Convert a linear RGB PFM into an input stream CSV for `HjxlAxiStreamCore` or
`HjxlAxiLiteStreamCore` simulation/DMA bring-up:

```sh
python3 tools/hjxl_rgb_stream.py \
  --pfm build-codex/fixtures/gradient-17x9.pfm \
  --stream-csv build-codex/fixtures/gradient-17x9-rgb-stream.csv \
  --axi-lite-csv build-codex/fixtures/gradient-17x9-rgb-control.csv \
  --trace-route all \
  --manifest-json build-codex/fixtures/gradient-17x9-rgb-manifest.json
```

The converter reads RGB PFM files in top-to-bottom raster order, quantizes
linear samples to signed Q8 by default, packs R/G/B into consecutive
`pixelBits` fields, and asserts `last` only on the final pixel. With
`--axi-lite-csv`, it also emits `address,data,strb` writes for the shared
AXI-Lite register map, deriving `xsize` and `ysize` from the PFM header and
using padded-input route defaults unless route flags such as `--enable-xyb` or
`--enable-dct` are supplied. `--trace-route` records the compile-time
`HjxlCore(traceRoute = ...)` elaboration intent separately from those runtime
flags; use focused names such as `raw-quant-field`, `ytox-map`, `ytob-map`, or
`ac-tokens` when generating artifacts for a focused top. An AQ-contrast replay
uses `--enable-xyb --enable-quant --token-select aq-contrast` and either the
all-route shell or `--trace-route aq-contrast`; the same runtime flags with
`--trace-route aq-fuzzy-erosion` selects the composed focused erosion build, and
`--trace-route aq-strategy-mask` selects the composed focused strategy mask.
Use `--trace-route aq-nonlinear-mask` for the signed-Q24 modulation seed.
Use `--trace-route aq-hf-modulation` for the cumulative seed after Y-detail
modulation, and `--trace-route aq-color-modulation` for the cumulative seed
after distance-dependent red/blue coverage.
Prepared-DCT manifests
record `prepared-dct-quantize-token` for the direct prepared stream boundary,
with no `TraceStage` id. `--manifest-json`
records the source PFM, image size, stream packing, generated file paths,
trace-route metadata, register map, status/control bits, and selected config
values for host runners. Use `--pixel-bits` and `--fraction-bits` when
simulating a non-default stream configuration. Shared AXI-Lite scalar overrides
must fit the 32-bit control registers, and `--fixed-raw-quant` must fit the
RTL's 8-bit raw quant field.
`HjxlAxiStreamCoreSpec` covers the current host loop from PFM to RGB input
stream CSV, through RTL padded-input trace emission, and back through
`tools/hjxl_stream_trace.py`. It also covers focused raw-quant and CFL metadata
trace captures feeding into `tools/hjxl_trace_tokens.py` metadata-grid outputs
and `tools/hjxl_compare_tokens.py` fixed-oracle comparison. The selected AQ
contrast, focused fuzzy-erosion, focused strategy-mask, and focused nonlinear-
mask routes have packed stage/index/value and final-TLAST regressions at this
shell.
`HjxlAxiLiteStreamCoreSpec` covers the same input stream after
programming the DUT from the generated AXI-Lite CSV.

Validate an existing RGB stream/control bundle before replay:

```sh
python3 tools/hjxl_rgb_stream.py \
  --validate-manifest build-codex/fixtures/gradient-17x9-rgb-manifest.json
```

Validation checks manifest format, stream word count, final-only TLAST, stream
CSV columns, AXI-Lite CSV columns, full-byte strobes, and register values
against the manifest's config block. Malformed integer and boolean CSV fields
fail with line-specific diagnostics before replay.

Generate a small C header from either RGB or prepared-DCT stream/control
manifest for host-driver stubs:

```sh
python3 tools/hjxl_manifest_header.py \
  --manifest-json build-codex/fixtures/gradient-17x9-rgb-manifest.json \
  --header build-codex/fixtures/gradient-17x9-rgb-bundle.h \
  --symbol-prefix HJXL_RGB_BUNDLE
```

The header contains the manifest format, target interface/shell names, stream
word count, input data width, prepared-DCT coefficient fractional bits when
applicable, stream word/byte counts, input `TKEEP` mask, input keep-enforcement
flag, frame block/tile geometry macros, supported-distance Q8 values plus
fallback distance, register offsets, status/control bit positions, AXI-Lite
address/data/strobe width macros, trace packing shift/mask and byte-offset
macros, capture width macros, trace `TKEEP` mask, an inline
little-endian packed-trace decoder returning a generated `*_stage_trace_t` with
portable sign extension for the signed trace value, and an ordered
`address,data,strb` write table derived from the manifest's AXI-Lite config.
C11/C++ consumers also get compile-time assertions tying
stream byte count, input `TKEEP` mask, AXI-Lite strobe width, trace field
shifts/byte offsets, supported trace value width, trace byte count, trace
`TKEEP` mask, and AXI-Lite write-table length back to the generated macros.
Malformed manifest integer fields fail with field-specific diagnostics before
header generation.
Generate replayable stream payload bytes from the same manifest:

```sh
python3 tools/hjxl_stream_buffer.py \
  --manifest-json build-codex/fixtures/gradient-17x9-rgb-manifest.json \
  --stream-bin build-codex/fixtures/gradient-17x9-rgb-stream.bin \
  --last-bin build-codex/fixtures/gradient-17x9-rgb-last.bin
```

`--stream-bin` packs each CSV `data` word little-endian using the manifest's
input stream width. `--last-bin` is an optional one-byte-per-word TLAST sidecar
for software replay and diagnostics; DMA paths that derive TLAST from transfer
length can ignore it. Malformed manifest integer fields such as
`stream.word_count` and RGB `stream.pixel_bits` fail with field-specific
diagnostics before payload generation; floating-point JSON numbers are rejected
instead of being truncated.
For the normal host handoff, generate the header, stream payload, TLAST sidecar,
and an artifact index in one step:

```sh
python3 tools/hjxl_host_bundle.py \
  --manifest-json build-codex/fixtures/gradient-17x9-rgb-manifest.json \
  --output-dir build-codex/fixtures/gradient-17x9-rgb-host \
  --name gradient-17x9-rgb \
  --symbol-prefix HJXL_RGB_BUNDLE \
  --replay-plan-json build-codex/fixtures/gradient-17x9-rgb-host/replay-plan.json
```

The resulting `*-bundle.json` records the manifest format, target interface
metadata, compile-time trace-route metadata, supported-distance Q8 values plus
fallback distance, generated file paths, stream word count, input stream byte width, input `TKEEP` mask,
prepared-DCT coefficient fractional bits when applicable, total stream byte
count for DMA transfers, trace packing/capture geometry, trace field
shifts/masks and byte offsets, trace `TKEEP` mask, AXI-Lite control-plane
geometry, and symbol prefix so host scripts do not need to rediscover the
bundle layout. The
`target` block distinguishes RGB
stream bundles for `HjxlAxiLiteStreamCore` from prepared-DCT bundles for
`HjxlPreparedDctAxiLiteStreamCore` / `HjxlKv260PreparedDctTop`. It also records
SHA-256 checksums for the bundle-local artifacts. The bundle contains a local
manifest copy plus replay stream/control CSV copies, and the index uses
bundle-relative artifact paths so the directory can be moved as a unit. Relative
paths in bundle indexes, bundle-local manifests, and saved replay plans resolve
against the containing index/manifest/plan directory first; cwd-relative lookup
is only a legacy fallback when the local artifact is absent. Use
`--no-last-bin` only for host paths that derive TLAST from the DMA transfer
length; validation still checks final-TLAST semantics through the bundle-local
stream CSV.
Validate the handoff bundle before using it from host code:

```sh
python3 tools/hjxl_host_bundle.py \
  --validate-bundle build-codex/fixtures/gradient-17x9-rgb-host/gradient-17x9-rgb-bundle.json
```

Bundle validation re-reads the source manifest, runs the format-specific
manifest validator, checks the target, distance, frame, and trace metadata,
generated header, stream payload, optional TLAST sidecar, copied AXI-Lite
control CSV, stream metadata, AXI-Lite geometry/write count, and SHA-256
checksums against the manifest, and fails on stale or hand-edited artifacts.
Malformed bundle metadata integer fields fail with field-specific diagnostics,
and malformed bundle-local control CSV integer fields fail with line-specific
diagnostics before replay; floating-point JSON numbers are rejected instead of
being truncated.
Older bundle indexes without `target`, `distance`, `frame`, `trace`, or `axi_lite`
metadata still validate, but
contradictory metadata does not.
To inspect the host replay sequence without writing a driver yet, emit a
machine-readable replay plan:

```sh
python3 tools/hjxl_host_bundle.py \
  --describe-bundle build-codex/fixtures/gradient-17x9-rgb-host/gradient-17x9-rgb-bundle.json
```

The replay plan validates the bundle first, then prints bundle-relative and
absolute resolved stream payload paths, diagnostic stream/control CSV paths, DMA
byte count, input `TKEEP` mask, optional TLAST sidecar paths, prepared-DCT
coefficient fractional bits when applicable, target interface and trace-route metadata,
frame dimensions, padded 8x8 block grid, 64x64 tile grid for CFL metadata,
AXI-Lite address/data/strobe geometry,
ordered AXI-Lite writes, status bit positions, supported-distance Q8 values
plus fallback distance, trace packing geometry including field shifts, byte
offsets, and masks, trace `TKEEP` mask, the default capture word size for the
current KV260 wrapper, the canonical `bundle_index_resolved` path, and artifact checksums as
`hjxl.host_replay_plan.v1` JSON.
Add `--replay-plan-json path/to/plan.json` to write the same validated replay
plan to a file for host scripts; it can be used with either bundle generation
or `--describe-bundle`. Validate a saved plan before replay:

```sh
python3 tools/hjxl_host_bundle.py \
  --validate-replay-plan build-codex/fixtures/gradient-17x9-rgb-host/replay-plan.json
```

Replay-plan validation regenerates the plan from the referenced bundle index
and fails if the saved file is stale. Malformed replay-plan numeric fields fail
with field-specific diagnostics before replay, and floating-point JSON numbers
are rejected instead of being truncated. When `bundle_index_resolved` is
present, validation uses that canonical path so saved plans can live outside
the bundle directory; older plans without it fall back to resolving
`bundle_index` relative
to the saved plan file, with cwd-relative lookup only as a legacy fallback.
Older v1 replay plans without `target`, `frame`, `status_bits`, `trace`, or
AXI-Lite geometry/register-map fields also validate against the regenerated
bundle description.

Before hardware capture exists, use replay-capture preflight mode to validate
the saved plan and expected target, frame, stream, trace, status, AXI-Lite, and
register-map metadata without requiring trace input:

```sh
python3 tools/hjxl_replay_capture.py \
  --replay-plan-json build-codex/fixtures/gradient-17x9-rgb-host/replay-plan.json \
  --preflight-only \
  --expect-target-interface rgb_axi_stream \
  --expect-target-variant rgb \
  --expect-target-stream-shell HjxlAxiStreamCore \
  --expect-target-controlled-shell HjxlAxiLiteStreamCore \
  --expect-target-no-kv260-top \
  --expect-target-input-stream "raster RGB pixels" \
  --expect-target-input-keep-enforced 0 \
  --expect-reg-flags 0x1c \
  --summary-json build-codex/traces/gradient-17x9-preflight-summary.json
```

After a host replay captures `m_axis_trace_tdata`, validate and assemble that
capture against the same replay plan:

```sh
python3 tools/hjxl_replay_capture.py \
  --replay-plan-json build-codex/fixtures/gradient-17x9-rgb-host/replay-plan.json \
  --expect-target-interface rgb_axi_stream \
  --expect-target-variant rgb \
  --expect-target-stream-shell HjxlAxiStreamCore \
  --expect-target-controlled-shell HjxlAxiLiteStreamCore \
  --expect-target-no-kv260-top \
  --expect-target-input-stream "raster RGB pixels" \
  --expect-target-input-keep-enforced 0 \
  --stream-bin build-codex/traces/gradient-17x9-kv260-trace.bin \
  --last-bin build-codex/traces/gradient-17x9-kv260-trace-last.bin \
  --codestream-bin build-codex/traces/gradient-17x9-rtl.jxl \
  --expect-codestream-bin build-codex/fixtures/gradient-17x9-dct-only.jxl \
  --summary-json build-codex/traces/gradient-17x9-capture-summary.json
```

This helper validates the replay plan before using it, derives width, height,
requested distance, and hardware-effective distance from the ordered AXI-Lite
writes, then feeds the captured trace into the same host assembler used by
`tools/hjxl_trace_to_codestream.py`. Unsupported `distanceQ8` values assemble
with the RTL's distance-1 fallback by default; pass
`--require-supported-distance` when a host script should reject that fallback.
Binary capture input defaults to the replay plan's trace metadata: 16-byte
words for the 128-bit `HjxlKv260PreparedDctTop` trace output today. Pass
`--stream-word-bytes 11` for raw packed 88-bit trace dumps. Use
`--expect-target-interface`, `--expect-target-variant`,
`--expect-target-stream-shell`, `--expect-target-controlled-shell`, or
`--expect-target-kv260-top` in host scripts that must reject the wrong replay
target before capture assembly. Use `--expect-target-no-kv260-top` for RGB
replay plans that should have no KV260 wrapper target. Use
`--expect-target-input-stream` and `--expect-target-input-keep-enforced` when
the host DMA path must also reject a replay plan with the wrong input payload
contract or `TKEEP` enforcement policy. The variant guard is the direct way to
reject a `direct` prepared replay plan when an estimated-CFL capture path is
expected.
Use `--expect-trace-route-name`,
`--expect-trace-route-stage` (`-1` for the all-route shell or prepared-DCT
direct boundary), and `--expect-trace-route-focused` to reject replay plans
generated for a different focused `traceRoute` or prepared stream boundary.
Use `--expect-frame-xsize`,
`--expect-frame-ysize`, `--expect-frame-x-blocks`,
`--expect-frame-y-blocks`, `--expect-frame-padded-xsize`,
`--expect-frame-padded-ysize`, `--expect-frame-block-count`,
`--expect-frame-x-tiles`, `--expect-frame-y-tiles`, and
`--expect-frame-tile-count` to reject captures prepared for a different image,
padded 8x8 block grid, or CFL tile grid. Use
`--expect-status-protocol-error-bit`, `--expect-status-busy-bit`,
`--expect-status-overflow-bit`, `--expect-status-unsupported-distance-bit`,
and `--expect-control-clear-protocol-error-bit` to reject replay plans whose
status/control bit positions do not match the host poll/clear logic. Use
`--expect-trace-stage-bits`,
`--expect-trace-group-bits`, `--expect-trace-index-bits`,
`--expect-trace-value-bits`, `--expect-trace-stage-shift`,
`--expect-trace-group-shift`, `--expect-trace-index-shift`,
`--expect-trace-value-shift`, `--expect-trace-stage-byte-offset`,
`--expect-trace-group-byte-offset`, `--expect-trace-index-byte-offset`,
`--expect-trace-value-byte-offset`, `--expect-trace-stage-mask`,
`--expect-trace-group-mask`, `--expect-trace-index-mask`,
`--expect-trace-value-mask`, `--expect-trace-packed-bits`,
`--expect-trace-packed-bytes`, `--expect-trace-keep-mask`, and
`--expect-capture-word-bytes` to reject replay plans whose trace field layout,
output `TKEEP`, or capture word geometry does not match the host runner. Use
`--expect-input-word-count`, `--expect-input-data-bits`, `--expect-input-word-bytes`,
`--expect-input-keep-mask`, `--expect-input-byte-count`, and
`--expect-input-coefficient-fraction-bits` to reject replay plans whose input
payload geometry, bit width, AXI-stream keep mask, or prepared-DCT coefficient
scale does not match the host runner. Use `--expect-axi-lite-addr-bits`,
`--expect-axi-lite-data-bits`, `--expect-axi-lite-strb-bits`, and
`--expect-axi-lite-write-count` to reject replay plans whose control-plane
geometry or programmed write count does not match the host runner. Use
`--expect-reg-status-control`, `--expect-reg-xsize`, `--expect-reg-ysize`,
`--expect-reg-distance-q8`, `--expect-reg-fixed-point-scale`,
`--expect-reg-fixed-inv-qac-q16`, `--expect-reg-fixed-raw-quant`,
`--expect-reg-flags`, `--expect-reg-fixed-ytox`, and
`--expect-reg-fixed-ytob` to reject replay plans whose AXI-Lite register offsets
do not match the host runner. Hex-capable numeric expectation flags reject
malformed values with argument-specific diagnostics. Older replay plans without
`frame`, `status_bits`,
or `trace` blocks still validate; the capture helper falls back to the current
16-bit group, 32-bit value, and 16-byte KV260 capture defaults when trace
metadata is missing. Capture summaries include replay frame geometry,
status/control bit positions, trace geometry, field shifts/masks, byte offsets,
and trace `TKEEP`, plus replay input stream word count, data bit width, word
byte count, input `TKEEP` mask, total DMA byte count, prepared-DCT coefficient
fractional bits when present, and AXI-Lite control-plane geometry and register
map.

For a quick non-`sbt` regression of the RGB and prepared-DCT host metadata
contracts, run:

```sh
python3 tools/hjxl_host_metadata_smoke.py
```

The smoke builds a tiny RGB fixture and a one-block Q16 prepared fixture, writes
their manifests and host bundles, validates replay plans, checks legacy
compatibility for omitted metadata, rejects contradictory keep-mask and
coefficient-scale, trace, and AXI-Lite metadata, and exercises the
replay-capture input-stream, trace-layout, and AXI-Lite expectation guards.

`HjxlAxiLiteStreamCore` exposes the same input/output streams, but drives
`FrameConfig` through 32-bit AXI-Lite registers:

| Address | Register | Bits |
| --- | --- | --- |
| `0x00` | status/control | read bit 0 = sticky stream `protocolError`, bit 1 = `busy`, bit 2 = `overflow`, bit 3 = unsupported `distanceQ8` fallback; write bit 0 = clear protocol error |
| `0x04` | `xsize` | image width |
| `0x08` | `ysize` | image height |
| `0x0c` | `distanceQ8` | distance in Q8 |
| `0x10` | `fixedPointScale` | explicit AC scale override, zero selects lookup |
| `0x14` | `fixedInvQacQ16` | explicit reciprocal AC scale |
| `0x18` | `fixedRawQuant` | raw-quant override; zero selects adaptive AQ on the focused raw route and the existing default on fixed DCT/token routes |
| `0x1c` | flags | bit 0 `enableXyb`, bit 1 `enableDct`, bit 2 `enableQuant`, bit 3 `enableTokenize`, bits 9:8 `tokenSelect` (`Dc=0`, `AcMetadata=1`, `AcTokens=2`, `AqContrast=3`) |
| `0x20` | `fixedYtox` | signed 8-bit scalar Y-to-X CFL override |
| `0x24` | `fixedYtob` | signed 8-bit scalar Y-to-B CFL override |
| `0x28` | identity | read-only ASCII `HJXL` magic (`0x484a584c`) |
| `0x2c` | ABI version | read-only `major << 16 | minor`; currently 1.8 |
| `0x30` | capabilities | read-only target capability mask defined in `abi/hjxl_abi.json` |
| `0x34` | maximum frame geometry | read-only maximum height in bits 31:16 and width in bits 15:0 |
| `0x38` | active route | read-only trace stage 0–20, direct prepared route 128, or estimated-CFL prepared route 129 |
| `0x3c` | build ID | read-only contract build identifier; currently `0x20260712` |

The AXI-Lite wrapper returns OKAY for mapped registers and DECERR for unmapped
word addresses or writes to read-only discovery registers. Byte strobes are
honored for writable configuration registers. RGB active-route discovery is
latched on the first accepted frame beat and remains stable while `busy`, even
when AXI-Lite writes update next-frame shadow configuration.

Generated manifests carry expected identity/version/build, required capability
mask, active route, and minimum frame capacity. These values propagate through
generated C headers, host bundles, replay plans, and replay-capture summaries;
legacy v1 manifests without the additive `discovery` block continue to derive
the current expectations from target and frame metadata.

Before DMA replay, capture the six discovery reads as `address,data,resp` and
validate the actual hardware against the replay plan:

```sh
python3 tools/hjxl_discovery_check.py \
  --replay-plan-json build-codex/host/prepared-replay-plan.json \
  --read-csv build-codex/host/prepared-discovery.csv \
  --summary-json build-codex/host/prepared-discovery-summary.json
```

The checker requires OKAY responses and exact identity, ABI, build, and route;
allows capability supersets; and requires the compiled maximum geometry to
cover the replay frame. It fails with concise diagnostics before any stream
buffer is submitted.

Convert packed AXI-stream trace captures back into the StageTrace CSV shape
used by the host tools:

```sh
python3 tools/hjxl_stream_trace.py \
  --stream-csv build-codex/traces/gradient-17x9-stream.csv \
  --trace-csv build-codex/traces/gradient-17x9-stage-trace.csv \
  --require-final-last
```

The input CSV may use `data,last` or `tdata,tlast` columns. `--require-final-last`
is appropriate for single-frame captures from all current `HjxlAxiStreamCore`
routes, including focused full AC-token traces.
For DMA-style captures, replace `--stream-csv` with a little-endian binary
TDATA buffer and optional TLAST sidecar:

```sh
python3 tools/hjxl_stream_trace.py \
  --stream-bin build-codex/traces/gradient-17x9-trace.bin \
  --last-bin build-codex/traces/gradient-17x9-trace-last.bin \
  --trace-csv build-codex/traces/gradient-17x9-stage-trace.csv \
  --require-final-last
```

The default binary trace word size is the packed `StageTrace` width rounded up
to bytes: 11 bytes for the default 88-bit trace stream. Use
`--stream-word-bytes` if a wrapper pads TDATA to a wider DMA bus; use
`--stream-word-bytes 16` for default `HjxlKv260PreparedDctTop` trace captures.
`tools/hjxl_trace_tokens.py` and `tools/hjxl_trace_to_codestream.py` also accept
packed stream captures directly with repeated `--stream-csv` or `--stream-bin`
inputs and the same `--group-bits`, `--trace-value-bits`,
`--stream-word-bytes`, and `--require-stream-final-last` options.
For replay-plan-based host bring-up, prefer `tools/hjxl_replay_capture.py`; it
derives the frame dimensions and distance from the saved control writes and
derives trace geometry from the replay plan unless explicitly overridden. It
also emits an optional `hjxl.capture_summary.v1` JSON report with the replay
target and trace-route metadata, replay artifact paths, SHA-256 checksum
metadata, replay input-stream metadata, capture geometry, token counts, and
byte counts.

Assemble frame and bare codestream bytes directly from a token `StageTrace`
CSV dump:

```sh
python3 tools/hjxl_trace_to_codestream.py \
  --trace-csv build-codex/traces/gradient-17x9-stage-trace.csv \
  --width 17 --height 9 \
  --frame-bin build-codex/traces/gradient-17x9-rtl-frame.bin \
  --codestream-bin build-codex/traces/gradient-17x9-rtl.jxl \
  --expect-codestream-bin build-codex/fixtures/gradient-17x9-dct-only.jxl
```

For packed AXI-stream captures, replace or combine `--trace-csv` with
`--stream-csv build-codex/traces/gradient-17x9-stream.csv`.

This is the near-term host boundary for RTL token traces: hardware emits
logical `StageTrace` rows, and host software performs entropy optimization and
bitstream serialization. Use `--expect-frame-bin` or `--expect-codestream-bin`
to turn the command into a byte-parity check. `TraceToCodestreamToolSpec`
covers multi-file trace input, expected-byte mismatch diagnostics, and
malformed trace rejection for this tool.

Compare converted token arrays against oracle arrays:

```sh
python3 tools/hjxl_compare_tokens.py \
  --expected-ac-metadata-tokens-npy build-codex/fixtures/gradient-17x9-dct-only-acmeta-tokens.npy \
  --actual-ac-metadata-tokens-npy build-codex/traces/gradient-17x9-rtl-acmeta-tokens.npy \
  --expected-ac-strategy-npy build-codex/fixtures/gradient-17x9-ac-strategy.npy \
  --actual-ac-strategy-npy build-codex/traces/gradient-17x9-rtl-ac-strategy.npy \
  --expected-raw-quant-field-npy build-codex/fixtures/gradient-17x9-fixed-raw-qf.npy \
  --actual-raw-quant-field-npy build-codex/traces/gradient-17x9-rtl-raw-qf.npy \
  --expected-ytox-map-npy build-codex/fixtures/gradient-17x9-fixed-ytox.npy \
  --actual-ytox-map-npy build-codex/traces/gradient-17x9-rtl-ytox.npy \
  --expected-ytob-map-npy build-codex/fixtures/gradient-17x9-fixed-ytob.npy \
  --actual-ytob-map-npy build-codex/traces/gradient-17x9-rtl-ytob.npy
```

The comparator is exact by default: token stream lengths, contexts, values, and
AC-strategy/raw-quant/CFL grid entries must match. `--max-value-delta` only
relaxes token values, not contexts, metadata grids, or stream shape, and should
be used for diagnostics rather than parity claims. Token arrays must be integer
nonnegative `uint32` `(context, value)` pairs, and metadata arrays are
range-checked before comparison so signedness mistakes cannot be hidden by NumPy
dtype wrapping. `TokenCompareToolSpec` covers exact matches, mismatch
diagnostics, and malformed token-array/grid rejection for this helper.

Convert prepared DCT-only block JSON fixtures into simulator input CSVs and
expected quantization trace CSVs:

```sh
python3 tools/hjxl_prepared_blocks.py \
  --prepared-json build-codex/fixtures/gradient-17x9-dct-only-prepared-blocks.json \
  --input-csv build-codex/fixtures/gradient-17x9-prepared-blocks.csv \
  --input-stream-csv build-codex/fixtures/gradient-17x9-prepared-blocks-stream.csv \
  --axi-lite-csv build-codex/fixtures/gradient-17x9-prepared-blocks-control.csv \
  --manifest-json build-codex/fixtures/gradient-17x9-prepared-blocks-manifest.json \
  --expected-trace-csv build-codex/fixtures/gradient-17x9-prepared-quantize-trace.csv
```

`HjxlPreparedDctAxiStreamCore` is the stream-shaped form of the direct
prepared-DCT quantize-to-token boundary. Each prepared raster block is supplied
as 201 32-bit input stream words: `quant`, `scaleQ16`, `invQacQ16`,
`invDcFactorQ16[0]`, `invDcFactorQ16[1]`, `invDcFactorQ16[2]`,
`xQmMultiplierQ16`, signed `ytox`, signed `ytob`, then 64 X, 64 Y, and 64 B
DCT coefficients. Signed fields are two's-complement encoded in the low bits of
their word. Input TLAST must be asserted on the final word of the final raster
block implied by `FrameConfig.xsize` and `ysize`; mismatches set sticky
`protocolError`, cleared by `clearProtocolError`. Output uses the same packed
trace stream format as `HjxlAxiStreamCore` and maps the direct wrapper's
`traceLast` sideband to TLAST.
`tools/hjxl_prepared_blocks.py --input-stream-csv ...` emits this `data,last`
CSV directly from the prepared-block JSON oracle, which is the intended host
fixture path for stream-shell simulation and DMA bring-up. Prepared-block JSON
integer fields are validated as integers; floating-point JSON numbers are
rejected instead of being truncated. Fields that pack into narrower RTL inputs
are range-checked before stream generation: `quant` is `uint8`, `scaleQ16` is
`uint16`, CFL multipliers are signed 8-bit, and coefficients are signed 32-bit.
Generated manifests
record the prepared coefficient fractional scale, Q16 for current fixtures, so
host code can reject payloads that do not match the RTL's
`preparedDctCoefficientFractionBits` configuration. They also record the
`prepared-dct-quantize-token` trace-route name for the direct prepared-DCT
stream boundary. The converter also checks
prepared fixture `block_x`/`block_y` and `tile_x`/`tile_y` fields when present,
so stale CFL tile coordinates fail before stream artifacts are generated. With
`--axi-lite-csv`, the same tool emits shared-register-map `address,data,strb`
writes. It derives `xsize`/`ysize` from the prepared JSON, defaulting to the
prepared-DCT token route (`flags = 526`) used by
`HjxlPreparedDctAxiLiteStreamCore`. Explicit `--flags` overrides accept decimal
or `0x` integer syntax and must fit the 32-bit AXI-Lite register; the
`--fixed-raw-quant` override is additionally limited to the RTL's 8-bit raw
quant field, while `--fixed-ytox` and `--fixed-ytob` are limited to signed
8-bit CFL fields. With `--manifest-json`, it records the
source fixture, image/block grid, 64x64 tile grid for CFL metadata, stream
layout, generated artifact paths, register map, status/control bits, and
selected config values. The default manifest target variant is `direct`, which
names `HjxlPreparedDctAxiStreamCore`, `HjxlPreparedDctAxiLiteStreamCore`, and
`HjxlKv260PreparedDctTop`. Pass `--target-variant estimated-cfl` when the same
prepared stream payload should be replayed against
`HjxlPreparedCflDctAxiStreamCore`, `HjxlPreparedCflDctAxiLiteStreamCore`, or
`HjxlKv260PreparedCflDctTop`; generated headers, host bundles, and replay plans
then expose `target.variant = "estimated-cfl"` and the estimated-CFL shell names.

Validate an existing prepared-DCT stream/control bundle before replay:

```sh
python3 tools/hjxl_prepared_blocks.py \
  --validate-manifest build-codex/fixtures/gradient-17x9-prepared-blocks-manifest.json
```

Validation checks manifest format, stream word count, block count, final-only
TLAST, stream CSV columns, AXI-Lite CSV columns, full-byte strobes, register
values, coefficient fractional scale, and source prepared-JSON metadata and
stream word contents when the source file is present. Malformed integer and
boolean CSV fields fail with line-specific diagnostics before replay.
Use `tools/hjxl_manifest_header.py` on the same prepared-DCT manifest to emit
host constants, target interface/variant macros, and the ordered control-write
table for the selected prepared control plane. Use
`tools/hjxl_stream_buffer.py` on
that manifest to emit the 32-bit little-endian prepared-block stream payload
and optional TLAST sidecar, or prefer `tools/hjxl_host_bundle.py` to generate
and `--validate-bundle` to verify the complete prepared-DCT host handoff. Use
`tools/hjxl_host_metadata_smoke.py` as the fast Python-only check that prepared
coefficient scale, input stream bit width, input `TKEEP` mask, trace `TKEEP`
mask, DMA byte count, replay-plan metadata, and capture preflight guards still
agree. It also checks that direct prepared-DCT and estimated-CFL prepared-DCT
target variants propagate through manifests, generated headers, host bundles,
replay plans, and replay-capture preflight guards. The same smoke keeps the
signed fixed-CFL control path covered by checking that `fixed_ytox = -7` and
`fixed_ytob = 11` survive manifest generation, AXI-Lite CSV emission,
replay-plan write tables, and generated C/C++ header smoke compilation.
`HjxlPreparedDctAxiStreamCoreSpec` drives that generated stream into RTL and
checks that the resulting packed token trace stream assembles to the same frame
and bare codestream bytes as libjxl-tiny's direct DCT-only path.
`HjxlPreparedCflDctAxiStreamCore` is the estimated-CFL sibling shell. It
accepts the same 201-word prepared block stream and emits the same packed trace
format, but wraps `FramePreparedCflDctOnlyQuantizeTokenTraceStage`, which
buffers prepared coefficients, estimates tile `YtoxMap`/`YtobMap` values in
RTL, then quantizes and tokenizes with those maps. The scalar `ytox`/`ytob`
input words are still consumed to preserve the stream ABI, but this route does
not use them as the quantization maps. Generate it with
`sbt 'runMain hjxl.ElaboratePreparedCflDctAxiStream'`; the focused
`HjxlPreparedCflDctAxiStreamCoreSpec` compares the packed shell against the
structured estimated-CFL wrapper.
`HjxlPreparedCflDctAxiLiteStreamCore` wraps the estimated-CFL stream shell with
the same 32-bit AXI-Lite register map and input `TKEEP` handling used by
`HjxlPreparedDctAxiLiteStreamCore`. Generate it with
`sbt 'runMain hjxl.ElaboratePreparedCflDctAxiLiteStream'`; its focused spec
checks register access, invalid input keep handling, generated ports, and trace
equivalence against the direct estimated-CFL stream shell.
`HjxlPreparedDctAxiLiteStreamCore` wraps the direct prepared-DCT stream shell
with the same 32-bit AXI-Lite register map as `HjxlAxiLiteStreamCore`; `xsize`,
`ysize`, and status/control are consumed directly for framing, busy/overflow
visibility, protocol-error clearing, and unsupported-distance reporting, while
the rest of `FrameConfig` is kept on the common control surface for future
prepared-path experiments. Its direct `unsupportedDistance` output mirrors
status/control bit 3. It consumes partial input keep masks, reports them
through the sticky protocol-error bit, and leaves the prepared-word parser
unadvanced. Its
spec programs the DUT from the generated AXI-Lite CSV, drives the same
prepared-block stream CSV, validates the manifest, and checks assembled bytes
against libjxl-tiny.
`HjxlKv260PreparedDctTop` is a thin Vivado-facing wrapper around this controlled
prepared-DCT core. It uses an active-low `ap_rst_n`, exposes conventional
`s_axi_control_*` AXI-Lite ports, `s_axis_input_*` prepared-block input stream
ports with full-word `s_axis_input_tkeep = 0xF` and sticky protocol-error
reporting for partial input keep masks, 128-bit `m_axis_trace_*` trace output
stream ports with `tkeep` marking the valid low 11 bytes, plus `busy`,
`overflow`, `protocol_error`, and
`unsupported_distance` status pins. Generate it with
`sbt 'runMain hjxl.ElaborateKv260PreparedDctTop'`; the output directory is
`generated-kv260-prepared-dct-top/`. Its focused spec checks both the generated
flat port surface and the flat AXI-Lite status/control and protocol-error
recovery paths, input and trace `TKEEP`, plus a complete one-block prepared
input stream to padded trace `TLAST` with packed trace-field checks.
`HjxlKv260PreparedCflDctTop` is the estimated-CFL variant of that flat wrapper.
It preserves the same KV260-facing ports and 128-bit trace padding, but wraps
`HjxlPreparedCflDctAxiLiteStreamCore` internally so the prepared coefficients
drive RTL CFL-map estimation before quantization/tokenization. Generate it with
`sbt 'runMain hjxl.ElaborateKv260PreparedCflDctTop'`; the output directory is
`generated-kv260-prepared-cfl-dct-top/`. Its focused spec checks generated
ports, inner estimated-CFL module selection, status/control behavior,
input/trace `TKEEP`, partial-input-keep recovery, and one complete prepared
stream to padded trace `TLAST`.

Convert quantization `StageTrace` CSV dumps into the prepared-token simulator
CSVs consumed by `FramePreparedTokenTraceStage`:

```sh
python3 tools/hjxl_quant_trace_to_prepared_tokens.py \
  --trace-csv build-codex/traces/gradient-17x9-quant-trace.csv \
  --width 17 --height 9 \
  --dc-csv build-codex/traces/gradient-17x9-quant-prepared-dc.csv \
  --ac-csv build-codex/traces/gradient-17x9-quant-prepared-ac.csv \
  --prepared-json build-codex/traces/gradient-17x9-quant-prepared-token-inputs.json
```

The optional prepared-token JSON output carries the same frame/tile/block
geometry metadata as `tools/hjxl_reference.py
--fixed-dct-only-prepared-token-inputs-json` and can be revalidated with
`tools/hjxl_prepared_token_inputs.py`. `QuantDc` and `QuantizedAc` trace values
must stay within the signed-32 `StageTrace.value` range before the converter
writes prepared-token CSVs or JSON.

Convert prepared-token JSON fixtures into simulator input CSVs:

```sh
python3 tools/hjxl_prepared_token_inputs.py \
  --prepared-json build-codex/fixtures/gradient-17x9-prepared-token-inputs.json \
  --dc-csv build-codex/fixtures/gradient-17x9-prepared-dc.csv \
  --ac-csv build-codex/fixtures/gradient-17x9-prepared-ac.csv
```

The converter requires the exact
`hjxl.fixed_dct_only_prepared_token_inputs.v1` format string, then validates the
prepared-token fixture's integer image geometry, optional fixed raw-quant and
signed 8-bit scalar CFL metadata, signed-32 DC samples, AC coordinates, nonzero
counts, signed-32 coefficients, block/tile geometry, and optional AC block
`block_x`/`block_y` and `tile_x`/`tile_y` coordinates before writing simulator
CSVs. Floating-point JSON numbers are rejected for integer fields instead of
being truncated.

`RgbToXybApproxSpec` locks the earliest active RGB-path arithmetic boundary.
It covers normalized cube-root scale transitions, signed-matrix ordering,
high-dynamic-range inputs that exceeded the former LUT range, independent
libjxl-tiny Q8-to-Q12 CSV fixtures, ready/valid flow control, and the broad
signed input sweep described above.

`AqContrastSpec` locks the next RGB-path boundary. It checks the Q12-to-Q16
gamma-ratio interpolation and integer-square-root primitives, exact hardware
agreement with the bit model, a broad smooth-neighborhood formula comparison,
global 4x4 cell traversal, output stability under backpressure, first-beat
frame snapshots, invalid geometry, five libjxl-tiny fixture families, and a
65x1 cross-tile traversal that exercises the carry-safe 72-pixel padded width.

`AqFuzzyErosionSpec` locks the following AQ boundary. It checks the weighted
four-minimum primitive, exact prepared-Q16 block output within one LSB, stable
backpressure and frame snapshots, five RGB oracle families within two percent,
a 65x1 RGB tile crossing, and two-dimensional 65x65 input padded to 72x72.
`AqFuzzyErosionElaborationSpec` guards the composed/prepared module surface.

`AqStrategyMaskSpec` locks the next block-resolution seam. It checks the
rational reciprocal against directed and randomized inputs, exact 42-cycle
latency and backpressure stability, exact prepared fixture masks, five composed
RGB oracle families, and horizontal/two-dimensional tile traversal.
`AqStrategyMaskElaborationSpec` also verifies that the reciprocal emits without
a SystemVerilog division operator.

`AqNonlinearMaskSpec` locks the parallel final-map branch after erosion. It
checks the three-term Q24 evaluator against directed/randomized float32 and
integer models, its exact shared-divider latency, signed prepared output and
backpressure stability, five RGB oracle families, and horizontal/two-
dimensional tile traversal. `AqNonlinearMaskElaborationSpec` verifies that one
sequential divider is shared and neither evaluator nor divider emits a
SystemVerilog division operator.

`AqHfModulationSpec` locks the first per-block final-map modulation. It checks
the 112-edge sum and Q32 coefficient against integer and float32 models, exact
64-cycle block latency, signed saturation, exact prepared fixtures, config and
backpressure behavior, five RGB oracle families, and 65x1/65x65 traversal.
`AqHfModulationElaborationSpec` verifies a sequential edge walker, no division
operator in the block datapath, and exactly one RGB-to-XYB converter in the
composed stage.

`AqColorModulationSpec` locks the next cumulative per-block operation. It
checks Q16 ramp coverage and distance-folded Q24 coefficients against exact
integer and float32 models across the active, zero-strength, and early-return
distance regimes; exact 64-cycle latency; saturation and stalled-output
stability; prepared oracle rows; five composed RGB families; and 65x1/65x65
traversal. `AqColorModulationElaborationSpec` verifies sequential HF/color
walkers, no color-block divider, one shared frame scheduler, and one
RGB-to-XYB converter.

`AqGammaModulationSpec` locks the final log-domain per-block operation. It
checks inverse-ratio interpolation and explicit clamps, normalized Q20
`fast_log2f`, exact fixed-model output and 128-cycle latency, saturation and
stalled-output stability, prepared oracle rows, five composed RGB families,
and 65x1/65x65 traversal. `AqGammaModulationElaborationSpec` verifies the
sequential HF/color/gamma chain, divider-free gamma arithmetic, one shared frame
scheduler, and one RGB-to-XYB converter.

`AqFinalModulationSpec` locks the final power/scale/dampen operation and its
connection to raw quantization. It checks normalized `fast_pow2f` exponent
seams, exact fixed-model output, float32 error, saturation and backpressure,
distance-7/14 damping boundaries at the prepared seam, supported RGB distance
parameters, five RGB families, exact fixed-model raw-quant bytes, explicit fixed override,
unsupported-distance fallback, and 65x1/65x65 traversal.
`AqFinalModulationElaborationSpec` verifies one cumulative scheduler/converter
chain and rejects division operators in the new exponent/final-map datapaths.

`AdaptiveQuantizationSpec` keeps the prepared final-AQ conversion seam isolated
from the now-connected RGB heuristics. It validates Q24 multiplication,
nearest rounding, low/high clamping, exact `RawQuantField` traces from multiple
libjxl-tiny patterns and distances, output stability under backpressure, frame
control snapshots, invalid geometry, and standalone SystemVerilog elaboration.

`FramePreparedDctOnlyQuantizeTraceStageSpec` checks the prepared-DCT quantizer
scheduler against libjxl-tiny prepared-block fixtures with exact quantized
AC/DC/nonzero trace comparison at the Q16 prepared-coefficient boundary, then
verifies that the observed RTL quantization trace converts into prepared-token
DC/AC CSVs and a prepared-token JSON fixture without reordering or dropping
records. It round-trips that JSON through `tools/hjxl_prepared_token_inputs.py`
before driving `FramePreparedTokenTraceStage` through DC, strategy, metadata,
and AC token trace emission. The same spec also checks
`FramePreparedDctOnlyQuantizeTokenTraceStage`, the direct RTL wrapper, against
that staged handoff and against libjxl-tiny token-array oracles for DC,
AC-metadata, AC, and strategy streams. It converts the direct wrapper's token
`StageTrace` output into token arrays and verifies the host assembler produces
the same frame bytes and bare JPEG XL codestream as the direct libjxl-tiny
DCT-only path. It also feeds the direct wrapper's `StageTrace` CSV to
`tools/hjxl_trace_to_codestream.py` and checks those bytes against the same
oracle. The spec also
exercises the adaptive all-DCT oracle exports by checking that
`--dct-only-dc-tokens-npy`, `--dct-only-ac-metadata-tokens-npy`,
`--dct-only-ac-tokens-npy`, and `--default-ac-strategy-npy` assemble back into
the same bytes as `--dct-only-frame-bin` and `--dct-only-codestream-bin`, and
uses `tools/hjxl_compare_tokens.py` for exact token-array comparison on the
direct wrapper trace.
`FramePreparedAcMetadataTokenTraceStageSpec` separately covers two-tile
prepared metadata fixtures for CFL residual prediction and raw-quant residual
contexts, including a libjxl-tiny oracle-backed 72x8 all-DCT case.
`FramePreparedCflAcMetadataTokenTraceStageSpec` covers the estimated-CFL
metadata path with horizontal 72x8, vertical 8x72, and two-dimensional 72x72
tile grids; keep the 72x72 case as the focused regression for the northwest
tile-neighbor predictor branch.
`PreparedDctElaborationSpec` is the focused FIRTool/SystemVerilog emission gate
for the prepared-DCT quantization and direct quantize-to-token standalone tops;
it emits into a temporary directory, checks the structured prepared-block input
and trace output port surface, and does not leave generated RTL in the checkout.
`FixedDctOnlyTokenAssemblySpec` is the current regression for that
hardware/software boundary. It generates a prepared-token JSON fixture with
`tools/hjxl_reference.py`, converts it to simulator CSVs with
`tools/hjxl_prepared_token_inputs.py`, drives `FramePreparedTokenTraceStage`,
converts the resulting StageTrace CSV with `tools/hjxl_trace_tokens.py`, feeds
the generated token arrays back to `tools/hjxl_reference.py`, and checks that
the assembled frame and codestream bytes match the direct libjxl-tiny
fixed-token oracle. It also checks `tools/hjxl_trace_to_codestream.py`, the
one-step StageTrace-to-byte assembler, against the same token arrays and byte
oracle. The full RGB-input token schedulers still depend on the approximate
fixed-point RGB/XYB/DCT path and are tracked as a separate parity gap.

## ABI generation

Stable host/RTL constants originate in [`abi/hjxl_abi.json`](abi/hjxl_abi.json).
Run `python3 tools/hjxl_generate_abi.py` after an intentional ABI edit and
`python3 tools/hjxl_generate_abi.py --check` to detect stale checked-in Scala
or Python bindings. See [`abi/README.md`](abi/README.md) for scope and update
rules.

## Versions

- Scala 2.13.18
- Chisel 7.13.0
- ScalaTest 3.2.19
- sbt 1.12.13
- Mill 1.1.7

## License

GPLv3. See [LICENSE](LICENSE).
