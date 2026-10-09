package app.truenascompanion

import app.truenascompanion.data.model.InterfaceRate
import app.truenascompanion.data.model.IpmiLan
import app.truenascompanion.data.model.LagPort
import app.truenascompanion.data.model.LinkState
import app.truenascompanion.data.model.NetInterface
import app.truenascompanion.data.model.NetInterfaceType
import app.truenascompanion.data.model.NetworkGlobal
import app.truenascompanion.data.model.NetworkOverview
import app.truenascompanion.data.model.PendingNetworkChanges
import app.truenascompanion.data.model.StaticRoute

/**
 * 1.9.0: TrueNAS 25.10 network answers shaped like the middleware's API models (`api/v25_10_0/network_configuration.py`,
 * `interface.py`, `static_route.py`, `dns.py`, `ipmi_lan.py`). Example data only: documentation addresses
 * (192.0.2.0/24, 198.51.100.0/24, 2001:db8::/32) and example.com names.
 */
object NetworkSamples {
    const val CONFIG = """{
      "id": 1, "hostname": "truenas", "domain": "home.example.com", "ipv4gateway": "192.0.2.1", "ipv6gateway": "",
      "nameserver1": "192.0.2.53", "nameserver2": "198.51.100.53", "nameserver3": "",
      "httpproxy": "", "hosts": ["192.0.2.20 printer.home.example.com"], "domains": ["lab.example.com"],
      "service_announcement": {"netbios": false, "mdns": true, "wsd": true},
      "activity": {"type": "DENY", "activities": []}, "hostname_local": "truenas", "hostname_virtual": null,
      "state": {"ipv4gateway": "192.0.2.1", "ipv6gateway": "fe80::1", "nameserver1": "192.0.2.53",
                "nameserver2": "198.51.100.53", "nameserver3": "", "hosts": ["192.0.2.20 printer.home.example.com"]}
    }"""

    private fun state(name: String, link: String, mtu: Int, mac: String, media: String, aliases: String, extra: String = "") = """
      {"name": "$name", "orig_name": "$name", "description": "", "mtu": $mtu, "cloned": false, "flags": ["UP"],
       "nd6_flags": [], "capabilities": [], "link_state": "$link", "media_type": "Ethernet", "media_subtype": "autoselect",
       "active_media_type": "Ethernet", "active_media_subtype": "$media", "supported_media": [], "media_options": null,
       "link_address": "$mac", "permanent_link_address": null, "hardware_link_address": "$mac",
       "aliases": $aliases, "vrrp_config": [] $extra}"""

    val INTERFACES = """[
      {"id": "enp1s0", "name": "enp1s0", "fake": false, "type": "PHYSICAL",
       "state": ${state("enp1s0", "LINK_STATE_UP", 1500, "02:00:00:00:00:01", "1000Mb/s Twisted Pair",
           """[{"type": "LINK", "address": "02:00:00:00:00:01"}, {"type": "INET", "address": "192.0.2.10", "netmask": 24, "broadcast": "192.0.2.255"}, {"type": "INET6", "address": "2001:db8::10", "netmask": 64}]""")},
       "aliases": [], "ipv4_dhcp": true, "ipv6_auto": true, "description": "Onboard", "mtu": null},
      {"id": "bond0", "name": "bond0", "fake": false, "type": "LINK_AGGREGATION",
       "state": ${state("bond0", "LINK_STATE_UP", 9000, "02:00:00:00:00:02", "Unknown Other",
           """[{"type": "INET", "address": "198.51.100.10", "netmask": 24}]""",
           """, "protocol": "LACP", "ports": [{"name": "enp2s0", "flags": ["ACTIVE", "AGGREGATING"]}, {"name": "enp3s0", "flags": []}], "xmit_hash_policy": "LAYER2+3", "lacpdu_rate": "SLOW" """)},
       "aliases": [{"type": "INET", "address": "198.51.100.10", "netmask": 24}], "ipv4_dhcp": false, "ipv6_auto": false,
       "description": "Storage", "mtu": 9000, "lag_protocol": "LACP", "lag_ports": ["enp2s0", "enp3s0"],
       "xmit_hash_policy": "LAYER2+3", "lacpdu_rate": "SLOW"},
      {"id": "vlan20", "name": "vlan20", "fake": false, "type": "VLAN",
       "state": ${state("vlan20", "LINK_STATE_LOWERLAYERDOWN", 1500, "02:00:00:00:00:01", "",
           """[]""", """, "parent": "enp1s0", "tag": 20, "pcp": null """)},
       "aliases": [], "ipv4_dhcp": false, "ipv6_auto": false, "description": "", "mtu": null,
       "vlan_parent_interface": "enp1s0", "vlan_tag": 20, "vlan_pcp": null},
      {"id": "br0", "name": "br0", "fake": true, "type": "BRIDGE",
       "state": ${state("br0", "", 1500, "", "", "[]")},
       "aliases": [{"type": "INET", "address": "192.0.2.30", "netmask": 24}], "ipv4_dhcp": false, "ipv6_auto": false,
       "description": "VMs", "mtu": null, "bridge_members": ["enp4s0"], "enable_learning": true}
    ]"""

