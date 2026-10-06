package app.truenascompanion.data.vpn

import java.net.InetAddress
import java.util.Base64

/** What the user sees about an imported tunnel (no secrets). */
data class WgSummary(
    val endpoint: String,
    val addresses: List<String>,
    val allowedIps: List<String>,
    val dns: List<String>,
    val peerPublicKey: String,
    val peers: Int,
) {
    /** The peer routes everything (0.0.0.0/0), which the app narrows to the NAS only. */
    val fullTunnel: Boolean get() = allowedIps.any { it.endsWith("/0") }
}

/**
 * WireGuard `.conf` handling in plain Kotlin (unit tested without Android): validation for the import screen and the
 * split-tunnel rewrite used when the app brings its tunnel up.
 *
 * Split tunnel: the saved config stays exactly as imported. At bring-up [forApp] derives the config that is actually
 * used: only this app is allowed into the tunnel (`IncludedApplications`), and only the NAS's LAN IP is routed through
 * it (AllowedIPs = `<nas>/32`), so no other app and no other destination is ever affected, even if the imported
 * config says `AllowedIPs = 0.0.0.0/0`. DNS servers are dropped because the app connects by IP.
 */
object WgConf {
    class Invalid(message: String) : Exception(message)

    private data class Section(val name: String, val entries: List<Pair<String, String>>) {
        fun all(key: String) = entries.filter { it.first.equals(key, true) }.flatMap { e -> e.second.split(',').map { it.trim() }.filter { it.isNotEmpty() } }
        fun one(key: String) = entries.lastOrNull { it.first.equals(key, true) }?.second?.trim()?.ifEmpty { null }
    }

    /** Quick check for scanned QR codes / shared text before the full validation. */
    fun looksLikeConfig(text: String): Boolean = text.contains("[Interface]", true) && text.contains("[Peer]", true)

