package app.truenascompanion.data.services

import app.truenascompanion.data.services.ServiceForms.list
import app.truenascompanion.data.services.ServiceForms.on
import app.truenascompanion.data.services.ServiceForms.text
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Settings editors of the common services, using the fields that exist in TrueNAS 25.10
 * (`api/v25_10_0/{ssh,smb,nfs,ups,snmp,ftp}.py`). Read-only fields (host keys, server SID, status flags) are left out.
 */
object ServiceSpecs {
    private val NETBIOS_NAME = Regex("""^(?![0-9]*$)[a-zA-Z0-9\-_!@#$%^&()'{}~]{1,15}$""")
    private val NETBIOS_DOMAIN = Regex("""^(?![0-9]*$)[a-zA-Z0-9.\-_!@#$%^&()'{}~]{1,15}$""")
    private val UNIX_PERM = Regex("^[0-7]{3,4}$")
    private val SNMP_WORDS = Regex("""^[-_a-zA-Z0-9\s]*$""")
    private val EMAIL = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")
    private val UPS_ID = Regex("""^[a-zA-Z0-9.\-_]+$""")
    private val NO_SPACE_HASH = Regex("""^[^ #]*$""")
    private const val NETBIOS_HINT = "1–15 letters, digits or - _ ! @ # $ % ^ & ( ) ' { } ~ (not only digits)"

    private fun opt(vararg v: String) = v.map { Option(it, it) }

    // ---------------- SSH ----------------
    val ssh = listOf(
        ServiceField("tcpport", "TCP port", FieldKind.Number(1, 65535), "Default 22", section = "Connection"),
        ServiceField("bindiface", "Listen on interfaces", FieldKind.MultiPick(), "None selected: all interfaces", section = "Connection", choicesKey = "ssh.bindiface"),
        ServiceField("passwordauth", "Allow password login", FieldKind.Toggle, "Off means SSH keys only, which is safer", section = "Sign-in"),
        ServiceField("password_login_groups", "Groups allowed to use passwords", FieldKind.TextList,
            "Comma-separated group names", section = "Sign-in", visibleIf = { it.on("passwordauth") }),
        ServiceField("kerberosauth", "Allow Kerberos authentication", FieldKind.Toggle, section = "Sign-in"),
        ServiceField("tcpfwd", "Allow TCP port forwarding", FieldKind.Toggle, "Lets SSH clients tunnel other connections through the NAS", section = "Options"),
        ServiceField("compression", "Compress connections", FieldKind.Toggle, "Can help on slow links", section = "Options"),
        ServiceField("weak_ciphers", "Weak ciphers", FieldKind.MultiPick(listOf(Option("AES128-CBC", "AES128-CBC"), Option("NONE", "None (no encryption)"))),
            "Only for very old clients; leave empty if unsure", section = "Options"),
        ServiceField("sftp_log_level", "SFTP log level", FieldKind.Pick(listOf(Option("", "Default")) + opt("QUIET", "FATAL", "ERROR", "INFO", "VERBOSE", "DEBUG", "DEBUG2", "DEBUG3")), section = "Advanced", advanced = true),
        ServiceField("sftp_log_facility", "SFTP log facility", FieldKind.Pick(listOf(Option("", "Default")) + opt("DAEMON", "USER", "AUTH", "LOCAL0", "LOCAL1", "LOCAL2", "LOCAL3", "LOCAL4", "LOCAL5", "LOCAL6", "LOCAL7")), section = "Advanced", advanced = true),
        ServiceField("options", "Extra sshd options", FieldKind.Text(multiline = true), "Added to sshd_config as-is. Mistakes can stop SSH from starting.", section = "Advanced", advanced = true),
    )

