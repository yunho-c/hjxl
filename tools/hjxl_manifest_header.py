#!/usr/bin/env python3
"""Generate a small C header from an HJXL stream/control manifest."""

from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path
import re

from hjxl_abi import (
    discovery_metadata as expected_discovery_metadata,
    rgb_active_route,
    validate_discovery_metadata,
)
from hjxl_abi_generated import (
    AXI_LITE_ADDR_BITS,
    AXI_LITE_DATA_BITS,
    AXI_LITE_STRB_BITS,
    CAPABILITY_BITS,
    KV260_TRACE_CAPTURE_WORD_BYTES,
    REGISTER_ACCESS,
    REGISTER_MAP,
    ROUTE_IDS,
    TRACE_GROUP_BITS,
    TRACE_GROUP_BYTE_OFFSET,
    TRACE_GROUP_MASK,
    TRACE_GROUP_SHIFT,
    TRACE_INDEX_BITS,
    TRACE_INDEX_BYTE_OFFSET,
    TRACE_INDEX_MASK,
    TRACE_INDEX_SHIFT,
    TRACE_PACKED_BITS,
    TRACE_PACKED_BYTES,
    TRACE_STAGE_BITS,
    TRACE_STAGE_BYTE_OFFSET,
    TRACE_STAGE_MASK,
    TRACE_STAGE_SHIFT,
    TRACE_STAGES,
    TRACE_TKEEP_MASK,
    TRACE_VALUE_BITS,
    TRACE_VALUE_BYTE_OFFSET,
    TRACE_VALUE_MASK,
    TRACE_VALUE_SHIFT,
)


SUPPORTED_FORMATS = {
    "hjxl.rgb_stream_manifest.v1": "RGB stream input",
    "hjxl.prepared_dct_stream_manifest.v1": "prepared DCT stream input",
}

TARGETS = {
    "hjxl.rgb_stream_manifest.v1": {
        "interface": "rgb_axi_stream",
        "variant": "rgb",
        "stream_shell": "HjxlAxiStreamCore",
        "controlled_shell": "HjxlAxiLiteStreamCore",
        "kv260_top": None,
        "input_stream": "raster RGB pixels",
        "input_keep_enforced": 0,
    },
    "hjxl.prepared_dct_stream_manifest.v1": {
        "interface": "prepared_dct_axi_stream",
        "variant": "direct",
        "stream_shell": "HjxlPreparedDctAxiStreamCore",
        "controlled_shell": "HjxlPreparedDctAxiLiteStreamCore",
        "kv260_top": "HjxlKv260PreparedDctTop",
        "input_stream": "prepared DCT-only block words",
        "input_keep_enforced": 1,
    },
}
TARGET_VARIANTS = {
    "hjxl.prepared_dct_stream_manifest.v1": {
        "direct": TARGETS["hjxl.prepared_dct_stream_manifest.v1"],
        "estimated-cfl": {
            "interface": "prepared_dct_axi_stream",
            "variant": "estimated-cfl",
            "stream_shell": "HjxlPreparedCflDctAxiStreamCore",
            "controlled_shell": "HjxlPreparedCflDctAxiLiteStreamCore",
            "kv260_top": "HjxlKv260PreparedCflDctTop",
            "input_stream": "prepared DCT-only block words with internally estimated CFL maps",
            "input_keep_enforced": 1,
        },
    },
}

TRACE_ROUTES = {
    "all": {"stage": None, "focused": False},
    **{name: {"stage": value, "focused": True} for name, value in TRACE_STAGES.items()},
    "prepared-dct-quantize-token": {"stage": None, "focused": True},
}
DEFAULT_DISTANCE_Q8 = 256
SUPPORTED_DISTANCE_Q8 = (64, 128, 256, 512, 1024, 2048)

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
SIGNED_CONFIG_KEYS = {"fixed_ytox", "fixed_ytob"}


def _symbol(value: str) -> str:
    symbol = re.sub(r"[^A-Za-z0-9_]", "_", value).upper()
    symbol = re.sub(r"_+", "_", symbol).strip("_")
    if not symbol:
        raise ValueError("symbol prefix must contain at least one letter or digit")
    if symbol[0].isdigit():
        symbol = f"_{symbol}"
    return symbol


