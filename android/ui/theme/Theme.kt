package com.diss.location_based_diary.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// --- DARK MODE RULES ---
private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = LightBlue,
    surface = LightGreen,
    onSurface = Black
)

// --- LIGHT MODE RULES ---
private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
    background = LightBlue,
    surface = LightGreen,
    onSurface = Black
)

/**
 * Wraps your entire app to apply your chosen colors and fonts.
 */
@Composable
fun LBSTheme(
    darkTheme: Boolean = isSystemInDarkTheme(), // Detects if the phone is in Dark Mode

    // 🚨 THE DYNAMIC COLOR TRAP 🚨
    // If true, Android 12+ phones will ignore your custom LightBlue/LightGreen colors
    // and extract colors from the user's phone wallpaper instead to match their system!
    // Change this to 'false' if you want your app to strictly use YOUR custom colors.
    dynamicColor: Boolean = true,

    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // If the phone supports it and dynamicColor is true, use the user's wallpaper colors
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        // Otherwise, use the colors we specifically defined above
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography, // Pulls the fonts from Type.kt
        content = content
    )
}