# R123 owner-phone checklist (17 Oct 2026)

Inputs: owner screenshots of train 20651 at 16:01 and 16:16 on 17 Oct 2026. The screenshots show a website past-card at Dodbele (15:57), then Kyatsandra with the expanded YPR–TK map scrolled to its start. They show `~15:19`, `~15:20`, etc. at the *far right*, though those are timetable/map estimates, not actuals. The screen shows SBC actual departure missing. Screenshots do not contain the source's raw SBC card, so a 15:00 actual **cannot** be invented.

| Owner request | Implementation | Acceptance / limitation |
| --- | --- | --- |
| Larger receipt, two lines | `Took: <elapsed> sec` over `At: <source update>`; 22sp maximum vs 11sp, one-second expanding fade; when a connection fails show `Connection failed`/`Source timed out`/`Source blocked request` and `Plz change source` there, no duration or update stamp. | Source clock is not event time; unknown update prints `—`. A failure is not the same as an unchanged HTTP answer. |
| Column insets | Header, main-stop times and rail share +8dp left; ACTUAL has 20dp right inset. | Pixel/layout assertions at 360dp and big font. |
| Expanded map focus | Scroll to the *drawn train centre* inside the expanded leg, not the row's start; focus after the latest list commit for first load, manual and Auto refresh. | If no train position is evidenced, no fake focus target. |
| Intermediate time classification | Static scheduled/interpolated `~` clocks moved to TIMETABLE left; ACTUAL right shows `—` for non-stops or missing reports. Selected website's validated *stop* arrival/departure can paint in amber with website-claim accessibility, never NTES Act. | The website's `Web · <time>` beside a non-stop train icon remains a separately attributed past-card claim, not evidence that it stopped. |
| Starting-stop departure | A separately past-marked origin website card with a schedule-paired departure may show even when the two clock values coincide; future/white cards still cannot. | In a screenshot alone no origin source card is visible. If the website did not publish such a card, SBC remains `—`; a schedule minute must not be promoted to railway actual. |
| Hero handle | Expanded and collapsed More/Less stay at upper right. | Same touch target and accessible label. |
| Default source | Home has a Data source tracking-default selector; it temporarily yields viewport space to the keyboard so inline suggestions remain reachable, then returns; tracking screen uses it and allows changing it, saving back to Home. | Website and web cache labelled unverified; default is view preference, not fabricated telemetry. |
| Search sorting and frequency | Both search modes allow Suggested/Departure/Arrival/Duration sorting using absolute run instants; result rows show S M T W T F S, underlining running days. | Unknown run mask says days unavailable; do not mark it daily. |

## Test plan / status

- Prior measured R122 workflows took Android 670s and E2E 845s, **both under the new 19-minute (1140s) ceiling**; 29/55 individual jobs or workflows still exceeded the older 180-second goal. Recheck R123 duration before declaring budget success; do not weaken strict APK-correspondence and live-parity checks.
- Local JDK/SDK are absent. Use CI's current-head Android compile, all four unit partitions, pixel/layout renders, six exact-published-APK acceptance partitions, and 31-row device/release E2E. Report strict audit failures separately, including DEX correspondence and the owner-phone telemetry gap.
- Owner-phone verification pending; do not merge PR #3 until confirmed.

## First R123 CI findings

- Android run 39027468431 failed: 2 ui-logic and 1 ui-render fixture mistakes (clock label colon count, Android SpannableString tested without Robolectric, website claim wording), plus 2/6 traveller partitions where the new 48dp home selector crowded inline suggestions behind the keyboard. Four other partitions passed. Fixed fixtures, exact `Plz change source` copy, and reserved the resized keyboard viewport for suggestions; rerun at new head is required before claiming success.
- E2E 39027981394 on intermediate 1618e1e finished in 807s with 31/31 device partitions green but failed the strict audit: its corresponding Android run had been cancelled by the next push, so APK parity and unit XML could not be evidenced. Newer c092d1b E2E 39027995628 was cancelled by that intermediate run's delayed plan: the plan compared SHAs rather than run numbers. Limit supersession to genuinely older run numbers and require a fresh current-head full sweep.
- Final c092d1b Android run 39027995591 passed all four unit partitions, render checks and six exact-published-byte traveller partitions in 661s (<1140s). Published versionCode 53 / `1.0.52-r123`, SHA-256 `28b2eb32a758f5e559aed9719191c2d26ffbdb743df009a44fd8fbbd4fb068a2` (11,108,552 bytes); its E2E remains unverified due to cancellation, not a pass.
