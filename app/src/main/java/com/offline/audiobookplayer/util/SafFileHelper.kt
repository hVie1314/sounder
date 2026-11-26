package com.offline.audiobookplayer.util

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.offline.audiobookplayer.data.repository.NewTrackInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Queries SAF URIs safely without loading audio file contents into memory.
 * Persists read URI grants via takePersistableUriPermission so access survives app restarts/reboots.
 */
object SafFileHelper {

    suspend fun resolvePickedFiles(
        context: Context,
        uris: List<Uri>
    ): List<NewTrackInput> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        uris.mapNotNull { uri ->
            takePersistableReadPermission(resolver, uri)
            val metadata = queryOpenableMetadata(resolver, uri)
            val name = metadata.displayName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "audio.mp3"
            if (!isMp3Candidate(name, resolver.getType(uri))) {
                return@mapNotNull null
            }
            NewTrackInput(
                uri = uri.toString(),
                fileName = name,
                fileSizeBytes = metadata.sizeBytes
            )
        }
    }

    suspend fun resolvePickedFolder(
        context: Context,
        treeUri: Uri
    ): List<NewTrackInput> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        takePersistableReadPermission(resolver, treeUri)

        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val collected = mutableListOf<NewTrackInput>()
        collectMp3Files(root, collected, maxDepth = 4)
        collected.sortWith { a, b -> naturalCompare(a.fileName, b.fileName) }
        collected
    }

    private fun collectMp3Files(
        dir: DocumentFile,
        out: MutableList<NewTrackInput>,
        maxDepth: Int
    ) {
        if (maxDepth < 0) return
        val children = dir.listFiles()
        for (child in children) {
            if (child.isDirectory) {
                collectMp3Files(child, out, maxDepth - 1)
            } else if (child.isFile) {
                val name = child.name ?: continue
                if (isMp3Candidate(name, child.type)) {
                    out.add(
                        NewTrackInput(
                            uri = child.uri.toString(),
                            fileName = name,
                            fileSizeBytes = child.length().coerceAtLeast(0L)
                        )
                    )
                }
            }
        }
    }

    private fun takePersistableReadPermission(resolver: ContentResolver, uri: Uri) {
        try {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some transient document providers do not support persistable grants; continue cleanly.
        }
    }

    private data class OpenableMeta(val displayName: String?, val sizeBytes: Long)

    private fun queryOpenableMetadata(resolver: ContentResolver, uri: Uri): OpenableMeta {
        return try {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use OpenableMeta(null, 0L)
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                OpenableMeta(name, size.coerceAtLeast(0L))
            } ?: OpenableMeta(null, 0L)
        } catch (_: Exception) {
            OpenableMeta(null, 0L)
        }
    }

    fun isMp3Candidate(fileName: String, mimeType: String?): Boolean {
        if (fileName.endsWith(".mp3", ignoreCase = true)) return true
        return mimeType.equals("audio/mpeg", ignoreCase = true) ||
            mimeType.equals("audio/mp3", ignoreCase = true)
    }

    /**
     * Natural alphanumeric comparator so "Tap 2.mp3" sorts before "Tap 10.mp3".
     */
    fun naturalCompare(first: String, second: String): Int {
        var i = 0
        var j = 0
        val len1 = first.length
        val len2 = second.length

        while (i < len1 && j < len2) {
            val c1 = first[i]
            val c2 = second[j]

            if (c1.isDigit() && c2.isDigit()) {
                val start1 = i
                while (i < len1 && first[i].isDigit()) i++
                val start2 = j
                while (j < len2 && second[j].isDigit()) j++

                val num1 = first.substring(start1, i).trimStart('0')
                val num2 = second.substring(start2, j).trimStart('0')

                if (num1.length != num2.length) {
                    return num1.length.compareTo(num2.length)
                }
                val numCmp = num1.compareTo(num2)
                if (numCmp != 0) return numCmp
            } else {
                val lowerCmp = c1.lowercaseChar().compareTo(c2.lowercaseChar())
                if (lowerCmp != 0) return lowerCmp
                i++
                j++
            }
        }
        return (len1 - i).compareTo(len2 - j)
    }
}
