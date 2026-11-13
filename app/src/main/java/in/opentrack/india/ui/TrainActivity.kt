package `in`.opentrack.india.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.animation.LinearInterpolator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.AdapterView
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.Toast
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Gravity
import android.view.WindowManager
import `in`.opentrack.india.domain.RouteBetween
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import `in`.opentrack.india.alarm.AlertFeedback
import `in`.opentrack.india.alarm.AlertState
import `in`.opentrack.india.alarm.AlertTrigger
import `in`.opentrack.india.alarm.JourneyAlarmConfig
import `in`.opentrack.india.alarm.JourneyAlarmNotifications
import `in`.opentrack.india.alarm.JourneyAlarmPolicy
import `in`.opentrack.india.alarm.JourneyAlarmService
import `in`.opentrack.india.alarm.JourneyAlarmStore
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute
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

    private data class AlertTriggerChoice(
        val title: String,
        val description: String,
        val icon: String,
        val isDefault: Boolean,
        val onSelected: () -> Unit,
    )

    private data class AlertDialogChrome(
        val dialog: Dialog,
        val body: android.widget.LinearLayout,
        val cancel: TextView,
        val spacer: View,
        val primary: TextView,
        val heightDp: Int,
    )

    private lateinit var binding: ActivityTrainBinding
    private var batteryAtCreate = false
    private var batteryRefreshLabel: TextView? = null
    private lateinit var vm: LiveViewModel
    private lateinit var stopAdapter: StopAdapter
    private lateinit var connectionCue: ConnectionIssueCue
    private var pendingNotificationAction: (() -> Unit)? = null
    private var pendingLocationAction: (() -> Unit)? = null
    private val alarmStoreListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { refreshArrivalAlertButton() }
    }
    private val notificationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingNotificationAction
        pendingNotificationAction = null
        if (granted && notificationMonitorAvailable()) action?.invoke() else explainPermissionNeeded(
            "Notifications are needed for the ongoing monitor and its arrival alert.")
    }
    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val action = pendingLocationAction
        pendingLocationAction = null
        val granted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (granted) action?.invoke() else explainPermissionNeeded(
            "Phone location was not allowed, so the distance alert was not started.")
    }
    /** A new anchor scrolls once; a manual successful refresh scrolls even at the SAME anchor. */
    private var lastAnchor: String? = null
    private var initialReportedFocusDone = false
    private var focusAfterRefresh = false
    private var focusRequestId = 0L
    /** The last route actually installed by AsyncListDiffer, not merely a network response. */
    private var committedRouteUi: LiveViewModel.Ui? = null
    private var receiptReady = false
    private var refreshStartedAt = 0L
    private var requestedRefreshSource = "NTES (official)"
    /** A completed HTTP request is not a new train event. Compare station+minute,
     * not the site's page-update clock or the time of our own button tap. */
    private var previousRefreshEvent: String? = null
    private var previousWebsiteClockKey: String? = null

    private fun websiteClocksFor(ui: LiveViewModel.Ui, haltCodes: List<String>,
                                 now: Long): Map<String, `in`.opentrack.india.v2.mirror.OrgWebsiteStatus.SiteClocks> {
        if (!WebsiteReportDisplay.selected(ui.activeSource)) return emptyMap()
        return WebsiteReportDisplay.selectedWebsiteClocks(ui.orgWebsiteReport,
            ui.orgWebsiteSiteClocks, vm.originDate, haltCodes,
            ui.orgWebsitePageUpdatedAt, now)
    }

    private fun websiteClockKey(ui: LiveViewModel.Ui?): String? {
        val codes = ui?.model?.rows?.map { it.code }.orEmpty()
        val selectedWebsite = ui != null && WebsiteReportDisplay.selected(ui.activeSource)
        val report = if (selectedWebsite) ui?.orgWebsiteReport else ui?.orgWebsiteComparisonReport
        val clocks = when {
            report != null -> WebsiteReportDisplay.displayedClocks(
                report, vm.originDate, codes, System.currentTimeMillis())
            selectedWebsite -> websiteClocksFor(ui!!, codes, System.currentTimeMillis())
            else -> emptyMap()
        }
        return clocks.toSortedMap().entries.joinToString("|") { (code, value) ->
            "$code:${value.arrivalMin}:${value.departureMin}:${value.forecast}:" +
                "${value.arrivalAfterPageUpdate}:${value.departureAfterPageUpdate}"
        }
    }

    private fun selectedEventAt(ui: LiveViewModel.Ui?): Long? {
        if (ui == null) return null
        if (WebsiteReportDisplay.selected(ui.activeSource)) return ui.orgWebsiteReport?.let {
            RailTime.railMinuteToEpochMillis(vm.originDate, it.eventMinute)
        }
        if (SourceEvidencePolicy.comparisonOnly(ui.activeSource)) return null
        val minute = ui.model.rows.lastOrNull {
            it.source == TrackModel.MinuteSource.REPORTED &&
                (it.showDep != null || it.showArr != null)
        }?.let { it.showDep ?: it.showArr }
        return minute?.let { RailTime.railMinuteToEpochMillis(vm.originDate, it) }
    }

    private fun eventKey(ui: LiveViewModel.Ui?, source: String): String? {
        if (ui == null) return null
        if (source == `in`.opentrack.india.v2.mirror.PublicPageChecks.ORG)
            return (ui.orgWebsiteReport ?: ui.orgWebsiteComparisonReport)?.let {
                "${it.anchorCode}:${it.eventMinute}"
            }
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
            else pendingAutoRefresh = true // first load must not silently consume the minute tick
            binding.root.postDelayed(this, 60_000L)
        }
    }
    // Age is a local minute-granular display, NOT a network poll or a projected movement.
    // One tick/minute and only affected route rows rebind; opt-in network cadence is unchanged.
    private val ageTicker = object : Runnable {
        override fun run() {
            lastUi?.let(::updateTableMarker)
            binding.stops.postDelayed(this, 60_000L)
        }
    }
    private var refreshSpin: ObjectAnimator? = null
    private var sourceDetails = "No sources checked yet."
    private var spinnerStates: Map<String, Pair<String, String>> = emptyMap()
    private var sourceSelectionTouched = false
    private var sourceSelectionTouchedAt = 0L
    private var lastUi: LiveViewModel.Ui? = null
    private var lastScreenLoadKey: Pair<String?, Long?>? = null
    private var completedReceipt: String? = null
    private val hideRefreshOverlay = Runnable { binding.refreshReceiptOverlay.visibility = View.GONE }
    private var lastFlashErrorKey: String? = null
    private var pendingAutoRefresh = false
    private var chainBusy = false
    private var mirrorBusy = false
    private var mirrorStatus: String? = null
    private var heroExpanded = true
    private var sourceSummaryWanted = false
    private var expansionAnimating = false
    private var expansionAnimator: ValueAnimator? = null

    /** Android lays out the delay/next footer even when a fixed card clips it.
     * The source selector is separately pinned below the bounded report and
     * cannot be pushed out of sight by a changing site message. */
    private fun summaryChromeHeight(): Int {
        val card = binding.trackingSummaryCard
        val last = (card.childCount - 1 downTo 0).map(card::getChildAt)
            .firstOrNull { it.visibility != View.GONE } ?: return 0
        return (last.bottom + card.paddingBottom -
            if (heroExpanded) binding.heroDetailsScroll.height else 0).coerceAtLeast(0)
    }

    private fun summaryCapPx(): Int {
        val bounded = RouteViewportBudget.sourceCapPx(
            (binding.contentColumn.height - binding.scroller.top).takeIf { it > 0 }
                ?: binding.root.height,
            resources.displayMetrics.heightPixels, resources.displayMetrics.density,
            resources.configuration.fontScale,
        )
        if (!heroExpanded) return bounded
        // On a small phone a verbose next-stop footer can consume the bounded
        // card. Keep the badge and headline in first glance; the outer report
        // scrolls to its footer. Source/Auto stay pinned below it and the table
        // retains its independent viewport.
        val firstGlance = binding.heroEvidenceSlot.height + binding.headline.height +
            (binding.headline.top - binding.heroEvidenceSlot.bottom).coerceAtLeast(0)
        return SummaryCardSizing.readableCap(bounded, summaryChromeHeight(), firstGlance)
    }

    /** R150's first-report height was locked at badge+headline (roughly 124dp),
     * so the later website notice and timeout note were painted behind the
     * Delay row. Fit the FULL measured content, without leaving an empty fixed
     * slot when a note disappears. The outer card is still capped for the route. */
    private fun fitSummaryHeight() {
        if (expansionAnimating) return
        val card = binding.trackingSummaryCard
        val details = binding.heroDetailsScroll
        if (card.height <= 0 || binding.contentColumn.height <= 0) return
        val chrome = summaryChromeHeight()
        val content = if (heroExpanded) details.getChildAt(0)?.height ?: 0 else 0
        if (heroExpanded && content == 0) return // a layout is still pending
        val fit = SummaryCardSizing.fit(chrome, content, summaryCapPx())
        if (heroExpanded && details.layoutParams.height != fit.detailsPx)
            details.layoutParams = details.layoutParams.apply { height = fit.detailsPx }
        if (card.layoutParams.height != fit.cardPx)
            card.layoutParams = card.layoutParams.apply { height = fit.cardPx }
    }

    private var summaryFitPosted = false
    private fun scheduleSummaryFit() {
        if (expansionAnimating || summaryFitPosted) return
        summaryFitPosted = true
        binding.trackingSummaryCard.post {
            summaryFitPosted = false
            fitSummaryHeight()
        }
    }

    private fun applyHeroExpansion() {
        binding.heroToggle.text = if (heroExpanded) "Less  ▴" else "More  ▾"
        binding.heroToggle.contentDescription = if (heroExpanded)
            "Collapse journey card to delay, next stop and source" else "Expand journey card"
        binding.heroEyebrow.visibility = if (heroExpanded || connectionCue.reason != null)
            View.VISIBLE else View.INVISIBLE
        binding.heroDetailsScroll.visibility = if (heroExpanded) View.VISIBLE else View.GONE
        binding.compactReport.visibility = if (heroExpanded) View.GONE else View.VISIBLE
        binding.batteryEvidence.visibility = if (batteryAtCreate && !heroExpanded)
            View.VISIBLE else View.GONE
        binding.sourceSummary.visibility = if (heroExpanded && sourceSummaryWanted) View.VISIBLE else View.GONE
        binding.note.visibility = if (heroExpanded &&
            (SummaryNoteUi.visible(lastUi?.model?.note, lastUi?.websiteGap != null) != null || errorText != null)) View.VISIBLE else View.GONE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("heroExpanded", heroExpanded)
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trainNo = intent.getStringExtra(EXTRA_TRAIN_NO).orEmpty()
        if (trainNo.length < 4) { finish(); return }
        binding = ActivityTrainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        batteryAtCreate = `in`.opentrack.india.data.Prefs(this).batteryMode
        if (batteryAtCreate) {
            BatteryMode.paint(binding.root, window)
            binding.refreshGlyph.visibility = View.GONE
            batteryRefreshLabel = TextView(this).apply {
                text = "Refresh"
                gravity = android.view.Gravity.CENTER
                layoutParams = android.widget.FrameLayout.LayoutParams(-1, -1)
            }.also { (binding.refresh as android.widget.FrameLayout).addView(it) }
            batteryRefreshLabel?.let { BatteryMode.emphasizeRefresh(binding.refresh, it) }
        }
        connectionCue = ConnectionIssueCue(binding.trackingSummaryCard, binding.connectionAlert,
            batteryAtCreate)
        heroExpanded = if (batteryAtCreate) false else
            savedInstanceState?.getBoolean("heroExpanded", true) ?: true
        binding.heroToggle.setOnClickListener {
            // The previous fixed height is *not* the target of More/Less. Measure
            // the newly selected contents without that constraint, then animate
            // only the card height. Lock exactly the new natural height at the end.
            expansionAnimator?.cancel()
            expansionAnimating = true
            val card = binding.trackingSummaryCard
            val details = binding.heroDetailsScroll
            val from = card.height
            details.layoutParams = details.layoutParams.apply {
                height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }
            card.layoutParams = card.layoutParams.apply {
                height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }
            heroExpanded = !heroExpanded
            applyHeroExpansion()
            // A detached measure with UNSPECIFIED height reused the old fixed
            // bounds on large-font phones. Let the *attached* ScrollView measure
            // the new contents first, then animate toward that real layout height.
            val observer = card.viewTreeObserver
            observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (observer.isAlive) observer.removeOnPreDrawListener(this)
                    val fit = SummaryCardSizing.fit(summaryChromeHeight(),
                        if (heroExpanded) details.getChildAt(0)?.height ?: 0 else 0,
                        summaryCapPx())
                    val detailsHeight = fit.detailsPx
                    val to = fit.cardPx
                    card.layoutParams = card.layoutParams.apply { height = if (batteryAtCreate) to else from }
                    if (heroExpanded && detailsHeight > 0) details.layoutParams =
                        details.layoutParams.apply { height = detailsHeight }
                    if (batteryAtCreate || from == to) {
                        expansionAnimating = false
                        card.layoutParams = card.layoutParams.apply { height = to }
                        scheduleSummaryFit()
                    } else {
                        expansionAnimator = ValueAnimator.ofInt(from, to).apply {
                            duration = 240L
                            addUpdateListener { animator ->
                                card.layoutParams = card.layoutParams.apply {
                                    height = animator.animatedValue as Int
                                }
                            }
                            addListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator) {
                                    if (expansionAnimator !== animation) return
                                    card.layoutParams = card.layoutParams.apply { height = to }
                                    expansionAnimating = false
                                    expansionAnimator = null
                                    scheduleSummaryFit()
                                }
                            })
                            start()
                        }
                    }
                    // Do not show a one-frame jump to the target before animation.
                    return false
                }
            })
        }
        applyHeroExpansion()
        // Inner ScrollView content can grow while its own height is fixed. A
        // source hint or failure note must re-fit the card after layout, not sit
        // half painted beneath Delay until the traveller toggles More/Less.
        binding.heroDetailsScroll.getChildAt(0).addOnLayoutChangeListener {
                _, _, _, _, _, _, _, _, _ -> scheduleSummaryFit()
        }

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

        stopAdapter = StopAdapter().apply { batteryMode = batteryAtCreate }
        stopAdapter.onMapUnavailable = { from ->
            val historical = lastUi?.staticRouteSource ==
                `in`.opentrack.india.remote.OfflineCorridorMap.SOURCE
            Diary.get()?.log("ROUTE_UI", "unmapped leg from $from; static=${lastUi?.staticRouteSource ?: "none"}")
            AlertDialog.Builder(this)
                .setTitle("No verified map for this leg")
                .setMessage("The scheduled halts are still shown. " +
                    (if (lastUi?.model?.routeLabel?.contains("live source says") == true)
                        "The live source names different endpoints for this number. " +
                            "Passing stations from the older timetable cannot safely be attached. "
                    else if (historical) "The offline historical corridor does not match this " +
                        "pair of current scheduled halts; other legs may have stations. "
                    else "Neither independent map supplied an ordered route for this run. " +
                        "A completed journey does not create missing map data. ") +
                    "We cannot invent passing stations or times. The map cannot locate the train.")
                .setNegativeButton("Close", null)
                .setPositiveButton("Retry map") { _, _ -> vm.retryIntermediateMap(from) }
                .show().also(::paintBatteryDialog)
        }
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
        installFloatingRefresh()
        val refreshPrefs = getSharedPreferences("tracking_refresh", Context.MODE_PRIVATE)
        binding.autoSwitch.isChecked = refreshPrefs.getBoolean("every_minute", false)
        binding.autoSwitch.setOnCheckedChangeListener { _, enabled ->
            refreshPrefs.edit().putBoolean("every_minute", enabled).apply()
            binding.root.removeCallbacks(autoRefreshTicker)
            if (!enabled) pendingAutoRefresh = false
            if (enabled && screenStarted) binding.root.postDelayed(autoRefreshTicker, 60_000L)
        }
        vm.liveReceipt.observe(this) {
            // The receipt can arrive BEFORE DiffUtil has installed the newly reported
            // leg. Focus in submitList's commit callback, never on the previous rows.
            if (focusAfterRefresh) receiptReady = true
        }
        // With the About button removed, long-press the full-width picker for
        // the same schedule/report guidance and the technical source report.
        binding.sourceSpinner.setOnLongClickListener {
            val website = lastUi?.activeSource ==
                `in`.opentrack.india.v2.mirror.PublicPageChecks.ORG
            AlertDialog.Builder(this)
                .setTitle("About these times")
                .setMessage("↘ Arrival   ↙ Departure\n\n" +
                    "Dim times are the timetable. Bright times are reported by " +
                    (if (website) "the selected website." else "the selected source.") +
                    " A dash means no time was reported. ~ on an intermediate " +
                    "station is a planned passing time estimated from the timetable and distances. " +
                    "Intermediate km comes from a separate route map and can differ from the main timetable.\n\n" +
                    "The train icon stays at the last reported place; its age is the " +
                    "age of that event, not the age of the page or proof of its current position.")
                .setNeutralButton("Source details") { _, _ ->
                    AlertDialog.Builder(this).setTitle("Source details · ${BuildStamp.ROUND}")
                        .setMessage(sourceDetails)
                        .setPositiveButton("Close", null).show().also(::paintBatteryDialog)
                }
                .setPositiveButton("Close", null)
                .create().also { dialog ->
                    dialog.show()
                    paintBatteryDialog(dialog)
                    // AOSP's all-caps action looks like a warning label and was
                    // hard to recognise as the second, optional explanation.
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setAllCaps(false)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setAllCaps(false)
                }
            true
        }
        binding.back.setOnClickListener { finish() }
        binding.arrivalAlert.setOnClickListener { onArrivalAlertClick() }
        refreshArrivalAlertButton()
        // Long-press the provenance badge to copy a live-feed report. It lives on that chip rather than in
        // Settings because the chip is what a traveller looks at when the screen is not telling the truth as
        // they know it — and the copied text is precisely what turns "it doesn't work" into something fixable
        // from another continent: which sources were tried, what each said, how many rows carry a reported
        // minute, and the stop table as displayed. No account, no token and no location history in it.
        binding.provenance.setOnLongClickListener {
            copyFeedReport()
            true
        }

        vm.mapRetryStatus.observe(this) { status ->
            if (!status.isNullOrBlank()) Toast.makeText(this, status, Toast.LENGTH_LONG).show()
        }
        vm.ui.observe(this) { it?.let { u ->
            lastUi = u
            bind(u)
            // Restore only the untouched first-glance source header. Once the
            // traveller scrolls within the card, live updates must not jump it.
            if (!binding.scroller.touchedByUser) binding.scroller.focusSourceResult()
            // The offline timetable can paint while the network is still in flight.
            // Without this call the first-load panel kept its layout_weight until
            // checking changed, crushing the route viewport on dense phones.
            renderChecking()
            renderNote()
            scheduleSummaryFit()
        } }
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
                // A two-second check receipt floats OVER the summary without
                // measuring it; the full details remain accessible afterward.
                // Also show a fixed-slot warning when HTTP failed and retained old data.
                val failure = RefreshFlash.connectionError(requestedRefreshSource,
                    lastUi?.sourceStates?.get(requestedRefreshSource), lastUi?.officialRequest,
                    lastUi?.publicSourceAnswered?.get(requestedRefreshSource))
                if (failure != null) showConnectionError(failure, requestedRefreshSource)
                // showConnectionError re-applies More/Less accessibility labels;
                // the receipt must be the final description, even on failure.
                attachCompletedReceipt()
                refreshStartedAt = 0L
            }
            if (wasBusy && !chainBusy) focusManualRefreshWhenReady()
            // Fit the measured source response once its content is laid out;
            // follow-up source hints are handled by the details layout listener.
            if (wasBusy && !chainBusy && refreshStartedAt == 0L) scheduleSummaryFit()
            if (wasBusy && !chainBusy && pendingAutoRefresh) binding.root.post {
                if (screenStarted && binding.autoSwitch.isChecked && !chainBusy && pendingAutoRefresh) {
                    pendingAutoRefresh = false
                    // Restart the interval after the deferred check; never overlap chains.
                    binding.root.removeCallbacks(autoRefreshTicker)
                    binding.root.postDelayed(autoRefreshTicker, 60_000L)
                    requestRefresh(auto = true)
                }
            }
        }
        // The sources box: fetching while the witnesses work. Once actuals exist, the line goes quiet -
        // the top card then carries the same sentence (badge + source row + refreshed stamp), and one
        // copy on screen is the owner's round-47 point: everything read at once, nothing said twice.
        vm.mirrorState.observe(this) { st ->
            mirrorStatus = st
            mirrorBusy = st?.startsWith("Checking") == true || st?.startsWith("Already checking") == true
            renderChecking()
            val useful = st != null && (st.startsWith("Checking") ||
                st.startsWith("Already checking"))
            // The shared fetch banner is the sole visible busy sentence. Keep the
            // source-details footer quiet so a slow poll cannot add a second line.
            binding.mirrorState.visibility = View.GONE
            if (useful) binding.mirrorState.text = simpleSourceStatus(st!!)
            st?.let { Diary.get()?.log("UISTATE") { "sources box: $it" } }
        }
        vm.error.observe(this) { msg ->
            errorText = msg
            renderNote()
        }
    }

    private fun alertTriggerSummary(config: JourneyAlarmConfig): String = when (config.trigger) {
        AlertTrigger.DESTINATION_REPORT ->
            "At destination · ${RowText.of(config.toName)} (live report)"
        AlertTrigger.PREVIOUS_STOP_REPORT ->
            "At preceding station · ${RowText.of(config.triggerName)} (live report)"
        AlertTrigger.PHONE_DISTANCE ->
            "Within ${config.distanceKm} km of ${RowText.of(config.toName)} · phone location"
    }

    private fun currentJourneyAlerts(): List<`in`.opentrack.india.alarm.JourneyAlarmSnapshot> {
        val train = intent.getStringExtra(EXTRA_TRAIN_NO).orEmpty()
        val from = intent.getStringExtra(EXTRA_FROM).orEmpty()
        val to = intent.getStringExtra(EXTRA_TO).orEmpty()
        val day = intent.getLongExtra(EXTRA_ORIGIN_EPOCH_DAY, RailTime.todayEpochDay())
        return JourneyAlarmStore.readAll(this).filter { snapshot ->
            val c = snapshot.config
            c.trainNo == train && c.fromCode.equals(from, true) && c.toCode.equals(to, true) &&
                c.originEpochDay == day && (snapshot.state == AlertState.ARMED ||
                snapshot.state == AlertState.RINGING)
        }
    }

    private fun refreshArrivalAlertButton() {
        val active = currentJourneyAlerts()
        val ringing = active.count { it.state == AlertState.RINGING }
        val text: String
        val description: String
        when {
            ringing > 0 -> {
                text = if (ringing == 1) "Stop alarm" else "Alarms · $ringing"
                description = "$ringing arrival alarm${if (ringing == 1) " is" else "s are"} ringing for this journey; open Alerts to manage each independently"
            }
            active.isNotEmpty() -> {
                text = if (active.size == 1) "Alert on" else "Alerts · ${active.size}"
                description = "${active.size} alert${if (active.size == 1) " is" else "s are"} monitoring this journey; tap to add another or manage all alerts"
            }
            else -> {
                text = "Alert"
                description = "Configure an arrival alert for this journey"
            }
        }
        binding.arrivalAlert.text = text
        binding.arrivalAlert.contentDescription = description
    }

    private fun onArrivalAlertClick() {
        val active = currentJourneyAlerts()
        if (active.isEmpty()) {
            requestNotificationAccess { loadJourneyForAlarm() }
            return
        }
        val current = active.joinToString("\n") { snapshot ->
            "• ${alertTriggerSummary(snapshot.config)} · ${snapshot.state.name.lowercase()}"
        }
        AlertDialog.Builder(this)
            .setTitle("${active.size} active alert${if (active.size == 1) "" else "s"} for this journey")
            .setMessage(current + "\n\nAdd another alert, or open the central list to stop one without affecting the others.")
            .setPositiveButton("Add another") { _, _ ->
                requestNotificationAccess { loadJourneyForAlarm() }
            }
            .setNeutralButton("Manage alerts") {
                    _, _ -> startActivity(Intent(this, AlertsActivity::class.java))
            }
            .setNegativeButton("Close", null)
            .show().also(::paintBatteryDialog)
    }

    private fun requestNotificationAccess(action: () -> Unit) {
        JourneyAlarmNotifications.ensureChannels(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            pendingNotificationAction = action
            notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!notificationMonitorAvailable()) {
            explainPermissionNeeded("Enable notifications for TrackTrain, including the Arrival alert monitoring channel.")
            return
        }
        action()
    }

    private fun notificationMonitorAvailable(): Boolean {
        if (!androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = getSystemService(android.app.NotificationManager::class.java) ?: return false
        return manager.getNotificationChannel(JourneyAlarmNotifications.MONITOR_CHANNEL)
            ?.importance != android.app.NotificationManager.IMPORTANCE_NONE
    }

    private fun alertChannelAvailable(feedback: AlertFeedback): Boolean {
        if (!notificationMonitorAvailable()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val channel = when (feedback) {
            AlertFeedback.NOTIFICATION -> JourneyAlarmNotifications.NOTIFICATION_CHANNEL
            AlertFeedback.VIBRATION -> JourneyAlarmNotifications.VIBRATION_CHANNEL
            AlertFeedback.RING_UNTIL_STOPPED -> JourneyAlarmNotifications.RING_CHANNEL
        }
        val manager = getSystemService(android.app.NotificationManager::class.java) ?: return false
        return manager.getNotificationChannel(channel)?.importance !=
            android.app.NotificationManager.IMPORTANCE_NONE
    }

    private fun explainPermissionNeeded(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Permission needed for arrival alerts")
            .setMessage(message + "\n\nYou can enable it in Android app settings and try again.")
            .setPositiveButton("Open app settings") { _, _ ->
                runCatching {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName")))
                }
            }
            .setNegativeButton("Not now", null)
            .show().also(::paintBatteryDialog)
    }

    private fun requestLocationAccess(action: () -> Unit) {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (fine) {
            action()
            return
        }
        pendingLocationAction = action
        // Android 12+ presents Approximate/Precise as one choice when both permissions are requested.
        // Coarse is enough to start the monitor, but the policy still waits unless a fix is accurate.
        locationPermissionRequest.launch(arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ))
    }

    private fun loadJourneyForAlarm() {
        val trainNo = intent.getStringExtra(EXTRA_TRAIN_NO).orEmpty()
        val fromCode = intent.getStringExtra(EXTRA_FROM).orEmpty()
        val toCode = intent.getStringExtra(EXTRA_TO).orEmpty()
        val day = intent.getLongExtra(EXTRA_ORIGIN_EPOCH_DAY, RailTime.todayEpochDay())
        lifecycleScope.launch {
            val repo = runCatching { (application as App).repository() }.getOrNull()
            val route = runCatching { repo?.savedSchedule(trainNo, fromCode, toCode, day) }.getOrNull()
                ?: runCatching { repo?.route(trainNo) }.getOrNull()
                ?: lastUi?.route?.takeIf { it.trainNo == trainNo }
            val from = route?.stops?.firstOrNull { it.code.equals(fromCode, true) }
            val to = route?.stops?.firstOrNull { it.code.equals(toCode, true) }
            if (route == null || from == null || to == null || to.seq <= from.seq) {
                AlertDialog.Builder(this@TrainActivity)
                    .setTitle("No route for this alert")
                    .setMessage("The selected boarding and destination stops are not on a usable ordered route for train $trainNo. No timetable-only or mismatched-route alert was created.")
                    .setPositiveButton("Close", null).show().also(::paintBatteryDialog)
                return@launch
            }
            showAlertTriggerChoices(route, from, to)
        }
    }

    private fun showAlertTriggerChoices(route: TrainRoute, from: StopRow, to: StopRow) {
        val intermediate = route.stops.filter { it.seq > from.seq && it.seq < to.seq }
        val prefs = `in`.opentrack.india.data.Prefs(this)
        val defaultDistance = prefs.defaultAlertDistanceKm
        val choices = mutableListOf<AlertTriggerChoice>()
        choices += AlertTriggerChoice(
            title = "At destination · ${RowText.of(to.name)}",
            description = "Fresh, route-matched station report only · no timetable ETA",
            icon = "◎",
            isDefault = prefs.defaultAlertTrigger == AlertTrigger.DESTINATION_REPORT,
        ) {
            showAlertPreferences(alarmConfig(route, from, to,
                AlertTrigger.DESTINATION_REPORT, to, 0))
        }
        if (intermediate.isNotEmpty()) choices += AlertTriggerChoice(
            title = "At a preceding station…",
            description = "Choose a halt between ${RowText.of(from.name)} and ${RowText.of(to.name)}",
            icon = "↗",
            isDefault = prefs.defaultAlertTrigger == AlertTrigger.PREVIOUS_STOP_REPORT,
        ) {
            showPrecedingStationChoices(route, from, to, intermediate)
        }
        val distances = listOf(defaultDistance) + listOf(2, 5, 10)
            .filterNot { it == defaultDistance }
        distances.forEach { distance ->
            choices += AlertTriggerChoice(
                title = "Within $distance km",
                description = "Phone location · straight-line distance to ${RowText.of(to.name)}",
                icon = "⌖",
                isDefault = prefs.defaultAlertTrigger == AlertTrigger.PHONE_DISTANCE &&
                    distance == defaultDistance,
            ) {
                showAlertPreferences(alarmConfig(route, from, to,
                    AlertTrigger.PHONE_DISTANCE, to, distance))
            }
        }
        // The Settings default is highlighted and sorted first, but every journey can
        // still pick another trigger without changing the global preference.
        val ordered = choices.sortedByDescending { it.isDefault }
        val chrome = createAlertDialogChrome(
            title = "Arrival alert · train ${route.trainNo}",
            subtitle = "Choose a trigger. Review the source and options before setting an alert.",
            eyebrow = "TRIGGER",
            heightDp = 218 + ordered.size * 82,
        )
        addAlertSection(chrome.body, "CHOOSE WHEN TO NOTIFY")
        ordered.forEach { addAlertTriggerOption(chrome, it) }
        showAlertDialog(chrome)
    }

    private fun showPrecedingStationChoices(route: TrainRoute, from: StopRow, to: StopRow,
                                            stops: List<StopRow>) {
        val chrome = createAlertDialogChrome(
            title = "Choose a preceding station",
            subtitle = "Select a route halt between ${RowText.of(from.name)} and ${RowText.of(to.name)}.",
            eyebrow = "LIVE REPORT TRIGGER",
            heightDp = 218 + minOf(stops.size, 8) * 76,
        )
        addAlertSection(chrome.body, "STATIONS ON THIS ROUTE")
        stops.forEachIndexed { index, stop ->
            addAlertTriggerOption(chrome, AlertTriggerChoice(
                title = "${RowText.of(stop.name)} (${stop.code})",
                description = "Route station ${index + 1} of ${stops.size} · fresh report only",
                icon = (index + 1).toString(),
                isDefault = false,
                onSelected = {
                    showAlertPreferences(alarmConfig(route, from, to,
                        AlertTrigger.PREVIOUS_STOP_REPORT, stop, 0))
                },
            ))
        }
        showAlertDialog(chrome)
    }

    private fun createAlertDialogChrome(title: String, subtitle: String,
        eyebrow: String, heightDp: Int): AlertDialogChrome {
        val root = layoutInflater.inflate(R.layout.dialog_alert_shell, null, false)
        root.findViewById<TextView>(R.id.alertEyebrow).text = eyebrow
        root.findViewById<TextView>(R.id.alertTitle).text = title
        root.findViewById<TextView>(R.id.alertSubtitle).text = subtitle
        val dialog = Dialog(this)
        dialog.setContentView(root)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        val body = root.findViewById<android.widget.LinearLayout>(R.id.alertDialogBody)
        val cancel = root.findViewById<TextView>(R.id.alertCancel)
        val spacer = root.findViewById<View>(R.id.alertFooterSpacer)
        val primary = root.findViewById<TextView>(R.id.alertPrimary)
        cancel.setOnClickListener { dialog.dismiss() }
        primary.visibility = View.GONE
        cancel.layoutParams = android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, alertDp(52))
        return AlertDialogChrome(dialog, body, cancel, spacer, primary, heightDp)
    }

    private fun addAlertSection(body: android.widget.LinearLayout, label: String) {
        body.addView(TextView(this).apply {
            text = label
            textSize = 11f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(getColor(R.color.text3))
            letterSpacing = 0.08f
            setPadding(alertDp(2), alertDp(4), alertDp(2), alertDp(8))
        }, android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun addAlertTriggerOption(chrome: AlertDialogChrome, choice: AlertTriggerChoice) {
        val row = layoutInflater.inflate(R.layout.item_alert_trigger, chrome.body, false)
        row.setBackgroundResource(if (choice.isDefault) R.drawable.ripple_alert_trigger_default
            else R.drawable.ripple_alert_trigger)
        row.findViewById<TextView>(R.id.alertTriggerIcon).text = choice.icon
        row.findViewById<TextView>(R.id.alertTriggerName).text = choice.title
        row.findViewById<TextView>(R.id.alertTriggerDescription).text = choice.description
        row.findViewById<TextView>(R.id.alertTriggerDefault).visibility =
            if (choice.isDefault) View.VISIBLE else View.GONE
        row.contentDescription = buildString {
            append(choice.title).append(". ").append(choice.description)
            if (choice.isDefault) append(". Default choice")
            append(". Double-tap to choose")
        }
        row.setOnClickListener {
            chrome.dialog.dismiss()
            choice.onSelected()
        }
        chrome.body.addView(row, android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = alertDp(8)
        })
    }

    private fun showAlertDialog(chrome: AlertDialogChrome, showPrimary: Boolean = false) {
        chrome.primary.visibility = if (showPrimary) View.VISIBLE else View.GONE
        chrome.spacer.visibility = if (showPrimary) View.VISIBLE else View.GONE
        chrome.cancel.layoutParams = if (showPrimary)
            android.widget.LinearLayout.LayoutParams(0, alertDp(52), 1f)
        else android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, alertDp(52))
        chrome.dialog.show()
        chrome.dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(0.56f)
            window.setGravity(Gravity.CENTER)
            val width = minOf(resources.displayMetrics.widthPixels - alertDp(32), alertDp(520))
                .coerceAtLeast(alertDp(280))
            val maxHeight = (resources.displayMetrics.heightPixels - alertDp(56)).coerceAtLeast(alertDp(340))
            window.setLayout(width, minOf(alertDp(chrome.heightDp), maxHeight))
            if (batteryAtCreate) BatteryMode.paint(window.decorView, window)
        }
    }

    private fun alarmConfig(route: TrainRoute, from: StopRow, to: StopRow,
                            trigger: AlertTrigger, triggerStop: StopRow,
                            distanceKm: Int): JourneyAlarmConfig = JourneyAlarmConfig(
        trainNo = route.trainNo, trainName = route.trainName,
        fromCode = from.code, toCode = to.code, toName = RowText.of(to.name), toSeq = to.seq,
        trigger = trigger, triggerCode = triggerStop.code,
        triggerName = RowText.of(triggerStop.name), triggerSeq = triggerStop.seq,
        distanceKm = distanceKm,
        feedback = `in`.opentrack.india.data.Prefs(this).defaultAlertFeedback,
        intervalMinutes = `in`.opentrack.india.data.Prefs(this).defaultAlertIntervalMinutes,
        originEpochDay = intent.getLongExtra(EXTRA_ORIGIN_EPOCH_DAY, RailTime.todayEpochDay()),
        baselineSeq = 0, baselineEventMinute = null, createdAtMillis = System.currentTimeMillis(),
    )

    private fun showAlertPreferences(config: JourneyAlarmConfig) {
        val triggerDetail = when (config.trigger) {
            AlertTrigger.DESTINATION_REPORT -> "At destination · ${RowText.of(config.toName)}"
            AlertTrigger.PREVIOUS_STOP_REPORT -> "At preceding station · ${RowText.of(config.triggerName)}"
            AlertTrigger.PHONE_DISTANCE -> "Within ${config.distanceKm} km · phone location"
        }
        val chrome = createAlertDialogChrome(
            title = "Alert options",
            subtitle = "$triggerDetail · choose feedback and check interval",
            eyebrow = "NOTIFICATION SETUP",
            heightDp = 700,
        )
        val body = chrome.body
        fun section(text: String) {
            body.addView(TextView(this).apply {
                this.text = text
                textSize = 14f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(getColor(R.color.text1))
                setPadding(0, alertDp(8), 0, alertDp(3))
            }, android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        fun radio(group: android.widget.RadioGroup, text: String): Int {
            val button = android.widget.RadioButton(this).apply {
                id = ViewCompat.generateViewId()
                this.text = text
                textSize = 14f
                setTextColor(getColor(R.color.text1))
                buttonTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
                minHeight = alertDp(48)
                setPadding(alertDp(6), alertDp(4), alertDp(8), alertDp(4))
            }
            group.addView(button, android.widget.RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return button.id
        }
        section("Alert style")
        val feedbackGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        val feedbackIds = listOf(
            AlertFeedback.NOTIFICATION to radio(feedbackGroup, "Notification · one alert"),
            AlertFeedback.VIBRATION to radio(feedbackGroup, "Vibration · one pattern"),
            AlertFeedback.RING_UNTIL_STOPPED to radio(feedbackGroup, "Ring until I stop it"),
        )
        feedbackGroup.check(feedbackIds.firstOrNull { it.first == config.feedback }?.second
            ?: feedbackIds.first().second)
        body.addView(feedbackGroup)

        section("Check interval")
        val intervalGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        val intervals = listOf(1, 2, 5)
        val intervalIds = intervals.map { minutes ->
            val suffix = if (minutes == 5) " · lower background work" else ""
            minutes to radio(intervalGroup, "$minutes ${if (minutes == 1) "minute" else "minutes"}$suffix")
        }
        intervalGroup.check(intervalIds.firstOrNull { it.first == config.intervalMinutes }?.second
            ?: intervalIds.last().second)
        body.addView(intervalGroup)

        val note = TextView(this).apply {
            text = buildString {
                append("Station alerts use fresh, route-matched NTES/erail source-reported times, not independently verified. Timetables, the whereismytrain.org.in website, and whereismytrain.in feed/cache clocks do not trigger them. The separate Google WIMT app is not used. ")
                if (config.trigger == AlertTrigger.PHONE_DISTANCE)
                    append("Distance uses phone location, a movement heuristic, and straight-line distance—not train GPS. A fresh fix within 250 m accuracy is required; approximate-only location may keep it waiting. ")
                append("An ongoing notification is required. Android, network, location, muted-channel, force-stop, and reboot limits can delay or stop monitoring. DND or device sound settings may mute feedback.")
            }
            textSize = 12f
            setTextColor(getColor(R.color.text2))
            setLineSpacing(alertDp(2).toFloat(), 1f)
            setPadding(alertDp(12), alertDp(12), alertDp(12), alertDp(12))
            setBackgroundResource(R.drawable.bg_summary_card)
        }
        body.addView(note, android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = alertDp(8)
            bottomMargin = alertDp(8)
        })
        chrome.primary.text = "Set alert"
        chrome.primary.contentDescription = "Set this arrival alert"
        chrome.primary.setOnClickListener {
            val selectedFeedback = feedbackIds.firstOrNull {
                it.second == feedbackGroup.checkedRadioButtonId
            }?.first ?: AlertFeedback.NOTIFICATION
            val interval = intervalIds.firstOrNull {
                it.second == intervalGroup.checkedRadioButtonId
            }?.first ?: 5
            if (!alertChannelAvailable(selectedFeedback)) {
                explainPermissionNeeded("Enable the selected arrival-alert notification channel in Android settings.")
                return@setOnClickListener
            }
            val selected = config.copy(feedback = selectedFeedback,
                intervalMinutes = interval, createdAtMillis = System.currentTimeMillis())
            chrome.dialog.dismiss()
            if (selected.trigger == AlertTrigger.PHONE_DISTANCE)
                requestLocationAccess { beginJourneyAlarm(selected) }
            else beginJourneyAlarm(selected)
        }
        showAlertDialog(chrome, showPrimary = true)
    }

    private fun alertDp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun beginJourneyAlarm(config: JourneyAlarmConfig) {
        binding.arrivalAlert.isEnabled = false
        binding.arrivalAlert.text = "Checking…"
        lifecycleScope.launch {
            val armedConfig = config.copy(createdAtMillis = System.currentTimeMillis())
            try {
                val repo = (application as App).repository()
                val monitorRoute = repo?.savedSchedule(config.trainNo, config.fromCode,
                    config.toCode, config.originEpochDay) ?: repo?.route(config.trainNo)
                    ?: lastUi?.route?.takeIf { it.trainNo == config.trainNo }
                val monitorFrom = monitorRoute?.stops?.firstOrNull {
                    it.code.equals(config.fromCode, true)
                }
                val monitorTo = monitorRoute?.stops?.firstOrNull {
                    it.code.equals(config.toCode, true)
                }
                val monitorTrigger = monitorRoute?.stops?.firstOrNull {
                    it.code.equals(config.triggerCode, true)
                }
                if (monitorRoute == null || monitorFrom == null || monitorTo == null ||
                    monitorTrigger == null || monitorFrom.seq >= monitorTo.seq ||
                    monitorTo.seq != config.toSeq || monitorTrigger.seq != config.triggerSeq ||
                    monitorTrigger.seq <= monitorFrom.seq || monitorTrigger.seq > monitorTo.seq) {
                    AlertDialog.Builder(this@TrainActivity)
                        .setTitle("Background route unavailable")
                        .setMessage("No stored route exactly matches the selected boarding, destination, and trigger sequence. The monitor was not started.")
                        .setPositiveButton("Close", null).show().also(::paintBatteryDialog)
                    return@launch
                }

                var baseline = JourneyAlarmPolicy.Baseline()
                if (config.trigger != AlertTrigger.PHONE_DISTANCE) {
                    val current = runCatching {
                        repo?.live(config.trainNo, config.fromCode, config.toCode,
                            config.originEpochDay, allowOverride = false, includeLocation = false)
                    }.getOrNull()
                    if (current != null)
                        baseline = JourneyAlarmPolicy.baseline(armedConfig, current.route, current.answer)
                    if (baseline.seq >= config.triggerSeq) {
                        val eventMinute = baseline.eventMinute
                        val detail = if (eventMinute != null)
                            "A current running-status reply already places train ${config.trainNo} at or beyond ${config.triggerName} (${RailTime.formatRailMinute(eventMinute)}). To avoid replaying an old event, no alert was armed. Choose a later trigger or distance instead."
                        else "The current running-status reply is already at or beyond ${config.triggerName}, but its event time is unknown. No alert was armed. Choose a later trigger or distance instead."
                        AlertDialog.Builder(this@TrainActivity)
                            .setTitle("Trigger already reached")
                            .setMessage(detail)
                            .setPositiveButton("Close", null).show().also(::paintBatteryDialog)
                        return@launch
                    }
                }
                val ready = armedConfig.copy(baselineSeq = baseline.seq,
                    baselineEventMinute = baseline.eventMinute)
                JourneyAlarmService.start(this@TrainActivity, ready)
                Toast.makeText(this@TrainActivity,
                    "Arrival alert armed · ongoing notification shows monitoring status",
                    Toast.LENGTH_LONG).show()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AlertDialog.Builder(this@TrainActivity)
                    .setTitle("Could not start arrival alert")
                    .setMessage("${failure.javaClass.simpleName}: ${failure.message.orEmpty().take(180)}")
                    .setPositiveButton("Close", null).show().also(::paintBatteryDialog)
            } finally {
                binding.arrivalAlert.isEnabled = true
                refreshArrivalAlertButton()
            }
        }
    }

    /** Default in the clock-free bottom dock; a deliberate drag stays anywhere
     * within the lower half, even over a clock. Scrolling the table must never
     * move a button the traveller already placed. */
    private fun installFloatingRefresh() {
        val prefs = getSharedPreferences("tracking_refresh", Context.MODE_PRIVATE)
        val button = binding.refresh
        // A saved drag is a root-overlay position; the original 60dp parking
        // strip is otherwise empty. Reclaim it before the first layout, without
        // changing the saved button coordinates or stealing source-clock space.
        if (prefs.getFloat("float_x", -1f) >= 0f && prefs.getFloat("float_y", -1f) >= 0f)
            RefreshDockSpace.release(binding.refreshDock)
        val inset = (10f * resources.displayMetrics.density).toInt()
        fun avoidClocks() {
            if (button.width <= 0 || binding.root.width <= 0) return
            val rootLocation = IntArray(2)
            binding.root.getLocationInWindow(rootLocation)
            val boxes = mutableListOf<RefreshPlacement.Box>()
            val viewportLocation = IntArray(2)
            binding.routeViewport.getLocationInWindow(viewportLocation)
            val viewTop = viewportLocation[1] - rootLocation[1]
            val viewBottom = viewTop + binding.routeViewport.height
            for (index in 0 until binding.stops.childCount) {
                val row = binding.stops.getChildAt(index)
                for (id in intArrayOf(R.id.schedArr, R.id.schedDep, R.id.liveArr, R.id.liveDep)) {
                    val clock = row.findViewById<View>(id) ?: continue
                    if (clock.visibility != View.VISIBLE || clock.width == 0) continue
                    val loc = IntArray(2)
                    clock.getLocationInWindow(loc)
                    val top = maxOf(loc[1] - rootLocation[1], viewTop)
                    val bottom = minOf(loc[1] - rootLocation[1] + clock.height, viewBottom)
                    if (top < bottom) boxes += RefreshPlacement.Box(
                        (loc[0] - rootLocation[0]).toFloat(), top.toFloat(),
                        (loc[0] - rootLocation[0] + clock.width).toFloat(), bottom.toFloat())
                }
            }
            val point = RefreshPlacement.settle(binding.root.width, binding.root.height,
                button.width, button.height, inset, button.x, button.y, boxes)
            button.x = point.x; button.y = point.y
        }
        var placedSize: Pair<Int, Int>? = null
        binding.root.addOnLayoutChangeListener { root, _, _, _, _, _, _, _, _ ->
            val size = root.width to root.height
            if (button.width == 0 || size.first <= 0 || size.second <= 0 ||
                placedSize == size) return@addOnLayoutChangeListener
            val savedX = prefs.getFloat("float_x", -1f)
            val savedY = prefs.getFloat("float_y", -1f)
            val dock = binding.refreshDock
            val defaultX = (root.width - button.width - 20f * resources.displayMetrics.density)
                .coerceAtLeast(inset.toFloat())
            val defaultY = dock.top + (dock.height - button.height) / 2f
            val point = RefreshPlacement.drag(root.width, root.height,
                button.width, button.height, inset,
                if (savedX >= 0f) root.width * savedX - button.width / 2f else defaultX,
                if (savedY >= 0f) root.height * savedY - button.height / 2f else defaultY)
            button.x = point.x; button.y = point.y
            // Clock avoidance is ONLY for the initial default. Respect a saved
            // drag even if the table/clock geometry changed since it was placed.
            if (savedX < 0f || savedY < 0f) avoidClocks()
            placedSize = size
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var originX = 0f
        var originY = 0f
        var dragging = false
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    originX = v.x; originY = v.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && dx * dx + dy * dy > slop * slop) dragging = true
                    if (dragging) {
                        v.parent.requestDisallowInterceptTouchEvent(true)
                        val point = RefreshPlacement.drag(binding.root.width, binding.root.height,
                            v.width, v.height, inset, originX + dx, originY + dy)
                        v.x = point.x; v.y = point.y
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        // Deliberate placement wins, including over a clock; do
                        // not snap to a surprising different screen position.
                        prefs.edit().putFloat("float_x", (v.x + v.width / 2f) /
                            binding.root.width.coerceAtLeast(1)).putFloat("float_y",
                            (v.y + v.height / 2f) / binding.root.height.coerceAtLeast(1)).apply()
                        RefreshDockSpace.release(binding.refreshDock)
                    } else v.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun requestRefresh(auto: Boolean = false) {
        if (chainBusy) return // never turn a duplicate tap/tick into a misleading receipt
        fitSummaryHeight()
        binding.refreshReceiptOverlay.removeCallbacks(hideRefreshOverlay)
        binding.refreshReceiptOverlay.visibility = View.GONE
        connectionCue.clear() // a new request will earn its own result; never blink during checking
        applyHeroExpansion()
        lastFlashErrorKey = null
        // A deliberate tap must return to the last reported locomotive, even
        // when the response repeats exactly the same stop/time. A background
        // one-minute tick never steals the traveller's scroll position.
        focusAfterRefresh = !auto
        if (!auto) focusRequestId++
        receiptReady = false
        refreshStartedAt = SystemClock.elapsedRealtime()
        requestedRefreshSource = vm.refreshTarget(auto)
        previousRefreshEvent = eventKey(lastUi, requestedRefreshSource)
        previousWebsiteClockKey = if (WebsiteReportDisplay.selected(requestedRefreshSource))
            websiteClockKey(lastUi) else null
        completedReceipt = null
        if (!batteryAtCreate) binding.stops.post { pulseAtVisibleTrain() }
        vm.refresh(manual = true, auto = auto)
    }

    /** The check can finish before the route diff has laid out its new marker.
     * Wait for BOTH edges; a failed request reuses the already committed route.
     * Neither a timetable-only response nor a page-update clock creates a glyph. */
    private fun focusManualRefreshWhenReady() {
        val ui = lastUi ?: return
        if (!focusAfterRefresh || chainBusy || refreshStartedAt != 0L ||
            committedRouteUi !== ui) return
        val request = focusRequestId
        binding.stops.post {
            if (!focusAfterRefresh || chainBusy || refreshStartedAt != 0L ||
                request != focusRequestId || committedRouteUi !== lastUi) return@post
            focusAfterRefresh = false
            focusCurrentLeg()
        }
    }

    private fun checkingSourceLabel(): String {
        val mirrorSource = if (mirrorBusy) mirrorStatus?.let { status ->
            when {
                status.startsWith("Checking ") -> status.substringAfter("Checking ")
                    .substringBefore("…").trim()
                status.startsWith("Already checking ") -> status.substringAfter("Already checking ")
                    .substringBefore(';').trim()
                else -> null
            }
        } else null
        if (mirrorSource != null) return "Fetching ${sourceName(mirrorSource)}…"
        val activeSource = lastUi?.activeSource?.takeIf { it.isNotBlank() }
        return if (refreshStartedAt > 0L || activeSource == null) "Fetching train status…"
            else "Fetching ${sourceName(activeSource)}…"
    }

    /** Keep progress visible only while a requested source is actually fetching. */
    private fun renderChecking() {
        val busy = chainBusy || mirrorBusy
        val showInlineStatus = !batteryAtCreate && busy && lastUi != null
        binding.fetchingStatus.visibility = if (showInlineStatus) View.VISIBLE else View.GONE
        if (showInlineStatus) {
            val message = checkingSourceLabel()
            binding.fetchingLabel.text = message
            binding.fetchingLabel.contentDescription =
                "$message A source fetch is in progress; completion time is unknown."
        } else {
            binding.fetchingLabel.contentDescription = null
        }
        // The first-open panel already has a large spinner and a sentence. Once
        // a timetable is on screen, use the same fetch status above it instead.
        binding.firstLoad.visibility = if (busy && lastUi == null) View.VISIBLE else View.GONE
        if (!batteryAtCreate && chainBusy && refreshStartedAt > 0L) {
            if (refreshSpin == null) {
                refreshSpin = ObjectAnimator.ofFloat(binding.refreshGlyph, View.ROTATION, 0f, 360f).apply {
                    duration = 900L
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    start()
                }
            }
        } else {
            refreshSpin?.cancel()
            refreshSpin = null
            binding.refreshGlyph.rotation = 0f
        }
    }

    // The search result carries a passenger segment (boarding/alighting), not the train's full
    // endpoints. Compare it with the ordered stops; never call an intermediate segment a conflicting
    // train identity. Full origin/terminus identity is checked separately in LiveViewModel.routeLabel.
    private var errorText: String? = null

    private fun selectedJourneyWarning(): String? {
        val ui = lastUi ?: return null
        return JourneyRouteGate.missingJourneyNote(
            ui.model.trainNo,
            ui.route.stops.map { it.code },
            intent.getStringExtra(EXTRA_FROM).orEmpty(),
            intent.getStringExtra(EXTRA_TO).orEmpty(),
        )
    }

    /** A genuinely uncovered passenger segment outranks transient note text; endpoint identity is
     * already compared against the full route in the ViewModel. */
    private fun renderNote() {
        val msg = selectedJourneyWarning() ?: errorText ?: SummaryNoteUi.visible(lastUi?.model?.note, lastUi?.websiteGap != null)
        binding.note.visibility = if (msg == null || !heroExpanded) View.GONE else View.VISIBLE
        if (msg != null) binding.note.text = msg
    }

    /**
     * The source dropdown offers NTES and the WIMT .org.in website. The independent .in live feed
     * and web cache keep their own evidence paths but are not separate user choices. Rebuilt only
     * when the visible option set changes, so a re-selection never fights the observer.
     */
    private fun sourceColor(tone: SourceIndicator.Tone): Int = when (tone) {
        SourceIndicator.Tone.READY -> R.color.accent
        SourceIndicator.Tone.CANDIDATE -> R.color.warn
        SourceIndicator.Tone.UNAVAILABLE -> R.color.late
        SourceIndicator.Tone.OLD -> R.color.warn
        SourceIndicator.Tone.PENDING -> R.color.text2
    }

    private fun sourcePickerDescription(selectedSource: String?): String =
        "Selected ${TrackingSources.label(TrackingSources.valid(selectedSource))}. " +
            "Choose NTES or the Where Is My Train website (.org.in). Long press for time and source details."

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
        // An offline or unresponsive source can leave sourceLines empty. Never
        // remove the selector just when a traveller needs its alternative.
        val names = choosing ?: TrackingSources.options.withIndex().sortedWith(
            compareBy<IndexedValue<String>> {
                val (state, detail) = spinnerStates[it.value] ?: ("none" to "checking on first load")
                SourceIndicator.priority(SourceIndicator.of(it.value, state, detail).tone)
            }.thenBy { if (it.value == `in`.opentrack.india.v2.mirror.PublicPageChecks.ORG) 0 else 1 }
                .thenBy { it.index }
        ).map { it.value }
        binding.sourceSpinner.visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
        // Spinner's own accessibility text can be empty on Android even when its
        // painted child says WIMT. Expose the *selected* source to TalkBack too.
        binding.sourceSpinner.contentDescription = sourcePickerDescription(ui.activeSource)
        binding.refresh.contentDescription = if (vm.refreshTarget(auto = false) != ui.activeSource)
            "Retry selected website first; NTES currently shown as fallback. Drag to move in lower half"
        else "Refresh selected source; drag to move in lower half"
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
                        val shortName = when (names[position]) {
                            TrackingSources.WIMT -> "WIMT .in"
                            `in`.opentrack.india.v2.mirror.PublicPageChecks.ORG -> "WIMT website"
                            `in`.opentrack.india.v2.mirror.WimtCandidate.SOURCE -> "WIMT cache"
                            else -> "NTES"
                        }
                        text = "● $shortName  ▾"
                        textSize = 14f
                        maxLines = 1
                        setTextColor(getColor(sourceColor(indicator.tone)))
                    }
                    BatteryMode.paint(view)
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
                        text = "● ${TrackingSources.label(name)} · ${indicator.label}" +
                            (SourceIndicator.seconds(detail)?.let { " · $it" } ?: "")
                        setTextColor(getColor(sourceColor(indicator.tone)))
                    }
                    BatteryMode.paint(view)
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
                    `in`.opentrack.india.data.Prefs(application as App).defaultTrackingSource =
                        TrackingSources.valid(name)
                    if (spinnerStates[name]?.first !in listOf("live", "last-known", "candidate") &&
                        !(name == `in`.opentrack.india.v2.mirror.PublicPageChecks.ORG &&
                            spinnerStates[name]?.first == "comparison")) {
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
                    // The source's result is at the top of the bounded card. Bring it
                    // into view after an intentional selection, not on background refresh.
                    binding.scroller.focusSourceResult()
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
        val wimtRows = ui.sourceLines[`in`.opentrack.india.v2.mirror.WimtCandidate.SOURCE].orEmpty()
        val wimtLiveRows = ui.sourceLines[TrackingSources.WIMT].orEmpty()
        val wimtLiveEvidence = ui.wimtLiveEvidence
        val wimtLiveSummary = when {
            wimtLiveEvidence == null -> "WIMT .in live-status feed has not been checked yet."
            wimtLiveEvidence.runDateMatched != true || wimtLiveEvidence.routeMatched != true ->
                "WIMT .in response is not confirmed for this run and both route endpoints; timetable shown. " +
                    (wimtLiveEvidence.detail.take(220))
            wimtLiveEvidence.actualStopCount > 0 ->
                "WIMT .in: ${wimtLiveEvidence.actualStopCount} source-reported stop row(s); " +
                    (when {
                        wimtLiveEvidence.freshSourceUpdate -> "upstream update stamp is fresh"
                        wimtLiveEvidence.sourceUpdateAheadByMillis != null ->
                            "upstream update stamp is ahead; not counted fresh yet"
                        else -> "last-known source report"
                    }) +
                    ". Update stamp is not an event time. Not GPS or independent train verification."
            wimtLiveEvidence.currentStationCode != null ->
                "WIMT current-station field says ${wimtLiveEvidence.currentStationCode}; " +
                    "no qualifying dated stop clocks, so current position is unknown."
            else -> "WIMT .in returned no qualifying dated stop clocks; timetable shown."
        }
        // Keep web-cache claims separate from the ACTUAL table. Its own data summary
        // appears only when that experimental source is deliberately selected. A
        // newer colored website card is also shown even if its clocks fail the event gate.
        val latestWebsiteCardSummary = if (WebsiteReportDisplay.selected(ui.activeSource))
            ui.orgWebsiteReport?.let { report -> WebsiteReportDisplay.latestSiteCardSummary(
                report, ui.route, ui.selectedLoadReceiptMillis ?: System.currentTimeMillis()) }
            else null
        val latestWebsiteCardBrief = if (WebsiteReportDisplay.selected(ui.activeSource))
            ui.orgWebsiteReport?.let { WebsiteReportDisplay.latestSiteCardBrief(it, ui.route) }
            else null
        sourceSummaryWanted = (
            ui.websiteGap != null || ui.alternateSourceHint != null ||
                publicCheck != null && ui.orgWebsiteReport == null ||
            ui.activeSource == TrackingSources.WIMT ||
            ui.activeSource == `in`.opentrack.india.v2.mirror.WimtCandidate.SOURCE || mirrorWithoutTimes ||
            ui.orgWebsiteCards.isNotEmpty() || latestWebsiteCardSummary != null
        )
        binding.sourceSummary.visibility = if (heroExpanded && sourceSummaryWanted) View.VISIBLE else View.GONE
        val selectedStatus = ui.activeSource?.let { ui.sourceStates[it] }
        val selectedReason = ui.activeSource?.let { name ->
            selectedStatus?.let { SourceIndicator.of(name, it.first, it.second).label }
        } ?: "no usable stop report"
        val response = selectedStatus?.second?.let(SourceIndicator::seconds)?.let { " · $it" }.orEmpty()
        val selectedPending = selectedStatus?.let { (state, detail) ->
            SourceIndicator.of(ui.activeSource.orEmpty(), state, detail).tone ==
                SourceIndicator.Tone.PENDING
        } == true
        val showingWimtRows = ui.activeSource ==
            `in`.opentrack.india.v2.mirror.WimtCandidate.SOURCE && wimtRows.isNotEmpty()
        val alternateSourceTarget = RefreshTargetPolicy.alternateSourceTarget(ui.activeSource)
        val canSwitchAlternate = !showingWimtRows && ui.alternateSourceHint != null &&
            alternateSourceTarget != null
        binding.sourceSummary.isClickable = canSwitchAlternate
        binding.sourceSummary.setOnClickListener(if (canSwitchAlternate)
            View.OnClickListener { alternateSourceTarget?.let(vm::setActiveSource) } else null)
        val sourceSummaryBase = when {
            ui.activeSource == TrackingSources.WIMT -> wimtLiveSummary
            showingWimtRows ->
                "WIMT web cache: ${wimtRows.size} source-reported web-cache time row(s), unverified, not railway ACTUAL. Open Source details."
            ui.alternateSourceHint != null -> ui.alternateSourceHint
            ui.websiteGap != null -> WebsiteGapSummaryUi.concise(ui.websiteGap)
            ui.wimtWebPoint != null -> "WIMT web-cache distance claim; not phone GPS."
            selectedPending -> "Checking ${sourceName(ui.activeSource.orEmpty())}…"
            else -> "$selectedReason$response. Timetable below."
        }
        binding.sourceSummary.text = listOfNotNull(sourceSummaryBase, latestWebsiteCardSummary)
            .joinToString("\n")
        binding.sourceSummary.contentDescription = if (canSwitchAlternate)
            "${binding.sourceSummary.text}. Tap to switch to ${TrackingSources.label(alternateSourceTarget!!)}."
            else binding.sourceSummary.text
        binding.headline.text = m.headline
        binding.routeHeadActual.text = if (WebsiteReportDisplay.selected(ui.activeSource))
            "WEBSITE" else "ACTUAL"
        binding.heroEyebrow.text = when {
            ui.wimtWebPoint != null -> "WEB CACHE · COMPARISON"
            ui.orgWebsiteReport != null -> "SITE REPORT"
            ui.railYatriClaim != null -> "RAILYATRI CLAIM"
            ui.activeSource == TrackingSources.WIMT &&
                (wimtLiveEvidence?.actualStopCount ?: 0) > 0 ->
                "WIMT .IN · SOURCE CLOCKS"
            ui.activeSource == TrackingSources.WIMT && wimtLiveEvidence?.currentStationCode != null ->
                "WIMT .IN · STATION FIELD"
            m.rows.any { it.source == TrackModel.MinuteSource.REPORTED } &&
                ui.activeSource == "NTES (official)" -> "NTES REPORT"
            m.rows.any { it.source == TrackModel.MinuteSource.REPORTED } &&
                ui.activeSource == TrackingSources.WIMT -> "WIMT .IN REPORT"
            m.rows.any { it.source == TrackModel.MinuteSource.REPORTED } -> "SOURCE REPORT"
            else -> "TIMETABLE ONLY"
        }
        binding.heroEyebrow.setTextColor(getColor(if (ui.wimtWebPoint != null ||
            ui.orgWebsiteReport != null || ui.railYatriClaim != null ||
            ui.activeSource == TrackingSources.WIMT) R.color.warn else R.color.live))
        // Site first clocks belong in the WEBSITE column. Neither their page age
        // nor a revisable last-card departure moves the train out of this halt.
        val leg = ui.orgWebsiteReport?.let { report ->
            report.intermediate?.let { p ->
                RouteBetween.siteIntermediateLeg(ui.route, ui.staticRoute,
                    p.precedingHalt, p.followingHalt, p.code, p.mapKm)
            } ?: if (m.rows.any { it.code.equals(report.anchorCode, true) &&
                    it.source == TrackModel.MinuteSource.SITE_REPORTED })
                RouteBetween.siteLastStopLeg(ui.route, ui.staticRoute, report.anchorCode)
            else null
        } ?: if (SourceEvidencePolicy.comparisonOnly(ui.activeSource)) null
            else if (ui.activeSource in setOf("NTES (official)", "erail", "last known",
                `in`.opentrack.india.v2.mirror.RunningStatusMirror.SOURCE,
                `in`.opentrack.india.v2.mirror.ConfirmTktMirror.SOURCE, TrackingSources.WIMT)) {
                val reported = m.rows.firstOrNull { it.code.equals(ui.anchorCode, true) &&
                    it.source == TrackModel.MinuteSource.REPORTED &&
                    (it.showArr != null || it.showDep != null) } ?: m.rows.lastOrNull {
                    it.source == TrackModel.MinuteSource.REPORTED &&
                        (it.showArr != null || it.showDep != null) }
                reported?.let { row ->
                    // WIMT's stop clock proves an event at the halt, not movement since
                    // that event. Keep its marker at the source-reported halt.
                    if (ui.activeSource == TrackingSources.WIMT)
                        RouteBetween.reportedHaltLeg(ui.route, ui.staticRoute, row.code)
                    else if (row.showDep != null)
                        RouteBetween.departureCueLeg(ui.route, ui.staticRoute, row.code)
                    else RouteBetween.reportedHaltLeg(ui.route, ui.staticRoute, row.code)
                }
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
        // TextViews are rebound across source/result states. Never leave a prior website
        // event in accessibility text when the current source has no qualifying report.
        binding.provenance.contentDescription = null
        binding.age.contentDescription = null
        binding.provenance.text = m.provenanceLabel
        binding.age.text = m.ageLabel
        binding.delay.text = m.delayLabel
        binding.delay.contentDescription = null // never retain a previous source's delay in TalkBack
        binding.delayLabel.text = if (ui.actualsHeadline != null ||
            m.rows.any { it.source == TrackModel.MinuteSource.REPORTED &&
                (it.showArr != null || it.showDep != null) }) "Delay at stop" else "Delay"
        binding.delay.setTextColor(getColor(DelayPresentation.color(m.tone)))
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
                        " · timetable"
                } ?: "Next stop unreported"
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
            binding.delay.text = DelayPresentation.mirrorLabel(ui.actualsLate, m.delayLabel)
            ui.delayDetail?.let { binding.delay.text = "${binding.delay.text} · $it" }
            binding.delay.setTextColor(getColor(DelayPresentation.color(m.tone, ui.actualsLate)))
        }
        if (ui.actualsHeadline != null) ui.sourcesLine?.let { binding.age.text = it }
        ui.wimtWebPoint?.let { point ->
            val pointName = ui.staticRoute?.stops?.firstOrNull {
                it.code.equals(point.code, true) }?.name ?: point.code
            // This is a separately dated *web-cache claim*, not a GPS coordinate or
            // a future arrival. The old official Act cells are deliberately not shown
            // while this source is selected (buildUi constructs a static-only table).
            val webKm = String.format(java.util.Locale.ROOT, "%.1f", point.routeKm)
            binding.headline.text = "Web-cache comparison: claims near ${RowText.of(pointName)} · position unknown"
            binding.headline.contentDescription = "Unverified WIMT web-cache claim near $pointName " +
                "at approximately $webKm route km; not an admitted current position or phone GPS"
            binding.provenance.text = "WIMT WEB · comparison only"
            binding.provenance.setBackgroundResource(R.drawable.bg_badge_schedule)
            binding.provenance.setTextColor(getColor(R.color.warn))
            binding.age.text = "point time ${RailTime.formatClock(point.eventAtMillis)} IST · " +
                "cache checked ${RailTime.formatClock(point.cacheAtMillis)}"
            binding.delay.text = "— · not verified"
            binding.delay.setTextColor(getColor(R.color.warn))
            binding.next.text = "Next stop unknown · timetable below"
        }
        if (publicCheck != null) {
            val org = ui.orgWebsiteReport?.takeIf { publicCheck ==
                `in`.opentrack.india.v2.mirror.PublicPageChecks.ORG }
            val claim = ui.railYatriClaim
            val station = org?.let { report -> ui.route.stops.firstOrNull {
                it.code.equals(report.anchorCode, true) } }
            val place = org?.intermediate?.name ?: station?.name ?: org?.anchorCode
            binding.headline.text = when {
                org != null -> "Last qualifying website event · ${RowText.of(place ?: org.anchorCode)} " +
                    "${RailTime.formatRailMinuteBare(org.eventMinute)}"
                claim != null -> "RailYatri comparison: ${claim.code} · passage time unverified"
                else -> "No qualifying past-stop report"
            }
            binding.headline.contentDescription = binding.headline.text.toString() +
                ". Selected ${sourceName(publicCheck)}. Website origin run inferred; this is a " +
                "qualifying page-published event claim, not an independently verified actual, GPS, or current position."
            binding.provenance.text = if (org != null) "Past site card · run inferred"
                else "Comparison only"
            binding.provenance.setBackgroundResource(R.drawable.bg_badge_schedule)
            binding.provenance.setTextColor(getColor(R.color.warn))
            binding.age.text = when {
                org != null -> "Site event · run inferred"
                claim != null -> "Provider updated ${RailTime.formatClock(claim.updateAt)} IST · not movement"
                else -> "No reported event time"
            }
            val siteStop = org?.rows?.get(org.anchorCode)
            val scheduledArrival = org?.intermediate?.scheduledMinute ?: station?.arrivalMin
            val siteDelay = if (siteStop?.actualArrivalMin != null && scheduledArrival != null)
                siteStop.actualArrivalMin - scheduledArrival else null
            binding.delayLabel.text = if (siteDelay == null) "Delay" else "Delay at site stop"
            binding.delay.text = if (siteDelay == null || org == null) "— · no reported delay"
                else when {
                    siteDelay > 0 -> "+${siteDelay}m late · ${RailTime.formatRailMinuteBare(org.eventMinute)}"
                    siteDelay < 0 -> "${-siteDelay}m early · ${RailTime.formatRailMinuteBare(org.eventMinute)}"
                    else -> "On time · ${RailTime.formatRailMinuteBare(org.eventMinute)}"
                }
            binding.delay.contentDescription = if (siteDelay == null) "No site delay at last stop"
                else "Website arrival delay $siteDelay minutes at ${RowText.of(place ?: org!!.anchorCode)}; " +
                    "current delay unknown"
            binding.delay.setTextColor(getColor(if (siteDelay == null) R.color.warn
                else DelayPresentation.color(m.tone, siteDelay)))
            val next = org?.intermediate?.let { p -> ui.route.stops.firstOrNull {
                it.code.equals(p.followingHalt, true) } } ?: station?.let {
                ui.route.stops.getOrNull(ui.route.stops.indexOf(it) + 1) }
            binding.next.text = next?.let { stop ->
                val scheduled = stop.arrivalMin ?: stop.departureMin
                // "Next stop" is already the label above this value. Avoid
                // repeating "Following halt" inside a half-width column: on
                // the owner's phone it wrapped to four lines and pushed Source
                // below the summary viewport. Do not turn timetable into ETA.
                RowText.of(stop.name) +
                    "\nTimetable " + (scheduled?.let(RailTime::formatRailMinuteBare) ?: "—") +
                    if (org != null && scheduled != null && scheduled < org.eventMinute)
                        " · past clock" else ""
            } ?: "Following halt unknown · timetable below"
        }
        // The first glance is a report, not a paragraph. Keep the complete model
        // sentence available to TalkBack / Info, but present the event, delay and
        // NEXT scheduled stop as separate visual answers. Do not promote a scheduled
        // arrival into a live ETA or infer movement after the last report.
        if (ui.activeSource == "NTES (official)") {
            JourneyHero.official(m.rows, ui.anchorCode)?.let { hero ->
                binding.headline.contentDescription = m.headline
                binding.headline.text = "Last NTES report: ${hero.title} · now unknown"
                binding.next.text = hero.next
                binding.provenance.text = "Reported stop"
            }
        }
        // No source event before the origin's scheduled departure: say what the
        // timetable predicts without asserting that the physical train has not moved.
        val scheduledStart = JourneyStartUi.scheduledStart(vm.originDate,
            ui.route.stops.firstOrNull()?.departureMin, System.currentTimeMillis(),
            ui.answer.perStop.values.any { it.actualArrivalMin != null || it.actualDepartureMin != null } ||
                ui.orgWebsiteReport != null || m.rows.any {
                    it.source == TrackModel.MinuteSource.REPORTED ||
                        it.source == TrackModel.MinuteSource.SITE_REPORTED })
        if (scheduledStart != null) {
            binding.headline.text = "Journey not started yet by timetable · no departure reported"
            binding.headline.contentDescription = "Scheduled journey has not reached its origin " +
                "departure time. There is no verified departure; an early run is not ruled out."
            binding.next.text = "Starts at ${RowText.of(ui.route.stops.first().name)} · " +
                "${RailTime.formatRailMinuteBare(scheduledStart)} (timetable)"
            binding.delay.text = "— · no departure reported"
        }
        val visibleNote = SummaryNoteUi.visible(m.note, ui.websiteGap != null)
        binding.note.visibility = if (visibleNote == null || !heroExpanded) View.GONE else View.VISIBLE
        visibleNote?.let { binding.note.text = it }

        if (WebsiteReportDisplay.selected(ui.activeSource) && ui.orgWebsiteReport != null) {
            val report = ui.orgWebsiteReport!!
            val eventKind = WebsiteReportDisplay.eventKind(report)
            val eventAt = RailTime.railMinuteToEpochMillis(vm.originDate, report.eventMinute)
            val eventAge = TableMarker.elapsedLabel((System.currentTimeMillis() - eventAt).coerceAtLeast(0))
            val appCheckedAt = ui.selectedLoadReceiptMillis ?: System.currentTimeMillis()
            binding.provenance.contentDescription = binding.provenance.contentDescription?.toString().orEmpty() +
                ". Website ${eventKind.noun} claim ${RailTime.formatRailMinute(report.eventMinute)}, " +
                "$eventAge old. Page updated ${WebsiteReportDisplay.pageUpdateClock(report)} IST; " +
                "app response received ${RailTime.formatClockSeconds(appCheckedAt)} IST; " +
                "page clock is not a train movement."
        }
        // A selected website paints its own parsed past cards, never NTES's cells.
        // An intermediate without a documented stop event stays timetable-only.
        stopAdapter.reports = ui.sourceLines[ui.activeSource] ?: ui.reports
        stopAdapter.websiteIntermediateReports = if (WebsiteReportDisplay.selected(ui.activeSource))
            ui.orgWebsiteReport?.rows.orEmpty() else emptyMap()
        stopAdapter.websiteTentativeDeparture = null
        val siteNow = System.currentTimeMillis()
        val selectedWebsiteClocks = websiteClocksFor(ui, ui.model.rows.map { it.code }, siteNow)
        stopAdapter.websiteCardTimes = selectedWebsiteClocks
        // Keep all .org.in card fields in a website-labelled caption. When NTES is
        // selected this is a supplemental comparison only; when the site is selected
        // it explains the separate WEBSITE clock column. Neither path writes Act cells.
        val websiteCaptionReport = when (ui.activeSource) {
            "NTES (official)" -> ui.orgWebsiteComparisonReport
            `in`.opentrack.india.v2.mirror.PublicPageChecks.ORG -> ui.orgWebsiteReport
            else -> null
        }
        stopAdapter.websiteComparisonTimes = when {
            websiteCaptionReport != null -> WebsiteReportDisplay.displayedClocks(websiteCaptionReport,
                vm.originDate, ui.model.rows.map { row -> row.code }, siteNow)
            WebsiteReportDisplay.selected(ui.activeSource) -> selectedWebsiteClocks
            else -> emptyMap()
        }
        val websitePageAt = websiteCaptionReport?.pageUpdatedAt ?: ui.orgWebsitePageUpdatedAt
        stopAdapter.websiteComparisonSaved = websitePageAt?.let {
            siteNow - it > 300_000L
        } == true
        stopAdapter.savedOfficialClocks = if (ui.activeSource == "NTES (official)")
            ui.answer.savedOfficialClocks else emptySet()
        stopAdapter.mappedLegStarts = ui.staticMappedLegStarts
        // A new response can repeat the same stop. Only show its receipt once;
        // neither the score nor the pulse changes a train's reported position.
        val loadKey = ui.activeSource to ui.selectedLoadReceiptMillis
        val newReceipt = ui.selectedLoadReceiptMillis != null && loadKey != lastScreenLoadKey
        if (newReceipt) lastScreenLoadKey = loadKey
        if (refreshStartedAt == 0L) {
            val site = `in`.opentrack.india.v2.mirror.PublicPageChecks.ORG
            val preferredFailure = RefreshFlash.preferredWebsiteError(ui.activeSource,
                vm.refreshTarget(auto = false), ui.sourceStates[site], ui.publicSourceAnswered[site])
            val failure = preferredFailure ?: RefreshFlash.connectionError(ui.activeSource.orEmpty(),
                ui.sourceStates[ui.activeSource], ui.officialRequest,
                ui.publicSourceAnswered[ui.activeSource])
            val failedSource = if (preferredFailure != null) site else ui.activeSource.orEmpty()
            val key = if (failure == null) null else
                "$failedSource:${ui.sourceStates[failedSource]?.second}:$failure"
            if (key != null && key != lastFlashErrorKey) {
                lastFlashErrorKey = key
                showConnectionError(failure!!, failedSource)
            } else if (failure == null) {
                lastFlashErrorKey = null
                connectionCue.clear()
            }
        }
        binding.routeArchiveNote.visibility = if (ui.staticRouteSource ==
            `in`.opentrack.india.remote.OfflineCorridorMap.SOURCE) View.VISIBLE else View.GONE
        stopAdapter.mapSource = ui.staticRouteSource
        stopAdapter.mappedAnchorKm = ui.staticRoute?.stops.orEmpty()
            .associate { it.code.uppercase() to it.schedKm }
        stopAdapter.setRoutePoints(RouteBetween.groupedPoints(ui.staticRoute, ui.route))
        stopAdapter.sourceNameForMarker = when (ui.activeSource) {
            "NTES (official)" -> "NTES"
            TrackingSources.WIMT -> "WIMT .in"
            "last known" -> "Saved report"
            `in`.opentrack.india.v2.mirror.PublicPageChecks.ORG -> "Website"
            else -> ui.activeSource ?: "Source"
        }
        stopAdapter.legWebsiteEventMinute = ui.orgWebsiteReport
            ?.takeIf { it.intermediate != null }?.eventMinute
        stopAdapter.setProjectedLeg(leg)
        renderSourceChips(ui)
        updateTableMarker(ui)
        if (DepartureCue.isCue(leg?.markerKind)) {
            // The station keeps its dot. The only train glyph is in the outbound
            // leg, never simultaneously pinned to the station and the leg.
            stopAdapter.haltMarker = null
        }
        binding.compactReport.text = listOfNotNull(binding.headline.text?.toString(),
            latestWebsiteCardBrief).joinToString("\n")
        binding.compactReport.contentDescription = listOfNotNull(
            binding.headline.contentDescription?.toString() ?: binding.headline.text?.toString(),
            latestWebsiteCardSummary,
        ).joinToString(". ")
        applyHeroExpansion()
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
            committedRouteUi = ui
            focusManualRefreshWhenReady()
            if (newReceipt && refreshStartedAt == 0L) binding.stops.post {
                pulseAtVisibleTrain()
            }
            // A usable NTES/website station may paint before ui.anchorCode is set.
            // Focus on the SAME station that owns the actual train glyph, not on
            // the first timetable row. Post after RecyclerView's layout commit.
            val anchor = stopAdapter.haltMarker?.code ?: leg?.takeIf {
                DepartureCue.isCue(it.markerKind) }?.fromCode ?: ui.wimtWebPoint?.precedingHalt
                ?: ui.orgWebsiteReport?.intermediate?.precedingHalt
            val focus = anchor != null && !initialReportedFocusDone && refreshStartedAt == 0L
            if (focus) binding.stops.post {
                if (lastUi !== ui || refreshStartedAt != 0L || focusAfterRefresh) return@post
                focusCurrentLeg()
                receiptReady = false
            }
        }
        val hidden = m.rows.size - (to - from + 1)
        binding.rowsNote.visibility = if (hidden > 0) View.VISIBLE else View.GONE
        if (hidden > 0) {
            binding.rowsNote.text = getString(R.string.rows_window, from + 1, to + 1, m.rows.size)
        }

        bindSource(ui)
        attachCompletedReceipt()

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
        if (batteryAtCreate) {
            BatteryMode.paint(binding.root)
            batteryRefreshLabel?.let { BatteryMode.emphasizeRefresh(binding.refresh, it) }
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
            if (ui.activeSource == TrackingSources.WIMT) {
                val evidence = ui.wimtLiveEvidence
                val rows = ui.sourceLines[TrackingSources.WIMT].orEmpty().values.toList()
                append("Selected WIMT .in live-status response · ")
                append(evidence?.responseCode?.let { "HTTP $it" } ?: "no HTTP response")
                evidence?.responseBytes?.let { append(" · $it bytes") }
                append("\nRun date echoed and matched: ").append(evidence?.runDateMatched?.toString() ?: "not echoed")
                append(" · both route endpoints echoed and matched: ")
                    .append(evidence?.routeMatched?.toString() ?: "not echoed")
                append("\n").append(evidence?.detail ?: "No WIMT .in request has completed.")
                val stamp = evidence?.sourceUpdateMillis
                append("\nUpstream update stamp: ")
                append(stamp?.let(RailTime::formatClockSeconds) ?: "unavailable")
                append(" IST · ").append(when {
                    stamp == null -> "freshness unknown"
                    evidence.freshSourceUpdate -> "fresh source stamp; not event time"
                    evidence.sourceUpdateAheadByMillis != null ->
                        "source stamp is ahead of device clock; freshness not established"
                    else -> "stale source stamp; current position unknown"
                })
                val intermediate = ui.wimtIntermediate
                if (intermediate != null) {
                    append("\nCurrent-station field claims ${intermediate.name} (${intermediate.code}) " +
                        "between ${intermediate.precedingHalt} and ${intermediate.followingHalt}; " +
                        "this is a source station claim, not GPS or measured distance.")
                    intermediate.eventMinute?.let { minute ->
                        val eventAt = RailTime.railMinuteToEpochMillis(vm.originDate, minute)
                        append("\nIntermediate source event ").append(RailTime.formatRailMinute(minute))
                            .append(" · ").append(if (eventAt <= System.currentTimeMillis())
                                TableMarker.elapsedLabel(System.currentTimeMillis() - eventAt) + " old"
                                else "event clock is in the future")
                    }
                }
                append("\nSelected WIMT .in source-reported rows (${rows.size}):\n")
                if (rows.isEmpty()) append("No source stop clock passed the current run/route parser gates.\n")
                else rows.take(12).forEach { append("• ").append(it).append('\n') }
                if (rows.size > 12) append("… ${rows.size - 12} additional rows omitted.\n")
                append("These are source-reported clocks, not NTES actuals, independent verification, " +
                    "GPS or movement inferred from the request time.\n\n")
            }
            ui.sourceStates.forEach { (name, status) ->
                append(sourceName(name)).append(": ")
                // Keep long source diagnostics in the copied diary, not in the tracking UI.
                if (name == `in`.opentrack.india.v2.mirror.WimtCandidate.SOURCE) {
                    val rows = ui.sourceLines[name].orEmpty().values.toList()
                    // Keep the actual HTTP result in Source details: status-only failures and
                    // malformed HTTP-200 bodies must not disappear behind a generic badge.
                    val responseDetail = status.second.substringAfter("; ", status.second)
                    append("${rows.size} source-reported web-cache time row(s); ")
                        .append(responseDetail)
                        .append("; unverified, not railway ACTUAL")
                } else append(when (status.first) {
                    "live" -> "reported times available"
                    "comparison" -> when {
                        name == `in`.opentrack.india.v2.mirror.WimtCandidate.SOURCE ->
                            "${ui.sourceLines[name].orEmpty().size} source-reported web-cache time row(s); unverified, not railway ACTUAL"
                        name == `in`.opentrack.india.v2.mirror.PublicPageChecks.ORG &&
                            ui.orgWebsiteReport != null -> "site-published past station times; run date inferred, not railway ACTUAL"
                        else -> "comparison only; not independently verified ACTUAL"
                    }
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
                if (name == `in`.opentrack.india.v2.mirror.WimtCandidate.SOURCE &&
                    ui.activeSource == name) {
                    val rows = ui.sourceLines[name].orEmpty().values.toList()
                    append("Selected WIMT web-cache rows · unverified, not railway ACTUAL:\n")
                    if (rows.isEmpty()) append("No station-time row passed the strict parser gates.\n")
                    else rows.take(12).forEach { append("• ").append(it).append('\n') }
                    if (rows.size > 12) append("… ${rows.size - 12} additional source rows omitted.\n")
                }
            }
            if (ui.activeSource == TrackingSources.WIMT) {
                append("\nWIMT .in data is source-reported, not independent train verification or GPS. ")
                append("The upstream update stamp is not an event clock or movement.")
            } else {
                append("\nWIMT web cache is not the WIMT phone app. ")
                append("Site claims are not verified actuals. Station distances can differ between maps.")
            }
            if (ui.activeSource == `in`.opentrack.india.v2.mirror.PublicPageChecks.ORG) {
                WebsiteReportDisplay.allSiteCardsSummary(
                    ui.orgWebsiteCards.ifEmpty { ui.orgWebsiteReport?.siteCards.orEmpty() },
                    ui.orgWebsitePageUpdatedAt,
                    ui.orgWebsitePageClockHasSeconds,
                    ui.selectedLoadReceiptMillis)?.let {
                    append("\n\n").append(it)
                }
            }
            ui.lastUpdated?.let { append("\n").append(it) }
        }
    }

    private fun sourceName(name: String): String = when (name) {
        TrackingSources.WIMT -> "WIMT .in live feed"
        `in`.opentrack.india.v2.mirror.WimtCandidate.SOURCE -> "WIMT web cache"
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
        val fromOfficial = ui.activeSource in listOf("NTES (official)", "erail", "last known",
            TrackingSources.WIMT)
        val anchor = ui.anchorCode ?: if (fromOfficial && ui.activeSource != TrackingSources.WIMT)
            ui.answer.anchorStopCode else null
        val now = System.currentTimeMillis()
        stopAdapter.websitePendingDeparture = null
        if (WebsiteReportDisplay.selected(ui.activeSource)) {
            val siteClocks = websiteClocksFor(ui, rows.map { it.code }, now)
            stopAdapter.websiteCardTimes = siteClocks
            stopAdapter.websiteComparisonTimes = siteClocks
        }
        val lastStopMarker = if (WebsiteReportDisplay.selected(ui.activeSource) &&
            ui.orgWebsiteReport?.intermediate == null)
            TableMarker.atHalt(rows, ui.orgWebsiteReport?.anchorCode, vm.originDate, now)
        else if (fromOfficial)
            TableMarker.atHalt(rows, anchor, vm.originDate, now) ?: rows.lastOrNull {
                it.source == TrackModel.MinuteSource.REPORTED
            }?.let { TableMarker.atHalt(rows, it.code, vm.originDate, now) }
        else null
        // This function also runs once per minute. Re-adding the row locomotive
        // after bind had already put a departure cue in the outbound leg painted
        // TWO trains on a real phone. Keep the same single-glyph decision on ticks.
        stopAdapter.haltMarker = lastStopMarker?.takeUnless { marker ->
            stopAdapter.departureCueAnchor()?.equals(marker.code, ignoreCase = true) == true
        }
        // The marker never moves with this clock. Recompute only the age of the
        // source's last event, including a website halt at 0% of the next leg.
        val timedHalt = rows.firstOrNull { it.code.equals(anchor, true) &&
            it.source == TrackModel.MinuteSource.REPORTED &&
            (it.showDep != null || it.showArr != null) } ?: rows.lastOrNull {
            it.source == TrackModel.MinuteSource.REPORTED &&
                (it.showDep != null || it.showArr != null) }
        val eventAt = ui.orgWebsiteReport?.let {
            RailTime.railMinuteToEpochMillis(vm.originDate, it.eventMinute)
        } ?: if (fromOfficial) (timedHalt?.showDep ?: timedHalt?.showArr)?.let {
            RailTime.railMinuteToEpochMillis(vm.originDate, it)
        } else null
        stopAdapter.legAgeSeconds = eventAt?.takeIf { it <= now }?.let { (now - it) / 1_000L }
        // The primary age line used to freeze at e.g. "6 min ago" while a static
        // website/NTES event remained unchanged. A refresh is a receipt, NOT a new
        // station event: age the original event locally, also at the last halt where
        // there is no next leg on which to draw an inline icon.
        val elapsed = eventAt?.takeIf { it <= now }?.let {
            TableMarker.elapsedLabel(now - it) + " ago"
        }
        when {
            WebsiteReportDisplay.selected(ui.activeSource) &&
                ui.orgWebsiteReport == null -> {
                val pageAt = ui.orgWebsitePageUpdatedAt
                val checkedAt = ui.selectedLoadReceiptMillis
                val pageAge = if (pageAt != null && checkedAt != null)
                    WebsiteReportDisplay.pageAgeAtCheck(pageAt,
                        ui.orgWebsitePageClockHasSeconds, checkedAt) else null
                binding.age.text = when {
                    pageAge != null -> "No qualifying site event · page age $pageAge at check"
                    pageAt != null -> "No qualifying site event · page updated " +
                        WebsiteReportDisplay.pageUpdateClock(pageAt, ui.orgWebsitePageClockHasSeconds) + " IST"
                    else -> "No qualifying website event time"
                }
                binding.age.contentDescription = buildString {
                    append("No qualifying past-card event or current position was established.")
                    pageAt?.let {
                        append(" Website page updated ")
                            .append(WebsiteReportDisplay.pageUpdateClock(it,
                                ui.orgWebsitePageClockHasSeconds)).append(" IST")
                    }
                    checkedAt?.let {
                        append("; app response received ").append(RailTime.formatClockSeconds(it))
                            .append(" IST")
                    }
                    pageAge?.let { append("; page age ").append(it).append(" at check") }
                    append(". Site cards are comparison only; neither timestamp is train movement.")
                }
            }
            WebsiteReportDisplay.selected(ui.activeSource) &&
                ui.orgWebsiteReport != null && elapsed != null -> {
                val report = ui.orgWebsiteReport!!
                val eventKind = WebsiteReportDisplay.eventKind(report)
                val checkedAt = ui.selectedLoadReceiptMillis ?: now
                val latestSiteSummary = WebsiteReportDisplay.latestSiteCardSummary(
                    report, ui.route, checkedAt)
                val pageAgeAtCheck = WebsiteReportDisplay.pageAgeAtCheck(report, checkedAt)
                binding.age.text = "${eventKind.summaryLabel} · event $elapsed · page age $pageAgeAtCheck at check"
                binding.age.contentDescription = "Website last mapped ${eventKind.noun} " +
                    "${RailTime.formatRailMinute(report.eventMinute)}; event age $elapsed; " +
                    "page updated ${WebsiteReportDisplay.pageUpdateClock(report)} IST, " +
                    "app response received ${RailTime.formatClockSeconds(checkedAt)} IST, " +
                    "page age $pageAgeAtCheck at check. The page clock is not train movement." +
                    (latestSiteSummary?.let { " $it" } ?: "")
            }
            ui.activeSource == "NTES (official)" && timedHalt != null && elapsed != null -> {
                val minute = (timedHalt.showDep ?: timedHalt.showArr)!!
                val clock = RailTime.formatRailMinute(minute)
                binding.age.text = "Freshness: $elapsed · event $clock"
                binding.age.contentDescription = "Last NTES event at ${timedHalt.name}, " +
                    "$clock; $elapsed; present position and current delay unknown"
                // A new HTTP answer with the same last-stop time cannot redate the delay.
                // This is the delay *then*, not a prediction of the train's delay now.
                if (ui.model.delayLabel != "—") {
                    binding.delay.text = "${ui.model.delayLabel} · $clock"
                    binding.delay.contentDescription = "${ui.model.delayLabel} at the last " +
                        "reported stop ${timedHalt.name} at $clock; current delay unknown"
                }
            }
            ui.activeSource == TrackingSources.WIMT && timedHalt != null && elapsed != null -> {
                val minute = (timedHalt.showDep ?: timedHalt.showArr)!!
                val clock = RailTime.formatRailMinute(minute)
                binding.age.text = "WIMT event · $elapsed · $clock"
                binding.age.contentDescription = "Last WIMT .in source-reported stop event " +
                    "at ${timedHalt.name}, $clock; $elapsed. The response time is not an event; " +
                    "present position and current delay are unknown."
                // This is the source's delay at that reported event, not a new estimate of now.
                if (ui.model.delayLabel != "—") {
                    binding.delay.text = "${ui.model.delayLabel} · $clock"
                    binding.delay.contentDescription = "${ui.model.delayLabel} at the last " +
                        "WIMT .in source-reported stop ${timedHalt.name} at $clock; current delay unknown"
                }
            }
            ui.activeSource == TrackingSources.WIMT -> {
                // Never let PositionEngine's HTTP-receipt age stand in for a missing station event.
                val intermediateMinute = ui.wimtIntermediate?.eventMinute
                if (intermediateMinute != null) {
                    val eventAt = RailTime.railMinuteToEpochMillis(vm.originDate, intermediateMinute)
                    val intermediateAge = eventAt.takeIf { it <= now }?.let {
                        TableMarker.elapsedLabel(now - it) + " ago"
                    } ?: "event clock is in the future"
                    binding.age.text = "WIMT intermediate · $intermediateAge"
                    binding.age.contentDescription = "WIMT .in reports an intermediate event at " +
                        "${ui.wimtIntermediate?.name}, ${RailTime.formatRailMinute(intermediateMinute)}; " +
                        "$intermediateAge. This source report is not GPS or proof of present position."
                } else {
                    binding.age.text = "WIMT event time unavailable"
                    binding.age.contentDescription = "No qualifying WIMT .in stop event time; " +
                        "response receipt and upstream update stamp are not event ages. " +
                        "Present position unknown."
                }
            }
        }
        val freshnessEventAt = if (ui.activeSource == TrackingSources.WIMT && eventAt == null)
            ui.wimtIntermediate?.eventMinute?.let {
                RailTime.railMinuteToEpochMillis(vm.originDate, it)
            } else eventAt
        val stale = SourceFreshness.isStale(freshnessEventAt, now)
        binding.age.setTextColor(getColor(if (stale) R.color.late else R.color.text3))
        if (stale && WebsiteReportDisplay.selected(ui.activeSource) &&
            ui.orgWebsiteReport != null) {
            // Staleness must not erase the website's inferred-run warning.
            binding.provenance.text = WebsiteReportDisplay.STALE_BADGE
            binding.provenance.setBackgroundResource(R.drawable.bg_badge_problem)
            binding.provenance.setTextColor(getColor(R.color.late))
        } else if (stale && ui.activeSource == "NTES (official)" && eventAt != null) {
            binding.provenance.text = "NTES · last reported stop · stale"
            binding.provenance.setBackgroundResource(R.drawable.bg_badge_problem)
            binding.provenance.setTextColor(getColor(R.color.late))
        } else if (stale && ui.activeSource == TrackingSources.WIMT && freshnessEventAt != null) {
            binding.provenance.text = "WIMT .in · source event · stale"
            binding.provenance.setBackgroundResource(R.drawable.bg_badge_problem)
            binding.provenance.setTextColor(getColor(R.color.late))
        }
        if (batteryAtCreate) {
            val latestSiteCard = if (WebsiteReportDisplay.selected(ui.activeSource))
                WebsiteReportDisplay.latestSiteCardBatteryBrief(ui.orgWebsiteReport, ui.route,
                    ui.selectedLoadReceiptMillis ?: now) else null
            binding.batteryEvidence.text = listOfNotNull(
                "${binding.provenance.text} · ${binding.age.text}", latestSiteCard,
            ).joinToString(" · ")
            BatteryMode.paint(binding.provenance)
            BatteryMode.paint(binding.age)
            BatteryMode.paint(binding.delay)
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
                val report = ui.orgWebsiteReport!!
                val eventMillis = RailTime.railMinuteToEpochMillis(vm.originDate, report.eventMinute)
                "last website ${WebsiteReportDisplay.eventKind(report).noun} " +
                    "${TableMarker.elapsedLabel((now - eventMillis).coerceAtLeast(0))} ago" +
                    " · current position unknown"
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
                        "last reported stop ${TableMarker.elapsedLabel(now - stopEventAt)} ago · current position unknown"
                    feedAge != null -> "feed stamped $feedAge ago; stop time unknown"
                    else -> "no newly reported stop time"
                }
            }
            else -> "no new timed position from this source"
        }
        val report = eventKey(ui, requestedRefreshSource)
        val cachedResponse = status?.second?.contains("Cached HTTP body", ignoreCase = true) == true
        val change = if (switched) RefreshEvidence.Change.UNCHANGED else
            RefreshEvidence.compare(previousRefreshEvent, report)
        val checked = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
            .format(java.util.Date(now))
        val detail = RefreshReceiptText.format(
            requestedRefreshSource, source, sourceName(active), checked, age,
            status, ui?.officialRequest,
            ui?.publicSourceAnswered?.get(requestedRefreshSource),
            cachedResponse, change, switched,
            websiteClocksChanged = source == "website" &&
                previousWebsiteClockKey != null && websiteClockKey(ui) != previousWebsiteClockKey,
        )
        val receipt = RefreshReceiptUi.short(source, checked, change, detail, cachedResponse)
        Diary.get()?.log("REFRESH") { detail }
        completedReceipt = receipt + ". " + detail
        attachCompletedReceipt()
        val freshness = if (switched) "${sourceName(active)} · $age" else age
        val overlay = RefreshReceiptUi.overlay(source, checked,
            SystemClock.elapsedRealtime() - refreshStartedAt, freshness, change, detail, cachedResponse)
        binding.receiptTook.text = overlay.took
        binding.receiptChecked.text = overlay.checked
        binding.receiptFreshness.text = overlay.freshness
        binding.refreshReceiptOverlay.visibility = View.VISIBLE
        binding.refreshReceiptOverlay.removeCallbacks(hideRefreshOverlay)
        if (batteryAtCreate) BatteryMode.paint(binding.refreshReceiptOverlay)
        binding.refreshReceiptOverlay.postDelayed(hideRefreshOverlay, 2_000L)
    }

    private fun attachCompletedReceipt() {
        val spoken = completedReceipt ?: return
        binding.provenance.contentDescription = binding.provenance.text.toString() + ". " + spoken
        // Keep the current source explicit for TalkBack and installed-app checks;
        // a refresh receipt augments this description rather than replacing selection.
        binding.sourceSpinner.contentDescription = sourcePickerDescription(lastUi?.activeSource) +
            " Last completed refresh: " + spoken
        // The toggle stays reachable when a warning covers provenance, even on
        // devices where a Spinner exposes only its selected child's description.
        val action = if (heroExpanded) "Collapse journey card" else "Expand journey card"
        binding.heroToggle.contentDescription = "$action. $spoken"
    }

    private fun showConnectionError(reason: String, source: String) {
        // Put a failure BELOW the badge without covering the last report or
        // replacing an event time with a page-check timestamp.
        connectionCue.show(reason, sourceName(source),
            lastUi?.activeSource?.takeIf { it != source }?.let(::sourceName))
        if (!heroExpanded) {
            binding.heroEyebrow.text = "CHECK FAILED"
            binding.heroEyebrow.setTextColor(getColor(R.color.connection_ink))
        }
        applyHeroExpansion()
    }

    private fun paintBatteryDialog(dialog: AlertDialog) {
        if (batteryAtCreate) dialog.window?.let { BatteryMode.paint(it.decorView, it) }
    }

    /** Locate the drawable's actual centre within the visible route viewport.
     * Do not pulse an offscreen or map-only icon as if it were newly measured. */
    private fun pulseAtVisibleTrain() {
        if (batteryAtCreate) return
        val list = binding.stops
        val overlay = binding.statusRipple
        val base = IntArray(2).also(overlay::getLocationOnScreen)
        for (index in 0 until list.childCount) {
            val row = list.getChildAt(index)
            val marker = row.findViewById<View>(R.id.stopTrainMarker)
            val leg = row.findViewById<InlineLegView>(R.id.inlineLeg)
            val target: Pair<Float, Float>? = when {
                marker != null && marker.visibility == View.VISIBLE ->
                    marker.width / 2f to marker.height / 2f
                leg != null && leg.visibility == View.VISIBLE -> leg.trainCenter()
                else -> null
            }
            val view = if (marker?.visibility == View.VISIBLE) marker else leg
            if (target != null && view != null) {
                val position = IntArray(2).also(view::getLocationOnScreen)
                val x = position[0] + target.first - base[0]
                val y = position[1] + target.second - base[1]
                if (x in 0f..overlay.width.toFloat() && y in 0f..overlay.height.toFloat()) {
                    overlay.pulse(x, y)
                    return
                }
            }
        }
    }

    /** Focus the ACTUAL drawable, including one deep inside an expanded leg.
     * Row-start scrolling left the new intermediate icon offscreen after refresh. */
    private fun focusCurrentLeg() {
        val ui = lastUi ?: return
        val anchor = stopAdapter.haltMarker?.code ?: stopAdapter.departureCueAnchor()
            ?: ui.orgWebsiteReport?.intermediate?.precedingHalt?.takeIf {
                WebsiteReportDisplay.selected(ui.activeSource)
            } ?: return
        val idx = stopAdapter.currentList.indexOfFirst { it.code.equals(anchor, true) }
        if (idx < 0) return
        lastAnchor = anchor
        initialReportedFocusDone = true
        val list = binding.stops
        val manager = list.layoutManager as LinearLayoutManager
        manager.scrollToPositionWithOffset(idx, resources.getDimensionPixelSize(R.dimen.gap_s))
        // A posted runnable may run BEFORE the RecyclerView's requested layout. Wait for
        // the laid-out row, especially when the locomotive is deep inside an
        // expanded YPR→Tumkur intermediate leg.
        val observer = list.viewTreeObserver
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                if (!screenStarted || lastUi !== ui) return true
                val row = manager.findViewByPosition(idx) as? android.view.ViewGroup ?: return true
                val marker = row.findViewById<View>(R.id.stopTrainMarker)
                val leg = row.findViewById<InlineLegView>(R.id.inlineLeg)
                val targetView: View
                val centerY: Float
                if (marker != null && marker.visibility == View.VISIBLE) {
                    targetView = marker
                    centerY = marker.height / 2f
                } else if (leg != null && leg.visibility == View.VISIBLE && leg.trainCenter() != null) {
                    targetView = leg
                    centerY = leg.trainCenter()!!.second
                } else return true // an untimed map is not a train location
                val bounds = android.graphics.Rect(0, 0, targetView.width, targetView.height)
                row.offsetDescendantRectToMyCoords(targetView, bounds)
                manager.scrollToPositionWithOffset(idx,
                    TrainFocus.rowOffset(list.height, bounds.top + centerY))
                list.post { pulseAtVisibleTrain() }
                return true
            }
        })
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
            append("TrackTrain live-feed report\n")
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
            if (u.activeSource == `in`.opentrack.india.v2.mirror.WimtCandidate.SOURCE) {
                val rows = u.sourceLines[`in`.opentrack.india.v2.mirror.WimtCandidate.SOURCE]
                    .orEmpty().values.toList()
                append("WIMT web-cache comparison rows=${rows.size}; unverified, not ACTUAL\n")
                rows.take(20).forEach { append("  ").append(it).append('\n') }
                if (rows.size > 20) append("  ${rows.size - 20} additional rows omitted\n")
            }
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
                for ((code, cell) in p.rows.entries.toList().takeLast(4)) append("  $code Web past card " +
                    "arr ${hhmm(cell.actualArrivalMin)} dep ${hhmm(cell.actualDepartureMin)} " +
                    "pf ${cell.platform ?: "–"}; site report, run inferred (not NTES)\n")
            }
            u.orgWebsiteDistanceProbe?.let {
                append("same-website distance POC: $it; no other request; not a live position\n")
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
        if (batteryAtCreate != `in`.opentrack.india.data.Prefs(this).batteryMode) {
            recreate() // returning from Settings: restore the exact original view tree when off
            return
        }
        JourneyAlarmStore.register(this, alarmStoreListener)
        refreshArrivalAlertButton()
        screenStarted = true
        vm.start()
        binding.stops.removeCallbacks(ageTicker)
        binding.stops.postDelayed(ageTicker, 60_000L)
        binding.root.removeCallbacks(autoRefreshTicker)
        if (binding.autoSwitch.isChecked) binding.root.postDelayed(autoRefreshTicker, 60_000L)
    }

    override fun onStop() {
        screenStarted = false
        pendingAutoRefresh = false
        binding.root.removeCallbacks(autoRefreshTicker)
        binding.stops.removeCallbacks(ageTicker)
        binding.statusRipple.cancelPulse()
        connectionCue.pause()
        binding.refreshReceiptOverlay.removeCallbacks(hideRefreshOverlay)
        binding.refreshReceiptOverlay.visibility = View.GONE
        refreshSpin?.cancel()
        refreshSpin = null
        binding.refreshGlyph.rotation = 0f
        expansionAnimator?.cancel()
        JourneyAlarmStore.unregister(this, alarmStoreListener)
        super.onStop()
    }

    companion object {
        /** Plain-language key for the optional source explanation, separate from feed diagnostics. */
        fun sourceText(m: TrackModel.View): String = buildString {
            append("Act = reported. Est = predicted, not an arrival report. ")
            append("Site = site's past-stop claim, not independently verified Act or GPS. ")
            append("A blue train marks the last source event, not current GPS. " +
                "It can appear between stops only with a qualified distance. " +
                "A WIMT web-cache distance is not phone-app GPS.\n")
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
