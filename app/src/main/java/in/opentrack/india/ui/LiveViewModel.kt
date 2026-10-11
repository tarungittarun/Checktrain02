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
import `in`.opentrack.india.model.TrainRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
) : ViewModel() {

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

    fun setAutoRefresh(enabled: Boolean) {
        auto = enabled
    }

    /** [manual] is only about the log: a tap and an auto tick take the same path, and knowing which one
     * produced a result is what makes a timing question (does a second request interfere with the first?)
     * answerable from a file instead of from a guess. */
    fun refresh(manual: Boolean = false) {
        Diary.get()?.log("UI", if (manual) "refresh tapped by the user" else "refresh (auto tick)")
        if (manual) {
            // The traveller pressed the button. A background experiment that is holding a socket to find out
            // why the last press failed is now the reason this press is slow, so it is told to stop; the
            // diagnostic resumes on the next refusal and has never been the point of the screen.
            FeedProbe.requestCancel()
        }
        val previous = lastAnswer?.position
        viewModelScope.launch {
            val repo = repo() ?: run {
                _error.value = "Timetable is not installed on this device yet."
                return@launch
            }
            val live = runCatching {
                repo.live(
                    trainNo = trainNo, fromCode = fromCode, toCode = toCode,
                    originEpochDay = originEpochDay,
                    previous = previous?.let { PositionEngine.TrainPositionInput(it) },
                )
            }.getOrNull()
            if (live == null) {
                _error.value = "No timetable for $trainNo on this device."
                return@launch
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
        return Ui(
            route = route, answer = answer, model = model,
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
    }
}
