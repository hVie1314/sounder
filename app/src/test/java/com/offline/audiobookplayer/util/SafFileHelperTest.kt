package com.offline.audiobookplayer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafFileHelperTest {

    @Test
    fun `naturalCompare orders chapter numbers numerically rather than lexicographically`() {
        val unsorted = listOf(
            "Tap 10.mp3",
            "Tap 2.mp3",
            "Tap 1.mp3",
            "Tap 100.mp3",
            "Tap 09.mp3"
        )
        val sorted = unsorted.sortedWith { a, b -> SafFileHelper.naturalCompare(a, b) }
        assertEquals(
            listOf("Tap 1.mp3", "Tap 2.mp3", "Tap 09.mp3", "Tap 10.mp3", "Tap 100.mp3"),
            sorted
        )
    }

    @Test
    fun `isMp3Candidate accepts mp3 extensions and audio mpeg mime types`() {
        assertTrue(SafFileHelper.isMp3Candidate("Chuong_01.mp3", null))
        assertTrue(SafFileHelper.isMp3Candidate("Chuong_01.MP3", "application/octet-stream"))
        assertTrue(SafFileHelper.isMp3Candidate("track", "audio/mpeg"))
        assertFalse(SafFileHelper.isMp3Candidate("cover.jpg", "image/jpeg"))
    }
}
