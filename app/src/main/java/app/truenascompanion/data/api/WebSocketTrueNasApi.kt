package app.truenascompanion.data.api

import app.truenascompanion.data.model.AlertItem
import app.truenascompanion.data.model.ApiFlavor
import app.truenascompanion.data.model.AppAction
import app.truenascompanion.data.model.AppInfo
import app.truenascompanion.data.model.LogLine
import app.truenascompanion.data.model.CatalogAppDetails
import app.truenascompanion.data.model.CatalogApp
import app.truenascompanion.data.model.AppStats
import app.truenascompanion.data.model.AppEditData
import app.truenascompanion.data.model.AppUpgradeSummary
import app.truenascompanion.data.model.JobInfo
import app.truenascompanion.data.model.Dataset
import app.truenascompanion.data.model.Disk
import app.truenascompanion.data.model.Pool
import app.truenascompanion.data.model.RealtimeStats
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.model.ServiceInfo
import app.truenascompanion.data.model.SystemInfo
import app.truenascompanion.data.net.PinningTrustManager
import app.truenascompanion.util.UrlUtils
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

private fun params(vararg items: JsonElement) = JsonArray(items.toList())
private fun p(s: String) = JsonPrimitive(s)
private fun p(i: Int) = JsonPrimitive(i)
private const val MAX_JOBS = 60

/** TrueNAS 25.04+ JSON-RPC 2.0 WebSocket API (`wss://host/api/current`). */
class WebSocketTrueNasApi internal constructor(private val rpc: JsonRpcClient) : TrueNasApi {

    override val flavor = ApiFlavor.WEBSOCKET
    override val supportsRealtime = true
    override val isAlive: Boolean get() = rpc.isOpen

    @Volatile
    private var cachedPhysmem: Long? = null

    companion object {
        suspend fun connect(client: OkHttpClient, tm: PinningTrustManager, server: ServerConfig, apiKey: String): WebSocketTrueNasApi {
            val rpc = JsonRpcClient(client, UrlUtils.webSocketUrl(server.url), tm)
            rpc.open()
            try {
                login(rpc, server, apiKey)
            } catch (e: Throwable) {
                rpc.close()
                throw e
            }
            return WebSocketTrueNasApi(rpc)
        }

        private suspend fun login(rpc: JsonRpcClient, server: ServerConfig, apiKey: String) {
            // 25.04 / 25.10: auth.login_with_api_key(api_key) -> Boolean. Deprecated, removed in v27.
            val ok = try {
                rpc.call("auth.login_with_api_key", params(p(apiKey))).prim()?.booleanOrNull ?: false
            } catch (e: Throwable) {
                if (!e.isMethodMissing()) throw e
                // v27+: auth.login_ex({"mechanism": "API_KEY_PLAIN", "username": ..., "api_key": ...})
                val res = rpc.call("auth.login_ex", params(buildJsonObject {
                    put("mechanism", "API_KEY_PLAIN")
                    put("username", server.username.ifBlank { "truenas_admin" })
                    put("api_key", apiKey)
                })).obj()
                when (res?.str("response_type")) {
                    "SUCCESS" -> true
                    "OTP_REQUIRED" -> throw TrueNasException.AuthFailed("This account requires a one-time password, which API key login does not support.")
                    "EXPIRED" -> throw TrueNasException.AuthFailed("This API key has expired. Create a new one in TrueNAS.")
                    else -> false
                }
            }
            if (!ok) throw TrueNasException.AuthFailed(API_KEY_REJECTED_MESSAGE)
        }
    }

    private suspend fun call(method: String, vararg args: JsonElement): JsonElement = rpc.call(method, params(*args))

    override suspend fun rpc(method: String, vararg args: JsonElement): JsonElement = call(method, *args)

    /** Calls a middleware "job" method and waits for it to finish via core.get_jobs polling (one implementation: Jobs.kt). */
    private suspend fun callJob(method: String, vararg args: JsonElement, timeoutMs: Long = 180_000): JsonElement? {
        val first = call(method, *args)
        val jobId = first.asJobId() ?: return first
        return awaitJob(jobId, method, timeoutMs)
    }

