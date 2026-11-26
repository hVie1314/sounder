package com.offline.audiobookplayer.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.extractor.ConstantBitrateSeekMap
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.MpegAudioUtil
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import java.io.EOFException
import java.io.IOException
import java.math.RoundingMode
import kotlin.math.roundToLong

@OptIn(UnstableApi::class)
internal interface Mp3TimeSeekMap : SeekMap {
    fun getTimeUs(position: Long): Long
    val averageBitrateBps: Int
}

@OptIn(UnstableApi::class)
internal class SafeConstantBitrateSeekMap(
    inputLength: Long,
    firstFrameBytePosition: Long,
    bitrate: Int,
    frameSize: Int,
    allowSeeksIfLengthUnknown: Boolean
) : ConstantBitrateSeekMap(
    inputLength,
    firstFrameBytePosition,
    bitrate,
    frameSize,
    allowSeeksIfLengthUnknown
), Mp3TimeSeekMap {
    override val averageBitrateBps: Int = bitrate
    override fun getTimeUs(position: Long): Long = getTimeUsAtPosition(position)
}

/**
 * 64-bit safe Xing TOC SeekMap.
 *
 * Stock Media3 `XingSeeker` trusts the 32-bit `XingFrame.dataSize` field which overflows at 4.29 GB
 * (cutting off playback and failing seeks past 4 GB on 150-hour MP3 files).
 * This implementation keeps Xing's frame-count duration and 100-entry relative TOC (percentages 0..255),
 * but scales all seek byte offsets against the true 64-bit [trueDataSize] (`fileLength - audioStartPosition`).
 */
@OptIn(UnstableApi::class)
internal class SafeXingSeekMap(
    private val audioStartPosition: Long,
    private val durationUs: Long,
    override val averageBitrateBps: Int,
    private val trueDataSize: Long,
    private val tableOfContents: LongArray
) : Mp3TimeSeekMap {

    override fun isSeekable(): Boolean = true

    override fun getDurationUs(): Long = durationUs

    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        val clampedTimeUs = timeUs.coerceIn(0L, durationUs)
        val percent = (clampedTimeUs.toDouble() * 100.0) / durationUs.toDouble()
        val scaledByteFraction = when {
            percent <= 0.0 -> 0.0
            percent >= 100.0 -> 256.0
            else -> {
                val prevIndex = percent.toInt().coerceIn(0, 99)
                val prevValue = tableOfContents[prevIndex].toDouble()
                val nextValue = if (prevIndex == 99) 256.0 else tableOfContents[prevIndex + 1].toDouble()
                val remainder = percent - prevIndex.toDouble()
                prevValue + remainder * (nextValue - prevValue)
            }
        }

        val maxOffset = (trueDataSize - 1L).coerceAtLeast(0L)
        val relativeOffset = ((scaledByteFraction / 256.0) * trueDataSize.toDouble())
            .roundToLong()
            .coerceIn(0L, maxOffset)

        return SeekMap.SeekPoints(SeekPoint(clampedTimeUs, audioStartPosition + relativeOffset))
    }

    override fun getTimeUs(position: Long): Long {
        val relativePos = position - audioStartPosition
        if (relativePos <= 0L || trueDataSize <= 0L) {
            return 0L
        }
        val scaledPos = (relativePos.toDouble() * 256.0) / trueDataSize.toDouble()
        if (scaledPos >= 256.0) return durationUs
        if (scaledPos <= 0.0) return 0L

        val prevIndex = Util.binarySearchFloor(
            tableOfContents,
            scaledPos.toLong(),
            /* inclusive = */ true,
            /* stayInBounds = */ true
        ).coerceIn(0, 99)

        val prevTimeUs = (durationUs * prevIndex.toLong()) / 100L
        val nextTimeUs = (durationUs * (prevIndex + 1).toLong()) / 100L
        val prevTable = tableOfContents[prevIndex]
        val nextTable = if (prevIndex == 99) 256L else tableOfContents[prevIndex + 1]

        val fraction = if (prevTable == nextTable) {
            0.0
        } else {
            (scaledPos - prevTable.toDouble()) / (nextTable - prevTable).toDouble()
        }
        return (prevTimeUs + (fraction * (nextTimeUs - prevTimeUs).toDouble()).roundToLong())
            .coerceIn(0L, durationUs)
    }

    companion object {
        fun isValidToc(toc: LongArray?): Boolean {
            if (toc == null || toc.size != 100) return false
            var prev = -1L
            for (v in toc) {
                if (v < 0L || v > 256L || v < prev) return false
                prev = v
            }
            return toc[99] > toc[0] && toc[50] > 0L
        }
    }
}

