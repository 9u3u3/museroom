-- Three things a public leaderboard needs before strangers are on it.
--
-- Usernames and pictures are visible to everyone signed in, rooms can be
-- walked into, and requests can be sent by anybody. Until now the only answer
-- to somebody unpleasant was to ignore them for ever.

-- --------------------------------------------------------------- blocking --

create table if not exists blocks (
    blocker    uuid not null references profiles on delete cascade,
    blocked    uuid not null references profiles on delete cascade,
    created_at timestamptz not null default now(),
    primary key (blocker, blocked),
    constraint not_yourself check (blocker <> blocked)
);

create index if not exists blocks_by_blocked on blocks (blocked);

alter table blocks enable row level security;

-- Your own list, and nobody else's. Deliberately not readable by the person
-- blocked: being told you have been blocked is an invitation to make another
-- account, and it is not information they are owed.
drop policy if exists blocks_read_own on blocks;
create policy blocks_read_own on blocks
    for select to authenticated using (blocker = (select auth.uid()));

drop policy if exists blocks_insert_own on blocks;
create policy blocks_insert_own on blocks
    for insert to authenticated with check (blocker = (select auth.uid()));

drop policy if exists blocks_delete_own on blocks;
create policy blocks_delete_own on blocks
    for delete to authenticated using (blocker = (select auth.uid()));

/**
 * Whether either of two people has blocked the other.
 *
 * Symmetric on purpose. Blocking somebody should stop them reaching you and
 * stop you seeing them, without either side having to be told which way round
 * it was.
 */
create or replace function is_blocked(a uuid, b uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select exists (
        select 1 from blocks
        where (blocker = a and blocked = b)
           or (blocker = b and blocked = a)
    );
$$;

revoke all on function is_blocked(uuid, uuid) from public, anon;
grant execute on function is_blocked(uuid, uuid) to authenticated;

-- Blocking has to be enforced where the data is read, not where it is drawn.
-- Hiding somebody in the app leaves every one of these rows readable by
-- anybody who asks the API directly.

drop policy if exists now_playing_read on now_playing;
create policy now_playing_read on now_playing
    for select to authenticated
    using (
        user_id = (select auth.uid())
        or (
            not is_blocked(now_playing.user_id, (select auth.uid()))
            and exists (
                select 1 from profiles p
                where p.id = now_playing.user_id
                  and (
                      p.visibility = 'everyone'
                      or (p.visibility = 'friends' and are_friends(p.id, (select auth.uid())))
                  )
            )
        )
    );

drop policy if exists listen_requests_ask on listen_requests;
create policy listen_requests_ask on listen_requests
    for insert to authenticated
    with check (
        from_user = (select auth.uid())
        and not is_blocked(to_user, (select auth.uid()))
        and exists (
            select 1 from profiles p
            where p.id = to_user
              and (
                  p.visibility = 'everyone'
                  or (p.visibility = 'friends' and are_friends(p.id, (select auth.uid())))
              )
        )
    );

drop policy if exists friendships_request on friendships;
create policy friendships_request on friendships
    for insert to authenticated
    with check (
        requested_by = (select auth.uid())
        and (select auth.uid()) in (user_a, user_b)
        and not is_blocked(user_a, user_b)
    );

/**
 * Block somebody, and undo whatever already connected you.
 *
 * A block that leaves the friendship standing is not a block: they would still
 * be on your list, still able to see you under a friends-only setting. So the
 * friendship goes and any request between you goes with it.
 */
create or replace function block_user(target uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    me uuid := auth.uid();
begin
    if me is null or target is null or me = target then
        return;
    end if;

    insert into blocks (blocker, blocked) values (me, target)
    on conflict do nothing;

    delete from friendships
     where (user_a = least(me, target) and user_b = greatest(me, target));

    delete from listen_requests
     where (from_user = me and to_user = target)
        or (from_user = target and to_user = me);
end;
$$;

revoke all on function block_user(uuid) from public, anon;
grant execute on function block_user(uuid) to authenticated;

-- --------------------------------------------------------------- reports --

create table if not exists reports (
    id          bigint generated always as identity primary key,
    reporter    uuid not null references profiles on delete cascade,
    reported    uuid not null references profiles on delete cascade,
    reason      text not null default '',
    created_at  timestamptz not null default now(),
    constraint not_yourself check (reporter <> reported)
);

create index if not exists reports_by_reported on reports (reported, created_at desc);

alter table reports enable row level security;

-- Write-only from the app's point of view. A reporter may file one and see
-- their own; nobody can read what anybody else has reported about them, which
-- is the whole point of a report.
drop policy if exists reports_insert_own on reports;
create policy reports_insert_own on reports
    for insert to authenticated with check (reporter = (select auth.uid()));

drop policy if exists reports_read_own on reports;
create policy reports_read_own on reports
    for select to authenticated using (reporter = (select auth.uid()));

-- ------------------------------------------------------ deleting yourself --

/**
 * Everything, gone, without having to email anybody.
 *
 * The profile row is the root: play events, sessions, daily totals, board
 * entries, friendships, requests, blocks and now-playing all cascade from it.
 * The auth user goes too, which is what makes this a deletion rather than a
 * very thorough clearing out.
 */
create or replace function delete_my_account()
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    me uuid := auth.uid();
begin
    if me is null then
        return;
    end if;
    delete from profiles where id = me;
    delete from auth.users where id = me;
end;
$$;

revoke all on function delete_my_account() from public, anon;
grant execute on function delete_my_account() to authenticated;
