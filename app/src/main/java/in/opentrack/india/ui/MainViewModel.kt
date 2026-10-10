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
            val repo = app.repository { done, total -> _phase.postValue(Phase.Preparing(done, total)) }
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
        }
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

    /** Suggestion dropdown sources; suspend because both read the SQLite master on IO. */
    fun suggestStations(text: String, onResult: (List<StationHit>) -> Unit) {
        viewModelScope.launch {
            val repo = app.repository() ?: return@launch
            onResult(repo.resolveStations(text))
        }
    }

    fun suggestTrains(text: String, onResult: (List<TrainSummary>) -> Unit) {
        viewModelScope.launch {
            val repo = app.repository() ?: return@launch
            onResult(repo.findTrains(text))
        }
    }
}
