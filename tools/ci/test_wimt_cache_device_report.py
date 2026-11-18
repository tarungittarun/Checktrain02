#!/usr/bin/env python3
"""Offline tests for the redacted WIMT cache device verdict; no network or image fixtures."""
import importlib.util
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("wimt_cache_device_report",
                                               ROOT / "wimt_cache_device_report.py")
module = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(module)


def check(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> None:
    valid_screens = {
        "verified": True,
        "appPackage": "in.opentrack.india",
        "captureSource": "in-test UiAutomator screenshot plus matching app-window hierarchy",
        "acceptedDataVerifiedInAppHierarchy": True,
        "candidateRows": 2,
        "httpStatusVisibleInDetails": "HTTP 200",
        "screenshots": [
            {"name": "wimt-cache-before-selected-source.png"},
            {"name": "wimt-cache-after-selected-source.png"},
            {"name": "wimt-cache-source-details.png"},
        ],
    }
    passed = module.make_report(
        "OK (1 test)\nWIMT_CACHE_DEVICE_RESULT candidateRows=2 http200=true\n" +
        module.PASS_LINE, 0, json.dumps(valid_screens))
    check(passed["accepted"] is True and passed["candidateRows"] == 2 and
          passed["acceptedDataVerifiedInAppHierarchy"] is True and
          passed["pixelTextVerified"] is False,
          "only strict installed-APK and in-app-hierarchy evidence can pass")
    legacy_pixel_claim = module.make_report(
        "OK (1 test)\nWIMT_CACHE_DEVICE_RESULT candidateRows=2 http200=true\n" +
        module.PASS_LINE, 0,
        '{"verified": true, "acceptedDataVerifiedInPixelsAndHierarchy": true}')
    check(legacy_pixel_claim["accepted"] is False,
          "a hierarchy check must not be misrepresented as pixel-level OCR evidence")
    for output, expected in [
        ("HTTP 403", "HTTP_403_REFUSED"),
        ("HTTP 429", "HTTP_429_RATE_LIMITED"),
        ("HTTP 200\nWIMT_CACHE_DEVICE_RESULT candidateRows=0 http200=true",
         "HTTP_200_NO_QUALIFYING_ROWS"),
        ("SocketTimeoutException", "TRANSPORT_FAILED"),
    ]:
        report = module.make_report("FAILURES!!!\n" + output, 1)
        check(report["accepted"] is False and report["verdict"] == expected,
              f"{output} cannot be counted as data success")
    zero = module.make_report("OK (0 tests)", 0)
    check(zero["accepted"] is False, "zero executed tests cannot pass")
    red = module.make_report("OK (1 test)", 0)
    check(red["accepted"] is False and red["verdict"] == "STRICT_CACHE_ACCEPTANCE_NOT_PROVEN",
          "runner success without the source sentinel cannot pass")
    print("WIMT cache result-report tests passed: refusal, rate-limit, empty rows and zero tests stay red")


if __name__ == "__main__":
    main()
