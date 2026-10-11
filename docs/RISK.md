# RISK (monthly review; next review 2026-11-11)
| risk | likelihood | impact | mitigation | owner |
|------|-----------|--------|------------|-------|
| NTES envelope changes shape silently | medium | live status dies | capture-before-parse; PROTOCOL_CHANGED state; weekly signature check (§12.3) | agent |
| sandbox+CI can never reach S1/S2 | certain | phone-only verification | labelled per-location verdicts; owner paste flow (§14.3) | agent+owner |
| stale timetable asset presented as truth | medium | wrong trains shown | known-truths gate; provenance labels; S3 permanently labelled | agent |
