-- Finding people in the same room.
--
-- Phones only need to notice each other exists; what they are playing already
-- travels through this database. So the radio carries a short rotating token and
-- nothing else, and only this server can turn a token back into a person.
--
-- Broadcasting a stable identifier would be the easy version and the wrong one.
-- Bluetooth advertisements are trivially logged, so a fixed id lets anyone with
-- a scanner follow a person around a city indefinitely. A token that rotates and
-- expires is worthless to everyone except Museroom, and only while both people
-- have the feature switched on.

alter table profiles
    add column proximity_enabled boolean not null default false;

create table proximity_beacons (
    token      text primary key,
    user_id    uuid not null references profiles on delete cascade,
    expires_at timestamptz not null,
    created_at timestamptz not null default now()
);

create index proximity_beacons_user_idx on proximity_beacons (user_id);
create index proximity_beacons_expiry_idx on proximity_beacons (expires_at);

alter table proximity_beacons enable row level security;

-- A person may publish and withdraw their own beacons. Nobody may read this
-- table, not even their own row: a readable beacon table is a map from tokens to
-- people, which is the one thing this design exists to prevent.
create policy beacons_publish_own on proximity_beacons
    for insert to authenticated with check (user_id = (select auth.uid()));

create policy beacons_withdraw_own on proximity_beacons
    for delete to authenticated using (user_id = (select auth.uid()));

/**
 * Turns tokens overheard on the radio into people.
 *
 * Runs as the definer so it can read beacons that nobody can select directly.
 * A caller learns only about people who are advertising right now, have opted
 * into proximity, are not hidden, and are actually playing something. Tokens
 * that match nothing simply return nothing, so fishing reveals no more than
 * silence does.
 */
create or replace function resolve_nearby(tokens text[])
returns table (
    user_id      uuid,
    handle       text,
    display_name text,
    title        text,
    artist       text,
    duration_ms  bigint,
    position_ms  bigint,
    is_playing   boolean,
    updated_at   timestamptz
)
language sql
stable
security definer
set search_path = public
as $$
    select distinct on (p.id)
           p.id, p.handle::text, p.display_name,
           n.title, n.artist, n.duration_ms, n.position_ms, n.is_playing, n.updated_at
    from proximity_beacons b
    join profiles p on p.id = b.user_id
    join now_playing n on n.user_id = p.id
    where b.token = any (tokens[1:64])
      and b.expires_at > now()
      and p.proximity_enabled
      and p.visibility <> 'nobody'
      and n.is_playing
      -- A row nobody has refreshed in five minutes is a phone that walked away.
      and n.updated_at > now() - interval '5 minutes'
      and p.id <> (select auth.uid())
    order by p.id, n.updated_at desc;
$$;

revoke all on function resolve_nearby(text[]) from public, anon;
grant execute on function resolve_nearby(text[]) to authenticated;

/** Housekeeping, so expired tokens do not accumulate forever. */
create or replace function purge_expired_beacons()
returns void
language sql
security definer
set search_path = public
as $$
    delete from proximity_beacons where expires_at < now() - interval '1 hour';
$$;
