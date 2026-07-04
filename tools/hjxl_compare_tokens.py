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
    return [(int(context), int(value)) for context, value in array]


def load_strategy(path: Path, label: str):
    np = _load_numpy()
    array = np.asarray(np.load(path), dtype=np.uint8)
    if array.ndim != 2:
        raise ValueError(f"{label}: expected 2D AC strategy grid, got {array.shape}")
    return array


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

    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("token artifacts match")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
