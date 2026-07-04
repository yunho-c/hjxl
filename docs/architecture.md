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
  emits at the top level: `Dc`, `AcMetadata`, or `AcTokens`. The full AC-token
  route is only instantiated when the core is elaborated with
  `traceRoute = TraceStage.AcTokens`.
- `RgbPixel` carries fixed-point linear RGB samples and image coordinates.
- `StageTrace` carries a stage id, group id, element index, and value.
- `HjxlCore` is the integration shell where stage modules will be wired.
- `HjxlAxiStreamCore` is the first hardware-facing stream shell. It accepts
  raster RGB samples without explicit coordinates, generates `RgbPixel.x/y`,
  and packs `StageTrace` rows into stream words.

Use the internal contracts for simulation and trace extraction, and use the
stream shell as the current KV260/Vivado-facing top-level shape.

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
- `FramePreparedDctOnlyQuantizeTraceStage` schedules complete prepared
  DCT-only X/Y/B blocks in raster order through `DctOnlyQuantizeTraceStage`.
  Its input boundary is the structured prepared-block data exported by
  `tools/hjxl_reference.py --dct-only-prepared-blocks-json`. This boundary uses
  Q16 DCT coefficients by default (`HjxlConfig.preparedDctCoefficientFractionBits`)
  so prepared CFL residual subtraction and quantization can match libjxl-tiny's
  floating reference fixture exactly. Its output boundary is frame-shaped
  `QuantizedAc`, `QuantDc`, and `NumNonzeros` trace records with `trace.group`
  equal to the raster block ordinal.
- `FramePreparedDctOnlyQuantizeTokenTraceStage` is the first direct RTL bridge
  from prepared DCT-only block inputs to fixed all-DCT logical token traces. It
  runs `DctOnlyQuantizeBlock`, buffers quantized DC/AC/nonzero frame state, and
  internally drives prepared DC, AC-metadata, and AC-token schedulers in the
  required output order. Unlike `FramePreparedTokenTraceStage`, it can describe
  the prepared raw-quant and CFL values used during quantization. It exposes
  `traceLast` on the final AC-token trace beat for host capture.
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
  order, buffers their X/Y/B quantized AC coefficients and nonzero counts,
  predicts nonzero counts from west/north block history, and emits the full
  `AcTokens` stream.
- `FramePreparedTokenTraceStage` combines the exact prepared DC and AC token
  schedulers with fixed all-DCT AC strategy and AC-metadata trace generation.
  Its input boundary is prepared quantized DC samples plus prepared quantized AC
  blocks; its output boundary is the same `StageTrace` CSV shape consumed by
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
  useful for focused simulation or integration. Token schedulers expose
  `traceLast`; `HjxlCore` carries it for DC, AC-metadata, and focused AC-token
  routes, and the dedicated AC top exposes it alongside the trace stream.
  `HjxlCoreRouteElaborationSpec` guards that split so the default all-route
  shell stays smaller.
- `HjxlAxiStreamCore` wraps `HjxlCore` in an AXI4-Stream-shaped raster input
  and trace output. Input data packs R/G/B into consecutive `pixelBits` fields
  with R in the low bits, checks input `last` against the configured raster
  frame length, and exposes sticky `protocolError` plus a `clearProtocolError`
  input for host recovery. Output data packs `{value,index,group,stage}` with
  `stage` in the low eight bits. Output `last` is asserted on each route's
  final frame trace word: fixed-size padded input, XYB, raw DCT, quantized
  traces, DC tokens, AC-metadata tokens, and AC strategy use lengths derived
  from `FrameConfig`, while the variable-length full AC-token route uses the
  scheduler's explicit final-token sideband. Use
  `sbt 'runMain hjxl.ElaborateAxiStream'` for the default shell or
  `sbt 'runMain hjxl.ElaborateAxiStreamCoreAcTokens'` for the focused
  full-AC-token shell.
- `ElaboratePreparedDctOnlyQuantize` generates standalone SystemVerilog for the
  prepared-DCT quantization scheduler. Use it when the integration boundary is
  after DCT/AQ/CFL scalar generation but before quantized DC/AC tokenization.
- `ElaboratePreparedDctOnlyQuantizeTokens` generates standalone SystemVerilog
  for the direct prepared-DCT quantize-to-token wrapper. This is the closest
  current RTL artifact to a prepared-transform encoder core, and it exposes
  `traceLast` for frame-level capture boundaries.
- `PreparedDctElaborationSpec` is the focused FIRTool emission regression for
  those prepared-DCT standalone tops. It emits SystemVerilog into a temporary
  directory and checks for the expected modules plus the structured
  prepared-block input, trace output, busy, and overflow ports, so generated RTL
  or top-level IO breakage is caught without committing generated files.
- `HjxlAxiStreamCoreSpec` covers the stream shell's raster coordinate
  generation, trace-row packing, fixed-size route output TLAST, and input TLAST
  protocol checking on cheap routes. It also captures real packed output from
  the padded-input stream shell and decodes it with `tools/hjxl_stream_trace.py`
  so the Chisel packing and host decoder stay aligned. Full AC-token TLAST
  alignment is covered at the token-stage and frame-scheduler levels because
  the focused AXI AC-token simulation is expensive.
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
- `tools/hjxl_reference.py --dct-only-ac-metadata-tokens-npy ...` writes
  libjxl-tiny AC-metadata token rows for the default all-DCT strategy using the
  prepared raw-quant and CFL maps. Use this to validate prepared metadata token
  schedulers instead of assuming fixed raw-quant/zero-CFL metadata.
