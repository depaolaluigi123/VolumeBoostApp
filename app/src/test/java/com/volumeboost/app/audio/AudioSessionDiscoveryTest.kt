package com.volumeboost.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSessionDiscoveryTest {

    // Rows captured from `dumpsys media.audio_flinger` on Android 13 (LineageOS), plus the
    // fast (`F<n>`) and static (`S`) variants AOSP prints in the same Type column.
    private val dump = """
        Output thread 0xb4000079d132b9a0, name AudioOut_15, tid 1906, type 0 (MIXER):
          Local log:
           10-06 09:36:12.840 AT::add       (0xb400007837ed5cf0)          62     no  18353     153      43 A  0x000 00000001 00000001  44100  3   1  0  -inf     0     0     0  00000000  22050       0 f         0        0       new
          4 Tracks of which 3 are active
            Type     Id Active Client Session Port Id S  Flags   Format Chn mask  SRate ST Usg CT  G db  L dB  R dB  VS dB   Server FrmCnt  FrmRdy F Underruns  Flushed   Latency
                     62    yes  18353     153      43 A  0x000 00000001 00000001  44100  3   1  0  -inf     0     0     0  00029580  22050   18522 A         0        0  565.00 t
          F1         70    yes  20001     161      51 A  0x000 00000001 00000003  48000  3   1  2  -inf     0     0     0  00001000    960     960 A         0        0   20.00 t
             S       59     no  16938      97      40 A  0x000 00000001 00000001  22050  3  13  4  -inf  -inf  -inf     0  00000000  22050   22050 F         0        0       new
                     80    yes   4242     170      55 A  0x000 00000001 00000003  48000  3   1  2  -inf     0     0     0  00001000    960     960 A         0        0   20.00 t
             P       90    yes   1234     180      60 A  0x000 00000001 00000003  48000  3   1  0  -inf     0     0     0  00001000    960     960 A         0        0   20.00 t
          1 Effect Chains
            1 effects for session 153
    """.trimIndent()

    @Test
    fun keepsNormalAndFastMediaTracks() {
        val sessions = AudioSessionDiscovery.parseMediaSessions(dump, ownPid = 4242)
        assertEquals(setOf(153, 161), sessions)
    }

    @Test
    fun skipsOwnProcess() {
        val sessions = AudioSessionDiscovery.parseMediaSessions(dump, ownPid = 18353)
        assertEquals(setOf(161, 170), sessions)
    }

    @Test
    fun skipsNonMediaUsageLocalLogAndPatchTracks() {
        val sessions = AudioSessionDiscovery.parseMediaSessions(dump, ownPid = -1)
        // 97 is USAGE_GAME (13), the local-log row is history, 180 is a patch track.
        assertEquals(setOf(153, 161, 170), sessions)
    }

    @Test
    fun parsesStaticTracks() {
        val row = "   S       59    yes  16938      97      40 A  0x000 00000001 00000001  22050  3   1  4  -inf"
        assertEquals(setOf(97), AudioSessionDiscovery.parseMediaSessions(row, ownPid = -1))
    }

    @Test
    fun emptyDumpHasNoSessions() {
        assertTrue(AudioSessionDiscovery.parseMediaSessions("0 Tracks\n", ownPid = -1).isEmpty())
    }

    @Test
    fun shellSessionRunsSeveralCommandsInOneProcess() {
        ShellSession(listOf("sh")).use { shell ->
            val pid = shell.run("echo \$\$")?.trim()
            assertEquals("hello\n\n", shell.run("echo hello"))
            // Output without a trailing newline still ends before the marker.
            assertEquals("abc\n", shell.run("printf abc"))
            // stderr is captured too.
            assertEquals("err\n\n", shell.run("echo err >&2"))
            assertEquals(pid, shell.run("echo \$\$")?.trim())
        }
    }

    @Test
    fun shellSessionReturnsNullWhenShellDies() {
        ShellSession(listOf("sh")).use { shell ->
            assertNull(shell.run("exit 1"))
            assertNull(shell.run("echo again"))
        }
    }

    @Test
    fun closedShellSessionReturnsNull() {
        val shell = ShellSession(listOf("sh"))
        shell.close()
        assertNull(shell.run("echo hello"))
    }
}
