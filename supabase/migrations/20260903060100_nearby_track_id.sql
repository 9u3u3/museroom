-- resolve_nearby now hands back the exact track, so a nearby listener can be
-- joined the same way a friend can.
create or replace function resolve_nearby(tokens text[])
returns table (
    user_id         uuid,
    handle          text,
    display_name    text,
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
           p.id, p.handle::text, p.display_name,
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
    order by p.id, n.updated_at desc;
$$;

revoke all on function resolve_nearby(text[]) from public, anon;
grant execute on function resolve_nearby(text[]) to authenticated;
