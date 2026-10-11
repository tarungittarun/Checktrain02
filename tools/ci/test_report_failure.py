#!/usr/bin/env python3
"""PoC-of-the-PoC for report_failure.py (brief 7.3): the reporter must actually ask its
question — collect tails when logs exist, state absence when they don't, and never crash.
git push is stubbed; we assert the report CONTENT, not the transport.
"""
import os
import subprocess
import sys
import tempfile
import unittest
import unittest.mock as mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import report_failure


class ReporterAsksItsQuestion(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.cwd = os.getcwd()
        os.chdir(self.dir.name)

    def tearDown(self):
        os.chdir(self.cwd)
        self.dir.cleanup()

    def test_report_collects_gradle_log_tail(self):
        with open("/tmp/gradle-test.log", "w") as f:
            f.write("line1\nFAILURE: Build failed with an exception.\nwhat went wrong: X\n")
        env = {
            "GITHUB_REPOSITORY": "x/y", "GITHUB_RUN_ID": "1", "GITHUB_JOB": "android",
            "GITHUB_SHA": "abc", "GITHUB_REF_NAME": "b", "GRADLE_LOG": "/tmp/gradle-test.log",
        }
        with mock.patch.dict(os.environ, env), \
             mock.patch.object(report_failure.subprocess, "run", return_value=subprocess.CompletedProcess([], 1)):
            rc = report_failure.main()
        body = open("ci-failure-report.txt").read()
        self.assertIn("FAILURE: Build failed", body)
        self.assertEqual(rc, 0)  # push failure must not mask the report itself

    def test_loop_guard_never_pushes_duplicate_report(self):
        # a second red run on the same commit must not push again (infinite-loop guard);
        # the guard keys on the PER-JOB filename so two red jobs don't collide
        with open("ci-failure-report-android.txt", "w") as f:
            f.write("previous report\n")
        ran = []
        env = {"GITHUB_JOB": "android"}
        with mock.patch.dict(os.environ, env), \
             mock.patch.object(report_failure.subprocess, "run",
                               side_effect=lambda *a, **k: ran.append(a)):
            rc = report_failure.main()
        self.assertEqual(rc, 0)
        self.assertEqual(ran, [])  # no git commands at all

    def test_report_filename_is_per_job(self):
        with mock.patch.dict(os.environ, {"GITHUB_JOB": "probe"}):
            self.assertEqual(report_failure.report_path(), "ci-failure-report-probe.txt")

    def test_report_states_absence_when_no_logs(self):
        env = {
            "GITHUB_REPOSITORY": "x/y", "GITHUB_RUN_ID": "1", "GITHUB_JOB": "android",
            "GITHUB_SHA": "abc", "GITHUB_REF_NAME": "b", "GRADLE_LOG": "/tmp/does-not-exist.log",
        }
        with mock.patch.dict(os.environ, env), \
             mock.patch.object(report_failure.subprocess, "run", return_value=subprocess.CompletedProcess([], 1)):
            report_failure.main()
        body = open("ci-failure-report.txt").read()
        self.assertIn("no gradle report files", body)


if __name__ == "__main__":
    unittest.main(verbosity=2)
