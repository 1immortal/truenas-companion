package app.truenascompanion.data.api

import app.truenascompanion.data.iscsi.IscsiData
import app.truenascompanion.data.iscsi.IscsiLogic
import app.truenascompanion.data.iscsi.WizardChap
import app.truenascompanion.data.iscsi.WizardExtent
import app.truenascompanion.data.iscsi.WizardForm
import app.truenascompanion.data.iscsi.WizardInitiators
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Steps of the "Share a block device" wizard, in the order they run. */
enum class WizardStep(val label: String) {
    ZVOL("Create the zvol"), EXTENT("Create the extent"), PORTAL("Create the portal"), INITIATORS("Create the initiator group"),
    CHAP("Create the CHAP user"), TARGET("Create the target"), LUN("Share the extent as LUN 0"),
}

enum class StepState { PENDING, RUNNING, DONE, FAILED, UNDONE, UNDO_FAILED, SKIPPED }

data class WizardResult(val ok: Boolean, val iqn: String? = null, val error: String? = null, val undoErrors: List<String> = emptyList())

/**
 * iSCSI (1.7.0), TrueNAS 25.10 (`plugins/iscsi_/`): `iscsi.global.config/update/sessions`, `iscsi.portal.*` (+
 * `listen_ip_choices`), `iscsi.initiator.*`, `iscsi.auth.*`, `iscsi.target.*`, `iscsi.extent.*`, `iscsi.targetextent.*`.
 * JSON-RPC over the WebSocket only.
 */
class IscsiApi(private val api: TrueNasApi) {

    private suspend fun list(method: String) = api.rpc(method).arr().orEmpty().mapNotNull { it.obj() }

    /** Everything at once; sessions, service state, listen choices and the HA check are optional. */
    suspend fun load(): IscsiData = coroutineScope {
        val global = async { IscsiLogic.global(api.rpc("iscsi.global.config").obj()) }
        val portals = async { list("iscsi.portal.query").mapNotNull(IscsiLogic::portal).sortedBy { it.tag } }
        val initiators = async { list("iscsi.initiator.query").mapNotNull(IscsiLogic::initiator).sortedBy { it.id } }
        val auths = async { list("iscsi.auth.query").mapNotNull(IscsiLogic::auth).sortedWith(compareBy({ it.tag }, { it.user })) }
        val targets = async { list("iscsi.target.query").mapNotNull(IscsiLogic::target).sortedBy { it.name } }
        val extents = async { list("iscsi.extent.query").mapNotNull(IscsiLogic::extent).sortedBy { it.name.lowercase() } }
        val luns = async { list("iscsi.targetextent.query").mapNotNull(IscsiLogic::lun) }
        val sessions = async { runCatching { sessions() }.getOrNull() }
        val service = async { runCatching { api.rpc("service.query", queryFilter(Triple("service", "=", JsonPrimitive("iscsitarget")))).arr()?.firstOrNull().obj() }.getOrNull() }
        val choices = async { runCatching { listenChoices() }.getOrDefault(emptyList()) }
        val datasets = async { runCatching { datasets() }.getOrDefault(emptyList()) }
        val ha = async { runCatching { api.rpc("failover.licensed").prim()?.booleanOrNull }.getOrNull() ?: false }
        val svc = service.await()
        IscsiData(
            global.await(), portals.await(), initiators.await(), auths.await(), targets.await(), extents.await(), luns.await(),
            sessions.await(), svc?.str("state")?.let { it == "RUNNING" }, svc?.bool("enable"), choices.await(), datasets.await(), ha.await(),
        )
    }

    suspend fun sessions() = list("iscsi.global.sessions").mapNotNull(IscsiLogic::session)

    suspend fun listenChoices(): List<String> = api.rpc("iscsi.portal.listen_ip_choices").obj()?.keys?.toList().orEmpty()

    suspend fun datasets() = api.rpc("pool.dataset.query", JsonArray(emptyList()), buildJsonObject {
        put("extra", buildJsonObject { put("flat", true); put("retrieve_children", false) })
    }).arr()?.mapNotNull { it.obj()?.let(Parsers::dataset) }?.sortedBy { it.id } ?: emptyList()

    suspend fun updateGlobal(body: JsonObject) { if (body.isNotEmpty()) api.rpc("iscsi.global.update", body) }

    private suspend fun create(ns: String, body: JsonObject): Int =
        api.rpc("$ns.create", body).obj()?.long("id")?.toInt() ?: throw TrueNasException.Rpc(0, "EINVAL", "TrueNAS didn't return the new $ns")

    private suspend fun update(ns: String, id: Int, body: JsonObject) { api.rpc("$ns.update", JsonPrimitive(id), body) }

    suspend fun save(ns: String, id: Int?, body: JsonObject): Int = if (id == null) create(ns, body) else { update(ns, id, body); id }

    suspend fun deletePortal(id: Int) { api.rpc("iscsi.portal.delete", JsonPrimitive(id)) }
    suspend fun deleteInitiator(id: Int) { api.rpc("iscsi.initiator.delete", JsonPrimitive(id)) }
    suspend fun deleteAuth(id: Int) { api.rpc("iscsi.auth.delete", JsonPrimitive(id)) }

    /** [deleteExtents] removes the extent entries too (their zvols and files stay); [force] even with connected initiators. */
    suspend fun deleteTarget(id: Int, force: Boolean, deleteExtents: Boolean) {
        api.rpc("iscsi.target.delete", JsonPrimitive(id), JsonPrimitive(force), JsonPrimitive(deleteExtents))
    }

