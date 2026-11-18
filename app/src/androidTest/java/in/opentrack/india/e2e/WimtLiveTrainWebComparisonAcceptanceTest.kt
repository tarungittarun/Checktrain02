package `in`.opentrack.india.e2e

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Installed-APK comparison of the selected WIMT `.in` source with TrackTrain's explicit
 * WIMT web-cache comparison path. Both paths address the same undocumented
 * `/cache/live_status` endpoint; this is not the `.org.in` site or Google's WIMT phone app.
 *
 * A train counts only when the app shows a fresh, run-date/route-matched `.in` current-station
 * claim and source-attributed event clocks, and the same train/date's web-cache comparison has
 * fresh row updates and at least one overlapping station event. Timetable, ETA, fallback, stale
 * rows, HTTP success alone, and screenshots without matching data never count.
 */
@RunWith(AndroidJUnit4::class)
class WimtLiveTrainWebComparisonAcceptanceTest {

    private data class EventClocks(val arrival: String?, val departure: String?)

    private data class Attempt(val trainNo: String) {
        var serviceDate: String? = null
        var appTrainNumberMatched: Boolean = false
        var webRequestTrainNumber: String? = null
        var webRequestTrainNumberMatchesApp: Boolean? = null
        var verdict: String = "NOT_RUN"
        var reason: String = ""
        var directHttpStatus: Int? = null
        var directSelected: Boolean = false
        var directFresh: Boolean = false
        var directDateMatched: Boolean = false
        var directRouteMatched: Boolean = false
        var directCurrentStationClaim: Boolean = false
        var directSourceRows: Int = 0
        var directVisibleTimeCells: Int = 0
        var webHttpStatus: Int? = null
        var webSelected: Boolean = false
        var webServiceDate: String? = null
        var webDateMatched: Boolean = false
        var webRouteMatched: Boolean = false
        var webTrainNumberEchoMatched: Boolean? = null
        var webFresh: Boolean = false
        var webFreshestRowAgeSeconds: Long? = null
        var webFreshestRowUpdatedAt: String? = null
        var webFreshnessCheckedAt: String? = null
        var webSourceRows: Int = 0
        var overlappingEventFields: Int = 0
        var matchedEventFields: Int = 0
        val mismatchFields = mutableListOf<String>()
        val appOnlyEventFields = mutableListOf<String>()
        val webOnlyEventFields = mutableListOf<String>()
        val screenshots = mutableListOf<String>()
        var stopAfterHostFailure: Boolean = false
        var defaultActiveSourceRestored: Boolean = false
        var defaultPreferenceRestored: Boolean = false
        var defaultResetSucceeded: Boolean = false
        var defaultResetFailure: String? = null

        fun toJson(): JSONObject = JSONObject().apply {
            put("trainNo", trainNo)
            putNullable("serviceDate", serviceDate)
            put("appTrainNumberMatched", appTrainNumberMatched)
            putNullable("webRequestTrainNumber", webRequestTrainNumber)
            putNullable("webRequestTrainNumberMatchesApp", webRequestTrainNumberMatchesApp)
            put("verdict", verdict)
            put("reason", reason)
            putNullable("directHttpStatus", directHttpStatus)
            put("directSelected", directSelected)
            put("directFresh", directFresh)
            put("directDateMatched", directDateMatched)
            put("directRouteMatched", directRouteMatched)
            put("directCurrentStationClaim", directCurrentStationClaim)
            put("directSourceRows", directSourceRows)
            put("directVisibleTimeCells", directVisibleTimeCells)
            putNullable("webHttpStatus", webHttpStatus)
            put("webSelected", webSelected)
            putNullable("webServiceDate", webServiceDate)
            put("webDateMatched", webDateMatched)
            put("webRouteMatched", webRouteMatched)
            putNullable("webTrainNumberEchoMatched", webTrainNumberEchoMatched)
            put("webFresh", webFresh)
            putNullable("webFreshestRowAgeSeconds", webFreshestRowAgeSeconds)
            putNullable("webFreshestRowUpdatedAt", webFreshestRowUpdatedAt)
            putNullable("webFreshnessCheckedAt", webFreshnessCheckedAt)
            put("webSourceRows", webSourceRows)
            put("overlappingEventFields", overlappingEventFields)
            put("matchedEventFields", matchedEventFields)
            put("mismatchFields", JSONArray(mismatchFields))
            put("appOnlyEventFields", JSONArray(appOnlyEventFields))
            put("webOnlyEventFields", JSONArray(webOnlyEventFields))
            put("screenshots", JSONArray(screenshots))
            put("stopAfterHostFailure", stopAfterHostFailure)
            put("defaultActiveSourceRestored", defaultActiveSourceRestored)
            put("defaultPreferenceRestored", defaultPreferenceRestored)
            put("defaultResetSucceeded", defaultResetSucceeded)
            put("defaultResetFailure", defaultResetFailure ?: JSONObject.NULL)
        }

