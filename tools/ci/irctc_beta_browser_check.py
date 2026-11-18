#!/usr/bin/env python3
"""One real, clean Chromium search of IRCTC's public beta page.

Captures the ntesData request/response contract without recording cookies, authorization values,
raw headers, or raw response bodies. No login, challenge-solving, request replay, or retry is used.
"""
import base64
import json
import os
import re
import shutil
import sys
import time
from urllib.parse import parse_qsl, urlparse

PAGE_URL = "https://www.irctc.co.in/eticket/booking/live-train"
HOST = "www.irctc.co.in"
ENDPOINT_PATH = "/eticketing/protected/mapps1/ntesData"
SENSITIVE_KEY = re.compile(r"cookie|authorization|token|secret|password|credential|csrf|nonce|session|greq|bmirak|captcha", re.I)
PUBLIC_FIELD = re.compile(r"train|journey|date|station|status|arrival|departure|arr|dep|delay|time|update|platform|expected|eta|etd|route|code|name|distance|progress", re.I)


def is_target(url):
    try:
        parsed = urlparse(url)
        return parsed.scheme == "https" and parsed.netloc.lower() == HOST and parsed.path == ENDPOINT_PATH
    except Exception:
        return False


def shape(value, depth=3):
    if value is None:
        return "null"
    if isinstance(value, list):
        return {"type": "array", "length": len(value),
                "item": shape(value[0], depth - 1) if value and depth > 0 else "omitted"}
    if isinstance(value, dict):
        keys = [key for key in value if not SENSITIVE_KEY.search(str(key))][:60]
        if depth <= 0:
            return {"type": "object", "keys": keys}
        return {"type": "object", "fields": {key: shape(value[key], depth - 1) for key in keys}}
    return {"type": type(value).__name__, "length": len(value) if isinstance(value, str) else None}


def safe_fields(value, prefix="", depth=0, out=None):
    if out is None:
        out = {}
    if depth > 5:
        return out
    if isinstance(value, list):
        for index, item in enumerate(value[:8]):
            safe_fields(item, f"{prefix}[{index}]", depth + 1, out)
    elif isinstance(value, dict):
        for key, child in list(value.items())[:100]:
            if SENSITIVE_KEY.search(str(key)):
                continue
            path = f"{prefix}.{key}" if prefix else str(key)
            if isinstance(child, (dict, list)):
                safe_fields(child, path, depth + 1, out)
            elif PUBLIC_FIELD.search(str(key)) and isinstance(child, (str, int, float, bool)):
                text = str(child)
                out[path] = text if len(text) <= 120 else text[:117] + "..."
    return out


def parse_body(body, content_type=""):
    if not body:
        return {"format": "empty", "shape": None, "safeFields": {}}
    text = str(body)
    try:
        parsed = json.loads(text)
        return {"format": "json", "shape": shape(parsed), "safeFields": safe_fields(parsed)}
    except (ValueError, TypeError):
        pass
    if "x-www-form-urlencoded" in str(content_type).lower() or ("=" in text and "{" not in text):
        parsed = dict(parse_qsl(text, keep_blank_values=True))
        return {"format": "form", "shape": shape(parsed), "safeFields": safe_fields(parsed)}
    return {"format": content_type or "text/other", "characters": len(text), "rawSampleOmitted": True}


def safe_headers(headers):
    lowered = {str(key).lower(): value for key, value in (headers or {}).items()}
    names = sorted(lowered)
    profiles = {}
    for key in ("greq", "bmirak"):
        if key in lowered:
            value = str(lowered[key])
            profiles[key] = {"present": True, "length": len(value),
                              "uuidLike": bool(re.fullmatch(r"[0-9a-f-]{24,64}", value, re.I))}
    return {
        "names": names,
        "contentType": lowered.get("content-type"),
        "accept": lowered.get("accept"),
        "cookieHeaderPresent": bool(lowered.get("cookie")),
        "authorizationHeaderPresent": bool(lowered.get("authorization")),
        "dynamicHeaderProfiles": profiles,
    }


