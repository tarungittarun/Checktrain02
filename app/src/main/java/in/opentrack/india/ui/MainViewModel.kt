package `in`.opentrack.india.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import `in`.opentrack.india.App
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.data.RailRepository
import `in`.opentrack.india.domain.TrainFinder
import `in`.opentrack.india.model.TrainHit
import `in`.opentrack.india.model.TrainSummary
import `in`.opentrack.india.v2.contract.Provenance
import `in`.opentrack.india.v2.contract.SourceKind
import `in`.opentrack.india.v2.domain.AssetRow
import `in`.opentrack.india.v2.domain.PairCache
import `in`.opentrack.india.v2.domain.PairMerge
import `in`.opentrack.india.v2.source.ErailPairParser
import `in`.opentrack.india.v2.source.ErailTransport
import `in`.opentrack.india.v2.source.PairAnswer
import `in`.opentrack.india.v2.source.PairQuery
import `in`.opentrack.india.v2.source.PairSource
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import `in`.opentrack.india.model.StationHit

/**
 * Home-screen state: pick a mode, pick a date, get the runs.
 *
 * Kept as a plain ViewModel with LiveData instead of a Flow/state-machine because there are exactly
 * two async operations here (prepare the database, run a search) and anything more elaborate would be
 * architecture theatre in a 5 MB app.
 */
class MainViewModel(private val app: App) : ViewModel() {

    enum class Mode { STATIONS, TRAIN }

    private val _phase = MutableLiveData<Phase>(Phase.Preparing(0, 0))
    val phase: LiveData<Phase> = _phase

    private val _results = MutableLiveData<TrainFinder.Report?>(null)
    val results: LiveData<TrainFinder.Report?> = _results

    private val _trainQuery = MutableLiveData<String>("")
    val trainQuery: LiveData<String> = _trainQuery

    /**
     * The v2 live-list state line, or null while nothing live has been said this search. Rendered as
     * the suffix of the results header - the 8.2 sentence the screen owes the passenger.
     */
    private val _liveNote = MutableLiveData<String?>(null)
    val liveNote: LiveData<String?> = _liveNote

    /** TTL cache for live pair listings (v2). A read from here is "cached", never "live". */
    private val pairCache = PairCache()

    var mode = Mode.STATIONS
        private set

    /**
     * Selected instant. Defaults to *now* rather than midnight: "trains for the next 24 hours" is the
     * question a traveller asks, and a midnight default makes the -24h half of the window return
     * yesterday's trains first, which reads as a bug.
     */
    var targetEpochDay: Long = RailTime.todayEpochDay()
        private set
    var targetMinuteOfDay: Int = RailTime.istMinuteOfDay(System.currentTimeMillis())
        private set

    /**
     * The instant the search was run against, in minutes since the IST epoch. The result rows print
     * "in 40 min" beside the departure, and that has to be relative to the day the user picked — a
     * search for tomorrow must not label tomorrow's 06:00 as "gone 20 hours ago".
     */
    val targetEpochMinute: Long get() = targetEpochDay * RailTime.MINUTES_PER_DAY + targetMinuteOfDay

    sealed class Phase {
        data class Preparing(val done: Long, val total: Long) : Phase()
        data class Ready(val dataVersion: String) : Phase()
        data class Broken(val message: String) : Phase()
        object Searching : Phase()
    }

    fun prepare() {
        if (_phase.value is Phase.Ready || _phase.value is Phase.Searching) return
        viewModelScope.launch {
            val repo = ready { done, total -> _phase.postValue(Phase.Preparing(done, total)) }.await()
            _phase.postValue(
                if (repo != null) Phase.Ready(repo.dataVersion())
                else Phase.Broken(
                    "The built-in timetable could not be installed on this device. " +
                        "Reinstall the app, or import a timetable file from Settings."
                )
            )
        }
    }

    fun setMode(mode: Mode) {
        this.mode = mode
        _results.value = null
    }

    fun setDate(epochDay: Long, minuteOfDay: Int) {
        targetEpochDay = epochDay
        targetMinuteOfDay = minuteOfDay
    }

    fun setTrainQuery(text: String) {
        _trainQuery.value = text
    }

