# Our own player — taking the music out of the WebView

This is a plan, not a patch. Nothing here is built yet. It exists so the next
change has a decision to follow instead of rediscovering the same dead ends.

Museroom plays music today by holding `music.youtube.com` in a hidden WebView
and driving `#movie_player` through `evaluateJavascript`. That was the right
call when the only question was "can a joiner hear the host's song at all". It
is the wrong foundation for a music player, and it is the reason a song still
does not start when it is told to.

The plan is to play the audio ourselves, with Media3, from a stream URL we
resolve over YouTube's own InnerTube API — the way Metrolist does — and to
build the rest of a real player on top of that: home, search, library, album,
artist, playlist, queue, lyrics, downloads. The rooms keep working throughout,
because `sync/RoomPlayer.kt` keeps its shape and only changes its guts.

## What we are solving

**The start is late and we cannot say by how much.** Two different delays are
tangled together and the current player cannot separate them.

The first is deliberate. `TogetherHost.ALLOWANCE_MS` is three and a half
seconds: the window every phone gets to fetch the next track before the room
is allowed to let go of it. That number was picked for a WebView that has to
hand a page-level player a video id and wait for it to navigate, negotiate and
buffer. It is a guess sized for the worst case.

The second is not deliberate. A page reports its position on `timeupdate`,
which fires about four times a second, and every reading crosses a JavaScript
bridge before we compare it against `ServerClock`. So the number the follow
loop steers by is quantised and already slightly old, and both the quantisation
and the staleness vary with how busy the page is. Below roughly a quarter of a
second, Museroom currently cannot tell a phone that is late from a phone that
is reporting late.

Everything else that is wrong with the WebView is the same problem wearing a
different hat. The page owns a queue we did not ask for, so `room.js` has to
watch for a track nobody chose and stop it. The page carries ad slots, so
`adblock.js` has to delete them before the page's own scripts run, because an
ad break that happens to one person breaks the room for everyone. The page can
decline to start in a viewport that is too small, and the failure is silence
rather than an error. None of these are bugs to fix. They are the cost of
renting somebody else's player.

## What Metrolist actually does

Metrolist is a third-party YouTube Music client, GPL-3.0, in maintenance mode
but current: 13.6.3, `media3` 1.10.1, Kotlin 2.4. It has no WebView player.
Here is the whole mechanism, in the order a track goes through it.

### 1. Browsing is an API, not a page

`innertube/` is a Gradle module of about four thousand lines whose entire job
is to call YouTube's internal InnerTube endpoints and parse the renderers that
come back. The public surface is one object, `YouTube`, and it covers
everything the app shows: `home`, `explore`, `charts`, `moodAndGenres`,
`newReleaseAlbums`, `search`, `searchSuggestions`, `searchSummary`, `album`,
`albumSongs`, `artist`, `artistItems`, `playlist`, `podcast`, `library`,
`musicHistory`, `next`, `related`, `queue`, `lyrics`, `transcript`, plus the
signed-in mutations — `likeVideo`, `subscribeChannel`, `createPlaylist`,
`addToPlaylist`, `moveSongPlaylist`, `addSongToLibrary`, `uploadSong`.

The parsing is the bulk of it. YouTube returns nested renderer objects with
names like `musicResponsiveListItemRenderer` and
`musicCarouselShelfRenderer`, and each `pages/` file turns one of those into a
flat Kotlin type. This is the part that is tedious to write and boring to
maintain, and it is the part we would be foolish to write ourselves.

Since a recent refactor, `innertube` no longer builds the requests itself. It
is a compatibility facade over **InnerTubeX**, a separate library
(`com.github.MetrolistGroup.innertubex:innertubex`, v0.5.2, published on
JitPack, GPL-3.0 — confirmed present and licensed as stated). InnerTubeX owns
request construction, session handling, retries, and the hard part below.

### 2. Getting a playable URL is the hard part

`YouTube.player()` returns a `PlayerResponse` with a `streamingData` block:
`adaptiveFormats`, each with an `itag`, a `mimeType`, a `bitrate`, an
`audioSampleRate`, a `contentLength`, a `loudnessDb`, and either a `url` or a
`signatureCipher`. Audio-only formats are the ones where `width` is null.

