# R119 failure checklist — do not equate a build with a usable APK

Source of failures: published-APK acceptance at [`4ea8afa`](https://github.com/tarungittarun/Checktrain02/actions/runs/39012352308), Android API 34 regular and offline/1.30×/small-viewport profiles. This document records **fixes proposed in source** separately from **observed results on a new published APK**. No item is passed by a code diff alone.

| Check | Previous evidence | R119 change / proof required | Status |
| --- | --- | --- | --- |
| Settings and mode touch targets | Settings ~44dp on both profiles; Segment configured 40dp | Minimum 48dp for clickable controls; regular and offline/trust rows must both pass; render check measures Refresh at 48dp | Code changed; device result pending |
| Impossible same-station result | Explicit refusal but previous train rows remained >3s online | Never ask live pair for invalid/same resolved stops, cancel/invalidate older query, reject stale callback on Main. Unit guard + online/trust device assertion with zero rows | Code changed; device result pending |
| Large-text route viewport | Previous offline large-text run showed <3 visible main stops; next run passed without UI change | Reserve room proportional to font scale for three main halts, while source card scrolls independently; offline/journey device row must pass, and source summary must remain visible | Code changed; intermittent risk until repeated device runs |
| Manual refresh | Guarded online/offline test passed; earlier red was a precondition error | Preserve all existing strict receipt/route assertions. Never infer movement from the page clock | Existing pass; recheck on R119 |
| Binary/signing | R118 published, built and installed hashes matched, test key verified | R119 code must increase and same published APK must be installed in every traveller job; no fresh random signing key | R119 code bumped; pending APK verification |
| Device job and workflow wall time | `4ea8afa` Android 370s; 3/6 traveller jobs >180s | Measure every R119 acceptance job and full workflow; **FAIL** if any still exceeds 180s. Do not claim splitting alone solved it | Pending measurement; no waiver |
| Phone-app position parity | No authenticated phone-app feed | Never certify parity from fixture tests or web timestamps | UNVERIFIED; no invented feed |
| Owner install / PR | PR #3 open | Keep PR open until owner confirms updated APK on phone | Pending owner confirmation |

A **strict PASS** requires a current-commit APK, passing exact-byte end-user rows, no skipped tests, and measured timing compliance; passing selected tests is only domain evidence. The broad E2E matrix builds separately; it must not be described as certification of published bytes.
