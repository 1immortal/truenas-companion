package app.truenascompanion

import app.truenascompanion.data.api.FieldError
import app.truenascompanion.data.api.ReplicationApi
import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.cloud.CloudRunWatch
import app.truenascompanion.data.model.CronSchedule
import app.truenascompanion.data.replication.ReadonlyPolicy
import app.truenascompanion.data.replication.ReplDirection
import app.truenascompanion.data.replication.ReplTiming
import app.truenascompanion.data.replication.ReplTransport
import app.truenascompanion.data.replication.ReplicationForm
import app.truenascompanion.data.replication.ReplicationLogic
import app.truenascompanion.data.replication.Retention
import app.truenascompanion.data.replication.SnapshotTaskRef
import app.truenascompanion.data.replication.SshConnection
import app.truenascompanion.notify.AlertTarget
import app.truenascompanion.notify.DeepLink
import app.truenascompanion.ui.replication.KeyPairForm
import app.truenascompanion.ui.replication.SshConnForm
import app.truenascompanion.ui.replication.SshForms
import app.truenascompanion.ui.replication.keyPairErrors
import app.truenascompanion.ui.tasks.splitFieldErrors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** 1.6.0: replication tasks, SSH connections and key pairs (TrueNAS 25.10 shapes from the middleware source; example data only). */
class V160ReplicationTest {
    private val calls = mutableListOf<Pair<String, List<JsonElement>>>()
    private var responder: (String, List<JsonElement>) -> JsonElement = { _, _ -> JsonNull }

    @Suppress("UNCHECKED_CAST")
    private val api = Proxy.newProxyInstance(TrueNasApi::class.java.classLoader, arrayOf(TrueNasApi::class.java)) { proxy, m, args ->
        when (m.name) {
            "rpc" -> {
                val method = args!![0] as String
                val params = (args[1] as Array<JsonElement>).toList()
                calls += method to params
                try {
                    responder(method, params)
                } catch (e: Throwable) {
                    (args.last() as kotlin.coroutines.Continuation<Any?>).resumeWith(Result.failure(e))
                    kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
                }
            }
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            "toString" -> "fake"
            else -> null
        }
    } as TrueNasApi

    private fun j(s: String) = Json.parseToJsonElement(s)
    private fun o(s: String) = j(s).jsonObject

    private val snapTask = """{"id":3,"dataset":"tank/photos","recursive":true,"lifetime_value":2,"lifetime_unit":"WEEK","enabled":true,
      "exclude":[],"naming_schema":"auto-%Y-%m-%d_%H-%M","allow_empty":true,"schedule":{"minute":"0","hour":"*","dom":"*","month":"*","dow":"*","begin":"00:00","end":"23:59"}}"""

    private val pushTask = """{"id":12,"name":"Photos to backup NAS","direction":"PUSH","transport":"SSH",
      "ssh_credentials":{"id":5,"name":"Backup NAS","type":"SSH_CREDENTIALS","attributes":{"host":"nas2.example.com","port":22,"username":"root","private_key":4,"remote_host_key":"ssh-ed25519 AAAAexample","connect_timeout":10}},
      "netcat_active_side":null,"netcat_active_side_listen_address":null,"netcat_active_side_port_min":null,"netcat_active_side_port_max":null,"netcat_passive_side_connect_address":null,
      "sudo":false,"source_datasets":["tank/photos"],"target_dataset":"backup/photos","recursive":true,"exclude":["tank/photos/cache"],
      "properties":true,"properties_exclude":["mountpoint"],"properties_override":{"compression":"zstd"},"replicate":false,
      "encryption":false,"encryption_inherit":null,"encryption_key":null,"encryption_key_format":null,"encryption_key_location":null,
      "periodic_snapshot_tasks":[$snapTask],"naming_schema":[],"also_include_naming_schema":[],"name_regex":null,"auto":true,"schedule":null,
      "restrict_schedule":{"minute":"0","hour":"*","dom":"*","month":"*","dow":"*","begin":"22:00","end":"06:00"},"only_matching_schedule":false,
      "allow_from_scratch":false,"readonly":"SET","hold_pending_snapshots":false,"retention_policy":"SOURCE","lifetime_value":null,"lifetime_unit":null,"lifetimes":[],
      "compression":"LZ4","speed_limit":2097152,"large_block":true,"embed":false,"compressed":true,"retries":5,"logging_level":null,"enabled":true,
      "state":{"state":"RUNNING","datetime":{"${'$'}date":1791540000000},"progress":{"dataset":"tank/photos","snapshot":"auto-2026-10-09_10-00","snapshots_sent":1,"snapshots_total":4,"bytes_sent":1048576,"bytes_total":4194304}},
      "job":null,"has_encrypted_dataset_keys":false}"""

