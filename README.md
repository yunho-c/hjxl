# hjxl

Chisel RTL for a hardware-accelerated JPEG XL encoder targeting practical
parity with
[libjxl-tiny](https://github.com/libjxl/libjxl-tiny), not the full JPEG XL
encoder surface.

Current RTL status: the top-level buffers a small RGB frame and emits one of
several trace streams: libjxl-tiny-compatible `input_padded`, padded
channel-first XYB when `enableXyb` is set, raster 8x8-block raw scaled-DCT
coefficients when `enableDct` is set, fixed-parameter DCT-only quantized block
records when `enableDct && enableQuant` are set, DC residual tokens when
`enableDct && enableQuant && enableTokenize && tokenSelect=Dc` are set, fixed
AC-metadata tokens when `enableQuant && enableTokenize &&
tokenSelect=AcMetadata` are set, or the current all-DCT AC strategy map when
only `enableQuant` is set. Later stages will replace the fixed quantization
defaults with adaptive quantization/CFL metadata, entropy coding, and bitstream
assembly. Standalone fixed-point primitives exist for
approximate RGB-to-XYB, 1D DCT-8, and the scaled 8x8 DCT block layout used by
libjxl-tiny. The RGB-to-XYB primitive keeps Q10 mixed-absorbance precision and
linearly interpolates a Q8 cube-root table before emitting Q12 XYB samples.
`DistanceParamsLookup` provides the first hardware distance-parameter boundary
for common Q8 distances `64`, `128`, `256`, `512`, `1024`, and `2048`, with
unsupported values falling back to distance 1. The fixed DCT-only quant/token
frame schedulers use those distance-derived AC/DC scalar parameters, including
the fixed raw-quant-5 AC reciprocal, while `fixedPointScale` plus
`fixedInvQacQ16` remain AC-scale overrides for trace experiments. `fixedRawQuant`
can override the default adjusted raw quant 5 globally for fixed-path traces.
`AcQuantScaleSelector` is the small reusable RTL boundary that chooses between
lookup parameters and explicit override parameters before frame schedulers feed
`DctOnlyQuantizeBlock`.
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
emits frame-shaped `QuantizedAc`, `QuantDc`, and `NumNonzeros` traces.
`FramePreparedDctOnlyQuantizeTokenTraceStage` bridges the same prepared-DCT
input boundary directly into fixed all-DCT logical token traces by internally
buffering quantized results and driving `FramePreparedTokenTraceStage`.
`FrameDctOnlyQuantizeTraceStage` schedules padded raster blocks through that
path with fixed distance-1 quantization defaults so future token stages have a
frame-shaped trace source before full AQ/CFL hardware is available.
`FrameDctOnlyDcTokenTraceStage` emits the first logical token stream: DC
predictor contexts and packed residuals in libjxl-tiny Y/X/B plane order.
`DcTokenTraceStage` exposes the same DC predictor/token packing as a prepared
single-sample boundary once quantized DC planes already exist, and
`FramePreparedDcTokenTraceStage` schedules complete prepared quantized DC planes
through that exact token boundary in libjxl-tiny Y/X/B raster order.
`FrameDctOnlyAcMetadataTokenTraceStage` emits fixed-path CFL, AC-strategy,
quant-field, and block-metadata tokens.
`FramePreparedAcMetadataTokenTraceStage` emits the same AC-metadata token
substream from prepared per-block raw quant values and per-tile CFL
multipliers, so prepared-DCT quantization can carry coherent metadata into the
token boundary.
`FrameDctOnlyAcNonzeroTokenTraceStage` is a directly tested standalone frame
stage for the first `AcTokens` substream: nonzero-count contexts and values for
each block/channel. `AcCoefficientTokenTraceStage` emits prepared-block AC
coefficient scan tokens with libjxl-tiny's DCT coefficient order and
zero-density contexts, and `AcBlockTokenTraceStage` combines the nonzero prefix
with those coefficient tokens for one prepared block/channel.
`DctOnlyAcBlockTokenTraceStage` sequences the prepared Y/X/B channel streams
for one all-DCT block. `FramePreparedAcTokenTraceStage` schedules complete
prepared quantized AC blocks through that exact token boundary, predicting
nonzero counts from west/north block history. `FramePreparedTokenTraceStage`
combines the exact prepared DC and AC schedulers with fixed all-DCT AC
strategy/metadata trace generation, producing the four logical trace streams
consumed by the host bitstream assembly boundary. `FrameDctOnlyAcTokenTraceStage`
is the first full RGB-input standalone frame scheduler for `AcTokens`: it emits
nonzero prefixes and coefficient scan/value tokens for every fixed all-DCT
raster block/channel. It is directly tested and exposed through the dedicated
`HjxlAcTokenCore` wrapper, not through the runtime-multiplexed `HjxlCore`.
`HjxlCore` also accepts an optional compile-time `traceRoute` parameter used by
simulation tests to instantiate only the selected route. The default still
builds the all-route integration shell.

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

Generate the current top-level SystemVerilog:

```sh
sbt 'runMain hjxl.Elaborate'
```

Generate the standalone full AC-token trace top-level:

```sh
sbt 'runMain hjxl.ElaborateAcTokens'
```

Generate the exact prepared-token trace top-levels:

```sh
sbt 'runMain hjxl.ElaboratePreparedDctOnlyQuantize'
sbt 'runMain hjxl.ElaboratePreparedDctOnlyQuantizeTokens'
sbt 'runMain hjxl.ElaboratePreparedDcTokens'
sbt 'runMain hjxl.ElaboratePreparedAcMetadataTokens'
sbt 'runMain hjxl.ElaboratePreparedAcTokens'
sbt 'runMain hjxl.ElaboratePreparedTokens'
```

Generate a small libjxl-tiny reference fixture:

```sh
python3 tools/hjxl_reference.py --width 17 --height 9 --pattern gradient \
  --fixed-raw-quant 5 \
  --pfm build-codex/fixtures/gradient-17x9.pfm \
  --input-padded-npy build-codex/fixtures/gradient-17x9-input-padded.npy \
  --xyb-npy build-codex/fixtures/gradient-17x9-xyb.npy \
  --dct8x8-npy build-codex/fixtures/gradient-17x9-dct8x8.npy \
  --default-ac-strategy-npy build-codex/fixtures/gradient-17x9-ac-strategy.npy \
  --raw-quant-field-npy build-codex/fixtures/gradient-17x9-raw-qf.npy \
  --libjxl-ac-strategy-npy build-codex/fixtures/gradient-17x9-libjxl-ac-strategy.npy \
  --ytox-map-npy build-codex/fixtures/gradient-17x9-ytox.npy \
  --ytob-map-npy build-codex/fixtures/gradient-17x9-ytob.npy \
  --dct-only-quantized-ac-npy build-codex/fixtures/gradient-17x9-dct-only-qac.npy \
  --dct-only-num-nonzeros-npy build-codex/fixtures/gradient-17x9-dct-only-nnz.npy \
  --dct-only-quant-dc-npy build-codex/fixtures/gradient-17x9-dct-only-qdc.npy \
  --dct-only-ac-metadata-tokens-npy build-codex/fixtures/gradient-17x9-dct-only-acmeta-tokens.npy \
  --dct-only-prepared-blocks-json build-codex/fixtures/gradient-17x9-dct-only-prepared-blocks.json \
  --distance-params-json build-codex/fixtures/distance-1-params.json \
  --fixed-dct-only-dc-tokens-npy build-codex/fixtures/gradient-17x9-fixed-dc-tokens.npy \
  --fixed-dct-only-ac-metadata-tokens-npy build-codex/fixtures/gradient-17x9-fixed-acmeta-tokens.npy \
  --fixed-dct-only-ac-tokens-npy build-codex/fixtures/gradient-17x9-fixed-ac-tokens.npy \
  --fixed-dct-only-prepared-token-inputs-json build-codex/fixtures/gradient-17x9-prepared-token-inputs.json \
  --fixed-dct-only-frame-bin build-codex/fixtures/gradient-17x9-fixed-frame.bin \
  --fixed-dct-only-codestream-bin build-codex/fixtures/gradient-17x9-fixed.jxl \
  --jxl build-codex/fixtures/gradient-17x9.jxl
```

Set `LIBJXL_TINY` if the reference checkout is not at
`/Users/yunhocho/GitHub/libjxl-tiny`.

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

Convert RTL or simulator `StageTrace` CSV dumps into those token arrays:

```sh
python3 tools/hjxl_trace_tokens.py \
  --trace-csv build-codex/traces/gradient-17x9-stage-trace.csv \
  --width 17 --height 9 \
  --dc-tokens-npy build-codex/traces/gradient-17x9-rtl-dc-tokens.npy \
  --ac-metadata-tokens-npy build-codex/traces/gradient-17x9-rtl-acmeta-tokens.npy \
  --ac-tokens-npy build-codex/traces/gradient-17x9-rtl-ac-tokens.npy \
  --ac-strategy-npy build-codex/traces/gradient-17x9-rtl-ac-strategy.npy
```

The CSV must have `stage,group,index,value` columns. Token rows use `group` as
the token ordinal and are written as `(context, value)` NumPy arrays. AC
strategy rows use `index` as the raster block ordinal and are reshaped using
`--width` and `--height`.

Convert prepared DCT-only block JSON fixtures into simulator input CSVs and
expected quantization trace CSVs:

```sh
python3 tools/hjxl_prepared_blocks.py \
  --prepared-json build-codex/fixtures/gradient-17x9-dct-only-prepared-blocks.json \
  --input-csv build-codex/fixtures/gradient-17x9-prepared-blocks.csv \
  --expected-trace-csv build-codex/fixtures/gradient-17x9-prepared-quantize-trace.csv
```

Convert quantization `StageTrace` CSV dumps into the prepared-token simulator
CSVs consumed by `FramePreparedTokenTraceStage`:

```sh
python3 tools/hjxl_quant_trace_to_prepared_tokens.py \
  --trace-csv build-codex/traces/gradient-17x9-quant-trace.csv \
  --width 17 --height 9 \
  --dc-csv build-codex/traces/gradient-17x9-quant-prepared-dc.csv \
  --ac-csv build-codex/traces/gradient-17x9-quant-prepared-ac.csv
```

Convert prepared-token JSON fixtures into simulator input CSVs:

```sh
python3 tools/hjxl_prepared_token_inputs.py \
  --prepared-json build-codex/fixtures/gradient-17x9-prepared-token-inputs.json \
  --dc-csv build-codex/fixtures/gradient-17x9-prepared-dc.csv \
  --ac-csv build-codex/fixtures/gradient-17x9-prepared-ac.csv
```

`FramePreparedDctOnlyQuantizeTraceStageSpec` checks the prepared-DCT quantizer
scheduler against libjxl-tiny prepared-block fixtures with small fixed-point
tolerances for the current rounded-Q12 coefficient boundary, then verifies that
the observed RTL quantization trace converts into prepared-token DC/AC CSVs
without reordering or dropping records and can drive `FramePreparedTokenTraceStage`
through DC, strategy, metadata, and AC token trace emission. The same spec also
checks `FramePreparedDctOnlyQuantizeTokenTraceStage`, the direct RTL wrapper,
against that staged handoff at the DC/AC token trace streams while checking its
prepared raw-quant/CFL metadata tokens against libjxl-tiny's
`ac_metadata_tokens` oracle. It also converts the direct wrapper's token
`StageTrace` output into token arrays and verifies the host assembler can
produce nonempty frame bytes and a bare JPEG XL codestream.
`FramePreparedAcMetadataTokenTraceStageSpec` separately covers two-tile
prepared metadata fixtures for CFL residual prediction and raw-quant residual
contexts, including a libjxl-tiny oracle-backed 72x8 all-DCT case.
`FixedDctOnlyTokenAssemblySpec` is the current regression for that
hardware/software boundary. It generates a prepared-token JSON fixture with
`tools/hjxl_reference.py`, converts it to simulator CSVs with
`tools/hjxl_prepared_token_inputs.py`, drives `FramePreparedTokenTraceStage`,
converts the resulting StageTrace CSV with `tools/hjxl_trace_tokens.py`, feeds
the generated token arrays back to `tools/hjxl_reference.py`, and checks that
the assembled frame and codestream bytes match the direct libjxl-tiny
fixed-token oracle. The full RGB-input token schedulers still depend on the
approximate fixed-point RGB/XYB/DCT path and are tracked as a separate parity
gap.

## Versions

- Scala 2.13.18
- Chisel 7.13.0
- ScalaTest 3.2.19
- sbt 1.12.13
- Mill 1.1.7

## License

GPLv3. See [LICENSE](LICENSE).
