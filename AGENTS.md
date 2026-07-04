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
  and trace output.
- `HjxlPreparedDctAxiStreamCore` is the stream-shaped shell for the current
  closest-to-parity prepared-DCT quantize-to-token path. Use it when the host
  can supply prepared DCT-only block records and should receive packed token
  traces without the wide structured Chisel IO.
- `HjxlPreparedDctAxiLiteStreamCore` wraps that prepared-DCT stream shell with
  the same AXI-Lite register map used by `HjxlAxiLiteStreamCore`. Prefer it for
  KV260-style integration experiments when the host can already supply prepared
  DCT blocks.
- Keep stage outputs traceable against libjxl-tiny names and array shapes.

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
- Use `circt.stage.ChiselStage.emitSystemVerilogFile` for RTL generation.
- Use `chisel3.simulator.scalatest.ChiselSim` for ScalaTest hardware tests.
- Treat Vivado/KV260 bitstream validation as unavailable unless `vivado` or
  `vitis` is present or the user provides a toolchain environment.

## Current RTL State

- `FramePadTraceStage` is the first real libjxl-tiny stage. It emits
  `input_padded` samples in channel-first order and implements right/bottom edge
  replication to the next 8x8 block boundary. Its `traceLast` output marks the
  final padded B-channel sample.
- `RgbToXybApprox` is a standalone Q8 linear-RGB to Q12 XYB approximation. It
  computes mixed absorbance at Q10 precision, linearly interpolates a Q8
  cube-root lookup table, then emits Q12 XYB. Treat it as an accuracy-tunable
  first pass, not final bit-exact libjxl-tiny parity.
- `FrameXybTraceStage` buffers and pads the frame, reuses `RgbToXybApprox`, and
  emits channel-first XYB trace samples. Its `traceLast` output marks the final
  padded B-channel sample. `HjxlCore` selects this stage when
  `FrameConfig.enableXyb` is true.
- `Dct8Approx` is a standalone Q12 1D DCT-8 primitive. It should be reused for
  the future 8x8 transform stage instead of writing a separate transform shape
  from scratch.
- `Dct8x8Approx` is the first block-level transform primitive. It uses
  `Dct8Approx` for both dimensions, applies libjxl-tiny's per-dimension 1/8
  scale, and emits the scaled 8x8 coefficient layout used before quantization.
- `DistanceParamsLookup` is the first hardware boundary for libjxl-tiny
  distance-derived scalar parameters. It supports common Q8 distances
  `64`, `128`, `256`, `512`, `1024`, and `2048`, defaulting unsupported values
  to distance 1. It emits global AC scale, quantized DC scale, inverse DC
  factors, fixed raw-quant-5 AC reciprocal, X quant-matrix multiplier, and EPF
  iterations. Use
  `tools/hjxl_reference.py --distance-params-json ...` to regenerate or check
  entries against the local libjxl-tiny Python port.
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
  reciprocal. Keep this boundary small and directly tested instead of
  validating the selection through expensive frame-level Verilator tests.
- `DctQuantizeTraceStage` is the prepared-block trace wrapper for that
  primitive. It emits 64 `QuantizedAc` trace records followed by one
  `NumNonzeros` record. Use it to validate quantization streams without wiring
  fake AQ/CFL values through `HjxlCore`.
- `QuantizeRoundtripYDct8x8Block` implements libjxl-tiny's Y quantize,
  quant-bias, and dequantize roundtrip for DCT-only 8x8 blocks. It needs an
  explicit reciprocal AC scale input; do not hide that with a constant unless a
  test fixture is intentionally fixed to one distance and quant value.
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
  inputs; it runs `DctOnlyQuantizeBlock`, buffers quantized frame state, then
  drives prepared DC, AC-metadata, and AC-token schedulers internally to emit
  DC, strategy, metadata, and AC token traces. This wrapper preserves prepared
  raw-quant and CFL metadata instead of falling back to fixed raw-quant/zero-CFL
  metadata, and its `traceLast` output marks the final AC-token trace beat.
  metadata.
