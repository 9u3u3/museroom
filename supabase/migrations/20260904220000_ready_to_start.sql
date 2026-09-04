-- Waiting for people, instead of guessing how long they need.
--
-- A start time two seconds out is a guess, and a guess that comes up short
-- costs a listener the opening of the song: the room begins, they are still
-- fetching, and by the time they have it the first seconds are gone. Guessing
-- generously costs everybody a longer gap between every song instead. Neither
-- is what anybody wants, and both are avoidable, because the listeners know
-- perfectly well when they are ready and were simply never asked.
--
-- So they say so. The host holds the moment open until everybody has the track
-- loaded and waiting, and only then lets go. Losing a second of music is the
-- one outcome this is not allowed to produce, so the wait stretches rather
-- than the start slipping past somebody.
--
-- There is still a ceiling on it. One phone that has gone away must not be
-- able to hold a room silent indefinitely, so patience runs out and the rest
-- of the room begins without it.

alter table now_playing
    -- The fingerprint of the track this phone has fetched and is holding,
    -- silent, waiting for the agreed moment. Null when it is not waiting for
    -- anything, which is almost always.
    add column if not exists room_ready_for text;

/**
 * The roster, now saying who is actually ready.
 *
 * Dropped and recreated because Postgres will not change a function's out
 * columns in place. Still nothing about what anybody is playing: being here,
 * how well you are keeping up, and whether you have the next one loaded.
 */
drop function if exists room_members(uuid);

create function room_members(host uuid)
returns table (
    user_id    uuid,
    handle     text,
    avatar_url text,
    late_ms    integer,
    ready_for  text
)
language sql
stable
security definer
set search_path = public
as $$
    select n.user_id, p.handle, p.avatar_url, n.room_late_ms, n.room_ready_for
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
