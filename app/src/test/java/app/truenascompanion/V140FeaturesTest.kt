package app.truenascompanion

import app.truenascompanion.data.api.FieldError
import app.truenascompanion.data.api.JsonRpcClient
import app.truenascompanion.data.api.ServiceVerb
import app.truenascompanion.data.api.ServicesApi
import app.truenascompanion.data.api.TasksApi
import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.api.fieldErrors
import app.truenascompanion.data.model.CronJobInput
import app.truenascompanion.data.model.CronSchedule
import app.truenascompanion.data.model.InitScriptInput
import app.truenascompanion.data.model.InitScriptType
import app.truenascompanion.data.model.InitScriptWhen
import app.truenascompanion.data.model.JobState
import app.truenascompanion.data.model.LastJob
import app.truenascompanion.data.model.ServiceInfo
import app.truenascompanion.data.services.ServiceForms
import app.truenascompanion.data.services.ServiceKind
import app.truenascompanion.data.services.ServiceSpecs
import app.truenascompanion.data.tasks.CronField
import app.truenascompanion.data.tasks.CronPreset
import app.truenascompanion.data.tasks.CronText
import app.truenascompanion.ui.tasks.InitForm
import app.truenascompanion.ui.tasks.splitFieldErrors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Proxy

/** 1.4.0: services, service settings, cron jobs and init/shutdown scripts (TrueNAS 25.10 shapes, example data only). */
class V140FeaturesTest {
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
    private fun cron(m: String, h: String, dom: String = "*", mon: String = "*", dow: String = "*") = CronSchedule(m, h, dom, mon, dow)

    // ---------------- Services ----------------

    @Test fun servicesAreSortedWithEditableOnesFirstAndUnknownStateFlagged() = runBlocking {
        responder = { m, _ ->
            assertEquals("service.query", m)
            j("""[
              {"id":1,"service":"nfs","enable":false,"state":"STOPPED","pids":[]},
              {"id":2,"service":"iscsitarget","enable":false,"state":"STOPPED","pids":[]},
              {"id":3,"service":"ups","enable":true,"state":"UNKNOWN","pids":[]},
              {"id":4,"service":"ssh","enable":true,"state":"RUNNING","pids":[812]},
              {"id":5,"service":"cifs","enable":true,"state":"RUNNING","pids":[901,902]}
            ]""")
        }
        val list = ServicesApi(api).services()
        assertEquals(listOf("ssh", "cifs", "nfs", "ups", "iscsitarget"), list.map { it.service })
        assertTrue(list.first { it.service == "ups" }.unknown)
        assertFalse(list.first { it.service == "ssh" }.unknown)
        assertTrue(list.first { it.service == "ssh" }.running)
        assertEquals(ServiceKind.SMB, ServiceKind.of("cifs"))
        assertNull(ServiceKind.of("iscsitarget"))
    }

    @Test fun controlRunsServiceControlJobAndWaitsForIt() = runBlocking {
        var jobSeen: Long? = null
        responder = { m, _ ->
            when (m) {
                "service.control" -> JsonPrimitive(42)
                "core.get_jobs" -> j("""[{"id":42,"state":"SUCCESS","result":true}]""")
                else -> JsonNull
            }
        }
        ServicesApi(api).control("ssh", ServiceVerb.RESTART) { jobSeen = it }
        assertEquals(42L, jobSeen)
        val (method, params) = calls.first()
        assertEquals("service.control", method)
        assertEquals(listOf(JsonPrimitive("RESTART"), JsonPrimitive("ssh"), o("""{"silent":false}""")), params)
        // Never the deprecated service.start/stop/restart.
        assertTrue(calls.none { it.first in setOf("service.start", "service.stop", "service.restart") })
    }

    @Test fun controlFallsBackToTheOldMethodsOnlyWhenServiceControlIsMissing() = runBlocking {
        responder = { m, _ ->
            when (m) {
                "service.control" -> throw TrueNasException.MethodNotFound("service.control")
                "service.stop" -> JsonPrimitive(true)
                else -> JsonNull
            }
        }
        ServicesApi(api).control("ftp", ServiceVerb.STOP)
        assertEquals(listOf("service.control", "service.stop"), calls.map { it.first })
        assertEquals(listOf(JsonPrimitive("ftp"), o("""{"silent":false}""")), calls[1].second)
    }

