package com.museroom.app.sync

import android.content.Context
import android.os.SystemClock
import com.museroom.app.media.NowPlaying
import com.museroom.app.media.NowPlayingRepository
import com.museroom.app.net.AuthRepository
import com.museroom.app.net.FriendsRepository
import com.museroom.app.net.ServerClock
import kotlinx.coroutines.delay

/**
 * Everybody starting a song at the same moment, including the host.
 *
 * A room used to begin each track whenever each phone happened to finish
 * fetching it. The host was always first, because they hear the song the
 * instant their own app starts it, and a listener cannot hear anything until
 * they have been told the song exists and have downloaded it. That is a second
 * or two, and no amount of correcting afterwards gives back an opening that
 * was never played: the joiner either skipped the first seconds or heard them
 * twice.
 *
 * Nothing clever about the network fixes that, because the delay is not
 * inefficiency. It is the time information takes to arrive. Every multi-room
 * audio system worth the name answers it the same way: agree a latency, put
 * the whole room behind it, and hand every device the same moment to begin.
 *
 * The awkward part is that Museroom does not own the host's audio. Their music
 * comes out of Spotify or YouTube Music, and the only way to put it behind the
 * same latency as everybody else's is to stop it and start it again. So that
 * is what this does, and it does it as narrowly as it can:
 *
 *  - only at a track change, where a short gap reads as the gap between songs
 *  - only when somebody is actually in the room to wait for
 *  - only for as long as the room has been measured to need
 *  - and never twice for an app that has shown it will not be stopped
 *
 * A host listening alone is never held, and never notices this exists.
 */
object RoomStart {

    /** Where the wait starts before anybody has been measured. */
    private const val OPENING_BID_MS = 2_000L

    /** Below this the room is not really waiting for anybody. */
    private const val FLOOR_MS = 900L

    /** Above this, waiting is worse than being slightly out of step. */
    private const val CEILING_MS = 6_000L

    /** How long a player is given to act on being asked to stop. */
    private const val OBEY_WITHIN_MS = 1_200L

    /** How often to look while waiting for that. */
    private const val LOOK_EVERY_MS = 100L

    /**
     * How far a player may drift and still count as stopped.
     *
     * Position is the honest test. A player reports every state under the sun
     * across a track change — stopped, buffering, none at all — and it reports
     * them whether or not anybody asked it to pause, so the flag says nothing
     * about whether the pause took. Whether the music is still moving does.
     */
    private const val STOPPED_WITHIN_MS = 250L

    /** How fast a room that keeps arriving early is allowed to tighten up. */
    private const val EASE_DOWN_MS = 150L

    /** Slack over the worst listener, so the estimate is not a coin toss. */
    private const val HEADROOM_MS = 250L

    /** How often to ask the room whether it is ready yet. */
    private const val ASK_EVERY_MS = 600L

    /** Time for a moved moment to reach everybody before it arrives. */
    private const val PUSH_ALLOWANCE_MS = 700L

    /** How much further out the moment is pushed when somebody is not ready. */
    private const val EXTENSION_MS = 1_200L

    /**
     * How long a room will be held for one phone that is not answering.
     *
     * Somebody who has walked away must not be able to keep everybody else in
     * silence, so patience ends and the rest of the room begins.
     */
    private const val PATIENCE_MS = 9_000L

    /**
     * Whether the host's own music is stopped by us right now.
     *
     * Read by the publisher, which must not mistake our own hand on the pause
     * button for the host putting their phone down. A room told "they stopped"
     * lets the track go, and it is about to be told to start it.
     */
    @Volatile
    var holding: Boolean = false
        private set

    /** The moment everybody begins, while one is pending. Shared clock. */
    @Volatile
    var startsAtMs: Long = 0L
        private set

    private var latencyMs = OPENING_BID_MS

    /** Apps that were asked to stop and did not. Asked once, then left alone. */
    private val refuses = mutableSetOf<String>()

    /** What the room is currently waiting, for the diagnostics panel. */
    val waitMs: Long get() = latencyMs

    /** Whether this player has shown it cannot be held. */
    fun refused(packageName: String): Boolean = synchronized(refuses) {
        packageName in refuses
    }

    /**
     * Whether this track change is worth holding the host for.
     *
     * An advert is not a song anybody chose and is not worth a gap. A player
     * that has already refused is not asked again, because asking costs a real
     * pause attempt on somebody's music every single track.
     */
    fun worthHolding(track: NowPlaying?, listeners: Int): Boolean {
        val playing = track ?: return false
        if (listeners <= 0) return false
        if (!playing.isPlaying || playing.isAdvert || playing.title.isBlank()) return false
        return !refused(playing.packageName)
    }

