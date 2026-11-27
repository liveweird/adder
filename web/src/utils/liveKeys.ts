// From a live frame to the TanStack Query keys it makes stale (`.claude/docs/live.md`, `web-internals.md` "The live channel"). A hint says THAT
// something changed, never what: the answer is a refetch through the ordinary REST reads, so authorization and the OpenAPI contract stay in
// one place. Keys are prefixes (R = room id, N = thread number).

import type { QueryClient, QueryKey } from "@tanstack/react-query";
import type { LiveClock } from "./liveClient";
import { homeKey, notificationsKey, roomKeys, roomOrganizationKey, searchAllKey } from "./roomKeys";
import { LIVE_EVENT_KINDS, type LiveEventKind, type LiveServerFrame } from "./liveProtocol";
import { deferReplyEcho } from "./liveReplyEcho";
import { lookingAt } from "./lookingAt";
import { clearRoomLinkPreviews } from "./linkPreviewCache";
import { quoteDependencyKeys } from "./quoteCacheDependencies";
import { deletedThreadDependencies, resetDeletedThreadContent, threadLifecycleKeys } from "./threadLifecycleCache";
import { clearMessageRevisions, clearRoomMessageRevisions } from "./messageRevisionCache";
import { commitTrackedMessageDelete } from "./messageDeleteCommit";
import { commitTrackedThreadDelete } from "./threadDeleteCommit";
import { allThreadReferenceKeys, resetPositiveThreadReferences, resetThreadReferences, settledThreadReferenceKeys, unresolvedThreadReferenceKeys } from "./threadReferenceCache";

const { rail: RAIL, list: ROOM_LIST, detail, access, joinRequests, members, workflow, workflowEdit, pins, threads, threadList, threadDetail, threadEvents, messages } = roomKeys;

const isKnownKind = (kind: string): kind is LiveEventKind => (LIVE_EVENT_KINDS as readonly string[]).includes(kind);

/** Protected content owned by one room, separate from caller-wide projections that also change when access is lost. */
const roomContentKeys = (r: number): QueryKey[] => [
  access(r),
  threads(r),
  members(r),
  joinRequests(r),
  workflow(r),
  workflowEdit(r),
  pins(r),
];

/** What a room's loss (or an unknown change to it) makes stale: everything the room's pages read, so each turns to its not-found or its new truth. */
const roomGone = (r: number): QueryKey[] => [detail(r), ...roomContentKeys(r), RAIL, ROOM_LIST, homeKey];

/** Discard replies issued under the old room permissions, including a first request that has no cache data yet. */
function resetRoomReads(queryClient: QueryClient, roomId: number, includeGlobalSearch: boolean) {
  clearRoomMessageRevisions(queryClient, roomId);
  void queryClient.resetQueries({
    queryKey: searchAllKey,
    predicate: ({ queryKey }) => queryKey[1] === "room" ? queryKey[2] === roomId : includeGlobalSearch,
  });
  void queryClient.resetQueries({
    queryKey: threads(roomId),
    predicate: ({ queryKey }) => queryKey[3] === "messages" && queryKey[5] === "context",
  });
}

type ThreadScopedKind = Extract<LiveEventKind, `thread.${string}` | `message.${string}`>;

