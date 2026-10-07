package com.volumeboost.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VisualizerSessionScannerTest {

    @Before
    fun reset() = VisualizerSessionScanner.resetDeepPass()

    @Test
    fun fewSessionsSinceBoot_meteredNewestFirst() {
        val ids = VisualizerSessionScanner.planCandidates(newest = 153, priority = emptyList())
        assertEquals((153 downTo 9 step 8).toList(), ids.toList())
    }

    @Test
    fun priorityComesFirstWithoutDuplicates() {
        val ids = VisualizerSessionScanner.planCandidates(newest = 153, priority = listOf(81, 0, 145))
        assertEquals(listOf(81, 145, 153, 137), ids.take(4))
        assertEquals(19, ids.size)
    }

    @Test
    fun deepPassResumesBelowPreviousSlice() {
        val newest = 9 + 8 * 2000
        val first = VisualizerSessionScanner.planCandidates(newest, emptyList()).toList()
        assertEquals(128 + 384, first.size)
        assertEquals(newest, first.first())
        val second = VisualizerSessionScanner.planCandidates(newest, emptyList()).toList()
        // Newest window again, then the deep pass continues where the first scan stopped.
        assertEquals(first.take(128), second.take(128))
        assertEquals(first.last() - 8, second[128])
    }

    @Test
    fun deepPassStopsAtOldestUntilReset() {
        val newest = 9 + 8 * 600
        var scans = 0
        var reachedOldest = false
        while (!reachedOldest && scans < 10) {
            reachedOldest = 9 in VisualizerSessionScanner.planCandidates(newest, emptyList())
            scans++
        }
        assertTrue(reachedOldest)
        // Exhausted: only the newest window until a playback change resets the pass.
        assertEquals(128, VisualizerSessionScanner.planCandidates(newest, emptyList()).size)
        VisualizerSessionScanner.resetDeepPass()
        assertEquals(128 + 384, VisualizerSessionScanner.planCandidates(newest, emptyList()).size)
    }

    @Test
    fun mixLouderByTheBoost_isCoveredBySessionZero() {
        // +10 dB boost: the mix reads ~10 dB above the session when session 0 reaches it.
        assertTrue(VisualizerSessionScanner.isCoveredByGlobal(globalGainMb = 1000, boostDb = 10))
        // A limiter squeezing loud audio still leaves more than 3 dB.
        assertTrue(VisualizerSessionScanner.isCoveredByGlobal(globalGainMb = 450, boostDb = 20))
    }

    @Test
    fun mixNotLouder_isNotCovered() {
        // Same level (session 0 ignored) or quieter (the player is on another output).
        assertFalse(VisualizerSessionScanner.isCoveredByGlobal(globalGainMb = 0, boostDb = 10))
        assertFalse(VisualizerSessionScanner.isCoveredByGlobal(globalGainMb = -2500, boostDb = 10))
        // Small boosts: half the boost is the threshold.
        assertFalse(VisualizerSessionScanner.isCoveredByGlobal(globalGainMb = 50, boostDb = 2))
        assertTrue(VisualizerSessionScanner.isCoveredByGlobal(globalGainMb = 150, boostDb = 2))
    }

    @Test
    fun unknownMix_countsAsCovered() {
        // No mix reading: a missing boost is safer than a doubled one.
        assertTrue(VisualizerSessionScanner.isCoveredByGlobal(globalGainMb = null, boostDb = 20))
    }
}
