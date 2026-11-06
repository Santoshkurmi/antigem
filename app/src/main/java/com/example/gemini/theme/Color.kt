package com.example.gemini.theme

import androidx.compose.ui.graphics.Color

// The app's palette names, from before themes existed. They are now live views of the theme on screen
// (ThemeManager / AppColors), so every place that uses them follows a theme change. The "Light"/"Dark" pairs were
// always picked by the current mode (`if (isDark) …Dark else …Light`): both names of a pair give the theme's color.
// New code should use AppColors or MaterialTheme.colorScheme directly.

/** Main accent (the theme's primary). */
val ClaudeTerracotta: Color get() = AppColors.primary
val ClaudeTerracottaDark: Color get() = AppColors.secondary
val ClaudeCream: Color get() = AppColors.background
val ClaudeWarmSurface: Color get() = AppColors.background
val ClaudeUserBubbleLight: Color get() = AppColors.userBubble
val ClaudeThinkingLight: Color get() = AppColors.thinkingBg

val ClaudeDarkBg: Color get() = AppColors.background
/** Cards and panels. */
val ClaudeDarkSurface: Color get() = AppColors.card
val ClaudeUserBubbleDark: Color get() = AppColors.userBubble
val ClaudeThinkingDark: Color get() = AppColors.thinkingBg

/** Background of code blocks in chats. */
val CodeBlockBgDark: Color get() = AppColors.codeBg
val TextPrimaryLight: Color get() = AppColors.onBackground
val TextSecondaryLight: Color get() = AppColors.textSecondary
val TextPrimaryDark: Color get() = AppColors.onBackground
val TextSecondaryDark: Color get() = AppColors.textSecondary

/** Status colors (named after the quota pill where they started). */
val QuotaGreen: Color get() = AppColors.success
val QuotaAmber: Color get() = AppColors.warning
val QuotaRed: Color get() = AppColors.danger

val GeminiBlue: Color get() = AppColors.info
val GeminiPurple: Color get() = AppColors.accentAlt