        private fun JSONObject.putNullable(key: String, value: Any?) {
            put(key, value ?: JSONObject.NULL)
        }
    }

    @Test fun installedApkMustCompareThreeFreshSameTrainAndDateWimtRuns() {
        val evidenceDir = File(E2E.context().filesDir, "evidence")
        check(evidenceDir.mkdirs() || evidenceDir.isDirectory) {
            "Cannot create private installed-app comparison evidence directory"
        }
        val attemptsFile = File(evidenceDir, "wimt-comparison-attempts.jsonl")
        attemptsFile.writeText("", Charsets.UTF_8)
        val attempts = mutableListOf<Attempt>()
        var hostBlocked = false
        for (trainNo in CANDIDATE_TRAINS) {
            val attempt = try {
                compareTrain(trainNo)
            } catch (error: Throwable) {
                if (error is VirtualMachineError || error is ThreadDeath) throw error
                Attempt(trainNo).apply {
                    verdict = "APP_FLOW_FAILURE"
                    reason = error.javaClass.simpleName
                    if (E2E.visible("trainNo", 300)) {
                        runCatching {
                            val tag = "wimt-compare-$trainNo-final"
                            E2E.shotWimt(tag)
                            screenshots += tag
                        }
                    }
                    defaultResetSucceeded = runCatching { resetDefaultToNtes(this) }.getOrElse {
                        defaultResetFailure = "reset-exception:${it.javaClass.simpleName}"
                        false
                    }
                    if (!defaultResetSucceeded) stopAfterHostFailure = true
                }
            }
            attempts += attempt
            attemptsFile.appendText(attempt.toJson().toString() + "\n", Charsets.UTF_8)
            Log.i(TAG, "WIMT_MULTI_ATTEMPT train=${attempt.trainNo} verdict=${attempt.verdict} " +
                "directHTTP=${attempt.directHttpStatus} webHTTP=${attempt.webHttpStatus} " +
                "directFresh=${attempt.directFresh} webFresh=${attempt.webFresh} " +
                "overlap=${attempt.overlappingEventFields} " +
                "mismatchCount=${attempt.mismatchFields.size + attempt.appOnlyEventFields.size + attempt.webOnlyEventFields.size}")
            val selectedWimtSource = attempt.directSelected || attempt.webSelected
            if (attempt.stopAfterHostFailure ||
                (selectedWimtSource && !attempt.defaultResetSucceeded)) {
                hostBlocked = true
                break // do not retry a refused host or proceed after a failed selected-source reset
            }
            if (attempts.count { it.verdict == "COMPARISON_COMPLETE" } >= MIN_COMPARISONS) break
        }

        val completed = attempts.filter { it.verdict == "COMPARISON_COMPLETE" }
        val mismatchCount = completed.sumOf { it.mismatchFields.size +
            it.appOnlyEventFields.size + it.webOnlyEventFields.size }
        Log.i(TAG, "WIMT_MULTI_SUMMARY candidates=${attempts.size} completed=${completed.size} " +
            "eventMismatchCount=$mismatchCount hostBlocked=$hostBlocked")
        if (completed.size >= MIN_COMPARISONS && !hostBlocked) {
            Log.i(TAG, "WIMT_MULTI_VERDICT=PASS_THREE_FRESH_SAME_TRAIN_DATE_COMPARISONS_NOT_PARITY")
        }
        assertTrue(
            "Need at least $MIN_COMPARISONS fresh same-train/date WIMT comparisons with the NTES " +
                "default verified after every selected-source attempt; completed=${completed.size}, attempted=${attempts.size}, " +
                "hostBlocked=$hostBlocked. Mismatches are reported, not hidden or re-labelled as parity.",
            completed.size >= MIN_COMPARISONS && !hostBlocked,
        )
        E2E.assertNoSystemDialog("WIMT multi-train source comparison")
    }

