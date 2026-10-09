package app.truenascompanion.data.iscsi

import app.truenascompanion.data.api.arr
import app.truenascompanion.data.api.bool
import app.truenascompanion.data.api.long
import app.truenascompanion.data.api.obj
import app.truenascompanion.data.api.prim
import app.truenascompanion.data.api.str
import app.truenascompanion.data.model.Dataset
import app.truenascompanion.util.Format
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/*
 * 1.7.0: iSCSI block sharing, from the TrueNAS 25.10 middleware (`stable/goldeye`: `plugins/iscsi_/` and
 * `api/v25_10_2/iscsi_{global,portal,initiator,auth,target,extent,target_to_extent}.py`).
 */

data class IscsiGlobal(
    val basename: String = "iqn.2005-10.org.freenas.ctl",
    val isnsServers: List<String> = emptyList(),
    val listenPort: Int = 3260,
    val poolAvailThreshold: Int? = null,
    val alua: Boolean = false,
    val iser: Boolean = false,
)

data class IscsiPortal(val id: Int, val tag: Int, val comment: String, val ips: List<String>, val port: Int = 3260) {
    val label: String get() = comment.ifBlank { "Portal $tag" }
    val addresses: String get() = ips.joinToString(", ") { if (':' in it) "[$it]:$port" else "$it:$port" }
}

/** Authorized initiators; an empty list allows every initiator. */
data class IscsiInitiatorGroup(val id: Int, val initiators: List<String>, val comment: String) {
    val allowsAll: Boolean get() = initiators.isEmpty()
    val label: String get() = comment.ifBlank { if (allowsAll) "Any initiator" else initiators.first() + if (initiators.size > 1) " +${initiators.size - 1}" else "" }
}

/** CHAP credential ("authorized access"). Targets refer to its [tag], not its id; several users can share a tag. */
data class IscsiAuth(
    val id: Int,
    val tag: Int,
    val user: String,
    val secret: String?,
    val peeruser: String = "",
    val peersecret: String? = null,
    val discoveryAuth: String = "NONE",
    val redacted: Boolean = false,
) {
    val mutual: Boolean get() = peeruser.isNotBlank()
}

enum class IscsiAuthMethod(val label: String) { NONE("None"), CHAP("CHAP"), CHAP_MUTUAL("Mutual CHAP") }

/** A portal of a target, with who may connect ([initiator] null: anyone) and how they sign in ([auth] is an auth tag). */
data class IscsiGroup(val portal: Int?, val initiator: Int? = null, val authmethod: IscsiAuthMethod = IscsiAuthMethod.NONE, val auth: Int? = null)

data class IscsiTarget(
    val id: Int,
    val name: String,
    val alias: String? = null,
    val mode: String = "ISCSI",
    val groups: List<IscsiGroup> = emptyList(),
    val authNetworks: List<String> = emptyList(),
)

enum class ExtentType(val label: String) { DISK("Zvol"), FILE("File") }

data class IscsiExtent(
    val id: Int,
    val name: String,
    val type: ExtentType = ExtentType.DISK,
    /** `zvol/<pool>/<name>` for DISK extents. */
    val disk: String? = null,
    val path: String? = null,
    val filesize: Long = 0,
    val blocksize: Int = 512,
    val pblocksize: Boolean = false,
    val availThreshold: Int? = null,
    val comment: String = "",
    val naa: String = "",
    val insecureTpc: Boolean = true,
    val xen: Boolean = false,
    val rpm: String = "SSD",
    val ro: Boolean = false,
    val enabled: Boolean = true,
    val serial: String = "",
    val productId: String? = null,
    val locked: Boolean? = null,
) {
    val zvol: String? get() = disk?.removePrefix("zvol/")
    val location: String get() = if (type == ExtentType.DISK) zvol.orEmpty() else path.orEmpty()
}

data class IscsiLun(val id: Int, val target: Int, val lunid: Int, val extent: Int)

data class IscsiSession(val initiator: String, val initiatorAddr: String, val target: String, val targetAlias: String, val iser: Boolean = false)

