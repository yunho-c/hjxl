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

Early RTL work should stop at traceable stage outputs and tokens. Software can
consume those tokens to produce final codestream bytes until entropy coding and
bit assembly are worth moving into hardware.

## Hardware Contracts

Internal Chisel modules should use `Decoupled` streams. The first public-facing
contract is intentionally simple:

- `FrameConfig` carries image size, distance, fixed-point scale, and feature
  flags. `tokenSelect` chooses which logical token substream a token trace
  emits at the top level: currently `Dc` or `AcMetadata`.
- `RgbPixel` carries fixed-point linear RGB samples and image coordinates.
- `StageTrace` carries a stage id, group id, element index, and value.
- `HjxlCore` is the integration shell where stage modules will be wired.

Use these contracts for simulation and trace extraction before adding KV260
AXI wrappers.

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
  `FrameDctOnlyQuantizeTraceStage`; when `enableTokenize` is also true, it
  uses `tokenSelect` to route into `FrameDctOnlyDcTokenTraceStage`,
  or `FrameDctOnlyAcMetadataTokenTraceStage`. When only `enableQuant` is true,
  it routes into `FrameAcStrategyTraceStage`.
  The optional compile-time `traceRoute` constructor argument restricts
  elaboration to one route for focused simulation while keeping the same public
  IO shape. Use it for tests and route-specific experiments; the default
  constructor still instantiates the all-route shell.
- `tools/hjxl_reference.py --input-padded-npy ...` writes the matching
  libjxl-tiny padded-input oracle artifact for small fixtures.
- `RgbToXybApprox` is a standalone Q8-to-Q12 fixed-point approximation of
  libjxl-tiny XYB conversion. It keeps Q10 precision for the mixed absorbance
  values and linearly interpolates a Q8 cube-root lookup table before emitting
  Q12 XYB samples. `tools/hjxl_reference.py --xyb-npy ...` writes the
  floating-point oracle artifact used to tune and validate future stage tests.
- `DistanceParamsLookup` is the first hardware boundary for libjxl-tiny's
  distance-derived scalar parameters. It supports common Q8 distances
  `64`, `128`, `256`, `512`, `1024`, and `2048`; unsupported values currently
  fall back to distance 1. It emits the global AC scale, quantized DC scale,
  fixed raw-quant-5 AC reciprocal, X/Y/B inverse DC factors, X quant-matrix
  multiplier, and EPF iteration count generated from the current libjxl-tiny
  Python formula.
- `Dct8Approx` is a standalone Q12 1D DCT-8 primitive matching libjxl-tiny's
  recursive scaled-DCT structure within fixed-point tolerance. It is the kernel
  for the future 8x8 and rectangular transform stages.
- `Dct8x8Approx` composes the 1D kernel into libjxl-tiny's scaled 8x8 DCT
  coefficient layout. It applies the 1/8 scale after each dimension and emits
  the transposed canonical order consumed by later quantization work.
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
  matching reciprocal.
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
- `FrameDctOnlyQuantizeTraceStage` is the first frame-level quantized-block
  scheduler. It buffers and pads RGB like `FrameDct8x8TraceStage`, converts
  each raster block through approximate XYB and DCT, then emits 198 records per
  block: 192 `QuantizedAc`, three `QuantDc`, and three `NumNonzeros` records.
  This stage deliberately still uses one global adjusted raw quant value and
  zero tile CFL multipliers, but it now takes distance-derived AC/DC scalar
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
  current fixed all-DCT path. It emits zero CFL tile-map tokens, all-DCT
  strategy tokens, fixed raw-quant-field tokens, and fixed block-metadata
  literals in libjxl-tiny order. Token traces use
  `trace.stage = AcMetadataTokens`, `trace.group = token ordinal`,
  `trace.index = context`, and `trace.value = packed residual or literal`.
