#!/usr/bin/env python3
"""Validate and assemble a captured HJXL trace stream using a replay plan."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

from hjxl_host_bundle import describe_host_bundle, validate_replay_plan
from hjxl_trace_to_codestream import assemble_trace, compare_bytes, write_array, write_bytes


def _load_plan(*, replay_plan_json: Path | None, bundle_index: Path | None) -> dict:
    if replay_plan_json is not None and bundle_index is not None:
        raise ValueError("choose only one of --replay-plan-json or --bundle-index")
    if replay_plan_json is None and bundle_index is None:
        raise ValueError("one of --replay-plan-json or --bundle-index is required")
    if replay_plan_json is not None:
        return validate_replay_plan(replay_plan_json)
    assert bundle_index is not None
    return describe_host_bundle(bundle_index)


def _write_data(plan: dict, register: str) -> int:
    for row in plan["axi_lite"]["writes"]:
        if row["register"] == register:
            return int(row["data"])
    raise ValueError(f"replay plan is missing AXI-Lite write for {register}")


def _frame_config_from_plan(plan: dict) -> tuple[int, int, float]:
    width = _write_data(plan, "xsize")
    height = _write_data(plan, "ysize")
    distance_q8 = _write_data(plan, "distance_q8")
    if width <= 0:
        raise ValueError("replay plan xsize must be positive")
    if height <= 0:
        raise ValueError("replay plan ysize must be positive")
    if distance_q8 <= 0:
        raise ValueError("replay plan distance_q8 must be positive")
    return width, height, distance_q8 / 256.0


def _positive_int(value: object, label: str) -> int:
    parsed = int(value)
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


def _has_capture_input(args: argparse.Namespace) -> bool:
    return bool(args.trace_csv or args.stream_csv or args.stream_bin)


def _target_value(plan: dict, key: str) -> str | None:
    target = plan.get("target")
    if not isinstance(target, dict):
        return None
    value = target.get(key)
    if value is None:
        return None
    return str(value)


def _check_expected_target(plan: dict, expectations: dict[str, str | None]) -> None:
    for key, expected in expectations.items():
        if expected is None:
            continue
        actual = _target_value(plan, key)
        if actual is None:
            raise ValueError(f"replay plan target metadata is missing {key}")
        if actual != expected:
            raise ValueError(
                f"replay plan target {key} expected {expected!r}, got {actual!r}"
            )


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
        "--expect-target-interface",
        help="require replay-plan target.interface to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-target-controlled-shell",
        help="require replay-plan target.controlled_shell to match before assembling capture data",
    )
    parser.add_argument(
        "--expect-target-kv260-top",
        help="require replay-plan target.kv260_top to match before assembling capture data",
    )
    args = parser.parse_args()

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
                "controlled_shell": args.expect_target_controlled_shell,
                "kv260_top": args.expect_target_kv260_top,
            },
        )
        width, height, distance = _frame_config_from_plan(plan)
        group_bits, trace_value_bits, stream_word_bytes = _trace_config_from_plan(
            plan,
            group_bits_override=args.group_bits,
            trace_value_bits_override=args.trace_value_bits,
            stream_word_bytes_override=args.stream_word_bytes,
        )
        dc_tokens, ac_metadata_tokens, ac_tokens, ac_strategy, frame, codestream = assemble_trace(
            args.trace_csv,
            args.stream_csv,
            args.stream_bin,
            args.last_bin,
            width,
            height,
            distance,
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
        "width": width,
        "height": height,
        "distance": distance,
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
        f"{plan['name']}: {width}x{height} distance={distance:g} "
        f"dc={len(dc_tokens)} ac_metadata={len(ac_metadata_tokens)} "
        f"ac={len(ac_tokens)} frame_bytes={len(frame)} codestream_bytes={len(codestream)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