    /**
     * Hold the host, tell the room when to start, then let everybody go at once.
     *
     * Returns false if the host's player would not be stopped, in which case
     * nothing was published and the caller should carry on as it always did.
     * That is a real outcome rather than a failure: a room whose host cannot
     * be held still keeps its listeners in step with each other.
     */
    suspend fun conduct(
        context: Context,
        track: NowPlaying,
        listeners: List<Int?>,
    ): Boolean {
        val app = track.packageName
        holding = true
        try {
            if (!NowPlayingRepository.hold(app)) {
                giveUpOn(app, "would not take the command")
                return false
            }
            if (!stopped(app)) {
                giveUpOn(app, "kept playing anyway")
                NowPlayingRepository.release(app)
                return false
            }

            adapt(listeners)

            // Where their player actually came to rest, not zero. A track
            // change is noticed a moment after it happens, so the host is
            // already a little way in, and the room joins them there rather
            // than rewinding them to the start of a song they have begun.
            val stopped = NowPlayingRepository.sessions.value
                .firstOrNull { it.packageName == app } ?: track
            val from = stopped.positionAt(SystemClock.elapsedRealtime())

            val sync = SyncEngine.get(context)
            var target = ServerClock.nowMs() + latencyMs

            suspend fun announce() {
                startsAtMs = target
                sync.publishNowPlaying(
                    track = stopped,
                    positionMs = from,
                    startsAt = java.time.Instant.ofEpochMilli(target),
                    startPositionMs = from,
                    // Stopped, but not stopped in the sense a room cares
                    // about. The start time beside it tells them apart.
                    playingOverride = false,
                )
            }
            announce()

            // Held open until everybody actually has the track, rather than
            // let go on a timer and hoped for. A guess that comes up short
            // costs somebody the opening of the song, and that is the one
            // price this is not allowed to pay. The moment moves instead.
            val patienceEnds = ServerClock.nowMs() + PATIENCE_MS
            while (true) {
                if (everybodyHas(context, stopped.fingerprint)) break
                val nowMs = ServerClock.nowMs()
                if (nowMs >= patienceEnds) break
                if (nowMs >= target - PUSH_ALLOWANCE_MS) {
                    target = nowMs + EXTENSION_MS
                    announce()
                }
                delay(ASK_EVERY_MS)
            }

            // Waited out against the shared clock rather than by counting down
            // from here, so that the publish taking a moment comes out of the
            // wait instead of being added to it.
            val remaining = target - ServerClock.nowMs()
            if (remaining > 0) delay(remaining)

            // They pressed next while everybody was getting ready. Nothing has
            // been published since the schedule, so resuming and announcing
            // the old track would tell the room to start a song the host has
            // already left. Let go and let the ordinary path say what is true.
            val stillTheirs = NowPlayingRepository.sessions.value
                .firstOrNull { it.packageName == app }
                ?.fingerprint == stopped.fingerprint
            if (!stillTheirs) {
                NowPlayingRepository.release(app)
                return false
            }

            NowPlayingRepository.release(app)
            // Said immediately rather than waiting for the player's own
            // callback to come back around: listeners are already playing by
            // now, and a row that still says stopped would have them stop.
            sync.publishNowPlaying(
                track = stopped,
                positionMs = from,
                startsAt = java.time.Instant.ofEpochMilli(target),
                startPositionMs = from,
                playingOverride = true,
            )
            return true
        } finally {
            holding = false
            startsAtMs = 0L
        }
    }

    /**
     * Whether the music has actually stopped moving.
     *
     * Watched rather than asked once. A track change already makes a player
     * announce several states in quick succession, so a single reading taken
     * a moment after the request tells you nothing: it will often say stopped
     * because the song was changing, not because we asked. Two positions a
     * short time apart cannot lie about it.
     */
    private suspend fun stopped(packageName: String): Boolean {
        val deadline = SystemClock.elapsedRealtime() + OBEY_WITHIN_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val first = reading(packageName) ?: return false
            delay(LOOK_EVERY_MS * 2)
            val second = reading(packageName) ?: return false
            if (second - first < STOPPED_WITHIN_MS) return true
        }
        return false
    }

    private fun reading(packageName: String): Long? =
        NowPlayingRepository.sessions.value
            .firstOrNull { it.packageName == packageName }
            ?.positionAt(SystemClock.elapsedRealtime())

    /** Why a host is not being held, in the words the self-check will show. */
    @Volatile
    var lastRefusal: String = ""
        private set

    /**
     * Whether everybody in the room has this track loaded and waiting.
     *
     * An empty room is ready by definition. Somebody who has not said anything
     * is not ready, which is the safe way round: the cost of being wrong here
     * is a second of silence for the host, and the cost of the other mistake
     * is a listener losing the start of a song.
     */
    private suspend fun everybodyHas(context: Context, fingerprint: String): Boolean {
        val me = AuthRepository.get(context).session.value?.userId ?: return true
        val roster = FriendsRepository.get(context).roomMembersOf(me).getOrNull() ?: return false
        if (roster.isEmpty()) return true
        return roster.all { it.readyFor == fingerprint }
    }

    private fun giveUpOn(packageName: String, why: String) {
        synchronized(refuses) { refuses += packageName }
        lastRefusal = "$packageName $why"
    }

    /**
     * Move the wait towards what the room has actually been needing.
     *
     * Up fast and down slowly, which is the right way round: being late is
     * heard as a song starting without you, and being early is heard as
     * nothing at all. A listener who has never reported says nothing either
     * way rather than counting as fast.
     */
    internal fun adapt(listeners: List<Int?>) {
        val reports = listeners.filterNotNull()
        if (reports.isEmpty()) return
        val worst = reports.max()
        latencyMs = when {
            worst > 0 -> (latencyMs + worst + HEADROOM_MS).coerceAtMost(CEILING_MS)
            // Comfortably early, by more than the slack we would have added.
            worst < -HEADROOM_MS -> (latencyMs - EASE_DOWN_MS).coerceAtLeast(FLOOR_MS)
            else -> latencyMs
        }
    }

    /** For tests, and for a room starting fresh. */
    internal fun reset() {
        latencyMs = OPENING_BID_MS
        lastRefusal = ""
        holding = false
        startsAtMs = 0L
        synchronized(refuses) { refuses.clear() }
    }
}
