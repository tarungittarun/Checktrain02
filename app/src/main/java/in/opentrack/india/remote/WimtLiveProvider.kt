package `in`.opentrack.india.remote

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.LiveSnapshot
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainRoute
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Direct selected-source request to Where Is My Train's `/cache/live_status` endpoint,
 * following Gravity01's provider path. The endpoint is undocumented and may be server-cached;
 * parsed clocks and station fields remain source claims, not independently verified movement,
 * GPS, or the separate Google WIMT Android app.
 */
object WimtLiveProvider {

    const val SOURCE_NAME = "WIMT .in live-status feed"
    private const val ENDPOINT = "https://whereismytrain.in/cache/live_status"
    // Identify this app honestly. Do not copy browser/WebView or hidden WIMT app identifiers.
    private const val USER_AGENT = "TrackTrain/1.0 (Android; selected WIMT source)"
    private const val MAX_RESPONSE_BYTES = 1_000_000
    const val FRESH_UPDATE_MAX_AGE_MS = 10 * 60_000L
    const val UPDATE_STAMP_FUTURE_TOLERANCE_MS = 120_000L

    /** One direct GET for the selected train/run; not `.org.in` or the app's comparison-only parser. The endpoint may itself be cached. */
    internal fun requestUrl(trainNo: String, dayStr: String): String =
        "$ENDPOINT?train_no=$trainNo&date=$dayStr&lang=en"

    internal data class WireReply(
        val body: String?,
        val status: Int?,
        val detail: String,
        val responseBytes: Int? = body?.toByteArray(Charsets.UTF_8)?.size,
    )

    fun hasFreshSourceUpdate(result: WimtResult, nowMillis: Long): Boolean =
        result.snapshot?.feedStampMillis?.let { stamp ->
            stamp <= nowMillis && nowMillis - stamp <= FRESH_UPDATE_MAX_AGE_MS
        } == true

    /** Return a local-only wait when the source stamp is slightly ahead; do not call it fresh early. */
    fun freshStampRecheckDelayMillis(result: WimtResult, nowMillis: Long): Long? {
        val stamp = result.snapshot?.feedStampMillis ?: return null
        val aheadBy = stamp - nowMillis
        return aheadBy.takeIf { it in 1..UPDATE_STAMP_FUTURE_TOLERANCE_MS }
    }

    data class WimtIntermediate(
        val code: String,
        val name: String,
        val precedingHalt: String,
        val followingHalt: String,
        val mapKm: Int = -1,
        val scheduledMinute: Int? = null,
        val actualMinute: Int? = null,
        val delayMin: Int? = null,
        val eventMinute: Int? = null,
    )

    data class WimtScheduleHalt(
        val code: String,
        val schArrMin: Int? = null,
        val schDepMin: Int? = null,
        val distanceKm: Int? = null,
        val platform: String? = null,
    )

    data class WimtResult(
        val snapshot: LiveSnapshot?,
        val detail: String,
        val stationLines: Map<String, String> = emptyMap(),
        val intermediate: WimtIntermediate? = null,
        val intermediateStops: List<StopRow> = emptyList(),
        val scheduleHalts: Map<String, WimtScheduleHalt> = emptyMap(),
        /** HTTP status and bytes are transport evidence only; neither alone qualifies a train event. */
        val responseCode: Int? = null,
        val responseBytes: Int? = null,
        /** Null means the API did not echo a run date; true means its echoed date matched the request. */
        val runDateMatched: Boolean? = null,
        /** Exact current-station field from the response; distinct from the mapped route anchor. */
        val currentStationCode: String? = null,
        /** True only when both response endpoints were echoed and matched this timetable. */
        val routeMatched: Boolean? = null,
    )

    fun fetch(
        trainNo: String,
        route: TrainRoute,
        originEpochDay: Long,
        nowMillis: Long = System.currentTimeMillis(),
        stationNameLookup: ((String) -> String?)? = null,
    ): WimtResult = fetch(trainNo, route, originEpochDay, nowMillis, stationNameLookup) { url ->
        request(url)
    }

