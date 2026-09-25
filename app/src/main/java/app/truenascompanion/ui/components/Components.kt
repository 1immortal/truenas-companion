package app.truenascompanion.ui.components

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import app.truenascompanion.ui.theme.LocalBrandColors
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.automirrored.rounded.Help
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.truenascompanion.data.model.Health
import app.truenascompanion.ui.theme.LocalStatusColors

/** Generic screen state. */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String, val cause: Throwable? = null) : UiState<Nothing>
}

val <T> UiState<T>.dataOrNull: T? get() = (this as? UiState.Success)?.data

@Composable
fun StatusChip(health: Health, label: String, modifier: Modifier = Modifier, showIcon: Boolean = true) {
    val colors = LocalStatusColors.current
    val bg by animateColorAsState(colors.containerOf(health), label = "chipBg")
    val fg by animateColorAsState(colors.of(health), label = "chipFg")
    Row(
        modifier = modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (showIcon) {
            Icon(
                imageVector = when (health) {
                    Health.HEALTHY -> Icons.Rounded.CheckCircle
                    Health.WARNING -> Icons.Rounded.Warning
                    Health.CRITICAL -> Icons.Rounded.Error
                    Health.UNKNOWN -> Icons.AutoMirrored.Rounded.Help
                },
                contentDescription = null, tint = fg, modifier = Modifier.size(14.dp),
            )
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg, fontWeight = FontWeight.SemiBold)
    }
}

/** Round tinted icon "avatar" used as the leading element of cards. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary, size: Dp = 40.dp) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f)) }
}

@Composable
fun LetterAvatar(text: String, size: Dp = 44.dp) {
    val palette = listOf(
        Color(0xFF0095D5), Color(0xFF7C4DFF), Color(0xFF00A67E), Color(0xFFEF6C00),
        Color(0xFFD81B60), Color(0xFF3949AB), Color(0xFF00897B), Color(0xFF6D4C41),
    )
    val c = palette[(text.hashCode() and 0x7fffffff) % palette.size]
    Box(
        Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(c, c.copy(alpha = 0.7f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.take(1).uppercase(), color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun ElevatedSection(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val brand = LocalBrandColors.current
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val border = BorderStroke(1.dp, if (brand.dark) brand.cardBorder.copy(alpha = 0.55f) else brand.cardBorder)
    // Light mode: a soft blue-tinted shadow lifts the white cards off the tinted background.
    val shadowMod = if (brand.dark) Modifier else Modifier.shadow(
        6.dp, MaterialTheme.shapes.large, clip = false,
        ambientColor = brand.glow.copy(alpha = 0.10f), spotColor = brand.glow.copy(alpha = 0.14f),
    )
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.then(shadowMod), shape = MaterialTheme.shapes.large, colors = colors, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Card(modifier = modifier.then(shadowMod), shape = MaterialTheme.shapes.large, colors = colors, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

/** Soft colored halo behind an element (colored shadows render on Android 9+; older versions show a plain shadow). */
fun Modifier.glow(color: Color, radius: Dp = 12.dp, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(50), alpha: Float = 0.55f): Modifier =
    this.shadow(radius, shape, clip = false, ambientColor = color.copy(alpha = alpha), spotColor = color.copy(alpha = alpha))

/** Primary action button with the brand gradient glow. */
@Composable
fun GlowButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val brand = LocalBrandColors.current
    Button(
        onClick = onClick, enabled = enabled, contentPadding = contentPadding,
        modifier = if (enabled) modifier.glow(brand.glow, 10.dp, alpha = if (brand.dark) 0.6f else 0.35f) else modifier,
        content = content,
    )
}

/** Animated usage bar; turns amber above 80% and red above 90% unless a color is given. */
@Composable
fun CapacityBar(fraction: Float, modifier: Modifier = Modifier, color: Color? = null, height: Dp = 10.dp) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(700, easing = FastOutSlowInEasing), label = "bar")
    val status = LocalStatusColors.current
    val brand = LocalBrandColors.current
    val brush = when {
        color != null -> SolidColor(color)
        fraction >= 0.9f -> SolidColor(status.critical)
        fraction >= 0.8f -> SolidColor(status.warning)
        else -> Brush.horizontalGradient(listOf(brand.gaugeStart, brand.gaugeEnd))
    }
    Box(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        Box(Modifier.fillMaxWidth(animated).height(height).clip(RoundedCornerShape(50)).background(brush))
    }
}

/** A segment of a [StackedBar]. */
data class BarSegment(val fraction: Float, val color: Color)

/** Horizontal stacked bar (e.g. memory: services / ZFS cache / free). */
@Composable
fun StackedBar(segments: List<BarSegment>, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val animated = segments.map { seg ->
        animateFloatAsState(seg.fraction.coerceIn(0f, 1f), tween(700, easing = FastOutSlowInEasing), label = "seg").value
    }
    Canvas(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50))) {
        drawRect(track)
        var x = 0f
        val gap = 2.dp.toPx()
        segments.forEachIndexed { i, seg ->
            val w = animated[i] * size.width
            if (w > 0.5f) {
                drawRect(seg.color, topLeft = Offset(x, 0f), size = androidx.compose.ui.geometry.Size((w - if (i < segments.lastIndex) gap else 0f).coerceAtLeast(1f), size.height))
            }
            x += w
        }
    }
}

