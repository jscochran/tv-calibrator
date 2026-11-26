package tvcal.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tvcal.android.ui.theme.Theme

// The pinned main action (Swift PinnedAction / PinnedActionKey / pinnedAction / pinnedActionHost): a child
// declares the screen's main (yellow) action; the hosting screen draws it pinned at the bottom.
//
//   PinnedActionHost { ...content that somewhere calls PinnedAction("Next") { next() }... }
//
// A tap always runs the newest closure of the view that declared it (rememberUpdatedState), like the
// Swift PinnedActionBox. A null title declares none (while measuring, say). When several are declared at
// once, the last one declared wins (Swift's reduce keeps the last non-nil value).

/** What the host draws: the title and whether it's enabled. */
data class PinnedActionSpec(val title: String, val enabled: Boolean)

/** The host's slot. One per [PinnedActionHost]; children reach it through [LocalPinnedAction]. */
@Stable
class PinnedActionHolder {
    private class Entry(val owner: Any, val title: String, val enabled: Boolean, val action: State<() -> Unit>)

    private val entries = mutableStateListOf<Entry>()

    /** The action to pin now, or null for none. */
    val current: PinnedActionSpec?
        get() = entries.lastOrNull()?.let { PinnedActionSpec(it.title, it.enabled) }

    internal fun declare(owner: Any, title: String?, enabled: Boolean, action: State<() -> Unit>) {
        val i = entries.indexOfFirst { it.owner === owner }
        if (title == null) {
            if (i >= 0) entries.removeAt(i)
            return
        }
        if (i >= 0) {
            val e = entries[i]
            if (e.title == title && e.enabled == enabled && e.action === action) return
            entries[i] = Entry(owner, title, enabled, action)
        } else {
            entries.add(Entry(owner, title, enabled, action))
        }
    }

    internal fun remove(owner: Any) {
        entries.removeAll { it.owner === owner }
    }

    /** Runs the pinned action's newest closure. */
    fun tap() {
        val e = entries.lastOrNull() ?: return
        if (e.enabled) e.action.value()
    }
}

/** The nearest host's slot (null outside a host: declarations are then ignored). */
val LocalPinnedAction = staticCompositionLocalOf<PinnedActionHolder?> { null }

/**
 * Swift `.pinnedAction(title, enabled:) { action }`: declares this screen's main action from inside a
 * composable; the nearest [PinnedActionHost] pins it. Call it unconditionally in the declaring composable
 * (pass title = null for none) so it stays declared while that composable is on screen.
 */
@Composable
fun PinnedAction(title: String?, enabled: Boolean = true, action: () -> Unit) {
    val holder = LocalPinnedAction.current ?: return
    val owner = remember { Any() }
    val latest = rememberUpdatedState(action)
    SideEffect { holder.declare(owner, title, enabled, latest) }
    DisposableEffect(holder, owner) {
        onDispose { holder.remove(owner) }
    }
}

/**
 * Swift `.pinnedActionHost()`: [content] fills the space (it scrolls itself), the declared action is pinned
 * under it as a [PrimaryButton] on a [BottomAction] bar, and [below] (e.g. GuidedScreen's Cancel bar) goes
 * under that.
 */
@Composable
fun PinnedActionHost(
    modifier: Modifier = Modifier,
    below: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val holder = remember { PinnedActionHolder() }
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CompositionLocalProvider(LocalPinnedAction provides holder) { content() }
        }
        val c = holder.current
        if (c != null) {
            BottomAction {
                PrimaryButton(c.title, onClick = { holder.tap() }, enabled = c.enabled)
            }
        }
        below?.invoke()
    }
}

/**
 * Swift `.bottomAction { }`: the screen's main action(s), pinned under the scrolling content
 * (20 dp sides, 12 top, 8 bottom, on the background at 96%). Stack several with 10 dp between.
 */
@Composable
fun BottomAction(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Theme.background.copy(alpha = 0.96f))
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}
