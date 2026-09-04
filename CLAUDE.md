# Museroom — notes for whoever works on this next

Read this before touching anything. `README.md` explains the app to a user;
this explains the codebase to a machine that has to change it.

## What it is

An Android app (Kotlin, Jetpack Compose, single module `:app`) that reads what
music is playing on the phone, shows it to friends, and lets a friend **join
your room** so the same song plays on their phone in step with yours. It also
finds nearby listeners over Bluetooth Low Energy, records listening minutes,
and ranks people on a leaderboard by minutes and by likes.

Backend is **Supabase** (Postgres, PostgREST, GoTrue, Realtime). There is no
server code of our own — everything server-side is SQL in
`supabase/migrations/`.

Distribution is **GitHub Pages**, from `docs/`, at
`https://9u3u3.github.io/museroom/`. Deliberately **not** the Play Store: the
app strips ad slots out of the YouTube Music player response, which the Play
Store would not accept. That decision is made and is not up for re-litigation.

Licence is GPL-3.0.

## Ground rules

1. **This codebase is written in prose.** Comments are full sentences that say
   *why*, not *what*. They read like someone explaining a decision to a
   colleague. Look at `sync/FollowSession.kt`, `net/ServerClock.kt` or
   `media/Sources.kt` before writing a line, and match that register. Do not
   add `// increment counter` style comments; do not strip the existing ones.
2. **Commit messages are the same voice.** A short imperative title of about
   six words ("Give two phones one clock"), then paragraphs of prose explaining
   the problem and the reasoning. No Conventional Commits, no bullet-point
   changelogs. End with the `Co-Authored-By` / `Claude-Session` trailers.
3. **Never claim something was verified when it was not.** Signed-in flows
   cannot be exercised from this machine (see *Limits* below). Say "not
   reproduced" rather than "fixed" when that is the truth. There is precedent
   for this in `todo.md` item 25.
4. **`todo.md` is gitignored working notes.** Numbered issues, ticked as they
   are done. Keep using it for multi-item bug lists.

## Build, run, test

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell cmd notification allow_listener \
  com.museroom.app/com.museroom.app.listener.MediaListenerService

./gradlew :app:testDebugUnitTest          # ~96 JVM tests
node app/src/test/js/stray.mjs            # 22 checks over the page script's logic
./gradlew :app:connectedDebugAndroidTest  # 19 tests; needs a device and network
```

Instrumented tests can be narrowed with
`-Pandroid.testInstrumentationRunnerArguments.class=com.museroom.app.LeaveRoomTest`.
`--tests` is not supported for connected tests.

**Credentials** live in `.env.local` at the repo root (gitignored, template in
`.env.local.example`). `app/build.gradle.kts` reads it into `BuildConfig`:
`SUPABASE_URL`, `SUPABASE_ANON_KEY`, `GOOGLE_WEB_CLIENT_ID`, `YOUTUBE_API_KEY`,
plus the release signing passwords. `SUPABASE_DB_URL` is used by tooling, not
by the app.

**The emulator is fragile here.** The package service has gone missing
repeatedly (`Can't find service: package`, `DELETE_FAILED_INTERNAL_ERROR`).
The cure is `-wipe-data -no-snapshot` plus a readiness loop that waits for both
`sys.boot_completed` and `pm list packages` to answer. A wipe also clears
notification-listener access, which silently fails four instrumented tests
until it is granted again.

## Layout

| Path | What lives there |
| --- | --- |
| `media/` | Reading `MediaSession`s, fingerprints, artwork, track resolution |
| `tracking/` | Playback into events (`PlaybackTracker`, `PlaybackDiffer`) |
| `credit/` | The rules deciding how much of a play counts |
| `sync/` | Rooms: `FollowSession`, `RoomPlayer`, `RoomService`, `RoomPresence`, `SyncEngine` |
| `net/` | Supabase: auth, profiles, friends, board, likes, proximity, realtime, `ServerClock`, `Updates` |
| `proximity/` | BLE advertising and scanning |
| `notify/` | Notification channels and their actions |
| `ui/` | Compose screens plus the comic/neobrutalist kit |
| `app/src/main/assets/` | `room.js` and `adblock.js`, injected into the hidden WebView |
| `supabase/migrations/` | Schema, RLS, security-definer RPCs, leaderboard roll-ups |
| `design/` | Static HTML artboards for the design system (`node design/build.mjs`) |
| `docs/` | The website, the APK, and `version.json` |

