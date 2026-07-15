# HJXL Architecture

`hjxl` targets the libjxl-tiny lossy VarDCT encoder path, not the full JPEG XL
specification. The first FPGA-facing implementation uses host software for file
I/O and final bitstream assembly while Chisel RTL accelerates deterministic
image, transform, quantization, and token stages.

## Reference Pipeline

The software oracle is `~/GitHub/libjxl-tiny`. Its Python port describes the
pipeline in a form suitable for RTL partitioning:

1. Accept channel-first linear RGB input.
2. Partition into DC groups, AC groups, 256x64 stripes, 64x64 tiles, and 8x8
   blocks.
3. Pad RGB to whole blocks and convert to XYB.
4. Compute adaptive quantization, chroma-from-luma maps, and AC strategy.
5. Compute DCTs, quantized DC/AC coefficients, and nonzero maps.
6. Emit DC, AC-metadata, and AC coefficient tokens.
7. Optimize entropy tables and serialize frame sections into a bare JXL
   codestream.

Early RTL work should stop at traceable stage outputs and tokens. Every current
trace scheduler also emits a `traceLast` sideband on its final frame record,
and the AXI-stream shell maps that sideband to output TLAST for capture.
Software can consume those tokens to produce final codestream bytes until
entropy coding and bit assembly are worth moving into hardware.

## Hardware Contracts

Internal Chisel modules should use `Decoupled` streams. The first public-facing
contract is intentionally simple:

- `FrameConfig` carries image size, distance, fixed-point scale, and feature
  flags. `tokenSelect` chooses `Dc`, `AcMetadata`, or `AcTokens` for token
  traces; the reserved value `AqContrast` selects the RGB contrast extension
  when XYB and quantization are enabled without DCT/tokenization. A focused
  `traceRoute = TraceStage.AqFuzzyErosion` build uses the same runtime flags to
  emit the next AQ intermediate, and `TraceStage.AqStrategyMask` continues to
  the mask consumed by strategy scoring. The parallel focused
  `TraceStage.AqNonlinearMask` route emits the signed-Q24 `_compute_mask`
  modulation seed; `TraceStage.AqHfModulation` continues through the first
  8x8-block Y-detail term, and `TraceStage.AqColorModulation` then applies the
  distance-dependent red/blue coverage term. `TraceStage.AqGammaModulation`
  adds the inverse-response average and log term; `TraceStage.AqFinalMap`
  applies the final exponent/scale/damping operation. A focused
  `TraceStage.RawQuantField` build uses that completed RGB path, runs the
  adaptive strategy search, and emits its rectangle-adjusted byte field when
  `fixedRawQuant` is zero; a nonzero uniform override remains a cheap bypass. The full
  AC-token route is only instantiated when the core is elaborated with
  `traceRoute = TraceStage.AcTokens`.
- `RgbPixel` carries fixed-point linear RGB samples and image coordinates.
- `StageTrace` carries a stage id, group id, element index, and value.
- `HjxlCore` is the integration shell where stage modules will be wired.
- `HjxlAxiStreamCore` is the first hardware-facing stream shell. It accepts
  raster RGB samples without explicit coordinates, generates `RgbPixel.x/y`,
  and packs `StageTrace` rows into stream words.
- `HjxlAxiLiteStreamCore` wraps the stream shell with a small 32-bit AXI-Lite
  control/status plane for image size, fixed-path quantization knobs, route
  flags, token selection, and protocol-error recovery.
- `HjxlPreparedDctAxiStreamCore` is the stream-shaped top for the most
  parity-ready prepared-DCT quantize-to-token path. It accepts packed prepared
  block records on a 32-bit AXI4-Stream-shaped input and emits packed token
  `StageTrace` rows.
- `HjxlPreparedDctAxiLiteStreamCore` adds the same 32-bit AXI-Lite control
  plane to that prepared-DCT stream top, giving the current strongest
  RTL-to-codestream boundary a KV260-style control/data shape. It validates the
  full-word input keep mask before forwarding beats into the prepared-word
  parser.
- `HjxlKv260PreparedDctTop` wraps the prepared-DCT AXI-Lite stream core with
  flat Vivado-style `ap_clk`/`ap_rst_n`, `s_axi_control_*`, `s_axis_input_*`,
  and 128-bit `m_axis_trace_*` ports. `s_axis_input_tkeep` is exposed for AXIS
  compatibility and must be `0xF` for the current full-word prepared stream;
  partial masks are consumed and reported through status/control protocol
  error. `m_axis_trace_tkeep` marks the low 11 bytes containing the packed trace
  word.

Use the internal contracts for simulation and trace extraction, and use the
KV260 prepared-DCT top as the current Vivado-facing top-level shape.

Stable cross-language values in these contracts are defined once in
`abi/hjxl_abi.json` and generated into `HjxlAbiGenerated.scala` and
`hjxl_abi_generated.py`. Existing Scala public objects alias the generated
values, while RGB/prepared manifest generation, trace conversion, token
extraction, and C-header generation import the Python binding. CI runs
`tools/hjxl_generate_abi.py --check` so a schema change cannot land with stale
language bindings. Target descriptions and manifest-specific C tables remain
owned by the host tools for now.

## Implemented RTL Slices

- `FramePadTraceStage` buffers a bounded simulation frame and emits
  `input_padded` trace samples in libjxl-tiny channel-first order. It repeats
  the last valid column across the padded right edge and then repeats the last
  padded row across the padded bottom edge.
- `FrameXybTraceStage` reuses the same bounded frame buffering and padding
  policy, then emits approximate Q12 XYB samples in channel-first order.
- `HjxlCore` currently routes input RGB samples into `FramePadTraceStage` by
  default, into `FrameXybTraceStage` when `FrameConfig.enableXyb` is true, and
  into `FrameDct8x8TraceStage` when `FrameConfig.enableDct` is true. When both
  `enableDct` and `enableQuant` are true, it routes into
  `FrameAqCflDctOnlyQuantizeTraceStage`; when `enableTokenize` is also true, it
  uses `tokenSelect` to route into `FrameDctOnlyDcTokenTraceStage`,
  `FrameAqCflDctOnlyAcMetadataTokenTraceStage`, or the focused
  `FrameAqCflDctOnlyQuantizeTokenTraceStage` AC stream. The quantized, metadata,
  and focused AC-token routes consume the completed adaptive raw-quant field
  and RGB-derived tile CFL; the DC-only route remains on the earlier fixed
  scheduler. Focused Y-to-X/Y-to-B routes expose the same estimated maps, while
  the default all-route core omits those expensive diagnostic map schedulers. When
  only `enableQuant` is true, the default all-route shell routes into the
  lightweight fixed `FrameAcStrategyTraceStage`; a compile-time focused
  `TraceStage.AcStrategy` build instead routes into
  `FrameAqAcStrategyTraceStage`. When XYB and quantization are
  enabled without DCT/tokenization, `tokenSelect=AqContrast` instead routes the
  global pre-erosion contrast grid; a focused `TraceStage.AqFuzzyErosion` build
  emits the erosion-derived block grid and a focused `TraceStage.AqStrategyMask`
  build emits its reciprocal strategy mask. A focused
  `TraceStage.AqNonlinearMask` build emits the separate signed-Q24 log-domain
  seed that starts final AQ-map modulation, while a focused
  `TraceStage.AqHfModulation` build emits that seed after the internal Y-edge
  term and a focused `TraceStage.AqColorModulation` build continues through
  red/blue coverage. A focused `TraceStage.AqGammaModulation` build continues
  through the inverse-response average and log term, and a focused
  `TraceStage.AqFinalMap` build completes the unsigned-Q24 map. The focused
  raw-quant build converts that map unless a nonzero fixed override is selected.
  The default selector deliberately retains fixed AC strategy so the new
  full-frame search does not inflate ordinary all-route elaboration.
  The optional compile-time `traceRoute` constructor argument restricts
  elaboration to one route for focused simulation while keeping the same public
  IO shape. Use it for tests and route-specific experiments; the default
  constructor still instantiates the all-route shell.
- `tools/hjxl_reference.py --input-padded-npy ...` writes the matching
  libjxl-tiny padded-input oracle artifact for small fixtures.
- `RgbToXybApprox` is a standalone Q8-to-Q12 fixed-point approximation of
  libjxl-tiny XYB conversion. It performs signed RGB mixing with Q26 matrix
  coefficients, adds the opsin bias before clamping like the reference, keeps
  Q24 mixed absorbance, and uses `CbrtApproxQ12` to normalize by powers of
  eight before interpolating a 225-entry Q5 table over `[1, 8)`. This removes
  the former source-clamp mismatch and absorbance saturation above roughly 2.0
  while keeping the cube-root table smaller than the former range-limited
  table. It remains an approximation, but
  `tools/hjxl_reference.py --xyb-q12-csv ...` now provides an independent
  fixed-point oracle: three fixture families stay within two Q12 units, and a
  deterministic 100,000-vector full signed-16/Q8 model sweep is bounded to five
  units. `--xyb-npy ...` remains the floating-point array export.
- `AqGammaRatioQ16`, `UnsignedIntegerSquareRoot`, and `AqContrastPixel` form
  the first image-dependent adaptive-quantization arithmetic boundary. The
  gamma ratio uses exact Q12 entries through the steep near-zero region,
  interpolates a coarser table through 1.0, and uses a bounded linear HDR tail;
  the masking expression uses fixed-point squares plus a 74-bit integer square
  root. `FrameAqContrastTraceStage` converts incoming RGB once, stores Q12 X/Y,
  and schedules one shared contrast datapath across global 4x4 padded-pixel
  cells. It emits positive Q16 `AqContrast` rows with raster cell ordinal in
  `trace.index`. The all-route RGB core selects this extension only when
  `enableXyb && enableQuant`, `tokenSelect=AqContrast`, and DCT/tokenization are
  disabled, preserving the previous default-selector AC-strategy route.
  Keeping a global grid avoids duplicating 4-pixel tile halos.
  `tools/hjxl_reference.py --aq-contrast-q16-csv ...` exports the matching
  native-float and Q12-input libjxl-tiny oracle values; the exporter checks its
  native reconstruction exactly against the `pre_erosion` array consumed by
  the full reference AQ function. Five RGB fixture families are bounded to two
  percent at this seam, and a 65x1 regression
  proves that the global cell grid and carry-safe 72-pixel padding cross a
  64-pixel tile boundary correctly. This is not yet the eroded AQ state.
- `AqFuzzyErosionSample` forms the center plus four-minimum weighted numerator
  for one clamped 3x3 Q16 contrast neighborhood. The prepared frame scheduler
  buffers the global row-major contrast grid, visits the four quarter-resolution
  cells belonging to each padded 8x8 block, accumulates their numerators, and
  performs one nearest divide by 20. This retains enough precision for the
  emitted block value to stay within one Q16 LSB of the native reference.
  `FrameAqFuzzyErosionTraceStage` composes that prepared scheduler after
  `FrameAqContrastTraceStage`, reusing the existing RGB-to-XYB and contrast
  hardware. It is intentionally a focused `TraceStage.AqFuzzyErosion` route;
  the default all-route core retains `AqContrast` behavior for ABI compatibility.
  Five RGB families remain within two percent, a 65x1 case crosses the
  horizontal 64-pixel tile edge, and prepared 65x65 input exercises the full
  18x18-cell/9x9-block grid padded to 72x72.
- `AqStrategyMaskReciprocal` computes the block mask used only by AC-strategy
  scoring: `1 / (fuzzy_erosion + 0.001)`. The Q16 boundary represents 0.001 as
  the exact rational 1/1000 and uses a 42-cycle restoring divider rather than a
  combinational division operator. `FramePreparedAqStrategyMaskTraceStage`
  provides a one-block-at-a-time ready/valid boundary, while
  `FrameAqStrategyMaskTraceStage` composes it after the RGB fuzzy-erosion path.
  The oracle checks the full-frame reconstruction against both the returned
  reference mask and the stitched 64x64 calls used by the real encoder; five
  RGB families stay within two percent and prepared fixtures match Q16 exactly.
  The nonlinear/final-map pipeline also carries this exact mask alongside the
  same block context into `FrameAqAcStrategyTraceStage`; the standalone focused
  mask trace and the default fixed `FrameAcStrategyTraceStage` remain available
  as narrower diagnostics.
