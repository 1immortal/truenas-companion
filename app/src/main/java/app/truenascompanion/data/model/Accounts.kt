package app.truenascompanion.data.model

/** A `user.query` entry (TrueNAS 25.10 `UserEntry`), reduced to what the app shows and edits. */
data class NasUser(
    val id: Int,
    val uid: Int,
    val username: String,
    val fullName: String,
    val email: String?,
    val home: String,
    val shell: String,
    /** Primary group: API id (not gid) and name. */
    val groupId: Int?,
    val groupName: String?,
    /** Additional groups (API ids). */
    val groups: List<Int>,
    val smb: Boolean,
    val passwordDisabled: Boolean,
    val sshPasswordEnabled: Boolean,
    val sshPubKey: String?,
    val locked: Boolean,
    val builtin: Boolean,
    val immutable: Boolean,
    val local: Boolean,
    val twoFactor: Boolean,
    val roles: List<String>,
    val sudoCommands: List<String> = emptyList(),
    val sudoCommandsNoPasswd: List<String> = emptyList(),
) {
    /** System or directory accounts: shown read-only (hidden by default). */
    val readOnly: Boolean get() = builtin || immutable || !local
    val isAdmin: Boolean get() = "FULL_ADMIN" in roles
    val sshKeyCount: Int get() = sshPubKey?.lineSequence()?.count { it.isNotBlank() && !it.trimStart().startsWith("#") } ?: 0
}

/** A `group.query` entry. */
data class NasGroup(
    val id: Int,
    val gid: Int,
    val name: String,
    val builtin: Boolean,
    val immutable: Boolean,
    val local: Boolean,
    val smb: Boolean,
    /** Member user API ids. */
    val users: List<Int>,
    val roles: List<String>,
    val sudoCommands: List<String> = emptyList(),
    val sudoCommandsNoPasswd: List<String> = emptyList(),
) {
    val readOnly: Boolean get() = builtin || immutable || !local
}

/** Values of the user editor. Null fields are left out of `user.update`. */
data class UserInput(
    val username: String,
    val fullName: String,
    val email: String? = null,
    /** Required on create unless [passwordDisabled]; on edit only sent when changing it. */
    val password: String? = null,
    val uid: Int? = null,
    /** Create a new primary group named like the user (create only). */
    val groupCreate: Boolean = true,
    val primaryGroup: Int? = null,
    val groups: List<Int> = emptyList(),
    val home: String = DEFAULT_HOME,
    val homeCreate: Boolean = false,
    val shell: String = "/usr/bin/zsh",
    val sshPubKey: String? = null,
    val smb: Boolean = true,
    val passwordDisabled: Boolean = false,
    val sshPasswordEnabled: Boolean = false,
    val locked: Boolean = false,
) {
    companion object { const val DEFAULT_HOME = "/var/empty" }
}

data class GroupInput(
    val name: String,
    val gid: Int? = null,
    val smb: Boolean = true,
    val users: List<Int> = emptyList(),
)
