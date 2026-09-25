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
        val NOTIFICATIONS = stringPreferencesKey("notification_prefs")
        val LOCK = stringPreferencesKey("lock_settings")
        fun seenAlerts(id: String) = stringPreferencesKey("seen_alerts_$id")
        fun signInNotified(id: String) = booleanPreferencesKey("signin_notified_$id")
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
            prefs.remove(Keys.seenAlerts(id))
            prefs.remove(Keys.signInNotified(id))
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

    suspend fun saveSessionToken(serverId: String, token: IssuedToken) {
        val enc = cipher.encrypt(token.token)
        store.edit {
            it[Keys.token(serverId)] = enc
            it[Keys.tokenExpiry(serverId)] = token.expiresAt
        }
    }

    suspend fun sessionToken(serverId: String): IssuedToken? {
        val prefs = store.data.first()
        val token = prefs[Keys.token(serverId)]?.let { cipher.decrypt(it) } ?: return null
        return IssuedToken(token, prefs[Keys.tokenExpiry(serverId)] ?: 0L)
    }

    suspend fun clearSessionToken(serverId: String) {
        store.edit {
            it.remove(Keys.token(serverId))
            it.remove(Keys.tokenExpiry(serverId))
        }
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