Three things stand between that response and a URL that will actually serve
bytes.

**The cipher.** Many formats arrive with the signature scrambled by a function
that lives inside YouTube's own player JavaScript, and it changes. Metrolist
ships a JavaScript solver in `app/src/main/assets/solver/` — `meriyah.js` to
parse the player script, `astring.js` to print it back out, and
`yt.solver.core.js` to find and run the transform. InnerTubeX's
`YouTubeCipherService` and `RemotePlayerConfigStore` drive it and cache the
result.

**The attestation.** YouTube increasingly wants a proof-of-origin token, and
the only way to mint one is to run Google's BotGuard, which is obfuscated
JavaScript that expects a browser. Metrolist runs it in a headless WebView:
`assets/po_token.html`, `utils/potoken/PoTokenWebView.kt`, wrapped in
`PoTokenGenerator` with a hard timeout, because the WebView's sandboxed
process can be culled by the system and leave the call hung forever. If it
times out, extraction falls through to clients that do not need a token, such
as `ANDROID_VR`.

This matters for us and deserves saying plainly: **the WebView does not
disappear, it changes job.** Today it is the thing playing the music, for the
whole length of every song, holding a page the size of YouTube Music. After
this change it is a short-lived, offscreen, cancellable call that mints a token
and goes away, and playback does not stop if it fails.

**The client.** The same video answers differently depending on which YouTube
client you claim to be, and clients break one at a time. Metrolist keeps a
per-video record of which clients have failed recently
(`InnerTubeXPlayer.markStreamClientFailed`, with a five-minute time to live)
and excludes them from the next attempt, so a song that fails on one client
retries on another instead of failing outright.

The result of all that is `InnerTubeXPlayer.playerResponseForPlayback`, which
hands back a URL, the request headers it needs, the client name that produced
it, how many seconds until it expires, and whether it must be fetched in
bounded ranges.

### 3. Playing it is ordinary Media3

This is the part that is almost anticlimactic, and it is the part that fixes
our timing.

```
ExoPlayer
  └── DefaultMediaSourceFactory(dataSourceFactory, {Matroska, FragmentedMp4, Mp4})
        └── ResolvingDataSource.Factory        ← turns a media id into a real URL
              └── CacheDataSource (downloads)  ← permanent, the user asked for it
                    └── CacheDataSource (player cache)   ← transient
                          └── OkHttpDataSource
```

`ResolvingDataSource` is the whole trick. ExoPlayer is handed `MediaItem`s
whose ids are video ids, not URLs. When it actually needs bytes, the resolver
runs: check the download cache, check the player cache, check an in-memory
`StreamUrlCache` keyed by media id and guarded by a generation counter so a
stale write cannot overwrite a fresh one, and only then go and extract. A
resolved URL is rewritten into the `DataSpec` along with its headers.

So the expensive work happens once per track, at the last possible moment, and
never again while the URL is valid. Everything after it — seeking, position,
rate, gapless transitions — is ExoPlayer doing what ExoPlayer does.

Around that: `MusicService` is a `MediaLibraryService` with a
`MediaLibrarySession`, so Android Auto and the system get a real media session
for free. Audio is post-processed by custom `AudioProcessor`s for volume
normalisation, using the `loudnessDb` that came back in the player response,
and for silence detection. Queues are an interface, `Queue`, with a
`YouTubeQueue` implementation that walks `next()` continuations and starts a
radio by asking for the playlist id `RDAMVM<videoId>`.

### 4. There are no ads because nobody asked for a page

Worth stating because it is the thing `adblock.js` exists to do. Ad slots are
a property of the YouTube Music web application: `playerAds`, `adPlacements`
and `adSlots` in the page's response. The InnerTube player endpoint, asked for
`streamingData` as a media client, returns formats. There is no ad break to
delete because there is no page to run one.

`app/src/main/assets/adblock.js` and the ad-store bookkeeping in `room.js`
both become dead code. So does the strayed-track check, because nothing is
going to start playing a song we did not queue.

### 5. They also built listen-together, and it does not change our design

