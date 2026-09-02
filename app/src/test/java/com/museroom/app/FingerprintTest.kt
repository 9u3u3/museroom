package com.museroom.app

import com.museroom.app.media.Fingerprint
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The cases that matter are the ones where the two apps describe the same song
 * differently. If these drift apart, the leaderboard splits a track in two.
 */
class FingerprintTest {

    @Test
    fun `youtube music topic suffix is stripped`() {
        assertEquals("radiohead", Fingerprint.artist("Radiohead - Topic"))
        assertEquals("radiohead", Fingerprint.artist("Radiohead"))
    }

    @Test
    fun `artist separated from album by a middle dot`() {
        assertEquals("tameimpala", Fingerprint.artist("Tame Impala · Currents"))
    }

    @Test
    fun `only the primary artist survives`() {
        assertEquals("kendricklamar", Fingerprint.artist("Kendrick Lamar, SZA"))
        assertEquals("drake", Fingerprint.artist("Drake feat. Rihanna"))
    }

    @Test
    fun `video and audio noise leaves the title`() {
        assertEquals("weirdfishes", Fingerprint.title("Weird Fishes (Official Video)"))
        assertEquals("weirdfishes", Fingerprint.title("Weird Fishes [Lyrics]"))
        assertEquals("weirdfishes", Fingerprint.title("Weird Fishes - Official Music Video"))
        assertEquals("weirdfishes", Fingerprint.title("Weird Fishes"))
    }

    @Test
    fun `remaster tags do not create a second track`() {
        assertEquals("letitbe", Fingerprint.title("Let It Be (Remastered 2009)"))
        assertEquals("letitbe", Fingerprint.title("Let It Be"))
    }

    @Test
    fun `punctuation differences collapse`() {
        assertEquals(Fingerprint.title("Don't Stop Me Now"), Fingerprint.title("Dont Stop Me Now"))
    }

    @Test
    fun `stylised letter spacing does not split a track`() {
        // Straight from a real device: one app spaces the letters out, the other
        // does not, and both must land on the same key.
        assertEquals(
            Fingerprint.of("For Certain", "PARTYNEXTDOOR", join(3, 30)),
            Fingerprint.of("F o r C e r t a i n", "PARTYNEXTDOOR", join(3, 31)),
        )
    }

    @Test
    fun `the same song from both apps agrees`() {
        val spotify = Fingerprint.of("Weird Fishes / Arpeggi", "Radiohead", join(5, 18))
        val ytMusic = Fingerprint.of(
            "Weird Fishes / Arpeggi (Official Video)",
            "Radiohead - Topic",
            join(5, 19), // the two apps routinely differ by about a second
        )
        assertEquals(spotify, ytMusic)
    }

    @Test
    fun `genuinely different songs stay apart`() {
        val a = Fingerprint.of("Nude", "Radiohead", join(4, 15))
        val b = Fingerprint.of("Reckoner", "Radiohead", join(4, 50))
        assert(a != b)
    }

    private fun join(minutes: Int, seconds: Int) = ((minutes * 60) + seconds) * 1000L
}
