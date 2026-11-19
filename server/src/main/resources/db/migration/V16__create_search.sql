-- Full-text search over message bodies and thread titles (0.5.0), by STORED generated `tsvector` columns: no trigger, no wrapper
-- function, and the ALTER TABLE below IS the backfill.
--
-- `public.unaccent()` is only STABLE, so it cannot feed a generated column or an expression index. PostgreSQL's own answer is a text
-- search configuration whose dictionary chain holds `unaccent` as a FILTERING dictionary: `to_tsvector(regconfig, text)` is
-- IMMUTABLE, and `ts_headline` with the same configuration marks a hit in the ORIGINAL text (accents kept). `adder_search` copies
-- `simple` (no stemming, no stop words) and maps the six token types that can hold non-ASCII letters through `unaccent` first; the
-- ASCII types keep `simple` alone. A non-superuser owner may create it (the V4 extension is already in `public`).
--
-- `STORED` MUST be written: on PostgreSQL 18 a bare `GENERATED ALWAYS AS (...)` is VIRTUAL, and an index on a virtual generated
-- column is not supported. A vector is about as large as the body; a token of 8,000 characters is skipped with a NOTICE and the
-- INSERT still succeeds. A tombstone's body is '' so its vector is empty, and a delete empties the vector with the body.
--
-- Each ADD COLUMN rewrites its table and computes every row, with the application NOT serving while Flyway runs (about 7 s per
-- 100,000 messages, `persistence.md` "Search (V16)" has the measurement). `threads.search` gets no index: the room's own index drives
-- that scan and the title match is a filter on it. `messages.search` gets the GIN index the message match reads.
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

ALTER TABLE messages ADD COLUMN search tsvector
    GENERATED ALWAYS AS (to_tsvector('public.adder_search'::regconfig, body)) STORED;
ALTER TABLE threads ADD COLUMN search tsvector
    GENERATED ALWAYS AS (to_tsvector('public.adder_search'::regconfig, title)) STORED;

CREATE INDEX idx_messages_search ON messages USING gin (search);
