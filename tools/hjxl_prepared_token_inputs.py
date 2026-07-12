#!/usr/bin/env python3
"""Convert prepared-token JSON fixtures into simple simulator CSV inputs."""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
import sys

SIGNED_32_MIN = -(1 << 31)
SIGNED_32_MAX = (1 << 31) - 1
SIGNED_8_MIN = -(1 << 7)
SIGNED_8_MAX = (1 << 7) - 1
UINT8_MAX = (1 << 8) - 1


def load_fixture(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as handle:
        fixture = json.load(handle)
    if not isinstance(fixture, dict):
        raise ValueError(f"{path}: fixture must be a JSON object")
    expected_format = "hjxl.fixed_dct_only_prepared_token_inputs.v1"
    if fixture.get("format") != expected_format:
        raise ValueError(f"{path}: expected format {expected_format!r}")
    validate_fixture(path, fixture)
    return fixture


def _as_object(path: Path, label: str, value: object) -> dict:
    if not isinstance(value, dict):
        raise ValueError(f"{path}: {label} must be an object")
    return value


def _as_list(path: Path, label: str, value: object) -> list:
    if not isinstance(value, list):
        raise ValueError(f"{path}: {label} must be a list")
    return value


def _as_int(path: Path, label: str, value: object) -> int:
    if isinstance(value, bool):
        raise ValueError(f"{path}: {label} must be an integer")
    if isinstance(value, float):
        raise ValueError(f"{path}: {label} must be an integer")
    try:
        return int(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{path}: {label} must be an integer") from exc


def _as_signed_32(path: Path, label: str, value: object) -> int:
    converted = _as_int(path, label, value)
    if converted < SIGNED_32_MIN or converted > SIGNED_32_MAX:
        raise ValueError(f"{path}: {label} outside signed 32-bit range")
    return converted


def _as_signed_8(path: Path, label: str, value: object) -> int:
    converted = _as_int(path, label, value)
    if converted < SIGNED_8_MIN or converted > SIGNED_8_MAX:
        raise ValueError(f"{path}: {label} outside signed 8-bit range")
    return converted


def validate_fixture(path: Path, fixture: dict) -> None:
    image = _as_object(path, "image", fixture.get("image", {}))
    x_blocks = _as_int(path, "image.x_blocks", image.get("x_blocks", 0))
    y_blocks = _as_int(path, "image.y_blocks", image.get("y_blocks", 0))
    xsize = _as_int(path, "image.xsize", image.get("xsize", x_blocks * 8))
    ysize = _as_int(path, "image.ysize", image.get("ysize", y_blocks * 8))
    if x_blocks <= 0 or y_blocks <= 0:
        raise ValueError(f"{path}: image.x_blocks and image.y_blocks must be positive")
    if xsize <= 0 or ysize <= 0:
        raise ValueError(f"{path}: image.xsize and image.ysize must be positive when present")
    if (xsize + 7) // 8 != x_blocks or (ysize + 7) // 8 != y_blocks:
        raise ValueError(f"{path}: image dimensions do not match declared 8x8 block grid")
    expected_x_tiles = (xsize + 63) // 64
    expected_y_tiles = (ysize + 63) // 64
    if (
        "x_tiles" in image
        and _as_int(path, "image.x_tiles", image["x_tiles"]) != expected_x_tiles
    ):
        raise ValueError(f"{path}: image.x_tiles does not match declared dimensions")
    if (
        "y_tiles" in image
        and _as_int(path, "image.y_tiles", image["y_tiles"]) != expected_y_tiles
    ):
        raise ValueError(f"{path}: image.y_tiles does not match declared dimensions")
    if "fixed_raw_quant" in fixture:
        fixed_raw_quant = _as_int(path, "fixed_raw_quant", fixture["fixed_raw_quant"])
        if fixed_raw_quant <= 0 or fixed_raw_quant > UINT8_MAX:
            raise ValueError(f"{path}: fixed_raw_quant outside 1..255 range")
    if "fixed_ytox" in fixture:
        _as_signed_8(path, "fixed_ytox", fixture["fixed_ytox"])
    if "fixed_ytob" in fixture:
        _as_signed_8(path, "fixed_ytob", fixture["fixed_ytob"])

    block_count = x_blocks * y_blocks
    dc_samples = _as_list(path, "dc_samples", fixture.get("dc_samples", []))
    if len(dc_samples) != block_count * 3:
        raise ValueError(
            f"{path}: expected {block_count * 3} DC samples, got {len(dc_samples)}"
        )
    for ordinal, value in enumerate(dc_samples):
        _as_signed_32(path, f"dc_samples[{ordinal}]", value)

    ac_blocks = _as_list(path, "ac_blocks", fixture.get("ac_blocks", []))
    if len(ac_blocks) != block_count:
        raise ValueError(f"{path}: expected {block_count} AC blocks, got {len(ac_blocks)}")

    seen_blocks = set()
    for expected_index, block_value in enumerate(ac_blocks):
        block = _as_object(path, f"ac_blocks[{expected_index}]", block_value)
        block_index = _as_int(
            path,
            f"AC block {expected_index} block_index",
            block.get("block_index", -1),
        )
        if block_index != expected_index:
            raise ValueError(
                f"{path}: expected AC block_index {expected_index}, got {block_index}"
            )
        if block_index in seen_blocks:
            raise ValueError(f"{path}: duplicate AC block_index {block_index}")
        seen_blocks.add(block_index)
        expected_block_x = expected_index % x_blocks
        expected_block_y = expected_index // x_blocks
        expected_tile_x = expected_block_x // 8
        expected_tile_y = expected_block_y // 8
        if (
            "block_x" in block
            and _as_int(path, f"AC block {block_index} block_x", block["block_x"])
            != expected_block_x
        ):
            raise ValueError(
                f"{path}: AC block {block_index} block_x does not match raster order"
            )
        if (
            "block_y" in block
            and _as_int(path, f"AC block {block_index} block_y", block["block_y"])
            != expected_block_y
        ):
            raise ValueError(
                f"{path}: AC block {block_index} block_y does not match raster order"
            )
        if (
            "tile_x" in block
            and _as_int(path, f"AC block {block_index} tile_x", block["tile_x"])
            != expected_tile_x
        ):
            raise ValueError(
                f"{path}: AC block {block_index} tile_x does not match 64x64 tile grid"
            )
        if (
            "tile_y" in block
            and _as_int(path, f"AC block {block_index} tile_y", block["tile_y"])
            != expected_tile_y
        ):
            raise ValueError(
                f"{path}: AC block {block_index} tile_y does not match 64x64 tile grid"
            )

        num_nonzeros = _as_list(
            path,
            f"AC block {block_index} num_nonzeros",
            block.get("num_nonzeros", []),
        )
        quantized = _as_list(
            path,
            f"AC block {block_index} quantized",
            block.get("quantized", []),
        )
        if len(num_nonzeros) != 3:
            raise ValueError(f"{path}: block {block_index} must have 3 nonzero counts")
        if len(quantized) != 3:
            raise ValueError(f"{path}: block {block_index} must have 3 quantized channels")
        for channel, (nonzeros_value, coefficients_value) in enumerate(
            zip(num_nonzeros, quantized)
        ):
            nonzeros = _as_int(
                path,
                f"AC block {block_index} channel {channel} nonzeros",
                nonzeros_value,
            )
            if nonzeros < 0 or nonzeros > 63:
                raise ValueError(
                    f"{path}: block {block_index} channel {channel} nonzeros outside 0..63"
                )
            coefficients = _as_list(
                path,
                f"AC block {block_index} channel {channel} coefficients",
                coefficients_value,
            )
            if len(coefficients) != 64:
                raise ValueError(
                    f"{path}: block {block_index} channel {channel} must have 64 coefficients"
                )
            coefficient_values = [
                _as_signed_32(
                    path,
                    f"AC block {block_index} channel {channel} coefficient {coefficient}",
                    value,
                )
                for coefficient, value in enumerate(coefficients)
            ]
            actual_nonzeros = sum(1 for value in coefficient_values[1:] if value != 0)
            if nonzeros != actual_nonzeros:
                raise ValueError(
                    f"{path}: block {block_index} channel {channel} declares {nonzeros} "
                    f"nonzeros but coefficients contain {actual_nonzeros}"
                )


def write_dc_csv(path: Path, fixture: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["ordinal", "value"])
        for ordinal, value in enumerate(fixture["dc_samples"]):
            writer.writerow([ordinal, int(value)])


def write_ac_csv(path: Path, fixture: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["block", "channel", "nonzeros", "coefficients"])
        for block in fixture["ac_blocks"]:
            block_index = int(block["block_index"])
            for channel, coefficients in enumerate(block["quantized"]):
                writer.writerow(
                    [
                        block_index,
                        channel,
                        int(block["num_nonzeros"][channel]),
                        " ".join(str(int(value)) for value in coefficients),
                    ]
                )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepared-json", type=Path, required=True, help="prepared-token JSON fixture")
    parser.add_argument("--dc-csv", type=Path, help="output DC sample CSV")
    parser.add_argument("--ac-csv", type=Path, help="output AC block/channel CSV")
    args = parser.parse_args()

    try:
        fixture = load_fixture(args.prepared_json)
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    if args.dc_csv is None and args.ac_csv is None:
        raise SystemExit("at least one output path is required")
    if args.dc_csv is not None:
        write_dc_csv(args.dc_csv, fixture)
    if args.ac_csv is not None:
        write_ac_csv(args.ac_csv, fixture)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
