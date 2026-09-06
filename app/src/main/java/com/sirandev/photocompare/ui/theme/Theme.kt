package com.sirandev.photocompare.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    secondary = Secondary,
    error = ErrorLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    secondary = SecondaryDark,
    error = ErrorDark,
)

/**
 * App theme on Material 3 (stable line). The expressive *components* (SegmentedButton,
 * tonal icon buttons, PullToRefreshBox, ListItem settings rows, …) are used across the
 * app; the [MaterialExpressiveTheme]/MotionScheme theming entry points are still
 * `internal` in the current stable material3 and will be adopted once stabilized.
 *
 * @param darkTheme    whether to use the dark color scheme
 * @param dynamicColor dynamic color on Android 12+ (Material You wallpaper palette)
 */
@Composable
fun PhotoCompareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
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
        typography = AppTypography,
        content = content,
    )
}
