# M0 SPIKE VERDICTS — which sources answer, from where

Rule: a verdict is per LOCATION. The same source can be blocked here and usable on the
owner's phone (brief §4). Capture before parse (§7.4); a single timeout is not a verdict
(§10.8 — probe retries once on TIMEOUT; the failures below are TLS handshake resets, not
timeouts, so no retry applies).

Measured 2026-10-11 from the dev sandbox by `python3 tools/probe.py sandbox`
(artifact: tools/captures/probe-sandbox-20261011T212355Z.json, local only):

```
S1-NTES    enquiry.indianrail.gov.in      blocked-by-network (tls)
S2-erail   erail.in                       blocked-by-network (tls)
```

| source | sandbox | GitHub CI runners | owner's phone |
|--------|---------|-------------------|---------------|
| S1 NTES | blocked-by-network (TLS handshake reset; 2026-10-11, 4th independent confirmation of the brief's recorded finding) | blocked-by-network (brief-recorded, 3 confirmations) | usable (brief-recorded; re-measure once an APK exists) |
| S2 erail | blocked-by-network (same failure mode; 2026-10-11) | unmeasured — expect blocked, same class | usable-but-labelled (brief-recorded; re-measure at M2) |
| S3 dumps | n/a (do-not-repeat, brief §4) | n/a | cold-start fallback only, permanently labelled |
| S4 portals | not probed yet — add data.gov.in to tools/probe.py before M0 exit | - | - |
| S5 APIs | forbidden at runtime (keyed; §3.1) | forbidden | design reference only |

What this page tells the owner: from dev machines (sandbox AND CI) the railway endpoints
do not answer TLS; phone measurements are the authority for S1/S2, and they happen at M2.
Phone-visible change this round: none — this is research.

Next measurements (each has a named owner step):
1. CI-1: run tools/probe.py inside a GitHub Actions job → verdict column "CI" becomes real.
2. M2 on phone: one pair search, paste exported text (§14.3) → phone column becomes real.
