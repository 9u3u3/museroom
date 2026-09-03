package com.museroom.app.sync

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.museroom.app.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Museroom's own music player.
 *
 * A joiner does not open YouTube Music and does not see it. Museroom holds the
 * YouTube Music web player in a WebView the size of one pixel and drives it
 * from here, so following a friend is Museroom playing music, with Museroom's
 * artwork and Museroom's controls, and nothing to tap through.
 *
 * The WebView belongs to the application rather than to a screen, because
 * music that stops when you change tabs is not music. It is attached to
 * whichever activity is up so that the system keeps rendering it; audio is the
 * only output that matters, and a single pixel is enough to earn it.
 */
@SuppressLint("StaticFieldLeak")
object RoomPlayer {

    /** Player states, as YouTube numbers them. */
    private const val PLAYING = 1
    private const val BUFFERING = 3

    private const val HOME = "https://music.youtube.com/"

    /** YouTube Music's "songs only" search filter, so we never match a video. */
    private const val SONGS_ONLY = "EgWKAQIIAWoKEAkQBRAKEAMQBA=="

    data class Snapshot(
        val ready: Boolean = false,
        val videoId: String = "",
        val wanted: String = "",
        val title: String = "",
        val author: String = "",
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val state: Int = -1,
        val ad: Boolean = false,
        /** What the player is doing, in one line, for when it is doing nothing. */
        val detail: String = "",
        /** When this was taken, so a stale reading is recognisable as one. */
        val takenAt: Long = 0,
    ) {
        val playing: Boolean get() = state == PLAYING
        val buffering: Boolean get() = state == BUFFERING
        val onWantedTrack: Boolean get() = wanted.isNotBlank() && videoId == wanted
    }

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var booted = false
    private var appContext: Context? = null

    private val pending = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val tokens = AtomicLong(0)

    /** Whether the page has ever finished loading in this process. */
    val started: Boolean get() = booted

    /** The application context, once anything has handed us one. */
    val context: Context? get() = appContext

    // ---- lifecycle -------------------------------------------------------