/** The thread-scoped kinds need the thread number; a hint without one falls back to the room's whole thread tree. */
function threadKeys(kind: ThreadScopedKind, r: number, n: number | undefined): QueryKey[] {
  // A deleted message takes its notices with it, so even a hint that names no thread re-reads them.
  if (n === undefined) return kind === "message.deleted" ? [threads(r), RAIL, notificationsKey, homeKey] : [threads(r), RAIL, homeKey];
  switch (kind) {
    case "thread.created":
      return [threadList(r), workflow(r), workflowEdit(r), RAIL, homeKey];
    case "thread.updated":
    case "thread.owner_changed":
      return [threadDetail(r, n), threadEvents(r, n), threadList(r), RAIL, homeKey];
    case "thread.status_changed":
      return [threadDetail(r, n), threadEvents(r, n), threadList(r), RAIL, workflow(r), workflowEdit(r), homeKey];
    case "message.posted":
      return [messages(r, n), threadDetail(r, n), threadList(r), RAIL, homeKey];
    case "message.edited":
      // The conversation, the thread itself (an edit can add or drop a hashtag, which its header and side panel show), the list (the same, on
      // its rows, and a tag filter would show or hide the thread) and the room's pins (a pinned message's body shows in the panel; a
      // redacted one must not linger there).
      return [messages(r, n), roomKeys.messageRevisions(r, n), threadDetail(r, n), threadList(r), pins(r), homeKey];
    case "message.reaction_changed":
      // Only the conversation shows reactions: no count, no list order and no rail moves with one (a reaction is not activity). Spaced: `isSlowFrame`.
      return [messages(r, n)];
    default:
      // message.deleted: the tombstone, the count on the thread and its row, the room's pins (a deleted message loses its pin) and the notices
      // (the ones about it went with it, and the badge and the drawer must not keep a row that now opens nothing).
      return [messages(r, n), threadDetail(r, n), threadList(r), pins(r), notificationsKey, homeKey];
  }
}

function eventKeys(kind: LiveEventKind, r: number, n: number | undefined): QueryKey[] {
  switch (kind) {
    case "room.created":
      return [RAIL, ROOM_LIST, homeKey];
    case "room.updated":
    case "room.archived":
    case "room.unarchived":
      return [detail(r), RAIL, ROOM_LIST, homeKey];
    case "room.deleted":
      // The one hint besides `revoked` that names the room's search results: the room is gone, so are its hits (and the notices of the room).
      return [...roomGone(r), roomKeys.search(r), notificationsKey, roomOrganizationKey];
    case "room.pin_changed":
      return [pins(r)];
    case "room.workflow_updated":
      return [workflow(r), workflowEdit(r), threadList(r), roomKeys.threadDetails(r), roomKeys.threadHistories(r), homeKey];
    case "thread.merged":
      return threadLifecycleKeys(r, false);
    case "thread.deleted":
      return threadLifecycleKeys(r, true);
    case "room.member_added":
      return [members(r), joinRequests(r), detail(r), access(r), RAIL, ROOM_LIST, homeKey];
    case "room.member_role_changed":
    case "room.member_removed":
      return [members(r), detail(r), RAIL, ROOM_LIST, homeKey];
    case "notification.created":
      // Personal: it reaches the recipient's sockets whether or not they subscribed to the room, and names no page of the room.
      return [notificationsKey, homeKey];
    default:
      return threadKeys(kind, r, n);
  }
}

/** The keys one frame makes stale (the table in `web-internals.md`); `ready` and `pong` carry no room, `ready` re-reads the room lists a missed hint would leave stale. */
export function keysForFrame(frame: LiveServerFrame): QueryKey[] {
  switch (frame.type) {
    case "event":
      // A kind this client does not know: re-read the room, whatever it was.
      return isKnownKind(frame.kind) ? eventKeys(frame.kind, frame.roomId, frame.threadNumber) : roomGone(frame.roomId);
    case "revoked":
      // A lost room's hits leave an open search, the room's own and the one across rooms; search results are otherwise not live.
      // The notices of a lost room leave the badge and the drawer too (the server stops listing them).
      return [...roomGone(frame.roomId), roomKeys.search(frame.roomId), searchAllKey, notificationsKey, roomOrganizationKey];
    case "subscribed":
      // The subscription is live only from this ack: whatever changed between the page's own read and now is caught by one re-read.
      return frame.roomIds.length === 0
        ? []
        : [...frame.roomIds.flatMap((r) => [detail(r), threads(r), members(r), workflow(r), workflowEdit(r)]), homeKey];
    case "ready":
      // A notice may have been minted while the socket was down: the badge and the drawer are re-read with the lists.
      return [RAIL, ROOM_LIST, notificationsKey, homeKey];
    case "resync":
      return [roomKeys.all, notificationsKey, homeKey, roomOrganizationKey];
    case "pong":
      return [];
  }
}

/**
 * The pages that list or count (the rail, the rooms list, a room's thread list) change on almost every hint: they refresh at most this often.
 * So does a conversation that a "slow" frame names (`isSlowFrame`: a reaction hint), which a post still re-reads within the settle time.
 */
