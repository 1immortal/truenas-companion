package app.truenascompanion.ui.dashboard

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.truenascompanion.ui.components.Tag
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.truenascompanion.data.model.MemoryBreakdown
import app.truenascompanion.ui.components.BarSegment
import app.truenascompanion.ui.components.StackedBar
import app.truenascompanion.ui.components.glow
import app.truenascompanion.ui.theme.LocalBrandColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.truenascompanion.data.model.ApiFlavor
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.WidgetType
import app.truenascompanion.ui.components.CapacityBar
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.components.RingGauge
import app.truenascompanion.ui.components.SkeletonBlock
import app.truenascompanion.ui.components.Sparkline
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.theme.LocalStatusColors
import app.truenascompanion.util.Format

fun WidgetType.icon(): ImageVector = when (this) {
    WidgetType.SYSTEM -> Icons.Rounded.Dns
    WidgetType.CPU -> Icons.Rounded.Speed
    WidgetType.MEMORY -> Icons.Rounded.Memory
    WidgetType.TEMPERATURE -> Icons.Rounded.Thermostat
    WidgetType.NETWORK -> Icons.Rounded.SettingsEthernet
    WidgetType.POOLS -> Icons.Rounded.Storage
    WidgetType.APPS -> Icons.Rounded.Apps
    WidgetType.ALERTS -> Icons.Rounded.NotificationsActive
    WidgetType.PROTECTION -> Icons.Rounded.Shield
    WidgetType.REPORTS -> Icons.Rounded.Insights
    WidgetType.RUNWAY -> Icons.Rounded.HourglassBottom
    WidgetType.ARC -> Icons.Rounded.Cached
}

fun tempHealth(c: Double?): Health = when {
    c == null -> Health.UNKNOWN
    c >= 70 -> Health.CRITICAL
    c >= 55 -> Health.WARNING
    else -> Health.HEALTHY
}

fun diskTempHealth(c: Double?): Health = when {
    c == null -> Health.UNKNOWN
    c >= 55 -> Health.CRITICAL
    c >= 45 -> Health.WARNING
    else -> Health.HEALTHY
}

@Composable
private fun WidgetHeader(type: WidgetType, full: Boolean, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(type.icon(), size = 30.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            if (full) type.title else type.shortTitle,
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics { heading() }, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        )
        trailing()
    }
}

/** Single-line text that shrinks (down to [min]) instead of wrapping or clipping. */
@Composable
fun FitText(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified, min: TextUnit = 11.sp) {
    val fallback = LocalContentColor.current
    val c = if (color != Color.Unspecified) color else style.color.takeOrElse { fallback }
    BasicText(
        text, modifier = modifier, style = style.copy(color = c), maxLines = 1, softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = min, maxFontSize = style.fontSize, stepSize = 0.5.sp),
    )
}

private fun Color.takeOrElse(block: () -> Color) = if (this != Color.Unspecified) this else block()

@Composable
private fun BigValue(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    AnimatedContent(text, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) }, label = "value", modifier = modifier) {
        FitText(it, MaterialTheme.typography.headlineMedium, color = color, min = 16.sp)
    }
}

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier, maxLines: Int = 2) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

/**
 * Pulsing "live" indicator with a soft halo.
 * Battery: the pulse is read only in the draw phase (no recomposition per frame) and the dot rests
 * between pulses, so the screen isn't asked for new frames most of the time.
 */
@Composable
fun LiveDot() {
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            pulse.snapTo(0f)
            pulse.animateTo(1f, tween(LIVE_PULSE_MS, easing = LinearEasing))
            kotlinx.coroutines.delay(LIVE_REST_MS)
        }
    }
    val color = LocalStatusColors.current.healthy
    Box(
        Modifier.size(14.dp).drawBehind {
            val p = pulse.value
            drawCircle(color.copy(alpha = (1f - p) * 0.55f), radius = size.minDimension / 2f * (0.5f + p * 0.7f))
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(7.dp).glow(color, 6.dp).clip(CircleShape).background(color))
    }
}

private const val LIVE_PULSE_MS = 1200
private const val LIVE_REST_MS = 1800L

private val WidgetMinHeight = 136.dp
private val CompactMinHeight = 112.dp

