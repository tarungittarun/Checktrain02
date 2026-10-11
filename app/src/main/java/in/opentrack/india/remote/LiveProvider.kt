package `in`.opentrack.india.remote

import android.content.Context
import `in`.opentrack.india.core.BrowserFetch
import `in`.opentrack.india.core.Diary
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
/**
 * One request, sent by whichever client this device will actually let through.
 *
 * The order is not a preference. A phone that hung up on `HttpURLConnection`, then got a 2xx from the same URL
 * sent from inside a page of the site's own a second later, has told us which client it permits - and on that
 * phone, asking again with the refused client is known-futile, which is what the last five rounds of logs
 * looked like. So: the JDK client whenever it is not demonstrably blocked, the site's own origin on devices
 * where the probe proved otherwise.
 *
 * `browserWorks` is latched only on a real 2xx body, never on a document the engine rendered. That is not
 * pedantry: the previous version of this latch was set by a "159-byte answer" which turned out to be Android's
 * XML parser complaining about JSON, and once latched it routed every refresh of the app through a transport
 * that had nothing to give back. A diagnostic that changes what the traveller sees is a bug, so the latch is
 * strict and the answer is read as bytes from `fetch()`, not as a page.
 */
private fun ask(
    http: Http,
    url: String,
    body: String?,
    headers: Map<String, String>,
    timeoutMillis: Int = Http.DEFAULT_TIMEOUT,
): Http.Result {
    if (!FeedProbe.browserWorks) {
        return if (body == null) http.get(url, timeoutMillis, 1, headers)
        else http.post(url, body, headers, timeoutMillis = timeoutMillis, retries = 1)
    }
    Diary.get()?.log("TRANSPORT") { "asking from the site's own page, which this device accepted" }
    return BrowserFetch.apiCall(url, body, BrowserFetch.originPage(url), headers, BrowserFetch.defaultTimeout)
}

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
        val r = when (val got = ask(http, url, null, emptyMap())) {
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

/**
 * Primary live source: the railway's own running-status feed, which carries actual times per halt.
 *
 * This is the provider whose absence made the whole product look like a timetable. It is tried before
 * [ErailProvider] and *after* the user's override, and it is deliberately not the only source: its payload
 * is AES-framed with a key that is fixed inside the official app (see [NtesWire]), so an upstream rotation
 * is a real possibility, and the price of that risk is falling back to a labelled timetable — never a
 * blank screen and never a schedule wearing a "live" badge.
 */
class NtesProvider(
    private val http: Http,
    private val baseUrl: String = NtesWire.BASE_URL,
    private val store: SnapshotStore? = null,
) : LiveProvider {

    override val kind = SourceKind.PROVIDER_NTES

    @Volatile
    private var note: String? = null

    override fun failureNote(): String? = note

    override fun fetch(trainNo: String, route: TrainRoute, journeyEpochDay: Long): LiveSnapshot? {
        note = null
        // If this device already found a request shape the feed answers, use it. Adoption is per-process and
        // per-network rather than baked into the build, because the difference between an answer and a closed
        // socket here belongs to the carrier and the server, not to the APK - and a rebuild should never be the
        // price of a header.
        val adopted = FeedProbe.adoptedFor(trainNo, journeyEpochDay)
        val plain = NtesWire.plainFor(NtesWire.SUB_LIVE, trainNo, journeyEpochDay)
        val headers = mapOf(
            "Content-Type" to "application/json",
            "charset" to "utf-8",
            // The feed answers the client signature it expects; with anything else some deployments of it
            // reset the connection, which the traveller would otherwise experience as "no live data".
            "User-Agent" to NtesWire.USER_AGENT,
        )
        val got = if (adopted != null) {
            FeedProbe.execute(http, adopted, trainNo, journeyEpochDay)
        } else {
            ask(http, baseUrl, NtesWire.envelope(plain), headers, TIMEOUT)
        }
        val body = when (got) {
            is Http.Result.Ok -> got
            is Http.Result.Failed -> {
                note = transportFailureSentence(got) +
                    (if (adopted != null) " (asked with the shape this device adopted: " + adopted.name + ")" else "") +
                    (if (FeedProbe.browserWorks) " (asked from the site's own page, the way its app does)" else "")
                // Refused is the moment the experiment matters: the device is the only place that can tell a
                // wrong request from a blocked network, so ask it instead of guessing from a sandbox.
                Diary.get()?.log("NTES", "refused: " + got.kind + " " + got.message +
                    (if (adopted == null) " (shipped shape)" else " (adopted shape " + adopted.name + ")"))
                if (got.kind == Http.Result.Kind.HTTP || got.kind == Http.Result.Kind.TIMEOUT) {
                    FeedProbe.maybeStart(http, trainNo, journeyEpochDay)
                }
                return null
            }
        }
        val doc = NtesWire.unwrapJson(body.body)
        if (doc == null) {
            // The one failure that is ours rather than upstream's, so it has to be distinguishable: an
            // envelope we could not open means the framing changed, not that the train is not running.
            note = "The live feed answered, but this build could not open its envelope (its protocol may have changed)."
            Diary.get()?.log("NTES", "envelope unopenable: body=" + body.body.length + "B, framing changed?")
            return null
        }
        val parsed = runCatching { NtesWire.parse(doc.toString()) }.getOrNull()
        if (parsed == null) {
            val keys = runCatching { doc.keys().asSequence().take(8).joinToString(", ") }.getOrNull()
            Diary.get()?.log("NTES", "reply was not a status table; top-level keys: " + (keys ?: "-"))
            note = "The live feed answered, but its reply was not a running-status table" +
                (if (keys.isNullOrBlank()) "." else " (it sent: $keys).")
            return null
        }
        if (parsed.isEmpty) {
            note = "The live feed has no running data for train $trainNo on this date" +
                (parsed.message?.let { " ($it)" } ?: ".")
            return null
        }
        if (!parsed.hasLiveColumns) {
            note = "The live feed is tracking this run but has not reported an arrival or departure minute " +
                "at any halt yet, so the times below are the timetable until it does."
        }
        val snap = NtesAdapter.toSnapshot(parsed, trainNo, journeyEpochDay) ?: run {
            Diary.get()?.log("NTES", "rows parsed (" + parsed.rows.size + ") but no station matched the timetable")
            return null
        }
        Diary.get()?.log(
            "NTES",
            "accepted: " + snap.perStop.size + " stops with reported cells; live cells " +
                (if (parsed.hasLiveColumns) "present" else "none yet"),
        )
        // Said out loud so a screenshot explains itself: "live, via a shape learned on this device" is a
        // different support conversation from "live, via the shipped request".
        // `adopted.name` is only reachable in the else branch, where the null check above has already excluded
        // the case - `adopted` is nullable, so hoisting that read out here would not compile.
        val shaped = if (adopted == null || snap.rawNote.isNullOrBlank()) snap
        else snap.copy(rawNote = (snap.rawNote ?: "live") + " · via " + adopted.name)
        store?.save(trainNo, shaped)
        return shaped
    }

    companion object {
        /** Slower than the timetable source on purpose: the envelope costs a round trip and a decrypt. */
        const val TIMEOUT = 15_000
    }
}

/** Maps the feed's rows onto a domain snapshot. Separate object so the mapping is testable without HTTP. */
object NtesAdapter {

    fun toSnapshot(parsed: NtesWire.Parsed, trainNo: String, journeyEpochDay: Long): LiveSnapshot? {
        val rows = parsed.rows
        if (rows.isEmpty()) return null
        val perStop = LinkedHashMap<String, LiveStopFields>()
        var anchorIdx = -1
        var anchorEvent = StopStatus.DEPARTED
        for ((i, s) in rows.withIndex()) {
            val code = s.code ?: continue
            val departed = s.departed == true || s.actDep != null
            val arrived = s.actArr != null || s.delayArr != null
            if (departed || arrived || s.cancelled) anchorIdx = i
            perStop[code] = LiveStopFields(
                actualArrivalMin = s.actArr,
                actualDepartureMin = s.actDep,
                delayArrivalMin = s.delayArr,
                delayDepartureMin = s.delayDep,
                platform = s.platform,
                cancelled = s.cancelled,
                departed = when {
                    s.departed != null -> s.departed
                    s.actDep != null -> true
                    s.actArr != null -> false
                    else -> null
                },
            )
            anchorEvent = when {
                s.cancelled && anchorIdx == i -> StopStatus.CANCELLED_HERE
                arrived && !departed && anchorIdx == i -> StopStatus.ARRIVED
                else -> StopStatus.DEPARTED
            }
        }
        // Not one row in the answer named a station we can key on, so there is nothing for the domain to
        // anchor to and no cell for the stop table to read. Returning null here keeps that guarantee at the
        // seam that produces it: ProviderChain would also drop this snapshot, but an adapter that hands back
        // an empty "live" result is one refactor away from a screen that says LIVE over a table of dashes.
        if (perStop.isEmpty()) return null
        val anchor = anchorIdx.takeIf { it >= 0 }?.let { rows[it] }
        val next = anchorIdx.takeIf { it >= 0 }?.let { rows.getOrNull(it + 1) }
        val anchorCode = anchor?.code
        // A "current station" the feed names is only useful if it exists on the timetable we hold; the
        // domain matches on station code, so an unknown code is dropped rather than guessed at.
        val feedCode = parsed.currentCode?.takeIf { c -> perStop.containsKey(c) } ?: anchorCode
        // No halt with an event and no station named in the header: the feed knows nothing about where
        // this train is, which is a different screen from "it is standing at a halt".
        val positionKnown = anchor != null || anchorCode != null
        return LiveSnapshot(
            trainNo = trainNo,
            journeyEpochDay = journeyEpochDay,
            atCode = feedCode,
            atSeq = 0,
            atName = anchor?.name ?: parsed.currentName,
            event = if (positionKnown) anchorEvent else StopStatus.UNKNOWN,
            eventMinute = anchor?.actDep ?: anchor?.actArr,
            scheduledMinuteAtEvent = anchor?.schedDep ?: anchor?.schedArr,
            nextCode = parsed.nextCode?.takeIf { it.isNotBlank() } ?: next?.code,
            expectedNextMinute = next?.actArr,
            delayMinutes = anchor?.let { it.delayDep ?: it.delayArr }
                ?: rows.lastOrNull { (it.delayDep ?: it.delayArr) != null }?.let { it.delayDep ?: it.delayArr },
            platform = anchor?.platform,
            perStop = perStop,
            fetchedAtMillis = System.currentTimeMillis(),
            sourceKind = SourceKind.PROVIDER_NTES,
            feedStampMillis = parsed.feedStampMillis,
            rawNote = buildString {
                parsed.message?.let { append(it.take(200)) }
                parsed.trainName?.let {
                    if (isNotEmpty()) append(" \u00b7 ")
                    append(it.take(60))
                }
            }.ifEmpty { null },
        )
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
        val r = when (val got = ask(http, url, null, emptyMap())) {
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
                    NtesProvider(http, NtesWire.BASE_URL, store),
                    ErailProvider(http, ErailProvider.DEFAULT_BASE, columnHint, store),
                    CacheProvider(store),
                )
            )
    }
}
