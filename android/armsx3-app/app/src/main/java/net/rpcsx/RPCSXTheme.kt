
package net.rpcsx

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat

// ARMSX3 palette. Seeded from the app logo (deep violet field, lavender glyph)
// rather than RPCSX's stock indigo -- purple is the PS3 house colour.
// Tonal steps follow Material3 conventions so every generated role stays
// contrast-correct in both schemes.
object colors {
    val primaryLight = Color(0xFF6A4BA8)
    val onPrimaryLight = Color(0xFFFFFFFF)
    val primaryContainerLight = Color(0xFFEADDFF)
    val onPrimaryContainerLight = Color(0xFF250057)
    val secondaryLight = Color(0xFF635B70)
    val onSecondaryLight = Color(0xFFFFFFFF)
    val secondaryContainerLight = Color(0xFFE9DEF8)
    val onSecondaryContainerLight = Color(0xFF1F182B)
    val tertiaryLight = Color(0xFF7E5260)
    val onTertiaryLight = Color(0xFFFFFFFF)
    val tertiaryContainerLight = Color(0xFFFFD9E3)
    val onTertiaryContainerLight = Color(0xFF31101D)
    val errorLight = Color(0xFFBA1A1A)
    val onErrorLight = Color(0xFFFFFFFF)
    val errorContainerLight = Color(0xFFFFDAD6)
    val onErrorContainerLight = Color(0xFF93000A)
    val backgroundLight = Color(0xFFFEF7FF)
    val onBackgroundLight = Color(0xFF1D1B20)
    val surfaceLight = Color(0xFFFEF7FF)
    val onSurfaceLight = Color(0xFF1D1B20)
    val surfaceVariantLight = Color(0xFFE7E0EB)
    val onSurfaceVariantLight = Color(0xFF49454E)
    val outlineLight = Color(0xFF7A757F)
    val outlineVariantLight = Color(0xFFCAC4CF)
    val scrimLight = Color(0xFF000000)
    val inverseSurfaceLight = Color(0xFF322F35)
    val inverseOnSurfaceLight = Color(0xFFF5EFF7)
    val inversePrimaryLight = Color(0xFFD3BBFF)
    val surfaceDimLight = Color(0xFFDED8E0)
    val surfaceBrightLight = Color(0xFFFEF7FF)
    val surfaceContainerLowestLight = Color(0xFFFFFFFF)
    val surfaceContainerLowLight = Color(0xFFF8F1FA)
    val surfaceContainerLight = Color(0xFFF2ECF4)
    val surfaceContainerHighLight = Color(0xFFECE6EE)
    val surfaceContainerHighestLight = Color(0xFFE6E0E9)

    val primaryDark = Color(0xFFD3BBFF)
    val onPrimaryDark = Color(0xFF3A1D76)
    val primaryContainerDark = Color(0xFF51358E)
    val onPrimaryContainerDark = Color(0xFFEADDFF)
    val secondaryDark = Color(0xFFCDC2DB)
    val onSecondaryDark = Color(0xFF342D41)
    val secondaryContainerDark = Color(0xFF4B4358)
    val onSecondaryContainerDark = Color(0xFFE9DEF8)
    val tertiaryDark = Color(0xFFEFB8C8)
    val onTertiaryDark = Color(0xFF4A2532)
    val tertiaryContainerDark = Color(0xFF633B48)
    val onTertiaryContainerDark = Color(0xFFFFD9E3)
    val errorDark = Color(0xFFFFB4AB)
    val onErrorDark = Color(0xFF690005)
    val errorContainerDark = Color(0xFF93000A)
    val onErrorContainerDark = Color(0xFFFFDAD6)
    val backgroundDark = Color(0xFF15121A)
    val onBackgroundDark = Color(0xFFE7E0E9)
    val surfaceDark = Color(0xFF15121A)
    val onSurfaceDark = Color(0xFFE7E0E9)
    val surfaceVariantDark = Color(0xFF49454E)
    val onSurfaceVariantDark = Color(0xFFCAC4CF)
    val outlineDark = Color(0xFF948F99)
    val outlineVariantDark = Color(0xFF49454E)
    val scrimDark = Color(0xFF000000)
    val inverseSurfaceDark = Color(0xFFE7E0E9)
    val inverseOnSurfaceDark = Color(0xFF322F35)
    val inversePrimaryDark = Color(0xFF6A4BA8)
    val surfaceDimDark = Color(0xFF15121A)
    val surfaceBrightDark = Color(0xFF3B3841)
    val surfaceContainerLowestDark = Color(0xFF0F0D14)
    val surfaceContainerLowDark = Color(0xFF1D1B20)
    val surfaceContainerDark = Color(0xFF211F26)
    val surfaceContainerHighDark = Color(0xFF2C2931)
    val surfaceContainerHighestDark = Color(0xFF37333C)
}

