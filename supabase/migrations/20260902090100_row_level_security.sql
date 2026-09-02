-- Who may read what.
--
-- Visibility is decided here rather than in the app. A client that forgets to
-- filter gets nothing back instead of leaking someone's listening.

alter table profiles            enable row level security;
alter table friendships         enable row level security;
alter table tracks              enable row level security;
alter table track_aliases       enable row level security;
alter table now_playing         enable row level security;
alter table play_events         enable row level security;
alter table listening_sessions  enable row level security;
alter table daily_listening     enable row level security;
alter table leaderboard_entries enable row level security;

-- Profiles are public to signed-in users: a leaderboard and a friend search both
-- need to show a handle. Nothing sensitive lives on this table.
create policy profiles_read on profiles
    for select to authenticated using (true);

create policy profiles_insert_self on profiles
    for insert to authenticated with check (id = (select auth.uid()));

create policy profiles_update_self on profiles
    for update to authenticated using (id = (select auth.uid()));

-- A friendship is visible to the two people in it and nobody else.
create policy friendships_read on friendships
    for select to authenticated
    using (user_a = (select auth.uid()) or user_b = (select auth.uid()));

create policy friendships_request on friendships
    for insert to authenticated
    with check (
        requested_by = (select auth.uid())
        and (select auth.uid()) in (user_a, user_b)
    );

create policy friendships_respond on friendships
    for update to authenticated
    using (user_a = (select auth.uid()) or user_b = (select auth.uid()));

create policy friendships_remove on friendships
    for delete to authenticated
    using (user_a = (select auth.uid()) or user_b = (select auth.uid()));

-- The catalogue is shared. Anyone signed in may read it, and may add a track or
-- an alias, because resolution happens once globally and everyone benefits.
create policy tracks_read on tracks
    for select to authenticated using (true);

create policy tracks_contribute on tracks
    for insert to authenticated with check (true);

create policy aliases_read on track_aliases
    for select to authenticated using (true);

create policy aliases_contribute on track_aliases
    for insert to authenticated with check (true);

-- The one that matters. Your own row always; a friend's row if they share with
-- friends; anyone's row if they share with everyone.
create policy now_playing_read on now_playing
    for select to authenticated
    using (
        user_id = (select auth.uid())
        or exists (
            select 1 from profiles p
            where p.id = now_playing.user_id
              and (
                  p.visibility = 'everyone'
                  or (p.visibility = 'friends' and are_friends(p.id, (select auth.uid())))
              )
        )
    );

create policy now_playing_write on now_playing
    for all to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));

-- Events are yours, and append-only. No update or delete policy exists, so the
-- trail cannot be rewritten after the fact.
create policy play_events_read_own on play_events
    for select to authenticated using (user_id = (select auth.uid()));

create policy play_events_append on play_events
    for insert to authenticated with check (user_id = (select auth.uid()));

create policy sessions_read_own on listening_sessions
    for select to authenticated using (user_id = (select auth.uid()));

create policy sessions_insert_own on listening_sessions
    for insert to authenticated with check (user_id = (select auth.uid()));

-- Deleting is allowed because "delete my history" has to actually work.
create policy sessions_delete_own on listening_sessions
    for delete to authenticated using (user_id = (select auth.uid()));

create policy daily_read_own on daily_listening
    for select to authenticated using (user_id = (select auth.uid()));

create policy daily_write_own on daily_listening
    for all to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));

-- The board is the one thing everyone sees the same way. Only the refresh
-- function writes it, so there is no client write policy at all.
create policy leaderboard_read on leaderboard_entries
    for select to authenticated using (true);

-- ---------------------------------------------------------------- triggers --

-- Every account gets a profile and a handle immediately, so nothing downstream
-- has to cope with a user who has no row.
create or replace function handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    base      text;
    candidate text;
    suffix    integer := 0;
begin
    base := lower(regexp_replace(coalesce(split_part(new.email, '@', 1), ''), '[^a-zA-Z0-9_]', '', 'g'));
    if length(base) < 3 then
        base := 'muse' || base;
    end if;
    base := left(base, 16);

    candidate := base;
    while exists (select 1 from profiles where handle = candidate) loop
        suffix := suffix + 1;
        candidate := base || suffix::text;
    end loop;

    insert into profiles (id, handle, display_name)
    values (new.id, candidate, coalesce(new.raw_user_meta_data ->> 'name', ''));

    return new;
end;
$$;

create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function handle_new_user();

-- Rolling up on write keeps the board's source current without a job to babysit.
-- Days are UTC so that one global leaderboard has one definition of "this week".
create or replace function roll_up_daily()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into daily_listening (user_id, day, credited_ms, track_count)
    values (new.user_id, (new.started_at at time zone 'UTC')::date, new.credited_ms, 1)
    on conflict (user_id, day) do update
        set credited_ms = daily_listening.credited_ms + excluded.credited_ms,
            track_count = daily_listening.track_count + 1;
    return new;
end;
$$;

create trigger listening_sessions_roll_up
    after insert on listening_sessions
    for each row execute function roll_up_daily();
