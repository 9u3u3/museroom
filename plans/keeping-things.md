# Keeping things

*The 4.1 release: downloads, shelves, and the settings that were drawn but not
built.*

The player landed in 4.0 and it could do one thing, which was play whatever you
asked it for as long as the phone had signal. Everything in this release is the
other half of being a music player: the parts that survive the signal going
away, the parts that remember what you liked, and the handful of controls every
player has that a person notices the absence of within a day.

The design for all of it already existed. `design/` has had ten artboards since
the player was planned, and most of what follows is not a new idea — it is the
gap between those boards and what was actually built being closed.

## Downloads, and why the cache came back

`LocalPlayer` has no disk cache, and the comment saying so is one of the longer
ones in the codebase, because the reason matters: a cache that keeps the first
part of a track that failed will replay exactly that much on every later
attempt and stop in precisely the same place. A song that broke once broke for
ever, at the same second. Media3 will not release a resource the player still
holds, so evicting it at the right moment is not something a caller can arrange.

That comment ends by saying the cache comes back with downloads, "where the
lifetime of a cached file is a decision somebody made on purpose". This is that.

The rules that make it safe are all about never handing over something partial:

- Bytes land in `<id>.part`. The real name appears only after the last byte.
- The `downloads` row is written after the rename, not before it.
- Anything that asks "can this play with the radio off" checks the row *and*
  the file, because a phone that ran out of space breaks one without the other.
- A sweep at startup deletes stray `.part` files and drops rows whose file is
  gone. Both happen: the first when a phone dies mid-download, the second when
  somebody clears the app's storage.

One at a time, deliberately. Four at once finishes the fourth sooner and the
first much later, and the first is the one somebody is about to get on a train
with.

The transfer honours the same bounded-range rule playback does, because it is
the same addresses: some refuse a request with no Range at all, some refuse one
that covers too much, and which is which depends on the client that issued the
address. Asking in chunks is also what makes progress a real number instead of
a spinner.

## Shelves that hold a note rather than a copy

A saved album keeps a title, an artist, a cover and a browse id. It does not
keep the track list. An album's contents live on YouTube's side and can gain a
remaster or lose a licence, so a copy taken the day it was saved would quietly
become wrong and there would be no moment at which anybody noticed. What is
worth keeping is the one thing YouTube does not know, which is that this person
wanted it. The page is fetched fresh when it opens.

Following an artist is the same shape and the screen says whose list it is. It
is Museroom's, not YouTube's. A follow that silently failed to reach an account
would be worse than one that never claimed to.

Downloads are the exception and the point of the whole release: those are
files, and they are the only shelf that still works with the radio off.

## The settings, and what each of them actually does

The Sound screen has been held to one rule since it was written — nothing on it
that does not change something audible. That is why it was short. It is longer
now because the switches became real, not because the rule was relaxed.

- **Normalise volume** uses the loudness figure the recording ships with, which
  is how much quieter than the reference that master is. Boost only, and capped
  at twelve decibels: above that the enhancer is raising the room tone the
  recording was made in rather than the music.
- **Skip silence** is ExoPlayer's own, which is the honest place for it.
- **The equalizer** is Android's, bound to a session id `LocalPlayer` mints
  itself. The player's own session is not settled until it has something to
  decode, which is after the point where somebody's saved settings should
  already be in force. Five bands are drawn and mapped onto however many the
  device really has, because a settings screen whose shape depends on the
  handset is one nobody can be told how to use. A device with no equalizer says
  so instead of showing dead sliders.
- **Crossfade is a fade**, and the screen says that in as many words. Museroom
  has one player, so the outgoing track goes quiet and the incoming one comes up
  from silence with no overlap. It fixes the thing people actually mind, which
  is a hard cut between two songs mastered at different levels. It is off in a
  room, always: everybody there is steering by one position on one clock, and a
  ramp that happened on one phone and not another is two people hearing
  different music at the same moment.
- **The sleep timer** stores a deadline rather than a countdown, so a phone that
  dozed through most of the hour still wakes up knowing the hour is over. "End
  of this track" is a different promise and is kept by the queue rather than by
  a clock, so a long song is allowed to be long.

## Two things drawn and not built

Said here so that the next person reading the artboards does not go looking for
them in the code.

**"Added by the room" on the queue.** The board shows a section of tracks a
listener asked the host to play, with the handle of whoever asked. Nothing in
the schema carries that: `listen_requests` is an invitation to listen together,
not a request for a song. Building it is a table, a policy that lets a room
member insert against the host, a realtime subscription and two screens — a
real feature rather than a gap — and none of it could be exercised from the
machine this was written on, because it needs two signed-in phones in one room.
It is worth building. It should not be built the day it cannot be tried.

**Signing in to YouTube.** The Sound screen has a card for it and the card says
plainly that there is no sign-in yet and why. With an account, the library
shelves here and the ones in YouTube Music would be the same list, and Subscribe
on an artist page would mean what the word means everywhere else. Without one,
everything is this phone's, which is a smaller promise and one the app can
actually keep.
