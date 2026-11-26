package com.offline.audiobookplayer.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

@UnstableApi
class FastLargeMp3SeekingTest {

    @Test
    fun `SafeXingSeekMap scales 150-hour over-4GB file accurately without 32-bit overflow`() {
        val durationUs = 150L * 3_600L * 1_000_000L // 150 hours
        val trueDataSize = 8_640_000_000L // ~8.64 GB (> 4.29 GB 32-bit Xing limit)
        val audioStart = 4_096L
        val toc = LongArray(100) { idx -> ((idx * 256L) / 100L).coerceIn(0L, 255L) }

        val seekMap = SafeXingSeekMap(
            audioStartPosition = audioStart,
            durationUs = durationUs,
            averageBitrateBps = 128_000,
            trueDataSize = trueDataSize,
            tableOfContents = toc
        )

        assertTrue(seekMap.isSeekable)
        assertEquals(durationUs, seekMap.durationUs)

        // Jump to 105:20:45 (well past 4.29 GB offset)
        val jumpUs = ((105L * 3_600L) + (20L * 60L) + 45L) * 1_000_000L
        val point = seekMap.getSeekPoints(jumpUs).first
        assertEquals(jumpUs, point.timeUs)
        assertTrue(
            "Expected seek byte offset past 4.29 GB, got ${point.position}",
            point.position > 4_294_967_296L
        )
        assertTrue(point.position < audioStart + trueDataSize)

        val roundTripUs = seekMap.getTimeUs(point.position)
        val diffSeconds = abs(roundTripUs - jumpUs) / 1_000_000L
        assertTrue("Round-trip error too large: ${diffSeconds}s", diffSeconds <= 60L)
    }

    @Test
    fun `SafeXingSeekMap rejects all-zero or non-monotonic TOCs`() {
        assertFalse(SafeXingSeekMap.isValidToc(null))
        assertFalse(SafeXingSeekMap.isValidToc(LongArray(50) { it.toLong() }))
        assertFalse(SafeXingSeekMap.isValidToc(LongArray(100) { 0L }))

        val decreasing = LongArray(100) { idx -> (idx * 2L).coerceAtMost(255L) }
        decreasing[40] = decreasing[39] - 1L
        assertFalse(SafeXingSeekMap.isValidToc(decreasing))

        val valid = LongArray(100) { idx -> (idx * 256L) / 100L }
        assertTrue(SafeXingSeekMap.isValidToc(valid))
    }

    @Test
    fun `PiecewiseLinearMp3SeekMap handles multi-bitrate 150-hour VBR layout cleanly`() {
        val firstSamplePos = 1_024L
        // 3 segments: 3 GB @ 64 kbps, 3 GB @ 128 kbps, 3 GB @ 192 kbps
        val streamLen = firstSamplePos + 9_000_000_000L
        val offsets = longArrayOf(
            firstSamplePos,
            firstSamplePos + 3_000_000_000L,
            firstSamplePos + 6_000_000_000L,
            streamLen
        )
        val bitrates = intArrayOf(64_000, 128_000, 192_000, 192_000)

        val seekMap = PiecewiseLinearMp3SeekMap(
            firstSamplePosition = firstSamplePos,
            streamLength = streamLen,
            anchorOffsets = offsets,
            anchorBitratesBps = bitrates,
            firstFrameSize = 417
        )

        assertTrue(seekMap.isSeekable)
        assertTrue(seekMap.durationUs > 100L * 3_600L * 1_000_000L)

        val midTimeUs = seekMap.durationUs / 2L
        val midPoint = seekMap.getSeekPoints(midTimeUs).first
        assertEquals(midTimeUs, midPoint.timeUs)
        assertTrue(midPoint.position in firstSamplePos until streamLen)

        val roundTripUs = seekMap.getTimeUs(midPoint.position)
        assertTrue(abs(roundTripUs - midTimeUs) <= 1_000_000L)
    }

