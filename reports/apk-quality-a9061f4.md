# Latest APK quality audit — R118 / a9061f4

**Overall: STRICT FAIL.** The app passed its current-head build, unit tests, render assertions, and all 31 installed-device rows. It did **not** pass release certification. These statements refer to the same source commit, `a9061f425f6f4ac57a0cde2cefde2f5c6ca68753`, and must not be conflated with a green E2E workflow: the E2E run itself finished **failure** because its terminal audit enforced the failing verdict.

- [Download the R118 debug APK](https://github.com/tarungittarun/Checktrain02/releases/download/latest-apk/app-debug.apk) — 11,083,032 bytes, versionCode 48, versionName `1.0.47-r118`; artifact SHA-256 begins `8da567d3454db17c`. The rolling-release asset matched the Android artifact byte-for-byte when audited. The `latest-apk` URL is rolling; confirm its release notes still say `a9061f4` before treating it as this build.
- [Android build and publication](https://github.com/tarungittarun/Checktrain02/actions/runs/39007980342): **success**.
- [Full E2E and strict quality audit](https://github.com/tarungittarun/Checktrain02/actions/runs/39007980315): **failure**, solely at the audit-verdict enforcement step after the evidence collector and 31 device rows succeeded. The raw, same-commit audit is the `apk-quality-audit` artifact, the job summary, and a comment on the commit.
- [PR #3](https://github.com/tarungittarun/Checktrain02/pull/3): still open and unmerged; owner's device confirmation is required before merging.

## Domain results

| Domain | Result | What the run establishes |
| --- | --- | --- |
| APK build, field-test compile, static checks and publication | PASS | Android build, checks, field-tests, screens and publish jobs green; exact rolling APK bytes equal the Android APK artifact. |
| Backend/source contracts | PASS (fixtures) | 185 source cases and 111 rules cases. These are deterministic source/parser/transport tests, not verified phone-app telemetry. |
| Frontend, layout and appearance | PASS (tested surfaces) | 38 UI-logic and 31 render cases, including screen bounds and station/time alignment assertions. Screenshot publication succeeded; it is not an exhaustive human visual review. |
| Installed-device UX | PASS (tested flows) | 31/31 required rows green across Android API 24 and 34, density, offline/network/settings, station/train/track and release smoke; no missing row. The coverage-report gate succeeded. |
| JUnit completeness | PASS | 365 tests total; zero failures or skips in the four required partitions. |
| Offline asset | PASS | The APK contains a 5,882,047-byte offline timetable asset. |
| APK signing/update-key continuity | **FAIL / not established** | The auditor did not extract a matching certificate fingerprint; details below. Do not call the APK unsigned on this evidence. |
| Published-versus-device-tested APK | **FAIL** | The two independently built APKs' compiled DEX digests did not match, so installed-device successes cannot certify the *published bytes*. |
| Live phone-app movement parity | **UNVERIFIED** | No authenticated Where Is My Train phone-app position feed. Website update times cannot prove minute-by-minute position. |
| ≤180-second jobs and workflows | **FAIL** | 27 of 49 measured jobs/workflows exceeded the limit or lacked a measurement. |

## Root-cause and evidence caveats

1. **Signing:** `apk_inspection()` calls `apksigner verify --verbose --print-certs`. In this code, the specific reported error, “APK signature could not be verified,” is emitted **only after that command exits successfully** but the expected `Signer #1 certificate SHA-256 digest: ...` line does not match the parser. Thus the run does **not** prove an unsigned APK. The raw signer output was not preserved in the artifact, so the exact formatting/parser discrepancy cannot be diagnosed from this run. The stable test-only update-key fingerprint comparison was consequently not performed; keep the gate red rather than inferring continuity.
2. **Binary correspondence:** The Android artifact and release asset matched; the E2E device matrix used an APK built separately by its own `build` job. Both APKs were available for inspection, and their DEX hash maps did not match. The mechanism behind the mismatch (nondeterministic compilation versus different generated inputs) was not established. Sharing one canonical APK between publication and the device matrix, then verifying its whole-file SHA-256 at install time, would resolve this uncertainty; simply relaxing the DEX comparison would not.
3. **Live data:** The current source tests use known fixtures. Neither those tests nor a changing website page timestamp establish the position shown by Google's phone app; this requires a verified source or an explicitly labelled side-by-side observation, not interpolation.
4. **Time budget:** Workflows have dependency and queue time as well as individual job wall time. Splitting device partitions alone did not make the *workflow* satisfy the three-minute requirement. The reported SLA is a measured failure, not a timeout to be increased.

## Performance highlights (observed wall time)

| Workflow/job | Time | Against 180 s |
| --- | ---: | --- |
| E2E full workflow (queue-inclusive) | 843 s | FAIL |
| E2E min24-track3 device job | 436 s | FAIL |
| E2E min24-settings-ui device job | 434 s | FAIL |
| Android workflow (queue-inclusive) | 406 s | FAIL |
| E2E density-track3 device job | 392 s | FAIL |
| E2E api34-track3 device job | 379 s | FAIL |
| Android render-unit job | 178 s | PASS, only 2 s margin |
| Android UI-logic job | 171 s | PASS |
| Android sources job | 165 s | PASS |
| Android debug APK build job | 159 s | PASS |
| Audit evidence/verdict job | 23 s | PASS for duration, FAIL for verdict |

**Disposition:** Shareable for hands-on testing, **not certified**. Do not merge PR #3 or describe the downloadable binary as device-verified yet. The next audit should retain all strict gates, record the raw signer output, test exact APK bytes on-device and remeasure the whole workflow after a substantive performance change.
