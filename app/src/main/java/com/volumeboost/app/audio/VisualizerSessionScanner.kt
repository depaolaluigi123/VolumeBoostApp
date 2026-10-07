package com.volumeboost.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.Visualizer
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Mix level minus session level (mB) while the session was heard, or null when the mix could
 * not be metered. Positive by about the boost when session 0 already boosts that session.
 */
typealias GlobalGainMb = Int?

/**
 * Path 2 without root: finds the audio sessions that are producing sound.
 *
 * Measured on LineageOS 20 / Android 13: an effect on the global session 0 is ignored, while
 * the same [android.media.audiofx.LoudnessEnhancer] on the player's real session works, from
 * any app. Android hides that session id ([AudioPlaybackConfiguration] reports 0), but:
 * - AudioFlinger allocates session ids from one sequential counter (9, 17, 25, … step 8),
 *   and [AudioManager.generateAudioSessionId] returns the next one. Every existing session is
 *   therefore below it.
 * - A [Visualizer] can be attached to any session id, and its peak/RMS meter tells whether
 *   audio is flowing there. It needs only the RECORD_AUDIO runtime permission (one tap in the
 *   standard Android dialog); the microphone is never opened, so no privacy indicator shows.
 *
 * Each batch also meters the output mix (session 0) at the same moment, so the scan reports how
 * much louder the mix is than each session it heard ([GlobalGainMb]): about the boost when the
 * global enhancer on session 0 already reaches that player, about 0 (or less) when it does not.
 *
 * Opening a meter costs ~10 ms of audioserver time, and long-running phones have thousands of
 * past ids, so a scan meters, in batches: the sessions already found, the newest ids (where a
 * player that just started lands), then the next slice of a deep pass over older ids (a player
 * created long ago, e.g. resumed). The deep pass resumes where the previous scan stopped.
 * Blocking (up to a few seconds): call from a single background thread.
 */
object VisualizerSessionScanner {

    private const val TAG = "VisualizerScanner"

    /** AudioFlinger session ids: `base | AUDIO_UNIQUE_ID_USE_SESSION`, base a multiple of 8. */
    private const val SESSION_STEP = 8
    private const val FIRST_SESSION = 9

    /**
     * Visualizers metered at once, and how long they listen. The audio policy caps effect
     * memory for the whole system (~400 Visualizers measured): stay far below it.
     */
    private const val BATCH_SIZE = 64
    private const val MIN_BATCH_SIZE = 8
    private const val LISTEN_MS = 250L

    /** Newest ids metered by every scan. */
    private const val NEWEST_IDS = 128

    /** Older ids metered per scan by the deep pass: bounds a scan to ~6 s. */
    private const val DEEP_IDS = 384

    /** [Visualizer.MeasurementPeakRms] value for digital silence (mB). */
    private const val SILENCE_MB = -9600

    /** The global output mix: metered alongside each batch. */
    private const val MIX_SESSION = 0

    // Deep pass state: advanced by the scanning thread, reset from the main thread too.
    /** Next (older) id the deep pass meters; 0 = start right below the newest ids. */
    @Volatile private var deepCursor = 0

    /** The deep pass reached the oldest id without finding everything: wait for a reset. */
    @Volatile private var deepDone = false

    // AudioAttributes usages that are alerts or calls, never media (values are stable API).
    private val ALERT_USAGES = setOf(
        AudioAttributes.USAGE_VOICE_COMMUNICATION,
        AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
        AudioAttributes.USAGE_ALARM,
        AudioAttributes.USAGE_NOTIFICATION,
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
        7, 8, 9, // deprecated NOTIFICATION_COMMUNICATION_* usages
        AudioAttributes.USAGE_NOTIFICATION_EVENT,
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    )

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** What other apps are playing right now, from the (anonymized) playback configurations. */
    data class Playback(val mediaPlayers: Int, val alertPlaying: Boolean)

    fun playback(audioManager: AudioManager): Playback {
        var media = 0
        var alert = false
        for (config in audioManager.activePlaybackConfigurations) {
            if (!isStarted(config)) continue
            val usage = config.audioAttributes.usage
            when {
                usage == AudioAttributes.USAGE_MEDIA -> media++
                usage in ALERT_USAGES -> alert = true
            }
        }
        // A player that does not report its state still shows up as music activity.
        if (media == 0 && audioManager.isMusicActive) media = 1
        return Playback(media, alert)
    }

    /**
     * The player state is not public API; [AudioPlaybackConfiguration.toString] prints it as
     * `state:started`. If a build formats it differently, count the player as started.
     */
    private fun isStarted(config: AudioPlaybackConfiguration): Boolean {
        val text = config.toString()
        return !text.contains("state:") || text.contains("state:started")
    }

    /** Restarts the deep pass from the newest ids (playback changed: a new player to find). */
    fun resetDeepPass() {
        deepCursor = 0
        deepDone = false
    }

