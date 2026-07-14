#!/usr/bin/env python3
"""Small libjxl-tiny oracle helper for HJXL development.

This script keeps reference exploration out of the RTL code. It can generate a
deterministic linear-RGB fixture and optionally encode it with libjxl-tiny's
educational Python port.
"""

from __future__ import annotations

import argparse
import csv
from functools import lru_cache
import json
import math
import os
from pathlib import Path
import sys


DEFAULT_LIBJXL_TINY = Path("/Users/yunhocho/GitHub/libjxl-tiny")
SIGNED_INT32_MAX = (1 << 31) - 1
SIGNED_INT32_MIN = -(1 << 31)


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


def write_xyb_q12_csv(path: Path, image) -> None:
    """Write the signed-Q8 RGB to signed-Q12 XYB hardware oracle seam."""
    np = _load_numpy()
    rgb_q8 = np.rint(np.asarray(image, dtype=np.float64) * (1 << 8)).astype(np.int64)
    if np.any(rgb_q8 < np.iinfo(np.int16).min) or np.any(rgb_q8 > np.iinfo(np.int16).max):
        raise ValueError("RGB Q8 fixture does not fit signed 16-bit")
    quantized_rgb = (rgb_q8.astype(np.float32) / np.float32(1 << 8)).astype(np.float32)
    padded_rgb = padded_input_from_python_port(quantized_rgb)
    xyb_q12 = np.rint(xyb_from_python_port(quantized_rgb) * np.float32(1 << 12)).astype(
        np.int64
    )
    padded_rgb_q8 = np.rint(padded_rgb * np.float32(1 << 8)).astype(np.int64)

    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(
            (
                "raster",
                "x",
                "y",
                "r_q8",
                "g_q8",
                "b_q8",
                "xyb_x_q12",
                "xyb_y_q12",
                "xyb_b_q12",
            )
        )
        raster = 0
        for y in range(padded_rgb.shape[1]):
            for x in range(padded_rgb.shape[2]):
                writer.writerow(
                    (
                        raster,
                        x,
                        y,
                        int(padded_rgb_q8[0, y, x]),
                        int(padded_rgb_q8[1, y, x]),
                        int(padded_rgb_q8[2, y, x]),
                        int(xyb_q12[0, y, x]),
                        int(xyb_q12[1, y, x]),
                        int(xyb_q12[2, y, x]),
                    )
                )
                raster += 1


