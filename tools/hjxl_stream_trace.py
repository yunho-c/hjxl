#!/usr/bin/env python3
"""Convert packed HJXL AXI-stream trace words into StageTrace CSV rows."""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
from pathlib import Path
import sys
from typing import TextIO


@dataclass(frozen=True)
class StreamTraceRow:
    data: int
    last: bool
    source: str
    line: int


@dataclass(frozen=True)
class StageTraceRow:
    stage: int
    group: int
    index: int
    value: int
    last: bool
    source: str
    line: int


def parse_int(value: str) -> int:
    return int(value.strip(), 0)


def parse_bool(value: str) -> bool:
    stripped = value.strip().lower()
    if stripped in {"1", "true", "t", "yes", "y"}:
        return True
    if stripped in {"0", "false", "f", "no", "n"}:
        return False
    raise ValueError(f"invalid boolean value {value!r}")


def _column(fieldnames: list[str], *candidates: str) -> str:
    lowered = {name.lower(): name for name in fieldnames}
    for candidate in candidates:
        if candidate.lower() in lowered:
            return lowered[candidate.lower()]
    raise ValueError(f"missing CSV column: expected one of {', '.join(candidates)}")


def read_stream_csv(path: Path) -> list[StreamTraceRow]:
    rows: list[StreamTraceRow] = []
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames is None:
            raise ValueError(f"{path}: missing CSV header")
        data_column = _column(reader.fieldnames, "data", "tdata")
        last_column = _column(reader.fieldnames, "last", "tlast")
        for line, row in enumerate(reader, start=2):
            try:
                data = parse_int(row[data_column])
                if data < 0:
                    raise ValueError("data must be nonnegative")
                rows.append(
                    StreamTraceRow(
                        data=data,
                        last=parse_bool(row[last_column]),
                        source=str(path),
                        line=line,
                    )
                )
            except Exception as exc:  # pylint: disable=broad-exception-caught
                raise ValueError(f"{path}:{line}: invalid stream row: {row}") from exc
    return rows


def sign_extend(value: int, bits: int) -> int:
    sign_bit = 1 << (bits - 1)
    mask = (1 << bits) - 1
    value &= mask
    return value - (1 << bits) if value & sign_bit else value


def decode_stream_rows(
    rows: list[StreamTraceRow],
    group_bits: int,
    trace_value_bits: int,
    require_final_last: bool,
) -> list[StageTraceRow]:
    if group_bits <= 0:
        raise ValueError("group_bits must be positive")
    if trace_value_bits <= 0:
        raise ValueError("trace_value_bits must be positive")

    stage_bits = 8
    index_bits = 32
    group_mask = (1 << group_bits) - 1
    index_mask = (1 << index_bits) - 1
    max_word = 1 << (stage_bits + group_bits + index_bits + trace_value_bits)
    decoded: list[StageTraceRow] = []
    for row in rows:
        if row.data >= max_word:
            raise ValueError(f"{row.source}:{row.line}: data word exceeds configured packed trace width")
        stage = row.data & 0xff
        group = (row.data >> stage_bits) & group_mask
        index = (row.data >> (stage_bits + group_bits)) & index_mask
        raw_value = row.data >> (stage_bits + group_bits + index_bits)
        decoded.append(
            StageTraceRow(
                stage=stage,
                group=group,
                index=index,
                value=sign_extend(raw_value, trace_value_bits),
                last=row.last,
                source=row.source,
                line=row.line,
            )
        )

    if require_final_last:
        if not decoded:
            raise ValueError("--require-final-last needs at least one stream row")
        early_last = [row for row in decoded[:-1] if row.last]
        if early_last:
            first = early_last[0]
            raise ValueError(f"{first.source}:{first.line}: TLAST asserted before final row")
        if not decoded[-1].last:
            last = decoded[-1]
            raise ValueError(f"{last.source}:{last.line}: final stream row does not assert TLAST")
    return decoded


def write_trace_csv(path: Path | None, rows: list[StageTraceRow]) -> None:
    if path is None:
        handle: TextIO = sys.stdout
        close = False
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        handle = path.open("w", encoding="utf-8", newline="")
        close = True
    try:
        writer = csv.writer(handle)
        writer.writerow(["stage", "group", "index", "value"])
        for row in rows:
            writer.writerow([row.stage, row.group, row.index, row.value])
    finally:
        if close:
            handle.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--stream-csv",
        type=Path,
        action="append",
        required=True,
        help="packed stream CSV input with data,last columns; tdata,tlast aliases are also accepted",
    )
    parser.add_argument("--trace-csv", type=Path, help="output StageTrace CSV; stdout if omitted")
    parser.add_argument("--group-bits", type=int, default=16, help="packed StageTrace group width")
    parser.add_argument("--trace-value-bits", type=int, default=32, help="packed StageTrace value width")
    parser.add_argument(
        "--require-final-last",
        action="store_true",
        help="require TLAST only on the final decoded row",
    )
    args = parser.parse_args()

    try:
        stream_rows: list[StreamTraceRow] = []
        for path in args.stream_csv:
            stream_rows.extend(read_stream_csv(path))
        trace_rows = decode_stream_rows(
            stream_rows,
            group_bits=args.group_bits,
            trace_value_bits=args.trace_value_bits,
            require_final_last=args.require_final_last,
        )
        write_trace_csv(args.trace_csv, trace_rows)
    except Exception as exc:  # pylint: disable=broad-exception-caught
        print(f"error: {exc}", file=sys.stderr)
        return 1
    if args.trace_csv is not None:
        print(f"decoded {len(trace_rows)} stream trace rows")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
