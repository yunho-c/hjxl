#!/usr/bin/env python3
"""Convert an HJXL manifest stream CSV into replayable binary buffers."""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path


SUPPORTED_FORMATS = {
    "hjxl.rgb_stream_manifest.v1",
    "hjxl.prepared_dct_stream_manifest.v1",
}


def _manifest_path(manifest_path: Path, raw_path: str | None) -> Path | None:
    if raw_path is None:
        return None
    path = Path(raw_path)
    if path.is_absolute() or path.exists():
        return path
    return manifest_path.parent / path


def _column(fieldnames: list[str], *candidates: str) -> str:
    lowered = {name.lower(): name for name in fieldnames}
    for candidate in candidates:
        if candidate.lower() in lowered:
            return lowered[candidate.lower()]
    raise ValueError(f"missing CSV column: expected one of {', '.join(candidates)}")


def _parse_bool(value: str) -> bool:
    stripped = value.strip().lower()
    if stripped in {"1", "true", "t", "yes", "y"}:
        return True
    if stripped in {"0", "false", "f", "no", "n"}:
        return False
    raise ValueError(f"invalid boolean value {value!r}")


def _input_data_bits(manifest: dict) -> int:
    stream = manifest["stream"]
    if "pixel_bits" in stream:
        return int(stream["pixel_bits"]) * 3
    return 32


def _read_stream_csv(path: Path) -> list[tuple[int, bool]]:
    rows: list[tuple[int, bool]] = []
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames is None:
            raise ValueError(f"{path}: missing CSV header")
        data_column = _column(reader.fieldnames, "data", "tdata")
        last_column = _column(reader.fieldnames, "last", "tlast")
        for line, row in enumerate(reader, start=2):
            try:
                data = int(row[data_column], 0)
                last = _parse_bool(row[last_column])
            except Exception as exc:  # pylint: disable=broad-exception-caught
                raise ValueError(f"{path}:{line}: invalid stream row: {row}") from exc
            if data < 0:
                raise ValueError(f"{path}:{line}: stream data must be nonnegative")
            rows.append((data, last))
    return rows


def _validate_rows(
    rows: list[tuple[int, bool]],
    *,
    source: Path,
    expected_word_count: int,
    input_data_bits: int,
) -> None:
    if len(rows) != expected_word_count:
        raise ValueError(f"{source}: expected {expected_word_count} stream rows, got {len(rows)}")
    if not rows:
        raise ValueError(f"{source}: stream CSV must contain at least one row")
    max_word = 1 << input_data_bits
    for index, (data, last) in enumerate(rows):
        if data >= max_word:
            raise ValueError(f"{source}: row {index + 2} data exceeds {input_data_bits}-bit stream width")
        if index == len(rows) - 1:
            if not last:
                raise ValueError(f"{source}: final stream row does not assert last")
        elif last:
            raise ValueError(f"{source}: last asserted before final row at index {index}")


def write_stream_buffers(
    *,
    manifest_path: Path,
    stream_bin: Path,
    last_bin: Path | None,
) -> tuple[int, int]:
    with manifest_path.open("r", encoding="utf-8") as handle:
        manifest = json.load(handle)
    manifest_format = str(manifest.get("format", ""))
    if manifest_format not in SUPPORTED_FORMATS:
        raise ValueError(f"{manifest_path}: unsupported manifest format {manifest_format!r}")

    stream = manifest["stream"]
    stream_csv = _manifest_path(manifest_path, stream.get("csv"))
    if stream_csv is None:
        raise ValueError(f"{manifest_path}: missing stream CSV path")
    expected_word_count = int(stream["word_count"])
    input_data_bits = _input_data_bits(manifest)
    input_data_bytes = (input_data_bits + 7) // 8
    rows = _read_stream_csv(stream_csv)
    _validate_rows(
        rows,
        source=stream_csv,
        expected_word_count=expected_word_count,
        input_data_bits=input_data_bits,
    )

    stream_bin.parent.mkdir(parents=True, exist_ok=True)
    with stream_bin.open("wb") as handle:
        for data, _ in rows:
            handle.write(data.to_bytes(input_data_bytes, byteorder="little", signed=False))

    if last_bin is not None:
        last_bin.parent.mkdir(parents=True, exist_ok=True)
        last_bin.write_bytes(bytes(1 if last else 0 for _, last in rows))

    return len(rows), input_data_bytes


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest-json", type=Path, required=True, help="HJXL stream/control manifest")
    parser.add_argument("--stream-bin", type=Path, required=True, help="output little-endian TDATA buffer")
    parser.add_argument("--last-bin", type=Path, help="optional one-byte-per-word TLAST sidecar")
    args = parser.parse_args()

    try:
        word_count, word_bytes = write_stream_buffers(
            manifest_path=args.manifest_json,
            stream_bin=args.stream_bin,
            last_bin=args.last_bin,
        )
    except (OSError, KeyError, TypeError, ValueError) as exc:
        raise SystemExit(f"error: {exc}") from exc

    print(f"wrote {word_count} stream words ({word_bytes} bytes/word) to {args.stream_bin}")
    if args.last_bin is not None:
        print(f"wrote TLAST sidecar to {args.last_bin}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
