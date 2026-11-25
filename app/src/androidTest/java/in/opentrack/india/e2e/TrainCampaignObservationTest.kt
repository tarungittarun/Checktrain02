package `in`.opentrack.india.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * One assigned train on one installed APK/emulator for exactly 45 minutes.
 * The app's optional foreground Auto control remains opt-in in production; this test enables it
 * visibly, samples the accessible screen at minutes 0..45, and verifies Auto is OFF at the end.
 * It does not set an arrival alert, claim background monitoring, or treat a page/feed stamp as a
 * train event. Every sample must expose a fresh NTES event clock from the selected run; update and
 * request receipts remain separate. The visible app source is recorded on every observation.
 */
@RunWith(AndroidJUnit4::class)
class TrainCampaignObservationTest {
    private data class Observation(
        val minute: Int,
        val elapsedMs: Long,
        val sampledAtIst: String,
        val screenPresent: Boolean,
        val autoEnabled: Boolean?,
        val trainNo: String,
        val runDate: String,
        val route: String,
        val headline: String,
        val provenance: String,
        val age: String,
        val sourceSummary: String,
        val sourceLabel: String,
        val sourceDescription: String,
        val eventDescription: String,
        val eventTimeQualified: Boolean,
        val receiptClock: String?,
        val receiptDescription: String,
        val newReceiptSincePrevious: Boolean,
        val screenshot: String?,
    ) {
        fun json(): JSONObject = JSONObject()
            .put("minute", minute)
            .put("elapsedMs", elapsedMs)
            .put("sampledAtIst", sampledAtIst)
            .put("screenPresent", screenPresent)
            .put("autoEnabled", autoEnabled ?: JSONObject.NULL)
            .put("trainNo", trainNo)
            .put("runDate", runDate)
            .put("route", route)
            .put("headline", headline)
            .put("provenance", provenance)
            .put("age", age)
            .put("sourceSummary", sourceSummary)
            .put("sourceLabel", sourceLabel)
            .put("sourceDescription", sourceDescription)
            .put("eventDescription", eventDescription)
            .put("eventTimeQualified", eventTimeQualified)
            .put("receiptClock", receiptClock ?: JSONObject.NULL)
            .put("receiptDescription", receiptDescription)
            .put("newReceiptSincePrevious", newReceiptSincePrevious)
            .put("screenshot", screenshot ?: JSONObject.NULL)
    }

    private data class ScheduleEligibility(
        val eligible: Boolean,
        val category: String,
        val currentMinute: Double,
        val minutesToDeparture: Double,
        val scheduledMinutesRemaining: Double,
        val reason: String?,
    )

    private fun utcStamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(System.currentTimeMillis())

