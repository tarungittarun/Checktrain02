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
import `in`.opentrack.india.v2.source.ErailPairParser
import `in`.opentrack.india.v2.source.ErailTransport
import `in`.opentrack.india.v2.source.PairParse
import `in`.opentrack.india.v2.source.PairWire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
                    Diary.get()?.log("UI", "auto refresh is off; only re-deriving position from the cache")
                    // Disabled: still keep the screen honest by re-deriving position from the cached
                    // snapshot once a minute, so the marker does not freeze mid-leg.
                    delay(60_000)
                    recomputeFromCache()
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
            _ui.value?.let { u -> _ui.value = buildUi(u.route, u.answer, u.tried) }
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
            val live = runCatching {
                repo.live(
                    trainNo = trainNo, fromCode = fromCode, toCode = toCode,
                    originEpochDay = originEpochDay,
                    previous = previous?.let { PositionEngine.TrainPositionInput(it) },
                )
            }.getOrNull()
            if (live == null) {
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
                return
            }
            lastAnswer = live.answer
            _ui.value = buildUi(live.route, live.answer, live.tried, live.notes)
            _error.value = null
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

    private fun nowMinute(now: Long): Int =
        ((RailTime.istEpochDay(now) - originEpochDay) * RailTime.MINUTES_PER_DAY +
            RailTime.istMinuteOfDay(now)).toInt()

    private fun buildUi(
        route: TrainRoute, answer: PositionEngine.Answer, tried: List<String>,
        notes: List<String> = emptyList(),
    ): Ui {
        val now = System.currentTimeMillis()
        val nowRail = nowMinute(now)
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
            note = notes.firstOrNull { it.isNotBlank() } ?: answer.note
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
        return Ui(
            route = route, answer = answer, model = labelled,
            boarding = model.rows.firstOrNull { it.isBoarding },
            alighting = model.rows.firstOrNull { it.isAlighting },
            nowRailMinute = nowRail, tried = tried, loading = false,
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
