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
emits frame-shaped `QuantizedAc`, `QuantDc`, and `NumNonzeros` traces with
`traceLast` on the final prepared quantization record. The
prepared-DCT frame boundary uses Q16 DCT coefficients by default so its CFL and
quantization path can match libjxl-tiny's float reference on the oracle fixture;
the RGB-input DCT path still emits Q12 coefficients.
`FramePreparedDctOnlyQuantizeTokenTraceStage` bridges the same prepared-DCT
input boundary directly into fixed all-DCT logical token traces by internally
buffering quantized results and driving `FramePreparedTokenTraceStage`. It also
exposes `traceLast` on the final AC-token trace beat.
The combined prepared-token boundary exposes `traceLast` on the final AC-token
trace beat, so generated prepared-token RTL has a concrete frame delimiter for
host capture.
Standalone prepared DC, AC-metadata, and AC-token tops also expose `traceLast`,
and `PreparedTokenElaborationSpec` checks that generated trace port surface.
`FrameDctOnlyQuantizeTraceStage` schedules padded raster blocks through that
path with fixed distance-1 quantization defaults so future token stages have a
frame-shaped trace source before full AQ/CFL hardware is available.
`FrameDctOnlyDcTokenTraceStage` emits the first logical token stream: DC
predictor contexts and packed residuals in libjxl-tiny Y/X/B plane order, with
`traceLast` on the final DC token.
`DcTokenTraceStage` exposes the same DC predictor/token packing as a prepared
single-sample boundary once quantized DC planes already exist, and
`FramePreparedDcTokenTraceStage` schedules complete prepared quantized DC planes
through that exact token boundary in libjxl-tiny Y/X/B raster order.
`FrameDctOnlyAcMetadataTokenTraceStage` emits fixed-path CFL, AC-strategy,
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
nonzero counts from west/north block history. `FramePreparedTokenTraceStage`
combines the exact prepared DC and AC schedulers with fixed all-DCT AC
strategy/metadata trace generation, producing the four logical trace streams
consumed by the host bitstream assembly boundary. `FrameDctOnlyAcTokenTraceStage`
is the first full RGB-input standalone frame scheduler for `AcTokens`: it emits
nonzero prefixes and coefficient scan/value tokens for every fixed all-DCT
raster block/channel. It is directly tested and exposed through the dedicated
`HjxlAcTokenCore` wrapper. It is also available through `HjxlCore` only when
the core is elaborated with `traceRoute = TraceStage.AcTokens`, keeping the
default all-route shell smaller.
`HjxlCore` also accepts an optional compile-time `traceRoute` parameter used by
simulation tests to instantiate only the selected route.
`HjxlCoreRouteElaborationSpec` checks that the focused AC-token core includes
the heavy scheduler while the default all-route shell omits it. The direct
AC-token scheduler behavior is covered by `FrameDctOnlyAcTokenTraceStageSpec`.
The default still builds the all-route integration shell.
Every current frame trace scheduler/top exposes a `traceLast` sideband on its
final frame trace beat. `HjxlAxiStreamCore` is the first hardware-facing shell:
it accepts raster RGB pixels on an AXI4-Stream-shaped input, generates core
`x/y` coordinates, packs `StageTrace` rows onto an output stream, and maps
`traceLast` to output TLAST for single-frame capture.
`HjxlAxiLiteStreamCore` wraps that stream shell with a minimal 32-bit AXI-Lite
control/status register map for KV260-style integration while keeping the same
AXI4-Stream data path.
`HjxlPreparedDctAxiLiteStreamCore` applies the same control-plane shape to the
prepared-DCT quantize-to-token stream path, which is currently the strongest
RTL-to-codestream parity boundary.

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

Generate the standalone full AC-token trace top-level:

```sh
sbt 'runMain hjxl.ElaborateAcTokens'
```

Generate the public `HjxlCore` IO shell with only the full AC-token route
instantiated:

```sh
sbt 'runMain hjxl.ElaborateCoreAcTokens'
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
  --dct-only-dc-tokens-npy build-codex/fixtures/gradient-17x9-dct-only-dc-tokens.npy \
  --dct-only-ac-tokens-npy build-codex/fixtures/gradient-17x9-dct-only-ac-tokens.npy \
  --dct-only-prepared-blocks-json build-codex/fixtures/gradient-17x9-dct-only-prepared-blocks.json \
  --dct-only-frame-bin build-codex/fixtures/gradient-17x9-dct-only-frame.bin \
  --dct-only-codestream-bin build-codex/fixtures/gradient-17x9-dct-only.jxl \
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

For generated AXI-stream shells, input pixels are raster ordered. Pack
`input.bits.data` as `R` in bits `[15:0]`, `G` in `[31:16]`, and `B` in
`[47:32]` for the default 16-bit pixel configuration. The wrapper checks input
`last` against `FrameConfig.xsize * FrameConfig.ysize` and exposes a sticky
`protocolError`; pulse `clearProtocolError` to clear it without resetting the
core. It also forwards active-route `busy` and `overflow` status for host
polling and fault reporting. Output trace words are packed as
`{value,index,group,stage}`, with
`stage` in the low eight bits. Output `last` is asserted on the final trace
word for each current route: padded input, XYB, raw DCT, quantized traces, DC
tokens, AC-metadata tokens, AC strategy, and the variable-length full AC-token
route all expose a scheduler `traceLast` sideband that the stream wrapper
carries to TLAST.

Convert a linear RGB PFM into an input stream CSV for `HjxlAxiStreamCore` or
`HjxlAxiLiteStreamCore` simulation/DMA bring-up:

```sh
python3 tools/hjxl_rgb_stream.py \
  --pfm build-codex/fixtures/gradient-17x9.pfm \
  --stream-csv build-codex/fixtures/gradient-17x9-rgb-stream.csv \
  --axi-lite-csv build-codex/fixtures/gradient-17x9-rgb-control.csv \
  --manifest-json build-codex/fixtures/gradient-17x9-rgb-manifest.json
