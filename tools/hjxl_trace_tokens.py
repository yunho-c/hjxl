#!/usr/bin/env python3
"""Convert HJXL StageTrace CSV dumps into token-input NumPy arrays."""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
from pathlib import Path
import sys

from hjxl_stream_trace import decode_stream_rows, read_stream_csv


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


def parse_stage(value: str) -> int:
    stripped = value.strip()
    if stripped in TRACE_STAGES:
        return TRACE_STAGES[stripped]
    return int(stripped, 0)


def parse_int(value: str) -> int:
    return int(value.strip(), 0)


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
            try:
                rows.append(
                    TraceRow(
                        stage=parse_stage(row["stage"]),
                        group=parse_int(row["group"]),
                        index=parse_int(row["index"]),
                        value=parse_int(row["value"]),
                        source=str(path),
                        line=line,
                    )
                )
            except Exception as exc:  # pylint: disable=broad-exception-caught
                raise ValueError(f"{path}:{line}: invalid trace row: {row}") from exc
    return rows


def load_trace_rows(
    paths: list[Path],
    stream_paths: list[Path] | None = None,
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
    parser.add_argument("--width", type=int, help="image width for AC strategy grid")
    parser.add_argument("--height", type=int, help="image height for AC strategy grid")
    args = parser.parse_args()

    if not args.trace_csv and not args.stream_csv:
        raise SystemExit("at least one --trace-csv or --stream-csv input is required")

    rows = load_trace_rows(
        args.trace_csv,
        stream_paths=args.stream_csv,
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
        if args.width is None or args.height is None:
            raise SystemExit("--ac-strategy-npy requires --width and --height")
        write_array_npy(args.ac_strategy_npy, ac_strategy_grid(rows, args.width, args.height))
    if (
        args.dc_tokens_npy is None
        and args.ac_metadata_tokens_npy is None
        and args.ac_tokens_npy is None
        and args.ac_strategy_npy is None
    ):
        print(f"read {len(rows)} trace rows", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
