-- The ADMIN-curated workflow-template registry (0.2.0): a registry row plus ONE definition document (Toadie's `blueprints.definition`
-- shape) - the statuses and fields are not rows, they are replaced with the document. A room COPIES a template's statuses and fields at
-- creation (V13) and edits its own copy afterwards, so a template edit or delete never touches a room.
--
-- `definition` is `{"statuses":[{key,label:{en,pl?},colour,category,initial}],"fields":[{key,label:{en,pl?},type,required,options:[{key,label}]}]}`,
-- stored through the canonical-JSON writer; the Kotlin enums are the whitelist of colour/category/type (no CHECK, the V5 rule).
-- `revision` is the optimistic-concurrency token of the whole-document PUT (`X-Expected-Revision`); every successful PUT bumps it.
CREATE TABLE workflow_templates (
    id SERIAL PRIMARY KEY,
    "name" VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    definition JSONB NOT NULL CHECK (jsonb_typeof(definition) = 'object'),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

-- Name uniqueness among ACTIVE templates only, case-insensitively (the soft-delete convention: a deleted template frees its name).
CREATE UNIQUE INDEX uq_workflow_templates_name_active ON workflow_templates (LOWER("name")) WHERE NOT marked_as_deleted;
