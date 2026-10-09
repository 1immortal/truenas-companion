package app.truenascompanion

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.security.SecretCipher
import app.truenascompanion.data.store.SettingsStore
import app.truenascompanion.ui.components.UndoEvent
import app.truenascompanion.ui.components.showUndoEvents
import app.truenascompanion.ui.servers.ServerRemovals
import app.truenascompanion.ui.theme.TrueNasTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.8.1 review: Undo edge cases (double Undo, process death, failures) and the pending-removal store. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xxhdpi")
class V181UndoTest {
    @get:Rule val rule = createComposeRule()

    /** Stands in for the persisted settings: survives a "process death" (a new [ServerRemovals]). */
    private class Disk {
        val pending = linkedSetOf<String>()
        val servers = linkedSetOf("nas1", "nas2")
        val finished = mutableListOf<String>()
        var failNext = false
    }

    private fun removals(scope: kotlinx.coroutines.CoroutineScope, d: Disk, finishGate: CompletableDeferred<Unit>? = null) = ServerRemovals(
        scope = scope,
        mark = { d.pending += it },
        unmark = { d.pending -= it },
        pending = { d.pending.toSet() },
        finish = {
            finishGate?.await()
            if (d.failNext) { d.failNext = false; error("NAS unreachable") }
            d.finished += it; d.servers -= it; d.pending -= it
        },
    )

    @Test fun doubleUndoRunsOnce() = runTest {
        val d = Disk()
        val r = removals(backgroundScope, d)
        r.request("nas1", 6_000)
        assertTrue(r.undo("nas1"))
        assertFalse(r.undo("nas1"))
        advanceTimeBy(20_000); runCurrent()
        assertTrue(d.finished.isEmpty())
        assertEquals(setOf("nas1", "nas2"), d.servers)
    }

