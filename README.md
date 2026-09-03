# Museroom

**See what your friends are listening to, join what they're playing, and count
the minutes.** An Android app, distributed from
[its own page](https://9u3u3.github.io/museroom/) rather than the Play Store.

<p align="center">
  <a href="https://9u3u3.github.io/museroom/museroom-latest.apk"><b>Download the APK</b></a>
  &nbsp;·&nbsp;
  <a href="https://9u3u3.github.io/museroom/">Website</a>
  &nbsp;·&nbsp;
  <a href="https://9u3u3.github.io/museroom/privacy.html">Privacy</a>
</p>

---

## What it does

- **Reads what's playing.** Title, artist, artwork and a position that ticks in
  real time, from whichever music app is playing.
- **Shows it to friends.** Add somebody by username and see their track live.
  You choose who sees yours: everyone, friends only, or nobody.
- **Listening rooms.** Ask to join what a friend is playing and their song
  starts on your phone. When they skip, you skip. Nothing to press, no other
  app to open, and no ad breaks.
- **People nearby.** Bluetooth Low Energy with a rotating token finds other
  listeners in the room around you. No location, ever.
- **A leaderboard.** Minutes and tracks, by day, week, month or all time,
  counted server-side so nobody can inflate them.

## How the interesting parts work

**Detection.** Neither Spotify nor YouTube Music will tell an app what is
playing. Android will: every media app publishes a `MediaSession` so the lock
screen and Bluetooth buttons work, and an app holding notification access can
read the active ones. Position is never polled — the player reports where it
was and when, and the rest is arithmetic, so a snapshot stays correct however
long it sits in a queue.

**Rooms.** No audio crosses between phones. Museroom plays the host's track
itself, through YouTube Music's web player in a browser view it keeps hidden,
matched by name and held in step by nudging the position. Ad slots are stripped
out of the player data before it loads, the same way the desktop clients do it,
because an ad break only ever happens to one person in a room and there is
nothing to stay in step with while it runs.

**Nearby.** Your phone broadcasts a short code that changes every fifteen
minutes and means nothing on its own. Only Museroom can match a code to a
person, and only while both people have it switched on. Android is told that
scan results are never used to work out where you are — which is true, and why
it never asks for location on Android 12 and up.

**Minutes.** A track counts when it finishes, not when it starts, and the
totals are worked out on a server rather than taken on trust from a phone.
Four separate clamps keep a paused player, a seek loop or a wrong clock from
inventing time that was never listened to.

## What it reads, and what it doesn't

A fixed list of music apps — Spotify, YouTube Music (ReVanced and RVX builds
included), Apple Music, Amazon Music, SoundCloud, Deezer, Tidal, Pandora,
JioSaavn, Wynk, Gaana and a handful more. Every other app on the device is
ignored, and the list only grows when a music app is added to it deliberately.

Browsers, video apps and podcast apps stay off it on purpose: a browser plays
whatever the web plays, so counting one would mean recording anything you
happen to open. Notification *contents* are never read — only the media
session. Nothing leaves the phone until you sign in, and private session stops
the recording itself rather than just the display.

The full [privacy policy](https://9u3u3.github.io/museroom/privacy.html) names
every app on the list and every piece of data that moves.

## Installing

Museroom isn't on the Play Store, so Android puts two warnings in the way.
Neither means anything is wrong, and the
[install guide](https://9u3u3.github.io/museroom/) explains both:

- **"Unsafe app blocked"** is Play Protect saying it doesn't recognise the app,
  which it says about everything not from the Play Store. Nothing in the app
  can turn it off.
- **"Restricted setting"** is Android greying out notification access for
  sideloaded apps. It's a once-only fix — and it survives updates, so install
  over the top rather than uninstalling.

## Building

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell cmd notification allow_listener \
  com.museroom.app/com.museroom.app.listener.MediaListenerService
```

Credentials live in `.env.local` at the repo root, which is gitignored and read
into `BuildConfig` at build time:

```properties
SUPABASE_URL=...
SUPABASE_ANON_KEY=...
GOOGLE_WEB_CLIENT_ID=...
# Optional. Track lookup falls back to the in-app player's own search without it.
YOUTUBE_API_KEY=
```

Tests:

```bash
./gradlew :app:testDebugUnitTest          # crediting, fingerprints, the allowlist
./gradlew :app:connectedDebugAndroidTest  # needs a device and the network
```

The instrumented tests are the ones that matter most. Nothing about the room
player is a documented interface, so they run against the real page and assert
that the script reaches it, that its search names a recording, and that the
player starts where it was told to rather than at the beginning.

## Layout

| Path | What lives there |
| --- | --- |
| `media/` | Reading media sessions, fingerprints, artwork, track resolution |
| `tracking/` | Turning playback into events, and events into credited sessions |
| `credit/` | The rules that decide how much of a play counts |
| `sync/` | Uploads, listening rooms, the hidden player, room presence |
| `net/` | Supabase: auth, profiles, friends, the board, proximity |
| `proximity/` | Bluetooth Low Energy advertising and scanning |
| `notify/` | The three notification channels and their actions |
| `ui/` | Compose screens and the comic/neobrutalist kit |
| `supabase/migrations/` | The schema, row-level security, and the board rollups |
| `docs/` | The website, served by GitHub Pages |

## Licence

Museroom is free software under the **GNU General Public License, version 3**.
The full text is in [LICENSE](LICENSE).

Anybody may use, study, change and share it, on the condition that anything
built on it stays free the same way. That is a deliberate choice for an app
that reads what you listen to: the only real assurance a thing does what it
claims is that anyone can check, and copyleft keeps that true of every version
anybody else ships.