- `FrameDctOnlyAcNonzeroTokenTraceStage` emits the mandatory nonzero-count
  prefix tokens of AC coefficient tokenization for the current fixed all-DCT
  path. It predicts each count from top/left block nonzero history, applies
  libjxl-tiny's nonzero bucket context formula for ordinary DCT, and emits
  `trace.stage = AcTokens`, `trace.group = token ordinal`, `trace.index =
  context`, and `trace.value = nonzero count`. It is currently a standalone
  frame stage rather than an `HjxlCore` route, and it does not yet emit
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
  order, buffers their X/Y/B quantized AC coefficients and nonzero counts,
  predicts nonzero counts from west/north block history, and emits the full
  `AcTokens` stream.
- `FramePreparedTokenTraceStage` combines the exact prepared DC and AC token
  schedulers with fixed all-DCT AC strategy and AC-metadata trace generation.
  Its input boundary is prepared quantized DC samples plus prepared quantized AC
  blocks; its output boundary is the same `StageTrace` CSV shape consumed by
  `tools/hjxl_trace_tokens.py` and the host bitstream assembler.
- `FrameDctOnlyAcTokenTraceStage` is the first complete RGB-input standalone
  frame scheduler for the fixed all-DCT AC token stream. It buffers/pads RGB,
  computes the same fixed DCT-only quantized block data as
  `FrameDctOnlyQuantizeTraceStage`, predicts nonzero counts from west/north
  block history, then emits each block's full Y/X/B AC token stream through
  `DctOnlyAcBlockTokenTraceStage`. It is kept out of `HjxlCore` because even
  route-specific Verilator top-level compilation is impractical while this
  scheduler is still large and trace-only.
- `HjxlAcTokenCore` is a compile-time top wrapper around
  `FrameDctOnlyAcTokenTraceStage`. Use `sbt 'runMain hjxl.ElaborateAcTokens'`
  when SystemVerilog for the full AC-token path is needed without instantiating
  every other trace path in `HjxlCore`.
- `ElaboratePreparedDcTokens` and `ElaboratePreparedAcTokens` generate
  standalone SystemVerilog for the exact prepared-token boundaries. Use these
  when integrating or timing the token path after quantized DC/AC planes are
  supplied by a future quantization scheduler.
- `ElaboratePreparedTokens` generates the combined prepared fixed-token top and
  is the preferred RTL artifact for the current host bitstream assembly
  boundary.
- Full frame-level quantized-block parity remains open: AQ/CFL map plumbing,
  distance parameter generation, dynamic reciprocal scaling, rectangular
  strategy scheduling, and comparison against `tools/hjxl_reference.py`
  DCT-only whole-frame artifacts are still separate work. Full token-to-codestream
  parity also still needs entropy table optimization and bitstream assembly.
- `FrameAcStrategyTraceStage` emits one `AcStrategy` value per padded 8x8 block,
  currently always ordinary DCT with the libjxl-tiny encoding
  `(raw_strategy << 1) | is_first_block == 1`. This matches the current
  DCT-only RTL transform path; it does not yet implement libjxl-tiny's 16x8/8x16
  search.
- `tools/hjxl_reference.py --default-ac-strategy-npy ...` writes the matching
  default DCT-first strategy map.
- `tools/hjxl_reference.py --raw-quant-field-npy ...`,
  `--libjxl-ac-strategy-npy ...`, `--ytox-map-npy ...`, and `--ytob-map-npy ...`
  write libjxl-tiny's adjusted raw quant field, searched AC strategy, and CFL
  maps for small whole-frame fixtures. These are oracle artifacts for the future
  adaptive-quantization/strategy implementation, not claims about current RTL
  parity.
- `tools/hjxl_reference.py --dct-only-quantized-ac-npy ...`,
  `--dct-only-num-nonzeros-npy ...`, `--dct-only-num-nonzeros-map-npy ...`, and
  `--dct-only-quant-dc-npy ...` write libjxl-tiny quantization outputs while
  forcing the default all-8x8-DCT strategy. Use these to validate the current
  transform/quantization direction before rectangular strategy search is
  implemented.
