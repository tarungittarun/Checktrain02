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

/** User-started foreground monitor. It polls only while an alert is armed and is always cancellable. */
class JourneyAlarmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var activeConfig: JourneyAlarmConfig? = null
    private var ringPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onCreate() {
        super.onCreate()
        JourneyAlarmNotifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                JourneyAlarmStore.clear(this)
                stopAlarmAndService()
                return START_NOT_STICKY
            }
            ACTION_STOP_RINGING -> {
                JourneyAlarmStore.update(this, AlertState.DISMISSED, "Alarm stopped by you")
                stopAlarmAndService()
                return START_NOT_STICKY
            }
        }
        val snapshot = JourneyAlarmStore.read(this)
        if (snapshot == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (snapshot.state) {
            AlertState.ARMED -> {
                startForegroundWithType(JourneyAlarmNotifications.MONITOR_ID,
                    JourneyAlarmNotifications.monitoring(this, snapshot), snapshot.config)
                if (activeConfig != snapshot.config || monitorJob?.isActive != true)
                    startMonitoring(snapshot.config)
                return START_STICKY
            }
            AlertState.RINGING -> {
                val (title, detail) = snapshot.status.split('\n', limit = 2)
                    .let { it.firstOrNull().orEmpty() to it.getOrNull(1).orEmpty() }
                startForegroundWithType(JourneyAlarmNotifications.MONITOR_ID,
                    JourneyAlarmNotifications.ringing(this, snapshot.config, title, detail), snapshot.config)
                startRinging()
                return START_STICKY
            }
            else -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
    }

    private fun startMonitoring(config: JourneyAlarmConfig) {
        monitorJob?.cancel()
        activeConfig = config
        monitorJob = scope.launch {
            var previous: PositionEngine.TrainPositionInput? = null
            while (isActive) {
                val snapshot = JourneyAlarmStore.read(this@JourneyAlarmService)
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
                val stillArmed = JourneyAlarmStore.read(this@JourneyAlarmService)
                if (stillArmed?.state != AlertState.ARMED || stillArmed.config != config) break
                when (decision) {
                    is JourneyAlarmPolicy.Decision.Fire -> {
                        fire(config, decision)
                        break
                    }
                    is JourneyAlarmPolicy.Decision.Missed -> {
                        markMissed(config, decision)
                        break
                    }
                    is JourneyAlarmPolicy.Decision.Waiting -> {
                        JourneyAlarmStore.update(this@JourneyAlarmService, AlertState.ARMED,
                            decision.detail, checkedAtMillis = System.currentTimeMillis())
                        val latest = JourneyAlarmStore.read(this@JourneyAlarmService)
                        if (latest != null) withContext(Dispatchers.Main) {
                            JourneyAlarmNotifications.ensureChannels(this@JourneyAlarmService)
                            androidx.core.app.NotificationManagerCompat.from(this@JourneyAlarmService)
                                .notify(JourneyAlarmNotifications.MONITOR_ID,
                                    JourneyAlarmNotifications.monitoring(this@JourneyAlarmService, latest))
                        }
                    }
                }
                delay(config.intervalMinutes * 60_000L)
            }
        }
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

    private suspend fun fire(config: JourneyAlarmConfig, event: JourneyAlarmPolicy.Decision.Fire) {
        val saved = JourneyAlarmStore.read(this)
        if (saved?.state != AlertState.ARMED || saved.config != config) return
        if (config.feedback == AlertFeedback.RING_UNTIL_STOPPED) {
            JourneyAlarmStore.update(this, AlertState.RINGING,
                "${event.title}\n${event.detail}", evidenceAtMillis = event.evidenceAtMillis)
            withContext(Dispatchers.Main) {
                startForegroundWithType(JourneyAlarmNotifications.MONITOR_ID,
                    JourneyAlarmNotifications.ringing(this@JourneyAlarmService,
                        config, event.title, event.detail), config)
                startRinging()
            }
        } else {
            JourneyAlarmStore.update(this, AlertState.FIRED,
                "${event.title} · ${event.detail}", evidenceAtMillis = event.evidenceAtMillis)
            withContext(Dispatchers.Main) {
                JourneyAlarmNotifications.triggered(this@JourneyAlarmService,
                    config, event.title, event.detail)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        Diary.get()?.log("ARRIVAL_ALERT", "${config.trainNo} fired: ${event.detail}")
    }

    private suspend fun markMissed(config: JourneyAlarmConfig, event: JourneyAlarmPolicy.Decision.Missed) {
        val saved = JourneyAlarmStore.read(this)
        if (saved?.state != AlertState.ARMED || saved.config != config) return
        JourneyAlarmStore.update(this, AlertState.MISSED, event.detail,
            evidenceAtMillis = event.evidenceAtMillis)
        withContext(Dispatchers.Main) {
            JourneyAlarmNotifications.triggered(this@JourneyAlarmService,
                config, "Earlier stop was not reported", event.detail, missed = true)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startForegroundWithType(id: Int, notification: android.app.Notification,
                                        config: JourneyAlarmConfig) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (config.trigger == AlertTrigger.PHONE_DISTANCE)
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

    private fun stopAlarmAndService() {
        monitorJob?.cancel()
        stopRinging()
        JourneyAlarmNotifications.cancel(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopRinging() {
        ringPlayer?.let { player -> runCatching { if (player.isPlaying) player.stop() }; runCatching { player.release() } }
        ringPlayer = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vibrator?.cancel()
        else @Suppress("DEPRECATION") vibrator?.cancel()
        vibrator = null
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        stopRinging()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_CANCEL = "in.opentrack.india.alarm.CANCEL"
        const val ACTION_STOP_RINGING = "in.opentrack.india.alarm.STOP_RINGING"
        const val ACTION_RECONFIGURE = "in.opentrack.india.alarm.RECONFIGURE"

        fun start(context: android.content.Context, config: JourneyAlarmConfig) {
            JourneyAlarmStore.arm(context, config)
            val intent = Intent(context, JourneyAlarmService::class.java).setAction(ACTION_RECONFIGURE)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (failure: RuntimeException) {
                JourneyAlarmStore.clear(context)
                runCatching { JourneyAlarmNotifications.cancel(context) }
                throw failure
            }
        }

        fun cancel(context: android.content.Context) {
            JourneyAlarmStore.clear(context)
            context.stopService(Intent(context, JourneyAlarmService::class.java))
            JourneyAlarmNotifications.cancel(context)
        }
    }
}