const SPACED_MIN_GAP_MS = 3_000;
/** A burst settles for this long before its keys are re-read... */
const SETTLE_MS = 300;
/** ...but never longer than this from its first hint. */
const MAX_WAIT_MS = 1_000;
/**
 * The frames that mean "you may have missed something" (`resync`, and the `ready` / `subscribed` that close the gap of a (re)connect) wait a
 * random 0 to this long first, so a whole room of tabs, all reconnecting after a server restart, does not refetch in the same instant.
 */
const SPREAD_MS = 2_000;
/** A QUIET tab (hidden for 5 minutes, 1.2 D9) re-reads the bell's count at most this often, whatever the hints say. */
const QUIET_MIN_GAP_MS = 60_000;

/**
 * The rail, the rooms list, a room's thread list and the caller's notices (the badge and the drawer): `["rooms","rail"]`, `["rooms","list"]`,
 * `["rooms","threads",R,"list"]`, `["notifications"]`.
 */
function isAggregate(key: QueryKey): boolean {
  if (key.length === 1) return key[0] === notificationsKey[0] || key[0] === homeKey[0];
  if (key.length === 2) return key[1] === "rail" || key[1] === "list";
  return key.length === 4 && key[1] === "threads" && key[3] === "list";
}

const idOf = (key: QueryKey) => JSON.stringify(key);

/**
 * The aggregates a re-read of this key ALSO refreshes (a room's whole thread tree includes its list; `["rooms"]` includes the rail and the rooms list).
 * `["search"]` is the other key of length one and covers none: a guard, so a search re-read is never stamped as a rail or rooms-list re-read.
 */
function coveredAggregates(key: QueryKey): string[] {
  if (key.length === 1) return key[0] === "rooms" ? [idOf(RAIL), idOf(ROOM_LIST)] : [];
  if (key.length === 3 && key[1] === "threads") return [idOf(threadList(key[2] as number))];
  return [];
}

/**
 * A frame whose keys keep the gap of the aggregates even though they are no aggregate: a reaction is cheap chatter (a thumbs-up storm under one
 * message), so it re-reads its conversation at most every three seconds. A `message.posted` for the same key is not slow and brings the re-read forward.
 */
function isSlowFrame(frame: LiveServerFrame): boolean {
  return frame.type === "event" && frame.kind === "message.reaction_changed";
}

interface Pending {
  key: QueryKey;
  /** An aggregate, or named by a slow frame: kept at least SPACED_MIN_GAP_MS from its last spaced re-read. */
  spaced: boolean;
  firstAt: number;
  dueAt: number;
}

export interface LiveInvalidator {
  /**
   * A quiet tab keeps ONLY the bell's keys (`["notifications"]`), spaced a minute apart: nothing else is on screen and the socket carries
   * personal frames only. Leaving quiet is followed by a `resync` from the client, the backfill of everything skipped meanwhile.
   */
  setQuiet(quiet: boolean): void;
  handle(frame: LiveServerFrame): void;
  /** Re-reads [keys] through the same coalescing and spacing as a hint's keys (an aggregate at most every three seconds). */
  request(keys: readonly QueryKey[]): void;
  dispose(): void;
}

/** The invalidator of each query client's live channel, so a re-read that is not a hint (the read marker's) can join its queue. */
const active = new WeakMap<QueryClient, LiveInvalidator>();

/**
 * Asks for a re-read of [key]: through the tab's live invalidator when there is one, so a read-marker-driven re-read and the hints'
 * coalesce into at most one flush per spacing window (the notice list is bucketed per user, `security.md`); straight away when no live
 * channel exists (a page rendered without the shell, a test).
 */
export function requestRefresh(queryClient: QueryClient, key: QueryKey): void {
  const invalidator = active.get(queryClient);
  if (invalidator) invalidator.request([key]);
  else void queryClient.invalidateQueries({ queryKey: key });
}

function queueCorrelatedEditQuotes(
  queryClient: QueryClient,
  frame: LiveServerFrame,
  queue: (key: QueryKey, spread: boolean, slow: boolean) => void,
): void {
  if (frame.type !== "event" || frame.kind !== "message.edited") return;
  quoteDependencyKeys(queryClient, frame)
    .filter((key) => key[0] === "rooms" && key[2] !== frame.roomId)
    .forEach((key) => queue(key, false, false));
}

