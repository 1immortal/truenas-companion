package app.truenascompanion.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import app.truenascompanion.data.disks.DisksApi
import app.truenascompanion.data.model.Dataset
import app.truenascompanion.data.model.DiskAlert
import app.truenascompanion.data.model.DiskInfo
import app.truenascompanion.data.model.EnclosureSlot
import app.truenascompanion.data.model.FileEntry
import app.truenascompanion.data.model.FileSort
import app.truenascompanion.data.model.FileStat
import app.truenascompanion.data.model.Pool
import app.truenascompanion.data.model.ReplacementCandidate
import app.truenascompanion.data.model.Route
import app.truenascompanion.data.disks.DiskLogic
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.ui.disks.DiskDetailContent
import app.truenascompanion.ui.disks.DisksData
import app.truenascompanion.ui.disks.DisksPane
import app.truenascompanion.ui.disks.PoolLayoutContent
import app.truenascompanion.ui.disks.ReplaceActions
import app.truenascompanion.ui.disks.ReplaceStep
import app.truenascompanion.ui.disks.ReplaceUi
import app.truenascompanion.ui.disks.ReplaceWizardContent
import app.truenascompanion.ui.files.FileActions
import app.truenascompanion.ui.files.FileBrowserContent
import app.truenascompanion.ui.files.FileBrowserUi
import app.truenascompanion.ui.files.FileDetails
import app.truenascompanion.ui.files.FilePreview
import app.truenascompanion.ui.files.FilesLauncher
import app.truenascompanion.ui.files.PreviewContent
import app.truenascompanion.ui.files.StopUploadDialog
import app.truenascompanion.ui.files.TransferKind
import app.truenascompanion.ui.files.TransferState
import app.truenascompanion.ui.theme.TrueNasTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 1.3.0 previews with example data only (file browser, disks, pool layout, replace wizard). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class V130ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private fun out(name: String) = File(dir, "$name.png").absolutePath
    private val now = System.currentTimeMillis()
    private val day = 86_400_000L

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun Frame(dark: Boolean, title: String? = null, content: @Composable () -> Unit) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, 1f)) {
            TrueNasTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    if (title == null) content()
                    else Scaffold(topBar = {
                        TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } })
                    }) { p -> Box(Modifier.padding(p).fillMaxSize()) { content() } }
                }
            }
        }
    }

    private fun settle() {
        repeat(8) { rule.mainClock.advanceTimeBy(250); shadowOf(android.os.Looper.getMainLooper()).idle() }
    }

    private fun shot(name: String, dark: Boolean = true, title: String? = null, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(dark, title, content) }
        settle(); rule.onRoot().captureRoboImage(out(name))
    }

    // ---------- example data ----------

    private fun e(name: String, type: String = "FILE", size: Long = 0, daysAgo: Int = 1, mount: Boolean = false, mode: Int = if (type == "DIRECTORY") 0x41ED else 0x81A4) =
        FileEntry(name, "/mnt/tank/media/$name", type, size, mode, 3000, 3000, false, mount, null, now - daysAgo * day)

    private val entries = listOf(
        e("Movies", "DIRECTORY", 4, 3, mount = true), e("Music", "DIRECTORY", 4, 12, mount = true), e("Photos", "DIRECTORY", 4, 1),
        e("Downloads", "DIRECTORY", 4, 0), e("ix-apps", "DIRECTORY", 4, 40), e(".recycle", "DIRECTORY", 4, 5),
        e("holiday-2026.mp4", size = 3_812_000_000, daysAgo = 20), e("IMG_0042.jpg", size = 4_200_000, daysAgo = 2),
        e("budget.xlsx", size = 48_000, daysAgo = 6), e("notes.txt", size = 2_100, daysAgo = 0), e("backup-config.tar.gz", size = 182_000_000, daysAgo = 9),
        e("manual.pdf", size = 9_400_000, daysAgo = 33),
    )
    private val ui = FileBrowserUi(path = "/mnt/tank/media", entries = entries, total = entries.size, loading = false, pools = listOf("tank", "backup"), route = Route.LOCAL)

    private val pools = listOf(
        Pool(1, "tank", "DEGRADED", false, false, "One or more devices has been removed.", 8_000_000_000_000, 3_100_000_000_000, 4_900_000_000_000, "4", "RESILVER", "SCANNING", 42.5, 0, listOf("sda", "sdc")),
        Pool(2, "backup", "ONLINE", true, false, null, 4_000_000_000_000, 1_200_000_000_000, 2_800_000_000_000, "1", "SCRUB", "FINISHED", 100.0, 0, listOf("sdd")),
    )
    private fun ds(id: String, locked: Boolean = false) = Dataset(id, id.substringBefore('/'), "FILESYSTEM", 100, 900, locked, locked, "/mnt/$id")
    private val datasets = listOf(ds("tank"), ds("tank/media"), ds("tank/media/Movies"), ds("tank/documents"), ds("tank/vault", locked = true), ds("tank/.ix-apps"), ds("backup"), ds("backup/replicas"))

    private val poolJson = """{"id":1,"name":"tank","guid":"1","status":"DEGRADED","healthy":false,"status_detail":"One or more devices has been removed.","size":8000000000000,"allocated":3100000000000,"free":4900000000000,
      "scan":{"function":"RESILVER","state":"SCANNING","start_time":{"${'$'}date":${now - 3_600_000}},"end_time":null,"percentage":42.5,"bytes_to_process":3100000000000,"bytes_processed":1317500000000,"bytes_issued":1300000000000,"pause":null,"errors":0,"total_secs_left":7500},
      "topology":{"data":[{"name":"mirror-0","type":"MIRROR","guid":"200","status":"DEGRADED","stats":{"read_errors":0,"write_errors":0,"checksum_errors":0,"size":4000000000000},"children":[
         {"name":"sda1","type":"DISK","guid":"201","status":"ONLINE","path":"/dev/disk/by-partuuid/a","disk":"sda","stats":{"read_errors":0,"write_errors":0,"checksum_errors":0},"children":[]},
         {"name":"replacing-1","type":"REPLACING","guid":"210","status":"DEGRADED","stats":{"read_errors":0,"write_errors":0,"checksum_errors":0},"children":[
            {"name":"9988","type":"DISK","guid":"202","status":"REMOVED","path":"/dev/disk/by-partuuid/b","disk":null,"stats":{"read_errors":12,"write_errors":3,"checksum_errors":0},"children":[],"unavail_disk":{"name":"sdb","serial":"WD-EXAMPLE0002","model":"WDC WD40EFRX-68N32N0","size":4000787030016}},
            {"name":"sdc1","type":"DISK","guid":"203","status":"ONLINE","path":"/dev/disk/by-partuuid/c","disk":"sdc","stats":{"read_errors":0,"write_errors":0,"checksum_errors":0},"children":[]}]}]},
        {"name":"mirror-1","type":"MIRROR","guid":"300","status":"ONLINE","stats":{"read_errors":0,"write_errors":0,"checksum_errors":0,"size":4000000000000},"children":[
         {"name":"sde1","type":"DISK","guid":"301","status":"ONLINE","path":"/dev/disk/by-partuuid/e","disk":"sde","stats":{"read_errors":0,"write_errors":0,"checksum_errors":2},"children":[]},
         {"name":"sdf1","type":"DISK","guid":"302","status":"ONLINE","path":"/dev/disk/by-partuuid/f","disk":"sdf","stats":{"read_errors":0,"write_errors":0,"checksum_errors":0},"children":[]}]}],
       "log":[],"cache":[{"name":"nvme0n1p1","type":"DISK","guid":"400","status":"ONLINE","path":"/dev/nvme0n1p1","disk":"nvme0n1","stats":{"read_errors":0,"write_errors":0,"checksum_errors":0},"children":[]}],"spare":[],"special":[],"dedup":[]}}"""
    private val layout = DisksApi.pool(Json.parseToJsonElement(poolJson).jsonObject)
    private fun disk(n: String, model: String, serial: String, size: Long, type: String, temp: Double?, bus: String = "ATA", pool: String? = "tank", rpm: Int? = 5400) =
        DiskInfo(n, "{serial_lunid}$serial", serial, model, size, type, bus, if (bus == "NVME") "nvme" else "scsi", rpm, null, pool, null, temp)
    private val disks = listOf(
        disk("sda", "WDC WD40EFRX-68N32N0", "WD-EXAMPLE0001", 4_000_787_030_016, "HDD", 36.0),
        disk("sdc", "WDC WD40EFRX-68N32N0", "WD-EXAMPLE0003", 4_000_787_030_016, "HDD", 34.0),
        disk("sde", "ST4000VN006-3CW104", "ZW6EXAMPLE5", 4_000_787_030_016, "HDD", 47.0),
        disk("sdf", "ST4000VN006-3CW104", "ZW6EXAMPLE6", 4_000_787_030_016, "HDD", 39.0),
        disk("nvme0n1", "Example NVMe 1TB", "EXNV0001", 1_000_204_886_016, "SSD", 44.0, bus = "NVME", rpm = null),
        disk("sdd", "Example SSD 2TB", "EXSSD0007", 2_000_398_934_016, "SSD", 31.0, pool = "backup", rpm = null),
    )
    private val data = DisksData(listOf(layout), disks, listOf(EnclosureSlot("encl-1", 1, "sde", true, false)),
        listOf(DiskAlert("SMARTUncorrectedErrors", "WARNING", "2 uncorrectable errors reported for sde (ZW6EXAMPLE5).", now - day)))

    // ---------- file browser ----------

    @Test fun files() = shot("v130_files") { FileBrowserContent(ui) }

    @Test fun filesLight() = shot("v130_files_light", dark = false) {
        FileBrowserContent(
            ui.copy(sort = FileSort.SIZE, descending = true, route = Route.REMOTE,
                transfer = TransferState(TransferKind.DOWNLOAD, "holiday-2026.mp4", 1_412_000_000, 3_812_000_000, proxyHint = true)),
        )
    }

    @Test fun filesSearch() = shot("v130_files_search") { FileBrowserContent(ui.copy(query = "i", showSystem = true)) }

    @Test fun filesTab() = shot("v130_files_tab", title = "Storage · Files") { FilesLauncher(pools, datasets) {} }

    @Test fun fileDetails() = shot("v130_file_details") {
        val f = entries.first { it.name == "budget.xlsx" }
        Column(Modifier.padding(top = 24.dp)) {
            FileDetails(f, FileStat(f.path, f.path, "FILE", 48_000, 0x81A4, 3000, 3000, "media", "family", now, now - 6 * day, now - 6 * day, now - 90 * day, true, false, 1), null, FileActions())
        }
    }

    @Test fun filePreview() = shot("v130_file_preview") {
        PreviewContent(
            FilePreview.Text(entries.first { it.name == "notes.txt" },
                "# Shopping list\n- coffee beans\n- spare 4 TB disk (same size or larger!)\n- label maker\n\n# NAS to-do\n1. scrub backup pool\n2. replace sdb in tank\n3. check snapshot tasks\n", false),
            FileActions(),
        )
    }

    @Test fun filesUploadStop() = shot("v130_files_upload_stop") {
        val t = TransferState(TransferKind.UPLOAD, "IMG_0042.jpg", 1_900_000, 4_200_000, target = "/mnt/tank/media/IMG_0042.jpg", started = true, replacing = true)
        FileBrowserContent(ui.copy(transfer = t, incomplete = setOf("/mnt/tank/media/holiday-2026.mp4")))
        StopUploadDialog(t, onKeep = {}, onStop = {})
    }

    // ---------- disks ----------

    @Test fun disksTab() = shot("v130_disks", title = "Storage · Disks") { DisksPane(data) {} }

    @Test fun poolLayout() = shot("v130_pool_layout", title = "tank") { PoolLayoutContent(layout, data, {}, {}) }

    @Test fun poolLayoutLight() = shot("v130_pool_layout_light", dark = false, title = "tank") { PoolLayoutContent(layout, data, {}, {}) }

    @Test fun diskDetail() = shot("v130_disk_detail", title = "sde") {
        DiskDetailContent("sde", data.copy(disks = disks.map { if (it.name == "sde") it.copy(tempMin = 33.0, tempAvg = 41.0, tempMax = 49.0) else it }), busy = false)
    }

    private val member = DiskLogic.memberByGuid(layout, "202")
    private val candidates = listOf(
        ReplacementCandidate("sdg", "{serial_lunid}WD-EXAMPLE0009", "WD-EXAMPLE0009", "WDC WD40EFRX-68N32N0", 4_000_787_030_016, "HDD", "ATA", null, emptyList()),
        ReplacementCandidate("sdh", "{serial_lunid}ZL2EXAMPLE8", "ZL2EXAMPLE8", "ST8000VN004-3CP101", 8_001_563_222_016, "HDD", "ATA", "oldpool", emptyList()),
        ReplacementCandidate("sdi", "{serial}EXUSB01", "EXUSB01", "Example USB 3 disk", 2_000_398_934_016, "HDD", "USB", null, listOf("sdj")),
    )

    @Test fun replaceIntro() = shot("v130_replace_intro", dark = false) {
        Column { ReplaceWizardContent(ReplaceUi(step = ReplaceStep.INTRO, loading = false, pool = layout, disks = disks, member = member), ReplaceActions()) }
    }

    @Test fun replaceChoose() = shot("v130_replace_choose") {
        Column { ReplaceWizardContent(ReplaceUi(step = ReplaceStep.CHOOSE, loading = false, pool = layout, disks = disks, member = member), ReplaceActions()) }
    }

    @Test fun replacePick() = shot("v130_replace_pick") {
        Column {
            ReplaceWizardContent(
                ReplaceUi(step = ReplaceStep.PICK, loading = false, pool = layout, disks = disks, member = member,
                    candidates = DiskLogic.candidates(candidates, 4_000_787_030_016), selected = candidates[0], serialConfirmed = true),
                ReplaceActions(),
            )
        }
    }

    @Test fun replaceResilver() = shot("v130_replace_resilver") {
        Column { ReplaceWizardContent(ReplaceUi(step = ReplaceStep.RESILVER, loading = false, pool = layout, disks = disks, member = member), ReplaceActions()) }
    }

    @Test fun replacePickWarnings() = shot("v130_replace_pick_warning", dark = false) {
        val oldDisk = ReplacementCandidate("sdk", "{serial_lunid}WD-EXAMPLE0002", "WD-EXAMPLE0002", "WDC WD40EFRX-68N32N0", 4_000_787_030_016, "HDD", "USB", null, emptyList())
        Column {
            ReplaceWizardContent(
                ReplaceUi(step = ReplaceStep.PICK, loading = false, pool = layout, disks = disks, member = member,
                    candidates = listOf(oldDisk), selected = oldDisk),
                ReplaceActions(),
            )
        }
    }
}
