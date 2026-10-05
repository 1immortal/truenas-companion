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
    /** Optional address on the home network (e.g. `https://192.168.1.10`); [url] is then the remote address. */
    val localUrl: String? = null,
    /** Pinned self-signed certificate for [localUrl] (the LAN IP usually has a different certificate). */
    val localPinnedCertSha256: String? = null,
    val routeMode: RouteMode = RouteMode.AUTO,
    /**
     * Optional Tailscale address of the NAS (`https://100.x.y.z` or a MagicDNS name). Used when the Tailscale app's VPN
     * is connected on the phone and the address answers.
     */
    val tailscaleUrl: String? = null,
    /** Pinned certificate for [tailscaleUrl] (usually the same self-signed certificate as the local address). */
    val tailscalePinnedCertSha256: String? = null,
    /** Built-in WireGuard tunnel use. The tunnel config itself is stored encrypted, separately (see SettingsStore). */
    val vpnMode: VpnMode = VpnMode.OFF,
    /** A WireGuard config is saved for this server (flag only; the config is a secret). */
    val wireGuardConfigured: Boolean = false,
    /** Which address this (resolved) copy connects to. Never stored: see [forRoute]. */
    @kotlinx.serialization.Transient val activeRoute: Route = Route.REMOTE,
) {
    val isHttps: Boolean get() = url.startsWith("https://", ignoreCase = true)
    val displayHost: String get() = url.substringAfter("://")

    val hasLocal: Boolean get() = !localUrl.isNullOrBlank()
    val isLocalHttps: Boolean get() = localUrl?.startsWith("https://", ignoreCase = true) == true

    /**
     * The local address may be used. TrueNAS revokes an API key that arrives over plain http, so an http local
     * address is only ever used with password sign-in.
     */
    val localUsable: Boolean get() = hasLocal && (isLocalHttps || authMethod == AuthMethod.PASSWORD)

    val hasTailscale: Boolean get() = !tailscaleUrl.isNullOrBlank()
    /** Same rule as [localUsable]: never send an API key over plain http. */
    val tailscaleUsable: Boolean get() = hasTailscale && (tailscaleUrl!!.startsWith("https://", true) || authMethod == AuthMethod.PASSWORD)

    /**
     * The NAS's LAN IP from [localUrl] when it is an IP literal: the built-in tunnel only routes this one address
     * (see WgConf.forApp), and connects to [localUrl] through it so the local certificate pin and same-NAS check apply.
     */
    val lanIp: String? get() = localUrl?.let { app.truenascompanion.util.UrlUtils.ipLiteralHost(it) }

    /** The built-in WireGuard tunnel may be used: a config is saved, it isn't switched off, and the local address is an IP. */
    val wireGuardUsable: Boolean get() = wireGuardConfigured && vpnMode != VpnMode.OFF && localUsable && lanIp != null

    /** More than one way to reach the NAS is configured, so the route chip is worth showing. */
    val hasAlternativeRoutes: Boolean get() = hasLocal || hasTailscale || wireGuardConfigured

    /** A copy that connects to the given address (same NAS, same credentials and session token). */
    fun forRoute(route: Route): ServerConfig = when {
        route == Route.LOCAL && localUsable -> copy(url = localUrl!!, pinnedCertSha256 = localPinnedCertSha256, activeRoute = Route.LOCAL)
        // Through the tunnel the app talks to the LAN address, so the local pin and the same-NAS check still apply.
        route == Route.VPN && localUsable -> copy(url = localUrl!!, pinnedCertSha256 = localPinnedCertSha256, activeRoute = Route.VPN)
        route == Route.TAILSCALE && tailscaleUsable ->
            copy(url = tailscaleUrl!!, pinnedCertSha256 = tailscalePinnedCertSha256 ?: localPinnedCertSha256, activeRoute = Route.TAILSCALE)
        else -> copy(activeRoute = Route.REMOTE)
    }
}

/** How the app reaches the NAS right now (shown as a chip). */
enum class Route(val label: String) { LOCAL("Local"), TAILSCALE("Tailscale"), VPN("VPN"), REMOTE("Remote") }

