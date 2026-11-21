# Frontend (`web/`)

Vite + React 19 + TypeScript SPA: the shell + auth, user/feature management, MFA, password reset,
the changelog, the flat-teams registry and the ADMIN telemetry panel — the v0.1.0 foundation, trimmed from Flow
(`~/Sources/flow` @ 4e43e480, 1.57.1, the donor: port building blocks from there, don't reinvent; Astrolabe's trim of the same
shell is the precedent) — plus, since 0.2.0, the ADMIN workflow-template pages and rooms (the Rooms page, the sidebar rooms rail, room settings, members, the room workflow editor and — since 0.3.0 — the threads of a room: the three-pane room view and its thread list, the new-thread form, a thread's header and its conversation — the messages and the composer — and its side panel: status, owner, details, history) — and, since 0.4.0, live updates: one WebSocket per tab in the shell turns id-only server hints into targeted query invalidations (`.claude/docs/live.md`, `web-internals.md` "The live channel"), and, since 0.5.0, finding things: the thread list's filters (in the URL), hashtag links and the two search pages (`/rooms/:id/search`, `/search`; `web-internals.md` "Search"). Routes are lazy.

- Dev server: `cd web && npm run dev` (port **5179** — not Vite's 5173 default, so Adder can run
  beside the sibling stacks' 5173–5178 and 5180; the port table is in `.claude/docs/ci.md`). All backend routes live under the
  `/api/` namespace and Vite proxies the single `/api` subtree → `http://localhost:8087`. Any
  other path is served as `index.html` so React Router owns the SPA URL space and browser reloads
  don't collide with API routes.
- Production build: `cd web && npm run build` → static files in `web/dist`. In the Docker image
  these are baked in and served by the Ktor server itself (via `WEB_STATIC_DIR`; see
  `plugins/Routing.kt`), so production is single-origin and there is no Vite proxy — the SPA and
  `/api` share `http://localhost:8087`.
- Regenerate API types: `cd web && npm run gen:api`. Reads
  `server/src/main/resources/openapi/documentation.yaml` directly (no server needed) and writes
  `web/src/api/schema.ts`. Run this after editing the OpenAPI spec; commit the regenerated
  `schema.ts` in the same change.
- **Quality gates (the frontend counterpart of the backend's detekt)**: `npm run lint` carries
  `eslint-plugin-sonarjs` (recommended) plus core size/complexity backstops — zero-findings gate;
  rule tuning lives in `eslint.config.js` ONLY, one commented override per deliberate idiom, and
  any inline `eslint-disable` needs a justifying comment. `npm run lint` runs with `--cache --cache-strategy content`
  (`web/.eslintcache`, gitignored; a warm run is ~0.6 s against ~12 s, CI restores the file between runs —
  Flow's `build-times.md` WHY 20); the config is not type-aware, so a per-file cache is sound, and a config change or an
  ESLint/node upgrade re-lints everything on its own. `npm run knip` is the dead-code gate
  (unused files/exports/dependencies; `knip.json` ignores only the
  `ajv` devDependency pinned only for `@stoplight/spectral-cli`'s resolution) — keep exports that
  only the declaring file uses un-exported, and delete what knip flags rather than ignoring it.
  **The API-contract gate**: `npm run lint:api` Spectral-lints the OpenAPI spec and
  `api-guidelines/examples/conformant.yaml` with `--fail-severity=warn`, through Adder's local ruleset
  `web/spectral.adder.yaml` (it `extends` the shared `api-guidelines/api-guidelines.spectral.yaml`, which is the other
  team's area and never edited; it switches off only the two registered-gap rules `x-sla`/`termsOfService`, each with
  its reason). A finding is fixed in the spec; a new local `off` needs an inapplicability comment, never a fixable finding;
  `npm run check:api` (`scripts/check-api.mjs`) regenerates the API types in memory and diffs
  them against the committed `src/api/schema.ts` — it must never overwrite that file, so
  `gen:api` stays the one command developers run to update it.
- **Build version stamp**: `vite.config.ts` injects `__APP_COMMIT__` (short sha, `+dirty` when the
  worktree has uncommitted changes) and `__APP_COMMIT_TIME__` (commit ISO timestamp) via `define`,
  declared in `src/vite-env.d.ts`. Env vars `GIT_SHA`/`GIT_COMMIT_TIME` override the local-git
  lookup — the Dockerfile's SPA stage sets them explicitly (its worktree never matches the index,
  so the dirty check would false-positive), and CI can do the same when building without `.git`.
  `src/components/VersionStamp.tsx` renders `v<APP_VERSION> · <sha> · <time>` at the bottom of the
  navbar (`App.tsx`) and under the login card (`components/AuthCard.tsx`).

## Layout conventions

- **Flat directories**: `pages/`, `components/`, `hooks/`, `utils/`, `api/`, `changelog/`,
  `locales/{en,pl}/`, `test/` — no deeper nesting, no per-feature folders (a feature contributes
  files into these).
- **Default exports for components/pages**, named exports for everything else; **no path
  aliases** — relative imports only.
- **Co-located tests**: `Foo.test.tsx` sits beside `Foo.tsx`; shared test scaffolding lives in
  `src/test/` (`setup.ts` forces `en`, `render.tsx` is the provider wrapper — it and every
  file-local `MantineProvider` must pass `env="test"`, or Select/Popover interaction silently
  fails under happy-dom; `http.ts` holds the fetch stubs).
- **CSS modules only where the theme can't express it**: styling belongs in `src/theme.ts`
  (Mantine `createTheme` — component `extend`s, defaultProps) with `src/theme.module.css` for the
  class-level parts; a per-component `*.module.css` is the exception, not the pattern.
- Pages are **lazy** (`React.lazy` in `App.tsx`); new routes register above the `path="*"`
  NotFound catch-all (LAST child, never feature-gated). **The nav model lives in
  `utils/navigation.ts`**, shared by the sidebar and the command palette: `NAV_SECTIONS` —
  labelled, always-open sections (currently Overview — Home and Search —, Rooms — the static "All rooms" leaf — and Administration: Teams, Users, Workflow templates, Feature flags) of `NavLeaf`s (the
  `label` is a typed i18n key; `adminOnly?` gates admin leaves, `keywords?` adds raw palette search terms for a renamed page's old name, and `visibleSections(isAdmin())`
  drops an emptied section) — plus `ACCOUNT_NAV` (Change password, Notification preferences, Changelog), which the header
  `UserMenu` and the palette render and the sidebar never does. Sections are static captions
  (`role="group"` + `aria-label`), never toggles: every leaf is always in the DOM, so tests and
  e2e address the links directly; leaf clicks close the mobile drawer; `activeNavPath` is the
  longest-prefix matcher over the leaves and the rail's rooms (it reads only `to`/`activeFor`, so `/rooms/12/members` lights room 12, not "All rooms"). **The icon rail (Lettuce's):** a header `ActionIcon`
  beside the Burger (`appShell.toggleNav`, `data-expanded` while expanded) narrows the desktop
  navbar from 240 to 64px — `rail = navCollapsed && !opened`, remembered under
  `adder.viewSettings.appShell.navCollapsed`; in rail mode each leaf is an icon-only `NavLink`
  (`classes.railLink`) whose `aria-label` IS the label (so every by-name locator keeps working)
  inside a right-hand `Tooltip`, section captions become hairline `Divider`s. The mobile overlay
  always shows full labels.
- **The rooms rail** (`components/RoomsRail.tsx`, drawn by `Shell` inside the Rooms section; the queries live in `hooks/useRailRooms.ts` under `["rooms", "rail", …]`, so any `["rooms"]` refresh updates it): one link per room of the caller (`GET /rooms?member=true&archived=false&sort=-lastActivityAt&pageSize=100`), an initials avatar named by the room in the icon rail, a "Show archived" toggle (`rooms.rail.showArchived`, a second `archived=true` query, dimmed under a divider) and a "More rooms…" link past 100; the command palette's Rooms group reads the same active query. **The room view** (the room's Threads tab, `pages/RoomHome.tsx`: the thread list beside the pane the route names; `/rooms/:id`, `/rooms/:id/threads/new` and `/rooms/:id/threads/:number` all render it): `components/ThreadList.tsx` (Open/Closed/All, the order incl. "Workflow order", the filter panel `ThreadFilters.tsx` — status, hashtags (`RoomTagSelect.tsx`), owner/"Mine"/"Mentions me"/"Watching" and the SELECT/MULTI_SELECT/USER fields, over Lettuce's ported `FilterMultiSelect.tsx` + `accessiblePill.tsx` — a labelled `nav` of whole-row links, `PaginationBar` without the page-size picker, 50 a page; **its state is the URL** — `scope`, `order`, `status`, `owner`, `mention` (only `me`), `watching` (only `me`), `tag`, `field`, `page`, parsed and written by `utils/threadListParams.ts` + `hooks/useThreadListParams.ts` over the ported `hooks/useLiveSearchParams.ts`, writes by `replace`, the last non-default query per room remembered and restored onto a bare URL, and every in-room link carries the keys through `withListParams` in `utils/roomLinks.ts`), `NewThreadForm.tsx` (title, a `MentionTextarea` body, required fields first and the rest behind "More fields"; `ThreadFieldInput.tsx` per field type, `RoomMemberSelect.tsx` for USER) and `ThreadPane.tsx` (the header, the side panel — `ThreadSidePanel.tsx`: `ThreadStatusControl.tsx`, `ThreadOwnerSelect.tsx`, `ThreadDetails.tsx` with `ThreadDetailsForm.tsx` and `ThreadFieldValue.tsx`, `ThreadHistory.tsx` over the ported `EventTimeline.tsx` — then the conversation: `MessageList.tsx`, `MessageItem.tsx` (a #hashtag in a body is a router link to the thread filtered by it: `MarkdownView`'s `tagHref`, `utils/remarkHashtags.ts`; a `<@id>` token reads as the person's name when the message's `mentions` list the id: `MarkdownView`'s `mentions`, `utils/remarkMentions.ts`, `utils/mentions.ts`, `web-internals.md` "Mentions in messages"; its kebab menu also carries "Pin"/"Unpin", `MessagePinMenuItem.tsx`), `MessageEditForm.tsx` and `MessageComposer.tsx`, whose box and the new-thread body's is `MentionTextarea.tsx` over `EmojiTextarea` (typing `@` lists the room's members; the caller keeps the canonical `<@id>` text and the box shows `@Name`: `utils/mentionText.ts`, `utils/knownNames.ts`, `web-internals.md` "Mentions in messages"); `api/messages.ts`). Layout (a container query on the main area), the draft kept above the routes, focus, heading levels (room name h2, pane h3, side-panel sections h4) and query keys: `.claude/docs/web-internals.md` "The room view"; the side panel's rules (who may close, the details editor's revision and `null`-clears draft rules, the history): "The thread side panel". **The conversation is the one list that does not use `PaginationBar`** (the exception, by design): a chat reads oldest-first with the newest at the bottom, so `MessageList` is a `useInfiniteQuery` timeline (Lettuce's `Kudos.tsx` shape turned upside down) with "Load older messages" at the TOP, keyset-paged through the server's `id[lt]` cursor, scrolled to the bottom on open (unless a `?message=` asks for a jump: older pages are loaded, at most 10, until it shows, and it is focused and highlighted), after the caller's own post and (under a reader at the bottom) when it grows; still no polling and no optimistic updates (a write re-reads its narrow keys, the whole `["rooms"]` prefix only on an access refusal; since 0.4.0 the server's live hints invalidate the same keys, without cancelling a fetch). Details: `web-internals.md` "The conversation". **Search** (`pages/RoomSearch.tsx` = the room's Search tab, `pages/Search.tsx` = the Overview leaf; both are `components/SearchBody.tsx` over `SearchForm.tsx` and `SearchResults.tsx`, snippets drawn by `Snippet.tsx` as text and `<mark>`, never HTML): the term, the Open/Closed/All scope and the page live in the URL (`utils/searchParams.ts`, `hooks/useSearchUrl.ts`), the term is checked client-side first, and the three 429s each have their own words; details: `web-internals.md` "Search". A room's pages share `components/RoomPage.tsx` (load triage — a missing room and a non-member are one 404 — header with the "Pinned (n)" button of `components/PinnedPanel.tsx`, `RoomTabs`); **unread** (0.6.0: the rail's dot and `@` count, the thread list's bold rows and counts, the conversation's "New" separator, "Jump to the first new message", the arrivals button and the read marker `hooks/useThreadReadMarker.ts`, `hooks/useRoomSeen.ts` from `RoomPage`; marks are words in the link's DESCRIPTION, never its name; an ADMIN outside the room sends no read write; `web-internals.md` "Unread"); **pins** (0.6.0: `hooks/useRoomPins.ts` under `roomKeys.pins`, `api/pins.ts`, `utils/pins.ts`, "Pin this thread" in the pane via `ThreadPinButton.tsx`, a pin mark on a thread's list row; `web-internals.md` "Pins"); **watching** (1.1.0: "Watch this thread" in the pane via `ThreadWatchButton.tsx` with `utils/watch.ts`, an eye mark on a thread's list row, the "Watching" filter; the thread carries its own `watching` flag; `web-internals.md` "Watching"); `utils/roomLinks.ts` spells the route family out and `utils/roomAccess.ts` is the UX-only owner/ADMIN check (the server guards). The room's Members tab (`pages/RoomMembers.tsx`: a paged table, `components/RoomMembersAdd.tsx` for the person and whole-team adds, row actions through `RowActionsMenu`, one "Leave room" button in the Members header for any member, which drops the room's queries only after the page has unmounted; the controls for an OWNER or an ADMIN, UX only; a 403/404/409 from any member write re-reads the room via `isAccessRefusal`) is in `RoomTabs`; the failure of an add shows from `MembersBody` (not the add controls, which a 403 removes). **The Workflow tab** (`pages/RoomWorkflow.tsx`, `components/RoomWorkflowForm.tsx`) is the workflow editor with one label column, the template editor's seed-once shape: it reads under its own `["rooms", "workflow-edit", id]` key (`gcTime: 0`, so a visit starts from a fresh read), seeds the draft once and sends `X-Expected-Revision`; an OWNER or ADMIN edits, everyone else (and everyone in an archived room) reads the same workflow as plain text (`RoomWorkflowView`, keyboard- and screen-reader-reachable) under a note, and the editor locks (a disabled `<fieldset>`) while a save is in flight; the save `error` lives in the page, because a refusal that turns the editor read-only re-seeds the form (so the read-only view shows the stored workflow, not the draft); after a re-seed focus moves to the workflow group; a failed read offers Retry; a `409 revision-conflict` keeps the draft and offers Reload, a `409 status-in-use` re-opens the retire dialog for its `keys`, any 403/404/409 re-reads the room (`isAccessRefusal`), and a save whose re-seed read fails freezes the editor behind a reload prompt (Lettuce's `staleAfterSave`).
- **The shell header** is 48px: brand, the always-mounted `LiveStatus` region (`components/LiveStatus.tsx`, `role="status"`: empty while the live channel is up, an orange "Reconnecting…" badge after 5 s down, "Live updates off" at once when the per-user socket cap refuses this tab), the notification bell (`components/NotificationsButton.tsx`: the unseen badge and a drawer of notices with an All / Mentions switch, `web-internals.md` "The notification bell"), the `CommandPalette` trigger, and the `UserMenu` (avatar →
  identity from `GET /users/{id}` under the `["user", id]` key, language, theme as a three-state
  SegmentedControl, Change password, Notification preferences (`pages/NotificationPreferences.tsx`: an In-app and an Email switch per kind of notice, grouped, `web-internals.md` "The notification preferences"), Changelog with the what's-new badge, for an ADMIN a "Telemetry panel" toggle, Sign out). The version
  stamp stays in the sidebar footer. **`components/CommandPalette.tsx`** wraps
  `@mantine/spotlight` (pinned to the core version — keep every `@mantine/*` on one version) over
  a module-level store (`utils/commandPalette.ts`): the session's pages, the caller's rooms, from a searchable term on a Search group ("Search all rooms for …" and, inside a room, "Search {room} for …": they open the search pages with the term in the URL and pass the label filter untouched), plus an ADMIN-only show/hide action for the telemetry panel. No
  `highlightQuery` — it splits labels into `<mark>` fragments that role/name locators cannot
  match.
- **Page chrome**: every page is a `Stack gap="md"` on the canvas starting with
  `components/PageHeader.tsx` (`title` → the `order={2}` heading tests locate, `description?`,
  `backTo?`, right-aligned `actions?` — the ONE place a page's primary "New …" button lives — and
  a `toolbar?` row). `PageHeader` also titles the browser tab ("{title} · Adder", `hooks/useDocumentTitle.ts`; `documentTitle?` when the tab needs more than
  the heading, as a room's tabs do; `AuthCard` does the same for the signed-out pages), so a new page gets its title by having a header.
  No `Container` and no page-wrapping `Paper`; widths come from
  `utils/layout.ts`: full-bleed lists, `Box maw={CONTENT_MAX_WIDTH}` for registry tables and the
  changelog, `Paper withBorder p="xl" maw={FORM_MAX_WIDTH}` (left-aligned) for the simple field
  forms. The one spinner is `components/LoadingBlock.tsx` (`TableLoadingRow` wraps it);
  `components/EditPageLoadState.tsx` is the edit pages' shared load-failure triage (centered
  loader, else a back-to-list alert); `EmptyState` takes the Tabler icon COMPONENT and sizes it
  itself (an optional `hint` is the next step under the label). **One heading outline everywhere**: the `PageHeader` title is the h2, a page's cards/sections are h3
  (`<Title order={3} size="h4">` keeps the look), their inner blocks h4; and every `Table` has an accessible
  name (`aria-label` — the words of the heading above it, or a `<area>.tableAria` key). Page tests pin the
  outline with `test/headings.ts`'s `headingOutline()`.

## The typed API layer (`src/api/`)

The OpenAPI spec at `server/src/main/resources/openapi/documentation.yaml` is the contract between
backend and frontend — hand-maintained, not auto-generated from routes. Swagger UI is at
`http://localhost:8087/openapi` (dev). The layer is small hand-written modules:

- **`http.ts`** (transport): `authedFetch()` with the single-flighted silent refresh on a 401. A DEFINITIVE rejection signs the session out, a TRANSIENT failure keeps it — **never collapse the two**; stale requests and refresh completions must not publish credentials, clear a newer login or retry across session identities. `ApiError` (read RFC 7807 fields via the `detail`/`instance`/`type` getters, never hand-cast `err.body`); `jsonRequest<T>()`/`voidRequest()` for every ordinary endpoint wrapper; `buildQuery()` for list wrappers (`false` and `0` ARE sent). Every transport fetch carries `timeoutSignal()` — new `fetch` call sites in `api/` must attach it. Refresh/wrapper internals: `.claude/docs/web-internals.md`.
- **`session.ts`**: token/roles/userId under the `adder.auth.*` localStorage keys (`persistSession`, `persistRefreshedSession`, the `isAdmin()`/`getUserId()` accessors); ADMIN-dependent components use `useAdmin()` from `auth.tsx`. Details: `.claude/docs/web-internals.md`.
- **`live.ts`**: `mintLiveTicket()` for the live socket (`POST /api/v1/live/tickets`); it goes through `authedFetch` but keeps the response headers, so a refusal is a `LiveMintError` (an `ApiError` with the `Retry-After`). The socket itself is `utils/liveClient.ts`, never a wrapper here (the ticket travels in the socket's FIRST frame, never in a URL). Details: `.claude/docs/web-internals.md` "The live channel".
- **`utils/roomKeys.ts`**: the query-key prefixes of a room's data (`roomKeys.detail(id)`, `.threadList(id)`, `.messages(id, n)` …), spelled ONCE; every reader and refresh call builds its key from it, because the live channel invalidates by the same prefixes (a guard test fails a hand-spelled one).
- **Session boundary** (`components/SessionBoundary.tsx` + `utils/sessionQueryCache.ts`): binds the root `QueryClient` to logical session identity — a different sign-in, sign-out or cross-tab change must synchronously remove caller-scoped query data before the next account renders, a token rotation within one identity must retain it, and late requests from the previous identity must not repopulate the new account's UI or cache. Keep this at the session boundary rather than clearing individual page keys. Mechanics: `.claude/docs/web-internals.md`.
- **`auth.ts`**: login + the MFA exchange, self-service password reset, and logout (`logout()` is
  best-effort — local credentials are cleared immediately, before awaiting the revoke POST for the
  captured token pair; a delayed completion never clears a later login, and the function never
  throws).
- The types come from the generated `schema.ts`; a new endpoint's wrapper goes into its feature's
  module (a new feature area gets a new module — never a catch-all file). Avoid heavyweight client
  generators (Orval/Kiota) — the lightweight pairing of `openapi-typescript` (types only) +
  hand-written fetch is intentional.

Explicit sign-out opens `/login` without a saved return destination (a self password change signs out the same way, with its own notice: `flagSignedOut("passwordChanged")`); anonymous access to a protected link preserves its path. Reading the signed-out notice during render must be side-effect free (acknowledge it after the login page commits). Test logout navigation with the real route guards from a non-home page. Details: `.claude/docs/web-internals.md`.

`openapi-typescript` is installed with `--legacy-peer-deps` because its declared peer is TS `^5`
while the scaffold uses TS 6; the generated output is compatible. If you re-`npm install` from
scratch, use `npm install --legacy-peer-deps`.

## Error handling

- **ErrorBoundary** (`components/ErrorBoundary.tsx`): a page render crash must never white-screen
  the app. `RouteErrorBoundary` wraps the `<Outlet />` inside `AppShell.Main` (header/nav survive;
  keyed by `location.pathname`, so navigating anywhere recovers), and a plain `ErrorBoundary` in
  `main.tsx` is the last resort for shell crashes. Don't add per-page boundaries — the two mounts
  are the model.
- **Catch-all 404**: `pages/NotFound.tsx` is the LAST `path="*"` child of the Shell route. New
  routes go above it.
- **Chunk-load recovery**: `main.tsx` listens for `vite:preloadError` (a redeploy 404s the old
  hashed chunks) and reloads once, rate-limited via sessionStorage (`adder.chunkReloadedAt`, max
  one reload/minute) so a genuinely missing chunk falls through to the ErrorBoundary instead of
  looping.
- **Query retry policy**: the `QueryClient` in `main.tsx` uses `shouldRetryQuery` (`api/http.ts`)
  — NEVER retry a 4xx (the answer won't change; retrying only delays the error UI), at most two
  retries for transient failures.
- **Messages**: never render `error.message` (it's the internal `API <status>` / the browser's
  "Failed to fetch") — map statuses to i18n keys via the shared mappers in `utils/saveError.ts`:
  `saveErrorMessage(err, t, keys)` for mutations (per-status keys + a `failed` fallback) and
  `loadErrorMessage(err, t)` for list loads. A bucket's 429 on a write says how long to wait through the shared `common.error.tooFast`
  (`retryAfter: true` in the keys; seconds from `ApiError.retryAfterMs`, which `jsonRequest`/`voidRequest` fill from `Retry-After`; the
  room writes use it). Errors render inline as `color="red" variant="light"`
  Alerts — never as toasts. A failed QUERY renders through `components/ErrorAlert.tsx`
  (`error`, optional `title`), never an inline `<Alert>{loadErrorMessage(…)}</Alert>`; a form/action failure
  (an already-mapped string) renders through `components/FormErrorAlert.tsx`, never a hand-written red `<Alert>`.
- The `@mantine/notifications` host is mounted in `main.tsx` (top-center, autoClose 2500, limit 3
  — deliberately not in App, so unit tests never mount it). **Success toasts only, with fixed
  vocabulary only** (`showSuccessToast(t("<area>.toast.*"))` in `utils/toast.tsx` — teal, never
  user-entered values; errors stay inline).

## Shared list-page building blocks (the Users.tsx template)

Every list page composes the same ported Lettuce blocks — copy `pages/Users.tsx`, don't re-derive:

- **State**: filters in `useStoredState` (persisted under
  `adder.viewSettings.<viewKey>.filter.*`, text filters debounced 300 ms — the DEBOUNCED value
  goes into the query key, and any action reading the filters reads the DEBOUNCED value too, so a
  click never operates on a slice the table isn't showing), sort/page/pageSize from
  `usePagedSort(initialSort, filterDeps, { key, sortFields })` with `SORT_FIELDS ... as const`.
  Every query derived from one area keys under that area's prefix (`["users", …]`, `["teams", …]`),
  so refreshing an area prefix after a mutation refreshes all its active queries and invalidates
  inactive cached data.
- **Query**: `useQuery({ queryKey: ["<area>", page, pageSize, sortParam, ...filters], queryFn: list<Area>(...), placeholderData: keepPreviousData })`.
- **Chrome**: `FilterPanel` (collapsed by default, persisted, active-count badge) +
  `ClearableTextInput` for freetext filters (debounced); `SortHeader` renders its OWN `Table.Th`
  (so the sort state lands as `aria-sort` on the header cell — don't wrap it in another Th);
  `TableLoadingRow` (`isLoading && !data`) / rows / `EmptyState` (`!isError`) triage in the tbody;
  a `color="red" variant="light"` Alert with `loadErrorMessage` ABOVE the table on error;
  `PaginationBar` below.
- **The colour vocabulary is app-wide, not per-page**: the olive `adder` brand = the interactive accent only (primary CTA,
  active nav item, focus); red = hard/blocking (validation errors, destructive confirm buttons, a failed row, a violated
  constraint); orange = warning / waived (a soft finding, drift); gray = neutral state; blue = in progress; grape = waiting;
  teal = success (success toasts and notices) — never green or lime, which sit beside the olive. A new status colour goes through that vocabulary, not a page-local pick.
  **Unread and unseen cues are blue, drawn by `components/UnreadCue.tsx` and nothing else** (rail dot and count, thread-list count and `@` mark, the conversation's "New" marker, the bell, the drawer's unseen dot, the what's-new dot): never red (red = blocking), never olive; the cue is `aria-hidden` and the host carries the words (`web-internals.md` "Unread"). A `Badge` with no `color` is **gray** by the theme default, so a meaning is always a hue passed on purpose.
- **Delete**: `useDeleteConfirm` + `ConfirmDeleteModal` — the hook owns modal state and the
  success toast, the page owns cache refresh. For mutations while a list remains mounted, use
  `refreshQueriesAfterMutation(queryClient, ["area"], ...)` from `utils/queryRefresh.ts` after
  success. It cancels all affected query groups before invalidating them: a newly keyed initial
  fetch has no cached data even when `keepPreviousData` displays rows, so default invalidation
  alone may reuse a pre-mutation response. Keep refresh out of failed mutation paths.
  **`ConfirmActionModal`** is the non-destructive sibling (Lettuce's, ported with the
  feature-flags bulk actions) — a message plus a neutral cancel and a confirm button whose
  `loading` blocks cancel/close while the action runs; labels arrive already translated; an action that can fail in place passes
  `error` (+ `errorTitle`) for the inline alert (the Users page's password reset). One page-number control, `components/Pager.tsx`, serves
  `PaginationBar` and the narrow pagers (the notification drawer, the history).
- **An entity's NAME is the way into its detail page** — a `RouterLink` `Anchor` with an
  interpolated accessible name (`common.action.editAria` = "Edit {{name}}", or an area-specific
  `openAria`), which is what tests and e2e locate. A real link, not an onClick: cmd/middle-click
  opens a tab and it takes keyboard focus. Route families are spelled out ONCE in
  `utils/<area>Links.ts` — never hand-assemble a URL.
- Row action buttons carry interpolated aria-labels (`<area>.editAria` etc.) — unit tests and e2e
  locate by them; table tests query cells by **text**, not `cell` role names. **Row actions**: a
  row with more than two actions bundles them under `components/RowActionsMenu.tsx` — an
  icon-only kebab whose accessible name is `common.table.operationsAria` ("Operations for
  {{name}}"; items are plain `menuitem`s, only one row's menu is open at a time); a per-row
  `Switch` (Feature flags) is state, not an action.

## Registries (the Teams.tsx template)

The small ADMIN-curated registries (Teams today; more arrive with the chat domain) compose
`useRegistryListControls` for their persisted name filter, debounce and paged sort, and
`RegistryListTable` for the common load/error/empty/pagination states. Each page owns its query
key and parameters, extra filters, columns, row actions and mutation refresh prefixes. Their
editors (e.g. `components/TeamEditorModal.tsx`) share the `RegistryMetadataFields` (name +
description, with `utils/charCount.tsx`'s "123 / 4000" counter) and `RegistryEditorActions`
(save-error alert + cancel/submit footer) components; form rules
(`utils/formRules.ts`'s shared `nameRule`/`descriptionRule`), submit/conflict handling and any
parent/target fields stay local. Keep each registry's field limits explicit and mirrored from the
server's own validation (`infra/validation/Text.kt`'s `requireNameAndDescription`).

**The workflow-template pages** (`WorkflowTemplates.tsx`, `CreateWorkflowTemplate.tsx`, `EditWorkflowTemplate.tsx`, all under `RequireAdmin`) are the registry shape with a full-page editor instead of a modal: `components/WorkflowTemplateForm.tsx` owns the draft, the dirty check (`utils/workflowTemplateForm.ts`), the Cancel confirm and the before-unload guard, and the page owns the request. The edit page seeds the form ONCE with the revision it read (a background refetch never rebases the draft; `gcTime: 0` so a visit starts from a fresh read) and sends it as `X-Expected-Revision`; a `409 revision-conflict` keeps the draft and offers Reload, which re-seeds on the user's click. Save is disabled while an edit is clean. The wire types in `utils/workflowForm.ts` ARE the generated `schema.ts` types (`workflowForm.test.ts` pins the vocabularies equal), so a spec change fails the compile.

**The room workflow** (`/rooms/:id/workflow`, `RoomWorkflow.tsx` → `components/RoomWorkflowForm.tsx` over the shared `WorkflowEditor.tsx`, which also drives the template form: `labelColumns` 1 for a room, 2 EN+PL for a template) follows the same draft rules: seeded ONCE from the revision read, re-seeded only by re-keying the form (after a save and on the conflict alert's Reload, `RevisionConflictAlert`), `X-Expected-Revision` on the whole-document PUT, dirty check + `useDiscardGuard`. Only the room's OWNER or an ADMIN on a non-archived room edits (UX only; anyone else gets the read-only `RoomWorkflowView` under a note saying why). Rows are controlled and every edit is a whole next draft (`utils/workflowForm.ts`): a persisted field's type is locked, statuses and fields are retired and revived by key (a removed row keeps an Undo notice), and removing a status asks for a same-category replacement in `RetireStatusModal` (`replacements` = `{retiredKey: replacementKey}`; `409 status-in-use` re-opens the dialog for the keys it names; unit-tested and, since 0.3.0, covered by an e2e scenario in `room-workflow`: threads using the status are moved to the replacement). A save that committed but whose re-read failed FREEZES the editor behind a reload prompt (its rows lack the new revision).

**Searchable pickers** (Astrolabe's, ported): `components/SearchPicker.tsx` owns the search-box mechanics (a debounced server-side term, the caller holds the pick so its label survives later searches); thin wrappers own the query and the texts — `TeamSelect` (the teams registry) and `UserDirectorySelect` (`GET /users/directory`, open to every signed-in user; the server REQUIRES a term, so the picker searches only from two characters, page size 20, and shows a hint below that — `minTermLength`/`minTermHint`). `TeamManagerSelect` stays on the ADMIN-only users list. A team's roster (`components/TeamMembers.tsx`, the Members h3 of `/teams/:id`) shows its controls to an ADMIN or the team's manager (`managerId === getUserId()`; UX only, the server guards).

## Forms (the CreateUser/EditUser template)

- Shared vocabulary in `utils/<area>Form.ts`: the `<Area>FormValues` type, length constants
  mirroring the server's, a `<area>FormValidation(t)` factory (rules identical to the server's —
  keep them in sync), and the mappers (`to<Area>FormValues(response)` to prefill, `to<Area>Body(values)` for the request, e.g. `teamForm.ts`). The field block lives in
  `components/<Area>FormFields.tsx`; the pages own submit/error/navigation.
- Edit prefills via **`form.initialize(...)` guarded by `!form.initialized` during render** —
  never a `useEffect`. Submit is a plain async fn with local `submitting`/`error` state wrapped by
  `form.onSubmit`; success → `invalidateQueries` (list + detail) → `showSuccessToast` →
  `navigate(list, { replace: true })`; failure → `saveErrorMessage` into an inline Alert.
- **Widths**: `Paper withBorder p="xl" maw={FORM_MAX_WIDTH}` under a `PageHeader` (with `backTo`
  to the list) for simple field forms — left-aligned, never a centred `Container`.

## User management and feature flags

Passwords are generated client-side and revealed exactly once (`components/OneTimePasswordModal.tsx` sets `closeOnClickOutside={false} closeOnEscape={false}`; closing drops the plaintext for good). The management routes sit under ONE `<Route element={<RequireAdmin />}>` in `App.tsx` — no page redirects itself, their queries stay `enabled: isAdmin()`; UX only, the server's `requireAdmin` is the rule. Users live under `/users` (list, `/new`, `/:id/edit`, `/:id/features`; `utils/userLinks.ts` spells the family out — never hand-assemble a URL); feature flags (`/users/:id/features`, ADMIN `/feature-flags`): mind the wholesale-replace semantics — a PUT whose disabled set omits `MFA` ENABLES it. Page narratives (`pages/Users|CreateUser|EditUser|ChangePassword|FeatureFlags|UserFeatures.tsx`): `.claude/docs/web-internals.md`.

## Per-feature rules (every page)

The ADMIN telemetry panel (`components/Telemetry*.tsx`, the shell's right-hand aside: memory, storage, requests, JVM) is the only chart surface in 0.1.0. Rules that apply to every page:

- **Charts** (any page): charts live only in lazy chunks, each chart component imports `@mantine/charts/styles.css` itself, a legend for two or more series, one axis (never dual), every chart has its numbers in a table beside it, colour is never the only carrier of identity, and colours come from the existing vocabulary in `utils/chartColors.ts` (no new hue for meaningful series; series keyed by arbitrary names — statuses, columns, people — draw from its `CATEGORY_PALETTE`; every mark clears WCAG 1.4.11, ≥ 3:1). Tests mock `@mantine/charts`. 
- **Dates** render deterministically (`YYYY-MM-DD` / `YYYY-MM-DD HH:mm` via `utils/formatDate.ts`), never `toLocaleString()`, and in the reader's
  own zone (`browserTimeZone()`) on every list and in the chat; only the telemetry panel and reports read a configured or UTC zone. Tests that print a
  date pin the zone with `test/timeZone.ts`'s `pinBrowserTimeZone`.
- **Failures render inline, never as a toast** (see "Error handling"); success toasts use the fixed vocabulary only.

## Internationalization (i18n)

The SPA is **N-language by architecture** via react-i18next (`src/i18n.ts`); the shipped bundles
are English (THE default and fallback everywhere) and Polish. All user-facing strings go through
`const { t } = useTranslation()` / `<Trans>` — **no hardcoded UI text**. Conventions:

- **Resources** live in `src/locales/{en,pl}/<area>.json`, one file per area (`appShell`, `auth`,
  `changelog`, `common`, `home`, `notifications`, `rooms`, `search`, `teams`, `telemetry`, `threads`, `users`, `workflows`); `i18n.ts` merges them into a single
  `translation` namespace, so keys read `area.key` (e.g. `t("auth.signIn")`). Only EN is
  statically imported — its typed `en` tree is the key canon AND the runtime fallback; every other
  language is auto-discovered from `locales/<lang>/` via `import.meta.glob`. Bundles are eager on
  purpose; move non-EN to lazy loading when a 3rd language ships or a bundle grows large.
- **Keys are typed**: `src/i18next.d.ts` augments i18next's `CustomTypeOptions` with `typeof en`
  from `i18n.ts` (the `@public`-tagged export — knip can't trace the d.ts consumer), so every
  `t()` call and `i18nKey` is compile-checked against the EN tree — a typo or removed key fails
  `tsc`/`npm run build`. Fields holding a key are typed `ParseKeys` (from `i18next`), never
  `string` (the `NavLeaf` `label` pattern in `utils/navigation.ts`); functions taking a translator
  take `TFunction`, never a hand-written `(key: string) => string`.
- **`common.*` is the shared source**: actions, field labels, shared vocabulary. Reuse it instead
  of duplicating; build Mantine `Select` option labels from `t()` at render so they translate.
- **Keep key parity vs EN for every shipped language** — a shipped bundle is all-or-nothing: every
  English key must exist in each `locales/<lang>/` (language-specific plural variants are
  expected), enforced by `locales/parity.test.ts` (auto-discovers language folders; also pins
  folders == `SUPPORTED_LANGUAGES`). Use i18next interpolation (`{{name}}`) and plural keys rather
  than string concatenation.
- **Adding a language** = a complete `locales/<lang>/` folder (parity-gated) + one code in `SUPPORTED_LANGUAGES` (`i18n.ts`) + one `NATIVE_LANGUAGE_NAMES` line; the server-side steps are in `.claude/docs/web-internals.md`.
- **Polish voice convention** (inherited from Lettuce): inclusive slash forms, active/direct
  voice, same tense and meaning as the English; spell out irregular feminines in full. No
  impersonal/passive dodges.
- The language switcher is the Language section of `components/UserMenu.tsx`; the user language is ONE synced property (the switcher also saves it server-side — it drives every email). Details: `.claude/docs/web-internals.md`.
- **Tests render English**: `src/test/setup.ts` imports `../i18n` and forces `en`, so text-based
  assertions match the EN resources.

## Theming

**The design language is theme-owned** (Lettuce's "clean enterprise SaaS" posture) —
`src/theme.ts` + `src/themeVariables.ts` + `src/theme.module.css` are the single source, and
`src/theme.test.ts` guards every contrast ratio:

- The brand is the 10-stop **`adder` olive tuple** (`#f7fbe6 … #586715 #465213`) with `primaryShade: { light: 8, dark: 9 }` —
  olive is a light hue, so the shades that carry white text sit at its deep end (white on shade 8 and on shade 9 clear 4.5:1,
  pinned in `theme.test.ts`) — and `autoContrast: true`.
  **Restrained** — in UI chrome and status badges (chart series colours are
  `utils/chartColors.ts`'s): olive marks only primary CTAs, the active nav item (a light tint + a
  3px accent bar) and focus — it is the interactive accent and nothing else (see the colour vocabulary). Everything else
  is neutral BY THEME DEFAULT — `Anchor` is text-coloured with a
  hover underline, `Chip` is `variant="light"`, `Badge` is `variant="light"` and gray, `ActionIcon` is
  `variant="subtle" color="gray"` (destructive ones pass `color="red"`), `Menu` is bottom-end in a
  portal, tables are `verticalSpacing="xs"` (≈40px rows). Pages never pass those props back. Teal
  is the SUCCESS colour, red the BLOCKING one, orange the warning one (soft findings, waived); the ADMIN role badge is
  `variant="outline" color="gray"`. **Never use stock green or lime for success** — they sit next to the olive brand. The light
  canvas carries a faint olive cast (`#f6f7f1`), pinned equal to `index.css`'s first-paint hex.
- **Colour tokens are AA-tested** (`themeVariables.ts`: `LIGHT_TOKENS`/`DARK_TOKENS`/`LIGHT_VARIANT_INKS`, wired in through `cssVariablesResolver` in `main.tsx`). Never hand a shade-suffixed colour (`"orange.8"`) to a light Badge/Alert — the tint comes from the hue, the ink from that map. Internals (`--adder-accent-ink`, the first-paint hexes): `.claude/docs/web-internals.md`.
- `theme.module.css` is scheme-aware **via `light-dark()`** throughout (tables card-framed on a
  quiet tinted canvas, white header/navbar surfaces, hairline borders) — new surface styling
  follows that pattern, not `[data-mantine-color-scheme]` selectors. Every `Table` inherits the
  card frame + hoverable rows + neutral compact header from the theme's `Table.extend` — don't add
  per-table frames.
- Soft diffuse `shadows` scale; tightened heading sizes (pages title themselves with `order={2}`).
- Inter is bundled (`@fontsource-variable/inter`, imported in `main.tsx`) so it loads same-origin
  and satisfies the CSP `font-src 'self'`; the system stack is the fallback.
- **Overlay accessibility rules (pinned by the e2e axe sweep over dialogs):** every `Modal` and
  `Drawer` passes `closeButtonProps={{ "aria-label": t("common.action.close") }}` — Mantine's
  close X has no default name — or hides the X with `withCloseButton={false}` when a footer
  button is the one deliberate exit (`OneTimePasswordModal`).
- **A scroller that can overflow is a focusable, labelled region.** A table wider than its box or
  capped by `maxHeight` goes in `components/ScrollRegion.tsx` (native scroller, `tabIndex={0}`,
  `role="region"`, the table's own i18n name as `aria-label`, the `tableScroll` focus ring) — never a
  bare `Table.ScrollContainer`; axe's `scrollable-region-focusable` fails any that overflows without it.
- The logo SVGs are computed geometry — change them in a vector editor, never by hand-editing the path data; restyle rule: keep aria-labels, roles and real semantic elements stable. Details: `.claude/docs/web-internals.md`.

## Changelog & app versioning

The user-facing changelog is a **build-time artifact** — no DB, no API, changes only with a
deploy. `src/changelog/entries.ts` holds `ChangelogEntry` rows (`version`, `date` `YYYY-MM-DD`,
`en`/`pl` **markdown** bodies), newest first; the app's only human-readable version is
`APP_VERSION` in **`src/changelog/version.ts`** — its own tiny module so the shell's eager imports
(VersionStamp, the what's-new dot) never pull the bilingual entries into the main bundle (the
entries ride the lazy Changelog chunk only). **A release = the new entry at the top of
entries.ts + the bump of that one literal** (the Gradle `1.0.0-SNAPSHOT` is unrelated);
`entries.test.ts` pins `CHANGELOG[0].version === APP_VERSION`, so forgetting either half fails the
suite. Release convention:

- Write both language bodies by hand (or LLM) — never derive them from commit messages. Bodies
  are *content*, so they live in the data file, not `locales/`; the PL body follows the Polish
  voice conventions (inclusive slash forms, active voice).
- Keep dates descending (same-day releases are fine — newest stays on top), versions unique, and
  both bodies non-empty — pinned by `src/changelog/entries.test.ts`.
- Complete publication with the annotated version tag and bilingual GitHub release described in
  [the application release process](../.claude/docs/app-releases.md). One version per milestone: the
  milestone's release commit bumps `APP_VERSION` and adds the one entry; work inside a milestone does not bump
  (`app-releases.md` has the rule and the patch exception); published tags never move.
- Keep phrases that tests assert on in plain text runs: markdown formatting splits text nodes,
  and testing-library's `getByText` matches direct text nodes only.

Rendering & UI wiring (details: `.claude/docs/web-internals.md`): `pages/Changelog.tsx` renders the entries through `MarkdownView` and calls `markChangelogSeen()`; bodies are authored EN+PL only, by policy. The "what's new" dot is driven by `hooks/useChangelogSeen.ts` via `useSyncExternalStore` — do **not** replace that with a plain localStorage read in state.

## Not yet ported from Toadie / Lettuce (port, don't reinvent)

When a feature needs a Lettuce or Covenant `web/` building block (`safeBackParam`, `MetaStrip`/`FormFooter`/`ListToolbar`, the data router's in-app `useBlocker` half of `useDiscardGuard` (only the `beforeunload` half is ported: `hooks/useDiscardGuard.ts` + `hooks/useBeforeUnloadGuard.ts`), the catalog facet counts, the CodeMirror editor; `FilterMultiSelect`/`accessiblePill` (0.5.0), `StatusPill`, the X-Expected-Revision transport, and — since 0.3.0 — `MarkdownView`'s message variant and the `EmojiPicker`/`EmojiButton`/`EmojiTextarea` trio are already ported), port the sibling's `web/` implementation and its `web/CLAUDE.md` section wholesale — the list is in `.claude/docs/web-internals.md`. `hooks/useRovingGrid.ts` is kept as prior art for later grid views' keyboard navigation (no consumer yet; its test keeps it live for knip).
