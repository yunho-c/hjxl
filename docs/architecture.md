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
  flags.
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
- `HjxlCore` currently wires input RGB samples into `FramePadTraceStage`. Later
  stages should replace or consume this trace stream rather than bypassing the
  padding behavior.
- `tools/hjxl_reference.py --input-padded-npy ...` writes the matching
  libjxl-tiny padded-input oracle artifact for small fixtures.
- `RgbToXybApprox` is a standalone Q8-to-Q12 fixed-point approximation of
  libjxl-tiny XYB conversion. `tools/hjxl_reference.py --xyb-npy ...` writes the
  floating-point oracle artifact used to tune and validate future stage tests.
- `Dct8Approx` is a standalone Q12 1D DCT-8 primitive matching libjxl-tiny's
  recursive scaled-DCT structure within fixed-point tolerance. It is the kernel
  for the future 8x8 and rectangular transform stages.

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
