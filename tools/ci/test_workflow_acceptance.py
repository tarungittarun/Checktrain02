#!/usr/bin/env python3
"""Lock APK publication behind same-run installed-app and screenshot acceptance."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/android.yml"
RUNNER = ROOT / "tools/ci/run_apk_acceptance.sh"
PLAN = ROOT / "tools/ci/acceptance_plan.py"
EXPORTER = ROOT / "tools/ci/export_alert_screenshots.py"


def job_block(text: str, name: str) -> str:
    match = re.search(rf"(?m)^  {re.escape(name)}:\s*$", text)
    if not match:
        raise AssertionError(f"missing workflow job: {name}")
    end = re.search(r"(?m)^  [A-Za-z0-9_-]+:\s*$", text[match.end():])
    return text[match.start():match.end() + end.start()] if end else text[match.start():]


class SameRunPublicationGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")
        cls.runner = RUNNER.read_text(encoding="utf-8")

    def test_public_rolling_release_waits_for_all_validation_jobs(self):
        publish = job_block(self.workflow, "publish")
        self.assertIn("needs: [checks, ui, build, field-tests, traveller-acceptance]", publish)
        self.assertIn("!contains(github.event.head_commit.message, '[wimt-device]')", publish)
        traveller = job_block(self.workflow, "traveller-acceptance")
        self.assertIn("needs: [build, field-tests]", traveller)
        self.assertNotIn("needs: [build, publish", traveller)

    def test_tag_release_cannot_publish_a_different_untested_r8_apk(self):
        release = job_block(self.workflow, "release")
        self.assertIn("needs: [checks, ui, build, field-tests, traveller-acceptance]", release)
        self.assertIn("needs.traveller-acceptance.result == 'success'", release)
        self.assertIn("release variant has its own same-run install matrix", release)

    def test_install_preflight_uses_two_same_run_artifact_downloads(self):
        download = 'gh run download "$GITHUB_RUN_ID" -n opentrack-debug-apk'
        self.assertEqual(2, self.runner.count(download))
        self.assertNotIn("gh release download", self.runner)
        self.assertIn("verify_acceptance_apk.py /tmp/acceptance-apk/app-debug.apk", self.runner)
        self.assertIn("/tmp/acceptance-build/app-debug.apk \"$GITHUB_SHA\"", self.runner)
        self.assertIn('cmp "$apk" /tmp/acceptance-apk/installed.apk', self.runner)

    def test_every_partition_requires_its_original_screenshot_pairs(self):
        self.assertIn('acceptance_plan.py "${GROUP:-}" screenshots', self.runner)
        self.assertIn('acceptance-evidence/screens/$tag.$ext', self.runner)
        self.assertIn("for ext in png xml", self.runner)
        plan = PLAN.read_text(encoding="utf-8")
        self.assertIn("Mandatory screenshot tags must be unique", plan)
        exporter = EXPORTER.read_text(encoding="utf-8")
        self.assertIn('screenshot_tags("alerts")', exporter)


if __name__ == "__main__":
    unittest.main()
