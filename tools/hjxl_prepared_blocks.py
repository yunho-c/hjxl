#!/usr/bin/env python3
"""Convert prepared DCT-only block JSON fixtures into simulator and stream CSV inputs."""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
import sys

from hjxl_abi_generated import (
    PREPARED_DCT_COEFFICIENTS_PER_CHANNEL,
    PREPARED_DCT_SCALAR_WORDS,
    PREPARED_DCT_WORDS_PER_BLOCK,
    REGISTER_DISTANCE_Q8,
    REGISTER_FIXED_INV_QAC_Q16,
    REGISTER_FIXED_POINT_SCALE,
    REGISTER_FIXED_RAW_QUANT,
    REGISTER_FIXED_YTOB,
    REGISTER_FIXED_YTOX,
    REGISTER_FLAGS,
    REGISTER_XSIZE,
    REGISTER_YSIZE,
    TOKEN_SELECT,
    TRACE_STAGES,
)

TRACE_STAGE_QUANT_DC = TRACE_STAGES["quant-dc"]
TRACE_STAGE_QUANTIZED_AC = TRACE_STAGES["quantized-ac"]
TRACE_STAGE_NUM_NONZEROS = TRACE_STAGES["num-nonzeros"]
BLOCK_SIZE = PREPARED_DCT_COEFFICIENTS_PER_CHANNEL
UINT8_MAX = (1 << 8) - 1
UINT16_MAX = (1 << 16) - 1
UINT32_MAX = (1 << 32) - 1
SINT8_MIN = -(1 << 7)
SINT8_MAX = (1 << 7) - 1
SINT32_MIN = -(1 << 31)
SINT32_MAX = (1 << 31) - 1
PREPARED_TRACE_ROUTE = {
    "name": "prepared-dct-quantize-token",
    "stage": None,
    "focused": True,
}
PREPARED_TARGETS = {
    "direct": {
        "interface": "prepared_dct_axi_stream",
        "variant": "direct",
        "stream_shell": "HjxlPreparedDctAxiStreamCore",
        "controlled_shell": "HjxlPreparedDctAxiLiteStreamCore",
        "kv260_top": "HjxlKv260PreparedDctTop",
        "input_stream": "prepared DCT-only block words",
        "input_keep_enforced": 1,
    },
    "estimated-cfl": {
        "interface": "prepared_dct_axi_stream",
        "variant": "estimated-cfl",
        "stream_shell": "HjxlPreparedCflDctAxiStreamCore",
        "controlled_shell": "HjxlPreparedCflDctAxiLiteStreamCore",
        "kv260_top": "HjxlKv260PreparedCflDctTop",
        "input_stream": "prepared DCT-only block words with internally estimated CFL maps",
        "input_keep_enforced": 1,
    },
}
CONFIG_REGISTER_KEYS = (
    ("xsize", "xsize"),
    ("ysize", "ysize"),
    ("distance_q8", "distance_q8"),
    ("fixed_point_scale", "fixed_point_scale"),
    ("fixed_inv_qac_q16", "fixed_inv_qac_q16"),
    ("fixed_raw_quant", "fixed_raw_quant"),
    ("flags", "flags"),
    ("fixed_ytox", "fixed_ytox"),
    ("fixed_ytob", "fixed_ytob"),
)


def _u32(value: int) -> int:
    return int(value) & 0xFFFFFFFF


def _parse_int(value: str | None, *, path: Path, line: int, field: str) -> int:
    if value is None:
        raise ValueError(f"{path}:{line}: {field} is required")
    stripped = value.strip()
    if not stripped:
        raise ValueError(f"{path}:{line}: {field} is required")
    try:
        return int(stripped, 0)
    except ValueError as exc:
        raise ValueError(
            f"{path}:{line}: {field} must be an integer, got {value!r}"
        ) from exc


def _arg_int(value: str) -> int:
    try:
        return int(value, 0)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(f"must be an integer, got {value!r}") from exc


