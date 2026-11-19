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
import java.security.MessageDigest
import java.util.Calendar
import java.util.TimeZone

/**
 * Four-hour installed-APK regression, invoked only by the explicit soak workflow. Auto is enabled
 * here for the run (the shipping default remains opt-in/off). It uses the app's normal one-minute
 * selected-source refresh and stops polling after a refusal or an unqualified WIMT response, so a
 * blocked/rate-limited endpoint is not hammered. Feed freshness, missing rows and HTTP status stay
 * separate; an elapsed schedule, page check, or retained row is never counted as fresh movement.
 * The 46-minute mode also requires the app's bounded WIMT running-status qualification: matched
 * run/route, fresh source stamp, current-station field, and at least one source-reported actual row.
 */
@RunWith(AndroidJUnit4::class)
class WimtLiveApiSoakTest {
    private data class ObservationState(
        val selected: Boolean,
        val visibleCells: Int,
        val sourceRowsVisible: Boolean,
        val summaryClass: String,
        val receiptFailure: Boolean,
        val receiptClock: String?,
    )

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
        val eventSnapshot: String?,
        val visibleCells: Int,
        val disclosed: Boolean,
        val currentStationCodeClaim: String?,
        val latestEventAgeLabel: String?,
        val runningStatusQualified: Boolean,
    ) {
        val hasCurrentStationClaim: Boolean
            get() = !currentStationCodeClaim.isNullOrBlank()

        val qualified: Boolean
            get() = selected && httpStatus == 200 && runMatched && routeMatched && rows > 0
        val strictFresh: Boolean
            get() = qualified && fresh && freshnessAccurate && visibleCells > 0 && disclosed
    }

    private fun trainForIstHour(): String = when (
        Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).get(Calendar.HOUR_OF_DAY)) {
        in 0..4 -> "16227"
        in 5..10 -> "20652"
        in 11..14 -> "12002"
        in 15..19 -> "20651"
        in 20..23 -> "20652"
        else -> "16227"
    }

    private fun visibleWimtSourceCellCount(): Int {
        // RecyclerView can replace clock nodes while the source refresh redraws rows.
        // UiAutomator then throws StaleObjectException between query and property read;
        // retry a fresh accessibility snapshot instead of aborting before evidence capture.
        repeat(3) {
            val count = runCatching {
                val cells = E2E.all(By.res(E2E.PKG, "liveArr")) +
                    E2E.all(By.res(E2E.PKG, "liveDep"))
                cells.count { node ->
                    val description = node.contentDescription.orEmpty()
                    description.contains("WIMT .in source-reported", true) &&
                        !description.endsWith("unknown", true)
                }
            }.getOrNull()
            if (count != null) return count
            SystemClock.sleep(100L)
        }
        return 0
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

    /** The traveller-facing summary says "N WIMT .in stop time(s)", not "source-reported stop row". */
    private fun hasWimtReportedStopTimes(summary: String): Boolean =
        summary.contains("WIMT .in stop time(s)", true)

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

    /** Hash only the in-app source-reported stop rows, excluding the provider update stamp. */
    private fun sourceEventSnapshot(details: String): String? {
        val marker = "Selected WIMT .in source-reported rows ("
        val start = details.indexOf(marker)
        if (start < 0) return null
        val headerEnd = details.indexOf("):", start)
        if (headerEnd < 0) return null
        val end = details.indexOf("\nThese are source-reported clocks", headerEnd)
        if (end < 0) return null
        val rows = details.substring(headerEnd + 2, end).lineSequence()
            .map { it.trim().removePrefix("•").trim() }
            .filter { it.isNotBlank() && !it.startsWith("No source stop clock", true) }
            .sorted()
            .joinToString("\n")
        if (rows.isBlank()) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(rows.toByteArray())
        return digest.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    }

    /** Returns true only when a prior actual-row snapshot existed and the event rows changed. */
    private fun recordSourceEventSnapshot(sample: Sample, known: MutableSet<String>): Boolean {
        val fingerprint = sample.eventSnapshot ?: return false
        val hadBaseline = known.isNotEmpty()
        val added = known.add(fingerprint)
        return hadBaseline && added
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
        val summaryFresh = summary.contains("source stamp fresh", true) ||
            summary.contains("upstream update stamp is fresh", true)
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
        val currentStationLine = details.lineSequence().firstOrNull {
            it.contains("current-station field claims", true)
        }.orEmpty()
        val currentStationClaim = currentStationLine
            .substringAfter("current-station field claims", "")
            .substringBefore(';')
            .trim()
        val currentStationCode = Regex("\\((\\w{2,5})\\)")
            .find(currentStationClaim)?.groupValues?.get(1)
            ?: Regex("^([A-Z0-9]{2,5})(?:\\s|$|[·;,])", RegexOption.IGNORE_CASE)
                .find(currentStationClaim)?.groupValues?.get(1)
        val latestEventAgeLabel = Regex(
            "latest stop event(?: stale)?: ([^·\\n]+)", RegexOption.IGNORE_CASE)
            .find(details)?.groupValues?.get(1)?.trim()
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
            eventSnapshot = sourceEventSnapshot(details),
            visibleCells = visibleCellsAtTableCapture,
            disclosed = details.contains("not NTES actuals", true) &&
                details.contains("GPS or movement", true),
            currentStationCodeClaim = currentStationCode?.uppercase(),
            latestEventAgeLabel = latestEventAgeLabel,
            runningStatusQualified = details.contains(
                "WIMT .in app-side running-status qualification: QUALIFIED", true),
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

    private fun sourceSummaryClass(summary: String): String = when {
        summary.isBlank() -> "EMPTY"
        summary.contains("checking", true) -> "CHECKING"
        summary.contains("stale", true) -> "STALE"
        receiptSuggestsFailure(summary) -> "FAILURE"
        summary.contains("ahead", true) || summary.contains("not established", true) ||
            summary.contains("freshness unknown", true) || summary.contains("not qualified", true) ||
            summary.contains("no source stop clock", true) || summary.contains("last-known", true) ||
            summary.contains("no dated", true) || summary.contains("not confirmed", true) ||
            summary.contains("not checked", true) -> "UNQUALIFIED"
        summary.contains("fresh", true) -> "FRESH_LABEL"
        else -> "OTHER"
    }

    /** Android system resource snapshots, queried through UiAutomator rather than app internals. */
    private fun processPssKb(): Int? = runCatching {
        val output = E2E.ui.executeShellCommand("dumpsys meminfo ${E2E.PKG}")
        Regex("(?im)^\\s*TOTAL PSS:\\s*(\\d+)")
            .find(output)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("(?m)^\\s*TOTAL\\s+(\\d+)\\s+\\d+")
                .find(output)?.groupValues?.get(1)?.toIntOrNull()
    }.getOrNull()

    private fun processCpuPercent(): Double? = runCatching {
        val output = E2E.ui.executeShellCommand("dumpsys cpuinfo")
        val packageLine = Regex("(?:^|[ /])${Regex.escape(E2E.PKG)}(?:$|[:\\s])")
        val processLine = output.lineSequence().firstOrNull { packageLine.containsMatchIn(it) }
            ?: return@runCatching null
        Regex("^\\s*([0-9]+(?:\\.[0-9]+)?)%")
            .find(processLine)?.groupValues?.get(1)?.toDoubleOrNull()
    }.getOrNull()

    private fun initialFailureReason(sample: Sample): String = when {
        sample.httpStatus == 403 -> "HTTP_403"
        sample.httpStatus == 429 -> "HTTP_429"
        sample.httpStatus == null && !sample.selected -> "WIMT_SOURCE_NOT_SELECTED_IN_APP"
        sample.httpStatus == null -> "NO_HTTP_RESPONSE"
        sample.httpStatus != 200 -> "HTTP_${sample.httpStatus}"
        !sample.runMatched -> "RUN_DATE_MISMATCH_OR_NOT_ECHOED"
        !sample.routeMatched -> "ROUTE_MISMATCH_OR_NOT_ECHOED"
        !sample.fresh && sample.stale -> "STALE_SOURCE_STAMP"
        !sample.fresh && !sample.freshnessAccurate -> "FRESHNESS_NOT_ESTABLISHED"
        !sample.fresh -> "SOURCE_STAMP_NOT_FRESH"
        sample.rows == 0 -> "NO_QUALIFIED_SOURCE_ACTUAL_ROWS"
        sample.visibleCells == 0 -> "NO_VISIBLE_WIMT_SOURCE_CELLS"
        !sample.selected -> "WIMT_SOURCE_NOT_SELECTED_IN_APP"
        !sample.hasCurrentStationClaim -> "CURRENT_STATION_CLAIM_MISSING"
        !sample.runningStatusQualified -> "RUNNING_STATUS_NOT_QUALIFIED"
        else -> "INITIAL_GATE_UNQUALIFIED"
    }

    @Test
    fun selectedWimtAppRemainsObservableEvery20SecondsFor30Minutes() {
        runSelectedWimtAutoRefreshSoak(
            requiredDurationMinutes = 30L,
            requireCurrentStation = true,
            observationIntervalSeconds = 20L,
            continuePassivelyAfterSourceFailure = true,
        )
    }

    @Test
    fun selectedWimtAppRemainsObservableEvery30SecondsFor60Minutes() {
        runSelectedWimtAutoRefreshSoak(
            requiredDurationMinutes = 60L,
            requireCurrentStation = true,
            observationIntervalSeconds = 30L,
            // Do not burn a 60-minute slot observing timetable-only UI after the
            // selected source failed the initial run/route/freshness gate.
            continuePassivelyAfterSourceFailure = false,
        )
    }

    @Test
    fun selectedWimtAutoRefreshRemainsStableFor240Minutes() {
        runSelectedWimtAutoRefreshSoak(240L, requireCurrentStation = false)
    }

    @Test
    fun selectedWimtAutoRefreshRemainsStableFor46Minutes() {
        runSelectedWimtAutoRefreshSoak(46L, requireCurrentStation = true)
    }

    private fun runSelectedWimtAutoRefreshSoak(
        requiredDurationMinutes: Long,
        requireCurrentStation: Boolean,
        observationIntervalSeconds: Long = 0L,
        continuePassivelyAfterSourceFailure: Boolean = false,
    ) {
        val durationMinutes = E2E.arg("soak_minutes", requiredDurationMinutes.toString())
            .toLongOrNull() ?: 0L
        assertEquals("instrumentation duration must match the selected soak test",
            requiredDurationMinutes, durationMinutes)
        val requestedObservationInterval = E2E.arg(
            "observation_interval_seconds", observationIntervalSeconds.toString())
            .toLongOrNull() ?: 0L
        assertEquals("instrumentation observation interval must match the selected test",
            observationIntervalSeconds, requestedObservationInterval)
        if (observationIntervalSeconds > 0L) {
            assertTrue("supported high-cadence modes are 30 minutes / 20 seconds and " +
                "60 minutes / 30 seconds",
                (requiredDurationMinutes == 30L && observationIntervalSeconds == 20L) ||
                    (requiredDurationMinutes == 60L && observationIntervalSeconds == 30L))
        }
        val durationMs = durationMinutes * 60_000L
        val observationIntervalMs = observationIntervalSeconds * 1_000L
        val expectedObservationSamples = if (observationIntervalSeconds > 0L)
            durationMs / observationIntervalMs + 1L else 0L
        val evidenceDir = File(E2E.context().filesDir, "evidence")
        check(evidenceDir.mkdirs() || evidenceDir.isDirectory) {
            "Cannot create the in-app WIMT source evidence directory"
        }
        val traceFile = File(evidenceDir, "wimt-soak-results.txt")
        traceFile.writeText("")

        var autoEnabled = false
        var autoOffConfirmed = false
        var autoStarted = false
        var initialGateAccepted = false
        var soakStartedAt = 0L
        var cycles = 0
        var missedReceipts = 0
        var freshSamples = 0
        var nonFreshSamples = 0
        var unqualifiedSamples = 0
        var httpFailures = 0
        var selectedLosses = 0
        var detailSamples = 0
        val sourceEventSnapshots = linkedSetOf<String>()
        var observationSamples = 0
        var missedObservationSlots = 0
        var maxObservationJitterMs = 0L
        val processPssSamplesKb = mutableListOf<Int>()
        val processCpuSamplesPercent = mutableListOf<Double>()
        var stoppedAfterSourceFailure = false
        var sourceLossRecorded = false
        var sourceFailureReason = "none"
        var currentStationChecksPassed = true
        var runningStatusChecksPassed = true

        fun recordObservation(sequence: Long, targetSeconds: Long): ObservationState {
            val summaryAtSample = E2E.text("sourceSummary", 250)
            val receipt = receiptDescription()
            val observedReceiptClock = Regex("""Checked (\d{2}:\d{2}:\d{2})""")
                .find(receipt)?.groupValues?.get(1)
            val selected = selectedWimt()
            val cells = visibleWimtSourceCellCount()
            val rowsVisible = hasWimtReportedStopTimes(summaryAtSample)
            val summaryClass = sourceSummaryClass(summaryAtSample)
            val receiptFailure = receiptSuggestsFailure(receipt)
            val screenVisible = E2E.find("trainNo", 100) != null
            val inForeground = runCatching { E2E.ui.currentPackageName == E2E.PKG }.getOrDefault(false)
            val autoEnabledAtSample = autoChecked() == true
            val elapsedMillis = (SystemClock.elapsedRealtime() - soakStartedAt).coerceAtLeast(0L)
            val jitter = (elapsedMillis - targetSeconds * 1_000L).coerceAtLeast(0L)
            maxObservationJitterMs = maxOf(maxObservationJitterMs, jitter)
            // Read resource metrics after recording the UI sample time so shell-command latency
            // cannot make an on-time UI observation look late (or hide real UI sampling jitter).
            val resourceSample = targetSeconds % 300L == 0L
            val pss = if (resourceSample) processPssKb() else null
            val cpuPercent = if (resourceSample) processCpuPercent() else null
            if (pss != null) processPssSamplesKb += pss
            if (cpuPercent != null) processCpuSamplesPercent += cpuPercent
            val receiptClass = when {
                receipt.isBlank() -> "NONE"
                receiptFailure -> "FAILURE"
                receipt.contains("Checked ", true) -> "CHECKED"
                else -> "OTHER"
            }
            writeTrace(traceFile,
                "WIMT_SOAK_OBSERVATION sample=$sequence targetElapsedSeconds=$targetSeconds " +
                    "elapsedSeconds=${elapsedMillis / 1_000L} jitterMs=$jitter selected=$selected " +
                    "visibleCells=$cells sourceRowsVisible=$rowsVisible summaryClass=$summaryClass " +
                    "autoEnabled=$autoEnabledAtSample receiptClock=${observedReceiptClock ?: "none"} " +
                    "receiptClass=$receiptClass trackingScreen=$screenVisible " +
                    "appForeground=$inForeground processPssKb=${pss ?: "unavailable"} " +
                    "processCpuPercent=${cpuPercent ?: "unavailable"}")
            observationSamples++
            return ObservationState(selected, cells, rowsVisible, summaryClass,
                receiptFailure, observedReceiptClock)
        }

        try {
            val number = E2E.arg("soak_train_no").trim().ifBlank { trainForIstHour() }
            assertTrue("invalid train number supplied to installed-app soak",
                Regex("\\d{4,5}").matches(number))
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
            val runDateLabel = E2E.text("runDate").replace(' ', '_')
            val routeLabel = E2E.text("routeLabel").replace(' ', '_')
            val trainSelection = if (E2E.arg("soak_train_no").isBlank())
                "IST-hour-schedule-candidate" else "workflow-assigned-train"
            writeTrace(traceFile,
                "WIMT_SOAK_TRAIN trainNo=$number runDate=$runDateLabel route=$routeLabel " +
                    "selection=$trainSelection currentStationRequired=$requireCurrentStation " +
                    "observationIntervalSeconds=$observationIntervalSeconds")
            val sourceSelectorVisible = E2E.reveal("sourceSpinner", "scroller")
            if (!continuePassivelyAfterSourceFailure) {
                assertTrue("source selector not visible", sourceSelectorVisible)
            }
            E2E.shotWimt("wimt-soak-before")
            val sourcePickerOpened = sourceSelectorVisible && E2E.tap("sourceSpinner")
            val source = if (sourcePickerOpened) E2E.ui.wait(
                Until.findObject(By.textContains("WIMT .in live-status feed")), E2E.LOCAL_MS) else null
            if (!continuePassivelyAfterSourceFailure) {
                assertNotNull("direct WIMT .in live-status source is not selectable", source)
            }
            val sourceSelectionTapped = source?.let {
                runCatching { it.click(); true }.getOrDefault(false)
            } ?: false
            if (!continuePassivelyAfterSourceFailure) {
                assertTrue("could not select the WIMT .in live-status source", sourceSelectionTapped)
            }
            writeTrace(traceFile,
                "WIMT_SOAK_SOURCE_SELECTION selectorVisible=$sourceSelectorVisible " +
                    "pickerOpened=$sourcePickerOpened optionVisible=${source != null} " +
                    "selectionTapped=$sourceSelectionTapped")

            val settleDeadline = SystemClock.elapsedRealtime() + E2E.NETWORK_MS +
                2 * 60_000L + 15_000L
            var summary = ""
            while (sourceSelectionTapped && SystemClock.elapsedRealtime() < settleDeadline) {
                summary = E2E.text("sourceSummary", 500)
                val selected = selectedWimtDescription()
                val terminalSourceFailure = continuePassivelyAfterSourceFailure &&
                    sourceSummaryClass(summary) in setOf("STALE", "FAILURE", "UNQUALIFIED")
                if (selected && !summary.contains("Checking", true) &&
                    (terminalSourceFailure ||
                        (hasWimtReportedStopTimes(summary) &&
                            !summary.contains("source stamp ahead", true) &&
                            !summary.contains("ahead; not counted fresh yet", true)))) break
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
                    hasWimtReportedStopTimes(summaryAtTableCapture))
            val initialDetails = sourceDetails("wimt-soak-start-details")
            if (!continuePassivelyAfterSourceFailure) {
                assertNotNull("selected WIMT .in source details were not available", initialDetails)
            }
            val initial = sample(summaryAtTableCapture, initialDetails.orEmpty(),
                selectedAtTableCapture, cellsAtTableCapture)
            val initialEventChanged = recordSourceEventSnapshot(initial, sourceEventSnapshots)
            detailSamples++
            if (initial.httpStatus != null && initial.httpStatus != 200) httpFailures++
            if (initial.strictFresh) freshSamples++ else nonFreshSamples++
            val initialOk = initial.strictFresh &&
                hasWimtReportedStopTimes(summaryAtTableCapture) &&
                (!requireCurrentStation || (initial.hasCurrentStationClaim &&
                    initial.runningStatusQualified))
            initialGateAccepted = initialOk
            if (!initialOk) {
                unqualifiedSamples++
                sourceFailureReason = initialFailureReason(initial)
            }
            if (requireCurrentStation && !initial.hasCurrentStationClaim) {
                currentStationChecksPassed = false
            }
            if (requireCurrentStation && !initial.runningStatusQualified) {
                runningStatusChecksPassed = false
            }
            writeTrace(traceFile,
                "WIMT_SOAK_INITIAL httpStatus=${initial.httpStatus ?: "none"} " +
                    "responseBytes=${initial.responseBytes ?: "unknown"} " +
                    "runDateMatched=${initial.runMatched} routeMatched=${initial.routeMatched} " +
                    "fresh=${initial.fresh} freshnessAccurate=${initial.freshnessAccurate} " +
                    "rows=${initial.rows} cells=${initial.visibleCells} " +
                    "responseClass=${initial.responseClass} parserOutcome=${initial.parserOutcome} " +
                    "selected=${initial.selected} disclosed=${initial.disclosed} " +
                    "currentStationClaim=${initial.currentStationCodeClaim ?: "unknown"} " +
                    "latestEventAge=${initial.latestEventAgeLabel?.replace(' ', '_') ?: "unknown"} " +
                    "wimtReportsRunning=${initial.runningStatusQualified} " +
                    "eventSnapshotPresent=${initial.eventSnapshot != null} " +
                    "eventSnapshotChanged=$initialEventChanged accepted=$initialOk " +
                    "failureReason=${if (initialOk) "none" else initialFailureReason(initial)}")
            if (!continuePassivelyAfterSourceFailure) {
                assertTrue("strict fresh WIMT .in source-reported table was not present before the " +
                    "$durationMinutes-minute regression: http=${initial.httpStatus} run=${initial.runMatched} " +
                    "route=${initial.routeMatched} fresh=${initial.fresh} rows=${initial.rows} " +
                    "cells=${initial.visibleCells} selected=${initial.selected} " +
                    "currentStation=${initial.currentStationCodeClaim ?: "unknown"} " +
                    "wimtReportsRunning=${initial.runningStatusQualified} " +
                    "disclosed=${initial.disclosed}", initialOk)
            }

            val autoAttempt = if (initialOk) enableAuto() else {
                val revealed = E2E.reveal("autoSwitch", "scroller")
                val before = autoChecked()
                val disabled = disableAuto()
                val after = autoChecked()
                AutoEnableResult(revealed, before,
                    if (before == true) disabled else null, after, null, null, after)
            }
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
                if (!continuePassivelyAfterSourceFailure) {
                    assertTrue("could not enable opt-in Auto · 1 min", autoAttempt.enabled)
                }
                val disabled = disableAuto()
                autoEnabled = !disabled
                autoOffConfirmed = disabled
                if (continuePassivelyAfterSourceFailure) {
                    assertTrue("could not verify Auto OFF before passive observation", disabled)
                    if (sourceFailureReason == "none") sourceFailureReason =
                        if (initialOk) "AUTO_ENABLE_FAILED" else initialFailureReason(initial)
                }
            } else {
                autoEnabled = true
                autoOffConfirmed = false
                autoStarted = true
            }
            soakStartedAt = SystemClock.elapsedRealtime()
            var lastReceiptClock = receiptClock()
            val seenReceiptClocks = mutableSetOf<String>()
            lastReceiptClock?.let(seenReceiptClocks::add)
            var nextExpectedSlot = 1L
            var nextExpectedReceiptDeadline = soakStartedAt + 90_000L
            val milestoneInterval = when {
                observationIntervalSeconds > 0L -> 5L
                durationMinutes <= 60L -> 15L
                else -> 60L
            }
            var nextMilestoneMinute = milestoneInterval
            val detailInterval = if (requireCurrentStation) 5 else 10
            val endAt = soakStartedAt + durationMs
            val receiptGraceEndAt = endAt + if (requireCurrentStation) 90_000L else 0L
            var nextObservationSeconds = observationIntervalSeconds
            var nextObservationSequence = 1L
            writeTrace(traceFile,
                "WIMT_SOAK_STARTED durationMinutes=$durationMinutes autoIntervalMinutes=1 " +
                    "observationIntervalSeconds=$observationIntervalSeconds " +
                    "expectedObservationSamples=$expectedObservationSamples " +
                    "requireCurrentStation=$requireCurrentStation " +
                    "runningStatusRequired=$requireCurrentStation requiredReceipts=$durationMinutes " +
                    "initialGateAccepted=$initialGateAccepted autoStarted=$autoStarted " +
                    "initialHttpStatus=${initial.httpStatus}")
            if (observationIntervalSeconds > 0L) recordObservation(0L, 0L)
            writeTrace(traceFile,
                "WIMT_SOAK_SAMPLE minute=0 elapsedSeconds=0 receiptClock=${lastReceiptClock ?: "none"} " +
                    "selected=${selectedWimt()} visibleCells=${visibleWimtSourceCellCount()} " +
                    "sourceRowsVisible=${hasWimtReportedStopTimes(E2E.text("sourceSummary", 500))} " +
                    "autoEnabled=${autoChecked() == true} receiptFailure=false " +
                    "currentStationClaim=${initial.currentStationCodeClaim ?: "unknown"} " +
                    "wimtReportsRunning=${initial.runningStatusQualified}")

            soakLoop@ while (SystemClock.elapsedRealtime() < endAt ||
                (observationIntervalSeconds > 0L && nextObservationSeconds * 1_000L <= durationMs) ||
                (requireCurrentStation && autoEnabled && cycles < durationMinutes.toInt() &&
                    SystemClock.elapsedRealtime() < receiptGraceEndAt)) {
                val now = SystemClock.elapsedRealtime()
                val elapsedForObservation = (now - soakStartedAt).coerceAtLeast(0L)
                if (observationIntervalSeconds > 0L &&
                    nextObservationSeconds * 1_000L <= durationMs &&
                    elapsedForObservation >= nextObservationSeconds * 1_000L) {
                    while (nextObservationSeconds * 1_000L <= durationMs &&
                        elapsedForObservation >= nextObservationSeconds * 1_000L + observationIntervalMs) {
                        missedObservationSlots++
                        writeTrace(traceFile,
                            "WIMT_SOAK_MISSED_OBSERVATION targetElapsedSeconds=$nextObservationSeconds " +
                                "elapsedSeconds=${elapsedForObservation / 1_000L}")
                        nextObservationSeconds += observationIntervalSeconds
                        nextObservationSequence++
                    }
                    if (nextObservationSeconds * 1_000L <= durationMs) {
                        val observation = recordObservation(nextObservationSequence,
                            nextObservationSeconds)
                        val newReceiptFailed = observation.receiptFailure &&
                            observation.receiptClock != null && observation.receiptClock != lastReceiptClock
                        if (continuePassivelyAfterSourceFailure && autoEnabled &&
                            (!observation.selected || newReceiptFailed ||
                                observation.summaryClass in setOf("STALE", "FAILURE", "UNQUALIFIED") ||
                                (observation.visibleCells == 0 && !observation.sourceRowsVisible &&
                                    observation.summaryClass == "OTHER"))) {
                            if (!observation.selected && !sourceLossRecorded) {
                                sourceLossRecorded = true
                                selectedLosses++
                            }
                            sourceFailureReason = when {
                                !observation.selected -> "SELECTED_WIMT_SOURCE_LOST"
                                newReceiptFailed -> "SELECTED_SOURCE_REFRESH_REPORTED_FAILURE"
                                else -> "UNQUALIFIED_SOURCE_RESPONSE"
                            }
                            unqualifiedSamples++
                            val disabled = disableAuto()
                            autoEnabled = !disabled
                            autoOffConfirmed = disabled
                            stoppedAfterSourceFailure = disabled
                            writeTrace(traceFile,
                                "WIMT_SOAK_AUTO_STOP reason=$sourceFailureReason " +
                                    "observationSample=$nextObservationSequence autoOff=$disabled")
                            if (!disabled) {
                                sourceFailureReason = "AUTO_DID_NOT_STOP_AFTER_OBSERVATION_FAILURE"
                                break
                            }
                        }
                        nextObservationSeconds += observationIntervalSeconds
                        nextObservationSequence++
                    }
                }
                val currentReceipt = receiptDescription()
                val clock = Regex("""Checked (\d{2}:\d{2}:\d{2})""")
                    .find(currentReceipt)?.groupValues?.get(1)
                if (autoStarted && clock != null && clock != lastReceiptClock && seenReceiptClocks.add(clock)) {
                    lastReceiptClock = clock
                    cycles++
                    E2E.ui.waitForIdle()
                    val isSelected = selectedWimt()
                    val elapsedMillis = (SystemClock.elapsedRealtime() - soakStartedAt).coerceAtLeast(0L)
                    val elapsedMinutes = elapsedMillis / 60_000L
                    val receiptFailed = receiptSuggestsFailure(currentReceipt)
                    val cycleSummary = E2E.text("sourceSummary", 500)
                    val cycleCells = visibleWimtSourceCellCount()
                    val cycleRowsVisible = hasWimtReportedStopTimes(cycleSummary)
                    val cycleAutoOn = autoChecked() == true
                    writeTrace(traceFile,
                        "WIMT_SOAK_CYCLE cycle=$cycles receiptClock=$clock " +
                            "elapsedSeconds=${elapsedMillis / 1_000L} elapsedMinutes=$elapsedMinutes " +
                            "selected=$isSelected " +
                            "receiptSuggestsFailure=$receiptFailed")
                    writeTrace(traceFile,
                        "WIMT_SOAK_SAMPLE minute=$cycles elapsedSeconds=${elapsedMillis / 1_000L} " +
                            "receiptClock=$clock selected=$isSelected visibleCells=$cycleCells " +
                            "sourceRowsVisible=$cycleRowsVisible autoEnabled=$cycleAutoOn " +
                            "receiptFailure=$receiptFailed")
                    if (requireCurrentStation && autoEnabled && isSelected &&
                        (receiptFailed || !cycleAutoOn)) {
                        unqualifiedSamples++
                        sourceFailureReason = if (receiptFailed)
                            "SELECTED_SOURCE_REFRESH_REPORTED_FAILURE" else "AUTO_DISABLED_DURING_OBSERVATION"
                        val disabled = disableAuto()
                        autoEnabled = !disabled
                        autoOffConfirmed = disabled
                        stoppedAfterSourceFailure = disabled
                        writeTrace(traceFile,
                            "WIMT_SOAK_AUTO_STOP reason=$sourceFailureReason " +
                                "cycle=$cycles autoOff=$disabled")
                        if (!disabled) {
                            sourceFailureReason = if (continuePassivelyAfterSourceFailure)
                                "AUTO_DID_NOT_STOP_AFTER_SOURCE_FAILURE" else
                                "AUTO_DID_NOT_STOP_AFTER_MINUTE_FAILURE"
                            break
                        }
                        if (!continuePassivelyAfterSourceFailure) break
                    }

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
                        if (requireCurrentStation && !continuePassivelyAfterSourceFailure) break
                    } else if (isSelected &&
                        (cycles % detailInterval == 0 || receiptFailed)) {
                        val milestone = if (durationMinutes <= 60L) when (elapsedMinutes) {
                            in 14L..16L -> "15m"
                            in 29L..31L -> "30m"
                            in 44L..46L -> "45m"
                            else -> null
                        } else when (elapsedMinutes) {
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
                            if (requireCurrentStation && !continuePassivelyAfterSourceFailure) break
                        } else {
                            val measured = sample(measuredSummary, details,
                                selectedAtTableCapture, cellsAtTableCapture)
                            val eventSnapshotChanged = recordSourceEventSnapshot(
                                measured, sourceEventSnapshots)
                            detailSamples++
                            if (measured.fresh) freshSamples++ else nonFreshSamples++
                            val currentStationMissing = requireCurrentStation &&
                                !measured.hasCurrentStationClaim
                            val runningStatusMissing = requireCurrentStation &&
                                !measured.runningStatusQualified
                            if (currentStationMissing) currentStationChecksPassed = false
                            if (runningStatusMissing) runningStatusChecksPassed = false
                            if (measured.httpStatus != 200 || !measured.runMatched ||
                                !measured.routeMatched || measured.rows == 0 || !measured.selected ||
                                !measured.fresh || !measured.freshnessAccurate || !measured.disclosed ||
                                currentStationMissing || runningStatusMissing) {
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
                                    "selected=${measured.selected} disclosed=${measured.disclosed} " +
                                    "currentStationClaim=${measured.currentStationCodeClaim ?: "unknown"} " +
                                    "latestEventAge=${measured.latestEventAgeLabel?.replace(' ', '_') ?: "unknown"} " +
                                    "wimtReportsRunning=${measured.runningStatusQualified} " +
                                    "eventSnapshotChanged=$eventSnapshotChanged " +
                                    "uniqueEventSnapshots=${sourceEventSnapshots.size} " +
                                    "responseClass=${measured.responseClass} " +
                                    "parserOutcome=${measured.parserOutcome} " +
                                    "responseBytes=${measured.responseBytes ?: "unknown"}")
                            val responseUnqualified = measured.httpStatus != 200 ||
                                measured.rows == 0 || !measured.runMatched ||
                                !measured.routeMatched || !measured.selected ||
                                !measured.fresh || !measured.freshnessAccurate || !measured.disclosed ||
                                currentStationMissing || runningStatusMissing
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
                                if (requireCurrentStation && !continuePassivelyAfterSourceFailure) break
                            }
                        }
                    }

                }

                while (autoStarted && now >= nextExpectedReceiptDeadline &&
                    nextExpectedSlot <= durationMinutes) {
                    if (cycles < nextExpectedSlot) {
                        missedReceipts++
                        writeTrace(traceFile,
                            "WIMT_SOAK_MISSED_SLOT slot=$nextExpectedSlot " +
                                "elapsedMinutes=${(now - soakStartedAt) / 60_000L}")
                        if (continuePassivelyAfterSourceFailure && autoEnabled) {
                            sourceFailureReason = "AUTO_REFRESH_RECEIPT_MISSING"
                            unqualifiedSamples++
                            val disabled = disableAuto()
                            autoEnabled = !disabled
                            autoOffConfirmed = disabled
                            stoppedAfterSourceFailure = disabled
                            writeTrace(traceFile,
                                "WIMT_SOAK_AUTO_STOP reason=AUTO_REFRESH_RECEIPT_MISSING " +
                                    "missingSlot=$nextExpectedSlot autoOff=$disabled")
                            if (!disabled) {
                                sourceFailureReason = "AUTO_DID_NOT_STOP_AFTER_SOURCE_FAILURE"
                                writeTrace(traceFile,
                                    "WIMT_SOAK_AUTO_STOP_FAILURE reason=$sourceFailureReason " +
                                        "missingSlot=$nextExpectedSlot")
                                break@soakLoop
                            }
                        }
                    }
                    nextExpectedSlot++
                    nextExpectedReceiptDeadline = soakStartedAt +
                        nextExpectedSlot * 60_000L + 30_000L
                }
                if (now >= soakStartedAt + nextMilestoneMinute * 60_000L) {
                    E2E.shotWimt("wimt-soak-${nextMilestoneMinute}m")
                    nextMilestoneMinute += milestoneInterval
                }
                if (E2E.find("trainNo", 100) == null) {
                    sourceFailureReason = "APP_TRACKING_SCREEN_LOST"
                    writeTrace(traceFile,
                        "WIMT_SOAK_APP_SCREEN_LOST elapsedMinutes=${(now - soakStartedAt) / 60_000L}")
                    break
                }
                if (observationIntervalSeconds > 0L) {
                    val untilNextObservation = nextObservationSeconds * 1_000L -
                        (SystemClock.elapsedRealtime() - soakStartedAt)
                    SystemClock.sleep(untilNextObservation.coerceIn(100L, 1_000L))
                } else {
                    SystemClock.sleep(2_000L)
                }
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
            E2E.shotWimt("wimt-soak-${durationMinutes}m")
            val finalDetails = if (finalSelectedAtTableCapture)
                sourceDetails("wimt-soak-${durationMinutes}m-details") else null
            val finalSelected = selectedWimt()
            if (continuePassivelyAfterSourceFailure && sourceFailureReason != "none") {
                val passiveDetails = finalDetails != null
                writeTrace(traceFile,
                    "WIMT_SOAK_PASSIVE_FINAL sourceFailureReason=$sourceFailureReason " +
                        "selected=$finalSelected sourceDetailsCaptured=$passiveDetails " +
                        "appRemainedOpen=${E2E.find("trainNo", 100) != null}")
            }
            if (finalDetails == null) {
                unqualifiedSamples++
                if (requireCurrentStation) {
                    currentStationChecksPassed = false
                    runningStatusChecksPassed = false
                }
                writeTrace(traceFile, "WIMT_SOAK_FINAL_DETAILS_UNAVAILABLE selected=$finalSelected")
            } else {
                val final = sample(finalSummary, finalDetails,
                    finalSelectedAtTableCapture, finalCellsAtTableCapture)
                val finalEventChanged = recordSourceEventSnapshot(final, sourceEventSnapshots)
                detailSamples++
                if (final.fresh) freshSamples++ else nonFreshSamples++
                val finalCurrentStationMissing = requireCurrentStation &&
                    !final.hasCurrentStationClaim
                val finalRunningStatusMissing = requireCurrentStation &&
                    !final.runningStatusQualified
                if (finalCurrentStationMissing) currentStationChecksPassed = false
                if (finalRunningStatusMissing) runningStatusChecksPassed = false
                if (final.httpStatus != 200 || !final.runMatched || !final.routeMatched ||
                    final.rows == 0 || !final.selected || !final.fresh ||
                    !final.freshnessAccurate || !final.disclosed ||
                    finalCurrentStationMissing || finalRunningStatusMissing)
                    unqualifiedSamples++
                if (final.httpStatus != 200) httpFailures++
                writeTrace(traceFile,
                    "WIMT_SOAK_DETAIL sample=$detailSamples cycle=final " +
                        "httpStatus=${final.httpStatus ?: "none"} " +
                        "runDateMatched=${final.runMatched} routeMatched=${final.routeMatched} " +
                        "fresh=${final.fresh} stale=${final.stale} " +
                        "freshnessAccurate=${final.freshnessAccurate} rows=${final.rows} " +
                        "cells=${final.visibleCells} selected=${final.selected} " +
                        "disclosed=${final.disclosed} " +
                        "currentStationClaim=${final.currentStationCodeClaim ?: "unknown"} " +
                        "latestEventAge=${final.latestEventAgeLabel?.replace(' ', '_') ?: "unknown"} " +
                        "wimtReportsRunning=${final.runningStatusQualified} " +
                        "eventSnapshotChanged=$finalEventChanged " +
                        "uniqueEventSnapshots=${sourceEventSnapshots.size} " +
                        "responseClass=${final.responseClass} parserOutcome=${final.parserOutcome} " +
                        "responseBytes=${final.responseBytes ?: "unknown"}")
            }
            val fullDuration = elapsed >= durationMs
            val requiredReceipts = if (requireCurrentStation) durationMinutes.toInt() else 230
            val allowedMissedReceipts = if (requireCurrentStation) 0 else 10
            val pssMin = processPssSamplesKb.minOrNull()
            val pssMax = processPssSamplesKb.maxOrNull()
            val pssFirst = processPssSamplesKb.firstOrNull()
            val pssLast = processPssSamplesKb.lastOrNull()
            val cpuMin = processCpuSamplesPercent.minOrNull()
            val cpuMax = processCpuSamplesPercent.maxOrNull()
            val cpuFirst = processCpuSamplesPercent.firstOrNull()
            val cpuLast = processCpuSamplesPercent.lastOrNull()
            val summaryLine = "WIMT_SOAK_SUMMARY durationMs=$elapsed cycles=$cycles " +
                "requiredReceipts=$requiredReceipts missedReceipts=$missedReceipts " +
                "detailSamples=$detailSamples freshSamples=$freshSamples " +
                "nonFreshSamples=$nonFreshSamples unqualifiedSamples=$unqualifiedSamples " +
                "httpFailures=$httpFailures selectedLosses=$selectedLosses " +
                "autoStoppedAfterSourceFailure=$stoppedAfterSourceFailure " +
                "sourceFailureReason=$sourceFailureReason fullDuration=$fullDuration " +
                "finalSelected=$finalSelected autoOff=$autoOffConfirmed " +
                "currentStationRequired=$requireCurrentStation " +
                "currentStationChecksPassed=$currentStationChecksPassed " +
                "runningStatusRequired=$requireCurrentStation " +
                "wimtRunningStatusChecksPassed=$runningStatusChecksPassed " +
                "initialGateAccepted=$initialGateAccepted autoStarted=$autoStarted " +
                "uniqueSourceEventSnapshots=${sourceEventSnapshots.size} " +
                "observationIntervalSeconds=$observationIntervalSeconds " +
                "observationSamples=$observationSamples " +
                "expectedObservationSamples=$expectedObservationSamples " +
                "missedObservationSlots=$missedObservationSlots " +
                "maxObservationJitterMs=$maxObservationJitterMs " +
                "processPssSampleCount=${processPssSamplesKb.size} " +
                "processPssMinKb=${pssMin ?: "unknown"} " +
                "processPssMaxKb=${pssMax ?: "unknown"} " +
                "processPssFirstKb=${pssFirst ?: "unknown"} " +
                "processPssLastKb=${pssLast ?: "unknown"} " +
                "processCpuSampleCount=${processCpuSamplesPercent.size} " +
                "processCpuMinPercent=${cpuMin ?: "unknown"} " +
                "processCpuMaxPercent=${cpuMax ?: "unknown"} " +
                "processCpuFirstPercent=${cpuFirst ?: "unknown"} " +
                "processCpuLastPercent=${cpuLast ?: "unknown"}"
            writeTrace(traceFile, summaryLine)
            assertTrue("the $durationMinutes-minute soak ended early: elapsedMs=$elapsed", fullDuration)
            if (observationIntervalSeconds > 0L) assertTrue(
                "20-second observation slots were missed or not all recorded: " +
                    "observed=$observationSamples expected=$expectedObservationSamples " +
                    "missed=$missedObservationSlots jitterMaxMs=$maxObservationJitterMs " +
                    "allowedJitterMs=10000",
                observationSamples.toLong() == expectedObservationSamples &&
                    missedObservationSlots == 0 && maxObservationJitterMs <= 10_000L)
            assertTrue(
                "initial WIMT source response did not qualify; refusing to start Auto: " +
                    "accepted=$initialGateAccepted autoStarted=$autoStarted " +
                    "http=${initial.httpStatus ?: "none"} runMatched=${initial.runMatched} " +
                    "routeMatched=${initial.routeMatched} fresh=${initial.fresh} rows=${initial.rows} " +
                    "parserOutcome=${initial.parserOutcome} failureReason=${initialFailureReason(initial)}",
                initialGateAccepted && autoStarted)
            assertTrue("fewer than $requiredReceipts one-minute selected-source receipts: " +
                "cycles=$cycles misses=$missedReceipts",
                cycles >= requiredReceipts && missedReceipts <= allowedMissedReceipts)
            if (observationIntervalSeconds == 30L) assertTrue(
                "WIMT actual stop rows never changed across the 60-minute installed-app " +
                    "observation; fresh response stamps alone are not new train events " +
                    "(distinct actual-row snapshots=${sourceEventSnapshots.size})",
                sourceEventSnapshots.size >= 2)
            assertEquals("the app lost the selected WIMT source or tracking screen", 0, selectedLosses)
            assertEquals("WIMT returned an HTTP failure or no qualifying run/route rows", 0,
                httpFailures + unqualifiedSamples)
            if (requireCurrentStation) assertTrue(
                "WIMT .in stopped claiming a current station at a required checkpoint",
                currentStationChecksPassed)
            if (requireCurrentStation) assertTrue(
                "WIMT .in running-status qualification failed at a required checkpoint; it needs a " +
                    "fresh, run/route-matched current-station field and at least one source-reported actual stop clock",
                runningStatusChecksPassed)
            assertTrue("final WIMT source selection did not remain visible", finalSelected)
            assertTrue("Auto must be verified OFF when the soak ends", autoOffConfirmed && !autoEnabled)
            E2E.assertNoSystemDialog("WIMT $durationMinutes-minute Auto refresh observation")
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
