package com.offline.audiobookplayer.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatterTest {

    @Test
    fun `formatDuration handles zero and negative values`() {
        assertEquals("00:00:00", TimeFormatter.formatDuration(0L))
        assertEquals("00:00:00", TimeFormatter.formatDuration(-5_000L))
        assertEquals("00:00:00", TimeFormatter.formatDuration(Long.MIN_VALUE + 1))
    }

    @Test
    fun `formatDuration formats standard durations accurately`() {
        assertEquals("00:00:05", TimeFormatter.formatDuration(5_000L))
        assertEquals("01:01:01", TimeFormatter.formatDuration(3_661_000L))
        assertEquals("05:20:00", TimeFormatter.formatDuration(19_200_000L))
    }

    @Test
    fun `formatDuration supports over 100 hours and beyond 32-bit Int max without overflow`() {
        val hundredHoursMs = 100L * 3_600L * 1_000L
        assertEquals("100:00:00", TimeFormatter.formatDuration(hundredHoursMs))

        val hundredTwentyFiveHoursMs = ((125L * 3_600L) + (43L * 60L) + 9L) * 1_000L
        assertEquals("125:43:09", TimeFormatter.formatDuration(hundredTwentyFiveHoursMs))

        // Exceeds Int.MAX_VALUE (~596.5 hours) to verify strict 64-bit arithmetic
        val sevenHundredHoursMs = ((700L * 3_600L) + (5L * 60L) + 42L) * 1_000L
        assertEquals("700:05:42", TimeFormatter.formatDuration(sevenHundredHoursMs))
    }

    @Test
    fun `parseAndValidateJumpTime accepts valid 64-bit timestamps over 100 hours`() {
        val totalDurationMs = 150L * 3_600L * 1_000L
        val result = TimeFormatter.parseAndValidateJumpTime(
            hoursText = "105",
            minutesText = "20",
            secondsText = "45",
            totalDurationMs = totalDurationMs
        )

        val expectedMs = ((105L * 3_600L) + (20L * 60L) + 45L) * 1_000L
        assertEquals(JumpTimeValidation.Valid(expectedMs), result)
    }

    @Test
    fun `parseAndValidateJumpTime treats blank subfields as zero when another field is set`() {
        val result = TimeFormatter.parseAndValidateJumpTime(
            hoursText = "",
            minutesText = "15",
            secondsText = "",
            totalDurationMs = 3_600_000L
        )
        assertEquals(JumpTimeValidation.Valid(15L * 60L * 1_000L), result)
    }

    @Test
    fun `parseAndValidateJumpTime allows zero and exact track duration boundary`() {
        val totalMs = 10_000_000L
        assertEquals(
            JumpTimeValidation.Valid(0L),
            TimeFormatter.parseAndValidateJumpTime("0", "0", "0", totalMs)
        )
        assertEquals(
            JumpTimeValidation.Valid(totalMs),
            TimeFormatter.parseAndValidateJumpTime("2", "46", "40", totalMs)
        )
    }

    @Test
    fun `parseAndValidateJumpTime rejects invalid inputs and out of bounds targets`() {
        val totalMs = 10_000_000L

        assertEquals(
            JumpTimeValidation.Invalid(JumpTimeError.EMPTY_INPUT),
            TimeFormatter.parseAndValidateJumpTime("", " ", "", totalMs)
        )
        assertEquals(
            JumpTimeValidation.Invalid(JumpTimeError.INVALID_NUMBER),
            TimeFormatter.parseAndValidateJumpTime("abc", "0", "0", totalMs)
        )
        assertEquals(
            JumpTimeValidation.Invalid(JumpTimeError.NEGATIVE_VALUE),
            TimeFormatter.parseAndValidateJumpTime("-1", "0", "0", totalMs)
        )
        assertEquals(
            JumpTimeValidation.Invalid(JumpTimeError.MINUTES_OUT_OF_RANGE),
            TimeFormatter.parseAndValidateJumpTime("0", "60", "0", totalMs)
        )
        assertEquals(
            JumpTimeValidation.Invalid(JumpTimeError.SECONDS_OUT_OF_RANGE),
            TimeFormatter.parseAndValidateJumpTime("0", "10", "60", totalMs)
        )
        assertEquals(
            JumpTimeValidation.Invalid(JumpTimeError.UNKNOWN_DURATION),
            TimeFormatter.parseAndValidateJumpTime("0", "1", "0", 0L)
        )
        assertEquals(
            JumpTimeValidation.Invalid(JumpTimeError.EXCEEDS_DURATION),
            TimeFormatter.parseAndValidateJumpTime("3", "0", "0", totalMs)
        )
        assertEquals(
            JumpTimeValidation.Invalid(JumpTimeError.OVERFLOW),
            TimeFormatter.parseAndValidateJumpTime("9999999999999999", "0", "0", totalMs)
        )
    }

    @Test
    fun `extractCleanTrackTitle strips mp3 extension case-insensitively`() {
        assertEquals("Truyen Kiem Hiep - Tap 01", TimeFormatter.extractCleanTrackTitle("Truyen Kiem Hiep - Tap 01.mp3"))
        assertEquals("AudioBook.Part2", TimeFormatter.extractCleanTrackTitle("  AudioBook.Part2.MP3  "))
        assertEquals("Unknown Audio", TimeFormatter.extractCleanTrackTitle("   "))
    }
}
