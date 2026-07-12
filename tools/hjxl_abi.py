#!/usr/bin/env python3
"""Shared helpers for schema-generated HJXL control-plane discovery metadata."""

from __future__ import annotations

from pathlib import Path

from hjxl_abi_generated import (
    CAPABILITY_PROFILES,
    DISCOVERY_ABI_VERSION,
    DISCOVERY_BUILD_ID,
    DISCOVERY_IDENTITY,
    TOKEN_SELECT,
    TRACE_STAGES,
)


DISCOVERY_KEYS = (
    "identity",
    "abi_version",
    "build_id",
    "required_capabilities",
    "active_route",
    "minimum_max_frame_width",
    "minimum_max_frame_height",
)


def rgb_active_route(*, flags: int, focused_route: int | None) -> int:
    if focused_route is not None:
        return focused_route
    enable_xyb = bool(flags & 1)
    enable_dct = bool(flags & (1 << 1))
    enable_quant = bool(flags & (1 << 2))
    enable_tokenize = bool(flags & (1 << 3))
    token_select = (flags >> 8) & 0x3
    use_dc = enable_dct and enable_quant and enable_tokenize and token_select == TOKEN_SELECT["dc"]
    use_metadata = enable_quant and enable_tokenize and token_select == TOKEN_SELECT["ac-metadata"]
    use_quant = enable_dct and enable_quant and not use_dc and not use_metadata
    use_dct = enable_dct and not use_quant and not use_dc and not use_metadata
    use_strategy = enable_quant and not enable_tokenize and not enable_dct
    use_xyb = enable_xyb and not any((use_dct, use_quant, use_dc, use_metadata, use_strategy))
    if use_dc:
        return TRACE_STAGES["dc-tokens"]
    if use_metadata:
        return TRACE_STAGES["ac-metadata-tokens"]
    if use_quant:
        return TRACE_STAGES["quantized-ac"]
    if use_dct:
        return TRACE_STAGES["raw-dct8x8"]
    if use_strategy:
        return TRACE_STAGES["ac-strategy"]
    if use_xyb:
        return TRACE_STAGES["xyb"]
    return TRACE_STAGES["input-padded"]


def discovery_metadata(*, profile: str, active_route: int, width: int, height: int) -> dict[str, int]:
    if profile not in CAPABILITY_PROFILES:
        raise ValueError(f"unknown discovery capability profile {profile!r}")
    if not 0 <= active_route < 1 << 8:
        raise ValueError(f"discovery active route must fit in 8 bits, got {active_route}")
    if not 0 < width <= 0xFFFF or not 0 < height <= 0xFFFF:
        raise ValueError("discovery minimum frame geometry must fit in positive 16-bit dimensions")
    return {
        "identity": DISCOVERY_IDENTITY,
        "abi_version": DISCOVERY_ABI_VERSION,
        "build_id": DISCOVERY_BUILD_ID,
        "required_capabilities": CAPABILITY_PROFILES[profile],
        "active_route": active_route,
        "minimum_max_frame_width": width,
        "minimum_max_frame_height": height,
    }


def _metadata_int(value: object, *, source: str, field: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{source}: discovery.{field} must be an integer")
    if not 0 <= value <= 0xFFFFFFFF:
        raise ValueError(f"{source}: discovery.{field} must fit in uint32_t")
    return value


def normalize_discovery_metadata(value: object, *, source: str | Path) -> dict[str, int]:
    source_text = str(source)
    if not isinstance(value, dict):
        raise ValueError(f"{source_text}: discovery must be an object")
    unknown = set(value) - set(DISCOVERY_KEYS)
    missing = set(DISCOVERY_KEYS) - set(value)
    if unknown:
        raise ValueError(f"{source_text}: discovery contains unknown fields: {sorted(unknown)}")
    if missing:
        raise ValueError(f"{source_text}: discovery is missing fields: {sorted(missing)}")
    normalized = {
        key: _metadata_int(value[key], source=source_text, field=key)
        for key in DISCOVERY_KEYS
    }
    if normalized["identity"] != DISCOVERY_IDENTITY:
        raise ValueError(f"{source_text}: discovery.identity does not match the HJXL identity")
    if normalized["abi_version"] != DISCOVERY_ABI_VERSION:
        raise ValueError(f"{source_text}: discovery.abi_version does not match the generated ABI")
    if normalized["build_id"] != DISCOVERY_BUILD_ID:
        raise ValueError(f"{source_text}: discovery.build_id does not match the generated contract build")
    if normalized["active_route"] >= 1 << 8:
        raise ValueError(f"{source_text}: discovery.active_route must fit in 8 bits")
    for key in ("minimum_max_frame_width", "minimum_max_frame_height"):
        if not 0 < normalized[key] <= 0xFFFF:
            raise ValueError(f"{source_text}: discovery.{key} must fit in a positive 16-bit dimension")
    return normalized


def validate_discovery_metadata(
    value: object,
    *,
    profile: str,
    active_route: int,
    width: int,
    height: int,
    source: str | Path,
) -> dict[str, int]:
    normalized = normalize_discovery_metadata(value, source=source)
    expected = discovery_metadata(profile=profile, active_route=active_route, width=width, height=height)
    if normalized != expected:
        mismatches = [key for key in DISCOVERY_KEYS if normalized[key] != expected[key]]
        raise ValueError(f"{source}: discovery metadata does not match target/frame fields: {mismatches}")
    return normalized
