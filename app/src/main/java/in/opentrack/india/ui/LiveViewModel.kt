package `in`.opentrack.india.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import `in`.opentrack.india.core.Diary
import `in`.opentrack.india.App
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.data.RailRepository
import `in`.opentrack.india.remote.FeedProbe
import `in`.opentrack.india.remote.RailGeoRoute
import `in`.opentrack.india.domain.PositionEngine
import `in`.opentrack.india.domain.Suggestion
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainPosition
import `in`.opentrack.india.model.TrainRoute
import `in`.opentrack.india.v2.contract.TrainIdentity
import `in`.opentrack.india.v2.crowd.CrowdMerger
import `in`.opentrack.india.v2.crowd.NtfyRelay
import `in`.opentrack.india.v2.crowd.StopReport
import `in`.opentrack.india.core.BrowserFetch
import `in`.opentrack.india.core.Http
import `in`.opentrack.india.v2.mirror.ConfirmTktMirror
import `in`.opentrack.india.v2.mirror.ErailRouteMirror
import `in`.opentrack.india.v2.mirror.RunningStatusMirror
import `in`.opentrack.india.v2.source.ErailPairParser
import `in`.opentrack.india.v2.source.ErailTransport
import `in`.opentrack.india.v2.source.PairParse
import `in`.opentrack.india.v2.source.PairWire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "What does this number mean today", as the details screen asks it - injected (5.1) so a laptop test
 * answers with a canned identity and no egress. The production answer comes from erail's keyless
 * by-number reply (the one source CI proved it can read, captures of 2026-10-12).
 */
/** The live tile's full payload for one number: identity PLUS its endpoint times. */
data class ErailLiveIdentity(
    val identity: TrainIdentity,
    val depMinute: Int?,
    val arrMinute: Int?,
    val alightDayOffset: Int,
)

fun interface IdentityFetch {
    suspend fun identityOf(trainNo: String): ErailLiveIdentity?
}

/**
 * The production fetch: one GET, parsed by the pair parser (the captures show the by-number reply IS
 * the same tile-record shape). Exactly one record must carry the asked-for number; anything else -
 * unreachable, unreadable, redirected to a different number - is null, and the screen stays on the
 * timetable with its honest label instead of guessing.
 */
internal suspend fun erailIdentityFetch(trainNo: String): ErailLiveIdentity? {
    val wire = withContext(Dispatchers.IO) { ErailTransport().fetchTrainNo(trainNo, 12_000) }
    if (wire !is PairWire.Ok) return null
    val rows = (ErailPairParser().parse(wire.body, wire.fetchedAtMillis) as? PairParse.Rows)
        ?.listing?.trains ?: return null
    val row = rows.singleOrNull { it.identity.number == trainNo.trim() } ?: return null
    return ErailLiveIdentity(row.identity, row.boardMin, row.alightMin, row.alightDayOffset)
}

/**
 * State for the live-tracking screen.
 *
 * Polling policy is a product decision, not a technical one: the reference apps refresh aggressively
 * and drain batteries on 12-hour journeys. Here the interval comes from [RailRepository.prefs]
 * (default 60 s, 0 = off), it only runs while the screen is STARTED, and every refresh is a *local*
 * DB read plus at most one HTTP call. A user who opens the app in a tunnel gets the cached snapshot
 * and an honest age label instead of a spinner.
 */
