package com.museroom.app.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.museroom.app.listener.MediaListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single source of truth for what is playing on this device.
 *
 * Reading active sessions requires our NotificationListenerService component to
 * be enabled in Settings. The service does not have to be doing anything; the
 * system only checks that the component is an enabled listener.
 */
object NowPlayingRepository {

    private const val TAG = "Museroom"

    private val handler = Handler(Looper.getMainLooper())
    private val bound = mutableListOf<Pair<MediaController, MediaController.Callback>>()

    private var manager: MediaSessionManager? = null
    private var component: ComponentName? = null
    private var started = false

    private val _sessions = MutableStateFlow<List<NowPlaying>>(emptyList())

    /**
     * What this phone is playing, as far as the rest of Museroom is concerned.
     *
     * Not quite the same list as the one the system hands over. While Museroom
     * is the room's own player, everything else on the phone is deliberately
     * left out of it — see [setRoomOnly].
     */
    val sessions: StateFlow<List<NowPlaying>> = _sessions.asStateFlow()

    private val _heard = MutableStateFlow<List<NowPlaying>>(emptyList())

    /**
     * Every session on the phone, including any this is choosing to ignore.
     *
     * The honest list, for the two questions that need it: what was playing
     * just before Museroom took the room over, and whether the app it asked to
     * stop actually did.
     */
    val heard: StateFlow<List<NowPlaying>> = _heard.asStateFlow()

    /** Wall-clock time of the last update we received. Powers the self-check. */
    private val _lastEventAt = MutableStateFlow(0L)
    val lastEventAt: StateFlow<Long> = _lastEventAt.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * What Museroom is playing itself, while you are in somebody's room.
     *
     * A listening room comes out of a browser view Museroom owns, and a browser
     * view is not a media session anybody can read — so as far as everything
     * downstream was concerned, an hour spent in a friend's room was an hour of
     * silence. None of it counted. It is reported here instead, and from that
     * point on it is an ordinary session: the same crediting, the same minutes,
     * the same thing friends can see.
     */
    private val _room = MutableStateFlow<NowPlaying?>(null)

    /** Readable on its own, because the room's notification wants exactly this. */
    val room: StateFlow<NowPlaying?> = _room.asStateFlow()

    fun setRoomPlayback(track: NowPlaying?) {
        if (_room.value == track) return
        _room.value = track
        publish()
    }

    /**
     * Whether Museroom's own player is the only music on this phone that counts.
     *
     * Set while a together-mode room is running, and it is not a tidying-up.
     * Museroom asks the music app it is taking over from to stop and cannot
     * make it, so a phone hosting a together room may well still have Spotify
     * running in the background. Everything downstream reads one session and
     * calls it "what this phone is playing" — the minutes, the friends list,
     * and above all the row every listener steers by. If Spotify can win that
     * even for a moment, a room full of people is yanked onto a song their
     * host is not choosing, three seconds ahead of where any of them can be.
     *
     * So while the room owns the speaker, the room is the only answer. What
     * the other app is doing is still readable through [heard], which is how
     * the person is told it never stopped.
     */
    fun setRoomOnly(only: Boolean) {
        if (roomOnly == only) return
        roomOnly = only
        publish()
    }

