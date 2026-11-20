-- Per-actor digest budget (1.0.0 checkup C-SEC-7): the most DISTINCT people one actor's mentions and hand-overs can make eligible for
-- the email digest in one UTC day (`notifications.email.maxRecipientsPerActorPerDay`). Without it, any room owner could enrol any user
-- and cause a digest email every interval naming a room of their choosing. The notice itself (the bell) is always made; over the budget
-- only its EMAIL is withheld, by stamping `notifications.emailed_at` at insert (V22's "settled, never mailed" meaning; no new column).
--
-- One row per (actor, UTC day) holds the admitted people: the first N distinct recipients of the day, in the order they were reached.
-- `recipients` is bounded by the configured budget (at most 1000), so the row stays small however many people the actor mentions; a
-- person already in it stays eligible for the rest of the day (the same person counts once). `exceeded_at` is the instant the first
-- notice was withheld: the audit event `notification.email_budget_exceeded` fires once, when it goes from null to set. The row is
-- locked `FOR UPDATE` by the write that makes notices, inside that write's transaction, so two rooms' writes of one actor serialize on
-- it and the budget holds under concurrency. It is NOT tied to the notices (a leave or a delete removes those, never the budget), so
-- adding and removing members cannot reset it.
--
-- Delete class (persistence.md): HARD delete. A counter of the day, not a record: the sweep deletes the days older than the notice
-- retention (`idx_notification_email_budget_day`); an actor's rows go with the user row (a test purge cascades; accounts are only
-- soft-deleted). `day` is the epoch day (millis / 86400000, UTC).
CREATE TABLE notification_email_budget (
    actor_id    BIGINT   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    day         INTEGER  NOT NULL,
    recipients  BIGINT[] NOT NULL DEFAULT '{}',
    exceeded_at BIGINT,
    PRIMARY KEY (actor_id, day)
);
CREATE INDEX idx_notification_email_budget_day ON notification_email_budget (day);
