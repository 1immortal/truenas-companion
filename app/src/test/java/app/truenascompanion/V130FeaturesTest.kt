package app.truenascompanion

import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.disks.DiskLogic
import app.truenascompanion.data.disks.DisksApi
import app.truenascompanion.data.files.FilePolicy
import app.truenascompanion.data.files.FilesApi
import app.truenascompanion.data.model.AlertItem
import app.truenascompanion.data.model.DiskKind
import app.truenascompanion.data.model.FileEntry
import app.truenascompanion.data.model.FileKind
import app.truenascompanion.data.model.FileSort
import app.truenascompanion.data.model.ReplacementCandidate
import app.truenascompanion.data.model.ResilverWatch
import app.truenascompanion.notify.AlertTarget
import app.truenascompanion.notify.DeepLink
import app.truenascompanion.notify.ResilverWatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** 1.3.0: file browser and disk replacement (TrueNAS 25.10 middleware shapes, example data only). */
class V130FeaturesTest {
    private val calls = mutableListOf<Pair<String, List<JsonElement>>>()
    private var responder: (String, List<JsonElement>) -> JsonElement = { _, _ -> JsonNull }
    private var alerts: List<AlertItem> = emptyList()

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
                    // A suspend call fails through its continuation (a Proxy would wrap a thrown checked exception).
                    (args.last() as kotlin.coroutines.Continuation<Any?>).resumeWith(Result.failure(e))
                    kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
                }
            }
            "alerts" -> alerts
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            "toString" -> "fake"
            else -> null
        }
    } as TrueNasApi

    private fun j(s: String) = Json.parseToJsonElement(s)
    private fun file(name: String, type: String = "FILE", size: Long = 1, mtime: Long? = null) =
        FileEntry(name, "/mnt/tank/$name", type, size, mtimeMillis = mtime)

    // ---------------- File policy ----------------

    @Test fun pathPolicyKeepsBrowsingInsidePools() {
        val pools = setOf("tank", "backup")
        assertEquals("/mnt/tank/media", FilePolicy.normalize("/mnt//tank/media/"))
        assertNull(FilePolicy.normalize("/mnt/tank/../../etc"))
        assertNull(FilePolicy.normalize("mnt/tank"))
        assertTrue(FilePolicy.isAllowed("/mnt", pools, false))
        assertTrue(FilePolicy.isAllowed("/mnt/tank/media/photos", pools, false))
        assertFalse(FilePolicy.isAllowed("/etc", pools, true))
        assertFalse(FilePolicy.isAllowed("/mnt/other/x", pools, true))
        assertFalse(FilePolicy.isAllowed("/mnt/tank/./x", pools, true))
        assertFalse(FilePolicy.isAllowed("/root/.ssh", pools, true))
        // System folders only with "Show system folders".
        assertFalse(FilePolicy.isAllowed("/mnt/tank/.ix-apps/app_mounts", pools, false))
        assertTrue(FilePolicy.isAllowed("/mnt/tank/.ix-apps/app_mounts", pools, true))
        assertFalse(FilePolicy.isAllowed("/mnt/tank/ix-applications", pools, false))
        assertFalse(FilePolicy.isAllowed("/mnt/tank/.system/samba4", pools, false))
        assertFalse(FilePolicy.isAllowed("/mnt/tank/media/.zfs/snapshot", pools, false))
        assertEquals("/mnt/tank", FilePolicy.parent("/mnt/tank/media"))
        assertEquals("/mnt", FilePolicy.parent("/mnt/tank"))
        assertNull(FilePolicy.parent("/mnt"))
        assertEquals(listOf("Pools" to "/mnt", "tank" to "/mnt/tank", "media" to "/mnt/tank/media"), FilePolicy.breadcrumbs("/mnt/tank/media"))
    }

    @Test fun hidesSystemAndDotEntriesUnlessAsked() {
        val e = listOf(file("ix-apps", "DIRECTORY"), file(".ix-apps", "DIRECTORY"), file(".system", "DIRECTORY"), file(".hidden"), file("media", "DIRECTORY"))
        assertEquals(listOf("media"), e.filter { FilePolicy.isVisible(it, false) }.map { it.name })
        assertEquals(5, e.count { FilePolicy.isVisible(it, true) })
    }

    @Test fun sortsFoldersFirstNaturallyAndBySizeOrDate() {
        val e = listOf(file("file10.txt", size = 5, mtime = 3), file("File2.txt", size = 50, mtime = 1), file("b", "DIRECTORY", mtime = 2), file("A", "DIRECTORY", mtime = 9), file("c.bin", size = 7))
        assertEquals(listOf("A", "b", "c.bin", "File2.txt", "file10.txt"), FilePolicy.sorted(e, FileSort.NAME, false).map { it.name })
        assertEquals(listOf("b", "A", "file10.txt", "File2.txt", "c.bin"), FilePolicy.sorted(e, FileSort.NAME, true).map { it.name })
        assertEquals(listOf("b", "A", "File2.txt", "c.bin", "file10.txt"), FilePolicy.sorted(e, FileSort.SIZE, true).map { it.name })
        assertEquals(listOf("file10.txt", "c.bin", "File2.txt"), FilePolicy.sorted(e, FileSort.SIZE, false).drop(2).map { it.name })
        assertEquals(listOf("file10.txt", "File2.txt", "c.bin"), FilePolicy.sorted(e, FileSort.DATE, true).drop(2).map { it.name })
        assertTrue(FilePolicy.matches(file("Holiday.JPG"), "holi"))
        assertFalse(FilePolicy.matches(file("Holiday.JPG"), "xmas"))
    }

    @Test fun namesKindsMimeAndPermissions() {
        assertNull(FilePolicy.nameProblem("Photos 2026"))
        assertEquals("Names can't contain /.", FilePolicy.nameProblem("a/b"))
        assertTrue(FilePolicy.nameProblem("..") != null)
        assertTrue(FilePolicy.nameProblem(" lead") != null)
        assertTrue(FilePolicy.nameProblem("x".repeat(256)) != null)
        assertEquals(FileKind.IMAGE, FilePolicy.kindOfName("IMG_0001.HEIC"))
        assertEquals(FileKind.VIDEO, FilePolicy.kindOfName("clip.mkv"))
        assertEquals(FileKind.ARCHIVE, FilePolicy.kindOfName("backup.tar.gz"))
        assertEquals(FileKind.OTHER, FilePolicy.kindOfName("README"))
        assertEquals("image/jpeg", FilePolicy.mimeOf("a.JPG"))
        assertEquals("text/plain", FilePolicy.mimeOf("notes.log"))
        assertEquals("application/octet-stream", FilePolicy.mimeOf("blob"))
        assertEquals("drwxr-xr-x", FilePolicy.permissions(0x41ED, "DIRECTORY"))
        assertEquals("-rw-r-----", FilePolicy.permissions(0x81A0, "FILE"))
        assertEquals("drwxrwxrwt", FilePolicy.permissions(0x43FF, "DIRECTORY"))
        assertEquals("0755", FilePolicy.octal(0x41ED))
    }

    @Test fun previewIsSizeCapped() {
        assertEquals(FilePolicy.Preview.TEXT, FilePolicy.previewKind(file("a.txt", size = 1000)))
        assertNull(FilePolicy.previewKind(file("big.log", size = FilePolicy.TEXT_PREVIEW_MAX + 1)))
        assertEquals(FilePolicy.Preview.IMAGE, FilePolicy.previewKind(file("p.png", size = 5_000_000)))
        assertNull(FilePolicy.previewKind(file("p.png", size = FilePolicy.IMAGE_PREVIEW_MAX + 1)))
        assertNull(FilePolicy.previewKind(file("raw.dng", size = 100)))
        assertNull(FilePolicy.previewKind(file("dir", "DIRECTORY")))
    }

    @Test fun friendlyErrors() {
        assertTrue(FilePolicy.friendlyError(TrueNasException.Forbidden()).startsWith("Permission denied"))
        assertEquals("This file or folder no longer exists.", FilePolicy.friendlyError(TrueNasException.Rpc(2, "ENOENT", "[ENOENT] /mnt/tank/x: path does not exist")))
        assertEquals("This isn't a folder.", FilePolicy.friendlyError(TrueNasException.Rpc(20, "ENOTDIR", "x")))
        assertTrue(FilePolicy.friendlyError(TrueNasException.Http(413, "x")).contains("reverse proxy"))
    }

    // ---------------- Files API call shapes ----------------

    @Test fun listdirUsesSelectPagingAndDirsFirstOrder() = runBlocking {
        responder = { m, p ->
            when {
                m == "filesystem.listdir" && p[2].jsonObject["count"] != null -> JsonPrimitive(1234)
                m == "filesystem.listdir" -> j("""[{"name":"media","path":"/mnt/tank/media","realpath":"/mnt/tank/media","type":"DIRECTORY","size":4,"mode":16877,"uid":0,"gid":0,"acl":true,"is_mountpoint":true},
                    {"name":"notes.txt","path":"/mnt/tank/notes.txt","realpath":"/mnt/tank/notes.txt","type":"FILE","size":2048,"mode":33188,"uid":3000,"gid":3000,"acl":false,"is_mountpoint":false}]""")
                else -> JsonNull
            }
        }
        val page = FilesApi(api).list("/mnt/tank", FileSort.SIZE, true, "", 0)
        assertEquals(1234, page.total)
        assertEquals(2, page.entries.size)
        assertTrue(page.entries[0].isDirectory && page.entries[0].isMountpoint && page.entries[0].acl)
        assertEquals(2048L, page.entries[1].size)
        assertEquals(listOf("filesystem.listdir", "filesystem.listdir"), calls.map { it.first })
        val opts = calls[1].second[2].jsonObject
        assertEquals("/mnt/tank", calls[1].second[0].jsonPrimitive.content)
        assertEquals(listOf("-size", "type"), opts["order_by"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(500, opts["limit"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, opts["offset"]!!.jsonPrimitive.content.toInt())
        assertTrue(opts["select"]!!.jsonArray.map { it.jsonPrimitive.content }.containsAll(listOf("name", "type", "size", "mode", "uid", "gid")))
        // Next page: no count call, offset moves on; a search goes to the server as a case-insensitive filter.
        calls.clear()
        FilesApi(api).list("/mnt/tank", FileSort.NAME, false, "Holi", 500)
        assertEquals(1, calls.size)
        assertEquals(500, calls[0].second[2].jsonObject["offset"]!!.jsonPrimitive.content.toInt())
        assertEquals("""[["name","Crin","Holi"]]""", calls[0].second[1].toString())
        assertEquals(listOf("name", "type"), calls[0].second[2].jsonObject["order_by"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun statMkdirDownloadAndUploadTokenCalls() = runBlocking {
        responder = { m, _ ->
            when (m) {
                "filesystem.stat" -> j("""{"realpath":"/mnt/tank/notes.txt","size":2048,"allocation_size":4096,"mode":33188,"uid":3000,"gid":3000,"atime":1780000000.5,"mtime":1780000000.25,"ctime":1780000000.0,"btime":1770000000.0,"dev":1,"inode":2,"nlink":1,"user":"alex","group":"family","acl":false,"is_mountpoint":false,"type":"FILE"}""")
                "filesystem.mkdir" -> j("""{"name":"new","path":"/mnt/tank/new","realpath":"/mnt/tank/new","type":"DIRECTORY","size":2,"mode":16877,"uid":0,"gid":0,"acl":false,"is_mountpoint":false}""")
                "core.download" -> j("""[42,"/_download/42?x=1"]""")
                "auth.generate_token" -> JsonPrimitive("one-time")
                else -> JsonNull
            }
        }
        val f = FilesApi(api)
        val st = f.stat("/mnt/tank/notes.txt")
        assertEquals("alex", st.user); assertEquals("family", st.group)
        assertEquals(1_780_000_000_250L, st.mtimeMillis)
        assertEquals("filesystem.stat", calls.last().first)
        f.mkdir("/mnt/tank/new")
        assertEquals("""{"path":"/mnt/tank/new","options":{"mode":"755","raise_chmod_error":false}}""", calls.last().second[0].toString())
        val t = f.startDownload("/mnt/tank/notes.txt", "notes.txt")
        assertEquals(42L, t.jobId); assertTrue(t.url.startsWith("/_download/"))
        assertEquals("""["filesystem.get",["/mnt/tank/notes.txt"],"notes.txt",false]""", calls.last().second.let { JsonArray(it).toString() })
        assertEquals("one-time", f.uploadToken())
        assertEquals("""[300,{},true,true]""", calls.last().second.let { JsonArray(it).toString() })
    }

    @Test fun downloadRejectsUnexpectedLinks() = runBlocking {
        responder = { _, _ -> j("""[7,"https://evil.example/x"]""") }
        val e = runCatching { FilesApi(api).startDownload("/mnt/tank/a", "a") }.exceptionOrNull()
        assertTrue(e is TrueNasException.Rpc)
    }

    @Test fun existsMapsEnoentToFalse() = runBlocking {
        responder = { _, _ -> throw TrueNasException.Rpc(2, "ENOENT", "[ENOENT] path not found") }
        assertFalse(FilesApi(api).exists("/mnt/tank/missing"))
    }

    // ---------------- Disks ----------------

    private val poolJson = """{"id":1,"name":"tank","guid":"111","status":"DEGRADED","healthy":false,"status_detail":"One or more devices has been removed.","size":8000000000000,"allocated":2000000000000,"free":6000000000000,
      "scan":{"function":"RESILVER","state":"SCANNING","start_time":{"${'$'}date":1780000000000},"end_time":null,"percentage":42.5,"bytes_to_process":1000,"bytes_processed":425,"bytes_issued":400,"pause":null,"errors":0,"total_secs_left":7500},
      "topology":{"data":[{"name":"mirror-0","type":"MIRROR","guid":"200","status":"DEGRADED","path":null,"stats":{"read_errors":0,"write_errors":0,"checksum_errors":0,"size":4000000000000},"children":[
         {"name":"sda1","type":"DISK","guid":"201","status":"ONLINE","path":"/dev/disk/by-partuuid/aaa","disk":"sda","device":"sda1","stats":{"read_errors":0,"write_errors":0,"checksum_errors":3,"size":4000000000000},"children":[],"unavail_disk":null},
         {"name":"9988","type":"DISK","guid":"202","status":"REMOVED","path":"/dev/disk/by-partuuid/bbb","disk":null,"device":null,"stats":{"read_errors":5,"write_errors":1,"checksum_errors":0,"size":0},"children":[],"unavail_disk":{"name":"sdb","serial":"WD-EXAMPLE2","model":"WDC WD40EFRX","size":4000787030016}}]}],
       "log":[],"cache":[{"name":"nvme0n1p1","type":"DISK","guid":"300","status":"ONLINE","path":"/dev/nvme0n1p1","disk":"nvme0n1","stats":{"read_errors":0,"write_errors":0,"checksum_errors":0},"children":[]}],"spare":[],"special":[],"dedup":[]}}"""

    @Test fun parsesPoolTopologyAndScan() {
        val p = DisksApi.pool(j(poolJson).jsonObject)
        assertEquals(listOf("data", "cache"), p.groups.map { it.category })
        val members = DiskLogic.members(p)
        assertEquals(3, members.size)
        val sdb = members.first { it.node.guid == "202" }
        assertEquals("sdb", sdb.node.unavailDisk?.name)
        assertEquals("MIRROR", sdb.parent?.type)
        assertEquals(6L, sdb.node.errors)
        assertEquals(42.5, p.scan!!.percent!!, 0.001)
        assertEquals(7500L, p.scan.secondsLeft)
        assertEquals(1_780_000_000_000L, p.scan.startMillis)
        assertTrue(DiskLogic.resilverRunning(p.scan))
        assertEquals("about 2 h 5 min", DiskLogic.eta(7500))
        assertEquals("sdb", DiskLogic.memberFor(listOf(p), "sdb")?.node?.unavailDisk?.name)
    }

    @Test fun actionRulesMatchTheWebUi() {
        val p = DisksApi.pool(j(poolJson).jsonObject)
        val m = DiskLogic.members(p)
        val sda = m.first { it.node.disk == "sda" }
        val sdb = m.first { it.node.guid == "202" }
        val cache = m.first { it.category == "cache" }
        assertTrue(DiskLogic.canOffline(sda)); assertFalse(DiskLogic.canOnline(sda)); assertTrue(DiskLogic.canDetach(sda))
        assertTrue(DiskLogic.canOffline(sdb)); assertTrue(DiskLogic.canOnline(sdb)) // web UI: only OFFLINE/UNAVAIL hide these
        assertFalse(DiskLogic.canOffline(cache)); assertFalse(DiskLogic.canOnline(cache)); assertFalse(DiskLogic.canDetach(cache))
        // Last healthy disk of a mirror: offlining it is not safe.
        assertFalse(DiskLogic.offlineIsSafe(sda))
        // Preselect the member that isn't ONLINE.
        assertEquals("202", DiskLogic.preselect(p)?.node?.guid)
        assertEquals("201", DiskLogic.preselect(p, "sda")?.node?.guid)
        assertEquals(4_000_787_030_016L, DiskLogic.memberSize(sdb, emptyMap()))
    }

    @Test fun sizeCheckAndCandidates() {
        assertEquals(DiskLogic.SizeCheck.Ok, DiskLogic.sizeCheck(4_000L, 4_000L))
        assertEquals(DiskLogic.SizeCheck.TooSmall(1L), DiskLogic.sizeCheck(4_000L, 3_999L))
        assertEquals(DiskLogic.SizeCheck.Unknown, DiskLogic.sizeCheck(null, 3_999L))
        val details = j("""{"used":[{"identifier":"{serial_lunid}EX-USED","name":"sdd","serial":"EX-USED","size":8000,"model":"M","type":"HDD","exported_zpool":"old","imported_zpool":null,"duplicate_serial":[]},
            {"identifier":"{serial_lunid}EX-IMP","name":"sde","serial":"EX-IMP","size":8000,"exported_zpool":null,"imported_zpool":"tank","duplicate_serial":[]}],
          "unused":[{"identifier":"{serial_lunid}EX-NEW","name":"sdc","serial":"EX-NEW","size":4000,"model":"WDC WD40EFRX","type":"HDD","exported_zpool":null,"imported_zpool":null,"duplicate_serial":["sdf"]},
            {"identifier":"{serial_lunid}EX-SMALL","name":"sdg","serial":"EX-SMALL","size":2000,"exported_zpool":null,"imported_zpool":null,"duplicate_serial":[]}]}""")
        responder = { _, _ -> details }
        val list = runBlocking { DisksApi(api).candidates() }
        assertEquals(listOf("sdc", "sdg", "sdd"), list.map { it.name })
        assertEquals(listOf("sdf"), list.first().duplicateSerial)
        assertTrue(DiskLogic.needsForce(list.first { it.name == "sdd" }))
        assertEquals(listOf("sdc", "sdd", "sdg"), DiskLogic.candidates(list, 4000L).map { it.name })
    }

    @Test fun replaceOfflineAndIdentifyCallShapes() = runBlocking {
        responder = { m, _ -> if (m == "pool.replace") JsonPrimitive(77) else JsonNull }
        val d = DisksApi(api)
        assertEquals(77L, d.startReplace(1, "202", "{serial_lunid}EX-NEW", false))
        assertEquals("""[1,{"label":"202","disk":"{serial_lunid}EX-NEW","force":false,"preserve_settings":true,"preserve_description":true}]""", calls.last().second.let { JsonArray(it).toString() })
        d.offline(1, "201"); assertEquals("pool.offline" to """[1,{"label":"201"}]""", calls.last().first to calls.last().second.let { JsonArray(it).toString() })
        d.online(1, "201"); assertEquals("pool.online", calls.last().first)
        d.detach(1, "201"); assertEquals("pool.detach" to """[1,{"label":"201"}]""", calls.last().first to calls.last().second.let { JsonArray(it).toString() })
        d.setIdentify(app.truenascompanion.data.model.EnclosureSlot("encl-1", 3, "sda", true, false), true)
        assertEquals("enclosure2.set_slot_status" to """[{"enclosure_id":"encl-1","slot":3,"status":"ON"}]""", calls.last().first to calls.last().second.let { JsonArray(it).toString() })
        assertTrue(calls.none { it.first.startsWith("smart.") })
    }

    @Test fun diskQueryKindsSlotsAndAlerts() = runBlocking {
        responder = { m, _ ->
            when (m) {
                "disk.query" -> j("""[{"identifier":"{serial_lunid}EX1","name":"sda","subsystem":"scsi","serial":"EX1","size":4000,"description":"","model":"WDC","rotationrate":5400,"type":"HDD","bus":"ATA","pool":"tank","zfs_guid":"201"},
                     {"identifier":"{serial}EX2","name":"nvme0n1","subsystem":"nvme","serial":"EX2","size":1000,"model":"Example NVMe","rotationrate":null,"type":"SSD","bus":"NVME","pool":null}]""")
                "disk.temperatures" -> j("""{"sda":36,"nvme0n1":44}""")
                "enclosure2.query" -> j("""[{"id":"encl-1","elements":{"Array Device Slot":{"1":{"dev":"sda","supports_identify_light":true,"drive_bay_light_status":"OFF"},"2":{"dev":"sdb","supports_identify_light":false}}}}]""")
                else -> JsonNull
            }
        }
        val d = DisksApi(api)
        val disks = d.disks()
        assertEquals("""[[],{"extra":{"pools":true}}]""", calls.first { it.first == "disk.query" }.second.let { JsonArray(it).toString() })
        assertEquals(DiskKind.HDD, disks[0].kind); assertEquals(DiskKind.NVME, disks[1].kind)
        assertEquals(36.0, disks[0].temperatureC!!, 0.0)
        val slots = d.enclosureSlots()
        assertEquals(2, slots.size); assertTrue(slots.first { it.dev == "sda" }.supportsIdentify); assertEquals(false, slots.first().lightOn)
        alerts = listOf(
            AlertItem("u1", "WARNING", "sda (EX1) failed a SMART selftest.", "SMARTFailedSelfTest", null, false, false, j("""{"name":"sda","serial":"EX1"}""")),
            AlertItem("u2", "CRITICAL", "Disk sdb is hot", "DiskTemperatureTooHot", null, false, false, j("""{"device":"/dev/sdb"}""")),
            AlertItem("u3", "WARNING", "Pool tank is DEGRADED", "VolumeStatus", null, false, false, j("""{"volume":"tank"}""")),
        )
        assertEquals(listOf("SMARTFailedSelfTest"), d.alertsFor("sda", "EX1").map { it.klass })
        assertEquals(listOf("DiskTemperatureTooHot"), d.alertsFor("sdb", null).map { it.klass })
        // No enclosure (most home systems): Identify is hidden.
        responder = { _, _ -> throw TrueNasException.MethodNotFound("enclosure2.query") }
        assertTrue(d.enclosureSlots().isEmpty())
    }

    // ---------------- Deep links & resilver watch ----------------

    @Test fun diskAlertsDeepLinkIntoReplaceWizard() {
        val withDevices = AlertTarget.of("VolumeStatus", j("""{"volume":"tank","state":"DEGRADED","status":"x","devices":"<br>The following devices are not healthy:<ul><li>Disk WDC WD40EFRX WD-EXAMPLE2 is REMOVED</li></ul>"}"""))
        assertEquals(AlertTarget.ReplaceDisk("tank"), withDevices)
        assertEquals(DeepLink.DEST_REPLACE_DISK, withDevices.destination)
        assertEquals(withDevices, AlertTarget.decode(withDevices.destination, withDevices.arg))
        assertEquals(AlertTarget.Pool("tank"), AlertTarget.of("VolumeStatus", j("""{"volume":"tank","state":"ONLINE","status":"x","devices":""}""")))
        assertEquals(AlertTarget.Disk("sdc"), AlertTarget.of("SMARTUncorrectedErrors", j("""{"name":"sdc","serial":"EX","ue":3}""")))
        assertEquals(AlertTarget.Disk(null), AlertTarget.decode(DeepLink.DEST_REPLACE_DISK, null))
    }

    @Test fun resilverWatchVerdicts() {
        val start = 1_780_000_000_000L
        val w = ResilverWatch("s1", 1, "tank", start)
        val running = DisksApi.pool(j(poolJson).jsonObject)
        assertEquals(ResilverWatcher.Verdict.RUNNING, ResilverWatcher.verdict(w, running, start + 60_000))
        val finished = running.copy(healthy = true, status = "ONLINE", scan = running.scan!!.copy(state = "FINISHED", endMillis = start + 3_600_000), groups = emptyList())
        assertEquals(ResilverWatcher.Verdict.DONE, ResilverWatcher.verdict(w, finished, start + 3_700_000))
        assertEquals(ResilverWatcher.Verdict.RUNNING, ResilverWatcher.verdict(w, null, start + 3_700_000))
        assertEquals(ResilverWatcher.Verdict.EXPIRED, ResilverWatcher.verdict(w, null, start + 8 * 86_400_000L))
        val json = Json.encodeToString(ResilverWatch.serializer(), w)
        assertEquals(w, Json.decodeFromString(ResilverWatch.serializer(), json))
    }

    @Test fun candidateOrderPutsSuitableFirst() {
        val a = ReplacementCandidate("sdx", "{x}", null, null, 100, null, null, null, emptyList())
        val b = ReplacementCandidate("sdy", "{y}", null, null, 10, null, null, null, emptyList())
        assertEquals(listOf("sdx", "sdy"), DiskLogic.candidates(listOf(b, a), 50).map { it.name })
    }

    // ---------------- Review fixes (save targets, partial uploads, wizard safety) ----------------

    @Test fun saveToDownloadsUsesMediaStoreFromAndroid10() {
        assertFalse(app.truenascompanion.data.files.SaveTargets.mediaStoreDownloads(26))
        assertFalse(app.truenascompanion.data.files.SaveTargets.mediaStoreDownloads(28))
        assertTrue(app.truenascompanion.data.files.SaveTargets.mediaStoreDownloads(29))
        assertTrue(app.truenascompanion.data.files.SaveTargets.mediaStoreDownloads(37))
    }

    @Test fun saveMimeNeverMakesTheSystemRenameTheFile() {
        val platform = mapOf("jpg" to "image/jpeg", "pdf" to "application/pdf", "txt" to "text/plain")
        val mime = { n: String -> app.truenascompanion.data.files.SaveTargets.saveMime(n) { platform[it] } }
        assertEquals("image/jpeg", mime("IMG_0042.JPG"))
        assertEquals("application/pdf", mime("manual.pdf"))
        // Unknown to the platform (yaml, log, no extension): octet-stream keeps "config.yaml" instead of "config.yaml.txt".
        assertEquals("application/octet-stream", mime("config.yaml"))
        assertEquals("application/octet-stream", mime("server.log"))
        assertEquals("application/octet-stream", mime("Makefile"))
    }

    @Test fun cancellingAStartedUploadWarnsAboutThePartialFile() {
        val notYet = app.truenascompanion.ui.files.TransferState(app.truenascompanion.ui.files.TransferKind.UPLOAD, "big.iso", 0, 4_000_000_000, target = "/mnt/tank/big.iso")
        assertFalse(notYet.cancelLeavesPartial)
        val streaming = notYet.copy(done = 1_000_000_000, started = true, replacing = true)
        assertTrue(streaming.cancelLeavesPartial)
        // Downloads never leave anything on the NAS (and the phone copy is deleted).
        assertFalse(app.truenascompanion.ui.files.TransferState(app.truenascompanion.ui.files.TransferKind.DOWNLOAD, "big.iso", 5, 10, started = true).cancelLeavesPartial)
        val text = app.truenascompanion.ui.files.FileBrowserViewModel.partialMessage("Upload stopped.", "big.iso", streaming)
        assertTrue(text, text.startsWith("Upload stopped. An incomplete big.iso"))
        assertTrue(text, text.contains("in place of the original"))
        assertTrue(text, text.contains("SMB/NFS"))
        assertFalse(app.truenascompanion.ui.files.FileBrowserViewModel.partialMessage("x", "a", streaming.copy(replacing = false)).contains("original"))
    }

    @Test fun memberLookupPrefersTheDiskThatHasTheNameNow() {
        // sdb went missing and Linux gave the name "sdb" to a disk in another vdev.
        val p = DisksApi.pool(j(poolJson.replace("\"name\":\"sda1\",\"type\":\"DISK\",\"guid\":\"201\",\"status\":\"ONLINE\",\"path\":\"/dev/disk/by-partuuid/aaa\",\"disk\":\"sda\"",
            "\"name\":\"sdb1\",\"type\":\"DISK\",\"guid\":\"201\",\"status\":\"ONLINE\",\"path\":\"/dev/disk/by-partuuid/aaa\",\"disk\":\"sdb\"")).jsonObject)
        assertEquals("201", DiskLogic.memberFor(listOf(p), "sdb")?.node?.guid)
        assertEquals("201", DiskLogic.preselect(p, "sdb")?.node?.guid)
        // Without a clash the missing member is still found by its recorded name.
        assertEquals("202", DiskLogic.memberFor(listOf(DisksApi.pool(j(poolJson).jsonObject)), "sdb")?.node?.guid)
    }

    @Test fun wizardFlagsTheOldDiskPickedAsNew() {
        val p = DisksApi.pool(j(poolJson).jsonObject)
        val m = DiskLogic.memberByGuid(p, "202")
        val old = ReplacementCandidate("sdk", "{serial_lunid}WD-EXAMPLE2", "wd-example2", "WDC WD40EFRX", 4_000_787_030_016, "HDD", "ATA", null, emptyList())
        val fresh = old.copy(name = "sdl", identifier = "{serial_lunid}EX-NEW", serial = "EX-NEW")
        val ui = app.truenascompanion.ui.disks.ReplaceUi(loading = false, pool = p, member = m, selected = old, serialConfirmed = true)
        assertEquals("sdb", ui.oldName)
        assertEquals("WD-EXAMPLE2", ui.oldSerial)
        assertTrue(ui.sameSerialAsOld)
        assertFalse(ui.copy(selected = fresh).sameSerialAsOld)
        assertTrue(ui.copy(selected = fresh).canReplace)
        // Serial not confirmed, too small, or Force needed but off: Replace stays disabled.
        assertFalse(ui.copy(selected = fresh, serialConfirmed = false).canReplace)
        assertFalse(ui.copy(selected = fresh.copy(size = 2_000_000_000_000)).canReplace)
        assertFalse(ui.copy(selected = fresh.copy(exportedZpool = "old")).canReplace)
        assertTrue(ui.copy(selected = fresh.copy(exportedZpool = "old"), force = true).canReplace)
        assertFalse(ui.copy(selected = fresh, forceRequired = true).canReplace)
    }

    @Test fun uploadReportsStartBeforeTheFirstByte() {
        val progress = mutableListOf<Pair<Long, Long>>()
        val client = okhttp3.OkHttpClient()
        val req = app.truenascompanion.data.files.FileTransfers(client, "https://nas.example").uploadRequest(
            "/mnt/tank/a.bin", "tok", "a.bin", 3, open = { java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
        ) { d, t -> progress += d to t }
        req.body!!.writeTo(okio.Buffer())
        assertEquals(0L to 3L, progress.first())
        assertEquals(3L to 3L, progress.last())
        assertEquals("Token tok", req.header("Authorization"))
        assertTrue(req.url.encodedPath == "/_upload")
    }
}
