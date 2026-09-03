-- Who is in the room with you.
--
-- A joiner has nothing playing of their own: Museroom plays their music itself,
-- and that is not a media session anybody can see. So being in somebody's room
-- has to be said out loud rather than inferred from what is playing.
--
-- Its own timestamp, deliberately. Bumping updated_at would make a joiner's
-- stale track look fresh to everyone reading their row for a different reason.

alter table now_playing
    add column if not exists following_user uuid references profiles on delete set null,
    add column if not exists following_since timestamptz;

create index if not exists now_playing_following
    on now_playing (following_user, following_since desc)
    where following_user is not null;
