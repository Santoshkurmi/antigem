package com.example.gemini.ui.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebSettings
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * How the in-app browser presents itself to websites.
 *
 * Google's sign-in risk checks compare the User-Agent string, the Sec-CH-UA client hints and the
 * X-Requested-With header with each other and with earlier sessions. A browser that looks like
 * plain Chrome, the same way every launch, passes far more often than one with random or
 * contradicting markers. Phones still differ from each other the same way real Chrome installs do:
 * by Chrome version (from each phone's WebView) and by device model (in high-entropy client hints).
 */
object BrowserIdentity {
    private const val WEBVIEW_BRAND = "Android WebView"
    private const val CHROME_BRAND = "Google Chrome"

    /** WebView's own metadata, captured from the first WebView before anything was overridden. */
    @Volatile
    private var defaultMetadata: UserAgentMetadata? = null

    /** Pages where nothing may be injected and no DevTools session may be attached. */
    fun isGoogleSignInUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "accounts.youtube.com" ||
            host == "myaccount.google.com" ||
            Regex("""^accounts\.google\.[a-z.]+$""").matches(host)
    }

    fun chromeFullVersion(userAgent: String): String {
        Regex("""Chrome/(\d+\.\d+\.\d+\.\d+)""").find(userAgent)?.groupValues?.get(1)?.let { return it }
        Regex("""Chrome/(\d+)""").find(userAgent)?.groupValues?.get(1)?.let { return "$it.0.0.0" }
        return "0.0.0.0"
    }

    private fun chromeMajorVersion(context: Context) =
        chromeFullVersion(WebSettings.getDefaultUserAgent(context)).substringBefore('.')

    /**
     * The reduced UA that Chrome for Android sends on every phone ("Android 10; K"), with this
     * phone's real Chrome major version. Tablets drop "Mobile" like Chrome does.
     */
    fun mobileUserAgent(context: Context): String {
        val mobile = if (WebSettings.getDefaultUserAgent(context).contains(" Mobile")) " Mobile" else ""
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${chromeMajorVersion(context)}.0.0.0$mobile Safari/537.36"
    }

    /** The UA Chrome for Android sends for "Desktop site". */
    fun desktopUserAgent(context: Context): String =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${chromeMajorVersion(context)}.0.0.0 Safari/537.36"

    /** True when client hints (header and navigator.userAgentData) can be set natively. */
    fun canSetClientHints() = WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)

    /** Applies the UA string, matching client hints and the X-Requested-With removal to one WebView. */
    fun applyTo(settings: WebSettings, context: Context, desktop: Boolean) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
        }
        if (canSetClientHints()) {
            val base = defaultMetadata ?: WebSettingsCompat.getUserAgentMetadata(settings).also { defaultMetadata = it }
            runCatching { WebSettingsCompat.setUserAgentMetadata(settings, chromeMetadata(base, desktop)) }
        }
        settings.userAgentString = if (desktop) desktopUserAgent(context) else mobileUserAgent(context)
    }

    /**
     * WebView's metadata with "Android WebView" renamed to "Google Chrome". The GREASE brand,
     * versions and brand order stay as WebView generated them, which is the same list Chrome of
     * this version sends.
     */
    private fun chromeMetadata(base: UserAgentMetadata, desktop: Boolean): UserAgentMetadata {
        val brands = base.brandVersionList.map { brand ->
            if (brand.brand == WEBVIEW_BRAND) UserAgentMetadata.BrandVersion.Builder(brand).setBrand(CHROME_BRAND).build() else brand
        }.toMutableList()
        if (brands.none { it.brand == CHROME_BRAND }) {
            brands.firstOrNull { it.brand == "Chromium" }?.let {
                brands.add(UserAgentMetadata.BrandVersion.Builder(it).setBrand(CHROME_BRAND).build())
            }
        }
        val builder = UserAgentMetadata.Builder(base).setBrandVersionList(brands)
        if (desktop) {
            builder.setPlatform("Linux").setPlatformVersion("").setArchitecture("x86").setBitness(64)
                .setModel("").setMobile(false).setWow64(false)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA_FORM_FACTORS)) {
            builder.setFormFactors(listOf(if (desktop) UserAgentMetadata.FORM_FACTOR_DESKTOP else UserAgentMetadata.FORM_FACTOR_MOBILE))
        }
        return builder.build()
    }
}