    // ---------------- SMB ----------------
    val smb = listOf(
        ServiceField("netbiosname", "NetBIOS name", FieldKind.Text(required = true, maxLength = 15, pattern = NETBIOS_NAME, patternHint = NETBIOS_HINT), "The name Windows shows for this NAS", section = "Identity"),
        ServiceField("netbiosalias", "NetBIOS aliases", FieldKind.TextList, "Other names, comma separated", section = "Identity",
            check = { d -> d.text("netbiosalias").split(',').map { it.trim() }.filter { it.isNotEmpty() }.firstOrNull { !NETBIOS_NAME.matches(it) }?.let { "\"$it\" isn't a valid NetBIOS name" } }),
        ServiceField("workgroup", "Workgroup", FieldKind.Text(required = true, maxLength = 15, pattern = NETBIOS_DOMAIN, patternHint = NETBIOS_HINT), "Must match your Windows PCs (usually WORKGROUP)", section = "Identity"),
        ServiceField("description", "Description", FieldKind.Text(), "Shown to SMB clients in some places", section = "Identity"),
        ServiceField("guest", "Guest account", FieldKind.Text(required = true), "Local user for shares with guest access (default nobody)", section = "Access"),
        ServiceField("admin_group", "Administrators group", FieldKind.Text(blankAsNull = true), "Members get full SMB admin rights. Leave empty for none.", section = "Access"),
        ServiceField("multichannel", "SMB multichannel", FieldKind.Toggle, "Faster transfers over several network links", section = "Access"),
        ServiceField("aapl_extensions", "Apple SMB extensions", FieldKind.Toggle, "Better macOS support; needed for Time Machine", section = "Access"),
        ServiceField("encryption", "Transport encryption", FieldKind.Pick(listOf(
            Option("DEFAULT", "Default (negotiate)"), Option("NEGOTIATE", "Negotiate (if the client asks)"),
            Option("DESIRED", "Desired (when supported)"), Option("REQUIRED", "Required (clients without it can't connect)"))), section = "Access"),
        ServiceField("bindip", "Listen on IP addresses", FieldKind.MultiPick(), "None selected: all addresses", section = "Access", choicesKey = "smb.bindip"),
        ServiceField("enable_smb1", "Enable SMB1", FieldKind.Toggle, "Outdated and insecure; only for very old devices", section = "Advanced", advanced = true),
        ServiceField("ntlmv1_auth", "Allow NTLMv1 authentication", FieldKind.Toggle, "Very insecure; avoid unless you must", section = "Advanced", advanced = true),
        ServiceField("localmaster", "Local master browser", FieldKind.Toggle, section = "Advanced", advanced = true),
        ServiceField("unixcharset", "Unix character set", FieldKind.Pick(), "Only change if file names aren't UTF-8", section = "Advanced", advanced = true, choicesKey = "smb.unixcharset"),
        ServiceField("filemask", "File create mask", FieldKind.Text(pattern = Regex("^(DEFAULT|[0-7]{3,4})$"), patternHint = "DEFAULT or an octal mask like 0664"), section = "Advanced", advanced = true),
        ServiceField("dirmask", "Folder create mask", FieldKind.Text(pattern = Regex("^(DEFAULT|[0-7]{3,4})$"), patternHint = "DEFAULT or an octal mask like 0775"), section = "Advanced", advanced = true),
        ServiceField("syslog", "Send logs to syslog", FieldKind.Toggle, section = "Advanced", advanced = true),
        ServiceField("debug", "Debug logging", FieldKind.Toggle, "Only while troubleshooting", section = "Advanced", advanced = true),
        ServiceField("smb_options", "Additional parameters", FieldKind.Text(multiline = true), "Unsupported smb.conf lines; can break SMB", section = "Advanced", advanced = true),
    )

    // ---------------- NFS ----------------
    private fun hasV4(d: Draft) = "NFSV4" in d.list("protocols")
    private fun nfsPort(key: String, label: String) = ServiceField(key, label, FieldKind.Number(1, 65535, nullable = true, emptyLabel = "Automatic"),
        "Empty: chosen automatically", section = "Ports & logging", advanced = true,
        check = { d -> if (d.text(key).trim() == "20049") "20049 is reserved for NFS over RDMA" else null })

