#!/usr/bin/env python3
"""Observe one current train through the direct WIMT `.in` source for 45 minutes.

This is a direct-source observation, not an installed-app or emulator test. A timetable is used
only to discover plausible run/date/route candidates. No candidate counts as live unless an ordinary
GET to whereismytrain.in/cache/live_status returns a fresh, matching run and route, a current-station
claim, and at least one parseable source-reported actual stop clock. The endpoint may be cached; its
update stamp is not event time, and this is not independent proof of movement or GPS.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, time as datetime_time, timedelta, timezone
from pathlib import Path
from typing import Any, Callable
from zoneinfo import ZoneInfo

import select_wimt_campaign_roster as roster

ENDPOINT = "https://whereismytrain.in/cache/live_status"
USER_AGENT = "TrackTrain/1.0 (CI direct WIMT .in observation)"
MAX_RESPONSE_BYTES = 1_000_000
MAX_FRESH_AGE_SECONDS = 10 * 60
FUTURE_TOLERANCE_SECONDS = 120
DURATION_MINUTES = 45
CHECKPOINT_MINUTES = 5
MAX_CANDIDATE_PROBES = 10
CANDIDATE_PROBE_GAP_SECONDS = 2.0
REQUEST_TIMEOUT_SECONDS = 8.0
IST = ZoneInfo("Asia/Kolkata")
UTC = timezone.utc


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """Do not silently change the requested `.in` source identity."""

    def redirect_request(self, req, fp, code, msg, headers, new_url):
        return None


@dataclass(frozen=True)
class SourceResponse:
    status: int | None
    body: bytes
    received_at_utc: datetime
    response_bytes: int = 0
    body_sha256: str | None = None
    transport_error: str | None = None
    too_large: bool = False


def _utc_now() -> datetime:
    return datetime.now(UTC)


def _fetch_source(candidate: dict[str, Any]) -> SourceResponse:
    """One ordinary, no-cookie, no-redirect GET. Never retries or stores raw response bodies."""
    query = urllib.parse.urlencode({
        "train_no": candidate["trainNo"],
        "date": candidate["runDate"],
        "lang": "en",
    })
    request = urllib.request.Request(
        ENDPOINT + "?" + query,
        headers={"User-Agent": USER_AGENT, "Accept": "application/json"},
        method="GET",
    )
    opener = urllib.request.build_opener(_NoRedirect())
    try:
        with opener.open(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            status = int(response.status)
            body = response.read(MAX_RESPONSE_BYTES + 1)
            received = _utc_now()
    except urllib.error.HTTPError as error:
        # A refusal or redirect is recorded by status only. Do not follow or retry it.
        return SourceResponse(int(error.code), b"", _utc_now())
    except Exception as error:
        name = type(error).__name__
        return SourceResponse(None, b"", _utc_now(), transport_error=name)

    too_large = len(body) > MAX_RESPONSE_BYTES
    digest = hashlib.sha256(body).hexdigest() if not too_large else None
    return SourceResponse(
        status=status,
        body=body if not too_large else b"",
        received_at_utc=received,
        response_bytes=len(body),
        body_sha256=digest,
        too_large=too_large,
    )


def _date_matches(value: str | None, expected: str) -> bool:
    if value is None:
        return False
    parsed = None
    for pattern in ("%d-%m-%Y", "%Y-%m-%d"):
        try:
            parsed = datetime.strptime(value.strip(), pattern).date()
            break
        except ValueError:
            continue
    try:
        requested = datetime.strptime(expected, "%d-%m-%Y").date()
    except ValueError:
        return False
    return parsed == requested


def _current_station_code(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip().upper()
    return text if re.fullmatch(r"[A-Z0-9]{2,5}", text) else None


def _truthy_flag(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value == 1
    return isinstance(value, str) and value.strip().lower() in {"true", "1", "yes"}


def _current_station(doc: dict[str, Any]) -> str | None:
    claim = roster.first_nonblank(doc, "curStn", "current_station", "currentStation")
    code = _current_station_code(claim)
    if code:
        return code
    rows = doc.get("days_schedule")
    if isinstance(rows, list):
        for row in rows:
            if isinstance(row, dict) and _truthy_flag(row.get("curStn")):
                return _current_station_code(roster.first_nonblank(row, "station_code"))
    return None


def _epoch_integer(raw: Any) -> int | None:
    if raw is None or isinstance(raw, bool):
        return None
    if isinstance(raw, (int, float)):
        try:
            return int(raw)
        except (ValueError, OverflowError):
            return None
    text = str(raw).strip()
    if not re.fullmatch(r"-?\d+", text):
        return None
    try:
        return int(text)
    except (ValueError, OverflowError):
        return None


def _event_clock_is_current(
    row: dict[str, Any], text_key: str, epoch_key: str, schedule_key: str,
    candidate: dict[str, Any], now_utc: datetime,
) -> bool:
    run_date = datetime.strptime(candidate["runDate"], "%d-%m-%Y").date()
    run_start = datetime.combine(run_date, datetime_time.min, tzinfo=IST).astimezone(UTC)
    latest = run_start + timedelta(days=4)
    latest_now = now_utc + timedelta(seconds=FUTURE_TOLERANCE_SECONDS)

    epoch = _epoch_integer(row.get(epoch_key))
    if epoch not in (None, 0):
        if epoch < 0 or epoch > 9_223_372_036_854_775:
            return False
        event_ms = epoch * 1000 if epoch < 100_000_000_000 else epoch
        try:
            event = datetime.fromtimestamp(event_ms / 1000, UTC)
        except (OverflowError, OSError, ValueError):
            return False
        return run_start <= event < latest and event <= latest_now

    clock = roster.parse_clock(row.get(text_key))
    anchor = roster.parse_clock(row.get(schedule_key))
    station = roster.station_code(roster.first_nonblank(row, "station_code"))
    if anchor is None and station == candidate.get("sourceStationCode"):
        anchor = int(candidate["scheduledDepartureMinute"])
    if anchor is None and station == candidate.get("destinationStationCode"):
        anchor = int(candidate["scheduledArrivalMinute"])
    if clock is None or anchor is None:
        return False
    minute = roster.align_clock(clock, anchor)
    event = run_start + timedelta(minutes=minute)
    return run_start <= event < latest and event <= latest_now


def _actual_stop_rows(
    doc: dict[str, Any], candidate: dict[str, Any], now_utc: datetime,
) -> int:
    rows = doc.get("days_schedule")
    if not isinstance(rows, list):
        return 0
    current = _current_station(doc)
    current_index = next((index for index, row in enumerate(rows)
                          if isinstance(row, dict) and
                          (roster.station_code(roster.first_nonblank(row, "station_code")) == current
                           or _truthy_flag(row.get("curStn")))), -1)
    actual_rows = 0
    for index, row in enumerate(rows):
        if not isinstance(row, dict) or str(row.get("source", "")).strip().lower() == "eta":
            continue
        if current_index >= 0 and index > current_index:
            continue
        has_actual = _event_clock_is_current(
            row, "actual_arrival_time", "actual_arrival_tm", "sch_arrival_time",
            candidate, now_utc,
        )
        departed = _truthy_flag(row.get("departed")) or (current_index >= 0 and index < current_index)
        if not has_actual and departed:
            has_actual = _event_clock_is_current(
                row, "actual_departure_time", "actual_departure_tm", "sch_departure_time",
                candidate, now_utc,
            )
        if has_actual:
            actual_rows += 1
    return actual_rows


def assess_response(candidate: dict[str, Any], response: SourceResponse) -> dict[str, Any]:
    """Apply the source identity, matching, freshness, current-station, and actual-clock gates."""
    now = response.received_at_utc.astimezone(UTC)
    result: dict[str, Any] = {
        "trainNo": candidate.get("trainNo"),
        "runDate": candidate.get("runDate"),
        "route": f"{candidate.get('sourceStationCode', '?')}->{candidate.get('destinationStationCode', '?')}",
        "sampledAtUtc": now.isoformat(timespec="seconds").replace("+00:00", "Z"),
        "httpStatus": response.status,
        "responseBytes": response.response_bytes,
        "responseSha256": response.body_sha256,
        "transportErrorClass": response.transport_error,
        "currentStationCodeClaim": None,
        "sourceUpdateStamp": None,
        "sourceUpdateAgeSeconds": None,
        "sourceScheduleRowCount": 0,
        "sourceActualStopRowCount": 0,
        "runDateMatched": False,
        "routeMatched": False,
        "freshSourceStamp": False,
        "currentStationClaimPresent": False,
        "protocolChecksPassed": False,
        "runningStatusQualified": False,
        "qualified": False,
        "failureReasons": [],
    }
    if response.transport_error:
        result["failureReasons"].append("transport_" + response.transport_error.lower())
        return result
    if response.status != 200:
        result["failureReasons"].append(
            f"http_{response.status}" if response.status is not None else "http_status_missing")
        return result
    if response.too_large:
        result["failureReasons"].append("response_too_large")
        return result
    try:
        doc = json.loads(response.body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        result["failureReasons"].append("invalid_json")
        return result
    if not isinstance(doc, dict):
        result["failureReasons"].append("unexpected_response_shape")
        return result

    echoed_date = roster.first_nonblank(doc, "start_date", "train_start_date")
    result["runDateMatched"] = _date_matches(echoed_date, str(candidate["runDate"]))
    source = roster.station_code(roster.first_nonblank(
        doc, "source_station_code", "source_code", "source_station"))
    destination = roster.station_code(roster.first_nonblank(
        doc, "destination_station_code", "destination_code", "destination_station"))
    result["routeMatched"] = (
        source == candidate.get("sourceStationCode") and
        destination == candidate.get("destinationStationCode")
    )

    stamp_text = roster.first_nonblank(doc, "lastUpdateIsoDate")
    if stamp_text:
        result["sourceUpdateStamp"] = stamp_text
        try:
            stamp = roster.parse_instant(stamp_text)
            age = (now - stamp).total_seconds()
            result["sourceUpdateAgeSeconds"] = round(age, 2)
            result["freshSourceStamp"] = 0 <= age <= MAX_FRESH_AGE_SECONDS
        except (ValueError, OverflowError):
            result["failureReasons"].append("source_stamp_invalid")

    rows = doc.get("days_schedule")
    if isinstance(rows, list):
        result["sourceScheduleRowCount"] = sum(isinstance(row, dict) for row in rows)
    current_station = _current_station(doc)
    result["currentStationCodeClaim"] = current_station
    result["currentStationClaimPresent"] = current_station is not None
    result["sourceActualStopRowCount"] = _actual_stop_rows(doc, candidate, now)

    try:
        protocol = roster.validate_reply(candidate, int(response.status), response.body, now)
        result["protocolChecksPassed"] = protocol.get("accepted") is True
        result["scheduleEndpointTimesMatched"] = protocol.get("scheduleEndpointTimesMatched") is True
    except roster.ProbeFailure as error:
        result["protocolFailure"] = error.code
        result["failureReasons"].append(error.code)

    if not result["runDateMatched"]:
        result["failureReasons"].append("run_date_not_matched")
    if not result["routeMatched"]:
        result["failureReasons"].append("route_not_matched")
    if not result["freshSourceStamp"] and "source_stamp_invalid" not in result["failureReasons"]:
        result["failureReasons"].append("source_stamp_not_fresh")
    if not result["currentStationClaimPresent"]:
        result["failureReasons"].append("current_station_claim_missing_or_invalid")
    if result["sourceActualStopRowCount"] <= 0:
        result["failureReasons"].append("no_current_source_reported_actual_stop_clock")

    result["runningStatusQualified"] = (
        result["runDateMatched"] and result["routeMatched"] and result["freshSourceStamp"] and
        result["currentStationClaimPresent"] and result["sourceActualStopRowCount"] > 0
    )
    result["qualified"] = (
        result["protocolChecksPassed"] and result["runningStatusQualified"]
    )
    result["failureReasons"] = sorted(set(result["failureReasons"]))
    return result


def _candidate_attempt_view(sample: dict[str, Any], candidate: dict[str, Any], index: int) -> dict[str, Any]:
    return {
        "probe": index,
        "trainNo": candidate.get("trainNo"),
        "runDate": candidate.get("runDate"),
        "route": sample.get("route"),
        "httpStatus": sample.get("httpStatus"),
        "responseBytes": sample.get("responseBytes"),
        "responseSha256": sample.get("responseSha256"),
        "runDateMatched": sample.get("runDateMatched"),
        "routeMatched": sample.get("routeMatched"),
        "freshSourceStamp": sample.get("freshSourceStamp"),
        "currentStationCodeClaim": sample.get("currentStationCodeClaim"),
        "sourceActualStopRowCount": sample.get("sourceActualStopRowCount"),
        "qualified": sample.get("qualified"),
        "failureReasons": sample.get("failureReasons", []),
    }


def observe_candidate(
    candidate: dict[str, Any],
    initial_sample: dict[str, Any],
    fetch: Callable[[dict[str, Any]], SourceResponse],
    *,
    duration_minutes: int = DURATION_MINUTES,
    interval_minutes: int = CHECKPOINT_MINUTES,
    monotonic: Callable[[], float] = time.monotonic,
    sleep: Callable[[float], None] = time.sleep,
    now: Callable[[], datetime] = _utc_now,
    on_sample: Callable[[dict[str, Any]], None] | None = None,
) -> dict[str, Any]:
    """Observe the same run at five-minute checkpoints for the full 45-minute window."""
    if initial_sample.get("qualified") is not True:
        raise ValueError("initial sample must be source-qualified before timing starts")
    start = monotonic()
    started_at = initial_sample["sampledAtUtc"]
    samples: list[dict[str, Any]] = []
    first = dict(initial_sample)
    first.update({"checkpointMinute": 0, "elapsedSeconds": 0.0})
    samples.append(first)
    if on_sample:
        on_sample(first)
    refusal_status: int | None = None
    missed_checkpoints: list[int] = []

    for minute in range(interval_minutes, duration_minutes + 1, interval_minutes):
        deadline = start + minute * 60
        delay = deadline - monotonic()
        if delay > 0:
            sleep(delay)
        # Do not generate a burst of catch-up requests if the runner stalls.
        if monotonic() > deadline + interval_minutes * 30:
            missed_checkpoints.append(minute)
            continue
        response = fetch(candidate)
        sample = assess_response(candidate, response)
        sample["checkpointMinute"] = minute
        sample["elapsedSeconds"] = round(max(0.0, monotonic() - start), 2)
        samples.append(sample)
        if on_sample:
            on_sample(sample)
        if response.status in (301, 302, 303, 307, 308, 403, 429):
            refusal_status = response.status
            break

    elapsed = max(0.0, monotonic() - start)
    expected_minutes = list(range(0, duration_minutes + 1, interval_minutes))
    recorded_minutes = [sample["checkpointMinute"] for sample in samples]
    check_points_present = recorded_minutes == expected_minutes
    duration_met = elapsed >= duration_minutes * 60
    all_qualified = bool(samples) and all(sample.get("qualified") is True for sample in samples)
    passed = duration_met and check_points_present and all_qualified and not missed_checkpoints
    return {
        "startedAtUtc": started_at,
        "endedAtUtc": now().astimezone(UTC).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "durationMs": round(elapsed * 1000),
        "durationMinutes": round(elapsed / 60, 2),
        "requiredDurationMinutes": duration_minutes,
        "checkpointIntervalMinutes": interval_minutes,
        "expectedCheckpointMinutes": expected_minutes,
        "recordedCheckpointMinutes": recorded_minutes,
        "missedCheckpointMinutes": missed_checkpoints,
        "refusalStatus": refusal_status,
        "samples": samples,
        "checks": {
            "elapsedAtLeast45Minutes": duration_met,
            "allTenFiveMinuteCheckpointsRecorded": check_points_present,
            "freshMatchedWimtInCurrentStationAndActualClockAtEveryCheckpoint": all_qualified,
            "noMissedCheckpoints": not missed_checkpoints,
            "sameTrainRunAndRouteThroughout": len({
                (item.get("trainNo"), item.get("runDate"), item.get("route"))
                for item in samples
            }) == 1,
        },
        "passed": passed,
    }


def _recent_timetable(dataset: dict[str, Any]) -> bool:
    age = dataset.get("timetableAgeDays")
    return (dataset.get("timetableFreshness") == "recent" and isinstance(age, int)
            and not isinstance(age, bool) and 0 <= age <= 90)


def _select_live_candidate(
    candidates: list[dict[str, Any]],
    fetch: Callable[[dict[str, Any]], SourceResponse],
    *,
    max_probes: int = MAX_CANDIDATE_PROBES,
    sleep: Callable[[float], None] = time.sleep,
    now: Callable[[], datetime] = _utc_now,
    on_probe: Callable[[dict[str, Any]], None] | None = None,
) -> tuple[dict[str, Any] | None, dict[str, Any] | None, list[dict[str, Any]], str | None]:
    attempts: list[dict[str, Any]] = []
    consecutive_transport = 0
    stop_reason: str | None = None
    for index, candidate in enumerate(candidates[:max_probes], start=1):
        if index > 1:
            sleep(CANDIDATE_PROBE_GAP_SECONDS)
        response = fetch(candidate)
        sample = assess_response(candidate, response)
        attempt = _candidate_attempt_view(sample, candidate, index)
        attempts.append(attempt)
        if on_probe:
            on_probe(attempt)
        if sample.get("qualified") is True:
            return candidate, sample, attempts, None
        if response.status in (301, 302, 303, 307, 308, 403, 429):
            stop_reason = f"source_refused_without_retry_http_{response.status}"
            break
        if response.transport_error or (response.status is not None and 500 <= response.status < 600):
            consecutive_transport += 1
            if consecutive_transport >= 3:
                stop_reason = "three_consecutive_transport_or_5xx_failures"
                break
        else:
            consecutive_transport = 0
    if stop_reason is None:
        if not candidates:
            stop_reason = "no_schedule_active_candidate"
        elif len(candidates) > max_probes:
            stop_reason = "candidate_probe_limit_reached"
        else:
            stop_reason = "no_candidate_met_current_wimt_source_gate"
    return None, None, attempts, stop_reason


def _summary(result: dict[str, Any]) -> str:
    observation = result.get("observation") or {}
    checks = observation.get("checks") or {}
    lines = [
        "# 45-minute direct WIMT `.in` source observation",
        "",
        f"- Verdict: **{result.get('verdict', 'NO_VERIFIED_45_MINUTE_DIRECT_WIMT_IN_OBSERVATION')}**",
        f"- Passed: **{result.get('passed', False)}**",
        "- Evidence type: **direct HTTP observation of the WIMT `.in` source; no APK, emulator, or app UI was used.**",
        "- Exact source: `https://whereismytrain.in/cache/live_status`.",
        "- This source is distinct from `.org.in`, the WIMT web-cache path, NTES, eRail, and the Google WIMT Android app.",
        "- Timetable candidates were used only to find a likely run; only a fresh, matching WIMT `.in` reply could start the timed observation.",
        f"- Train: **{result.get('trainNo') or 'not selected'}**; run date: **{result.get('runDate') or 'not selected'}**; route: **{result.get('route') or 'not selected'}**",
        f"- Elapsed after initial qualifying response: **{observation.get('durationMinutes', 0)} / 45 minutes**",
        f"- Checkpoints: **{len(observation.get('samples', []))}** at 0, 5, …, 45 minutes where recorded.",
        f"- Source selection outcome: `{result.get('selectionStopReason') or 'selected'}`",
        "",
        "## Checkpoints",
        "",
        "| Minute | HTTP | Run / route | Fresh source stamp | Current-station claim | Actual-stop rows | Qualified |",
        "| ---: | ---: | --- | --- | --- | ---: | --- |",
    ]
    for sample in observation.get("samples", []):
        lines.append(
            f"| {sample.get('checkpointMinute')} | {sample.get('httpStatus') or '—'} | "
            f"{sample.get('runDateMatched')}/{sample.get('routeMatched')} | "
            f"{sample.get('freshSourceStamp')} "
            f"({sample.get('sourceUpdateAgeSeconds') if sample.get('sourceUpdateAgeSeconds') is not None else '—'}s) | "
            f"{sample.get('currentStationCodeClaim') or '—'} | "
            f"{sample.get('sourceActualStopRowCount', 0)} | {sample.get('qualified')} |"
        )
    lines += ["", "## Acceptance checks", ""]
    for name, passed in checks.items():
        lines.append(f"- {'PASS' if passed else 'FAIL'} — `{name}`")
    if result.get("candidateAttempts"):
        lines += ["", "## Candidate preflight", ""]
        for attempt in result["candidateAttempts"]:
            lines.append(
                f"- Train {attempt.get('trainNo')} ({attempt.get('runDate')}, {attempt.get('route')}): "
                f"qualified={attempt.get('qualified')}; "
                f"HTTP={attempt.get('httpStatus')}; failure={','.join(attempt.get('failureReasons') or []) or 'none'}"
            )
    lines += [
        "",
        "## Evidence limits",
        "",
        "The qualification uses the source's echoed run date and route, its `lastUpdateIsoDate` freshness (at most ten minutes old), a source current-station field, and at least one parseable, non-ETA actual stop clock. The endpoint may be server-cached; an update stamp is not the event time. These are WIMT source claims, not independent proof of physical train movement, GPS, or railway actuals. This run is **not app testing** and contains no app screenshots or emulator evidence.",
        "",
    ]
    return "\n".join(lines)


def run_observation(
    db_path: Path,
    output_dir: Path,
    *,
    fetch: Callable[[dict[str, Any]], SourceResponse] = _fetch_source,
    now: Callable[[], datetime] = _utc_now,
    sleep: Callable[[float], None] = time.sleep,
    monotonic: Callable[[], float] = time.monotonic,
) -> dict[str, Any]:
    output_dir.mkdir(parents=True, exist_ok=True)
    result: dict[str, Any] = {
        "schemaVersion": 1,
        "mode": "direct-source-only",
        "source": "whereismytrain.in/cache/live_status",
        "sourceHost": "whereismytrain.in",
        "sourceDistinctFrom": ["Where Is My Train website (.org.in)", "WIMT web-cache path",
                               "NTES", "eRail", "Google WIMT Android app"],
        "startedAtUtc": now().astimezone(UTC).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "passed": False,
        "verdict": "NO_VERIFIED_45_MINUTE_DIRECT_WIMT_IN_OBSERVATION",
        "trainNo": None,
        "runDate": None,
        "route": None,
        "candidateAttempts": [],
        "selectionStopReason": None,
        "observation": None,
        "timetableSelectionMetadata": {},
        "independentlyVerifiedMovement": False,
        "gpsVerified": False,
    }
    trace_path = output_dir / "trace.jsonl"

    def event(record: dict[str, Any]) -> None:
        with trace_path.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(record, sort_keys=True, ensure_ascii=False) + "\n")
            stream.flush()

    db, temp = roster._open_sqlite(db_path)
    try:
        candidates, dataset = roster.scheduled_candidates(db, now().astimezone(UTC))
    finally:
        db.close()
        if temp is not None:
            temp.cleanup()
    result["timetableSelectionMetadata"] = {
        key: dataset.get(key) for key in (
            "sourceVintage", "timetableVintage", "timetableAgeDays", "timetableFreshness",
            "runDaysVintage", "dataTrust", "knownRunDayCandidates",
        )
    }
    if not _recent_timetable(dataset):
        result["selectionStopReason"] = "timetable_vintage_not_recent"
        result["timetableSelectionMetadata"]["candidateCount"] = len(candidates)
        event({"event": "selection_stopped", "reason": result["selectionStopReason"]})
    else:
        # Prefer scheduled-active trains with the longest scheduled time remaining, but never
        # treat that schedule as live evidence; the direct WIMT response is the admission gate.
        candidates.sort(key=lambda item: (
            0 if item.get("scheduleEligibility") == "scheduled-active-now" else 1,
            -float(item.get("scheduledMinutesRemainingAtSelection", 0)),
            item.get("trainNo", ""),
        ))
        result["timetableSelectionMetadata"]["candidateCount"] = len(candidates)
        candidate, initial, attempts, stop_reason = _select_live_candidate(
            candidates, fetch, sleep=sleep, now=now,
            on_probe=lambda attempt: event({"event": "candidate_probe", **attempt}),
        )
        result["candidateAttempts"] = attempts
        result["selectionStopReason"] = stop_reason
        if candidate is not None and initial is not None:
            result["trainNo"] = candidate["trainNo"]
            result["runDate"] = candidate["runDate"]
            result["route"] = f"{candidate['sourceStationCode']}->{candidate['destinationStationCode']}"
            event({"event": "observation_started", "trainNo": result["trainNo"],
                   "runDate": result["runDate"], "route": result["route"],
                   "source": result["source"]})
            observation = observe_candidate(
                candidate, initial, fetch, monotonic=monotonic, sleep=sleep, now=now,
                on_sample=lambda sample: event({"event": "observation_sample", **sample}),
            )
            result["observation"] = observation
            result["passed"] = observation["passed"] is True
            if result["passed"]:
                result["verdict"] = "PASS_45_MINUTE_DIRECT_WIMT_IN_SOURCE_OBSERVATION"
            else:
                result["selectionStopReason"] = result["selectionStopReason"] or \
                    (f"source_refused_http_{observation['refusalStatus']}"
                     if observation.get("refusalStatus") else "observation_gate_failed")
            event({"event": "observation_ended", "passed": result["passed"],
                   "durationMinutes": observation["durationMinutes"],
                   "recordedCheckpointMinutes": observation["recordedCheckpointMinutes"]})

    result["endedAtUtc"] = now().astimezone(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")
    result["reportMarkdown"] = _summary(result)
    (output_dir / "result.json").write_text(
        json.dumps(result, indent=2, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")
    (output_dir / "report.md").write_text(result["reportMarkdown"], encoding="utf-8")
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", required=True, type=Path, help="generated SQLite refdb or gzip-framed .dbgz")
    parser.add_argument("--output", required=True, type=Path, help="directory for the redacted source trace and report")
    args = parser.parse_args()
    if not args.db.is_file():
        parser.error(f"reference DB not found: {args.db}")
    result = run_observation(args.db, args.output)
    print(json.dumps({
        "passed": result["passed"],
        "verdict": result["verdict"],
        "trainNo": result["trainNo"],
        "runDate": result["runDate"],
        "route": result["route"],
        "durationMinutes": (result.get("observation") or {}).get("durationMinutes"),
        "candidateAttempts": len(result["candidateAttempts"]),
        "selectionStopReason": result["selectionStopReason"],
        "output": str(args.output),
    }, sort_keys=True))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
