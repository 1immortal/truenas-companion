package app.truenascompanion.data.api

import app.truenascompanion.data.model.IpmiLan
import app.truenascompanion.data.model.LagPort
import app.truenascompanion.data.model.LinkState
import app.truenascompanion.data.model.NetInterface
import app.truenascompanion.data.model.NetInterfaceType
import app.truenascompanion.data.model.NetworkGlobal
import app.truenascompanion.data.model.NetworkOverview
import app.truenascompanion.data.model.PendingNetworkChanges
import app.truenascompanion.data.model.StaticRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 1.9.0: Network settings, **view only**. Checked against the TrueNAS 25.10 middleware (`api/v25_10_0/
 * network_configuration.py`, `interface.py`, `static_route.py`, `dns.py`, `ipmi_lan.py`; `plugins/network.py`,
 * `plugins/network_/`, `plugins/ipmi_/lan.py`).
 *
 * Every call goes through [read], which only allows the methods in [READ_METHODS]; nothing here can create, update,
 * delete, commit, roll back or check in a network change (NetworkReadOnlyTest enforces it). Changing the network from
 * a phone can cut the NAS off from the network, so the app never offers it.
 *
 * Roles: `network.configuration.config` NETWORK_GENERAL_READ; `interface.query`, `staticroute.query`, `dns.query`
 * NETWORK_INTERFACE_READ; `ipmi.lan.query` IPMI_READ. `interface.has_pending_changes` and `interface.checkin_waiting`
 * only read in-memory state but need NETWORK_INTERFACE_WRITE, so a read-only account gets "unknown" for them.
 */
class NetworkApi(private val api: TrueNasApi) {

    companion object {
        /** The only methods this class may call. All of them are read-only on TrueNAS 25.10. */
        val READ_METHODS = setOf(
            "network.configuration.config",
            "interface.query",
            "staticroute.query",
            "dns.query",
            "interface.has_pending_changes",
            "interface.checkin_waiting",
            "ipmi.lan.query",
        )

        /** "1000Mb/s Twisted Pair" -> "1 Gb/s · Twisted Pair"; "Unknown …" or blank -> null. */
        fun speedText(activeMediaSubtype: String?): String? {
            val s = activeMediaSubtype?.trim().orEmpty()
            if (s.isEmpty() || s.startsWith("Unknown", ignoreCase = true)) return null
            val m = Regex("""^(\d+)\s*Mb/s\s*(.*)$""").find(s) ?: return s
            val mbps = m.groupValues[1].toLong()
            val port = m.groupValues[2].trim()
            val rate = when {
                mbps >= 1000 && mbps % 1000 == 0L -> "${mbps / 1000} Gb/s"
                mbps >= 1000 -> String.format(java.util.Locale.US, "%.1f Gb/s", mbps / 1000.0)
                else -> "$mbps Mb/s"
            }
            return if (port.isEmpty() || port.equals("Other", ignoreCase = true)) rate else "$rate · $port"
        }

        fun linkState(raw: String?): LinkState = when (raw?.removePrefix("LINK_STATE_")) {
            "UP" -> LinkState.UP
            "DOWN", "LOWERLAYERDOWN", "NOTPRESENT" -> LinkState.DOWN
            else -> LinkState.UNKNOWN
        }

        private fun alias(o: JsonObject): String? {
            val type = o.str("type")
            if (type != null && type != "INET" && type != "INET6") return null // "LINK" is the MAC address
            val addr = o.str("address")?.takeIf { it.isNotBlank() } ?: return null
            val mask = o["netmask"].prim()?.let { it.intOrNull?.toString() ?: it.contentOrNull }?.takeIf { it.isNotBlank() }
            return if (mask == null) addr else "$addr/$mask"
        }

        private fun strings(e: JsonElement?): List<String> = e.arr()?.mapNotNull { it.prim()?.contentOrNull?.takeIf(String::isNotBlank) } ?: emptyList()

        fun parseGlobal(o: JsonObject): NetworkGlobal {
            val state = o["state"].obj()
            val sa = o["service_announcement"].obj()
            fun ns(src: JsonObject?) = listOf("nameserver1", "nameserver2", "nameserver3").mapNotNull { src?.str(it)?.takeIf(String::isNotBlank) }
            return NetworkGlobal(
                hostname = o.str("hostname").orEmpty(),
                domain = o.str("domain").orEmpty(),
                additionalDomains = strings(o["domains"]),
                ipv4Gateway = o.str("ipv4gateway").orEmpty(),
                ipv6Gateway = o.str("ipv6gateway").orEmpty(),
                nameservers = ns(o),
                hosts = strings(o["hosts"]),
                httpProxy = o.str("httpproxy").orEmpty(),
                mdns = sa?.bool("mdns"), wsd = sa?.bool("wsd"), netbios = sa?.bool("netbios"),
                currentIpv4Gateway = state?.str("ipv4gateway").orEmpty(),
                currentIpv6Gateway = state?.str("ipv6gateway").orEmpty(),
                currentNameservers = ns(state),
                virtualHostname = o.str("hostname_virtual")?.takeIf { it.isNotBlank() },
            )
        }

        fun parseInterface(o: JsonObject): NetInterface? {
            val name = o.str("name") ?: o.str("id") ?: return null
            val st = o["state"].obj()
            val fake = o.bool("fake") == true
            return NetInterface(
                name = name,
                type = NetInterfaceType.parse(o.str("type")),
                description = o.str("description").orEmpty(),
                link = if (fake) LinkState.UNKNOWN else linkState(st?.str("link_state")),
                mtu = if (fake) null else st?.long("mtu")?.toInt(),
                configuredMtu = o.long("mtu")?.toInt(),
                dhcp = o.bool("ipv4_dhcp") == true,
                ipv6Auto = o.bool("ipv6_auto") == true,
                configuredAddresses = o["aliases"].arr()?.mapNotNull { it.obj()?.let(::alias) } ?: emptyList(),
                currentAddresses = st?.get("aliases").arr()?.mapNotNull { it.obj()?.let(::alias) } ?: emptyList(),
                mac = st?.str("link_address").orEmpty(),
                speed = speedText(st?.str("active_media_subtype")),
                lagProtocol = o.str("lag_protocol"),
                lagPorts = run {
                    val live = st?.get("ports").arr()?.mapNotNull { p -> p.obj()?.let { LagPort(it.str("name") ?: return@mapNotNull null, strings(it["flags"])) } }
                        ?.associateBy { it.name } ?: emptyMap()
                    val configured = strings(o["lag_ports"])
                    (configured.map { live[it] ?: LagPort(it) } + live.values.filter { it.name !in configured })
                },
                xmitHashPolicy = o.str("xmit_hash_policy"),
                lacpduRate = o.str("lacpdu_rate"),
                vlanParent = o.str("vlan_parent_interface"),
                vlanTag = o.long("vlan_tag")?.toInt(),
                vlanPcp = o.long("vlan_pcp")?.toInt(),
                bridgeMembers = strings(o["bridge_members"]),
                notPresent = fake,
            )
        }

        fun parseRoute(o: JsonObject): StaticRoute? = StaticRoute(
            id = o.long("id")?.toInt() ?: return null,
            destination = o.str("destination") ?: return null,
            gateway = o.str("gateway").orEmpty(),
            description = o.str("description").orEmpty(),
        )

        fun parseIpmi(o: JsonObject): IpmiLan? = IpmiLan(
            channel = o.long("channel")?.toInt() ?: o.long("id")?.toInt() ?: return null,
            ipSource = o.str("ip_address_source").orEmpty(),
            ipAddress = o.str("ip_address").orEmpty(),
            subnetMask = o.str("subnet_mask").orEmpty(),
            gateway = o.str("default_gateway_ip_address").orEmpty(),
            mac = o.str("mac_address").orEmpty(),
            vlanId = if (o.bool("vlan_id_enable") == true) o.long("vlan_id")?.toInt() else null,
        )

        /** Physical first, then aggregations, bridges, VLANs; by name within each. */
        private val typeOrder = listOf(NetInterfaceType.PHYSICAL, NetInterfaceType.LINK_AGGREGATION, NetInterfaceType.BRIDGE, NetInterfaceType.VLAN, NetInterfaceType.UNKNOWN)
        fun sortInterfaces(list: List<NetInterface>) = list.sortedWith(compareBy({ typeOrder.indexOf(it.type) }, { it.name }))
    }

