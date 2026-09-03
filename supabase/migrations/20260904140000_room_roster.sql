-- Who else is in here.
--
-- A joiner was told "Nobody is in your room", which is true and useless: they
-- are not in their own room, they are in somebody else's, along with whoever
-- else turned up. The host could see the roster and nobody else could.
--
-- Read through a function rather than by opening the rows up, because the
-- rows say what everybody is playing and only the fact of being here should
-- travel. Blocking still holds in both directions.

create or replace function room_members(host uuid)
returns table (
    user_id    uuid,
    handle     text,
    avatar_url text
)
language sql
stable
security definer
set search_path = public
as $$
    select n.user_id, p.handle, p.avatar_url
      from now_playing n
      join profiles p on p.id = n.user_id
     where n.following_user = host
       -- Same two minutes the host's own list uses: anything older is
       -- somebody who closed the app rather than somebody who left.
       and n.following_since > now() - interval '2 minutes'
       and auth.uid() is not null
       and not is_blocked(host, auth.uid())
       and not is_blocked(n.user_id, auth.uid())
       -- The host, or somebody who is in the room themselves. A roster is
       -- not something a passer-by gets to read.
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