function resetSensitiveSourceReads(
  queryClient: QueryClient,
  frame: LiveServerFrame,
  roomLost: boolean,
  queue: (key: QueryKey, spread: boolean, slow: boolean) => void,
  quiet: boolean,
): void {
  if (frame.type === "event" && frame.kind === "thread.deleted") {
    void resetDeletedThreadContent(queryClient, frame.roomId, deletedThreadDependencies(queryClient, frame), !quiet);
    return;
  }
  const quoteDependencies = quoteDependencyKeys(queryClient, frame);
  const quoteSourceChanged = frame.type === "event" &&
    ["message.edited", "message.deleted", "room.deleted"].includes(frame.kind);
  const quoteRoomId = frame.type === "event" || frame.type === "revoked" ? frame.roomId : null;
  const quoteSourcePrefix = quoteRoomId === null ? null : [...threads(quoteRoomId), "quote-source"] as const;
  const hasQuoteSourceReads = quoteSourcePrefix !== null && queryClient.getQueryCache().findAll({ queryKey: quoteSourcePrefix }).length > 0;
  if (quoteSourcePrefix !== null && (roomLost || (frame.type === "event" && frame.kind === "message.deleted"))) {
    // Security/lifecycle losses clear excerpts immediately and cancel replies issued under the old source permission.
    if (hasQuoteSourceReads) void queryClient.resetQueries({ queryKey: quoteSourcePrefix });
    for (const key of quoteDependencies) void queryClient.resetQueries({ queryKey: key, exact: true });
  } else if (quoteSourcePrefix !== null && quoteSourceChanged) {
    if (hasQuoteSourceReads) void queryClient.invalidateQueries({ queryKey: quoteSourcePrefix });
    for (const key of quoteDependencies) queue(key, false, false);
  }
}

function newlyReadableReferenceKeys(queryClient: QueryClient, frame: LiveServerFrame): QueryKey[] {
  if (frame.type !== "event") return [];
  if (frame.kind === "thread.created") return unresolvedThreadReferenceKeys(queryClient, frame.roomId, frame.threadNumber);
  if (frame.kind === "room.member_added") return unresolvedThreadReferenceKeys(queryClient, frame.roomId);
  return [];
}

/**
 * Applies the cache side of losing access to one room.
 *
 * A live `revoked` frame keeps the invalidator's existing cadence: sensitive reads are reset immediately while the ordinary room and aggregate
 * keys are queued by `handle`. A successful self-leave has no guaranteed socket, so it removes every source-room read without refetching the
 * now-forbidden endpoints and immediately resets the surviving projections that may still expose that room.
 */
export function applyRoomRevocation(queryClient: QueryClient, roomId: number, sourceRoom: "reset" | "remove" = "reset"): void {
  const frame = { type: "revoked", roomId } as const;
  void resetThreadReferences(queryClient, roomId);
  if (sourceRoom === "reset") {
    resetSensitiveSourceReads(queryClient, frame, true, () => undefined, false);
    resetRoomReads(queryClient, roomId, true);
    clearRoomLinkPreviews(queryClient, roomId);
    return;
  }

  // Discover cross-room quote dependencies before removing the source room's thread tree. Resetting them cancels any old render and refetches
  // only an active destination; the late source response cannot repopulate its destroyed query.
  const quoteDependencies = quoteDependencyKeys(queryClient, frame);
  const detailKey = detail(roomId);
  const detailIsActive = queryClient.getQueryCache().findAll({ queryKey: detailKey, exact: true, type: "active" }).length > 0;
  clearRoomMessageRevisions(queryClient, roomId);
  clearRoomLinkPreviews(queryClient, roomId);
  for (const key of roomContentKeys(roomId)) queryClient.removeQueries({ queryKey: key });
  // A replacement route already showing this room gets the one authoritative 404/preview recheck; an inactive detail is dropped without I/O.
  if (detailIsActive) void queryClient.resetQueries({ queryKey: detailKey, exact: true });
  else queryClient.removeQueries({ queryKey: detailKey, exact: true });
  queryClient.removeQueries({ queryKey: roomKeys.search(roomId) });

  for (const key of quoteDependencies) void queryClient.resetQueries({ queryKey: key, exact: true });
  void queryClient.resetQueries({
    queryKey: searchAllKey,
    predicate: ({ queryKey }) => queryKey[1] !== "room",
  });
  for (const key of [RAIL, ROOM_LIST, homeKey, notificationsKey, roomOrganizationKey]) {
    void queryClient.resetQueries({ queryKey: key });
  }
}