    override suspend fun systemInfo(): SystemInfo =
        Parsers.systemInfo(call("system.info").obj() ?: JsonObject(emptyMap())).also { cachedPhysmem = it.physicalMemory }

    override fun realtimeStats(): Flow<RealtimeStats> = channelFlow {
        launch(start = CoroutineStart.UNDISPATCHED) {
            rpc.events.collect { ev ->
                if (ev.str("collection")?.startsWith("reporting.realtime") == true) {
                    ev["fields"].obj()?.let { send(Parsers.realtime(it, cachedPhysmem)) }
                }
            }
        }
        val subId = call("core.subscribe", p("reporting.realtime"))
        launch { // end the flow when the socket dies so the repository reconnects (no polling needed)
            rpc.closed.await()
            close(TrueNasException.NotConnected())
        }
        awaitClose { runCatching { rpc.notify("core.unsubscribe", params(subId)) } }
    }

    override suspend fun pools(): List<Pool> = call("pool.query").arr()?.mapNotNull { it.obj()?.let(Parsers::pool) } ?: emptyList()

    override suspend fun disks(): List<Disk> {
        val poolByDisk = runCatching { pools() }.getOrDefault(emptyList())
            .flatMap { pool -> pool.diskNames.map { it to pool.name } }.toMap()
        return call("disk.query").arr()?.mapNotNull { it.obj()?.let { o -> Parsers.disk(o, poolByDisk) } }
            ?.sortedBy { it.name } ?: emptyList()
    }

    override suspend fun diskNames(): List<String> {
        val options = buildJsonObject { put("select", JsonArray(listOf(p("name")))) }
        return call("disk.query", JsonArray(emptyList()), options).arr()
            ?.mapNotNull { it.obj()?.get("name")?.jsonPrimitive?.contentOrNull }?.sorted() ?: emptyList()
    }

    override suspend fun diskTemperatures(names: List<String>): Map<String, Double> {
        if (names.isEmpty()) return emptyMap()
        val list = JsonArray(names.map { p(it) })
        return call("disk.temperatures", list).obj()?.let(Parsers::diskTemperatures) ?: emptyMap()
    }

    override suspend fun datasets(): List<Dataset> {
        val options = buildJsonObject {
            put("extra", buildJsonObject { put("flat", true); put("retrieve_children", false) })
        }
        return call("pool.dataset.query", JsonArray(emptyList()), options).arr()
            ?.mapNotNull { it.obj()?.let(Parsers::dataset) }?.sortedBy { it.id } ?: emptyList()
    }

    @Volatile
    private var legacyApps: Boolean? = null

