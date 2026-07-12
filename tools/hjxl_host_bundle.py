#!/usr/bin/env python3
"""Generate host replay artifacts from an HJXL stream/control manifest."""

from __future__ import annotations

import argparse
import copy
import csv
import hashlib
import json
from pathlib import Path
import shutil

from hjxl_manifest_header import (
    AXI_LITE_ADDR_BITS,
    AXI_LITE_DATA_BITS,
    AXI_LITE_STRB_BITS,
    KV260_TRACE_CAPTURE_WORD_BYTES,
    TRACE_GROUP_BITS,
    TRACE_GROUP_BYTE_OFFSET,
    TRACE_GROUP_MASK,
    TRACE_GROUP_SHIFT,
    TRACE_INDEX_BITS,
    TRACE_INDEX_BYTE_OFFSET,
    TRACE_INDEX_MASK,
    TRACE_INDEX_SHIFT,
    TRACE_PACKED_BITS,
    TRACE_PACKED_BYTES,
    TRACE_STAGE_BITS,
    TRACE_STAGE_BYTE_OFFSET,
    TRACE_STAGE_MASK,
    TRACE_STAGE_SHIFT,
    TRACE_TKEEP_MASK,
    TRACE_VALUE_BITS,
    TRACE_VALUE_BYTE_OFFSET,
    TRACE_VALUE_MASK,
    TRACE_VALUE_SHIFT,
    coefficient_fraction_bits,
    distance_metadata,
    header_text,
    input_data_bits,
    input_keep_mask,
    register_writes,
    target_metadata,
    trace_route_metadata,
)
from hjxl_stream_buffer import stream_rows_from_manifest, write_stream_buffers


def _safe_name(value: str) -> str:
    cleaned = "".join(ch if ch.isalnum() or ch in "-_" else "-" for ch in value).strip("-_")
    if not cleaned:
        raise ValueError("bundle name must contain at least one letter or digit")
    return cleaned


def _resolve_index_path(index_path: Path, raw_path: str | None) -> Path | None:
    if raw_path is None:
        return None
    path = Path(raw_path)
    if path.is_absolute():
        return path
    index_relative = index_path.parent / path
    if index_relative.exists():
        return index_relative
    if path.exists():
        return path
    return index_relative


def _manifest_relative_path(manifest_path: Path, raw_path: str | None) -> Path | None:
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


def _artifact_path(path: Path, base: Path) -> str:
    try:
        return str(path.relative_to(base))
    except ValueError:
        return str(path)


def _resolved_path(path: Path | None) -> str | None:
    if path is None:
        return None
    return str(path.resolve())


def _expected_stream_bytes(rows: list[tuple[int, bool]], word_bytes: int) -> bytes:
    return b"".join(
        data.to_bytes(word_bytes, byteorder="little", signed=False)
        for data, _ in rows
    )


def _stream_metadata(
    manifest: dict,
    *,
    word_count: int,
    input_data_bits_value: int,
    input_data_bytes: int,
) -> dict:
    metadata = {
        "word_count": word_count,
        "input_data_bits": input_data_bits_value,
        "input_data_bytes": input_data_bytes,
        "input_keep_mask": input_keep_mask(manifest),
        "byte_count": word_count * input_data_bytes,
    }
    fraction_bits = coefficient_fraction_bits(manifest)
    if fraction_bits:
        metadata["coefficient_fraction_bits"] = fraction_bits
    return metadata


