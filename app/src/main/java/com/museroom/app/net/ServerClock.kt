package com.museroom.app.net

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant

/**
 * One clock, for phones that do not have one.
 *
 * Everything a room does rests on a single sentence: they were at 2:15 at this
 * moment. One phone writes the moment and another reads it, and until now each
 * used its own idea of what time it was. Two Android phones are routinely most
 * of a second apart, and nothing in the app noticed, because the tolerance was
 * wide enough to swallow it.
 *
 * It stops being swallowable the moment you hold two players together closely.
 * A permanent half-second of disagreement becomes a permanent correction, and a
 * permanent correction is a tempo difference — which is far more audible than
 * the half-second it was trying to fix. So neither phone's clock is the
 * authority. Both ask the database, and both work in its time.
 *
 * The measurement is the old one: note the time, ask, note it again, and assume
 * the answer was true halfway through. The best sample wins rather than the
 * newest, because a reading taken during a slow moment on the network is worse
 * than one taken a few minutes ago on a fast one, and a clock does not go off.
 */
object ServerClock {

    /** Server time minus this phone's time, in milliseconds. */
    @Volatile
    private var skewMs: Long = 0

    /** How uncertain that number is: half the round trip that produced it. */
    @Volatile
    private var uncertaintyMs: Long = Long.MAX_VALUE

    @Volatile
    private var measuredAt: Long = 0

    /** Past this the network has changed enough that a better sample may exist. */
    private const val STALE_AFTER_MS = 10 * 60 * 1000L

    /** A round trip worse than this says more about the network than the clock. */
    private const val USELESS_ABOVE_MS = 4_000L

    /** Whether anything has been measured, for the diagnostics panel. */
    val measured: Boolean get() = uncertaintyMs != Long.MAX_VALUE

    /** What the offset is, so a person looking for a reason can see it. */
    val offsetMs: Long get() = skewMs

    /** Now, in the only time both phones agree on. */
    fun nowMs(): Long = System.currentTimeMillis() + skewMs

    fun now(): Instant = Instant.ofEpochMilli(nowMs())

    /**
     * Asks, and keeps the answer if it is better than the one we have.
     *
     * Safe to call often. It does nothing when the current reading is both
     * recent and tight, which is most of the time.
     */
    suspend fun sync(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val age = SystemClock.elapsedRealtime() - measuredAt
        if (!force && measured && age < STALE_AFTER_MS && uncertaintyMs < 250) {
            return@withContext true
        }
        runCatching {
            val before = System.currentTimeMillis()
            val body = Supabase.rpc("server_now", buildJsonObject { }, null)
            val after = System.currentTimeMillis()

            val roundTrip = after - before
            if (roundTrip > USELESS_ABOVE_MS) return@runCatching false
            val server = Instant.parse(body.trim().trim('"')).toEpochMilli()

            // The answer was true at some point between asking and hearing
            // back. The middle is the best guess, and half the trip is the
            // most it can be wrong by.
            val half = roundTrip / 2
            val sample = server - (before + half)

            // Better means measured through a quieter moment, not measured
            // more recently. A tight reading from ten minutes ago describes
            // the clock more accurately than a loose one from just now.
            if (half <= uncertaintyMs || age >= STALE_AFTER_MS) {
                skewMs = sample
                uncertaintyMs = half
                measuredAt = SystemClock.elapsedRealtime()
            }
            true
        }.getOrDefault(false)
    }
}
