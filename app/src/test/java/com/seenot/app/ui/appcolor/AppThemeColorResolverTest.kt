package com.seenot.app.ui.appcolor

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeColorResolverTest {
    @Test
    fun knownPackagesUseMutedBrandColors() {
        assertEquals(Color(0xFF5FAE87), AppThemeColorResolver.resolve("com.tencent.mm").background)
        assertEquals(Color(0xFFC85D6A), AppThemeColorResolver.resolve("com.xingin.xhs").background)
        assertEquals(Color(0xFFC75A54), AppThemeColorResolver.resolve("com.google.android.youtube").background)
        assertEquals(Color(0xFFC9875F), AppThemeColorResolver.resolve("com.taobao.taobao").background)
    }

    @Test
    fun unknownPackageUsesStableFallbackColor() {
        val first = AppThemeColorResolver.resolve("com.example.notes")
        val second = AppThemeColorResolver.resolve("com.example.notes")

        assertEquals(first, second)
        assertTrue(AppThemeColorResolver.defaultBackgrounds.contains(first.background))
    }

    @Test
    fun unknownPackagesSpreadAcrossFallbackPalette() {
        val colors = listOf(
            "com.example.alpha",
            "com.example.beta",
            "com.example.gamma",
            "org.example.delta",
            "net.example.epsilon",
            "io.example.zeta",
            "app.example.eta",
            "site.example.theta"
        ).map { AppThemeColorResolver.resolve(it).background }.toSet()

        assertTrue("Expected unknown packages to use more than one fallback color", colors.size > 1)
    }

    @Test
    fun contentColorContrastsWithLightAndDarkBackgrounds() {
        assertEquals(Color(0xFF111827), AppThemeColorResolver.contentColorFor(Color(0xFFFDD835)))
        assertEquals(Color.White, AppThemeColorResolver.contentColorFor(Color(0xFF111827)))
    }

    @Test
    fun paletteAvoidsHighSaturationColors() {
        val sampledColors = AppThemeColorResolver.defaultBackgrounds + listOf(
            AppThemeColorResolver.resolve("com.tencent.mm").background,
            AppThemeColorResolver.resolve("com.xingin.xhs").background,
            AppThemeColorResolver.resolve("com.google.android.youtube").background,
            AppThemeColorResolver.resolve("com.taobao.taobao").background,
            AppThemeColorResolver.resolve("com.sankuai.meituan").background,
            AppThemeColorResolver.resolve("com.snapchat.android").background
        )

        sampledColors.forEach { color ->
            assertTrue("Expected muted color, got $color", saturation(color) <= 0.58f)
        }
    }

    private fun saturation(color: Color): Float {
        val max = maxOf(color.red, color.green, color.blue)
        val min = minOf(color.red, color.green, color.blue)
        if (max == min) return 0f
        val lightness = (max + min) / 2f
        val delta = max - min
        return delta / (1f - kotlin.math.abs(2f * lightness - 1f))
    }
}
