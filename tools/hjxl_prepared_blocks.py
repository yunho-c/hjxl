#!/usr/bin/env python3
"""Convert prepared DCT-only block JSON fixtures into simulator and stream CSV inputs."""

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
PREPARED_DCT_SCALAR_WORDS = 9
PREPARED_DCT_WORDS_PER_BLOCK = PREPARED_DCT_SCALAR_WORDS + 3 * BLOCK_SIZE
REGISTER_XSIZE = 0x04
REGISTER_YSIZE = 0x08
REGISTER_DISTANCE_Q8 = 0x0C
REGISTER_FIXED_POINT_SCALE = 0x10
REGISTER_FIXED_INV_QAC_Q16 = 0x14
REGISTER_FIXED_RAW_QUANT = 0x18
REGISTER_FLAGS = 0x1C

TOKEN_SELECT = {
    "dc": 0,
    "ac-metadata": 1,
    "ac-tokens": 2,
}
CONFIG_REGISTER_KEYS = (
    ("xsize", "xsize"),
    ("ysize", "ysize"),
    ("distance_q8", "distance_q8"),
    ("fixed_point_scale", "fixed_point_scale"),
    ("fixed_inv_qac_q16", "fixed_inv_qac_q16"),
    ("fixed_raw_quant", "fixed_raw_quant"),
    ("flags", "flags"),
)


def _u32(value: int) -> int:
    return int(value) & 0xFFFFFFFF


def _parse_int(value: str) -> int:
    return int(value, 0)


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


def image_dimensions(fixture: dict) -> tuple[int, int, int, int]:
    image = fixture.get("image", {})
    x_blocks = int(image.get("x_blocks", 0))
    y_blocks = int(image.get("y_blocks", 0))
    xsize = int(image.get("xsize", x_blocks * 8))
    ysize = int(image.get("ysize", y_blocks * 8))
    return xsize, ysize, x_blocks, y_blocks


def validate_fixture(path: Path, fixture: dict) -> None:
    xsize, ysize, x_blocks, y_blocks = image_dimensions(fixture)
    if x_blocks <= 0 or y_blocks <= 0:
        raise ValueError(f"{path}: image.x_blocks and image.y_blocks must be positive")
    if xsize <= 0 or ysize <= 0:
        raise ValueError(f"{path}: image.xsize and image.ysize must be positive when present")
    if (xsize + 7) // 8 != x_blocks or (ysize + 7) // 8 != y_blocks:
        raise ValueError(f"{path}: image dimensions do not match declared 8x8 block grid")

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


