#!/usr/bin/env python3
"""Small libjxl-tiny oracle helper for HJXL development.

This script keeps reference exploration out of the RTL code. It can generate a
deterministic linear-RGB fixture and optionally encode it with libjxl-tiny's
educational Python port.
"""

from __future__ import annotations

import argparse
import csv
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
