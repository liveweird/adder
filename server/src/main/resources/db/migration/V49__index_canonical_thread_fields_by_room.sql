-- A field-filtered thread list is scoped to exactly one room and canonical threads before applying one or more bound jsonb
-- containment documents. A plain GIN(fields) lets a forced generic plan read matching field values from every room and filter the
-- room afterwards. Leading with room_id through btree_gin keeps both the work and its timing inside the already-authorized room.
--
-- The partial predicate is the list's `canonical()` predicate exactly. Deleted and merged source threads remain available through
-- their explicit lifecycle views, but the ordinary field-filtered list never reads them. `jsonb_path_ops` is sufficient because every
-- supported field filter is `fields @> <document>`.
CREATE EXTENSION IF NOT EXISTS btree_gin;

CREATE INDEX idx_threads_room_fields_canonical
    ON threads USING gin (room_id, fields jsonb_path_ops)
    WHERE NOT marked_as_deleted AND merged_into_thread_number IS NULL;