- `tools/hjxl_reference.py --dct-only-prepared-blocks-json ...` writes a
  structured per-block oracle for the prepared `DctOnlyQuantizeBlock` boundary:
  Q12 coefficients, raw quant, AC/DC scale reciprocals, CFL scalars, and
  libjxl-tiny's expected quantized AC/DC/nonzero outputs. The expected outputs
  come from libjxl-tiny's floating reference path, so comparisons against RTL
  should account for the current fixed-point Q12 coefficient input tolerance
  until frame-level fixed-point staging is locked down.
- `tools/hjxl_reference.py --distance-params-json ...` writes the libjxl-tiny
  distance parameters used to populate `DistanceParamsLookup`. Regenerate this
  when updating lookup entries or when the local reference changes. The JSON
  also includes the reciprocal for `--fixed-raw-quant`, defaulting to raw quant
  5.
- `tools/hjxl_reference.py --fixed-dct-only-dc-tokens-npy ...`,
  `--fixed-dct-only-ac-metadata-tokens-npy ...`, and
  `--fixed-dct-only-ac-tokens-npy ...` write logical `(context, value)` token
  streams for the current fixed all-DCT assumptions: `--fixed-raw-quant`
  defaulting to 5, zero CFL maps, all ordinary DCT blocks, and the requested
  distance's libjxl-tiny scale parameters. Use these to validate token ordering
  and context formulas against libjxl-tiny; exact RTL value parity still
  depends on closing the fixed-point transform/quantization gap for nontrivial
  images. The current host-boundary Scala regression uses a prepared-token
  gradient fixture with nonzero AC coefficients, so the exact token path is
  covered after libjxl-tiny quantized DC/AC data has been prepared.
- `tools/hjxl_reference.py --fixed-dct-only-prepared-token-inputs-json ...`
  writes the prepared DC sample stream and prepared AC raster blocks consumed
  by `FramePreparedTokenTraceStage` under the same fixed all-DCT assumptions.
  Use this when building trace-driver fixtures for the combined prepared-token
  RTL boundary.
- `tools/hjxl_prepared_token_inputs.py --prepared-json ... --dc-csv ...
  --ac-csv ...` converts that JSON into simple simulator input CSV streams for
  `FramePreparedTokenTraceStage`.
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
  reproduces the direct fixed-token oracle bytes exactly.
- `tools/hjxl_trace_tokens.py` converts `StageTrace` CSV dumps with
  `stage,group,index,value` columns into the token-input arrays consumed by
  `tools/hjxl_reference.py`. For token stages, `group` must be a contiguous
  token ordinal and output rows are `(context, value)`. For `AcStrategy`, the
  trace `index` is the raster block ordinal and the tool reshapes it with the
  supplied image width and height.
- `FixedDctOnlyTokenAssemblySpec` exercises the current host-boundary chain:
  libjxl-tiny prepared-token JSON from `tools/hjxl_reference.py`, simulator
  input CSVs from `tools/hjxl_prepared_token_inputs.py`, RTL traces from
  `FramePreparedTokenTraceStage`, token arrays from `tools/hjxl_trace_tokens.py`,
  and final frame/codestream bytes from the host assembler path in
  `tools/hjxl_reference.py`. This proves the prepared-token software boundary,
  not full RGB-input token parity: `FrameDctOnlyDcTokenTraceStage` and
  `FrameDctOnlyAcTokenTraceStage` still use the approximate fixed-point
  RGB/XYB/DCT path.

## Accuracy Policy

Use stage-tolerant parity:

- Compare fixed-point approximations of floating stages with explicit
  tolerances.
- Require exact equality after rounding or quantization.
- Require exact equality for token streams when a stage claims token parity.
- Do not optimize resource use until the relevant trace stage is stable.

## KV260 Path

The local machine has Verilator available, but Vivado/Vitis are not assumed.
The immediate FPGA deliverable is generated SystemVerilog and a Vivado-ready
top-level shape. Bitstream validation belongs in a later environment with AMD
tools installed.