def _int(value: object, *, field: str) -> int:
    if value is None:
        raise ValueError(f"{field} is required")
    if isinstance(value, bool):
        raise ValueError(f"{field} must be an integer, got {value!r}")
    if isinstance(value, float):
        raise ValueError(f"{field} must be an integer, got {value!r}")
    try:
        if isinstance(value, str):
            return int(value.strip(), 0)
        return int(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{field} must be an integer, got {value!r}") from exc


def _u32(value: object, *, field: str) -> int:
    parsed = _int(value, field=field)
    if parsed < 0 or parsed > 0xFFFFFFFF:
        raise ValueError(f"{field} must fit in uint32_t, got {value!r}")
    return parsed


def _config_write_data(register_key: str, value: object, *, field: str) -> int:
    if register_key not in SIGNED_CONFIG_KEYS:
        return _u32(value, field=field)
    parsed = _int(value, field=field)
    if parsed < -(1 << 7) or parsed > (1 << 7) - 1:
        raise ValueError(f"{field} must fit in signed 8-bit, got {value!r}")
    return parsed & 0xFFFFFFFF


def _hex(value: int) -> str:
    return f"0x{value:08x}u"


def _c_string(value: object) -> str:
    if value is None:
        return ""
    return value.replace("\\", "\\\\").replace("\"", "\\\"")


def _status_bit(status_bits: dict, key: str, default: int | None = None) -> int:
    if key in status_bits:
        return _u32(status_bits[key], field=f"axi_lite.status_control_bits.{key}")
    if default is not None:
        return _u32(default, field=f"axi_lite.status_control_bits.{key}")
    raise KeyError(key)


def target_metadata(manifest_or_format: dict | str | None) -> dict:
    if isinstance(manifest_or_format, dict):
        manifest = manifest_or_format
        manifest_format = manifest.get("format")
        target_block = manifest.get("target", {})
    else:
        manifest = None
        manifest_format = manifest_or_format
        target_block = {}
    if manifest_format not in TARGETS:
        raise ValueError(f"unsupported manifest format {manifest_format!r}")

    variant = None
    if isinstance(target_block, dict):
        variant = target_block.get("variant")
    elif target_block:
        raise ValueError("target must be an object")

    if variant is None:
        target = copy.deepcopy(TARGETS[manifest_format])
    else:
        variants = TARGET_VARIANTS.get(manifest_format)
        if variants is None or variant not in variants:
            raise ValueError(f"unsupported target.variant {variant!r}")
        target = copy.deepcopy(variants[variant])

    if manifest is not None and isinstance(target_block, dict):
        for key, expected_value in target.items():
            if key in target_block and target_block[key] != expected_value:
                raise ValueError(f"target.{key} does not match target.variant")
    return target


def trace_route_metadata(manifest: dict) -> dict:
    route = manifest.get("trace_route")
    if route is None:
        if manifest.get("format") == "hjxl.prepared_dct_stream_manifest.v1":
            route = {"name": "prepared-dct-quantize-token"}
        else:
            route = {"name": "all"}
    if not isinstance(route, dict):
        raise ValueError("trace_route must be an object")
    name = route.get("name")
    if name not in TRACE_ROUTES:
        raise ValueError(f"unsupported trace_route.name {name!r}")
    route_info = TRACE_ROUTES[name]
    expected_stage = route_info["stage"]
    expected_focused = route_info["focused"]
    if route.get("stage") != expected_stage:
        raise ValueError("trace_route.stage does not match trace_route.name")
    if bool(route.get("focused", expected_focused)) != expected_focused:
        raise ValueError("trace_route.focused does not match trace_route.name")
    return {
        "name": name,
        "stage": expected_stage,
        "focused": expected_focused,
    }


def distance_metadata() -> dict:
    return {
        "fallback_q8": DEFAULT_DISTANCE_Q8,
        "supported_q8": list(SUPPORTED_DISTANCE_Q8),
    }


def register_writes(manifest: dict) -> list[tuple[str, int, int, int]]:
    axi_lite = manifest["axi_lite"]
    register_map = axi_lite["register_map"]
    config = axi_lite["config"]
    rows = []
    for register_key, config_key in CONFIG_REGISTER_KEYS:
        rows.append(
            (
                register_key,
                _u32(register_map[register_key], field=f"axi_lite.register_map.{register_key}"),
                _config_write_data(
                    register_key,
                    config[config_key],
                    field=f"axi_lite.config.{config_key}",
                ),
                0xF,
            )
        )
    return rows


def register_map_metadata(manifest: dict) -> dict[str, int]:
    raw_map = manifest["axi_lite"]["register_map"]
    normalized = {
        name: _u32(address, field=f"axi_lite.register_map.{name}")
        for name, address in raw_map.items()
    }
    for name, access in REGISTER_ACCESS.items():
        if access != "ro":
            continue
        expected = REGISTER_MAP[name]
        if name in normalized and normalized[name] != expected:
            raise ValueError(
                f"axi_lite.register_map.{name} does not match generated discovery address"
            )
        normalized[name] = expected
    return normalized


def input_data_bits(manifest: dict) -> int:
    stream = manifest["stream"]
    if "pixel_bits" in stream:
        pixel_bits = _int(stream["pixel_bits"], field="stream.pixel_bits")
        if pixel_bits <= 0 or pixel_bits > 32:
            raise ValueError("stream.pixel_bits must be in the range 1..32")
        return pixel_bits * 3
    return 32


def input_data_bytes(manifest: dict) -> int:
    return (input_data_bits(manifest) + 7) // 8


def input_keep_mask(manifest: dict) -> int:
    return (1 << input_data_bytes(manifest)) - 1


def coefficient_fraction_bits(manifest: dict) -> int:
    return _int(
        manifest["stream"].get("coefficient_fraction_bits", 0),
        field="stream.coefficient_fraction_bits",
    )


def frame_metadata(manifest: dict) -> dict[str, int]:
    image = manifest.get("image", {})
    config = manifest["axi_lite"]["config"]
    xsize = _u32(config["xsize"], field="axi_lite.config.xsize")
    ysize = _u32(config["ysize"], field="axi_lite.config.ysize")
    x_blocks = _u32(image.get("x_blocks", (xsize + 7) // 8), field="image.x_blocks")
    y_blocks = _u32(image.get("y_blocks", (ysize + 7) // 8), field="image.y_blocks")
    x_tiles = _u32(image.get("x_tiles", (xsize + 63) // 64), field="image.x_tiles")
    y_tiles = _u32(image.get("y_tiles", (ysize + 63) // 64), field="image.y_tiles")
    return {
        "xsize": xsize,
        "ysize": ysize,
        "x_blocks": x_blocks,
        "y_blocks": y_blocks,
        "padded_xsize": x_blocks * 8,
        "padded_ysize": y_blocks * 8,
        "block_count": x_blocks * y_blocks,
        "x_tiles": x_tiles,
        "y_tiles": y_tiles,
        "tile_count": x_tiles * y_tiles,
    }


def manifest_discovery_metadata(manifest: dict) -> dict[str, int]:
    frame = frame_metadata(manifest)
    target = target_metadata(manifest)
    route = trace_route_metadata(manifest)
    if target["interface"] == "rgb_axi_stream":
        profile = "rgb"
        active_route = rgb_active_route(
            flags=_u32(manifest["axi_lite"]["config"]["flags"], field="axi_lite.config.flags"),
            focused_route=route["stage"],
        )
    else:
        profile = "prepared-direct" if target["variant"] == "direct" else "prepared-estimated-cfl"
        active_route = ROUTE_IDS[profile]
    if "discovery" in manifest:
        return validate_discovery_metadata(
            manifest["discovery"],
            profile=profile,
            active_route=active_route,
            width=frame["xsize"],
            height=frame["ysize"],
            source="manifest",
        )
    return expected_discovery_metadata(
        profile=profile,
        active_route=active_route,
        width=frame["xsize"],
        height=frame["ysize"],
    )


def header_text(manifest: dict, *, symbol_prefix: str, include_guard: str | None) -> str:
    manifest_format = str(manifest.get("format", ""))
    if manifest_format not in SUPPORTED_FORMATS:
        raise ValueError(f"unsupported manifest format {manifest_format!r}")

    prefix = _symbol(symbol_prefix)
    guard = _symbol(include_guard or f"{prefix}_H")
    stream = manifest["stream"]
    axi_lite = manifest["axi_lite"]
    status_bits = axi_lite["status_control_bits"]
    register_map = register_map_metadata(manifest)
    rows = register_writes(manifest)
    word_count = _u32(stream["word_count"], field="stream.word_count")
    stream_data_bits = input_data_bits(manifest)
    stream_data_bytes = input_data_bytes(manifest)
    stream_keep_mask = input_keep_mask(manifest)
    coefficient_fraction_bits_value = _u32(
        coefficient_fraction_bits(manifest),
        field="stream.coefficient_fraction_bits",
    )
    frame = frame_metadata(manifest)
    target = target_metadata(manifest)
    trace_route = trace_route_metadata(manifest)
    trace_route_stage = -1 if trace_route["stage"] is None else int(trace_route["stage"])
    discovery = manifest_discovery_metadata(manifest)

    lines = [
        "/* Generated by tools/hjxl_manifest_header.py; do not edit by hand. */",
        f"#ifndef {guard}",
        f"#define {guard}",
        "",
        "#include <stdint.h>",
        "",
        "typedef struct {",
        "  uint32_t stage;",
        "  uint32_t group;",
        "  uint32_t index;",
        "  int32_t value;",
        f"}} {prefix.lower()}_stage_trace_t;",
        "",
        "typedef struct {",
        "  uint32_t address;",
        "  uint32_t data;",
        "  uint32_t strb;",
        f"}} {prefix.lower()}_axi_lite_write_t;",
        "",
        f"#define {prefix}_MANIFEST_FORMAT \"{manifest_format}\"",
        f"#define {prefix}_MANIFEST_DESCRIPTION \"{SUPPORTED_FORMATS[manifest_format]}\"",
        f"#define {prefix}_TARGET_INTERFACE \"{_c_string(target['interface'])}\"",
        f"#define {prefix}_TARGET_VARIANT \"{_c_string(target['variant'])}\"",
        f"#define {prefix}_TARGET_STREAM_SHELL \"{_c_string(target['stream_shell'])}\"",
        f"#define {prefix}_TARGET_CONTROLLED_SHELL \"{_c_string(target['controlled_shell'])}\"",
        f"#define {prefix}_TARGET_KV260_TOP \"{_c_string(target['kv260_top'])}\"",
        f"#define {prefix}_TARGET_INPUT_STREAM \"{_c_string(target['input_stream'])}\"",
        f"#define {prefix}_TRACE_ROUTE_NAME \"{_c_string(trace_route['name'])}\"",
        f"#define {prefix}_TRACE_ROUTE_STAGE {trace_route_stage}",
        f"#define {prefix}_TRACE_ROUTE_FOCUSED {int(trace_route['focused'])}u",
        f"#define {prefix}_STREAM_WORD_COUNT {word_count}u",
        f"#define {prefix}_FRAME_XSIZE {frame['xsize']}u",
        f"#define {prefix}_FRAME_YSIZE {frame['ysize']}u",
        f"#define {prefix}_FRAME_X_BLOCKS {frame['x_blocks']}u",
        f"#define {prefix}_FRAME_Y_BLOCKS {frame['y_blocks']}u",
        f"#define {prefix}_FRAME_PADDED_XSIZE {frame['padded_xsize']}u",
        f"#define {prefix}_FRAME_PADDED_YSIZE {frame['padded_ysize']}u",
        f"#define {prefix}_FRAME_BLOCK_COUNT {frame['block_count']}u",
        f"#define {prefix}_FRAME_X_TILES {frame['x_tiles']}u",
        f"#define {prefix}_FRAME_Y_TILES {frame['y_tiles']}u",
        f"#define {prefix}_FRAME_TILE_COUNT {frame['tile_count']}u",
        f"#define {prefix}_INPUT_DATA_BITS {stream_data_bits}u",
        f"#define {prefix}_COEFFICIENT_FRACTION_BITS {coefficient_fraction_bits_value}u",
        f"#define {prefix}_STREAM_WORD_BYTES {stream_data_bytes}u",
        f"#define {prefix}_INPUT_TKEEP_MASK {_hex(stream_keep_mask)}",
        f"#define {prefix}_INPUT_TKEEP_ENFORCED {_u32(target['input_keep_enforced'], field='target.input_keep_enforced')}u",
        f"#define {prefix}_STREAM_BYTE_COUNT {word_count * stream_data_bytes}u",
        f"#define {prefix}_AXI_LITE_ADDR_BITS {AXI_LITE_ADDR_BITS}u",
        f"#define {prefix}_AXI_LITE_DATA_BITS {AXI_LITE_DATA_BITS}u",
        f"#define {prefix}_AXI_LITE_STRB_BITS {AXI_LITE_STRB_BITS}u",
        f"#define {prefix}_AXI_LITE_WRITE_COUNT {len(rows)}u",
        f"#define {prefix}_DISCOVERY_IDENTITY {_hex(discovery['identity'])}",
        f"#define {prefix}_DISCOVERY_ABI_VERSION {_hex(discovery['abi_version'])}",
        f"#define {prefix}_DISCOVERY_BUILD_ID {_hex(discovery['build_id'])}",
        f"#define {prefix}_DISCOVERY_REQUIRED_CAPABILITIES {_hex(discovery['required_capabilities'])}",
        f"#define {prefix}_DISCOVERY_ACTIVE_ROUTE {discovery['active_route']}u",
        f"#define {prefix}_DISCOVERY_MIN_MAX_FRAME_WIDTH {discovery['minimum_max_frame_width']}u",
        f"#define {prefix}_DISCOVERY_MIN_MAX_FRAME_HEIGHT {discovery['minimum_max_frame_height']}u",
        f"#define {prefix}_TRACE_STAGE_BITS {TRACE_STAGE_BITS}u",
        f"#define {prefix}_TRACE_GROUP_BITS {TRACE_GROUP_BITS}u",
        f"#define {prefix}_TRACE_INDEX_BITS {TRACE_INDEX_BITS}u",
        f"#define {prefix}_TRACE_VALUE_BITS {TRACE_VALUE_BITS}u",
        f"#define {prefix}_TRACE_STAGE_SHIFT {TRACE_STAGE_SHIFT}u",
        f"#define {prefix}_TRACE_GROUP_SHIFT {TRACE_GROUP_SHIFT}u",
        f"#define {prefix}_TRACE_INDEX_SHIFT {TRACE_INDEX_SHIFT}u",
        f"#define {prefix}_TRACE_VALUE_SHIFT {TRACE_VALUE_SHIFT}u",
        f"#define {prefix}_TRACE_STAGE_BYTE_OFFSET {TRACE_STAGE_BYTE_OFFSET}u",
        f"#define {prefix}_TRACE_GROUP_BYTE_OFFSET {TRACE_GROUP_BYTE_OFFSET}u",
        f"#define {prefix}_TRACE_INDEX_BYTE_OFFSET {TRACE_INDEX_BYTE_OFFSET}u",
        f"#define {prefix}_TRACE_VALUE_BYTE_OFFSET {TRACE_VALUE_BYTE_OFFSET}u",
        f"#define {prefix}_TRACE_STAGE_MASK {_hex(TRACE_STAGE_MASK)}",
        f"#define {prefix}_TRACE_GROUP_MASK {_hex(TRACE_GROUP_MASK)}",
        f"#define {prefix}_TRACE_INDEX_MASK {_hex(TRACE_INDEX_MASK)}",
        f"#define {prefix}_TRACE_VALUE_MASK {_hex(TRACE_VALUE_MASK)}",
        f"#define {prefix}_TRACE_PACKED_BITS {TRACE_PACKED_BITS}u",
        f"#define {prefix}_TRACE_PACKED_BYTES {TRACE_PACKED_BYTES}u",
        f"#define {prefix}_TRACE_TKEEP_MASK {_hex(TRACE_TKEEP_MASK)}",
        f"#define {prefix}_KV260_TRACE_CAPTURE_WORD_BYTES {KV260_TRACE_CAPTURE_WORD_BYTES}u",
        f"#define {prefix}_DISTANCE_FALLBACK_Q8 {DEFAULT_DISTANCE_Q8}u",
        f"#define {prefix}_SUPPORTED_DISTANCE_Q8_COUNT {len(SUPPORTED_DISTANCE_Q8)}u",
        "",
    ]

    register_addresses = [
        (name, _u32(address, field=f"axi_lite.register_map.{name}"))
        for name, address in register_map.items()
    ]
    for name, address in sorted(register_addresses, key=lambda item: item[1]):
        lines.append(f"#define {prefix}_REG_{_symbol(name)} {_hex(address)}")
        lines.append(
            f"#define {prefix}_REG_{_symbol(name)}_ACCESS_{_symbol(REGISTER_ACCESS.get(name, 'unknown'))} 1u"
        )
    lines.append("")
    for name, bit in sorted(CAPABILITY_BITS.items(), key=lambda item: item[1]):
        lines.append(f"#define {prefix}_CAPABILITY_{_symbol(name)}_BIT {bit}u")
    lines.extend(
        [
            f"#define {prefix}_STATUS_PROTOCOL_ERROR_BIT "
            f"{_u32(status_bits['protocol_error'], field='axi_lite.status_control_bits.protocol_error')}u",
            f"#define {prefix}_STATUS_BUSY_BIT "
            f"{_u32(status_bits['busy'], field='axi_lite.status_control_bits.busy')}u",
            f"#define {prefix}_STATUS_OVERFLOW_BIT "
            f"{_u32(status_bits['overflow'], field='axi_lite.status_control_bits.overflow')}u",
            f"#define {prefix}_STATUS_UNSUPPORTED_DISTANCE_BIT {_status_bit(status_bits, 'unsupported_distance', 3)}u",
            "#define "
            f"{prefix}_CONTROL_CLEAR_PROTOCOL_ERROR_BIT "
            f"{_u32(status_bits['clear_protocol_error_write_bit'], field='axi_lite.status_control_bits.clear_protocol_error_write_bit')}u",
            "",
            f"static const uint32_t {prefix}_SUPPORTED_DISTANCE_Q8[] = {{",
            "  " + ", ".join(f"{value}u" for value in SUPPORTED_DISTANCE_Q8),
            "};",
            "",
            f"static const {prefix.lower()}_axi_lite_write_t {prefix}_AXI_LITE_WRITES[] = {{",
        ]
    )
    for register_key, address, data, strb in rows:
        lines.append(
            f"  {{ {_hex(address)}, {_hex(data)}, {_hex(strb)} }},"
            f" /* {register_key} */"
        )
    lines.extend(
        [
            "};",
            "",
            f"static inline uint32_t {prefix.lower()}_load_u16_le(const uint8_t *bytes) {{",
            "  return ((uint32_t)bytes[0]) | ((uint32_t)bytes[1] << 8);",
            "}",
            "",
            f"static inline uint32_t {prefix.lower()}_load_u32_le(const uint8_t *bytes) {{",
            "  return ((uint32_t)bytes[0]) |",
            "         ((uint32_t)bytes[1] << 8) |",
            "         ((uint32_t)bytes[2] << 16) |",
            "         ((uint32_t)bytes[3] << 24);",
            "}",
            "",
            f"static inline int32_t {prefix.lower()}_sign_extend_trace_value(uint32_t raw) {{",
            f"  const uint32_t value = raw & {prefix}_TRACE_VALUE_MASK;",
            f"  const uint32_t sign = 1u << ({prefix}_TRACE_VALUE_BITS - 1u);",
            "  if ((value & sign) == 0u) {",
            "    return (int32_t)value;",
            "  }",
            f"  const uint32_t magnitude = ((~value) & {prefix}_TRACE_VALUE_MASK) + 1u;",
            "  if (magnitude == (1u << 31)) {",
            "    return (-2147483647 - 1);",
            "  }",
            "  return -(int32_t)magnitude;",
            "}",
            "",
            f"static inline {prefix.lower()}_stage_trace_t {prefix.lower()}_decode_trace_word(const uint8_t *bytes) {{",
            f"  {prefix.lower()}_stage_trace_t trace;",
            f"  trace.stage = (uint32_t)bytes[{prefix}_TRACE_STAGE_BYTE_OFFSET] & {prefix}_TRACE_STAGE_MASK;",
            f"  trace.group = {prefix.lower()}_load_u16_le(bytes + {prefix}_TRACE_GROUP_BYTE_OFFSET) & {prefix}_TRACE_GROUP_MASK;",
            f"  trace.index = {prefix.lower()}_load_u32_le(bytes + {prefix}_TRACE_INDEX_BYTE_OFFSET) & {prefix}_TRACE_INDEX_MASK;",
            f"  trace.value = {prefix.lower()}_sign_extend_trace_value(",
            f"    {prefix.lower()}_load_u32_le(bytes + {prefix}_TRACE_VALUE_BYTE_OFFSET));",
            "  return trace;",
            "}",
            "",
            "#if defined(__cplusplus)",
            "static_assert("
            f"{prefix}_STREAM_BYTE_COUNT == {prefix}_STREAM_WORD_COUNT * {prefix}_STREAM_WORD_BYTES, "
            '"stream byte count mismatch");',
            "static_assert("
            f"{prefix}_INPUT_TKEEP_MASK == ((1u << {prefix}_STREAM_WORD_BYTES) - 1u), "
            '"input TKEEP mask mismatch");',
            "static_assert("
            f"{prefix}_AXI_LITE_STRB_BITS == {prefix}_AXI_LITE_DATA_BITS / 8u, "
            '"AXI-Lite strobe width mismatch");',
            "static_assert("
            f"{prefix}_TRACE_GROUP_SHIFT == {prefix}_TRACE_STAGE_SHIFT + {prefix}_TRACE_STAGE_BITS, "
            '"trace group shift mismatch");',
            "static_assert("
            f"{prefix}_TRACE_INDEX_SHIFT == {prefix}_TRACE_GROUP_SHIFT + {prefix}_TRACE_GROUP_BITS, "
            '"trace index shift mismatch");',
            "static_assert("
            f"{prefix}_TRACE_VALUE_SHIFT == {prefix}_TRACE_INDEX_SHIFT + {prefix}_TRACE_INDEX_BITS, "
            '"trace value shift mismatch");',
            "static_assert("
            f"{prefix}_TRACE_PACKED_BITS == {prefix}_TRACE_VALUE_SHIFT + {prefix}_TRACE_VALUE_BITS, "
            '"trace packed bit count mismatch");',
            "static_assert("
            f"{prefix}_TRACE_VALUE_BITS > 0u && {prefix}_TRACE_VALUE_BITS <= 32u, "
            '"trace value width unsupported by generated C decoder");',
            "static_assert("
            f"{prefix}_TRACE_STAGE_SHIFT % 8u == 0u && "
            f"{prefix}_TRACE_GROUP_SHIFT % 8u == 0u && "
            f"{prefix}_TRACE_INDEX_SHIFT % 8u == 0u && "
            f"{prefix}_TRACE_VALUE_SHIFT % 8u == 0u, "
            '"trace field byte alignment mismatch");',
            "static_assert("
            f"{prefix}_TRACE_STAGE_BYTE_OFFSET == {prefix}_TRACE_STAGE_SHIFT / 8u && "
            f"{prefix}_TRACE_GROUP_BYTE_OFFSET == {prefix}_TRACE_GROUP_SHIFT / 8u && "
            f"{prefix}_TRACE_INDEX_BYTE_OFFSET == {prefix}_TRACE_INDEX_SHIFT / 8u && "
            f"{prefix}_TRACE_VALUE_BYTE_OFFSET == {prefix}_TRACE_VALUE_SHIFT / 8u, "
            '"trace field byte offset mismatch");',
            "static_assert("
            f"sizeof({prefix}_AXI_LITE_WRITES) / sizeof({prefix}_AXI_LITE_WRITES[0]) == "
            f"{prefix}_AXI_LITE_WRITE_COUNT, "
            '"AXI-Lite write count mismatch");',
            "static_assert("
            f"{prefix}_TRACE_PACKED_BYTES == ({prefix}_TRACE_PACKED_BITS + 7u) / 8u, "
            '"trace packed byte count mismatch");',
            "static_assert("
            f"{prefix}_TRACE_TKEEP_MASK == ((1u << {prefix}_TRACE_PACKED_BYTES) - 1u), "
            '"trace TKEEP mask mismatch");',
            "static_assert("
            f"sizeof({prefix}_SUPPORTED_DISTANCE_Q8) / sizeof({prefix}_SUPPORTED_DISTANCE_Q8[0]) == "
            f"{prefix}_SUPPORTED_DISTANCE_Q8_COUNT, "
            '"supported distance count mismatch");',
            "#elif defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L",
            "_Static_assert("
            f"{prefix}_STREAM_BYTE_COUNT == {prefix}_STREAM_WORD_COUNT * {prefix}_STREAM_WORD_BYTES, "
            '"stream byte count mismatch");',
            "_Static_assert("
            f"{prefix}_INPUT_TKEEP_MASK == ((1u << {prefix}_STREAM_WORD_BYTES) - 1u), "
            '"input TKEEP mask mismatch");',
            "_Static_assert("
            f"{prefix}_AXI_LITE_STRB_BITS == {prefix}_AXI_LITE_DATA_BITS / 8u, "
            '"AXI-Lite strobe width mismatch");',
            "_Static_assert("
            f"{prefix}_TRACE_GROUP_SHIFT == {prefix}_TRACE_STAGE_SHIFT + {prefix}_TRACE_STAGE_BITS, "
            '"trace group shift mismatch");',
            "_Static_assert("
            f"{prefix}_TRACE_INDEX_SHIFT == {prefix}_TRACE_GROUP_SHIFT + {prefix}_TRACE_GROUP_BITS, "
            '"trace index shift mismatch");',
            "_Static_assert("
            f"{prefix}_TRACE_VALUE_SHIFT == {prefix}_TRACE_INDEX_SHIFT + {prefix}_TRACE_INDEX_BITS, "
            '"trace value shift mismatch");',
            "_Static_assert("
            f"{prefix}_TRACE_PACKED_BITS == {prefix}_TRACE_VALUE_SHIFT + {prefix}_TRACE_VALUE_BITS, "
            '"trace packed bit count mismatch");',
            "_Static_assert("
            f"{prefix}_TRACE_VALUE_BITS > 0u && {prefix}_TRACE_VALUE_BITS <= 32u, "
            '"trace value width unsupported by generated C decoder");',
            "_Static_assert("
            f"{prefix}_TRACE_STAGE_SHIFT % 8u == 0u && "
            f"{prefix}_TRACE_GROUP_SHIFT % 8u == 0u && "
            f"{prefix}_TRACE_INDEX_SHIFT % 8u == 0u && "
            f"{prefix}_TRACE_VALUE_SHIFT % 8u == 0u, "
            '"trace field byte alignment mismatch");',
            "_Static_assert("
            f"{prefix}_TRACE_STAGE_BYTE_OFFSET == {prefix}_TRACE_STAGE_SHIFT / 8u && "
            f"{prefix}_TRACE_GROUP_BYTE_OFFSET == {prefix}_TRACE_GROUP_SHIFT / 8u && "
            f"{prefix}_TRACE_INDEX_BYTE_OFFSET == {prefix}_TRACE_INDEX_SHIFT / 8u && "
            f"{prefix}_TRACE_VALUE_BYTE_OFFSET == {prefix}_TRACE_VALUE_SHIFT / 8u, "
            '"trace field byte offset mismatch");',
            "_Static_assert("
            f"sizeof({prefix}_AXI_LITE_WRITES) / sizeof({prefix}_AXI_LITE_WRITES[0]) == "
            f"{prefix}_AXI_LITE_WRITE_COUNT, "
            '"AXI-Lite write count mismatch");',
            "_Static_assert("
            f"{prefix}_TRACE_PACKED_BYTES == ({prefix}_TRACE_PACKED_BITS + 7u) / 8u, "
            '"trace packed byte count mismatch");',
            "_Static_assert("
            f"{prefix}_TRACE_TKEEP_MASK == ((1u << {prefix}_TRACE_PACKED_BYTES) - 1u), "
            '"trace TKEEP mask mismatch");',
            "_Static_assert("
            f"sizeof({prefix}_SUPPORTED_DISTANCE_Q8) / sizeof({prefix}_SUPPORTED_DISTANCE_Q8[0]) == "
            f"{prefix}_SUPPORTED_DISTANCE_Q8_COUNT, "
            '"supported distance count mismatch");',
            "#endif",
            "",
            f"#endif /* {guard} */",
            "",
        ]
    )
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest-json", type=Path, required=True, help="HJXL stream/control manifest")
    parser.add_argument("--header", type=Path, required=True, help="output C header path")
    parser.add_argument(
        "--symbol-prefix",
        default="HJXL_BUNDLE",
        help="C symbol prefix for generated macros and objects",
    )
    parser.add_argument("--include-guard", help="explicit C include guard")
    args = parser.parse_args()

    try:
        with args.manifest_json.open("r", encoding="utf-8") as handle:
            manifest = json.load(handle)
        text = header_text(
            manifest,
            symbol_prefix=args.symbol_prefix,
            include_guard=args.include_guard,
        )
        args.header.parent.mkdir(parents=True, exist_ok=True)
        args.header.write_text(text, encoding="utf-8")
    except (OSError, KeyError, TypeError, ValueError) as exc:
        raise SystemExit(f"error: {exc}") from exc

    print(f"wrote {args.header}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
