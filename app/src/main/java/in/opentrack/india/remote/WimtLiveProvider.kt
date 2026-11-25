package `in`.opentrack.india.remote

import `in`.opentrack.india.core.Diary
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.LiveSnapshot
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainRoute
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.text.ParsePosition
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLException
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
    private const val LOG_TAG = "WimtLiveProvider"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val MAX_TRANSIENT_ATTEMPTS = 2
    private const val MAX_RETRY_AFTER_SECONDS = 10
    private val requestSequence = AtomicLong(0L)
    // Identify this app honestly. Do not copy browser/WebView or hidden WIMT app identifiers.
    private const val USER_AGENT = "TrackTrain/1.0 (Android; selected WIMT source)"
    private const val MAX_RESPONSE_BYTES = 1_000_000
    const val FRESH_UPDATE_MAX_AGE_MS = 10 * 60_000L
    const val UPDATE_STAMP_FUTURE_TOLERANCE_MS = 120_000L
    const val NEAR_FUTURE_FALLBACK_GRACE_MS = 2_000L
    private const val FALLBACK_RECHECK_PADDING_MS = 150L

    /** Brief local wait for a tiny positive clock offset; it never makes the stamp fresh early. */
    fun fallbackWaitForNearFutureStamp(aheadByMillis: Long?): Long? =
        aheadByMillis?.takeIf { it in 1..NEAR_FUTURE_FALLBACK_GRACE_MS }
            ?.plus(FALLBACK_RECHECK_PADDING_MS)

    /** One direct GET for the selected train/run; not `.org.in` or the app's comparison-only parser. The endpoint may itself be cached. */
    internal fun requestUrl(trainNo: String, dayStr: String): String =
        "$ENDPOINT?train_no=$trainNo&date=$dayStr&lang=en"

    internal data class WireReply(
        val body: String?,
        val status: Int?,
        val detail: String,
        val responseBytes: Int? = body?.toByteArray(Charsets.UTF_8)?.size,
        /** Wall-clock time when the complete response body arrived; test seams may omit it. */
        val receivedAtMillis: Long? = null,
        /** Monotonic stage timings; header time includes DNS, connect, TLS and response headers. */
        val headerWaitMillis: Long? = null,
        val bodyReadMillis: Long? = null,
        val contentType: String? = null,
        val contentEncoding: String? = null,
        val serverDate: String? = null,
        val retryAfterSeconds: Int? = null,
        val failureClass: String? = null,
        val attemptCount: Int = 1,
        val retryReason: String? = null,
    )

    fun hasFreshSourceUpdate(result: WimtResult, nowMillis: Long): Boolean =
        result.snapshot?.feedStampMillis?.let { stamp ->
            stamp <= nowMillis && nowMillis - stamp <= FRESH_UPDATE_MAX_AGE_MS
        } == true

    /** Strict alert gate: an HTTP/JSON reply alone is never a selected-run live source. */
    fun alertQualificationFailure(result: WimtResult, nowMillis: Long): String? {
        if (result.snapshot == null) return "WIMT .in did not return a usable live report: " +
            result.detail.take(140)
        if (result.runDateMatched != true || result.routeMatched != true)
            return "WIMT .in did not echo a matching run date and both route endpoints"
        if (!hasFreshSourceUpdate(result, nowMillis))
            return "WIMT .in source update stamp is missing, future, or stale"
        return null
    }

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
        val parseDiagnostics: ParseDiagnostics = ParseDiagnostics(),
    )

    /** Counts only; deliberately excludes raw source values and response-body fragments. */
    data class ParseDiagnostics(
        val scheduleRows: Int = 0,
        val malformedRows: Int = 0,
        val rowsMissingStationCode: Int = 0,
        val etaRows: Int = 0,
        val actualClockFieldsPresent: Int = 0,
        val actualStopRowsAccepted: Int = 0,
        val rejectedInvertedDepartures: Int = 0,
        val currentStationRowFound: Boolean = false,
        val sourceStampFieldPresent: Boolean = false,
        val sourceStampParsed: Boolean = false,
    )

    fun fetch(
        trainNo: String,
        route: TrainRoute,
        originEpochDay: Long,
        nowMillis: Long = System.currentTimeMillis(),
        stationNameLookup: ((String) -> String?)? = null,
    ): WimtResult = fetchInternal(trainNo, route, originEpochDay, nowMillis, stationNameLookup) { url, id ->
        request(url, id)
    }

    /** Injectable wire boundary for JVM tests; production uses one ordinary Android GET. */
    internal fun fetch(
        trainNo: String,
        route: TrainRoute,
        originEpochDay: Long,
        nowMillis: Long,
        stationNameLookup: ((String) -> String?)?,
        get: (String) -> WireReply,
    ): WimtResult = fetchInternal(trainNo, route, originEpochDay, nowMillis,
        stationNameLookup) { url, _ -> get(url) }

    private fun fetchInternal(
        trainNo: String,
        route: TrainRoute,
        originEpochDay: Long,
        nowMillis: Long,
        stationNameLookup: ((String) -> String?)?,
        get: (String, String) -> WireReply,
    ): WimtResult {
        val requestId = requestSequence.incrementAndGet()
        if (!trainNo.matches(Regex("[0-9]{4,5}")) || !route.trainNo.equals(trainNo, true)) {
            emit("request_rejected", "id=$requestId category=TRAIN_OR_ROUTE_MISMATCH")
            return WimtResult(null, "WIMT request train number does not match the loaded timetable")
        }
        if (route.stops.isEmpty()) {
            emit("request_rejected", "id=$requestId category=EMPTY_TIMETABLE_STOPS trainNo=$trainNo")
            return WimtResult(null, "No timetable stops available for WIMT")
        }
        val dayStr = formatRunDate(originEpochDay) ?: run {
            emit("request_rejected", "id=$requestId category=INVALID_RUN_DATE trainNo=$trainNo")
            return WimtResult(null, "Invalid WIMT run date")
        }
        val requestStartedNanos = System.nanoTime()
        emit("request_start", "id=$requestId trainNo=$trainNo runDate=$dayStr " +
            "route=${route.srcCode}-${route.dstCode} timeoutConnectMs=$CONNECT_TIMEOUT_MS " +
            "timeoutReadMs=$READ_TIMEOUT_MS")
        val url = requestUrl(trainNo, dayStr)
        val wire = try {
            get(url, requestId.toString())
        } catch (e: Exception) {
            val failureClass = classifyTransportFailure(e)
            WireReply(null, null, failureClass, failureClass = failureClass)
        }
        val elapsedMs = elapsedMillis(requestStartedNanos)
        if (wire.status != HttpURLConnection.HTTP_OK) {
            val detail = wire.detail.ifBlank { "WIMT server returned HTTP ${wire.status ?: "unknown"}" }
            emit("request_complete", requestLogFields(requestId, wire, elapsedMs) +
                " outcome=${wire.failureClass ?: httpOutcome(wire.status)}")
            return WimtResult(null, detail, responseCode = wire.status, responseBytes = wire.responseBytes)
        }
        val body = wire.body ?: run {
            val detail = wire.detail.ifBlank { "WIMT returned an empty response" }
            emit("request_complete", requestLogFields(requestId, wire, elapsedMs) +
                " outcome=${wire.failureClass ?: "EMPTY_BODY"}")
            return WimtResult(null, detail, responseCode = wire.status, responseBytes = wire.responseBytes)
        }
        val bodyBytes = body.toByteArray(Charsets.UTF_8).size
        if (bodyBytes > MAX_RESPONSE_BYTES) {
            emit("request_complete", requestLogFields(requestId, wire, elapsedMs) +
                " outcome=RESPONSE_TOO_LARGE decodedBytes=$bodyBytes")
            return WimtResult(null, "WIMT response exceeded $MAX_RESPONSE_BYTES bytes",
                responseCode = wire.status, responseBytes = wire.responseBytes ?: bodyBytes)
        }
        // Freshness details must use the response receipt clock, not the time before a slow GET.
        // Keep the injected test seam deterministic when it does not supply a receipt timestamp.
        val responseNowMillis = wire.receivedAtMillis ?: nowMillis
        val parseStartedNanos = System.nanoTime()
        val parsed = try {
            parse(body, trainNo, route, originEpochDay, responseNowMillis, stationNameLookup)
        } catch (e: Exception) {
            val category = "PARSER_EXCEPTION"
            emit("parse_failure", "id=$requestId category=$category exception=${e.javaClass.simpleName}")
            WimtResult(null, "WIMT response could not be parsed (${e.javaClass.simpleName})")
        }
        val parseElapsedMs = elapsedMillis(parseStartedNanos)
        val finalResult = parsed.copy(responseCode = wire.status,
            responseBytes = wire.responseBytes ?: bodyBytes)
        val sourceRowCount = finalResult.snapshot?.perStop?.count { (_, fields) ->
            fields.actualArrivalMin != null || fields.actualDepartureMin != null
        } ?: 0
        emit("request_complete", requestLogFields(requestId, wire, elapsedMs) +
            " outcome=${parseOutcome(finalResult)} parseMs=$parseElapsedMs " +
            "runDateMatched=${finalResult.runDateMatched ?: "unknown"} " +
            "routeMatched=${finalResult.routeMatched ?: "unknown"} sourceRows=$sourceRowCount " +
            "currentStationPresent=${!finalResult.currentStationCode.isNullOrBlank()} " +
            "scheduleRows=${finalResult.parseDiagnostics.scheduleRows} " +
            "malformedRows=${finalResult.parseDiagnostics.malformedRows} " +
            "rowsMissingStationCode=${finalResult.parseDiagnostics.rowsMissingStationCode} " +
            "etaRows=${finalResult.parseDiagnostics.etaRows} " +
            "actualClockFields=${finalResult.parseDiagnostics.actualClockFieldsPresent} " +
            "acceptedActualRows=${finalResult.parseDiagnostics.actualStopRowsAccepted} " +
            "invertedDeparturesRejected=${finalResult.parseDiagnostics.rejectedInvertedDepartures} " +
            "currentStationRowFound=${finalResult.parseDiagnostics.currentStationRowFound} " +
            "sourceStampField=${finalResult.parseDiagnostics.sourceStampFieldPresent} " +
            "sourceStampParsed=${finalResult.parseDiagnostics.sourceStampParsed} " +
            "sourceStampAgeMs=${finalResult.snapshot?.feedStampMillis?.let { responseNowMillis - it } ?: "unknown"} " +
            "fresh=${hasFreshSourceUpdate(finalResult, responseNowMillis)}")
        return finalResult
    }

    internal fun request(url: String): WireReply =
        request(url, "net-${requestSequence.incrementAndGet()}")

    private fun request(url: String, requestId: String): WireReply {
        var last = WireReply(null, null, "request was not attempted", failureClass = "NOT_ATTEMPTED")
        var retryReason: String? = null
        for (attempt in 1..MAX_TRANSIENT_ATTEMPTS) {
            if (attempt > 1) {
                val retryDelay = retryDelayMillis(last)
                emit("retry", "attempt=$attempt previous=${retryReason ?: "transient_failure"} " +
                    "delayMs=$retryDelay status=${last.status ?: "none"}")
                try {
                    Thread.sleep(retryDelay)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return last.copy(failureClass = "INTERRUPTED", retryReason = retryReason,
                        attemptCount = attempt - 1)
                }
            }
            last = requestOnce(url, attempt, requestId)
            if (attempt == MAX_TRANSIENT_ATTEMPTS || !isRetryable(last)) {
                return last.copy(attemptCount = attempt, retryReason = retryReason)
            }
            retryReason = last.failureClass ?: "HTTP_${last.status}"
        }
        return last.copy(attemptCount = MAX_TRANSIENT_ATTEMPTS, retryReason = retryReason)
    }

    /** One bounded network attempt. No redirect, refusal, response body or request URL is logged. */
    private fun requestOnce(url: String, attempt: Int, requestId: String): WireReply {
        var connection: HttpURLConnection? = null
        var phase = "open_connection"
        val startedNanos = System.nanoTime()
        var headerWaitMillis: Long? = null
        var bodyStartedNanos: Long? = null
        var status: Int? = null
        var contentType: String? = null
        var contentEncoding: String? = null
        var serverDate: String? = null
        var retryAfterSeconds: Int? = null
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            connection = conn
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            // Send only ordinary, documented negotiation headers. Never impersonate a browser,
            // pass cookies, or attempt to bypass a refusal/challenge.
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            phase = "response_headers_dns_connect_tls"
            val headersStartedNanos = System.nanoTime()
            val responseStatus = conn.responseCode
            status = responseStatus
            headerWaitMillis = elapsedMillis(headersStartedNanos)
            contentType = conn.contentType?.take(120)
            contentEncoding = conn.contentEncoding?.take(40)
            serverDate = conn.getHeaderField("Date")?.take(80)
            retryAfterSeconds = parseRetryAfterSeconds(conn.getHeaderField("Retry-After"))
            if (responseStatus !in 200..299) {
                runCatching { conn.errorStream?.close() }
                val reply = WireReply(null, responseStatus,
                    "WIMT server returned HTTP $responseStatus", responseBytes = null,
                    receivedAtMillis = System.currentTimeMillis(),
                    headerWaitMillis = headerWaitMillis, bodyReadMillis = 0L,
                    contentType = contentType, contentEncoding = contentEncoding,
                    serverDate = serverDate, retryAfterSeconds = retryAfterSeconds,
                    failureClass = httpOutcome(responseStatus))
                emit("http_response", requestLogFields(requestId, reply, elapsedMillis(startedNanos)) +
                    " attempt=$attempt")
                return reply
            }
            phase = "read_body"
            bodyStartedNanos = System.nanoTime()
            val stream = conn.inputStream
            val decodedStream = if (contentEncoding.equals("gzip", ignoreCase = true))
                GZIPInputStream(stream) else stream
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            var total = 0
            decodedStream.use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_RESPONSE_BYTES) {
                        val elapsed = elapsedMillis(startedNanos)
                        val bodyElapsed = elapsedMillis(bodyStartedNanos)
                        return WireReply(null, status,
                            "WIMT response exceeded $MAX_RESPONSE_BYTES bytes", total,
                            receivedAtMillis = System.currentTimeMillis(),
                            headerWaitMillis = headerWaitMillis, bodyReadMillis = bodyElapsed,
                            contentType = contentType, contentEncoding = contentEncoding,
                            serverDate = serverDate, retryAfterSeconds = retryAfterSeconds,
                            failureClass = "RESPONSE_TOO_LARGE").also {
                            emit("http_response", requestLogFields(requestId, it, elapsed) +
                                " attempt=$attempt outcome=RESPONSE_TOO_LARGE")
                        }
                    }
                    output.write(buffer, 0, count)
                }
            }
            val body = output.toString(Charsets.UTF_8.name())
            val bodyElapsed = elapsedMillis(bodyStartedNanos)
            val reply = WireReply(body, status,
                if (body.isEmpty()) "WIMT returned an empty response" else "HTTP $status", total,
                System.currentTimeMillis(), headerWaitMillis, bodyElapsed, contentType,
                contentEncoding, serverDate, retryAfterSeconds,
                if (body.isEmpty()) "EMPTY_BODY" else null)
            emit("http_response", requestLogFields(requestId, reply, elapsedMillis(startedNanos)) +
                " attempt=$attempt")
            return reply
        } catch (e: Exception) {
            val failureClass = classifyTransportFailure(e)
            val bodyElapsed = bodyStartedNanos?.let(::elapsedMillis)
            val reply = WireReply(null, status,
                "$failureClass (${e.javaClass.simpleName})", null,
                System.currentTimeMillis(), headerWaitMillis, bodyElapsed, contentType,
                contentEncoding, serverDate, retryAfterSeconds, failureClass)
            emit("network_failure", "phase=$phase attempt=$attempt " +
                requestLogFields(requestId, reply, elapsedMillis(startedNanos)) +
                " exception=${e.javaClass.simpleName}")
            return reply
        } finally {
            connection?.disconnect()
        }
    }

    private fun isRetryable(reply: WireReply): Boolean = when {
        reply.status == 429 || reply.status?.let { it in 300..399 || it in 400..499 } == true -> false
        reply.status == 500 || reply.status == 502 || reply.status == 503 || reply.status == 504 ->
            reply.retryAfterSeconds == null ||
                reply.retryAfterSeconds?.let { it <= MAX_RETRY_AFTER_SECONDS } == true
        reply.status != null -> false
        reply.failureClass in setOf("DNS_FAILURE", "CONNECT_FAILURE", "TLS_FAILURE",
            "SOCKET_TIMEOUT", "SOCKET_FAILURE", "IO_FAILURE") -> true
        else -> false
    }

    private fun retryDelayMillis(reply: WireReply): Long = when {
        reply.retryAfterSeconds != null -> reply.retryAfterSeconds.coerceAtMost(
            MAX_RETRY_AFTER_SECONDS).toLong() * 1_000L
        reply.status == 500 || reply.status == 502 || reply.status == 503 || reply.status == 504 -> 750L
        else -> 400L
    }

    private fun parseRetryAfterSeconds(raw: String?): Int? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toIntOrNull()?.let { return it.coerceAtLeast(0) }
        return runCatching {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
                isLenient = false
            }
            val retryAt = format.parse(value)?.time ?: return null
            ((retryAt - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1_000L
        }.getOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
    }

    private fun classifyTransportFailure(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(6).toList()
        return when {
            chain.any { it is UnknownHostException } -> "DNS_FAILURE"
            chain.any { it is SocketTimeoutException } -> "SOCKET_TIMEOUT"
            chain.any { it is SSLException } -> "TLS_FAILURE"
            chain.any { it is java.net.ConnectException } -> "CONNECT_FAILURE"
            chain.any { it is java.net.SocketException } -> "SOCKET_FAILURE"
            chain.any { it is IOException } -> "IO_FAILURE"
            else -> "NETWORK_EXCEPTION"
        }
    }

    private fun httpOutcome(status: Int?): String {
        val code = status ?: return "NO_HTTP_RESPONSE"
        return when {
            code == 403 -> "HTTP_403_REFUSED"
            code == 429 -> "HTTP_429_RATE_LIMITED"
            code in 500..599 -> "HTTP_${code}_SERVER_ERROR"
            code in 300..399 -> "HTTP_${code}_REDIRECT_NOT_FOLLOWED"
            code in 400..499 -> "HTTP_${code}_CLIENT_ERROR"
            else -> "HTTP_${code}_UNEXPECTED"
        }
    }

    private fun parseOutcome(result: WimtResult): String {
        if (result.snapshot == null) {
            val detail = result.detail.lowercase(Locale.ENGLISH)
            return when {
                detail.contains("invalid json") -> "INVALID_JSON"
                detail.contains("no schedule") -> "MISSING_SCHEDULE_ARRAY"
                detail.contains("train number did not match") -> "TRAIN_MISMATCH"
                detail.contains("route endpoints did not match") -> "ROUTE_MISMATCH"
                detail.contains("run date did not match") -> "RUN_DATE_MISMATCH"
                detail.contains("response exceeded") -> "RESPONSE_TOO_LARGE"
                detail.contains("could not be parsed") -> "PARSER_EXCEPTION"
                detail.contains("timetable stops") -> "NO_TIMETABLE_STOPS"
                detail.contains("invalid wimt run date") -> "INVALID_REQUEST_DATE"
                else -> "NO_QUALIFIED_SNAPSHOT"
            }
        }
        return when {
            result.runDateMatched == false -> "RUN_DATE_MISMATCH"
            result.routeMatched == false -> "ROUTE_MISMATCH"
            result.runDateMatched != true -> "RUN_DATE_NOT_ECHOED"
            result.routeMatched != true -> "ROUTE_NOT_FULLY_ECHOED"
            result.snapshot.feedStampMillis == null -> "SOURCE_STAMP_MISSING_OR_INVALID"
            result.snapshot.perStop.values.none {
                it.actualArrivalMin != null || it.actualDepartureMin != null
            } -> "ZERO_SOURCE_ACTUAL_STOPS"
            else -> "SOURCE_ROWS_PRESENT"
        }
    }

    private fun elapsedMillis(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos).coerceAtLeast(0L)) / 1_000_000L

    private fun requestLogFields(id: Any, reply: WireReply, elapsedMs: Long): String =
        "id=$id status=${reply.status ?: "none"} totalMs=$elapsedMs " +
            "headersMs=${reply.headerWaitMillis ?: "unknown"} bodyMs=${reply.bodyReadMillis ?: "unknown"} " +
            "bytes=${reply.responseBytes ?: "unknown"} encoding=${reply.contentEncoding ?: "identity"} " +
            "contentType=${reply.contentType?.replace(Regex("[^A-Za-z0-9_./+;-]"), "") ?: "unknown"} " +
            "retryAfterSeconds=${reply.retryAfterSeconds ?: "none"} attempts=${reply.attemptCount} " +
            "retryReason=${reply.retryReason ?: "none"} failure=${reply.failureClass ?: "none"} " +
            "serverDate=${reply.serverDate?.replace(Regex("\\s+"), "_") ?: "unknown"}"

    private fun emit(event: String, fields: String) {
        val line = "event=$event $fields"
        runCatching { Log.i(LOG_TAG, line) }
        runCatching { Diary.get()?.log("WIMT", line) }
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
        var malformedRows = 0
        var rowsMissingStationCode = 0
        var etaRows = 0
        var actualClockFieldsPresent = 0

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
            val row = rows.optJSONObject(i)
            if (row == null) {
                malformedRows++
                continue
            }
            val code = firstNonBlank(row, "station_code")?.uppercase(Locale.ENGLISH)
            if (code == null) {
                rowsMissingStationCode++
                continue
            }
            val isEta = row.optString("source").equals("eta", ignoreCase = true)
            if (isEta) etaRows++
            if (listOf("actual_arrival_time", "actual_arrival_tm", "actual_departure_time",
                    "actual_departure_tm", "actual_arrival_epoch", "actual_departure_epoch")
                    .any { firstNonBlank(row, it) != null }) actualClockFieldsPresent++

            val isCur = code.equals(curStnCode, true) || row.optBoolean("curStn", false)
            // WIMT marks unreached future halts with source: "eta". Also treat every row after
            // its current-station pointer as not reached, even if an ETA tag is omitted.
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
        // Do not bake a transient future/stale verdict into the response's fixed detail string.
        // LiveViewModel evaluates this stamp against the latest device clock whenever it renders.
        val updateDetail = if (parsedUpdateMillis != null)
            "upstream update stamp reported; freshness is evaluated locally (not event time)"
        else "upstream update timestamp unavailable"
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
            parseDiagnostics = ParseDiagnostics(
                scheduleRows = rows.length(),
                malformedRows = malformedRows,
                rowsMissingStationCode = rowsMissingStationCode,
                etaRows = etaRows,
                actualClockFieldsPresent = actualClockFieldsPresent,
                actualStopRowsAccepted = actualCount,
                rejectedInvertedDepartures = rejectedInvertedDepartures,
                currentStationRowFound = curStnIdx >= 0,
                sourceStampFieldPresent = !updateIso.isNullOrBlank(),
                sourceStampParsed = parsedUpdateMillis != null,
            ),
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
