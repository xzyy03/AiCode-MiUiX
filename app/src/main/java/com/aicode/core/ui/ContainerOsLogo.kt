package com.aicode.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import com.aicode.R

/**
 * 容器系统类型 → 单色 logo；未识别（含 null）返回 null，由调用方回退通用容器图标。
 * 「容器与镜像」列表与文件树的当前容器根共用这一套映射，保证两处图标一致。
 */
@Composable
fun containerOsLogo(osId: String?): Painter? = when (osId) {
    "alpine" -> painterResource(R.drawable.logo_alpine)
    "centos" -> painterResource(R.drawable.logo_centos)
    "ubuntu" -> painterResource(R.drawable.logo_ubuntu)
    "debian" -> painterResource(R.drawable.logo_debian)
    else -> null
}
