package com.roderickqiu.seenot.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Yellow80,
    secondary = YellowGrey80,
    tertiary = YellowOrange80,
    primaryContainer = YellowContainer80,
    secondaryContainer = YellowGreyContainer80,
    tertiaryContainer = YellowOrangeContainer80
)

private val LightColorScheme = lightColorScheme(
    primary = Yellow40,
    secondary = YellowGrey40,
    tertiary = YellowOrange40,
    primaryContainer = YellowContainer40,
    secondaryContainer = YellowGreyContainer40,
    tertiaryContainer = YellowOrangeContainer40,
    // Ensure high-contrast text using same yellow tonal family
    onPrimary = Yellow10,
    onPrimaryContainer = Yellow20

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Composable
fun SeeNotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}