    /**
     * Meters [priority] first (sessions already boosted), then the newest ids, then the next
     * slice of the deep pass, and returns every session with sound with its [GlobalGainMb].
     * Stops early once [wanted] sessions were heard.
     *
     * @return null when no Visualizer could be created (permission revoked).
     */
    fun scan(
        audioManager: AudioManager,
        priority: Collection<Int>,
        wanted: Int
    ): Map<Int, GlobalGainMb>? {
        // Every id allocated so far is below the one we get now.
        val newest = audioManager.generateAudioSessionId() - SESSION_STEP
        val candidates = planCandidates(newest, priority)

        val heard = LinkedHashMap<Int, GlobalGainMb>()
        val queue = ArrayDeque(candidates)
        val retried = HashSet<Int>()
        var batchSize = BATCH_SIZE
        var created = 0
        while (queue.isNotEmpty() && heard.size < wanted) {
            val meters = ArrayList<Pair<Int, Visualizer>>()
            val refused = ArrayList<Int>()
            repeat(minOf(batchSize, queue.size)) {
                val id = queue.removeFirst()
                when (val result = openMeter(id)) {
                    is Meter.Open -> meters.add(id to result.visualizer)
                    Meter.NotEnabled -> refused.add(id)
                    Meter.NotCreated -> Unit
                }
            }
            created += meters.size
            // Some builds (MIUI) refuse to enable part of a large batch: meter those ids again,
            // once, in smaller batches, so the player's session is not skipped.
            if (refused.isNotEmpty()) {
                batchSize = (batchSize / 2).coerceAtLeast(MIN_BATCH_SIZE)
                refused.filter { retried.add(it) }.asReversed().forEach { queue.addFirst(it) }
            }
            if (meters.isEmpty()) continue
            // Created after the global enhancer, so it sits after it in the session 0 chain
            // and reads the boosted mix.
            val mixMeter = (openMeter(MIX_SESSION) as? Meter.Open)?.visualizer
            try {
                Thread.sleep(LISTEN_MS)
                val measurement = Visualizer.MeasurementPeakRms()
                val mixRms = mixMeter?.let { rmsOf(it, measurement) }
                for ((id, meter) in meters) {
                    val rms = rmsOf(meter, measurement) ?: continue
                    heard[id] = mixRms?.let { it - rms }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return heard
            } finally {
                meters.forEach { (_, meter) -> meter.release() }
                mixMeter?.release()
            }
        }
        if (created == 0) return null
        if (heard.size >= wanted) resetDeepPass()
        Log.d(TAG, "metered $created ids up to $newest: heard $heard")
        return heard
    }

    /**
     * Ids to meter, in order: [priority], the [NEWEST_IDS] newest ids from [newest] down, then
     * the next [DEEP_IDS] ids of the deep pass (advancing it).
     */
    internal fun planCandidates(newest: Int, priority: Collection<Int>): Set<Int> {
        val candidates = LinkedHashSet<Int>()
        priority.filterTo(candidates) { it >= FIRST_SESSION }
        val belowNewest = addIds(candidates, newest, NEWEST_IDS)
        if (!deepDone) {
            // Ids that left the newest window since the last scan were metered while in it.
            val from = if (deepCursor in FIRST_SESSION until belowNewest) deepCursor else belowNewest
            deepCursor = addIds(candidates, from, DEEP_IDS)
            deepDone = deepCursor < FIRST_SESSION
        }
        return candidates
    }

    /** Adds up to [count] session ids from [from] downwards; returns the next older id. */
    private fun addIds(into: MutableSet<Int>, from: Int, count: Int): Int {
        var session = from
        repeat(count) {
            if (session < FIRST_SESSION) return session
            into.add(session)
            session -= SESSION_STEP
        }
        return session
    }

    private sealed interface Meter {
        class Open(val visualizer: Visualizer) : Meter
        /** Not created: permission missing or no effect slot. */
        object NotCreated : Meter
        /** Created but not enabled: the build refused to enable it (e.g. too many at once). */
        object NotEnabled : Meter
    }

    private fun openMeter(session: Int): Meter {
        val visualizer = try {
            Visualizer(session)
        } catch (error: Throwable) {
            // RuntimeException / UnsupportedOperationException: permission missing or no slot.
            Log.w(TAG, "cannot meter session $session: ${error.message}")
            return Meter.NotCreated
        }
        // setEnabled() reports failure with a status code, not an exception: a meter left
        // disabled throws IllegalStateException when read.
        val status = try {
            visualizer.measurementMode = Visualizer.MEASUREMENT_MODE_PEAK_RMS
            visualizer.setEnabled(true)
        } catch (error: RuntimeException) {
            Log.w(TAG, "cannot enable meter on session $session: ${error.message}")
            Visualizer.ERROR
        }
        if (status == Visualizer.SUCCESS) return Meter.Open(visualizer)
        Log.w(TAG, "meter on session $session not enabled: status $status")
        visualizer.release()
        return Meter.NotEnabled
    }

    /** RMS level (mB) of [meter], or null when it heard silence or could not be read. */
    private fun rmsOf(meter: Visualizer, measurement: Visualizer.MeasurementPeakRms): Int? =
        try {
            if (meter.getMeasurementPeakRms(measurement) == Visualizer.SUCCESS &&
                measurement.mPeak > SILENCE_MB
            ) {
                measurement.mRms
            } else {
                null
            }
        } catch (error: IllegalStateException) {
            // Disabled meanwhile (e.g. the session moved to another output).
            Log.w(TAG, "cannot read meter: ${error.message}")
            null
        }

    /**
     * Whether the global enhancer already boosts a session, from its [GlobalGainMb] and the
     * boost in dB. Above half the boost (at most 3 dB, so a limiter squeezing loud audio still
     * counts) the session is covered. Unknown counts as covered: a missing boost is safer than a
     * doubled one.
     */
    internal fun isCoveredByGlobal(globalGainMb: GlobalGainMb, boostDb: Int): Boolean {
        if (globalGainMb == null) return true
        val thresholdMb = minOf(boostDb * 50, 300)
        return globalGainMb >= thresholdMb
    }
}
