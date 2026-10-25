package com.muse.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.muse.core.theme.Spacing

/**
 * miuix 风格大标题顶栏：首行是返回键与操作按钮，下方是左对齐的大标题。
 *
 * 与 M3 TopAppBar 的区别只在排版——返回键/操作仍在顶部一行，标题单独占一行、
 * 用 headline 字号压在内容上方，形成「设置」这类页面的层级感。
 */
@Composable
fun MiuixLargeTopBar(
    title: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.background,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    titleStyle: TextStyle = MaterialTheme.typography.headlineMedium
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            navigationIcon?.invoke()
            Spacer(Modifier.weight(1f))
            actions?.invoke(this)
        }
        if (title.isNotEmpty()) {
            Text(
                text = title,
                style = titleStyle,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = 10.dp)
            )
        }
    }
}