    private fun task() = ReplicationLogic.task(o(pushTask))!!
    private val snapRef get() = ReplicationLogic.snapshotTaskRef(o(snapTask))!!

    // ---------------- parsing ----------------

    @Test fun queryEntryParses() {
        val t = task()
        assertEquals(12, t.id)
        assertEquals(5, t.sshCredentialsId)
        assertEquals("Backup NAS", t.sshCredentialsName)
        assertEquals(listOf(3), t.periodicSnapshotTasks.map { it.id })
        assertTrue(t.running)
        assertFalse(t.failed)
        assertEquals("RUNNING", t.state!!.state)
        assertEquals(1791540000000L, t.state!!.atMillis)
        assertEquals(25.0, ReplicationLogic.percent(t)!!, 0.01)
        assertTrue(ReplicationLogic.progressText(t)!!.contains("(2 of 4)"))
        assertEquals("tank/photos\n→ Backup NAS: backup/photos", ReplicationLogic.route(t))
        assertEquals("After the snapshot task", ReplicationLogic.whenText(t))
    }

    @Test fun errorStateIsFailedAndPendingFallsBackToTheJob() {
        val failed = ReplicationLogic.task(o(pushTask.replace("\"state\":\"RUNNING\"", "\"state\":\"ERROR\",\"error\":\"No incremental base\"")))!!
        assertTrue(failed.failed)
        assertEquals("No incremental base", ReplicationLogic.lastState(failed)!!.error)
        val pending = ReplicationLogic.task(o(pushTask.replace("\"state\":{\"state\":\"RUNNING\"", "\"state\":{\"state\":\"PENDING\"")
            .replace("\"job\":null", "\"job\":{\"id\":77,\"state\":\"SUCCESS\",\"time_finished\":{\"${'$'}date\":1791540000000}}")))!!
        assertEquals("FINISHED", ReplicationLogic.lastState(pending)!!.state)
        assertEquals(77L, pending.jobId)
    }

    @Test fun formRoundTripsTheStoredTask() {
        val f = ReplicationLogic.form(task())
        assertEquals(ReplTiming.AFTER_SNAPSHOTS, f.timing)
        assertEquals(setOf(3), f.snapshotTaskIds)
        assertEquals("2048", f.speedLimit)
        assertEquals("compression=zstd", f.propertiesOverride)
        assertEquals("tank/photos/cache", f.exclude)
        val body = ReplicationLogic.taskJson(f)
        assertEquals(JsonPrimitive(true), body["auto"])
        assertEquals(JsonNull, body["schedule"])
        assertEquals(j("[3]"), body["periodic_snapshot_tasks"])
        assertEquals(JsonPrimitive(5), body["ssh_credentials"])
        assertEquals(JsonPrimitive(2097152), body["speed_limit"])
        assertEquals(JsonPrimitive("LZ4"), body["compression"])
        assertEquals(o("""{"compression":"zstd"}"""), body["properties_override"])
        assertEquals(JsonArray(emptyList()), body["lifetimes"])
        // Not shown in the editor; update merges so these stay as stored.
        listOf("restrict_schedule", "embed", "logging_level").forEach { assertFalse(it, it in body) }
        assertTrue(ReplicationLogic.errors(f, listOf(snapRef)).isEmpty())
    }