    @Test fun controlReportsAFalseResultAsFailure() = runBlocking {
        responder = { m, _ -> if (m == "service.control") JsonPrimitive(7) else j("""[{"id":7,"state":"SUCCESS","result":false}]""") }
        try {
            ServicesApi(api).control("nfs", ServiceVerb.START)
            fail("expected JobFailed")
        } catch (e: TrueNasException.JobFailed) {
            assertTrue(e.message!!.contains("NFS", ignoreCase = true) || e.message!!.isNotBlank())
        }
    }

    @Test fun controlSurfacesJobErrors() = runBlocking {
        responder = { m, _ -> if (m == "service.control") JsonPrimitive(8) else j("""[{"id":8,"state":"FAILED","error":"[EFAULT] ups failed to start\nTraceback"}]""") }
        try {
            ServicesApi(api).control("ups", ServiceVerb.START)
            fail("expected JobFailed")
        } catch (e: TrueNasException.JobFailed) {
            assertEquals("[EFAULT] ups failed to start", e.message)
        }
    }

    @Test fun autostartUsesServiceUpdateWithEnableOnly() = runBlocking {
        ServicesApi(api).setAutostart("cifs", true)
        assertEquals("service.update" to listOf(JsonPrimitive("cifs"), o("""{"enable":true}""")), calls.single())
    }

    @Test fun confirmationTextsExplainTheImpact() {
        val ssh = ServiceInfo(id = 4, service = "ssh", running = true, enabledOnBoot = true)
        val stop = ServicesApi.confirmText(ssh, ServiceVerb.STOP)
        assertTrue(stop.contains("SSH"))
        assertTrue(stop.contains("started again"))
        assertTrue(stop.contains("next boot"))
        assertTrue(ServicesApi.confirmText(ssh, ServiceVerb.RESTART).contains("for a moment"))
        assertTrue("ssh" in ServicesApi.ACCESS_SERVICES && "cifs" in ServicesApi.ACCESS_SERVICES)
        assertFalse("ups" in ServicesApi.ACCESS_SERVICES)
    }

    private val sshConfig = o("""{
        "id":1,"bindiface":[],"tcpport":22,"password_login_groups":[],"passwordauth":false,"kerberosauth":false,
        "tcpfwd":false,"compression":false,"sftp_log_level":"","sftp_log_facility":"","weak_ciphers":["AES128-CBC"],
        "options":"","privatekey":"-----BEGIN OPENSSH PRIVATE KEY-----example","host_dsa_key":null,"host_rsa_key":"x"
    }""")

    @Test fun sshEditSendsOnlyTheChangedFields() {
        val fields = ServiceForms.fields(ServiceKind.SSH)
        val draft = ServiceForms.toDraft(fields, sshConfig)
        assertTrue(ServiceForms.changes(fields, sshConfig, draft).isEmpty())
        val edited = draft + ("tcpport" to JsonPrimitive("2222")) + ("password_login_groups" to JsonPrimitive("admins, staff"))
        // Password groups only apply (and are only sent) with password login on.
        assertEquals(o("""{"tcpport":2222}"""), ServiceForms.changes(fields, sshConfig, edited))
        val withPasswords = edited + ("passwordauth" to JsonPrimitive(true))
        val changes = ServiceForms.changes(fields, sshConfig, withPasswords)
        assertEquals(o("""{"tcpport":2222,"passwordauth":true,"password_login_groups":["admins","staff"]}"""), changes)
        assertFalse(changes.containsKey("privatekey"))
        assertFalse(changes.containsKey("id"))
    }

    @Test fun localValidationCatchesBadValues() {
        val fields = ServiceForms.fields(ServiceKind.SSH)
        val bad = ServiceForms.toDraft(fields, sshConfig) + ("tcpport" to JsonPrimitive("70000"))
        assertEquals("Must be between 1 and 65535", ServiceForms.validate(fields, bad)["tcpport"])
        val notNumber = ServiceForms.toDraft(fields, sshConfig) + ("tcpport" to JsonPrimitive("22a"))
        assertEquals("Enter a whole number", ServiceForms.validate(fields, notNumber)["tcpport"])
        // Invalid drafts are never sent.
        assertFalse(ServiceForms.changes(fields, sshConfig, notNumber).containsKey("tcpport"))
    }

