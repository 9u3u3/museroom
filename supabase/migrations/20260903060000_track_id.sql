-- The player's own identifier for the track, where it publishes one.
--
-- Without it, sending a friend to a song means sending them to a search for its
-- title and hoping they tap the right result. With it they land on the exact
-- track, which is the difference between "close to automatic" and automatic.
alter table now_playing
    add column source_track_id text;
