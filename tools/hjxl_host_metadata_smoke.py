#!/usr/bin/env python3
"""Smoke-test prepared-DCT host bundle and replay metadata propagation."""

from __future__ import annotations

import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from hjxl_abi import discovery_metadata, rgb_active_route
from hjxl_abi_generated import (
    DISCOVERY_ABI_VERSION,
    REGISTER_MAP,
    ROUTE_IDS,
    TRACE_STAGES,
)
from hjxl_host_bundle import (
    describe_host_bundle,
    validate_host_bundle,
    validate_replay_plan,
    write_host_bundle,
)
from hjxl_prepared_blocks import (
    TOKEN_SELECT,
    flags_word,
    load_fixture,
    validate_manifest_json,
    write_axi_lite_csv,
    write_input_stream_csv,
    write_manifest_json,
)
from hjxl_rgb_stream import (
    TOKEN_SELECT as RGB_TOKEN_SELECT,
    flags_word as rgb_flags_word,
    write_axi_lite_csv as write_rgb_axi_lite_csv,
    write_manifest_json as write_rgb_manifest_json,
    write_stream_csv as write_rgb_stream_csv,
)
from hjxl_replay_capture import (
    _axi_lite_summary,
    _check_expected_axi_lite,
    _check_expected_frame,
    _check_expected_input_stream,
    _check_expected_register_map,
    _check_expected_status_bits,
    _check_expected_trace,
    _frame_summary,
    _input_stream_summary,
    _status_bits_summary,
)


EXPECTED_STREAM = {
    "word_count": 201,
    "input_data_bits": 32,
    "word_bytes": 4,
    "input_keep_mask": 0xF,
    "byte_count": 804,
    "coefficient_fraction_bits": 16,
}

EXPECTED_RGB_STREAM = {
    "word_count": 4,
    "input_data_bits": 48,
    "word_bytes": 6,
    "input_keep_mask": 0x3F,
    "byte_count": 24,
}

EXPECTED_TRACE = {
    "stage_bits": 8,
    "group_bits": 16,
    "index_bits": 32,
    "trace_value_bits": 32,
    "stage_shift": 0,
    "group_shift": 8,
    "index_shift": 24,
    "trace_value_shift": 56,
    "stage_byte_offset": 0,
    "group_byte_offset": 1,
    "index_byte_offset": 3,
    "trace_value_byte_offset": 7,
    "stage_mask": 0xFF,
    "group_mask": 0xFFFF,
    "index_mask": 0xFFFFFFFF,
    "trace_value_mask": 0xFFFFFFFF,
    "packed_bits": 88,
    "packed_bytes": 11,
    "tkeep_mask": 0x7FF,
    "capture_word_bytes": 16,
}

EXPECTED_AXI_LITE = {
    "addr_bits": 8,
    "data_bits": 32,
    "strb_bits": 4,
    "write_count": 9,
}

EXPECTED_REGISTER_MAP = dict(REGISTER_MAP)

EXPECTED_DISCOVERY = discovery_metadata(
    profile="prepared-direct",
    active_route=ROUTE_IDS["prepared-direct"],
    width=8,
    height=8,
)
EXPECTED_ESTIMATED_DISCOVERY = discovery_metadata(
    profile="prepared-estimated-cfl",
    active_route=ROUTE_IDS["prepared-estimated-cfl"],
    width=8,
    height=8,
)
EXPECTED_RGB_DISCOVERY = discovery_metadata(
    profile="rgb",
    active_route=3,
    width=2,
    height=2,
)

EXPECTED_FRAME = {
    "xsize": 8,
    "ysize": 8,
    "x_blocks": 1,
    "y_blocks": 1,
    "padded_xsize": 8,
    "padded_ysize": 8,
    "block_count": 1,
    "x_tiles": 1,
    "y_tiles": 1,
    "tile_count": 1,
}

EXPECTED_RGB_FRAME = {
    "xsize": 2,
    "ysize": 2,
    "x_blocks": 1,
    "y_blocks": 1,
    "padded_xsize": 8,
    "padded_ysize": 8,
    "block_count": 1,
    "x_tiles": 1,
    "y_tiles": 1,
    "tile_count": 1,
}

EXPECTED_STATUS_BITS = {
    "protocol_error": 0,
    "busy": 1,
    "overflow": 2,
    "unsupported_distance": 3,
    "clear_protocol_error_write_bit": 0,
}

EXPECTED_FIXED_YTOX = -7
EXPECTED_FIXED_YTOB = 11


def _zero_coefficients() -> list[int]:
    return [0] * 64


def _fixture() -> dict:
    return {
        "format": "hjxl.dct_only_prepared_blocks.v1",
        "coefficient_fraction_bits": 16,
        "image": {"x_blocks": 1, "y_blocks": 1},
        "blocks": [
            {
                "block_index": 0,
                "inputs": {
                    "coefficient_fraction_bits": 16,
                    "quant": 5,
                    "scale_q16": 1234,
                    "inv_qac_q16": 5678,
                    "inv_dc_factor_q16": [11, 22, 33],
                    "x_qm_multiplier_q16": 44,
                    "ytox": -2,
                    "ytob": 3,
                    "coefficients_q": [
                        list(range(64)),
                        list(range(100, 164)),
                        list(range(-64, 0)),
                    ],
                },
                "expected": {
                    "quantized_ac": [
                        _zero_coefficients(),
                        _zero_coefficients(),
                        _zero_coefficients(),
                    ],
                    "quantized_dc": [0, 0, 0],
                    "num_nonzeros": [0, 0, 0],
                },
            }
        ],
    }


def _write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _compile_generated_header(header: Path, symbol_prefix: str) -> None:
    expected_ytox = EXPECTED_FIXED_YTOX & 0xFFFFFFFF
    expected_ytob = EXPECTED_FIXED_YTOB & 0xFFFFFFFF
    declarations = f"""
#include "{header}"

enum {{
  stream_words = {symbol_prefix}_STREAM_WORD_COUNT,
  stream_bytes = {symbol_prefix}_STREAM_BYTE_COUNT,
  frame_blocks = {symbol_prefix}_FRAME_BLOCK_COUNT,
  frame_tiles = {symbol_prefix}_FRAME_TILE_COUNT,
  axi_lite_addr_bits = {symbol_prefix}_AXI_LITE_ADDR_BITS,
  axi_lite_data_bits = {symbol_prefix}_AXI_LITE_DATA_BITS,
  axi_lite_strb_bits = {symbol_prefix}_AXI_LITE_STRB_BITS,
  input_keep_enforced = {symbol_prefix}_INPUT_TKEEP_ENFORCED,
  trace_keep = {symbol_prefix}_TRACE_TKEEP_MASK,
}};

int main(void) {{
  const uint8_t word[16] = {{
    0x05, 0x34, 0x12, 0xef, 0xcd, 0xab, 0x89, 0xfd,
    0xff, 0xff, 0xff, 0, 0, 0, 0, 0
  }};
  const uint8_t min_word[16] = {{
    0x06, 0x78, 0x56, 0x01, 0xef, 0xcd, 0xab, 0x00,
    0x00, 0x00, 0x80, 0, 0, 0, 0, 0
  }};
  {symbol_prefix.lower()}_stage_trace_t trace = {symbol_prefix.lower()}_decode_trace_word(word);
  if ({symbol_prefix}_AXI_LITE_WRITE_COUNT != 9u ||
      {symbol_prefix}_AXI_LITE_WRITES[7].address != {symbol_prefix}_REG_FIXED_YTOX ||
      {symbol_prefix}_AXI_LITE_WRITES[7].data != {expected_ytox:#010x}u ||
      {symbol_prefix}_AXI_LITE_WRITES[7].strb != 0x0fu ||
      {symbol_prefix}_AXI_LITE_WRITES[8].address != {symbol_prefix}_REG_FIXED_YTOB ||
      {symbol_prefix}_AXI_LITE_WRITES[8].data != {expected_ytob:#010x}u ||
      {symbol_prefix}_AXI_LITE_WRITES[8].strb != 0x0fu ||
      trace.stage != 5u || trace.group != 0x1234u || trace.index != 0x89abcdefu || trace.value != -3) {{
    return 1;
  }}
  trace = {symbol_prefix.lower()}_decode_trace_word(min_word);
  if (trace.stage != 6u || trace.group != 0x5678u || trace.index != 0xabcdef01u || trace.value != (-2147483647 - 1)) {{
    return 2;
  }}
  return (int)(stream_words + stream_bytes + frame_blocks + frame_tiles + axi_lite_addr_bits + axi_lite_data_bits + axi_lite_strb_bits + input_keep_enforced + trace_keep) == 0;
}}
""".lstrip()
    c_source = header.with_name(f"{header.stem}-smoke.c")
    cxx_source = header.with_name(f"{header.stem}-smoke.cc")
    c_source.write_text(declarations, encoding="utf-8")
    cxx_source.write_text(declarations, encoding="utf-8")
    cc = shutil.which("cc")
    if cc is not None:
        subprocess.run([cc, "-std=c11", "-fsyntax-only", str(c_source)], check=True)
    cxx = shutil.which("c++")
    if cxx is not None:
        subprocess.run([cxx, "-std=c++11", "-fsyntax-only", str(cxx_source)], check=True)


