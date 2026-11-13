#!/usr/bin/env python3
"""Temporarily transport real Alerts emulator captures through GitHub's API.

Actions artifacts remain the durable source of evidence. This one-run transport is a
fallback for review environments that cannot reach GitHub's artifact blob host: it posts
a bounded base64 ZIP in commit-comment chunks so the original captures can be inspected.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from io import BytesIO
from zipfile import ZIP_DEFLATED, ZipFile

TEST_SOURCE = Path("app/src/androidTest/java/in/opentrack/india/e2e/TravellerAcceptanceTest.kt")
# openAlertChoices() takes a literal screenshot tag and calls shotRequired() internally;
# include those helper-driven traversals as well as the direct mandatory captures.
TAG_RE = re.compile(
    r'(?:E2E\.shotRequired|openAlertChoices)\("(alert-[A-Za-z0-9._-]+)"\)'
)
CHUNK_CHARS = 60_000  # GitHub's comment limit is 65,536 bytes, including the marker line.


def split_payload(payload: str, limit: int = CHUNK_CHARS) -> list[str]:
    if limit <= 0 or limit % 4:
        raise ValueError("base64 chunk size must be a positive multiple of four")
    return [payload[i:i + limit] for i in range(0, len(payload), limit)] or [""]


def required_tags() -> list[str]:
    tags = TAG_RE.findall(TEST_SOURCE.read_text(encoding="utf-8"))
    if not tags or len(tags) != len(set(tags)):
        raise RuntimeError("Alerts test must define unique mandatory screenshot tags")
    return tags


def build_bundle(evidence_dir: Path, profile: str, run_id: str, sha: str,
                 allow_partial: bool, only_tags: list[str] | None = None
                 ) -> tuple[bytes, dict[str, object]]:
    all_tags = required_tags()
    tags = all_tags if only_tags is None else list(dict.fromkeys(only_tags))
    if not tags or any(tag not in all_tags for tag in tags):
        raise RuntimeError("evidence scope contains no required tag or an unknown tag")

    present: list[str] = []
    missing: list[str] = []
    for tag in tags:
        for ext in ("png", "xml"):
            name = f"{tag}.{ext}"
            path = evidence_dir / name
            if path.is_file() and path.stat().st_size > (1000 if ext == "png" else 100):
                present.append(name)
            else:
                missing.append(name)
    if missing and not allow_partial:
        raise RuntimeError("missing required emulator evidence: " + ", ".join(missing))
    if not present:
        raise RuntimeError("no Alerts screenshot or hierarchy files were captured")

    manifest: dict[str, object] = {
        "format": "tracktrain-alert-evidence-v1",
        "profile": profile,
        "runId": run_id,
        "sourceSha": sha,
        "complete": not missing,
        "requiredTags": all_tags,
        "scopeTags": tags,
        "includedFiles": present,
        "missingFiles": missing,
    }
    buffer = BytesIO()
    with ZipFile(buffer, "w", compression=ZIP_DEFLATED, compresslevel=9) as archive:
        archive.writestr("manifest.json", json.dumps(manifest, indent=2, sort_keys=True))
        for name in present:
            archive.write(evidence_dir / name, f"screens/{name}")
    return buffer.getvalue(), manifest


def post_chunks(payload: bytes, profile: str, run_id: str, sha: str) -> str:
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    token = os.environ.get("GH_TOKEN", "") or os.environ.get("GITHUB_TOKEN", "")
    if not repo or not sha or not token:
        raise RuntimeError("GITHUB_REPOSITORY, source SHA and GH_TOKEN are required")
    digest = hashlib.sha256(payload).hexdigest()
    encoded = base64.b64encode(payload).decode("ascii")
    chunks = split_payload(encoded)
    endpoint = f"repos/{repo}/commits/{sha}/comments"
    with tempfile.TemporaryDirectory(prefix="alert-evidence-comments-") as temp:
        path = Path(temp) / "comment.md"
        for index, chunk in enumerate(chunks, start=1):
            header = (f"<!-- E2E_ALERT_EVIDENCE_V1 run={run_id} profile={profile} "
                      f"sha256={digest} chunk={index}/{len(chunks)} -->\n")
            path.write_text(header + chunk, encoding="ascii")
            result = subprocess.run(
                ["gh", "api", "-X", "POST", endpoint, "-F", f"body=@{path}"],
                text=True, capture_output=True, check=False,
            )
            if result.returncode:
                raise RuntimeError(
                    f"could not post evidence transport chunk {index}/{len(chunks)}: "
                    f"{result.stderr.strip()[:300]}"
                )
    return digest


def self_test() -> None:
    payload = "QUJD" * 31_337
    chunks = split_payload(payload, 400)
    assert all(len(chunk) <= 400 for chunk in chunks)
    assert all(len(chunk) % 4 == 0 for chunk in chunks[:-1])
    assert "".join(chunks) == payload
    assert split_payload("", 400) == [""]

    tags = required_tags()
    helper_tags = re.findall(
        r'openAlertChoices\("(alert-[A-Za-z0-9._-]+)"\)',
        TEST_SOURCE.read_text(encoding="utf-8"),
    )
    assert len(tags) == 28 and len(helper_tags) == 4, (len(tags), helper_tags)
    with tempfile.TemporaryDirectory(prefix="alert-evidence-self-test-") as temp:
        evidence = Path(temp)
        first = tags[0]
        (evidence / f"{first}.png").write_bytes(b"\x89PNG\r\n" + b"x" * 2000)
        (evidence / f"{first}.xml").write_text("<hierarchy/>" + " " * 101, encoding="utf-8")
        bundle, manifest = build_bundle(evidence, "online", "self-test", "deadbeef", True,
                                        only_tags=[first])
        assert manifest["complete"] is True
        assert manifest["requiredTags"] == tags
        assert manifest["scopeTags"] == [first]
        assert manifest["includedFiles"] == [f"{first}.png", f"{first}.xml"]
        with ZipFile(BytesIO(bundle)) as archive:
            assert set(archive.namelist()) == {
                "manifest.json", f"screens/{first}.png", f"screens/{first}.xml"
            }
            stored_manifest = json.loads(archive.read("manifest.json"))
            assert stored_manifest["runId"] == "self-test"
            assert stored_manifest["sourceSha"] == "deadbeef"
        try:
            build_bundle(evidence, "online", "self-test", "deadbeef", False)
        except RuntimeError as error:
            assert "missing required emulator evidence" in str(error)
        else:
            raise AssertionError("strict export accepted incomplete screenshot evidence")
    print(f"Alerts screenshot transport self-tests passed ({len(tags)} required paths)")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence_dir", nargs="?", type=Path)
    parser.add_argument("profile", nargs="?", choices=("online", "offline"))
    parser.add_argument("--allow-partial", action="store_true",
                        help="export captures from a failed traversal, with missing tags recorded")
    parser.add_argument("--only-tag", action="append", dest="only_tags",
                        help="limit the transport to a subset of required tags (repeatable)")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.evidence_dir is None or args.profile is None:
        parser.error("evidence_dir and profile are required unless --self-test is used")
    run_id = os.environ.get("GITHUB_RUN_ID", "unknown")
    sha = os.environ.get("GITHUB_SHA", "")
    payload, manifest = build_bundle(args.evidence_dir, args.profile, run_id, sha,
                                     args.allow_partial, args.only_tags)
    digest = post_chunks(payload, args.profile, run_id, sha)
    print("E2E_ALERT_EVIDENCE_TRANSPORT " + json.dumps({
        "profile": args.profile,
        "runId": run_id,
        "sourceSha": sha,
        "complete": manifest["complete"],
        "scopeTags": len(manifest["scopeTags"]),
        "files": len(manifest["includedFiles"]),
        "missing": manifest["missingFiles"],
        "bundleBytes": len(payload),
        "sha256": digest,
    }, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
