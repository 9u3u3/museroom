package com.museroom.app.tracking

import android.content.Context
import android.os.SystemClock
import com.museroom.app.data.MuseroomDatabase
import com.museroom.app.data.PlayEventType
import com.museroom.app.data.toDomain
import com.museroom.app.data.toEntity
import com.museroom.app.credit.Crediting
import com.museroom.app.media.NowPlayingRepository
import com.museroom.app.media.advertPlaying
import com.museroom.app.media.pickActive
import com.museroom.app.privacy.PrivacyState
import com.museroom.app.net.FriendsRepository
import com.museroom.app.net.LikesRepository
import com.museroom.app.net.ListenRepository
import com.museroom.app.net.Updates
import com.museroom.app.sync.FollowSession
import com.museroom.app.notify.FriendAlerts
import com.museroom.app.notify.Notifier
import com.museroom.app.sync.RoomPresence
import com.museroom.app.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Watches what is playing and writes the event trail.
 *
 * Deliberately not a foreground service. The notification listener is already a
 * bound service the system keeps alive, which is how scrobblers stay running, and
 * a foreground service would need a declared type that does not honestly describe
 * what this does. If OEM battery management proves too aggressive in practice,
 * the self-check will show it as a stale last event.
 */
object PlaybackTracker {

    private const val PREFS = "museroom.tracking"
    private const val KEY_WATERMARK = "credited_through_event_id"
    private const val KEY_LET_IN_THROUGH = "let_in_through_request_id"
    private const val KEY_LIKES_THROUGH = "likes_announced_through"
    private const val HEARTBEAT_MS = 30_000L

    /**
     * The heartbeat. Changes no longer wait for a poll — they are written as
     * the player reports them — so this only exists to stop a long track going
     * stale to everybody watching.
     */
    private const val NOW_PLAYING_HEARTBEAT_MS = 15_000L
    private const val SYNC_MS = 60_000L
    private const val INBOX_MS = 20_000L

    /** Faster than the inbox, because somebody is waiting on this one. */
    private const val ANSWER_MS = 8_000L

    /** A friend putting a record on can wait a minute to be mentioned. */
    private const val FRIENDS_MS = 60_000L

    /** Nothing about a hand-installed app changes fast enough to ask sooner. */
    private const val UPDATE_CHECK_MS = 6 * 60 * 60 * 1000L

    private val differ = PlaybackDiffer(heartbeatMs = HEARTBEAT_MS)

    /** Ids already announced, so a pending request is not notified every 20s. */
    private val announced = mutableSetOf<Long>()
    private val mutex = Mutex()
    private var scope: CoroutineScope? = null

    fun start(context: Context) {
        if (scope != null) return
        val app = context.applicationContext
        val db = MuseroomDatabase.get(app)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        privacy = PrivacyState.get(app)
        appContext = app
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope

        newScope.launch {
            NowPlayingRepository.sessions.collect { record(db, prefs) }
        }

        // Heartbeats are what let the server tell "still listening" from "the app
        // died mid-song". Nothing else drives them; the player has no reason to
        // push an update while a track plays through.
        newScope.launch {
            while (true) {
                delay(HEARTBEAT_MS / 3)
                record(db, prefs)
            }
        }

        // What friends read, and what anybody following is steered by.
        //
        // Pausing has to travel, which is why this no longer skips a paused
        // player. Somebody in your room heard the previous message, "playing",
        // and would go on hearing it for as long as you never sent another.
        val sync = SyncEngine.get(app)
        newScope.launch { publishNowPlaying(sync) }

        // The outbox drains on a slow loop. A failed pass simply retries the same
        // rows next time, because uploads are keyed off a flag, not a clock.
        newScope.launch {
            while (true) {
                delay(SYNC_MS)
                sync.sync()
            }
        }

        // Somebody asking to listen along is the only thing worth interrupting
        // for, and it has to arrive whether or not Museroom is on screen.
        val listen = ListenRepository.get(app)
        newScope.launch {
            while (true) {
                delay(INBOX_MS)
                listen.inbox().onSuccess { requests ->
                    requests.filter { it.id !in announced }.forEach { request ->
                        announced += request.id
                        Notifier.listenRequest(app, request.id, request.handle, request.title)
                    }
                }
            }
        }

        // Being let in starts the music, here, without anybody pressing
        // anything. Asking and then having to find a second button on a screen
        // you are not looking at is the same manual step this was meant to end.
        newScope.launch { watchForBeingLetIn(app, listen, prefs) }

        // Somebody walking into your room, and friends putting something on.
        // Both belong here rather than on a screen: the whole point of either
        // message is that it reaches you when you are not looking.
        RoomPresence.start(app)
        newScope.launch { watchFriendsListening(app) }
        newScope.launch { watchLikes(app, prefs) }
        newScope.launch { watchForUpdates(app) }
        newScope.launch { showWhatIsBeingCounted(app) }
    }

