package com.seenot.app.ui.appcolor

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeColorResolverTest {
    @Test
    fun knownPackagesUseBrandColors() {
        assertEquals(Color(0xFF07C160), AppThemeColorResolver.resolve("com.tencent.mm").background)
        assertEquals(Color(0xFFFF2442), AppThemeColorResolver.resolve("com.xingin.xhs").background)
        assertEquals(Color(0xFFFF0000), AppThemeColorResolver.resolve("com.google.android.youtube").background)
        assertEquals(Color(0xFFFF6900), AppThemeColorResolver.resolve("com.taobao.taobao").background)
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
}