def _expect_error(label: str, func, expected_fragment: str) -> None:
    try:
        func()
    except ValueError as exc:
        message = str(exc)
        if expected_fragment not in message:
            raise AssertionError(
                f"{label}: expected error containing {expected_fragment!r}, got {message!r}"
            ) from exc
        return
    raise AssertionError(f"{label}: expected ValueError containing {expected_fragment!r}")


def _assert_stream_metadata(plan: dict) -> None:
    stream = plan["stream"]
    expected_index_stream = {
        "word_count": EXPECTED_STREAM["word_count"],
        "input_data_bits": EXPECTED_STREAM["input_data_bits"],
        "input_data_bytes": EXPECTED_STREAM["word_bytes"],
        "input_keep_mask": EXPECTED_STREAM["input_keep_mask"],
        "byte_count": EXPECTED_STREAM["byte_count"],
        "coefficient_fraction_bits": EXPECTED_STREAM["coefficient_fraction_bits"],
    }
    actual_index_stream = {
        "word_count": stream["word_count"],
        "input_data_bits": stream["input_data_bits"],
        "input_data_bytes": stream["word_bytes"],
        "input_keep_mask": stream["input_keep_mask"],
        "byte_count": stream["byte_count"],
        "coefficient_fraction_bits": stream["coefficient_fraction_bits"],
    }
    if actual_index_stream != expected_index_stream:
        raise AssertionError(
            f"unexpected replay stream metadata: {actual_index_stream!r}"
        )
    summary = _input_stream_summary(plan)
    if summary != EXPECTED_STREAM:
        raise AssertionError(f"unexpected capture input stream summary: {summary!r}")


def _assert_fixed_cfl_writes(plan: dict) -> None:
    writes = {write["register"]: write for write in plan["axi_lite"]["writes"]}
    expected = {
        "fixed_ytox": (EXPECTED_REGISTER_MAP["fixed_ytox"], EXPECTED_FIXED_YTOX & 0xFFFFFFFF),
        "fixed_ytob": (EXPECTED_REGISTER_MAP["fixed_ytob"], EXPECTED_FIXED_YTOB & 0xFFFFFFFF),
    }
    for register, (address, data) in expected.items():
        write = writes.get(register)
        if write is None:
            raise AssertionError(f"missing AXI-Lite write for {register}")
        actual = (int(write["address"]), int(write["data"]), int(write["strb"]))
        expected_tuple = (address, data, 0xF)
        if actual != expected_tuple:
            raise AssertionError(
                f"unexpected AXI-Lite write for {register}: {actual!r}, expected {expected_tuple!r}"
            )


