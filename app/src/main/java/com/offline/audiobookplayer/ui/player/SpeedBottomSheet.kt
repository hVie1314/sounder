package com.offline.audiobookplayer.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.offline.audiobookplayer.data.model.UserSettings
import com.offline.audiobookplayer.ui.theme.TimerDisplayLargeStyle
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedBottomSheet(
    currentSpeed: Float,
    speedPresets: List<Float>,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Tốc độ phát",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "${formatSpeed(currentSpeed)}x",
                style = TimerDisplayLargeStyle,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Customizable Preset Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
            ) {
                speedPresets.forEach { preset ->
                    val isSelected = abs(currentSpeed - preset) < 0.05f
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSpeedSelected(preset) },
                        label = { Text("${formatSpeed(preset)}x") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Fine -0.1x / Slider / +0.1x
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalIconButton(
                    onClick = {
                        val next = ((currentSpeed * 10f).roundToInt() - 1) / 10f
                        onSpeedSelected(next.coerceAtLeast(UserSettings.MIN_SPEED))
                    },
                    enabled = currentSpeed > UserSettings.MIN_SPEED + 0.01f
                ) {
                    Icon(Icons.Rounded.Remove, contentDescription = "Giảm 0.1x")
                }

                Slider(
                    value = currentSpeed,
                    onValueChange = { raw ->
                        val snapped = (raw * 10f).roundToInt() / 10f
                        onSpeedSelected(snapped)
                    },
                    valueRange = UserSettings.MIN_SPEED..UserSettings.MAX_SPEED,
                    steps = 24,
                    modifier = Modifier.weight(1f)
                )

                FilledTonalIconButton(
                    onClick = {
                        val next = ((currentSpeed * 10f).roundToInt() + 1) / 10f
                        onSpeedSelected(next.coerceAtMost(UserSettings.MAX_SPEED))
                    },
                    enabled = currentSpeed < UserSettings.MAX_SPEED - 0.01f
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Tăng 0.1x")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Phạm vi 0.5x – 3.0x (giữ nguyên cao độ giọng đọc)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
