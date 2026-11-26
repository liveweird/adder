-- The search's correlated scalar probe restricts every message lookup to one candidate thread and stops at its first live match:
--
--   thread_id = threads.id
--   AND NOT marked_as_deleted
--   AND COALESCE(search, ''::tsvector) @@ <query>
--   LIMIT 1
--
-- A plain GIN on `search` is unsafe for that shape under a prepared generic plan: it can build a table-wide bitmap inside every
-- per-thread probe. A multicolumn btree_gin index avoids the table-wide bitmap but still materialises every matching row of a thread
-- before LIMIT can stop it, badly regressing common words. GiST supplies both the integer equality and the tsvector match as one
-- index scan, so LIMIT retains its early exit while rare and absent words avoid reading every message body of the thread.
--
-- The expression and partial predicate deliberately spell the query exactly. Tombstones have an empty vector already, but excluding
-- them keeps the index smaller and matches the probe's live-message rule. `btree_gist` is a trusted contrib extension in the pinned
-- PostgreSQL image; the database owner can install it without superuser privileges.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE INDEX idx_messages_thread_search_live
    ON messages USING gist (thread_id, (COALESCE(search, ''::tsvector)))
    WHERE NOT marked_as_deleted;
