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
    val sessions: StateFlow<List<NowPlaying>> = _sessions.asStateFlow()

    /** Wall-clock time of the last update we received. Powers the self-check. */
    private val _lastEventAt = MutableStateFlow(0L)
    val lastEventAt: StateFlow<Long> = _lastEventAt.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

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
        _sessions.value = emptyList()
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
        _sessions.value = bound.mapNotNull { (controller, _) -> controller.toNowPlaying() }
        _lastEventAt.value = System.currentTimeMillis()
    }
}

/**
 * Which session the user actually means. Something playing always wins; between
 * two playing sessions, the one that reported most recently does.
 */
fun List<NowPlaying>.pickActive(): NowPlaying? =
    filter { it.isTracked }.pick() ?: pick()

private fun List<NowPlaying>.pick(): NowPlaying? =
    filter { it.isPlaying }.maxByOrNull { it.reportedAtElapsed }
        ?: maxByOrNull { it.reportedAtElapsed }
