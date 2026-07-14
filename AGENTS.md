# HJXL Agent Guide

## Project Goal

`hjxl` is a Chisel RTL project for a functional FPGA-oriented JPEG XL encoder.
The goal is not full JPEG XL coverage. Target parity is `libjxl-tiny`,
available locally at:

```sh
/Users/yunhocho/GitHub/libjxl-tiny
```

Use that repository as the behavioral reference, especially its Python port
under `python/jxl_tiny` and trace/parity tools under `tools/`.

The initial FPGA target is AMD Kria KV260 / Zynq UltraScale+ MPSoC. Optimize
first for correctness and traceability on still images, then for throughput.

## Scope

In scope:
- Linear RGB input derived from PFM or host-decoded image files.
- Lossy VarDCT path modeled after libjxl-tiny.
- XYB conversion, adaptive quantization, chroma-from-luma, AC strategy, DCT,
  quantization, tokenization, and stage traces.
- Chisel RTL, ChiselSim tests, generated SystemVerilog, and eventually KV260
  Vivado packaging.

Out of scope for early work:
- Full JPEG XL spec coverage.
- Lossless mode.
- Alpha channels.
- Container metadata/boxes beyond what libjxl-tiny emits.
- Bit-exact parity at every floating-point stage.
- Camera-stream integration before the PFM/buffer path works.

## Architecture Defaults

Use a hardware/software split:
- Host software reads files, converts to linear RGB/PFM-equivalent buffers, and
  initially performs final entropy optimization and JXL bitstream assembly.
- Chisel hardware accelerates deterministic image/transform/token stages.
- Internal RTL interfaces should use ready/valid `Decoupled` streams.
- `HjxlAxiStreamCore` is the current hardware-facing shell. It accepts raster
  RGB stream words, generates `RgbPixel.x/y`, and packs `StageTrace` output
  words as `{value,index,group,stage}` with `stage` in the low eight bits.
  Input data packs R/G/B in consecutive `pixelBits` fields with R in the low
  bits. `protocolError` is sticky and cleared by `clearProtocolError`. Output
  `last` is valid for every current route and follows the selected scheduler's
  `traceLast` final-beat sideband. `busy` and `overflow` are forwarded from the
  selected trace route for host polling and fault reporting.
- `HjxlAxiLiteStreamCore` is the current KV260-facing controlled shell. It
  wraps `HjxlAxiStreamCore` with a minimal 32-bit AXI-Lite register map for
  `FrameConfig` and status/control while preserving the same AXI4-Stream input
  and trace output. It also exposes `unsupportedDistance` directly, matching
  status/control read bit 3 when `distanceQ8` falls back to distance 1. Host
  bundle metadata treats this control plane as 8-bit addresses, 32-bit data,
  and 4-bit strobes; keep generated headers, replay plans, and capture
  preflight checks in lockstep with that geometry.
  `HjxlAxiStreamCore` snapshots the complete `FrameConfig` on the first
  accepted input beat and holds it through the accepted final trace beat.
  AXI-Lite writes during `busy` update readable next-frame shadow state; they
  must not affect the active frame. Preserve the focused mid-frame regression
  when changing stream or control behavior.
- `HjxlPreparedDctAxiStreamCore` is the stream-shaped shell for the current
  closest-to-parity prepared-DCT quantize-to-token path. Use it when the host
  can supply prepared DCT-only block records and should receive packed token
  traces without the wide structured Chisel IO.
- `HjxlPreparedCflDctAxiStreamCore` uses the same prepared-DCT stream layout
  but estimates tile CFL maps in RTL before quantization/tokenization. Use it
  when the host can supply prepared coefficients but should not precompute
  `ytox`/`ytob`; the scalar CFL words remain present only for stream-layout
  compatibility.
- `HjxlPreparedCflDctAxiLiteStreamCore` wraps that estimated-CFL stream shell
  with the shared 32-bit AXI-Lite register map and full-word input `TKEEP`
  enforcement. Use it for controlled-shell experiments before deciding whether
  the estimated-CFL path should become the KV260 default.
- `HjxlPreparedDctAxiLiteStreamCore` wraps that prepared-DCT stream shell with
  the same AXI-Lite register map used by `HjxlAxiLiteStreamCore`. Prefer it for
  KV260-style integration experiments when the host can already supply prepared
  DCT blocks; its direct `unsupportedDistance` output mirrors the same status
  bit 3 fallback diagnostic. Its input keep sideband must be `0xF`; partial
  masks are consumed and folded into the sticky protocol-error status bit.
- `HjxlKv260PreparedDctTop` is the current Vivado-facing wrapper around
  `HjxlPreparedDctAxiLiteStreamCore`. It exposes flat `ap_clk`/`ap_rst_n`,
  `s_axi_control_*`, `s_axis_input_*`, and 128-bit `m_axis_trace_*` ports plus
  `busy`, `overflow`, `protocol_error`, and `unsupported_distance` pins, while
  preserving the tested prepared stream/control core internally.
  `s_axis_input_tkeep` is present for AXIS compatibility and the current
  prepared-word stream contract requires full 32-bit beats (`0xF`); partial
  masks are consumed and reported through the sticky protocol-error bit.
  `m_axis_trace_tkeep` marks the low 11 bytes that carry the packed trace word.
  This direct prepared-DCT wrapper is the frozen near-term synthesis and KV260
  bring-up target. Prioritize physical feasibility work here before expanding
  the experimental estimated-CFL target in parallel.
- `HjxlKv260PreparedCflDctTop` is the alternate Vivado-facing wrapper around
  `HjxlPreparedCflDctAxiLiteStreamCore`. Its flat ports and trace padding match
  `HjxlKv260PreparedDctTop`, but it estimates tile CFL maps internally from
  prepared DCT coefficients. Host manifests and bundles select this target with
  `target.variant = "estimated-cfl"`; the default prepared-DCT target remains
  `target.variant = "direct"`.
- Keep stage outputs traceable against libjxl-tiny names and array shapes.
- Treat `abi/hjxl_abi.json` as authoritative for shared register, status,
  trace-packing/stage, token-select, and prepared-stream-layout constants. Run
  `python3 tools/hjxl_generate_abi.py` after intentional changes and
  `python3 tools/hjxl_generate_abi.py --check` before committing. Do not edit
  `HjxlAbiGenerated.scala` or `hjxl_abi_generated.py` by hand.

Use stage-tolerant accuracy:
- Floating/fixed-point stages compare with documented tolerances.
- Integer maps, quantized coefficients, nonzero maps, and token streams should
  match exactly where practical.
- Prefer a simpler trace-comparable implementation before optimizing for
  resource use.

## Reference Reading Path

Read these libjxl-tiny files before making architectural changes:
1. `doc/python-port.md`
2. `doc/python-walkthrough.md`
3. `doc/tracing-infrastructure.md`
4. `python/jxl_tiny/encoder.py`
5. `python/jxl_tiny/image.py` and `python/jxl_tiny/xyb.py`
6. `python/jxl_tiny/adaptive_quantization.py`, `chroma_from_luma.py`, and
   `ac_strategy.py`
7. `python/jxl_tiny/transforms.py` and `quantization.py`
8. `python/jxl_tiny/tokenization.py`
9. `python/jxl_tiny/entropy.py` and `bitstream.py`

## Development Rules

- Do not start from final `.jxl` byte mismatches. First compare the earliest
  mismatching stage.
- Keep generated traces, simulation outputs, and build products out of git.
- Prefer small deterministic fixtures over large real images for RTL debugging.
- Keep Chisel modules parameterized by dimensions and fixed-point widths where
  useful, but do not over-generalize before the libjxl-tiny path works.
- Use carry-preserving `+&` in Chisel ceil-div helpers before dividing or
  shifting by block/tile sizes. Plain `+` can drop the carry bit and undercount
  valid non-tile-aligned capacities such as 72px.
- Use `circt.stage.ChiselStage.emitSystemVerilogFile` for RTL generation.
- Use `chisel3.simulator.scalatest.ChiselSim` for ScalaTest hardware tests.
- Treat Vivado/KV260 bitstream validation as unavailable unless `vivado` or
  `vitis` is present or the user provides a toolchain environment.
- For the frozen direct prepared-DCT FPGA target, use
  `tclsh fpga/vivado/synth.tcl --preflight-only` after elaboration to validate
  the generated source set without Vivado. With Vivado installed, use
  `vivado -mode batch -source fpga/vivado/synth.tcl`; keep reports beneath the
  gitignored `build/vivado/` tree and distinguish out-of-context synthesis from
  place-and-route, bitstream, and board proof.
- `HjxlPreparedDctThroughputSpec` is the deterministic no-backpressure cycle
  baseline for the direct prepared-DCT stream. Keep its exact measurements and
  `docs/performance.md` synchronized. Explain startup latency, steady-state
  input cost, and content-dependent token expansion separately; do not present
  the 200 MHz conversion as measured hardware performance until Vivado timing
  closes.

## Current RTL State

- `FramePadTraceStage` is the first real libjxl-tiny stage. It emits
  `input_padded` samples in channel-first order and implements right/bottom edge
  replication to the next 8x8 block boundary. Its `traceLast` output marks the
  final padded B-channel sample.
- `RgbToXybApprox` is a standalone Q8 linear-RGB to Q12 XYB approximation. It
  performs the signed matrix at Q26, adds the opsin bias and then clamps at
  Q24, and uses `CbrtApproxQ12` to normalize by powers of eight before
  interpolating a 225-entry Q5 cube-root table over `[1, 8)`. It covers the
  full positive signed-16/Q8 range without the former absorbance saturation
  above roughly 2.0. The libjxl-tiny Q8-to-Q12 fixture bound is two Q12 units
  and the deterministic 100,000-vector full signed-range model bound is five;
  treat it as a locked approximation, not bit-exact XYB parity.
- `FrameXybTraceStage` buffers and pads the frame, reuses `RgbToXybApprox`, and
  emits channel-first XYB trace samples. Its `traceLast` output marks the final
  padded B-channel sample. `HjxlCore` selects this stage when
  `FrameConfig.enableXyb` is true without a downstream AQ/DCT route.
- `AqGammaRatioQ16`, `UnsignedIntegerSquareRoot`, and `AqContrastPixel`
  implement the first image-dependent AQ arithmetic seam. They consume Q12
  X/Y neighborhoods and emit positive Q16 masking contributions. The gamma
  approximation is piecewise around its steep near-zero region, and the
  masking square root is an explicit bounded integer datapath rather than a
  variable divider or floating-point operator.
- `FrameAqContrastTraceStage` converts each accepted RGB pixel once, buffers
  Q12 X/Y, and schedules one `AqContrastPixel` across global 4x4 cells of the
  padded frame. It emits row-major `TraceStage.AqContrast` Q16 pre-erosion
  values. `HjxlCore` selects it for `enableXyb && enableQuant` with
  `tokenSelect = TokenTraceSelect.AqContrast` when DCT and tokenization are
  disabled; the default token selector preserves the existing AC-strategy
  route. It snapshots geometry on the first accepted beat;
  later control changes apply to the next frame. The global grid deliberately
  avoids duplicate 4-pixel tile halos. Its passive `xybAccepted` sideband marks
  the converted sample stored for each accepted input, allowing later AQ
  modulation stages to share the same converter. Do not describe this trace as final
  AQ-map or raw-quant parity.
- `AqFuzzyErosionSample` selects the four smallest positive Q16 values from a
  clamped 3x3 contrast neighborhood and adds the center a second time.
  `FramePreparedAqFuzzyErosionTraceStage` buffers row-major prepared contrast
  cells, accumulates four weighted neighborhoods, performs one nearest divide
  by 20, and emits one `TraceStage.AqFuzzyErosion` Q16 value per padded 8x8
  block. `FrameAqFuzzyErosionTraceStage` composes the RGB contrast stage with
  that prepared scheduler without duplicating XYB conversion. It is available
  through the focused `traceRoute = TraceStage.AqFuzzyErosion` core build; the
  default all-route core continues to select the earlier `AqContrast` trace for
  `tokenSelect = AqContrast`.