    /**
     * Saying, out loud, that this is being recorded.
     *
     * An app that reads what you are listening to should be visible while it
     * does it, in the shade with everything else, rather than only from inside
     * itself. Nobody should have to open Museroom to find out whether Museroom
     * is running — and the honest version of that is a line that appears
     * exactly when something is being counted and goes when it is not.
     *
     * A private session shows nothing, because nothing is being counted. Nor
     * does a room: a joiner already has the room's own notification, and two
     * notifications for one piece of music is one too many.
     */
    private suspend fun showWhatIsBeingCounted(app: Context) {
        var shown = ""
        NowPlayingRepository.sessions.collect { sessions ->
            val hidden = privacy?.privateSession?.value == true
            val active = sessions.pickActive()
                ?.takeIf { it.isTracked && it.isPlaying && !hidden }
                // The room posts its own, with the same cover and more to say.
                ?.takeIf { FollowSession.following.value == null }

            val state = "${active?.fingerprint.orEmpty()}|${active?.artwork != null}"
            if (state == shown) return@collect
            shown = state

            if (active == null) {
                Notifier.clearTracking(app)
            } else {
                Notifier.tracking(
                    app, active.title, active.artist, active.sourceLabel, active.artwork,
                )
            }
        }
    }

    /**
     * Whether a newer build is on the site.
     *
     * Here rather than on a screen because the point is to reach somebody who
     * is not looking. Museroom is not on a store, so nothing updates itself
     * and nobody would otherwise be told; the alternative is people running an
     * old build for ever, which for an app where two phones have to agree
     * about a protocol goes wrong quietly.
     *
     * Announced once per version, never downloaded, never installed. It opens
     * the page and the person decides.
     */
    private suspend fun watchForUpdates(app: Context) {
        while (true) {
            Updates.check(app).onSuccess { offered ->
                val release = offered ?: Updates.newer.value
                if (release != null && !Updates.alreadyAnnounced(app, release)) {
                    // Marked only once it is actually in the shade. Marking
                    // first would turn a failed post into a version nobody is
                    // ever told about.
                    if (Notifier.update(app, release.versionName, release.notes)) {
                        Updates.markAnnounced(app, release)
                    }
                }
            }
            delay(UPDATE_CHECK_MS)
        }
    }

    /**
     * Somebody liked what you were playing.
     *
     * On the same slow loop as everything else social, and keyed off the last
     * like already announced rather than a clock, so signing in on a second
     * phone does not replay a month of them. The first pass only records where
     * we are; nothing that happened before you had the feature is news.
     */
    private suspend fun watchLikes(app: Context, prefs: android.content.SharedPreferences) {
        val likes = LikesRepository.get(app)
        val alerts = FriendAlerts.get(app)
        while (true) {
            delay(FRIENDS_MS)
            if (!alerts.enabled.value) continue
            val since = prefs.getString(KEY_LIKES_THROUGH, null)
            if (since == null) {
                prefs.edit().putString(KEY_LIKES_THROUGH, java.time.Instant.now().toString()).apply()
                continue
            }
            likes.received(since).onSuccess { arrived ->
                arrived.forEach { like ->
                    prefs.edit().putString(KEY_LIKES_THROUGH, like.at).apply()
                    val track = listOf(like.title, like.artist)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    Notifier.liked(app, like.handle, track)
                }
            }
        }
    }

