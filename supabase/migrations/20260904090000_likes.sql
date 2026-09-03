-- Saying you like what somebody is playing.
--
-- Minutes measure endurance, not taste: the person top of the board is the one
-- who left something running. A like is the other half of that, and it is the
-- only number here that other people decide.

create table if not exists likes (
    liker       uuid not null references profiles on delete cascade,
    liked       uuid not null references profiles on delete cascade,
    -- The track, as the person playing it published it. Kept so the same song
    -- cannot be liked twice, and so a profile can say what was liked.
    fingerprint text not null,
    title       text not null default '',
    artist      text not null default '',
    created_at  timestamptz not null default now(),
    primary key (liker, liked, fingerprint),
    constraint not_yourself check (liker <> liked)
);

create index if not exists likes_by_liked on likes (liked, created_at desc);

alter table likes enable row level security;

-- Yours, and the ones about you. Nobody can read who likes anybody else,
-- which keeps a like a message between two people rather than a public list.
drop policy if exists likes_read_own on likes;
create policy likes_read_own on likes
    for select to authenticated
    using (liker = (select auth.uid()) or liked = (select auth.uid()));

-- No insert policy on purpose. Every like goes through like_track below, which
-- decides for itself what was playing; a client that could write this row
-- directly could like a track nobody played, as often as it liked.
drop policy if exists likes_delete_own on likes;
create policy likes_delete_own on likes
    for delete to authenticated using (liker = (select auth.uid()));

-- ------------------------------------------------------------- the running total --

alter table profiles
    add column if not exists likes_received integer not null default 0;

create or replace function likes_count_changed()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    if tg_op = 'INSERT' then
        update profiles set likes_received = likes_received + 1 where id = new.liked;
        return new;
    else
        update profiles
           set likes_received = greatest(likes_received - 1, 0)
         where id = old.liked;
        return old;
    end if;
end;
$$;

drop trigger if exists likes_roll_up on likes;
create trigger likes_roll_up
    after insert or delete on likes
    for each row execute function likes_count_changed();

-- Whatever is already there, counted once, so the column starts out true.
update profiles p
   set likes_received = coalesce(
       (select count(*) from likes l where l.liked = p.id), 0);

-- ------------------------------------------------------------------ liking --

/**
 * Like whatever somebody is playing right now.
 *
 * The client says who, never what. The track is read here, from the row the
 * other person published, so a like always refers to something they were
 * actually playing at the moment it was sent — and the same song cannot be
 * liked twice however many times the button is pressed.
 *
 * Returns true only when this added something.
 */
create or replace function like_track(target uuid)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
    me      uuid := auth.uid();
    playing now_playing%rowtype;
begin
    if me is null or target is null or me = target then
        return false;
    end if;
    if is_blocked(me, target) then
        return false;
    end if;

    -- Only what you are allowed to see. Somebody sharing with friends only is
    -- not likeable by a stranger who guessed their id.
    if not exists (
        select 1 from profiles p
        where p.id = target
          and (p.visibility = 'everyone'
               or (p.visibility = 'friends' and are_friends(p.id, me)))
    ) then
        return false;
    end if;

    select * into playing from now_playing where user_id = target;
    if not found
       or not playing.is_playing
       or coalesce(playing.title, '') = ''
       or playing.updated_at < now() - interval '5 minutes' then
        return false;
    end if;

    insert into likes (liker, liked, fingerprint, title, artist)
    values (me, target, playing.fingerprint, playing.title, coalesce(playing.artist, ''))
    on conflict do nothing;

    return found;
end;
$$;

revoke all on function like_track(uuid) from public, anon;
grant execute on function like_track(uuid) to authenticated;

/** Taking it back, for whatever they are playing now. */
create or replace function unlike_track(target uuid)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
    me      uuid := auth.uid();
    playing now_playing%rowtype;
begin
    if me is null or target is null then
        return false;
    end if;
    select * into playing from now_playing where user_id = target;
    if not found then
        return false;
    end if;
    delete from likes
     where liker = me and liked = target and fingerprint = playing.fingerprint;
    return found;
end;
$$;

revoke all on function unlike_track(uuid) from public, anon;
grant execute on function unlike_track(uuid) to authenticated;

-- ------------------------------------------------------------- the board --

alter table leaderboard_entries
    add column if not exists likes bigint not null default 0;

-- A second order over the same rows. Two ranks in one table rather than two
-- tables, because they are the same hundred people sorted two ways.
alter table leaderboard_entries
    add column if not exists like_rank integer not null default 0;

create or replace function refresh_leaderboards()
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    window_ record;
begin
    for window_ in
        select * from (values
            ('day'::text,   to_char(now(), 'YYYY-MM-DD'),  (now() at time zone 'UTC')::date),
            ('week'::text,  to_char(now(), 'IYYY"-W"IW'),  date_trunc('week', now())::date),
            ('month'::text, to_char(now(), 'YYYY-MM'),     date_trunc('month', now())::date),
            ('all'::text,   'all',                         date '0001-01-01')
        ) as t(period, key, since)
    loop
        delete from leaderboard_entries
         where period = window_.period and period_key = window_.key;

        insert into leaderboard_entries
            (scope, period, period_key, rank, like_rank,
             user_id, credited_ms, track_count, likes)
        with totals as (
            select d.user_id,
                   sum(least(d.credited_ms, 57600000)) as credited_ms,
                   sum(d.track_count)                  as track_count,
                   min(pr.created_at)                  as joined_at
              from daily_listening d
              join profiles pr on pr.id = d.user_id
             where pr.on_global_board
               and d.day >= window_.since
             group by d.user_id
        ),
        hearts as (
            select l.liked as user_id, count(*) as likes
              from likes l
             where l.created_at >= window_.since
             group by l.liked
        )
        select 'global', window_.period, window_.key,
               row_number() over (
                   order by t.credited_ms desc, t.joined_at asc),
               row_number() over (
                   order by coalesce(h.likes, 0) desc, t.credited_ms desc, t.joined_at asc),
               t.user_id, t.credited_ms, t.track_count, coalesce(h.likes, 0)
          from totals t
          left join hearts h on h.user_id = t.user_id;
    end loop;
end;
$$;