/** Circular gauge (270° arc) with a royal-blue → cyan gradient and a soft glow. A solid [color] overrides the gradient (warning/critical). */
@Composable
fun RingGauge(fraction: Float, modifier: Modifier = Modifier, color: Color? = null, stroke: Dp = 8.dp) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(600, easing = FastOutSlowInEasing), label = "ring")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val brand = LocalBrandColors.current
    Canvas(modifier) {
        val s = stroke.toPx()
        val inset = s / 2 + 3.dp.toPx() // room for the glow
        val arcSize = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
        val tl = Offset(inset, inset)
        drawArc(track, 135f, 270f, false, style = Stroke(s, cap = StrokeCap.Round), topLeft = tl, size = arcSize)
        if (animated <= 0.001f) return@Canvas
        val brush: Brush = if (color != null) SolidColor(color) else {
            // Sweep gradient mapped onto the arc (starts at 135°, spans 270°).
            val mid = androidx.compose.ui.graphics.lerp(brand.gaugeStart, brand.gaugeEnd, 225f / 270f)
            Brush.sweepGradient(
                0f to mid, 0.125f to brand.gaugeEnd, 0.375f to brand.gaugeStart, 1f to mid,
                center = center,
            )
        }
        val glowColor = color ?: brand.glow
        val sweep = 270f * animated
        // Glow: wider, translucent passes under the arc.
        // Glow strength ramps in with the sweep so tiny values don't turn into a blob at the arc start.
        val glowScale = (sweep / 40f).coerceIn(0f, 1f)
        if (glowScale > 0f) for ((w, a) in listOf(2.4f to 0.07f, 1.7f to 0.10f)) {
            drawArc(glowColor.copy(alpha = (if (brand.dark) a * 1.4f else a) * glowScale), 135f, sweep, false,
                style = Stroke(s * w, cap = StrokeCap.Round), topLeft = tl, size = arcSize)
        }
        drawArc(brush, 135f, sweep, false, style = Stroke(s, cap = StrokeCap.Round), topLeft = tl, size = arcSize)
    }
}

/** Tiny line chart with a soft gradient fill and glow. */
@Composable
fun Sparkline(values: List<Float>, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, maxValue: Float? = null) {
    val dark = LocalBrandColors.current.dark
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val max = (maxValue ?: values.max()).coerceAtLeast(0.0001f)
        val pad = 3.dp.toPx()
        val h = size.height - pad
        val stepX = size.width / (values.size - 1)
        val points = values.mapIndexed { i, v -> Offset(i * stepX, pad + h - (v / max).coerceIn(0f, 1f) * h) }
        val line = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) {
                val p0 = points[i - 1]; val p1 = points[i]
                val cx = (p0.x + p1.x) / 2
                cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
            }
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = if (dark) 0.34f else 0.24f), Color.Transparent)))
        drawPath(line, color.copy(alpha = if (dark) 0.22f else 0.14f), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color.copy(alpha = 0.25f), 5.dp.toPx(), points.last())
        drawCircle(color, 2.5.dp.toPx(), points.last())
    }
}

/** Shimmering placeholder for skeleton loading states. */
fun Modifier.shimmer(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "shimmerX",
    )
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val highlight = MaterialTheme.colorScheme.surfaceContainerLow
    background(
        Brush.linearGradient(
            colors = listOf(base, highlight, base),
            start = Offset(x * 600f, 0f), end = Offset(x * 600f + 600f, 300f),
        )
    )
}

@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, height: Dp = 16.dp, widthFraction: Float = 1f) {
    Box(modifier.fillMaxWidth(widthFraction).height(height).clip(RoundedCornerShape(8.dp)).shimmer())
}

@Composable
fun SkeletonCard(modifier: Modifier = Modifier, height: Dp = 110.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(MaterialTheme.shapes.large).shimmer())
}

@Composable
fun SkeletonList(count: Int = 5, itemHeight: Dp = 96.dp) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(count) { SkeletonCard(height = itemHeight) }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier, loginRequired: Boolean = false) {
    if (loginRequired) {
        EmptyState(
            icon = Icons.Rounded.Lock,
            title = "Sign in required",
            message = "Sign in with your TrueNAS account to see this server.",
            modifier = modifier,
            action = { Button(onClick = onRetry) { Text("Sign in") } },
        )
    } else {
        EmptyState(
            icon = Icons.Rounded.CloudOff,
            title = "Can't reach your NAS",
            message = message,
            modifier = modifier,
            action = { FilledTonalButton(onClick = onRetry) { Text("Try again") } },
        )
    }
}

/** Scrollable error state (so pull-to-refresh still works). */
@Composable
fun ScrollableErrorState(message: String, loginRequired: Boolean = false, onRetry: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) { item { Spacer(Modifier.height(48.dp)); ErrorState(message, onRetry, loginRequired = loginRequired) } }
}

val UiState.Error.isLoginRequired: Boolean get() = cause is app.truenascompanion.data.api.TrueNasException.LoginRequired

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    destructive: Boolean = false,
    icon: ImageVector? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let { { Icon(it, null) } },
        title = { Text(title) },
        text = { Text(text, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            if (destructive) {
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text(confirmLabel, maxLines = 1) }
            } else {
                GlowButton(onClick = onConfirm) { Text(confirmLabel, maxLines = 1) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Expandable(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(visible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column { content() }
    }
}

@Composable
fun InfoBanner(text: String, modifier: Modifier = Modifier, health: Health = Health.WARNING) {
    val c = LocalStatusColors.current
    Surface(color = c.containerOf(health), shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (health == Health.CRITICAL) Icons.Rounded.Error else Icons.Rounded.Warning, null, tint = c.of(health))
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
