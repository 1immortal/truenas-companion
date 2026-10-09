package app.truenascompanion

import app.truenascompanion.data.iscsi.ExtentType
import app.truenascompanion.data.iscsi.IscsiAuth
import app.truenascompanion.data.iscsi.IscsiAuthMethod
import app.truenascompanion.data.iscsi.IscsiData
import app.truenascompanion.data.iscsi.IscsiExtent
import app.truenascompanion.data.iscsi.IscsiGlobal
import app.truenascompanion.data.iscsi.IscsiGroup
import app.truenascompanion.data.iscsi.IscsiInitiatorGroup
import app.truenascompanion.data.iscsi.IscsiLun
import app.truenascompanion.data.iscsi.IscsiPortal
import app.truenascompanion.data.iscsi.IscsiSession
import app.truenascompanion.data.iscsi.IscsiTarget
import app.truenascompanion.data.model.Dataset

/** Example iSCSI configuration for 1.7.0 tests and previews (example addresses and names only). */
object IscsiSamples {
    private const val GiB = 1L shl 30
    const val BASE = "iqn.2005-10.org.freenas.ctl"

    private fun ds(id: String, type: String = "FILESYSTEM", volsize: Long? = null) =
        Dataset(id, id.substringBefore('/'), type, 10 * GiB, 900 * GiB, false, false, if (type == "VOLUME") null else "/mnt/$id", volsize = volsize)

    val datasets = listOf(
        ds("tank"), ds("tank/.system"), ds("tank/iscsi"),
        ds("tank/iscsi/vm-datastore", "VOLUME", 500 * GiB), ds("tank/iscsi/old-vm", "VOLUME", 64 * GiB), ds("tank/iscsi/spare", "VOLUME", 100 * GiB),
        ds("tank/media"),
    )

    val portals = listOf(
        IscsiPortal(1, 1, "LAN", listOf("0.0.0.0")),
        IscsiPortal(2, 2, "", listOf("2001:db8::50")),
    )
    val initiators = listOf(
        IscsiInitiatorGroup(1, listOf("iqn.1998-01.com.vmware:esxi-01", "iqn.1998-01.com.vmware:esxi-02"), "Hypervisors"),
        IscsiInitiatorGroup(2, listOf("iqn.1991-05.com.microsoft:backup-pc"), ""),
    )
    val auths = listOf(
        IscsiAuth(1, 1, "vmhost", null, "truenas", null, "NONE", redacted = true),
        IscsiAuth(2, 2, "backup", null, "", null, "NONE", redacted = true),
    )
    val targets = listOf(
        IscsiTarget(1, "vm-datastore", "VM datastore", groups = listOf(IscsiGroup(1, 1, IscsiAuthMethod.CHAP_MUTUAL, 1)), authNetworks = listOf("192.168.1.0/24")),
        IscsiTarget(2, "backup-disk", null, groups = listOf(IscsiGroup(1, 2, IscsiAuthMethod.CHAP, 2))),
        IscsiTarget(3, "scratch", null, groups = listOf(IscsiGroup(2))),
    )
    val extents = listOf(
        IscsiExtent(1, "vm-datastore", ExtentType.DISK, disk = "zvol/tank/iscsi/vm-datastore", path = "zvol/tank/iscsi/vm-datastore", blocksize = 512, serial = "a1b2c3d4e5f6001"),
        IscsiExtent(2, "backup-disk", ExtentType.FILE, path = "/mnt/tank/iscsi/backup.img", filesize = 200 * GiB, blocksize = 4096, rpm = "7200", serial = "a1b2c3d4e5f6002"),
        IscsiExtent(3, "old-vm", ExtentType.DISK, disk = "zvol/tank/iscsi/old-vm", path = "zvol/tank/iscsi/old-vm", ro = true, serial = "a1b2c3d4e5f6003"),
    )
    val luns = listOf(IscsiLun(1, 1, 0, 1), IscsiLun(2, 2, 0, 2))
    val sessions = listOf(
        IscsiSession("iqn.1998-01.com.vmware:esxi-01", "192.168.1.21", "$BASE:vm-datastore", "VM datastore"),
        IscsiSession("iqn.1998-01.com.vmware:esxi-02", "192.168.1.22", "$BASE:vm-datastore", "VM datastore"),
    )

    val data = IscsiData(
        IscsiGlobal(BASE, emptyList(), 3260, 80), portals, initiators, auths, targets, extents, luns, sessions,
        serviceRunning = true, serviceOnBoot = true, listenChoices = listOf("0.0.0.0", "::", "192.168.1.50", "2001:db8::50"), datasets = datasets,
    )

    val empty = IscsiData(IscsiGlobal(BASE), sessions = emptyList(), serviceRunning = false, serviceOnBoot = false,
        listenChoices = listOf("0.0.0.0", "::", "192.168.1.50"), datasets = datasets)
}