/**
 * O(1) piecewise-linear SeekMap constructed from sparse 4 KB `pread64` samples across a giant VBR
 * or concatenated multi-chapter MP3 that lacks a valid Xing/VBRI seek header.
 *
 * Sampling 15–17 evenly spaced windows across a 10 GB file reads only ~64 KB total (< 5 ms),
 * avoiding the minutes-long multi-gigabyte linear scan of `IndexSeeker` AND avoiding wrong durations
 * caused by assuming a quiet 32 kbps opening frame represents an entire 150-hour audiobook.
 */
@OptIn(UnstableApi::class)
internal class PiecewiseLinearMp3SeekMap(
    private val firstSamplePosition: Long,
    private val streamLength: Long,
    private val anchorOffsets: LongArray,
    private val anchorBitratesBps: IntArray,
    private val firstFrameSize: Int
) : Mp3TimeSeekMap {

    private val anchorTimesUs: LongArray
    private val totalDurationUs: Long
    override val averageBitrateBps: Int

    init {
        require(anchorOffsets.size >= 2 && anchorOffsets.size == anchorBitratesBps.size)
        val times = LongArray(anchorOffsets.size)
        var cumulativeUs = 0L
        for (i in 0 until anchorOffsets.lastIndex) {
            val deltaBytes = (anchorOffsets[i + 1] - anchorOffsets[i]).coerceAtLeast(0L)
            val segBitrate = ((anchorBitratesBps[i].toLong() + anchorBitratesBps[i + 1].toLong()) / 2L)
                .coerceAtLeast(8_000L)
            cumulativeUs += Util.scaleLargeValue(deltaBytes, 8_000_000L, segBitrate, RoundingMode.HALF_UP)
            times[i + 1] = cumulativeUs
        }
        anchorTimesUs = times
        totalDurationUs = cumulativeUs.coerceAtLeast(1L)

        val totalDataBytes = (streamLength - firstSamplePosition).coerceAtLeast(1L)
        val avg = Util.scaleLargeValue(totalDataBytes, 8_000_000L, totalDurationUs, RoundingMode.HALF_UP)
        averageBitrateBps = avg.coerceIn(8_000L, Int.MAX_VALUE.toLong()).toInt()
    }

    override fun isSeekable(): Boolean = true

    override fun getDurationUs(): Long = totalDurationUs

    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        val clampedTimeUs = timeUs.coerceIn(0L, totalDurationUs)
        val idx = Util.binarySearchFloor(
            anchorTimesUs,
            clampedTimeUs,
            /* inclusive = */ true,
            /* stayInBounds = */ true
        ).coerceIn(0, anchorOffsets.lastIndex - 1)

        val t0 = anchorTimesUs[idx]
        val t1 = anchorTimesUs[idx + 1]
        val b0 = anchorOffsets[idx]
        val b1 = anchorOffsets[idx + 1]

        val rawBytePos = if (t1 <= t0) {
            b0
        } else {
            val fraction = (clampedTimeUs - t0).toDouble() / (t1 - t0).toDouble()
            b0 + (fraction * (b1 - b0).toDouble()).roundToLong()
        }
        val maxPos = (streamLength - firstFrameSize.toLong()).coerceAtLeast(firstSamplePosition)
        val clampedPos = rawBytePos.coerceIn(firstSamplePosition, maxPos)
        return SeekMap.SeekPoints(SeekPoint(clampedTimeUs, clampedPos))
    }

    override fun getTimeUs(position: Long): Long {
        if (position <= firstSamplePosition) return 0L
        if (position >= streamLength) return totalDurationUs

        val idx = Util.binarySearchFloor(
            anchorOffsets,
            position,
            /* inclusive = */ true,
            /* stayInBounds = */ true
        ).coerceIn(0, anchorOffsets.lastIndex - 1)

        val b0 = anchorOffsets[idx]
        val b1 = anchorOffsets[idx + 1]
        val t0 = anchorTimesUs[idx]
        val t1 = anchorTimesUs[idx + 1]

        if (b1 <= b0) return t0
        val fraction = (position - b0).toDouble() / (b1 - b0).toDouble()
        return (t0 + (fraction * (t1 - t0).toDouble()).roundToLong()).coerceIn(0L, totalDurationUs)
    }
}

/**
 * High-speed, 64-bit safe MP3 Extractor engineered for massive 8–10 GB / 150-hour audiobooks on low-RAM Android:
 *
 * Key optimizations over stock Media3 `Mp3Extractor`:
 * 1. **Zero-allocation ID3v2 skip**: Skips arbitrary-size ID3v2 tags (including huge embedded cover art)
 *    via `RESULT_SEEK` / chunked skip without allocating `new byte[tagLength]` on the heap (prevents OOM).
 * 2. **Buffered in-RAM frame synchronization**: Scans a 16 KB chunk in memory after every seek instead
 *    of calling `skipFully(1)` + 4-byte peeks byte-by-byte through SAF/FUSE.
 * 3. **32-bit Xing overflow & corrupt header immunity**: Validates `Xing`/`Info`/`VBRI` headers in 64-bit
 *    arithmetic, rejects impossible/concatenated Chapter-1 Xing durations, and scales valid Xing TOCs
 *    against the true 64-bit file length (`input.length`).
 * 4. **O(1) multi-point bitrate calibration**: When no valid Xing/VBRI header exists, samples 15 tiny
 *    4 KB windows via `Os.pread` (~3–5 ms) to detect VBR or multi-bitrate concatenated MP3s cleanly.
 * 5. **Mid-file ID3v2 / chapter boundary resilience**: Automatically skips embedded ID3v2 tags between
 *    concatenated MP3 chapters when resynchronizing instead of failing at `MAX_SYNC_BYTES`.
 */
