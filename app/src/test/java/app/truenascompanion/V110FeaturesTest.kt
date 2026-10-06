package app.truenascompanion

import app.truenascompanion.data.api.AccountsApi
import app.truenascompanion.data.api.AuditApi
import app.truenascompanion.data.api.ReportingApi
import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.model.AuditFilter
import app.truenascompanion.data.model.AuditQuick
import app.truenascompanion.data.model.AuditService
import app.truenascompanion.data.model.AuditTimeRange
import app.truenascompanion.data.model.GroupInput
import app.truenascompanion.data.model.ReportData
import app.truenascompanion.data.model.ReportRange
import app.truenascompanion.data.model.ReportSeries
import app.truenascompanion.data.model.UserInput
import app.truenascompanion.ui.accounts.AccountValidation
import app.truenascompanion.ui.components.ChartViewport
import app.truenascompanion.ui.reports.ReportsViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** 1.1.0: exact RPC shapes for users/groups, reporting and audit against TrueNAS 25.10 middleware. */
class V110FeaturesTest {
    private val calls = mutableListOf<Pair<String, List<JsonElement>>>()
    private var responder: (String, List<JsonElement>) -> JsonElement = { _, _ -> JsonNull }

    @Suppress("UNCHECKED_CAST")
    private val api = Proxy.newProxyInstance(TrueNasApi::class.java.classLoader, arrayOf(TrueNasApi::class.java)) { proxy, m, args ->
        when (m.name) {
            "rpc" -> {
                val method = args!![0] as String
                val params = (args[1] as Array<JsonElement>).toList()
                calls += method to params
                responder(method, params)
            }
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            "toString" -> "fake"
            else -> null
        }
    } as TrueNasApi

    private fun j(s: String) = Json.parseToJsonElement(s)

    private val userJson = """{"id":71,"uid":3001,"username":"alex","full_name":"Alex Example","email":null,"home":"/mnt/tank/home/alex",
        "shell":"/usr/bin/zsh","group":{"id":120,"bsdgrp_gid":3001,"bsdgrp_group":"alex"},"groups":[44,90],"smb":true,
        "password_disabled":false,"ssh_password_enabled":false,"sshpubkey":"ssh-ed25519 AAAAC3Nza alex@example\n# old\nssh-rsa AAAAB3 alex@laptop",
        "locked":false,"builtin":false,"immutable":false,"local":true,"twofactor_auth_configured":true,"roles":["FULL_ADMIN"],"sudo_commands":[]}"""

    // ---- Users & groups ----

    @Test fun parsesUser() {
        val u = AccountsApi.parseUser(j(userJson).jsonObject)!!
        assertEquals("alex", u.username); assertEquals(120, u.groupId); assertEquals("alex", u.groupName)
        assertEquals(listOf(44, 90), u.groups); assertEquals(2, u.sshKeyCount)
        assertTrue(u.isAdmin); assertTrue(u.twoFactor); assertFalse(u.readOnly)
        val root = AccountsApi.parseUser(j("""{"id":1,"uid":0,"username":"root","builtin":true,"immutable":true,"local":true}""").jsonObject)!!
        assertTrue(root.readOnly)
    }

    @Test fun listsOnlyLocalAccounts() = runBlocking {
        responder = { m, _ -> if (m == "user.query") j("[$userJson]") else j("[]") }
        val users = AccountsApi(api).users()
        assertEquals(1, users.size)
        assertEquals("user.query", calls[0].first)
        assertEquals("""[["local","=",true]]""", calls[0].second[0].toString())
    }

    @Test fun createUserPayload() = runBlocking {
        AccountsApi(api).createUser(UserInput(username = "sam", fullName = "Sam Example", password = "s3cret", groups = listOf(44),
            home = "/mnt/tank/home", homeCreate = true, sshPubKey = " ssh-ed25519 AAAA sam@example "))
        val (m, p) = calls.single()
        assertEquals("user.create", m)
        val o = p[0].jsonObject
        assertEquals("sam", o["username"]!!.jsonPrimitive.content)
        assertTrue(o["group_create"]!!.jsonPrimitive.boolean)
        assertNull(o["group"])
        assertEquals("s3cret", o["password"]!!.jsonPrimitive.content)
        assertTrue(o["home_create"]!!.jsonPrimitive.boolean)
        assertEquals("ssh-ed25519 AAAA sam@example", o["sshpubkey"]!!.jsonPrimitive.content)
        assertEquals("[44]", o["groups"].toString())
    }

