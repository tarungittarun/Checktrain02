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
        body = open("ci-failure-report-android.txt").read()  # per-job filename (L-7)
        self.assertIn("FAILURE: Build failed", body)
        self.assertEqual(rc, 0)  # commit step "fails" (mock) -> nothing-to-commit path = 0

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

    def test_cause_extraction_beats_the_stack_trace(self):
        # run 38177487116 symptom: 120-line tail showed only stack frames, the cause was
        # just above the window. The reporter must print the 'What went wrong' block.
        log = ["line\n"] * 300
        log += ["FAILURE: Build failed with an exception.\n",
                "* What went wrong:\n",
                "A problem occurred configuring project ':app'.\n",
                "> Could not resolve resConfigs\n"]
        log += ["\tat org.gradle.stack.Frame(F%d.java:%d)\n" % (i, i) for i in range(300)]
        with open("/tmp/gradle-test2.log", "w") as f:
            f.writelines(log)
        env = {
            "GITHUB_REPOSITORY": "x/y", "GITHUB_RUN_ID": "1", "GITHUB_JOB": "android",
            "GITHUB_SHA": "abc", "GITHUB_REF_NAME": "b", "GRADLE_LOG": "/tmp/gradle-test2.log",
        }
        with mock.patch.dict(os.environ, env), \
             mock.patch.object(report_failure.subprocess, "run", return_value=subprocess.CompletedProcess([], 1)):
            report_failure.main()
        body = open("ci-failure-report-android.txt").read()
        self.assertIn("Could not resolve resConfigs", body)
        self.assertIn("gradle CAUSE", body)

    def test_compiler_e_lines_are_captured(self):
        # run 38177760988 symptom: cause showed 'Compilation error. See log' but not the
        # e: file.kt lines that actually name the problem
        log = ["some output\n"] * 50
        log += ["e: file:///work/app/src/main/java/X.kt:12:5 Unresolved reference: resConfigs\n"]
        log += ["FAILURE: Build failed with an exception.\n"]
        log += ["\tat frame\n"] * 200
        with open("/tmp/gradle-test3.log", "w") as f:
            f.writelines(log)
        env = {
            "GITHUB_REPOSITORY": "x/y", "GITHUB_RUN_ID": "1", "GITHUB_JOB": "android",
            "GITHUB_SHA": "abc", "GITHUB_REF_NAME": "b", "GRADLE_LOG": "/tmp/gradle-test3.log",
        }
        with mock.patch.dict(os.environ, env), \
             mock.patch.object(report_failure.subprocess, "run", return_value=subprocess.CompletedProcess([], 1)):
            report_failure.main()
        body = open("ci-failure-report-android.txt").read()
        self.assertIn("Unresolved reference: resConfigs", body)

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
        body = open("ci-failure-report-android.txt").read()  # per-job filename (L-7)
        self.assertIn("no gradle report files", body)


if __name__ == "__main__":
    unittest.main(verbosity=2)
