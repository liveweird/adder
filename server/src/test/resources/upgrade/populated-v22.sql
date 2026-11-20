-- A populated database at the v0.6.0 schema (Flyway V22): one or more rows in EVERY table, written the way the 0.6.0 server wrote
-- them (the shapes of the stored values: persistence.md, chat-model.md), for PopulatedUpgradeTest, which then migrates it to the
-- latest version. Plain SQL against the V22 schema; it must keep matching V22 (an applied migration never changes), so it is
-- not edited when a later migration lands. One placeholder is substituted by the test: {{PASSWORD_HASH}} (a bcrypt hash).
--
-- Values are realistic on purpose: Polish diacritics, CJK, RTL, emoji with modifiers, a combining mark, the longest title (200), the
-- longest TEXT field (500), the longest URL (2000) and the longest message body (8000), soft-deleted rows, retired statuses and fields.
-- Every value V23 must scrub carries the marker SECRET; no value it must keep does.

CREATE FUNCTION pg_temp.t(minutes integer) RETURNS bigint LANGUAGE sql IMMUTABLE AS
    $$ SELECT 1795082400000::bigint + minutes::bigint * 60000 $$;   -- 2026-11-19T10:00:00Z plus N minutes

-- ----------------------------------------------------------------------------------------------------------------------------
-- Users (V1, V3, V5, V7, V8, V2). Id 1 is the V3 seed admin.
-- ----------------------------------------------------------------------------------------------------------------------------
INSERT INTO users (id, "name", email, password_hash, "role", password_changed_at, language, marked_as_deleted, credential_revision) VALUES
    (2, 'Olivia Żółkiewska',  'olivia.owner@upgrade.test', '{{PASSWORD_HASH}}', 'USER',  pg_temp.t(-9000), 'pl', FALSE, 3),
    (3, 'Zoë Ångström',       'zoe@upgrade.test',          '{{PASSWORD_HASH}}', 'USER',  0,                'en', FALSE, 0),
    (4, '李雷 (Li Lei)',       'lilei@upgrade.test',        '{{PASSWORD_HASH}}', 'USER',  pg_temp.t(-100),  'en', FALSE, 1),
    (5, 'Admin Dwa',          'admin2@upgrade.test',       '{{PASSWORD_HASH}}', 'ADMIN', 0,                'pl', FALSE, 0),
    (6, 'Gerard Odszedł',     'gone@upgrade.test',         '{{PASSWORD_HASH}}', 'USER',  0,                'en', TRUE,  0);
-- The mail address of a deleted account is free again (the partial unique index): a new active account takes it.
INSERT INTO users (id, "name", email, password_hash, "role", language) VALUES
    (7, 'Gerard Wrócił', 'gone@upgrade.test', '{{PASSWORD_HASH}}', 'USER', 'en');
INSERT INTO user_disabled_features (user_id, feature) VALUES (2, 'MFA'), (3, 'MFA'), (4, 'MFA'), (5, 'MFA'), (6, 'MFA');
INSERT INTO revoked_tokens (jti, expires_at) VALUES
    ('0b9d9c3e-6c4e-4f7a-9d44-0f1e2d3c4b5a', pg_temp.t(60 * 24 * 7)),
    ('f3a1c0de-0000-4000-8000-00000000beef', pg_temp.t(-60));
