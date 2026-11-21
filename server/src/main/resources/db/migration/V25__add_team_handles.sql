-- The team's optional MENTION HANDLE (1.1.0): the lowercase token a group mention names a team by. The grammar
-- (2 to 30 characters, ^[a-z][a-z0-9]*(-[a-z0-9]+)*$, a reserved set) is enforced by the application, not by a CHECK:
-- a CHECK on an application vocabulary cannot follow a later change (the V5 rule), and the reserved words are code.
-- Nullable, no backfill: a team without a handle simply cannot be mentioned, and the ADMIN sets one when it matters.
ALTER TABLE teams ADD COLUMN handle VARCHAR(30);

-- Unique among ACTIVE teams only (the soft-delete convention: a deleted team frees its handle). Handles are stored
-- lowercase, so no LOWER() is needed; NULLs never collide in a unique index.
CREATE UNIQUE INDEX uq_teams_handle_active ON teams (handle) WHERE NOT marked_as_deleted;
