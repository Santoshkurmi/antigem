package com.example.gemini.data.files

import android.net.Uri
import androidx.core.content.FileProvider
import com.example.gemini.data.miniapps.MiniAppSigning

/**
 * AntiGem's FileProvider. It tells other apps that *.agapp files are mini app packages, so a package sent through
 * WhatsApp / Telegram / email keeps that type and tapping it on the other phone opens AntiGem's installer
 * (Android doesn't know the extension, so plain FileProvider would say application/octet-stream).
 */
class AntiGemFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? =
        if (uri.path?.endsWith(".agapp", ignoreCase = true) == true) MiniAppSigning.MIME else super.getType(uri)

    override fun getTypeAnonymous(uri: Uri): String? =
        if (uri.path?.endsWith(".agapp", ignoreCase = true) == true) MiniAppSigning.MIME else super.getTypeAnonymous(uri)
}
