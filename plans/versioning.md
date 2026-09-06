# What version this actually is

The numbers on the first fifty releases were not calculated, they were felt.
Every change moved the minor digit whether it added something or repaired
something, and the major digit moved at 2.0, 3.0, 4.0 and 5.0 because the minor
digit had got big. That is not a version, it is a counter with a decimal point
in it.

This is the recalculation, done against the commit history, using the rule:

- **Major** — a breaking change. Something people relied on works differently
  or is gone.
- **Minor** — a new feature that does not break what was there.
- **Patch** — a fix or a small improvement.

Everything before the app was first drawn in its own design is `0.x`, because
semantic versioning reserves that for the period before there is a shape worth
promising to keep.

## The three breaking changes

Only three things in the whole history actually broke something.

1. **`1.0.0` — "Build the app in the design we drew".** Every screen was
   replaced. Somebody who knew the old app did not know this one.
2. **`2.0.0` — "Play the music ourselves, so following somebody needs no
   hands".** Before it, following a friend opened their music app. After it,
   Museroom is the thing playing. That is a different promise, not a better
   version of the old one.
3. **`3.0.0` — "Run the room a few seconds behind, and stop pausing the host".**
   Versions 5.3 and 5.4 held the host's own player at a track change so every
   phone could start together. This took that out. A host who had got used to
   the room waiting for them found it no longer did.

Notably **not** breaking: playing our own audio instead of a hidden web page.
It changed everything underneath and nothing anybody had to relearn, so it is a
minor.

## The walk

| Was | Is | Change | Why |
| --- | --- | --- | --- |
| 0.7 | 0.1.0 | detection, crediting, sync | first cut |
| 0.8 | 0.2.0 | delete history, private session | feature |
| 0.9 | 0.3.0 | ask before recording | feature |
| 1.0 | 0.4.0 | fixed list of players | feature |
| 1.1 | 0.5.0 | friends | feature |
| 1.2 | 0.6.0 | proximity | feature |
| 1.3 | 0.6.1 | proximity reasons and range | fix |
| 1.4 | 0.7.0 | artwork, timers, listen too | feature |
| 1.5 | 0.7.1 | verify a play command | fix |
| 1.6 | 0.8.0 | skip adverts, near hands-free | feature |
| 1.7 | 0.9.0 | design system and mark | feature |
| 1.8 | 0.9.1 | redraw the mark | fix |
| 2.0 | **1.0.0** | the app in its own design | **breaking** |
| 2.1 | 1.0.1 | invisible icons, calmer screens | fix |
| 2.2 | 1.0.2 | wrapping, copy, restricted setting | fix |
| 2.3 | 1.1.0 | ask to join | feature |
| 2.4 | 1.2.0 | follow by seeking | feature |
| 2.5 | 1.3.0 | exact track links | feature |
| 3.0 | **2.0.0** | we play the music | **breaking** |
| 3.1 | 2.0.1 | keep ad breaks out | fix |
| 3.2 | 2.1.0 | start on yes, from the notification | feature |
| 3.3 | 2.1.1 | a real window for the player | fix |
| 3.4 | 2.1.2 | stop remembering a miss | fix |
| 3.5 | 2.1.3 | stop publishing email addresses | fix |
| 3.6 | 2.1.4 | keep playing in a pocket | fix |
| 3.7 | 2.2.0 | faces, a door you choose | feature |
| 3.8 | 2.3.0 | battery, radio, faces on the radar | feature |
| 3.9 | 2.3.1 | stats and sticker clipping | fix |
| 4.0 | 2.4.0 | count time in a room | feature |
| 4.2 | 2.5.0 | say what is in here | feature |
| 4.3 | 2.6.0 | leave, block, report, delete, history | feature |
| 4.4 | 2.7.0 | pushed skips instead of polling | feature |
| 4.5 | 2.8.0 | hearts, pages, a track you heard | feature |
| 4.6 | 2.9.0 | one player, a clock that stops, updates | feature |
| 4.7 | 2.9.1 | the crash on the air | fix |
| 4.8 | 2.9.2 | take people to the page | fix |
| 4.9 | 2.9.3 | never play a song nobody chose | fix |
| 5.0 | 2.9.4 | stop when they stop | fix |
| 5.1 | 2.10.0 | hold in step by speed | feature |
| 5.2 | 2.11.0 | two phones, one clock | feature |
| 5.3 | 2.12.0 | start every song together | feature |
| 5.4 | 2.12.1 | never start in the wrong place | fix |
| 5.5 | **3.0.0** | the room runs behind, host unheld | **breaking** |
| 5.6 | 3.0.1 | the crash on leaving a room | fix |
| 5.7 | 3.0.2 | listeners out of Nearby | fix |
| 5.8 | 3.1.0 | together mode | feature |
| 5.9.0 | 3.2.0 | our own player, search, a player screen | feature |
| 5.9.1 | 3.2.1 | a real extractor, past the minute | fix |
| 5.9.2 | 3.2.2 | ask a different client | fix |
| 5.9.3 | 3.2.3 | name what stopped | fix |
| 5.9.4 | 3.2.4 | stop breaking a song by remembering it | fix |
| 5.10.0 | 3.3.0 | home shelves | feature |
| 5.11.0 | 3.4.0 | the library and its database | feature |

## The number going backwards

The name drops from 5.11.0 to 3.4.0 and nobody is stranded by it, because
`versionCode` is what decides whether an update exists and it only ever goes up.
`net/Updates.kt` compares `release.versionCode <= installed` and never reads the
name; the name is what a person sees. So the count keeps climbing while the name
becomes true.

That is the whole reason this was worth doing rather than carrying on from 5.11.
A version is a claim about what changed. Ours was claiming five breaking changes
that never happened, and hiding the three that did.
