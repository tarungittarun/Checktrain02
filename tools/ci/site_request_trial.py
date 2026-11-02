#!/usr/bin/env python3
"""One first-hop website GET matching TrackTrain's declared URL and headers.

This is an availability test from a GitHub runner, NOT a phone/browser/TLS fingerprint
simulator and NOT an access-control workaround. No cookies, spoofed browser identity,
challenge solving, redirects, retries, response body on errors or raw CDN IDs.
"""
from __future__ import annotations

import datetime as dt
import gzip
import hashlib
import json
import pathlib
import re
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass

ROOT = pathlib.Path(__file__).resolve().parents[2]
PAGE = ROOT / "app/src/main/java/in/opentrack/india/v2/mirror/PublicPageChecks.kt"
HTTP = ROOT / "app/src/main/java/in/opentrack/india/core/Http.kt"
LIMIT = 256_000  # bytes after gzip decoding; never publish the HTML
TRAIN = re.compile(r"[0-9]{5}\Z")


class ContractError(ValueError):
    pass


@dataclass(frozen=True)
class Contract:
    url_template: str
    headers: dict[str, str]
    timeout_seconds: float

    def url(self, train: str) -> str:
        if not TRAIN.fullmatch(train):
            raise ContractError("train number must be exactly five ASCII digits")
        return self.url_template.replace("$number", train)


def require(pattern: str, source: str, label: str) -> re.Match[str]:
    match = re.search(pattern, source, re.S)
    if not match:
        raise ContractError("app request contract changed: " + label)
    return match


def app_contract(page: str | None = None, http: str | None = None) -> Contract:
    """Fail closed on a drift in the app's *explicit* request settings.

    This checks source expressions rather than maintaining a second manually edited copy
    of headers. It does not claim to model Android's implicit headers or its TLS stack.
    """
    page = PAGE.read_text() if page is None else page
    http = HTTP.read_text() if http is None else http
    require(r'ORG\s*->\s*"https://whereismytrain\.org\.in/\?trainNo=\$number"',
            page, "site URL / trainNo")
    require(r'number\.matches\(Regex\("\[0-9\]\{5\}"\)\)', page, "number validation")
    require(r'internal fun retriesFor\(name: String\): Int = 0', page, "zero app retries")
    require(r'ORG\s*->\s*mapOf\("Accept" to "text/html"\)', page, "website headers")
    require(r'http\.get\(url,\s*timeoutMillis\s*=\s*4_000,\s*retries\s*=\s*retriesFor\(name\),'
            r'\s*budgetMillis\s*=\s*4_000,\s*headers\s*=\s*headersFor\(name\),'
            r'\s*allowCachedOnFailure\s*=\s*name\s*!=\s*ORG\)', page,
            "4s budget and no website failure cache")
    require(r'if \(last\.kind == Result\.Kind\.HTTP\) break', http, "no HTTP-error retry")
    require(r'!httpStatusResponse\(last\)', http, "no IPv4 retry after HTTP status")
    require(r'if \(responseStatus >= 400\)\s*\{.*?return Result\.Failed\(Result\.Kind\.HTTP, '
            r'"HTTP \$responseStatus"\)', http, "refusal handled before body")
    require(r'requestMethod\s*=\s*"GET"', http, "GET method")
    require(r'setRequestProperty\("Accept-Encoding", "gzip"\)', http, "gzip request")
    require(r'setRequestProperty\("Connection", "close"\)', http, "close request")
    ua = require(r'private const val USER_AGENT\s*=\s*"([^"\r\n]+)"', http, "app identity").group(1)
    if not ua.startswith("OpenTrackIndia/") or any(s in ua.lower() for s in ("mozilla", "chrome")):
        raise ContractError("app identity must not impersonate a browser")
    return Contract("https://whereismytrain.org.in/?trainNo=$number",
                    {"User-Agent": ua, "Accept": "text/html", "Accept-Encoding": "gzip",
                     "Connection": "close"}, 4.0)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        # Android can follow redirects; this first-hop trial intentionally cannot make
        # an uncounted second request or follow an unexpected destination.
        return None