    val nfs = listOf(
        ServiceField("protocols", "Protocols", FieldKind.MultiPick(listOf(Option("NFSV3", "NFSv3"), Option("NFSV4", "NFSv4")), minCount = 1), section = "Server"),
        ServiceField("servers", "Number of servers", FieldKind.Number(1, 256, nullable = true, emptyLabel = "Automatic"), "Empty: automatic (based on CPU count)", section = "Server"),
        ServiceField("bindip", "Listen on IP addresses", FieldKind.MultiPick(), "None selected: all addresses", section = "Server", choicesKey = "nfs.bindip"),
        ServiceField("v4_domain", "NFSv4 DNS domain", FieldKind.Text(), "Optional", section = "Server", visibleIf = ::hasV4, clearWhenHidden = JsonPrimitive("")),
        ServiceField("v4_krb", "Require Kerberos for NFSv4", FieldKind.Toggle, section = "Server", visibleIf = ::hasV4),
        ServiceField("allow_nonroot", "Allow non-root mount requests", FieldKind.Toggle, "Some clients (e.g. macOS) need this", section = "Options"),
        ServiceField("userd_manage_gids", "Manage groups on the server", FieldKind.Toggle, "For users in more than 16 groups", section = "Options"),
        ServiceField("rdma", "NFS over RDMA", FieldKind.Toggle, "Needs an RDMA-capable network card", section = "Options"),
        nfsPort("mountd_port", "mountd port"),
        nfsPort("rpcstatd_port", "rpc.statd port"),
        nfsPort("rpclockd_port", "rpc.lockd port"),
        ServiceField("mountd_log", "Log mountd", FieldKind.Toggle, section = "Ports & logging", advanced = true),
        ServiceField("statd_lockd_log", "Log statd and lockd", FieldKind.Toggle, section = "Ports & logging", advanced = true),
    )

    // ---------------- UPS ----------------
    private fun master(d: Draft) = d.text("mode") != "SLAVE"
    val ups = listOf(
        ServiceField("mode", "Mode", FieldKind.Pick(listOf(Option("MASTER", "Master: the UPS is plugged into this NAS"), Option("SLAVE", "Slave: monitor a UPS on another NUT server"))), section = "UPS"),
        ServiceField("identifier", "Identifier", FieldKind.Text(required = true, pattern = UPS_ID, patternHint = "Letters, digits, . - and _ only"), "Name of the UPS, e.g. ups", section = "UPS"),
        ServiceField("driver", "Driver", FieldKind.Pick(), "Pick your UPS model", section = "UPS", choicesKey = "ups.drivers", visibleIf = ::master,
            check = { d -> if (d.text("driver").isBlank()) "Required in master mode" else null }),
        ServiceField("port", "Port or hostname", FieldKind.Pick(custom = true), "auto works for most USB UPS units; pick Other… for a network UPS", section = "UPS", choicesKey = "ups.ports", visibleIf = ::master,
            check = { d -> if (d.text("port").isBlank()) "Required in master mode" else null }),
        ServiceField("remotehost", "Remote host", FieldKind.Text(), "Address of the NUT server, e.g. 192.168.1.50", section = "UPS", visibleIf = { !master(it) },
            check = { d -> if (d.text("remotehost").isBlank()) "Required in slave mode" else null }),
        ServiceField("remoteport", "Remote port", FieldKind.Number(1, 65535), "Default 3493", section = "UPS", visibleIf = { !master(it) }),
        ServiceField("description", "Description", FieldKind.Text(), section = "UPS"),
        ServiceField("monuser", "Monitor user", FieldKind.Text(required = true, pattern = NO_SPACE_HASH, patternHint = "No spaces or #"), section = "Monitoring"),
        ServiceField("monpwd", "Monitor password", FieldKind.Text(secret = true, required = true, pattern = NO_SPACE_HASH, patternHint = "No spaces or #"), section = "Monitoring"),
        ServiceField("rmonitor", "Remote monitoring", FieldKind.Toggle, "Let other NUT clients on the network watch this UPS", section = "Monitoring"),
        ServiceField("shutdown", "Shut down", FieldKind.Pick(listOf(Option("LOWBATT", "When the battery is low"), Option("BATT", "After a while on battery"))), section = "Shutdown"),
        ServiceField("shutdowntimer", "Shutdown timer (seconds)", FieldKind.Number(0, 86_400), "How long to run on battery before shutting down", section = "Shutdown",
            visibleIf = { it.text("shutdown") == "BATT" }),
        ServiceField("powerdown", "Turn off the UPS afterwards", FieldKind.Toggle, "The UPS powers off once the NAS has shut down", section = "Shutdown"),
        ServiceField("shutdowncmd", "Shutdown command", FieldKind.Text(blankAsNull = true), "Empty: the default (poweroff)", section = "Shutdown", advanced = true),
        ServiceField("nocommwarntime", "No-communication warning (seconds)", FieldKind.Number(0, 86_400, nullable = true, emptyLabel = "Default"), "Empty: default (300)", section = "Advanced", advanced = true),
        ServiceField("hostsync", "Wait for secondaries (seconds)", FieldKind.Number(0, 86_400), "Default 15", section = "Advanced", advanced = true),
        ServiceField("extrausers", "Extra users (upsd.users)", FieldKind.Text(secret = true, multiline = true), "Can contain passwords", section = "Advanced", advanced = true),
        ServiceField("options", "Extra driver options (ups.conf)", FieldKind.Text(multiline = true), section = "Advanced", advanced = true),
        ServiceField("optionsupsd", "Extra upsd options (upsd.conf)", FieldKind.Text(multiline = true), section = "Advanced", advanced = true),
    )

