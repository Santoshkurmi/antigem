package com.termux.app

import com.example.gemini.data.service.TermuxService as CoreTermuxService

/**
 * Direct com.termux.app.TermuxService implementation for shell commands
 * (e.g., termux-wake-lock, termux-wake-unlock) targeting explicit component com.termux.app.TermuxService.
 */
class TermuxService : CoreTermuxService()
