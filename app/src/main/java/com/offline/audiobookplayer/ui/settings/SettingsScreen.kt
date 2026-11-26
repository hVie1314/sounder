package com.offline.audiobookplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.offline.audiobookplayer.data.model.UserSettings
import com.offline.audiobookplayer.ui.player.formatSpeed
import com.offline.audiobookplayer.ui.theme.AppThemeMode
import com.offline.audiobookplayer.ui.theme.TimerLabelStyle
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: UserSettings,
    onBack: () -> Unit,
    onUpdateSpeedPresets: (List<Float>) -> Unit,
    onUpdateSeekStepSeconds: (Int) -> Unit,
    onUpdateAutoRewindSeconds: (Int) -> Unit,
    onUpdateThemeMode: (AppThemeMode) -> Unit,
    onUpdateVbrIndexSeeking: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var customSeekStepText by remember(settings.seekStepSeconds) {
        mutableStateOf(settings.seekStepSeconds.toString())
    }
    var seekStepError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Cài đặt") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Quay lại"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 1. Speed Presets
            SettingsSectionCard(title = "Các mức tốc độ Preset") {
                settings.speedPresets.forEachIndexed { index, preset ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Preset ${index + 1}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalIconButton(
                                onClick = {
                                    val updated = settings.speedPresets.toMutableList()
                                    val nextVal = ((preset * 10f).roundToInt() - 1) / 10f
                                    updated[index] = nextVal.coerceAtLeast(UserSettings.MIN_SPEED)
                                    onUpdateSpeedPresets(updated)
                                },
                                enabled = preset > UserSettings.MIN_SPEED + 0.01f
                            ) {
                                Icon(Icons.Rounded.Remove, contentDescription = "Giảm 0.1x")
                            }
                            Text(
                                text = "${formatSpeed(preset)}x",
                                style = TimerLabelStyle,
                                modifier = Modifier.padding(horizontal = 14.dp)
                            )
                            FilledTonalIconButton(
                                onClick = {
                                    val updated = settings.speedPresets.toMutableList()
                                    val nextVal = ((preset * 10f).roundToInt() + 1) / 10f
                                    updated[index] = nextVal.coerceAtMost(UserSettings.MAX_SPEED)
                                    onUpdateSpeedPresets(updated)
                                },
                                enabled = preset < UserSettings.MAX_SPEED - 0.01f
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = "Tăng 0.1x")
                            }
                        }
                    }
                }
                TextButton(
                    onClick = { onUpdateSpeedPresets(UserSettings.DEFAULT_SPEED_PRESETS) }
                ) {
                    Text("Khôi phục mặc định (0.5x / 1.0x / 2.0x)")
                }
            }

            // 2. Seek Step
            SettingsSectionCard(title = "Bước tua tiến / lùi (giây)") {
                Text(
                    text = "Áp dụng ngay trên màn hình phát, màn hình khoá và thông báo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(5, 10, 15, 30, 60).forEach { seconds ->
                        FilterChip(
                            selected = settings.seekStepSeconds == seconds,
                            onClick = {
                                seekStepError = null
                                onUpdateSeekStepSeconds(seconds)
                            },
                            label = { Text("${seconds}s") }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customSeekStepText,
                        onValueChange = {
                            customSeekStepText = it.filter { ch -> ch.isDigit() }.take(3)
                            seekStepError = null
                        },
                        label = { Text("Số giây tuỳ chỉnh (1–300)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = {
                            val parsed = customSeekStepText.toIntOrNull()
                            if (parsed == null || parsed !in UserSettings.MIN_SEEK_STEP_SECONDS..UserSettings.MAX_SEEK_STEP_SECONDS) {
                                seekStepError = "Nhập trong khoảng 1 – 300 giây"
                            } else {
                                onUpdateSeekStepSeconds(parsed)
                            }
                        }
                    ) {
                        Text("Lưu")
                    }
                }
                if (seekStepError != null) {
                    Text(
                        text = seekStepError!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // 3. Auto-rewind on resume
            SettingsSectionCard(title = "Tự động lùi khi mở lại app") {
                Text(
                    text = "Tự động lùi nhẹ vài giây khi khôi phục phiên nghe trước để dễ bắt nhịp.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0, 3, 5, 10).forEach { sec ->
                        FilterChip(
                            selected = settings.autoRewindSeconds == sec,
                            onClick = { onUpdateAutoRewindSeconds(sec) },
                            label = {
                                Text(if (sec == 0) "0s (Chính xác)" else "${sec}s")
                            }
                        )
                    }
                }
            }

            // 4. Theme Selector
            SettingsSectionCard(title = "Giao diện (Theme)") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val options = listOf(
                        AppThemeMode.OLED to "OLED Đen tuyền",
                        AppThemeMode.DARK to "Tối (Dark)",
                        AppThemeMode.LIGHT to "Sáng (Light)",
                        AppThemeMode.SYSTEM to "Hệ thống"
                    )
                    options.forEach { (mode, label) ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { onUpdateThemeMode(mode) },
                            label = { Text(label) }
                        )
                    }
                }
            }

            // 5. MP3 Seeking Mode
            SettingsSectionCard(title = "Bộ giải mã MP3 & Bảo vệ >4 GB") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Chế độ tua chính xác cho file VBR (Index Seeking)",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Mặc định TẮT: dùng CBR Seeking an toàn cho file 8–10 GB, tránh tràn 32-bit Xing header và tua tức thì không tốn RAM. Chỉ bật nếu nghe file VBR < 4 GB bị lệch mốc thời gian.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = settings.vbrIndexSeekingEnabled,
                        onCheckedChange = onUpdateVbrIndexSeeking
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
            content()
        }
    }
}