```

The converter reads RGB PFM files in top-to-bottom raster order, quantizes
linear samples to signed Q8 by default, packs R/G/B into consecutive
`pixelBits` fields, and asserts `last` only on the final pixel. With
`--axi-lite-csv`, it also emits `address,data,strb` writes for the shared
AXI-Lite register map, deriving `xsize` and `ysize` from the PFM header and
using padded-input route defaults unless route flags such as `--enable-xyb` or
`--enable-dct` are supplied. `--manifest-json` records the source PFM, image
size, stream packing, generated file paths, register map, status/control bits,
and selected config values for host runners. Use `--pixel-bits` and
`--fraction-bits` when simulating a non-default stream configuration.
`HjxlAxiStreamCoreSpec` covers the current host loop from PFM to RGB input
stream CSV, through RTL padded-input trace emission, and back through
`tools/hjxl_stream_trace.py`; `HjxlAxiLiteStreamCoreSpec` covers the same input
stream after programming the DUT from the generated AXI-Lite CSV.

Validate an existing RGB stream/control bundle before replay:

```sh
python3 tools/hjxl_rgb_stream.py \
  --validate-manifest build-codex/fixtures/gradient-17x9-rgb-manifest.json
```

Validation checks manifest format, stream word count, final-only TLAST, stream
CSV columns, AXI-Lite CSV columns, full-byte strobes, and register values
against the manifest's config block.

`HjxlAxiLiteStreamCore` exposes the same input/output streams, but drives
`FrameConfig` through 32-bit AXI-Lite registers:

| Address | Register | Bits |
| --- | --- | --- |
| `0x00` | status/control | read bit 0 = sticky stream `protocolError`, bit 1 = `busy`, bit 2 = `overflow`; write bit 0 = clear protocol error |
| `0x04` | `xsize` | image width |
| `0x08` | `ysize` | image height |
| `0x0c` | `distanceQ8` | distance in Q8 |
| `0x10` | `fixedPointScale` | explicit AC scale override, zero selects lookup |
| `0x14` | `fixedInvQacQ16` | explicit reciprocal AC scale |
| `0x18` | `fixedRawQuant` | raw-quant override, zero selects default |
| `0x1c` | flags | bit 0 `enableXyb`, bit 1 `enableDct`, bit 2 `enableQuant`, bit 3 `enableTokenize`, bits 9:8 `tokenSelect` |

The AXI-Lite wrapper returns OKAY for mapped registers and DECERR for unmapped
word addresses. Byte strobes are honored for writable configuration registers.

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
`tools/hjxl_trace_tokens.py` and `tools/hjxl_trace_to_codestream.py` also accept
the same packed stream captures directly with repeated `--stream-csv` inputs and
the same `--group-bits`, `--trace-value-bits`, and
`--require-stream-final-last` options.

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
  --actual-ac-strategy-npy build-codex/traces/gradient-17x9-rtl-ac-strategy.npy
```

The comparator is exact by default: token stream lengths, contexts, values, and
AC-strategy grid entries must match. `--max-value-delta` only relaxes token
values, not contexts or stream shape, and should be used for diagnostics rather
than parity claims. `TokenCompareToolSpec` covers exact matches, mismatch
diagnostics, and malformed token-array rejection for this helper.

Convert prepared DCT-only block JSON fixtures into simulator input CSVs and
expected quantization trace CSVs:

```sh
python3 tools/hjxl_prepared_blocks.py \
  --prepared-json build-codex/fixtures/gradient-17x9-dct-only-prepared-blocks.json \
  --input-csv build-codex/fixtures/gradient-17x9-prepared-blocks.csv \
  --input-stream-csv build-codex/fixtures/gradient-17x9-prepared-blocks-stream.csv \
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
fixture path for stream-shell simulation and DMA bring-up.
`HjxlPreparedDctAxiStreamCoreSpec` drives that generated stream into RTL and
checks that the resulting packed token trace stream assembles to the same frame
and bare codestream bytes as libjxl-tiny's direct DCT-only path.
`HjxlPreparedDctAxiLiteStreamCore` wraps this prepared-DCT stream shell with the
same 32-bit AXI-Lite register map as `HjxlAxiLiteStreamCore`; `xsize`, `ysize`,
and status/control are consumed directly for framing, busy/overflow visibility,
and protocol-error clearing, while the rest of `FrameConfig` is kept on the
common control surface for future prepared-path experiments. Its
spec programs `xsize`/`ysize` through AXI-Lite, drives the same prepared-block
stream CSV, and checks assembled bytes against libjxl-tiny.

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
scheduler against libjxl-tiny prepared-block fixtures with exact quantized
AC/DC/nonzero trace comparison at the Q16 prepared-coefficient boundary, then
verifies that the observed RTL quantization trace converts into prepared-token
DC/AC CSVs without reordering or dropping records and can drive
`FramePreparedTokenTraceStage` through DC, strategy, metadata, and AC token
trace emission. The same spec also checks
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

## Versions

- Scala 2.13.18
- Chisel 7.13.0
- ScalaTest 3.2.19
- sbt 1.12.13
- Mill 1.1.7

## License

GPLv3. See [LICENSE](LICENSE).
