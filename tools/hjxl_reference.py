#!/usr/bin/env python3
"""Small libjxl-tiny oracle helper for HJXL development.

This script keeps reference exploration out of the RTL code. It can generate a
deterministic linear-RGB fixture and optionally encode it with libjxl-tiny's
educational Python port.
"""

from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path
import sys


DEFAULT_LIBJXL_TINY = Path("/Users/yunhocho/GitHub/libjxl-tiny")


def _load_numpy():
    import numpy as np  # pylint: disable=import-outside-toplevel

    return np


def _libjxl_tiny_root() -> Path:
    return Path(os.environ.get("LIBJXL_TINY", DEFAULT_LIBJXL_TINY)).expanduser()


def _add_libjxl_tiny(root: Path) -> None:
    python_dir = root / "python"
    if not python_dir.is_dir():
        raise SystemExit(f"missing libjxl-tiny Python dir: {python_dir}")
    sys.path.insert(0, str(python_dir))


def generate_fixture(width: int, height: int, pattern: str):
    np = _load_numpy()
    y, x = np.mgrid[0:height, 0:width]
    if pattern == "constant":
        image = np.zeros((3, height, width), dtype=np.float32)
        image[0, :, :] = 0.25
        image[1, :, :] = 0.5
        image[2, :, :] = 0.75
        return image
    if pattern == "gradient":
        denom_x = max(width - 1, 1)
        denom_y = max(height - 1, 1)
        image = np.empty((3, height, width), dtype=np.float32)
        image[0] = x.astype(np.float32) / np.float32(denom_x)
        image[1] = y.astype(np.float32) / np.float32(denom_y)
        image[2] = (x + y).astype(np.float32) / np.float32(denom_x + denom_y)
        return image
    if pattern == "checkerboard":
        board = ((x // 4 + y // 4) & 1).astype(np.float32)
        return np.stack((board, 1.0 - board, board * 0.5), axis=0).astype(np.float32)
    if pattern == "impulse":
        image = np.zeros((3, height, width), dtype=np.float32)
        image[:, height // 2, width // 2] = np.asarray((1.0, 0.5, 0.25), dtype=np.float32)
        return image
    if pattern == "random":
        rng = np.random.default_rng(0)
        return rng.random((3, height, width), dtype=np.float32)
    raise ValueError(f"unknown pattern: {pattern}")


def write_pfm(path: Path, image) -> None:
    np = _load_numpy()
    if image.shape[0] != 3:
        raise ValueError("expected channel-first RGB image")
    rgb = np.moveaxis(image, 0, -1).astype("<f4")
    with path.open("wb") as handle:
        handle.write(f"PF\n{image.shape[2]} {image.shape[1]}\n-1.0\n".encode("ascii"))
        rgb[::-1].tofile(handle)


def encode_with_python_port(image, distance: float) -> bytes:
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.encoder import encode_from_image  # pylint: disable=import-outside-toplevel

    return encode_from_image(image, distance)


def padded_input_from_python_port(image):
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.image import copy_and_pad_image  # pylint: disable=import-outside-toplevel

    return copy_and_pad_image(image)


def xyb_from_python_port(image):
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.image import copy_and_pad_image  # pylint: disable=import-outside-toplevel
    from jxl_tiny.xyb import to_xyb  # pylint: disable=import-outside-toplevel

    return to_xyb(copy_and_pad_image(image))


def dct8x8_from_python_port(image):
    np = _load_numpy()
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.transforms import BLOCK_DIM, scaled_dct_8x8  # pylint: disable=import-outside-toplevel

    xyb = xyb_from_python_port(image)
    blocks = []
    for y0 in range(0, xyb.shape[1], BLOCK_DIM):
        for x0 in range(0, xyb.shape[2], BLOCK_DIM):
            blocks.append(
                [
                    scaled_dct_8x8(
                        xyb[channel, y0 : y0 + BLOCK_DIM, x0 : x0 + BLOCK_DIM]
                    ).reshape(-1)
                    for channel in range(3)
                ]
            )
    return np.asarray(
        blocks,
        dtype=np.float32,
    )


def default_ac_strategy_from_python_port(image):
    np = _load_numpy()
    padded = padded_input_from_python_port(image)
    y_blocks = padded.shape[1] // 8
    x_blocks = padded.shape[2] // 8
    dct_first = (0 << 1) | 1
    return np.full((y_blocks, x_blocks), dct_first, dtype=np.uint8)


def quant_metadata_from_python_port(image, distance: float):
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.encoder import (  # pylint: disable=import-outside-toplevel
        _compute_ac_group_fields,
        _effective_distance,
    )

    xyb = xyb_from_python_port(image)
    _, ysize, xsize = image.shape
    return _compute_ac_group_fields(
        xyb,
        xsize,
        ysize,
        _effective_distance(distance),
    )


def dct_only_quant_outputs_from_python_port(image, distance: float):
    np = _load_numpy()
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.ac_strategy import DCT, adjust_quant_field  # pylint: disable=import-outside-toplevel
    from jxl_tiny.adaptive_quantization import compute_adaptive_quantization  # pylint: disable=import-outside-toplevel
    from jxl_tiny.chroma_from_luma import compute_chroma_from_luma  # pylint: disable=import-outside-toplevel
    from jxl_tiny.encoder import (  # pylint: disable=import-outside-toplevel
        BLOCK_DIM,
        TILE_DIM,
        TILE_DIM_IN_BLOCKS,
        _ceil_div,
        _effective_distance,
    )
    from jxl_tiny.quantization import quantize_ac_group  # pylint: disable=import-outside-toplevel

    xyb = xyb_from_python_port(image)
    _, ysize, xsize = image.shape
    distance = _effective_distance(distance)
    x_blocks = _ceil_div(xsize, BLOCK_DIM)
    y_blocks = _ceil_div(ysize, BLOCK_DIM)
    x_tiles = _ceil_div(xsize, TILE_DIM)
    y_tiles = _ceil_div(ysize, TILE_DIM)

    raw_quant_field = np.empty((y_blocks, x_blocks), dtype=np.uint8)
    ac_strategy = np.full((y_blocks, x_blocks), np.uint8((DCT << 1) | 1))
    ytox_map = np.zeros((y_tiles, x_tiles), dtype=np.int8)
    ytob_map = np.zeros((y_tiles, x_tiles), dtype=np.int8)

    for tile_y in range(y_tiles):
        by0 = tile_y * TILE_DIM_IN_BLOCKS
        tile_blocks_y = min(TILE_DIM_IN_BLOCKS, y_blocks - by0)
        py0 = by0 * BLOCK_DIM
        py1 = py0 + tile_blocks_y * BLOCK_DIM
        for tile_x in range(x_tiles):
            bx0 = tile_x * TILE_DIM_IN_BLOCKS
            tile_blocks_x = min(TILE_DIM_IN_BLOCKS, x_blocks - bx0)
            px0 = bx0 * BLOCK_DIM
            px1 = px0 + tile_blocks_x * BLOCK_DIM
            aq = compute_adaptive_quantization(
                xyb,
                distance,
                block_x0=bx0,
                block_y0=by0,
                block_width=tile_blocks_x,
                block_height=tile_blocks_y,
            )
            raw_quant_field[by0 : by0 + tile_blocks_y, bx0 : bx0 + tile_blocks_x] = (
                aq.raw_quant_field
            )
            cfl = compute_chroma_from_luma(xyb[:, py0:py1, px0:px1])
            ytox_map[tile_y, tile_x] = cfl.ytox
            ytob_map[tile_y, tile_x] = cfl.ytob

    raw_quant_field = adjust_quant_field(raw_quant_field, ac_strategy)
    quantized_blocks = quantize_ac_group(
        xyb, raw_quant_field, ac_strategy, ytox_map, ytob_map, distance
    )

    quantized_ac = np.zeros((y_blocks * x_blocks, 3, 64), dtype=np.int32)
    num_nonzeros = np.zeros((y_blocks, x_blocks, 3), dtype=np.int32)
    num_nonzeros_map = np.zeros((3, y_blocks, x_blocks), dtype=np.uint8)
    quant_dc = np.zeros((3, y_blocks, x_blocks), dtype=np.int16)
    for by in range(y_blocks):
        for bx in range(x_blocks):
            block = quantized_blocks[(by, bx)]
            block_index = by * x_blocks + bx
            quantized_ac[block_index] = block.quantized_ac
            num_nonzeros[by, bx] = block.num_nonzeros
            num_nonzeros_map[:, by : by + 1, bx : bx + 1] = block.num_nonzeros_map
            quant_dc[:, by : by + 1, bx : bx + 1] = block.block_quant_dc

    return raw_quant_field, ytox_map, ytob_map, quantized_ac, num_nonzeros, num_nonzeros_map, quant_dc


def distance_params_from_python_port(distance: float, fixed_raw_quant: int = 5):
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.quantization import (  # pylint: disable=import-outside-toplevel
        K_INV_DC_QUANT,
        compute_distance_params,
    )

    params = compute_distance_params(distance)
    inv_dc_factor_q16 = [
        int(round(float(value) * float(params.scale_dc) * (1 << 16)))
        for value in K_INV_DC_QUANT
    ]
    x_qm_multiplier_q16 = int(round(math.pow(1.25, params.x_qm_scale - 2.0) * (1 << 16)))
    return {
        "format": "hjxl.distance_params.v1",
        "distance": float(distance),
        "distance_q8": int(round(float(distance) * 256.0)),
        "global_scale": int(params.global_scale),
        "quant_dc": int(params.quant_dc),
        "scale_q16": int(params.global_scale),
        "fixed_raw_quant": int(fixed_raw_quant),
        "inv_qac_q16": int((1 << 32) // (int(params.global_scale) * int(fixed_raw_quant))),
        "scale": float(params.scale),
        "scale_dc": float(params.scale_dc),
        "inv_dc_factor_q16": inv_dc_factor_q16,
        "x_qm_scale": int(params.x_qm_scale),
        "x_qm_multiplier_q16": x_qm_multiplier_q16,
        "epf_iters": int(params.epf_iters),
    }


def dct_only_prepared_blocks_from_python_port(image, distance: float):
    np = _load_numpy()
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.ac_strategy import DCT, adjust_quant_field  # pylint: disable=import-outside-toplevel
    from jxl_tiny.adaptive_quantization import compute_adaptive_quantization  # pylint: disable=import-outside-toplevel
    from jxl_tiny.chroma_from_luma import compute_chroma_from_luma  # pylint: disable=import-outside-toplevel
    from jxl_tiny.encoder import (  # pylint: disable=import-outside-toplevel
        BLOCK_DIM,
        TILE_DIM,
        TILE_DIM_IN_BLOCKS,
        _ceil_div,
        _effective_distance,
    )
    from jxl_tiny.quantization import (  # pylint: disable=import-outside-toplevel
        K_INV_DC_QUANT,
        compute_distance_params,
        quantize_ac_group,
    )

    xyb = xyb_from_python_port(image)
    _, ysize, xsize = image.shape
    distance = _effective_distance(distance)
    x_blocks = _ceil_div(xsize, BLOCK_DIM)
    y_blocks = _ceil_div(ysize, BLOCK_DIM)
    x_tiles = _ceil_div(xsize, TILE_DIM)
    y_tiles = _ceil_div(ysize, TILE_DIM)
    params = compute_distance_params(distance)

    raw_quant_field = np.empty((y_blocks, x_blocks), dtype=np.uint8)
    ac_strategy = np.full((y_blocks, x_blocks), np.uint8((DCT << 1) | 1))
    ytox_map = np.zeros((y_tiles, x_tiles), dtype=np.int8)
    ytob_map = np.zeros((y_tiles, x_tiles), dtype=np.int8)

    for tile_y in range(y_tiles):
        by0 = tile_y * TILE_DIM_IN_BLOCKS
        tile_blocks_y = min(TILE_DIM_IN_BLOCKS, y_blocks - by0)
        py0 = by0 * BLOCK_DIM
        py1 = py0 + tile_blocks_y * BLOCK_DIM
        for tile_x in range(x_tiles):
            bx0 = tile_x * TILE_DIM_IN_BLOCKS
            tile_blocks_x = min(TILE_DIM_IN_BLOCKS, x_blocks - bx0)
            px0 = bx0 * BLOCK_DIM
            px1 = px0 + tile_blocks_x * BLOCK_DIM
            aq = compute_adaptive_quantization(
                xyb,
                distance,
                block_x0=bx0,
                block_y0=by0,
                block_width=tile_blocks_x,
                block_height=tile_blocks_y,
            )
            raw_quant_field[by0 : by0 + tile_blocks_y, bx0 : bx0 + tile_blocks_x] = (
                aq.raw_quant_field
            )
            cfl = compute_chroma_from_luma(xyb[:, py0:py1, px0:px1])
            ytox_map[tile_y, tile_x] = cfl.ytox
            ytob_map[tile_y, tile_x] = cfl.ytob

    raw_quant_field = adjust_quant_field(raw_quant_field, ac_strategy)
    quantized_blocks = quantize_ac_group(
        xyb, raw_quant_field, ac_strategy, ytox_map, ytob_map, distance
    )
    scale_q16 = int(params.global_scale)
    x_qm_multiplier_q16 = int(round(math.pow(1.25, params.x_qm_scale - 2.0) * (1 << 16)))
    inv_dc_factor_q16 = [
        int(round(float(value) * float(params.scale_dc) * (1 << 16)))
        for value in K_INV_DC_QUANT
    ]

    blocks = []
    for by in range(y_blocks):
        for bx in range(x_blocks):
            block = quantized_blocks[(by, bx)]
            quant = int(raw_quant_field[by, bx])
            tile_y = by // TILE_DIM_IN_BLOCKS
            tile_x = bx // TILE_DIM_IN_BLOCKS
            inv_qac_q16 = int(round((1.0 / (float(params.scale) * float(quant))) * (1 << 16)))
            blocks.append(
                {
                    "block_index": by * x_blocks + bx,
                    "block_x": bx,
                    "block_y": by,
                    "tile_x": tile_x,
                    "tile_y": tile_y,
                    "inputs": {
                        "coefficients_q12": np.rint(block.raw_coefficients * np.float32(1 << 12)).astype(np.int32).tolist(),
                        "quant": quant,
                        "scale_q16": scale_q16,
                        "inv_qac_q16": inv_qac_q16,
                        "inv_dc_factor_q16": inv_dc_factor_q16,
                        "x_qm_multiplier_q16": x_qm_multiplier_q16,
                        "ytox": int(ytox_map[tile_y, tile_x]),
                        "ytob": int(ytob_map[tile_y, tile_x]),
                    },
                    "expected": {
                        "quantized_ac": block.quantized_ac.astype(np.int32).tolist(),
                        "quantized_dc": block.block_quant_dc.reshape(3).astype(np.int32).tolist(),
                        "num_nonzeros": block.num_nonzeros.astype(np.int32).tolist(),
                    },
                }
            )

    return {
        "format": "hjxl.dct_only_prepared_blocks.v1",
        "image": {
            "xsize": int(xsize),
            "ysize": int(ysize),
            "x_blocks": int(x_blocks),
            "y_blocks": int(y_blocks),
        },
        "distance": float(distance),
        "distance_params": {
            "global_scale": int(params.global_scale),
            "quant_dc": int(params.quant_dc),
            "scale_q16": int(scale_q16),
            "scale_dc": float(params.scale_dc),
            "x_qm_scale": int(params.x_qm_scale),
            "epf_iters": int(params.epf_iters),
        },
        "blocks": blocks,
    }


def fixed_dct_only_token_outputs_from_python_port(
    image, distance: float, fixed_raw_quant: int = 5
):
    np = _load_numpy()
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.ac_strategy import DCT  # pylint: disable=import-outside-toplevel
    from jxl_tiny.encoder import (  # pylint: disable=import-outside-toplevel
        BLOCK_DIM,
        TILE_DIM,
        _ceil_div,
        _effective_distance,
    )
    from jxl_tiny.quantization import quantize_ac_group  # pylint: disable=import-outside-toplevel
    from jxl_tiny.tokenization import (  # pylint: disable=import-outside-toplevel
        ac_metadata_tokens,
        ac_tokens_from_quantized_blocks,
        dc_tokens,
    )

    xyb = xyb_from_python_port(image)
    _, ysize, xsize = image.shape
    distance = _effective_distance(distance)
    x_blocks = _ceil_div(xsize, BLOCK_DIM)
    y_blocks = _ceil_div(ysize, BLOCK_DIM)
    x_tiles = _ceil_div(xsize, TILE_DIM)
    y_tiles = _ceil_div(ysize, TILE_DIM)

    raw_quant_field = np.full((y_blocks, x_blocks), fixed_raw_quant, dtype=np.uint8)
    ac_strategy = np.full((y_blocks, x_blocks), np.uint8((DCT << 1) | 1))
    ytox_map = np.zeros((y_tiles, x_tiles), dtype=np.int8)
    ytob_map = np.zeros((y_tiles, x_tiles), dtype=np.int8)
    quantized_blocks = quantize_ac_group(
        xyb, raw_quant_field, ac_strategy, ytox_map, ytob_map, distance
    )

    quant_dc = np.zeros((3, y_blocks, x_blocks), dtype=np.int16)
    nzeros_map = np.zeros((3, y_blocks, x_blocks), dtype=np.uint8)
    for by in range(y_blocks):
        for bx in range(x_blocks):
            block = quantized_blocks[(by, bx)]
            quant_dc[:, by : by + 1, bx : bx + 1] = block.block_quant_dc
            nzeros_map[:, by : by + 1, bx : bx + 1] = block.num_nonzeros_map

    return (
        dc_tokens(quant_dc),
        ac_metadata_tokens(ytox_map, ytob_map, ac_strategy, raw_quant_field),
        ac_tokens_from_quantized_blocks(ac_strategy, quantized_blocks, nzeros_map),
        ac_strategy,
    )


def fixed_dct_only_prepared_token_inputs_from_python_port(
    image, distance: float, fixed_raw_quant: int = 5
):
    np = _load_numpy()
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.ac_strategy import DCT  # pylint: disable=import-outside-toplevel
    from jxl_tiny.encoder import (  # pylint: disable=import-outside-toplevel
        BLOCK_DIM,
        TILE_DIM,
        _ceil_div,
        _effective_distance,
    )
    from jxl_tiny.quantization import quantize_ac_group  # pylint: disable=import-outside-toplevel

    xyb = xyb_from_python_port(image)
    _, ysize, xsize = image.shape
    distance = _effective_distance(distance)
    x_blocks = _ceil_div(xsize, BLOCK_DIM)
    y_blocks = _ceil_div(ysize, BLOCK_DIM)
    x_tiles = _ceil_div(xsize, TILE_DIM)
    y_tiles = _ceil_div(ysize, TILE_DIM)

    raw_quant_field = np.full((y_blocks, x_blocks), fixed_raw_quant, dtype=np.uint8)
    ac_strategy = np.full((y_blocks, x_blocks), np.uint8((DCT << 1) | 1))
    ytox_map = np.zeros((y_tiles, x_tiles), dtype=np.int8)
    ytob_map = np.zeros((y_tiles, x_tiles), dtype=np.int8)
    quantized_blocks = quantize_ac_group(
        xyb, raw_quant_field, ac_strategy, ytox_map, ytob_map, distance
    )

    quant_dc = np.zeros((3, y_blocks, x_blocks), dtype=np.int16)
    ac_blocks = []
    for by in range(y_blocks):
        for bx in range(x_blocks):
            block = quantized_blocks[(by, bx)]
            quant_dc[:, by : by + 1, bx : bx + 1] = block.block_quant_dc
            ac_blocks.append(
                {
                    "block_index": int(by * x_blocks + bx),
                    "block_x": int(bx),
                    "block_y": int(by),
                    "num_nonzeros": block.num_nonzeros.astype(np.int32).tolist(),
                    "quantized": block.quantized_ac.astype(np.int32).tolist(),
                }
            )

    dc_channel_order = [1, 0, 2]
    dc_samples = [
        int(value)
        for channel in dc_channel_order
        for value in quant_dc[channel].reshape(-1).astype(np.int32)
    ]

    return {
        "format": "hjxl.fixed_dct_only_prepared_token_inputs.v1",
        "image": {
            "xsize": int(xsize),
            "ysize": int(ysize),
            "x_blocks": int(x_blocks),
            "y_blocks": int(y_blocks),
        },
        "distance": float(distance),
        "fixed_raw_quant": int(fixed_raw_quant),
        "dc_sample_order": "Y-plane raster, X-plane raster, B-plane raster",
        "dc_samples": dc_samples,
        "ac_block_order": "raster blocks, each block stores X/Y/B channels",
        "ac_blocks": ac_blocks,
    }


def fixed_dct_only_bitstream_outputs_from_python_port(
    image, distance: float, fixed_raw_quant: int = 5
):
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.bitstream import (  # pylint: disable=import-outside-toplevel
        codestream_bytes,
        frame_bytes,
    )

    dc, ac_metadata, ac, ac_strategy = fixed_dct_only_token_outputs_from_python_port(
        image, distance, fixed_raw_quant
    )
    _, ysize, xsize = image.shape
    frame = frame_bytes(dc, ac_metadata, ac, ac_strategy, distance)
    codestream = codestream_bytes(xsize, ysize, dc, ac_metadata, ac, ac_strategy, distance)
    return frame, codestream


def bitstream_outputs_from_token_inputs(
    xsize: int,
    ysize: int,
    distance: float,
    dc_tokens_path: Path,
    ac_metadata_tokens_path: Path,
    ac_tokens_path: Path,
    ac_strategy_path: Path,
) -> tuple[bytes, bytes]:
    np = _load_numpy()
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.bitstream import (  # pylint: disable=import-outside-toplevel
        codestream_bytes,
        frame_bytes,
    )

    dc_tokens = np.asarray(np.load(dc_tokens_path), dtype=np.uint32)
    ac_metadata_tokens = np.asarray(np.load(ac_metadata_tokens_path), dtype=np.uint32)
    ac_tokens = np.asarray(np.load(ac_tokens_path), dtype=np.uint32)
    ac_strategy = np.asarray(np.load(ac_strategy_path), dtype=np.uint8)
    frame = frame_bytes(dc_tokens, ac_metadata_tokens, ac_tokens, ac_strategy, distance)
    codestream = codestream_bytes(
        xsize, ysize, dc_tokens, ac_metadata_tokens, ac_tokens, ac_strategy, distance
    )
    return frame, codestream


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--width", type=int, default=17)
    parser.add_argument("--height", type=int, default=9)
    parser.add_argument(
        "--pattern",
        choices=("constant", "gradient", "checkerboard", "impulse", "random"),
        default="gradient",
    )
    parser.add_argument("--distance", type=float, default=1.0)
    parser.add_argument(
        "--fixed-raw-quant",
        type=int,
        default=5,
        help="fixed all-DCT adjusted raw quant value for fixed-token oracles",
    )
    parser.add_argument("--pfm", type=Path, help="optional PFM output path")
    parser.add_argument("--jxl", type=Path, help="optional Python-port JXL output path")
    parser.add_argument(
        "--input-padded-npy",
        type=Path,
        help="optional libjxl-tiny input_padded NumPy output path",
    )
    parser.add_argument("--xyb-npy", type=Path, help="optional libjxl-tiny XYB NumPy output path")
    parser.add_argument(
        "--dct8x8-npy",
        type=Path,
        help="optional libjxl-tiny raster 8x8 XYB DCT blocks NumPy output path",
    )
    parser.add_argument(
        "--default-ac-strategy-npy",
        type=Path,
        help="optional default DCT-first AC strategy map NumPy output path",
    )
    parser.add_argument(
        "--raw-quant-field-npy",
        type=Path,
        help="optional libjxl-tiny adjusted raw quant field NumPy output path",
    )
    parser.add_argument(
        "--libjxl-ac-strategy-npy",
        type=Path,
        help="optional libjxl-tiny searched AC strategy NumPy output path",
    )
    parser.add_argument("--ytox-map-npy", type=Path, help="optional libjxl-tiny Y-to-X CFL map")
    parser.add_argument("--ytob-map-npy", type=Path, help="optional libjxl-tiny Y-to-B CFL map")
    parser.add_argument(
        "--dct-only-raw-quant-field-npy",
        type=Path,
        help="optional adjusted raw quant field for the default all-DCT strategy",
    )
    parser.add_argument(
        "--dct-only-ytox-map-npy",
        type=Path,
        help="optional Y-to-X CFL map for the default all-DCT strategy",
    )
    parser.add_argument(
        "--dct-only-ytob-map-npy",
        type=Path,
        help="optional Y-to-B CFL map for the default all-DCT strategy",
    )
    parser.add_argument(
        "--dct-only-quantized-ac-npy",
        type=Path,
        help="optional quantized AC blocks for the default all-DCT strategy",
    )
    parser.add_argument(
        "--dct-only-num-nonzeros-npy",
        type=Path,
        help="optional raw nonzero counts per block/channel for the default all-DCT strategy",
    )
    parser.add_argument(
        "--dct-only-num-nonzeros-map-npy",
        type=Path,
        help="optional nonzero-count map for the default all-DCT strategy",
    )
    parser.add_argument(
        "--dct-only-quant-dc-npy",
        type=Path,
        help="optional quantized DC planes for the default all-DCT strategy",
    )
    parser.add_argument(
        "--dct-only-prepared-blocks-json",
        type=Path,
        help="optional prepared-block JSON oracle for the default all-DCT strategy",
    )
    parser.add_argument(
        "--distance-params-json",
        type=Path,
        help="optional libjxl-tiny distance parameter JSON for the requested distance",
    )
    parser.add_argument(
        "--fixed-dct-only-dc-tokens-npy",
        type=Path,
        help="optional fixed-quant all-DCT DC token oracle with rows (context, value)",
    )
    parser.add_argument(
        "--fixed-dct-only-ac-metadata-tokens-npy",
        type=Path,
        help="optional fixed-quant all-DCT AC-metadata token oracle with rows (context, value)",
    )
    parser.add_argument(
        "--fixed-dct-only-ac-tokens-npy",
        type=Path,
        help="optional fixed-quant all-DCT AC token oracle with rows (context, value)",
    )
    parser.add_argument(
        "--fixed-dct-only-prepared-token-inputs-json",
        type=Path,
        help="optional prepared DC/AC inputs for FramePreparedTokenTraceStage",
    )
    parser.add_argument(
        "--fixed-dct-only-frame-bin",
        type=Path,
        help="optional fixed-quant all-DCT frame bytes serialized from logical tokens",
    )
    parser.add_argument(
        "--fixed-dct-only-codestream-bin",
        type=Path,
        help="optional fixed-quant all-DCT bare codestream bytes serialized from logical tokens",
    )
    parser.add_argument(
        "--token-input-dc-tokens-npy",
        type=Path,
        help="input logical DC tokens with rows (context, value) for host bitstream assembly",
    )
    parser.add_argument(
        "--token-input-ac-metadata-tokens-npy",
        type=Path,
        help="input logical AC metadata tokens with rows (context, value)",
    )
    parser.add_argument(
        "--token-input-ac-tokens-npy",
        type=Path,
        help="input logical AC tokens with rows (context, value)",
    )
    parser.add_argument(
        "--token-input-ac-strategy-npy",
        type=Path,
        help="input encoded AC strategy grid matching the token groups",
    )
    parser.add_argument(
        "--token-input-frame-bin",
        type=Path,
        help="optional frame bytes assembled from token-input npy files",
    )
    parser.add_argument(
        "--token-input-codestream-bin",
        type=Path,
        help="optional bare codestream bytes assembled from token-input npy files",
    )
    args = parser.parse_args()
    if args.fixed_raw_quant <= 0 or args.fixed_raw_quant > 255:
        raise SystemExit("--fixed-raw-quant must be in the range 1..255")

    image = generate_fixture(args.width, args.height, args.pattern)
    quant_metadata = None
    dct_only_quant_outputs = None
    fixed_dct_only_token_outputs = None
    fixed_dct_only_prepared_token_inputs = None
    fixed_dct_only_bitstream_outputs = None
    token_input_bitstream_outputs = None

    def get_quant_metadata():
        nonlocal quant_metadata
        if quant_metadata is None:
            quant_metadata = quant_metadata_from_python_port(image, args.distance)
        return quant_metadata

    def get_dct_only_quant_outputs():
        nonlocal dct_only_quant_outputs
        if dct_only_quant_outputs is None:
            dct_only_quant_outputs = dct_only_quant_outputs_from_python_port(image, args.distance)
        return dct_only_quant_outputs

    def get_fixed_dct_only_token_outputs():
        nonlocal fixed_dct_only_token_outputs
        if fixed_dct_only_token_outputs is None:
            fixed_dct_only_token_outputs = fixed_dct_only_token_outputs_from_python_port(
                image, args.distance, args.fixed_raw_quant
            )
        return fixed_dct_only_token_outputs

    def get_fixed_dct_only_prepared_token_inputs():
        nonlocal fixed_dct_only_prepared_token_inputs
        if fixed_dct_only_prepared_token_inputs is None:
            fixed_dct_only_prepared_token_inputs = (
                fixed_dct_only_prepared_token_inputs_from_python_port(
                    image, args.distance, args.fixed_raw_quant
                )
            )
        return fixed_dct_only_prepared_token_inputs

    def get_fixed_dct_only_bitstream_outputs():
        nonlocal fixed_dct_only_bitstream_outputs
        if fixed_dct_only_bitstream_outputs is None:
            fixed_dct_only_bitstream_outputs = fixed_dct_only_bitstream_outputs_from_python_port(
                image, args.distance, args.fixed_raw_quant
            )
        return fixed_dct_only_bitstream_outputs

    def get_token_input_bitstream_outputs():
        nonlocal token_input_bitstream_outputs
        if token_input_bitstream_outputs is None:
            required = {
                "--token-input-dc-tokens-npy": args.token_input_dc_tokens_npy,
                "--token-input-ac-metadata-tokens-npy": args.token_input_ac_metadata_tokens_npy,
                "--token-input-ac-tokens-npy": args.token_input_ac_tokens_npy,
                "--token-input-ac-strategy-npy": args.token_input_ac_strategy_npy,
            }
            missing = [flag for flag, path in required.items() if path is None]
            if missing:
                raise SystemExit(
                    "token-input bitstream assembly requires " + ", ".join(missing)
                )
            token_input_bitstream_outputs = bitstream_outputs_from_token_inputs(
                args.width,
                args.height,
                args.distance,
                args.token_input_dc_tokens_npy,
                args.token_input_ac_metadata_tokens_npy,
                args.token_input_ac_tokens_npy,
                args.token_input_ac_strategy_npy,
            )
        return token_input_bitstream_outputs

    if args.pfm is not None:
        args.pfm.parent.mkdir(parents=True, exist_ok=True)
        write_pfm(args.pfm, image)
    if args.jxl is not None:
        args.jxl.parent.mkdir(parents=True, exist_ok=True)
        args.jxl.write_bytes(encode_with_python_port(image, args.distance))
    if args.input_padded_npy is not None:
        np = _load_numpy()
        args.input_padded_npy.parent.mkdir(parents=True, exist_ok=True)
        np.save(args.input_padded_npy, padded_input_from_python_port(image))
    if args.xyb_npy is not None:
        np = _load_numpy()
        args.xyb_npy.parent.mkdir(parents=True, exist_ok=True)
        np.save(args.xyb_npy, xyb_from_python_port(image))
    if args.dct8x8_npy is not None:
        np = _load_numpy()
        args.dct8x8_npy.parent.mkdir(parents=True, exist_ok=True)
        np.save(args.dct8x8_npy, dct8x8_from_python_port(image))
    if args.default_ac_strategy_npy is not None:
        np = _load_numpy()
        args.default_ac_strategy_npy.parent.mkdir(parents=True, exist_ok=True)
        np.save(args.default_ac_strategy_npy, default_ac_strategy_from_python_port(image))
    if args.raw_quant_field_npy is not None:
        np = _load_numpy()
        args.raw_quant_field_npy.parent.mkdir(parents=True, exist_ok=True)
        raw_quant_field, _, _, _ = get_quant_metadata()
        np.save(args.raw_quant_field_npy, raw_quant_field)
    if args.libjxl_ac_strategy_npy is not None:
        np = _load_numpy()
        args.libjxl_ac_strategy_npy.parent.mkdir(parents=True, exist_ok=True)
        _, ac_strategy, _, _ = get_quant_metadata()
        np.save(args.libjxl_ac_strategy_npy, ac_strategy)
    if args.ytox_map_npy is not None:
        np = _load_numpy()
        args.ytox_map_npy.parent.mkdir(parents=True, exist_ok=True)
        _, _, ytox_map, _ = get_quant_metadata()
        np.save(args.ytox_map_npy, ytox_map)
    if args.ytob_map_npy is not None:
        np = _load_numpy()
        args.ytob_map_npy.parent.mkdir(parents=True, exist_ok=True)
        _, _, _, ytob_map = get_quant_metadata()
        np.save(args.ytob_map_npy, ytob_map)
    if args.dct_only_raw_quant_field_npy is not None:
        np = _load_numpy()
        args.dct_only_raw_quant_field_npy.parent.mkdir(parents=True, exist_ok=True)
        raw_quant_field, _, _, _, _, _, _ = get_dct_only_quant_outputs()
        np.save(args.dct_only_raw_quant_field_npy, raw_quant_field)
    if args.dct_only_ytox_map_npy is not None:
        np = _load_numpy()
        args.dct_only_ytox_map_npy.parent.mkdir(parents=True, exist_ok=True)
        _, ytox_map, _, _, _, _, _ = get_dct_only_quant_outputs()
        np.save(args.dct_only_ytox_map_npy, ytox_map)
    if args.dct_only_ytob_map_npy is not None:
        np = _load_numpy()
        args.dct_only_ytob_map_npy.parent.mkdir(parents=True, exist_ok=True)
        _, _, ytob_map, _, _, _, _ = get_dct_only_quant_outputs()
        np.save(args.dct_only_ytob_map_npy, ytob_map)
    if args.dct_only_quantized_ac_npy is not None:
        np = _load_numpy()
        args.dct_only_quantized_ac_npy.parent.mkdir(parents=True, exist_ok=True)
        _, _, _, quantized_ac, _, _, _ = get_dct_only_quant_outputs()
        np.save(args.dct_only_quantized_ac_npy, quantized_ac)
    if args.dct_only_num_nonzeros_npy is not None:
        np = _load_numpy()
        args.dct_only_num_nonzeros_npy.parent.mkdir(parents=True, exist_ok=True)
        _, _, _, _, num_nonzeros, _, _ = get_dct_only_quant_outputs()
        np.save(args.dct_only_num_nonzeros_npy, num_nonzeros)
    if args.dct_only_num_nonzeros_map_npy is not None:
        np = _load_numpy()
        args.dct_only_num_nonzeros_map_npy.parent.mkdir(parents=True, exist_ok=True)
        _, _, _, _, _, num_nonzeros_map, _ = get_dct_only_quant_outputs()
        np.save(args.dct_only_num_nonzeros_map_npy, num_nonzeros_map)
    if args.dct_only_quant_dc_npy is not None:
        np = _load_numpy()
        args.dct_only_quant_dc_npy.parent.mkdir(parents=True, exist_ok=True)
        _, _, _, _, _, _, quant_dc = get_dct_only_quant_outputs()
        np.save(args.dct_only_quant_dc_npy, quant_dc)
    if args.dct_only_prepared_blocks_json is not None:
        args.dct_only_prepared_blocks_json.parent.mkdir(parents=True, exist_ok=True)
        args.dct_only_prepared_blocks_json.write_text(
            json.dumps(dct_only_prepared_blocks_from_python_port(image, args.distance), indent=2),
            encoding="utf-8",
        )
    if args.distance_params_json is not None:
        args.distance_params_json.parent.mkdir(parents=True, exist_ok=True)
        args.distance_params_json.write_text(
            json.dumps(distance_params_from_python_port(args.distance, args.fixed_raw_quant), indent=2),
            encoding="utf-8",
        )
    if args.fixed_dct_only_dc_tokens_npy is not None:
        np = _load_numpy()
        args.fixed_dct_only_dc_tokens_npy.parent.mkdir(parents=True, exist_ok=True)
        dc, _, _, _ = get_fixed_dct_only_token_outputs()
        np.save(args.fixed_dct_only_dc_tokens_npy, dc)
    if args.fixed_dct_only_ac_metadata_tokens_npy is not None:
        np = _load_numpy()
        args.fixed_dct_only_ac_metadata_tokens_npy.parent.mkdir(parents=True, exist_ok=True)
        _, ac_metadata, _, _ = get_fixed_dct_only_token_outputs()
        np.save(args.fixed_dct_only_ac_metadata_tokens_npy, ac_metadata)
    if args.fixed_dct_only_ac_tokens_npy is not None:
        np = _load_numpy()
        args.fixed_dct_only_ac_tokens_npy.parent.mkdir(parents=True, exist_ok=True)
        _, _, ac, _ = get_fixed_dct_only_token_outputs()
        np.save(args.fixed_dct_only_ac_tokens_npy, ac)
    if args.fixed_dct_only_prepared_token_inputs_json is not None:
        args.fixed_dct_only_prepared_token_inputs_json.parent.mkdir(parents=True, exist_ok=True)
        args.fixed_dct_only_prepared_token_inputs_json.write_text(
            json.dumps(get_fixed_dct_only_prepared_token_inputs(), indent=2),
            encoding="utf-8",
        )
    if args.fixed_dct_only_frame_bin is not None:
        args.fixed_dct_only_frame_bin.parent.mkdir(parents=True, exist_ok=True)
        frame, _ = get_fixed_dct_only_bitstream_outputs()
        args.fixed_dct_only_frame_bin.write_bytes(frame)
    if args.fixed_dct_only_codestream_bin is not None:
        args.fixed_dct_only_codestream_bin.parent.mkdir(parents=True, exist_ok=True)
        _, codestream = get_fixed_dct_only_bitstream_outputs()
        args.fixed_dct_only_codestream_bin.write_bytes(codestream)
    if args.token_input_frame_bin is not None:
        args.token_input_frame_bin.parent.mkdir(parents=True, exist_ok=True)
        frame, _ = get_token_input_bitstream_outputs()
        args.token_input_frame_bin.write_bytes(frame)
    if args.token_input_codestream_bin is not None:
        args.token_input_codestream_bin.parent.mkdir(parents=True, exist_ok=True)
        _, codestream = get_token_input_bitstream_outputs()
        args.token_input_codestream_bin.write_bytes(codestream)
    if (
        args.pfm is None
        and args.jxl is None
        and args.input_padded_npy is None
        and args.xyb_npy is None
        and args.dct8x8_npy is None
        and args.default_ac_strategy_npy is None
        and args.raw_quant_field_npy is None
        and args.libjxl_ac_strategy_npy is None
        and args.ytox_map_npy is None
        and args.ytob_map_npy is None
        and args.dct_only_raw_quant_field_npy is None
        and args.dct_only_ytox_map_npy is None
        and args.dct_only_ytob_map_npy is None
        and args.dct_only_quantized_ac_npy is None
        and args.dct_only_num_nonzeros_npy is None
        and args.dct_only_num_nonzeros_map_npy is None
        and args.dct_only_quant_dc_npy is None
        and args.dct_only_prepared_blocks_json is None
        and args.distance_params_json is None
        and args.fixed_dct_only_dc_tokens_npy is None
        and args.fixed_dct_only_ac_metadata_tokens_npy is None
        and args.fixed_dct_only_ac_tokens_npy is None
        and args.fixed_dct_only_prepared_token_inputs_json is None
        and args.fixed_dct_only_frame_bin is None
        and args.fixed_dct_only_codestream_bin is None
        and args.token_input_frame_bin is None
        and args.token_input_codestream_bin is None
    ):
        print(f"generated {args.pattern} fixture: shape={image.shape}, dtype={image.dtype}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