private val lightScheme = lightColorScheme(
    primary = colors.primaryLight,
    onPrimary = colors.onPrimaryLight,
    primaryContainer = colors.primaryContainerLight,
    onPrimaryContainer = colors.onPrimaryContainerLight,
    secondary = colors.secondaryLight,
    onSecondary = colors.onSecondaryLight,
    secondaryContainer = colors.secondaryContainerLight,
    onSecondaryContainer = colors.onSecondaryContainerLight,
    tertiary = colors.tertiaryLight,
    onTertiary = colors.onTertiaryLight,
    tertiaryContainer = colors.tertiaryContainerLight,
    onTertiaryContainer = colors.onTertiaryContainerLight,
    error = colors.errorLight,
    onError = colors.onErrorLight,
    errorContainer = colors.errorContainerLight,
    onErrorContainer = colors.onErrorContainerLight,
    background = colors.backgroundLight,
    onBackground = colors.onBackgroundLight,
    surface = colors.surfaceLight,
    onSurface = colors.onSurfaceLight,
    surfaceVariant = colors.surfaceVariantLight,
    onSurfaceVariant = colors.onSurfaceVariantLight,
    outline = colors.outlineLight,
    outlineVariant = colors.outlineVariantLight,
    scrim = colors.scrimLight,
    inverseSurface = colors.inverseSurfaceLight,
    inverseOnSurface = colors.inverseOnSurfaceLight,
    inversePrimary = colors.inversePrimaryLight,
    surfaceDim = colors.surfaceDimLight,
    surfaceBright = colors.surfaceBrightLight,
    surfaceContainerLowest = colors.surfaceContainerLowestLight,
    surfaceContainerLow = colors.surfaceContainerLowLight,
    surfaceContainer = colors.surfaceContainerLight,
    surfaceContainerHigh = colors.surfaceContainerHighLight,
    surfaceContainerHighest = colors.surfaceContainerHighestLight,
)

private val darkScheme = darkColorScheme(
    primary = colors.primaryDark,
    onPrimary = colors.onPrimaryDark,
    primaryContainer = colors.primaryContainerDark,
    onPrimaryContainer = colors.onPrimaryContainerDark,
    secondary = colors.secondaryDark,
    onSecondary = colors.onSecondaryDark,
    secondaryContainer = colors.secondaryContainerDark,
    onSecondaryContainer = colors.onSecondaryContainerDark,
    tertiary = colors.tertiaryDark,
    onTertiary = colors.onTertiaryDark,
    tertiaryContainer = colors.tertiaryContainerDark,
    onTertiaryContainer = colors.onTertiaryContainerDark,
    error = colors.errorDark,
    onError = colors.onErrorDark,
    errorContainer = colors.errorContainerDark,
    onErrorContainer = colors.onErrorContainerDark,
    background = colors.backgroundDark,
    onBackground = colors.onBackgroundDark,
    surface = colors.surfaceDark,
    onSurface = colors.onSurfaceDark,
    surfaceVariant = colors.surfaceVariantDark,
    onSurfaceVariant = colors.onSurfaceVariantDark,
    outline = colors.outlineDark,
    outlineVariant = colors.outlineVariantDark,
    scrim = colors.scrimDark,
    inverseSurface = colors.inverseSurfaceDark,
    inverseOnSurface = colors.inverseOnSurfaceDark,
    inversePrimary = colors.inversePrimaryDark,
    surfaceDim = colors.surfaceDimDark,
    surfaceBright = colors.surfaceBrightDark,
    surfaceContainerLowest = colors.surfaceContainerLowestDark,
    surfaceContainerLow = colors.surfaceContainerLowDark,
    surfaceContainer = colors.surfaceContainerDark,
    surfaceContainerHigh = colors.surfaceContainerHighDark,
    surfaceContainerHighest = colors.surfaceContainerHighestDark,
)

@Composable
fun RPCSXTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // TODO(Ishan09811): Implement dynamic colors option whenever settings gets implemented
    val colors = if (darkTheme) darkScheme else lightScheme

    val view = LocalView.current
    val activity = view.context as? Activity

    SideEffect {
        activity?.window?.apply {
            statusBarColor = android.graphics.Color.TRANSPARENT
            navigationBarColor = android.graphics.Color.TRANSPARENT
            isNavigationBarContrastEnforced = false
            val insetsController = WindowInsetsControllerCompat(this, decorView)
            insetsController.isAppearanceLightNavigationBars = !darkTheme
            insetsController.isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        content = content
    )
}