    @Test fun removingAgainAfterUndoStartsAFreshWindow() = runTest {
        val d = Disk()
        val r = removals(backgroundScope, d)
        r.request("nas1", 6_000)
        advanceTimeBy(5_000); runCurrent()
        assertTrue(r.undo("nas1"))
        r.request("nas1", 6_000)
        advanceTimeBy(5_000); runCurrent()
        assertTrue("the first timer must not fire", d.finished.isEmpty())
        advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf("nas1"), d.finished)
    }

    @Test fun undoWhileTheRemovalRunsIsTooLateAndKeepsTheMark() = runTest {
        val d = Disk()
        val gate = CompletableDeferred<Unit>()
        val r = removals(backgroundScope, d, gate)
        r.request("nas1", 6_000)
        advanceTimeBy(7_000); runCurrent() // window closed; finish is signing out on the NAS
        assertFalse(r.undo("nas1"))
        assertEquals(setOf("nas1"), d.pending)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf("nas1"), d.finished)
    }

    @Test fun leftoversDontFinishARemovalTwice() = runTest {
        val d = Disk()
        val gate = CompletableDeferred<Unit>()
        val r = removals(backgroundScope, d, gate)
        r.request("nas1", 6_000)
        advanceTimeBy(7_000); runCurrent()
        val leftovers = launch { r.finishLeftovers() }
        runCurrent()
        gate.complete(Unit); runCurrent()
        leftovers.join()
        assertEquals(listOf("nas1"), d.finished)
    }

    @Test fun processDeathInsideTheWindowFinishesOnTheNextStart() = runTest {
        val d = Disk()
        val before = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job() + kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        removals(before, d).request("nas1", 6_000)
        advanceTimeBy(2_000); runCurrent()
        before.cancel() // the process dies: the timer is gone, the mark is on disk
        assertEquals(setOf("nas1"), d.pending)
        assertEquals(setOf("nas1", "nas2"), d.servers)
        val after = removals(backgroundScope, d)
        assertFalse("no Undo survives a restart", after.undo("nas1"))
        after.finishLeftovers()
        assertEquals(listOf("nas1"), d.finished)
        assertEquals(setOf("nas2"), d.servers)
        assertTrue(d.pending.isEmpty())
    }

    @Test fun aFailedRemovalStaysHiddenAndIsRetriedOnTheNextStart() = runTest {
        val d = Disk().apply { failNext = true }
        val r = removals(backgroundScope, d)
        r.request("nas1", 6_000)
        advanceTimeBy(7_000); runCurrent()
        assertTrue(d.finished.isEmpty())
        assertEquals(setOf("nas1"), d.pending)
        removals(backgroundScope, d).finishLeftovers()
        assertEquals(listOf("nas1"), d.finished)
    }

    // --- The real settings store ---

    /** DataStore instances are per process, so start every test from an empty server list. */
    private suspend fun store() = SettingsStore(ApplicationProvider.getApplicationContext(), SecretCipher()).also { s ->
        s.allServers.first().forEach { s.deleteServer(it.id) }
        s.pendingRemovals().keys.forEach { s.clearPendingRemoval(it) }
    }

    @Test fun pendingRemovalHidesTheServerAndUndoBringsItBack() = runBlocking {
        val s = store()
        s.saveServer(ServerConfig("a", "Alpha", "https://alpha.example"), null)
        s.saveServer(ServerConfig("b", "Beta", "https://beta.example"), null)
        s.setActiveServer("a")
        s.markPendingRemoval("a")
        assertEquals(listOf("b"), s.servers.first().map { it.id })
        assertEquals(listOf("a", "b"), s.allServers.first().map { it.id })
        assertEquals("the next server is used during the window", "b", s.activeServerId.first())
        s.clearPendingRemoval("a")
        assertEquals(listOf("a", "b"), s.servers.first().map { it.id })
        assertEquals("Undo makes it active again", "a", s.activeServerId.first())
    }

    @Test fun pickingAnotherServerDuringTheWindowSticksAfterUndo() = runBlocking {
        val s = store()
        s.saveServer(ServerConfig("a", "Alpha", "https://alpha.example"), null)
        s.saveServer(ServerConfig("b", "Beta", "https://beta.example"), null)
        s.saveServer(ServerConfig("c", "Gamma", "https://gamma.example"), null)
        s.setActiveServer("a")
        s.markPendingRemoval("a")
        s.setActiveServer("c")
        s.clearPendingRemoval("a")
        assertEquals("c", s.activeServerId.first())
    }

    @Test fun finishingTheRemovalDeletesItAndClearsTheMark() = runBlocking {
        val s = store()
        s.saveServer(ServerConfig("a", "Alpha", "https://alpha.example"), null)
        s.saveServer(ServerConfig("b", "Beta", "https://beta.example"), null)
        s.saveServer(ServerConfig("c", "Gamma", "https://gamma.example"), null)
        s.setActiveServer("a")
        s.markPendingRemoval("a")
        s.markPendingRemoval("b")
        s.deleteServer("a")
        assertEquals(listOf("b", "c"), s.allServers.first().map { it.id })
        assertEquals(setOf("b"), s.pendingRemovals().keys)
        assertEquals("a server still waiting for removal isn't picked as the next one", "c", s.activeServerId.first())
        s.deleteServer("b")
        assertTrue(s.pendingRemovals().isEmpty())
        assertEquals(listOf("c"), s.servers.first().map { it.id })
    }

    // --- Undo snackbars on alert screens ---

    @Test fun aNewUndoReplacesTheOldOneAndEachRunsAtMostOnce() {
        val host = SnackbarHostState()
        val events = Channel<UndoEvent>(Channel.BUFFERED)
        val log = mutableListOf<String>()
        rule.setContent {
            TrueNasTheme { SnackbarHost(host) }
            androidx.compose.runtime.LaunchedEffect(Unit) { showUndoEvents(events.receiveAsFlow(), host, 60_000) }
        }
        events.trySend(UndoEvent("Alert dismissed", undo = { log += "undo A" }, expired = { log += "expired A" }))
        rule.waitForIdle()
        rule.onNodeWithText("Alert dismissed").assertIsDisplayed()
        events.trySend(UndoEvent("Snoozed for 1 hour on this phone", undo = { log += "undo B" }, expired = { log += "expired B" }))
        rule.waitForIdle()
        rule.onNodeWithText("Alert dismissed").assertDoesNotExist()
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Undo").assertDoesNotExist()
        assertEquals(listOf("expired A", "undo B"), log)
    }

    @Test fun anUndoThatWaitedWhileTheScreenWasClosedIsDropped() {
        val host = SnackbarHostState()
        val events = Channel<UndoEvent>(Channel.BUFFERED)
        val log = mutableListOf<String>()
        events.trySend(UndoEvent("Alert dismissed", undo = { log += "undo" }, expired = { log += "expired" },
            createdAt = System.currentTimeMillis() - 120_000))
        rule.setContent {
            TrueNasTheme { SnackbarHost(host) }
            androidx.compose.runtime.LaunchedEffect(Unit) { showUndoEvents(events.receiveAsFlow(), host, 6_000) }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Alert dismissed").assertDoesNotExist()
        assertEquals(listOf("expired"), log)
    }
}