/** Everything the iSCSI screens show, loaded at once. */
data class IscsiData(
    val global: IscsiGlobal = IscsiGlobal(),
    val portals: List<IscsiPortal> = emptyList(),
    val initiators: List<IscsiInitiatorGroup> = emptyList(),
    val auths: List<IscsiAuth> = emptyList(),
    val targets: List<IscsiTarget> = emptyList(),
    val extents: List<IscsiExtent> = emptyList(),
    val luns: List<IscsiLun> = emptyList(),
    /** Null when `iscsi.global.sessions` failed (e.g. no read role). */
    val sessions: List<IscsiSession>? = emptyList(),
    val serviceRunning: Boolean? = null,
    val serviceOnBoot: Boolean? = null,
    val listenChoices: List<String> = emptyList(),
    val datasets: List<Dataset> = emptyList(),
    val haLicensed: Boolean = false,
) {
    fun iqn(t: IscsiTarget) = IscsiLogic.iqn(global.basename, t.name)
    fun sessionsFor(t: IscsiTarget): List<IscsiSession> = sessions.orEmpty().filter { it.target == iqn(t) }
    fun lunsOf(t: IscsiTarget) = luns.filter { it.target == t.id }.sortedBy { it.lunid }
    fun extent(id: Int) = extents.firstOrNull { it.id == id }
    fun target(id: Int) = targets.firstOrNull { it.id == id }
    fun portal(id: Int?) = portals.firstOrNull { it.id == id }
    fun initiator(id: Int?) = initiators.firstOrNull { it.id == id }
    fun lunOf(e: IscsiExtent) = luns.firstOrNull { it.extent == e.id }
    val authTags: List<Int> get() = auths.map { it.tag }.distinct().sorted()
    fun authsWithTag(tag: Int?) = auths.filter { it.tag == tag }
    /** Zvols not used by an extent (the web UI's `disk_choices` is private, so the app filters `pool.dataset.query`). */
    fun freeZvols(except: String? = null): List<Dataset> {
        val used = extents.mapNotNull { it.zvol }.toSet() - setOfNotNull(except)
        return datasets.filter { it.isVolume && !it.isSystem && it.id !in used && !it.locked }
    }
}

// ---------------- forms ----------------

data class GlobalForm(val basename: String = "", val isns: String = "", val listenPort: String = "3260", val threshold: String = "", val alua: Boolean = false)

data class PortalForm(val comment: String = "", val ips: List<String> = listOf("0.0.0.0"), val newIp: String = "")

data class InitiatorForm(val comment: String = "", val allowAll: Boolean = true, val initiators: String = "")

data class AuthForm(
    val tag: String = "1",
    val user: String = "",
    val secret: String = "",
    val mutual: Boolean = false,
    val peeruser: String = "",
    val peersecret: String = "",
    val discoveryAuth: IscsiAuthMethod = IscsiAuthMethod.NONE,
    /** Editing: the stored secrets weren't sent; leaving them empty keeps them. */
    val secretHidden: Boolean = false,
    val peerSecretHidden: Boolean = false,
)

data class TargetForm(
    val name: String = "",
    val alias: String = "",
    val groups: List<IscsiGroup> = listOf(IscsiGroup(null)),
    val authNetworks: String = "",
    /** Kept as stored (Fibre Channel needs Enterprise hardware). */
    val mode: String = "ISCSI",
)

enum class SizeUnit(val bytes: Long) { MiB(1L shl 20), GiB(1L shl 30), TiB(1L shl 40) }

data class ExtentForm(
    val name: String = "",
    val type: ExtentType = ExtentType.DISK,
    val zvol: String = "",
    val path: String = "",
    val size: String = "",
    val unit: SizeUnit = SizeUnit.GiB,
    val blocksize: Int = 512,
    val pblocksize: Boolean = false,
    val availThreshold: String = "",
    val comment: String = "",
    val insecureTpc: Boolean = true,
    val xen: Boolean = false,
    val rpm: String = "SSD",
    val ro: Boolean = false,
    val enabled: Boolean = true,
    val serial: String = "",
    val productId: String = "",
    /** Editing a FILE extent: its size now (it can only grow). */
    val originalSize: Long? = null,
)

data class LunForm(val target: Int? = null, val extent: Int? = null, val lunid: String = "")

/** How the wizard gets its extent. */
enum class WizardExtent(val label: String) { NEW_ZVOL("New zvol"), EXISTING_ZVOL("Existing zvol"), FILE("New file") }

/** The web UI wizard's "Sharing platform" presets (block size, Xen compatibility; TPC on, SSD speed). */
enum class SharingPlatform(val label: String, val blocksize: Int, val xen: Boolean, val help: String) {
    MODERN("Modern OS", 4096, false, "4 KiB blocks: Windows 10+, current Linux and macOS initiators"),
    VMWARE("VMware", 512, false, "512-byte blocks with XCOPY offload, for ESXi datastores"),
    XEN("Xen", 512, true, "512-byte blocks with Xen compatibility"),
    LEGACY("Older OS", 512, false, "512-byte blocks for older initiators"),
}

enum class WizardInitiators(val label: String) { ALL("Anyone"), LIST("Only these"), EXISTING("Existing group") }
enum class WizardChap(val label: String) { NONE("No CHAP"), NEW("New CHAP user"), EXISTING("Existing") }

data class WizardForm(
    val name: String = "",
    val extent: WizardExtent = WizardExtent.NEW_ZVOL,
    val parent: String = "",
    val size: String = "",
    val unit: SizeUnit = SizeUnit.GiB,
    val sparse: Boolean = false,
    val zvol: String = "",
    val filePath: String = "",
    val platform: SharingPlatform = SharingPlatform.MODERN,
    val newPortal: Boolean = true,
    val portalId: Int? = null,
    val portalIps: List<String> = listOf("0.0.0.0"),
    val initiators: WizardInitiators = WizardInitiators.ALL,
    val initiatorList: String = "",
    val initiatorGroupId: Int? = null,
    val chap: WizardChap = WizardChap.NONE,
    val authTag: Int? = null,
    val chapUser: String = "",
    val chapSecret: String = "",
    val mutual: Boolean = false,
    val peerUser: String = "",
    val peerSecret: String = "",
)

