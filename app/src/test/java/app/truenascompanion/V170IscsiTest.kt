package app.truenascompanion

import app.truenascompanion.data.api.FieldError
import app.truenascompanion.data.api.IscsiApi
import app.truenascompanion.data.api.StepState
import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.api.WizardStep
import app.truenascompanion.data.iscsi.AuthForm
import app.truenascompanion.data.iscsi.ExtentForm
import app.truenascompanion.data.iscsi.ExtentType
import app.truenascompanion.data.iscsi.GlobalForm
import app.truenascompanion.data.iscsi.InitiatorForm
import app.truenascompanion.data.iscsi.IscsiAuthMethod
import app.truenascompanion.data.iscsi.IscsiGroup
import app.truenascompanion.data.iscsi.IscsiLogic
import app.truenascompanion.data.iscsi.LunForm
import app.truenascompanion.data.iscsi.PortalForm
import app.truenascompanion.data.iscsi.SharingPlatform
import app.truenascompanion.data.iscsi.SizeUnit
import app.truenascompanion.data.iscsi.TargetForm
import app.truenascompanion.data.iscsi.WizardChap
import app.truenascompanion.data.iscsi.WizardExtent
import app.truenascompanion.data.iscsi.WizardForm
import app.truenascompanion.data.iscsi.WizardInitiators
import app.truenascompanion.ui.iscsi.IscsiDelete
import app.truenascompanion.ui.iscsi.IscsiDeletes
import app.truenascompanion.ui.iscsi.WizardPlan
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

/** 1.7.0: iSCSI (TrueNAS 25.10 `plugins/iscsi_/` shapes from the middleware source; example data only). */
class V170IscsiTest {
    private val calls = mutableListOf<Pair<String, List<JsonElement>>>()
    private var responder: (String, List<JsonElement>) -> JsonElement = { _, _ -> JsonNull }

