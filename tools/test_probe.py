#!/usr/bin/env python3
"""PoC-of-the-PoC for tools/probe.py (brief 7.3): the probe must ask the question it
exists to ask — a dead host must yield blocked-by-network (never "usable"), a healthy
host must yield usable, and a lone TIMEOUT must be retried exactly once before verdict.

Runs fully offline: patched socket layer, no network. Exit 0 = PASS.
"""
import unittest
import unittest.mock as mock

import probe


class FakeTLS:
    @staticmethod
    def version():
        return "TLSv1.3"

    def __enter__(self):
        return self

    def __exit__(self, *a):
        pass


class FakeSock:
    def __init__(self, behavior):
        self.behavior = behavior

    def __enter__(self):
        return self

    def __exit__(self, *a):
        pass

    def settimeout(self, t):
        pass

    def connect(self, addr):
        if self.behavior == "tcp-fail":
            raise OSError("connection refused")


class FakeResp:
    status = 200

    def __enter__(self):
        return self

    def __exit__(self, *a):
        pass

    def read(self, n):
        return b"ID~NAME~2026-10-11-7-56-55"[:n]


def patch_net(behavior):
    """Patch probe's network layer per behavior; returns list of context managers."""
    def getaddrinfo(host, port, proto=None):
        if behavior == "dns-fail":
            raise probe.socket.gaierror("name resolution failed")
        return [(2, 1, 6, "", ("127.0.0.1", port))]

    def make_socket(family, st):
        return FakeSock(behavior)

    class Ctx:
        def wrap_socket(self, sock, server_hostname=None):
            if behavior == "tls-fail":
                raise probe.ssl.SSLError(1, "[SSL: WRONG_VERSION_NUMBER] handshake reset")
            return FakeTLS()

    def urlopen(req, timeout=None):
        if behavior == "http-500":
            raise probe.urllib.error.HTTPError(req.full_url, 500, "err", {}, None)
        return FakeResp()

    return [
        mock.patch.object(probe.socket, "getaddrinfo", getaddrinfo),
        mock.patch.object(probe.socket, "socket", make_socket),
        mock.patch.object(probe.ssl, "create_default_context", Ctx),
        mock.patch.object(probe.urllib.request, "urlopen", urlopen),
    ]


class ProbeAsksItsQuestion(unittest.TestCase):
    def run_with(self, behavior):
        cms = patch_net(behavior)
        for c in cms:
            c.start()
        try:
            return probe.probe_with_retry("example.test", 443, "/x")
        finally:
            for c in cms:
                c.stop()

    def test_dead_hosts_are_blocked_never_usable(self):
        for behavior in ("tls-fail", "tcp-fail", "dns-fail", "http-500"):
            attempts, _ = self.run_with(behavior)
            v = probe.verdict(attempts)
            self.assertTrue(
                v.startswith("blocked-by-"),
                f"{behavior} must be blocked, got: {v}",
            )

    def test_healthy_host_is_usable_with_shape_unverified(self):
        attempts, body = self.run_with("ok")
        v = probe.verdict(attempts)
        self.assertTrue(v.startswith("usable"), v)
        self.assertIn("shape unverified", v)  # brief 7.4: no parser without a capture
        self.assertIsNotNone(body)

    def test_single_timeout_retries_exactly_once(self):
        # a lone timeout is not a verdict (brief 10.8): exactly one retry
        seq = {"n": 0}

        def flaky(host, port, path, timeout):
            seq["n"] += 1
            if seq["n"] == 1:
                return {"host": host, "stage": "tcp", "ok": False,
                        "error": "TIMEOUT at tcp: t", "ms": 1}, None
            return {"host": host, "stage": "http", "ok": True, "status": 200, "ms": 2}, b"x"

        with mock.patch.object(probe, "probe_once", flaky):
            attempts, _ = probe.probe_with_retry("flaky.example", 443, "/x")
        self.assertEqual(seq["n"], 2)
        self.assertTrue(probe.verdict(attempts).startswith("usable"))

    def test_two_timeouts_stay_blocked(self):
        def always_timeout(host, port, path, timeout):
            return {"host": host, "stage": "tcp", "ok": False,
                    "error": "TIMEOUT at tcp: t", "ms": 1}, None

        with mock.patch.object(probe, "probe_once", always_timeout):
            attempts, _ = probe.probe_with_retry("slow.example", 443, "/x")
        self.assertEqual(len(attempts), 2)
        self.assertIn("timeout x2", probe.verdict(attempts))


if __name__ == "__main__":
    unittest.main(verbosity=2)
