-- Two more rows worth being told about.
--
-- The note left when now_playing joined this publication said everything else
-- was "either private, slow-moving, or read on a schedule where a few seconds
-- costs nothing". That was true of these two at the time, because a request sat
-- as a card on the home screen and the screen was already polling.
--
-- It stops being true once requests live behind a button with a dot on it. A
-- dot that takes fifteen seconds to appear is worse than no dot: somebody looks
-- at a screen that says nothing is waiting, and something is. The dot is a
-- claim about right now, so it has to be told rather than discover.
--
-- Neither table is a firehose. A person is party to a handful of these rows,
-- row-level security is applied to what Realtime forwards, and the client reads
-- the list back through its normal query rather than trusting the payload. What
-- travels here is the news that something changed, not the thing itself.

alter publication supabase_realtime add table listen_requests;
alter publication supabase_realtime add table friendships;
