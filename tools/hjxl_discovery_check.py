#!/usr/bin/env python3
"""Validate AXI-Lite discovery readback against an HJXL replay plan."""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
import sys

from hjxl_host_bundle import describe_host_bundle, validate_replay_plan


DISCOVERY_REGISTERS = (
    "identity",
    "abi_version",
    "capabilities",
    "max_frame_geometry",
    "active_route",
    "build_id",
)


def _parse_int(value: str | None, *, path: Path, line: int, field: str) -> int:
    if value is None or not value.strip():
        raise ValueError(f"{path}:{line}: {field} is required")
    try:
        parsed = int(value.strip(), 0)
    except ValueError as exc:
        raise ValueError(f"{path}:{line}: {field} must be an integer, got {value!r}") from exc
    if not 0 <= parsed <= 0xFFFFFFFF:
        raise ValueError(f"{path}:{line}: {field} must fit in uint32_t")
    return parsed


def read_discovery_csv(path: Path, register_map: dict[str, object]) -> dict[str, int]:
    address_to_name = {
        int(register_map[name]): name
        for name in DISCOVERY_REGISTERS
        if name in register_map
    }
    if len(address_to_name) != len(DISCOVERY_REGISTERS):
        missing = sorted(set(DISCOVERY_REGISTERS) - set(register_map))
        raise ValueError(f"replay plan register map is missing discovery registers: {missing}")
    observed: dict[str, int] = {}
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames != ["address", "data", "resp"]:
            raise ValueError(f"{path}: expected address,data,resp columns")
        for line, row in enumerate(reader, start=2):
            address = _parse_int(row["address"], path=path, line=line, field="address")
            data = _parse_int(row["data"], path=path, line=line, field="data")
            response = _parse_int(row["resp"], path=path, line=line, field="resp")
            if address not in address_to_name:
                continue
            name = address_to_name[address]
            if name in observed:
                raise ValueError(f"{path}:{line}: duplicate discovery read for {name}")
            if response != 0:
                raise ValueError(f"{path}:{line}: discovery read {name} returned AXI response {response}")
            observed[name] = data
    missing = sorted(set(DISCOVERY_REGISTERS) - set(observed))
    if missing:
        raise ValueError(f"{path}: missing discovery reads: {missing}")
    return observed


def validate_discovery_readback(plan: dict, observed: dict[str, int]) -> dict[str, object]:
    expected = plan.get("discovery")
    if not isinstance(expected, dict):
        raise ValueError("replay plan is missing discovery metadata")
    for name in ("identity", "abi_version", "build_id", "active_route"):
        if observed[name] != int(expected[name]):
            raise ValueError(
                f"discovery {name} expected {int(expected[name]):#010x}, got {observed[name]:#010x}"
            )
    required_capabilities = int(expected["required_capabilities"])
    missing_capabilities = required_capabilities & ~observed["capabilities"]
    if missing_capabilities:
        raise ValueError(f"discovery capabilities are missing required mask {missing_capabilities:#010x}")

    max_width = observed["max_frame_geometry"] & 0xFFFF
    max_height = (observed["max_frame_geometry"] >> 16) & 0xFFFF
    minimum_width = int(expected["minimum_max_frame_width"])
    minimum_height = int(expected["minimum_max_frame_height"])
    if max_width < minimum_width or max_height < minimum_height:
        raise ValueError(
            "discovery maximum frame geometry "
            f"{max_width}x{max_height} is smaller than required {minimum_width}x{minimum_height}"
        )
    return {
        "format": "hjxl.discovery_check.v1",
        "identity": observed["identity"],
        "abi_version": observed["abi_version"],
        "build_id": observed["build_id"],
        "capabilities": observed["capabilities"],
        "required_capabilities": required_capabilities,
        "active_route": observed["active_route"],
        "max_frame_width": max_width,
        "max_frame_height": max_height,
        "minimum_max_frame_width": minimum_width,
        "minimum_max_frame_height": minimum_height,
    }


def _load_plan(*, replay_plan_json: Path | None, bundle_index: Path | None) -> dict:
    if replay_plan_json is not None:
        return validate_replay_plan(replay_plan_json)
    assert bundle_index is not None
    return describe_host_bundle(bundle_index)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--replay-plan-json", type=Path)
    source.add_argument("--bundle-index", type=Path)
    parser.add_argument("--read-csv", type=Path, required=True, help="AXI-Lite address,data,resp readback CSV")
    parser.add_argument("--summary-json", type=Path)
    args = parser.parse_args()
    try:
        plan = _load_plan(replay_plan_json=args.replay_plan_json, bundle_index=args.bundle_index)
        register_map = plan.get("axi_lite", {}).get("register_map")
        if not isinstance(register_map, dict):
            raise ValueError("replay plan AXI-Lite metadata is missing register_map")
        observed = read_discovery_csv(args.read_csv, register_map)
        summary = validate_discovery_readback(plan, observed)
        if args.summary_json is not None:
            args.summary_json.parent.mkdir(parents=True, exist_ok=True)
            args.summary_json.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(
            "validated HJXL discovery "
            f"ABI {summary['abi_version']:#010x}, route {summary['active_route']}, "
            f"capacity {summary['max_frame_width']}x{summary['max_frame_height']}"
        )
        return 0
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(exc, file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
