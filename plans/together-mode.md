# Together mode — a second kind of room

This is a plan, not a patch. Nothing here is built yet. It exists so the next
change has a decision to follow instead of rediscovering the same dead ends.

Broadcast mode is what the app does today. Together mode is the opt-in where
the host is on the same clock as the listeners. The two cannot be mixed on one
track: either Museroom owns every speaker in the room, or the host’s native
player is allowed to be ahead.

## What we are solving

A listener is told a track exists at the instant it begins, and cannot have
fetched it yet. If we try to sit level with a host who is already playing
Spotify, somebody loses the opening: we skip it, or we play it twice, or we
hold the host’s music app and hope it obeys.

The current room refuses that bargain. It runs a fixed three seconds behind
the host (`ROOM_LAG_MS` in `sync/FollowSession.kt`). Listeners stay in step
with each other. The host is the DJ and is supposed to be ahead.

That is the right default. It is the wrong deal when people are in the same
room and both phones are playing out loud. Then the host being three seconds
early is the whole problem.

## What every other app already decided

AmpMe, SoundSeeder, Samsung Group Play, Huawei Party Mode, Spotify Jam, Apple
SharePlay, Snapcast, AirPlay, Sonos: if the phones must start together, every
phone is a client of one player. A shared clock names a moment in the future.
Everyone cues, then begins. Drift is walked off with speed, not by seeking the
intro away.

None of them pause the host’s Spotify from outside. Third-party transport
controls on Android (`MediaController.pause`, `play`, `seekTo(0)`) are
requests. Spotify often honours them; YouTube Music often does not; seek is
the least reliable of the three. Versions 5.3 and 5.4 already shipped “hold
the host’s own player at a track change” and took it out: the control was
unreliable, and the silence it cost the host was not worth it.

The leftover columns on `now_playing` are that attempt, not scrap:

- `starts_at`
- `start_position_ms`
- `room_ready_for`
- `room_late_ms`

`room_members()` still returns lateness and readiness. Do not invent a
parallel schema. Reuse these, on purpose, for a mode where we actually own
the player.

Social now-playing apps (Last.fm, MUBR, Jamscope, AudiPals) show the song and
stop there. Museroom’s unusual piece is broadcast: read whatever the host is
already playing through `MediaSession`, and play a YouTube Music copy for
friends. Together mode is not that invention. Together mode is the solved
problem, done inside our client.

## The two modes

The host chooses. Listeners do not. A room is one mode at a time.

### Broadcast (default, already shipped)

- Host plays Spotify, YouTube Music, or any app on the allowlist in
  `media/Sources.kt`.
- Museroom only reads that session. It never drives it.
- Listeners play Museroom’s hidden YouTube Music WebView (`sync/RoomPlayer.kt`).
- Follow loop aims at `targetPosition` (host minus `ROOM_LAG_MS`), not at
  being level with the host.
- A track that began while we were in the room still starts at 0. A track
  already running when we walked in is joined partway. Never seek a start
  forward to catch up.
- Pause, skip, and scrub belong to the host’s music app. The joiner
  notification has Like and Leave only.

### Together (new)

- Nobody’s native player is the speaker. Host included.
- Every phone in the room is `RoomPlayer`: same `cue`, same `begin`, same
  `ServerClock` moment.
- Skip and pause are Museroom’s, on the host. Listeners still do not get
  transport.
- `starts_at` is the agreement. It is computed from the shared clock, not
  from a “go” message, so nobody is late by however long their packet took.
- The host hears YouTube Music through Museroom, not Spotify. That has to be
  on the toggle, in words a person can read before they flip it.

There is no third mode that keeps the host on Spotify and lines their speaker
up with the room. That is the 5.3 path. Do not reopen it.

## How together actually starts a song

Copy Snapcast and Jam, not a barrier that waits on the slowest phone.

1. Host picks the next track inside Museroom (search already exists on
   `RoomPlayer`). Resolve it to a YouTube Music id the same way listeners
   already do.
2. Host writes the row: fingerprint, title, artist, `source_track_id`,
   `is_playing = false`, `start_position_ms = 0` for a new track,
   `starts_at = ServerClock.now() + FETCH_ALLOWANCE`.
   Three-ish seconds is the same budget listeners already get. It is a
   buffer, not a guess we later try to shrink by pausing people.
3. Every phone, host included, `cue`s that id and holds silent.
4. At `starts_at`, every phone `begin`s at `start_position_ms`. The host is
   not special in this loop.
5. After that, the follow loop is the one we have: below 400 ms nudge rate
   (`preservesPitch`), above it seek, rate-limited. Comparisons are against
   the scheduled position in server time, not against a native host that is
   three seconds ahead — because there is no such host.

