package com.example.gemini.ui.components

import android.util.Log
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.SoftwareKeyboardController
import com.example.gemini.AppViewMode

/**
 * Coordinates unified focus and keyboard (IME) handoff across persistent main screens:
 * CHAT, IDE, TERMINAL, and BROWSER.
 *
 * Rules:
 * 1. If keyboard is showing in the active screen, it stays showing across all other screens with 0 flicker.
 * 2. If keyboard is closed, it stays closed across all screens.
 * 3. Exact cursor position and selection are preserved independently in each screen.
 */
class AppFocusCoordinator {
    private val TAG = "AppFocusCoordinator"

    private val focusRequesters = mutableMapOf<AppViewMode, () -> Unit>()
    private val clearFocusRequesters = mutableMapOf<AppViewMode, () -> Unit>()

    fun registerScreen(
        mode: AppViewMode,
        onRequestFocus: () -> Unit,
        onClearFocus: () -> Unit
    ) {
        focusRequesters[mode] = onRequestFocus
        clearFocusRequesters[mode] = onClearFocus
    }

    fun unregisterScreen(mode: AppViewMode) {
        focusRequesters.remove(mode)
        clearFocusRequesters.remove(mode)
    }

    fun onScreenLeaving(leavingMode: AppViewMode, isKeyboardOpen: Boolean) {
        Log.d(TAG, "Screen $leavingMode leaving, isKeyboardOpen=$isKeyboardOpen")
        // Only force clear focus if the keyboard is closed.
        // If the keyboard is open, allowing the target screen to request focus transfers the IME seamlessly
        // without triggering Compose's explicit hideSoftInputFromWindow call.
        if (!isKeyboardOpen) {
            clearFocusRequesters[leavingMode]?.invoke()
        }
    }

    fun onScreenEntering(
        enteringMode: AppViewMode,
        isKeyboardOpen: Boolean
    ) {
        Log.d(TAG, "Screen $enteringMode entering, isKeyboardOpen=$isKeyboardOpen")

        if (isKeyboardOpen) {
            // Keyboard is active: immediately request focus on target screen to hand off IME with 0 flicker
            focusRequesters[enteringMode]?.invoke()
        } else {
            // Keyboard is not active: keep keyboard closed and ensure offscreen view is not holding active window focus
            clearFocusRequesters[enteringMode]?.invoke()
        }
    }
}

val LocalAppFocusCoordinator = compositionLocalOf { AppFocusCoordinator() }