    private fun compareTrain(trainNo: String): Attempt {
        val result = Attempt(trainNo)
        var sourceChoiceTouched = false
        try {
        val prefix = "wimt-compare-$trainNo"
        E2E.launch()
        E2E.awaitSearchScreen()
        if (!E2E.tap("modeTrain") || !E2E.typeInBox("trainBox", trainNo) ||
            !E2E.ui.wait(Until.hasObject(E2E.res("sugPrimary")), E2E.LOCAL_MS)) {
            result.verdict = "NO_LOCAL_TRAIN_MATCH"
            result.reason = "The train was not present in local search; no live request was made."
            return result
        }
        if (!E2E.tapSuggestion("trainBox") || !E2E.reveal("find", "searchCardScroll") ||
            !E2E.tap("find") || !E2E.ui.wait(Until.hasObject(E2E.res("hitGo")), E2E.NETWORK_MS) ||
            !E2E.openTrackFromFirstResult()) {
            result.verdict = "TRAIN_SEARCH_OR_TRACK_OPEN_FAILED"
            result.reason = "The installed app did not open a tracking result for this candidate."
            return result
        }
        val openedTrain = E2E.text("trainNo")
        if (openedTrain != trainNo) {
            result.verdict = "TRAIN_NUMBER_MISMATCH"
            result.reason = "The tracking screen did not show the requested candidate train number."
            return result
        }
        result.appTrainNumberMatched = true
        result.serviceDate = parseServiceDate(E2E.text("runDate"))
        if (result.serviceDate == null) {
            result.verdict = "SERVICE_DATE_NOT_VISIBLE"
            result.reason = "The app did not expose a parseable origin service date."
            return result
        }
        if (!E2E.reveal("sourceSpinner", "scroller")) {
            result.verdict = "SOURCE_SELECTOR_NOT_VISIBLE"
            result.reason = "The installed tracking screen did not expose its source selector."
            return result
        }
        val initialSource = E2E.find("sourceSpinner")?.contentDescription.orEmpty()
        if (!initialSource.contains("Selected NTES · official", true)) {
            result.verdict = "SOURCE_DEFAULT_NOT_RESET"
            result.reason = "A clean comparison must begin with NTES selected before the single WIMT request."
            return result
        }
        shot(result, "$prefix-before")

        if (!E2E.tap("sourceSpinner")) {
            result.verdict = "SOURCE_PICKER_DID_NOT_OPEN"
            result.reason = "The installed WIMT source picker could not be opened."
            return result
        }
        val liveItem = E2E.ui.wait(
            Until.findObject(By.textContains("WIMT .in live-status feed")), E2E.LOCAL_MS)
        if (liveItem == null) {
            result.verdict = "LIVE_SOURCE_NOT_SELECTABLE"
            result.reason = "The direct WIMT `.in` source was not present in the installed selector."
            return result
        }
        sourceChoiceTouched = true
        liveItem.click() // one deliberate selected-source GET; no retry or Auto refresh
        result.directSelected = true
        val (liveSelected, liveSummary) = awaitSource("Selected WIMT .in live-status feed")
        result.directVisibleTimeCells = visibleWimtSourceCellCount()
        shot(result, "$prefix-app-live")
        val liveDetails = openSourceDetails(
            "$prefix-app-details",
            By.textContains("Selected WIMT .in live-status response"),
            result,
        )
        result.directHttpStatus = httpStatus(liveDetails.ifBlank { liveSummary })
        result.directDateMatched = liveDetails.contains("Run date echoed and matched: true", true)
        result.directRouteMatched = liveDetails.contains(
            "both route endpoints echoed and matched: true", true)
        result.directFresh = liveDetails.contains("fresh source stamp; not event time", true) &&
            liveSummary.contains("upstream update stamp is fresh", true)
        result.directCurrentStationClaim = liveDetails.contains("current-station field claims", true)
        result.directSourceRows = Regex(
            "Selected WIMT \\.in source-reported rows \\((\\d+)\\):")
            .find(liveDetails)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val liveSelectionStillVisible = liveSelected.contains(
            "Selected WIMT .in live-status feed", true)
        val directQualifies = liveSelectionStillVisible &&
            result.directHttpStatus == 200 && result.directFresh &&
            result.directDateMatched && result.directRouteMatched &&
            result.directCurrentStationClaim && result.directSourceRows > 0 &&
            result.directVisibleTimeCells > 0 && result.serviceDate != null
        if (!directQualifies) {
            result.verdict = when (result.directHttpStatus) {
                403 -> "DIRECT_HTTP_403_REFUSED"
                429 -> "DIRECT_HTTP_429_RATE_LIMITED"
                else -> if (result.directHttpStatus?.let { it in 500..599 } == true)
                    "DIRECT_HTTP_5XX" else "DIRECT_SOURCE_NOT_QUALIFIED"
            }
            result.reason = "Direct source must be fresh, date/route matched, current-station attributed, " +
                "and visibly contain source-reported event clocks; schedule/fallback is excluded."
            result.stopAfterHostFailure = result.directHttpStatus == null ||
                result.directHttpStatus?.let { it in 400..599 } == true
            return result
        }
        val appRows = parseEventRows(liveDetails, webCache = false)

        if (!E2E.reveal("sourceSpinner", "scroller") || !E2E.tap("sourceSpinner")) {
            result.verdict = "WEB_CACHE_PICKER_DID_NOT_OPEN"
            result.reason = "Direct WIMT data qualified, but the web-cache comparison selector was unavailable."
            return result
        }
        val cacheItem = E2E.ui.wait(
            Until.findObject(By.textContains("WIMT web cache · experimental")), E2E.LOCAL_MS)
        if (cacheItem == null) {
            E2E.ui.pressBack()
            E2E.ui.waitForIdle()
            result.verdict = "WEB_CACHE_COMPARISON_NOT_SELECTABLE"
            result.reason = "The explicit WIMT web-cache comparison path was not selectable."
            return result
        }
        cacheItem.click() // one deliberate comparison GET for the same train and origin date
        result.webSelected = true
        result.webRequestTrainNumber = trainNo
        result.webRequestTrainNumberMatchesApp = result.appTrainNumberMatched
        val (webSelected, webSummary) = awaitSource("Selected WIMT web cache · experimental")
        shot(result, "$prefix-web-cache")
        val webDetails = openSourceDetails(
            "$prefix-web-details",
            By.textContains("WIMT web cache:"),
            result,
        )
        val cacheEvidence = webDetails.ifBlank { webSummary }
        result.webHttpStatus = httpStatus(cacheEvidence)
        result.webServiceDate = Regex("(?i)run date (\\d{1,2} [A-Za-z]{3} \\d{4})")
            .find(cacheEvidence)?.groupValues?.get(1)
        result.webDateMatched = result.webServiceDate.equals(result.serviceDate, true)
        result.webRouteMatched = cacheEvidence.contains("cache-reported route ", true) &&
            cacheEvidence.contains("matched requested route", true)
        result.webTrainNumberEchoMatched = when {
            cacheEvidence.contains("response train number did not match requested train", true) -> false
            cacheEvidence.contains("response train number matched requested train", true) -> true
            else -> null // request was for the visible train number; the cache did not echo it
        }
        result.webSourceRows = Regex(
            "WIMT web cache: (\\d+) source-reported web-cache time row\\(s\\)")
            .find(cacheEvidence)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val freshestUpdate = freshestWebRowUpdate(cacheEvidence)
        val checkedAtMillis = System.currentTimeMillis()
        val ageMillis = freshestUpdate?.first?.let { checkedAtMillis - it }
        result.webFreshestRowUpdatedAt = freshestUpdate?.second
        result.webFreshnessCheckedAt = formatIstMillis(checkedAtMillis)
        result.webFreshestRowAgeSeconds = ageMillis?.takeIf { it >= 0L }?.div(1_000L)
        result.webFresh = ageMillis != null && ageMillis in 0L..MAX_WEB_ROW_AGE_MS
        val cacheSelectionStillVisible = webSelected.contains(
            "Selected WIMT web cache · experimental", true)
        val cacheQualifies = cacheSelectionStillVisible && result.webHttpStatus == 200 &&
            result.webSourceRows > 0 && result.webDateMatched && result.webRouteMatched &&
            result.webFresh && result.webTrainNumberEchoMatched != false
        if (!cacheQualifies) {
            result.verdict = when (result.webHttpStatus) {
                403 -> "WEB_CACHE_HTTP_403_REFUSED"
                429 -> "WEB_CACHE_HTTP_429_RATE_LIMITED"
                else -> when {
                    result.webHttpStatus?.let { it in 500..599 } == true ->
                        "WEB_CACHE_HTTP_5XX"
                    !result.webDateMatched -> "WEB_CACHE_IDENTITY_OR_FRESHNESS_MISMATCH"
                    else -> "WEB_CACHE_SOURCE_NOT_QUALIFIED"
                }
            }
            result.reason = "The comparison path must return same-date/route source rows with a " +
                "fresh row-update timestamp; ETA, stale rows, and HTTP status alone are excluded."
            result.stopAfterHostFailure = result.webHttpStatus == null ||
                result.webHttpStatus?.let { it in 400..599 } == true
            return result
        }

        val webRows = parseEventRows(webDetails, webCache = true)
        val stats = compareRows(appRows, webRows)
        result.overlappingEventFields = stats.overlap
        result.matchedEventFields = stats.matched.size
        result.mismatchFields += stats.mismatches
        result.appOnlyEventFields += stats.appOnly
        result.webOnlyEventFields += stats.webOnly
        if (stats.overlap == 0) {
            result.verdict = "NO_OVERLAPPING_EVENT_FIELDS"
            result.reason = "Both same-date sources returned fresh rows, but no station event field overlapped."
        } else {
            result.verdict = "COMPARISON_COMPLETE"
            result.reason = if (stats.mismatches.isEmpty() && stats.appOnly.isEmpty() && stats.webOnly.isEmpty())
                "Fresh same-train/date event fields matched across the two app source paths."
            else "Comparison completed; event-time or row-presence differences are retained below."
        }
        return result
        } finally {
            // A search miss does not change the selected source or the saved NTES preference.
            // Only enter Settings after this attempt actually switched to a WIMT source.
            if (sourceChoiceTouched) {
                result.defaultResetSucceeded = runCatching { resetDefaultToNtes(result) }.getOrElse {
                    result.defaultResetFailure = "reset-exception:${it.javaClass.simpleName}"
                    false
                }
                if (!result.defaultResetSucceeded) {
                    result.stopAfterHostFailure = true
                    result.reason = listOf(result.reason,
                        "Could not persist NTES as the next tracking screen's default source " +
                            "(${result.defaultResetFailure ?: "unknown reset stage"}).")
                        .filter { it.isNotBlank() }.joinToString(" ")
                } else if (!result.defaultActiveSourceRestored) {
                    result.reason = listOf(result.reason,
                        "NTES is verified as the saved next-screen default in Settings; the current " +
                            "track screen remained on the selected WIMT source.")
                        .filter { it.isNotBlank() }.joinToString(" ")
                }
            }
        }
    }