`app/src/main/kotlin/com/metrolist/music/listentogether/` has a client, a
manager, a wire protocol with its own codec, and its own `ServerClock`. We do
not need any of it — Museroom's rooms are further along and are built on our
own Supabase schema and Realtime client. It is worth knowing it exists only as
confirmation that the design converges: a shared clock, a scheduled moment,
every phone a client of it.

## What this buys the room

Every one of these is an existing invariant in `CLAUDE.md` that gets easier,
not a new feature.

- **Position stops being a guess.** `ExoPlayer.getCurrentPosition()` is a
  direct call returning milliseconds. No bridge, no `timeupdate`, no
  quantisation. `TOLERANCE_MS` of 400 ms was chosen partly to absorb reporting
  noise; the noise floor moves down and the tolerance can follow it.
- **Seeks land where they are aimed.** Exact seek to a millisecond, rather than
  asking a page to seek and reading back where it decided to go.
- **The rate nudge stays and gets cleaner.** `PlaybackParameters(speed, pitch)`
  with pitch held at 1.0 is the same manoeuvre as `preservesPitch`, done in the
  audio pipeline rather than by a page.
- **The moment can be prepared for.** `Player.prepare()` on a `MediaSource` can
  happen well before the moment, so what is left at the moment is starting
  decode, not fetching a page. This is what should let `ALLOWANCE_MS` come
  down. What it comes down *to* is a measurement, not a guess, and taking that
  measurement is a step in the plan.
- **The tail rule gets an exact duration.** "Has our own player finished" is
  answered by position against a duration from the container, not by a page's
  rounded numbers.
- **Nobody ever loses a second of music, more cheaply.** The rule that a track
  begun at its start is never seeked forward stays exactly as it is. It simply
  costs less, because the gap it has to close is smaller to begin with.
- **The stray check disappears** and `room.js` with it.

Two honest limits. First, `play()` is still not sample-accurate: there is
buffer and `AudioTrack` latency between the call and the first sound, it
differs between handsets, and it is measurable but not removable. The
scheduled-moment design stays for exactly that reason. Second, all of this
improves the room's *timing*; it does nothing about the network round trip to
Supabase, which is already not the dominant term.

## What we take, and how

Metrolist is GPL-3.0. InnerTubeX is GPL-3.0. Museroom is GPL-3.0. Lifting code
is allowed and is the intended use; the obligation is to keep the licence and
say where it came from.

| Piece | Decision |
| --- | --- |
| InnerTubeX | **Not used.** See *What the first experiment changed* below. Kept as the fallback if the plain path ever stops answering. |
| `innertube/` module | Not lifted. Its value is browse-response parsing, which we only need from step 5, and it comes with the same toolchain cost. Revisit there. |
| PoToken WebView | **Not needed.** The clients we ask are not asked for a token. |
| Stream resolution | Written ourselves, on the okhttp and kotlinx.serialization the app already had: `player/InnerTube.kt` and `player/Streams.kt`. |
| `MusicService` | Write our own. Metrolist's is 4,900 lines and carries Discord presence, Cast, Shazam, alarms, a wrapped feature and an equalizer wizard we do not want. Take the data-source stack and the queue interface; leave the rest. |
| UI | Write our own. Metrolist is Material 3. We are not. |
| listen-together | Take nothing. Ours is further along. |

## What the first experiment changed

The plan above said to depend on InnerTubeX and not to fork it. Step 1 was
written to find out whether that was real. It was not, and the reason is worth
keeping.

**InnerTubeX costs a toolchain migration, not a dependency line.** Its published
Android artifact carries Kotlin metadata version 2.4.0, is compiled to Java 21
bytecode, and declares `minCompileSdk=37`. Museroom is on Kotlin 2.0.21, Java 17
and compileSdk 35. Using it means moving Kotlin, KSP, AGP, Gradle, Compose, Room
and the Android platform all at once, and installing a platform that is not on
this machine, before a single note is played.

**The expensive part turned out to be avoidable.** The cipher solver and the
BotGuard WebView exist because the *web* client's URLs arrive scrambled and
increasingly need an attestation token. Clients that have no browser to run
that check in cannot be asked for one. Asked as `ANDROID_VR`, the player
endpoint answers with four audio-only formats, each carrying a plain `url`,
good for about six hours; `IOS` answers the same way. Both were verified
against the live endpoint, and a range request on the resulting URL returned
`206 Partial Content` with real Opus audio.