    override suspend fun apps(): List<AppInfo> {
        if (legacyApps != true) {
            try {
                return call("app.query").arr()?.mapNotNull { it.obj()?.let(Parsers::app) }?.sortedBy { it.name.lowercase() }
                    .also { legacyApps = false } ?: emptyList()
            } catch (e: Throwable) {
                if (!e.isMethodMissing()) throw e
                legacyApps = true
            }
        }
        return call("chart.release.query").arr()?.mapNotNull { it.obj()?.let(Parsers::chartRelease) }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    override suspend fun appAction(app: AppInfo, action: AppAction) {
        if (app.legacyChart) {
            fun scale(replicas: Int) = buildJsonObject { put("replica_count", replicas) }
            when (action) {
                AppAction.START -> callJob("chart.release.scale", p(app.name), scale(1))
                AppAction.STOP -> callJob("chart.release.scale", p(app.name), scale(0))
                AppAction.RESTART -> { callJob("chart.release.scale", p(app.name), scale(0)); callJob("chart.release.scale", p(app.name), scale(1)) }
                AppAction.REDEPLOY -> callJob("chart.release.redeploy", p(app.name))
                AppAction.PULL_REDEPLOY -> callJob(
                    "chart.release.pull_container_images", p(app.name), buildJsonObject { put("redeploy", true) }, timeoutMs = PULL_TIMEOUT_MS,
                )
            }
            return
        }
        when (action) {
            AppAction.START -> callJob("app.start", p(app.name))
            AppAction.STOP -> callJob("app.stop", p(app.name))
            AppAction.RESTART -> { callJob("app.stop", p(app.name)); callJob("app.start", p(app.name)) }
            AppAction.REDEPLOY -> callJob("app.redeploy", p(app.name))
            // Redeploy alone runs `compose up --force-recreate` with the default "missing" pull policy, so an image
            // rebuilt under the same tag is not fetched. app.pull_images runs `compose pull --policy always`, clears
            // the image-update flag and then redeploys (middlewared plugins/apps/pull_images.py, 25.10).
            AppAction.PULL_REDEPLOY -> callJob("app.pull_images", p(app.name), buildJsonObject { put("redeploy", true) }, timeoutMs = PULL_TIMEOUT_MS)
        }
    }

    override suspend fun alerts(): List<AlertItem> =
        call("alert.list").arr()?.mapNotNull { it.obj()?.let(Parsers::alert) }
            ?.sortedByDescending { it.datetimeMillis ?: 0 } ?: emptyList()

    override suspend fun dismissAlert(uuid: String) {
        call("alert.dismiss", p(uuid))
    }

    override suspend fun alertClassTitles(): Map<String, String> = runCatching {
        call("alert.list_categories").arr().orEmpty().flatMap { cat ->
            cat.obj()?.get("classes").arr().orEmpty().mapNotNull { c ->
                val o = c.obj() ?: return@mapNotNull null
                val id = o.str("id") ?: return@mapNotNull null
                val title = o.str("title") ?: return@mapNotNull null
                id to title
            }
        }.toMap()
    }.getOrDefault(emptyMap())

    override fun alertEvents(): Flow<Unit> = channelFlow {
        launch(start = CoroutineStart.UNDISPATCHED) {
            rpc.events.collect { ev -> if (ev.str("collection") == "alert.list") send(Unit) }
        }
        val subId = call("core.subscribe", p("alert.list"))
        launch {
            rpc.closed.await()
            close(TrueNasException.NotConnected())
        }
        awaitClose { runCatching { rpc.notify("core.unsubscribe", params(subId)) } }
    }

    override suspend fun services(): List<ServiceInfo> =
        call("service.query").arr()?.mapNotNull { it.obj()?.let(Parsers::service) }?.sortedBy { it.displayName } ?: emptyList()

    override suspend fun serviceAction(service: String, start: Boolean) {
        val result = try {
            // 25.04+: service.control(verb, service, options) is a job.
            callJob("service.control", p(if (start) "START" else "STOP"), p(service), buildJsonObject { put("silent", false) })
        } catch (e: Throwable) {
            if (!e.isMethodMissing()) throw e
            // Older releases: service.start / service.stop (deprecated in 25.x, removed in 26).
            call(if (start) "service.start" else "service.stop", p(service))
        }
        if (result?.prim()?.booleanOrNull == false) {
            throw TrueNasException.JobFailed("TrueNAS could not ${if (start) "start" else "stop"} $service. Check its configuration in the web UI.")
        }
    }

    private suspend fun power(method: String, reason: String) {
        // 25.04+: system.reboot/shutdown(reason, options) are jobs; we don't wait since the server goes away.
        try {
            call(method, p(reason))
        } catch (e: Throwable) {
            if (e is TrueNasException.Rpc && e.code != -32001) call(method) else throw e
        }
    }

    override suspend fun reboot() = power("system.reboot", "Reboot requested from TrueNAS Companion (Android)")
    override suspend fun shutdown() = power("system.shutdown", "Shutdown requested from TrueNAS Companion (Android)")

    /**
     * `auth.generate_token(ttl, attrs, match_origin, single_use)`. We request a reusable (single_use=false) token that is
     * not bound to the client address (match_origin=false) because phones change networks and sit behind proxies.
     * Throws if the server refuses (e.g. STIG mode, or the original password + 2FA sign-in has expired).
     */
    suspend fun mintToken(ttlSeconds: Long): String =
        call("auth.generate_token", JsonPrimitive(ttlSeconds), JsonObject(emptyMap()), JsonPrimitive(false), JsonPrimitive(false))
            .prim()?.takeIf { it.isString }?.content
            ?: throw TrueNasException.Unsupported("TrueNAS did not issue a session token")

    /** [mintToken], or null if the server refuses. */
    suspend fun generateToken(ttlSeconds: Long): String? = try {
        mintToken(ttlSeconds)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    // --- App upgrades & jobs ---

    /** Calls a job method without waiting and returns the job id. */
    private suspend fun startJob(method: String, vararg args: JsonElement): Long =
        call(method, *args).prim()?.takeUnless { it.isString }?.longOrNull
            ?: throw TrueNasException.JobFailed("$method did not return a job id")

    override suspend fun appUpgradeSummary(app: AppInfo): AppUpgradeSummary {
        val res = call("app.upgrade_summary", p(app.name), buildJsonObject { put("app_version", "latest") }).obj()
            ?: JsonObject(emptyMap())
        return Parsers.upgradeSummary(res, app.version)
    }

    override suspend fun startAppUpgrade(app: AppInfo, snapshotHostPaths: Boolean): Long {
        if (app.legacyChart) throw TrueNasException.Unsupported("Upgrading legacy Kubernetes apps isn't supported. Use the TrueNAS web UI.")
        return startJob("app.upgrade", p(app.name), buildJsonObject {
            put("app_version", "latest")
            put("snapshot_hostpaths", snapshotHostPaths)
        })
    }

    override suspend fun startCatalogSync(): Long = startJob("catalog.sync")

    override suspend fun abortJob(id: Long) {
        call("core.job_abort", JsonPrimitive(id))
    }

    override fun jobs(): Flow<List<JobInfo>> = channelFlow {
        val jobs = HashMap<Long, JsonObject>()
        val lock = Mutex()
        suspend fun emitSnapshot() {
            val list = jobs.values.mapNotNull(Parsers::job).sortedByDescending { it.id }.take(MAX_JOBS)
            send(list)
        }
        // Subscribe first so no update is lost between the snapshot query and the subscription.
        launch(start = CoroutineStart.UNDISPATCHED) {
            rpc.events.collect { ev ->
                if (ev.str("collection") != "core.get_jobs") return@collect
                val fields = ev["fields"].obj()
                val id = ev.long("id") ?: fields?.long("id") ?: return@collect
                lock.withLock {
                    when (ev.str("msg")?.lowercase()) {
                        "removed" -> jobs.remove(id)
                        else -> if (fields != null) jobs[id] = JsonObject((jobs[id] ?: emptyMap()) + fields)
                    }
                    emitSnapshot()
                }
            }
        }
        launch { // end the flow when the socket dies so the repository can reconnect (was a 3 s polling loop)
            rpc.closed.await()
            close(TrueNasException.NotConnected())
        }
        val subId = call("core.subscribe", p("core.get_jobs"))
        val initial = call("core.get_jobs", JsonArray(emptyList()), buildJsonObject {
            put("order_by", buildJsonArray { add(p("-id")) })
            put("limit", MAX_JOBS)
        }).arr().orEmpty()
        lock.withLock {
            initial.forEach { e -> e.obj()?.let { o -> o.long("id")?.let { id -> jobs.putIfAbsent(id, o) } } }
            emitSnapshot()
        }
        awaitClose { runCatching { rpc.notify("core.unsubscribe", params(subId)) } }
    }

    // --- Apps batch (method names verified against the 25.10.3 middleware source) ---

    override suspend fun catalogApps(): List<CatalogApp> =
        call("app.available").arr()?.mapNotNull { it.obj()?.let { o -> Parsers.catalogApp(o) } } ?: emptyList()

    override suspend fun catalogAppDetails(name: String, train: String): CatalogAppDetails {
        val o = call("catalog.get_app_details", p(name), buildJsonObject { put("train", train) }).obj()
            ?: throw TrueNasException.Rpc(0, null, "No details for $name")
        return Parsers.catalogDetails(o, train) ?: throw TrueNasException.Rpc(0, null, "$name has no installable version")
    }

    override suspend fun startAppInstall(catalogApp: String, appName: String, train: String, version: String, values: JsonObject): Long =
        startJob("app.create", buildJsonObject {
            put("custom_app", false)
            put("catalog_app", catalogApp)
            put("app_name", appName)
            put("train", train)
            put("version", version)
            put("values", values)
        })

    override suspend fun appEditData(appName: String): AppEditData {
        val filter = buildJsonArray { add(buildJsonArray { add(p("name")); add(p("=")); add(p(appName)) }) }
        val options = buildJsonObject { put("extra", buildJsonObject { put("include_app_schema", true); put("retrieve_config", true) }) }
        val o = call("app.query", filter, options).arr()?.firstOrNull()?.obj() ?: throw TrueNasException.Rpc(0, "ENOENT", "App $appName not found")
        val custom = o.bool("custom_app") ?: false
        return AppEditData(
            app = appName,
            values = o["config"].obj() ?: JsonObject(emptyMap()),
            schema = o["version_details"].obj()?.get("schema").obj(),
            customApp = custom,
        )
    }

    override suspend fun startAppUpdate(appName: String, values: JsonObject, customApp: Boolean): Long =
        startJob("app.update", p(appName), buildJsonObject {
            if (customApp) put("custom_compose_config", values) else put("values", values)
        })

    override suspend fun startAppDelete(appName: String, removeVolumes: Boolean): Long =
        startJob("app.delete", p(appName), buildJsonObject {
            put("remove_images", true)
            put("remove_ix_volumes", removeVolumes)
        })

    override suspend fun appRollbackVersions(appName: String): List<String> =
        call("app.rollback_versions", p(appName)).arr()?.mapNotNull { it.prim()?.contentOrNull }
            ?.sortedWith { a, b -> Parsers.compareVersions(b, a) } ?: emptyList()

    override suspend fun startAppRollback(appName: String, version: String, snapshot: Boolean): Long =
        startJob("app.rollback", p(appName), buildJsonObject {
            put("app_version", version)
            put("rollback_snapshot", snapshot)
        })

    // --- Virtualization ---

    override suspend fun vms(): List<app.truenascompanion.data.model.VmInfo> =
        call("vm.query", JsonArray(emptyList()), buildJsonObject { put("order_by", JsonArray(listOf(JsonPrimitive("name")))) })
            .arr()?.mapNotNull { it.obj()?.let(Parsers::vm) } ?: emptyList()

    override suspend fun vmStart(id: Int, overcommit: Boolean) {
        call("vm.start", p(id), buildJsonObject { put("overcommit", overcommit) })
    }

    override suspend fun startVmStop(id: Int): Long =
        startJob("vm.stop", p(id), buildJsonObject { put("force", false); put("force_after_timeout", false) })

    override suspend fun vmPowerOff(id: Int) { call("vm.poweroff", p(id)) }

    override suspend fun startVmRestart(id: Int): Long = startJob("vm.restart", p(id))

    override suspend fun vmDelete(id: Int, deleteZvols: Boolean) {
        call("vm.delete", p(id), buildJsonObject { put("zvols", deleteZvols); put("force", false) })
    }

    override suspend fun vmUpdateResources(id: Int, vcpus: Int, cores: Int, threads: Int, memoryMb: Long, autostart: Boolean, description: String) {
        call("vm.update", p(id), buildJsonObject {
            put("vcpus", vcpus); put("cores", cores); put("threads", threads); put("memory", memoryMb)
            put("autostart", autostart); put("description", description)
        })
    }

    override suspend fun vmCreate(request: app.truenascompanion.data.model.VmCreateRequest): Int {
        val r = request
        val vm = call("vm.create", buildJsonObject {
            put("name", r.name); put("description", r.description)
            put("vcpus", r.vcpus); put("cores", r.cores); put("threads", r.threads); put("memory", r.memoryMb)
            put("bootloader", r.bootloader); put("autostart", r.autostart)
        }).obj()
        val id = vm?.long("id")?.toInt() ?: throw TrueNasException.Rpc(0, null, "vm.create did not return an id")
        fun device(order: Int, attrs: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
            put("vm", id); put("order", order); put("attributes", buildJsonObject(attrs))
        }
        val devices = buildList {
            if (r.isoPath != null) add(device(1000) { put("dtype", "CDROM"); put("path", r.isoPath) })
            if (r.diskParent != null) add(device(1001) {
                put("dtype", "DISK"); put("type", "VIRTIO"); put("create_zvol", true)
                put("zvol_name", "${r.diskParent.trimEnd('/')}/${r.name}-disk0")
                put("zvol_volsize", r.diskSizeGiB.toLong() * 1024 * 1024 * 1024)
            })
            if (r.nicAttach != null) add(device(1002) { put("dtype", "NIC"); put("type", "VIRTIO"); put("nic_attach", r.nicAttach) })
            if (!r.displayPassword.isNullOrBlank()) add(device(1003) {
                put("dtype", "DISPLAY"); put("type", "SPICE"); put("bind", "0.0.0.0"); put("web", true); put("password", r.displayPassword)
            })
        }
        val failures = mutableListOf<String>()
        for (d in devices) {
            try { call("vm.device.create", d) }
            catch (e: TrueNasException) { failures += "${d["attributes"].obj()?.str("dtype")?.lowercase()}: ${e.message}" }
        }
        if (failures.isNotEmpty()) throw TrueNasException.Rpc(0, null, "VM ${r.name} was created, but some devices failed (${failures.joinToString("; ")}). Fix them in the TrueNAS web UI.")
        return id
    }

    override suspend fun vmDisplayUri(id: Int, host: String, https: Boolean): String? {
        val res = call("vm.get_display_web_uri", p(id), p(host), buildJsonObject { put("protocol", if (https) "HTTPS" else "HTTP") }).obj()
        res?.str("error")?.let { throw TrueNasException.Rpc(0, null, it) }
        return res?.str("uri")
    }

    override suspend fun nicAttachChoices(): List<String> =
        call("vm.device.nic_attach_choices").obj()?.keys?.sorted() ?: emptyList()

    override suspend fun listDir(path: String): List<app.truenascompanion.data.model.FsEntry> =
        call("filesystem.listdir", p(path), JsonArray(emptyList()), buildJsonObject { put("order_by", JsonArray(listOf(JsonPrimitive("name")))) })
            .arr()?.mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                val name = o.str("name") ?: return@mapNotNull null
                if (name.startsWith(".")) return@mapNotNull null
                app.truenascompanion.data.model.FsEntry(name, o.str("path") ?: "$path/$name", o.str("type") == "DIRECTORY")
            } ?: emptyList()

    override suspend fun zvolParents(): List<String> =
        call("pool.dataset.query", JsonArray(listOf(JsonArray(listOf(p("type"), p("="), p("FILESYSTEM"))))),
            buildJsonObject { put("extra", buildJsonObject { put("flat", true); put("retrieve_children", true); put("properties", JsonArray(listOf(p("name")))) }); put("order_by", JsonArray(listOf(p("name")))) })
            .arr()?.mapNotNull { it.obj()?.str("id") }
            ?.filterNot { id -> id.contains("/ix-") || id.contains("/.") || id.substringAfter('/', "").startsWith("ix-") }
            ?: emptyList()

    override suspend fun containersState(): String? = call("virt.global.config").obj()?.str("state")

    override suspend fun shellToken(): String =
        (call("auth.generate_token", JsonPrimitive(300), JsonObject(emptyMap()), JsonPrimitive(true), JsonPrimitive(true)) as? JsonPrimitive)
            ?.contentOrNull?.takeIf { it.isNotBlank() } ?: throw TrueNasException.Unsupported("The NAS did not return a shell token.")

    override suspend fun resizeShell(id: String, cols: Int, rows: Int) {
        call("core.resize_shell", p(id), p(cols), p(rows))
    }

    override suspend fun appShellContainers(appName: String): Map<String, String> =
        call("app.container_console_choices", p(appName)).obj()?.mapValues { (id, v) ->
            (v.obj()?.get("service_name") as? JsonPrimitive)?.contentOrNull ?: id.take(12)
        } ?: emptyMap()

    override suspend fun virtInstances(): List<app.truenascompanion.data.model.VirtInstance> =
        call("virt.instance.query").arr()?.mapNotNull { it.obj()?.let(Parsers::virtInstance) }?.sortedBy { it.name.lowercase() } ?: emptyList()

    override suspend fun startVirtStart(id: String): Long = startJob("virt.instance.start", p(id))
    override suspend fun startVirtStop(id: String, force: Boolean): Long =
        startJob("virt.instance.stop", p(id), buildJsonObject { put("timeout", if (force) -1 else 60); put("force", force) })
    override suspend fun startVirtRestart(id: String): Long =
        startJob("virt.instance.restart", p(id), buildJsonObject { put("timeout", 60); put("force", false) })
    override suspend fun startVirtDelete(id: String): Long = startJob("virt.instance.delete", p(id))

    override fun appLogs(appName: String, containerId: String, tail: Int): Flow<LogLine> =
        eventSource("app.container_log_follow", buildJsonObject {
            put("app_name", appName); put("container_id", containerId); put("tail_lines", tail)
        }) { fields -> fields.obj()?.let { LogLine(it.str("data").orEmpty().trimEnd('\n', '\r'), it.str("timestamp")) } }

    override fun appStats(intervalSeconds: Int): Flow<List<AppStats>> =
        eventSource("app.stats", buildJsonObject { put("interval", intervalSeconds.coerceAtLeast(2)) }) { fields ->
            fields.arr()?.mapNotNull { it.obj()?.let(Parsers::appStats) }
        }

    /**
     * Subscribes to a middleware event source (`core.subscribe("name:{json args}")`). Ends when the server
     * unsubscribes us (e.g. the container stopped) or the socket dies; unsubscribes when the collector goes away.
     */
    private fun <T : Any> eventSource(name: String, args: JsonObject, map: (JsonElement) -> T?): Flow<T> = channelFlow {
        val full = "$name:$args"
        launch(start = CoroutineStart.UNDISPATCHED) {
            rpc.events.collect { ev ->
                if (ev.str("collection") != full) return@collect
                if (ev.containsKey("msg")) {
                    ev["fields"]?.let(map)?.let { send(it) }
                } else { // notify_unsubscribed
                    val err = ev["error"].obj()
                    close(err?.let { TrueNasException.Rpc(0, it.str("errname"), it.str("reason") ?: "Stream ended") })
                }
            }
        }
        val subId = call("core.subscribe", p(full))
        launch { rpc.closed.await(); close(TrueNasException.NotConnected()) }
        awaitClose { runCatching { rpc.notify("core.unsubscribe", params(subId)) } }
    }.buffer(kotlinx.coroutines.channels.Channel.UNLIMITED) // never slow the shared event flow down

    override fun close() = rpc.close()
}

/**
 * Job status polling: quick at first (most actions finish in a few seconds), then gradually slower up to 5 s, so a
 * 20-minute image pull costs ~250 small requests instead of ~1200.
 */
internal fun jobPollDelayMs(poll: Int): Long = when {
    poll < 5 -> 1_000L
    poll < 15 -> 2_000L
    poll < 30 -> 3_000L
    else -> 5_000L
}

/** Large images (e.g. immich) can take many minutes to pull; middlewared allows compose 20 min. */
private const val PULL_TIMEOUT_MS = 25 * 60_000L