def _check_replay_expectations(plan: dict, *, expected_discovery: dict = EXPECTED_DISCOVERY) -> None:
    if plan.get("discovery") != expected_discovery:
        raise AssertionError(f"unexpected discovery metadata: {plan.get('discovery')!r}")
    frame_summary = _frame_summary(plan)
    if frame_summary != EXPECTED_FRAME:
        raise AssertionError(f"unexpected frame summary: {frame_summary!r}")
    _check_expected_frame(
        plan,
        xsize=EXPECTED_FRAME["xsize"],
        ysize=EXPECTED_FRAME["ysize"],
        x_blocks=EXPECTED_FRAME["x_blocks"],
        y_blocks=EXPECTED_FRAME["y_blocks"],
        padded_xsize=EXPECTED_FRAME["padded_xsize"],
        padded_ysize=EXPECTED_FRAME["padded_ysize"],
        block_count=EXPECTED_FRAME["block_count"],
        x_tiles=EXPECTED_FRAME["x_tiles"],
        y_tiles=EXPECTED_FRAME["y_tiles"],
        tile_count=EXPECTED_FRAME["tile_count"],
    )
    _expect_error(
        "frame block-count mismatch",
        lambda: _check_expected_frame(
            plan,
            xsize=None,
            ysize=None,
            x_blocks=None,
            y_blocks=None,
            padded_xsize=None,
            padded_ysize=None,
            block_count=2,
            x_tiles=None,
            y_tiles=None,
            tile_count=None,
        ),
        "replay plan frame block_count expected 2, got 1",
    )
    _expect_error(
        "nonpositive frame expectation",
        lambda: _check_expected_frame(
            plan,
            xsize=0,
            ysize=None,
            x_blocks=None,
            y_blocks=None,
            padded_xsize=None,
            padded_ysize=None,
            block_count=None,
            x_tiles=None,
            y_tiles=None,
            tile_count=None,
        ),
        "--expect-frame-xsize must be positive",
    )
    status_summary = _status_bits_summary(plan)
    if status_summary != EXPECTED_STATUS_BITS:
        raise AssertionError(f"unexpected status-bits summary: {status_summary!r}")
    _check_expected_status_bits(
        plan,
        protocol_error=EXPECTED_STATUS_BITS["protocol_error"],
        busy=EXPECTED_STATUS_BITS["busy"],
        overflow=EXPECTED_STATUS_BITS["overflow"],
        unsupported_distance=EXPECTED_STATUS_BITS["unsupported_distance"],
        clear_protocol_error_write_bit=EXPECTED_STATUS_BITS["clear_protocol_error_write_bit"],
    )
    _expect_error(
        "status unsupported-distance bit mismatch",
        lambda: _check_expected_status_bits(
            plan,
            protocol_error=None,
            busy=None,
            overflow=None,
            unsupported_distance=4,
            clear_protocol_error_write_bit=None,
        ),
        "replay plan status_bits unsupported_distance expected 4, got 3",
    )
    _expect_error(
        "negative status expectation",
        lambda: _check_expected_status_bits(
            plan,
            protocol_error=-1,
            busy=None,
            overflow=None,
            unsupported_distance=None,
            clear_protocol_error_write_bit=None,
        ),
        "--expect-status-protocol-error-bit must be nonnegative",
    )
    summary = _axi_lite_summary(plan)
    expected_axi_lite_summary = EXPECTED_AXI_LITE | {"register_map": EXPECTED_REGISTER_MAP}
    if summary != expected_axi_lite_summary:
        raise AssertionError(f"unexpected AXI-Lite summary: {summary!r}")
    _assert_fixed_cfl_writes(plan)
    _check_expected_axi_lite(
        plan,
        addr_bits=EXPECTED_AXI_LITE["addr_bits"],
        data_bits=EXPECTED_AXI_LITE["data_bits"],
        strb_bits=EXPECTED_AXI_LITE["strb_bits"],
        write_count=EXPECTED_AXI_LITE["write_count"],
    )
    _expect_error(
        "AXI-Lite data-width mismatch",
        lambda: _check_expected_axi_lite(
            plan,
            addr_bits=None,
            data_bits=64,
            strb_bits=None,
            write_count=None,
        ),
        "replay plan AXI-Lite data_bits expected 64, got 32",
    )
    _expect_error(
        "nonpositive AXI-Lite expectation",
        lambda: _check_expected_axi_lite(
            plan,
            addr_bits=None,
            data_bits=None,
            strb_bits=0,
            write_count=None,
        ),
        "--expect-axi-lite-strb-bits must be positive",
    )
    _check_expected_register_map(
        plan,
        status_control=EXPECTED_REGISTER_MAP["status_control"],
        xsize=EXPECTED_REGISTER_MAP["xsize"],
        ysize=EXPECTED_REGISTER_MAP["ysize"],
        distance_q8=EXPECTED_REGISTER_MAP["distance_q8"],
        fixed_point_scale=EXPECTED_REGISTER_MAP["fixed_point_scale"],
        fixed_inv_qac_q16=EXPECTED_REGISTER_MAP["fixed_inv_qac_q16"],
        fixed_raw_quant=EXPECTED_REGISTER_MAP["fixed_raw_quant"],
        flags=EXPECTED_REGISTER_MAP["flags"],
        fixed_ytox=EXPECTED_REGISTER_MAP["fixed_ytox"],
        fixed_ytob=EXPECTED_REGISTER_MAP["fixed_ytob"],
    )
    _expect_error(
        "register-map flags mismatch",
        lambda: _check_expected_register_map(
            plan,
            status_control=None,
            xsize=None,
            ysize=None,
            distance_q8=None,
            fixed_point_scale=None,
            fixed_inv_qac_q16=None,
            fixed_raw_quant=None,
            flags=0x20,
            fixed_ytox=None,
            fixed_ytob=None,
        ),
        "replay plan AXI-Lite register_map flags expected 32, got 28",
    )
    _expect_error(
        "negative register-map expectation",
        lambda: _check_expected_register_map(
            plan,
            status_control=-1,
            xsize=None,
            ysize=None,
            distance_q8=None,
            fixed_point_scale=None,
            fixed_inv_qac_q16=None,
            fixed_raw_quant=None,
            flags=None,
            fixed_ytox=None,
            fixed_ytob=None,
        ),
        "--expect-reg-status-control must be nonnegative",
    )
    _check_expected_trace(
        plan,
        stage_bits=EXPECTED_TRACE["stage_bits"],
        group_bits=EXPECTED_TRACE["group_bits"],
        index_bits=EXPECTED_TRACE["index_bits"],
        trace_value_bits=EXPECTED_TRACE["trace_value_bits"],
        stage_shift=EXPECTED_TRACE["stage_shift"],
        group_shift=EXPECTED_TRACE["group_shift"],
        index_shift=EXPECTED_TRACE["index_shift"],
        trace_value_shift=EXPECTED_TRACE["trace_value_shift"],
        stage_byte_offset=EXPECTED_TRACE["stage_byte_offset"],
        group_byte_offset=EXPECTED_TRACE["group_byte_offset"],
        index_byte_offset=EXPECTED_TRACE["index_byte_offset"],
        trace_value_byte_offset=EXPECTED_TRACE["trace_value_byte_offset"],
        stage_mask=EXPECTED_TRACE["stage_mask"],
        group_mask=EXPECTED_TRACE["group_mask"],
        index_mask=EXPECTED_TRACE["index_mask"],
        trace_value_mask=EXPECTED_TRACE["trace_value_mask"],
        packed_bits=EXPECTED_TRACE["packed_bits"],
        packed_bytes=EXPECTED_TRACE["packed_bytes"],
        keep_mask=EXPECTED_TRACE["tkeep_mask"],
        capture_word_bytes=EXPECTED_TRACE["capture_word_bytes"],
    )
    _expect_error(
        "trace packed-byte mismatch",
        lambda: _check_expected_trace(
            plan,
            stage_bits=None,
            group_bits=None,
            index_bits=None,
            trace_value_bits=None,
            stage_shift=None,
            group_shift=None,
            index_shift=None,
            trace_value_shift=None,
            stage_byte_offset=None,
            group_byte_offset=None,
            index_byte_offset=None,
            trace_value_byte_offset=None,
            stage_mask=None,
            group_mask=None,
            index_mask=None,
            trace_value_mask=None,
            packed_bits=None,
            packed_bytes=EXPECTED_TRACE["packed_bytes"] + 1,
            keep_mask=None,
            capture_word_bytes=None,
        ),
        "replay plan trace packed_bytes expected 12, got 11",
    )
    _expect_error(
        "trace keep-mask mismatch",
        lambda: _check_expected_trace(
            plan,
            stage_bits=None,
            group_bits=None,
            index_bits=None,
            trace_value_bits=None,
            stage_shift=None,
            group_shift=None,
            index_shift=None,
            trace_value_shift=None,
            stage_byte_offset=None,
            group_byte_offset=None,
            index_byte_offset=None,
            trace_value_byte_offset=None,
            stage_mask=None,
            group_mask=None,
            index_mask=None,
            trace_value_mask=None,
            packed_bits=None,
            packed_bytes=None,
            keep_mask=0x3FF,
            capture_word_bytes=None,
        ),
        "replay plan trace tkeep_mask expected 1023, got 2047",
    )
    _expect_error(
        "nonpositive trace expectation",
        lambda: _check_expected_trace(
            plan,
            stage_bits=None,
            group_bits=0,
            index_bits=None,
            trace_value_bits=None,
            stage_shift=None,
            group_shift=None,
            index_shift=None,
            trace_value_shift=None,
            stage_byte_offset=None,
            group_byte_offset=None,
            index_byte_offset=None,
            trace_value_byte_offset=None,
            stage_mask=None,
            group_mask=None,
            index_mask=None,
            trace_value_mask=None,
            packed_bits=None,
            packed_bytes=None,
            keep_mask=None,
            capture_word_bytes=None,
        ),
        "--expect-trace-group-bits must be positive",
    )
    _expect_error(
        "negative trace shift expectation",
        lambda: _check_expected_trace(
            plan,
            stage_bits=None,
            group_bits=None,
            index_bits=None,
            trace_value_bits=None,
            stage_shift=-1,
            group_shift=None,
            index_shift=None,
            trace_value_shift=None,
            stage_byte_offset=None,
            group_byte_offset=None,
            index_byte_offset=None,
            trace_value_byte_offset=None,
            stage_mask=None,
            group_mask=None,
            index_mask=None,
            trace_value_mask=None,
            packed_bits=None,
            packed_bytes=None,
            keep_mask=None,
            capture_word_bytes=None,
        ),
        "--expect-trace-stage-shift must be nonnegative",
    )
    _check_expected_input_stream(
        plan,
        word_count=EXPECTED_STREAM["word_count"],
        input_data_bits=EXPECTED_STREAM["input_data_bits"],
        word_bytes=EXPECTED_STREAM["word_bytes"],
        input_keep_mask=EXPECTED_STREAM["input_keep_mask"],
        byte_count=EXPECTED_STREAM["byte_count"],
        coefficient_fraction_bits=EXPECTED_STREAM["coefficient_fraction_bits"],
    )
    _expect_error(
        "word-count mismatch",
        lambda: _check_expected_input_stream(
            plan,
            word_count=EXPECTED_STREAM["word_count"] - 1,
            input_data_bits=None,
            word_bytes=None,
            input_keep_mask=None,
            byte_count=None,
            coefficient_fraction_bits=None,
        ),
        "replay plan stream word_count expected 200, got 201",
    )
    _expect_error(
        "nonpositive expectation",
        lambda: _check_expected_input_stream(
            plan,
            word_count=0,
            input_data_bits=None,
            word_bytes=None,
            input_keep_mask=None,
            byte_count=None,
            coefficient_fraction_bits=None,
        ),
        "--expect-input-word-count must be positive",
    )


