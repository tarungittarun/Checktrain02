package `in`.opentrack.india.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
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
                    JourneyAlarmStore.readAll(this).filter {
                        it.state != AlertState.ARMED && it.state != AlertState.RINGING
                    }.forEach { JourneyAlarmNotifications.cancelAlert(this, it.id) }
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
            "No active alerts. Set one from a train timetable; several alerts can run together.")
        else active.forEach { addAlertCard(container, it, active = true) }

        addSection(container, "PAST ALERT HISTORY · ${history.size}")
        if (history.isEmpty()) addEmpty(container,
            "Completed, missed, stopped and cancelled alerts appear here. The most recent 100 are retained.")
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
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        }
        val train = buildString {
            append("Train ").append(config.trainNo)
            config.trainName.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }
        card.addView(text(train, 17f, R.color.text1, bold = true))
        card.addView(text("${config.fromCode} → ${config.toCode} · run date ${date(config.originEpochDay)}",
            13f, R.color.text2, top = 3))
        card.addView(text(triggerSummary(snapshot), 14f, R.color.text1, top = 10))
        if (config.trigger != AlertTrigger.PHONE_DISTANCE && config.scheduledMinute != null) {
            card.addView(text("${config.leadSeconds} sec lead · timetable ${RailTime.formatRailMinute(config.scheduledMinute)} · not a live ETA",
                13f, R.color.text2, top = 3))
        }
        card.addView(text("${feedbackSummary(config.feedback)} · check every ${config.intervalMinutes} " +
            if (config.intervalMinutes == 1) "minute" else "minutes", 13f, R.color.text2, top = 3))
        card.addView(text(stateSummary(snapshot), 14f, stateColor(snapshot.state), top = 9, bold = true))
        if (snapshot.status.isNotBlank())
            card.addView(text(snapshot.status, 13f,
                if (snapshot.checkFailed) R.color.warn else R.color.text2, top = 3))
        snapshot.lastSourceLabel?.takeIf { it.isNotBlank() }?.let { source ->
            val label = if (config.trigger == AlertTrigger.PHONE_DISTANCE)
                "Last location check" else "Last accepted report"
            card.addView(text("$label · $source", 12f, R.color.text3, top = 4))
        }
        if (snapshot.lastCheckedAtMillis > 0L)
            card.addView(text("Last check · ${RailTime.formatClockSeconds(snapshot.lastCheckedAtMillis)} IST",
                12f, R.color.text3, top = 5))
        if (snapshot.evidenceAtMillis > 0L) {
            val evidenceLabel = when {
                config.trigger == AlertTrigger.PHONE_DISTANCE ->
                    "Phone-location trigger evidence · not train GPS"
                snapshot.evidenceSourceLabel != null ->
                    "${snapshot.evidenceSourceLabel} trigger evidence · source-reported, not independently verified"
                config.scheduledMinute != null ->
                    "Timetable reminder · not a live ETA or a source-reported arrival"
                else -> "Trigger evidence · not independently verified"
            }
            card.addView(text("$evidenceLabel · ${RailTime.formatClockSeconds(snapshot.evidenceAtMillis)} IST",
                12f, R.color.text3, top = 2))
        }
        if (snapshot.state == AlertState.ARMED) card.addView(text(
            "Android and network limits can delay checks. Monitoring ends after the selected run window.",
            12f, R.color.text3, top = 7))
        if (active) {
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
                layoutParams = lp
            }
            if (snapshot.state == AlertState.ARMED) {
                val edit = actionButton("Modify alert") { editAlert(snapshot) }
                actions.addView(edit, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            }
            val actionText = if (snapshot.state == AlertState.RINGING) "Stop alarm" else "Cancel alert"
            val action = actionButton(actionText) { confirmStop(snapshot, actionText) }
            actions.addView(action, LinearLayout.LayoutParams(
                if (snapshot.state == AlertState.ARMED) 0 else -1, -2,
                if (snapshot.state == AlertState.ARMED) 1f else 0f))
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
            AlertTrigger.DESTINATION_REPORT -> "At station · ${c.toName} · source report or timetable reminder"
            AlertTrigger.PREVIOUS_STOP_REPORT -> "At preceding station · ${c.triggerName} · live source report"
            AlertTrigger.PHONE_DISTANCE -> "Within ${c.distanceKm} km of ${c.toName} · phone location, straight-line distance"
        }
    }

    private fun feedbackSummary(feedback: AlertFeedback): String = when (feedback) {
        AlertFeedback.NOTIFICATION -> "Ping · one sound"
        AlertFeedback.VIBRATION -> "Vibrate · one pattern"
        AlertFeedback.RING_UNTIL_STOPPED -> "Alarm ring · stop anytime"
    }

    private fun stateSummary(snapshot: JourneyAlarmSnapshot): String = when (snapshot.state) {
        AlertState.ARMED -> if (snapshot.checkFailed) "Monitoring · source check needs attention" else "Monitoring"
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
                "This stops only this alarm. Other train alerts keep running."
            else "This cancels only this alert. It will remain in past alert history.")
            .setPositiveButton(action) { _, _ -> JourneyAlarmService.cancel(this, snapshot.id) }
            .setNegativeButton("Keep", null)
            .show().also(::paintBatteryDialog)
    }

    private fun showDefaultOptions() {
        val prefs = Prefs(this)
        val editor = JourneyAlarmOptionsEditor(this, prefs.defaultAlertFeedback,
            prefs.defaultAlertLeadSeconds, prefs.defaultAlertIntervalMinutes,
            "These are starting choices for new alerts only. Every alert keeps its own saved options. A station lead is measured from the published timetable clock, not a live ETA; phone-distance alerts use the selected radius instead.")
        val triggerOptions = listOf(AlertTrigger.DESTINATION_REPORT,
            AlertTrigger.PREVIOUS_STOP_REPORT, AlertTrigger.PHONE_DISTANCE)
        val distanceOptions = listOf(2, 5, 10)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(optionLabel("Default trigger"))
        }
        val triggerSpinner = optionSpinner(
            listOf("Destination · station report or timetable reminder",
                "Any upcoming station · source report or timetable reminder",
                "Phone distance · location"),
            triggerOptions.indexOf(prefs.defaultAlertTrigger), "Default alert trigger")
        root.addView(triggerSpinner)
        root.addView(optionLabel("Default phone-distance radius"))
        val distanceSpinner = optionSpinner(distanceOptions.map { "$it km · straight-line" },
            distanceOptions.indexOf(prefs.defaultAlertDistanceKm), "Default phone-distance radius")
        root.addView(distanceSpinner)
        root.addView(editor.view)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
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
                prefs.defaultAlertTrigger = triggerOptions.getOrNull(triggerSpinner.selectedItemPosition)
                    ?: AlertTrigger.DESTINATION_REPORT
                prefs.defaultAlertDistanceKm = distanceOptions.getOrNull(distanceSpinner.selectedItemPosition)
                    ?: 5
                prefs.defaultAlertFeedback = selected.feedback
                prefs.defaultAlertLeadSeconds = selected.leadSeconds
                prefs.defaultAlertIntervalMinutes = selected.intervalMinutes
                dialog.dismiss()
            }
        }
        dialog.show()
        if (BatteryMode.enabled(binding.root)) BatteryMode.paint(dialog.window?.decorView ?: binding.root)
    }

    private fun optionLabel(label: String) = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(getColor(R.color.text1))
        setPadding(dp(4), dp(10), dp(4), dp(4))
    }

    private fun optionSpinner(labels: List<String>, selected: Int, description: String) = Spinner(this).apply {
        contentDescription = description
        setBackgroundResource(R.drawable.bg_inset)
        minimumHeight = dp(48)
        adapter = object : ArrayAdapter<String>(this@AlertsActivity,
            R.layout.item_sort_selected, labels) {
            init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getView(position, convertView, parent).also { BatteryMode.paint(it) }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getDropDownView(position, convertView, parent).also { BatteryMode.paint(it) }
        }
        setSelection(selected.coerceIn(labels.indices), false)
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
        val editor = JourneyAlarmOptionsEditor(this, c.feedback, c.leadSeconds,
            c.intervalMinutes,
            "Only this alert will change. A station-triggered source result identifies the source; the timetable reminder is explicitly labeled and is not a live ETA.",
            includeLeadTime = c.trigger != AlertTrigger.PHONE_DISTANCE &&
                (c.scheduledMinute != null || route != null))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val stationSpinner: Spinner? = if (stations.isNotEmpty()) {
            root.addView(TextView(this).apply {
                text = "Upcoming station"
                textSize = 14f
                setTextColor(getColor(R.color.text1))
                setPadding(0, dp(6), 0, dp(4))
            })
            Spinner(this).apply {
                contentDescription = "Alert station"
                setBackgroundResource(R.drawable.bg_inset)
                minimumHeight = dp(48)
                adapter = stationAdapter(stations, c)
                val selectedCode = if (c.trigger == AlertTrigger.PREVIOUS_STOP_REPORT)
                    c.triggerCode else c.toCode
                val selectedIndex = stations.indexOfFirst { it.code.equals(selectedCode, true) }
                setSelection(selectedIndex.coerceAtLeast(0), false)
                root.addView(this)
            }
        } else {
            root.addView(TextView(this).apply {
                text = "The route is not available offline; the current station cannot be changed here. Timing and alert style can still be modified."
                textSize = 12f
                setTextColor(getColor(R.color.text2))
                setPadding(0, dp(8), 0, dp(8))
            })
            null
        }
        val distanceOptions = listOf(2, 5, 10)
        val distanceSpinner = if (c.trigger == AlertTrigger.PHONE_DISTANCE) {
            root.addView(optionLabel("Distance radius · straight-line"))
            val spinner = optionSpinner(distanceOptions.map { "$it km" },
                distanceOptions.indexOf(c.distanceKm), "Phone-distance radius")
            root.addView(spinner)
            spinner
        } else null
        root.addView(editor.view)
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Modify alert · train ${c.trainNo}")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save changes", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = editor.read() ?: return@setOnClickListener
                val stationIndex = stationSpinner?.selectedItemPosition ?: -1
                val stop = stations.getOrNull(stationIndex)
                val now = System.currentTimeMillis()
                val changed = if (stop != null && route != null) {
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
                        distanceKm = if (trigger == AlertTrigger.PHONE_DISTANCE)
                            distanceOptions.getOrNull(distanceSpinner?.selectedItemPosition ?: -1)
                                ?: c.distanceKm else 0,
                        feedback = selected.feedback,
                        leadSeconds = selected.leadSeconds,
                        intervalMinutes = selected.intervalMinutes,
                        scheduledMinute = if (trigger == AlertTrigger.PHONE_DISTANCE) null
                            else stop.arrivalMin ?: stop.departureMin,
                        journeyEndMinute = stop.arrivalMin ?: stop.departureMin,
                        createdAtMillis = now,
                    )
                } else c.copy(feedback = selected.feedback, leadSeconds = selected.leadSeconds,
                    intervalMinutes = selected.intervalMinutes,
                    distanceKm = distanceOptions.getOrNull(distanceSpinner?.selectedItemPosition ?: -1)
                        ?: c.distanceKm,
                    createdAtMillis = now)
                if (changed.trigger != AlertTrigger.PHONE_DISTANCE && changed.scheduledMinute == null &&
                    (stop != null || c.scheduledMinute != null)) {
                    Toast.makeText(this, "This station has no published timetable time.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val due = JourneyAlarmSchedule.dueAtMillis(changed)
                if (due != null && due <= now) {
                    Toast.makeText(this, "That lead time has already passed; choose a shorter lead or later station.",
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

    private fun stationAdapter(stations: List<StopRow>, config: JourneyAlarmConfig): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, R.layout.item_sort_selected, stations.map { stop ->
            val time = (stop.arrivalMin ?: stop.departureMin)?.let { RailTime.formatRailMinute(it) }
                ?: "time unavailable"
            "${stop.name} (${stop.code}) · $time"
        }) {
            init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getView(position, convertView, parent).also { BatteryMode.paint(it) }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getDropDownView(position, convertView, parent).also { BatteryMode.paint(it) }
        }

    private fun date(epochDay: Long): String = RailTime.formatDate(epochDay)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun paintBatteryDialog(dialog: AlertDialog) {
        if (BatteryMode.enabled(binding.root)) BatteryMode.paint(dialog.window?.decorView ?: binding.root)
    }
}
