#!/usr/bin/env python3
"""Build a WiX-safe BMP ICO (16/32/48 only) from Kmate.png."""

from __future__ import annotations

import struct
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src/main/resources/icons/Kmate.png"
OUTS = [
    ROOT / "src/main/jpackage/Kmate.ico",
    ROOT / "src/main/resources/icons/Kmate.ico",
]
SIZES = (16, 32, 48)


def dib(img: Image.Image) -> bytes:
    w, h = img.size
    rgba = img.convert("RGBA")
    pixels = list(rgba.getdata())
    xor = bytearray()
    for y in range(h - 1, -1, -1):
        for x in range(w):
            r, g, b, a = pixels[y * w + x]
            xor += struct.pack("BBBB", b, g, r, a)
    row_bytes = ((w + 31) // 32) * 4
    mask = bytearray()
    for y in range(h - 1, -1, -1):
        bits = 0
        count = 0
        row = bytearray()
        for x in range(w):
            bits <<= 1
            if pixels[y * w + x][3] < 128:
                bits |= 1
            count += 1
            if count == 8:
                row.append(bits)
                bits = 0
                count = 0
        if count:
            bits <<= 8 - count
            row.append(bits)
        row.extend(b"\x00" * (row_bytes - len(row)))
        mask += row
    header = struct.pack(
        "<IiiHHIIiiII",
        40,
        w,
        h * 2,
        1,
        32,
        0,
        len(xor),
        0,
        0,
        0,
        0,
    )
    return header + xor + mask


def write_ico(path: Path, images: list[bytes], sizes: tuple[int, ...]) -> None:
    entries = bytearray()
    payload = bytearray()
    offset = 6 + 16 * len(images)
    for size, blob in zip(sizes, images):
        entries += struct.pack(
            "<BBBBHHII",
            size,
            size,
            0,
            0,
            1,
            32,
            len(blob),
            offset,
        )
        payload += blob
        offset += len(blob)
    path.write_bytes(struct.pack("<HHH", 0, 1, len(images)) + entries + payload)


def main() -> int:
    src = Image.open(SRC)
    images = [dib(src.resize((s, s), Image.Resampling.LANCZOS)) for s in SIZES]
    for out in OUTS:
        write_ico(out, images, SIZES)
        print(f"wrote {out} ({out.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
