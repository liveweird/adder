-- The room aggregate (0.2.0), in ONE migration so a room never exists without its workflow: the room, its members, and its own
-- copy of a workflow template's statuses and fields (copied at creation, edited in place afterwards; no link back to the template).
--
-- Soft delete + a partial unique name: `rooms` (a deleted room frees its name; archived rooms keep theirs). Hard delete: `room_members`
-- (a pure join, the team_members class - the audit trail records adds and removals, a re-add must not collide with a dead row; a
-- soft-deleted user keeps an inert row that reads skip). Soft delete with a FULL (not partial) key: `room_statuses` and `room_fields` -
-- a retired key stays reserved for the life of the room (it is revived, never minted twice), so a later thread's history and its
-- (room_id, status_key) foreign key stay unambiguous. That is the documented exception to "free a unique field on delete".
--
-- `workflow_revision` is the optimistic-concurrency token of the room workflow's whole-document PUT (`X-Expected-Revision`).
-- Enum-valued columns (`role`, `colour`, `category`, `type`) carry no CHECK: the Kotlin enum is the whitelist (the V5 rule).
-- A position is the array index on the wire; retired rows keep a stale one.
CREATE TABLE rooms (
    id SERIAL PRIMARY KEY,
    "name" VARCHAR(80) NOT NULL,
    description VARCHAR(500),
    archived_at BIGINT,
    workflow_revision BIGINT NOT NULL DEFAULT 1 CHECK (workflow_revision > 0),
    last_activity_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

-- Name uniqueness among non-deleted rooms, case-insensitively.
CREATE UNIQUE INDEX uq_rooms_name_active ON rooms (LOWER("name")) WHERE NOT marked_as_deleted;

-- The rooms rail and the Rooms page sort by recent activity (`sort=-lastActivityAt`); the index serves that order over non-deleted rooms.
CREATE INDEX idx_rooms_last_activity ON rooms (last_activity_at) WHERE NOT marked_as_deleted;

CREATE TABLE room_members (
    room_id INTEGER NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    user_id BIGINT  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    "role" VARCHAR(10) NOT NULL,
    added_at BIGINT NOT NULL,
    PRIMARY KEY (room_id, user_id)
);
CREATE INDEX idx_room_members_user_id ON room_members(user_id);

CREATE TABLE room_statuses (
    room_id INTEGER NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    "key" VARCHAR(40) NOT NULL,
    label VARCHAR(60) NOT NULL,
    colour VARCHAR(10) NOT NULL,
    category VARCHAR(10) NOT NULL,
    is_initial BOOLEAN NOT NULL DEFAULT FALSE,
    position INTEGER NOT NULL,
    marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (room_id, "key")
);
CREATE UNIQUE INDEX uq_room_statuses_initial ON room_statuses (room_id) WHERE is_initial;

CREATE TABLE room_fields (
    room_id INTEGER NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    "key" VARCHAR(40) NOT NULL,
    label VARCHAR(60) NOT NULL,
    "type" VARCHAR(20) NOT NULL,
    options JSONB NOT NULL DEFAULT '[]',
    required BOOLEAN NOT NULL DEFAULT FALSE,
    position INTEGER NOT NULL,
    marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (room_id, "key")
);