- `tools/hjxl_reference.py --dct-only-dc-tokens-npy ...` and
  `--dct-only-ac-tokens-npy ...` write the matching adaptive all-DCT logical
  DC and AC token rows. Together with `--dct-only-ac-metadata-tokens-npy` and
  `--default-ac-strategy-npy`, these are the exact libjxl-tiny token oracle for
  the all-8x8-DCT path with real adaptive quantization and CFL maps.
- `tools/hjxl_reference.py --dct-only-prepared-blocks-json ...` writes a
  structured per-block oracle for the prepared `DctOnlyQuantizeBlock` boundary:
  Q16 coefficients, raw quant, AC/DC scale reciprocals, CFL scalars, and
  libjxl-tiny's expected quantized AC/DC/nonzero outputs. The converter accepts
  legacy Q12 fixtures, but current generated fixtures use
  `inputs.coefficient_fraction_bits = 16` and `inputs.coefficients_q`.
- `tools/hjxl_reference.py --dct-only-frame-bin ...` and
  `--dct-only-codestream-bin ...` serialize the adaptive all-DCT logical tokens
  through libjxl-tiny's entropy optimizer and bitstream writer. Use these as
  the byte oracle once RTL token streams have been compared against the
  adaptive all-DCT token arrays.
- `tools/hjxl_prepared_blocks.py --prepared-json ... --input-csv ...
  --expected-trace-csv ...` converts those prepared-block fixtures into
  simulator input rows and expected quantization trace rows. The converter
  validates raster block order, three X/Y/B coefficient channels, scalar input
  fields, expected output shapes, and declared nonzero counts.
- `tools/hjxl_quant_trace_to_prepared_tokens.py --trace-csv ... --width ...
  --height ... --dc-csv ... --ac-csv ...` converts `QuantDc`,
  `QuantizedAc`, and `NumNonzeros` trace rows into the prepared DC and AC CSV
  streams consumed by `FramePreparedTokenTraceStage`. It validates complete
  per-block quantization records, emits DC samples in token order (Y plane, X
  plane, then B plane), and preserves AC blocks in raster order with X/Y/B
  channel rows.
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
- `tools/hjxl_stream_trace.py --stream-csv ... --trace-csv ...` converts
  packed `HjxlAxiStreamCore` trace stream captures back into `StageTrace` CSV.
  It accepts `data,last` or `tdata,tlast` input columns, unpacks
  `{value,index,group,stage}`, sign-extends the value field, and can enforce a
  single-frame final TLAST with `--require-final-last`. `StreamTraceToolSpec`
  covers signed value decoding, accepted column aliases, TLAST rejection, the
  handoff into `tools/hjxl_trace_tokens.py`, direct `--stream-csv` token
  extraction, and `tools/hjxl_trace_to_codestream.py --stream-csv` validation.
- `tools/hjxl_trace_to_codestream.py --trace-csv ... --width ... --height ...
  --frame-bin ... --codestream-bin ...` is the direct host assembler for RTL
  token traces. It performs the same token extraction as `hjxl_trace_tokens.py`,
  then uses libjxl-tiny's entropy optimizer and bitstream writer to serialize a
  frame and bare codestream from those logical tokens. It accepts repeated
  `--trace-csv` and `--stream-csv` inputs, so packed stream captures can feed
  the byte assembler without an intermediate decoded CSV. `--expect-frame-bin`
  and `--expect-codestream-bin` make it a byte-parity checker for RTL bring-up.
  `TraceToCodestreamToolSpec` covers multi-file trace input, packed stream
  byte-parity input, expected-byte mismatch diagnostics, and malformed trace
  rejection for this CLI.
- `tools/hjxl_compare_tokens.py` compares converted token arrays against oracle
  arrays. It is exact by default for stream length, contexts, values, and AC
  strategy grid entries; use its value-delta mode only as a diagnostic while
  closing fixed-point quantization differences, not as a parity claim.
  `TokenCompareToolSpec` covers exact matches, mismatch diagnostics, and
  malformed token-array rejection for this CLI.
- `FixedDctOnlyTokenAssemblySpec` exercises the current host-boundary chain:
  libjxl-tiny prepared-token JSON from `tools/hjxl_reference.py`, simulator
  input CSVs from `tools/hjxl_prepared_token_inputs.py`, RTL traces from
  `FramePreparedTokenTraceStage`, token arrays from `tools/hjxl_trace_tokens.py`,
  and final frame/codestream bytes from the host assembler path in
  `tools/hjxl_reference.py`. It also checks `tools/hjxl_trace_to_codestream.py`
  against the same extracted token arrays and byte oracle. This proves the
  prepared-token software boundary, not full RGB-input token parity:
  `FrameDctOnlyDcTokenTraceStage` and `FrameDctOnlyAcTokenTraceStage` still use
  the approximate fixed-point RGB/XYB/DCT path.
- `FramePreparedDctOnlyQuantizeTraceStageSpec` covers the prepared-DCT
  quantization scheduler against libjxl-tiny prepared-block fixtures. It keeps
  stage/group/index/value comparison exact at the Q16 prepared-coefficient
  boundary, then verifies the observed RTL quantization trace can be projected
  into prepared-token DC/AC CSV inputs without changing the trace data and can
  drive `FramePreparedTokenTraceStage` through complete DC, strategy, metadata,
  and AC token trace emission. It also checks
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
The immediate FPGA deliverable is generated SystemVerilog and a Vivado-ready
top-level shape. `HjxlAxiStreamCore` is the current candidate wrapper for that
shape; it is intentionally simple enough to connect to DMA-style AXI streams
before AXI-Lite control registers and host drivers are designed. Bitstream
validation belongs in a later environment with AMD tools installed.