    private fun awaitSource(selectedLabel: String): Pair<String, String> {
        val deadline = System.currentTimeMillis() + E2E.NETWORK_MS + 90_000L
        var selected = ""
        var summary = ""
        while (System.currentTimeMillis() < deadline) {
            selected = E2E.find("sourceSpinner", 500)?.contentDescription.orEmpty()
            summary = E2E.text("sourceSummary", 500)
            val settled = summary.isNotBlank() &&
                !summary.contains("Checking", true) &&
                !summary.contains("has not been checked yet", true) &&
                !summary.contains("not checked yet", true)
            if (selected.contains(selectedLabel, true) && settled) return selected to summary
            Thread.sleep(250)
        }
        return selected to summary
    }

    private fun openSourceDetails(tag: String, query: androidx.test.uiautomator.BySelector,
                                  result: Attempt): String {
        if (!E2E.reveal("sourceSpinner", "scroller")) return ""
        val picker = E2E.find("sourceSpinner") ?: return ""
        picker.longClick()
        val detailsButton = E2E.ui.wait(Until.findObject(By.text("Source details")), E2E.LOCAL_MS)
        if (detailsButton == null) {
            dismissSourceDetailsDialogStack()
            return ""
        }
        detailsButton.click()
        val node = E2E.ui.wait(Until.findObject(query), E2E.LOCAL_MS)
        if (node == null) {
            E2E.ui.pressBack()
            E2E.ui.waitForIdle()
            dismissSourceDetailsDialogStack()
            return ""
        }
        val details = node.text.orEmpty()
        shot(result, tag)
        E2E.ui.pressBack() // close Source details; then close its still-open parent dialog
        E2E.ui.waitForIdle()
        dismissSourceDetailsDialogStack()
        return details
    }

