package com.museroom.app

import com.museroom.app.proximity.Beacon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What goes over the air, and what must not.
 */
class BeaconTest {

    @Test
    fun `a token round trips through an advertisement`() {
        val token = Beacon.newToken()
        assertEquals(token, Beacon.tokenFrom(Beacon.payload(token)))
    }

    @Test
    fun `the payload fits in a single legacy advertisement`() {
        // 31 bytes total, of which flags take 3 and the manufacturer header 4.
        assertTrue(Beacon.payload(Beacon.newToken()).size <= 24)
    }

    @Test
    fun `somebody else's advertisement is ignored`() {
        assertNull(Beacon.tokenFrom(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)))
        assertNull(Beacon.tokenFrom(byteArrayOf()))
        assertNull(Beacon.tokenFrom(null))
    }

    @Test
    fun `a truncated advertisement is rejected rather than half-read`() {
        val full = Beacon.payload(Beacon.newToken())
        assertNull(Beacon.tokenFrom(full.copyOfRange(0, full.size - 1)))
        assertNull(Beacon.tokenFrom(full + 0x00))
    }

    @Test
    fun `tokens do not repeat`() {
        val tokens = List(500) { Beacon.newToken() }
        assertEquals(500, tokens.toSet().size)
        assertNotEquals(tokens[0], tokens[1])
    }

    @Test
    fun `rotation happens well inside a token's lifetime`() {
        // Otherwise a scan could land in the gap between one token expiring and
        // the next being published.
        assertTrue(Beacon.ROTATE_AFTER_MS < Beacon.LIFETIME_MS / 2)
    }
}