INSERT INTO password_reset_tokens (token_hash, user_id, email, credential_revision, expires_at) VALUES
    ('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 3, 'zoe@upgrade.test', 0, pg_temp.t(30));

-- ----------------------------------------------------------------------------------------------------------------------------
-- Teams with a roster (V6, V10), and the workflow template registry (V11; V12 seeded ids 1 and 2).
-- ----------------------------------------------------------------------------------------------------------------------------
INSERT INTO teams (id, "name", description, created_at, updated_at, marked_as_deleted, manager_user_id) VALUES
    (1, 'Platform Reliability', 'Dyżury, incydenty i post-mortemy — on-call rota, runbooks.', pg_temp.t(-5000), pg_temp.t(-4000), FALSE, 2),
    (2, 'Obsługa klienta (zlikwidowany)', NULL, pg_temp.t(-5000), pg_temp.t(-3000), TRUE, 3);
INSERT INTO team_members (team_id, user_id) VALUES (1, 2), (1, 3), (1, 6), (2, 4);

INSERT INTO workflow_templates (id, "name", description, definition, revision, created_at, updated_at, marked_as_deleted) VALUES
    (3, 'Retrospektywa — post-mortem', 'Od zebrania faktów do działań naprawczych.',
     '{"statuses":[{"key":"facts","label":{"en":"Facts","pl":"Fakty"},"colour":"GRAY","category":"OPEN","initial":true},
                   {"key":"actions","label":{"en":"Actions","pl":"Działania"},"colour":"BLUE","category":"OPEN","initial":false},
                   {"key":"published","label":{"en":"Published","pl":"Opublikowane"},"colour":"TEAL","category":"CLOSED","initial":false}],
       "fields":[{"key":"owner-team","label":{"en":"Team","pl":"Zespół"},"type":"TEXT","required":false,"options":[]}]}'::jsonb,
     4, pg_temp.t(-4000), pg_temp.t(-2000), FALSE),
    (4, 'Stary proces', NULL, '{"statuses":[],"fields":[]}'::jsonb, 7, pg_temp.t(-5000), pg_temp.t(-1000), TRUE);

-- ----------------------------------------------------------------------------------------------------------------------------
-- Rooms (V13): 1 an incident room with a retired status and retired fields; 2 an archived room; 3 a soft-deleted room.
-- ----------------------------------------------------------------------------------------------------------------------------
INSERT INTO rooms (id, "name", description, archived_at, workflow_revision, last_activity_at, created_at, updated_at, marked_as_deleted) VALUES
    (1, 'Incydent: baza danych (2026-11-19)', 'Wysoka latencja zapytań w orders-db — all hands. 🔥', NULL,          6, pg_temp.t(100), pg_temp.t(-300), pg_temp.t(90), FALSE),
    (2, 'Spokojny pokój',                      NULL,                                                  pg_temp.t(10), 1, pg_temp.t(20),  pg_temp.t(-200), pg_temp.t(10), FALSE),
    (3, 'Zlikwidowany pokój',                  'Ten pokój został usunięty.',                          NULL,          2, pg_temp.t(30),  pg_temp.t(-100), pg_temp.t(40), TRUE);

INSERT INTO room_members (room_id, user_id, "role", added_at, seen_at, read_from) VALUES
    (1, 2, 'OWNER',  pg_temp.t(-300), pg_temp.t(50),  pg_temp.t(40)),
    (1, 3, 'MEMBER', pg_temp.t(-290), pg_temp.t(100), pg_temp.t(100)),
    (1, 4, 'MEMBER', pg_temp.t(-280), pg_temp.t(-280), pg_temp.t(-280)),
    (1, 6, 'MEMBER', pg_temp.t(-270), pg_temp.t(20),  pg_temp.t(20)),
    (2, 2, 'OWNER',  pg_temp.t(-200), pg_temp.t(25),  pg_temp.t(25)),
    (2, 3, 'MEMBER', pg_temp.t(-190), pg_temp.t(0),   pg_temp.t(0)),
    (3, 2, 'OWNER',  pg_temp.t(-100), pg_temp.t(30),  pg_temp.t(30));

