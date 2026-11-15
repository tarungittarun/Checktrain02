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

/** Values edited in both the timetable setup and the home Alerts manager. */
data class AlarmOutputPreferences(
    val feedback: AlertFeedback,
    val leadSeconds: Int,
    val intervalMinutes: Int,
)

/** Reusable, accessible alert options with a bounded custom lead-time field. */
class JourneyAlarmOptionsEditor(
    private val context: Context,
    initialFeedback: AlertFeedback,
    private val initialLeadSeconds: Int,
    initialIntervalMinutes: Int,
    note: String,
    details: String? = null,
    private val includeLeadTime: Boolean = true,
) {
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(8))
    }

    private val feedbackIds: List<Pair<AlertFeedback, Int>>
    private var leadSpinner: Spinner? = null
    private var customLead: EditText? = null
    private val intervalIds: List<Pair<Int, Int>>

    private val leadPresets = listOf(30, 60, 120, 300, 600)

    init {
        addSection("Alert style")
        val feedbackGroup = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
        feedbackIds = listOf(
            AlertFeedback.NOTIFICATION to addRadio(feedbackGroup, "Ping · one sound"),
            AlertFeedback.VIBRATION to addRadio(feedbackGroup, "Vibrate · one pattern"),
            AlertFeedback.RING_UNTIL_STOPPED to addRadio(feedbackGroup, "Alarm ring · stop anytime"),
        )
        feedbackGroup.check(feedbackIds.firstOrNull { it.first == initialFeedback }?.second
            ?: feedbackIds.first().second)
        view.addView(feedbackGroup)

        if (includeLeadTime) {
            addSection("Lead time · before the published timetable time")
            val leadControl = Spinner(context).apply {
                contentDescription = "Alert lead time in seconds"
                setBackgroundResource(R.drawable.bg_inset)
                minimumHeight = dp(48)
                adapter = spinnerAdapter(listOf(
                    "30 seconds", "1 minute · 60 seconds", "2 minutes · 120 seconds",
                    "5 minutes · 300 seconds", "10 minutes · 600 seconds", "Custom seconds…",
                ))
            }
            leadSpinner = leadControl
            view.addView(leadControl, matchWrap())
            val leadInput = EditText(context).apply {
                hint = "Custom lead · 0–${JourneyAlarmSchedule.MAX_LEAD_SECONDS} seconds"
                contentDescription = "Custom lead time in seconds, up to ${JourneyAlarmSchedule.MAX_LEAD_SECONDS}"
                inputType = InputType.TYPE_CLASS_NUMBER
                setSingleLine(true)
                setTextSize(15f)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundResource(R.drawable.bg_inset)
                visibility = View.GONE
            }
            customLead = leadInput
            view.addView(leadInput, matchWrap(top = 6))
            val leadIndex = leadPresets.indexOf(initialLeadSeconds)
            if (leadIndex >= 0) {
                leadControl.setSelection(leadIndex, false)
            } else {
                leadControl.setSelection(leadPresets.size, false)
                leadInput.setText(initialLeadSeconds.coerceIn(0,
                    JourneyAlarmSchedule.MAX_LEAD_SECONDS).toString())
                leadInput.visibility = View.VISIBLE
            }
            leadControl.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, item: View?,
                                            position: Int, id: Long) {
                    leadInput.visibility = if (position == leadPresets.size) View.VISIBLE else View.GONE
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        }

        addSection("Check frequency")
        val intervalGroup = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
        intervalIds = listOf(1, 2, 5).map { minutes ->
            val suffix = if (minutes == 5) " · lower background work" else ""
            minutes to addRadio(intervalGroup,
                "$minutes ${if (minutes == 1) "minute" else "minutes"}$suffix")
        }
        intervalGroup.check(intervalIds.firstOrNull { it.first == initialIntervalMinutes }?.second
            ?: intervalIds.last().second)
        view.addView(intervalGroup)

        TextView(context).apply {
            text = note
            textSize = 12f
            maxLines = 3
            setTextColor(context.getColor(R.color.text2))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.bg_summary_card)
        }.also { view.addView(it, matchWrap(top = 6)) }
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
        val selectedFeedback = feedbackIds.firstOrNull { it.second == checkedRadioId(view) }?.first
            ?: AlertFeedback.NOTIFICATION
        val lead = if (!includeLeadTime) initialLeadSeconds else {
            val leadPosition = leadSpinner?.selectedItemPosition ?: -1
            if (leadPosition == leadPresets.size) {
                val input = customLead ?: return null
                val raw = input.text.toString().trim()
                val parsed = raw.toIntOrNull()
                if (parsed == null || parsed !in 0..JourneyAlarmSchedule.MAX_LEAD_SECONDS) {
                    input.error = "Enter 0–${JourneyAlarmSchedule.MAX_LEAD_SECONDS} seconds"
                    input.requestFocus()
                    return null
                }
                parsed
            } else leadPresets.getOrNull(leadPosition) ?: leadPresets[2]
        }
        val checked = findCheckedInterval(view)
        val interval = intervalIds.firstOrNull { it.second == checked }?.first ?: 5
        return AlarmOutputPreferences(selectedFeedback, lead, interval)
    }

    private fun addSection(label: String) {
        view.addView(TextView(context).apply {
            text = label
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(context.getColor(R.color.text1))
            setPadding(0, dp(10), 0, dp(3))
        }, matchWrap())
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

    private fun spinnerAdapter(values: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(context, R.layout.item_sort_selected, values) {
            init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getView(position, convertView, parent).also { BatteryMode.paint(it) }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getDropDownView(position, convertView, parent).also { BatteryMode.paint(it) }
        }

    private fun checkedRadioId(root: android.view.View): Int =
        (root as? LinearLayout)?.let { layout ->
            (0 until layout.childCount).mapNotNull { layout.getChildAt(it) as? RadioGroup }
                .firstOrNull()?.checkedRadioButtonId
        } ?: -1

    private fun findCheckedInterval(root: android.view.View): Int =
        (root as? LinearLayout)?.let { layout ->
            (0 until layout.childCount).mapNotNull { layout.getChildAt(it) as? RadioGroup }
                .lastOrNull()?.checkedRadioButtonId
        } ?: -1

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        if (top > 0) topMargin = dp(top)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
