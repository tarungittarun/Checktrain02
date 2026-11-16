package `in`.opentrack.india.ui

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import `in`.opentrack.india.App
import `in`.opentrack.india.R
import `in`.opentrack.india.alarm.AlertFeedback
import `in`.opentrack.india.alarm.AlertState
import `in`.opentrack.india.alarm.AlertTrigger
import `in`.opentrack.india.alarm.JourneyAlarmConfig
import `in`.opentrack.india.alarm.JourneyAlarmNotifications
import `in`.opentrack.india.alarm.JourneyAlarmRouteSelection
import `in`.opentrack.india.alarm.JourneyAlarmSchedule
import `in`.opentrack.india.alarm.JourneyAlarmService
import `in`.opentrack.india.alarm.JourneyAlarmSnapshot
import `in`.opentrack.india.alarm.JourneyAlarmStore
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.data.Prefs
import `in`.opentrack.india.databinding.ActivityAlertsBinding
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute
import kotlinx.coroutines.launch

/** Master alert options, per-alert editing, cancellation, status and bounded local history. */
class AlertsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAlertsBinding
    private var screenStarted = false
    private var alertActionDialog: AlertDialog? = null
    private val actionDialogShown = mutableSetOf<Int>()
    private val storeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { if (!isFinishing) renderAlerts() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlertsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.back.setOnClickListener { finish() }
        binding.options.setOnClickListener { showDefaultOptions() }
        binding.clearHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear past alert history?")
                .setMessage("Active alerts will remain armed. This removes completed, missed, stopped and cancelled entries from this device.")
                .setPositiveButton("Clear history") { _, _ ->
                    JourneyAlarmStore.readAll(this).filterNot {
                        isActiveState(it.state)
                    }.forEach { JourneyAlarmNotifications.cancelAlert(this, it.id) }
                    JourneyAlarmStore.clearHistory(this)
                    renderAlerts()
                }
                .setNegativeButton("Keep", null)
                .show().also(::paintBatteryDialog)
        }
        renderAlerts()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderAlerts()
    }

    override fun onStart() {
        super.onStart()
        screenStarted = true
        JourneyAlarmStore.register(this, storeListener)
        renderAlerts()
    }

    override fun onStop() {
        screenStarted = false
        alertActionDialog?.dismiss()
        alertActionDialog = null
        JourneyAlarmStore.unregister(this, storeListener)
        super.onStop()
    }

    private fun renderAlerts() {
        if (!::binding.isInitialized) return
        val all = JourneyAlarmStore.readAll(this)
        val active = all.filter { isActiveState(it.state) }
            .sortedByDescending { it.config.createdAtMillis }
        val history = all.filterNot { isActiveState(it.state) }
            .sortedByDescending { maxOf(it.evidenceAtMillis, it.lastCheckedAtMillis, it.config.createdAtMillis) }
        val container = binding.alertsContainer
        container.removeAllViews()

        addSection(container, "ACTIVE ALERTS · ${active.size}")
        if (active.isEmpty()) addEmpty(container,
            "No active alerts. Set one from a train timetable; several alerts can run together.")
        else active.forEach { addAlertCard(container, it, active = true) }

        addSection(container, "PAST ALERT HISTORY · ${history.size}")
        if (history.isEmpty()) addEmpty(container,
            "Completed, missed, stopped and cancelled alerts appear here. The most recent 100 are retained.")
        else history.forEach { addAlertCard(container, it, active = false) }
        binding.clearHistory.visibility = if (history.isNotEmpty()) View.VISIBLE else View.GONE
        if (BatteryMode.enabled(binding.root)) BatteryMode.paint(binding.root)
        presentAlertAction(active)
    }

    private fun addSection(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 12f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(getColor(R.color.text2))
            setPadding(dp(2), dp(10), dp(2), dp(8))
            contentDescription = title
        })
    }

    private fun addEmpty(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(getColor(R.color.text2))
            setPadding(dp(8), dp(4), dp(8), dp(16))
        })
    }

    private fun addAlertCard(parent: LinearLayout, snapshot: JourneyAlarmSnapshot, active: Boolean) {
        val config = snapshot.config
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            setBackgroundResource(R.drawable.bg_card)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        }
        val train = buildString {
            append("Train ").append(config.trainNo)
            config.trainName.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }
        card.addView(text(train, 17f, R.color.text1, bold = true))
        card.addView(text("${config.fromCode} → ${config.toCode} · ${date(config.originEpochDay)}",
            13f, R.color.text2, top = 3))
        card.addView(text(triggerSummary(snapshot), 14f, R.color.text1, top = 10))
        if (config.trigger != AlertTrigger.PHONE_DISTANCE && config.scheduledMinute != null) {
            card.addView(text("Published timetable · ${RailTime.formatRailMinute(config.scheduledMinute)}",
                13f, R.color.text2, top = 3))
        }
        card.addView(text(feedbackSummary(config), 13f, R.color.text2, top = 5))
        card.addView(text("Check frequency · every ${config.intervalMinutes} " +
            if (config.intervalMinutes == 1) "minute" else "minutes",
            13f, R.color.text2, top = 2))
        card.addView(text(stateSummary(snapshot), 14f, stateColor(snapshot.state), top = 9, bold = true))
        if (snapshot.status.isNotBlank()) {
            val status = text(snapshot.status, 13f,
                if (snapshot.checkFailed) R.color.warn else R.color.text2, top = 3).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                contentDescription = snapshot.status
            }
            card.addView(status)
        }
        snapshot.lastSourceLabel?.takeIf { it.isNotBlank() }?.let { source ->
            val label = if (config.trigger == AlertTrigger.PHONE_DISTANCE) "Location" else "Sources"
            card.addView(text("$label · $source", 12f, R.color.text3, top = 4))
        }
        if (snapshot.lastCheckedAtMillis > 0L) {
            card.addView(text("Last check · ${RailTime.formatClockSeconds(snapshot.lastCheckedAtMillis)} IST",
                12f, R.color.text3, top = 5))
            if (snapshot.state == AlertState.ARMED) {
                val expectedNext = snapshot.lastCheckedAtMillis + config.intervalMinutes * 60_000L
                card.addView(text("Next check · about ${RailTime.formatClockSeconds(expectedNext)} IST",
                    12f, R.color.text3, top = 2))
            }
        } else if (snapshot.state == AlertState.ARMED) {
            card.addView(text("First check · starting soon", 12f, R.color.text3, top = 5))
        }
        if (snapshot.snoozedUntilMillis > 0L &&
            snapshot.state in setOf(AlertState.ALERTING, AlertState.RINGING)) {
            card.addView(text("Snoozed · resumes around " +
                "${RailTime.formatClockSeconds(snapshot.snoozedUntilMillis)} IST",
                12f, R.color.text3, top = 3))
        } else if (snapshot.state == AlertState.ALERTING && snapshot.nextRepeatAtMillis > 0L) {
            card.addView(text("Next ping · about " +
                "${RailTime.formatClockSeconds(snapshot.nextRepeatAtMillis)} IST",
                12f, R.color.text3, top = 3))
        }
        if (snapshot.evidenceAtMillis > 0L) {
            val evidenceLabel = when {
                config.trigger == AlertTrigger.PHONE_DISTANCE -> "Phone location"
                snapshot.evidenceSourceLabel != null -> snapshot.evidenceSourceLabel
                config.scheduledMinute != null -> "Timetable reminder"
                else -> "Trigger"
            }
            card.addView(text("$evidenceLabel · ${RailTime.formatClockSeconds(snapshot.evidenceAtMillis)} IST",
                12f, R.color.text3, top = 2))
        }
        if (active) {
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
            }
            when (snapshot.state) {
                AlertState.ARMED -> {
                    val edit = actionButton("Modify alert") { editAlert(snapshot) }
                    actions.addView(edit, LinearLayout.LayoutParams(0, -2, 1f).apply {
                        marginEnd = dp(8)
                    })
                    val cancel = actionButton("Cancel alert") {
                        confirmStop(snapshot, "Cancel alert")
                    }
                    actions.addView(cancel, LinearLayout.LayoutParams(0, -2, 1f))
                }
                AlertState.ALERTING, AlertState.RINGING -> {
                    val turnOff = actionButton("Turn off") {
                        JourneyAlarmService.stopAlert(this@AlertsActivity, snapshot.id)
                    }
                    actions.addView(turnOff, LinearLayout.LayoutParams(0, -2, 1f).apply {
                        marginEnd = dp(8)
                    })
                    val snooze = actionButton("Snooze 2 min") {
                        JourneyAlarmService.snooze(this@AlertsActivity, snapshot.id)
                    }
                    actions.addView(snooze, LinearLayout.LayoutParams(0, -2, 1f))
                }
                else -> Unit
            }
            card.addView(actions)
        }
        parent.addView(card)
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(getColor(R.color.text1))
        setBackgroundResource(R.drawable.bg_btn_ghost)
        minHeight = dp(44)
        setOnClickListener { action() }
    }

    private fun text(value: String, size: Float, color: Int, top: Int = 0,
                     bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(getColor(color))
        if (bold) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        if (top > 0) setPadding(0, dp(top), 0, 0)
    }

    private fun triggerSummary(snapshot: JourneyAlarmSnapshot): String {
        val c = snapshot.config
        return when (c.trigger) {
            AlertTrigger.DESTINATION_REPORT -> "At selected station · ${c.toName}"
            AlertTrigger.PREVIOUS_STOP_REPORT -> "At preceding station · ${c.triggerName}"
            AlertTrigger.PHONE_DISTANCE -> "Within ${c.distanceKm} km · ${c.toName}"
        }
    }

    private fun feedbackSummary(config: JourneyAlarmConfig): String = when (config.feedback) {
        AlertFeedback.NOTIFICATION -> if (config.repeatIntervalSeconds == JourneyAlarmSchedule.REPEAT_ONCE)
            "Ping · once" else "Ping · repeats every ${repeatLabel(config.repeatIntervalSeconds)}"
        AlertFeedback.VIBRATION -> if (config.repeatIntervalSeconds == JourneyAlarmSchedule.REPEAT_ONCE)
            "Vibrate · once" else "Vibrate · repeats every ${repeatLabel(config.repeatIntervalSeconds)}"
        AlertFeedback.RING_UNTIL_STOPPED -> "Ring · continues until turned off"
    }

    private fun repeatLabel(seconds: Int): String = when {
        seconds == JourneyAlarmSchedule.REPEAT_ONCE -> "once"
        seconds < 60 -> "$seconds sec"
        seconds % 60 == 0 -> "${seconds / 60} min"
        else -> "$seconds sec"
    }

    private fun stateSummary(snapshot: JourneyAlarmSnapshot): String = when (snapshot.state) {
        AlertState.ARMED -> if (snapshot.checkFailed)
            "Monitoring · no usable source data; retrying" else "Monitoring"
        AlertState.ALERTING -> if (snapshot.snoozedUntilMillis > 0L) "Alert snoozed"
            else if (snapshot.config.repeatIntervalSeconds == JourneyAlarmSchedule.REPEAT_ONCE)
                "One-time alert sent · turn off or snooze"
            else "Alert active · repeating"
        AlertState.RINGING -> if (snapshot.snoozedUntilMillis > 0L)
            "Ring snoozed" else "Ring active · turn off anytime"
        AlertState.FIRED -> "Alert sent"
        AlertState.MISSED -> "Trigger not reported in time"
        AlertState.DISMISSED -> "Stopped or cancelled"
    }

    private fun stateColor(state: AlertState): Int = when (state) {
        AlertState.ARMED, AlertState.ALERTING, AlertState.RINGING -> R.color.live
        AlertState.FIRED -> R.color.accent
        AlertState.MISSED -> R.color.warn
        AlertState.DISMISSED -> R.color.text2
    }

    private fun confirmStop(snapshot: JourneyAlarmSnapshot, action: String) {
        AlertDialog.Builder(this)
            .setTitle("$action for train ${snapshot.config.trainNo}?")
            .setMessage(if (snapshot.state == AlertState.RINGING)
                "This stops only this alarm. Other train alerts keep running."
            else "This cancels only this alert. It will remain in past alert history.")
            .setPositiveButton(action) { _, _ -> JourneyAlarmService.cancel(this, snapshot.id) }
            .setNegativeButton("Keep", null)
            .show().also(::paintBatteryDialog)
    }

    private fun showDefaultOptions() {
        val prefs = Prefs(this)
        val editor = JourneyAlarmOptionsEditor(
            context = this,
            initialFeedback = prefs.defaultAlertFeedback,
            initialRepeatIntervalSeconds = prefs.defaultAlertRepeatIntervalSeconds,
            initialIntervalMinutes = prefs.defaultAlertIntervalMinutes,
            note = "Starting choices for new alerts. Select the upcoming station on each train's route.",
            details = "Station checks use only fresh, route-matched NTES/eRail reports and the direct .in live-status feed, which must match the run date and both route endpoints. Successful checks stay silent. A silent status notification is sent only when all qualified source attempts fail to return usable data, then cleared on recovery. Timetable reminders are labeled separately from source reports. Ping/vibration can be one-shot or repeat; ring continues until turned off. Android background limits can delay checks.",
            stationUnavailableSummary = "Station is chosen per journey from its upcoming stops.",
        )
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(editor.view)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Alert defaults")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save defaults", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = editor.read() ?: return@setOnClickListener
                prefs.defaultAlertFeedback = selected.feedback
                prefs.defaultAlertRepeatIntervalSeconds = selected.repeatIntervalSeconds
                prefs.defaultAlertIntervalMinutes = selected.intervalMinutes
                dialog.dismiss()
            }
        }
        dialog.show()
        if (BatteryMode.enabled(binding.root)) BatteryMode.paint(dialog.window?.decorView ?: binding.root)
    }

    private fun editAlert(snapshot: JourneyAlarmSnapshot) {
        lifecycleScope.launch {
            val c = snapshot.config
            val repo = runCatching { (application as App).repository() }.getOrNull()
            val saved = runCatching { repo?.savedSchedule(c.trainNo, c.fromCode, c.toCode,
                c.originEpochDay) }.getOrNull()
            val bundled = runCatching { repo?.route(c.trainNo) }.getOrNull()
            val route = JourneyAlarmRouteSelection.firstMatching(c.trainNo,
                c.fromCode, c.toCode, saved, bundled)
            val from = route?.stops?.firstOrNull { it.code.equals(c.fromCode, true) }
            val stations = if (route != null && from != null)
                route.stops.filter { it.seq > from.seq } else emptyList()
            showEditDialog(snapshot, route, stations)
        }
    }

    private fun showEditDialog(snapshot: JourneyAlarmSnapshot, route: TrainRoute?, stations: List<StopRow>) {
        val c = snapshot.config
        val currentStationCode = if (c.trigger == AlertTrigger.PREVIOUS_STOP_REPORT)
            c.triggerCode else c.toCode
        val stationHint = if (route == null || stations.isEmpty())
            "The route is unavailable offline. The current station stays ${c.toName}."
        else "Choose any upcoming stop, including the final station on this route."
        val editor = JourneyAlarmOptionsEditor(
            context = this,
            initialFeedback = c.feedback,
            initialRepeatIntervalSeconds = c.repeatIntervalSeconds,
            initialIntervalMinutes = c.intervalMinutes,
            note = "Only this alert changes. Successful source checks stay quiet; check status and timing stay here.",
            details = "Station events qualify only when fresh NTES/eRail or direct .in data matches this train, run and ordered route. The .in feed must echo the run date and both route endpoints. Timetable reminders are clearly labeled and are not live ETAs. If every qualified source attempt fails to return usable data, one silent notice is sent for the outage and cleared on recovery. Ping/vibration can be one-shot or repeat; ring continues until turned off. Snooze pauses any style for 2 minutes.",
            stations = stations,
            initialStationCode = currentStationCode,
            stationUnavailableSummary = stationHint,
        )
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(editor.view) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Modify alert · train ${c.trainNo}")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save changes", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = editor.read() ?: return@setOnClickListener
                val stop = selected.station?.takeIf { route != null }
                val now = System.currentTimeMillis()
                if (stop != null && c.trigger != AlertTrigger.PHONE_DISTANCE &&
                    stop.arrivalMin == null && stop.departureMin == null) {
                    Toast.makeText(this, "This station has no published timetable time.",
                        Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val changed = if (stop != null) {
                    val trigger = if (c.trigger == AlertTrigger.PHONE_DISTANCE)
                        AlertTrigger.PHONE_DISTANCE else AlertTrigger.DESTINATION_REPORT
                    c.copy(
                        toCode = stop.code,
                        toName = stop.name,
                        toSeq = stop.seq,
                        trigger = trigger,
                        triggerCode = stop.code,
                        triggerName = stop.name,
                        triggerSeq = stop.seq,
                        distanceKm = if (trigger == AlertTrigger.PHONE_DISTANCE) c.distanceKm else 0,
                        feedback = selected.feedback,
                        repeatIntervalSeconds = selected.repeatIntervalSeconds,
                        intervalMinutes = selected.intervalMinutes,
                        leadSeconds = if (trigger == AlertTrigger.PHONE_DISTANCE) c.leadSeconds else 0,
                        scheduledMinute = if (trigger == AlertTrigger.PHONE_DISTANCE) null
                            else stop.arrivalMin ?: stop.departureMin,
                        journeyEndMinute = stop.arrivalMin ?: stop.departureMin,
                        createdAtMillis = now,
                    )
                } else c.copy(
                    feedback = selected.feedback,
                    repeatIntervalSeconds = selected.repeatIntervalSeconds,
                    intervalMinutes = selected.intervalMinutes,
                    leadSeconds = if (c.trigger == AlertTrigger.PHONE_DISTANCE) c.leadSeconds else 0,
                    createdAtMillis = now,
                )
                val stationChanged = stop != null && !stop.code.equals(currentStationCode, true)
                val due = JourneyAlarmSchedule.dueAtMillis(changed)
                if (stationChanged && due != null && due <= now) {
                    Toast.makeText(this,
                        "That station's timetable time has passed. Choose a later stop.",
                        Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (JourneyAlarmStore.replaceConfig(this, snapshot.id, changed)) {
                    JourneyAlarmNotifications.cancelAlert(this, snapshot.id)
                    JourneyAlarmService.reconfigure(this)
                    dialog.dismiss()
                    Toast.makeText(this, "Alert updated", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
        if (BatteryMode.enabled(binding.root)) BatteryMode.paint(dialog.window?.decorView ?: binding.root)
    }

    private fun presentAlertAction(active: List<JourneyAlarmSnapshot>) {
        if (!screenStarted || isFinishing) return
        val requestedId = intent.getIntExtra(EXTRA_ALERT_ID, -1)
        val now = System.currentTimeMillis()
        active.filter {
            it.state in setOf(AlertState.ALERTING, AlertState.RINGING) &&
                it.snoozedUntilMillis > now
        }.forEach { actionDialogShown.remove(it.id) }
        if (alertActionDialog?.isShowing == true) return
        val ready = active.filter {
            it.state in setOf(AlertState.ALERTING, AlertState.RINGING) &&
                it.snoozedUntilMillis <= now && it.id !in actionDialogShown
        }
        val snapshot = ready.firstOrNull { it.id == requestedId } ?: ready.firstOrNull() ?: return
        actionDialogShown += snapshot.id
        alertActionDialog = JourneyAlertActionDialog.show(
            activity = this,
            snapshot = snapshot,
            onStop = { JourneyAlarmService.stopAlert(this, snapshot.id) },
            onSnooze = {
                JourneyAlarmService.snooze(this, snapshot.id)
                actionDialogShown.remove(snapshot.id)
            },
        ).also { dialog ->
            dialog.setOnDismissListener { if (alertActionDialog === dialog) alertActionDialog = null }
        }
    }

    private fun isActiveState(state: AlertState): Boolean =
        state == AlertState.ARMED || state == AlertState.ALERTING || state == AlertState.RINGING

    private fun date(epochDay: Long): String = RailTime.formatDate(epochDay)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun paintBatteryDialog(dialog: AlertDialog) {
        if (BatteryMode.enabled(binding.root)) BatteryMode.paint(dialog.window?.decorView ?: binding.root)
    }

    companion object { const val EXTRA_ALERT_ID = "alertId" }
}
