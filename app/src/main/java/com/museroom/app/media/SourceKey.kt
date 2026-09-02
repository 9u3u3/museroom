package com.museroom.app.media

/**
 * What a consent decision applies to.
 *
 * For a music app that is the app itself. For a browser it has to be finer: one
 * decision covering every site someone visits is useless, because the reason to
 * block a browser is usually one page, not the browser.
 */
data class SourceKey(val packageName: String, val site: String?) {

    /** Stable string used as the preferences key. */
    val id: String get() = if (site != null) "$packageName|$site" else packageName

    val isBrowser: Boolean get() = packageName in Browsers.PACKAGES

    companion object {
        /** Reverses [id], so a stored row can be traced back to its source. */
        fun parse(id: String): SourceKey {
            val split = id.indexOf('|')
            return if (split < 0) {
                SourceKey(id, null)
            } else {
                SourceKey(id.take(split), id.substring(split + 1).takeIf { it.isNotEmpty() })
            }
        }
    }
}

object Browsers {

    val PACKAGES = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "org.chromium.chrome",
        "org.mozilla.firefox",
        "org.mozilla.fenix",
        "org.mozilla.focus",
        "com.brave.browser",
        "com.brave.browser_nightly",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.opera.gx",
        "com.duckduckgo.mobile.android",
        "com.sec.android.app.sbrowser",
        "com.vivaldi.browser",
        "com.kiwibrowser.browser",
        "com.UCMobile.intl",
        "org.torproject.torbrowser",
        "com.yandex.browser",
        "acr.browser.lightning",
        "org.adblockplus.browser",
    )

    /** Looks like a hostname: at least one dot, no spaces, a plausible suffix. */
    private val hostShaped = Regex("""^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9-]+)*\.[a-z]{2,}$""")

    /**
     * Digs an origin out of whatever the browser published.
     *
     * Chrome fills the artist field with the site when a page supplies no media
     * metadata of its own, but a page that does supply metadata overrides it. So
     * every field is searched, and if nothing host-shaped turns up we return null
     * and the source stays blocked. Failing closed is the only safe direction
     * here: an unidentifiable site cannot be consented to.
     */
    fun siteFrom(vararg candidates: String?): String? {
        for (raw in candidates) {
            val value = raw?.trim()?.lowercase()?.removePrefix("www.") ?: continue
            if (value.isEmpty() || value.contains(' ')) continue
            val cleaned = value.substringAfter("://").substringBefore('/')
            if (hostShaped.matches(cleaned)) return cleaned
        }
        return null
    }
}