    @Test fun scheduleSendsCronWithWindow() {
        val f = ReplicationLogic.form(task()).copy(timing = ReplTiming.SCHEDULE, schedule = CronSchedule("15", "1", "*", "*", "*"), onlyMatchingSchedule = true)
        val body = ReplicationLogic.taskJson(f)
        assertEquals(o("""{"minute":"15","hour":"1","dom":"*","month":"*","dow":"*","begin":"00:00","end":"23:59"}"""), body["schedule"])
        assertEquals(JsonPrimitive(true), body["only_matching_schedule"])
        val manual = ReplicationLogic.taskJson(f.copy(timing = ReplTiming.MANUAL))
        assertEquals(JsonPrimitive(false), manual["auto"]); assertEquals(JsonNull, manual["schedule"]); assertEquals(JsonPrimitive(false), manual["only_matching_schedule"])
        assertEquals("The window must start before it ends", ReplicationLogic.errors(f.copy(schedule = f.schedule.copy(begin = "22:00", end = "06:00")))["schedule"])
    }

    @Test fun pullUsesNamingSchemaAndNeverAfterSnapshots() {
        val f = ReplicationForm(name = "Pull docs", direction = ReplDirection.PULL, sshCredentialsId = 5, sourceDatasets = listOf("tank/docs"),
            targetDataset = "backup/docs", namingSchemas = "auto-%Y-%m-%d_%H-%M", timing = ReplTiming.AFTER_SNAPSHOTS, holdPendingSnapshots = true, snapshotTaskIds = setOf(3))
        assertEquals(ReplTiming.SCHEDULE, ReplicationLogic.effectiveTiming(f))
        val body = ReplicationLogic.taskJson(f)
        assertEquals(j("""["auto-%Y-%m-%d_%H-%M"]"""), body["naming_schema"])
        assertEquals(j("[]"), body["also_include_naming_schema"])
        assertEquals(j("[]"), body["periodic_snapshot_tasks"])
        assertEquals(JsonPrimitive(false), body["hold_pending_snapshots"])
        assertTrue(body["schedule"] is kotlinx.serialization.json.JsonObject)
        assertTrue(ReplicationLogic.errors(f).isEmpty())
        assertEquals("Enter the naming schema of the snapshots to pull", ReplicationLogic.errors(f.copy(namingSchemas = ""))["naming_schema"])
    }

    @Test fun onetimeBodyLeavesOutTaskOnlyKeys() {
        val body = ReplicationLogic.taskJson(ReplicationLogic.form(task()), onetime = true)
        listOf("name", "auto", "schedule", "only_matching_schedule", "enabled").forEach { assertFalse(it, it in body) }
        assertEquals(JsonPrimitive("PUSH"), body["direction"])
    }

    @Test fun encryptionKeyLocation() {
        val base = ReplicationLogic.form(task()).copy(encryption = true, encryptionKey = "correct horse battery")
        val inDb = ReplicationLogic.taskJson(base)
        assertEquals(JsonPrimitive("\$TrueNAS"), inDb["encryption_key_location"])
        assertEquals(JsonPrimitive(false), inDb["encryption_inherit"])
        assertEquals(JsonPrimitive("PASSPHRASE"), inDb["encryption_key_format"])
        val file = ReplicationLogic.taskJson(base.copy(encryptionKeyInTrueNas = false, encryptionKeyLocation = " /root/key "))
        assertEquals(JsonPrimitive("/root/key"), file["encryption_key_location"])
        val inherit = ReplicationLogic.taskJson(base.copy(encryptionInherit = true))
        assertEquals(JsonPrimitive(true), inherit["encryption_inherit"]); assertEquals(JsonNull, inherit["encryption_key"])
        val off = ReplicationLogic.taskJson(base.copy(encryption = false))
        assertEquals(JsonNull, off["encryption_inherit"]); assertEquals(JsonNull, off["encryption_key_location"])
        assertEquals("64 hex digits", ReplicationLogic.errors(base.copy(encryptionKeyFormat = "HEX", encryptionKey = "abc"), listOf(snapRef))["encryption_key"])
        assertEquals("At least 8 characters", ReplicationLogic.errors(base.copy(encryptionKey = "short"), listOf(snapRef))["encryption_key"])
        val stored = ReplicationLogic.form(ReplicationLogic.task(o(pushTask.replace("\"encryption\":false", "\"encryption\":true")
            .replace("\"encryption_key_location\":null", "\"encryption_key_location\":\"${'$'}TrueNAS\"")))!!)
        assertTrue(stored.encryptionKeyInTrueNas); assertEquals("", stored.encryptionKeyLocation)
    }

