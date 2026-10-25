package com.aicode.feature.backup.presentation

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.rememberSheetFlingFix
import com.aicode.feature.backup.domain.remote.BackupTarget
import com.aicode.feature.backup.domain.remote.BackupTargetType
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Eye
import compose.icons.feathericons.EyeOff

/**
 * 网络备份目标的新建/编辑底部弹窗：按类型（WebDAV / S3）切换字段，支持连通性测试与保存。
 *
 * 不直接落库，保存与测试均通过回调交给调用方（ViewModel）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupTargetSheet(
    initial: BackupTarget?,
    onDismiss: () -> Unit,
    onSave: (BackupTarget) -> Unit,
    onTest: (BackupTarget, (Boolean, String) -> Unit) -> Unit
) {
    val context = LocalContext.current

    var type by remember(initial) { mutableStateOf(initial?.type ?: BackupTargetType.WEBDAV) }
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var endpoint by remember(initial) { mutableStateOf(initial?.endpoint ?: "") }
    var basePath by remember(initial) { mutableStateOf(initial?.basePath ?: "") }
    var username by remember(initial) { mutableStateOf(initial?.username ?: "") }
    var password by remember(initial) { mutableStateOf(initial?.password ?: "") }
    var passwordVisible by remember { mutableStateOf(false) }
    var accessKey by remember(initial) { mutableStateOf(initial?.accessKey ?: "") }
    var secretKey by remember(initial) { mutableStateOf(initial?.secretKey ?: "") }
    var secretVisible by remember { mutableStateOf(false) }
    var sessionToken by remember(initial) { mutableStateOf(initial?.sessionToken ?: "") }
    var bucket by remember(initial) { mutableStateOf(initial?.bucket ?: "") }
    var region by remember(initial) { mutableStateOf(initial?.region ?: "") }
    var pathStyle by remember(initial) { mutableStateOf(initial?.pathStyle ?: true) }
    var isTesting by remember { mutableStateOf(false) }

    fun currentTarget(): BackupTarget = BackupTarget(
        id = initial?.id ?: "",
        name = name.trim(),
        type = type,
        endpoint = endpoint.trim(),
        basePath = basePath.trim(),
        username = username.trim(),
        password = password,
        accessKey = accessKey.trim(),
        secretKey = secretKey,
        sessionToken = sessionToken.trim(),
        bucket = bucket.trim(),
        region = region.trim(),
        pathStyle = pathStyle,
        createdAt = initial?.createdAt ?: 0L
    )

    val valid = name.isNotBlank() && endpoint.isNotBlank() &&
        (type == BackupTargetType.WEBDAV || bucket.isNotBlank())

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val flingFix = rememberSheetFlingFix(sheetState)

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets(0.dp) },
        dialogMaxWidth = 600.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = screenHeight * 0.88f)) {
            BackupSheetTitle(
                text = stringResource(
                    if (initial != null) R.string.backup_net_edit_target else R.string.backup_net_add_target
                )
            )

            BackupTypeSegments(
                selected = type,
                onSelect = { type = it },
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(flingFix)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AppTextField(
                        value = name,
                        onValueChange = { name = it },
                        labelComposable = { Text(stringResource(R.string.backup_net_name)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    AppTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        labelComposable = { Text(stringResource(R.string.backup_net_endpoint)) },
                        placeholderComposable = { Text(stringResource(R.string.backup_net_endpoint_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    AppTextField(
                        value = basePath,
                        onValueChange = { basePath = it },
                        labelComposable = { Text(stringResource(R.string.backup_net_base_path)) },
                        placeholderComposable = { Text(stringResource(R.string.backup_net_base_path_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (type == BackupTargetType.WEBDAV) {
                        AppTextField(
                            value = username,
                            onValueChange = { username = it },
                            labelComposable = { Text(stringResource(R.string.backup_net_username)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        AppTextField(
                            value = password,
                            onValueChange = { password = it },
                            labelComposable = { Text(stringResource(R.string.backup_net_password)) },
                            visualTransformation = if (passwordVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                            trailingIcon = {
                                val image = if (passwordVisible) FeatherIcons.Eye else FeatherIcons.EyeOff
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        AppTextField(
                            value = bucket,
                            onValueChange = { bucket = it },
                            labelComposable = { Text(stringResource(R.string.backup_net_bucket)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        AppTextField(
                            value = region,
                            onValueChange = { region = it },
                            labelComposable = { Text(stringResource(R.string.backup_net_region)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        AppTextField(
                            value = accessKey,
                            onValueChange = { accessKey = it },
                            labelComposable = { Text(stringResource(R.string.backup_net_access_key)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        AppTextField(
                            value = secretKey,
                            onValueChange = { secretKey = it },
                            labelComposable = { Text(stringResource(R.string.backup_net_secret_key)) },
                            visualTransformation = if (secretVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                            trailingIcon = {
                                val image = if (secretVisible) FeatherIcons.Eye else FeatherIcons.EyeOff
                                IconButton(onClick = { secretVisible = !secretVisible }) {
                                    Icon(image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        AppTextField(
                            value = sessionToken,
                            onValueChange = { sessionToken = it },
                            labelComposable = { Text(stringResource(R.string.backup_net_session_token)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.backup_net_path_style),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            AppSwitch(checked = pathStyle, onCheckedChange = { pathStyle = it })
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            isTesting = true
                            onTest(currentTarget()) { _, msg ->
                                isTesting = false
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isTesting && endpoint.isNotBlank() &&
                            (type == BackupTargetType.WEBDAV || bucket.isNotBlank())
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.backup_net_test_connection))
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 12.dp, bottom = 24.dp)
                    .navigationBarsPadding()
            ) {
                Button(
                    enabled = valid,
                    onClick = { onSave(currentTarget()) },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(FeatherIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(if (initial != null) R.string.common_save else R.string.common_add),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}

@Composable
private fun BackupSheetTitle(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.size(36.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.size(36.dp))
    }
}

@Composable
private fun BackupTypeSegments(
    selected: BackupTargetType,
    onSelect: (BackupTargetType) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = listOf(
        BackupTargetType.WEBDAV to stringResource(R.string.backup_net_type_webdav),
        BackupTargetType.S3 to stringResource(R.string.backup_net_type_s3)
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        tabs.forEach { (value, title) ->
            val isSelected = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    ),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
