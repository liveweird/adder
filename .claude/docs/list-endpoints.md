### List endpoint conventions

> **Ported.** `server/src/main/kotlin/infra/paging/` (`Paging.kt`, `QueryParams.kt`, `PageResponse.kt`) is Lettuce's
> implementation, ported via Toadie; `GET /api/v1/users` is the reference. The conventions below are what the package enforces.

**The generic list rules are owned by `api-guidelines/API-GUIDELINES.md`** (cite the rule IDs):

- Offset pagination `page`/`pageSize` (1-based, default 20, max 100; out-of-range → `400`) in the
  `{items, page, pageSize, total}` envelope; `total` is computed after filters, before pagination,
  with count + page rows in the **same `suspendTransaction`**; cursor paging is a documented
  per-endpoint opt-in [API-LIST-001/002, API-STRUCT-004].
- Sorting: `sort=field`/`-field`, comma-separated multi-field, validated against a per-endpoint
  whitelist → `400`; a field named twice (with or without `-`) or a list longer than the whitelist is a `400`
  (a computed sort costs SQL per entry); `id` asc always appended as tiebreaker; default `id` asc documented
  [API-LIST-003].
- Filtering: whitelisted equality filters on the field's own name plus `field[gte|gt|lte|lt]`
  bracket operators only where needed; strict `true`/`false` booleans; enums by string name; a repeated
  scalar key is a `400` (never silent first-value-wins; unknown parameter *names* stay ignored by deliberate
  leniency), see "Repeated keys" below [API-LIST-004].
- Free text: `q` first, per-column substring only when a UI requires it [API-LIST-005]. Consumers: the directory, searches; the rest filter per column (ledger below).
- Naming: camelCase params; reusable `Page`/`PageSize`/`Sort` under `#/components/parameters`
  `$ref`'d from each list path; one `*Page` envelope schema per resource; sortable/filterable
  whitelists documented per path [API-NAME-001..004].

**Implementation shape:** `ApplicationCall.parsePaging(sortable = setOf(...))` parses
`page`/`pageSize`/`sort` from the query string against the per-endpoint whitelist into a
`PageRequest` (appending the `id`-asc tiebreaker); `Query.applyPaging(req, columns)` applies
`.orderBy(...)` + `.limit(...).offset(...)` to an Exposed `Query` via the service's file-level
`SORTABLE_COLUMNS` map (the two whitelists must agree — a missing column is an `error()`, i.e. a
500-grade invariant violation). Validation failures throw `BadRequestException` → `400` +
`ProblemDetail`. The service runs `count()` + the page rows against ONE shared predicate in ONE
`suspendTransaction` and returns an `XListResult(items, total)`; the route responds
`paging.toPage(items, total)` and the DTO file declares `typealias XPageResponse = PageResponse<XResponse>`.
New list endpoints copy `users/` (service `list`, routes `get<X>`, the OpenAPI `$ref`s to the shared
`Page`/`PageSize`/`Sort` parameters and a `*Page` schema) rather than re-parsing params.

**Substring filters:** every per-column substring filter must be case- AND accent-insensitive —
use `containsNormalized` (`infra/db/Sql.kt`, rendering
`LOWER(public.unaccent(col)) LIKE LOWER(public.unaccent(?))`, extension enabled in V4); never
hand-roll `lowerCase() like containsPattern(...)`, and keep unaccent-before-LOWER (the reverse
breaks uppercase-diacritic input under a C-locale database). The SPA mirrors it via the
theme's `foldedOptionsFilter` (`web/src/utils/text.ts`).

**Repeated keys (`IN`).** A scalar key repeated is a `400` (`singleValue`); a parameter documents `IN` semantics through the `repeatedValues` family (`repeatedLongs`/`repeatedStrings` in `infra/paging/QueryParams.kt`), declared in the spec as `type: array` with `style: form`, `explode: true` and `minItems`/`maxItems`, with duplicates collapsed (`distinct()`) before the bound applies. The ledger (which endpoint takes which key, and the bounds) is in `chat-model.md` "List endpoint ledger"; a new consumer adds a line there.

**Bounded wrapper `{items}`:** `GET /tag-categories` only (admin-curated, at most 100 x 100).

**No shape-exception exists yet.** Every list keeps the paged envelope; a purpose-built document (e.g. a thread board) answers
outside it only with a documented cap and a line here in the commit that introduces it.

**Sub-collections and facets:** a record's own unbounded sub-collection is paged like any list; the plain `{items}`
wrapper is for genuinely bounded, admin-curated sets. Facet counts (`GET .../facets`) are not implemented — port Covenant's
pattern with the first filterable list that needs them.

**Ledger.** Search (`q` rules, the `SearchPageSize` cap), pins, the keyset `id[lt]` on messages, the member, notification and thread-list filters and the repeated-key bounds: `chat-model.md` "List endpoint ledger".
