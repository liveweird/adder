// From a live frame to the TanStack Query keys it makes stale (`.claude/docs/live.md`, `web-internals.md` "The live channel"). A hint says THAT
// something changed, never what: the answer is a refetch through the ordinary REST reads, so authorization and the OpenAPI contract stay in
// one place. Keys are prefixes (R = room id, N = thread number).

import type { QueryClient, QueryKey } from "@tanstack/react-query";
import type { LiveClock } from "./liveClient";
import { notificationsKey, roomKeys, searchAllKey } from "./roomKeys";
import { LIVE_EVENT_KINDS, type LiveEventKind, type LiveServerFrame } from "./liveProtocol";

const { rail: RAIL, list: ROOM_LIST, detail, members, workflow, workflowEdit, pins, threads, threadList, threadDetail, threadEvents, messages } = roomKeys;

const isKnownKind = (kind: string): kind is LiveEventKind => (LIVE_EVENT_KINDS as readonly string[]).includes(kind);

/** What a room's loss (or an unknown change to it) makes stale: everything the room's pages read, so each turns to its not-found or its new truth. */
const roomGone = (r: number): QueryKey[] => [detail(r), threads(r), members(r), workflow(r), workflowEdit(r), pins(r), RAIL, ROOM_LIST];

type ThreadScopedKind = Extract<LiveEventKind, `thread.${string}` | `message.${string}`>;

/** The thread-scoped kinds need the thread number; a hint without one falls back to the room's whole thread tree. */
function threadKeys(kind: ThreadScopedKind, r: number, n: number | undefined): QueryKey[] {
  if (n === undefined) return [threads(r), RAIL];
  switch (kind) {
    case "thread.created":
      return [threadList(r), workflow(r), workflowEdit(r), RAIL];
    case "thread.updated":
    case "thread.owner_changed":
      return [threadDetail(r, n), threadEvents(r, n), threadList(r), RAIL];
    case "thread.status_changed":
      return [threadDetail(r, n), threadEvents(r, n), threadList(r), RAIL, workflow(r), workflowEdit(r)];
    case "message.posted":
      return [messages(r, n), threadDetail(r, n), threadList(r), RAIL];
    case "message.edited":
      // The conversation, the list (an edit can add or drop a hashtag, which a tag filter would show or hide) and the room's pins (a pinned
      // message's body shows in the panel; a redacted one must not linger there).
      return [messages(r, n), threadList(r), pins(r)];
    case "message.reaction_changed":
      // Only the conversation shows reactions: no count, no list order and no rail moves with one (a reaction is not activity). Spaced: `isSlowFrame`.
      return [messages(r, n)];
    default:
      // message.deleted: the tombstone, the count on the thread and its row, and the room's pins (a deleted message loses its pin).
      return [messages(r, n), threadDetail(r, n), threadList(r), pins(r)];
  }
}

function eventKeys(kind: LiveEventKind, r: number, n: number | undefined): QueryKey[] {
  switch (kind) {
    case "room.created":
      return [RAIL, ROOM_LIST];
    case "room.updated":
    case "room.archived":
    case "room.unarchived":
      return [detail(r), RAIL, ROOM_LIST];
    case "room.deleted":
      // The one hint besides `revoked` that names the room's search results: the room is gone, so are its hits.
      return [...roomGone(r), roomKeys.search(r)];
    case "room.pin_changed":
      return [pins(r)];
    case "room.workflow_updated":
      return [workflow(r), workflowEdit(r), threadList(r), roomKeys.threadDetails(r), roomKeys.threadHistories(r)];
    case "room.member_added":
    case "room.member_role_changed":
    case "room.member_removed":
      return [members(r), detail(r), RAIL, ROOM_LIST];
    case "notification.created":
      // Personal: it reaches the recipient's sockets whether or not they subscribed to the room, and names no page of the room.
      return [notificationsKey];
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
      return [...roomGone(frame.roomId), roomKeys.search(frame.roomId), searchAllKey];
    case "subscribed":
      // The subscription is live only from this ack: whatever changed between the page's own read and now is caught by one re-read.
      return frame.roomIds.flatMap((r) => [detail(r), threads(r), members(r), workflow(r), workflowEdit(r)]);
    case "ready":
      return [RAIL, ROOM_LIST];
    case "resync":
      return [roomKeys.all];
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

/** The rail, the rooms list and a room's thread list: `["rooms","rail"]`, `["rooms","list"]`, `["rooms","threads",R,"list"]`. */
function isAggregate(key: QueryKey): boolean {
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
  handle(frame: LiveServerFrame): void;
  dispose(): void;
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
      if (queryClient.isFetching({ queryKey: entry.key }) > 0) {
        entry.dueAt = now + SETTLE_MS;
        continue;
      }
      pending.delete(id);
      // EVERY flush is stamped, not only a spaced entry's: a conversation re-read that a post drove (an entry a reaction joined but did not own)
      // must still hold a reaction right after it to the 3 s gap. A wider re-read stamps the aggregates it covers (the 3 s guarantee holds).
      lastFlushedAt.set(id, now);
      for (const covered of coveredAggregates(entry.key)) lastFlushedAt.set(covered, now);
      void queryClient.invalidateQueries({ queryKey: entry.key });
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
      const dueAt = Math.max(now + wait, spaced ? (lastFlushedAt.get(id) ?? -Infinity) + SPACED_MIN_GAP_MS : -Infinity);
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

  return {
    handle(frame) {
      const spread = frame.type === "resync" || frame.type === "ready" || frame.type === "subscribed";
      const slow = isSlowFrame(frame);
      for (const key of keysForFrame(frame)) queue(key, spread, slow);
      arm();
    },
    dispose() {
      if (timer !== null) clock.clearTimeout(timer);
      timer = null;
      pending.clear();
      lastFlushedAt.clear();
    },
  };
}
