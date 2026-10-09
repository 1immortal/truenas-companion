package app.truenascompanion.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.truenascompanion.data.api.TrueNasException

/**
 * 1.8.1: the one way screens show "loading", "couldn't load" and "nothing here yet".
 *
 * [StateContent] picks the right state for a [UiState]; [ScreenScaffold] adds the shared top bar, pull-to-refresh and
 * a snackbar host around it. Both use the 1.8.0 components ([SkeletonList], [EmptyState]) so every list looks the same.
 */

/** What an empty list shows: an icon, a short title, one friendly sentence and an optional button. */
data class EmptyContent(
    val icon: ImageVector,
    val title: String,
    val message: String,
    val action: (@Composable () -> Unit)? = null,
)

/** The kind of failure, which decides the error state's title and icon. */
enum class ErrorKind { NETWORK, SIGN_IN, OTHER }

fun errorKindOf(cause: Throwable?): ErrorKind = when (cause) {
    is TrueNasException.LoginRequired, is TrueNasException.TokenRejected, is TrueNasException.PasswordRejected,
    is TrueNasException.AuthFailed, is TrueNasException.OtpLockout -> ErrorKind.SIGN_IN
    is TrueNasException.Unreachable, is TrueNasException.Timeout, is TrueNasException.NotConnected,
    is TrueNasException.Interrupted, is TrueNasException.Tls, is java.io.IOException -> ErrorKind.NETWORK
    null -> ErrorKind.NETWORK
    else -> ErrorKind.OTHER
}

/** Error-state title for [kind]. */
fun errorTitle(kind: ErrorKind): String = when (kind) {
    ErrorKind.NETWORK -> "Can't reach your NAS"
    ErrorKind.SIGN_IN -> "Sign in required"
    ErrorKind.OTHER -> "Couldn't load this"
}

/**
 * Friendly error with a Retry button. The text is a polite live region, so TalkBack reads it when it appears.
 * Scrollable, so pull-to-refresh keeps working above it.
 */
@Composable
fun ScreenError(message: String, cause: Throwable?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val kind = errorKindOf(cause)
    val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    LazyColumn(modifier.fillMaxSize()) {
        item {
            Spacer(Modifier.height(48.dp))
            EmptyState(
                icon = when (kind) { ErrorKind.NETWORK -> Icons.Rounded.CloudOff; ErrorKind.SIGN_IN -> Icons.Rounded.Lock; ErrorKind.OTHER -> Icons.Rounded.ErrorOutline },
                title = errorTitle(kind),
                message = if (kind == ErrorKind.SIGN_IN) "Sign in with your TrueNAS account to see this server." else message,
                modifier = live,
                action = {
                    androidx.compose.material3.FilledTonalButton(onClick = onRetry) { Text(if (kind == ErrorKind.SIGN_IN) "Sign in" else "Try again") }
                },
            )
        }
    }
}

/** Scrollable empty state (pull-to-refresh keeps working). */
@Composable
fun ScreenEmpty(empty: EmptyContent, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(0.dp)) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Spacer(Modifier.height(32.dp))
            EmptyState(empty.icon, empty.title, empty.message, action = empty.action)
        }
    }
}

/**
 * Shows [state]: skeletons while loading, [ScreenError] on failure, [empty] when [isEmpty] says the data is empty,
 * otherwise [content].
 */
@Composable
fun <T> StateContent(
    state: UiState<T>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    skeletonCount: Int = 5,
    skeletonHeight: Dp = 96.dp,
    loading: @Composable () -> Unit = { SkeletonList(skeletonCount, skeletonHeight) },
    empty: EmptyContent? = null,
    isEmpty: (T) -> Boolean = { (it as? Collection<*>)?.isEmpty() == true },
    content: @Composable (T) -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        Crossfade(targetState = state::class, label = "screenState") { kind ->
            when {
                kind == UiState.Loading::class -> loading()
                kind == UiState.Error::class -> (state as? UiState.Error)?.let { ScreenError(it.message, it.cause, onRetry) }
                else -> (state as? UiState.Success<T>)?.let { s ->
                    if (empty != null && isEmpty(s.data)) ScreenEmpty(empty) else content(s.data)
                }
            }
        }
    }
}

/**
 * A complete screen: shared top bar (with Back when [onBack] is set), optional pull-to-refresh ([onRefresh]) and a
 * snackbar host, around [StateContent].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ScreenScaffold(
    title: String,
    state: UiState<T>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    floatingActionButton: @Composable () -> Unit = {},
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    skeletonCount: Int = 5,
    skeletonHeight: Dp = 96.dp,
    empty: EmptyContent? = null,
    isEmpty: (T) -> Boolean = { (it as? Collection<*>)?.isEmpty() == true },
    /** 1.9.0: shown above every state (loading, error, empty, content) and never scrolled away, e.g. a warning. */
    header: (@Composable () -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } } },
                actions = actions,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = floatingActionButton,
    ) { padding ->
        val body: @Composable () -> Unit = {
            StateContent(state, onRetry, skeletonCount = skeletonCount, skeletonHeight = skeletonHeight, empty = empty, isEmpty = isEmpty, content = content)
        }
        androidx.compose.foundation.layout.Column(Modifier.padding(padding).fillMaxSize()) {
            header?.invoke()
            val rest = Modifier.weight(1f).fillMaxSize()
            if (onRefresh != null) {
                PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = rest) { body() }
            } else {
                Box(rest) { body() }
            }
        }
    }
}