So the resolver is ours after all, and it is small: one request, one response
shape, a format chosen by bitrate. No ktor, no QuickJS, no headless WebView, no
toolchain change. Media3 1.9.1 is the newest release that still declares
`minCompileSdk=35`, which is what lets the engine land without moving anything
else.

This is a worse position in exactly one way, and it should be said plainly: we
now own the breakage. If YouTube starts demanding a token from device clients
too, InnerTubeX is the answer and the toolchain bill comes due then. That is
why `player/InnerTube.kt` keeps a *list* of clients rather than a favourite, why
a client that fails a specific recording is remembered and skipped, and why
step 10 still holds the WebView player in reserve for a release.

## The shape of it in Museroom

```
:innertube                       lifted, GPL-3, credited
com.museroom.app.player/
  MusicService.kt                MediaLibraryService, ExoPlayer, the caches
  Streams.kt                     video id → URL, headers, expiry
  StreamCache.kt                 in-memory, generation-guarded
  PlayerConnection.kt            what the UI binds to
  queues/                        Queue, ListQueue, YouTubeQueue, radio
  potoken/                       lifted BotGuard WebView
com.museroom.app.library/        Room entities, DAOs, account sync
com.museroom.app.ui/screens/     home, search, library, album, artist,
                                 playlist, player, lyrics, queue, sound
```

The single most important decision in this plan:

> **`sync/RoomPlayer.kt` keeps its public surface and changes its
> implementation.** `search`, `cue`, `load`, `begin`, `seekTo`, `play`,
> `setRate` and the `Snapshot` flow stay exactly as they are. `FollowSession`
> and `TogetherHost` do not change at all.

Every room invariant in `CLAUDE.md` is written against that surface. If the
surface holds, the rooms keep working while the player underneath is replaced,
the existing tests keep meaning what they meant, and there is never a commit
where both the player and the room are in motion at once.

## The screens

The design is done and is in `design/`, built with `node design/build.mjs`.
Twenty-one artboards across three pages of `canvas.json`. It continues the
existing comic and neobrutalist kit unchanged: cream paper, three-pixel ink
stroke, offset hard shadows, Bangers display, Archivo body, Space Mono for
numbers, and the four accents that stay the same colour in both themes.

The tabs change, because a music player needs somewhere to browse:

**Home · Library · Rooms · Board · You**

- **Home** replaces Now. Quick picks, listen again, a live-rooms strip, and the
  external-capture card. Search is a magnifier in the top bar, where it is
  expected, rather than a sixth tab.
- **Library** is new: playlists, songs, albums, artists, offline. Liked songs
  and Offline are drawn as accent fills, because they are the app's own
  collections and never carry a cover of their own.
- **Rooms** is the old Friends and Nearby under one tab with three chips —
  friends, nearby, requests. The in-room screen is its own surface.
- **Board** and **You** are unchanged.

New surfaces: Search, Player, Lyrics, Queue, Album, Artist, Playlist, Room and
Sound. A **mini player** sits on top of the nav on every tab. It sits *on* the
nav rather than floating over the list, because a bar that hovers hides the
last row of every screen it appears on.

Three rules the new screens follow, so they stay recognisably the same app:

- **Lime means Museroom is playing.** Sky means we are reading another app.
  The capture card is sky and always will be.
- **A row is a row everywhere.** One `.row` in the token sheet, used by home,
  search, library, album, artist, playlist, queue and the room. A 46-pixel
  thumbnail gets the same halftone screen as a full-bleed cover, so they read
  as the same object at two sizes.
- **The player surfaces ship dark.** They are what is on screen with the lights
  off, and the artwork should be the only bright thing on them.

## Order of work

Each step ends somewhere shippable. Nothing after step 4 blocks the rooms.

1. ~~**The dependency, proved.**~~ Done, and it changed the plan. Media3 1.9.1
   added; the extraction path verified against the live endpoint end to end.
