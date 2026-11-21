-- The tag registry (1.2.0, `.claude/docs/product.md` 1.2 D2, `persistence.md` "Tag categories (V32)"; Hammurabi's V13 `tag_categories` and
-- `tags` ported WITHOUT its `adr_tags` join, itself Toadie's): an ADMIN-curated registry of CATEGORIES, each holding TAGS. The join that
-- puts a tag on a thread is V33's `thread_tags`; this migration is the registry and its seed, nothing else.
--
-- `tag_categories`: a name (unique among ACTIVE rows, case-insensitively - a soft-deleted category frees its name, the V6 `teams` rule) and an
-- optional description; `revision` is the optimistic-concurrency token of the category's PUT and DELETE (`X-Expected-Revision`, bumped by
-- every PUT, so an admin editing a stale copy gets a 409 instead of silently overwriting another admin's tag list). Soft-deleted
-- (`marked_as_deleted`, the convention). `tags`: one row per tag, in exactly ONE category (`category_id`). `name` is stored NORMALISED
-- (trimmed, whitespace runs collapsed, lower-cased - `tags/TagCategory.kt`), and `uq_tags_name` makes it unique over EVERY row, deleted
-- ones too, on purpose: a tag removed from the registry is soft-deleted (RETIRED), never hard-deleted, so the threads that will carry it
-- (V33) keep a real row to point at; adding the same name to a category again REVIVES that row (same id) instead of minting a twin. A tag
-- is therefore live exactly while `NOT marked_as_deleted`; deleting a category retires its tags with it. "Each tag in exactly one ACTIVE
-- category" is this one row plus the unique name; the registry's writers also serialise on a table lock (`infra/db/Locking.kt`).
--
-- Plain tables with no existing row to rewrite and no reference from any older table: a 1.1.0 binary never names them (inert on rollback).
CREATE TABLE tag_categories (
    id SERIAL PRIMARY KEY,
    "name" VARCHAR(60) NOT NULL,
    description VARCHAR(500),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX uq_tag_categories_name_active ON tag_categories (LOWER("name")) WHERE NOT marked_as_deleted;

CREATE TABLE tags (
    id SERIAL PRIMARY KEY,
    category_id INTEGER NOT NULL REFERENCES tag_categories(id),
    "name" VARCHAR(40) NOT NULL,
    marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX uq_tags_name ON tags ("name");
CREATE INDEX idx_tags_category ON tags (category_id) WHERE NOT marked_as_deleted;

-- The starter registry (1.2 D2, the user's yes of 2026-11-21): two small categories an ADMIN can rename, extend or delete, like V12's
-- two templates. Tag names are their own stable keys (lower-case English, unique for ever: the registry has no per-language label), and
-- after this migration they change only through the ADMIN editor, never by editing this file. Stamped like V12's rows.
INSERT INTO tag_categories ("name", description, created_at, updated_at)
VALUES
    ('Kind', 'What sort of thread this is', (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT, (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT),
    ('Area', 'The part of the business a thread touches', (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT, (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT);

INSERT INTO tags (category_id, "name")
SELECT c.id, t.tag
FROM (VALUES
    ('Kind', 'question'), ('Kind', 'decision'), ('Kind', 'action'), ('Kind', 'announcement'), ('Kind', 'postmortem'),
    ('Area', 'customer'), ('Area', 'security'), ('Area', 'data'), ('Area', 'infrastructure'), ('Area', 'process')
) AS t(category, tag)
JOIN tag_categories c ON c."name" = t.category;
