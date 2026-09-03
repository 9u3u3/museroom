-- Somewhere to keep a resolved YouTube id.
--
-- Resolution costs an API call against a small daily quota, so it happens once
-- per track for everybody rather than once per play per person. The first phone
-- to hear a song pays; every other phone reads the answer.
alter table tracks
    add column youtube_video_id text;

create index if not exists track_aliases_fingerprint on track_aliases (fingerprint);
