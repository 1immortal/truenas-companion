package app.truenascompanion.ui.dashboard

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
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.MaterialTheme
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
private fun WidgetHeader(type: WidgetType, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(type.icon(), size = 32.dp)
        Spacer(Modifier.width(10.dp))
        Text(type.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

@Composable
private fun BigValue(text: String, modifier: Modifier = Modifier) {
    AnimatedContent(text, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) }, label = "value", modifier = modifier) {
        Text(it, style = MaterialTheme.typography.headlineMedium, maxLines = 1)
    }
}

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier, maxLines = 2, overflow = TextOverflow.Ellipsis)

@Composable
private fun LiveDot() {
    val t = rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "liveA")
    Box(Modifier.size(8.dp).alpha(a).clip(CircleShape).background(LocalStatusColors.current.healthy))
}

private val WidgetMinHeight = 132.dp

@Composable
fun DashboardWidget(
    type: WidgetType,
    full: Boolean,
    data: DashboardData,
    live: LiveStats,
    flavor: ApiFlavor?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    ElevatedSection(modifier = modifier.heightIn(min = WidgetMinHeight), onClick = onClick, contentPadding = 16.dp) {
        when (type) {
            WidgetType.SYSTEM -> SystemWidget(data, flavor, live)
            WidgetType.CPU -> CpuWidget(full, live, data, flavor)
            WidgetType.MEMORY -> MemoryWidget(full, live, data, flavor)
            WidgetType.TEMPERATURE -> TemperatureWidget(live, data, flavor)
            WidgetType.NETWORK -> NetworkWidget(full, live, flavor)
            WidgetType.POOLS -> PoolsWidget(full, data)
            WidgetType.APPS -> AppsWidget(data)
            WidgetType.ALERTS -> AlertsWidget(full, data)
        }
    }
}

@Composable
private fun NoLive(flavor: ApiFlavor?) {
    Muted(if (flavor == ApiFlavor.REST) "Live stats need TrueNAS 25.04+ (WebSocket API)" else "Waiting for live data…")
}

@Composable
private fun SystemWidget(d: DashboardData, flavor: ApiFlavor?, live: LiveStats) {
    WidgetHeader(WidgetType.SYSTEM) { if (live.latest != null) LiveDot() }
    Spacer(Modifier.height(12.dp))
    val s = d.system
    if (s == null) {
        SkeletonBlock(height = 28.dp, widthFraction = 0.6f); Spacer(Modifier.height(8.dp)); SkeletonBlock(widthFraction = 0.8f)
        return
    }
    Text(s.hostname, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Muted(s.version)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusChip(Health.HEALTHY, "Up ${Format.uptime(s.uptimeSeconds)}")
        s.cores?.let { StatusChip(Health.UNKNOWN, "$it cores", showIcon = false) }
        if (flavor == ApiFlavor.REST) StatusChip(Health.WARNING, "Legacy API", showIcon = false)
    }
    s.cpuModel?.let { Spacer(Modifier.height(8.dp)); Muted(it) }
}

@Composable
private fun CpuWidget(full: Boolean, live: LiveStats, d: DashboardData, flavor: ApiFlavor?) {
    WidgetHeader(WidgetType.CPU)
    Spacer(Modifier.height(10.dp))
    val cpu = live.latest?.cpuPercent
    if (cpu == null) { NoLive(flavor); return }
    val status = LocalStatusColors.current
    val color = when { cpu >= 90 -> status.critical; cpu >= 70 -> status.warning; else -> MaterialTheme.colorScheme.primary }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(if (full) 84.dp else 64.dp), contentAlignment = Alignment.Center) {
            RingGauge((cpu / 100).toFloat(), Modifier.size(if (full) 84.dp else 64.dp), color = color)
            Text(Format.percent(cpu), style = if (full) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
        }
        if (full) {
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Sparkline(live.cpu, Modifier.fillMaxWidth().height(56.dp), color = color, maxValue = 100f)
                d.system?.loadAverage?.takeIf { it.isNotEmpty() }?.let {
                    Spacer(Modifier.height(6.dp)); Muted("Load " + it.joinToString(" · ") { v -> "%.2f".format(v) })
                }
            }
        }
    }
    if (!full) {
        Spacer(Modifier.height(8.dp))
        Sparkline(live.cpu, Modifier.fillMaxWidth().height(28.dp), color = color, maxValue = 100f)
    }
}

@Composable
private fun MemoryWidget(full: Boolean, live: LiveStats, d: DashboardData, flavor: ApiFlavor?) {
    WidgetHeader(WidgetType.MEMORY)
    Spacer(Modifier.height(10.dp))
    val s = live.latest
    val total = s?.memoryTotal ?: d.system?.physicalMemory
    val used = s?.memoryUsed
    if (used == null || total == null || total == 0L) {
        if (total != null) { BigValue(Format.bytes(total)); Muted("installed") } else NoLive(flavor)
        return
    }
    val frac = used.toFloat() / total
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(if (full) 84.dp else 64.dp), contentAlignment = Alignment.Center) {
            RingGauge(frac, Modifier.size(if (full) 84.dp else 64.dp), color = MaterialTheme.colorScheme.tertiary)
            Text(Format.percent(frac * 100.0), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(Format.bytes(used), style = MaterialTheme.typography.titleMedium)
            Muted("of ${Format.bytes(total)}")
            if (full) s.arcSize?.let { Spacer(Modifier.height(4.dp)); Muted("ZFS cache (ARC) ${Format.bytes(it)}") }
        }
    }
}

