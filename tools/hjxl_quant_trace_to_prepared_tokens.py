#!/usr/bin/env python3
"""Convert quantization StageTrace CSV dumps into prepared-token simulator CSVs."""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
import json
from pathlib import Path
import sys

from hjxl_abi_generated import TRACE_STAGES_BY_SCALA_NAME

TRACE_STAGES = {
    name: TRACE_STAGES_BY_SCALA_NAME[name]
    for name in ("QuantDc", "QuantizedAc", "NumNonzeros")
}
BLOCK_DIM = 8
BLOCK_SIZE = BLOCK_DIM * BLOCK_DIM
TILE_DIM = 64
SIGNED_32_MIN = -(1 << 31)
SIGNED_32_MAX = (1 << 31) - 1


@dataclass(frozen=True)
class TraceRow:
    stage: int
    group: int
    index: int
    value: int
    source: str
    line: int


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
                    group=parse_int(row["group"], field="group", source=path, line=line),
                    index=parse_int(row["index"], field="index", source=path, line=line),
                    value=parse_int(row["value"], field="value", source=path, line=line),
                    source=str(path),
                    line=line,
                )
            )
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


def require_signed_32(row: TraceRow, label: str) -> None:
    if row.value < SIGNED_32_MIN or row.value > SIGNED_32_MAX:
        raise ValueError(
            f"{row.source}:{row.line}: {label} value {row.value} outside signed 32-bit range"
        )


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
            require_signed_32(
                row,
                f"QuantizedAc block {row.group} channel {channel} coefficient {coefficient}",
            )
            quantized_ac[row.group][channel][coefficient] = _assign_once(
                quantized_ac[row.group][channel][coefficient],
                row,
                f"QuantizedAc block {row.group} channel {channel} coefficient {coefficient}",
            )
        elif row.stage == TRACE_STAGES["QuantDc"]:
            if row.index < 0 or row.index >= 3:
                raise ValueError(f"{row.source}:{row.line}: QuantDc index {row.index} outside 0..2")
            require_signed_32(row, f"QuantDc block {row.group} channel {row.index}")
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


def image_geometry(width: int, height: int) -> dict[str, int]:
    if width <= 0 or height <= 0:
        raise ValueError("width and height must be positive")
    x_blocks = (width + BLOCK_DIM - 1) // BLOCK_DIM
    y_blocks = (height + BLOCK_DIM - 1) // BLOCK_DIM
    x_tiles = (width + TILE_DIM - 1) // TILE_DIM
    y_tiles = (height + TILE_DIM - 1) // TILE_DIM
    return {
        "xsize": width,
        "ysize": height,
        "x_blocks": x_blocks,
        "y_blocks": y_blocks,
        "x_tiles": x_tiles,
        "y_tiles": y_tiles,
    }


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


def prepared_token_fixture(
    *,
    width: int,
    height: int,
    quantized_dc: list[list[int]],
    quantized_ac: list[list[list[int]]],
    num_nonzeros: list[list[int]],
) -> dict:
    image = image_geometry(width, height)
    x_blocks = image["x_blocks"]
    dc_samples = [
        int(value)
        for channel in (1, 0, 2)
        for block_dc in quantized_dc
        for value in (block_dc[channel],)
    ]
    ac_blocks = []
    for block, block_ac in enumerate(quantized_ac):
        block_x = block % x_blocks
        block_y = block // x_blocks
        ac_blocks.append(
            {
                "block_index": block,
                "block_x": block_x,
                "block_y": block_y,
                "tile_x": block_x // (TILE_DIM // BLOCK_DIM),
                "tile_y": block_y // (TILE_DIM // BLOCK_DIM),
                "num_nonzeros": [int(value) for value in num_nonzeros[block]],
                "quantized": [
                    [int(value) for value in coefficients]
                    for coefficients in block_ac
                ],
            }
        )
    return {
        "format": "hjxl.fixed_dct_only_prepared_token_inputs.v1",
        "image": image,
        "dc_sample_order": "Y-plane raster, X-plane raster, B-plane raster",
        "dc_samples": dc_samples,
        "ac_block_order": "raster blocks, each block stores X/Y/B channels",
        "ac_blocks": ac_blocks,
    }


def write_prepared_json(
    path: Path,
    *,
    width: int,
    height: int,
    quantized_dc: list[list[int]],
    quantized_ac: list[list[list[int]]],
    num_nonzeros: list[list[int]],
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fixture = prepared_token_fixture(
        width=width,
        height=height,
        quantized_dc=quantized_dc,
        quantized_ac=quantized_ac,
        num_nonzeros=num_nonzeros,
    )
    path.write_text(json.dumps(fixture, indent=2, sort_keys=True) + "\n", encoding="utf-8")


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
    parser.add_argument(
        "--prepared-json",
        type=Path,
        help="optional output prepared-token JSON fixture with geometry and block coordinates",
    )
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

    if args.dc_csv is None and args.ac_csv is None and args.prepared_json is None:
        raise SystemExit("at least one output path is required")
    if args.dc_csv is not None:
        write_dc_csv(args.dc_csv, quantized_dc)
    if args.ac_csv is not None:
        write_ac_csv(args.ac_csv, quantized_ac, num_nonzeros)
    if args.prepared_json is not None:
        write_prepared_json(
            args.prepared_json,
            width=args.width,
            height=args.height,
            quantized_dc=quantized_dc,
            quantized_ac=quantized_ac,
            num_nonzeros=num_nonzeros,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