def _json_int(value: object, *, path: Path, field: str) -> int:
    if value is None:
        raise ValueError(f"{path}: {field} is required")
    if isinstance(value, bool):
        raise ValueError(f"{path}: {field} must be an integer, got {value!r}")
    if isinstance(value, float):
        raise ValueError(f"{path}: {field} must be an integer, got {value!r}")
    try:
        if isinstance(value, str):
            return int(value.strip(), 0)
        return int(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{path}: {field} must be an integer, got {value!r}") from exc


def prepared_target_metadata(variant: str) -> dict:
    if variant not in PREPARED_TARGETS:
        raise ValueError(f"unsupported target variant {variant!r}")
    return dict(PREPARED_TARGETS[variant])


def validate_target_metadata(path: Path, manifest: dict) -> None:
    target = manifest.get("target", {})
    if target is None:
        target = {}
    if not isinstance(target, dict):
        raise ValueError(f"{path}: target must be an object")
    variant = str(target.get("variant", "direct"))
    expected = prepared_target_metadata(variant)
    for key, expected_value in expected.items():
        if key in target and target[key] != expected_value:
            raise ValueError(f"{path}: target.{key} does not match target.variant")


def _int_list(values: object, *, path: Path, label: str, length: int) -> list[int]:
    if not isinstance(values, list) or len(values) != length:
        raise ValueError(f"{path}: {label} must contain {length} values")
    return [
        _json_int(value, path=path, field=f"{label}[{index}]")
        for index, value in enumerate(values)
    ]


def _int_matrix(values: object, *, path: Path, label: str) -> list[list[int]]:
    if not isinstance(values, list) or len(values) != 3:
        raise ValueError(f"{path}: {label} must contain 3 channels")
    return [
        _int_list(
            channel_values,
            path=path,
            label=f"{label}[{channel}]",
            length=BLOCK_SIZE,
        )
        for channel, channel_values in enumerate(values)
    ]


def _check_range(
    value: int,
    *,
    path: Path,
    field: str,
    minimum: int,
    maximum: int,
    range_name: str,
) -> None:
    if value < minimum or value > maximum:
        raise ValueError(f"{path}: {field} outside {range_name} range")


def _check_matrix_range(
    values: list[list[int]],
    *,
    path: Path,
    label: str,
    minimum: int,
    maximum: int,
    range_name: str,
) -> None:
    for channel, channel_values in enumerate(values):
        for index, value in enumerate(channel_values):
            _check_range(
                value,
                path=path,
                field=f"{label}[{channel}][{index}]",
                minimum=minimum,
                maximum=maximum,
                range_name=range_name,
            )


def _coefficients(
    inputs: dict,
    *,
    path: Path,
    block_index: int,
) -> tuple[int, list[list[int]]]:
    if "coefficients_q" in inputs:
        fraction_bits = _json_int(
            inputs.get("coefficient_fraction_bits", 0),
            path=path,
            field=f"block {block_index} inputs.coefficient_fraction_bits",
        )
        if fraction_bits <= 0:
            raise ValueError(
                f"{path}: block {block_index} inputs.coefficient_fraction_bits must be positive"
            )
        coefficients = _int_matrix(
            inputs.get("coefficients_q"),
            path=path,
            label=f"block {block_index} inputs.coefficients_q",
        )
        _check_matrix_range(
            coefficients,
            path=path,
            label=f"block {block_index} inputs.coefficients_q",
            minimum=SINT32_MIN,
            maximum=SINT32_MAX,
            range_name="signed 32-bit",
        )
        return (fraction_bits, coefficients)
    coefficients = _int_matrix(
        inputs.get("coefficients_q12"),
        path=path,
        label=f"block {block_index} inputs.coefficients_q12",
    )
    _check_matrix_range(
        coefficients,
        path=path,
        label=f"block {block_index} inputs.coefficients_q12",
        minimum=SINT32_MIN,
        maximum=SINT32_MAX,
        range_name="signed 32-bit",
    )
    return (12, coefficients)


def load_fixture(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as handle:
        fixture = json.load(handle)
    expected_format = "hjxl.dct_only_prepared_blocks.v1"
    if fixture.get("format") != expected_format:
        raise ValueError(f"{path}: expected format {expected_format!r}")
    validate_fixture(path, fixture)
    return fixture


def image_dimensions(fixture: dict, *, path: Path) -> tuple[int, int, int, int]:
    image = fixture.get("image", {})
    x_blocks = _json_int(image.get("x_blocks", 0), path=path, field="image.x_blocks")
    y_blocks = _json_int(image.get("y_blocks", 0), path=path, field="image.y_blocks")
    xsize = _json_int(image.get("xsize", x_blocks * 8), path=path, field="image.xsize")
    ysize = _json_int(image.get("ysize", y_blocks * 8), path=path, field="image.ysize")
    return xsize, ysize, x_blocks, y_blocks


def coefficient_fraction_bits(fixture: dict, *, path: Path) -> int:
    blocks = fixture.get("blocks", [])
    if not blocks:
        raise ValueError(f"{path}: fixture must contain at least one block")
    fraction_bits, _ = _coefficients(
        blocks[0].get("inputs", {}),
        path=path,
        block_index=0,
    )
    return fraction_bits


def validate_fixture(path: Path, fixture: dict) -> None:
    xsize, ysize, x_blocks, y_blocks = image_dimensions(fixture, path=path)
    image = fixture.get("image", {})
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
        and _json_int(image["x_tiles"], path=path, field="image.x_tiles") != expected_x_tiles
    ):
        raise ValueError(f"{path}: image.x_tiles does not match declared dimensions")
    if (
        "y_tiles" in image
        and _json_int(image["y_tiles"], path=path, field="image.y_tiles") != expected_y_tiles
    ):
        raise ValueError(f"{path}: image.y_tiles does not match declared dimensions")

    block_count = x_blocks * y_blocks
    blocks = fixture.get("blocks", [])
    if len(blocks) != block_count:
        raise ValueError(f"{path}: expected {block_count} blocks, got {len(blocks)}")

    for expected_index, block in enumerate(blocks):
        block_index = _json_int(
            block.get("block_index", -1),
            path=path,
            field=f"block {expected_index} block_index",
        )
        if block_index != expected_index:
            raise ValueError(
                f"{path}: expected block_index {expected_index}, got {block_index}"
            )
        expected_block_x = expected_index % x_blocks
        expected_block_y = expected_index // x_blocks
        expected_tile_x = expected_block_x // 8
        expected_tile_y = expected_block_y // 8
        if (
            "block_x" in block
            and _json_int(
                block["block_x"],
                path=path,
                field=f"block {block_index} block_x",
            )
            != expected_block_x
        ):
            raise ValueError(
                f"{path}: block {block_index} block_x does not match raster order"
            )
        if (
            "block_y" in block
            and _json_int(
                block["block_y"],
                path=path,
                field=f"block {block_index} block_y",
            )
            != expected_block_y
        ):
            raise ValueError(
                f"{path}: block {block_index} block_y does not match raster order"
            )
        if (
            "tile_x" in block
            and _json_int(
                block["tile_x"],
                path=path,
                field=f"block {block_index} tile_x",
            )
            != expected_tile_x
        ):
            raise ValueError(
                f"{path}: block {block_index} tile_x does not match 64x64 tile grid"
            )
        if (
            "tile_y" in block
            and _json_int(
                block["tile_y"],
                path=path,
                field=f"block {block_index} tile_y",
            )
            != expected_tile_y
        ):
            raise ValueError(
                f"{path}: block {block_index} tile_y does not match 64x64 tile grid"
            )
        inputs = block.get("inputs", {})
        expected = block.get("expected", {})

        fraction_bits, coefficients = _coefficients(
            inputs,
            path=path,
            block_index=block_index,
        )
        declared_fraction_bits = _json_int(
            fixture.get("coefficient_fraction_bits", fraction_bits),
            path=path,
            field="coefficient_fraction_bits",
        )
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
        _check_matrix_range(
            quantized_ac,
            path=path,
            label=f"block {block_index} expected.quantized_ac",
            minimum=SINT32_MIN,
            maximum=SINT32_MAX,
            range_name="signed 32-bit",
        )
        del coefficients

        quant = _json_int(
            inputs.get("quant", -1),
            path=path,
            field=f"block {block_index} inputs.quant",
        )
        _check_range(
            quant,
            path=path,
            field=f"block {block_index} inputs.quant",
            minimum=0,
            maximum=UINT8_MAX,
            range_name="uint8",
        )
        for key, maximum, range_name in (
            ("scale_q16", UINT16_MAX, "uint16"),
            ("inv_qac_q16", UINT32_MAX, "uint32"),
            ("x_qm_multiplier_q16", UINT32_MAX, "uint32"),
        ):
            value = _json_int(
                inputs.get(key, -1),
                path=path,
                field=f"block {block_index} inputs.{key}",
            )
            _check_range(
                value,
                path=path,
                field=f"block {block_index} inputs.{key}",
                minimum=0,
                maximum=maximum,
                range_name=range_name,
            )
        for key in ("ytox", "ytob"):
            value = _json_int(
                inputs.get(key),
                path=path,
                field=f"block {block_index} inputs.{key}",
            )
            _check_range(
                value,
                path=path,
                field=f"block {block_index} inputs.{key}",
                minimum=SINT8_MIN,
                maximum=SINT8_MAX,
                range_name="signed 8-bit",
            )
        inv_dc_factor = _int_list(
            inputs.get("inv_dc_factor_q16"),
            path=path,
            label=f"block {block_index} inputs.inv_dc_factor_q16",
            length=3,
        )
        for channel, value in enumerate(inv_dc_factor):
            _check_range(
                value,
                path=path,
                field=f"block {block_index} inputs.inv_dc_factor_q16[{channel}]",
                minimum=0,
                maximum=UINT32_MAX,
                range_name="uint32",
            )
        quantized_dc = _int_list(
            expected.get("quantized_dc"),
            path=path,
            label=f"block {block_index} expected.quantized_dc",
            length=3,
        )
        for channel, value in enumerate(quantized_dc):
            _check_range(
                value,
                path=path,
                field=f"block {block_index} expected.quantized_dc[{channel}]",
                minimum=SINT32_MIN,
                maximum=SINT32_MAX,
                range_name="signed 32-bit",
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


def prepared_block_stream_words(block: dict, *, path: Path) -> list[int]:
    inputs = block["inputs"]
    _, coefficients = _coefficients(
        inputs,
        path=path,
        block_index=int(block["block_index"]),
    )
    words = [
        int(inputs["quant"]),
        int(inputs["scale_q16"]),
        int(inputs["inv_qac_q16"]),
        int(inputs["inv_dc_factor_q16"][0]),
        int(inputs["inv_dc_factor_q16"][1]),
        int(inputs["inv_dc_factor_q16"][2]),
        int(inputs["x_qm_multiplier_q16"]),
        _u32(int(inputs["ytox"])),
        _u32(int(inputs["ytob"])),
    ]
    words.extend(_u32(value) for channel in coefficients for value in channel)
    if len(words) != PREPARED_DCT_WORDS_PER_BLOCK:
        raise ValueError(f"{path}: internal error: prepared block stream has {len(words)} words")
    return [_u32(word) for word in words]


def prepared_stream_words(fixture: dict, *, path: Path) -> list[int]:
    words: list[int] = []
    for block in fixture["blocks"]:
        words.extend(prepared_block_stream_words(block, path=path))
    return words


def write_input_stream_csv(path: Path, fixture: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    total_words = len(fixture["blocks"]) * PREPARED_DCT_WORDS_PER_BLOCK
    word_index = 0
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["data", "last"])
        for block in fixture["blocks"]:
            for word in prepared_block_stream_words(block, path=path):
                word_index += 1
                writer.writerow([word, int(word_index == total_words)])


def flags_word(
    *,
    enable_xyb: bool,
    enable_dct: bool,
    enable_quant: bool,
    enable_tokenize: bool,
    token_select: int,
) -> int:
    return (
        int(enable_xyb)
        | (int(enable_dct) << 1)
        | (int(enable_quant) << 2)
        | (int(enable_tokenize) << 3)
        | (token_select << 8)
    )


def write_axi_lite_csv(
    path: Path,
    *,
    xsize: int,
    ysize: int,
    distance_q8: int,
    fixed_point_scale: int,
    fixed_inv_qac_q16: int,
    fixed_raw_quant: int,
    flags: int,
    fixed_ytox: int,
    fixed_ytob: int,
) -> None:
    rows = [
        (REGISTER_XSIZE, xsize),
        (REGISTER_YSIZE, ysize),
        (REGISTER_DISTANCE_Q8, distance_q8),
        (REGISTER_FIXED_POINT_SCALE, fixed_point_scale),
        (REGISTER_FIXED_INV_QAC_Q16, fixed_inv_qac_q16),
        (REGISTER_FIXED_RAW_QUANT, fixed_raw_quant),
        (REGISTER_FLAGS, flags),
        (REGISTER_FIXED_YTOX, fixed_ytox),
        (REGISTER_FIXED_YTOB, fixed_ytob),
    ]
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["address", "data", "strb"])
        for address, data in rows:
            writer.writerow([address, data & 0xFFFFFFFF, 0xF])


def write_manifest_json(
    path: Path,
    *,
    prepared_json: Path,
    input_stream_csv: Path,
    axi_lite_csv: Path | None,
    fixture: dict,
    distance_q8: int,
    fixed_point_scale: int,
    fixed_inv_qac_q16: int,
    fixed_raw_quant: int,
    flags: int,
    fixed_ytox: int,
    fixed_ytob: int,
    token_select_name: str,
    target_variant: str,
) -> None:
    xsize, ysize, x_blocks, y_blocks = image_dimensions(fixture, path=prepared_json)
    x_tiles = (xsize + 63) // 64
    y_tiles = (ysize + 63) // 64
    block_count = len(fixture["blocks"])
    fraction_bits = coefficient_fraction_bits(fixture, path=prepared_json)
    register_map = {
        "status_control": 0x00,
        "xsize": REGISTER_XSIZE,
        "ysize": REGISTER_YSIZE,
        "distance_q8": REGISTER_DISTANCE_Q8,
        "fixed_point_scale": REGISTER_FIXED_POINT_SCALE,
        "fixed_inv_qac_q16": REGISTER_FIXED_INV_QAC_Q16,
        "fixed_raw_quant": REGISTER_FIXED_RAW_QUANT,
        "flags": REGISTER_FLAGS,
        "fixed_ytox": REGISTER_FIXED_YTOX,
        "fixed_ytob": REGISTER_FIXED_YTOB,
    }
    manifest = {
        "format": "hjxl.prepared_dct_stream_manifest.v1",
        "source": {"prepared_json": str(prepared_json)},
        "target": prepared_target_metadata(target_variant),
        "trace_route": dict(PREPARED_TRACE_ROUTE),
        "image": {
            "xsize": xsize,
            "ysize": ysize,
            "x_blocks": x_blocks,
            "y_blocks": y_blocks,
            "x_tiles": x_tiles,
            "y_tiles": y_tiles,
        },
        "stream": {
            "csv": str(input_stream_csv),
            "columns": ["data", "last"],
            "word_count": block_count * PREPARED_DCT_WORDS_PER_BLOCK,
            "block_count": block_count,
            "words_per_block": PREPARED_DCT_WORDS_PER_BLOCK,
            "scalar_words_per_block": PREPARED_DCT_SCALAR_WORDS,
            "coefficient_words_per_block": 3 * BLOCK_SIZE,
            "coefficient_fraction_bits": fraction_bits,
            "layout": [
                "quant",
                "scale_q16",
                "inv_qac_q16",
                "inv_dc_factor_q16[0]",
                "inv_dc_factor_q16[1]",
                "inv_dc_factor_q16[2]",
                "x_qm_multiplier_q16",
                "ytox",
                "ytob",
                "coefficients_x[64]",
                "coefficients_y[64]",
                "coefficients_b[64]",
            ],
            "last": "asserted only on the final prepared-DCT word",
        },
        "axi_lite": {
            "csv": str(axi_lite_csv) if axi_lite_csv is not None else None,
            "columns": ["address", "data", "strb"],
            "register_map": register_map,
            "status_control_bits": {
                "protocol_error": 0,
                "busy": 1,
                "overflow": 2,
                "unsupported_distance": 3,
                "clear_protocol_error_write_bit": 0,
            },
            "config": {
                "xsize": xsize,
                "ysize": ysize,
                "distance_q8": distance_q8,
                "fixed_point_scale": fixed_point_scale,
                "fixed_inv_qac_q16": fixed_inv_qac_q16,
                "fixed_raw_quant": fixed_raw_quant,
                "flags": flags,
                "fixed_ytox": fixed_ytox,
                "fixed_ytob": fixed_ytob,
                "token_select": token_select_name,
            },
        },
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2, sort_keys=True)
        handle.write("\n")


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


def _manifest_path(manifest_path: Path, raw_path: str | None) -> Path | None:
    if raw_path is None:
        return None
    path = Path(raw_path)
    if path.is_absolute():
        return path
    manifest_relative = manifest_path.parent / path
    if manifest_relative.exists():
        return manifest_relative
    if path.exists():
        return path
    return manifest_relative


def _csv_bool(value: str | None, *, path: Path, line: int, field: str) -> bool:
    if value is None:
        raise ValueError(f"{path}:{line}: {field} is required")
    stripped = value.strip().lower()
    if not stripped:
        raise ValueError(f"{path}:{line}: {field} is required")
    if stripped in {"1", "true", "t", "yes", "y"}:
        return True
    if stripped in {"0", "false", "f", "no", "n"}:
        return False
    raise ValueError(f"{path}:{line}: {field} must be boolean, got {value!r}")


def validate_stream_csv(path: Path, expected_word_count: int) -> list[tuple[int, bool]]:
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames != ["data", "last"]:
            raise ValueError(f"{path}: expected data,last columns")
        rows = list(reader)
    if len(rows) != expected_word_count:
        raise ValueError(f"{path}: expected {expected_word_count} stream rows, got {len(rows)}")
    if not rows:
        raise ValueError(f"{path}: stream CSV must contain at least one row")
    parsed_rows: list[tuple[int, bool]] = []
    for index, row in enumerate(rows):
        line = index + 2
        last = _csv_bool(row["last"], path=path, line=line, field="last")
        if index == len(rows) - 1:
            if not last:
                raise ValueError(f"{path}: final stream row does not assert last")
        elif last:
            raise ValueError(f"{path}: last asserted before final row at index {index}")
        data = _parse_int(row["data"], path=path, line=line, field="data")
        if data < 0 or data > 0xFFFFFFFF:
            raise ValueError(f"{path}: stream row {index} data does not fit in uint32_t")
        parsed_rows.append((data, last))
    return parsed_rows


def validate_axi_lite_csv(path: Path, manifest: dict) -> None:
    axi_lite = manifest["axi_lite"]
    register_map = axi_lite["register_map"]
    config = axi_lite["config"]
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames != ["address", "data", "strb"]:
            raise ValueError(f"{path}: expected address,data,strb columns")
        writes = {}
        for line, row in enumerate(reader, start=2):
            address = _parse_int(
                row["address"], path=path, line=line, field="address"
            )
            data = _parse_int(row["data"], path=path, line=line, field="data")
            strb = _parse_int(row["strb"], path=path, line=line, field="strb")
            writes[address] = (data, strb)
    for register_key, config_key in CONFIG_REGISTER_KEYS:
        address = int(register_map[register_key])
        if address not in writes:
            raise ValueError(f"{path}: missing write for {register_key} at address {address}")
        data, strobe = writes[address]
        if strobe != 0xF:
            raise ValueError(f"{path}: {register_key} strobe must be 0xf, got {strobe:#x}")
        expected = int(config[config_key]) & 0xFFFFFFFF
        if data != expected:
            raise ValueError(f"{path}: {register_key} expected {expected}, got {data}")


def validate_manifest_json(path: Path) -> None:
    with path.open("r", encoding="utf-8") as handle:
        manifest = json.load(handle)
    if manifest.get("format") != "hjxl.prepared_dct_stream_manifest.v1":
        raise ValueError(f"{path}: unsupported manifest format {manifest.get('format')!r}")
    trace_route = manifest.get("trace_route", PREPARED_TRACE_ROUTE)
    if trace_route != PREPARED_TRACE_ROUTE:
        raise ValueError(f"{path}: trace_route must describe the prepared-DCT quantize-to-token route")
    validate_target_metadata(path, manifest)
    image = manifest["image"]
    stream = manifest["stream"]
    xsize = _json_int(image.get("xsize"), path=path, field="image.xsize")
    ysize = _json_int(image.get("ysize"), path=path, field="image.ysize")
    x_blocks = _json_int(image.get("x_blocks"), path=path, field="image.x_blocks")
    y_blocks = _json_int(image.get("y_blocks"), path=path, field="image.y_blocks")
    if xsize <= 0 or ysize <= 0:
        raise ValueError(f"{path}: image dimensions must be positive")
    if x_blocks <= 0 or y_blocks <= 0:
        raise ValueError(f"{path}: image block grid must be positive")
    expected_blocks = x_blocks * y_blocks
    expected_word_count = expected_blocks * PREPARED_DCT_WORDS_PER_BLOCK
    x_tiles = _json_int(
        image.get("x_tiles", (xsize + 63) // 64),
        path=path,
        field="image.x_tiles",
    )
    y_tiles = _json_int(
        image.get("y_tiles", (ysize + 63) // 64),
        path=path,
        field="image.y_tiles",
    )
    if x_tiles <= 0 or y_tiles <= 0:
        raise ValueError(f"{path}: image tile grid must be positive")
    if x_tiles * 64 < xsize or x_tiles * 64 >= xsize + 64:
        raise ValueError(f"{path}: image x tile grid does not cover xsize")
    if y_tiles * 64 < ysize or y_tiles * 64 >= ysize + 64:
        raise ValueError(f"{path}: image y tile grid does not cover ysize")
    if _json_int(stream.get("block_count"), path=path, field="stream.block_count") != expected_blocks:
        raise ValueError(f"{path}: stream block_count {stream['block_count']} does not match image grid")
    if _json_int(stream.get("word_count"), path=path, field="stream.word_count") != expected_word_count:
        raise ValueError(
            f"{path}: stream word_count {stream['word_count']} does not match image block grid"
        )
    manifest_fraction_bits = stream.get("coefficient_fraction_bits")
    if manifest_fraction_bits is not None:
        fraction_bits = _json_int(
            manifest_fraction_bits,
            path=path,
            field="stream.coefficient_fraction_bits",
        )
        if fraction_bits <= 0:
            raise ValueError(f"{path}: stream.coefficient_fraction_bits must be positive")
    stream_csv = _manifest_path(path, stream["csv"])
    if stream_csv is None:
        raise ValueError(f"{path}: missing stream CSV path")
    stream_rows = validate_stream_csv(stream_csv, expected_word_count)
    axi_lite_csv = _manifest_path(path, manifest["axi_lite"]["csv"])
    if axi_lite_csv is not None:
        validate_axi_lite_csv(axi_lite_csv, manifest)

    source_json = _manifest_path(path, manifest.get("source", {}).get("prepared_json"))
    if source_json is not None and source_json.exists():
        fixture = load_fixture(source_json)
        source_xsize, source_ysize, source_x_blocks, source_y_blocks = image_dimensions(
            fixture, path=source_json
        )
        source_fraction_bits = coefficient_fraction_bits(fixture, path=source_json)
        if (
            xsize != source_xsize
            or ysize != source_ysize
            or x_blocks != source_x_blocks
            or y_blocks != source_y_blocks
            or expected_blocks != len(fixture["blocks"])
        ):
            raise ValueError(f"{path}: manifest image metadata does not match source prepared JSON")
        source_image = fixture.get("image", {})
        if (
            "x_tiles" in source_image
            and _json_int(image.get("x_tiles", x_tiles), path=path, field="image.x_tiles")
            != _json_int(source_image["x_tiles"], path=source_json, field="image.x_tiles")
        ) or (
            "y_tiles" in source_image
            and _json_int(image.get("y_tiles", y_tiles), path=path, field="image.y_tiles")
            != _json_int(source_image["y_tiles"], path=source_json, field="image.y_tiles")
        ):
            raise ValueError(f"{path}: manifest tile metadata does not match source prepared JSON")
        if manifest_fraction_bits is not None and fraction_bits != source_fraction_bits:
            raise ValueError(
                f"{path}: stream coefficient_fraction_bits {manifest_fraction_bits} "
                f"does not match source prepared JSON value {source_fraction_bits}"
            )
        expected_words = prepared_stream_words(fixture, path=source_json)
        actual_words = [data for data, _ in stream_rows]
        if actual_words != expected_words:
            mismatch = next(
                index
                for index, (actual, expected) in enumerate(zip(actual_words, expected_words))
                if actual != expected
            )
            raise ValueError(
                f"{stream_csv}: stream row {mismatch} data {actual_words[mismatch]} "
                f"does not match source prepared JSON value {expected_words[mismatch]}"
            )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepared-json", type=Path, help="prepared-block JSON fixture")
    parser.add_argument("--input-csv", type=Path, help="output prepared block input CSV")
    parser.add_argument(
        "--input-stream-csv",
        type=Path,
        help="output prepared block AXI-stream-style data,last CSV",
    )
    parser.add_argument("--expected-trace-csv", type=Path, help="output expected quantization trace CSV")
    parser.add_argument(
        "--axi-lite-csv",
        type=Path,
        help="optional AXI-Lite register write CSV with address,data,strb columns",
    )
    parser.add_argument(
        "--manifest-json",
        type=Path,
        help="optional JSON manifest describing generated stream/control artifacts",
    )
    parser.add_argument(
        "--validate-manifest",
        type=Path,
        help="validate an existing prepared-DCT stream manifest and its referenced CSV artifacts",
    )
    parser.add_argument("--distance-q8", type=int, default=256, help="FrameConfig distanceQ8 register value")
    parser.add_argument("--fixed-point-scale", type=int, default=0, help="FrameConfig fixedPointScale")
    parser.add_argument("--fixed-inv-qac-q16", type=int, default=0, help="FrameConfig fixedInvQacQ16")
    parser.add_argument("--fixed-raw-quant", type=int, default=0, help="FrameConfig fixedRawQuant")
    parser.add_argument("--fixed-ytox", type=_arg_int, default=0, help="FrameConfig fixedYtox signed 8-bit CFL override")
    parser.add_argument("--fixed-ytob", type=_arg_int, default=0, help="FrameConfig fixedYtob signed 8-bit CFL override")
    parser.add_argument("--enable-xyb", action="store_true", help="set FrameConfig enableXyb")
    parser.add_argument("--disable-dct", action="store_true", help="clear FrameConfig enableDct")
    parser.add_argument("--disable-quant", action="store_true", help="clear FrameConfig enableQuant")
    parser.add_argument("--disable-tokenize", action="store_true", help="clear FrameConfig enableTokenize")
    parser.add_argument(
        "--token-select",
        choices=tuple(TOKEN_SELECT),
        default="ac-tokens",
        help="FrameConfig tokenSelect field used when tokenization is enabled",
    )
    parser.add_argument(
        "--flags",
        type=_arg_int,
        help="override packed FrameConfig flags word; default is the prepared-DCT token path",
    )
    parser.add_argument(
        "--target-variant",
        choices=tuple(PREPARED_TARGETS),
        default="direct",
        help="prepared stream target metadata to record in generated manifests",
    )
    args = parser.parse_args()

    if args.validate_manifest is not None and args.prepared_json is None:
        try:
            validate_manifest_json(args.validate_manifest)
        except ValueError as exc:
            raise SystemExit(f"error: {exc}") from exc
        print(f"validated {args.validate_manifest}")
        return 0
    if args.prepared_json is None:
        raise SystemExit("--prepared-json is required unless --validate-manifest is used alone")

    try:
        fixture = load_fixture(args.prepared_json)
    except (OSError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    if (
        args.input_csv is None
        and args.input_stream_csv is None
        and args.expected_trace_csv is None
        and args.axi_lite_csv is None
        and args.manifest_json is None
    ):
        raise SystemExit("at least one output path is required")
    if args.manifest_json is not None and args.input_stream_csv is None:
        raise SystemExit("--manifest-json requires --input-stream-csv")
    for name, value in (
        ("--distance-q8", args.distance_q8),
        ("--fixed-point-scale", args.fixed_point_scale),
        ("--fixed-inv-qac-q16", args.fixed_inv_qac_q16),
        ("--fixed-raw-quant", args.fixed_raw_quant),
    ):
        if value < 0:
            raise SystemExit(f"{name} must be non-negative")
        if value > UINT32_MAX:
            raise SystemExit(f"{name} must fit in uint32")
    if args.fixed_raw_quant > UINT8_MAX:
        raise SystemExit("--fixed-raw-quant must fit in uint8")
    for name, value in (("--fixed-ytox", args.fixed_ytox), ("--fixed-ytob", args.fixed_ytob)):
        if value < SINT8_MIN or value > SINT8_MAX:
            raise SystemExit(f"{name} must fit in signed 8-bit")
    if args.flags is not None and (args.flags < 0 or args.flags > UINT32_MAX):
        raise SystemExit("--flags must fit in uint32")

    xsize, ysize, _, _ = image_dimensions(fixture, path=args.prepared_json)
    flags = (
        args.flags
        if args.flags is not None
        else flags_word(
            enable_xyb=args.enable_xyb,
            enable_dct=not args.disable_dct,
            enable_quant=not args.disable_quant,
            enable_tokenize=not args.disable_tokenize,
            token_select=TOKEN_SELECT[args.token_select],
        )
    )

    if args.input_csv is not None:
        write_input_csv(args.input_csv, fixture)
    if args.input_stream_csv is not None:
        write_input_stream_csv(args.input_stream_csv, fixture)
    if args.expected_trace_csv is not None:
        write_expected_trace_csv(args.expected_trace_csv, fixture)
    if args.axi_lite_csv is not None:
        write_axi_lite_csv(
            args.axi_lite_csv,
            xsize=xsize,
            ysize=ysize,
            distance_q8=args.distance_q8,
            fixed_point_scale=args.fixed_point_scale,
            fixed_inv_qac_q16=args.fixed_inv_qac_q16,
            fixed_raw_quant=args.fixed_raw_quant,
            flags=flags,
            fixed_ytox=args.fixed_ytox,
            fixed_ytob=args.fixed_ytob,
        )
    if args.manifest_json is not None:
        write_manifest_json(
            args.manifest_json,
            prepared_json=args.prepared_json,
            input_stream_csv=args.input_stream_csv,
            axi_lite_csv=args.axi_lite_csv,
            fixture=fixture,
            distance_q8=args.distance_q8,
            fixed_point_scale=args.fixed_point_scale,
            fixed_inv_qac_q16=args.fixed_inv_qac_q16,
            fixed_raw_quant=args.fixed_raw_quant,
            flags=flags,
            fixed_ytox=args.fixed_ytox,
            fixed_ytob=args.fixed_ytob,
            token_select_name=args.token_select if args.flags is None else "flags-override",
            target_variant=args.target_variant,
        )
    if args.validate_manifest is not None:
        try:
            validate_manifest_json(args.validate_manifest)
        except ValueError as exc:
            raise SystemExit(f"error: {exc}") from exc
        print(f"validated {args.validate_manifest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
