#!/usr/bin/env python3
"""Fail closed when an acceptance test or required screenshot is omitted/duplicated."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import acceptance_plan as plan


class PlanTest(unittest.TestCase):
    def test_every_device_method_and_screenshot_has_one_owner(self):
        self.assertEqual(7, plan.check())
        self.assertEqual({"journey", "trust", "refresh", "alerts"}, set(plan.GROUPS))
        self.assertEqual(3, len(plan.screenshot_tags("journey")))
        self.assertEqual(2, len(plan.screenshot_tags("trust")))
        self.assertEqual(3, len(plan.screenshot_tags("refresh")))
        self.assertEqual(29, len(plan.screenshot_tags("alerts")))
        self.assertIn("traveller-alert-triggers", plan.screenshot_tags("alerts"))

    def test_new_unassigned_method_rejects_plan(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "Test.kt"
            source.write_text(plan.SOURCE.read_text() + "\n@Test fun newJourney() {\n" +
                              '    E2E.shotRequired("new-journey")\n}\n')
            with patch.object(plan, "SOURCE", source):
                with self.assertRaisesRegex(ValueError, "do not cover"):
                    plan.check()

    def test_duplicate_method_rejects_plan(self):
        with patch.dict(plan.GROUPS, {"refresh": plan.GROUPS["refresh"] + plan.GROUPS["trust"][:1]}):
            with self.assertRaisesRegex(ValueError, "do not cover"):
                plan.check()

    def test_duplicate_screenshot_tag_rejects_plan(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "Test.kt"
            source.write_text(plan.SOURCE.read_text() + '\nE2E.shotRequired("traveller-route")\n')
            with patch.object(plan, "SOURCE", source):
                with self.assertRaisesRegex(ValueError, "unique"):
                    plan.check()

    def test_method_without_mandatory_capture_rejects_plan(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "Test.kt"
            source.write_text(plan.SOURCE.read_text().replace(
                'E2E.shotRequired("traveller-controls")',
                'E2E.shot("traveller-controls")'))
            with patch.object(plan, "SOURCE", source):
                with self.assertRaisesRegex(ValueError, "lack mandatory screenshots"):
                    plan.check()


if __name__ == "__main__":
    unittest.main()
