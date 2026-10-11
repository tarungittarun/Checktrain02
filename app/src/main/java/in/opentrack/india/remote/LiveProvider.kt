package `in`.opentrack.india.remote

import android.content.Context
import `in`.opentrack.india.core.Http
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.LiveSnapshot
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainRoute
import java.io.File

/**
 * The live-data port: everything the app knows about a train *right now* arrives through here.
 *
 * This interface is the direct answer to failure mode C1 in docs/ARCHITECTURE.md — earlier attempts
 * bound the domain to one endpoint, so an upstream policy change ended the product. The domain only
 * ever sees [LiveSnapshot]; implementations are replaceable at runtime and two of the four need no
 * network at all, so "the server went away" costs freshness, never capability.
 */
interface LiveProvider {
    val kind: SourceKind
    /** Blocking; called on an IO dispatcher. Must return null rather than throw. */
    fun fetch(trainNo: String, route: TrainRoute, journeyEpochDay: Long): LiveSnapshot?

    /**
     * A sentence for the traveller explaining an empty [fetch], in the language of what actually
     * happened: "the network is off", "the request timed out", "the server answered 503", "the answer
     * did not parse", "the answer carries no running times". Null means "nothing specific to say", and
     * the chain then falls back to the generic offline sentence. This exists because "no live data" and
     * "live data is unavailable for a reason you could act on" are different screens, and every other
     * train app in this space shows the first while meaning the second.
     */
    fun failureNote(): String? = null
}

/**
 * The sentence a transport failure deserves. File-level rather than one per provider because what the
 * traveller needs — "your device is offline", "the server refused us", "the reply was not data" — is
 * the same whoever made the request, and two copies of it drift the moment one gets reworded.
 */
private fun transportFailureSentence(failure: Http.Result.Failed): String =
    when (failure.kind) {
        Http.Result.Kind.OFFLINE -> "This device has no network route to the live feed."
        Http.Result.Kind.TIMEOUT -> "The live feed did not answer within the time limit."
        Http.Result.Kind.HTTP -> "The live feed refused the request" +
            (if (failure.message.isNotBlank()) " (" + failure.message + ")" else "") + "."
        Http.Result.Kind.EMPTY -> "The live feed answered with an empty body."
        Http.Result.Kind.PARSE -> "The live feed answered with something unreadable."
    }

/**
 * Primary source: the public enquiry service, keyed by train number + journey date, live flavour.
 * No API key, no account, and no proxy of ours in the middle — which is also why we parse the flat
 * `~`-delimited enquiry format instead of the HTML page (see [ErailWire]).
 */
class ErailProvider(
    private val http: Http,
    private val baseUrl: String = DEFAULT_BASE,
    private val columnHint: String? = null,
    private val store: SnapshotStore? = null,
) : LiveProvider {

    override val kind = SourceKind.PROVIDER

    @Volatile
    private var note: String? = null

    override fun failureNote(): String? = note

    override fun fetch(trainNo: String, route: TrainRoute, journeyEpochDay: Long): LiveSnapshot? {
        note = null
        val url = buildString {
            append(baseUrl.trimEnd('/'))
            append("/rail/getTrains.aspx?TrainNo=").append(trainNo)
            append("&DataSource=1&Language=0&Cache=true&Flag=1")
            append("&Date=").append(RailTime.formatIsoDate(journeyEpochDay))
        }
        val r = when (val got = http.get(url)) {
            is Http.Result.Ok -> got
            // Every one of these used to be `as? Ok ?: return null`, which collapsed five distinguishable
            // failures into one blank screen. The device state that causes each is different — aeroplane
            // mode, a slow tunnel, a server refusing us, a page that changed shape — so the sentence the
            // traveller gets has to be different too, or the only advice we can give is "try again".
            is Http.Result.Failed -> {
                note = transportFailureSentence(got)
                return null
            }
        }
        val parsed = runCatching { ErailWire.parse(r.body, columnHint) }.getOrNull()
        if (parsed == null) {
            note = "The live feed answered, but its reply is not in the format this build understands."
            return null
        }
        if (parsed.isEmpty) {
            note = "The live feed has nothing at all about train $trainNo."
            return null
        }
        // The honest headline for the endpoint's real limitation: it publishes the timetable, the run
        // window and berth availability, and *not* per-stop running times. A snapshot is still built from
        // whatever it did report, but the screen is told why the live column stays dashed.
        if (!parsed.hasLiveColumns) {
            // Copied to a local first: `feedStampMillis` is a property of another file, so a null test on
            // it does not license passing the value to a non-null parameter, and `!!` in a string template
            // is how a copy that runs on every refresh ends up crashing on the one that does not.
            val stamp = parsed.feedStampMillis
            note = if (stamp != null) {
                "The live feed answered (its data is stamped ${RailTime.formatClock(stamp)} IST) " +
                    "but published no arrival or departure minute for this run, so every minute below is the timetable."
            } else {
                "The live feed published no arrival or departure minute for this run, so every minute below " +
                    "is the timetable."
            }
        }
        val snap = ErailAdapter.toSnapshot(parsed, trainNo, journeyEpochDay, r.fromCache, r.ageMillis)
            ?: return null
        store?.save(trainNo, snap)
        return snap
    }


    companion object {
        const val DEFAULT_BASE = "https://erail.in"
    }
}

