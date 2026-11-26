package com.offline.audiobookplayer.data.repository

import androidx.room.withTransaction
import com.offline.audiobookplayer.data.local.TruyenCastDatabase
import com.offline.audiobookplayer.data.local.entity.PlaybackStateEntity
import com.offline.audiobookplayer.data.local.entity.QueueTrackEntity
import com.offline.audiobookplayer.player.PlaybackTimestampGuard
import com.offline.audiobookplayer.player.PositionSaveDecision
import com.offline.audiobookplayer.player.SaveReason
import com.offline.audiobookplayer.util.TimeFormatter
import kotlinx.coroutines.flow.Flow

data class NewTrackInput(
    val uri: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val durationMs: Long = 0L
)

data class RestoreSnapshot(
    val queue: List<QueueTrackEntity>,
    val activeQueueIndex: Int,
    val startPositionMs: Long
)

class AudiobookRepository(
    private val database: TruyenCastDatabase
) {
    private val queueDao = database.queueDao()
    private val playbackStateDao = database.playbackStateDao()

    val queueFlow: Flow<List<QueueTrackEntity>> = queueDao.observeQueue()
    val playbackStateFlow: Flow<PlaybackStateEntity?> = playbackStateDao.observePlaybackState()

    suspend fun getRestoreSnapshot(): RestoreSnapshot? {
        val queue = queueDao.getQueueSnapshot()
        if (queue.isEmpty()) return null

        val savedState = playbackStateDao.getPlaybackState()
        val matchedIndex = savedState?.activeTrackUri?.let { uri ->
            queue.indexOfFirst { it.uri == uri }.takeIf { it >= 0 }
        }
        val activeIndex = matchedIndex
            ?: savedState?.activeQueueIndex?.coerceIn(0, queue.lastIndex)
            ?: 0

        val activeTrack = queue[activeIndex]
        val resolvedPositionMs = when {
            savedState != null && savedState.activeTrackUri == activeTrack.uri && savedState.positionMs > 0L ->
                savedState.positionMs
            activeTrack.lastPositionMs > 0L ->
                activeTrack.lastPositionMs
            else -> 0L
        }

        return RestoreSnapshot(
            queue = queue,
            activeQueueIndex = activeIndex,
            startPositionMs = resolvedPositionMs
        )
    }

    suspend fun addTracksToQueue(inputs: List<NewTrackInput>): List<QueueTrackEntity> {
        if (inputs.isEmpty()) return queueDao.getQueueSnapshot()

        val now = System.currentTimeMillis()
        return database.withTransaction {
            val startIndex = queueDao.getMaxQueueIndex() + 1
            val entities = inputs.mapIndexed { offset, input ->
                QueueTrackEntity(
                    uri = input.uri,
                    fileName = input.fileName,
                    title = TimeFormatter.extractCleanTrackTitle(input.fileName),
                    fileSizeBytes = input.fileSizeBytes.coerceAtLeast(0L),
                    durationMs = input.durationMs.coerceAtLeast(0L),
                    lastPositionMs = 0L,
                    queueIndex = startIndex + offset,
                    lastPlayedTimestamp = 0L,
                    addedTimestamp = now
                )
            }
            queueDao.insertAllIgnore(entities)

            val updatedQueue = queueDao.getQueueSnapshot()
            val currentPlaybackState = playbackStateDao.getPlaybackState()
            if (currentPlaybackState == null && updatedQueue.isNotEmpty()) {
                val first = updatedQueue.first()
                playbackStateDao.upsertPlaybackState(
                    PlaybackStateEntity(
                        activeTrackUri = first.uri,
                        activeQueueIndex = 0,
                        positionMs = first.lastPositionMs,
                        durationMs = first.durationMs,
                        lastPlayedTimestamp = now
                    )
                )
            }
            updatedQueue
        }
    }

    suspend fun savePlaybackPosition(
        trackUri: String,
        queueIndex: Int,
        candidatePositionMs: Long,
        durationMs: Long,
        reason: SaveReason,
        isInitialSeekPending: Boolean,
        hasPlayerError: Boolean
    ): PositionSaveDecision {
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val existingTrack = queueDao.getTrackByUri(trackUri)
            val existingState = playbackStateDao.getPlaybackState()

            val currentSavedPositionMs = maxOf(
                existingTrack?.lastPositionMs ?: 0L,
                if (existingState?.activeTrackUri == trackUri) existingState.positionMs else 0L
            )
            val effectiveDurationMs = if (durationMs > 0L) {
                durationMs
            } else {
                existingTrack?.durationMs ?: 0L
            }

            val decision = PlaybackTimestampGuard.evaluate(
                currentSavedPositionMs = currentSavedPositionMs,
                candidatePositionMs = candidatePositionMs,
                durationMs = effectiveDurationMs,
                reason = reason,
                isInitialSeekPending = isInitialSeekPending,
                hasPlayerError = hasPlayerError
            )

            when (decision) {
                is PositionSaveDecision.Persist -> {
                    queueDao.updateTrackProgress(
                        uri = trackUri,
                        positionMs = decision.positionMs,
                        durationMs = effectiveDurationMs,
                        lastPlayedTimestamp = now
                    )
                    playbackStateDao.upsertPlaybackState(
                        PlaybackStateEntity(
                            activeTrackUri = trackUri,
                            activeQueueIndex = queueIndex.coerceAtLeast(0),
                            positionMs = decision.positionMs,
                            durationMs = effectiveDurationMs,
                            lastPlayedTimestamp = now
                        )
                    )
                }

                is PositionSaveDecision.Skip -> {
                    if (durationMs > 0L) {
                        queueDao.updateTrackDuration(uri = trackUri, durationMs = durationMs)
                    }
                }
            }
            decision
        }
    }

    suspend fun updateActiveTrackSelection(
        trackUri: String,
        queueIndex: Int
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val track = queueDao.getTrackByUri(trackUri)
            playbackStateDao.upsertPlaybackState(
                PlaybackStateEntity(
                    activeTrackUri = trackUri,
                    activeQueueIndex = queueIndex.coerceAtLeast(0),
                    positionMs = track?.lastPositionMs ?: 0L,
                    durationMs = track?.durationMs ?: 0L,
                    lastPlayedTimestamp = now
                )
            )
        }
    }

    suspend fun removeTrack(uri: String) {
        database.withTransaction {
            queueDao.deleteTrackByUri(uri)
            val remaining = queueDao.getQueueSnapshot()
            queueDao.replaceOrdering(remaining.map { it.uri })

            val state = playbackStateDao.getPlaybackState()
            if (remaining.isEmpty()) {
                playbackStateDao.clearPlaybackState()
            } else if (state?.activeTrackUri == uri) {
                val nextIndex = state.activeQueueIndex.coerceIn(0, remaining.lastIndex)
                val nextTrack = remaining[nextIndex]
                playbackStateDao.upsertPlaybackState(
                    PlaybackStateEntity(
                        activeTrackUri = nextTrack.uri,
                        activeQueueIndex = nextIndex,
                        positionMs = nextTrack.lastPositionMs,
                        durationMs = nextTrack.durationMs,
                        lastPlayedTimestamp = System.currentTimeMillis()
                    )
                )
            } else if (state?.activeTrackUri != null) {
                val updatedIndex = remaining.indexOfFirst { it.uri == state.activeTrackUri }
                if (updatedIndex >= 0 && updatedIndex != state.activeQueueIndex) {
                    playbackStateDao.upsertPlaybackState(state.copy(activeQueueIndex = updatedIndex))
                }
            }
        }
    }

    suspend fun reorderQueue(orderedUris: List<String>) {
        database.withTransaction {
            queueDao.replaceOrdering(orderedUris)
            val state = playbackStateDao.getPlaybackState() ?: return@withTransaction
            val activeUri = state.activeTrackUri ?: return@withTransaction
            val newIndex = orderedUris.indexOf(activeUri)
            if (newIndex >= 0 && newIndex != state.activeQueueIndex) {
                playbackStateDao.upsertPlaybackState(state.copy(activeQueueIndex = newIndex))
            }
        }
    }

    suspend fun clearQueue() {
        database.withTransaction {
            queueDao.clearQueue()
            playbackStateDao.clearPlaybackState()
        }
    }
}