- `AqNonlinearMaskEvaluator` implements libjxl-tiny's separate `_compute_mask`
  branch from positive Q16 fuzzy erosion to a signed-Q24 log-domain modulation
  seed. Q24 constants preserve the reference's three rational terms, and one
  56-cycle restoring divider is reused sequentially for an exact 174-cycle
  evaluator latency without a combinational division operator.
  `FramePreparedAqNonlinearMaskTraceStage` supplies the one-block-at-a-time
  frame contract and `FrameAqNonlinearMaskTraceStage` composes it after the
  existing RGB contrast/erosion path. The oracle independently reconstructs
  the float32 private function, checks full-frame versus stitched 64x64 calls,
  and supplies the fixed model.
- `AqHfModulationBlock` consumes one signed-Q24 nonlinear seed plus one 8x8
  signed-Q12 Y block. It visits one pixel per cycle, accumulates the 112
  internal horizontal/vertical absolute differences, and subtracts their Q32-
  coefficient-weighted magnitude after an exact 64-cycle latency. The prepared
  frame stage provides raster/config/backpressure semantics.
  `FrameAqModulationBlockStage` captures the passive accepted-XYB tap exposed by
  the existing contrast stage, edge-replicates partial blocks, and pairs them
  with nonlinear seeds. It holds the frame boundary until the downstream final
  block retires. The focused HF wrapper requests Y-only storage and emits
  signed-Q24 `AqHfModulation` rows; wider cumulative stages reuse the same
  scheduler with X/Y/B storage. The oracle independently reconstructs the
  private float32 function and proves full-frame equality with stitched 64x64
  calls.
- `AqColorModulationBlock` consumes a cumulative HF seed, Q8 distance, and one
  signed-Q12 XYB block. It scans one pixel per cycle for exactly 64 cycles,
  accumulates red and blue ramp coverage in Q16, caps both sums, and applies
  pre-folded Q24 baseline/coverage coefficients without a runtime divider.
  Distance above 4 returns the input seed exactly. The prepared frame wrapper
  locks distance and raster order. `AqHfColorModulationBlockPipeline` pipelines
  HF and color while retaining X/Y for the next operation;
  `FrameAqColorModulationTraceStage` composes it with the shared frame scheduler
  and one RGB-to-XYB converter. Its oracle matches the private float32 function
  and stitched tile calls.
- `AqGammaModulationBlock` consumes the cumulative color seed and signed-Q12
  X/Y block. It alternates the two per-pixel inverse gamma responses for an
  exact 128-cycle latency, using exact near-zero Q12 lookup entries,
  coarse/HDR interpolation, and a normalized Q20 `fast_log2f` table. Prepared
  response values are explicitly clamped to the RGB converter's supported
  `[0, 8]` range and the datapath has no runtime divider. The prepared frame
  wrapper preserves raster/config/backpressure semantics;
  `FrameAqGammaModulationTraceStage` composes the shared scheduler and reusable
  HF/color/gamma pipeline with one converter. Its oracle matches the private
  float32 function and stitched tile calls.
- `AqFastExpQ24` converts the cumulative gamma seed through a normalized
  257-entry fractional `fast_pow2f` table with explicit exponent saturation.
  `AqFinalModulationBlock` applies distance-derived unsigned-Q24 scale, damping,
  base-level addition, and saturation. `FrameAqFinalMapPipeline` combines that
  operation with the shared cumulative scheduler, applies the distance-1
  fallback before every per-block operation, and retains the inverse global AC
  scale for `AqMapToRawQuant`. `FrameAqFinalMapTraceStage` emits the completed
  unsigned-Q24 map as `AqFinalMap`; `FrameAqRawQuantTraceStage` emits the exact
  map-to-byte conversion before strategy adjustment. The narrow
  `FrameRawQuantFieldTraceStage` selects this pre-strategy adaptive diagnostic
  when `fixedRawQuant` is zero and the retained fixed path otherwise.
- `DistanceParamsLookup` is the first hardware boundary for libjxl-tiny's
  distance-derived scalar parameters. It supports common Q8 distances
  `64`, `128`, `256`, `512`, `1024`, and `2048`; unsupported values currently
  fall back to distance 1, and AXI-Lite controlled shells report that fallback
  at status bit 3. It emits the global AC scale, quantized DC scale, fixed
  raw-quant-5 AC reciprocal, X/Y/B inverse DC factors, X quant-matrix
  multiplier, EPF iteration count, and the Q24 AQ scale/damping/inverse-global-
  scale triplet generated from the current libjxl-tiny Python formulas.
- `Dct8Approx` is a standalone Q12 1D DCT-8 primitive matching libjxl-tiny's
  recursive scaled-DCT structure within fixed-point tolerance. It is the shared
  kernel for the current 8x8 and rectangular transform stages.
- `Dct8x8Approx` composes the 1D kernel into libjxl-tiny's scaled 8x8 DCT
  coefficient layout. It applies the 1/8 scale after each dimension and emits
  the transposed canonical order consumed by later quantization work.
- `Dct16Approx` extends the same recursive Q12 kernel to 16 samples.
  `Dct16x8Approx` and `Dct8x16Approx` apply the correct 1/16 and 1/8
  per-dimension scales and emit libjxl-tiny's canonical 8x16 coefficient
  layouts. The vertical transform is intentionally transposed; the horizontal
  transform is not. These are combinational reusable primitives for both
  strategy scoring and prepared variable-shape quantization, not frame
  schedulers.
- `AcStrategyDecisionSelector` implements the exact decision-only tail of
  `find_best_16x16_transform`: aggregate orientation comparison, horizontal
  tie selection, strict subregion replacement, and raster first/continuation
  encoding. It accepts already-scaled unsigned candidate costs.
- `AcStrategyCandidateCostEvaluator` is the prepared scoring boundary upstream
  of that selector. It consumes canonical Q12 coefficients plus the maximum
  Q24 AQ and Q16 mask over the candidate, signed tile CFL, and supported Q8
  distance; one coefficient is evaluated per cycle. Its raw and outer-scaled
  Q16 outputs match the host integer model exactly. The integer model preserves
  the reference formula and nearest-even coefficient rounding, but remains
  approximate relative to float libjxl-tiny because the transform input seam is
  Q12. Unsupported distances fall back to distance 1 with an explicit flag.
  Its one-coefficient-per-cycle control does not prove that the wide residual/
  inverse/AQ product and two combinational square roots will meet FPGA timing.
- `PreparedAcStrategy2x2Selector` sequences the reference's eight-candidate
  order, retains 64-bit costs, and applies `AcStrategyDecisionSelector` behind
  a backpressure-safe prepared interface. `FramePreparedAcStrategyTraceStage`
  supplies its tile-local frame traversal and candidate preparation, then walks
  the completed raster map to apply the reference maximum raw-quant operation
  to each selected horizontal or vertical rectangle. It emits the adjusted byte
  as a sideband aligned with every strategy trace. `FrameAqAcStrategyTraceStage`
  supplies the focused RGB-connected source.
- `VarDctQuantizeBlock` is the first-block-owned prepared quantization boundary
  shared by DCT, 16x8, and 8x16. One record carries 64 ordinary-DCT or 128
  rectangular canonical X/Y/B coefficients, the adjusted raw-quant byte,
  distance scalars, and tile CFL values. The result keeps AC coefficients and
  raw token-prefix nonzero counts on that owner, while exposing one or two
  raster-ordered DC cells and the per-cell shifted nonzero count used by frame
  prediction. Rectangular low-frequency coefficient 1 is split with the
  reference DCT16-to-2 scale before DC quantization. Y reconstruction walks one
  active coefficient per cycle (64 for DCT, 128 for rectangles), so the emitted
  hierarchy contains one dynamic quant-bias divider rather than one per
  coefficient. X/B CFL residuals and AC quantization consume the held Y result
  atomically under backpressure.
- `VarDctQuantizeTraceStage` emits owner AC records, covered-cell DC records,
  three raw nonzero counts, then three shifted prediction-map counts. It is a
  focused arithmetic/order diagnostic, not the final non-DCT token scheduler.
  `FramePreparedVarDctQuantizeStage` accepts only first blocks in raster order;
  no continuation record is legal. Its coverage map places the second 16x8
  cell below the owner and the second 8x16 cell to its right, rejects gaps,
  overlaps, out-of-bounds rectangles, and incorrect final markers, and drops a
  malformed frame while retaining sticky `overflow`. The output retains owner
  coordinates, raster ordinal, adjusted quant, and CFL for later metadata and
  AC-token composition. This prepared seam is deliberately separate from the
  RGB strategy scheduler until variable-shape metadata and coefficient scan
  ownership are implemented.
- `FrameDct8x8TraceStage` is the first transform integration slice. It buffers
  and pads the RGB frame, converts each padded raster 8x8 block to approximate
  XYB, applies `Dct8x8Approx` for all three channels, and emits `RawDct8x8`
  trace coefficients. `trace.group` is the raster block index and `trace.index`
  is `channel * 64 + coefficient`.
- `tools/hjxl_reference.py --dct8x8-npy ...` writes raster 8x8 XYB DCT blocks as
  `(block, channel, coefficient)` float32 arrays.
- `QuantizeDct8x8Block` is the first AC quantization primitive. It consumes one
  DCT-only 8x8 coefficient block in Q12, an adjusted raw quant-field byte, the
  distance-derived AC scale in Q16, and an optional X-channel quant-matrix
  multiplier. It applies libjxl-tiny's DCT inverse quant matrices, quadrant
  thresholds, integer rounding, and DC-excluding nonzero count for one channel.
  This is intentionally a block primitive; whole-frame quantization still needs
  AQ/CFL fields, Y roundtrip reconstruction, chroma residual subtraction, and
  quantized DC plane handling before it can claim libjxl-tiny quantized-block
  parity.
- `AcQuantScaleSelector` is the reusable frame-scheduler boundary for choosing
  AC quantization scale parameters. A zero `FrameConfig.fixedPointScale` selects
  `DistanceParamsLookup` scale and reciprocal; a nonzero value selects the
  explicit `fixedPointScale` and `fixedInvQacQ16` pair. A zero
  `FrameConfig.fixedRawQuant` keeps adjusted raw quant 5; a nonzero value
  overrides the fixed-path raw quant globally and must be paired with the
matching reciprocal. `FrameConfig.fixedYtox` and `FrameConfig.fixedYtob`
provide signed 8-bit scalar CFL-map overrides for focused RGB-input metadata
traces; prepared-DCT estimated-CFL routes use separate `FramePreparedCfl*`
wrappers.
- `DctQuantizeTraceStage` wraps `QuantizeDct8x8Block` as a prepared-block trace
  boundary. It accepts one block's coefficients and quant parameters, emits 64
  `QuantizedAc` records with `trace.index = channel * 64 + coefficient`, then
  emits one `NumNonzeros` record with `trace.index = channel`. This is for
  quantization simulation and validation; it is not a substitute for the missing
  frame-level AQ/CFL integration.
- `QuantizeRoundtripYDct8x8Block` builds on the DCT-only Y quantizer by applying
  libjxl-tiny's quantization-bias adjustment and Y DCT weight matrix, then
  dequantizing through an explicit reciprocal AC scale input. This produces the
  reconstructed Y coefficients that X/B quantization must use for CFL residual
  subtraction. The reciprocal is currently supplied by the caller; distance
  parameter hardware is still future work.