    /** The single gate to the NAS: refuses anything that isn't a known read-only method. */
    private suspend fun read(method: String, vararg args: JsonElement): JsonElement {
        check(method in READ_METHODS) { "Network settings are view only: $method is not allowed" }
        return api.rpc(method, *args)
    }

    /** An optional part: null if the NAS refuses it (permissions, older version); cancellation still propagates. */
    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: TrueNasException.NotConnected) {
        throw e
    } catch (_: Throwable) {
        null
    }

    suspend fun global(): NetworkGlobal = parseGlobal(read("network.configuration.config").obj() ?: throw TrueNasException.Rpc(0, null, "The NAS sent no network configuration."))

    suspend fun interfaces(): List<NetInterface> = sortInterfaces(read("interface.query").arr()?.mapNotNull { it.obj()?.let(::parseInterface) } ?: emptyList())

    suspend fun staticRoutes(): List<StaticRoute> = read("staticroute.query").arr()?.mapNotNull { it.obj()?.let(::parseRoute) } ?: emptyList()

    suspend fun resolvers(): List<String> = read("dns.query").arr()?.mapNotNull { it.obj()?.str("nameserver") } ?: emptyList()

    suspend fun pending(): PendingNetworkChanges = PendingNetworkChanges(
        hasPending = optional { read("interface.has_pending_changes").prim()?.contentOrNull?.toBooleanStrictOrNull() },
        checkinSecondsLeft = optional { read("interface.checkin_waiting").prim()?.intOrNull },
    )

    /** IPMI (BMC) network settings; empty when the NAS has no IPMI or the account can't read it. Can take a few seconds. */
    suspend fun ipmi(): List<IpmiLan> = optional { read("ipmi.lan.query").arr()?.mapNotNull { it.obj()?.let(::parseIpmi) } } ?: emptyList()

    /** Everything except IPMI, fetched in parallel. */
    suspend fun overview(): NetworkOverview = coroutineScope {
        val g = async { global() }
        val i = async { interfaces() }
        val r = async { optional { staticRoutes() } }
        val d = async { optional { resolvers() } }
        val p = async { pending() }
        NetworkOverview(g.await(), i.await(), r.await(), d.await(), p.await())
    }
}