Five tabs: Now, Friends, Nearby, Board, You (`ui/MuseroomApp.kt`). `PersonCard`
is drawn once above everything, because a name is tappable on five screens.

## The four subsystems

### 1. Detection — what is playing

Neither Spotify nor YouTube Music exposes an API. Android does: every media app
publishes a `MediaSession`, and an app holding notification access can read the
active ones through `MediaSessionManager.getActiveSessions()`. That access is
why `listener/MediaListenerService` exists — it does almost nothing, it exists
to hold the permission.

**Position is never polled.** The player reports where it was and when; the
rest is arithmetic (`reportedPosition + elapsed × speed`). A snapshot therefore
stays correct however long it sits in a queue. Do not add a polling loop.

`media/Sources.kt` is a **fixed allowlist** of music apps. Browsers, video and
podcast apps are excluded on purpose: a browser plays whatever the web plays,
so counting one means recording anything the person opens. Adding an app is a
deliberate edit here, never a user-facing question.

### 2. Crediting and the leaderboard

Events go to `play_events`, and a track counts **when it finishes, not when it
starts**. Four clamps stop a paused player, a seek loop or a wrong clock from
inventing time. The track *count* additionally needs **more than 30%** of the
song to have been heard (`counts_as_a_track()` in SQL, mirrored by
`credit/Crediting.kt` for offline history).

The phone computes history so it works with no network, **but the server's
answer wins**. A leaderboard that trusts a client-computed number ranks
whoever edited their client. `refresh_leaderboards()` recomputes
`leaderboard_entries` from the events.

### 3. Rooms — the hard part, and where most bugs live

No audio crosses between phones. The host writes their position into
`now_playing`; the joiner's Museroom plays **its own copy** of the same
recording out of a hidden full-size `WebView` running `music.youtube.com`,
driven by `evaluateJavascript` against `#movie_player`.

Pieces:

- **`sync/RoomPlayer.kt`** — owns the WebView. `search`, `load`, `seekTo`,
  `play`, `setRate`, and a `Snapshot` flow. `wantedId` survives page
  navigation and is re-stated in `onPageFinished`.
- **`app/src/main/assets/room.js`** — the page's hand on the player. Caches
  the player, the `<video>` element and the ad store rather than
  re-querying a page the size of YouTube Music several times a second.
  Reports on `timeupdate`/`play`/`pause`/`seeked`/`ended`/`ratechange`, with
  a one-second interval only as a net.
- **`app/src/main/assets/adblock.js`** — registered with
  `WebViewCompat.addDocumentStartJavaScript`, so it runs **before** the page's
  own scripts. Deletes `playerAds`, `adPlacements` and `adSlots` from both the
  network-parsed response and the baked-in one. An ad break only ever happens
  to one person in a room, so it does not merely annoy, it breaks the feature.
- **`sync/FollowSession.kt`** — the loop that holds the joiner in step.
- **`sync/RoomService.kt`** — a foreground service (`mediaPlayback`) with a
  platform `MediaSession` and a `MediaStyle` notification.
- **`net/Realtime.kt`** — a hand-written Phoenix-over-WebSocket client so a
  host's skip is *pushed*, not discovered two seconds later by polling.

Invariants that were each learned from a real bug. Do not undo them:

- **A room runs a deliberate three seconds behind the host** (`ROOM_LAG_MS` in
  `sync/FollowSession.kt`). Every comparison in the follow loop is against
  `targetPosition`, not `hostPosition`. This is not a compromise, it is the
  design: a listener is told a track exists at the instant it begins and cannot
  have fetched it yet, so aiming to be level means either starting late and
  skipping the difference or being hauled forward later. The delay turns that
  gap into a budget spent before the song starts.
- **Nobody ever loses a second of music. This is the top priority, stated by
  the user.** Two rules enforce it. A track that began while we were in the
  room starts at position 0, however far ahead the host is by then; only a
  track already playing when we walked in is joined partway. And a track begun
  at its start is never seeked forward, even to get in step, because that skips
  seconds nobody has heard. Falling further behind is free; the gap is closed by
  playback speed, which is inaudible.
- **The previous track's tail is protected.** When the host changes song, the
  listener still has the delay's worth of the old one to play. Loading the new
  one immediately would cut it off, so the switch waits until the old track
  finishes or the host passes the lag.
