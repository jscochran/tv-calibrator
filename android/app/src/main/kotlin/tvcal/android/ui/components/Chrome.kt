package tvcal.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import tvcal.android.ui.theme.Fonts
import tvcal.android.ui.theme.Symbols
import tvcal.android.ui.theme.Theme

// Screen chrome: the inline navigation bar, a screen scaffold, the scrolling content column, and the two
// sheet kinds (SwiftUI .sheet → full-screen Dialog for tall flows, ModalBottomSheet for short ones).

/** What the navigation bar's leading button is. */
enum class NavLeading { BACK, CLOSE, NONE }

/**
 * Swift `.navigationTitle(t)` + `.navigationBarTitleDisplayMode(.inline)`: 44 dp, the title centred
 * (17 semibold, white), a teal back arrow or close X on the left, [actions] on the right.
 */
@Composable
fun NavBar(
    title: String,
    modifier: Modifier = Modifier,
    leading: NavLeading = NavLeading.BACK,
    onLeading: () -> Unit = {},
    leadingLabel: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(modifier.fillMaxWidth().height(44.dp)) {
        if (leading != NavLeading.NONE) {
            IconButton(onClick = onLeading, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(
                    if (leading == NavLeading.BACK) Symbols.back else Symbols.xmark,
                    // Swift labels most sheets' X "Close"; TVSetupFlow's is "Cancel" (pass leadingLabel).
                    contentDescription = leadingLabel ?: if (leading == NavLeading.BACK) "Back" else "Close",
                    tint = Theme.tealBright,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Text(
            title,
            style = Fonts.headlineBrand,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
        )
        Row(
            Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

/**
 * A whole screen: the background colour edge to edge, the safe-drawing insets (status bar, navigation
 * bar, keyboard), the [NavBar] when [title] isn't null, [content] filling the rest (it scrolls itself:
 * see [ScreenColumn]), and [bottomBar] (a [BottomAction]) pinned at the bottom.
 */
@Composable
fun ScreenScaffold(
    title: String?,
    modifier: Modifier = Modifier,
    leading: NavLeading = NavLeading.BACK,
    onLeading: () -> Unit = {},
    leadingLabel: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(Theme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        if (title != null) {
            NavBar(title, leading = leading, onLeading = onLeading, leadingLabel = leadingLabel, actions = actions)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        bottomBar?.invoke()
    }
}

/**
 * The usual scrolling screen body (Swift ScrollView { VStack(alignment: .leading, spacing) }
 * .padding(.horizontal, 20).padding(.vertical, 12)).
 */
@Composable
fun ScreenColumn(
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/**
 * A full-screen sheet (Swift .sheet with its own NavigationStack: TVSetupFlow, Manage Screens, Welcome,
 * Camera setup): a Dialog over everything, edge to edge. Put a [ScreenScaffold] with
 * `leading = NavLeading.CLOSE, onLeading = onDismiss` inside. Back dismisses it.
 */
@Composable
fun FullScreenSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Theme.background)) { content() }
    }
}

/**
 * A bottom sheet (Swift .sheet for short content: Jump to a step, Advanced formats): fully expanded, on
 * the background colour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalBottomSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Theme.background,
        contentColor = Color.White,
        content = content,
    )
}
