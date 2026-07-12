#!/usr/bin/env python3
"""Convert HJXL StageTrace CSV dumps into token-input NumPy arrays."""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
from pathlib import Path
import sys

from hjxl_stream_trace import decode_stream_rows, read_stream_bin, read_stream_csv


TRACE_STAGES = {
    "InputPadded": 0,
    "Xyb": 1,
    "RawDct8x8": 2,
    "RawQuantField": 3,
    "YtoxMap": 4,
    "YtobMap": 5,
    "AcStrategy": 6,
    "QuantDc": 7,
    "QuantizedAc": 8,
    "NumNonzeros": 9,
    "DcTokens": 10,
    "AcMetadataTokens": 11,
    "AcTokens": 12,
}

TOKEN_STAGES = {
    "dc": TRACE_STAGES["DcTokens"],
    "ac_metadata": TRACE_STAGES["AcMetadataTokens"],
    "ac": TRACE_STAGES["AcTokens"],
}

BLOCK_DIM = 8
TILE_DIM = 64


@dataclass(frozen=True)
class TraceRow:
    stage: int
    group: int
    index: int
    value: int
    source: str
    line: int


def _load_numpy():
    import numpy as np  # pylint: disable=import-outside-toplevel

    return np


def parse_stage(value: str | None, *, source: Path, line: int) -> int:
    if value is None:
        raise ValueError(f"{source}:{line}: stage is required")
    stripped = value.strip()
    if not stripped:
        raise ValueError(f"{source}:{line}: stage is required")
    if stripped in TRACE_STAGES:
        return TRACE_STAGES[stripped]
    try:
        return int(stripped, 0)
    except ValueError as exc:
        raise ValueError(
            f"{source}:{line}: stage must be a trace stage name or integer, got {value!r}"
        ) from exc


def parse_int(value: str | None, *, field: str, source: Path, line: int) -> int:
    if value is None:
        raise ValueError(f"{source}:{line}: {field} is required")
    stripped = value.strip()
    if not stripped:
        raise ValueError(f"{source}:{line}: {field} is required")
    try:
        return int(stripped, 0)
    except ValueError as exc:
        raise ValueError(
            f"{source}:{line}: {field} must be an integer, got {value!r}"
        ) from exc


def read_trace_csv(path: Path) -> list[TraceRow]:
    rows: list[TraceRow] = []
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        required = {"stage", "group", "index", "value"}
        if reader.fieldnames is None:
            raise ValueError(f"{path}: missing CSV header")
        missing = required.difference(reader.fieldnames)
        if missing:
            raise ValueError(f"{path}: missing CSV columns: {', '.join(sorted(missing))}")
        for line, row in enumerate(reader, start=2):
            rows.append(
                TraceRow(
                    stage=parse_stage(row["stage"], source=path, line=line),
                    group=parse_int(
                        row["group"],
                        field="group",
                        source=path,
                        line=line,
                    ),
                    index=parse_int(
                        row["index"],
                        field="index",
                        source=path,
                        line=line,
                    ),
                    value=parse_int(
                        row["value"],
                        field="value",
                        source=path,
                        line=line,
                    ),
                    source=str(path),
                    line=line,
                )
            )
    return rows


