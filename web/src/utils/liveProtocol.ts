// The live channel's wire (`.claude/docs/live.md` "Frames and close codes"): the frame types, the URL and the lenient server-frame parser.
// The frame types ARE the generated `schema.ts` ones (`LiveClientFrame`, `LiveServerFrame`, `LiveEventKind`), pinned by `expectTypeOf` in
// `liveProtocol.test.ts`; the one deliberate widening is an event's `kind`, which is a plain string here because a newer server may add kinds.

import type { components } from "../api/schema";

/** The hint kinds; the wire name equals the audit name (`live.md` "Hints"). */
export const LIVE_EVENT_KINDS = [
  "room.created",
  "room.updated",
  "room.archived",
  "room.unarchived",
  "room.deleted",
  "room.member_added",
  "room.member_role_changed",
  "room.member_removed",
  "room.workflow_updated",
  "thread.created",
  "thread.updated",
  "thread.deleted",
  "thread.merged",
  "thread.status_changed",
  "thread.owner_changed",
  "message.posted",
  "message.edited",
  "message.deleted",
  "message.reaction_changed",
  "room.pin_changed",
  "notification.created",
] as const;

export type LiveEventKind = (typeof LIVE_EVENT_KINDS)[number];

/** What the client sends: the ticket first (within 5 s, exactly once), then the subscription diffs and the application ping. */
export type LiveClientFrame = components["schemas"]["LiveClientFrame"];

type GeneratedEventFrame = components["schemas"]["LiveEventFrame"];

/**
 * What the server sends. An `event`'s `kind` is a plain string on purpose: the client keeps working when a newer server adds kinds
 * (`keysForFrame` treats an unknown kind with a room as a reason to re-read that room).
 */
export type LiveServerFrame =
  | Exclude<components["schemas"]["LiveServerFrame"], GeneratedEventFrame>
  | (Omit<GeneratedEventFrame, "kind"> & { kind: string });

/** Subscriptions per socket when a `ready` frame does not say. */
const DEFAULT_MAX_SUBSCRIPTIONS = 128;

/** A linear scan, not `replace(/\/+$/, "")`: that regex backtracks super-linearly (sonarjs) on a long run of slashes. */
function trimTrailingSlashes(value: string): string {
  let end = value.length;
  while (end > 0 && value[end - 1] === "/") end--;
  return value.slice(0, end);
}

/**
 * The socket's URL: the same base as the REST calls (`VITE_API_BASE`, `API_BASE` in `api/http.ts`), so a build that talks to an API on another
 * origin opens its socket there too. An empty base is the page's own host (`ws:` on http, `wss:` on https); a relative base (`/adder`) is a path
 * prefix on that host (resolved like REST's `fetch`, so one without a leading slash is relative to the page); an absolute `http(s)://host[:port][/prefix]` base maps to `ws(s)://` on THAT origin (the server then needs the page's
 * origin in `http.corsHosts`, as for REST). The ticket is never in the URL, API-AUTH-002.
 */
export function liveUrl(location: Pick<Location, "protocol" | "host"> & { pathname?: string }, apiBase = ""): string {
  const path = "/api/v1/live";
  const given = apiBase.trim();
  if (/^https?:\/\//i.test(given)) {
    try {
      const url = new URL(given);
      return `${url.protocol === "https:" ? "wss:" : "ws:"}//${url.host}${trimTrailingSlashes(url.pathname)}${path}`;
    } catch {
      // an unusable base: REST fails the same way; fall through to the page's own host
    }
  }
  // Resolved the way REST's `fetch` resolves it: "/adder" from the origin's root, "adder" relative to the page's own path.
  const prefix = /^https?:\/\//i.test(given) ? "" : trimTrailingSlashes(given);
  const resolved = new URL(`${prefix}${path}`, `${location.protocol}//${location.host}${location.pathname ?? "/"}`);
  return `${resolved.protocol === "https:" ? "wss:" : "ws:"}//${resolved.host}${resolved.pathname}`;
}

const MUTATION_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

const isId = (value: unknown): value is number => typeof value === "number" && Number.isSafeInteger(value) && value > 0;
const isIdList = (value: unknown): value is number[] => Array.isArray(value) && value.every(isId);

/** A list the frame may omit (an older server): absent is empty, present must be ids. */
function idsOrEmpty(value: unknown): number[] | null {
  if (value === undefined) return [];
  return isIdList(value) ? value : null;
}

function optionalId(value: unknown): number | undefined | null {
  if (value === undefined || value === null) return undefined;
  return isId(value) ? value : null;
}

function parseEvent(frame: Record<string, unknown>): LiveServerFrame | null {
  const threadNumber = optionalId(frame.threadNumber);
  const messageId = optionalId(frame.messageId);
  if (typeof frame.kind !== "string" || !isId(frame.roomId) || threadNumber === null || messageId === null) return null;
  return {
    type: "event",
    kind: frame.kind,
    roomId: frame.roomId,
    ...(threadNumber === undefined ? {} : { threadNumber }),
    ...(messageId === undefined ? {} : { messageId }),
    ...(["message.posted", "message.edited", "message.reaction_changed", "thread.updated", "thread.status_changed", "thread.owner_changed", "thread.merged"].includes(frame.kind) &&
      typeof frame.mutationId === "string" && MUTATION_ID.test(frame.mutationId)
      ? { mutationId: frame.mutationId } : {}),
  };
}

function parseSubscribed(frame: Record<string, unknown>): LiveServerFrame | null {
  const roomIds = idsOrEmpty(frame.roomIds);
  const refused = idsOrEmpty(frame.refused);
  const capped = idsOrEmpty(frame.capped);
  return roomIds && refused && capped ? { type: "subscribed", roomIds, refused, capped } : null;
}

/**
 * One text frame to a server frame, or null when it is not one the client understands: not text, not JSON, not an object, an unknown `type`
 * (the client ignores those) or a known type with malformed members. Unknown members are dropped.
 */
export function parseServerFrame(raw: unknown): LiveServerFrame | null {
  if (typeof raw !== "string") return null;
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof value !== "object" || value === null || Array.isArray(value)) return null;
  const frame = value as Record<string, unknown>;
  switch (frame.type) {
    case "ready": {
      // An older server may not announce the cap; the protocol's default is 128.
      const maxSubscriptions = frame.maxSubscriptions === undefined ? DEFAULT_MAX_SUBSCRIPTIONS : frame.maxSubscriptions;
      return typeof frame.protocol === "number" && isId(maxSubscriptions)
        ? {
            type: "ready",
            protocol: frame.protocol,
            maxSubscriptions,
            ...(frame.presenceSupported === true ? { presenceSupported: true } : {}),
          }
        : null;
    }
    case "subscribed":
      return parseSubscribed(frame);
    case "event":
      return parseEvent(frame);
    case "revoked":
      return isId(frame.roomId) ? { type: "revoked", roomId: frame.roomId } : null;
    case "resync":
      return { type: "resync" };
    case "pong":
      return { type: "pong" };
    default:
      return null;
  }
}
