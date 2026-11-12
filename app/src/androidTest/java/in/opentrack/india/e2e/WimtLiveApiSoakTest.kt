package `in`.opentrack.india.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * Four-hour installed-APK regression, invoked only by the explicit soak workflow. Auto is enabled
 * here for the run (the shipping default remains opt-in/off). It uses the app's normal one-minute
 * selected-source refresh and stops polling after a refusal or an unqualified WIMT response, so a
 * blocked/rate-limited endpoint is not hammered. Feed freshness, missing rows and HTTP status stay
 * separate; an elapsed schedule, page check, or retained row is never counted as fresh movement.
 */
@RunWith(AndroidJUnit4::class)
class WimtLiveApiSoakTest {
    private data class Sample(
        val selected: Boolean,
        val httpStatus: Int?,
        val runMatched: Boolean,
        val routeMatched: Boolean,
        val fresh: Boolean,
        val stale: Boolean,
        val freshnessAccurate: Boolean,
        val responseClass: String,
        val parserOutcome: String,
        val responseBytes: Int?,
        val rows: Int,
        val visibleCells: Int,
        val disclosed: Boolean,
    ) {
        val qualified: Boolean
            get() = selected && httpStatus == 200 && runMatched && routeMatched && rows > 0
        val strictFresh: Boolean
            get() = qualified && fresh && visibleCells > 0 && disclosed
    }

