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
libjxl-tiny.
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
records for simulation. `FrameDctOnlyQuantizeTraceStage` schedules padded raster
blocks through that path with fixed distance-1 quantization defaults so future
token stages have a frame-shaped trace source before full AQ/CFL hardware is
available. `FrameDctOnlyDcTokenTraceStage` emits the first logical token stream:
DC predictor contexts and packed residuals in libjxl-tiny Y/X/B plane order.
`DcTokenTraceStage` exposes the same DC predictor/token packing as a prepared
single-sample boundary once quantized DC planes already exist.
`FrameDctOnlyAcMetadataTokenTraceStage` emits fixed-path CFL, AC-strategy,
quant-field, and block-metadata tokens.
`FrameDctOnlyAcNonzeroTokenTraceStage` is a directly tested standalone frame
stage for the first `AcTokens` substream: nonzero-count contexts and values for
each block/channel. `AcCoefficientTokenTraceStage` emits prepared-block AC
coefficient scan tokens with libjxl-tiny's DCT coefficient order and
zero-density contexts, and `AcBlockTokenTraceStage` combines the nonzero prefix
with those coefficient tokens for one prepared block/channel.
`DctOnlyAcBlockTokenTraceStage` sequences the prepared Y/X/B channel streams
for one all-DCT block. `FrameDctOnlyAcTokenTraceStage` is the first full
standalone frame scheduler for `AcTokens`: it emits nonzero prefixes and
coefficient scan/value tokens for every fixed all-DCT raster block/channel. It
is directly tested and exposed through the dedicated `HjxlAcTokenCore` wrapper,
but not routed through the runtime-multiplexed `HjxlCore`.

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

Generate a small libjxl-tiny reference fixture:

```sh
python3 tools/hjxl_reference.py --width 17 --height 9 --pattern gradient \
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
  --dct-only-prepared-blocks-json build-codex/fixtures/gradient-17x9-dct-only-prepared-blocks.json \
  --fixed-dct-only-dc-tokens-npy build-codex/fixtures/gradient-17x9-fixed-dc-tokens.npy \
  --fixed-dct-only-ac-metadata-tokens-npy build-codex/fixtures/gradient-17x9-fixed-acmeta-tokens.npy \
  --fixed-dct-only-ac-tokens-npy build-codex/fixtures/gradient-17x9-fixed-ac-tokens.npy \
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

## Versions

- Scala 2.13.18
- Chisel 7.13.0
- ScalaTest 3.2.19
- sbt 1.12.13
- Mill 1.1.7

## License

GPLv3. See [LICENSE](LICENSE).