    /** Injectable wire boundary for JVM tests; production uses one ordinary Android GET. */
    internal fun fetch(
        trainNo: String,
        route: TrainRoute,
        originEpochDay: Long,
        nowMillis: Long,
        stationNameLookup: ((String) -> String?)?,
        get: (String) -> WireReply,
    ): WimtResult {
        if (!trainNo.matches(Regex("[0-9]{4,5}")) || !route.trainNo.equals(trainNo, true)) {
            return WimtResult(null, "WIMT request train number does not match the loaded timetable")
        }
        if (route.stops.isEmpty()) return WimtResult(null, "No timetable stops available for WIMT")
        val dayStr = formatRunDate(originEpochDay) ?: return WimtResult(null, "Invalid WIMT run date")
        val url = requestUrl(trainNo, dayStr)
        val wire = try {
            get(url)
        } catch (e: Exception) {
            WireReply(null, null, e.message?.take(160) ?: "Network connection failed")
        }
        if (wire.status != HttpURLConnection.HTTP_OK) {
            val detail = wire.detail.ifBlank { "WIMT server returned HTTP ${wire.status ?: "unknown"}" }
            return WimtResult(null, detail, responseCode = wire.status, responseBytes = wire.responseBytes)
        }
        val body = wire.body ?: return WimtResult(null,
            wire.detail.ifBlank { "WIMT returned an empty response" },
            responseCode = wire.status, responseBytes = wire.responseBytes)
        if (body.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) {
            return WimtResult(null, "WIMT response exceeded $MAX_RESPONSE_BYTES bytes",
                responseCode = wire.status, responseBytes = wire.responseBytes ?: body.toByteArray(Charsets.UTF_8).size)
        }
        val parsed = parse(body, trainNo, route, originEpochDay, nowMillis, stationNameLookup)
        return parsed.copy(responseCode = wire.status,
            responseBytes = wire.responseBytes ?: body.toByteArray(Charsets.UTF_8).size)
    }