- `CflCoefficientSampleWeight`, `CflWeightedSumAccumulator`,
  `CflMultiplierEstimator`, `CflCoefficientSumAccumulator`, and
  `CflTileCoefficientEstimator` are the first isolated RTL primitives for real
  chroma-from-luma estimation. The weighting block applies libjxl-tiny's X/B
  inverse-weight matrix entries to Q16 luma/chroma DCT coefficient pairs. The
  accumulator accepts those weighted `values_m`/`values_s` AC samples for
  either X (`base = 0`) or B (`base = 1`) and forms Q16 `sum(a*a)` and
  `sum(a*b)` terms. The estimator applies libjxl-tiny's regularized
  least-squares denominator, rounds the signed multiplier, and clamps to int8.
  `CflCoefficientSumAccumulator` composes that chain behind a coefficient-pair
  stream; `CflTileCoefficientEstimator` consumes one Y/X/B coefficient-triple
  stream and emits both Y-to-X and Y-to-B tile results; and
  `CflTileCoefficientTraceStage` emits the corresponding `YtoxMap`/`YtobMap`
  `StageTrace` rows. Future tile logic still needs to schedule 8x8 coefficient
  traversal across each 64x64 tile for the RGB-input path.
- `FramePreparedCflMapTraceStage` is the first prepared-frame scheduler for real
  CFL maps. It accepts prepared DCT-only raster block records, buffers X/Y/B
  coefficients, walks the valid 8x8 blocks inside each 64x64 tile, and emits a
  `YtoxMap`/`YtobMap` trace pair per tile. This validates the frame/tile CFL
  traversal boundary reused by the estimated-CFL metadata, quantization, and
  token wrappers.
- `FramePreparedCflAcMetadataTokenTraceStage` composes that prepared CFL map
  scheduler with `FramePreparedAcMetadataTokenTraceStage`. It consumes prepared
  DCT-only raster block records, captures estimated Y-to-X/Y-to-B maps
  internally, and emits AC-metadata tokens with those maps.
- `FramePreparedCflDctOnlyQuantizeTraceStage` composes the same prepared CFL map
  scheduler with `DctOnlyQuantizeTraceStage`. It buffers prepared DCT-only block
  records, captures estimated tile CFL maps, substitutes the per-block tile
  values into quantization, and emits prepared `QuantizedAc`, `QuantDc`, and
  `NumNonzeros` traces.
- `FramePreparedCflDctOnlyQuantizeTokenTraceStage` is the current strongest
  estimated-CFL prepared-DCT token boundary. It buffers prepared blocks,
  estimates tile CFL maps once, quantizes with those maps, feeds the same maps
  to prepared AC-metadata tokenization, and emits DC, strategy, AC-metadata, and
  AC token traces in the host-assembler order.
- `QuantizeChromaResidualDct8x8Block` consumes X or B coefficients, the
  reconstructed Y coefficients, a signed CFL multiplier, and quantization
  parameters. It subtracts `ytox / 84 * Y` for X or `(1 + ytob / 84) * Y` for B,
  then reuses `QuantizeDct8x8Block` for AC quantization and nonzero counting.
- `QuantizeDcDct8x8Block` quantizes one DCT-only DC coefficient using an
  explicit inverse DC factor. For B it subtracts half of the already-quantized Y
  DC value, matching libjxl-tiny's chroma DC storage convention. This completes
  the current DCT-only per-channel AC/DC block quantization math.
- `DctOnlyQuantizeBlock` composes the DCT-only block primitives for one prepared
  X/Y/B 8x8 coefficient triplet. The caller supplies the raw quant value, AC
  scale, reciprocal AC scale, inverse DC factors, X quant-matrix multiplier, and
  tile CFL multipliers; the module emits quantized AC coefficients, quantized DC
  coefficients, and nonzero counts for all channels. This is the block-level
  shape a future frame scheduler should use after AQ/CFL/distance parameter
  generation exists.
- `DctOnlyQuantizeTraceStage` wraps `DctOnlyQuantizeBlock` for prepared-block
  trace validation. It emits 192 `QuantizedAc` records first
  (`trace.index = channel * 64 + coefficient`), then three `QuantDc` records
  (`trace.index = channel`), then three `NumNonzeros` records
  (`trace.index = channel`). `trace.group` is the caller-provided block/group
  id.
- `FramePreparedDctOnlyQuantizeTraceStage` schedules complete prepared
  DCT-only X/Y/B blocks in raster order through `DctOnlyQuantizeTraceStage`.
  Its input boundary is the structured prepared-block data exported by
  `tools/hjxl_reference.py --dct-only-prepared-blocks-json`. This boundary uses
  Q16 DCT coefficients by default (`HjxlConfig.preparedDctCoefficientFractionBits`)
  so prepared CFL residual subtraction and quantization can match libjxl-tiny's
  floating reference fixture exactly. Its output boundary is frame-shaped
  `QuantizedAc`, `QuantDc`, and `NumNonzeros` trace records with `trace.group`
  equal to the raster block ordinal, and `traceLast` marks the final
  `NumNonzeros` record of the final prepared raster block.
- `FramePreparedDctOnlyQuantizeTokenTraceStage` is the first direct RTL bridge
  from prepared DCT-only block inputs to fixed all-DCT logical token traces. It
  runs `DctOnlyQuantizeBlock`, stores DC values for the required Y/X/B plane
  reorder, and atomically streams each quantized AC/nonzero result and metadata
  record into the dedicated prepared schedulers that own those frame stores.
  This removes the orchestration layer's duplicate AC/nonzero and metadata
  arrays while preserving the required DC, strategy, metadata, and AC output
  order. Unlike `FramePreparedTokenTraceStage`, it can describe the prepared
  raw-quant and CFL values used during quantization. It exposes `traceLast` on
  the final AC-token trace beat for host capture.
- `FrameAdjustedRawQuantFieldTraceStage` is the public focused raw-quant
  selector. Zero `FrameConfig.fixedRawQuant` routes RGB through the completed AQ
  map, adaptive strategy search, and post-search rectangle adjustment; a
  nonzero value selects `FrameFixedRawQuantFieldTraceStage` because a uniform
  byte is already adjustment-invariant. Selection is captured at frame start,
  and the byte is zero-extended into `StageTrace.value`, so values above 127
  remain positive despite the signed trace carrier. The default all-route shell
  omits this heavy focused path. The older `FrameRawQuantFieldTraceStage`
  remains a narrower pre-strategy conversion diagnostic.
- `AqMapToRawQuant` is the first fixed-point boundary for real adaptive-
  quantization data. It multiplies an unsigned Q24 AQ-map sample by the
  unsigned Q24 inverse global AC scale, rounds to the nearest integer, and
  clamps the result to libjxl-tiny's raw-quant range `[1, 255]`.
  `FramePreparedAqRawQuantTraceStage` accepts one prepared AQ sample per padded
  8x8 raster block, snapshots frame geometry and inverse scale on the first
  accepted block, buffers one trace beat for ready/valid stability, and emits
  `RawQuantField` traces with `traceLast` on the final block. This stage moves
  the independently testable final conversion seam. The RGB path now supplies
  this converter from `FrameAqFinalMapPipeline`; the prepared stage remains for
  exact arithmetic isolation and is not yet integrated into prepared-token
  scheduling.
- `FrameAqDctBlockStage` is the reusable RGB-to-DCT bridge. It retains the
  completed AQ pipeline's X/Y/B Q12 block, applies three shared
  `Dct8x8Approx` instances, selects adaptive or explicit fixed raw quant, and
  emits distance-derived scalars without a reciprocal. This keeps map and
  metadata-only hierarchies from elaborating unused divider hardware. The
  focused RGB VarDCT build also captures exact Q12 and Q16 results from one
  cube-root lookup path, stores one optional Q16 frame at the final-AQ owner,
  and emits Q16 ordinary-DCT values for selected-owner CFL fitting and
  quantization. A one-bit correction ROM preserves the original Q12 lookup
  contract without a second full converter; older all-DCT routes do not
  elaborate the Q16 sideband or frame store.
  `FrameAqDctOnlyBlockStage` enriches the same record for quantization:
  adaptive blocks compute `round(2^32 / (scaleQ16 * rawQuant))` with the
  33-cycle `AdaptiveInvQacQ16` restoring divider; zero divisors saturate and no
  division operator appears in emitted RTL. A nonzero `fixedRawQuant` instead
  preserves the caller-supplied `fixedInvQacQ16` contract.
- `FrameAqCflMapTraceStage` feeds the native-Q12 DCT records into the prepared
  tile estimator. `FramePreparedCflMapTraceStage` widens Q12 to its Q16 fitting
  domain only at that boundary, preserving the prepared-Q16 stream ABI. It can
  expose both maps or compile-time-filter one map while automatically draining
  the other internal result; selected-stream `traceLast` marks the last chosen
  tile.
- `FrameAqCflDctOnlyQuantizeTraceStage` replays the buffered records with the
  estimated maps through the Q12-parameterized prepared quantizer and emits
  192 `QuantizedAc`, three `QuantDc`, and three `NumNonzeros` records per raster
  block. `FrameAqCflDctOnlyQuantizeTokenTraceStage` emits DC, all-DCT strategy,
  adaptive AC-metadata, and AC logical traces in host-assembler order; its
  focused-core form drains the preceding token classes and exposes only AC.
  `FrameAqCflDctOnlyAcMetadataTokenTraceStage` uses the DCT records for map
  fitting but deliberately omits reciprocal hardware. The older
  `FrameAqDctOnly*` wrappers and `FrameCflMapTraceStage` remain fixed-CFL
  diagnostics rather than current core routes.
- `FrameDctOnlyQuantizeTraceStage` is the retained fixed frame-level
  quantized-block diagnostic. It buffers and pads RGB like
  `FrameDct8x8TraceStage`, converts
  each raster block through approximate XYB and DCT, then emits 198 records per
  block: 192 `QuantizedAc`, three `QuantDc`, and three `NumNonzeros` records.
  This stage deliberately still uses one global adjusted raw quant value and
  scalar fixed tile CFL multipliers, but it now takes distance-derived AC/DC scalar
  parameters from `DistanceParamsLookup`. `FrameConfig.fixedPointScale` plus
  `fixedInvQacQ16` can override only the AC scale path; a zero scale value uses
  the lookup scale and reciprocal. `FrameConfig.fixedRawQuant` can override the
  default raw quant 5 globally for trace experiments.
- `DcTokenize` implements the libjxl-tiny signed-token packing, clamped
  gradient predictor, and compressed 1024-entry gradient-context lookup used by
  DC tokenization.
- `DcTokenTraceStage` is the prepared quantized-DC token boundary. It accepts
  one already-quantized DC value plus west/north/northwest predictor neighbors,
  then emits the logical `(context, value)` DC token. Its test matches the
  libjxl-tiny fixed-token oracle exactly for the constant-frame prepared DC
  planes, independent of the current fixed-point RGB-to-quantized-DC path.
- `FramePreparedDcTokenTraceStage` schedules complete prepared quantized DC
  planes through that exact token boundary. It accepts DC samples in
  libjxl-tiny token order: Y plane, X plane, then B plane, each in raster block
  order. It buffers the frame, computes west/north/northwest predictors from
  the prepared planes, and emits `DcTokens` trace records.
- `FrameDctOnlyDcTokenTraceStage` emits DC residual token traces from the same
  fixed-parameter DCT-only frame path. It emits tokens in libjxl-tiny order:
  Y plane, X plane, then B plane, each in raster block order. Token traces use
  `trace.stage = DcTokens`, `trace.group = token ordinal`,
  `trace.index = context`, and `trace.value = packed residual`. The frame
  scheduler computes current/west/north/northwest quantized DC values, then
  delegates predictor/context/residual packing to `DcTokenTraceStage`.
- `FrameDctOnlyAcMetadataTokenTraceStage` emits AC-metadata token traces for the
  current fixed all-DCT path. It emits scalar fixed CFL tile-map tokens, all-DCT
  strategy tokens, fixed raw-quant-field tokens, and fixed block-metadata
  literals in libjxl-tiny order. Token traces use
  `trace.stage = AcMetadataTokens`, `trace.group = token ordinal`,
  `trace.index = context`, and `trace.value = packed residual or literal`.
  `FrameDctOnlyDcTokenTraceStage` and `FrameDctOnlyAcMetadataTokenTraceStage`
  both expose `traceLast` on their final fixed-length token trace beat.
