package `in`.opentrack.india.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import `in`.opentrack.india.App
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
        wireReporter()
        binding.stops.layoutManager = LinearLayoutManager(this)
        binding.stops.adapter = stopAdapter
        binding.stops.isNestedScrollingEnabled = false

        binding.refresh.setOnClickListener { vm.refresh(manual = true) }
        binding.toggle.setOnClickListener {
            fullRoute = !fullRoute
            binding.toggle.setText(
                if (fullRoute) R.string.show_my_segment else R.string.show_full_route
            )
            lastUi?.let { bind(it) }
        }
        binding.autoBtn.setOnClickListener {
            autoOn = !autoOn
            vm.setAutoRefresh(autoOn)
            binding.autoBtn.text = getString(
                if (autoOn) R.string.auto_on else R.string.auto_off
            )
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
        vm.reportStatus.observe(this) { msg ->
            binding.reportStatus.visibility = if (msg == null) View.GONE else View.VISIBLE
            binding.reportStatus.text = msg
        }
        vm.checking.observe(this) { busy ->
            // The 1800s complaint, answered: a moving bar for exactly as long as the app is working,
            // and a captioned spinner while the very first look is still in flight.
            binding.checkingBar.visibility = if (busy == true) View.VISIBLE else View.GONE
            binding.firstLoad.visibility = if (busy == true && lastUi == null) View.VISIBLE else View.GONE
        }
        vm.error.observe(this) { msg ->
            errorText = msg
            renderNote()
        }
        binding.toggle.setText(R.string.show_full_route)
        binding.autoBtn.setText(R.string.auto_on)
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
     * ADR-006's controls, present but inert until the user asks for them (the pref is off by default):
     * a toggle reveals the box, the spinner names the stop, one button per fact, and the loopback - the
     * pipe's proof on a ct-test topic, never a real train's.
     */
    private val prefs by lazy { `in`.opentrack.india.data.Prefs(this) }

    private fun wireReporter() {
        renderReporter(null)
        binding.reportToggle.setOnClickListener {
            prefs.crowdReports = !prefs.crowdReports
            renderReporter(null)
        }
        binding.reportStop.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {}
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        binding.reportArr.setOnClickListener {
            val code = binding.reportStop.selectedItem as? String ?: return@setOnClickListener
            vm.reportAt(code, `in`.opentrack.india.v2.crowd.StopReport.Kind.ARRIVED)
        }
        binding.reportDep.setOnClickListener {
            val code = binding.reportStop.selectedItem as? String ?: return@setOnClickListener
            vm.reportAt(code, `in`.opentrack.india.v2.crowd.StopReport.Kind.DEPARTED)
        }
        binding.reportTest.setOnClickListener { vm.loopbackTest() }
    }

    /** Box and toggle text follow the pref; the spinner follows the route once per train. */
    private fun renderReporter(ui: LiveViewModel.Ui?) {
        val on = prefs.crowdReports
        binding.reportToggle.setText(if (on) R.string.report_toggle_off else R.string.report_toggle_on)
        binding.reportBox.visibility = if (on) View.VISIBLE else View.GONE
        // The pipe is invisible until a report exists - so say it is listening. The relay answered on
        // the owner's phone (paste #15) with zero reports, because zero travellers exist yet; the
        // caption names that state honestly and disappears the moment real lines render per row.
        val hasLines = ui?.reports?.isNotEmpty() == true
        binding.reportHint.visibility = if (on && !hasLines) View.VISIBLE else View.GONE
        if (on && !hasLines) binding.reportHint.setText(R.string.report_listening)
        val route = ui?.route ?: return
        if (binding.reportStop.tag != route.trainNo) {
            binding.reportStop.tag = route.trainNo
            val codes = route.stops.map { it.code }
            binding.reportStop.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item, codes,
            )
            val board = ui.model.rows.indexOfFirst { it.isBoarding }
            if (board >= 0) binding.reportStop.setSelection(board.coerceAtMost(codes.size - 1))
        }
    }

    private fun bind(ui: LiveViewModel.Ui) {
        val m = ui.model
        binding.trainNo.text = m.trainNo
        binding.routeLabel.text = m.routeLabel
        binding.runDate.text = m.runDateLabel
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
        binding.note.visibility = if (m.note == null) View.GONE else View.VISIBLE
        m.note?.let { binding.note.text = it }

        binding.progressLabel.text = m.progressLabel
        binding.progressBar.pct = m.progressPct
        binding.progressBar.schedulePct = m.schedulePct
        binding.progressBar.live = m.isLive

        stopAdapter.reports = ui.reports
        if (ui.reports.isNotEmpty()) {
            Diary.get()?.log("UISTATE") {
                "crowd on screen: " + ui.reports.entries.joinToString(" ; ") { it.key + " -> " + it.value }
            }
        }
        renderReporter(ui)

        val win = vm.windowFor(m.rows, m.anchorSeq, fullRoute)
        val from = win.first.coerceIn(0, (m.rows.size - 1).coerceAtLeast(0))
        val to = win.last.coerceIn(from, (m.rows.size - 1).coerceAtLeast(0))
        stopAdapter.submitList(m.rows.subList(from, to + 1))
        val hidden = m.rows.size - (to - from + 1)
        binding.rowsNote.visibility = if (hidden > 0) View.VISIBLE else View.GONE
        if (hidden > 0) {
            binding.rowsNote.text = getString(R.string.rows_window, from + 1, to + 1, m.rows.size)
        }

        // The plot always shows the passenger's own segment: it answers "is it late on my stretch", and
        // re-centring it on a route the traveller never rides would make the gap unreadable.
        val segStart = m.rows.indexOfFirst { it.isBoarding }.coerceAtLeast(0)
        val segEnd = m.rows.indexOfLast { it.isAlighting }.let { if (it < segStart) m.rows.size - 1 else it }
        binding.chart.setJourney(
            m, ui.answer.position, ui.nowRailMinute,
            segStart..segEnd.coerceAtLeast(segStart + 1),
        )
        binding.chartLegend.text = if (m.isLive) {
            "dashed = timetable · solid = reported position · amber tick on the bar = where the timetable puts it now"
        } else {
            "amber dotted = what the timetable implies at this minute, not a report about the train"
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
