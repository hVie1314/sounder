package com.offline.audiobookplayer.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offline.audiobookplayer.player.SleepTimerState
import com.offline.audiobookplayer.ui.theme.TimerDisplayLargeStyle
import com.offline.audiobookplayer.ui.theme.TimerLabelStyle
import com.offline.audiobookplayer.util.TimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    uiState: PlayerUiState,
    snackbarHostState: SnackbarHostState,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekStep: (Boolean) -> Unit,
    onPreviousTrack: () -> Unit,
    onNextTrack: () -> Unit,
    onVolumeChange: (Int) -> Unit,
    onOpenSpeedSheet: () -> Unit,
    onOpenJumpDialog: () -> Unit,
    onOpenSleepTimerSheet: () -> Unit,
    onOpenQueueSheet: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                ),
                title = {
                    Text(
                        text = "TruyenCast",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onOpenQueueSheet) {
                        BadgedBox(
                            badge = {
                                if (uiState.queue.isNotEmpty()) {
                                    Badge {
                                        Text(
                                            text = uiState.queue.size.toString(),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                contentDescription = "Mở danh sách phát"
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Cài đặt"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        if (!uiState.hasTracks) {
            EmptyPlayerContent(
                isImporting = uiState.isImporting,
                onPickFiles = onPickFiles,
                onPickFolder = onPickFolder,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp)
            )
        } else {
            ActivePlayerContent(
                uiState = uiState,
                onTogglePlayPause = onTogglePlayPause,
                onSeekTo = onSeekTo,
                onSeekStep = onSeekStep,
                onPreviousTrack = onPreviousTrack,
                onNextTrack = onNextTrack,
                onVolumeChange = onVolumeChange,
                onOpenSpeedSheet = onOpenSpeedSheet,
                onOpenJumpDialog = onOpenJumpDialog,
                onOpenSleepTimerSheet = onOpenSleepTimerSheet,
                onOpenQueueSheet = onOpenQueueSheet,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 20.dp)
            )
        }
    }
}

@Composable
private fun EmptyPlayerContent(
    isImporting: Boolean,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(96.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Headphones,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Chưa có sách nói nào",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Hỗ trợ file MP3 siêu lớn (8–10 GB), tự động ghi nhớ chính xác mốc thời gian nghe.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(28.dp))

        if (isImporting) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Đang nạp danh sách MP3...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Button(
                onClick = onPickFiles,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.AudioFile, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Chọn file MP3 (1 hoặc nhiều)")
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onPickFolder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.CreateNewFolder, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Chọn cả thư mục MP3")
            }
        }
    }
}

@Composable
private fun ActivePlayerContent(
    uiState: PlayerUiState,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekStep: (Boolean) -> Unit,
    onPreviousTrack: () -> Unit,
    onNextTrack: () -> Unit,
    onVolumeChange: (Int) -> Unit,
    onOpenSpeedSheet: () -> Unit,
    onOpenJumpDialog: () -> Unit,
    onOpenSleepTimerSheet: () -> Unit,
    onOpenQueueSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeTrack = uiState.activeTrack
    val totalDurationMs = uiState.durationMs.coerceAtLeast(0L)
    val livePositionMs = uiState.currentPositionMs.coerceIn(0L, totalDurationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)

    var isDraggingSeekBar by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val actualFraction = if (totalDurationMs > 0L) {
        (livePositionMs.toDouble() / totalDurationMs.toDouble()).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    val sliderFraction = if (isDraggingSeekBar) dragFraction else actualFraction
    val displayedPositionMs = if (isDraggingSeekBar && totalDurationMs > 0L) {
        (dragFraction.toDouble() * totalDurationMs.toDouble()).roundToLong().coerceIn(0L, totalDurationMs)
    } else {
        livePositionMs
    }
    val remainingMs = if (totalDurationMs > 0L) {
        (totalDurationMs - displayedPositionMs).coerceAtLeast(0L)
    } else {
        0L
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Track position pill + Sleep Timer live countdown badge
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = uiState.queuePositionLabel,
                        style = TimerLabelStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }

                if (uiState.sleepTimerState !is SleepTimerState.Inactive) {
                    SleepTimerBadge(
                        state = uiState.sleepTimerState,
                        onClick = onOpenSleepTimerSheet
                    )
                }

                if (uiState.isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = activeTrack?.title ?: "Không rõ tiêu đề",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (uiState.settings.vbrIndexSeekingEnabled) {
                    "VBR Index Seeking • ${formatSpeed(uiState.settings.playbackSpeed)}x"
                } else {
                    "CBR Fast (>4GB Safe) • ${formatSpeed(uiState.settings.playbackSpeed)}x"
                },
                style = TimerLabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Hero tabular/monospace timer
            Text(
                text = TimeFormatter.formatDuration(displayedPositionMs),
                style = TimerDisplayLargeStyle,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (totalDurationMs > 0L) {
                    "${TimeFormatter.formatDuration(totalDurationMs)}  •  Còn ${TimeFormatter.formatDuration(remainingMs)}"
                } else {
                    "Đang đọc thời lượng tệp..."
                },
                style = TimerLabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 64-bit safe interactive SeekBar
            Slider(
                value = sliderFraction,
                onValueChange = { fraction ->
                    isDraggingSeekBar = true
                    dragFraction = fraction.coerceIn(0f, 1f)
                },
                onValueChangeFinished = {
                    if (totalDurationMs > 0L) {
                        val targetMs = (dragFraction.toDouble() * totalDurationMs.toDouble())
                            .roundToLong()
                            .coerceIn(0L, totalDurationMs)
                        onSeekTo(targetMs)
                    }
                    isDraggingSeekBar = false
                },
                enabled = totalDurationMs > 0L,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Primary Transport Controls
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onPreviousTrack,
                    enabled = uiState.hasTracks,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = "Bài trước",
                        modifier = Modifier.size(32.dp)
                    )
                }

                SeekStepButton(
                    isForward = false,
                    stepSeconds = uiState.settings.seekStepSeconds,
                    onClick = { onSeekStep(false) }
                )

                FilledIconButton(
                    onClick = onTogglePlayPause,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        imageVector = if (uiState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (uiState.isPlaying) "Tạm dừng" else "Phát",
                        modifier = Modifier.size(38.dp)
                    )
                }

                SeekStepButton(
                    isForward = true,
                    stepSeconds = uiState.settings.seekStepSeconds,
                    onClick = { onSeekStep(true) }
                )

                IconButton(
                    onClick = onNextTrack,
                    enabled = uiState.activeTrackIndex < uiState.queue.lastIndex,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Bài tiếp",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Quick Action Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickActionButton(
                    icon = Icons.Rounded.Speed,
                    label = "${formatSpeed(uiState.settings.playbackSpeed)}x",
                    onClick = onOpenSpeedSheet,
                    modifier = Modifier.weight(1f)
                )
                QuickActionButton(
                    icon = Icons.Rounded.Schedule,
                    label = "Nhảy tới",
                    onClick = onOpenJumpDialog,
                    modifier = Modifier.weight(1f)
                )
                QuickActionButton(
                    icon = Icons.Rounded.Bedtime,
                    label = if (uiState.sleepTimerState is SleepTimerState.Inactive) "Hẹn giờ" else "Đang hẹn",
                    onClick = onOpenSleepTimerSheet,
                    modifier = Modifier.weight(1f)
                )
                QuickActionButton(
                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                    label = "Danh sách",
                    onClick = onOpenQueueSheet,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Volume Slider synced with AudioManager.STREAM_MUSIC
            VolumeControlRow(
                currentVolume = uiState.volumeState.current,
                maxVolume = uiState.volumeState.max,
                onVolumeChange = onVolumeChange
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SeekStepButton(
    isForward: Boolean,
    stepSeconds: Int,
    onClick: () -> Unit
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = if (isForward) Icons.Rounded.Forward10 else Icons.Rounded.Replay10,
                contentDescription = if (isForward) "Tua tới $stepSeconds giây" else "Tua lùi $stepSeconds giây",
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = if (isForward) "+${stepSeconds}s" else "-${stepSeconds}s",
                style = TimerLabelStyle
            )
        }
    }
}

@Composable
private fun QuickActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SleepTimerBadge(
    state: SleepTimerState,
    onClick: () -> Unit
) {
    val label = when (state) {
        is SleepTimerState.Countdown -> TimeFormatter.formatDuration(state.remainingMs)
        is SleepTimerState.EndOfTrack -> if (state.isFadingOut) "Đang nhỏ dần..." else "Hết file"
        SleepTimerState.Inactive -> ""
    }
    AssistChip(
        onClick = onClick,
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Bedtime,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        },
        label = {
            Text(text = label, style = TimerLabelStyle)
        }
    )
}

@Composable
private fun VolumeControlRow(
    currentVolume: Int,
    maxVolume: Int,
    onVolumeChange: (Int) -> Unit
) {
    val safeMax = maxVolume.coerceAtLeast(1)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    if (currentVolume > 0) onVolumeChange(0) else onVolumeChange((safeMax / 2).coerceAtLeast(1))
                }
            ) {
                Icon(
                    imageVector = if (currentVolume == 0) {
                        Icons.AutoMirrored.Rounded.VolumeOff
                    } else {
                        Icons.AutoMirrored.Rounded.VolumeUp
                    },
                    contentDescription = "Âm lượng đa phương tiện",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Slider(
                value = currentVolume.toFloat(),
                onValueChange = { value ->
                    val target = value.roundToInt().coerceIn(0, safeMax)
                    if (target != currentVolume) {
                        onVolumeChange(target)
                    }
                },
                valueRange = 0f..safeMax.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${(currentVolume * 100) / safeMax}%",
                style = TimerLabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

internal fun formatSpeed(speed: Float): String {
    return String.format(Locale.US, "%.1f", speed)
}
