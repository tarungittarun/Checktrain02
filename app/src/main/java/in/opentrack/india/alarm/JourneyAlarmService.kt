package `in`.opentrack.india.alarm

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import `in`.opentrack.india.App
import `in`.opentrack.india.core.Diary
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.data.RailRepository
import `in`.opentrack.india.model.SourceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/** One user-started foreground service monitors multiple, independently editable journey alerts. */
class JourneyAlarmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Accessed on the service/main thread; each alert has its own poll and deadline jobs. */
    private val monitorJobs = mutableMapOf<Int, Job>()
    private val monitorConfigs = mutableMapOf<Int, JourneyAlarmConfig>()
    private val dueJobs = mutableMapOf<Int, Job>()
    private val expiryJobs = mutableMapOf<Int, Job>()
    private var ringPlayer: MediaPlayer? = null

    /** Multiple alerts for the same run share one short-lived source result rather than duplicating HTTP work. */
    private data class SourceBatch(
        val reports: List<RailRepository.Live>,
        val failures: List<String>,
        val checkedAtMillis: Long,
    )
    private data class TimedSourceBatch(val atMillis: Long, val batch: SourceBatch)
    private data class TimedErailProbe(val atMillis: Long, val probe: RailRepository.SourceProbe)
    private data class CheckResult(
        val decision: JourneyAlarmPolicy.Decision,
        val sourceLabel: String? = null,
        val receivedSources: String? = null,
        val failures: List<String> = emptyList(),
    )
    private val sourceLocks = ConcurrentHashMap<String, Mutex>()
    private val sourceCache = ConcurrentHashMap<String, TimedSourceBatch>()
    private val erailLocks = ConcurrentHashMap<String, Mutex>()
    private val erailCache = ConcurrentHashMap<String, TimedErailProbe>()

    override fun onCreate() {
        super.onCreate()
        JourneyAlarmNotifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val id = intent.getIntExtra(EXTRA_ALERT_ID, NO_ALERT_ID)
                if (id > 0) {
                    JourneyAlarmStore.dismiss(this, id)
                    JourneyAlarmNotifications.cancelAlert(this, id)
                } else {
                    JourneyAlarmStore.dismissActive(this)
                    JourneyAlarmNotifications.cancel(this)
                }
            }
            ACTION_STOP_RINGING -> {
                val id = intent.getIntExtra(EXTRA_ALERT_ID, NO_ALERT_ID)
                val snapshot = if (id > 0) JourneyAlarmStore.find(this, id)
                    else JourneyAlarmStore.readAll(this).firstOrNull { it.state == AlertState.RINGING }
                snapshot?.takeIf { it.state == AlertState.RINGING }?.let {
                    JourneyAlarmStore.update(this, it.id, AlertState.DISMISSED,
                        "Alarm stopped by you", checkFailed = false)
                    JourneyAlarmNotifications.cancelAlert(this, it.id)
                }
            }
        }
        return reconcile(startId)
    }

    /** Reconcile a single foreground notice and the ID-keyed monitor/deadline jobs. */
    private fun reconcile(startId: Int = 0): Int {
        val all = JourneyAlarmStore.readAll(this)
        val active = all.filter { it.state == AlertState.ARMED || it.state == AlertState.RINGING }
        val armed = active.filter { it.state == AlertState.ARMED }.associate { it.id to it.config }
        monitorJobs.toMap().forEach { (id, job) ->
            if (armed[id] != monitorConfigs[id]) {
                job.cancel()
                monitorJobs.remove(id)
                monitorConfigs.remove(id)
                dueJobs.remove(id)?.cancel()
                expiryJobs.remove(id)?.cancel()
            }
        }
        dueJobs.toMap().forEach { (id, job) -> if (id !in armed) {
            job.cancel(); dueJobs.remove(id)
        } }
        expiryJobs.toMap().forEach { (id, job) -> if (id !in armed) {
            job.cancel(); expiryJobs.remove(id)
        } }
        if (active.isEmpty()) {
            stopRinging()
            JourneyAlarmNotifications.cancelMonitor(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (startId > 0) stopSelf(startId) else stopSelf()
            return START_NOT_STICKY
        }

        startForegroundWithType(JourneyAlarmNotifications.MONITOR_ID,
            JourneyAlarmNotifications.monitoring(this, active), active)
        active.filter { it.state == AlertState.RINGING }.forEach { snapshot ->
            val (title, detail) = snapshot.status.split('\n', limit = 2)
                .let { it.firstOrNull().orEmpty() to it.getOrNull(1).orEmpty() }
            androidx.core.app.NotificationManagerCompat.from(this).notify(snapshot.id,
                JourneyAlarmNotifications.ringing(this, snapshot, title, detail))
        }
        if (active.any { it.state == AlertState.RINGING }) startRinging() else stopRinging()
        active.filter { it.state == AlertState.ARMED }.forEach { snapshot ->
            if (monitorJobs[snapshot.id]?.isActive != true ||
                monitorConfigs[snapshot.id] != snapshot.config) startMonitoring(snapshot)
        }
        return START_STICKY
    }

    private fun startMonitoring(initial: JourneyAlarmSnapshot) {
        val id = initial.id
        val config = initial.config
        monitorConfigs[id] = config
        val job = scope.launch {
            try {
                // Old saved alerts predate an explicit run-end minute. Recover it from the
                // exact local route once so no legacy monitor can poll indefinitely.
                val expiryAt = resolveExpiryAt(config)
                withContext(Dispatchers.Main) {
                    val latest = JourneyAlarmStore.find(this@JourneyAlarmService, id)
                    if (latest?.state == AlertState.ARMED && latest.config == config)
                        scheduleDeadlineJobs(initial, expiryAt)
                }
                while (isActive) {
                    val snapshot = JourneyAlarmStore.find(this@JourneyAlarmService, id)
                    if (snapshot?.state != AlertState.ARMED || snapshot.config != config) break
                    val now = System.currentTimeMillis()
                    if (now >= expiryAt) {
                        expire(initial)
                        break
                    }
                    val dueAt = JourneyAlarmSchedule.dueAtMillis(config)
                    if (dueAt != null && now >= dueAt) {
                        fireScheduled(initial, dueAt)
                        break
                    }
                    // Do not begin a slow network ladder just before a known scheduled reminder.
                    if (dueAt != null && dueAt - now in 1..2_000L) {
                        delay(dueAt - now)
                        continue
                    }

                    val check = try {
                        val repo = (application as App).repository()
                        if (repo == null) CheckResult(
                            JourneyAlarmPolicy.Decision.Waiting("Timetable and live sources are unavailable"),
                            failures = listOf("The local rail repository could not be opened"),
                        ) else if (config.trigger == AlertTrigger.PHONE_DISTANCE) {
                            checkPhoneDistance(repo, config)
                        } else checkStationEvent(repo, config)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        CheckResult(
                            JourneyAlarmPolicy.Decision.Waiting(
                                "Status check could not run; the alert remains armed"),
                            failures = listOf("Source check failed (${failure.javaClass.simpleName})"),
                        )
                    }

                    val stillArmed = JourneyAlarmStore.find(this@JourneyAlarmService, id)
                    if (stillArmed?.state != AlertState.ARMED || stillArmed.config != config) break
                    when (val decision = check.decision) {
                        is JourneyAlarmPolicy.Decision.Fire -> {
                            fire(initial, decision, check.sourceLabel ?: check.receivedSources)
                            break
                        }
                        is JourneyAlarmPolicy.Decision.Missed -> {
                            markMissed(initial, decision, check.sourceLabel ?: check.receivedSources)
                            break
                        }
                        is JourneyAlarmPolicy.Decision.Waiting -> {
                            val failed = check.failures.isNotEmpty()
                            val failureText = check.failures.joinToString(" · ").take(FAILURE_SUMMARY_LIMIT)
                            val status = when {
                                failed && check.receivedSources != null ->
                                    "${check.receivedSources} checked · another source unavailable: $failureText"
                                failed -> "Source check unavailable · $failureText"
                                else -> decision.detail
                            }
                            val updated = JourneyAlarmStore.updateArmed(this@JourneyAlarmService,
                                id, config, status, checkedAtMillis = System.currentTimeMillis(),
                                lastSourceLabel = check.receivedSources, checkFailed = failed)
                            if (!updated) break
                            val saved = JourneyAlarmStore.find(this@JourneyAlarmService, id)
                            if (failed && !snapshot.checkFailed && saved != null &&
                                saved.state == AlertState.ARMED && saved.config == config && saved.checkFailed) {
                                JourneyAlarmNotifications.checkWarning(this@JourneyAlarmService,
                                    saved, failureText.ifBlank {
                                        "A fresh source report could not be received."
                                    })
                                val afterWarning = JourneyAlarmStore.find(this@JourneyAlarmService, id)
                                if (afterWarning == null || afterWarning.state != AlertState.ARMED ||
                                    afterWarning.config != config || !afterWarning.checkFailed)
                                    JourneyAlarmNotifications.cancelAlert(this@JourneyAlarmService, id)
                            } else if (!failed && snapshot.checkFailed) {
                                JourneyAlarmNotifications.cancelAlert(this@JourneyAlarmService, id)
                            }
                            withContext(Dispatchers.Main) { refreshForegroundNotification() }
                            val afterCheck = JourneyAlarmStore.find(this@JourneyAlarmService, id)
                            if (afterCheck?.state != AlertState.ARMED || afterCheck.config != config) break
                            val dueDelay = dueAt?.minus(System.currentTimeMillis())
                                ?.takeIf { it > 0L } ?: Long.MAX_VALUE
                            val endDelay = expiryAt - System.currentTimeMillis()
                            delay(max(1L, min(config.intervalMinutes * 60_000L,
                                min(dueDelay, endDelay.coerceAtLeast(1L)))))
                        }
                    }
                }
            } finally {
                val thisJob = currentCoroutineContext()[Job]
                withContext(Dispatchers.Main) {
                    if (monitorJobs[id] === thisJob) {
                        monitorJobs.remove(id)
                        monitorConfigs.remove(id)
                    }
                    reconcile()
                }
            }
        }
        monitorJobs[id] = job
    }

    private suspend fun resolveExpiryAt(config: JourneyAlarmConfig): Long {
        if (config.journeyEndMinute != null || config.scheduledMinute != null)
            return JourneyAlarmSchedule.expiryAtMillis(config)
        val repo = (application as App).repository() ?: return JourneyAlarmSchedule.expiryAtMillis(config)
        val saved = runCatching {
            repo.savedSchedule(config.trainNo, config.fromCode, config.toCode, config.originEpochDay)
        }.getOrNull()
        val bundled = runCatching { repo.route(config.trainNo) }.getOrNull()
        val route = JourneyAlarmRouteSelection.firstMatching(
            config.trainNo, config.fromCode, config.toCode, saved, bundled)
            ?: return JourneyAlarmSchedule.expiryAtMillis(config)
        val target = route.stops.firstOrNull { it.code.equals(config.toCode, true) }
            ?: return JourneyAlarmSchedule.expiryAtMillis(config)
        val endMinute = target.arrivalMin ?: target.departureMin
            ?: return JourneyAlarmSchedule.expiryAtMillis(config)
        return RailTime.railMinuteToEpochMillis(config.originEpochDay, endMinute) +
            JourneyAlarmSchedule.RUN_END_GRACE_MILLIS
    }

    /** Deadline jobs run separately from provider requests so a slow source cannot delay the alarm. */
    private fun scheduleDeadlineJobs(initial: JourneyAlarmSnapshot, expiryAt: Long) {
        val id = initial.id
        val dueAt = JourneyAlarmSchedule.dueAtMillis(initial.config)
        if (dueAt != null && dueJobs[id]?.isActive != true) {
            val dueJob = scope.launch(start = CoroutineStart.LAZY) {
                val wait = (dueAt - System.currentTimeMillis()).coerceAtLeast(0L)
                if (wait > 0L) delay(wait)
                fireScheduled(initial, dueAt)
                withContext(Dispatchers.Main) { reconcile() }
            }
            dueJobs[id] = dueJob
            dueJob.start()
        }
        if (expiryJobs[id]?.isActive != true) {
            val expiryJob = scope.launch(start = CoroutineStart.LAZY) {
                val wait = (expiryAt - System.currentTimeMillis()).coerceAtLeast(0L)
                if (wait > 0L) delay(wait)
                expire(initial)
                withContext(Dispatchers.Main) { reconcile() }
            }
            expiryJobs[id] = expiryJob
            expiryJob.start()
        }
    }

    private suspend fun checkStationEvent(repo: RailRepository,
                                         config: JourneyAlarmConfig): CheckResult {
        val batch = sharedSourceBatch(repo, config)
        val failures = batch.failures.toMutableList()
        val received = mutableListOf<String>()
        val details = mutableListOf<String>()
        var ntesReport: RailRepository.Live? = null

        // Evaluate the first accepted report immediately. If it qualifies, the user is notified
        // without waiting for a slower secondary request. If it does not, probe the other source.
        for (live in batch.reports) {
            val label = live.sourceName ?: JourneyAlarmPolicy.sourceLabel(live.provenance)
            received += label
            val decision = JourneyAlarmPolicy.stationEvent(
                config, live.route, live.answer, System.currentTimeMillis(),
                sourceDisplayName = label)
            when (decision) {
                is JourneyAlarmPolicy.Decision.Fire -> return CheckResult(
                    decision, sourceLabel = label, receivedSources = label,
                    failures = failures.distinct())
                is JourneyAlarmPolicy.Decision.Missed -> return CheckResult(
                    decision, sourceLabel = label, receivedSources = label,
                    failures = failures.distinct())
                is JourneyAlarmPolicy.Decision.Waiting -> details += "$label: ${decision.detail}"
            }
            if (live.provenance == SourceKind.PROVIDER_NTES) ntesReport = live
        }

        ntesReport?.let { first ->
            val independent = sharedErailProbe(repo, config, first.route)
            independent.live?.let { erail ->
                val label = erail.sourceName ?: JourneyAlarmPolicy.sourceLabel(erail.provenance)
                received += label
                when (val decision = JourneyAlarmPolicy.stationEvent(
                    config, erail.route, erail.answer, System.currentTimeMillis(),
                    sourceDisplayName = label)) {
                    is JourneyAlarmPolicy.Decision.Fire -> return CheckResult(
                        decision, sourceLabel = label,
                        receivedSources = received.distinct().joinToString(" + "),
                        failures = failures.distinct())
                    is JourneyAlarmPolicy.Decision.Missed -> return CheckResult(
                        decision, sourceLabel = label,
                        receivedSources = received.distinct().joinToString(" + "),
                        failures = failures.distinct())
                    is JourneyAlarmPolicy.Decision.Waiting -> details += "$label: ${decision.detail}"
                }
            } ?: independent.failure?.let(failures::add)
        }

        val sourceNames = received.distinct()
        val waiting = details.lastOrNull() ?: "No fresh route-matched report is available yet"
        val withSources = if (sourceNames.isNotEmpty())
            "${sourceNames.joinToString(" + ")} checked · $waiting" else waiting
        Diary.get()?.log("ARRIVAL_ALERT", "${config.trainNo} station check: " +
            "${sourceNames.joinToString(" + ").ifBlank { "no accepted live source" }}; " +
            "${failures.size} source check failure(s)")
        return CheckResult(JourneyAlarmPolicy.Decision.Waiting(withSources),
            receivedSources = sourceNames.takeIf { it.isNotEmpty() }?.joinToString(" + "),
            failures = failures.distinct())
    }

    /** In-flight and very short completed-result coalescing avoids duplicate network work for same-run alerts. */
    private suspend fun sharedSourceBatch(repo: RailRepository,
                                          config: JourneyAlarmConfig): SourceBatch {
        val key = sourceKey(config)
        val now = System.currentTimeMillis()
        sourceCache[key]?.takeIf { now - it.atMillis in 0..SOURCE_CACHE_MILLIS }
            ?.let { return it.batch }
        val mutex = sourceMutex(key)
        return mutex.withLock {
            val lockedNow = System.currentTimeMillis()
            sourceCache[key]?.takeIf { lockedNow - it.atMillis in 0..SOURCE_CACHE_MILLIS }
                ?.let { return@withLock it.batch }
            val batch = fetchSourceBatch(repo, config)
            sourceCache[key] = TimedSourceBatch(System.currentTimeMillis(), batch)
            trimSourceCache()
            batch
        }
    }

    private suspend fun sharedErailProbe(repo: RailRepository, config: JourneyAlarmConfig,
                                         route: `in`.opentrack.india.model.TrainRoute): RailRepository.SourceProbe {
        val key = sourceKey(config)
        val now = System.currentTimeMillis()
        erailCache[key]?.takeIf { now - it.atMillis in 0..SOURCE_CACHE_MILLIS }
            ?.let { return it.probe }
        val mutex = erailMutex(key)
        return mutex.withLock {
            val lockedNow = System.currentTimeMillis()
            erailCache[key]?.takeIf { lockedNow - it.atMillis in 0..SOURCE_CACHE_MILLIS }
                ?.let { return@withLock it.probe }
            val probe = try {
                repo.probeErailStatus(config.trainNo, route, config.originEpochDay)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                RailRepository.SourceProbe(null,
                    "erail source check failed (${failure.javaClass.simpleName})")
            }
            erailCache[key] = TimedErailProbe(System.currentTimeMillis(), probe)
            trimErailCache()
            probe
        }
    }

    private fun sourceKey(config: JourneyAlarmConfig) =
        "${config.trainNo.uppercase()}|${config.fromCode.uppercase()}|" +
            "${config.toCode.uppercase()}|${config.originEpochDay}"

    private fun sourceMutex(key: String): Mutex = sourceLocks[key] ?: synchronized(sourceLocks) {
        sourceLocks[key] ?: Mutex().also { sourceLocks[key] = it }
    }

    private fun erailMutex(key: String): Mutex = erailLocks[key] ?: synchronized(erailLocks) {
        erailLocks[key] ?: Mutex().also { erailLocks[key] = it }
    }

    private fun trimSourceCache() {
        if (sourceCache.size <= SOURCE_CACHE_LIMIT) return
        sourceCache.entries.sortedBy { it.value.atMillis }
            .take(sourceCache.size - SOURCE_CACHE_LIMIT)
            .forEach { sourceCache.remove(it.key, it.value) }
    }

    private fun trimErailCache() {
        if (erailCache.size <= SOURCE_CACHE_LIMIT) return
        erailCache.entries.sortedBy { it.value.atMillis }
            .take(erailCache.size - SOURCE_CACHE_LIMIT)
            .forEach { erailCache.remove(it.key, it.value) }
    }

    private suspend fun fetchSourceBatch(repo: RailRepository,
                                         config: JourneyAlarmConfig): SourceBatch = withContext(Dispatchers.IO) {
        val failures = mutableListOf<String>()
        val route = runCatching {
            repo.savedSchedule(config.trainNo, config.fromCode, config.toCode, config.originEpochDay)
                ?: repo.route(config.trainNo)
        }.getOrNull()
        val live = try {
            repo.live(config.trainNo, config.fromCode, config.toCode,
                config.originEpochDay, previous = null, fallbackRoute = route,
                allowOverride = false, includeLocation = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failures += "NTES/erail request failed (${failure.javaClass.simpleName})"
            null
        }
        if (live == null) {
            if (failures.isEmpty()) failures += "No route-checked NTES/erail report was received"
            return@withContext SourceBatch(emptyList(), failures.distinct(), System.currentTimeMillis())
        }
        failures += live.notes.filter { it.isNotBlank() }
        val selectedProviderHit = live.tried.any {
            it == "PROVIDER_NTES=hit" || it == "PROVIDER=hit"
        }
        val liveProviderKind = live.provenance == SourceKind.PROVIDER_NTES ||
            live.provenance == SourceKind.PROVIDER
        val candidate = if (selectedProviderHit && liveProviderKind) listOf(live) else emptyList()
        if (candidate.isEmpty() && failures.isEmpty())
            failures += "No fresh live report was returned by NTES or erail"
        SourceBatch(candidate, failures.distinct(), System.currentTimeMillis())
    }

    private suspend fun checkPhoneDistance(repo: RailRepository,
                                           config: JourneyAlarmConfig): CheckResult {
        val route = try {
            repo.savedSchedule(config.trainNo, config.fromCode, config.toCode, config.originEpochDay)
                ?: repo.route(config.trainNo)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return CheckResult(JourneyAlarmPolicy.Decision.Waiting(
                "Route lookup failed; distance monitoring remains armed"),
                failures = listOf("Could not load the route for phone-distance monitoring"))
        }
        if (route == null) return CheckResult(JourneyAlarmPolicy.Decision.Waiting(
            "No route available to measure distance to ${config.toName}"),
            failures = listOf("No route is available for the configured phone-distance check"))
        if (!repo.hasLocationPermission()) return CheckResult(
            JourneyAlarmPolicy.Decision.Waiting("Phone-location permission is unavailable"),
            failures = listOf("Phone-location permission is not currently granted"))
        val sensor = repo.sensor()
        val fix = try {
            sensor.singleFix()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return CheckResult(JourneyAlarmPolicy.Decision.Waiting(
                "Phone location could not be read; distance monitoring remains armed"),
                failures = listOf("Phone-location check failed (${failure.javaClass.simpleName})"))
        } ?: return CheckResult(JourneyAlarmPolicy.Decision.Waiting(
            "No recent phone location; distance monitoring remains armed"),
            failures = listOf("No fresh phone-location fix could be obtained"))
        val routeMatch = sensor.evidence(fix, route) != null
        val evidence = JourneyAlarmPolicy.PhoneFix(fix.lat, fix.lon, fix.accuracyM,
            fix.atMillis, fix.speedKmh, fix.movedMeters, routeMatch)
        return CheckResult(JourneyAlarmPolicy.distanceEvent(
            config, route, evidence, System.currentTimeMillis()),
            receivedSources = "Phone location")
    }

    private suspend fun fireScheduled(initial: JourneyAlarmSnapshot, dueAtMillis: Long) {
        val config = initial.config
        val stationTime = config.scheduledMinute?.let { RailTime.formatRailMinute(it) } ?: "unknown"
        val due = JourneyAlarmPolicy.Decision.Fire(
            title = "Scheduled reminder · ${config.toName}",
            detail = "The published timetable lists ${config.toName} at $stationTime. " +
                "This reminder was set ${config.leadSeconds} seconds before that timetable time; " +
                "it is not a live ETA or a source-reported arrival.",
            evidenceAtMillis = dueAtMillis,
        )
        fire(initial, due, evidenceSourceLabel = null)
    }

    private suspend fun fire(initial: JourneyAlarmSnapshot,
                             event: JourneyAlarmPolicy.Decision.Fire,
                             sourceLabel: String? = null,
                             evidenceSourceLabel: String? = sourceLabel) {
        val id = initial.id
        val config = initial.config
        val ringing = config.feedback == AlertFeedback.RING_UNTIL_STOPPED
        val state = if (ringing) AlertState.RINGING else AlertState.FIRED
        val status = if (ringing) "${event.title}\n${event.detail}"
            else "${event.title} · ${event.detail}"
        val won = JourneyAlarmStore.transitionFromArmed(this, id, config, state, status,
            evidenceAtMillis = event.evidenceAtMillis,
            lastSourceLabel = sourceLabel, evidenceSourceLabel = evidenceSourceLabel)
        if (!won) return
        withContext(Dispatchers.Main) {
            val snapshot = JourneyAlarmStore.find(this@JourneyAlarmService, id)
                ?: return@withContext
            if (snapshot.state != state || snapshot.config != config) return@withContext
            JourneyAlarmNotifications.cancelAlert(this@JourneyAlarmService, id)
            if (ringing) {
                androidx.core.app.NotificationManagerCompat.from(this@JourneyAlarmService)
                    .notify(id, JourneyAlarmNotifications.ringing(this@JourneyAlarmService,
                        snapshot, event.title, event.detail))
                startRinging()
            } else {
                JourneyAlarmNotifications.triggered(this@JourneyAlarmService,
                    snapshot, event.title, event.detail)
            }
        }
        Diary.get()?.log("ARRIVAL_ALERT", "${config.trainNo} fired: ${event.detail}")
    }

    private suspend fun markMissed(initial: JourneyAlarmSnapshot,
                                   event: JourneyAlarmPolicy.Decision.Missed,
                                   sourceLabel: String? = null) {
        val won = JourneyAlarmStore.transitionFromArmed(this, initial.id, initial.config,
            AlertState.MISSED, event.detail, evidenceAtMillis = event.evidenceAtMillis,
            lastSourceLabel = sourceLabel, evidenceSourceLabel = sourceLabel)
        if (!won) return
        withContext(Dispatchers.Main) {
            val snapshot = JourneyAlarmStore.find(this@JourneyAlarmService, initial.id)
                ?: return@withContext
            if (snapshot.state != AlertState.MISSED || snapshot.config != initial.config)
                return@withContext
            JourneyAlarmNotifications.cancelAlert(this@JourneyAlarmService, initial.id)
            JourneyAlarmNotifications.triggered(this@JourneyAlarmService,
                snapshot, "Earlier stop was not reported", event.detail, missed = true)
        }
    }

    private suspend fun expire(initial: JourneyAlarmSnapshot) {
        val detail = "The selected run window ended. Monitoring stopped; no qualifying source event was received."
        val won = JourneyAlarmStore.transitionFromArmed(this, initial.id, initial.config,
            AlertState.MISSED, detail)
        if (!won) return
        withContext(Dispatchers.Main) {
            val snapshot = JourneyAlarmStore.find(this@JourneyAlarmService, initial.id)
                ?: return@withContext
            if (snapshot.state != AlertState.MISSED || snapshot.config != initial.config)
                return@withContext
            JourneyAlarmNotifications.cancelAlert(this@JourneyAlarmService, initial.id)
            JourneyAlarmNotifications.triggered(this@JourneyAlarmService,
                snapshot, "Alert expired", detail, missed = true)
        }
    }

    private fun refreshForegroundNotification() {
        val active = JourneyAlarmStore.readAll(this).filter {
            it.state == AlertState.ARMED || it.state == AlertState.RINGING
        }
        if (active.isEmpty()) {
            reconcile()
            return
        }
        startForegroundWithType(JourneyAlarmNotifications.MONITOR_ID,
            JourneyAlarmNotifications.monitoring(this, active), active)
    }

    private fun startForegroundWithType(id: Int, notification: android.app.Notification,
                                        active: List<JourneyAlarmSnapshot>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (active.any { it.config.trigger == AlertTrigger.PHONE_DISTANCE })
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(id, notification, type)
        } else startForeground(id, notification)
    }

    private fun startRinging() {
        if (ringPlayer != null) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return
        val player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                setDataSource(this@JourneyAlarmService, uri)
                isLooping = true
                setOnPreparedListener { it.start() }
                setOnErrorListener { mp, _, _ ->
                    runCatching { mp.release() }
                    if (ringPlayer === mp) ringPlayer = null
                    true
                }
                prepareAsync()
            }
        }.getOrNull()
        ringPlayer = player
    }

    private fun stopRinging() {
        ringPlayer?.let { player ->
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.release() }
        }
        ringPlayer = null
    }

    override fun onDestroy() {
        monitorJobs.values.toList().forEach { it.cancel() }
        dueJobs.values.toList().forEach { it.cancel() }
        expiryJobs.values.toList().forEach { it.cancel() }
        monitorJobs.clear()
        monitorConfigs.clear()
        dueJobs.clear()
        expiryJobs.clear()
        stopRinging()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val SOURCE_CACHE_MILLIS = 10_000L
        private const val SOURCE_CACHE_LIMIT = 32
        private const val FAILURE_SUMMARY_LIMIT = 420
        const val ACTION_CANCEL = "in.opentrack.india.alarm.CANCEL"
        const val ACTION_STOP_RINGING = "in.opentrack.india.alarm.STOP_RINGING"
        const val ACTION_RECONFIGURE = "in.opentrack.india.alarm.RECONFIGURE"
        const val EXTRA_ALERT_ID = "alertId"
        private const val NO_ALERT_ID = -1

        fun start(context: android.content.Context, config: JourneyAlarmConfig): Int {
            val id = JourneyAlarmStore.arm(context, config)
            val intent = Intent(context, JourneyAlarmService::class.java).setAction(ACTION_RECONFIGURE)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (failure: RuntimeException) {
                JourneyAlarmStore.remove(context, id)
                runCatching { JourneyAlarmNotifications.cancelAlert(context, id) }
                throw failure
            }
            return id
        }

        fun cancel(context: android.content.Context, id: Int) {
            JourneyAlarmStore.dismiss(context, id)
            JourneyAlarmNotifications.cancelAlert(context, id)
            reconfigure(context)
        }

        fun reconfigure(context: android.content.Context) {
            val intent = Intent(context, JourneyAlarmService::class.java).setAction(ACTION_RECONFIGURE)
            val active = JourneyAlarmStore.readAll(context).any {
                it.state == AlertState.ARMED || it.state == AlertState.RINGING
            }
            if (active) runCatching { ContextCompat.startForegroundService(context, intent) }
            else runCatching { context.startService(intent) }
        }

        /** Explicit all-alert cancellation for the legacy global action only. */
        fun cancel(context: android.content.Context) {
            JourneyAlarmStore.dismissActive(context)
            context.stopService(Intent(context, JourneyAlarmService::class.java))
            JourneyAlarmNotifications.cancel(context)
        }
    }
}