/** Turns the parsed wire rows into a domain snapshot. Separate object so it is testable alone. */
object ErailAdapter {

    fun toSnapshot(
        parsed: ErailWire.Parsed, trainNo: String, journeyEpochDay: Long,
        fromCache: Boolean, ageMillis: Long,
    ): LiveSnapshot? {
        val rows = parsed.stops
        if (rows.isEmpty()) return null
        val perStop = LinkedHashMap<String, LiveStopFields>()
        var anchorIdx = -1
        var anchorEvent = StopStatus.DEPARTED
        for ((i, s) in rows.withIndex()) {
            val code = s.code ?: continue
            val cancelled = s.statusWord?.contains("cancel") == true
            val departed = s.statusWord?.contains("depart") == true ||
                s.statusWord?.contains("pass") == true || s.actDep != null
            val arrived = s.statusWord?.contains("arriv") == true ||
                (s.actArr != null && s.actDep == null)
            if (departed || arrived || cancelled) anchorIdx = i
            perStop[code] = LiveStopFields(
                actualArrivalMin = s.actArr, actualDepartureMin = s.actDep,
                delayArrivalMin = s.delayArr, delayDepartureMin = s.delayDep,
                platform = s.platform, cancelled = cancelled,
                departed = when { departed -> true; s.actArr != null -> false; else -> null },
            )
            anchorEvent = when {
                cancelled -> StopStatus.CANCELLED_HERE
                arrived && !departed -> StopStatus.ARRIVED
                else -> StopStatus.DEPARTED
            }
        }
        val anchor = anchorIdx.takeIf { it >= 0 }?.let { rows[it] }
        val next = anchorIdx.takeIf { it >= 0 }?.let { rows.getOrNull(it + 1) }
        val anyDelay = rows.lastOrNull { (it.delayDep ?: it.delayArr) != null }
            ?.let { it.delayDep ?: it.delayArr }
        return LiveSnapshot(
            trainNo = trainNo,
            journeyEpochDay = journeyEpochDay,
            atCode = anchor?.code ?: parsed.header?.let { null },
            atSeq = 0,
            atName = anchor?.name ?: parsed.header?.positionLine,
            event = if (anchor == null) StopStatus.UNKNOWN else anchorEvent,
            eventMinute = anchor?.actDep ?: anchor?.actArr,
            scheduledMinuteAtEvent = anchor?.schedDep ?: anchor?.schedArr,
            nextCode = next?.code,
            expectedNextMinute = next?.actArr ?: next?.schedArr,
            delayMinutes = anchor?.let { it.delayDep ?: it.delayArr } ?: anyDelay,
            platform = anchor?.platform,
            perStop = perStop,
            fetchedAtMillis = System.currentTimeMillis() - ageMillis,
            sourceKind = if (fromCache) SourceKind.CACHED else SourceKind.PROVIDER,
            feedStampMillis = parsed.feedStampMillis,
            rawNote = buildString {
                parsed.header?.positionLine?.let { append(it.take(240)) }
                if (parsed.unclassifiedRows > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("${parsed.unclassifiedRows} row(s) not understood")
                }
            }.ifEmpty { null },
        )
    }
}

