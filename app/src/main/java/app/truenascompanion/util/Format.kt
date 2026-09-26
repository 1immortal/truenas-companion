package app.truenascompanion.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

object Format {
    private val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")

    fun bytes(value: Long?): String {
        if (value == null) return "—"
        if (value < 1024) return "$value B"
        val exp = (ln(value.toDouble()) / ln(1024.0)).toInt().coerceAtMost(units.size - 1)
        return String.format(Locale.US, "%.1f %s", value / 1024.0.pow(exp), units[exp])
    }

    /** Compact transfer rate in bytes per second with auto units, e.g. "850 B/s", "7.2 KB/s", "118 MB/s". */
    fun rate(bytesPerSec: Double?): String {
        if (bytesPerSec == null) return "—"
        val u = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
        var v = bytesPerSec.coerceAtLeast(0.0)
        var i = 0
        while (v >= 999.5 && i < u.size - 1) { v /= 1000; i++ }
        return if (i == 0 || v >= 9.95) String.format(Locale.US, "%.0f %s", v, u[i]) else String.format(Locale.US, "%.1f %s", v, u[i])
    }

    /** CPU percent: "<1%" near idle, one decimal below 10%. */
    fun cpuPercent(p: Double?): String = when {
        p == null -> "—"
        p < 1 -> "<1%"
        p < 9.95 && String.format(Locale.US, "%.1f", p).let { !it.endsWith(".0") } -> String.format(Locale.US, "%.1f%%", p)
        else -> String.format(Locale.US, "%.0f%%", p)
    }

    fun percent(p: Double?): String = if (p == null) "—" else String.format(Locale.US, "%.0f%%", p)

    fun temp(c: Double?): String = if (c == null) "—" else String.format(Locale.US, "%.0f°C", c)

    fun uptime(seconds: Long?): String {
        if (seconds == null) return "—"
        val d = seconds / 86400
        val h = (seconds % 86400) / 3600
        val m = (seconds % 3600) / 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m"
        }
    }

    fun relativeTime(millis: Long?, now: Long = System.currentTimeMillis()): String {
        if (millis == null) return ""
        val diff = abs(now - millis) / 1000
        return when {
            diff < 60 -> "just now"
            diff < 3600 -> "${diff / 60} min ago"
            diff < 86400 -> "${diff / 3600} h ago"
            else -> "${diff / 86400} d ago"
        }
    }

    /** Strip the small amount of HTML TrueNAS puts in alert texts. */
    fun stripHtml(s: String): String = s
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .trim()
}

object UrlUtils {
    /**
     * Turns user input like `nas.local`, `192.168.1.5:444/ui/` or `https://nas/` into `scheme://host[:port]`.
     * Defaults to https when no scheme is given. Returns null when the input can't be a host.
     */
    fun normalize(input: String): String? {
        var s = input.trim()
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        val scheme = s.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = s.substringAfter("://")
        val hostPort = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (hostPort.isBlank() || hostPort.contains(' ')) return null
        return "$scheme://$hostPort"
    }

    /** Host part of a URL (no scheme, port or path), e.g. `https://nas.example.org:8443/ui` -> `nas.example.org`. */
    fun hostOf(url: String): String? {
        val hostPort = (normalize(url) ?: return null).substringAfter("://")
        return if (hostPort.startsWith("[")) hostPort.substringAfter('[').substringBefore(']')
        else hostPort.substringBefore(':').ifBlank { null }
    }

    /** The host when it is an IPv4 literal (the tunnel routes exactly this address), else null. */
    fun ipLiteralHost(url: String): String? = hostOf(url)?.takeIf { isIpv4(it) }

    fun isIpv4(s: String): Boolean {
        val parts = s.split('.')
        return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() in 0..255 }
    }

    fun webSocketUrl(base: String): String {
        val wsScheme = if (base.startsWith("https://", true)) "wss" else "ws"
        return "$wsScheme://${base.substringAfter("://")}/api/current"
    }
}
