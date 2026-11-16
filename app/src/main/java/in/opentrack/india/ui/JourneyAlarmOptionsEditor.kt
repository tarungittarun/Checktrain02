package `in`.opentrack.india.ui

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.ViewCompat
import `in`.opentrack.india.R
import `in`.opentrack.india.alarm.AlertFeedback
import `in`.opentrack.india.alarm.JourneyAlarmSchedule
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.StopRow

/** The four traveller-facing choices for a journey alert. */
data class AlarmOutputPreferences(
    val station: StopRow?,
    val feedback: AlertFeedback,
    val repeatIntervalSeconds: Int,
    val intervalMinutes: Int,
)

/**
 * Reusable, accessible four-field alert editor used by the train setup and Alerts screens.
 *
 * 1. Upcoming station (all route stops after boarding, including the route's final stop).
 * 2. Ping, vibration, or a ring that continues until turned off.
 * 3. One ping/vibration or a repeat interval with custom seconds; ring has no repeat interval.
 * 4. Source-check frequency, with 2/5/10-minute presets and custom minutes.
 *
 * When used for defaults there is no journey route yet, so field 1 is an explanatory read-only
 * row; the other three defaults are still edited with the exact same controls.
 */
class JourneyAlarmOptionsEditor(
    private val context: Context,
    initialFeedback: AlertFeedback,
    initialRepeatIntervalSeconds: Int,
    initialIntervalMinutes: Int,
    note: String,
    details: String? = null,
    stations: List<StopRow> = emptyList(),
    initialStationCode: String? = null,
    stationUnavailableSummary: String = "Choose an upcoming station from the train's route.",
) {
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(8))
    }

    private val stationOptions = stations.toList()
    private var stationSpinner: Spinner? = null
    private val feedbackGroup = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
    private val feedbackIds: List<Pair<AlertFeedback, Int>>
    private val repeatSpinner: Spinner
    private val customRepeat: EditText
    private val ringNote: TextView
    private val checkSpinner: Spinner
    private val customCheck: EditText

    private val repeatPresets = listOf(JourneyAlarmSchedule.REPEAT_ONCE, 5, 10, 30, 60, 120, 300)
    private val checkPresets = listOf(2, 5, 10)

    init {
        addSection("1 · Upcoming station")
        if (stationOptions.isNotEmpty()) {
            stationSpinner = Spinner(context).apply {
                contentDescription = "Upcoming station through the final station"
                setBackgroundResource(R.drawable.bg_inset)
                minimumHeight = dp(48)
                adapter = spinnerAdapter(stationOptions.map { station ->
                    val time = (station.arrivalMin ?: station.departureMin)?.let {
                        RailTime.formatRailMinute(it)
                    } ?: "time unavailable"
                    "${station.name} (${station.code}) · $time"
                })
                val selected = stationOptions.indexOfFirst {
                    it.code.equals(initialStationCode, ignoreCase = true)
                }
                setSelection(selected.coerceAtLeast(0), false)
            }.also { view.addView(it, matchWrap()) }
        } else {
            readOnlyField(stationUnavailableSummary)
        }

        addSection("2 · Notify style")
        feedbackIds = listOf(
            AlertFeedback.NOTIFICATION to addRadio(feedbackGroup, "Ping"),
            AlertFeedback.VIBRATION to addRadio(feedbackGroup, "Vibrate"),
            AlertFeedback.RING_UNTIL_STOPPED to addRadio(feedbackGroup, "Ring · turn off manually"),
        )
        feedbackGroup.contentDescription = "Notify style"
        feedbackGroup.check(feedbackIds.firstOrNull { it.first == initialFeedback }?.second
            ?: feedbackIds.first().second)
        view.addView(feedbackGroup)

        addSection("3 · Frequency / duration")
        val repeatOptions = repeatPresets.map(::repeatLabel) + "Custom seconds…"
        repeatSpinner = Spinner(context).apply {
            contentDescription = "Ping or vibration repeat interval"
            setBackgroundResource(R.drawable.bg_inset)
            minimumHeight = dp(48)
            adapter = spinnerAdapter(repeatOptions)
        }
        customRepeat = numberInput(
            "Custom repeat · ${JourneyAlarmSchedule.MIN_REPEAT_SECONDS}–${JourneyAlarmSchedule.MAX_REPEAT_SECONDS} seconds",
            "Custom ping or vibration repeat interval in seconds",
        ).apply { visibility = View.GONE }
        ringNote = readOnlyField("Ring continues until you turn it off. Snoozing pauses it for 2 minutes.")
        ringNote.visibility = View.GONE
        view.addView(repeatSpinner, matchWrap())
        view.addView(customRepeat, matchWrap(top = 6))
        view.addView(ringNote, matchWrap(top = 4))
        val initialRepeatIndex = repeatPresets.indexOf(initialRepeatIntervalSeconds)
        if (initialRepeatIndex >= 0) {
            repeatSpinner.setSelection(initialRepeatIndex, false)
        } else {
            repeatSpinner.setSelection(repeatPresets.size, false)
            customRepeat.setText(initialRepeatIntervalSeconds.coerceIn(
                JourneyAlarmSchedule.MIN_REPEAT_SECONDS,
                JourneyAlarmSchedule.MAX_REPEAT_SECONDS).toString())
            customRepeat.visibility = View.VISIBLE
        }
        repeatSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, item: View?,
                                        position: Int, id: Long) {
                updateRepeatVisibility()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        feedbackGroup.setOnCheckedChangeListener { _, _ -> updateRepeatVisibility() }
        updateRepeatVisibility()

        addSection("4 · Check frequency")
        checkSpinner = Spinner(context).apply {
            contentDescription = "Alert source check frequency in minutes"
            setBackgroundResource(R.drawable.bg_inset)
            minimumHeight = dp(48)
            adapter = spinnerAdapter(checkPresets.map { "$it minutes" } + "Custom minutes…")
        }
        customCheck = numberInput(
            "Custom check frequency · 1–${JourneyAlarmSchedule.MAX_CHECK_INTERVAL_MINUTES} minutes",
            "Custom alert source check frequency in minutes",
        ).apply { visibility = View.GONE }
        view.addView(checkSpinner, matchWrap())
        view.addView(customCheck, matchWrap(top = 6))
        val initialCheckIndex = checkPresets.indexOf(initialIntervalMinutes)
        if (initialCheckIndex >= 0) {
            checkSpinner.setSelection(initialCheckIndex, false)
        } else {
            checkSpinner.setSelection(checkPresets.size, false)
            customCheck.setText(initialIntervalMinutes.coerceIn(
                1, JourneyAlarmSchedule.MAX_CHECK_INTERVAL_MINUTES).toString())
            customCheck.visibility = View.VISIBLE
        }
        checkSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, item: View?,
                                        position: Int, id: Long) {
                customCheck.visibility = if (position == checkPresets.size) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        TextView(context).apply {
            text = note
            textSize = 12f
            maxLines = 4
            setTextColor(context.getColor(R.color.text2))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.bg_summary_card)
        }.also { view.addView(it, matchWrap(top = 8)) }

        details?.takeIf { it.isNotBlank() }?.let { fullDetails ->
            val detail = TextView(context).apply {
                text = fullDetails
                textSize = 12f
                setTextColor(context.getColor(R.color.text2))
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundResource(R.drawable.bg_summary_card)
                visibility = View.GONE
            }
            val disclosure = TextView(context).apply {
                text = "Source and reliability details ▾"
                textSize = 13f
                minHeight = dp(44)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setTextColor(context.getColor(R.color.accent))
                contentDescription = "Show alert source and reliability details"
                setOnClickListener {
                    val show = detail.visibility != View.VISIBLE
                    detail.visibility = if (show) View.VISIBLE else View.GONE
                    text = if (show) "Hide source and reliability details ▴"
                        else "Source and reliability details ▾"
                    contentDescription = if (show) "Hide alert source and reliability details"
                        else "Show alert source and reliability details"
                }
            }
            view.addView(disclosure, matchWrap(top = 2))
            view.addView(detail, matchWrap(top = 2))
        }
        BatteryMode.paint(view)
    }

    fun read(): AlarmOutputPreferences? {
        val station = stationOptions.getOrNull(stationSpinner?.selectedItemPosition ?: -1)
        val feedback = feedbackIds.firstOrNull { it.second == feedbackGroup.checkedRadioButtonId }?.first
            ?: AlertFeedback.NOTIFICATION
        val repeatSeconds = if (feedback == AlertFeedback.RING_UNTIL_STOPPED) {
            initialRepeatIntervalSeconds.takeIf {
                it == JourneyAlarmSchedule.REPEAT_ONCE
            } ?: initialRepeatIntervalSeconds.coerceIn(JourneyAlarmSchedule.MIN_REPEAT_SECONDS,
                JourneyAlarmSchedule.MAX_REPEAT_SECONDS)
        } else {
            val position = repeatSpinner.selectedItemPosition
            if (position == repeatPresets.size) {
                val raw = customRepeat.text.toString().trim()
                val parsed = raw.toIntOrNull()
                if (parsed == null || parsed !in JourneyAlarmSchedule.MIN_REPEAT_SECONDS..
                    JourneyAlarmSchedule.MAX_REPEAT_SECONDS) {
                    customRepeat.error = "Enter ${JourneyAlarmSchedule.MIN_REPEAT_SECONDS}–" +
                        "${JourneyAlarmSchedule.MAX_REPEAT_SECONDS} seconds"
                    customRepeat.requestFocus()
                    return null
                }
                parsed
            } else repeatPresets.getOrNull(position) ?: 60
        }
        val checkMinutes = if (checkSpinner.selectedItemPosition == checkPresets.size) {
            val raw = customCheck.text.toString().trim()
            val parsed = raw.toIntOrNull()
            if (parsed == null || parsed !in 1..JourneyAlarmSchedule.MAX_CHECK_INTERVAL_MINUTES) {
                customCheck.error = "Enter 1–${JourneyAlarmSchedule.MAX_CHECK_INTERVAL_MINUTES} minutes"
                customCheck.requestFocus()
                return null
            }
            parsed
        } else checkPresets.getOrNull(checkSpinner.selectedItemPosition) ?: checkPresets[0]
        return AlarmOutputPreferences(station, feedback, repeatSeconds, checkMinutes)
    }

    private fun updateRepeatVisibility() {
        val selectedFeedback = feedbackIds.firstOrNull {
            it.second == feedbackGroup.checkedRadioButtonId
        }?.first ?: AlertFeedback.NOTIFICATION
        val ring = selectedFeedback == AlertFeedback.RING_UNTIL_STOPPED
        repeatSpinner.visibility = if (ring) View.GONE else View.VISIBLE
        customRepeat.visibility = if (ring || repeatSpinner.selectedItemPosition != repeatPresets.size)
            View.GONE else View.VISIBLE
        ringNote.visibility = if (ring) View.VISIBLE else View.GONE
    }

    private fun addSection(label: String) {
        view.addView(TextView(context).apply {
            text = label
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(context.getColor(R.color.text1))
            setPadding(0, dp(10), 0, dp(4))
        }, matchWrap())
    }

    private fun readOnlyField(label: String): TextView = TextView(context).apply {
        text = label
        textSize = 13f
        setTextColor(context.getColor(R.color.text2))
        setPadding(dp(12), dp(11), dp(12), dp(11))
        setBackgroundResource(R.drawable.bg_summary_card)
    }

    private fun addRadio(group: RadioGroup, label: String): Int {
        val button = RadioButton(context).apply {
            id = ViewCompat.generateViewId()
            text = label
            textSize = 14f
            setTextColor(context.getColor(R.color.text1))
            buttonTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent))
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            minHeight = dp(48)
            setPadding(dp(6), dp(4), dp(8), dp(4))
        }
        group.addView(button, RadioGroup.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return button.id
    }

    private fun numberInput(hintText: String, description: String) = EditText(context).apply {
        hint = hintText
        contentDescription = description
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine(true)
        setTextSize(15f)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setBackgroundResource(R.drawable.bg_inset)
    }

    private fun spinnerAdapter(values: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(context, R.layout.item_sort_selected, values) {
            init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getView(position, convertView, parent).also { BatteryMode.paint(it) }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getDropDownView(position, convertView, parent).also { BatteryMode.paint(it) }
        }

    private fun repeatLabel(seconds: Int) = when {
        seconds == JourneyAlarmSchedule.REPEAT_ONCE -> "Once"
        seconds < 60 -> "Every $seconds seconds"
        seconds % 60 == 0 -> "Every ${seconds / 60} ${if (seconds == 60) "minute" else "minutes"}"
        else -> "Every $seconds seconds"
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        if (top > 0) topMargin = dp(top)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