- `FramePreparedAcMetadataTokenTraceStage` emits AC-metadata token traces from
  prepared raster block metadata. Each input block supplies raw quant, Y-to-X
  CFL, and Y-to-B CFL values. The stage stores raw quant per block and CFL per
  tile, then emits CFL residuals with libjxl-tiny's clamped-gradient predictor,
  all-DCT strategy tokens, quant-field residuals, and fixed block metadata
  literals.
- `FrameDctOnlyAcNonzeroTokenTraceStage` emits the mandatory nonzero-count
  prefix tokens of AC coefficient tokenization for the current fixed all-DCT
  path. It predicts each count from top/left block nonzero history, applies
  libjxl-tiny's nonzero bucket context formula for ordinary DCT, and emits
  `trace.stage = AcTokens`, `trace.group = token ordinal`, `trace.index =
  context`, and `trace.value = nonzero count`; `traceLast` marks the final
  prefix token. It is currently a standalone frame stage rather than an
  `HjxlCore` route, and it does not yet emit
  coefficient scan tokens.
- `AcCoefficientTokenTraceStage` emits prepared-block ordinary-DCT AC
  coefficient scan tokens. The caller provides quantized coefficients, channel,
  nonzero count, and first token ordinal; the stage walks libjxl-tiny's 8x8 DCT
  coefficient order, applies the zero-density context formula, packs signed
  coefficients, and stops after consuming all nonzero AC coefficients. This is
  the block primitive needed before frame-level AC coefficient token scheduling.
- `AcBlockTokenTraceStage` wraps the prepared AC coefficient-token primitive
  with the required nonzero-count prefix token for one ordinary-DCT
  block/channel. The caller supplies the predicted nonzero count from the
  frame scheduler plus the actual count and quantized coefficients; the wrapper
  emits a complete prepared block/channel AC token stream.
- `DctOnlyAcBlockTokenTraceStage` sequences `AcBlockTokenTraceStage` over one
  prepared X/Y/B ordinary-DCT block in libjxl-tiny channel order: Y, X, then B.
  The caller supplies predicted nonzero counts, actual nonzero counts, quantized
  coefficients, and the first token ordinal.
- `FramePreparedAcTokenTraceStage` schedules complete prepared quantized AC
  blocks through that exact all-DCT token boundary. It accepts blocks in raster
  order and stores frame-scaled coefficients in
  `PreparedAcCoefficientFrameStore`, a `SyncReadMem` organized as one 96-bit
  X/Y/B coefficient triplet per address. A single block register serializes 64
  writes and reconstructs 64 reads; the generated default module is a
  1024x96 synchronous memory rather than a 16-block wide register array.
  Nonzero counts remain in a small register plane for west/north prediction.
  The scheduler prefetches the next block while the current block emits tokens,
  then emits the full `AcTokens` stream. The 64-cycle write drain fits beneath
  the 201-word packed prepared-block arrival interval, so this change does not
  add packed-stream input stalls.
- `FramePreparedTokenTraceStage` combines the exact prepared DC and AC token
  schedulers with fixed all-DCT AC strategy and AC-metadata trace generation.
  Its input boundary is prepared quantized DC samples plus prepared quantized AC
  blocks; the fixed metadata path uses `FrameConfig.fixedRawQuant`,
  `fixedYtox`, and `fixedYtob` rather than per-block prepared metadata inputs.
  Its output boundary is the same `StageTrace` CSV shape consumed by
  `tools/hjxl_trace_tokens.py` and the host bitstream assembler. It exposes
  `traceLast` on the final AC-token trace beat, giving the combined prepared
  boundary an explicit frame delimiter.
- `FrameDctOnlyAcTokenTraceStage` is the first complete RGB-input standalone
  frame scheduler for the fixed all-DCT AC token stream. It buffers/pads RGB,
  computes the same fixed DCT-only quantized block data as
  `FrameDctOnlyQuantizeTraceStage`, predicts nonzero counts from west/north
  block history, then emits each block's full Y/X/B AC token stream through
  `DctOnlyAcBlockTokenTraceStage`. It is kept out of default `HjxlCore`
  all-route elaboration because this scheduler is still large and trace-only,
  but it is available through the normal core IO when `HjxlCore` is elaborated
  with `traceRoute = TraceStage.AcTokens`.
- `HjxlAcTokenCore` is a compile-time top wrapper around
  `FrameDctOnlyAcTokenTraceStage`. Use `sbt 'runMain hjxl.ElaborateAcTokens'`
  when SystemVerilog for the full AC-token path is needed as a dedicated top;
  use `new HjxlCore(traceRoute = TraceStage.AcTokens)` or
  `sbt 'runMain hjxl.ElaborateCoreAcTokens'` when the public core IO shell is
  useful for focused simulation or integration. Every current frame trace
  scheduler/top exposes `traceLast`; `HjxlCore` carries it for the selected
  route, and the dedicated AC top exposes it alongside the trace stream.
  `HjxlCoreRouteElaborationSpec` guards that split so the default all-route
  shell stays smaller.
- `HjxlAxiStreamCore` wraps `HjxlCore` in an AXI4-Stream-shaped raster input
  and trace output. Input data packs R/G/B into consecutive `pixelBits` fields
  with R in the low bits, checks input `last` against the configured raster
  frame length, and exposes active-route `busy`/`overflow` status plus sticky
  `protocolError` and a `clearProtocolError` input for host recovery. Output
  data packs `{value,index,group,stage}` with `stage` in the low eight bits.
  Output `last` is asserted on each route's final frame trace word by carrying
  the selected scheduler's `traceLast` sideband to TLAST. The complete
  `FrameConfig` is snapshotted on the first accepted input beat and held
  through acceptance of that final trace beat, preventing live control changes
  from moving an active frame between schedulers or parameter sets. Use
  `sbt 'runMain hjxl.ElaborateAxiStream'` for the default shell or
  `sbt 'runMain hjxl.ElaborateAxiStreamCoreAcTokens'` for the focused
  full-AC-token shell.
- `HjxlAxiLiteStreamCore` wraps that stream shell with AXI-Lite configuration
  registers while preserving the same raster input stream and packed trace
  output stream. The 32-bit register map is: `0x00` status/control
  (`protocolError` read at bit 0, `busy` at bit 1, `overflow` at bit 2,
  unsupported `distanceQ8` fallback at bit 3, clear protocol error on write
  bit 0), `0x04` `xsize`, `0x08` `ysize`, `0x0c`
  `distanceQ8`, `0x10` `fixedPointScale`, `0x14`
  `fixedInvQacQ16`, `0x18` `fixedRawQuant`, `0x1c` flags,
  `0x20` signed-low-byte `fixedYtox`, and `0x24` signed-low-byte `fixedYtob`
  (`enableXyb`, `enableDct`, `enableQuant`, `enableTokenize`, and
  `tokenSelect` at bits 9:8). Writes honor byte strobes; unmapped word
  addresses return AXI-Lite DECERR. Writes remain legal while `busy`: reads
  immediately expose the updated shadow register bank, while the active stream
  uses its snapshot and the new values apply to the next frame. The read-only
  discovery window is `0x28` identity, `0x2c` ABI version, `0x30` capabilities,
  `0x34` packed maximum frame geometry, `0x38` active route, and `0x3c` contract
  build ID. RGB active route is latched with the frame-start configuration;
  prepared wrappers report route IDs 128 (direct) or 129 (estimated CFL).
  Writes to discovery registers return DECERR. `tools/hjxl_discovery_check.py`
  validates real `address,data,resp` readback against a bundle or replay plan,
  accepting capability supersets but requiring identity/version/build/route
  equality and sufficient compiled frame capacity. Use
  `sbt 'runMain hjxl.ElaborateAxiLiteStream'` for the default controlled shell
  or `sbt 'runMain hjxl.ElaborateAxiLiteStreamCoreAcTokens'` for the focused
  full-AC-token controlled shell.
- `ElaborateAqContrast`, `ElaborateAqFuzzyErosion`,
  `ElaborateAqStrategyMask`, `ElaborateAqNonlinearMask`, and
  `ElaborateAqHfModulation`/`ElaborateAqColorModulation`/
  `ElaborateAqGammaModulation`/`ElaborateAqFinalMap`/
  `ElaborateAqRawQuant` generate standalone
  RGB AQ diagnostic tops in
  `generated-aq-contrast/`, `generated-aq-fuzzy-erosion/`, and
  `generated-aq-strategy-mask/`, `generated-aq-nonlinear-mask/`, and
  `generated-aq-hf-modulation/`/`generated-aq-color-modulation/`/
  `generated-aq-gamma-modulation/`/`generated-aq-final-map/`/
  `generated-aq-raw-quant/`. `ElaboratePreparedAqFinalModulation` separately
  emits the narrow prepared-seed final operation. Their
  elaboration specs guard the composed RGB/prepared module boundaries and
  trace/status ports; the strategy-mask and nonlinear-mask specs also reject
  division operators in their sequential arithmetic modules.
- `ElaboratePreparedAcStrategy` and `ElaborateAqAcStrategy` generate the
  prepared and RGB-connected strategy schedulers. `ElaborateAqAdjustedRawQuant`
  exposes the RGB strategy-adjusted byte trace, while `ElaborateCoreAcStrategy`
  and `ElaborateCoreRawQuant` wrap the corresponding focused routes in the
  public core IO. They write the ignored `generated-prepared-ac-strategy/`,
  `generated-aq-ac-strategy/`, `generated-aq-adjusted-raw-quant/`,
  `generated-core-ac-strategy/`, and `generated-core-raw-quant/` trees.
- `ElaborateAqDctOnlyQuantize`, `ElaborateAqDctOnlyAcMetadataTokens`, and
  `ElaborateAqDctOnlyQuantizeTokens` retain the fixed-CFL adaptive RGB
  diagnostics. `ElaborateAqCflMaps`, `ElaborateAqCflDctOnlyQuantize`,
  `ElaborateAqCflDctOnlyAcMetadataTokens`, and
  `ElaborateAqCflDctOnlyQuantizeTokens` emit the estimated-CFL map, quantized,
  metadata, and combined logical-token tops used by the current RGB core
  routes. Structural regressions require one RGB-to-XYB converter and three
  DCT instances in every estimated-CFL hierarchy; only quantization/token tops
  may contain `AdaptiveInvQacQ16`, and that restoring implementation must not
  emit a division operator.
- `ElaboratePreparedDctOnlyQuantize` generates standalone SystemVerilog for the
  prepared-DCT quantization scheduler. Use it when the integration boundary is
  after DCT/AQ/CFL scalar generation but before quantized DC/AC tokenization.
  The generated top exposes `traceLast` for capture boundaries.
- `ElaboratePreparedVarDctQuantize` generates the structured first-block-owned
  DCT/16x8/8x16 frame quantizer in `generated-prepared-var-dct-quantize/`.
  Its elaboration regression guards the 128-coefficient surface and requires
  exactly one dynamic quant-bias division in emitted RTL. The current hierarchy
  still contains three combinational 128-lane AC quantizers; no synthesis,
  resource, or timing claim is made for this boundary.
- `ElaboratePreparedDctOnlyQuantizeTokens` generates standalone SystemVerilog
  for the direct prepared-DCT quantize-to-token wrapper. This is the closest
  current RTL artifact to a prepared-transform encoder core, and it exposes
  `traceLast` for frame-level capture boundaries.
- `HjxlPreparedDctAxiStreamCore` wraps that direct prepared-DCT
  quantize-to-token wrapper in a 32-bit AXI4-Stream-shaped input. Each prepared
  raster block is supplied as 201 words: nine scalar words (`quant`, `scaleQ16`,
  `invQacQ16`, three inverse-DC factors, `xQmMultiplierQ16`, signed `ytox`,
  signed `ytob`) followed by 64 X, 64 Y, and 64 B DCT coefficients.
  Coefficients and CFL values are two's-complement encoded in the low bits.
  Input TLAST is expected on the final word of the final configured raster
  block; mismatches set sticky `protocolError`. Output uses the same packed
  `{value,index,group,stage}` trace stream format and maps the direct wrapper's
  `traceLast` to TLAST. Use `sbt 'runMain hjxl.ElaboratePreparedDctAxiStream'`
  when a DMA-facing version of the prepared-DCT token boundary is needed.
  `tools/hjxl_prepared_blocks.py --input-stream-csv ...` emits this exact
  `data,last` stream format from the prepared-block JSON oracle. The same tool
  can emit the paired AXI-Lite `address,data,strb` control CSV and a replay
  manifest with `--axi-lite-csv` and `--manifest-json`; the prepared default is
  the direct token route (`flags = 526`), and `--validate-manifest` checks saved
  stream/control bundles before host replay.