def bounded_body(response) -> bytes:
    encoding = response.headers.get("Content-Encoding", "").lower().strip()
    if encoding == "gzip":
        with gzip.GzipFile(fileobj=response) as stream:
            body = stream.read(LIMIT + 1)
    elif encoding in ("", "identity"):
        body = response.read(LIMIT + 1)
    else:
        raise ValueError("unsupported response encoding")
    if len(body) > LIMIT:
        raise ValueError("page exceeds bounded read")
    return body


def classify(train: str, opener=None, contract: Contract | None = None) -> dict:
    contract = contract or app_contract()
    url = contract.url(train)
    opener = opener or urllib.request.build_opener(NoRedirect)
    request = urllib.request.Request(url, headers=contract.headers, method="GET")
    result = {"checkedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
              "requestedHost": "whereismytrain.org.in", "method": "GET",
              "client": "github-runner-urllib-not-android", "redirects": "disabled",
              "attempts": 1, "pageAccepted": False}
    try:
        with opener.open(request, timeout=contract.timeout_seconds) as response:
            result["httpStatus"] = response.status
            result["cdnServer"] = response.headers.get("Server", "").strip().lower() == "hcdn"
            result["cdnRequestIdPresent"] = bool(response.headers.get("x-hcdn-request-id"))
            content_type = response.headers.get_content_type().lower()
            result["contentType"] = (content_type if content_type in ("text/html", "application/json", "text/plain")
                                     else "other")
            if response.status != 200:
                result["verdict"] = "NON_200_NO_BODY_READ"
                return result
            body = bounded_body(response)
            # A 200 can be a challenge or a generic page; never call it train data.
            text = body.decode("utf-8", "replace")
            result["bytes"] = len(body)
            result["bodySha256Prefix"] = hashlib.sha256(body).hexdigest()[:16]
            if re.search(r'checking your browser before accessing|please wait for up to 5 seconds',
                         text, re.I):
                result["verdict"] = "BROWSER_CHECK_NOT_TRAIN_DATA"
            elif not re.search(rf'\b{re.escape(train)}\b', text) or not re.search(
                    r'<!--\s*Station Details Card\s*-->', text, re.I):
                result["verdict"] = "NO_MATCHED_TRAIN_STATION_CARDS"
            else:
                result["pageAccepted"] = True
                result["verdict"] = "MATCHED_PAGE_STRUCTURE_ONLY_NOT_DATED_EVENTS"
                result["stationCards"] = len(re.findall(
                    r'<!--\s*Station Details Card\s*-->', text, re.I))
    except urllib.error.HTTPError as error:
        # No error body read, even for a challenge. Do not persist the raw CDN ID.
        result["httpStatus"] = error.code
        result["cdnServer"] = error.headers.get("Server", "").strip().lower() == "hcdn"
        result["cdnRequestIdPresent"] = bool(error.headers.get("x-hcdn-request-id"))
        retry = error.headers.get("Retry-After", "").strip()
        result["retryAfterSeconds"] = (int(retry) if re.fullmatch(r"[0-9]{1,5}", retry)
                                       and int(retry) <= 86400 else None)
        result["verdict"] = "REDIRECT_NOT_FOLLOWED" if 300 <= error.code < 400 else "HTTP_REFUSED_NO_BODY_READ"
        error.close()
    except (OSError, ValueError) as error:
        result["verdict"] = "TRANSPORT_OR_BODY_ERROR"
        result["errorType"] = type(error).__name__  # no URL/IP/token from exception message
    return result


def main() -> int:
    if len(sys.argv) == 2 and sys.argv[1] == "--contract":
        contract = app_contract()
        print("App URL, method, identity, headers, retry, budget and refusal guards match trial contract")
        return 0
    if len(sys.argv) != 2:
        print("usage: site_request_trial.py --contract | TRAIN_NUMBER", file=sys.stderr)
        return 3
    try:
        result = classify(sys.argv[1])
    except ContractError as error:
        print(str(error), file=sys.stderr)
        return 3
    print(json.dumps(result, sort_keys=True))
    return 0 if result["pageAccepted"] else 2


if __name__ == "__main__":
    sys.exit(main())
