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

**Deliberate exceptions to the template** (each with its reason; a new one is added here in the commit that introduces it):
- `auth/` (login, MFA, refresh, logout, password reset; `plugins/Health.kt`'s probes): STRING-path routes (`post("/api/v1/login")`), not `@Resource` typed ones, ported from Flow as they are. They are the public and credential endpoints: each rides its own per-IP rate-limit bucket mounted around it in `routing {}` (`RateLimits.kt`) and answers uniformly whatever the state of the account (`security.md`, `authorization.md` "The public surface"); they are never edited as a template.
- `live/` (0.4.0): a WebSocket (`webSocket {}`) beside one typed ticket POST. The upgrade `GET /api/v1/live` is public at the HTTP layer (`security: []`) and authenticates in its first frame with a single-use ticket, so the guard-before-body rule becomes admission order (bucket, Origin, 503s) and close codes instead of 401/403 (`live.md`, `authorization.md`). Nothing imports `live/` (`PackageBoundaryTest`).
- `notifications/` (0.6.0): a top layer that imports `threads/`, `rooms/` and `users/` and that nothing but the composition root imports (`PackageBoundaryTest`). `threads/` reaches it only through the `ThreadNotices` port, so a notice is made inside the write's transaction (`chat-model.md`); its routes are the caller's own rows and need no guard.
- `rooms/` (0.2.0) and `threads/` (0.3.0): member-scoped resources with the hidden-resource uniform `404`, a per-room write lock (`withRoomWrite`, which a thread or message write also runs under) and guards that take a `RoomStanding` instead of an ADMIN check: see `authorization.md` "Rooms" and "Threads" and `persistence.md` "Rooms (V13)" and "Threads (V14)".
Sibling precedents to port rather than reinvent when the chat domain needs them (`product.md` "Donors"): Toadie's `X-Expected-Revision` whole-document
PUT (ported: the template registry above; Toadie's `EventLog` too, ported for `thread_events`), Covenant's 409-on-illegal-move lifecycle.