    @Test fun localAndNetcatTransportsDropSshOnlyOptions() {
        val local = ReplicationLogic.form(task()).copy(transport = ReplTransport.LOCAL, sudo = true)
        val b = ReplicationLogic.taskJson(local)
        assertEquals(JsonNull, b["ssh_credentials"]); assertEquals(JsonNull, b["compression"]); assertEquals(JsonNull, b["speed_limit"])
        assertEquals(JsonPrimitive(false), b["sudo"])
        val nc = ReplicationLogic.form(task()).copy(transport = ReplTransport.NETCAT, netcatPortMin = "51000", netcatPortMax = "50000")
        val nb = ReplicationLogic.taskJson(nc)
        assertEquals(JsonPrimitive("LOCAL"), nb["netcat_active_side"]); assertEquals(JsonNull, nb["compression"]); assertEquals(JsonPrimitive(51000), nb["netcat_active_side_port_min"])
        assertEquals("Must be at least the lowest port", ReplicationLogic.errors(nc, listOf(snapRef))["netcat_active_side_port_max"])
        assertEquals("Can't be inside a source dataset", ReplicationLogic.errors(local.copy(targetDataset = "tank/photos/copy"), listOf(snapRef))["target_dataset"])
    }

    @Test fun validationMirrorsTheMiddleware() {
        val ok = ReplicationLogic.form(task())
        fun e(f: ReplicationForm, tasks: List<SnapshotTaskRef> = listOf(snapRef)) = ReplicationLogic.errors(f, tasks)
        assertEquals("Choose an SSH connection", e(ok.copy(sshCredentialsId = null))["ssh_credentials"])
        assertEquals("Only with \"Include child datasets\"", e(ok.copy(recursive = false))["exclude"])
        assertEquals("tank/docs/x isn't inside a source dataset", e(ok.copy(exclude = "tank/docs/x"))["exclude"])
        assertEquals("Not with full filesystem replication", e(ok.copy(replicate = true))["exclude"])
        assertEquals("Full filesystem replication keeps snapshots like the source", e(ok.copy(replicate = true, exclude = "", retention = Retention.NONE))["retention_policy"])
        assertTrue(e(ok.copy(replicate = true, exclude = "")).isEmpty())
        assertEquals("Full filesystem replication needs a recursive snapshot task of the source",
            e(ok.copy(replicate = true, exclude = ""), listOf(snapRef.copy(recursive = false)))["periodic_snapshot_tasks"])
        assertEquals("The snapshot task for tank/photos is turned off", e(ok, listOf(snapRef.copy(enabled = false)))["periodic_snapshot_tasks"])
        assertTrue(e(ok.copy(enabled = false), listOf(snapRef.copy(enabled = false))).isEmpty())
        assertEquals("Pick a periodic snapshot task or enter a naming schema", e(ok.copy(snapshotTaskIds = emptySet()))["periodic_snapshot_tasks"])
        assertEquals("Pick a periodic snapshot task first", e(ok.copy(snapshotTaskIds = emptySet(), namingSchemas = "manual-%Y-%m-%d_%H-%M"))["auto"])
        assertEquals("bad needs %Y %m %d %H and %M", e(ok.copy(namingSchemas = "bad"))["naming_schema"])
        assertEquals("Use Same as source or Keep all with a regular expression", e(ok.copy(useRegex = true, nameRegex = "auto-.*", retention = Retention.CUSTOM))["retention_policy"])
        assertEquals("Not a valid regular expression", e(ok.copy(useRegex = true, nameRegex = "auto-("))["name_regex"])
        assertEquals("A whole number of 1 or more", e(ok.copy(retention = Retention.CUSTOM, lifetimeValue = "0"))["lifetime_value"])
        assertEquals("KiB/s, or empty for unlimited", e(ok.copy(speedLimit = "0"))["speed_limit"])
        assertEquals("Use property=value, one per line", e(ok.copy(propertiesOverride = "compression"))["properties_override"])
        // Run once doesn't need a name.
        assertNull(ReplicationLogic.errors(ok.copy(name = ""), listOf(snapRef), onetime = true)["name"])
    }

