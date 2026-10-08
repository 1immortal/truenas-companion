package app.truenascompanion.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import app.truenascompanion.data.api.ServiceVerb
import app.truenascompanion.data.model.CronJob
import app.truenascompanion.data.model.CronJobInput
import app.truenascompanion.data.model.CronSchedule
import app.truenascompanion.data.model.InitScript
import app.truenascompanion.data.model.InitScriptType
import app.truenascompanion.data.model.InitScriptWhen
import app.truenascompanion.data.model.JobState
import app.truenascompanion.data.model.LastJob
import app.truenascompanion.data.model.ServiceInfo
import app.truenascompanion.data.services.Option
import app.truenascompanion.data.services.ServiceForms
import app.truenascompanion.data.services.ServiceKind
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.ui.services.ServiceActionDialog
import app.truenascompanion.ui.services.ServiceBusy
import app.truenascompanion.ui.services.ServiceSettingsContent
import app.truenascompanion.ui.services.ServiceSettingsData
import app.truenascompanion.ui.services.ServicesContent
import app.truenascompanion.ui.tasks.CronJobEditorContent
import app.truenascompanion.ui.tasks.CronJobsContent
import app.truenascompanion.ui.tasks.CronRun
import app.truenascompanion.ui.tasks.CronRunDialog
import app.truenascompanion.ui.tasks.InitForm
import app.truenascompanion.ui.tasks.InitScriptEditorContent
import app.truenascompanion.ui.tasks.InitScriptsContent
import app.truenascompanion.ui.tasks.TasksTabs
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

