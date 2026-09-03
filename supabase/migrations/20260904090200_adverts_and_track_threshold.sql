-- Two corrections to what counts.

-- ------------------------------------------------------------------ adverts --
-- An advert on the host's phone used to reach a room as silence, which reads
-- the same as somebody putting their phone down. The two want different
-- answers: one holds the track and waits, the other lets it go. So the room
-- is told which it is.

alter table now_playing
    add column if not exists is_advert boolean not null default false;

-- ------------------------------------------------------- what a track is --
-- Thirty seconds of a song you skipped is not a track you listened to. The
-- count was every session, however brief, which made skipping through an
-- album the fastest way to a large number.

create or replace function counts_as_a_track(credited_ms bigint, duration_ms bigint)
returns boolean
language sql
immutable
as $$
    select case
        -- Nearly a third of the way in, which is past the point where a skip
        -- was a skip rather than a listen.
        when duration_ms > 0 then credited_ms * 10 >= duration_ms * 3
        -- Some players never say how long a track is. Half a minute is the
        -- same judgement without the arithmetic.
        else credited_ms >= 30000
    end;
$$;

create or replace function roll_up_daily()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into daily_listening (user_id, day, credited_ms, track_count)
    values (
        new.user_id,
        (new.started_at at time zone 'UTC')::date,
        new.credited_ms,
        case when counts_as_a_track(new.credited_ms, new.duration_ms) then 1 else 0 end
    )
    on conflict (user_id, day) do update
        set credited_ms = daily_listening.credited_ms + excluded.credited_ms,
            track_count = daily_listening.track_count + excluded.track_count;
    return new;
end;
$$;

-- Deleting has to use the same rule, or removing a track that was never
-- counted would take somebody else's count down with it.
create or replace function roll_down_daily()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    update daily_listening
       set credited_ms = greatest(credited_ms - old.credited_ms, 0),
           track_count = greatest(
               track_count -
               case when counts_as_a_track(old.credited_ms, old.duration_ms) then 1 else 0 end,
               0)
     where user_id = old.user_id
       and day = (old.started_at at time zone 'UTC')::date;

    delete from daily_listening
     where user_id = old.user_id
       and credited_ms = 0
       and track_count = 0;

    return old;
end;
$$;

-- Every count already stored was made under the old rule, so they are all
-- wrong by the new one. Recomputed from the sessions they came from.
update daily_listening d
   set track_count = coalesce(counted.n, 0)
  from (
        select d2.user_id, d2.day,
               (select count(*)
                  from listening_sessions s
                 where s.user_id = d2.user_id
                   and (s.started_at at time zone 'UTC')::date = d2.day
                   and counts_as_a_track(s.credited_ms, s.duration_ms)) as n
          from daily_listening d2
       ) counted
 where d.user_id = counted.user_id
   and d.day = counted.day;

select refresh_leaderboards();