    private fun trainForIstHour(): String = when (
        Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).get(Calendar.HOUR_OF_DAY)) {
        in 0..4 -> "16227"
        in 5..10 -> "20652"
        in 11..14 -> "12002"
        in 15..19 -> "20651"
        else -> "16227"
    }

    private fun visibleWimtSourceCellCount(): Int {
        val cells = E2E.all(By.res(E2E.PKG, "liveArr")) +
            E2E.all(By.res(E2E.PKG, "liveDep"))
        return cells.count {
            val description = it.contentDescription.orEmpty()
            description.contains("WIMT .in source-reported", true) &&
                !description.endsWith("unknown", true)
        }
    }

    /** Read the accessible selected source first; use the painted picker label as a fallback. */
    private fun selectedWimt(): Boolean {
        val spinner = E2E.find("sourceSpinner", 300) ?: return false
        val text = runCatching { spinner.text.orEmpty() }.getOrDefault("")
        val displayed = if (text.isNotBlank()) text else runCatching {
            spinner.findObject(By.clazz("android.widget.TextView"))?.text.orEmpty()
        }.getOrDefault("")
        val description = runCatching { spinner.contentDescription.orEmpty() }.getOrDefault("")
        // The selected-source description is the authoritative accessibility state;
        // a just-completed receipt may still leave the painted spinner child stale.
        if (description.startsWith("Selected ", true)) {
            return description.contains("Selected WIMT .in live-status feed", true)
        }
        if (displayed.isNotBlank()) {
            return displayed.contains("WIMT .in", true) &&
                !displayed.contains("WIMT site", true) && !displayed.contains("WIMT cache", true)
        }
        return false
    }

    private fun selectedWimtDescription(): Boolean = runCatching {
        E2E.find("sourceSpinner", 300)?.contentDescription.orEmpty()
            .contains("Selected WIMT .in live-status feed", true)
    }.getOrDefault(false)

    private fun receiptDescription(): String {
        val spinnerReceipt = runCatching {
            E2E.find("sourceSpinner", 100)?.contentDescription.orEmpty()
        }.getOrDefault("")
        if (spinnerReceipt.contains("Checked ", true)) return spinnerReceipt
        return runCatching {
            E2E.find("heroToggle", 100)?.contentDescription.orEmpty()
        }.getOrDefault("")
    }

    private fun receiptClock(): String? = Regex("""Checked (\d{2}:\d{2}:\d{2})""")
        .find(receiptDescription())?.groupValues?.get(1)

    private fun writeTrace(file: File, line: String) {
        file.appendText(line + "\n")
        android.util.Log.i("WimtLiveApiSoak", line)
    }

    /** Reads the same in-app source details a traveller can open; it never reaches into app internals. */
    private fun sourceDetails(captureTag: String? = null): String? {
        if (!selectedWimt() || !E2E.reveal("sourceSpinner", "scroller", swipes = 2)) return null
        val spinner = E2E.find("sourceSpinner", 300) ?: return null
        if (runCatching { spinner.longClick() }.isFailure) return null
        try {
            val detailsButton = E2E.ui.wait(
                Until.findObject(By.text("Source details")), E2E.LOCAL_MS) ?: return null
            detailsButton.click()
            E2E.ui.waitForIdle()
            val detailsNode = E2E.ui.wait(
                Until.findObject(By.textContains("Selected WIMT .in live-status response")),
                E2E.LOCAL_MS) ?: return null
            val text = detailsNode.text.orEmpty()
            if (captureTag != null) E2E.shotWimt(captureTag)
            return text
        } finally {
            // Pressing Source details dismisses About and opens the details dialog. One Back
            // closes that active dialog and restores tracking; a second Back would exit it.
            E2E.ui.pressBack()
            E2E.ui.waitForIdle()
        }
    }

    /** Selection and clock cells are frozen with the visible table, before opening modal details. */
    private fun sample(
        summary: String,
        details: String,
        selectedAtTableCapture: Boolean,
        visibleCellsAtTableCapture: Int,
    ): Sample {
        val status = Regex("\\bHTTP ([1-5][0-9]{2})\\b")
            .find(details)?.groupValues?.get(1)?.toIntOrNull()
        val rows = Regex("""Selected WIMT \.in source-reported rows \((\d+)\):""")
            .find(details)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val stampFresh = details.contains("fresh source stamp; not event time")
        val summaryFresh = summary.contains("upstream update stamp is fresh")
        val stale = details.contains("stale source stamp; current position unknown")
        val responseClass = when {
            status == null -> "HTTP_STATUS_UNKNOWN"
            status != 200 -> "HTTP_$status"
            rows == 0 -> "HTTP_200_NO_QUALIFIED_ROWS"
            else -> "HTTP_200_SOURCE_ROWS_PRESENT"
        }
        val parserOutcome = when {
            details.contains("response run date did not match", true) -> "RUN_DATE_MISMATCH"
            details.contains("response route endpoints did not match", true) -> "ROUTE_MISMATCH"
            details.contains("response train number did not match", true) -> "TRAIN_MISMATCH"
            details.contains("invalid json", true) -> "INVALID_JSON"
            details.contains("no schedule in wimt response", true) -> "NO_SCHEDULE_ARRAY"
            details.contains("response exceeded", true) -> "RESPONSE_TOO_LARGE"
            details.contains("requested run date was not echoed", true) -> "RUN_DATE_NOT_ECHOED"
            details.contains("route endpoints were not both echoed", true) -> "ROUTE_NOT_FULLY_ECHOED"
            rows == 0 && details.contains("source-reported actual stop", true) ->
                "PARSED_ZERO_SOURCE_ACTUAL_STOPS"
            rows == 0 -> "ZERO_QUALIFIED_SOURCE_ROWS"
            else -> "SOURCE_ROWS_PRESENT"
        }
        val responseBytes = Regex("HTTP [1-5][0-9]{2} · ([0-9]+) bytes")
            .find(details)?.groupValues?.get(1)?.toIntOrNull()
        val nonFreshLabel = listOf(
            "stale source stamp; current position unknown",
            "source stamp is ahead of device clock; freshness not established",
            "freshness unknown",
        ).any { details.contains(it) }
        val freshnessAccurate = (stampFresh && summaryFresh) ||
            (nonFreshLabel && !summaryFresh)
        return Sample(
            selected = selectedAtTableCapture,
            httpStatus = status,
            runMatched = details.contains("Run date echoed and matched: true"),
            routeMatched = details.contains("both route endpoints echoed and matched: true"),
            fresh = stampFresh && summaryFresh,
            stale = stale,
            freshnessAccurate = freshnessAccurate,
            responseClass = responseClass,
            parserOutcome = parserOutcome,
            responseBytes = responseBytes,
            rows = rows,
            visibleCells = visibleCellsAtTableCapture,
            disclosed = details.contains("not NTES actuals", true) &&
                details.contains("GPS or movement", true),
        )
    }

    private data class AutoEnableResult(
        val controlRevealed: Boolean,
        val initialChecked: Boolean?,
        val resetTap: Boolean?,
        val checkedAfterReset: Boolean?,
        val enableTap: Boolean?,
        val enableRetryTap: Boolean?,
        val checkedAfterEnable: Boolean?,
    ) {
        val enabled: Boolean
            get() = controlRevealed && initialChecked != null &&
                checkedAfterReset == false && checkedAfterEnable == true
    }

    private fun autoChecked(timeoutMs: Long = 250): Boolean? = runCatching {
        E2E.find("autoSwitch", timeoutMs)?.isChecked
    }.getOrNull()

    private fun waitForAutoState(expected: Boolean): Boolean? {
        val deadline = SystemClock.elapsedRealtime() + 3_000L
        var checked = autoChecked(100)
        while (checked != expected && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(100L)
            checked = autoChecked(100)
        }
        return checked
    }

    private fun enableAuto(): AutoEnableResult {
        val revealed = E2E.reveal("autoSwitch", "scroller")
        val initial = autoChecked()
        if (!revealed || initial == null) {
            return AutoEnableResult(revealed, initial, null, initial, null, null, initial)
        }
        var resetTap: Boolean? = null
        var afterReset: Boolean? = initial
        if (initial) {
            resetTap = E2E.tap("autoSwitch")
            afterReset = if (resetTap == true) waitForAutoState(false) else autoChecked()
            if (afterReset != false) {
                return AutoEnableResult(revealed, initial, resetTap, afterReset,
                    null, null, afterReset)
            }
        }
        val enableTap = E2E.tap("autoSwitch")
        var afterEnable = if (enableTap) waitForAutoState(true) else autoChecked()
        var retryTap: Boolean? = null
        // Retry only after the first click has settled and the accessible state is
        // still explicitly OFF; this cannot turn a successfully enabled control back OFF.
        if (afterEnable == false) {
            retryTap = E2E.tap("autoSwitch")
            afterEnable = if (retryTap == true) waitForAutoState(true) else autoChecked()
        }
        return AutoEnableResult(revealed, initial, resetTap, afterReset,
            enableTap, retryTap, afterEnable)
    }

    /** Returns true only after the opt-in toggle is observed OFF. */
    private fun disableAuto(): Boolean {
        if (!E2E.reveal("autoSwitch", "scroller")) return false
        return when (autoChecked()) {
            true -> E2E.tap("autoSwitch") && waitForAutoState(false) == false
            false -> true
            null -> false
        }
    }

    private fun receiptSuggestsFailure(description: String): Boolean = listOf(
        "check failed", "request failed", "no usable", "no validated", "refused",
        "HTTP 403", "HTTP 429", "timed out", "unavailable",
    ).any { description.contains(it, true) }

    @Test
    fun selectedWimtAutoRefreshRemainsStableFor240Minutes() {
        val durationMinutes = E2E.arg("soak_minutes", "240").toLongOrNull() ?: 0L
        assertEquals("this regression must remain exactly 240 minutes", 240L, durationMinutes)
        val durationMs = durationMinutes * 60_000L
        val evidenceDir = File(E2E.context().filesDir, "evidence")
        check(evidenceDir.mkdirs() || evidenceDir.isDirectory) {
            "Cannot create the in-app four-hour evidence directory"
        }
        val traceFile = File(evidenceDir, "wimt-soak-results.txt")
        traceFile.writeText("")

        var autoEnabled = false
        var autoOffConfirmed = false
        var soakStartedAt = 0L
        var cycles = 0
        var missedReceipts = 0
        var freshSamples = 0
        var nonFreshSamples = 0
        var unqualifiedSamples = 0
        var httpFailures = 0
        var selectedLosses = 0
        var detailSamples = 0
        var stoppedAfterSourceFailure = false
        var sourceLossRecorded = false
        var sourceFailureReason = "none"

        try {
            val number = trainForIstHour()
            E2E.launch()
            E2E.awaitSearchScreen()
            assertTrue("train mode missing", E2E.tap("modeTrain"))
            assertTrue("$number not in local search", E2E.typeInBox("trainBox", number) &&
                E2E.ui.wait(Until.hasObject(E2E.res("sugPrimary")), E2E.LOCAL_MS))
            assertTrue("could not select $number", E2E.tapSuggestion("trainBox"))
            assertTrue("Find missing", E2E.reveal("find", "searchCardScroll") && E2E.tap("find"))
            assertTrue("$number result missing", E2E.ui.wait(Until.hasObject(E2E.res("hitGo")), E2E.NETWORK_MS))
            assertTrue("Track not opened", E2E.openTrackFromFirstResult())
            assertEquals("wrong train opened", number, E2E.text("trainNo"))
            assertTrue("source selector not visible", E2E.reveal("sourceSpinner", "scroller"))
            E2E.shotWimt("wimt-soak-before")
            assertTrue("source picker cannot open", E2E.tap("sourceSpinner"))
            val source = E2E.ui.wait(
                Until.findObject(By.textContains("WIMT .in live-status feed")), E2E.LOCAL_MS)
            assertNotNull("direct WIMT .in live-status source is not selectable", source)
            source!!.click()

            val settleDeadline = SystemClock.elapsedRealtime() + E2E.NETWORK_MS +
                2 * 60_000L + 15_000L
            var summary = ""
            while (SystemClock.elapsedRealtime() < settleDeadline) {
                summary = E2E.text("sourceSummary", 500)
                val selected = selectedWimtDescription()
                if (selected && summary.contains("source-reported stop row", true) &&
                    !summary.contains("Checking", true) &&
                    !summary.contains("ahead; not counted fresh yet", true)) break
                Thread.sleep(250)
            }
            val tableDeadline = SystemClock.elapsedRealtime() + 10_000L
            while (visibleWimtSourceCellCount() == 0 &&
                SystemClock.elapsedRealtime() < tableDeadline) {
                E2E.ui.waitForIdle()
                Thread.sleep(250)
            }
            E2E.ui.waitForIdle()
            summary = E2E.text("sourceSummary", 500)
            // Freeze the *rendered table* evidence before navigating into Source details.
            // Opening/closing dialogs can replace Spinner accessibility text or table nodes;
            // acceptance must describe the same screen shown in this genuine app screenshot.
            val selectedAtTableCapture = selectedWimt()
            val cellsAtTableCapture = visibleWimtSourceCellCount()
            val summaryAtTableCapture = summary
            E2E.shotWimt("wimt-soak-start")
            writeTrace(traceFile,
                "WIMT_SOAK_TABLE_CAPTURE selected=$selectedAtTableCapture " +
                    "visibleCells=$cellsAtTableCapture hasSourceRows=" +
                    summaryAtTableCapture.contains("source-reported stop row", true))
            val initialDetails = sourceDetails("wimt-soak-start-details")
            assertNotNull("selected WIMT .in source details were not available", initialDetails)
            val initial = sample(summaryAtTableCapture, initialDetails.orEmpty(),
                selectedAtTableCapture, cellsAtTableCapture)
            detailSamples++
            if (initial.strictFresh) freshSamples++ else nonFreshSamples++
            val initialOk = initial.strictFresh &&
                summaryAtTableCapture.contains("source-reported stop row", true)
            writeTrace(traceFile,
                "WIMT_SOAK_INITIAL httpStatus=${initial.httpStatus ?: "none"} " +
                    "responseBytes=${initial.responseBytes ?: "unknown"} " +
                    "runDateMatched=${initial.runMatched} routeMatched=${initial.routeMatched} " +
                    "fresh=${initial.fresh} rows=${initial.rows} cells=${initial.visibleCells} " +
                    "responseClass=${initial.responseClass} parserOutcome=${initial.parserOutcome} " +
                    "selected=${initial.selected} disclosed=${initial.disclosed} accepted=$initialOk")
            assertTrue("strict fresh WIMT .in source-reported table was not present before the " +
                "four-hour regression: http=${initial.httpStatus} run=${initial.runMatched} " +
                "route=${initial.routeMatched} fresh=${initial.fresh} rows=${initial.rows} " +
                "cells=${initial.visibleCells} selected=${initial.selected} " +
                "disclosed=${initial.disclosed}", initialOk)

            val autoAttempt = enableAuto()
            autoEnabled = autoAttempt.checkedAfterEnable != false
            autoOffConfirmed = autoAttempt.checkedAfterEnable == false
            writeTrace(traceFile,
                "WIMT_SOAK_AUTO_ENABLE revealed=${autoAttempt.controlRevealed} " +
                    "initialChecked=${autoAttempt.initialChecked ?: "unknown"} " +
                    "resetTap=${autoAttempt.resetTap?.toString() ?: "not-needed"} " +
                    "checkedAfterReset=${autoAttempt.checkedAfterReset ?: "unknown"} " +
                    "enableTap=${autoAttempt.enableTap?.toString() ?: "not-attempted"} " +
                    "enableRetryTap=${autoAttempt.enableRetryTap?.toString() ?: "not-attempted"} " +
                    "checkedAfterEnable=${autoAttempt.checkedAfterEnable ?: "unknown"} " +
                    "enabled=${autoAttempt.enabled}")
            if (!autoAttempt.enabled) {
                runCatching { E2E.shotWimt("wimt-soak-auto-enable-failed") }
            }
            assertTrue("could not enable opt-in Auto · 1 min", autoAttempt.enabled)
            autoEnabled = true
            autoOffConfirmed = false
            soakStartedAt = SystemClock.elapsedRealtime()
            var lastReceiptClock = receiptClock()
            val seenReceiptClocks = mutableSetOf<String>()
            lastReceiptClock?.let(seenReceiptClocks::add)
            var nextExpectedSlot = 1L
            var nextExpectedReceiptDeadline = soakStartedAt + 90_000L
            var nextMilestoneMinute = 60L
            val endAt = soakStartedAt + durationMs
            writeTrace(traceFile,
                "WIMT_SOAK_STARTED durationMinutes=240 autoIntervalMinutes=1 " +
                    "initialHttpStatus=${initial.httpStatus}")

            while (SystemClock.elapsedRealtime() < endAt) {
                val now = SystemClock.elapsedRealtime()
                val currentReceipt = receiptDescription()
                val clock = Regex("""Checked (\d{2}:\d{2}:\d{2})""")
                    .find(currentReceipt)?.groupValues?.get(1)
                if (clock != null && clock != lastReceiptClock && seenReceiptClocks.add(clock)) {
                    lastReceiptClock = clock
                    cycles++
                    val isSelected = selectedWimt()
                    val elapsedMinutes = (now - soakStartedAt) / 60_000L
                    writeTrace(traceFile,
                        "WIMT_SOAK_CYCLE cycle=$cycles receiptClock=$clock " +
                            "elapsedMinutes=$elapsedMinutes selected=$isSelected " +
                            "receiptSuggestsFailure=${receiptSuggestsFailure(currentReceipt)}")

                    if (!isSelected && !sourceLossRecorded) {
                        sourceLossRecorded = true
                        selectedLosses++
                        sourceFailureReason = "SELECTED_WIMT_SOURCE_LOST"
                        writeTrace(traceFile,
                            "WIMT_SOAK_SOURCE_LOST cycle=$cycles elapsedMinutes=$elapsedMinutes")
                        val disabled = disableAuto()
                        autoEnabled = !disabled
                        autoOffConfirmed = disabled
                        stoppedAfterSourceFailure = disabled
                        writeTrace(traceFile,
                            "WIMT_SOAK_AUTO_STOP reason=$sourceFailureReason " +
                                "cycle=$cycles autoOff=$disabled")
                        if (!disabled) {
                            sourceFailureReason = "AUTO_DID_NOT_STOP_AFTER_SOURCE_LOSS"
                            break
                        }
                    } else if (isSelected &&
                        (cycles % 10 == 0 || receiptSuggestsFailure(currentReceipt))) {
                        val milestone = when (elapsedMinutes) {
                            in 55L..65L -> "60m"
                            in 115L..125L -> "120m"
                            in 175L..185L -> "180m"
                            else -> null
                        }
                        val selectedAtTableCapture = selectedWimt()
                        val cellsAtTableCapture = visibleWimtSourceCellCount()
                        val measuredSummary = E2E.text("sourceSummary", 500)
                        val details = sourceDetails(milestone?.let { "wimt-soak-$it-details" })
                        if (details == null) {
                            unqualifiedSamples++
                            if (!sourceLossRecorded) {
                                sourceLossRecorded = true
                                selectedLosses++
                            }
                            sourceFailureReason = "SELECTED_WIMT_DETAILS_UNAVAILABLE"
                            val disabled = disableAuto()
                            autoEnabled = !disabled
                            autoOffConfirmed = disabled
                            stoppedAfterSourceFailure = disabled
                            writeTrace(traceFile,
                                "WIMT_SOAK_DETAIL_UNAVAILABLE cycle=$cycles " +
                                    "elapsedMinutes=$elapsedMinutes autoOff=$disabled")
                            if (!disabled) {
                                sourceFailureReason = "AUTO_DID_NOT_STOP_AFTER_DETAILS_LOSS"
                                break
                            }
                        } else {
                            val measured = sample(measuredSummary, details,
                                selectedAtTableCapture, cellsAtTableCapture)
                            detailSamples++
                            if (measured.fresh) freshSamples++ else nonFreshSamples++
                            if (measured.httpStatus != 200 || !measured.runMatched ||
                                !measured.routeMatched || measured.rows == 0 || !measured.selected ||
                                !measured.freshnessAccurate || !measured.disclosed) {
                                unqualifiedSamples++
                            }
                            if (measured.httpStatus != 200) httpFailures++
                            writeTrace(traceFile,
                                "WIMT_SOAK_DETAIL sample=$detailSamples cycle=$cycles " +
                                    "elapsedMinutes=$elapsedMinutes " +
                                    "httpStatus=${measured.httpStatus ?: "none"} " +
                                    "runDateMatched=${measured.runMatched} " +
                                    "routeMatched=${measured.routeMatched} fresh=${measured.fresh} " +
                                    "stale=${measured.stale} freshnessAccurate=${measured.freshnessAccurate} " +
                                    "rows=${measured.rows} cells=${measured.visibleCells} " +
                                    "selected=${measured.selected} disclosed=${measured.disclosed}")
                            val responseUnqualified = measured.httpStatus != 200 ||
                                measured.rows == 0 || !measured.runMatched ||
                                !measured.routeMatched || !measured.selected ||
                                !measured.freshnessAccurate || !measured.disclosed
                            if (responseUnqualified) {
                                sourceFailureReason = when (measured.httpStatus) {
                                    403 -> "HTTP_403"
                                    429 -> "HTTP_429"
                                    null -> "NO_HTTP_RESPONSE"
                                    else -> if (measured.httpStatus in 500..599) "HTTP_5XX" else
                                        "UNQUALIFIED_SOURCE_RESPONSE"
                                }
                                val disabled = disableAuto()
                                autoEnabled = !disabled
                                autoOffConfirmed = disabled
                                stoppedAfterSourceFailure = disabled
                                writeTrace(traceFile,
                                    "WIMT_SOAK_AUTO_STOP reason=$sourceFailureReason " +
                                        "cycle=$cycles autoOff=$disabled")
                                if (!disabled) {
                                    sourceFailureReason = "AUTO_DID_NOT_STOP_AFTER_SOURCE_FAILURE"
                                    break
                                }
                            }
                        }
                    }

                }

                while (now >= nextExpectedReceiptDeadline && nextExpectedSlot <= 240L) {
                    if (cycles < nextExpectedSlot) {
                        missedReceipts++
                        writeTrace(traceFile,
                            "WIMT_SOAK_MISSED_SLOT slot=$nextExpectedSlot " +
                                "elapsedMinutes=${(now - soakStartedAt) / 60_000L}")
                    }
                    nextExpectedSlot++
                    nextExpectedReceiptDeadline = soakStartedAt +
                        nextExpectedSlot * 60_000L + 30_000L
                }
                if (now >= soakStartedAt + nextMilestoneMinute * 60_000L) {
                    E2E.shotWimt("wimt-soak-${nextMilestoneMinute}m")
                    nextMilestoneMinute += 60L
                }
                if (E2E.find("trainNo", 100) == null) {
                    sourceFailureReason = "APP_TRACKING_SCREEN_LOST"
                    writeTrace(traceFile,
                        "WIMT_SOAK_APP_SCREEN_LOST elapsedMinutes=${(now - soakStartedAt) / 60_000L}")
                    break
                }
                SystemClock.sleep(2_000L)
            }

            if (autoEnabled) {
                val disabled = disableAuto()
                autoEnabled = !disabled
                autoOffConfirmed = disabled
            }
            val elapsed = SystemClock.elapsedRealtime() - soakStartedAt
            E2E.ui.waitForIdle()
            val finalSummary = E2E.text("sourceSummary", 500)
            val finalSelectedAtTableCapture = selectedWimt()
            val finalCellsAtTableCapture = visibleWimtSourceCellCount()
            E2E.shotWimt("wimt-soak-240m")
            val finalDetails = if (finalSelectedAtTableCapture)
                sourceDetails("wimt-soak-240m-details") else null
            val finalSelected = selectedWimt()
            if (finalDetails == null) {
                unqualifiedSamples++
                writeTrace(traceFile, "WIMT_SOAK_FINAL_DETAILS_UNAVAILABLE selected=$finalSelected")
            } else {
                val final = sample(finalSummary, finalDetails,
                    finalSelectedAtTableCapture, finalCellsAtTableCapture)
                detailSamples++
                if (final.fresh) freshSamples++ else nonFreshSamples++
                if (final.httpStatus != 200 || !final.runMatched || !final.routeMatched ||
                    final.rows == 0 || !final.selected || !final.freshnessAccurate ||
                    !final.disclosed) unqualifiedSamples++
                if (final.httpStatus != 200) httpFailures++
                writeTrace(traceFile,
                    "WIMT_SOAK_DETAIL sample=$detailSamples cycle=final " +
                        "httpStatus=${final.httpStatus ?: "none"} " +
                        "runDateMatched=${final.runMatched} routeMatched=${final.routeMatched} " +
                        "fresh=${final.fresh} stale=${final.stale} " +
                        "freshnessAccurate=${final.freshnessAccurate} rows=${final.rows} " +
                        "cells=${final.visibleCells} selected=${final.selected} " +
                        "disclosed=${final.disclosed} responseClass=${final.responseClass} " +
                        "parserOutcome=${final.parserOutcome} " +
                        "responseBytes=${final.responseBytes ?: "unknown"}")
            }
            val fullDuration = elapsed >= durationMs
            val summaryLine = "WIMT_SOAK_SUMMARY durationMs=$elapsed cycles=$cycles " +
                "missedReceipts=$missedReceipts detailSamples=$detailSamples " +
                "freshSamples=$freshSamples nonFreshSamples=$nonFreshSamples " +
                "unqualifiedSamples=$unqualifiedSamples httpFailures=$httpFailures " +
                "selectedLosses=$selectedLosses autoStoppedAfterSourceFailure=$stoppedAfterSourceFailure " +
                "sourceFailureReason=$sourceFailureReason fullDuration=$fullDuration " +
                "finalSelected=$finalSelected autoOff=$autoOffConfirmed"
            writeTrace(traceFile, summaryLine)
            assertTrue("the 240-minute soak ended early: elapsedMs=$elapsed", fullDuration)
            assertTrue("fewer than 230 one-minute selected-source receipts: cycles=$cycles misses=$missedReceipts",
                cycles >= 230 && missedReceipts <= 10)
            assertEquals("the app lost the selected WIMT source or tracking screen", 0, selectedLosses)
            assertEquals("WIMT returned an HTTP failure or no qualifying run/route rows", 0,
                httpFailures + unqualifiedSamples)
            assertTrue("final WIMT source selection did not remain visible", finalSelected)
            assertTrue("Auto must be verified OFF when the soak ends", autoOffConfirmed && !autoEnabled)
            E2E.assertNoSystemDialog("WIMT four-hour Auto refresh soak")
        } finally {
            if (autoEnabled) {
                val disabled = runCatching { disableAuto() }.getOrDefault(false)
                autoEnabled = !disabled
                autoOffConfirmed = autoOffConfirmed || disabled
            }
            if (soakStartedAt > 0L) {
                val elapsed = SystemClock.elapsedRealtime() - soakStartedAt
                writeTrace(traceFile,
                    "WIMT_SOAK_FINALLY elapsedMs=$elapsed autoOff=${autoOffConfirmed && !autoEnabled}")
            }
            runCatching { E2E.shotWimt("wimt-soak-final-even-on-failure") }
        }
    }
}
