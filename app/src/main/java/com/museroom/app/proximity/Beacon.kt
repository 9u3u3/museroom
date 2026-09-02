package com.museroom.app.proximity

import java.security.SecureRandom

/**
 * What goes over the air.
 *
 * A short random token and nothing else. It is not derived from the user, so it
 * cannot be reversed; it rotates, so a scanner logging advertisements cannot
 * follow anyone; and it expires, so an old recording is worthless. Only the
 * server can turn one back into a person, and only while both people have the
 * feature switched on.
 */
object Beacon {

    /**
     * Museroom's marker in the advertisement. Manufacturer id 0xFFFF is the
     * range reserved for exactly this sort of use, so the prefix is what actually
     * identifies us, and the scan filter matches on it.
     */
    const val MANUFACTURER_ID = 0xFFFF
    val PREFIX = byteArrayOf(0x4D, 0x55, 0x53, 0x45) // "MUSE"

    /** Eight bytes: small enough for one legacy advertisement, far too large to guess. */
    private const val TOKEN_BYTES = 8

    /** Rotated well inside its lifetime, so a scan never falls into a gap. */
    const val ROTATE_AFTER_MS = 15 * 60 * 1000L
    const val LIFETIME_MS = 35 * 60 * 1000L

    private val random = SecureRandom()

    fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return bytes.toHex()
    }

    /** The advertised payload: our prefix, then the token. */
    fun payload(token: String): ByteArray = PREFIX + token.fromHex()

    /** Reads a token back out of an advertisement, or null if it is not ours. */
    fun tokenFrom(payload: ByteArray?): String? {
        if (payload == null || payload.size != PREFIX.size + TOKEN_BYTES) return null
        for (i in PREFIX.indices) if (payload[i] != PREFIX[i]) return null
        return payload.copyOfRange(PREFIX.size, payload.size).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