def self_test():
    assert is_target("https://www.irctc.co.in" + ENDPOINT_PATH)
    assert not is_target("https://attacker.example" + ENDPOINT_PATH)
    assert not is_target("https://www.irctc.co.in" + ENDPOINT_PATH + "/extra")
    req = parse_body(json.dumps({"trainNo": "12089", "journeyDate": "20261012", "authToken": "private"}), "application/json")
    encoded = json.dumps(req)
    assert "private" not in encoded and "authToken" not in encoded
    assert "12089" in encoded and "20261012" in encoded
    form = parse_body("trainNo=12089&journeyDate=20261012&csrf=private", "application/x-www-form-urlencoded")
    assert "private" not in json.dumps(form) and "csrf" not in json.dumps(form)
    headers = safe_headers({"Cookie": "private", "Authorization": "private", "Content-Type": "application/json", "greq": "abc"})
    assert "private" not in json.dumps(headers)
    assert headers["cookieHeaderPresent"] and headers["authorizationHeaderPresent"]

    class FakeDriver:
        def execute_cdp_cmd(self, command, _params):
            if command == "Network.getResponseBody":
                return {"body": json.dumps({"trainNo": "12089", "currentStation": "TKE", "accessToken": "private"}), "base64Encoded": False}
            raise AssertionError(command)

    fixture = {
        "requestId": "fixture", "url": "https://www.irctc.co.in" + ENDPOINT_PATH + "?journeyDate=20261012",
        "method": "POST", "postData": json.dumps({"trainNo": "12089", "csrf": "private"}),
        "requestHeaders": {"Cookie": "private", "Content-Type": "application/json", "greq": "dynamic-secret"},
        "response": {"status": 200, "headers": {"Content-Type": "application/json"}},
        "loadingFinished": True,
    }
    result = response_shape(fixture, FakeDriver())
    encoded = json.dumps(result)
    assert "private" not in encoded and "dynamic-secret" not in encoded
    assert "12089" in encoded and "TKE" in encoded and "20261012" in encoded
    print("IRCTC browser-check sanitizer tests passed")


def response_shape(entry, driver):
    request_id = entry["requestId"]
    post_data = entry.get("postData")
    if post_data is None and entry.get("hasPostData"):
        try:
            post_data = driver.execute_cdp_cmd("Network.getRequestPostData", {"requestId": request_id}).get("postData", "")
        except Exception:
            post_data = ""
    headers = entry.get("requestHeaders", {})
    request_url = urlparse(entry.get("url", ""))
    request = {
        "method": entry.get("method"),
        "path": ENDPOINT_PATH,
        "query": parse_body(request_url.query, "application/x-www-form-urlencoded"),
        "headers": safe_headers(headers),
        "body": parse_body(post_data or "", headers.get("Content-Type") or headers.get("content-type") or ""),
        "bodyBytes": len((post_data or "").encode("utf-8")),
    }
    response = entry.get("response", {})
    response_headers_raw = dict(response.get("headers", {}))
    response_headers_raw.update(entry.get("extraResponseHeaders", {}))
    response_body = ""
    if entry.get("loadingFinished"):
        try:
            body = driver.execute_cdp_cmd("Network.getResponseBody", {"requestId": request_id})
            response_body = base64.b64decode(body["body"]).decode("utf-8", "replace") if body.get("base64Encoded") else body.get("body", "")
        except Exception as exc:
            entry["bodyReadError"] = type(exc).__name__
    response_headers = {str(k).lower(): v for k, v in response_headers_raw.items()}
    response_summary = {
        "status": entry.get("extraResponseStatus", response.get("status")),
        "headers": {
            "names": sorted(response_headers),
            "contentType": response_headers.get("content-type"),
            "contentEncoding": response_headers.get("content-encoding"),
            "cacheControl": response_headers.get("cache-control"),
            "accessControlAllowOrigin": response_headers.get("access-control-allow-origin"),
            "accessControlAllowCredentials": response_headers.get("access-control-allow-credentials"),
        },
        "body": parse_body(response_body, response_headers.get("content-type", "")),
        "bodyBytes": len(response_body.encode("utf-8")),
        "bodyReadError": entry.get("bodyReadError"),
    }
    return {"request": request, **response_summary}


