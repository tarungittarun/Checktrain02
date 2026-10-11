# Worklog — where this is at, and how to continue

Read this first, every time, before anything else. Update the three top lines before you stop.
It exists because CI knowledge is worthless if it lives only in the session that earned it.

## Head

- **HEAD / branch**: `arena/edd38b02-checktrain02` (push only here; one branch per session).
- **Last CI observation**: the run on `6abcadb` was rejected as an invalid workflow file (`jobs: null`) because
  a scripted patch of mine put `default:` on a `workflow_dispatch` input twice — and `check_workflows.py`
  reported 0 problems, because its block model let a mapping's first child swallow its siblings. Both are
  fixed: the input has one default, the scanner now tracks each mapping by its children's column (discovered
  lazily) and skips block scalars by column, and `tools/ci/test_check_workflows.py` is a permanent 11-case
  regression set that `plan` and `android.yml` run **before** trusting the checker.
- **State of the device sweep**: run on `8bede2c` had four rows inside the action at 29 min with nothing
  published (annotations only appear at step boundaries). The next run adds the row clock, the visible
  `gh run download` fetch step and the boot-budget cap, so a slow row is now bounded and legible.
- **Open question the next run answers**: does the flow suite execute on the booted emulator now that
  `adb` is on the job's `PATH` and `avdmanager` gets `-b/--abi`? Expected: 6 instrumented passes per row,
  then coverage, then the monkey pass, then `### e2e/<row> — PASS|FAIL` on the commit.

## Reading a run from here (the only channels that work)

```bash
R=tarungittarun/Checktrain02
gh run list --workflow e2e.yml --branch arena/edd38b02-checktrain02 --limit 1 \
  --json databaseId,headSha,status,conclusion
gh api "repos/$R/actions/runs/<run>/jobs" --jq '.jobs[]|"\(.id) \(.name[0:24]) \(.status) \(.conclusion//"")"'
gh api "repos/$R/check-runs/<job id>/annotations?per_page=100" \
  --jq '.[]|"[\(.annotation_level)] L\(.start_line) \(.message[0:220])"'
gh api "repos/$R/commits/<sha>/comments?per_page=100" \
  --jq '[.[].body|select(startswith("### e2e"))]'
```

- Job logs redirect to blob storage and are **not fetchable here**. The check-run annotations are.
- Annotations are **not published until a step ends** — a quiet in-progress row is not necessarily stuck.
  The per-step `[row] step N:` notices exist so that the stream, once readable, says how far it got.
- `gh run cancel` and `gh workflow run` answer **403** for this credential. A push supersedes an in-flight
  run; `[e2e-full]` in the commit message selects the 4-row matrix, `[e2e-focus: Class]` one class.

## Solved — do not re-litigate

| Symptom | Cause | Where the fix lives |
|---|---|---|
| Run named by file path, `jobs: null`, ~25 s red | duplicate mapping key (`if: always()` twice) — PyYAML accepts it, Actions rejects the file | `tools/ci/check_workflows.py` |
| Same, for an action input | `required: true` **and** `default:` on one input | same checker |
| Job dies as one step, no sub-steps | `always()`/`failure()` in a **composite step** `if:` | verdict step reads `steps.<id>.outcome` |
| `Can't find 'action.yml'` | a `uses: ./local/action` needs the **job's** checkout, not the action's | `e2e.yml` row jobs |
| `exit code 127` mid-row, no other annotation | `adb`/`sdkmanager` not on the **job's** PATH (the boot script's export is local) | `$GITHUB_PATH` in `emulator_boot.sh`, visible `gh run download` fetch step |
| `Found AVD target architecture: arm` on x86_64 | hand-written AVD lacked `systemImage.arch` | `emulator_boot.sh` |
| `avdmanager create avd` prints its usage | needs `-b/--abi` named; global options go before the subcommand | `emulator_boot.sh`, three forms tried per run |
| Row comment never posted | `set -e` + `grep -q … && verdict=FAIL` aborts the step | `if` statements in the action |
| `1/1 passes green` on a row with no device | roll-up read `variant_row=` as a test | `e2e_rollup.py` (verdict-shaped values only) |
| False `rc=0` for every failed avdmanager form | an `if` with no `else` exits 0 | capture `rc` from the command |
| `jobs: null` right after editing an input | two `default:` keys on one input — and the linter blind to it | `dup_keys` rewritten, `tools/ci/test_check_workflows.py` runs first |

## Standing rules for edits in this tree

- After any script that rewrites `.github/**` by hand: `git diff --numstat` must show **additions with no
  deletions** unless the deletion is the point. One such script silently deleted 12 step bodies.
- Never chain an `assert`-guarded patch and a `git commit`/`push` in one shell command: a failed assert
  aborts the patch while the commit still runs.
- CI checkers are **stdlib-only**; PyYAML is optional and `check_workflows.py` degrades to indentation
  rules when it is absent (a runner's python has no PyYAML).
- Before pushing: `python3 tools/ci/test_check_workflows.py && python3 tools/ci/check_workflows.py &&
  `python3 tools/ci/flow_coverage.py &&
  for c in check_kotlin_balance check_refs check_resources check_members; do python3 tools/ci/$c.py; done`
  and `bash -n` on every harness script. All must be 0 problems.
- Keep the workspace lean: no generated artifacts, no scratch dirs in the repo, nothing outside it.

## What the pipeline is for

The app (offline timetables, live feed stamped and labelled, never presenting schedule as actual) is built by
`.github/workflows/android.yml`; `.github/workflows/e2e.yml` builds the APKs and then **drives them on
emulators on four parallel runners** across the 46 flows listed in `docs/E2E_FLOWS.md`, with the manifest
gated against `@flow` annotations on every event. `report` fails the run when no row booted or the minSdk
row's tests failed, and ratchets line coverage (`--min-lines 45`) — to be raised once the first green device
row produces a JaCoCo number.
