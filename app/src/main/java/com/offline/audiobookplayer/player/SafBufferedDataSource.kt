package com.offline.audiobookplayer.player

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Collections

/**
 * Thread-safe, zero-leak LRU cache of open file descriptors for SAF `content://` and `file://` URIs.
 *
 * Why this speeds up Jump-To and Seek dramatically on 150-hour MP3s:
 * 1. Stock Media3 `ContentDataSource` calls `ContentResolver.openTypedAssetFileDescriptor()`
 *    (cross-process Binder IPC) AND `FileInputStream.skip(byteOffset)` on EVERY single seek.
 * 2. By caching the active open FD (LRU size 2) and reading via `Os.pread(fd, ..., offset)`
 *    (stateless 64-bit `pread64` syscall), seeking across 8–10 GB takes 0 Binder IPCs and 0 `skip()` calls.
 * 3. Uses `Os.fstat(fd).st_size` for the exact 64-bit file length rather than trusting 32-bit
 *    or `-1` lengths from third-party DocumentProviders.
 */
class SafFdCache(private val context: Context) {

    data class OpenHandle(
        val uriKey: String,
        val afd: AssetFileDescriptor?,
        val pfd: ParcelFileDescriptor?,
        val fd: FileDescriptor,
        val fileSize: Long
    ) {
        fun closeQuietly() {
            try {
                afd?.close()
            } catch (_: IOException) {
            }
            try {
                pfd?.close()
            } catch (_: IOException) {
            }
        }
    }

    private val appContext = context.applicationContext
    private val lock = Any()

    private val cache = object : LinkedHashMap<String, OpenHandle>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OpenHandle>?): Boolean {
            if (size > MAX_CACHED_FDS && eldest != null) {
                eldest.value.closeQuietly()
                return true
            }
            return false
        }
    }

    private val unseekableUris: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun acquire(uri: Uri): OpenHandle? {
        val normalized = uri.normalizeScheme()
        val key = normalized.toString()
        if (key in unseekableUris) return null

        synchronized(lock) {
            val existing = cache[key]
            if (existing != null && existing.fd.valid()) {
                return existing
            }
            if (existing != null) {
                cache.remove(key)
                existing.closeQuietly()
            }
        }

        val opened = openHandle(normalized, key) ?: return null
        synchronized(lock) {
            val raced = cache[key]
            if (raced != null && raced.fd.valid()) {
                opened.closeQuietly()
                return raced
            }
            cache[key] = opened
        }
        return opened
    }

    fun evict(uri: Uri) {
        val key = uri.normalizeScheme().toString()
        synchronized(lock) {
            cache.remove(key)?.closeQuietly()
        }
    }

    fun markUnseekable(uri: Uri) {
        val key = uri.normalizeScheme().toString()
        unseekableUris.add(key)
        synchronized(lock) {
            cache.remove(key)?.closeQuietly()
        }
    }

    fun releaseAll() {
        synchronized(lock) {
            cache.values.forEach { it.closeQuietly() }
            cache.clear()
        }
        unseekableUris.clear()
    }

    /**
     * Stateless 64-bit `pread64` helper usable both by [SafBufferedDataSource] and
     * [FastLargeMp3Extractor] (for O(1) multi-point VBR bitrate probing).
     * Returns total bytes read, or `-1` if the handle is non-seekable/unavailable.
     */
    @Throws(IOException::class)
    fun preadAt(uri: Uri, offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
        if (length <= 0 || offset < 0L) return 0
        val handle = acquire(uri) ?: return -1
        return try {
            preadFully(handle.fd, buffer, dstOffset, length, offset)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ESPIPE) {
                markUnseekable(uri)
                return -1
            }
            evict(uri)
            val reopened = acquire(uri) ?: return -1
            try {
                preadFully(reopened.fd, buffer, dstOffset, length, offset)
            } catch (retryErr: ErrnoException) {
                if (retryErr.errno == OsConstants.ESPIPE) {
                    markUnseekable(uri)
                    return -1
                }
                throw IOException(retryErr)
            }
        }
    }

    private fun openHandle(uri: Uri, key: String): OpenHandle? {
        val scheme = uri.scheme
        if (ContentResolver.SCHEME_FILE == scheme) {
            val path = uri.path ?: return null
            val file = File(path)
            if (!file.exists()) throw FileNotFoundException("File not found: $path")
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return verifySeekablePfd(key, afd = null, pfd = pfd)
        }
        if (ContentResolver.SCHEME_CONTENT != scheme) {
            return null
        }

        val resolver = appContext.contentResolver
        val afd = try {
            val extras = Bundle().apply {
                putBoolean(EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT, true)
            }
            resolver.openTypedAssetFileDescriptor(uri, "*/*", extras)
        } catch (_: Exception) {
            try {
                resolver.openAssetFileDescriptor(uri, "r")
            } catch (fnf: FileNotFoundException) {
                throw fnf
            } catch (_: Exception) {
                null
            }
        } ?: return null

        // Sub-file assets with non-zero startOffset should be handled by stock ContentDataSource.
        if (afd.startOffset != 0L) {
            try {
                afd.close()
            } catch (_: IOException) {
            }
            unseekableUris.add(key)
            return null
        }

        return verifySeekableAfd(key, afd)
    }

    private fun verifySeekableAfd(key: String, afd: AssetFileDescriptor): OpenHandle? {
        val fd = afd.fileDescriptor
        val size = try {
            val statSize = Os.fstat(fd).st_size
            when {
                statSize > 0L -> statSize
                afd.declaredLength > 0L -> afd.declaredLength
                else -> -1L
            }
        } catch (_: ErrnoException) {
            -1L
        }

        if (size <= 0L || !testPread(fd)) {
            try {
                afd.close()
            } catch (_: IOException) {
            }
            unseekableUris.add(key)
            return null
        }

        return OpenHandle(uriKey = key, afd = afd, pfd = null, fd = fd, fileSize = size)
    }

    private fun verifySeekablePfd(
        key: String,
        afd: AssetFileDescriptor?,
        pfd: ParcelFileDescriptor
    ): OpenHandle? {
        val fd = pfd.fileDescriptor
        val size = try {
            Os.fstat(fd).st_size
        } catch (_: ErrnoException) {
            -1L
        }
        if (size <= 0L || !testPread(fd)) {
            try {
                pfd.close()
            } catch (_: IOException) {
            }
            unseekableUris.add(key)
            return null
        }
        return OpenHandle(uriKey = key, afd = afd, pfd = pfd, fd = fd, fileSize = size)
    }

    private fun testPread(fd: FileDescriptor): Boolean {
        val probe = ByteArray(1)
        return try {
            Os.pread(fd, probe, 0, 1, 0L) >= 0
        } catch (_: Exception) {
            false
        }
    }

    @Throws(ErrnoException::class, InterruptedIOException::class)
    private fun preadFully(
        fd: FileDescriptor,
        buffer: ByteArray,
        dstOffset: Int,
        length: Int,
        fileOffset: Long
    ): Int {
        var totalRead = 0
        while (totalRead < length) {
            try {
                val n = Os.pread(
                    fd,
                    buffer,
                    dstOffset + totalRead,
                    length - totalRead,
                    fileOffset + totalRead
                )
                if (n <= 0) break
                totalRead += n
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) {
                    if (Thread.interrupted()) throw InterruptedIOException()
                    continue
                }
                throw e
            }
        }
        return totalRead
    }

    companion object {
        private const val MAX_CACHED_FDS = 2
        private const val EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT =
            "android.provider.extra.ACCEPT_ORIGINAL_MEDIA_FORMAT"
    }
}

