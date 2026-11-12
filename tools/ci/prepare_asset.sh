#!/usr/bin/env bash
# The offline timetable asset, built the same way in every job that needs it.
#
# Kept in one file because two jobs now need it (the build job, and the render-test job, whose test reads
# the *packaged* asset rather than the ETL's output directory), and a copy of a 20-line pipeline is how
# the pin check silently stops running somewhere.
set -euo pipefail
# `tee /tmp/etl.log` below is what the CI failure report prints, so the log lives even though the step is
# shared between two jobs.

out=app/src/main/assets/ref/refdb.dbgz

# Deterministic given the same upstream dump, so the same commit always produces the same bytes. The
# committed asset is normally present, which makes this a ~2 s integrity check rather than a rebuild;
# `PREFDB_FORCE=1` (or `-PrefdbForce` for a Gradle-only run) forces the full ETL.
if [ -f "$out" ] && [ -z "${PREFDB_FORCE:-}" ]; then
  echo "asset already present: $(ls -l "$out")"
else
  /usr/bin/time -f "ETL wall clock: %e s" python3 tools/build_refdb.py \
    --profile halts --out "$out" 2>&1 | tee /tmp/etl.log | tail -25
fi

test -f "$out" || { echo "::error::no $out"; exit 1; }

# The app opens this asset by a hardcoded string in RefDb. If the two disagree, the APK ships unable to
# answer a single offline query while every Gradle task is green — which is what aapt's ".gz" renaming did
# before the asset was renamed. Pin the pair here, where the file exists and its real name is known.
grep -qF "ASSET_NAME = \"ref/$(basename "$out")\"" \
  app/src/main/java/in/opentrack/india/data/RefDb.kt || {
    echo "::error::RefDb.ASSET_NAME does not match the generated asset name"; exit 1; }

bytes=$(stat -c %s "$out")
echo "asset name pinned: ref/$(basename "$out") · $bytes bytes"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "| asset | bytes |"
    echo "| --- | --- |"
    echo "| ref/$(basename "$out") | $bytes |"
  } >> "$GITHUB_STEP_SUMMARY"
fi

# Does this dataset agree with the railway at all? tools/ci/data_truths.py holds the handful of facts a human
# read off a live source (fixtures/known_truths.json). A disagreement is deliberately not fatal here - a red
# build leaves the passenger on an OLDER APK, which is worse than a build that admits doubt - but the verdict
# is stamped inside the asset (meta.data_trust) and the app then refuses to present these times as
# authoritative. Green pipeline, honest screen, report in the job summary.
python3 - "$out" << 'GATEEOF' || true
import gzip, os, sqlite3, sys
sys.path.insert(0, os.path.join("tools", "ci"))
import data_truths
blob = open(sys.argv[1], "rb").read()
path = "/tmp/refdb-truths.db"
open(path, "wb").write(gzip.decompress(blob) if blob[:2] == b"\x1f\x8b" else blob)
db = sqlite3.connect(path)
res = data_truths.evaluate(db, data_truths.load_truths())
db.close()
print(data_truths.report(res))
summary = os.environ.get("GITHUB_STEP_SUMMARY")
if summary:
    with open(summary, "a", encoding="utf-8") as fh:
        fh.write("### Offline data vs known truths\n\n```\n%s\n```\n\n" % data_truths.report(res))
GATEEOF

# Timetable age is based on the last commit touching the schedule/route input files,
# not the clone tip or this build's timestamp. Commit age still does not prove accuracy.
python3 - "$out" <<'VINTAGEEOF'
import gzip, json, os, sqlite3, sys
blob = open(sys.argv[1], "rb").read()
path = "/tmp/refdb-vintage.db"
open(path, "wb").write(gzip.decompress(blob) if blob[:2] == b"\x1f\x8b" else blob)
db = sqlite3.connect(path)
meta = dict(db.execute("SELECT k,v FROM meta WHERE k IN "
                       "('timetable_vintage','timetable_age_days','timetable_freshness',"
                       "'source_vintage','run_days_vintage','stations_vintage','source_provenance',"
                       "'data_trust','data_truth_problems')"))
db.close()
try:
    provenance = json.loads(meta.get("source_provenance", "{}"))
except (TypeError, ValueError):
    provenance = {}
lines = [
    "### Timetable input vintage (source-history age, not correctness evidence)",
    "",
    f"- Schedule/route files last changed: **{meta.get('timetable_vintage') or 'unknown'}**",
    f"- Age at build: **{meta.get('timetable_age_days') or 'unknown'} days** "
    f"({meta.get('timetable_freshness', 'unknown')})",
    f"- Historical repository-tip vintage: {meta.get('source_vintage') or 'unknown'}",
    f"- Station-master vintage: {meta.get('stations_vintage') or 'unknown'}",
    f"- Run-day-overlay vintage: {meta.get('run_days_vintage') or 'unknown'}",
    f"- Known-truth verdict: **{meta.get('data_trust', 'unverified')}**",
    "- These commit dates do not establish current or correct schedules.",
]
for repo, details in sorted(provenance.items()):
    for path, info in sorted((details.get("files") or {}).items()):
        lines.append(f"- `{repo}/{path}` last touched: {info.get('date') or 'unknown'} "
                     f"(commit `{(info.get('commit') or '')[:12] or 'unknown'}`)")
print("\n".join(lines))
summary = os.environ.get("GITHUB_STEP_SUMMARY")
if summary:
    with open(summary, "a", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n\n")
VINTAGEEOF
