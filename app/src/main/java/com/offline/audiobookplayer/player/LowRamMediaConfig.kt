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
    const val MIN_BUFFER_MS = 15_000
    const val MAX_BUFFER_MS = 30_000
    const val BUFFER_FOR_PLAYBACK_MS = 1_000
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_000
    const val TARGET_BUFFER_BYTES = 2 * 1024 * 1024 // ~2 MB cap for low-RAM devices

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
 * Configures MP3 extraction for massive >4 GB files (8-10 GB):
 * - Default CBR seeking + disabled ID3 metadata avoids 32-bit Xing header overflows,
 *   prevents multi-gigabyte scans, and blocks embedded artwork OOMs.
 * - Optional VBR index seeking fallback can be toggled in Settings.
 */
@OptIn(UnstableApi::class)
class AudiobookExtractorsFactory : ExtractorsFactory {

    @Volatile
    var useVbrIndexSeeking: Boolean = false

    private val cbrFastFactory = DefaultExtractorsFactory().setMp3ExtractorFlags(
        Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS or
            Mp3Extractor.FLAG_DISABLE_ID3_METADATA
    )

    private val vbrIndexFallbackFactory = DefaultExtractorsFactory().setMp3ExtractorFlags(
        Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING or
            Mp3Extractor.FLAG_DISABLE_ID3_METADATA
    )

    override fun createExtractors(): Array<Extractor> {
        return activeFactory().createExtractors()
    }

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>
    ): Array<Extractor> {
        return activeFactory().createExtractors(uri, responseHeaders)
    }

    private fun activeFactory(): DefaultExtractorsFactory {
        return if (useVbrIndexSeeking) vbrIndexFallbackFactory else cbrFastFactory
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
