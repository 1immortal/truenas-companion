package app.truenascompanion.data.api

import app.truenascompanion.data.model.GroupInput
import app.truenascompanion.data.model.NasGroup
import app.truenascompanion.data.model.NasUser
import app.truenascompanion.data.model.UserInput
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Credentials › Users and Groups (1.1.0). Method names and payloads follow TrueNAS 25.10 middleware
 * (`api/v25_10_2/user.py`, `group.py`) and the web UI (`user.store.ts`, `group-form.component.ts`):
 * `user.query/create/update/delete`, `user.shell_choices`, `user.get_next_uid`, `group.query/create/update/delete`.
 * Password resets use `user.update(id, {password})` like the web UI's user form.
 */
class AccountsApi(private val api: TrueNasApi) {

    /** Local accounts (directory-service accounts can't be changed through TrueNAS and aren't listed). */
    suspend fun users(): List<NasUser> =
        api.rpc("user.query", queryFilter(Triple("local", "=", JsonPrimitive(true)))).arr()
            ?.mapNotNull { it.obj()?.let(::parseUser) }?.sortedWith(compareBy({ it.builtin }, { it.username.lowercase() })) ?: emptyList()

    suspend fun groups(): List<NasGroup> =
        api.rpc("group.query", queryFilter(Triple("local", "=", JsonPrimitive(true)))).arr()
            ?.mapNotNull { it.obj()?.let(::parseGroup) }?.sortedWith(compareBy({ it.builtin }, { it.name.lowercase() })) ?: emptyList()

    /** `user.shell_choices(group_ids)` → path → label. */
    suspend fun shellChoices(groupIds: List<Int> = emptyList()): Map<String, String> =
        api.rpc("user.shell_choices", JsonArray(groupIds.map { JsonPrimitive(it) })).obj()
            ?.mapValues { (_, v) -> v.prim()?.contentOrNull ?: "" } ?: emptyMap()

    suspend fun nextUid(): Int? = api.rpc("user.get_next_uid").prim()?.intOrNull

    suspend fun createUser(input: UserInput): NasUser? =
        api.rpc("user.create", createJson(input)).obj()?.let(::parseUser)

    /**
     * `user.update(id, {...})`. Only changed fields of [input] compared with [old] are sent, so read-only or
     * untouched settings are never rewritten. Like the web UI, a new home directory is created in a separate first call.
     */
    suspend fun updateUser(old: NasUser, input: UserInput): NasUser? {
        val patch = updateJson(old, input)
        if (input.homeCreate && input.home != old.home) {
            api.rpc("user.update", JsonPrimitive(old.id), buildJsonObject { put("home_create", true); put("home", input.home) })
        }
        if (patch.isEmpty()) return null
        return api.rpc("user.update", JsonPrimitive(old.id), JsonObject(patch)).obj()?.let(::parseUser)
    }

    suspend fun setLocked(id: Int, locked: Boolean) {
        api.rpc("user.update", JsonPrimitive(id), buildJsonObject { put("locked", locked) })
    }

    suspend fun resetPassword(id: Int, newPassword: String) {
        require(newPassword.isNotEmpty()) { "Enter a new password" }
        api.rpc("user.update", JsonPrimitive(id), buildJsonObject { put("password", newPassword) })
    }

    suspend fun setSshKeys(id: Int, keys: String?) {
        api.rpc("user.update", JsonPrimitive(id), buildJsonObject { put("sshpubkey", keys?.trim()?.ifBlank { null }?.let(::JsonPrimitive) ?: JsonNull) })
    }

    suspend fun deleteUser(id: Int, deleteGroup: Boolean) {
        api.rpc("user.delete", JsonPrimitive(id), buildJsonObject { put("delete_group", deleteGroup) })
    }

    /** `group.create` returns the new group's id. */
    suspend fun createGroup(input: GroupInput): Int? =
        api.rpc("group.create", buildJsonObject {
            put("name", input.name.trim())
            input.gid?.let { put("gid", it) }
            put("smb", input.smb)
            putJsonArray("users") { input.users.forEach { add(JsonPrimitive(it)) } }
        }).prim()?.intOrNull

    suspend fun updateGroup(id: Int, input: GroupInput) {
        api.rpc("group.update", JsonPrimitive(id), buildJsonObject {
            put("name", input.name.trim())
            put("smb", input.smb)
            putJsonArray("users") { input.users.forEach { add(JsonPrimitive(it)) } }
        })
    }

    suspend fun deleteGroup(id: Int, deleteUsers: Boolean) {
        api.rpc("group.delete", JsonPrimitive(id), buildJsonObject { put("delete_users", deleteUsers) })
    }

