# Museroom

Read what is playing on an Android phone, show it to friends, count the minutes.

This repository currently contains **Phase 0**: the detection probe that decides
whether the rest of the product is buildable. It has no backend and no accounts.

## What Phase 0 proves

Neither Spotify nor YouTube Music will tell an app what is playing. Android will.
Every media app publishes a `MediaSession` so the lock screen and Bluetooth
buttons work, and an app whose `NotificationListenerService` is enabled can read
every active session on the device.

The probe reads that session and shows:

- title, artist, album and album artwork
- a position that ticks in real time, extrapolated rather than polled
- the track fingerprint used to decide that two plays are the same song
- a self-check panel and the raw metadata every player actually sends

## Build and run

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.museroom.app/.MainActivity
```

Then grant notification access in Settings, or from a shell:

```bash
adb shell cmd notification allow_listener \
  com.museroom.app/com.museroom.app.listener.MediaListenerService
```

Writing `settings put secure enabled_notification_listeners` looks like it works
but does not rebind the listener, and reading sessions still fails. Use the
`cmd notification` form above.

## Tests

```bash
./gradlew :app:testDebugUnitTest        # fingerprint and position arithmetic
./gradlew :app:connectedDebugAndroidTest # publishes a real MediaSession and reads it back
```

The instrumented test needs notification access granted on the target device
first, by the command above. It fails with that command in the message if not.

**Reinstalling the app revokes notification access.** Since both Gradle test tasks
reinstall, re-run the `allow_listener` command after any install, or the run fails
before it reaches an assertion.

## Layout

| Path | What lives there |
| --- | --- |
| `media/NowPlaying.kt` | The snapshot model and the position extrapolation |
| `media/Fingerprint.kt` | Normalising the strings each app sends into one identity |
| `media/SessionReader.kt` | `MediaController` to `NowPlaying`, defensively |
| `media/NowPlayingRepository.kt` | Binds sessions, publishes state |
| `listener/MediaListenerService.kt` | Holds the permission, filters to the music allowlist |
| `ui/NowPlayingScreen.kt` | The probe screen |

## The next thing that has to happen

Everything so far is verified against a synthetic session on an emulator. The
mechanism works. What is **not** yet verified is how Spotify and YouTube Music
each fill these fields in practice, and that is the whole reason Phase 0 exists.

Install this on a real handset with both apps, play a song in each, and read the
raw metadata panel. Expect YouTube Music to need work: artist arrives as
`Name - Topic`, and non-catalogue uploads carry a video thumbnail rather than an
album cover.

## Licence

Museroom is free software under the **GNU General Public License, version 3**.
The full text is in [LICENSE](LICENSE).

That means anybody may use, study, change and share it, on the condition that
anything built on it stays free in the same way. It is a deliberate choice for
an app that reads what you listen to: the only real assurance that a thing is
doing what it claims is that anyone can check, and copyleft keeps that true of
every version anybody else ships.
