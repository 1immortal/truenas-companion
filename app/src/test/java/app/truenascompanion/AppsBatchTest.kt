package app.truenascompanion

import app.truenascompanion.data.api.Parsers
import app.truenascompanion.data.model.CatalogApp
import app.truenascompanion.data.model.LogLine
import app.truenascompanion.ui.apps.LogBuffer
import app.truenascompanion.ui.apps.filterCatalog
import app.truenascompanion.ui.apps.form.AppForm
import app.truenascompanion.ui.apps.form.PathKey
import app.truenascompanion.ui.apps.form.display
import app.truenascompanion.util.PortalUrls
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

/** Schema shaped like a 25.10 catalog `schema` (normalized by the middleware). */
class AppsBatchTest {
    private val schema = Json.parseToJsonElement(
        """{
          "groups": [{"name": "App Configuration", "description": "Configure the app"}, {"name": "Network Configuration"}],
          "questions": [
            {"variable": "app", "label": "", "group": "App Configuration", "schema": {"type": "dict", "attrs": [
              {"variable": "password", "label": "Password", "schema": {"type": "string", "required": true, "private": true, "min_length": 8}},
              {"variable": "mode", "label": "Mode", "schema": {"type": "string", "default": "basic", "enum": [
                {"value": "basic", "description": "Basic"}, {"value": "advanced", "description": "Advanced"}]}},
              {"variable": "workers", "label": "Workers", "schema": {"type": "int", "default": 2, "min": 1, "max": 16,
                "show_if": [["mode", "=", "advanced"]]}},
              {"variable": "envs", "label": "Extra env", "schema": {"type": "list", "default": [], "items": [
                {"variable": "env", "label": "Env", "schema": {"type": "dict", "attrs": [
                  {"variable": "name", "label": "Name", "schema": {"type": "string", "required": true}},
                  {"variable": "value", "label": "Value", "schema": {"type": "string"}}]}}]}}
            ]}},
            {"variable": "network", "label": "", "group": "Network Configuration", "schema": {"type": "dict", "attrs": [
              {"variable": "web_port", "label": "WebUI Port", "schema": {"type": "int", "default": 30080, "required": true, "min": 1, "max": 65535}},
              {"variable": "host_network", "label": "Host network", "schema": {"type": "boolean", "default": false}},
              {"variable": "cert", "label": "Certificate", "schema": {"type": "int", "null": true}}
            ]}},
            {"variable": "gpu", "label": "GPU", "schema": {"type": "cron", "default": {}}}
          ]
        }""",
    ).jsonObject

