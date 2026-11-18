-- The thread aggregate (0.3.0), in ONE migration so a thread never exists without its messages and its history: the thread, its
-- messages, and its append-only event log. Delete class of each table (persistence.md):
--
-- `threads`: never deleted in 0.3 (a thread is closed, not removed; a room's soft delete hides its threads with it). A room's HARD
-- delete (test purge only) cascades into `threads` and `room_statuses` in the same statement; the composite status foreign key
-- below keeps the default NO ACTION, and that cascade is pinned by a test (`ThreadSchemaTest`).
-- `messages`: soft delete (`marked_as_deleted`), because a deleted message stays listed as a tombstone: the body is scrubbed to ''
-- and `deleted_by` records who removed it, on EVERY delete (the author, or the room OWNER or an ADMIN, who may delete anyone's but
-- never edit). The CHECKs pin that shape: a tombstone has an empty body and a `deleted_by`, a live message has a body and none.
-- `thread_events`: append-only, no update and no delete (the shape of Toadie's `catalog_file_events`): the user-facing History of a
-- thread. Events are stored STRUCTURALLY (`event_type` + a JSON object of string params, rendered in the viewer's language);
-- the security trail stays the AUDIT-marked logs. `user_id` is ON DELETE RESTRICT: an actor is never hard-removed from under the
-- history (users soft-delete anyway).
--
-- `threads.status_key` references `room_statuses(room_id, "key")`: a status that threads use can never disappear, and a retired
-- status keeps its row (soft delete with a full key, V13). `closed_at` mirrors the status category (set on a move into a CLOSED
-- status, cleared on a move out); `message_count` counts every message, tombstones included, and never goes down. `fields` is a
-- JSON object of field key to value; retired fields keep their values. `revision` is the optimistic-concurrency token of the
-- details PUT (`X-Expected-Revision`). A thread's `number` is per room (`MAX + 1` under the room lock, never reused).
-- Enum-valued columns (`event_type`) carry no CHECK: the Kotlin enum is the whitelist (the V5 rule).
CREATE TABLE threads (
    id SERIAL PRIMARY KEY,
    room_id INTEGER NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    "number" INTEGER NOT NULL CHECK ("number" > 0),
    title VARCHAR(200) NOT NULL,
    status_key VARCHAR(40) NOT NULL,
    owner_id BIGINT NOT NULL REFERENCES users(id),
    created_by BIGINT NOT NULL REFERENCES users(id),
    fields JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(fields) = 'object'),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    message_count INTEGER NOT NULL DEFAULT 0 CHECK (message_count >= 0),
    closed_at BIGINT,
    last_activity_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT fk_threads_status FOREIGN KEY (room_id, status_key) REFERENCES room_statuses(room_id, "key")
);
CREATE UNIQUE INDEX uq_threads_room_number ON threads (room_id, "number");
CREATE INDEX idx_threads_room_activity ON threads (room_id, last_activity_at);
CREATE INDEX idx_threads_room_status ON threads (room_id, status_key);

CREATE TABLE messages (
    id SERIAL PRIMARY KEY,
    thread_id INTEGER NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    author_id BIGINT NOT NULL REFERENCES users(id),
    body TEXT NOT NULL CHECK (char_length(body) <= 8000),
    created_at BIGINT NOT NULL,
    edited_at BIGINT,
    marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_by BIGINT REFERENCES users(id),
    CHECK (NOT marked_as_deleted OR body = ''),
    CHECK (marked_as_deleted OR char_length(body) >= 1),
    CHECK ((deleted_by IS NOT NULL) = marked_as_deleted)
);
CREATE INDEX idx_messages_thread ON messages (thread_id, id);

CREATE TABLE thread_events (
    id SERIAL PRIMARY KEY,
    thread_id INTEGER NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    params TEXT NOT NULL DEFAULT '{}'
);
CREATE INDEX idx_thread_events_thread ON thread_events (thread_id, created_at, id);
