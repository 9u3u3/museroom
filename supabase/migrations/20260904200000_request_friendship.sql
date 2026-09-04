-- Asking twice must not undo what the first ask achieved.
--
-- The pair is stored in one fixed order under one primary key, so "send a
-- friend request" and "overwrite the friendship" were the same write. The
-- client inserted with on_conflict and Prefer: resolution=merge-duplicates,
-- which meant tapping Add on somebody you were already friends with replaced
-- the accepted row with a pending one, silently, for both people. There was no
-- duplicate row to notice afterwards, only a downgraded one.
--
-- So the client loses the reach to write this table at all. Deciding whether an
-- ask is a new request, a duplicate, or an answer to somebody else's request
-- means reading what is already there, and a client that can be edited is not
-- the place to decide it. Same reasoning as the likes table: the insert policy
-- is gone on purpose, and everything goes through the function below.

create or replace function request_friendship(target uuid)
returns text
language plpgsql
security definer
set search_path = public
as $$
declare
    me      uuid := auth.uid();
    a       uuid;
    b       uuid;
    current friendship_status;
    asker   uuid;
    written integer;
begin
    if me is null or target is null or target = me then
        return 'self';
    end if;

    if is_blocked(me, target) then
        return 'blocked';
    end if;

    a := least(me, target);
    b := greatest(me, target);

    select status, requested_by
      into current, asker
      from friendships
     where user_a = a and user_b = b;

    if found then
        if current = 'accepted' then
            return 'already_friends';
        elsif current = 'pending' and asker = me then
            return 'already_requested';
        elsif current = 'pending' then
            -- They asked first. The answer to this is Accept, and offering
            -- another request instead would leave both people waiting on
            -- each other.
            return 'they_asked_you';
        end if;
    end if;

    insert into friendships (user_a, user_b, status, requested_by)
    values (a, b, 'pending', me)
    on conflict (user_a, user_b) do nothing;

    -- Two taps racing land here together. The one that lost wrote nothing, and
    -- saying "sent" to it would be a small lie about a row it did not create.
    get diagnostics written = row_count;
    if written = 0 then
        return 'already_requested';
    end if;

    return 'sent';
end;
$$;

revoke all on function request_friendship(uuid) from public, anon;
grant execute on function request_friendship(uuid) to authenticated;

-- Nothing may insert here but the function above.
drop policy if exists friendships_request on friendships;

/**
 * Answering, and only answering.
 *
 * The old policy had a using clause and no with check at all, so an update was
 * free to rewrite the pair or move requested_by to somebody else. Narrowed to
 * the one move a person is allowed to make: a pending row of theirs becoming
 * accepted. An accepted row is no longer updatable by anybody, which is the
 * other half of making the demotion impossible — unfriending is a delete.
 */
drop policy if exists friendships_respond on friendships;
create policy friendships_respond on friendships
    for update to authenticated
    using (
        (user_a = (select auth.uid()) or user_b = (select auth.uid()))
        and status = 'pending'
    )
    with check (status = 'accepted');

/**
 * The part a policy cannot state.
 *
 * A with check clause sees only the row being written, so it can say what the
 * new values must be and never that they must match the old ones. Writing
 * user_a = user_a there looks like a guard and is a tautology. This is where
 * that guarantee actually lives.
 */
create or replace function friendships_stay_themselves()
returns trigger
language plpgsql
as $$
begin
    if new.user_a <> old.user_a
       or new.user_b <> old.user_b
       or new.requested_by <> old.requested_by then
        raise exception 'A friendship may be answered, not rewritten.';
    end if;
    return new;
end;
$$;

drop trigger if exists friendships_stay_themselves on friendships;
create trigger friendships_stay_themselves
    before update on friendships
    for each row execute function friendships_stay_themselves();
