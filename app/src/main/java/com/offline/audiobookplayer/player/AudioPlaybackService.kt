package com.offline.audiobookplayer.player

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.offline.audiobookplayer.MainActivity
import com.offline.audiobookplayer.R
import com.offline.audiobookplayer.TruyenCastApplication
import com.offline.audiobookplayer.data.local.SettingsDataStore
import com.offline.audiobookplayer.data.repository.AudiobookRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(UnstableApi::class)
class AudioPlaybackService : MediaSessionService() {

    private lateinit var repository: AudiobookRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var sleepTimerManager: SleepTimerManager

    private lateinit var extractorsFactory: AudiobookExtractorsFactory
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var forwardingPlayer: DynamicSeekPlayer
    private var mediaSession: MediaSession? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var periodicSaveJob: Job? = null
    private var endOfTrackFadeJob: Job? = null

    @Volatile
    private var isInitialSeekPending: Boolean = false

    @Volatile
    private var hasPlayerError: Boolean = false

    @Volatile
    private var currentSeekStepSeconds: Int = 10

    private var noisyReceiverRegistered: Boolean = false

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                val trackUri = exoPlayer.currentMediaItem?.mediaId ?: return
                val index = exoPlayer.currentMediaItemIndex
                val pos = exoPlayer.currentPosition
                val dur = exoPlayer.duration
                serviceScope.launch {
                    persistPosition(
                        trackUri = trackUri,
                        queueIndex = index,
                        candidatePositionMs = pos,
                        durationMs = dur,
                        reason = SaveReason.AUDIO_BECOMING_NOISY
                    )
                }
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                hasPlayerError = false
                startPeriodicPersistence()
                startEndOfTrackWatcher()
            } else {
                stopPeriodicPersistence()
                stopEndOfTrackWatcher()
                val trackUri = exoPlayer.currentMediaItem?.mediaId ?: return
                val index = exoPlayer.currentMediaItemIndex
                val pos = exoPlayer.currentPosition
                val dur = exoPlayer.duration
                serviceScope.launch {
                    persistPosition(
                        trackUri = trackUri,
                        queueIndex = index,
                        candidatePositionMs = pos,
                        durationMs = dur,
                        reason = SaveReason.PAUSE
                    )
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    isInitialSeekPending = false
                    hasPlayerError = false
                    val trackUri = exoPlayer.currentMediaItem?.mediaId ?: return
                    val index = exoPlayer.currentMediaItemIndex
                    val pos = exoPlayer.currentPosition
                    val dur = exoPlayer.duration
                    if (dur > 0L) {
                        serviceScope.launch {
                            persistPosition(
                                trackUri = trackUri,
                                queueIndex = index,
                                candidatePositionMs = pos,
                                durationMs = dur,
                                reason = SaveReason.PERIODIC_TICK
                            )
                        }
                    }
                }

                Player.STATE_ENDED -> {
                    isInitialSeekPending = false
                    val trackUri = exoPlayer.currentMediaItem?.mediaId ?: return
                    val index = exoPlayer.currentMediaItemIndex
                    val dur = exoPlayer.duration
                    serviceScope.launch {
                        if (sleepTimerManager.isEndOfTrackActive()) {
                            sleepTimerManager.onTrackReachedEnd()
                        }
                        persistPosition(
                            trackUri = trackUri,
                            queueIndex = index,
                            candidatePositionMs = 0L,
                            durationMs = dur,
                            reason = SaveReason.TRACK_ENDED
                        )
                    }
                }

                Player.STATE_IDLE,
                Player.STATE_BUFFERING -> Unit
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            when (reason) {
                Player.DISCONTINUITY_REASON_SEEK -> {
                    isInitialSeekPending = false
                    val targetUri = newPosition.mediaItem?.mediaId ?: return
                    val targetIndex = newPosition.mediaItemIndex
                    val targetPos = newPosition.positionMs
                    val dur = exoPlayer.duration
                    serviceScope.launch {
                        persistPosition(
                            trackUri = targetUri,
                            queueIndex = targetIndex,
                            candidatePositionMs = targetPos,
                            durationMs = dur,
                            reason = SaveReason.USER_SEEK
                        )
                    }
                }

                Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> {
                    isInitialSeekPending = false
                    val oldUri = oldPosition.mediaItem?.mediaId
                    val newUri = newPosition.mediaItem?.mediaId
                    val newIndex = newPosition.mediaItemIndex
                    val endOfTrackSleep = sleepTimerManager.isEndOfTrackActive()

                    serviceScope.launch {
                        if (oldUri != null) {
                            persistPosition(
                                trackUri = oldUri,
                                queueIndex = oldPosition.mediaItemIndex,
                                candidatePositionMs = 0L,
                                durationMs = 0L,
                                reason = SaveReason.TRACK_ENDED
                            )
                        }
                        if (endOfTrackSleep) {
                            exoPlayer.pause()
                            sleepTimerManager.onTrackReachedEnd()
                        }
                        if (newUri != null) {
                            repository.updateActiveTrackSelection(
                                trackUri = newUri,
                                queueIndex = newIndex
                            )
                        }
                    }
                }

                Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> {
                    val currentUri = newPosition.mediaItem?.mediaId ?: return
                    val currentIndex = newPosition.mediaItemIndex
                    val currentPos = newPosition.positionMs
                    val dur = exoPlayer.duration
                    serviceScope.launch {
                        persistPosition(
                            trackUri = currentUri,
                            queueIndex = currentIndex,
                            candidatePositionMs = currentPos,
                            durationMs = dur,
                            reason = SaveReason.TRACK_SWITCH
                        )
                    }
                }

                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            hasPlayerError = true
            stopPeriodicPersistence()
            stopEndOfTrackWatcher()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val appContainer = (application as TruyenCastApplication).appContainer
        repository = appContainer.audiobookRepository
        settingsDataStore = appContainer.settingsDataStore
        sleepTimerManager = appContainer.sleepTimerManager

        appContainer.bindSleepTimerCallbacks(
            setVolume = { volume ->
                if (::exoPlayer.isInitialized) {
                    exoPlayer.volume = volume.coerceIn(0f, 1f)
                }
            },
            fadeCompletePause = {
                if (::exoPlayer.isInitialized) {
                    val trackUri = exoPlayer.currentMediaItem?.mediaId
                    val index = exoPlayer.currentMediaItemIndex
                    val pos = exoPlayer.currentPosition
                    val dur = exoPlayer.duration
                    if (trackUri != null) {
                        persistPosition(
                            trackUri = trackUri,
                            queueIndex = index,
                            candidatePositionMs = pos,
                            durationMs = dur,
                            reason = SaveReason.SLEEP_TIMER
                        )
                    }
                    exoPlayer.pause()
                    exoPlayer.volume = 1.0f
                }
            }
        )

        extractorsFactory = AudiobookExtractorsFactory()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setLoadControl(LowRamMediaConfig.buildLowRamLoadControl())
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractorsFactory))
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .apply {
                addListener(playerListener)
            }

        forwardingPlayer = DynamicSeekPlayer(
            player = exoPlayer,
            initialSeekIncrementMs = currentSeekStepSeconds * 1_000L,
            onSeekCommitted = { trackUri, targetPos ->
                if (trackUri != null) {
                    isInitialSeekPending = false
                    val index = exoPlayer.currentMediaItemIndex
                    val dur = exoPlayer.duration
                    serviceScope.launch {
                        persistPosition(
                            trackUri = trackUri,
                            queueIndex = index,
                            candidatePositionMs = targetPos,
                            durationMs = dur,
                            reason = SaveReason.USER_SEEK
                        )
                    }
                }
            }
        )

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(sessionActivityIntent)
            .setBitmapLoader(NoOpBitmapLoader())
            .setCallback(AudiobookSessionCallback())
            .setMediaButtonPreferences(buildMediaButtonPreferences(currentSeekStepSeconds))
            .build()

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(NOTIFICATION_CHANNEL_ID)
            .setChannelName(R.string.playback_notification_channel_name)
            .build()
            .apply {
                setSmallIcon(R.drawable.ic_notification_headphones)
            }
        setMediaNotificationProvider(notificationProvider)

        ContextCompat.registerReceiver(
            this,
            becomingNoisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        noisyReceiverRegistered = true

        observeSettings()
        restoreLastPlaybackSession()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistCurrentSnapshotBlocking(SaveReason.SERVICE_TEARDOWN)
        if (::exoPlayer.isInitialized && !exoPlayer.playWhenReady) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopPeriodicPersistence()
        stopEndOfTrackWatcher()

        if (::exoPlayer.isInitialized) {
            persistCurrentSnapshotBlocking(SaveReason.SERVICE_TEARDOWN)
        }

        if (noisyReceiverRegistered) {
            unregisterReceiver(becomingNoisyReceiver)
            noisyReceiverRegistered = false
        }

        val appContainer = (application as? TruyenCastApplication)?.appContainer
        appContainer?.unbindSleepTimerCallbacks()

        mediaSession?.run {
            release()
            mediaSession = null
        }

        if (::exoPlayer.isInitialized) {
            exoPlayer.removeListener(playerListener)
            exoPlayer.release()
        }

        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observeSettings() {
        serviceScope.launch {
            settingsDataStore.settingsFlow.collectLatest { settings ->
                currentSeekStepSeconds = settings.seekStepSeconds
                extractorsFactory.useVbrIndexSeeking = settings.vbrIndexSeekingEnabled
                exoPlayer.playbackParameters = PlaybackParameters(settings.playbackSpeed, 1.0f)
                forwardingPlayer.updateSeekIncrementMs(settings.seekStepSeconds * 1_000L)
                mediaSession?.setMediaButtonPreferences(
                    buildMediaButtonPreferences(settings.seekStepSeconds)
                )
            }
        }
    }

    private fun restoreLastPlaybackSession() {
        serviceScope.launch {
            val snapshot = repository.getRestoreSnapshot() ?: return@launch
            if (exoPlayer.mediaItemCount > 0) return@launch

            val settings = settingsDataStore.getSnapshot()
            val autoRewindMs = settings.autoRewindSeconds * 1_000L
            val resumePositionMs = if (snapshot.startPositionMs > 0L) {
                (snapshot.startPositionMs - autoRewindMs).coerceAtLeast(0L)
            } else {
                0L
            }

            isInitialSeekPending = resumePositionMs > 0L
            hasPlayerError = false

            val mediaItems = snapshot.queue.map { track ->
                MediaItem.Builder()
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

            exoPlayer.setMediaItems(mediaItems, snapshot.activeQueueIndex, resumePositionMs)
            exoPlayer.prepare()
        }
    }

    private fun startPeriodicPersistence() {
        if (periodicSaveJob?.isActive == true) return
        periodicSaveJob = serviceScope.launch {
            while (isActive && exoPlayer.isPlaying) {
                delay(PERIODIC_SAVE_INTERVAL_MS)
                if (!exoPlayer.isPlaying) break
                val trackUri = exoPlayer.currentMediaItem?.mediaId ?: continue
                persistPosition(
                    trackUri = trackUri,
                    queueIndex = exoPlayer.currentMediaItemIndex,
                    candidatePositionMs = exoPlayer.currentPosition,
                    durationMs = exoPlayer.duration,
                    reason = SaveReason.PERIODIC_TICK
                )
            }
        }
    }

    private fun stopPeriodicPersistence() {
        periodicSaveJob?.cancel()
        periodicSaveJob = null
    }

    private fun startEndOfTrackWatcher() {
        if (endOfTrackFadeJob?.isActive == true) return
        endOfTrackFadeJob = serviceScope.launch {
            while (isActive && exoPlayer.isPlaying) {
                if (sleepTimerManager.isEndOfTrackActive()) {
                    sleepTimerManager.onPlaybackProgress(
                        isPlaying = exoPlayer.isPlaying,
                        positionMs = exoPlayer.currentPosition,
                        durationMs = exoPlayer.duration
                    )
                }
                delay(END_OF_TRACK_WATCH_INTERVAL_MS)
            }
        }
    }

    private fun stopEndOfTrackWatcher() {
        endOfTrackFadeJob?.cancel()
        endOfTrackFadeJob = null
    }

    private suspend fun persistPosition(
        trackUri: String,
        queueIndex: Int,
        candidatePositionMs: Long,
        durationMs: Long,
        reason: SaveReason
    ) {
        repository.savePlaybackPosition(
            trackUri = trackUri,
            queueIndex = queueIndex,
            candidatePositionMs = candidatePositionMs,
            durationMs = durationMs,
            reason = reason,
            isInitialSeekPending = isInitialSeekPending,
            hasPlayerError = hasPlayerError
        )
    }

    private fun persistCurrentSnapshotBlocking(reason: SaveReason) {
        if (!::exoPlayer.isInitialized) return
        val trackUri = exoPlayer.currentMediaItem?.mediaId ?: return
        val index = exoPlayer.currentMediaItemIndex
        val position = exoPlayer.currentPosition
        val duration = exoPlayer.duration

        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(SHUTDOWN_SAVE_TIMEOUT_MS) {
                persistPosition(
                    trackUri = trackUri,
                    queueIndex = index,
                    candidatePositionMs = position,
                    durationMs = duration,
                    reason = reason
                )
            }
        }
    }

    private fun performRelativeSeek(deltaMs: Long) {
        if (exoPlayer.mediaItemCount == 0) return
        val currentPos = exoPlayer.currentPosition.coerceAtLeast(0L)
        val duration = exoPlayer.duration
        val rawTarget = (currentPos + deltaMs).coerceAtLeast(0L)
        val clampedTarget = if (duration > 0L) rawTarget.coerceAtMost(duration) else rawTarget

        isInitialSeekPending = false
        exoPlayer.seekTo(clampedTarget)

        val trackUri = exoPlayer.currentMediaItem?.mediaId ?: return
        val index = exoPlayer.currentMediaItemIndex
        serviceScope.launch {
            persistPosition(
                trackUri = trackUri,
                queueIndex = index,
                candidatePositionMs = clampedTarget,
                durationMs = duration,
                reason = SaveReason.USER_SEEK
            )
        }
    }

    private fun buildMediaButtonPreferences(seekStepSeconds: Int): List<CommandButton> {
        val rewindIcon = when (seekStepSeconds) {
            5 -> CommandButton.ICON_SKIP_BACK_5
            10 -> CommandButton.ICON_SKIP_BACK_10
            15 -> CommandButton.ICON_SKIP_BACK_15
            30 -> CommandButton.ICON_SKIP_BACK_30
            else -> CommandButton.ICON_SKIP_BACK
        }
        val forwardIcon = when (seekStepSeconds) {
            5 -> CommandButton.ICON_SKIP_FORWARD_5
            10 -> CommandButton.ICON_SKIP_FORWARD_10
            15 -> CommandButton.ICON_SKIP_FORWARD_15
            30 -> CommandButton.ICON_SKIP_FORWARD_30
            else -> CommandButton.ICON_SKIP_FORWARD
        }

        val rewindButton = CommandButton.Builder(rewindIcon)
            .setDisplayName(getString(R.string.action_rewind_seconds, seekStepSeconds))
            .setSessionCommand(COMMAND_REWIND_STEP)
            .setSlots(CommandButton.SLOT_BACK)
            .build()

        val forwardButton = CommandButton.Builder(forwardIcon)
            .setDisplayName(getString(R.string.action_forward_seconds, seekStepSeconds))
            .setSessionCommand(COMMAND_FORWARD_STEP)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()

        val previousButton = CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setDisplayName(getString(R.string.action_previous_track))
            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
            .setSlots(CommandButton.SLOT_BACK_SECONDARY)
            .build()

        val nextButton = CommandButton.Builder(CommandButton.ICON_NEXT)
            .setDisplayName(getString(R.string.action_next_track))
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
            .setSlots(CommandButton.SLOT_FORWARD_SECONDARY)
            .build()

        return listOf(rewindButton, forwardButton, previousButton, nextButton)
    }

    private inner class AudiobookSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val baseResult = super.onConnect(session, controller)
            val sessionCommands = baseResult.availableSessionCommands.buildUpon()
                .add(COMMAND_REWIND_STEP)
                .add(COMMAND_FORWARD_STEP)
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(baseResult.availablePlayerCommands)
                .setMediaButtonPreferences(buildMediaButtonPreferences(currentSeekStepSeconds))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                ACTION_REWIND_STEP -> {
                    performRelativeSeek(-currentSeekStepSeconds * 1_000L)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                ACTION_FORWARD_STEP -> {
                    performRelativeSeek(currentSeekStepSeconds * 1_000L)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                else -> super.onCustomCommand(session, controller, customCommand, args)
            }
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "truyencast_playback_channel"
        const val ACTION_REWIND_STEP = "com.offline.audiobookplayer.ACTION_REWIND_STEP"
        const val ACTION_FORWARD_STEP = "com.offline.audiobookplayer.ACTION_FORWARD_STEP"

        val COMMAND_REWIND_STEP = SessionCommand(ACTION_REWIND_STEP, Bundle.EMPTY)
        val COMMAND_FORWARD_STEP = SessionCommand(ACTION_FORWARD_STEP, Bundle.EMPTY)

        private const val PERIODIC_SAVE_INTERVAL_MS = 5_000L
        private const val END_OF_TRACK_WATCH_INTERVAL_MS = 200L
        private const val SHUTDOWN_SAVE_TIMEOUT_MS = 1_500L
    }
}
