package com.offline.audiobookplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTimestampGuardTest {

    private val trackDurationMs = 400_000_000L // ~111 hours

    @Test
    fun `blocks overwriting non-zero position with 0L during non-explicit lifecycle events`() {
        val savedPositionMs = 19_200_000L // 05:20:00
        val blockedReasons = listOf(
            SaveReason.PERIODIC_TICK,
            SaveReason.PAUSE,
            SaveReason.TRACK_SWITCH,
            SaveReason.AUDIO_BECOMING_NOISY,
            SaveReason.SLEEP_TIMER,
            SaveReason.SERVICE_TEARDOWN
        )

        blockedReasons.forEach { reason ->
            val decision = PlaybackTimestampGuard.evaluate(
                currentSavedPositionMs = savedPositionMs,
                candidatePositionMs = 0L,
                durationMs = trackDurationMs,
                reason = reason,
                isInitialSeekPending = false,
                hasPlayerError = false
            )
            assertTrue("Expected Skip for $reason", decision is PositionSaveDecision.Skip)
        }
    }

    @Test
    fun `allows persisting 0L when user explicitly seeks to zero or track naturally ends`() {
        val savedPositionMs = 19_200_000L

        val userSeekDecision = PlaybackTimestampGuard.evaluate(
            currentSavedPositionMs = savedPositionMs,
            candidatePositionMs = 0L,
            durationMs = trackDurationMs,
            reason = SaveReason.USER_SEEK,
            isInitialSeekPending = false,
            hasPlayerError = false
        )
        assertEquals(PositionSaveDecision.Persist(0L), userSeekDecision)

        val trackEndedDecision = PlaybackTimestampGuard.evaluate(
            currentSavedPositionMs = savedPositionMs,
            candidatePositionMs = 0L,
            durationMs = trackDurationMs,
            reason = SaveReason.TRACK_ENDED,
            isInitialSeekPending = false,
            hasPlayerError = false
        )
        assertEquals(PositionSaveDecision.Persist(0L), trackEndedDecision)
    }

    @Test
    fun `blocks negative or TIME_UNSET candidate positions for every reason`() {
        val unsetTime = Long.MIN_VALUE + 1 // C.TIME_UNSET equivalent

        SaveReason.entries.forEach { reason ->
            val decision = PlaybackTimestampGuard.evaluate(
                currentSavedPositionMs = 5_000L,
                candidatePositionMs = unsetTime,
                durationMs = trackDurationMs,
                reason = reason,
                isInitialSeekPending = false,
                hasPlayerError = false
            )
            assertTrue("Expected Skip for unset position on $reason", decision is PositionSaveDecision.Skip)
        }
    }

    @Test
    fun `blocks background writes while initial resume seek is pending or player has error`() {
        val pendingSeekDecision = PlaybackTimestampGuard.evaluate(
            currentSavedPositionMs = 19_200_000L,
            candidatePositionMs = 120L,
            durationMs = trackDurationMs,
            reason = SaveReason.PERIODIC_TICK,
            isInitialSeekPending = true,
            hasPlayerError = false
        )
        assertTrue(pendingSeekDecision is PositionSaveDecision.Skip)

        val errorStateDecision = PlaybackTimestampGuard.evaluate(
            currentSavedPositionMs = 19_200_000L,
            candidatePositionMs = 1_000L,
            durationMs = trackDurationMs,
            reason = SaveReason.SERVICE_TEARDOWN,
            isInitialSeekPending = false,
            hasPlayerError = true
        )
        assertTrue(errorStateDecision is PositionSaveDecision.Skip)
    }

    @Test
    fun `persists valid 64-bit positions exceeding Int max and clamps to track duration`() {
        val largeDurationMs = 2_520_000_000L // 700 hours (> Int.MAX_VALUE)
        val largePositionMs = 2_200_000_000L // ~611 hours (> Int.MAX_VALUE)

        val normalDecision = PlaybackTimestampGuard.evaluate(
            currentSavedPositionMs = 19_200_000L,
            candidatePositionMs = largePositionMs,
            durationMs = largeDurationMs,
            reason = SaveReason.PERIODIC_TICK,
            isInitialSeekPending = false,
            hasPlayerError = false
        )
        assertEquals(PositionSaveDecision.Persist(largePositionMs), normalDecision)

        val clampedDecision = PlaybackTimestampGuard.evaluate(
            currentSavedPositionMs = 19_200_000L,
            candidatePositionMs = largeDurationMs + 10_000L,
            durationMs = largeDurationMs,
            reason = SaveReason.USER_SEEK,
            isInitialSeekPending = false,
            hasPlayerError = false
        )
        assertEquals(PositionSaveDecision.Persist(largeDurationMs), clampedDecision)
    }
}
