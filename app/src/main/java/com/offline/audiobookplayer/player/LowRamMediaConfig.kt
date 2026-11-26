package com.offline.audiobookplayer.player

import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@OptIn(UnstableApi::class)
object LowRamMediaConfig {
    // Tuned for instant Jump-To & Seek on 4 GB RAM devices:
    // After a seek ExoPlayer only needs 500 ms of decoded audio (~8 KB) before playback resumes.
    const val MIN_BUFFER_MS = 15_000
    const val MAX_BUFFER_MS = 30_000
    const val BUFFER_FOR_PLAYBACK_MS = 400
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 500
    const val TARGET_BUFFER_BYTES = 2 * 1024 * 1024 // ~2 MB strict cap for 4 GB RAM devices

    fun buildLowRamLoadControl(): DefaultLoadControl {
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
    }
}

/**
 * Configures MP3 extraction for massive >4 GB / 150-hour files:
 * - Default mode uses [FastLargeMp3Extractor]: zero-allocation ID3v2 skip, 64-bit Xing TOC scaling,
 *   corrupt/concatenated Xing rejection, and O(1) multi-point bitrate calibration via [SafFdCache].
 * - Optional VBR index seeking fallback is restricted to files < 256 MB so giant 150-hour MP3s
 *   never trigger multi-gigabyte linear scans or OOMs even if the user enables the toggle.
 */
@OptIn(UnstableApi::class)
class AudiobookExtractorsFactory(
    private val fdCache: SafFdCache? = null
) : ExtractorsFactory {

    @Volatile
    var useVbrIndexSeeking: Boolean = false

    private val fallbackFactory = DefaultExtractorsFactory()

    private val vbrIndexFallbackFactory = DefaultExtractorsFactory().setMp3ExtractorFlags(
        Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING or
            Mp3Extractor.FLAG_DISABLE_ID3_METADATA
    )

    override fun createExtractors(): Array<Extractor> {
        return createExtractors(Uri.EMPTY, emptyMap())
    }

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>
    ): Array<Extractor> {
        val targetUri = uri.takeUnless { it == Uri.EMPTY }
        if (useVbrIndexSeeking) {
            val fileSize = targetUri?.let { fdCache?.acquire(it)?.fileSize } ?: -1L
            if (fileSize in 1L until VBR_INDEX_MAX_SAFE_BYTES) {
                return vbrIndexFallbackFactory.createExtractors(uri, responseHeaders)
            }
        }

        val fastMp3 = FastLargeMp3Extractor(uri = targetUri, fdCache = fdCache)
        val others = fallbackFactory.createExtractors(uri, responseHeaders)
            .filterNot { it is Mp3Extractor }
        return arrayOf(fastMp3, *others.toTypedArray())
    }

    companion object {
        private const val VBR_INDEX_MAX_SAFE_BYTES = 256L * 1024L * 1024L
    }
}

/**
 * Prevents Media3 / MediaSession notifications from decoding oversized embedded cover artwork into RAM.
 */
@OptIn(UnstableApi::class)
class NoOpBitmapLoader : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = false

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        return Futures.immediateFailedFuture(UnsupportedOperationException("Artwork loading disabled for low RAM"))
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        return Futures.immediateFailedFuture(UnsupportedOperationException("Artwork loading disabled for low RAM"))
    }

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? = null
}
