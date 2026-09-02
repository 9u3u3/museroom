package com.museroom.app.media

/**
 * The players Museroom counts. Nothing else on the device is recorded.
 *
 * A fixed list rather than a question the user answers. Asking per app puts the
 * burden of a privacy decision on someone in the middle of doing something else,
 * and a wrong tap is not recoverable in the way a wrong setting is: the thing is
 * already written down. So the list is short, it is music players only, and it
 * grows when a player is added here deliberately.
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
    )

    val packages: Set<String> get() = SUPPORTED.keys

    fun isSupported(packageName: String): Boolean = packageName in SUPPORTED

    /** The player's name, for a supported package. */
    fun label(packageName: String): String =
        SUPPORTED[packageName]
            ?: packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
}