/**
 * Turns frames into invalidations, coalesced (a burst of hints is one re-read per key) and spaced (a list at most every three seconds).
 * It NEVER cancels: while a key is being fetched it waits and tries again, because cancelling a fetch would drop a "Load older messages" click
 * and leave the scroll anchor armed. `invalidateQueries` refetches the ACTIVE queries only; the rest are just marked stale.
 */
export function createLiveInvalidator(queryClient: QueryClient, clock: LiveClock): LiveInvalidator {
  const pending = new Map<string, Pending>();
  const lastFlushedAt = new Map<string, number>();
  let timer: unknown = null;
  let timerDueAt = 0;
  let quiet = false;
  let connected = false;
  const isBellKey = (key: QueryKey) => key[0] === notificationsKey[0];

  function arm(): void {
    if (pending.size === 0) return;
    const dueAt = Math.min(...[...pending.values()].map((entry) => entry.dueAt));
    if (timer !== null && timerDueAt <= dueAt) return;
    if (timer !== null) clock.clearTimeout(timer);
    timerDueAt = dueAt;
    timer = clock.setTimeout(flush, Math.max(0, dueAt - clock.now()));
  }

  function flush(): void {
    timer = null;
    const now = clock.now();
    for (const [id, entry] of [...pending]) {
      if (entry.dueAt > now) continue;
      const filters = { queryKey: entry.key };
      if (queryClient.isFetching(filters) > 0) {
        entry.dueAt = now + SETTLE_MS;
        continue;
      }
      pending.delete(id);
      // EVERY flush is stamped, not only a spaced entry's: a conversation re-read that a post drove (an entry a reaction joined but did not own)
      // must still hold a reaction right after it to the 3 s gap. A wider re-read stamps the aggregates it covers (the 3 s guarantee holds).
      lastFlushedAt.set(id, now);
      for (const covered of coveredAggregates(entry.key)) lastFlushedAt.set(covered, now);
      void queryClient.invalidateQueries(filters);
    }
    arm();
  }

  function queue(key: QueryKey, spread: boolean, slow: boolean): void {
    const id = idOf(key);
    const now = clock.now();
    const spaced = slow || isAggregate(key);
    const existing = pending.get(id);
    if (spaced || spread) {
      // A fixed place (the spread, or the settle) kept to the gap since the last re-read; a later hint can only bring it forward.
      const wait = spread ? clock.random() * SPREAD_MS : SETTLE_MS;
      const gap = quiet ? QUIET_MIN_GAP_MS : SPACED_MIN_GAP_MS;
      const dueAt = Math.max(now + wait, spaced ? (lastFlushedAt.get(id) ?? -Infinity) + gap : -Infinity);
      if (!existing || dueAt < existing.dueAt) pending.set(id, { key, spaced, firstAt: existing?.firstAt ?? now, dueAt });
    } else if (existing?.spaced) {
      // A hint that is no reaction (a post) for a conversation waiting out the gap: it settles on its own clock, and only ever brings the re-read forward.
      // There is no debounce here by design (`firstAt` is read only by the plain-key branch below): the due time can only move earlier, never later.
      existing.dueAt = Math.min(existing.dueAt, now + SETTLE_MS);
    } else if (existing) {
      existing.dueAt = Math.min(now + SETTLE_MS, existing.firstAt + MAX_WAIT_MS);
    } else {
      pending.set(id, { key, spaced, firstAt: now, dueAt: now + SETTLE_MS });
    }
  }

  const invalidator: LiveInvalidator = {
    setQuiet(next) {
      quiet = next;
    },
    handle(frame) {
      if (frame.type === "ready" || frame.type === "resync") {
        const includePending = frame.type === "resync" || connected;
        const keys = includePending ? allThreadReferenceKeys(queryClient) : settledThreadReferenceKeys(queryClient);
        resetPositiveThreadReferences(queryClient);
        if (!quiet) keys.forEach((key) => queue(key, true, false));
        if (frame.type === "ready") connected = true;
      }
      if (frame.type === "subscribed") frame.roomIds.forEach((roomId) => {
        const keys = settledThreadReferenceKeys(queryClient, roomId);
        resetPositiveThreadReferences(queryClient, roomId);
        if (!quiet) keys.forEach((key) => queue(key, true, false));
      });
      if (frame.type === "event" && frame.kind === "room.deleted") {
        void resetThreadReferences(queryClient, frame.roomId);
      }
      if (!quiet) newlyReadableReferenceKeys(queryClient, frame).forEach((key) => queue(key, false, false));
      // Role history is permission-sensitive: clear it before a writer's exact echo can be held until the HTTP response settles.
      if (frame.type === "event" && frame.kind === "room.member_role_changed") {
        clearRoomMessageRevisions(queryClient, frame.roomId);
      }
      if (deferReplyEcho(queryClient, frame, () => active.get(queryClient)?.handle(frame))) {
        // The edit writer's own refresh covers every source-room thread read and the source room's pins. Quotes rendered in another room are
        // independent reads, so keep their ordinary invalidation even while the exact correlated event waits for the write response.
        queueCorrelatedEditQuotes(queryClient, frame, queue);
        arm();
        return;
      }
      if (commitTrackedMessageDelete(queryClient, frame, () => active.get(queryClient)?.handle(frame))) return;
      if (commitTrackedThreadDelete(queryClient, frame, () => active.get(queryClient)?.handle(frame))) return;
      const roomLost = frame.type === "revoked" || (frame.type === "event" && frame.kind === "room.deleted");
      const threadDeleted = frame.type === "event" && frame.kind === "thread.deleted";
      if (frame.type === "revoked") applyRoomRevocation(queryClient, frame.roomId);
      else resetSensitiveSourceReads(queryClient, frame, roomLost, queue, quiet);
      if (roomLost && frame.type !== "revoked") {
        resetRoomReads(queryClient, frame.roomId, false);
        clearRoomLinkPreviews(queryClient, frame.roomId);
      }
      if (frame.type === "event" && frame.kind === "message.deleted" && frame.threadNumber !== undefined) {
        clearMessageRevisions(queryClient, frame.roomId, frame.threadNumber, frame.messageId);
      }
      // A notice about the thread the reader is looking at (open, visible, at the end) is acknowledged by the pane through the read marker; the
      // bell is not re-read, so the badge never rises and falls again (`utils/lookingAt.ts`).
      if (frame.type === "event" && frame.kind === "notification.created") {
        const looking = lookingAt(frame.roomId, frame.threadNumber);
        if (looking !== null && looking.covers(frame.messageId)) {
          looking.acknowledge(frame.messageId);
          return;
        }
      }
      // A notice is spread like a reconnect's catch-up: one wide event reaches hundreds of tabs at the same instant, and their re-reads of the
      // bell would arrive as one wave (`security.md` "notification-read").
      const spread =
        frame.type === "resync" ||
        frame.type === "ready" ||
        frame.type === "subscribed" ||
        (frame.type === "event" && frame.kind === "notification.created");
      const slow = isSlowFrame(frame);
      for (const key of keysForFrame(frame)) {
        if (roomLost && key[0] === searchAllKey[0]) continue;
        if (threadDeleted && key[0] === "rooms" && key[1] === "pins" && key[2] === frame.roomId) continue;
        if (!quiet || isBellKey(key)) queue(key, spread, slow);
      }
      arm();
    },
    request(keys) {
      for (const key of keys) if (!quiet || isBellKey(key)) queue(key, false, false);
      arm();
    },
    dispose() {
      if (timer !== null) clock.clearTimeout(timer);
      timer = null;
      pending.clear();
      lastFlushedAt.clear();
      if (active.get(queryClient) === invalidator) active.delete(queryClient);
    },
  };
  active.set(queryClient, invalidator);
  return invalidator;
}
