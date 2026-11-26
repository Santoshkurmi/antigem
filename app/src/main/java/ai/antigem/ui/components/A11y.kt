package ai.antigem.ui.components

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import ai.antigem.i18n.L

/*
 * TalkBack / keyboard helpers. They only add semantics and focus rules: nothing is drawn differently and touch works
 * as before. Compose builds the semantics tree only while an accessibility service asks for it, so without TalkBack
 * these cost nothing.
 */

/**
 * For a screen kept alive while another is shown (drawn with alpha 0 and moved off-screen): while [visible] is false,
 * TalkBack can't reach anything inside it and keyboard Tab / arrow keys don't walk into it. A requestFocus from code
 * still goes through (the screens hand the keyboard over to each other that way).
 *
 * Put it LAST in the modifier chain: only its tail changes when [visible] does, so the nodes before it stay as they are.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.hiddenWhileOffscreen(visible: Boolean): Modifier =
    this
        .focusProperties {
            onEnter = {
                if (!visible && requestedFocusDirection != FocusDirection.Enter) cancelFocusChange()
            }
        }
        .focusGroup()
        .then(if (visible) Modifier else Modifier.clearAndSetSemantics { })

/**
 * On a settings-style row (label + description + a Switch or Checkbox; pass Role.Checkbox for the latter): TalkBack reads the whole row as
 * ONE item ("Show message stats, token counts…, On, Switch") and double-tap toggles it. Pair it with
 * [mergedIntoRow] on the control itself so it isn't a second stop. Touch is untouched: the row doesn't become tappable.
 *
 * Don't use it on a row that already has `.clickable` / `.toggleable` (that already is one item with its own action).
 */
fun Modifier.toggleRowSemantics(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    role: Role = Role.Switch,
    enabled: Boolean = true
): Modifier = semantics(mergeDescendants = true) {
    this.role = role
    toggleableState = ToggleableState(checked)
    if (!enabled || onCheckedChange == null) disabled()
    if (onCheckedChange != null) onClick { if (enabled) onCheckedChange(!checked); enabled }
}

/** On the Switch / Checkbox inside a row that has [toggleRowSemantics]: hidden from TalkBack, still works by touch. */
fun Modifier.mergedIntoRow(): Modifier = clearAndSetSemantics { }

/**
 * On a terminal extra key (they react to raw touch, so TalkBack had nothing to double-tap): reads its full name
 * ("Page up", not "PGUP"), double-tap presses it, and a sticky key (CTRL / ALT: the ones with [onLongClick]) also says
 * Off / Active / Locked and offers "Lock or unlock" in TalkBack's actions menu. TalkBack speaks the new state by itself
 * when it changes.
 */
fun Modifier.terminalKeySemantics(
    label: String,
    isActive: Boolean,
    isLocked: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
): Modifier = clearAndSetSemantics {
    contentDescription = terminalKeySpokenName(label)
    role = Role.Button
    if (onLongClick != null) {
        stateDescription = when {
            isLocked -> L.components.a11y.keyLocked
            isActive -> L.components.a11y.keyActive
            else -> L.components.a11y.keyOff
        }
        onLongClick(label = L.components.a11y.lockOrUnlock) { onLongClick(); true }
    }
    onClick { onClick(); true }
}

/** What TalkBack says for a terminal key's label: symbols and abbreviations become words. */
private fun terminalKeySpokenName(label: String): String = when (label) {
    "ESC" -> L.components.a11y.keyEscape
    "/" -> L.components.a11y.keySlash
    "-" -> L.components.a11y.keyDash
    "HOME" -> L.components.a11y.keyHome
    "END" -> L.components.a11y.keyEnd
    "↑" -> L.components.a11y.keyUp
    "↓" -> L.components.a11y.keyDown
    "←" -> L.components.a11y.keyLeft
    "→" -> L.components.a11y.keyRight
    "↹", "TAB" -> L.components.a11y.keyTab
    "PGUP", L.components.localTerminal.pgup -> L.components.a11y.keyPageUp
    "PGDN", L.components.localTerminal.pgdn -> L.components.a11y.keyPageDown
    "CTRL", L.components.localTerminal.ctrl, L.ide.executionRunner.ctrl -> L.components.a11y.keyControl
    "ALT", L.components.localTerminal.alt, L.ide.executionRunner.alt -> L.components.a11y.keyAlt
    else -> label
}

