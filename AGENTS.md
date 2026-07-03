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
- `FrameAcStrategyTraceStage` emits one default DCT-first AC strategy value per
  padded block. This matches the current all-8x8-DCT transform path but not
  libjxl-tiny's adaptive 16x8/8x16 strategy search.
- `tools/hjxl_reference.py` can export real libjxl-tiny quant metadata with
  `--raw-quant-field-npy`, `--libjxl-ac-strategy-npy`, `--ytox-map-npy`, and
  `--ytob-map-npy`. Use those artifacts as the oracle before implementing AQ,
  CFL, or transform-strategy RTL; do not compare those outputs against the
  current default AC-strategy trace as if they were equivalent.
- `HjxlCore` currently exposes padded-input, XYB, raw-DCT, or default
  AC-strategy trace streams. `enableDct` has priority over `enableQuant`, which
  has priority over `enableXyb`. Do not describe it as an encoder yet; these
  are traceable pipeline slices.

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