    // ---------------- SNMP ----------------
    private fun v3(d: Draft) = d.on("v3")
    private fun secretSet(d: Draft, key: String) = d.text(key).isNotBlank()
    val snmp = listOf(
        ServiceField("location", "Location", FieldKind.Text(), "Where the NAS is, e.g. Server closet", section = "System"),
        ServiceField("contact", "Contact", FieldKind.Text(), "Email or name of the admin", section = "System",
            check = { d -> d.text("contact").trim().let { c -> if (c.isEmpty() || (if ('@' in c) EMAIL.matches(c) else SNMP_WORDS.matches(c))) null else "An email address, or a name with letters, digits, spaces, - and _" } }),
        ServiceField("community", "Community", FieldKind.Text(secret = true, pattern = SNMP_WORDS, patternHint = "Letters, digits, spaces, - and _ only"), "Shared secret for SNMP v1/v2c (default public)", section = "SNMP v1/v2c",
            check = { d -> if (!v3(d) && !secretSet(d, "community")) "Required when SNMPv3 is off" else null }),
        ServiceField("traps", "Send traps", FieldKind.Toggle, section = "SNMP v1/v2c"),
        ServiceField("v3", "SNMPv3", FieldKind.Toggle, "Encrypted, user-based access", section = "SNMPv3"),
        ServiceField("v3_username", "Username", FieldKind.Text(maxLength = 20), section = "SNMPv3", visibleIf = ::v3,
            check = { d -> if (d.text("v3_username").isBlank()) "Required for SNMPv3" else null }),
        ServiceField("v3_authtype", "Authentication", FieldKind.Pick(listOf(Option("", "Not set"), Option("MD5", "MD5"), Option("SHA", "SHA"))), section = "SNMPv3", visibleIf = ::v3,
            check = { d -> if (d.text("v3_authtype").isBlank()) "Required for SNMPv3" else null }),
        ServiceField("v3_password", "Password", FieldKind.Text(secret = true), "At least 8 characters", section = "SNMPv3", visibleIf = ::v3,
            check = { d -> val p = d.text("v3_password"); when { p.isBlank() -> "Required for SNMPv3"; p != ServiceForms.REDACTED && p.length < 8 -> "At least 8 characters"; else -> null } }),
        ServiceField("v3_privproto", "Privacy protocol", FieldKind.Pick(listOf(Option(null, "None"), Option("AES", "AES"), Option("DES", "DES"))), "Encrypts SNMP traffic", section = "SNMPv3", visibleIf = ::v3),
        ServiceField("v3_privpassphrase", "Privacy passphrase", FieldKind.Text(secret = true, blankAsNull = true), section = "SNMPv3",
            visibleIf = { v3(it) && it["v3_privproto"].let { p -> p != null && p !is JsonNull } },
            check = { d -> if (!secretSet(d, "v3_privpassphrase")) "Required with a privacy protocol" else null }),
        ServiceField("zilstat", "ZFS ZIL statistics", FieldKind.Toggle, section = "Advanced", advanced = true),
        ServiceField("loglevel", "Log level", FieldKind.Pick(listOf("Emergency", "Alert", "Critical", "Error", "Warning", "Notice", "Info", "Debug").mapIndexed { i, l -> Option("$i", "$i · $l") }, numeric = true), section = "Advanced", advanced = true),
        ServiceField("options", "Extra snmpd options", FieldKind.Text(multiline = true), "Can stop SNMP from working if wrong", section = "Advanced", advanced = true),
    )