    @Test fun passwordlessUserSendsNullPasswordAndNeedsGroup() {
        val o = AccountsApi.createJson(UserInput("svc", "Service", passwordDisabled = true, smb = false, groupCreate = false, primaryGroup = 5))
        assertEquals(JsonNull, o["password"])
        assertEquals(5, o["group"]!!.jsonPrimitive.int)
        assertTrue(runCatching { AccountsApi.createJson(UserInput("svc", "Service", password = "x", groupCreate = false)) }.isFailure)
        assertTrue(runCatching { AccountsApi.createJson(UserInput("svc", "Service")) }.isFailure)
    }

    @Test fun updateSendsOnlyChangedFieldsAndHomeFirst() = runBlocking {
        val old = AccountsApi.parseUser(j(userJson).jsonObject)!!
        val input = UserInput(username = "alex", fullName = "Alex Example", groupCreate = false, primaryGroup = 120, groups = listOf(90, 44),
            home = "/mnt/tank/users", homeCreate = true, shell = "/usr/bin/bash", sshPubKey = old.sshPubKey, smb = true, locked = true)
        AccountsApi(api).updateUser(old, input)
        assertEquals(2, calls.size)
        assertEquals("""{"home_create":true,"home":"/mnt/tank/users"}""", calls[0].second[1].toString())
        val patch = calls[1].second[1].jsonObject
        assertEquals(setOf("shell", "locked"), patch.keys)
        assertEquals(71, calls[1].second[0].jsonPrimitive.int)
    }

    @Test fun lockResetDeleteAndGroups() = runBlocking {
        responder = { m, _ -> if (m == "group.create") JsonPrimitive(130) else JsonNull }
        val a = AccountsApi(api)
        a.setLocked(71, true); a.resetPassword(71, "n3w"); a.deleteUser(71, deleteGroup = false)
        assertEquals(130, a.createGroup(GroupInput("media", null, true, listOf(71))))
        a.updateGroup(130, GroupInput("media2", 4000, false, emptyList())); a.deleteGroup(130, deleteUsers = false)
        assertEquals(listOf("user.update", "user.update", "user.delete", "group.create", "group.update", "group.delete"), calls.map { it.first })
        assertEquals("""{"locked":true}""", calls[0].second[1].toString())
        assertEquals("""{"password":"n3w"}""", calls[1].second[1].toString())
        assertEquals("""{"delete_group":false}""", calls[2].second[1].toString())
        assertEquals("""{"name":"media","smb":true,"users":[71]}""", calls[3].second[0].toString())
        assertFalse("gid can't change on update", calls[4].second[1].jsonObject.containsKey("gid"))
        assertEquals("""{"delete_users":false}""", calls[5].second[1].toString())
    }

    @Test fun accountValidation() {
        assertNull(AccountValidation.username("alex_01"))
        assertNotNull(AccountValidation.username("-bad"))
        assertNotNull(AccountValidation.username(""))
        assertNotNull(AccountValidation.username("a".repeat(33)))
        assertNotNull(AccountValidation.password("a", "b"))
        assertNull(AccountValidation.password("a", "a"))
        assertNull(AccountValidation.sshKeys("ssh-ed25519 AAAA a@example\n\n# comment\necdsa-sha2-nistp256 AAAA"))
        assertNotNull(AccountValidation.sshKeys("hello world"))
    }

    // ---- Reporting ----

    private val cpuData = """{"name":"cpu","identifier":null,"legend":["time","cpu","cpu0"],"start":1000,"end":1020,
        "data":[[1000,10.5,20],[1010,null,30],[1020,30,40]],"aggregations":{"min":{"cpu":10.5},"mean":{"cpu":20.25},"max":{"cpu":30}}}"""

    @Test fun netdataRequestShape() = runBlocking {
        responder = { _, _ -> j("[$cpuData]") }
        val d = ReportingApi(api).data(listOf("cpu" to null, "interface" to "eth0"), ReportRange.DAY, endSec = 100_000)
        val (m, p) = calls.single()
        assertEquals("reporting.netdata_get_data", m)
        assertEquals("""[{"name":"cpu"},{"name":"interface","identifier":"eth0"}]""", p[0].toString())
        assertEquals("""{"start":13600,"end":100000,"aggregate":true}""", p[1].toString())
        assertEquals(1, d.size)
    }

    @Test fun parsesNetdataWithGaps() {
        val d = ReportingApi.parseData(j(cpuData).jsonObject)!!
        assertEquals(listOf(1000L, 1010L, 1020L), d.times.toList())
        assertEquals(listOf("cpu", "cpu0"), d.series.map { it.label })
        assertTrue(d.series[0].values[1].isNaN())
        assertEquals(20.25, d.series[0].mean!!, 0.001)
        assertEquals(30.0, d.series[0].max!!, 0.001)
        val picked = ReportsViewModel.pickSeries(app.truenascompanion.data.model.ReportKind.CPU, d)
        assertEquals(listOf("CPU"), picked.series.map { it.label })
    }