2. ~~**The resolver.**~~ Done. `player/InnerTube.kt` asks the player endpoint as
   a device client and rolls over between clients; `player/Streams.kt` picks a
   format for a quality and caches the answer against its stated expiry, with a
   generation guard so a slow answer cannot overwrite a fresh one. Pinned by
   nine unit tests against a saved capture.
3. **`MusicService`.** `player/LocalPlayer.kt` is the engine: ExoPlayer over a
   resolving data source over a disk cache over OkHttp. Still needs the
   foreground service, the platform media session and the notification, and it
   has not made a sound on a real phone yet.
4. **`RoomPlayer` becomes an adapter.** Same surface, new guts. Then
   **measure**: log the interval between `begin` and first audio on a real
   phone, several times, and set `ALLOWANCE_MS` and `TOLERANCE_MS` from what
   comes back. Ship this. The rooms are now on our own player and the delay
   question has an actual number attached to it.
5. **Browsing.** Home, search, album, artist and playlist against the `YouTube`
   facade. Read-only, no account needed. This is where the app starts being a
   music player.
6. **Queue and player UI.** The full player, the mini player, the queue with
   reordering, and playing from any of the browse screens.
7. **Library and the database.** Room entities for songs, albums, artists,
   playlists and formats. Liked songs, local playlists, history, downloads.
   Museroom already uses Room, so this is new tables, not new machinery.
8. **The account.** Sign in to YouTube Music, sync library and playlists.
   Everything before this works signed out.
9. **Lyrics**, then **Sound**: quality, cache size, normalisation, skip
   silence, sleep timer, equalizer.
10. **Delete the old player.** `room.js`, `adblock.js`, `AwakeWebView`, the ad
    store, the stray check, and the WebView half of `RoomPlayer`. Not before
    step 4 has been on real phones for a release.

## What this plan will not do

- **It does not remove the external-capture feature.** Reading `MediaSession`
  stays. It is what feeds the board, what makes broadcast rooms possible, and
  what makes Museroom useful to somebody who lives in Spotify.
  `media/Sources.kt` stays a fixed allowlist.
- **It does not host or ship any audio.** The bytes come from YouTube, the same
  as they do today. What changes is that we ask for them as a client instead of
  running their page.
- **It does not use SABR.** Metrolist explicitly refuses streams that need it,
  and so should we until the ordinary path is solid.
- **It does not build `room_ready_for` or `room_late_ms`.** Still unused, still
  not to be built on without asking. A dead phone must not be able to hold a
  room silent, and a faster player does not change that.
- **It does not go to the Play Store.** That decision is made and is not
  reopened by this.
- **It does not fork InnerTubeX.** If it breaks, we take a new version or we
  contribute upstream.

## How this breaks, and what we do about it

- **YouTube changes the cipher or the attestation.** This is not hypothetical;
  it is the normal weather. The mitigation is the dependency: InnerTubeX is
  versioned and maintained, and per-video client rollover means one broken
  client is a retry rather than a failure. Museroom's own mitigation is step 10
  — keep the WebView implementation behind `RoomPlayer`'s interface for one
  full release after step 4, so there is something to fall back to while a fix
  lands.
- **The PoToken WebView gets culled by the system.** Metrolist hit this and
  capped it with a timeout that falls through to token-free clients. Lift the
  timeout along with the code; it is the part that was learned the hard way.
- **A region without YouTube Music.** The client simply does not work there.
  Nothing to do but say so.
- **App size and cold start.** Media3, ktor and the innertube module add
  several megabytes and some startup work. Worth measuring at step 3, not
  worrying about before.
- **Terms of service.** Reading a `MediaSession` was uncontroversial. Calling
  InnerTube as a media client is what every third-party YouTube client does and
  is against YouTube's terms. Museroom is already sideloaded and GPL, which is
  the same position Metrolist, NewPipe and the rest occupy. It is a reason the
  Play Store stays closed to us, which it already was.

## The open question this plan does not answer

Whether the delay that was heard is the deliberate three and a half seconds
between songs or a listener genuinely starting after the host. The current
player cannot tell those apart, which is itself part of the argument for
replacing it. Step 4 is where that stops being a guess: with a real player, the
interval between the scheduled moment and first audio is a number we can log on
each phone and compare.
