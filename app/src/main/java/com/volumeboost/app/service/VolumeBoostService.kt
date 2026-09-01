package com.volumeboost.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import com.volumeboost.app.R
import com.volumeboost.app.VolumeBoostApplication
import com.volumeboost.app.audio.AudioSessionDiscovery
import com.volumeboost.app.audio.VolumeBoostManager
import com.volumeboost.app.settings.LocaleManager
import com.volumeboost.app.settings.PreferencesRepository
import com.volumeboost.app.state.BoostStateStore
import com.volumeboost.app.ui.MainActivity

/**
 * Reads the single in-process [BoostStateStore] for effect + notification snapshots.
 *
 * Two optional shade UIs (chosen in settings):
 * - Classic RemoteViews (`notification_boost.xml`) with −1 / +1 / checkbox
 * - MediaStyle seek bar (YouTube/Spotify-style), dB mapped via [PlaybackStateCompat.ACTION_SEEK_TO]
 *
 * Either, both, or neither may be shown. While the foreground service must run with boost on
 * and both are off, a minimal ongoing notification is posted (Android FGS requirement).
 */
class VolumeBoostService : Service() {

    private val boostManager = VolumeBoostManager()
    private val prefs: PreferencesRepository
        get() = (application as VolumeBoostApplication).preferences
    private val boostStore: BoostStateStore
        get() = (application as VolumeBoostApplication).boostStore

    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var discoveryThread: HandlerThread? = null
    private var discoveryHandler: Handler? = null
    private var discoveryActive = false

    /** True while the foreground notification / service should stay up. */
    private var controlsActive = false

    /** Last enabled flag actually applied to the effect / FGS. */
    private var appliedEnabled = false

    /** Ignore store callbacks while tearing down (ACTION_STOP). */
    private var ignoringStore = false

    /** Coalesce rapid store updates onto the latest snapshot. */
    private var applyScheduled = false
    private var pendingHardStop = false

    /**
     * When true, the next apply also pushes a notification snapshot.
     * Level-only slider drags leave this false so the shade is not flooded with intermediates.
     */
    private var pendingPublishNotification = false

    private var unsubscribe: (() -> Unit)? = null

    /** MediaStyle scrubber + transport mapped to boost controls. */
    private var mediaSession: MediaSessionCompat? = null

    private val mediaCallback = object : MediaSessionCompat.Callback() {
        override fun onSeekTo(pos: Long) {
            val max = boostStore.snapshot().maxBoostDb.coerceAtLeast(1)
            val db = ((pos + MS_PER_DB / 2) / MS_PER_DB).toInt().coerceIn(0, max)
            boostStore.setBoostDb(db)
            publishControls(boostStore.snapshot())
        }

        override fun onSkipToNext() {
            boostStore.adjustBoostDb(1)
            publishControls(boostStore.snapshot())
        }

        override fun onSkipToPrevious() {
            boostStore.adjustBoostDb(-1)
            publishControls(boostStore.snapshot())
        }

        override fun onPlay() {
            boostStore.setEnabled(true)
            VolumeBoostService.sync(this@VolumeBoostService)
        }

        override fun onPause() {
            // Soft-disable: keep the ongoing controls visible.
            boostStore.setEnabled(false)
            publishControls(boostStore.snapshot())
        }

    }

    private val applyLatestRunnable = Runnable {
        applyScheduled = false
        val hardStop = pendingHardStop
        val publishNotification = pendingPublishNotification
        pendingHardStop = false
        pendingPublishNotification = false
        applyState(
            boostStore.snapshot(),
            stopIfDisabled = hardStop,
            publishNotification = publishNotification
        )
    }