    private val snmpConfig = o("""{
        "id":1,"location":"","contact":"","traps":false,"v3":true,"community":"public","v3_username":"monitor",
        "v3_authtype":"SHA","v3_password":"********","v3_privproto":"AES","v3_privpassphrase":"********",
        "options":"","zilstat":false,"loglevel":3
    }""")

    @Test fun redactedSecretsAreNeverSentBack() {
        val fields = ServiceForms.fields(ServiceKind.SNMP)
        val draft = ServiceForms.toDraft(fields, snmpConfig)
        assertEquals(ServiceForms.REDACTED, draft["v3_password"]!!.let { (it as JsonPrimitive).content })
        val edited = draft + ("location" to JsonPrimitive("Server closet"))
        assertEquals(o("""{"location":"Server closet"}"""), ServiceForms.changes(fields, snmpConfig, edited))
        assertNull(ServiceForms.validate(fields, edited)["v3_password"])
        // A new password replaces the placeholder and is sent.
        val newPw = edited + ("v3_password" to JsonPrimitive("longer-secret"))
        assertEquals(JsonPrimitive("longer-secret"), ServiceForms.changes(fields, snmpConfig, newPw)["v3_password"])
        // Numeric picks go out as numbers.
        val lvl = draft + ("loglevel" to JsonPrimitive("6"))
        assertEquals(o("""{"loglevel":6}"""), ServiceForms.changes(fields, snmpConfig, lvl))
    }

    private val upsConfig = o("""{
        "id":1,"mode":"MASTER","identifier":"ups","remotehost":"","remoteport":3493,"driver":"","port":"",
        "options":"","optionsupsd":"","description":"","shutdown":"BATT","shutdowntimer":30,"shutdowncmd":null,
        "nocommwarntime":null,"monuser":"upsmon","monpwd":"example-pass","extrausers":"","rmonitor":false,
        "powerdown":true,"hostsync":15,"complete_identifier":"ups@localhost"
    }""")

    @Test fun upsRulesDependOnMode() {
        val fields = ServiceForms.fields(ServiceKind.UPS)
        val master = ServiceForms.toDraft(fields, upsConfig)
        val masterErrors = ServiceForms.validate(fields, master)
        assertTrue("driver" in masterErrors)
        assertTrue("port" in masterErrors)
        assertFalse("remotehost" in masterErrors)
        val slave = master + ("mode" to JsonPrimitive("SLAVE"))
        val slaveErrors = ServiceForms.validate(fields, slave)
        assertFalse("driver" in slaveErrors)
        assertTrue("remotehost" in slaveErrors)
        val ok = slave + ("remotehost" to JsonPrimitive("192.168.1.50"))
        assertTrue(ServiceForms.validate(fields, ok).isEmpty())
        // Hidden master-only fields aren't sent; complete_identifier never is.
        val changes = ServiceForms.changes(fields, upsConfig, ok)
        assertEquals(o("""{"mode":"SLAVE","remotehost":"192.168.1.50"}"""), changes)
        val noPw = ok + ("monpwd" to JsonPrimitive(""))
        assertEquals("Required", ServiceForms.validate(fields, noPw)["monpwd"])
    }