    @Test fun retentionCustomSendsLifetimeOnly() {
        val b = ReplicationLogic.taskJson(ReplicationLogic.form(task()).copy(retention = Retention.CUSTOM, lifetimeValue = "3", lifetimeUnit = "MONTH", readonly = ReadonlyPolicy.REQUIRE))
        assertEquals(JsonPrimitive(3), b["lifetime_value"]); assertEquals(JsonPrimitive("MONTH"), b["lifetime_unit"])
        assertFalse("lifetimes" in b)
        assertEquals(JsonPrimitive("REQUIRE"), b["readonly"])
    }

    @Test fun serverFieldErrorsLandOnTheirFields() {
        val e = TrueNasException.Rpc(-32602, "EINVAL", "Invalid", listOf(
            FieldError("replication_update.target_dataset", "Target dataset is in use"),
            FieldError("replication_update.schedule.hour", "Invalid hour"),
            FieldError("replication_update.also_include_naming_schema.0", "Naming schema needs %Y"),
            FieldError("replication_update.lifetime_unit", "This field is required"),
        ))
        val (mine, general) = splitFieldErrors(e, ReplicationLogic.FIELDS, ReplicationLogic.FIELD_ALIASES)
        assertEquals(setOf("target_dataset", "schedule", "naming_schema", "lifetime_value"), mine.keys)
        assertNull(general)
    }

    // ---------------- API calls ----------------

    @Test fun taskCallsUseTheDocumentedArguments() = runBlocking {
        responder = { m, _ ->
            when (m) {
                "replication.run", "replication.run_onetime" -> JsonPrimitive(4242)
                "replication.restore" -> o(pushTask.replace("\"id\":12", "\"id\":13"))
                "replication.list_datasets" -> j("""["backup","backup/photos"]""")
                "replication.list_naming_schemas" -> j("""["auto-%Y-%m-%d_%H-%M"]""")
                "pool.snapshottask.query" -> j("[$snapTask]")
                "replication.query" -> j("[$pushTask]")
                else -> JsonNull
            }
        }
        val a = ReplicationApi(api)
        assertEquals(12, a.tasks().single().id)
        assertEquals(4242L, a.run(12))
        assertEquals("replication.run" to listOf<JsonElement>(JsonPrimitive(12)), calls.last())
        assertEquals(4242L, a.runOnetime(o("""{"direction":"PUSH"}""")))
        assertEquals("replication.run_onetime", calls.last().first)
        assertEquals(13, a.restore(12, " Restore photos ", "/tank/restored/")!!.id)
        assertEquals(listOf(JsonPrimitive(12), o("""{"name":"Restore photos","target_dataset":"tank/restored"}""")), calls.last().second)
        assertEquals(listOf("backup", "backup/photos"), a.listDatasets(ReplTransport.SSH, 5))
        assertEquals(listOf<JsonElement>(JsonPrimitive("SSH"), JsonPrimitive(5)), calls.last().second)
        a.listDatasets(ReplTransport.LOCAL, null)
        assertEquals(listOf(JsonPrimitive("LOCAL"), JsonNull), calls.last().second)
        assertEquals(listOf("auto-%Y-%m-%d_%H-%M"), a.namingSchemas())
        assertEquals("tank/photos", a.snapshotTasks().single().dataset)
        a.setEnabled(12, false)
        assertEquals(listOf(JsonPrimitive(12), o("""{"enabled":false}""")), calls.last().second)
        a.delete(12)
        assertEquals("replication.delete" to listOf<JsonElement>(JsonPrimitive(12)), calls.last())
    }

    @Test fun runWithoutJobIdFails() = runBlocking {
        responder = { _, _ -> JsonNull }
        assertTrue(runCatching { ReplicationApi(api).run(1) }.exceptionOrNull() is TrueNasException.JobFailed)
    }

    // ---------------- keychain ----------------

    private val keyPairJson = """{"id":4,"name":"replication-key","type":"SSH_KEY_PAIR","attributes":{"private_key":"-----BEGIN OPENSSH PRIVATE KEY-----\nexample\n-----END OPENSSH PRIVATE KEY-----","public_key":"ssh-rsa AAAAB3NzaC1yc2EAAAADexampleexample root@truenas"}}"""