- `HjxlPreparedCflDctAxiStreamCore` is the estimated-CFL sibling of that stream
  shell. It preserves the same 201-word prepared block ABI and packed trace
  output, but wraps `FramePreparedCflDctOnlyQuantizeTokenTraceStage` so tile
  CFL maps are estimated inside RTL from prepared coefficients before
  quantization and tokenization. The incoming scalar `ytox`/`ytob` words are
  parsed for stream compatibility only. Use
  `sbt 'runMain hjxl.ElaboratePreparedCflDctAxiStream'` when a DMA-facing
  prepared-DCT token shell should own CFL-map estimation.
- `HjxlPreparedCflDctAxiLiteStreamCore` wraps the estimated-CFL stream shell
  with the same AXI-Lite register map, status bits, and full-word input `TKEEP`
  contract as `HjxlPreparedDctAxiLiteStreamCore`. Use
  `sbt 'runMain hjxl.ElaboratePreparedCflDctAxiLiteStream'` when a controlled
  prepared-DCT token shell should own CFL-map estimation behind the common
  AXI-Lite control surface. Use `HjxlKv260PreparedCflDctTop` when the same route
  needs the flat Vivado/KV260 port surface.
- `HjxlPreparedDctAxiLiteStreamCore` wraps the prepared-DCT stream shell with
  the common AXI-Lite register map. `xsize`, `ysize`, and status/control are
  consumed directly for stream framing, busy/overflow reporting, and
  protocol-error recovery; `unsupportedDistance` mirrors status/control bit 3
  for distance fallback visibility. The other
  `FrameConfig` registers remain exposed for a uniform host control surface and
  future prepared-path experiments. Use
  `sbt 'runMain hjxl.ElaboratePreparedDctAxiLiteStream'` when both the
  prepared-DCT packed stream path and AXI-Lite configuration are needed.
- `HjxlKv260PreparedDctTop` is a thin `RawModule` wrapper around
  `HjxlPreparedDctAxiLiteStreamCore` for Vivado block-design integration. It
  keeps the prepared stream/control behavior unchanged but exposes flat
  `ap_clk`, active-low `ap_rst_n`, `s_axi_control_*` AXI-Lite,
  `s_axis_input_*` prepared-block input stream, 128-bit `m_axis_trace_*` trace
  output stream with full-word input TKEEP, partial-input-keep protocol-error
  reporting, and output TKEEP marking the valid low 11 bytes, and
  `busy`/`overflow`/`protocol_error`/`unsupported_distance` status ports. Use
  `sbt 'runMain hjxl.ElaborateKv260PreparedDctTop'` to write
  `generated-kv260-prepared-dct-top/`. This direct variant is the frozen
  near-term synthesis and KV260 bring-up target; collect physical feasibility
  data for it before expanding the experimental estimated-CFL variant.
- `HjxlKv260PreparedCflDctTop` is the estimated-CFL variant of that flat
  wrapper. It preserves the KV260-facing ports and 128-bit padded trace stream,
  but wraps `HjxlPreparedCflDctAxiLiteStreamCore` internally so prepared
  coefficients drive RTL CFL-map estimation before quantization/tokenization.
  Use `sbt 'runMain hjxl.ElaborateKv260PreparedCflDctTop'` to write
  `generated-kv260-prepared-cfl-dct-top/`. Host manifests, bundles, and replay
  plans select this route explicitly with `target.variant = "estimated-cfl"`;
  the default prepared-DCT host target remains `target.variant = "direct"`.
- `PreparedDctElaborationSpec` is the focused FIRTool emission regression for
  those prepared-DCT standalone tops. It emits SystemVerilog into a temporary
  directory and checks for the expected modules plus the structured
  prepared-block input, trace output, busy, and overflow ports, so generated RTL
  or top-level IO breakage is caught without committing generated files.
- `ElaborateCflTileCoefficientTrace` generates standalone SystemVerilog for the
  tile coefficient CFL trace bridge. Use
  `sbt 'runMain hjxl.ElaborateCflTileCoefficientTrace'` when validating or
  timing the current coefficient-stream CFL estimator before frame-level tile
  traversal exists. The generated top emits `YtoxMap` followed by `YtobMap`
  trace rows from caller-supplied Y/X/B coefficient triples.
- `ElaboratePreparedCflMapTrace` generates standalone SystemVerilog for the
  prepared-frame CFL map scheduler. Use
  `sbt 'runMain hjxl.ElaboratePreparedCflMapTrace'` when validating or timing
  the prepared-block-to-CFL-map boundary.
- `ElaboratePreparedCflDctOnlyQuantize` generates standalone SystemVerilog for
  prepared DCT-only quantization with internally estimated CFL maps. Use
  `sbt 'runMain hjxl.ElaboratePreparedCflDctOnlyQuantize'` when validating that
  CFL estimation is feeding chroma residual quantization.
- `ElaboratePreparedCflDctOnlyQuantizeTokens` generates standalone SystemVerilog
  for the direct prepared quantize-to-token path with internally estimated CFL
  maps. Use `sbt 'runMain hjxl.ElaboratePreparedCflDctOnlyQuantizeTokens'` when
  validating the current strongest estimated-CFL prepared-token boundary.
- `ElaboratePreparedCflDctAxiStream` generates standalone SystemVerilog for the
  packed AXI-stream version of the estimated-CFL prepared quantize-to-token
  path. Use `sbt 'runMain hjxl.ElaboratePreparedCflDctAxiStream'` when
  validating the DMA-shaped stream boundary before adding a control plane.
- `ElaboratePreparedCflDctAxiLiteStream` generates standalone SystemVerilog for
  the AXI-Lite-controlled estimated-CFL prepared stream shell. Use
  `sbt 'runMain hjxl.ElaboratePreparedCflDctAxiLiteStream'` when validating the
  shared control-plane wrapper around the estimated-CFL stream route.
- `ElaboratePreparedCflAcMetadataTokens` generates standalone SystemVerilog for
  the prepared-frame AC-metadata token path with internally estimated CFL maps.
  Use `sbt 'runMain hjxl.ElaboratePreparedCflAcMetadataTokens'` when validating
  that CFL map estimation is feeding token metadata.
- `CflElaborationSpec` guards those generated CFL trace tops' coefficient input,
  prepared-block input, trace output, `traceLast`, `busy`, and overflow port
  surfaces.
- `HjxlPreparedDctAxiStreamCoreSpec` compares the packed stream shell against
  the structured direct prepared-DCT quantize-to-token wrapper, checks prepared
  input TLAST error reporting/clearing, guards the generated stream-shell port
  surface, and runs an oracle-backed path from libjxl-tiny prepared-block JSON
  through `--input-stream-csv`, RTL packed token trace output, and
  `tools/hjxl_trace_to_codestream.py` byte comparison against libjxl-tiny's
  direct DCT-only codestream.
- `HjxlPreparedDctAxiLiteStreamCoreSpec` covers the controlled prepared-DCT
  stream top's register map, AXI-Lite programmed frame sizing, protocol-error
  clearing, invalid input-keep rejection, generated port surface, generated
  control-CSV replay, manifest validation, and the same prepared-block stream
  to codestream byte comparison used for the plain prepared-DCT stream shell.
- `HjxlKv260PreparedDctTopElaborationSpec` guards the flat Vivado-style port
  names for the current KV260 top and checks that the prepared-DCT AXI-Lite
  stream core remains instantiated below it. It also simulates the flat
  AXI-Lite status/control path, input/trace `TKEEP`, partial input-keep
  protocol-error handling, a complete one-block prepared input stream to padded
  trace `TLAST` with packed trace-field checks, and protocol-error recovery
  through a test harness around the `RawModule` top.
- `HjxlKv260PreparedCflDctTopElaborationSpec` guards the matching flat
  Vivado-style port surface for the estimated-CFL KV260 top, verifies that
  generated RTL includes the estimated-CFL controlled and stream modules, and
  simulates status/control, input/trace `TKEEP`, one-block stream forwarding,
  and partial input-keep recovery through that route.
- `HjxlAxiStreamCoreSpec` covers the stream shell's raster coordinate
  generation, trace-row packing, fixed-size route output TLAST, and input TLAST
  protocol checking on cheap routes. It also captures real packed output from
  the padded-input stream shell and decodes it with `tools/hjxl_stream_trace.py`
  so the Chisel packing and host decoder stay aligned. It also verifies focused
  raw-quant/CFL metadata route captures through direct
  `tools/hjxl_trace_tokens.py` metadata-grid extraction and
  `tools/hjxl_compare_tokens.py` fixed-oracle comparison, and checks packed
  `AqContrast` stage/index/value fields with final TLAST plus the focused
  `AqFuzzyErosion`, `AqStrategyMask`, signed `AqNonlinearMask`, and signed
  `AqHfModulation`/`AqColorModulation`/`AqGammaModulation`, plus completed
  `AqFinalMap`, adaptive quantized-record, and adaptive AC-metadata block/TLAST
  paths.
  Full AC-token TLAST alignment is covered
  at the token-stage and frame-scheduler levels because the focused AXI AC-token
  simulation is expensive.
- `HjxlAxiStreamElaborationSpec` guards the generated stream-shell port surface
  and checks that the focused AC-token stream top includes the full AC-token
  scheduler while the default stream top omits it.
- `ElaboratePreparedAcMetadataTokens` generates standalone SystemVerilog for
  the prepared AC-metadata token scheduler. The generated top exposes
  `traceLast` for fixed-length metadata capture boundaries.
- `ElaboratePreparedDcTokens` and `ElaboratePreparedAcTokens` generate
  standalone SystemVerilog for the exact prepared-token boundaries. Use these
  when integrating or timing the token path after quantized DC/AC planes are
  supplied by a future quantization scheduler. The prepared DC and AC tops
  expose `traceLast` for frame-level capture boundaries.
- `ElaboratePreparedTokens` generates the combined prepared fixed-token top and
  is the preferred RTL artifact for the current host bitstream assembly
  boundary. Its generated top also exposes `traceLast` for the combined
  DC/strategy/metadata/AC trace stream.
- `PreparedTokenElaborationSpec` guards the generated trace port surface for
  prepared DC, AC-metadata, AC, and combined token tops.
- Full frame-level parity remains open even though adaptive raw quant, dynamic
  reciprocal scaling, RGB-derived tile CFL, quantized traces, AC metadata, and
  a combined all-DCT token stream are connected, while separate focused routes
  now emit an RGB-derived adaptive strategy map and its adjusted raw-quant
  field. The RGB path still uses approximate Q12 XYB/DCT. A separate prepared
  first-block-owned boundary now quantizes selected rectangle shapes, but the
  RGB strategy output does not yet feed it and variable-shape metadata/tokens
  are still absent; broader distance generation, whole-frame reference
  comparison, and RGB-token-to-codestream proof remain separate work.
  Entropy optimization and bitstream assembly remain host responsibilities.
- `CflCoefficientSampleWeight`/`CflWeightedSumAccumulator`/
  `CflMultiplierEstimator`/`CflCoefficientSumAccumulator`/
  `CflTileCoefficientEstimator`/`CflTileCoefficientTraceStage` cover only
  inverse-weight lookup/application, weighted-sample accumulation, signed CFL
  multiplier division/round/clamp, and tile trace emission. They are reused by
  the prepared/RGB frame map, quantization, metadata, token, and focused
  strategy schedulers; keep the arithmetic primitives directly tested while
  physical implementation and non-DCT interaction remain open.
