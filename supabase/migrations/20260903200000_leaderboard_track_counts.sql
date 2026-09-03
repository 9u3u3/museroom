-- The board shows minutes and nothing else. A rank of "56m" answers how much
-- somebody listened but not how, and the site's own mock already promises a
-- track count next to it; the real board should keep that promise.

alter table leaderboard_entries
    add column if not exists track_count bigint not null default 0;

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
    insert into leaderboard_entries (scope, period, period_key, rank, user_id, credited_ms, track_count)
    select 'global', 'day', day_key,
           row_number() over (order by sum(least(d.credited_ms, 57600000)) desc, min(p.created_at) asc),
           d.user_id, sum(least(d.credited_ms, 57600000)), sum(d.track_count)
    from daily_listening d
    join profiles p on p.id = d.user_id
    where p.on_global_board and d.day = (now() at time zone 'UTC')::date
    group by d.user_id;

    delete from leaderboard_entries where period = 'week' and period_key = week_key;
    insert into leaderboard_entries (scope, period, period_key, rank, user_id, credited_ms, track_count)
    select 'global', 'week', week_key,
           row_number() over (order by sum(least(d.credited_ms, 57600000)) desc, min(p.created_at) asc),
           d.user_id, sum(least(d.credited_ms, 57600000)), sum(d.track_count)
    from daily_listening d
    join profiles p on p.id = d.user_id
    where p.on_global_board and d.day >= date_trunc('week', now())::date
    group by d.user_id;

    delete from leaderboard_entries where period = 'month' and period_key = month_key;
    insert into leaderboard_entries (scope, period, period_key, rank, user_id, credited_ms, track_count)
    select 'global', 'month', month_key,
           row_number() over (order by sum(least(d.credited_ms, 57600000)) desc, min(p.created_at) asc),
           d.user_id, sum(least(d.credited_ms, 57600000)), sum(d.track_count)
    from daily_listening d
    join profiles p on p.id = d.user_id
    where p.on_global_board and d.day >= date_trunc('month', now())::date
    group by d.user_id;

    delete from leaderboard_entries where period = 'all' and period_key = 'all';
    insert into leaderboard_entries (scope, period, period_key, rank, user_id, credited_ms, track_count)
    select 'global', 'all', 'all',
           row_number() over (order by sum(least(d.credited_ms, 57600000)) desc, min(p.created_at) asc),
           d.user_id, sum(least(d.credited_ms, 57600000)), sum(d.track_count)
    from daily_listening d
    join profiles p on p.id = d.user_id
    where p.on_global_board
    group by d.user_id;
end;
$$;