    @Test fun keychainParsingAndRedaction() = runBlocking {
        responder = { m, p ->
            when (m) {
                "keychaincredential.query" -> if (p[0].toString().contains("SSH_KEY_PAIR")) j("[$keyPairJson,{\"id\":6,\"name\":\"hidden\",\"type\":\"SSH_KEY_PAIR\",\"attributes\":{\"private_key\":\"********\",\"public_key\":\"********\"}}]")
                    else j("""[{"id":5,"name":"Backup NAS","type":"SSH_CREDENTIALS","attributes":{"host":"nas2.example.com","port":2222,"username":"repl","private_key":4,"remote_host_key":"ssh-ed25519 AAAAexample","connect_timeout":10}}]""")
                "keychaincredential.used_by" -> j("""[{"title":"Replication task \"Photos to backup NAS\"","unbind_method":"disable"}]""")
                "keychaincredential.generate_ssh_key_pair" -> o("""{"private_key":"-----BEGIN RSA PRIVATE KEY-----\nx\n-----END RSA PRIVATE KEY-----","public_key":"ssh-rsa AAAAx"}""")
                "keychaincredential.remote_ssh_host_key_scan" -> JsonPrimitive("ssh-ed25519 AAAAscanned")
                else -> JsonNull
            }
        }
        val a = ReplicationApi(api)
        val keys = a.keyPairs()
        assertEquals(listOf("hidden", "replication-key"), keys.map { it.name })
        assertTrue(keys[0].redacted); assertNull(keys[0].privateKey)
        assertFalse(keys[1].redacted)
        assertEquals("ssh-rsa AAAAB3NzaC1y…eexample", keys[1].shortPublicKey)
        val c = a.connections().single()
        assertEquals("repl@nas2.example.com:2222", c.address)
        assertEquals(4, c.privateKeyId)
        assertEquals(j("""[["type","=","SSH_CREDENTIALS"]]"""), calls.last().second[0])
        assertEquals("disable", a.usedBy(5).single().unbindMethod)
        a.deleteCredential(5)
        assertEquals("keychaincredential.delete" to listOf<JsonElement>(JsonPrimitive(5)), calls.last())
        assertEquals("ssh-rsa AAAAx", a.generateKeyPair().second)
        assertEquals("ssh-ed25519 AAAAscanned", a.scanHostKey(" nas2.example.com ", 22, 10))
        assertEquals(o("""{"host":"nas2.example.com","port":22,"connect_timeout":10}"""), calls.last().second.single())
        a.renameCredential(4, " new name ")
        assertEquals(listOf(JsonPrimitive(4), o("""{"name":"new name"}""")), calls.last().second)
        a.updateConnection(c.copy(name = "Backup NAS 2"))
        assertEquals(o("""{"name":"Backup NAS 2","attributes":{"host":"nas2.example.com","port":2222,"username":"repl","private_key":4,"remote_host_key":"ssh-ed25519 AAAAexample","connect_timeout":10}}"""),
            calls.last().second[1])
    }

    @Test fun keyPairJsonAndChecks() {
        assertEquals(o("""{"name":"k","type":"SSH_KEY_PAIR","attributes":{"private_key":null,"public_key":"ssh-ed25519 AAAA x"}}"""),
            ReplicationApi.keyPairJson(" k ", " ", "ssh-ed25519 AAAA x"))
        assertEquals("Paste a private key or generate one", keyPairErrors(KeyPairForm("k"), editing = false)["private_key"])
        assertEquals("Keys with a passphrase aren't allowed",
            keyPairErrors(KeyPairForm("k", "-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\n-----END RSA PRIVATE KEY-----"), false)["private_key"])
        assertEquals("Looks like ssh-ed25519 AAAA… comment", keyPairErrors(KeyPairForm("k", publicKey = "nonsense"), false)["public_key"])
        assertTrue(keyPairErrors(KeyPairForm("k", "-----BEGIN OPENSSH PRIVATE KEY-----\nx\n-----END OPENSSH PRIVATE KEY-----"), false).isEmpty())
        assertTrue(keyPairErrors(KeyPairForm("renamed"), editing = true).isEmpty())
    }

