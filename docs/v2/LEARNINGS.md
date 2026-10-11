# Learnings — what this project got wrong, and the rule that follows

Twelve rounds, ~100+ CI runs and ~10 hours produced an app whose **data was wrong**. Not because the tooling
failed — every gate stayed green the whole time. This is the list that must be true of all future work. Each
lesson names where it is *enforced*, because a lesson in prose is a wish.

1. **Internal consistency is not truth.** The ETL validated schema, index plans, monotonic distances and three
   fixture trains — all from the same stale dump, so nothing could disagree with itself.
   → Enforced by `tools/ci/data_truths.py`, whose facts come from *outside* the repo.
2. **A commit date is not a data vintage.** I told the owner the timetable was "113 days old" because the
   source repo's last commit was 2026-06-20 — while 10.9% of its trains still carry numbering retired in
   2022-23. I repeated the exact sin I had just fixed (build stamp → data stamp).
   → Enforced by the `retired_prefix_pct` check, which fails on the data's own anachronisms.
3. **Never present a fallback as authoritative.** A label like "as of <date>" that is really "when we packed
   it" converts a data problem into a trust problem.
   → Enforced by `dataVersion()`'s three states and the `check_literals`/`check_refs` gates on the string path.
4. **Reusing the source's identity assumptions is how a wrong train ships.** `16228` in our copy is a different
   train. Numbers are recycled; only a live source can say what a number means *today*.
   → Enforced by Stage 1 (provenance on every row) and C15 (live pair search).
5. **One ordering must not serve two decisions.** Proximity is right for "which instance of this number do we
   keep" and wrong for "what order does a passenger read". The bug survived because both used one comparator.
   → Enforced by `TrainFinderTest` (`…LeftAMinuteAgo…` for selection, `catchableTrainsComeFirst…` for display).
6. **A missing train is worse than a slow app, and it looked like a working one.** Trains keyed in the reverse
   direction simply vanish from one whole direction of search — no error, no empty state, just fewer trains.
   → Enforced by `pair_lists.must_include` in the gate (T9.7 adds `must_not_include`).
7. **A diagnostic must never outrank the user's request.** An unbounded probe ladder on one serialised socket
   lock made refresh take 31–44 s.
   → Enforced by budgets + `cancelRequested` + `portDead` short-circuit (C5).
8. **Green CI is not a user-visible result.** Rounds 1–7 were mostly provable plumbing while the phone showed
   wrong times.
   → Enforced by the round rule: change what the phone shows, or say plainly why you didn't (PLAN.md §2.4).
9. **I optimised the wrong layer first.** The one 20-minute check — query the shipped DB and compare with a live
   source — should have been round one. Cost: ~9 rounds.
   → Enforced by ordering in PLAN.md: data correctness is Stage 0/1, not a follow-up.
10. **Guessing at an API shape burns rounds.** `NtesWire.parse` has never seen a real payload; library docs
    imply PascalCase keys. Every fix there so far was a hypothesis.
    → Enforced by Stage 0's fixture requirement: no parser without a captured response.
11. **Local gates are not a type-checker.** `apply()` reporting `ok` proves an anchor matched, not that the file
    parses; `ast.parse`/`bash -n` in the same breath caught two of my own edits.
    → Enforced by running `py_compile` + `bash -n` + `check_*` on every edit round, and never announcing a
    build before the run says so.
12. **Tooling-shaped promises need receipts.** Telling the owner "install this APK" as the test loop was a
    design failure, not a constraint. Emulators exist in CI; the missing piece is fixtures + a local mock.
    → Enforced by T9.9 and PLAN.md §6, which states what the emulator *cannot* prove (railway egress) instead
    of implying it can prove everything.

13. **A second push destroys the first push's evidence.** Pushing `52cd940` while `a06ac16` was still running
    cancelled `52cd940`'s build through the concurrency group, and left `a06ac16`'s e2e `report` job failing on
    "no results to roll up" - a red row that meant nothing, and a green build we never got to read. The fix is
    not a config tweak (cancelling stale builds is the right economy on 2 vCPU runners): it is **one push per
    round**, then read the run, then start the next round.
    → Enforced by this rule + the CI-state line in `docs/v2/STATUS.md`, which records each round's run id so a
    later round can tell "cancelled by me" from "failed on its own".

## Method the next rounds follow (what "actual product development" means here)

Scope → contract → **component PoC with an executable pass criterion** → implementation → automated proof at
the lowest layer that can give it → device proof only where devices are the only witness → ship with a written
delta → record in `docs/WORKLOG.md` + `docs/v2/STATUS.md`. A component without a PoC does not enter a shipping
screen. A claim without a named artifact is not made. Fabricated data is never a fallback: `—` beats a guess.