    /**
     * A friend has started something.
     *
     * Only the transition is worth a message. Somebody who was already playing
     * when this loop first looked is not news, and neither is the same person
     * still playing on the next pass; what gets announced is the moment they
     * went from quiet to listening, once, silently.
     */
    private suspend fun watchFriendsListening(app: Context) {
        val friends = FriendsRepository.get(app)
        val alerts = FriendAlerts.get(app)
        var playing = emptySet<String>()
        var first = true

        while (true) {
            delay(FRIENDS_MS)
            // Switched off means no request at all, not a request whose answer
            // gets thrown away. Nothing here is worth a minute of radio.
            if (!alerts.enabled.value) {
                first = true
                playing = emptySet()
                continue
            }
            friends.friends().onSuccess { list ->
                val nowPlaying = list.filter { it.nowPlaying != null }
                if (!first) {
                    nowPlaying
                        .filter { it.profile.id !in playing }
                        .filter { alerts.shouldAnnounce(it.profile.id) }
                        .forEach { friend ->
                            val track = friend.nowPlaying?.let { np ->
                                listOf(np.title, np.artist)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · ")
                            }.orEmpty()
                            // The cover, if it is already to hand. Not worth
                            // holding up the message for, so a miss simply
                            // means the message arrives without a picture.
                            val art = friend.nowPlaying?.let {
                                com.museroom.app.media.Artwork.cached(it.title, it.artist)
                            }
                            Notifier.friendListening(
                                app, friend.profile.id, friend.profile.handle, track, art,
                            )
                        }
                }
                // Muted friends still count as seen, so unmuting somebody
                // mid-song does not announce a track they started an hour ago.
                playing = nowPlaying.map { it.profile.id }.toSet()
                first = false
            }
        }
    }

    /**
     * The other half of asking: noticing you were let in.
     *
     * A watermark rather than a clock, and one that starts at whatever you have
     * already sent, so signing in does not replay every room you were ever
     * admitted to.
     */
    private suspend fun watchForBeingLetIn(
        app: Context,
        listen: ListenRepository,
        prefs: android.content.SharedPreferences,
    ) {
        if (!prefs.contains(KEY_LET_IN_THROUGH)) {
            val latest = listen.lastSentId().getOrNull() ?: return
            prefs.edit().putLong(KEY_LET_IN_THROUGH, latest).apply()
        }
        while (true) {
            delay(ANSWER_MS)
            val through = prefs.getLong(KEY_LET_IN_THROUGH, 0L)
            listen.accepted(through).onSuccess { answers ->
                answers.forEach { answer ->
                    prefs.edit().putLong(KEY_LET_IN_THROUGH, answer.id).apply()
                    if (FollowSession.following.value?.hostId == answer.toUser) return@forEach
                    Notifier.letIn(app, answer.handle)
                    FollowSession.start(app, answer.toUser, answer.handle)
                }
            }
        }
    }

