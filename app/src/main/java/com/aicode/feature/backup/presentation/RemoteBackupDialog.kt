package com.aicode.feature.backup.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.backup.domain.remote.RemoteBackup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 云端备份列表弹窗：展示远端备份（名称/大小/时间），支持恢复与二次确认删除。 */
@Composable
fun RemoteBackupDialog(
    targetName: String,
    items: List<RemoteBackup>,
    onRestore: (RemoteBackup) -> Unit,
    onDelete: (RemoteBackup) -> Unit,
    onDismiss: () -> Unit
) {
    var pendingDelete by remember { mutableStateOf<RemoteBackup?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${stringResource(R.string.backup_net_remote_list_title)} · $targetName") },
        text = {
            if (items.isEmpty()) {
                Text(
                    text = stringResource(R.string.backup_net_remote_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Spacing.md)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    items(items, key = { it.name }) { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = remoteBackupSubtitle(item),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { onRestore(item) }) {
                                Text(stringResource(R.string.backup_net_remote_restore))
                            }
                            TextButton(onClick = { pendingDelete = item }) {
                                Text(
                                    text = stringResource(R.string.backup_net_remote_delete),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        androidx.compose.material3.HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.backup_net_remote_delete)) },
            text = { Text(stringResource(R.string.backup_net_remote_delete_confirm, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(target)
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

private fun remoteBackupSubtitle(item: RemoteBackup): String {
    val size = formatSize(item.size)
    if (item.lastModified <= 0L) return size
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.lastModified))
    return "$size · $time"
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
