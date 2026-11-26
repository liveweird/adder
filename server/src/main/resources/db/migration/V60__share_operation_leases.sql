-- Cross-instance, per-user leases for the two bounded operations that previously used process-local in-flight sets. Operation names
-- are fixed application constants; no room, message, URL or other submitted value is stored. Rows are retained after release so the
-- random owner token fences stale callers, and rows remain bounded by the fixed operation set times the users table.
CREATE TABLE operation_leases (
    operation   VARCHAR(32) NOT NULL,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    owner_id    UUID NOT NULL,
    expires_at  BIGINT NOT NULL,
    PRIMARY KEY (operation, user_id)
);
