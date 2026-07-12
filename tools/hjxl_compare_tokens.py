#!/usr/bin/env python3
"""Compare HJXL token-array NumPy artifacts."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys


def _load_numpy():
    import numpy as np  # pylint: disable=import-outside-toplevel

    return np


def load_token_pairs(path: Path, label: str) -> list[tuple[int, int]]:
    np = _load_numpy()
    array = np.asarray(np.load(path))
    if array.ndim != 2 or array.shape[1] != 2:
        raise ValueError(f"{label}: expected shape (n, 2), got {array.shape}")
    if not np.issubdtype(array.dtype, np.integer):
        raise ValueError(f"{label}: expected integer token pairs, got {array.dtype}")
    negative = np.argwhere(array < 0)
    if negative.size != 0:
        row, column = (int(value) for value in negative[0])
        field = "context" if column == 0 else "value"
        raise ValueError(f"{label}: negative token {field} {int(array[row, column])} at row {row}")
    max_uint32 = (1 << 32) - 1
    too_large = np.argwhere(array > max_uint32)
    if too_large.size != 0:
        row, column = (int(value) for value in too_large[0])
        field = "context" if column == 0 else "value"
        raise ValueError(f"{label}: token {field} {int(array[row, column])} outside uint32 at row {row}")
    return [(int(context), int(value)) for context, value in array]


def load_strategy(path: Path, label: str):
    np = _load_numpy()
    array = load_integer_grid(path, label, np.uint8, "AC strategy")
    if array.ndim != 2:
        raise ValueError(f"{label}: expected 2D AC strategy grid, got {array.shape}")
    return array


def load_integer_grid(path: Path, label: str, dtype, description: str):
    np = _load_numpy()
    array = np.asarray(np.load(path))
    if array.ndim != 2:
        raise ValueError(f"{label}: expected 2D {description} grid, got {array.shape}")
    if not np.issubdtype(array.dtype, np.integer):
        raise ValueError(f"{label}: expected integer {description} grid, got {array.dtype}")
    info = np.iinfo(dtype)
    out_of_range = np.argwhere((array < info.min) | (array > info.max))
    if out_of_range.size != 0:
        y, x = (int(value) for value in out_of_range[0])
        raise ValueError(
            f"{label}: {description} value {int(array[y, x])} outside {np.dtype(dtype).name}"
        )
    return array.astype(dtype, copy=False)


def load_metadata_grid(path: Path, label: str, dtype, description: str):
    return load_integer_grid(path, label, dtype, description)


def compare_token_pairs(
    label: str,
    expected: list[tuple[int, int]],
    actual: list[tuple[int, int]],
    max_value_delta: int,
) -> list[str]:
    errors: list[str] = []
    if len(actual) != len(expected):
        errors.append(f"{label}: length mismatch: actual={len(actual)} expected={len(expected)}")
        return errors

    mismatch_count = 0
    first_mismatch = None
    for ordinal, (actual_pair, expected_pair) in enumerate(zip(actual, expected)):
        actual_context, actual_value = actual_pair
        expected_context, expected_value = expected_pair
        if actual_context != expected_context or abs(actual_value - expected_value) > max_value_delta:
            mismatch_count += 1
            if first_mismatch is None:
                first_mismatch = (
                    ordinal,
                    actual_context,
                    actual_value,
                    expected_context,
                    expected_value,
                )

    if first_mismatch is not None:
        ordinal, actual_context, actual_value, expected_context, expected_value = first_mismatch
        errors.append(
            f"{label}: {mismatch_count} mismatches; first at ordinal {ordinal}: "
            f"actual=({actual_context}, {actual_value}) "
            f"expected=({expected_context}, {expected_value})"
        )
    return errors


def compare_strategy(label: str, expected_path: Path, actual_path: Path) -> list[str]:
    np = _load_numpy()
    expected = load_strategy(expected_path, f"expected {label}")
    actual = load_strategy(actual_path, f"actual {label}")
    if actual.shape != expected.shape:
        return [f"{label}: shape mismatch: actual={actual.shape} expected={expected.shape}"]
    mismatches = np.argwhere(actual != expected)
    if mismatches.size == 0:
        return []
    y, x = (int(value) for value in mismatches[0])
    return [
        f"{label}: {len(mismatches)} mismatches; first at ({y}, {x}): "
        f"actual={int(actual[y, x])} expected={int(expected[y, x])}"
    ]


def compare_metadata_grid(
    label: str,
    expected_path: Path,
    actual_path: Path,
    dtype,
    description: str,
) -> list[str]:
    np = _load_numpy()
    expected = load_metadata_grid(expected_path, f"expected {label}", dtype, description)
    actual = load_metadata_grid(actual_path, f"actual {label}", dtype, description)
    if actual.shape != expected.shape:
        return [f"{label}: shape mismatch: actual={actual.shape} expected={expected.shape}"]
    mismatches = np.argwhere(actual != expected)
    if mismatches.size == 0:
        return []
    y, x = (int(value) for value in mismatches[0])
    return [
        f"{label}: {len(mismatches)} mismatches; first at ({y}, {x}): "
        f"actual={int(actual[y, x])} expected={int(expected[y, x])}"
    ]


def compare_metadata_file_pair(
    label: str,
    expected_path: Path | None,
    actual_path: Path | None,
    dtype,
    description: str,
) -> list[str]:
    if expected_path is None and actual_path is None:
        return []
    if expected_path is None or actual_path is None:
        return [f"{label}: both --expected-{label} and --actual-{label} are required"]
    return compare_metadata_grid(label, expected_path, actual_path, dtype, description)


def compare_token_file_pair(
    label: str,
    expected_path: Path | None,
    actual_path: Path | None,
    max_value_delta: int,
) -> list[str]:
    if expected_path is None and actual_path is None:
        return []
    if expected_path is None or actual_path is None:
        return [f"{label}: both --expected-{label} and --actual-{label} are required"]
    expected = load_token_pairs(expected_path, f"expected {label}")
    actual = load_token_pairs(actual_path, f"actual {label}")
    return compare_token_pairs(label, expected, actual, max_value_delta)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected-dc-tokens-npy", type=Path)
    parser.add_argument("--actual-dc-tokens-npy", type=Path)
    parser.add_argument("--expected-ac-metadata-tokens-npy", type=Path)
    parser.add_argument("--actual-ac-metadata-tokens-npy", type=Path)
    parser.add_argument("--expected-ac-tokens-npy", type=Path)
    parser.add_argument("--actual-ac-tokens-npy", type=Path)
    parser.add_argument("--expected-ac-strategy-npy", type=Path)
    parser.add_argument("--actual-ac-strategy-npy", type=Path)
    parser.add_argument("--expected-raw-quant-field-npy", type=Path)
    parser.add_argument("--actual-raw-quant-field-npy", type=Path)
    parser.add_argument("--expected-ytox-map-npy", type=Path)
    parser.add_argument("--actual-ytox-map-npy", type=Path)
    parser.add_argument("--expected-ytob-map-npy", type=Path)
    parser.add_argument("--actual-ytob-map-npy", type=Path)
    parser.add_argument(
        "--max-value-delta",
        type=int,
        default=0,
        help="allowed value delta for token-pair comparisons; contexts and lengths must still match",
    )
    args = parser.parse_args()
    if args.max_value_delta < 0:
        raise SystemExit("--max-value-delta must be non-negative")

    errors: list[str] = []
    errors.extend(
        compare_token_file_pair(
            "dc-tokens-npy",
            args.expected_dc_tokens_npy,
            args.actual_dc_tokens_npy,
            args.max_value_delta,
        )
    )
    errors.extend(
        compare_token_file_pair(
            "ac-metadata-tokens-npy",
            args.expected_ac_metadata_tokens_npy,
            args.actual_ac_metadata_tokens_npy,
            args.max_value_delta,
        )
    )
    errors.extend(
        compare_token_file_pair(
            "ac-tokens-npy",
            args.expected_ac_tokens_npy,
            args.actual_ac_tokens_npy,
            args.max_value_delta,
        )
    )

    if args.expected_ac_strategy_npy is not None or args.actual_ac_strategy_npy is not None:
        if args.expected_ac_strategy_npy is None or args.actual_ac_strategy_npy is None:
            errors.append(
                "ac-strategy-npy: both --expected-ac-strategy-npy and "
                "--actual-ac-strategy-npy are required"
            )
        else:
            errors.extend(
                compare_strategy(
                    "ac-strategy-npy",
                    args.expected_ac_strategy_npy,
                    args.actual_ac_strategy_npy,
                )
            )

    np = _load_numpy()
    errors.extend(
        compare_metadata_file_pair(
            "raw-quant-field-npy",
            args.expected_raw_quant_field_npy,
            args.actual_raw_quant_field_npy,
            np.uint8,
            "raw quant-field",
        )
    )
    errors.extend(
        compare_metadata_file_pair(
            "ytox-map-npy",
            args.expected_ytox_map_npy,
            args.actual_ytox_map_npy,
            np.int8,
            "Y-to-X CFL map",
        )
    )
    errors.extend(
        compare_metadata_file_pair(
            "ytob-map-npy",
            args.expected_ytob_map_npy,
            args.actual_ytob_map_npy,
            np.int8,
            "Y-to-B CFL map",
        )
    )

    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("artifacts match")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
