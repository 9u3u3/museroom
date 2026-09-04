-- Starting together, on a time rather than on a message.
--
-- Everything a room does rests on the host being ahead: they hear a song the
-- instant it starts, and a listener cannot hear it until they have been told it
-- exists and have fetched it. That is a second or two, every song, and no
-- amount of correcting afterwards gives back an opening that was never played.
-- Correcting for it is what made a listener either skip the first seconds or
-- hear them twice.
--
-- Every serious multi-room audio system solves this the same way, and none of
-- them solve it by being clever about the network. They agree a fixed latency,
-- put the whole stream behind it, and give every device the same moment to
-- begin. AirPlay holds about half a second, Snapcast a second by default. The
-- delay is not a flaw they failed to remove; it is the thing that buys the
-- sync.
--
-- So the host's own music is held at a track change too, for as long as the
-- room needs, and everybody starts at once. These three columns are that
-- agreement written down.

alter table now_playing
    -- The moment, in the database's clock, that everybody begins this track.
    -- Null when nothing is scheduled, which is the ordinary case: a room with
    -- nobody in it never makes anybody wait.
    add column if not exists starts_at timestamptz,

    -- Where to begin. Usually a few hundred milliseconds rather than zero,
    -- because the host's player has already moved by the time it is stopped,
    -- and the room joins them there rather than rewinding them.
    add column if not exists start_position_ms bigint not null default 0,

    -- A listener's report of how late it was for the last start it was given,
    -- in milliseconds, or a negative number for how early it was ready. This
    -- is what lets the wait shrink on a fast room and grow on a slow one
    -- instead of being a number somebody guessed once.
    add column if not exists room_late_ms integer;

/**
 * The roster, now carrying how well each listener is keeping up.
 *
 * Dropped and recreated rather than replaced: Postgres will not change a
 * function's out columns in place, and says so by failing outright.
 *
 * Lateness is only ever about the room. It says nothing about what anybody is
 * playing, which is the same line the rest of this function holds.
 */
drop function if exists room_members(uuid);

create function room_members(host uuid)
returns table (
    user_id    uuid,
    handle     text,
    avatar_url text,
    late_ms    integer
)
language sql
stable
security definer
set search_path = public
as $$
    select n.user_id, p.handle, p.avatar_url, n.room_late_ms
      from now_playing n
      join profiles p on p.id = n.user_id
     where n.following_user = host
       and n.following_since > now() - interval '2 minutes'
       and auth.uid() is not null
       and not is_blocked(host, auth.uid())
       and not is_blocked(n.user_id, auth.uid())
       and (
            host = auth.uid()
            or exists (
                select 1 from now_playing mine
                 where mine.user_id = auth.uid()
                   and mine.following_user = host
                   and mine.following_since > now() - interval '2 minutes'
            )
       )
     order by n.following_since asc;
$$;

revoke all on function room_members(uuid) from public, anon;
grant execute on function room_members(uuid) to authenticated;