    const val ROUTES = """[{"id": 1, "destination": "203.0.113.0/24", "gateway": "192.0.2.254", "description": "Lab network"}]"""
    const val DNS = """[{"nameserver": "192.0.2.53"}, {"nameserver": "198.51.100.53"}]"""
    const val IPMI = """[{"channel": 1, "id": 1, "ip_address_source": "static", "ip_address": "192.0.2.40",
      "mac_address": "02:00:00:00:00:40", "subnet_mask": "255.255.255.0", "default_gateway_ip_address": "192.0.2.1",
      "default_gateway_mac_address": "00:00:00:00:00:00", "backup_gateway_ip_address": "0.0.0.0",
      "backup_gateway_mac_address": "00:00:00:00:00:00", "vlan_id": null, "vlan_id_enable": false, "vlan_priority": 0}]"""

    /** Ready-made example model for previews and UI tests. */
    val GLOBAL = NetworkGlobal(
        hostname = "truenas", domain = "home.example.com", additionalDomains = listOf("lab.example.com"),
        ipv4Gateway = "192.0.2.1", currentIpv4Gateway = "192.0.2.1", currentIpv6Gateway = "fe80::1",
        nameservers = listOf("192.0.2.53", "198.51.100.53"), currentNameservers = listOf("192.0.2.53", "198.51.100.53"),
        hosts = listOf("192.0.2.20 printer.home.example.com"), mdns = true, wsd = true, netbios = false,
    )
    val ETH = NetInterface(
        "enp1s0", NetInterfaceType.PHYSICAL, "Onboard", LinkState.UP, 1500, null, dhcp = true, ipv6Auto = true,
        currentAddresses = listOf("192.0.2.10/24", "2001:db8::10/64"), mac = "02:00:00:00:00:01", speed = "1 Gb/s · Twisted Pair",
    )
    val BOND = NetInterface(
        "bond0", NetInterfaceType.LINK_AGGREGATION, "Storage", LinkState.UP, 9000, 9000,
        configuredAddresses = listOf("198.51.100.10/24"), currentAddresses = listOf("198.51.100.10/24"), mac = "02:00:00:00:00:02",
        speed = "20 Gb/s", lagProtocol = "LACP", lagPorts = listOf(LagPort("enp2s0", listOf("ACTIVE", "AGGREGATING")), LagPort("enp3s0", listOf("ACTIVE", "AGGREGATING"))),
        xmitHashPolicy = "LAYER2+3", lacpduRate = "SLOW",
    )
    val VLAN = NetInterface("vlan20", NetInterfaceType.VLAN, "Cameras", LinkState.DOWN, 1500, vlanParent = "enp1s0", vlanTag = 20)
    val OVERVIEW = NetworkOverview(
        GLOBAL, listOf(ETH, BOND, VLAN), listOf(StaticRoute(1, "203.0.113.0/24", "192.0.2.254", "Lab network")),
        listOf("192.0.2.53", "198.51.100.53"), PendingNetworkChanges(false, null),
    )
    val RATES = mapOf(
        "enp1s0" to InterfaceRate(true, 1_250_000.0, 310_000.0, 1000.0),
        "bond0" to InterfaceRate(true, 48_200_000.0, 12_700_000.0, 20000.0),
    )
    val IPMI_LIST = listOf(IpmiLan(1, "static", "192.0.2.40", "255.255.255.0", "192.0.2.1", "02:00:00:00:00:40"))
}