    private fun dismissSourceDetailsDialogStack() {
        repeat(2) {
            val detailsDialog = runCatching {
                E2E.ui.findObject(By.textContains("Source details ·")) != null
            }.getOrDefault(false)
            val aboutDialog = runCatching {
                E2E.ui.findObject(By.text("About these times")) != null
            }.getOrDefault(false)
            if (!detailsDialog && !aboutDialog) return
            E2E.ui.pressBack()
            E2E.ui.waitForIdle()
        }
    }

    /** Persist the next screen's default through the visible Settings control, not a live source request. */
    private fun resetDefaultToNtes(result: Attempt): Boolean {
        dismissSourceDetailsDialogStack()
        result.defaultActiveSourceRestored = E2E.find("sourceSpinner", 500)
            ?.contentDescription.orEmpty().contains("Selected NTES · official", true)

        var backs = 0
        while (!E2E.visible("settings", 500) && backs < 3) {
            E2E.ui.pressBack()
            E2E.ui.waitForIdle()
            backs++
        }
        if (!E2E.visible("settings", 1_000)) {
            result.defaultResetFailure = "settings-button-unavailable"
            return false
        }
        if (!E2E.tap("settings", 1_000)) {
            result.defaultResetFailure = "settings-open-failed"
            return false
        }

        if (!waitForSettingsState() || !E2E.reveal("defaultTrackingSource", "settingsScroll")) {
            result.defaultResetFailure = "default-source-setting-unavailable"
            leaveSettingsForSearch()
            return false
        }

        fun selectedNtes(): Boolean = E2E.find("defaultTrackingSource", 500)
            ?.contentDescription.orEmpty().contains("NTES · official", true)
        if (!selectedNtes()) {
            if (!E2E.tap("defaultTrackingSource")) {
                result.defaultResetFailure = "default-source-picker-did-not-open"
                leaveSettingsForSearch()
                return false
            }
            val ntes = E2E.ui.wait(Until.findObject(By.text("NTES · official")), E2E.LOCAL_MS)
            if (ntes == null) {
                result.defaultResetFailure = "ntes-default-option-not-visible"
                leaveSettingsForSearch()
                return false
            }
            ntes.click()
            val saveDeadline = System.currentTimeMillis() + E2E.LOCAL_MS
            while (System.currentTimeMillis() < saveDeadline && !selectedNtes()) Thread.sleep(150)
        }
        if (!selectedNtes()) {
            result.defaultResetFailure = "ntes-default-not-selected-in-settings"
            leaveSettingsForSearch()
            return false
        }

        // The dropdown's label is only the current view state. Recreate Settings from Search and
        // read it back so the evidence proves the preference was saved, not merely selected.
        if (!leaveSettingsForSearch()) {
            result.defaultResetFailure = "return-to-search-before-persistence-check-failed"
            return false
        }
        if (!E2E.visible("settings", 1_000) || !E2E.tap("settings", 1_000)) {
            result.defaultResetFailure = "settings-reopen-for-persistence-check-failed"
            return false
        }
        if (!waitForSettingsState() || !E2E.reveal("defaultTrackingSource", "settingsScroll")) {
            result.defaultResetFailure = "default-source-setting-unavailable-after-reopen"
            leaveSettingsForSearch()
            return false
        }
        result.defaultPreferenceRestored = selectedNtes()
        if (!result.defaultPreferenceRestored) {
            result.defaultResetFailure = "ntes-default-not-persisted-after-settings-reopen"
        }
        val returnedToSearch = leaveSettingsForSearch()
        if (!returnedToSearch && result.defaultResetFailure == null) {
            result.defaultResetFailure = "return-to-search-after-persistence-check-failed"
        }
        result.defaultResetSucceeded = result.defaultPreferenceRestored && returnedToSearch
        return result.defaultResetSucceeded
    }