    @Test fun downsampleAveragesBucketsAndKeepsGaps() {
        val n = 1000
        val vals = FloatArray(n) { if (it in 100..199) Float.NaN else it.toFloat() }
        val d = ReportData("cpu", null, 0, n.toLong(), LongArray(n) { it.toLong() }, listOf(ReportSeries("cpu", vals)))
        val s = ReportingApi.downsample(d, 100)
        assertEquals(100, s.times.size)
        assertEquals(4.5f, s.series[0].values[0], 0.001f)
        assertTrue(s.series[0].values[15].isNaN())
        assertEquals(d, ReportingApi.downsample(d, 2000))
    }

    @Test fun mergesDiskTemperaturesByIdentifier() {
        fun disk(id: String, times: LongArray, v: Float) = ReportData("disktemp", id, 0, 0, times, listOf(ReportSeries("temperature_value", FloatArray(times.size) { v })))
        val m = ReportsViewModel.mergeByIdentifier(listOf(disk("sda", longArrayOf(0, 60, 120), 30f), disk("sdb", longArrayOf(5, 65), 40f), disk("sdc", LongArray(0), 1f)))
        assertEquals(listOf("sda", "sdb"), m.series.map { it.label })
        assertEquals(listOf(40f, 40f, 40f), m.series[1].values.toList())
    }

    @Test fun reportsFetchUsesOneDataCallWithAvailableGraphs() = runBlocking {
        responder = { m, p ->
            when (m) {
                "reporting.netdata_graphs" -> j("""[{"name":"cpu","title":"CPU","vertical_label":"%","identifiers":null},
                    {"name":"interface","title":"Interface","vertical_label":"Kb/s","identifiers":["eno1","eno2"]},
                    {"name":"disktemp","title":"Disks","vertical_label":"C","identifiers":["sda","sdb"]}]""")
                "reporting.netdata_get_data" -> JsonArray(p[0].jsonArray.map { g ->
                    val o = g.jsonObject
                    j("""{"name":${o["name"]},"identifier":${o["identifier"] ?: "null"},"legend":["time","v"],"data":[[1,1],[2,2]],"aggregations":{}}""")
                })
                else -> JsonNull
            }
        }
        val ui = ReportsViewModel.fetch(ReportingApi(api), app.truenascompanion.ui.reports.ReportsUi(iface = "eno2"))
        assertEquals(listOf("reporting.netdata_graphs", "reporting.netdata_get_data"), calls.map { it.first })
        assertEquals("""[{"name":"cpu"},{"name":"interface","identifier":"eno2"},{"name":"disktemp","identifier":"sda"},{"name":"disktemp","identifier":"sdb"}]""", calls[1].second[0].toString())
        assertEquals(listOf(app.truenascompanion.data.model.ReportKind.CPU, app.truenascompanion.data.model.ReportKind.NETWORK, app.truenascompanion.data.model.ReportKind.DISK_TEMP), ui.charts.keys.toList())
        assertEquals(listOf("sda", "sdb"), ui.charts[app.truenascompanion.data.model.ReportKind.DISK_TEMP]!!.series.map { it.label })
        assertEquals("eno2", ui.iface)
        // Graph list is cached for the next refresh.
        calls.clear(); ReportsViewModel.fetch(ReportingApi(api), ui.copy(range = ReportRange.WEEK))
        assertEquals(listOf("reporting.netdata_get_data"), calls.map { it.first })
    }

    @Test fun chartViewportZoomAndPan() {
        val z = ChartViewport().zoom(4f, 0.5f)
        assertEquals(0.375f, z.start, 1e-4f); assertEquals(0.625f, z.end, 1e-4f)
        assertTrue(z.zoomed)
        val p = z.pan(1f) // drag right by a full width → earlier
        assertEquals(0.125f, p.start, 1e-4f)
        assertEquals(0f, z.pan(10f).start, 1e-4f) // clamped
        assertEquals(1f, z.pan(-10f).end, 1e-4f)
        assertEquals(0.02f, ChartViewport().zoom(1000f, 0f).span, 1e-4f)
        assertEquals(ChartViewport(), z.zoom(0.01f, 0.3f))
        assertFalse(ChartViewport().zoomed)
    }

    // ---- Audit ----

