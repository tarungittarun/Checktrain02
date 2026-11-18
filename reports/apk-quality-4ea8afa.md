# Latest APK — parallel acceptance and measured performance

**Commit:** `4ea8afa3e21cb0b30d7e31fde143552866eb90d0`. **Overall: FAIL.** [Android workflow and six published-APK acceptance jobs](https://github.com/tarungittarun/Checktrain02/actions/runs/39012352308) completed. [Current-head core E2E](https://github.com/tarungittarun/Checktrain02/actions/runs/39012352319) runs separately; it tests a separately built app APK, not a substitute for the failing published-byte acceptance.

**APK tested:** rolling R118 debug APK, versionCode 48 / `1.0.47-r118`, 11,083,116 bytes, SHA-256 `d9e79ff70083a382d5b03799e9cdb8b9b546964250f256ee40abfa3e404ec94c`. [Download](https://github.com/tarungittarun/Checktrain02/releases/download/latest-apk/app-debug.apk) for investigation; this URL is rolling, so check its notes still say `4ea8afa` when using this report.

## What changed in the workflow

Five unchanged end-user acceptance assertions are partitioned **exactly once per profile** into `journey` (ticket→route, settings/back), `trust` (impossible trip, accessible control sizes) and `refresh` (manual request/receipt). The existing two online/offline profiles thus run as **six independent parallel jobs**, not two serial all-method jobs. A self-tested `acceptance_plan.py --check` fails CI if a future `@Test` is omitted or duplicated. Every job independently downloads the *published* APK, verifies its signature/stable test key, manifest/offline asset and version, compares it byte-for-byte to the same-run build artifact, installs it and compares the installed `base.apk` byte-for-byte. Results still fail closed; a failed journey does not suppress separate warm-launch timing or the screenshot/window/logcat artifact. No acceptance threshold or assertion was relaxed to save time.

## Performance — measured, not estimated

| Measure | Before split (`efaf0fd`) | After split (`4ea8afa`) |
| --- | ---: | ---: |
| Android workflow wall, including queue/dependencies | 496 s | **370 s** |
| End-user stage: slowest job | online all-methods 218 s | online journey **205 s** |
| End-user job durations | online 218 s; offline 164 s | online trust **146 s**, offline refresh **166 s**, offline trust **173 s**, online refresh **188 s**, offline journey **204 s**, online journey **205 s** |
| Jobs exceeding 180 s | 1/2 | **3/6** |

**Wall-time improvement: 126 s (~25%). The 180-second requirement is still NOT met** by the Android workflow or three acceptance jobs; this is a measured partial improvement, not a speed certification. Parallel emulator setup and the build→publish→install dependency remain on the critical path. The separate core E2E workflow also needs its own current-run performance accounting.

## Latest published-byte functional findings

| Profile / group | Executed | Verdict | Evidence |
| --- | --- | --- | --- |
| Online journey | 2/2 | PASS | Exact ticket→route and settings/back journeys passed. |
| Offline large-font journey | 2/2 | PASS | Both methods passed **on this run**. A previous large-font run failed to show three main stops; because no app UI change was made, this should be treated as a source/viewport-dependent regression risk, not declared fixed. |
| Online refresh | 1/1 | PASS | Manual refresh after the first source check settled passed. |
| Offline refresh | 1/1 | PASS | Same guarded refresh passed without network. |
| Online trust | 0/2 | **FAIL** | Settings tap target ~44.2 dp (<48 dp); same-station refusal still left train rows visible after a three-second settle wait. |
| Offline large-font trust | 1/2 | **FAIL** | Settings tap target 44 dp (<48 dp); invalid-trip method passed. |

All six groups obtained the **same published, artifact and installed APK SHA-256** and passed signing/key, package, asset and permission preflight. The runner measured **cold-process relaunches with already-installed data** after assertions even for failed trust groups: online **1,462 ms**, offline **1,150 ms**; both under the 10-second threshold. This is not a first-ever install or a warm-process resume measurement. Those two passes do not cancel the UX failures. Tests executed 5/5 methods in each profile; pass counts are **3/5 online** and **4/5 offline**, not a general product-quality percentage.

**Root-cause leads:** `BtnGhost` is configured with `minHeight=44dp` and `Segment` with `minHeight=40dp` in `app/src/main/res/values/themes.xml`, matching the accessibility measurement. `TrainFinder` returns empty results for `SAME_STATION`, but `MainViewModel.search()` still starts `fetchLivePair()` on that invalid request, and `applyLive()` can post replacement hits; inspect the recorded source response/callback before attributing all stale rows to this path. The online failure remains reproducible after waiting for the asynchronous list diff.

**Still unverified:** human visual approval on a physical phone, an older-version upgrade retaining data, exact published-byte API 24/R8 testing, screen-reader narration, and minute-by-minute phone-app telemetry. No claim of fully verified live position follows from these results. [PR #3](https://github.com/tarungittarun/Checktrain02/pull/3) remains open and unmerged. Do not certify this APK.
