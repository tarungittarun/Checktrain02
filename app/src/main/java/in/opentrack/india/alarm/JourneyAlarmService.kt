package `in`.opentrack.india.alarm

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import `in`.opentrack.india.App
import `in`.opentrack.india.core.Diary
import `in`.opentrack.india.data.RailRepository
import `in`.opentrack.india.domain.PositionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One user-started foreground service can monitor many independently cancellable journey alerts. */
class JourneyAlarmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Accessed on the service/main thread; each alert has its own polling state and job. */
    private val monitorJobs = mutableMapOf<Int, Job>()
    private var ringPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

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
                        "Alarm stopped by you")
                    JourneyAlarmNotifications.cancelAlert(this, it.id)
                }
            }
        }
        return reconcile(startId)
    }

    /** Reconcile one foreground notice and an ID-keyed set of monitor loops. */
    private fun reconcile(startId: Int = 0): Int {
        val all = JourneyAlarmStore.readAll(this)
        val active = all.filter { it.state == AlertState.ARMED || it.state == AlertState.RINGING }
        val armedIds = active.filter { it.state == AlertState.ARMED }.map { it.id }.toSet()
        monitorJobs.toMap().forEach { (id, job) ->
            if (id !in armedIds) {
                job.cancel()
                monitorJobs.remove(id)
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
        active.filter { it.state == AlertState.RINGING }.forEach { snapshot ->
            val (title, detail) = snapshot.status.split('\n', limit = 2)
                .let { it.firstOrNull().orEmpty() to it.getOrNull(1).orEmpty() }
            androidx.core.app.NotificationManagerCompat.from(this).notify(snapshot.id,
                JourneyAlarmNotifications.ringing(this, snapshot, title, detail))
        }
        if (active.any { it.state == AlertState.RINGING }) startRinging() else stopRinging()
        active.filter { it.state == AlertState.ARMED }.forEach { snapshot ->
            if (monitorJobs[snapshot.id]?.isActive != true) startMonitoring(snapshot)
        }
        return START_STICKY
    }

    private fun startMonitoring(initial: JourneyAlarmSnapshot) {
        val id = initial.id
        val config = initial.config
        val job = scope.launch {
            var previous: PositionEngine.TrainPositionInput? = null
            try {
                while (isActive) {
                    val snapshot = JourneyAlarmStore.find(this@JourneyAlarmService, id)
                    if (snapshot?.state != AlertState.ARMED || snapshot.config != config) break
                    val decision = try {
                        val repo = (application as App).repository()
                        if (repo == null) {
                            JourneyAlarmPolicy.Decision.Waiting("Timetable unavailable; alert is still armed")
                        } else if (config.trigger == AlertTrigger.PHONE_DISTANCE) {
                            checkPhoneDistance(repo, config)
                        } else {
                            val result = checkStationEvent(repo, config, previous)
                            result.second?.let { live ->
                                if (live.answer.position.provenance.live)
                                    previous = PositionEngine.TrainPositionInput(live.answer.position)
                            }
                            result.first
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        JourneyAlarmPolicy.Decision.Waiting(
                            "Status check failed (${failure.javaClass.simpleName}); will retry")
                    }
                    val stillArmed = JourneyAlarmStore.find(this@JourneyAlarmService, id)
                    if (stillArmed?.state != AlertState.ARMED || stillArmed.config != config) break
                    when (decision) {
                        is JourneyAlarmPolicy.Decision.Fire -> fire(initial, decision)
                        is JourneyAlarmPolicy.Decision.Missed -> markMissed(initial, decision)
                        is JourneyAlarmPolicy.Decision.Waiting -> {
                            JourneyAlarmStore.update(this@JourneyAlarmService, id, AlertState.ARMED,
                                decision.detail, checkedAtMillis = System.currentTimeMillis())
                            withContext(Dispatchers.Main) { refreshForegroundNotification() }
                            delay(config.intervalMinutes * 60_000L)
                        }
                    }
                    if (decision !is JourneyAlarmPolicy.Decision.Waiting) break
                }
            } finally {
                withContext(Dispatchers.Main) {
                    monitorJobs.remove(id)
                    reconcile()
                }
            }
        }
        monitorJobs[id] = job
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

    private suspend fun checkStationEvent(
        repo: RailRepository,
        config: JourneyAlarmConfig,
        previous: PositionEngine.TrainPositionInput?,
    ): Pair<JourneyAlarmPolicy.Decision, RailRepository.Live?> {
        val live = runCatching {
            repo.live(config.trainNo, config.fromCode, config.toCode,
                config.originEpochDay, previous, allowOverride = false, includeLocation = false)
        }.getOrNull() ?: return JourneyAlarmPolicy.Decision.Waiting(
            "No route-checked running-status reply yet; will retry"
        ) to null
        val decision = JourneyAlarmPolicy.stationEvent(
            config, live.route, live.answer, System.currentTimeMillis())
        Diary.get()?.log("ARRIVAL_ALERT", "${config.trainNo} ${config.trigger.name}: " +
            "${live.answer.position.provenance.label}, ${decision.javaClass.simpleName}")
        return decision to live
    }

    private suspend fun checkPhoneDistance(
        repo: RailRepository,
        config: JourneyAlarmConfig,
    ): JourneyAlarmPolicy.Decision {
        val route = runCatching {
            repo.savedSchedule(config.trainNo, config.fromCode, config.toCode, config.originEpochDay)
                ?: repo.route(config.trainNo)
        }.getOrNull() ?: return JourneyAlarmPolicy.Decision.Waiting(
            "No route available to measure distance to ${config.toName}"
        )
        val sensor = repo.sensor()
        val fix = if (repo.hasLocationPermission())
            runCatching { sensor.singleFix() }.getOrNull()
        else null
        val routeMatch = fix?.let { sensor.evidence(it, route) != null } == true
        val evidence = fix?.let {
            JourneyAlarmPolicy.PhoneFix(it.lat, it.lon, it.accuracyM, it.atMillis,
                it.speedKmh, it.movedMeters, routeMatch)
        }
        return JourneyAlarmPolicy.distanceEvent(
            config, route, evidence, System.currentTimeMillis())
    }

    private suspend fun fire(initial: JourneyAlarmSnapshot, event: JourneyAlarmPolicy.Decision.Fire) {
        val id = initial.id
        val config = initial.config
        val saved = JourneyAlarmStore.find(this, id)
        if (saved?.state != AlertState.ARMED || saved.config != config) return
        if (config.feedback == AlertFeedback.RING_UNTIL_STOPPED) {
            JourneyAlarmStore.update(this, id, AlertState.RINGING,
                "${event.title}\n${event.detail}", evidenceAtMillis = event.evidenceAtMillis)
            withContext(Dispatchers.Main) {
                JourneyAlarmStore.find(this@JourneyAlarmService, id)?.let { snapshot ->
                    androidx.core.app.NotificationManagerCompat.from(this@JourneyAlarmService)
                        .notify(id, JourneyAlarmNotifications.ringing(this@JourneyAlarmService,
                            snapshot, event.title, event.detail))
                }
                startRinging()
            }
        } else {
            JourneyAlarmStore.update(this, id, AlertState.FIRED,
                "${event.title} · ${event.detail}", evidenceAtMillis = event.evidenceAtMillis)
            withContext(Dispatchers.Main) {
                JourneyAlarmStore.find(this@JourneyAlarmService, id)?.let { snapshot ->
                    JourneyAlarmNotifications.triggered(this@JourneyAlarmService,
                        snapshot, event.title, event.detail)
                }
            }
        }
        Diary.get()?.log("ARRIVAL_ALERT", "${config.trainNo} fired: ${event.detail}")
    }

    private suspend fun markMissed(initial: JourneyAlarmSnapshot,
                                   event: JourneyAlarmPolicy.Decision.Missed) {
        val id = initial.id
        val saved = JourneyAlarmStore.find(this, id)
        if (saved?.state != AlertState.ARMED || saved.config != initial.config) return
        JourneyAlarmStore.update(this, id, AlertState.MISSED, event.detail,
            evidenceAtMillis = event.evidenceAtMillis)
        withContext(Dispatchers.Main) {
            JourneyAlarmStore.find(this@JourneyAlarmService, id)?.let { snapshot ->
                JourneyAlarmNotifications.triggered(this@JourneyAlarmService,
                    snapshot, "Earlier stop was not reported", event.detail, missed = true)
            }
        }
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
        if (ringPlayer?.isPlaying == true) return
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vibrator = getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
        val v = vibrator
        if (v?.hasVibrator() == true) {
            val pattern = longArrayOf(0L, 650L, 300L, 650L, 700L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                v.vibrate(VibrationEffect.createWaveform(pattern, 0))
            else @Suppress("DEPRECATION") v.vibrate(pattern, 0)
        }
    }

    private fun stopRinging() {
        ringPlayer?.let { player ->
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.release() }
        }
        ringPlayer = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator?.cancel()
        else @Suppress("DEPRECATION") vibrator?.cancel()
        vibrator = null
    }

    override fun onDestroy() {
        monitorJobs.values.toList().forEach { it.cancel() }
        monitorJobs.clear()
        stopRinging()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
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
            val intent = Intent(context, JourneyAlarmService::class.java).setAction(ACTION_RECONFIGURE)
            // Cancellation can leave no active alerts. Starting this as a foreground service
            // in that case would violate Android's requirement to post a foreground notice.
            // This call originates from the visible Alerts screen; if other alerts remain,
            // reconcile() promotes the service before returning.
            runCatching { context.startService(intent) }
        }

        /** Explicit all-alert cancellation for the legacy global action only. */
        fun cancel(context: android.content.Context) {
            JourneyAlarmStore.dismissActive(context)
            context.stopService(Intent(context, JourneyAlarmService::class.java))
            JourneyAlarmNotifications.cancel(context)
        }
    }
}
