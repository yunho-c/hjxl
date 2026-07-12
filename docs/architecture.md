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
  fall back to distance 1, and AXI-Lite controlled shells report that fallback
  at status bit 3. It emits the global AC scale, quantized DC scale, fixed
  raw-quant-5 AC reciprocal, X/Y/B inverse DC factors, X quant-matrix
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
  runs `DctOnlyQuantizeBlock`, buffers quantized DC/AC/nonzero frame state, and
  internally drives prepared DC, AC-metadata, and AC-token schedulers in the
  required output order. Unlike `FramePreparedTokenTraceStage`, it can describe
  the prepared raw-quant and CFL values used during quantization. It exposes
  `traceLast` on the final AC-token trace beat for host capture.
- `FrameRawQuantFieldTraceStage` emits the current fixed raw-quant field for a
  padded frame: one adjusted raw-quant value per 8x8 block, defaulting to
  `QuantizeDct8x8Block.DefaultRawQuant` unless `FrameConfig.fixedRawQuant`
  overrides it. The raw-quant byte is zero-extended into `StageTrace.value`, so
  values above 127 remain positive metadata values even though trace values are
  signed. `HjxlCore` exposes it only as the focused
  `traceRoute = TraceStage.RawQuantField` route today, keeping the default
  all-route behavior on the existing AC-strategy metadata path.
- `FrameCflMapTraceStage` emits the current fixed scalar CFL maps for a padded
  frame: one Y-to-X or Y-to-B value per 64x64 tile. `HjxlCore` exposes these as
  focused `TraceStage.YtoxMap` and `TraceStage.YtobMap` routes today, giving
  future chroma-from-luma work a tested trace shape without changing the
  default metadata route.
- `FrameDctOnlyQuantizeTraceStage` is the first frame-level quantized-block
  scheduler. It buffers and pads RGB like `FrameDct8x8TraceStage`, converts
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
  order, buffers their X/Y/B quantized AC coefficients and nonzero counts,
  predicts nonzero counts from west/north block history, and emits the full
  `AcTokens` stream.
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
  the selected scheduler's `traceLast` sideband to TLAST. Use
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
  addresses return AXI-Lite DECERR. Use
  `sbt 'runMain hjxl.ElaborateAxiLiteStream'` for the default controlled shell
  or `sbt 'runMain hjxl.ElaborateAxiLiteStreamCoreAcTokens'` for the focused
  full-AC-token controlled shell.
- `ElaboratePreparedDctOnlyQuantize` generates standalone SystemVerilog for the
  prepared-DCT quantization scheduler. Use it when the integration boundary is
  after DCT/AQ/CFL scalar generation but before quantized DC/AC tokenization.
  The generated top exposes `traceLast` for capture boundaries.
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
  `generated-kv260-prepared-dct-top/`.
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
  `tools/hjxl_compare_tokens.py` fixed-oracle comparison. Full AC-token TLAST
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
- `CflCoefficientSampleWeight`/`CflWeightedSumAccumulator`/
  `CflMultiplierEstimator`/`CflCoefficientSumAccumulator`/
  `CflTileCoefficientEstimator`/`CflTileCoefficientTraceStage` cover only
  inverse-weight lookup/application, weighted-sample accumulation, signed CFL
  multiplier division/round/clamp, and tile trace emission. Keep them directly
  tested while future CFL work adds tile DCT traversal, tile-map scheduling,
  and frame integration.
- `FrameRawQuantFieldTraceStage` emits one fixed adjusted raw-quant value per
  padded 8x8 block. Treat it as a traceable fixed-metadata scaffold for future
  AQ work, not as a replacement for libjxl-tiny's adaptive quantization. The
  raw-quant byte is zero-extended into the signed trace-value field.
- `FrameCflMapTraceStage` emits fixed scalar Y-to-X and Y-to-B CFL map traces,
  one record per 64x64 tile. Treat it as a traceable fixed-metadata scaffold
  for future chroma-from-luma work, not as libjxl-tiny CFL parity.
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
  current focused raw-quant and CFL-map trace routes, using the same
  `--fixed-raw-quant`, `--fixed-ytox`, and `--fixed-ytob` values as the fixed
  token oracles. These are the right comparator inputs for fixed
  `FrameRawQuantFieldTraceStage` and `FrameCflMapTraceStage` captures; the
  adaptive `--dct-only-*` metadata grids remain software-path references for
  future AQ/CFL hardware.
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
The immediate FPGA deliverable is generated SystemVerilog and a Vivado-ready
top-level shape. `HjxlKv260PreparedDctTop` is the current candidate wrapper for
that shape: AXI4-Stream carries prepared DCT-only block records and trace
output, while AXI4-Lite programs frame configuration and clears stream protocol
errors. The lower-level `HjxlAxiLiteStreamCore` remains the RGB-input
controlled shell for simulation and future end-to-end hardware work.
Host drivers, DMA packaging, timing closure, and bitstream validation belong in
a later environment with AMD tools installed.