    // ---------------- FTP ----------------
    private fun tls(d: Draft) = d.on("tls")
    private fun passive(d: Draft): String? {
        val min = d.text("passiveportsmin").trim().toLongOrNull() ?: return null
        val max = d.text("passiveportsmax").trim().toLongOrNull() ?: return null
        return when {
            min != 0L && min !in 1024..65535 -> null
            (min == 0L) != (max == 0L) -> "Set both passive ports, or both to 0"
            max != 0L && max <= min -> "Must be greater than the minimum"
            else -> null
        }
    }
    private fun passivePort(key: String, label: String, cross: Boolean) = ServiceField(key, label, FieldKind.Number(0, 65535), "0 = default", section = "Passive mode", advanced = true,
        check = { d -> val v = d.text(key).trim().toLongOrNull(); if (v != null && v != 0L && v < 1024) "0, or 1024–65535" else if (cross) passive(d) else null })
    private val tlsPolicies = listOf(
        Option("", "Default"), Option("on", "Required for login and data"), Option("off", "Off"),
        Option("auth", "Login only"), Option("ctrl", "Control channel"), Option("data", "Data only"), Option("!data", "Not for data"),
        Option("ctrl+data", "Control and data"), Option("ctrl+!data", "Control, not data"), Option("auth+data", "Login and data"), Option("auth+!data", "Login, not data"),
    )
    private fun tlsOpt(key: String, label: String) = ServiceField(key, label, FieldKind.Toggle, section = "TLS options", advanced = true, visibleIf = ::tls)
    private fun bw(key: String, label: String) = ServiceField(key, label, FieldKind.Number(0, Int.MAX_VALUE.toLong()), "KiB/s, 0 = unlimited", section = "Bandwidth", advanced = true)

