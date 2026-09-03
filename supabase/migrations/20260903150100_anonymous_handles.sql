-- Handles stop being made out of email addresses.
--
-- The old rule took the part before the @, which meant a leaderboard published
-- everybody's email local part to every signed-in stranger. Nobody chose that,
-- and on a public board it is the one thing that should never be automatic.
--
-- New accounts get a name that says nothing, and the app lets people pick their
-- own. Existing handles are left alone: they are how friends already find each
-- other, and renaming somebody without asking would break that.

create or replace function handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    candidate text;
begin
    loop
        -- Six characters from a 36-letter alphabet: enough room that a
        -- collision is rare, short enough to read out to a friend.
        candidate := 'muse' || lower(
            substring(replace(gen_random_uuid()::text, '-', '') from 1 for 6)
        );
        exit when not exists (select 1 from profiles where handle = candidate);
    end loop;

    insert into profiles (id, handle, display_name)
    values (new.id, candidate, coalesce(new.raw_user_meta_data ->> 'name', ''));

    return new;
end;
$$;
