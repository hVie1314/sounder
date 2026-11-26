package com.offline.audiobookplayer.ui.player

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.offline.audiobookplayer.data.local.SettingsDataStore
import com.offline.audiobookplayer.data.local.entity.PlaybackStateEntity
import com.offline.audiobookplayer.data.local.entity.QueueTrackEntity
import com.offline.audiobookplayer.data.model.UserSettings
import com.offline.audiobookplayer.data.repository.AudiobookRepository
import com.offline.audiobookplayer.data.repository.NewTrackInput
import com.offline.audiobookplayer.player.AudioPlaybackService
import com.offline.audiobookplayer.player.SleepTimerManager
import com.offline.audiobookplayer.player.SleepTimerState
import com.offline.audiobookplayer.ui.theme.AppThemeMode
import com.offline.audiobookplayer.util.MediaVolumeObserver
import com.offline.audiobookplayer.util.MediaVolumeState
import com.offline.audiobookplayer.util.SafFileHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private data class LiveControllerSnapshot(
    val isConnected: Boolean = false,
    val mediaItemCount: Int = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentMediaItemIndex: Int = 0,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val errorMessage: String? = null
)

private data class TransientUiFlags(
    val isImporting: Boolean = false,
    val statusMessage: String? = null
)

class PlayerViewModel(
    application: Application,
    private val repository: AudiobookRepository,
    private val settingsDataStore: SettingsDataStore,
    private val sleepTimerManager: SleepTimerManager
) : AndroidViewModel(application) {

    private val appContext = application.applicationContext

    private val liveControllerState = MutableStateFlow(LiveControllerSnapshot())
    private val transientFlags = MutableStateFlow(TransientUiFlags())

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var connectJob: Job? = null
    private var tickerJob: Job? = null
    private var shouldStayConnected: Boolean = false

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromPlayer(player)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                startPositionTicker()
            } else {
                stopPositionTicker()
            }
            mediaController?.let(::syncFromPlayer)
        }

        override fun onPlayerError(error: PlaybackException) {
            liveControllerState.update {
                it.copy(
                    isPlaying = false,
                    isBuffering = false,
                    errorMessage = error.localizedMessage ?: "Không thể phát tệp âm thanh này"
                )
            }
        }
    }

    private val persistedStateFlow = combine(
        repository.queueFlow,
        repository.playbackStateFlow,
        settingsDataStore.settingsFlow
    ) { queue, playbackState, settings ->
        Triple(queue, playbackState, settings)
    }

    private val systemStateFlow = combine(
        sleepTimerManager.state,
        MediaVolumeObserver.observe(appContext)
    ) { sleepTimer, volume ->
        sleepTimer to volume
    }

    val uiState: StateFlow<PlayerUiState> = combine(
        persistedStateFlow,
        systemStateFlow,
        liveControllerState,
        transientFlags
    ) { (queue, persistedPlayback, settings), (sleepTimer, volume), live, flags ->
        buildUiState(
            queue = queue,
            persistedPlayback = persistedPlayback,
            settings = settings,
            sleepTimer = sleepTimer,
            volume = volume,
            live = live,
            flags = flags
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = PlayerUiState()
    )

    fun connectController() {
        shouldStayConnected = true
        if (mediaController != null || connectJob?.isActive == true) return

        connectJob = viewModelScope.launch {
            val sessionToken = SessionToken(
                appContext,
                ComponentName(appContext, AudioPlaybackService::class.java)
            )
            val future = MediaController.Builder(appContext, sessionToken).buildAsync()
            controllerFuture = future
            try {
                val controller = future.await()
                if (!shouldStayConnected) {
                    MediaController.releaseFuture(future)
                    if (controllerFuture === future) controllerFuture = null
                    return@launch
                }
                mediaController = controller
                controller.addListener(playerListener)
                reconcileControllerQueueWithDb(controller)
                syncFromPlayer(controller)
                if (controller.isPlaying) {
                    startPositionTicker()
                }
            } catch (_: Exception) {
                // Controller connection failed or cancelled; keep DB-backed UI intact.
            }
        }
    }

    fun disconnectController() {
        shouldStayConnected = false
        connectJob?.cancel()
        connectJob = null
        stopPositionTicker()

        mediaController?.removeListener(playerListener)
        mediaController = null

        controllerFuture?.let { future ->
            MediaController.releaseFuture(future)
        }
        controllerFuture = null
        liveControllerState.update { it.copy(isConnected = false, isPlaying = false) }
    }

    override fun onCleared() {
        disconnectController()
        super.onCleared()
    }

    fun togglePlayPause() {
        viewModelScope.launch {
            val controller = awaitController() ?: return@launch
            if (controller.mediaItemCount == 0) {
                reconcileControllerQueueWithDb(controller)
            }
            if (controller.mediaItemCount == 0) return@launch

            if (controller.playbackState == Player.STATE_IDLE) {
                controller.prepare()
            }
            if (controller.isPlaying) {
                controller.pause()
            } else {
                if (controller.playbackState == Player.STATE_ENDED) {
                    controller.seekTo(0L)
                }
                controller.play()
            }
        }
    }

    fun seekToPosition(positionMs: Long) {
        val controller = mediaController ?: return
        val duration = controller.duration
        val clamped = if (duration > 0L) {
            positionMs.coerceIn(0L, duration)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        controller.seekTo(clamped)
        liveControllerState.update { it.copy(currentPositionMs = clamped) }
    }

    fun seekByStep(forward: Boolean) {
        val controller = mediaController ?: return
        val stepMs = uiState.value.settings.seekStepSeconds * 1_000L
        val current = controller.currentPosition.coerceAtLeast(0L)
        val delta = if (forward) stepMs else -stepMs
        seekToPosition(current + delta)
    }

    fun seekToPreviousTrack() {
        viewModelScope.launch {
            val controller = awaitController() ?: return@launch
            val prevIndex = controller.currentMediaItemIndex - 1
            if (prevIndex >= 0) {
                val targetTrack = uiState.value.queue.getOrNull(prevIndex)
                val targetPos = targetTrack?.lastPositionMs ?: 0L
                controller.seekTo(prevIndex, targetPos)
            } else {
                controller.seekTo(0L)
            }
        }
    }

    fun seekToNextTrack() {
        viewModelScope.launch {
            val controller = awaitController() ?: return@launch
            val nextIndex = controller.currentMediaItemIndex + 1
            if (nextIndex < controller.mediaItemCount) {
                val targetTrack = uiState.value.queue.getOrNull(nextIndex)
                val targetPos = targetTrack?.lastPositionMs ?: 0L
                controller.seekTo(nextIndex, targetPos)
            }
        }
    }

    fun selectTrackFromQueue(index: Int) {
        viewModelScope.launch {
            val queue = uiState.value.queue
            val track = queue.getOrNull(index) ?: return@launch
            val controller = awaitController() ?: return@launch
            reconcileControllerQueueWithDb(controller)
            if (index in 0 until controller.mediaItemCount) {
                controller.seekTo(index, track.lastPositionMs.coerceAtLeast(0L))
                if (controller.playbackState == Player.STATE_IDLE) {
                    controller.prepare()
                }
                controller.play()
            }
        }
    }

    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            transientFlags.update { it.copy(isImporting = true) }
            try {
                val inputs = SafFileHelper.resolvePickedFiles(appContext, uris)
                appendImportedTracks(inputs)
            } finally {
                transientFlags.update { it.copy(isImporting = false) }
            }
        }
    }

    fun importFolder(treeUri: Uri) {
        viewModelScope.launch {
            transientFlags.update { it.copy(isImporting = true) }
            try {
                val inputs = SafFileHelper.resolvePickedFolder(appContext, treeUri)
                appendImportedTracks(inputs)
            } finally {
                transientFlags.update { it.copy(isImporting = false) }
            }
        }
    }

    private suspend fun appendImportedTracks(inputs: List<NewTrackInput>) {
        if (inputs.isEmpty()) {
            transientFlags.update { it.copy(statusMessage = "Không tìm thấy tệp MP3 hợp lệ") }
            return
        }
        val previousCount = uiState.value.queue.size
        val updatedQueue = repository.addTracksToQueue(inputs)
        val addedCount = (updatedQueue.size - previousCount).coerceAtLeast(0)

        val controller = mediaController ?: awaitController()
        controller?.let { activeController ->
            if (activeController.mediaItemCount == 0 && updatedQueue.isNotEmpty()) {
                activeController.setMediaItems(
                    updatedQueue.map(::toMediaItem),
                    0,
                    updatedQueue[0].lastPositionMs
                )
                activeController.prepare()
            } else {
                val existingIds = buildSet {
                    for (i in 0 until activeController.mediaItemCount) {
                        add(activeController.getMediaItemAt(i).mediaId)
                    }
                }
                val newItems = updatedQueue
                    .filter { it.uri !in existingIds }
                    .map(::toMediaItem)
                if (newItems.isNotEmpty()) {
                    activeController.addMediaItems(newItems)
                }
            }
        }

        val message = if (addedCount > 0) {
            "Đã thêm $addedCount tệp MP3 vào danh sách phát"
        } else {
            "Các tệp đã có sẵn trong danh sách phát"
        }
        transientFlags.update { it.copy(statusMessage = message) }
    }

    fun moveTrack(fromIndex: Int, toIndex: Int) {
        val currentQueue = uiState.value.queue
        if (fromIndex !in currentQueue.indices || toIndex !in currentQueue.indices || fromIndex == toIndex) {
            return
        }
        val reordered = currentQueue.toMutableList().apply {
            val item = removeAt(fromIndex)
            add(toIndex, item)
        }
        viewModelScope.launch {
            mediaController?.let { controller ->
                if (fromIndex < controller.mediaItemCount && toIndex < controller.mediaItemCount) {
                    controller.moveMediaItem(fromIndex, toIndex)
                }
            }
            repository.reorderQueue(reordered.map { it.uri })
        }
    }

    fun removeTrack(uri: String) {
        viewModelScope.launch {
            mediaController?.let { controller ->
                for (i in 0 until controller.mediaItemCount) {
                    if (controller.getMediaItemAt(i).mediaId == uri) {
                        controller.removeMediaItem(i)
                        break
                    }
                }
            }
            repository.removeTrack(uri)
        }
    }

    fun clearQueue() {
        viewModelScope.launch {
            sleepTimerManager.cancel()
            mediaController?.let { controller ->
                controller.stop()
                controller.clearMediaItems()
            }
            repository.clearQueue()
            liveControllerState.update {
                LiveControllerSnapshot(isConnected = mediaController != null)
            }
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = UserSettings.clampSpeed(speed)
        mediaController?.let { controller ->
            controller.playbackParameters = PlaybackParameters(clamped, 1.0f)
        }
        viewModelScope.launch {
            settingsDataStore.setPlaybackSpeed(clamped)
        }
    }

    fun setSpeedPresets(presets: List<Float>) {
        viewModelScope.launch {
            settingsDataStore.setSpeedPresets(presets)
        }
    }

    fun setSeekStepSeconds(seconds: Int) {
        viewModelScope.launch {
            settingsDataStore.setSeekStepSeconds(seconds)
        }
    }

    fun setAutoRewindSeconds(seconds: Int) {
        viewModelScope.launch {
            settingsDataStore.setAutoRewindSeconds(seconds)
        }
    }

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch {
            settingsDataStore.setThemeMode(mode)
        }
    }

    fun setVbrIndexSeekingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setVbrIndexSeekingEnabled(enabled)
            transientFlags.update {
                it.copy(
                    statusMessage = if (enabled) {
                        "Đã bật tua chính xác VBR (khởi tạo lại bài nếu đang phát)"
                    } else {
                        "Đã chuyển về tua CBR an toàn cho file >4 GB"
                    }
                )
            }
        }
    }

    fun startSleepTimerMinutes(minutes: Int) {
        if (minutes <= 0) return
        sleepTimerManager.startCountdown(minutes.toLong() * 60_000L)
    }

    fun startSleepTimerEndOfTrack() {
        sleepTimerManager.startEndOfTrack()
    }

    fun cancelSleepTimer() {
        sleepTimerManager.cancel()
    }

    fun setMediaVolume(volumeIndex: Int) {
        MediaVolumeObserver.setVolume(appContext, volumeIndex)
    }

    fun clearStatusMessage() {
        transientFlags.update { it.copy(statusMessage = null) }
    }

    fun clearErrorMessage() {
        liveControllerState.update { it.copy(errorMessage = null) }
    }

    private suspend fun awaitController(): MediaController? {
        mediaController?.let { return it }
        connectController()
        return try {
            val controller = controllerFuture?.await()
            if (controller != null && mediaController == null && shouldStayConnected) {
                mediaController = controller
                controller.addListener(playerListener)
                reconcileControllerQueueWithDb(controller)
                syncFromPlayer(controller)
            }
            controller
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun reconcileControllerQueueWithDb(controller: MediaController) {
        val snapshot = repository.getRestoreSnapshot() ?: return
        if (controller.mediaItemCount == 0) {
            val settings = settingsDataStore.getSnapshot()
            val autoRewindMs = settings.autoRewindSeconds * 1_000L
            val resumePositionMs = if (snapshot.startPositionMs > 0L) {
                (snapshot.startPositionMs - autoRewindMs).coerceAtLeast(0L)
            } else {
                0L
            }
            val items = snapshot.queue.map(::toMediaItem)
            controller.setMediaItems(items, snapshot.activeQueueIndex, resumePositionMs)
            controller.prepare()
        }
    }

    private fun syncFromPlayer(player: Player) {
        val currentIdx = player.currentMediaItemIndex.coerceAtLeast(0)
        val rawDuration = player.duration
        val duration = if (rawDuration > 0L) rawDuration else 0L
        val rawPosition = player.currentPosition
        val position = if (rawPosition >= 0L) rawPosition else 0L

        liveControllerState.update { prev ->
            prev.copy(
                isConnected = true,
                mediaItemCount = player.mediaItemCount,
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                currentMediaItemIndex = currentIdx,
                currentPositionMs = position,
                durationMs = duration,
                errorMessage = if (player.playerError != null) prev.errorMessage else null
            )
        }
    }

    private fun startPositionTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = viewModelScope.launch {
            while (isActive) {
                val controller = mediaController ?: break
                if (!controller.isPlaying) break
                val rawPos = controller.currentPosition
                val rawDur = controller.duration
                liveControllerState.update { prev ->
                    prev.copy(
                        currentPositionMs = if (rawPos >= 0L) rawPos else prev.currentPositionMs,
                        durationMs = if (rawDur > 0L) rawDur else prev.durationMs
                    )
                }
                delay(UI_POSITION_TICK_MS)
            }
        }
    }

    private fun stopPositionTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun buildUiState(
        queue: List<QueueTrackEntity>,
        persistedPlayback: PlaybackStateEntity?,
        settings: UserSettings,
        sleepTimer: SleepTimerState,
        volume: MediaVolumeState,
        live: LiveControllerSnapshot,
        flags: TransientUiFlags
    ): PlayerUiState {
        val activeIndex = when {
            queue.isEmpty() -> 0
            live.isConnected && live.mediaItemCount > 0 -> live.currentMediaItemIndex.coerceIn(0, queue.lastIndex)
            persistedPlayback?.activeTrackUri != null -> {
                queue.indexOfFirst { it.uri == persistedPlayback.activeTrackUri }
                    .takeIf { it >= 0 }
                    ?: persistedPlayback.activeQueueIndex.coerceIn(0, queue.lastIndex)
            }
            else -> 0
        }

        val activeTrack = queue.getOrNull(activeIndex)
        val positionMs = when {
            live.isConnected && live.mediaItemCount > 0 && live.currentPositionMs > 0L -> live.currentPositionMs
            live.isConnected && live.mediaItemCount > 0 && live.isPlaying -> live.currentPositionMs
            activeTrack != null && activeTrack.lastPositionMs > 0L -> activeTrack.lastPositionMs
            persistedPlayback?.activeTrackUri == activeTrack?.uri -> persistedPlayback?.positionMs ?: 0L
            else -> live.currentPositionMs
        }
        val durationMs = when {
            live.isConnected && live.mediaItemCount > 0 && live.durationMs > 0L -> live.durationMs
            activeTrack != null && activeTrack.durationMs > 0L -> activeTrack.durationMs
            persistedPlayback?.activeTrackUri == activeTrack?.uri -> persistedPlayback?.durationMs ?: 0L
            else -> 0L
        }

        return PlayerUiState(
            queue = queue,
            activeTrackIndex = activeIndex,
            activeTrack = activeTrack,
            isPlaying = live.isPlaying,
            isBuffering = live.isBuffering,
            currentPositionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            settings = settings,
            sleepTimerState = sleepTimer,
            volumeState = volume,
            isImporting = flags.isImporting,
            statusMessage = flags.statusMessage,
            errorMessage = live.errorMessage
        )
    }

    private fun toMediaItem(track: QueueTrackEntity): MediaItem {
        return MediaItem.Builder()
            .setMediaId(track.uri)
            .setUri(Uri.parse(track.uri))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setDisplayTitle(track.title)
                    .build()
            )
            .build()
    }

    class Factory(
        private val application: Application,
        private val repository: AudiobookRepository,
        private val settingsDataStore: SettingsDataStore,
        private val sleepTimerManager: SleepTimerManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PlayerViewModel::class.java)) {
                return PlayerViewModel(
                    application = application,
                    repository = repository,
                    settingsDataStore = settingsDataStore,
                    sleepTimerManager = sleepTimerManager
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }

    private companion object {
        const val UI_POSITION_TICK_MS = 250L
    }
}