- `AqStrategyMaskReciprocal` evaluates libjxl-tiny's pre-modulation
  `1 / (erosion + 0.001)` strategy mask in Q16 using an exact 1/1000 rational
  offset and a 42-cycle restoring divider. `FramePreparedAqStrategyMaskTraceStage`
  applies that primitive to prepared raster block values, and
  `FrameAqStrategyMaskTraceStage` composes the contrast and erosion path without
  duplicating their arithmetic. It is a focused
  `traceRoute = TraceStage.AqStrategyMask` diagnostic route and is not yet wired
  into rectangular strategy scoring.
- `AqNonlinearMaskEvaluator` evaluates the distinct `_compute_mask` branch from
  positive Q16 erosion to the signed-Q24 log-domain seed used by final AQ-map
  modulation. It shares one 56-cycle restoring divider across three rational
  terms for an exact 174-cycle evaluator latency. The prepared frame stage
  preserves raster order/config lifetime and `FrameAqNonlinearMaskTraceStage`
  composes it after the existing contrast/erosion path. Use the focused
  `traceRoute = TraceStage.AqNonlinearMask`; do not call this the final AQ map.
- `AqHfModulationBlock` adds libjxl-tiny's first per-block modulation to a
  signed-Q24 nonlinear seed. It walks one signed-Q12 8x8 Y block over exactly
  64 cycles, sums all 112 internal horizontal/vertical absolute differences,
  and applies the Q32 coefficient without a divider or wide combinational edge
  tree. `FramePreparedAqHfModulationTraceStage` provides the exact prepared
  boundary. `FrameAqModulationBlockStage` is the reusable RGB scheduler that
  snapshots the frame, stores accepted XYB, applies right/bottom edge
  replication, pairs blocks with nonlinear seeds, and waits for downstream
  final-block retirement before accepting a new frame. The focused
  `FrameAqHfModulationTraceStage` requests Y-only storage and emits raster
  `TraceStage.AqHfModulation` rows. Use the focused
  `traceRoute = TraceStage.AqHfModulation`; do not call this the final AQ map.
- `AqColorModulationBlock` applies the next cumulative per-block term to a
  signed-Q24 HF seed. It scans one signed-Q12 XYB block over exactly 64 cycles,
  accumulates capped red/blue coverage in Q16, and uses distance-folded Q24
  constants with no runtime divider. Distance above 4 returns the seed exactly.
  `FramePreparedAqColorModulationTraceStage` locks the prepared frame's Q8
  distance and order. `AqHfColorModulationBlockPipeline` is the reusable
  prepared-block chain that preserves X/Y context after color, and
  `FrameAqColorModulationTraceStage` composes it with the shared frame
  scheduler. It emits `TraceStage.AqColorModulation` rows with one RGB-to-XYB
  converter.
- `AqGammaModulationBlock` applies the final log-domain per-block term. It
  evaluates one red- or green-derived inverse response per cycle, clamps the
  prepared Q12 response input to `[0, 8]`, uses fine/coarse/HDR Q20 ratio
  interpolation, averages 128 responses, and applies a normalized Q20
  `fast_log2f` approximation. Its exact latency is 128 cycles and it contains
  no runtime divider. `FramePreparedAqGammaModulationTraceStage` is the exact
  prepared frame boundary; `FrameAqGammaModulationTraceStage` reuses the shared
  scheduler and HF/color/gamma pipeline and emits `TraceStage.AqGammaModulation`
  rows with one RGB-to-XYB converter.
- `AqFastExpQ24` and `AqFinalModulationBlock` implement the final per-block
  power/scale/damping step. The former uses a normalized 257-entry fractional
  `fast_pow2f` table with explicit exponent saturation; the latter applies the
  Q24 distance scale, high-distance damping, base-level add, and unsigned
  saturation. `FrameAqFinalMapPipeline` shares one completed cumulative chain
  between final-map, raw-quant, DCT-quantization, and token consumers, applies
  the distance-1 fallback before color/final arithmetic, and retains the X/Y/B
  Q12 block plus all quantization scalars. `TraceStage.AqFinalMap` is the
  completed unsigned-Q24 diagnostic seam.
- `Dct8Approx` is a standalone Q12 1D DCT-8 primitive. It should be reused for
  the future 8x8 transform stage instead of writing a separate transform shape
  from scratch.
- `Dct8x8Approx` is the first block-level transform primitive. It uses
  `Dct8Approx` for both dimensions, applies libjxl-tiny's per-dimension 1/8
  scale, and emits the scaled 8x8 coefficient layout used before quantization.
- `Dct16Approx` is the matching recursive Q12 16-point kernel.
  `Dct16x8Approx` and `Dct8x16Approx` implement libjxl-tiny's two rectangular
  transforms with their intentionally different canonical 8x16 layouts. Reuse
  them for AC-strategy scoring and future rectangular quantization; do not
  duplicate their orientation logic in a scheduler. Use
  `tools/hjxl_reference.py --scaled-dct-q12-csv ...` for signed float-reference
  and exact fixed-model oracle cases.
- `AcStrategyDecisionSelector` is the exact decision-only tail for one complete
  2x2 block region. It consumes common-scale nonnegative candidate costs,
  chooses horizontal on aggregate ties, replaces a rectangle only on a strict
  win, and emits raster `(rawStrategy << 1) | isFirstBlock` values. It does not
  calculate entropy costs or schedule a frame.
- `AcStrategyCandidateCostEvaluator` is the prepared fixed-point scoring seam.
  It consumes canonical Q12 X/Y/B coefficients for one 8x8, 16x8, or 8x16
  candidate plus Q24 AQ, Q16 strategy mask, Q8 distance, and signed CFL values;
  it walks one coefficient per cycle and emits both the raw Q16 entropy/loss
  estimate and distance-scaled Q16 cost. Unsupported distances use distance 1
  and are reported, while extreme arithmetic saturates with an overflow flag.
  The integer model is exact against
  `tools/hjxl_reference.py --ac-strategy-cost-q16-csv ...`; that artifact also
  records the exact scheduler decision after fixed rectangular transforms and
  fixed CFL. The scorer remains an approximation of the float reference after
  Q12 coefficient rounding.
- `PreparedAcStrategy2x2Selector` accepts eight prepared candidates in strict
  order: four raster 8x8 blocks, left/right 16x8 rectangles, then top/bottom
  8x16 rectangles. It retains their 64-bit costs, feeds
  `AcStrategyDecisionSelector`, and holds the decision and diagnostics under
  backpressure. `FramePreparedAcStrategyTraceStage` now supplies its frame/tile
  scheduling, retains each input raw-quant byte, applies the reference
  post-search maximum to both blocks in every selected rectangle, and emits the
  adjusted byte aligned with the raster strategy trace. The default
  `FrameAcStrategyTraceStage` remains a separate fixed all-DCT scaffold.
- `DistanceParamsLookup` is the first hardware boundary for libjxl-tiny
  distance-derived scalar parameters. It supports common Q8 distances
  `64`, `128`, `256`, `512`, `1024`, and `2048`, defaulting unsupported values
  to distance 1; AXI-Lite controlled shells report that fallback at status bit
  3. It emits global AC scale, quantized DC scale, inverse DC factors, fixed
  raw-quant-5 AC reciprocal, X quant-matrix multiplier, and EPF iterations. Use
  `tools/hjxl_reference.py --distance-params-json ...` to regenerate or check
  entries against the local libjxl-tiny Python port. The entries also carry Q24
  AQ scale, damping, and inverse-global-scale values for the completed RGB AQ
  path; keep all three in lockstep with the same oracle.
- `FrameDct8x8TraceStage` buffers the RGB frame, converts each padded 8x8 block
  to approximate XYB, runs `Dct8x8Approx` for all three channels, and emits
  `RawDct8x8` trace samples. `trace.group` is the raster block index and
  `trace.index` is `channel * 64 + coefficient`; `traceLast` marks the final
  coefficient of the final padded raster block.
- `QuantizeDct8x8Block` is a standalone DCT-only AC quantization primitive. It
  takes one channel's Q12 DCT coefficients plus raw quant, AC scale Q16, and QM
  multiplier Q16 inputs, then emits quantized coefficients and a DC-excluding
  nonzero count. It does not replace the missing whole-frame AQ/CFL/Y-roundtrip
  quantization path.
- `AcQuantScaleSelector` chooses the AC scale and reciprocal pair used by
  frame schedulers. A zero `FrameConfig.fixedPointScale` means use
  `DistanceParamsLookup`; a nonzero value means use the explicit
  `fixedPointScale` and `fixedInvQacQ16` pair. A zero
  `FrameConfig.fixedRawQuant` means adjusted raw quant 5; a nonzero value is a
  global fixed-path raw quant override and must be paired with the matching
  reciprocal. `FrameConfig.fixedYtox` and `FrameConfig.fixedYtob` are signed
  8-bit scalar CFL-map overrides for focused metadata routes until real CFL
  estimation exists. Keep this boundary small and directly tested instead of
  validating the selection through expensive frame-level Verilator tests.
- `DctQuantizeTraceStage` is the prepared-block trace wrapper for that
  primitive. It emits 64 `QuantizedAc` trace records followed by one
  `NumNonzeros` record. Use it to validate quantization streams without wiring
  fake AQ/CFL values through `HjxlCore`.
- `QuantizeRoundtripYDct8x8Block` implements libjxl-tiny's Y quantize,
  quant-bias, and dequantize roundtrip for DCT-only 8x8 blocks. It needs an
  explicit reciprocal AC scale input; do not hide that with a constant unless a
  test fixture is intentionally fixed to one distance and quant value.
- `CflCoefficientSampleWeight`, `CflWeightedSumAccumulator`,
  `CflMultiplierEstimator`, `CflCoefficientSumAccumulator`, and
  `CflTileCoefficientEstimator` are the first hardware primitives for real CFL
  estimation. The weighting block applies libjxl-tiny's X/B inverse-weight
  matrix entries to Q16 luma/chroma DCT coefficient pairs, the accumulator forms
  `sum(a*a)` and `sum(a*b)`, and the estimator applies libjxl-tiny's
  regularizer, nearest signed rounding, and int8 clamping.
  `CflCoefficientSumAccumulator` composes those pieces behind a streaming
  coefficient-pair interface; `CflTileCoefficientEstimator` consumes Y/X/B
  coefficient triples and emits both tile multipliers; and
  `CflTileCoefficientTraceStage` emits the corresponding `YtoxMap`/`YtobMap`
  `StageTrace` rows. These modules do not compute tile DCTs or schedule the
  final RGB-input tile maps by themselves. `FramePreparedCflMapTraceStage`
  buffers prepared raster DCT-only blocks, streams their coefficients through
  the tile estimator in 64x64 tile order, and emits prepared-frame
  `YtoxMap`/`YtobMap` trace rows. Keep its 72x72 spec case when changing tile
  traversal; it is the direct 2x2 map-order regression reused by estimated-CFL
  metadata and quantization paths. `CflTileGeometry` centralizes the block-to-tile
  ordinal mapping used when those paths feed per-tile CFL maps into metadata and
  quantization. `FramePreparedCflAcMetadataTokenTraceStage`
  consumes the same prepared-block input, estimates the CFL maps internally, and
  feeds them into prepared AC-metadata tokenization.
  `FramePreparedCflDctOnlyQuantizeTraceStage` consumes prepared blocks, estimates
  the maps internally, and feeds the per-block tile values into DCT-only
  quantization. `FramePreparedCflDctOnlyQuantizeTokenTraceStage` combines
  estimated-CFL quantization and estimated-CFL AC metadata in the direct prepared
  quantize-to-token path.
