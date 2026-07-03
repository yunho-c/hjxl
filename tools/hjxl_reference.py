#!/usr/bin/env python3
"""Small libjxl-tiny oracle helper for HJXL development.

This script keeps reference exploration out of the RTL code. It can generate a
deterministic linear-RGB fixture and optionally encode it with libjxl-tiny's
educational Python port.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import sys


DEFAULT_LIBJXL_TINY = Path("/Users/yunhocho/GitHub/libjxl-tiny")


def _load_numpy():
    import numpy as np  # pylint: disable=import-outside-toplevel

    return np


def _libjxl_tiny_root() -> Path:
    return Path(os.environ.get("LIBJXL_TINY", DEFAULT_LIBJXL_TINY)).expanduser()


def _add_libjxl_tiny(root: Path) -> None:
    python_dir = root / "python"
    if not python_dir.is_dir():
        raise SystemExit(f"missing libjxl-tiny Python dir: {python_dir}")
    sys.path.insert(0, str(python_dir))


def generate_fixture(width: int, height: int, pattern: str):
    np = _load_numpy()
    y, x = np.mgrid[0:height, 0:width]
    if pattern == "constant":
        image = np.zeros((3, height, width), dtype=np.float32)
        image[0, :, :] = 0.25
        image[1, :, :] = 0.5
        image[2, :, :] = 0.75
        return image
    if pattern == "gradient":
        denom_x = max(width - 1, 1)
        denom_y = max(height - 1, 1)
        image = np.empty((3, height, width), dtype=np.float32)
        image[0] = x.astype(np.float32) / np.float32(denom_x)
        image[1] = y.astype(np.float32) / np.float32(denom_y)
        image[2] = (x + y).astype(np.float32) / np.float32(denom_x + denom_y)
        return image
    if pattern == "checkerboard":
        board = ((x // 4 + y // 4) & 1).astype(np.float32)
        return np.stack((board, 1.0 - board, board * 0.5), axis=0).astype(np.float32)
    if pattern == "impulse":
        image = np.zeros((3, height, width), dtype=np.float32)
        image[:, height // 2, width // 2] = np.asarray((1.0, 0.5, 0.25), dtype=np.float32)
        return image
    if pattern == "random":
        rng = np.random.default_rng(0)
        return rng.random((3, height, width), dtype=np.float32)
    raise ValueError(f"unknown pattern: {pattern}")


def write_pfm(path: Path, image) -> None:
    np = _load_numpy()
    if image.shape[0] != 3:
        raise ValueError("expected channel-first RGB image")
    rgb = np.moveaxis(image, 0, -1).astype("<f4")
    with path.open("wb") as handle:
        handle.write(f"PF\n{image.shape[2]} {image.shape[1]}\n-1.0\n".encode("ascii"))
        rgb[::-1].tofile(handle)


def encode_with_python_port(image, distance: float) -> bytes:
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.encoder import encode_from_image  # pylint: disable=import-outside-toplevel

    return encode_from_image(image, distance)


def padded_input_from_python_port(image):
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.image import copy_and_pad_image  # pylint: disable=import-outside-toplevel

    return copy_and_pad_image(image)


def xyb_from_python_port(image):
    root = _libjxl_tiny_root()
    _add_libjxl_tiny(root)
    from jxl_tiny.image import copy_and_pad_image  # pylint: disable=import-outside-toplevel
    from jxl_tiny.xyb import to_xyb  # pylint: disable=import-outside-toplevel

    return to_xyb(copy_and_pad_image(image))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--width", type=int, default=17)
    parser.add_argument("--height", type=int, default=9)
    parser.add_argument(
        "--pattern",
        choices=("constant", "gradient", "checkerboard", "impulse", "random"),
        default="gradient",
    )
    parser.add_argument("--distance", type=float, default=1.0)
    parser.add_argument("--pfm", type=Path, help="optional PFM output path")
    parser.add_argument("--jxl", type=Path, help="optional Python-port JXL output path")
    parser.add_argument(
        "--input-padded-npy",
        type=Path,
        help="optional libjxl-tiny input_padded NumPy output path",
    )
    parser.add_argument("--xyb-npy", type=Path, help="optional libjxl-tiny XYB NumPy output path")
    args = parser.parse_args()

    image = generate_fixture(args.width, args.height, args.pattern)
    if args.pfm is not None:
        args.pfm.parent.mkdir(parents=True, exist_ok=True)
        write_pfm(args.pfm, image)
    if args.jxl is not None:
        args.jxl.parent.mkdir(parents=True, exist_ok=True)
        args.jxl.write_bytes(encode_with_python_port(image, args.distance))
    if args.input_padded_npy is not None:
        np = _load_numpy()
        args.input_padded_npy.parent.mkdir(parents=True, exist_ok=True)
        np.save(args.input_padded_npy, padded_input_from_python_port(image))
    if args.xyb_npy is not None:
        np = _load_numpy()
        args.xyb_npy.parent.mkdir(parents=True, exist_ok=True)
        np.save(args.xyb_npy, xyb_from_python_port(image))
    if (
        args.pfm is None
        and args.jxl is None
        and args.input_padded_npy is None
        and args.xyb_npy is None
    ):
        print(f"generated {args.pattern} fixture: shape={image.shape}, dtype={image.dtype}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
