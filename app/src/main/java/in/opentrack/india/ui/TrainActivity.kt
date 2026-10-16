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
    private var requestedRefreshSource = "NTES (official)"
    /** A completed HTTP request is not a new train event. Compare station+minute,
     * not the site's page-update clock or the time of our own button tap. */
    private var previousRefreshEvent: String? = null
    private fun eventKey(ui: LiveViewModel.Ui?, source: String): String? {
        if (ui == null) return null
        if (source == `in`.opentrack.india.v2.mirror.PublicPageChecks.ORG)
            return ui.orgWebsiteReport?.let { "${it.anchorCode}:${it.eventMinute}" }
        if (source == `in`.opentrack.india.v2.mirror.WimtCandidate.SOURCE)
            return ui.wimtWebPoint?.let { "${it.precedingHalt}:${it.routeKm}:${it.eventAtMillis}" }
        val last = ui.model.rows.lastOrNull { it.source == TrackModel.MinuteSource.REPORTED &&
            (it.showArr != null || it.showDep != null) }
        return last?.let { "${it.code}:${it.showArr}:${it.showDep}" }
    }
    private var screenStarted = false
    /** Opt-in, screen-visible only. Uses precisely the selected-source-first manual chain. */
    private val autoRefreshTicker = object : Runnable {
        override fun run() {
            if (!screenStarted || !binding.autoSwitch.isChecked) return
            if (!chainBusy) requestRefresh(auto = true)
            binding.root.postDelayed(this, 60_000L)
        }
    }
    // Age is a local clock display, NOT a network poll or a projected train movement.
    private val ageTicker = object : Runnable {
        override fun run() {
            lastUi?.let(::updateTableMarker)
            binding.stops.postDelayed(this, 30_000L)
        }
    }
    private var sourceDetails = "No sources checked yet."
    private var spinnerStates: Map<String, Pair<String, String>> = emptyMap()
    private var sourceSelectionTouched = false
    private var sourceSelectionTouchedAt = 0L
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
        binding.sourceSpinner.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                sourceSelectionTouched = true
                sourceSelectionTouchedAt = SystemClock.elapsedRealtime()
            }
            false
        }

        binding.refresh.setOnClickListener { requestRefresh() }
        val refreshPrefs = getSharedPreferences("tracking_refresh", Context.MODE_PRIVATE)
        binding.autoSwitch.isChecked = refreshPrefs.getBoolean("every_minute", false)
        binding.autoSwitch.setOnCheckedChangeListener { _, enabled ->
            refreshPrefs.edit().putBoolean("every_minute", enabled).apply()
            binding.root.removeCallbacks(autoRefreshTicker)
            if (enabled && screenStarted) binding.root.postDelayed(autoRefreshTicker, 60_000L)
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
                .setMessage(sourceDetails + "\n\nOn the route: the train stays at the last reported " +
                    "stop unless a source supplies a qualified distance. Small ticks use static map " +
                    "kilometres; they are not stop reports. Late minutes are shown in the top card; " +
                    "the distance ruler has the same shape for every source. A reported " +
                    "departure cannot tell whether the train is now running, at another stop, " +
                    "or halted between stops. A phone app's on-board location signal is " +
                    "not the same as its public web page.")
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
            val useful = st != null && (st.startsWith("Checking") ||
                st.startsWith("Already checking"))
            binding.mirrorState.visibility = if (useful) View.VISIBLE else View.GONE
            if (useful) binding.mirrorState.text = simpleSourceStatus(st!!)
            st?.let { Diary.get()?.log("UISTATE") { "sources box: $it" } }
        }
        vm.error.observe(this) { msg ->
            errorText = msg
            renderNote()
        }
    }

    private fun requestRefresh(auto: Boolean = false) {
        if (chainBusy) return // never turn a duplicate tap/tick into a misleading receipt
        focusAfterRefresh = true
        refreshStartedAt = SystemClock.elapsedRealtime()
        requestedRefreshSource = lastUi?.activeSource ?: "NTES (official)"
        previousRefreshEvent = eventKey(lastUi, requestedRefreshSource)
        binding.refreshFeedback.visibility = View.GONE
        vm.refresh(manual = true, auto = auto)
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
        SourceIndicator.Tone.CANDIDATE -> R.color.warn
        SourceIndicator.Tone.UNAVAILABLE -> R.color.late
        SourceIndicator.Tone.OLD -> R.color.warn
        SourceIndicator.Tone.PENDING -> R.color.text2
    }

    private fun renderSourceChips(ui: LiveViewModel.Ui) {
        spinnerStates = ui.sourceStates
        // A source can finish its first-load probe while the dropdown is open. Replacing
        // its adapter then dismisses/reselects the popup and can swallow the user's tap.
        // Freeze the visible order until onItemSelected consumes that touch; freshness
        // colours/times still update in the existing adapter. Rank again afterwards.
        val touchFresh = sourceSelectionTouched &&
            SystemClock.elapsedRealtime() - sourceSelectionTouchedAt in 0L..30_000L
        if (!touchFresh) sourceSelectionTouched = false
        val choosing = (binding.sourceSpinner.tag as? List<*>)
            ?.filterIsInstance<String>()?.takeIf { touchFresh && it.isNotEmpty() }
        val names = choosing ?: ui.sourceLines.keys.withIndex().sortedWith(
            compareBy<IndexedValue<String>> {
                val (state, detail) = spinnerStates[it.value] ?: ("none" to "checking on first load")
                SourceIndicator.priority(SourceIndicator.of(it.value, state, detail).tone)
            }.thenBy { if (it.value == `in`.opentrack.india.v2.mirror.PublicPageChecks.ORG) 0 else 1 }
                .thenBy { it.index }
        ).map { it.value }
        binding.sourceSpinner.visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
        if (names.isEmpty()) return
        // State updates recolor the existing adapter. Rebuilding the Spinner for every
        // background answer can fire a selection callback for its default first item,
        // silently changing the user's chosen source back to NTES.
        if (binding.sourceSpinner.tag != names) {
            binding.sourceSpinner.tag = names
            binding.sourceSpinner.onItemSelectedListener = null
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
                        text = "● ${sourceName(name)} · ${indicator.label}" +
                            (SourceIndicator.seconds(detail)?.let { " · $it" } ?: "")
                        setTextColor(getColor(sourceColor(indicator.tone)))
                    }
                    return view
                }
            }
            val target = names.indexOf(ui.activeSource)
            if (target >= 0) binding.sourceSpinner.setSelection(target, false)
            binding.sourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val name = names.getOrNull(pos) ?: return
                    // Reordering after a background answer must never select the first
                    // item by accident. Only a traveller-initiated dropdown choice fetches.
                    if (!sourceSelectionTouched) return
                    // Opening the popup can report its already-selected row again.
                    // Do not spend the pending touch before the traveller taps a
                    // different entry; a background probe may have repainted it.
                    if (name == lastUi?.activeSource) return
                    sourceSelectionTouched = false
                    if (spinnerStates[name]?.first !in listOf("live", "last-known", "candidate")) {
                        // API 24's Toast layout must use the framework inflater: the
                        // Activity's AppCompat factory crashes on its system TextView
                        // (StringBlock pooled-string index outside the Toast XML pool).
                        Toast.makeText(applicationContext,
                            "Checking ${sourceName(name)}…",
                            Toast.LENGTH_SHORT).show()
                        val previous = names.indexOf(lastUi?.activeSource)
                        if (previous >= 0) binding.sourceSpinner.setSelection(previous, false)
                        vm.probeSource(name)
                    } else vm.setActiveSource(name)
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        // Even a data-only adapter notification can fire onItemSelected for the
        // currently highlighted item while its popup is open. Do not consume the
        // pending touch as if that programmatic callback were the traveller's choice.
        if (!touchFresh)
            (binding.sourceSpinner.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
        if (!touchFresh) ui.activeSource?.let { src ->
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
            ui.alternateSiteHint != null || publicCheck != null && ui.orgWebsiteReport == null && ui.railYatriClaim == null ||
            ui.activeSource == `in`.opentrack.india.v2.mirror.WimtCandidate.SOURCE &&
                ui.wimtWebPoint == null || mirrorWithoutTimes
        ) View.VISIBLE else View.GONE
        val selectedStatus = ui.activeSource?.let { ui.sourceStates[it] }
        val selectedReason = ui.activeSource?.let { name ->
            selectedStatus?.let { SourceIndicator.of(name, it.first, it.second).label }
        } ?: "no usable stop report"
        val response = selectedStatus?.second?.let(SourceIndicator::seconds)?.let { " · $it" }.orEmpty()
        binding.sourceSummary.text = when {
            ui.alternateSiteHint != null -> ui.alternateSiteHint
            ui.orgWebsiteReport != null -> "Site's past-stop claims; not confirmed railway actuals."
            ui.wimtWebPoint != null -> "Site distance claim; not phone GPS."
            else -> "${sourceName(ui.activeSource.orEmpty())}: $selectedReason$response. Timetable shown."
        }
        binding.headline.text = m.headline
        binding.heroEyebrow.text = when {
            ui.wimtWebPoint != null -> "WEB POSITION CLAIM · MOTION UNKNOWN"
            ui.orgWebsiteReport != null -> "WEBSITE CLAIM · MOTION UNKNOWN"
            ui.railYatriClaim != null -> "STATION CLAIM · MOTION UNKNOWN"
            m.rows.any { it.source == TrackModel.MinuteSource.REPORTED } -> "LAST STOP · MOTION UNKNOWN"
            else -> "TIMETABLE · NOT LIVE"
        }
        binding.heroEyebrow.setTextColor(getColor(if (ui.wimtWebPoint != null ||
            ui.orgWebsiteReport != null || ui.railYatriClaim != null) R.color.warn else R.color.live))
        // No NTES time-interpolated icon in position-only mode. A separate amber
        // marker can scale a fresh, explicitly UNVERIFIED WIMT web-cache km claim
        // onto two official scheduled halts; it never invents an ETA or Act cell.
        val leg = ui.wimtWebPoint?.let { p ->
            RouteBetween.webCacheLeg(ui.route, ui.staticRoute,
                p.precedingHalt, p.followingHalt, p.routeKm)
        } ?: ui.orgWebsiteReport?.let { report ->
            report.intermediate?.let { p ->
                RouteBetween.siteIntermediateLeg(ui.route, ui.staticRoute,
                    p.precedingHalt, p.followingHalt, p.code, p.mapKm)
            } ?: if (m.rows.any { it.code.equals(report.anchorCode, true) &&
                    it.source == TrackModel.MinuteSource.SITE_REPORTED })
                RouteBetween.siteLastStopLeg(ui.route, ui.staticRoute, report.anchorCode)
            else null
        } ?: ui.railYatriClaim?.takeIf {
            ui.activeSource == `in`.opentrack.india.v2.mirror.PublicPageChecks.RAIL_YATRI
        }?.let { RouteBetween.stationClaimLeg(ui.route, ui.staticRoute, it.code) }
            ?: if (ui.activeSource in setOf("NTES (official)", "erail", "last known",
                `in`.opentrack.india.v2.mirror.RunningStatusMirror.SOURCE,
                `in`.opentrack.india.v2.mirror.ConfirmTktMirror.SOURCE)) {
            val reported = m.rows.firstOrNull { it.code.equals(ui.anchorCode, true) &&
                it.source == TrackModel.MinuteSource.REPORTED &&
                (it.showArr != null || it.showDep != null) } ?: m.rows.lastOrNull {
                it.source == TrackModel.MinuteSource.REPORTED &&
                    (it.showArr != null || it.showDep != null) }
            reported?.let { RouteBetween.reportedHaltLeg(ui.route, ui.staticRoute, it.code) }
        } else null
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
            binding.headline.text = "Near ${RowText.of(pointName)}"
            binding.headline.contentDescription = "Unverified WIMT web-cache claim near $pointName " +
                "at approximately $webKm route km; current motion unknown, not phone GPS"
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
                org?.intermediate != null -> "Reported at ${RowText.of(org.intermediate.name)}"
                org != null && station != null ->
                    (if (org.departed) "Departed " else "Arrived at ") + RowText.of(station.name)
                claim != null && station != null ->
                    "RailYatri reports ${claim.status} ${station.name} · passage time unknown"
                else -> "${sourceName(publicCheck)}: no qualifying past-stop report for this run"
            }
            if (org != null) binding.headline.contentDescription =
                "Unverified website claim: ${binding.headline.text}, " +
                    "${RailTime.formatRailMinute(org.eventMinute)}; current position and motion unknown"
            binding.provenance.text = when {
                org != null -> "WEBSITE · past-stop claims, unverified"
                claim != null -> "RAILYATRI · station claim only"
                else -> "${sourceName(publicCheck)} · comparison only"
            }
            binding.provenance.setBackgroundResource(R.drawable.bg_badge_schedule)
            binding.provenance.setTextColor(getColor(R.color.warn))
            binding.age.text = when {
                org != null -> "Site page updated ${RailTime.formatClock(org.pageUpdatedAt)} IST · " +
                    "not a movement time · site omits run date"
                claim != null -> "provider updated ${RailTime.formatClock(claim.updateAt)} IST · upstream ${claim.upstream} · not an event time"
                else -> "No qualifying past-stop report from selected source"
            }
            val siteStop = org?.let { it.rows[it.anchorCode] }
            val scheduled = org?.intermediate?.scheduledMinute ?: station?.arrivalMin
            val siteArrivalDelay = if (siteStop?.actualArrivalMin != null && scheduled != null)
                siteStop.actualArrivalMin - scheduled else null
            binding.delay.text = if (siteArrivalDelay != null)
                if (siteArrivalDelay > 0) "+${siteArrivalDelay}m late · site claim"
                else if (siteArrivalDelay < 0) "${-siteArrivalDelay}m early · site claim"
                else "On time · site claim"
            else if (org != null) "— · delay not confirmed"
            else "— · no verified delay"
            binding.delay.setTextColor(getColor(R.color.warn))
            val next = org?.intermediate?.let { p -> ui.route.stops.firstOrNull {
                it.code.equals(p.followingHalt, true) } } ?: station?.let {
                ui.route.stops.getOrNull(ui.route.stops.indexOf(it) + 1) }
            binding.next.text = next?.let { RowText.of(it.name) + " · " +
                ((it.arrivalMin ?: it.departureMin)?.let(RailTime::formatRailMinute) ?: "—") +
                "\nSchedule only · not an ETA" } ?: "Next stop unknown · timetable below"
        }
        // The first glance is a report, not a paragraph. Keep the complete model
        // sentence available to TalkBack / Info, but present the event, delay and
        // NEXT scheduled stop as separate visual answers. Do not promote a scheduled
        // arrival into a live ETA or infer movement after the last report.
        if (ui.activeSource == "NTES (official)") {
            JourneyHero.official(m.rows, ui.anchorCode)?.let { hero ->
                binding.headline.contentDescription = m.headline
                binding.headline.text = hero.title
                binding.next.text = hero.next
                binding.provenance.text = "NTES · reported stop"
            }
        }
        binding.note.visibility = if (m.note == null) View.GONE else View.VISIBLE
        m.note?.let { binding.note.text = it }

        // Page notes stay in Info; the in-table icon and source badge already describe
        // the selected site's qualified claim without repeating an excerpt at the halt.
        stopAdapter.reports = ui.sourceLines[ui.activeSource] ?: ui.reports
        stopAdapter.setRoutePoints(RouteBetween.groupedPoints(ui.staticRoute, ui.route))
        stopAdapter.sourceNameForMarker = if (ui.activeSource == "NTES (official)") "NTES"
            else if (ui.activeSource == "last known") "Saved report"
            else ui.activeSource ?: "Source"
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
                    "candidate" -> when {
                        name == `in`.opentrack.india.v2.mirror.PublicPageChecks.ORG &&
                            ui.orgWebsiteReport != null -> "site-published past station times; " +
                            "last card's departure provisional, not railway Act; site omits run date"
                        name == `in`.opentrack.india.v2.mirror.WimtCandidate.SOURCE &&
                            ui.wimtWebPoint != null -> "recent unverified web point; stop times are static"
                        else -> "unverified source claim; not railway actuals"
                    }
                    "none", "unverified" -> {
                        val rows = Regex("(\\d+) (?:page )?rows").find(status.second)?.groupValues?.get(1)
                        if (status.second.contains("Request failed", ignoreCase = true))
                            SourceIndicator.of(name, status.first, status.second).label
                        else if (rows != null) "$rows page rows, no confirmed stop times" else
                            if (status.second.contains("not answered") ||
                                status.second.contains("manual check available")) "tap to check"
                            else "no verified times available"
                    }
                    else -> "last known; not a new report"
                })
                SourceIndicator.seconds(status.second)?.let { append(" · ").append(it) }
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
        val atAnchor = if (ui.wimtWebPoint == null && ui.orgWebsiteReport?.intermediate == null)
            TableMarker.atHalt(rows, anchor, vm.originDate, now) else null
        stopAdapter.haltMarker = atAnchor ?: if (fromOfficial && ui.orgWebsiteReport?.intermediate == null)
            rows.lastOrNull { it.source == TrackModel.MinuteSource.REPORTED }?.let {
                TableMarker.atHalt(rows, it.code, vm.originDate, now)
            } else ui.railYatriClaim?.takeIf {
                ui.activeSource == `in`.opentrack.india.v2.mirror.PublicPageChecks.RAIL_YATRI &&
                    rows.any { row -> row.code.equals(it.code, true) }
            }?.let { TableMarker.Halt(it.code,
                "RailYatri station claim · passage time unknown · not GPS", true) }
        // The marker never moves with this clock. Recompute only the age of the
        // source's last event, including a website halt at 0% of the next leg.
        val timedHalt = rows.firstOrNull { it.code.equals(anchor, true) &&
            it.source == TrackModel.MinuteSource.REPORTED &&
            (it.showDep != null || it.showArr != null) } ?: rows.lastOrNull {
            it.source == TrackModel.MinuteSource.REPORTED &&
                (it.showDep != null || it.showArr != null) }
        val eventAt = ui.wimtWebPoint?.eventAtMillis
            ?: ui.orgWebsiteReport?.let { RailTime.railMinuteToEpochMillis(vm.originDate,
                it.eventMinute) }
            ?: (timedHalt?.showDep ?: timedHalt?.showArr)?.let {
                RailTime.railMinuteToEpochMillis(vm.originDate, it)
            }
        stopAdapter.legAgeSeconds = eventAt?.let { ((now - it).coerceAtLeast(0L) / 1_000L) }
        // The primary age line used to freeze at e.g. "6 min ago" while a static
        // website/NTES event remained unchanged. A refresh is a receipt, NOT a new
        // station event: age the original event locally, also at the last halt where
        // there is no next leg on which to draw an inline icon.
        val elapsed = eventAt?.takeIf { it <= now }?.let {
            TableMarker.elapsedLabel(now - it) + " ago"
        }
        when {
            ui.activeSource == `in`.opentrack.india.v2.mirror.PublicPageChecks.ORG &&
                ui.orgWebsiteReport != null && elapsed != null ->
                binding.age.text = "${RailTime.formatRailMinute(ui.orgWebsiteReport!!.eventMinute)} · " +
                    "$elapsed · page checked ${RailTime.formatClock(ui.orgWebsiteReport!!.pageUpdatedAt)} (not movement)"
            ui.activeSource == `in`.opentrack.india.v2.mirror.WimtCandidate.SOURCE &&
                ui.wimtWebPoint != null && elapsed != null ->
                binding.age.text = "Web-cache point $elapsed · unverified; not phone GPS"
            ui.activeSource == "NTES (official)" && timedHalt != null && elapsed != null ->
                binding.age.text = "${(timedHalt.showDep ?: timedHalt.showArr)?.let(RailTime::formatRailMinute).orEmpty()} · " +
                    "$elapsed · position now unknown"
        }
    }

    private fun showRefreshFeedback() {
        val ui = lastUi
        val status = ui?.sourceStates?.get(requestedRefreshSource)
        val source = if (requestedRefreshSource ==
            `in`.opentrack.india.v2.mirror.PublicPageChecks.ORG) "website"
            else sourceName(requestedRefreshSource)
        val switched = ui?.activeSource != requestedRefreshSource
        val now = System.currentTimeMillis()
        val active = ui?.activeSource ?: requestedRefreshSource
        val age = when {
            active == `in`.opentrack.india.v2.mirror.PublicPageChecks.ORG &&
                ui?.orgWebsiteReport != null -> {
                val eventMillis = RailTime.railMinuteToEpochMillis(vm.originDate,
                    ui.orgWebsiteReport!!.eventMinute)
                "site-reported stop ${TableMarker.elapsedLabel((now - eventMillis).coerceAtLeast(0))} ago" +
                    " · later position unknown"
            }
            active == `in`.opentrack.india.v2.mirror.WimtCandidate.SOURCE &&
                ui?.wimtWebPoint != null -> "web point " +
                "${TableMarker.elapsedLabel((now - ui.wimtWebPoint!!.eventAtMillis).coerceAtLeast(0))} ago" +
                    " · not GPS"
            active == "NTES (official)" && ui != null -> {
                val row = ui.model.rows.lastOrNull {
                    it.source == TrackModel.MinuteSource.REPORTED &&
                        (it.showDep != null || it.showArr != null) }
                val stopEventAt = (row?.showDep ?: row?.showArr)?.let {
                    RailTime.railMinuteToEpochMillis(vm.originDate, it)
                }
                val feedAge = ui.answer.position.feedStampMillis?.let { stamp ->
                    TableMarker.elapsedLabel((now - stamp).coerceAtLeast(0))
                }
                when {
                    stopEventAt != null && stopEventAt <= now ->
                        "last reported stop ${TableMarker.elapsedLabel(now - stopEventAt)} ago"
                    feedAge != null -> "feed stamped $feedAge ago; stop time unknown"
                    else -> "no newly reported stop time"
                }
            }
            else -> "no new timed position from this source"
        }
        val report = eventKey(ui, requestedRefreshSource)
        val changed = !switched && report != null && report != previousRefreshEvent
        val checked = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
            .format(java.util.Date(now))
        binding.refreshFeedback.text = when {
            switched -> "Checked $checked · $source unavailable; now ${sourceName(active)}"
            status?.first !in listOf("live", "candidate") ->
                "Tried $source at $checked · no usable new report · $age"
            changed -> "New stop report · $source · checked $checked · $age"
            else -> "Checked $checked · no newer stop from $source · $age"
        }
        binding.refreshFeedback.visibility = View.VISIBLE
    }

    /** Bring the current reported halt/leg to the top without manufacturing a position. */
    private fun focusCurrentLeg() {
        val ui = lastUi ?: return
        val anchor = stopAdapter.haltMarker?.code ?: ui.wimtWebPoint?.precedingHalt
            ?: ui.orgWebsiteReport?.intermediate?.precedingHalt ?: return
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
            Toast.makeText(applicationContext, R.string.feed_report_empty, Toast.LENGTH_SHORT).show()
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
        Toast.makeText(applicationContext, R.string.feed_report_copied, Toast.LENGTH_SHORT).show()
    }

    /** Rail minutes to HH:MM, with the same fold the table uses, and a dash where the feed said nothing. */
    private fun hhmm(min: Int?): String {
        if (min == null) return "-"
        val folded = ((min % 1440) + 1440) % 1440
        return (folded / 60).toString().padStart(2, '0') + ":" + (folded % 60).toString().padStart(2, '0')
    }

    override fun onStart() {
        super.onStart()
        screenStarted = true
        vm.start()
        binding.stops.removeCallbacks(ageTicker)
        binding.stops.postDelayed(ageTicker, 30_000L)
        binding.root.removeCallbacks(autoRefreshTicker)
        if (binding.autoSwitch.isChecked) binding.root.postDelayed(autoRefreshTicker, 60_000L)
    }

    override fun onStop() {
        screenStarted = false
        binding.root.removeCallbacks(autoRefreshTicker)
        binding.stops.removeCallbacks(ageTicker)
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