object IscsiLogic {
    const val REDACTED = "********"
    val RPMS = listOf("SSD", "5400", "7200", "10000", "15000", "UNKNOWN")
    val BLOCKSIZES = listOf(512, 1024, 2048, 4096)
    const val MAX_LUN = 16382
    private val TARGET_NAME = Regex("^[-a-z0-9.:]+$")
    private val IPV4 = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")
    private val IPV6 = Regex("^[0-9a-fA-F:]*:[0-9a-fA-F:.]*$")

    fun isIp(s: String) = IPV4.matches(s) || (IPV6.matches(s) && s.count { it == ':' } >= 2)
    fun isNetwork(s: String): Boolean {
        val (ip, bits) = s.split('/').let { it[0] to it.getOrNull(1) }
        if (!isIp(ip) || s.count { it == '/' } > 1) return false
        val max = if (IPV4.matches(ip)) 32 else 128
        return bits == null || (bits.toIntOrNull() ?: -1) in 0..max
    }

    /** Full IQN: names that are already IQN/NAA/EUI stay as they are (like `active_sessions_for_targets`). */
    fun iqn(basename: String, name: String) = if (name.startsWith("iqn.") || name.startsWith("naa.") || name.startsWith("eui.")) name else "$basename:$name"

    private fun lines(s: String) = s.split('\n', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    private fun JsonObject.strings(k: String) = this[k].arr()?.mapNotNull { it.prim()?.contentOrNull } ?: emptyList()

    // ---------- parsing ----------

    fun global(o: JsonObject?) = o?.let {
        IscsiGlobal(it.str("basename").orEmpty(), it.strings("isns_servers"), it.long("listen_port")?.toInt() ?: 3260,
            it.long("pool_avail_threshold")?.toInt(), it.bool("alua") ?: false, it.bool("iser") ?: false)
    } ?: IscsiGlobal()

    fun portal(o: JsonObject): IscsiPortal? {
        val listen = o["listen"].arr()?.mapNotNull { it.obj() }.orEmpty()
        return IscsiPortal(o.long("id")?.toInt() ?: return null, o.long("tag")?.toInt() ?: 0, o.str("comment").orEmpty(),
            listen.mapNotNull { it.str("ip") }, listen.firstOrNull()?.long("port")?.toInt() ?: 3260)
    }

    fun initiator(o: JsonObject): IscsiInitiatorGroup? =
        IscsiInitiatorGroup(o.long("id")?.toInt() ?: return null, o.strings("initiators"), o.str("comment").orEmpty())

    fun auth(o: JsonObject): IscsiAuth? {
        val secret = o.str("secret")
        val peer = o.str("peersecret")
        return IscsiAuth(
            o.long("id")?.toInt() ?: return null, o.long("tag")?.toInt() ?: 0, o.str("user").orEmpty(),
            secret?.takeUnless { it == REDACTED }, o.str("peeruser").orEmpty(), peer?.takeUnless { it == REDACTED || it.isEmpty() },
            o.str("discovery_auth") ?: "NONE", redacted = secret == REDACTED,
        )
    }

    private fun method(s: String?) = runCatching { IscsiAuthMethod.valueOf(s!!) }.getOrDefault(IscsiAuthMethod.NONE)

    fun target(o: JsonObject): IscsiTarget? = IscsiTarget(
        id = o.long("id")?.toInt() ?: return null,
        name = o.str("name").orEmpty(),
        alias = o.str("alias")?.takeIf { it.isNotEmpty() },
        mode = o.str("mode") ?: "ISCSI",
        groups = o["groups"].arr()?.mapNotNull { it.obj() }?.map {
            IscsiGroup(it.long("portal")?.toInt(), it.long("initiator")?.toInt(), method(it.str("authmethod")), it.long("auth")?.toInt())
        }.orEmpty(),
        authNetworks = o.strings("auth_networks"),
    )

    fun extent(o: JsonObject): IscsiExtent? = IscsiExtent(
        id = o.long("id")?.toInt() ?: return null,
        name = o.str("name").orEmpty(),
        type = if (o.str("type") == "FILE") ExtentType.FILE else ExtentType.DISK,
        disk = o.str("disk") ?: o.str("path")?.takeIf { it.startsWith("zvol/") },
        path = o.str("path"),
        filesize = o.long("filesize") ?: 0,
        blocksize = o.long("blocksize")?.toInt() ?: 512,
        pblocksize = o.bool("pblocksize") ?: false,
        availThreshold = o.long("avail_threshold")?.toInt(),
        comment = o.str("comment").orEmpty(),
        naa = o.str("naa").orEmpty(),
        insecureTpc = o.bool("insecure_tpc") ?: true,
        xen = o.bool("xen") ?: false,
        rpm = o.str("rpm") ?: "SSD",
        ro = o.bool("ro") ?: false,
        enabled = o.bool("enabled") ?: true,
        serial = o.str("serial").orEmpty(),
        productId = o.str("product_id"),
        locked = o.bool("locked"),
    )

    fun lun(o: JsonObject): IscsiLun? = IscsiLun(o.long("id")?.toInt() ?: return null, o.long("target")?.toInt() ?: return null,
        o.long("lunid")?.toInt() ?: 0, o.long("extent")?.toInt() ?: return null)

    fun session(o: JsonObject): IscsiSession? = IscsiSession(o.str("initiator") ?: return null, o.str("initiator_addr").orEmpty(),
        o.str("target").orEmpty(), o.str("target_alias").orEmpty(), o.bool("iser") ?: false)

    // ---------- forms ----------

    fun globalForm(g: IscsiGlobal) = GlobalForm(g.basename, g.isnsServers.joinToString("\n"), g.listenPort.toString(), g.poolAvailThreshold?.toString().orEmpty(), g.alua)

    /** `iscsi.global.update` merges, so only changed keys are sent (`alua` only on licensed HA systems). */
    fun globalJson(f: GlobalForm, old: IscsiGlobal, haLicensed: Boolean) = buildJsonObject {
        if (f.basename.trim() != old.basename) put("basename", f.basename.trim())
        val isns = lines(f.isns)
        if (isns != old.isnsServers) put("isns_servers", JsonArray(isns.map { JsonPrimitive(it) }))
        f.listenPort.trim().toIntOrNull()?.takeIf { it != old.listenPort }?.let { put("listen_port", it) }
        val t = f.threshold.trim().toIntOrNull()
        if (t != old.poolAvailThreshold) put("pool_avail_threshold", t?.let { JsonPrimitive(it) } ?: JsonNull)
        if (haLicensed && f.alua != old.alua) put("alua", f.alua)
    }

    fun globalErrors(f: GlobalForm): Map<String, String> = buildMap {
        if (f.basename.isBlank()) put("basename", "Required, e.g. iqn.2005-10.org.freenas.ctl")
        else if (f.basename.trim().length > 120 || f.basename.any { it.isWhitespace() }) put("basename", "No spaces, up to 120 characters")
        if ((f.listenPort.trim().toIntOrNull() ?: 0) !in 1025..65535) put("listen_port", "1025–65535")
        if (f.threshold.isNotBlank() && (f.threshold.trim().toIntOrNull() ?: 0) !in 1..99) put("pool_avail_threshold", "1–99 %, or empty for no alert")
        lines(f.isns).firstOrNull { !isnsOk(it) }?.let { put("isns_servers", "$it isn't an IP address (optionally :port)") }
    }

    /** IP, IP:port or [IPv6]:port, as `validate_isns_server` accepts. */
    fun isnsOk(s: String): Boolean {
        val (host, port) = when {
            s.startsWith("[") -> s.substringAfter('[').substringBefore(']') to s.substringAfter(']', "").removePrefix(":").ifEmpty { null }
            s.count { it == ':' } == 1 -> s.substringBefore(':') to s.substringAfter(':')
            else -> s to null
        }
        return isIp(host) && (port == null || (port.toIntOrNull() ?: 0) in 1..65535)
    }

    fun portalForm(p: IscsiPortal) = PortalForm(p.comment, p.ips)
    fun portalJson(f: PortalForm) = buildJsonObject {
        put("comment", f.comment.trim())
        put("listen", buildJsonArray { f.ips.forEach { add(buildJsonObject { put("ip", it) }) } })
    }
    fun portalErrors(f: PortalForm, d: IscsiData, editing: Int?): Map<String, String> = buildMap {
        if (f.ips.isEmpty()) put("listen", "Add at least one address")
        f.ips.firstOrNull { !isIp(it) }?.let { put("listen", "$it isn't an IP address") }
        val taken = d.portals.filter { it.id != editing }.flatMap { p -> p.ips.map { it to p } }.toMap()
        f.ips.firstOrNull { it in taken }?.let { put("listen", "$it is already used by ${taken.getValue(it).label}") }
        if (f.newIp.isNotBlank() && !isIp(f.newIp.trim())) put("new_ip", "Not an IP address")
    }

    fun initiatorForm(g: IscsiInitiatorGroup) = InitiatorForm(g.comment, g.allowsAll, g.initiators.joinToString("\n"))
    fun initiatorJson(f: InitiatorForm) = buildJsonObject {
        put("comment", f.comment.trim())
        put("initiators", JsonArray(if (f.allowAll) emptyList() else lines(f.initiators).map { JsonPrimitive(it) }))
    }
    fun initiatorErrors(f: InitiatorForm): Map<String, String> = buildMap {
        if (!f.allowAll && lines(f.initiators).isEmpty()) put("initiators", "Enter at least one IQN or address, or allow everyone")
        lines(f.initiators).firstOrNull { !f.allowAll && it.contains('"') }?.let { put("initiators", "Quotes aren't allowed") }
    }

    fun authForm(a: IscsiAuth) = AuthForm(a.tag.toString(), a.user, a.secret.orEmpty(), a.mutual, a.peeruser, a.peersecret.orEmpty(),
        method(a.discoveryAuth), secretHidden = a.redacted, peerSecretHidden = a.redacted && a.mutual)

    /** Create sends everything; update leaves out secrets that weren't retyped (TrueNAS keeps them). */
    fun authJson(f: AuthForm, editing: Boolean) = buildJsonObject {
        put("tag", f.tag.trim().toIntOrNull() ?: 1)
        put("user", f.user.trim())
        if (!editing || f.secret.isNotEmpty()) put("secret", f.secret)
        put("peeruser", if (f.mutual) f.peeruser.trim() else "")
        if (!f.mutual) put("peersecret", "") else if (!editing || f.peersecret.isNotEmpty()) put("peersecret", f.peersecret)
        put("discovery_auth", if (!f.mutual && f.discoveryAuth == IscsiAuthMethod.CHAP_MUTUAL) "NONE" else f.discoveryAuth.name)
    }

    fun secretError(s: String, title: String): String? = when {
        s.length !in 12..16 -> "$title must be 12–16 characters"
        s != s.trim() -> "$title can't start or end with a space"
        '#' in s -> "$title can't contain #"
        else -> null
    }

    fun authErrors(f: AuthForm, d: IscsiData, editing: Int?): Map<String, String> = buildMap {
        if ((f.tag.trim().toIntOrNull() ?: 0) < 1) put("tag", "A group number of 1 or more")
        if (f.user.isBlank()) put("user", "Required")
        val keepSecret = editing != null && f.secretHidden && f.secret.isEmpty()
        if (!keepSecret) secretError(f.secret, "Secret")?.let { put("secret", it) }
        if (f.mutual) {
            if (f.peeruser.isBlank()) put("peeruser", "Required for mutual CHAP")
            val keepPeer = editing != null && f.peerSecretHidden && f.peersecret.isEmpty()
            if (!keepPeer) {
                if (f.peersecret.isNotEmpty() && f.peersecret == f.secret) put("peersecret", "Must differ from the secret")
                else secretError(f.peersecret, "Peer secret")?.let { put("peersecret", it) }
            }
        }
        if (f.discoveryAuth == IscsiAuthMethod.CHAP_MUTUAL) {
            if (!f.mutual) put("discovery_auth", "Mutual CHAP discovery needs a peer user")
            else if (d.auths.any { it.id != editing && it.discoveryAuth == "CHAP_MUTUAL" }) put("discovery_auth", "Only one entry may use mutual CHAP for discovery")
        }
    }

    fun targetForm(t: IscsiTarget) = TargetForm(t.name, t.alias.orEmpty(), t.groups, t.authNetworks.joinToString("\n"), t.mode)

    /** `iscsi_parameters` isn't sent, so an update keeps it (update merges). */
    fun targetJson(f: TargetForm) = buildJsonObject {
        put("name", f.name.trim())
        put("alias", f.alias.trim().ifEmpty { null }?.let { JsonPrimitive(it) } ?: JsonNull)
        put("mode", f.mode)
        put("groups", buildJsonArray {
            f.groups.forEach { g ->
                add(buildJsonObject {
                    put("portal", g.portal)
                    put("initiator", g.initiator?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("authmethod", g.authmethod.name)
                    put("auth", if (g.authmethod == IscsiAuthMethod.NONE) JsonNull else g.auth?.let { JsonPrimitive(it) } ?: JsonNull)
                })
            }
        })
        put("auth_networks", JsonArray(lines(f.authNetworks).map { JsonPrimitive(it) }))
    }

    fun targetNameError(name: String, d: IscsiData, editing: Int?): String? {
        val n = name.trim()
        return when {
            n.isEmpty() -> "Required"
            n.length > 120 -> "Up to 120 characters"
            !TARGET_NAME.matches(n) -> "Only lowercase letters, digits, dot, dash and colon"
            d.targets.any { it.id != editing && it.name == n } -> "A target with this name exists"
            else -> null
        }
    }

    fun targetErrors(f: TargetForm, d: IscsiData, editing: Int?): Map<String, String> = buildMap {
        targetNameError(f.name, d, editing)?.let { put("name", it) }
        val alias = f.alias.trim()
        when {
            alias == "target" -> put("alias", "\"target\" is reserved")
            '"' in alias -> put("alias", "Quotes aren't allowed")
            alias.isNotEmpty() && d.targets.any { it.id != editing && it.alias == alias } -> put("alias", "Another target has this alias")
        }
        val seen = mutableSetOf<Int>()
        f.groups.forEach { g ->
            when {
                g.portal == null -> put("portal", "Choose a portal")
                !seen.add(g.portal) -> put("portal", "Each portal only once per target")
            }
            if (g.authmethod != IscsiAuthMethod.NONE && g.auth == null) put("auth", "Choose a CHAP group")
            if (g.authmethod == IscsiAuthMethod.CHAP_MUTUAL && g.auth != null && d.authsWithTag(g.auth).none { it.mutual }) put("auth", "CHAP group ${g.auth} has no peer user for mutual CHAP")
        }
        lines(f.authNetworks).firstOrNull { !isNetwork(it) }?.let { put("auth_networks", "$it isn't a network like 192.168.1.0/24") }
    }

    fun extentForm(e: IscsiExtent) = ExtentForm(
        name = e.name, type = e.type, zvol = e.zvol.orEmpty(), path = e.path.orEmpty(),
        size = if (e.type == ExtentType.FILE && e.filesize > 0) bestSize(e.filesize).first else "",
        unit = if (e.type == ExtentType.FILE && e.filesize > 0) bestSize(e.filesize).second else SizeUnit.GiB,
        blocksize = e.blocksize, pblocksize = e.pblocksize, availThreshold = e.availThreshold?.toString().orEmpty(), comment = e.comment,
        insecureTpc = e.insecureTpc, xen = e.xen, rpm = e.rpm, ro = e.ro, enabled = e.enabled, serial = e.serial, productId = e.productId.orEmpty(),
        originalSize = if (e.type == ExtentType.FILE) e.filesize else null,
    )

    /** Largest unit that shows [bytes] as a whole number. */
    fun bestSize(bytes: Long): Pair<String, SizeUnit> =
        SizeUnit.entries.reversed().firstOrNull { bytes % it.bytes == 0L }?.let { (bytes / it.bytes).toString() to it } ?: ((bytes / SizeUnit.MiB.bytes).toString() to SizeUnit.MiB)

    fun bytes(size: String, unit: SizeUnit): Long? = size.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }?.let { (it * unit.bytes).toLong() }

    fun extentJson(f: ExtentForm, editing: Boolean) = buildJsonObject {
        put("name", f.name.trim())
        put("type", f.type.name)
        if (f.type == ExtentType.DISK) {
            put("disk", "zvol/" + f.zvol.trim().removePrefix("zvol/"))
            put("path", JsonNull)
        } else {
            put("disk", JsonNull)
            put("path", f.path.trim())
            put("filesize", bytes(f.size, f.unit) ?: 0)
        }
        put("blocksize", f.blocksize)
        put("pblocksize", f.pblocksize)
        put("avail_threshold", f.availThreshold.trim().toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonNull)
        put("comment", f.comment.trim())
        put("insecure_tpc", f.insecureTpc)
        put("xen", f.xen)
        put("rpm", f.rpm)
        put("ro", f.ro)
        put("enabled", f.enabled)
        if (f.serial.isNotBlank()) put("serial", f.serial.trim()) else if (!editing) put("serial", JsonNull)
        put("product_id", f.productId.trim().ifEmpty { null }?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    private fun sanitize(n: String) = n.replace('.', '_').replace('/', '-')

    fun extentNameError(name: String, d: IscsiData, editing: Int?): String? {
        val n = name.trim()
        val others = d.extents.filter { it.id != editing }
        return when {
            n.isEmpty() -> "Required"
            n.length > 64 -> "Up to 64 characters"
            '"' in n -> "Quotes aren't allowed"
            others.any { it.name == n } -> "An extent with this name exists"
            others.any { sanitize(it.name) == sanitize(n) } -> "Too close to extent ${others.first { sanitize(it.name) == sanitize(n) }.name} (. and / count as the same)"
            else -> null
        }
    }

    fun extentErrors(f: ExtentForm, d: IscsiData, editing: Int?): Map<String, String> = buildMap {
        extentNameError(f.name, d, editing)?.let { put("name", it) }
        val others = d.extents.filter { it.id != editing }
        if (f.type == ExtentType.DISK) {
            val z = f.zvol.trim().removePrefix("zvol/")
            when {
                z.isEmpty() -> put("disk", "Choose a zvol")
                others.any { it.zvol == z } -> put("disk", "Already used by extent ${others.first { it.zvol == z }.name}")
            }
            if ('@' in z && !f.ro) put("ro", "A snapshot can only be shared read-only")
        } else {
            val p = f.path.trim()
            val size = bytes(f.size, f.unit)
            when {
                p.isEmpty() -> put("path", "Required, e.g. /mnt/tank/iscsi/disk1.img")
                !p.startsWith("/mnt/") || p.removePrefix("/mnt/").count { it == '/' } < 1 -> put("path", "A file inside a pool dataset, under /mnt/<pool>/")
                p.endsWith("/") -> put("path", "A file path, not a folder")
                ' ' in p -> put("path", "No spaces in the path")
                others.any { it.path == p } -> put("path", "Already used by extent ${others.first { it.path == p }.name}")
            }
            when {
                f.size.isNotBlank() && size == null -> put("filesize", "A number")
                size != null && size > 0 && size % f.blocksize != 0L -> put("filesize", "Must be a multiple of the ${f.blocksize}-byte block size")
                size != null && f.originalSize != null && f.originalSize > 0 && size < f.originalSize -> put("filesize", "Can only grow (now ${Format.bytes(f.originalSize)})")
                editing == null && (size == null || size == 0L) -> put("filesize", "Size of the new file (0 only for an existing file)")
            }
        }
        if (f.availThreshold.isNotBlank() && (f.availThreshold.trim().toIntOrNull() ?: 0) !in 1..99) put("avail_threshold", "1–99 %, or empty")
        val s = f.serial.trim()
        if (s.length > 20) put("serial", "Up to 20 characters") else if ('"' in s) put("serial", "Quotes aren't allowed")
        else if (s.isNotEmpty() && others.any { it.serial == s }) put("serial", "Another extent has this serial")
        if (f.productId.trim().length > 16) put("product_id", "Up to 16 characters")
    }

    fun lunJson(f: LunForm) = buildJsonObject {
        put("target", f.target); put("extent", f.extent)
        put("lunid", f.lunid.trim().toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    fun lunErrors(f: LunForm, d: IscsiData, editing: Int?): Map<String, String> = buildMap {
        if (f.target == null) put("target", "Choose a target")
        if (f.extent == null) put("extent", "Choose an extent")
        else d.luns.firstOrNull { it.extent == f.extent && it.id != editing }?.let { put("extent", "Already shared by ${d.target(it.target)?.name ?: "another target"}; an extent can only be in one target") }
        val id = f.lunid.trim()
        if (id.isNotEmpty()) {
            val n = id.toIntOrNull()
            when {
                n == null || n !in 0..MAX_LUN -> put("lunid", "0–$MAX_LUN, or empty for the next free one")
                d.luns.any { it.target == f.target && it.lunid == n && it.id != editing } -> put("lunid", "LUN $n is taken on this target")
            }
        }
    }

    // ---------- wizard ----------

    fun wizardErrors(f: WizardForm, d: IscsiData): Map<String, String> = buildMap {
        targetNameError(f.name, d, null)?.let { put("name", it) }
        if (!containsKey("name")) extentNameError(f.name, d, null)?.let { put("name", "Extent: $it") }
        when (f.extent) {
            WizardExtent.NEW_ZVOL -> {
                if (f.parent.isBlank()) put("parent", "Choose where to create the zvol")
                else if (d.datasets.any { it.id == "${f.parent}/${f.name.trim()}" }) put("name", "${f.parent}/${f.name.trim()} already exists")
                val b = bytes(f.size, f.unit)
                if (b == null || b < SizeUnit.MiB.bytes) put("size", "At least 1 MiB")
            }
            WizardExtent.EXISTING_ZVOL -> if (f.zvol.isBlank()) put("zvol", "Choose a zvol")
            WizardExtent.FILE -> {
                val p = f.filePath.trim()
                when {
                    !p.startsWith("/mnt/") || p.removePrefix("/mnt/").count { it == '/' } < 1 || p.endsWith("/") -> put("path", "A new file inside a pool dataset, e.g. /mnt/tank/iscsi/${f.name.ifBlank { "disk" }}.img")
                    ' ' in p -> put("path", "No spaces in the path")
                    d.extents.any { it.path == p } -> put("path", "Already used by an extent")
                }
                val b = bytes(f.size, f.unit)
                if (b == null || b < SizeUnit.MiB.bytes) put("size", "At least 1 MiB")
                else if (b % f.platform.blocksize != 0L) put("size", "Must be a multiple of ${f.platform.blocksize} bytes")
            }
        }
        if (f.newPortal) {
            if (f.portalIps.isEmpty()) put("portal", "Pick at least one address")
            val taken = d.portals.flatMap { it.ips }.toSet()
            f.portalIps.firstOrNull { it in taken }?.let { put("portal", "$it is already used by another portal; pick that portal instead") }
        } else if (f.portalId == null) put("portal", "Choose a portal")
        when (f.initiators) {
            WizardInitiators.LIST -> if (lines(f.initiatorList).isEmpty()) put("initiators", "Enter at least one IQN or address")
            WizardInitiators.EXISTING -> if (f.initiatorGroupId == null) put("initiators", "Choose a group")
            WizardInitiators.ALL -> Unit
        }
        when (f.chap) {
            WizardChap.EXISTING -> when {
                f.authTag == null -> put("auth", "Choose a CHAP group")
                f.mutual && d.authsWithTag(f.authTag).none { it.mutual } -> put("auth", "This group has no peer user for mutual CHAP")
            }
            WizardChap.NEW -> {
                if (f.chapUser.isBlank()) put("chap_user", "Required")
                secretError(f.chapSecret, "Secret")?.let { put("chap_secret", it) }
                if (f.mutual) {
                    if (f.peerUser.isBlank()) put("peer_user", "Required")
                    if (f.peerSecret == f.chapSecret && f.peerSecret.isNotEmpty()) put("peer_secret", "Must differ from the secret")
                    else secretError(f.peerSecret, "Peer secret")?.let { put("peer_secret", it) }
                }
            }
            WizardChap.NONE -> Unit
        }
    }

    fun wizardZvolName(f: WizardForm) = "${f.parent.trim('/')}/${f.name.trim()}"

    fun wizardZvolJson(f: WizardForm) = buildJsonObject {
        put("name", wizardZvolName(f))
        put("type", "VOLUME")
        put("volsize", roundUp(bytes(f.size, f.unit) ?: 0, 1L shl 20))
        put("sparse", f.sparse)
    }

    private fun roundUp(v: Long, m: Long) = ((v + m - 1) / m) * m

    fun wizardExtentJson(f: WizardForm) = buildJsonObject {
        put("name", f.name.trim())
        when (f.extent) {
            WizardExtent.NEW_ZVOL -> { put("type", "DISK"); put("disk", "zvol/" + wizardZvolName(f)) }
            WizardExtent.EXISTING_ZVOL -> { put("type", "DISK"); put("disk", "zvol/" + f.zvol.removePrefix("zvol/")) }
            WizardExtent.FILE -> { put("type", "FILE"); put("path", f.filePath.trim()); put("filesize", bytes(f.size, f.unit) ?: 0) }
        }
        put("blocksize", f.platform.blocksize)
        put("insecure_tpc", true)
        put("xen", f.platform.xen)
        put("rpm", "SSD")
    }

    fun wizardTargetJson(f: WizardForm, portal: Int, initiator: Int?, authTag: Int?) = buildJsonObject {
        put("name", f.name.trim())
        put("groups", buildJsonArray {
            add(buildJsonObject {
                put("portal", portal)
                put("initiator", initiator?.let { JsonPrimitive(it) } ?: JsonNull)
                val m = when { f.chap == WizardChap.NONE -> IscsiAuthMethod.NONE; f.mutual -> IscsiAuthMethod.CHAP_MUTUAL; else -> IscsiAuthMethod.CHAP }
                put("authmethod", m.name)
                put("auth", if (m == IscsiAuthMethod.NONE) JsonNull else authTag?.let { JsonPrimitive(it) } ?: JsonNull)
            })
        })
    }

    fun wizardAuthJson(f: WizardForm, tag: Int) = authJson(
        AuthForm(tag.toString(), f.chapUser, f.chapSecret, f.mutual, f.peerUser, f.peerSecret), editing = false,
    )

    fun nextAuthTag(d: IscsiData) = (d.auths.maxOfOrNull { it.tag } ?: 0) + 1

    fun initiatorsListJson(f: WizardForm) = initiatorJson(InitiatorForm("Created for ${f.name.trim()}", false, f.initiatorList))

    fun groupSummary(g: IscsiGroup, d: IscsiData): String = listOf(
        d.portal(g.portal)?.label ?: "Portal ?",
        g.initiator?.let { d.initiator(it)?.label ?: "group $it" } ?: "any initiator",
        when (g.authmethod) { IscsiAuthMethod.NONE -> "no CHAP"; IscsiAuthMethod.CHAP -> "CHAP group ${g.auth}"; IscsiAuthMethod.CHAP_MUTUAL -> "mutual CHAP group ${g.auth}" },
    ).joinToString(" · ")

    fun extentSize(e: IscsiExtent, d: IscsiData): Long? = when (e.type) {
        ExtentType.FILE -> e.filesize.takeIf { it > 0 }
        ExtentType.DISK -> d.datasets.firstOrNull { it.id == e.zvol }?.volsize
    }

    // ---------- in use ----------

    fun targetsUsingPortal(p: IscsiPortal, d: IscsiData) = d.targets.filter { t -> t.groups.any { it.portal == p.id } }
    fun targetsUsingInitiator(g: IscsiInitiatorGroup, d: IscsiData) = d.targets.filter { t -> t.groups.any { it.initiator == g.id } }
    /** TrueNAS refuses to delete the last user of a CHAP group that targets use. */
    fun targetsBlockingAuthDelete(a: IscsiAuth, d: IscsiData): List<IscsiTarget> =
        if (d.auths.any { it.id != a.id && it.tag == a.tag }) emptyList()
        else d.targets.filter { t -> t.groups.any { it.authmethod != IscsiAuthMethod.NONE && it.auth == a.tag } }

    /** Sessions on the target an extent is shared through (deleting needs `force` then). */
    fun sessionsForExtent(e: IscsiExtent, d: IscsiData): List<IscsiSession> =
        d.lunOf(e)?.let { l -> d.target(l.target)?.let { d.sessionsFor(it) } }.orEmpty()

    val GLOBAL_FIELDS = setOf("basename", "isns_servers", "listen_port", "pool_avail_threshold", "alua")
    val PORTAL_FIELDS = setOf("listen", "comment")
    val INITIATOR_FIELDS = setOf("initiators", "comment")
    val AUTH_FIELDS = setOf("tag", "user", "secret", "peeruser", "peersecret", "discovery_auth")
    val TARGET_FIELDS = setOf("name", "alias", "mode", "portal", "initiator", "auth", "authmethod", "auth_networks")
    val EXTENT_FIELDS = setOf("name", "disk", "path", "filesize", "ro", "serial", "avail_threshold", "product_id", "blocksize", "comment")
    val EXTENT_ALIASES = mapOf("type" to "disk")
    val LUN_FIELDS = setOf("lunid", "extent", "target")
}
