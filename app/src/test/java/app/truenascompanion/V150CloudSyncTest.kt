package app.truenascompanion

import app.truenascompanion.data.api.CloudSyncApi
import app.truenascompanion.data.api.FieldError
import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.cloud.BwRow
import app.truenascompanion.data.cloud.CloudDirection
import app.truenascompanion.data.cloud.CloudProviders
import app.truenascompanion.data.cloud.CloudSyncForm
import app.truenascompanion.data.cloud.CloudSyncLogic
import app.truenascompanion.data.cloud.TransferMode
import app.truenascompanion.data.model.CronSchedule
import app.truenascompanion.data.model.JobState
import app.truenascompanion.data.model.LastJob
import app.truenascompanion.notify.AlertTarget
import app.truenascompanion.notify.CloudSyncWatcher
import app.truenascompanion.notify.DeepLink
import app.truenascompanion.ui.tasks.splitFieldErrors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** 1.5.0: cloud sync credentials and tasks (TrueNAS 25.10 shapes from the middleware source; example data only). */
class V150CloudSyncTest {
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

    private val providersJson = """[
      {"name":"S3","title":"Amazon S3","credentials_oauth":null,"buckets":true,"bucket_title":"Bucket",
       "task_schema":[{"property":"bucket"},{"property":"folder"},{"property":"fast_list"},{"property":"region"},{"property":"encryption"},{"property":"storage_class"}]},
      {"name":"B2","title":"Backblaze B2","credentials_oauth":null,"buckets":true,"bucket_title":"Bucket",
       "task_schema":[{"property":"bucket"},{"property":"folder"},{"property":"fast_list"},{"property":"b2_chunk_size"}]},
      {"name":"DROPBOX","title":"Dropbox","credentials_oauth":"https://www.truenas.com/oauth/dropbox","buckets":false,"bucket_title":"Bucket",
       "task_schema":[{"property":"folder"},{"property":"dropbox_chunk_size"}]},
      {"name":"AZUREBLOB","title":"Microsoft Azure Blob Storage","credentials_oauth":null,"buckets":true,"bucket_title":"Container",
       "task_schema":[{"property":"bucket"},{"property":"folder"},{"property":"fast_list"}]}
    ]"""

    private val b2Task = """{"id":7,"description":"Photos to B2","path":"/mnt/tank/photos",
      "credentials":{"id":3,"name":"Backblaze","provider":{"type":"B2","account":"0012ab","key":"K001example"}},
      "attributes":{"bucket":"photo-backup","folder":"/nas","fast_list":true,"chunk_size":128,"legacy":"x"},
      "schedule":{"minute":"30","hour":"2","dom":"*","month":"*","dow":"*"},
      "pre_script":"","post_script":"","snapshot":true,"include":[],"exclude":["*.tmp"],"args":"","enabled":true,
      "job":{"id":4411,"state":"RUNNING","progress":{"percent":42.5,"description":"1.2 GiB / 2.9 GiB, 12 MiB/s"},"time_started":{"${'$'}date":1790410000000}},
      "locked":false,"bwlimit":[{"time":"08:00","bandwidth":524288},{"time":"23:00","bandwidth":null}],"transfers":8,
      "direction":"PUSH","transfer_mode":"SYNC","encryption":true,"filename_encryption":true,
      "encryption_password":"********","encryption_salt":"********","create_empty_src_dirs":false,"follow_symlinks":false}"""

    // ---------------- parsing ----------------

    @Test fun providersCarryBucketsOAuthAndAttributeKeys() = runBlocking {
        responder = { m, _ -> assertEquals("cloudsync.providers", m); j(providersJson) }
        val ps = CloudSyncApi(api).providers()
        assertEquals(listOf("S3", "B2", "DROPBOX", "AZUREBLOB"), ps.map { it.name })
        val b2 = ps[1]
        assertTrue(b2.buckets)
        assertEquals(setOf("bucket", "folder", "fast_list", "chunk_size"), b2.wireKeys)
        assertEquals("https://www.truenas.com/oauth/dropbox", ps[2].oauthUrl)
        assertFalse(ps[2].buckets)
        assertEquals("Container", ps[3].bucketTitle)
    }

