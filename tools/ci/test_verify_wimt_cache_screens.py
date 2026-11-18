#!/usr/bin/env python3
"""Adversarial offline tests for in-app capture validation; no OCR or live device."""
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import zlib


ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("verify_wimt_cache_screens",
                                               ROOT / "verify_wimt_cache_screens.py")
verify_module = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(verify_module)


def chunk(name: bytes, data: bytes) -> bytes:
    return (struct.pack(">I", len(data)) + name + data +
            struct.pack(">I", zlib.crc32(data, zlib.crc32(name)) & 0xFFFFFFFF))


def make_png(width: int = 320, height: int = 320, *, bad_filter: bool = False,
             wrong_rows: bool = False, indexed: bool = False,
             split_idat: bool = False) -> bytes:
    color = 3 if indexed else 2
    depth = 8
    channels = 1 if indexed else 3
    row_bytes = width * channels
    rows = []
    for y in range(height - (1 if wrong_rows else 0)):
        filter_byte = 5 if bad_filter and y == 0 else 0
        pixels = bytes(((x * 19 + y * 7) & 0xFF) for x in range(row_bytes))
        rows.append(bytes((filter_byte,)) + pixels)
    compressed = zlib.compress(b"".join(rows), level=6)
    header = struct.pack(">IIBBBBB", width, height, depth, color, 0, 0, 0)
    pieces = [verify_module.PNG_MAGIC, chunk(b"IHDR", header)]
    if split_idat:
        middle = len(compressed) // 2
        pieces += [chunk(b"IDAT", compressed[:middle]), chunk(b"tEXt", b"note\x00gap"),
                   chunk(b"IDAT", compressed[middle:])]
    else:
        pieces.append(chunk(b"IDAT", compressed))
    pieces.append(chunk(b"IEND", b""))
    return b"".join(pieces)


class VerifyPngTest(unittest.TestCase):
    def test_valid_png_is_fully_decoded_with_dimensions(self):
        self.assertEqual((320, 320), verify_module.validate_png(make_png(), "valid.png"))

    def test_bad_crc_and_truncated_chunk_fail_closed(self):
        valid = make_png()
        corrupt = bytearray(valid)
        corrupt[-1] ^= 0x01
        with self.assertRaisesRegex(ValueError, "CRC"):
            verify_module.validate_png(bytes(corrupt), "bad-crc.png")
        with self.assertRaisesRegex(ValueError, "truncated|boundary|incomplete"):
            verify_module.validate_png(valid[:-8], "truncated.png")

    def test_wrong_scanline_size_bad_filter_and_missing_palette_fail(self):
        with self.assertRaisesRegex(ValueError, "decoded size"):
            verify_module.validate_png(make_png(wrong_rows=True), "short-scanlines.png")
        with self.assertRaisesRegex(ValueError, "filter"):
            verify_module.validate_png(make_png(bad_filter=True), "bad-filter.png")
        with self.assertRaisesRegex(ValueError, "palette"):
            verify_module.validate_png(make_png(indexed=True), "missing-palette.png")

    def test_nonconsecutive_idat_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "non-consecutive"):
            verify_module.validate_png(make_png(split_idat=True), "split-idat.png")

    def test_acceptance_checks_app_hierarchy_not_pixel_ocr(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "screens").mkdir()
            hierarchy = {
                "before": 'resource-id="sourceSpinner"',
                "after": 'resource-id="sourceSummary" '
                         'content-desc="Selected WIMT web cache · experimental" '
                         'text="WIMT web cache: 2 source-reported web-cache time row(s), '
                         'unverified, not railway ACTUAL. Open Source details."',
                "details": 'text="WIMT web cache: 2 source-reported web-cache time row(s); HTTP 200; '
                           'run date 12 Oct 2026; cache-reported route TLGP→MYS matched requested route; '
                           'arrival 20:55 [field actual_arrival_tm]; cache source tag live; '
                           'row update 12 Oct 2026 20:56:00 IST; unverified, not railway ACTUAL; '
                           'WIMT web note (unverified, not Act); '
                           'Selected WIMT web-cache rows"',
            }
            names = {
                "before": "wimt-cache-before-selected-source",
                "after": "wimt-cache-after-selected-source",
                "details": "wimt-cache-source-details",
            }
            image = make_png()
            # Ensure the in-app evidence size gate is exercised without changing its pixels.
            text_chunk = chunk(b"tEXt", b"capture\x00" + b"x" * 1100)
            image = image[:-len(chunk(b"IEND", b""))] + text_chunk + chunk(b"IEND", b"")
            for label, stem in names.items():
                (root / "screens" / f"{stem}.png").write_bytes(image)
                (root / "screens" / f"{stem}.xml").write_text(
                    '<hierarchy><node package="in.opentrack.india" class="android.widget.TextView" '
                    'bounds="[0,0][320,480]" ' + hierarchy[label] +
                    "/></hierarchy>", encoding="utf-8")
            result = verify_module.verify(root, accepted=True)
            self.assertTrue(result["acceptedDataVerifiedInAppHierarchy"])
            self.assertFalse("pixelTextVerified" in result)
            self.assertEqual(2, result["candidateRows"])

            bad_details = root / "screens" / f"{names['details']}.xml"
            bad_details.write_text('<hierarchy><node package="in.opentrack.india" '
                                   'class="android.widget.TextView" bounds="[0,0][320,480]" '
                                   'text="WIMT web cache: 0 source-reported web-cache time row(s); '
                                   'HTTP 403; Selected WIMT web-cache rows"/></hierarchy>', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "accepted test lacks matching visible-summary/details rows"):
                verify_module.verify(root, accepted=True)


if __name__ == "__main__":
    unittest.main()