@Composable
fun DashboardWidget(
    type: WidgetType,
    full: Boolean,
    data: DashboardData,
    live: LiveStats,
    flavor: ApiFlavor?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** 1.10.0: compact density (less padding, lower minimum height). */
    compact: Boolean = false,
) {
    ElevatedSection(modifier = modifier.heightIn(min = if (compact) CompactMinHeight else WidgetMinHeight), onClick = onClick, contentPadding = if (compact) 10.dp else 14.dp) {
        when (type) {
            WidgetType.SYSTEM -> SystemWidget(data, flavor, live)
            WidgetType.CPU -> CpuWidget(full, live, data, flavor)
            WidgetType.MEMORY -> MemoryWidget(full, live, data, flavor)
            WidgetType.TEMPERATURE -> TemperatureWidget(full, live, data, flavor)
            WidgetType.NETWORK -> NetworkWidget(full, live, flavor)
            WidgetType.POOLS -> PoolsWidget(full, data)
            WidgetType.APPS -> AppsWidget(full, data)
            WidgetType.ALERTS -> AlertsWidget(full, data)
            WidgetType.PROTECTION -> ProtectionWidget(full, data)
            WidgetType.REPORTS -> ReportsWidget(full, live)
            WidgetType.RUNWAY -> RunwayWidget(full, data)
            WidgetType.ARC -> ArcWidget(full, live, data, flavor)
        }
    }
}