class LiveViewModel(
    private val app: App,
    private val trainNo: String,
    private val fromCode: String,
    private val toCode: String,
    private val originEpochDay: Long,
    private val identityFetch: IdentityFetch = IdentityFetch(::erailIdentityFetch),
    private val relay: NtfyRelay = NtfyRelay(),
) : ViewModel() {

    /** The live identity for [trainNo], once one reaches the device; null until then (and stays null on failure). */
    private var liveIdentity: ErailLiveIdentity? = null

    /**
     * @param model the whole screen, already stringified. The Activity binds and styles; it computes
     *   nothing, because a header derived separately from its own table is how this screen once said
     *   "departed TIPTUR" while listing Tiptur as not yet reached.
     */
    data class Ui(
        val route: TrainRoute,
        val answer: PositionEngine.Answer,
        val model: TrackModel.View,
        val boarding: TrackModel.Stop?,
        val alighting: TrackModel.Stop?,
        val nowRailMinute: Int,
        val tried: List<String>,
        val loading: Boolean,
        /** One reader sentence per stop code ("SMET departed 10:37 — reported by travellers…"), when the
            traveller opted in; empty = the feature is off, and the screen shows no report column at all. */
        val reports: Map<String, String> = emptyMap(),
        /** False when the relay itself was unreachable - the reports card must say so, never imply an empty train. */
        val relayOk: Boolean = true,
        /** The halt the mirror's actuals say the train is at/just past - the screen points here. */
        val anchorCode: String? = null,
        /** When the mirror speaks, its sentence IS the headline; null = render the schedule's. */
        val actualsHeadline: String? = null,
        val actualsNext: String? = null,
        /** "ACTUALS · runningstatus.in" - the badge the headline earned, never the schedule's. */
        val actualsBadge: String? = null,
        /** Measured lateness behind the mirror's own schedule pair; drives the badge colour. */
        val actualsLate: Int? = null,
        /** Which witnesses answered this refresh, for the age line: honesty about sources. */
        val sourcesLine: String? = null,
        /** Per-witness reader lines; the dropdown switches which one the table shows. */
        val sourceLines: Map<String, Map<String, String>> = emptyMap(),
        /** The witness the table is currently showing - the dropdown's selection. */
        val activeSource: String? = null,
        /** A witness answered with minutes, but none is an observation yet (no deviation to report). */
        val actualsSource: String? = null,
        val sourceStates: Map<String, Pair<String, String>> = emptyMap(),
        val lastUpdated: String? = null,
        val delayDetail: String? = null,
        /** Static-only additional route codes, when RailGeo proved all dated official halts in order.
         * Never used to locate the train or generate missing times. */
        val staticRoute: TrainRoute? = null,
    )

    private val _ui = MutableLiveData<Ui?>()
    val ui: LiveData<Ui?> = _ui

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    /**
     * True while a look at the live world is in flight (the first load included). The screen shows a
     * moving bar for exactly this long: during a 12-second refusal a frozen frame reads as "broken" -
     * the owner's 1800s complaint - because the wait was invisible. The traveller is owed the sight
     * of the app working.
     */
    private val _checking = MutableLiveData(true)
    val checking: LiveData<Boolean> = _checking

    /** The reporter controls' last outcome, as a sentence ("published: SMET DEPARTED 14:02"). */
    private val _reportStatus = MutableLiveData<String?>()
    val reportStatus: LiveData<String?> = _reportStatus

    /** ADR-006 opt-in, read from prefs on every refresh: off means nothing polled, nothing published. */
    @Volatile
    private var crowdOn = false

    @Volatile
    private var lastCrowdPoll = 0L

    /** The mirror's own throttle - independent of the relay's, so one slow witness cannot starve the other. */
    private var lastMirrorPoll = 0L

    /**
     * The mirror's position shift, PERSISTED across refreshes: paste #24 showed the note and the
     * shifted clock evaporating on the next auto-tick because refreshBody rebuilds from scratch.
     * Freshness window 30 min - a stale shift is a stale train.
     */
    private var mirrorLate = 0
    private var delayDevice = 0
    private var delayDetail: String? = null
    private var mirrorCrossChecked = false
    private var geoRoute: TrainRoute? = null
    private var geoAttemptAt = 0L
    private var officialStaticKnown = false
    private var mirrorSourceStates: Map<String, Pair<String, String>> = emptyMap()
    private var lastFetchAt = 0L
    private var mirrorNote: String? = null
    private var mirrorShiftAt = 0L
    private var mirrorAnchorCode: String? = null
    private var mirrorHeadline: String? = null
    private var mirrorNext: String? = null
    private var mirrorBadge: String? = null
    private var mirrorSources: String? = null
    /**
     * The FULL route (round 47): erail's page lists halts the device timetable lacks (16228: 27
     * TLGP→MYS vs the on-device 11 SMET→SBC). Merged ONCE per screen open, cached for a day; the
     * device halts keep their seqs, so the position answer stays valid on the grown route.
     */
    private var extentRoute: TrainRoute? = null
    private var extentAt = 0L
    private var extentAttemptAt = 0L
    private var mirrorSourceLines: Map<String, Map<String, String>> = emptyMap()
    /** Per-witness STRUCTURED minutes (code -> act arr/dep) - what the table's Act/Est columns carry. */
    private var mirrorStops: Map<String, List<RunningStatusMirror.Stop>> = emptyMap()
    private var mirrorActiveSource: String? = null
    private var mirrorRefreshedAt = 0L
    private var cacheTrainNo: String? = null

    private fun freshShift(): Boolean =
        cacheTrainNo == trainNo && System.currentTimeMillis() - mirrorShiftAt < 1_800_000

    private var manualErail: PositionEngine.Answer? = null
    private var manualErailAt = 0L
    private var manualErailDetail: String? = null
    private var manualProbeJob: Job? = null

    /** Explicit selection is a *fetch*, even when the official feed is healthy. Only a
     * same-run, past-stop verified response can replace its table. On refusal the official
     * view stays in place and the selected source's failure is shown. */
    fun setActiveSource(name: String) {
        if (name == "NTES (official)" || name == "last known") {
            mirrorActiveSource = name
            val u = _ui.value ?: return
            _ui.postValue(buildUi(u.route, lastAnswer ?: u.answer, u.tried).copy(relayOk = u.relayOk))
            return
        }
        if (name == "erail") {
            if (System.currentTimeMillis() - manualErailAt > PositionEngine.STALE_MS)
                manualErail = null
            if (manualErail != null) {
                mirrorActiveSource = name
                val u = _ui.value ?: return
                _ui.postValue(buildUi(u.route, manualErail ?: u.answer, u.tried).copy(relayOk = u.relayOk))
            } else probeSource(name)
            return
        }
        if (name in setOf(RunningStatusMirror.SOURCE, ConfirmTktMirror.SOURCE)) {
            if (freshShift() && mirrorStops[name].orEmpty().isNotEmpty()) {
                mirrorActiveSource = name
                val u = _ui.value ?: return
                _ui.postValue(buildUi(u.route, lastAnswer ?: u.answer, u.tried).copy(relayOk = u.relayOk))
            } else probeSource(name)
            return
        }
        _mirrorState.postValue("$name: no verified per-stop feed available to ask")
    }

    fun probeSource(name: String) {
        if (manualProbeJob?.isActive == true) {
            _mirrorState.postValue("Already checking a source; wait for its result")
            return
        }
        Diary.get()?.log("SOURCE", "manual probe requested: $name")
        if (name == RunningStatusMirror.SOURCE || name == ConfirmTktMirror.SOURCE) {
            maybeMirror(force = true, preferred = name)
        } else if (name == "erail") {
            manualProbeJob = viewModelScope.launch {
                _mirrorState.postValue("Checking erail independently; keeping the official view until verified…")
                val u = _ui.value ?: return@launch
                val live = runCatching { repo()?.probeErail(trainNo, u.route, originEpochDay) }.getOrNull()
                manualErail = live?.answer?.takeIf { it.position.provenance.live &&
                    it.perStop.values.any { f -> f.actualArrivalMin != null || f.actualDepartureMin != null } }
                manualErailDetail = if (manualErail != null) "verified run stops from erail"
                    else "checked; no verified same-run stop minutes"
                if (manualErail != null) {
                    manualErailAt = System.currentTimeMillis()
                    mirrorActiveSource = name
                }
                _mirrorState.postValue("erail: $manualErailDetail")
                _ui.postValue(buildUi(u.route, manualErail ?: (lastAnswer ?: u.answer), u.tried)
                    .copy(relayOk = u.relayOk))
                Diary.get()?.log("SOURCE", "manual erail: $manualErailDetail")
            }
        } else _mirrorState.postValue("$name: no verified per-stop endpoint is configured")
    }

    /**
     * The stale-while-revalidate store: the mirror's last known answer for ANY train, persisted as one
     * JSON object (org.json - the house way to build JSON). A track screen for a train seen in the last
     * six hours renders its actuals THE MOMENT it opens, then refreshes underneath; on a fluctuating
     * network that is the difference between an instant table and a spinner the journey outlives.
     */
    private suspend fun loadMirrorCache() {
        val repo = runCatching { repo() }.getOrNull() ?: return
        val raw = runCatching { org.json.JSONObject(repo.prefs.mirrorCache) }.getOrNull() ?: return
        val obj = raw.optJSONObject(trainNo) ?: return
        if (obj.optInt("validated") != 1 || obj.optLong("originDay") != originEpochDay) return
        val at = obj.optLong("at", 0L)
        if (at == 0L || System.currentTimeMillis() - at > 6 * 3_600_000L) return
        val lines = LinkedHashMap<String, String>()
        val keys = obj.optJSONObject("lines")
        if (keys != null) for (k in keys.keys()) lines[k] = keys.optString(k)
        if (lines.isEmpty()) return
        cacheTrainNo = trainNo
        mirrorSourceLines = mapOf("last known" to lines)
        mirrorSourceStates = mapOf("last known" to ("last-known" to "last known · refreshed ${obj.optString("refreshed")}"))
        val cachedStops = mutableListOf<RunningStatusMirror.Stop>()
        val stopArr = obj.optJSONArray("stops")
        if (stopArr != null) {
            for (i in 0 until stopArr.length()) {
                val o = stopArr.optJSONObject(i) ?: continue
                cachedStops += RunningStatusMirror.Stop(
                    o.optString("c"),
                    o.optInt("sa", -1).takeIf { it >= 0 },
                    o.optInt("sd", -1).takeIf { it >= 0 },
                    o.optInt("aa", -1).takeIf { it >= 0 },
                    o.optInt("ad", -1).takeIf { it >= 0 },
                )
            }
        }
        if (cachedStops.isNotEmpty()) mirrorStops = mapOf("last known" to cachedStops)
        mirrorActiveSource = "last known"
        mirrorAnchorCode = obj.optString("anchor").ifBlank { null }
        mirrorHeadline = obj.optString("headline").ifBlank { null }
        mirrorNext = obj.optString("next").ifBlank { null }
        mirrorBadge = obj.optString("badge").ifBlank { null }
        mirrorLate = obj.optInt("late", 0)
        delayDevice = obj.optInt("delayDevice", mirrorLate)
        delayDetail = obj.optString("delayDetail").ifBlank { null }
        mirrorSources = "last known · refreshed " + obj.optString("refreshed")
        mirrorNote = obj.optString("note").ifBlank { null }
        mirrorShiftAt = at
        mirrorRefreshedAt = at
        _mirrorState.postValue("Showing last known actuals (from ${obj.optString("refreshed")}) — refreshing…")
        Diary.get()?.log("MIRROR") {
            "cache applied for $trainNo: ${lines.size} stop line(s) from ${obj.optString("refreshed")}"
        }
    }

    private suspend fun saveMirrorCache(display: Map<String, String>) {
        val repo = runCatching { repo() }.getOrNull() ?: return
        val raw = runCatching { org.json.JSONObject(repo.prefs.mirrorCache) }.getOrNull() ?: return
        val refreshed = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(mirrorRefreshedAt))
        val obj = org.json.JSONObject()
            .put("at", mirrorRefreshedAt)
            .put("validated", 1)
            .put("originDay", originEpochDay)
            .put("refreshed", refreshed)
            .put("late", mirrorLate)
            .put("delayDevice", delayDevice)
        delayDetail?.let { obj.put("delayDetail", it) }
        mirrorAnchorCode?.let { obj.put("anchor", it) }
        mirrorHeadline?.let { obj.put("headline", it) }
        mirrorNext?.let { obj.put("next", it) }
        mirrorBadge?.let { obj.put("badge", it) }
        mirrorNote?.let { obj.put("note", it) }
        val lines = org.json.JSONObject()
        for ((k, v) in display) lines.put(k, v)
        obj.put("lines", lines)
        val stopsOut = org.json.JSONArray()
        for (st in mirrorStops[mirrorActiveSource].orEmpty()) {
            val so = org.json.JSONObject()
            so.put("c", st.stationCode)
            st.schArr?.let { so.put("sa", it) }
            st.schDep?.let { so.put("sd", it) }
            st.actArr?.let { so.put("aa", it) }
            st.actDep?.let { so.put("ad", it) }
            stopsOut.put(so)
        }
        if (stopsOut.length() > 0) obj.put("stops", stopsOut)
        raw.put(trainNo, obj)
        val trimmed = org.json.JSONObject()
        // Keep the freshest eight trains: a commuter's week is five, with margin.
        val keysWithAt = mutableListOf<Pair<Long, String>>()
        for (key in raw.keys()) {
            val o = raw.optJSONObject(key) ?: continue
            keysWithAt += o.optLong("at", 0L) to key
        }
        var kept = 0
        for (entry in keysWithAt.sortedByDescending { it.first }) {
            if (kept++ >= 8) continue
            trimmed.put(entry.second, raw.optJSONObject(entry.second) ?: continue)
        }
        repo.prefs.mirrorCache = trimmed.toString()
    }

    /** The track screen's live "what are the sources doing" line: fetching / answered / honest empty. */
    private val _mirrorState = MutableLiveData<String?>()
    val mirrorState: LiveData<String?> = _mirrorState

    /** The direct wire's 403 is a fact for minutes, not for every refresh: skip it while fresh. */
    private var directDeadUntil = 0L

    private var pollJob: Job? = null
    private var liveRefreshJob: Job? = null
    private var lastAnswer: PositionEngine.Answer? = null
    private var auto = true

    val originDate: Long get() = originEpochDay
    val trainNumber: String get() = trainNo

    /** Seq bounds for "my segment" vs "whole route" in the stop list and the chart. */
    fun fromSeq(): Int = _ui.value?.boarding?.seq ?: 0
    fun toSeq(): Int = _ui.value?.alighting?.seq ?: Int.MAX_VALUE

    /** Resolved lazily: the first screen visit may be the moment the DB finishes extracting. */
    private suspend fun repo(): RailRepository? = app.repository()

    fun start() {
        refresh()
        fetchLiveIdentity()
        startPolling()
    }

    private fun startPolling() {
        if (!auto || pollJob != null) return
        pollJob = viewModelScope.launch {
            val seconds = repo()?.prefs?.autoRefreshSeconds ?: 60
            if (seconds <= 0) return@launch
            Diary.get()?.log("UI", "auto refresh loop: every ${seconds}s")
            while (isActive && auto) {
                delay(seconds * 1000L)
                refresh()
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * One identity request per screen open (2.5), off the critical path: the timetable renders first,
     * the live answer relabels it when (and only when) it arrives. Null means "no live identity reached
     * this device" and the timetable label stays exactly as it was - a failed lookup is never rendered
     * as a correction.
     */
    private fun fetchLiveIdentity() {
        viewModelScope.launch {
            val id = runCatching { identityFetch.identityOf(trainNo) }.getOrNull()
            if (id == null) {
                Diary.get()?.log("UI", "no erail identity reached the device for $trainNo; the timetable label stays")
                return@launch
            }
            liveIdentity = id
            Diary.get()?.log(
                "UI",
                "erail live identity for $trainNo: ${id.identity.fromCode}->${id.identity.toCode} (${id.identity.name})",
            )
            // Re-read our own last emission (the Activity holds its own copy; this class does not see it).
            _ui.value?.let { u -> _ui.value = buildUi(u.route, u.answer, u.tried).copy(reports = u.reports, relayOk = u.relayOk) }
        }
    }

    fun setAutoRefresh(enabled: Boolean) {
        if (auto == enabled) return
        auto = enabled
        if (enabled) {
            Diary.get()?.log("UI", "auto refresh ON - polling loop restarted")
            startPolling()
        } else {
            pollJob?.cancel()
            pollJob = null
            Diary.get()?.log("UI", "auto refresh OFF - polling loop cancelled; only manual Refresh asks")
        }
    }

    /** [manual] is only about the log: a tap and an auto tick take the same path, and knowing which one
     * produced a result is what makes a timing question (does a second request interfere with the first?)
     * answerable from a file instead of from a guess. */
    fun refresh(manual: Boolean = false) {
        // Three taps in one second on the owner's R53 phone launched three copies of the
        // same official POST. Keep one live chain in flight; the independent mirror is
        // already throttled separately. This prevents duplicate traffic on weak networks.
        if (liveRefreshJob?.isActive == true) {
            Diary.get()?.log("UI", "refresh already running; duplicate tick/tap ignored")
            return
        }
        if (!manual && !auto) {
            Diary.get()?.log("UI", "auto tick refused - the switch is off; only a manual Refresh asks")
            return
        }
        lastFetchAt = System.currentTimeMillis()
        Diary.get()?.log("UI", if (manual) "refresh tapped by the user" else "refresh (auto tick)")
        _checking.postValue(true)
        if (manual) {
            // The traveller pressed the button. A background experiment that is holding a socket to find out
            // why the last press failed is now the reason this press is slow, so it is told to stop; the
            // diagnostic resumes on the next refusal and has never been the point of the screen.
            FeedProbe.requestCancel()
        }
        val previous = lastAnswer?.position
        liveRefreshJob = viewModelScope.launch {
            try {
                refreshBody(previous)
            } finally {
                _checking.postValue(false)
            }
        }
    }

    private suspend fun refreshBody(previous: TrainPosition?) {
        run {
            val repo = repo() ?: run {
                _error.value = "Timetable is not installed on this device yet."
                return
            }
            crowdOn = repo.prefs.crowdReports
            // The traveller does NOT stare at a spinner: the offline timetable paints FIRST - station,
            // distance and schedule are on screen before any network is waited on (the owner's round-47
            // complaint: the table must appear at once; actuals land on top when a witness answers).
            val paintT0 = System.currentTimeMillis()
            if (_ui.value == null) {
                val now0 = System.currentTimeMillis()
                val saved = repo.lastOfficial(trainNo, fromCode, toCode, originEpochDay)
                val savedStatic = repo.savedSchedule(trainNo, fromCode, toCode, originEpochDay)
                val cachedRoute = saved?.route ?: savedStatic
                    ?: runCatching { repo.queries.route(trainNo) }.getOrNull()
                if (cachedRoute != null) {
                    officialStaticKnown = saved != null || savedStatic != null
                    // Reopen today's validated full static route BEFORE asking any live source.
                    loadGeoCache(cachedRoute, repo)
                    val cached = saved?.answer ?: PositionEngine.locate(
                        PositionEngine.Request(cachedRoute, null, nowMinute(now0), now0, null),
                    )
                    lastAnswer = cached
                    _ui.value = buildUi(
                        cachedRoute, cached, emptyList(),
                        saved?.notes ?: listOf("Timetable shown while the live sources are asked — actuals land on top the moment a source answers."),
                    )
                    _error.value = null
                    Diary.get()?.log("UI") {
                        "instant offline paint for $trainNo (${cachedRoute.stops.size} halts) in " +
                            "${System.currentTimeMillis() - paintT0}ms - live chain continues underneath"
                    }
                }
            }
            // The witnesses start NOW, in parallel with the erail chain - not after it. Paste #27: on a
            // fluctuating network the chain held the screen for minutes while the mirror needed 14 s.
            // The 90 s throttle makes the later, post-buildUi calls no-ops.
            maybeCrowd()
            // And the route grows to the journey's real span once erail's page names the halts the
            // device timetable lacks (throttled + day-cached; never blocks the paint above).
            // A 0.5 s official answer makes both of these slow page probes unnecessary;
            // defer them until after the official request actually fails.
            // Crucial: an erail list can contain numbers absent from the bundled (older) DB.
            // Previously repo.live() returned null BEFORE asking NTES in that case (20689).
            // Use the dated live-list identity only as an endpoint scaffold; the official STNS
            // must independently prove its train, day and passenger span before gaining any rows.
            val hasDeviceRoute = runCatching { repo.queries.route(trainNo) }.getOrNull() != null
            val fallbackRoute = if (!hasDeviceRoute &&
                repo.savedSchedule(trainNo, fromCode, toCode, originEpochDay) == null) {
                (liveIdentity ?: runCatching { identityFetch.identityOf(trainNo) }.getOrNull())
                    ?.takeIf { it.identity.number == trainNo }
                    ?.also { liveIdentity = it }
                    ?.let(::syntheticRoute)
            } else null
            if (fallbackRoute != null && _ui.value == null) {
                val at = System.currentTimeMillis()
                val initial = PositionEngine.locate(
                    PositionEngine.Request(fallbackRoute, null, nowMinute(at), at, null))
                lastAnswer = initial
                _ui.value = buildUi(fallbackRoute, initial, emptyList(),
                    listOf("Only endpoint times available while checking the dated official station table."))
            }
            val live = runCatching {
                repo.live(
                    trainNo = trainNo, fromCode = fromCode, toCode = toCode,
                    originEpochDay = originEpochDay,
                    previous = previous?.let { PositionEngine.TrainPositionInput(it) },
                    fallbackRoute = fallbackRoute,
                )
            }.getOrNull()
            if (live == null) {
                // A failed lookup is not always a missing train: on a network that kills every live
                // ask, repo.live can fail TRANSIENTLY for a train whose full route sits on this very
                // device - and the owner's 16:47 paste reported exactly that: "some stations are
                // also missing after refresh". So before any endpoint-only view, re-read the offline
                // route; the synthetic two-stop view is ONLY for trains the offline copy truly lacks.
                val cachedRoute = runCatching { repo.queries.route(trainNo) }.getOrNull()
                if (cachedRoute != null) {
                    val now = System.currentTimeMillis()
                    val answer = PositionEngine.locate(
                        PositionEngine.Request(cachedRoute, null, nowMinute(now), now, previous),
                    )
                    lastAnswer = answer
                    _ui.value = buildUi(
                        cachedRoute, answer, emptyList(),
                        listOf("the live fetch failed this tick — the on-device timetable is shown, unchanged"),
                    )
                    _error.value = null
                    maybeCrowd()
                    maybeMirror()
                    maybeRouteExtent()
                    return
                }
                // The train the list advertised may exist ONLY in the live source (the offline copy
                // lacks it - 20689 on the owner's device). A dead end here is the worst sentence in
                // the app: render what we DO know - identity and the two endpoint times the erail
                // tile carries - and name exactly what is missing. Never silence.
                val id = liveIdentity ?: runCatching { identityFetch.identityOf(trainNo) }.getOrNull()
                if (id == null) {
                    _error.value = "No timetable for $trainNo on this device, and the live source did not answer."
                    return
                }
                liveIdentity = id
                val route = syntheticRoute(id)
                val now = System.currentTimeMillis()
                val answer = PositionEngine.locate(
                    PositionEngine.Request(route, null, nowMinute(now), now, null),
                )
                lastAnswer = answer
                _ui.value = buildUi(route, answer, emptyList(), listOf(fallbackNote(id, fromCode, toCode)))
                _error.value = null
                maybeCrowd()
                maybeMirror()
                maybeRouteExtent()
                return
            }
            lastAnswer = live.answer
            officialStaticKnown = officialStaticKnown || live.officialScheduleVerified ||
                live.answer.position.provenance == SourceKind.PROVIDER_NTES
            _ui.value = buildUi(live.route, live.answer, live.tried, live.notes)
            _error.value = null
            maybeCrowd()
            if (officialStaticKnown) maybeRailGeo(live.route)
            if (live.answer.position.provenance != SourceKind.PROVIDER_NTES) {
                maybeMirror()
                if (!officialStaticKnown) maybeRouteExtent()
            } else {
                _mirrorState.postValue("Official NTES answered; slow independent page probes skipped")
            }
        }
    }

    private suspend fun recomputeFromCache() {
        val repo = repo() ?: return
        val cached = runCatching { repo.queries.route(trainNo) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        val answer = PositionEngine.locate(
            PositionEngine.Request(
                route = cached, snapshot = null, nowRailMinute = nowMinute(now),
                nowMillis = now, previous = lastAnswer?.position,
            )
        )
        lastAnswer = answer
        _ui.value = buildUi(cached, answer, emptyList(), emptyList())
    }

    /** Reader poll discipline (adopted from the owner's researched spec, 60-120 s window, 90 s chosen):
        the relay is free but a poll per auto-tick hammers it for data that changes by the minute, not
        by the second. The user's own publish bypasses the throttle - they just acted; they read now. */
    private fun maybeCrowd(force: Boolean = false) {
        if (!crowdOn) return
        val nowWall = System.currentTimeMillis()
        if (!force && nowWall - lastCrowdPoll < CROWD_POLL_MIN_MILLIS) return
        lastCrowdPoll = nowWall
        viewModelScope.launch {
            val codes = _ui.value?.route?.stops?.map { it.code } ?: return@launch
            val poll = withContext(Dispatchers.IO) { relay.pollRecentOrFailure(trainNo) }
            val merged = CrowdMerger.merge(poll.reports, System.currentTimeMillis())
            // Reader lines ONLY where a fresh actual exists: a 14-halt wall of "no traveller reports
            // yet" is noise, not honesty - the honest absence is one diary line and an empty column.
            val lines = CrowdMerger.lines(merged, merged.stops.map { it.stationCode }.distinct())
            _ui.postValue(_ui.value?.copy(reports = lines, relayOk = poll.relayOk))
            Diary.get()?.log("CROWD") {
                "read ${poll.reports.size} report(s) on ct-$trainNo; ${lines.size} stop(s) with fresh " +
                    "actuals, ${merged.droppedStale} dropped stale; relay " +
                    (if (poll.relayOk) "answered" else "unreachable")
            }
        }
    }

    /** Historical RailGeo was reported to carry passing-point CODES in 2019; the CI runner cannot
     * handshake with it today. Try once, on the phone, AFTER the fast official answer (never on the
     * live request's critical path). An unmatched/changed schema only creates a diagnostic, not rows. */
    private fun maybeRailGeo(official: TrainRoute) {
        if (geoRoute != null && _ui.value?.staticRoute != null) return
        val now = System.currentTimeMillis()
        if (now - geoAttemptAt < 3_600_000L) return
        geoAttemptAt = now
        viewModelScope.launch {
            val repo = repo() ?: return@launch
            if (loadGeoCache(official, repo)) {
                _ui.value?.let { _ui.value = it.copy(staticRoute = geoRoute) }
                return@launch
            }
            val date = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.ENGLISH).apply {
                timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
            }.format(java.util.Date(originEpochDay * 86_400_000L))
            val url = "https://enquiry.indianrail.gov.in/ntes/RailGeo?action=getTrainSchedule" +
                "&trainNo=$trainNo&trainStartDate=" + java.net.URLEncoder.encode(date, "UTF-8")
            val response = withContext(Dispatchers.IO) {
                Http(app).get(url, timeoutMillis = 3_500, retries = 0, budgetMillis = 3_500)
            }
            val body = (response as? Http.Result.Ok)?.body
            if (body == null) {
                Diary.get()?.log("ROUTE") { "RailGeo phone request: ${(response as Http.Result.Failed).message}" }
                return@launch
            }
            val grown = withContext(Dispatchers.IO) {
                RailGeoRoute.parse(body, official) { code -> repo.queries.station(code)?.name }
            }
            if (grown == null) {
                Diary.get()?.log("ROUTE") {
                    "RailGeo returned ${body.length}B but no validated full route for $trainNo; " +
                        "schema prefix=${body.take(350).replace('\n', ' ')}"
                }
                return@launch
            }
            geoRoute = grown
            app.getSharedPreferences("railgeo_routes", 0).edit()
                .putString("$trainNo:$originEpochDay", body)
                .putLong("$trainNo:$originEpochDay:at", System.currentTimeMillis()).apply()
            _ui.value?.let { u -> if (u.route.trainNo == trainNo) _ui.value = u.copy(staticRoute = grown) }
            Diary.get()?.log("ROUTE") {
                "RailGeo validated ${grown.stops.size} ordered stations (${grown.stops.count { !it.isHalt }} " +
                    "extra route points with unknown stopping status, no assumed times); official schedule ${official.stops.size} halts"
            }
        }
    }

    private fun loadGeoCache(base: TrainRoute, repo: RailRepository): Boolean {
        val key = "$trainNo:$originEpochDay"
        val prefs = app.getSharedPreferences("railgeo_routes", 0)
        val savedAt = prefs.getLong("$key:at", 0L)
        if (savedAt <= 0 || System.currentTimeMillis() - savedAt !in 0L..86_400_000L) return false
        val body = prefs.getString(key, null) ?: return false
        val grown = RailGeoRoute.parse(body, base) { code -> repo.queries.station(code)?.name }
            ?: return false
        geoRoute = grown
        return true
    }

    /** One route-page attempt per 10 minutes; the answer itself is cached for a day in prefs. */
    private fun maybeRouteExtent(force: Boolean = false) {
        val nowWall = System.currentTimeMillis()
        if (!force && nowWall - extentAttemptAt < 600_000) return
        extentAttemptAt = nowWall
        viewModelScope.launch {
            val repo = repo() ?: return@launch
            if (extentRoute == null && !loadExtentCache()) {
                val got = withContext(Dispatchers.IO) { ErailRouteMirror.fetchOrWhy(trainNo) }
                if (got.halts.isEmpty()) {
                    Diary.get()?.log("ROUTE") { "full route not available: ${got.why}" }
                    return@launch
                }
                val base = _ui.value?.route
                    ?: runCatching { repo.queries.route(trainNo) }.getOrNull()
                    ?: return@launch
                val merged = mergeRouteExtents(base, got.halts)
                if (merged == null) {
                    Diary.get()?.log("ROUTE") {
                        "erail's halt list does not contain the device route in order - keeping the device table"
                    }
                    return@launch
                }
                extentRoute = merged
                extentAt = System.currentTimeMillis()
                saveExtentCache(got.halts)
            }
            val u = _ui.value ?: return@launch
            // A late, partial erail halt list must not overwrite a validated official schedule,
            // including a schedule-only response with no actual minutes yet.
            if (officialStaticKnown || u.answer.position.provenance == SourceKind.PROVIDER_NTES) return@launch
            val grown = extentRoute ?: return@launch
            _ui.postValue(buildUi(grown, u.answer, u.tried).copy(relayOk = u.relayOk))
            Diary.get()?.log("ROUTE") {
                "full route on screen: ${grown.stops.size} halts " +
                    "${grown.srcCode}->${grown.dstCode} (device timetable carries " +
                    "${u.route.stops.size}; the rest render an em-dash schedule until a timetable names their minutes)"
            }
        }
    }

    /** Day-fresh route cache, same SWR discipline (and JSON house style) as the mirror's. */
    private suspend fun loadExtentCache(): Boolean {
        if (extentRoute != null) return true
        val raw = runCatching {
            org.json.JSONObject(runCatching { repo()?.prefs?.erailRouteCache }.getOrNull() ?: "{}")
        }.getOrNull() ?: return false
        val obj = raw.optJSONObject(trainNo) ?: return false
        val at = obj.optLong("at", 0L)
        if (at == 0L || System.currentTimeMillis() - at > 86_400_000L) return false
        val arr = obj.optJSONArray("halts") ?: return false
        val halts = mutableListOf<ErailRouteMirror.Halt>()
        for (i in 0 until arr.length()) {
            val h = arr.optJSONObject(i) ?: continue
            halts += ErailRouteMirror.Halt(h.optString("n"), h.optString("c"))
        }
        if (halts.size < 2) return false
        val base = runCatching { repo()?.queries?.route(trainNo) }.getOrNull() ?: return false
        val merged = mergeRouteExtents(base, halts) ?: return false
        extentRoute = merged
        extentAt = at
        return true
    }

    private suspend fun saveExtentCache(halts: List<ErailRouteMirror.Halt>) {
        val raw = runCatching {
            org.json.JSONObject(runCatching { repo()?.prefs?.erailRouteCache }.getOrNull() ?: "{}")
        }.getOrNull() ?: return
        val obj = org.json.JSONObject().put("at", extentAt)
        val arr = org.json.JSONArray()
        for (h in halts) arr.put(org.json.JSONObject().put("n", h.name).put("c", h.code))
        obj.put("halts", arr)
        raw.put(trainNo, obj)
        // Keep the freshest twelve trains' routes; the same trim discipline as the mirror cache.
        val trimmed = org.json.JSONObject()
        val keysWithAt = mutableListOf<Pair<Long, String>>()
        for (key in raw.keys()) {
            val o = raw.optJSONObject(key) ?: continue
            keysWithAt += o.optLong("at", 0L) to key
        }
        var kept = 0
        for (entry in keysWithAt.sortedByDescending { it.first }) {
            if (kept++ >= 12) continue
            trimmed.put(entry.second, raw.optJSONObject(entry.second) ?: continue)
        }
        runCatching {
            val r = repo() ?: return
            r.prefs.erailRouteCache = trimmed.toString()
        }
    }

    /**
     * The mirror witness: while the officials refuse this device, runningstatus.in server-renders the
     * actual arrival/departure of today's run for every halt it knows about - the exact minutes this
     * product exists for (the owner's priority, verbatim: "get actual arrival and departure time").
     * It runs only when NO live data reached the device (a live answer outranks a mirror), it is
     * throttled like the relay, and its lines carry its own name - a second witness is labelled, never
     * blended into the official columns. A traveller report wins its stop; the mirror fills the rest.
     */
    private fun maybeMirror(force: Boolean = false, preferred: String? = null) {
        if (!force && _ui.value?.answer?.position?.provenance?.live == true) return
        val nowWall = System.currentTimeMillis()
        if (!force && nowWall - lastMirrorPoll < CROWD_POLL_MIN_MILLIS) return
        lastMirrorPoll = nowWall
        val job = viewModelScope.launch {
            val route = _ui.value?.route ?: runCatching { repo()?.queries?.route(trainNo) }.getOrNull()
                ?: return@launch
            if (!freshShift()) loadMirrorCache()
            val names = route.stops.map { it.code to it.name }
            _mirrorState.postValue("Checking independent feeds; page times are not actuals until validated…")
            val ckDeferred = async {
                val direct = withContext(Dispatchers.IO) { ConfirmTktMirror.fetchOrWhy(trainNo, names) }
                if (direct.ok) direct else {
                    val page = withContext(Dispatchers.IO) {
                        BrowserFetch.pageText(ConfirmTktMirror.BASE + trainNo, 25_000,
                            settleMillis = 1_200, textCap = 24_000)
                    }
                    val text = (page as? Http.Result.Ok)?.body?.let(RunningStatusMirror::stripTags)
                    if (text == null) direct else {
                        val lines = ConfirmTktMirror.parse(text, names)
                        ConfirmTktMirror.Fetched(lines, lines.size, lines.isNotEmpty(),
                            ConfirmTktMirror.stopsOf(text, names), ConfirmTktMirror.pageDayOf(text))
                    }
                }
            }
            val direct = if (System.currentTimeMillis() < directDeadUntil)
                RunningStatusMirror.Fetched(null, "recent 403; browser lane")
            else withContext(Dispatchers.IO) { RunningStatusMirror.fetchOrWhy(trainNo) }
            if (direct.why.startsWith("HTTP 403")) directDeadUntil = System.currentTimeMillis() + 600_000
            val primary = direct.actuals ?: run {
                val page = withContext(Dispatchers.IO) {
                    BrowserFetch.pageText("https://runningstatus.in/status/$trainNo", 30_000,
                        settleMillis = 1_200, textCap = 24_000)
                }
                (page as? Http.Result.Ok)?.body?.let {
                    RunningStatusMirror.parse(RunningStatusMirror.stripTags(it))
                }
            }
            val rawCk = ckDeferred.await()
            // A slower mirror must never replace a verified official screen or its route.
            if (!force && _ui.value?.answer?.position?.provenance == SourceKind.PROVIDER_NTES) return@launch
            val expectedDay = java.text.SimpleDateFormat("dd-MMM", java.util.Locale.ENGLISH).apply {
                timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
            }.format(java.util.Date(originEpochDay * 86_400_000L))
            val ck = if (rawCk.pageDay?.equals(expectedDay, true) == true) rawCk else {
                Diary.get()?.log("MIRROR", "confirmtkt page date ${rawCk.pageDay ?: "missing"} " +
                    "does not prove requested run $expectedDay; refusing all minutes")
                rawCk.copy(lines = emptyMap(), stops = emptyList(), halts = 0, ok = false)
            }
            val u = _ui.value ?: return@launch
            val nowRail = nowMinute(System.currentTimeMillis())
            val base = TrackModel.build(route, u.answer.position, u.answer.perStop, nowRail,
                route.stops.firstOrNull { it.code.equals(fromCode, true) }?.seq ?: route.stops.first().seq,
                route.stops.lastOrNull { it.code.equals(toCode, true) }?.seq ?: route.stops.last().seq,
                originEpochDay, null)
            if (primary != null && primary.runEpochDay != originEpochDay) {
                Diary.get()?.log("MIRROR", "runningstatus page run=${primary.runEpochDay} " +
                    "does not match requested run=$originEpochDay; refusing all minutes")
            }
            val primaryForRun = primary?.takeIf { it.runEpochDay == originEpochDay }
            val plausiblePrimary = validateStops(base, primaryForRun?.stops.orEmpty(), nowRail)
                .filter { st -> base.rows.any { row -> row.code.equals(st.stationCode, true) &&
                    ((row.schedArr != null && st.actArr != null) ||
                        (row.schedDep != null && st.actDep != null)) } }
            val disputed = conflictingCodes(plausiblePrimary, ck.stops, base, nowRail)
            val verifiedPrimary = plausiblePrimary.filterNot { it.stationCode.uppercase() in disputed }
            // ConfirmTkt's page mixes schedule, estimates and past runs. An answer is NOT evidence.
            // The runningstatus page often has no run date even when it returned 14 raw rows. Use
            // the dated official response as a SECOND possible witness, but only for overlapping
            // reported cells: this does not promote the other ConfirmTkt cells to observations.
            // The original answer (not the selected table) retains NTES cells on manual selection.
            val officialWitness = officialStopsForCrossCheck(lastAnswer ?: u.answer, base, nowRail)
            val witness = if (officialWitness.isNotEmpty()) officialWitness else verifiedPrimary
            val verifiedCk = crossCheckedStops(witness, ck.stops, base, nowRail)
            val ckWitnessLabel = if (officialWitness.isNotEmpty()) "NTES" else "dated runningstatus"
            Diary.get()?.log("MIRROR") {
                "ConfirmTkt ${ck.stops.size} raw, ${verifiedCk.size} overlapping halts corroborated by $ckWitnessLabel; " +
                    "official witness=${officialWitness.size}, runningstatus witness=${verifiedPrimary.size}"
            }
            mirrorCrossChecked = verifiedCk.isNotEmpty()
            val refreshed = clockAt(System.currentTimeMillis())
            mirrorSourceStates = linkedMapOf(
                RunningStatusMirror.SOURCE to (if (verifiedPrimary.isNotEmpty())
                    "live" to "${verifiedPrimary.size} verified halts" +
                        (if (disputed.isEmpty()) "" else " · ${disputed.size} disputed hidden") + " · $refreshed"
                    else "none" to "no verified halt (${direct.why.take(60)})"),
                ConfirmTktMirror.SOURCE to (if (verifiedCk.isNotEmpty())
                    "live" to "${verifiedCk.size} cells overlap $ckWitnessLabel · $refreshed"
                    else if (ck.ok) "unverified" to "page answered; minutes not corroborated"
                    else "none" to "no answer this refresh"),
            )
            mirrorSourceLines = linkedMapOf(
                RunningStatusMirror.SOURCE to primary?.let {
                    RunningStatusMirror.sentences(it.copy(stops = verifiedPrimary), route.stops.map { r -> r.code })
                }.orEmpty(),
                ConfirmTktMirror.SOURCE to ck.lines.filterKeys { code ->
                    verifiedCk.any { it.stationCode.equals(code, true) }
                },
            )
            mirrorStops = linkedMapOf(
                RunningStatusMirror.SOURCE to verifiedPrimary,
                ConfirmTktMirror.SOURCE to verifiedCk,
            )
            // Never silently switch away from the selected feed, except when the previous selection
            // was a deprecated cache entry. The dropdown and the table must describe the same witness.
            if (preferred != null) {
                if (mirrorStops[preferred].orEmpty().isNotEmpty()) mirrorActiveSource = preferred
            } else if (mirrorActiveSource == null || mirrorActiveSource == "last known")
                mirrorActiveSource = if (verifiedPrimary.isNotEmpty()) RunningStatusMirror.SOURCE
                    else if (verifiedCk.isNotEmpty()) ConfirmTktMirror.SOURCE else null
            mirrorRefreshedAt = System.currentTimeMillis()
            mirrorShiftAt = mirrorRefreshedAt
            cacheTrainNo = trainNo
            val overlaid = overlayActuals(base, verifiedPrimary, nowRail)
            val receipt = deviceReceipt(overlaid)
            delayDevice = receipt?.first ?: 0
            delayDetail = receipt?.second
            // An extrapolated position and ETA must never arise from an uncorroborated page token.
            mirrorLate = if (verifiedPrimary.isNotEmpty()) delayDevice.coerceAtLeast(0) else 0
            mirrorNote = null
            mirrorHeadline = null
            mirrorNext = null
            mirrorBadge = null
            mirrorAnchorCode = null
            mirrorSources = "${verifiedPrimary.size} verified on runningstatus.in · " +
                "${verifiedCk.size} corroborated on confirmtkt · checked $refreshed"
            _mirrorState.postValue(if (preferred != null) {
                if (mirrorStops[preferred].orEmpty().isNotEmpty())
                    "$preferred: verified for this run · $mirrorSources"
                else "$preferred: no verified same-run actuals; keeping the current source"
            } else if (verifiedPrimary.isNotEmpty()) "Verified actuals · $mirrorSources"
                else "No verified actual arrival/departure this refresh — showing timetable only")
            _ui.postValue(buildUi(u.route, u.answer, u.tried).copy(relayOk = u.relayOk))
            if (verifiedPrimary.isNotEmpty()) saveMirrorCache(mirrorSourceLines[RunningStatusMirror.SOURCE].orEmpty())
            Diary.get()?.log("MIRROR") {
                "manual=${preferred ?: "no"}; direct=${direct.why.take(45)}; " +
                    "primary raw=${primary?.stops?.size ?: 0} verified=${verifiedPrimary.size} " +
                    "disputed hidden=${disputed.joinToString(",")}; " +
                    "confirmtkt raw=${ck.stops.size} corroborated=${verifiedCk.size}; " +
                    "unverified minutes never drive position or delay"
            }
        }
        if (force) manualProbeJob = job
    }

    /** The traveller's half: publish one observation, then re-read the topic so the report shows. */
    fun reportAt(stationCode: String, kind: StopReport.Kind) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val rep = StopReport(
                trainNo = trainNo, stationCode = stationCode.uppercase(), kind = kind,
                railMinute = RailTime.istMinuteOfDay(now), reportedAtMillis = now,
            )
            val w = withContext(Dispatchers.IO) { relay.publish(rep) }
            val msg = when (w) {
                is NtfyRelay.Wire.Ok ->
                    "published: ${rep.stationCode} $kind ${rep.railMinute / 60}:" + "%02d".format(rep.railMinute % 60) +
                        " — thank you, travellers on this train will see it"
                is NtfyRelay.Wire.Unreachable -> "could not publish (${w.why}) — nothing was sent"
            }
            _reportStatus.postValue(msg)
            Diary.get()?.log("CROWD") { msg }
            if (w is NtfyRelay.Wire.Ok) maybeCrowd(force = true)
        }
    }

    /**
     * The pipe's proof, with a guardrail the vocabulary forbids us to skip: the test report travels on
     * ct-test-<nonce>, NEVER on a real train's topic - a loopback that showed up as a traveller report
     * would be exactly the fabricated witness this project promised never to create.
     */
    fun loopbackTest() {
        viewModelScope.launch {
            val nonce = java.util.UUID.randomUUID().toString().takeLast(4)
            val now = System.currentTimeMillis()
            val rep = StopReport("test-$nonce", "TEST", StopReport.Kind.DEPARTED,
                RailTime.istMinuteOfDay(now), now)
            val pub = withContext(Dispatchers.IO) { relay.publish(rep) }
            if (pub !is NtfyRelay.Wire.Ok) {
                val why = (pub as? NtfyRelay.Wire.Unreachable)?.why ?: "unknown"
                _reportStatus.postValue("loopback FAILED at publish: $why")
                return@launch
            }
            // ntfy.sh serves from a load-balanced cluster: the publish lands on one node and the
            // read-back can hit another before the caches sync - a single 1.5 s read failed THREE
            // times on the owner's phone (paste #16) with relayOk=true. The retry window is the fix:
            // same topic, same test minute, a few patient reads - never a second publish.
            var back = withContext(Dispatchers.IO) { relay.pollRecentOrFailure("test-$nonce") }
            var attempt = 1
            while (attempt < 4 &&
                !(back.relayOk && back.reports.any { it.stationCode == "TEST" && it.railMinute == rep.railMinute })
            ) {
                delay(attempt * 2_500L)
                attempt++
                back = withContext(Dispatchers.IO) { relay.pollRecentOrFailure("test-$nonce") }
            }
            // Three verdicts, not one: PARSED (the pipe and the parser both work), IN-THE-BYTES (the
            // relay returned the message but our parser could not read it - parser guilty, and the raw
            // line is captured below), or NEVER-RETURNED (the relay accepted the publish and never
            // serves it back - relay guilty). Paste #19 made both failure shapes plausible.
            val id = (pub as NtfyRelay.Wire.Ok).body.substringAfter("\"id\":\"", "").substringBefore('"')
            val parsedMatch = back.reports.any { it.stationCode == "TEST" && it.railMinute == rep.railMinute }
            val rawHit = id.isNotEmpty() && back.rawBody.contains(id)
            val msg = when {
                parsedMatch ->
                    "loopback OK: published and read back on ct-test-$nonce — the report pipe works on this device"
                rawHit -> {
                    val rawLine = back.rawBody.lineSequence().firstOrNull { it.contains(id) }?.take(600) ?: ""
                    Diary.get()?.log("CROWD") { "loopback raw line: $rawLine" }
                    "loopback: the relay DID return the message ($id) but it did not parse - parser guilty, raw line captured in the diary"
                }
                else ->
                    "loopback FAILED: published $id but the poll never returned it after $attempt read(s) - relay-side"
            }
            _reportStatus.postValue(msg)
            Diary.get()?.log("CROWD") { msg }
        }
    }

    private fun clockAt(at: Long): String = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        .format(java.util.Date(at))

    private fun nowMinute(now: Long): Int =
        ((RailTime.istEpochDay(now) - originEpochDay) * RailTime.MINUTES_PER_DAY +
            RailTime.istMinuteOfDay(now)).toInt()

    private fun buildUi(
        route: TrainRoute, answer: PositionEngine.Answer, tried: List<String>,
        notes: List<String> = emptyList(),
        /** The mirror's actuals set this clock back by the train's measured lateness (0 = schedule). */
        nowRailOverride: Int? = null,
        /** When the mirror speaks, its sentence IS the position note - it outranks the refusal text. */
        positionNote: String? = null,
    ): Ui {
        val now = System.currentTimeMillis()
        if (manualErail != null && now - manualErailAt > PositionEngine.STALE_MS) {
            manualErail = null
            if (mirrorActiveSource == "erail") mirrorActiveSource = null
        }
        val nowRail = nowRailOverride ?: nowMinute(now)
        val pos = answer.position
        // An explicit mirror selection is an independent witness, not an overlay on
        // NTES's 15 actuals. Start from the schedule and add ONLY that mirror's
        // validated cells; otherwise the dropdown would misattribute official minutes.
        val selectedMirrorForTable = freshShift() && mirrorActiveSource in
            setOf(RunningStatusMirror.SOURCE, ConfirmTktMirror.SOURCE, "last known") &&
            mirrorStops[mirrorActiveSource].orEmpty().isNotEmpty()
        val tablePos = if (selectedMirrorForTable) pos.copy(
            provenance = SourceKind.OFFLINE_SCHEDULE, anchorSeq = 0, nextSeq = 0,
            fraction = 0f, delayMinutes = null, etaNextMinute = null) else pos
        val model = TrackModel.build(
            route = route, pos = tablePos,
            perStop = if (selectedMirrorForTable) emptyMap() else answer.perStop,
            nowRailMinute = nowRail,
            boardSeq = route.stops.firstOrNull { it.code.equals(fromCode, true) }?.seq
                ?: route.stops.firstOrNull()?.seq ?: 1,
            alightSeq = route.stops.lastOrNull { it.code.equals(toCode, true) }?.seq
                ?: route.stops.lastOrNull()?.seq ?: route.lastSeq,
            runEpochDay = originEpochDay,
            // The source's own sentence leads. It is the only one that names a cause the traveller can act
            // on — no network, a timeout, a refusal, or the case this endpoint really is: it answered about
            // the train and published no arrival or departure minute at all. "No live report reached this
            // device" is the last resort, for when no source had anything more specific to say.
            note = positionNote
                ?: notes.firstOrNull { it.isNotBlank() } ?: answer.note
                ?: if (pos.provenance.live) null
                else "No live report reached this device — every minute below is the timetable, " +
                    "not the train.",
        )
        // The label is where the founding bug lived (16228: the timetable said one route, the railway
        // another). When a live identity reached the device, the label says both readings - the overlay
        // is a sentence, never a silent swap, because the stop table below is still the timetable's.
        val labelled = model.copy(
            routeLabel = identityOverlay(route.srcCode, route.dstCode, liveIdentity?.identity)
                ?.let { "${model.routeLabel} · $it" }
                ?: model.routeLabel,
        )
        // THE OWNER'S COLUMNS, FED: the active witness's structured minutes are written INTO the
        // rows - Act under Sch - whenever that witness answered, whether or not the erail chain
        // did. This is the gate paste #28 caught and #29 proved twice more: the mirror answered
        // while the screen kept saying "schedule only". Erail's refusal no longer buries a
        // witness that spoke.
        val selectedMirror = mirrorActiveSource in setOf(RunningStatusMirror.SOURCE,
            ConfirmTktMirror.SOURCE, "last known")
        val overlayStops = if (freshShift() && selectedMirror &&
            (pos.provenance != SourceKind.PROVIDER_NTES || selectedMirrorForTable))
            mirrorStops[mirrorActiveSource].orEmpty() else emptyList()
        val withActuals = if (overlayStops.isEmpty()) labelled
            else overlayActuals(labelled, overlayStops, nowMinute(now))
        // Refreshes must not wipe what the witnesses wrote (paste #24 wiped the reader lines, paste #26
        // wiped the actuals headline): everything the mirror said is re-applied from state while fresh.
        val live = freshShift()
        // No deviation to build a status word from is still not silence: one sentence from the
        // freshest reported halt keeps the top box in the mirror's voice.
        val verified = if (live && overlayStops.isNotEmpty()) mirrorVerdict(withActuals) else null
        val receipt = if (verified != null) deviceReceipt(withActuals) else null
        val ntesActualCount = answer.perStop.values.count {
            it.actualArrivalMin != null || it.actualDepartureMin != null
        }
        val sourceNames = linkedMapOf(
            "NTES (official)" to (if (answer.position.provenance == SourceKind.PROVIDER_NTES && ntesActualCount > 0)
                "live" to "$ntesActualCount stops with actual minutes"
                else if (answer.position.provenance == SourceKind.CACHED && ntesActualCount > 0)
                "last-known" to "$ntesActualCount saved stops; not live"
                else "none" to "no per-stop actuals on this device"),
            RunningStatusMirror.SOURCE to (mirrorSourceStates[RunningStatusMirror.SOURCE]
                ?: ("none" to "not answered yet")),
            ConfirmTktMirror.SOURCE to (mirrorSourceStates[ConfirmTktMirror.SOURCE]
                ?: ("none" to "not answered yet")),
            "erail" to (if (manualErail != null) "live" to (manualErailDetail ?: "verified manual result")
                else if (manualErailDetail != null) "unverified" to manualErailDetail!!
                else if (answer.position.provenance == SourceKind.PROVIDER && ntesActualCount > 0)
                "live" to "$ntesActualCount stops with actual minutes"
                else if (liveIdentity != null) "unverified" to "identity only; no actual halt times"
                else "none" to "no per-stop actuals"),
            "ixigo" to ("unverified" to "probe only; no verified per-stop feed"),
        )
        val selectable = linkedMapOf<String, Map<String, String>>()
        if ("last known" in mirrorSourceLines) sourceNames["last known"] =
            "last-known" to "cached for this run; not a new observation"
        for (name in sourceNames.keys) selectable[name] = mirrorSourceLines[name].orEmpty()
        val selected = mirrorActiveSource ?: if (answer.position.provenance == SourceKind.PROVIDER)
            "erail" else "NTES (official)"
        val lastReported = withActuals.rows.lastOrNull { it.source == TrackModel.MinuteSource.REPORTED }
        val nextRow = lastReported?.let { r -> withActuals.rows.getOrNull(withActuals.rows.indexOf(r) + 1) }
        val nextStatement = nextRow?.let { r ->
            if (r.source == TrackModel.MinuteSource.PROJECTED && r.showArr != null && r.showArr >= nowMinute(now))
                "Next ${r.name}: estimated ${RailTime.formatRailMinute(r.showArr)} (not reported)"
            else "Next ${r.name}: arrival not confirmed"
        }
        return Ui(
            route = route, answer = if (selectedMirrorForTable) answer.copy(position = tablePos,
                perStop = emptyMap()) else answer, model = withActuals,
            boarding = withActuals.rows.firstOrNull { it.isBoarding },
            alighting = withActuals.rows.firstOrNull { it.isAlighting },
            nowRailMinute = nowRail, tried = tried, loading = false,
            reports = mirrorSourceLines[mirrorActiveSource] ?: _ui.value?.reports ?: emptyMap(),
            relayOk = _ui.value?.relayOk ?: true,
            anchorCode = if (verified != null) lastReported?.code else null,
            actualsHeadline = verified?.first?.let { if (selected == "last known")
                "Last known at ${clockAt(mirrorRefreshedAt)} · $it" else it },
            actualsNext = if (verified != null) nextStatement else null,
            actualsBadge = if (verified != null) (if (selected == "last known")
                "CACHED · $selected" else "VERIFIED · $selected") else null,
            actualsLate = receipt?.first,
            sourcesLine = if (verified != null) mirrorSources else null,
            sourceLines = selectable,
            activeSource = selected,
            actualsSource = if (verified != null) selected else null,
            sourceStates = sourceNames,
            lastUpdated = (mirrorRefreshedAt.takeIf { it > 0 } ?: lastFetchAt.takeIf { it > 0 })
                ?.let { (if (verified != null) "Verified " else "Last checked ") + clockAt(it) },
            delayDetail = receipt?.second,
            staticRoute = geoRoute?.takeIf { geo ->
                geo.srcCode.equals(route.srcCode, true) && geo.dstCode.equals(route.dstCode, true) &&
                    route.stops.map { it.code.uppercase() }.let { required ->
                        geo.stops.map { it.code.uppercase() }.filter { it in required } == required
                    }
            },
        )
    }

    /**
     * Which rows the list should carry, given the screen's "my segment / full route" toggle.
     *
     * A 950-halt passenger train costs a layout pass per bound row, and nobody scrolls 950 rows to find
     * themselves in one, so the window is bounded at [MAX_ROWS] and centred on the anchor when a position
     * exists (otherwise on the boarding stop). The caller prints what was hidden, which is the difference
     * between a bounded list and a truncated one.
     */
    fun windowFor(rows: List<TrackModel.Stop>, anchorSeq: Int, fullRoute: Boolean): IntRange {
        val base = if (fullRoute) 0 until rows.size else segmentRange(rows)
        if (base.last - base.first + 1 <= MAX_ROWS) return base
        val centre = rows.indexOfFirst { it.seq == anchorSeq }.takeIf { it >= 0 }
            ?: rows.indexOfFirst { it.isBoarding }.takeIf { it >= 0 }
            ?: (base.first + base.last) / 2
        val half = MAX_ROWS / 2
        val lo = (centre - half).coerceIn(0, (rows.size - MAX_ROWS).coerceAtLeast(0))
        return lo until (lo + MAX_ROWS)
    }

    private fun segmentRange(rows: List<TrackModel.Stop>): IntRange {
        val b = rows.indexOfFirst { it.isBoarding }
        val a = rows.indexOfLast { it.isAlighting }
        return if (b in 0..a) b..a else 0 until (rows.size - 1).coerceAtLeast(0)
    }

    companion object {
        /** Enough rows to read a real route without a stutter on a five-year-old phone. */
        const val MAX_ROWS = 140

        /** The researched spec's 60-120 s cache window, midpoint adopted for relay reads. */
        const val CROWD_POLL_MIN_MILLIS = 90_000L

        /**
         * What the live identity adds to the timetable's route label, as a pure function (the screen
         * computes nothing, 8.2). Null: no identity reached the device - the label stays. Match: the
         * confirmation is stated, not implied. Mismatch: BOTH readings in one line, and the table is
         * named as the timetable's - the label may be corrected but the times below it are not, not
         * until a real per-stop source exists (T20.1's paste, 17.1's ladder).
         */
        /**
         * The two-stop route a live-only train can honestly render: origin departure and terminus
         * arrival from the erail tile, the day wrap folded into the terminus stop's journeyDay.
         * Nothing in it claims per-stop knowledge the source never gave.
         */
        internal fun syntheticRoute(live: ErailLiveIdentity): TrainRoute = TrainRoute(
            trainNo = live.identity.number,
            trainName = live.identity.name,
            srcCode = live.identity.fromCode,
            srcName = live.identity.fromCode,
            dstCode = live.identity.toCode,
            dstName = live.identity.toCode,
            totalKm = 0,
            totalMin = null,
            runDays = null,
            returnNo = null,
            stops = listOf(
                StopRow(
                    seq = 1, code = live.identity.fromCode, name = live.identity.fromCode,
                    state = null, lat = null, lon = null,
                    arrivalMin = null, departureMin = live.depMinute,
                    journeyDay = 0, schedKm = 0, isHalt = true,
                ),
                StopRow(
                    seq = 2, code = live.identity.toCode, name = live.identity.toCode,
                    state = null, lat = null, lon = null,
                    arrivalMin = live.arrMinute, departureMin = null,
                    journeyDay = live.alightDayOffset, schedKm = 0, isHalt = true,
                ),
            ),
            segments = emptyList(),
        )

        private fun slip(actual: Int, scheduled: Int): Int =
            Math.floorMod(actual - scheduled + 720, 1440) - 720

        /** Refuse a clock-only token that is hours before its own displayed stop, ahead of now,
         * before the run began, or out of route order. Never wrap an implausible -237 min into a
         * seemingly valid delay, and never use the feed's separate schedule as the baseline. */
        internal fun validateStops(
            model: TrackModel.View, stops: List<RunningStatusMirror.Stop>, nowRail: Int,
        ): List<RunningStatusMirror.Stop> {
            val byCode = stops.associateBy { it.stationCode.uppercase() }
            val origin = model.rows.firstOrNull()?.schedDep ?: model.rows.firstOrNull()?.schedArr
            var lastMinute = origin?.minus(10) ?: Int.MIN_VALUE
            return model.rows.mapNotNull { row ->
                val st = byCode[row.code.uppercase()] ?: return@mapNotNull null
                fun accept(clock: Int?, sched: Int?): Int? {
                    if (clock == null || sched == null) return null
                    val shown = if (clock >= 1440) clock else RailTime.alignClock(clock, sched) ?: return null
                    val offset = shown - sched
                    return shown.takeIf { offset in -10..180 && shown <= nowRail + 2 &&
                        (origin == null || shown >= origin - 10) && shown >= lastMinute - 2 }
                }
                val arr = accept(st.actArr, row.schedArr)
                if (arr != null) lastMinute = arr
                val dep = accept(st.actDep, row.schedDep)
                if (dep != null) lastMinute = dep
                // Extensions without a timetable can carry a *labelled estimate*, not a confirmed Act.
                val extension = row.schedArr == null && row.schedDep == null
                st.copy(actArr = arr ?: if (extension) st.actArr else null,
                    actDep = dep ?: if (extension) st.actDep else null)
            }
        }

        /** Suppress a row when two plausible past timestamps explicitly disagree; neither witness
         * wins by ordering. Missing or implausible page times are NOT counter-evidence. */
        internal fun conflictingCodes(
            primary: List<RunningStatusMirror.Stop>, candidate: List<RunningStatusMirror.Stop>,
            model: TrackModel.View, nowRail: Int,
        ): Set<String> {
            val second = validateStops(model, candidate, nowRail)
                .associateBy { it.stationCode.uppercase() }
            return primary.filter { first ->
                val other = second[first.stationCode.uppercase()] ?: return@filter false
                (first.actArr != null && other.actArr != null &&
                    kotlin.math.abs(slip(first.actArr, other.actArr)) > 5) ||
                    (first.actDep != null && other.actDep != null &&
                        kotlin.math.abs(slip(first.actDep, other.actDep)) > 5)
            }.map { it.stationCode.uppercase() }.toSet()
        }

        /** Official same-run per-stop ACTUALS may corroborate overlapping page cells, never
         * unobserved page rows. Cached/offline/erail data cannot authenticate ConfirmTkt.
         * No clock is inferred from delay or schedule. validateStops gates future and order. */
        internal fun officialStopsForCrossCheck(
            answer: PositionEngine.Answer, model: TrackModel.View, nowRail: Int,
        ): List<RunningStatusMirror.Stop> {
            if (answer.position.provenance != SourceKind.PROVIDER_NTES) return emptyList()
            return validateStops(model, answer.perStop.map { (code, cell) ->
                RunningStatusMirror.Stop(code, null, null,
                    cell.actualArrivalMin, cell.actualDepartureMin)
            }, nowRail).filter { it.actArr != null || it.actDep != null }
        }

        /** The second page is not independent proof merely because it returned HTML. */
        internal fun crossCheckedStops(
            primary: List<RunningStatusMirror.Stop>, candidate: List<RunningStatusMirror.Stop>,
            model: TrackModel.View, nowRail: Int,
        ): List<RunningStatusMirror.Stop> {
            val clean = validateStops(model, candidate, nowRail)
            val byCode = primary.associateBy { it.stationCode.uppercase() }
            return clean.mapNotNull { st ->
                val first = byCode[st.stationCode.uppercase()] ?: return@mapNotNull null
                val arr = st.actArr?.takeIf { a -> first.actArr != null &&
                    kotlin.math.abs(slip(a, first.actArr)) <= 5 }
                val dep = st.actDep?.takeIf { d -> first.actDep != null &&
                    kotlin.math.abs(slip(d, first.actDep)) <= 5 }
                if (arr == null && dep == null) null else st.copy(actArr = arr, actDep = dep)
            }
        }

        private fun deviceReceipt(model: TrackModel.View): Pair<Int, String>? {
            val last = model.rows.lastOrNull { it.source == TrackModel.MinuteSource.REPORTED &&
                ((it.showDep != null && it.schedDep != null) ||
                    (it.showArr != null && it.schedArr != null)) } ?: return null
            val useDep = last.showDep != null && last.schedDep != null
            val shown = if (useDep) last.showDep!! else last.showArr!!
            val sched = if (useDep) last.schedDep!! else last.schedArr!!
            return slip(shown, sched) to
                "${last.code} ${RailTime.formatRailMinuteBare(sched)} → ${RailTime.formatRailMinuteBare(shown)}"
        }

        /** Only a VERIFIED stop can move an estimate. Propagate forward, never backwards from a
         * later halt; hide an ETA already behind the device clock instead of printing time travel. */
        internal fun overlayActuals(
            model: TrackModel.View, stops: List<RunningStatusMirror.Stop>, nowRail: Int,
        ): TrackModel.View {
            val byCode = validateStops(model, stops, nowRail).associateBy { it.stationCode.uppercase() }
            // Project only the single next station after the final verified stop.
            val lastVerified = model.rows.indexOfLast { r -> byCode[r.code.uppercase()]?.let { st ->
                st.actArr != null || st.actDep != null } == true }
            val nextSeq = model.rows.getOrNull(lastVerified + 1)?.seq
            var carried: Int? = null
            val origin = model.rows.firstOrNull()?.schedDep ?: model.rows.firstOrNull()?.schedArr
            return model.copy(rows = model.rows.map { row ->
                val st = byCode[row.code.uppercase()]
                if (row.schedArr == null && row.schedDep == null) {
                    return@map if (st?.actArr != null || st?.actDep != null) row.copy(
                        showArr = st?.actArr, showDep = st?.actDep,
                        source = TrackModel.MinuteSource.PROJECTED) else row
                }
                val arr = st?.actArr?.takeIf { row.schedArr != null }
                val dep = st?.actDep?.takeIf { row.schedDep != null }
                if (arr != null || dep != null) {
                    val actual = if (dep != null && row.schedDep != null) dep to row.schedDep
                        else arr!! to row.schedArr!!
                    carried = slip(actual.first, actual.second)
                    return@map row.copy(showArr = arr, showDep = dep, delay = carried,
                        source = TrackModel.MinuteSource.REPORTED)
                }
                if (row.source == TrackModel.MinuteSource.REPORTED) return@map row
                val offset = carried?.takeIf { row.seq == nextSeq }
                val arrEst = if (offset == null) null else row.schedArr?.plus(offset)
                val depEst = if (offset == null) null else row.schedDep?.plus(offset)
                val pivot = arrEst ?: depEst
                if (pivot != null && pivot >= nowRail - 2 && (origin == null || pivot >= origin - 10))
                    row.copy(showArr = arrEst, showDep = depEst, delay = offset,
                        source = TrackModel.MinuteSource.PROJECTED)
                else row.copy(source = TrackModel.MinuteSource.SCHEDULE, delay = null)
            })
        }

        /**
         * The top box's sentence when the mirror answered but nothing deviated yet: the freshest
         * REPORTED halt, its minute, and its slip against the displayed timetable. Null when no row
         * carries an observed minute - the screen then says plainly that no halt is reported yet.
         */
        internal fun mirrorVerdict(model: TrackModel.View): Pair<String, Int>? {
            val last = model.rows.lastOrNull { it.source == TrackModel.MinuteSource.REPORTED &&
                (it.showDep != null || it.showArr != null) } ?: return null
            val dep = last.showDep != null && last.schedDep != null
            val actual = if (dep) last.showDep!! else last.showArr ?: return null
            val schedule = if (dep) last.schedDep!! else last.schedArr ?: return null
            val d = slip(actual, schedule)
            val word = when {
                d > 2 -> "$d min late"
                d < -2 -> "${-d} min early"
                else -> "on time"
            }
            return "Last verified: ${if (dep) "left" else "arrived at"} ${last.code} at " +
                RailTime.formatRailMinuteBare(actual) + " · $word" to d
        }

        /**
         * The device halts must appear in the page's list, IN ORDER, or the merge is refused - a
         * route is never guessed. Device rows keep their seqs (the position answer stays valid);
         * added rows take seqs outside the device band, no minutes, no km.
         */
        internal fun mergeRouteExtents(base: TrainRoute, halts: List<ErailRouteMirror.Halt>): TrainRoute? {
            val baseCodes = base.stops.map { it.code.uppercase() }
            val pageCodes = halts.map { it.code.uppercase() }
            var at = -1
            loop@ for (off in 0..pageCodes.size - baseCodes.size) {
                for (i in baseCodes.indices) {
                    if (pageCodes[off + i] != baseCodes[i]) continue@loop
                }
                at = off
                break
            }
            if (at < 0) return null
            val before = halts.subList(0, at)
            val after = halts.subList(at + baseCodes.size, halts.size)
            val pre = before.mapIndexed { i, h ->
                StopRow(
                    seq = i - before.size, code = h.code, name = h.name, state = null,
                    lat = null, lon = null, arrivalMin = null, departureMin = null,
                    journeyDay = 0, schedKm = -1, isHalt = true,
                )
            }
            val post = after.mapIndexed { i, h ->
                StopRow(
                    seq = base.stops.last().seq + 1 + i, code = h.code, name = h.name, state = null,
                    lat = null, lon = null, arrivalMin = null, departureMin = null,
                    journeyDay = 0, schedKm = -1, isHalt = true,
                )
            }
            val stops = pre + base.stops + post
            return base.copy(
                stops = stops,
                srcCode = stops.first().code, srcName = stops.first().name,
                dstCode = stops.last().code, dstName = stops.last().name,
            )
        }
        /** The sentence under the dead-end's replacement: what we know, and exactly what we do not. */
        internal fun fallbackNote(live: ErailLiveIdentity, searchedFrom: String, searchedTo: String): String {
            val base = "This train is not in the device's offline timetable — the two times are the live " +
                "source's origin→terminus times (via erail); per-stop halts are not available on this device."
            val pairMatches = live.identity.fromCode.equals(searchedFrom, true) &&
                live.identity.toCode.equals(searchedTo, true)
            return if (pairMatches) base
            else "the live source says this number runs ${live.identity.fromCode} → ${live.identity.toCode}, " +
                "not $searchedFrom → $searchedTo. " + base
        }

        internal fun identityOverlay(srcCode: String, dstCode: String, id: TrainIdentity?): String? = when {
            id == null -> null
            id.fromCode.equals(srcCode, ignoreCase = true) &&
                id.toCode.equals(dstCode, ignoreCase = true) ->
                "identity confirmed live (via erail)"
            else ->
                "the live source says this number runs ${id.fromCode} → ${id.toCode} (via erail); " +
                    "this device's timetable says $srcCode → $dstCode — the stop table below is the timetable's"
        }
    }
}
