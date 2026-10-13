package `in`.opentrack.india.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.AdapterView
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import `in`.opentrack.india.App
import `in`.opentrack.india.core.BuildStamp
import `in`.opentrack.india.core.Diary
import `in`.opentrack.india.R
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.databinding.ActivityTrainBinding
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.remote.FeedProbe

/**
 * Screen 2 of 3 — the live view: where the train is against where the timetable says it should be, and
 * the stop-by-stop comparison underneath.
 *
 * This Activity binds strings and picks colours. It computes nothing about the train: the sentence at the
 * top and the highlighted row in the table below are both read out of the same [TrackModel.View], so
 * they cannot tell two stories. That separation is the point of the model, and it is asserted in
 * `TrackModelTest`.
 *
 * Provenance is shown at the same visual weight as the position, not in a footer: a tracker that displays
 * a stale marker without saying so is the specific failure this project exists to avoid.
 */
class TrainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrainBinding
    private lateinit var vm: LiveViewModel
    private lateinit var stopAdapter: StopAdapter
    private var fullRoute = false

    /** The last anchor the screen pointed at - the scroll fires when it CHANGES, never on every tick. */
    private var lastAnchor: String? = null
    private var autoOn = true
    private var sourceShown = false
    private var lastUi: LiveViewModel.Ui? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trainNo = intent.getStringExtra(EXTRA_TRAIN_NO).orEmpty()
        if (trainNo.length < 4) { finish(); return }
        binding = ActivityTrainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as App
        val from = intent.getStringExtra(EXTRA_FROM).orEmpty()
        val to = intent.getStringExtra(EXTRA_TO).orEmpty()
        val originDay = intent.getLongExtra(EXTRA_ORIGIN_EPOCH_DAY, RailTime.todayEpochDay())
        vm = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    LiveViewModel(app, trainNo, from, to, originDay) as T
            }
        )[LiveViewModel::class.java]

        stopAdapter = StopAdapter()
        binding.stops.layoutManager = LinearLayoutManager(this)
        binding.stops.adapter = stopAdapter
        binding.buildStamp.text = BuildStamp.ROUND

        binding.refresh.setOnClickListener { vm.refresh(manual = true) }
        binding.autoSwitch.setOnCheckedChangeListener(null)
        binding.autoSwitch.isChecked = autoOn
        binding.autoSwitch.setOnCheckedChangeListener { _, checked ->
            autoOn = checked
            vm.setAutoRefresh(checked)
        }
        binding.sourceToggle.setOnClickListener {
            sourceShown = !sourceShown
            binding.sourceCard.visibility = if (sourceShown) View.VISIBLE else View.GONE
            binding.sourceToggle.setText(
                if (sourceShown) R.string.hide_source else R.string.show_source
            )
        }
        binding.back.setOnClickListener { finish() }
        // Long-press the provenance badge to copy a live-feed report. It lives on that chip rather than in
        // Settings because the chip is what a traveller looks at when the screen is not telling the truth as
        // they know it — and the copied text is precisely what turns "it doesn't work" into something fixable
        // from another continent: which sources were tried, what each said, how many rows carry a reported
        // minute, and the stop table as displayed. No account, no token and no location history in it.
        binding.provenance.setOnLongClickListener {
            copyFeedReport()
            true
        }

        vm.ui.observe(this) { it?.let { u -> lastUi = u; bind(u); renderNote() } }
        vm.checking.observe(this) { busy ->
            // The 1800s complaint, answered: a moving bar for exactly as long as the app is working,
            // and a captioned spinner while the very first look is still in flight.
            binding.checkingBar.visibility = if (busy == true) View.VISIBLE else View.GONE
            binding.firstLoad.visibility = if (busy == true && lastUi == null) View.VISIBLE else View.GONE
        }
        // The sources box: fetching while the witnesses work. Once actuals exist, the line goes quiet -
        // the top card then carries the same sentence (badge + source row + refreshed stamp), and one
        // copy on screen is the owner's round-47 point: everything read at once, nothing said twice.
        vm.mirrorState.observe(this) { st ->
            val duplicate = st != null && st.startsWith("Actuals ·")
            binding.mirrorState.visibility = if (st == null || duplicate) View.GONE else View.VISIBLE
            st?.let {
                binding.mirrorState.text = it
                Diary.get()?.log("UISTATE") { "sources box: $it" }
            }
        }
        vm.error.observe(this) { msg ->
            errorText = msg
            renderNote()
        }
    }

    // LEGACY-FREEZE (5.2b): the live pair list (v2) can know a fresher identity for this number than
    // the offline route this screen renders (16228 was two different trains wearing one number - the
    // founding bug). When the tapped row's codes disagree with the loaded route, the screen SAYS so
    // (TrainIdentity.conflictWith's sentence pattern) instead of silently showing a wrong train's times.
    private var errorText: String? = null

    private fun identityConflict(): String? {
        val ui = lastUi ?: return null
        val liveFrom = intent.getStringExtra(EXTRA_FROM).orEmpty()
        val liveTo = intent.getStringExtra(EXTRA_TO).orEmpty()
        if (liveFrom.length < 3 || liveTo.length < 3) return null
        val label = ui.model.routeLabel
        if (label.contains(liveFrom) && label.contains(liveTo)) return null
        return "train ${ui.model.trainNo}: this device's timetable says $label, " +
            "the live list says $liveFrom → $liveTo — the times below are the timetable's and may " +
            "belong to the wrong train"
    }

    /** One writer for the note line: a live-identity conflict outranks a transient error message. */
    private fun renderNote() {
        val msg = identityConflict() ?: errorText
        binding.note.visibility = if (msg == null) View.GONE else View.VISIBLE
        if (msg != null) binding.note.text = msg
    }

    /**
     * The source DROPDOWN (the owner asked for a dropdown in the top box, round 45): every loaded
     * witness is an entry; picking one re-renders the table from that witness. Rebuilt only when the
     * witness set changes, so a re-selection never fights the observer.
     */
    private fun renderSourceChips(ui: LiveViewModel.Ui) {
        val names = ui.sourceLines.keys.toList()
        binding.sourceSpinner.visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
        if (names.isEmpty()) return
        val states = ui.sourceStates
        if (binding.sourceSpinner.tag != (names to states)) {
            binding.sourceSpinner.tag = names to states
            binding.sourceSpinner.adapter = object : ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, names,
            ) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    val view = super.getView(position, convertView, parent)
                    (view as TextView).apply {
                        text = names[position]
                        setTextColor(getColor(R.color.text1))
                    }
                    return view
                }

                override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    val view = super.getDropDownView(position, convertView, parent)
                    val name = names[position]
                    val (state, detail) = states[name] ?: ("last-known" to "last known")
                    (view as TextView).apply {
                        text = "$name · $detail"
                        setTextColor(getColor(when (state) {
                            "live" -> R.color.live
                            "none" -> R.color.late
                            else -> R.color.warn
                        }))
                    }
                    return view
                }
            }
            binding.sourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val name = names.getOrNull(pos) ?: return
                    if (name != lastUi?.activeSource) vm.setActiveSource(name)
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        ui.activeSource?.let { src ->
            val at = names.indexOf(src)
            if (at >= 0 && binding.sourceSpinner.selectedItemPosition != at) {
                binding.sourceSpinner.setSelection(at, false)
            }
        }
    }

    private fun bind(ui: LiveViewModel.Ui) {
        val m = ui.model
        binding.trainNo.text = m.trainNo
        binding.routeLabel.text = m.routeLabel
        binding.runDate.text = m.runDateLabel
        binding.lastUpdated.visibility = if (ui.lastUpdated == null) View.GONE else View.VISIBLE
        binding.lastUpdated.text = ui.lastUpdated
        binding.headline.text = m.headline

        val (badgeBg, badgeText) = when (m.tone) {
            TrackModel.Tone.LIVE_OK, TrackModel.Tone.LIVE_EARLY ->
                R.drawable.bg_badge_live to R.color.live
            TrackModel.Tone.LIVE_LATE -> R.drawable.bg_badge_problem to R.color.late
            TrackModel.Tone.PROBLEM -> R.drawable.bg_badge_problem to R.color.late
            TrackModel.Tone.SCHEDULE -> R.drawable.bg_badge_schedule to R.color.warn
            TrackModel.Tone.NONE -> R.drawable.bg_pill to R.color.text2
        }
        binding.provenance.setBackgroundResource(badgeBg)
        binding.provenance.setTextColor(getColor(badgeText))
        binding.provenance.text = m.provenanceLabel
        binding.age.text = m.ageLabel
        binding.delay.text = m.delayLabel
        binding.delay.setTextColor(getColor(
            when (m.tone) {
                TrackModel.Tone.LIVE_LATE, TrackModel.Tone.PROBLEM -> R.color.late
                TrackModel.Tone.LIVE_EARLY -> R.color.early
                TrackModel.Tone.SCHEDULE -> R.color.warn
                else -> R.color.accent
            }
        ))
        binding.next.text = m.nextLabel
        // When the mirror's actuals reached this screen, THEY are the headline: the schedule's own
        // sentences ("Should be past Shimoga Town") contradicted the confirmed departure and read as
        // wrong data (paste #25). The badge earns its colour from the measured lateness.
        if (ui.actualsHeadline == null) {
            val src = ui.actualsSource
            if (src != null) {
                // A witness answered with minutes but none is an observation yet: say so, name it,
                // never fall back to "sources unreachable" - that would deny data on the screen.
                binding.provenance.text = "ACTUALS · $src"
                binding.provenance.setBackgroundResource(R.drawable.bg_badge_live)
                binding.provenance.setTextColor(getColor(R.color.live))
                binding.delay.text = "no halt reported yet"
                binding.delay.setTextColor(getColor(R.color.accent))
            } else {
                // The static-sounding labels were a fair complaint: a chip that says the same thing in
                // every state reads as stuck. Name the situation instead - the sources are being asked.
                binding.provenance.text = "SCHEDULE · sources unreachable"
                binding.delay.text = "—"
            }
        }
        if (ui.actualsHeadline != null) {
            binding.headline.text = ui.actualsHeadline
            binding.next.text = ui.actualsNext
            binding.provenance.text = ui.actualsBadge
            val late = ui.actualsLate ?: 0
            binding.provenance.setBackgroundResource(
                if (late > 2) R.drawable.bg_badge_problem else R.drawable.bg_badge_live,
            )
            binding.provenance.setTextColor(getColor(if (late > 2) R.color.late else R.color.live))
            binding.delay.text = when {
                late > 2 -> "$late min late"
                ui.actualsLate != null -> "on time"
                else -> m.delayLabel
            }
            ui.delayDetail?.let { binding.delay.text = "${binding.delay.text} · $it" }
            binding.delay.setTextColor(getColor(if (late > 2) R.color.late else R.color.live))
        }
        ui.sourcesLine?.let { binding.age.text = it }
        binding.note.visibility = if (m.note == null) View.GONE else View.VISIBLE
        m.note?.let { binding.note.text = it }

        stopAdapter.reports = ui.sourceLines[ui.activeSource] ?: ui.reports
        renderSourceChips(ui)
        stopAdapter.highlightCode = ui.anchorCode
        if (ui.reports.isNotEmpty()) {
            Diary.get()?.log("UISTATE") {
                "crowd on screen: " + ui.reports.entries.joinToString(" ; ") { it.key + " -> " + it.value }
            }
        }

        val anchorSeq = ui.anchorCode
            ?.let { ac -> m.rows.firstOrNull { it.code.equals(ac, true) }?.seq }
            ?: m.anchorSeq
        // Always the FULL route now: the owner removed the segment toggle - the screen points at the
        // train and the traveller scrolls for the rest, which the anchor scroll already does.
        val win = vm.windowFor(m.rows, anchorSeq, true)
        val from = win.first.coerceIn(0, (m.rows.size - 1).coerceAtLeast(0))
        val to = win.last.coerceIn(from, (m.rows.size - 1).coerceAtLeast(0))
        stopAdapter.submitList(m.rows.subList(from, to + 1)) {
            val anchor = ui.anchorCode
            if (anchor != null && anchor != lastAnchor) {
                val idx = m.rows.subList(from, to + 1).indexOfFirst { it.code.equals(anchor, true) }
                if (idx >= 0) {
                    lastAnchor = anchor
                    (binding.stops.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, 160)
                }
            }
        }
        val hidden = m.rows.size - (to - from + 1)
        binding.rowsNote.visibility = if (hidden > 0) View.VISIBLE else View.GONE
        if (hidden > 0) {
            binding.rowsNote.text = getString(R.string.rows_window, from + 1, to + 1, m.rows.size)
        }

        bindSource(m, ui)

        // The frontend captures itself (the owner's ask, 2026-10-12): what this screen shows goes
        // into the diary as elements, and the window photographs itself to the app's own private
        // files dir - a paste then carries the screens with zero screenshots taken by hand.
        Diary.get()?.log("UISTATE") {
            UiState.track(
                m.trainNo, m.headline, m.provenanceLabel, m.ageLabel, m.delayLabel,
                m.nextLabel, m.progressLabel, m.note, m.rows,
            )
        }
        UiShot.snap(this, "track")
    }

    /**
     * The provenance strip that used to sit under the buttons as `provider override – provider – cached –`
     * is now a disclosure, because it is diagnostic rather than decisional — but it stays reachable in two
     * taps, which is what makes a claim like "this came from cache" auditable instead of vibes.
     */
    private fun bindSource(m: TrackModel.View, ui: LiveViewModel.Ui) {
        binding.sourceText.text = sourceText(m, ui.tried)
    }

    /** Everything the screen knows about the feed, as text — see the long-press handler above. */
    private fun copyFeedReport() {
        val u = lastUi
        if (u == null) {
            Toast.makeText(this, R.string.feed_report_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val m = u.model
        val reported = m.rows.count { it.source == TrackModel.MinuteSource.REPORTED }
        val projected = m.rows.count { it.source == TrackModel.MinuteSource.PROJECTED }
        val timetable = m.rows.count { it.source == TrackModel.MinuteSource.SCHEDULE }
        val text = buildString {
            append("OpenTrack India live-feed report\n")
            // Written first on purpose. A report is only evidence about the build that produced it, and one
            // round of debugging was lost to a paste from a stale install that looked identical otherwise.
            val pi = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
            val diary = Diary.get()
            append(
                "build " + (pi?.versionName ?: "?") + " code " + (pi?.versionCode ?: -1) +
                    " · diagSchema " + Diary.SCHEMA +
                    " · log " + (if (diary?.enabled == true) "on" else "off") + "\n",
            )
            append("train ${m.trainNo} · ${m.runDateLabel} · seen ${RailTime.formatClock(System.currentTimeMillis())} IST\n")
            // Filtered rather than concatenated: an empty feed-stamp used to leave "· " hanging at the end of
            // the line, which is the sort of detail that makes a diagnostic look unfinished in a screenshot.
            append(listOf(
                "source " + m.provenanceLabel,
                "age " + m.ageLabel,
                m.feedStampLabel,
            ).filter { it.isNotBlank() }.joinToString(" · "))
            append("\n")
            append("delay ${m.delayLabel} · next ${m.nextLabel} · progress ${m.progressLabel}\n")
            append("rows ${m.rows.size}: $reported reported / $projected projected / $timetable timetable\n")
            append("providers ${u.tried.joinToString(" ")}\n")
            m.note?.let { append("note $it\n") }
            // The device-run request experiment, if it has run. This is the line that turns "it does not work
            // here" into a fixable report from one paste, so it is printed before the table.
            val probe = FeedProbe.render()
            if (probe.isNotEmpty()) append(probe).append("\n")
            append("as shown on screen (up to 8 halts):\n")
            for (r in m.rows.take(8)) {
                append("  ${r.name} [${r.state}] arr ${hhmm(r.showArr)} dep ${hhmm(r.showDep)}")
                append(" ${r.source.name.lowercase()}")
                r.delay?.let { append(" +$it") }
                r.platform?.let { append(" plt $it") }
                append(" km ${r.kmFromBoard}\n")
            }
        }
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("opentrack-feed-report", text))
        }
        Log.i(TAG_FEED_REPORT, text)
        Toast.makeText(this, R.string.feed_report_copied, Toast.LENGTH_SHORT).show()
    }

    /** Rail minutes to HH:MM, with the same fold the table uses, and a dash where the feed said nothing. */
    private fun hhmm(min: Int?): String {
        if (min == null) return "-"
        val folded = ((min % 1440) + 1440) % 1440
        return (folded / 60).toString().padStart(2, '0') + ":" + (folded % 60).toString().padStart(2, '0')
    }

    override fun onStart() {
        super.onStart()
        vm.start()
    }

    override fun onStop() {
        vm.stop()
        super.onStop()
    }

    companion object {
        /**
         * What the disclosure says. It is a companion function rather than a line of the Activity because the
         * test-source render harness has to paint the same sentence: while it was written out twice, the two
         * drifted, and a preview of this app claimed the provenance strip listed the size of the shipped
         * timetable, which it never does. One composition, so a screenshot cannot lie.
         */
        fun sourceText(m: TrackModel.View, tried: List<String>): String = buildString {
            append("Position source: ${m.provenanceLabel} · ${m.ageLabel}")
            // The provider's own clock, when it publishes one. Kept out of the age chip because that chip is
            // a few centimetres wide on a phone held at arm's length, and a clipped timestamp is worse than
            // a missing one.
            if (m.feedStampLabel.isNotEmpty()) append(" · ${m.feedStampLabel}")
            append("\nProviders tried this refresh: ")
            append(
                if (tried.isEmpty()) "none — answered from the on-device timetable"
                else tried.joinToString(", ") { it.replace("_", " ").lowercase() }
            )
            append("\n${m.runDateLabel} · ${m.rows.size} stops loaded · ")
            append(if (m.hasDistances) "distances from the offline reference data"
            else "no distance data for this train")
            // Said here rather than in Settings because this card is where the app admits what it could not
            // get, and the gesture that turns that admission into a report someone can act on has to be
            // mentioned exactly once, in the place where the user is already reading about the feed.
            append("\nLong-press the badge above to copy all of this as a report")
        }

        /** The tag the CI rows and a bug report both grep for. */
        const val TAG_FEED_REPORT = "LiveFeedReport"
        const val EXTRA_TRAIN_NO = "trainNo"
        const val EXTRA_FROM = "fromCode"
        const val EXTRA_TO = "toCode"
        const val EXTRA_ORIGIN_EPOCH_DAY = "originEpochDay"
    }
}
