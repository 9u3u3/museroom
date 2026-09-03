-- Push instead of poll, for the one row that has to be timely.
--
-- Following somebody works by asking every couple of seconds whether they are
-- still on the same track. On its own that is a two-second delay before a skip
-- is even noticed, on top of however long the host took to publish it — and
-- the whole budget for feeling "in step" is a couple of seconds. A change to
-- this one row is worth being told about rather than discovered.
--
-- Only now_playing goes in the publication. Everything else is either private,
-- slow-moving, or read on a schedule where a few seconds costs nothing.

alter publication supabase_realtime add table now_playing;