def capture(train_number):
    from selenium import webdriver
    from selenium.webdriver.chrome.options import Options
    from selenium.webdriver.common.by import By
    from selenium.webdriver.support.ui import WebDriverWait

    options = Options()
    chrome_binary = shutil.which("google-chrome") or shutil.which("chromium") or shutil.which("chromium-browser")
    if not chrome_binary:
        raise RuntimeError("Chrome/Chromium binary not installed on this runner")
    options.binary_location = chrome_binary
    options.add_argument("--headless=new")
    options.add_argument("--no-sandbox")
    options.add_argument("--disable-dev-shm-usage")
    options.add_argument("--disable-gpu")
    options.set_capability("goog:loggingPrefs", {"performance": "ALL"})
    driver = webdriver.Chrome(options=options)
    driver.set_page_load_timeout(45)
    driver.execute_cdp_cmd("Network.enable", {})
    requests = {}
    document_status = None
    page_title = ""
    page_text = ""
    form_info = []
    screenshot_path = os.environ.get("IRCTC_SCREENSHOT_PATH", "irctc-beta-page.png")
    navigation_error = None
    try:
        try:
            driver.get(PAGE_URL)
        except Exception as exc:
            navigation_error = f"{type(exc).__name__}: {str(exc)[:180]}"
        interaction_error = None
        try:
            try:
                WebDriverWait(driver, 20).until(lambda d: d.execute_script("return document.readyState") in ("interactive", "complete"))
            except Exception:
                pass
            page_title = driver.title[:160]
            form_info = driver.execute_script("""return Array.from(document.querySelectorAll('input')).slice(0,12).map(e=>({
              type:e.type||'', placeholder:(e.getAttribute('placeholder')||'').slice(0,100),
              ariaLabel:(e.getAttribute('aria-label')||'').slice(0,100), visible:!!e.getClientRects().length
            }));""")
            candidates = driver.find_elements(By.CSS_SELECTOR, "input")
            target = None
            for element in candidates:
                if not element.is_displayed():
                    continue
                label = " ".join([element.get_attribute("placeholder") or "", element.get_attribute("aria-label") or ""])
                if re.search(r"train\s*(number|name)", label, re.I):
                    target = element
                    break
            if target is None:
                target = next((e for e in candidates if e.is_displayed() and (e.get_attribute("type") or "text") in ("text", "search")), None)
            if target is None:
                raise RuntimeError("visible train search input not found")
            # Keep page bootstrap traffic separate, but include any suggestions plus the Search request.
            try:
                driver.get_log("performance")
            except Exception:
                pass
            target.clear()
            target.send_keys(train_number)
            search = driver.execute_script("""return Array.from(document.querySelectorAll('button,[role=button],input[type=submit]'))
              .find(e=>!!e.getClientRects().length && ((e.innerText||e.value||'').trim().toLowerCase()==='search')) || null;""")
            if search is None:
                raise RuntimeError("visible Search control not found")
            driver.execute_script("arguments[0].click()", search)

            def consume_events():
                nonlocal document_status
                target_event = False
                for row in driver.get_log("performance"):
                    try:
                        message = json.loads(row["message"])["message"]
                        method = message.get("method")
                        params = message.get("params", {})
                        rid = params.get("requestId")
                        if method == "Network.requestWillBeSent":
                            req = params.get("request", {})
                            url = req.get("url", "")
                            if is_target(url):
                                target_event = True
                                requests.setdefault(rid, {"requestId": rid})
                                item = requests[rid]
                                item.update({"url": url, "method": req.get("method"), "postData": req.get("postData"),
                                             "hasPostData": req.get("hasPostData", False),
                                             "requestHeaders": req.get("headers", {})})
                        elif rid in requests and method == "Network.requestWillBeSentExtraInfo":
                            target_event = True
                            requests[rid]["requestHeaders"] = params.get("headers", requests[rid].get("requestHeaders", {}))
                        elif rid in requests and method == "Network.responseReceived":
                            target_event = True
                            requests[rid]["response"] = params.get("response", {})
                        elif rid in requests and method == "Network.responseReceivedExtraInfo":
                            target_event = True
                            requests[rid]["extraResponseStatus"] = params.get("statusCode")
                            requests[rid]["extraResponseHeaders"] = params.get("headers", {})
                        elif rid in requests and method == "Network.loadingFinished":
                            target_event = True
                            requests[rid]["loadingFinished"] = True
                        elif method == "Network.responseReceived" and params.get("type") == "Document":
                            response = params.get("response", {})
                            if urlparse(response.get("url", "")).netloc.lower() == HOST:
                                document_status = response.get("status")
                    except (KeyError, TypeError, ValueError):
                        continue
                return target_event

            deadline = time.monotonic() + 30
            last_target_event = time.monotonic()
            while time.monotonic() < deadline:
                if consume_events():
                    last_target_event = time.monotonic()
                if any(item.get("loadingFinished") for item in requests.values()) and \
                        time.monotonic() - last_target_event >= 1.5:
                    break
                time.sleep(0.25)
        except Exception as exc:
            interaction_error = f"{type(exc).__name__}: {str(exc)[:180]}"
        try:
            page_title = driver.title[:160]
        except Exception:
            page_title = ""
        try:
            page_text = driver.find_element(By.TAG_NAME, "body").text[:2_000]
        except Exception:
            page_text = ""
        try:
            driver.save_screenshot(screenshot_path)
        except Exception:
            screenshot_path = None
        captured = [response_shape(item, driver) for item in requests.values()][:8]
        verdict = ("NO_NTESDATA_REQUEST_CAPTURED" if not captured else
                   "NTESDATA_POST_2XX_CAPTURED" if any(x.get("request", {}).get("method") == "POST" and
                       isinstance(x.get("status"), (int, float)) and 200 <= x["status"] < 300 for x in captured) else
                   "NTESDATA_POST_CAPTURED_NON_2XX" if any(x.get("request", {}).get("method") == "POST" for x in captured) else
                   "NTESDATA_SEEN_BUT_NO_POST")
        return {
            "checkedAtUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "sourcePage": PAGE_URL,
            "trainNumber": train_number,
            "client": "one clean headless Chrome session on GitHub Actions; no login or user cookies",
            "documentHttpStatus": document_status,
            "pageTitle": page_title,
            "visiblePageText": " ".join(page_text.split())[:2_000],
            "formInputs": form_info,
            "navigationError": navigation_error,
            "interactionError": interaction_error,
            "endpointRequests": captured,
            "screenshot": screenshot_path,
            "safety": {
                "rawRequestOrResponseBodiesSaved": False,
                "cookieOrAuthorizationValuesSaved": False,
                "headerValuesSaved": False,
                "challengeSolvingOrRequestReplay": False,
                "oneSearchOnly": True,
            },
            "verdict": verdict,
        }
    finally:
        driver.quit()


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    else:
        train_number = os.environ.get("IRCTC_TRAIN_NUMBER", "12089")
        report_path = os.environ.get("IRCTC_REPORT_PATH", "irctc-beta-result.json")
        if not re.fullmatch(r"[0-9]{5}", train_number):
            raise SystemExit("IRCTC_TRAIN_NUMBER must be exactly five digits")
        try:
            result = capture(train_number)
        except Exception as exc:
            result = {
                "checkedAtUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "sourcePage": PAGE_URL, "trainNumber": train_number,
                "verdict": "BROWSER_SETUP_OR_PAGE_FAILURE",
                "error": f"{type(exc).__name__}: {str(exc)[:220]}",
                "rawRequestOrResponseBodiesSaved": False,
            }
        with open(report_path, "w", encoding="utf-8") as out:
            json.dump(result, out, ensure_ascii=False, indent=2)
            out.write("\n")
        print(json.dumps(result, ensure_ascii=False, indent=2))
        if result.get("verdict") == "BROWSER_SETUP_OR_PAGE_FAILURE":
            raise SystemExit(2)