INSERT INTO room_statuses (room_id, "key", label, colour, category, is_initial, position, marked_as_deleted) VALUES
    (1, 'triage',         'Triaż',          'RED',    'OPEN',   TRUE,  0, FALSE),
    (1, 'investigating',  'Analiza',        'ORANGE', 'OPEN',   FALSE, 1, FALSE),
    (1, 'mitigating',     'Mitygacja',      'BLUE',   'OPEN',   FALSE, 2, FALSE),
    (1, 'monitoring',     'Monitorowanie',  'GRAPE',  'OPEN',   FALSE, 3, FALSE),
    (1, 'resolved',       'Rozwiązane',     'TEAL',   'CLOSED', FALSE, 4, FALSE),
    (1, 'blocked-legacy', 'Zablokowane',    'GRAY',   'OPEN',   FALSE, 2, TRUE),
    (2, 'open',           'Otwarte',        'GRAY',   'OPEN',   TRUE,  0, FALSE),
    (2, 'in-progress',    'W toku',         'BLUE',   'OPEN',   FALSE, 1, FALSE),
    (2, 'done',           'Zrobione',       'TEAL',   'CLOSED', FALSE, 2, FALSE),
    (2, 'wont-do',        'Nie robimy',     'GRAY',   'CLOSED', FALSE, 3, FALSE),
    (3, 'open',           'Otwarte',        'GRAY',   'OPEN',   TRUE,  0, FALSE),
    (3, 'done',           'Zrobione',       'TEAL',   'CLOSED', FALSE, 1, FALSE);

INSERT INTO room_fields (room_id, "key", label, "type", options, required, position, marked_as_deleted) VALUES
    (1, 'severity',   'Ważność',        'SELECT',       '[{"key":"s1","label":"S1"},{"key":"s2","label":"S2"},{"key":"s3","label":"S3"},{"key":"s4","label":"S4"}]', FALSE, 0, FALSE),
    (1, 'summary',    'Podsumowanie',   'TEXT',         '[]', FALSE, 1, FALSE),
    (1, 'runbook',    'Runbook',        'URL',          '[]', FALSE, 2, FALSE),
    (1, 'due',        'Termin',         'DATE',         '[]', FALSE, 3, FALSE),
    (1, 'impact',     'Dotknięci',      'NUMBER',       '[]', FALSE, 4, FALSE),
    (1, 'lead',       'Prowadzący',     'USER',         '[]', FALSE, 5, FALSE),
    (1, 'components', 'Komponenty',     'MULTI_SELECT', '[{"key":"db","label":"Baza danych"},{"key":"api","label":"API"},{"key":"cdn","label":"CDN"}]', FALSE, 6, FALSE),
    (1, 'shared',     'Wspólne',        'SELECT',       '[{"key":"a","label":"A"},{"key":"b","label":"B"}]', FALSE, 7, FALSE),
    (1, 'note',       'Notatka',        'TEXT',         '[]', FALSE, 8, TRUE),
    (1, 'link',       'Link',           'URL',          '[]', FALSE, 9, TRUE),
    (1, 'deadline',   'Deadline',       'DATE',         '[]', FALSE, 10, TRUE),
    (1, 'legacy_sev', 'Dawna ważność',  'SELECT',       '[{"key":"s1","label":"S1"},{"key":"s2","label":"S2"}]', FALSE, 11, TRUE),
    (2, 'owner_note', 'Notatka właściciela', 'TEXT',    '[]', FALSE, 0, FALSE),
    (2, 'priority',   'Priorytet',      'SELECT',       '[{"key":"p1","label":"P1"},{"key":"p2","label":"P2"}]', FALSE, 1, FALSE),
    (2, 'shared',     'Wspólne',        'URL',          '[]', FALSE, 2, FALSE),
    (3, 'note',       'Notatka',        'TEXT',         '[]', FALSE, 0, FALSE);

