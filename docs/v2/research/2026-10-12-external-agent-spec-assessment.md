# Assessment: externally researched "zero-cost live tracking" spec (owner-provided, 2026-10-12)

The owner supplied a three-part spec from outside research: (1) a system architecture, (2) a
Python/FastAPI scraping proxy with cache, (3) an Android Retrofit client bound to that proxy.
Verdict per part, against this project's measured evidence map (rounds 13–26, both vantages:
the owner's phone and CI datacenter runners).

## Part 1 — architecture

| Claim | Verdict |
|---|---|
| Static layer local in the APK (routes, codes, coords, scheduled times); never fetched at runtime | **Already ours since v1** (offline asset, 6,775 trains; the UNVERIFIED banner exists precisely because this copy can be stale). |
| Dynamic layer = "parse open NTES endpoints by emulating valid browser sessions" | **Built and measured dead.** Fifteen-plus request shapes and a real browser engine (cookies, headers, referer, origin) have been tried from the phone: the app's stack is mid-session killed ("IOException: closed", the owner's pastes #7/#9/#10/#11), and the browser engine's POST is *accepted and never answered* (10–13 s hangs, pastes #9–#11). From datacenter IPs (six CI runs) NTES TLS-times out. There is no third vantage. |
| Cache 60–120 s per train, shared across users | **Adopted (this round)** for the one live pipe that exists — the traveller-report relay: reader polls are now throttled to ≥90 s (the midpoint of the proposed window), except immediately after the user's own publish. Note the spec's premise ("multiple users must be served from cache") presumes a shared server; our users share nothing but the public relay, which is itself the cache. |

## Part 2 — the FastAPI scraping proxy

**Rejected — and not on principle first, on measurement.**

1. **The vantage it depends on is the one we falsified.** The proxy scrapes from a server —
   i.e. a datacenter IP. Every datacenter attempt we have on record (GitHub runners ×6) ended
   in TLS timeouts / 403 / 404 against NTES and erail. A Docker host would be born behind the
   exact wall we spent rounds mapping. The spec's load-bearing assumption — "a server can
   reach these endpoints" — is the one assumption this project has already tested and buried.
2. **It never ran.** The script's tracking URL is malformed (`indianrail.gov.inq?opt=…`), its
   form fields (`jStation`, `language`) and CSS selectors (`station-row`, `trnRunStatTbl`) are
   invented — the kind of plausible code an AI produces without executing. This project's
   method forbids exactly that: parsers are written only against CAPTURED payloads
   (`app/src/test/resources/captures/`), and every capture so far shows refusals, not tables.
3. **It violates the product's standing constraints** (no server of ours, no fees, no ops
   burden) and re-creates the exact death pattern ADR-006 documented for scraper apps: one
   host, one IP, one ban / one unpaid bill away from dead — while presenting as "live".

## Part 3 — Retrofit client bound to "your new free tracking server"

Rejected as specified (it binds the APK to the Part-2 proxy). What survives is its *shape*:
a client that renders `station_code / scheduled / actual / delay` rows is precisely Phase A's
rendered-page parser (the pending `webview-erail-rstrain-dom-tap` paste decides whether erail's
page yields such a table) — and precisely what the traveller-report reader column already
renders from real witnesses. The transport, not the table, is what the evidence kills.

## The closing questions the spec asked

- "Docker compose + production Redis?" — **No.** There is no server in this architecture, by
  the evidence above; a cache layer in front of a blocked scraper caches the block.
- "Android background service for offline layout caching?" — **Already shipped, differently:**
  the offline asset, the 24-h-refusal snapshot cache with honest ages, the pair cache, and the
  report column rendering from the last poll. A background service would drain battery for
  data every measured door refuses to give.

## What this assessment changes in code

One change, this round: relay-reader poll discipline (≥90 s, publish-bypassed) —
`LiveViewModel.CROWD_POLL_MIN_MILLIS`. Everything else the spec proposes is either already
shipped or already measured dead; nothing is adopted on hope.

---

## Addendum (same day): the owner's second research piece — the "NTES Android app gateway"

Claim: `POST https://enquiry.indianrail.gov.in/crisns/AppServAnd`, JSON body
`{"queryType":"6","trainNo":…,"startDate":"12-Oct-2026","stationCode":"","lang":"en"}`,
dalvik UA, answering `trainFullStatus[]` with `schArrTime/actArrTime/actDepTime/arrDelay/depDelay`.

Verdict: **worth probing — and only the phone can probe it.** Unlike the previous piece's proxy
(this project's one unfalsifiable-in-advance claim), this targets the SAME host the owner's phone
already TLS-handshakes with (`enquiry… handshake: ok in 352 ms`, ENV lines of pastes #9–#11) on a
PATH this project has never tried. The claims are unverified — the endpoint name, payload keys and
response schema come from a write-up that may never have run — but this project's method has a
mechanism for exactly this: the probe ladder, on the one vantage where the host answers, with the
result captured and judged by the in-app time-token scan. Shipped as two variants this round:
`post-appservand-json` (the app's own stack, dalvik signature) and `webview-appservand` (the
browser engine, warm on the NTES page — the transport axis that has separated this phone's two
stacks all along). They replace the two getRunTrain spellings, measured 404 twice (pastes #10/#11)
and retired with evidence banked.

Adopted implicitly from the same write-up: the dd-MMM-yyyy date format ("12-Oct-2026") — the same
lesson this project learned from erail's 404 (the date string is part of the request's
fingerprint); generated by `FeedProbe.mmmDate`, never hand-typed.

Rejected from the same write-up:
- **`verify=False` on TLS.** Never. If CRIS cycles certificates, the system trust store handles
  it; a disable-verification flag turns "live actuals" into a channel a network hop could poison
  with fabricated arrivals — the exact class of untruth this product exists to prevent.
- **The offered Retrofit/Node clients.** Premised on the gateway working. The capture decides
  first; a client is written against a captured payload or not at all.
- **The 90-second cache** — already adopted last round (relay-reader poll discipline).
