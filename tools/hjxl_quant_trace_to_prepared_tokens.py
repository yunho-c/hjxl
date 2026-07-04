#!/usr/bin/env python3
"""Convert quantization StageTrace CSV dumps into prepared-token simulator CSVs."""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
from pathlib import Path
import sys


TRACE_STAGES = {
    "QuantDc": 7,
    "QuantizedAc": 8,
    "NumNonzeros": 9,
}
BLOCK_DIM = 8
BLOCK_SIZE = BLOCK_DIM * BLOCK_DIM


@dataclass(frozen=True)
class TraceRow:
    stage: int
    group: int
    index: int
    value: int
    source: str
    line: int


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


def load_trace_rows(paths: list[Path]) -> list[TraceRow]:
    rows: list[TraceRow] = []
    for path in paths:
        rows.extend(read_trace_csv(path))
    return rows


def _assign_once(slot, row: TraceRow, label: str):
    if slot is not None:
        raise ValueError(f"{row.source}:{row.line}: duplicate {label}")
    return row.value


def collect_quantized_blocks(
    rows: list[TraceRow],
    width: int,
    height: int,
) -> tuple[list[list[int]], list[list[list[int]]], list[list[int]]]:
    if width <= 0 or height <= 0:
        raise ValueError("width and height must be positive")

    x_blocks = (width + BLOCK_DIM - 1) // BLOCK_DIM
    y_blocks = (height + BLOCK_DIM - 1) // BLOCK_DIM
    block_count = x_blocks * y_blocks
    quantized_dc: list[list[int | None]] = [[None for _ in range(3)] for _ in range(block_count)]
    quantized_ac: list[list[list[int | None]]] = [
        [[None for _ in range(BLOCK_SIZE)] for _ in range(3)] for _ in range(block_count)
    ]
    num_nonzeros: list[list[int | None]] = [[None for _ in range(3)] for _ in range(block_count)]

    for row in rows:
        if row.stage not in TRACE_STAGES.values():
            continue
        if row.group < 0 or row.group >= block_count:
            raise ValueError(
                f"{row.source}:{row.line}: quant trace group {row.group} outside 0..{block_count - 1}"
            )

        if row.stage == TRACE_STAGES["QuantizedAc"]:
            if row.index < 0 or row.index >= 3 * BLOCK_SIZE:
                raise ValueError(
                    f"{row.source}:{row.line}: QuantizedAc index {row.index} outside 0..{3 * BLOCK_SIZE - 1}"
                )
            channel = row.index // BLOCK_SIZE
            coefficient = row.index % BLOCK_SIZE
            quantized_ac[row.group][channel][coefficient] = _assign_once(
                quantized_ac[row.group][channel][coefficient],
                row,
                f"QuantizedAc block {row.group} channel {channel} coefficient {coefficient}",
            )
        elif row.stage == TRACE_STAGES["QuantDc"]:
            if row.index < 0 or row.index >= 3:
                raise ValueError(f"{row.source}:{row.line}: QuantDc index {row.index} outside 0..2")
            quantized_dc[row.group][row.index] = _assign_once(
                quantized_dc[row.group][row.index],
                row,
                f"QuantDc block {row.group} channel {row.index}",
            )
        elif row.stage == TRACE_STAGES["NumNonzeros"]:
            if row.index < 0 or row.index >= 3:
                raise ValueError(
                    f"{row.source}:{row.line}: NumNonzeros index {row.index} outside 0..2"
                )
            if row.value < 0 or row.value > 63:
                raise ValueError(
                    f"{row.source}:{row.line}: NumNonzeros value {row.value} outside 0..63"
                )
            num_nonzeros[row.group][row.index] = _assign_once(
                num_nonzeros[row.group][row.index],
                row,
                f"NumNonzeros block {row.group} channel {row.index}",
            )

    dc_out: list[list[int]] = []
    ac_out: list[list[list[int]]] = []
    nnz_out: list[list[int]] = []
    for block in range(block_count):
        block_dc: list[int] = []
        block_nnz: list[int] = []
        block_ac: list[list[int]] = []
        for channel in range(3):
            dc_value = quantized_dc[block][channel]
            if dc_value is None:
                raise ValueError(f"missing QuantDc block {block} channel {channel}")
            block_dc.append(dc_value)

            nonzeros = num_nonzeros[block][channel]
            if nonzeros is None:
                raise ValueError(f"missing NumNonzeros block {block} channel {channel}")
            block_nnz.append(nonzeros)

            coefficients = quantized_ac[block][channel]
            missing = [index for index, value in enumerate(coefficients) if value is None]
            if missing:
                raise ValueError(
                    f"missing QuantizedAc block {block} channel {channel} coefficients {missing[:8]}"
                )
            channel_coefficients = [int(value) for value in coefficients]
            actual_nonzeros = sum(1 for value in channel_coefficients[1:] if value != 0)
            if actual_nonzeros != nonzeros:
                raise ValueError(
                    f"block {block} channel {channel} declares {nonzeros} nonzeros "
                    f"but coefficients contain {actual_nonzeros}"
                )
            block_ac.append(channel_coefficients)
        dc_out.append(block_dc)
        ac_out.append(block_ac)
        nnz_out.append(block_nnz)
    return dc_out, ac_out, nnz_out


def write_dc_csv(path: Path, quantized_dc: list[list[int]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["ordinal", "value"])
        ordinal = 0
        for channel in (1, 0, 2):
            for block_dc in quantized_dc:
                writer.writerow([ordinal, int(block_dc[channel])])
                ordinal += 1


def write_ac_csv(
    path: Path,
    quantized_ac: list[list[list[int]]],
    num_nonzeros: list[list[int]],
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["block", "channel", "nonzeros", "coefficients"])
        for block, block_ac in enumerate(quantized_ac):
            for channel, coefficients in enumerate(block_ac):
                writer.writerow(
                    [
                        block,
                        channel,
                        int(num_nonzeros[block][channel]),
                        " ".join(str(int(value)) for value in coefficients),
                    ]
                )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--trace-csv",
        type=Path,
        action="append",
        required=True,
        help="StageTrace CSV input with quantization rows; may be repeated",
    )
    parser.add_argument("--width", type=int, required=True, help="source image width")
    parser.add_argument("--height", type=int, required=True, help="source image height")
    parser.add_argument("--dc-csv", type=Path, help="output prepared DC sample CSV")
    parser.add_argument("--ac-csv", type=Path, help="output prepared AC block/channel CSV")
    args = parser.parse_args()

    try:
        quantized_dc, quantized_ac, num_nonzeros = collect_quantized_blocks(
            load_trace_rows(args.trace_csv),
            args.width,
            args.height,
        )
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    if args.dc_csv is None and args.ac_csv is None:
        raise SystemExit("at least one output path is required")
    if args.dc_csv is not None:
        write_dc_csv(args.dc_csv, quantized_dc)
    if args.ac_csv is not None:
        write_ac_csv(args.ac_csv, quantized_ac, num_nonzeros)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
