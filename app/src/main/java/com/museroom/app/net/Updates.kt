package com.museroom.app.net

import android.content.Context
import com.museroom.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class Release(
    @SerialName("version_code") val versionCode: Int = 0,
    @SerialName("version_name") val versionName: String = "",
    val url: String = "",
    val notes: String = "",
)

/**
 * Telling people a newer build exists.
 *
 * Museroom is not on the Play Store, deliberately, and the cost of that is
 * that nothing updates itself and nobody is told. Somebody who installed once
 * would sit on that build for ever, which for an app where two phones have to
 * agree about a protocol is worse than an occasional nag.
 *
 * So the site publishes one small file saying what the newest build is, and
 * this reads it. Nothing is downloaded and nothing is installed: it opens the
 * page in the browser and the person decides, exactly as they did the first
 * time. A version somebody has said no to stays said no to.
 */
object Updates {

    private const val MANIFEST = "https://9u3u3.github.io/museroom/version.json"
    private const val PREFS = "museroom.updates"
    private const val KEY_SKIPPED = "skipped_version_code"

    /** Once a day is often enough for something people install by hand. */
    private const val EVERY_MS = 24 * 60 * 60 * 1000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _available = MutableStateFlow<Release?>(null)

    /** Null unless there is a newer build this phone has not turned down. */
    val available: StateFlow<Release?> = _available.asStateFlow()

    private var lastChecked = 0L

    /**
     * [force] is the settings button, which should answer even when nothing
     * has changed since the last look.
     */
    suspend fun check(context: Context, force: Boolean = false): Result<Release?> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (!force && now - lastChecked < EVERY_MS) {
                return@withContext Result.success(_available.value)
            }
            runCatching {
                lastChecked = now
                val body = http.newCall(Request.Builder().url(MANIFEST).build()).execute()
                    .use { response ->
                        if (!response.isSuccessful) error("The site said ${response.code}.")
                        response.body?.string().orEmpty()
                    }
                val release = Supabase.json.decodeFromString(Release.serializer(), body)
                val prefs = context.applicationContext
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val skipped = prefs.getInt(KEY_SKIPPED, 0)

                _available.value =
                    if (shouldOffer(release, BuildConfig.VERSION_CODE, skipped, force)) {
                        release
                    } else {
                        null
                    }
                _available.value
            }
        }

    /**
     * Whether a build is worth putting in front of somebody.
     *
     * Separated out because it is the one part of this nobody can watch
     * happen: a prompt that never appears and a prompt that appears for a
     * build you already have look identical from the outside until the day
     * one of them is wrong.
     *
     * [force] is the settings button, which reaches past a version somebody
     * turned down, because they just asked.
     */
    internal fun shouldOffer(
        release: Release,
        installed: Int,
        skipped: Int,
        force: Boolean,
    ): Boolean {
        // Anything but https is somebody else's link, whatever the file says.
        if (!release.url.startsWith("https://")) return false
        if (release.versionCode <= installed) return false
        return force || release.versionCode > skipped
    }

    /** Not this one. A later one will ask again. */
    fun skip(context: Context, release: Release) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SKIPPED, release.versionCode)
            .apply()
        _available.value = null
    }
}