/** 1.4.0 previews with example data only (services, service settings, cron jobs, init/shutdown scripts). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class V140ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private fun out(name: String) = File(dir, "$name.png").absolutePath

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun Frame(dark: Boolean, title: String? = null, save: Boolean = false, content: @Composable () -> Unit) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, 1f)) {
            TrueNasTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    if (title == null) content()
                    else Scaffold(topBar = {
                        TopAppBar(
                            title = { Text(title) },
                            navigationIcon = { IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } },
                            actions = { if (save) TextButton(onClick = {}) { Text("Save") } },
                        )
                    }) { p -> Box(Modifier.padding(p).fillMaxSize()) { content() } }
                }
            }
        }
    }

    private fun settle() {
        repeat(8) { rule.mainClock.advanceTimeBy(250); shadowOf(android.os.Looper.getMainLooper()).idle() }
    }

    private fun shot(name: String, dark: Boolean = true, title: String? = null, save: Boolean = false, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(dark, title, save, content) }
        settle(); rule.onRoot().captureRoboImage(out(name))
    }

    // ---------- example data ----------

    private val services = listOf(
        ServiceInfo(id = 1, service = "ssh", running = true, enabledOnBoot = true),
        ServiceInfo(id = 2, service = "cifs", running = true, enabledOnBoot = true),
        ServiceInfo(id = 3, service = "nfs", running = false, enabledOnBoot = false),
        ServiceInfo(id = 4, service = "ups", running = true, enabledOnBoot = true),
        ServiceInfo(id = 5, service = "snmp", running = false, enabledOnBoot = false),
        ServiceInfo(id = 6, service = "ftp", running = false, enabledOnBoot = false),
        ServiceInfo(id = 7, service = "iscsitarget", running = false, enabledOnBoot = false),
    )

    private val upsConfig = Json.parseToJsonElement("""{
        "id":1,"mode":"MASTER","identifier":"ups","remotehost":"","remoteport":3493,"driver":"usbhid-ups${'$'}Back-UPS Pro","port":"auto",
        "options":"","optionsupsd":"","description":"Rack UPS","shutdown":"BATT","shutdowntimer":120,"shutdowncmd":null,
        "nocommwarntime":null,"monuser":"upsmon","monpwd":"example-pass","extrausers":"","rmonitor":false,
        "powerdown":true,"hostsync":15,"complete_identifier":"ups@localhost"
    }""").jsonObject
    private val upsChoices = mapOf(
        "ups.drivers" to listOf(Option("usbhid-ups${'$'}Back-UPS Pro", "APC Back-UPS Pro USB (usbhid-ups)"), Option("blazer_usb${'$'}X", "Generic Megatec USB (blazer_usb)")),
        "ups.ports" to listOf(Option("auto", "auto (USB)"), Option("/dev/ttyS0", "/dev/ttyS0")),
    )
    private val sshConfig = Json.parseToJsonElement("""{
        "id":1,"bindiface":[],"tcpport":22,"password_login_groups":["admins"],"passwordauth":true,"kerberosauth":false,
        "tcpfwd":false,"compression":false,"sftp_log_level":"","sftp_log_facility":"","weak_ciphers":["AES128-CBC"],"options":""
    }""").jsonObject

    private fun s(m: String, h: String, dom: String = "*", mon: String = "*", dow: String = "*") = CronSchedule(m, h, dom, mon, dow)
    private val cronJobs = listOf(
        CronJob(1, "Nightly cleanup", "find /mnt/tank/downloads -type f -mtime +30 -delete", "root", s("0", "3"), true, true, false),
        CronJob(2, "Weekly report", "/mnt/tank/scripts/report.sh --email admin@example.com", "admin", s("30", "7", dow = "1"), true, false, false),
        CronJob(3, "Sync photos", "rsync -a /mnt/tank/photos/ /mnt/backup/photos/", "backup", s("*/15", "*"), false, true, true),
        CronJob(4, "", "midclt call disk.smart_test SHORT '[\"sda\",\"sdb\"]'", "root", s("0", "2", dom = "1"), true, true, false),
    )
    private val scripts = listOf(
        InitScript(1, InitScriptType.COMMAND, "modprobe example_driver", "", InitScriptWhen.PREINIT, true, 10, "Load NIC driver"),
        InitScript(2, InitScriptType.SCRIPT, "", "/mnt/tank/scripts/start-containers.sh", InitScriptWhen.POSTINIT, true, 60, "Start helper containers"),
        InitScript(3, InitScriptType.COMMAND, "echo booted >> /mnt/tank/logs/boot.log", "", InitScriptWhen.POSTINIT, false, 10, ""),
        InitScript(4, InitScriptType.SCRIPT, "", "/mnt/tank/scripts/flush-cache.sh", InitScriptWhen.SHUTDOWN, true, 30, "Flush caches"),
    )

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun TasksFrame(tab: Int, content: @Composable () -> Unit) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(title = { Text("Scheduled tasks") }, navigationIcon = { IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } })
                    TasksTabs(tab) {}
                }
            },
            floatingActionButton = { ExtendedFloatingActionButton(onClick = {}, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text(if (tab == 0) "New cron job" else "New script") }) },
        ) { p -> Box(Modifier.padding(p).fillMaxSize()) { content() } }
    }

    // ---------- services ----------

    private fun servicesList(dark: Boolean, name: String) = shot(name, dark, "Services") {
        ServicesContent(services, mapOf("cifs" to ServiceBusy(ServiceVerb.RESTART, jobId = 12)), onAction = { _, _ -> }, onAutostart = { _, _ -> }, onOpen = {})
    }

    @Test fun services() = servicesList(true, "v140_services")
    @Test fun servicesLight() = servicesList(false, "v140_services_light")

    @Config(qualifiers = "w360dp-h1400dp-xxhdpi")
    @Test fun upsSettings() = shot("v140_ups_settings", true, "UPS settings", save = true) {
        val f = ServiceForms.fields(ServiceKind.UPS)
        ServiceSettingsContent(ServiceKind.UPS, f, ServiceForms.toDraft(f, upsConfig), ServiceSettingsData(upsConfig, upsChoices, services[3]), emptyMap(), onChange = { _, _ -> })
    }

    @Test fun sshSettings() = shot("v140_ssh_settings", false, "SSH settings", save = true) {
        val f = ServiceForms.fields(ServiceKind.SSH)
        val draft = ServiceForms.toDraft(f, sshConfig) + ("tcpport" to kotlinx.serialization.json.JsonPrimitive("70000"))
        ServiceSettingsContent(ServiceKind.SSH, f, draft, ServiceSettingsData(sshConfig, mapOf("ssh.bindiface" to listOf(Option("eno1", "eno1"), Option("eno2", "eno2"))), services[0]),
            ServiceForms.validate(f, draft), onChange = { _, _ -> })
    }

    @Test fun stopSshDialog() = shot("v140_stop_ssh_dialog", true, "Services") {
        ServicesContent(services, emptyMap(), onAction = { _, _ -> }, onAutostart = { _, _ -> }, onOpen = {})
        ServiceActionDialog(services[0], ServiceVerb.STOP, onConfirm = {}, onDismiss = {})
    }

    // ---------- scheduled tasks ----------

    @Test fun cronJobsList() = shot("v140_cron_jobs", true) {
        TasksFrame(0) { CronJobsContent(cronJobs, emptySet(), { _, _ -> }, {}, {}, {}) }
    }

    @Test fun cronJobsLight() = shot("v140_cron_jobs_light", false) {
        TasksFrame(0) { CronJobsContent(cronJobs, emptySet(), { _, _ -> }, {}, {}, {}) }
    }

    @Config(qualifiers = "w360dp-h1500dp-xxhdpi")
    @Test fun cronEditor() = shot("v140_cron_editor", true, "Edit cron job", save = true) {
        CronJobEditorContent(
            CronJobInput("Weekly report", "/mnt/tank/scripts/report.sh --email admin@example.com", "admin", s("30", "7", dow = "1"), hideStdout = false),
            listOf("root", "admin", "backup"), emptyMap(), null, onChange = {},
        )
    }

    @Config(qualifiers = "w360dp-h1500dp-xxhdpi")
    @Test fun cronEditorCustom() = shot("v140_cron_editor_custom", false, "New cron job", save = true) {
        CronJobEditorContent(
            CronJobInput("Business hours sync", "rsync -a /mnt/tank/work/ /mnt/backup/work/", "backup", s("*/30", "9-17", dow = "mon-fri")),
            listOf("root", "admin", "backup"), emptyMap(), null, startCustom = true, onChange = {},
        )
    }

    @Test fun cronRunResult() = shot("v140_cron_run", true) {
        TasksFrame(0) { CronJobsContent(cronJobs, emptySet(), { _, _ -> }, {}, {}, {}) }
        CronRunDialog(CronRun(cronJobs[0], LastJob(JobState.SUCCESS, null, null, null, "Removed 12 files older than 30 days\nFreed 4.2 GiB", 100.0, "Execution finished")), onClose = {})
    }

    @Test fun initScriptsList() = shot("v140_init_scripts", true) {
        TasksFrame(1) { InitScriptsContent(scripts, emptySet(), { _, _ -> }, {}, {}) }
    }

    @Test fun initScriptEditor() = shot("v140_init_script_editor", false, "Edit script", save = true) {
        InitScriptEditorContent(InitForm(InitScriptType.SCRIPT, "", "/mnt/tank/scripts/start-containers.sh", InitScriptWhen.POSTINIT, true, "60", "Start helper containers"),
            emptyMap(), null, onBrowse = {}, onChange = {})
    }
}
