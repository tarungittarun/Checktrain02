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

## L-4 Repo baseline is empty by design (measured 2026-10-11)
Head 9a0514d "1" contains only a 2-byte file `1`. docs/, CI, code: none pre-exist.
Nothing to audit beyond this. `gh issue` #2 (open) belongs to the previous session's
round 13 and is left untouched.