    @Volatile
    private var roomOnly = false

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        rebind(controllers.orEmpty())
    }

    @Synchronized
    fun start(context: Context) {
        if (started) {
            resync()
            return
        }
        val app = context.applicationContext
        val msm = app.getSystemService(MediaSessionManager::class.java) ?: run {
            _error.value = "This device has no MediaSessionManager."
            return
        }
        val comp = ComponentName(app, MediaListenerService::class.java)
        try {
            msm.addOnActiveSessionsChangedListener(sessionsChanged, comp, handler)
            rebind(msm.getActiveSessions(comp))
            manager = msm
            component = comp
            started = true
            _error.value = null
        } catch (e: SecurityException) {
            // Expected until the user enables notification access.
            _error.value = "Notification access has not been granted yet."
            Log.d(TAG, "getActiveSessions denied", e)
        }
    }

    @Synchronized
    fun stop() {
        manager?.removeOnActiveSessionsChangedListener(sessionsChanged)
        unbindAll()
        manager = null
        component = null
        started = false
        _room.value = null
        roomOnly = false
        _sessions.value = emptyList()
        _heard.value = emptyList()
    }

    /**
     * Asks whatever is playing here to stop. Once, and without insisting.
     *
     * A third-party transport control on Android is a request, not a command:
     * Spotify usually honours it, YouTube Music often does not, and nothing
     * about the API says which you are talking to. So this is offered once, on
     * the way into a together-mode room where Museroom is about to become the
     * speaker, and if it is ignored the person is told on screen that two apps
     * will sound until they pause the other one themselves. Repeating it would
     * be a loop fighting an app that has already declined.
     */
    @Synchronized
    fun askToPause() {
        bound.forEach { (controller, _) ->
            runCatching {
                if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    controller.transportControls.pause()
                }
            }
        }
    }

    /** Re-reads every bound controller without rebuilding the bindings. Cheap. */
    fun resync() = publish()

    /** Rebuilds the controller list from scratch. Use when sessions come and go. */
    fun refresh() {
        val msm = manager ?: return
        val comp = component ?: return
        runCatching { msm.getActiveSessions(comp) }
            .onSuccess { rebind(it) }
            .onFailure { _error.value = it.message }
    }

    @Synchronized
    private fun rebind(controllers: List<MediaController>) {
        unbindAll()
        for (controller in controllers) {
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
                override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
                override fun onSessionDestroyed() {
                    handler.post { refresh() }
                }
            }
            runCatching { controller.registerCallback(callback, handler) }
                .onSuccess { bound += controller to callback }
        }
        publish()
    }

    private fun unbindAll() {
        bound.forEach { (controller, callback) ->
            runCatching { controller.unregisterCallback(callback) }
        }
        bound.clear()
    }

    private fun publish() {
        val live = bound.mapNotNull { (controller, _) -> controller.toNowPlaying() }
        val room = _room.value
        _heard.value = live + listOfNotNull(room)
        _sessions.value = if (roomOnly) listOfNotNull(room) else _heard.value
        _lastEventAt.value = System.currentTimeMillis()
    }
}

/**
 * Which session the user actually means.
 *
 * Only supported players are ever returned. An unsupported app is not merely
 * uncounted, it is not shown either: putting a video's title, artwork and a
 * running timer on screen is most of the exposure, whether or not the minutes
 * were recorded. The self-check still lists what was detected, by package name
 * alone, so nothing is hidden from the person who owns the phone.
 */
fun List<NowPlaying>.pickActive(): NowPlaying? {
    val supported = filter { it.isTracked && !it.isAdvert }
    // Museroom's own player wins outright whenever it is running, rather than
    // by being the most recent reading. In a together-mode room the host is a
    // client of their own room, and their music app may well still be playing
    // in the background because the request to pause it was only ever a
    // request. Deciding between the two on recency would mean whichever
    // happened to report last, so the room would be published as the room for
    // a few seconds and then as Spotify, and everybody following would be
    // yanked between two different songs. There is nothing to weigh here: if
    // Museroom is playing, that is what this phone is playing.
    supported.firstOrNull { it.isRoom }?.let { return it }
    return supported.filter { it.isPlaying }.maxByOrNull { it.reportedAtElapsed }
        ?: supported.maxByOrNull { it.reportedAtElapsed }
}

/**
 * Whether the sound coming out of this phone right now is an advert.
 *
 * Asked separately from [pickActive] because the two want opposite things.
 * Nothing should ever show or count an advert, so it is not an active session;
 * but a room needs to be told, so the fact has to be reachable.
 */
fun List<NowPlaying>.advertPlaying(): Boolean =
    any { it.isTracked && it.isAdvert && it.isPlaying }
