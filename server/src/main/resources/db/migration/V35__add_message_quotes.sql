-- A message quotes one earlier message of its own thread (1.3.0): `messages.quoted_message_id`, the quoting message's own reference, set when it
-- is posted and never changed (an edit cannot touch it). Nothing of the quoted text is stored with it: the reads show the quoted message as
-- it reads NOW (`chat-model.md` "Quotes (1.3)").
--
-- Delete class (persistence.md): the column is a reference inside `messages`, which is SOFT-deleted (V14): a delete is an UPDATE that scrubs
-- the body, so the reference survives it ON PURPOSE and every quote of the message then reads as a deleted quote. ON DELETE SET NULL fires only
-- on a HARD delete (a test purge, a thread or room cascade), where it keeps a quoting row that outlives its quoted one in the same statement.
-- No CHECK (the V15 rule: only code writes the column) and no composite key for "same thread" (it would need a second unique index over
-- `(thread_id, id)` on the hottest table): the write checks the thread under the room lock and the read is scoped by thread.
--
-- Cost: the column is nullable with no default, so the table is not rewritten; the foreign key is validated in one pass over `messages` (every
-- value is NULL) and the index build is a second pass (`persistence.md` "Message quotes (V35)" holds the measured times). The index is the
-- foreign key's own probe (for every hard-deleted message PostgreSQL looks for the rows that quote it; without it a room purge scans `messages`
-- once per deleted row); it is partial so only quoting messages are in it, and it will serve "the replies to this message" when that is built.
--
-- A 1.2.0 binary on this schema boots (Flyway ignores a newer migration it does not know), never names the column, so its posts quote
-- nothing and its reads show no quote, keeps the index maintained, and soft-deletes a quoted message as before.
ALTER TABLE messages
    ADD COLUMN quoted_message_id INTEGER CONSTRAINT fk_messages_quoted REFERENCES messages (id) ON DELETE SET NULL;
CREATE INDEX idx_messages_quoted ON messages (quoted_message_id) WHERE quoted_message_id IS NOT NULL;