- **An earlier design held the host's own player at a track change** so every
  device could start on an agreed moment. It was built, shipped as 5.3/5.4, and
  removed: the transport control was unreliable and the gap it cost the host was
  not worth it. `now_playing.starts_at`, `start_position_ms`, `room_ready_for`
  and `room_late_ms` are the leftover columns, still present and now unused, and
  `room_members` still returns two of them. Do not build on them without asking.
- **Every listener begins a new song on the same computed moment.**
  `roomStartMoment` derives it from the host's own row (position and when that
  was true give the instant the track began) plus the lag and a fetch
  allowance. Nothing is sent: every phone reading the same row in the same clock
  gets the same number, so nobody is late by however long a message took. A new
  track is fetched with `RoomPlayer.cue` (silent) and released with `begin`.
- **Never cut the tail of the previous song.** Fetching the next track stops the
  current one, so the switch waits until *our own player* has finished, judged
  by its position against its duration, not by where the host has got to.
- **The Realtime heartbeat must never be a bare thread.** It was, sleeping
  between beats, and closing the flow interrupted it; the InterruptedException
  unwound out of the thread's own body where nothing caught it, and an uncaught
  exception on any thread kills the app. That was the crash on leaving a room,
  and it needed a live socket, so only a signed-in user in a real room could
  ever see it. It is a coroutine in the flow's scope now.
- **One shared clock.** `net/ServerClock.kt` asks the database
  (`server_now()`), halves the round trip, and keeps the *tightest* sample
  rather than the newest. Both ends stamp and project in that time. Two
  Android phones are routinely most of a second apart; that disagreement used
  to be absorbed by a 2.5 s tolerance, and absorbing it is what put a floor
  under how closely a room could be held. Worse, a permanent skew becomes a
  permanent tempo correction, which is more audible than the skew.
- **Correct by speed, not only by seeking.** Above `TOLERANCE_MS` (400 ms)
  seek; below it, nudge playback rate up to ±5% with `preservesPitch`, aiming
  to close the gap over about five seconds. Seeks are rate limited
  (`MIN_CORRECTION_GAP_MS`), and during the cooldown the rate still leans.
- **Never play a song nobody chose.** When a track ends, the page hands itself
  back its own queue and starts whatever it fancies. `room.js` pauses anything
  whose id is not `wanted` and flags `strayed`. This is the check that has to
  be right; the ad-store flag is only ever used to *explain* a stall.
- **A paused host is not projected.** `hostPosition` refuses to extrapolate
  when the host is stopped, and `obeyImmediately` acts on the Realtime push
  for both pause and resume. Resuming seeks as well, which is free because the
  music is already stopped.
- **The joiner does not lose the opening of a song.** `pendingFirstSync` with
  a tighter `FIRST_SYNC_TOLERANCE_MS` catches the track up once, early.
- **The service pays its debt first.** A service started as a foreground
  service owes the system a notification within seconds of *every* start,
  including starts that came from the Leave and Like buttons. The `foreground`
  flag in `RoomService.onStartCommand` guarantees it. Button intents use
  `PendingIntent.getForegroundService` on O+.
- **The WebView is full size and underneath everything, not one pixel.** A
  video player given a viewport that small can decline to start, and the
  failure is silence rather than an error. Museroom's own screen is opaque and
  sits on top, so the page is laid out properly and still never seen. (The
  header comment in `room.js` still says one pixel; it is stale.)
- **Chromium suspends WebView media when the window is invisible.** Worked
  around by `AwakeWebView.onWindowVisibilityChanged`. Measured, not assumed.
- **Swiping out of recents ends the room.** The window the player lives in is
  destroyed and Chromium will not keep audio without one, so `onTaskRemoved`
  stops rather than leaving a notification over silence. This is a platform
  limit, not a bug to chase.
- **The joiner's notification has no transport controls.** Pause, skip and
  scrub belong to the host. Only Like and Leave.

### 4. Nearby

`resolve_nearby` excludes anybody whose `following_user` is set and fresh:
somebody in a room is not a room to join, and their phone reports the host's
track as its own playback so they otherwise look exactly like a host.
`NearbyScreen` also filters out the signed-in user, because some Android radios
report their own advertisement back as a sighting.