    fun search(fromText: String, toText: String) {
        if (_phase.value !is Phase.Ready) return
        _phase.value = Phase.Searching
        _liveNote.value = null // a new search starts with no live claim at all
        viewModelScope.launch {
            val repo = app.repository() ?: run {
                _phase.value = Phase.Broken("Timetable is not installed yet.")
                return@launch
            }
            val report = when (mode) {
                Mode.STATIONS -> repo.search(fromText, toText, targetEpochDay, targetMinuteOfDay)
                Mode.TRAIN -> trainReport(repo, toText)
            }
            _results.value = report
            _phase.value = Phase.Ready(repo.dataVersion())
            // v2 slice 1: the asset answer is on screen; the live answer, if the network allows,
            // upgrades it in place - never blocks it (5.6: render first, upgrade after).
            if (mode == Mode.STATIONS && fromText.length >= 2 && toText.length >= 2) {
                fetchLivePair(fromText, toText, report)
            }
        }
    }

    /**
     * One live erail pair attempt, merged over the asset rows, every row labelled.
     *
     * This lives in this v1 file under the legacy freeze only because the results screen it upgrades
     * is v1's (the strangler replaces screens one at a time): merge, cache and typed answers are all
     * v2 (`in.opentrack.india.v2.*`), and this is the seam that feeds them to the existing adapter.
     * Cache first (a fresh cached listing renders instantly, honestly aged by the note), then at most
     * ONE network attempt, typed failure, no retries.
     */
    private fun fetchLivePair(fromCode: String, toCode: String, assetReport: TrainFinder.Report) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val cached = pairCache.get(fromCode, toCode, now)
            if (cached != null) {
                applyLive(assetReport, PairAnswer.Listing(cached.listing), now, fromCache = true)
                return@launch
            }
            val source = PairSource("erail", ErailTransport(), ErailPairParser())
            val answer = try {
                source.search(PairQuery(fromCode.uppercase(), toCode.uppercase(), budgetMs = 12_000))
            } catch (e: Exception) {
                // 5.4: no silent catch - the failure becomes the sentence the passenger reads.
                PairAnswer.CouldNotLook("unexpected ${e.javaClass.simpleName}")
            }
            if (answer is PairAnswer.Listing) {
                pairCache.put(answer.listing, System.currentTimeMillis())
            }
            applyLive(assetReport, answer, System.currentTimeMillis())
        }
    }

    /** Merge the live answer into the asset report and post it, rows labelled, state line attached. */
    private fun applyLive(assetReport: TrainFinder.Report, answer: PairAnswer, nowMillis: Long,
                          fromCache: Boolean = false) {
        val assetRows = assetReport.hits.map { h ->
            AssetRow(
                h.trainNo, h.trainName, h.fromCode, h.toCode,
                h.boardMinute, h.alightMinute,
                Provenance(SourceKind.OFFLINE_ASSET, "refdb", 0L),
            )
        }
        val merged = PairMerge.merge(assetRows, answer, targetEpochMinute)
        val (hits, hidden) = PairMerge.toTrainHits(merged.rows, targetEpochMinute) { board ->
            pickOrigin(targetEpochMinute, board)
        }
        var line = merged.stateLine
        if (hidden > 0) line += " · $hidden train(s) listed without a readable time"
        if (fromCache) {
            // A cache read is never live (7.5): the sentence says so, with the age.
            val fetchedAt = (answer as? PairAnswer.Listing)?.listing?.provenance?.fetchedAtMillis
                ?: nowMillis
            val ageMin = ((nowMillis - fetchedAt) / 60_000L).coerceAtLeast(0L)
            line += if (ageMin <= 0L) " · served from cache" else " · cached, fetched $ageMin min ago"
        }
        _results.postValue(assetReport.copy(hits = hits))
        _liveNote.postValue(line)
    }

    /**
     * Train-number/name mode: resolve the train from the offline timetable, then attach its own
     * origin→destination leg so the row is as informative as a station-pair result. The user gets the
     * same tap-through to live tracking either way.
     */
    private suspend fun trainReport(repo: RailRepository, query: String): TrainFinder.Report {
        val summaries = repo.queries.findTrains(query)
        val hits = ArrayList<TrainHit>(summaries.size)
        for (sm in summaries) {
            val route = repo.queries.route(sm.no) ?: continue
            val first = route.stops.firstOrNull()
            val last = route.stops.lastOrNull()
            if (first == null || last == null) continue
            val board = first.departureMin ?: first.arrivalMin ?: 0
            val alight = last.arrivalMin ?: last.departureMin ?: board
            // "for -24..+24 h" still applies in number mode: pick the run nearest the target instant.
            val target = targetEpochDay * RailTime.MINUTES_PER_DAY + targetMinuteOfDay
            val origin = pickOrigin(target, board)
            if (!RailTime.runsOn(sm.runDays, RailTime.istWeekdayOfEpochDay(origin))) continue
            hits += TrainHit(
                trainNo = sm.no, trainName = sm.name, trainType = sm.type,
                originDateEpochDay = origin, boardMinute = board, alightMinute = alight,
                fromCode = route.srcCode, toCode = route.dstCode,
                fromSeq = first.seq, toSeq = last.seq,
                distanceKm = (last.schedKm - first.schedKm).coerceAtLeast(0),
                runDays = sm.runDays,
            )
        }
        return TrainFinder.Report(
            from = null, to = null, hits = hits,
            reason = if (hits.isEmpty()) TrainFinder.Reason.NO_DIRECT_TRAINS else TrainFinder.Reason.OK,
        )
    }

    /** Origin date whose boarding time is closest to the target inside the ±24 h window. */
    private fun pickOrigin(targetEpochMinute: Long, boardMinute: Int): Long {
        val base = targetEpochDay - Math.floorDiv(boardMinute.toLong(), RailTime.MINUTES_PER_DAY.toLong())
        var best = base
        var bestDelta = Long.MAX_VALUE
        for (delta in -1L..1L) {
            val origin = base + delta
            val boardEpoch = origin * RailTime.MINUTES_PER_DAY + boardMinute
            val d = kotlin.math.abs(boardEpoch - targetEpochMinute)
            if (d < bestDelta) { bestDelta = d; best = origin }
        }
        return best
    }

    fun remember(hit: TrainHit) {
        viewModelScope.launch {
            app.repository()?.prefs?.pushRecent(hit.trainNo, hit.fromCode, hit.toCode, hit.originDateEpochDay)
        }
    }

    /**
     * One readiness job for the whole ViewModel, shared by `prepare()` and both suggest paths.
     *
     * This exists because `App.repository()` answers `null` while the 14 MB timetable is still being
     * extracted on a first run — and the old suggest code did `?: return@launch`, which is a silent refusal to
     * answer. Type three letters during those seconds on a fresh install and the type-ahead looked broken and
     * said nothing about why, which is exactly what it did. Awaiting one shared job instead means a query
     * typed mid-installation queues and lands as soon as the database opens.
     */
    private var readiness: Deferred<RailRepository?>? = null

    private fun ready(onProgress: (Long, Long) -> Unit = { _, _ -> }): Deferred<RailRepository?> {
        readiness?.let { return it }
        synchronized(this) {
            readiness?.let { return it }
            val job = viewModelScope.async(Dispatchers.IO) { app.prepare(onProgress) }
            readiness = job
            return job
        }
    }

    /** Let the next keystroke try again: a failed extraction should not be remembered forever. */
    private fun forgetReadiness() = synchronized(this) {
        if (readiness?.isCompleted == true) readiness = null
    }

    /**
     * The dropdown sources, with the failure reported rather than swallowed: `onMiss` is what lets the box
     * say "timetable still installing" or "lookup failed: SQLiteException" in the place the user is looking.
     */
    fun suggestStations(text: String, onResult: (List<StationHit>) -> Unit, onMiss: (String) -> Unit = {}) {
        lookup("station", text, onResult, onMiss) { it.resolveStations(text) }
    }

    fun suggestTrains(text: String, onResult: (List<TrainSummary>) -> Unit, onMiss: (String) -> Unit = {}) {
        lookup("train", text, onResult, onMiss) { it.findTrains(text) }
    }

    private fun <T> lookup(
        what: String, text: String, onResult: (List<T>) -> Unit, onMiss: (String) -> Unit,
        block: suspend (RailRepository) -> List<T>,
    ) {
        viewModelScope.launch {
            val repo = runCatching { ready().await() }.getOrNull()
            if (repo == null) {
                forgetReadiness()
                onMiss(
                    if (text.isBlank()) "Type at least three characters."
                    else "The built-in timetable is still being installed on this device — " +
                        "matches for \"$text\" will appear as soon as it is ready."
                )
                return@launch
            }
            runCatching { block(repo) }
                .onSuccess { rows ->
                    if (rows.isEmpty()) onMiss("Nothing in the built-in timetable matches \"$text\".")
                    else onResult(rows)
                }
                .onFailure { onMiss("$what lookup failed: ${it.javaClass.simpleName}") }
        }
    }
}
