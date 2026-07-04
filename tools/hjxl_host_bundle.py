#!/usr/bin/env python3
"""Generate host replay artifacts from an HJXL stream/control manifest."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from hjxl_manifest_header import header_text
from hjxl_stream_buffer import write_stream_buffers


def _safe_name(value: str) -> str:
    cleaned = "".join(ch if ch.isalnum() or ch in "-_" else "-" for ch in value).strip("-_")
    if not cleaned:
        raise ValueError("bundle name must contain at least one letter or digit")
    return cleaned


def _input_data_bits(manifest: dict) -> int:
    stream = manifest["stream"]
    if "pixel_bits" in stream:
        return int(stream["pixel_bits"]) * 3
    return 32


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

    index = {
        "format": "hjxl.host_bundle.v1",
        "source_manifest": str(manifest_path),
        "manifest_format": manifest.get("format"),
        "name": safe_name,
        "artifacts": {
            "header": str(header),
            "stream_bin": str(stream_bin),
            "last_bin": str(last_bin) if last_bin is not None else None,
        },
        "stream": {
            "word_count": word_count,
            "input_data_bits": _input_data_bits(manifest),
            "input_data_bytes": word_bytes,
        },
        "axi_lite_write_count": 7,
        "symbol_prefix": symbol_prefix,
    }
    index_json.write_text(json.dumps(index, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return index


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest-json", type=Path, required=True, help="HJXL stream/control manifest")
    parser.add_argument("--output-dir", type=Path, required=True, help="host bundle output directory")
    parser.add_argument("--name", required=True, help="bundle filename stem")
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
    args = parser.parse_args()

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
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
