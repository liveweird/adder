-- The tags of a thread (1.2.0, `.claude/docs/product.md` 1.2 D1 and D3, `persistence.md` "Thread tags (V33)"; Hammurabi's `adr_tags` ported to a
-- thread): the join that puts a registry tag (V32) on a thread. A member sets a thread's tags as a whole-set replace
-- (`PUT /rooms/{id}/threads/{number}/tags`), applied as a diff, and every change is one `TAGS_CHANGED` row of `thread_events`.
--
-- Delete class (persistence.md): HARD delete - a join, no soft delete and no version column; every change is a history row, so nothing is
-- lost by erasing the pair. A row goes with its thread (`ON DELETE CASCADE`; threads are never deleted by the application, only by a test
-- purge). `tag_id` has NO `ON DELETE` rule on purpose: a registry tag is only ever RETIRED (`tags.marked_as_deleted`, V32), never erased, so
-- a thread that carries it keeps a real row to point at and the foreign key can never fire; a hard purge of a tag (a test, never the app)
-- must clear its `thread_tags` first. `added_by` is the person who put the tag on (a user is only ever soft-deleted, so the key never fires).
--
-- A retired tag stays on its threads until the next tag save of the thread drops it (1.2 A6): the row is the proof, `tags.marked_as_deleted`
-- is the verdict. The primary key `(thread_id, tag_id)` serves "this thread's tags" (the page read, one `thread_id IN (...)` probe each) and the
-- list's `threadTag` filter (`EXISTS` on `thread_id = threads.id AND tag_id IN (...)`); `idx_thread_tags_tag (tag_id, thread_id)` serves the
-- other direction (which threads carry a tag: the registry's future usage count, S8's search by tag, a tag purge).
--
-- A plain new table with no existing row to rewrite and no reference from any older table: a 1.1.0 binary never names it (inert on rollback).
-- What it cannot read is the `TAGS_CHANGED` history rows this release writes: `persistence.md` "Rollback below 1.2.0" has the one statement.
CREATE TABLE thread_tags (
    thread_id INTEGER NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    tag_id    INTEGER NOT NULL REFERENCES tags(id),
    added_at  BIGINT  NOT NULL,
    added_by  BIGINT  NOT NULL REFERENCES users(id),
    PRIMARY KEY (thread_id, tag_id)
);
CREATE INDEX idx_thread_tags_tag ON thread_tags (tag_id, thread_id);
