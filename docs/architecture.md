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
- `QuantizeDct8x8Block` is the first AC quantization primitive. It consumes one
  DCT-only 8x8 coefficient block in Q12, an adjusted raw quant-field byte, the
  distance-derived AC scale in Q16, and an optional X-channel quant-matrix
  multiplier. It applies libjxl-tiny's DCT inverse quant matrices, quadrant
  thresholds, integer rounding, and DC-excluding nonzero count for one channel.
  This is intentionally a block primitive; whole-frame quantization still needs
  AQ/CFL fields, Y roundtrip reconstruction, chroma residual subtraction, and
  quantized DC plane handling before it can claim libjxl-tiny quantized-block
  parity.
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
- The frame-level quantized-block stage remains open: AQ/CFL map plumbing,
  reciprocal/distance scale generation, raster block scheduling, trace emission
  across a frame, and comparison against `tools/hjxl_reference.py` DCT-only
  whole-frame artifacts are still separate work.
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
- `tools/hjxl_reference.py --dct-only-prepared-blocks-json ...` writes a
  structured per-block oracle for the prepared `DctOnlyQuantizeBlock` boundary:
  Q12 coefficients, raw quant, AC/DC scale reciprocals, CFL scalars, and
  libjxl-tiny's expected quantized AC/DC/nonzero outputs. The expected outputs
  come from libjxl-tiny's floating reference path, so comparisons against RTL
  should account for the current fixed-point Q12 coefficient input tolerance
  until frame-level fixed-point staging is locked down.

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
