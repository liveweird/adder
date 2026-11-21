-- The wide-event budget (1.1.0 S6, `chat-model.md` "Watching, group mentions and notice kinds (1.1)", 1.1 Q1 = (a)): the most WIDE events
-- one actor may make in a UTC day (`notifications.maxBroadcastsPerActorPerDay`, 500). A wide event is one that would notify more than 20
-- people (a new thread in a big room, a reply in a heavily watched thread): without a bound, 120 posts a minute to 499 people is about
-- 86 million notice rows a day for one account. Over the budget the WRITE still succeeds and only the people named directly
-- (a `<@id>` mention, a hand-over) are told.
--
-- The counter rides the actor's existing V24 row (one row per actor and UTC day, already locked `FOR UPDATE` by the writes that make
-- notices), so no table and no index is added. `broadcasts` counts the wide events admitted today; `broadcasts_exceeded_at` is the instant
-- the first one was refused: the audit event `notification.broadcast_budget_exceeded` fires once, when it goes from null to set.
-- Existing rows start at 0 and null; a build rolled back to 1.0.0 ignores both columns.
--
-- Delete class: unchanged (HARD, the V24 row of the day, swept with the days older than the notice retention).
ALTER TABLE notification_email_budget
    ADD COLUMN broadcasts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN broadcasts_exceeded_at BIGINT;
