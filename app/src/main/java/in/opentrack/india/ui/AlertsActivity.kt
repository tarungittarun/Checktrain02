package `in`.opentrack.india.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import `in`.opentrack.india.R
import `in`.opentrack.india.alarm.AlertFeedback
import `in`.opentrack.india.alarm.AlertState
import `in`.opentrack.india.alarm.AlertTrigger
import `in`.opentrack.india.alarm.JourneyAlarmService
import `in`.opentrack.india.alarm.JourneyAlarmSnapshot
import `in`.opentrack.india.alarm.JourneyAlarmStore
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.databinding.ActivityAlertsBinding

/** Central active-alert controls plus a bounded, device-local history. */
class AlertsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAlertsBinding
    private val storeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { if (!isFinishing) renderAlerts() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlertsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.back.setOnClickListener { finish() }
        binding.clearHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear past alert history?")
                .setMessage("Active alerts will remain armed. This removes completed, missed, stopped and cancelled entries from this device.")
                .setPositiveButton("Clear history") { _, _ ->
                    JourneyAlarmStore.readAll(this).filter {
                        it.state != AlertState.ARMED && it.state != AlertState.RINGING
                    }.forEach { `in`.opentrack.india.alarm.JourneyAlarmNotifications.cancelAlert(this, it.id) }
                    JourneyAlarmStore.clearHistory(this)
                    renderAlerts()
                }
                .setNegativeButton("Keep", null)
                .show().also(::paintBatteryDialog)
        }
        renderAlerts()
    }

    override fun onStart() {
        super.onStart()
        JourneyAlarmStore.register(this, storeListener)
        renderAlerts()
    }

    override fun onStop() {
        JourneyAlarmStore.unregister(this, storeListener)
        super.onStop()
    }

    private fun renderAlerts() {
        if (!::binding.isInitialized) return
        val all = JourneyAlarmStore.readAll(this)
        val active = all.filter { it.state == AlertState.ARMED || it.state == AlertState.RINGING }
            .sortedByDescending { it.config.createdAtMillis }
        val history = all.filterNot { it.state == AlertState.ARMED || it.state == AlertState.RINGING }
            .sortedByDescending { maxOf(it.evidenceAtMillis, it.lastCheckedAtMillis, it.config.createdAtMillis) }
        val container = binding.alertsContainer
        container.removeAllViews()

        addSection(container, "ACTIVE ALERTS · ${active.size}")
        if (active.isEmpty()) addEmpty(container,
            "No active alerts. Set an alert from a train screen; multiple alerts can run together.")
        else active.forEach { addAlertCard(container, it, active = true) }

        addSection(container, "PAST ALERT HISTORY · ${history.size}")
        if (history.isEmpty()) addEmpty(container,
            "Completed, missed, stopped and cancelled alerts will appear here. The most recent 100 are retained.")
        else history.forEach { addAlertCard(container, it, active = false) }
        binding.clearHistory.visibility = if (history.isNotEmpty()) View.VISIBLE else View.GONE
        if (BatteryMode.enabled(binding.root)) BatteryMode.paint(binding.root)
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
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.bottomMargin = dp(10)
            layoutParams = lp
        }
        val train = buildString {
            append("Train ").append(config.trainNo)
            config.trainName.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }
        card.addView(text(train, 17f, R.color.text1, bold = true))
        card.addView(text("${config.fromCode} → ${config.toCode} · run date ${date(config.originEpochDay)}",
            13f, R.color.text2, top = 3))
        card.addView(text(triggerSummary(snapshot), 14f, R.color.text1, top = 10))
        card.addView(text("${feedbackSummary(config.feedback)} · check every ${config.intervalMinutes} " +
            if (config.intervalMinutes == 1) "minute" else "minutes", 13f, R.color.text2, top = 3))
        card.addView(text(stateSummary(snapshot), 14f, stateColor(snapshot.state), top = 9, bold = true))
        if (snapshot.status.isNotBlank())
            card.addView(text(snapshot.status, 13f, R.color.text2, top = 3))
        if (snapshot.lastCheckedAtMillis > 0L)
            card.addView(text("Last check · ${RailTime.formatClockSeconds(snapshot.lastCheckedAtMillis)} IST",
                12f, R.color.text3, top = 5))
        if (snapshot.evidenceAtMillis > 0L) {
            val evidenceLabel = if (config.trigger == AlertTrigger.PHONE_DISTANCE)
                "Phone-location trigger evidence" else "Source-reported trigger evidence"
            card.addView(text("$evidenceLabel · ${RailTime.formatClockSeconds(snapshot.evidenceAtMillis)} IST · not independent verification",
                12f, R.color.text3, top = 2))
        }
        if (snapshot.state == AlertState.ARMED) card.addView(text(
            "Android, network, location and notification-channel limits may delay or stop checks.",
            12f, R.color.text3, top = 7))
        if (active) {
            val actionText = if (snapshot.state == AlertState.RINGING) "Stop alarm" else "Cancel alert"
            val action = Button(this).apply {
                text = actionText
                isAllCaps = false
                setTextColor(getColor(R.color.text1))
                setBackgroundResource(R.drawable.bg_btn_ghost)
                minHeight = dp(44)
                setOnClickListener { confirmStop(snapshot, actionText) }
            }
            val lp = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
            card.addView(action, lp)
        }
        parent.addView(card)
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
            AlertTrigger.DESTINATION_REPORT -> "At destination · ${c.toName} · live source report"
            AlertTrigger.PREVIOUS_STOP_REPORT -> "At preceding station · ${c.triggerName} · live source report"
            AlertTrigger.PHONE_DISTANCE -> "Within ${c.distanceKm} km of ${c.toName} · phone location, straight-line distance"
        }
    }

    private fun feedbackSummary(feedback: AlertFeedback): String = when (feedback) {
        AlertFeedback.NOTIFICATION -> "Notification · one alert"
        AlertFeedback.VIBRATION -> "Vibration · one pattern"
        AlertFeedback.RING_UNTIL_STOPPED -> "Ring until stopped"
    }

    private fun stateSummary(snapshot: JourneyAlarmSnapshot): String = when (snapshot.state) {
        AlertState.ARMED -> "Monitoring"
        AlertState.RINGING -> "Alarm ringing"
        AlertState.FIRED -> "Alert sent"
        AlertState.MISSED -> "Trigger not reported in time"
        AlertState.DISMISSED -> "Stopped or cancelled"
    }

    private fun stateColor(state: AlertState): Int = when (state) {
        AlertState.ARMED, AlertState.RINGING -> R.color.live
        AlertState.FIRED -> R.color.accent
        AlertState.MISSED -> R.color.warn
        AlertState.DISMISSED -> R.color.text2
    }

    private fun confirmStop(snapshot: JourneyAlarmSnapshot, action: String) {
        AlertDialog.Builder(this)
            .setTitle("$action for train ${snapshot.config.trainNo}?")
            .setMessage(if (snapshot.state == AlertState.RINGING)
                "This stops only this alert. Other train alerts keep running."
            else "This cancels only this alert. It will remain in past alert history.")
            .setPositiveButton(action) { _, _ -> JourneyAlarmService.cancel(this, snapshot.id) }
            .setNegativeButton("Keep", null)
            .show().also(::paintBatteryDialog)
    }

    private fun date(epochDay: Long): String = RailTime.formatDate(epochDay)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun paintBatteryDialog(dialog: AlertDialog) {
        if (BatteryMode.enabled(binding.root)) BatteryMode.paint(dialog.window?.decorView ?: binding.root)
    }
}
