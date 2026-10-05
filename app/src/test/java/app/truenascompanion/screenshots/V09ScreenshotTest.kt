package app.truenascompanion.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import app.truenascompanion.data.model.BootEnvironment
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.NasUpdateStatus
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.SectionTitle
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.system.SettingRow
import app.truenascompanion.ui.theme.TrueNasTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import app.truenascompanion.ui.components.GlowButton
import androidx.compose.material3.OutlinedButton
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.text.DateFormat
import java.util.Date

/** v0.9.0 previews: NAS updates and boot environments (static sample UI, no ViewModels). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h2000dp-xxhdpi", application = android.app.Application::class)
class V09ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private fun out(name: String) = File(dir, "$name.png").absolutePath

    private val update = NasUpdateStatus(
        code = "NORMAL", currentTrain = "TrueNAS-SCALE-Goldeye", currentProfile = "GENERAL",
        newVersion = "25.10.5", releaseNotes = "Security fixes and SMB improvements.", releaseNotesUrl = null,
        changelog = "- Fix SMB share reconnect\n- Better scrub progress", errorReason = null,
        downloadPercent = null, downloadDescription = null,
    )
    private val boots = listOf(
        BootEnvironment("25.10.4", "boot-pool/ROOT/25.10.4", true, true, 1_720_000_000_000, 1_200_000_000, "1.12G", true, true),
        BootEnvironment("25.10.3", "boot-pool/ROOT/25.10.3", false, false, 1_700_000_000_000, 1_100_000_000, "1.02G", false, true),
        BootEnvironment("25.10.2", "boot-pool/ROOT/25.10.2", false, false, 1_680_000_000_000, 1_050_000_000, "1.00G", false, true),
    )

    @Composable private fun Frame(dark: Boolean, content: @Composable () -> Unit) {
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
    @Composable private fun Shell(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(title = { Text("System") })
            LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { item { content() } }
        }
    }

    @Test fun updateAvailable() = shot("v09_nas_update") {
        Shell {
            SectionTitle("TrueNAS update")
            ElevatedSection {
                SettingRow(Icons.Rounded.SystemUpdateAlt, "TrueNAS updates", "Update available: ${update.newVersion}")
                Spacer(Modifier.height(10.dp))
                StatusChip(Health.WARNING, "Available")
                Spacer(Modifier.height(8.dp))
                Text("New version ${update.newVersion}", style = MaterialTheme.typography.bodyLarge)
                Text(update.releaseNotes!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {}) { Text("Check") }
                    GlowButton(onClick = {}) { Text("Download & update") }
                }
            }
        }
    }

    @Test fun bootEnvs() = shot("v09_boot_envs") {
        Shell {
            SectionTitle("Boot environments")
            ElevatedSection {
                SettingRow(Icons.Rounded.Storage, "Boot environments", "Activate, clone, keep or delete")
                Spacer(Modifier.height(8.dp))
                boots.forEachIndexed { i, be ->
                    val date = be.createdMillis?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(be.id, style = MaterialTheme.typography.titleSmall)
                            Text(listOfNotNull(date, be.used, if (be.keep) "kept" else null).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        when {
                            be.active && be.activated -> StatusChip(Health.HEALTHY, "Active", showIcon = false)
                            be.activated -> StatusChip(Health.WARNING, "Next boot", showIcon = false)
                        }
                    }
                    if (i < boots.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }

    @Test fun bootEnvsLight() = shot("v09_boot_envs_light", dark = false) {
        Shell {
            SectionTitle("Boot environments")
            ElevatedSection {
                SettingRow(Icons.Rounded.Storage, "Boot environments", "Three environments")
                boots.take(2).forEach { be ->
                    Text(be.id, style = MaterialTheme.typography.titleSmall)
                    Text(be.used ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}
