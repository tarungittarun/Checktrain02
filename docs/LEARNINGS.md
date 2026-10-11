# LEARNINGS — lesson + the artifact that enforces it (brief §13.1)

## L-1 Sandbox egress is the environment, not an obstacle (measured 2026-10-11)
TLS completes: github.com, codeload.github.com, api.github.com, registry.npmjs.org,
pypi.org, files.pythonhosted.org, AND dl.google.com, services.gradle.org,
maven.google.com, repo.maven.apache.org (TCP 443 + no handshake error observed).
TLS dies at handshake (`SSL_ERROR_SYSCALL`): enquiry.indianrail.gov.in (4 paths),
erail.in (2 paths), deb.debian.org, release-assets.githubusercontent.com.
Consequence: JVM/Android work is verified in CI (full egress); locally only Python
gates run. Enforced by: docs/DATA_SOURCES.md verdict rows; tools/probe.py reruns.

## L-2 Four JDK routes are dead in this sandbox — do not retry (measured 2026-10-11)
1. GitHub release assets: 302 → release-assets.githubusercontent.com → TLS dies.
2. deb.debian.org: TCP ok, TLS dies; also no root to install anyway.
3. PyPI: `install-jdk`/`jdk` exist but fetch JVMs from blocked hosts at runtime.
4. apt/sudo: unavailable (uid 1001, no sudo).
Consequence: §9.6 applies — local gates prove nothing about compile; CI decides.
Enforced by: STATUS.md row P1 (BLOCKED, no unblock command).

## L-3 Previous session reached APK assembly and failed there; cause unknown (2026-10-11)
Branch arena/508929e1-checktrain02, run 38175112113 (push, ~2026-10-11): job `debug APK`
failed at step "Assemble debug APK"; `tests + screens` failed at "Unit tests"; `hygiene`
failed deleting stale artifacts. Log download failed twice with EOF from
results-receiver.actions.githubusercontent.com — per §10.8 one retry was spent; cause is
recorded UNKNOWN, not guessed. The old branch also proves the pipeline shape works up to
asset generation + java/gradle setup (those steps were green). Enforced by: CI-1 rebuilds
fresh evidence instead of importing it.

## L-5 Egress rule refined: TCP-open ≠ TLS-usable (measured 2026-10-12)
services.gradle.org, dl.google.com, maven.google.com, repo.maven.apache.org accept the
TCP connect but die at the TLS handshake (SSL_ERROR_SYSCALL), same class as the railway
endpoints. Only the six allow-listed hosts complete TLS. Consequences drawn:
- Gradle distribution, AGP, SDK platforms download only inside CI runners (they have full egress).
- gradle-wrapper.jar (43,453 B, sha256 cb0da6751c2b753a16ac168bb354870ebb1e162e9083f116729cec9c781156b8)
  was taken from the owner's own previous-session branch (arena/508929e1-checktrain02) —
  recorded hash here so any future tampering is a visible diff, not a mystery.
- L-1's claim that those four build hosts were "TLS ok" was wrong at TLS depth; L-1 stands
  for the allow-listed six only. Enforced by: .github/workflows/ci.yml probe job + this row.

## L-6 Build versions pinned to the previous session's known-good set (2026-10-12)
AGP 8.5.2, Kotlin 1.9.24, Gradle 8.7, JDK 17, compileSdk/targetSdk 34, minSdk 24.
Reason: CI-1 must answer ONE question (does the pipeline assemble an APK) with ZERO
version variables. Bumps are their own ADR'd, CI-verified step later.

## L-7 erail.in ANSWERS from GitHub CI runners (measured 2026-10-12, run 38177163382)
The probe job printed `S2-erail erail.in usable (shape unverified)` while S1 timed out
x2 from the same runner. Consequence: the S2 parser + a captured fixture + regression
tests can all be built and proven in CI, entirely without the phone; the phone remains
the authority for S1 only. Do NOT parse from the ledger's memory: capture first (§7.4).
Also learned the same run: two red jobs racing one report filename lose the push race —
reports are now per-job files (tools/ci/report_failure.py); and `bytes.isprintable()`
does not exist (fixed in probe.py). Enforced by: DATA_SOURCES.md S2 row + probe job.

## L-4 Repo baseline is empty by design (measured 2026-10-11)
Head 9a0514d "1" contains only a 2-byte file `1`. docs/, CI, code: none pre-exist.
Nothing to audit beyond this. `gh issue` #2 (open) belongs to the previous session's
round 13 and is left untouched.