/**
 * True while TalkBack (touch exploration) is on; updates when it's switched on or off while the screen is showing.
 * For things that should only change under TalkBack (bigger touch targets, extra semantics nodes).
 */
@Composable
fun rememberTalkBackOn(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) {
        context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }
    var on by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled -> on = enabled }
        manager?.addTouchExplorationStateChangeListener(listener)
        on = manager?.isTouchExplorationEnabled == true
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}

/**
 * At least 48 × 48dp while [talkBackOn]; nothing changes otherwise. Put it BEFORE size() / height() in the chain
 * (size() then gives way to it). Icons keep their size; a button's press highlight or a key's background grows with
 * it. Pass the result of one [rememberTalkBackOn] per screen, not one per button.
 */
fun Modifier.a11yMinTouchTarget(talkBackOn: Boolean): Modifier =
    if (talkBackOn) this.sizeIn(minWidth = 48.dp, minHeight = 48.dp) else this

/**
 * A bottom sheet's state that opens at full height while TalkBack is on (half-open sheets need a two-finger drag under
 * TalkBack, and part of the list starts off-screen) and at half height as before otherwise. Read once when the sheet
 * opens: turning TalkBack on or off while a sheet is open doesn't rebuild it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberSheetStateFullUnderTalkBack(): SheetState {
    val context = LocalContext.current
    val talkBackOn = remember {
        (context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)
            ?.isTouchExplorationEnabled == true
    }
    return rememberModalBottomSheetState(skipPartiallyExpanded = talkBackOn)
}

/**
 * A native View shown through AndroidView (the terminal, the code editor): Compose's semantics don't reach inside it, so
 * while [hidden] (a drawer or panel drawn over it, or its screen hidden) it is taken out of TalkBack's reach here,
 * with everything inside it. Nothing is drawn differently.
 */
@Composable
fun HideFromTalkBackWhile(view: android.view.View?, hidden: Boolean) {
    DisposableEffect(view, hidden) {
        view?.importantForAccessibility =
            if (hidden) android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            else android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        onDispose { }
    }
}

/**
 * What TalkBack says for a git file's status letter ("Modified", not "M"). The bridge sends "U" for an untracked file
 * (the Untracked list: pass [untracked]) while git's own "U" in the staged / changes lists is a conflict; a commit's
 * files can carry a score ("R100"), so only the first letter counts.
 */
fun gitStatusSpokenName(status: String, untracked: Boolean = false): String = when (status.firstOrNull()) {
    'M' -> L.components.a11y.gitModified
    'A' -> L.components.a11y.gitAdded
    'D' -> L.components.a11y.gitDeleted
    'R' -> L.components.a11y.gitRenamed
    'C' -> L.components.a11y.gitCopied
    'T' -> L.components.a11y.gitTypeChanged
    '?' -> L.components.a11y.gitUntracked
    'U' -> if (untracked) L.components.a11y.gitUntracked else L.components.a11y.gitConflict
    else -> status
}

/**
 * On a terminal: "Read terminal output" in TalkBack's actions menu (TalkBack itself only gets the rows on screen). It
 * hands [onRead] the whole output, scrollback included, with wrapped lines joined back (its last 2000 lines at most),
 * to show as selectable text. An accessibility action only: nothing changes on screen or for touch.
 */
fun com.termux.view.TerminalView.addReadOutputA11yAction(onRead: (String) -> Unit) {
    androidx.core.view.ViewCompat.addAccessibilityAction(this, L.components.a11y.readTerminalOutput) { view, _ ->
        val text = (view as com.termux.view.TerminalView).currentSession?.emulator?.screen
            ?.getTranscriptTextWithFullLinesJoined()?.trimEnd() ?: ""
        val lines = text.lines()
        onRead(if (lines.size > 2000) lines.takeLast(2000).joinToString("\n") else text)
        true
    }
}
