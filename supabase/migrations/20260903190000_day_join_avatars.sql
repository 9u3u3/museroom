-- Three things people asked for, and one they will only notice if it is wrong.

-- ------------------------------------------------------------------- a day --
-- A week is a long time to wait to see a number move. Today is the period most
-- people actually check, so it exists now, and "all time" becomes the default
-- because that is the one a leaderboard is for.

alter table leaderboard_entries drop constraint if exists leaderboard_entries_period_check;
alter table leaderboard_entries
    add constraint leaderboard_entries_period_check
    check (period in ('day', 'week', 'month', 'all'));

create or replace function refresh_leaderboards()
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    day_key   text := to_char(now(), 'YYYY-MM-DD');
    week_key  text := to_char(now(), 'IYYY"-W"IW');
    month_key text := to_char(now(), 'YYYY-MM');
begin
    delete from leaderboard_entries where period = 'day' and period_key = day_key;
    insert into leaderboard_entries (scope, period, period_key, rank, user_id, credited_ms)
    select 'global', 'day', day_key,
           row_number() over (order by sum(least(d.credited_ms, 57600000)) desc, min(p.created_at) asc),
           d.user_id, sum(least(d.credited_ms, 57600000))
    from daily_listening d
    join profiles p on p.id = d.user_id
    where p.on_global_board and d.day = (now() at time zone 'UTC')::date
    group by d.user_id;

    delete from leaderboard_entries where period = 'week' and period_key = week_key;
    insert into leaderboard_entries (scope, period, period_key, rank, user_id, credited_ms)
    select 'global', 'week', week_key,
           row_number() over (order by sum(least(d.credited_ms, 57600000)) desc, min(p.created_at) asc),
           d.user_id, sum(least(d.credited_ms, 57600000))
    from daily_listening d
    join profiles p on p.id = d.user_id
    where p.on_global_board and d.day >= date_trunc('week', now())::date
    group by d.user_id;

    delete from leaderboard_entries where period = 'month' and period_key = month_key;
    insert into leaderboard_entries (scope, period, period_key, rank, user_id, credited_ms)
    select 'global', 'month', month_key,
           row_number() over (order by sum(least(d.credited_ms, 57600000)) desc, min(p.created_at) asc),
           d.user_id, sum(least(d.credited_ms, 57600000))
    from daily_listening d
    join profiles p on p.id = d.user_id
    where p.on_global_board and d.day >= date_trunc('month', now())::date
    group by d.user_id;

    delete from leaderboard_entries where period = 'all' and period_key = 'all';
    insert into leaderboard_entries (scope, period, period_key, rank, user_id, credited_ms)
    select 'global', 'all', 'all',
           row_number() over (order by sum(least(d.credited_ms, 57600000)) desc, min(p.created_at) asc),
           d.user_id, sum(least(d.credited_ms, 57600000))
    from daily_listening d
    join profiles p on p.id = d.user_id
    where p.on_global_board
    group by d.user_id;
end;
$$;

-- ------------------------------------------------------------- who may join --
-- Asking is right when a room is a small thing between two people. It is
-- friction when somebody wants their music open to whoever turns up. Both are
-- reasonable, so it is a setting rather than a decision made for everybody.

alter table profiles
    add column if not exists join_mode text not null default 'ask';
alter table profiles drop constraint if exists profiles_join_mode_check;
alter table profiles
    add constraint profiles_join_mode_check check (join_mode in ('ask', 'open'));

-- Nearby has to say so too, or the same person is open on one screen and not
-- on the other. Dropped rather than replaced: Postgres will not change a
-- function's return columns in place.
drop function if exists resolve_nearby(text[]);

create function resolve_nearby(tokens text[])
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
    order by p.id, n.updated_at desc;
$$;

revoke all on function resolve_nearby(text[]) from public, anon;
grant execute on function resolve_nearby(text[]) to authenticated;
