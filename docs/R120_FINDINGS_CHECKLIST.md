# R120 measured findings checklist

Source commit: `5cc28b76a4076064a89b3cf134cf877c63affe7b` (`arena/6f68e526-checktrain02`). This checklist is **not** an overall green verdict. R119 evidence and the preceding failures are in [R119_FINDINGS_CHECKLIST.md](R119_FINDINGS_CHECKLIST.md).

| Check | Observed R120 evidence | Status |
| --- | --- | --- |
| Same-station rows / invalid live pair | Online and offline *trust* exact-published-APK partitions pass two methods each; these explicitly assert zero stale rows and accessible controls. | PASS on tested profiles; not owner-device confirmation |
| Settings / mode touch targets ≥48dp | Both *trust* partitions pass on emulator; narrow-screen regression render compiles and unit suite passes. | PASS on tested profiles |
| Three visible main stations at large font | Offline *journey* exact-byte partition passes after installed/build/release identity checks. | PASS on tested profile; repeated physical device check pending |
| Manual refresh, actual receipt, maintained route | Online/offline *refresh* partitions pass one method each. | PASS on tested profiles |
| Date usable on API24, source dropdown / About times scroll reachable, Auto · 1 min receipt | New vertical full-width date, strict width test, scroll-to-control journeys and synchronous busy transition. Broad E2E device rows min24-station1, density-track0/1/2/3 all passed at current head. | PASS on tested emulator profiles, including the previously failing methods |
| Published bytes and signing | Android [39016861473](https://github.com/tarungittarun/Checktrain02/actions/runs/39016861473) passed six online/offline traveller jobs; each downloads release and build independently, checks signer/version/commit and compares installed bytes. Rolling release names `5cc28b7`, versionCode 50 / `1.0.49-r120`, 11,086,220 bytes, SHA-256 `1cb9dd6bdbe2f3ab421a79d2276f8ffe14d442accaa2bd87d7ec8f5e1ac365ff` (GitHub release asset digest; also independently checked by CI audit). | PASS in CI; owner's phone not tested |
| Build / acceptance runtime ≤180s per job **and** workflow | Android workflow wall **402s**, 08:18:21–08:25:03 UTC (last job ended at 08:25:02, 401s after creation). Debug build 168s; ui-render 184s. Traveller durations: online journey 168s, trust 193s, refresh 215s; offline journey 144s, trust 148s, refresh 144s. | FAIL: 3 jobs >180s and 401s workflow |
| Phone-app location parity | No authenticated phone-app feed or independently verified position observation. | UNVERIFIED; website page clocks cannot establish movement |
| Owner acceptance / merge | PR #3 remains unmerged. | PENDING user confirmation |

Broad E2E is a *separately built* variant, not an exact-published-byte certification. [R120 broad run 39016861436](https://github.com/tarungittarun/Checktrain02/actions/runs/39016861436): **31/31 device/release rows passed**, and 372 JUnit cases passed with zero skips. Its strict audit job **failed**, correctly: independently built E2E APK DEX hashes did not match the Android APK (do not equate those binaries), live phone-app position parity is unverified, and the three-minute target failed. E2E wall time **807 s**; 33 of 55 measured jobs/workflows exceeded 180 s or lacked a measurement. The slowest device job was 500 s. All five previously red R119 device rows are green here, but green test counts do not establish source parity or satisfy the three-minute goal.

**Strict result: FAIL**. Remaining work: eliminate the duplicate-APK correspondence ambiguity (use the exact published artifact for the broad device matrix or prove reproducible bytecode without relaxing the audit); reduce both workflow and long emulator-job wall times without skipping tests; verify on the owner's phone. PR #3 stays open.