`proximity/` advertises a short token that rotates every fifteen minutes and
means nothing on its own; only `resolve_nearby()` can map it to a person, and
only while both people have it on. `neverForLocation` on `BLUETOOTH_SCAN` is
what keeps location out of it on Android 12+.

`BLUETOOTH_CONNECT` is declared **only** to raise the system's own "turn
Bluetooth on" dialog. From Android 12 that dialog does not decline without the
permission, it throws, which took the whole app down. The launch is also
wrapped with a Settings fallback.

## The database

Tables: `profiles`, `play_events`, `listening_sessions`, `daily_listening`,
`tracks`, `track_aliases`, `leaderboard_entries`, `friendships`, `now_playing`,
`listen_requests`, `proximity_beacons`, `likes`, `blocks`, `reports`.

Security posture, which matters more than the shapes:

- **RLS everywhere**, with security-definer functions for anything a client
  must not be able to write directly.
- **`likes` has no insert policy at all, on purpose.** Every like goes through
  `like_track()`, which decides for itself what the target was playing at that
  moment. A client that could write the row could like a track nobody played,
  as often as it liked. The button sends *who*, never *what*.
- **`public_profile()` deliberately does not expose `display_name`** — that is
  whatever Google handed over, which for most people is their real name.
  Handles only. If you change this function's OUT columns, Postgres requires
  `drop function` first; it will not alter them in place.
- **`room_members()`** is definer because a stranger's row is not ours to
  select. Reading the roster by plain select is what made people invisible in
  their own room. Visible to the host or anyone in the room, blocking enforced
  both ways, two-minute freshness.
- **PostgREST embed hints** are required where two foreign keys point at the
  same table, e.g. `profiles!likes_liker_fkey`.

Migrations are plain SQL, timestamp-named, applied against the hosted project.
`psql` is not installed on this machine; **`python3` with `pg8000`** and
`SUPABASE_DB_URL` from `.env.local` is the working route, and is also how
schema claims get verified out of band. The Supabase CLI is present at
`/usr/bin/supabase`.

## Releasing

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. `./gradlew :app:assembleRelease` (signs with `keystore/museroom-release.jks`,
   passwords from `.env.local`; the keystore directory is gitignored).
3. Copy the APK to `docs/museroom-latest.apk`.
4. Update `docs/version.json` — `version_code`, `version_name`, `url`, `notes`.
   The in-app updater (`net/Updates.kt`) polls this once a day, shows a red dot
   on the You tab and the update button, and notifies once per version. It
   never downloads or installs; it opens the site and the person decides.
5. Update the version shown in `docs/index.html`.
6. Commit and push to `main`. GitHub Pages serves `docs/`.
7. Verify: the manifest is live, the site shows the new version, and the served
   APK is byte-for-byte identical to the local build.

`docs/index.html` also carries the install guide. Sideloading raises two
Android warnings, and both are explained there: **"Unsafe app blocked"** (Play
Protect, sometimes needing Play Protect paused from Play Store → profile →
Play Protect → settings, because "Install anyway" is often absent) and
**"Restricted setting"** (notification access greyed out for sideloaded apps;
a once-only fix that survives updates, so install over the top rather than
uninstalling).

## Limits, and things that are not bugs

- **Signed-in flows cannot be tested from this machine.** There is no way to
  sign in on the emulator, so likes, the profile card, the board's likes sort
  and real two-phone room sync are verified at the database and HTTP layer
  only. Real-phone testing belongs to the user.
- **`YOUTUBE_API_KEY` in `.env.local` is empty and that is fine.** Track lookup
  falls back to the in-page YouTube Music search. Do not treat it as a bug.
- **Host-ad detection depends on the music app announcing it.** Spotify sets
  the documented `android.media.metadata.ADVERTISEMENT` flag; YouTube Music
  generally does not, so a YouTube Music ad on the host can still travel as if
  it were a track. Known, not chaseable without new signal.
- **The Supabase region stays where it is.** Moving Singapore → Mumbai was
  considered and declined: sync is dominated by intervals and tolerance, not
  the ~60–90 ms round trip. Do not reopen unprompted.
- **The database password appears in an old chat transcript** and should be
  rotated. Raise it if credentials come up.
- **Open item:** `todo.md` 25 — a reported crash on Leave after joining, not
  reproduced across five instrumented scenarios. Hardened the one path tests
  cannot reach. Needs confirming on a real phone, ideally with the crash text.
