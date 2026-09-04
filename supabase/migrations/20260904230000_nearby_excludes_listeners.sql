-- Somebody in a room is not somebody to join.
--
-- Nearby answers one question: who around me is playing something I could ask
-- to listen along with. A person who is themselves listening along with
-- somebody else is not an answer to it. Their phone is playing the host's
-- music, so they look exactly like a host from the outside, and asking to join
-- them would put you a second-hand copy of a room you could have joined
-- directly.
--
-- It is also how people were seeing their own name in the list. A listener's
-- phone reports the room's track as its own playback, quite correctly, and
-- some Android radios hear their own advertisement and report it as a
-- sighting. The token then resolves to a real person who is really playing
-- something: themselves.
--
-- The freshness window is the same two minutes the roster uses, so somebody
-- who closed the app rather than leaving the room comes back to Nearby on
-- their own.

create or replace function resolve_nearby(tokens text[])
returns table (
    user_id         uuid,
    handle          text,
    display_name    text,
    avatar_url      text,
    join_mode       text,
    title           text,
    artist          text,
    duration_ms     bigint,
    position_ms     bigint,
    is_playing      boolean,
    updated_at      timestamptz,
    source_track_id text,
    source_package  text
)
language sql
stable
security definer
set search_path = public
as $$
    select distinct on (p.id)
           p.id, p.handle::text, p.display_name, p.avatar_url, p.join_mode,
           n.title, n.artist, n.duration_ms, n.position_ms, n.is_playing, n.updated_at,
           n.source_track_id, n.source_package
    from proximity_beacons b
    join profiles p on p.id = b.user_id
    join now_playing n on n.user_id = p.id
    where b.token = any (tokens[1:64])
      and b.expires_at > now()
      and p.proximity_enabled
      and p.visibility <> 'nobody'
      and n.is_playing
      and n.updated_at > now() - interval '5 minutes'
      and p.id <> (select auth.uid())
      -- In somebody else's room, so not a room to join.
      and (
          n.following_user is null
          or n.following_since is null
          or n.following_since <= now() - interval '2 minutes'
      )
    order by p.id, n.updated_at desc;
$$;

revoke all on function resolve_nearby(text[]) from public, anon;
grant execute on function resolve_nearby(text[]) to authenticated;
