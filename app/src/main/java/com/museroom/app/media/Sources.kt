package com.museroom.app.media

/**
 * The players Museroom counts. Nothing else on the device is recorded.
 *
 * A fixed list rather than a question the user answers. Asking per app puts the
 * burden of a privacy decision on someone in the middle of doing something else,
 * and a wrong tap is not recoverable in the way a wrong setting is: the thing is
 * already written down. So the list is music players only, and it grows when a
 * player is added here deliberately rather than when one happens to play audio.
 *
 * Browsers are absent on purpose. A browser plays whatever the web plays, so
 * supporting one means recording anything the person happens to open, and there
 * is no way to consent to that in advance.
 */
object Sources {

    private val SUPPORTED = linkedMapOf(
        "com.spotify.music" to "Spotify",
        "com.google.android.apps.youtube.music" to "YouTube Music",
        // The ReVanced and RVX builds are the same app under their own package.
        "app.revanced.android.apps.youtube.music" to "YouTube Music",
        "app.rvx.android.apps.youtube.music" to "YouTube Music",
        "com.apple.android.music" to "Apple Music",
        "com.amazon.mp3" to "Amazon Music",
        "deezer.android.app" to "Deezer",
        "com.soundcloud.android" to "SoundCloud",
        "com.aspiro.tidal" to "Tidal",
        "com.pandora.android" to "Pandora",
        "com.jio.media.jiobeats" to "JioSaavn",
        "com.bsbportal.music" to "Wynk Music",
        "com.gaana" to "Gaana",
        "com.anghami" to "Anghami",
        "tv.yandex.music" to "Yandex Music",
        "com.kakao.music" to "Kakao Music",
        "com.tencent.ibg.joox" to "JOOX",
        "com.melodis.midomiMusicIdentifier.freemium" to "SoundHound",
        "com.napster.android" to "Napster",
        "com.qobuz.music" to "Qobuz",
        "com.bandcamp.android" to "Bandcamp",
        "com.audiomack" to "Audiomack",
    )

    val packages: Set<String> get() = SUPPORTED.keys

    /** Every app counted, named once each, for showing somebody the list. */
    val labels: List<String> get() = SUPPORTED.values.distinct()

    fun isSupported(packageName: String): Boolean = packageName in SUPPORTED

    /** The player's name, for a supported package. */
    fun label(packageName: String): String =
        SUPPORTED[packageName]
            ?: packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
}
