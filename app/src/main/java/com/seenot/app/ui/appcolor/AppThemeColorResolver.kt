package com.seenot.app.ui.appcolor

import androidx.compose.ui.graphics.Color

data class AppThemeColors(
    val background: Color,
    val content: Color
)

object AppThemeColorResolver {
    val defaultBackgrounds = listOf(
        Color(0xFFC86F69),
        Color(0xFFC36A83),
        Color(0xFF9E77B5),
        Color(0xFF7483BD),
        Color(0xFF5F95C7),
        Color(0xFF5E9F9A),
        Color(0xFF72A875),
        Color(0xFFC9A15C),
        Color(0xFFC8895F),
        Color(0xFF98766A),
        Color(0xFF73848E),
        Color(0xFF8676B8),
        Color(0xFF5AA2B0),
        Color(0xFF8EAA63),
        Color(0xFFB5B45F),
        Color(0xFFC98557)
    )

    private val brandBackgrounds = mapOf(
        "com.tencent.mm" to Color(0xFF5FAE87),
        "com.tencent.mobileqq" to Color(0xFF5FA6C8),
        "com.sina.weibo" to Color(0xFFC85D5D),
        "com.xingin.xhs" to Color(0xFFC85D6A),
        "com.ss.android.ugc.aweme" to Color(0xFF3F4750),
        "tv.danmaku.bili" to Color(0xFFD07F99),
        "com.zhihu.android" to Color(0xFF5B8FC7),
        "com.netease.cloudmusic" to Color(0xFFC75E58),
        "com.taobao.taobao" to Color(0xFFC9875F),
        "com.tmall.wireless" to Color(0xFFC85D65),
        "com.jingdong.app.mall" to Color(0xFFC85E58),
        "com.sankuai.meituan" to Color(0xFFCBB066),
        "com.eg.android.AlipayGphone" to Color(0xFF5B8FC7),
        "com.google.android.youtube" to Color(0xFFC75A54),
        "com.instagram.android" to Color(0xFFC56A8A),
        "com.twitter.android" to Color(0xFF3F4750),
        "com.zhiliaoapp.musically" to Color(0xFF3F4750),
        "com.reddit.frontpage" to Color(0xFFD27650),
        "com.discord" to Color(0xFF7479C0),
        "com.snapchat.android" to Color(0xFFCFC46E),
        "com.android.chrome" to Color(0xFF5F8FC5),
        "org.mozilla.firefox" to Color(0xFFD48355),
        "com.microsoft.emmx" to Color(0xFF5C9E96),
        "com.google.android.gm" to Color(0xFFC8665D),
        "com.google.android.apps.docs" to Color(0xFF6392C6),
        "com.microsoft.teams" to Color(0xFF7774B8),
        "com.Slack" to Color(0xFF8A5B8F),
        "notion.id" to Color(0xFF3F4750)
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
