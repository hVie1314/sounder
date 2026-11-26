package com.offline.audiobookplayer.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.offline.audiobookplayer.ui.theme.TimerLabelStyle
import com.offline.audiobookplayer.util.JumpTimeError
import com.offline.audiobookplayer.util.JumpTimeValidation
import com.offline.audiobookplayer.util.TimeFormatter

@Composable
fun JumpToTimeDialog(
    totalDurationMs: Long,
    onConfirmJump: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var hoursText by remember { mutableStateOf("") }
    var minutesText by remember { mutableStateOf("") }
    var secondsText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nhảy tới thời điểm") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (totalDurationMs > 0L) {
                        "Thời lượng tệp: ${TimeFormatter.formatDuration(totalDurationMs)}"
                    } else {
                        "Chưa xác định được tổng thời lượng tệp"
                    },
                    style = TimerLabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = hoursText,
                        onValueChange = {
                            hoursText = it.filter { ch -> ch.isDigit() }
                            errorMessage = null
                        },
                        label = { Text("Giờ") },
                        placeholder = { Text("05") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = {
                            minutesText = it.filter { ch -> ch.isDigit() }.take(2)
                            errorMessage = null
                        },
                        label = { Text("Phút") },
                        placeholder = { Text("20") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = secondsText,
                        onValueChange = {
                            secondsText = it.filter { ch -> ch.isDigit() }.take(2)
                            errorMessage = null
                        },
                        label = { Text("Giây") },
                        placeholder = { Text("00") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (val result = TimeFormatter.parseAndValidateJumpTime(
                        hoursText = hoursText,
                        minutesText = minutesText,
                        secondsText = secondsText,
                        totalDurationMs = totalDurationMs
                    )) {
                        is JumpTimeValidation.Valid -> {
                            onConfirmJump(result.targetPositionMs)
                            onDismiss()
                        }

                        is JumpTimeValidation.Invalid -> {
                            errorMessage = mapJumpError(result.error, totalDurationMs)
                        }
                    }
                }
            ) {
                Text("Đi tới")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Huỷ")
            }
        }
    )
}

private fun mapJumpError(error: JumpTimeError, totalDurationMs: Long): String {
    return when (error) {
        JumpTimeError.EMPTY_INPUT -> "Vui lòng nhập ít nhất một ô Giờ, Phút hoặc Giây."
        JumpTimeError.INVALID_NUMBER -> "Giá trị nhập không hợp lệ."
        JumpTimeError.NEGATIVE_VALUE -> "Thời gian không được âm."
        JumpTimeError.MINUTES_OUT_OF_RANGE -> "Phút phải nằm trong khoảng 00 – 59."
        JumpTimeError.SECONDS_OUT_OF_RANGE -> "Giây phải nằm trong khoảng 00 – 59."
        JumpTimeError.UNKNOWN_DURATION -> "Chưa đọc xong thời lượng tệp để nhảy thời gian."
        JumpTimeError.EXCEEDS_DURATION ->
            "Vượt quá tổng thời lượng (${TimeFormatter.formatDuration(totalDurationMs)})."
        JumpTimeError.OVERFLOW -> "Giá trị giờ quá lớn."
    }
}
