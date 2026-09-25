package app.truenascompanion

import app.truenascompanion.data.api.Parsers
import app.truenascompanion.data.model.JobState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersJobsTest {
    @Test
    fun parsesCoreGetJobsEntry() {
        val o = Json.parseToJsonElement(
            """{"id": 42, "method": "app.upgrade", "arguments": ["plex", {"app_version": "latest"}],
               "description": null, "abortable": true, "state": "RUNNING",
               "progress": {"percent": 40, "description": "Pulling images", "extra": null},
               "error": null, "time_started": {"${'$'}date": 1700000000000}, "time_finished": null}""",
        ).jsonObject
        val job = Parsers.job(o)!!
        assertEquals(42L, job.id)
        assertEquals("plex", job.firstArgument)
        assertEquals(JobState.RUNNING, job.state)
        assertEquals(40.0, job.percent!!, 0.0)
        assertEquals("Pulling images", job.progressText)
        assertEquals(1700000000000L, job.startedMillis)
        assertTrue(job.state.active)
    }

    @Test
    fun upgradeSummaryPrefersHumanVersionAndStripsHtml() {
        val o = Json.parseToJsonElement(
            """{"latest_version": "1.2.0", "upgrade_version": "1.2.0", "upgrade_human_version": "2.0.1_1.2.0",
               "changelog": "<h2>Changes</h2><ul><li>Fix A</li><li>Fix &amp; B</li></ul>", "available_versions_for_upgrade": []}""",
        ).jsonObject
        val s = Parsers.upgradeSummary(o, "1.9.0_1.1.0")
        assertEquals("2.0.1_1.2.0", s.targetVersion)
        assertTrue(s.changelog!!.contains("• Fix & B"))
    }
}
