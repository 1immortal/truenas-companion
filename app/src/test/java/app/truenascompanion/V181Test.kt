package app.truenascompanion

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.api.restoreAlert
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import app.truenascompanion.data.model.Route
import app.truenascompanion.ui.components.EmptyContent
import app.truenascompanion.ui.components.ErrorKind
import app.truenascompanion.ui.components.StateContent
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.components.UndoTiming
import app.truenascompanion.ui.components.errorKindOf
import app.truenascompanion.ui.components.showUndo
import app.truenascompanion.ui.connection.ConnectionAnnouncer
import app.truenascompanion.ui.connection.ConnectionAnnouncer.Phase
import app.truenascompanion.ui.servers.ServerRemovals
import app.truenascompanion.ui.theme.TrueNasTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/** 1.8.1: Undo, the shared screen states, connection announcements. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xxhdpi")
class V181Test {
    @get:Rule val rule = createComposeRule()

    // --- Undo timing ---

    @Test fun undoLastsLongerWithAccessibilityOn() {
        assertEquals(6_000L, UndoTiming.timeoutMs(accessibilityOn = false, recommendedMs = null))
        assertEquals(12_000L, UndoTiming.timeoutMs(accessibilityOn = true, recommendedMs = null))
        // The user's "Time to take action" setting always wins when it's longer.
        assertEquals(30_000L, UndoTiming.timeoutMs(accessibilityOn = false, recommendedMs = 30_000L))
        assertEquals(30_000L, UndoTiming.timeoutMs(accessibilityOn = true, recommendedMs = 30_000L))
        assertEquals(6_000L, UndoTiming.timeoutMs(accessibilityOn = false, recommendedMs = 6_000L))
    }

    @Test fun undoSnackbarReportsTheUndoTap() {
        val host = SnackbarHostState()
        val result = CompletableDeferred<Boolean>()
        rule.setContent {
            val scope = rememberCoroutineScope()
            TrueNasTheme { SnackbarHost(host) }
            androidx.compose.runtime.LaunchedEffect(Unit) { scope.launch { result.complete(host.showUndo("Alert dismissed", 60_000)) } }
        }
        rule.onNodeWithText("Alert dismissed").assertIsDisplayed()
        rule.onNodeWithText("Undo").performClick()
        rule.waitUntil(5_000) { result.isCompleted }
        assertTrue(runBlocking { result.await() })
    }

    @Test fun undoSnackbarTimesOut() {
        val host = SnackbarHostState()
        val result = CompletableDeferred<Boolean>()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TrueNasTheme { SnackbarHost(host) }
            androidx.compose.runtime.LaunchedEffect(Unit) { result.complete(host.showUndo("Removed homenas", 6_000)) }
        }
        rule.mainClock.advanceTimeBy(7_000)
        rule.waitUntil(5_000) { result.isCompleted }
        assertFalse(runBlocking { result.await() })
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText("Removed homenas").assertDoesNotExist()
    }

    // --- Alert restore goes to the NAS ---

    @Test fun undoDismissCallsAlertRestore() = runBlocking {
        val calls = mutableListOf<Pair<String, List<JsonElement>>>()
        val api = Proxy.newProxyInstance(TrueNasApi::class.java.classLoader, arrayOf(TrueNasApi::class.java), InvocationHandler { proxy, m, args ->
            when {
                m.name == "rpc" -> { @Suppress("UNCHECKED_CAST") calls += (args[0] as String) to (args[1] as Array<JsonElement>).toList(); JsonNull }
                m.name == "hashCode" -> System.identityHashCode(proxy)
                else -> null
            }
        }) as TrueNasApi
        api.restoreAlert("a-1")
        assertEquals(listOf("alert.restore" to listOf<JsonElement>(JsonPrimitive("a-1"))), calls)
    }

    // --- Server removal with Undo ---

    private class FakeRemovalStore {
        val pending = linkedSetOf<String>()
        val finished = mutableListOf<String>()
    }

    private fun removals(scope: kotlinx.coroutines.CoroutineScope, s: FakeRemovalStore) = ServerRemovals(
        scope = scope,
        mark = { s.pending += it },
        unmark = { s.pending -= it },
        pending = { s.pending.toSet() },
        finish = { s.finished += it; s.pending -= it },
    )

    @Test fun removalWaitsForTheUndoWindow() = runTest {
        val s = FakeRemovalStore()
        val r = removals(backgroundScope, s)
        r.request("nas1", 6_000)
        assertEquals(setOf("nas1"), s.pending)
        advanceTimeBy(5_000); runCurrent()
        assertTrue("nothing deleted or signed out inside the window", s.finished.isEmpty())
        advanceTimeBy(1_500); runCurrent()
        assertEquals(listOf("nas1"), s.finished)
        assertTrue(s.pending.isEmpty())
    }

    @Test fun undoKeepsTheServer() = runTest {
        val s = FakeRemovalStore()
        val r = removals(backgroundScope, s)
        r.request("nas1", 6_000)
        advanceTimeBy(3_000); runCurrent()
        assertTrue(r.undo("nas1"))
        advanceTimeBy(10_000); runCurrent()
        assertTrue(s.finished.isEmpty())
        assertTrue(s.pending.isEmpty())
        assertFalse("a second Undo is a no-op", r.undo("nas1"))
    }

    @Test fun undoAfterTheWindowIsTooLate() = runTest {
        val s = FakeRemovalStore()
        val r = removals(backgroundScope, s)
        r.request("nas1", 6_000)
        advanceTimeBy(7_000); runCurrent()
        assertFalse(r.undo("nas1"))
        assertEquals(listOf("nas1"), s.finished)
    }

    @Test fun removalLeftOverFromAKilledProcessIsFinishedOnStart() = runTest {
        val s = FakeRemovalStore().apply { pending += "old" }
        val r = removals(backgroundScope, s)
        r.request("new", 6_000) // still inside its window: must not be finished early
        r.finishLeftovers()
        assertEquals(listOf("old"), s.finished)
        assertEquals(setOf("new"), s.pending)
    }

    // --- Shared screen states ---

    @Test fun stateContentShowsLoadingErrorEmptyAndData() {
        var state by androidx.compose.runtime.mutableStateOf<UiState<List<String>>>(UiState.Loading)
        var retries = 0
        rule.setContent {
            TrueNasTheme {
                StateContent(state, onRetry = { retries++ }, empty = EmptyContent(Icons.Rounded.Apps, "No apps installed", "Apps you install will appear here.")) { data ->
                    Text("Items: ${data.joinToString()}")
                }
            }
        }
        state = UiState.Error("The NAS didn't answer in time.", TrueNasException.Timeout())
        rule.waitForIdle()
        rule.onNodeWithText("Can't reach your NAS").assertIsDisplayed()
        rule.onNodeWithText("The NAS didn't answer in time.").assertIsDisplayed()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)).assertExists()
        rule.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
        state = UiState.Success(emptyList())
        rule.waitForIdle()
        rule.onNodeWithText("No apps installed").assertIsDisplayed()
        state = UiState.Success(listOf("plex", "immich"))
        rule.waitForIdle()
        rule.onNodeWithText("Items: plex, immich").assertIsDisplayed()
    }

    @Test fun errorKindsPickFriendlyTitles() {
        assertEquals(ErrorKind.NETWORK, errorKindOf(TrueNasException.Unreachable("x")))
        assertEquals(ErrorKind.NETWORK, errorKindOf(java.net.SocketTimeoutException()))
        assertEquals(ErrorKind.SIGN_IN, errorKindOf(TrueNasException.LoginRequired()))
        assertEquals(ErrorKind.OTHER, errorKindOf(TrueNasException.Rpc(22, "EINVAL", "Invalid")))
    }

    // --- Connection announcements ---

    @Test fun quickStartUpIsSilent() {
        val a = ConnectionAnnouncer()
        // Connecting was shorter than its settle time, so only Connected settles: nothing to say.
        assertNull(a.settle(Phase.CONNECTED, Route.LOCAL, "homenas", null))
    }

    @Test fun slowConnectAnnouncesConnectingThenTheRoute() {
        val a = ConnectionAnnouncer()
        assertEquals("Connecting…", a.settle(Phase.CONNECTING, null, "homenas", null))
        assertEquals("Connected to homenas via home address", a.settle(Phase.CONNECTED, Route.LOCAL, "homenas", null))
        // A route switch while connected is a change worth saying.
        assertEquals("Connected to homenas via Tailscale", a.settle(Phase.CONNECTED, Route.TAILSCALE, "homenas", null))
        assertNull(a.settle(Phase.CONNECTED, Route.TAILSCALE, "homenas", null))
    }

    @Test fun dropsAndFailuresAreAnnouncedOnce() {
        val a = ConnectionAnnouncer()
        a.settle(Phase.CONNECTING, null, "homenas", null)
        a.settle(Phase.CONNECTED, Route.REMOTE, "homenas", null)
        assertEquals("Connection lost. Reconnecting…", a.settle(Phase.CONNECTING, null, "homenas", null))
        assertEquals("Can't connect. Host not found.", a.settle(Phase.FAILED, null, "homenas", "Host not found."))
        // Automatic retries that fail the same way stay quiet; a different reason is spoken.
        assertNull(a.settle(Phase.CONNECTING, null, "homenas", null))
        assertNull(a.settle(Phase.FAILED, null, "homenas", "Host not found."))
        assertEquals("Can't connect. Connection refused.", a.settle(Phase.FAILED, null, "homenas", "Connection refused."))
        assertEquals("Connected to homenas via VPN", a.settle(Phase.CONNECTED, Route.VPN, "homenas", null))
        assertEquals("Connection lost. Timed out", a.settle(Phase.FAILED, null, "homenas", "Timed out"))
    }

    @Test fun goingToTheBackgroundIsSilent() {
        val a = ConnectionAnnouncer()
        a.settle(Phase.CONNECTING, null, "homenas", null)
        a.settle(Phase.CONNECTED, Route.LOCAL, "homenas", null)
        assertNull(a.settle(Phase.IDLE, null, "homenas", null))
        // Back in the app, a quick reconnect says nothing.
        assertNull(a.settle(Phase.CONNECTED, Route.LOCAL, "homenas", null))
    }
}
