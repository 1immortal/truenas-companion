package app.truenascompanion.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.truenascompanion.ui.theme.LocalBrandColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One line on a [TimeSeriesChart]. NaN values are gaps. */
@Immutable
data class ChartLine(val label: String, val values: FloatArray, val color: Color)

/**
 * Visible window of a chart as fractions of the full time range (0..1). Pure state so zoom/pan math is unit-testable.
 */
@Immutable
data class ChartViewport(val start: Float = 0f, val end: Float = 1f) {
    val span: Float get() = end - start
    val zoomed: Boolean get() = span < 0.999f

    /** Pinch: scales the window by 1/[zoom] around [focus] (0..1 of the visible width), clamped to [minSpan]..1. */
    fun zoom(zoom: Float, focus: Float, minSpan: Float = 0.02f): ChartViewport {
        if (zoom <= 0f) return this
        val newSpan = (span / zoom).coerceIn(minSpan, 1f)
        val anchor = start + focus.coerceIn(0f, 1f) * span
        var s = anchor - focus.coerceIn(0f, 1f) * newSpan
        s = s.coerceIn(0f, 1f - newSpan)
        return ChartViewport(s, s + newSpan)
    }

    /** Pan by [dxFraction] of the visible width (positive = drag right = earlier data). */
    fun pan(dxFraction: Float): ChartViewport {
        val shift = -dxFraction * span
        val s = (start + shift).coerceIn(0f, 1f - span)
        return ChartViewport(s, s + span)
    }
}

/**
 * Smooth, themed time-series chart drawn with Compose Canvas (no chart library):
 * - two-finger pinch zooms around the fingers and two-finger drag pans; double tap resets;
 * - touching (or sliding a finger sideways) shows a tooltip with the time and every series' value;
 * - vertical drags are left to the surrounding list so the screen still scrolls.
 */
