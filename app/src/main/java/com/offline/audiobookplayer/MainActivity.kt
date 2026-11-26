package com.offline.audiobookplayer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offline.audiobookplayer.ui.player.JumpToTimeDialog
import com.offline.audiobookplayer.ui.player.PlayerScreen
import com.offline.audiobookplayer.ui.player.PlayerViewModel
import com.offline.audiobookplayer.ui.player.SleepTimerBottomSheet
import com.offline.audiobookplayer.ui.player.SpeedBottomSheet
import com.offline.audiobookplayer.ui.queue.QueueBottomSheet
import com.offline.audiobookplayer.ui.settings.SettingsScreen
import com.offline.audiobookplayer.ui.theme.AppThemeMode
import com.offline.audiobookplayer.ui.theme.TruyenCastTheme

class MainActivity : ComponentActivity() {

    private val playerViewModel: PlayerViewModel by viewModels {
        val container = (application as TruyenCastApplication).appContainer
        PlayerViewModel.Factory(
            application = application,
            repository = container.audiobookRepository,
            settingsDataStore = container.settingsDataStore,
            sleepTimerManager = container.sleepTimerManager
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC

        setContent {
            val uiState by playerViewModel.uiState.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val useDarkBars = when (uiState.settings.themeMode) {
                AppThemeMode.OLED, AppThemeMode.DARK -> true
                AppThemeMode.LIGHT -> false
                AppThemeMode.SYSTEM -> systemDark
            }

            LaunchedEffect(useDarkBars) {
                val barStyle = if (useDarkBars) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(
                    statusBarStyle = barStyle,
                    navigationBarStyle = barStyle
                )
            }

            TruyenCastTheme(themeMode = uiState.settings.themeMode) {
                TruyenCastRoot(viewModel = playerViewModel)
            }
        }
    }
}

@Composable
private fun TruyenCastRoot(viewModel: PlayerViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showQueueSheet by rememberSaveable { mutableStateOf(false) }
    var showSpeedSheet by rememberSaveable { mutableStateOf(false) }
    var showSleepTimerSheet by rememberSaveable { mutableStateOf(false) }
    var showJumpToTimeDialog by rememberSaveable { mutableStateOf(false) }

    // Lifecycle-safe MediaController connection (connects ON_START, releases ON_STOP)
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.connectController()
                Lifecycle.Event.ON_STOP -> viewModel.disconnectController()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            viewModel.connectController()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Request POST_NOTIFICATIONS runtime permission on Android 13+
    val context = androidx.compose.ui.platform.LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // SAF Pickers (single/multiple MP3 files and folder tree)
    val pickFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importFiles(uris)
        }
    }

    val pickFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            viewModel.importFolder(treeUri)
        }
    }

    val launchFilePicker = {
        pickFilesLauncher.launch(arrayOf("audio/mpeg", "audio/mp3", "audio/*"))
    }
    val launchFolderPicker = {
        pickFolderLauncher.launch(null)
    }

    LaunchedEffect(uiState.statusMessage) {
        val msg = uiState.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearStatusMessage()
    }

    LaunchedEffect(uiState.errorMessage) {
        val err = uiState.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(err)
        viewModel.clearErrorMessage()
    }

    BackHandler(enabled = showSettings) {
        showSettings = false
    }

    if (showSettings) {
        SettingsScreen(
            settings = uiState.settings,
            onBack = { showSettings = false },
            onUpdateSpeedPresets = viewModel::setSpeedPresets,
            onUpdateSeekStepSeconds = viewModel::setSeekStepSeconds,
            onUpdateAutoRewindSeconds = viewModel::setAutoRewindSeconds,
            onUpdateThemeMode = viewModel::setThemeMode,
            onUpdateVbrIndexSeeking = viewModel::setVbrIndexSeekingEnabled
        )
    } else {
        PlayerScreen(
            uiState = uiState,
            snackbarHostState = snackbarHostState,
            onTogglePlayPause = viewModel::togglePlayPause,
            onSeekTo = viewModel::seekToPosition,
            onSeekStep = viewModel::seekByStep,
            onPreviousTrack = viewModel::seekToPreviousTrack,
            onNextTrack = viewModel::seekToNextTrack,
            onVolumeChange = viewModel::setMediaVolume,
            onOpenSpeedSheet = { showSpeedSheet = true },
            onOpenJumpDialog = { showJumpToTimeDialog = true },
            onOpenSleepTimerSheet = { showSleepTimerSheet = true },
            onOpenQueueSheet = { showQueueSheet = true },
            onOpenSettings = { showSettings = true },
            onPickFiles = launchFilePicker,
            onPickFolder = launchFolderPicker
        )
    }

    if (showSpeedSheet) {
        SpeedBottomSheet(
            currentSpeed = uiState.settings.playbackSpeed,
            speedPresets = uiState.settings.speedPresets,
            onSpeedSelected = viewModel::setPlaybackSpeed,
            onDismiss = { showSpeedSheet = false }
        )
    }

    if (showJumpToTimeDialog) {
        JumpToTimeDialog(
            totalDurationMs = uiState.durationMs,
            onConfirmJump = viewModel::seekToPosition,
            onDismiss = { showJumpToTimeDialog = false }
        )
    }

    if (showSleepTimerSheet) {
        SleepTimerBottomSheet(
            sleepTimerState = uiState.sleepTimerState,
            onStartMinutes = viewModel::startSleepTimerMinutes,
            onStartEndOfTrack = viewModel::startSleepTimerEndOfTrack,
            onCancelTimer = viewModel::cancelSleepTimer,
            onDismiss = { showSleepTimerSheet = false }
        )
    }

    if (showQueueSheet) {
        QueueBottomSheet(
            queue = uiState.queue,
            activeTrackIndex = uiState.activeTrackIndex,
            activePositionMs = uiState.currentPositionMs,
            activeDurationMs = uiState.durationMs,
            isImporting = uiState.isImporting,
            onPickFiles = launchFilePicker,
            onPickFolder = launchFolderPicker,
            onSelectTrack = viewModel::selectTrackFromQueue,
            onMoveTrack = viewModel::moveTrack,
            onRemoveTrack = viewModel::removeTrack,
            onClearQueue = viewModel::clearQueue,
            onDismiss = { showQueueSheet = false }
        )
    }
}