- `QuantizeChromaResidualDct8x8Block` implements the X/B CFL residual step
  before quantization. It expects reconstructed Y coefficients from
  `QuantizeRoundtripYDct8x8Block`, a signed CFL multiplier, and the same raw
  quant/scale/QM inputs as `QuantizeDct8x8Block`.
- `QuantizeDcDct8x8Block` implements DCT-only quantized DC for one channel. Feed
  it an explicit inverse DC factor from distance parameters; for B, also feed
  the already-quantized Y DC so it can subtract the libjxl-tiny half-Y
  correction.
- `DctOnlyQuantizeBlock` composes the prepared X/Y/B block path and emits
  quantized AC, quantized DC, and nonzero counts for one all-DCT 8x8 block. It
  still expects caller-provided AQ/CFL/distance-derived scalar inputs; do not
  treat it as frame-level quantization.
- `DctOnlyQuantizeTraceStage` is the prepared-block trace wrapper for
  `DctOnlyQuantizeBlock`. It emits all `QuantizedAc` records first, then
  `QuantDc`, then `NumNonzeros`; keep this order when building frame-level
  quant traces.
- `FramePreparedDctOnlyQuantizeTraceStage` is the prepared-frame quantization
  scheduler. Feed it complete prepared DCT-only block inputs in raster order:
  X/Y/B coefficients at `HjxlConfig.preparedDctCoefficientFractionBits`
  fractional bits (Q16 by default) plus raw quant, AC scale, reciprocal AC
  scale, inverse DC factors, X quant-matrix multiplier, and CFL multipliers. It
  emits frame-shaped `QuantizedAc`, `QuantDc`, and `NumNonzeros` traces with
  `trace.group = raster block ordinal`; `traceLast` marks the final
  `NumNonzeros` record of the final prepared raster block. Prefer this boundary
  when moving upstream from prepared quantized-token inputs toward DCT/AQ/CFL
  integration.
- `FramePreparedDctOnlyQuantizeTokenTraceStage` is the direct prepared-DCT
  quantize-to-token RTL wrapper. Feed it the same prepared DCT-only raster block
  inputs; it runs `DctOnlyQuantizeBlock`, stores DC for plane-order replay, and
  atomically streams AC/nonzero results plus metadata into the dedicated frame
  schedulers that own those stores. Do not reintroduce orchestration-level
  AC/metadata frame arrays. This wrapper preserves prepared raw-quant and CFL
  metadata instead of falling back to fixed raw-quant/scalar-CFL metadata, and
  its `traceLast` output marks the final AC-token trace beat.
- `FrameRawQuantFieldTraceStage` is the narrow pre-strategy real/fixed
  raw-quant selector. Zero `FrameConfig.fixedRawQuant` routes RGB through
  `FrameAqRawQuantTraceStage`; a nonzero byte selects the retained
  `FrameFixedRawQuantFieldTraceStage`. `FrameAdjustedRawQuantFieldTraceStage` is
  the focused public selector: its zero-override branch runs
  `FrameAqAdjustedRawQuantTraceStage`, while a nonzero uniform byte uses the
  cheap fixed branch because it is adjustment-invariant. Selection is captured
  at frame start. All paths emit zero-extended `RawQuantField` bytes, so values
  above 127 stay positive in `StageTrace.value`. The default all-route shell
  omits the heavy adjusted route.
- `AqMapToRawQuant` converts unsigned Q24 prepared AQ-map and inverse-global-
  scale values into libjxl-tiny pre-strategy raw-quant bytes with nearest rounding
  and a `[1, 255]` clamp. `FramePreparedAqRawQuantTraceStage` streams one such
  prepared AQ value per padded raster block, snapshots frame geometry and scale
  on the first accepted block, and emits backpressure-safe `RawQuantField`
  traces with `traceLast`. It remains the narrow prepared arithmetic seam; the
  RGB final-map pipeline now feeds the same converter directly. Prepared-token
  scheduling still consumes host-prepared raw quant metadata.
- `AdaptiveInvQacQ16` is the 33-cycle restoring divider for adaptive
  quantization's per-block reciprocal AC scale. It emits nearest
  `round(2^32 / (scaleQ16 * rawQuant))`, saturates a zero divisor, holds output
  stable under backpressure, and must elaborate without a division operator.
  `FrameAqDctBlockStage` combines the completed AQ map/raw byte, one retained
  Q12 X/Y/B block, three `Dct8x8Approx` instances, and distance-derived DC/QM
  parameters without elaborating a reciprocal. `FrameAqDctOnlyBlockStage`
  enriches that record with the dynamic reciprocal for quantization. Nonzero
  `fixedRawQuant` bypasses the divider result and uses the caller's matching
  `fixedInvQacQ16`; zero recomputes the reciprocal even when `fixedPointScale`
  overrides the lookup scale.
- `FrameAqCflMapTraceStage` is the RGB-derived CFL route. It buffers native-Q12
  DCT blocks, widens them to Q16 only at the prepared tile-estimator boundary,
  and emits both tile maps or a compile-time-filtered Y-to-X/Y-to-B stream.
  Focused `HjxlCore` map routes use this scheduler; the default all-route core
  omits those heavy diagnostic paths.
- `FrameAqCflDctOnlyQuantizeTraceStage` is the current `HjxlCore` RGB quantized
  route. It applies adaptive raw quant and estimated tile CFL through the Q12-
  parameterized prepared quantizer, emitting `QuantizedAc`, `QuantDc`, and
  `NumNonzeros`. `FrameAqCflDctOnlyAcMetadataTokenTraceStage` is the current
  core metadata route; it estimates CFL from the same DCT records but omits the
  reciprocal. `FrameAqCflDctOnlyQuantizeTokenTraceStage` is the combined RGB
  path for DC, all-DCT strategy, adaptive metadata, and AC logical traces; its
  AC-only form backs the focused core route. The earlier `FrameAqDctOnly*` and
  `FrameCflMapTraceStage` modules remain fixed-CFL diagnostics. Do not describe
  any RGB path as full parity: XYB/DCT remain approximate and rectangular
  strategy search is absent.
- `FrameDctOnlyQuantizeTraceStage` is the retained fixed frame-level quant
  diagnostic. It buffers/pads RGB, computes approximate XYB and DCT per raster
  8x8 block, then emits 192 `QuantizedAc`, three `QuantDc`, and three
  `NumNonzeros` records per block. It intentionally still uses one global
  adjusted raw quant value and scalar fixed CFL multipliers, but it now consumes
  distance-derived AC/DC scalar parameters from `DistanceParamsLookup`.
  `FrameConfig.fixedPointScale`
  plus `fixedInvQacQ16` overrides only the AC scale path; zero scale uses the
  distance lookup scale and reciprocal. `FrameConfig.fixedRawQuant` can override
  the default raw quant 5 globally for trace experiments. Do not
  describe it as adaptive-quantization parity. `traceLast` marks the final
  `NumNonzeros` record of the final padded raster block.
- `DcTokenize` provides libjxl-tiny DC token helpers: signed-token packing,
  clamped-gradient prediction, and the compressed gradient-context LUT.
- `DcTokenTraceStage` is the prepared quantized-DC token primitive. Feed it a
  current quantized DC sample plus west/north/northwest predictor neighbors; it
  emits exactly one `DcTokens` trace record. Its spec is oracle-backed against
  libjxl-tiny for a constant-frame prepared DC plane.
- `FramePreparedDcTokenTraceStage` is the exact prepared-frame DC scheduler.
  Feed it complete quantized DC planes in libjxl-tiny token order: Y plane, X
  plane, then B plane, each in raster block order. It buffers the planes,
  computes west/north/northwest predictors, and emits `DcTokens` trace records.
  Prefer this in host-boundary tests over hand-building predictor-neighbor
  inputs for `DcTokenTraceStage`.
- `FrameDctOnlyDcTokenTraceStage` is the first token trace stage. It uses the
  same fixed-parameter DCT-only frame path and emits only DC tokens, in Y/X/B
  plane order. For token traces, `trace.group` is the token ordinal,
  `trace.index` is the token context, and `trace.value` is the packed residual.
  It delegates predictor/context/residual packing to `DcTokenTraceStage`; its
  `traceLast` output marks the final DC token.
- `FrameDctOnlyAcMetadataTokenTraceStage` emits the fixed-path AC metadata
  tokens: scalar fixed CFL tile maps, all-DCT strategy choices, fixed raw quant-field
  values, and fixed block metadata literals. It uses
  `trace.stage = AcMetadataTokens`, `trace.group = token ordinal`,
  `trace.index = context`, and `trace.value = packed residual or literal`; its
  `traceLast` output marks the final metadata token.
- `FramePreparedAcMetadataTokenTraceStage` emits AC metadata tokens from
  prepared raster block metadata. Feed one raw quant, Y-to-X CFL, and Y-to-B CFL
  value per raster block. It stores raw quant per block and CFL per tile, then
  emits CFL residuals, all-DCT strategy tokens, quant-field residuals, and fixed
  block metadata literals in libjxl-tiny order.
- `FrameDctOnlyAcNonzeroTokenTraceStage` emits the nonzero-count prefix tokens
  for AC coefficient tokenization in ordinary-DCT block/channel order. It uses
  `trace.stage = AcTokens`, `trace.group = token ordinal`, `trace.index =
  context`, and `trace.value = nonzero count`; `traceLast` marks the final
  prefix token. It does not emit coefficient scan/value tokens yet. It is
  currently a standalone frame stage; do not claim it is selected by `HjxlCore`.
- `AcCoefficientTokenTraceStage` emits prepared-block ordinary-DCT coefficient
  scan/value tokens after the nonzero-count prefix. Feed it quantized
  coefficients, channel, nonzero count, and the first token ordinal; it emits
  `trace.stage = AcTokens`, `trace.group = token ordinal`, `trace.index =
  zero-density context`, and `trace.value = packed coefficient`.
- `AcBlockTokenTraceStage` wraps the nonzero-count prefix and prepared
  coefficient scan tokens for one ordinary-DCT block/channel. It still expects
  the caller to provide the predicted nonzero count from frame/block neighbors.
- `DctOnlyAcBlockTokenTraceStage` sequences prepared AC token streams for one
  X/Y/B ordinary-DCT block in libjxl-tiny channel order: Y, X, then B. It still
  expects caller-provided predicted nonzero counts and first token ordinal.
- `FramePreparedAcTokenTraceStage` is the exact prepared-frame AC scheduler.
  Feed it complete quantized AC blocks in raster order. It serializes each
  block into `PreparedAcCoefficientFrameStore`, whose `SyncReadMem` holds one
  96-bit X/Y/B coefficient triplet per address, while small register planes
  retain nonzero counts for west/north prediction. It prefetches the next block
  during current-block token emission. Preserve this narrow memory shape and
  Decoupled input stalls; do not expand the frame back into a wide `Reg(Vec)`.
- `FramePreparedTokenTraceStage` is the combined exact prepared-token boundary.
  Feed it prepared quantized DC samples first, then prepared quantized AC blocks.
  It emits DC tokens, AC strategy traces, AC metadata tokens, and AC tokens in a
  single `StageTrace` stream suitable for `tools/hjxl_trace_tokens.py`. Its DC
  and AC token schedulers consume prepared quantized inputs; its fixed metadata
  path uses `FrameConfig.fixedRawQuant`, `fixedYtox`, and `fixedYtob` rather
  than per-block prepared metadata inputs. Its `traceLast` output is asserted
  with the final AC-token trace beat for the frame.
- `FrameDctOnlyAcTokenTraceStage` is the complete RGB-input standalone frame
  scheduler for fixed all-DCT AC tokens. It recomputes the current fixed DCT-only
  quantized block stream, predicts nonzero counts from west/north block
  history, and emits full Y/X/B block AC streams through
  `DctOnlyAcBlockTokenTraceStage`.
