#!/usr/bin/env python3
"""Report whether fresh installed-app captures were recovered from app-private storage.

This is diagnostics only: successful file recovery does not qualify a WIMT comparison or satisfy
its three-comparison/screenshot acceptance gates.
"""
from pathlib import Path
import argparse
import json
import re
import sys
import tempfile

CAPTURE_NAME = re.compile(
    r"^wimt-compare-[0-9]{4,5}-(before|app-live|app-details|web-cache|web-details|final)\.(png|xml)$"
)
CAPTURE_TAG = re.compile(
    r"^wimt-compare-[0-9]{4,5}-(before|app-live|app-details|web-cache|web-details|final)$"
)


def summarize(folder: Path, listing_succeeded: bool) -> dict:
    expected = set()
    attempts_path = folder / "attempts.jsonl"
    if attempts_path.is_file():
        for line in attempts_path.read_text(encoding="utf-8", errors="replace").splitlines():
            try:
                attempt = json.loads(line)
            except json.JSONDecodeError:
                continue
            names = attempt.get("screenshots", [])
            if not isinstance(names, list):
                continue
            for tag in names:
                if isinstance(tag, str) and CAPTURE_TAG.fullmatch(tag):
                    expected.update((tag + ".png", tag + ".xml"))

    listing_path = folder / "capture-listing.txt"
    listed = {
        line.strip() for line in listing_path.read_text(
            encoding="utf-8", errors="replace").splitlines()
        if CAPTURE_NAME.fullmatch(line.strip())
    } if listing_path.is_file() else set()

    transfers = {}
    transfer_path = folder / "capture-transfers.tsv"
    if transfer_path.is_file():
        for line in transfer_path.read_text(encoding="utf-8", errors="replace").splitlines():
            parts = line.split("\t")
            if len(parts) != 3:
                continue
            try:
                size = int(parts[2])
            except ValueError:
                size = 0
            transfers[parts[0]] = (parts[1], size)
    recovered = {
        name for name, (status, size) in transfers.items()
        if status == "copied" and size > 0
    }
    error_path = folder / "capture-access-error.txt"
    error = error_path.read_text(encoding="utf-8", errors="replace").strip() \
        if error_path.is_file() else ""
    return {
        "appPrivateEvidenceListingSucceeded": listing_succeeded,
        "expectedScreenshotFileCount": len(expected),
        "listedScreenshotFileCount": len(listed),
        "expectedFilesFoundInAppDirectory": len(expected & listed),
        "recoveredScreenshotFileCount": len(recovered),
        "expectedFilesRecovered": len(expected & recovered),
        "missingExpectedScreenshotFileCount": len(expected - recovered),
        "extraListedScreenshotFileCount": len(listed - expected),
        "failedScreenshotCopyCount": sum(
            status != "copied" or size <= 0 for status, size in transfers.values()
        ),
        "captureAccessError": error[:320] or None,
    }


def self_test() -> None:
    with tempfile.TemporaryDirectory(prefix="wimt-capture-recovery-") as temporary:
        folder = Path(temporary)
        (folder / "attempts.jsonl").write_text(json.dumps({
            "trainNo": "12301",
            "screenshots": ["wimt-compare-12301-before", "wimt-compare-12301-app-details"],
        }) + "\n", encoding="utf-8")
        (folder / "capture-listing.txt").write_text(
            "wimt-compare-12301-before.png\n"
            "wimt-compare-12301-before.xml\n"
            "wimt-compare-12301-app-details.png\n"
            "wimt-compare-12301-app-details.xml\n"
            "unrelated.png\n", encoding="utf-8")
        (folder / "capture-transfers.tsv").write_text(
            "wimt-compare-12301-before.png\tcopied\t2048\n"
            "wimt-compare-12301-before.xml\tcopied\t900\n"
            "wimt-compare-12301-app-details.png\tcopied\t4096\n"
            "wimt-compare-12301-app-details.xml\tfailed\t0\n", encoding="utf-8")
        summary = summarize(folder, listing_succeeded=True)
        assert summary["expectedScreenshotFileCount"] == 4
        assert summary["listedScreenshotFileCount"] == 4
        assert summary["expectedFilesFoundInAppDirectory"] == 4
        assert summary["recoveredScreenshotFileCount"] == 3
        assert summary["missingExpectedScreenshotFileCount"] == 1
        assert summary["failedScreenshotCopyCount"] == 1
        assert summary["extraListedScreenshotFileCount"] == 0
        failed_listing = summarize(folder, listing_succeeded=False)
        assert failed_listing["appPrivateEvidenceListingSucceeded"] is False
        assert failed_listing["expectedFilesRecovered"] == 3
    print("WIMT installed-app capture recovery self-test passed; recovery never counts as acceptance")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence", type=Path)
    parser.add_argument("--listing-succeeded", choices=("true", "false"))
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.evidence is None or args.listing_succeeded is None:
        parser.error("--evidence and --listing-succeeded are required")
    print(json.dumps(summarize(args.evidence, args.listing_succeeded == "true"),
                     sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
