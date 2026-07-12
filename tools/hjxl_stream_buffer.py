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


def validate_manifest_json(manifest_path: Path, manifest_format: str) -> None:
    if manifest_format == "hjxl.rgb_stream_manifest.v1":
        from hjxl_rgb_stream import validate_manifest_json as validate_rgb_manifest

        validate_rgb_manifest(manifest_path)
        return
    if manifest_format == "hjxl.prepared_dct_stream_manifest.v1":
        from hjxl_prepared_blocks import validate_manifest_json as validate_prepared_manifest

        validate_prepared_manifest(manifest_path)
        return
    raise ValueError(f"{manifest_path}: unsupported manifest format {manifest_format!r}")


def _manifest_path(manifest_path: Path, raw_path: str | None) -> Path | None:
    if raw_path is None:
        return None
    path = Path(raw_path)
    if path.is_absolute():
        return path
    manifest_relative = manifest_path.parent / path
    if manifest_relative.exists():
        return manifest_relative
    if path.exists():
        return path
    return manifest_relative


def _column(fieldnames: list[str], *candidates: str) -> str:
    lowered = {name.lower(): name for name in fieldnames}
    for candidate in candidates:
        if candidate.lower() in lowered:
            return lowered[candidate.lower()]
    raise ValueError(f"missing CSV column: expected one of {', '.join(candidates)}")


def _parse_int(value: str | None, *, field: str, source: Path, line: int) -> int:
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


def _manifest_int(value: object, *, field: str, source: Path) -> int:
    if value is None:
        raise ValueError(f"{source}: {field} is required")
    if isinstance(value, bool):
        raise ValueError(f"{source}: {field} must be an integer, got {value!r}")
    if isinstance(value, float):
        raise ValueError(f"{source}: {field} must be an integer, got {value!r}")
    try:
        if isinstance(value, str):
            return int(value.strip(), 0)
        return int(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{source}: {field} must be an integer, got {value!r}") from exc


def _parse_bool(value: str | None, *, field: str, source: Path, line: int) -> bool:
    if value is None:
        raise ValueError(f"{source}:{line}: {field} is required")
    stripped = value.strip().lower()
    if not stripped:
        raise ValueError(f"{source}:{line}: {field} is required")
    if stripped in {"1", "true", "t", "yes", "y"}:
        return True
    if stripped in {"0", "false", "f", "no", "n"}:
        return False
    raise ValueError(f"{source}:{line}: {field} must be boolean, got {value!r}")


def _input_data_bits(manifest: dict, *, source: Path) -> int:
    stream = manifest["stream"]
    if "pixel_bits" in stream:
        return _manifest_int(stream["pixel_bits"], field="stream.pixel_bits", source=source) * 3
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
            data = _parse_int(
                row[data_column],
                field=data_column,
                source=path,
                line=line,
            )
            last = _parse_bool(
                row[last_column],
                field=last_column,
                source=path,
                line=line,
            )
            if data < 0:
                raise ValueError(f"{path}:{line}: {data_column} must be nonnegative")
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


def stream_rows_from_manifest(manifest_path: Path) -> tuple[list[tuple[int, bool]], int, int]:
    with manifest_path.open("r", encoding="utf-8") as handle:
        manifest = json.load(handle)
    manifest_format = str(manifest.get("format", ""))
    if manifest_format not in SUPPORTED_FORMATS:
        raise ValueError(f"{manifest_path}: unsupported manifest format {manifest_format!r}")
    validate_manifest_json(manifest_path, manifest_format)

    stream = manifest["stream"]
    stream_csv = _manifest_path(manifest_path, stream.get("csv"))
    if stream_csv is None:
        raise ValueError(f"{manifest_path}: missing stream CSV path")
    expected_word_count = _manifest_int(stream.get("word_count"), field="stream.word_count", source=manifest_path)
    input_data_bits = _input_data_bits(manifest, source=manifest_path)
    input_data_bytes = (input_data_bits + 7) // 8
    rows = _read_stream_csv(stream_csv)
    _validate_rows(
        rows,
        source=stream_csv,
        expected_word_count=expected_word_count,
        input_data_bits=input_data_bits,
    )
    return rows, input_data_bits, input_data_bytes


def write_stream_buffers(
    *,
    manifest_path: Path,
    stream_bin: Path,
    last_bin: Path | None,
) -> tuple[int, int]:
    rows, _, input_data_bytes = stream_rows_from_manifest(manifest_path)

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
