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
- `FrameXybTraceStage` reuses the same bounded frame buffering and padding
  policy, then emits approximate Q12 XYB samples in channel-first order.
- `HjxlCore` currently routes input RGB samples into `FramePadTraceStage` by
  default, into `FrameXybTraceStage` when `FrameConfig.enableXyb` is true, and
  into `FrameDct8x8TraceStage` when `FrameConfig.enableDct` is true. DCT routing
  has priority over AC-strategy and XYB routing because it consumes the XYB
  approximation internally. `FrameConfig.enableQuant` currently selects
  `FrameAcStrategyTraceStage` when DCT tracing is disabled.
- `tools/hjxl_reference.py --input-padded-npy ...` writes the matching
  libjxl-tiny padded-input oracle artifact for small fixtures.
- `RgbToXybApprox` is a standalone Q8-to-Q12 fixed-point approximation of
  libjxl-tiny XYB conversion. `tools/hjxl_reference.py --xyb-npy ...` writes the
  floating-point oracle artifact used to tune and validate future stage tests.
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
