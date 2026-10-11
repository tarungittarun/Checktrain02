# DATA_SOURCES — source ledger (brief §4, §13.1)

Format per source: endpoint / auth / shape / cadence / latency / licence / verdict / last measured.

## S1 NTES / CRIS (enquiry.indianrail.gov.in/crisns/AppServAnd)
- auth: none (AES-CBC + MD5 envelope; constants public in official APK, not secrets)
- shape: UNCAPTURED — no parser until a capture exists (§7.4)
- cadence: ~5-10 min recompute; latency: minutes; licence: unofficial, no SLA
- verdict per location:
  - phone: usable (brief-recorded, pre-2026-10-11; re-measure at M2)
  - GitHub runners: blocked-by-network (TLS handshake; brief-recorded, 3 confirmations;
    re-measured 2026-10-12 run 38177163382: timeout x2 from the runner — timeout class,
    same verdict)
  - this sandbox: blocked-by-network — re-measured 2026-10-11 by tools/probe.py,
    `SSL_connect: SSL_ERROR_SYSCALL` at TLS stage (4th independent confirmation).
- trust: HIGH on stated values, LOW on completeness (hand-updated last-reported stop).

## S2 erail.in (erail.in/rail/getTrains.aspx)
- auth: none (api.erail.in is keyed — forbidden for runtime, §3.1)
- shape: flat `~`-separated text + embedded feed stamp (e.g. 2026-10-11-7-56-55); path matters
- cadence: hours; licence: unoffical web endpoint; second WITNESS only
- verdict per location:
  - phone: usable-but-labelled (brief-recorded; re-measure at M2)
  - GitHub CI runner: **usable** — measured 2026-10-12 (run 38177163382, probe job):
    TLS+HTTP completed, verdict "usable (shape unverified)". This is the M0 turning
    point: S2's parser can be developed and regression-tested in CI, no phone needed.
  - this sandbox: blocked-by-network — re-measured 2026-10-11, same TLS failure as S1.
- trust: MEDIUM; two agreeing keyless sources = evidence, one = hypothesis.

## S3 GitHub timetable dumps
- MEASURED FAILURE (brief §4): 6,775-train build with pre-2022-23 numbers; retired `0`-prefix
  10.9%; 16228 wrong identity; 16221 reverse-keyed. Commit date ≠ data vintage.
- verdict: cold-start fallback only, permanently labelled, never an authority. Do not repeat.

## S4 Open-data portals (data.gov.in, CRIS/NPTRI bulk)
- CANDIDATE, UNADMITTED. Needs: measured cadence + vintage + key requirement; must pass
  known-truths gate including the three S3 errors. CI-secret build-time key allowed only
  with ADR + owner consent. Promote only on evidence. Not measured this round (egress to
  data.gov.in not yet tested — add to probe before M0 exit).

## S5 Third-party APIs (RailRadar, Railway Engine, indianrailapi.com, railwayapi.com, Apify)
- all keyed/paid/ToS-grey: forbidden as runtime providers. Design reference only
  (Railway Engine freshness envelope = the model §3.2 implements).