- `FrameDctOnlyQuantizeTraceStage` is the current frame-level quant trace
  scheduler. It buffers/pads RGB, computes approximate XYB and DCT per raster
  8x8 block, then emits 192 `QuantizedAc`, three `QuantDc`, and three
  `NumNonzeros` records per block. It intentionally still uses one global
  adjusted raw quant value and zero CFL multipliers, but it now consumes
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
  tokens: zero CFL tile maps, all-DCT strategy choices, fixed raw quant-field
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
  Feed it complete quantized AC blocks in raster order. It buffers X/Y/B
  quantized AC coefficients and nonzero counts, predicts nonzero counts from
  west/north block history, and emits the full all-DCT `AcTokens` stream.
- `FramePreparedTokenTraceStage` is the combined exact prepared-token boundary.
  Feed it prepared quantized DC samples first, then prepared quantized AC blocks.
  It emits DC tokens, AC strategy traces, AC metadata tokens, and AC tokens in a
  single `StageTrace` stream suitable for `tools/hjxl_trace_tokens.py`. Its
  `traceLast` output is asserted with the final AC-token trace beat for the
  frame.
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
  packed RTL output and feeds it to `tools/hjxl_stream_trace.py`.
- Use `sbt 'runMain hjxl.ElaborateAxiLiteStream'` for the default AXI-Lite
  controlled stream wrapper and
  `sbt 'runMain hjxl.ElaborateAxiLiteStreamCoreAcTokens'` for the focused
  full-AC-token controlled wrapper. These write `generated-axi-lite-stream/`
  and `generated-axi-lite-stream-core-ac-tokens/`; keep both generated
  directories out of git. The register map is: `0x00` status/control
  (`protocolError` read bit 0, `busy` read bit 1, `overflow` read bit 2,
  clear protocol error on write bit 0), `0x04` `xsize`, `0x08` `ysize`,
  `0x0c` `distanceQ8`, `0x10` `fixedPointScale`, `0x14`
  `fixedInvQacQ16`, `0x18` `fixedRawQuant`, and `0x1c` flags
  (`enableXyb`, `enableDct`, `enableQuant`, `enableTokenize`, `tokenSelect` in
  bits 9:8). `HjxlAxiLiteStreamCoreSpec` covers register reads/writes, byte
  strobes, decode errors, and protocol-error clearing;
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
  tool's layout in lockstep with `HjxlPreparedDctAxiStreamCore`. The same spec
  also proves the stream path through `tools/hjxl_trace_to_codestream.py` by
  comparing assembled frame/codestream bytes against libjxl-tiny's direct
  DCT-only output.
- Use `sbt 'runMain hjxl.ElaboratePreparedDctAxiLiteStream'` to generate the
  AXI-Lite controlled version of that prepared-DCT stream shell. It writes
  `generated-prepared-dct-axi-lite-stream/`; keep it out of git. This top uses
  the same register map as `HjxlAxiLiteStreamCore`; `xsize`, `ysize`, and
  status/control are active today, while the remaining `FrameConfig` registers
  preserve a common host control surface. `HjxlPreparedDctAxiLiteStreamCoreSpec`
  covers register access, AXI-Lite programmed frame sizing, protocol-error
  clearing, generated ports, and a libjxl-tiny prepared-block stream to
  codestream byte comparison through the host assembler.
- `PreparedDctElaborationSpec` is the focused FIRTool/SystemVerilog emission
  regression for the prepared-DCT quantization and direct quantize-to-token
  standalone tops. It also checks the structured prepared-block input and trace
  output port surface. Run it after touching those modules or their IO bundles.
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
- `FrameAcStrategyTraceStage` emits one default DCT-first AC strategy value per
  padded block. This matches the current all-8x8-DCT transform path but not
  libjxl-tiny's adaptive 16x8/8x16 strategy search. Its `traceLast` output
  marks the final padded raster block's strategy record.
- `tools/hjxl_reference.py` can export real libjxl-tiny quant metadata with
  `--raw-quant-field-npy`, `--libjxl-ac-strategy-npy`, `--ytox-map-npy`, and
  `--ytob-map-npy`. Use those artifacts as the oracle before implementing AQ,
  CFL, or transform-strategy RTL; do not compare those outputs against the
  current default AC-strategy trace as if they were equivalent.
- The same helper can export all-DCT quantization oracle outputs with
  `--dct-only-raw-quant-field-npy`, `--dct-only-ytox-map-npy`,
  `--dct-only-ytob-map-npy`, `--dct-only-quantized-ac-npy`,
  `--dct-only-num-nonzeros-npy`, `--dct-only-num-nonzeros-map-npy`, and
  `--dct-only-quant-dc-npy`. Prefer these artifacts when validating the current
  DCT-only quantization path before adaptive rectangular strategy search exists.
