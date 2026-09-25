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
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppearanceSettings(val themeMode: ThemeMode = ThemeMode.SYSTEM, val dynamicColor: Boolean = true)

/** All persisted app state: servers, encrypted API keys, per-server dashboard layouts, appearance. */
class SettingsStore(context: Context, private val cipher: SecretCipher) {
    private val store = context.applicationContext.dataStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private object Keys {
        val SERVERS = stringPreferencesKey("servers")
        val ACTIVE = stringPreferencesKey("active_server")
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        fun apiKey(id: String) = stringPreferencesKey("api_key_$id")
        fun layout(id: String) = stringPreferencesKey("dashboard_layout_$id")
        fun password(id: String) = stringPreferencesKey("password_$id")
        fun token(id: String) = stringPreferencesKey("session_token_$id")
        fun tokenExpiry(id: String) = longPreferencesKey("session_token_expiry_$id")
    }

    val servers: Flow<List<ServerConfig>> = store.data.map { prefs ->
        prefs[Keys.SERVERS]?.let { runCatching { json.decodeFromString<List<ServerConfig>>(it) }.getOrNull() } ?: emptyList()
    }.distinctUntilChanged()

    val activeServerId: Flow<String?> = store.data.map { it[Keys.ACTIVE] }.distinctUntilChanged()

    val appearance: Flow<AppearanceSettings> = store.data.map { prefs ->
        AppearanceSettings(
            themeMode = prefs[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[Keys.DYNAMIC] ?: true,
        )
    }.distinctUntilChanged()

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

    private fun decodeServers(prefs: Preferences): List<ServerConfig> =
        prefs[Keys.SERVERS]?.let { runCatching { json.decodeFromString<List<ServerConfig>>(it) }.getOrNull() } ?: emptyList()
}
