-- Presence stops being a foreign key.
--
-- Adding following_user with a reference to profiles gave now_playing two
-- foreign keys to the same table, and PostgREST will not guess between them:
-- every query embedding a friend's now_playing started failing with PGRST201.
-- The friends list broke the moment the column landed.
--
-- The reference bought nothing that matters here. Presence is thrown away
-- after two minutes whatever happens, so a row pointing at a deleted profile
-- is a row that was already about to be ignored. Being the only path between
-- these two tables is worth more than the integrity was.

alter table now_playing drop constraint if exists now_playing_following_user_fkey;