    @Test fun nfsServersAreAutomaticWhenManagedAndV4DomainClearsWithV4() {
        val raw = o("""{
            "id":1,"servers":4,"managed_nfsd":true,"allow_nonroot":false,"protocols":["NFSV3","NFSV4"],"v4_krb":false,
            "v4_domain":"example.com","bindip":[],"mountd_port":null,"rpcstatd_port":null,"rpclockd_port":null,
            "mountd_log":false,"statd_lockd_log":false,"v4_krb_enabled":false,"userd_manage_gids":false,"keytab_has_nfs_spn":false,"rdma":false
        }""")
        val cfg = ServiceSpecs.normalize(ServiceKind.NFS, raw)
        assertEquals(JsonNull, cfg["servers"])
        val fields = ServiceForms.fields(ServiceKind.NFS)
        val draft = ServiceForms.toDraft(fields, cfg)
        assertTrue(ServiceForms.changes(fields, cfg, draft).isEmpty())
        val v3only = draft + ("protocols" to JsonArray(listOf(JsonPrimitive("NFSV3"))))
        assertEquals(o("""{"protocols":["NFSV3"],"v4_domain":""}"""), ServiceForms.changes(fields, cfg, v3only))
        val none = draft + ("protocols" to JsonArray(emptyList()))
        assertEquals("Pick at least 1", ServiceForms.validate(fields, none)["protocols"])
    }

    @Test fun upsAndFtpChoicesComeFromTheNas() = runBlocking {
        responder = { m, _ ->
            when (m) {
                "ups.driver_choices" -> o("""{"usbhid-ups${'$'}Back-UPS":"APC Back-UPS (usbhid-ups)","blazer_ser${'$'}X":""}""")
                "ups.port_choices" -> j("""["auto","/dev/ttyS0"]""")
                else -> JsonNull
            }
        }
        val c = ServicesApi(api).choices(ServiceKind.UPS)
        assertEquals(listOf("APC Back-UPS (usbhid-ups)", "blazer_ser X"), c.getValue("ups.drivers").map { it.label })
        assertEquals(listOf("auto", "/dev/ttyS0"), c.getValue("ups.ports").map { it.value })
        assertEquals("auto (USB)", c.getValue("ups.ports").first().label)
    }

    @Test fun updateSendsChangesToTheServiceNamespace() = runBlocking {
        responder = { m, p ->
            assertEquals("ssh.update", m)
            JsonObject(sshConfig + (p.single() as JsonObject))
        }
        val saved = ServicesApi(api).update(ServiceKind.SSH, o("""{"tcpport":2222}"""))
        assertEquals(JsonPrimitive(2222), saved["tcpport"])
    }

    // ---------------- Validation errors ----------------

    @Test fun middlewareValidationErrorsMapToFields() {
        val data = o("""{"error":22,"errname":"EINVAL","reason":"[EINVAL] ssh_update.tcpport: Port is in use",
            "extra":[["ssh_update.tcpport","Port is in use",22],["ssh_update.bindiface.0","Invalid interface",22],["data.tcpport","Must be 1-65535",22]]}""")
        val errs = JsonRpcClient.fieldErrors(data)
        assertEquals(3, errs.size)
        assertEquals(listOf("tcpport", "bindiface", "tcpport"), errs.map { it.field })
        val e = TrueNasException.Rpc(-32602, "EINVAL", "Invalid params", errs)
        assertEquals(mapOf("tcpport" to "Port is in use\nMust be 1-65535", "bindiface" to "Invalid interface"), e.fieldErrors())
        assertTrue(RuntimeException("x").fieldErrors().isEmpty())
        assertTrue(JsonRpcClient.fieldErrors(null).isEmpty())
        assertEquals("passiveportsmin", FieldError("passiveportsmin", "x").field)
    }

    @Test fun editorSplitsKnownAndOtherFieldErrors() {
        val e = TrueNasException.Rpc(-32602, "EINVAL", "Invalid", listOf(
            FieldError("cron_job_create.user", "User does not exist"),
            FieldError("cron_job_create.schedule.minute", "Invalid minute"),
            FieldError("cron_job_create.foo", "Something else"),
        ))
        val (mine, general) = splitFieldErrors(e, setOf("user", "schedule"), mapOf("minute" to "schedule"))
        assertEquals(mapOf("user" to "User does not exist", "schedule" to "Invalid minute"), mine)
        assertEquals("foo: Something else", general)
        val (none, msg) = splitFieldErrors(TrueNasException.JobFailed("boom"), setOf("user"))
        assertTrue(none.isEmpty())
        assertEquals("boom", msg)
    }

    // ---------------- Cron schedule text ----------------