    private fun waitForSettingsState(): Boolean {
        val deadline = System.currentTimeMillis() + E2E.LOCAL_MS
        while (System.currentTimeMillis() < deadline) {
            if (E2E.text("state", 300).isNotBlank()) return true
            Thread.sleep(150)
        }
        return false
    }

    private fun leaveSettingsForSearch(): Boolean {
        // Use the Settings screen's visible Back control first. A system Back can be consumed by
        // a still-focused native spinner/IME, leaving the test on Settings while looking idle.
        val tappedBack = E2E.reveal("back", "settingsScroll", up = false) &&
            E2E.tap("back", 1_000)
        if (!tappedBack) E2E.ui.pressBack()
        E2E.ui.waitForIdle()
        val searchVisible = E2E.visible("settings", E2E.LOCAL_MS) &&
            (E2E.visible("fromBox", 1_000) || E2E.visible("editSearch", 1_000))
        return searchVisible
    }

    private fun shot(result: Attempt, tag: String) {
        E2E.ui.waitForIdle()
        E2E.shotWimt(tag)
        result.screenshots += tag
    }

    private fun visibleWimtSourceCellCount(): Int {
        fun countVisibleCells(): Int {
            val cells = E2E.all(By.res(E2E.PKG, "liveArr")) + E2E.all(By.res(E2E.PKG, "liveDep"))
            return cells.count {
                val description = it.contentDescription.orEmpty()
                description.contains("WIMT .in source-reported", true) &&
                    !description.endsWith("unknown", true)
            }
        }

        countVisibleCells().takeIf { it > 0 }?.let { return it }
        // The selected source can report real event clocks at stops outside the current RecyclerView
        // viewport (the screen initially focuses its current station). Scroll the actual stop list in
        // both directions and require a visible, source-attributed cell; details text alone is not enough.
        for (up in listOf(false, true)) {
            repeat(8) {
                if (!E2E.swipeIn("stops", up)) return countVisibleCells()
                E2E.ui.waitForIdle()
                countVisibleCells().takeIf { it > 0 }?.let { return it }
            }
        }
        return countVisibleCells()
    }

