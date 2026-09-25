package app.truenascompanion.data.model

import kotlinx.serialization.Serializable

/** A saved TrueNAS server. The API key is stored separately, encrypted with an Android Keystore key. */
@Serializable
data class ServerConfig(
    val id: String,
    val name: String,
    /** Normalized base URL, e.g. `https://nas.local` or `http://192.168.1.10:8080`. */
    val url: String,
    /**
     * Account name. Required for password sign-in; for API keys only needed on TrueNAS 26+/27 where
     * `auth.login_ex` (API_KEY_PLAIN) requires the key owner's username.
     */
    val username: String = "",
    /** SHA-256 fingerprint (hex, uppercase, colon separated) of a self-signed certificate the user chose to trust. */
    val pinnedCertSha256: String? = null,
    /** Skip the WebSocket API and use the legacy REST API v2.0 directly. */
    val forceRest: Boolean = false,
    val authMethod: AuthMethod = AuthMethod.API_KEY,
    /** Lifetime of the reusable session token requested after a password (+2FA) sign-in. */
    val sessionDays: Int = 7,
) {
    val isHttps: Boolean get() = url.startsWith("https://", ignoreCase = true)
    val displayHost: String get() = url.substringAfter("://")
}

enum class AuthMethod(val label: String) {
    API_KEY("API key"),
    PASSWORD("Password"),
}

enum class ApiFlavor(val label: String) {
    WEBSOCKET("JSON-RPC WebSocket (/api/current)"),
    REST("REST API v2.0 (legacy)"),
}

data class SystemInfo(
    val hostname: String,
    val version: String,
    val uptimeSeconds: Long?,
    val uptimeText: String?,
    val cpuModel: String?,
    val cores: Int?,
    val physicalMemory: Long?,
    val loadAverage: List<Double>,
    val systemProduct: String?,
    val eccMemory: Boolean?,
)

data class RealtimeStats(
    val cpuPercent: Double?,
    val cpuTempC: Double?,
    val memoryTotal: Long?,
    val memoryAvailable: Long?,
    val arcSize: Long?,
    val netRxBytesPerSec: Double?,
    val netTxBytesPerSec: Double?,
    /** Per-thread usage (`cpu0`, `cpu1`, …) in percent. */
    val cpuCores: List<Double> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
) {
    val memoryUsed: Long?
        get() = if (memoryTotal != null && memoryAvailable != null) (memoryTotal - memoryAvailable).coerceAtLeast(0) else null

    /**
     * Memory split like the TrueNAS web UI: services (processes, apps, VMs) / ZFS cache (ARC) / free.
     * `physical_memory_available` is Linux MemAvailable, which does not count the ARC as available, so
     * services = total - available - arc. If a kernel ever reports ARC as available we subtract it from free instead.
     */
    val memoryBreakdown: MemoryBreakdown?
        get() {
            val total = memoryTotal ?: return null
            val avail = memoryAvailable ?: return null
            if (total <= 0) return null
            val used = (total - avail).coerceIn(0, total)
            val arc = (arcSize ?: 0L).coerceIn(0, total)
            return if (arc <= used) MemoryBreakdown(total, services = used - arc, arc = arc, free = avail.coerceAtLeast(0))
            else MemoryBreakdown(total, services = used, arc = arc, free = (avail - arc).coerceAtLeast(0))
        }
}

data class MemoryBreakdown(val total: Long, val services: Long, val arc: Long, val free: Long) {
    val servicesFraction get() = services.toFloat() / total
    val arcFraction get() = arc.toFloat() / total
}

enum class Health { HEALTHY, WARNING, CRITICAL, UNKNOWN }

data class Pool(
    val id: Long,
    val name: String,
    val status: String,
    val healthy: Boolean,
    val warning: Boolean,
    val statusDetail: String?,
    val size: Long?,
    val allocated: Long?,
    val free: Long?,
    val fragmentation: String?,
    val scanFunction: String?,
    val scanState: String?,
    val scanPercent: Double?,
    val scanErrors: Long?,
    val diskNames: List<String>,
) {
    val health: Health
        get() = when {
            status.equals("ONLINE", true) && healthy && !warning -> Health.HEALTHY
            status.equals("ONLINE", true) || status.equals("DEGRADED", true) || warning -> Health.WARNING
            else -> Health.CRITICAL
        }
    val usedFraction: Float
        get() = if (size != null && size > 0 && allocated != null) (allocated.toDouble() / size).toFloat().coerceIn(0f, 1f) else 0f
}