    @Test fun schedulesReadNaturally() {
        assertEquals("Every minute", CronText.describe(cron("*", "*")))
        assertEquals("Every 15 minutes", CronText.describe(cron("*/15", "*")))
        assertEquals("Every hour", CronText.describe(cron("0", "*")))
        assertEquals("Every hour at :05 past", CronText.describe(cron("5", "*")))
        assertEquals("Every 6 hours", CronText.describe(cron("0", "*/6")))
        assertEquals("Every day at 03:00", CronText.describe(cron("00", "3")))
        assertEquals("Every Sunday at 02:30", CronText.describe(cron("30", "2", dow = "0")))
        assertEquals("Every Sunday at 02:30", CronText.describe(cron("30", "2", dow = "sun")))
        assertEquals("Weekdays at 06:30", CronText.describe(cron("30", "6", dow = "1-5")))
        assertEquals("Every month on the 1st at 03:00", CronText.describe(cron("0", "3", dom = "1")))
        assertEquals("Every month on the 22nd at 03:00", CronText.describe(cron("0", "3", dom = "22")))
        assertEquals("Every year on 1 January at 00:00", CronText.describe(cron("0", "0", dom = "1", mon = "1")))
        assertTrue(CronText.describe(cron("0", "3", dom = "1-7", dow = "1")).startsWith("Custom: "))
        assertEquals("Every 30 minutes from 09:00 to 17:59 on weekdays", CronText.describe(cron("*/30", "9-17", dow = "mon-fri")))
        assertEquals("Every minute from 22:00 to 23:59", CronText.describe(cron("*", "22-23")))
        assertEquals("Every 10 minutes from 08:00 to 09:59 on Mondays and Fridays", CronText.describe(cron("*/10", "8-9", dow = "1,5")))
    }

    @Test fun cronFieldsAreValidatedLikeCroniter() {
        assertNull(CronText.fieldError(CronField.MINUTE, "*/15"))
        assertNull(CronText.fieldError(CronField.MINUTE, "0,30"))
        assertNull(CronText.fieldError(CronField.HOUR, "9-17"))
        assertNull(CronText.fieldError(CronField.DOW, "mon-fri"))
        assertNull(CronText.fieldError(CronField.DOW, "7"))
        assertNull(CronText.fieldError(CronField.MONTH, "jan,jul"))
        assertNotNull(CronText.fieldError(CronField.MINUTE, "60"))
        assertNotNull(CronText.fieldError(CronField.HOUR, "24"))
        assertNotNull(CronText.fieldError(CronField.DOM, "0"))
        assertNotNull(CronText.fieldError(CronField.MINUTE, ""))
        assertNotNull(CronText.fieldError(CronField.MINUTE, "abc"))
        assertNotNull(CronText.fieldError(CronField.MINUTE, "*/0"))
        assertFalse(CronText.isValid(cron("61", "3")))
        assertTrue(CronText.isValid(cron("0", "3")))
    }

    @Test fun presetsKeepTheChosenTime() {
        val daily = cron("30", "4")
        assertEquals(CronPreset.DAILY, CronText.presetOf(daily))
        val weekly = CronText.applyPreset(daily, CronPreset.WEEKLY)
        assertEquals(CronPreset.WEEKLY, CronText.presetOf(weekly))
        assertEquals("30" to "4", weekly.minute to weekly.hour)
        val monthly = CronText.applyPreset(weekly, CronPreset.MONTHLY)
        assertEquals(CronPreset.MONTHLY, CronText.presetOf(monthly))
        assertEquals("*", monthly.dow)
        val hourly = CronText.applyPreset(monthly, CronPreset.HOURLY)
        assertEquals(CronPreset.HOURLY, CronText.presetOf(hourly))
        assertEquals("*" to "30", hourly.hour to hourly.minute)
        assertEquals(CronPreset.CUSTOM, CronText.presetOf(cron("*/5", "*")))
        assertEquals(cron("0", "3", dow = "0"), CronText.parse("0 3 * * 0")?.copy(begin = null, end = null))
        assertNull(CronText.parse("0 3 * *"))
        assertEquals("1st", CronText.ordinal(1)); assertEquals("2nd", CronText.ordinal(2)); assertEquals("11th", CronText.ordinal(11)); assertEquals("23rd", CronText.ordinal(23))
    }

