package app.truenascompanion

import app.truenascompanion.data.model.Health
import app.truenascompanion.widget.WidgetSnapshot
import app.truenascompanion.widget.WidgetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WidgetAndSigningTest {
    @Test fun widgetStoreRoundTrip() {
        val ctx = RuntimeEnvironment.getApplication()
        val snap = WidgetSnapshot("lab", Health.WARNING, "tank: DEGRADED", 2, "on LAN", null, 123L)
        WidgetStore.save(ctx, snap)
        val loaded = WidgetStore.load(ctx)
        assertEquals("lab", loaded.serverName)
        assertEquals(Health.WARNING, loaded.poolHealth)
        assertEquals(2, loaded.alertCount)
        assertEquals("on LAN", loaded.routeLabel)
    }

    @Test fun trustedSignerFingerprintsAreHex() {
        assertTrue(BuildConfig.RELEASE_SIGNER_SHA256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(BuildConfig.DEBUG_SIGNER_SHA256.startsWith("b613e16e"))
        assertEquals(64, BuildConfig.RELEASE_SIGNER_SHA256.length)
    }
}
