#!/usr/bin/env python3
"""Ask the live-feed endpoints what they actually answer, and report it on the commit.

Why this runs in CI: it is a network question, and the answer differs per egress path. Measured, not
assumed: this sandbox gets `TLS/SSL connection has been closed` from `enquiry.indianrail.gov.in`, and so did
GitHub-hosted runners (the probe comments posted on da0633c and b41c38c) — which is why the script's real
output is a *reachability verdict*, and why the device stays the only witness for what the feed actually
sends. Runners do vary by region and by egress pool, so asking costs seconds and an answer is worth having
in writing; when every path is blocked, that is a finding, and the app must then not promise live data.

The second half of that sentence is why `erail.in` is probed here too: it is a plain GET, keyless, and its
own header carries the feed stamp, so if any CI path can reach anything, it is probably that one.

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


def flat_table(doc):
    """Parallel-array responses, which is how several NTES sub-services actually answer.

    Some services return `{"TRAINNO":[..], "STN":[..], "ATD":[..]}` - one array per column - instead of an
    array of row objects. The Kotlin parser has to serve both, and it can only be written that way if we can
    *see* the difference from here, so this reports it rather than assuming the row-object style.
    """
    if not isinstance(doc, dict):
        return None
    lists = {k: v for k, v in doc.items() if isinstance(v, list)}
    if not lists:
        return None
    lens = {k: len(v) for k, v in lists.items()}
    n = max(lens.values()) if lens else 0
    if n == 0:
        return {"lengths": lens, "rows": []}
    if len(set(lens.values())) > 1:
        # Ragged columns: still informative (it means the feed pads or truncates), and transposing would lie.
        return {"lengths": lens, "rows": []}
    cols = [k for k in doc if isinstance(doc[k], list)]
    rows = [{c: doc[c][i] for c in cols} for i in range(n)]
    return {"lengths": lens, "rows": rows}


def numbers_from(doc):
    """Every train number a reply mentions, whatever key it hid it under.

    Deliberately a regex over the whole payload: the point of this probe is to find out what the feed says,
    not to confirm what our parser expects to find.
    """
    return sorted(set(__import__("re").findall(r"\b(\d{5})\b", json.dumps(doc, separators=(",", ":")))))


def identity_from(doc):
    """name / origin / terminal from a FindTrainJson-style reply, key-name agnostic."""
    blob = doc if isinstance(doc, dict) else {}
    # unwrap one level of list/array-of-one, which is how these flat services often wrap the record
    for k, v in list(blob.items()):
        if isinstance(v, list) and len(v) == 1 and isinstance(v[0], dict):
            blob = v[0]
            break
    def pick(*cands):
        for key, val in blob.items():
            ku = key.upper()
            if any(c in ku for c in cands) and isinstance(val, (str, int)):
                s = str(val).strip()
                if s:
                    return s
        return ""
    return {"name": pick("NAME"), "from": pick("SRC", "ORIGIN", "FROM"), "to": pick("DST", "TERM")}



def erail_pair(src: str, dst: str, date_1: str) -> tuple[str, str]:
    """One GET to erail.in for a station pair, returning (verdict, body). No key, no account, our no proxy.

    The response is `~`-separated text (not JSON, not HTML tables), and its header embeds the feed stamp, so
    a successful call tells us two things at once: which trains the railway lists for that pair *now*, and how
    current that list is. That is the pair-search the app needs, and the offline dump's replacement.
    """
    url = ("https://erail.in/getTrains.aspx?Station_From=" + src + "&Station_To=" + dst +
           "&Date_1=" + date_1 + "&Time_1=23:30&Trains=BOTH&Sort_Type=PVT&Date_Format=DD-MMM-YYYY")
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            body = r.read(400_000).decode("utf-8", "replace")
            return f"HTTP {getattr(r, 'status', 200)} {len(body)}B", body
    except urllib.error.HTTPError as e:
        return f"HTTP {e.code}", ""
    except Exception as e:
        return f"{type(e).__name__}: {str(e)[:90]}", ""


def probe_pair(src_code: str, dst_code: str, must: list[str], date_dalvik: str,
               today: datetime.datetime, capture: str | None = None,
               fetch=call, alt=erail_pair) -> tuple[list[str], dict | None]:
    """One station pair, asked of both keyless sources, with the alternate tried no matter what.

    The `fetch=call, alt=erail_pair` injection exists for one reason: a test must be able to say "the primary
    timed out" and check that the alternate still got asked. The first version of this function put the erail
    call below the primary's `if doc is None: continue`, so the alternate only ran when the primary worked -
    a fallback gated on the thing it is a fallback for, which is the same defect the app has been fighting,
    caught here by its own probe output (an `### pair ... erail.in` line that never appeared).
    """
    out: list[str] = []
    live_entry: dict | None = None
    plain = (f"service=TrainBtwStnMob&subService=TrainBtwStnJson&stnFrom={src_code}"
             f"&stnTo={dst_code}&trainType=All")
    verdict, doc = fetch(plain)
    out.append(f"\n### pair {src_code}->{dst_code} · live list · {verdict}")
    found: list[str] = []
    if doc is None:
        out.append(f"  primary gave nothing usable, so this pair's truth rests on the alternate alone")
    else:
        if capture:
            with open(os.path.join(capture, f"pair-{src_code}-{dst_code}.json"), "w", encoding="utf-8") as fh:
                json.dump(doc, fh)
        out.append("top-level: " + json.dumps(shape(doc), separators=(",", ":"))[:900])
        found = numbers_from(doc)
        out.append(f"train numbers mentioned: {len(found)} -> {', '.join(found[:40])}")
        live_entry = {"from": src_code, "to": dst_code, "must_include": found}
        for n in must:
            out.append(f"  our fixture claims {n} on this pair: "
                       + ("CONFIRMED by the live feed" if n in found else "NOT in what the feed returned"))

    # Second witness, unconditional. Two keyless answers that agree are evidence; one is a hypothesis.
    e_verdict, e_body = alt(src_code, dst_code, today.strftime("%d-%b-%Y"))
    out.append(f"### pair {src_code}->{dst_code} · erail.in (alternate) · {e_verdict}")
    e_nums: list[str] = []
    if e_body:
        import re as _re
        stamp = _re.search(r"(\d{4}-\d{2}-\d{2}-\d{1,2}-\d{1,2}-\d{1,2})", e_body)
        e_nums = sorted(set(_re.findall(r"\b(\d{5})\b", e_body[:200000])))
        out.append(f"feed stamp in payload: {stamp.group(1) if stamp else 'not found'}; "
                   f"train numbers mentioned: {len(e_nums)}")
        out.append("  " + ", ".join(e_nums[:40]))
        for n in must:
            if n not in e_nums:
                out.append(f"  erail does not list {n} either - the fixture's claim is the odd one out")
    if e_nums:
        if live_entry is None:
            live_entry = {"from": src_code, "to": dst_code, "must_include": sorted(e_nums)}
        else:
            live_entry["must_include"] = sorted(set(live_entry["must_include"]) | set(e_nums))
    return out, live_entry



def main() -> int:
    out: list[str] = []
    if not crypto_available():
        print("pycryptodome is absent; the probe cannot run here and is not failing the build")
        return 0
    today = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=5, minutes=30)))
    date = today.strftime("%d-%b-%Y")
    capture = os.environ.get("PROBE_CAPTURE")
    if capture:
        os.makedirs(capture, exist_ok=True)

    # The probe plan comes from fixtures/known_truths.json, so the facts the app is gated on are the same
    # facts we ask the railway about. When the two disagree the report says which one moved; that is the
    # whole method - it is what a passenger could not get the app to admit for three rounds.
    truths = {}
    try:
        tp = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "fixtures", "known_truths.json")
        truths = json.load(open(tp, encoding="utf-8"))
    except Exception as e:  # a missing fixture must not blind the probe
        out.append(f"(no known-truths fixture to probe against: {e})")
    trains = [t.get("train_no") for t in truths.get("trains", []) if t.get("train_no")]
    trains = (trains or []) + [os.environ.get("PROBE_TRAIN", "12627")]
    pairs = [(p.get("from"), p.get("to"), p.get("must_include", [])) for p in truths.get("pair_lists", [])]

    out.append(f"probed {BASE} at {today.isoformat(timespec='seconds')} (IST)")
    out.append(f"plan: {len(set(trains))} train identities + {len(pairs)} station pair(s) from "
               f"fixtures/known_truths.json (verified {truths.get('verifiedOn', '?')})")

    live = {"verifiedOn": today.strftime("%Y-%m-%d"), "verifiedAgainst": "CI probe " + BASE,
            "trains": [], "pair_lists": []}

    for train in dict.fromkeys(trains):
        for label, plain in (
            ("identity", f"service=TrainBtwStnMob&subService=FindTrainJson&trainNo={train}"),
            ("live", f"service=TrainRunningMob&subService=ShowFullRunJson&trainNo={train}&startDate={date}"),
            ("schedule", f"service=TrainRunningMob&subService=GetTrainSchedule&trainNo={train}&startDate={date}"),
        ):
            verdict, doc = call(plain)
            out.append(f"\n### train {train} · {label} · {verdict}")
            if doc is None:
                continue
            if capture:
                with open(os.path.join(capture, f"{label}-{train}.json"), "w", encoding="utf-8") as fh:
                    json.dump(doc, fh)
            out.append("top-level: " + json.dumps(shape(doc), separators=(",", ":"))[:900])
            flat = flat_table(doc)
            if flat:
                out.append(f"parallel-array reply: {json.dumps(flat['lengths'], separators=(',', ':'))[:300]}")
                if flat["rows"]:
                    out.append("first 2 rows: " + json.dumps(flat["rows"][:2], separators=(",", ":"))[:700])
            key, rows = first_array(doc)
            if rows:
                filled = [k for k in shape(rows[0]) if any(str(r.get(k, "")).strip() for r in rows if isinstance(r, dict))]
                out.append(f"stop array '{key}' has {len(rows)} rows; non-empty in at least one row: {filled}")
                out.append("first 2 rows: " + json.dumps(rows[:2], separators=(",", ":"))[:700])
            if label == "identity" and isinstance(doc, (dict, list)):
                ident = identity_from(doc if isinstance(doc, dict) else {"r": doc})
                out.append("identity read: " + json.dumps(ident, separators=(",", ":")))
                if ident.get("name") and len(ident.get("from", "")) == 3 and len(ident.get("to", "")) == 3:
                    live["trains"].append({"train_no": train, "name": ident["name"],
                                           "from": ident["from"], "to": ident["to"]})

    for src_code, dst_code, must in pairs:
        probe_lines, entry = probe_pair(src_code, dst_code, must, date, today, capture)
        out += probe_lines
        if entry:
            live["pair_lists"].append(entry)

    # Propose, never apply: an automated edit to the truth fixture would be a second source of "the app
    # quietly believes itself". A human reads this block and pastes it, and the commit says who checked.
    out.append("\n### proposed refresh of fixtures/known_truths.json")
    out.append("```json\n" + json.dumps(live, indent=1)[:3000] + "\n```")

    body = "\n".join(out)
    print(body[:9000])
    tok = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    repo = os.environ.get("GITHUB_REPOSITORY")
    sha = os.environ.get("GITHUB_SHA")
    if tok and repo and sha and os.environ.get("PROBE_POST", "1") == "1":
        subprocess.run(
            ["gh", "api", "-X", "POST", f"repos/{repo}/commits/{sha}/comments",
             "-f", f"body=### live-feed probe\n\n```\n{body[:5000]}\n```"],
            check=False, timeout=60,
        )
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as fh:
            fh.write("### live-feed probe\n\n```\n" + body[:4500] + "\n```\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
