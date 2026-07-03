# hjxl

Chisel RTL for a hardware-accelerated JPEG XL encoder targeting practical
parity with
[libjxl-tiny](https://github.com/libjxl/libjxl-tiny), not the full JPEG XL
encoder surface.

Current RTL status: the top-level buffers a small RGB frame and emits one of
four trace streams: libjxl-tiny-compatible `input_padded`, padded channel-first
XYB when `enableXyb` is set, raster 8x8-block raw scaled-DCT coefficients when
`enableDct` is set, or the current all-DCT AC strategy map when `enableQuant` is
set. Later stages will extend that transform path into adaptive quantization and
tokenization. Standalone fixed-point primitives exist for approximate
RGB-to-XYB, 1D DCT-8, and the scaled 8x8 DCT block layout used by libjxl-tiny.

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
  --jxl build-codex/fixtures/gradient-17x9.jxl
```

Set `LIBJXL_TINY` if the reference checkout is not at
`/Users/yunhocho/GitHub/libjxl-tiny`.

## Versions

- Scala 2.13.18
- Chisel 7.13.0
- ScalaTest 3.2.19
- sbt 1.12.13
- Mill 1.1.7

## License

GPLv3. See [LICENSE](LICENSE).
