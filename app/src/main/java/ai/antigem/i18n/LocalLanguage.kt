package ai.antigem.i18n

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * [LanguageManager.version], handed down from GeminiTheme. Static on purpose: when it changes, everything below
 * recomposes once (so every [L] text is read again in the new language), while reading a text costs nothing.
 */
val LocalLanguageVersion = staticCompositionLocalOf { 0 }
