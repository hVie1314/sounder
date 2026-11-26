package com.offline.audiobookplayer.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.offline.audiobookplayer.player.SleepTimerState
import com.offline.audiobookplayer.ui.theme.TimerLabelStyle
import com.offline.audiobookplayer.util.TimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SleepTimerBottomSheet(
    sleepTimerState: SleepTimerState,
    onStartMinutes: (Int) -> Unit,
    onStartEndOfTrack: () -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit
) {
    var customMinutesText by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = "Hẹn giờ tắt",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (sleepTimerState !is SleepTimerState.Inactive) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when (sleepTimerState) {
                                    is SleepTimerState.Countdown ->
                                        "Còn lại: ${TimeFormatter.formatDuration(sleepTimerState.remainingMs)}"
                                    is SleepTimerState.EndOfTrack ->
                                        "Dừng khi hết file hiện tại"
                                    SleepTimerState.Inactive -> ""
                                },
                                style = TimerLabelStyle,
                                color = MaterialTheme.colorScheme.primary
                            )
                            val fading = when (sleepTimerState) {
                                is SleepTimerState.Countdown -> sleepTimerState.isFadingOut
                                is SleepTimerState.EndOfTrack -> sleepTimerState.isFadingOut
                                SleepTimerState.Inactive -> false
                            }
                            if (fading) {
                                Text(
                                    text = "Đang giảm dần âm lượng...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        OutlinedButton(onClick = {
                            onCancelTimer()
                            onDismiss()
                        }) {
                            Text("Huỷ hẹn giờ")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(15, 30, 45, 60).forEach { minutes ->
                    FilledTonalButton(
                        onClick = {
                            onStartMinutes(minutes)
                            onDismiss()
                        }
                    ) {
                        Text("$minutes phút")
                    }
                }
                FilledTonalButton(
                    onClick = {
                        onStartEndOfTrack()
                        onDismiss()
                    }
                ) {
                    Text("Hết file hiện tại")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = customMinutesText,
                    onValueChange = {
                        customMinutesText = it.filter { ch -> ch.isDigit() }.take(3)
                        inputError = null
                    },
                    label = { Text("Số phút tuỳ chỉnh") },
                    placeholder = { Text("VD: 90") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = {
                        val parsed = customMinutesText.toIntOrNull()
                        if (parsed == null || parsed !in 1..720) {
                            inputError = "Nhập từ 1 đến 720 phút"
                        } else {
                            onStartMinutes(parsed)
                            onDismiss()
                        }
                    }
                ) {
                    Text("Bắt đầu")
                }
            }

            if (inputError != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = inputError!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Âm lượng sẽ giảm dần trong 3 giây cuối trước khi tạm dừng và lưu mốc thời gian.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
