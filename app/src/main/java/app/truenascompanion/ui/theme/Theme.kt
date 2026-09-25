package app.truenascompanion.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.store.ThemeMode

private val Brand = Color(0xFF0095D5)
private val Navy = Color(0xFF0B3D62)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006590), onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E6FF), onPrimaryContainer = Color(0xFF001E2F),
    secondary = Color(0xFF4F616E), secondaryContainer = Color(0xFFD2E5F5), onSecondaryContainer = Color(0xFF0B1D29),
    tertiary = Color(0xFF63597C), tertiaryContainer = Color(0xFFE9DDFF),
    background = Color(0xFFF7F9FC), surface = Color(0xFFF7F9FC),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F4F8),
    surfaceContainer = Color(0xFFEBEEF3), surfaceContainerHigh = Color(0xFFE5E8ED), surfaceContainerHighest = Color(0xFFDFE3E8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF89CEFF), onPrimary = Color(0xFF00344D),
    primaryContainer = Color(0xFF004C6E), onPrimaryContainer = Color(0xFFC8E6FF),
    secondary = Color(0xFFB6C9D8), secondaryContainer = Color(0xFF374955), onSecondaryContainer = Color(0xFFD2E5F5),
    tertiary = Color(0xFFCDC0E9), tertiaryContainer = Color(0xFF4B4263),
    background = Color(0xFF0F1417), surface = Color(0xFF0F1417),
    surfaceContainerLowest = Color(0xFF0A0F12), surfaceContainerLow = Color(0xFF171C20),
    surfaceContainer = Color(0xFF1B2024), surfaceContainerHigh = Color(0xFF252B2E), surfaceContainerHighest = Color(0xFF303539),
)

/** Semantic status colors that read well in both light and dark themes. */
@Immutable
data class StatusColors(
    val healthy: Color, val healthyContainer: Color,
    val warning: Color, val warningContainer: Color,
    val critical: Color, val criticalContainer: Color,
    val neutral: Color, val neutralContainer: Color,
) {
    fun of(h: Health) = when (h) {
        Health.HEALTHY -> healthy
        Health.WARNING -> warning
        Health.CRITICAL -> critical
        Health.UNKNOWN -> neutral
    }

    fun containerOf(h: Health) = when (h) {
        Health.HEALTHY -> healthyContainer
        Health.WARNING -> warningContainer
        Health.CRITICAL -> criticalContainer
        Health.UNKNOWN -> neutralContainer
    }
}

private val LightStatus = StatusColors(
    healthy = Color(0xFF1B7F4B), healthyContainer = Color(0xFFD3F5E0),
    warning = Color(0xFF8A5A00), warningContainer = Color(0xFFFFE8BF),
    critical = Color(0xFFB3261E), criticalContainer = Color(0xFFFFDAD6),
    neutral = Color(0xFF5C6670), neutralContainer = Color(0xFFE3E8EE),
)
private val DarkStatus = StatusColors(
    healthy = Color(0xFF7EDBA3), healthyContainer = Color(0xFF12402A),
    warning = Color(0xFFFFC86B), warningContainer = Color(0xFF4A3300),
    critical = Color(0xFFFFB4AB), criticalContainer = Color(0xFF5C1512),
    neutral = Color(0xFFB5C0CA), neutralContainer = Color(0xFF2A3238),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val base = Typography()
private val AppTypography = base.copy(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(letterSpacing = 0.4.sp),
)

@Composable
fun TrueNasTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, dynamicColor: Boolean = true, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

@Suppress("unused")
val BrandBlue = Brand
@Suppress("unused")
val BrandNavy = Navy
