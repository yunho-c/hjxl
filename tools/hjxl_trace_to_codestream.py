#!/usr/bin/env python3
"""Assemble frame/codestream bytes directly from HJXL StageTrace CSV dumps."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

from hjxl_reference import _add_libjxl_tiny, _libjxl_tiny_root, _load_numpy
from hjxl_trace_tokens import TOKEN_STAGES, ac_strategy_grid, load_trace_rows, token_pairs


def _write_bytes(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def _write_array(path: Path, array) -> None:
    np = _load_numpy()
    path.parent.mkdir(parents=True, exist_ok=True)
    np.save(path, np.asarray(array))


def _compare_bytes(label: str, expected_path: Path, actual: bytes) -> list[str]:
    expected = expected_path.read_bytes()
    if actual == expected:
        return []
    limit = min(len(actual), len(expected))
    first_difference = next(
        (index for index in range(limit) if actual[index] != expected[index]),
        limit,
    )
    if first_difference == limit:
        detail = "length differs before any byte mismatch"
    else:
        detail = (
            f"first byte mismatch at offset {first_difference}: "
            f"actual=0x{actual[first_difference]:02x} "
            f"expected=0x{expected[first_difference]:02x}"
        )
    return [
        f"{label} mismatch: actual_len={len(actual)} "
        f"expected_len={len(expected)}; {detail}"
    ]


def assemble_trace(
    trace_csv: list[Path],
    stream_csv: list[Path],
    width: int,
    height: int,
    distance: float,
    group_bits: int = 16,
    trace_value_bits: int = 32,
    require_stream_final_last: bool = False,
):
    np = _load_numpy()
    rows = load_trace_rows(
        trace_csv,
        stream_paths=stream_csv,
        group_bits=group_bits,
        trace_value_bits=trace_value_bits,
        require_stream_final_last=require_stream_final_last,
    )
    dc_tokens = np.asarray(token_pairs(rows, TOKEN_STAGES["dc"]), dtype=np.uint32)
    ac_metadata_tokens = np.asarray(
        token_pairs(rows, TOKEN_STAGES["ac_metadata"]),
        dtype=np.uint32,
    )
    ac_tokens = np.asarray(token_pairs(rows, TOKEN_STAGES["ac"]), dtype=np.uint32)
    ac_strategy = np.asarray(ac_strategy_grid(rows, width, height), dtype=np.uint8)

    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.bitstream import codestream_bytes, frame_bytes  # pylint: disable=import-outside-toplevel

    frame = frame_bytes(dc_tokens, ac_metadata_tokens, ac_tokens, ac_strategy, distance)
    codestream = codestream_bytes(
        width,
        height,
        dc_tokens,
        ac_metadata_tokens,
        ac_tokens,
        ac_strategy,
        distance,
    )
    return dc_tokens, ac_metadata_tokens, ac_tokens, ac_strategy, frame, codestream


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--trace-csv",
        type=Path,
        action="append",
        default=[],
        help="StageTrace CSV input with columns stage,group,index,value; may be repeated",
    )
    parser.add_argument(
        "--stream-csv",
        type=Path,
        action="append",
        default=[],
        help="packed AXI-stream trace CSV input with data,last or tdata,tlast columns; may be repeated",
    )
    parser.add_argument("--group-bits", type=int, default=16, help="packed stream StageTrace group width")
    parser.add_argument("--trace-value-bits", type=int, default=32, help="packed stream StageTrace value width")
    parser.add_argument(
        "--require-stream-final-last",
        action="store_true",
        help="require packed stream TLAST only on the final decoded row",
    )
    parser.add_argument("--width", type=int, required=True, help="image width in pixels")
    parser.add_argument("--height", type=int, required=True, help="image height in pixels")
    parser.add_argument("--distance", type=float, default=1.0, help="JPEG XL encoder distance")
    parser.add_argument("--frame-bin", type=Path, help="output serialized JPEG XL frame bytes")
    parser.add_argument("--codestream-bin", type=Path, help="output bare JPEG XL codestream bytes")
    parser.add_argument("--expect-frame-bin", type=Path, help="expected frame bytes to compare")
    parser.add_argument(
        "--expect-codestream-bin",
        type=Path,
        help="expected bare JPEG XL codestream bytes to compare",
    )
    parser.add_argument("--dc-tokens-npy", type=Path, help="optional extracted DC token rows")
    parser.add_argument(
        "--ac-metadata-tokens-npy",
        type=Path,
        help="optional extracted AC metadata token rows",
    )
    parser.add_argument("--ac-tokens-npy", type=Path, help="optional extracted AC token rows")
    parser.add_argument("--ac-strategy-npy", type=Path, help="optional extracted AC strategy grid")
    args = parser.parse_args()

    if args.width <= 0:
        raise SystemExit("--width must be positive")
    if args.height <= 0:
        raise SystemExit("--height must be positive")
    if args.distance <= 0.0:
        raise SystemExit("--distance must be positive")
    if not args.trace_csv and not args.stream_csv:
        raise SystemExit("at least one --trace-csv or --stream-csv input is required")
    if (
        args.frame_bin is None
        and args.codestream_bin is None
        and args.expect_frame_bin is None
        and args.expect_codestream_bin is None
        and args.dc_tokens_npy is None
        and args.ac_metadata_tokens_npy is None
        and args.ac_tokens_npy is None
        and args.ac_strategy_npy is None
    ):
        raise SystemExit("at least one output path is required")

    try:
        dc_tokens, ac_metadata_tokens, ac_tokens, ac_strategy, frame, codestream = assemble_trace(
            args.trace_csv,
            args.stream_csv,
            args.width,
            args.height,
            args.distance,
            group_bits=args.group_bits,
            trace_value_bits=args.trace_value_bits,
            require_stream_final_last=args.require_stream_final_last,
        )
    except Exception as exc:  # pylint: disable=broad-exception-caught
        print(f"error: {exc}", file=sys.stderr)
        return 1

    if args.dc_tokens_npy is not None:
        _write_array(args.dc_tokens_npy, dc_tokens)
    if args.ac_metadata_tokens_npy is not None:
        _write_array(args.ac_metadata_tokens_npy, ac_metadata_tokens)
    if args.ac_tokens_npy is not None:
        _write_array(args.ac_tokens_npy, ac_tokens)
    if args.ac_strategy_npy is not None:
        _write_array(args.ac_strategy_npy, ac_strategy)
    if args.frame_bin is not None:
        _write_bytes(args.frame_bin, frame)
    if args.codestream_bin is not None:
        _write_bytes(args.codestream_bin, codestream)

    errors: list[str] = []
    if args.expect_frame_bin is not None:
        errors.extend(_compare_bytes("frame", args.expect_frame_bin, frame))
    if args.expect_codestream_bin is not None:
        errors.extend(_compare_bytes("codestream", args.expect_codestream_bin, codestream))
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1

    print(
        "assembled trace: "
        f"dc={len(dc_tokens)} ac_metadata={len(ac_metadata_tokens)} "
        f"ac={len(ac_tokens)} frame_bytes={len(frame)} codestream_bytes={len(codestream)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
