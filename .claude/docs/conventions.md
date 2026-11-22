### Feature conventions

The shape every new feature follows; read in full before adding a feature, route, service, migration or page.

**Feature template — copy `teams/` (a small ADMIN-curated registry)**: it is the
only shape the foundation ships. `<feature>/<Entity>.kt` (request/response DTOs + `toResponse`)
with the `validateX` free function enforced by route AND service (in the DTO file, or a sibling
`<Entity>Validation.kt` once the rules outgrow it), `<Entity>Routes.kt` (`@Resource` typed routes
under `/api/v1/...` + `configureXRoutes()` reading services from `attributes`, `audit(...)` on
every mutation, authorization BEFORE body decoding so 403 wins over 400 — declarative and flat; past
~100 lines it delegates to private `Route.xxx(deps)` functions grouped by concern rather than
splitting into more files, which is exactly what `config/detekt/detekt.yml`'s `LongMethod`
(threshold 100) and `CyclomaticComplexMethod` (excludes `*Routes.kt`) overrides protect),
`<Entity>Service.kt` (Exposed `object` table nested inside the service, `suspendTransaction`,
soft-delete via `marked_as_deleted` + partial unique indexes, list = count + rows on one
predicate), a `V<n>__description.sql` migration (+ its checksum pin in `MigrationChecksumTest`),
spec paths in `openapi/documentation.yaml`, `cd web && npm run gen:api` (same commit), lazy pages +
`NAV_SECTIONS` entries (`web/src/utils/navigation.ts`), and an e2e spec + scenario doc +
coverage-map line. Fuller shapes (sub-collections such as room members, view endpoints, pure kernels) live in the chat packages (`chat-model.md`).

**The document-column registry (`workflows/`, V11) — the template's first fuller shape.** Same files as `teams/`, with three differences. (1) The body of
the registry is one `jsonb definition` column written through `canonicalJson` and read back with a tolerant `Json { ignoreUnknownKeys = true }`; the
validator is `validateWorkflowTemplate` (name/description limits, then the pure kernel `WorkflowRules.kt` for the structure, then the label maps), the
sanitiser trims every label of every language, and the route AND the service both run the validator. (2) Label maps are `{en, pl?}` after Lettuce's
`validateDictionaryUpdate`: `en` required, `pl` optional (a present blank is a 400), no other key (`SUPPORTED_LANGUAGES`), and per language the labels of one list are unique (`requireUniqueLabels`); the kernel sees keys only, so the
caller runs `requireLabel` over status, field AND option labels. (3) The PUT is a whole-document replace under the REQUIRED `X-Expected-Revision` header:
`requireAdmin` → `call.expectedRevision()` (400) → sanitise + validate (400) → the service locks the row `FOR NO KEY UPDATE`, `requireRevision` (409
`revision-conflict`) and writes `revision + 1`; the answer is 204 and the client re-reads for the new revision. Seeds ship as a data migration
(`ON CONFLICT DO NOTHING`) with a test that decodes them through the kernel. The room workflow (0.2.0 C9) reuses (3) and the kernel.

**The two-table registry (`tags/`, V32, 1.2.0) — the template's second registry shape.** Ported from Hammurabi as it is (`TagCategory.kt`, `TagCategoryRoutes.kt`, `TagCategoryService.kt`; Adder differences: `requireNameAndDescription` for the name rule, a `CHECK (revision > 0)` on the table, a bounded lock wait (`SET LOCAL lock_timeout`, `55P03` as the `room-busy` 429), `COLLATE "C"` ordering of names, an over-long tag never echoed whole in a 400, and the new revision in the `tag_category.updated` audit line). A category row plus child rows (`tags`), no document column: (1) the read is the paged envelope like every list (sortable `id`/`name`, default `name`; the SPA reads it whole at `pageSize=100`: `list-endpoints.md`); (2) a PUT is a whole replace of the category AND its child list under the required `X-Expected-Revision` (the workflow registry's order), and so is the DELETE; (3) every write runs in `lockingTransaction` (`infra/db/Locking.kt`, a `LOCK TABLE ... SHARE ROW EXCLUSIVE` first), which serialises two admins and covers an empty table; (4) a child is retired, never erased, and its name is unique for ever, so re-adding it revives the same row (`persistence.md` "Tag categories (V32)"). Do not use `lockingTransaction` for anything else.

**Free-text list filters** (`?name=`, `?email=`) read through `optionalBoundedString(name, maxLength)` (`infra/paging/QueryParams.kt`): trimmed, a bound in CODE POINTS (pass the stored column's own limit, never a fresh number), a `400` past it, and the same `maxLength` on the spec parameter; the SPA input carries the matching `maxLength` (`ClearableTextInput`). A list whose filter takes a repeated key uses the `repeated*` family instead (`list-endpoints.md`). A PUT body's validator is a `validateX` that the route AND the service run (the notification preferences' `validateNotificationPreferences` is the small example); a sort whitelist is DERIVED from the service's column map (`SORTABLE_COLUMNS.keys`), never typed twice.

**Wire names (frozen at 1.0.0; C-API-5).** A reference to a person is `<role>Id` (`ownerId`, `authorId`, `managerId`, `actorId`, `mentionedId`,
`pinnedById`/`createdById` for the past-participle roles), its display text `<role>Name`; plain `userId` (and the plural `userIds`, users to add) only where the row IS the user (a
roster row, `LoginResponse`, a path segment, the `<@id>` mention). A boolean is a bare adjective or participle, never `is`/`has`/`was`
(`closed`, `archived`, `seen`, `unread`, `deleted`, `actorDeleted`). A request or filter key and the response property it mirrors share one name.

**Deliberate exceptions to the template** (each with its reason; a new one is added here in the commit that introduces it):
- `auth/` (login, MFA, refresh, logout, password reset; `plugins/Health.kt`'s probes): STRING-path routes (`post("/api/v1/login")`), not `@Resource` typed ones, ported from Flow as they are. They are the public and credential endpoints: each rides its own per-IP rate-limit bucket mounted around it in `routing {}` (`RateLimits.kt`) and answers uniformly whatever the state of the account (`security.md`, `authorization.md` "The public surface"); they are never edited as a template.
- `live/` (0.4.0): a WebSocket (`webSocket {}`) beside one typed ticket POST. The upgrade `GET /api/v1/live` is public at the HTTP layer (`security: []`) and authenticates in its first frame with a single-use ticket, so the guard-before-body rule becomes admission order (bucket, Origin, 503s) and close codes instead of 401/403 (`live.md`, `authorization.md`). Nothing imports `live/` (`PackageBoundaryTest`).
- `notifications/` (0.6.0): a top layer that imports `threads/`, `rooms/` and `users/` and that nothing but the composition root imports (`PackageBoundaryTest`). `threads/` reaches it only through the `ThreadNotices` port, so a notice is made inside the write's transaction (`chat-model.md`); its routes are the caller's own rows and need no guard.
- `rooms/` (0.2.0) and `threads/` (0.3.0): member-scoped resources with the hidden-resource uniform `404`, a per-room write lock (`withRoomWrite`, which a thread or message write also runs under) and guards that take a `RoomStanding` instead of an ADMIN check: see `authorization.md` "Rooms" and "Threads" and `persistence.md` "Rooms (V13)" and "Threads (V14)".
Sibling precedents to port rather than reinvent when the chat domain needs them (`product.md` "Donors"): Toadie's `X-Expected-Revision` whole-document
PUT (ported: the template registry above; Toadie's `EventLog` too, ported for `thread_events`), Covenant's 409-on-illegal-move lifecycle.