    @Test
    fun `FastLargeMp3Extractor skips huge ID3v2 via RESULT_SEEK and rejects bogus Chapter-1 Xing`() {
        val id3PayloadSize = 200_000 // > 128 KB threshold -> triggers RESULT_SEEK skip
        val id3TotalSize = 10 + id3PayloadSize
        val frameSize = 417
        val audioHeader = 0xFFFB9000.toInt() // MPEG1 Layer III, 128 kbps, 44100 Hz, stereo

        // Build Xing frame at frame 0 claiming only 500 frames (~13s) even though file reports 8.64 GB
        val xingFrame = ByteArray(frameSize)
        writeIntBE(xingFrame, 0, audioHeader)
        xingFrame[36] = 'X'.code.toByte()
        xingFrame[37] = 'i'.code.toByte()
        xingFrame[38] = 'n'.code.toByte()
        xingFrame[39] = 'g'.code.toByte()
        writeIntBE(xingFrame, 40, 0x03) // frameCount + dataSize flags
        writeIntBE(xingFrame, 44, 500)  // bogus Chapter-1 frame count
        writeIntBE(xingFrame, 48, 208_500)

        val audioFrames = ByteArray(frameSize * 6)
        for (k in 0 until 6) {
            writeIntBE(audioFrames, k * frameSize, audioHeader)
        }

        val backingHead = ByteArray(id3TotalSize + xingFrame.size + audioFrames.size)
        // ID3v2.3 header with synchsafe size = id3PayloadSize
        backingHead[0] = 'I'.code.toByte()
        backingHead[1] = 'D'.code.toByte()
        backingHead[2] = '3'.code.toByte()
        backingHead[3] = 3
        backingHead[4] = 0
        backingHead[5] = 0
        backingHead[6] = ((id3PayloadSize shr 21) and 0x7F).toByte()
        backingHead[7] = ((id3PayloadSize shr 14) and 0x7F).toByte()
        backingHead[8] = ((id3PayloadSize shr 7) and 0x7F).toByte()
        backingHead[9] = (id3PayloadSize and 0x7F).toByte()

        System.arraycopy(xingFrame, 0, backingHead, id3TotalSize, xingFrame.size)
        System.arraycopy(audioFrames, 0, backingHead, id3TotalSize + xingFrame.size, audioFrames.size)

        val virtualTotalLength = 8_640_000_000L // ~150 hours at 128 kbps
        val extractor = FastLargeMp3Extractor(
            probeReader = { _, dst, len ->
                // Return valid 128 kbps frames for multi-point probe windows
                var pos = 0
                while (pos + frameSize <= len) {
                    writeIntBE(dst, pos, audioHeader)
                    pos += frameSize
                }
                len
            }
        )

        val recordingOutput = RecordingExtractorOutput()
        extractor.init(recordingOutput)

        val input = VirtualHeadExtractorInput(backingHead, virtualTotalLength)
        assertTrue(extractor.sniff(input))

        val posHolder = PositionHolder()
        val firstRes = extractor.read(input, posHolder)
        assertEquals(Extractor.RESULT_SEEK, firstRes)
        assertEquals(id3TotalSize.toLong(), posHolder.position)

        // Simulate ProgressiveMediaPeriod re-opening directly at id3TotalSize
        input.seekTo(posHolder.position)
        val secondRes = extractor.read(input, posHolder)
        assertEquals(Extractor.RESULT_CONTINUE, secondRes)

        val seekMap = recordingOutput.seekMap
        assertNotNull(seekMap)
        assertTrue(seekMap!!.isSeekable)

        // Bogus Chapter-1 Xing (~13s) MUST be rejected; duration must reflect ~150 hours of audio payload
        val audioPayloadBytes = virtualTotalLength - id3TotalSize - frameSize
        val expected150HoursUs = (audioPayloadBytes * 8_000_000L) / 128_000L
        assertTrue(
            "Expected ~150h duration ($expected150HoursUs), got ${seekMap.durationUs}",
            abs(seekMap.durationUs - expected150HoursUs) < 1_000_000L
        )
        assertTrue(recordingOutput.track.emittedSamples >= 1)

        // Verify Jump-To 100:00:00 maps past 4.29 GB (64-bit safe)
        val targetJumpUs = 100L * 3_600L * 1_000_000L
        val seekPoint100h = seekMap.getSeekPoints(targetJumpUs).first
        assertTrue(
            "Expected 100h seek point past 4.29 GB, got ${seekPoint100h.position}",
            seekPoint100h.position > 4_294_967_296L && seekPoint100h.position < virtualTotalLength
        )

        // Verify post-seek resynchronization when landing mid-frame (offset + 17 bytes)
        val midFrameOffset = id3TotalSize.toLong() + frameSize + 17L
        extractor.seek(midFrameOffset, targetJumpUs)
        input.seekTo(midFrameOffset)
        val afterSeekRes = extractor.read(input, posHolder)
        assertEquals(Extractor.RESULT_CONTINUE, afterSeekRes)
        assertTrue(recordingOutput.track.emittedSamples >= 2)
    }

