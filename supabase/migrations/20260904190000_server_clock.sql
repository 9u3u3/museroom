-- A clock both phones can agree on.
--
-- Everything about a room rests on one sentence: they were at 2:15 at this
-- moment. The moment was written by one phone and read by another, each using
-- its own idea of the time — and two Android phones are routinely a second
-- apart. Under a wide tolerance that skew was simply absorbed. It stops being
-- absorbable the moment you try to hold two players together closely: a
-- permanent half-second of disagreement becomes a permanent correction, which
-- is a tempo difference somebody can hear.
--
-- So neither phone's clock is used. Both ask the database what time it is,
-- measure how long the asking took, and keep the difference. One clock, two
-- readers.

create or replace function server_now()
returns timestamptz
language sql
stable
as $$
    -- clock_timestamp rather than now(), which is the transaction's start and
    -- would fold whatever the statement itself cost into the answer.
    select clock_timestamp();
$$;

revoke all on function server_now() from public;
grant execute on function server_now() to authenticated, anon;
