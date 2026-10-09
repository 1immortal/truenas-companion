package app.truenascompanion.ui.certs

import app.truenascompanion.ui.components.Tag
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.truenascompanion.data.model.AcmeAuthenticator
import app.truenascompanion.data.model.AcmeRequest
import app.truenascompanion.data.model.CertImport
import app.truenascompanion.data.model.CertKind
import app.truenascompanion.data.model.CertStatus
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.NasCertificate
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.*
import app.truenascompanion.ui.components.Scaffold
import app.truenascompanion.ui.components.TopAppBar
import java.text.DateFormat
import java.util.Date

sealed interface CertAction {
    data class Details(val cert: NasCertificate) : CertAction
    data class UseForWebUi(val cert: NasCertificate) : CertAction
    data class RenewDays(val cert: NasCertificate) : CertAction
    data object Import : CertAction
    data object Acme : CertAction
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CertificatesScreen(onBack: () -> Unit, onReviewServer: (String) -> Unit, highlight: String? = null) {
    val vm = appViewModel { CertificatesViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val warnDays by vm.warnDays.collectAsStateWithLifecycle(initialValue = 14)
    val snackbar = remember { SnackbarHostState() }
    var review by remember { mutableStateOf<CertEvent.ReviewNeeded?>(null) }
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is CertEvent.Message -> snackbar.showSnackbar(e.text)
                is CertEvent.ReviewNeeded -> review = e
            }
        }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var action by remember { mutableStateOf<CertAction?>(null) }
    val now = remember(state) { System.currentTimeMillis() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Certificates") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (state is UiState.Success && busy == null) AddCertButton(onImport = { action = CertAction.Import }, onAcme = { vm.loadAcmeOptions(); action = CertAction.Acme })
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(5, 110.dp)
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.refresh() }
                is UiState.Success -> CertificatesContent(s.data, now, warnDays, tab, onTab = { tab = it }, busy = busy, highlight = highlight, onAction = { action = it })
            }
        }
    }

    val data = (state as? UiState.Success)?.data
    when (val a = action) {
        null -> Unit
        is CertAction.Details -> CertDetailSheet(a.cert, now, warnDays, isWebUi = data?.uiCertId == a.cert.id, onDismiss = { action = null })
        is CertAction.UseForWebUi -> ConfirmDialog(
            title = "Use ${a.cert.name} for the web UI?",
            text = "TrueNAS restarts its web server with this certificate and every open session (browser and this app) is disconnected. " +
                "Browsers that don't trust it will show security warnings, and API clients that pin the old certificate stop working.\n\n" +
                "Next, this app asks you to check and trust the new certificate. If that doesn't happen within 10 minutes, TrueNAS switches back to the previous certificate by itself.",
            confirmLabel = "Switch certificate", destructive = true, strongAuth = true, icon = Icons.Rounded.GppMaybe,
            onConfirm = { action = null; vm.useForWebUi(a.cert) }, onDismiss = { action = null },
        )
        is CertAction.RenewDays -> RenewDaysDialog(a.cert, onDismiss = { action = null }) { d -> action = null; vm.setRenewDays(a.cert, d) }
        CertAction.Import -> CertSheet(onDismiss = { action = null }) {
            ImportCertForm(onCancel = { action = null }) { input -> action = null; vm.import(input) }
        }
        CertAction.Acme -> {
            val options by vm.acme.collectAsStateWithLifecycle()
            val csrDomains by vm.csrDomains.collectAsStateWithLifecycle()
            CertSheet(onDismiss = { action = null }) {
                when (val o = options) {
                    null, UiState.Loading -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    is UiState.Error -> ErrorState(o.message, onRetry = { vm.loadAcmeOptions() })
                    is UiState.Success -> AcmeCertForm(
                        options = o.data,
                        csrs = data?.certs.orEmpty().filter { it.kind == CertKind.CSR },
                        csrDomains = csrDomains, onCsrPicked = { vm.loadCsrDomains(it) },
                        onCancel = { action = null },
                    ) { req -> action = null; vm.createAcme(req) }
                }
            }
        }
    }
    review?.let { r ->
        AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Rounded.VerifiedUser, null) },
            title = { Text("Trust the new certificate") },
            text = { Text("${r.serverName} is restarting its web server with the new certificate. Open the connection settings, test the connection and check the certificate before trusting it. TrueNAS keeps the change once this app reconnects; otherwise it rolls back in 10 minutes.") },
            confirmButton = { GlowButton(onClick = { review = null; onReviewServer(r.serverId) }) { Text("Review now") } },
            dismissButton = { TextButton(onClick = { review = null }) { Text("Later") } },
        )
    }
}