    // ---------------- Cron jobs ----------------

    @Test fun cronJobsParseAndSerializeTheMiddlewareShape() = runBlocking {
        responder = { _, _ ->
            j("""[
              {"id":3,"enabled":true,"stderr":false,"stdout":true,"schedule":{"minute":"00","hour":"3","dom":"*","month":"*","dow":"*"},
               "command":"/mnt/tank/scripts/cleanup.sh","description":"Nightly cleanup","user":"root"},
              {"id":5,"enabled":false,"stderr":true,"stdout":false,"schedule":{"minute":"0","hour":"2","dom":"*","month":"*","dow":"0"},
               "command":"midclt call disk.smart_test SHORT '[\"sda\"]'","description":"","user":"root"}
            ]""")
        }
        val jobs = TasksApi(api).cronJobs()
        assertEquals(listOf(3, 5), jobs.map { it.id })
        val a = jobs[0]
        assertEquals("Nightly cleanup", a.title)
        assertTrue(a.hideStdout); assertFalse(a.hideStderr); assertFalse(a.isSmartTest)
        assertEquals("Every day at 03:00", CronText.describe(a.schedule))
        assertTrue(jobs[1].isSmartTest)
        assertEquals("S.M.A.R.T. test", jobs[1].title)
        assertEquals("Emails errors only", a.mailText)
        assertFalse(jobs[1].enabled)

        val json = TasksApi.cronJson(CronJobInput(" Report ", "echo hi ", "backup", cron(" 15", "*/2 "), enabled = false, hideStdout = false, hideStderr = true))
        assertEquals(o("""{"description":"Report","command":"echo hi","user":"backup","enabled":false,"stdout":false,"stderr":true,
            "schedule":{"minute":"15","hour":"*/2","dom":"*","month":"*","dow":"*"}}"""), json)
    }

    @Test fun cronCrudUsesTheCronjobNamespace() = runBlocking {
        responder = { m, p ->
            when (m) {
                "cronjob.create" -> JsonObject((p[0] as JsonObject) + ("id" to JsonPrimitive(9)))
                "cronjob.update" -> JsonObject((p[1] as JsonObject) + ("id" to p[0]))
                else -> JsonPrimitive(true)
            }
        }
        val t = TasksApi(api)
        val input = CronJobInput("Backup", "rsync -a /mnt/tank/a /mnt/tank/b", "root", cron("0", "1"))
        assertEquals(9, t.createCronJob(input)?.id)
        t.updateCronJob(9, input.copy(description = "Backup 2"))
        t.setCronEnabled(9, false)
        t.deleteCronJob(9)
        assertEquals(listOf("cronjob.create", "cronjob.update", "cronjob.update", "cronjob.delete"), calls.map { it.first })
        assertEquals(listOf(JsonPrimitive(9), o("""{"enabled":false}""")), calls[2].second)
        assertEquals(listOf(JsonPrimitive(9)), calls[3].second)
    }

    @Test fun runNowFollowsTheJobAndKeepsItsOutput() = runBlocking {
        var polls = 0
        responder = { m, p ->
            when (m) {
                "cronjob.run" -> { assertEquals(listOf(JsonPrimitive(3), JsonPrimitive(false)), p); JsonPrimitive(77) }
                "core.get_jobs" -> if (polls++ == 0) j("""[{"id":77,"state":"RUNNING","progress":{"percent":10,"description":"Executing"}}]""")
                else j("""[{"id":77,"state":"SUCCESS","logs_excerpt":"cleaned 12 files\n","progress":{"percent":100,"description":"Execution finished"}}]""")
                else -> JsonNull
            }
        }
        val t = TasksApi(api)
        val id = t.runCronJob(3)
        assertEquals(77L, id)
        val seen = mutableListOf<LastJob>()
        val last = t.followJob(id) { seen += it }
        assertEquals(JobState.SUCCESS, last.state)
        assertEquals("cleaned 12 files\n", last.logExcerpt)
        assertEquals(JobState.RUNNING, seen.first().state)
        assertEquals("Executing", seen.first().progressText)
    }

