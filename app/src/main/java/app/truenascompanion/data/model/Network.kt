package app.truenascompanion.data.model

/**
 * 1.9.0: Network settings, view only. Mirrors TrueNAS 25.10 `network.configuration.config`, `interface.query`,
 * `staticroute.query`, `dns.query`, `interface.has_pending_changes` / `checkin_waiting` and `ipmi.lan.query`.
 */
data class NetworkGlobal(
    val hostname: String,
    val domain: String,
    val additionalDomains: List<String> = emptyList(),
    /** Configured values (empty when unset; DHCP then provides them). */
    val ipv4Gateway: String = "",
    val ipv6Gateway: String = "",
    val nameservers: List<String> = emptyList(),
    val hosts: List<String> = emptyList(),
    val httpProxy: String = "",
    val mdns: Boolean? = null,
    val wsd: Boolean? = null,
    val netbios: Boolean? = null,
    /** What the NAS uses right now (`state`), e.g. a gateway or name servers from DHCP. */
    val currentIpv4Gateway: String = "",
    val currentIpv6Gateway: String = "",
    val currentNameservers: List<String> = emptyList(),
    /** HA systems only. */
    val virtualHostname: String? = null,
) {
    val fqdn: String get() = if (domain.isBlank()) hostname else "$hostname.$domain"
}

enum class NetInterfaceType(val label: String) {
    PHYSICAL("Physical"), BRIDGE("Bridge"), LINK_AGGREGATION("Link aggregation"), VLAN("VLAN"), UNKNOWN("Other");

    companion object {
        fun parse(s: String?): NetInterfaceType = entries.firstOrNull { it.name == s } ?: UNKNOWN
    }
}

enum class LinkState { UP, DOWN, UNKNOWN }

/** One LAG member with the flags the kernel reports for it (e.g. ACTIVE, COLLECTING, DISTRIBUTING). */
data class LagPort(val name: String, val flags: List<String> = emptyList())

data class NetInterface(
    val name: String,
    val type: NetInterfaceType,
    val description: String = "",
    val link: LinkState = LinkState.UNKNOWN,
    /** MTU in use right now. */
    val mtu: Int? = null,
    /** Configured MTU; null means the default (1500). */
    val configuredMtu: Int? = null,
    val dhcp: Boolean = false,
    val ipv6Auto: Boolean = false,
    /** Static addresses saved in the configuration, "address/prefix". */
    val configuredAddresses: List<String> = emptyList(),
    /** Addresses on the interface right now (`state.aliases`, without the MAC entry), "address/prefix". */
    val currentAddresses: List<String> = emptyList(),
    val mac: String = "",
    /** e.g. "1 Gb/s" or "10 Gb/s · Fibre", from `state.active_media_subtype`; null when unknown. */
    val speed: String? = null,
    val lagProtocol: String? = null,
    val lagPorts: List<LagPort> = emptyList(),
    val xmitHashPolicy: String? = null,
    val lacpduRate: String? = null,
    val vlanParent: String? = null,
    val vlanTag: Int? = null,
    val vlanPcp: Int? = null,
    val bridgeMembers: List<String> = emptyList(),
    /** Saved in the configuration but not present on the system (e.g. not applied yet). */
    val notPresent: Boolean = false,
)

data class StaticRoute(val id: Int, val destination: String, val gateway: String, val description: String = "")

/**
 * Unfinished network changes on the NAS. [hasPending] / [checkinSecondsLeft] are null when the NAS didn't say (the
 * account may lack the NETWORK_INTERFACE_WRITE role these read-only checks require).
 */
data class PendingNetworkChanges(val hasPending: Boolean?, val checkinSecondsLeft: Int?) {
    val waitingForCheckin: Boolean get() = (checkinSecondsLeft ?: 0) > 0
    val needsAttention: Boolean get() = hasPending == true || waitingForCheckin
    val unknown: Boolean get() = hasPending == null && checkinSecondsLeft == null
}

data class IpmiLan(
    val channel: Int,
    val ipSource: String = "",
    val ipAddress: String = "",
    val subnetMask: String = "",
    val gateway: String = "",
    val mac: String = "",
    val vlanId: Int? = null,
)

data class NetworkOverview(
    val global: NetworkGlobal,
    val interfaces: List<NetInterface>,
    /** null if the NAS didn't return them. */
    val staticRoutes: List<StaticRoute>? = emptyList(),
    /** Name servers in /etc/resolv.conf (`dns.query`); null if unavailable. */
    val resolvers: List<String>? = null,
    val pending: PendingNetworkChanges = PendingNetworkChanges(null, null),
)

/** Live per-interface numbers from `reporting.realtime` (`interfaces.<name>`). */
data class InterfaceRate(val linkUp: Boolean, val rxBytesPerSec: Double, val txBytesPerSec: Double, val speedMbps: Double? = null)