@Composable
private fun AddCertButton(onImport: () -> Unit, onAcme: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ExtendedFloatingActionButton(onClick = { menu = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Add") })
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Import certificate") }, leadingIcon = { Icon(Icons.Rounded.UploadFile, null) }, onClick = { menu = false; onImport() })
            DropdownMenuItem(text = { Text("Request ACME certificate") }, leadingIcon = { Icon(Icons.Rounded.Public, null) }, onClick = { menu = false; onAcme() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CertSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), content = content)
    }
}

/** Stateless list (screenshot tests render it with example data). */
@Composable
fun CertificatesContent(
    data: CertsData, now: Long, warnDays: Int, tab: Int, onTab: (Int) -> Unit,
    busy: String? = null, highlight: String? = null, onAction: (CertAction) -> Unit,
) {
    val kinds = listOf(CertKind.CERTIFICATE, CertKind.CA, CertKind.CSR)
    val shown = data.certs.filter { it.kind == kinds[tab] }
    val attention = data.certs.count { it.status(now, warnDays) == CertStatus.EXPIRING || it.status(now, warnDays) == CertStatus.EXPIRED }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            kinds.forEachIndexed { i, k ->
                val n = data.certs.count { it.kind == k }
                Tab(selected = tab == i, onClick = { onTab(i) }, text = { Text(when (k) { CertKind.CERTIFICATE -> "Certificates"; CertKind.CA -> "CAs"; CertKind.CSR -> "CSRs" } + " ($n)") })
            }
        }
        if (busy != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(busy, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            if (attention > 0 && tab != 2) item {
                InfoBanner(if (attention == 1) "1 certificate needs attention" else "$attention certificates need attention",
                    health = if (data.certs.any { it.status(now, warnDays) == CertStatus.EXPIRED }) Health.CRITICAL else Health.WARNING)
            }
            if (shown.isEmpty()) item {
                EmptyState(Icons.Rounded.VerifiedUser, "Nothing here", when (tab) {
                    0 -> "Import a certificate or request one from Let's Encrypt with the Add button."
                    1 -> "No certificate authorities on this NAS."
                    else -> "CSRs are created when you request an ACME certificate."
                })
            }
            items(shown, key = { "c${it.id}" }) { cert ->
                CertCard(cert, now, warnDays, isWebUi = data.uiCertId == cert.id, canUseForWebUi = cert.id in data.uiChoices && data.uiCertId != cert.id,
                    highlighted = highlight != null && cert.name == highlight, onAction = onAction)
            }
        }
    }
}

fun expiryLabel(c: NasCertificate, now: Long): String {
    val d = c.daysLeft(now) ?: return if (c.expired) "Expired" else "No expiry date"
    return when {
        c.expired || d < 0 -> if (d == -1L) "Expired yesterday" else "Expired ${-d} days ago"
        d == 0L -> "Expires today"
        d == 1L -> "Expires tomorrow"
        else -> "$d days left"
    }
}