    @Test fun cronLocalChecks() {
        val ok = CronJobInput("", "echo hi", "root", cron("0", "3"))
        assertTrue(TasksApi.cronErrors(ok).isEmpty())
        assertEquals(setOf("command", "user", "schedule", "description"),
            TasksApi.cronErrors(ok.copy(command = " ", user = "", schedule = cron("x", "3"), description = "d".repeat(201))).keys)
        assertTrue("user" in TasksApi.cronErrors(ok.copy(user = "two words")))
    }

    @Test fun usernamesPutRootFirstThenLocalUsers() = runBlocking {
        responder = { m, p ->
            assertEquals("user.query", m)
            assertEquals(JsonArray(listOf(JsonPrimitive("username"), JsonPrimitive("builtin"), JsonPrimitive("locked"))), (p[1] as JsonObject)["select"])
            j("""[{"username":"daemon","builtin":true},{"username":"zoe","builtin":false},{"username":"root","builtin":true},{"username":"alex","builtin":false}]""")
        }
        assertEquals(listOf("root", "alex", "zoe", "daemon"), TasksApi(api).usernames())
    }

    // ---------------- Init/shutdown scripts ----------------

    @Test fun initScriptsParseSortAndSerialize() = runBlocking {
        responder = { _, _ ->
            j("""[
              {"id":4,"type":"SCRIPT","command":"","script":"/mnt/tank/scripts/stop.sh","when":"SHUTDOWN","enabled":true,"timeout":30,"comment":"Flush caches"},
              {"id":2,"type":"COMMAND","command":"echo booted","script":"","when":"POSTINIT","enabled":false,"timeout":10,"comment":""},
              {"id":1,"type":"COMMAND","command":"modprobe example","script":null,"when":"PREINIT","enabled":true,"timeout":10,"comment":"Load module"}
            ]""")
        }
        val list = TasksApi(api).initScripts()
        assertEquals(listOf(1, 2, 4), list.map { it.id })
        assertEquals(InitScriptWhen.PREINIT, list[0].whenRun)
        assertEquals("Post-init command", list[1].title)
        assertEquals("Load module", list[0].title)
        assertEquals("/mnt/tank/scripts/stop.sh", list[2].target)
        assertEquals(30, list[2].timeout)

        val json = TasksApi.initJson(InitScriptInput(InitScriptType.COMMAND, " echo hi ", "/old/path.sh", InitScriptWhen.POSTINIT, timeout = 20, comment = " c "))
        assertEquals(o("""{"type":"COMMAND","command":"echo hi","script":"","when":"POSTINIT","enabled":true,"timeout":20,"comment":"c"}"""), json)
    }

    @Test fun initScriptCrudAndChecks() = runBlocking {
        responder = { m, p -> if (m == "initshutdownscript.create") JsonObject((p[0] as JsonObject) + ("id" to JsonPrimitive(6))) else JsonPrimitive(true) }
        val t = TasksApi(api)
        val s = InitScriptInput(InitScriptType.SCRIPT, "", "/mnt/tank/scripts/start.sh", InitScriptWhen.POSTINIT)
        assertEquals(6, t.createInitScript(s)?.id)
        t.setInitScriptEnabled(6, false)
        t.deleteInitScript(6)
        assertEquals(listOf("initshutdownscript.create", "initshutdownscript.update", "initshutdownscript.delete"), calls.map { it.first })
        // No "run now": TrueNAS 25.10 has no public method for it.
        assertTrue(calls.none { it.first.contains("execute") })

        assertTrue(TasksApi.initErrors(s).isEmpty())
        assertEquals(setOf("script"), TasksApi.initErrors(s.copy(script = "start.sh")).keys)
        assertEquals(setOf("command"), TasksApi.initErrors(s.copy(type = InitScriptType.COMMAND)).keys)
        assertEquals(setOf("timeout"), TasksApi.initErrors(s.copy(timeout = 0)).keys)
        assertEquals(setOf("timeout"), InitForm(type = InitScriptType.COMMAND, command = "x", timeout = "").errors().keys)
        assertTrue(InitForm(type = InitScriptType.COMMAND, command = "x", timeout = "15").errors().isEmpty())
    }
}