- `HjxlAcTokenCore` wraps the full AC-token frame scheduler as a dedicated
  compile-time top. Use `sbt 'runMain hjxl.ElaborateAcTokens'` for this path
  when a standalone full-AC-token top is needed. `HjxlCore` can also expose the
  same route when elaborated with `traceRoute = TraceStage.AcTokens`; use
  `sbt 'runMain hjxl.ElaborateCoreAcTokens'` when generated SystemVerilog should
  keep the public core IO shell while instantiating only that route. The default
  all-route shell intentionally does not instantiate this heavy scheduler.
  Every current frame trace scheduler/top exports `traceLast`; `HjxlCore`
  carries it for the selected route, while `HjxlAxiStreamCore` maps it to
  stream TLAST for capture.
- Use `sbt 'runMain hjxl.ElaborateAxiStream'` for the default AXI-stream-shaped
  wrapper and `sbt 'runMain hjxl.ElaborateAxiStreamCoreAcTokens'` for the same
  stream wrapper focused on the full AC-token route. These write
  `generated-axi-stream/` and `generated-axi-stream-core-ac-tokens/`; keep both
  generated directories out of git. `HjxlAxiStreamElaborationSpec` guards the
  emitted stream-shell port surface and focused-route module inclusion.
  `HjxlAxiStreamCoreSpec` includes a host-decoder regression that captures real
  packed RTL output and feeds it to `tools/hjxl_stream_trace.py`; it also
  checks focused raw-quant/CFL metadata captures through
  `tools/hjxl_trace_tokens.py` metadata-grid extraction and
  `tools/hjxl_compare_tokens.py` fixed-oracle comparison, plus packed
  `AqContrast` stage/index/value fields and final TLAST for the selected AQ
  route plus the focused `AqFuzzyErosion`, `AqStrategyMask`, and signed
  `AqNonlinearMask`, `AqHfModulation`, `AqColorModulation`, and
  `AqGammaModulation`, plus completed `AqFinalMap`, block/TLAST routes.
- Use `sbt 'runMain hjxl.ElaborateAxiLiteStream'` for the default AXI-Lite
  controlled stream wrapper and
  `sbt 'runMain hjxl.ElaborateAxiLiteStreamCoreAcTokens'` for the focused
  full-AC-token controlled wrapper. These write `generated-axi-lite-stream/`
  and `generated-axi-lite-stream-core-ac-tokens/`; keep both generated
  directories out of git. The register map is: `0x00` status/control
  (`protocolError` read bit 0, `busy` read bit 1, `overflow` read bit 2,
  unsupported `distanceQ8` fallback read bit 3, clear protocol error on write
  bit 0), `0x04` `xsize`, `0x08` `ysize`,
  `0x0c` `distanceQ8`, `0x10` `fixedPointScale`, `0x14`
  `fixedInvQacQ16`, `0x18` `fixedRawQuant`, `0x1c` flags,
  `0x20` signed-low-byte `fixedYtox`, and `0x24` signed-low-byte `fixedYtob`
  (`enableXyb`, `enableDct`, `enableQuant`, `enableTokenize`, `tokenSelect` in
  bits 9:8). `HjxlAxiLiteStreamCoreSpec` covers register reads/writes, byte
  strobes, decode errors, and protocol-error clearing. Read-only discovery
  continues at `0x28` identity, `0x2c` ABI version,
  `0x30` capabilities, `0x34` packed maximum frame geometry, `0x38` active
  route, and `0x3c` contract build ID. Writes to those addresses must return
  DECERR. Keep schema-generated bindings, manifest/header discovery metadata,
  and RGB frame-start route latching synchronized. Use
  `tools/hjxl_discovery_check.py` on AXI-Lite `address,data,resp` readback before
  replay; do not treat manifest expectations alone as proof of the connected
  bitstream's identity or capacity.
  `HjxlAxiLiteStreamElaborationSpec` guards the generated controlled-shell
  port surface and focused-route module inclusion.
- Use `sbt 'runMain hjxl.ElaboratePreparedDctOnlyQuantize'` to generate the
  standalone prepared-DCT quantization scheduler. It writes
  `generated-prepared-dct-only-quantize/`; keep the generated directory out of
  git. The generated top exposes `traceLast` for frame-level capture
  boundaries.
- Use `sbt 'runMain hjxl.ElaboratePreparedDctOnlyQuantizeTokens'` to generate
  the direct prepared-DCT quantize-to-token wrapper. It writes
  `generated-prepared-dct-only-quantize-tokens/`; keep the generated directory
  out of git. The generated top exposes `traceLast` for frame-level capture
  boundaries.
- Use `sbt 'runMain hjxl.ElaboratePreparedDctAxiStream'` to generate the
  stream-shaped direct prepared-DCT quantize-to-token shell. It writes
  `generated-prepared-dct-axi-stream/`; keep the generated directory out of
  git. Each prepared raster block is encoded as 201 32-bit stream words:
  `quant`, `scaleQ16`, `invQacQ16`, three `invDcFactorQ16` words,
  `xQmMultiplierQ16`, signed `ytox`, signed `ytob`, then 64 X, 64 Y, and 64 B
  DCT coefficients. Signed values are two's-complement encoded in the low bits.
  Input TLAST must mark the final word of the final raster block implied by
  `xsize` and `ysize`; mismatches set sticky `protocolError`, cleared by
  `clearProtocolError`. `HjxlPreparedDctAxiStreamCoreSpec` compares this packed
  shell against the structured direct wrapper and checks generated ports.
  Use `tools/hjxl_prepared_blocks.py --input-stream-csv ...` to generate the
  matching `data,last` stream CSV from prepared-block JSON fixtures; keep that
  tool's layout in lockstep with `HjxlPreparedDctAxiStreamCore`. Add
  `--axi-lite-csv` to emit shared-register-map `address,data,strb` writes and
  `--manifest-json` to record the source fixture, image/block grid, stream
  layout, register map, status/control bits, generated paths, and selected
  config values, including the prepared coefficient fractional scale. Current
  generated fixtures use Q16 coefficients, matching
  `HjxlConfig.preparedDctCoefficientFractionBits`. The prepared-control default
  is the direct prepared-DCT token path (`flags = 526`). Use
  `tools/hjxl_prepared_blocks.py --validate-manifest ...` before replaying a
  saved prepared-DCT bundle; it checks word count, block count, final-only
  TLAST, CSV columns, AXI-Lite strobes/register values, and source prepared JSON
  metadata, coefficient fractional scale, and stream word contents when present.
  Malformed integer/boolean CSV fields should fail with line-specific
  diagnostics. Use
  `tools/hjxl_manifest_header.py --manifest-json ...
  --header ...` to emit C register constants, AXI-Lite width macros, trace
  packing macros, and an ordered AXI-Lite control write table from RGB or
  prepared-DCT manifests.
- Use `sbt 'runMain hjxl.ElaboratePreparedCflDctAxiStream'` to generate the
  estimated-CFL prepared-DCT stream shell. It writes
  `generated-prepared-cfl-dct-axi-stream/`; keep the generated directory out of
  git. Its input and output packing are identical to
  `HjxlPreparedDctAxiStreamCore`, so existing prepared stream CSV fixtures can
  be replayed, but `FramePreparedCflDctOnlyQuantizeTokenTraceStage` estimates
  tile `YtoxMap`/`YtobMap` values internally from the prepared coefficients.
  The incoming scalar `ytox`/`ytob` words are parsed only to preserve the
  201-word block ABI and should not be treated as the quantization maps for
  this route. `HjxlPreparedCflDctAxiStreamCoreSpec` compares the packed shell
  against the structured estimated-CFL wrapper and checks generated ports.
- Use `sbt 'runMain hjxl.ElaboratePreparedCflDctAxiLiteStream'` to generate the
  AXI-Lite-controlled estimated-CFL prepared-DCT stream shell. It writes
  `generated-prepared-cfl-dct-axi-lite-stream/`; keep the generated directory
  out of git. The register map, status bits, and full-word input `TKEEP`
  contract match `HjxlPreparedDctAxiLiteStreamCore`, but the internal stream
  route is `HjxlPreparedCflDctAxiStreamCore`. `HjxlPreparedCflDctAxiLiteStreamCoreSpec`
  checks register access, invalid input keep handling, generated ports, and
  trace equivalence against the direct estimated-CFL stream shell.
- Use `sbt 'runMain hjxl.ElaboratePreparedDctAxiLiteStream'` to generate the
  AXI-Lite controlled version of that prepared-DCT stream shell. It writes
  `generated-prepared-dct-axi-lite-stream/`; keep it out of git. This top uses
  the same register map as `HjxlAxiLiteStreamCore`; `xsize`, `ysize`, and
  status/control are active today, while the remaining `FrameConfig` registers
  preserve a common host control surface. `HjxlPreparedDctAxiLiteStreamCoreSpec`
  covers register access, AXI-Lite programmed frame sizing, protocol-error
  clearing, invalid input-keep rejection, generated ports, generated
  control-CSV replay, manifest validation, and a libjxl-tiny prepared-block
  stream to codestream byte comparison through the host assembler.
- Use `sbt 'runMain hjxl.ElaborateKv260PreparedDctTop'` to generate the current
  Vivado/KV260-oriented wrapper. It writes
  `generated-kv260-prepared-dct-top/`; keep it out of git. This is a thin
  `RawModule` wrapper around `HjxlPreparedDctAxiLiteStreamCore` with flat
  AXI-style port names, active-low reset, full-word input `TKEEP`, and a
  128-bit padded trace output with TKEEP. `HjxlKv260PreparedDctTopElaborationSpec`
  guards that generated port surface and simulates the flat AXI-Lite
  status/control path, input/trace `TKEEP`, a complete one-block prepared input
  stream to padded trace `TLAST` with packed trace-field checks, and
  protocol-error recovery, including partial input-keep rejection.
- Use `sbt 'runMain hjxl.ElaborateKv260PreparedCflDctTop'` to generate the
  estimated-CFL Vivado/KV260-oriented wrapper. It writes
  `generated-kv260-prepared-cfl-dct-top/`; keep it out of git. This top has the
  same flat AXI-style port surface and trace padding as
  `HjxlKv260PreparedDctTop`, but wraps `HjxlPreparedCflDctAxiLiteStreamCore`.
  `HjxlKv260PreparedCflDctTopElaborationSpec` guards generated ports, inner
  estimated-CFL module selection, flat AXI-Lite status/control behavior,
  input/trace `TKEEP`, a complete one-block prepared stream to padded trace
  `TLAST`, and partial input-keep recovery.
- `PreparedDctElaborationSpec` is the focused FIRTool/SystemVerilog emission
  regression for the prepared-DCT quantization and direct quantize-to-token
  standalone tops. It also checks the structured prepared-block input and trace
  output port surface. Run it after touching those modules or their IO bundles.
- Use `sbt 'runMain hjxl.ElaborateCflTileCoefficientTrace'` to generate
  standalone RTL for the tile coefficient CFL trace bridge. It writes
  `generated-cfl-tile-coefficient-trace/`; keep the generated directory out of
  git. The top consumes caller-supplied Y/X/B coefficient triples for one tile
  and emits `YtoxMap` then `YtobMap` trace rows. It does not schedule frame-level
  tile DCT traversal.
- Use `sbt 'runMain hjxl.ElaboratePreparedCflMapTrace'` to generate standalone
  RTL for the prepared-frame CFL map scheduler. It writes
  `generated-prepared-cfl-map-trace/`; keep the generated directory out of git.
  The top consumes prepared raster DCT-only block records, buffers
  coefficients, and emits one `YtoxMap`/`YtobMap` pair per 64x64 tile. The
  standalone top stops at map traces; the estimated-CFL quantization,
  AC-metadata, and combined token wrappers consume the same scheduler
  internally.
- Use `sbt 'runMain hjxl.ElaboratePreparedCflDctOnlyQuantize'` to generate
  standalone RTL for prepared DCT-only quantization with internally estimated
  CFL maps. It writes `generated-prepared-cfl-dct-only-quantize/`; keep the
  generated directory out of git.
