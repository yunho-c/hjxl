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
  replication to the next 8x8 block boundary.
- `RgbToXybApprox` is a standalone Q8 linear-RGB to Q12 XYB approximation with a
  cube-root lookup table. Treat it as an accuracy-tunable first pass, not final
  bit-exact libjxl-tiny parity.
- `FrameXybTraceStage` buffers and pads the frame, reuses `RgbToXybApprox`, and
  emits channel-first XYB trace samples. `HjxlCore` selects this stage when
  `FrameConfig.enableXyb` is true.
- `Dct8Approx` is a standalone Q12 1D DCT-8 primitive. It should be reused for
  the future 8x8 transform stage instead of writing a separate transform shape
  from scratch.
- `Dct8x8Approx` is the first block-level transform primitive. It uses
  `Dct8Approx` for both dimensions, applies libjxl-tiny's per-dimension 1/8
  scale, and emits the scaled 8x8 coefficient layout used before quantization.
- `FrameDct8x8TraceStage` buffers the RGB frame, converts each padded 8x8 block
  to approximate XYB, runs `Dct8x8Approx` for all three channels, and emits
  `RawDct8x8` trace samples. `trace.group` is the raster block index and
  `trace.index` is `channel * 64 + coefficient`.
- `QuantizeDct8x8Block` is a standalone DCT-only AC quantization primitive. It
  takes one channel's Q12 DCT coefficients plus raw quant, AC scale Q16, and QM
  multiplier Q16 inputs, then emits quantized coefficients and a DC-excluding
  nonzero count. It does not replace the missing whole-frame AQ/CFL/Y-roundtrip
  quantization path.
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
- `FrameDctOnlyQuantizeTraceStage` is the current frame-level quant trace
  scheduler. It buffers/pads RGB, computes approximate XYB and DCT per raster
  8x8 block, then emits 192 `QuantizedAc`, three `QuantDc`, and three
  `NumNonzeros` records per block. It intentionally uses fixed defaults:
  adjusted raw quant 5, distance-1 DC factors, default X QM scale, zero CFL
  multipliers, and `FrameConfig.fixedPointScale` as AC scale Q16 with zero
  selecting 7340. Do not describe it as adaptive-quantization parity.
- `DcTokenize` provides libjxl-tiny DC token helpers: signed-token packing,
  clamped-gradient prediction, and the compressed gradient-context LUT.
- `DcTokenTraceStage` is the prepared quantized-DC token primitive. Feed it a
  current quantized DC sample plus west/north/northwest predictor neighbors; it
  emits exactly one `DcTokens` trace record. Its spec is oracle-backed against
  libjxl-tiny for a constant-frame prepared DC plane.
- `FrameDctOnlyDcTokenTraceStage` is the first token trace stage. It uses the
  same fixed-parameter DCT-only frame path and emits only DC tokens, in Y/X/B
  plane order. For token traces, `trace.group` is the token ordinal,
  `trace.index` is the token context, and `trace.value` is the packed residual.
  It delegates predictor/context/residual packing to `DcTokenTraceStage`.
- `FrameDctOnlyAcMetadataTokenTraceStage` emits the fixed-path AC metadata
  tokens: zero CFL tile maps, all-DCT strategy choices, fixed raw quant-field
  values, and fixed block metadata literals. It uses
  `trace.stage = AcMetadataTokens`, `trace.group = token ordinal`,
  `trace.index = context`, and `trace.value = packed residual or literal`.
- `FrameDctOnlyAcNonzeroTokenTraceStage` emits the nonzero-count prefix tokens
  for AC coefficient tokenization in ordinary-DCT block/channel order. It uses
  `trace.stage = AcTokens`, `trace.group = token ordinal`, `trace.index =
  context`, and `trace.value = nonzero count`. It does not emit coefficient
  scan/value tokens yet. It is currently a standalone frame stage; do not claim
  it is selected by `HjxlCore`.