-- ----------------------------------------------------------------------------------------------------------------------------
-- Threads (V14; V16's generated vector is computed by the server): numbers 1..6 in room 1, 1..2 in room 2, 1 in room 3.
-- ----------------------------------------------------------------------------------------------------------------------------
INSERT INTO threads (id, room_id, "number", title, status_key, owner_id, created_by, fields, revision, message_count, closed_at, last_activity_at, created_at, updated_at) VALUES
    (1, 1, 1, 'Baza danych: wysoka latencja zapytań 🔥 (orders-db)', 'investigating', 3, 2,
     jsonb_build_object(
         'severity', 's2',
         'summary', left(repeat('Opis awarii: zażółć gęślą jaźń. ', 20), 500),
         'runbook', left('https://wiki.example.test/runbook?q=' || repeat('x', 2000), 2000),
         'due', '2026-12-24',
         'impact', 1250.5,
         'lead', 2,
         'components', jsonb_build_array('db', 'api'),
         'shared', 'b',
         'note', 'retired TEXT value hunter2-retired',
         'deadline', '2026-11-30'),
     7, 5, NULL, pg_temp.t(100), pg_temp.t(1), pg_temp.t(95)),
    (2, 1, 2, 'Wyciek tokenu w logach', 'triage', 2, 2,
     jsonb_build_object('severity', 's1', 'impact', 3, 'components', jsonb_build_array('api')),
     1, 2, NULL, pg_temp.t(60), pg_temp.t(10), pg_temp.t(10)),
    (3, 1, 3, 'Awaria CDN — rozwiązana', 'resolved', 2, 3,
     jsonb_build_object('severity', 's3', 'due', '2026-11-18'),
     3, 2, pg_temp.t(70), pg_temp.t(70), pg_temp.t(20), pg_temp.t(70)),
    (4, 1, 4, 'Nowe zgłoszenie bez pól', 'triage', 4, 4, '{}'::jsonb, 1, 1, NULL, pg_temp.t(30), pg_temp.t(30), pg_temp.t(30)),
    (5, 1, 5, 'Wątek byłego pracownika', 'triage', 6, 6,
     jsonb_build_object('lead', 6, 'impact', 0.25),
     2, 1, NULL, pg_temp.t(40), pg_temp.t(40), pg_temp.t(41)),
    (6, 1, 6, left(repeat('Długi tytuł wątku — ', 20), 200), 'monitoring', 2, 2, '{}'::jsonb, 1, 1, NULL, pg_temp.t(50), pg_temp.t(50), pg_temp.t(50)),
    (7, 2, 1, 'Zaplanować przegląd kwartalny', 'in-progress', 2, 2,
     jsonb_build_object('owner_note', 'hunter2 dla przeglądu', 'priority', 'p2', 'shared', 'https://example.test/q?k=1'),
     2, 2, NULL, pg_temp.t(20), pg_temp.t(5), pg_temp.t(20)),
    (8, 2, 2, 'Pusta kolumna', 'open', 3, 3, '{}'::jsonb, 1, 1, NULL, pg_temp.t(15), pg_temp.t(15), pg_temp.t(15)),
    (9, 3, 1, 'Wątek w usuniętym pokoju', 'open', 2, 2, jsonb_build_object('note', 'hunter2'), 1, 1, NULL, pg_temp.t(30), pg_temp.t(30), pg_temp.t(30));

-- ----------------------------------------------------------------------------------------------------------------------------
-- Messages (V14, V15's tags_version): Markdown, mentions, hashtags, an edit, a tombstone, the longest body.
-- ----------------------------------------------------------------------------------------------------------------------------
INSERT INTO messages (id, thread_id, author_id, body, created_at, edited_at, marked_as_deleted, deleted_by, tags_version) VALUES
    (1, 1, 2, E'Zgłoszenie: **wysoka latencja** zapytań w `orders-db` 🔥\n\nZażółć gęślą jaźń — Unicode: 日本語, العربية, e' || chr(769) || E' (łącznik).\n\n#incydent #baza_danych <@3> proszę o podgląd.',
        pg_temp.t(1), NULL, FALSE, NULL, 1),
    (2, 1, 3, E'Potwierdzam 👍🏽 — widzę to samo.\n\n> cytat: latencja p99 > 4 s\n\n#incydent #Żółć <@2> <@6> dołączcie proszę.',
        pg_temp.t(5), pg_temp.t(7), FALSE, NULL, 1),
    (3, 1, 4, left(repeat('Zażółć gęślą jaźń — 日本語テキスト, wiersz diagnostyczny. ', 400), 8000),
        pg_temp.t(9), NULL, FALSE, NULL, 0),
    (4, 1, 6, 'Sprawdziłem pulę połączeń: 98/100. Zostawiam notatkę przed odejściem.', pg_temp.t(12), NULL, FALSE, NULL, 1),
    (5, 1, 4, '', pg_temp.t(14), NULL, TRUE, 2, 1),
    (6, 2, 2, 'Token w logach `auth-gw` — rotacja w toku.', pg_temp.t(10), NULL, FALSE, NULL, 1),
    (7, 2, 3, 'Potwierdzone, <@4> sprawdź proszę drugi węzeł.', pg_temp.t(11), NULL, FALSE, NULL, 1),
    (8, 3, 3, 'CDN zwraca 503 dla `/static/*`.', pg_temp.t(20), NULL, FALSE, NULL, 1),
    (9, 3, 2, 'Naprawione, cache odświeżony. #poprawka', pg_temp.t(70), NULL, FALSE, NULL, 1),
    (10, 4, 4, 'Pierwsze zgłoszenie — jeszcze bez pól.', pg_temp.t(30), NULL, FALSE, NULL, 1),
    (11, 5, 6, 'Przejmuję ten wątek.', pg_temp.t(40), NULL, FALSE, NULL, 1),
    (12, 6, 2, 'Monitorujemy przez 24 godziny.', pg_temp.t(50), NULL, FALSE, NULL, 1),
    (13, 7, 2, 'Przegląd kwartalny: proszę o propozycje terminów.', pg_temp.t(5), NULL, FALSE, NULL, 1),
    (14, 7, 3, 'Proponuję przyszły wtorek. 🎉', pg_temp.t(20), NULL, FALSE, NULL, 1),
    (15, 8, 3, 'Pusty wątek poza przeglądem.', pg_temp.t(15), NULL, FALSE, NULL, 1),
    (16, 9, 3, '<@2> ten pokój zniknie.', pg_temp.t(30), NULL, FALSE, NULL, 1);

INSERT INTO message_tags (message_id, thread_id, room_id, tag) VALUES
    (1, 1, 1, 'incydent'), (1, 1, 1, 'baza_danych'),
    (2, 1, 1, 'incydent'), (2, 1, 1, 'zolc'),
    (9, 3, 1, 'poprawka');

INSERT INTO message_mentions (message_id, user_id, thread_id, room_id) VALUES
    (1, 3, 1, 1), (2, 2, 1, 1), (2, 6, 1, 1), (7, 4, 2, 1), (16, 2, 9, 3);

INSERT INTO message_reactions (message_id, user_id, emoji, created_at) VALUES
    (1, 3, '👍', pg_temp.t(2)), (1, 4, '👍', pg_temp.t(3)), (1, 2, '❤️', pg_temp.t(4)),
    (2, 2, '👍🏽', pg_temp.t(6)), (2, 6, '🇵🇱', pg_temp.t(8)),
    (9, 3, '🎉', pg_temp.t(71)), (14, 2, '🎉', pg_temp.t(21));

INSERT INTO room_pins (id, room_id, thread_id, message_id, pinned_by, pinned_at) VALUES
    (1, 1, 1, NULL, 2, pg_temp.t(15)),
    (2, 1, 1, 2,    3, pg_temp.t(16)),
    (3, 2, 7, NULL, 2, pg_temp.t(21));

-- ----------------------------------------------------------------------------------------------------------------------------
-- The history (V14): every event type, the OLD shapes V23 scrubs, and the shapes it must leave alone.
-- ----------------------------------------------------------------------------------------------------------------------------
INSERT INTO thread_events (id, thread_id, user_id, created_at, event_type, params) VALUES
    -- thread 1 (room 1): the opening title, TEXT and URL values, SELECT / DATE / NUMBER / USER / MULTI_SELECT values, retired and unknown fields
    (1,  1, 2, pg_temp.t(1),  'CREATED',       '{"title":"SECRET Baza danych: wysoka latencja zapytań 🔥","status":"triage","statusLabel":"Triaż"}'),
    (2,  1, 2, pg_temp.t(2),  'FIELD_CHANGED', '{"field":"summary","fieldLabel":"Podsumowanie","to":"' || left(repeat('SECRET-zażółć gęślą ', 40), 500) || '"}'),
    (3,  1, 2, pg_temp.t(3),  'FIELD_CHANGED', '{"field":"runbook","fieldLabel":"Runbook","from":"https://wiki.example.test/old?token=SECRET","to":"' || left('https://wiki.example.test/runbook?token=SECRET&q=' || repeat('x', 2000), 2000) || '"}'),
    (4,  1, 2, pg_temp.t(4),  'FIELD_CHANGED', '{"field":"severity","fieldLabel":"Ważność","to":"S3"}'),
    (5,  1, 3, pg_temp.t(6),  'FIELD_CHANGED', '{"field":"severity","fieldLabel":"Ważność","from":"S3","to":"S2"}'),
    (6,  1, 3, pg_temp.t(7),  'FIELD_CHANGED', '{"field":"due","fieldLabel":"Termin","from":"2026-11-30","to":"2026-12-24"}'),
    (7,  1, 3, pg_temp.t(8),  'FIELD_CHANGED', '{"field":"impact","fieldLabel":"Dotknięci","from":"900","to":"1250.5"}'),
    (8,  1, 3, pg_temp.t(9),  'FIELD_CHANGED', '{"field":"lead","fieldLabel":"Prowadzący","from":"Olivia Żółkiewska","to":"Zoë Ångström"}'),
    (9,  1, 6, pg_temp.t(10), 'FIELD_CHANGED', '{"field":"components","fieldLabel":"Komponenty","from":"Baza danych","to":"Baza danych, API"}'),
    (10, 1, 2, pg_temp.t(11), 'FIELD_CHANGED', '{"field":"note","fieldLabel":"Notatka","from":"SECRET hasło \"quoted\" 🔑","to":"SECRET dwa"}'),
    (11, 1, 2, pg_temp.t(12), 'FIELD_CHANGED', '{"field":"link","fieldLabel":"Link","to":"https://example.test/?apikey=SECRET"}'),
    (12, 1, 2, pg_temp.t(13), 'FIELD_CHANGED', '{"field":"deadline","fieldLabel":"Deadline","from":"2026-11-25","to":"2026-11-30"}'),
    (13, 1, 2, pg_temp.t(14), 'FIELD_CHANGED', '{"field":"ghost","fieldLabel":"Duch","from":"SECRET a","to":"SECRET b"}'),
    (14, 1, 2, pg_temp.t(15), 'FIELD_CHANGED', '{"field":"summary","fieldLabel":"Podsumowanie"}'),
    (15, 1, 2, pg_temp.t(16), 'TITLE_CHANGED', '{"from":"SECRET Baza danych: wysoka latencja","to":"SECRET Baza danych: wysoka latencja zapytań 🔥"}'),
    (16, 1, 3, pg_temp.t(17), 'TITLE_CHANGED', '{"from":"SECRET Baza danych: wysoka latencja zapytań 🔥","to":"Baza danych: wysoka latencja zapytań 🔥 (orders-db) SECRET"}'),
    (17, 1, 2, pg_temp.t(18), 'STATUS_CHANGED','{"from":"triage","to":"investigating","fromLabel":"Triaż","toLabel":"Analiza"}'),
    (18, 1, 2, pg_temp.t(19), 'OWNER_CHANGED', '{"fromId":"2","toId":"3","fromName":"Olivia Żółkiewska","toName":"Zoë Ångström"}'),
    (19, 1, 3, pg_temp.t(20), 'FIELD_CHANGED', '{"field":"shared","fieldLabel":"Wspólne","from":"A","to":"B"}'),
    (20, 1, 2, pg_temp.t(21), 'FIELD_CHANGED', '{"field":"legacy_sev","fieldLabel":"Dawna ważność","from":"S1","to":"S2"}'),
    -- threads 2 and 3: a creation, moves, a close, a reopen and a second close
    (21, 2, 2, pg_temp.t(10), 'CREATED',       '{"title":"SECRET Wyciek tokenu w logach","status":"triage","statusLabel":"Triaż"}'),
    (22, 3, 3, pg_temp.t(20), 'CREATED',       '{"title":"SECRET Awaria CDN","status":"triage","statusLabel":"Triaż"}'),
    (23, 3, 3, pg_temp.t(30), 'STATUS_CHANGED','{"from":"triage","to":"mitigating","fromLabel":"Triaż","toLabel":"Mitygacja"}'),
    (24, 3, 2, pg_temp.t(40), 'CLOSED',        '{"from":"mitigating","to":"resolved","fromLabel":"Mitygacja","toLabel":"Rozwiązane"}'),
    (25, 3, 2, pg_temp.t(50), 'REOPENED',      '{"from":"resolved","to":"monitoring","fromLabel":"Rozwiązane","toLabel":"Monitorowanie"}'),
    (26, 3, 2, pg_temp.t(70), 'CLOSED',        '{"from":"monitoring","to":"resolved","fromLabel":"Monitorowanie","toLabel":"Rozwiązane"}'),
    -- thread 4: already in the shape a post-scrub server writes (no title, an empty rename)
    (27, 4, 4, pg_temp.t(30), 'CREATED',       '{"status":"triage","statusLabel":"Triaż"}'),
    (28, 4, 4, pg_temp.t(31), 'TITLE_CHANGED', '{}'),
    -- thread 5: a deleted actor, a retired status replaced
    (29, 5, 6, pg_temp.t(40), 'CREATED',       '{"title":"SECRET Wątek byłego pracownika","status":"blocked-legacy","statusLabel":"Zablokowane"}'),
    (30, 5, 2, pg_temp.t(41), 'STATUS_REPLACED','{"from":"blocked-legacy","to":"triage","fromLabel":"Zablokowane","toLabel":"Triaż"}'),
    -- thread 6: the longest title
    (31, 6, 2, pg_temp.t(50), 'CREATED',       '{"title":"' || left(repeat('SECRET Długi tytuł wątku — ', 20), 200) || '","status":"monitoring","statusLabel":"Monitorowanie"}'),
    -- room 2 (a key that is a SELECT in room 1 is a URL here), room 3
    (32, 7, 2, pg_temp.t(5),  'CREATED',       '{"title":"SECRET Zaplanować przegląd kwartalny","status":"open","statusLabel":"Otwarte"}'),
    (33, 7, 2, pg_temp.t(6),  'TITLE_CHANGED', '{"from":"SECRET Przegląd","to":"SECRET Zaplanować przegląd kwartalny"}'),
    (34, 7, 2, pg_temp.t(7),  'FIELD_CHANGED', '{"field":"owner_note","fieldLabel":"Notatka właściciela","to":"SECRET hunter2 dla przeglądu"}'),
    (35, 7, 2, pg_temp.t(8),  'FIELD_CHANGED', '{"field":"priority","fieldLabel":"Priorytet","from":"P1","to":"P2"}'),
    (36, 7, 2, pg_temp.t(9),  'FIELD_CHANGED', '{"field":"shared","fieldLabel":"Wspólne","from":"https://example.test/?k=SECRET","to":"https://example.test/q?k=SECRET"}'),
    (37, 8, 3, pg_temp.t(15), 'CREATED',       '{"title":"SECRET Pusta kolumna","status":"open","statusLabel":"Otwarte"}'),
    (38, 9, 2, pg_temp.t(30), 'CREATED',       '{"title":"SECRET Wątek w usuniętym pokoju","status":"open","statusLabel":"Otwarte"}'),
    (39, 9, 2, pg_temp.t(31), 'FIELD_CHANGED', '{"field":"note","fieldLabel":"Notatka","to":"SECRET hunter2"}'),
    -- a TEXT field cleared: only `from` is set
    (40, 1, 2, pg_temp.t(22), 'FIELD_CHANGED', '{"field":"summary","fieldLabel":"Podsumowanie","from":"SECRET stary opis awarii"}');

-- ----------------------------------------------------------------------------------------------------------------------------
-- Read state (V19), notices (V18, V22's emailed_at) and their email switches (V22).
-- ----------------------------------------------------------------------------------------------------------------------------
INSERT INTO thread_reads (user_id, thread_id, room_id, last_read_message_id, read_at) VALUES
    (3, 1, 1, 2, pg_temp.t(6)), (2, 1, 1, 3, pg_temp.t(50)), (4, 3, 1, 8, pg_temp.t(25)), (2, 7, 2, 14, pg_temp.t(25));

INSERT INTO notifications (id, recipient_id, room_id, notification_type, actor_id, thread_id, message_id, created_at, seen_at, emailed_at) VALUES
    (1, 3, 1, 'MENTIONED',       2, 1, 1,    pg_temp.t(1),  NULL,           NULL),
    (2, 2, 1, 'MENTIONED',       3, 1, 2,    pg_temp.t(5),  pg_temp.t(30),  NULL),
    (3, 6, 1, 'MENTIONED',       3, 1, 2,    pg_temp.t(5),  NULL,           pg_temp.t(7)),
    (4, 4, 1, 'MENTIONED',       3, 2, 7,    pg_temp.t(11), NULL,           NULL),
    (5, 3, 1, 'THREAD_ASSIGNED', 2, 1, NULL, pg_temp.t(19), NULL,           pg_temp.t(19)),
    (6, 2, 3, 'MENTIONED',       3, 9, 16,   pg_temp.t(30), NULL,           NULL);

INSERT INTO user_notification_preferences (user_id, notification_type, channel) VALUES
    (3, 'MENTIONED', 'EMAIL'), (2, 'THREAD_ASSIGNED', 'EMAIL');

-- ----------------------------------------------------------------------------------------------------------------------------
-- Telemetry (V9): the ops schema.
-- ----------------------------------------------------------------------------------------------------------------------------
INSERT INTO ops.telemetry_processes (id, role, instance, started_at, last_seen, build_version, build_commit, processors, heap_max_bytes, container_limit_bytes) VALUES
    (1, 'app', 'adder-0', pg_temp.t(-600), pg_temp.t(100), '0.6.0', '1a2b3c4d', 4, 1073741824, 2147483648);
INSERT INTO ops.telemetry_samples (resolution, ts, process_id, metric, label, n, avg, max) VALUES
    (3600, pg_temp.t(0),  1, 'jvm.heap.used', '', 60, 412345678.5, 512345678),
    (3600, pg_temp.t(60), 1, 'http.requests', 'GET /api/v1/rooms', 12, 3.5, 17),
    (60,   pg_temp.t(99), 1, 'jvm.threads',   '', 1, 41, 41);

-- ----------------------------------------------------------------------------------------------------------------------------
-- The sequences follow the explicit ids, as they would after the server wrote these rows.
-- ----------------------------------------------------------------------------------------------------------------------------
SELECT setval(pg_get_serial_sequence('users', 'id'),               (SELECT max(id) FROM users)),
       setval(pg_get_serial_sequence('teams', 'id'),               (SELECT max(id) FROM teams)),
       setval(pg_get_serial_sequence('workflow_templates', 'id'),  (SELECT max(id) FROM workflow_templates)),
       setval(pg_get_serial_sequence('rooms', 'id'),               (SELECT max(id) FROM rooms)),
       setval(pg_get_serial_sequence('threads', 'id'),             (SELECT max(id) FROM threads)),
       setval(pg_get_serial_sequence('messages', 'id'),            (SELECT max(id) FROM messages)),
       setval(pg_get_serial_sequence('thread_events', 'id'),       (SELECT max(id) FROM thread_events)),
       setval(pg_get_serial_sequence('notifications', 'id'),       (SELECT max(id) FROM notifications)),
       setval(pg_get_serial_sequence('room_pins', 'id'),           (SELECT max(id) FROM room_pins)),
       setval(pg_get_serial_sequence('ops.telemetry_processes', 'id'), (SELECT max(id) FROM ops.telemetry_processes));
