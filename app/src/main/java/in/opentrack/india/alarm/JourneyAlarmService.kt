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
import `in`.opentrack.india.model.TrainRoute
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
    private val expiryJobs = mutableMapOf<Int, Job>()
    private val repeatJobs = mutableMapOf<Int, Job>()
    private val repeatConfigs = mutableMapOf<Int, Pair<JourneyAlarmConfig, AlertState>>()
    private var ringPlayer: MediaPlayer? = null

    /** Multiple alerts for the same run share one short-lived source result rather than duplicating HTTP work. */
    private data class SourceBatch(
        val reports: List<RailRepository.Live>,
        val failures: List<String>,
        val checkedAtMillis: Long,
        val route: TrainRoute? = null,
    )
    private data class TimedSourceBatch(val atMillis: Long, val batch: SourceBatch)
    private data class TimedWimtProbe(val atMillis: Long, val probe: RailRepository.SourceProbe)
    private data class CheckResult(
        val decision: JourneyAlarmPolicy.Decision,
        val sourceLabel: String? = null,
        val receivedSources: String? = null,
        val failures: List<String> = emptyList(),
        /** True only if at least one source supplied data usable under the alert's qualification rules. */
        val hasUsableSourceData: Boolean = false,
    )
    private val sourceLocks = ConcurrentHashMap<String, Mutex>()
    private val sourceCache = ConcurrentHashMap<String, TimedSourceBatch>()
    private val wimtLocks = ConcurrentHashMap<String, Mutex>()
    private val wimtCache = ConcurrentHashMap<String, TimedWimtProbe>()

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
            ACTION_STOP_ALERT, ACTION_STOP_RINGING -> {
                val id = intent.getIntExtra(EXTRA_ALERT_ID, NO_ALERT_ID)
                val snapshot = if (id > 0) JourneyAlarmStore.find(this, id)
                    else JourneyAlarmStore.readAll(this).firstOrNull {
                        it.state == AlertState.ALERTING || it.state == AlertState.RINGING
                    }
                snapshot?.takeIf {
                    it.state == AlertState.ALERTING || it.state == AlertState.RINGING
                }?.let {
                    JourneyAlarmStore.update(this, it.id, AlertState.DISMISSED,
                        "Alert stopped by you", checkFailed = false)
                    JourneyAlarmNotifications.cancelAlert(this, it.id)
                }
            }
            ACTION_SNOOZE -> {
                val id = intent.getIntExtra(EXTRA_ALERT_ID, NO_ALERT_ID)
                val snapshot = JourneyAlarmStore.find(this, id)
                snapshot?.takeIf {
                    it.state == AlertState.ALERTING || it.state == AlertState.RINGING
                }?.let {
                    val until = System.currentTimeMillis() + JourneyAlarmSchedule.SNOOZE_MILLIS
                    JourneyAlarmStore.updateActiveTiming(this, it.id, it.config, it.state,
                        snoozedUntilMillis = until, nextRepeatAtMillis = until)
                }
            }
        }
        return reconcile(startId)
    }

    /** Reconcile a single foreground notice and the ID-keyed monitor/deadline jobs. */
    private fun reconcile(startId: Int = 0): Int {
        val all = JourneyAlarmStore.readAll(this)
        val active = all.filter { isActiveState(it.state) }
        val activeById = active.associateBy { it.id }
        val armed = active.filter { it.state == AlertState.ARMED }.associate { it.id to it.config }
        monitorJobs.toMap().forEach { (id, job) ->
            if (armed[id] != monitorConfigs[id]) {
                job.cancel()
                monitorJobs.remove(id)
                monitorConfigs.remove(id)
                expiryJobs.remove(id)?.cancel()
            }
        }
        expiryJobs.toMap().forEach { (id, job) -> if (id !in armed) {
            job.cancel(); expiryJobs.remove(id)
        } }
        repeatJobs.toMap().forEach { (id, job) ->
            val snapshot = activeById[id]
            val needsTimer = snapshot != null &&
                ((snapshot.state == AlertState.ALERTING &&
                    (snapshot.snoozedUntilMillis > 0L || snapshot.nextRepeatAtMillis > 0L)) ||
                    (snapshot.state == AlertState.RINGING && snapshot.snoozedUntilMillis > 0L))
            val current = snapshot?.let { it.config to it.state }
            if (!needsTimer || repeatConfigs[id] != current) {
                job.cancel()
                repeatJobs.remove(id)
                repeatConfigs.remove(id)
            }
        }
        if (active.isEmpty()) {
            stopRinging()
            JourneyAlarmNotifications.cancelMonitor(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (startId > 0) stopSelf(startId) else stopSelf()
            return START_NOT_STICKY
        }

        startForegroundWithType(JourneyAlarmNotifications.MONITOR_ID,
            JourneyAlarmNotifications.monitoring(this, active), active)
        active.filter { it.state == AlertState.ALERTING || it.state == AlertState.RINGING }
            .forEach { snapshot ->
                androidx.core.app.NotificationManagerCompat.from(this).notify(snapshot.id,
                    JourneyAlarmNotifications.activeAlert(this, snapshot, soundNow = false))
            }
        val ringNow = active.any {
            it.state == AlertState.RINGING && it.snoozedUntilMillis <= System.currentTimeMillis()
        }
        if (ringNow) startRinging() else stopRinging()
        active.filter { it.state == AlertState.ARMED }.forEach { snapshot ->
            if (monitorJobs[snapshot.id]?.isActive != true ||
                monitorConfigs[snapshot.id] != snapshot.config) startMonitoring(snapshot)
        }
        active.filter {
            (it.state == AlertState.ALERTING &&
                (it.snoozedUntilMillis > 0L || it.nextRepeatAtMillis > 0L)) ||
                (it.state == AlertState.RINGING && it.snoozedUntilMillis > 0L)
        }.forEach(::startAlertTimer)
        return START_STICKY
    }

    /** Persisted per-alert timer for repeated ping/vibration and 2-minute snooze expiry. */
    private fun startAlertTimer(initial: JourneyAlarmSnapshot) {
        val id = initial.id
        val config = initial.config
        val state = initial.state
        val key = config to state
        if (repeatJobs[id]?.isActive == true && repeatConfigs[id] == key) return
        repeatJobs[id]?.cancel()
        repeatJobs.remove(id)
        repeatConfigs.remove(id)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (isActive) {
                    var snapshot = JourneyAlarmStore.find(this@JourneyAlarmService, id) ?: break
                    if (snapshot.state != state || snapshot.config != config) break
                    val now = System.currentTimeMillis()
                    val targetAt = when {
                        snapshot.snoozedUntilMillis > 0L -> snapshot.snoozedUntilMillis
                        state == AlertState.RINGING -> break
                        snapshot.nextRepeatAtMillis > 0L -> snapshot.nextRepeatAtMillis
                        else -> break
                    }
                    if (targetAt > now) delay(targetAt - now)
                    snapshot = JourneyAlarmStore.find(this@JourneyAlarmService, id) ?: break
                    if (snapshot.state != state || snapshot.config != config) break
                    val resumedAt = System.currentTimeMillis()
                    if (snapshot.snoozedUntilMillis > 0L &&
                        snapshot.snoozedUntilMillis <= resumedAt) {
                        val nextRepeat = if (state == AlertState.ALERTING &&
                            config.repeatIntervalSeconds > JourneyAlarmSchedule.REPEAT_ONCE)
                            resumedAt + config.repeatIntervalSeconds * 1_000L else 0L
                        if (!JourneyAlarmStore.updateActiveTiming(this@JourneyAlarmService, id,
                                config, state, snoozedUntilMillis = 0L,
                                nextRepeatAtMillis = nextRepeat)) {
                            val current = JourneyAlarmStore.find(this@JourneyAlarmService, id)
                            if (current != null && current.state == state && current.config == config)
                                continue
                            break
                        }
                        val resumed = JourneyAlarmStore.find(this@JourneyAlarmService, id) ?: break
                        withContext(Dispatchers.Main) {
                            if (resumed.state == state && resumed.config == config) {
                                if (state == AlertState.ALERTING)
                                    androidx.core.app.NotificationManagerCompat.from(this@JourneyAlarmService)
                                        .notify(id, JourneyAlarmNotifications.activeAlert(
                                            this@JourneyAlarmService, resumed, soundNow = true))
                                reconcile()
                            }
                        }
                        continue
                    }
                    if (state == AlertState.ALERTING && snapshot.snoozedUntilMillis <= 0L &&
                        snapshot.nextRepeatAtMillis > 0L && snapshot.nextRepeatAtMillis <= resumedAt) {
                        val nextRepeat = resumedAt + config.repeatIntervalSeconds * 1_000L
                        if (!JourneyAlarmStore.updateActiveTiming(this@JourneyAlarmService, id,
                                config, state, snoozedUntilMillis = 0L,
                                nextRepeatAtMillis = nextRepeat)) {
                            val current = JourneyAlarmStore.find(this@JourneyAlarmService, id)
                            if (current != null && current.state == state && current.config == config)
                                continue
                            break
                        }
                        val repeated = JourneyAlarmStore.find(this@JourneyAlarmService, id) ?: break
                        withContext(Dispatchers.Main) {
                            if (repeated.state == state && repeated.config == config &&
                                repeated.nextRepeatAtMillis == nextRepeat) {
                                androidx.core.app.NotificationManagerCompat.from(this@JourneyAlarmService)
                                    .notify(id, JourneyAlarmNotifications.activeAlert(
                                        this@JourneyAlarmService, repeated, soundNow = true))
                                refreshForegroundNotification()
                            }
                        }
                    }
                }
            } finally {
                val thisJob = currentCoroutineContext()[Job]
                withContext(Dispatchers.Main) {
                    if (repeatJobs[id] === thisJob) {
                        repeatJobs.remove(id)
                        repeatConfigs.remove(id)
                    }
                }
            }
        }
        repeatJobs[id] = job
        repeatConfigs[id] = key
        job.start()
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
                    // Wake at the reminder boundary, then check both qualified sources first.
                    // A real station event at or before schedule wins without a schedule ping.
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
                        is JourneyAlarmPolicy.Decision.Waiting -> {
                            // A partial outage is not a traveller-facing failure when any independent
                            // source still supplied fresh, route-qualified actual data. Notify only when
                            // the whole qualified fallback/retry set has no usable source result.
                            val noUsableSource = !check.hasUsableSourceData
                            val failureText = check.failures.joinToString(" · ").take(FAILURE_SUMMARY_LIMIT)
                            val detail = buildString {
                                append(decision.detail)
                                if (failureText.isNotBlank() && check.hasUsableSourceData)
                                    append(" · Other source attempts unavailable: ").append(failureText)
                            }
                            val status = when {
                                noUsableSource -> buildString {
                                    append("No usable live source data · ")
                                    append(failureText.ifBlank { decision.detail }.take(FAILURE_SUMMARY_LIMIT))
                                }
                                snapshot.checkFailed -> "Recovered · $detail"
                                else -> detail
                            }
                            val checkedAt = System.currentTimeMillis()
                            val transition = JourneyAlarmStore.updateArmedCheck(
                                this@JourneyAlarmService, id, config, status,
                                checkedAtMillis = checkedAt,
                                lastSourceLabel = check.receivedSources.takeIf { check.hasUsableSourceData },
                                hasUsableSourceData = check.hasUsableSourceData,
                            ) ?: break
                            if (transition.outageStarted) {
                                JourneyAlarmNotifications.checkWarning(this@JourneyAlarmService,
                                    transition.snapshot, failureText.ifBlank {
                                        "Neither NTES nor WIMT supplied fresh, usable station-event data."
                                    })
                                val afterWarning = JourneyAlarmStore.find(this@JourneyAlarmService, id)
                                if (afterWarning == null || afterWarning.state != AlertState.ARMED ||
                                    afterWarning.config != config || !afterWarning.checkFailed)
                                    JourneyAlarmNotifications.cancelCheckWarning(this@JourneyAlarmService, id)
                            } else if (transition.recovered) {
                                JourneyAlarmNotifications.cancelCheckWarning(this@JourneyAlarmService, id)
                            }
                            val dueAtForReminder = JourneyAlarmSchedule.dueAtMillis(config)
                            if (dueAtForReminder != null && JourneyAlarmSchedule.reminderDue(
                                    config, transition.snapshot.scheduledReminderSent, checkedAt))
                                sendScheduledReminder(initial, dueAtForReminder)
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

    /** Run expiry remains independent from provider requests; scheduled reminders require a live check first. */
    private fun scheduleDeadlineJobs(initial: JourneyAlarmSnapshot, expiryAt: Long) {
        val id = initial.id
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
        var hasUsableSourceData = false

        // Check the first source immediately; a qualifying Fire is returned without waiting for slower sources.
        // A later-station position without this stop's actual event remains Waiting, never terminal.
        for (live in batch.reports) {
            val label = live.sourceName ?: JourneyAlarmPolicy.sourceLabel(live.provenance)
            received += label
            val checkedAt = System.currentTimeMillis()
            val sourceUsable = JourneyAlarmPolicy.hasUsableSourceData(
                config, live.route, live.answer, checkedAt,
                source = JourneyAlarmPolicy.AlertEvidenceSource.NTES)
            hasUsableSourceData = hasUsableSourceData || sourceUsable
            val decision = JourneyAlarmPolicy.stationEvent(
                config, live.route, live.answer, checkedAt, sourceDisplayName = label,
                source = JourneyAlarmPolicy.AlertEvidenceSource.NTES)
            when (decision) {
                is JourneyAlarmPolicy.Decision.Fire -> return CheckResult(
                    decision, sourceLabel = label,
                    receivedSources = received.distinct().joinToString(" + "),
                    failures = failures.distinct(), hasUsableSourceData = true)
                is JourneyAlarmPolicy.Decision.Waiting -> details += "$label: ${decision.detail}"
            }
        }

        // Direct WIMT is checked independently from NTES. Its probe must echo this run date,
        // both selected route endpoints, and a fresh upstream stamp; no website or cache is used.
        val route = batch.route ?: batch.reports.firstOrNull()?.route
        if (route != null) {
            val wimt = sharedWimtProbe(repo, config, route)
            wimt.live?.let { live ->
                val label = live.sourceName ?: "WIMT .in live-status feed"
                received += label
                val checkedAt = System.currentTimeMillis()
                val sourceUsable = JourneyAlarmPolicy.hasUsableSourceData(
                    config, live.route, live.answer, checkedAt,
                    source = JourneyAlarmPolicy.AlertEvidenceSource.WIMT)
                hasUsableSourceData = hasUsableSourceData || sourceUsable
                when (val decision = JourneyAlarmPolicy.stationEvent(
                    config, live.route, live.answer, checkedAt,
                    sourceDisplayName = label,
                    source = JourneyAlarmPolicy.AlertEvidenceSource.WIMT)) {
                    is JourneyAlarmPolicy.Decision.Fire -> return CheckResult(
                        decision, sourceLabel = label,
                        receivedSources = received.distinct().joinToString(" + "),
                        failures = failures.distinct(), hasUsableSourceData = true)
                    is JourneyAlarmPolicy.Decision.Waiting -> details += "$label: ${decision.detail}"
                }
            } ?: wimt.failure?.let(failures::add)
        } else {
            failures += "No route was available for the WIMT .in alert check"
        }

        val sourceNames = received.distinct()
        val waiting = details.lastOrNull() ?: "No fresh route-matched report is available yet"
        val withSources = if (sourceNames.isNotEmpty())
            "${sourceNames.joinToString(" + ")} checked · $waiting" else waiting
        Diary.get()?.log("ARRIVAL_ALERT", "${config.trainNo} station check: " +
            "${sourceNames.joinToString(" + ").ifBlank { "no accepted live source" }}; " +
            if (hasUsableSourceData) "usable data received; ${failures.size} other source attempt(s) unavailable"
            else "no usable source data; ${failures.size} source attempt(s) unavailable")
        return CheckResult(JourneyAlarmPolicy.Decision.Waiting(withSources),
            receivedSources = sourceNames.takeIf { it.isNotEmpty() }?.joinToString(" + "),
            failures = failures.distinct(), hasUsableSourceData = hasUsableSourceData)
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

    private suspend fun sharedWimtProbe(repo: RailRepository, config: JourneyAlarmConfig,
                                        route: TrainRoute): RailRepository.SourceProbe {
        val key = sourceKey(config)
        val now = System.currentTimeMillis()
        wimtCache[key]?.takeIf { now - it.atMillis in 0..SOURCE_CACHE_MILLIS }
            ?.let { return it.probe }
        val mutex = wimtMutex(key)
        return mutex.withLock {
            val lockedNow = System.currentTimeMillis()
            wimtCache[key]?.takeIf { lockedNow - it.atMillis in 0..SOURCE_CACHE_MILLIS }
                ?.let { return@withLock it.probe }
            val probe = try {
                repo.probeWimtStatus(config.trainNo, route, config.originEpochDay)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                RailRepository.SourceProbe(null,
                    "WIMT .in source check failed (${failure.javaClass.simpleName})")
            }
            wimtCache[key] = TimedWimtProbe(System.currentTimeMillis(), probe)
            trimWimtCache()
            probe
        }
    }

    private fun sourceKey(config: JourneyAlarmConfig) =
        "${config.trainNo.uppercase()}|${config.fromCode.uppercase()}|" +
            "${config.toCode.uppercase()}|${config.originEpochDay}"

    private fun sourceMutex(key: String): Mutex = sourceLocks[key] ?: synchronized(sourceLocks) {
        sourceLocks[key] ?: Mutex().also { sourceLocks[key] = it }
    }

    private fun wimtMutex(key: String): Mutex = wimtLocks[key] ?: synchronized(wimtLocks) {
        wimtLocks[key] ?: Mutex().also { wimtLocks[key] = it }
    }

    private fun trimSourceCache() {
        if (sourceCache.size <= SOURCE_CACHE_LIMIT) return
        sourceCache.entries.sortedBy { it.value.atMillis }
            .take(sourceCache.size - SOURCE_CACHE_LIMIT)
            .forEach { sourceCache.remove(it.key, it.value) }
    }

    private fun trimWimtCache() {
        if (wimtCache.size <= SOURCE_CACHE_LIMIT) return
        wimtCache.entries.sortedBy { it.value.atMillis }
            .take(wimtCache.size - SOURCE_CACHE_LIMIT)
            .forEach { wimtCache.remove(it.key, it.value) }
    }

    private suspend fun fetchSourceBatch(repo: RailRepository,
                                         config: JourneyAlarmConfig): SourceBatch = withContext(Dispatchers.IO) {
        val failures = mutableListOf<String>()
        val route = try {
            val saved = repo.savedSchedule(config.trainNo, config.fromCode,
                config.toCode, config.originEpochDay)
            val bundled = repo.route(config.trainNo)
            JourneyAlarmRouteSelection.firstMatching(config.trainNo, config.fromCode,
                config.toCode, saved, bundled)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failures += "Could not load a route for the NTES/WIMT alert check (${failure.javaClass.simpleName})"
            null
        }
        if (route == null) {
            if (failures.isEmpty()) failures += "No route matches the selected train and ordered journey"
            return@withContext SourceBatch(emptyList(), failures.distinct(),
                System.currentTimeMillis(), null)
        }

        val probe = try {
            repo.probeNtesStatus(config.trainNo, route, config.originEpochDay,
                config.fromCode, config.toCode)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            RailRepository.SourceProbe(null,
                "NTES alert check failed (${failure.javaClass.simpleName})")
        }
        val report = probe.live?.takeIf {
            it.sourceName == "NTES running status" &&
                it.provenance == SourceKind.PROVIDER_NTES &&
                it.originEpochDay == config.originEpochDay
        }
        if (report == null) failures += probe.failure
            ?: "NTES did not return a fresh, dated, route-matched running-status report"
        SourceBatch(if (report == null) emptyList() else listOf(report), failures.distinct(),
            System.currentTimeMillis(), route)
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
        val checkedAt = System.currentTimeMillis()
        val decision = JourneyAlarmPolicy.distanceEvent(config, route, evidence, checkedAt)
        val usableFix = checkedAt - fix.atMillis in 0..90_000L &&
            fix.accuracyM in 0f..250f && routeMatch
        return CheckResult(decision, receivedSources = "Phone location",
            hasUsableSourceData = usableFix)
    }

    private suspend fun sendScheduledReminder(initial: JourneyAlarmSnapshot, dueAtMillis: Long) {
        val config = initial.config
        val sentAt = System.currentTimeMillis()
        val status = "Scheduled reminder sent · monitoring continues for a fresh report at ${config.toName}"
        val saved = JourneyAlarmStore.markScheduledReminderSent(
            this, initial.id, config, status, sentAt) ?: return
        withContext(Dispatchers.Main) {
            val latest = JourneyAlarmStore.find(this@JourneyAlarmService, initial.id)
                ?: return@withContext
            if (latest.state != AlertState.ARMED || latest.config != config ||
                !latest.scheduledReminderSent) return@withContext
            androidx.core.app.NotificationManagerCompat.from(this@JourneyAlarmService).notify(
                initial.id, JourneyAlarmNotifications.scheduledReminder(
                    this@JourneyAlarmService, saved, dueAtMillis))
            refreshForegroundNotification()
        }
        Diary.get()?.log("ARRIVAL_ALERT", "${config.trainNo} scheduled reminder sent; monitor remains ARMED")
    }

    private suspend fun fire(initial: JourneyAlarmSnapshot,
                             event: JourneyAlarmPolicy.Decision.Fire,
                             sourceLabel: String? = null,
                             evidenceSourceLabel: String? = sourceLabel) {
        val id = initial.id
        val config = initial.config
        val ringing = config.feedback == AlertFeedback.RING_UNTIL_STOPPED
        val state = if (ringing) AlertState.RINGING else AlertState.ALERTING
        val status = if (ringing) "${event.title}\n${event.detail}"
            else "${event.title} · ${event.detail}"
        val now = System.currentTimeMillis()
        val nextRepeat = if (ringing ||
            config.repeatIntervalSeconds == JourneyAlarmSchedule.REPEAT_ONCE) 0L
            else now + config.repeatIntervalSeconds * 1_000L
        val won = JourneyAlarmStore.transitionFromArmed(this, id, config, state, status,
            checkedAtMillis = now,
            evidenceAtMillis = event.evidenceAtMillis,
            lastSourceLabel = sourceLabel, evidenceSourceLabel = evidenceSourceLabel,
            nextRepeatAtMillis = nextRepeat)
        if (!won) return
        withContext(Dispatchers.Main) {
            val snapshot = JourneyAlarmStore.find(this@JourneyAlarmService, id)
                ?: return@withContext
            if (snapshot.state != state || snapshot.config != config) return@withContext
            JourneyAlarmNotifications.cancelAlert(this@JourneyAlarmService, id)
            androidx.core.app.NotificationManagerCompat.from(this@JourneyAlarmService).notify(id,
                JourneyAlarmNotifications.activeAlert(this@JourneyAlarmService, snapshot,
                    soundNow = !ringing))
            if (ringing) startRinging()
        }
        Diary.get()?.log("ARRIVAL_ALERT", "${config.trainNo} fired: ${event.detail}")
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

    private fun isActiveState(state: AlertState): Boolean =
        state == AlertState.ARMED || state == AlertState.ALERTING || state == AlertState.RINGING

    private fun refreshForegroundNotification() {
        val active = JourneyAlarmStore.readAll(this).filter { isActiveState(it.state) }
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
        expiryJobs.values.toList().forEach { it.cancel() }
        repeatJobs.values.toList().forEach { it.cancel() }
        monitorJobs.clear()
        monitorConfigs.clear()
        expiryJobs.clear()
        repeatJobs.clear()
        repeatConfigs.clear()
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
        const val ACTION_STOP_ALERT = "in.opentrack.india.alarm.STOP_ALERT"
        const val ACTION_SNOOZE = "in.opentrack.india.alarm.SNOOZE"
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
                it.state == AlertState.ARMED || it.state == AlertState.ALERTING ||
                    it.state == AlertState.RINGING
            }
            if (active) runCatching { ContextCompat.startForegroundService(context, intent) }
            else runCatching { context.startService(intent) }
        }

        fun stopAlert(context: android.content.Context, id: Int) {
            val snapshot = JourneyAlarmStore.find(context, id) ?: return
            if (snapshot.state != AlertState.ALERTING && snapshot.state != AlertState.RINGING) return
            JourneyAlarmStore.update(context, id, AlertState.DISMISSED,
                "Alert stopped by you", checkFailed = false)
            JourneyAlarmNotifications.cancelAlert(context, id)
            reconfigure(context)
        }

        fun snooze(context: android.content.Context, id: Int) {
            val snapshot = JourneyAlarmStore.find(context, id) ?: return
            if (snapshot.state != AlertState.ALERTING && snapshot.state != AlertState.RINGING) return
            val until = System.currentTimeMillis() + JourneyAlarmSchedule.SNOOZE_MILLIS
            if (JourneyAlarmStore.updateActiveTiming(context, id, snapshot.config, snapshot.state,
                    snoozedUntilMillis = until, nextRepeatAtMillis = until)) {
                reconfigure(context)
            }
        }

        /** Explicit all-alert cancellation for the legacy global action only. */
        fun cancel(context: android.content.Context) {
            JourneyAlarmStore.dismissActive(context)
            context.stopService(Intent(context, JourneyAlarmService::class.java))
            JourneyAlarmNotifications.cancel(context)
        }
    }
}