    @Test fun semiAutomaticSetupJson() {
        val f = SshConnForm(name = "Backup NAS", newKeyName = "backup-key", url = "https://nas2.example.com/", adminUsername = "admin", password = "pw", otp = "123456", username = "repl", sudo = true)
        assertTrue(SshForms.errors(f, false, setOf("other")).isEmpty())
        assertEquals("A key pair with this name exists", SshForms.errors(f, false, setOf("backup-key"))["key_name"])
        assertEquals(o("""{"connection_name":"Backup NAS","setup_type":"SEMI-AUTOMATIC","private_key":{"generate_key":true,"name":"backup-key"},
            "semi_automatic_setup":{"url":"https://nas2.example.com","verify_ssl":true,"admin_username":"admin","password":"pw","otp_token":"123456","username":"repl","connect_timeout":10,"sudo":true}}"""),
            ReplicationApi.setupJson(SshForms.setup(f)))
        val token = ReplicationApi.setupJson(SshForms.setup(f.copy(useToken = true, token = "tok", generateKey = false, existingKeyId = 4)))
        assertEquals(o("""{"url":"https://nas2.example.com","verify_ssl":true,"token":"tok","username":"repl","connect_timeout":10,"sudo":true}"""), token["semi_automatic_setup"])
        assertEquals(o("""{"generate_key":false,"existing_key_id":4}"""), token["private_key"])
        assertEquals("Like https://nas2.example.com", SshForms.errors(f.copy(url = "nas2"), false, emptySet())["url"])
        assertEquals("Required", SshForms.errors(f.copy(password = ""), false, emptySet())["password"])
    }

    @Test fun manualSetupJsonAndEditing() {
        val f = SshConnForm(name = "Offsite", semiAutomatic = false, generateKey = false, existingKeyId = 4, host = "203.0.113.7", port = "2222", remoteHostKey = "ssh-ed25519 AAAA", username = "zfs")
        assertTrue(SshForms.errors(f, false, emptySet()).isEmpty())
        assertEquals(o("""{"host":"203.0.113.7","port":2222,"username":"zfs","remote_host_key":"ssh-ed25519 AAAA","connect_timeout":10}"""),
            ReplicationApi.setupJson(SshForms.setup(f))["manual_setup"])
        assertEquals("1–65535", SshForms.errors(f.copy(port = "70000"), false, emptySet())["port"])
        assertEquals("Tap Discover or paste the host key", SshForms.errors(f.copy(remoteHostKey = ""), false, emptySet())["remote_host_key"])
        val edit = SshForms.of(SshConnection(5, "Backup NAS", "nas2.example.com", 22, "root", 4, "ssh-ed25519 AAAA", 10))
        assertFalse(edit.semiAutomatic)
        assertTrue(SshForms.errors(edit, true, setOf("replication-key")).isEmpty())
        val e = TrueNasException.Rpc(-32602, "EINVAL", "Invalid", listOf(FieldError("setup_ssh_connection.semi_automatic_setup.otp_token", "Invalid code")))
        assertEquals(setOf("password"), splitFieldErrors(e, SshForms.FIELDS, SshForms.FIELD_ALIASES).first.keys)
    }

    // ---------------- notifications and deep links ----------------

    @Test fun replicationAlertsOpenReplication() {
        assertEquals(AlertTarget.Replication, AlertTarget.of("ReplicationFailed", o("""{"name":"Photos","message":"No route"}""")))
        assertEquals(AlertTarget.Replication, AlertTarget.of("ReplicationSuccess", o("""{"name":"Photos"}""")))
        assertEquals(AlertTarget.Replication, AlertTarget.decode(DeepLink.DEST_REPLICATION, null))
        assertEquals("Open replication", AlertTarget.Replication.label)
    }

    @Test fun watchKindDefaultsToCloudSyncForOldEntries() {
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(CloudRunWatch.serializer(),
            """{"serverId":"s","taskId":7,"taskName":"Photos","jobId":1,"dryRun":false,"startedAt":0}""")
        assertEquals(CloudRunWatch.KIND_CLOUD_SYNC, old.kind)
        val r = CloudRunWatch("s", 12, "Photos to backup NAS", 2, false, 0, CloudRunWatch.KIND_REPLICATION)
        assertEquals(r, Json.decodeFromString(CloudRunWatch.serializer(), Json.encodeToString(CloudRunWatch.serializer(), r)))
        assertEquals("replication", Json.parseToJsonElement(Json.encodeToString(CloudRunWatch.serializer(), r)).jsonObject["kind"]!!.jsonPrimitive.content)
    }
}