@Composable
private fun NoLive(flavor: ApiFlavor?) {
    Muted("Waiting for live data…", maxLines = 3)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SystemWidget(d: DashboardData, flavor: ApiFlavor?, live: LiveStats) {
    WidgetHeader(WidgetType.SYSTEM, true) { if (live.latest != null) LiveDot() }
    Spacer(Modifier.height(12.dp))
    val s = d.system
    if (s == null) {
        SkeletonBlock(height = 28.dp, widthFraction = 0.6f); Spacer(Modifier.height(8.dp)); SkeletonBlock(widthFraction = 0.8f)
        return
    }
    // 1.8.0 (UI review P1-27): the hostname is already the screen title, so this card answers "is my NAS OK?" first.
    SystemHealthSummary(d)
    Spacer(Modifier.height(10.dp))
    Text(s.version, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Muted("Up ${Format.uptime(s.uptimeSeconds)}" + (s.cpuModel?.let { " · $it" } ?: ""), maxLines = 1)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        s.cores?.let { Tag("$it threads") }
        s.physicalMemory?.let { Tag(Format.bytes(it) + " RAM") }
    }
}

/** One line per area with a tone dot: pools, alerts, apps. Missing data is left out. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SystemHealthSummary(d: DashboardData) {
    val items = DashboardSummary.items(d)
    if (items.isEmpty()) return
    val status = LocalStatusColors.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (health, text) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(status.fillOf(health), androidx.compose.foundation.shape.CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
        }
    }
}

/** Text for [SystemHealthSummary] (pure, unit-tested). */
internal object DashboardSummary {
    fun items(d: DashboardData): List<Pair<Health, String>> = buildList {
        d.pools?.takeIf { it.isNotEmpty() }?.let { pools ->
            val bad = pools.count { it.health != Health.HEALTHY }
            add(if (bad == 0) Health.HEALTHY to (if (pools.size == 1) "Pool healthy" else "${pools.size} pools healthy")
                else pools.maxOf { it.health } to (if (bad == 1) "1 pool needs attention" else "$bad pools need attention"))
        }
        d.alerts?.let { a ->
            add(if (a.active == 0) Health.HEALTHY to "No alerts" else a.worst to (if (a.active == 1) "1 alert" else "${a.active} alerts"))
        }
        d.apps?.takeIf { it.total > 0 }?.let { a ->
            add(if (a.problems > 0) Health.WARNING to "${a.problems} of ${a.total} apps need attention" else Health.HEALTHY to "${a.running} of ${a.total} apps running")
        }
    }
}

@Composable
private fun gaugeOverride(p: Double): Color? {
    val status = LocalStatusColors.current
    return when { p >= 90 -> status.criticalFill; p >= 75 -> status.warningFill; else -> null }
}

/** Sparkline scale: at least 0–20% so an idle CPU doesn't look like a flat line glued to the bottom or a wild spike. */
private fun cpuScale(values: List<Float>) = ((values.maxOrNull() ?: 0f) * 1.25f).coerceIn(20f, 100f)

@Composable
private fun CpuWidget(full: Boolean, live: LiveStats, d: DashboardData, flavor: ApiFlavor?) {
    WidgetHeader(WidgetType.CPU, full)
    Spacer(Modifier.height(10.dp))
    val s = live.latest
    val cpu = s?.cpuPercent
    if (cpu == null) { NoLive(flavor); return }
    val override = gaugeOverride(cpu)
    val lineColor = override ?: LocalBrandColors.current.accent
    val load = d.system?.loadAverage?.firstOrNull()
    Row(verticalAlignment = Alignment.CenterVertically) {
        val ring = if (full) 88.dp else 68.dp
        Box(Modifier.size(ring), contentAlignment = Alignment.Center) {
            RingGauge((cpu / 100).toFloat(), Modifier.size(ring), color = override)
            FitText(Format.cpuPercent(cpu), if (full) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 14.dp), min = 10.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (full) {
                Sparkline(live.cpu, Modifier.fillMaxWidth().height(52.dp), color = lineColor, maxValue = cpuScale(live.cpu))
                Spacer(Modifier.height(6.dp))
                Muted(listOfNotNull(
                    d.system?.loadAverage?.takeIf { it.isNotEmpty() }?.let { "Load " + it.joinToString(" · ") { v -> "%.2f".format(v) } },
                    s.cpuCores.takeIf { it.isNotEmpty() }?.let { "busiest thread ${Format.cpuPercent(it.max())}" },
                ).joinToString("  ·  "), maxLines = 2)
            } else {
                Muted("Load", maxLines = 1)
                FitText(load?.let { "%.2f".format(it) } ?: "—", MaterialTheme.typography.titleMedium)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    if (full && s.cpuCores.size > 1) {
        CoreBars(s.cpuCores, Modifier.fillMaxWidth().height(22.dp))
    } else if (!full) {
        Sparkline(live.cpu, Modifier.fillMaxWidth().height(28.dp), color = lineColor, maxValue = cpuScale(live.cpu))
    }
}

/** One thin bar per CPU thread. */
@Composable
private fun CoreBars(cores: List<Double>, modifier: Modifier) {
    val brand = LocalBrandColors.current
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        cores.forEach { c ->
            Box(Modifier.weight(1f).fillMaxHeight().clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).background(track), contentAlignment = Alignment.BottomCenter) {
                val f = (c / 100.0).toFloat().coerceIn(0.06f, 1f)
                Box(Modifier.fillMaxWidth().fillMaxHeight(f).background(Brush.verticalGradient(listOf(brand.gaugeEnd, brand.gaugeStart))))
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(6.dp))
        // The value shrinks to fit rather than truncating the label.
        FitText(value, MaterialTheme.typography.labelMedium.copy(textAlign = androidx.compose.ui.text.style.TextAlign.End), Modifier.weight(1f), min = 9.sp)
    }
}

@Composable
private fun MemoryWidget(full: Boolean, live: LiveStats, d: DashboardData, flavor: ApiFlavor?) {
    WidgetHeader(WidgetType.MEMORY, full)
    Spacer(Modifier.height(10.dp))
    val s = live.latest
    val b: MemoryBreakdown? = s?.memoryBreakdown
    if (b == null) {
        val total = d.system?.physicalMemory
        if (total != null) { BigValue(Format.bytes(total)); Muted("installed") } else NoLive(flavor)
        return
    }
    val brand = LocalBrandColors.current
    val servicesColor = gaugeOverride(b.servicesFraction * 100.0) ?: MaterialTheme.colorScheme.primary
    val arcColor = brand.chartArc
    val freeColor = MaterialTheme.colorScheme.outlineVariant
    BigValue(Format.bytes(b.services))
    Muted("used by services · ${Format.bytes(b.total)} total", maxLines = if (full) 1 else 2)
    Spacer(Modifier.height(10.dp))
    StackedBar(listOf(BarSegment(b.servicesFraction, servicesColor), BarSegment(b.arcFraction, arcColor)), height = 10.dp)
    Spacer(Modifier.height(8.dp))
    if (full) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendItem(servicesColor, "Services", Format.bytes(b.services), Modifier.weight(1f))
            LegendItem(arcColor, "ZFS cache", Format.bytes(b.arc), Modifier.weight(1f))
            LegendItem(freeColor, "Free", Format.bytes(b.free), Modifier.weight(1f))
        }
    } else {
        LegendItem(arcColor, "Cache", Format.bytes(b.arc))
        LegendItem(freeColor, "Free", Format.bytes(b.free))
    }
}

@Composable
private fun TemperatureWidget(full: Boolean, live: LiveStats, d: DashboardData, flavor: ApiFlavor?) {
    WidgetHeader(WidgetType.TEMPERATURE, full)
    Spacer(Modifier.height(10.dp))
    val status = LocalStatusColors.current
    val cpuT = live.latest?.cpuTempC
    val cpuLabel = "CPU"
    val cpuColor = if (cpuT != null) status.of(tempHealth(cpuT)) else MaterialTheme.colorScheme.onSurface
    if (full) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) { BigValue(Format.temp(cpuT), color = cpuColor); Muted(cpuLabel, maxLines = 1) }
            d.hottestDisk?.let { (name, t) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    FitText(Format.temp(t), MaterialTheme.typography.titleLarge, color = status.of(diskTempHealth(t)))
                    Muted("hottest disk · $name", maxLines = 1)
                }
            }
        }
    } else {
        BigValue(Format.temp(cpuT), color = cpuColor)
        Muted(cpuLabel, maxLines = 1)
        d.hottestDisk?.let { (name, t) ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Format.temp(t), style = MaterialTheme.typography.titleSmall, color = status.of(diskTempHealth(t)), maxLines = 1, softWrap = false)
                Spacer(Modifier.width(6.dp))
                Muted("disk $name", maxLines = 1)
            }
        }
    }
}