def _check_legacy_replay_plan(plan_path: Path, plan: dict) -> None:
    invalid_trace_route_plan = copy.deepcopy(plan)
    invalid_trace_route_plan["trace_route"]["name"] = "all"
    invalid_trace_route_plan["trace_route"]["stage"] = None
    invalid_trace_route_plan["trace_route"]["focused"] = False
    _write_json(plan_path, invalid_trace_route_plan)
    _expect_error(
        "replay trace-route metadata mismatch",
        lambda: validate_replay_plan(plan_path),
        "replay plan does not match described bundle",
    )

    legacy_frame_plan = copy.deepcopy(plan)
    legacy_frame_plan["frame"].pop("x_tiles")
    legacy_frame_plan["frame"].pop("y_tiles")
    legacy_frame_plan["frame"].pop("tile_count")
    _write_json(plan_path, legacy_frame_plan)
    validate_replay_plan(plan_path)

    legacy_plan = copy.deepcopy(plan)
    legacy_plan.pop("frame")
    legacy_plan.pop("status_bits")
    legacy_plan["stream"].pop("coefficient_fraction_bits")
    legacy_plan["stream"].pop("input_data_bits")
    legacy_plan["stream"].pop("input_keep_mask")
    legacy_plan["axi_lite"].pop("addr_bits")
    legacy_plan["axi_lite"].pop("data_bits")
    legacy_plan["axi_lite"].pop("strb_bits")
    legacy_plan["axi_lite"].pop("register_map")
    _write_json(plan_path, legacy_plan)
    validate_replay_plan(plan_path)
    _expect_error(
        "missing frame metadata",
        lambda: _check_expected_frame(
            legacy_plan,
            xsize=EXPECTED_FRAME["xsize"],
            ysize=None,
            x_blocks=None,
            y_blocks=None,
            padded_xsize=None,
            padded_ysize=None,
            block_count=None,
            x_tiles=None,
            y_tiles=None,
            tile_count=None,
        ),
        "replay plan is missing frame metadata",
    )
    _expect_error(
        "missing status-bits metadata",
        lambda: _check_expected_status_bits(
            legacy_plan,
            protocol_error=EXPECTED_STATUS_BITS["protocol_error"],
            busy=None,
            overflow=None,
            unsupported_distance=None,
            clear_protocol_error_write_bit=None,
        ),
        "replay plan is missing status_bits metadata",
    )
    _expect_error(
        "missing AXI-Lite metadata",
        lambda: _check_expected_axi_lite(
            legacy_plan,
            addr_bits=EXPECTED_AXI_LITE["addr_bits"],
            data_bits=None,
            strb_bits=None,
            write_count=None,
        ),
        "replay plan AXI-Lite metadata is missing addr_bits",
    )
    _expect_error(
        "missing register-map metadata",
        lambda: _check_expected_register_map(
            legacy_plan,
            status_control=EXPECTED_REGISTER_MAP["status_control"],
            xsize=None,
            ysize=None,
            distance_q8=None,
            fixed_point_scale=None,
            fixed_inv_qac_q16=None,
            fixed_raw_quant=None,
            flags=None,
            fixed_ytox=None,
            fixed_ytob=None,
        ),
        "replay plan AXI-Lite metadata is missing register_map",
    )
    _expect_error(
        "missing coefficient metadata",
        lambda: _check_expected_input_stream(
            legacy_plan,
            word_count=None,
            input_data_bits=None,
            word_bytes=None,
            input_keep_mask=None,
            byte_count=None,
            coefficient_fraction_bits=EXPECTED_STREAM["coefficient_fraction_bits"],
        ),
        "replay plan stream metadata is missing coefficient_fraction_bits",
    )
    legacy_trace_plan = copy.deepcopy(plan)
    legacy_trace_plan.pop("trace")
    _expect_error(
        "missing trace metadata",
        lambda: _check_expected_trace(
            legacy_trace_plan,
            stage_bits=None,
            group_bits=None,
            index_bits=None,
            trace_value_bits=None,
            stage_shift=None,
            group_shift=None,
            index_shift=None,
            trace_value_shift=None,
            stage_byte_offset=None,
            group_byte_offset=None,
            index_byte_offset=None,
            trace_value_byte_offset=None,
            stage_mask=None,
            group_mask=None,
            index_mask=None,
            trace_value_mask=None,
            packed_bits=None,
            packed_bytes=None,
            keep_mask=None,
            capture_word_bytes=EXPECTED_TRACE["capture_word_bytes"],
        ),
        "replay plan is missing trace metadata",
    )


def _check_bundle_index_compatibility(index_path: Path) -> None:
    original = json.loads(index_path.read_text(encoding="utf-8"))

    legacy_frame_index = copy.deepcopy(original)
    legacy_frame_index["frame"].pop("x_tiles")
    legacy_frame_index["frame"].pop("y_tiles")
    legacy_frame_index["frame"].pop("tile_count")
    _write_json(index_path, legacy_frame_index)
    validate_host_bundle(index_path)

    legacy_index = copy.deepcopy(original)
    legacy_index.pop("trace")
    legacy_index.pop("axi_lite")
    legacy_index.pop("frame")
    legacy_index["stream"].pop("coefficient_fraction_bits")
    legacy_index["stream"].pop("input_keep_mask")
    _write_json(index_path, legacy_index)
    validate_host_bundle(index_path)

    invalid_axi_lite_index = copy.deepcopy(original)
    invalid_axi_lite_index["axi_lite"]["data_bits"] = 64
    _write_json(index_path, invalid_axi_lite_index)
    _expect_error(
        "AXI-Lite metadata mismatch",
        lambda: validate_host_bundle(index_path),
        "axi_lite metadata does not match RTL control plane",
    )

    invalid_register_map_index = copy.deepcopy(original)
    invalid_register_map_index["axi_lite"]["register_map"]["flags"] = 0x20
    _write_json(index_path, invalid_register_map_index)
    _expect_error(
        "AXI-Lite register-map mismatch",
        lambda: validate_host_bundle(index_path),
        "axi_lite metadata does not match RTL control plane",
    )

    invalid_trace_index = copy.deepcopy(original)
    invalid_trace_index["trace"]["tkeep_mask"] = 0x3FF
    _write_json(index_path, invalid_trace_index)
    _expect_error(
        "trace metadata mismatch",
        lambda: validate_host_bundle(index_path),
        "trace metadata does not match RTL trace packing",
    )

    invalid_frame_index = copy.deepcopy(original)
    invalid_frame_index["frame"]["block_count"] = 2
    _write_json(index_path, invalid_frame_index)
    _expect_error(
        "frame metadata mismatch",
        lambda: validate_host_bundle(index_path),
        "frame metadata does not match source manifest",
    )

    invalid_trace_route_index = copy.deepcopy(original)
    invalid_trace_route_index["trace_route"]["name"] = "all"
    invalid_trace_route_index["trace_route"]["focused"] = False
    _write_json(index_path, invalid_trace_route_index)
    _expect_error(
        "trace-route metadata mismatch",
        lambda: validate_host_bundle(index_path),
        "trace_route metadata does not match source manifest",
    )

    invalid_keep_index = copy.deepcopy(original)
    invalid_keep_index["stream"]["input_keep_mask"] = 0x7
    _write_json(index_path, invalid_keep_index)
    _expect_error(
        "input keep mask mismatch",
        lambda: validate_host_bundle(index_path),
        "stream.input_keep_mask does not match source manifest",
    )

    invalid_index = copy.deepcopy(original)
    invalid_index["stream"]["coefficient_fraction_bits"] = 12
    _write_json(index_path, invalid_index)
    _expect_error(
        "coefficient scale mismatch",
        lambda: validate_host_bundle(index_path),
        "stream.coefficient_fraction_bits does not match source manifest",
    )

    _write_json(index_path, original)


def _check_bundle_relative_paths_win_over_cwd(index_path: Path) -> None:
    collision_cwd = index_path.parent.parent / "collision-cwd"
    collision_cwd.mkdir()
    (collision_cwd / "prepared-manifest.json").write_text("{}\n", encoding="utf-8")
    (collision_cwd / "prepared-stream.csv").write_text("data,last\n0,1\n", encoding="utf-8")
    (collision_cwd / "prepared-control.csv").write_text("address,data,strb\n0,0,15\n", encoding="utf-8")
    previous_cwd = Path.cwd()
    try:
        os.chdir(collision_cwd)
        validate_host_bundle(index_path)
    finally:
        os.chdir(previous_cwd)