    private fun istStamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("Asia/Kolkata")
    }.format(System.currentTimeMillis())

    private fun istDate(): String = SimpleDateFormat("dd-MM-yyyy", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("Asia/Kolkata")
    }.format(System.currentTimeMillis())

    private fun istMinuteNow(): Double {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        return calendar.get(Calendar.HOUR_OF_DAY) * 60.0 + calendar.get(Calendar.MINUTE) +
            calendar.get(Calendar.SECOND) / 60.0
    }

    private fun eligibilityAtStart(
        runDate: String,
        departureMinute: Int,
        arrivalMinute: Int,
    ): ScheduleEligibility {
        val currentMinute = istMinuteNow()
        if (istDate() != runDate) return ScheduleEligibility(
            false, "run-date-no-longer-today", currentMinute,
            departureMinute - currentMinute, arrivalMinute - currentMinute,
            "the assigned run date is no longer the emulator's current IST date",
        )
        val category = when {
            departureMinute <= currentMinute && currentMinute <= arrivalMinute -> "scheduled-active-now"
            currentMinute < departureMinute && departureMinute <= currentMinute + 60 ->
                "scheduled-departure-within-60-minutes"
            else -> "outside-eligibility-window"
        }
        val eligible = category != "outside-eligibility-window"
        return ScheduleEligibility(
            eligible, category, currentMinute,
            departureMinute - currentMinute, arrivalMinute - currentMinute,
            if (eligible) null else "the assigned timetable window is no longer active or due within 60 minutes",
        )
    }

    private fun safeText(id: String, timeoutMs: Long = 250): String = runCatching {
        E2E.find(id, timeoutMs)?.text.orEmpty().trim()
    }.getOrDefault("").take(600)

    private fun safeDescription(id: String, timeoutMs: Long = 250): String = runCatching {
        E2E.find(id, timeoutMs)?.contentDescription?.toString().orEmpty().trim()
    }.getOrDefault("").take(1_200)

    private fun safeAutoChecked(): Boolean? = runCatching {
        E2E.find("autoSwitch", 250)?.isChecked
    }.getOrNull()

    private fun receiptClock(description: String): String? =
        Regex("""Checked (\d{2}:\d{2}:\d{2})""").find(description)?.groupValues?.get(1)

    private fun captureScreenshot(tag: String, campaignDir: File): String? = runCatching {
        val screens = File(campaignDir, "screens")
        check(screens.mkdirs() || screens.isDirectory)
        E2E.ui.waitForIdle()
        Thread.sleep(100L)
        val png = File(screens, "$tag.png")
        check(E2E.ui.takeScreenshot(png) && png.isFile && png.length() > 1_000L)
        val xml = File(screens, "$tag.xml")
        E2E.ui.dumpWindowHierarchy(xml)
        check(xml.isFile && xml.length() > 100L)
        check(xml.readText().contains("package=\"${E2E.PKG}\"")) {
            "campaign screenshot hierarchy does not contain the installed app package"
        }
        println("TRAIN_CAMPAIGN_SCREENSHOT $tag pngBytes=${png.length()} xmlBytes=${xml.length()}")
        "screens/${png.name}"
    }.getOrNull()

    private fun sample(minute: Int, startedAt: Long, previousReceipt: String?, campaignDir: File): Observation {
        val now = SystemClock.elapsedRealtime()
        val sourceDescription = safeDescription("sourceSpinner")
        val eventDescription = safeDescription("age")
        val sampledAt = System.currentTimeMillis()
        val clock = receiptClock(sourceDescription)
        val train = safeText("trainNo")
        val date = safeText("runDate")
        val route = safeText("routeLabel")
        val eventTimeQualified = SourceEventTimeQualification.ntesEventFresh(
            sourceDescription, eventDescription, date, train.isNotBlank() && route.isNotBlank(), sampledAt)
        val screenshot = if (minute == 0 || minute == 15 || minute == 30 || minute == 45) {
            captureScreenshot("campaign-minute-${minute.toString().padStart(2, '0')}", campaignDir)
        } else null
        val present = safeText("trainNo").isNotBlank() && safeText("routeLabel").isNotBlank() &&
            runCatching { E2E.ui.currentPackageName == E2E.PKG }.getOrDefault(false)
        return Observation(
            minute = minute,
            elapsedMs = (now - startedAt).coerceAtLeast(0L),
            sampledAtIst = istStamp(),
            screenPresent = present,
            autoEnabled = safeAutoChecked(),
            trainNo = train,
            runDate = date,
            route = route,
            headline = safeText("headline"),
            provenance = safeText("provenance"),
            age = safeText("age"),
            sourceSummary = safeText("sourceSummary"),
            sourceLabel = safeText("sourceSpinner"),
            sourceDescription = sourceDescription,
            eventDescription = eventDescription,
            eventTimeQualified = eventTimeQualified,
            receiptClock = clock,
            receiptDescription = sourceDescription.substringAfter("Last completed refresh: ", "")
                .take(600),
            newReceiptSincePrevious = clock != null && clock != previousReceipt,
            screenshot = screenshot,
        )
    }

    private fun appendObservation(file: File, observation: Observation) {
        file.appendText(observation.json().toString() + "\n", Charsets.UTF_8)
        android.util.Log.i("TrainCampaign", "OBS " + observation.json().toString())
    }

    private fun waitForReceiptAfter(targetAt: Long, previousClock: String?): String? {
        val deadline = targetAt + 30_000L
        var latest: String? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = receiptClock(safeDescription("sourceSpinner"))
            if (latest != null && latest != previousClock) return latest
            SystemClock.sleep(500L)
        }
        return latest
    }

    private fun enableAuto(): Boolean {
        if (!E2E.reveal("autoSwitch", "scroller")) return false
        val initial = safeAutoChecked() ?: return false
        if (initial) {
            if (!E2E.tap("autoSwitch") || safeAutoChecked() != false) return false
        }
        if (!E2E.tap("autoSwitch")) return false
        val deadline = SystemClock.elapsedRealtime() + 3_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (safeAutoChecked() == true) return true
            SystemClock.sleep(100L)
        }
        return false
    }

    private fun disableAuto(): Boolean {
        if (!E2E.reveal("autoSwitch", "scroller")) return false
        return when (safeAutoChecked()) {
            true -> E2E.tap("autoSwitch") && safeAutoChecked() == false
            false -> true
            null -> false
        }
    }

    private fun writeResult(file: File, result: JSONObject) {
        file.writeText(result.toString(2) + "\n", Charsets.UTF_8)
        android.util.Log.i("TrainCampaign", "RESULT " + result.toString())
    }

    @Test
    fun assignedTrainIsObservedAtMinuteZeroThrough45OnOneEmulator() {
        val slot = E2E.arg("campaign_slot").toIntOrNull() ?: 0
        val trainNo = E2E.arg("campaign_train_no")
        val runDate = E2E.arg("campaign_run_date")
        val departureMinute = E2E.arg("campaign_departure_minute").toIntOrNull() ?: -1
        val arrivalMinute = E2E.arg("campaign_arrival_minute").toIntOrNull() ?: -1
        val assignedCategory = E2E.arg("campaign_schedule_eligibility")
        val requiredDurationMinutes = E2E.arg("campaign_duration_minutes", "45").toLongOrNull() ?: 0L
        val expectedRunDateLabel = runCatching {
            val parsed = SimpleDateFormat("dd-MM-yyyy", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("Asia/Kolkata")
                isLenient = false
            }.parse(runDate) ?: error("invalid run date")
            "run of " + SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).apply {
                timeZone = TimeZone.getTimeZone("Asia/Kolkata")
            }.format(parsed)
        }.getOrDefault("")
        val evidenceDir = File(E2E.context().filesDir, "campaign")
        check(evidenceDir.mkdirs() || evidenceDir.isDirectory) { "Cannot create campaign evidence directory" }
        val observationsFile = File(evidenceDir, "observations.jsonl")
        val resultFile = File(evidenceDir, "result.json")
        observationsFile.writeText("")

        var flowPassed = false
        var autoEnabledAtStart = false
        var autoOffAtEnd = false
        var startedAt = 0L
        var endedAt = 0L
        var startedAtUtc = ""
        var endedAtUtc = ""
        var deviceStartCheckedAtIst = ""
        var failure = ""
        var scheduleCheck: ScheduleEligibility? = null
        var sourceAtStart = ""
        var sourceAtEnd = ""
        var previousReceipt: String? = null
        val observations = mutableListOf<Observation>()

        try {
            assertEquals("the campaign duration must be exactly 45 minutes", 45L, requiredDurationMinutes)
            assertTrue("invalid assigned matrix slot", slot in 1..20)
            assertTrue("the roster did not assign a valid train number", trainNo.matches(Regex("[0-9]{4,5}")))
            assertTrue("the roster omitted the run date", runDate.matches(Regex("\\d{2}-\\d{2}-\\d{4}")))
            assertTrue("the roster's schedule endpoint times are invalid", departureMinute in 0..1439 &&
                arrivalMinute >= departureMinute)
            deviceStartCheckedAtIst = istStamp()
            scheduleCheck = eligibilityAtStart(runDate, departureMinute, arrivalMinute)
            assertTrue("assigned train became ineligible before the device test: ${scheduleCheck}",
                scheduleCheck.eligible)

            E2E.launch()
            E2E.awaitSearchScreen()
            assertTrue("train mode unavailable", E2E.tap("modeTrain"))
            assertTrue("assigned train $trainNo was not available in the installed reference DB",
                E2E.typeInBox("trainBox", trainNo) &&
                    E2E.ui.wait(Until.hasObject(E2E.res("sugPrimary")), E2E.LOCAL_MS))
            assertTrue("could not select the assigned train $trainNo", E2E.tapSuggestion("trainBox"))
            assertTrue("Find control was unavailable for $trainNo",
                E2E.reveal("find", "searchCardScroll") && E2E.tap("find"))
            assertTrue("no search result was rendered for assigned train $trainNo",
                E2E.ui.wait(Until.hasObject(E2E.res("hitGo")), E2E.NETWORK_MS))
            assertTrue("could not open the result for assigned train $trainNo",
                E2E.openTrackForTrain(trainNo))
            assertEquals("the emulator opened a different train", trainNo, E2E.text("trainNo"))
            assertEquals("the app screen did not use the assigned run date",
                expectedRunDateLabel, E2E.text("runDate"))
            assertTrue("tracking route did not render", E2E.text("routeLabel").isNotBlank())
            assertTrue("station table did not render", E2E.count(E2E.res("stopName")) > 0)
            assertTrue("the selected app source is not the documented NTES official source",
                safeDescription("sourceSpinner").contains("Selected NTES · official", true))
            sourceAtStart = safeDescription("sourceSpinner")

            autoEnabledAtStart = enableAuto()
            assertTrue("could not visibly enable opt-in Auto · 1 min", autoEnabledAtStart)
            startedAtUtc = utcStamp()
            startedAt = SystemClock.elapsedRealtime()
            flowPassed = true
            val initial = sample(0, startedAt, previousReceipt, evidenceDir)
            observations += initial
            appendObservation(observationsFile, initial)
            previousReceipt = initial.receiptClock

            for (minute in 1..45) {
                val target = startedAt + minute * 60_000L
                while (SystemClock.elapsedRealtime() < target) {
                    SystemClock.sleep(minOf(1_000L, target - SystemClock.elapsedRealtime()))
                }
                waitForReceiptAfter(target, previousReceipt)
                val observation = sample(minute, startedAt, previousReceipt, evidenceDir)
                observations += observation
                appendObservation(observationsFile, observation)
                if (observation.receiptClock != null) previousReceipt = observation.receiptClock
            }
            endedAt = SystemClock.elapsedRealtime()
            endedAtUtc = utcStamp()
        } catch (error: Throwable) {
            failure = error.javaClass.simpleName + ": " + (error.message ?: "no detail").take(320)
            android.util.Log.e("TrainCampaign", "Campaign UI test failed: $failure", error)
            runCatching { E2E.ui.takeScreenshot(File(evidenceDir, "campaign-failure.png")) }
        } finally {
            if (startedAt > 0L && endedAt == 0L) {
                endedAt = SystemClock.elapsedRealtime()
                endedAtUtc = utcStamp()
            }
            autoOffAtEnd = runCatching { disableAuto() }.getOrDefault(false)
            sourceAtEnd = safeDescription("sourceSpinner")
            if (startedAt > 0L && observations.size < 46) {
                // Keep the trace's gaps explicit; never fabricate the missing UI values.
                for (minute in observations.size..45) {
                    if (observations.any { it.minute == minute }) continue
                    val gap = Observation(
                        minute = minute,
                        elapsedMs = minute * 60_000L,
                        sampledAtIst = "",
                        screenPresent = false,
                        autoEnabled = safeAutoChecked(),
                        trainNo = "", runDate = "", route = "", headline = "",
                        provenance = "", age = "", sourceSummary = "", sourceLabel = "",
                        sourceDescription = "", eventDescription = "", eventTimeQualified = false,
                        receiptClock = null, receiptDescription = "",
                        newReceiptSincePrevious = false, screenshot = null,
                    )
                    observations += gap
                    appendObservation(observationsFile, gap)
                }
            }
            val durationMs = if (startedAt > 0L) (endedAt - startedAt).coerceAtLeast(0L) else 0L
            val actualScreens = observations.mapNotNull { it.screenshot }
            val allScreensPresent = listOf(0, 15, 30, 45).all { minute ->
                observations.any { it.minute == minute && it.screenshot != null }
            }
            val allScreenSamplesPresent = observations.size == 46 && observations.all { it.screenPresent }
            val autoOnAtEverySample = observations.size == 46 && observations.all { it.autoEnabled == true }
            val eventTimeQualifiedAtEverySample = observations.size == 46 &&
                observations.all { it.eventTimeQualified }
            val minuteReceiptCount = observations.count { it.minute in 1..45 && it.newReceiptSincePrevious }
            val missedReceiptSamples = 45 - minuteReceiptCount
            val passed = flowPassed && failure.isBlank() && durationMs >= 45L * 60_000L &&
                observations.size == 46 && allScreenSamplesPresent && autoOnAtEverySample &&
                eventTimeQualifiedAtEverySample && minuteReceiptCount >= 43 &&
                missedReceiptSamples <= 2 && autoOffAtEnd &&
                scheduleCheck?.eligible == true &&
                sourceAtStart.contains("Selected NTES · official", true) &&
                sourceAtEnd.contains("Selected NTES · official", true) && allScreensPresent
            val result = JSONObject()
                .put("schemaVersion", 1)
                .put("slot", slot)
                .put("trainNo", trainNo)
                .put("runDate", runDate)
                .put("assignedScheduleEligibility", assignedCategory)
                .put("deviceStartScheduleEligibility", scheduleCheck?.category ?: "not-checked")
                .put("deviceStartEligible", scheduleCheck?.eligible == true)
                .put("deviceStartEligibilityReason", scheduleCheck?.reason ?: JSONObject.NULL)
                .put("deviceStartCurrentMinuteOfDay", scheduleCheck?.currentMinute ?: JSONObject.NULL)
                .put("deviceStartMinutesToDeparture", scheduleCheck?.minutesToDeparture ?: JSONObject.NULL)
                .put("deviceStartScheduledMinutesRemaining", scheduleCheck?.scheduledMinutesRemaining ?: JSONObject.NULL)
                .put("deviceStartCheckedAtIst", deviceStartCheckedAtIst)
                .put("startedAtUtc", if (startedAtUtc.isNotBlank()) startedAtUtc else JSONObject.NULL)
                .put("endedAtUtc", if (endedAtUtc.isNotBlank()) endedAtUtc else JSONObject.NULL)
                .put("durationRequiredMinutes", 45)
                .put("durationMs", durationMs)
                .put("observationCount", observations.size)
                .put("expectedObservationCount", 46)
                .put("screenPresentSamples", observations.count { it.screenPresent })
                .put("autoOnAtEverySample", autoOnAtEverySample)
                .put("eventTimeQualifiedAtEverySample", eventTimeQualifiedAtEverySample)
                .put("minuteRefreshReceipts", minuteReceiptCount)
                .put("missedOrUnchangedReceiptSamples", missedReceiptSamples)
                .put("flowPassed", flowPassed)
                .put("autoEnabledAtStart", autoEnabledAtStart)
                .put("autoOffAtEnd", autoOffAtEnd)
                .put("selectedSourceAtStart", sourceAtStart)
                .put("selectedSourceAtEnd", sourceAtEnd)
                .put("verifiedAppScreenshots", JSONArray(actualScreens))
                .put("failure", failure)
                .put("passed", passed)
            writeResult(resultFile, result)
        }

        val finalResult = runCatching { JSONObject(resultFile.readText()) }.getOrNull()
        assertTrue("the emulator did not record a complete 45-minute minute-by-minute UI trial: " +
            finalResult?.toString(), finalResult?.optBoolean("passed", false) == true)
    }
}