    @Suppress("UNCHECKED_CAST")
    private val api = Proxy.newProxyInstance(TrueNasApi::class.java.classLoader, arrayOf(TrueNasApi::class.java)) { proxy, m, args ->
        when (m.name) {
            "rpc" -> {
                val method = args!![0] as String
                val params = (args[1] as Array<JsonElement>).toList()
                synchronized(calls) { calls += method to params }
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
    private val d = IscsiSamples.data
    private val GiB = 1L shl 30

    // ---------- parsing ----------

    @Test fun parsesMiddlewareShapes() {
        val g = IscsiLogic.global(o("""{"id":1,"basename":"iqn.2005-10.org.freenas.ctl","isns_servers":["192.168.1.9"],"listen_port":3260,"pool_avail_threshold":null,"alua":false,"iser":false}"""))
        assertEquals(listOf("192.168.1.9"), g.isnsServers); assertNull(g.poolAvailThreshold)

        val p = IscsiLogic.portal(o("""{"id":4,"tag":2,"comment":"","listen":[{"ip":"192.168.1.50","port":3260},{"ip":"::","port":3260}]}"""))!!
        assertEquals("Portal 2", p.label); assertEquals("192.168.1.50:3260, [::]:3260", p.addresses)

        val a = IscsiLogic.auth(o("""{"id":3,"tag":1,"user":"vmhost","secret":"********","peeruser":"truenas","peersecret":"********","discovery_auth":"NONE"}"""))!!
        assertTrue(a.redacted); assertNull(a.secret); assertNull(a.peersecret); assertTrue(a.mutual)
        val f = IscsiLogic.authForm(a)
        assertTrue(f.secretHidden); assertTrue(f.peerSecretHidden); assertEquals("", f.secret)

        val t = IscsiLogic.target(o("""{"id":1,"name":"vm-datastore","alias":null,"mode":"ISCSI","groups":[{"portal":1,"initiator":null,"authmethod":"CHAP","auth":2}],"auth_networks":["10.0.0.0/8"],"rel_tgt_id":1,"iscsi_parameters":null}"""))!!
        assertEquals(listOf(IscsiGroup(1, null, IscsiAuthMethod.CHAP, 2)), t.groups); assertNull(t.alias)

        // extent.query reports a DISK extent's zvol in both disk and path ("zvol/…").
        val e = IscsiLogic.extent(o("""{"id":7,"name":"vm","type":"DISK","disk":"zvol/tank/iscsi/vm","path":"zvol/tank/iscsi/vm","filesize":"0","blocksize":512,"pblocksize":false,
            "avail_threshold":null,"comment":"","naa":"0x6589cfc000000abc","insecure_tpc":true,"xen":false,"rpm":"SSD","ro":false,"enabled":true,"vendor":"TrueNAS","serial":"abc","product_id":null,"locked":false}"""))!!
        assertEquals("tank/iscsi/vm", e.zvol); assertEquals("tank/iscsi/vm", e.location)

        val s = IscsiLogic.session(o("""{"initiator":"iqn.1991-05.com.microsoft:pc1","initiator_addr":"192.168.1.30","initiator_alias":null,"target":"iqn.2005-10.org.freenas.ctl:vm","target_alias":"vm","header_digest":null,"data_digest":null,"max_data_segment_length":null,"max_receive_data_segment_length":null,"max_xmit_data_segment_length":null,"max_burst_length":null,"first_burst_length":null,"immediate_data":false,"iser":false,"offload":false}"""))!!
        assertEquals("192.168.1.30", s.initiatorAddr)
    }

    @Test fun loadAssemblesEverythingAndToleratesMissingSessions() = runBlocking {
        responder = { m, p ->
            when (m) {
                "iscsi.global.config" -> j("""{"basename":"iqn.2005-10.org.freenas.ctl","isns_servers":[],"listen_port":3260,"pool_avail_threshold":null,"alua":false}""")
                "iscsi.portal.query" -> j("""[{"id":1,"tag":1,"comment":"LAN","listen":[{"ip":"0.0.0.0","port":3260}]}]""")
                "iscsi.initiator.query" -> j("""[{"id":1,"initiators":[],"comment":""}]""")
                "iscsi.auth.query", "iscsi.targetextent.query" -> j("[]")
                "iscsi.target.query" -> j("""[{"id":1,"name":"vm","groups":[{"portal":1,"initiator":1,"authmethod":"NONE","auth":null}]}]""")
                "iscsi.extent.query" -> j("[]")
                "iscsi.global.sessions" -> throw TrueNasException.Rpc(13, "EACCES", "Not authorized")
                "service.query" -> { assertEquals("iscsitarget", p[0].toString().let { Regex("\"iscsitarget\"").find(it)?.value?.trim('"') }); j("""[{"service":"iscsitarget","state":"STOPPED","enable":false}]""") }
                "iscsi.portal.listen_ip_choices" -> j("""{"0.0.0.0":"0.0.0.0","::":"::","192.168.1.50":"192.168.1.50"}""")
                "pool.dataset.query" -> j("[]")
                "failover.licensed" -> JsonPrimitive(false)
                else -> error("unexpected $m")
            }
        }
        val r = IscsiApi(api).load()
        assertNull(r.sessions)
        assertEquals(false, r.serviceRunning)
        assertEquals(listOf("0.0.0.0", "::", "192.168.1.50"), r.listenChoices)
        assertEquals("iqn.2005-10.org.freenas.ctl:vm", r.iqn(r.targets[0]))
        assertTrue(calls.none { it.first.contains("/api/") })
    }

    // ---------- global ----------

    @Test fun globalSendsOnlyChangesAndNeverAluaWithoutHa() {
        val old = d.global
        val f = IscsiLogic.globalForm(old).copy(listenPort = "3261", threshold = "", alua = true)
        val body = IscsiLogic.globalJson(f, old, haLicensed = false)
        assertEquals(setOf("listen_port", "pool_avail_threshold"), body.keys)
        assertEquals(JsonNull, body["pool_avail_threshold"])
        assertTrue("alua" in IscsiLogic.globalJson(f, old, haLicensed = true))
        assertTrue(IscsiLogic.globalJson(IscsiLogic.globalForm(old), old, false).isEmpty())

        val e = IscsiLogic.globalErrors(GlobalForm("", "nas.example.com\n192.168.1.9:3205", "80", "100"))
        assertEquals(setOf("basename", "isns_servers", "listen_port", "pool_avail_threshold"), e.keys)
        assertTrue(IscsiLogic.globalErrors(GlobalForm(IscsiSamples.BASE, "192.168.1.9\n[2001:db8::9]:3205", "3260", "")).isEmpty())
    }

    // ---------- portals, initiators ----------

    @Test fun portalValidationAndPayload() {
        assertEquals("0.0.0.0 is already used by LAN", IscsiLogic.portalErrors(PortalForm(ips = listOf("0.0.0.0")), d, null)["listen"])
        assertTrue(IscsiLogic.portalErrors(PortalForm(ips = listOf("0.0.0.0")), d, 1).isEmpty())
        assertEquals("Add at least one address", IscsiLogic.portalErrors(PortalForm(ips = emptyList()), d, null)["listen"])
        assertEquals(o("""{"comment":"Storage VLAN","listen":[{"ip":"192.168.1.50"}]}"""), IscsiLogic.portalJson(PortalForm(" Storage VLAN ", listOf("192.168.1.50"))))
    }

    @Test fun initiatorGroupAllowAllSendsEmptyList() {
        assertEquals(JsonArray(emptyList()), IscsiLogic.initiatorJson(InitiatorForm("x", true, "iqn.a:b"))["initiators"])
        assertEquals(listOf("iqn.1991-05.com.microsoft:pc1", "192.168.1.30"),
            IscsiLogic.initiatorJson(InitiatorForm("", false, "iqn.1991-05.com.microsoft:pc1\n 192.168.1.30 \n"))["initiators"]!!.let { (it as JsonArray).map { e -> e.jsonPrimitive.content } })
        assertEquals(setOf("initiators"), IscsiLogic.initiatorErrors(InitiatorForm("", false, " ")).keys)
    }

    // ---------- CHAP ----------

    @Test fun chapSecretsRulesAndRedaction() {
        val edit = IscsiLogic.authForm(d.auths[0])
        // Untouched redacted secrets are left out of the update so TrueNAS keeps them.
        val body = IscsiLogic.authJson(edit, editing = true)
        assertFalse("secret" in body); assertFalse("peersecret" in body)
        assertTrue(IscsiLogic.authErrors(edit, d, 1).isEmpty())

        val new = AuthForm("3", "pc1", "short")
        assertEquals("Secret must be 12–16 characters", IscsiLogic.authErrors(new, d, null)["secret"])
        assertEquals("Secret can't contain #", IscsiLogic.authErrors(new.copy(secret = "exampleSecret#1"), d, null)["secret"])
        val mutual = new.copy(secret = "exampleSecret1", mutual = true, peeruser = "nas", peersecret = "exampleSecret1")
        assertEquals("Must differ from the secret", IscsiLogic.authErrors(mutual, d, null)["peersecret"])
        val ok = mutual.copy(peersecret = "examplePeerSec2")
        assertTrue(IscsiLogic.authErrors(ok, d, null).isEmpty())
        assertEquals(o("""{"tag":3,"user":"pc1","secret":"exampleSecret1","peeruser":"nas","peersecret":"examplePeerSec2","discovery_auth":"NONE"}"""), IscsiLogic.authJson(ok, false))

        // Turning mutual off clears the peer on the server; mutual discovery needs a peer and is allowed once.
        val off = IscsiLogic.authJson(ok.copy(mutual = false, discoveryAuth = IscsiAuthMethod.CHAP_MUTUAL), false)
        assertEquals("", off["peeruser"]!!.jsonPrimitive.content); assertEquals("", off["peersecret"]!!.jsonPrimitive.content)
        assertEquals("NONE", off["discovery_auth"]!!.jsonPrimitive.content)
        val other = d.copy(auths = d.auths.map { if (it.id == 2) it.copy(discoveryAuth = "CHAP_MUTUAL") else it })
        assertEquals("Only one entry may use mutual CHAP for discovery", IscsiLogic.authErrors(ok.copy(discoveryAuth = IscsiAuthMethod.CHAP_MUTUAL), other, null)["discovery_auth"])
    }

    // ---------- targets ----------

    @Test fun targetNameGroupsAndPayload() {
        assertEquals(IscsiSamples.BASE + ":scratch", d.iqn(d.targets[2]))
        assertEquals("iqn.2020-01.com.example:disk", IscsiLogic.iqn(IscsiSamples.BASE, "iqn.2020-01.com.example:disk"))
        assertTrue(IscsiLogic.targetErrors(TargetForm("Upper Case", groups = listOf(IscsiGroup(1))), d, null).containsKey("name"))
        assertTrue(IscsiLogic.targetErrors(TargetForm("scratch", groups = listOf(IscsiGroup(1))), d, null).containsKey("name"))
        assertTrue(IscsiLogic.targetErrors(TargetForm("scratch", groups = listOf(IscsiGroup(2))), d, 3).isEmpty())
        assertEquals("Each portal only once per target", IscsiLogic.targetErrors(TargetForm("new", groups = listOf(IscsiGroup(1), IscsiGroup(1))), d, null)["portal"])
        assertEquals("Choose a CHAP group", IscsiLogic.targetErrors(TargetForm("new", groups = listOf(IscsiGroup(1, null, IscsiAuthMethod.CHAP))), d, null)["auth"])
        // CHAP group 2 has no peer user, so mutual CHAP can't use it.
        assertTrue(IscsiLogic.targetErrors(TargetForm("new", groups = listOf(IscsiGroup(1, null, IscsiAuthMethod.CHAP_MUTUAL, 2))), d, null)["auth"]!!.contains("no peer user"))
        assertEquals("\"target\" is reserved", IscsiLogic.targetErrors(TargetForm("new", "target", listOf(IscsiGroup(1))), d, null)["alias"])
        assertTrue(IscsiLogic.targetErrors(TargetForm("new", groups = listOf(IscsiGroup(1)), authNetworks = "192.168.1.0/24\nbad"), d, null)["auth_networks"]!!.startsWith("bad"))

        val body = IscsiLogic.targetJson(TargetForm("lab", "", listOf(IscsiGroup(2, 1, IscsiAuthMethod.CHAP, 1)), "192.168.1.0/24"))
        assertEquals(j("""[{"portal":2,"initiator":1,"authmethod":"CHAP","auth":1}]"""), body["groups"])
        assertEquals(JsonNull, body["alias"])
        assertFalse("iscsi_parameters" in body)
    }

    // ---------- extents ----------

    @Test fun extentPayloadsAndRules() {
        val disk = IscsiLogic.extentJson(ExtentForm("spare", ExtentType.DISK, zvol = "tank/iscsi/spare"), editing = false)
        assertEquals("zvol/tank/iscsi/spare", disk["disk"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, disk["path"]); assertEquals(JsonNull, disk["serial"]); assertEquals(JsonNull, disk["product_id"])
        val file = IscsiLogic.extentJson(ExtentForm("f", ExtentType.FILE, path = "/mnt/tank/iscsi/f.img", size = "2", unit = SizeUnit.GiB), editing = true)
        assertEquals(2 * GiB, file["filesize"]!!.jsonPrimitive.content.toLong()); assertFalse("serial" in file)

        assertEquals("Already used by extent old-vm", IscsiLogic.extentErrors(ExtentForm("x", zvol = "tank/iscsi/old-vm"), d, null)["disk"])
        assertTrue(IscsiLogic.extentErrors(ExtentForm("old-vm", zvol = "tank/iscsi/old-vm", ro = true), d, 3).isEmpty())
        assertEquals("A snapshot can only be shared read-only", IscsiLogic.extentErrors(ExtentForm("x", zvol = "tank/iscsi/spare@monday"), d, null)["ro"])
        // Names collide after TrueNAS flattens . and / (vm.datastore vs vm_datastore).
        val dotted = d.copy(extents = d.extents + d.extents[0].copy(id = 9, name = "vm.datastore", disk = "zvol/tank/x"))
        assertTrue(IscsiLogic.extentNameError("vm_datastore", dotted, null)!!.startsWith("Too close"))
        val f = IscsiLogic.extentForm(d.extents[1])
        assertEquals("200" to SizeUnit.GiB, f.size to f.unit)
        assertTrue(IscsiLogic.extentErrors(f.copy(size = "100"), d, 2)["filesize"]!!.startsWith("Can only grow"))
        assertTrue(IscsiLogic.extentErrors(f.copy(size = "0.0001", unit = SizeUnit.MiB), d, 2)["filesize"]!!.contains("multiple"))
        assertEquals("No spaces in the path", IscsiLogic.extentErrors(ExtentForm("n", ExtentType.FILE, path = "/mnt/tank/my disk.img", size = "1"), d, null)["path"])
        assertEquals(listOf("tank/iscsi/spare"), d.freeZvols().map { it.id })
        assertEquals(listOf("tank/iscsi/old-vm", "tank/iscsi/spare"), d.freeZvols(except = "tank/iscsi/old-vm").map { it.id })
    }

    @Test fun lunRules() {
        assertTrue(IscsiLogic.lunErrors(LunForm(1, 2, ""), d, null)["extent"]!!.contains("backup-disk"))
        assertEquals("LUN 0 is taken on this target", IscsiLogic.lunErrors(LunForm(1, 3, "0"), d, null)["lunid"])
        assertTrue(IscsiLogic.lunErrors(LunForm(1, 3, ""), d, null).isEmpty())
        assertEquals(JsonNull, IscsiLogic.lunJson(LunForm(1, 3, ""))["lunid"])
        assertEquals("0–16382, or empty for the next free one", IscsiLogic.lunErrors(LunForm(1, 3, "16383"), d, null)["lunid"])
    }

    @Test fun serverFieldErrorsLandUnderTheirFields() {
        val e = TrueNasException.Rpc(22, "EINVAL", "Validation", listOf(
            FieldError("iscsi_extent_create.type", "Disk extents need a zvol"), FieldError("iscsi_extent_create.filesize", "Too big"), FieldError("iscsi_extent_create.vendor", "x"),
        ))
        val (mine, general) = splitFieldErrors(e, IscsiLogic.EXTENT_FIELDS, IscsiLogic.EXTENT_ALIASES)
        assertEquals(setOf("disk", "filesize"), mine.keys)
        assertEquals("vendor: x", general)
        val (t, _) = splitFieldErrors(TrueNasException.Rpc(22, "EINVAL", "V", listOf(FieldError("iscsi_target_create.groups.0.auth", "Auth group 9 does not exist"))), IscsiLogic.TARGET_FIELDS)
        assertEquals("Auth group 9 does not exist", t["auth"])
    }

    // ---------- deletes ----------

    @Test fun deletePlansExplainAndBlock() {
        val ini = IscsiDeletes.plan(IscsiDelete.Initiator(d.initiators[0]), d)
        assertTrue(ini.blocked); assertTrue(ini.text.contains("vm-datastore"))
        val auth = IscsiDeletes.plan(IscsiDelete.Auth(d.auths[1]), d)
        assertTrue(auth.blocked); assertTrue(auth.text.contains("backup-disk"))
        assertFalse(IscsiDeletes.plan(IscsiDelete.Auth(d.auths[1]), d.copy(auths = d.auths + d.auths[1].copy(id = 5, user = "backup2"))).blocked)

        val portal = IscsiDeletes.plan(IscsiDelete.Portal(d.portals[1]), d)
        assertFalse(portal.blocked); assertTrue(portal.text.contains("scratch will then be unreachable"))

        val target = IscsiDeletes.plan(IscsiDelete.Target(d.targets[0]), d)
        assertTrue(target.needsForce!!.startsWith("2 initiators are connected"))
        assertTrue(target.deleteExtentsOption!!.contains("zvols and files themselves are kept"))
        assertNull(IscsiDeletes.plan(IscsiDelete.Target(d.targets[2]), d).needsForce)

        val zvol = IscsiDeletes.plan(IscsiDelete.Extent(d.extents[0]), d)
        assertTrue(zvol.text.contains("The zvol tank/iscsi/vm-datastore and its data are kept"))
        assertNull(zvol.removeFileOption); assertTrue(zvol.needsForce != null)
        val file = IscsiDeletes.plan(IscsiDelete.Extent(d.extents[1]), d)
        assertEquals("Also delete the file /mnt/tank/iscsi/backup.img and all data in it", file.removeFileOption)
        assertNull(file.needsForce)
        assertTrue(IscsiDeletes.plan(IscsiDelete.Lun(d.luns[0]), d).needsForce != null)
    }

    @Test fun deleteCallsCarryTheOptions() = runBlocking {
        responder = { _, _ -> JsonPrimitive(true) }
        val a = IscsiApi(api)
        a.deleteExtent(2, remove = true, force = false)
        a.deleteTarget(1, force = true, deleteExtents = true)
        a.deleteLun(4, force = true)
        assertEquals(listOf(
            "iscsi.extent.delete" to listOf(JsonPrimitive(2), JsonPrimitive(true), JsonPrimitive(false)),
            "iscsi.target.delete" to listOf(JsonPrimitive(1), JsonPrimitive(true), JsonPrimitive(true)),
            "iscsi.targetextent.delete" to listOf(JsonPrimitive(4), JsonPrimitive(true)),
        ), calls)
    }

    @Test fun startServiceEnablesAtBootThenStarts() = runBlocking {
        responder = { _, _ -> JsonPrimitive(true) }
        IscsiApi(api).startService()
        assertEquals(listOf("service.update", "service.control"), calls.map { it.first })
        assertEquals("iscsitarget", calls[0].second[0].jsonPrimitive.content)
        assertEquals("START", calls[1].second[0].jsonPrimitive.content)
    }

    // ---------- wizard ----------

    private val wiz = WizardForm(
        name = "lab-disk", extent = WizardExtent.NEW_ZVOL, parent = "tank/iscsi", size = "50", unit = SizeUnit.GiB, sparse = true,
        platform = SharingPlatform.VMWARE, newPortal = true, portalIps = listOf("192.168.1.50"),
        initiators = WizardInitiators.LIST, initiatorList = "iqn.1991-05.com.microsoft:lab-pc",
        chap = WizardChap.NEW, chapUser = "lab", chapSecret = "exampleSecret1",
    )

    @Test fun wizardValidation() {
        assertTrue(IscsiLogic.wizardErrors(wiz, d).isEmpty())
        assertEquals(setOf("name"), IscsiLogic.wizardErrors(wiz.copy(name = "vm-datastore"), d).keys)
        assertEquals("0.0.0.0 is already used by another portal; pick that portal instead", IscsiLogic.wizardErrors(wiz.copy(portalIps = listOf("0.0.0.0")), d)["portal"])
        assertEquals("At least 1 MiB", IscsiLogic.wizardErrors(wiz.copy(size = ""), d)["size"])
        assertTrue(IscsiLogic.wizardErrors(wiz.copy(extent = WizardExtent.FILE, filePath = "/mnt/tank"), d).containsKey("path"))
        assertEquals("tank/iscsi/spare already exists", IscsiLogic.wizardErrors(wiz.copy(name = "spare"), d)["name"])
        assertEquals(listOf(WizardStep.ZVOL, WizardStep.EXTENT, WizardStep.PORTAL, WizardStep.INITIATORS, WizardStep.CHAP, WizardStep.TARGET, WizardStep.LUN), WizardPlan.steps(wiz))
        assertEquals(listOf(WizardStep.EXTENT, WizardStep.TARGET, WizardStep.LUN),
            WizardPlan.steps(wiz.copy(extent = WizardExtent.EXISTING_ZVOL, newPortal = false, initiators = WizardInitiators.ALL, chap = WizardChap.NONE)))
        // Defaults: first pool root, existing portal when there is one, no 0.0.0.0 clash.
        val init = WizardPlan.initial(d)
        assertEquals("tank", init.parent); assertFalse(init.newPortal); assertEquals(1, init.portalId); assertTrue(init.portalIps.isEmpty())
        assertTrue(WizardPlan.initial(IscsiSamples.empty).newPortal)
    }

    private fun createResponder(failOn: String? = null, undoFails: String? = null): (String, List<JsonElement>) -> JsonElement {
        var next = 100
        return { m, _ ->
            when {
                m == failOn -> throw TrueNasException.Rpc(22, "EINVAL", "[EINVAL] boom")
                m == undoFails -> throw TrueNasException.Rpc(16, "EBUSY", "busy")
                m == "pool.dataset.create" -> o("""{"id":"tank/iscsi/lab-disk"}""")
                m.endsWith(".create") -> o("""{"id":${next++}}""")
                m == "filesystem.stat" -> throw TrueNasException.Rpc(2, "ENOENT", "not found")
                else -> JsonPrimitive(true)
            }
        }
    }

    @Test fun wizardCreatesInOrder() = runBlocking {
        responder = createResponder()
        val states = mutableListOf<Pair<WizardStep, StepState>>()
        val r = IscsiApi(api).runWizard(wiz, d) { s, st -> states += s to st }
        assertTrue(r.ok); assertEquals(IscsiSamples.BASE + ":lab-disk", r.iqn)
        assertEquals(listOf("pool.dataset.create", "iscsi.extent.create", "iscsi.portal.create", "iscsi.initiator.create", "iscsi.auth.create", "iscsi.target.create", "iscsi.targetextent.create"),
            calls.map { it.first })
        val zvol = calls[0].second[0].jsonObject
        assertEquals("tank/iscsi/lab-disk", zvol["name"]!!.jsonPrimitive.content); assertEquals("VOLUME", zvol["type"]!!.jsonPrimitive.content)
        assertEquals(50 * GiB, zvol["volsize"]!!.jsonPrimitive.content.toLong()); assertEquals("true", zvol["sparse"]!!.jsonPrimitive.content)
        val extent = calls[1].second[0].jsonObject
        assertEquals("zvol/tank/iscsi/lab-disk", extent["disk"]!!.jsonPrimitive.content); assertEquals(512, extent["blocksize"]!!.jsonPrimitive.content.toInt())
        // New CHAP user gets the next free group number (3) and the target uses it; the LUN is 0.
        assertEquals(3, calls[4].second[0].jsonObject["tag"]!!.jsonPrimitive.content.toInt())
        assertEquals(j("""[{"portal":101,"initiator":102,"authmethod":"CHAP","auth":3}]"""), calls[5].second[0].jsonObject["groups"])
        assertEquals(o("""{"target":104,"extent":100,"lunid":0}"""), calls[6].second[0].jsonObject)
        assertTrue(states.none { it.second == StepState.FAILED })
    }

    @Test fun wizardRollsBackInReverseOnFailure() = runBlocking {
        responder = createResponder(failOn = "iscsi.target.create")
        val states = mutableMapOf<WizardStep, StepState>()
        val r = IscsiApi(api).runWizard(wiz, d) { s, st -> states[s] = st }
        assertFalse(r.ok); assertTrue(r.error!!.startsWith("Create the target failed")); assertTrue(r.undoErrors.isEmpty())
        val undo = calls.dropWhile { it.first != "iscsi.target.create" }.drop(1)
        assertEquals(listOf("iscsi.auth.delete", "iscsi.initiator.delete", "iscsi.portal.delete", "iscsi.extent.delete", "pool.dataset.delete"), undo.map { it.first })
        // The zvol extent is removed without remove=true (nothing to remove), the zvol non-recursively.
        assertEquals(listOf(JsonPrimitive(100), JsonPrimitive(false), JsonPrimitive(true)), undo[3].second)
        assertEquals("tank/iscsi/lab-disk", undo[4].second[0].jsonPrimitive.content)
        assertEquals("false", undo[4].second[1].jsonObject["recursive"]!!.jsonPrimitive.content)
        assertEquals(StepState.FAILED, states[WizardStep.TARGET]); assertEquals(StepState.UNDONE, states[WizardStep.ZVOL])
    }

    @Test fun wizardReportsUndoProblemsAndNeverOverwritesFiles() = runBlocking {
        responder = createResponder(failOn = "iscsi.targetextent.create", undoFails = "iscsi.portal.delete")
        val r = IscsiApi(api).runWizard(wiz, d)
        assertFalse(r.ok)
        assertEquals(1, r.undoErrors.size); assertTrue(r.undoErrors[0].startsWith("Create the portal"))
        assertTrue("iscsi.target.delete" in calls.map { it.first })

        // FILE: an existing file is refused before anything is created, so rollback can never delete user data.
        calls.clear()
        responder = { m, _ -> if (m == "filesystem.stat") o("""{"size":1}""") else error("must not call $m") }
        val file = wiz.copy(extent = WizardExtent.FILE, filePath = "/mnt/tank/iscsi/existing.img")
        val r2 = IscsiApi(api).runWizard(file, d)
        assertFalse(r2.ok); assertTrue(r2.error!!.contains("already exists"))
        assertEquals(listOf("filesystem.stat"), calls.map { it.first })

        // A new FILE extent that fails later is deleted with remove=true (the file was created by the wizard).
        calls.clear(); responder = createResponder(failOn = "iscsi.portal.create")
        IscsiApi(api).runWizard(file, d)
        val del = calls.first { it.first == "iscsi.extent.delete" }
        assertEquals(JsonPrimitive(true), del.second[1])
        val ext = calls.first { it.first == "iscsi.extent.create" }.second[0].jsonObject
        assertEquals("FILE", ext["type"]!!.jsonPrimitive.content); assertEquals(50 * GiB, ext["filesize"]!!.jsonPrimitive.content.toLong())
    }
}