/** Built-in WireGuard tunnel per server. */
enum class VpnMode(val label: String) {
    OFF("Off"),
    /** Only when neither the local address nor Tailscale reaches the NAS; down 30 s after the app leaves the screen. */
    AUTO("Auto"),
    /** Up whenever the app is open (and for instant alerts), used before every other address. */
    ALWAYS("Always on"),
}

enum class RouteMode(val label: String) {
    AUTO("Auto"),
    LOCAL("Always local"),
    REMOTE("Always remote"),
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
    val scanStartMillis: Long? = null,
    val scanEndMillis: Long? = null,
) {
    /** A scrub (not a resilver) is running right now. */
    val scrubRunning: Boolean get() = scanFunction.equals("SCRUB", true) && scanState.equals("SCANNING", true)
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
    /** `disk.query` identifier, e.g. `{serial_lunid}…`; used by SMART test cron jobs. */
    val identifier: String? = null,
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
    val compression: String? = null,
    val compressratio: String? = null,
    val comments: String? = null,
    val recordsize: String? = null,
    val volsize: Long? = null,
    val readonly: String? = null,
) {
    val depth: Int get() = id.count { it == '/' }
    val shortName: String get() = id.substringAfterLast('/')
    val isVolume: Boolean get() = type.equals("VOLUME", true)
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

/** SMB share (`sharing.smb.query`), 25.10 purpose enum. */
data class SmbShare(
    val id: Int,
    val name: String,
    val path: String,
    val purpose: String,
    val enabled: Boolean,
    val comment: String,
    val readonly: Boolean,
    val browsable: Boolean,
    val locked: Boolean?,
) {
    val datasetId: String? get() = path.removePrefix("/mnt/").takeIf { path.startsWith("/mnt/") && it.isNotEmpty() }
}

/** NFS share (`sharing.nfs.query`). */
data class NfsShare(
    val id: Int,
    val path: String,
    val comment: String,
    val enabled: Boolean,
    val readonly: Boolean,
    val networks: List<String>,
    val hosts: List<String>,
    val locked: Boolean?,
) {
    val datasetId: String? get() = path.removePrefix("/mnt/").takeIf { path.startsWith("/mnt/") && it.isNotEmpty() }
}

/** Input for creating a dataset or ZVOL (`pool.dataset.create`). */
data class DatasetCreateRequest(
    val name: String,
    val type: String = "FILESYSTEM",
    val shareType: String = "GENERIC",
    val compression: String? = null,
    val comments: String? = null,
    val volsize: Long? = null,
    val sparse: Boolean = false,
)

/** Input for an SMB share create/update. */
data class SmbShareInput(
    val name: String,
    val path: String,
    val purpose: String = "DEFAULT_SHARE",
    val enabled: Boolean = true,
    val comment: String = "",
    val readonly: Boolean = false,
    val browsable: Boolean = true,
)

/** Input for an NFS share create/update. */
data class NfsShareInput(
    val path: String,
    val comment: String = "",
    val enabled: Boolean = true,
    val readonly: Boolean = false,
    val networks: List<String> = emptyList(),
    val hosts: List<String> = emptyList(),
)

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
    val customApp: Boolean = false,
    /** Catalog icon (`metadata.icon`). */
    val iconUrl: String? = null,
    val train: String? = null,
    /** Catalog app name (`metadata.name`), e.g. "immich" even if the app was installed as "photos". */
    val catalogName: String? = null,
    val containerDetails: List<AppContainerInfo> = emptyList(),
    /** All portals (label -> URL); [portalUrl] is the first one. */
    val portals: Map<String, String> = emptyMap(),
    val notes: String? = null,
)

/** One container of an app (`active_workloads.container_details`). */
data class AppContainerInfo(val id: String, val service: String, val image: String?, val state: String?)