- Use `sbt 'runMain hjxl.ElaboratePreparedCflDctOnlyQuantizeTokens'` to generate
  standalone RTL for the direct prepared quantize-to-token path with internally
  estimated CFL maps. It writes
  `generated-prepared-cfl-dct-only-quantize-tokens/`; keep the generated
  directory out of git.
- Use `sbt 'runMain hjxl.ElaboratePreparedCflAcMetadataTokens'` to generate
  standalone RTL for the prepared-frame AC-metadata token scheduler with
  internally estimated CFL maps. It writes
  `generated-prepared-cfl-ac-metadata-tokens/`; keep the generated directory out
  of git.
- Use `sbt 'runMain hjxl.ElaboratePreparedAcMetadataTokens'` to generate the
  standalone prepared AC-metadata token scheduler. It writes
  `generated-prepared-ac-metadata-tokens/`; keep the generated directory out of
  git.
- Use `sbt 'runMain hjxl.ElaboratePreparedDcTokens'` and
  `sbt 'runMain hjxl.ElaboratePreparedAcTokens'` to generate standalone RTL for
  the individual exact prepared-token boundaries. Use
  `sbt 'runMain hjxl.ElaboratePreparedTokens'` for the combined host-boundary
  top. These write `generated-prepared-dc-tokens/`,
  `generated-prepared-ac-tokens/`, and `generated-prepared-tokens/`; keep the
  generated directories out of git. The prepared DC, AC-metadata, AC, and
  combined prepared-token tops expose `traceLast` for frame-level capture
  boundaries. `PreparedTokenElaborationSpec` guards that generated trace port
  surface.
- Entropy coding, entropy table optimization, and bitstream assembly are still
  future work.
- `FrameAdjustedRawQuantFieldTraceStage` emits adaptive strategy-adjusted
  raw-quant bytes when `fixedRawQuant` is zero and selects the fixed byte only
  for an explicit nonzero override. It is a focused route, and its byte remains
  zero-extended into `StageTrace.value`. Keep `FrameRawQuantFieldTraceStage` as
  the smaller pre-strategy conversion diagnostic.
- Use `sbt 'runMain hjxl.ElaboratePreparedAqFinalModulation'` to generate the
  narrow prepared gamma-seed to final-map boundary. It writes
  `generated-prepared-aq-final-modulation/`; keep it out of git.
- Use `sbt 'runMain hjxl.ElaboratePreparedAqRawQuant'` to generate the
  standalone prepared AQ-map to raw-quant trace boundary. It writes
  `generated-prepared-aq-raw-quant/`; keep the generated directory out of git.
- Use `sbt 'runMain hjxl.ElaborateAqFuzzyErosion'` to generate the composed
  RGB contrast-to-erosion top. It writes `generated-aq-fuzzy-erosion/`; keep
  the generated directory out of git. This trace is pre-modulation AQ state,
  not the final AQ map.
- Use `sbt 'runMain hjxl.ElaborateAqStrategyMask'` to generate the composed RGB
  strategy-mask top. It writes `generated-aq-strategy-mask/`; keep the generated
  directory out of git. The reciprocal is sequential, but the upstream
  correctness-first RGB/contrast/erosion structures still require physical
  feasibility work.
- Use `sbt 'runMain hjxl.ElaborateAqNonlinearMask'` to generate the composed RGB
  `_compute_mask` top. It writes `generated-aq-nonlinear-mask/`; keep the
  generated directory out of git. The evaluator shares a sequential divider,
  but the upstream register grids and remaining AQ arithmetic still require
  physical feasibility work.
- Use `sbt 'runMain hjxl.ElaborateAqHfModulation'` to generate the composed RGB
  HF-modulation top. It writes `generated-aq-hf-modulation/`; keep the generated
  directory out of git. The HF block is a 64-cycle sequential edge walker and
  the composed top must contain exactly one `RgbToXybApprox`, but its full-frame
  register storage still requires physical feasibility work.
- Use `sbt 'runMain hjxl.ElaborateAqColorModulation'` to generate the composed
  RGB HF-plus-color top. It writes `generated-aq-color-modulation/`; keep the
  generated directory out of git. The color block is a 64-cycle sequential
  coverage walker with no runtime divider. The hierarchy must contain one
  shared modulation scheduler and one `RgbToXybApprox`; full-frame X/Y/B
  register storage still requires physical feasibility work.
- Use `sbt 'runMain hjxl.ElaborateAqGammaModulation'` to generate the composed
  RGB HF-plus-color-plus-gamma top. It writes
  `generated-aq-gamma-modulation/`; keep the generated directory out of git.
  The gamma block alternates ratio-table lookups over 128 cycles and uses no
  runtime divider. The hierarchy must contain one shared modulation scheduler,
  one reusable HF/color pipeline, and one `RgbToXybApprox`; the combinational
  lookup tables and full-frame X/Y/B register storage still require physical
  feasibility work.
- Use `sbt 'runMain hjxl.ElaborateAqFinalMap'` to generate the completed RGB AQ
  map top in `generated-aq-final-map/`, and
  `sbt 'runMain hjxl.ElaborateAqRawQuant'` for the same chain through
  pre-strategy raw-quant conversion in `generated-aq-raw-quant/`. Keep both out of git. The
  final exponent and scale/dampen datapaths contain no runtime divider, but the
  fractional lookup table, multipliers, and full-frame storage still need
  physical feasibility evidence.
- Use `sbt 'runMain hjxl.ElaborateAqDctOnlyQuantize'` for adaptive RGB
  quantized traces, `sbt 'runMain hjxl.ElaborateAqDctOnlyAcMetadataTokens'` for
  the lightweight adaptive metadata path, and
  `sbt 'runMain hjxl.ElaborateAqDctOnlyQuantizeTokens'` for the combined
  adaptive logical-token path. They write
  `generated-aq-dct-only-quantize/`,
  `generated-aq-dct-only-ac-metadata-tokens/`, and
  `generated-aq-dct-only-quantize-tokens/`; keep all three out of git. The
  quant/token hierarchies must contain one RGB-to-XYB converter and three DCTs;
  metadata must contain one converter and no DCT/reciprocal hardware.
- Use `sbt 'runMain hjxl.ElaborateAqCflMaps'`,
  `sbt 'runMain hjxl.ElaborateAqCflDctOnlyQuantize'`,
  `sbt 'runMain hjxl.ElaborateAqCflDctOnlyAcMetadataTokens'`, and
  `sbt 'runMain hjxl.ElaborateAqCflDctOnlyQuantizeTokens'` for the current
  estimated-CFL RGB map, quantized, metadata, and combined-token tops. Keep the
  generated `generated-aq-cfl-*` directories out of git. Every hierarchy must
  contain one RGB-to-XYB converter, three DCTs, and one CFL estimator;
  reciprocal hardware belongs only in the quantized and combined-token tops.
- `FrameAcStrategyTraceStage` emits one default DCT-first AC strategy value per
  padded block and remains the lightweight default all-route choice.
  `FramePreparedAcStrategyTraceStage` is the adaptive prepared-frame boundary:
  it buffers Q12 XYB/DCT blocks, fits per-tile CFL, generates and scores the
  four 8x8 plus four rectangular candidates for every complete tile-local 2x2
  region, leaves incomplete edges DCT, applies the exact post-search maximum to
  every selected rectangle's two raw-quant bytes, and emits the raster decision
  map plus aligned adjusted-quant sideband.
  `FrameAqAcStrategyTraceStage` feeds it from the shared RGB AQ/DCT block source;
  a focused `HjxlCore(traceRoute = TraceStage.AcStrategy)` selects this path
  while `FrameAqAdjustedRawQuantTraceStage` adapts the same sideband for the
  focused raw-quant route and the default shell stays fixed. This is not yet
  downstream non-DCT quantization: current quant/token paths remain all-DCT and
  do not consume the rectangle-adjusted quant field.
- Use `sbt 'runMain hjxl.ElaboratePreparedAcStrategy'` for the prepared frame
  search, `sbt 'runMain hjxl.ElaborateAqAcStrategy'` for its RGB composition,
  and `sbt 'runMain hjxl.ElaborateCoreAcStrategy'` for the focused public-core
  shell. They write `generated-prepared-ac-strategy/`,
  `generated-aq-ac-strategy/`, and `generated-core-ac-strategy/`; keep all
  generated directories out of git. The RGB/core hierarchy must contain one
  RGB-to-XYB converter, three ordinary DCTs, the two rectangular transform
  shapes, one CFL estimator, and no adaptive reciprocal quantizer.
- Use `sbt 'runMain hjxl.ElaborateAqAdjustedRawQuant'` for the standalone
  strategy-adjusted RGB byte trace and
  `sbt 'runMain hjxl.ElaborateCoreRawQuant'` for the focused public-core shell.
  They write `generated-aq-adjusted-raw-quant/` and
  `generated-core-raw-quant/`; keep both generated directories out of git.
- `tools/hjxl_reference.py --xyb-q12-csv ...` exports padded signed-Q8 RGB rows
  beside libjxl-tiny signed-Q12 XYB values. Keep the gradient, checkerboard, and
  random oracle cases in `RgbToXybApproxSpec` when changing matrix precision,
  clamping, normalization, or the cube-root table.
- `tools/hjxl_reference.py --aq-contrast-q16-csv ...` exports the global
  quarter-resolution pre-erosion contrast grid from native float32 XYB and XYB
  rounded to Q12, and checks the reconstructed native grid exactly against the
  array consumed by libjxl-tiny's real fuzzy-erosion call. Keep the constant,
  gradient, checkerboard, impulse, and random cases plus the 65x1 cross-tile/
  carry-width case in `AqContrastSpec`; the current RGB-connected bound is two
  percent.
- `tools/hjxl_reference.py --aq-fuzzy-erosion-q16-csv ...` exports the
  block-resolution erosion intermediate and checks its independent
  reconstruction exactly against the full reference AQ function. Keep the
  prepared one-LSB checks, five RGB families, 65x1 RGB crossing, and prepared
  65x65-to-72x72 two-dimensional traversal in `AqFuzzyErosionSpec`.
- `tools/hjxl_reference.py --aq-strategy-mask-q16-csv ...` exports prepared
  erosion, native mask, fixed-Q16 mask, and Q12-input variants. It checks the
  independent full-frame mask exactly against both the real AQ result and the
  encoder's stitched 64x64 tile calls. Keep the fixed-latency/backpressure,
  five RGB families, 65x1 crossing, and 65x65 tiled-order cases in
  `AqStrategyMaskSpec`.
- `tools/hjxl_reference.py --aq-nonlinear-mask-q24-csv ...` exports prepared
  Q16 erosion, native and fixed signed-Q24 `_compute_mask` outputs, and Q12-
  input variants. It checks an independent float32 reconstruction against the
  real private reference function and verifies full-frame equality with
  stitched 64x64 tile calls. Keep directed/random fixed-model and exact-
  latency checks, signed backpressure, five RGB families, 65x1 crossing, and
  65x65 tiled order in `AqNonlinearMaskSpec`.
- `tools/hjxl_reference.py --aq-hf-modulation-q24-csv ...` exports native,
  Q12-rounded, and signed-Q8-input cumulative signed-Q24 seeds after the Y HF
  term. It independently reconstructs the float32 edge order, checks the real
  private `_hf_modulation`, and proves full-frame equality with stitched 64x64
  calls. Keep exact 64-cycle/model/backpressure checks, five RGB families,
  65x1 crossing, 65x65 order, and single-converter elaboration in
  `AqHfModulationSpec`/`AqHfModulationElaborationSpec`.