    private fun writeIntBE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    private class RecordingExtractorOutput : ExtractorOutput {
        var seekMap: SeekMap? = null
        val track = RecordingTrackOutput()
        override fun track(id: Int, type: Int): TrackOutput = track
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }
    }

    private class RecordingTrackOutput : TrackOutput {
        var format: Format? = null
        var emittedSamples = 0
        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(
            input: androidx.media3.common.DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int
        ): Int {
            val tmp = ByteArray(length)
            val n = input.read(tmp, 0, length)
            return n
        }

        override fun sampleData(
            data: androidx.media3.common.util.ParsableByteArray,
            length: Int,
            sampleDataPart: Int
        ) {
            data.skipBytes(length)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?
        ) {
            emittedSamples++
        }
    }

    private class VirtualHeadExtractorInput(
        private val head: ByteArray,
        private val virtualLength: Long
    ) : ExtractorInput {
        private var pos = 0L
        private var peekPos = 0L

        fun seekTo(newPos: Long) {
            pos = newPos
            peekPos = newPos
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val avail = (head.size.toLong() - pos).toInt()
            if (avail <= 0) return C.RESULT_END_OF_INPUT
            val n = minOf(length, avail)
            System.arraycopy(head, pos.toInt(), buffer, offset, n)
            pos += n
            peekPos = pos
            return n
        }

        override fun readFully(buffer: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (pos + length > head.size) return false
            System.arraycopy(head, pos.toInt(), buffer, offset, length)
            pos += length
            peekPos = pos
            return true
        }

        override fun readFully(buffer: ByteArray, offset: Int, length: Int) {
            readFully(buffer, offset, length, false)
        }

        override fun skip(length: Int): Int {
            val avail = (head.size.toLong() - pos).toInt()
            val n = minOf(length, avail)
            pos += n
            peekPos = pos
            return n
        }

        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
            if (pos + length > head.size) return false
            pos += length
            peekPos = pos
            return true
        }

        override fun skipFully(length: Int) {
            skipFully(length, false)
        }

        override fun peek(buffer: ByteArray, offset: Int, length: Int): Int {
            val avail = (head.size.toLong() - peekPos).toInt()
            if (avail <= 0) return C.RESULT_END_OF_INPUT
            val n = minOf(length, avail)
            System.arraycopy(head, peekPos.toInt(), buffer, offset, n)
            peekPos += n
            return n
        }

        override fun peekFully(buffer: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (peekPos + length > head.size) return false
            System.arraycopy(head, peekPos.toInt(), buffer, offset, length)
            peekPos += length
            return true
        }

        override fun peekFully(buffer: ByteArray, offset: Int, length: Int) {
            peekFully(buffer, offset, length, false)
        }

        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
            if (peekPos + length > head.size) return false
            peekPos += length
            return true
        }

        override fun advancePeekPosition(length: Int) {
            advancePeekPosition(length, false)
        }

        override fun resetPeekPosition() {
            peekPos = pos
        }

        override fun getPeekPosition(): Long = peekPos
        override fun getPosition(): Long = pos
        override fun getLength(): Long = virtualLength

        override fun <E : Throwable> setRetryPosition(position: Long, e: E) {
            pos = position
            peekPos = position
            throw e
        }
    }
}