@Composable
private fun NetworkWidget(full: Boolean, live: LiveStats, flavor: ApiFlavor?) {
    WidgetHeader(WidgetType.NETWORK, full)
    Spacer(Modifier.height(10.dp))
    val s = live.latest
    if (s?.netRxBytesPerSec == null) { NoLive(flavor); return }
    val brand = LocalBrandColors.current
    val rxColor = brand.chartRx
    val txColor = brand.chartTx
    if (full) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RateLabel(Icons.Rounded.ArrowDownward, Format.rate(s.netRxBytesPerSec), rxColor, Modifier.weight(1f))
            RateLabel(Icons.Rounded.ArrowUpward, Format.rate(s.netTxBytesPerSec), txColor, Modifier.weight(1f))
        }
    } else {
        RateLabel(Icons.Rounded.ArrowDownward, Format.rate(s.netRxBytesPerSec), rxColor, Modifier.fillMaxWidth())
        Spacer(Modifier.height(2.dp))
        RateLabel(Icons.Rounded.ArrowUpward, Format.rate(s.netTxBytesPerSec), txColor, Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(8.dp))
    val max = ((live.rx + live.tx).maxOrNull() ?: 1f).coerceAtLeast(1f) * 1.15f
    Box(Modifier.fillMaxWidth().height(if (full) 52.dp else 28.dp)) {
        Sparkline(live.rx, Modifier.matchParentSize(), color = rxColor, maxValue = max)
        Sparkline(live.tx, Modifier.matchParentSize(), color = txColor, maxValue = max)
    }
}

@Composable
private fun RateLabel(icon: ImageVector, text: String, color: Color, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        FitText(text, MaterialTheme.typography.titleMedium, min = 10.sp)
    }
}