private fun dateText(ms: Long?) = ms?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "—"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CertCard(c: NasCertificate, now: Long, warnDays: Int, isWebUi: Boolean, canUseForWebUi: Boolean, highlighted: Boolean, onAction: (CertAction) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val status = c.status(now, warnDays)
    val (icon, tint) = when (status) {
        CertStatus.EXPIRED -> Icons.Rounded.GppBad to MaterialTheme.colorScheme.error
        CertStatus.EXPIRING -> Icons.Rounded.GppMaybe to app.truenascompanion.ui.theme.LocalStatusColors.current.of(Health.WARNING)
        CertStatus.OK -> Icons.Rounded.VerifiedUser to MaterialTheme.colorScheme.primary
        CertStatus.NOT_APPLICABLE -> Icons.Rounded.Description to MaterialTheme.colorScheme.onSurfaceVariant
    }
    ElevatedSection(onClick = { onAction(CertAction.Details(c)) }, contentPadding = 14.dp,
        modifier = if (highlighted) Modifier.glow(MaterialTheme.colorScheme.primary, radius = 8.dp, shape = MaterialTheme.shapes.large, alpha = 0.35f) else Modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tint = tint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(c.domains.firstOrNull() ?: c.kind.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Actions for ${c.name}") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Details") }, leadingIcon = { Icon(Icons.Rounded.Info, null) }, onClick = { menu = false; onAction(CertAction.Details(c)) })
                    if (canUseForWebUi) DropdownMenuItem(text = { Text("Use for web UI…") }, leadingIcon = { Icon(Icons.Rounded.Language, null) }, onClick = { menu = false; onAction(CertAction.UseForWebUi(c)) })
                    if (c.acme) DropdownMenuItem(text = { Text("Renewal…") }, leadingIcon = { Icon(Icons.Rounded.Autorenew, null) }, onClick = { menu = false; onAction(CertAction.RenewDays(c)) })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (status) {
                CertStatus.EXPIRED -> StatusChip(Health.CRITICAL, expiryLabel(c, now))
                CertStatus.EXPIRING -> StatusChip(Health.WARNING, expiryLabel(c, now))
                CertStatus.OK -> StatusChip(Health.HEALTHY, expiryLabel(c, now))
                CertStatus.NOT_APPLICABLE -> Tag("Signing request")
            }
            if (isWebUi) Tag("Web UI", brand = true)
            if (c.acme) Tag("ACME · auto-renew")
            if (c.selfSigned && c.kind != CertKind.CSR) Tag("Self-signed")
        }
        if (c.kind != CertKind.CSR) {
            Spacer(Modifier.height(8.dp))
            Text(listOfNotNull(c.issuer?.let { "Issued by $it" }, "until ${dateText(c.untilMillis)}").joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (c.domains.size > 1) {
            Text("Covers " + c.domains.take(3).joinToString(", ") + if (c.domains.size > 3) " +${c.domains.size - 3}" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CertDetailSheet(c: NasCertificate, now: Long, warnDays: Int, isWebUi: Boolean, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) { CertDetailContent(c, now, warnDays, isWebUi) }
}

@Composable
fun CertDetailContent(c: NasCertificate, now: Long, warnDays: Int, isWebUi: Boolean) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(c.name, style = MaterialTheme.typography.titleLarge)
        Text(listOfNotNull(c.kind.label, if (isWebUi) "used by the web UI" else null, if (c.kind != CertKind.CSR) expiryLabel(c, now) else null).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (c.status(now, warnDays) == CertStatus.EXPIRED) InfoBanner("This certificate has expired. Clients will refuse it.", health = Health.CRITICAL)
        LabeledValue("Common name", c.common ?: "—")
        LabeledValue("Names (SAN)", c.sans.joinToString("\n").ifBlank { "—" })
        if (c.kind != CertKind.CSR) {
            LabeledValue("Issuer", (c.issuer ?: "—") + if (c.selfSigned) " (self-signed)" else "")
            LabeledValue("Valid from", dateText(c.fromMillis))
            LabeledValue("Valid until", dateText(c.untilMillis))
        }
        LabeledValue("Key", listOfNotNull(c.keyType, c.keyLength?.let { "$it bit" }, c.digest).joinToString(" · ").ifBlank { "—" })
        if (c.acme) {
            LabeledValue("Renewal", "TrueNAS renews it automatically ${c.renewDays ?: 10} days before expiry")
            c.acmeUri?.let { LabeledValue("ACME server", it) }
        }
        c.fingerprint?.let { LabeledValue("Fingerprint", it) }
        c.dn?.let { LabeledValue("Subject", it) }
    }
}

@Composable
private fun RenewDaysDialog(c: NasCertificate, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var days by remember { mutableFloatStateOf((c.renewDays ?: 10).toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Autorenew, null) },
        title = { Text("Renewal for ${c.name}") },
        text = {
            Column {
                Text("TrueNAS renews ACME certificates by itself, once a day, starting this many days before they expire. Renewing on demand isn't available in the API.")
                Spacer(Modifier.height(12.dp))
                Text("${days.toInt()} days before expiry", style = MaterialTheme.typography.titleMedium)
                Slider(value = days, onValueChange = { days = it }, valueRange = 1f..30f, steps = 28)
            }
        },
        confirmButton = { GlowButton(onClick = { onSave(days.toInt()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Reads a small text file (PEM) picked by the user. */
private fun readText(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < 256 * 1024) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        out.toString(Charsets.UTF_8.name())
    }
}.getOrNull()

@Composable
fun ImportCertForm(onCancel: () -> Unit, onImport: (CertImport) -> Unit) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("") }
    var cert by rememberSaveable { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var trusted by rememberSaveable { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val text = uri?.let { readText(context, it) } ?: return@rememberLauncherForActivityResult
        val (c, k) = CertForms.splitPem(text)
        c?.let { cert = it }
        k?.let { key = it }
    }
    val error = CertForms.importError(name, cert, key)
    Text("Import certificate", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(4.dp))
    Text("Paste the PEM certificate (with its chain) and private key, or pick a .pem/.crt/.key file. A file with both fills both fields.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        isError = tried && CertForms.nameError(name) != null, supportingText = { Text(CertForms.nameError(name)?.takeIf { tried } ?: "Letters, digits, - and _") })
    OutlinedTextField(cert, { cert = it }, label = { Text("Certificate (PEM)") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
    Spacer(Modifier.height(8.dp))
    app.truenascompanion.ui.components.SecureWindowEffect()
    OutlinedTextField(key, { key = it }, label = { Text("Private key (PEM)") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (key.isEmpty()) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Rounded.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Pick a file")
    }
    Spacer(Modifier.height(8.dp))
    app.truenascompanion.ui.components.SecureWindowEffect()
    OutlinedTextField(passphrase, { passphrase = it }, label = { Text("Key passphrase (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(trusted, { trusted = it })
        Text("Add to the NAS's trusted store", style = MaterialTheme.typography.bodyMedium)
    }
    if (tried && error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onCancel) { Text("Cancel") }
        Spacer(Modifier.width(8.dp))
        GlowButton(onClick = {
            tried = true
            if (error == null) onImport(CertImport(name.trim(), CertForms.splitPem(cert).first!!, CertForms.splitPem(key).second!!, passphrase.ifEmpty { null }, trusted))
        }) { Text("Import") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AcmeCertForm(
    options: AcmeOptions,
    csrs: List<NasCertificate>,
    csrDomains: Map<Int, List<String>>,
    onCsrPicked: (Int) -> Unit,
    onCancel: () -> Unit,
    onRequest: (AcmeRequest) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var csrId by rememberSaveable { mutableStateOf<Int?>(null) }
    var domainText by rememberSaveable { mutableStateOf("") }
    var directory by rememberSaveable { mutableStateOf(CertForms.defaultDirectory(options.directories)) }
    var tos by rememberSaveable { mutableStateOf(false) }
    var renew by remember { mutableFloatStateOf(10f) }
    val mapping = remember { mutableStateMapOf<String, Int>() }
    var tried by remember { mutableStateOf(false) }
    val domains = if (csrId != null) csrDomains[csrId].orEmpty() else CertForms.domains(domainText)
    // Every domain defaults to the first authenticator.
    LaunchedEffect(domains, options.authenticators) {
        options.authenticators.firstOrNull()?.let { first -> domains.forEach { d -> if (d !in mapping) mapping[d] = first.id } }
    }
    val error = CertForms.acmeError(name, domains, mapping.filterKeys { it in domains }, tos, renew.toInt())
        ?: if (directory == null) "Choose an ACME server" else null

    Text("Request ACME certificate", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(4.dp))
    Text("TrueNAS proves you own the domains through a DNS authenticator you set up in the web UI, then renews the certificate by itself.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    if (options.authenticators.isEmpty()) {
        InfoBanner("No DNS authenticators yet. Add one in the TrueNAS web UI (Credentials › Certificates › ACME DNS-Authenticators) first.")
        Spacer(Modifier.height(12.dp))
    }
    OutlinedTextField(name, { name = it }, label = { Text("Certificate name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        isError = tried && CertForms.nameError(name) != null, supportingText = { Text(CertForms.nameError(name)?.takeIf { tried } ?: "Letters, digits, - and _") })
    Text("Signing request", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = csrId == null, onClick = { csrId = null }, label = { Text("New (RSA 2048)") })
        csrs.forEach { c -> FilterChip(selected = csrId == c.id, onClick = { csrId = c.id; onCsrPicked(c.id) }, label = { Text(c.name) }) }
    }
    if (csrId == null) {
        OutlinedTextField(domainText, { domainText = it }, label = { Text("Domains") }, placeholder = { Text("nas.example.com, *.example.com") },
            modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 3, supportingText = { Text("The first one becomes the common name") })
    }
    if (domains.isNotEmpty() && options.authenticators.isNotEmpty()) {
        Text("DNS authenticator per domain", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        domains.forEach { d -> AuthenticatorRow(d, options.authenticators, mapping[d]) { mapping[d] = it } }
    }
    Text("ACME server", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.directories.forEach { (uri, label) -> FilterChip(selected = directory == uri, onClick = { directory = uri }, label = { Text(label.ifBlank { uri }) }) }
    }
    Text("Renew ${renew.toInt()} days before expiry", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    Slider(value = renew, onValueChange = { renew = it }, valueRange = 1f..30f, steps = 28)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(tos, { tos = it })
        Text("I accept the ACME server's terms of service", style = MaterialTheme.typography.bodyMedium)
    }
    if (tried && error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onCancel) { Text("Cancel") }
        Spacer(Modifier.width(8.dp))
        GlowButton(onClick = {
            tried = true
            if (error == null) onRequest(AcmeRequest(
                name = name.trim(), csrId = csrId,
                newCsrCommon = if (csrId == null) domains.first() else null,
                newCsrSans = if (csrId == null) domains.drop(1) else emptyList(),
                directoryUri = directory!!, dnsMapping = domains.associateWith { mapping.getValue(it) }, renewDays = renew.toInt(),
            ))
        }) { Text("Request") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticatorRow(domain: String, auths: List<AcmeAuthenticator>, selected: Int?, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        OutlinedTextField(
            value = auths.firstOrNull { it.id == selected }?.let { a -> a.name + (a.type?.let { " ($it)" } ?: "") } ?: "Choose…",
            onValueChange = {}, readOnly = true, singleLine = true, label = { Text(domain, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            auths.forEach { a -> DropdownMenuItem(text = { Text(a.name + (a.type?.let { " ($it)" } ?: "")) }, onClick = { open = false; onSelect(a.id) }) }
        }
    }
}