- `FrameRawQuantFieldTraceStage` selects the completed but pre-strategy adaptive
  raw quant at zero `fixedRawQuant` and the retained fixed scaffold for a
  nonzero override. It remains useful as a narrow conversion diagnostic;
  `HjxlCore` uses `FrameAdjustedRawQuantFieldTraceStage` for the focused public
  route.
- `FrameCflMapTraceStage` emits fixed scalar Y-to-X and Y-to-B CFL map traces,
  one record per 64x64 tile. Treat it as a traceable fixed-metadata scaffold
  for future chroma-from-luma work, not as libjxl-tiny CFL parity.
- `FrameAcStrategyTraceStage` remains the default all-route scaffold and emits
  ordinary DCT with the libjxl-tiny encoding
  `(raw_strategy << 1) | is_first_block == 1`. The focused adaptive path uses
  `FramePreparedAcStrategyTraceStage`: it buffers prepared raster Q12 XYB and
  ordinary-DCT blocks plus raw-quant bytes, estimates CFL separately for each 64x64 tile, visits
  complete 2x2 block regions in tile-local raster order, computes the four DCT,
  two 16x8, and two 8x16 candidates with covered-block AQ/mask maxima, stores
  each exact fixed-point decision, then applies `adjust_quant_field` by lifting
  both bytes of each first-block rectangle to their local maximum. It emits the
  full raster strategy map with an aligned adjusted-byte sideband. Incomplete
  frame or tile rows/columns remain DCT. `FrameAqAcStrategyTraceStage` feeds
  that boundary from the shared RGB final-AQ/DCT block source; the focused core
  route therefore uses one RGB-to-XYB conversion and three ordinary DCTs.
  `FrameAqAdjustedRawQuantTraceStage` exposes the sideband as raw-quant traces.
  The selected-owner composition can optionally buffer a higher-precision
  coefficient sideband for CFL fitting and quantization while the scorer keeps
  the established Q12 inputs and decisions. The live VarDCT route obtains both
  precisions from one converter and uses Q16 downstream; prepared Q12 strategy
  users retain their original interface.
- `tools/hjxl_reference.py --scaled-dct-q12-csv ...` writes signed Q12 DCT-16,
  16x8, and 8x16 inputs beside independent libjxl-tiny coefficients and the
  exact integer transform model. The axis ramps guard the two different
  canonical rectangular layouts.
- `tools/hjxl_reference.py --ac-strategy-cost-q16-csv ...` writes all eight
  prepared candidates for one 16x16 region, the exact fixed-point estimates and
  scaled costs, float reference costs, the reference/scorer decisions, and the
  scheduler-fixed decision after exact Q12 rectangular transforms plus fixed
  CFL fitting. Use it to validate both the scorer and frame-composition seams.
- `tools/hjxl_reference.py --var-dct-quantize-q16-csv ...` writes DCT, 16x8,
  and 8x16 first-block records with canonical Q16 coefficients, the exact
  frozen-Q16 AC/DC/raw/shifted-count model, and the native-float libjxl-tiny
  result. The current 12-case audit is exact against the fixed model; native AC
  differs by at most one integer, while DC and both count forms are exact.
- `tools/hjxl_reference.py --var-dct-dc-tokens-npy ...`,
  `--var-dct-ac-metadata-tokens-npy ...`, `--var-dct-ac-tokens-npy ...`, and
  `--var-dct-ac-strategy-npy ...` export native searched-VarDCT arrays for one
  AC group. `--var-dct-frame-bin ...` and `--var-dct-codestream-bin ...`
  serialize those same arrays. The exact-Q8 16x16 impulse regression uses this
  seam to prove nonzero DC/AC RTL traces and final codestream parity.
- `tools/hjxl_reference.py --default-ac-strategy-npy ...` writes the matching
  default DCT-first strategy map.
- `tools/hjxl_reference.py --raw-quant-field-npy ...`,
  `--libjxl-ac-strategy-npy ...`, `--ytox-map-npy ...`, and `--ytob-map-npy ...`
  write libjxl-tiny's adjusted raw quant field, searched AC strategy, and CFL
  maps for small whole-frame fixtures. These remain float-reference artifacts
  for measuring the fixed Q12 strategy path and future downstream non-DCT
  integration, not claims of full RTL parity.
- `tools/hjxl_reference.py --aq-hf-modulation-q24-csv ...`,
  `--aq-color-modulation-q24-csv ...`, and
  `--aq-gamma-modulation-q24-csv ...` reconstruct the corresponding private
  float32 functions, compare them with the real calls, prove full-frame equality
  with encoder-shaped stitched calls, and emit the Q12/Q16/Q24 fixed seams used
  by the prepared and RGB-connected tests. The color artifact records Q8
  distance and covers its exact early-return behavior above distance 4. The
  gamma artifact covers the clamped Q12 inverse-ratio and normalized Q20 log
  seams used by the 128-cycle prepared block.
- `tools/hjxl_reference.py --aq-final-map-q24-csv ...` reconstructs the final
  float32 power/scale/damping operation, proves equality with the real completed
  AQ map, and records Q24 fixed map/scalar seams plus reference and fixed
  raw-quant bytes for native, Q12-rounded, and Q8-input paths. Its Q8-input byte
  column is also combined with the independently exported scheduler decision to
  verify rectangle adjustment. It is the oracle for `FrameAqFinalMapTraceStage`,
  the pre-strategy `FrameRawQuantFieldTraceStage` branch, and the adjusted
  focused route.
- `tools/hjxl_reference.py --dct-only-aq-map-q24-csv ...` exports the
  all-DCT AQ map and inverse global AC scale as unsigned Q24 values alongside
  libjxl-tiny's reference raw-quant bytes and the same fixed-point conversion
  computed in software. Use it as the exact oracle for
  `FramePreparedAqRawQuantTraceStage`; this narrow seam remains useful even
  though the upstream RGB gamma output is now connected through final
  modulation to the same converter.
- `tools/hjxl_reference.py --dct-only-quantized-ac-npy ...`,
  `--dct-only-num-nonzeros-npy ...`, `--dct-only-num-nonzeros-map-npy ...`, and
  `--dct-only-quant-dc-npy ...` write libjxl-tiny quantization outputs while
  forcing the default all-8x8-DCT strategy. Use these to validate the current
  transform/quantization direction before rectangular strategy search is
  implemented.
- `tools/hjxl_reference.py --dct-only-ac-metadata-tokens-npy ...` writes
  libjxl-tiny AC-metadata token rows for the default all-DCT strategy using the
  prepared raw-quant and CFL maps. Use this to validate prepared metadata token
  schedulers instead of assuming fixed raw-quant/scalar-CFL metadata.
- `tools/hjxl_reference.py --dct-only-dc-tokens-npy ...` and
  `--dct-only-ac-tokens-npy ...` write the matching adaptive all-DCT logical
  DC and AC token rows. Together with `--dct-only-ac-metadata-tokens-npy` and
  `--default-ac-strategy-npy`, these are the exact libjxl-tiny token oracle for
  the all-8x8-DCT path with real adaptive quantization and CFL maps.
- `tools/hjxl_reference.py --dct-only-prepared-blocks-json ...` writes a
  structured per-block oracle for the prepared `DctOnlyQuantizeBlock` boundary:
  Q16 coefficients, raw quant, AC/DC scale reciprocals, CFL scalars, image
  block geometry, 64x64 tile geometry, and libjxl-tiny's expected quantized
  AC/DC/nonzero outputs. The converter accepts legacy Q12 fixtures, but current
  generated fixtures use `inputs.coefficient_fraction_bits = 16`,
  `inputs.coefficients_q`, and explicit tile fields. When per-block
  `block_x`/`block_y` and `tile_x`/`tile_y` fields are present, the converter
  validates them against raster order and the 64x64 CFL tile grid.
- `tools/hjxl_prepared_blocks.py --prepared-json ... --input-csv ...
  --input-stream-csv ... --axi-lite-csv ... --manifest-json ...
  --expected-trace-csv ...` converts those prepared-block fixtures into
  structured simulator inputs, packed prepared-DCT stream `data,last` inputs,
  shared-register-map AXI-Lite writes, replay manifests, and expected
  quantization trace CSVs. `--validate-manifest` checks stream word count, block
  count, final-only TLAST, CSV columns, AXI-Lite strobes/register values, and
  coefficient fractional scale against source prepared-JSON metadata and stream
  word contents when present. Prepared-block JSON integer fields must be actual
  integers; floating-point JSON numbers are rejected instead of being truncated.
  Fields that pack into narrower RTL inputs are range-checked before stream
  generation: `quant` is `uint8`, `scaleQ16` is `uint16`, CFL multipliers are
  signed 8-bit, and coefficients are signed 32-bit. Explicit `--flags`
  overrides accept decimal or `0x` syntax and must fit the 32-bit AXI-Lite
  register; `--fixed-raw-quant` must fit the 8-bit RTL field.
  `--target-variant estimated-cfl` records the estimated-CFL prepared target in
  the manifest, so generated headers, bundles, and replay plans name
  `HjxlPreparedCflDctAxiStreamCore`, `HjxlPreparedCflDctAxiLiteStreamCore`, and
  `HjxlKv260PreparedCflDctTop`; the default `direct` variant names the existing
  fixed-map prepared-DCT stream/control/KV260 shells.
  Malformed integer/boolean CSV fields fail with line-specific diagnostics
  before replay. Use `tools/hjxl_manifest_header.py` on the saved manifest to
  generate the matching C host constants, target macros, and control-write
  table, and `tools/hjxl_stream_buffer.py` to generate little-endian stream
  payload bytes plus an optional one-byte-per-word TLAST sidecar.
- `tools/hjxl_reference.py --dct-only-frame-bin ...` and
  `--dct-only-codestream-bin ...` serialize the adaptive all-DCT logical tokens
  through libjxl-tiny's entropy optimizer and bitstream writer. Use these as
  the byte oracle once RTL token streams have been compared against the
  adaptive all-DCT token arrays.
- `tools/hjxl_quant_trace_to_prepared_tokens.py --trace-csv ... --width ...
  --height ... --dc-csv ... --ac-csv ... --prepared-json ...` converts
  `QuantDc`, `QuantizedAc`, and `NumNonzeros` trace rows into the prepared DC
  and AC CSV streams consumed by `FramePreparedTokenTraceStage`, and can also
  emit the prepared-token JSON fixture consumed by
  `tools/hjxl_prepared_token_inputs.py`. It validates complete per-block
  quantization records, rejects `QuantDc`/`QuantizedAc` values outside the
  signed-32 `StageTrace.value` range, emits DC samples in token order (Y plane,
  X plane, then B plane), preserves AC blocks in raster order with X/Y/B channel
  rows, and carries frame/tile/block geometry in the JSON output.
- `tools/hjxl_reference.py --distance-params-json ...` writes the libjxl-tiny
  distance parameters used to populate `DistanceParamsLookup`. Regenerate this
  when updating lookup entries or when the local reference changes. The JSON
  also includes the reciprocal for `--fixed-raw-quant`, defaulting to raw quant
  5.
- `tools/hjxl_reference.py --fixed-dct-only-dc-tokens-npy ...`,
  `--fixed-dct-only-ac-metadata-tokens-npy ...`, and
  `--fixed-dct-only-ac-tokens-npy ...` write logical `(context, value)` token
  streams for the current fixed all-DCT assumptions: `--fixed-raw-quant`
  defaulting to 5, `--fixed-ytox`/`--fixed-ytob` defaulting to zero scalar CFL
  maps, all ordinary DCT blocks, and the requested distance's libjxl-tiny scale
  parameters. The CFL flags are signed 8-bit values. Use these to validate
  token ordering and context formulas against libjxl-tiny; exact RTL value
  parity still depends on closing the fixed-point transform/quantization gap
  for nontrivial images. The current host-boundary Scala regression uses a
  prepared-token gradient fixture with nonzero AC coefficients, so the exact
  token path is covered after libjxl-tiny quantized DC/AC data has been
  prepared.