    @Test fun taskEntryParsesRunningJobScheduleAndLimits() {
        val t = CloudSyncLogic.task(o(b2Task))!!
        assertEquals(7, t.id); assertEquals(3, t.credentialId); assertEquals("B2", t.providerType); assertEquals("Backblaze", t.credentialName)
        assertTrue(t.running)
        assertEquals(42.5, t.job!!.percent!!, 0.01)
        assertEquals(4411L, t.jobId)
        assertEquals("photo-backup/nas", t.remote)
        assertEquals(CronSchedule("30", "2", "*", "*", "*"), t.schedule)
        assertEquals(listOf(524288L, null), t.bwlimit.map { it.bandwidth })
        assertEquals(CloudDirection.PUSH, t.direction); assertEquals(TransferMode.SYNC, t.mode)
        assertEquals("/mnt/tank/photos → Backblaze: photo-backup/nas", CloudSyncLogic.routeText(t, "Backblaze"))
    }

    @Test fun remoteTextWithoutBucket() {
        assertEquals("/", CloudSyncLogic.remoteText(null, ""))
        assertEquals("/Backups/nas", CloudSyncLogic.remoteText(null, "Backups/nas/"))
        assertEquals("bucket", CloudSyncLogic.remoteText("bucket", "/"))
    }

    // ---------------- task body ----------------

    @Test fun updateBodyKeepsOnlyProviderAttributesAndHiddenSecrets() {
        val providers = runBlocking { responder = { _, _ -> j(providersJson) }; CloudSyncApi(api).providers() }
        val t = CloudSyncLogic.task(o(b2Task))!!
        val f = CloudSyncLogic.form(t)
        assertTrue(f.secretsHidden)
        assertEquals("", f.encryptionPassword)
        assertEquals("512", f.bwlimit[0].limit)
        val body = CloudSyncLogic.taskJson(f, providers[1], update = true)
        val attrs = body["attributes"]!!.jsonObject
        assertEquals(setOf("bucket", "folder", "fast_list", "chunk_size"), attrs.keys)
        assertEquals(128, attrs["chunk_size"]!!.jsonPrimitive.content.toInt())
        // The saved password stays: not sent while hidden and untouched.
        assertFalse("encryption_password" in body)
        assertFalse("encryption_salt" in body)
        assertFalse("args" in body)
        assertEquals(3, body["credentials"]!!.jsonPrimitive.content.toInt())
        assertEquals(JsonPrimitive(524288), body["bwlimit"]!!.jsonArray[0].jsonObject["bandwidth"])
        assertEquals(JsonNull, body["bwlimit"]!!.jsonArray[1].jsonObject["bandwidth"])
        assertEquals(JsonPrimitive(8), body["transfers"])
        assertEquals("30", body["schedule"]!!.jsonObject["minute"]!!.jsonPrimitive.content)
        // Typing a new password sends it.
        val typed = CloudSyncLogic.taskJson(f.copy(encryptionPassword = "new-secret"), providers[1], update = true)
        assertEquals("new-secret", typed["encryption_password"]!!.jsonPrimitive.content)
    }

