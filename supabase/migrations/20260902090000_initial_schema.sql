-- Museroom initial schema.
--
-- Shape of the thing: a hot now_playing row per user that friends read, an
-- append-only event log the phone uploads, sessions and daily rollups derived
-- from it, and a precomputed leaderboard nobody has to query the rollups for.

create extension if not exists citext;

create type visibility as enum ('everyone', 'friends', 'nobody');
create type friendship_status as enum ('pending', 'accepted', 'blocked');
create type play_event_type as enum ('PLAY', 'PAUSE', 'SEEK', 'TRACK_CHANGE', 'HEARTBEAT', 'STOP');

-- ---------------------------------------------------------------- identity --

create table profiles (
    id              uuid primary key references auth.users on delete cascade,
    handle          citext not null unique check (handle ~ '^[a-z0-9_]{3,20}$'),
    display_name    text not null default '',
    avatar_url      text,
    -- Who may see what this person is playing right now.
    visibility      visibility not null default 'friends',
    -- Minutes are ranked publicly only if the person agreed to it.
    on_global_board boolean not null default true,
    created_at      timestamptz not null default now()
);

-- ----------------------------------------------------------------- friends --

-- One row per friendship, with the pair stored in a fixed order so two rows can
-- never disagree about whether two people are friends.
create table friendships (
    user_a       uuid not null references profiles on delete cascade,
    user_b       uuid not null references profiles on delete cascade,
    status       friendship_status not null default 'pending',
    requested_by uuid not null references profiles on delete cascade,
    created_at   timestamptz not null default now(),
    primary key (user_a, user_b),
    constraint ordered_pair check (user_a < user_b)
);

create index friendships_user_b_idx on friendships (user_b);

create or replace function are_friends(a uuid, b uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select exists (
        select 1 from friendships f
        where f.status = 'accepted'
          and f.user_a = least(a, b)
          and f.user_b = greatest(a, b)
    );
$$;

-- --------------------------------------------------------------- catalogue --

-- One row per song, shared by every user. A track is resolved once globally,
-- never per person.
create table tracks (
    id               uuid primary key default gen_random_uuid(),
    title            text not null,
    artist           text not null,
    album            text not null default '',
    duration_ms      bigint not null default 0,
    art_url          text,
    spotify_track_id text,
    resolved_at      timestamptz
);

-- The cache that stops us re-resolving the same messy string forever. Several
-- fingerprints can point at one track, which is how a stylised title from one
-- app merges with the plain spelling from another.
create table track_aliases (
    fingerprint text primary key,
    track_id    uuid not null references tracks on delete cascade,
    source      text not null default '',
    confidence  real not null default 1.0,
    seen_count  integer not null default 1,
    created_at  timestamptz not null default now()
);

create index track_aliases_track_idx on track_aliases (track_id);

-- ------------------------------------------------------------ now playing --

-- Overwritten in place, one row per user. This is what a friend list reads, so
-- it stays small. Position is broadcast over realtime between writes rather than
-- persisted every second.
create table now_playing (
    user_id        uuid primary key references profiles on delete cascade,
    track_id       uuid references tracks on delete set null,
    fingerprint    text not null,
    title          text not null,
    artist         text not null,
    album          text not null default '',
    duration_ms    bigint not null default 0,
    art_url        text,
    position_ms    bigint not null default 0,
    is_playing     boolean not null default false,
    source_package text not null default '',
    updated_at     timestamptz not null default now()
);

-- ------------------------------------------------------------------ events --

-- Append-only. The phone reports what happened, never how long it listened.
create table play_events (
    id                bigint generated always as identity primary key,
    user_id           uuid not null references profiles on delete cascade,
    type              play_event_type not null,
    fingerprint       text not null,
    title             text not null,
    artist            text not null,
    album             text not null default '',
    duration_ms       bigint not null default 0,
    source_package    text not null default '',
    position_ms       bigint not null default 0,
    -- Both clocks travel with the event: wall time a server can reason about,
    -- and monotonic time, the only safe basis for measuring an interval.
    client_clock_ms   bigint not null,
    client_elapsed_ms bigint not null,
    server_ts         timestamptz not null default now()
);

create index play_events_user_idx on play_events (user_id, id);

-- Reconstructed stretches of listening with the minutes we will defend.
create table listening_sessions (
    id             bigint generated always as identity primary key,
    user_id        uuid not null references profiles on delete cascade,
    track_id       uuid references tracks on delete set null,
    fingerprint    text not null,
    title          text not null,
    artist         text not null,
    album          text not null default '',
    duration_ms    bigint not null default 0,
    source_package text not null default '',
    started_at     timestamptz not null,
    ended_at       timestamptz not null,
    credited_ms    bigint not null check (credited_ms >= 0),
    created_at     timestamptz not null default now()
);

create index listening_sessions_user_time_idx on listening_sessions (user_id, started_at desc);
create index listening_sessions_fingerprint_idx on listening_sessions (fingerprint);

-- What every board sums over, so a board query never touches the event log.
create table daily_listening (
    user_id     uuid not null references profiles on delete cascade,
    day         date not null,
    credited_ms bigint not null default 0,
    track_count integer not null default 0,
    primary key (user_id, day)
);

-- ------------------------------------------------------------- leaderboard --

-- Precomputed. Reading the top 100 is a hundred rows in rank order, identical
-- for every viewer, rather than a ranking query over every user on every open.
create table leaderboard_entries (
    scope       text not null default 'global',
    period      text not null check (period in ('week', 'month', 'all')),
    period_key  text not null,
    rank        integer not null,
    user_id     uuid not null references profiles on delete cascade,
    credited_ms bigint not null,
    computed_at timestamptz not null default now(),
    primary key (scope, period, period_key, user_id)
);

-- A rank is stored for every user, not only the top hundred, because the first
-- thing someone in 400th place wants is their own number.
create index leaderboard_rank_idx on leaderboard_entries (scope, period, period_key, rank);

create or replace function refresh_leaderboards()
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    week_key  text := to_char(now(), 'IYYY"-W"IW');
    month_key text := to_char(now(), 'YYYY-MM');
begin
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
