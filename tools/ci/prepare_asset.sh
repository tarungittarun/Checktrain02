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
