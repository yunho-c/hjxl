#!/usr/bin/env python3
"""Convert prepared-token JSON fixtures into simple simulator CSV inputs."""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
import sys


def load_fixture(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as handle:
        fixture = json.load(handle)
    expected_format = "hjxl.fixed_dct_only_prepared_token_inputs.v1"
    if fixture.get("format") != expected_format:
        raise ValueError(f"{path}: expected format {expected_format!r}")
    validate_fixture(path, fixture)
    return fixture


def validate_fixture(path: Path, fixture: dict) -> None:
    image = fixture.get("image", {})
    x_blocks = int(image.get("x_blocks", 0))
    y_blocks = int(image.get("y_blocks", 0))
    if x_blocks <= 0 or y_blocks <= 0:
        raise ValueError(f"{path}: image.x_blocks and image.y_blocks must be positive")

    block_count = x_blocks * y_blocks
    dc_samples = fixture.get("dc_samples", [])
    if len(dc_samples) != block_count * 3:
        raise ValueError(
            f"{path}: expected {block_count * 3} DC samples, got {len(dc_samples)}"
        )

    ac_blocks = fixture.get("ac_blocks", [])
    if len(ac_blocks) != block_count:
        raise ValueError(f"{path}: expected {block_count} AC blocks, got {len(ac_blocks)}")

    seen_blocks = set()
    for expected_index, block in enumerate(ac_blocks):
        block_index = int(block.get("block_index", -1))
        if block_index != expected_index:
            raise ValueError(
                f"{path}: expected AC block_index {expected_index}, got {block_index}"
            )
        if block_index in seen_blocks:
            raise ValueError(f"{path}: duplicate AC block_index {block_index}")
        seen_blocks.add(block_index)

        num_nonzeros = block.get("num_nonzeros", [])
        quantized = block.get("quantized", [])
        if len(num_nonzeros) != 3:
            raise ValueError(f"{path}: block {block_index} must have 3 nonzero counts")
        if len(quantized) != 3:
            raise ValueError(f"{path}: block {block_index} must have 3 quantized channels")
        for channel, (nonzeros, coefficients) in enumerate(zip(num_nonzeros, quantized)):
            nonzeros = int(nonzeros)
            if nonzeros < 0 or nonzeros > 63:
                raise ValueError(
                    f"{path}: block {block_index} channel {channel} nonzeros outside 0..63"
                )
            if len(coefficients) != 64:
                raise ValueError(
                    f"{path}: block {block_index} channel {channel} must have 64 coefficients"
                )
            actual_nonzeros = sum(1 for value in coefficients[1:] if int(value) != 0)
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
