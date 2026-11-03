package com.aggapp.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** 品牌设计令牌（2026-10-24 淡绿清新风）：清新绿主色 + 绿青渐变 + 琥珀点缀 */
object Brand {
    val Primary = Color(0xFF4CAF50)
    val Teal = Color(0xFF26A69A)
    val Amber = Color(0xFFFFA43D)
    val PrimaryDeep = Color(0xFF2E7D32)
    // 头部渐变：贴合淡绿清新风的绿→青绿
    val Green = Color(0xFF43A047)
    val TealGreen = Color(0xFF26C6A8)

    /** 品牌渐变（头部区/按钮/图标同源） */
    val Gradient = Brush.horizontalGradient(listOf(Green, TealGreen))
    val GradientVertical = Brush.verticalGradient(listOf(Green, TealGreen))

    /** 各平台品牌色（胶囊选中底色、登录态标识） */
    val PlatformColor = mapOf(
        com.aggapp.core.model.Platform.PDD to Color(0xFFE02E24),
        com.aggapp.core.model.Platform.TAOBAO to Color(0xFFFF6A00),
        com.aggapp.core.model.Platform.JD to Color(0xFFE1251B),
        com.aggapp.core.model.Platform.SF to Color(0xFF00396F),
    )
}