def load_trace_rows(
    paths: list[Path],
    stream_paths: list[Path] | None = None,
    stream_bin_paths: list[Path] | None = None,
    last_bin_paths: list[Path] | None = None,
    stream_word_bytes: int | None = None,
    group_bits: int = 16,
    trace_value_bits: int = 32,
    require_stream_final_last: bool = False,
) -> list[TraceRow]:
    rows: list[TraceRow] = []
    for path in paths:
        rows.extend(read_trace_csv(path))
    stream_inputs = [
        row
        for path in stream_paths or []
        for row in read_stream_csv(path)
    ]
    binary_paths = stream_bin_paths or []
    last_paths = last_bin_paths or []
    if last_paths and len(last_paths) != len(binary_paths):
        raise ValueError("--last-bin count must be zero or match --stream-bin count")
    packed_bits = 8 + group_bits + 32 + trace_value_bits
    binary_word_bytes = stream_word_bytes or ((packed_bits + 7) // 8)
    for index, path in enumerate(binary_paths):
        last_bin = last_paths[index] if last_paths else None
        stream_inputs.extend(
            read_stream_bin(
                path,
                word_bytes=binary_word_bytes,
                last_bin=last_bin,
            )
        )
    if stream_inputs:
        for stream_row in decode_stream_rows(
            stream_inputs,
            group_bits=group_bits,
            trace_value_bits=trace_value_bits,
            require_final_last=require_stream_final_last,
        ):
            rows.append(
                TraceRow(
                    stage=stream_row.stage,
                    group=stream_row.group,
                    index=stream_row.index,
                    value=stream_row.value,
                    source=stream_row.source,
                    line=stream_row.line,
                )
            )
    return rows


def token_pairs(rows: list[TraceRow], stage: int) -> list[tuple[int, int]]:
    selected = [row for row in rows if row.stage == stage]
    by_group: dict[int, TraceRow] = {}
    max_uint32 = (1 << 32) - 1
    for row in selected:
        if row.group in by_group:
            previous = by_group[row.group]
            raise ValueError(
                f"duplicate token group {row.group}: {previous.source}:{previous.line} "
                f"and {row.source}:{row.line}"
            )
        if row.index < 0:
            raise ValueError(f"{row.source}:{row.line}: negative token context {row.index}")
        if row.value < 0:
            raise ValueError(f"{row.source}:{row.line}: negative packed token value {row.value}")
        if row.index > max_uint32:
            raise ValueError(f"{row.source}:{row.line}: token context {row.index} outside uint32")
        if row.value > max_uint32:
            raise ValueError(f"{row.source}:{row.line}: packed token value {row.value} outside uint32")
        by_group[row.group] = row

    expected = list(range(len(by_group)))
    actual = sorted(by_group)
    if actual != expected:
        raise ValueError(
            f"non-contiguous token groups for stage {stage}: expected {expected[:8]}... "
            f"got {actual[:8]}..."
        )
    return [(by_group[group].index, by_group[group].value) for group in expected]


def ac_strategy_grid(rows: list[TraceRow], width: int, height: int):
    np = _load_numpy()
    x_blocks = (width + BLOCK_DIM - 1) // BLOCK_DIM
    y_blocks = (height + BLOCK_DIM - 1) // BLOCK_DIM
    total = x_blocks * y_blocks
    selected = [row for row in rows if row.stage == TRACE_STAGES["AcStrategy"]]
    by_index: dict[int, TraceRow] = {}
    for row in selected:
        if row.index in by_index:
            previous = by_index[row.index]
            raise ValueError(
                f"duplicate AC strategy index {row.index}: {previous.source}:{previous.line} "
                f"and {row.source}:{row.line}"
            )
        if row.index < 0 or row.index >= total:
            raise ValueError(
                f"{row.source}:{row.line}: AC strategy index {row.index} outside 0..{total - 1}"
            )
        if row.value < 0 or row.value > 255:
            raise ValueError(f"{row.source}:{row.line}: AC strategy value {row.value} outside uint8")
        by_index[row.index] = row
    missing = [index for index in range(total) if index not in by_index]
    if missing:
        raise ValueError(f"missing AC strategy trace indices: {missing[:8]}")
    values = [by_index[index].value for index in range(total)]
    return np.asarray(values, dtype=np.uint8).reshape((y_blocks, x_blocks))


def uint8_grid(rows: list[TraceRow], *, stage: int, width: int, height: int, label: str):
    np = _load_numpy()
    x_blocks = (width + BLOCK_DIM - 1) // BLOCK_DIM
    y_blocks = (height + BLOCK_DIM - 1) // BLOCK_DIM
    total = x_blocks * y_blocks
    selected = [row for row in rows if row.stage == stage]
    by_index: dict[int, TraceRow] = {}
    for row in selected:
        if row.index in by_index:
            previous = by_index[row.index]
            raise ValueError(
                f"duplicate {label} index {row.index}: {previous.source}:{previous.line} "
                f"and {row.source}:{row.line}"
            )
        if row.index < 0 or row.index >= total:
            raise ValueError(f"{row.source}:{row.line}: {label} index {row.index} outside 0..{total - 1}")
        if row.value < 0 or row.value > 255:
            raise ValueError(f"{row.source}:{row.line}: {label} value {row.value} outside uint8")
        by_index[row.index] = row
    missing = [index for index in range(total) if index not in by_index]
    if missing:
        raise ValueError(f"missing {label} trace indices: {missing[:8]}")
    values = [by_index[index].value for index in range(total)]
    return np.asarray(values, dtype=np.uint8).reshape((y_blocks, x_blocks))


def int8_tile_grid(rows: list[TraceRow], *, stage: int, width: int, height: int, label: str):
    np = _load_numpy()
    x_tiles = (width + TILE_DIM - 1) // TILE_DIM
    y_tiles = (height + TILE_DIM - 1) // TILE_DIM
    total = x_tiles * y_tiles
    selected = [row for row in rows if row.stage == stage]
    by_index: dict[int, TraceRow] = {}
    for row in selected:
        if row.index in by_index:
            previous = by_index[row.index]
            raise ValueError(
                f"duplicate {label} index {row.index}: {previous.source}:{previous.line} "
                f"and {row.source}:{row.line}"
            )
        if row.index < 0 or row.index >= total:
            raise ValueError(f"{row.source}:{row.line}: {label} index {row.index} outside 0..{total - 1}")
        if row.value < -128 or row.value > 127:
            raise ValueError(f"{row.source}:{row.line}: {label} value {row.value} outside int8")
        by_index[row.index] = row
    missing = [index for index in range(total) if index not in by_index]
    if missing:
        raise ValueError(f"missing {label} trace indices: {missing[:8]}")
    values = [by_index[index].value for index in range(total)]
    return np.asarray(values, dtype=np.int8).reshape((y_tiles, x_tiles))


def require_positive_dimensions(width: int | None, height: int | None, *, output: str) -> tuple[int, int]:
    if width is None or height is None:
        raise SystemExit(f"{output} requires --width and --height")
    if width <= 0 or height <= 0:
        raise ValueError(f"{output} requires positive --width and --height")
    return width, height


def write_token_npy(path: Path, pairs: list[tuple[int, int]]) -> None:
    np = _load_numpy()
    path.parent.mkdir(parents=True, exist_ok=True)
    np.save(path, np.asarray(pairs, dtype=np.uint32))


def write_array_npy(path: Path, array) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    np = _load_numpy()
    np.save(path, np.asarray(array))


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
    parser.add_argument(
        "--stream-bin",
        type=Path,
        action="append",
        default=[],
        help="little-endian packed AXI-stream trace binary input; may be repeated",
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
        help="bytes per binary stream word; defaults to packed StageTrace width rounded up",
    )
    parser.add_argument("--group-bits", type=int, default=16, help="packed stream StageTrace group width")
    parser.add_argument("--trace-value-bits", type=int, default=32, help="packed stream StageTrace value width")
    parser.add_argument(
        "--require-stream-final-last",
        action="store_true",
        help="require packed stream TLAST only on the final decoded row",
    )
    parser.add_argument("--dc-tokens-npy", type=Path, help="output DC token rows (context, value)")
    parser.add_argument(
        "--ac-metadata-tokens-npy",
        type=Path,
        help="output AC metadata token rows (context, value)",
    )
    parser.add_argument("--ac-tokens-npy", type=Path, help="output AC token rows (context, value)")
    parser.add_argument("--ac-strategy-npy", type=Path, help="output AC strategy grid")
    parser.add_argument("--raw-quant-field-npy", type=Path, help="output raw quant-field block grid")
    parser.add_argument("--ytox-map-npy", type=Path, help="output Y-to-X CFL tile grid")
    parser.add_argument("--ytob-map-npy", type=Path, help="output Y-to-B CFL tile grid")
    parser.add_argument("--width", type=int, help="image width for AC strategy grid")
    parser.add_argument("--height", type=int, help="image height for metadata grids")
    args = parser.parse_args()

    if not args.trace_csv and not args.stream_csv and not args.stream_bin:
        raise SystemExit("at least one --trace-csv, --stream-csv, or --stream-bin input is required")

    try:
        rows = load_trace_rows(
            args.trace_csv,
            stream_paths=args.stream_csv,
            stream_bin_paths=args.stream_bin,
            last_bin_paths=args.last_bin,
            stream_word_bytes=args.stream_word_bytes,
            group_bits=args.group_bits,
            trace_value_bits=args.trace_value_bits,
            require_stream_final_last=args.require_stream_final_last,
        )
        if args.dc_tokens_npy is not None:
            write_token_npy(args.dc_tokens_npy, token_pairs(rows, TOKEN_STAGES["dc"]))
        if args.ac_metadata_tokens_npy is not None:
            write_token_npy(
                args.ac_metadata_tokens_npy,
                token_pairs(rows, TOKEN_STAGES["ac_metadata"]),
            )
        if args.ac_tokens_npy is not None:
            write_token_npy(args.ac_tokens_npy, token_pairs(rows, TOKEN_STAGES["ac"]))
        if args.ac_strategy_npy is not None:
            width, height = require_positive_dimensions(
                args.width,
                args.height,
                output="--ac-strategy-npy",
            )
            write_array_npy(
                args.ac_strategy_npy,
                ac_strategy_grid(rows, width, height),
            )
        if args.raw_quant_field_npy is not None:
            width, height = require_positive_dimensions(
                args.width,
                args.height,
                output="--raw-quant-field-npy",
            )
            write_array_npy(
                args.raw_quant_field_npy,
                uint8_grid(
                    rows,
                    stage=TRACE_STAGES["RawQuantField"],
                    width=width,
                    height=height,
                    label="raw quant-field",
                ),
            )
        if args.ytox_map_npy is not None:
            width, height = require_positive_dimensions(
                args.width,
                args.height,
                output="--ytox-map-npy",
            )
            write_array_npy(
                args.ytox_map_npy,
                int8_tile_grid(
                    rows,
                    stage=TRACE_STAGES["YtoxMap"],
                    width=width,
                    height=height,
                    label="Y-to-X CFL map",
                ),
            )
        if args.ytob_map_npy is not None:
            width, height = require_positive_dimensions(
                args.width,
                args.height,
                output="--ytob-map-npy",
            )
            write_array_npy(
                args.ytob_map_npy,
                int8_tile_grid(
                    rows,
                    stage=TRACE_STAGES["YtobMap"],
                    width=width,
                    height=height,
                    label="Y-to-B CFL map",
                ),
            )
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    if (
        args.dc_tokens_npy is None
        and args.ac_metadata_tokens_npy is None
        and args.ac_tokens_npy is None
        and args.ac_strategy_npy is None
        and args.raw_quant_field_npy is None
        and args.ytox_map_npy is None
        and args.ytob_map_npy is None
    ):
        print(f"read {len(rows)} trace rows", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
