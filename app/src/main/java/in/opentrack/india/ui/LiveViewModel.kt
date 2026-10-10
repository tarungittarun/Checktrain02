package in.opentrack.india.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import in.opentrack.india.core.RailTime
import in.opentrack.india.data.RailRepository
import in.opentrack.india.domain.PositionEngine
import in.opentrack.india.model.StopRow
import in.opentrack.india.model.TrainRoute
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
    private val app: in.opentrack.india.App,
    private val trainNo: String,
    private val fromCode: String,
    private val toCode: String,
    private val originEpochDay: Long,
) : ViewModel() {

    data class Ui(
        val route: TrainRoute,
        val answer: PositionEngine.Answer,
        val stops: List<StopRow>,
        val boarding: StopRow?,
        val alighting: StopRow?,
        val nowRailMinute: Int,
        val tried: List<String>,
        /** true while the first fetch is in flight, so the view can show a schedule-only render */
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

    /** Resolved lazily: the first screen visit may be the moment the DB finishes extracting. */
    private suspend fun repo(): RailRepository? = app.repository()

    fun start() {
        if (pollJob != null) return
        refresh()
        pollJob = viewModelScope.launch {
            while (isActive) {
                val seconds = repo()?.prefs?.autoRefreshSeconds ?: 60
                if (seconds <= 0 || !auto) {
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

    fun refresh() {
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
            _ui.value = buildUi(live)
            _error.value = null
        }
    }

    private suspend fun recomputeFromCache() {
        val repo = repo() ?: return
        val cached = runCatching { repo.queries.route(trainNo) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        val nowRail = ((RailTime.istEpochDay(now) - originEpochDay) * RailTime.MINUTES_PER_DAY +
            RailTime.istMinuteOfDay(now)).toInt()
        val answer = PositionEngine.locate(
            PositionEngine.Request(
                route = cached, snapshot = null, nowRailMinute = nowRail,
                nowMillis = now, previous = lastAnswer?.position,
            )
        )
        lastAnswer = answer
        _ui.value = buildUi(cached, answer, emptyList())
    }

    private fun buildUi(live: RailRepository.Live): Ui = buildUi(live.route, live.answer, live.tried)

    private fun buildUi(
        route: TrainRoute, answer: PositionEngine.Answer, tried: List<String>,
    ): Ui {
        val pos = answer.position
        val overlay = PositionEngine.delayProfile(
            route, answer.perStop, pos.anchorSeq, pos.delayMinutes
        )
        val stops = route.stops.mapIndexed { i, s ->
            val delayArr = overlay.getOrNull(i)
            s.copy(
                delayArrivalMin = delayArr,
                status = when {
                    s.seq < pos.anchorSeq -> in.opentrack.india.model.StopStatus.DEPARTED
                    s.seq == pos.anchorSeq -> pos.status
                    else -> in.opentrack.india.model.StopStatus.UPCOMING
                },
            )
        }
        val boarding = stops.firstOrNull { it.code == fromCode }
        val alighting = stops.firstOrNull { it.code == toCode }
        return Ui(
            route = route, answer = answer, stops = stops,
            boarding = boarding, alighting = alighting,
            nowRailMinute = ((RailTime.istEpochDay(System.currentTimeMillis()) - originEpochDay) *
                RailTime.MINUTES_PER_DAY + RailTime.istMinuteOfDay(System.currentTimeMillis())).toInt(),
            tried = tried, loading = false,
        )
    }

    /** Seq bounds for "my segment" vs "whole route" in the stop list. */
    fun fromSeq(): Int = _ui.value?.boarding?.seq ?: 0
    fun toSeq(): Int = _ui.value?.alighting?.seq ?: Int.MAX_VALUE
}