- `tools/hjxl_reference.py --fixed-dct-only-raw-quant-field-npy ...`,
  `--fixed-dct-only-ytox-map-npy ...`, and
  `--fixed-dct-only-ytob-map-npy ...` write fixed metadata-grid oracles for the
  focused raw-quant and fixed-CFL trace routes, using the same
  `--fixed-raw-quant`, `--fixed-ytox`, and `--fixed-ytob` values as the fixed
  token oracles. These are the right comparator inputs for the explicit fixed
  branch of `FrameAdjustedRawQuantFieldTraceStage` and for
  `FrameCflMapTraceStage` captures. The adaptive `--dct-only-*` metadata grids
  instead serve as software references for the connected RGB AQ/CFL paths and
  future downstream non-DCT integration.
- `tools/hjxl_reference.py --fixed-dct-only-prepared-token-inputs-json ...`
  writes the prepared DC sample stream and prepared AC raster blocks consumed
  by `FramePreparedTokenTraceStage` under the same fixed all-DCT assumptions,
  including the selected fixed raw-quant and scalar CFL metadata. Use this when
  building trace-driver fixtures for the combined prepared-token RTL boundary.
- `tools/hjxl_prepared_token_inputs.py --prepared-json ... --dc-csv ...
  --ac-csv ...` converts that JSON into simple simulator input CSV streams for
  `FramePreparedTokenTraceStage`. It requires the exact
  `hjxl.fixed_dct_only_prepared_token_inputs.v1` format string, then validates
  integer-valued image geometry, optional fixed raw-quant and signed 8-bit
  scalar CFL metadata, signed-32 DC samples, AC coordinates, nonzero counts,
  and signed-32 coefficients; image block/tile geometry; and optional AC block
  raster/tile coordinates before writing CSVs. Floating-point JSON numbers are
  rejected for integer fields instead of being truncated.
- `tools/hjxl_reference.py --fixed-dct-only-frame-bin ...` and
  `--fixed-dct-only-codestream-bin ...` serialize those fixed logical token
  streams through libjxl-tiny's entropy optimizer and bitstream writer. These
  are host-side oracle artifacts for the initial hardware/software split: RTL
  emits traceable tokens, while software can consume tokens to build frame
  sections and a bare `.jxl` codestream.
- `tools/hjxl_reference.py --token-input-dc-tokens-npy ...`,
  `--token-input-ac-metadata-tokens-npy ...`, `--token-input-ac-tokens-npy ...`,
  and `--token-input-ac-strategy-npy ...` assemble frame/codestream bytes from
  precomputed logical token arrays. This is the first explicit software
  consumer boundary for future RTL trace dumps; current smoke checks verify it
  reproduces the direct fixed-token oracle bytes exactly. Token arrays must have
  shape `(n, 2)` with nonnegative `uint32` context/value entries, and the AC
  strategy grid must be a 2D integer array in the `uint8` range; malformed
  artifacts are rejected before bitstream assembly.
- `tools/hjxl_trace_tokens.py` converts `StageTrace` CSV dumps with
  `stage,group,index,value` columns into the token-input arrays consumed by
  `tools/hjxl_reference.py`. For token stages, `group` must be a contiguous
  token ordinal and output rows are nonnegative `uint32` `(context, value)`
  pairs. For `AcStrategy`, the trace `index` is the raster block ordinal and
  the tool reshapes it with positive supplied image width and height. The same
  helper can write metadata-grid arrays for oracle comparison:
  `--raw-quant-field-npy` produces a `uint8` 8x8-block grid, and
  `--ytox-map-npy` / `--ytob-map-npy` produce `int8` 64x64 tile grids. Grid
  outputs reject non-positive dimensions before writing NumPy artifacts.
- `tools/hjxl_stream_trace.py --stream-csv ... --trace-csv ...` converts
  packed `HjxlAxiStreamCore` trace stream captures back into `StageTrace` CSV.
  It accepts `data,last` or `tdata,tlast` input columns, unpacks
  `{value,index,group,stage}`, sign-extends the value field, and can enforce a
  single-frame final TLAST with `--require-final-last`. It also accepts
  little-endian binary TDATA captures through `--stream-bin`, with optional
  one-byte-per-word TLAST sidecars through `--last-bin`; the default binary word
  size is 11 bytes for the current 88-bit trace stream. Use
  `--stream-word-bytes 16` for default `HjxlKv260PreparedDctTop` captures.
  `StreamTraceToolSpec`
  covers signed value decoding, accepted column aliases, TLAST rejection, the
  handoff into `tools/hjxl_trace_tokens.py`, direct `--stream-csv` and
  `--stream-bin` token extraction, and packed-stream
  `tools/hjxl_trace_to_codestream.py` validation.
- `tools/hjxl_rgb_stream.py --pfm ... --stream-csv ...` converts linear RGB
  PFM files into raster `data,last` input stream CSVs for `HjxlAxiStreamCore`
  and `HjxlAxiLiteStreamCore`. It restores top-to-bottom raster order from the
  PFM bottom-row-first payload, quantizes floats to signed Q8 by default, packs
  R/G/B into consecutive component fields, and asserts final TLAST only on the
  last pixel. With `--axi-lite-csv`, it also emits the matching
  `address,data,strb` control writes for the shared AXI-Lite register map,
  including PFM-derived `xsize`/`ysize` and requested route flags.
  `--trace-route` records the compile-time focused route, if any, separately
  from those runtime flags. With `--manifest-json`, it records the generated
  artifacts, image dimensions, stream packing, trace-route metadata, register
  map, status/control bits, and selected config values for host runners. Shared
  AXI-Lite scalar overrides must fit the 32-bit control registers, and
  `--fixed-raw-quant` must fit the RTL's 8-bit raw quant field; `--fixed-ytox`
  and `--fixed-ytob` must fit signed 8-bit CFL fields.
  Prepared-DCT manifests use `prepared-dct-quantize-token` as the route name for
  the direct prepared stream boundary, with no `TraceStage` id. This is the
  current host-side fixture path for RGB
  stream-shell simulation before DMA buffers or KV260 drivers exist.
  `tools/hjxl_manifest_header.py --manifest-json ... --header ...` turns the
  generated manifest into C constants, target interface/variant/shell and trace-route
  macros, stream
  byte-count macros, input keep-mask and enforcement macros, frame block/tile
  geometry macros, supported-distance Q8 constants, AXI-Lite address/data/strobe
  width macros, trace packing shift/mask and byte-offset macros, capture width
  macros, trace `TKEEP` mask, an inline little-endian
  packed-trace decoder returning a generated `*_stage_trace_t` with portable
  sign extension for the signed trace value, and an ordered AXI-Lite write
  table for host-driver stubs. The
  generated header includes
  C11/C++ static assertions for stream byte-count, input keep-mask, AXI-Lite
  strobe width, trace field shifts/byte offsets, supported trace value width,
  trace byte-count, trace keep-mask, and write-table-length consistency so
  stale host handoff artifacts fail early. Malformed manifest integer fields
  fail with field-specific diagnostics before header generation.
  `tools/hjxl_stream_buffer.py --manifest-json ... --stream-bin ...` turns it
  into little-endian stream payload bytes for deterministic replay. Malformed
  manifest integer fields such as `stream.word_count` and RGB
  `stream.pixel_bits` fail with field-specific diagnostics before payload
  generation; floating-point JSON numbers are rejected instead of truncated.
  `tools/hjxl_host_bundle.py --manifest-json ...
  --output-dir ... --name ...` is the one-shot command that writes both
  artifacts plus a bundle index. The bundle includes a local manifest copy and
  replay stream/control CSV copies, with bundle-relative artifact paths in the
  index so the directory can be moved as a unit. Relative paths in bundle
  indexes, bundle-local manifests, and saved replay plans resolve against the
  containing index/manifest/plan directory first; cwd-relative lookup is only a
  legacy fallback when the local artifact is absent. The index's
  `target` block distinguishes RGB AXI-stream bundles for `HjxlAxiLiteStreamCore`
  from prepared-DCT bundles for `HjxlPreparedDctAxiLiteStreamCore` and the
  current `HjxlKv260PreparedDctTop`, and its `distance` block records supported
  Q8 distances plus the fallback Q8 value. Prepared-DCT bundles also record
  `stream.coefficient_fraction_bits` so host runners can assert the coefficient
  scale before DMA. `stream.input_keep_mask` records the full-byte AXI-stream
  keep mask for one input payload word. The index's `stream.byte_count` is the
  intended host/DMA transfer byte count for the input payload. Its `frame`
  block records the configured image dimensions, padded 8x8 block grid, block
  count, and 64x64 tile grid used by CFL metadata. Its `trace`
  block records packed trace geometry, field shifts/masks, byte offsets, trace
  `TKEEP` mask, and default capture word size, its `axi_lite` block records control-plane address/data/strobe
  widths, programmed write count, and AXI-Lite register map, and SHA-256 checksums cover the
  bundle-local artifacts. Its
  `--validate-bundle` mode re-reads the source manifest, runs the
  format-specific manifest validator, and checks the generated header, target
  metadata, distance metadata, frame metadata, trace metadata, AXI-Lite metadata,
  stream payload, optional TLAST sidecar, copied AXI-Lite control CSV, stream metadata,
  AXI-Lite write count, and artifact checksums before host replay. Malformed
  bundle metadata integer fields fail with field-specific diagnostics, and
  malformed bundle-local control CSV integer fields fail with line-specific
  diagnostics; floating-point JSON numbers are rejected instead of truncated.
  `--no-last-bin` is valid for host paths that
  derive TLAST from transfer length; final-TLAST semantics are still validated
  through the bundle-local stream CSV. `--describe-bundle` validates the bundle
  and emits `hjxl.host_replay_plan.v1` JSON with bundle-relative and absolute
  resolved stream payload paths, diagnostic stream/control CSV paths, DMA byte
  count, input `TKEEP` mask, optional TLAST sidecar paths, prepared-DCT
  coefficient fractional bits when applicable, target interface metadata,
  frame dimensions, padded 8x8 block grid, and 64x64 tile grid,
  AXI-Lite address/data/strobe
  geometry, ordered AXI-Lite writes, status bits, supported-distance Q8 values
  plus fallback distance, trace packing geometry including field shifts, byte
  offsets, and masks, trace `TKEEP` mask, default capture word bytes, and
  artifact checksums for early host bring-up scripts.
  `bundle_index_resolved` records the canonical
  source bundle-index path. `--replay-plan-json ...` writes the same validated
  plan to a file during bundle generation or `--describe-bundle` for scripts
  that should not parse stdout.
  `--validate-replay-plan ...` regenerates the plan from the referenced bundle
  index and fails if the saved file is stale. Malformed replay-plan numeric
  fields fail with field-specific diagnostics before replay, and floating-point
  JSON numbers are rejected instead of truncated. When present,
  `bundle_index_resolved` is used for validation so saved plans can live outside
  the bundle directory; older plans without it resolve `bundle_index` relative
  to the saved plan file, with cwd-relative lookup only as a legacy fallback.
  Older v1 bundles or replay plans without `target`, `distance`, `frame`,
  `status_bits`, `trace`, or AXI-Lite geometry/register-map metadata still validate, but
  contradictory metadata is rejected.
  `tools/hjxl_replay_capture.py --replay-plan-json ... --preflight-only ...`
  validates the same replay-plan expectations and can emit
  `hjxl.replay_preflight_summary.v1` JSON with replay artifact paths, resolved
  payload paths, and SHA-256 checksum metadata before any trace capture data
  exists. Use the trace-route name, stage, and focused expectation flags to
  reject replay plans generated for the wrong all-route/focused shell before
  capture assembly.
  `tools/hjxl_replay_capture.py --replay-plan-json ... --stream-bin ...
  --last-bin ... --codestream-bin ... --expect-codestream-bin ...` is the
  replay-plan-aware post-capture checker. It validates the plan, derives width,
  height, requested distance, and hardware-effective distance from the AXI-Lite
  writes, and feeds captured trace rows into the same libjxl-tiny-backed
  assembler as `hjxl_trace_to_codestream.py`. Unsupported `distanceQ8` values
  assemble with the RTL's distance-1 fallback by default; use
  `--require-supported-distance` to reject that fallback.
  Binary captures default to the plan's trace metadata: 16-byte words for the
  KV260 wrapper's 128-bit trace output today. `--stream-word-bytes 11` handles
  raw packed 88-bit traces. `--expect-target-interface`,
  `--expect-target-variant`, `--expect-target-stream-shell`,
  `--expect-target-controlled-shell`, and `--expect-target-kv260-top` let host
  scripts reject captures prepared for the wrong shell or prepared-DCT target
  variant before assembly. `--expect-target-no-kv260-top` asserts that RGB
  replay plans keep `target.kv260_top` null. `--expect-target-input-stream` and
  `--expect-target-input-keep-enforced` cover the target input payload
  description and whether the selected shell requires full-word input `TKEEP`.
  `--expect-frame-xsize`, `--expect-frame-ysize`,
  `--expect-frame-x-blocks`, `--expect-frame-y-blocks`,
  `--expect-frame-padded-xsize`, `--expect-frame-padded-ysize`, and
  `--expect-frame-block-count`, `--expect-frame-x-tiles`,
  `--expect-frame-y-tiles`, and `--expect-frame-tile-count` let host runners
  reject replay plans for the wrong image, padded block grid, or CFL tile grid
  before assembly.
  `--expect-status-protocol-error-bit`, `--expect-status-busy-bit`,
  `--expect-status-overflow-bit`, `--expect-status-unsupported-distance-bit`,
  and `--expect-control-clear-protocol-error-bit` let host runners reject
  status/control bit positions that do not match their poll/clear logic.
  `--expect-trace-stage-bits`, `--expect-trace-group-bits`,
  `--expect-trace-index-bits`, `--expect-trace-value-bits`,
  `--expect-trace-stage-shift`, `--expect-trace-group-shift`,
  `--expect-trace-index-shift`, `--expect-trace-value-shift`,
  `--expect-trace-stage-byte-offset`, `--expect-trace-group-byte-offset`,
  `--expect-trace-index-byte-offset`, `--expect-trace-value-byte-offset`,
  `--expect-trace-stage-mask`, `--expect-trace-group-mask`,
  `--expect-trace-index-mask`, `--expect-trace-value-mask`,
  `--expect-trace-packed-bits`, `--expect-trace-packed-bytes`,
  `--expect-trace-keep-mask`, and `--expect-capture-word-bytes` let host
  runners reject the wrong trace field layout, output `TKEEP`, or capture word
  geometry before assembly.
  `--expect-input-word-count`, `--expect-input-data-bits`,
  `--expect-input-word-bytes`,
  `--expect-input-keep-mask`, `--expect-input-byte-count`, and
  `--expect-input-coefficient-fraction-bits` let host runners reject payloads
  with the wrong input geometry, bit width, AXI-stream keep mask, or
  prepared-DCT coefficient scale. `--expect-axi-lite-addr-bits`,
  `--expect-axi-lite-data-bits`, `--expect-axi-lite-strb-bits`, and
  `--expect-axi-lite-write-count` let host runners reject a mismatched
  control-plane width or programmed write count. `--expect-reg-status-control`,
  `--expect-reg-xsize`, `--expect-reg-ysize`, `--expect-reg-distance-q8`,
  `--expect-reg-fixed-point-scale`, `--expect-reg-fixed-inv-qac-q16`,
  `--expect-reg-fixed-raw-quant`, `--expect-reg-flags`,
  `--expect-reg-fixed-ytox`, and `--expect-reg-fixed-ytob` let host runners
  reject replay plans with unexpected AXI-Lite register offsets. Hex-capable
  numeric expectation flags reject malformed values with argument-specific
  diagnostics. Older v1 replay plans without
  `frame`, `status_bits`, or `trace` blocks still validate and use the current
  group/value width and KV260 capture defaults when trace metadata is missing.
  `--summary-json` writes a `hjxl.capture_summary.v1` report with replay target
  and trace-route metadata, frame metadata, status/control bit positions, replay
  input-stream metadata including input data bit width and input keep mask,
  AXI-Lite geometry/register map, trace field shifts/masks and byte offsets,
  replay artifact paths, SHA-256 checksum metadata, capture geometry, token
  counts, and byte counts for host logs. Prepared summaries include
  `input_stream.coefficient_fraction_bits` when present in the replay plan.
  `tools/hjxl_host_metadata_smoke.py` is the fast Python-only regression for
  this handoff contract. It creates tiny RGB and one-block Q16 prepared
  fixtures, generates manifests and host bundles, validates replay plans,
  checks legacy tolerance for omitted metadata, rejects contradictory keep-mask,
  coefficient-scale, trace, and AXI-Lite metadata, and exercises the
  replay-capture input-stream, trace-geometry, target-variant, and AXI-Lite
  expectation guards. It checks both direct prepared-DCT and estimated-CFL
  prepared-DCT target propagation through generated headers, host bundles,
  replay plans, and preflight mismatch diagnostics.
  It uses nonzero signed fixed-CFL config values (`fixed_ytox = -7`,
  `fixed_ytob = 11`) so manifest, AXI-Lite CSV, replay-plan write-table, and
  generated-header paths keep signed control-register handling covered.
  `HjxlAxiStreamCoreSpec` drives that generated stream into RTL and decodes the
  resulting packed trace stream, and `HjxlAxiLiteStreamCoreSpec` repeats the
  flow using the generated AXI-Lite CSV to program the controlled shell, so both
  host-side stream directions and the control-plane handoff are covered
  together. `tools/hjxl_rgb_stream.py --validate-manifest ...` validates saved
  bundles by checking stream row count, final-only TLAST, CSV columns, AXI-Lite
  strobes, and register values against the manifest; malformed integer/boolean
  CSV fields fail with line-specific diagnostics before replay.