@OptIn(UnstableApi::class)
class FastLargeMp3Extractor(
    private val probeReader: ((offset: Long, dst: ByteArray, length: Int) -> Int)? = null
) : Extractor {

    constructor(uri: Uri?, fdCache: SafFdCache?) : this(
        probeReader = if (uri != null && fdCache != null) {
            { offset, dst, length -> fdCache.preadAt(uri, offset, dst, 0, length) }
        } else {
            null
        }
    )

    private val scratch = ParsableByteArray(10)
    private val syncBuffer = ByteArray(SYNC_CHUNK_BYTES)
    private val synchronizedHeader = MpegAudioUtil.Header()

    private var extractorOutput: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null

    private var synchronizedHeaderData: Int = 0
    private var initialHeaderMask: Int = 0
    private var basisTimeUs: Long = C.TIME_UNSET
    private var samplesRead: Long = 0L
    private var firstSamplePosition: Long = 0L
    private var sampleBytesRemaining: Int = 0
    private var seeker: Mp3TimeSeekMap? = null

    override fun sniff(input: ExtractorInput): Boolean {
        input.resetPeekPosition()
        val id3Bytes = measureLeadingId3Bytes(input)
        val probeLen = MAX_SNIFF_BYTES
        val buf = ByteArray(probeLen)
        val filled: Int

        if (id3Bytes > 65_536L && probeReader != null) {
            // Avoid reading multi-MB embedded artwork into ExtractorInput's heap peekBuffer during sniff.
            filled = try {
                probeReader.invoke(id3Bytes, buf, probeLen)
            } catch (_: IOException) {
                -1
            }
        } else {
            if (id3Bytes > 0L) {
                if (id3Bytes > Int.MAX_VALUE.toLong() ||
                    !input.advancePeekPosition(id3Bytes.toInt(), /* allowEndOfInput = */ true)
                ) {
                    input.resetPeekPosition()
                    return false
                }
            }
            filled = peekUpTo(input, buf, probeLen)
            input.resetPeekPosition()
        }

        if (filled < 16) return false

        for (i in 0..(filled - 4)) {
            val b0 = buf[i].toInt() and 0xFF
            val b1 = buf[i + 1].toInt() and 0xFF
            if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) continue

            val headerData = readIntBE(buf, i)
            val frameSize = MpegAudioUtil.getFrameSize(headerData)
            if (frameSize < 4) continue

            if (verifyConsecutiveFramesInBuffer(buf, filled, i, headerData, frameSize, REQUIRED_SYNC_FRAMES)) {
                return true
            }
        }
        return false
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    override fun seek(position: Long, timeUs: Long) {
        synchronizedHeaderData = 0
        basisTimeUs = C.TIME_UNSET
        samplesRead = 0L
        sampleBytesRemaining = 0
    }

    override fun release() = Unit

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val output = extractorOutput ?: return Extractor.RESULT_END_OF_INPUT
        val track = trackOutput ?: return Extractor.RESULT_END_OF_INPUT

        // Fast zero-allocation ID3v2 skip when starting at offset 0.
        if (seeker == null && input.position == 0L) {
            val id3EndOffset = measureLeadingId3Bytes(input)
            if (id3EndOffset > 0L) {
                val streamLen = input.length
                if (id3EndOffset > SEEK_SKIP_ID3_THRESHOLD_BYTES &&
                    streamLen != C.LENGTH_UNSET.toLong() &&
                    id3EndOffset < streamLen
                ) {
                    seekPosition.position = id3EndOffset
                    return Extractor.RESULT_SEEK
                }
                if (!skipFullyOrEof(input, id3EndOffset)) {
                    return Extractor.RESULT_END_OF_INPUT
                }
            }
        }

        if (synchronizedHeaderData == 0) {
            val syncResult = synchronize(input, seekPosition)
            if (syncResult != SYNC_OK) {
                return syncResult
            }
        }

        if (seeker == null) {
            val builtSeeker = computeSeeker(input)
            seeker = builtSeeker
            output.seekMap(builtSeeker)

            val bitrateForFormat = builtSeeker.averageBitrateBps
            val formatBuilder = Format.Builder()
                .setContainerMimeType(MimeTypes.AUDIO_MPEG)
                .setSampleMimeType(synchronizedHeader.mimeType)
                .setMaxInputSize(MpegAudioUtil.MAX_FRAME_SIZE_BYTES)
                .setChannelCount(synchronizedHeader.channels)
                .setSampleRate(synchronizedHeader.sampleRate)
            if (bitrateForFormat != C.RATE_UNSET_INT && bitrateForFormat > 0) {
                formatBuilder.setAverageBitrate(bitrateForFormat)
            }
            track.format(formatBuilder.build())
            val durationUs = builtSeeker.durationUs
            if (durationUs != C.TIME_UNSET) {
                track.durationUs(durationUs)
            }
            firstSamplePosition = input.position
        } else if (firstSamplePosition > 0L && input.position < firstSamplePosition) {
            val toSkip = firstSamplePosition - input.position
            if (!skipFullyOrEof(input, toSkip)) {
                return Extractor.RESULT_END_OF_INPUT
            }
        }

        return readSample(input, track)
    }

    private fun readSample(input: ExtractorInput, track: TrackOutput): Int {
        if (sampleBytesRemaining == 0) {
            input.resetPeekPosition()
            if (!input.peekFully(scratch.data, 0, 4, /* allowEndOfInput = */ true)) {
                return Extractor.RESULT_END_OF_INPUT
            }
            scratch.position = 0
            val headerData = scratch.readInt()
            if (!headersMatch(headerData, synchronizedHeaderData) ||
                MpegAudioUtil.getFrameSize(headerData) == -1
            ) {
                // Frame stream desynchronized (e.g. concatenated chapter boundary or mid-file ID3).
                if (!skipFullyOrEof(input, 1)) {
                    return Extractor.RESULT_END_OF_INPUT
                }
                synchronizedHeaderData = 0
                return Extractor.RESULT_CONTINUE
            }

            synchronizedHeader.setForHeaderData(headerData)
            if (basisTimeUs == C.TIME_UNSET) {
                basisTimeUs = seeker?.getTimeUs(input.position) ?: 0L
                samplesRead = 0L
            }
            sampleBytesRemaining = synchronizedHeader.frameSize
        }

        val bytesAppended = track.sampleData(input, sampleBytesRemaining, /* allowEndOfInput = */ true)
        if (bytesAppended == C.RESULT_END_OF_INPUT) {
            return Extractor.RESULT_END_OF_INPUT
        }
        sampleBytesRemaining -= bytesAppended
        if (sampleBytesRemaining > 0) {
            return Extractor.RESULT_CONTINUE
        }

        val sampleTimeUs = basisTimeUs + (samplesRead * 1_000_000L) / synchronizedHeader.sampleRate.toLong()
        track.sampleMetadata(
            sampleTimeUs,
            C.BUFFER_FLAG_KEY_FRAME,
            synchronizedHeader.frameSize,
            /* offset = */ 0,
            /* encryptionData = */ null
        )
        samplesRead += synchronizedHeader.samplesPerFrame.toLong()
        sampleBytesRemaining = 0
        return Extractor.RESULT_CONTINUE
    }

    private fun synchronize(input: ExtractorInput, seekPosition: PositionHolder): Int {
        var scannedBytes = 0
        val expectedMask = initialHeaderMask
        val minFramesNeeded = if (seeker == null) REQUIRED_SYNC_FRAMES else SEEK_SYNC_FRAMES

        while (scannedBytes < MAX_SYNC_BYTES) {
            input.resetPeekPosition()
            val filled = peekUpTo(input, syncBuffer, SYNC_CHUNK_BYTES)
            input.resetPeekPosition()
            if (filled < 4) {
                return Extractor.RESULT_END_OF_INPUT
            }

            var i = 0
            while (i <= filled - 4) {
                val b0 = syncBuffer[i].toInt() and 0xFF
                val b1 = syncBuffer[i + 1].toInt() and 0xFF
                val b2 = syncBuffer[i + 2].toInt() and 0xFF

                // Handle mid-file ID3v2 tags inside concatenated audiobooks seamlessly.
                if (b0 == 'I'.code && b1 == 'D'.code && b2 == '3'.code && i + 10 <= filled) {
                    val id3Bytes = parseId3TagSizeIfValid(syncBuffer, i)
                    if (id3Bytes > 0L) {
                        val totalSkip = i.toLong() + id3Bytes
                        val streamLen = input.length
                        if (totalSkip > SEEK_SKIP_ID3_THRESHOLD_BYTES &&
                            streamLen != C.LENGTH_UNSET.toLong() &&
                            input.position + totalSkip < streamLen
                        ) {
                            seekPosition.position = input.position + totalSkip
                            return Extractor.RESULT_SEEK
                        }
                        if (!skipFullyOrEof(input, totalSkip)) {
                            return Extractor.RESULT_END_OF_INPUT
                        }
                        scannedBytes = 0
                        break
                    }
                }

                if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                    val headerData = readIntBE(syncBuffer, i)
                    val frameSize = MpegAudioUtil.getFrameSize(headerData)
                    if (frameSize >= 4 &&
                        (expectedMask == 0 || headersMatch(headerData, expectedMask))
                    ) {
                        if (i > 0 && !skipFullyOrEof(input, i.toLong())) {
                            return Extractor.RESULT_END_OF_INPUT
                        }
                        val matched = verifyConsecutiveFramesOnInput(
                            input = input,
                            firstHeaderData = headerData,
                            firstFrameSize = frameSize,
                            requiredFrames = minFramesNeeded,
                            allowShortAtEof = (seeker != null)
                        )
                        if (matched) {
                            input.resetPeekPosition()
                            synchronizedHeaderData = headerData
                            synchronizedHeader.setForHeaderData(headerData)
                            if (initialHeaderMask == 0) {
                                initialHeaderMask = headerData
                            }
                            return SYNC_OK
                        }
                        input.resetPeekPosition()
                        if (!skipFullyOrEof(input, 1L)) {
                            return Extractor.RESULT_END_OF_INPUT
                        }
                        scannedBytes += i + 1
                        break
                    }
                }
                i++
            }

            if (i > filled - 4) {
                // Keep 10 overlap bytes so an ID3 header straddling chunk boundaries is never split.
                val advance = (filled - 10).coerceAtLeast(1)
                if (!skipFullyOrEof(input, advance.toLong())) {
                    return Extractor.RESULT_END_OF_INPUT
                }
                scannedBytes += advance
            }
        }
        return Extractor.RESULT_END_OF_INPUT
    }

    private fun verifyConsecutiveFramesOnInput(
        input: ExtractorInput,
        firstHeaderData: Int,
        firstFrameSize: Int,
        requiredFrames: Int,
        allowShortAtEof: Boolean
    ): Boolean {
        input.resetPeekPosition()
        var validCount = 1
        var bytesToNextFrame = firstFrameSize
        val hdrBytes = ByteArray(4)

        while (validCount < requiredFrames) {
            if (!input.advancePeekPosition(bytesToNextFrame, /* allowEndOfInput = */ true)) {
                return allowShortAtEof && validCount >= 1
            }
            if (!input.peekFully(hdrBytes, 0, 4, /* allowEndOfInput = */ true)) {
                return allowShortAtEof && validCount >= 1
            }
            val nextHeader = readIntBE(hdrBytes, 0)
            if (!headersMatch(nextHeader, firstHeaderData)) {
                return false
            }
            val nextSize = MpegAudioUtil.getFrameSize(nextHeader)
            if (nextSize < 4) {
                return false
            }
            bytesToNextFrame = nextSize - 4
            validCount++
        }
        return true
    }

    private fun computeSeeker(input: ExtractorInput): Mp3TimeSeekMap {
        val headerPos = input.position
        val firstFrameSize = synchronizedHeader.frameSize.coerceAtLeast(4)
        val firstFrameBytes = ByteArray(firstFrameSize)
        val hasFullFirstFrame = input.peekFully(firstFrameBytes, 0, firstFrameSize, /* allowEndOfInput = */ true)
        input.resetPeekPosition()

        var parsedSeekHeader: ParsedSeekFrame? = null
        if (hasFullFirstFrame) {
            parsedSeekHeader = parseSeekFrameIfPresent(synchronizedHeader, firstFrameBytes)
            if (parsedSeekHeader != null) {
                // Skip the silent Xing / Info / VBRI header frame so audio begins cleanly on frame #2.
                skipFullyOrEof(input, firstFrameSize.toLong())
                val nextHdr = ByteArray(4)
                if (input.peekFully(nextHdr, 0, 4, /* allowEndOfInput = */ true)) {
                    val h = readIntBE(nextHdr, 0)
                    if (headersMatch(h, synchronizedHeaderData) && MpegAudioUtil.getFrameSize(h) >= 4) {
                        synchronizedHeaderData = h
                        synchronizedHeader.setForHeaderData(h)
                    }
                }
                input.resetPeekPosition()
            }
        }

        val audioStartPos = input.position
        val streamLength = input.length
        val trueDataSize = if (streamLength != C.LENGTH_UNSET.toLong() && streamLength > audioStartPos) {
            streamLength - audioStartPos
        } else {
            -1L
        }

        // 1. Validate and use Xing / Info / VBRI duration (with 64-bit trueDataSize fix!)
        if (parsedSeekHeader != null && parsedSeekHeader.frameCount > 0L) {
            val sampleCount = parsedSeekHeader.frameCount * synchronizedHeader.samplesPerFrame.toLong() - 1L
            if (sampleCount > 0L && synchronizedHeader.sampleRate > 0) {
                val xingDurationUs = Util.sampleCountToDurationUs(sampleCount, synchronizedHeader.sampleRate)
                if (xingDurationUs > 0L) {
                    val impliedBitrate = if (trueDataSize > 0L) {
                        Util.scaleLargeValue(trueDataSize, 8_000_000L, xingDurationUs, RoundingMode.HALF_UP)
                    } else {
                        synchronizedHeader.bitrate.toLong()
                    }
                    // Sanity check rejects concatenated Chapter-1 Xing headers (e.g. 30 min duration on 8 GB file).
                    if (impliedBitrate in MIN_VALID_MP3_BITRATE_BPS..MAX_VALID_MP3_BITRATE_BPS) {
                        val toc = parsedSeekHeader.toc
                        if (trueDataSize > 0L && SafeXingSeekMap.isValidToc(toc)) {
                            return SafeXingSeekMap(
                                audioStartPosition = audioStartPos,
                                durationUs = xingDurationUs,
                                averageBitrateBps = impliedBitrate.toInt(),
                                trueDataSize = trueDataSize,
                                tableOfContents = toc!!
                            )
                        }
                        if (trueDataSize > 0L) {
                            return SafeConstantBitrateSeekMap(
                                inputLength = streamLength,
                                firstFrameBytePosition = audioStartPos,
                                bitrate = impliedBitrate.toInt(),
                                frameSize = synchronizedHeader.frameSize,
                                allowSeeksIfLengthUnknown = true
                            )
                        }
                    }
                }
            }
        }

        // 2. No valid Xing/VBRI header: sample local frames + O(1) multi-point pread across file
        //    to accurately handle both CBR and headerless VBR / concatenated audiobooks.
        if (streamLength != C.LENGTH_UNSET.toLong() && trueDataSize > MIN_MULTI_POINT_PROBE_BYTES) {
            val sampled = buildMultiPointSeekMapIfNeeded(
                input = input,
                audioStartPos = audioStartPos,
                streamLength = streamLength,
                trueDataSize = trueDataSize
            )
            if (sampled != null) {
                return sampled
            }
        }

        // 3. Fallback: pure 64-bit ConstantBitrateSeekMap
        val safeBitrate = synchronizedHeader.bitrate.takeIf { it > 0 } ?: FALLBACK_BITRATE_BPS
        return SafeConstantBitrateSeekMap(
            inputLength = streamLength,
            firstFrameBytePosition = audioStartPos.coerceAtLeast(headerPos),
            bitrate = safeBitrate,
            frameSize = synchronizedHeader.frameSize,
            allowSeeksIfLengthUnknown = true
        )
    }

    private fun buildMultiPointSeekMapIfNeeded(
        input: ExtractorInput,
        audioStartPos: Long,
        streamLength: Long,
        trueDataSize: Long
    ): Mp3TimeSeekMap? {
        val reader = probeReader ?: return null
        val fallbackBitrate = synchronizedHeader.bitrate.takeIf { it > 0 } ?: FALLBACK_BITRATE_BPS

        // Measure local start bitrate from the first ~4 KB of real audio (already in 64 KB buffer).
        val startWindow = ByteArray(PROBE_WINDOW_BYTES)
        val startPeeked = peekUpTo(input, startWindow, PROBE_WINDOW_BYTES)
        input.resetPeekPosition()
        val startBitrate = measureAverageBitrateInWindow(
            buf = startWindow,
            length = startPeeked,
            expectedHeaderMask = synchronizedHeaderData
        ) ?: fallbackBitrate

        val offsets = ArrayList<Long>(MULTI_POINT_COUNT + 2)
        val bitrates = ArrayList<Int>(MULTI_POINT_COUNT + 2)
        offsets.add(audioStartPos)
        bitrates.add(startBitrate)

        val probeBuf = ByteArray(PROBE_WINDOW_BYTES)
        var allSameBitrate = (startBitrate == fallbackBitrate)

        for (step in 1..MULTI_POINT_COUNT) {
            val offset = audioStartPos + ((trueDataSize * step.toLong()) / (MULTI_POINT_COUNT + 1).toLong())
            if (offset + 64L >= streamLength) continue
            val read = try {
                reader.invoke(offset, probeBuf, PROBE_WINDOW_BYTES)
            } catch (_: IOException) {
                return null
            }
            if (read <= 0) break

            val localBitrate = measureAverageBitrateInWindow(
                buf = probeBuf,
                length = read,
                expectedHeaderMask = synchronizedHeaderData
            )
            if (localBitrate != null) {
                if (localBitrate != fallbackBitrate) {
                    allSameBitrate = false
                }
                offsets.add(offset)
                bitrates.add(localBitrate)
            }
        }

        if (offsets.size < 2) return null
        if (allSameBitrate) {
            return SafeConstantBitrateSeekMap(
                inputLength = streamLength,
                firstFrameBytePosition = audioStartPos,
                bitrate = fallbackBitrate,
                frameSize = synchronizedHeader.frameSize,
                allowSeeksIfLengthUnknown = true
            )
        }

        offsets.add(streamLength)
        bitrates.add(bitrates.last())

        return PiecewiseLinearMp3SeekMap(
            firstSamplePosition = audioStartPos,
            streamLength = streamLength,
            anchorOffsets = offsets.toLongArray(),
            anchorBitratesBps = bitrates.toIntArray(),
            firstFrameSize = synchronizedHeader.frameSize.coerceAtLeast(4)
        )
    }

    private fun measureAverageBitrateInWindow(
        buf: ByteArray,
        length: Int,
        expectedHeaderMask: Int
    ): Int? {
        if (length < 16) return null
        val tmpHeader = MpegAudioUtil.Header()

        for (i in 0..(length - 4)) {
            val b0 = buf[i].toInt() and 0xFF
            val b1 = buf[i + 1].toInt() and 0xFF
            if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) continue

            val firstHdr = readIntBE(buf, i)
            if (!headersMatch(firstHdr, expectedHeaderMask)) continue
            val firstSize = MpegAudioUtil.getFrameSize(firstHdr)
            if (firstSize < 4 || i + firstSize + 4 > length) continue

            val secondHdr = readIntBE(buf, i + firstSize)
            if (!headersMatch(secondHdr, expectedHeaderMask)) continue
            if (MpegAudioUtil.getFrameSize(secondHdr) < 4) continue

            var cursor = i
            var bitrateSum = 0L
            var count = 0
            while (cursor + 4 <= length && count < 8) {
                val h = readIntBE(buf, cursor)
                if (!headersMatch(h, expectedHeaderMask)) break
                if (!tmpHeader.setForHeaderData(h) || tmpHeader.frameSize < 4 || tmpHeader.bitrate <= 0) break
                bitrateSum += tmpHeader.bitrate.toLong()
                count++
                cursor += tmpHeader.frameSize
            }
            if (count > 0) {
                return (bitrateSum / count.toLong()).toInt()
            }
        }
        return null
    }

    private data class ParsedSeekFrame(
        val frameCount: Long,
        val dataSize: Long,
        val toc: LongArray?
    )

    private fun parseSeekFrameIfPresent(
        header: MpegAudioUtil.Header,
        frame: ByteArray
    ): ParsedSeekFrame? {
        val xingOffset = if ((header.version and 1) != 0) {
            if (header.channels != 1) 36 else 21
        } else {
            if (header.channels != 1) 21 else 13
        }

        if (frame.size >= xingOffset + 8) {
            val tag = readIntBE(frame, xingOffset)
            if (tag == SEEK_HEADER_XING || tag == SEEK_HEADER_INFO) {
                val flags = readIntBE(frame, xingOffset + 4)
                var cursor = xingOffset + 8
                var frameCount = -1L
                var dataSize = -1L
                var toc: LongArray? = null

                if ((flags and 1) != 0 && cursor + 4 <= frame.size) {
                    frameCount = readUnsignedIntBE(frame, cursor)
                    cursor += 4
                }
                if ((flags and 2) != 0 && cursor + 4 <= frame.size) {
                    dataSize = readUnsignedIntBE(frame, cursor)
                    cursor += 4
                }
                if ((flags and 4) != 0 && cursor + 100 <= frame.size) {
                    toc = LongArray(100) { idx -> (frame[cursor + idx].toInt() and 0xFF).toLong() }
                }
                return ParsedSeekFrame(frameCount = frameCount, dataSize = dataSize, toc = toc)
            }
        }

        if (frame.size >= 54) {
            val vbriTag = readIntBE(frame, 36)
            if (vbriTag == SEEK_HEADER_VBRI) {
                val dataSize = readUnsignedIntBE(frame, 46)
                val frameCount = readUnsignedIntBE(frame, 50)
                return ParsedSeekFrame(frameCount = frameCount, dataSize = dataSize, toc = null)
            }
        }
        return null
    }

    private fun measureLeadingId3Bytes(input: ExtractorInput): Long {
        var total = 0L
        var tags = 0
        val hdr = ByteArray(10)
        while (tags < MAX_STACKED_ID3_TAGS) {
            if (!input.peekFully(hdr, 0, 10, /* allowEndOfInput = */ true)) {
                break
            }
            val size = parseId3TagSizeIfValid(hdr, 0)
            if (size <= 0L) break
            total += size
            tags++
            val payloadRemaining = size - 10L
            if (payloadRemaining > Int.MAX_VALUE.toLong() ||
                !input.advancePeekPosition(payloadRemaining.toInt(), /* allowEndOfInput = */ true)
            ) {
                break
            }
        }
        input.resetPeekPosition()
        return total
    }

    private fun parseId3TagSizeIfValid(buf: ByteArray, offset: Int): Long {
        if (offset + 10 > buf.size) return 0L
        if (buf[offset] != 'I'.code.toByte() ||
            buf[offset + 1] != 'D'.code.toByte() ||
            buf[offset + 2] != '3'.code.toByte()
        ) {
            return 0L
        }
        val verMajor = buf[offset + 3].toInt() and 0xFF
        val verMinor = buf[offset + 4].toInt() and 0xFF
        if (verMajor == 0xFF || verMinor == 0xFF) return 0L

        val b6 = buf[offset + 6].toInt() and 0xFF
        val b7 = buf[offset + 7].toInt() and 0xFF
        val b8 = buf[offset + 8].toInt() and 0xFF
        val b9 = buf[offset + 9].toInt() and 0xFF
        if ((b6 or b7 or b8 or b9) and 0x80 != 0) return 0L

        val tagPayload = ((b6 and 0x7F) shl 21) or
            ((b7 and 0x7F) shl 14) or
            ((b8 and 0x7F) shl 7) or
            (b9 and 0x7F)
        val flags = buf[offset + 5].toInt() and 0xFF
        val footerSize = if ((flags and 0x10) != 0) 10L else 0L
        return 10L + tagPayload.toLong() + footerSize
    }

    private fun verifyConsecutiveFramesInBuffer(
        buf: ByteArray,
        length: Int,
        startOffset: Int,
        firstHeaderData: Int,
        firstFrameSize: Int,
        requiredFrames: Int
    ): Boolean {
        var pos = startOffset + firstFrameSize
        var validCount = 1
        while (validCount < requiredFrames) {
            if (pos + 4 > length) return false
            val h = readIntBE(buf, pos)
            if (!headersMatch(h, firstHeaderData)) return false
            val fSize = MpegAudioUtil.getFrameSize(h)
            if (fSize < 4) return false
            pos += fSize
            validCount++
        }
        return true
    }

    private fun peekUpTo(input: ExtractorInput, dst: ByteArray, maxBytes: Int): Int {
        var total = 0
        while (total < maxBytes) {
            val read = input.peek(dst, total, maxBytes - total)
            if (read == C.RESULT_END_OF_INPUT || read <= 0) break
            total += read
        }
        return total
    }

    private fun skipFullyOrEof(input: ExtractorInput, bytesToSkip: Long): Boolean {
        var remaining = bytesToSkip
        while (remaining > 0L) {
            val chunk = minOf(remaining, SKIP_CHUNK_MAX_BYTES).toInt()
            try {
                if (!input.skipFully(chunk, /* allowEndOfInput = */ true)) {
                    return false
                }
            } catch (_: EOFException) {
                return false
            }
            remaining -= chunk.toLong()
        }
        return true
    }

    companion object {
        private const val MPEG_AUDIO_HEADER_MASK = -128000 // 0xFFFE0C00 (sync + version + layer + sampleRate)
        private const val SEEK_HEADER_XING = 0x58696E67 // "Xing"
        private const val SEEK_HEADER_INFO = 0x496E666F // "Info"
        private const val SEEK_HEADER_VBRI = 0x56425249 // "VBRI"

        private const val MAX_SNIFF_BYTES = 32 * 1024
        private const val MAX_SYNC_BYTES = 256 * 1024
        private const val SYNC_CHUNK_BYTES = 16 * 1024
        private const val PROBE_WINDOW_BYTES = 4 * 1024
        private const val MULTI_POINT_COUNT = 15
        private const val MIN_MULTI_POINT_PROBE_BYTES = 512 * 1024L
        private const val SEEK_SKIP_ID3_THRESHOLD_BYTES = 128 * 1024L
        private const val SKIP_CHUNK_MAX_BYTES = 1024 * 1024L
        private const val MAX_STACKED_ID3_TAGS = 8

        private const val REQUIRED_SYNC_FRAMES = 4
        private const val SEEK_SYNC_FRAMES = 2

        private const val MIN_VALID_MP3_BITRATE_BPS = 8_000L
        private const val MAX_VALID_MP3_BITRATE_BPS = 450_000L
        private const val FALLBACK_BITRATE_BPS = 128_000

        private const val SYNC_OK = 1

        internal fun headersMatch(headerA: Int, headerB: Int): Boolean {
            return (headerA and MPEG_AUDIO_HEADER_MASK) == (headerB and MPEG_AUDIO_HEADER_MASK)
        }

        internal fun readIntBE(buf: ByteArray, offset: Int): Int {
            return ((buf[offset].toInt() and 0xFF) shl 24) or
                ((buf[offset + 1].toInt() and 0xFF) shl 16) or
                ((buf[offset + 2].toInt() and 0xFF) shl 8) or
                (buf[offset + 3].toInt() and 0xFF)
        }

        internal fun readUnsignedIntBE(buf: ByteArray, offset: Int): Long {
            return readIntBE(buf, offset).toLong() and 0xFFFF_FFFFL
        }
    }
}
