-- Being able to take something back.
--
-- A listening app records whatever happens to be playing, which sooner or later
-- includes something the person did not mean to share. Deleting has to remove it
-- from the totals too, otherwise the minutes quietly survive the row.

create or replace function roll_down_daily()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    update daily_listening
       set credited_ms = greatest(credited_ms - old.credited_ms, 0),
           track_count = greatest(track_count - 1, 0)
     where user_id = old.user_id
       and day = (old.started_at at time zone 'UTC')::date;

    -- A day with nothing left in it should not linger as a zero row.
    delete from daily_listening
     where user_id = old.user_id
       and credited_ms = 0
       and track_count = 0;

    return old;
end;
$$;

create trigger listening_sessions_roll_down
    after delete on listening_sessions
    for each row execute function roll_down_daily();

-- Events stay append-only for everyone else, but a person can erase their own.
-- This cannot inflate anyone's minutes, only remove them, so the anti-cheat
-- clamps are unaffected.
create policy play_events_delete_own on play_events
    for delete to authenticated using (user_id = (select auth.uid()));