    private fun parseServiceDate(text: String): String? = Regex(
        "(?i)run of (\\d{1,2} [A-Za-z]{3} \\d{4})")
        .find(text)?.groupValues?.get(1)

    private fun httpStatus(text: String): Int? = Regex("(?i)HTTP\\s+(\\d{3})")
        .find(text)?.groupValues?.get(1)?.toIntOrNull()

    private fun freshestWebRowUpdate(details: String): Pair<Long, String>? {
        val formatter = SimpleDateFormat("dd MMM yyyy HH:mm:ss 'IST'", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
            isLenient = false
        }
        return Regex("(?i)row update (\\d{1,2} [A-Za-z]{3} \\d{4} \\d{2}:\\d{2}:\\d{2} IST)")
            .findAll(details)
            .mapNotNull { match ->
                val text = match.groupValues[1]
                val position = ParsePosition(0)
                formatter.parse(text, position)
                    ?.time?.takeIf { position.index == text.length }?.let { it to text }
            }
            .maxByOrNull { it.first }
    }

    private fun formatIstMillis(millis: Long): String =
        SimpleDateFormat("dd MMM yyyy HH:mm:ss.SSS 'IST'", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }.format(Date(millis))

    private fun parseEventRows(details: String, webCache: Boolean): Map<String, EventClocks> {
        val line = Regex("(?m)^\\s*•\\s*([A-Z0-9]{2,6})\\s*·\\s*(.+?)\\s*$")
        val arrival = Regex("(?:^|·)\\s*arr(?:ival)?\\s+(\\d{2}:\\d{2}(?:\\s+\\([+-]\\d+d\\))?)",
            RegexOption.IGNORE_CASE)
        val departure = Regex("(?:^|·)\\s*dep(?:arture)?\\s+(\\d{2}:\\d{2}(?:\\s+\\([+-]\\d+d\\))?)",
            RegexOption.IGNORE_CASE)
        val parsed = linkedMapOf<String, EventClocks>()
        for (match in line.findAll(details)) {
            val station = match.groupValues[1].uppercase(Locale.ENGLISH)
            val body = match.groupValues[2]
            // Direct-source rows say `arr`/`dep`; comparison rows say `arrival`/`departure`.
            val arr = arrival.find(body)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")
            val dep = departure.find(body)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")
            if (arr != null || dep != null) parsed[station] = EventClocks(arr, dep)
        }
        // Keep the parameter explicit: a future UI wording change must not silently make the
        // comparison parser accept a schedule line as a web-cache event line.
        if (webCache && !details.contains("WIMT web note (unverified, not Act)", true)) return emptyMap()
        if (!webCache && !details.contains("Selected WIMT .in source-reported rows", true)) return emptyMap()
        return parsed
    }