- `tools/hjxl_reference.py --aq-color-modulation-q24-csv ...` exports native,
  Q12-rounded, and signed-Q8-input cumulative Q24 seeds after the red/blue
  term. It reconstructs the private float32 function, checks the real call,
  proves stitched 64x64 equality, and emits the Q16-coverage/Q24-coefficient
  fixed model for the requested Q8 distance. Keep active/zero/early-return
  distances, signed saturation, exact 64-cycle prepared results, RGB
  backpressure/config lifetime, five fixture families, 65x1/65x65 order, and
  single-scheduler/single-converter elaboration in
  `AqColorModulationSpec`/`AqColorModulationElaborationSpec`.
- `tools/hjxl_reference.py --aq-gamma-modulation-q24-csv ...` exports native,
  Q12-rounded, and signed-Q8-input cumulative Q24 seeds after the gamma term.
  It independently reconstructs the inverse-ratio average and `fast_log2f`,
  checks the real private function, proves stitched 64x64 equality, and emits
  the clamped ratio/log fixed model. Keep ratio/log seams, signed saturation,
  exact 128-cycle prepared results, RGB backpressure/config lifetime, five
  fixture families, 65x1/65x65 order, and single-scheduler/single-converter
  elaboration in `AqGammaModulationSpec`/
  `AqGammaModulationElaborationSpec`.
- `tools/hjxl_reference.py --aq-final-map-q24-csv ...` exports native,
  Q12-rounded, and signed-Q8-input completed AQ maps; the cumulative gamma
  seeds; Q24 scale/dampen/inverse-global-scale values; and reference/fixed
  raw-quant bytes. It reconstructs the final float32 operation exactly against
  the real AQ map. Keep exponent seams, distance-7/14 damping, saturation,
  prepared exactness, five RGB fixtures, exact fixed-model raw bytes, fixed override,
  unsupported fallback, 65x1/65x65 order, and single-scheduler/converter
  elaboration in `AqFinalModulationSpec`/
  `AqFinalModulationElaborationSpec`.
- `tools/hjxl_reference.py` can export real libjxl-tiny quant metadata with
  `--raw-quant-field-npy`, `--libjxl-ac-strategy-npy`, `--ytox-map-npy`, and
  `--ytob-map-npy`. Use those artifacts as the oracle before implementing AQ,
  CFL, or transform-strategy RTL; do not compare those outputs against the
  current default AC-strategy trace as if they were equivalent.
- The same helper can export all-DCT quantization oracle outputs with
  `--dct-only-raw-quant-field-npy`, `--dct-only-aq-map-q24-csv`,
  `--dct-only-ytox-map-npy`,
  `--dct-only-ytob-map-npy`, `--dct-only-quantized-ac-npy`,
  `--dct-only-num-nonzeros-npy`, `--dct-only-num-nonzeros-map-npy`, and
  `--dct-only-quant-dc-npy`. Prefer these artifacts when validating the current
  DCT-only quantization path before adaptive rectangular strategy search exists.
  The AQ CSV contains unsigned Q24 AQ-map and inverse-global-scale inputs plus
  reference and fixed-converted raw-quant bytes; use it for the standalone
  prepared AQ trace seam, not as evidence that upstream AQ heuristics are RTL.
- `--dct-only-ac-metadata-tokens-npy` exports libjxl-tiny AC-metadata tokens
  for the default all-DCT strategy using prepared raw-quant and CFL maps. Use
  this oracle for `FramePreparedAcMetadataTokenTraceStage` instead of comparing
  against fixed raw-quant/scalar-CFL metadata.
- `--dct-only-dc-tokens-npy` and `--dct-only-ac-tokens-npy` export the matching
  adaptive all-DCT logical token streams. Pair them with
  `--dct-only-ac-metadata-tokens-npy` and `--default-ac-strategy-npy` when
  comparing future RTL token traces against libjxl-tiny's all-8x8-DCT path.
- `--dct-only-prepared-blocks-json` exports the prepared-block interface for
  `DctOnlyQuantizeBlock`: Q16 coefficients by default
  (`inputs.coefficient_fraction_bits = 16`, `inputs.coefficients_q`),
  quant/scaling/CFL inputs, image block geometry, 64x64 tile geometry, and
  libjxl-tiny expected quantized AC/DC/nonzero results. The converter still
  accepts legacy `coefficients_q12`, but new fixtures should use the explicit
  fraction-bit field and tile fields. When per-block `block_x`/`block_y` and
  `tile_x`/`tile_y` fields are present, the converter validates them against
  raster order and the 64x64 CFL tile grid before generating stream artifacts.
- `tools/hjxl_prepared_blocks.py --prepared-json ... --input-csv ...
  --input-stream-csv ... --axi-lite-csv ... --manifest-json ...
  --expected-trace-csv ...` converts prepared-block JSON fixtures into
  simulator input rows for `FramePreparedDctOnlyQuantizeTraceStage`, packed
  prepared-DCT stream input rows, shared AXI-Lite control writes, a replay
  manifest, and expected quantization trace rows. It validates raster block
  order, three X/Y/B coefficient channels, scalar input fields, expected output
  shapes, and declared nonzero counts against expected coefficient data.
  Prepared-block JSON integer fields must be actual integers; floating-point JSON
  numbers are rejected instead of being truncated. It also range-checks fields
  that pack into narrower RTL inputs: `quant` is `uint8`, `scaleQ16` is
  `uint16`, CFL multipliers are signed 8-bit, and coefficients are signed
  32-bit. Explicit `--flags` values accept decimal or `0x` syntax but must fit
  the 32-bit AXI-Lite register; `--fixed-raw-quant` must fit the 8-bit RTL
  field. The
  `--validate-manifest` mode checks saved prepared-DCT stream/control bundles
  before replay. Use `--target-variant estimated-cfl` when the prepared stream
  payload should target `HjxlPreparedCflDctAxiStreamCore`,
  `HjxlPreparedCflDctAxiLiteStreamCore`, or `HjxlKv260PreparedCflDctTop`
  instead of the default direct prepared-DCT target. Validators should reject
  contradictory target shell names for a selected variant.
- `tools/hjxl_manifest_header.py --manifest-json ... --header ...` consumes
  RGB or prepared-DCT stream/control manifests and writes a C header containing
  target interface/variant/shell macros, stream word count, input data width, stream
  byte counts, input `TKEEP` mask, input keep-enforcement flag, frame
  block/tile geometry macros, supported-distance Q8 constants, trace packing
  shift/mask and byte-offset macros, capture width macros, trace `TKEEP` mask,
  an inline little-endian trace decoder returning
  `*_stage_trace_t` with portable sign extension for the signed trace value,
  AXI-Lite register offsets, status bits, AXI-Lite address/data/strobe width
  macros, and the ordered config-write table. Keep it
  manifest-driven so host code does not duplicate register constants by hand.
  The generated header includes C11/C++ static assertions for stream byte count,
  input `TKEEP` mask, AXI-Lite strobe width, trace field shifts/byte offsets,
  supported trace value width, trace byte count, trace `TKEEP` mask, and
  AXI-Lite write-table length; preserve those checks when changing the host handoff
  format. Malformed manifest integer fields should fail with field-specific
  diagnostics before header generation.
- `tools/hjxl_stream_buffer.py --manifest-json ... --stream-bin ...
  --last-bin ...` consumes RGB or prepared-DCT manifests and writes
  little-endian stream payload bytes plus an optional one-byte-per-word TLAST
  sidecar. Use it for deterministic host/DMA replay fixtures instead of parsing
  CSVs inside host code. Malformed manifest integer fields such as
  `stream.word_count` and RGB `stream.pixel_bits` should fail with
  field-specific diagnostics before payload generation; floating-point JSON
  numbers should be rejected instead of truncated.
- `tools/hjxl_host_bundle.py --manifest-json ... --output-dir ... --name ...`
  is the preferred one-shot handoff command. It writes the C header, stream
  payload, optional TLAST sidecar, and a `*-bundle.json` artifact index from the
  same manifest. It also copies a bundle-local manifest plus replay
  stream/control CSVs and records relative artifact paths so the bundle
  directory can move as a unit. Relative paths in bundle indexes, bundle-local
  manifests, and saved replay plans must resolve against the containing
  index/manifest/plan directory before falling back to cwd-relative legacy
  lookup. The index's `target` block is the explicit host
  contract: RGB manifests target `HjxlAxiLiteStreamCore`, while prepared-DCT
  manifests target `HjxlPreparedDctAxiLiteStreamCore` and the current
  `HjxlKv260PreparedDctTop`. Its `distance` block records supported Q8
  distances and the fallback Q8 value. Its `stream.coefficient_fraction_bits`
  records the prepared-DCT coefficient scale for prepared bundles. Its
  `stream.input_keep_mask` records the full-byte AXI-stream keep mask for one
  input payload word. The index's `stream.byte_count` is the intended host/DMA
  transfer byte count for the input payload. Its `trace` block records the
  packed trace geometry, field shifts/masks, byte offsets, trace `TKEEP` mask,
  and default capture word size. Its `axi_lite` block records the control-plane
  address/data/strobe widths and programmed write count, and its SHA-256
  checksums cover the bundle-local artifacts.
  `--no-last-bin` is valid only when the host
  path derives TLAST from transfer length; bundle validation still checks
  final-TLAST semantics through the stream CSV. Use the lower-level
  header/buffer tools only when tests need to check one artifact in isolation. Run
  `tools/hjxl_host_bundle.py --validate-bundle ...` before host replay; it
  checks the bundle index, runs the format-specific manifest validator, checks
  target metadata, distance metadata, trace metadata, AXI-Lite metadata,
  generated header, stream payload, optional TLAST sidecar, copied AXI-Lite
  control CSV, stream metadata, and AXI-Lite write count against the source
  manifest, then verifies artifact checksums. Malformed bundle metadata integer
  fields should fail with field-specific diagnostics, and malformed
  bundle-local control CSV integer fields should fail with line-specific
  diagnostics; floating-point JSON numbers should be rejected instead of
  truncated. Older bundle
  indexes without `target`, `distance`, `trace`, or `axi_lite` still validate, but
  contradictory metadata should be treated as a hard error. New frame metadata
  includes the 64x64 `x_tiles`/`y_tiles`/`tile_count` grid used by CFL tile
  maps; older frame records that omit those tile fields remain valid as long as
  their present fields are not contradictory.
  `tools/hjxl_host_bundle.py --describe-bundle ...` validates the bundle and
  prints `hjxl.host_replay_plan.v1` JSON with bundle-relative and absolute resolved
  stream payload paths, diagnostic stream/control CSV paths, DMA byte count,
  input `TKEEP` mask, optional TLAST sidecar paths, prepared-DCT coefficient
  fractional bits when applicable, target interface metadata, AXI-Lite
  address/data/strobe geometry, ordered AXI-Lite writes, status bits, frame
  dimensions, padded 8x8 block grid, and 64x64 tile grid,
  supported-distance Q8 values plus
  fallback distance, trace packing geometry including field shifts/masks and
  byte offsets, trace `TKEEP` mask, default capture word bytes, and artifact
  checksums.
  `bundle_index_resolved` is the canonical bundle-index path for host scripts.
  Use that shape for host bring-up scripts before writing platform-specific
  drivers. Add `--replay-plan-json ...` during bundle generation or
  `--describe-bundle` when a host script needs a stable replay-plan file
  instead of stdout. Run
  `tools/hjxl_host_bundle.py --validate-replay-plan ...` before consuming a
  saved plan; it regenerates the plan from the referenced bundle index and
  fails if the saved file is stale. Malformed replay-plan numeric fields should
  fail with field-specific diagnostics before replay, and floating-point JSON
  numbers should be rejected instead of truncated. When present,
  `bundle_index_resolved` is
  used for validation so saved plans can live outside the bundle directory;
  older plans without it resolve `bundle_index` relative to the saved plan file,
  with cwd-relative lookup only as a legacy fallback. Older v1 replay plans
  without `target`, `frame`, `status_bits`, `trace`, or AXI-Lite
  geometry/register-map fields still validate against the regenerated bundle
  description.