A phone that misses `starts_at` starts late and closes by speed. It does not
skip the opening. That rule does not change.

`room_ready_for` is optional seasoning, not the clock. If everyone has cued
before `starts_at`, we can still wait until `starts_at` so a late Realtime
delivery does not start one phone early. If someone has not cued when the
moment arrives, they join late. Do not hold the room silent until a 6 s
timeout on every skip. A dead phone must not own the room; a live room must
not eat six seconds of nothing between tracks.

### Mid-join

Unchanged in spirit. Someone who walks in during a track is given the current
position, not 0:00. Rewinding the room for them is how you steal the song
from everyone already in it.

`start_position_ms` on a scheduled start is for “this track begins here for
the whole room.” A joiner arriving later ignores a stale `starts_at` (the
moment has gone) and follows the live position, same as broadcast mid-join.

### Pause and skip

A paused together-room is not projected. That is already true of a paused
host in `hostPosition`. Together just means the pause came from Museroom
calling `RoomPlayer.pause()` on the host, then publishing `is_playing =
false`. Resume seeks (free: the music is already stopped) and plays.

Skip is a new scheduled start, not a native `MediaSession` callback. The
previous track’s tail is still protected: fetching the next id stops the
current one, so the switch waits until our own player has finished, or until
the host has moved on past the lag, exactly as broadcast already does. In
together mode the “host” position *is* our player, so the tail rule is
simpler — it is the same object.

### Ads

Together-mode audio is our WebView. `adblock.js` already runs at document
start. Host-ad detection from Spotify’s `ADVERTISEMENT` flag does not apply,
because Spotify is not the speaker. A YouTube Music ad that still gets
through is the same class of stall `room.js` already has to survive
(`strayed`, wanted-id guard). Do not invent a second ad path.

## How the host gets onto our player

Turning the toggle on is a handoff, not a crossfade of two apps.

1. If something is already playing natively, resolve that title to a YouTube
   Music id (existing `TrackResolver` / in-page search).
2. Request audio focus. Ask the native session to pause once if a controller
   is sitting there. If it ignores us, say so on screen: two apps will play
   at once until they pause Spotify themselves. Do not busy-loop transport
   controls.
3. Cue the id on the host `RoomPlayer`, write `starts_at`, begin with the
   room.
4. From then on, `PlaybackTracker` must not publish the native session as
   `now_playing` while together mode is on. The WebView snapshot is the row.
   Publishing Spotify in parallel would yank listeners back into broadcast
   follow.

Turning the toggle off is the reverse: `RoomPlayer.leave()` on the host,
native session becomes `now_playing` again, listeners still in the room fall
back to broadcast lag against that session. If the host has nothing native
playing, the room simply has no track until they start one.

Empty room: together mode may be on with nobody listening. The host is then
just using Museroom as a player. That is fine, and it means the first joiner
walks into a client that is already the source of truth.

## What the host uses to pick music

Together mode is not “read Spotify, play YouTube Music on the host too.” That
still learns about a skip after Spotify has started it.

The host needs a way to search and skip inside Museroom. `RoomPlayer` already
searches through the page. The missing piece is UI: a search field and a
skip/pause on the host’s Now screen and on the host notification.

That is the real product cost of together mode. Without it the mode is a
hostage: you flipped the toggle and now you cannot change song.

Listener notification stays Like and Leave. Host notification in together
mode is allowed pause and skip, because those belong to Museroom.

## Schema and protocol

Add one explicit flag. Do not make listeners infer mode from `starts_at`
being non-null.

Suggested column, new migration:

```sql
alter table now_playing
  add column if not exists room_mode text not null default 'broadcast';
-- 'broadcast' | 'together'
```

Keep `starts_at`, `start_position_ms`, `room_ready_for`, `room_late_ms`.
In together mode they mean what the 5.3 comments said, except the host is
also a client and `start_position_ms` is usually 0 for a fresh track.

Publish `starts_at` in the same `ServerClock` as `updated_at`. Listeners
already derive `roomStartMoment` from position and `updated_at` in
broadcast. Together mode should prefer the written `starts_at` when it is in
the future, and ignore it when it is in the past (mid-join / stale row).

Realtime stays the push for pause, resume, and track change. The heartbeat
stays a coroutine. Do not put a bare thread back.

`resolve_nearby` already hides anyone whose `following_user` is set. A
together host has `following_user` null, so they remain a room to join. Do
not treat together as “following yourself.”

## UI

Host, on the room card on Now (the roster already lives there):

- A toggle: **Broadcast** / **Together**.
- One sentence under Together: “Everyone, including you, plays in Museroom.
  Pause Spotify or both will sound.”