- `--dct-only-ac-metadata-tokens-npy` exports libjxl-tiny AC-metadata tokens
  for the default all-DCT strategy using prepared raw-quant and CFL maps. Use
  this oracle for `FramePreparedAcMetadataTokenTraceStage` instead of comparing
  against fixed raw-quant/zero-CFL metadata.
- `--dct-only-dc-tokens-npy` and `--dct-only-ac-tokens-npy` export the matching
  adaptive all-DCT logical token streams. Pair them with
  `--dct-only-ac-metadata-tokens-npy` and `--default-ac-strategy-npy` when
  comparing future RTL token traces against libjxl-tiny's all-8x8-DCT path.
- `--dct-only-prepared-blocks-json` exports the prepared-block interface for
  `DctOnlyQuantizeBlock`: Q16 coefficients by default
  (`inputs.coefficient_fraction_bits = 16`, `inputs.coefficients_q`),
  quant/scaling/CFL inputs, and libjxl-tiny expected quantized AC/DC/nonzero
  results. The converter still accepts legacy `coefficients_q12`, but new
  fixtures should use the explicit fraction-bit field.
- `tools/hjxl_prepared_blocks.py --prepared-json ... --input-csv ...
  --expected-trace-csv ...` converts prepared-block JSON fixtures into simulator
  input rows for `FramePreparedDctOnlyQuantizeTraceStage` plus expected
  quantization trace rows. It validates raster block order, three X/Y/B
  coefficient channels, scalar input fields, expected output shapes, and
  declared nonzero counts against expected coefficient data.
- `tools/hjxl_quant_trace_to_prepared_tokens.py --trace-csv ... --width ...
  --height ... --dc-csv ... --ac-csv ...` converts quantization traces into the
  prepared-token simulator CSVs. It requires complete `QuantDc`,
  `QuantizedAc`, and `NumNonzeros` records for every raster block, emits DC
  samples in token order (Y plane, X plane, then B plane), and emits AC block
  rows in raster block order with X/Y/B channel rows.
- `--distance-params-json` exports libjxl-tiny distance parameters. Use it to
  update or audit `DistanceParamsLookup` entries. Its `inv_qac_q16` field is
  computed for `--fixed-raw-quant`, which defaults to 5.
- `--fixed-dct-only-dc-tokens-npy`,
  `--fixed-dct-only-ac-metadata-tokens-npy`, and
  `--fixed-dct-only-ac-tokens-npy` export logical `(context, value)` token
  streams for `--fixed-raw-quant` (default 5), zero-CFL, all-DCT fixtures. Use
  these to cross-check token order and context formulas against libjxl-tiny. For
  nontrivial images, exact value parity still depends on improving the
  fixed-point transform and quantization path. The current host-boundary Scala
  regression uses a prepared-token gradient fixture with nonzero AC
  coefficients, so the exact token path is covered after libjxl-tiny quantized
  DC/AC data has been prepared.
- `--fixed-dct-only-prepared-token-inputs-json` exports the prepared DC sample
  stream and prepared AC raster blocks consumed by `FramePreparedTokenTraceStage`
  for the same fixed all-DCT assumptions. Use it to regenerate combined
  prepared-token trace-driver fixtures.
- `tools/hjxl_prepared_token_inputs.py --prepared-json ... --dc-csv ...
  --ac-csv ...` converts those JSON fixtures into simple simulator input CSV
  streams for `FramePreparedTokenTraceStage`. It validates the expected DC
  sample count, contiguous AC block order, three X/Y/B channels, nonzero ranges,
  declared nonzero counts against coefficient data, and 64 coefficients per
  channel before writing outputs.
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
- `tools/hjxl_trace_tokens.py` converts `StageTrace` CSV dumps with
  `stage,group,index,value` columns into those token-input NumPy arrays. Token
  stages require contiguous `group` ordinals; AC strategy traces use `index` as
  the raster block ordinal and require image width/height for reshaping.
- `tools/hjxl_stream_trace.py --stream-csv ... --trace-csv ...` decodes packed
  `HjxlAxiStreamCore` trace captures with `data,last` or `tdata,tlast` columns
  back into `StageTrace` CSV. Use `--require-final-last` for single-frame
  captures from all current routes, including focused full AC-token captures.
  `StreamTraceToolSpec` covers this helper, its handoff into
  `tools/hjxl_trace_tokens.py`, direct `--stream-csv` token extraction, and the
  `tools/hjxl_trace_to_codestream.py --stream-csv` path.
