package app.truenascompanion.screenshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.truenascompanion.data.api.Parsers
import app.truenascompanion.data.model.AppContainerInfo
import app.truenascompanion.data.model.AppInfo
import app.truenascompanion.data.model.AppState
import app.truenascompanion.data.model.AppStats
import app.truenascompanion.data.model.AuthMethod
import app.truenascompanion.data.model.CatalogApp
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.LogLine
import app.truenascompanion.data.model.Route
import app.truenascompanion.data.model.RouteMode
import app.truenascompanion.data.net.LocalDetector
import app.truenascompanion.data.security.LockSettings
import app.truenascompanion.data.security.RelockDelay
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.ui.apps.AppDetailContent
import app.truenascompanion.ui.apps.AppFormContent
import app.truenascompanion.ui.apps.AppFormUi
import app.truenascompanion.ui.apps.CatalogContent
import app.truenascompanion.ui.apps.CatalogDetailContent
import app.truenascompanion.ui.apps.CatalogUi
import app.truenascompanion.ui.apps.DeleteAppDialog
import app.truenascompanion.ui.apps.LogStatus
import app.truenascompanion.ui.apps.LogsContent
import app.truenascompanion.ui.apps.LogsUi
import app.truenascompanion.ui.apps.RollbackDialog
import app.truenascompanion.ui.apps.form.AppForm
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.dashboard.DashboardTitle
import app.truenascompanion.ui.lock.Biometrics
import app.truenascompanion.ui.lock.LockScreen
import app.truenascompanion.ui.lock.SecuritySection
import app.truenascompanion.ui.servers.DetectedAddressDialog
import app.truenascompanion.ui.servers.LocalAddressSection
import app.truenascompanion.ui.servers.LocalStatus
import app.truenascompanion.ui.servers.ServerEditState
import app.truenascompanion.ui.theme.TrueNasTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** v0.4.0 previews: app lock, home network, apps (catalog / install / detail / logs). Example data only. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h1700dp-xxhdpi", application = android.app.Application::class)
class V04ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private fun out(name: String) = File(dir, "$name.png").absolutePath

    @Composable
    private fun Frame(dark: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
            TrueNasTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }
    }

    private fun shot(name: String, dark: Boolean, font: Float = 1f, height: Int? = null, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(dark, font) { Box(if (height != null) Modifier.fillMaxWidth().height(height.dp) else Modifier.fillMaxWidth()) { content() } } }
        rule.mainClock.advanceTimeBy(1500)
        rule.onRoot().captureRoboImage(out(name))
    }

    private fun dialogShot(name: String, dark: Boolean, font: Float = 1f, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(dark, font) { Box(Modifier.fillMaxSize()) { content() } } }
        rule.mainClock.advanceTimeBy(1000)
        rule.onNode(isDialog()).captureRoboImage(out(name))
    }

    // --- app lock ---
    @Composable private fun Lock() = LockScreen(error = null) {}
    @Test fun lockDark() = shot("preview-lock-dark", true, height = 760) { Lock() }
    @Test fun lockLight() = shot("preview-lock-light", false, height = 760) { Lock() }
    @Test fun lockFont() = shot("audit-lock-font130", true, 1.3f, height = 760) { LockScreen(error = "Too many attempts. Try again in 30 seconds.") {} }

    @Composable private fun Security(on: Boolean = true, availability: Biometrics.Availability = Biometrics.Availability.READY) = Box(Modifier.padding(16.dp)) {
        SecuritySection(LockSettings(enabled = on, relock = RelockDelay.ONE_MINUTE), availability, {}, {}, {}, {}, {})
    }
    @Test fun securityDark() = shot("preview-security-dark", true) { Security() }
    @Test fun securityLight() = shot("preview-security-light", false) { Security() }
    @Test fun securityFont() = shot("audit-security-font130", false, 1.3f) { Security(false, Biometrics.Availability.NO_SCREEN_LOCK) }

    // --- home network ---
    private val edit = ServerEditState(
        name = "Home NAS", url = "https://nas.example.com", authMethod = AuthMethod.PASSWORD, username = "truenas_admin",
        localUrl = "https://192.168.1.10:444", localPinnedCert = "ab:cd", routeMode = RouteMode.AUTO, localStatus = LocalStatus.Reachable(true),
    )
    @Composable private fun Local(s: ServerEditState, route: Route?) = Column(Modifier.padding(16.dp)) {
        LocalAddressSection(s, route, {}, {}, {}, {}, {})
    }
    @Test fun localDark() = shot("preview-local-address-dark", true) { Local(edit, Route.LOCAL) }
    @Test fun localLight() = shot("preview-local-address-light", false) { Local(edit, Route.LOCAL) }
    @Test fun localHttpWarn() = shot("preview-local-address-http-light", false) {
        Local(edit.copy(localUrl = "http://192.168.1.10:81", localPinnedCert = null, localStatus = null), Route.REMOTE)
    }
    @Test fun localHttpBlocked() = shot("audit-local-address-apikey-http-font130", true, 1.3f) {
        Local(edit.copy(authMethod = AuthMethod.API_KEY, localUrl = "http://192.168.1.10:81", localPinnedCert = null, localStatus = null), null)
    }
    @Test fun localDetecting() = shot("audit-local-address-detecting-font130", false, 1.3f) { Local(edit.copy(localUrl = "192.168.1.10", localPinnedCert = null, localStatus = null, detecting = true), null) }
    @Test fun detectedHttps() = dialogShot("preview-detected-https-dark", true) {
        DetectedAddressDialog(LocalDetector.Found("https://192.168.1.10:444", true), AuthMethod.PASSWORD, {}, {})
    }
    @Test fun detectedHttp() = dialogShot("preview-detected-http-light", false, 1.3f) {
        DetectedAddressDialog(LocalDetector.Found("http://192.168.1.10:81", false), AuthMethod.PASSWORD, {}, {})
    }
    @Test fun routeChip() = shot("preview-route-chip-dark", true) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.titleLarge) {
                DashboardTitle("homenas", "Live", Health.HEALTHY, live = true, route = Route.LOCAL)
                DashboardTitle("homenas", "Live", Health.HEALTHY, live = true, route = Route.REMOTE)
            }
        }
    }

    // --- apps ---
    private fun cat(name: String, title: String, desc: String, pop: Int, vararg cats: String, installed: Boolean = false) =
        CatalogApp(name, title, desc, null, cats.toList(), "stable", installed, "1.2.0", "1.0", pop)
    private val catalog = listOf(
        cat("jellyfin", "Jellyfin", "Free software media system that puts you in control of managing and streaming your media.", 1, "media", installed = true),
        cat("immich", "Immich", "High performance self-hosted photo and video management solution.", 2, "photos", "media"),
        cat("nextcloud", "Nextcloud", "A file sharing server that puts the control and security of your own data back into your hands.", 3, "productivity"),
        cat("home-assistant", "Home Assistant", "Open source home automation that puts local control and privacy first.", 4, "home-automation"),
        cat("vaultwarden", "Vaultwarden", "Alternative implementation of the Bitwarden server API written in Rust.", 5, "security"),
        cat("syncthing", "Syncthing", "Continuous file synchronization program.", 6, "storage"),
    )
    @Composable private fun Catalog(query: String = "", category: String? = null) =
        Box(Modifier.height(1000.dp)) { CatalogContent(CatalogUi(UiState.Success(catalog), query, category), {}, {}, {}, {}) }
    @Test fun catalogDark() = shot("preview-catalog-dark", true) { Catalog() }
    @Test fun catalogLight() = shot("preview-catalog-light", false) { Catalog(category = "media") }
    @Test fun catalogFont() = shot("audit-catalog-font130", true, 1.3f) { Catalog() }

    private val detailsJson = Json.parseToJsonElement(
        """{"name": "jellyfin", "title": "Jellyfin", "categories": ["media"], "latest_version": "1.2.0",
            "description": "Free software media system that puts you in control of managing and streaming your media.",
            "app_readme": "<h1>Jellyfin</h1><p>Jellyfin is a Free Software Media System that puts you in control of managing and streaming your media.</p>",
            "home": "https://jellyfin.org", "sources": ["https://github.com/jellyfin/jellyfin"],
            "versions": {"1.2.0": {"app_metadata": {"app_version": "10.10.7"}, "values": {}, "schema": $SCHEMA}}}""",
    ).jsonObject
    @Test fun catalogDetailDark() = shot("preview-catalog-detail-dark", true, height = 900) { CatalogDetailContent(Parsers.catalogDetails(detailsJson, "stable")!!) {} }
    @Test fun catalogDetailFont() = shot("audit-catalog-detail-font130", false, 1.3f, height = 1000) { CatalogDetailContent(Parsers.catalogDetails(detailsJson, "stable")!!) {} }

    @Composable private fun Form() {
        val schema = Json.parseToJsonElement(SCHEMA).jsonObject
        val q = AppForm.parseQuestions(schema)
        val values = AppForm.withDefaults(q, JsonObject(emptyMap()))
        AppFormContent(
            AppFormUi(loading = false, title = "Jellyfin", version = "1.2.0", groups = AppForm.groups(schema), values = values, appName = "jellyfin",
                issues = mapOf("jellyfin.password" to "Password needs at least 8 characters")),
            install = true, onAppName = {}, onChange = { _, _ -> }, onRemove = { _, _ -> }, onJson = {}, onSubmit = {},
        )
    }
    @Test fun formDark() = shot("preview-install-form-dark", true, height = 1500) { Form() }
    @Test fun formLight() = shot("preview-install-form-light", false, height = 1500) { Form() }
    @Test fun formFont() = shot("audit-install-form-font130", true, 1.3f, height = 1650) { Form() }

    private val installed = AppInfo(
        name = "jellyfin", state = AppState.RUNNING, version = "10.10.7_1.2.0", upgradeAvailable = false, imageUpdatesAvailable = false,
        description = "Media server", portalUrl = "http://192.168.1.10:30013/", containers = 1, catalogName = "jellyfin", train = "stable",
        containerDetails = listOf(
            AppContainerInfo("a1", "jellyfin", "jellyfin/jellyfin:10.10.7", "running"),
            AppContainerInfo("a2", "permissions", "ixsystems/container-utils:1.0.2", "exited"),
        ),
        notes = "# Welcome to TrueNAS SCALE\nThank you for installing Jellyfin!\n\n## Bug reports\nIf you find a bug in this app, please file an issue.",
    )
    private val stats = AppStats("jellyfin", 7, 612L * 1024 * 1024, 184_000, 12_400, 1_250_000_000, 380_000_000)
    @Composable private fun Detail() = Box(Modifier.height(1000.dp)) { AppDetailContent(installed, stats, {}, {}, {}) }
    @Test fun detailDark() = shot("preview-app-detail-dark", true) { Detail() }
    @Test fun detailLight() = shot("preview-app-detail-light", false) { Detail() }
    @Test fun detailFont() = shot("audit-app-detail-font130", true, 1.3f) { Detail() }
    @Test fun deleteDialog() = dialogShot("audit-delete-app-font130", false, 1.3f) { DeleteAppDialog("jellyfin", {}, {}) }
    @Test fun rollbackDialog() = dialogShot("preview-rollback-dark", true) { RollbackDialog("jellyfin", "1.2.0", listOf("1.1.4", "1.1.3", "1.0.9"), { _, _ -> }, {}) }

    private val logLines = listOf(
        "[INF] Jellyfin version: 10.10.7",
        "[INF] Environment Variables: [\"[JELLYFIN_DATA_DIR, /config]\", \"[JELLYFIN_CACHE_DIR, /cache]\"]",
        "[INF] Arguments: [\"/jellyfin/jellyfin.dll\", \"--ffmpeg\", \"/usr/lib/jellyfin-ffmpeg/ffmpeg\"]",
        "[INF] Operating system: Debian GNU/Linux 12 (bookworm)",
        "[INF] Kestrel is listening on 0.0.0.0",
        "[WRN] Unable to find ffprobe; some features may be unavailable",
        "[INF] Executed all pre-startup entry points in 0:00:00.4",
        "[INF] Core startup complete",
        "[INF] Startup complete 0:00:04.2",
        "[INF] Scheduled task Scan Media Library completed after 1 minute(s) and 12 seconds",
        "[ERR] Error downloading subtitles from OpenSubtitles: 401 Unauthorized",
        "[INF] Playback started: Big Buck Bunny (1080p) on Android TV",
    ).map { LogLine(it, null) }
    @Composable private fun Logs(query: String = "", paused: Int = 0) = Box(Modifier.height(900.dp)) {
        LogsContent(LogsUi(installed.containerDetails, "a1", logLines, LogStatus.STREAMING), query, follow = true, pendingLines = paused,
            onQuery = {}, onSelect = {}, onResume = {}, onUserScrolledUp = {})
    }
    @Test fun logsDark() = shot("preview-logs-dark", true) { Logs() }
    @Test fun logsLight() = shot("preview-logs-light", false) { Logs(query = "inf") }
    @Test fun logsFont() = shot("audit-logs-font130", true, 1.3f) { Logs(paused = 42) }

    companion object {
        const val SCHEMA = """{
          "groups": [{"name": "Jellyfin Configuration", "description": "Configure Jellyfin"}, {"name": "Network Configuration", "description": "Configure network"},
                     {"name": "Storage Configuration", "description": "Configure storage"}],
          "questions": [
            {"variable": "TZ", "label": "Timezone", "group": "Jellyfin Configuration", "schema": {"type": "string", "default": "Etc/UTC", "required": true,
              "enum": [{"value": "Etc/UTC", "description": "'Etc/UTC' timezone"}, {"value": "Asia/Jerusalem", "description": "'Asia/Jerusalem' timezone"}]}},
            {"variable": "jellyfin", "label": "", "group": "Jellyfin Configuration", "schema": {"type": "dict", "attrs": [
              {"variable": "publish_server_url", "label": "Publish Server URL", "description": "The URL clients should use.", "schema": {"type": "uri"}},
              {"variable": "password", "label": "Admin password", "schema": {"type": "string", "private": true, "min_length": 8, "default": "secret"}},
              {"variable": "additional_envs", "label": "Additional Environment Variables", "schema": {"type": "list", "default": [], "items": [
                {"variable": "env", "label": "Environment Variable", "schema": {"type": "dict", "attrs": [
                  {"variable": "name", "label": "Name", "schema": {"type": "string", "required": true}},
                  {"variable": "value", "label": "Value", "schema": {"type": "string"}}]}}]}}]}},
            {"variable": "network", "label": "", "group": "Network Configuration", "schema": {"type": "dict", "attrs": [
              {"variable": "web_port", "label": "WebUI Port", "description": "The port for Jellyfin WebUI", "schema": {"type": "int", "default": 30013, "min": 1, "max": 65535, "required": true}},
              {"variable": "host_network", "label": "Host Network", "description": "Bind to the host network. Recommended for DLNA.", "schema": {"type": "boolean", "default": false}}]}},
            {"variable": "storage", "label": "", "group": "Storage Configuration", "schema": {"type": "dict", "attrs": [
              {"variable": "config", "label": "Jellyfin Config Storage", "schema": {"type": "dict", "attrs": [
                {"variable": "type", "label": "Type", "schema": {"type": "string", "default": "ix_volume", "required": true,
                  "enum": [{"value": "host_path", "description": "Host Path (Path that already exists on the system)"}, {"value": "ix_volume", "description": "ixVolume (Dataset created automatically by the system)"}]}},
                {"variable": "host_path", "label": "Host Path", "schema": {"type": "hostpath", "show_if": [["type", "=", "host_path"]], "required": true}}]}}]}}
          ]
        }"""
    }
}
