package com.volumeboost.app.audio

import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import com.volumeboost.app.settings.PreferencesRepository

/**
 * Owns one [LoudnessEnhancer] per audio session and applies the boost gain.
 *
 * The boost level is expressed in decibel (dB) and mapped to millibels for
 * [LoudnessEnhancer.setTargetGain] (1 dB = 100 mB).
 *
 * Three paths feed this manager:
 * 1. **Global session 0** — attached as soon as boost is on, so audio that starts later is
 *    boosted at once on builds that honour it (MIUI 14 boosts YouTube this way). Many builds
 *    ignore it (measured: 0 dB on LineageOS 20), and on some it reaches only part of the
 *    players (MIUI: not VLC, which plays on another output).
 * 2. **AudioEffect broadcasts** — [attachSession] / [detachSession] when a media app
 *    announces its session (see [com.volumeboost.app.service.VolumeBoostService]).
 * 3. **Session discovery** — [syncActiveSessions] with IDs read as root by
 *    [AudioSessionDiscovery], or [addHeardSessions] with IDs from [VisualizerSessionScanner]
 *    (no root). Reaches YouTube, Deezer, boost mid-playback.
 *
 * Session 0 and a session enhancer must not both boost the same player: once a track goes
 * through a session effect chain its audio also passes the session 0 chain, so the gain was
 * applied twice (measured: +20 dB for a +10 dB setting). How session 0 is combined with real
 * sessions is the [GlobalMode], chosen by the service from what it can measure.
 */
class VolumeBoostManager {

    /** How the global enhancer (session 0) is used next to real sessions. */
    enum class GlobalMode {
        /** Not used: root discovery finds every media session exactly. */
        OFF,

        /**
         * Always attached. Each real session is measured ([addHeardSessions]): it gets the
         * boost only if session 0 does not already reach it, else 0 dB. Until measured, a
         * session gets 0 dB (a missing boost is safer than a doubled one).
         */
        ALWAYS,

        /** Attached only while no real session is: nothing can be measured. */
        EXCLUSIVE
    }

    /** sessionId → active LoudnessEnhancer (includes session 0 while it is used). */
    private val enhancers = HashMap<Int, LoudnessEnhancer>()

    /** Real sessions that session 0 already boosts: their own enhancer stays at 0 dB. */
    private val coveredByGlobal = HashSet<Int>()

    /** Boost gain in dB. */
    private var level: Int = 0
    private var enabled: Boolean = false

    private var globalMode = GlobalMode.EXCLUSIVE

    /** Sessions attached by [addHeardSessions], least recently heard first. */
    private val heardSessions = LinkedHashSet<Int>()

    /** Broadcast sessions assumed covered (0 dB) until a scan measures them: id → uptime (ms). */
    private val unmeasured = HashMap<Int, Long>()

    /** Session 0 was refused since boost was enabled: do not retry on every change. */
    private var globalRefused = false

    val isActive: Boolean
        @Synchronized get() = enabled && enhancers.isNotEmpty()