- `tools/hjxl_rgb_stream.py --pfm ... --stream-csv ...` converts RGB PFM files
  into the raster `data,last` input stream expected by `HjxlAxiStreamCore` and
  `HjxlAxiLiteStreamCore`. It restores top-to-bottom PFM row order, quantizes
  linear samples to signed Q8 by default, packs R/G/B into consecutive
  `pixelBits` fields, and asserts `last` only on the final pixel. With
  `--axi-lite-csv`, it also emits `address,data,strb` writes for the shared
  AXI-Lite register map, including PFM-derived `xsize`/`ysize` and route flags.
  With `--manifest-json`, it records source/image metadata, stream packing,
  generated artifact paths, the AXI-Lite register map, status/control bits, and
  config values. Keep this helper aligned with `RgbToXybApprox.InputFractionBits`,
  the stream wrapper's component packing, and `HjxlAxiLiteRegister`.
  `HjxlAxiStreamCoreSpec` exercises the current path from PFM to RGB stream,
  RTL, and trace decoding, and `HjxlAxiLiteStreamCoreSpec` exercises the same
  path using the generated AXI-Lite CSV to configure the DUT; preserve those
  regressions when changing host input packing or the control register map.
  Use `tools/hjxl_rgb_stream.py --validate-manifest ...` before replaying a
  saved host bundle; it checks stream row count, final-only TLAST, CSV columns,
  AXI-Lite strobes, and register values against the manifest.
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
  AC strategy grid entries. Use `--max-value-delta` only for diagnostics while
  narrowing fixed-point differences, not for final parity claims.
  `TokenCompareToolSpec` covers this helper; update it when changing comparator
  behavior.
- `FixedDctOnlyTokenAssemblySpec` is the current host-boundary regression. It
  runs the full prepared-token boundary chain: libjxl-tiny prepared-token JSON,
  simulator input CSVs, `FramePreparedTokenTraceStage`, StageTrace-to-token
  conversion, host frame/codestream assembly through `hjxl_reference.py`, and
  the one-step `hjxl_trace_to_codestream.py` path. Do not treat this as full
  RGB-input token parity: `FrameDctOnlyDcTokenTraceStage` and
  `FrameDctOnlyAcTokenTraceStage` still go through the approximate fixed-point
  RGB/XYB/DCT path, so closing that parity gap remains separate work.
- `FramePreparedDctOnlyQuantizeTraceStageSpec` covers the prepared-DCT
  quantization scheduler with libjxl-tiny prepared-block fixtures. It enforces
  exact stage/group/index/value comparison at the Q16 prepared-coefficient
  boundary, then verifies the observed RTL quantization trace converts into
  prepared-token DC/AC CSV inputs without data loss or reordering and can drive
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
- `HjxlCore` currently exposes padded-input, XYB, raw-DCT, fixed-parameter
  quantized-DCT, fixed-parameter DC-token, fixed AC-metadata-token, or default
  AC-strategy trace streams. `FrameConfig.tokenSelect` chooses token substreams:
  `Dc`, `AcMetadata`, or `AcTokens`; the full AC-token stream is available only
  in the focused `traceRoute = TraceStage.AcTokens` core elaboration or through
  `HjxlAcTokenCore`, while the AC nonzero-only stream remains a standalone
  trace stage.
  `HjxlCore(traceRoute = ...)` can restrict elaboration to one compile-time
  trace route while preserving the same public IO and runtime config contract
  for that route. Use this form in focused tests; compiling the all-route shell
  under Verilator is expensive as the frame schedulers grow.
  `HjxlCoreRouteElaborationSpec` guards that the default all-route shell omits
  the heavy full-AC-token scheduler while `traceRoute = TraceStage.AcTokens`
  includes it behind the normal core IO.
  `enableDct && enableQuant && enableTokenize && tokenSelect=Dc` selects the
  DC-token frame trace; `enableDct && enableQuant` selects the quantized-DCT
  frame trace; `enableDct` alone selects raw DCT;
  `enableQuant && enableTokenize && tokenSelect=AcMetadata` selects AC metadata
  tokens; `enableQuant` alone selects AC strategy metadata. Do not describe it
  as an encoder yet; these are traceable pipeline slices.

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