    /**
     * Telling everybody else what this phone is doing.
     *
     * Checked often and sent rarely: a change is worth a few seconds' delay at
     * most, and a track playing through unchanged is worth one message every
     * fifteen seconds. Pausing, resuming and skipping all count as changes.
     */
    private suspend fun publishNowPlaying(sync: SyncEngine) {
        var lastState = ""
        var lastSentAt = 0L
        var wasPlaying = false
        val gate = Mutex()

        suspend fun publish(heartbeat: Boolean) {
            gate.withLock {
                val hidden = privacy?.privateSession?.value == true
                val sessions = NowPlayingRepository.sessions.value
                val active = sessions.pickActive()?.takeIf { it.isTracked && !hidden }
                // A private session hides adverts too, for the same reason it
                // hides everything else: nothing about this phone travels.
                val advert = !hidden && sessions.advertPlaying()

                val state = "${active?.fingerprint.orEmpty()}|${active?.isPlaying == true}|$advert"
                val now = SystemClock.elapsedRealtime()
                val due = now - lastSentAt > NOW_PLAYING_HEARTBEAT_MS
                val changed = state != lastState
                if (!changed && !(heartbeat && due)) return@withLock

                when {
                    active != null -> {
                        sync.publishNowPlaying(active, active.positionAt(now))
                        wasPlaying = active.isPlaying
                    }
                    // An advert, said as an advert. Anybody in this room stays
                    // put and stays quiet rather than being sent away.
                    advert -> {
                        sync.publishAdvert()
                        wasPlaying = false
                    }
                    wasPlaying -> {
                        // Nothing at all is playing, and the last thing anybody
                        // heard from this phone said otherwise.
                        sync.publishStopped()
                        wasPlaying = false
                    }
                }
                lastState = state
                lastSentAt = now
            }
        }

        coroutineScope {
            // Written the moment the player says something changed, rather
            // than up to three seconds after the fact. Everybody following is
            // waiting on this write, and the second half of the delay they
            // used to feel was here.
            launch {
                NowPlayingRepository.sessions.collect { publish(heartbeat = false) }
            }
            // A track playing through unchanged still has to say so
            // occasionally, or it goes stale and friends stop seeing it.
            launch {
                while (true) {
                    delay(NOW_PLAYING_HEARTBEAT_MS)
                    publish(heartbeat = true)
                }
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        differ.reset()
        runCatching { appContext.let { Notifier.clearTracking(it) } }
    }

    private var privacy: PrivacyState? = null
    private lateinit var appContext: Context

    private suspend fun record(db: MuseroomDatabase, prefs: android.content.SharedPreferences) {
        mutex.withLock {
            // Private mode is handled here, at the point of capture. Treating it
            // as "nothing is playing" also closes the open session cleanly rather
            // than leaving a track hanging mid-count.
            val hidden = privacy?.privateSession?.value == true
            val active = NowPlayingRepository.sessions.value.pickActive()
                ?.takeIf { it.isTracked && !hidden }
            val events = differ.diff(active, System.currentTimeMillis(), SystemClock.elapsedRealtime())
            if (events.isEmpty()) return@withLock
            events.forEach { db.dao().insertEvent(it.toEntity()) }
            creditClosedSessions(db, prefs)

            // A track change should reach friends now, not up to fifteen seconds
            // later. The periodic publish only exists to keep the position fresh.
            if (events.any { it.type == PlayEventType.TRACK_CHANGE || it.type == PlayEventType.PLAY }) {
                active?.let {
                    SyncEngine.get(appContext).publishNowPlaying(
                        it, it.positionAt(SystemClock.elapsedRealtime()),
                    )
                }
            }
        }
    }

    /**
     * Credits everything up to the most recent point where a track ended. The
     * track still playing is left alone; its minutes are counted when it finishes,
     * so a session is never credited twice.
     */
    private suspend fun creditClosedSessions(
        db: MuseroomDatabase,
        prefs: android.content.SharedPreferences,
    ) {
        val watermark = prefs.getLong(KEY_WATERMARK, 0L)
        val pending = db.dao().eventsAfter(watermark).map { it.toDomain() }
        if (pending.size < 2) return

        val terminal = pending.indexOfLast {
            it.type == PlayEventType.TRACK_CHANGE || it.type == PlayEventType.STOP
        }
        if (terminal <= 0) return

        // The terminal event is included so the final span is credited, then left
        // unconsumed so it can open the next session on the following pass.
        val closed = pending.subList(0, terminal + 1)
        val sessions = Crediting.sessions(closed)
        if (sessions.isNotEmpty()) db.dao().insertSessions(sessions)
        prefs.edit().putLong(KEY_WATERMARK, pending[terminal - 1].id).apply()
    }
}
