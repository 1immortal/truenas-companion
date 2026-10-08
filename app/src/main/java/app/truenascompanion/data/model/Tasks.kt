package app.truenascompanion.data.model

/** System › Scheduled tasks (1.4.0): `cronjob.*` entries (TrueNAS 25.10 middleware, `cronjob.py` API model). */
data class CronJob(
    val id: Int,
    val description: String,
    val command: String,
    val user: String,
    val schedule: CronSchedule,
    val enabled: Boolean,
    /** `stdout: true` means TrueNAS throws standard output away instead of mailing it. */
    val hideStdout: Boolean,
    /** `stderr: true` means TrueNAS throws standard error away instead of mailing it. */
    val hideStderr: Boolean,
) {
    /** S.M.A.R.T. test schedules are cron jobs in 25.10; the app manages them under Storage › Protection. */
    val isSmartTest: Boolean get() = command.trimStart().startsWith("midclt call disk.smart_test")
    val title: String get() = description.trim().ifBlank { if (isSmartTest) "S.M.A.R.T. test" else "Cron job #$id" }

    /** What TrueNAS emails to the user after a run ("Emails errors only"). */
    val mailText: String get() = when {
        !hideStdout && !hideStderr -> "Emails output and errors"
        hideStdout && !hideStderr -> "Emails errors only"
        !hideStdout -> "Emails output only"
        else -> "Emails nothing"
    }
}

/** What a new or edited cron job sends (`cronjob.create` / `cronjob.update`). */
data class CronJobInput(
    val description: String,
    val command: String,
    val user: String,
    val schedule: CronSchedule,
    val enabled: Boolean = true,
    val hideStdout: Boolean = true,
    val hideStderr: Boolean = false,
)

enum class InitScriptType(val label: String) { COMMAND("Command"), SCRIPT("Script file") }

enum class InitScriptWhen(val label: String, val description: String) {
    PREINIT("Pre-init", "Early in boot, before most services start"),
    POSTINIT("Post-init", "Late in boot, once most services are running"),
    SHUTDOWN("Shutdown", "When the NAS shuts down or reboots"),
}

/** `initshutdownscript.*` entry (TrueNAS 25.10 middleware, `initshutdownscript.py` API model). */
data class InitScript(
    val id: Int,
    val type: InitScriptType,
    val command: String,
    val script: String,
    val whenRun: InitScriptWhen,
    val enabled: Boolean,
    val timeout: Int,
    val comment: String,
) {
    /** The command or the script path, whichever applies. */
    val target: String get() = if (type == InitScriptType.SCRIPT) script else command
    val title: String get() = comment.trim().ifBlank { "${whenRun.label} ${if (type == InitScriptType.SCRIPT) "script" else "command"}" }
}

data class InitScriptInput(
    val type: InitScriptType,
    val command: String,
    val script: String,
    val whenRun: InitScriptWhen,
    val enabled: Boolean = true,
    val timeout: Int = 10,
    val comment: String = "",
)