- Use `tools/hjxl_replay_capture.py --replay-plan-json ... --preflight-only
  ...` before DMA capture exists when a host script needs to validate the saved
  plan, expectation flags, and optional `hjxl.replay_preflight_summary.v1`
  output without requiring trace data. That summary includes replay artifact
  paths, resolved payload paths, and SHA-256 checksum metadata so host logs can
  identify the exact bundle inputs validated before capture.
- `tools/hjxl_replay_capture.py --replay-plan-json ... --stream-bin ...
  --last-bin ... --codestream-bin ... --expect-codestream-bin ...` is the
  preferred post-DMA capture checker for host bring-up. It validates the saved
  replay plan before use, derives width, height, requested distance, and
  hardware-effective distance from the ordered AXI-Lite writes, then assembles
  captured token traces through the same libjxl-tiny-backed path as
  `tools/hjxl_trace_to_codestream.py`. Unsupported `distanceQ8` values assemble
  with the RTL's distance-1 fallback by default; use
  `--require-supported-distance` when a host script should reject that fallback.
  Binary capture input defaults to the plan's trace metadata: 16-byte words for
  `HjxlKv260PreparedDctTop`'s 128-bit `m_axis_trace_tdata` today. Pass
  `--stream-word-bytes 11` for raw packed 88-bit trace dumps. Use
  `--expect-target-interface`, `--expect-target-variant`,
  `--expect-target-stream-shell`, `--expect-target-controlled-shell`, and
  `--expect-target-kv260-top` when a host script must reject the wrong replay
  target before capture assembly. Use `--expect-target-no-kv260-top` when an
  RGB replay plan should not name a KV260 wrapper top. Use
  `--expect-target-input-stream` and `--expect-target-input-keep-enforced` when
  the DMA setup must also reject the wrong input payload contract or input
  `TKEEP` enforcement policy. Use the variant and stream-shell guards to
  distinguish direct prepared-DCT and estimated-CFL prepared-DCT bundles before
  accepting capture data. Use
  `--expect-frame-xsize`,
  `--expect-frame-ysize`, `--expect-frame-x-blocks`,
  `--expect-frame-y-blocks`, `--expect-frame-padded-xsize`,
  `--expect-frame-padded-ysize`, `--expect-frame-block-count`,
  `--expect-frame-x-tiles`, `--expect-frame-y-tiles`, and
  `--expect-frame-tile-count` when a host runner must reject a replay plan for
  the wrong image, padded block grid, or CFL tile grid.
  Use `--expect-status-protocol-error-bit`, `--expect-status-busy-bit`,
  `--expect-status-overflow-bit`, `--expect-status-unsupported-distance-bit`,
  and `--expect-control-clear-protocol-error-bit` when a host runner must
  reject status/control bit positions that do not match its poll/clear logic.
  Use `--expect-trace-stage-bits`,
  `--expect-trace-group-bits`, `--expect-trace-index-bits`,
  `--expect-trace-value-bits`, `--expect-trace-stage-shift`,
  `--expect-trace-group-shift`, `--expect-trace-index-shift`,
  `--expect-trace-value-shift`, `--expect-trace-stage-byte-offset`,
  `--expect-trace-group-byte-offset`, `--expect-trace-index-byte-offset`,
  `--expect-trace-value-byte-offset`, `--expect-trace-stage-mask`,
  `--expect-trace-group-mask`, `--expect-trace-index-mask`,
  `--expect-trace-value-mask`, `--expect-trace-packed-bits`,
  `--expect-trace-packed-bytes`, `--expect-trace-keep-mask`, and
  `--expect-capture-word-bytes` when a host runner must reject the wrong trace
  field layout, output `TKEEP`, or capture word geometry before assembly. Use
  `--expect-input-word-count`,
  `--expect-input-data-bits`, `--expect-input-word-bytes`,
  `--expect-input-keep-mask`, `--expect-input-byte-count`, and
  `--expect-input-coefficient-fraction-bits` when a host runner must reject the
  wrong replay input geometry, bit width, AXI-stream keep mask, or prepared-DCT
  coefficient scale before capture assembly. Use `--expect-axi-lite-addr-bits`,
  `--expect-axi-lite-data-bits`, `--expect-axi-lite-strb-bits`, and
  `--expect-axi-lite-write-count` to reject mismatched control-plane geometry.
  Use `--expect-reg-status-control`, `--expect-reg-xsize`,
  `--expect-reg-ysize`, `--expect-reg-distance-q8`,
  `--expect-reg-fixed-point-scale`, `--expect-reg-fixed-inv-qac-q16`,
  `--expect-reg-fixed-raw-quant`, `--expect-reg-flags`,
  `--expect-reg-fixed-ytox`, and `--expect-reg-fixed-ytob` to reject AXI-Lite
  register offsets that do not match the host runner. Hex-capable
  numeric expectation flags should reject malformed values with
  argument-specific diagnostics.
  Older v1 replay plans without `frame`, `status_bits`, or `trace` blocks still
  validate, and this helper falls back to the current group/value width and
  KV260 capture defaults when trace metadata is missing. Use `--summary-json`
  to write a `hjxl.capture_summary.v1` report with replay target and
  trace-route metadata, frame metadata, status/control bit positions, replay
  input-stream metadata, trace field shifts/masks and byte offsets, AXI-Lite
  geometry and register map, replay artifact paths, SHA-256 checksum metadata,
  capture geometry, token counts, and byte counts next to captured artifacts.
  Prepared summaries
  carry `input_stream.coefficient_fraction_bits` when the replay plan provides
  it.
- `python3 tools/hjxl_host_metadata_smoke.py` is the quick Python-only
  regression for RGB and prepared-DCT host metadata. Run it before or after
  touching manifest/header/bundle/replay-capture code to verify Q16 prepared
  coefficient-scale propagation, input data width, input `TKEEP` mask, trace
  field shifts/masks, byte offsets, trace `TKEEP` mask, AXI-Lite metadata, DMA
  byte count, direct prepared-DCT and estimated-CFL target variant metadata,
  signed fixed-CFL AXI-Lite writes (`fixed_ytox = -7`, `fixed_ytob = 11`),
  generated C/C++ trace decoder syntax and signed-value behavior, legacy
  metadata tolerance, and replay-capture expectation guards without invoking
  `sbt`.
- `tools/hjxl_quant_trace_to_prepared_tokens.py --trace-csv ... --width ...
  --height ... --dc-csv ... --ac-csv ... --prepared-json ...` converts
  quantization traces into prepared-token simulator CSVs and, optionally, the
  same prepared-token JSON fixture format consumed by
  `tools/hjxl_prepared_token_inputs.py`. It requires complete `QuantDc`,
  `QuantizedAc`, and `NumNonzeros` records for every raster block, rejects
  `QuantDc`/`QuantizedAc` values outside the signed-32 `StageTrace.value` range,
  emits DC samples in token order (Y plane, X plane, then B plane), and emits AC
  block rows in raster block order with X/Y/B channel rows plus frame/tile/block
  metadata in the JSON path.
- `--distance-params-json` exports libjxl-tiny distance parameters. Use it to
  update or audit `DistanceParamsLookup` entries. Its `inv_qac_q16` field is
  computed for `--fixed-raw-quant`, which defaults to 5.
- `--fixed-dct-only-dc-tokens-npy`,
  `--fixed-dct-only-ac-metadata-tokens-npy`, and
  `--fixed-dct-only-ac-tokens-npy` export logical `(context, value)` token
  streams for `--fixed-raw-quant` (default 5), `--fixed-ytox`/`--fixed-ytob`
  signed 8-bit scalar CFL maps (default 0), and all-DCT fixtures. Use these to
  cross-check token order and context formulas against libjxl-tiny. For
  nontrivial images, exact value parity still depends on improving the
  fixed-point transform and quantization path. The current host-boundary Scala
  regression uses a prepared-token gradient fixture with nonzero AC
  coefficients, so the exact token path is covered after libjxl-tiny quantized
  DC/AC data has been prepared.
- `--fixed-dct-only-raw-quant-field-npy`, `--fixed-dct-only-ytox-map-npy`, and
  `--fixed-dct-only-ytob-map-npy` export the fixed metadata grids that match the
  current focused `RawQuantField`, `YtoxMap`, and `YtobMap` RTL routes, using
  the same `--fixed-raw-quant`, `--fixed-ytox`, and `--fixed-ytob` values as the
  fixed token oracles. Use these for explicit fixed-route comparator oracles;
  the adaptive `--dct-only-*` metadata
  grids describe libjxl-tiny's all-DCT software path and are not expected to
  match the fixed metadata routes.
- `--fixed-dct-only-prepared-token-inputs-json` exports the prepared DC sample
  stream and prepared AC raster blocks consumed by `FramePreparedTokenTraceStage`
  for the same fixed all-DCT assumptions, including the selected fixed raw-quant
  and scalar CFL metadata. Use it to regenerate combined prepared-token
  trace-driver fixtures.
- `tools/hjxl_prepared_token_inputs.py --prepared-json ... --dc-csv ...
  --ac-csv ...` converts those JSON fixtures into simple simulator input CSV
  streams for `FramePreparedTokenTraceStage`. It requires the exact
  `hjxl.fixed_dct_only_prepared_token_inputs.v1` format string, then validates
  integer-valued image geometry, optional fixed raw-quant and signed 8-bit
  scalar CFL metadata, signed-32 DC samples, AC coordinates, nonzero counts, and
  signed-32 coefficients; the expected DC sample count; image block/tile
  geometry when present; contiguous AC block order; optional AC block
  raster/tile coordinates; three X/Y/B channels; nonzero ranges; declared
  nonzero counts against coefficient data; and 64 coefficients per channel.
  Floating-point JSON numbers must be rejected for integer fields instead of
  being truncated before writing outputs.
- `--fixed-dct-only-frame-bin` and `--fixed-dct-only-codestream-bin` serialize
  the fixed logical tokens through libjxl-tiny's entropy optimizer and bitstream
  writer. Treat these as host-side oracle artifacts for the near-term
  hardware/software split, not as evidence that RTL emits final `.jxl` bytes.
- `--dct-only-frame-bin` and `--dct-only-codestream-bin` serialize the adaptive
  all-DCT token oracle using real raw-quant and CFL maps. Use these only after
  checking token-array parity; the Q16 prepared-DCT wrapper is expected to match
  the current oracle fixture exactly, while the RGB-input path still has
  approximate XYB/DCT sources.
- `--token-input-dc-tokens-npy`, `--token-input-ac-metadata-tokens-npy`,
  `--token-input-ac-tokens-npy`, and `--token-input-ac-strategy-npy` let the
  helper assemble frame/codestream bytes from precomputed logical tokens. Use
  this path for future RTL trace dumps before attempting an RTL entropy coder.
  Token arrays must have shape `(n, 2)` with nonnegative `uint32`
  context/value entries, and the AC strategy grid must be a 2D integer array in
  the `uint8` range; the helper rejects malformed artifacts instead of relying
  on NumPy dtype wrapping.
- `tools/hjxl_trace_tokens.py` converts `StageTrace` CSV dumps with
  `stage,group,index,value` columns into those token-input NumPy arrays. Token
  stages require contiguous `group` ordinals and nonnegative `uint32`
  `context,value` pairs; AC strategy traces use `index` as the raster block
  ordinal and require positive image width/height for reshaping. The same tool
  can also extract metadata grids: `--raw-quant-field-npy` writes a `uint8`
  8x8-block grid from `RawQuantField`, while `--ytox-map-npy` and
  `--ytob-map-npy` write `int8` 64x64-tile grids from `YtoxMap` and `YtobMap`.
  Grid outputs must reject non-positive dimensions before writing NumPy
  artifacts.
  Compare those focused fixed-route captures against the fixed metadata oracle
  grids from `tools/hjxl_reference.py`.
