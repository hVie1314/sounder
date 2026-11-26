package com.offline.audiobookplayer.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerManagerTest {

    @Test
    fun `countdown fades volume over final 3 seconds, triggers pause, and restores volume to 1`() = runTest {
        val recordedVolumes = mutableListOf<Float>()
        var pauseCount = 0

        val manager = SleepTimerManager(
            scope = backgroundScope,
            onSetVolume = { recordedVolumes.add(it) },
            onFadeCompletePause = { pauseCount++ }
        )

        manager.startCountdown(5_000L)
        runCurrent()

        assertTrue(manager.state.value is SleepTimerState.Countdown)
        assertEquals(1.0f, recordedVolumes.last(), 0.001f)
        assertEquals(0, pauseCount)

        // Advance into the 3-second fade-out zone
        advanceTimeBy(3_500L)
        runCurrent()
        val fadeState = manager.state.value as SleepTimerState.Countdown
        assertTrue(fadeState.isFadingOut)
        assertTrue(recordedVolumes.last() < 1.0f)

        // Complete countdown
        advanceTimeBy(2_000L)
        runCurrent()

        assertEquals(SleepTimerState.Inactive, manager.state.value)
        assertEquals(1, pauseCount)
        assertEquals(1.0f, recordedVolumes.last(), 0.001f)
    }

    @Test
    fun `cancel during fade-out restores full volume and never invokes pause`() = runTest {
        val recordedVolumes = mutableListOf<Float>()
        var pauseCount = 0

        val manager = SleepTimerManager(
            scope = backgroundScope,
            onSetVolume = { recordedVolumes.add(it) },
            onFadeCompletePause = { pauseCount++ }
        )

        manager.startCountdown(4_000L)
        advanceTimeBy(2_500L)
        runCurrent()
        assertTrue(recordedVolumes.last() < 1.0f)

        manager.cancel()
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(SleepTimerState.Inactive, manager.state.value)
        assertEquals(0, pauseCount)
        assertEquals(1.0f, recordedVolumes.last(), 0.001f)
    }

    @Test
    fun `end of track mode fades in last 3 seconds and resets cleanly at track end`() = runTest {
        val recordedVolumes = mutableListOf<Float>()
        var pauseCount = 0

        val manager = SleepTimerManager(
            scope = backgroundScope,
            onSetVolume = { recordedVolumes.add(it) },
            onFadeCompletePause = { pauseCount++ }
        )

        manager.startEndOfTrack()
        assertEquals(SleepTimerState.EndOfTrack(isFadingOut = false), manager.state.value)

        manager.onPlaybackProgress(isPlaying = true, positionMs = 98_500L, durationMs = 100_000L)
        assertEquals(SleepTimerState.EndOfTrack(isFadingOut = true), manager.state.value)
        assertEquals(0.5f, recordedVolumes.last(), 0.01f)

        manager.onTrackReachedEnd()
        assertEquals(SleepTimerState.Inactive, manager.state.value)
        assertEquals(1, pauseCount)
        assertEquals(1.0f, recordedVolumes.last(), 0.001f)
    }
}
