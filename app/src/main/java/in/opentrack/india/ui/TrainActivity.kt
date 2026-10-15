package `in`.opentrack.india.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.AdapterView
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.Toast
import android.view.View
import `in`.opentrack.india.domain.RouteBetween
import androidx.appcompat.app.AlertDialog
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
    /** A new anchor scrolls once; a manual successful refresh scrolls even at the SAME anchor. */
    private var lastAnchor: String? = null
    private var focusAfterRefresh = false
    private var receiptReady = false
    private var refreshStartedAt = 0L
    private val hideFeedback = Runnable { binding.refreshFeedback.visibility = View.GONE }
    // Age is a local clock display, NOT a network poll or a projected train movement.
    private val ageTicker = object : Runnable {
        override fun run() {
            lastUi?.let(::updateTableMarker)
            binding.stops.postDelayed(this, 30_000L)
        }
    }
    private var sourceDetails = "No sources checked yet."
    private var spinnerStates: Map<String, Pair<String, String>> = emptyMap()
    private var lastUi: LiveViewModel.Ui? = null
    private var chainBusy = false
    private var mirrorBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trainNo = intent.getStringExtra(EXTRA_TRAIN_NO).orEmpty()
        if (trainNo.length < 4) { finish(); return }
        binding = ActivityTrainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.back.setText(R.string.back_to_trains)

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

        binding.refresh.setOnClickListener {
            focusAfterRefresh = true
            refreshStartedAt = SystemClock.elapsedRealtime()
            vm.refresh(manual = true)
            // Refresh the source the traveller actually selected, too. An old cache
            // candidate must not masquerade as a new observation after an NTES tick.
            if (lastUi?.activeSource == `in`.opentrack.india.v2.mirror.WimtCandidate.SOURCE)
                vm.probeSource(`in`.opentrack.india.v2.mirror.WimtCandidate.SOURCE)
        }
        vm.liveReceipt.observe(this) {
            if (focusAfterRefresh) {
                receiptReady = true
                binding.stops.post {
                    if (receiptReady) {
                        focusCurrentLeg()
                        receiptReady = false
                        focusAfterRefresh = false
                    }
                }
            }
        }
        binding.sourceToggle.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Sources · ${BuildStamp.ROUND}")
                .setMessage(sourceDetails)
                .setPositiveButton("Close", null)
                .show()
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
            val wasBusy = chainBusy
            chainBusy = busy == true
            renderChecking()
            // A timeout can finish without a live receipt. Still centre on the
            // last *reported* anchor after this manual request, even if unchanged.
            if (wasBusy && !chainBusy && refreshStartedAt > 0L) {
                // A fast live receipt may already have cleared focusAfterRefresh. The
                // latency receipt belongs to this button press either way.
                showRefreshFeedback()
                refreshStartedAt = 0L
            }
            if (wasBusy && !chainBusy && focusAfterRefresh) binding.stops.post {
                focusCurrentLeg()
                focusAfterRefresh = false
            }
        }
        // The sources box: fetching while the witnesses work. Once actuals exist, the line goes quiet -
        // the top card then carries the same sentence (badge + source row + refreshed stamp), and one
        // copy on screen is the owner's round-47 point: everything read at once, nothing said twice.
        vm.mirrorState.observe(this) { st ->
            mirrorBusy = st?.startsWith("Checking") == true || st?.startsWith("Already checking") == true
            renderChecking()
            val useful = st != null && !st.startsWith("Actuals ·") &&
                !st.startsWith("Official NTES answered")
            binding.mirrorState.visibility = if (useful) View.VISIBLE else View.GONE
            if (useful) binding.mirrorState.text = simpleSourceStatus(st!!)
            st?.let { Diary.get()?.log("UISTATE") { "sources box: $it" } }
        }
        vm.error.observe(this) { msg ->
            errorText = msg
            renderNote()
        }
    }

    /** Keep progress visible only while a requested source is actually fetching. */
    private fun renderChecking() {
        val busy = chainBusy || mirrorBusy
        binding.checkingBar.visibility = if (busy) View.VISIBLE else View.GONE
        binding.firstLoad.visibility = if (busy && lastUi == null) View.VISIBLE else View.GONE
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
    private fun sourceColor(tone: SourceIndicator.Tone): Int = when (tone) {
        SourceIndicator.Tone.READY -> R.color.accent
        SourceIndicator.Tone.UNAVAILABLE -> R.color.late
        SourceIndicator.Tone.OLD -> R.color.warn
        SourceIndicator.Tone.PENDING -> R.color.text2
    }

    private fun renderSourceChips(ui: LiveViewModel.Ui) {
        val names = ui.sourceLines.keys.toList()
        binding.sourceSpinner.visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
        if (names.isEmpty()) return
        spinnerStates = ui.sourceStates
        // State updates recolor the existing adapter. Rebuilding the Spinner for every
        // background answer can fire a selection callback for its default first item,
        // silently changing the user's chosen source back to NTES.
        if (binding.sourceSpinner.tag != names) {
            binding.sourceSpinner.tag = names
            binding.sourceSpinner.adapter = object : ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, names,
            ) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    val view = super.getView(position, convertView, parent)
                    (view as TextView).apply {
                        val (state, detail) = spinnerStates[names[position]] ?: ("none" to "checking on first load")
                        val indicator = SourceIndicator.of(names[position], state, detail)
                        text = "● ${sourceName(names[position])}  ▾"
                        textSize = 14f
                        maxLines = 1
                        setTextColor(getColor(sourceColor(indicator.tone)))
                    }
                    return view
                }

                override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    val view = super.getDropDownView(position, convertView, parent)
                    val name = names[position]
                    val (state, detail) = spinnerStates[name] ?: ("none" to "checking on first load")
                    val indicator = SourceIndicator.of(name, state, detail)
                    (view as TextView).apply {
                        val density = resources.displayMetrics.density
                        setPadding((12 * density).toInt(), (12 * density).toInt(),
                            (12 * density).toInt(), (12 * density).toInt())
                        textSize = 14f
                        text = "● ${sourceName(name)} · ${indicator.label}"
                        setTextColor(getColor(sourceColor(indicator.tone)))
                    }
                    return view
                }
            }
            binding.sourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val name = names.getOrNull(pos) ?: return
                    if (name != lastUi?.activeSource) {
                        if (spinnerStates[name]?.first !in listOf("live", "last-known", "candidate")) {
                            Toast.makeText(this@TrainActivity,
                                "Checking ${sourceName(name)}…",
                                Toast.LENGTH_SHORT).show()
                            val previous = names.indexOf(lastUi?.activeSource)
                            if (previous >= 0) binding.sourceSpinner.setSelection(previous, false)
                            vm.probeSource(name)
                        } else vm.setActiveSource(name)
                    }
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        (binding.sourceSpinner.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
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
        binding.lastUpdated.visibility = View.GONE // age is already in the badge; timestamp is in Info
        binding.lastUpdated.text = ui.lastUpdated
        val publicCheck = ui.activeSource?.takeIf {
            it in `in`.opentrack.india.v2.mirror.PublicPageChecks.names
        }
        val mirrorWithoutTimes = ui.activeSource?.let { selected ->
            selected in setOf(`in`.opentrack.india.v2.mirror.ConfirmTktMirror.SOURCE,
                `in`.opentrack.india.v2.mirror.RunningStatusMirror.SOURCE, "erail") &&
                ui.sourceStates[selected]?.first != "live"
        } == true
        // One explanation only: the qualified site's badge + labelled table already state
        // provenance. Show this line only when its selected source has no usable point.
        binding.sourceSummary.visibility = if (
            publicCheck != null && ui.orgWebsiteReport == null && ui.railYatriClaim == null ||
            ui.activeSource == `in`.opentrack.india.v2.mirror.WimtCandidate.SOURCE &&
                ui.wimtWebPoint == null || mirrorWithoutTimes
        ) View.VISIBLE else View.GONE
        binding.sourceSummary.text = when {
            ui.orgWebsiteReport != null -> "Site's past-stop claims; not confirmed railway actuals."
            ui.wimtWebPoint != null -> "Site distance claim; not phone GPS."
            publicCheck != null -> "No confirmed times from ${sourceName(publicCheck)}; timetable shown."
            mirrorWithoutTimes -> "No confirmed times from ${sourceName(ui.activeSource.orEmpty())}; timetable shown."
            else -> "No confirmed position from this site; timetable shown."
        }
        binding.headline.text = m.headline
        // No NTES time-interpolated icon in position-only mode. A separate amber
        // marker can scale a fresh, explicitly UNVERIFIED WIMT web-cache km claim
        // onto two official scheduled halts; it never invents an ETA or Act cell.
        val leg = ui.wimtWebPoint?.let { p ->
            RouteBetween.webCacheLeg(ui.route, ui.staticRoute,
                p.precedingHalt, p.followingHalt, p.routeKm)
        }
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
        if (ui.actualsHeadline == null && !m.isLive) {
            val savedReport = ui.answer.position.provenance ==
                `in`.opentrack.india.model.SourceKind.CACHED &&
                m.rows.any { it.source == TrackModel.MinuteSource.REPORTED }
            if (savedReport) {
                // A timeout did not erase the historical official observation. It is
                // last-known, never today's *current* location or a new live receipt.
                binding.headline.text = m.headline
                binding.provenance.text = m.provenanceLabel
                binding.age.text = m.ageLabel
                binding.delay.text = "Last reported: ${m.delayLabel}"
                binding.next.text = m.nextLabel
            } else {
                binding.headline.text = "Live position unverified — timetable only"
                binding.provenance.text = "SCHEDULE · no verified actuals"
                binding.age.text = ui.lastUpdated ?: "Not checked yet"
                binding.delay.text = "— · no verified delay"
                val nextSchedule = m.rows.firstOrNull {
                    (it.schedArr ?: it.schedDep ?: Int.MIN_VALUE) > ui.nowRailMinute
                }
                binding.next.text = nextSchedule?.let {
                    "Scheduled: ${it.name} · " +
                        (it.schedArr ?: it.schedDep)?.let(RailTime::formatRailMinute) +
                        " (not a live ETA)"
                } ?: "No live next-stop ETA"
            }
            binding.provenance.setBackgroundResource(R.drawable.bg_badge_schedule)
            binding.provenance.setTextColor(getColor(R.color.warn))
            binding.delay.setTextColor(getColor(R.color.warn))
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
        if (ui.actualsHeadline != null) ui.sourcesLine?.let { binding.age.text = it }
        ui.wimtWebPoint?.let { point ->
            val pointName = ui.staticRoute?.stops?.firstOrNull {
                it.code.equals(point.code, true) }?.name ?: point.code
            val nextHalt = ui.route.stops.firstOrNull {
                it.code.equals(point.followingHalt, true) }
            // This is a separately dated *web-cache claim*, not a GPS coordinate or
            // a future arrival. The old official Act cells are deliberately not shown
            // while this source is selected (buildUi constructs a static-only table).
            val webKm = String.format(java.util.Locale.ROOT, "%.1f", point.routeKm)
            binding.headline.text = "WIMT web reports near $pointName · ≈$webKm route km"
            binding.provenance.text = "WIMT WEB · position candidate"
            binding.provenance.setBackgroundResource(R.drawable.bg_badge_schedule)
            binding.provenance.setTextColor(getColor(R.color.warn))
            binding.age.text = "point time ${RailTime.formatClock(point.eventAtMillis)} IST · " +
                "cache checked ${RailTime.formatClock(point.cacheAtMillis)}"
            binding.delay.text = "— · not verified"
            binding.delay.setTextColor(getColor(R.color.warn))
            binding.next.text = nextHalt?.let { halt ->
                "Next scheduled: ${halt.name} · " +
                    ((halt.arrivalMin ?: halt.departureMin)?.let(RailTime::formatRailMinute) ?: "—") +
                    " · arrival not confirmed"
            } ?: "Next halt not mapped; no live ETA"
        }
        if (publicCheck != null) {
            val claim = ui.railYatriClaim
            val org = ui.orgWebsiteReport?.takeIf { publicCheck ==
                `in`.opentrack.india.v2.mirror.PublicPageChecks.ORG }
            val station = if (org != null) ui.route.stops.firstOrNull {
                it.code.equals(org.anchorCode, true) }
                else claim?.let { c -> ui.route.stops.firstOrNull { it.code.equals(c.code, true) } }
            binding.headline.text = when {
                org != null && station != null -> "Site reports " +
                    (if (org.departed) "departed" else "arrived at") + " ${station.name} · " +
                    "${RailTime.formatRailMinute(org.eventMinute)} · current position unknown"
                claim != null && station != null ->
                    "RailYatri reports ${claim.status} ${station.name} · passage time unknown"
                else -> "${sourceName(publicCheck)}: no qualifying past-stop report for this run"
            }
            binding.provenance.text = when {
                org != null -> "WEBSITE · past-stop claims, unverified"
                claim != null -> "RAILYATRI · station claim only"
                else -> "${sourceName(publicCheck)} · comparison only"
            }
            binding.provenance.setBackgroundResource(R.drawable.bg_badge_schedule)
            binding.provenance.setTextColor(getColor(R.color.warn))
            binding.age.text = when {
                org != null -> "Site updated ${RailTime.formatClock(org.pageUpdatedAt)} IST · not the event time · run day unconfirmed"
                claim != null -> "provider updated ${RailTime.formatClock(claim.updateAt)} IST · upstream ${claim.upstream} · not an event time"
                else -> "No qualifying past-stop report from selected source"
            }
            binding.delay.text = if (org != null) "— · no independently verified delay"
                else "— · no verified delay from ${sourceName(publicCheck)}"
            binding.delay.setTextColor(getColor(R.color.warn))
            binding.next.text = if (station != null) {
                val next = ui.route.stops.getOrNull(ui.route.stops.indexOf(station) + 1)
                next?.let { "Next scheduled: ${it.name} · " +
                    ((it.arrivalMin ?: it.departureMin)?.let(RailTime::formatRailMinute) ?: "—") +
                    " · not a live ETA" } ?: "No next scheduled halt"
            } else "No source-confirmed next stop · timetable below"
        }
        binding.note.visibility = if (m.note == null) View.GONE else View.VISIBLE
        m.note?.let { binding.note.text = it }

        // Page notes stay in Info; the in-table icon and source badge already describe
        // the selected site's qualified claim without repeating an excerpt at the halt.
        stopAdapter.reports = ui.sourceLines[ui.activeSource] ?: ui.reports
        stopAdapter.setRoutePoints(RouteBetween.groupedPoints(ui.staticRoute, ui.route))
        stopAdapter.legAgeSeconds = ui.wimtWebPoint?.let {
            ((System.currentTimeMillis() - it.eventAtMillis) / 1_000).coerceAtLeast(0)
        }
        stopAdapter.setProjectedLeg(leg)
        renderSourceChips(ui)
        updateTableMarker(ui)
        stopAdapter.highlightCode = stopAdapter.haltMarker?.code ?: ui.anchorCode
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
            if (anchor != null && anchor != lastAnchor) focusCurrentLeg()
            if (receiptReady) {
                focusCurrentLeg()
                receiptReady = false
                focusAfterRefresh = false
            }
        }
        val hidden = m.rows.size - (to - from + 1)
        binding.rowsNote.visibility = if (hidden > 0) View.VISIBLE else View.GONE
        if (hidden > 0) {
            binding.rowsNote.text = getString(R.string.rows_window, from + 1, to + 1, m.rows.size)
        }

        bindSource(ui)

        // The frontend captures itself (the owner's ask, 2026-10-12): what this screen shows goes
        // into the diary as elements, and the window photographs itself to the app's own private
        // files dir - a paste then carries the screens with zero screenshots taken by hand.
        Diary.get()?.log("UISTATE") {
            UiState.track(
                m.trainNo, binding.headline.text.toString(), binding.provenance.text.toString(),
                binding.age.text.toString(), binding.delay.text.toString(),
                binding.next.text.toString(), m.progressLabel, m.note, m.rows,
            )
        }
        UiShot.snap(this, "track")
    }

    /** One useful line per source; full machine diagnostics remain on badge long-press. */
    private fun bindSource(ui: LiveViewModel.Ui) {
        sourceDetails = buildString {
            val p = ui.wimtWebPoint
            if (p != null) append("Act = reported, not a web-cache row. " +
                "Position: WIMT web-cache candidate ${p.code} at ${p.routeKm} route km, " +
                "point time ${RailTime.formatClock(p.eventAtMillis)} IST; " +
                "cache clock is not movement. Not WIMT phone-app GPS or verified Act.\n\n")
            else append(sourceText(ui.model)).append("\n\n")
            ui.sourceStates.forEach { (name, status) ->
                append(sourceName(name)).append(": ")
                // Keep long source diagnostics in the copied diary, not in the tracking UI.
                append(when (status.first) {
                    "live" -> "reported times available"
                    "candidate" -> if (name == `in`.opentrack.india.v2.mirror.WimtCandidate.SOURCE &&
                        ui.wimtWebPoint != null) "recent unverified web point; stop times are static"
                        else "web-cache notes only; not verified actuals"
                    "none", "unverified" -> {
                        val rows = Regex("(\\d+) (?:page )?rows").find(status.second)?.groupValues?.get(1)
                        if (status.second.contains("Request failed", ignoreCase = true))
                            "couldn't connect; try again on a stronger connection"
                        else if (rows != null) "$rows page rows, no confirmed stop times" else
                            if (status.second.contains("not answered") ||
                                status.second.contains("manual check available")) "tap to check"
                            else "no verified times available"
                    }
                    else -> "last known; not a new report"
                })
                Regex("checked in (\\d+) ms").find(status.second)?.groupValues?.get(1)?.let {
                    append(" · ").append(it).append(" ms")
                }
                append('\n')
            }
            append("\nWIMT web cache is not the WIMT phone app. ")
            append("Site claims are not verified actuals. Station distances can differ between maps.")
            ui.lastUpdated?.let { append("\n").append(it) }
        }
    }

    private fun sourceName(name: String): String = when (name) {
        `in`.opentrack.india.v2.mirror.WimtCandidate.SOURCE -> "WIMT website"
        "NTES (official)" -> "NTES · official"
        else -> name
    }

    private fun simpleSourceStatus(status: String): String = when {
        status.startsWith("Checking") || status.startsWith("Already checking") -> "Checking source…"
        status.startsWith("No verified actual") ||
            status.contains("0 independently corroborated") || status.contains("0 verified") ->
            "No independently confirmed times; current view stays."
        status.startsWith("WIMT") -> "WIMT web checked; any position shown is unverified."
        else -> "Source checked. Open Info for details."
    }

    private fun updateTableMarker(ui: LiveViewModel.Ui) {
        val rows = ui.model.rows
        val fromOfficial = ui.activeSource in listOf("NTES (official)", "erail", "last known")
        val anchor = ui.anchorCode ?: if (fromOfficial) ui.answer.anchorStopCode else null
        val now = System.currentTimeMillis()
        val atAnchor = if (ui.wimtWebPoint == null)
            TableMarker.atHalt(rows, anchor, vm.originDate, now) else null
        stopAdapter.haltMarker = atAnchor ?: if (fromOfficial)
            rows.lastOrNull { it.source == TrackModel.MinuteSource.REPORTED }?.let {
                TableMarker.atHalt(rows, it.code, vm.originDate, now)
            } else null
        ui.wimtWebPoint?.let { stopAdapter.legAgeSeconds = ((now - it.eventAtMillis) / 1_000).coerceAtLeast(0) }
    }

    private fun showRefreshFeedback() {
        val elapsed = (SystemClock.elapsedRealtime() - refreshStartedAt).coerceAtLeast(0L)
        val u = lastUi
        val ageSeconds = u?.answer?.position?.feedAgeSeconds ?: u?.answer?.position?.ageSeconds
        val age = if (u?.answer?.position?.provenance?.live == true && ageSeconds != null)
            "NTES feed ${ageSeconds / 60}m old" else "no new official report"
        binding.refreshFeedback.text = "↻ ${elapsed} ms · $age"
        binding.refreshFeedback.visibility = View.VISIBLE
        binding.refreshFeedback.removeCallbacks(hideFeedback)
        binding.refreshFeedback.postDelayed(hideFeedback, 1_200L)
    }

    /** Bring the current reported halt/leg to the top without manufacturing a position. */
    private fun focusCurrentLeg() {
        val ui = lastUi ?: return
        val anchor = stopAdapter.haltMarker?.code ?: ui.wimtWebPoint?.precedingHalt ?: return
        val idx = stopAdapter.currentList.indexOfFirst { it.code.equals(anchor, true) }
        if (idx < 0) return
        lastAnchor = anchor
        (binding.stops.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(
            idx, resources.getDimensionPixelSize(R.dimen.gap_s))
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
        val website = m.rows.filter { it.source == TrackModel.MinuteSource.SITE_REPORTED }
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
            u.wimtWebPoint?.let { p -> append("WIMT WEB CACHE CANDIDATE " +
                "${p.code} ${p.routeKm}km event ${RailTime.formatClock(p.eventAtMillis)} " +
                "cache ${RailTime.formatClock(p.cacheAtMillis)} IST; not phone-app GPS or verified Act\n") }
            // Filtered rather than concatenated: an empty feed-stamp used to leave "· " hanging at the end of
            // the line, which is the sort of detail that makes a diagnostic look unfinished in a screenshot.
            append(listOf(
                "source " + (if (u.wimtWebPoint != null) "WIMT web candidate, unverified"
                    else m.provenanceLabel),
                "age " + (u.wimtWebPoint?.let { p ->
                    "web event ${RailTime.formatClock(p.eventAtMillis)}; " +
                        "cache checked ${RailTime.formatClock(p.cacheAtMillis)} IST"
                } ?: m.ageLabel),
                if (u.wimtWebPoint != null) "" else m.feedStampLabel,
            ).filter { it.isNotBlank() }.joinToString(" · "))
            append("\n")
            append("delay ${m.delayLabel} · next ${m.nextLabel} · progress ${m.progressLabel}\n")
            append("rows ${m.rows.size}: $reported reported / ${website.size} website (unverified) / " +
                "$projected projected / $timetable timetable\n")
            u.orgWebsiteReport?.let { p ->
                append("website last past-card ${p.anchorCode} at " +
                    "${RailTime.formatRailMinute(p.eventMinute)}; page updated " +
                    "${RailTime.formatClock(p.pageUpdatedAt)} IST (not event clock); " +
                    "origin run inferred, not printed on page\n")
                for (row in website.takeLast(4)) append("  ${row.code} Web " +
                    "arr ${hhmm(row.showArr)} dep ${hhmm(row.showDep)} " +
                    "pf ${row.platform ?: "–"} vs " +
                    "Sch ${hhmm(row.schedArr)}/${hhmm(row.schedDep)}\n")
            }
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
        binding.stops.postDelayed(ageTicker, 30_000L)
    }

    override fun onStop() {
        binding.stops.removeCallbacks(ageTicker)
        binding.refreshFeedback.removeCallbacks(hideFeedback)
        super.onStop()
    }

    companion object {
        /** Plain-language key for the optional source explanation, separate from feed diagnostics. */
        fun sourceText(m: TrackModel.View): String = buildString {
            append("Act = reported. Est = predicted, not an arrival report. ")
            append("Site = site's past-stop claim, not independently verified Act or GPS. ")
            append("A blue train, if shown, is projected, not GPS. An amber WIMT web marker " +
                "is an unverified cache-distance claim, not phone-app GPS.\n")
            append("Position: ${m.provenanceLabel} · ${m.ageLabel}")
            if (m.feedStampLabel.isNotEmpty()) append(" · ${m.feedStampLabel}")
            append("\nLong-press the source badge to copy a detailed report.")
        }

        /** The tag the CI rows and a bug report both grep for. */
        const val TAG_FEED_REPORT = "LiveFeedReport"
        const val EXTRA_TRAIN_NO = "trainNo"
        const val EXTRA_FROM = "fromCode"
        const val EXTRA_TO = "toCode"
        const val EXTRA_ORIGIN_EPOCH_DAY = "originEpochDay"
    }
}