/**
 * User-configured endpoint, tried first when set. The escape hatch that keeps the app alive if the
 * public feed ever starts requiring a key: the user repoints one setting, no APK update, and no new
 * server for us to operate. Accepts [SnapshotCodec]'s format (which most IR-derived REST APIs can be
 * mapped onto) as well as a nested `data.currentLocation` shape.
 */
class OverrideProvider(
    private val http: Http,
    private val template: String?,
    private val store: SnapshotStore? = null,
) : LiveProvider {

    override val kind = SourceKind.PROVIDER_OVERRIDE

    @Volatile
    private var note: String? = null

    override fun failureNote(): String? = note

    override fun fetch(trainNo: String, route: TrainRoute, journeyEpochDay: Long): LiveSnapshot? {
        note = null
        val t = template?.trim().orEmpty()
        // Two distinct states this used to share one: "you never configured an endpoint" (nothing to
        // explain, the built-in source is in charge) and "your endpoint is set but is not a URL" (a typo
        // in Settings, which the traveller can fix). Silence was the more confusing of the two answers.
        if (t.isEmpty()) return null
        if (!t.startsWith("http")) {
            note = "The custom live endpoint in Settings is not a URL starting with http, so it was not tried."
            return null
        }
        val url = t.replace("{trainNo}", trainNo)
            .replace("{date}", RailTime.formatIsoDate(journeyEpochDay))
            .replace("{dateCompact}", RailTime.formatCompactDate(journeyEpochDay))
            .replace("{srcCode}", route.srcCode)
            .replace("{dstCode}", route.dstCode)
        val r = when (val got = http.get(url)) {
            is Http.Result.Ok -> got
            is Http.Result.Failed -> {
                note = "Your endpoint: " + transportFailureSentence(got)
                return null
            }
        }
        val snap = runCatching { SnapshotCodec.decode(r.body, fallbackTrainNo = trainNo) }.getOrNull()
            ?.copy(fetchedAtMillis = System.currentTimeMillis() - r.ageMillis,
                sourceKind = if (r.fromCache) SourceKind.CACHED else SourceKind.PROVIDER_OVERRIDE)
            ?: run {
                note = "Your endpoint answered, but the reply was not the JSON shape this app reads " +
                    "(see the snapshot format in the README)."
                return null
            }
        // A hand-built endpoint can answer with the wrong train (a cached proxy, a copy-pasted URL, a
        // template that ignored {trainNo}). Showing another train's position is the single worst
        // failure this screen can have, so an identity mismatch is treated as "no answer" and left at
        // that — the header then reads OFFLINE/CACHED, which is true of the *requested* train.
        if (snap.trainNo.isNotEmpty() && trainNo.isNotEmpty() && snap.trainNo != trainNo) {
            note = "Your endpoint answered with train ${snap.trainNo} while this screen asked for $trainNo, " +
                "so its answer was refused rather than shown."
            return null
        }
        if (snap.sourceKind == SourceKind.PROVIDER_OVERRIDE) store?.save(trainNo, snap)
        return snap
    }
}

/**
 * Last good snapshot on disk. This is what "offline-first" means for live data: after a refresh in
 * the morning, the 2 a.m. status page still shows where the train was last *confirmed*, labelled as
 * such, rather than an error.
 */
class CacheProvider(private val store: SnapshotStore) : LiveProvider {
    override val kind = SourceKind.CACHED

    @Volatile
    private var note: String? = null

    override fun failureNote(): String? = note

