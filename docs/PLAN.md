# PLAN — stages, exit criteria, phone impact (brief §7, §8, §13.1)

| stage | what | exit criterion | what changes on the phone |
|-------|------|----------------|---------------------------|
| S0 / M0 | research spike | docs/spike-verdicts.md has a verdict per source per location (sandbox/CI/phone) | nothing — research, SAY SO |
| CI-1 | skeleton + pipeline | one CI run quoted; debug APK artifact exists with size+date read from the API | installable APK exists (blank screen) |
| S2 / M1 | contract + provenance + known-truths gate | contract tests green in CI; app cannot print a time without a label (test-enforced) | results screen shows the 3 provenance states |
| S3 | component PoCs | COMPONENTS.md register: every component PASS or explicitly blocked | nothing |
| S4 / M2 | live pair search | owner-device SMET pair matches live source or screen names the failure | real trains between two stations, labelled |
| S4 / M3 | running status | no row shows an unattributed delay; first-row latency unchanged | late/platform info per visible row |
| S5 | QA matrix | QA_MATRIX.md rows green or waived with reason | stability |
| S6 / M5 | release hardening | signed release on tag; RUNBOOK.md works | signed install |

Order is fixed by the brief; no stage opens before the previous exits (§7).
