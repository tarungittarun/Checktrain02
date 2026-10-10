package `in`.opentrack.india.data

import android.content.Context
import `in`.opentrack.india.core.Http
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.PositionEngine
import `in`.opentrack.india.domain.TrainFinder
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.TrainRoute
import `in`.opentrack.india.remote.ProviderChain
import `in`.opentrack.india.remote.SnapshotStore
import `in`.opentrack.india.sensor.SensorPositioner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import `in`.opentrack.india.model.StationHit
import `in`.opentrack.india.model.TrainSummary

/**
 * The only object the UI talks to. It exists to enforce three invariants that are easy to break when
 * a screen reaches for data directly:
 *
 *  1. **Offline first, always.** Search and timetable come from the on-device SQLite and are served
 *     with no network call at all; the network is only ever asked for the live delta.
 *  2. **Degradation is data, not an exception.** Every live read returns a [Live] whose [SourceKind]
 *     says how much of it is real, so a screen can render "position as of 04:12, feed unreachable"
 *     instead of a spinner or a crash.
 *  3. **One clock.** The rail-minute axis is computed here from the run's origin date, so no view has
 *     to know that stops.arrival_min is minutes from origin midnight.
 */
class RailRepository private constructor(
    private val context: Context,
    private val ref: RefDb,
    private val http: Http,
    private val store: SnapshotStore,
    val prefs: Prefs,
) {

    val queries = RefQueries(ref)
    private val sensors = SensorPositioner(context)

    data class Live(
        val route: TrainRoute,
        val answer: PositionEngine.Answer,
        val tried: List<String>,
        val originEpochDay: Long,
    ) {
        val provenance: SourceKind get() = answer.position.provenance
    }

    suspend fun search(fromText: String, toText: String, epochDay: Long, minuteOfDay: Int) =
        withContext(Dispatchers.IO) {
            TrainFinder.search(queries, fromText, toText, epochDay, minuteOfDay)
        }

    suspend fun route(trainNo: String): TrainRoute? = withContext(Dispatchers.IO) {
        queries.route(trainNo)
    }

    /**
     * Trains whose number or name matches a fragment, for the type-ahead in "train number" mode. Same
     * shape as [resolveStations]: an IO-dispatched read of the on-device master, never on the UI thread.
     */
    suspend fun findTrains(raw: String): List<TrainSummary> = withContext(Dispatchers.IO) {
        runCatching { queries.findTrains(raw) }.getOrDefault(emptyList())
    }

    suspend fun resolveStations(text: String) = withContext(Dispatchers.IO) {
        queries.resolveStations(text)
    }

    /**
     * Live view for one run: schedule from the device, live delta from the chain, position derived by
     * [PositionEngine] with the previously shown position as input (that is what makes rule 3 —
     * never move backwards — enforceable).
     */
    suspend fun live(
        trainNo: String, fromCode: String, toCode: String, originEpochDay: Long,
        previous: PositionEngine.TrainPositionInput? = null,
    ): Live? = withContext(Dispatchers.IO) {
        val route = queries.route(trainNo) ?: return@withContext null
        val nowMillis = System.currentTimeMillis()
        // rail-minute "now": minutes from the origin date's midnight, in IST
        val nowRail = ((RailTime.istEpochDay(nowMillis) - originEpochDay) * RailTime.MINUTES_PER_DAY
            + RailTime.istMinuteOfDay(nowMillis)).toInt()
        val chain = ProviderChain.build(
            http, store,
            overrideUrl = prefs.overrideUrl.ifBlank { null },
            columnHint = prefs.columnHint.ifBlank { null },
        )
        val outcome = chain.fetch(trainNo, route, originEpochDay)
        val gps = if (prefs.useLocation && hasLocationPermission()) {
            sensors.singleFix()?.let { sensors.evidence(it, route) }
        } else null
        val answer = PositionEngine.locate(
            PositionEngine.Request(
                route = route,
                snapshot = outcome.snapshot,
                nowRailMinute = nowRail,
                nowMillis = nowMillis,
                previous = previous?.position,
                gps = gps,
            )
        )
        Live(route, answer, outcome.tried, originEpochDay)
    }

    fun hasLocationPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun nearestStationsToUser(limit: Int = 3): List<StationHit> {
        val fix = sensors.latest() ?: return emptyList()
        return queries.nearestStations(fix.lat, fix.lon, limit)
    }

    fun sensor(): SensorPositioner = sensors

    fun dataVersion(): String = queries.dataVersion()

    fun clearCaches() {
        store.clear()
        http.clearCache()
        prefs.clearCaches()
    }

    companion object {
        @Volatile
        private var shared: RailRepository? = null

        /**
         * Single instance on purpose: the extracted DB handle and the HTTP cache are process-wide
         * resources, and opening the read-only database per screen would re-run SQLite's header check
         * on a 14 MB file, which is measurable on low-end hardware.
         */
        fun get(context: Context, ref: RefDb): RailRepository = shared ?: synchronized(this) {
            shared ?: RailRepository(
                context = context.applicationContext,
                ref = ref,
                http = Http(context.applicationContext),
                store = SnapshotStore(context.applicationContext),
                prefs = Prefs(context.applicationContext),
            ).also { shared = it }
        }
    }
}
