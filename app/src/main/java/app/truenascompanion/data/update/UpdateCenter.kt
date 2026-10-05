package app.truenascompanion.data.update

import app.truenascompanion.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide update state shared by the daily worker and System › About. */
class UpdateCenter(private val checker: UpdateChecker = UpdateChecker(BuildConfig.UPDATE_REPO)) {
    private val _latest = MutableStateFlow<UpdateResult?>(null)
    /** Last check result (null = not checked in this process yet). */
    val latest: StateFlow<UpdateResult?> = _latest.asStateFlow()

    val repo: String get() = BuildConfig.UPDATE_REPO

    fun publish(result: UpdateResult) { _latest.value = result }

    suspend fun check(currentVersion: String, channel: UpdateChannel = UpdateChannel.defaultForBuild()): UpdateResult =
        checker.check(currentVersion, channel).also { _latest.value = it }
}