/** An app in the catalog (`app.available`). */
data class CatalogApp(
    val name: String,
    val title: String,
    val description: String,
    val iconUrl: String?,
    val categories: List<String>,
    val train: String,
    val installed: Boolean,
    val latestVersion: String?,
    val latestAppVersion: String?,
    val popularity: Int? = null,
    val recommended: Boolean = false,
    val home: String? = null,
)

/** `catalog.get_app_details` for the version that would be installed. */
data class CatalogAppDetails(
    val app: CatalogApp,
    val version: String,
    val appVersion: String?,
    val readme: String?,
    /** `schema` of the version: `{groups: [...], questions: [...]}`. */
    val schema: kotlinx.serialization.json.JsonObject?,
    /** Default values for the questions. */
    val defaults: kotlinx.serialization.json.JsonObject,
    val screenshots: List<String>,
    val sources: List<String>,
)

/** Current configuration of an installed app plus the questions of its installed version (for the edit form). */
data class AppEditData(
    val app: String,
    val values: kotlinx.serialization.json.JsonObject,
    val schema: kotlinx.serialization.json.JsonObject?,
    val customApp: Boolean,
)

data class LogLine(val text: String, val timestamp: String?)

/** `app.stats` sample for one app. */
data class AppStats(
    val app: String,
    val cpuPercent: Int,
    val memoryBytes: Long,
    val rxBytesPerSec: Long,
    val txBytesPerSec: Long,
    val blkReadBytes: Long,
    val blkWriteBytes: Long,
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

enum class AppAction(val label: String, val progress: String, val done: String) {
    START("Start", "Starting", "started"),
    STOP("Stop", "Stopping", "stopped"),
    RESTART("Restart", "Restarting", "restarted"),
    /** `app.redeploy`: recreates the containers with the current config. Does NOT fetch newer images for the same tag. */
    REDEPLOY("Redeploy", "Redeploying", "redeployed"),
    /** `app.pull_images(app, {redeploy: true})`: pulls newer builds of the app's image tags, then redeploys. */
    PULL_REDEPLOY("Redeploy", "Pulling image", "updated to the newer image build"),
}

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

// --- Virtualization (0.4.1): classic VMs (`vm.*`) and Incus containers (`virt.instance.*`) ---

enum class VmState { RUNNING, STOPPED, SUSPENDED, UNKNOWN }

/** A VM device reduced to what the details screen shows. [kind] is the middleware `dtype`. */
data class VmDevice(val id: Int, val kind: String, val title: String, val detail: String?)

data class VmInfo(
    val id: Int,
    val name: String,
    val description: String,
    val state: VmState,
    val vcpus: Int,
    val cores: Int,
    val threads: Int,
    val memoryMb: Long,
    val autostart: Boolean,
    val bootloader: String?,
    val devices: List<VmDevice>,
    val displayAvailable: Boolean,
) {
    val totalCpus: Int get() = vcpus * cores * threads
    val hasWebDisplay: Boolean get() = devices.any { it.kind == "DISPLAY" && it.detail?.contains("web") == true }
}

enum class InstanceStatus { RUNNING, STOPPED, STARTING, STOPPING, FROZEN, ERROR, UNKNOWN }

data class VirtInstance(
    val id: String,
    val name: String,
    val type: String,
    val status: InstanceStatus,
    val cpu: String?,
    val memoryBytes: Long?,
    val autostart: Boolean,
    val image: String?,
    val addresses: List<String>,
    val storagePool: String?,
)

/** Everything the "New VM" form collects; the repository turns it into `vm.create` + `vm.device.create` calls. */
data class VmCreateRequest(
    val name: String,
    val description: String,
    val vcpus: Int,
    val cores: Int,
    val threads: Int,
    val memoryMb: Long,
    val bootloader: String,
    val autostart: Boolean,
    /** Parent dataset for a new zvol, e.g. "tank/vms"; null = no disk. */
    val diskParent: String?,
    val diskSizeGiB: Int,
    val isoPath: String?,
    val nicAttach: String?,
    val displayPassword: String?,
)

data class FsEntry(val name: String, val path: String, val isDirectory: Boolean)
