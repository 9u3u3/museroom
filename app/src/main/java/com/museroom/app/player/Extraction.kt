package com.museroom.app.player

import android.content.Context
import android.util.Log
import com.metrolist.innertubex.InnerTube as InnerTubeX
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality as XQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.museroom.app.player.potoken.PoTokenGenerator
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Getting a stream address that keeps serving.
 *
 * Museroom asked YouTube for plain URLs first, because doing so needed nothing
 * but the HTTP client the app already had. That worked and then stopped working
 * in a way worth writing down: the URLs still arrive, they still play, and they
 * stop dead one megabyte in, which is about a minute of music. YouTube has moved
 * these clients to its own streaming protocol and the plain address is a leftover
 * that nobody is expected to read to the end.
 *
 * Getting past that means proving we are not a robot, and the proof is Google's
 * own obfuscated JavaScript, which expects a browser to run in. So there is a
 * WebView again — but a completely different one from the player this replaced.
 * That one held the whole of YouTube Music for the length of every song. This
 * one is offscreen, mints a token, and goes away, and if it fails the extraction
 * falls through to clients that do not need one.
 *
 * The protocol work is InnerTubeX's, deliberately. Signature descrambling and
 * the token dance are the parts that break when YouTube changes something, and
 * they are worth having somebody else maintain.
 */
object Extraction {

    private const val TAG = "MuseroomPlayer"

    /**
     * Where the descrambler's recipe comes from.
     *
     * The transform that unscrambles a stream signature lives in YouTube's own
     * player script and changes without notice, so it is fetched rather than
     * compiled in, and cached so that a phone with no network still has the last
     * one that worked.
     */
    private class ConfigStore(context: Context) : PlayerConfigRepository {
        private val prefs = context.getSharedPreferences("player_config", Context.MODE_PRIVATE)

        override val enabled = true
        override val sourceUrl = CONFIG_URL
        override val defaultSourceUrl = CONFIG_URL
        override var cachedJson: String
            get() = prefs.getString("json", "").orEmpty()
            set(value) { prefs.edit().putString("json", value).apply() }
        override var cachedAtMs: Long
            get() = prefs.getLong("cached_at", 0L)
            set(value) { prefs.edit().putLong("cached_at", value).apply() }
        override var cachedSourceUrl: String
            get() = prefs.getString("source", "").orEmpty()
            set(value) { prefs.edit().putString("source", value).apply() }
        override var cachedEtag: String
            get() = prefs.getString("etag", "").orEmpty()
            set(value) { prefs.edit().putString("etag", value).apply() }

        private companion object {
            const val CONFIG_URL =
                "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/" +
                    "library/src/main/assets/player_configs.json"
        }
    }

    @Volatile
    private var app: Context? = null

    private val lock = Mutex()
    private var extractor: InnerTubeExtractor? = null

    fun attach(context: Context) {
        if (app == null) app = context.applicationContext
    }

    private val http: HttpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 30_000
                socketTimeoutMillis = 30_000
            }
        }
    }

    private val tokens: PoTokenGenerator by lazy {
        PoTokenGenerator(requireNotNull(app) { "Extraction.attach was never called" })
    }

    private val provider = object : TokenProvider {
        override val capabilities = TokenProviderCapabilities(
            providers = setOf(PoTokenProviderKind.WEB_BOTGUARD),
            usesWebView = true,
        )

        override suspend fun getPoToken(
            videoId: String,
            visitorData: String,
            cookie: String?,
        ): PoTokenResult? = tokens.getWebClientPoToken(videoId, visitorData)?.let {
            PoTokenResult(
                playerRequestToken = it.playerRequestPoToken,
                streamingDataToken = it.streamingDataPoToken,
                visitorData = visitorData,
            )
        }

        override suspend fun close() = tokens.close()
    }

    private val logger = InnerTubeLogger { event ->
        val message = event.message + event.details.entries
            .joinToString(prefix = " [", postfix = "]") { "${it.key}=${it.value}" }
            .takeIf { event.details.isNotEmpty() }.orEmpty()
        when (event.level) {
            InnerTubeLogLevel.DEBUG -> Log.d(TAG, message)
            InnerTubeLogLevel.INFO -> Log.i(TAG, message)
            InnerTubeLogLevel.WARN -> Log.w(TAG, message)
            InnerTubeLogLevel.ERROR -> Log.e(TAG, message)
        }
    }

    private suspend fun extractor(): InnerTubeExtractor {
        extractor?.let { return it }
        return lock.withLock {
            extractor?.let { return@withLock it }
            val context = requireNotNull(app) { "Extraction.attach was never called" }
            val inner = InnerTubeX(http)
            val store = RemotePlayerConfigStore(http, ConfigStore(context), logger)
            InnerTubeExtractor(
                configParser = YtConfigParserImpl(http, inner, store, logger),
                cipherService = YouTubeCipherService(http, store, logger),
                innerTube = inner,
                tokenProvider = provider,
                logger = logger,
            ).also { extractor = it }
        }
    }

    /**
     * An address for this recording, or null if nothing playable came back.
     *
     * [avoid] names the clients that have just failed for this exact track,
     * which the player fills in when a stream it was given stopped serving.
     * Without it a recording that one client is wrong about fails the same way
     * every time it is asked for.
     */
    suspend fun extract(
        videoId: String,
        quality: Streams.Quality,
        avoid: Set<String> = emptySet(),
    ): ExtractedStream {
        val stream = requireNotNull(
            extractor().extract(
                videoId = videoId,
                hints = ContentHints().withStreamCapabilities(
                    allowHls = false,
                    // Not supported by this engine, and asking for it would mean
                    // being handed something we cannot play.
                    allowSabr = false,
                    allowBoundedRange = true,
                ),
                excludedClients = avoid,
                audioQuality = when (quality) {
                    Streams.Quality.Low -> XQuality.LOW
                    Streams.Quality.High -> XQuality.AUTO
                    Streams.Quality.Max -> XQuality.HIGH
                },
                clientPlaybackNonce = generateClientPlaybackNonce(),
            ),
        ) { "no playable stream for $videoId" }
        check(stream.sabrBootstrap == null) { "this engine cannot play a SABR stream" }
        return stream
    }

    /** Lets the token machinery go. Called when nothing is playing any more. */
    suspend fun release() {
        try {
            provider.close()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "token generator would not close: ${error.message}")
        }
    }
}
