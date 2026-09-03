-- Asking to listen along.
--
-- The audio itself never moves: each phone plays the track from its own player.
-- What travels is the ask and the answer, which is the part that makes it feel
-- like joining somebody rather than coincidentally playing the same song.

create type listen_request_status as enum ('pending', 'accepted', 'declined', 'cancelled');

create table listen_requests (
    id              bigint generated always as identity primary key,
    from_user       uuid not null references profiles on delete cascade,
    to_user         uuid not null references profiles on delete cascade,
    status          listen_request_status not null default 'pending',
    -- The track is copied onto the request so it survives the host skipping on.
    title           text not null default '',
    artist          text not null default '',
    fingerprint     text not null default '',
    source_track_id text,
    created_at      timestamptz not null default now(),
    responded_at    timestamptz,
    constraint not_yourself check (from_user <> to_user)
);

create index listen_requests_inbox on listen_requests (to_user, status, created_at desc);
create index listen_requests_outbox on listen_requests (from_user, created_at desc);

alter table listen_requests enable row level security;

-- Only the two people involved ever see it.
create policy listen_requests_read on listen_requests
    for select to authenticated
    using (from_user = (select auth.uid()) or to_user = (select auth.uid()));

-- You may ask someone whose listening you can already see. Asking to join a
-- person who has not shared anything with you is not a request, it is a ping.
create policy listen_requests_ask on listen_requests
    for insert to authenticated
    with check (
        from_user = (select auth.uid())
        and exists (
            select 1 from profiles p
            where p.id = to_user
              and (
                  p.visibility = 'everyone'
                  or (p.visibility = 'friends' and are_friends(p.id, (select auth.uid())))
              )
        )
    );

-- The host answers; the asker may only withdraw.
create policy listen_requests_answer on listen_requests
    for update to authenticated
    using (to_user = (select auth.uid()) or from_user = (select auth.uid()));

/** Requests nobody answered stop being interesting fairly quickly. */
create or replace function expire_listen_requests()
returns void
language sql
security definer
set search_path = public
as $$
    update listen_requests
       set status = 'cancelled', responded_at = now()
     where status = 'pending'
       and created_at < now() - interval '10 minutes';
$$;