    private data class RowComparison(
        val overlap: Int,
        val matched: List<String>,
        val mismatches: List<String>,
        val appOnly: List<String>,
        val webOnly: List<String>,
    )

    private fun compareRows(app: Map<String, EventClocks>, web: Map<String, EventClocks>): RowComparison {
        val matched = mutableListOf<String>()
        val mismatches = mutableListOf<String>()
        val appOnly = mutableListOf<String>()
        val webOnly = mutableListOf<String>()
        var overlap = 0
        val fields = listOf("arrival", "departure")
        for (station in (app.keys + web.keys).toSortedSet()) {
            val a = app[station]
            val w = web[station]
            for (field in fields) {
                val appTime = if (field == "arrival") a?.arrival else a?.departure
                val webTime = if (field == "arrival") w?.arrival else w?.departure
                val label = "$station $field"
                when {
                    appTime != null && webTime != null -> {
                        overlap++
                        if (appTime == webTime) matched += "$label=$appTime"
                        else mismatches += "$label app=$appTime web=$webTime"
                    }
                    appTime != null -> appOnly += "$label app=$appTime"
                    webTime != null -> webOnly += "$label web=$webTime"
                }
            }
        }
        return RowComparison(overlap, matched, mismatches, appOnly, webOnly)
    }

    private companion object {
        const val TAG = "WimtMultiTrain"
        const val MIN_COMPARISONS = 3
        const val MAX_WEB_ROW_AGE_MS = 10 * 60_000L
        // The first group produced fresh direct-source rows in a prior installed run; the second
        // group is stop-dense and present in this APK's evening timetable index. These are probes
        // only: runtime identity, source freshness, matching event rows, and genuine-screenshot
        // gates still decide whether any candidate counts.
        val CANDIDATE_TRAINS = listOf(
            "13006", "16630", "13308", "12615", "12627", "11077", "13005",
            "57306", "57305", "14814", "11271", "58414", "56227", "58012", "18530",
        )
    }
}