- `tools/hjxl_stream_trace.py --stream-csv ... --trace-csv ...` decodes packed
  `HjxlAxiStreamCore` trace captures with `data,last` or `tdata,tlast` columns
  back into `StageTrace` CSV. It also accepts little-endian binary TDATA
  captures with `--stream-bin ... --last-bin ...`; the default binary word size
  is the packed trace width rounded up to bytes (11 bytes today), and
  `--stream-word-bytes` handles wider padded DMA buses. Use
  `--stream-word-bytes 16` for default `HjxlKv260PreparedDctTop` captures. Use
  `--require-final-last` for single-frame captures from all current routes,
  including focused full AC-token captures.
  `StreamTraceToolSpec` covers this helper, its handoff into
  `tools/hjxl_trace_tokens.py`, direct `--stream-csv` and `--stream-bin` token
  extraction, generated host-header compile smokes when `cc` or `c++` is
  available, portable host bundle relocation validation, and the
  `tools/hjxl_trace_to_codestream.py` packed-stream paths. It also covers
  replay-plan-driven capture validation through `tools/hjxl_replay_capture.py`.
- `tools/hjxl_rgb_stream.py --pfm ... --stream-csv ...` converts RGB PFM files
  into the raster `data,last` input stream expected by `HjxlAxiStreamCore` and
  `HjxlAxiLiteStreamCore`. It restores top-to-bottom PFM row order, quantizes
  linear samples to signed Q8 by default, packs R/G/B into consecutive
  `pixelBits` fields, and asserts `last` only on the final pixel. With
  `--axi-lite-csv`, it also emits `address,data,strb` writes for the shared
  AXI-Lite register map, including PFM-derived `xsize`/`ysize` and route flags.
  `--trace-route` records compile-time `HjxlCore(traceRoute = ...)`
  elaboration intent separately from those runtime flags. With
  `--manifest-json`, it records source/image metadata, stream packing,
  generated artifact paths, trace-route metadata, the AXI-Lite register map,
  status/control bits, and config values. Shared AXI-Lite scalar overrides must
  fit the 32-bit control registers, `--fixed-raw-quant` must fit the RTL's
  8-bit raw quant field, and `--fixed-ytox`/`--fixed-ytob` must fit signed 8-bit
  CFL fields. Keep this helper aligned with
  `RgbToXybApprox.InputFractionBits`,
  the stream wrapper's component packing, and `HjxlAxiLiteRegister`. Use
  `tools/hjxl_manifest_header.py` on generated manifests when a C host stub
  needs the matching register constants and config-write table, and
  `tools/hjxl_stream_buffer.py` when it needs replayable stream payload bytes.
  `HjxlAxiStreamCoreSpec` exercises the current path from PFM to RGB stream,
  RTL, and trace decoding, and `HjxlAxiLiteStreamCoreSpec` exercises the same
  path using the generated AXI-Lite CSV to configure the DUT; preserve those
  regressions when changing host input packing or the control register map.
  The stream spec also captures focused raw-quant/CFL metadata routes and feeds
  the packed stream output directly into `tools/hjxl_trace_tokens.py`.
  Host replay preflight can use `--expect-trace-route-name`,
  `--expect-trace-route-stage`, and `--expect-trace-route-focused` to reject a
  bundle for the wrong route shape before capture assembly.
  Prepared-DCT stream manifests use the route name
  `prepared-dct-quantize-token` for the direct prepared-DCT quantize-to-token
  boundary; its route stage is `null` in JSON and `-1` in expectation/header
  macros because it is not a `TraceStage` enum route.
  Use `tools/hjxl_rgb_stream.py --validate-manifest ...` before replaying a
  saved host bundle; it checks stream row count, final-only TLAST, CSV columns,
  AXI-Lite strobes, and register values against the manifest, and malformed
  integer/boolean CSV fields should fail with line-specific diagnostics.
- `tools/hjxl_trace_to_codestream.py --trace-csv ... --width ... --height ...
  --frame-bin ... --codestream-bin ...` is the one-step host assembler for RTL
  token traces. It extracts DC, AC-metadata, AC, and strategy token artifacts
  from `StageTrace` or packed stream CSV inputs, then uses libjxl-tiny
  entropy/bitstream code to write a frame and bare codestream. Use
  `--expect-frame-bin` or `--expect-codestream-bin` for byte-parity checks.
  `TraceToCodestreamToolSpec` covers multi-file trace input, packed stream
  byte-parity input, expected-byte mismatch diagnostics, and malformed trace
  rejection for this CLI.
- `tools/hjxl_compare_tokens.py` compares token-input arrays against oracle
  arrays. It is exact by default for token stream length, contexts, values, and
  AC strategy/raw-quant/CFL metadata grid entries. Use `--max-value-delta` only
  for token-value diagnostics while narrowing fixed-point differences, not for
  final parity claims; it does not relax metadata-grid comparisons. Token
  arrays must contain integer nonnegative `uint32` `context,value` pairs, and
  metadata grids must be integer arrays within their declared `uint8`/`int8`
  ranges; do not rely on NumPy dtype wrapping to hide signedness mistakes.
  `TokenCompareToolSpec` covers this helper; update it when changing comparator
  behavior.
- `FixedDctOnlyTokenAssemblySpec` is the current host-boundary regression. It
  runs the full prepared-token boundary chain: libjxl-tiny prepared-token JSON,
  simulator input CSVs, `FramePreparedTokenTraceStage`, StageTrace-to-token
  conversion, host frame/codestream assembly through `hjxl_reference.py`, and
  the one-step `hjxl_trace_to_codestream.py` path, using nonzero scalar fixed
  CFL metadata. Do not treat this as full RGB-input token parity:
  `FrameDctOnlyDcTokenTraceStage` and
  `FrameDctOnlyAcTokenTraceStage` still go through the approximate fixed-point
  RGB/XYB/DCT path, so closing that parity gap remains separate work.
- `FramePreparedDctOnlyQuantizeTraceStageSpec` covers the prepared-DCT
  quantization scheduler with libjxl-tiny prepared-block fixtures. It enforces
  exact stage/group/index/value comparison at the Q16 prepared-coefficient
  boundary, then verifies the observed RTL quantization trace converts into
  prepared-token DC/AC CSV inputs and a prepared-token JSON fixture without
  data loss or reordering. The JSON path is round-tripped through
  `tools/hjxl_prepared_token_inputs.py` before driving
  `FramePreparedTokenTraceStage` through the full fixed all-DCT logical trace
  stream. It also checks `FramePreparedDctOnlyQuantizeTokenTraceStage` against
  that staged handoff and against libjxl-tiny token-array oracles for DC,
  AC-metadata, AC, and strategy streams, including final-beat `traceLast`
  framing. It converts the direct wrapper's token `StageTrace` rows with
  `tools/hjxl_trace_tokens.py`, compares them with
  `tools/hjxl_compare_tokens.py`, and verifies the host assembler output
  matches the direct libjxl-tiny DCT-only frame and bare JPEG XL codestream
  bytes. It also feeds the same direct-wrapper trace CSV to
  `tools/hjxl_trace_to_codestream.py`; keep that one-step byte check when
  changing the prepared-DCT token wrapper or host trace format.
- `FramePreparedAcMetadataTokenTraceStageSpec` covers the standalone prepared
  AC-metadata scheduler with two-tile fixtures, including a libjxl-tiny
  oracle-backed 72x8 all-DCT case. Keep it when changing CFL residual
  prediction or raw-quant residual context logic; the integrated prepared-DCT
  fixture is only single-tile today.
- `FramePreparedCflAcMetadataTokenTraceStageSpec` covers the estimated-CFL
  metadata path with horizontal 72x8, vertical 8x72, and two-dimensional 72x72
  tile grids. Keep the 72x72 case when changing CFL-map prediction; it is the
  focused regression for the northwest tile-neighbor branch.
- `HjxlCore` currently exposes padded-input, XYB, raw-DCT, adaptive-raw-quant/
  estimated-CFL quantized-DCT, fixed-parameter DC-token, adaptive-raw-quant/
  estimated-CFL AC-metadata-token, or default AC-strategy, AQ-contrast, focused
  AQ-fuzzy-erosion, and focused AQ-strategy-mask trace streams.
  `FrameConfig.tokenSelect` chooses
  `Dc`, `AcMetadata`, or `AcTokens` for token routes; the reserved
  `AqContrast` value selects the RGB contrast extension when XYB and
  quantization are enabled without DCT/tokenization. The full AC-token stream
  is available only in the focused `traceRoute = TraceStage.AcTokens` core
  elaboration or through `HjxlAcTokenCore`, while the AC nonzero-only stream
  remains a standalone trace stage.
  `HjxlCore(traceRoute = ...)` can restrict elaboration to one compile-time
  trace route while preserving the same public IO and runtime config contract
  for that route. Use this form in focused tests; compiling the all-route shell
  under Verilator is expensive as the frame schedulers grow.
  `HjxlCoreSpec` route-level simulator checks should be run with ScalaTest
  `-z` filters for the route being changed; the DC/quant/AC token routes pull in
  the RGB-to-XYB/DCT frame schedulers and can make the full class slow. Keep
  detailed token behavior in the standalone scheduler specs and use
  `HjxlCoreRouteElaborationSpec` for inclusion/port-surface coverage.
  `HjxlCoreRouteElaborationSpec` guards that the default all-route shell omits
  the heavy full-AC-token scheduler while `traceRoute = TraceStage.AcTokens`
  includes it behind the normal core IO.
  `enableDct && enableQuant && enableTokenize && tokenSelect=Dc` selects the
  DC-token frame trace; `enableDct && enableQuant` selects the adaptive-
  raw-quant DCT frame trace; `enableDct` alone selects raw DCT;
  `enableQuant && enableTokenize && tokenSelect=AcMetadata` selects adaptive
  raw-quant AC metadata with estimated RGB CFL; `enableXyb && enableQuant &&
  tokenSelect=AqContrast` without DCT or
  tokenization selects the AQ contrast grid in the all-route core, the fuzzy
  erosion grid when `traceRoute = TraceStage.AqFuzzyErosion`, and the strategy
  mask when `traceRoute = TraceStage.AqStrategyMask`, or the signed modulation
  seed when `traceRoute = TraceStage.AqNonlinearMask`, or that seed after Y HF
  modulation when `traceRoute = TraceStage.AqHfModulation`, or the cumulative
  red/blue result when `traceRoute = TraceStage.AqColorModulation`, or the
  cumulative log-domain result when
  `traceRoute = TraceStage.AqGammaModulation`, or the completed unsigned-Q24
  map when `traceRoute = TraceStage.AqFinalMap`; a focused
  `TraceStage.RawQuantField` build emits strategy-adjusted adaptive bytes at
  zero `fixedRawQuant` and the explicit fixed byte otherwise;
  `enableQuant` alone selects fixed AC strategy metadata in the default
  all-route shell, while a focused `TraceStage.AcStrategy` build selects the
  adaptive trace-only map. Do not describe either as a complete encoder yet;
  these are traceable pipeline slices.

## Verification Commands

Core Chisel checks:

```sh
sbt test
./mill hjxl.test
```

When oracle tooling exists in this repo, run it with:

```sh
LIBJXL_TINY=/Users/yunhocho/GitHub/libjxl-tiny sbt test
```

CI sets `HJXL_REPO_ROOT`, installs Verilator and `python3-numpy`, checks out the
libjxl-tiny Python-port commit `07f2dfe11a1a9f621052e75db5feffb0f58f44bd` from
`https://github.com/yunho-c/libjxl-tiny.git` into `$LIBJXL_TINY`, runs Python
helper `py_compile`, then runs `sbt test` and `./mill _.test`. Use
`HJXL_REPO_ROOT=$PWD` for local Mill runs that invoke Python helper scripts.
Do not add Python oracle/tool tests that only pass under an undeclared local
package or local checkout; either keep them within those dependencies or update
the CI setup step with the required package/source.

For libjxl-tiny reference exploration, use disposable output under
`build-codex/` in the libjxl-tiny checkout.
