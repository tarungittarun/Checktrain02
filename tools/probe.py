#!/usr/bin/env python3
"""Source probe ladder for OpenTrack India v2 (M0 / S0).

One command, per-source verdicts, no parsing of live payloads until a capture
exists (brief 7.4). Stdlib only; timeout + ONE retry per host (brief 10.8: a
single timeout is not a verdict; retry once or conclude nothing).

Verdict vocabulary (brief S0 exit criterion):
  usable | usable-but-labelled | blocked-by-network | blocked-by-shape

Verdicts are per LOCATION (sandbox | ci | phone): the same source can be
blocked-by-network here and usable on the owner's phone (brief 4, S1 measured).

Artifacts live in tools/captures/ which is git-ignored (brief 9.8); only the
committed report docs/spike-verdicts.md goes into git.

Exit code: 0 if the tool itself ran (network failures are DATA, not errors).
"""

import json
import os
import socket
import ssl
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
CAPTURE_DIR = os.path.join(HERE, "captures")

# host, tcp port, path to GET for a HEAD-like liveness check (kept harmless:
# small, public, no POST). NTES is POST-only: TCP+TLS is the whole probe here;
# anything deeper needs the envelope + a captured payload (brief 7.4).
SOURCES = [
    {
        "id": "S1-NTES",
        "host": "enquiry.indianrail.gov.in",
        "port": 443,
        "path": "/crisns/AppServAnd",
        "note": "POST-only endpoint; probe measures reachability, not shape",
    },
    {
        "id": "S2-erail",
        "host": "erail.in",
        "port": 443,
        "path": "/rail/getTrains.aspx",
        "note": "GET returns payload with embedded feed stamp",
    },
]


def probe_once(host: str, port: int, path: str, timeout: float):
    """One attempt. Returns dict with stage reached and error class."""
    t0 = time.monotonic()
    result = {"host": host, "stage": "dns", "ok": False, "error": None, "ms": 0}
    try:
        infos = socket.getaddrinfo(host, port, proto=socket.IPPROTO_TCP)
        result["stage"] = "tcp"
        family, _, _, _, sockaddr = infos[0]
        with socket.socket(family, socket.SOCK_STREAM) as sock:
            sock.settimeout(timeout)
            sock.connect(sockaddr)
            result["stage"] = "tls"
            ctx = ssl.create_default_context()
            with ctx.wrap_socket(sock, server_hostname=host) as tls:
                tls_version = tls.version()
                result["stage"] = "http"
                req = urllib.request.Request(
                    f"https://{host}{path}",
                    headers={"User-Agent": "OpenTrackIndia-probe/0.1 (research; 1 request)"},
                )
                with urllib.request.urlopen(req, timeout=timeout) as resp:
                    body = resp.read(4096)  # signature only, never values logged
                    result.update(
                        ok=True,
                        stage="http",
                        status=resp.status,
                        body_bytes=len(body),
                        tls=tls_version,
                    )
                    return result, body
        result["tls"] = tls_version  # unreachable, keeps flow obvious
    except socket.gaierror as e:
        result["error"] = f"DNS: {e}"
    except ssl.SSLError as e:
        result["error"] = f"TLS: {e}"
    except socket.timeout as e:
        result["error"] = f"TIMEOUT at {result['stage']}: {e}"
    except (ConnectionError, OSError, urllib.error.URLError) as e:
        result["error"] = f"{type(e).__name__} at {result['stage']}: {e}"
    result["ms"] = int((time.monotonic() - t0) * 1000)
    return result, None


def probe_with_retry(host, port, path, timeout=8.0):
    """Probe once; on TIMEOUT only, retry exactly once (brief 10.8)."""
    first, body = probe_once(host, port, path, timeout)
    if first["ok"] or not (first["error"] or "").startswith("TIMEOUT"):
        return [first], body
    second, body2 = probe_once(host, port, path, timeout)
    return [first, second], (body if body is not None else body2)


def verdict(attempts):
    last = attempts[-1]
    if last["ok"]:
        # Reachable + answered: shape verdict comes later against a capture.
        return "usable (shape unverified - no parser without a capture, brief 7.4)"
    err = last["error"] or ""
    if err.startswith("TIMEOUT"):
        return "blocked-by-network (timeout x%d)" % len(attempts)
    if err.startswith("TLS") or err.startswith("DNS"):
        return "blocked-by-network (%s)" % err.split(":")[0].lower()
    if err.startswith("HTTP"):
        return "blocked-by-shape (%s)" % err
    return "blocked-by-network (%s)" % err


def main():
    where = sys.argv[1] if len(sys.argv) > 1 else "sandbox"
    os.makedirs(CAPTURE_DIR, exist_ok=True)
    report = {
        "tool": "tools/probe.py",
        "ran_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "location": where,
        "sources": [],
    }
    for src in SOURCES:
        attempts, body = probe_with_retry(src["host"], src["port"], src["path"])
        v = verdict(attempts)
        entry = {
            "id": src["id"],
            "host": src["host"],
            "verdict": v,
            "attempts": attempts,
        }
        report["sources"].append(entry)
        print("%-10s %-30s %s" % (src["id"], src["host"], v))
        if body:
            # Commit NOTHING verbatim from live sources at probe stage: store
            # only a structural signature (brief 3.5 + 7.4).
            sig = {
                "bytes": len(body),
                "prefix_kind": "text" if body[:64].isprintable() else "binary",
                "sample_delims": {d: body[:2048].count(d.encode()) for d in ("~", "|", ",", "\t")},
            }
            entry["capture_signature"] = sig
    out = os.path.join(CAPTURE_DIR, f"probe-{where}-{time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())}.json")
    with open(out, "w") as f:
        json.dump(report, f, indent=1, default=str)
    print("artifact: %s" % out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
