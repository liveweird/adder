-- A thread's hashtags (1.2.0), the index behind `MessageTagService.hashtagsOf`: the thread list and the thread read put `hashtags` on every
-- row, and ONE statement reads a whole page of them, one probe per listed thread (`WHERE thread_id = t.id ORDER BY tag LIMIT 20`). V15's
-- only index, `idx_message_tags_room_tag (room_id, tag, thread_id)`, leads with the room, so serving a page from it would walk the room's whole
-- tag index on the hottest read; `(thread_id, tag)` hands one thread's distinct keys over already ordered, so the LIMIT stops the probe early.
--
-- A plain index on a derived table: nothing is rewritten, no row of any other table is touched, and it is inert to a 1.1.0 binary, which never
-- names it (it keeps maintaining it, the writes being the same inserts and deletes). The delete class of `message_tags` is unchanged (HARD,
-- V15). `CREATE INDEX` holds a SHARE lock on `message_tags` for the build (writes to it wait; reads do not): a table with a row per distinct
-- hashtag of a live message, built in well under a second at the sizes of `persistence.md` "Hashtags (V15)".
CREATE INDEX idx_message_tags_thread ON message_tags (thread_id, tag);