- `tools/hjxl_trace_to_codestream.py --trace-csv ... --width ... --height ...
  --frame-bin ... --codestream-bin ...` is the direct host assembler for RTL
  token traces. It performs the same token extraction as `hjxl_trace_tokens.py`,
  then uses libjxl-tiny's entropy optimizer and bitstream writer to serialize a
  frame and bare codestream from those logical tokens. It accepts repeated
  `--trace-csv`, `--stream-csv`, and `--stream-bin` inputs, so packed stream
  captures can feed the byte assembler without an intermediate decoded CSV.
  `--expect-frame-bin` and `--expect-codestream-bin` make it a byte-parity
  checker for RTL bring-up.
  `TraceToCodestreamToolSpec` covers multi-file trace input, packed stream
  byte-parity input, expected-byte mismatch diagnostics, and malformed trace
  rejection for this CLI.
  For replay-plan-based host runs, use `hjxl_replay_capture.py` so dimensions
  and distance come from the validated control-plane bundle instead of a manual
  command line.
- `tools/hjxl_compare_tokens.py` compares converted token arrays against oracle
  arrays. It is exact by default for stream length, contexts, values, and AC
  strategy/raw-quant/CFL metadata grid entries; use its value-delta mode only
  as a token-value diagnostic while closing fixed-point quantization
  differences, not as a parity claim. Token arrays must contain integer
  nonnegative `uint32` `(context, value)` pairs. Metadata-grid comparisons remain
  exact, and metadata arrays must stay within their declared `uint8`/`int8`
  ranges before comparison.
  `TokenCompareToolSpec` covers exact matches, mismatch diagnostics, and
  malformed token-array/grid rejection for this CLI.
- `FixedDctOnlyTokenAssemblySpec` exercises the current host-boundary chain:
  libjxl-tiny prepared-token JSON from `tools/hjxl_reference.py`, simulator
  input CSVs from `tools/hjxl_prepared_token_inputs.py`, RTL traces from
  `FramePreparedTokenTraceStage`, token arrays from `tools/hjxl_trace_tokens.py`,
  and final frame/codestream bytes from the host assembler path in
  `tools/hjxl_reference.py`. It uses nonzero scalar fixed CFL metadata and also
  checks `tools/hjxl_trace_to_codestream.py` against the same extracted token
  arrays and byte oracle. This proves the prepared-token software boundary, not
  full RGB-input token parity:
  `FrameDctOnlyDcTokenTraceStage` and `FrameDctOnlyAcTokenTraceStage` still use
  the approximate fixed-point RGB/XYB/DCT path.
- `FramePreparedDctOnlyQuantizeTraceStageSpec` covers the prepared-DCT
  quantization scheduler against libjxl-tiny prepared-block fixtures. It keeps
  stage/group/index/value comparison exact at the Q16 prepared-coefficient
  boundary, then verifies the observed RTL quantization trace can be projected
  into prepared-token DC/AC CSV inputs and a prepared-token JSON fixture without
  changing the trace data. The JSON path is round-tripped through
  `tools/hjxl_prepared_token_inputs.py` before driving
  `FramePreparedTokenTraceStage` through complete DC, strategy, metadata, and
  AC token trace emission. It also checks
  `FramePreparedDctOnlyQuantizeTokenTraceStage` against that staged handoff and
  against libjxl-tiny token-array oracles for DC, AC-metadata, AC, and strategy
  streams, including final-beat `traceLast` framing. The same regression
  converts the direct wrapper's token `StageTrace` output through
  `tools/hjxl_trace_tokens.py`, uses
  `tools/hjxl_compare_tokens.py` for exact token-array comparison, and verifies
  the host assembler output matches the direct libjxl-tiny DCT-only frame and
  codestream bytes. It also feeds the direct wrapper `StageTrace` CSV through
  `tools/hjxl_trace_to_codestream.py` and checks those one-step bytes against
  the same oracle.
- `FramePreparedAcMetadataTokenTraceStageSpec` covers the standalone prepared
  AC-metadata scheduler with two-tile fixtures so CFL residual prediction and
  raw-quant residual contexts are tested outside the single-tile integrated
  fixture. One case is synthetic and one drives the stage from libjxl-tiny
  prepared-block metadata and compares against `ac_metadata_tokens`.

## Accuracy Policy

Use stage-tolerant parity:

- Compare fixed-point approximations of floating stages with explicit
  tolerances.
- Require exact equality after rounding or quantization.
- Require exact equality for token streams when a stage claims token parity.
- Do not optimize resource use until the relevant trace stage is stable.

## KV260 Path

The local machine has Verilator available, but Vivado/Vitis are not assumed.
The immediate FPGA deliverable is generated SystemVerilog plus a reproducible
out-of-context synthesis gate. `HjxlKv260PreparedDctTop` is the frozen first
wrapper for that work: AXI4-Stream carries prepared DCT-only block records and
trace output, while AXI4-Lite programs frame configuration and clears stream
protocol errors. `fpga/vivado/synth.tcl` targets
`xck26-sfvc784-2LV-c` at 200 MHz, validates the generated `filelist.f`, and
writes checkpoint, utilization, timing, constraint, methodology, and clock
reports. Its `--preflight-only` mode runs under ordinary `tclsh`, so source-set
and top-module errors can be caught without AMD tools. The 70% per-resource
budget reserves integration headroom for the later MPSoC block design.

This remains an out-of-context contract: all AXI interfaces are assumed
synchronous to `ap_clk`, with one nanosecond of input and output integration
delay and 0.2 ns clock uncertainty. The lower-level `HjxlAxiLiteStreamCore`
remains the RGB-input controlled shell for simulation and future end-to-end
hardware work. Place-and-route, host drivers, DMA packaging, XSA/bitstream
generation, timing closure for the integrated design, and board validation
still require an environment with AMD tools installed.

`HjxlPreparedDctThroughputSpec` provides the complementary simulation-cycle
baseline for this target. Under continuous source/sink traffic, the direct
stream currently measures 342 cycles for one zero 8x8 block, 347 cycles when
one AC coefficient is nonzero in each channel, and 611 cycles for two zero
blocks. A parameterized 72x72 profiling elaboration additionally measures
22,493 cycles for an 81-block/four-tile zero frame and 33,620 cycles when all
63 AC positions in every channel and block are nonzero. See
`docs/performance.md` for phase definitions, exact token counts, and
limitations. These numbers isolate core behavior and do not include AXI-Lite
setup, DMA, memory, clock crossings, or physical timing; the larger profiling
configuration is not the frozen default KV260 elaboration.