@Composable
fun TimeSeriesChart(
    times: LongArray,
    lines: List<ChartLine>,
    formatValue: (Float) -> String,
    formatTime: (Long) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    minY: Float? = 0f,
    maxY: Float? = null,
    viewport: ChartViewport = ChartViewport(),
    onViewportChange: (ChartViewport) -> Unit = {},
    description: String = "Chart",
) {
    val brand = LocalBrandColors.current
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = scheme.onSurfaceVariant, fontSize = 10.sp)
    val tipTitleStyle = TextStyle(color = scheme.inverseOnSurface, fontSize = 11.sp)
    var touchX by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(times.size, lines.size, times.firstOrNull(), times.lastOrNull()) {
        reveal.snapTo(0f); reveal.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }
    val currentViewport by androidx.compose.runtime.rememberUpdatedState(viewport)
    val onChange by androidx.compose.runtime.rememberUpdatedState(onViewportChange)

    val gestures = Modifier.pointerInput(Unit) {
        var lastTapAt = 0L
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var scrubbing = false
            var multi = false
            var decided = false
            var prevCentroid: Offset? = null
            var prevDist = 0f
            val startPos = down.position
            touchX = down.position.x
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2) {
                    multi = true; touchX = null
                    val c = Offset(pressed.map { it.position.x }.average().toFloat(), pressed.map { it.position.y }.average().toFloat())
                    val d = (pressed[0].position - pressed[1].position).getDistance()
                    val pc = prevCentroid
                    if (pc != null && prevDist > 0f) {
                        var vp = currentViewport
                        vp = vp.zoom(d / prevDist, (c.x / size.width.toFloat()))
                        vp = vp.pan((c.x - pc.x) / size.width.toFloat())
                        onChange(vp)
                    }
                    prevCentroid = c; prevDist = d
                    event.changes.forEach { it.consume() }
                } else if (!multi) {
                    val p = pressed[0]
                    val dx = abs(p.position.x - startPos.x); val dy = abs(p.position.y - startPos.y)
                    if (!decided && (dx > viewConfiguration.touchSlop || dy > viewConfiguration.touchSlop)) {
                        decided = true
                        scrubbing = dx >= dy
                        if (!scrubbing) { touchX = null }
                    }
                    if (scrubbing || !decided) {
                        touchX = p.position.x
                        if (scrubbing) p.consume()
                    } else break // vertical: let the list scroll
                }
            }
            if (!multi && !scrubbing && !decided) {
                val now = System.currentTimeMillis()
                if (now - lastTapAt < 300) { onChange(ChartViewport()); touchX = null }
                lastTapAt = now
                // a tap keeps the tooltip visible until the next touch
            } else if (scrubbing || multi) touchX = null
        }
    }

    Canvas(
        modifier.fillMaxWidth().height(height).then(gestures).semantics { contentDescription = description },
    ) {
        widthPx = size.width
        val n = times.size
        val leftPad = 40.dp.toPx(); val bottomPad = 18.dp.toPx(); val topPad = 6.dp.toPx()
        val plotW = size.width - leftPad; val plotH = size.height - bottomPad - topPad
        if (n < 2 || lines.isEmpty()) {
            drawText(measurer, "No data for this period", Offset(leftPad, size.height / 2 - 8.dp.toPx()), labelStyle)
            return@Canvas
        }
        val i0 = (viewport.start * (n - 1)).toInt().coerceIn(0, n - 2)
        val i1 = (kotlin.math.ceil(viewport.end * (n - 1).toDouble()).toInt()).coerceIn(i0 + 1, n - 1)
        // Relative Long math: epoch seconds as Float lose precision (~128 s steps at today's timestamps).
        val t0 = times[i0]; val span = (times[i1] - t0).coerceAtLeast(1L).toFloat()
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        lines.forEach { l -> for (i in i0..i1) { val v = l.values.getOrElse(i) { Float.NaN }; if (!v.isNaN()) { lo = min(lo, v); hi = max(hi, v) } } }
        if (lo == Float.MAX_VALUE) { lo = 0f; hi = 1f }
        val yMin = minY ?: (lo - (hi - lo) * 0.1f)
        var yMax = maxY ?: niceCeil(hi * 1.1f)
        if (yMax <= yMin) yMax = yMin + 1f
        fun x(i: Int) = leftPad + (times[i] - t0).toFloat() / span * plotW
        fun y(v: Float) = topPad + plotH - (v - yMin) / (yMax - yMin) * plotH

        // grid + y labels
        val gridColor = scheme.outlineVariant.copy(alpha = if (brand.dark) 0.45f else 0.7f)
        for (k in 0..3) {
            val v = yMin + (yMax - yMin) * k / 3f
            val yy = y(v)
            drawLine(gridColor, Offset(leftPad, yy), Offset(size.width, yy), 1f, pathEffect = if (k == 0) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val tl = measurer.measure(formatValue(v), labelStyle)
            drawText(tl, topLeft = Offset(leftPad - tl.size.width - 4.dp.toPx(), yy - tl.size.height / 2f))
        }
        // x labels: start, middle, end of the visible window
        listOf(i0, (i0 + i1) / 2, i1).forEachIndexed { k, i ->
            val tl = measurer.measure(formatTime(times[i]), labelStyle)
            val xx = when (k) { 0 -> leftPad; 1 -> x(i) - tl.size.width / 2f; else -> size.width - tl.size.width }
            drawText(tl, topLeft = Offset(xx, size.height - tl.size.height))
        }

        clipRect(leftPad, 0f, leftPad + plotW * reveal.value, size.height) {
            lines.forEachIndexed { li, l ->
                val path = smoothPath(i0, i1, l.values, ::x, ::y)
                if (li == 0 && lines.size <= 2) {
                    val fill = Path().apply { addPath(path); lineTo(x(i1), y(yMin)); lineTo(x(i0), y(yMin)); close() }
                    drawPath(fill, Brush.verticalGradient(listOf(l.color.copy(alpha = if (brand.dark) 0.30f else 0.20f), Color.Transparent), startY = topPad, endY = topPad + plotH))
                }
                drawPath(path, l.color.copy(alpha = if (brand.dark) 0.20f else 0.12f), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, l.color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }

        touchX?.let { tx ->
            val xx = tx.coerceIn(leftPad, size.width)
            val frac = (xx - leftPad) / plotW
            val target = frac * span
            var idx = i0
            for (i in i0..i1) if (abs((times[i] - t0) - target) < abs((times[idx] - t0) - target)) idx = i
            val px = x(idx)
            drawLine(scheme.onSurface.copy(alpha = 0.35f), Offset(px, topPad), Offset(px, topPad + plotH), 1.5f)
            val rows = lines.mapNotNull { l -> l.values.getOrNull(idx)?.takeUnless { it.isNaN() }?.let { l to it } }
            rows.forEach { (l, v) ->
                drawCircle(l.color.copy(alpha = 0.3f), 6.dp.toPx(), Offset(px, y(v)))
                drawCircle(l.color, 3.dp.toPx(), Offset(px, y(v)))
            }
            drawTooltip(measurer, formatTime(times[idx]), rows.map { (l, v) -> Triple(l.color, l.label, formatValue(v)) }, px, topPad, scheme.inverseSurface, tipTitleStyle)
        }
    }
}

private fun niceCeil(v: Float): Float {
    if (v <= 0f) return 1f
    val exp = kotlin.math.floor(kotlin.math.log10(v.toDouble())).toFloat()
    val base = Math.pow(10.0, exp.toDouble()).toFloat()
    val m = v / base
    val nice = when { m <= 1f -> 1f; m <= 2f -> 2f; m <= 2.5f -> 2.5f; m <= 5f -> 5f; else -> 10f }
    return nice * base
}

/** Cubic segments with horizontal tangents (no overshoot past neighbouring points); NaN breaks the line. */
private fun smoothPath(i0: Int, i1: Int, values: FloatArray, x: (Int) -> Float, y: (Float) -> Float): Path {
    val p = Path()
    var prev: Offset? = null
    for (i in i0..i1) {
        val v = values.getOrElse(i) { Float.NaN }
        if (v.isNaN()) { prev = null; continue }
        val pt = Offset(x(i), y(v))
        val pv = prev
        if (pv == null) p.moveTo(pt.x, pt.y)
        else { val cx = (pv.x + pt.x) / 2; p.cubicTo(cx, pv.y, cx, pt.y, pt.x, pt.y) }
        prev = pt
    }
    return p
}

private fun DrawScope.drawTooltip(
    measurer: androidx.compose.ui.text.TextMeasurer,
    title: String,
    rows: List<Triple<Color, String, String>>,
    anchorX: Float,
    top: Float,
    bg: Color,
    style: TextStyle,
) {
    val pad = 8.dp.toPx(); val dot = 7.dp.toPx(); val gap = 4.dp.toPx()
    val titleL = measurer.measure(title, style)
    val rowLs = rows.map { (_, label, value) -> measurer.measure("$label  $value", style) }
    val w = max(titleL.size.width.toFloat(), (rowLs.maxOfOrNull { it.size.width } ?: 0) + dot + gap) + pad * 2
    val h = titleL.size.height + rowLs.sumOf { it.size.height } + pad * 2 + gap
    var left = anchorX + 10.dp.toPx()
    if (left + w > size.width) left = anchorX - w - 10.dp.toPx()
    left = left.coerceAtLeast(0f)
    drawRoundRect(bg.copy(alpha = 0.92f), Offset(left, top), Size(w, h), CornerRadius(10.dp.toPx()))
    drawText(titleL, topLeft = Offset(left + pad, top + pad))
    var yy = top + pad + titleL.size.height + gap
    rows.forEachIndexed { i, (color, _, _) ->
        val l = rowLs[i]
        drawCircle(color, dot / 2, Offset(left + pad + dot / 2, yy + l.size.height / 2f))
        drawText(l, topLeft = Offset(left + pad + dot + gap, yy))
        yy += l.size.height
    }
}
