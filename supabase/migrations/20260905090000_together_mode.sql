-- A room that owns every speaker in it, host included.
--
-- Broadcast is what a room has always been: the host plays Spotify or YouTube
-- Music, Museroom reads that session, and everybody else plays their own copy
-- three seconds behind. The lag is not a compromise. A listener is told a
-- track exists at the instant it begins and cannot have fetched it yet, so
-- aiming to be level with a native host costs somebody the opening of the
-- song.
--
-- Together is the other bargain, and it is the one every multi-room system
-- already settled on: nobody's native player is the speaker. Every phone in
-- the room, the host's included, is a client of one agreed moment. Then being
-- level is not a trick, it is simply what happens, because there is no player
-- ahead of the clock to catch up with.
--
-- Which of the two a room is running has to be said out loud rather than
-- guessed from whether starts_at happens to be set. A listener reading a
-- half-written row would otherwise pick the wrong distance to hold, and the
-- wrong distance is heard.

alter table now_playing
    add column if not exists room_mode text not null default 'broadcast';

alter table now_playing
    drop constraint if exists now_playing_room_mode_check;

alter table now_playing
    add constraint now_playing_room_mode_check
    check (room_mode in ('broadcast', 'together'));
