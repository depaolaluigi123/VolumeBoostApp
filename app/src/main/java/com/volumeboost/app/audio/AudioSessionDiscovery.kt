package com.volumeboost.app.audio

import android.util.Log

/**
 * Path 3 of volume boost: discover real audio session IDs of media playing on the device.
 *
 * Without elevated privileges a normal app cannot learn other apps’ session IDs — the
 * framework returns anonymized [android.media.AudioPlaybackConfiguration] copies with the
 * session stripped. Compliant players can still announce themselves via
 * [android.media.audiofx.AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION] (path 2),
 * but many (YouTube, Deezer, …) never do.
 *
 * With root we read AudioFlinger (`dumpsys media.audio_flinger`), which lists every output
 * track with its session id and usage. We keep only [USAGE_MEDIA] tracks so the boost
 * targets music/video playback regardless of which app is playing — including when the
 * user enables boost after playback has already started.
 *
 * Used by [com.volumeboost.app.service.VolumeBoostService] → [VolumeBoostManager.syncActiveSessions].
 */
object AudioSessionDiscovery {

    private const val TAG = "AudioSessionDiscovery"

    /** AudioAttributes.USAGE_MEDIA — the stream type we want to boost. */
    private const val USAGE_MEDIA = 1

    // Track rows in `dumpsys media.audio_flinger` look like:
    //   112     no   6955     689     109 S  0x600 00000005 00000001  44100  3   1  2  -14 ...
    //   Id     Act  Client  Session PortId St Flags Format   ChnMask   SRate ST Usg CT ...
    private val TRACK_ROW = Regex(
        "^\\s*(\\d+)\\s+(yes|no)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\S)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)"
    )

    @Volatile private var rootChecked = false
    @Volatile private var rootAvailable = false

    /** Whether a working `su` binary is reachable. Result is cached after the first probe. */
    fun isRootAvailable(): Boolean {
        if (rootChecked) return rootAvailable
        synchronized(this) {
            if (!rootChecked) {
                rootAvailable = runAsRoot("id")?.contains("uid=0") == true
                rootChecked = true
                Log.i(TAG, "root available = $rootAvailable")
            }
        }
        return rootAvailable
    }

    /**
     * Session IDs of every MEDIA output track AudioFlinger currently knows about,
     * excluding [ownPid] (this app). Requires root; returns an empty set otherwise.
     */
    fun discoverMediaSessions(ownPid: Int): Set<Int> {
        if (!isRootAvailable()) return emptySet()
        val dump = runAsRoot("dumpsys media.audio_flinger") ?: return emptySet()
        val sessions = LinkedHashSet<Int>()
        for (line in dump.lineSequence()) {
            val match = TRACK_ROW.find(line) ?: continue
            val pid = match.groupValues[3].toIntOrNull() ?: continue
            val session = match.groupValues[4].toIntOrNull() ?: continue
            val usage = match.groupValues[12].toIntOrNull() ?: continue
            if (pid == ownPid || session <= 0) continue
            if (usage == USAGE_MEDIA) sessions.add(session)
        }
        return sessions
    }

    /** Runs [command] via `su -c`. Returns combined stdout/stderr, or null on failure. */
    private fun runAsRoot(command: String): String? = try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        output
    } catch (error: Throwable) {
        Log.w(TAG, "root command failed: $command", error)
        null
    }
}
