package com.museroom.app

import com.museroom.app.media.Sources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The supported list is the whole privacy boundary now. Anything these tests let
 * through is something that gets recorded.
 */
class SourcesTest {

    @Test
    fun `the streaming apps we support are counted`() {
        listOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "app.revanced.android.apps.youtube.music",
            "com.apple.android.music",
            "com.amazon.mp3",
            "deezer.android.app",
            "com.soundcloud.android",
            "com.aspiro.tidal",
            "com.jio.media.jiobeats",
        ).forEach { assertTrue(it, Sources.isSupported(it)) }
    }

    @Test
    fun `youtube proper is not youtube music`() {
        // One character of package name apart, and the difference is whether an
        // evening of videos lands on a public leaderboard.
        assertFalse(Sources.isSupported("app.revanced.android.youtube"))
        assertFalse(Sources.isSupported("com.google.android.youtube"))
    }

    @Test
    fun `browsers are never counted`() {
        listOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.brave.browser",
            "com.duckduckgo.mobile.android",
            "com.sec.android.app.sbrowser",
        ).forEach { assertFalse(it, Sources.isSupported(it)) }
    }

    @Test
    fun `anything unrecognised is refused rather than guessed at`() {
        assertFalse(Sources.isSupported(""))
        assertFalse(Sources.isSupported("com.some.podcast.app"))
        assertFalse(Sources.isSupported("com.spotify.music.fake"))
        assertFalse(Sources.isSupported("com.whatsapp"))
    }

    @Test
    fun `a longer list is still a list, not everything that makes noise`() {
        // The allowlist grew to cover the streaming apps people actually use.
        // What it must never grow into is "anything with a media session",
        // which is every podcast app, every game and every video in a browser.
        listOf(
            "com.google.android.videos",
            "com.netflix.mediaclient",
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "org.videolan.vlc",
            "com.google.android.apps.podcasts",
        ).forEach { assertFalse(it, Sources.isSupported(it)) }
    }

    @Test
    fun `forks are labelled as the app they actually are`() {
        assertEquals("YouTube Music", Sources.label("app.revanced.android.apps.youtube.music"))
        assertEquals("YouTube Music", Sources.label("app.rvx.android.apps.youtube.music"))
        assertEquals("Spotify", Sources.label("com.spotify.music"))
    }
}