def _trace_metadata() -> dict:
    return {
        "stage_bits": TRACE_STAGE_BITS,
        "group_bits": TRACE_GROUP_BITS,
        "index_bits": TRACE_INDEX_BITS,
        "trace_value_bits": TRACE_VALUE_BITS,
        "stage_shift": TRACE_STAGE_SHIFT,
        "group_shift": TRACE_GROUP_SHIFT,
        "index_shift": TRACE_INDEX_SHIFT,
        "trace_value_shift": TRACE_VALUE_SHIFT,
        "stage_byte_offset": TRACE_STAGE_BYTE_OFFSET,
        "group_byte_offset": TRACE_GROUP_BYTE_OFFSET,
        "index_byte_offset": TRACE_INDEX_BYTE_OFFSET,
        "trace_value_byte_offset": TRACE_VALUE_BYTE_OFFSET,
        "stage_mask": TRACE_STAGE_MASK,
        "group_mask": TRACE_GROUP_MASK,
        "index_mask": TRACE_INDEX_MASK,
        "trace_value_mask": TRACE_VALUE_MASK,
        "packed_bits": TRACE_PACKED_BITS,
        "packed_bytes": TRACE_PACKED_BYTES,
        "tkeep_mask": TRACE_TKEEP_MASK,
        "default_capture_word_bytes": KV260_TRACE_CAPTURE_WORD_BYTES,
    }


def _register_map_metadata(manifest: dict) -> dict:
    return {
        name: int(address)
        for name, address in manifest["axi_lite"]["register_map"].items()
    }


def _axi_lite_metadata(write_count: int, manifest: dict | None = None) -> dict:
    metadata = {
        "addr_bits": AXI_LITE_ADDR_BITS,
        "data_bits": AXI_LITE_DATA_BITS,
        "strb_bits": AXI_LITE_STRB_BITS,
        "write_count": write_count,
    }
    if manifest is not None:
        metadata["register_map"] = _register_map_metadata(manifest)
    return metadata


