package app.truenascompanion.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.AccountsApi
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.GroupInput
import app.truenascompanion.data.model.NasGroup
import app.truenascompanion.data.model.NasUser
import app.truenascompanion.data.model.UserInput
import app.truenascompanion.ui.components.UiState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class AccountsData(
    val users: List<NasUser>,
    val groups: List<NasGroup>,
    val shells: Map<String, String>,
)

class AccountsViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<UiState<AccountsData>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch { c.repository.reloadKey.collect { if (it != null) load() } }
    }

    fun refresh() = viewModelScope.launch { _refreshing.value = true; load(); _refreshing.value = false }

    private suspend fun load() {
        try {
            _state.value = UiState.Success(c.repository.call { api ->
                val a = AccountsApi(api)
                AccountsData(a.users(), a.groups(), runCatching { a.shellChoices() }.getOrDefault(emptyMap()))
            })
        } catch (e: Throwable) {
            if (_state.value !is UiState.Success) _state.value = UiState.Error(e.userMessage(), e) else _messages.trySend(e.userMessage())
        }
    }

    private fun run(done: String, block: suspend (AccountsApi) -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                c.repository.call { block(AccountsApi(it)) }
                _messages.trySend(done)
            } catch (e: Throwable) {
                _messages.trySend(e.userMessage())
            } finally {
                _busy.value = false
                load()
            }
        }
    }

    fun createUser(input: UserInput) = run("User ${input.username} created") { it.createUser(input) }
    fun updateUser(old: NasUser, input: UserInput) = run("Saved ${input.username}") { it.updateUser(old, input) }
    fun setLocked(u: NasUser, locked: Boolean) = run(if (locked) "${u.username} is locked" else "${u.username} is unlocked") { it.setLocked(u.id, locked) }
    fun resetPassword(u: NasUser, pw: String) = run("Password changed for ${u.username}") { it.resetPassword(u.id, pw) }
    fun deleteUser(u: NasUser, deleteGroup: Boolean) = run("Deleted ${u.username}") { it.deleteUser(u.id, deleteGroup) }
    fun createGroup(input: GroupInput) = run("Group ${input.name} created") { it.createGroup(input) }
    fun updateGroup(g: NasGroup, input: GroupInput) = run("Saved ${input.name}") { it.updateGroup(g.id, input) }
    fun deleteGroup(g: NasGroup, deleteUsers: Boolean) = run("Deleted ${g.name}") { it.deleteGroup(g.id, deleteUsers) }
}
