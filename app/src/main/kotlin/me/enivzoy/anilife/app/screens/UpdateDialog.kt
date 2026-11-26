package me.enivzoy.anilife.app.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.enivzoy.anilife.app.data.UpdateState
import me.enivzoy.anilife.app.ui.AniLifeColors
import me.enivzoy.anilife.app.ui.appEnter
import me.enivzoy.anilife.app.ui.liquidGlass
import me.enivzoy.anilife.core.model.Update

@Composable
fun UpdateDialog(
    update: Update,
    state: UpdateState,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onShareLogs: () -> Unit,
    shareNotice: Boolean,
) {
    AlertDialog(
        onDismissRequest = { if (!state.busy) onLater() },
        modifier = Modifier.liquidGlass(shape = RoundedCornerShape(28.dp)).appEnter(),
        containerColor = AniLifeColors.Card,
        titleContentColor = AniLifeColors.Foreground,
        textContentColor = AniLifeColors.MutedForeground,
        title = { Text("Update available", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = if (update.changelog.isBlank()) {
                        "AniLife ${update.version} is available."
                    } else {
                        "AniLife ${update.version} is available.\n\n${update.changelog}"
                    },
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                )
                when (state) {
                    UpdateState.Idle -> Unit
                    is UpdateState.Downloading -> {
                        val progress = state.progress
                        val total = update.sizeBytes.takeIf { it > 0L }
                        val bytes = if (total != null && progress != null) {
                            (total * progress).toLong()
                        } else {
                            0L
                        }
                        val detail = if (total != null && progress != null) {
                            formatUpdateProgress(bytes, total)
                        } else {
                            ""
                        }
                        Spacer(Modifier.height(12.dp))
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = AniLifeColors.Primary,
                                strokeCap = StrokeCap.Round,
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = AniLifeColors.Primary,
                                strokeCap = StrokeCap.Round,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (detail.isBlank()) "Downloading…" else "Downloading… $detail",
                            color = AniLifeColors.MutedForeground,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                    UpdateState.Installing -> {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = AniLifeColors.Primary,
                            strokeCap = StrokeCap.Round,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "AniLife will close. Windows will show installation progress and reopen the app when finished.",
                            color = AniLifeColors.MutedForeground,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                    is UpdateState.Failed -> {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = state.message,
                            color = AniLifeColors.Destructive,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onShareLogs,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AniLifeColors.Primary),
                        ) {
                            Text("Share logs", fontSize = 12.sp)
                        }
                        if (shareNotice) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Update logs copied to clipboard and folder opened.",
                                color = AniLifeColors.MutedForeground,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onUpdate, enabled = !state.busy) {
                Text(
                    if (state is UpdateState.Failed) "Retry" else "Update now",
                    color = AniLifeColors.Primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onLater, enabled = !state.busy) {
                Text("Later", color = AniLifeColors.MutedForeground, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

internal fun formatUpdateProgress(bytes: Long, total: Long?): String =
    if (total != null && total > 0L) {
        "${bytes * 100 / total}% (${formatSetupBytes(bytes)} / ${formatSetupBytes(total)})"
    } else {
        formatSetupBytes(bytes)
    }