def _frame_metadata(manifest: dict) -> dict:
    image = manifest.get("image", {})
    config = manifest["axi_lite"]["config"]
    xsize = int(config["xsize"])
    ysize = int(config["ysize"])
    image_xsize = int(image.get("xsize", image.get("width", xsize)))
    image_ysize = int(image.get("ysize", image.get("height", ysize)))
    if image_xsize != xsize or image_ysize != ysize:
        raise ValueError("manifest image dimensions do not match AXI-Lite config")
    x_blocks = int(image.get("x_blocks", (xsize + 7) // 8))
    y_blocks = int(image.get("y_blocks", (ysize + 7) // 8))
    if x_blocks <= 0 or y_blocks <= 0:
        raise ValueError("manifest frame block grid must be positive")
    padded_xsize = x_blocks * 8
    padded_ysize = y_blocks * 8
    if padded_xsize < xsize or padded_xsize >= xsize + 8:
        raise ValueError("manifest frame x block grid does not cover xsize")
    if padded_ysize < ysize or padded_ysize >= ysize + 8:
        raise ValueError("manifest frame y block grid does not cover ysize")
    x_tiles = int(image.get("x_tiles", (xsize + 63) // 64))
    y_tiles = int(image.get("y_tiles", (ysize + 63) // 64))
    if x_tiles <= 0 or y_tiles <= 0:
        raise ValueError("manifest frame tile grid must be positive")
    if x_tiles * 64 < xsize or x_tiles * 64 >= xsize + 64:
        raise ValueError("manifest frame x tile grid does not cover xsize")
    if y_tiles * 64 < ysize or y_tiles * 64 >= ysize + 64:
        raise ValueError("manifest frame y tile grid does not cover ysize")
    return {
        "xsize": xsize,
        "ysize": ysize,
        "x_blocks": x_blocks,
        "y_blocks": y_blocks,
        "padded_xsize": padded_xsize,
        "padded_ysize": padded_ysize,
        "block_count": x_blocks * y_blocks,
        "x_tiles": x_tiles,
        "y_tiles": y_tiles,
        "tile_count": x_tiles * y_tiles,
    }


def _validate_optional_metadata(
    actual: dict,
    expected: dict,
    *,
    label: str,
    source: Path,
) -> None:
    for key, actual_value in actual.items():
        if key not in expected:
            raise ValueError(f"{source}: {label} metadata contains unknown field {key}")
        expected_value = expected[key]
        field = f"{label}.{key}"
        if isinstance(expected_value, dict):
            if not isinstance(actual_value, dict):
                raise ValueError(f"{source}: {field} must be an object")
            _validate_optional_metadata(
                actual_value,
                expected_value,
                label=field,
                source=source,
            )
        elif isinstance(expected_value, bool):
            if actual_value is not expected_value:
                raise ValueError(f"{source}: {label} metadata does not match RTL {label}")
        elif isinstance(expected_value, int):
            if (
                _metadata_int(actual_value, source=source, field=field)
                != expected_value
            ):
                raise ValueError(f"{source}: {label} metadata does not match RTL {label}")
        elif actual_value != expected_value:
            raise ValueError(f"{source}: {label} metadata does not match RTL {label}")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _bundle_file_checksums(index_path: Path, index: dict, manifest: dict) -> dict[str, str]:
    artifacts = index["artifacts"]
    raw_paths: dict[str, str | None] = {
        "source_manifest": index.get("source_manifest"),
        "header": artifacts.get("header"),
        "stream_bin": artifacts.get("stream_bin"),
        "last_bin": artifacts.get("last_bin"),
        "stream_csv": manifest["stream"].get("csv"),
        "axi_lite_csv": manifest["axi_lite"].get("csv"),
    }
    checksums: dict[str, str] = {}
    for name, raw_path in raw_paths.items():
        path = _resolve_index_path(index_path, raw_path)
        if path is not None:
            checksums[name] = _sha256(path)
    return checksums


def validate_checksums(index_path: Path, index: dict, manifest: dict) -> None:
    expected = index.get("checksums", {}).get("sha256")
    if expected is None:
        raise ValueError(f"{index_path}: missing SHA-256 artifact checksums")
    actual = _bundle_file_checksums(index_path, index, manifest)
    if set(actual) != set(expected):
        raise ValueError(f"{index_path}: checksum artifact set does not match bundle artifacts")
    for name, actual_digest in actual.items():
        if str(expected[name]).lower() != actual_digest:
            raise ValueError(f"{index_path}: checksum mismatch for {name}")


def _column(fieldnames: list[str], *candidates: str) -> str:
    lowered = {name.lower(): name for name in fieldnames}
    for candidate in candidates:
        if candidate.lower() in lowered:
            return lowered[candidate.lower()]
    raise ValueError(f"missing CSV column: expected one of {', '.join(candidates)}")


def _parse_int(value: str | None, *, source: Path, line: int, field: str) -> int:
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


def _metadata_int(value: object, *, source: Path, field: str) -> int:
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
        raise ValueError(
            f"{source}: {field} must be an integer, got {value!r}"
        ) from exc


def _read_axi_lite_rows(path: Path) -> list[tuple[int, int, int]]:
    rows: list[tuple[int, int, int]] = []
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames is None:
            raise ValueError(f"{path}: missing CSV header")
        address_column = _column(reader.fieldnames, "address", "addr", "awaddr")
        data_column = _column(reader.fieldnames, "data", "wdata")
        strb_column = _column(reader.fieldnames, "strb", "wstrb")
        for line, row in enumerate(reader, start=2):
            rows.append(
                (
                    _parse_int(
                        row[address_column],
                        source=path,
                        line=line,
                        field=address_column,
                    ),
                    _parse_int(
                        row[data_column],
                        source=path,
                        line=line,
                        field=data_column,
                    ),
                    _parse_int(
                        row[strb_column],
                        source=path,
                        line=line,
                        field=strb_column,
                    ),
                )
            )
    return rows


def validate_axi_lite_csv(manifest_path: Path, manifest: dict) -> None:
    axi_lite_csv = _manifest_relative_path(manifest_path, manifest["axi_lite"].get("csv"))
    if axi_lite_csv is None:
        raise ValueError(f"{manifest_path}: missing AXI-Lite CSV path")
    expected = [(address, data, strb) for _, address, data, strb in register_writes(manifest)]
    actual = _read_axi_lite_rows(axi_lite_csv)
    if actual != expected:
        raise ValueError(f"{axi_lite_csv}: AXI-Lite control rows do not match source manifest")


def validate_host_bundle(index_path: Path) -> dict:
    with index_path.open("r", encoding="utf-8") as handle:
        index = json.load(handle)
    if index.get("format") != "hjxl.host_bundle.v1":
        raise ValueError(f"{index_path}: unsupported bundle format {index.get('format')!r}")

    manifest_path = _resolve_index_path(index_path, index.get("source_manifest"))
    if manifest_path is None:
        raise ValueError(f"{index_path}: missing source_manifest")
    with manifest_path.open("r", encoding="utf-8") as handle:
        manifest = json.load(handle)
    if index.get("manifest_format") != manifest.get("format"):
        raise ValueError(f"{index_path}: manifest_format does not match source manifest")
    expected_target = target_metadata(manifest)
    if "target" in index and index["target"] != expected_target:
        raise ValueError(f"{index_path}: target metadata does not match source manifest")
    expected_distance = distance_metadata()
    if "distance" in index and index["distance"] != expected_distance:
        raise ValueError(f"{index_path}: distance metadata does not match RTL lookup")
    expected_frame = _frame_metadata(manifest)
    if "frame" in index:
        for key, actual_value in index["frame"].items():
            if key not in expected_frame:
                raise ValueError(f"{index_path}: frame metadata contains unknown field {key}")
            expected_value = expected_frame[key]
            if isinstance(expected_value, int):
                actual_value = _metadata_int(
                    actual_value,
                    source=index_path,
                    field=f"frame.{key}",
                )
            if actual_value != expected_value:
                raise ValueError(f"{index_path}: frame metadata does not match source manifest")
    expected_trace_route = trace_route_metadata(manifest)
    if "trace_route" in index and index["trace_route"] != expected_trace_route:
        raise ValueError(f"{index_path}: trace_route metadata does not match source manifest")
    expected_trace = _trace_metadata()
    if "trace" in index:
        try:
            _validate_optional_metadata(index["trace"], expected_trace, label="trace", source=index_path)
        except ValueError as exc:
            if "does not match" in str(exc):
                raise ValueError(f"{index_path}: trace metadata does not match RTL trace packing") from exc
            raise

    rows, input_data_bits, input_data_bytes = stream_rows_from_manifest(manifest_path)
    stream = index["stream"]
    if _metadata_int(
        stream.get("word_count"),
        source=index_path,
        field="stream.word_count",
    ) != len(rows):
        raise ValueError(f"{index_path}: stream.word_count does not match source manifest")
    if _metadata_int(
        stream.get("input_data_bits"),
        source=index_path,
        field="stream.input_data_bits",
    ) != input_data_bits:
        raise ValueError(f"{index_path}: stream.input_data_bits does not match source manifest")
    if _metadata_int(
        stream.get("input_data_bytes"),
        source=index_path,
        field="stream.input_data_bytes",
    ) != input_data_bytes:
        raise ValueError(f"{index_path}: stream.input_data_bytes does not match source manifest")
    if "input_keep_mask" in stream:
        expected_keep_mask = input_keep_mask(manifest)
        if _metadata_int(
            stream.get("input_keep_mask"),
            source=index_path,
            field="stream.input_keep_mask",
        ) != expected_keep_mask:
            raise ValueError(f"{index_path}: stream.input_keep_mask does not match source manifest")
    expected_byte_count = len(rows) * input_data_bytes
    if _metadata_int(
        stream.get("byte_count"),
        source=index_path,
        field="stream.byte_count",
    ) != expected_byte_count:
        raise ValueError(f"{index_path}: stream.byte_count does not match source manifest")
    expected_fraction_bits = coefficient_fraction_bits(manifest)
    if "coefficient_fraction_bits" in stream:
        if _metadata_int(
            stream.get("coefficient_fraction_bits"),
            source=index_path,
            field="stream.coefficient_fraction_bits",
        ) != expected_fraction_bits:
            raise ValueError(
                f"{index_path}: stream.coefficient_fraction_bits does not match source manifest"
            )

    expected_write_count = len(register_writes(manifest))
    if _metadata_int(
        index.get("axi_lite_write_count"),
        source=index_path,
        field="axi_lite_write_count",
    ) != expected_write_count:
        raise ValueError(f"{index_path}: axi_lite_write_count does not match source manifest")
    expected_axi_lite = _axi_lite_metadata(expected_write_count, manifest)
    if "axi_lite" in index:
        try:
            _validate_optional_metadata(index["axi_lite"], expected_axi_lite, label="axi_lite", source=index_path)
        except ValueError as exc:
            if "does not match" in str(exc):
                raise ValueError(f"{index_path}: axi_lite metadata does not match RTL control plane") from exc
            raise
    validate_axi_lite_csv(manifest_path, manifest)

    artifacts = index["artifacts"]
    header = _resolve_index_path(index_path, artifacts.get("header"))
    stream_bin = _resolve_index_path(index_path, artifacts.get("stream_bin"))
    last_bin = _resolve_index_path(index_path, artifacts.get("last_bin"))
    if header is None:
        raise ValueError(f"{index_path}: missing header artifact")
    if stream_bin is None:
        raise ValueError(f"{index_path}: missing stream_bin artifact")

    expected_header = header_text(
        manifest,
        symbol_prefix=str(index["symbol_prefix"]),
        include_guard=None,
    )
    actual_header = header.read_text(encoding="utf-8")
    if actual_header != expected_header:
        raise ValueError(f"{header}: header does not match source manifest")

    actual_stream = stream_bin.read_bytes()
    expected_stream = _expected_stream_bytes(rows, input_data_bytes)
    if actual_stream != expected_stream:
        raise ValueError(f"{stream_bin}: stream payload does not match source manifest")

    if last_bin is not None:
        actual_last = last_bin.read_bytes()
        expected_last = bytes(1 if last else 0 for _, last in rows)
        if actual_last != expected_last:
            raise ValueError(f"{last_bin}: TLAST sidecar does not match source manifest")

    validate_checksums(index_path, index, manifest)
    return index


def describe_host_bundle(index_path: Path) -> dict:
    index = validate_host_bundle(index_path)
    manifest_path = _resolve_index_path(index_path, index["source_manifest"])
    if manifest_path is None:
        raise ValueError(f"{index_path}: missing source_manifest")
    with manifest_path.open("r", encoding="utf-8") as handle:
        manifest = json.load(handle)
    artifacts = index["artifacts"]
    writes = [
        {
            "register": register,
            "address": address,
            "data": data,
            "strb": strb,
        }
        for register, address, data, strb in register_writes(manifest)
    ]
    bundle_dir = index_path.parent
    stream_bin = _resolve_index_path(index_path, artifacts.get("stream_bin"))
    last_bin = _resolve_index_path(index_path, artifacts.get("last_bin"))
    header = _resolve_index_path(index_path, artifacts.get("header"))
    stream_csv = _manifest_relative_path(manifest_path, manifest["stream"].get("csv"))
    axi_lite_csv = _manifest_relative_path(manifest_path, manifest["axi_lite"].get("csv"))
    plan = {
        "format": "hjxl.host_replay_plan.v1",
        "bundle_index": str(index_path),
        "bundle_index_resolved": str(index_path.resolve()),
        "bundle_dir": str(bundle_dir.resolve()),
        "name": index["name"],
        "manifest_format": index["manifest_format"],
        "target": target_metadata(manifest),
        "trace_route": index.get("trace_route", trace_route_metadata(manifest)),
        "frame": index.get("frame", _frame_metadata(manifest)),
        "artifacts": {
            "header": artifacts["header"],
            "header_resolved": _resolved_path(header),
            "source_manifest": index["source_manifest"],
            "source_manifest_resolved": _resolved_path(manifest_path),
            "stream_csv": manifest["stream"].get("csv"),
            "stream_csv_resolved": _resolved_path(stream_csv),
            "axi_lite_csv": manifest["axi_lite"].get("csv"),
            "axi_lite_csv_resolved": _resolved_path(axi_lite_csv),
        },
        "stream": {
            "stream_bin": artifacts["stream_bin"],
            "stream_bin_resolved": _resolved_path(stream_bin),
            "last_bin": artifacts.get("last_bin"),
            "last_bin_resolved": _resolved_path(last_bin),
            "word_count": index["stream"]["word_count"],
            "input_data_bits": index["stream"]["input_data_bits"],
            "word_bytes": index["stream"]["input_data_bytes"],
            "input_keep_mask": index["stream"].get("input_keep_mask", input_keep_mask(manifest)),
            "byte_count": index["stream"]["byte_count"],
        },
        "trace": index.get("trace", _trace_metadata()),
        "distance": distance_metadata(),
        "axi_lite": _axi_lite_metadata(len(writes), manifest) | {"writes": writes},
        "status_bits": manifest["axi_lite"]["status_control_bits"],
        "checksums": index["checksums"],
    }
    if "coefficient_fraction_bits" in index["stream"]:
        plan["stream"]["coefficient_fraction_bits"] = index["stream"]["coefficient_fraction_bits"]
    return plan


def _replay_plan_index_path(plan_path: Path, raw_path: str) -> Path:
    path = Path(raw_path)
    if path.is_absolute():
        return path
    plan_relative = plan_path.parent / path
    if plan_relative.exists():
        return plan_relative
    if path.exists():
        return path
    return plan_relative


def _metadata_matches(
    actual: object,
    expected: object,
    *,
    source: Path,
    field: str,
) -> bool:
    if isinstance(expected, dict):
        if not isinstance(actual, dict):
            return False
        if set(actual) != set(expected):
            return False
        return all(
            _metadata_matches(
                actual[key],
                expected_value,
                source=source,
                field=f"{field}.{key}",
            )
            for key, expected_value in expected.items()
        )
    if isinstance(expected, list):
        if not isinstance(actual, list) or len(actual) != len(expected):
            return False
        return all(
            _metadata_matches(
                actual_value,
                expected_value,
                source=source,
                field=f"{field}[{index}]",
            )
            for index, (actual_value, expected_value) in enumerate(zip(actual, expected))
        )
    if isinstance(expected, bool):
        return isinstance(actual, bool) and actual == expected
    if isinstance(expected, int):
        return _metadata_int(actual, source=source, field=field) == expected
    return actual == expected


def validate_replay_plan(plan_path: Path) -> dict:
    with plan_path.open("r", encoding="utf-8") as handle:
        plan = json.load(handle)
    if plan.get("format") != "hjxl.host_replay_plan.v1":
        raise ValueError(f"{plan_path}: unsupported replay-plan format {plan.get('format')!r}")
    bundle_index = plan.get("bundle_index")
    if not isinstance(bundle_index, str):
        raise ValueError(f"{plan_path}: missing bundle_index")
    canonical_bundle_index = plan.get("bundle_index_resolved")
    if isinstance(canonical_bundle_index, str):
        bundle_index_path = Path(canonical_bundle_index)
    else:
        bundle_index_path = _replay_plan_index_path(plan_path, bundle_index)
    expected = describe_host_bundle(bundle_index_path)
    expected["bundle_index"] = bundle_index
    if "bundle_index_resolved" not in plan:
        expected.pop("bundle_index_resolved", None)
    if "trace" not in plan:
        expected.pop("trace", None)
    if "target" not in plan:
        expected.pop("target", None)
    if "distance" not in plan:
        expected.pop("distance", None)
    if "frame" not in plan:
        expected.pop("frame", None)
    else:
        for key in tuple(expected["frame"]):
            if key not in plan["frame"]:
                expected["frame"].pop(key, None)
    if "status_bits" not in plan:
        expected.pop("status_bits", None)
    if "trace_route" not in plan:
        expected.pop("trace_route", None)
    if "trace" in plan:
        for key in tuple(expected["trace"]):
            if key not in plan["trace"]:
                expected["trace"].pop(key, None)
    if "coefficient_fraction_bits" not in plan.get("stream", {}):
        expected["stream"].pop("coefficient_fraction_bits", None)
    if "input_data_bits" not in plan.get("stream", {}):
        expected["stream"].pop("input_data_bits", None)
    if "input_keep_mask" not in plan.get("stream", {}):
        expected["stream"].pop("input_keep_mask", None)
    if "axi_lite" in plan:
        for key in ("addr_bits", "data_bits", "strb_bits", "register_map"):
            if key not in plan["axi_lite"]:
                expected["axi_lite"].pop(key, None)
    if not _metadata_matches(plan, expected, source=plan_path, field="replay plan"):
        raise ValueError(f"{plan_path}: replay plan does not match described bundle")
    return plan


def write_bundle_manifest_copy(
    *,
    manifest_path: Path,
    manifest: dict,
    output_dir: Path,
    safe_name: str,
) -> Path:
    bundle_manifest = copy.deepcopy(manifest)
    manifest_copy = output_dir / f"{safe_name}-manifest.json"

    stream_csv = _manifest_relative_path(manifest_path, manifest["stream"].get("csv"))
    if stream_csv is None:
        raise ValueError(f"{manifest_path}: missing stream CSV path")
    stream_csv_copy = output_dir / f"{safe_name}-stream.csv"
    shutil.copyfile(stream_csv, stream_csv_copy)
    bundle_manifest["stream"]["csv"] = stream_csv_copy.name

    axi_lite_csv = _manifest_relative_path(manifest_path, manifest["axi_lite"].get("csv"))
    if axi_lite_csv is not None:
        axi_lite_csv_copy = output_dir / f"{safe_name}-control.csv"
        shutil.copyfile(axi_lite_csv, axi_lite_csv_copy)
        bundle_manifest["axi_lite"]["csv"] = axi_lite_csv_copy.name

    manifest_copy.write_text(
        json.dumps(bundle_manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return manifest_copy


def write_host_bundle(
    *,
    manifest_path: Path,
    output_dir: Path,
    name: str,
    symbol_prefix: str,
    include_last: bool,
) -> dict:
    with manifest_path.open("r", encoding="utf-8") as handle:
        manifest = json.load(handle)

    safe_name = _safe_name(name)
    output_dir.mkdir(parents=True, exist_ok=True)
    header = output_dir / f"{safe_name}.h"
    stream_bin = output_dir / f"{safe_name}-stream.bin"
    last_bin = output_dir / f"{safe_name}-last.bin" if include_last else None
    index_json = output_dir / f"{safe_name}-bundle.json"

    header.write_text(
        header_text(manifest, symbol_prefix=symbol_prefix, include_guard=None),
        encoding="utf-8",
    )
    word_count, word_bytes = write_stream_buffers(
        manifest_path=manifest_path,
        stream_bin=stream_bin,
        last_bin=last_bin,
    )
    manifest_copy = write_bundle_manifest_copy(
        manifest_path=manifest_path,
        manifest=manifest,
        output_dir=output_dir,
        safe_name=safe_name,
    )

    stream_metadata = _stream_metadata(
        manifest,
        word_count=word_count,
        input_data_bits_value=input_data_bits(manifest),
        input_data_bytes=word_bytes,
    )
    index = {
        "format": "hjxl.host_bundle.v1",
        "source_manifest": manifest_copy.name,
        "original_manifest": str(manifest_path),
        "manifest_format": manifest.get("format"),
        "target": target_metadata(manifest),
        "trace_route": trace_route_metadata(manifest),
        "distance": distance_metadata(),
        "frame": _frame_metadata(manifest),
        "trace": _trace_metadata(),
        "name": safe_name,
        "artifacts": {
            "header": _artifact_path(header, output_dir),
            "stream_bin": _artifact_path(stream_bin, output_dir),
            "last_bin": _artifact_path(last_bin, output_dir) if last_bin is not None else None,
        },
        "stream": stream_metadata,
        "axi_lite": _axi_lite_metadata(len(register_writes(manifest)), manifest),
        "axi_lite_write_count": len(register_writes(manifest)),
        "symbol_prefix": symbol_prefix,
    }
    index["checksums"] = {
        "sha256": _bundle_file_checksums(index_json, index, json.loads(manifest_copy.read_text(encoding="utf-8")))
    }
    index_json.write_text(json.dumps(index, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return index


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest-json", type=Path, help="HJXL stream/control manifest")
    parser.add_argument("--output-dir", type=Path, help="host bundle output directory")
    parser.add_argument("--name", help="bundle filename stem")
    parser.add_argument(
        "--symbol-prefix",
        default="HJXL_BUNDLE",
        help="C symbol prefix for generated header macros and objects",
    )
    parser.add_argument(
        "--no-last-bin",
        action="store_true",
        help="omit the TLAST sidecar from the generated bundle",
    )
    parser.add_argument(
        "--validate-bundle",
        type=Path,
        help="validate an existing hjxl.host_bundle.v1 index and its artifacts",
    )
    parser.add_argument(
        "--describe-bundle",
        type=Path,
        help="validate an existing bundle and print a host replay-plan JSON document",
    )
    parser.add_argument(
        "--validate-replay-plan",
        type=Path,
        help="validate a saved hjxl.host_replay_plan.v1 JSON document against its bundle",
    )
    parser.add_argument(
        "--replay-plan-json",
        type=Path,
        help="optional output path for host replay-plan JSON during generation or --describe-bundle",
    )
    args = parser.parse_args()

    mode_count = sum(
        value is not None
        for value in (args.validate_bundle, args.describe_bundle, args.validate_replay_plan)
    )
    if mode_count > 1:
        raise SystemExit("error: choose only one of --validate-bundle, --describe-bundle, or --validate-replay-plan")

    if args.validate_bundle is not None:
        if args.replay_plan_json is not None:
            raise SystemExit("error: --replay-plan-json cannot be used with --validate-bundle")
        try:
            index = validate_host_bundle(args.validate_bundle)
        except (OSError, KeyError, TypeError, ValueError) as exc:
            raise SystemExit(f"error: {exc}") from exc
        print(
            "validated host bundle "
            f"{index['name']} with {index['stream']['word_count']} stream words"
        )
        return 0

    if args.validate_replay_plan is not None:
        if args.replay_plan_json is not None:
            raise SystemExit("error: --replay-plan-json cannot be used with --validate-replay-plan")
        try:
            plan = validate_replay_plan(args.validate_replay_plan)
        except (OSError, KeyError, TypeError, ValueError) as exc:
            raise SystemExit(f"error: {exc}") from exc
        print(
            "validated host replay plan "
            f"{plan['name']} with {plan['stream']['word_count']} stream words"
        )
        return 0

    if args.describe_bundle is not None:
        try:
            plan = describe_host_bundle(args.describe_bundle)
        except (OSError, KeyError, TypeError, ValueError) as exc:
            raise SystemExit(f"error: {exc}") from exc
        text = json.dumps(plan, indent=2, sort_keys=True) + "\n"
        if args.replay_plan_json is not None:
            args.replay_plan_json.parent.mkdir(parents=True, exist_ok=True)
            args.replay_plan_json.write_text(text, encoding="utf-8")
            print(f"wrote host replay plan to {args.replay_plan_json}")
        else:
            print(text, end="")
        return 0

    missing = [
        flag
        for flag, value in (
            ("--manifest-json", args.manifest_json),
            ("--output-dir", args.output_dir),
            ("--name", args.name),
        )
        if value is None
    ]
    if missing:
        raise SystemExit(f"error: missing required arguments for bundle generation: {', '.join(missing)}")

    try:
        index = write_host_bundle(
            manifest_path=args.manifest_json,
            output_dir=args.output_dir,
            name=args.name,
            symbol_prefix=args.symbol_prefix,
            include_last=not args.no_last_bin,
        )
    except (OSError, KeyError, TypeError, ValueError) as exc:
        raise SystemExit(f"error: {exc}") from exc

    print(
        "wrote host bundle "
        f"{index['name']} with {index['stream']['word_count']} stream words to {args.output_dir}"
    )
    if args.replay_plan_json is not None:
        index_path = args.output_dir / f"{index['name']}-bundle.json"
        try:
            plan = describe_host_bundle(index_path)
            text = json.dumps(plan, indent=2, sort_keys=True) + "\n"
            args.replay_plan_json.parent.mkdir(parents=True, exist_ok=True)
            args.replay_plan_json.write_text(text, encoding="utf-8")
        except (OSError, KeyError, TypeError, ValueError) as exc:
            raise SystemExit(f"error: {exc}") from exc
        print(f"wrote host replay plan to {args.replay_plan_json}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