    val ftp = listOf(
        ServiceField("port", "Port", FieldKind.Number(1, 65535), "Default 21", section = "Connection"),
        ServiceField("clients", "Max clients", FieldKind.Number(1, 10_000), section = "Connection"),
        ServiceField("ipconnections", "Connections per IP", FieldKind.Number(0, 1000), "0 = unlimited", section = "Connection"),
        ServiceField("loginattempt", "Login attempts", FieldKind.Number(0, 1000), "Failed tries before disconnecting; 0 = unlimited", section = "Connection"),
        ServiceField("timeout", "Idle timeout (seconds)", FieldKind.Number(0, 10_000), "0 = never", section = "Connection"),
        ServiceField("timeout_notransfer", "No-transfer timeout (seconds)", FieldKind.Number(0, 10_000), "0 = never", section = "Connection"),
        ServiceField("onlylocal", "Allow local user login", FieldKind.Toggle, "TrueNAS users sign in with their password", section = "Access"),
        ServiceField("onlyanonymous", "Allow anonymous login", FieldKind.Toggle, "Anyone can sign in without a password", section = "Access"),
        ServiceField("anonpath", "Anonymous folder", FieldKind.Text(blankAsNull = true), "A folder on a pool, e.g. /mnt/tank/public", section = "Access",
            visibleIf = { it.on("onlyanonymous") },
            check = { d -> val p = d.text("anonpath").trim(); when { p.isEmpty() -> "Required for anonymous login"; !p.startsWith("/mnt/") -> "Must be a folder under /mnt"; else -> null } }),
        ServiceField("defaultroot", "Keep users in their home folder", FieldKind.Toggle, "chroot: users can't browse outside it", section = "Access"),
        ServiceField("banner", "Welcome message", FieldKind.Text(multiline = true), section = "Access"),
        ServiceField("tls", "Enable TLS (FTPS)", FieldKind.Toggle, "Encrypts logins and transfers", section = "TLS"),
        ServiceField("tls_policy", "TLS policy", FieldKind.Pick(tlsPolicies), section = "TLS", visibleIf = ::tls),
        ServiceField("ssltls_certificate", "Certificate", FieldKind.Pick(numeric = true), section = "TLS", choicesKey = "certificates", visibleIf = ::tls,
            check = { d -> if (d["ssltls_certificate"].let { it == null || it is JsonNull || d.text("ssltls_certificate").isBlank() }) "Pick a certificate for TLS" else null }),
        ServiceField("fxp", "Allow FXP (server-to-server)", FieldKind.Toggle, section = "Transfers", advanced = true),
        ServiceField("resume", "Allow resuming transfers", FieldKind.Toggle, section = "Transfers", advanced = true),
        ServiceField("reversedns", "Reverse DNS lookups", FieldKind.Toggle, section = "Transfers", advanced = true),
        ServiceField("ident", "Ident lookups (RFC 1413)", FieldKind.Toggle, section = "Transfers", advanced = true),
        ServiceField("filemask", "File mask", FieldKind.Text(required = true, pattern = UNIX_PERM, patternHint = "Octal, e.g. 077"), section = "Transfers", advanced = true),
        ServiceField("dirmask", "Folder mask", FieldKind.Text(required = true, pattern = UNIX_PERM, patternHint = "Octal, e.g. 022"), section = "Transfers", advanced = true),
        ServiceField("masqaddress", "Public address", FieldKind.Text(), "For passive mode behind NAT, e.g. nas.example.com", section = "Passive mode", advanced = true),
        passivePort("passiveportsmin", "Passive ports from", cross = false),
        passivePort("passiveportsmax", "Passive ports to", cross = true),
        bw("localuserbw", "Local users upload"), bw("localuserdlbw", "Local users download"),
        bw("anonuserbw", "Anonymous upload"), bw("anonuserdlbw", "Anonymous download"),
        tlsOpt("tls_opt_allow_client_renegotiations", "Allow client renegotiation"),
        tlsOpt("tls_opt_allow_dot_login", "Allow .tlslogin files"),
        tlsOpt("tls_opt_allow_per_user", "Allow per-user TLS settings"),
        tlsOpt("tls_opt_common_name_required", "Require certificate common name"),
        tlsOpt("tls_opt_enable_diags", "TLS diagnostics logging"),
        tlsOpt("tls_opt_export_cert_data", "Export certificate data"),
        tlsOpt("tls_opt_no_empty_fragments", "No empty fragments"),
        tlsOpt("tls_opt_no_session_reuse_required", "Don't require session reuse"),
        tlsOpt("tls_opt_stdenvvars", "Export standard TLS variables"),
        tlsOpt("tls_opt_dns_name_required", "Require DNS name in client certificate"),
        tlsOpt("tls_opt_ip_address_required", "Require IP address in client certificate"),
        ServiceField("options", "Extra ProFTPD directives", FieldKind.Text(multiline = true), "Can stop FTP from starting if wrong", section = "Advanced", advanced = true),
    )

    /**
     * Adjusts a config before editing: NFS reports the automatic server count as a number, so `servers` is shown as
     * empty ("Automatic") while `managed_nfsd` is true.
     */
    fun normalize(kind: ServiceKind, config: JsonObject): JsonObject = when (kind) {
        ServiceKind.NFS -> if ((config["managed_nfsd"] as? JsonPrimitive)?.booleanOrNull == true) JsonObject(config + ("servers" to JsonNull)) else config
        else -> config
    }
}
