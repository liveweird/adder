-- The team roster (Covenant's V6 shape): membership is a pure join, a hard-delete table of the user_disabled_features class - no
-- history worth keeping here (the audit trail records adds and removals), a re-add must not collide with a dead row, and nothing
-- references a membership row. A soft-deleted user keeps an inert row; every read resolves members through ACTIVE users. A
-- soft-deleted team keeps its roster for the record (reads filter active teams). Rooms snapshot a team's roster at add time.
CREATE TABLE team_members (
    team_id INTEGER NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    user_id BIGINT  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (team_id, user_id)
);
CREATE INDEX idx_team_members_user_id ON team_members(user_id);
