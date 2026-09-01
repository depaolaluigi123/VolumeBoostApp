package com.volumeboost.app.audio

import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import com.volumeboost.app.settings.PreferencesRepository

/**
 * Owns one [LoudnessEnhancer] per audio session and applies the boost gain.
 *
 * The boost level is expressed in decibel (dB) and mapped to millibels for
 * [LoudnessEnhancer.setTargetGain] (1 dB = 100 mB). The effect must be
 * attached to a real playback session — not only to the deprecated global mix.
 *
 * Three discovery paths feed this manager (all call [attach] eventually):
 * 1. **Session 0 (global mix)** — best-effort in [setEnabled]; works on some OEMs,
 *    silently ignored on many modern Android builds.
 * 2. **AudioEffect broadcasts** — [attachSession] / [detachSession] when a media app
 *    announces its session (see [com.volumeboost.app.service.VolumeBoostService]).
 * 3. **Root AudioFlinger scan** — [syncActiveSessions] with IDs from
 *    [AudioSessionDiscovery] (YouTube, Deezer, boost mid-playback).
 */
class VolumeBoostManager {

    /** sessionId → active LoudnessEnhancer (includes session 0 when path 1 is used). */
    private val enhancers = HashMap<Int, LoudnessEnhancer>()

    /** Boost gain in dB. */
    private var level: Int = 0
    private var enabled: Boolean = false

    val isActive: Boolean
        @Synchronized get() = enabled && enhancers.isNotEmpty()

    /**
     * Enables or disables boost.
     *
     * When enabling, always tries **path 1** (attach to [GLOBAL_AUDIO_SESSION], 
     * where GLOBAL_AUDIO_SESSION = 0) and re-applies the current level to every 
     * session already in [enhancers] (from paths 2 and 3).
     */
    @Synchronized
    fun setEnabled(enabled: Boolean, levelDb: Int): Boolean {
        this.enabled = enabled
        this.level = levelDb.coerceIn(
            PreferencesRepository.MIN_BOOST_DB,
            PreferencesRepository.MAX_MAX_BOOST_DB
        )
        return if (enabled) {
            // Path 1: best-effort global mix (session 0).
            var ok = attach(GLOBAL_AUDIO_SESSION)
            // Re-apply gain on sessions already known from broadcasts / root discovery.
            for (session in enhancers.keys.toList()) {
                ok = applyTo(session) && ok
            }
            ok
        } else {
            releaseAll()
            true
        }
    }

    /** Updates the boost (in dB) applied to every currently attached session. */
    @Synchronized
    fun setLevel(levelDb: Int) {
        this.level = levelDb.coerceIn(
            PreferencesRepository.MIN_BOOST_DB,
            PreferencesRepository.MAX_MAX_BOOST_DB
        )
        if (!enabled) return
        for (session in enhancers.keys.toList()) {
            applyTo(session)
        }
    }

    /**
     * Path 2: called when a media app broadcasts
     * [android.media.audiofx.AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION].
     */
    @Synchronized
    fun attachSession(sessionId: Int) {
        if (!enabled) return
        attach(sessionId)
    }

    /**
     * Path 2: called when a media app broadcasts
     * [android.media.audiofx.AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION].
     */
    @Synchronized
    fun detachSession(sessionId: Int) {
        if (sessionId == GLOBAL_AUDIO_SESSION) return
        enhancers.remove(sessionId)?.let { release(it) }
    }

    /**
     * Path 3: reconcile with sessions found by [AudioSessionDiscovery] (root only).
     * Attaches new MEDIA sessions and releases ones that are no longer playing.
     * Session 0 (path 1) is never removed here.
     */
    @Synchronized
    fun syncActiveSessions(activeSessions: Set<Int>) {
        if (!enabled) return
        for (session in activeSessions) {
            attach(session)
        }
        val stale = enhancers.keys.filter { it != GLOBAL_AUDIO_SESSION && it !in activeSessions }
        for (session in stale) {
            enhancers.remove(session)?.let { release(it) }
        }
    }

    @Synchronized
    fun release() = releaseAll()

    /** Creates (or refreshes) a [LoudnessEnhancer] for [sessionId] and applies [level]. */
    private fun attach(sessionId: Int): Boolean {
        if (enhancers.containsKey(sessionId)) {
            return applyTo(sessionId)
        }
        return try {
            val effect = LoudnessEnhancer(sessionId)
            enhancers[sessionId] = effect
            applyTo(sessionId)
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to attach to session=$sessionId", error)
            false
        }
    }

    private fun applyTo(sessionId: Int): Boolean {
        val effect = enhancers[sessionId] ?: return false
        return try {
            effect.setTargetGain(dbToMillibels(level))
            effect.enabled = level > 0
            true
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to apply level=$level to session=$sessionId", error)
            enhancers.remove(sessionId)
            release(effect)
            false
        }
    }

    private fun releaseAll() {
        for (effect in enhancers.values) {
            release(effect)
        }
        enhancers.clear()
    }

    private fun release(effect: LoudnessEnhancer) {
        try {
            effect.enabled = false
            effect.release()
        } catch (_: Throwable) {
            // Best-effort cleanup.
        }
    }

    companion object {
        private const val TAG = "VolumeBoostManager"

        /**
         * Path 1 target: historical "global output mix" session.
         * Deprecated; still attempted because some OEM builds still honour it.
         */
        private const val GLOBAL_AUDIO_SESSION = 0

        /** [LoudnessEnhancer.setTargetGain] takes millibels: 1 dB = 100 mB. */
        fun dbToMillibels(db: Int): Int = db * 100
    }
}
