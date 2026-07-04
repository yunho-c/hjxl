#!/usr/bin/env python3
"""Convert prepared DCT-only block JSON fixtures into simulator CSV inputs."""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
import sys


TRACE_STAGE_QUANT_DC = 7
TRACE_STAGE_QUANTIZED_AC = 8
TRACE_STAGE_NUM_NONZEROS = 9
BLOCK_SIZE = 64


def _int_list(values: object, *, path: Path, label: str, length: int) -> list[int]:
    if not isinstance(values, list) or len(values) != length:
        raise ValueError(f"{path}: {label} must contain {length} values")
    return [int(value) for value in values]


def _int_matrix(values: object, *, path: Path, label: str) -> list[list[int]]:
    if not isinstance(values, list) or len(values) != 3:
        raise ValueError(f"{path}: {label} must contain 3 channels")
    return [
        _int_list(channel_values, path=path, label=f"{label}[{channel}]", length=BLOCK_SIZE)
        for channel, channel_values in enumerate(values)
    ]


def _coefficients(inputs: dict, *, path: Path, block_index: int) -> tuple[int, list[list[int]]]:
    if "coefficients_q" in inputs:
        fraction_bits = int(inputs.get("coefficient_fraction_bits", 0))
        if fraction_bits <= 0:
            raise ValueError(
                f"{path}: block {block_index} inputs.coefficient_fraction_bits must be positive"
            )
        return (
            fraction_bits,
            _int_matrix(
                inputs.get("coefficients_q"),
                path=path,
                label=f"block {block_index} inputs.coefficients_q",
            ),
        )
    return (
        12,
        _int_matrix(
            inputs.get("coefficients_q12"),
            path=path,
            label=f"block {block_index} inputs.coefficients_q12",
        ),
    )


def load_fixture(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as handle:
        fixture = json.load(handle)
    expected_format = "hjxl.dct_only_prepared_blocks.v1"
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
    blocks = fixture.get("blocks", [])
    if len(blocks) != block_count:
        raise ValueError(f"{path}: expected {block_count} blocks, got {len(blocks)}")

    for expected_index, block in enumerate(blocks):
        block_index = int(block.get("block_index", -1))
        if block_index != expected_index:
            raise ValueError(
                f"{path}: expected block_index {expected_index}, got {block_index}"
            )
        inputs = block.get("inputs", {})
        expected = block.get("expected", {})

        fraction_bits, coefficients = _coefficients(inputs, path=path, block_index=block_index)
        declared_fraction_bits = int(fixture.get("coefficient_fraction_bits", fraction_bits))
        if fraction_bits != declared_fraction_bits:
            raise ValueError(
                f"{path}: block {block_index} coefficient fraction bits {fraction_bits} "
                f"does not match fixture {declared_fraction_bits}"
            )
        quantized_ac = _int_matrix(
            expected.get("quantized_ac"),
            path=path,
            label=f"block {block_index} expected.quantized_ac",
        )
        del coefficients

        quant = int(inputs.get("quant", -1))
        if quant < 0 or quant > 255:
            raise ValueError(f"{path}: block {block_index} inputs.quant outside 0..255")
        for key in ("scale_q16", "inv_qac_q16", "x_qm_multiplier_q16"):
            value = int(inputs.get(key, -1))
            if value < 0:
                raise ValueError(f"{path}: block {block_index} inputs.{key} must be nonnegative")
        _int_list(
            inputs.get("inv_dc_factor_q16"),
            path=path,
            label=f"block {block_index} inputs.inv_dc_factor_q16",
            length=3,
        )
        _int_list(
            expected.get("quantized_dc"),
            path=path,
            label=f"block {block_index} expected.quantized_dc",
            length=3,
        )
        num_nonzeros = _int_list(
            expected.get("num_nonzeros"),
            path=path,
            label=f"block {block_index} expected.num_nonzeros",
            length=3,
        )
        for channel, count in enumerate(num_nonzeros):
            if count < 0 or count > 63:
                raise ValueError(
                    f"{path}: block {block_index} channel {channel} expected nonzeros outside 0..63"
                )
            actual_count = sum(1 for value in quantized_ac[channel][1:] if value != 0)
            if count != actual_count:
                raise ValueError(
                    f"{path}: block {block_index} channel {channel} declares {count} "
                    f"nonzeros but expected coefficients contain {actual_count}"
                )


def write_input_csv(path: Path, fixture: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(
            [
                "block",
                "quant",
                "scale_q16",
                "inv_qac_q16",
                "inv_dc_factor_q16",
                "x_qm_multiplier_q16",
                "ytox",
                "ytob",
                "coefficients_x",
                "coefficients_y",
                "coefficients_b",
            ]
        )
        for block in fixture["blocks"]:
            inputs = block["inputs"]
            _, coefficients = _coefficients(
                inputs,
                path=path,
                block_index=int(block["block_index"]),
            )
            writer.writerow(
                [
                    int(block["block_index"]),
                    int(inputs["quant"]),
                    int(inputs["scale_q16"]),
                    int(inputs["inv_qac_q16"]),
                    " ".join(str(int(value)) for value in inputs["inv_dc_factor_q16"]),
                    int(inputs["x_qm_multiplier_q16"]),
                    int(inputs["ytox"]),
                    int(inputs["ytob"]),
                    " ".join(str(int(value)) for value in coefficients[0]),
                    " ".join(str(int(value)) for value in coefficients[1]),
                    " ".join(str(int(value)) for value in coefficients[2]),
                ]
            )


def write_expected_trace_csv(path: Path, fixture: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["stage", "group", "index", "value"])
        for block in fixture["blocks"]:
            block_index = int(block["block_index"])
            expected = block["expected"]
            for channel, coefficients in enumerate(expected["quantized_ac"]):
                for coefficient_index, value in enumerate(coefficients):
                    writer.writerow(
                        [
                            TRACE_STAGE_QUANTIZED_AC,
                            block_index,
                            channel * BLOCK_SIZE + coefficient_index,
                            int(value),
                        ]
                    )
            for channel, value in enumerate(expected["quantized_dc"]):
                writer.writerow([TRACE_STAGE_QUANT_DC, block_index, channel, int(value)])
            for channel, value in enumerate(expected["num_nonzeros"]):
                writer.writerow([TRACE_STAGE_NUM_NONZEROS, block_index, channel, int(value)])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepared-json", type=Path, required=True, help="prepared-block JSON fixture")
    parser.add_argument("--input-csv", type=Path, help="output prepared block input CSV")
    parser.add_argument("--expected-trace-csv", type=Path, help="output expected quantization trace CSV")
    args = parser.parse_args()

    try:
        fixture = load_fixture(args.prepared_json)
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    if args.input_csv is None and args.expected_trace_csv is None:
        raise SystemExit("at least one output path is required")
    if args.input_csv is not None:
        write_input_csv(args.input_csv, fixture)
    if args.expected_trace_csv is not None:
        write_expected_trace_csv(args.expected_trace_csv, fixture)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