    /** [remove] also deletes the file of a FILE extent (TrueNAS never deletes a zvol here). */
    suspend fun deleteExtent(id: Int, remove: Boolean, force: Boolean) {
        api.rpc("iscsi.extent.delete", JsonPrimitive(id), JsonPrimitive(remove), JsonPrimitive(force))
    }

    suspend fun deleteLun(id: Int, force: Boolean) { api.rpc("iscsi.targetextent.delete", JsonPrimitive(id), JsonPrimitive(force)) }

    suspend fun startService() {
        ServicesApi(api).setAutostart("iscsitarget", true)
        ServicesApi(api).control("iscsitarget", ServiceVerb.START)
    }

    private suspend fun fileExists(path: String) = runCatching { api.rpc("filesystem.stat", JsonPrimitive(path)).obj() != null }.getOrDefault(false)

    /**
     * Creates everything for [f] in order (zvol, extent, portal, initiators, CHAP, target, LUN). If a step fails, what was
     * created is deleted again in reverse order; undo problems are reported, not thrown.
     */
    suspend fun runWizard(f: WizardForm, d: IscsiData, onStep: (WizardStep, StepState) -> Unit = { _, _ -> }): WizardResult {
        val undo = ArrayDeque<Pair<WizardStep, suspend () -> Unit>>()
        var step = WizardStep.ZVOL
        fun skip(s: WizardStep) = onStep(s, StepState.SKIPPED)
        try {
            if (f.extent == WizardExtent.NEW_ZVOL) {
                onStep(step, StepState.RUNNING)
                val name = IscsiLogic.wizardZvolName(f)
                api.rpc("pool.dataset.create", IscsiLogic.wizardZvolJson(f))
                undo.addFirst(step to { api.rpc("pool.dataset.delete", JsonPrimitive(name), buildJsonObject { put("recursive", false); put("force", false) }) })
                onStep(step, StepState.DONE)
            } else skip(step)

            step = WizardStep.EXTENT
            onStep(step, StepState.RUNNING)
            if (f.extent == WizardExtent.FILE && fileExists(f.filePath.trim())) throw TrueNasException.Rpc(0, "EEXIST", "${f.filePath.trim()} already exists. Pick a new file name, or add an extent for the existing file.")
            val extentId = create("iscsi.extent", IscsiLogic.wizardExtentJson(f))
            val removeFile = f.extent == WizardExtent.FILE
            undo.addFirst(step to { deleteExtent(extentId, remove = removeFile, force = true) })
            onStep(step, StepState.DONE)

            step = WizardStep.PORTAL
            val portalId = if (f.newPortal) {
                onStep(step, StepState.RUNNING)
                val id = create("iscsi.portal", buildJsonObject {
                    put("comment", f.name.trim())
                    put("listen", JsonArray(f.portalIps.map { buildJsonObject { put("ip", it) } }))
                })
                undo.addFirst(step to { deletePortal(id) })
                onStep(step, StepState.DONE); id
            } else { skip(step); f.portalId!! }

            step = WizardStep.INITIATORS
            val initiatorId = when (f.initiators) {
                WizardInitiators.ALL -> { skip(step); null }
                WizardInitiators.EXISTING -> { skip(step); f.initiatorGroupId }
                WizardInitiators.LIST -> {
                    onStep(step, StepState.RUNNING)
                    val id = create("iscsi.initiator", IscsiLogic.initiatorsListJson(f))
                    undo.addFirst(step to { deleteInitiator(id) })
                    onStep(step, StepState.DONE); id
                }
            }

            step = WizardStep.CHAP
            val tag = when (f.chap) {
                WizardChap.NONE -> { skip(step); null }
                WizardChap.EXISTING -> { skip(step); f.authTag }
                WizardChap.NEW -> {
                    onStep(step, StepState.RUNNING)
                    val t = IscsiLogic.nextAuthTag(d)
                    val id = create("iscsi.auth", IscsiLogic.wizardAuthJson(f, t))
                    undo.addFirst(step to { deleteAuth(id) })
                    onStep(step, StepState.DONE); t
                }
            }

            step = WizardStep.TARGET
            onStep(step, StepState.RUNNING)
            val targetId = create("iscsi.target", IscsiLogic.wizardTargetJson(f, portalId, initiatorId, tag))
            undo.addFirst(step to { deleteTarget(targetId, force = true, deleteExtents = false) })
            onStep(step, StepState.DONE)

            step = WizardStep.LUN
            onStep(step, StepState.RUNNING)
            create("iscsi.targetextent", buildJsonObject { put("target", targetId); put("extent", extentId); put("lunid", 0) })
            onStep(step, StepState.DONE)
            return WizardResult(true, IscsiLogic.iqn(d.global.basename, f.name.trim()))
        } catch (e: Throwable) {
            onStep(step, StepState.FAILED)
            val problems = mutableListOf<String>()
            for ((s, u) in undo) {
                try { u(); onStep(s, StepState.UNDONE) } catch (x: Throwable) {
                    onStep(s, StepState.UNDO_FAILED); problems += "${s.label}: ${x.userMessage()}"
                }
            }
            return WizardResult(false, error = "${step.label} failed: ${e.userMessage()}", undoErrors = problems)
        }
    }
}
