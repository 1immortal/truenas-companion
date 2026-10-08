package app.truenascompanion.data.api

import app.truenascompanion.data.model.CertKind
import app.truenascompanion.data.model.ServiceInfo
import app.truenascompanion.data.services.Option
import app.truenascompanion.data.services.ServiceKind
import app.truenascompanion.data.services.ServiceSpecs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

enum class ServiceVerb(val label: String, val doing: String, val done: String) {
    START("Start", "Starting", "started"),
    STOP("Stop", "Stopping", "stopped"),
    RESTART("Restart", "Restarting", "restarted"),
}

/**
 * System › Services (1.4.0), TrueNAS 25.10 (`plugins/service.py`, `api/v25_10_0/service.py`):
 * `service.query`, `service.control(verb, service, options)` (a job; `service.start/stop/restart` are deprecated and
 * removed in 26.04), `service.update(id_or_name, {enable})` for start-on-boot, and `<ns>.config` / `<ns>.update`
 * plus the `*_choices` helpers for the services that have a settings editor.
 */
class ServicesApi(private val api: TrueNasApi) {

    /** Services with an editor first (in [ServiceKind] order), then the rest by name. */
    suspend fun services(): List<ServiceInfo> =
        api.rpc("service.query").arr()?.mapNotNull { it.obj()?.let(Parsers::service) }?.let(::sorted) ?: emptyList()

    /**
     * Runs `service.control` and waits for the job. With `silent: false` a failure fails the job with the reason;
     * a `false` result (the service isn't running after START/RESTART) is reported too.
     */
    suspend fun control(service: String, verb: ServiceVerb, onJob: (Long) -> Unit = {}) {
        val options = buildJsonObject { put("silent", false) }
        val (method, first) = try {
            "service.control" to api.rpc("service.control", JsonPrimitive(verb.name), JsonPrimitive(service), options)
        } catch (e: Throwable) {
            // Releases without service.control: the older per-verb methods (deprecated in 25.x, removed in 26.04).
            if (!e.isMethodMissing()) throw e
            val old = "service.${verb.name.lowercase()}"
            old to api.rpc(old, JsonPrimitive(service), options)
        }
        val result = first.asJobId()?.let { id -> onJob(id); api.awaitJob(id, method) } ?: first
        if (result.prim()?.booleanOrNull == false) {
            val name = ServiceInfo.SERVICE_NAMES[service] ?: service
            throw TrueNasException.JobFailed(
                if (verb == ServiceVerb.STOP) "TrueNAS could not stop $name." else "$name isn't running after the ${verb.label.lowercase()}. Check its settings.",
            )
        }
    }

    /** Start on boot (`service.update`). */
    suspend fun setAutostart(service: String, enable: Boolean) {
        api.rpc("service.update", JsonPrimitive(service), buildJsonObject { put("enable", enable) })
    }

    suspend fun config(kind: ServiceKind): JsonObject =
        ServiceSpecs.normalize(kind, api.rpc("${kind.namespace}.config").obj() ?: throw TrueNasException.JobFailed("No ${kind.title} settings returned"))

    /** Sends only the changed fields and returns the saved config. Validation errors carry per-field messages ([fieldErrors]). */
    suspend fun update(kind: ServiceKind, changes: JsonObject): JsonObject =
        ServiceSpecs.normalize(kind, api.rpc("${kind.namespace}.update", changes).obj() ?: config(kind))

    /** Options loaded from the NAS for the editor's pickers (choicesKey -> options). Failures leave a picker empty. */
    suspend fun choices(kind: ServiceKind): Map<String, List<Option>> {
        suspend fun dict(method: String): List<Option> = runCatching {
            api.rpc(method).obj()?.map { (k, v) -> Option(k, v.prim()?.contentOrNull?.takeIf { it.isNotBlank() } ?: k) }
        }.getOrNull().orEmpty()
        return when (kind) {
            ServiceKind.SSH -> mapOf("ssh.bindiface" to dict("ssh.bindiface_choices"))
            ServiceKind.SMB -> mapOf("smb.bindip" to dict("smb.bindip_choices").map { Option(it.value, it.value!!) }, "smb.unixcharset" to dict("smb.unixcharset_choices").map { Option(it.value, it.value!!) })
            ServiceKind.NFS -> mapOf("nfs.bindip" to dict("nfs.bindip_choices").map { Option(it.value, it.value!!) })
            ServiceKind.UPS -> mapOf(
                "ups.drivers" to dict("ups.driver_choices").map { Option(it.value, upsDriverLabel(it)) }.sortedBy { it.label.lowercase() },
                "ups.ports" to runCatching { api.rpc("ups.port_choices").arr()?.mapNotNull { it.prim()?.contentOrNull }?.map { Option(it, if (it == "auto") "auto (USB)" else it) } }.getOrNull().orEmpty(),
            )
            ServiceKind.SNMP -> emptyMap()
            ServiceKind.FTP -> mapOf("certificates" to runCatching {
                CertificatesApi(api).certificates().filter { it.kind == CertKind.CERTIFICATE }.map { Option(it.id.toString(), it.name) }
            }.getOrNull().orEmpty())
        }
    }

    companion object {
        fun sorted(list: List<ServiceInfo>): List<ServiceInfo> = list.sortedWith(
            compareBy<ServiceInfo> { s -> ServiceKind.of(s.service)?.ordinal ?: Int.MAX_VALUE }.thenBy { it.displayName.lowercase() },
        )

        /** `ups.driver_choices` keys look like `usbhid-ups$Model`; the value is "Maker Model (driver)". */
        internal fun upsDriverLabel(o: Option): String = o.label.takeIf { it != o.value } ?: o.value.orEmpty().replace('$', ' ')

        /** Services where stopping (or restarting) can cut off access to the NAS or its data. */
        val ACCESS_SERVICES = setOf("ssh", "cifs", "nfs", "iscsitarget", "nvmet", "ftp")

        /** Confirmation text for stopping or restarting [s]. */
        fun confirmText(s: ServiceInfo, verb: ServiceVerb): String {
            val name = s.displayName
            val impact = when (s.service) {
                "ssh" -> "Every SSH and SFTP session is closed and nobody can sign in over SSH, including you if that is how you reach the NAS"
                "cifs" -> "Windows and macOS computers lose their SMB shares, open files can lose unsaved changes, and Time Machine backups stop"
                "nfs" -> "NFS clients lose their mounts and programs using them may hang or fail"
                "iscsitarget" -> "Computers and VMs using iSCSI disks lose them, which can crash them or corrupt data"
                "nvmet" -> "Hosts using NVMe-oF namespaces lose them, which can crash them or corrupt data"
                "ftp" -> "FTP transfers in progress are cut off"
                "ups" -> "The NAS stops watching the UPS and won't shut down safely during a power cut"
                "snmp" -> "Monitoring tools stop getting data from the NAS"
                else -> "Anything using $name stops working"
            }
            return when (verb) {
                ServiceVerb.STOP -> "$impact. That lasts until $name is started again." +
                    if (s.enabledOnBoot) " It still starts on the next boot." else ""
                ServiceVerb.RESTART -> "$name is unavailable for a moment while it restarts, and connections in progress may drop."
                ServiceVerb.START -> "Start $name now?"
            }
        }
    }
}
