package ai.antigem.ui.components

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.focusGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