/**
 * Buffered 64-bit random-access DataSource backed by [SafFdCache] + `Os.pread` + 64 KB read-ahead buffer.
 *
 * Stock Media3 `DefaultExtractorInput` often reads 1–4 bytes at a time when resynchronizing MP3 frames
 * after a seek. Without read-ahead buffering, each 1-byte read becomes a JNI + FUSE/SAF kernel call.
 * This class absorbs tiny reads in a 64 KB heap buffer (negligible on 4 GB RAM) and falls back cleanly
 * to Media3's [DefaultDataSource] for non-seekable streams or unsupported URI schemes.
 */
@OptIn(UnstableApi::class)
class SafBufferedDataSource(
    context: Context,
    private val fdCache: SafFdCache
) : DataSource {

    private val fallbackDataSource = DefaultDataSource.Factory(context.applicationContext).createDataSource()
    private val preadDataSource = PreadDataSource(fdCache)
    private var activeDataSource: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        preadDataSource.addTransferListener(transferListener)
        fallbackDataSource.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri.normalizeScheme()
        val scheme = uri.scheme
        if (scheme == ContentResolver.SCHEME_CONTENT || scheme == ContentResolver.SCHEME_FILE) {
            val handle = try {
                fdCache.acquire(uri)
            } catch (fnf: FileNotFoundException) {
                throw DataSourceException(fnf, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
            } catch (ioe: IOException) {
                throw DataSourceException(ioe, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            }
            if (handle != null) {
                activeDataSource = preadDataSource
                return preadDataSource.openWithHandle(dataSpec, uri, handle)
            }
        }
        activeDataSource = fallbackDataSource
        return fallbackDataSource.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val source = activeDataSource ?: return C.RESULT_END_OF_INPUT
        return source.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = activeDataSource?.uri

    override fun getResponseHeaders(): Map<String, List<String>> {
        return activeDataSource?.responseHeaders ?: emptyMap()
    }

    override fun close() {
        val source = activeDataSource ?: return
        activeDataSource = null
        source.close()
    }

    private class PreadDataSource(
        private val fdCache: SafFdCache
    ) : BaseDataSource(/* isNetwork = */ false) {

        private val readBuffer = ByteArray(READ_AHEAD_BUFFER_BYTES)
        private var activeHandle: SafFdCache.OpenHandle? = null
        private var currentUri: Uri? = null
        private var readPosition: Long = 0L
        private var bytesRemaining: Long = 0L
        private var bufferFileStart: Long = 0L
        private var bufferValidBytes: Int = 0
        private var opened: Boolean = false

        override fun open(dataSpec: DataSpec): Long {
            val uri = dataSpec.uri.normalizeScheme()
            val handle = fdCache.acquire(uri)
                ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            return openWithHandle(dataSpec, uri, handle)
        }

        fun openWithHandle(dataSpec: DataSpec, uri: Uri, handle: SafFdCache.OpenHandle): Long {
            if (dataSpec.position < 0L || dataSpec.position > handle.fileSize) {
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }

            currentUri = uri
            bufferValidBytes = 0
            bufferFileStart = 0L
            transferInitializing(dataSpec)
            activeHandle = handle
            readPosition = dataSpec.position

            val remainingInFile = handle.fileSize - dataSpec.position
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                minOf(remainingInFile, dataSpec.length)
            } else {
                remainingInFile
            }
            if (bytesRemaining < 0L) {
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }

            opened = true
            transferStarted(dataSpec)
            return bytesRemaining
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

            val toRead = minOf(length.toLong(), bytesRemaining).toInt()
            val delta = readPosition - bufferFileStart

            // Fast path: serve from 64 KB read-ahead buffer in memory (0 syscalls).
            if (delta in 0L until bufferValidBytes.toLong()) {
                val bufOffset = delta.toInt()
                val available = bufferValidBytes - bufOffset
                val copied = minOf(toRead, available)
                System.arraycopy(readBuffer, bufOffset, buffer, offset, copied)
                readPosition += copied
                bytesRemaining -= copied
                bytesTransferred(copied)
                return copied
            }

            val handle = activeHandle ?: return C.RESULT_END_OF_INPUT

            // Large read path: bypass internal buffer for >= 64 KB requests.
            if (toRead >= READ_AHEAD_BUFFER_BYTES) {
                val read = preadWithRetry(handle.fd, buffer, offset, toRead, readPosition)
                if (read == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
                readPosition += read
                bytesRemaining -= read
                bytesTransferred(read)
                return read
            }

            // Refill 64 KB read-ahead buffer at current readPosition.
            val fillSize = minOf(READ_AHEAD_BUFFER_BYTES.toLong(), bytesRemaining).toInt()
            val filled = preadWithRetry(handle.fd, readBuffer, 0, fillSize, readPosition)
            if (filled == C.RESULT_END_OF_INPUT) {
                bufferFileStart = 0L
                bufferValidBytes = 0
                return C.RESULT_END_OF_INPUT
            }

            bufferFileStart = readPosition
            bufferValidBytes = filled
            val copied = minOf(toRead, filled)
            System.arraycopy(readBuffer, 0, buffer, offset, copied)
            readPosition += copied
            bytesRemaining -= copied
            bytesTransferred(copied)
            return copied
        }

        private fun preadWithRetry(
            fd: FileDescriptor,
            dst: ByteArray,
            dstOffset: Int,
            length: Int,
            fileOffset: Long
        ): Int {
            while (true) {
                try {
                    val n = Os.pread(fd, dst, dstOffset, length, fileOffset)
                    return if (n <= 0) C.RESULT_END_OF_INPUT else n
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) {
                        if (Thread.interrupted()) throw InterruptedIOException()
                        continue
                    }
                    val uri = currentUri
                    if (uri != null) {
                        fdCache.evict(uri)
                        val reopened = fdCache.acquire(uri)
                        if (reopened != null) {
                            activeHandle = reopened
                            try {
                                val retryN = Os.pread(reopened.fd, dst, dstOffset, length, fileOffset)
                                return if (retryN <= 0) C.RESULT_END_OF_INPUT else retryN
                            } catch (retryErr: Exception) {
                                throw DataSourceException(retryErr, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
                            }
                        }
                    }
                    throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
                }
            }
        }

        override fun getUri(): Uri? = currentUri

        override fun close() {
            currentUri = null
            activeHandle = null
            bufferFileStart = 0L
            bufferValidBytes = 0
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(
        context: Context,
        private val fdCache: SafFdCache
    ) : DataSource.Factory {
        private val appContext = context.applicationContext
        override fun createDataSource(): DataSource = SafBufferedDataSource(appContext, fdCache)
    }

    companion object {
        const val READ_AHEAD_BUFFER_BYTES = 64 * 1024
    }
}
