package com.museroom.app

import com.museroom.app.media.Browsers
import com.museroom.app.media.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Consent has to fail closed. Anything these tests let through is something that
 * gets recorded without the user having agreed to it.
 */
class SourceConsentTest {

    @Test
    fun `an origin is recognised in whichever field the browser used`() {
        assertEquals("example.com", Browsers.siteFrom("example.com"))
        assertEquals("example.com", Browsers.siteFrom(null, "www.example.com"))
        assertEquals("news.bbc.co.uk", Browsers.siteFrom(null, null, "news.bbc.co.uk"))
        assertEquals("example.com", Browsers.siteFrom("https://example.com/watch?v=1"))
    }

    @Test
    fun `a page title is not mistaken for a site`() {
        assertNull(Browsers.siteFrom("Some Video Title"))
        assertNull(Browsers.siteFrom("PARTYNEXTDOOR"))
        assertNull(Browsers.siteFrom(""))
        assertNull(Browsers.siteFrom(null))
    }

    @Test
    fun `the first host-shaped field wins over later ones`() {
        assertEquals("site.org", Browsers.siteFrom("A Long Title", "site.org", "other.com"))
    }

    @Test
    fun `browser sites get their own key, so one site is not a verdict on the browser`() {
        val a = SourceKey("com.android.chrome", "example.com")
        val b = SourceKey("com.android.chrome", "other.com")
        assertTrue("two sites in one browser must not share a decision", a.id != b.id)
        assertTrue(a.isBrowser)
    }

    @Test
    fun `a key survives a round trip through storage`() {
        val browser = SourceKey("com.android.chrome", "example.com")
        assertEquals(browser, SourceKey.parse(browser.id))

        val app = SourceKey("com.spotify.music", null)
        assertEquals(app, SourceKey.parse(app.id))
    }

    @Test
    fun `a music app is not treated as a browser`() {
        assertTrue(!SourceKey("com.spotify.music", null).isBrowser)
        assertTrue(!SourceKey("app.revanced.android.apps.youtube.music", null).isBrowser)
    }
}
