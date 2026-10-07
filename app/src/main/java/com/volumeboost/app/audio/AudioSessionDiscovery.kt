package com.volumeboost.app.audio

import android.content.Context
import android.util.Log
import java.io.Closeable
import java.io.IOException

/**
 * Path 2 of volume boost: discover real audio session IDs of media playing on the device.
 *
 * Without elevated privileges a normal app cannot learn other apps’ session IDs — the
 * framework returns anonymized [android.media.AudioPlaybackConfiguration] copies with the
 * session stripped. Compliant players can still announce themselves via
 * [android.media.audiofx.AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION] (path 1),
 * but many (YouTube, Deezer, …) never do. Two ways to find them ([Access]):
 * - **[Access.ROOT]** — `su` reads AudioFlinger (`dumpsys media.audio_flinger`), which lists
 *   every output track with its session id and usage. Only [USAGE_MEDIA] tracks are kept.
 *   A single long-lived `su` shell is reused for every scan: spawning `su` every few seconds
 *   would make Magisk show a toast and log each request.
 * - **[Access.VISUALIZER]** — no root: [VisualizerSessionScanner] finds the sessions that are
 *   producing sound, with only the RECORD_AUDIO runtime permission. It cannot read the usage,
 *   so root is preferred when available.
 *
 * Used by [com.volumeboost.app.service.VolumeBoostService] → [VolumeBoostManager].
 */
object AudioSessionDiscovery {

    private const val TAG = "AudioSessionDiscovery"

    /** AudioAttributes.USAGE_MEDIA — the stream type we want to boost. */
    private const val USAGE_MEDIA = 1

    // Track rows in `dumpsys media.audio_flinger` (Android 12+) look like:
    //   Type     Id Active Client Session Port Id S  Flags   Format Chn mask  SRate ST Usg CT ...
    //                62    yes  18353     153      43 A  0x000 00000001 00000001  44100  3   1  0 ...
    // The Type column is blank for normal tracks, `S` for static ones, and fast tracks are
    // prefixed with `F<index>`. Patch tracks (`P`) are internal routing and are skipped.
    private val TRACK_ROW = Regex(
        "^\\s*(?:F\\d+\\s+)?(?:S\\s+)?(\\d+)\\s+(yes|no)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\S)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)"
    )

    /** How path 2 can find other apps' sessions on this device. */
    enum class Access { ROOT, VISUALIZER, NONE }

    private const val DUMPSYS = "/system/bin/dumpsys"
    private const val AUDIO_FLINGER = "media.audio_flinger"

    private val lock = Any()

    @Volatile private var shell: ShellSession? = null
    @Volatile private var rootChecked = false
    @Volatile private var rootAvailable = false

    /** Current access, probing `su` first (may show the Magisk prompt: call off the main thread). */
    fun access(context: Context): Access = when {
        isRootAvailable() -> Access.ROOT
        VisualizerSessionScanner.hasPermission(context) -> Access.VISUALIZER
        else -> Access.NONE
    }

    /** Like [access] but never probes `su`: safe for the UI thread. Unprobed root counts as NONE. */
    fun knownAccess(context: Context): Access = when {
        rootChecked && rootAvailable -> Access.ROOT
        VisualizerSessionScanner.hasPermission(context) -> Access.VISUALIZER
        else -> Access.NONE
    }

    /** Whether a working `su` binary is reachable. Result is cached after the first probe. */
    fun isRootAvailable(): Boolean {
        if (rootChecked) return rootAvailable
        synchronized(lock) {
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
     * excluding [ownPid] (this app), read as root. Returns null when root is unavailable or
     * the scan failed: callers should then keep their current sessions (including the ones
     * attached through path 1).
     */
    fun discoverMediaSessions(ownPid: Int): Set<Int>? {
        if (!isRootAvailable()) return null
        val dump = runAsRoot("$DUMPSYS $AUDIO_FLINGER") ?: return null
        return parseMediaSessions(dump, ownPid)
    }

    /** Extracts MEDIA session IDs from a `dumpsys media.audio_flinger` dump. */
    internal fun parseMediaSessions(dump: String, ownPid: Int): Set<Int> {
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

    /**
     * Ends the root shell (boost off). Does not take [lock], so it never blocks on a scan
     * in progress: that scan just fails and the next one opens a fresh shell.
     */
    fun closeShell() {
        val current = shell
        shell = null
        current?.close()
    }

    /** Runs [command] in the shared root shell. Returns its output, or null on failure. */
    private fun runAsRoot(command: String): String? = synchronized(lock) {
        val session = shell ?: openShell() ?: return null
        val output = session.run(command)
        if (output == null) {
            Log.w(TAG, "root command failed: $command")
            if (shell === session) shell = null
            session.close()
        }
        output
    }

    private fun openShell(): ShellSession? = try {
        ShellSession(listOf("su")).also { shell = it }
    } catch (error: IOException) {
        Log.w(TAG, "cannot start su", error)
        null
    }
}

/**
 * A long-lived interactive shell. Each [run] writes one command followed by an end marker
 * and reads output up to that marker. Not thread-safe: callers serialize access.
 */
internal class ShellSession(command: List<String>) : Closeable {

    private val process: Process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()
    private val input = process.outputStream.bufferedWriter()
    private val output = process.inputStream.bufferedReader()

    /** Output of [command] (stderr included), or null if the shell is gone. */
    fun run(command: String): String? = try {
        // The bare `echo` guarantees the marker starts on its own line.
        input.write("$command 2>&1; echo; echo $END_MARKER\n")
        input.flush()
        val result = StringBuilder()
        var ended = false
        while (true) {
            val line = output.readLine() ?: break
            if (line == END_MARKER) {
                ended = true
                break
            }
            result.append(line).append('\n')
        }
        if (ended) result.toString() else null
    } catch (_: IOException) {
        null
    }

    override fun close() {
        process.destroy()
    }

    private companion object {
        const val END_MARKER = "__VOLUME_BOOST_END__"
    }
}