data class Disk(
    val name: String,
    val serial: String?,
    val model: String?,
    val size: Long?,
    val type: String?,
    val rotationRate: Int?,
    val pool: String?,
    val temperatureC: Double?,
)

data class Dataset(
    val id: String,
    val pool: String,
    val type: String,
    val used: Long?,
    val available: Long?,
    val encrypted: Boolean,
    val locked: Boolean,
    val mountpoint: String?,
) {
    val depth: Int get() = id.count { it == '/' }
    val shortName: String get() = id.substringAfterLast('/')
    val isSystem: Boolean
        get() = id.contains("/.system") || id.contains("/ix-applications") || id.contains("/.ix-") ||
            id.contains("/ix-apps") || shortName.startsWith(".")
    val usedFraction: Float
        get() {
            val u = used ?: return 0f
            val total = u + (available ?: return 0f)
            return if (total > 0) (u.toDouble() / total).toFloat() else 0f
        }
}

enum class AppState { RUNNING, STOPPED, DEPLOYING, STOPPING, CRASHED, UNKNOWN }

data class AppInfo(
    val name: String,
    val state: AppState,
    val version: String?,
    val upgradeAvailable: Boolean,
    val imageUpdatesAvailable: Boolean,
    val description: String?,
    val portalUrl: String?,
    val containers: Int?,
    /** true when the app came from the pre-24.10 Kubernetes `chart.release.*` API. */
    val legacyChart: Boolean = false,
    /** Newest catalog version (`latest_version`), when an upgrade is available. */
    val latestVersion: String? = null,
)

/** Result of `app.upgrade_summary`. */
data class AppUpgradeSummary(
    val currentVersion: String?,
    val targetVersion: String?,
    val changelog: String?,
)

enum class JobState { WAITING, RUNNING, SUCCESS, FAILED, ABORTED, UNKNOWN;
    val active: Boolean get() = this == WAITING || this == RUNNING
}

/** A middleware job from `core.get_jobs`. */
data class JobInfo(
    val id: Long,
    val method: String,
    val firstArgument: String?,
    val description: String?,
    val state: JobState,
    val percent: Double?,
    val progressText: String?,
    val error: String?,
    val abortable: Boolean,
    val startedMillis: Long?,
    val finishedMillis: Long?,
)

enum class AppAction(val label: String) { START("Start"), STOP("Stop"), RESTART("Restart"), REDEPLOY("Redeploy") }

data class AlertItem(
    val uuid: String,
    val level: String,
    val text: String,
    val klass: String?,
    val datetimeMillis: Long?,
    val dismissed: Boolean,
    val oneShot: Boolean,
) {
    val health: Health
        get() = when (level.uppercase()) {
            "INFO", "NOTICE" -> Health.HEALTHY
            "WARNING" -> Health.WARNING
            else -> Health.CRITICAL
        }
}

data class ServiceInfo(
    val id: Long,
    val service: String,
    val running: Boolean,
    val enabledOnBoot: Boolean,
) {
    val displayName: String get() = SERVICE_NAMES[service] ?: service.uppercase()

    companion object {
        val SERVICE_NAMES = mapOf(
            "cifs" to "SMB", "nfs" to "NFS", "ssh" to "SSH", "ftp" to "FTP",
            "iscsitarget" to "iSCSI", "snmp" to "SNMP", "ups" to "UPS", "smartd" to "S.M.A.R.T.",
            "nvmet" to "NVMe-oF", "webdav" to "WebDAV", "rsync" to "Rsync", "s3" to "S3",
            "lldp" to "LLDP", "openvpn_client" to "OpenVPN Client", "openvpn_server" to "OpenVPN Server",
            "dynamicdns" to "Dynamic DNS", "netdata" to "Netdata", "tftp" to "TFTP", "webshare" to "WebShare",
            "docker" to "Docker", "incus" to "Incus", "mdns" to "mDNS", "wsdd" to "WS-Discovery",
            "keepalived" to "Keepalived", "truecommand" to "TrueCommand",
        )
    }
}
