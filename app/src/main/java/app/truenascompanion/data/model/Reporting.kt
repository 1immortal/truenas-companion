package app.truenascompanion.data.model

/** Report time ranges offered in the app (the web UI's zoom levels). */
enum class ReportRange(val label: String, val seconds: Long) {
    HOUR("1h", 3_600),
    DAY("1d", 86_400),
    WEEK("1w", 7 * 86_400),
    MONTH("1m", 30 * 86_400),
    /** 1.10.0: TrueNAS keeps 30 days by default (`reporting.config` `tier1_days`); the page says when history is shorter. */
    YEAR("1y", 365 * 86_400),
}

/** `reporting.netdata_graphs` entry. */
data class ReportGraph(val name: String, val title: String, val verticalLabel: String?, val identifiers: List<String>?)

/**
 * One `reporting.netdata_get_data` result: `legend[0]` is "time", rows are `[t, v1, v2, …]`.
 * Stored column-wise (seconds since epoch + one value array per series; NaN for gaps).
 */
data class ReportData(
    val name: String,
    val identifier: String?,
    val start: Long,
    val end: Long,
    val times: LongArray,
    val series: List<ReportSeries>,
) {
    val isEmpty: Boolean get() = times.isEmpty() || series.isEmpty()
}

data class ReportSeries(val label: String, val values: FloatArray, val min: Double? = null, val mean: Double? = null, val max: Double? = null)

/** The charts on the Reports screen (graph names valid for 25.10 `GraphIdentifier`). */
enum class ReportKind(val graph: String, val title: String, val unit: ReportUnit, val perIdentifier: Boolean = false) {
    CPU("cpu", "CPU", ReportUnit.PERCENT),
    CPU_TEMP("cputemp", "CPU temperature", ReportUnit.CELSIUS),
    MEMORY("memory", "Memory available", ReportUnit.BYTES),
    ARC("arcsize", "ZFS ARC size", ReportUnit.BYTES),
    NETWORK("interface", "Network", ReportUnit.KILOBITS, perIdentifier = true),
    DISK("disk", "Disk I/O", ReportUnit.KIBIBYTES, perIdentifier = true),
    DISK_TEMP("disktemp", "Disk temperature", ReportUnit.CELSIUS, perIdentifier = true),
    LOAD("load", "Load average", ReportUnit.PLAIN),
}

enum class ReportUnit { PERCENT, CELSIUS, BYTES, KILOBITS, KIBIBYTES, PLAIN }
