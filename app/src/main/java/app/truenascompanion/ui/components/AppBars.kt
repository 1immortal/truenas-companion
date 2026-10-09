package app.truenascompanion.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/**
 * 1.8.0 (UI review: consistent top bars). Screens import these instead of the Material versions: every [Scaffold]
 * owns a pinned scroll behaviour and every [TopAppBar] inside it uses it, so all bars sit on the page background
 * and take the same tint once content scrolls under them. The title is a TalkBack heading.
 */
@OptIn(ExperimentalMaterial3Api::class)
internal val LocalAppBarScroll = staticCompositionLocalOf<TopAppBarScrollBehavior?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Scaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = contentColorFor(containerColor),
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    CompositionLocalProvider(LocalAppBarScroll provides scroll) {
        androidx.compose.material3.Scaffold(
            modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
            topBar = topBar, bottomBar = bottomBar, snackbarHost = snackbarHost,
            floatingActionButton = floatingActionButton, floatingActionButtonPosition = floatingActionButtonPosition,
            containerColor = containerColor, contentColor = contentColor, contentWindowInsets = contentWindowInsets,
        ) { padding -> CompositionLocalProvider(LocalAppBarScroll provides null) { content(padding) } }
    }
}

/** Shared top-bar colours: page background at rest, a container tint when content scrolls underneath. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun appBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.background,
    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    colors: TopAppBarColors = appBarColors(),
    scrollBehavior: TopAppBarScrollBehavior? = LocalAppBarScroll.current,
) {
    androidx.compose.material3.TopAppBar(
        title = { androidx.compose.foundation.layout.Box(Modifier.semantics { heading() }) { title() } },
        modifier = modifier, navigationIcon = navigationIcon, actions = actions,
        windowInsets = windowInsets, colors = colors, scrollBehavior = scrollBehavior,
    )
}