    override fun fetch(trainNo: String, route: TrainRoute, journeyEpochDay: Long): LiveSnapshot? {
        note = null
        val snap = store.load(trainNo)
        if (snap == null) {
            // Distinguish "this device has never been online with this train" from "the live feed is down
            // and there is no rescue": the first calls for a walk out of the tunnel, the second for a
            // retry later, and the file layer knows which one it is.
            note = "Nothing this device has stored about $trainNo is new enough to show — a snapshot older " +
                "than 24 hours is refused rather than presented as live."
            return null
        }
        if (snap.perStop.isEmpty() && snap.atCode == null) {
            note = "The snapshot on this device is too incomplete to show."
            return null
        }
        return snap
    }
}

/** Per-train snapshot file, named by train number so a "recent" list can rehydrate without an index. */
class SnapshotStore(context: Context) {

    private val dir = File(context.filesDir, "live").apply { mkdirs() }

    fun file(trainNo: String): File = File(dir, trainNo.filter { it.isDigit() || it.isLetter() } + ".json")

    fun save(trainNo: String, snapshot: LiveSnapshot) {
        runCatching {
            val f = file(trainNo)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(SnapshotCodec.encode(snapshot))
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                // Some private-storage mounts refuse a rename; a copy is the fallback, and the temp
                // file is then removed, or it accumulates one per refresh for every train watched.
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        }
    }

    fun load(trainNo: String): LiveSnapshot? = runCatching {
        val f = file(trainNo)
        if (!f.isFile) return null
        // Age-capped: a 3-day-old "live position" is misinformation, not a fallback.
        if (System.currentTimeMillis() - f.lastModified() > MAX_AGE) return null
        SnapshotCodec.decode(f.readText())
    }.getOrNull()

    fun clear() = runCatching { dir.listFiles()?.forEach { it.delete() } }

    companion object {
        const val MAX_AGE = 24 * 60 * 60_000L
    }
}

/**
 * Runs the chain in priority order and reports which source answered, because the UI must show it
 * (rule C5: a degraded mode is a visible state, never a silent one).
 */
class ProviderChain(private val providers: List<LiveProvider>) {

    data class Outcome(
        val snapshot: LiveSnapshot?,
        val winner: SourceKind,
        val tried: List<String>,
        /**
         * Why each source that came back empty did so, in its own words. Surfaced even when a later source
         * rescued the screen, because "showing the last thing we fetched at 06:12, because the live feed
         * timed out" is a more useful sentence than either half of it alone.
         */
        val notes: List<String> = emptyList(),
    )

    fun fetch(trainNo: String, route: TrainRoute, journeyEpochDay: Long): Outcome {
        val tried = ArrayList<String>(providers.size)
        val notes = ArrayList<String>(providers.size)
        for (p in providers) {
            var thrown: Throwable? = null
            val snap = try {
                p.fetch(trainNo, route, journeyEpochDay)
            } catch (e: Throwable) {
                thrown = e
                null
            }
            tried += p.kind.name + (if (snap == null) "=miss" else "=hit")
            if (snap == null) {
                // A provider is allowed to be silent about why it declined; a provider that *threw* must
                // not be, because that is the one case where the app itself may be at fault, and after
                // R8 this sentence is often the only trace of it a user will ever be able to show us.
                val why = p.failureNote() ?: thrown?.let {
                    "this source did not answer properly (${it.javaClass.simpleName})."
                }
                if (why != null && why.isNotBlank()) notes += "${p.kind.label}: $why"
            }
            if (snap != null) return Outcome(snap, snap.sourceKind, tried, notes)
        }
        return Outcome(null, SourceKind.NONE, tried, notes)
    }

    companion object {
        fun build(http: Http, store: SnapshotStore, overrideUrl: String?, columnHint: String?) =
            ProviderChain(
                listOf(
                    OverrideProvider(http, overrideUrl, store),
                    ErailProvider(http, ErailProvider.DEFAULT_BASE, columnHint, store),
                    CacheProvider(store),
                )
            )
    }
}
