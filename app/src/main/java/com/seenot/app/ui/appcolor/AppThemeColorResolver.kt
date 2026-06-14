package com.seenot.app.ui.appcolor

import androidx.compose.ui.graphics.Color

data class AppThemeColors(
    val background: Color,
    val content: Color
)

object AppThemeColorResolver {
    val defaultBackgrounds = listOf(
        Color(0xFFE53935),
        Color(0xFFD81B60),
        Color(0xFF8E24AA),
        Color(0xFF3949AB),
        Color(0xFF1E88E5),
        Color(0xFF00897B),
        Color(0xFF43A047),
        Color(0xFFF9A825),
        Color(0xFFFB8C00),
        Color(0xFF6D4C41),
        Color(0xFF546E7A),
        Color(0xFF5E35B1),
        Color(0xFF00ACC1),
        Color(0xFF7CB342),
        Color(0xFFC0CA33),
        Color(0xFFEF6C00)
    )

    private val brandBackgrounds = mapOf(
        "com.tencent.mm" to Color(0xFF07C160),
        "com.tencent.mobileqq" to Color(0xFF12B7F5),
        "com.sina.weibo" to Color(0xFFE6162D),
        "com.xingin.xhs" to Color(0xFFFF2442),
        "com.ss.android.ugc.aweme" to Color(0xFF111111),
        "tv.danmaku.bili" to Color(0xFFFB7299),
        "com.zhihu.android" to Color(0xFF1677FF),
        "com.netease.cloudmusic" to Color(0xFFD43C33),
        "com.taobao.taobao" to Color(0xFFFF6900),
        "com.tmall.wireless" to Color(0xFFFF0036),
        "com.jingdong.app.mall" to Color(0xFFE1251B),
        "com.sankuai.meituan" to Color(0xFFFFC300),
        "com.eg.android.AlipayGphone" to Color(0xFF1677FF),
        "com.google.android.youtube" to Color(0xFFFF0000),
        "com.instagram.android" to Color(0xFFE4405F),
        "com.twitter.android" to Color(0xFF111111),
        "com.zhiliaoapp.musically" to Color(0xFF111111),
        "com.reddit.frontpage" to Color(0xFFFF4500),
        "com.discord" to Color(0xFF5865F2),
        "com.snapchat.android" to Color(0xFFFFFC00),
        "com.android.chrome" to Color(0xFF1A73E8),
        "org.mozilla.firefox" to Color(0xFFFF7139),
        "com.microsoft.emmx" to Color(0xFF0F9D8A),
        "com.google.android.gm" to Color(0xFFEA4335),
        "com.google.android.apps.docs" to Color(0xFF4285F4),
        "com.microsoft.teams" to Color(0xFF6264A7),
        "com.Slack" to Color(0xFF611F69),
        "notion.id" to Color(0xFF111111)
    )

    fun resolve(packageName: String): AppThemeColors {
        val background = brandBackgrounds[packageName] ?: defaultBackgroundFor(packageName)
        return AppThemeColors(
            background = background,
            content = contentColorFor(background)
        )
    }

    fun contentColorFor(background: Color): Color {
        val luminance = 0.299f * background.red + 0.587f * background.green + 0.114f * background.blue
        return if (luminance > 0.62f) Color(0xFF111827) else Color.White
    }

    private fun defaultBackgroundFor(packageName: String): Color {
        val index = Math.floorMod(packageName.hashCode(), defaultBackgrounds.size)
        return defaultBackgrounds[index]
    }
}
