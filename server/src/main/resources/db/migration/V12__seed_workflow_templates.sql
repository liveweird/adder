-- The two seeded workflow templates (decision D3): "Default" and "Incident", EN and PL labels throughout. After this migration the
-- labels change only through the ADMIN template editor, never by editing this file. V11 and V12 land together, so no template
-- can exist yet and ON CONFLICT DO NOTHING is purely defensive.
INSERT INTO workflow_templates ("name", description, definition, created_at, updated_at)
VALUES (
    'Default',
    'A general-purpose flow for everyday work.',
    '{
      "statuses": [
        {"key": "open", "label": {"en": "Open", "pl": "Otwarte"}, "colour": "GRAY", "category": "OPEN", "initial": true},
        {"key": "in-progress", "label": {"en": "In progress", "pl": "W toku"}, "colour": "BLUE", "category": "OPEN", "initial": false},
        {"key": "waiting", "label": {"en": "Waiting", "pl": "Oczekuje"}, "colour": "GRAPE", "category": "OPEN", "initial": false},
        {"key": "done", "label": {"en": "Done", "pl": "Zrobione"}, "colour": "TEAL", "category": "CLOSED", "initial": false},
        {"key": "wont-do", "label": {"en": "Won''t do", "pl": "Nie robimy"}, "colour": "GRAY", "category": "CLOSED", "initial": false}
      ],
      "fields": []
    }'::jsonb,
    (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT,
    (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT
)
ON CONFLICT DO NOTHING;

INSERT INTO workflow_templates ("name", description, definition, created_at, updated_at)
VALUES (
    'Incident',
    'A flow for working an incident from triage to resolution.',
    '{
      "statuses": [
        {"key": "triage", "label": {"en": "Triage", "pl": "Triaż"}, "colour": "RED", "category": "OPEN", "initial": true},
        {"key": "investigating", "label": {"en": "Investigating", "pl": "Analiza"}, "colour": "ORANGE", "category": "OPEN", "initial": false},
        {"key": "mitigating", "label": {"en": "Mitigating", "pl": "Mitygacja"}, "colour": "BLUE", "category": "OPEN", "initial": false},
        {"key": "monitoring", "label": {"en": "Monitoring", "pl": "Monitorowanie"}, "colour": "GRAPE", "category": "OPEN", "initial": false},
        {"key": "resolved", "label": {"en": "Resolved", "pl": "Rozwiązane"}, "colour": "TEAL", "category": "CLOSED", "initial": false}
      ],
      "fields": [
        {"key": "severity", "label": {"en": "Severity", "pl": "Ważność"}, "type": "SELECT", "required": false, "options": [
          {"key": "s1", "label": {"en": "S1", "pl": "S1"}},
          {"key": "s2", "label": {"en": "S2", "pl": "S2"}},
          {"key": "s3", "label": {"en": "S3", "pl": "S3"}},
          {"key": "s4", "label": {"en": "S4", "pl": "S4"}}
        ]},
        {"key": "component", "label": {"en": "Component", "pl": "Komponent"}, "type": "TEXT", "required": false, "options": []},
        {"key": "jira-link", "label": {"en": "Jira link", "pl": "Link do Jiry"}, "type": "URL", "required": false, "options": []},
        {"key": "eta", "label": {"en": "ETA", "pl": "ETA"}, "type": "DATE", "required": false, "options": []}
      ]
    }'::jsonb,
    (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT,
    (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT
)
ON CONFLICT DO NOTHING;
