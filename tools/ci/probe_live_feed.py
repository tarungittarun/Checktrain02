#!/usr/bin/env python3
"""Ask the live-feed endpoints what they actually answer, and report it on the commit.

Why this runs in CI and not in a development sandbox: the endpoint is a POST to
`enquiry.indianrail.gov.in`, and a sandbox may not be allowed to reach that host at all — while a
GitHub-hosted runner always can. So the runner is the probe, and the answer is a commit comment rather
than something only one machine can see.

The app's own request shape is defined by `NtesWire.kt`; this script mirrors it with the same static key,
IV and signature secret, because that is the only way to find out whether the Kotlin implementation would
have worked before a user does. It reports structure, not contents: which keys exist, which of the
stop-level fields carry real times, how many rows have an actual arrival. A train that is 5-10 minutes
late is included on purpose — a feed that only answers "On Time" is exactly the failure the user called
out.

Stdlib + `pycryptodome` (installed by the job; if it is absent the script says so and exits 0, because a
probe that cannot run must not be able to fail the build it is helping).
"""
from __future__ import annotations

import datetime
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

BASE = "https://enquiry.indianrail.gov.in/crisns/AppServAnd"
KEY = b"8EA4DB2CC1EB3DC5"
IV = b"7DC5EB3BB4DB6EA8"
SCKEY = "645fbc1e56e23365f2f3c204ae0899f6"
UA = "Dalvik/2.1.0 (Linux; Android 11)"


def crypto_available() -> bool:
    try:
        import Crypto.Cipher.AES  # noqa: F401
        return True
    except Exception:
        return False


def wrap(plain: str) -> str:
    import base64
    import binascii
    import hashlib
    from Crypto.Cipher import AES
    from Crypto.Util.Padding import pad

    ct = AES.new(KEY, AES.MODE_CBC, IV).encrypt(pad(plain.encode(), 16))
    enc = binascii.hexlify(base64.b64encode(ct)).decode().upper()
    return hashlib.md5((plain + SCKEY).encode()).hexdigest().upper() + "#" + enc


def unwrap(wire: str) -> str:
    import base64
    import binascii
    from Crypto.Cipher import AES
    from Crypto.Util.Padding import unpad

    if "#" in wire:
        wire = wire.split("#", 1)[1]
    ct = base64.b64decode(binascii.unhexlify(wire.strip()))
    return unpad(AES.new(KEY, AES.MODE_CBC, IV).decrypt(ct), 16).decode("utf-8", "replace")


def call(plain: str, timeout: int = 25):
    body = json.dumps({"jsonIn": wrap(plain)}).encode()
    req = urllib.request.Request(
        BASE, data=body,
        headers={"Content-Type": "application/json", "charset": "utf-8", "User-Agent": UA},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8", "replace")
            status = r.status
    except urllib.error.HTTPError as e:
        return f"HTTPError {e.code}", None
    except Exception as e:  # noqa: BLE001 - a probe reports failures, it does not raise them
        return f"{type(e).__name__}: {e}"[:160], None
    try:
        doc = json.loads(raw)
    except Exception:
        return f"not JSON ({len(raw)} bytes, starts {raw[:60]!r})", None
    if isinstance(doc, dict) and "jsonIn" in doc:
        try:
            doc = json.loads(unwrap(doc["jsonIn"]))
        except Exception as e:  # noqa: BLE001
            return f"encrypted envelope, could not decrypt: {e}"[:160], None
    return "ok", doc


def shape(v, depth=0):
    """Key names and array lengths only — the point is the schema, and this text goes on a commit."""
    if depth > 2:
        return "…"
    if isinstance(v, dict):
        return {k: shape(v[k], depth + 1) for k in sorted(v)[:24]}
    if isinstance(v, list):
        return [f"list[{len(v)}]", shape(v[0], depth + 1) if v else None]
    if isinstance(v, str):
        return v[:34]
    return v


def first_array(doc):
    """The stop list, wherever it is: NTES names it differently across sub-services."""
    if isinstance(doc, dict):
        for k in ("S", "stops", "route", "schedule", "DATA", "data", "Result"):
            v = doc.get(k)
            if isinstance(v, list) and v:
                return k, v
        for v in doc.values():
            got = first_array(v)
            if got:
                return got
    return None, None


def main() -> int:
    out: list[str] = []
    if not crypto_available():
        print("pycryptodome is absent; the probe cannot run here and is not failing the build")
        return 0
    today = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=5, minutes=30)))
    date = today.strftime("%d-%b-%Y")
    out.append(f"probed {BASE} at {today.isoformat(timespec='seconds')} (IST)")
    # A mix on purpose: a busy late-running express, a superfast, and a passenger train.
    for train in (os.environ.get("PROBE_TRAIN", "12627"), "12301", "56213"):
        for label, plain in (
            ("live", f"service=TrainRunningMob&subService=ShowFullRunJson&trainNo={train}&startDate={date}"),
            ("schedule", f"service=TrainRunningMob&subService=GetTrainSchedule&trainNo={train}&startDate={date}"),
        ):
            verdict, doc = call(plain)
            out.append(f"\n### train {train} · {label} · {verdict}")
            if doc is None:
                continue
            out.append("top-level: " + json.dumps(shape(doc), separators=(",", ":"))[:900])
            key, rows = first_array(doc)
            if rows:
                filled = [k for k in shape(rows[0]) if any(str(r.get(k, "")).strip() for r in rows if isinstance(r, dict))]
                out.append(f"stop array '{key}' has {len(rows)} rows; non-empty in at least one row: {filled}")
                out.append("first 2 rows: " + json.dumps(rows[:2], separators=(",", ":"))[:700])
            if isinstance(doc, dict):
                for hk in ("MSG", "CTT", "NEXT", "PREV", "TMNAME", "SDATE", "EDATA", "lastUpdate", "status"):
                    if hk in doc:
                        out.append(f"  {hk} = {str(doc[hk])[:90]}")
    body = "\n".join(out)
    print(body[:6000])
    tok = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    repo = os.environ.get("GITHUB_REPOSITORY")
    sha = os.environ.get("GITHUB_SHA")
    if tok and repo and sha and os.environ.get("PROBE_POST", "1") == "1":
        subprocess.run(
            ["gh", "api", "-X", "POST", f"repos/{repo}/commits/{sha}/comments",
             "-f", f"body=### live-feed probe\n\n```\n{body[:5000]}\n```"],
            check=False, timeout=60,
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
