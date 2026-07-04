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
    KV260_TRACE_CAPTURE_WORD_BYTES,
    TRACE_GROUP_BITS,
    TRACE_INDEX_BITS,
    TRACE_PACKED_BITS,
    TRACE_PACKED_BYTES,
    TRACE_STAGE_BITS,
    TRACE_VALUE_BITS,
    distance_metadata,
    header_text,
    input_data_bits,
    register_writes,
    target_metadata,
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
    if path.is_absolute() or path.exists():
        return path
    return index_path.parent / path


def _manifest_relative_path(manifest_path: Path, raw_path: str | None) -> Path | None:
    if raw_path is None:
        return None
    path = Path(raw_path)
    if path.is_absolute() or path.exists():
        return path
    return manifest_path.parent / path


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
            try:
                rows.append(
                    (
                        int(row[address_column], 0),
                        int(row[data_column], 0),
                        int(row[strb_column], 0),
                    )
                )
            except Exception as exc:  # pylint: disable=broad-exception-caught
                raise ValueError(f"{path}:{line}: invalid AXI-Lite row: {row}") from exc
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
    expected_target = target_metadata(manifest.get("format"))
    if "target" in index and index["target"] != expected_target:
        raise ValueError(f"{index_path}: target metadata does not match source manifest")
    expected_distance = distance_metadata()
    if "distance" in index and index["distance"] != expected_distance:
        raise ValueError(f"{index_path}: distance metadata does not match RTL lookup")

    rows, input_data_bits, input_data_bytes = stream_rows_from_manifest(manifest_path)
    stream = index["stream"]
    if int(stream["word_count"]) != len(rows):
        raise ValueError(f"{index_path}: stream.word_count does not match source manifest")
    if int(stream["input_data_bits"]) != input_data_bits:
        raise ValueError(f"{index_path}: stream.input_data_bits does not match source manifest")
    if int(stream["input_data_bytes"]) != input_data_bytes:
        raise ValueError(f"{index_path}: stream.input_data_bytes does not match source manifest")
    expected_byte_count = len(rows) * input_data_bytes
    if int(stream["byte_count"]) != expected_byte_count:
        raise ValueError(f"{index_path}: stream.byte_count does not match source manifest")

    expected_write_count = len(register_writes(manifest))
    if int(index["axi_lite_write_count"]) != expected_write_count:
        raise ValueError(f"{index_path}: axi_lite_write_count does not match source manifest")
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
    return {
        "format": "hjxl.host_replay_plan.v1",
        "bundle_index": str(index_path),
        "bundle_index_resolved": str(index_path.resolve()),
        "bundle_dir": str(bundle_dir.resolve()),
        "name": index["name"],
        "manifest_format": index["manifest_format"],
        "target": target_metadata(index["manifest_format"]),
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
            "word_bytes": index["stream"]["input_data_bytes"],
            "byte_count": index["stream"]["byte_count"],
        },
        "trace": {
            "stage_bits": TRACE_STAGE_BITS,
            "group_bits": TRACE_GROUP_BITS,
            "index_bits": TRACE_INDEX_BITS,
            "trace_value_bits": TRACE_VALUE_BITS,
            "packed_bits": TRACE_PACKED_BITS,
            "packed_bytes": TRACE_PACKED_BYTES,
            "default_capture_word_bytes": KV260_TRACE_CAPTURE_WORD_BYTES,
        },
        "distance": distance_metadata(),
        "axi_lite": {
            "write_count": len(writes),
            "writes": writes,
        },
        "status_bits": manifest["axi_lite"]["status_control_bits"],
        "checksums": index["checksums"],
    }


def _replay_plan_index_path(plan_path: Path, raw_path: str) -> Path:
    path = Path(raw_path)
    if path.is_absolute() or path.exists():
        return path
    return plan_path.parent / path


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
    if plan != expected:
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

    index = {
        "format": "hjxl.host_bundle.v1",
        "source_manifest": manifest_copy.name,
        "original_manifest": str(manifest_path),
        "manifest_format": manifest.get("format"),
        "target": target_metadata(manifest.get("format")),
        "distance": distance_metadata(),
        "name": safe_name,
        "artifacts": {
            "header": _artifact_path(header, output_dir),
            "stream_bin": _artifact_path(stream_bin, output_dir),
            "last_bin": _artifact_path(last_bin, output_dir) if last_bin is not None else None,
        },
        "stream": {
            "word_count": word_count,
            "input_data_bits": input_data_bits(manifest),
            "input_data_bytes": word_bytes,
            "byte_count": word_count * word_bytes,
        },
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
