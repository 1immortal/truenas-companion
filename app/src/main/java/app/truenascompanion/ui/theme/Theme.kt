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

// Brand: royal blue primary, glowing cyan accent, violet/teal companions for charts.
private val Royal = Color(0xFF2F5BEA)
private val RoyalLight = Color(0xFF4169E1)
private val Cyan = Color(0xFF22D3EE)
private val CyanBright = Color(0xFF00E5FF)

private val LightColors = lightColorScheme(
    primary = Royal, onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FF), onPrimaryContainer = Color(0xFF0A1F66),
    secondary = Color(0xFF0E7490), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFF7FE), onSecondaryContainer = Color(0xFF083344),
    tertiary = Color(0xFF6D4AE6), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEBE3FF), onTertiaryContainer = Color(0xFF22005D),
    background = Color(0xFFF3F6FF), onBackground = Color(0xFF0E1A3A),
    surface = Color(0xFFF3F6FF), onSurface = Color(0xFF0E1A3A),
    surfaceVariant = Color(0xFFE3E9FA), onSurfaceVariant = Color(0xFF4A5680),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFDFDFF),
    surfaceContainer = Color(0xFFEDF2FF), surfaceContainerHigh = Color(0xFFE6ECFF), surfaceContainerHighest = Color(0xFFDDE5FB),
    outline = Color(0xFF7A86AD), outlineVariant = Color(0xFFD3DBF2),
    inverseSurface = Color(0xFF16244F), inverseOnSurface = Color(0xFFE6ECFF), inversePrimary = Color(0xFFA9BCFF),
    surfaceTint = Royal,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6F8CFF), onPrimary = Color.White,
    primaryContainer = Color(0xFF1E3A8A), onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Cyan, onSecondary = Color(0xFF002A33),
    secondaryContainer = Color(0xFF0B4A5E), onSecondaryContainer = Color(0xFFCFF7FE),
    tertiary = Color(0xFFA78BFA), onTertiary = Color(0xFF22005D),
    tertiaryContainer = Color(0xFF3B2A7A), onTertiaryContainer = Color(0xFFEBE3FF),
    background = Color(0xFF0B1430), onBackground = Color(0xFFE6ECFF),
    surface = Color(0xFF0B1430), onSurface = Color(0xFFE6ECFF),
    surfaceVariant = Color(0xFF1B2B5C), onSurfaceVariant = Color(0xFFA9B6DA),
    surfaceContainerLowest = Color(0xFF081026), surfaceContainerLow = Color(0xFF121E44),
    surfaceContainer = Color(0xFF16244F), surfaceContainerHigh = Color(0xFF1B2B5C), surfaceContainerHighest = Color(0xFF243669),
    outline = Color(0xFF5A6A9A), outlineVariant = Color(0xFF2A3A6E),
    inverseSurface = Color(0xFFE6ECFF), inverseOnSurface = Color(0xFF16244F), inversePrimary = Royal,
    surfaceTint = Color(0xFF6F8CFF),
)

/** Extra brand colors (accent glow, gradients, chart palette, card border) that Material's scheme has no slot for. */
@Immutable
data class BrandColors(
    val accent: Color,
    val gaugeStart: Color,
    val gaugeEnd: Color,
    val glow: Color,
    val cardBorder: Color,
    val headerGlow: Color,
    val chartRx: Color,
    val chartTx: Color,
    val chartArc: Color,
    val dark: Boolean,
)

private val LightBrand = BrandColors(
    accent = Color(0xFF0891B2), gaugeStart = Royal, gaugeEnd = Color(0xFF06B6D4), glow = Color(0xFF3B82F6),
    cardBorder = Color(0xFFDCE3F7), headerGlow = Color(0xFF4169E1),
    chartRx = Color(0xFF0891B2), chartTx = Color(0xFF7C3AED), chartArc = Color(0xFF14B8A6), dark = false,
)
private val DarkBrand = BrandColors(
    accent = Cyan, gaugeStart = RoyalLight, gaugeEnd = CyanBright, glow = Cyan,
    cardBorder = Color(0xFF2B3F80), headerGlow = RoyalLight,
    chartRx = Cyan, chartTx = Color(0xFFA78BFA), chartArc = Color(0xFF2DD4BF), dark = true,
)

val LocalBrandColors = staticCompositionLocalOf { LightBrand }

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
    neutral = Color(0xFF55618A), neutralContainer = Color(0xFFE3E9FA),
)
private val DarkStatus = StatusColors(
    healthy = Color(0xFF6EE7A8), healthyContainer = Color(0xFF0F3B33),
    warning = Color(0xFFFFCC66), warningContainer = Color(0xFF3F3113),
    critical = Color(0xFFFF9A91), criticalContainer = Color(0xFF4A1A2A),
    neutral = Color(0xFFB4C0E4), neutralContainer = Color(0xFF22305C),
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
fun TrueNasTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, dynamicColor: Boolean = false, content: @Composable () -> Unit) {
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
    androidx.compose.runtime.CompositionLocalProvider(
        LocalStatusColors provides if (dark) DarkStatus else LightStatus,
        LocalBrandColors provides if (dark) DarkBrand else LightBrand,
    ) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

