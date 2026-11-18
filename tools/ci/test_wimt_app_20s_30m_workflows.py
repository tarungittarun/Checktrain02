#!/usr/bin/env python3
"""Offline guard for the four distinct fixed-train installed-app workflows."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TRAIN_WORKFLOWS = {
    "wimt-app-soak-20s-30m-16227.yml": "16227",
    "wimt-app-soak-20s-30m-20652.yml": "20652",
    "wimt-app-soak-20s-30m-12002.yml": "12002",
    "wimt-app-soak-20s-30m-20651.yml": "20651",
}
REUSABLE = ".github/workflows/wimt-app-soak-20s-30m-reusable.yml"


def main() -> int:
    seen: dict[str, str] = {}
    for filename, expected_train in TRAIN_WORKFLOWS.items():
        path = ROOT / ".github" / "workflows" / filename
        text = path.read_text(encoding="utf-8")
        assignments = re.findall(r"(?m)^\s+train_no:\s*['\"]?(\d{4,5})['\"]?\s*$", text)
        assert assignments == [expected_train], f"{filename}: expected one train_no={expected_train}, got {assignments}"
        assert f"uses: ./{REUSABLE}" in text, f"{filename}: must call the shared installed-app workflow"
        assert "workflow_dispatch:" in text, f"{filename}: explicit manual dispatch is required"
        assert "[wimt-app-soak-20s]" in text, f"{filename}: push runs require an explicit live-test marker"
        seen[expected_train] = filename
    assert len(seen) == 4 and len(set(seen)) == 4, f"train numbers must be unique: {seen}"

    reusable = (ROOT / REUSABLE).read_text(encoding="utf-8")
    assert "workflow_call:" in reusable
    assert re.search(r"(?ms)^      train_no:\n        description:.*?\n        required: true\n        type: string", reusable)
    assert "WIMT_LIVE_SOAK_MINUTES: '30'" in reusable
    assert "WIMT_LIVE_SOAK_OBSERVATION_INTERVAL_SECONDS: '20'" in reusable
    assert "WIMT_LIVE_SOAK_TRAIN_NO: ${{ inputs.train_no }}" in reusable
    assert "wimt_app_20s_30m_report.py" in reusable
    print("WIMT installed-app workflow assignments: PASS (four unique train numbers)")
    for train, filename in seen.items():
        print(f"  {filename}: train {train}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
