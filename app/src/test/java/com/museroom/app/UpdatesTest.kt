package com.museroom.app

import com.museroom.app.net.Release
import com.museroom.app.net.Supabase
import com.museroom.app.net.Updates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Being told a newer build exists.
 *
 * Nobody can watch this one happen: a prompt that never appears and a prompt
 * that appears for the build you already have look the same from outside,
 * until the day one of them is wrong. So the decision is pinned here.
 */
class UpdatesTest {

    private fun release(code: Int, url: String = "https://9u3u3.github.io/museroom/") =
        Release(versionCode = code, versionName = "9.9", url = url)

    @Test fun `a newer build is offered`() {
        assertTrue(Updates.shouldOffer(release(19), installed = 18, skipped = 0, force = false))
    }

    @Test fun `the build you are on is not`() {
        assertFalse(Updates.shouldOffer(release(18), installed = 18, skipped = 0, force = false))
    }

    /** A site rolled back should never talk somebody into going backwards. */
    @Test fun `an older build is not offered`() {
        assertFalse(Updates.shouldOffer(release(17), installed = 18, skipped = 0, force = false))
    }

    @Test fun `saying no to a version means no`() {
        assertFalse(Updates.shouldOffer(release(19), installed = 18, skipped = 19, force = false))
    }

    @Test fun `but the one after it still asks`() {
        assertTrue(Updates.shouldOffer(release(20), installed = 18, skipped = 19, force = false))
    }

    /** Asking from the settings screen is asking, whatever was turned down before. */
    @Test fun `checking by hand reaches past a refusal`() {
        assertTrue(Updates.shouldOffer(release(19), installed = 18, skipped = 19, force = true))
    }

    /**
     * The file is fetched over the network. Whatever it says, the button it
     * produces must not be able to send somebody somewhere else.
     */
    @Test fun `only an https link is offered`() {
        assertFalse(
            Updates.shouldOffer(
                release(19, "http://example.com/x.apk"), installed = 18, skipped = 0, force = false,
            ),
        )
        assertFalse(
            Updates.shouldOffer(
                release(19, "market://details?id=com.museroom.app"),
                installed = 18, skipped = 0, force = false,
            ),
        )
    }

    /** The shape the site actually publishes, read the way the app reads it. */
    @Test fun `the published file parses`() {
        val json = """
            {"version_code": 18, "version_name": "4.6",
             "url": "https://9u3u3.github.io/museroom/", "notes": "Something."}
        """.trimIndent()
        val release = Supabase.json.decodeFromString(Release.serializer(), json)
        assertEquals(18, release.versionCode)
        assertEquals("4.6", release.versionName)
        assertTrue(Updates.shouldOffer(release, installed = 17, skipped = 0, force = false))
    }
}
