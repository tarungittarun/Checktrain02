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
import `in`.opentrack.india.domain.PositionEngine
import `in`.opentrack.india.domain.Suggestion
import `in`.opentrack.india.domain.TrackModel
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
        /** Per-witness reader lines; the chips switch which one the table shows. */
        val sourceLines: Map<String, Map<String, String>> = emptyMap(),
        /** The witness the table is currently showing - the highlighted chip. */
        val activeSource: String? = null,
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
    private var mirrorNote: String? = null
    private var mirrorShiftAt = 0L
    private var mirrorAnchorCode: String? = null
    private var mirrorHeadline: String? = null
    private var mirrorNext: String? = null
    private var mirrorBadge: String? = null
    private var mirrorSources: String? = null
    private var mirrorSourceLines: Map<String, Map<String, String>> = emptyMap()
    private var mirrorActiveSource: String? = null
    private var mirrorRefreshedAt = 0L
    private var cacheTrainNo: String? = null

    private fun freshShift(): Boolean =
        cacheTrainNo == trainNo && System.currentTimeMillis() - mirrorShiftAt < 1_800_000

    /** The table's lines follow the selected chip; the merged view is the fallback. */
    fun setActiveSource(name: String) {
        mirrorActiveSource = name
        val lines = mirrorSourceLines[name]
        val u = _ui.value ?: return
        _ui.postValue(u.copy(activeSource = name, reports = lines ?: u.reports))
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
        val at = obj.optLong("at", 0L)
        if (at == 0L || System.currentTimeMillis() - at > 6 * 3_600_000L) return
        val lines = LinkedHashMap<String, String>()
        val keys = obj.optJSONObject("lines")
        if (keys != null) for (k in keys.keys()) lines[k] = keys.optString(k)
        if (lines.isEmpty()) return
        cacheTrainNo = trainNo
        mirrorSourceLines = mapOf("last known" to lines)
        mirrorActiveSource = "last known"
        mirrorAnchorCode = obj.optString("anchor").ifBlank { null }
        mirrorHeadline = obj.optString("headline").ifBlank { null }
        mirrorNext = obj.optString("next").ifBlank { null }
        mirrorBadge = obj.optString("badge").ifBlank { null }
        mirrorLate = obj.optInt("late", 0)
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
            .put("refreshed", refreshed)
            .put("late", mirrorLate)
        mirrorAnchorCode?.let { obj.put("anchor", it) }
        mirrorHeadline?.let { obj.put("headline", it) }
        mirrorNext?.let { obj.put("next", it) }
        mirrorBadge?.let { obj.put("badge", it) }
        mirrorNote?.let { obj.put("note", it) }
        val lines = org.json.JSONObject()
        for ((k, v) in display) lines.put(k, v)
        obj.put("lines", lines)
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
        if (pollJob != null) return
        refresh()
        fetchLiveIdentity()
        pollJob = viewModelScope.launch {
            while (isActive) {
                val seconds = repo()?.prefs?.autoRefreshSeconds ?: 60
                if (seconds <= 0 || !auto) {
                    // Paste #28: the owner switched auto OFF and watched the network keep retrying.
                    // Off means OFF - no re-derive, no mirror, no probes; the screen holds what it
                    // has until the traveller taps Refresh. A phone on a train pays for every byte.
                    delay(30_000)
                } else {
                    delay(seconds * 1000L)
                    refresh()
                }
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
        auto = enabled
    }

    /** [manual] is only about the log: a tap and an auto tick take the same path, and knowing which one
     * produced a result is what makes a timing question (does a second request interfere with the first?)
     * answerable from a file instead of from a guess. */
    fun refresh(manual: Boolean = false) {
        Diary.get()?.log("UI", if (manual) "refresh tapped by the user" else "refresh (auto tick)")
        _checking.postValue(true)
        if (manual) {
            // The traveller pressed the button. A background experiment that is holding a socket to find out
            // why the last press failed is now the reason this press is slow, so it is told to stop; the
            // diagnostic resumes on the next refusal and has never been the point of the screen.
            FeedProbe.requestCancel()
        }
        val previous = lastAnswer?.position
        viewModelScope.launch {
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
            // The witnesses start NOW, in parallel with the erail chain - not after it. Paste #27: on a
            // fluctuating network the chain held the screen for minutes while the mirror needed 14 s.
            // The 90 s throttle makes the later, post-buildUi calls no-ops.
            maybeMirror()
            maybeCrowd()
            // And the traveller does NOT stare at a spinner while they run: the offline timetable
            // paints this second (paste #27's "journey completes before it loads"), the actuals
            // land on top of it when a witness answers.
            if (_ui.value == null) {
                val now0 = System.currentTimeMillis()
                val cachedRoute = runCatching { repo.queries.route(trainNo) }.getOrNull()
                if (cachedRoute != null) {
                    val cached = PositionEngine.locate(
                        PositionEngine.Request(cachedRoute, null, nowMinute(now0), now0, null),
                    )
                    lastAnswer = cached
                    _ui.value = buildUi(
                        cachedRoute, cached, emptyList(),
                        listOf("Timetable shown while the live sources are asked — actuals land on top the moment a source answers."),
                    )
                    _error.value = null
                    Diary.get()?.log("UI") {
                        "instant offline paint for $trainNo (${cachedRoute.stops.size} halts) - live chain continues underneath"
                    }
                }
            }
            val live = runCatching {
                repo.live(
                    trainNo = trainNo, fromCode = fromCode, toCode = toCode,
                    originEpochDay = originEpochDay,
                    previous = previous?.let { PositionEngine.TrainPositionInput(it) },
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
                return
            }
            lastAnswer = live.answer
            _ui.value = buildUi(live.route, live.answer, live.tried, live.notes)
            _error.value = null
            maybeCrowd()
            maybeMirror()
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

    /**
     * The mirror witness: while the officials refuse this device, runningstatus.in server-renders the
     * actual arrival/departure of today's run for every halt it knows about - the exact minutes this
     * product exists for (the owner's priority, verbatim: "get actual arrival and departure time").
     * It runs only when NO live data reached the device (a live answer outranks a mirror), it is
     * throttled like the relay, and its lines carry its own name - a second witness is labelled, never
     * blended into the official columns. A traveller report wins its stop; the mirror fills the rest.
     */
    private fun maybeMirror(force: Boolean = false) {
        if (_ui.value?.answer?.position?.provenance?.live == true) return
        val nowWall = System.currentTimeMillis()
        if (!force && nowWall - lastMirrorPoll < CROWD_POLL_MIN_MILLIS) return
        lastMirrorPoll = nowWall
        viewModelScope.launch {
            // The route may not be on screen yet (first load fires the mirror in parallel with the
            // erail chain) - the OFFLINE copy names the halts, and it is on this device.
            val stopRows = _ui.value?.route?.stops
                ?: runCatching { repo()?.queries?.route(trainNo)?.stops }.getOrNull()
                ?: return@launch
            val codes = stopRows.map { it.code }
            if (!freshShift()) loadMirrorCache()
            _mirrorState.postValue("Fetching actuals from 2 sources…")
            // The SECOND source runs in PARALLEL, not after: paste #26 waited out the primary's whole
            // chain before confirmtkt even started - seconds the traveller pays twice.
            val ckDeferred = async {
                ConfirmTktMirror.fetchOrWhy(trainNo, stopRows.map { it.code to it.name })
            }
            val directSkipped = System.currentTimeMillis() < directDeadUntil
            val got = if (directSkipped) {
                RunningStatusMirror.Fetched(null, "skipped: blocked minutes ago")
            } else {
                withContext(Dispatchers.IO) { RunningStatusMirror.fetchOrWhy(trainNo) }
            }
            var actuals = got.actuals
            if (actuals == null && got.why.startsWith("HTTP 403")) {
                // A fresh 403 buys the direct wire ten minutes of silence: an 8 s wait for a refusal
                // the network already answered is latency the traveller pays for nothing.
                directDeadUntil = System.currentTimeMillis() + 600_000
            }
            if (actuals == null) {
                // The direct wire is filtered or bot-gated on some networks (paste #21: HTTP 403 here
                // while CI fetched the same url fine) - so ask through the app's browser lane, the
                // transport this phone demonstrably lets through, before declaring the mirror dead.
                Diary.get()?.log("MIRROR") {
                    "${RunningStatusMirror.SOURCE} direct wire: ${got.why}" +
                        (if (directSkipped) " (skipped - blocked recently)" else "; asking through the app's browser")
                }
                // Paste #21 named the block: Cloudflare. The WebView lands on "Performing security
                // verification", which AUTO-CLEARS in a real browser in ~5-10 s and then loads the real
                // page - the first build read at 2 s and caught the challenge itself. Two reads, each
                // waiting 12 s inside the clear window; the mirror is declared dead only if BOTH still
                // sit behind the check.
                var laneBody: String? = null
                var laneWhy = "no answer"
                for (read in 1..2) {
                    val page = withContext(Dispatchers.IO) {
                        BrowserFetch.pageText(
                            "https://runningstatus.in/status/$trainNo", 30_000,
                            settleMillis = 12_000, textCap = 16_000,
                        )
                    }
                    val body = (page as? Http.Result.Ok)?.body
                    when {
                        body == null -> laneWhy = (page as Http.Result.Failed).message
                        body.contains("security verification") ->
                            laneWhy = "the bot check was still up at read $read"
                        else -> laneBody = body
                    }
                    if (laneBody != null) break
                }
                val lane = laneBody?.let { RunningStatusMirror.parse(RunningStatusMirror.stripTags(it)) }
                if (lane == null) {
                    val peek = laneBody?.replace('\n', ' ')?.take(150)
                    Diary.get()?.log("MIRROR") {
                        "${RunningStatusMirror.SOURCE} browser lane failed: $laneWhy" +
                            (peek?.let { "; the page began: \"$it\"" } ?: "")
                    }
                    val ck = ckDeferred.await()
                    val offline = laneWhy.contains("UnknownHost") || laneWhy.contains("imeout")
                    _mirrorState.postValue(
                        when {
                            offline && freshShift() ->
                                "No network right now — showing the last known actuals"
                            offline ->
                                "No network right now — the actuals will load when it returns"
                            else ->
                                "No actuals yet — the train may not be running today " +
                                    "(runningstatus.in: no halt rows · confirmtkt: " +
                                    if (ck.ok) "${ck.halts} halts)" else "no answer)"
                        }
                    )
                    return@launch
                }
                actuals = lane
                Diary.get()?.log("MIRROR") { "${RunningStatusMirror.SOURCE} answered through the browser lane" }
            }
            val mirrorLines = RunningStatusMirror.sentences(actuals, codes)
            if (mirrorLines.isEmpty()) {
                Diary.get()?.log("MIRROR") {
                    "${RunningStatusMirror.SOURCE} answered for $trainNo but carries no actual minutes yet"
                }
                _mirrorState.postValue(
                    "No actuals yet — the train may not be running today (runningstatus.in: 0 halts)"
                )
                return@launch
            }
            val crowd = _ui.value?.reports ?: emptyMap()
            val merged = crowd.filterKeys { it !in mirrorLines } + mirrorLines
            // The extra log the owner asked for, and the feature it announces: the schedule clock said
            // "past Shimoga" while the actuals proved the train left Birur 31 late - so the POSITION
            // follows the actuals now. Last confirmed departure, its lateness, and the clock set back
            // by exactly that lateness: "next stop in N min" becomes the train's real remaining time.
            val u = _ui.value
            val nowRail = nowMinute(System.currentTimeMillis())
            val confirmed = RunningStatusMirror.lastConfirmed(actuals, codes, nowRail)
            if (confirmed != null && confirmed.second.isNotEmpty()) {
                Diary.get()?.log("MIRROR") {
                    "excluded ${confirmed.second.size} future-minute echo halt(s) " +
                        "(${confirmed.second.joinToString(",")}) - estimates, not observations"
                }
            }
            val late = confirmed?.let { RunningStatusMirror.slipMinutes(it.first) } ?: 0
            // The SECOND witness, fetched on the same refresh: confirmtkt by station name. Its lines
            // fill only the stops the primary lacks, and its answering (or not) is shown in the age
            // line - the owner asked for two sources on screen every refresh, and honesty about which.
            val ck = ckDeferred.await()
            val ckFilled = ck.lines.filterKeys { it !in merged }
            val display = merged + ckFilled
            mirrorRefreshedAt = System.currentTimeMillis()
            val refreshed = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(mirrorRefreshedAt))
            val relayLine = "relay: " + if (_ui.value?.relayOk != false) "answered" else "unreachable"
            val sourcesLine = "${RunningStatusMirror.SOURCE} (${mirrorLines.size} halts) · " +
                "${ConfirmTktMirror.SOURCE} " + (if (ck.ok) "(${ck.halts} halts)" else "did not answer") +
                " · " + relayLine + " · refreshed $refreshed"
            mirrorSources = sourcesLine
            mirrorSourceLines = linkedMapOf(
                RunningStatusMirror.SOURCE to mirrorLines,
                ConfirmTktMirror.SOURCE to ck.lines,
            )
            mirrorActiveSource = RunningStatusMirror.SOURCE
            cacheTrainNo = trainNo
            saveMirrorCache(display)
            _mirrorState.postValue("Actuals · $sourcesLine")
            Diary.get()?.log("MIRROR") { "sources this refresh: $sourcesLine" }

            // Anchor + headline from the ACTUALS: the halt the train is making for, and the sentence
            // that names what the train really did - the schedule's own headline ("Should be past
            // Shimoga Town") contradicted the confirmed departure and read as wrong data (paste #25).
            var anchorCode: String? = null
            var headline: String? = null
            var nextLine: String? = null
            if (confirmed != null) {
                val last = confirmed.first
                val li = stopRows.indexOfFirst { it.code.equals(last.stationCode, true) }
                val next = if (li >= 0) stopRows.getOrNull(li + 1) else null
                anchorCode = next?.code ?: last.stationCode
                fun hm(m: Int): String = "${m / 60}:" + "%02d".format(m % 60)
                fun dayTag(minute: Int): String = if (minute >= 1440) " (+1d)" else ""
                // The station-status word the owner asked for, derived ONLY from what a mirror can prove:
                // a halt whose departure minute is this exact minute or the next few is being served; a
                // halt with an arrival but no departure is being sat at; anything else is running.
                val statusWord = when {
                    last.stationCode == codes.lastOrNull() && last.actArr != null ->
                        "Reached ${last.stationCode} — journey complete"
                    last.actArr != null && last.actDep == null -> "Halted at ${last.stationCode}"
                    last.actDep != null && nowRail - last.actDep in 0..2 -> "Departing ${last.stationCode} now"
                    else -> "Running"
                }
                headline = statusWord + " — Left ${last.stationCode} at " +
                    hm((last.actDep ?: 0) % 1440) + dayTag(last.actDep ?: 0) + " — " +
                    (if (late > 2) "$late min late" else "on time") +
                    (if (ck.ok) " · cross-checked" else "")
                nextLine = when {
                    next != null -> {
                        val sch = (next.arrivalMin ?: next.departureMin) ?: 0
                        val exp = sch + late
                        "Next: ${next.name} · expected ~${hm(exp % 1440)}" + dayTag(exp)
                    }
                    else -> "Reached ${last.stationCode} — the run's end"
                }
            }
            if (u != null && confirmed != null && late > 0) {
                val last = confirmed.first
                fun hm(m: Int): String = "${m / 60}:" + "%02d".format(m % 60)
                val positionNote =
                    "Position from the mirror's actuals: last confirmed ${last.stationCode} dep " +
                        hm(last.actDep!!) + " (${RunningStatusMirror.SOURCE}, $late min late) — the " +
                        "schedule clock is set back by that lateness, so the next-stop estimate is " +
                        "the train's, not the timetable's."
                mirrorLate = late
                mirrorNote = positionNote
                mirrorShiftAt = System.currentTimeMillis()
                mirrorAnchorCode = anchorCode
                mirrorHeadline = headline
                mirrorNext = nextLine
                mirrorBadge = "ACTUALS · ${RunningStatusMirror.SOURCE}"
                val shifted = buildUi(
                    u.route, u.answer, u.tried, emptyList(),
                    nowRail - late, positionNote,
                ).copy(reports = display, relayOk = u.relayOk)
                _ui.postValue(shifted)
                Diary.get()?.log("MIRROR") {
                    "position from actuals: last confirmed ${last.stationCode} dep ${hm(last.actDep)} " +
                        "($late min late) - schedule clock shifted back $late min (persists across refreshes)"
                }
            } else {
                if (confirmed != null && late <= 0) {
                    // The train caught up with its timetable (or the mirror only has on-time data):
                    // drop any stale shift instead of dragging the clock back forever.
                    mirrorLate = 0
                    mirrorNote = null
                    mirrorShiftAt = System.currentTimeMillis()
                }
                if (u != null) {
                    mirrorAnchorCode = anchorCode
                    mirrorHeadline = headline
                    mirrorNext = nextLine
                    mirrorBadge = headline?.let { "ACTUALS · ${RunningStatusMirror.SOURCE}" }
                    _ui.postValue(buildUi(u.route, u.answer, u.tried, emptyList()).copy(reports = display))
                }
            }
            Diary.get()?.log("MIRROR") {
                "${RunningStatusMirror.SOURCE} answered: ${mirrorLines.size} stop(s) with actual " +
                    "arr/dep for $trainNo"
            }
        }
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
        val shift = if (nowRailOverride == null && freshShift()) mirrorLate else 0
        val nowRail = (nowRailOverride ?: nowMinute(now)) - shift
        val pos = answer.position
        val model = TrackModel.build(
            route = route, pos = pos, perStop = answer.perStop, nowRailMinute = nowRail,
            boardSeq = route.stops.firstOrNull { it.code.equals(fromCode, true) }?.seq
                ?: route.stops.firstOrNull()?.seq ?: 1,
            alightSeq = route.stops.lastOrNull { it.code.equals(toCode, true) }?.seq
                ?: route.stops.lastOrNull()?.seq ?: route.lastSeq,
            runEpochDay = originEpochDay,
            // The source's own sentence leads. It is the only one that names a cause the traveller can act
            // on — no network, a timeout, a refusal, or the case this endpoint really is: it answered about
            // the train and published no arrival or departure minute at all. "No live report reached this
            // device" is the last resort, for when no source had anything more specific to say.
            note = positionNote ?: (if (freshShift()) mirrorNote else null)
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
        // Refreshes must not wipe what the witnesses wrote (paste #24 wiped the reader lines, paste #26
        // wiped the actuals headline): everything the mirror said is re-applied from state while fresh.
        val live = freshShift()
        return Ui(
            route = route, answer = answer, model = labelled,
            boarding = model.rows.firstOrNull { it.isBoarding },
            alighting = model.rows.firstOrNull { it.isAlighting },
            nowRailMinute = nowRail, tried = tried, loading = false,
            reports = mirrorSourceLines[mirrorActiveSource] ?: _ui.value?.reports ?: emptyMap(),
            relayOk = _ui.value?.relayOk ?: true,
            anchorCode = if (live) mirrorAnchorCode else null,
            actualsHeadline = if (live) mirrorHeadline else null,
            actualsNext = if (live) mirrorNext else null,
            actualsBadge = if (live) mirrorBadge else null,
            actualsLate = if (live && mirrorLate > 0) mirrorLate else null,
            sourcesLine = if (live) mirrorSources else null,
            sourceLines = if (live) mirrorSourceLines else emptyMap(),
            activeSource = if (live) mirrorActiveSource else null,
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