    /**
     * Enables or disables boost. When enabling, attaches session 0 as the [GlobalMode] allows
     * and re-applies the current level to every session already in [enhancers].
     */
    @Synchronized
    fun setEnabled(enabled: Boolean, levelDb: Int) {
        this.enabled = enabled
        this.level = levelDb.coerceIn(
            PreferencesRepository.MIN_BOOST_DB,
            PreferencesRepository.MAX_MAX_BOOST_DB
        )
        if (enabled) {
            globalRefused = false
            // Re-apply gain on sessions already known from broadcasts / discovery.
            for (session in enhancers.keys.toList()) {
                applyTo(session)
            }
            updateGlobalSession()
        } else {
            releaseAll()
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

    /** Switches how session 0 is combined with real sessions (see [GlobalMode]). */
    @Synchronized
    fun setGlobalMode(mode: GlobalMode) {
        if (mode == globalMode) return
        globalMode = mode
        // Coverage is only measured in ALWAYS; elsewhere real sessions get the full boost.
        if (mode != GlobalMode.ALWAYS) {
            coveredByGlobal.clear()
            unmeasured.clear()
        }
        if (!enabled) return
        for (session in enhancers.keys.toList()) {
            if (session != GLOBAL_AUDIO_SESSION) applyTo(session)
        }
        updateGlobalSession()
    }

    /**
     * Path 2: called when a media app broadcasts
     * [android.media.audiofx.AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION].
     */
    @Synchronized
    fun attachSession(sessionId: Int) {
        if (!enabled || sessionId == GLOBAL_AUDIO_SESSION) return
        // Not measured yet: 0 dB until the next scan, while session 0 is always on.
        if (globalMode == GlobalMode.ALWAYS && !enhancers.containsKey(sessionId) &&
            enhancers.containsKey(GLOBAL_AUDIO_SESSION)
        ) {
            coveredByGlobal.add(sessionId)
            unmeasured[sessionId] = nowMs()
        }
        attach(sessionId)
        updateGlobalSession()
    }

    /**
     * Path 2: called when a media app broadcasts
     * [android.media.audiofx.AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION].
     */
    @Synchronized
    fun detachSession(sessionId: Int) {
        if (sessionId == GLOBAL_AUDIO_SESSION) return
        forget(sessionId)
        updateGlobalSession()
    }

    /**
     * Path 3 with root: reconcile with sessions found by [AudioSessionDiscovery].
     * Attaches new MEDIA sessions and releases ones that are no longer playing.
     */
    @Synchronized
    fun syncActiveSessions(activeSessions: Set<Int>) {
        if (!enabled) return
        for (session in activeSessions) {
            if (session != GLOBAL_AUDIO_SESSION) attach(session)
        }
        val stale = enhancers.keys.filter { it != GLOBAL_AUDIO_SESSION && it !in activeSessions }
        for (session in stale) {
            forget(session)
        }
        updateGlobalSession()
    }

    /**
     * Path 3 without root: attaches sessions found by [VisualizerSessionScanner], each with
     * whether session 0 already boosts it (then, in [GlobalMode.ALWAYS], it stays at 0 dB).
     *
     * A silent session may just be paused, so nothing is released here (resuming would play
     * unboosted until the next scan). Session ids are never reused, so ended sessions are
     * only evicted once more than [MAX_HEARD_SESSIONS] were attached.
     */
    @Synchronized
    fun addHeardSessions(sessions: Map<Int, Boolean>) {
        if (!enabled) return
        for ((session, covered) in sessions) {
            if (session == GLOBAL_AUDIO_SESSION) continue
            unmeasured.remove(session)
            // Covered only counts while session 0 really has an enhancer: otherwise nothing
            // boosts the player but its own enhancer.
            if (covered && globalMode == GlobalMode.ALWAYS &&
                enhancers.containsKey(GLOBAL_AUDIO_SESSION)
            ) {
                coveredByGlobal.add(session)
            } else {
                coveredByGlobal.remove(session)
            }
            if (!attach(session)) continue
            heardSessions.remove(session)
            heardSessions.add(session)
        }
        while (heardSessions.size > MAX_HEARD_SESSIONS) {
            forget(heardSessions.first())
        }
        updateGlobalSession()
    }

    /**
     * Gives the full boost to broadcast sessions that no scan measured within [UNMEASURED_GRACE_MS]
     * (e.g. never heard, or the mix could not be read): staying at 0 dB would leave them silent
     * of any boost if session 0 does not reach them.
     *
     * @return true while some session is still waiting for its first measurement.
     */
    @Synchronized
    fun expireUnmeasured(): Boolean {
        val now = nowMs()
        val iterator = unmeasured.entries.iterator()
        val expired = ArrayList<Int>()
        while (iterator.hasNext()) {
            val (session, since) = iterator.next()
            if (now - since >= UNMEASURED_GRACE_MS) {
                iterator.remove()
                expired.add(session)
            }
        }
        for (session in expired) {
            coveredByGlobal.remove(session)
            if (enabled) applyTo(session)
        }
        return unmeasured.isNotEmpty()
    }

    private fun nowMs() = android.os.SystemClock.elapsedRealtime()

    /** Real sessions with an enhancer, most recently heard first: each scan re-measures them. */
    @Synchronized
    fun attachedSessions(): List<Int> {
        val others = enhancers.keys.filter { it != GLOBAL_AUDIO_SESSION && it !in heardSessions }
        return heardSessions.reversed() + others
    }

    /**
     * Disables boost and frees every effect. Also disarms paths 2 and 3, so a late
     * broadcast or an in-flight scan cannot re-attach an enhancer while boost is off.
     */
    @Synchronized
    fun release() {
        enabled = false
        releaseAll()
    }

    /** Attaches or releases session 0 as the [GlobalMode] requires. */
    private fun updateGlobalSession() {
        if (!enabled) return
        val wanted = when (globalMode) {
            GlobalMode.OFF -> false
            GlobalMode.ALWAYS -> true
            GlobalMode.EXCLUSIVE -> enhancers.keys.none { it != GLOBAL_AUDIO_SESSION }
        }
        if (!wanted) {
            enhancers.remove(GLOBAL_AUDIO_SESSION)?.let { release(it) }
        } else if (!globalRefused && !enhancers.containsKey(GLOBAL_AUDIO_SESSION)) {
            if (!attach(GLOBAL_AUDIO_SESSION)) {
                globalRefused = true
                coveredByGlobal.clear()
                unmeasured.clear()
                for (session in enhancers.keys.toList()) applyTo(session)
                Log.w(TAG, "Global session unavailable; relying on session discovery")
            }
        }
    }

    /** Creates (or refreshes) a [LoudnessEnhancer] for [sessionId] and applies the gain. */
    private fun attach(sessionId: Int): Boolean {
        if (sessionId < 0) return false
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

    /**
     * A covered session keeps an enabled enhancer at 0 dB: on some builds session 0 reaches a
     * track only while the track has a session effect chain.
     */
    private fun applyTo(sessionId: Int): Boolean {
        val effect = enhancers[sessionId] ?: return false
        val gainDb = if (sessionId in coveredByGlobal) 0 else level
        return try {
            effect.setTargetGain(dbToMillibels(gainDb))
            effect.enabled = level > 0
            true
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to apply level=$gainDb to session=$sessionId", error)
            forget(sessionId)
            false
        }
    }

    private fun forget(sessionId: Int) {
        heardSessions.remove(sessionId)
        unmeasured.remove(sessionId)
        coveredByGlobal.remove(sessionId)
        enhancers.remove(sessionId)?.let { release(it) }
    }

    private fun releaseAll() {
        for (effect in enhancers.values) {
            release(effect)
        }
        enhancers.clear()
        heardSessions.clear()
        unmeasured.clear()
        coveredByGlobal.clear()
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

        /** The historical "global output mix" session (path 1). */
        private const val GLOBAL_AUDIO_SESSION = 0

        /** Sessions kept by [addHeardSessions]; older ones have most likely ended. */
        private const val MAX_HEARD_SESSIONS = 8

        /** How long a broadcast session may stay at 0 dB waiting to be measured. */
        const val UNMEASURED_GRACE_MS = 3_000L

        /** [LoudnessEnhancer.setTargetGain] takes millibels: 1 dB = 100 mB. */
        fun dbToMillibels(db: Int): Int = db * 100
    }
}