    /**
     * Gives the player a window to live in. Safe to call repeatedly; the
     * WebView itself is created once and survives the activity being recreated,
     * because a rotation is not a reason for the music to stop.
     *
     * Full size, and underneath everything. It was one pixel to begin with,
     * which is tidier and does not work: a video player given a viewport that
     * small can decline to start, and the failure is silence rather than an
     * error. Museroom's own screen is opaque and sits on top, so the page is
     * laid out properly and still never seen.
     */
    fun attach(activity: Activity) = onMain {
        appContext = activity.applicationContext
        val view = create(activity.applicationContext)
        (view.parent as? ViewGroup)?.removeView(view)
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@onMain
        content.addView(
            view,
            0,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    fun detach() = onMain {
        val view = web ?: return@onMain
        (view.parent as? ViewGroup)?.removeView(view)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(context: Context): WebView {
        web?.let { return it }
        val view = WebView(context)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The listener's tap on "listen with them" is the gesture. Asking
            // for another one inside a WebView they cannot see is asking for
            // silence.
            mediaPlaybackRequiresUserGesture = false
            // Google refuses to sign people in to a browser that announces
            // itself as embedded, and the marker for that is "; wv".
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.addJavascriptInterface(Bridge(), "MuseroomBridge")
        if (BuildConfig.DEBUG) {
            // The page is invisible, so without this a failure inside it is a
            // silence rather than a message.
            view.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.d("RoomPlayer", "${message.message()} @${message.lineNumber()}")
                    return true
                }
            }
        }
        blockAds(view)
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (!documentStartSupported) asset(v, "adblock.js")
            }

            override fun onPageFinished(v: WebView, url: String) {
                booted = true
                asset(v, "room.js")
            }
        }
        web = view
        return view
    }

    /**
     * Ad breaks, dealt with before the page can arrange one.
     *
     * The listener is the only one who would hear it. The host never pauses,
     * so an ad here is a stretch of time with nothing to stay in step with,
     * and the room is broken for as long as it runs. It has to be the page's
     * own scripts that never see the ad slots, which means running first, and
     * a document-start script is the only hook that reliably does.
     */
    private fun blockAds(view: WebView) {
        if (!documentStartSupported) return
        runCatching {
            val script = read(view.context, "adblock.js") ?: return
            WebViewCompat.addDocumentStartJavaScript(
                view,
                script,
                setOf("https://music.youtube.com", "https://www.youtube.com"),
            )
        }
    }

    private val documentStartSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    private fun asset(view: WebView, name: String) {
        val script = read(view.context, name) ?: return
        view.evaluateJavascript(script, null)
    }

    private fun read(context: Context, name: String): String? = runCatching {
        context.assets.open(name).bufferedReader().use { it.readText() }
    }.getOrNull()

    // ---- playback --------------------------------------------------------

    /**
     * Play a track from a given moment.
     *
     * Two routes, and which one runs matters. Once the page is up, handing the
     * player an id moves it without a page load, which is fast enough to keep
     * a track change feeling instant. Before that there is nothing to hand an
     * id to, so the first track arrives as a navigation.
     */
    fun load(videoId: String, startMs: Long) = onMain {
        val view = web ?: return@onMain
        val startSeconds = (startMs.coerceAtLeast(0L) / 1000.0)
        if (!booted) {
            view.loadUrl(watchUrl(videoId, startSeconds))
            return@onMain
        }
        view.evaluateJavascript(
            "window.__museroom && window.__museroom.load(${videoId.quoted()}, $startSeconds)",
        ) { result ->
            // A false here means the page is loaded but the player is not the
            // object we expect any more. Falling back to a navigation is slow
            // and correct, which beats fast and silent.
            if (result == "false") {
                view.loadUrl(watchUrl(videoId, startSeconds))
            }
        }
    }

    fun seekTo(positionMs: Long) = js("window.__museroom.seek(${positionMs / 1000.0})")

    fun play() = js("window.__museroom.play()")

    fun pause() = js("window.__museroom.pause()")

    /** Stop following: silence the player and forget what it was aiming at. */
    fun leave() {
        js("window.__museroom.leave()")
        _snapshot.value = Snapshot(ready = _snapshot.value.ready)
    }

    /** Remembers an application context, so the player can be woken later. */
    fun prime(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun watchUrl(videoId: String, startSeconds: Double): String =
        "${HOME}watch?v=$videoId&t=${startSeconds.toInt()}"

    /**
     * Opens the page without playing anything.
     *
     * Worth doing the moment somebody asks to listen along rather than when
     * they are let in, because a cold page costs several seconds and those
     * seconds would otherwise be spent with the host already singing.
     */
    fun warmUp() = onMain {
        val context = appContext ?: return@onMain
        val view = create(context)
        if (!booted && view.url == null) view.loadUrl(HOME)
    }

    // ---- resolving a title ----------------------------------------------

    /**
     * A video id for this song, found by the page's own search.
     *
     * This is the piece that makes following work without an API key: the
     * search runs inside a signed-in YouTube Music, so it returns the song
     * rather than a cover of it, and costs nothing.
     */
    suspend fun search(title: String, artist: String): String? {
        if (!booted) return null
        val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return null

        val token = "t${tokens.incrementAndGet()}"
        val waiting = CompletableDeferred<String?>()
        pending[token] = waiting
        js(
            "window.__museroom.search(${token.quoted()}, ${query.quoted()}, ${SONGS_ONLY.quoted()})",
        )
        val found = withTimeoutOrNull(12_000) { waiting.await() }
        pending.remove(token)
        return found?.takeIf { it.isNotBlank() }
    }

    /**
     * Runs an expression in the page and hands back what it evaluated to.
     *
     * Exists for the tests. Nothing about a listening room is a documented
     * interface, so the only way to know the page still behaves is to ask it.
     */
    suspend fun evaluate(expression: String): String? {
        if (!booted) return null
        val answer = CompletableDeferred<String?>()
        onMain {
            val view = web
            if (view == null) answer.complete(null)
            else view.evaluateJavascript(expression) { answer.complete(it) }
        }
        return withTimeoutOrNull(10_000) { answer.await() }
    }

    // ---- plumbing --------------------------------------------------------

    private class Bridge {
        @JavascriptInterface
        fun state(json: String) {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return
            _snapshot.value = Snapshot(
                ready = o.optBoolean("ready"),
                videoId = o.optString("videoId"),
                wanted = o.optString("wanted"),
                title = o.optString("title"),
                author = o.optString("author"),
                positionMs = o.optLong("positionMs"),
                durationMs = o.optLong("durationMs"),
                state = o.optInt("state", -1),
                ad = o.optBoolean("ad"),
                detail = o.optString("detail"),
                takenAt = SystemClock.elapsedRealtime(),
            )
        }

        @JavascriptInterface
        fun resolved(token: String, videoId: String) {
            pending.remove(token)?.complete(videoId.takeIf { it.isNotBlank() })
        }
    }

    private fun js(expression: String) = onMain {
        val view = web ?: return@onMain
        if (!booted) return@onMain
        view.evaluateJavascript("window.__museroom && $expression", null)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}

/** JSON string quoting, so a song called `O'Neil "live"` cannot break a call. */
private fun String.quoted(): String = JSONObject.quote(this)