    private val discoveryRunnable = object : Runnable {
        override fun run() {
            if (AudioSessionDiscovery.isRootAvailable()) {
                val sessions = AudioSessionDiscovery.discoverMediaSessions(Process.myPid())
                boostManager.syncActiveSessions(sessions)
            }
            if (discoveryActive) {
                discoveryHandler?.postDelayed(this, DISCOVERY_INTERVAL_MS)
            }
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            if (discoveryActive) scheduleImmediateDiscovery()
        }
    }
    private var playbackCallbackRegistered = false

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, NO_SESSION)
            if (session == NO_SESSION) return
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION ->
                    boostManager.attachSession(session)
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION ->
                    boostManager.detachSession(session)
            }
        }
    }
    private var sessionReceiverRegistered = false

    private val boostObserver = BoostStateStore.Observer {
        if (ignoringStore) return@Observer
        scheduleApply(hardStopWhenDisabled = false, publishNotification = false)
    }

    private fun scheduleApply(hardStopWhenDisabled: Boolean, publishNotification: Boolean) {
        if (hardStopWhenDisabled) pendingHardStop = true
        if (publishNotification) pendingPublishNotification = true
        if (applyScheduled) return
        applyScheduled = true
        mainHandler.post(applyLatestRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val sessionFilter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        ContextCompat.registerReceiver(
            this,
            sessionReceiver,
            sessionFilter,
            ContextCompat.RECEIVER_EXPORTED
        )
        sessionReceiverRegistered = true

        unsubscribe = boostStore.observe(boostObserver)

        discoveryThread = HandlerThread("boost-discovery").also { thread ->
            thread.start()
            discoveryHandler = Handler(thread.looper)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                mainHandler.removeCallbacks(applyLatestRunnable)
                applyScheduled = false
                pendingHardStop = false
                pendingPublishNotification = false
                ignoringStore = true
                try {
                    if (boostStore.snapshot().enabled) {
                        boostStore.setEnabled(false)
                    }
                    tearDownAndStop()
                } finally {
                    ignoringStore = false
                }
                return START_NOT_STICKY
            }
            ACTION_SET_ENABLED -> {
                val enabled = if (intent.hasExtra(RemoteViews.EXTRA_CHECKED)) {
                    intent.getBooleanExtra(RemoteViews.EXTRA_CHECKED, false)
                } else if (intent.hasExtra(EXTRA_ENABLED)) {
                    intent.getBooleanExtra(EXTRA_ENABLED, !boostStore.snapshot().enabled)
                } else {
                    !boostStore.snapshot().enabled
                }
                pendingPublishNotification = true
                boostStore.setEnabled(enabled)
                if (enabled) {
                    // Ensure FGS path runs if we were soft-disabled.
                    scheduleApply(hardStopWhenDisabled = false, publishNotification = true)
                }
                return START_STICKY
            }
            ACTION_ADJUST_DB -> {
                val delta = intent.getIntExtra(EXTRA_DELTA_DB, 0)
                pendingPublishNotification = true
                boostStore.adjustBoostDb(delta)
                return START_STICKY
            }
            ACTION_PUBLISH_NOTIFICATION -> {
                if (controlsActive || boostStore.snapshot().enabled) {
                    val state = boostStore.snapshot()
                    if (state.enabled && (!appliedEnabled || !controlsActive)) {
                        scheduleApply(hardStopWhenDisabled = false, publishNotification = true)
                    } else {
                        controlsActive = true
                        publishControls(state)
                    }
                    return START_STICKY
                }
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                scheduleApply(hardStopWhenDisabled = true, publishNotification = true)
                return if (boostStore.snapshot().enabled || controlsActive) {
                    START_STICKY
                } else {
                    START_NOT_STICKY
                }
            }
        }
    }

    /**
     * @param stopIfDisabled when true and boost is off, tear down the service (app toggle off).
     *   When false, keep the notification so the user can re-enable from the shade.
     * @param publishNotification when false, only the audio effect tracks intermediate dB.
     */
    private fun applyState(
        state: BoostStateStore.State,
        stopIfDisabled: Boolean,
        publishNotification: Boolean
    ) {
        if (state.enabled) {
            val needFullEnable = !appliedEnabled || !controlsActive
            if (needFullEnable) {
                val ok = boostManager.setEnabled(true, state.boostDb)
                if (!ok) {
                    ignoringStore = true
                    try {
                        boostStore.setEnabled(false)
                    } finally {
                        ignoringStore = false
                    }
                    boostManager.release()
                    stopDiscovery()
                    appliedEnabled = false
                    controlsActive = true
                    publishControls(boostStore.snapshot())
                    return
                }
                startDiscovery()
                appliedEnabled = true
                controlsActive = true
                publishControls(state)
            } else {
                boostManager.setLevel(state.boostDb)
                if (prefs.showMediaNotification) {
                    syncMediaSession(state)
                }
                if (publishNotification) {
                    publishControls(state)
                }
            }
        } else {
            stopDiscovery()
            boostManager.release()
            appliedEnabled = false
            val anyControls =
                prefs.showClassicNotification || prefs.showMediaNotification
            if (stopIfDisabled || !anyControls) {
                // No shade UI to re-enable from — tear down fully.
                tearDownAndStop()
            } else {
                controlsActive = true
                publishControls(state)
            }
        }
    }

    /**
     * Posts the notifications selected in settings and keeps one of them as the FGS notice.
     */
    private fun publishControls(state: BoostStateStore.State) {
        ensureChannel()
        val showClassic = prefs.showClassicNotification
        val showMedia = prefs.showMediaNotification

        if (showMedia) {
            syncMediaSession(state)
        } else {
            releaseMediaSession()
        }

        val classic = if (showClassic) buildClassicNotification(state) else null
        val media = if (showMedia) buildMediaNotification(state) else null
        val minimal = if (!showClassic && !showMedia) buildMinimalNotification(state) else null

        // One notification must back the foreground service.
        val (fgsId, fgsNotification) = when {
            media != null -> ID_MEDIA to media
            classic != null -> ID_CLASSIC to classic
            else -> ID_MINIMAL to (minimal ?: buildMinimalNotification(state))
        }
        startForegroundNotification(fgsId, fgsNotification)

        // Secondary style (when both are enabled).
        if (showClassic && fgsId != ID_CLASSIC && classic != null) {
            notificationManager.notify(ID_CLASSIC, classic)
        }
        if (showMedia && fgsId != ID_MEDIA && media != null) {
            notificationManager.notify(ID_MEDIA, media)
        }

        // Remove styles the user turned off.
        if (!showClassic) notificationManager.cancel(ID_CLASSIC)
        if (!showMedia) notificationManager.cancel(ID_MEDIA)
        if (showClassic || showMedia) notificationManager.cancel(ID_MINIMAL)
    }

    private fun startForegroundNotification(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                id,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(id, notification)
        }
    }

    private fun tearDownAndStop() {
        controlsActive = false
        appliedEnabled = false
        stopDiscovery()
        boostManager.release()
        releaseMediaSession()
        notificationManager.cancel(ID_CLASSIC)
        notificationManager.cancel(ID_MEDIA)
        notificationManager.cancel(ID_MINIMAL)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startDiscovery() {
        discoveryActive = true
        if (!playbackCallbackRegistered) {
            audioManager.registerAudioPlaybackCallback(playbackCallback, discoveryHandler)
            playbackCallbackRegistered = true
        }
        scheduleImmediateDiscovery()
    }

    private fun scheduleImmediateDiscovery() {
        discoveryHandler?.apply {
            removeCallbacks(discoveryRunnable)
            post(discoveryRunnable)
        }
    }

    private fun stopDiscovery() {
        discoveryActive = false
        if (playbackCallbackRegistered) {
            audioManager.unregisterAudioPlaybackCallback(playbackCallback)
            playbackCallbackRegistered = false
        }
        discoveryHandler?.removeCallbacks(discoveryRunnable)
    }

    override fun onDestroy() {
        unsubscribe?.invoke()
        unsubscribe = null
        mainHandler.removeCallbacks(applyLatestRunnable)
        applyScheduled = false
        stopDiscovery()
        discoveryThread?.quitSafely()
        discoveryThread = null
        discoveryHandler = null
        if (sessionReceiverRegistered) {
            try {
                unregisterReceiver(sessionReceiver)
            } catch (_: Throwable) {
            }
            sessionReceiverRegistered = false
        }
        boostManager.release()
        releaseMediaSession()
        super.onDestroy()
    }

    private fun contentPendingIntent(): PendingIntent {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun markOngoing(notification: Notification): Notification {
        notification.flags = notification.flags or
            Notification.FLAG_ONGOING_EVENT or
            Notification.FLAG_NO_CLEAR
        return notification
    }

    private fun buildClassicNotification(state: BoostStateStore.State): Notification {
        val localized = LocaleManager.wrapContext(this, prefs)
        val summary = if (state.enabled) {
            localized.getString(R.string.notification_text, state.boostDb)
        } else {
            localized.getString(R.string.notification_text_off)
        }
        val remote = buildClassicRemoteViews(localized, state)
        return markOngoing(
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(localized.getString(R.string.notification_title))
                .setContentText(summary)
                .setContentIntent(contentPendingIntent())
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(remote)
                .setCustomBigContentView(remote)
                .build()
        )
    }

    private fun buildClassicRemoteViews(
        localized: Context,
        state: BoostStateStore.State
    ): RemoteViews {
        val views = RemoteViews(packageName, R.layout.notification_boost)
        val maxDb = state.maxBoostDb
        val boostDb = state.boostDb
        val enabled = state.enabled
        views.setTextViewText(
            R.id.notifTitle,
            localized.getString(R.string.notification_title)
        )
        views.setTextViewText(
            R.id.notifLevel,
            localized.getString(R.string.notification_text, boostDb)
        )
        views.setProgressBar(
            R.id.notifProgress,
            maxDb.coerceAtLeast(1),
            boostDb.coerceIn(0, maxDb),
            false
        )
        views.setTextViewText(R.id.notifMinus, localized.getString(R.string.notification_decrease))
        views.setTextViewText(R.id.notifPlus, localized.getString(R.string.notification_increase))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setCompoundButtonChecked(R.id.notifEnable, enabled)
            views.setOnCheckedChangeResponse(
                R.id.notifEnable,
                RemoteViews.RemoteResponse.fromPendingIntent(
                    controlServicePending(ACTION_SET_ENABLED, requestCode = 10, mutable = true)
                )
            )
        } else {
            views.setBoolean(R.id.notifEnable, "setChecked", enabled)
            views.setOnClickPendingIntent(
                R.id.notifEnable,
                controlServicePending(ACTION_SET_ENABLED, requestCode = 10) {
                    putExtra(EXTRA_ENABLED, !enabled)
                }
            )
        }

        views.setOnClickPendingIntent(
            R.id.notifMinus,
            controlServicePending(ACTION_ADJUST_DB, requestCode = 12) {
                putExtra(EXTRA_DELTA_DB, -1)
            }
        )
        views.setOnClickPendingIntent(
            R.id.notifPlus,
            controlServicePending(ACTION_ADJUST_DB, requestCode = 13) {
                putExtra(EXTRA_DELTA_DB, 1)
            }
        )
        return views
    }

    private fun buildMediaNotification(state: BoostStateStore.State): Notification {
        val localized = LocaleManager.wrapContext(this, prefs)
        val summary = if (state.enabled) {
            localized.getString(R.string.notification_text, state.boostDb)
        } else {
            localized.getString(R.string.notification_text_off)
        }
        val toggleLabel = if (state.enabled) {
            localized.getString(R.string.boost_enabled)
        } else {
            localized.getString(R.string.boost_disabled)
        }
        val session = mediaSession
            ?: error("Media session must exist before building the media notification")

        return markOngoing(
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(localized.getString(R.string.notification_title))
                .setContentText(summary)
                .setSubText(localized.getString(R.string.notification_slider_hint, state.maxBoostDb))
                .setContentIntent(contentPendingIntent())
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setStyle(
                    MediaNotificationCompat.MediaStyle()
                        .setMediaSession(session.sessionToken)
                        .setShowActionsInCompactView(0, 1, 2)
                )
                .addAction(
                    R.drawable.ic_notification,
                    localized.getString(R.string.notification_decrease),
                    controlServicePending(ACTION_ADJUST_DB, 22) { putExtra(EXTRA_DELTA_DB, -1) }
                )
                .addAction(
                    R.drawable.ic_notification,
                    toggleLabel,
                    controlServicePending(ACTION_SET_ENABLED, 20) {
                        putExtra(EXTRA_ENABLED, !state.enabled)
                    }
                )
                .addAction(
                    R.drawable.ic_notification,
                    localized.getString(R.string.notification_increase),
                    controlServicePending(ACTION_ADJUST_DB, 23) { putExtra(EXTRA_DELTA_DB, 1) }
                )
                .build()
        )
    }

    /** Required FGS placeholder when the user disables both control notifications. */
    private fun buildMinimalNotification(state: BoostStateStore.State): Notification {
        val localized = LocaleManager.wrapContext(this, prefs)
        val text = if (state.enabled) {
            localized.getString(R.string.notification_minimal_text)
        } else {
            localized.getString(R.string.notification_text_off)
        }
        return markOngoing(
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(localized.getString(R.string.notification_title))
                .setContentText(text)
                .setContentIntent(contentPendingIntent())
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        )
    }

    /**
     * YouTube/Spotify-style scrubber: duration = max dB, position = current dB
     * (scaled to ms so SystemUI draws a proper seek bar). Speed is 0 so it does not “play”.
     */
    private fun syncMediaSession(state: BoostStateStore.State) {
        val max = state.maxBoostDb.coerceAtLeast(1)
        val current = state.boostDb.coerceIn(0, max)
        val durationMs = max * MS_PER_DB
        val positionMs = current * MS_PER_DB

        val session = mediaSession ?: MediaSessionCompat(this, MEDIA_SESSION_TAG).also { created ->
            created.setCallback(mediaCallback, mainHandler)
            created.isActive = true
            mediaSession = created
        }

        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(
                    MediaMetadataCompat.METADATA_KEY_TITLE,
                    getString(R.string.notification_title)
                )
                .putString(
                    MediaMetadataCompat.METADATA_KEY_ARTIST,
                    getString(R.string.notification_text, current)
                )
                .putString(
                    MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
                    getString(R.string.notification_slider_hint, max)
                )
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                .build()
        )

        val playbackState = if (state.enabled) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(MEDIA_ACTIONS)
                .setState(
                    playbackState,
                    positionMs,
                    /* playbackSpeed = */ 0f,
                    SystemClock.elapsedRealtime()
                )
                .build()
        )
    }

    private fun releaseMediaSession() {
        mediaSession?.apply {
            setCallback(null)
            isActive = false
            release()
        }
        mediaSession = null
    }

    private fun controlServicePending(
        action: String,
        requestCode: Int,
        mutable: Boolean = false,
        configure: (Intent.() -> Unit)? = null
    ): PendingIntent {
        val intent = Intent(this, VolumeBoostService::class.java).setAction(action)
        configure?.invoke(intent)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, requestCode, intent, flags)
        } else {
            PendingIntent.getService(this, requestCode, intent, flags)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val localized = LocaleManager.wrapContext(this, prefs)
        val channel = NotificationChannel(
            CHANNEL_ID,
            localized.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = localized.getString(R.string.notification_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_SYNC = "com.volumeboost.app.action.SYNC"
        const val ACTION_STOP = "com.volumeboost.app.action.STOP"
        const val ACTION_SET_ENABLED = "com.volumeboost.app.action.SET_ENABLED"
        const val ACTION_ADJUST_DB = "com.volumeboost.app.action.ADJUST_DB"
        const val ACTION_PUBLISH_NOTIFICATION = "com.volumeboost.app.action.PUBLISH_NOTIFICATION"
        const val EXTRA_ENABLED = "extra_enabled"
        const val EXTRA_DELTA_DB = "extra_delta_db"

        private const val CHANNEL_ID = "volume_boost_controls_v4"
        private const val ID_CLASSIC = 1001
        private const val ID_MEDIA = 1002
        private const val ID_MINIMAL = 1003
        private const val NO_SESSION = Int.MIN_VALUE
        private const val DISCOVERY_INTERVAL_MS = 2000L
        private const val MEDIA_SESSION_TAG = "VolumeBoostControls"

        /** 1 dB ↔ 1000 ms on the MediaStyle scrubber (SystemUI shows mm:ss ≈ dB). */
        private const val MS_PER_DB = 1000L

        private const val MEDIA_ACTIONS =
            PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE

        fun sync(context: Context) {
            val store = (context.applicationContext as VolumeBoostApplication).boostStore
            val intent = Intent(context, VolumeBoostService::class.java).apply {
                action = if (store.snapshot().enabled) ACTION_SYNC else ACTION_STOP
            }
            if (store.snapshot().enabled) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun publishNotification(context: Context) {
            val intent = Intent(context, VolumeBoostService::class.java).apply {
                action = ACTION_PUBLISH_NOTIFICATION
            }
            context.startService(intent)
        }
    }
}