    private fun sections(text: String): List<Section> {
        val out = mutableListOf<Section>()
        var name: String? = null
        var entries = mutableListOf<Pair<String, String>>()
        for (raw in text.replace("\r", "").lines()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                name?.let { out += Section(it, entries) }
                name = line.substring(1, line.length - 1).trim()
                entries = mutableListOf()
                continue
            }
            val eq = line.indexOf('=')
            if (name == null || eq <= 0) throw Invalid("This doesn't look like a WireGuard config (line \"${line.take(40)}\").")
            entries += line.substring(0, eq).trim() to line.substring(eq + 1).trim()
        }
        name?.let { out += Section(it, entries) }
        return out
    }

    private fun isKey(s: String?): Boolean =
        s != null && s.length == 44 && runCatching { Base64.getDecoder().decode(s).size == 32 }.getOrDefault(false)

    /** Validates an imported config and describes it. Throws [Invalid] with a message for the user. */
    fun validate(text: String): WgSummary {
        if (text.length > 16_384) throw Invalid("That's too long for a WireGuard config.")
        val secs = sections(text)
        val ifaces = secs.filter { it.name.equals("Interface", true) }
        val peers = secs.filter { it.name.equals("Peer", true) }
        secs.firstOrNull { !it.name.equals("Interface", true) && !it.name.equals("Peer", true) }?.let {
            throw Invalid("Unknown section [${it.name}] in the config.")
        }
        if (ifaces.size != 1) throw Invalid("The config needs exactly one [Interface] section.")
        if (peers.isEmpty()) throw Invalid("The config has no [Peer] (the server).")
        val iface = ifaces.single()
        if (!isKey(iface.one("PrivateKey"))) throw Invalid("The [Interface] has no valid PrivateKey.")
        val addresses = iface.all("Address")
        if (addresses.isEmpty() || addresses.any { cidr(it) == null }) throw Invalid("The [Interface] needs a valid Address, e.g. 10.8.0.2/24.")
        peers.forEachIndexed { i, p ->
            if (!isKey(p.one("PublicKey"))) throw Invalid("Peer ${i + 1} has no valid PublicKey.")
            p.one("PresharedKey")?.let { if (!isKey(it)) throw Invalid("Peer ${i + 1} has an invalid PresharedKey.") }
            if (p.all("AllowedIPs").any { cidr(it) == null }) throw Invalid("Peer ${i + 1} has an invalid AllowedIPs entry.")
        }
        val main = peers.first()
        val endpoint = main.one("Endpoint") ?: throw Invalid("The [Peer] has no Endpoint (your home address and port, e.g. myhome.example.org:51820).")
        if (endpointPort(endpoint) == null) throw Invalid("The Endpoint \"$endpoint\" needs a port, e.g. myhome.example.org:51820.")
        return WgSummary(
            endpoint = endpoint,
            addresses = addresses,
            allowedIps = peers.flatMap { it.all("AllowedIPs") },
            dns = iface.all("DNS"),
            peerPublicKey = main.one("PublicKey")!!,
            peers = peers.size,
        )
    }

    /** `host:port` or `[v6]:port` -> port. */
    fun endpointPort(endpoint: String): Int? = endpoint.substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 1..65535 }

    fun endpointHost(endpoint: String): String = endpoint.substringBeforeLast(':').removePrefix("[").removeSuffix("]")

    /**
     * The config the app actually runs (see class docs). [nasIp] must be covered by a peer's AllowedIPs, otherwise the
     * WireGuard server wouldn't forward to it and [Invalid] explains that.
     */
    fun forApp(text: String, nasIp: String, packageName: String): String {
        validate(text)
        val secs = sections(text)
        val iface = secs.first { it.name.equals("Interface", true) }
        val peer = secs.filter { it.name.equals("Peer", true) }.firstOrNull { p -> p.all("AllowedIPs").any { covers(it, nasIp) } }
            ?: throw Invalid(
                "This tunnel doesn't lead to $nasIp (its AllowedIPs are ${secs.filter { it.name.equals("Peer", true) }.flatMap { it.all("AllowedIPs") }.joinToString().ifEmpty { "empty" }}). " +
                    "Allow the NAS's IP in your WireGuard server, or check the local address.",
            )
        val keepIface = setOf("privatekey", "address", "mtu", "listenport")
        val keepPeer = setOf("publickey", "presharedkey", "endpoint", "persistentkeepalive")
        return buildString {
            appendLine("[Interface]")
            iface.entries.filter { it.first.lowercase() in keepIface }.forEach { appendLine("${it.first} = ${it.second}") }
            appendLine("IncludedApplications = $packageName")
            appendLine()
            appendLine("[Peer]")
            peer.entries.filter { it.first.lowercase() in keepPeer }.forEach { appendLine("${it.first} = ${it.second}") }
            appendLine("AllowedIPs = $nasIp/32")
        }
    }

    private fun cidr(s: String): Pair<ByteArray, Int>? {
        val addr = s.substringBefore('/').trim()
        if (addr.isEmpty() || !(addr.contains(':') || addr.all { it.isDigit() || it == '.' })) return null // literals only, no DNS
        val bytes = runCatching { InetAddress.getByName(addr).address }.getOrNull() ?: return null
        val bits = if (s.contains('/')) s.substringAfter('/').trim().toIntOrNull() ?: return null else bytes.size * 8
        if (bits !in 0..bytes.size * 8) return null
        return bytes to bits
    }

    /** True when [ip] lies inside [network] (e.g. `0.0.0.0/0` or `192.168.1.0/24` covers `192.168.1.10`). */
    fun covers(network: String, ip: String): Boolean {
        val (net, bits) = cidr(network) ?: return false
        val (addr, _) = cidr(ip) ?: return false
        if (net.size != addr.size) return false
        for (i in 0 until bits) {
            val mask = 0x80 ushr (i % 8)
            if ((net[i / 8].toInt() and mask) != (addr[i / 8].toInt() and mask)) return false
        }
        return true
    }

    /** `192.168.1.10/24` -> `192.168.1.0/24` (for "advertise this subnet" in the Tailscale setup). */
    fun networkOf(cidrText: String): String? {
        val (bytes, bits) = cidr(cidrText) ?: return null
        if (bytes.size != 4) return null
        val masked = bytes.mapIndexed { i, b ->
            val keep = (bits - i * 8).coerceIn(0, 8)
            (b.toInt() and (0xFF shl (8 - keep)) and 0xFF)
        }
        return masked.joinToString(".") + "/$bits"
    }
}