def write_input_stream_csv(path: Path, fixture: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    total_words = len(fixture["blocks"]) * PREPARED_DCT_WORDS_PER_BLOCK
    word_index = 0
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["data", "last"])
        for block in fixture["blocks"]:
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
                raise ValueError(
                    f"{path}: internal error: prepared block stream has {len(words)} words"
                )
            for word in words:
                word_index += 1
                writer.writerow([_u32(word), int(word_index == total_words)])


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
) -> None:
    rows = [
        (REGISTER_XSIZE, xsize),
        (REGISTER_YSIZE, ysize),
        (REGISTER_DISTANCE_Q8, distance_q8),
        (REGISTER_FIXED_POINT_SCALE, fixed_point_scale),
        (REGISTER_FIXED_INV_QAC_Q16, fixed_inv_qac_q16),
        (REGISTER_FIXED_RAW_QUANT, fixed_raw_quant),
        (REGISTER_FLAGS, flags),
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
    token_select_name: str,
) -> None:
    xsize, ysize, x_blocks, y_blocks = image_dimensions(fixture)
    block_count = len(fixture["blocks"])
    register_map = {
        "status_control": 0x00,
        "xsize": REGISTER_XSIZE,
        "ysize": REGISTER_YSIZE,
        "distance_q8": REGISTER_DISTANCE_Q8,
        "fixed_point_scale": REGISTER_FIXED_POINT_SCALE,
        "fixed_inv_qac_q16": REGISTER_FIXED_INV_QAC_Q16,
        "fixed_raw_quant": REGISTER_FIXED_RAW_QUANT,
        "flags": REGISTER_FLAGS,
    }
    manifest = {
        "format": "hjxl.prepared_dct_stream_manifest.v1",
        "source": {"prepared_json": str(prepared_json)},
        "image": {
            "xsize": xsize,
            "ysize": ysize,
            "x_blocks": x_blocks,
            "y_blocks": y_blocks,
        },
        "stream": {
            "csv": str(input_stream_csv),
            "columns": ["data", "last"],
            "word_count": block_count * PREPARED_DCT_WORDS_PER_BLOCK,
            "block_count": block_count,
            "words_per_block": PREPARED_DCT_WORDS_PER_BLOCK,
            "scalar_words_per_block": PREPARED_DCT_SCALAR_WORDS,
            "coefficient_words_per_block": 3 * BLOCK_SIZE,
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
    if path.is_absolute() or path.exists():
        return path
    return manifest_path.parent / path


def _csv_bool(value: str) -> bool:
    return int(value, 0) != 0


def validate_stream_csv(path: Path, expected_word_count: int) -> None:
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames != ["data", "last"]:
            raise ValueError(f"{path}: expected data,last columns")
        rows = list(reader)
    if len(rows) != expected_word_count:
        raise ValueError(f"{path}: expected {expected_word_count} stream rows, got {len(rows)}")
    if not rows:
        raise ValueError(f"{path}: stream CSV must contain at least one row")
    for index, row in enumerate(rows):
        last = _csv_bool(row["last"])
        if index == len(rows) - 1:
            if not last:
                raise ValueError(f"{path}: final stream row does not assert last")
        elif last:
            raise ValueError(f"{path}: last asserted before final row at index {index}")
        int(row["data"], 0)


def validate_axi_lite_csv(path: Path, manifest: dict) -> None:
    axi_lite = manifest["axi_lite"]
    register_map = axi_lite["register_map"]
    config = axi_lite["config"]
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames != ["address", "data", "strb"]:
            raise ValueError(f"{path}: expected address,data,strb columns")
        writes = {
            int(row["address"], 0): (int(row["data"], 0), int(row["strb"], 0))
            for row in reader
        }
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
    image = manifest["image"]
    stream = manifest["stream"]
    expected_blocks = int(image["x_blocks"]) * int(image["y_blocks"])
    expected_word_count = expected_blocks * PREPARED_DCT_WORDS_PER_BLOCK
    if int(stream["block_count"]) != expected_blocks:
        raise ValueError(f"{path}: stream block_count {stream['block_count']} does not match image grid")
    if int(stream["word_count"]) != expected_word_count:
        raise ValueError(
            f"{path}: stream word_count {stream['word_count']} does not match image block grid"
        )
    stream_csv = _manifest_path(path, stream["csv"])
    if stream_csv is None:
        raise ValueError(f"{path}: missing stream CSV path")
    validate_stream_csv(stream_csv, expected_word_count)
    axi_lite_csv = _manifest_path(path, manifest["axi_lite"]["csv"])
    if axi_lite_csv is not None:
        validate_axi_lite_csv(axi_lite_csv, manifest)

    source_json = _manifest_path(path, manifest.get("source", {}).get("prepared_json"))
    if source_json is not None and source_json.exists():
        fixture = load_fixture(source_json)
        xsize, ysize, x_blocks, y_blocks = image_dimensions(fixture)
        if (
            int(image["xsize"]) != xsize
            or int(image["ysize"]) != ysize
            or int(image["x_blocks"]) != x_blocks
            or int(image["y_blocks"]) != y_blocks
            or int(stream["block_count"]) != len(fixture["blocks"])
        ):
            raise ValueError(f"{path}: manifest image metadata does not match source prepared JSON")


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
        type=_parse_int,
        help="override packed FrameConfig flags word; default is the prepared-DCT token path",
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

    xsize, ysize, _, _ = image_dimensions(fixture)
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
            token_select_name=args.token_select if args.flags is None else "flags-override",
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
