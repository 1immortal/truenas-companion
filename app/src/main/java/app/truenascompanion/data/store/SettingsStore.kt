package app.truenascompanion.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.truenascompanion.data.api.IssuedToken
import app.truenascompanion.data.api.SessionTokens
import app.truenascompanion.data.api.TokenStore
import app.truenascompanion.data.model.DashboardLayout
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.security.SecretCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import app.truenascompanion.notify.AlertFilter
import app.truenascompanion.notify.AlertLevel
import app.truenascompanion.notify.QuietHours
import app.truenascompanion.notify.SeenAlert
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppearanceSettings(val themeMode: ThemeMode = ThemeMode.SYSTEM, val dynamicColor: Boolean = false)

/** Phone notifications for TrueNAS alerts. Global options; [enabledServers] turns them on per server. */
@Serializable
data class NotificationPrefs(
    val enabledServers: Set<String> = emptySet(),
    val minLevel: AlertLevel = AlertLevel.WARNING,
    val intervalMinutes: Int = 15,
    val instant: Boolean = false,
    val notifyOnClear: Boolean = false,
    val quietEnabled: Boolean = false,
    val quietStart: Int = 22 * 60,
    val quietEnd: Int = 7 * 60,
    /** The "get alerts on your phone" card on the Alerts screen was answered. */
    val promptDismissed: Boolean = false,
) {
    val filter: AlertFilter get() = AlertFilter(minLevel, notifyOnClear, QuietHours(quietEnabled, quietStart, quietEnd))
    fun isEnabled(serverId: String?) = serverId != null && serverId in enabledServers

    companion object {
        val INTERVALS = listOf(15, 30, 60)
    }
}

/** All persisted app state: servers, encrypted API keys, per-server dashboard layouts, appearance. */
class SettingsStore(context: Context, private val cipher: SecretCipher) {
    private val store = context.applicationContext.dataStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private object Keys {
        val SERVERS = stringPreferencesKey("servers")
        val ACTIVE = stringPreferencesKey("active_server")
        val THEME = stringPreferencesKey("theme_mode")
        // v0.2.1: new key so every existing install is migrated to the brand theme (dynamic color off) once.
        val DYNAMIC = booleanPreferencesKey("dynamic_color_v2")
        fun apiKey(id: String) = stringPreferencesKey("api_key_$id")
        fun layout(id: String) = stringPreferencesKey("dashboard_layout_$id")
        fun password(id: String) = stringPreferencesKey("password_$id")
        fun token(id: String) = stringPreferencesKey("session_token_$id")
        fun tokenExpiry(id: String) = longPreferencesKey("session_token_expiry_$id")
        fun spare(id: String) = stringPreferencesKey("session_spare_$id")
        fun spareExpiry(id: String) = longPreferencesKey("session_spare_expiry_$id")
        val NOTIFICATIONS = stringPreferencesKey("notification_prefs")
        val LOCK = stringPreferencesKey("lock_settings")
        val UPDATE_AUTO = booleanPreferencesKey("update_auto_check")
        val UPDATE_NOTIFIED = stringPreferencesKey("update_notified_version")
        fun seenAlerts(id: String) = stringPreferencesKey("seen_alerts_$id")
        fun signInNotified(id: String) = booleanPreferencesKey("signin_notified_$id")
        fun wireGuard(id: String) = stringPreferencesKey("wireguard_conf_$id")
        fun vpnWorked(id: String) = booleanPreferencesKey("vpn_worked_$id")
        fun vpnTipDismissed(id: String) = booleanPreferencesKey("vpn_tip_dismissed_$id")
    }

    val servers: Flow<List<ServerConfig>> = store.data.map { prefs ->
        prefs[Keys.SERVERS]?.let { runCatching { json.decodeFromString<List<ServerConfig>>(it) }.getOrNull() } ?: emptyList()
    }.distinctUntilChanged()

    val activeServerId: Flow<String?> = store.data.map { it[Keys.ACTIVE] }.distinctUntilChanged()