def _check_rgb_bundle(temp: Path) -> None:
    pfm_path = temp / "rgb.pfm"
    stream_csv = temp / "rgb-stream.csv"
    control_csv = temp / "rgb-control.csv"
    manifest_json = temp / "rgb-manifest.json"
    bundle_dir = temp / "rgb-bundle"

    pfm_path.write_bytes(b"PF\n2 2\n-1.0\n" + bytes(2 * 2 * 3 * 4))
    pixels = [
        (0.0, 0.25, 0.5),
        (0.75, 1.0, -0.25),
        (0.125, -0.5, 0.625),
        (-0.75, 0.875, 0.0),
    ]
    flags = rgb_flags_word(
        enable_xyb=False,
        enable_dct=False,
        enable_quant=False,
        enable_tokenize=False,
        token_select=RGB_TOKEN_SELECT["dc"],
    )
    write_rgb_stream_csv(
        stream_csv,
        pixels,
        pixel_bits=16,
        fraction_bits=8,
        clamp=False,
    )
    write_rgb_axi_lite_csv(
        control_csv,
        width=2,
        height=2,
        distance_q8=256,
        fixed_point_scale=0,
        fixed_inv_qac_q16=0,
        fixed_raw_quant=0,
        flags=flags,
        fixed_ytox=EXPECTED_FIXED_YTOX,
        fixed_ytob=EXPECTED_FIXED_YTOB,
    )
    write_rgb_manifest_json(
        manifest_json,
        pfm=pfm_path,
        stream_csv=stream_csv,
        axi_lite_csv=control_csv,
        width=2,
        height=2,
        pixel_bits=16,
        fraction_bits=8,
        word_count=len(pixels),
        distance_q8=256,
        fixed_point_scale=0,
        fixed_inv_qac_q16=0,
        fixed_raw_quant=0,
        flags=flags,
        fixed_ytox=EXPECTED_FIXED_YTOX,
        fixed_ytob=EXPECTED_FIXED_YTOB,
        token_select_name="dc",
        trace_route_name="raw-quant-field",
    )
    write_host_bundle(
        manifest_path=manifest_json,
        output_dir=bundle_dir,
        name="rgb",
        symbol_prefix="HJXL_RGB",
        include_last=True,
    )
    _compile_generated_header(bundle_dir / "rgb.h", "HJXL_RGB")
    plan = describe_host_bundle(bundle_dir / "rgb-bundle.json")
    if plan.get("trace_route") != {
        "name": "raw-quant-field",
        "stage": 3,
        "focused": True,
    }:
        raise AssertionError(f"unexpected RGB trace route metadata: {plan.get('trace_route')!r}")
    if plan.get("discovery") != EXPECTED_RGB_DISCOVERY:
        raise AssertionError(f"unexpected RGB discovery metadata: {plan.get('discovery')!r}")
    _assert_fixed_cfl_writes(plan)
    header_text = (bundle_dir / "rgb.h").read_text(encoding="utf-8")
    for expected in (
        '#define HJXL_RGB_TARGET_INTERFACE "rgb_axi_stream"',
        '#define HJXL_RGB_TARGET_VARIANT "rgb"',
        '#define HJXL_RGB_TARGET_STREAM_SHELL "HjxlAxiStreamCore"',
        '#define HJXL_RGB_TARGET_CONTROLLED_SHELL "HjxlAxiLiteStreamCore"',
        '#define HJXL_RGB_TARGET_KV260_TOP ""',
        '#define HJXL_RGB_TARGET_INPUT_STREAM "raster RGB pixels"',
        "#define HJXL_RGB_INPUT_TKEEP_ENFORCED 0u",
        '#define HJXL_RGB_TRACE_ROUTE_NAME "raw-quant-field"',
        "#define HJXL_RGB_TRACE_ROUTE_STAGE 3",
        "#define HJXL_RGB_TRACE_ROUTE_FOCUSED 1u",
        "#define HJXL_RGB_DISCOVERY_REQUIRED_CAPABILITIES 0x000000f9u",
        "#define HJXL_RGB_DISCOVERY_ACTIVE_ROUTE 3u",
        "#define HJXL_RGB_DISCOVERY_MIN_MAX_FRAME_WIDTH 2u",
        "#define HJXL_RGB_DISCOVERY_MIN_MAX_FRAME_HEIGHT 2u",
    ):
        if expected not in header_text:
            raise AssertionError(f"generated RGB header missed {expected}")
    summary = _input_stream_summary(plan)
    if summary != EXPECTED_RGB_STREAM:
        raise AssertionError(f"unexpected RGB input stream summary: {summary!r}")
    frame_summary = _frame_summary(plan)
    if frame_summary != EXPECTED_RGB_FRAME:
        raise AssertionError(f"unexpected RGB frame summary: {frame_summary!r}")
    _check_expected_frame(
        plan,
        xsize=EXPECTED_RGB_FRAME["xsize"],
        ysize=EXPECTED_RGB_FRAME["ysize"],
        x_blocks=EXPECTED_RGB_FRAME["x_blocks"],
        y_blocks=EXPECTED_RGB_FRAME["y_blocks"],
        padded_xsize=EXPECTED_RGB_FRAME["padded_xsize"],
        padded_ysize=EXPECTED_RGB_FRAME["padded_ysize"],
        block_count=EXPECTED_RGB_FRAME["block_count"],
        x_tiles=EXPECTED_RGB_FRAME["x_tiles"],
        y_tiles=EXPECTED_RGB_FRAME["y_tiles"],
        tile_count=EXPECTED_RGB_FRAME["tile_count"],
    )
    _check_expected_input_stream(
        plan,
        word_count=EXPECTED_RGB_STREAM["word_count"],
        input_data_bits=EXPECTED_RGB_STREAM["input_data_bits"],
        word_bytes=EXPECTED_RGB_STREAM["word_bytes"],
        input_keep_mask=EXPECTED_RGB_STREAM["input_keep_mask"],
        byte_count=EXPECTED_RGB_STREAM["byte_count"],
        coefficient_fraction_bits=None,
    )
    _check_expected_axi_lite(
        plan,
        addr_bits=EXPECTED_AXI_LITE["addr_bits"],
        data_bits=EXPECTED_AXI_LITE["data_bits"],
        strb_bits=EXPECTED_AXI_LITE["strb_bits"],
        write_count=EXPECTED_AXI_LITE["write_count"],
    )
    _check_expected_register_map(
        plan,
        status_control=EXPECTED_REGISTER_MAP["status_control"],
        xsize=EXPECTED_REGISTER_MAP["xsize"],
        ysize=EXPECTED_REGISTER_MAP["ysize"],
        distance_q8=EXPECTED_REGISTER_MAP["distance_q8"],
        fixed_point_scale=EXPECTED_REGISTER_MAP["fixed_point_scale"],
        fixed_inv_qac_q16=EXPECTED_REGISTER_MAP["fixed_inv_qac_q16"],
        fixed_raw_quant=EXPECTED_REGISTER_MAP["fixed_raw_quant"],
        flags=EXPECTED_REGISTER_MAP["flags"],
        fixed_ytox=EXPECTED_REGISTER_MAP["fixed_ytox"],
        fixed_ytob=EXPECTED_REGISTER_MAP["fixed_ytob"],
    )
    replay_plan_json = temp / "rgb-replay-plan.json"
    _write_json(replay_plan_json, plan)
    validate_replay_plan(replay_plan_json)
    subprocess.run(
        [
            "python3",
            "tools/hjxl_replay_capture.py",
            "--replay-plan-json",
            str(replay_plan_json),
            "--preflight-only",
            "--expect-target-interface",
            "rgb_axi_stream",
            "--expect-target-variant",
            "rgb",
            "--expect-target-stream-shell",
            "HjxlAxiStreamCore",
            "--expect-target-controlled-shell",
            "HjxlAxiLiteStreamCore",
            "--expect-target-no-kv260-top",
            "--expect-target-input-stream",
            "raster RGB pixels",
            "--expect-target-input-keep-enforced",
            "0",
            "--expect-trace-route-name",
            "raw-quant-field",
            "--expect-trace-route-stage",
            "3",
            "--expect-trace-route-focused",
            "true",
        ],
        check=True,
        stdout=subprocess.DEVNULL,
    )
    invalid_target_keep = subprocess.run(
        [
            "python3",
            "tools/hjxl_replay_capture.py",
            "--replay-plan-json",
            str(replay_plan_json),
            "--preflight-only",
            "--expect-target-input-keep-enforced",
            "1",
        ],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if invalid_target_keep.returncode == 0:
        raise AssertionError("RGB input-keep target mismatch should fail preflight")
    invalid_target_keep_output = invalid_target_keep.stdout + invalid_target_keep.stderr
    if "replay plan target input_keep_enforced expected 1, got 0" not in invalid_target_keep_output:
        raise AssertionError(f"RGB input-keep target mismatch missed diagnostic: {invalid_target_keep_output}")
        if "Traceback" in invalid_target_keep_output:
            raise AssertionError(f"RGB input-keep target mismatch leaked a traceback: {invalid_target_keep_output}")
    invalid_target_no_kv = subprocess.run(
        [
            "python3",
            "tools/hjxl_replay_capture.py",
            "--replay-plan-json",
            str(replay_plan_json),
            "--preflight-only",
            "--expect-target-kv260-top",
            "HjxlKv260PreparedDctTop",
            "--expect-target-no-kv260-top",
        ],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if invalid_target_no_kv.returncode != 2:
        raise AssertionError(
            "conflicting RGB KV260 target expectations should fail argument parsing with exit 2, "
            f"got {invalid_target_no_kv.returncode}: "
            f"{invalid_target_no_kv.stdout}{invalid_target_no_kv.stderr}"
        )
    invalid_target_no_kv_output = invalid_target_no_kv.stdout + invalid_target_no_kv.stderr
    if "--expect-target-kv260-top and --expect-target-no-kv260-top are mutually exclusive" not in invalid_target_no_kv_output:
        raise AssertionError(
            f"conflicting RGB KV260 target expectations missed diagnostic: {invalid_target_no_kv_output}"
        )
    if "Traceback" in invalid_target_no_kv_output:
        raise AssertionError(
            f"conflicting RGB KV260 target expectations leaked a traceback: {invalid_target_no_kv_output}"
        )
    invalid_route = subprocess.run(
        [
            "python3",
            "tools/hjxl_replay_capture.py",
            "--replay-plan-json",
            str(replay_plan_json),
            "--preflight-only",
            "--expect-trace-route-name",
            "ytox-map",
        ],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if invalid_route.returncode == 0:
        raise AssertionError("mismatched replay trace route should fail preflight")
    invalid_output = invalid_route.stdout + invalid_route.stderr
    if "trace_route name expected 'ytox-map', got 'raw-quant-field'" not in invalid_output:
        raise AssertionError(f"mismatched trace route missed diagnostic: {invalid_output}")
    if "Traceback" in invalid_output:
        raise AssertionError(f"mismatched trace route leaked a traceback: {invalid_output}")
    invalid_focused = subprocess.run(
        [
            "python3",
            "tools/hjxl_replay_capture.py",
            "--replay-plan-json",
            str(replay_plan_json),
            "--preflight-only",
            "--expect-trace-route-name",
            "raw-quant-field",
            "--expect-trace-route-focused",
            "false",
        ],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if invalid_focused.returncode == 0:
        raise AssertionError("mismatched replay trace route focused flag should fail preflight")
    invalid_focused_output = invalid_focused.stdout + invalid_focused.stderr
    if "trace_route focused expected False, got True" not in invalid_focused_output:
        raise AssertionError(f"mismatched trace route focused flag missed diagnostic: {invalid_focused_output}")
    if "Traceback" in invalid_focused_output:
        raise AssertionError(f"mismatched trace route focused flag leaked a traceback: {invalid_focused_output}")


def main() -> int:
    aq_flags = rgb_flags_word(
        enable_xyb=True,
        enable_dct=False,
        enable_quant=True,
        enable_tokenize=False,
        token_select=RGB_TOKEN_SELECT["aq-contrast"],
    )
    if rgb_active_route(flags=aq_flags, focused_route=None) != TRACE_STAGES["aq-contrast"]:
        raise AssertionError("RGB host route selection missed the AQ contrast stage")
    if rgb_active_route(
        flags=aq_flags,
        focused_route=TRACE_STAGES["aq-fuzzy-erosion"],
    ) != TRACE_STAGES["aq-fuzzy-erosion"]:
        raise AssertionError("RGB host route selection missed focused AQ fuzzy erosion")
    if rgb_active_route(
        flags=aq_flags,
        focused_route=TRACE_STAGES["aq-strategy-mask"],
    ) != TRACE_STAGES["aq-strategy-mask"]:
        raise AssertionError("RGB host route selection missed focused AQ strategy mask")
    if rgb_active_route(
        flags=aq_flags,
        focused_route=TRACE_STAGES["aq-nonlinear-mask"],
    ) != TRACE_STAGES["aq-nonlinear-mask"]:
        raise AssertionError("RGB host route selection missed focused AQ nonlinear mask")
    if rgb_active_route(
        flags=aq_flags,
        focused_route=TRACE_STAGES["aq-hf-modulation"],
    ) != TRACE_STAGES["aq-hf-modulation"]:
        raise AssertionError("RGB host route selection missed focused AQ HF modulation")
    if rgb_active_route(
        flags=aq_flags,
        focused_route=TRACE_STAGES["aq-color-modulation"],
    ) != TRACE_STAGES["aq-color-modulation"]:
        raise AssertionError("RGB host route selection missed focused AQ color modulation")
    if rgb_active_route(
        flags=aq_flags,
        focused_route=TRACE_STAGES["aq-gamma-modulation"],
    ) != TRACE_STAGES["aq-gamma-modulation"]:
        raise AssertionError("RGB host route selection missed focused AQ gamma modulation")

    with tempfile.TemporaryDirectory(prefix="hjxl-host-metadata-smoke-") as temp_name:
        temp = Path(temp_name)
        fixture_path = temp / "prepared-blocks.json"
        stream_csv = temp / "prepared-stream.csv"
        control_csv = temp / "prepared-control.csv"
        manifest_json = temp / "prepared-manifest.json"
        bundle_dir = temp / "bundle"
        replay_plan_json = temp / "prepared-replay-plan.json"
        estimated_manifest_json = temp / "prepared-estimated-cfl-manifest.json"
        estimated_bundle_dir = temp / "estimated-bundle"
        estimated_replay_plan_json = temp / "prepared-estimated-cfl-replay-plan.json"

        _write_json(fixture_path, _fixture())
        fixture = load_fixture(fixture_path)
        write_input_stream_csv(stream_csv, fixture)
        write_axi_lite_csv(
            control_csv,
            xsize=8,
            ysize=8,
            distance_q8=256,
            fixed_point_scale=0,
            fixed_inv_qac_q16=0,
            fixed_raw_quant=0,
            flags=flags_word(
                enable_xyb=False,
                enable_dct=True,
                enable_quant=True,
                enable_tokenize=True,
                token_select=TOKEN_SELECT["ac-tokens"],
            ),
            fixed_ytox=EXPECTED_FIXED_YTOX,
            fixed_ytob=EXPECTED_FIXED_YTOB,
        )
        write_manifest_json(
            manifest_json,
            prepared_json=fixture_path,
            input_stream_csv=stream_csv,
            axi_lite_csv=control_csv,
            fixture=fixture,
            distance_q8=256,
            fixed_point_scale=0,
            fixed_inv_qac_q16=0,
            fixed_raw_quant=0,
            flags=flags_word(
                enable_xyb=False,
                enable_dct=True,
                enable_quant=True,
                enable_tokenize=True,
                token_select=TOKEN_SELECT["ac-tokens"],
            ),
            fixed_ytox=EXPECTED_FIXED_YTOX,
            fixed_ytob=EXPECTED_FIXED_YTOB,
            token_select_name="ac-tokens",
            target_variant="direct",
        )
        validate_manifest_json(manifest_json)
        original_manifest = json.loads(manifest_json.read_text(encoding="utf-8"))
        invalid_discovery_manifest = copy.deepcopy(original_manifest)
        invalid_discovery_manifest["discovery"]["identity"] = 0
        _write_json(manifest_json, invalid_discovery_manifest)
        _expect_error(
            "manifest discovery identity mismatch",
            lambda: validate_manifest_json(manifest_json),
            "discovery.identity does not match the HJXL identity",
        )
        _write_json(manifest_json, original_manifest)

        write_host_bundle(
            manifest_path=manifest_json,
            output_dir=bundle_dir,
            name="prepared",
            symbol_prefix="HJXL_PREPARED",
            include_last=True,
        )
        _compile_generated_header(bundle_dir / "prepared.h", "HJXL_PREPARED")
        index_path = bundle_dir / "prepared-bundle.json"
        plan = describe_host_bundle(index_path)
        if plan.get("trace_route") != {
            "name": "prepared-dct-quantize-token",
            "stage": None,
            "focused": True,
        }:
            raise AssertionError(f"unexpected prepared trace route metadata: {plan.get('trace_route')!r}")
        if plan.get("target", {}).get("variant") != "direct":
            raise AssertionError(f"unexpected prepared target metadata: {plan.get('target')!r}")
        prepared_header = (bundle_dir / "prepared.h").read_text(encoding="utf-8")
        for expected in (
            '#define HJXL_PREPARED_TARGET_INTERFACE "prepared_dct_axi_stream"',
            '#define HJXL_PREPARED_TARGET_VARIANT "direct"',
            '#define HJXL_PREPARED_TARGET_STREAM_SHELL "HjxlPreparedDctAxiStreamCore"',
            '#define HJXL_PREPARED_TARGET_CONTROLLED_SHELL "HjxlPreparedDctAxiLiteStreamCore"',
            '#define HJXL_PREPARED_TARGET_KV260_TOP "HjxlKv260PreparedDctTop"',
            '#define HJXL_PREPARED_TARGET_INPUT_STREAM "prepared DCT-only block words"',
            "#define HJXL_PREPARED_INPUT_TKEEP_ENFORCED 1u",
            '#define HJXL_PREPARED_TRACE_ROUTE_NAME "prepared-dct-quantize-token"',
            "#define HJXL_PREPARED_TRACE_ROUTE_STAGE -1",
            "#define HJXL_PREPARED_TRACE_ROUTE_FOCUSED 1u",
            "#define HJXL_PREPARED_REG_IDENTITY 0x00000028u",
            "#define HJXL_PREPARED_REG_IDENTITY_ACCESS_RO 1u",
            "#define HJXL_PREPARED_REG_BUILD_ID 0x0000003cu",
            "#define HJXL_PREPARED_DISCOVERY_IDENTITY 0x484a584cu",
            f"#define HJXL_PREPARED_DISCOVERY_ABI_VERSION 0x{DISCOVERY_ABI_VERSION:08x}u",
            "#define HJXL_PREPARED_DISCOVERY_BUILD_ID 0x20260712u",
            "#define HJXL_PREPARED_DISCOVERY_REQUIRED_CAPABILITIES 0x000003fau",
            "#define HJXL_PREPARED_DISCOVERY_ACTIVE_ROUTE 128u",
            "#define HJXL_PREPARED_DISCOVERY_MIN_MAX_FRAME_WIDTH 8u",
            "#define HJXL_PREPARED_DISCOVERY_MIN_MAX_FRAME_HEIGHT 8u",
            "#define HJXL_PREPARED_CAPABILITY_CALLER_CFL_BIT 9u",
        ):
            if expected not in prepared_header:
                raise AssertionError(f"generated prepared header missed {expected}")
        _assert_stream_metadata(plan)
        _check_replay_expectations(plan)
        _write_json(replay_plan_json, plan)
        validate_replay_plan(replay_plan_json)

        discovery_csv = temp / "prepared-discovery.csv"
        discovery_summary = temp / "prepared-discovery-summary.json"
        register_map = plan["axi_lite"]["register_map"]
        discovery_values = {
            "identity": EXPECTED_DISCOVERY["identity"],
            "abi_version": EXPECTED_DISCOVERY["abi_version"],
            "capabilities": EXPECTED_DISCOVERY["required_capabilities"],
            "max_frame_geometry": (32 << 16) | 32,
            "active_route": EXPECTED_DISCOVERY["active_route"],
            "build_id": EXPECTED_DISCOVERY["build_id"],
        }
        discovery_csv.write_text(
            "address,data,resp\n"
            + "".join(
                f"{register_map[name]},{value},0\n"
                for name, value in discovery_values.items()
            ),
            encoding="utf-8",
        )
        subprocess.run(
            [
                "python3",
                "tools/hjxl_discovery_check.py",
                "--replay-plan-json",
                str(replay_plan_json),
                "--read-csv",
                str(discovery_csv),
                "--summary-json",
                str(discovery_summary),
            ],
            check=True,
            stdout=subprocess.DEVNULL,
        )
        discovery_result = json.loads(discovery_summary.read_text(encoding="utf-8"))
        if discovery_result.get("max_frame_width") != 32 or discovery_result.get("active_route") != 128:
            raise AssertionError(f"unexpected discovery-check summary: {discovery_result!r}")

        invalid_discovery_values = dict(discovery_values)
        invalid_discovery_values["capabilities"] = 0
        discovery_csv.write_text(
            "address,data,resp\n"
            + "".join(
                f"{register_map[name]},{value},0\n"
                for name, value in invalid_discovery_values.items()
            ),
            encoding="utf-8",
        )
        invalid_discovery = subprocess.run(
            [
                "python3",
                "tools/hjxl_discovery_check.py",
                "--replay-plan-json",
                str(replay_plan_json),
                "--read-csv",
                str(discovery_csv),
            ],
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if invalid_discovery.returncode == 0:
            raise AssertionError("missing discovery capabilities should fail host preflight")
        invalid_discovery_output = invalid_discovery.stdout + invalid_discovery.stderr
        if "discovery capabilities are missing required mask" not in invalid_discovery_output:
            raise AssertionError(
                f"missing discovery capabilities produced the wrong diagnostic: {invalid_discovery_output}"
            )
        if "Traceback" in invalid_discovery_output:
            raise AssertionError(f"discovery capability mismatch leaked a traceback: {invalid_discovery_output}")

        preflight_summary = temp / "prepared-preflight-summary.json"
        subprocess.run(
            [
                "python3",
                "tools/hjxl_replay_capture.py",
                "--replay-plan-json",
                str(replay_plan_json),
                "--preflight-only",
                "--expect-target-interface",
                "prepared_dct_axi_stream",
                "--expect-target-variant",
                "direct",
                "--expect-target-stream-shell",
                "HjxlPreparedDctAxiStreamCore",
                "--expect-target-controlled-shell",
                "HjxlPreparedDctAxiLiteStreamCore",
                "--expect-target-kv260-top",
                "HjxlKv260PreparedDctTop",
                "--expect-target-input-stream",
                "prepared DCT-only block words",
                "--expect-target-input-keep-enforced",
                "1",
                "--expect-trace-route-name",
                "prepared-dct-quantize-token",
                "--expect-trace-route-stage",
                "-1",
                "--expect-trace-route-focused",
                "true",
                "--expect-frame-xsize",
                str(EXPECTED_FRAME["xsize"]),
                "--expect-frame-tile-count",
                str(EXPECTED_FRAME["tile_count"]),
                "--expect-input-word-count",
                str(EXPECTED_STREAM["word_count"]),
                "--expect-reg-flags",
                "0x1c",
                "--summary-json",
                str(preflight_summary),
            ],
            check=True,
            stdout=subprocess.DEVNULL,
        )
        invalid_expectation = subprocess.run(
            [
                "python3",
                "tools/hjxl_replay_capture.py",
                "--replay-plan-json",
                str(replay_plan_json),
                "--preflight-only",
                "--expect-trace-keep-mask",
                "bad",
            ],
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if invalid_expectation.returncode != 2:
            raise AssertionError(
                "malformed replay expectation should fail argument parsing with exit 2, "
                f"got {invalid_expectation.returncode}: "
                f"{invalid_expectation.stdout}{invalid_expectation.stderr}"
            )
        invalid_output = invalid_expectation.stdout + invalid_expectation.stderr
        expected_error = "argument --expect-trace-keep-mask: must be an integer"
        if expected_error not in invalid_output:
            raise AssertionError(f"malformed replay expectation missed diagnostic: {invalid_output}")
        if "Traceback" in invalid_output:
            raise AssertionError(f"malformed replay expectation leaked a traceback: {invalid_output}")
        preflight = json.loads(preflight_summary.read_text(encoding="utf-8"))
        if preflight.get("format") != "hjxl.replay_preflight_summary.v1":
            raise AssertionError(f"unexpected preflight summary format: {preflight!r}")
        if preflight.get("discovery") != EXPECTED_DISCOVERY:
            raise AssertionError(f"preflight summary lost discovery metadata: {preflight!r}")
        artifacts = preflight.get("artifacts", {})
        if artifacts.get("source_manifest") != "prepared-manifest.json":
            raise AssertionError(f"preflight summary lost source manifest provenance: {preflight!r}")
        if artifacts.get("stream_bin") != "prepared-stream.bin":
            raise AssertionError(f"preflight summary lost stream payload provenance: {preflight!r}")
        if "stream_bin_resolved" not in artifacts:
            raise AssertionError(f"preflight summary lost resolved stream payload path: {preflight!r}")
        checksums = preflight.get("checksums", {}).get("sha256", {})
        if "stream_bin" not in checksums or "source_manifest" not in checksums:
            raise AssertionError(f"preflight summary lost artifact checksums: {preflight!r}")

        write_manifest_json(
            estimated_manifest_json,
            prepared_json=fixture_path,
            input_stream_csv=stream_csv,
            axi_lite_csv=control_csv,
            fixture=fixture,
            distance_q8=256,
            fixed_point_scale=0,
            fixed_inv_qac_q16=0,
            fixed_raw_quant=0,
            flags=flags_word(
                enable_xyb=False,
                enable_dct=True,
                enable_quant=True,
                enable_tokenize=True,
                token_select=TOKEN_SELECT["ac-tokens"],
            ),
            fixed_ytox=EXPECTED_FIXED_YTOX,
            fixed_ytob=EXPECTED_FIXED_YTOB,
            token_select_name="ac-tokens",
            target_variant="estimated-cfl",
        )
        validate_manifest_json(estimated_manifest_json)
        write_host_bundle(
            manifest_path=estimated_manifest_json,
            output_dir=estimated_bundle_dir,
            name="prepared-estimated-cfl",
            symbol_prefix="HJXL_PREPARED_CFL",
            include_last=True,
        )
        _compile_generated_header(
            estimated_bundle_dir / "prepared-estimated-cfl.h",
            "HJXL_PREPARED_CFL",
        )
        estimated_plan = describe_host_bundle(
            estimated_bundle_dir / "prepared-estimated-cfl-bundle.json"
        )
        expected_estimated_target = {
            "interface": "prepared_dct_axi_stream",
            "variant": "estimated-cfl",
            "stream_shell": "HjxlPreparedCflDctAxiStreamCore",
            "controlled_shell": "HjxlPreparedCflDctAxiLiteStreamCore",
            "kv260_top": "HjxlKv260PreparedCflDctTop",
            "input_stream": "prepared DCT-only block words with internally estimated CFL maps",
            "input_keep_enforced": 1,
        }
        if estimated_plan.get("target") != expected_estimated_target:
            raise AssertionError(
                f"unexpected estimated-CFL target metadata: {estimated_plan.get('target')!r}"
            )
        estimated_header = (estimated_bundle_dir / "prepared-estimated-cfl.h").read_text(
            encoding="utf-8"
        )
        for expected in (
            '#define HJXL_PREPARED_CFL_TARGET_INTERFACE "prepared_dct_axi_stream"',
            '#define HJXL_PREPARED_CFL_TARGET_VARIANT "estimated-cfl"',
            '#define HJXL_PREPARED_CFL_TARGET_STREAM_SHELL "HjxlPreparedCflDctAxiStreamCore"',
            '#define HJXL_PREPARED_CFL_TARGET_CONTROLLED_SHELL "HjxlPreparedCflDctAxiLiteStreamCore"',
            '#define HJXL_PREPARED_CFL_TARGET_KV260_TOP "HjxlKv260PreparedCflDctTop"',
            '#define HJXL_PREPARED_CFL_TARGET_INPUT_STREAM "prepared DCT-only block words with internally estimated CFL maps"',
            "#define HJXL_PREPARED_CFL_INPUT_TKEEP_ENFORCED 1u",
            '#define HJXL_PREPARED_CFL_TRACE_ROUTE_NAME "prepared-dct-quantize-token"',
            "#define HJXL_PREPARED_CFL_TRACE_ROUTE_STAGE -1",
            "#define HJXL_PREPARED_CFL_TRACE_ROUTE_FOCUSED 1u",
        ):
            if expected not in estimated_header:
                raise AssertionError(f"generated estimated-CFL header missed {expected}")
        _assert_stream_metadata(estimated_plan)
        _check_replay_expectations(
            estimated_plan,
            expected_discovery=EXPECTED_ESTIMATED_DISCOVERY,
        )
        _write_json(estimated_replay_plan_json, estimated_plan)
        validate_replay_plan(estimated_replay_plan_json)
        subprocess.run(
            [
                "python3",
                "tools/hjxl_replay_capture.py",
                "--replay-plan-json",
                str(estimated_replay_plan_json),
                "--preflight-only",
                "--expect-target-interface",
                "prepared_dct_axi_stream",
                "--expect-target-variant",
                "estimated-cfl",
                "--expect-target-stream-shell",
                "HjxlPreparedCflDctAxiStreamCore",
                "--expect-target-controlled-shell",
                "HjxlPreparedCflDctAxiLiteStreamCore",
                "--expect-target-kv260-top",
                "HjxlKv260PreparedCflDctTop",
                "--expect-target-input-stream",
                "prepared DCT-only block words with internally estimated CFL maps",
                "--expect-target-input-keep-enforced",
                "1",
                "--expect-trace-route-name",
                "prepared-dct-quantize-token",
                "--expect-trace-route-stage",
                "-1",
                "--expect-trace-route-focused",
                "true",
                "--expect-input-word-count",
                str(EXPECTED_STREAM["word_count"]),
                "--expect-input-coefficient-fraction-bits",
                str(EXPECTED_STREAM["coefficient_fraction_bits"]),
            ],
            check=True,
            stdout=subprocess.DEVNULL,
        )
        invalid_estimated_target = subprocess.run(
            [
                "python3",
                "tools/hjxl_replay_capture.py",
                "--replay-plan-json",
                str(estimated_replay_plan_json),
                "--preflight-only",
                "--expect-target-variant",
                "direct",
            ],
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if invalid_estimated_target.returncode == 0:
            raise AssertionError("estimated-CFL replay target mismatch should fail preflight")
        invalid_estimated_output = invalid_estimated_target.stdout + invalid_estimated_target.stderr
        if "replay plan target variant expected 'direct', got 'estimated-cfl'" not in invalid_estimated_output:
            raise AssertionError(
                f"estimated-CFL target mismatch missed diagnostic: {invalid_estimated_output}"
            )
        if "Traceback" in invalid_estimated_output:
            raise AssertionError(
                f"estimated-CFL target mismatch leaked a traceback: {invalid_estimated_output}"
            )
        invalid_estimated_keep = subprocess.run(
            [
                "python3",
                "tools/hjxl_replay_capture.py",
                "--replay-plan-json",
                str(estimated_replay_plan_json),
                "--preflight-only",
                "--expect-target-input-keep-enforced",
                "0",
            ],
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if invalid_estimated_keep.returncode == 0:
            raise AssertionError("estimated-CFL input-keep target mismatch should fail preflight")
        invalid_estimated_keep_output = invalid_estimated_keep.stdout + invalid_estimated_keep.stderr
        if "replay plan target input_keep_enforced expected 0, got 1" not in invalid_estimated_keep_output:
            raise AssertionError(
                "estimated-CFL input-keep target mismatch missed diagnostic: "
                f"{invalid_estimated_keep_output}"
            )
        if "Traceback" in invalid_estimated_keep_output:
            raise AssertionError(
                "estimated-CFL input-keep target mismatch leaked a traceback: "
                f"{invalid_estimated_keep_output}"
            )
        invalid_estimated_no_kv = subprocess.run(
            [
                "python3",
                "tools/hjxl_replay_capture.py",
                "--replay-plan-json",
                str(estimated_replay_plan_json),
                "--preflight-only",
                "--expect-target-no-kv260-top",
            ],
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if invalid_estimated_no_kv.returncode == 0:
            raise AssertionError("estimated-CFL no-KV260 target expectation should fail preflight")
        invalid_estimated_no_kv_output = invalid_estimated_no_kv.stdout + invalid_estimated_no_kv.stderr
        if "replay plan target kv260_top expected null, got 'HjxlKv260PreparedCflDctTop'" not in invalid_estimated_no_kv_output:
            raise AssertionError(
                f"estimated-CFL no-KV260 target mismatch missed diagnostic: {invalid_estimated_no_kv_output}"
            )
        if "Traceback" in invalid_estimated_no_kv_output:
            raise AssertionError(
                f"estimated-CFL no-KV260 target mismatch leaked a traceback: {invalid_estimated_no_kv_output}"
            )

        _check_legacy_replay_plan(replay_plan_json, plan)
        _check_bundle_index_compatibility(index_path)
        _check_bundle_relative_paths_win_over_cwd(index_path)
        _check_rgb_bundle(temp)

    print("host metadata smoke passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