    companion object {
        private fun JsonElement?.ints(): List<Int> = arr()?.mapNotNull { it.prim()?.intOrNull } ?: emptyList()
        private fun JsonElement?.strings(): List<String> = arr()?.mapNotNull { it.prim()?.contentOrNull } ?: emptyList()

        fun parseUser(o: JsonObject): NasUser? {
            val id = o.long("id")?.toInt() ?: return null
            val group = o["group"].obj()
            return NasUser(
                id = id,
                uid = o.long("uid")?.toInt() ?: -1,
                username = o.str("username") ?: return null,
                fullName = o.str("full_name").orEmpty(),
                email = o.str("email"),
                home = o.str("home") ?: UserInput.DEFAULT_HOME,
                shell = o.str("shell") ?: "/usr/sbin/nologin",
                groupId = group?.long("id")?.toInt(),
                groupName = group?.str("bsdgrp_group") ?: group?.str("group") ?: group?.str("name"),
                groups = o["groups"].ints(),
                smb = o.bool("smb") ?: false,
                passwordDisabled = o.bool("password_disabled") ?: false,
                sshPasswordEnabled = o.bool("ssh_password_enabled") ?: false,
                sshPubKey = o.str("sshpubkey"),
                locked = o.bool("locked") ?: false,
                builtin = o.bool("builtin") ?: false,
                immutable = o.bool("immutable") ?: false,
                local = o.bool("local") ?: true,
                twoFactor = o.bool("twofactor_auth_configured") ?: false,
                roles = o["roles"].strings(),
                sudoCommands = o["sudo_commands"].strings(),
                sudoCommandsNoPasswd = o["sudo_commands_nopasswd"].strings(),
            )
        }

        fun parseGroup(o: JsonObject): NasGroup? {
            val id = o.long("id")?.toInt() ?: return null
            return NasGroup(
                id = id,
                gid = o.long("gid")?.toInt() ?: -1,
                name = o.str("name") ?: o.str("group") ?: return null,
                builtin = o.bool("builtin") ?: false,
                immutable = o.bool("immutable") ?: false,
                local = o.bool("local") ?: true,
                smb = o.bool("smb") ?: false,
                users = o["users"].ints(),
                roles = o["roles"].strings(),
                sudoCommands = o["sudo_commands"].strings(),
                sudoCommandsNoPasswd = o["sudo_commands_nopasswd"].strings(),
            )
        }

        internal fun createJson(input: UserInput) = buildJsonObject {
            put("username", input.username.trim())
            put("full_name", input.fullName.trim().ifBlank { input.username.trim() })
            input.email?.trim()?.takeIf { it.isNotEmpty() }?.let { put("email", it) }
            input.uid?.let { put("uid", it) }
            put("group_create", input.groupCreate)
            if (!input.groupCreate) put("group", input.primaryGroup ?: error("Pick a primary group"))
            putJsonArray("groups") { input.groups.forEach { add(JsonPrimitive(it)) } }
            put("home", input.home.trim().ifBlank { UserInput.DEFAULT_HOME })
            put("home_create", input.homeCreate)
            put("shell", input.shell)
            input.sshPubKey?.trim()?.takeIf { it.isNotEmpty() }?.let { put("sshpubkey", it) }
            put("smb", input.smb)
            put("password_disabled", input.passwordDisabled)
            put("ssh_password_enabled", input.sshPasswordEnabled)
            put("locked", input.locked)
            if (input.passwordDisabled) put("password", JsonNull)
            else put("password", input.password?.takeIf { it.isNotEmpty() } ?: error("Enter a password"))
        }

        /** Changed fields only (see [updateUser]). */
        internal fun updateJson(old: NasUser, input: UserInput): Map<String, JsonElement> {
            val m = LinkedHashMap<String, JsonElement>()
            fun s(key: String, new: String?, cur: String?) { if ((new ?: "") != (cur ?: "")) m[key] = new?.let(::JsonPrimitive) ?: JsonNull }
            fun b(key: String, new: Boolean, cur: Boolean) { if (new != cur) m[key] = JsonPrimitive(new) }
            if (input.username.trim() != old.username) m["username"] = JsonPrimitive(input.username.trim())
            if (input.fullName.trim() != old.fullName) m["full_name"] = JsonPrimitive(input.fullName.trim().ifBlank { old.username })
            s("email", input.email?.trim()?.ifBlank { null }, old.email)
            if (input.primaryGroup != null && input.primaryGroup != old.groupId) m["group"] = JsonPrimitive(input.primaryGroup)
            if (input.groups.sorted() != old.groups.sorted()) m["groups"] = JsonArray(input.groups.map { JsonPrimitive(it) })
            if (!input.homeCreate && input.home.trim() != old.home) m["home"] = JsonPrimitive(input.home.trim())
            s("shell", input.shell, old.shell)
            s("sshpubkey", input.sshPubKey?.trim()?.ifBlank { null }, old.sshPubKey?.trim()?.ifBlank { null })
            b("smb", input.smb, old.smb)
            b("password_disabled", input.passwordDisabled, old.passwordDisabled)
            b("ssh_password_enabled", input.sshPasswordEnabled, old.sshPasswordEnabled)
            b("locked", input.locked, old.locked)
            input.password?.takeIf { it.isNotEmpty() }?.let { m["password"] = JsonPrimitive(it) }
            return m
        }
    }
}
