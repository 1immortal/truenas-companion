package app.truenascompanion.screenshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import app.truenascompanion.data.model.FsEntry
import app.truenascompanion.data.model.InstanceStatus
import app.truenascompanion.data.model.VirtInstance
import app.truenascompanion.data.model.VmDevice
import app.truenascompanion.data.model.VmInfo
import app.truenascompanion.data.model.VmState
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.theme.TrueNasTheme
import app.truenascompanion.ui.virt.ContainerCard
import app.truenascompanion.ui.virt.ContainerList
import app.truenascompanion.ui.virt.ContainersUi
import app.truenascompanion.ui.virt.DeleteVmDialog
import app.truenascompanion.ui.virt.IsoBrowserDialog
import app.truenascompanion.ui.virt.VmCreateContent
import app.truenascompanion.ui.virt.VmCreateUi
import app.truenascompanion.ui.virt.VmDetailContent
import app.truenascompanion.ui.virt.VmFormState
import app.truenascompanion.ui.virt.VmList
import app.truenascompanion.ui.virt.Workload
import app.truenascompanion.ui.virt.WorkloadSwitcher
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** v0.4.1 previews: VMs and containers. Example data only. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h1700dp-xxhdpi", application = android.app.Application::class)
class V041ScreenshotTest {
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

    private val ubuntu = VmInfo(
        3, "ubuntu", "Dev box", VmState.RUNNING, 2, 2, 1, 8192, true, "UEFI",
        listOf(
            VmDevice(10, "DISK", "Disk", "tank/vms/ubuntu-disk0 · VIRTIO"),
            VmDevice(11, "CDROM", "CD-ROM", "ubuntu-24.04.iso"),
            VmDevice(12, "NIC", "Network", "VIRTIO · br0 · 00:a0:98:12:34:56"),
            VmDevice(13, "DISPLAY", "Display", "SPICE · port 5900 · web"),
        ), true,
    )
    private val vms = listOf(
        ubuntu,
        ubuntu.copy(id = 4, name = "windows11", description = "Gaming VM with GPU passthrough", state = VmState.STOPPED, vcpus = 1, cores = 8, threads = 2, memoryMb = 16384, autostart = false),
        ubuntu.copy(id = 5, name = "homeassistant", description = "", state = VmState.RUNNING, vcpus = 2, cores = 1, memoryMb = 4096),
    )

    @Composable private fun VmTab(busy: Map<String, String> = emptyMap()) = Column(Modifier.height(1000.dp)) {
        WorkloadSwitcher(Workload.VMS, {})
        VmList(UiState.Success(vms), busy, {}, {}, {}, { _, _ -> })
    }
    @Test fun vmsDark() = shot("preview-vms-dark", true) { VmTab() }
    @Test fun vmsLight() = shot("preview-vms-light", false) { VmTab(mapOf("vm:5" to "Restart")) }
    @Test fun vmsFont() = shot("audit-vms-font130", true, 1.3f) { VmTab() }

    @Composable private fun Detail() = Box(Modifier.height(1150.dp)) { VmDetailContent(ubuntu, null, {}, {}, {}, {}) }
    @Test fun vmDetailDark() = shot("preview-vm-detail-dark", true) { Detail() }
    @Test fun vmDetailLight() = shot("preview-vm-detail-light", false) { Detail() }
    @Test fun vmDetailFont() = shot("audit-vm-detail-font130", false, 1.3f, height = 1400) { VmDetailContent(vms[1], null, {}, {}, {}, {}) }

    private val createUi = VmCreateUi(
        form = VmFormState(name = "debian", diskParent = "tank/vms", nicAttach = "br0", isoPath = "/mnt/tank/iso/debian-13.1.0-amd64-netinst.iso"),
        parents = listOf("tank", "tank/vms", "fast"), nics = listOf("br0", "enp3s0"), loading = false,
    )
    @Test fun createDark() = shot("preview-vm-create-dark", true, height = 1650) { VmCreateContent(createUi, {}, {}, {}) }
    @Test fun createLight() = shot("preview-vm-create-light", false, height = 1650) { VmCreateContent(createUi.copy(showErrors = true, form = createUi.form.copy(name = "my-vm")), {}, {}, {}) }
    @Test fun createFont() = shot("audit-vm-create-font130", true, 1.3f, height = 1700) { VmCreateContent(createUi, {}, {}, {}) }

    @Test fun deleteVm() = dialogShot("audit-vm-delete-font130", true, 1.3f) { DeleteVmDialog(vms[1], {}, {}) }
    @Test fun isoBrowser() = dialogShot("preview-iso-browser-light", false) {
        IsoBrowserDialog(createUi.copy(browsePath = "/mnt/tank/iso", browseEntries = listOf(
            FsEntry("old", "/mnt/tank/iso/old", true),
            FsEntry("debian-13.1.0-amd64-netinst.iso", "/mnt/tank/iso/debian-13.1.0-amd64-netinst.iso", false),
            FsEntry("TrueNAS-SCALE-25.10.3.iso", "/mnt/tank/iso/TrueNAS-SCALE-25.10.3.iso", false),
            FsEntry("virtio-win-0.1.271.iso", "/mnt/tank/iso/virtio-win-0.1.271.iso", false),
        )), {}, {}, {})
    }

    private val containers = listOf(
        VirtInstance("web", "web", "CONTAINER", InstanceStatus.RUNNING, "2", 2L * 1024 * 1024 * 1024, true, "Debian bookworm amd64 (20250101_05:24)", listOf("10.0.3.12", "fd42:1::12"), "tank"),
        VirtInstance("pihole", "pihole", "CONTAINER", InstanceStatus.RUNNING, null, null, true, "Ubuntu noble amd64", listOf("10.0.3.20"), "tank"),
        VirtInstance("build", "build", "CONTAINER", InstanceStatus.STOPPED, "8", 8L * 1024 * 1024 * 1024, false, "Alpine 3.22 amd64", emptyList(), "fast"),
    )
    @Composable private fun Containers() = Column(Modifier.height(1000.dp)) {
        WorkloadSwitcher(Workload.CONTAINERS, {})
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ContainerCard(containers[0], null, {}, {}, startExpanded = true)
            ContainerCard(containers[1], "Restart", {}, {})
            ContainerCard(containers[2], null, {}, {})
        }
    }
    @Test fun containersDark() = shot("preview-containers-dark", true) { Containers() }
    @Test fun containersLight() = shot("preview-containers-light", false) { Containers() }
    @Test fun containersFont() = shot("audit-containers-font130", true, 1.3f, height = 1300) { Containers() }
    @Test fun containersEmpty() = shot("preview-containers-empty-light", false, height = 700) {
        ContainerList(ContainersUi(UiState.Success(emptyList()), "Containers aren't set up yet. Choose a pool for them in the TrueNAS web UI (Containers › Configuration)."), emptyMap(), {}, { _, _ -> }, {})
    }
}
