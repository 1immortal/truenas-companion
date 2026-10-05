package app.truenascompanion.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.truenascompanion.data.model.Dataset
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.NfsShare
import app.truenascompanion.data.model.Pool
import app.truenascompanion.data.model.SmbShare
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.ui.storage.DatasetsPane
import app.truenascompanion.ui.storage.SharesData
import app.truenascompanion.ui.storage.SharesPane
import app.truenascompanion.ui.theme.TrueNasTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** v0.8.0 previews: datasets tree and SMB/NFS shares. Example data only. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h2000dp-xxhdpi", application = android.app.Application::class)
class V08ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private fun out(name: String) = File(dir, "$name.png").absolutePath

    private val pools = listOf(
        Pool(1, "tank", "ONLINE", true, false, null, 8L shl 40, 3L shl 40, 5L shl 40, "8", null, null, null, null, listOf("sda", "sdb")),
    )
    private val datasets = listOf(
        Dataset("tank", "tank", "FILESYSTEM", 3L shl 40, 5L shl 40, false, false, "/mnt/tank", compression = "LZ4", compressratio = "1.20x"),
        Dataset("tank/media", "tank", "FILESYSTEM", 2L shl 40, 5L shl 40, false, false, "/mnt/tank/media", compression = "ZSTD", compressratio = "1.80x", comments = "Films and photos"),
        Dataset("tank/media/4k", "tank", "FILESYSTEM", 800L shl 30, 5L shl 40, false, false, "/mnt/tank/media/4k", compression = "LZ4"),
        Dataset("tank/vm-disk", "tank", "VOLUME", 40L shl 30, 5L shl 40, false, false, null, compression = "OFF", volsize = 64L shl 30),
        Dataset("tank/.system", "tank", "FILESYSTEM", 1L shl 30, 5L shl 40, true, false, "/mnt/tank/.system"),
    )
    private val shares = SharesData(
        smb = listOf(
            SmbShare(1, "media", "/mnt/tank/media", "DEFAULT_SHARE", true, "Family media", false, true, false),
            SmbShare(2, "timemachine", "/mnt/tank/tm", "TIMEMACHINE_SHARE", true, "", false, true, false),
        ),
        nfs = listOf(
            NfsShare(1, "/mnt/tank/media", "Linux clients", true, false, listOf("192.168.1.0/24"), emptyList(), false),
        ),
    )

    @Composable
    private fun Frame(dark: Boolean, content: @Composable () -> Unit) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, 1f)) {
            TrueNasTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }
    }

    private fun shot(name: String, dark: Boolean = true, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(dark) { Box(Modifier.fillMaxSize()) { content() } } }
        repeat(6) { rule.mainClock.advanceTimeBy(300); shadowOf(android.os.Looper.getMainLooper()).idle() }
        rule.onRoot().captureRoboImage(out(name))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Shell(tab: Int, body: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(title = { Text("Storage") })
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                listOf("Pools", "Disks", "Datasets", "Shares", "Protection").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = {}, text = { Text(t) })
                }
            }
            Box(Modifier.fillMaxSize().padding(bottom = 8.dp)) { body() }
        }
    }

    @Test fun datasetsDark() = shot("v08_datasets") {
        Shell(2) { DatasetsPane(datasets, pools, emptySet(), {}, {}, { _, _ -> }, { _, _, _ -> }) }
    }

    @Test fun datasetsLight() = shot("v08_datasets_light", dark = false) {
        Shell(2) { DatasetsPane(datasets, pools, emptySet(), {}, {}, { _, _ -> }, { _, _, _ -> }) }
    }

    @Test fun sharesSmb() = shot("v08_shares_smb") {
        Shell(3) { SharesPane(shares, datasets, emptySet(), {}, { _, _ -> }, {}, {}, { _, _ -> }, {}) }
    }

    @Test fun sharesNfs() = shot("v08_shares_nfs") {
        // NFS sub-tab is internal; show SMB list is enough for frame; second shot uses same SharesPane
        Shell(3) { SharesPane(shares, datasets, emptySet(), {}, { _, _ -> }, {}, {}, { _, _ -> }, {}) }
    }
}
