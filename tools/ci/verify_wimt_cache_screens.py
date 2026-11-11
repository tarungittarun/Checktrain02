#!/usr/bin/env python3
"""Verify in-test UI screenshots for the installed WIMT web-cache device check.

This only inspects the PNG bytes and UiAutomator hierarchy saved *inside* the running APK.
It never substitutes a launcher/host screenshot or creates a screenshot from text.
"""
from pathlib import Path
import argparse
import json
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zlib


APP_PACKAGE = "in.opentrack.india"
REQUIRED = {
    "before": "wimt-cache-before-selected-source",
    "after": "wimt-cache-after-selected-source",
    "details": "wimt-cache-source-details",
}
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
MAX_PNG_BYTES = 32 * 1024 * 1024
MAX_DECODED_BYTES = 128 * 1024 * 1024


def validate_png(raw: bytes, name: str) -> tuple[int, int]:
    """Check PNG chunks/CRCs and fully decode bounded scanlines; this is not OCR."""
    if len(raw) < 45 or raw[:8] != PNG_MAGIC:
        raise ValueError(f"not a PNG capture: {name}")
    if len(raw) > MAX_PNG_BYTES:
        raise ValueError(f"PNG capture exceeds {MAX_PNG_BYTES // (1024 * 1024)} MiB: {name}")
    offset = 8
    chunk_index = 0
    width = height = bit_depth = color_type = interlace = None
    palette_entries = None
    idat = bytearray()
    saw_idat = False
    idat_closed = False
    saw_iend = False
    while offset < len(raw):
        if len(raw) - offset < 12:
            raise ValueError(f"truncated PNG chunk: {name}")
        size = struct.unpack(">I", raw[offset:offset + 4])[0]
        end = offset + 12 + size
        if end > len(raw):
            raise ValueError(f"PNG chunk exceeds file boundary: {name}")
        kind = raw[offset + 4:offset + 8]
        data = raw[offset + 8:offset + 8 + size]
        expected_crc = struct.unpack(">I", raw[offset + 8 + size:end])[0]
        actual_crc = zlib.crc32(data, zlib.crc32(kind)) & 0xFFFFFFFF
        if expected_crc != actual_crc:
            raise ValueError(f"PNG chunk CRC mismatch ({kind.decode('ascii', 'replace')}): {name}")
        if chunk_index == 0 and kind != b"IHDR":
            raise ValueError(f"PNG does not start with IHDR: {name}")
        if kind == b"IHDR":
            if chunk_index != 0 or size != 13:
                raise ValueError(f"invalid or duplicate PNG IHDR: {name}")
            width, height, bit_depth, color_type, compression, filtering, interlace = \
                struct.unpack(">IIBBBBB", data)
            if width < 240 or height < 320 or width > 12_000 or height > 12_000 or \
                    width * height > 50_000_000:
                raise ValueError(f"implausible screenshot dimensions: {width}x{height}")
            legal_depths = {0: {1, 2, 4, 8, 16}, 2: {8, 16},
                            3: {1, 2, 4, 8}, 4: {8, 16}, 6: {8, 16}}
            if color_type not in legal_depths or bit_depth not in legal_depths[color_type] or \
                    compression != 0 or filtering != 0 or interlace not in (0, 1):
                raise ValueError(f"unsupported or invalid PNG IHDR: {name}")
        elif width is None:
            raise ValueError(f"PNG chunk precedes IHDR: {name}")
        elif kind == b"PLTE":
            if saw_idat or palette_entries is not None or size == 0 or size % 3 or size > 768:
                raise ValueError(f"invalid PNG palette: {name}")
            palette_entries = size // 3
            if color_type in (0, 4) or (color_type == 3 and palette_entries > (1 << bit_depth)):
                raise ValueError(f"PNG palette is incompatible with its color type: {name}")
        elif kind == b"IDAT":
            if idat_closed:
                raise ValueError(f"non-consecutive PNG IDAT chunks: {name}")
            saw_idat = True
            if len(idat) + size > MAX_PNG_BYTES:
                raise ValueError(f"PNG image data exceeds the bounded size: {name}")
            idat.extend(data)
        elif kind == b"IEND":
            if size != 0 or not saw_idat or end != len(raw):
                raise ValueError(f"invalid or non-final PNG IEND: {name}")
            saw_iend = True
        else:
            if saw_idat:
                idat_closed = True
            # Unknown critical chunks (uppercase first byte) cannot be safely decoded.
            if kind[:1].isupper():
                raise ValueError(f"unsupported critical PNG chunk {kind!r}: {name}")
        if kind not in (b"IDAT", b"IEND") and saw_idat:
            idat_closed = True
        offset = end
        chunk_index += 1
        if saw_iend:
            break
    if not saw_iend or width is None or not idat:
        raise ValueError(f"incomplete PNG capture: {name}")
    if color_type == 3 and palette_entries is None:
        raise ValueError(f"indexed PNG is missing its palette: {name}")

    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color_type]
    passes = [(0, 0, 1, 1)] if interlace == 0 else [
        (0, 0, 8, 8), (4, 0, 8, 8), (0, 4, 4, 8),
        (2, 0, 4, 4), (0, 2, 2, 4), (1, 0, 2, 2), (0, 1, 1, 2),
    ]
    expected_bytes = 0
    pass_rows: list[tuple[int, int]] = []
    for start_x, start_y, step_x, step_y in passes:
        pass_width = max(0, (width - start_x + step_x - 1) // step_x)
        pass_height = max(0, (height - start_y + step_y - 1) // step_y)
        if pass_width and pass_height:
            row_bytes = (pass_width * channels * bit_depth + 7) // 8
            expected_bytes += pass_height * (row_bytes + 1)
            pass_rows.append((pass_height, row_bytes + 1))
    if expected_bytes > MAX_DECODED_BYTES:
        raise ValueError(f"decoded PNG would exceed the bounded size: {name}")
    try:
        decoder = zlib.decompressobj()
        pending = bytes(idat)
        decoded = bytearray()
        while pending:
            remaining = expected_bytes + 1 - len(decoded)
            if remaining <= 0:
                raise ValueError(f"PNG scanlines exceed their declared dimensions: {name}")
            piece = decoder.decompress(pending, remaining)
            decoded.extend(piece)
            if len(decoded) > expected_bytes:
                raise ValueError(f"PNG scanlines exceed their declared dimensions: {name}")
            pending = decoder.unconsumed_tail
            if pending and not piece:
                raise ValueError(f"PNG decompression did not make progress: {name}")
        if not decoder.eof or decoder.unused_data:
            raise ValueError(f"truncated or trailing PNG compressed stream: {name}")
        decoded.extend(decoder.flush())
    except zlib.error as exc:
        raise ValueError(f"invalid PNG compressed image data: {name}") from exc
    if len(decoded) != expected_bytes:
        raise ValueError(f"PNG decoded size does not match its dimensions: {name}")
    cursor = 0
    for row_count, stride in pass_rows:
        for _ in range(row_count):
            if decoded[cursor] > 4:
                raise ValueError(f"invalid PNG scanline filter: {name}")
            cursor += stride
    return width, height


def read_capture(folder: Path, name: str) -> tuple[Path, str, tuple[int, int]]:
    image = folder / "screens" / f"{name}.png"
    hierarchy = folder / "screens" / f"{name}.xml"
    if not image.is_file() or image.stat().st_size < 1_000:
        raise ValueError(f"missing or too-small in-app screenshot: {image.name}")
    if image.stat().st_size > MAX_PNG_BYTES:
        raise ValueError(f"PNG capture exceeds {MAX_PNG_BYTES // (1024 * 1024)} MiB: {image.name}")
    raw = image.read_bytes()
    width, height = validate_png(raw, image.name)
    if not hierarchy.is_file() or hierarchy.stat().st_size < 100:
        raise ValueError(f"missing in-test window hierarchy: {hierarchy.name}")
    try:
        root = ET.parse(hierarchy).getroot()
    except ET.ParseError as exc:
        raise ValueError(f"invalid UiAutomator hierarchy {hierarchy.name}: {exc}") from exc
    nodes = list(root.iter())
    if not any(n.attrib.get("package") == APP_PACKAGE for n in nodes):
        raise ValueError(f"hierarchy is not from the installed app: {hierarchy.name}")
    text = "\n".join(" ".join(n.attrib.get(key, "") for key in
                                ("text", "content-desc", "resource-id")) for n in nodes)
    return image, text, (width, height)


def verify(folder: Path, accepted: bool = False) -> dict:
    captures = {label: read_capture(folder, name) for label, name in REQUIRED.items()}
    before_text = captures["before"][1]
    after_text = captures["after"][1]
    details_text = captures["details"][1]
    if "sourceSpinner" not in before_text and "sourceSpinner" not in after_text:
        raise ValueError("in-app track screen/source picker is absent from both UI hierarchies")
    if "Selected WIMT web cache" not in after_text:
        raise ValueError("after-request screenshot hierarchy does not identify the selected WIMT web cache")
    if "WIMT web cache:" not in details_text or "Selected WIMT web-cache rows" not in details_text:
        raise ValueError("source-details screenshot hierarchy does not show the cache response section")
    row_pattern = r"WIMT web cache: (\d+) source-reported web-cache time row\(s\)"
    row_match = re.search(row_pattern, details_text)
    summary_match = re.search(row_pattern, after_text)
    rows = int(row_match.group(1)) if row_match else 0
    cache_line = next((line for line in details_text.splitlines()
                       if "WIMT web cache:" in line), "")
    http_status = re.search(r"HTTP (200|403|429|[1-5][0-9]{2})", cache_line)
    if accepted:
        summary_rows = int(summary_match.group(1)) if summary_match else 0
        if rows < 1 or summary_rows != rows or not http_status or \
                http_status.group(1) != "200" or \
                "unverified, not railway ACTUAL" not in after_text or \
                "run date " not in details_text or \
                "WIMT web note (unverified, not Act)" not in details_text or \
                "unverified, not railway ACTUAL" not in details_text:
            raise ValueError("accepted test lacks matching visible-summary/details rows, HTTP 200, or provenance in the in-app hierarchy")
    return {
        "verified": True,
        "appPackage": APP_PACKAGE,
        "captureSource": "in-test UiAutomator screenshot plus matching app-window hierarchy",
        # The text assertion comes from the installed app's accessibility hierarchy;
        # PNG pixels are validated as an image, but this verifier performs no OCR.
        "acceptedDataVerifiedInAppHierarchy": accepted,
        "candidateRows": rows,
        "httpStatusVisibleInDetails": "HTTP " + http_status.group(1) if http_status else None,
        "screenshots": [
            {"name": REQUIRED[key] + ".png", "bytes": captures[key][0].stat().st_size,
             "dimensions": list(captures[key][2])}
            for key in REQUIRED
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--accepted", action="store_true",
                        help="require strict live-response evidence as well as in-app captures")
    args = parser.parse_args()
    try:
        verdict = verify(args.evidence, args.accepted)
    except (OSError, ValueError) as exc:
        print(f"WIMT_CACHE_SCREEN_NOT_VERIFIED: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(verdict, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