    internal fun request(url: String): WireReply {
        var connection: HttpURLConnection? = null
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            connection = conn
            conn.connectTimeout = 5_000
            conn.readTimeout = 7_000
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            // Match this app's declared TrackTrain identity rather than impersonating a browser;
            // do not send copied cookies, hidden WIMT app identifiers, or challenge-evasion headers.
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                ?: return WireReply(null, status, "WIMT server returned HTTP $status (empty response)", 0)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            var total = 0
            stream.use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_RESPONSE_BYTES) {
                        return WireReply(null, status,
                            "WIMT response exceeded $MAX_RESPONSE_BYTES bytes", total)
                    }
                    output.write(buffer, 0, count)
                }
            }
            val body = output.toString(Charsets.UTF_8.name())
            WireReply(body, status,
                if (body.isEmpty()) "WIMT returned an empty response" else "HTTP $status", total)
        } catch (e: Exception) {
            WireReply(null, connection?.let { runCatching { it.responseCode }.getOrNull() },
                e.message?.take(160) ?: "Network connection failed")
        } finally {
            connection?.disconnect()
        }
    }

    fun parse(
        body: String,
        trainNo: String,
        route: TrainRoute,
        originEpochDay: Long,
        nowMillis: Long,
        stationNameLookup: ((String) -> String?)? = null,
    ): WimtResult {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            return WimtResult(null, "Invalid JSON from WIMT")
        }

        if (!trainNo.matches(Regex("[0-9]{4,5}")) || !route.trainNo.equals(trainNo, true)) {
            return WimtResult(null, "WIMT request train number does not match the loaded timetable")
        }
        if (route.stops.isEmpty()) return WimtResult(null, "No timetable stops available for WIMT")
        if (formatRunDate(originEpochDay) == null) return WimtResult(null, "Invalid WIMT run date")

        val echoedTrain = firstNonBlank(root, "train_no", "train_number", "trainNo")
        if (echoedTrain != null && echoedTrain != trainNo) {
            return WimtResult(null, "WIMT response train number did not match the request")
        }
        val echoedSource = stationCode(firstNonBlank(root,
            "source_station_code", "source_code", "source_station"))
        val echoedDestination = stationCode(firstNonBlank(root,
            "destination_station_code", "destination_code", "destination_station"))
        val routeMismatch = echoedSource != null && !echoedSource.equals(route.srcCode, true) ||
            echoedDestination != null && !echoedDestination.equals(route.dstCode, true)
        val routeMatched = when {
            routeMismatch -> false
            echoedSource != null && echoedDestination != null -> true
            else -> null
        }
        if (routeMismatch) {
            return WimtResult(null, "WIMT response route endpoints did not match the loaded timetable",
                routeMatched = false)
        }
        val echoedStartDate = firstNonBlank(root, "start_date", "train_start_date")
        val runDateMatched = echoedStartDate?.let(::parseRunEpochDay)?.let { it == originEpochDay }
        if (echoedStartDate != null && runDateMatched != true) {
            return WimtResult(null, "WIMT response run date did not match the requested run",
                runDateMatched = false, routeMatched = routeMatched)
        }

        val rows = root.optJSONArray("days_schedule")
            ?: return WimtResult(null, "No schedule in WIMT response", runDateMatched = runDateMatched,
                routeMatched = routeMatched)

        val rootCurStnCode = firstNonBlank(root, "curStn")?.uppercase(Locale.ENGLISH).orEmpty()
        val curStnIdx = (0 until rows.length()).firstOrNull { index ->
            val row = rows.optJSONObject(index) ?: return@firstOrNull false
            val code = firstNonBlank(row, "station_code")?.uppercase(Locale.ENGLISH).orEmpty()
            (rootCurStnCode.isNotBlank() && code.equals(rootCurStnCode, true)) ||
                row.optBoolean("curStn", false)
        } ?: -1
        val curStnCode = rootCurStnCode.ifBlank {
            val currentRow = if (curStnIdx >= 0) rows.optJSONObject(curStnIdx) else null
            firstNonBlank(currentRow, "station_code")?.uppercase(Locale.ENGLISH).orEmpty()
        }
        val updateIso = firstNonBlank(root, "lastUpdateIsoDate")
        val parsedUpdateMillis = parseIsoMillis(updateIso)
        // A future upstream stamp (or a parser/clock mismatch) is not a fresh feed stamp.
        val updateMillis = parsedUpdateMillis?.takeIf {
            it <= nowMillis + UPDATE_STAMP_FUTURE_TOLERANCE_MS
        }

        val perStop = mutableMapOf<String, LiveStopFields>()
        val lines = mutableMapOf<String, String>()
        val intermediateStops = mutableListOf<StopRow>()
        val scheduleHalts = mutableMapOf<String, WimtScheduleHalt>()

        var curStnDeparted = false
        var lastDepartedRouteCode: String? = null
        var lastArrivedRouteCode: String? = null
        var priorSourceEventMinute: Int? = null
        var rejectedInvertedDepartures = 0

        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val code = firstNonBlank(row, "station_code")?.uppercase(Locale.ENGLISH) ?: continue

            val isCur = code.equals(curStnCode, true) || row.optBoolean("curStn", false)
            // WIMT marks unreached future halts with source: "eta". Also treat every row after
            // its current-station pointer as not reached, even if an ETA tag is omitted.
            val isEta = row.optString("source").equals("eta", ignoreCase = true)
            val departed = !isEta && row.optBoolean("departed", false)
            val stopOnRoute = route.stops.firstOrNull { it.code.equals(code, true) }
            val stopsFlag = row.optBoolean("stops", true)
            val reachedByFeedOrder = curStnIdx < 0 || i <= curStnIdx

            val schArrHm = RailTime.parseHm(firstNonBlank(row, "sch_arrival_time"))
            val schDepHm = RailTime.parseHm(firstNonBlank(row, "sch_departure_time"))
            val schArrMin = RailTime.alignClock(schArrHm, stopOnRoute?.arrivalMin)
            val schDepMin = RailTime.alignClock(schDepHm, stopOnRoute?.departureMin)
            val distKm = (row.opt("distance") as? Number)?.toInt()
                ?: (row.opt("distance_from_source") as? Number)?.toInt()
            val pf = firstNonBlank(row, "platform")
                ?: row.optJSONArray("platform_info")?.optJSONObject(0)
                    ?.let { firstNonBlank(it, "platform_number") }
            scheduleHalts[code] = WimtScheduleHalt(
                code = code,
                schArrMin = schArrMin,
                schDepMin = schDepMin,
                distanceKm = distKm,
                platform = pf,
            )

            // Only source-labelled, non-ETA clocks on the reached side of the feed's current
            // pointer are candidates. Epoch fields carry their own date; HH:mm fields require a
            // timetable anchor. Neither an unanchored clock nor a future value becomes an actual.
            val rawArr = if (!isEta && reachedByFeedOrder)
                actualRailMinute(row, "actual_arrival_time", "actual_arrival_tm",
                    stopOnRoute?.arrivalMin ?: priorSourceEventMinute, originEpochDay, nowMillis)
            else null
            val rawDepCandidate = if (!isEta && (departed || curStnIdx >= 0 && i < curStnIdx))
                actualRailMinute(row, "actual_departure_time", "actual_departure_tm",
                    stopOnRoute?.departureMin ?: priorSourceEventMinute, originEpochDay, nowMillis)
            else null
            val depMin = rawDepCandidate?.let { candidate ->
                if (rawArr != null && candidate < rawArr) {
                    rejectedInvertedDepartures++
                    null
                } else candidate
            }
            val arrMin = rawArr
            val arrClock = arrMin?.let { Math.floorMod(it, RailTime.MINUTES_PER_DAY) }
            val depClock = depMin?.let { Math.floorMod(it, RailTime.MINUTES_PER_DAY) }
            if (isCur && depMin != null) curStnDeparted = true

            val rawDelayArr = (row.opt("delay_in_arrival") as? Number)?.toInt()
            val rawDelayDep = (row.opt("delay_in_departure") as? Number)?.toInt()

            // Preserve source-reported zero. Derive only when both a source event and its
            // source schedule clock exist; a delay field alone never creates an actual time.
            val delayArr = rawDelayArr ?: if (arrClock != null && schArrHm != null)
                RailTime.clockDelta(arrClock, schArrHm)?.coerceIn(-120, 1440) else null
            val delayDep = rawDelayDep ?: if (depClock != null && schDepHm != null)
                RailTime.clockDelta(depClock, schDepHm)?.coerceIn(-120, 1440) else null

            if (stopOnRoute != null) {
                if (!isEta && depMin != null) {
                    lastDepartedRouteCode = code
                } else if (!isEta && arrMin != null) {
                    lastArrivedRouteCode = code
                }
            } else if (!stopsFlag || !route.stops.any { it.code.equals(code, true) }) {
                val rawName = firstNonBlank(row, "station_name")
                val resolvedName = rawName ?: stationNameLookup?.invoke(code) ?: code
                intermediateStops.add(StopRow(
                    seq = 0, code = code, name = resolvedName, state = null, lat = null, lon = null,
                    arrivalMin = schArrMin, departureMin = schDepMin, journeyDay = 1,
                    schedKm = distKm ?: -1, isHalt = false,
                ))
            }

            val fields = LiveStopFields(
                actualArrivalMin = arrMin,
                actualDepartureMin = depMin,
                delayArrivalMin = delayArr,
                delayDepartureMin = delayDep,
                platform = pf,
                departed = departed,
            )
            perStop[code] = fields
            (depMin ?: arrMin)?.let { event ->
                if (priorSourceEventMinute == null || event >= priorSourceEventMinute!!) {
                    priorSourceEventMinute = event
                }
            }

            // Source details list only rows that passed the actual-time gates. ETA-only
            // platform/delay fields are not presented as reported stop-time rows.
            if (arrMin != null || depMin != null) {
                val lineParts = mutableListOf<String>()
                arrMin?.let { lineParts.add("arr ${RailTime.formatRailMinute(it)}") }
                depMin?.let { lineParts.add("dep ${RailTime.formatRailMinute(it)}") }
                val delayForLine = delayDep ?: delayArr
                delayForLine?.let { lineParts.add("${if (it > 0) "+$it" else "$it"}m") }
                pf?.let { lineParts.add("PF $it") }
                lines[code] = "$code · " + lineParts.joinToString(" · ")
            }
        }

        // Check if curStn is an intermediate non-commercial passing halt
        val curStnOnRoute = route.stops.firstOrNull { it.code.equals(curStnCode, true) }
        val wimtIntermediate: WimtIntermediate? = if (curStnCode.isNotBlank() && curStnOnRoute == null && curStnIdx >= 0) {
            val curRow = rows.optJSONObject(curStnIdx)
            var precCode: String? = null
            for (j in curStnIdx - 1 downTo 0) {
                val c = rows.optJSONObject(j)?.optString("station_code")?.uppercase(Locale.ENGLISH) ?: continue
                if (route.stops.any { it.code.equals(c, true) }) {
                    precCode = c
                    break
                }
            }
            var follCode: String? = null
            for (j in curStnIdx + 1 until rows.length()) {
                val c = rows.optJSONObject(j)?.optString("station_code")?.uppercase(Locale.ENGLISH) ?: continue
                if (route.stops.any { it.code.equals(c, true) }) {
                    follCode = c
                    break
                }
            }
            val prec = precCode ?: lastDepartedRouteCode ?: route.stops.first().code
            val foll = follCode ?: route.stops.firstOrNull { it.seq > (route.stops.firstOrNull { s -> s.code.equals(prec, true) }?.seq ?: 1) }?.code ?: prec

            val currentIsEta = curRow?.optString("source").equals("eta", ignoreCase = true)
            val currentDepartedFlag = !currentIsEta && curRow?.optBoolean("departed", false) == true
            val previousEvent = perStop[prec]?.actualDepartureMin ?: perStop[prec]?.actualArrivalMin
            val schArrHm = RailTime.parseHm(firstNonBlank(curRow, "sch_arrival_time"))
            val schDepHm = RailTime.parseHm(firstNonBlank(curRow, "sch_departure_time"))
            val actualArr = if (!currentIsEta)
                actualRailMinute(curRow, "actual_arrival_time", "actual_arrival_tm",
                    previousEvent, originEpochDay, nowMillis) else null
            val depCandidate = if (currentDepartedFlag)
                actualRailMinute(curRow, "actual_departure_time", "actual_departure_tm",
                    previousEvent, originEpochDay, nowMillis) else null
            val actualDep = depCandidate?.takeIf { actualArr == null || it >= actualArr }
            val actualClock = (actualArr ?: actualDep)?.let {
                Math.floorMod(it, RailTime.MINUTES_PER_DAY)
            }
            val currentDelay = if (currentIsEta) null else
                (curRow?.opt("delay_in_arrival") as? Number)?.toInt()
                    ?: (curRow?.opt("delay_in_departure") as? Number)?.toInt()
                    ?: if (actualClock != null && schArrHm != null)
                        RailTime.clockDelta(actualClock, schArrHm)?.coerceIn(-120, 1440)
                    else null

            val rawName = firstNonBlank(curRow, "station_name")
            val name = rawName ?: stationNameLookup?.invoke(curStnCode) ?: curStnCode
            val dist = (curRow?.opt("distance") as? Number)?.toInt()
                ?: (curRow?.opt("distance_from_source") as? Number)?.toInt() ?: -1

            WimtIntermediate(
                code = curStnCode,
                name = name,
                precedingHalt = prec,
                followingHalt = foll,
                mapKm = dist,
                scheduledMinute = schArrHm ?: schDepHm,
                actualMinute = actualArr ?: actualDep,
                delayMin = currentDelay,
                // A timetable minute is not a passage event. Keep it in scheduledMinute only.
                eventMinute = actualArr ?: actualDep,
            )
        } else null

        val (anchorStop, isDeparted) = when {
            wimtIntermediate != null -> {
                val preceding = route.stops.firstOrNull {
                    it.code.equals(wimtIntermediate.precedingHalt, true)
                } ?: route.stops.first()
                preceding to (perStop[preceding.code]?.actualDepartureMin != null)
            }
            curStnOnRoute != null -> curStnOnRoute to curStnDeparted
            lastDepartedRouteCode != null -> (route.stops.firstOrNull { it.code.equals(lastDepartedRouteCode, true) }
                ?: route.stops.first()) to true
            lastArrivedRouteCode != null -> (route.stops.firstOrNull { it.code.equals(lastArrivedRouteCode, true) }
                ?: route.stops.first()) to false
            else -> (route.stops.firstOrNull() ?: route.stops.first()) to false
        }

        val anchorCode = anchorStop.code
        val anchorSeq = anchorStop.seq

        val event = when {
            isDeparted && perStop[anchorCode]?.actualDepartureMin != null -> StopStatus.DEPARTED
            perStop[anchorCode]?.actualArrivalMin != null -> StopStatus.ARRIVED
            // curStn identifies a source station claim, not an arrival/departure event.
            else -> StopStatus.UNKNOWN
        }

        // Only the commercial anchor's own reported event drives PositionEngine. A WIMT
        // intermediate's passage clock and source delay remain separate provider claims.
        val eventMin = if (event == StopStatus.DEPARTED)
            perStop[anchorCode]?.actualDepartureMin
        else perStop[anchorCode]?.actualArrivalMin

        val anchorFields = perStop[anchorCode]
        val effectiveDelay = anchorFields?.delayDepartureMin ?: anchorFields?.delayArrivalMin

        val snapshot = LiveSnapshot(
            trainNo = trainNo,
            journeyEpochDay = originEpochDay,
            atCode = anchorCode,
            atSeq = anchorSeq,
            atName = anchorStop.name,
            event = event,
            eventMinute = eventMin,
            scheduledMinuteAtEvent = anchorStop.departureMin ?: anchorStop.arrivalMin,
            nextCode = wimtIntermediate?.followingHalt ?: route.stops.firstOrNull { it.seq > anchorSeq }?.code,
            expectedNextMinute = null,
            delayMinutes = effectiveDelay,
            platform = perStop[anchorCode]?.platform,
            perStop = perStop,
            fetchedAtMillis = nowMillis,
            sourceKind = SourceKind.PROVIDER,
            feedStampMillis = updateMillis,
            rawNote = updateMillis?.let { stamp ->
                "Where Is My Train · source update ${((nowMillis - stamp).coerceAtLeast(0) / 60_000)}m ago"
            } ?: "Where Is My Train · upstream update time unavailable",
        )

        val actualCount = perStop.count {
            it.value.actualArrivalMin != null || it.value.actualDepartureMin != null
        }
        val location = when {
            wimtIntermediate != null -> "WIMT current-station field claims ${wimtIntermediate.name} (${wimtIntermediate.code}); " +
                "the timetable has no matching stop; preceding halt $anchorCode"
            curStnOnRoute != null -> "WIMT current-station field claims $anchorCode"
            isDeparted -> "last source-reported halt $anchorCode · departure time reported"
            perStop[anchorCode]?.actualArrivalMin != null -> "last source-reported halt $anchorCode · arrival time reported"
            else -> "last mapped halt $anchorCode · current station unknown"
        }
        val updateDetail = when {
            parsedUpdateMillis != null && parsedUpdateMillis > nowMillis ->
                "upstream update stamp is in the future; not counted fresh"
            updateMillis != null -> "upstream update stamp ${((nowMillis - updateMillis).coerceAtLeast(0) / 60_000)}m ago (not event time)"
            else -> "upstream update timestamp unavailable"
        }
        val runDetail = when {
            runDateMatched == true && routeMatched == true -> "response run date and route endpoints matched"
            runDateMatched == true -> "response run date matched; route endpoints were not both echoed"
            else -> "requested run date was not echoed by the response"
        }
        val delayDetail = effectiveDelay?.let {
            "source-reported delay ${if (it > 0) "+$it" else it.toString()}m at the last commercial event"
        } ?: "delay unavailable"
        val invertedDetail = if (rejectedInvertedDepartures == 0) ""
            else " · $rejectedInvertedDepartures departure clock(s) earlier than arrival excluded"
        val detail = "$location · $delayDetail · $actualCount source-reported actual stop(s) · " +
            "$updateDetail · $runDetail$invertedDetail"

        return WimtResult(
            snapshot = snapshot,
            detail = detail,
            stationLines = lines,
            intermediate = wimtIntermediate,
            intermediateStops = intermediateStops,
            scheduleHalts = scheduleHalts,
            responseBytes = body.toByteArray(Charsets.UTF_8).size,
            runDateMatched = runDateMatched,
            currentStationCode = curStnCode.takeIf { it.isNotBlank() },
            routeMatched = routeMatched,
        )
    }

    private fun firstNonBlank(json: JSONObject?, vararg keys: String): String? {
        for (key in keys) {
            val raw = json?.opt(key) ?: continue
            val value = raw.toString().trim()
            if (value.isNotEmpty() && !value.equals("null", true)) return value
        }
        return null
    }

    /** Only short station-code fields are treated as endpoint identity; descriptive names vary by source. */
    private fun stationCode(value: String?): String? = value?.trim()?.uppercase(Locale.ENGLISH)
        ?.takeIf { it.matches(Regex("[A-Z0-9]{2,5}")) }

    private fun actualRailMinute(
        row: JSONObject?,
        textKey: String,
        epochKey: String,
        scheduledMinute: Int?,
        originEpochDay: Long,
        nowMillis: Long,
    ): Int? {
        val raw = row?.opt(epochKey)
        val epoch = when (raw) {
            is Number -> raw.toLong()
            null, JSONObject.NULL -> null
            else -> raw.toString().trim().toLongOrNull()
        }
        if (epoch != null && epoch != 0L) {
            if (epoch < 0L || epoch > Long.MAX_VALUE / 1_000L) return null
            val millis = if (epoch < 100_000_000_000L) epoch * 1_000L else epoch
            if (!actualMillisAllowed(millis, originEpochDay, nowMillis)) return null
            val absoluteMinute = Math.floorDiv(millis + RailTime.IST_OFFSET_MILLIS, 60_000L)
            val runStartMinute = originEpochDay * RailTime.MINUTES_PER_DAY
            return (absoluteMinute - runStartMinute).takeIf {
                it >= Int.MIN_VALUE.toLong() && it <= Int.MAX_VALUE.toLong()
            }?.toInt()
        }

        // A bare clock has no date. It is admissible only when a same-run timetable/event anchor
        // gives it an unambiguous day; never assume that a clock-only value is day one.
        val clock = firstNonBlank(row, textKey)?.let(RailTime::parseHm) ?: return null
        val anchor = scheduledMinute ?: return null
        val railMinute = RailTime.alignClock(clock, anchor) ?: return null
        val millis = RailTime.railMinuteToEpochMillis(originEpochDay, railMinute)
        return railMinute.takeIf { actualMillisAllowed(millis, originEpochDay, nowMillis) }
    }

    private fun actualMillisAllowed(millis: Long, originEpochDay: Long, nowMillis: Long): Boolean {
        val runStart = RailTime.railMinuteToEpochMillis(originEpochDay, 0)
        val latestRunMinute = RailTime.railMinuteToEpochMillis(originEpochDay, 4 * RailTime.MINUTES_PER_DAY)
        val latestNow = if (nowMillis > Long.MAX_VALUE - 120_000L) Long.MAX_VALUE
            else nowMillis + 120_000L
        return millis >= runStart && millis < latestRunMinute && millis <= latestNow
    }

    private fun formatRunDate(epochDay: Long): String? {
        if (epochDay !in RailTime.daysFromCivil(1900, 1, 1)..RailTime.daysFromCivil(2200, 12, 31))
            return null
        val (year, month, day) = RailTime.civilFromDays(epochDay)
        return String.format(Locale.ENGLISH, "%02d-%02d-%04d", day, month, year)
    }

    private fun parseRunEpochDay(value: String): Long? {
        val pattern = when {
            value.matches(Regex("\\d{2}-\\d{2}-\\d{4}")) -> "dd-MM-yyyy"
            value.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> "yyyy-MM-dd"
            else -> return null
        }
        val format = SimpleDateFormat(pattern, Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
            isLenient = false
        }
        val position = ParsePosition(0)
        val date = format.parse(value, position) ?: return null
        if (position.index != value.length) return null
        return RailTime.istEpochDay(date.time)
    }

    /** Parse WIMT's offset-bearing ISO stamp without java.time/desugaring dependencies. */
    private fun parseIsoMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        val match = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})?$""")
            .matchEntire(iso.trim()) ?: return null
        val base = match.groupValues[1]
        val fraction = match.groupValues[2].take(3).padEnd(3, '0')
        val zone = match.groupValues[3]
        val localTime = "$base.$fraction"
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ENGLISH).apply {
                timeZone = TimeZone.getTimeZone(if (zone.isEmpty()) "Asia/Kolkata" else "UTC")
                isLenient = false
            }
            val position = ParsePosition(0)
            val parsed = format.parse(localTime, position) ?: return null
            if (position.index != localTime.length) return null
            if (zone.isEmpty() || zone == "Z") return parsed.time
            val offset = Regex("^([+-])(\\d{2}):?(\\d{2})$").matchEntire(zone) ?: return null
            val hours = offset.groupValues[2].toIntOrNull() ?: return null
            val minutes = offset.groupValues[3].toIntOrNull() ?: return null
            if (hours > 23 || minutes > 59) return null
            val signedOffset = (hours * 60L + minutes) * 60_000L *
                if (offset.groupValues[1] == "+") 1L else -1L
            parsed.time - signedOffset
        } catch (_: Exception) {
            null
        }
    }
}