@Composable
private fun PoolsWidget(full: Boolean, d: DashboardData) {
    WidgetHeader(WidgetType.POOLS, full)
    Spacer(Modifier.height(12.dp))
    val pools = d.pools
    when {
        pools == null && d.loading -> { SkeletonBlock(); Spacer(Modifier.height(8.dp)); SkeletonBlock(widthFraction = 0.7f) }
        pools == null -> Muted("Unavailable")
        pools.isEmpty() -> Muted("No pools yet")
        !full -> {
            val worst = pools.maxOf { it.health.ordinal }.let { Health.entries[it] }
            BigValue("${pools.size}")
            StatusChip(worst, if (worst == Health.HEALTHY) "Healthy" else "Attention")
        }
        else -> Column {
            // 1.8.0 (UI review P1-28): each pool is one tight block (name · % · status, bar, caption); dividers between.
            pools.take(4).forEachIndexed { i, p ->
                if (i > 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.semantics(mergeDescendants = true) {}) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Format.percent(p.usedFraction * 100.0), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        Spacer(Modifier.width(8.dp))
                        StatusChip(p.health, p.status.lowercase().replaceFirstChar { it.uppercase() })
                    }
                    Spacer(Modifier.height(4.dp))
                    CapacityBar(p.usedFraction)
                    Spacer(Modifier.height(4.dp))
                    Muted("${Format.bytes(p.allocated)} of ${Format.bytes(p.size)}", maxLines = 1)
                }
            }
            if (pools.size > 4) Muted("+${pools.size - 4} more")
        }
    }
}