@Composable
private fun TemperatureWidget(live: LiveStats, d: DashboardData, flavor: ApiFlavor?) {
    WidgetHeader(WidgetType.TEMPERATURE)
    Spacer(Modifier.height(10.dp))
    val status = LocalStatusColors.current
    val cpuT = live.latest?.cpuTempC
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(Format.temp(cpuT), style = MaterialTheme.typography.headlineMedium, color = if (cpuT != null) status.of(tempHealth(cpuT)) else MaterialTheme.colorScheme.onSurface)
            Muted(if (cpuT == null && live.latest == null && flavor == ApiFlavor.REST) "CPU (needs live API)" else "CPU")
        }
        d.hottestDisk?.let { (name, t) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(Format.temp(t), style = MaterialTheme.typography.titleLarge, color = status.of(diskTempHealth(t)))
                Muted("hottest disk · $name")
            }
        }
    }
}

@Composable
private fun NetworkWidget(full: Boolean, live: LiveStats, flavor: ApiFlavor?) {
    WidgetHeader(WidgetType.NETWORK)
    Spacer(Modifier.height(10.dp))
    val s = live.latest
    if (s?.netRxBytesPerSec == null) { NoLive(flavor); return }
    val rxColor = MaterialTheme.colorScheme.primary
    val txColor = MaterialTheme.colorScheme.tertiary
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RateLabel(Icons.Rounded.ArrowDownward, Format.rate(s.netRxBytesPerSec), rxColor, Modifier.weight(1f))
        RateLabel(Icons.Rounded.ArrowUpward, Format.rate(s.netTxBytesPerSec), txColor, Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    val max = ((live.rx + live.tx).maxOrNull() ?: 1f).coerceAtLeast(1f)
    Box(Modifier.fillMaxWidth().height(if (full) 56.dp else 32.dp)) {
        Sparkline(live.rx, Modifier.matchParentSize(), color = rxColor, maxValue = max)
        Sparkline(live.tx, Modifier.matchParentSize(), color = txColor, maxValue = max)
    }
}

@Composable
private fun RateLabel(icon: ImageVector, text: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1)
    }
}

@Composable
private fun PoolsWidget(full: Boolean, d: DashboardData) {
    WidgetHeader(WidgetType.POOLS)
    Spacer(Modifier.height(12.dp))
    val pools = d.pools
    when {
        pools == null && d.loading -> { SkeletonBlock(); Spacer(Modifier.height(8.dp)); SkeletonBlock(widthFraction = 0.7f) }
        pools == null -> Muted("Unavailable")
        pools.isEmpty() -> Muted("No pools yet")
        !full -> {
            val worst = pools.maxOf { it.health.ordinal }.let { Health.entries[it] }
            BigValue("${pools.size}")
            StatusChip(worst, if (worst == Health.HEALTHY) "All healthy" else "Needs attention")
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            pools.take(4).forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    StatusChip(p.health, p.status.lowercase().replaceFirstChar { it.uppercase() })
                }
                Spacer(Modifier.height(6.dp))
                CapacityBar(p.usedFraction)
                Spacer(Modifier.height(4.dp))
                Muted("${Format.bytes(p.allocated)} used of ${Format.bytes(p.size)}")
            }
            if (pools.size > 4) Muted("+${pools.size - 4} more")
        }
    }
}

@Composable
private fun AppsWidget(d: DashboardData) {
    WidgetHeader(WidgetType.APPS)
    Spacer(Modifier.height(10.dp))
    val a = d.apps
    if (a == null) { if (d.loading) SkeletonBlock(height = 28.dp, widthFraction = 0.5f) else Muted("Unavailable"); return }
    Row(verticalAlignment = Alignment.Bottom) {
        BigValue("${a.running}")
        Text(" / ${a.total} running", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
    }
    Spacer(Modifier.height(6.dp))
    when {
        a.problems > 0 -> StatusChip(Health.CRITICAL, "${a.problems} crashed")
        a.updates > 0 -> StatusChip(Health.WARNING, "${a.updates} update${if (a.updates > 1) "s" else ""}")
        a.total > 0 -> StatusChip(Health.HEALTHY, "Up to date")
    }
}

@Composable
private fun AlertsWidget(full: Boolean, d: DashboardData) {
    WidgetHeader(WidgetType.ALERTS)
    Spacer(Modifier.height(10.dp))
    val a = d.alerts
    if (a == null) { if (d.loading) SkeletonBlock(height = 28.dp, widthFraction = 0.5f) else Muted("Unavailable"); return }
    if (a.active == 0) {
        BigValue("All clear")
        StatusChip(Health.HEALTHY, "No active alerts")
        return
    }
    BigValue("${a.active}")
    StatusChip(a.worst, when (a.worst) { Health.CRITICAL -> "Critical"; Health.WARNING -> "Warnings"; else -> "Info" })
    if (full) a.latest?.let { Spacer(Modifier.height(8.dp)); Muted(it.text) }
}
