package app.truenascompanion

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.truenascompanion.data.api.NetworkApi
import app.truenascompanion.data.api.Parsers
import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.model.LinkState
import app.truenascompanion.data.model.NetInterfaceType
import app.truenascompanion.data.model.PendingNetworkChanges
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.network.InterfaceDetailContent
import app.truenascompanion.ui.network.LocalCopyValue
import app.truenascompanion.ui.network.NETWORK_WARNING_TAG
import app.truenascompanion.ui.network.NETWORK_WARNING_TEXT
import app.truenascompanion.ui.network.NetworkContent
import app.truenascompanion.ui.network.gatewayText
import app.truenascompanion.ui.network.mtuText
import app.truenascompanion.ui.network.pendingWarning
import app.truenascompanion.ui.system.HubGroup
import app.truenascompanion.ui.system.HubItem
import app.truenascompanion.ui.system.filterHub
import app.truenascompanion.ui.theme.TrueNasTheme
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation

/** 1.9.0: Network settings are view only: read-only API, parsing of TrueNAS 25.10 answers, the warning and the UI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class V190NetworkTest {
    @get:Rule val rule = createComposeRule()

    /** A fake NAS that answers the read methods with [NetworkSamples] and records every method the app calls. */
    private class RecordingNas(val failing: Set<String> = emptySet()) {
        val calls = mutableListOf<String>()
        val api: TrueNasApi = Proxy.newProxyInstance(TrueNasApi::class.java.classLoader, arrayOf(TrueNasApi::class.java), InvocationHandler { proxy, m, args ->
            when (m.name) {
                "rpc" -> {
                    val method = args[0] as String
                    synchronized(calls) { calls += method }
                    if (method in failing) throw TrueNasException.Rpc(13, "EACCES", "Not authorized")
                    answer(method)
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "RecordingNas"
                "close" -> Unit
                else -> throw UnsupportedOperationException("NetworkApi must only use rpc(): ${m.name}")
            }
        }) as TrueNasApi

        fun answer(method: String): JsonElement = when (method) {
            "network.configuration.config" -> Json.parseToJsonElement(NetworkSamples.CONFIG)
            "interface.query" -> Json.parseToJsonElement(NetworkSamples.INTERFACES)
            "staticroute.query" -> Json.parseToJsonElement(NetworkSamples.ROUTES)
            "dns.query" -> Json.parseToJsonElement(NetworkSamples.DNS)
            "interface.has_pending_changes" -> JsonPrimitive(true)
            "interface.checkin_waiting" -> JsonNull
            "ipmi.lan.query" -> Json.parseToJsonElement(NetworkSamples.IPMI)
            else -> JsonNull
        }
    }

    /** Anything that sounds like a change. `checkin_waiting` is a query; `checkin` itself is not. */
    private val writeLike = Regex("""\.(create|update|delete|do_\w+|commit|rollback|cancel_rollback|checkin|save_\w+|sync\w*|set_\w+|start|stop|restart|apply\w*|register\w*|lag_setup|websocket_\w+)$""")

    // --- Read only ---

    @Test fun theAllowListOnlyHoldsReadMethods() {
        for (m in NetworkApi.READ_METHODS) assertFalse("$m looks like a write", writeLike.containsMatchIn(m))
        assertTrue("interface.commit" !in NetworkApi.READ_METHODS)
        assertTrue("interface.rollback" !in NetworkApi.READ_METHODS)
        assertTrue("interface.checkin" !in NetworkApi.READ_METHODS)
    }

    /** Calls every public method of NetworkApi against the fake NAS; each RPC must be on the read-only allow list. */
    @Test fun everyNetworkApiMethodOnlyCallsReadMethods() = runBlocking {
        val nas = RecordingNas()
        val net = NetworkApi(nas.api)
        val suspendMethods = NetworkApi::class.java.declaredMethods.filter {
            java.lang.reflect.Modifier.isPublic(it.modifiers) && it.parameterTypes.size == 1 && it.parameterTypes[0] == Continuation::class.java
        }
        assertTrue("found the API methods", suspendMethods.size >= 7)
        for (m in suspendMethods) {
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { cont -> m.invoke(net, cont) }
        }
        assertTrue(nas.calls.isNotEmpty())
        for (c in nas.calls) {
            assertTrue("$c is not an allowed read method", c in NetworkApi.READ_METHODS)
            assertFalse("$c looks like a write", writeLike.containsMatchIn(c))
        }
        assertEquals("all allowed reads are used", NetworkApi.READ_METHODS, nas.calls.toSet())
    }

    /** Source check, like NoRestAuthTest: no method name other than the allowed reads appears in the network code. */
    @Test fun networkSourcesNameNoOtherMethods() {
        val roots = listOf(
            File("src/main/java/app/truenascompanion/data/api/NetworkApi.kt"),
            File("src/main/java/app/truenascompanion/ui/network"),
        )
        val files = roots.flatMap { r -> if (r.isDirectory) r.walk().filter { it.isFile && it.extension == "kt" }.toList() else listOf(r) }
        assertTrue("sources found from ${File(".").absolutePath}", files.size >= 3)
        val literal = Regex("\"([a-z_]+(?:\\.[a-z_0-9]+)+)\"")
        for (f in files) {
            val text = f.readText()
            for (m in literal.findAll(text).map { it.groupValues[1] }) {
                if (m.startsWith("app.") || m.startsWith("android.")) continue
                assertTrue("${f.name} names $m, which isn't an allowed read method", m in NetworkApi.READ_METHODS)
            }
            if (f.parentFile.name == "network") {
                assertFalse("${f.name} must not call the API directly", Regex("""\.(rpc|callJob)\(""").containsMatchIn(text))
            }
        }
    }

    /** Nowhere in the app is a network-changing method named (other features may read, e.g. `network.general.summary`). */
    @Test fun noNetworkWriteMethodAnywhereInTheApp() {
        val write = Regex("\"(interface|network\\.configuration|staticroute|ipmi\\.lan|network\\.general|route|dns)\\.(\\w+)\"")
        File("src/main/java").walk().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            write.findAll(f.readText()).forEach { m ->
                assertFalse("${f.name}: ${m.value} changes the network", writeLike.containsMatchIn(m.value.trim('"')))
            }
        }
    }

    @Test fun optionalPartsFailQuietly() = runBlocking {
        val nas = RecordingNas(failing = setOf("interface.has_pending_changes", "interface.checkin_waiting", "staticroute.query", "dns.query", "ipmi.lan.query"))
        val o = NetworkApi(nas.api).overview()
        assertTrue(o.pending.unknown)
        assertNull(o.staticRoutes)
        assertNull(o.resolvers)
        assertTrue(NetworkApi(nas.api).ipmi().isEmpty())
    }

    @Test fun theMainPartsStillFailLoudly() {
        val nas = RecordingNas(failing = setOf("interface.query"))
        try {
            runBlocking { NetworkApi(nas.api).overview() }
            fail("expected an error")
        } catch (e: Throwable) {
            val root = generateSequence(e) { it.cause }.last()
            assertTrue("$root", root is TrueNasException.Rpc)
        }
    }

    // --- Parsing (TrueNAS 25.10 shapes) ---

    @Test fun parsesTheGlobalConfiguration() = runBlocking {
        val o = NetworkApi(RecordingNas().api).overview()
        val g = o.global
        assertEquals("truenas.home.example.com", g.fqdn)
        assertEquals(listOf("lab.example.com"), g.additionalDomains)
        assertEquals(listOf("192.0.2.53", "198.51.100.53"), g.nameservers)
        assertEquals("fe80::1", g.currentIpv6Gateway)
        assertEquals(true, g.mdns); assertEquals(false, g.netbios)
        assertEquals(listOf("192.0.2.20 printer.home.example.com"), g.hosts)
        assertEquals(listOf("192.0.2.53", "198.51.100.53"), o.resolvers)
        assertEquals("Lab network", o.staticRoutes!!.single().description)
        assertEquals(PendingNetworkChanges(true, null), o.pending)
        assertEquals(1, NetworkApi(RecordingNas().api).ipmi().single().channel)
    }

    @Test fun parsesInterfacesOfEveryType() = runBlocking {
        val list = NetworkApi(RecordingNas().api).interfaces()
        assertEquals(listOf("enp1s0", "bond0", "br0", "vlan20"), list.map { it.name }) // physical, LAG, bridge, VLAN
        val eth = list[0]
        assertEquals(LinkState.UP, eth.link)
        assertEquals(listOf("192.0.2.10/24", "2001:db8::10/64"), eth.currentAddresses) // the MAC ("LINK") entry is left out
        assertEquals("02:00:00:00:00:01", eth.mac)
        assertEquals("1 Gb/s · Twisted Pair", eth.speed)
        assertTrue(eth.dhcp && eth.ipv6Auto)
        val bond = list[1]
        assertEquals(NetInterfaceType.LINK_AGGREGATION, bond.type)
        assertEquals("LACP", bond.lagProtocol)
        assertEquals(listOf("enp2s0", "enp3s0"), bond.lagPorts.map { it.name })
        assertEquals(listOf("ACTIVE", "AGGREGATING"), bond.lagPorts[0].flags)
        assertNull("Unknown speed is hidden", bond.speed)
        assertEquals(9000, bond.mtu)
        val br = list[2]
        assertTrue(br.notPresent)
        assertEquals(listOf("enp4s0"), br.bridgeMembers)
        assertEquals(listOf("192.0.2.30/24"), br.configuredAddresses)
        val vlan = list[3]
        assertEquals(LinkState.DOWN, vlan.link)
        assertEquals("enp1s0", vlan.vlanParent); assertEquals(20, vlan.vlanTag); assertNull(vlan.vlanPcp)
    }

    @Test fun speedAndLinkHelpers() {
        assertEquals("10 Gb/s · FIBRE", NetworkApi.speedText("10000Mb/s FIBRE"))
        assertEquals("2.5 Gb/s · Twisted Pair", NetworkApi.speedText("2500Mb/s Twisted Pair"))
        assertEquals("100 Mb/s", NetworkApi.speedText("100Mb/s Other"))
        assertNull(NetworkApi.speedText("Unknown Twisted Pair"))
        assertNull(NetworkApi.speedText(""))
        assertEquals(LinkState.UNKNOWN, NetworkApi.linkState("LINK_STATE_UNKNOWN"))
        assertEquals(LinkState.DOWN, NetworkApi.linkState("LINK_STATE_DOWN"))
    }

    @Test fun realtimeKeepsPerInterfaceTraffic() {
        val fields = Json.parseToJsonElement("""{"interfaces": {
            "enp1s0": {"link_state": "LINK_STATE_UP", "speed": 1000, "received_bytes_rate": 1250000.0, "sent_bytes_rate": 310000.0},
            "enp2s0": {"link_state": "LINK_STATE_DOWN", "speed": 0, "received_bytes": 0, "sent_bytes": 0, "received_bytes_rate": 0, "sent_bytes_rate": 0}}}""").jsonObject
        val s = Parsers.realtime(fields)
        assertEquals(1_250_000.0, s.interfaces.getValue("enp1s0").rxBytesPerSec, 0.1)
        assertEquals(1000.0, s.interfaces.getValue("enp1s0").speedMbps!!, 0.1)
        assertFalse(s.interfaces.getValue("enp2s0").linkUp)
        assertNull(s.interfaces.getValue("enp2s0").speedMbps)
        assertEquals("total still counts only links that are up", 1_250_000.0, s.netRxBytesPerSec!!, 0.1)
    }

    @Test fun wordingHelpers() {
        assertEquals("192.0.2.1", gatewayText("192.0.2.1", "192.0.2.1"))
        assertEquals("192.0.2.1 (from DHCP)", gatewayText("", "192.0.2.1"))
        assertEquals("192.0.2.1 (in use: 192.0.2.254)", gatewayText("192.0.2.1", "192.0.2.254"))
        assertEquals("Not set", gatewayText("", ""))
        assertEquals("Default (1500)", mtuText(NetworkSamples.VLAN.copy(mtu = null)))
        assertEquals("1500 (saved: 9000)", mtuText(NetworkSamples.ETH.copy(configuredMtu = 9000)))
        assertNull(pendingWarning(PendingNetworkChanges(false, null)))
        assertNull(pendingWarning(PendingNetworkChanges(null, null)))
        assertEquals("Unfinished network changes", pendingWarning(PendingNetworkChanges(true, null))!!.first)
        assertTrue(pendingWarning(PendingNetworkChanges(true, 42))!!.second.contains("42 s"))
    }

    // --- UI ---

    private fun show(state: UiState<app.truenascompanion.data.model.NetworkOverview>, onCopy: (String, String) -> Unit = { _, _ -> }) {
        rule.setContent {
            TrueNasTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalCopyValue provides onCopy) {
                    NetworkContent(state, NetworkSamples.IPMI_LIST, NetworkSamples.RATES, onRetry = {}, onBack = {}, onOpenInterface = {}, onReports = {})
                }
            }
        }
    }

    @Test fun theWarningIsAlwaysThereAndCantBeDismissed() {
        var state by androidx.compose.runtime.mutableStateOf<UiState<app.truenascompanion.data.model.NetworkOverview>>(UiState.Loading)
        rule.setContent { TrueNasTheme { NetworkContent(state, emptyList(), emptyMap(), onRetry = {}, onBack = {}, onOpenInterface = {}, onReports = {}) } }
        rule.onNodeWithTag(NETWORK_WARNING_TAG).assertIsDisplayed()
        state = UiState.Error("The NAS didn't answer in time.", TrueNasException.Timeout())
        rule.waitForIdle()
        rule.onNodeWithTag(NETWORK_WARNING_TAG).assertIsDisplayed()
        state = UiState.Success(NetworkSamples.OVERVIEW)
        rule.waitForIdle()
        val warning = rule.onNodeWithTag(NETWORK_WARNING_TAG).assertIsDisplayed().fetchSemanticsNode()
        // Not dismissible: no click or dismiss action, and TalkBack reads the whole warning as one item.
        assertNull(warning.config.getOrNull(SemanticsActions.OnClick))
        assertNull(warning.config.getOrNull(SemanticsActions.Dismiss))
        val said = warning.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()
        assertTrue(said, said.startsWith("Warning. View only.") && said.contains(NETWORK_WARNING_TEXT))
        assertTrue("read first", warning.config.getOrNull(SemanticsProperties.TraversalIndex)!! < 0f)
    }

    @Test fun nothingOnThePageIsEditable() {
        show(UiState.Success(NetworkSamples.OVERVIEW.copy(pending = PendingNetworkChanges(true, 45))))
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)).assertCountEquals(0)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertCountEquals(0)
        rule.onAllNodesWithText("Save", substring = false).assertCountEquals(0)
        rule.onAllNodesWithText("Edit", substring = false).assertCountEquals(0)
        rule.onNodeWithTag("network_pending").assertIsDisplayed()
        rule.onNodeWithText("Network changes are being tested").assertIsDisplayed()
    }

    @Test fun longPressCopiesAValue() {
        val copied = mutableListOf<Pair<String, String>>()
        show(UiState.Success(NetworkSamples.OVERVIEW)) { l, v -> copied += l to v }
        rule.onNodeWithTag("network_list").performScrollToNode(hasText("truenas.home.example.com"))
        rule.onNodeWithText("truenas.home.example.com").performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(listOf("Hostname" to "truenas.home.example.com"), copied)
    }

    @Test fun interfacesListAndDetail() {
        show(UiState.Success(NetworkSamples.OVERVIEW))
        rule.onNodeWithTag("network_list").performScrollToNode(hasTestTag("network_if_bond0"))
        rule.onNodeWithTag("network_if_bond0").assertIsDisplayed()
    }

    @Test fun lagDetailShowsMembers() {
        rule.setContent { TrueNasTheme { InterfaceDetailContent(NetworkSamples.BOND, NetworkSamples.RATES["bond0"], onBack = {}, onReports = {}) } }
        rule.onNodeWithTag(NETWORK_WARNING_TAG).assertIsDisplayed()
        rule.onNodeWithTag("network_detail").performScrollToNode(hasText("Link aggregation"))
        rule.onNodeWithText("LACP").assertExists()
        rule.onNodeWithText("enp2s0 (active, aggregating)", substring = true).assertExists()
    }

    @Test fun hubHasANetworkTileInServer() {
        assertTrue(HubItem.NETWORK in HubGroup.SERVER.items)
        assertTrue(filterHub("gateway").any { (_, items) -> HubItem.NETWORK in items })
        assertTrue(filterHub("vlan").any { (_, items) -> HubItem.NETWORK in items })
        assertEquals("View only", app.truenascompanion.ui.system.hubSubtitles(app.truenascompanion.ui.system.HubSummary())[HubItem.NETWORK]!!.text)
    }
}