@Composable
private fun AppsWidget(full: Boolean, d: DashboardData) {
    WidgetHeader(WidgetType.APPS, full)
    Spacer(Modifier.height(10.dp))
    val a = d.apps
    if (a == null) { if (d.loading) SkeletonBlock(height = 28.dp, widthFraction = 0.5f) else Muted("Unavailable"); return }
    Row(verticalAlignment = Alignment.Bottom) {
        BigValue("${a.running}")
        Text(" / ${a.total}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp), maxLines = 1)
    }
    Muted("running", maxLines = 1)
    Spacer(Modifier.height(6.dp))
    when {
        a.problems > 0 -> StatusChip(Health.CRITICAL, "${a.problems} crashed")
        a.updates > 0 -> StatusChip(Health.WARNING, "${a.updates} update${if (a.updates > 1) "s" else ""}")
        a.total > 0 -> StatusChip(Health.HEALTHY, "Up to date")
    }
}

@Composable
private fun AlertsWidget(full: Boolean, d: DashboardData) {
    WidgetHeader(WidgetType.ALERTS, full)
    Spacer(Modifier.height(10.dp))
    val a = d.alerts
    if (a == null) { if (d.loading) SkeletonBlock(height = 28.dp, widthFraction = 0.5f) else Muted("Unavailable"); return }
    if (a.active == 0) {
        BigValue("All clear")
        StatusChip(Health.HEALTHY, "No alerts")
        return
    }
    BigValue("${a.active}")
    StatusChip(a.worst, when (a.worst) { Health.CRITICAL -> "Critical"; Health.WARNING -> "Warnings"; else -> "Info" })
    if (full) a.latest?.let { Spacer(Modifier.height(8.dp)); Muted(it.text) }
}

@Composable
private fun ProtectionWidget(full: Boolean, d: DashboardData) {
    WidgetHeader(WidgetType.PROTECTION, full)
    Spacer(Modifier.height(12.dp))
    val p = d.protection
    when {
        p == null && d.loading -> { SkeletonBlock(); Spacer(Modifier.height(8.dp)); SkeletonBlock(widthFraction = 0.7f) }
        p == null -> Muted("Unavailable")
        full -> app.truenascompanion.ui.protection.ProtectionRows(p, compact = true)
        else -> {
            val worst = p.worst
            BigValue(if (p.problems == 0) "OK" else "${p.problems}")
            StatusChip(worst.health, when (worst) {
                app.truenascompanion.data.protection.ProtectionStatus.FAILED -> "Failed"
                app.truenascompanion.data.protection.ProtectionStatus.OVERDUE -> "Overdue"
                app.truenascompanion.data.protection.ProtectionStatus.NONE -> "Not set up"
                app.truenascompanion.data.protection.ProtectionStatus.RUNNING -> "Running"
                app.truenascompanion.data.protection.ProtectionStatus.OK -> "All good"
            })
        }
    }
}

@Composable
private fun ReportsWidget(full: Boolean, live: LiveStats) {
    WidgetHeader(WidgetType.REPORTS, full)
    Spacer(Modifier.height(10.dp))
    if (live.cpu.size >= 2) {
        Sparkline(live.cpu, Modifier.fillMaxWidth().height(if (full) 52.dp else 40.dp), color = LocalBrandColors.current.accent, maxValue = cpuScale(live.cpu))
        Spacer(Modifier.height(8.dp))
    } else {
        BigValue("1h – 1m")
    }
    Muted(if (full) "CPU, memory, network, disks and temperatures over time" else "History & charts")
}


/** 1.10.0: "About N months until full" per pool, with a sparkline of the phone's daily samples. */
@Composable
private fun RunwayWidget(full: Boolean, d: DashboardData) {
    WidgetHeader(WidgetType.RUNWAY, full)
    Spacer(Modifier.height(10.dp))
    val list = d.runway
    when {
        list == null && d.loading -> { SkeletonBlock(); Spacer(Modifier.height(8.dp)); SkeletonBlock(widthFraction = 0.7f) }
        list == null -> Muted("Unavailable")
        list.isEmpty() -> Muted("No pools yet")
        !full -> {
            val soonest = list.minByOrNull { (it.forecast as? app.truenascompanion.data.runway.RunwayForecast.Full)?.daysLeft ?: Long.MAX_VALUE }!!
            Text(soonest.pool, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Muted(app.truenascompanion.data.runway.Runway.text(soonest.forecast), maxLines = 3)
        }
        else -> Column {
            list.take(4).forEachIndexed { i, r ->
                if (i > 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
                RunwayRow(r)
            }
            Spacer(Modifier.height(8.dp))
            Muted("Estimated on this phone from one sample a day (TrueNAS doesn't keep pool usage history).", maxLines = 3)
        }
    }
}

@Composable
fun RunwayRow(r: PoolRunway) {
    val f = r.forecast
    val tone = when (f) {
        is app.truenascompanion.data.runway.RunwayForecast.Full -> when {
            f.daysLeft < 30 -> Health.CRITICAL
            f.daysLeft < 180 -> Health.WARNING
            else -> Health.HEALTHY
        }
        app.truenascompanion.data.runway.RunwayForecast.AlreadyFull -> Health.CRITICAL
        else -> Health.UNKNOWN
    }
    val text = app.truenascompanion.data.runway.Runway.text(f)
    Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(r.pool, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (tone == Health.UNKNOWN) MaterialTheme.colorScheme.onSurfaceVariant else LocalStatusColors.current.of(tone))
            Muted("${Format.percent(r.usedFraction * 100.0)} used now", maxLines = 1)
        }
        if (r.usedFractions.size >= 2) {
            Spacer(Modifier.width(12.dp))
            Sparkline(r.usedFractions, Modifier.width(96.dp).height(36.dp).clearAndSetSemantics {}, color = LocalBrandColors.current.accent, maxValue = 1f)
        }
    }
}

/** 1.10.0: ARC size (and share of RAM) plus the demand hit ratio over the last minute, from `reporting.realtime`. */
@Composable
private fun ArcWidget(full: Boolean, live: LiveStats, d: DashboardData, flavor: ApiFlavor?) {
    WidgetHeader(WidgetType.ARC, full)
    Spacer(Modifier.height(10.dp))
    val s = live.latest ?: return NoLive(flavor)
    val arc = s.arcSize
    if (arc == null) { Muted("No ARC data from this NAS"); return }
    BigValue(Format.bytes(arc))
    val total = s.memoryTotal ?: d.system?.physicalMemory
    total?.takeIf { it > 0 }?.let { Muted("${Format.percent(arc * 100.0 / it)} of RAM", maxLines = 1) }
    Spacer(Modifier.height(6.dp))
    val hit = live.arcHit.takeIf { it.isNotEmpty() }?.average()
    if (hit != null) {
        StatusChip(if (hit >= 90) Health.HEALTHY else if (hit >= 70) Health.WARNING else Health.INFO, "Hit ratio ${Format.percent(hit)}")
        if (full && live.arcHit.size >= 2) {
            Spacer(Modifier.height(6.dp))
            Sparkline(live.arcHit, Modifier.fillMaxWidth().height(36.dp), color = LocalBrandColors.current.chartArc, maxValue = 100f)
        }
    } else Muted("Hit ratio: no reads right now", maxLines = 2)
}