    private fun filters(f: AuditFilter) = AuditApi.filters(f, nowSec = 1_000_000).map { it.jsonArray.toString() }

    @Test fun auditRequestMatchesWebUi() = runBlocking {
        responder = { _, p -> if (p[0].jsonObject["query-options"]!!.jsonObject["count"] != null) JsonPrimitive(12) else JsonArray(emptyList()) }
        val a = AuditApi(api)
        a.query(AuditFilter(service = AuditService.SMB), offset = 50, nowSec = 1_000_000)
        assertEquals(12, a.count(AuditFilter(), nowSec = 1_000_000))
        val q = calls[0].second[0].jsonObject
        assertEquals("audit.query", calls[0].first)
        assertEquals("""["SMB"]""", q["services"].toString())
        assertEquals("""{"limit":50,"offset":50,"order_by":["-message_timestamp"]}""", q["query-options"].toString())
        assertEquals("""[["message_timestamp",">",913600]]""", q["query-filters"].toString())
        assertEquals("""{"count":true}""", calls[1].second[0].jsonObject["query-options"].toString())
    }

    @Test fun legacyRestQuickFilterFindsRestLogins() {
        val f = filters(AuditFilter(time = AuditTimeRange.ALL, quick = setOf(AuditQuick.LEGACY_REST), address = "203.0.113.7"))
        assertEquals(listOf(
            """["address","~","203\\.0\\.113\\.7"]""",
            """["event","=","AUTHENTICATION"]""",
            """["service_data.protocol","=","LEGACY_REST"]""",
        ), f)
        // Authentication + REST logins doesn't duplicate the event filter.
        assertEquals(1, filters(AuditFilter(quick = setOf(AuditQuick.AUTHENTICATION, AuditQuick.LEGACY_REST))).count { it.contains("\"event\"") })
        assertTrue(filters(AuditFilter(quick = setOf(AuditQuick.FAILED))).contains("""["success","=",false]"""))
    }

    @Test fun basicSearchPicksEventOrUsername() {
        assertTrue(filters(AuditFilter(search = "method call")).contains("""["event","~","METHOD_CALL"]"""))
        assertTrue(filters(AuditFilter(search = "adm*")).contains("""["username","~","adm.*"]"""))
        assertTrue(filters(AuditFilter(username = "a.b", event = "LOGOUT")).containsAll(listOf("""["event","=","LOGOUT"]""", """["username","~","a\\.b"]""")))
        assertFalse(AuditQuick.LEGACY_REST.appliesTo(AuditService.SMB))
        assertTrue(AuditQuick.AUTHENTICATION.appliesTo(AuditService.SMB))
    }

    @Test fun parsesAuditEntryAndExportsCsv() {
        val raw = j("""{"audit_id":"a1","message_timestamp":1700000000,"timestamp":{"${'$'}date":1700000000000},"address":"203.0.113.7","username":"admin",
            "session":"s1","service":"MIDDLEWARE","service_data":{"vers":{"major":0,"minor":1},"origin":"203.0.113.7","protocol":"LEGACY_REST","credentials":null},
            "event":"AUTHENTICATION","event_data":{"credentials":{"credentials":"API_KEY","credentials_data":{}},"error":null},"success":true}""").jsonObject
        val e = AuditApi.parse(raw)!!
        assertEquals("API_KEY · LEGACY_REST", e.summary)
        assertEquals(1_700_000_000L, e.timestamp)
        val call = AuditApi.parse(j("""{"message_timestamp":1,"event":"METHOD_CALL","success":false,"event_data":{"method":"user.update","description":"Update user \"bob\", ok"}}""").jsonObject)!!
        assertFalse(call.success)
        val csv = AuditApi.toCsv(listOf(e, call))
        val lines = csv.trimEnd().split("\r\n")
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("timestamp,service,event,username,address,success"))
        assertTrue(lines[1].startsWith("2023-11-14T22:13:20Z,MIDDLEWARE,AUTHENTICATION,admin,203.0.113.7,true,API_KEY · LEGACY_REST,a1,s1,"))
        assertTrue(lines[2].contains("\"user.update · Update user \"\"bob\"\", ok\""))
        assertTrue(AuditApi.prettyJson(raw).contains("\n    \"audit_id\": \"a1\""))
    }

    @Test fun auditFilterActiveCount() {
        assertEquals(0, AuditFilter().activeCount)
        assertEquals(3, AuditFilter(username = "x", quick = setOf(AuditQuick.FAILED), time = AuditTimeRange.WEEK).activeCount)
    }

    @Suppress("unused") private fun unused(o: JsonObject) = o["x"]?.jsonPrimitive?.long
}
