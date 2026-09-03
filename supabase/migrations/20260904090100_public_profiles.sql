-- Somebody else's page.
--
-- Until now a person was a name on a row. You could see what they were playing
-- and nothing about them, which makes a leaderboard a list of strangers.
--
-- What this returns is only what was already public: the name and picture
-- everybody can see, the totals that are already on the board, and the likes
-- other people gave. Listening history stays private, because the privacy
-- policy says it is, and one convenient screen is not a reason to break that.

-- Replacing it changes the columns it returns, which Postgres will not do in
-- place.
drop function if exists public_profile(uuid);

create function public_profile(target uuid)
returns table (
    id             uuid,
    handle         text,
    avatar_url     text,
    created_at     timestamptz,
    likes_received integer,
    on_global_board boolean,
    join_mode      text,
    credited_ms    bigint,
    track_count    bigint,
    rank           integer,
    like_rank      integer,
    is_friend      boolean,
    shares_with_me boolean,
    likes_from_me  bigint
)
language sql
stable
security definer
set search_path = public
as $$
    select p.id,
           p.handle,
           -- Deliberately not display_name. That is whatever Google handed
           -- over, which for most people is their real name, and the whole
           -- point of choosing a username was that a stranger sees the
           -- username and nothing else.
           p.avatar_url,
           p.created_at,
           p.likes_received,
           p.on_global_board,
           p.join_mode,
           -- Opting out of the board means the totals are nobody's business,
           -- and that has to hold here too or the opt-out is decorative.
           case when p.on_global_board then coalesce(b.credited_ms, 0) end,
           case when p.on_global_board then coalesce(b.track_count, 0) end,
           case when p.on_global_board then b.rank end,
           case when p.on_global_board then b.like_rank end,
           are_friends(p.id, auth.uid()),
           (p.visibility = 'everyone'
            or (p.visibility = 'friends' and are_friends(p.id, auth.uid()))),
           coalesce(mine.n, 0)
      from profiles p
      left join leaderboard_entries b
             on b.user_id = p.id and b.period = 'all' and b.period_key = 'all'
      left join lateral (
           select count(*) as n from likes l
            where l.liked = p.id and l.liker = auth.uid()
      ) mine on true
     where p.id = target
       and auth.uid() is not null
       -- A blocked person has no page, in either direction.
       and not is_blocked(p.id, auth.uid());
$$;

revoke all on function public_profile(uuid) from public, anon;
grant execute on function public_profile(uuid) to authenticated;