    @Test
    fun parsesGroupsAndDefaults() {
        val groups = AppForm.groups(schema)
        assertEquals(listOf("App Configuration", "Network Configuration"), groups.map { it.name }.take(2))
        val questions = AppForm.parseQuestions(schema)
        val values = AppForm.withDefaults(questions, JsonObject(emptyMap()))
        val app = values["app"]!!.jsonObject
        assertEquals("basic", app["mode"]!!.jsonPrimitive.content)
        assertEquals(2, app["workers"]!!.jsonPrimitive.content.toInt())
        assertEquals(30080, values["network"]!!.jsonObject["web_port"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf("gpu"), AppForm.unsupported(questions).map { it.variable })
    }

    @Test
    fun keepsExistingValuesOverDefaults() {
        val questions = AppForm.parseQuestions(schema)
        val existing = Json.parseToJsonElement("""{"network": {"web_port": 8080}}""").jsonObject
        val values = AppForm.withDefaults(questions, existing)
        assertEquals("8080", values["network"]!!.jsonObject["web_port"]!!.jsonPrimitive.content)
        assertEquals("false", values["network"]!!.jsonObject["host_network"]!!.jsonPrimitive.content)
    }

    @Test
    fun setGetAndRemoveInNestedLists() {
        val questions = AppForm.parseQuestions(schema)
        var values: kotlinx.serialization.json.JsonElement = AppForm.withDefaults(questions, JsonObject(emptyMap()))
        val envs = listOf(PathKey.Key("app"), PathKey.Key("envs"))
        val envSchema = questions.first { it.variable == "app" }.schema.attrs.first { it.variable == "envs" }.schema
        repeat(2) { i ->
            val list = AppForm.get(values, envs) as JsonArray
            values = AppForm.set(values, envs, JsonArray(list + AppForm.newItem(envSchema)))
            values = AppForm.set(values, envs + PathKey.Index(i) + PathKey.Key("name"), JsonPrimitive("K$i"))
        }
        assertEquals("K1", (AppForm.get(values, envs + PathKey.Index(1) + PathKey.Key("name")) as JsonPrimitive).content)
        values = AppForm.removeAt(values, envs, 0)
        val left = AppForm.get(values, envs) as JsonArray
        assertEquals(1, left.size)
        assertEquals("K1", left[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("app.envs[0].name", (envs + PathKey.Index(0) + PathKey.Key("name")).display())
    }

    @Test
    fun showIfHidesAndSkipsValidation() {
        val questions = AppForm.parseQuestions(schema)
        val appQ = questions.first { it.variable == "app" }.schema.attrs
        val workers = appQ.first { it.variable == "workers" }.schema
        val basic = Json.parseToJsonElement("""{"mode": "basic", "workers": 99}""").jsonObject
        assertFalse(AppForm.visible(workers, basic))
        assertTrue(AppForm.visible(workers, Json.parseToJsonElement("""{"mode": "advanced"}""").jsonObject))
        // workers=99 is out of range but hidden, so only the password issue remains
        val issues = AppForm.validate(appQ, JsonObject(basic + ("password" to JsonPrimitive("longenough"))))
        assertTrue(issues.isEmpty())
    }

    @Test
    fun validatesRequiredRangesLengthsAndListItems() {
        val questions = AppForm.parseQuestions(schema)
        val values = Json.parseToJsonElement(
            """{"app": {"password": "short", "mode": "advanced", "workers": 40, "envs": [{"name": "", "value": "x"}]},
                "network": {"web_port": 70000, "host_network": false, "cert": null}}""",
        ).jsonObject
        val byPath = AppForm.validate(questions, values).associate { it.path.display() to it.message }
        assertEquals("Password needs at least 8 characters", byPath["app.password"])
        assertEquals("Workers must be at most 16", byPath["app.workers"])
        assertEquals("Name is required", byPath["app.envs[0].name"])
        assertEquals("WebUI Port must be at most 65535", byPath["network.web_port"])
        assertNull(byPath["network.cert"]) // nullable
    }

    @Test
    fun conditionOperators() {
        assertTrue(AppForm.matches(JsonPrimitive(5), ">", JsonPrimitive(3)))
        assertTrue(AppForm.matches(JsonPrimitive("a"), "in", Json.parseToJsonElement("""["a","b"]""")))
        assertTrue(AppForm.matches(JsonPrimitive(true), "=", JsonPrimitive(true)))
        assertFalse(AppForm.matches(JsonPrimitive("1"), "!=", JsonPrimitive(1)))
    }

    @Test
    fun parsesTypedInput() {
        val s = AppForm.parseQuestions(schema).first { it.variable == "network" }.schema.attrs.first { it.variable == "web_port" }.schema
        assertEquals(JsonPrimitive(8080L), AppForm.parseInput(s, "8080"))
        assertEquals(JsonPrimitive("80x"), AppForm.parseInput(s, "80x")) // kept as text so validation can flag it
    }

    @Test
    fun appNames() {
        assertNull(AppForm.appNameError("jellyfin-2"))
        assertNotNull(AppForm.appNameError("Jellyfin"))
        assertNotNull(AppForm.appNameError("2fa"))
        assertNotNull(AppForm.appNameError("app-"))
        assertNotNull(AppForm.appNameError("a".repeat(41)))
    }

    private fun cat(name: String, title: String, pop: Int?, vararg cats: String) =
        CatalogApp(name, title, "$title media app", null, cats.toList(), "stable", false, "1.0.0", "1.0", pop)

    @Test
    fun catalogFilterAndSort() {
        val apps = listOf(cat("plex", "Plex", 2, "media"), cat("jellyfin", "Jellyfin", 1, "media"), cat("nextcloud", "Nextcloud", 3, "productivity"))
        assertEquals(listOf("jellyfin", "plex", "nextcloud"), filterCatalog(apps, "", null).map { it.name })
        assertEquals(listOf("jellyfin", "plex"), filterCatalog(apps, "", "media").map { it.name })
        // prefix matches first, then other matches ("media" appears in every description)
        assertEquals(listOf("plex"), filterCatalog(apps, "ple", null).map { it.name })
        assertEquals("nextcloud", filterCatalog(apps, "next", null).single().name)
    }

    @Test
    fun portalHostIsRewrittenToCurrentAddress() {
        assertEquals("http://nas.example.com:30080/web", PortalUrls.rewrite("http://192.168.1.10:30080/web", "https://nas.example.com:444"))
        assertEquals("http://192.168.1.10:30080/", PortalUrls.rewrite("http://192.168.1.10:30080/", null))
        assertEquals("not a url", PortalUrls.rewrite("not a url", "https://nas.example.com"))
    }

    @Test
    fun catalogDetailsPicksLatestVersion() {
        val o = Json.parseToJsonElement(
            """{"name": "jellyfin", "title": "Jellyfin", "categories": ["media"], "latest_version": "1.10.0",
                "icon_url": "https://example.com/j.png", "app_readme": "<h1>Jellyfin</h1><p>Media server</p>",
                "versions": {
                  "1.9.0": {"human_version": "10.9_1.9.0", "schema": {"questions": []}, "values": {}},
                  "1.10.0": {"human_version": "10.10_1.10.0", "app_metadata": {"app_version": "10.10.7", "screenshots": ["https://example.com/s1.png"]},
                             "schema": {"groups": [], "questions": [{"variable": "a", "label": "A", "schema": {"type": "string"}}]},
                             "values": {"a": "x"}}}}""",
        ).jsonObject
        val d = Parsers.catalogDetails(o, "community")!!
        assertEquals("1.10.0", d.version)
        assertEquals("10.10.7", d.appVersion)
        assertEquals("community", d.app.train)
        assertEquals(listOf("https://example.com/s1.png"), d.screenshots)
        assertTrue(d.readme!!.contains("Media server") && !d.readme!!.contains("<p>"))
        assertEquals("1.9.0", Parsers.catalogDetails(o, "community", "1.9.0")!!.version)
        assertTrue(Parsers.compareVersions("1.10.0", "1.9.9") > 0)
        assertTrue(Parsers.compareVersions("1.2", "1.2.1") < 0)
        assertEquals(0, Parsers.compareVersions("2.0.0", "2.0.0"))
    }

    @Test
    fun appStatsSumsNetworksAndRoundsCpu() {
        val o = Json.parseToJsonElement(
            """{"app_name": "plex", "cpu_usage": 12.6, "memory": 524288000,
                "networks": [{"interface_name": "eth0", "rx_bytes": 1000, "tx_bytes": 200}, {"interface_name": "eth1", "rx_bytes": 24, "tx_bytes": 0}],
                "blkio": {"read": 4096, "write": 8192}}""",
        ).jsonObject
        val s = Parsers.appStats(o)!!
        assertEquals(13, s.cpuPercent)
        assertEquals(1024L, s.rxBytesPerSec)
        assertEquals(200L, s.txBytesPerSec)
        assertEquals(8192L, s.blkWriteBytes)
    }

    @Test
    fun installedAppParsesContainersAndPortals() {
        val o = Json.parseToJsonElement(
            """{"name": "my-plex", "state": "RUNNING", "version": "1.2.3", "custom_app": false, "notes": "Hi",
                "metadata": {"name": "plex", "train": "stable", "icon": "https://example.com/p.png"},
                "portals": {"Web UI": "http://192.168.1.10:32400/web"},
                "active_workloads": {"containers": 1, "container_details": [
                  {"id": "abc123", "service_name": "plex", "image": "plexinc/pms-docker:1.2.3", "state": "running"}]}}""",
        ).jsonObject
        val a = Parsers.app(o)
        assertEquals("plex", a.catalogName)
        assertEquals("abc123", a.containerDetails.single().id)
        assertEquals("http://192.168.1.10:32400/web", a.portalUrl)
        assertEquals("https://example.com/p.png", a.iconUrl)
    }

    @Test
    fun jobArgumentReadsAppNameFromCreatePayload() {
        val job = Parsers.job(Json.parseToJsonElement(
            """{"id": 7, "method": "app.create", "state": "RUNNING", "arguments": [{"app_name": "jellyfin", "catalog_app": "jellyfin"}],
                "progress": {"percent": 10, "description": "Installing"}}""",
        ).jsonObject)
        assertEquals("jellyfin", job!!.firstArgument)
    }

    @Test
    fun logBufferKeepsNewestLinesAndFilters() {
        val buffer = ArrayDeque<LogLine>()
        repeat(10) { LogBuffer.append(buffer, LogLine("line $it ${if (it % 3 == 0) "ERROR" else "info"}", null), max = 5) }
        assertEquals(5, buffer.size)
        assertEquals("line 5 info", buffer.first().text)
        assertEquals(listOf("line 6 ERROR", "line 9 ERROR"), LogBuffer.filter(buffer.toList(), "error").map { it.text })
        assertEquals(5, LogBuffer.filter(buffer.toList(), " ").size)
    }

    @Test
    fun logLevels() {
        assertEquals(app.truenascompanion.ui.apps.LogLevel.ERROR, LogBuffer.level("[ERR] Error downloading subtitles"))
        assertEquals(app.truenascompanion.ui.apps.LogLevel.ERROR, LogBuffer.level("time=1 level=error msg=boom"))
        assertEquals(app.truenascompanion.ui.apps.LogLevel.WARN, LogBuffer.level("2026-09-26 WARNING disk almost full"))
        assertEquals(app.truenascompanion.ui.apps.LogLevel.OTHER, LogBuffer.level("[INF] errors=0 warnings=0 terror"))
    }
}
