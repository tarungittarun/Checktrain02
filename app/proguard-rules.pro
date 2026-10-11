# OpenTrack India v2. CI-1: nothing to keep yet (no reflection, no JNI).
# R8 is exercised by release builds in CI (brief 9.4): shrinking has broken runtime
# reflection before, so the release path stays compiled even while debug ships.