    val appearance: Flow<AppearanceSettings> = store.data.map { prefs ->
        AppearanceSettings(
            themeMode = prefs[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[Keys.DYNAMIC] ?: false,
        )
    }.distinctUntilChanged()

    val lockSettings: Flow<app.truenascompanion.data.security.LockSettings> = store.data.map { prefs ->
        prefs[Keys.LOCK]?.let { runCatching { json.decodeFromString<app.truenascompanion.data.security.LockSettings>(it) }.getOrNull() }
            ?: app.truenascompanion.data.security.LockSettings()
    }.distinctUntilChanged()

    // --- VPN (0.6) ---

    /** Saves a server's WireGuard config (contains its private key: encrypted like the API key) and flags the server. */
    suspend fun saveWireGuard(serverId: String, conf: String, mode: app.truenascompanion.data.model.VpnMode) {
        val enc = cipher.encrypt(conf)
        store.edit { prefs ->
            prefs[Keys.wireGuard(serverId)] = enc
            val list = decodeServers(prefs).map { if (it.id == serverId) it.copy(wireGuardConfigured = true, vpnMode = mode) else it }
            prefs[Keys.SERVERS] = json.encodeToString(list)
        }
    }

    suspend fun wireGuard(serverId: String): String? =
        store.data.first()[Keys.wireGuard(serverId)]?.let { runCatching { cipher.decrypt(it) }.getOrNull() }

    suspend fun clearWireGuard(serverId: String) {
        store.edit { prefs ->
            prefs.remove(Keys.wireGuard(serverId))
            val list = decodeServers(prefs).map { if (it.id == serverId) it.copy(wireGuardConfigured = false, vpnMode = app.truenascompanion.data.model.VpnMode.OFF) else it }
            prefs[Keys.SERVERS] = json.encodeToString(list)
        }
    }

    /** Updates just the VPN-related fields of a saved server (the rest stays as it is). */
    suspend fun updateServer(serverId: String, transform: (ServerConfig) -> ServerConfig) {
        store.edit { prefs ->
            val list = decodeServers(prefs).map { if (it.id == serverId) transform(it) else it }
            prefs[Keys.SERVERS] = json.encodeToString(list)
        }
    }

    /** Remembers that a VPN route (built-in tunnel or Tailscale) connected once: unlocks the security tip. */
    suspend fun markVpnWorked(serverId: String) {
        if (store.data.first()[Keys.vpnWorked(serverId)] == true) return
        store.edit { it[Keys.vpnWorked(serverId)] = true }
    }

    fun vpnTip(serverId: String): Flow<Boolean> = store.data.map {
        it[Keys.vpnWorked(serverId)] == true && it[Keys.vpnTipDismissed(serverId)] != true
    }.distinctUntilChanged()

    suspend fun dismissVpnTip(serverId: String) { store.edit { it[Keys.vpnTipDismissed(serverId)] = true } }

    /** Daily background update check (default on). */
    val autoUpdateCheck: Flow<Boolean> = store.data.map { it[Keys.UPDATE_AUTO] ?: true }.distinctUntilChanged()
    suspend fun setAutoUpdateCheck(on: Boolean) { store.edit { it[Keys.UPDATE_AUTO] = on } }
    /** The newest version we already posted an "update available" notification for. */
    suspend fun updateNotifiedVersion(): String? = store.data.first()[Keys.UPDATE_NOTIFIED]
    suspend fun setUpdateNotifiedVersion(v: String) { store.edit { it[Keys.UPDATE_NOTIFIED] = v } }

    suspend fun updateLockSettings(f: (app.truenascompanion.data.security.LockSettings) -> app.truenascompanion.data.security.LockSettings) {
        store.edit { prefs ->
            val cur = prefs[Keys.LOCK]?.let { runCatching { json.decodeFromString<app.truenascompanion.data.security.LockSettings>(it) }.getOrNull() }
                ?: app.truenascompanion.data.security.LockSettings()
            prefs[Keys.LOCK] = json.encodeToString(f(cur))
        }
    }

    suspend fun saveServer(server: ServerConfig, apiKey: String?) {
        val encrypted = apiKey?.let { cipher.encrypt(it) }
        store.edit { prefs ->
            val list = decodeServers(prefs).toMutableList()
            val idx = list.indexOfFirst { it.id == server.id }
            if (idx >= 0) list[idx] = server else list.add(server)
            prefs[Keys.SERVERS] = json.encodeToString(list)
            if (encrypted != null) prefs[Keys.apiKey(server.id)] = encrypted
            if (prefs[Keys.ACTIVE] == null) prefs[Keys.ACTIVE] = server.id
        }
    }

    suspend fun deleteServer(id: String) {
        store.edit { prefs ->
            val list = decodeServers(prefs).filterNot { it.id == id }
            prefs[Keys.SERVERS] = json.encodeToString(list)
            prefs.remove(Keys.apiKey(id))
            prefs.remove(Keys.layout(id))
            prefs.remove(Keys.password(id))
            prefs.remove(Keys.token(id))
            prefs.remove(Keys.tokenExpiry(id))
            prefs.remove(Keys.spare(id))
            prefs.remove(Keys.spareExpiry(id))
            prefs.remove(Keys.seenAlerts(id))
            prefs.remove(Keys.signInNotified(id))
            prefs.remove(Keys.wireGuard(id))
            prefs.remove(Keys.vpnWorked(id))
            prefs.remove(Keys.vpnTipDismissed(id))
            val np = decodeNotifications(prefs)
            if (id in np.enabledServers) prefs[Keys.NOTIFICATIONS] = json.encodeToString(np.copy(enabledServers = np.enabledServers - id))
            if (prefs[Keys.ACTIVE] == id) {
                val next = list.firstOrNull()?.id
                if (next != null) prefs[Keys.ACTIVE] = next else prefs.remove(Keys.ACTIVE)
            }
        }
    }

    suspend fun setActiveServer(id: String) {
        store.edit { it[Keys.ACTIVE] = id }
    }

    suspend fun apiKey(serverId: String): String? =
        store.data.first()[Keys.apiKey(serverId)]?.let { cipher.decrypt(it) }

    // --- password sign-in secrets (all encrypted with the Keystore key) ---

    suspend fun savePassword(serverId: String, password: String) {
        val enc = cipher.encrypt(password)
        store.edit { it[Keys.password(serverId)] = enc }
    }

    suspend fun password(serverId: String): String? =
        store.data.first()[Keys.password(serverId)]?.let { cipher.decrypt(it) }

    suspend fun hasPassword(serverId: String): Boolean = store.data.first()[Keys.password(serverId)] != null

    suspend fun clearPassword(serverId: String) {
        store.edit { it.remove(Keys.password(serverId)) }
    }

    /** Saves the primary + spare session tokens in one atomic DataStore edit (null clears that slot). */
    suspend fun saveSessionTokens(serverId: String, tokens: SessionTokens) {
        val primary = tokens.primary?.let { cipher.encrypt(it.token) to it.expiresAt }
        val spare = tokens.spare?.takeIf { it.token != tokens.primary?.token }?.let { cipher.encrypt(it.token) to it.expiresAt }
        store.edit {
            if (primary != null) { it[Keys.token(serverId)] = primary.first; it[Keys.tokenExpiry(serverId)] = primary.second }
            else { it.remove(Keys.token(serverId)); it.remove(Keys.tokenExpiry(serverId)) }
            if (spare != null) { it[Keys.spare(serverId)] = spare.first; it[Keys.spareExpiry(serverId)] = spare.second }
            else { it.remove(Keys.spare(serverId)); it.remove(Keys.spareExpiry(serverId)) }
        }
    }

    suspend fun sessionTokens(serverId: String): SessionTokens {
        val prefs = store.data.first()
        fun read(key: Preferences.Key<String>, expiry: Preferences.Key<Long>) =
            prefs[key]?.let { enc -> runCatching { cipher.decrypt(enc) }.getOrNull() }?.let { IssuedToken(it, prefs[expiry] ?: 0L) }
        return SessionTokens(read(Keys.token(serverId), Keys.tokenExpiry(serverId)), read(Keys.spare(serverId), Keys.spareExpiry(serverId)))
    }

    /** Compare-and-remove: drops a slot only if it still holds one of [tokens] (a newer token saved meanwhile stays). */
    suspend fun removeSessionTokens(serverId: String, tokens: Set<String>) {
        store.edit { prefs ->
            fun matches(key: Preferences.Key<String>) =
                prefs[key]?.let { enc -> runCatching { cipher.decrypt(enc) }.getOrNull() in tokens } == true
            if (matches(Keys.token(serverId))) { prefs.remove(Keys.token(serverId)); prefs.remove(Keys.tokenExpiry(serverId)) }
            if (matches(Keys.spare(serverId))) { prefs.remove(Keys.spare(serverId)); prefs.remove(Keys.spareExpiry(serverId)) }
        }
    }

    suspend fun clearSessionToken(serverId: String) = saveSessionTokens(serverId, SessionTokens())

    /** [TokenStore] view for [SessionTokenManager]. */
    val tokenStore: TokenStore = object : TokenStore {
        override suspend fun load(serverId: String) = sessionTokens(serverId)
        override suspend fun save(serverId: String, tokens: SessionTokens) = saveSessionTokens(serverId, tokens)
        override suspend fun remove(serverId: String, tokens: Set<String>) = removeSessionTokens(serverId, tokens)
    }

    fun dashboardLayout(serverId: String): Flow<DashboardLayout> = store.data.map { prefs ->
        prefs[Keys.layout(serverId)]?.let { runCatching { json.decodeFromString<DashboardLayout>(it) }.getOrNull() }
            ?.normalized() ?: DashboardLayout.DEFAULT
    }.distinctUntilChanged()

    suspend fun saveDashboardLayout(serverId: String, layout: DashboardLayout) {
        store.edit { it[Keys.layout(serverId)] = json.encodeToString(layout) }
    }

    suspend fun resetDashboardLayout(serverId: String) {
        store.edit { it.remove(Keys.layout(serverId)) }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[Keys.THEME] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        store.edit { it[Keys.DYNAMIC] = enabled }
    }

    // --- phone notifications ---

    val notificationPrefs: Flow<NotificationPrefs> = store.data.map { decodeNotifications(it) }.distinctUntilChanged()

    suspend fun updateNotificationPrefs(transform: (NotificationPrefs) -> NotificationPrefs) {
        store.edit { prefs ->
            val current = decodeNotifications(prefs)
            val next = transform(current)
            if (next != current) prefs[Keys.NOTIFICATIONS] = json.encodeToString(next)
        }
    }

    /** null = never checked (the next check sets a silent baseline). */
    suspend fun seenAlerts(serverId: String): List<SeenAlert>? =
        store.data.first()[Keys.seenAlerts(serverId)]?.let { runCatching { json.decodeFromString<List<SeenAlert>>(it) }.getOrNull() }

    suspend fun saveSeenAlerts(serverId: String, seen: List<SeenAlert>) {
        store.edit { it[Keys.seenAlerts(serverId)] = json.encodeToString(seen) }
    }

    suspend fun clearSeenAlerts(serverId: String) {
        store.edit { it.remove(Keys.seenAlerts(serverId)) }
    }

    /** Returns true if the flag changed (used so "sign in to keep receiving alerts" is posted only once). */
    suspend fun setSignInNotified(serverId: String, notified: Boolean): Boolean {
        var changed = false
        store.edit {
            val old = it[Keys.signInNotified(serverId)] ?: false
            if (old != notified) {
                changed = true
                if (notified) it[Keys.signInNotified(serverId)] = true else it.remove(Keys.signInNotified(serverId))
            }
        }
        return changed
    }

    private fun decodeNotifications(prefs: Preferences): NotificationPrefs =
        prefs[Keys.NOTIFICATIONS]?.let { runCatching { json.decodeFromString<NotificationPrefs>(it) }.getOrNull() } ?: NotificationPrefs()

    private fun decodeServers(prefs: Preferences): List<ServerConfig> =
        prefs[Keys.SERVERS]?.let { runCatching { json.decodeFromString<List<ServerConfig>>(it) }.getOrNull() } ?: emptyList()
}
