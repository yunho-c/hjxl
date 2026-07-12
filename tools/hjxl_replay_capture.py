#!/usr/bin/env python3
"""Validate and assemble a captured HJXL trace stream using a replay plan."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
from pathlib import Path
import sys

from hjxl_host_bundle import describe_host_bundle, validate_replay_plan
from hjxl_manifest_header import distance_metadata
from hjxl_trace_to_codestream import assemble_trace, compare_bytes, write_array, write_bytes


DEFAULT_DISTANCE_METADATA = distance_metadata()


@dataclass(frozen=True)
class FrameConfigFromPlan:
    width: int
    height: int
    requested_distance_q8: int
    effective_distance_q8: int

    @property
    def distance_supported(self) -> bool:
        return self.requested_distance_q8 == self.effective_distance_q8

    @property
    def requested_distance(self) -> float:
        return self.requested_distance_q8 / 256.0

    @property
    def effective_distance(self) -> float:
        return self.effective_distance_q8 / 256.0


def _load_plan(*, replay_plan_json: Path | None, bundle_index: Path | None) -> dict:
    if replay_plan_json is not None and bundle_index is not None:
        raise ValueError("choose only one of --replay-plan-json or --bundle-index")
    if replay_plan_json is None and bundle_index is None:
        raise ValueError("one of --replay-plan-json or --bundle-index is required")
    if replay_plan_json is not None:
        return validate_replay_plan(replay_plan_json)
    assert bundle_index is not None
    return describe_host_bundle(bundle_index)


def _plan_int(value: object, label: str) -> int:
    if value is None:
        raise ValueError(f"{label} is required")
    if isinstance(value, bool):
        raise ValueError(f"{label} must be an integer")
    if isinstance(value, float):
        raise ValueError(f"{label} must be an integer")
    try:
        if isinstance(value, str):
            return int(value.strip(), 0)
        return int(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{label} must be an integer") from exc


def _write_data(plan: dict, register: str) -> int:
    for row in plan["axi_lite"]["writes"]:
        if row["register"] == register:
            return _plan_int(row.get("data"), f"replay plan AXI-Lite write {register}.data")
    raise ValueError(f"replay plan is missing AXI-Lite write for {register}")


def _frame_config_from_plan(plan: dict, *, require_supported_distance: bool) -> FrameConfigFromPlan:
    width = _write_data(plan, "xsize")
    height = _write_data(plan, "ysize")
    frame = plan.get("frame")
    if isinstance(frame, dict):
        if _plan_int(frame.get("xsize", width), "replay plan frame.xsize") != width:
            raise ValueError("replay plan frame.xsize does not match AXI-Lite xsize write")
        if _plan_int(frame.get("ysize", height), "replay plan frame.ysize") != height:
            raise ValueError("replay plan frame.ysize does not match AXI-Lite ysize write")
    distance_q8 = _write_data(plan, "distance_q8")
    distance_metadata = plan.get("distance", {})
    fallback_distance_q8 = _plan_int(
        distance_metadata.get("fallback_q8", DEFAULT_DISTANCE_METADATA["fallback_q8"]),
        "replay plan distance.fallback_q8",
    )
    supported_distance_q8 = frozenset(
        _plan_int(value, f"replay plan distance.supported_q8[{index}]")
        for index, value in enumerate(
            distance_metadata.get("supported_q8", DEFAULT_DISTANCE_METADATA["supported_q8"])
        )
    )
    if width <= 0:
        raise ValueError("replay plan xsize must be positive")
    if height <= 0:
        raise ValueError("replay plan ysize must be positive")
    if distance_q8 <= 0:
        raise ValueError("replay plan distance_q8 must be positive")
    if fallback_distance_q8 <= 0:
        raise ValueError("replay plan distance.fallback_q8 must be positive")
    if not supported_distance_q8:
        raise ValueError("replay plan distance.supported_q8 must not be empty")
    effective_distance_q8 = distance_q8 if distance_q8 in supported_distance_q8 else fallback_distance_q8
    if require_supported_distance and effective_distance_q8 != distance_q8:
        supported = ", ".join(str(value) for value in sorted(supported_distance_q8))
        raise ValueError(
            "replay plan distance_q8 "
            f"{distance_q8} is not supported by RTL distance lookup; supported Q8 distances: {supported}"
        )
    return FrameConfigFromPlan(
        width=width,
        height=height,
        requested_distance_q8=distance_q8,
        effective_distance_q8=effective_distance_q8,
    )


def _positive_int(value: object, label: str) -> int:
    parsed = _plan_int(value, label)
    if parsed <= 0:
        raise ValueError(f"{label} must be positive")
    return parsed


def _trace_config_from_plan(
    plan: dict,
    *,
    group_bits_override: int | None,
    trace_value_bits_override: int | None,
    stream_word_bytes_override: int | None,
) -> tuple[int, int, int]:
    trace = plan.get("trace", {})
    group_bits = (
        _positive_int(group_bits_override, "--group-bits")
        if group_bits_override is not None
        else _positive_int(trace.get("group_bits", 16), "replay plan trace.group_bits")
    )
    trace_value_bits = (
        _positive_int(trace_value_bits_override, "--trace-value-bits")
        if trace_value_bits_override is not None
        else _positive_int(trace.get("trace_value_bits", 32), "replay plan trace.trace_value_bits")
    )
    stream_word_bytes = (
        _positive_int(stream_word_bytes_override, "--stream-word-bytes")
        if stream_word_bytes_override is not None
        else _positive_int(
            trace.get("default_capture_word_bytes", 16),
            "replay plan trace.default_capture_word_bytes",
        )
    )
    return group_bits, trace_value_bits, stream_word_bytes


def _input_stream_summary(plan: dict) -> dict:
    stream = plan.get("stream", {})
    summary = {}
    for key in (
        "word_count",
        "input_data_bits",
        "word_bytes",
        "input_keep_mask",
        "byte_count",
        "coefficient_fraction_bits",
    ):
        if key in stream:
            summary[key] = _plan_int(stream[key], f"replay plan stream.{key}")
    return summary


def _trace_summary(plan: dict) -> dict:
    trace = plan.get("trace", {})
    summary = {}
    for key in (
        "stage_bits",
        "group_bits",
        "index_bits",
        "trace_value_bits",
        "stage_shift",
        "group_shift",
        "index_shift",
        "trace_value_shift",
        "stage_byte_offset",
        "group_byte_offset",
        "index_byte_offset",
        "trace_value_byte_offset",
        "stage_mask",
        "group_mask",
        "index_mask",
        "trace_value_mask",
        "packed_bits",
        "packed_bytes",
        "tkeep_mask",
        "default_capture_word_bytes",
    ):
        if key in trace:
            summary[key] = _plan_int(trace[key], f"replay plan trace.{key}")
    return summary


def _axi_lite_summary(plan: dict) -> dict:
    axi_lite = plan.get("axi_lite", {})
    summary = {}
    for key in (
        "addr_bits",
        "data_bits",
        "strb_bits",
        "write_count",
    ):
        if key in axi_lite:
            summary[key] = _plan_int(axi_lite[key], f"replay plan AXI-Lite {key}")
    register_map = axi_lite.get("register_map")
    if isinstance(register_map, dict):
        summary["register_map"] = {
            key: _plan_int(value, f"replay plan AXI-Lite register_map {key}")
            for key, value in register_map.items()
        }
    return summary


def _artifact_summary(plan: dict) -> dict:
    artifacts = plan.get("artifacts", {})
    summary: dict[str, object] = {}
    if not isinstance(artifacts, dict):
        artifacts = {}
    for key in (
            "header",
            "header_resolved",
            "source_manifest",
            "source_manifest_resolved",
            "stream_csv",
            "stream_csv_resolved",
            "axi_lite_csv",
            "axi_lite_csv_resolved",
    ):
        if key in artifacts:
            summary[key] = artifacts[key]
    stream = plan.get("stream", {})
    if isinstance(stream, dict):
        for key in (
            "stream_bin",
            "stream_bin_resolved",
            "last_bin",
            "last_bin_resolved",
        ):
            if key in stream:
                summary[key] = stream[key]
    return summary


def _checksums_summary(plan: dict) -> dict:
    checksums = plan.get("checksums", {})
    if not isinstance(checksums, dict):
        return {}
    sha256 = checksums.get("sha256")
    if isinstance(sha256, dict):
        return {"sha256": {key: str(value) for key, value in sha256.items()}}
    return {}


def _check_register_value(register_map: dict, *, key: str, expected: int | None, flag: str) -> None:
    _check_nonnegative_expectation(expected, flag)
    if expected is None:
        return
    if key not in register_map:
        raise ValueError(f"replay plan AXI-Lite register_map metadata is missing {key}")
    actual = _plan_int(register_map[key], f"replay plan AXI-Lite register_map {key}")
    if actual != expected:
        raise ValueError(f"replay plan AXI-Lite register_map {key} expected {expected}, got {actual}")


def _check_expected_register_map(
    plan: dict,
    *,
    status_control: int | None,
    xsize: int | None,
    ysize: int | None,
    distance_q8: int | None,
    fixed_point_scale: int | None,
    fixed_inv_qac_q16: int | None,
    fixed_raw_quant: int | None,
    flags: int | None,
    fixed_ytox: int | None,
    fixed_ytob: int | None,
) -> None:
    if (
        status_control is None
        and xsize is None
        and ysize is None
        and distance_q8 is None
        and fixed_point_scale is None
        and fixed_inv_qac_q16 is None
        and fixed_raw_quant is None
        and flags is None
        and fixed_ytox is None
        and fixed_ytob is None
    ):
        return
    axi_lite = plan.get("axi_lite")
    if not isinstance(axi_lite, dict):
        raise ValueError("replay plan is missing AXI-Lite metadata")
    register_map = axi_lite.get("register_map")
    if not isinstance(register_map, dict):
        raise ValueError("replay plan AXI-Lite metadata is missing register_map")
    _check_register_value(
        register_map,
        key="status_control",
        expected=status_control,
        flag="--expect-reg-status-control",
    )
    _check_register_value(register_map, key="xsize", expected=xsize, flag="--expect-reg-xsize")
    _check_register_value(register_map, key="ysize", expected=ysize, flag="--expect-reg-ysize")
    _check_register_value(register_map, key="distance_q8", expected=distance_q8, flag="--expect-reg-distance-q8")
    _check_register_value(
        register_map,
        key="fixed_point_scale",
        expected=fixed_point_scale,
        flag="--expect-reg-fixed-point-scale",
    )
    _check_register_value(
        register_map,
        key="fixed_inv_qac_q16",
        expected=fixed_inv_qac_q16,
        flag="--expect-reg-fixed-inv-qac-q16",
    )
    _check_register_value(
        register_map,
        key="fixed_raw_quant",
        expected=fixed_raw_quant,
        flag="--expect-reg-fixed-raw-quant",
    )
    _check_register_value(register_map, key="flags", expected=flags, flag="--expect-reg-flags")
    _check_register_value(register_map, key="fixed_ytox", expected=fixed_ytox, flag="--expect-reg-fixed-ytox")
    _check_register_value(register_map, key="fixed_ytob", expected=fixed_ytob, flag="--expect-reg-fixed-ytob")


def _status_bits_summary(plan: dict) -> dict:
    status_bits = plan.get("status_bits", {})
    summary = {}
    for key in (
        "protocol_error",
        "busy",
        "overflow",
        "unsupported_distance",
        "clear_protocol_error_write_bit",
    ):
        if key in status_bits:
            summary[key] = _plan_int(status_bits[key], f"replay plan status_bits.{key}")
    return summary


def _frame_summary(plan: dict) -> dict:
    frame = plan.get("frame", {})
    summary = {}
    for key in (
        "xsize",
        "ysize",
        "x_blocks",
        "y_blocks",
        "padded_xsize",
        "padded_ysize",
        "block_count",
        "x_tiles",
        "y_tiles",
        "tile_count",
    ):
        if key in frame:
            summary[key] = _plan_int(frame[key], f"replay plan frame.{key}")
    return summary


def _check_status_bit_value(status_bits: dict, *, key: str, expected: int | None, flag: str) -> None:
    _check_nonnegative_expectation(expected, flag)
    if expected is None:
        return
    if key not in status_bits:
        raise ValueError(f"replay plan status_bits metadata is missing {key}")
    actual = _plan_int(status_bits[key], f"replay plan status_bits {key}")
    if actual != expected:
        raise ValueError(f"replay plan status_bits {key} expected {expected}, got {actual}")


def _check_expected_status_bits(
    plan: dict,
    *,
    protocol_error: int | None,
    busy: int | None,
    overflow: int | None,
    unsupported_distance: int | None,
    clear_protocol_error_write_bit: int | None,
) -> None:
    if (
        protocol_error is None
        and busy is None
        and overflow is None
        and unsupported_distance is None
        and clear_protocol_error_write_bit is None
    ):
        return
    status_bits = plan.get("status_bits")
    if not isinstance(status_bits, dict):
        raise ValueError("replay plan is missing status_bits metadata")
    _check_status_bit_value(
        status_bits,
        key="protocol_error",
        expected=protocol_error,
        flag="--expect-status-protocol-error-bit",
    )
    _check_status_bit_value(status_bits, key="busy", expected=busy, flag="--expect-status-busy-bit")
    _check_status_bit_value(status_bits, key="overflow", expected=overflow, flag="--expect-status-overflow-bit")
    _check_status_bit_value(
        status_bits,
        key="unsupported_distance",
        expected=unsupported_distance,
        flag="--expect-status-unsupported-distance-bit",
    )
    _check_status_bit_value(
        status_bits,
        key="clear_protocol_error_write_bit",
        expected=clear_protocol_error_write_bit,
        flag="--expect-control-clear-protocol-error-bit",
    )


def _check_frame_value(frame: dict, *, key: str, expected: int | None) -> None:
    if expected is None:
        return
    if key not in frame:
        raise ValueError(f"replay plan frame metadata is missing {key}")
    actual = _plan_int(frame[key], f"replay plan frame {key}")
    if actual != expected:
        raise ValueError(f"replay plan frame {key} expected {expected}, got {actual}")


def _check_expected_frame(
    plan: dict,
    *,
    xsize: int | None,
    ysize: int | None,
    x_blocks: int | None,
    y_blocks: int | None,
    padded_xsize: int | None,
    padded_ysize: int | None,
    block_count: int | None,
    x_tiles: int | None,
    y_tiles: int | None,
    tile_count: int | None,
) -> None:
    _check_positive_expectation(xsize, "--expect-frame-xsize")
    _check_positive_expectation(ysize, "--expect-frame-ysize")
    _check_positive_expectation(x_blocks, "--expect-frame-x-blocks")
    _check_positive_expectation(y_blocks, "--expect-frame-y-blocks")
    _check_positive_expectation(padded_xsize, "--expect-frame-padded-xsize")
    _check_positive_expectation(padded_ysize, "--expect-frame-padded-ysize")
    _check_positive_expectation(block_count, "--expect-frame-block-count")
    _check_positive_expectation(x_tiles, "--expect-frame-x-tiles")
    _check_positive_expectation(y_tiles, "--expect-frame-y-tiles")
    _check_positive_expectation(tile_count, "--expect-frame-tile-count")
    if (
        xsize is None
        and ysize is None
        and x_blocks is None
        and y_blocks is None
        and padded_xsize is None
        and padded_ysize is None
        and block_count is None
        and x_tiles is None
        and y_tiles is None
        and tile_count is None
    ):
        return
    frame = plan.get("frame")
    if not isinstance(frame, dict):
        raise ValueError("replay plan is missing frame metadata")
    _check_frame_value(frame, key="xsize", expected=xsize)
    _check_frame_value(frame, key="ysize", expected=ysize)
    _check_frame_value(frame, key="x_blocks", expected=x_blocks)
    _check_frame_value(frame, key="y_blocks", expected=y_blocks)
    _check_frame_value(frame, key="padded_xsize", expected=padded_xsize)
    _check_frame_value(frame, key="padded_ysize", expected=padded_ysize)
    _check_frame_value(frame, key="block_count", expected=block_count)
    _check_frame_value(frame, key="x_tiles", expected=x_tiles)
    _check_frame_value(frame, key="y_tiles", expected=y_tiles)
    _check_frame_value(frame, key="tile_count", expected=tile_count)


def _check_trace_value(
    trace: dict,
    *,
    key: str,
    expected: int | None,
    flag: str,
    allow_zero: bool = False,
) -> None:
    if allow_zero:
        if expected is not None and expected < 0:
            raise ValueError(f"{flag} must be nonnegative")
    else:
        _check_positive_expectation(expected, flag)
    if expected is None:
        return
    if key not in trace:
        raise ValueError(f"replay plan trace metadata is missing {key}")
    actual = _plan_int(trace[key], f"replay plan trace {key}")
    if actual != expected:
        raise ValueError(f"replay plan trace {key} expected {expected}, got {actual}")


def _check_expected_trace(
    plan: dict,
    *,
    stage_bits: int | None,
    group_bits: int | None,
    index_bits: int | None,
    trace_value_bits: int | None,
    stage_shift: int | None,
    group_shift: int | None,
    index_shift: int | None,
    trace_value_shift: int | None,
    stage_byte_offset: int | None,
    group_byte_offset: int | None,
    index_byte_offset: int | None,
    trace_value_byte_offset: int | None,
    stage_mask: int | None,
    group_mask: int | None,
    index_mask: int | None,
    trace_value_mask: int | None,
    packed_bits: int | None,
    packed_bytes: int | None,
    keep_mask: int | None,
    capture_word_bytes: int | None,
) -> None:
    if (
        stage_bits is None
        and group_bits is None
        and index_bits is None
        and trace_value_bits is None
        and stage_shift is None
        and group_shift is None
        and index_shift is None
        and trace_value_shift is None
        and stage_byte_offset is None
        and group_byte_offset is None
        and index_byte_offset is None
        and trace_value_byte_offset is None
        and stage_mask is None
        and group_mask is None
        and index_mask is None
        and trace_value_mask is None
        and packed_bits is None
        and packed_bytes is None
        and keep_mask is None
        and capture_word_bytes is None
    ):
        return
    trace = plan.get("trace")
    if not isinstance(trace, dict):
        raise ValueError("replay plan is missing trace metadata")
    _check_trace_value(trace, key="stage_bits", expected=stage_bits, flag="--expect-trace-stage-bits")
    _check_trace_value(trace, key="group_bits", expected=group_bits, flag="--expect-trace-group-bits")
    _check_trace_value(trace, key="index_bits", expected=index_bits, flag="--expect-trace-index-bits")
    _check_trace_value(
        trace,
        key="trace_value_bits",
        expected=trace_value_bits,
        flag="--expect-trace-value-bits",
    )
    _check_trace_value(
        trace,
        key="stage_shift",
        expected=stage_shift,
        flag="--expect-trace-stage-shift",
        allow_zero=True,
    )
    _check_trace_value(
        trace,
        key="group_shift",
        expected=group_shift,
        flag="--expect-trace-group-shift",
        allow_zero=True,
    )
    _check_trace_value(
        trace,
        key="index_shift",
        expected=index_shift,
        flag="--expect-trace-index-shift",
        allow_zero=True,
    )
    _check_trace_value(
        trace,
        key="trace_value_shift",
        expected=trace_value_shift,
        flag="--expect-trace-value-shift",
        allow_zero=True,
    )
    _check_trace_value(
        trace,
        key="stage_byte_offset",
        expected=stage_byte_offset,
        flag="--expect-trace-stage-byte-offset",
        allow_zero=True,
    )
    _check_trace_value(
        trace,
        key="group_byte_offset",
        expected=group_byte_offset,
        flag="--expect-trace-group-byte-offset",
        allow_zero=True,
    )
    _check_trace_value(
        trace,
        key="index_byte_offset",
        expected=index_byte_offset,
        flag="--expect-trace-index-byte-offset",
        allow_zero=True,
    )
    _check_trace_value(
        trace,
        key="trace_value_byte_offset",
        expected=trace_value_byte_offset,
        flag="--expect-trace-value-byte-offset",
        allow_zero=True,
    )
    _check_trace_value(trace, key="stage_mask", expected=stage_mask, flag="--expect-trace-stage-mask")
    _check_trace_value(trace, key="group_mask", expected=group_mask, flag="--expect-trace-group-mask")
    _check_trace_value(trace, key="index_mask", expected=index_mask, flag="--expect-trace-index-mask")
    _check_trace_value(
        trace,
        key="trace_value_mask",
        expected=trace_value_mask,
        flag="--expect-trace-value-mask",
    )
    _check_trace_value(trace, key="packed_bits", expected=packed_bits, flag="--expect-trace-packed-bits")
    _check_trace_value(trace, key="packed_bytes", expected=packed_bytes, flag="--expect-trace-packed-bytes")
    _check_trace_value(trace, key="tkeep_mask", expected=keep_mask, flag="--expect-trace-keep-mask")
    _check_trace_value(
        trace,
        key="default_capture_word_bytes",
        expected=capture_word_bytes,
        flag="--expect-capture-word-bytes",
    )


def _has_capture_input(args: argparse.Namespace) -> bool:
    return bool(args.trace_csv or args.stream_csv or args.stream_bin)


def _target_value(plan: dict, key: str) -> object | None:
    target = plan.get("target")
    if not isinstance(target, dict):
        return None
    return target.get(key)


def _check_expected_no_target_value(plan: dict, key: str) -> None:
    target = plan.get("target")
    if not isinstance(target, dict):
        raise ValueError("replay plan is missing target metadata")
    if key not in target:
        raise ValueError(f"replay plan target metadata is missing {key}")
    actual = target[key]
    if actual is not None:
        raise ValueError(f"replay plan target {key} expected null, got {str(actual)!r}")


def _check_expected_target(plan: dict, expectations: dict[str, str | int | None]) -> None:
    for key, expected in expectations.items():
        if expected is None:
            continue
        actual = _target_value(plan, key)
        if actual is None:
            raise ValueError(f"replay plan target metadata is missing {key}")
        if isinstance(expected, int):
            actual_value = _plan_int(actual, f"replay plan target {key}")
        else:
            actual_value = str(actual)
        if actual_value != expected:
            raise ValueError(
                f"replay plan target {key} expected {expected!r}, got {actual_value!r}"
            )


def _check_expected_trace_route(
    plan: dict,
    *,
    name: str | None,
    stage: int | None,
    focused: bool | None,
) -> None:
    if name is None and stage is None and focused is None:
        return
    route = plan.get("trace_route")
    if not isinstance(route, dict):
        raise ValueError("replay plan is missing trace_route metadata")
    if name is not None:
        actual_name = route.get("name")
        if actual_name != name:
            raise ValueError(
                f"replay plan trace_route name expected {name!r}, got {actual_name!r}"
            )
    if stage is not None:
        actual_stage = -1 if route.get("stage") is None else _plan_int(
            route["stage"],
            "replay plan trace_route stage",
        )
        if actual_stage != stage:
            raise ValueError(
                f"replay plan trace_route stage expected {stage}, got {actual_stage}"
            )
    if focused is not None:
        actual_focused = bool(route.get("focused"))
        if actual_focused != focused:
            raise ValueError(
                f"replay plan trace_route focused expected {focused}, got {actual_focused}"
            )


def _check_positive_expectation(value: int | None, flag: str) -> None:
    if value is not None and value <= 0:
        raise ValueError(f"{flag} must be positive")


def _check_nonnegative_expectation(value: int | None, flag: str) -> None:
    if value is not None and value < 0:
        raise ValueError(f"{flag} must be nonnegative")


def _check_stream_value(stream: dict, *, key: str, expected: int | None) -> None:
    if expected is None:
        return
    if key not in stream:
        raise ValueError(f"replay plan stream metadata is missing {key}")
    actual = _plan_int(stream[key], f"replay plan stream {key}")
    if actual != expected:
        raise ValueError(f"replay plan stream {key} expected {expected}, got {actual}")


def _check_expected_input_stream(
    plan: dict,
    *,
    word_count: int | None,
    input_data_bits: int | None,
    word_bytes: int | None,
    input_keep_mask: int | None,
    byte_count: int | None,
    coefficient_fraction_bits: int | None,
) -> None:
    _check_positive_expectation(word_count, "--expect-input-word-count")
    _check_positive_expectation(input_data_bits, "--expect-input-data-bits")
    _check_positive_expectation(word_bytes, "--expect-input-word-bytes")
    _check_positive_expectation(input_keep_mask, "--expect-input-keep-mask")
    _check_positive_expectation(byte_count, "--expect-input-byte-count")
    _check_positive_expectation(
        coefficient_fraction_bits,
        "--expect-input-coefficient-fraction-bits",
    )
    if (
        word_count is None
        and input_data_bits is None
        and word_bytes is None
        and input_keep_mask is None
        and byte_count is None
        and coefficient_fraction_bits is None
    ):
        return
    stream = plan.get("stream")
    if not isinstance(stream, dict):
        raise ValueError("replay plan is missing stream metadata")
    _check_stream_value(stream, key="word_count", expected=word_count)
    _check_stream_value(stream, key="input_data_bits", expected=input_data_bits)
    _check_stream_value(stream, key="word_bytes", expected=word_bytes)
    _check_stream_value(stream, key="input_keep_mask", expected=input_keep_mask)
    _check_stream_value(stream, key="byte_count", expected=byte_count)
    _check_stream_value(
        stream,
        key="coefficient_fraction_bits",
        expected=coefficient_fraction_bits,
    )


def _check_axi_lite_value(axi_lite: dict, *, key: str, expected: int | None) -> None:
    if expected is None:
        return
    if key not in axi_lite:
        raise ValueError(f"replay plan AXI-Lite metadata is missing {key}")
    actual = _plan_int(axi_lite[key], f"replay plan AXI-Lite {key}")
    if actual != expected:
        raise ValueError(f"replay plan AXI-Lite {key} expected {expected}, got {actual}")


def _check_expected_axi_lite(
    plan: dict,
    *,
    addr_bits: int | None,
    data_bits: int | None,
    strb_bits: int | None,
    write_count: int | None,
) -> None:
    _check_positive_expectation(addr_bits, "--expect-axi-lite-addr-bits")
    _check_positive_expectation(data_bits, "--expect-axi-lite-data-bits")
    _check_positive_expectation(strb_bits, "--expect-axi-lite-strb-bits")
    _check_positive_expectation(write_count, "--expect-axi-lite-write-count")
    if addr_bits is None and data_bits is None and strb_bits is None and write_count is None:
        return
    axi_lite = plan.get("axi_lite")
    if not isinstance(axi_lite, dict):
        raise ValueError("replay plan is missing AXI-Lite metadata")
    _check_axi_lite_value(axi_lite, key="addr_bits", expected=addr_bits)
    _check_axi_lite_value(axi_lite, key="data_bits", expected=data_bits)
    _check_axi_lite_value(axi_lite, key="strb_bits", expected=strb_bits)
    _check_axi_lite_value(axi_lite, key="write_count", expected=write_count)


def _preflight_summary(
    *,
    plan: dict,
    frame_config: FrameConfigFromPlan,
    group_bits: int,
    trace_value_bits: int,
    stream_word_bytes: int,
) -> dict:
    return {
        "format": "hjxl.replay_preflight_summary.v1",
        "replay_name": plan["name"],
        "target": plan.get("target"),
        "trace_route": plan.get("trace_route"),
        "artifacts": _artifact_summary(plan),
        "checksums": _checksums_summary(plan),
        "width": frame_config.width,
        "height": frame_config.height,
        "frame": _frame_summary(plan),
        "status_bits": _status_bits_summary(plan),
        "distance": frame_config.effective_distance,
        "requested_distance": frame_config.requested_distance,
        "distance_q8": {
            "requested": frame_config.requested_distance_q8,
            "effective": frame_config.effective_distance_q8,
            "supported": frame_config.distance_supported,
        },
        "input_stream": _input_stream_summary(plan),
        "trace": _trace_summary(plan),
        "axi_lite": _axi_lite_summary(plan),
        "capture_defaults": {
            "group_bits": group_bits,
            "trace_value_bits": trace_value_bits,
            "stream_word_bytes": stream_word_bytes,
        },
    }


def _integer_arg(value: str) -> int:
    try:
        return int(value, 0)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("must be an integer") from exc


def _bool_arg(value: str) -> bool:
    normalized = value.strip().lower()
    if normalized in ("1", "true", "yes", "on", "focused"):
        return True
    if normalized in ("0", "false", "no", "off", "all"):
        return False
    raise argparse.ArgumentTypeError("must be one of 0, 1, true, false")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    plan_group = parser.add_mutually_exclusive_group(required=True)
    plan_group.add_argument(
        "--replay-plan-json",
        type=Path,
        help="validated hjxl.host_replay_plan.v1 JSON for the input replay bundle",
    )
    plan_group.add_argument(
        "--bundle-index",
        type=Path,
        help="hjxl.host_bundle.v1 index; described into a replay plan before capture validation",
    )
    parser.add_argument(
        "--trace-csv",
        type=Path,
        action="append",
        default=[],
        help="captured StageTrace CSV with stage,group,index,value columns; may be repeated",
    )
    parser.add_argument(
        "--stream-csv",
        type=Path,
        action="append",
        default=[],
        help="captured packed trace stream CSV with data,last or tdata,tlast columns; may be repeated",
    )
    parser.add_argument(
        "--stream-bin",
        type=Path,
        action="append",
        default=[],
        help="captured little-endian packed trace TDATA binary; may be repeated",
    )
    parser.add_argument(
        "--last-bin",
        type=Path,
        action="append",
        default=[],
        help="optional one-byte-per-word TLAST sidecar for each --stream-bin",
    )
    parser.add_argument(
        "--stream-word-bytes",
        type=int,
        help="bytes per captured binary trace word; defaults to the replay plan trace metadata",
    )
    parser.add_argument("--group-bits", type=int, help="packed StageTrace group width")
    parser.add_argument("--trace-value-bits", type=int, help="packed StageTrace value width")
    parser.add_argument(
        "--allow-missing-final-last",
        action="store_true",
        help="do not require captured packed streams to assert TLAST only on the final word",
    )
    parser.add_argument(
        "--require-supported-distance",
        action="store_true",
        help="fail if replay-plan distanceQ8 would use the RTL distance-1 fallback",
    )
    parser.add_argument("--frame-bin", type=Path, help="output serialized JPEG XL frame bytes")
    parser.add_argument("--codestream-bin", type=Path, help="output bare JPEG XL codestream bytes")
    parser.add_argument("--expect-frame-bin", type=Path, help="expected frame bytes to compare")
    parser.add_argument("--expect-codestream-bin", type=Path, help="expected bare codestream bytes to compare")
    parser.add_argument("--dc-tokens-npy", type=Path, help="optional extracted DC token rows")
    parser.add_argument("--ac-metadata-tokens-npy", type=Path, help="optional extracted AC metadata token rows")
    parser.add_argument("--ac-tokens-npy", type=Path, help="optional extracted AC token rows")
    parser.add_argument("--ac-strategy-npy", type=Path, help="optional extracted AC strategy grid")
    parser.add_argument("--summary-json", type=Path, help="optional capture assembly summary JSON")
    parser.add_argument(
        "--preflight-only",
        action="store_true",
        help="validate the replay plan and expectation flags without requiring capture inputs",
    )
    parser.add_argument(
        "--expect-target-interface",
        help="require replay-plan target.interface to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-target-variant",
        help="require replay-plan target.variant to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-target-stream-shell",
        help="require replay-plan target.stream_shell to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-target-controlled-shell",
        help="require replay-plan target.controlled_shell to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-target-kv260-top",
        help="require replay-plan target.kv260_top to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-target-no-kv260-top",
        action="store_true",
        help="require replay-plan target.kv260_top to be null before assembling capture data",
    )
    parser.add_argument(
        "--expect-target-input-stream",
        help="require replay-plan target.input_stream to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-target-input-keep-enforced",
        type=_integer_arg,
        choices=(0, 1),
        help="require replay-plan target.input_keep_enforced to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-trace-route-name",
        help="require replay-plan trace_route.name to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-trace-route-stage",
        type=_integer_arg,
        help="require replay-plan trace_route.stage to match; use -1 for the all-route shell",
    )
    parser.add_argument(
        "--expect-trace-route-focused",
        type=_bool_arg,
        help="require replay-plan trace_route.focused to match; accepts 0/1 or true/false",
    )
    parser.add_argument(
        "--expect-frame-xsize",
        type=int,
        help="require replay-plan frame.xsize to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-frame-ysize",
        type=int,
        help="require replay-plan frame.ysize to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-frame-x-blocks",
        type=int,
        help="require replay-plan frame.x_blocks to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-frame-y-blocks",
        type=int,
        help="require replay-plan frame.y_blocks to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-frame-padded-xsize",
        type=int,
        help="require replay-plan frame.padded_xsize to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-frame-padded-ysize",
        type=int,
        help="require replay-plan frame.padded_ysize to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-frame-block-count",
        type=int,
        help="require replay-plan frame.block_count to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-frame-x-tiles",
        type=int,
        help="require replay-plan frame.x_tiles to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-frame-y-tiles",
        type=int,
        help="require replay-plan frame.y_tiles to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-frame-tile-count",
        type=int,
        help="require replay-plan frame.tile_count to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-status-protocol-error-bit",
        type=int,
        help="require replay-plan status_bits.protocol_error to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-status-busy-bit",
        type=int,
        help="require replay-plan status_bits.busy to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-status-overflow-bit",
        type=int,
        help="require replay-plan status_bits.overflow to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-status-unsupported-distance-bit",
        type=int,
        help="require replay-plan status_bits.unsupported_distance to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-control-clear-protocol-error-bit",
        type=int,
        help="require replay-plan status_bits.clear_protocol_error_write_bit to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-trace-stage-bits",
        type=int,
        help="require replay-plan trace.stage_bits to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-trace-group-bits",
        type=int,
        help="require replay-plan trace.group_bits to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-trace-index-bits",
        type=int,
        help="require replay-plan trace.index_bits to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-trace-value-bits",
        type=int,
        help="require replay-plan trace.trace_value_bits to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-trace-stage-shift",
        type=int,
        help="require replay-plan trace.stage_shift to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-trace-group-shift",
        type=int,
        help="require replay-plan trace.group_shift to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-trace-index-shift",
        type=int,
        help="require replay-plan trace.index_shift to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-trace-value-shift",
        type=int,
        help="require replay-plan trace.trace_value_shift to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-trace-stage-byte-offset",
        type=int,
        help="require replay-plan trace.stage_byte_offset to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-trace-group-byte-offset",
        type=int,
        help="require replay-plan trace.group_byte_offset to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-trace-index-byte-offset",
        type=int,
        help="require replay-plan trace.index_byte_offset to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-trace-value-byte-offset",
        type=int,
        help="require replay-plan trace.trace_value_byte_offset to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-trace-stage-mask",
        type=_integer_arg,
        help="require replay-plan trace.stage_mask to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-trace-group-mask",
        type=_integer_arg,
        help="require replay-plan trace.group_mask to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-trace-index-mask",
        type=_integer_arg,
        help="require replay-plan trace.index_mask to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-trace-value-mask",
        type=_integer_arg,
        help="require replay-plan trace.trace_value_mask to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-trace-packed-bits",
        type=int,
        help="require replay-plan trace.packed_bits to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-trace-packed-bytes",
        type=int,
        help="require replay-plan trace.packed_bytes to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-trace-keep-mask",
        type=_integer_arg,
        help="require replay-plan trace.tkeep_mask to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-capture-word-bytes",
        type=int,
        help="require replay-plan trace.default_capture_word_bytes to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-input-coefficient-fraction-bits",
        type=int,
        help="require replay-plan stream.coefficient_fraction_bits to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-input-word-count",
        type=int,
        help="require replay-plan stream.word_count to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-input-data-bits",
        type=int,
        help="require replay-plan stream.input_data_bits to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-input-word-bytes",
        type=int,
        help="require replay-plan stream.word_bytes to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-input-keep-mask",
        type=_integer_arg,
        help="require replay-plan stream.input_keep_mask to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-input-byte-count",
        type=int,
        help="require replay-plan stream.byte_count to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-axi-lite-addr-bits",
        type=int,
        help="require replay-plan AXI-Lite address width to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-axi-lite-data-bits",
        type=int,
        help="require replay-plan AXI-Lite data width to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-axi-lite-strb-bits",
        type=int,
        help="require replay-plan AXI-Lite strobe width to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-axi-lite-write-count",
        type=int,
        help="require replay-plan AXI-Lite write count to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-reg-status-control",
        type=_integer_arg,
        help="require replay-plan AXI-Lite status_control register address to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-reg-xsize",
        type=_integer_arg,
        help="require replay-plan AXI-Lite xsize register address to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-reg-ysize",
        type=_integer_arg,
        help="require replay-plan AXI-Lite ysize register address to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-reg-distance-q8",
        type=_integer_arg,
        help="require replay-plan AXI-Lite distance_q8 register address to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-reg-fixed-point-scale",
        type=_integer_arg,
        help="require replay-plan AXI-Lite fixed_point_scale register address to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-reg-fixed-inv-qac-q16",
        type=_integer_arg,
        help="require replay-plan AXI-Lite fixed_inv_qac_q16 register address to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-reg-fixed-raw-quant",
        type=_integer_arg,
        help="require replay-plan AXI-Lite fixed_raw_quant register address to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-reg-fixed-ytox",
        type=_integer_arg,
        help="require replay-plan AXI-Lite fixed_ytox register address to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-reg-fixed-ytob",
        type=_integer_arg,
        help="require replay-plan AXI-Lite fixed_ytob register address to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-reg-flags",
        type=_integer_arg,
        help="require replay-plan AXI-Lite flags register address to match before assembling capture data",
    )
    args = parser.parse_args()

    if args.expect_target_kv260_top is not None and args.expect_target_no_kv260_top:
        parser.error("--expect-target-kv260-top and --expect-target-no-kv260-top are mutually exclusive")

    if not args.preflight_only:
        if not _has_capture_input(args):
            raise SystemExit("error: at least one --trace-csv, --stream-csv, or --stream-bin input is required")
        if args.stream_bin and args.last_bin and len(args.last_bin) != len(args.stream_bin):
            raise SystemExit("error: --last-bin count must be zero or match --stream-bin count")
        if (
            args.frame_bin is None
            and args.codestream_bin is None
            and args.expect_frame_bin is None
            and args.expect_codestream_bin is None
            and args.dc_tokens_npy is None
            and args.ac_metadata_tokens_npy is None
            and args.ac_tokens_npy is None
            and args.ac_strategy_npy is None
            and args.summary_json is None
        ):
            raise SystemExit("error: at least one output, expected-byte check, or --summary-json is required")

    try:
        plan = _load_plan(replay_plan_json=args.replay_plan_json, bundle_index=args.bundle_index)
        _check_expected_target(
            plan,
            {
                "interface": args.expect_target_interface,
                "variant": args.expect_target_variant,
                "stream_shell": args.expect_target_stream_shell,
                "controlled_shell": args.expect_target_controlled_shell,
                "kv260_top": args.expect_target_kv260_top,
                "input_stream": args.expect_target_input_stream,
                "input_keep_enforced": args.expect_target_input_keep_enforced,
            },
        )
        if args.expect_target_no_kv260_top:
            _check_expected_no_target_value(plan, "kv260_top")
        _check_expected_trace_route(
            plan,
            name=args.expect_trace_route_name,
            stage=args.expect_trace_route_stage,
            focused=args.expect_trace_route_focused,
        )
        _check_expected_frame(
            plan,
            xsize=args.expect_frame_xsize,
            ysize=args.expect_frame_ysize,
            x_blocks=args.expect_frame_x_blocks,
            y_blocks=args.expect_frame_y_blocks,
            padded_xsize=args.expect_frame_padded_xsize,
            padded_ysize=args.expect_frame_padded_ysize,
            block_count=args.expect_frame_block_count,
            x_tiles=args.expect_frame_x_tiles,
            y_tiles=args.expect_frame_y_tiles,
            tile_count=args.expect_frame_tile_count,
        )
        _check_expected_status_bits(
            plan,
            protocol_error=args.expect_status_protocol_error_bit,
            busy=args.expect_status_busy_bit,
            overflow=args.expect_status_overflow_bit,
            unsupported_distance=args.expect_status_unsupported_distance_bit,
            clear_protocol_error_write_bit=args.expect_control_clear_protocol_error_bit,
        )
        _check_expected_trace(
            plan,
            stage_bits=args.expect_trace_stage_bits,
            group_bits=args.expect_trace_group_bits,
            index_bits=args.expect_trace_index_bits,
            trace_value_bits=args.expect_trace_value_bits,
            stage_shift=args.expect_trace_stage_shift,
            group_shift=args.expect_trace_group_shift,
            index_shift=args.expect_trace_index_shift,
            trace_value_shift=args.expect_trace_value_shift,
            stage_byte_offset=args.expect_trace_stage_byte_offset,
            group_byte_offset=args.expect_trace_group_byte_offset,
            index_byte_offset=args.expect_trace_index_byte_offset,
            trace_value_byte_offset=args.expect_trace_value_byte_offset,
            stage_mask=args.expect_trace_stage_mask,
            group_mask=args.expect_trace_group_mask,
            index_mask=args.expect_trace_index_mask,
            trace_value_mask=args.expect_trace_value_mask,
            packed_bits=args.expect_trace_packed_bits,
            packed_bytes=args.expect_trace_packed_bytes,
            keep_mask=args.expect_trace_keep_mask,
            capture_word_bytes=args.expect_capture_word_bytes,
        )
        _check_expected_input_stream(
            plan,
            word_count=args.expect_input_word_count,
            input_data_bits=args.expect_input_data_bits,
            word_bytes=args.expect_input_word_bytes,
            input_keep_mask=args.expect_input_keep_mask,
            byte_count=args.expect_input_byte_count,
            coefficient_fraction_bits=args.expect_input_coefficient_fraction_bits,
        )
        _check_expected_axi_lite(
            plan,
            addr_bits=args.expect_axi_lite_addr_bits,
            data_bits=args.expect_axi_lite_data_bits,
            strb_bits=args.expect_axi_lite_strb_bits,
            write_count=args.expect_axi_lite_write_count,
        )
        _check_expected_register_map(
            plan,
            status_control=args.expect_reg_status_control,
            xsize=args.expect_reg_xsize,
            ysize=args.expect_reg_ysize,
            distance_q8=args.expect_reg_distance_q8,
            fixed_point_scale=args.expect_reg_fixed_point_scale,
            fixed_inv_qac_q16=args.expect_reg_fixed_inv_qac_q16,
            fixed_raw_quant=args.expect_reg_fixed_raw_quant,
            flags=args.expect_reg_flags,
            fixed_ytox=args.expect_reg_fixed_ytox,
            fixed_ytob=args.expect_reg_fixed_ytob,
        )
        frame_config = _frame_config_from_plan(
            plan,
            require_supported_distance=args.require_supported_distance,
        )
        group_bits, trace_value_bits, stream_word_bytes = _trace_config_from_plan(
            plan,
            group_bits_override=args.group_bits,
            trace_value_bits_override=args.trace_value_bits,
            stream_word_bytes_override=args.stream_word_bytes,
        )
        if args.preflight_only:
            summary = _preflight_summary(
                plan=plan,
                frame_config=frame_config,
                group_bits=group_bits,
                trace_value_bits=trace_value_bits,
                stream_word_bytes=stream_word_bytes,
            )
            if args.summary_json is not None:
                args.summary_json.parent.mkdir(parents=True, exist_ok=True)
                args.summary_json.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
            print(
                "validated replay preflight "
                f"{plan['name']}: {frame_config.width}x{frame_config.height} "
                f"distance={frame_config.effective_distance:g} "
                f"requested_distance={frame_config.requested_distance:g} "
                f"distance_supported={str(frame_config.distance_supported).lower()} "
                f"stream_words={plan['stream']['word_count']} "
                f"axi_lite_writes={plan['axi_lite']['write_count']}"
            )
            return 0
        if not _has_capture_input(args):
            raise ValueError("at least one --trace-csv, --stream-csv, or --stream-bin input is required")
        if args.stream_bin and args.last_bin and len(args.last_bin) != len(args.stream_bin):
            raise ValueError("--last-bin count must be zero or match --stream-bin count")
        if (
            args.frame_bin is None
            and args.codestream_bin is None
            and args.expect_frame_bin is None
            and args.expect_codestream_bin is None
            and args.dc_tokens_npy is None
            and args.ac_metadata_tokens_npy is None
            and args.ac_tokens_npy is None
            and args.ac_strategy_npy is None
            and args.summary_json is None
        ):
            raise ValueError("at least one output, expected-byte check, or --summary-json is required")
        dc_tokens, ac_metadata_tokens, ac_tokens, ac_strategy, frame, codestream = assemble_trace(
            args.trace_csv,
            args.stream_csv,
            args.stream_bin,
            args.last_bin,
            frame_config.width,
            frame_config.height,
            frame_config.effective_distance,
            stream_word_bytes=stream_word_bytes,
            group_bits=group_bits,
            trace_value_bits=trace_value_bits,
            require_stream_final_last=not args.allow_missing_final_last,
        )
    except Exception as exc:  # pylint: disable=broad-exception-caught
        print(f"error: {exc}", file=sys.stderr)
        return 1

    if args.dc_tokens_npy is not None:
        write_array(args.dc_tokens_npy, dc_tokens)
    if args.ac_metadata_tokens_npy is not None:
        write_array(args.ac_metadata_tokens_npy, ac_metadata_tokens)
    if args.ac_tokens_npy is not None:
        write_array(args.ac_tokens_npy, ac_tokens)
    if args.ac_strategy_npy is not None:
        write_array(args.ac_strategy_npy, ac_strategy)
    if args.frame_bin is not None:
        write_bytes(args.frame_bin, frame)
    if args.codestream_bin is not None:
        write_bytes(args.codestream_bin, codestream)

    errors: list[str] = []
    if args.expect_frame_bin is not None:
        errors.extend(compare_bytes("frame", args.expect_frame_bin, frame))
    if args.expect_codestream_bin is not None:
        errors.extend(compare_bytes("codestream", args.expect_codestream_bin, codestream))
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1

    summary = {
        "format": "hjxl.capture_summary.v1",
        "replay_name": plan["name"],
        "target": plan.get("target"),
        "trace_route": plan.get("trace_route"),
        "artifacts": _artifact_summary(plan),
        "checksums": _checksums_summary(plan),
        "width": frame_config.width,
        "height": frame_config.height,
        "frame": _frame_summary(plan),
        "status_bits": _status_bits_summary(plan),
        "distance": frame_config.effective_distance,
        "requested_distance": frame_config.requested_distance,
        "distance_q8": {
            "requested": frame_config.requested_distance_q8,
            "effective": frame_config.effective_distance_q8,
            "supported": frame_config.distance_supported,
        },
        "input_stream": _input_stream_summary(plan),
        "trace": _trace_summary(plan),
        "axi_lite": _axi_lite_summary(plan),
        "capture": {
            "trace_csv_count": len(args.trace_csv),
            "stream_csv_count": len(args.stream_csv),
            "stream_bin_count": len(args.stream_bin),
            "group_bits": group_bits,
            "trace_value_bits": trace_value_bits,
            "stream_word_bytes": stream_word_bytes,
            "requires_final_last": not args.allow_missing_final_last,
        },
        "tokens": {
            "dc": int(len(dc_tokens)),
            "ac_metadata": int(len(ac_metadata_tokens)),
            "ac": int(len(ac_tokens)),
        },
        "frame_bytes": len(frame),
        "codestream_bytes": len(codestream),
    }
    if args.summary_json is not None:
        args.summary_json.parent.mkdir(parents=True, exist_ok=True)
        args.summary_json.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    print(
        "validated capture for replay "
        f"{plan['name']}: {frame_config.width}x{frame_config.height} "
        f"distance={frame_config.effective_distance:g} "
        f"requested_distance={frame_config.requested_distance:g} "
        f"distance_supported={str(frame_config.distance_supported).lower()} "
        f"dc={len(dc_tokens)} ac_metadata={len(ac_metadata_tokens)} "
        f"ac={len(ac_tokens)} frame_bytes={len(frame)} codestream_bytes={len(codestream)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
