-- Full-text search over message bodies and thread titles (0.5.0), by STORED generated `tsvector` columns: no trigger, no wrapper
-- function, and the ALTER TABLE below IS the backfill.
--
-- `public.unaccent()` is only STABLE, so it cannot feed a generated column or an expression index. PostgreSQL's own answer is a text
-- search configuration whose dictionary chain holds `unaccent` as a FILTERING dictionary: `to_tsvector(regconfig, text)` is
-- IMMUTABLE, and `ts_headline` with the same configuration marks a hit in the ORIGINAL text (accents kept). `adder_search` copies
-- `simple` (no stemming, no stop words) and maps the six token types that can hold non-ASCII letters through `unaccent` first; the
-- ASCII types keep `simple` alone. A non-superuser owner may create it (the V4 extension is already in `public`).
--
-- `STORED` MUST be written: on PostgreSQL 18 a bare `GENERATED ALWAYS AS (...)` is VIRTUAL (computed again on every read, and no index
-- on it is supported). A vector is about as large as the body; a token of 8,000 characters is skipped with a NOTICE and the
-- INSERT still succeeds. A tombstone's body is '' so its vector is empty, and a delete empties the vector with the body.
--
-- Each ADD COLUMN rewrites its table and computes every row, with the application NOT serving while Flyway runs (about 7 s per
-- 100,000 messages, `persistence.md` "Search (V16)" has the measurement).
--
-- NO index is created on either vector, deliberately. The search restricts to the caller's rooms FIRST (`threads(room_id, ...)`), then
-- probes each of those threads' messages through `idx_messages_thread` and stops at the first message that matches: the vector is a
-- FILTER on rows the thread index already reached, linear in the searched rooms' own messages. A GIN index on `messages.search` was
-- built here at first and no statement read it: with the per-thread probe the planner never chooses it, and a table-wide match (the
-- plan it served) is exactly the cost the search avoids. It would cost about 100 MB per 100,000 messages and slow every post. If a
-- room or a caller's message count ever makes the probe too slow, `BACKLOG.md` holds the options (a `btree_gin` index on
-- `(thread_id, search)`, or a `room_id` on `messages`); that is a LATER migration, with its own measurement.
--
-- The configuration is IMMUTABLE BY PROMISE. Altering it, or a major PostgreSQL upgrade that changes the `unaccent` rules, leaves the
-- stored vectors stale until they are recomputed: `ALTER TABLE ... ALTER COLUMN search SET EXPRESSION AS (...)`. A later migration
-- that alters the configuration does that in the same file (the runbook is in `persistence.md`). The configuration cannot be dropped
-- while a column depends on it.
--
-- The columns are never declared in an Exposed table object (`selectAll()` would read a vector on every message read, and the driver
-- has no `tsvector` codec): `infra/db/Fts.kt` names the configuration and refers to the columns as free expressions.
CREATE TEXT SEARCH CONFIGURATION public.adder_search (COPY = pg_catalog.simple);
ALTER TEXT SEARCH CONFIGURATION public.adder_search
    ALTER MAPPING FOR hword, hword_part, word, numword, numhword, hword_numpart
    WITH public.unaccent, pg_catalog.simple;

-- `pg_catalog.to_tsvector`, schema-qualified: a generated column's expression is STORED PARSED (as function OIDs), so the search_path
-- matters only when the text is parsed: this migration, a later `SET EXPRESSION`, or a dump restore. Qualified, none of them can
-- resolve the name to a function of another schema that sits ahead of `pg_catalog`.
ALTER TABLE messages ADD COLUMN search tsvector
    GENERATED ALWAYS AS (pg_catalog.to_tsvector('public.adder_search'::regconfig, body)) STORED;
ALTER TABLE threads ADD COLUMN search tsvector
    GENERATED ALWAYS AS (pg_catalog.to_tsvector('public.adder_search'::regconfig, title)) STORED;