- While together is on and a track is cued but `starts_at` is still in the
  future, a small “starting together” state is enough. Do not show a 6 s
  countdown as if we were waiting on named people, unless we later turn
  `room_ready_for` into a roster tick.

Friends / Nearby join buttons do not mention the mode. Joining is joining.
The follow bar can say “together” vs the existing lag copy so a listener
knows they are not three seconds behind a native DJ.

Do not ask this as a permission during onboarding. It is a room setting.

## Crediting, presence, likes

Minutes still count when a track finishes, on the server. Together-mode
playback is still a play: the host’s WebView snapshot is what
`PlaybackTracker` should treat as the local session so the host is not
credited from a paused Spotify *and* from Museroom.

Likes still send *who*, never *what*. `like_track()` reads `now_playing`.
Together mode does not change that, as long as the host row is the WebView
track.

A joiner in together mode is still `following_user = host`. Presence,
freshness, blocking, two-minute roster: unchanged.

## What we will not do

- Drive Spotify / YouTube Music / Apple Music with `TransportControls` to
  hold a start.
- Stream PCM from the host (Snapcast). We do not capture other apps’ audio,
  and we must not.
- Ultrasonic AmpMe calibration. Same-room speaker stacks can wait. Together
  mode on one clock is already the right first 100 ms; chirps are a later
  polish.
- Official Spotify / Apple playback SDKs so each person plays “their”
  service. We do not have those APIs, and YouTube Music has no public one.
  The WebView is the client we have.
- Building on `starts_at` for broadcast mode. Broadcast keeps the lag.
- Letting listeners skip in together mode. Host is still the DJ.

## Order of work

Do not land the toggle until the host can change song without leaving the
mode.

1. **Flag and publish path.** `room_mode` on `now_playing`. While together,
   stop publishing the native session; publish `RoomPlayer` snapshots
   instead. JVM tests around “native row must not win.”
2. **Scheduled start for a room of clients.** Host cues, writes `starts_at`,
   host and listeners `begin` on that instant. Extend `RoomDelayTest` /
   `FollowSession` so `roomStartMoment` in together mode is `starts_at`, and
   a past `starts_at` falls through to live position. Reuse
   `FETCH_ALLOWANCE_MS`. Do not wait on `room_ready_for` yet.
3. **Host transport.** Pause, play, skip, search UI on Now and on the host
   notification (`PendingIntent.getForegroundService` still, service still
   pays the notification debt first). `room.js` wanted-id guard applies to
   the host now too.
4. **Handoff.** Toggle on: resolve current native track, audio focus, one
   pause attempt, copy if the native app keeps playing. Toggle off: leave
   player, native session owns the row again, listeners drop to lag.
5. **Copy, empty states, mid-join, leave.** Swiping out of recents still
   ends the room (`onTaskRemoved`). Together does not get a special
   background exemption; Chromium still needs a window.
6. **Only then** consider `room_ready_for` to shrink the buffer on a fast
   room. Ceiling still exists. Default path must work with the allowance
   alone.

## Tests

- JVM: together `starts_at` in the future is the begin moment; in the past
  it is ignored; pause is not projected; mid-join does not rewind; native
  publish is suppressed while together.
- `app/src/test/js/stray.mjs`: host is another client of `wanted`. A host
  that strays must pause the same way a listener does, or the room follows a
  song nobody chose.
- Instrumented: `RoomPlayer` cue/begin already has coverage. Host-as-client
  can be a narrower class if we can drive the WebView without sign-in.
- Signed-in two-phone together sync cannot be exercised from this machine.
  Say “not reproduced” until it is tried on real phones. Same limit as
  likes and the current room.

## Files that will move

When this is built, expect the change to sit in:

- `sync/FollowSession.kt` — branch on mode; host may run a follow loop
  against their own published row, or a thinner “I am the clock” loop that
  only cues and begins.
- `sync/RoomPlayer.kt` / `assets/room.js` — host uses cue/begin, not only
  listeners.
- `sync/RoomService.kt` / `notify/` — host transport in together mode.
- `tracking/PlaybackTracker.kt` / `sync/SyncEngine.kt` — which session is
  `now_playing`.
- `ui/screens/NowScreen.kt` — toggle, search, copy.
- `supabase/migrations/` — `room_mode`; keep the 5.3 columns.
- `app/src/test/java/com/museroom/app/RoomDelayTest.kt` and a new together
  test.

Match the existing comment register. Full sentences, why not what.

## Success

Broadcast still works with the native app, lag intact, nobody losing a
second of music.

Together, with the toggle on, the host and a listener begin a new track
within the same tolerance we already use in the follow loop (~400 ms before
a seek), including the host’s phone. The host did not wait on Spotify. The
host waited on `starts_at`, which is our player.