def aq_contrast_pre_erosion_from_xyb(xyb):
    """Return libjxl-tiny's global quarter-resolution pre-erosion grid."""
    np = _load_numpy()
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.adaptive_quantization import (  # pylint: disable=import-outside-toplevel
        _masking_sqrt,
        _ratio_cubic_root_to_simple_gamma,
    )

    source = np.asarray(xyb, dtype=np.float32)
    if source.ndim != 3 or source.shape[0] != 3:
        raise ValueError("expected channel-first XYB image with shape (3, y, x)")
    if source.shape[1] % 4 != 0 or source.shape[2] % 4 != 0:
        raise ValueError("AQ contrast input dimensions must be multiples of four")

    height = source.shape[1]
    width = source.shape[2]
    contributions = np.empty((height, width), dtype=np.float32)
    match_gamma_offset = np.float32(0.019)
    x_multiplier = np.float32(23.426802998210313)
    for y in range(height):
        up = y - 1 if y > 0 else y
        down = y + 1 if y + 1 < height else y
        for x in range(width):
            left = x - 1 if x > 0 else x
            right = x + 1 if x + 1 < width else x
            base_y = np.float32(
                np.float32(0.25)
                * np.float32(
                    source[1, down, x]
                    + source[1, up, x]
                    + source[1, y, left]
                    + source[1, y, right]
                )
            )
            gamma = _ratio_cubic_root_to_simple_gamma(
                np.float32(source[1, y, x] + match_gamma_offset)
            )
            difference_y = np.float32(
                gamma * np.float32(source[1, y, x] - base_y)
            )
            difference_y = np.float32(difference_y * difference_y)
            base_x = np.float32(
                np.float32(0.25)
                * np.float32(
                    source[0, down, x]
                    + source[0, up, x]
                    + source[0, y, left]
                    + source[0, y, right]
                )
            )
            difference_x = np.float32(
                gamma * np.float32(source[0, y, x] - base_x)
            )
            difference_x = np.float32(difference_x * difference_x)
            difference = np.float32(difference_y + np.float32(x_multiplier * difference_x))
            contributions[y, x] = _masking_sqrt(difference)

    cells = np.empty((height // 4, width // 4), dtype=np.float32)
    for cell_y in range(height // 4):
        for cell_x in range(width // 4):
            columns = []
            for local_x in range(4):
                column = np.float32(contributions[cell_y * 4, cell_x * 4 + local_x])
                for local_y in range(1, 4):
                    column = np.float32(
                        column
                        + contributions[cell_y * 4 + local_y, cell_x * 4 + local_x]
                    )
                columns.append(column)
            total = np.float32(columns[0] + columns[1] + columns[2] + columns[3])
            cells[cell_y, cell_x] = np.float32(np.float32(0.25) * total)
    return cells


def aq_fuzzy_erosion_from_pre_erosion(pre_erosion):
    """Reconstruct libjxl-tiny fuzzy erosion over a full-frame contrast grid."""
    np = _load_numpy()
    source = np.asarray(pre_erosion, dtype=np.float32)
    if source.ndim != 2 or source.shape[0] % 2 != 0 or source.shape[1] % 2 != 0:
        raise ValueError("AQ fuzzy-erosion input must be an even two-dimensional grid")

    height, width = source.shape
    output = np.zeros((height // 2, width // 2), dtype=np.float32)
    for y in range(height):
        up = y - 1 if y > 0 else y
        down = y + 1 if y + 1 < height else y
        for x in range(width):
            left = x - 1 if x > 0 else x
            right = x + 1 if x + 1 < width else x
            neighborhood = sorted(
                np.float32(source[neighbor_y, neighbor_x])
                for neighbor_y in (up, y, down)
                for neighbor_x in (left, x, right)
            )
            value = np.float32(
                np.float32(0.05)
                * np.float32(
                    source[y, x]
                    + neighborhood[0]
                    + neighborhood[1]
                    + neighborhood[2]
                    + neighborhood[3]
                )
            )
            output_y = y // 2
            output_x = x // 2
            if x % 2 == 0 and y % 2 == 0:
                output[output_y, output_x] = value
            else:
                output[output_y, output_x] = np.float32(
                    output[output_y, output_x] + value
                )
    return output


def aq_strategy_mask_from_fuzzy_erosion(fuzzy_erosion):
    """Reconstruct the pre-modulation mask used by AC-strategy scoring."""
    np = _load_numpy()
    source = np.asarray(fuzzy_erosion, dtype=np.float32)
    return np.asarray(
        np.float32(1.0) / np.asarray(source + np.float32(0.001), dtype=np.float32),
        dtype=np.float32,
    )


def fixed_aq_strategy_mask_q16(fuzzy_erosion_q16: int) -> int:
    """Convert prepared Q16 erosion to Q16 strategy mask with rational 0.001."""
    if fuzzy_erosion_q16 < 0 or fuzzy_erosion_q16 > SIGNED_INT32_MAX:
        raise ValueError("AQ fuzzy-erosion input must fit positive signed 32-bit Q16")
    scale = 1 << 16
    offset_denominator = 1000
    numerator = scale * scale * offset_denominator
    denominator = fuzzy_erosion_q16 * offset_denominator + scale
    return (numerator + denominator // 2) // denominator


def aq_nonlinear_mask_from_fuzzy_erosion(fuzzy_erosion):
    """Reconstruct `_compute_mask`, the log-domain seed for AQ modulation."""
    np = _load_numpy()
    source = np.asarray(fuzzy_erosion, dtype=np.float32)
    output = np.empty_like(source)

    base = np.float32(-0.74174993)
    mul4 = np.float32(3.2353257320940401)
    mul2 = np.float32(12.906028311180409)
    offset2 = np.float32(305.04035728311436)
    mul3 = np.float32(5.0220313103171232)
    offset3 = np.float32(2.1925739705298404)
    offset4 = np.float32(np.float32(0.25) * offset3)
    mul0 = np.float32(0.74760422233706747)

    for index in np.ndindex(source.shape):
        value1 = max(np.float32(source[index] * mul0), np.float32(1e-3))
        value1_squared = np.float32(value1 * value1)
        value2 = np.float32(np.float32(1.0) / np.float32(value1 + offset2))
        value3 = np.float32(np.float32(1.0) / np.float32(value1_squared + offset3))
        value4 = np.float32(np.float32(1.0) / np.float32(value1_squared + offset4))
        output[index] = np.float32(
            base
            + np.float32(mul4 * value4)
            + np.float32(mul2 * value2)
            + np.float32(mul3 * value3)
        )
    return output


def fixed_aq_nonlinear_mask_q24(fuzzy_erosion_q16: int) -> int:
    """Convert positive Q16 erosion to signed Q24 `_compute_mask` output."""
    if fuzzy_erosion_q16 < 0 or fuzzy_erosion_q16 > SIGNED_INT32_MAX:
        raise ValueError("AQ fuzzy-erosion input must fit positive signed 32-bit Q16")

    fraction_bits = 24
    base_q24 = -12444499
    mul4_q24 = 54279760
    mul2_q24 = 216527232
    offset2_q24 = 5117727744
    mul3_q24 = 84255704
    offset3_q24 = 36785288
    offset4_q24 = 9196322
    mul0_q24 = 12542718
    minimum_q24 = 16777

    value1_q24 = max(
        (fuzzy_erosion_q16 * mul0_q24 + (1 << 15)) >> 16,
        minimum_q24,
    )
    value1_squared_q24 = (
        value1_q24 * value1_q24 + (1 << (fraction_bits - 1))
    ) >> fraction_bits

    def rounded_term(multiplier_q24: int, denominator_q24: int) -> int:
        numerator = multiplier_q24 << fraction_bits
        return (numerator + denominator_q24 // 2) // denominator_q24

    result = (
        base_q24
        + rounded_term(mul4_q24, value1_squared_q24 + offset4_q24)
        + rounded_term(mul2_q24, value1_q24 + offset2_q24)
        + rounded_term(mul3_q24, value1_squared_q24 + offset3_q24)
    )
    if result < SIGNED_INT32_MIN or result > SIGNED_INT32_MAX:
        raise ValueError("AQ nonlinear-mask Q24 result does not fit signed 32-bit")
    return result


def aq_hf_modulation_from_xyb(xyb, nonlinear_mask):
    """Add libjxl-tiny's per-block Y high-frequency term to AQ seeds."""
    np = _load_numpy()
    source = np.asarray(xyb, dtype=np.float32)
    seeds = np.asarray(nonlinear_mask, dtype=np.float32)
    if source.ndim != 3 or source.shape[0] != 3:
        raise ValueError("expected channel-first XYB image with shape (3, y, x)")
    if source.shape[1] % 8 or source.shape[2] % 8:
        raise ValueError("AQ HF modulation input dimensions must be multiples of eight")
    expected_shape = (source.shape[1] // 8, source.shape[2] // 8)
    if seeds.shape != expected_shape:
        raise ValueError(
            f"AQ HF modulation seeds must have shape {expected_shape}, got {seeds.shape}"
        )

    multiplier = np.float32(-2.0052193233688884 / 112.0)
    output = np.empty_like(seeds)
    for block_y in range(expected_shape[0]):
        for block_x in range(expected_shape[1]):
            total = np.float32(0.0)
            pixel_y0 = block_y * 8
            pixel_x0 = block_x * 8
            for local_y in range(8):
                y = pixel_y0 + local_y
                for local_x in range(8):
                    x = pixel_x0 + local_x
                    if local_x != 7:
                        total = np.float32(
                            total
                            + abs(np.float32(source[1, y, x] - source[1, y, x + 1]))
                        )
                    if local_y != 7:
                        total = np.float32(
                            total
                            + abs(np.float32(source[1, y, x] - source[1, y + 1, x]))
                        )
            output[block_y, block_x] = np.float32(
                np.float32(total * multiplier) + seeds[block_y, block_x]
            )
    return output


def fixed_aq_hf_modulation_q24(seed_q24: int, xyb_y_q12) -> int:
    """Apply the HJXL Q32 HF coefficient to one prepared Q12 Y block."""
    np = _load_numpy()
    if seed_q24 < SIGNED_INT32_MIN or seed_q24 > SIGNED_INT32_MAX:
        raise ValueError("AQ HF modulation seed must fit signed 32-bit Q24")
    samples = np.asarray(xyb_y_q12)
    if samples.shape != (8, 8):
        raise ValueError(f"AQ HF modulation Y block must have shape (8, 8), got {samples.shape}")
    if not np.issubdtype(samples.dtype, np.integer):
        raise ValueError("AQ HF modulation Y block must contain integer Q12 samples")
    if np.any(samples < SIGNED_INT32_MIN) or np.any(samples > SIGNED_INT32_MAX):
        raise ValueError("AQ HF modulation Y samples must fit signed 32-bit Q12")

    total_q12 = 0
    for local_y in range(8):
        for local_x in range(8):
            center = int(samples[local_y, local_x])
            if local_x != 7:
                total_q12 += abs(center - int(samples[local_y, local_x + 1]))
            if local_y != 7:
                total_q12 += abs(center - int(samples[local_y + 1, local_x]))

    multiplier_q32 = 76895992
    product_shift = 20
    magnitude_q24 = (
        total_q12 * multiplier_q32 + (1 << (product_shift - 1))
    ) >> product_shift
    return max(SIGNED_INT32_MIN, min(SIGNED_INT32_MAX, seed_q24 - magnitude_q24))


def aq_color_modulation_from_xyb(xyb, hf_modulation, distance: float):
    """Add libjxl-tiny's per-block red/blue color modulation to AQ seeds."""
    np = _load_numpy()
    source = np.asarray(xyb, dtype=np.float32)
    seeds = np.asarray(hf_modulation, dtype=np.float32)
    if source.ndim != 3 or source.shape[0] != 3:
        raise ValueError("expected channel-first XYB image with shape (3, y, x)")
    if source.shape[1] % 8 or source.shape[2] % 8:
        raise ValueError("AQ color modulation input dimensions must be multiples of eight")
    expected_shape = (source.shape[1] // 8, source.shape[2] // 8)
    if seeds.shape != expected_shape:
        raise ValueError(
            f"AQ color modulation seeds must have shape {expected_shape}, got {seeds.shape}"
        )

    strength_mul = np.float32(2.177823400325309)
    red_ramp_start = np.float32(0.0073200141118951231)
    red_ramp_length = np.float32(0.019421555948474039)
    blue_ramp_length = np.float32(0.086890611400405895)
    blue_ramp_start = np.float32(0.26973418507870539)
    strength = np.float32(
        strength_mul
        * np.float32(np.float32(1.0) - np.float32(0.25) * np.float32(distance))
    )
    if strength < 0:
        return np.array(seeds, dtype=np.float32, copy=True)

    ratio = np.float32(30.610615782142737)
    red_strength = np.float32(strength * np.float32(5.992297772961519))
    baseline = np.float32(strength * np.float32(-0.009174542291185913))
    red_cap = np.float32(ratio * red_ramp_length)
    blue_cap = np.float32(ratio * blue_ramp_length)
    red_coefficient = np.float32(red_strength / ratio)
    blue_coefficient = np.float32(strength / ratio)

    output = np.empty_like(seeds)
    for block_y in range(expected_shape[0]):
        for block_x in range(expected_shape[1]):
            red_coverage = np.float32(0.0)
            blue_coverage = np.float32(0.0)
            pixel_y0 = block_y * 8
            pixel_x0 = block_x * 8
            for local_y in range(8):
                y = pixel_y0 + local_y
                for local_x in range(8):
                    x = pixel_x0 + local_x
                    pixel_x = max(
                        np.float32(0.0),
                        np.float32(source[0, y, x] - red_ramp_start),
                    )
                    pixel_y = np.float32(source[1, y, x])
                    pixel_b = max(
                        np.float32(0.0),
                        np.float32(
                            source[2, y, x]
                            - np.float32(pixel_y + blue_ramp_start)
                        ),
                    )
                    red_coverage = np.float32(
                        red_coverage + min(pixel_x, red_ramp_length)
                    )
                    blue_coverage = np.float32(
                        blue_coverage + min(pixel_b, blue_ramp_length)
                    )

            overall_red = np.float32(
                min(red_coverage, red_cap) * red_coefficient
            )
            overall_blue = np.float32(
                min(blue_coverage, blue_cap) * blue_coefficient
            )
            output[block_y, block_x] = np.float32(
                overall_red
                + overall_blue
                + np.float32(np.float32(seeds[block_y, block_x]) + baseline)
            )
    return output


def fixed_aq_color_modulation_q24(
    seed_q24: int,
    distance_q8: int,
    xyb_q12,
) -> int:
    """Apply HJXL's Q16-coverage color modulation to one prepared XYB block."""
    np = _load_numpy()
    if seed_q24 < SIGNED_INT32_MIN or seed_q24 > SIGNED_INT32_MAX:
        raise ValueError("AQ color modulation seed must fit signed 32-bit Q24")
    if distance_q8 < 0 or distance_q8 > np.iinfo(np.uint16).max:
        raise ValueError("AQ color modulation distance must fit unsigned 16-bit Q8")
    samples = np.asarray(xyb_q12)
    if samples.shape != (3, 8, 8):
        raise ValueError(
            f"AQ color modulation XYB block must have shape (3, 8, 8), got {samples.shape}"
        )
    if not np.issubdtype(samples.dtype, np.integer):
        raise ValueError("AQ color modulation block must contain integer Q12 samples")
    if np.any(samples < SIGNED_INT32_MIN) or np.any(samples > SIGNED_INT32_MAX):
        raise ValueError("AQ color modulation samples must fit signed 32-bit Q12")

    distance_delta_q10 = max(0, 1024 - distance_q8)
    if distance_delta_q10 == 0:
        return seed_q24

    red_ramp_start_q16 = 480
    red_ramp_length_q16 = 1273
    blue_ramp_start_q16 = 17677
    blue_ramp_length_q16 = 5694
    red_coverage_cap_q16 = 38962
    blue_coverage_cap_q16 = 174311
    baseline_magnitude_combined_q24 = 335218
    red_combined_q24 = 7152598
    blue_combined_q24 = 1193632

    red_coverage_q16 = 0
    blue_coverage_q16 = 0
    for local_y in range(8):
        for local_x in range(8):
            x_q16 = int(samples[0, local_y, local_x]) << 4
            b_minus_y_q16 = (
                int(samples[2, local_y, local_x])
                - int(samples[1, local_y, local_x])
            ) << 4
            red_coverage_q16 += min(
                max(0, x_q16 - red_ramp_start_q16),
                red_ramp_length_q16,
            )
            blue_coverage_q16 += min(
                max(0, b_minus_y_q16 - blue_ramp_start_q16),
                blue_ramp_length_q16,
            )

    red_coverage_q16 = min(red_coverage_q16, red_coverage_cap_q16)
    blue_coverage_q16 = min(blue_coverage_q16, blue_coverage_cap_q16)

    def distance_scaled_q24(combined_q24: int) -> int:
        return (combined_q24 * distance_delta_q10 + (1 << 9)) >> 10

    baseline_magnitude_q24 = distance_scaled_q24(
        baseline_magnitude_combined_q24
    )
    red_coefficient_q24 = distance_scaled_q24(red_combined_q24)
    blue_coefficient_q24 = distance_scaled_q24(blue_combined_q24)
    red_q24 = (
        red_coverage_q16 * red_coefficient_q24 + (1 << 15)
    ) >> 16
    blue_q24 = (
        blue_coverage_q16 * blue_coefficient_q24 + (1 << 15)
    ) >> 16
    result = seed_q24 - baseline_magnitude_q24 + red_q24 + blue_q24
    return max(SIGNED_INT32_MIN, min(SIGNED_INT32_MAX, result))


def _aq_inverse_gamma_ratio_float32(value):
    """Independent float32 reconstruction of the inverted gamma ratio."""
    np = _load_numpy()
    epsilon = np.float32(1e-2)
    sg_mul = np.float32(226.0480446705883)
    sg_mul2 = np.float32(1.0 / 73.377132366608819)
    log2 = np.float32(0.693147181)
    return_mul = np.float32(sg_mul2 * np.float32(18.6580932135) * log2)
    value_offset = np.float32(7.14672470003)
    normalized = max(np.float32(value), np.float32(0.0))
    value_squared = np.float32(normalized * normalized)
    numerator_multiplier = np.float32(
        np.float32(return_mul * np.float32(3.0)) * sg_mul
    )
    numerator = np.float32(
        np.float32(numerator_multiplier * value_squared) + epsilon
    )
    denominator_multiplier = np.float32(log2 * sg_mul)
    denominator = np.float32(
        np.float32(np.float32(denominator_multiplier * normalized) * value_squared)
        + np.float32(np.float32(value_offset * log2) + epsilon)
    )
    return np.float32(numerator / denominator)


def _aq_fast_log2_float32(value):
    """Independent float32 reconstruction of libjxl-tiny `fast_log2f`."""
    np = _load_numpy()
    input_value = np.float32(value)
    coefficients_p = (
        np.float32(-1.8503833400518310e-06),
        np.float32(1.4287160470083755),
        np.float32(0.74245873327820566),
    )
    coefficients_q = (
        np.float32(0.99032814277590719),
        np.float32(1.0096718572241148),
        np.float32(0.17409343003366823),
    )
    input_bits = np.asarray(input_value, dtype=np.float32).view(np.int32)[()]
    exponent_bits = np.int32(input_bits - np.int32(0x3F2AAAAB))
    exponent = np.int32(exponent_bits >> np.int32(23))
    mantissa = np.asarray(
        np.int32(input_bits - np.int32(exponent << np.int32(23))),
        dtype=np.int32,
    ).view(np.float32)[()]
    reduced = np.float32(mantissa - np.float32(1.0))
    numerator = np.float32(
        np.float32(coefficients_p[2] * reduced) + coefficients_p[1]
    )
    denominator = np.float32(
        np.float32(coefficients_q[2] * reduced) + coefficients_q[1]
    )
    numerator = np.float32(
        np.float32(numerator * reduced) + coefficients_p[0]
    )
    denominator = np.float32(
        np.float32(denominator * reduced) + coefficients_q[0]
    )
    return np.float32(
        np.float32(numerator / denominator) + np.float32(exponent)
    )


def aq_gamma_modulation_from_xyb(xyb, color_modulation):
    """Add libjxl-tiny's per-block gamma term to cumulative AQ seeds."""
    np = _load_numpy()
    source = np.asarray(xyb, dtype=np.float32)
    seeds = np.asarray(color_modulation, dtype=np.float32)
    if source.ndim != 3 or source.shape[0] != 3:
        raise ValueError("expected channel-first XYB image with shape (3, y, x)")
    if source.shape[1] % 8 or source.shape[2] % 8:
        raise ValueError("AQ gamma modulation input dimensions must be multiples of eight")
    expected_shape = (source.shape[1] // 8, source.shape[2] // 8)
    if seeds.shape != expected_shape:
        raise ValueError(
            f"AQ gamma modulation seeds must have shape {expected_shape}, got {seeds.shape}"
        )

    output = np.empty_like(seeds)
    gamma_multiplier = np.float32(
        np.float32(-0.15526878023684174) * np.float32(0.693147180559945)
    )
    for block_y in range(expected_shape[0]):
        for block_x in range(expected_shape[1]):
            overall_ratio = np.float32(0.0)
            pixel_y0 = block_y * 8
            pixel_x0 = block_x * 8
            for local_y in range(8):
                y = pixel_y0 + local_y
                for local_x in range(8):
                    x = pixel_x0 + local_x
                    input_y = np.float32(source[1, y, x] + np.float32(0.16))
                    input_x = np.float32(source[0, y, x])
                    ratio_r = _aq_inverse_gamma_ratio_float32(
                        np.float32(input_y - input_x)
                    )
                    ratio_g = _aq_inverse_gamma_ratio_float32(
                        np.float32(input_y + input_x)
                    )
                    overall_ratio = np.float32(
                        overall_ratio
                        + np.float32(
                            np.float32(0.5) * np.float32(ratio_r + ratio_g)
                        )
                    )
            overall_ratio = np.float32(overall_ratio * np.float32(1.0 / 64.0))
            contribution = np.float32(
                gamma_multiplier * _aq_fast_log2_float32(overall_ratio)
            )
            output[block_y, block_x] = np.float32(
                contribution + seeds[block_y, block_x]
            )
    return output


@lru_cache(maxsize=1)
def _aq_gamma_fixed_tables():
    """Return the integer tables shared by the Python and Chisel models."""
    ratio_scale = 1 << 20

    def rounded_ratio(value_q12: int) -> int:
        value = float(_aq_inverse_gamma_ratio_float32(value_q12 / float(1 << 12)))
        return int(math.floor(value * ratio_scale + 0.5))

    fine = tuple(rounded_ratio(value) for value in range(0, 257))
    coarse = tuple(rounded_ratio(value) for value in range(256, 4097, 16))
    hdr = tuple(rounded_ratio(value) for value in range(4096, 32769, 64))
    log_table = tuple(
        max(
            0,
            int(
                math.floor(
                    float(_aq_fast_log2_float32(1.0 + index / 256.0))
                    * (1 << 20)
                    + 0.5
                )
            ),
        )
        for index in range(257)
    )
    return fine, coarse, hdr, log_table


def fixed_aq_inverse_gamma_ratio_q20(value_q12: int) -> int:
    """Evaluate HJXL's clamped piecewise inverse-ratio approximation."""
    fine, coarse, hdr, _ = _aq_gamma_fixed_tables()
    clamped = max(0, min(8 << 12, int(value_q12)))

    def interpolate(table, relative: int, step_bits: int) -> int:
        base = relative >> step_bits
        if base >= len(table) - 1:
            return table[-1]
        fraction = relative & ((1 << step_bits) - 1)
        scale = 1 << step_bits
        return (
            table[base] * (scale - fraction)
            + table[base + 1] * fraction
            + scale // 2
        ) >> step_bits

    if clamped <= 256:
        return fine[clamped]
    if clamped <= 4096:
        return interpolate(coarse, clamped - 256, 4)
    return interpolate(hdr, clamped - 4096, 6)


def fixed_aq_fast_log2_q20(value_q20: int) -> int:
    """Evaluate HJXL's normalized Q20 `fast_log2f` approximation."""
    tables = _aq_gamma_fixed_tables()
    _, _, _, table = tables
    maximum_ratio = max(max(values) for values in tables[:3])
    if value_q20 <= 0 or value_q20 > maximum_ratio:
        raise ValueError("AQ gamma fixed log input is out of range")
    leading_index = int(value_q20).bit_length() - 1
    normalized = int(value_q20) << (20 - leading_index)
    relative = normalized - (1 << 20)
    index = relative >> 12
    fraction = relative & ((1 << 12) - 1)
    mantissa = (
        table[index] * ((1 << 12) - fraction)
        + table[index + 1] * fraction
        + (1 << 11)
    ) >> 12
    return (leading_index - 20) * (1 << 20) + mantissa


def fixed_aq_gamma_modulation_q24(seed_q24: int, xyb_q12) -> int:
    """Apply HJXL's Q20 ratio/log gamma modulation to one X/Y block."""
    np = _load_numpy()
    if seed_q24 < SIGNED_INT32_MIN or seed_q24 > SIGNED_INT32_MAX:
        raise ValueError("AQ gamma modulation seed must fit signed 32-bit Q24")
    samples = np.asarray(xyb_q12)
    if samples.shape not in ((2, 8, 8), (3, 8, 8)):
        raise ValueError(
            f"AQ gamma modulation X/Y block must have shape (2, 8, 8) or "
            f"(3, 8, 8), got {samples.shape}"
        )
    if not np.issubdtype(samples.dtype, np.integer):
        raise ValueError("AQ gamma modulation block must contain integer Q12 samples")
    if np.any(samples < SIGNED_INT32_MIN) or np.any(samples > SIGNED_INT32_MAX):
        raise ValueError("AQ gamma modulation samples must fit signed 32-bit Q12")

    ratio_sum_q20 = 0
    for local_y in range(8):
        for local_x in range(8):
            input_y_q12 = int(samples[1, local_y, local_x]) + 655
            input_x_q12 = int(samples[0, local_y, local_x])
            ratio_sum_q20 += fixed_aq_inverse_gamma_ratio_q20(
                input_y_q12 - input_x_q12
            )
            ratio_sum_q20 += fixed_aq_inverse_gamma_ratio_q20(
                input_y_q12 + input_x_q12
            )
    average_ratio_q20 = (ratio_sum_q20 + 64) >> 7
    log_q20 = fixed_aq_fast_log2_q20(average_ratio_q20)
    product = log_q20 * -1805633
    if product >= 0:
        contribution_q24 = (product + (1 << 19)) >> 20
    else:
        contribution_q24 = -(((-product) + (1 << 19)) >> 20)
    result = seed_q24 + contribution_q24
    return max(SIGNED_INT32_MIN, min(SIGNED_INT32_MAX, result))


def _aq_fast_pow2_float32(value):
    """Independent float32 reconstruction of libjxl-tiny `fast_pow2f`."""
    np = _load_numpy()
    input_value = np.float32(value)
    floor_value = math.floor(float(input_value))
    exponent_bits = np.int32(np.int32(floor_value + 127) << np.int32(23))
    exponent = np.asarray(exponent_bits, dtype=np.int32).view(np.float32)[()]
    fraction = np.float32(input_value - np.float32(floor_value))
    numerator = np.float32(fraction + np.float32(1.01749063e1))
    numerator = np.float32(
        np.float32(numerator * fraction) + np.float32(4.88687798e1)
    )
    numerator = np.float32(
        np.float32(numerator * fraction) + np.float32(9.85506591e1)
    )
    numerator = np.float32(numerator * exponent)
    denominator = np.float32(
        np.float32(fraction * np.float32(2.10242958e-1))
        + np.float32(-2.22328856e-2)
    )
    denominator = np.float32(
        np.float32(denominator * fraction) + np.float32(-1.94414990e1)
    )
    denominator = np.float32(
        np.float32(denominator * fraction) + np.float32(9.85506633e1)
    )
    return np.float32(numerator / denominator)


def aq_final_modulation_from_gamma(gamma_modulation, distance: float):
    """Apply libjxl-tiny's final power/scale/dampen operation."""
    np = _load_numpy()
    seeds = np.asarray(gamma_modulation, dtype=np.float32)
    reference_distance = float(np.float32(distance))
    scale = np.float32(np.float32(0.8294) / np.float32(reference_distance))
    base_level = np.float32(np.float32(0.5) * scale)
    dampen = np.float32(1.0)
    if reference_distance >= 7.0:
        dampen = np.float32(
            1.0 - ((reference_distance - 7.0) / (14.0 - 7.0))
        )
        if dampen < 0:
            dampen = np.float32(0.0)
    multiplier = np.float32(scale * dampen)
    add = np.float32(np.float32(np.float32(1.0) - dampen) * base_level)
    output = np.empty_like(seeds)
    for index in np.ndindex(seeds.shape):
        power = _aq_fast_pow2_float32(
            np.float32(seeds[index] * np.float32(1.442695041))
        )
        output[index] = np.float32(np.float32(power * multiplier) + add)
    return output


@lru_cache(maxsize=1)
def _aq_fast_pow2_q24_table():
    np = _load_numpy()
    return tuple(
        int(
            math.floor(
                float(_aq_fast_pow2_float32(np.float32(index / 256.0)))
                * (1 << 24)
                + 0.5
            )
        )
        for index in range(257)
    )


def _round_shift_signed(value: int, shift: int) -> int:
    if value >= 0:
        return (value + (1 << (shift - 1))) >> shift
    return -(((-value) + (1 << (shift - 1))) >> shift)


def fixed_aq_fast_exp_q24(seed_q24: int) -> int:
    """Approximate `fast_pow2f(seed * log2(e))` as unsigned Q24."""
    if seed_q24 < SIGNED_INT32_MIN or seed_q24 > SIGNED_INT32_MAX:
        raise ValueError("AQ final-modulation seed must fit signed 32-bit Q24")
    log2_q24 = _round_shift_signed(int(seed_q24) * 24204406, 24)
    exponent = log2_q24 >> 24
    fraction = log2_q24 - (exponent << 24)
    index = fraction >> 16
    interpolation = fraction & 0xFFFF
    table = _aq_fast_pow2_q24_table()
    mantissa = (
        table[index] * (65536 - interpolation)
        + table[index + 1] * interpolation
        + 32768
    ) >> 16
    if exponent >= 0:
        shifted = mantissa << exponent
    elif -exponent > 26:
        shifted = 0
    else:
        shifted = (mantissa + (1 << (-exponent - 1))) >> -exponent
    return min((1 << 32) - 1, shifted)


def fixed_aq_final_scalars_q24(distance: float) -> tuple[int, int, int]:
    """Return Q24 scale, dampen, and inverse global AC scale."""
    np = _load_numpy()
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.adaptive_quantization import (  # pylint: disable=import-outside-toplevel
        inverse_global_ac_scale,
    )

    reference_distance = float(np.float32(distance))
    scale = np.float32(np.float32(0.8294) / np.float32(reference_distance))
    dampen = np.float32(1.0)
    if reference_distance >= 7.0:
        dampen = np.float32(
            1.0 - ((reference_distance - 7.0) / (14.0 - 7.0))
        )
        if dampen < 0:
            dampen = np.float32(0.0)
    inv_global_scale = inverse_global_ac_scale(reference_distance)

    def q24(value) -> int:
        return int(math.floor(float(value) * (1 << 24) + 0.5))

    values = q24(scale), q24(dampen), q24(inv_global_scale)
    if any(value < 0 or value > np.iinfo(np.uint32).max for value in values):
        raise ValueError("AQ final-modulation scalar does not fit unsigned Q24")
    return values


def fixed_aq_final_modulation_q24(
    seed_q24: int,
    scale_q24: int,
    dampen_q24: int,
) -> int:
    """Apply HJXL's fixed final power/scale/dampen operation."""
    maximum = (1 << 32) - 1
    if scale_q24 < 0 or scale_q24 > maximum:
        raise ValueError("AQ final scale must fit unsigned Q24")
    if dampen_q24 < 0 or dampen_q24 > (1 << 24):
        raise ValueError("AQ final dampen must be in Q24 [0, 1]")

    def multiply_q24(left: int, right: int) -> int:
        return min(maximum, (left * right + (1 << 23)) >> 24)

    power_q24 = fixed_aq_fast_exp_q24(seed_q24)
    base_level_q24 = (scale_q24 + 1) >> 1
    multiplier_q24 = multiply_q24(scale_q24, dampen_q24)
    add_q24 = multiply_q24((1 << 24) - dampen_q24, base_level_q24)
    return min(maximum, multiply_q24(power_q24, multiplier_q24) + add_q24)


def fixed_aq_raw_quant(aq_map_q24: int, inv_global_scale_q24: int) -> int:
    """Convert one fixed final AQ-map sample to the adjusted raw-quant byte."""
    rounded = (
        int(aq_map_q24) * int(inv_global_scale_q24) + (1 << 47)
    ) >> 48
    return min(255, max(1, rounded))


def capture_aq_nonlinear_mask_from_xyb(
    xyb,
    block_x0: int = 0,
    block_y0: int = 0,
    block_width: int | None = None,
    block_height: int | None = None,
):
    """Capture real `_compute_mask` outputs from one reference AQ rectangle."""
    np = _load_numpy()
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny import adaptive_quantization  # pylint: disable=import-outside-toplevel

    source = np.asarray(xyb, dtype=np.float32)
    if block_width is None:
        block_width = source.shape[2] // 8 - block_x0
    if block_height is None:
        block_height = source.shape[1] // 8 - block_y0

    captured = []
    original_compute_mask = adaptive_quantization._compute_mask

    def capture_compute_mask(value):
        result = original_compute_mask(value)
        captured.append(result)
        return result

    adaptive_quantization._compute_mask = capture_compute_mask
    try:
        adaptive_quantization.compute_adaptive_quantization(
            source,
            distance=1.0,
            block_x0=block_x0,
            block_y0=block_y0,
            block_width=block_width,
            block_height=block_height,
        )
    finally:
        adaptive_quantization._compute_mask = original_compute_mask

    expected_count = block_width * block_height
    if len(captured) != expected_count:
        raise RuntimeError(
            f"libjxl-tiny AQ captured {len(captured)} nonlinear-mask values; "
            f"expected {expected_count}"
        )
    return np.asarray(captured, dtype=np.float32).reshape(block_height, block_width)


def capture_aq_hf_modulation_from_xyb(
    xyb,
    block_x0: int = 0,
    block_y0: int = 0,
    block_width: int | None = None,
    block_height: int | None = None,
):
    """Capture real `_hf_modulation` outputs from one reference AQ rectangle."""
    np = _load_numpy()
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny import adaptive_quantization  # pylint: disable=import-outside-toplevel

    source = np.asarray(xyb, dtype=np.float32)
    if block_width is None:
        block_width = source.shape[2] // 8 - block_x0
    if block_height is None:
        block_height = source.shape[1] // 8 - block_y0

    captured = []
    original_hf_modulation = adaptive_quantization._hf_modulation

    def capture_hf_modulation(xyb_y, block_x, block_y, out_val):
        result = original_hf_modulation(xyb_y, block_x, block_y, out_val)
        captured.append(result)
        return result

    adaptive_quantization._hf_modulation = capture_hf_modulation
    try:
        adaptive_quantization.compute_adaptive_quantization(
            source,
            distance=1.0,
            block_x0=block_x0,
            block_y0=block_y0,
            block_width=block_width,
            block_height=block_height,
        )
    finally:
        adaptive_quantization._hf_modulation = original_hf_modulation

    expected_count = block_width * block_height
    if len(captured) != expected_count:
        raise RuntimeError(
            f"libjxl-tiny AQ captured {len(captured)} HF-modulation values; "
            f"expected {expected_count}"
        )
    return np.asarray(captured, dtype=np.float32).reshape(block_height, block_width)


def capture_aq_color_modulation_from_xyb(
    xyb,
    distance: float,
    block_x0: int = 0,
    block_y0: int = 0,
    block_width: int | None = None,
    block_height: int | None = None,
):
    """Capture real `_color_modulation` outputs from one reference AQ rectangle."""
    np = _load_numpy()
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny import adaptive_quantization  # pylint: disable=import-outside-toplevel

    source = np.asarray(xyb, dtype=np.float32)
    if block_width is None:
        block_width = source.shape[2] // 8 - block_x0
    if block_height is None:
        block_height = source.shape[1] // 8 - block_y0

    captured = []
    original_color_modulation = adaptive_quantization._color_modulation

    def capture_color_modulation(xyb_value, block_x, block_y, distance_value, out_val):
        result = original_color_modulation(
            xyb_value, block_x, block_y, distance_value, out_val
        )
        captured.append(result)
        return result

    adaptive_quantization._color_modulation = capture_color_modulation
    try:
        adaptive_quantization.compute_adaptive_quantization(
            source,
            distance=distance,
            block_x0=block_x0,
            block_y0=block_y0,
            block_width=block_width,
            block_height=block_height,
        )
    finally:
        adaptive_quantization._color_modulation = original_color_modulation

    expected_count = block_width * block_height
    if len(captured) != expected_count:
        raise RuntimeError(
            f"libjxl-tiny AQ captured {len(captured)} color-modulation values; "
            f"expected {expected_count}"
        )
    return np.asarray(captured, dtype=np.float32).reshape(block_height, block_width)


def capture_aq_gamma_modulation_from_xyb(
    xyb,
    distance: float,
    block_x0: int = 0,
    block_y0: int = 0,
    block_width: int | None = None,
    block_height: int | None = None,
):
    """Capture real `_gamma_modulation` outputs from one reference AQ rectangle."""
    np = _load_numpy()
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny import adaptive_quantization  # pylint: disable=import-outside-toplevel

    source = np.asarray(xyb, dtype=np.float32)
    if block_width is None:
        block_width = source.shape[2] // 8 - block_x0
    if block_height is None:
        block_height = source.shape[1] // 8 - block_y0

    captured = []
    original_gamma_modulation = adaptive_quantization._gamma_modulation

    def capture_gamma_modulation(xyb_value, block_x, block_y, out_val):
        result = original_gamma_modulation(xyb_value, block_x, block_y, out_val)
        captured.append(result)
        return result

    adaptive_quantization._gamma_modulation = capture_gamma_modulation
    try:
        adaptive_quantization.compute_adaptive_quantization(
            source,
            distance=distance,
            block_x0=block_x0,
            block_y0=block_y0,
            block_width=block_width,
            block_height=block_height,
        )
    finally:
        adaptive_quantization._gamma_modulation = original_gamma_modulation

    expected_count = block_width * block_height
    if len(captured) != expected_count:
        raise RuntimeError(
            f"libjxl-tiny AQ captured {len(captured)} gamma-modulation values; "
            f"expected {expected_count}"
        )
    return np.asarray(captured, dtype=np.float32).reshape(block_height, block_width)


def tiled_aq_nonlinear_mask_from_xyb(xyb):
    """Stitch `_compute_mask` outputs from encoder-shaped 64x64 AQ calls."""
    np = _load_numpy()
    source = np.asarray(xyb, dtype=np.float32)
    y_blocks = source.shape[1] // 8
    x_blocks = source.shape[2] // 8
    tile_blocks = 8
    output = np.empty((y_blocks, x_blocks), dtype=np.float32)
    for block_y0 in range(0, y_blocks, tile_blocks):
        block_height = min(tile_blocks, y_blocks - block_y0)
        for block_x0 in range(0, x_blocks, tile_blocks):
            block_width = min(tile_blocks, x_blocks - block_x0)
            output[
                block_y0 : block_y0 + block_height,
                block_x0 : block_x0 + block_width,
            ] = capture_aq_nonlinear_mask_from_xyb(
                source,
                block_x0=block_x0,
                block_y0=block_y0,
                block_width=block_width,
                block_height=block_height,
            )
    return output


def tiled_aq_hf_modulation_from_xyb(xyb):
    """Stitch HF-modulated AQ seeds from encoder-shaped 64x64 AQ calls."""
    np = _load_numpy()
    source = np.asarray(xyb, dtype=np.float32)
    y_blocks = source.shape[1] // 8
    x_blocks = source.shape[2] // 8
    tile_blocks = 8
    output = np.empty((y_blocks, x_blocks), dtype=np.float32)
    for block_y0 in range(0, y_blocks, tile_blocks):
        block_height = min(tile_blocks, y_blocks - block_y0)
        for block_x0 in range(0, x_blocks, tile_blocks):
            block_width = min(tile_blocks, x_blocks - block_x0)
            output[
                block_y0 : block_y0 + block_height,
                block_x0 : block_x0 + block_width,
            ] = capture_aq_hf_modulation_from_xyb(
                source,
                block_x0=block_x0,
                block_y0=block_y0,
                block_width=block_width,
                block_height=block_height,
            )
    return output


def tiled_aq_color_modulation_from_xyb(xyb, distance: float):
    """Stitch color-modulated AQ seeds from encoder-shaped 64x64 AQ calls."""
    np = _load_numpy()
    source = np.asarray(xyb, dtype=np.float32)
    y_blocks = source.shape[1] // 8
    x_blocks = source.shape[2] // 8
    tile_blocks = 8
    output = np.empty((y_blocks, x_blocks), dtype=np.float32)
    for block_y0 in range(0, y_blocks, tile_blocks):
        block_height = min(tile_blocks, y_blocks - block_y0)
        for block_x0 in range(0, x_blocks, tile_blocks):
            block_width = min(tile_blocks, x_blocks - block_x0)
            output[
                block_y0 : block_y0 + block_height,
                block_x0 : block_x0 + block_width,
            ] = capture_aq_color_modulation_from_xyb(
                source,
                distance=distance,
                block_x0=block_x0,
                block_y0=block_y0,
                block_width=block_width,
                block_height=block_height,
            )
    return output


def tiled_aq_gamma_modulation_from_xyb(xyb, distance: float):
    """Stitch gamma-modulated AQ seeds from encoder-shaped 64x64 AQ calls."""
    np = _load_numpy()
    source = np.asarray(xyb, dtype=np.float32)
    y_blocks = source.shape[1] // 8
    x_blocks = source.shape[2] // 8
    tile_blocks = 8
    output = np.empty((y_blocks, x_blocks), dtype=np.float32)
    for block_y0 in range(0, y_blocks, tile_blocks):
        block_height = min(tile_blocks, y_blocks - block_y0)
        for block_x0 in range(0, x_blocks, tile_blocks):
            block_width = min(tile_blocks, x_blocks - block_x0)
            output[
                block_y0 : block_y0 + block_height,
                block_x0 : block_x0 + block_width,
            ] = capture_aq_gamma_modulation_from_xyb(
                source,
                distance=distance,
                block_x0=block_x0,
                block_y0=block_y0,
                block_width=block_width,
                block_height=block_height,
            )
    return output


def tiled_aq_strategy_mask_from_xyb(xyb):
    """Stitch the strategy mask from the same 64x64 AQ calls used by encoder.py."""
    np = _load_numpy()
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.adaptive_quantization import (  # pylint: disable=import-outside-toplevel
        compute_adaptive_quantization,
    )

    source = np.asarray(xyb, dtype=np.float32)
    y_blocks = source.shape[1] // 8
    x_blocks = source.shape[2] // 8
    tile_blocks = 8
    output = np.empty((y_blocks, x_blocks), dtype=np.float32)
    for block_y0 in range(0, y_blocks, tile_blocks):
        block_height = min(tile_blocks, y_blocks - block_y0)
        for block_x0 in range(0, x_blocks, tile_blocks):
            block_width = min(tile_blocks, x_blocks - block_x0)
            result = compute_adaptive_quantization(
                source,
                distance=1.0,
                block_x0=block_x0,
                block_y0=block_y0,
                block_width=block_width,
                block_height=block_height,
            )
            output[
                block_y0 : block_y0 + block_height,
                block_x0 : block_x0 + block_width,
            ] = result.mask
    return output


def capture_aq_erosion_inputs_from_xyb(xyb):
    """Capture actual contrast, erosion, and strategy-mask arrays from full AQ."""
    np = _load_numpy()
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny import adaptive_quantization  # pylint: disable=import-outside-toplevel

    captured_pre_erosion = None
    captured_erosion = None
    original_fuzzy_erosion = adaptive_quantization._fuzzy_erosion

    def capture_fuzzy_erosion(pre_erosion, *args, **kwargs):
        nonlocal captured_pre_erosion, captured_erosion
        captured_pre_erosion = np.array(pre_erosion, dtype=np.float32, copy=True)
        result = original_fuzzy_erosion(pre_erosion, *args, **kwargs)
        captured_erosion = np.array(result, dtype=np.float32, copy=True)
        return result

    adaptive_quantization._fuzzy_erosion = capture_fuzzy_erosion
    try:
        result = adaptive_quantization.compute_adaptive_quantization(xyb, distance=1.0)
    finally:
        adaptive_quantization._fuzzy_erosion = original_fuzzy_erosion
    if captured_pre_erosion is None or captured_erosion is None:
        raise RuntimeError("libjxl-tiny AQ function did not invoke fuzzy erosion")
    return captured_pre_erosion, captured_erosion, np.array(
        result.mask, dtype=np.float32, copy=True
    )


def write_aq_contrast_q16_csv(path: Path, image) -> None:
    """Write the first image-dependent AQ contrast seam in signed Q16."""
    np = _load_numpy()
    xyb = xyb_from_python_port(image)
    reference = aq_contrast_pre_erosion_from_xyb(xyb)
    captured_pre_erosion, _, _ = capture_aq_erosion_inputs_from_xyb(xyb)
    if captured_pre_erosion is None or not np.array_equal(reference, captured_pre_erosion):
        raise RuntimeError(
            "AQ contrast reconstruction does not match libjxl-tiny pre_erosion input"
        )

    quantized_xyb = (
        np.rint(np.asarray(xyb, dtype=np.float64) * (1 << 12)).astype(np.float32)
        / np.float32(1 << 12)
    )
    quantized_reference = aq_contrast_pre_erosion_from_xyb(quantized_xyb)
    reference_q16 = np.rint(reference.astype(np.float64) * (1 << 16)).astype(np.int64)
    quantized_reference_q16 = np.rint(
        quantized_reference.astype(np.float64) * (1 << 16)
    ).astype(np.int64)
    if np.any(reference_q16 < 0) or np.any(reference_q16 > np.iinfo(np.int32).max):
        raise ValueError("AQ contrast Q16 fixture does not fit signed 32-bit")
    if np.any(quantized_reference_q16 < 0) or np.any(
        quantized_reference_q16 > np.iinfo(np.int32).max
    ):
        raise ValueError("quantized-XYB AQ contrast Q16 fixture does not fit signed 32-bit")

    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(
            (
                "cell",
                "cell_x",
                "cell_y",
                "pre_erosion_q16",
                "quantized_xyb_pre_erosion_q16",
            )
        )
        cell = 0
        for cell_y in range(reference_q16.shape[0]):
            for cell_x in range(reference_q16.shape[1]):
                writer.writerow(
                    (
                        cell,
                        cell_x,
                        cell_y,
                        int(reference_q16[cell_y, cell_x]),
                        int(quantized_reference_q16[cell_y, cell_x]),
                    )
                )
                cell += 1


def write_aq_fuzzy_erosion_q16_csv(path: Path, image) -> None:
    """Write libjxl-tiny's erosion-derived per-block AQ intermediate in Q16."""
    np = _load_numpy()
    xyb = xyb_from_python_port(image)
    pre_erosion = aq_contrast_pre_erosion_from_xyb(xyb)
    reference = aq_fuzzy_erosion_from_pre_erosion(pre_erosion)
    captured_pre_erosion, captured_reference, _ = capture_aq_erosion_inputs_from_xyb(xyb)
    if not np.array_equal(pre_erosion, captured_pre_erosion):
        raise RuntimeError(
            "AQ contrast reconstruction does not match libjxl-tiny pre_erosion input"
        )
    if not np.array_equal(reference, captured_reference):
        raise RuntimeError(
            "AQ fuzzy-erosion reconstruction does not match libjxl-tiny output"
        )

    quantized_xyb = (
        np.rint(np.asarray(xyb, dtype=np.float64) * (1 << 12)).astype(np.float32)
        / np.float32(1 << 12)
    )
    quantized_pre_erosion = aq_contrast_pre_erosion_from_xyb(quantized_xyb)
    quantized_reference = aq_fuzzy_erosion_from_pre_erosion(quantized_pre_erosion)
    reference_q16 = np.rint(reference.astype(np.float64) * (1 << 16)).astype(np.int64)
    quantized_reference_q16 = np.rint(
        quantized_reference.astype(np.float64) * (1 << 16)
    ).astype(np.int64)
    if np.any(reference_q16 < 0) or np.any(reference_q16 > np.iinfo(np.int32).max):
        raise ValueError("AQ fuzzy-erosion Q16 fixture does not fit signed 32-bit")
    if np.any(quantized_reference_q16 < 0) or np.any(
        quantized_reference_q16 > np.iinfo(np.int32).max
    ):
        raise ValueError("quantized-XYB AQ fuzzy-erosion fixture does not fit signed 32-bit")

    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(
            (
                "block",
                "block_x",
                "block_y",
                "fuzzy_erosion_q16",
                "quantized_xyb_fuzzy_erosion_q16",
            )
        )
        block = 0
        for block_y in range(reference_q16.shape[0]):
            for block_x in range(reference_q16.shape[1]):
                writer.writerow(
                    (
                        block,
                        block_x,
                        block_y,
                        int(reference_q16[block_y, block_x]),
                        int(quantized_reference_q16[block_y, block_x]),
                    )
                )
                block += 1


def write_aq_strategy_mask_q16_csv(path: Path, image) -> None:
    """Write the block mask consumed by AC-strategy scoring in Q16."""
    np = _load_numpy()
    xyb = xyb_from_python_port(image)
    pre_erosion = aq_contrast_pre_erosion_from_xyb(xyb)
    erosion = aq_fuzzy_erosion_from_pre_erosion(pre_erosion)
    reference = aq_strategy_mask_from_fuzzy_erosion(erosion)
    captured_pre, captured_erosion, captured_reference = (
        capture_aq_erosion_inputs_from_xyb(xyb)
    )
    if not np.array_equal(pre_erosion, captured_pre):
        raise RuntimeError(
            "AQ contrast reconstruction does not match libjxl-tiny pre_erosion input"
        )
    if not np.array_equal(erosion, captured_erosion):
        raise RuntimeError(
            "AQ fuzzy-erosion reconstruction does not match libjxl-tiny output"
        )
    if not np.array_equal(reference, captured_reference):
        raise RuntimeError(
            "AQ strategy-mask reconstruction does not match libjxl-tiny output"
        )
    tiled_reference = tiled_aq_strategy_mask_from_xyb(xyb)
    if not np.array_equal(reference, tiled_reference):
        raise RuntimeError(
            "full-frame AQ strategy mask does not match encoder-style tiled AQ calls"
        )

    quantized_xyb = (
        np.rint(np.asarray(xyb, dtype=np.float64) * (1 << 12)).astype(np.float32)
        / np.float32(1 << 12)
    )
    quantized_pre = aq_contrast_pre_erosion_from_xyb(quantized_xyb)
    quantized_erosion = aq_fuzzy_erosion_from_pre_erosion(quantized_pre)
    quantized_reference = aq_strategy_mask_from_fuzzy_erosion(quantized_erosion)

    erosion_q16 = np.rint(erosion.astype(np.float64) * (1 << 16)).astype(np.int64)
    reference_q16 = np.rint(reference.astype(np.float64) * (1 << 16)).astype(np.int64)
    fixed_reference_q16 = np.empty(reference_q16.shape, dtype=np.int64)
    for block_y in range(reference_q16.shape[0]):
        for block_x in range(reference_q16.shape[1]):
            fixed_reference_q16[block_y, block_x] = fixed_aq_strategy_mask_q16(
                int(erosion_q16[block_y, block_x])
            )
    quantized_erosion_q16 = np.rint(
        quantized_erosion.astype(np.float64) * (1 << 16)
    ).astype(np.int64)
    quantized_reference_q16 = np.rint(
        quantized_reference.astype(np.float64) * (1 << 16)
    ).astype(np.int64)
    for name, values in (
        ("AQ fuzzy erosion", erosion_q16),
        ("AQ strategy mask", reference_q16),
        ("fixed AQ strategy mask", fixed_reference_q16),
        ("quantized-XYB AQ fuzzy erosion", quantized_erosion_q16),
        ("quantized-XYB AQ strategy mask", quantized_reference_q16),
    ):
        if np.any(values < 0) or np.any(values > SIGNED_INT32_MAX):
            raise ValueError(f"{name} Q16 fixture does not fit signed 32-bit")

    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(
            (
                "block",
                "block_x",
                "block_y",
                "fuzzy_erosion_q16",
                "strategy_mask_q16",
                "fixed_strategy_mask_q16",
                "quantized_xyb_fuzzy_erosion_q16",
                "quantized_xyb_strategy_mask_q16",
            )
        )
        block = 0
        for block_y in range(reference_q16.shape[0]):
            for block_x in range(reference_q16.shape[1]):
                writer.writerow(
                    (
                        block,
                        block_x,
                        block_y,
                        int(erosion_q16[block_y, block_x]),
                        int(reference_q16[block_y, block_x]),
                        int(fixed_reference_q16[block_y, block_x]),
                        int(quantized_erosion_q16[block_y, block_x]),
                        int(quantized_reference_q16[block_y, block_x]),
                    )
                )
                block += 1


def write_aq_nonlinear_mask_q24_csv(path: Path, image) -> None:
    """Write `_compute_mask` output, the signed Q24 AQ modulation seed."""
    np = _load_numpy()
    xyb = xyb_from_python_port(image)
    pre_erosion = aq_contrast_pre_erosion_from_xyb(xyb)
    erosion = aq_fuzzy_erosion_from_pre_erosion(pre_erosion)
    reference = aq_nonlinear_mask_from_fuzzy_erosion(erosion)
    captured_reference = capture_aq_nonlinear_mask_from_xyb(xyb)
    if not np.array_equal(reference, captured_reference):
        raise RuntimeError(
            "AQ nonlinear-mask reconstruction does not match libjxl-tiny `_compute_mask`"
        )
    tiled_reference = tiled_aq_nonlinear_mask_from_xyb(xyb)
    if not np.array_equal(reference, tiled_reference):
        raise RuntimeError(
            "full-frame AQ nonlinear mask does not match encoder-style tiled AQ calls"
        )

    quantized_xyb = (
        np.rint(np.asarray(xyb, dtype=np.float64) * (1 << 12)).astype(np.float32)
        / np.float32(1 << 12)
    )
    quantized_pre = aq_contrast_pre_erosion_from_xyb(quantized_xyb)
    quantized_erosion = aq_fuzzy_erosion_from_pre_erosion(quantized_pre)
    quantized_reference = aq_nonlinear_mask_from_fuzzy_erosion(quantized_erosion)

    erosion_q16 = np.rint(erosion.astype(np.float64) * (1 << 16)).astype(np.int64)
    reference_q24 = np.rint(reference.astype(np.float64) * (1 << 24)).astype(np.int64)
    fixed_reference_q24 = np.empty(reference_q24.shape, dtype=np.int64)
    for block_y in range(reference_q24.shape[0]):
        for block_x in range(reference_q24.shape[1]):
            fixed_reference_q24[block_y, block_x] = fixed_aq_nonlinear_mask_q24(
                int(erosion_q16[block_y, block_x])
            )
    quantized_erosion_q16 = np.rint(
        quantized_erosion.astype(np.float64) * (1 << 16)
    ).astype(np.int64)
    quantized_reference_q24 = np.rint(
        quantized_reference.astype(np.float64) * (1 << 24)
    ).astype(np.int64)

    for name, values in (
        ("AQ fuzzy erosion", erosion_q16),
        ("quantized-XYB AQ fuzzy erosion", quantized_erosion_q16),
    ):
        if np.any(values < 0) or np.any(values > SIGNED_INT32_MAX):
            raise ValueError(f"{name} Q16 fixture does not fit positive signed 32-bit")
    for name, values in (
        ("AQ nonlinear mask", reference_q24),
        ("fixed AQ nonlinear mask", fixed_reference_q24),
        ("quantized-XYB AQ nonlinear mask", quantized_reference_q24),
    ):
        if np.any(values < SIGNED_INT32_MIN) or np.any(values > SIGNED_INT32_MAX):
            raise ValueError(f"{name} Q24 fixture does not fit signed 32-bit")

    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(
            (
                "block",
                "block_x",
                "block_y",
                "fuzzy_erosion_q16",
                "nonlinear_mask_q24",
                "fixed_nonlinear_mask_q24",
                "quantized_xyb_fuzzy_erosion_q16",
                "quantized_xyb_nonlinear_mask_q24",
            )
        )
        block = 0
        for block_y in range(reference_q24.shape[0]):
            for block_x in range(reference_q24.shape[1]):
                writer.writerow(
                    (
                        block,
                        block_x,
                        block_y,
                        int(erosion_q16[block_y, block_x]),
                        int(reference_q24[block_y, block_x]),
                        int(fixed_reference_q24[block_y, block_x]),
                        int(quantized_erosion_q16[block_y, block_x]),
                        int(quantized_reference_q24[block_y, block_x]),
                    )
                )
                block += 1


def write_aq_hf_modulation_q24_csv(path: Path, image) -> None:
    """Write the cumulative signed-Q24 AQ seed after Y HF modulation."""
    np = _load_numpy()
    xyb = xyb_from_python_port(image)
    pre_erosion = aq_contrast_pre_erosion_from_xyb(xyb)
    erosion = aq_fuzzy_erosion_from_pre_erosion(pre_erosion)
    nonlinear = aq_nonlinear_mask_from_fuzzy_erosion(erosion)
    reference = aq_hf_modulation_from_xyb(xyb, nonlinear)
    captured_reference = capture_aq_hf_modulation_from_xyb(xyb)
    if not np.array_equal(reference, captured_reference):
        raise RuntimeError(
            "AQ HF-modulation reconstruction does not match libjxl-tiny `_hf_modulation`"
        )
    tiled_reference = tiled_aq_hf_modulation_from_xyb(xyb)
    if not np.array_equal(reference, tiled_reference):
        raise RuntimeError(
            "full-frame AQ HF modulation does not match encoder-style tiled AQ calls"
        )

    quantized_xyb_q12 = np.rint(
        np.asarray(xyb, dtype=np.float64) * (1 << 12)
    ).astype(np.int64)
    quantized_xyb = (
        quantized_xyb_q12.astype(np.float32) / np.float32(1 << 12)
    ).astype(np.float32)
    quantized_pre = aq_contrast_pre_erosion_from_xyb(quantized_xyb)
    quantized_erosion = aq_fuzzy_erosion_from_pre_erosion(quantized_pre)
    quantized_nonlinear = aq_nonlinear_mask_from_fuzzy_erosion(quantized_erosion)
    quantized_reference = aq_hf_modulation_from_xyb(
        quantized_xyb, quantized_nonlinear
    )

    input_q8 = np.rint(
        np.asarray(image, dtype=np.float64) * (1 << 8)
    ).astype(np.int64)
    if np.any(input_q8 < np.iinfo(np.int16).min) or np.any(
        input_q8 > np.iinfo(np.int16).max
    ):
        raise ValueError("RGB Q8 fixture does not fit signed 16-bit")
    input_quantized_rgb = (
        input_q8.astype(np.float32) / np.float32(1 << 8)
    ).astype(np.float32)
    input_quantized_xyb = xyb_from_python_port(input_quantized_rgb)
    input_quantized_xyb_q12 = np.rint(
        np.asarray(input_quantized_xyb, dtype=np.float64) * (1 << 12)
    ).astype(np.int64)
    input_q8_xyb_q12 = (
        input_quantized_xyb_q12.astype(np.float32) / np.float32(1 << 12)
    ).astype(np.float32)
    input_quantized_pre = aq_contrast_pre_erosion_from_xyb(input_q8_xyb_q12)
    input_quantized_erosion = aq_fuzzy_erosion_from_pre_erosion(
        input_quantized_pre
    )
    input_quantized_erosion_q16 = np.rint(
        input_quantized_erosion.astype(np.float64) * (1 << 16)
    ).astype(np.int64)

    erosion_q16 = np.rint(erosion.astype(np.float64) * (1 << 16)).astype(np.int64)
    nonlinear_q24 = np.rint(nonlinear.astype(np.float64) * (1 << 24)).astype(np.int64)
    reference_q24 = np.rint(reference.astype(np.float64) * (1 << 24)).astype(np.int64)
    xyb_y_q12 = np.rint(
        np.asarray(xyb[1], dtype=np.float64) * (1 << 12)
    ).astype(np.int64)
    fixed_nonlinear_q24 = np.empty(reference_q24.shape, dtype=np.int64)
    fixed_reference_q24 = np.empty(reference_q24.shape, dtype=np.int64)

    quantized_erosion_q16 = np.rint(
        quantized_erosion.astype(np.float64) * (1 << 16)
    ).astype(np.int64)
    quantized_nonlinear_q24 = np.rint(
        quantized_nonlinear.astype(np.float64) * (1 << 24)
    ).astype(np.int64)
    quantized_reference_q24 = np.rint(
        quantized_reference.astype(np.float64) * (1 << 24)
    ).astype(np.int64)
    fixed_quantized_reference_q24 = np.empty(reference_q24.shape, dtype=np.int64)
    input_quantized_fixed_nonlinear_q24 = np.empty(
        reference_q24.shape, dtype=np.int64
    )
    input_quantized_fixed_reference_q24 = np.empty(
        reference_q24.shape, dtype=np.int64
    )

    for block_y in range(reference_q24.shape[0]):
        for block_x in range(reference_q24.shape[1]):
            y0 = block_y * 8
            x0 = block_x * 8
            fixed_nonlinear_q24[block_y, block_x] = fixed_aq_nonlinear_mask_q24(
                int(erosion_q16[block_y, block_x])
            )
            fixed_reference_q24[block_y, block_x] = fixed_aq_hf_modulation_q24(
                int(fixed_nonlinear_q24[block_y, block_x]),
                xyb_y_q12[y0 : y0 + 8, x0 : x0 + 8],
            )
            fixed_quantized_seed_q24 = fixed_aq_nonlinear_mask_q24(
                int(quantized_erosion_q16[block_y, block_x])
            )
            fixed_quantized_reference_q24[block_y, block_x] = (
                fixed_aq_hf_modulation_q24(
                    fixed_quantized_seed_q24,
                    quantized_xyb_q12[1, y0 : y0 + 8, x0 : x0 + 8],
                )
            )
            input_quantized_fixed_nonlinear_q24[block_y, block_x] = (
                fixed_aq_nonlinear_mask_q24(
                    int(input_quantized_erosion_q16[block_y, block_x])
                )
            )
            input_quantized_fixed_reference_q24[block_y, block_x] = (
                fixed_aq_hf_modulation_q24(
                    int(input_quantized_fixed_nonlinear_q24[block_y, block_x]),
                    input_quantized_xyb_q12[1, y0 : y0 + 8, x0 : x0 + 8],
                )
            )

    for name, values in (
        ("AQ fuzzy erosion", erosion_q16),
        ("quantized-XYB AQ fuzzy erosion", quantized_erosion_q16),
        ("input-Q8 AQ fuzzy erosion", input_quantized_erosion_q16),
    ):
        if np.any(values < 0) or np.any(values > SIGNED_INT32_MAX):
            raise ValueError(f"{name} Q16 fixture does not fit positive signed 32-bit")
    for name, values in (
        ("AQ nonlinear mask", nonlinear_q24),
        ("AQ HF modulation", reference_q24),
        ("fixed AQ nonlinear mask", fixed_nonlinear_q24),
        ("fixed AQ HF modulation", fixed_reference_q24),
        ("quantized-XYB AQ nonlinear mask", quantized_nonlinear_q24),
        ("quantized-XYB AQ HF modulation", quantized_reference_q24),
        ("fixed quantized-XYB AQ HF modulation", fixed_quantized_reference_q24),
        ("input-Q8 fixed AQ nonlinear mask", input_quantized_fixed_nonlinear_q24),
        ("input-Q8 fixed AQ HF modulation", input_quantized_fixed_reference_q24),
    ):
        if np.any(values < SIGNED_INT32_MIN) or np.any(values > SIGNED_INT32_MAX):
            raise ValueError(f"{name} Q24 fixture does not fit signed 32-bit")

    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(
            (
                "block",
                "block_x",
                "block_y",
                "fuzzy_erosion_q16",
                "nonlinear_mask_q24",
                "hf_modulation_q24",
                "fixed_nonlinear_mask_q24",
                "fixed_hf_modulation_q24",
                "quantized_xyb_fuzzy_erosion_q16",
                "quantized_xyb_nonlinear_mask_q24",
                "quantized_xyb_hf_modulation_q24",
                "fixed_quantized_xyb_hf_modulation_q24",
                "input_q8_fuzzy_erosion_q16",
                "input_q8_fixed_nonlinear_mask_q24",
                "input_q8_fixed_hf_modulation_q24",
            )
        )
        block = 0
        for block_y in range(reference_q24.shape[0]):
            for block_x in range(reference_q24.shape[1]):
                writer.writerow(
                    (
                        block,
                        block_x,
                        block_y,
                        int(erosion_q16[block_y, block_x]),
                        int(nonlinear_q24[block_y, block_x]),
                        int(reference_q24[block_y, block_x]),
                        int(fixed_nonlinear_q24[block_y, block_x]),
                        int(fixed_reference_q24[block_y, block_x]),
                        int(quantized_erosion_q16[block_y, block_x]),
                        int(quantized_nonlinear_q24[block_y, block_x]),
                        int(quantized_reference_q24[block_y, block_x]),
                        int(fixed_quantized_reference_q24[block_y, block_x]),
                        int(input_quantized_erosion_q16[block_y, block_x]),
                        int(input_quantized_fixed_nonlinear_q24[block_y, block_x]),
                        int(input_quantized_fixed_reference_q24[block_y, block_x]),
                    )
                )
                block += 1


def write_aq_color_modulation_q24_csv(
    path: Path,
    image,
    distance: float,
) -> None:
    """Write cumulative signed-Q24 AQ seeds after red/blue color modulation."""
    np = _load_numpy()
    distance_q8 = int(round(float(distance) * (1 << 8)))
    if distance_q8 < 0 or distance_q8 > np.iinfo(np.uint16).max:
        raise ValueError("AQ color-modulation distance does not fit unsigned Q8")
    reference_distance = float(np.float32(distance_q8 / float(1 << 8)))

    def float_chain(source):
        pre_erosion = aq_contrast_pre_erosion_from_xyb(source)
        erosion = aq_fuzzy_erosion_from_pre_erosion(pre_erosion)
        nonlinear = aq_nonlinear_mask_from_fuzzy_erosion(erosion)
        hf = aq_hf_modulation_from_xyb(source, nonlinear)
        color = aq_color_modulation_from_xyb(source, hf, reference_distance)
        return erosion, hf, color

    def fixed_chain(erosion, source_q12):
        erosion_q16 = np.rint(
            np.asarray(erosion, dtype=np.float64) * (1 << 16)
        ).astype(np.int64)
        shape = erosion_q16.shape
        fixed_hf = np.empty(shape, dtype=np.int64)
        fixed_color = np.empty(shape, dtype=np.int64)
        for block_y in range(shape[0]):
            for block_x in range(shape[1]):
                y0 = block_y * 8
                x0 = block_x * 8
                block = source_q12[:, y0 : y0 + 8, x0 : x0 + 8]
                nonlinear_q24 = fixed_aq_nonlinear_mask_q24(
                    int(erosion_q16[block_y, block_x])
                )
                fixed_hf[block_y, block_x] = fixed_aq_hf_modulation_q24(
                    nonlinear_q24,
                    block[1],
                )
                fixed_color[block_y, block_x] = fixed_aq_color_modulation_q24(
                    int(fixed_hf[block_y, block_x]),
                    distance_q8,
                    block,
                )
        return erosion_q16, fixed_hf, fixed_color

    xyb = xyb_from_python_port(image)
    erosion, hf, reference = float_chain(xyb)
    captured_reference = capture_aq_color_modulation_from_xyb(
        xyb, reference_distance
    )
    if not np.array_equal(reference, captured_reference):
        raise RuntimeError(
            "AQ color-modulation reconstruction does not match libjxl-tiny "
            "`_color_modulation`"
        )
    tiled_reference = tiled_aq_color_modulation_from_xyb(
        xyb, reference_distance
    )
    if not np.array_equal(reference, tiled_reference):
        raise RuntimeError(
            "full-frame AQ color modulation does not match encoder-style tiled AQ calls"
        )

    xyb_q12 = np.rint(
        np.asarray(xyb, dtype=np.float64) * (1 << 12)
    ).astype(np.int64)
    quantized_xyb = (
        xyb_q12.astype(np.float32) / np.float32(1 << 12)
    ).astype(np.float32)
    quantized_erosion, quantized_hf, quantized_reference = float_chain(
        quantized_xyb
    )

    input_q8 = np.rint(
        np.asarray(image, dtype=np.float64) * (1 << 8)
    ).astype(np.int64)
    if np.any(input_q8 < np.iinfo(np.int16).min) or np.any(
        input_q8 > np.iinfo(np.int16).max
    ):
        raise ValueError("RGB Q8 fixture does not fit signed 16-bit")
    input_quantized_rgb = (
        input_q8.astype(np.float32) / np.float32(1 << 8)
    ).astype(np.float32)
    input_quantized_xyb = xyb_from_python_port(input_quantized_rgb)
    input_quantized_xyb_q12 = np.rint(
        np.asarray(input_quantized_xyb, dtype=np.float64) * (1 << 12)
    ).astype(np.int64)
    input_q8_xyb_q12 = (
        input_quantized_xyb_q12.astype(np.float32) / np.float32(1 << 12)
    ).astype(np.float32)
    input_quantized_pre = aq_contrast_pre_erosion_from_xyb(input_q8_xyb_q12)
    input_quantized_erosion = aq_fuzzy_erosion_from_pre_erosion(
        input_quantized_pre
    )

    erosion_q16, fixed_hf, fixed_reference = fixed_chain(erosion, xyb_q12)
    quantized_erosion_q16, fixed_quantized_hf, fixed_quantized_reference = (
        fixed_chain(quantized_erosion, xyb_q12)
    )
    (
        input_quantized_erosion_q16,
        input_quantized_fixed_hf,
        input_quantized_fixed_reference,
    ) = fixed_chain(input_quantized_erosion, input_quantized_xyb_q12)

    hf_q24 = np.rint(hf.astype(np.float64) * (1 << 24)).astype(np.int64)
    reference_q24 = np.rint(
        reference.astype(np.float64) * (1 << 24)
    ).astype(np.int64)
    quantized_hf_q24 = np.rint(
        quantized_hf.astype(np.float64) * (1 << 24)
    ).astype(np.int64)
    quantized_reference_q24 = np.rint(
        quantized_reference.astype(np.float64) * (1 << 24)
    ).astype(np.int64)

    for name, values in (
        ("AQ fuzzy erosion", erosion_q16),
        ("quantized-XYB AQ fuzzy erosion", quantized_erosion_q16),
        ("input-Q8 AQ fuzzy erosion", input_quantized_erosion_q16),
    ):
        if np.any(values < 0) or np.any(values > SIGNED_INT32_MAX):
            raise ValueError(f"{name} Q16 fixture does not fit positive signed 32-bit")
    for name, values in (
        ("AQ HF modulation", hf_q24),
        ("AQ color modulation", reference_q24),
        ("fixed AQ HF modulation", fixed_hf),
        ("fixed AQ color modulation", fixed_reference),
        ("quantized-XYB AQ HF modulation", quantized_hf_q24),
        ("quantized-XYB AQ color modulation", quantized_reference_q24),
        ("fixed quantized-XYB AQ HF modulation", fixed_quantized_hf),
        ("fixed quantized-XYB AQ color modulation", fixed_quantized_reference),
        ("input-Q8 fixed AQ HF modulation", input_quantized_fixed_hf),
        ("input-Q8 fixed AQ color modulation", input_quantized_fixed_reference),
    ):
        if np.any(values < SIGNED_INT32_MIN) or np.any(values > SIGNED_INT32_MAX):
            raise ValueError(f"{name} Q24 fixture does not fit signed 32-bit")

    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(
            (
                "block",
                "block_x",
                "block_y",
                "distance_q8",
                "hf_modulation_q24",
                "color_modulation_q24",
                "fixed_hf_modulation_q24",
                "fixed_color_modulation_q24",
                "quantized_xyb_hf_modulation_q24",
                "quantized_xyb_color_modulation_q24",
                "fixed_quantized_xyb_hf_modulation_q24",
                "fixed_quantized_xyb_color_modulation_q24",
                "input_q8_fixed_hf_modulation_q24",
                "input_q8_fixed_color_modulation_q24",
            )
        )
        block = 0
        for block_y in range(reference_q24.shape[0]):
            for block_x in range(reference_q24.shape[1]):
                writer.writerow(
                    (
                        block,
                        block_x,
                        block_y,
                        distance_q8,
                        int(hf_q24[block_y, block_x]),
                        int(reference_q24[block_y, block_x]),
                        int(fixed_hf[block_y, block_x]),
                        int(fixed_reference[block_y, block_x]),
                        int(quantized_hf_q24[block_y, block_x]),
                        int(quantized_reference_q24[block_y, block_x]),
                        int(fixed_quantized_hf[block_y, block_x]),
                        int(fixed_quantized_reference[block_y, block_x]),
                        int(input_quantized_fixed_hf[block_y, block_x]),
                        int(input_quantized_fixed_reference[block_y, block_x]),
                    )
                )
                block += 1


def write_aq_gamma_modulation_q24_csv(
    path: Path,
    image,
    distance: float,
) -> None:
    """Write cumulative signed-Q24 AQ seeds after gamma modulation."""
    np = _load_numpy()
    distance_q8 = int(round(float(distance) * (1 << 8)))
    if distance_q8 < 0 or distance_q8 > np.iinfo(np.uint16).max:
        raise ValueError("AQ gamma-modulation distance does not fit unsigned Q8")
    reference_distance = float(np.float32(distance_q8 / float(1 << 8)))

    def float_chain(source):
        pre_erosion = aq_contrast_pre_erosion_from_xyb(source)
        erosion = aq_fuzzy_erosion_from_pre_erosion(pre_erosion)
        nonlinear = aq_nonlinear_mask_from_fuzzy_erosion(erosion)
        hf = aq_hf_modulation_from_xyb(source, nonlinear)
        color = aq_color_modulation_from_xyb(source, hf, reference_distance)
        gamma = aq_gamma_modulation_from_xyb(source, color)
        return erosion, color, gamma

    def fixed_chain(erosion, source_q12):
        erosion_q16 = np.rint(
            np.asarray(erosion, dtype=np.float64) * (1 << 16)
        ).astype(np.int64)
        shape = erosion_q16.shape
        fixed_color = np.empty(shape, dtype=np.int64)
        fixed_gamma = np.empty(shape, dtype=np.int64)
        for block_y in range(shape[0]):
            for block_x in range(shape[1]):
                y0 = block_y * 8
                x0 = block_x * 8
                block = source_q12[:, y0 : y0 + 8, x0 : x0 + 8]
                nonlinear_q24 = fixed_aq_nonlinear_mask_q24(
                    int(erosion_q16[block_y, block_x])
                )
                hf_q24 = fixed_aq_hf_modulation_q24(nonlinear_q24, block[1])
                fixed_color[block_y, block_x] = fixed_aq_color_modulation_q24(
                    hf_q24,
                    distance_q8,
                    block,
                )
                fixed_gamma[block_y, block_x] = fixed_aq_gamma_modulation_q24(
                    int(fixed_color[block_y, block_x]),
                    block,
                )
        return erosion_q16, fixed_color, fixed_gamma

    xyb = xyb_from_python_port(image)
    erosion, color, reference = float_chain(xyb)
    captured_reference = capture_aq_gamma_modulation_from_xyb(
        xyb, reference_distance
    )
    if not np.array_equal(reference, captured_reference):
        raise RuntimeError(
            "AQ gamma-modulation reconstruction does not match libjxl-tiny "
            "`_gamma_modulation`"
        )
    tiled_reference = tiled_aq_gamma_modulation_from_xyb(
        xyb, reference_distance
    )
    if not np.array_equal(reference, tiled_reference):
        raise RuntimeError(
            "full-frame AQ gamma modulation does not match encoder-style tiled AQ calls"
        )

    xyb_q12 = np.rint(
        np.asarray(xyb, dtype=np.float64) * (1 << 12)
    ).astype(np.int64)
    quantized_xyb = (
        xyb_q12.astype(np.float32) / np.float32(1 << 12)
    ).astype(np.float32)
    quantized_erosion, _, quantized_reference = float_chain(quantized_xyb)

    input_q8 = np.rint(
        np.asarray(image, dtype=np.float64) * (1 << 8)
    ).astype(np.int64)
    if np.any(input_q8 < np.iinfo(np.int16).min) or np.any(
        input_q8 > np.iinfo(np.int16).max
    ):
        raise ValueError("RGB Q8 fixture does not fit signed 16-bit")
    input_quantized_rgb = (
        input_q8.astype(np.float32) / np.float32(1 << 8)
    ).astype(np.float32)
    input_quantized_xyb = xyb_from_python_port(input_quantized_rgb)
    input_quantized_xyb_q12 = np.rint(
        np.asarray(input_quantized_xyb, dtype=np.float64) * (1 << 12)
    ).astype(np.int64)
    input_q8_xyb_q12 = (
        input_quantized_xyb_q12.astype(np.float32) / np.float32(1 << 12)
    ).astype(np.float32)
    input_quantized_pre = aq_contrast_pre_erosion_from_xyb(input_q8_xyb_q12)
    input_quantized_erosion = aq_fuzzy_erosion_from_pre_erosion(
        input_quantized_pre
    )

    erosion_q16, fixed_color, fixed_reference = fixed_chain(erosion, xyb_q12)
    quantized_erosion_q16, _, fixed_quantized_reference = fixed_chain(
        quantized_erosion,
        xyb_q12,
    )
    (
        input_quantized_erosion_q16,
        input_quantized_fixed_color,
        input_quantized_fixed_reference,
    ) = fixed_chain(input_quantized_erosion, input_quantized_xyb_q12)

    color_q24 = np.rint(color.astype(np.float64) * (1 << 24)).astype(np.int64)
    reference_q24 = np.rint(
        reference.astype(np.float64) * (1 << 24)
    ).astype(np.int64)
    quantized_reference_q24 = np.rint(
        quantized_reference.astype(np.float64) * (1 << 24)
    ).astype(np.int64)

    for name, values in (
        ("AQ fuzzy erosion", erosion_q16),
        ("quantized-XYB AQ fuzzy erosion", quantized_erosion_q16),
        ("input-Q8 AQ fuzzy erosion", input_quantized_erosion_q16),
    ):
        if np.any(values < 0) or np.any(values > SIGNED_INT32_MAX):
            raise ValueError(f"{name} Q16 fixture does not fit positive signed 32-bit")
    for name, values in (
        ("AQ color modulation", color_q24),
        ("AQ gamma modulation", reference_q24),
        ("fixed AQ color modulation", fixed_color),
        ("fixed AQ gamma modulation", fixed_reference),
        ("quantized-XYB AQ gamma modulation", quantized_reference_q24),
        ("fixed quantized-XYB AQ gamma modulation", fixed_quantized_reference),
        ("input-Q8 fixed AQ color modulation", input_quantized_fixed_color),
        ("input-Q8 fixed AQ gamma modulation", input_quantized_fixed_reference),
    ):
        if np.any(values < SIGNED_INT32_MIN) or np.any(values > SIGNED_INT32_MAX):
            raise ValueError(f"{name} Q24 fixture does not fit signed 32-bit")

    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(
            (
                "block",
                "block_x",
                "block_y",
                "distance_q8",
                "color_modulation_q24",
                "gamma_modulation_q24",
                "fixed_color_modulation_q24",
                "fixed_gamma_modulation_q24",
                "quantized_xyb_gamma_modulation_q24",
                "fixed_quantized_xyb_gamma_modulation_q24",
                "input_q8_fixed_color_modulation_q24",
                "input_q8_fixed_gamma_modulation_q24",
            )
        )
        block = 0
        for block_y in range(reference_q24.shape[0]):
            for block_x in range(reference_q24.shape[1]):
                writer.writerow(
                    (
                        block,
                        block_x,
                        block_y,
                        distance_q8,
                        int(color_q24[block_y, block_x]),
                        int(reference_q24[block_y, block_x]),
                        int(fixed_color[block_y, block_x]),
                        int(fixed_reference[block_y, block_x]),
                        int(quantized_reference_q24[block_y, block_x]),
                        int(fixed_quantized_reference[block_y, block_x]),
                        int(input_quantized_fixed_color[block_y, block_x]),
                        int(input_quantized_fixed_reference[block_y, block_x]),
                    )
                )
                block += 1


def write_aq_final_map_q24_csv(
    path: Path,
    image,
    distance: float,
) -> None:
    """Write completed AQ-map and raw-quant evidence for the RGB chain."""
    np = _load_numpy()
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.adaptive_quantization import (  # pylint: disable=import-outside-toplevel
        compute_adaptive_quantization,
    )

    distance_q8 = int(round(float(distance) * (1 << 8)))
    if distance_q8 <= 0 or distance_q8 > np.iinfo(np.uint16).max:
        raise ValueError("AQ final-map distance must fit positive unsigned Q8")
    reference_distance = float(np.float32(distance_q8 / float(1 << 8)))
    scale_q24, dampen_q24, inv_global_scale_q24 = fixed_aq_final_scalars_q24(
        reference_distance
    )

    def float_chain(source):
        pre_erosion = aq_contrast_pre_erosion_from_xyb(source)
        erosion = aq_fuzzy_erosion_from_pre_erosion(pre_erosion)
        nonlinear = aq_nonlinear_mask_from_fuzzy_erosion(erosion)
        hf = aq_hf_modulation_from_xyb(source, nonlinear)
        color = aq_color_modulation_from_xyb(source, hf, reference_distance)
        gamma = aq_gamma_modulation_from_xyb(source, color)
        final_map = aq_final_modulation_from_gamma(gamma, reference_distance)
        return erosion, gamma, final_map

    def fixed_chain(erosion, source_q12):
        erosion_q16 = np.rint(
            np.asarray(erosion, dtype=np.float64) * (1 << 16)
        ).astype(np.int64)
        shape = erosion_q16.shape
        fixed_gamma = np.empty(shape, dtype=np.int64)
        fixed_map = np.empty(shape, dtype=np.int64)
        fixed_raw = np.empty(shape, dtype=np.uint8)
        for block_y in range(shape[0]):
            for block_x in range(shape[1]):
                y0 = block_y * 8
                x0 = block_x * 8
                block = source_q12[:, y0 : y0 + 8, x0 : x0 + 8]
                nonlinear_q24 = fixed_aq_nonlinear_mask_q24(
                    int(erosion_q16[block_y, block_x])
                )
                hf_q24 = fixed_aq_hf_modulation_q24(nonlinear_q24, block[1])
                color_q24 = fixed_aq_color_modulation_q24(
                    hf_q24,
                    distance_q8,
                    block,
                )
                gamma_q24 = fixed_aq_gamma_modulation_q24(color_q24, block)
                map_q24 = fixed_aq_final_modulation_q24(
                    gamma_q24,
                    scale_q24,
                    dampen_q24,
                )
                fixed_gamma[block_y, block_x] = gamma_q24
                fixed_map[block_y, block_x] = map_q24
                fixed_raw[block_y, block_x] = fixed_aq_raw_quant(
                    map_q24,
                    inv_global_scale_q24,
                )
        return erosion_q16, fixed_gamma, fixed_map, fixed_raw

    xyb = xyb_from_python_port(image)
    erosion, gamma, reference_map = float_chain(xyb)
    direct = compute_adaptive_quantization(xyb, reference_distance)
    if not np.array_equal(reference_map, direct.aq_map):
        raise RuntimeError(
            "AQ final-map reconstruction does not match libjxl-tiny "
            "`_per_block_modulations`"
        )

    xyb_q12 = np.rint(
        np.asarray(xyb, dtype=np.float64) * (1 << 12)
    ).astype(np.int64)
    quantized_xyb = (
        xyb_q12.astype(np.float32) / np.float32(1 << 12)
    ).astype(np.float32)
    quantized_erosion, _, quantized_reference_map = float_chain(quantized_xyb)

    input_q8 = np.rint(
        np.asarray(image, dtype=np.float64) * (1 << 8)
    ).astype(np.int64)
    if np.any(input_q8 < np.iinfo(np.int16).min) or np.any(
        input_q8 > np.iinfo(np.int16).max
    ):
        raise ValueError("RGB Q8 fixture does not fit signed 16-bit")
    input_quantized_rgb = (
        input_q8.astype(np.float32) / np.float32(1 << 8)
    ).astype(np.float32)
    input_quantized_xyb = xyb_from_python_port(input_quantized_rgb)
    input_quantized_xyb_q12 = np.rint(
        np.asarray(input_quantized_xyb, dtype=np.float64) * (1 << 12)
    ).astype(np.int64)
    input_q8_xyb_q12 = (
        input_quantized_xyb_q12.astype(np.float32) / np.float32(1 << 12)
    ).astype(np.float32)
    input_quantized_pre = aq_contrast_pre_erosion_from_xyb(input_q8_xyb_q12)
    input_quantized_erosion = aq_fuzzy_erosion_from_pre_erosion(
        input_quantized_pre
    )

    _, fixed_gamma, fixed_map, fixed_raw = fixed_chain(erosion, xyb_q12)
    _, _, fixed_quantized_map, _ = fixed_chain(
        quantized_erosion,
        xyb_q12,
    )
    (
        input_quantized_erosion_q16,
        input_quantized_fixed_gamma,
        input_quantized_fixed_map,
        input_quantized_fixed_raw,
    ) = fixed_chain(input_quantized_erosion, input_quantized_xyb_q12)

    gamma_q24 = np.rint(gamma.astype(np.float64) * (1 << 24)).astype(np.int64)
    reference_map_q24 = np.rint(
        reference_map.astype(np.float64) * (1 << 24)
    ).astype(np.int64)
    quantized_reference_map_q24 = np.rint(
        quantized_reference_map.astype(np.float64) * (1 << 24)
    ).astype(np.int64)

    for name, values in (
        ("AQ fuzzy erosion", input_quantized_erosion_q16),
        ("AQ final map", reference_map_q24),
        ("fixed AQ final map", fixed_map),
        ("quantized-XYB AQ final map", quantized_reference_map_q24),
        ("fixed quantized-XYB AQ final map", fixed_quantized_map),
        ("input-Q8 fixed AQ final map", input_quantized_fixed_map),
    ):
        if np.any(values < 0) or np.any(values > np.iinfo(np.uint32).max):
            raise ValueError(f"{name} fixture does not fit unsigned Q24")
    for name, values in (
        ("AQ gamma modulation", gamma_q24),
        ("fixed AQ gamma modulation", fixed_gamma),
        ("input-Q8 fixed AQ gamma modulation", input_quantized_fixed_gamma),
    ):
        if np.any(values < SIGNED_INT32_MIN) or np.any(values > SIGNED_INT32_MAX):
            raise ValueError(f"{name} fixture does not fit signed Q24")

    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(
            (
                "block",
                "block_x",
                "block_y",
                "distance_q8",
                "gamma_modulation_q24",
                "aq_map_q24",
                "fixed_gamma_modulation_q24",
                "fixed_aq_map_q24",
                "quantized_xyb_aq_map_q24",
                "fixed_quantized_xyb_aq_map_q24",
                "input_q8_fixed_gamma_modulation_q24",
                "input_q8_fixed_aq_map_q24",
                "aq_scale_q24",
                "aq_dampen_q24",
                "inv_global_scale_q24",
                "reference_raw_quant",
                "fixed_raw_quant",
                "input_q8_fixed_raw_quant",
            )
        )
        block = 0
        for block_y in range(reference_map_q24.shape[0]):
            for block_x in range(reference_map_q24.shape[1]):
                writer.writerow(
                    (
                        block,
                        block_x,
                        block_y,
                        distance_q8,
                        int(gamma_q24[block_y, block_x]),
                        int(reference_map_q24[block_y, block_x]),
                        int(fixed_gamma[block_y, block_x]),
                        int(fixed_map[block_y, block_x]),
                        int(quantized_reference_map_q24[block_y, block_x]),
                        int(fixed_quantized_map[block_y, block_x]),
                        int(input_quantized_fixed_gamma[block_y, block_x]),
                        int(input_quantized_fixed_map[block_y, block_x]),
                        scale_q24,
                        dampen_q24,
                        inv_global_scale_q24,
                        int(direct.raw_quant_field[block_y, block_x]),
                        int(fixed_raw[block_y, block_x]),
                        int(input_quantized_fixed_raw[block_y, block_x]),
                    )
                )
                block += 1


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


def write_scaled_dct_q12_csv(path: Path) -> None:
    """Write independent Q12 fixtures for the 16-point and rectangular DCTs."""
    np = _load_numpy()
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.transforms import (  # pylint: disable=import-outside-toplevel
        _dct_1d,
        scaled_dct,
    )

    scale = 1 << 12

    def one_dimensional_fixture(name: str):
        if name == "constant":
            return np.full(16, 1024, dtype=np.int64)
        if name == "ramp":
            return np.arange(-15, 17, 2, dtype=np.int64) * 137
        if name == "alternating":
            return np.asarray(
                [1536 if index % 2 == 0 else -1024 for index in range(16)]
            )
        if name == "impulse":
            values = np.zeros(16, dtype=np.int64)
            values[5] = 3072
            return values
        if name == "signed":
            return np.asarray(
                [((index * 811 + 173) % 4096) - 2048 for index in range(16)]
            )
        raise ValueError(f"unknown DCT-16 fixture: {name}")

    def rectangular_fixture(name: str, rows: int, columns: int):
        y, x = np.mgrid[0:rows, 0:columns]
        if name == "constant":
            return np.full((rows, columns), 1024, dtype=np.int64)
        if name == "x-ramp":
            return (x * 233 - (columns - 1) * 117).astype(np.int64)
        if name == "y-ramp":
            return (y * 197 - (rows - 1) * 99).astype(np.int64)
        if name == "impulse":
            values = np.zeros((rows, columns), dtype=np.int64)
            values[rows // 3, columns // 2] = 3072
            return values
        if name == "signed":
            return ((y * 811 + x * 509 + 173) % 4096 - 2048).astype(np.int64)
        raise ValueError(f"unknown rectangular DCT fixture: {name}")

    fixtures = []
    for name in ("constant", "ramp", "alternating", "impulse", "signed"):
        inputs = one_dimensional_fixture(name)
        expected = np.rint(
            _dct_1d((inputs.astype(np.float32) / np.float32(scale)))
            * np.float32(scale)
        ).astype(np.int64)
        fixed_expected = np.asarray(_fixed_dct16_q12(inputs.tolist()), dtype=np.int64)
        fixtures.append(
            (
                "dct16",
                name,
                inputs.reshape(-1),
                expected.reshape(-1),
                fixed_expected.reshape(-1),
            )
        )

    for kind, rows, columns in (("dct16x8", 16, 8), ("dct8x16", 8, 16)):
        for name in ("constant", "x-ramp", "y-ramp", "impulse", "signed"):
            inputs = rectangular_fixture(name, rows, columns)
            expected = np.rint(
                scaled_dct(inputs.astype(np.float32) / np.float32(scale))
                * np.float32(scale)
            ).astype(np.int64)
            fixed_expected = fixed_rectangular_dct_q12(inputs)
            fixtures.append(
                (
                    kind,
                    name,
                    inputs.reshape(-1),
                    expected.reshape(-1),
                    fixed_expected.reshape(-1),
                )
            )

    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(
            (
                "kind",
                "fixture",
                "index",
                "input_q12",
                "coefficient_q12",
                "fixed_coefficient_q12",
            )
        )
        for kind, name, inputs, expected, fixed_expected in fixtures:
            if inputs.shape != expected.shape or inputs.shape != fixed_expected.shape:
                raise AssertionError(
                    f"{kind}/{name}: input and canonical coefficient sizes differ"
                )
            for index, (input_q12, coefficient_q12, fixed_coefficient_q12) in enumerate(
                zip(inputs, expected, fixed_expected)
            ):
                writer.writerow(
                    (
                        kind,
                        name,
                        index,
                        int(input_q12),
                        int(coefficient_q12),
                        int(fixed_coefficient_q12),
                    )
                )


AC_STRATEGY_COST_PARAMS_Q16 = {
    64: (113980, 36384, 39627),
    128: (162424, 38540, 41947),
    256: (259311, 41504, 45247),
    512: (453086, 44817, 49098),
    1024: (646862, 47762, 52674),
    2048: (646862, 49892, 55356),
}


_FIXED_DCT_SQRT2_Q12 = 5793
_FIXED_DCT_WC4_Q12 = (2217, 5352)
_FIXED_DCT_WC8_Q12 = (2088, 2463, 3686, 10498)
_FIXED_DCT_WC16_Q12 = (2058, 2140, 2322, 2649, 3228, 4345, 7055, 20894)


def _fixed_dct_mul_q12(value: int, coefficient_q12: int) -> int:
    return (int(value) * int(coefficient_q12)) >> 12


def _fixed_dct2_q12(values: list[int]) -> list[int]:
    return [values[0] + values[1], values[0] - values[1]]


def _fixed_dct4_q12(values: list[int]) -> list[int]:
    even = _fixed_dct2_q12([values[0] + values[3], values[1] + values[2]])
    odd = _fixed_dct2_q12(
        [
            _fixed_dct_mul_q12(values[0] - values[3], _FIXED_DCT_WC4_Q12[0]),
            _fixed_dct_mul_q12(values[1] - values[2], _FIXED_DCT_WC4_Q12[1]),
        ]
    )
    odd[0] = _fixed_dct_mul_q12(odd[0], _FIXED_DCT_SQRT2_Q12) + odd[1]
    return [even[0], odd[0], even[1], odd[1]]


def _fixed_dct8_q12(values: list[int]) -> list[int]:
    even = _fixed_dct4_q12([values[i] + values[7 - i] for i in range(4)])
    odd = _fixed_dct4_q12(
        [
            _fixed_dct_mul_q12(
                values[i] - values[7 - i], _FIXED_DCT_WC8_Q12[i]
            )
            for i in range(4)
        ]
    )
    odd[0] = _fixed_dct_mul_q12(odd[0], _FIXED_DCT_SQRT2_Q12) + odd[1]
    for index in range(1, 3):
        odd[index] += odd[index + 1]
    return [
        even[0],
        odd[0],
        even[1],
        odd[1],
        even[2],
        odd[2],
        even[3],
        odd[3],
    ]


def _fixed_dct16_q12(values: list[int]) -> list[int]:
    even = _fixed_dct8_q12([values[i] + values[15 - i] for i in range(8)])
    odd = _fixed_dct8_q12(
        [
            _fixed_dct_mul_q12(
                values[i] - values[15 - i], _FIXED_DCT_WC16_Q12[i]
            )
            for i in range(8)
        ]
    )
    odd[0] = _fixed_dct_mul_q12(odd[0], _FIXED_DCT_SQRT2_Q12) + odd[1]
    for index in range(1, 7):
        odd[index] += odd[index + 1]
    return [value for index in range(8) for value in (even[index], odd[index])]


def fixed_rectangular_dct_q12(samples_q12):
    """Independent integer model of `DctRectangularApprox`."""
    np = _load_numpy()
    samples = np.asarray(samples_q12, dtype=np.int64)
    rows, columns = samples.shape
    if (rows, columns) not in ((16, 8), (8, 16)):
        raise ValueError("fixed rectangular DCT requires 16x8 or 8x16 samples")

    transform_rows = _fixed_dct16_q12 if rows == 16 else _fixed_dct8_q12
    transform_columns = _fixed_dct16_q12 if columns == 16 else _fixed_dct8_q12
    row_shift = int(math.log2(rows))
    column_shift = int(math.log2(columns))
    column_pass = [
        [value >> row_shift for value in transform_rows(samples[:, x].tolist())]
        for x in range(columns)
    ]
    row_pass = [
        [
            value >> column_shift
            for value in transform_columns(
                [column_pass[x][y_frequency] for x in range(columns)]
            )
        ]
        for y_frequency in range(rows)
    ]
    if rows == 16:
        canonical = [
            row_pass[y_frequency][x_frequency]
            for x_frequency in range(columns)
            for y_frequency in range(rows)
        ]
    else:
        canonical = [
            row_pass[y_frequency][x_frequency]
            for y_frequency in range(rows)
            for x_frequency in range(columns)
        ]
    return np.asarray(canonical, dtype=np.int64)


def fixed_cfl_multipliers_from_dct_q12(block_coefficients_q12) -> tuple[int, int]:
    """Model the Q12-to-Q16 tile estimator used by the frame scheduler."""
    np = _load_numpy()
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.chroma_from_luma import (  # pylint: disable=import-outside-toplevel
        INV_MATRIX_B,
        INV_MATRIX_X,
    )

    blocks = np.asarray(block_coefficients_q12, dtype=np.int64)
    if blocks.ndim != 3 or blocks.shape[1:] != (3, 64):
        raise ValueError("fixed CFL input must have shape (blocks, 3, 64)")
    weights = (
        np.rint(INV_MATRIX_X * np.float32(1 << 16)).astype(np.int64),
        np.rint(INV_MATRIX_B * np.float32(1 << 16)).astype(np.int64),
    )

    def estimate(use_b: bool) -> int:
        matrix = weights[1 if use_b else 0]
        sum_aa = 0
        sum_ab = 0
        count = 0
        for block in blocks:
            for coefficient in range(64):
                weight = int(matrix[coefficient])
                model_q16 = ((int(block[1, coefficient]) << 4) * weight) >> 16
                signal_channel = 2 if use_b else 0
                signal_q16 = (
                    (int(block[signal_channel, coefficient]) << 4) * weight
                ) >> 16
                a_q16 = _round_divide_signed(model_q16, 84)
                b_q16 = (model_q16 if use_b else 0) - signal_q16
                sum_aa += (a_q16 * a_q16) >> 16
                sum_ab += (a_q16 * b_q16) >> 16
                count += 1
        denominator = sum_aa + count * 33
        numerator = -sum_ab
        rounded = _round_divide_signed(numerator, denominator)
        return max(-128, min(127, rounded))

    return estimate(False), estimate(True)


def _round_divide_signed(value: int, denominator: int) -> int:
    if value >= 0:
        return (value + denominator // 2) // denominator
    return -((-value + denominator // 2) // denominator)


def _round_positive_q16(value: float) -> int:
    return int(math.floor(float(value) * (1 << 16) + 0.5))


def _ceil_log2_nonzero(value: int) -> int:
    if value <= 1:
        return 0
    return (value - 1).bit_length()


def fixed_ac_strategy_candidate_cost_q16(
    strategy: int,
    coefficients_q12,
    quant_q24: int,
    mask_q16: int,
    distance_q8: int,
    ytox: int,
    ytob: int,
) -> tuple[int, int]:
    """Evaluate the integer model used by `AcStrategyCandidateCostEvaluator`."""
    np = _load_numpy()
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.ac_strategy import (  # pylint: disable=import-outside-toplevel
        DCT,
        DCT16X8,
        DCT8X16,
        INV_MATRICES,
    )

    if strategy not in (DCT, DCT16X8, DCT8X16):
        raise ValueError(f"unsupported AC strategy: {strategy}")
    coefficient_count = 64 if strategy == DCT else 128
    coefficients = np.asarray(coefficients_q12, dtype=np.int64)
    if coefficients.shape != (3, coefficient_count):
        raise ValueError(
            f"strategy {strategy} requires Q12 coefficients shaped "
            f"(3, {coefficient_count}), got {coefficients.shape}"
        )
    if distance_q8 not in AC_STRATEGY_COST_PARAMS_Q16:
        distance_q8 = 256
    cost1_q16, dct_multiplier_q16, rectangular_multiplier_q16 = (
        AC_STRATEGY_COST_PARAMS_Q16[distance_q8]
    )

    scale = 1 << 16
    inverse_q16 = [
        np.rint(matrix * np.float32(scale)).astype(np.int64)
        for matrix in INV_MATRICES[strategy]
    ]
    cfl_factor_q16 = (
        _round_divide_signed(ytox * scale, 84),
        0,
        scale + _round_divide_signed(ytob * scale, 84),
    )
    cost2_q16 = _round_positive_q16(4.4628149885273363)
    cost_delta_q16 = _round_positive_q16(5.3359184934516337)
    info_loss2_multiplier_q16 = _round_positive_q16(50.46839691767866)

    entropy_q16 = 0
    info_loss_q16 = 0
    info_loss2_q32 = 0
    for channel in range(3):
        nonzeros = 0
        for coefficient in range(coefficient_count):
            residual_q28 = (
                int(coefficients[channel, coefficient]) * scale
                - int(coefficients[1, coefficient]) * cfl_factor_q16[channel]
            )
            value_q16 = _round_shift_signed(
                residual_q28
                * int(inverse_q16[channel][coefficient])
                * int(quant_q24),
                52,
            )
            magnitude = abs(value_q16)
            integer = magnitude >> 16
            fraction = magnitude & 0xFFFF
            round_up = fraction > 0x8000 or (
                fraction == 0x8000 and (integer & 1) != 0
            )
            quantized = integer + int(round_up)
            difference_q16 = min(fraction, scale - fraction)
            info_loss_q16 += difference_q16
            info_loss2_q32 += difference_q16 * difference_q16

            if quantized != 0:
                nonzeros += 1
                entropy_q16 += cost1_q16
            if quantized >= 2:
                entropy_q16 += cost2_q16
            sqrt_quantized_q16 = math.isqrt(quantized << 32)
            entropy_q16 += _round_shift_signed(
                sqrt_quantized_q16 * cost_delta_q16, 16
            )

        nbits = _ceil_log2_nonzero(nonzeros + 1) + 1
        entropy_q16 += _round_positive_q16(
            7.565053364251793 * (_ceil_log2_nonzero(nbits + 17) + nbits)
        )

    num_blocks = 1 if strategy == DCT else 2
    info_loss_root_q16 = math.isqrt(num_blocks * info_loss2_q32)
    info_loss_score_q16 = (
        138 * info_loss_q16
        + _round_shift_signed(
            info_loss_root_q16 * info_loss2_multiplier_q16, 16
        )
    )
    estimate_q16 = entropy_q16 + _round_shift_signed(
        mask_q16 * info_loss_score_q16, 16
    )
    if strategy == DCT:
        scaled_cost_q16 = _round_shift_signed(
            (estimate_q16 + 3 * scale) * dct_multiplier_q16, 16
        )
    else:
        scaled_cost_q16 = _round_shift_signed(
            estimate_q16 * rectangular_multiplier_q16, 16
        )
    return estimate_q16, scaled_cost_q16


def _strategy_decision_from_scaled_costs(costs: list[int]):
    np = _load_numpy()
    if len(costs) != 8:
        raise ValueError("one 2x2 AC-strategy decision requires eight candidate costs")
    dct = np.asarray(costs[:4], dtype=np.int64).reshape(2, 2)
    vertical = costs[4:6]
    horizontal = costs[6:8]
    vertical_dct = (dct[0, 0] + dct[1, 0], dct[0, 1] + dct[1, 1])
    horizontal_dct = (dct[0, 0] + dct[0, 1], dct[1, 0] + dct[1, 1])
    vertical_cost = sum(
        min(vertical[index], vertical_dct[index]) for index in range(2)
    )
    horizontal_cost = sum(
        min(horizontal[index], horizontal_dct[index]) for index in range(2)
    )
    decision = np.ones((2, 2), dtype=np.uint8)
    if vertical_cost < horizontal_cost:
        for x in range(2):
            if vertical[x] < vertical_dct[x]:
                decision[0, x] = np.uint8(3)
                decision[1, x] = np.uint8(2)
    else:
        for y in range(2):
            if horizontal[y] < horizontal_dct[y]:
                decision[y, 0] = np.uint8(5)
                decision[y, 1] = np.uint8(4)
    return decision


def write_ac_strategy_candidate_cost_q16_csv(
    path: Path, image, distance: float
) -> None:
    """Write one complete prepared 2x2 strategy-cost fixture."""
    np = _load_numpy()
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.ac_strategy import (  # pylint: disable=import-outside-toplevel
        DCT,
        DCT16X8,
        DCT8X16,
        estimate_entropy,
        find_best_16x16_transform,
    )
    from jxl_tiny.adaptive_quantization import (  # pylint: disable=import-outside-toplevel
        compute_adaptive_quantization,
    )
    from jxl_tiny.chroma_from_luma import (  # pylint: disable=import-outside-toplevel
        compute_chroma_from_luma,
    )
    from jxl_tiny.encoder import _effective_distance  # pylint: disable=import-outside-toplevel
    from jxl_tiny.transforms import scaled_dct  # pylint: disable=import-outside-toplevel

    xyb = xyb_from_python_port(image)
    if xyb.shape[1] < 16 or xyb.shape[2] < 16:
        raise ValueError("AC-strategy cost fixture requires at least a padded 16x16 image")
    effective_distance = _effective_distance(distance)
    distance_q8 = int(math.floor(effective_distance * 256.0 + 0.5))
    if distance_q8 not in AC_STRATEGY_COST_PARAMS_Q16:
        raise ValueError("AC-strategy cost fixture requires a supported Q8 distance")
    distance_f32 = np.float32(effective_distance)
    slope = min(np.float32(1.0), distance_f32 * np.float32(1.0 / 3.0))
    cost1 = np.float32(1.0) + slope * np.float32(8.8703248061477744)
    dct_multiplier = np.float32(
        np.float32(1.0735757687292623 * 0.75)
        + np.float32(-0.55 * 0.75)
        / (distance_f32 + np.float32(1.4))
    )
    rectangular_multiplier = np.float32(
        np.float32(0.9019587899705066)
        + np.float32(-0.55) / (distance_f32 + np.float32(1.6))
    )
    derived_cost_params_q16 = (
        _round_positive_q16(float(cost1)),
        _round_positive_q16(float(dct_multiplier)),
        _round_positive_q16(float(rectangular_multiplier)),
    )
    if AC_STRATEGY_COST_PARAMS_Q16[distance_q8] != derived_cost_params_q16:
        raise AssertionError("fixed AC-strategy distance parameters drifted")

    aq = compute_adaptive_quantization(
        xyb,
        effective_distance,
        block_x0=0,
        block_y0=0,
        block_width=2,
        block_height=2,
    )
    cfl = compute_chroma_from_luma(xyb[:, :16, :16])
    candidate_specs = (
        (DCT, 0, 0),
        (DCT, 1, 0),
        (DCT, 0, 1),
        (DCT, 1, 1),
        (DCT16X8, 0, 0),
        (DCT16X8, 1, 0),
        (DCT8X16, 0, 0),
        (DCT8X16, 0, 1),
    )
    candidates = []
    fixed_costs = []
    for candidate, (strategy, block_x, block_y) in enumerate(candidate_specs):
        rows, columns = {
            DCT: (8, 8),
            DCT16X8: (16, 8),
            DCT8X16: (8, 16),
        }[strategy]
        coefficients = np.asarray(
            [
                np.rint(
                    scaled_dct(
                        xyb[
                            channel,
                            block_y * 8 : block_y * 8 + rows,
                            block_x * 8 : block_x * 8 + columns,
                        ]
                    ).reshape(-1)
                    * np.float32(1 << 12)
                ).astype(np.int64)
                for channel in range(3)
            ],
            dtype=np.int64,
        )
        covered_y = 2 if strategy == DCT16X8 else 1
        covered_x = 2 if strategy == DCT8X16 else 1
        quant_q24 = int(
            np.rint(
                np.max(
                    aq.aq_map[
                        block_y : block_y + covered_y,
                        block_x : block_x + covered_x,
                    ]
                )
                * np.float32(1 << 24)
            )
        )
        mask_q16 = int(
            np.rint(
                np.max(
                    aq.mask[
                        block_y : block_y + covered_y,
                        block_x : block_x + covered_x,
                    ]
                )
                * np.float32(1 << 16)
            )
        )
        fixed_estimate_q16, fixed_scaled_cost_q16 = (
            fixed_ac_strategy_candidate_cost_q16(
                strategy,
                coefficients,
                quant_q24,
                mask_q16,
                distance_q8,
                int(cfl.ytox),
                int(cfl.ytob),
            )
        )
        reference_estimate = float(
            estimate_entropy(
                strategy,
                xyb,
                0,
                0,
                block_x,
                block_y,
                effective_distance,
                aq.aq_map,
                aq.mask,
                int(cfl.ytox),
                int(cfl.ytob),
            )
        )
        reference_multiplier = (
            dct_multiplier if strategy == DCT else rectangular_multiplier
        )
        reference_scaled_cost = (
            reference_estimate + (3.0 if strategy == DCT else 0.0)
        ) * float(reference_multiplier)
        candidates.append(
            (
                candidate,
                strategy,
                block_x,
                block_y,
                coefficients,
                quant_q24,
                mask_q16,
                fixed_estimate_q16,
                fixed_scaled_cost_q16,
                reference_estimate,
                reference_scaled_cost,
            )
        )
        fixed_costs.append(fixed_scaled_cost_q16)

    reference_decision = find_best_16x16_transform(
        xyb,
        aq.aq_map,
        aq.mask,
        effective_distance,
        int(cfl.ytox),
        int(cfl.ytob),
    ).decision
    fixed_decision = _strategy_decision_from_scaled_costs(fixed_costs)
    scheduler_xyb_q12 = np.rint(xyb[:, :16, :16] * np.float32(1 << 12)).astype(
        np.int64
    )
    scheduler_ytox, scheduler_ytob = fixed_cfl_multipliers_from_dct_q12(
        np.asarray([candidate[4] for candidate in candidates[:4]], dtype=np.int64)
    )
    scheduler_costs = []
    for candidate, (strategy, block_x, block_y) in enumerate(candidate_specs):
        if strategy == DCT:
            scheduler_coefficients = candidates[candidate][4]
        else:
            rows, columns = {
                DCT16X8: (16, 8),
                DCT8X16: (8, 16),
            }[strategy]
            scheduler_coefficients = np.asarray(
                [
                    fixed_rectangular_dct_q12(
                        scheduler_xyb_q12[
                            channel,
                            block_y * 8 : block_y * 8 + rows,
                            block_x * 8 : block_x * 8 + columns,
                        ]
                    )
                    for channel in range(3)
                ],
                dtype=np.int64,
            )
        _, scheduler_cost = fixed_ac_strategy_candidate_cost_q16(
            strategy,
            scheduler_coefficients,
            candidates[candidate][5],
            candidates[candidate][6],
            distance_q8,
            scheduler_ytox,
            scheduler_ytob,
        )
        scheduler_costs.append(scheduler_cost)
    scheduler_fixed_decision = _strategy_decision_from_scaled_costs(scheduler_costs)
    reference_decision_text = ":".join(
        str(int(value)) for value in reference_decision.reshape(-1)
    )
    fixed_decision_text = ":".join(
        str(int(value)) for value in fixed_decision.reshape(-1)
    )
    scheduler_fixed_decision_text = ":".join(
        str(int(value)) for value in scheduler_fixed_decision.reshape(-1)
    )

    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(
            (
                "candidate",
                "strategy",
                "block_x",
                "block_y",
                "coefficient",
                "x_q12",
                "y_q12",
                "b_q12",
                "quant_q24",
                "mask_q16",
                "distance_q8",
                "ytox",
                "ytob",
                "fixed_estimate_q16",
                "fixed_scaled_cost_q16",
                "reference_estimate",
                "reference_scaled_cost",
                "reference_decision",
                "fixed_decision",
                "scheduler_fixed_decision",
            )
        )
        for candidate in candidates:
            coefficient_count = candidate[4].shape[1]
            for coefficient in range(coefficient_count):
                writer.writerow(
                    (
                        candidate[0],
                        candidate[1],
                        candidate[2],
                        candidate[3],
                        coefficient,
                        int(candidate[4][0, coefficient]),
                        int(candidate[4][1, coefficient]),
                        int(candidate[4][2, coefficient]),
                        candidate[5],
                        candidate[6],
                        distance_q8,
                        int(cfl.ytox),
                        int(cfl.ytob),
                        candidate[7],
                        candidate[8],
                        f"{candidate[9]:.9g}",
                        f"{candidate[10]:.9g}",
                        reference_decision_text,
                        fixed_decision_text,
                        scheduler_fixed_decision_text,
                    )
                )


def default_ac_strategy_from_python_port(image):
    np = _load_numpy()
    padded = padded_input_from_python_port(image)
    y_blocks = padded.shape[1] // 8
    x_blocks = padded.shape[2] // 8
    dct_first = (0 << 1) | 1
    return np.full((y_blocks, x_blocks), dct_first, dtype=np.uint8)


def fixed_dct_only_metadata_from_fixture(
    image,
    fixed_raw_quant: int = 5,
    fixed_ytox: int = 0,
    fixed_ytob: int = 0,
):
    np = _load_numpy()
    _, ysize, xsize = image.shape
    x_blocks = (xsize + 7) // 8
    y_blocks = (ysize + 7) // 8
    x_tiles = (xsize + 63) // 64
    y_tiles = (ysize + 63) // 64
    return (
        np.full((y_blocks, x_blocks), fixed_raw_quant, dtype=np.uint8),
        np.full((y_tiles, x_tiles), fixed_ytox, dtype=np.int8),
        np.full((y_tiles, x_tiles), fixed_ytob, dtype=np.int8),
    )


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

    aq_map = np.empty((y_blocks, x_blocks), dtype=np.float32)
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
            aq_map[by0 : by0 + tile_blocks_y, bx0 : bx0 + tile_blocks_x] = aq.aq_map
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

    return (
        raw_quant_field,
        ytox_map,
        ytob_map,
        quantized_ac,
        num_nonzeros,
        num_nonzeros_map,
        quant_dc,
        aq_map,
    )


def dct_only_ac_metadata_tokens_from_python_port(image, distance: float):
    np = _load_numpy()
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.ac_strategy import DCT  # pylint: disable=import-outside-toplevel
    from jxl_tiny.tokenization import ac_metadata_tokens  # pylint: disable=import-outside-toplevel

    (
        raw_quant_field,
        ytox_map,
        ytob_map,
        _,
        _,
        _,
        _,
        _,
    ) = dct_only_quant_outputs_from_python_port(image, distance)
    ac_strategy = np.full(raw_quant_field.shape, np.uint8((DCT << 1) | 1))
    return ac_metadata_tokens(ytox_map, ytob_map, ac_strategy, raw_quant_field)


def inverse_global_ac_scale_from_python_port(distance: float) -> float:
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.adaptive_quantization import inverse_global_ac_scale  # pylint: disable=import-outside-toplevel
    from jxl_tiny.encoder import _effective_distance  # pylint: disable=import-outside-toplevel

    return float(inverse_global_ac_scale(_effective_distance(distance)))


def write_dct_only_aq_map_q24_csv(
    path: Path,
    aq_map,
    raw_quant_field,
    inv_global_scale: float,
) -> None:
    """Write the fixed-point seam between AQ heuristics and raw quant bytes."""
    np = _load_numpy()
    fraction_bits = 24
    scale = 1 << fraction_bits
    aq_q24 = np.rint(np.asarray(aq_map, dtype=np.float64) * scale).astype(np.int64)
    inv_scale_q24 = int(round(inv_global_scale * scale))
    if np.any(aq_q24 < 0) or np.any(aq_q24 > np.iinfo(np.uint32).max):
        raise ValueError("adaptive quantization Q24 map does not fit uint32")
    if inv_scale_q24 < 0 or inv_scale_q24 > np.iinfo(np.uint32).max:
        raise ValueError("inverse global AC scale Q24 does not fit uint32")
    fixed_raw = np.empty(aq_q24.shape, dtype=np.uint8)
    rounding_bias = 1 << (2 * fraction_bits - 1)
    for block_y in range(aq_q24.shape[0]):
        for block_x in range(aq_q24.shape[1]):
            product = int(aq_q24[block_y, block_x]) * inv_scale_q24
            rounded = (product + rounding_bias) >> (2 * fraction_bits)
            fixed_raw[block_y, block_x] = min(255, max(1, rounded))

    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(
            (
                "block",
                "block_x",
                "block_y",
                "aq_map_q24",
                "inv_global_scale_q24",
                "reference_raw_quant",
                "fixed_raw_quant",
            )
        )
        block = 0
        for block_y in range(aq_q24.shape[0]):
            for block_x in range(aq_q24.shape[1]):
                writer.writerow(
                    (
                        block,
                        block_x,
                        block_y,
                        int(aq_q24[block_y, block_x]),
                        inv_scale_q24,
                        int(raw_quant_field[block_y, block_x]),
                        int(fixed_raw[block_y, block_x]),
                    )
                )
                block += 1


def dct_only_token_outputs_from_python_port(image, distance: float):
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


def dct_only_bitstream_outputs_from_python_port(image, distance: float):
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.bitstream import (  # pylint: disable=import-outside-toplevel
        codestream_bytes,
        frame_bytes,
    )

    dc, ac_metadata, ac, ac_strategy = dct_only_token_outputs_from_python_port(
        image, distance
    )
    _, ysize, xsize = image.shape
    frame = frame_bytes(dc, ac_metadata, ac, ac_strategy, distance)
    codestream = codestream_bytes(xsize, ysize, dc, ac_metadata, ac, ac_strategy, distance)
    return frame, codestream


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
    aq_scale_q24, aq_dampen_q24, aq_inv_global_scale_q24 = (
        fixed_aq_final_scalars_q24(distance)
    )
    return {
        "format": "hjxl.distance_params.v1",
        "distance": float(distance),
        "distance_q8": int(round(float(distance) * 256.0)),
        "global_scale": int(params.global_scale),
        "quant_dc": int(params.quant_dc),
        "scale_q16": int(params.global_scale),
        "fixed_raw_quant": int(fixed_raw_quant),
        "inv_qac_q16": int((1 << 32) // (int(params.global_scale) * int(fixed_raw_quant))),
        "aq_scale_q24": aq_scale_q24,
        "aq_dampen_q24": aq_dampen_q24,
        "aq_inv_global_scale_q24": aq_inv_global_scale_q24,
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
    coefficient_fraction_bits = 16

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
                        "coefficient_fraction_bits": coefficient_fraction_bits,
                        "coefficients_q": np.rint(
                            block.raw_coefficients
                            * np.float32(1 << coefficient_fraction_bits)
                        )
                        .astype(np.int32)
                        .tolist(),
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
            "x_tiles": int(x_tiles),
            "y_tiles": int(y_tiles),
        },
        "coefficient_fraction_bits": coefficient_fraction_bits,
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


def write_var_dct_quantize_q16_csv(
    path: Path,
    image,
    distance: float,
    raw_quant: int,
    ytox: int,
    ytob: int,
) -> None:
    """Write first-block DCT/16x8/8x16 quantization fixtures.

    The input seam is the Q16 canonical coefficient payload intended for the
    RTL prepared boundary. The artifact contains both an exact frozen-Q16
    AC/DC/nonzero model and the real libjxl-tiny float result, including
    two-cell DC and shifted nonzero maps for rectangular transforms.
    """
    np = _load_numpy()
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.ac_strategy import (  # pylint: disable=import-outside-toplevel
        DCT,
        DCT16X8,
        DCT8X16,
        DCT_Y_WEIGHTS,
        DCT2_Y_WEIGHTS,
        INV_MATRICES,
    )
    from jxl_tiny.encoder import BLOCK_DIM, _ceil_div  # pylint: disable=import-outside-toplevel
    from jxl_tiny.quantization import (  # pylint: disable=import-outside-toplevel
        K_INV_DC_QUANT,
        compute_distance_params,
        quantize_ac_group,
    )

    if not 1 <= raw_quant <= 255:
        raise ValueError("VarDCT raw quant must fit a nonzero byte")
    if not -128 <= ytox <= 127 or not -128 <= ytob <= 127:
        raise ValueError("VarDCT CFL multipliers must fit signed bytes")

    xyb = xyb_from_python_port(image)
    x_blocks = _ceil_div(image.shape[2], BLOCK_DIM)
    y_blocks = _ceil_div(image.shape[1], BLOCK_DIM)
    if x_blocks < 2 or y_blocks < 2:
        raise ValueError("VarDCT fixture requires at least a 2x2 block image")

    params = compute_distance_params(distance)
    scale_q16 = int(params.global_scale)
    inv_qac_q16 = int(
        round((1.0 / (float(params.scale) * float(raw_quant))) * (1 << 16))
    )
    inv_dc_factor_q16 = [
        int(round(float(value) * float(params.scale_dc) * (1 << 16)))
        for value in K_INV_DC_QUANT
    ]
    x_qm_multiplier_q16 = int(
        round(math.pow(1.25, params.x_qm_scale - 2.0) * (1 << 16))
    )
    qf = np.full((y_blocks, x_blocks), np.uint8(raw_quant), dtype=np.uint8)
    ytox_map = np.full((1, 1), np.int8(ytox), dtype=np.int8)
    ytob_map = np.full((1, 1), np.int8(ytob), dtype=np.int8)

    def encoded(strategy: int, first: bool) -> np.uint8:
        return np.uint8((strategy << 1) | (1 if first else 0))

    def round_shift_signed(value: int, shift: int) -> int:
        magnitude = abs(int(value))
        rounded = (magnitude + (1 << (shift - 1))) >> shift
        return -rounded if value < 0 else rounded

    def trunc_div_signed(numerator: int, denominator: int) -> int:
        quotient = abs(int(numerator)) // int(denominator)
        return -quotient if numerator < 0 else quotient

    dct_thresholds_q12 = (
        (2376, 2929, 3031, 3195),
        (2376, 2601, 2703, 2867),
        (2376, 3072, 3072, 3072),
    )
    rectangular_thresholds_q12 = (
        (2351, 2904, 3006, 3170),
        (2351, 2576, 2679, 2843),
        (2351, 3047, 3047, 3047),
    )
    inv_matrices_q16 = {
        strategy: tuple(
            tuple(int(round(float(value) * (1 << 16))) for value in channel)
            for channel in INV_MATRICES[strategy]
        )
        for strategy in (DCT, DCT16X8, DCT8X16)
    }
    y_weights_q32 = {
        DCT: tuple(int(round(float(value) * (1 << 32))) for value in DCT_Y_WEIGHTS),
        DCT16X8: tuple(
            int(round(float(value) * (1 << 32))) for value in DCT2_Y_WEIGHTS
        ),
        DCT8X16: tuple(
            int(round(float(value) * (1 << 32))) for value in DCT2_Y_WEIGHTS
        ),
    }

    def quantize_channel_q16(
        coefficients_q16,
        channel: int,
        strategy: int,
        qm_multiplier_q16: int,
    ):
        coefficient_count = 64 if strategy == DCT else 128
        width = 8 if strategy == DCT else 16
        height = 8
        thresholds = (
            dct_thresholds_q12[channel]
            if strategy == DCT
            else rectangular_thresholds_q12[channel]
        )
        output = []
        for index in range(coefficient_count):
            inverse_weight_q16 = inv_matrices_q16[strategy][channel][index]
            weighted_q16 = (int(coefficients_q16[index]) * inverse_weight_q16) >> 16
            value_q16 = (
                weighted_q16 * scale_q16 * raw_quant * qm_multiplier_q16
            ) >> 32
            coefficient_x = index % width
            coefficient_y = index // width
            threshold_index = (
                (2 if coefficient_y >= height // 2 else 0)
                + (1 if coefficient_x >= width // 2 else 0)
            )
            threshold_q16 = thresholds[threshold_index] << 4
            output.append(
                round_shift_signed(value_q16, 16)
                if abs(value_q16) >= threshold_q16
                else 0
            )
        return output

    def low_frequency_pair_q16(coefficients_q16, strategy: int):
        if strategy == DCT:
            return [int(coefficients_q16[0])]
        scaled_low1 = round_shift_signed(
            int(coefficients_q16[1]) * 3873047726, 32
        )
        return [
            int(coefficients_q16[0]) + scaled_low1,
            int(coefficients_q16[0]) - scaled_low1,
        ]

    def quantize_dc_q16(
        coefficient_q16: int,
        channel: int,
        quantized_y_dc: int,
    ) -> int:
        product_q32 = coefficient_q16 * inv_dc_factor_q16[channel]
        if channel == 2:
            product_q32 -= (quantized_y_dc * (1 << 15)) << 16
        return round_shift_signed(product_q32, 32)

    def fixed_quantize_block(raw_coefficients_q16, strategy: int):
        coefficient_count = 64 if strategy == DCT else 128
        quantized_y = quantize_channel_q16(
            raw_coefficients_q16[1], 1, strategy, 1 << 16
        )
        reconstructed_y_q16 = []
        for coefficient, weight_q32 in zip(
            quantized_y, y_weights_q32[strategy]
        ):
            magnitude = abs(coefficient)
            if magnitude == 0:
                adjusted_q16 = 0
            elif magnitude == 1:
                adjusted_q16 = 60945
            else:
                adjusted_q16 = (magnitude << 16) - 9503 // magnitude
            if coefficient < 0:
                adjusted_q16 = -adjusted_q16
            reconstructed_y_q16.append(
                (adjusted_q16 * weight_q32 * inv_qac_q16) >> 48
            )

        x_factor_q16 = trunc_div_signed(ytox * (1 << 16), 84)
        b_factor_q16 = (1 << 16) + trunc_div_signed(ytob * (1 << 16), 84)
        residual_x_q16 = [
            int(raw_coefficients_q16[0][index])
            - ((x_factor_q16 * reconstructed_y_q16[index]) >> 16)
            for index in range(coefficient_count)
        ]
        residual_b_q16 = [
            int(raw_coefficients_q16[2][index])
            - ((b_factor_q16 * reconstructed_y_q16[index]) >> 16)
            for index in range(coefficient_count)
        ]
        quantized_x = quantize_channel_q16(
            residual_x_q16, 0, strategy, x_qm_multiplier_q16
        )
        quantized_b = quantize_channel_q16(
            residual_b_q16, 2, strategy, 1 << 16
        )
        quantized_ac = (quantized_x, quantized_y, quantized_b)

        y_dc_coefficients = low_frequency_pair_q16(
            raw_coefficients_q16[1], strategy
        )
        x_dc_coefficients = low_frequency_pair_q16(residual_x_q16, strategy)
        b_dc_coefficients = low_frequency_pair_q16(residual_b_q16, strategy)
        quantized_y_dc = [
            quantize_dc_q16(coefficient, 1, 0)
            for coefficient in y_dc_coefficients
        ]
        quantized_dc = (
            [
                quantize_dc_q16(coefficient, 0, quantized_y_dc[index])
                for index, coefficient in enumerate(x_dc_coefficients)
            ],
            quantized_y_dc,
            [
                quantize_dc_q16(coefficient, 2, quantized_y_dc[index])
                for index, coefficient in enumerate(b_dc_coefficients)
            ],
        )

        llf_count = 1 if strategy == DCT else 2
        num_nonzeros = tuple(
            sum(value != 0 for value in channel[llf_count:])
            for channel in quantized_ac
        )
        shifted_nonzeros = tuple(
            value if strategy == DCT else (value + 1) >> 1
            for value in num_nonzeros
        )
        return quantized_ac, quantized_dc, num_nonzeros, shifted_nonzeros

    rows = []
    for strategy in (DCT, DCT16X8, DCT8X16):
        strategy_grid = np.full(
            (y_blocks, x_blocks), encoded(DCT, True), dtype=np.uint8
        )
        if strategy == DCT16X8:
            strategy_grid[0, 0] = encoded(strategy, True)
            strategy_grid[1, 0] = encoded(strategy, False)
        elif strategy == DCT8X16:
            strategy_grid[0, 0] = encoded(strategy, True)
            strategy_grid[0, 1] = encoded(strategy, False)

        block = quantize_ac_group(
            xyb, qf, strategy_grid, ytox_map, ytob_map, distance
        )[(0, 0)]
        coefficient_count = int(block.raw_coefficients.shape[1])
        dc = block.block_quant_dc.reshape(3, -1)
        shifted_nonzeros = block.num_nonzeros_map.reshape(3, -1)[:, 0]
        coefficients_q16 = np.rint(
            block.raw_coefficients * np.float32(1 << 16)
        ).astype(np.int32)
        (
            fixed_quantized_ac,
            fixed_quantized_dc,
            fixed_num_nonzeros,
            fixed_shifted_nonzeros,
        ) = fixed_quantize_block(coefficients_q16, strategy)

        rows.append(
            (
                strategy,
                coefficient_count,
                int(dc.shape[1]),
                raw_quant,
                scale_q16,
                inv_qac_q16,
                " ".join(str(value) for value in inv_dc_factor_q16),
                x_qm_multiplier_q16,
                ytox,
                ytob,
                *(" ".join(str(int(value)) for value in row) for row in coefficients_q16),
                *(" ".join(str(int(value)) for value in row) for row in fixed_quantized_ac),
                *(" ".join(str(int(value)) for value in row) for row in fixed_quantized_dc),
                " ".join(str(int(value)) for value in fixed_num_nonzeros),
                " ".join(str(int(value)) for value in fixed_shifted_nonzeros),
                *(" ".join(str(int(value)) for value in row) for row in block.quantized_ac),
                *(" ".join(str(int(value)) for value in row) for row in dc),
                " ".join(str(int(value)) for value in block.num_nonzeros),
                " ".join(str(int(value)) for value in shifted_nonzeros),
            )
        )

    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(
            (
                "strategy",
                "coefficient_count",
                "covered_blocks",
                "quant",
                "scale_q16",
                "inv_qac_q16",
                "inv_dc_factor_q16",
                "x_qm_multiplier_q16",
                "ytox",
                "ytob",
                "coefficients_x_q16",
                "coefficients_y_q16",
                "coefficients_b_q16",
                "fixed_quantized_ac_x",
                "fixed_quantized_ac_y",
                "fixed_quantized_ac_b",
                "fixed_quantized_dc_x",
                "fixed_quantized_dc_y",
                "fixed_quantized_dc_b",
                "fixed_num_nonzeros",
                "fixed_shifted_num_nonzeros",
                "quantized_ac_x",
                "quantized_ac_y",
                "quantized_ac_b",
                "quantized_dc_x",
                "quantized_dc_y",
                "quantized_dc_b",
                "num_nonzeros",
                "shifted_num_nonzeros",
            )
        )
        writer.writerows(rows)


def fixed_dct_only_token_outputs_from_python_port(
    image,
    distance: float,
    fixed_raw_quant: int = 5,
    fixed_ytox: int = 0,
    fixed_ytob: int = 0,
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
    ytox_map = np.full((y_tiles, x_tiles), fixed_ytox, dtype=np.int8)
    ytob_map = np.full((y_tiles, x_tiles), fixed_ytob, dtype=np.int8)
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


def write_var_dct_token_fixture(directory: Path, image, distance: float) -> None:
    """Write one mixed-shape first-block fixture and its exact logical tokens."""
    np = _load_numpy()
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.ac_strategy import (  # pylint: disable=import-outside-toplevel
        DCT,
        DCT16X8,
        DCT8X16,
        is_first_block,
        raw_strategy,
    )
    from jxl_tiny.encoder import _effective_distance  # pylint: disable=import-outside-toplevel
    from jxl_tiny.quantization import quantize_ac_group  # pylint: disable=import-outside-toplevel
    from jxl_tiny.tokenization import (  # pylint: disable=import-outside-toplevel
        ac_metadata_tokens,
        ac_tokens_from_quantized_blocks,
        dc_tokens,
    )

    if image.shape != (3, 16, 32):
        raise ValueError("VarDCT token fixture requires --width 32 --height 16")

    xyb = xyb_from_python_port(image)
    strategy = np.asarray(
        (
            ((DCT16X8 << 1) | 1, (DCT << 1) | 1, (DCT8X16 << 1) | 1, DCT8X16 << 1),
            (DCT16X8 << 1, (DCT << 1) | 1, (DCT << 1) | 1, (DCT << 1) | 1),
        ),
        dtype=np.uint8,
    )
    raw_quant = np.asarray(((3, 7, 11, 11), (3, 13, 17, 19)), dtype=np.uint8)
    ytox = np.asarray([[-9]], dtype=np.int8)
    ytob = np.asarray([[7]], dtype=np.int8)
    quantized = quantize_ac_group(
        xyb,
        raw_quant,
        strategy,
        ytox,
        ytob,
        _effective_distance(distance),
    )

    quant_dc = np.zeros((3, 2, 4), dtype=np.int16)
    nzeros_map = np.zeros((3, 2, 4), dtype=np.uint8)
    owners = []
    for block_y in range(2):
        for block_x in range(4):
            encoded = strategy[block_y, block_x]
            if not bool(is_first_block(encoded)):
                continue
            block = quantized[(block_y, block_x)]
            raw = int(raw_strategy(encoded))
            covered_y = block.block_quant_dc.shape[1]
            covered_x = block.block_quant_dc.shape[2]
            quant_dc[
                :, block_y : block_y + covered_y, block_x : block_x + covered_x
            ] = block.block_quant_dc
            nzeros_map[
                :, block_y : block_y + covered_y, block_x : block_x + covered_x
            ] = block.num_nonzeros_map
            owners.append(
                (
                    block_x,
                    block_y,
                    block_y * 4 + block_x,
                    raw,
                    block.quantized_ac.shape[1],
                    covered_x * covered_y,
                    int(raw_quant[block_y, block_x]),
                    block,
                )
            )

    dc = dc_tokens(quant_dc)
    metadata = ac_metadata_tokens(ytox, ytob, strategy, raw_quant)
    ac = ac_tokens_from_quantized_blocks(strategy, quantized, nzeros_map)

    directory.mkdir(parents=True, exist_ok=True)
    with (directory / "owners.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(
            (
                "block_x",
                "block_y",
                "block_ordinal",
                "last",
                "strategy",
                "coefficient_count",
                "covered_blocks",
                "raw_quant",
                "ytox",
                "ytob",
                "quantized_ac_x",
                "quantized_ac_y",
                "quantized_ac_b",
                "quantized_dc_x",
                "quantized_dc_y",
                "quantized_dc_b",
                "num_nonzeros",
                "shifted_num_nonzeros",
            )
        )
        for owner_index, (
            block_x,
            block_y,
            block_ordinal,
            raw,
            coefficient_count,
            covered_blocks,
            owner_quant,
            block,
        ) in enumerate(owners):
            writer.writerow(
                (
                    block_x,
                    block_y,
                    block_ordinal,
                    int(owner_index == len(owners) - 1),
                    raw,
                    coefficient_count,
                    covered_blocks,
                    owner_quant,
                    int(ytox[0, 0]),
                    int(ytob[0, 0]),
                    *(";".join(str(int(value)) for value in block.quantized_ac[channel])
                      for channel in range(3)),
                    *(";".join(str(int(value)) for value in block.block_quant_dc[channel].reshape(-1))
                      for channel in range(3)),
                    ";".join(str(int(value)) for value in block.num_nonzeros),
                    ";".join(
                        str(int(block.num_nonzeros_map[channel].reshape(-1)[0]))
                        for channel in range(3)
                    ),
                )
            )

    trace_rows = []
    trace_rows.extend(("dc", index, context, value) for index, (context, value) in enumerate(dc))
    trace_rows.extend(
        ("strategy", 0, index, int(value))
        for index, value in enumerate(strategy.reshape(-1))
    )
    trace_rows.extend(
        ("metadata", index, context, value)
        for index, (context, value) in enumerate(metadata)
    )
    trace_rows.extend(("ac", index, context, value) for index, (context, value) in enumerate(ac))
    with (directory / "trace.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(("section", "group", "index", "value", "last"))
        for index, (section, group, context, value) in enumerate(trace_rows):
            writer.writerow((section, int(group), int(context), int(value), int(index == len(trace_rows) - 1)))


def fixed_dct_only_prepared_token_inputs_from_python_port(
    image,
    distance: float,
    fixed_raw_quant: int = 5,
    fixed_ytox: int = 0,
    fixed_ytob: int = 0,
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
    ytox_map = np.full((y_tiles, x_tiles), fixed_ytox, dtype=np.int8)
    ytob_map = np.full((y_tiles, x_tiles), fixed_ytob, dtype=np.int8)
    tile_dim_in_blocks = TILE_DIM // BLOCK_DIM
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
                    "tile_x": int(bx // tile_dim_in_blocks),
                    "tile_y": int(by // tile_dim_in_blocks),
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
            "x_tiles": int(x_tiles),
            "y_tiles": int(y_tiles),
        },
        "distance": float(distance),
        "fixed_raw_quant": int(fixed_raw_quant),
        "fixed_ytox": int(fixed_ytox),
        "fixed_ytob": int(fixed_ytob),
        "dc_sample_order": "Y-plane raster, X-plane raster, B-plane raster",
        "dc_samples": dc_samples,
        "ac_block_order": "raster blocks, each block stores X/Y/B channels",
        "ac_blocks": ac_blocks,
    }


def fixed_dct_only_bitstream_outputs_from_python_port(
    image,
    distance: float,
    fixed_raw_quant: int = 5,
    fixed_ytox: int = 0,
    fixed_ytob: int = 0,
):
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.bitstream import (  # pylint: disable=import-outside-toplevel
        codestream_bytes,
        frame_bytes,
    )

    dc, ac_metadata, ac, ac_strategy = fixed_dct_only_token_outputs_from_python_port(
        image, distance, fixed_raw_quant, fixed_ytox, fixed_ytob
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

    dc_tokens = load_token_input_pairs(dc_tokens_path, "token-input dc-tokens-npy")
    ac_metadata_tokens = load_token_input_pairs(
        ac_metadata_tokens_path, "token-input ac-metadata-tokens-npy"
    )
    ac_tokens = load_token_input_pairs(ac_tokens_path, "token-input ac-tokens-npy")
    ac_strategy = load_token_input_ac_strategy(
        ac_strategy_path, "token-input ac-strategy-npy"
    )
    frame = frame_bytes(dc_tokens, ac_metadata_tokens, ac_tokens, ac_strategy, distance)
    codestream = codestream_bytes(
        xsize, ysize, dc_tokens, ac_metadata_tokens, ac_tokens, ac_strategy, distance
    )
    return frame, codestream


def load_token_input_pairs(path: Path, label: str):
    np = _load_numpy()
    array = np.asarray(np.load(path))
    if array.ndim != 2 or array.shape[1] != 2:
        raise ValueError(f"{label}: expected shape (n, 2), got {array.shape}")
    if not np.issubdtype(array.dtype, np.integer):
        raise ValueError(f"{label}: expected integer token pairs, got {array.dtype}")

    max_uint32 = np.iinfo(np.uint32).max
    below_min = (
        array < 0
        if np.issubdtype(array.dtype, np.signedinteger)
        else np.zeros(array.shape, dtype=bool)
    )
    above_max = array > max_uint32
    out_of_range = np.argwhere(below_min | above_max)
    if out_of_range.size:
        row = int(out_of_range[0][0])
        column = int(out_of_range[0][1])
        field = "context" if column == 0 else "value"
        raise ValueError(
            f"{label}: token {field} {int(array[row, column])} outside uint32 at row {row}"
        )
    return array.astype(np.uint32, copy=False)


def load_token_input_ac_strategy(path: Path, label: str):
    np = _load_numpy()
    array = np.asarray(np.load(path))
    if array.ndim != 2:
        raise ValueError(f"{label}: expected 2D AC strategy grid, got {array.shape}")
    if not np.issubdtype(array.dtype, np.integer):
        raise ValueError(f"{label}: expected integer AC strategy grid, got {array.dtype}")

    max_uint8 = np.iinfo(np.uint8).max
    below_min = (
        array < 0
        if np.issubdtype(array.dtype, np.signedinteger)
        else np.zeros(array.shape, dtype=bool)
    )
    above_max = array > max_uint8
    out_of_range = np.argwhere(below_min | above_max)
    if out_of_range.size:
        index = tuple(int(value) for value in out_of_range[0])
        raise ValueError(
            f"{label}: AC strategy value {int(array[index])} outside uint8 at index {index}"
        )
    return array.astype(np.uint8, copy=False)


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
    parser.add_argument(
        "--fixed-ytox",
        type=int,
        default=0,
        help="fixed all-DCT signed 8-bit Y-to-X CFL value for fixed-token oracles",
    )
    parser.add_argument(
        "--fixed-ytob",
        type=int,
        default=0,
        help="fixed all-DCT signed 8-bit Y-to-B CFL value for fixed-token oracles",
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
        "--xyb-q12-csv",
        type=Path,
        help="optional signed-Q8 RGB to signed-Q12 XYB fixed-point fixture",
    )
    parser.add_argument(
        "--aq-contrast-q16-csv",
        type=Path,
        help="optional quarter-resolution AQ pre-erosion Q16 fixture",
    )
    parser.add_argument(
        "--aq-fuzzy-erosion-q16-csv",
        type=Path,
        help="optional block-resolution AQ fuzzy-erosion Q16 fixture",
    )
    parser.add_argument(
        "--aq-strategy-mask-q16-csv",
        type=Path,
        help="optional block-resolution AQ strategy-mask Q16 fixture",
    )
    parser.add_argument(
        "--aq-nonlinear-mask-q24-csv",
        type=Path,
        help="optional signed-Q24 `_compute_mask` AQ modulation-seed fixture",
    )
    parser.add_argument(
        "--aq-hf-modulation-q24-csv",
        type=Path,
        help="optional signed-Q24 AQ seed after per-block Y HF modulation",
    )
    parser.add_argument(
        "--aq-color-modulation-q24-csv",
        type=Path,
        help="optional signed-Q24 AQ seed after per-block red/blue color modulation",
    )
    parser.add_argument(
        "--aq-gamma-modulation-q24-csv",
        type=Path,
        help="optional signed-Q24 AQ seed after per-block gamma modulation",
    )
    parser.add_argument(
        "--aq-final-map-q24-csv",
        type=Path,
        help="optional completed unsigned-Q24 AQ map and raw-quant evidence",
    )
    parser.add_argument(
        "--dct8x8-npy",
        type=Path,
        help="optional libjxl-tiny raster 8x8 XYB DCT blocks NumPy output path",
    )
    parser.add_argument(
        "--scaled-dct-q12-csv",
        type=Path,
        help="optional Q12 DCT-16 and canonical 16x8/8x16 transform fixture",
    )
    parser.add_argument(
        "--ac-strategy-cost-q16-csv",
        type=Path,
        help="optional fixed-point prepared AC-strategy candidate-cost fixture",
    )
    parser.add_argument(
        "--var-dct-quantize-q16-csv",
        type=Path,
        help="optional Q16 prepared DCT/16x8/8x16 quantization fixture",
    )
    parser.add_argument(
        "--var-dct-token-fixture-dir",
        type=Path,
        help="optional mixed-shape first-block owner and logical-token CSV directory",
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
        "--dct-only-aq-map-q24-csv",
        type=Path,
        help="optional Q24 AQ-map to raw-quant conversion fixture for the default all-DCT strategy",
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
        "--dct-only-ac-metadata-tokens-npy",
        type=Path,
        help="optional AC-metadata token oracle for the default all-DCT strategy",
    )
    parser.add_argument(
        "--dct-only-dc-tokens-npy",
        type=Path,
        help="optional DC token oracle for the adaptive default all-DCT strategy",
    )
    parser.add_argument(
        "--dct-only-ac-tokens-npy",
        type=Path,
        help="optional AC token oracle for the adaptive default all-DCT strategy",
    )
    parser.add_argument(
        "--dct-only-prepared-blocks-json",
        type=Path,
        help="optional prepared-block JSON oracle for the default all-DCT strategy",
    )
    parser.add_argument(
        "--dct-only-frame-bin",
        type=Path,
        help="optional adaptive all-DCT frame bytes serialized from logical tokens",
    )
    parser.add_argument(
        "--dct-only-codestream-bin",
        type=Path,
        help="optional adaptive all-DCT bare codestream bytes serialized from logical tokens",
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
        "--fixed-dct-only-raw-quant-field-npy",
        type=Path,
        help="optional fixed-quant raw quant-field block grid",
    )
    parser.add_argument(
        "--fixed-dct-only-ytox-map-npy",
        type=Path,
        help="optional fixed-quant Y-to-X CFL tile grid",
    )
    parser.add_argument(
        "--fixed-dct-only-ytob-map-npy",
        type=Path,
        help="optional fixed-quant Y-to-B CFL tile grid",
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
    for name, value in (
        ("--fixed-ytox", args.fixed_ytox),
        ("--fixed-ytob", args.fixed_ytob),
    ):
        if value < -(1 << 7) or value > (1 << 7) - 1:
            raise SystemExit(f"{name} must fit in signed 8-bit")

    image = generate_fixture(args.width, args.height, args.pattern)
    quant_metadata = None
    dct_only_quant_outputs = None
    dct_only_token_outputs = None
    dct_only_bitstream_outputs = None
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

    def get_dct_only_token_outputs():
        nonlocal dct_only_token_outputs
        if dct_only_token_outputs is None:
            dct_only_token_outputs = dct_only_token_outputs_from_python_port(
                image, args.distance
            )
        return dct_only_token_outputs

    def get_dct_only_bitstream_outputs():
        nonlocal dct_only_bitstream_outputs
        if dct_only_bitstream_outputs is None:
            dct_only_bitstream_outputs = dct_only_bitstream_outputs_from_python_port(
                image, args.distance
            )
        return dct_only_bitstream_outputs

    def get_fixed_dct_only_token_outputs():
        nonlocal fixed_dct_only_token_outputs
        if fixed_dct_only_token_outputs is None:
            fixed_dct_only_token_outputs = fixed_dct_only_token_outputs_from_python_port(
                image,
                args.distance,
                args.fixed_raw_quant,
                args.fixed_ytox,
                args.fixed_ytob,
            )
        return fixed_dct_only_token_outputs

    def get_fixed_dct_only_prepared_token_inputs():
        nonlocal fixed_dct_only_prepared_token_inputs
        if fixed_dct_only_prepared_token_inputs is None:
            fixed_dct_only_prepared_token_inputs = (
                fixed_dct_only_prepared_token_inputs_from_python_port(
                    image,
                    args.distance,
                    args.fixed_raw_quant,
                    args.fixed_ytox,
                    args.fixed_ytob,
                )
            )
        return fixed_dct_only_prepared_token_inputs

    def get_fixed_dct_only_bitstream_outputs():
        nonlocal fixed_dct_only_bitstream_outputs
        if fixed_dct_only_bitstream_outputs is None:
            fixed_dct_only_bitstream_outputs = fixed_dct_only_bitstream_outputs_from_python_port(
                image,
                args.distance,
                args.fixed_raw_quant,
                args.fixed_ytox,
                args.fixed_ytob,
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
            try:
                token_input_bitstream_outputs = bitstream_outputs_from_token_inputs(
                    args.width,
                    args.height,
                    args.distance,
                    args.token_input_dc_tokens_npy,
                    args.token_input_ac_metadata_tokens_npy,
                    args.token_input_ac_tokens_npy,
                    args.token_input_ac_strategy_npy,
                )
            except ValueError as exc:
                raise SystemExit(str(exc))
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
    if args.xyb_q12_csv is not None:
        write_xyb_q12_csv(args.xyb_q12_csv, image)
    if args.aq_contrast_q16_csv is not None:
        write_aq_contrast_q16_csv(args.aq_contrast_q16_csv, image)
    if args.aq_fuzzy_erosion_q16_csv is not None:
        write_aq_fuzzy_erosion_q16_csv(args.aq_fuzzy_erosion_q16_csv, image)
    if args.aq_strategy_mask_q16_csv is not None:
        write_aq_strategy_mask_q16_csv(args.aq_strategy_mask_q16_csv, image)
    if args.aq_nonlinear_mask_q24_csv is not None:
        write_aq_nonlinear_mask_q24_csv(args.aq_nonlinear_mask_q24_csv, image)
    if args.aq_hf_modulation_q24_csv is not None:
        write_aq_hf_modulation_q24_csv(args.aq_hf_modulation_q24_csv, image)
    if args.aq_color_modulation_q24_csv is not None:
        write_aq_color_modulation_q24_csv(
            args.aq_color_modulation_q24_csv,
            image,
            args.distance,
        )
    if args.aq_gamma_modulation_q24_csv is not None:
        write_aq_gamma_modulation_q24_csv(
            args.aq_gamma_modulation_q24_csv,
            image,
            args.distance,
        )
    if args.aq_final_map_q24_csv is not None:
        write_aq_final_map_q24_csv(
            args.aq_final_map_q24_csv,
            image,
            args.distance,
        )
    if args.dct8x8_npy is not None:
        np = _load_numpy()
        args.dct8x8_npy.parent.mkdir(parents=True, exist_ok=True)
        np.save(args.dct8x8_npy, dct8x8_from_python_port(image))
    if args.scaled_dct_q12_csv is not None:
        write_scaled_dct_q12_csv(args.scaled_dct_q12_csv)
    if args.ac_strategy_cost_q16_csv is not None:
        write_ac_strategy_candidate_cost_q16_csv(
            args.ac_strategy_cost_q16_csv,
            image,
            args.distance,
        )
    if args.var_dct_quantize_q16_csv is not None:
        write_var_dct_quantize_q16_csv(
            args.var_dct_quantize_q16_csv,
            image,
            args.distance,
            args.fixed_raw_quant,
            args.fixed_ytox,
            args.fixed_ytob,
        )
    if args.var_dct_token_fixture_dir is not None:
        write_var_dct_token_fixture(
            args.var_dct_token_fixture_dir,
            image,
            args.distance,
        )
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
        raw_quant_field, _, _, _, _, _, _, _ = get_dct_only_quant_outputs()
        np.save(args.dct_only_raw_quant_field_npy, raw_quant_field)
    if args.dct_only_aq_map_q24_csv is not None:
        raw_quant_field, _, _, _, _, _, _, aq_map = get_dct_only_quant_outputs()
        write_dct_only_aq_map_q24_csv(
            args.dct_only_aq_map_q24_csv,
            aq_map,
            raw_quant_field,
            inverse_global_ac_scale_from_python_port(args.distance),
        )
    if args.dct_only_ytox_map_npy is not None:
        np = _load_numpy()
        args.dct_only_ytox_map_npy.parent.mkdir(parents=True, exist_ok=True)
        _, ytox_map, _, _, _, _, _, _ = get_dct_only_quant_outputs()
        np.save(args.dct_only_ytox_map_npy, ytox_map)
    if args.dct_only_ytob_map_npy is not None:
        np = _load_numpy()
        args.dct_only_ytob_map_npy.parent.mkdir(parents=True, exist_ok=True)
        _, _, ytob_map, _, _, _, _, _ = get_dct_only_quant_outputs()
        np.save(args.dct_only_ytob_map_npy, ytob_map)
    if args.dct_only_quantized_ac_npy is not None:
        np = _load_numpy()
        args.dct_only_quantized_ac_npy.parent.mkdir(parents=True, exist_ok=True)
        _, _, _, quantized_ac, _, _, _, _ = get_dct_only_quant_outputs()
        np.save(args.dct_only_quantized_ac_npy, quantized_ac)
    if args.dct_only_num_nonzeros_npy is not None:
        np = _load_numpy()
        args.dct_only_num_nonzeros_npy.parent.mkdir(parents=True, exist_ok=True)
        _, _, _, _, num_nonzeros, _, _, _ = get_dct_only_quant_outputs()
        np.save(args.dct_only_num_nonzeros_npy, num_nonzeros)
    if args.dct_only_num_nonzeros_map_npy is not None:
        np = _load_numpy()
        args.dct_only_num_nonzeros_map_npy.parent.mkdir(parents=True, exist_ok=True)
        _, _, _, _, _, num_nonzeros_map, _, _ = get_dct_only_quant_outputs()
        np.save(args.dct_only_num_nonzeros_map_npy, num_nonzeros_map)
    if args.dct_only_quant_dc_npy is not None:
        np = _load_numpy()
        args.dct_only_quant_dc_npy.parent.mkdir(parents=True, exist_ok=True)
        _, _, _, _, _, _, quant_dc, _ = get_dct_only_quant_outputs()
        np.save(args.dct_only_quant_dc_npy, quant_dc)
    if args.dct_only_ac_metadata_tokens_npy is not None:
        np = _load_numpy()
        args.dct_only_ac_metadata_tokens_npy.parent.mkdir(parents=True, exist_ok=True)
        _, ac_metadata, _, _ = get_dct_only_token_outputs()
        np.save(args.dct_only_ac_metadata_tokens_npy, ac_metadata)
    if args.dct_only_dc_tokens_npy is not None:
        np = _load_numpy()
        args.dct_only_dc_tokens_npy.parent.mkdir(parents=True, exist_ok=True)
        dc, _, _, _ = get_dct_only_token_outputs()
        np.save(args.dct_only_dc_tokens_npy, dc)
    if args.dct_only_ac_tokens_npy is not None:
        np = _load_numpy()
        args.dct_only_ac_tokens_npy.parent.mkdir(parents=True, exist_ok=True)
        _, _, ac, _ = get_dct_only_token_outputs()
        np.save(args.dct_only_ac_tokens_npy, ac)
    if args.dct_only_prepared_blocks_json is not None:
        args.dct_only_prepared_blocks_json.parent.mkdir(parents=True, exist_ok=True)
        args.dct_only_prepared_blocks_json.write_text(
            json.dumps(dct_only_prepared_blocks_from_python_port(image, args.distance), indent=2),
            encoding="utf-8",
        )
    if args.dct_only_frame_bin is not None:
        args.dct_only_frame_bin.parent.mkdir(parents=True, exist_ok=True)
        frame, _ = get_dct_only_bitstream_outputs()
        args.dct_only_frame_bin.write_bytes(frame)
    if args.dct_only_codestream_bin is not None:
        args.dct_only_codestream_bin.parent.mkdir(parents=True, exist_ok=True)
        _, codestream = get_dct_only_bitstream_outputs()
        args.dct_only_codestream_bin.write_bytes(codestream)
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
    if args.fixed_dct_only_raw_quant_field_npy is not None:
        np = _load_numpy()
        args.fixed_dct_only_raw_quant_field_npy.parent.mkdir(parents=True, exist_ok=True)
        raw_quant_field, _, _ = fixed_dct_only_metadata_from_fixture(
            image, args.fixed_raw_quant, args.fixed_ytox, args.fixed_ytob
        )
        np.save(args.fixed_dct_only_raw_quant_field_npy, raw_quant_field)
    if args.fixed_dct_only_ytox_map_npy is not None:
        np = _load_numpy()
        args.fixed_dct_only_ytox_map_npy.parent.mkdir(parents=True, exist_ok=True)
        _, ytox_map, _ = fixed_dct_only_metadata_from_fixture(
            image, args.fixed_raw_quant, args.fixed_ytox, args.fixed_ytob
        )
        np.save(args.fixed_dct_only_ytox_map_npy, ytox_map)
    if args.fixed_dct_only_ytob_map_npy is not None:
        np = _load_numpy()
        args.fixed_dct_only_ytob_map_npy.parent.mkdir(parents=True, exist_ok=True)
        _, _, ytob_map = fixed_dct_only_metadata_from_fixture(
            image, args.fixed_raw_quant, args.fixed_ytox, args.fixed_ytob
        )
        np.save(args.fixed_dct_only_ytob_map_npy, ytob_map)
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
        and args.xyb_q12_csv is None
        and args.aq_contrast_q16_csv is None
        and args.aq_fuzzy_erosion_q16_csv is None
        and args.aq_strategy_mask_q16_csv is None
        and args.aq_nonlinear_mask_q24_csv is None
        and args.aq_hf_modulation_q24_csv is None
        and args.aq_color_modulation_q24_csv is None
        and args.aq_gamma_modulation_q24_csv is None
        and args.aq_final_map_q24_csv is None
        and args.dct8x8_npy is None
        and args.scaled_dct_q12_csv is None
        and args.ac_strategy_cost_q16_csv is None
        and args.var_dct_quantize_q16_csv is None
        and args.var_dct_token_fixture_dir is None
        and args.default_ac_strategy_npy is None
        and args.raw_quant_field_npy is None
        and args.libjxl_ac_strategy_npy is None
        and args.ytox_map_npy is None
        and args.ytob_map_npy is None
        and args.dct_only_raw_quant_field_npy is None
        and args.dct_only_aq_map_q24_csv is None
        and args.dct_only_ytox_map_npy is None
        and args.dct_only_ytob_map_npy is None
        and args.dct_only_quantized_ac_npy is None
        and args.dct_only_num_nonzeros_npy is None
        and args.dct_only_num_nonzeros_map_npy is None
        and args.dct_only_quant_dc_npy is None
        and args.dct_only_ac_metadata_tokens_npy is None
        and args.dct_only_dc_tokens_npy is None
        and args.dct_only_ac_tokens_npy is None
        and args.dct_only_prepared_blocks_json is None
        and args.dct_only_frame_bin is None
        and args.dct_only_codestream_bin is None
        and args.distance_params_json is None
        and args.fixed_dct_only_dc_tokens_npy is None
        and args.fixed_dct_only_ac_metadata_tokens_npy is None
        and args.fixed_dct_only_ac_tokens_npy is None
        and args.fixed_dct_only_raw_quant_field_npy is None
        and args.fixed_dct_only_ytox_map_npy is None
        and args.fixed_dct_only_ytob_map_npy is None
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