- `AcCoefficientTokenTraceStage` emits prepared-block ordinary-DCT coefficient
  scan/value tokens after the nonzero-count prefix. Feed it quantized
  coefficients, channel, nonzero count, and the first token ordinal; it emits
  `trace.stage = AcTokens`, `trace.group = token ordinal`, `trace.index =
  zero-density context`, and `trace.value = packed coefficient`. It is not yet
  frame-scheduled.
- `AcBlockTokenTraceStage` wraps the nonzero-count prefix and prepared
  coefficient scan tokens for one ordinary-DCT block/channel. It still expects
  the caller to provide the predicted nonzero count from frame/block neighbors.
- `DctOnlyAcBlockTokenTraceStage` sequences prepared AC token streams for one
  X/Y/B ordinary-DCT block in libjxl-tiny channel order: Y, X, then B. It still
  expects caller-provided predicted nonzero counts and first token ordinal.
- `FrameDctOnlyAcTokenTraceStage` is the complete standalone frame scheduler
  for fixed all-DCT AC tokens. It recomputes the current fixed DCT-only
  quantized block stream, predicts nonzero counts from west/north block
  history, and emits full Y/X/B block AC streams through
  `DctOnlyAcBlockTokenTraceStage`. It is not selected by `HjxlCore`; keep it
  standalone until the top-level no longer instantiates every heavy trace path.
- `HjxlAcTokenCore` wraps the full AC-token frame scheduler as a dedicated
  compile-time top. Use `sbt 'runMain hjxl.ElaborateAcTokens'` for this path
  instead of routing it through `HjxlCore`; attempting to runtime-mux it into
  `HjxlCore` makes unrelated Verilator top-level tests impractical.
- Entropy coding, entropy table optimization, and bitstream assembly are still
  future work.
- `FrameAcStrategyTraceStage` emits one default DCT-first AC strategy value per
  padded block. This matches the current all-8x8-DCT transform path but not
  libjxl-tiny's adaptive 16x8/8x16 strategy search.
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
- `--dct-only-prepared-blocks-json` exports the prepared-block interface for
  `DctOnlyQuantizeBlock`: Q12 coefficients, quant/scaling/CFL inputs, and
  libjxl-tiny expected quantized AC/DC/nonzero results. Treat the expected
  values as a floating-reference oracle; current RTL comparisons may still need
  explicit tolerance because the hardware boundary uses rounded fixed-point DCT
  coefficients.
- `--fixed-dct-only-dc-tokens-npy`,
  `--fixed-dct-only-ac-metadata-tokens-npy`, and
  `--fixed-dct-only-ac-tokens-npy` export logical `(context, value)` token
  streams for raw-quant-5, zero-CFL, all-DCT fixtures. Use these to cross-check
  token order and context formulas against libjxl-tiny. For nontrivial images,
  exact value parity still depends on improving the fixed-point transform and
  quantization path. Current Scala tests use the AC-metadata oracle directly
  and use the AC oracle for a constant frame where all AC coefficients are zero
  and the token stream is exactly comparable.
- `--fixed-dct-only-frame-bin` and `--fixed-dct-only-codestream-bin` serialize
  the fixed logical tokens through libjxl-tiny's entropy optimizer and bitstream
  writer. Treat these as host-side oracle artifacts for the near-term
  hardware/software split, not as evidence that RTL emits final `.jxl` bytes.
- `--token-input-dc-tokens-npy`, `--token-input-ac-metadata-tokens-npy`,
  `--token-input-ac-tokens-npy`, and `--token-input-ac-strategy-npy` let the
  helper assemble frame/codestream bytes from precomputed logical tokens. Use
  this path for future RTL trace dumps before attempting an RTL entropy coder.
- `HjxlCore` currently exposes padded-input, XYB, raw-DCT, fixed-parameter
  quantized-DCT, fixed-parameter DC-token, fixed AC-metadata-token, or default
  AC-strategy trace streams. `FrameConfig.tokenSelect` chooses token substreams:
  currently `Dc` or `AcMetadata` through `HjxlCore`; AC nonzero/full AC token
  streams are standalone trace stages.
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

For libjxl-tiny reference exploration, use disposable output under
`build-codex/` in the libjxl-tiny checkout.