    @Test fun createBodyForS3HasExactAttributesAndNullSse() {
        val providers = runBlocking { responder = { _, _ -> j(providersJson) }; CloudSyncApi(api).providers() }
        val f = CloudSyncForm(description = "Docs", path = "/mnt/tank/docs/", credentialId = 1, bucket = "docs", folder = "/",
            storageClass = "STANDARD_IA", transfers = "", encryption = false)
        val body = CloudSyncLogic.taskJson(f, providers[0], update = false)
        val a = body["attributes"]!!.jsonObject
        assertEquals(setOf("bucket", "folder", "fast_list", "region", "encryption", "storage_class"), a.keys)
        assertEquals(JsonNull, a["encryption"])
        assertEquals("STANDARD_IA", a["storage_class"]!!.jsonPrimitive.content)
        assertEquals("/mnt/tank/docs", body["path"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["transfers"])
        assertEquals("", body["encryption_password"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive("AES256"), CloudSyncLogic.attributes(f.copy(s3Encryption = true), providers[0])["encryption"])
    }

    @Test fun nonBucketProviderNeverSendsABucket() {
        val providers = runBlocking { responder = { _, _ -> j(providersJson) }; CloudSyncApi(api).providers() }
        val a = CloudSyncLogic.attributes(CloudSyncForm(credentialId = 2, bucket = "stale", folder = "/Backups"), providers[2])
        assertEquals(setOf("folder", "chunk_size"), a.keys)
        assertEquals(48, a["chunk_size"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun localChecksMatchTheMiddlewareRules() {
        val p = runBlocking { responder = { _, _ -> j(providersJson) }; CloudSyncApi(api).providers() }[1]
        val ok = CloudSyncForm(path = "/mnt/tank/photos", credentialId = 3, bucket = "b")
        assertTrue(CloudSyncLogic.errors(ok, p).isEmpty())
        val bad = CloudSyncLogic.errors(ok.copy(path = "/home/admin", bucket = "", snapshot = true, direction = CloudDirection.PULL,
            encryption = true, transfers = "0", chunkSize = "2", bwlimit = listOf(BwRow("23:00", ""), BwRow("08:00", "100"))), p)
        assertEquals(setOf("path", "bucket", "snapshot", "encryption_password", "transfers", "chunk_size", "bwlimit"), bad.keys)
        assertEquals("Times must go up: 23:00, then 08:00", bad["bwlimit"])
        assertEquals("Can't be used with Move", CloudSyncLogic.errors(ok.copy(snapshot = true, mode = TransferMode.MOVE), p)["snapshot"])
        // A hidden saved password counts as set.
        assertNull(CloudSyncLogic.errors(ok.copy(encryption = true, secretsHidden = true), p)["encryption_password"])
        assertEquals("08:05", CloudSyncLogic.normalizeTime("8:05"))
        assertNull(CloudSyncLogic.normalizeTime("24:00"))
    }

    @Test fun restoreGoesIntoAPoolFolder() {
        assertEquals("Choose a folder", CloudSyncLogic.restorePathError(" "))
        assertEquals("Pick a folder inside a pool", CloudSyncLogic.restorePathError("/mnt/"))
        assertEquals("Pick a folder inside a pool", CloudSyncLogic.restorePathError("/home/admin"))
        assertNull(CloudSyncLogic.restorePathError("/mnt/tank/restore"))
    }

    @Test fun serverFieldErrorsLandOnTheirFields() {
        val e = TrueNasException.Rpc(-32602, "EINVAL", "Invalid", listOf(
            FieldError("cloud_sync_create.attributes.folder", "Directory does not exist"),
            FieldError("cloud_sync_create.bwlimit.1.time", "Invalid time order: 23:00, 08:00"),
            FieldError("cloud_sync_create.schedule.hour", "Invalid hour"),
            FieldError("cloud_sync_create.pre_script", "The ability to edit pre-scripts and post-scripts is limited"),
        ))
        val (mine, general) = splitFieldErrors(e, CloudSyncLogic.FIELDS, CloudSyncLogic.FIELD_ALIASES)
        assertEquals(setOf("folder", "bwlimit", "schedule", "pre_script"), mine.keys)
        assertNull(general)
    }

    // ---------------- credentials ----------------

    @Test fun s3CredentialJsonHasTypedValuesAndAllKeys() {
        val v = CloudProviders.defaults("S3") + mapOf("access_key_id" to "AKIAEXAMPLE", "secret_access_key" to "example/secret", "endpoint" to "https://s3.example.com/")
        val p = CloudProviders.providerJson("S3", v)
        assertEquals("S3", p["type"]!!.jsonPrimitive.content)
        assertEquals(setOf("type", "access_key_id", "secret_access_key", "endpoint", "region", "skip_region", "signatures_v2", "max_upload_parts"), p.keys)
        assertEquals(JsonPrimitive(10000), p["max_upload_parts"])
        assertEquals(JsonPrimitive(false), p["skip_region"])
        assertTrue(CloudProviders.errors("S3", v).isEmpty())
        assertEquals("s3.example.com", CloudProviders.summary("S3", p))
    }

    @Test fun sftpSendsNullPasswordAndKeyPairId() {
        val p = CloudProviders.providerJson("SFTP", mapOf("host" to "backup.example.com", "port" to "2222", "user" to "nas", "pass" to "", "private_key" to "4"))
        assertEquals(JsonNull, p["pass"])
        assertEquals(JsonPrimitive(4), p["private_key"])
        assertEquals(JsonPrimitive(2222), p["port"])
        val none = CloudProviders.providerJson("SFTP", mapOf("host" to "h", "user" to "u", "pass" to "pw", "private_key" to ""))
        assertEquals(JsonNull, none["private_key"])
        assertEquals(JsonPrimitive(22), none["port"])
        assertEquals("1–65535", CloudProviders.errors("SFTP", mapOf("host" to "h", "user" to "u", "port" to "70000"))["port"])
    }

    @Test fun swiftSendsRequiredNullableChoices() {
        val p = CloudProviders.providerJson("OPENSTACK_SWIFT", mapOf("user" to "u", "key" to "k", "auth" to "https://auth.example.com/v3", "auth_version" to "3", "endpoint_type" to ""))
        assertEquals(JsonPrimitive(3), p["auth_version"])
        assertEquals(JsonNull, p["endpoint_type"])
    }

    @Test fun oauthTokenMustBeJsonWithAccessToken() {
        assertNull(CloudProviders.tokenError("""{"access_token":"ya29.example","token_type":"Bearer","refresh_token":"1//x","expiry":"2026-10-09T12:00:00Z"}"""))
        assertNotNull(CloudProviders.tokenError("ya29.example"))
        assertEquals("The token has no access_token", CloudProviders.tokenError("""{"refresh_token":"x"}"""))
        val errs = CloudProviders.errors("ONEDRIVE", CloudProviders.defaults("ONEDRIVE"))
        assertEquals(setOf("token", "drive_id"), errs.keys)
        assertEquals("ONEDRIVE", CloudProviders.spec("onedrive")!!.type)
        assertEquals("drive", CloudProviders.spec("GOOGLE_DRIVE")!!.rcloneName)
    }

    @Test fun otherLocalCredentialChecks() {
        assertEquals("Only letters, digits, - and .", CloudProviders.errors("AZUREBLOB", mapOf("account" to "my_account", "key" to "k"))["account"])
        assertEquals("Use an https:// URL", CloudProviders.errors("STORJ_IX", mapOf("access_key_id" to "a", "secret_access_key" to "s", "endpoint" to "http://gateway.example.com"))["endpoint"])
        assertEquals("Use a full URL starting with https://", CloudProviders.errors("WEBDAV", mapOf("url" to "cloud.example.com", "vendor" to "NEXTCLOUD"))["url"])
        assertEquals("Paste the whole JSON file, starting with {", CloudProviders.errors("GOOGLE_CLOUD_STORAGE", mapOf("service_account_credentials" to "abc"))["service_account_credentials"])
        // Redacted values ("********") are not re-validated.
        assertTrue(CloudProviders.errors("B2", mapOf("account" to "********", "key" to "********")).isEmpty())
        assertTrue(CloudProviders.redacted(mapOf("key" to "********")))
    }

    @Test fun everyMinimumProviderHasASpec() {
        listOf("S3", "B2", "GOOGLE_DRIVE", "DROPBOX", "ONEDRIVE", "SFTP", "WEBDAV", "STORJ_IX", "AZUREBLOB", "PCLOUD", "BOX").forEach {
            assertNotNull(it, CloudProviders.spec(it))
        }
        assertEquals(19, CloudProviders.SPECS.size)
        assertTrue(CloudProviders.SPECS.filter { it.oauth }.all { s -> s.fields.first { it.key == "token" }.kind == app.truenascompanion.data.cloud.FieldKind.TOKEN })
    }

    @Test fun credentialValuesRoundTrip() {
        val c = CloudSyncLogic.credential(o("""{"id":3,"name":"Backblaze","provider":{"type":"B2","account":"0012ab","key":"K001example"}}"""))!!
        val v = CloudProviders.valuesOf(c.type, c.provider)
        assertEquals(mapOf("account" to "0012ab", "key" to "K001example"), v)
        assertEquals(c.provider, CloudProviders.providerJson(c.type, v))
    }

    // ---------------- API calls ----------------

    @Test fun runDryRunAbortAndRestoreCalls() = runBlocking {
        responder = { m, _ ->
            when (m) {
                "cloudsync.sync" -> JsonPrimitive(901)
                "cloudsync.abort" -> JsonPrimitive(true)
                "cloudsync.restore" -> o(b2Task.replace("\"id\":7", "\"id\":8"))
                else -> JsonNull
            }
        }
        val a = CloudSyncApi(api)
        assertEquals(901L, a.sync(7, dryRun = true))
        assertEquals(listOf(JsonPrimitive(7), o("""{"dry_run":true}""")), calls.last().second)
        assertTrue(a.abort(7))
        assertEquals("cloudsync.abort" to listOf<JsonElement>(JsonPrimitive(7)), calls.last())
        assertEquals(8, a.restore(7, "Restore of photos", TransferMode.COPY, "/mnt/tank/restore")!!.id)
        assertEquals(o("""{"description":"Restore of photos","transfer_mode":"COPY","path":"/mnt/tank/restore"}"""), calls.last().second[1])
    }

    @Test fun syncWithoutJobIdFails() = runBlocking {
        responder = { _, _ -> JsonNull }
        val r = runCatching { CloudSyncApi(api).sync(7, false) }
        assertTrue(r.exceptionOrNull() is TrueNasException.JobFailed)
    }

    @Test fun listingCallsUseTheDocumentedArguments() = runBlocking {
        responder = { m, _ ->
            when (m) {
                "cloudsync.list_buckets" -> j("""[{"Path":"photos","Name":"photos","Size":-1,"IsDir":true,"IsBucket":true}]""")
                "cloudsync.list_directory" -> j("""[{"Path":"b.txt","Name":"b.txt","Size":12,"IsDir":false},{"Path":"Alpha","Name":"Alpha","Size":-1,"IsDir":true}]""")
                "keychaincredential.query" -> j("""[{"id":4,"name":"backup-key"}]""")
                "cloudsync.onedrive_list_drives" -> j("""[{"drive_id":"b!abc","drive_type":"PERSONAL","name":"OneDrive","description":""}]""")
                else -> JsonNull
            }
        }
        val a = CloudSyncApi(api)
        assertEquals("photos", a.listBuckets(3).single().name)
        assertEquals(listOf<JsonElement>(JsonPrimitive(3)), calls.last().second)
        val entries = a.listDirectory(2, null, "Backups")
        assertEquals(listOf("Alpha", "b.txt"), entries.map { it.name })
        val arg = calls.last().second.single().jsonObject
        assertEquals(o("""{"folder":"Backups"}"""), arg["attributes"])
        assertEquals(JsonPrimitive(false), arg["encryption"])
        assertEquals(JsonPrimitive(""), arg["args"])
        a.listDirectory(3, "photos", "")
        assertEquals(o("""{"bucket":"photos","folder":""}"""), calls.last().second.single().jsonObject["attributes"])
        assertEquals(4, a.keyPairs().single().id)
        assertEquals(j("""[["type","=","SSH_KEY_PAIR"]]"""), calls.last().second[0])
        assertEquals("b!abc", a.oneDriveDrives("", "", "{\"access_token\":\"x\"}").single().id)
    }

    @Test fun verifyAndCredentialCrud() = runBlocking {
        responder = { m, _ ->
            when (m) {
                "cloudsync.credentials.verify" -> o("""{"valid":false,"error":"Failed to lsjson: 401 Unauthorized\nmore","excerpt":"401 Unauthorized"}""")
                "cloudsync.credentials.create", "cloudsync.credentials.update" -> o("""{"id":5,"name":"Docs","provider":{"type":"DROPBOX","client_id":"","client_secret":"","token":"{}"}}""")
                "cloudsync.credentials.delete" -> throw TrueNasException.Rpc(-32001, "EFAULT", "This credential is used by cloud sync task Photos")
                else -> JsonNull
            }
        }
        val a = CloudSyncApi(api)
        val v = a.verify(CloudProviders.providerJson("B2", mapOf("account" to "a", "key" to "k")))
        assertFalse(v.valid); assertEquals("401 Unauthorized", v.message)
        assertEquals(5, a.createCredential(" Docs ", o("""{"type":"DROPBOX","token":"{}"}"""))!!.id)
        assertEquals("Docs", calls.last().second[0].jsonObject["name"]!!.jsonPrimitive.content)
        a.updateCredential(5, "Docs", o("""{"type":"DROPBOX","token":"{}"}"""))
        assertEquals(JsonPrimitive(5), calls.last().second[0])
        val err = runCatching { a.deleteCredential(5) }.exceptionOrNull()
        assertTrue(err!!.message!!.contains("used by cloud sync task"))
    }

    // ---------------- notifications and deep links ----------------

    @Test fun cloudSyncAlertsOpenCloudSync() {
        assertEquals(AlertTarget.CloudSync, AlertTarget.of("CloudSyncTaskFailed", o("""{"id":7,"name":"Photos"}""")))
        assertEquals(AlertTarget.CloudSync, AlertTarget.decode(DeepLink.DEST_CLOUD_SYNC, "7"))
        assertEquals("Open cloud sync", AlertTarget.CloudSync.label)
    }

    @Test fun watcherKnowsWhenARunEnded() {
        assertFalse(CloudSyncWatcher.ended(null))
        assertFalse(CloudSyncWatcher.ended(LastJob(JobState.RUNNING, null, null, null, null, 10.0, null)))
        assertTrue(CloudSyncWatcher.ended(LastJob(JobState.FAILED, null, null, "boom", null, null, null)))
        assertTrue(CloudSyncWatcher.ended(LastJob(JobState.ABORTED, null, null, null, null, null, null)))
        assertEquals(5_000L, CloudSyncWatcher.pollDelayMs(0))
        assertEquals(30_000L, CloudSyncWatcher.pollDelayMs(100))
    }

}
