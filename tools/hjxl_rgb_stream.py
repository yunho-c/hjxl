#!/usr/bin/env python3
"""Convert linear RGB PFM images into HJXL AXI input stream CSV rows."""

from __future__ import annotations

import argparse
import csv
import json
import math
from pathlib import Path
import struct

from hjxl_abi import discovery_metadata, rgb_active_route, validate_discovery_metadata
from hjxl_abi_generated import (
    CONTROL_CLEAR_PROTOCOL_ERROR,
    REGISTER_MAP,
    REGISTER_DISTANCE_Q8,
    REGISTER_FIXED_INV_QAC_Q16,
    REGISTER_FIXED_POINT_SCALE,
    REGISTER_FIXED_RAW_QUANT,
    REGISTER_FIXED_YTOB,
    REGISTER_FIXED_YTOX,
    REGISTER_FLAGS,
    REGISTER_XSIZE,
    REGISTER_YSIZE,
    STATUS_BITS,
    TOKEN_SELECT,
    TRACE_STAGES,
)

SINT8_MIN = -(1 << 7)
SINT8_MAX = (1 << 7) - 1
UINT8_MAX = (1 << 8) - 1
UINT32_MAX = (1 << 32) - 1

TRACE_ROUTES = {
    "all": None,
    **TRACE_STAGES,
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


def _read_ascii_line(handle, *, label: str) -> str:
    line = handle.readline()
    if not line:
        raise ValueError(f"missing {label}")
    return line.decode("ascii").strip()


def read_pfm(path: Path) -> tuple[int, int, list[tuple[float, float, float]]]:
    with path.open("rb") as handle:
        magic = _read_ascii_line(handle, label="PFM magic")
        if magic != "PF":
            raise ValueError(f"{path}: expected RGB PFM magic PF, got {magic!r}")

        dimensions = _read_ascii_line(handle, label="PFM dimensions").split()
        if len(dimensions) != 2:
            raise ValueError(f"{path}: expected '<width> <height>' dimensions")
        width = int(dimensions[0])
        height = int(dimensions[1])
        if width <= 0 or height <= 0:
            raise ValueError(f"{path}: width and height must be positive")

        scale = float(_read_ascii_line(handle, label="PFM scale"))
        if scale == 0.0:
            raise ValueError(f"{path}: PFM scale must be nonzero")
        endian = "<" if scale < 0.0 else ">"
        sample_count = width * height * 3
        data = handle.read()
        expected_bytes = sample_count * 4
        if len(data) != expected_bytes:
            raise ValueError(f"{path}: expected {expected_bytes} data bytes, got {len(data)}")
        values = struct.unpack(f"{endian}{sample_count}f", data)

    rows_bottom_first = [
        values[row * width * 3 : (row + 1) * width * 3]
        for row in range(height)
    ]
    pixels: list[tuple[float, float, float]] = []
    for row in reversed(rows_bottom_first):
        for x in range(width):
            base = x * 3
            pixels.append((row[base], row[base + 1], row[base + 2]))
    return width, height, pixels


def _signed_range(pixel_bits: int) -> tuple[int, int]:
    if pixel_bits <= 0 or pixel_bits > 32:
        raise ValueError("--pixel-bits must be in the range 1..32")
    return -(1 << (pixel_bits - 1)), (1 << (pixel_bits - 1)) - 1


def quantize_sample(value: float, *, fraction_bits: int, pixel_bits: int, clamp: bool) -> int:
    if not math.isfinite(value):
        raise ValueError(f"non-finite PFM sample: {value}")
    quantized = int(round(value * (1 << fraction_bits)))
    minimum, maximum = _signed_range(pixel_bits)
    if clamp:
        return max(minimum, min(maximum, quantized))
    if quantized < minimum or quantized > maximum:
        raise ValueError(
            f"quantized sample {quantized} does not fit signed {pixel_bits}-bit field"
        )
    return quantized


def pack_rgb_word(r: int, g: int, b: int, *, pixel_bits: int) -> int:
    mask = (1 << pixel_bits) - 1
    return (r & mask) | ((g & mask) << pixel_bits) | ((b & mask) << (2 * pixel_bits))


def write_stream_csv(
    path: Path,
    pixels: list[tuple[float, float, float]],
    *,
    pixel_bits: int,
    fraction_bits: int,
    clamp: bool,
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["data", "last"])
        for index, (r_float, g_float, b_float) in enumerate(pixels):
            r = quantize_sample(r_float, fraction_bits=fraction_bits, pixel_bits=pixel_bits, clamp=clamp)
            g = quantize_sample(g_float, fraction_bits=fraction_bits, pixel_bits=pixel_bits, clamp=clamp)
            b = quantize_sample(b_float, fraction_bits=fraction_bits, pixel_bits=pixel_bits, clamp=clamp)
            writer.writerow([pack_rgb_word(r, g, b, pixel_bits=pixel_bits), int(index == len(pixels) - 1)])


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
    width: int,
    height: int,
    distance_q8: int,
    fixed_point_scale: int,
    fixed_inv_qac_q16: int,
    fixed_raw_quant: int,
    flags: int,
    fixed_ytox: int,
    fixed_ytob: int,
) -> None:
    rows = [
        (REGISTER_XSIZE, width),
        (REGISTER_YSIZE, height),
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
    pfm: Path,
    stream_csv: Path,
    axi_lite_csv: Path | None,
    width: int,
    height: int,
    pixel_bits: int,
    fraction_bits: int,
    word_count: int,
    distance_q8: int,
    fixed_point_scale: int,
    fixed_inv_qac_q16: int,
    fixed_raw_quant: int,
    flags: int,
    fixed_ytox: int,
    fixed_ytob: int,
    token_select_name: str,
    trace_route_name: str,
) -> None:
    x_tiles = (width + 63) // 64
    y_tiles = (height + 63) // 64
    register_map = dict(REGISTER_MAP)
    manifest = {
        "format": "hjxl.rgb_stream_manifest.v1",
        "source": {"pfm": str(pfm)},
        "trace_route": {
            "name": trace_route_name,
            "stage": TRACE_ROUTES[trace_route_name],
            "focused": TRACE_ROUTES[trace_route_name] is not None,
        },
        "image": {
            "width": width,
            "height": height,
            "x_tiles": x_tiles,
            "y_tiles": y_tiles,
        },
        "stream": {
            "csv": str(stream_csv),
            "columns": ["data", "last"],
            "word_count": word_count,
            "pixel_bits": pixel_bits,
            "fraction_bits": fraction_bits,
            "packing": "R in low component field, then G, then B",
            "last": "asserted only on the final pixel",
        },
        "discovery": discovery_metadata(
            profile="rgb",
            active_route=rgb_active_route(flags=flags, focused_route=TRACE_ROUTES[trace_route_name]),
            width=width,
            height=height,
        ),
        "axi_lite": {
            "csv": str(axi_lite_csv) if axi_lite_csv is not None else None,
            "columns": ["address", "data", "strb"],
            "register_map": register_map,
            "status_control_bits": dict(STATUS_BITS)
            | {"clear_protocol_error_write_bit": CONTROL_CLEAR_PROTOCOL_ERROR},
            "config": {
                "xsize": width,
                "ysize": height,
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


def _parse_bool(value: str | None, *, path: Path, line: int, field: str) -> bool:
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
        line = index + 2
        data = _parse_int(row["data"], path=path, line=line, field="data")
        last = _parse_bool(row["last"], path=path, line=line, field="last")
        if data < 0:
            raise ValueError(f"{path}:{line}: data must be nonnegative")
        if index == len(rows) - 1:
            if not last:
                raise ValueError(f"{path}: final stream row does not assert last")
        elif last:
            raise ValueError(f"{path}: last asserted before final row at index {index}")


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
    if manifest.get("format") != "hjxl.rgb_stream_manifest.v1":
        raise ValueError(f"{path}: unsupported manifest format {manifest.get('format')!r}")
    image = manifest["image"]
    stream = manifest["stream"]
    width = _json_int(image.get("width"), path=path, field="image.width")
    height = _json_int(image.get("height"), path=path, field="image.height")
    if width <= 0 or height <= 0:
        raise ValueError(f"{path}: image width and height must be positive")
    expected_word_count = width * height
    x_tiles = _json_int(
        image.get("x_tiles", (width + 63) // 64),
        path=path,
        field="image.x_tiles",
    )
    y_tiles = _json_int(
        image.get("y_tiles", (height + 63) // 64),
        path=path,
        field="image.y_tiles",
    )
    if x_tiles <= 0 or y_tiles <= 0:
        raise ValueError(f"{path}: image tile grid must be positive")
    if x_tiles * 64 < width or x_tiles * 64 >= width + 64:
        raise ValueError(f"{path}: image x tile grid does not cover width")
    if y_tiles * 64 < height or y_tiles * 64 >= height + 64:
        raise ValueError(f"{path}: image y tile grid does not cover height")
    if _json_int(stream.get("word_count"), path=path, field="stream.word_count") != expected_word_count:
        raise ValueError(
            f"{path}: stream word_count {stream['word_count']} does not match image dimensions"
        )
    if "pixel_bits" in stream:
        pixel_bits = _json_int(stream["pixel_bits"], path=path, field="stream.pixel_bits")
        if pixel_bits <= 0 or pixel_bits > 32:
            raise ValueError(f"{path}: stream.pixel_bits must be in the range 1..32")
    trace_route = manifest.get("trace_route")
    if trace_route is not None:
        if not isinstance(trace_route, dict):
            raise ValueError(f"{path}: trace_route must be an object")
        name = trace_route.get("name")
        if name not in TRACE_ROUTES:
            raise ValueError(f"{path}: unsupported trace_route.name {name!r}")
        expected_stage = TRACE_ROUTES[name]
        if trace_route.get("stage") != expected_stage:
            raise ValueError(f"{path}: trace_route.stage does not match trace_route.name")
        if bool(trace_route.get("focused")) != (expected_stage is not None):
            raise ValueError(f"{path}: trace_route.focused does not match trace_route.name")
    else:
        name = "all"
    config = manifest["axi_lite"]["config"]
    if "discovery" in manifest:
        validate_discovery_metadata(
            manifest["discovery"],
            profile="rgb",
            active_route=rgb_active_route(
                flags=_json_int(config.get("flags"), path=path, field="axi_lite.config.flags"),
                focused_route=TRACE_ROUTES[name],
            ),
            width=width,
            height=height,
            source=path,
        )
    stream_csv = _manifest_path(path, stream["csv"])
    if stream_csv is None:
        raise ValueError(f"{path}: missing stream CSV path")
    validate_stream_csv(stream_csv, expected_word_count)
    axi_lite_csv = _manifest_path(path, manifest["axi_lite"]["csv"])
    if axi_lite_csv is not None:
        validate_axi_lite_csv(axi_lite_csv, manifest)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pfm", type=Path, help="input RGB PFM image")
    parser.add_argument("--stream-csv", type=Path, help="output AXI stream data,last CSV")
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
        help="validate an existing RGB stream manifest and its referenced CSV artifacts",
    )
    parser.add_argument("--pixel-bits", type=int, default=16, help="signed component field width")
    parser.add_argument(
        "--fraction-bits",
        type=int,
        default=8,
        help="linear RGB fixed-point fraction bits; default matches RgbToXybApprox Q8 input",
    )
    parser.add_argument(
        "--no-clamp",
        action="store_true",
        help="fail instead of clamping samples outside the signed component range",
    )
    parser.add_argument("--distance-q8", type=int, default=256, help="FrameConfig distanceQ8 register value")
    parser.add_argument("--fixed-point-scale", type=int, default=0, help="FrameConfig fixedPointScale")
    parser.add_argument("--fixed-inv-qac-q16", type=int, default=0, help="FrameConfig fixedInvQacQ16")
    parser.add_argument("--fixed-raw-quant", type=int, default=0, help="FrameConfig fixedRawQuant")
    parser.add_argument("--fixed-ytox", type=int, default=0, help="FrameConfig fixedYtox signed 8-bit CFL override")
    parser.add_argument("--fixed-ytob", type=int, default=0, help="FrameConfig fixedYtob signed 8-bit CFL override")
    parser.add_argument("--enable-xyb", action="store_true", help="set FrameConfig enableXyb")
    parser.add_argument("--enable-dct", action="store_true", help="set FrameConfig enableDct")
    parser.add_argument("--enable-quant", action="store_true", help="set FrameConfig enableQuant")
    parser.add_argument("--enable-tokenize", action="store_true", help="set FrameConfig enableTokenize")
    parser.add_argument(
        "--token-select",
        choices=tuple(TOKEN_SELECT),
        default="dc",
        help="FrameConfig tokenSelect field for token substreams or the selected AQ contrast route",
    )
    parser.add_argument(
        "--trace-route",
        choices=tuple(TRACE_ROUTES),
        default="all",
        help="compile-time HjxlCore traceRoute used by the generated top",
    )
    args = parser.parse_args()

    if args.validate_manifest is not None and args.pfm is None and args.stream_csv is None:
        try:
            validate_manifest_json(args.validate_manifest)
        except ValueError as exc:
            raise SystemExit(f"error: {exc}") from exc
        print(f"validated {args.validate_manifest}")
        return 0

    if args.pfm is None or args.stream_csv is None:
        raise SystemExit("--pfm and --stream-csv are required unless --validate-manifest is used alone")

    if args.fraction_bits < 0 or args.fraction_bits > 30:
        raise SystemExit("--fraction-bits must be in the range 0..30")
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

    try:
        width, height, pixels = read_pfm(args.pfm)
        flags = flags_word(
            enable_xyb=args.enable_xyb,
            enable_dct=args.enable_dct,
            enable_quant=args.enable_quant,
            enable_tokenize=args.enable_tokenize,
            token_select=TOKEN_SELECT[args.token_select],
        )
        write_stream_csv(
            args.stream_csv,
            pixels,
            pixel_bits=args.pixel_bits,
            fraction_bits=args.fraction_bits,
            clamp=not args.no_clamp,
        )
        if args.axi_lite_csv is not None:
            write_axi_lite_csv(
                args.axi_lite_csv,
                width=width,
                height=height,
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
                pfm=args.pfm,
                stream_csv=args.stream_csv,
                axi_lite_csv=args.axi_lite_csv,
                width=width,
                height=height,
                pixel_bits=args.pixel_bits,
                fraction_bits=args.fraction_bits,
                word_count=len(pixels),
                distance_q8=args.distance_q8,
                fixed_point_scale=args.fixed_point_scale,
                fixed_inv_qac_q16=args.fixed_inv_qac_q16,
                fixed_raw_quant=args.fixed_raw_quant,
                flags=flags,
                fixed_ytox=args.fixed_ytox,
                fixed_ytob=args.fixed_ytob,
                token_select_name=args.token_select,
                trace_route_name=args.trace_route,
            )
    except ValueError as exc:
        raise SystemExit(f"error: {exc}") from exc

    print(f"wrote {len(pixels)} RGB stream words for {width}x{height}")
    if args.axi_lite_csv is not None:
        print(f"wrote AXI-Lite config writes to {args.axi_lite_csv}")
    if args.manifest_json is not None:
        print(f"wrote manifest to {args.manifest_json}")
    if args.validate_manifest is not None:
        try:
            validate_manifest_json(args.validate_manifest)
        except ValueError as exc:
            raise SystemExit(f"error: {exc}") from exc
        print(f"validated {args.validate_manifest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
