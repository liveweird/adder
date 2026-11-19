// The live channel's wire (`.claude/docs/live.md` "Frames and close codes"): the frame types, the URL and the lenient server-frame parser.
// The frame types ARE the generated `schema.ts` ones (`LiveClientFrame`, `LiveServerFrame`, `LiveEventKind`), pinned by `expectTypeOf` in
// `liveProtocol.test.ts`; the one deliberate widening is an event's `kind`, which is a plain string here because a newer server may add kinds.

import type { components } from "../api/schema";

/** The sixteen hint kinds; the wire name equals the audit name (`live.md` "Hints"). */
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
  "thread.status_changed",
  "thread.owner_changed",
  "message.posted",
  "message.edited",
  "message.deleted",
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

/** The socket's own path: the ticket travels in the first frame, never in the URL (API-AUTH-002). */
export function liveUrl(location: Pick<Location, "protocol" | "host">): string {
  const scheme = location.protocol === "https:" ? "wss:" : "ws:";
  return `${scheme}//${location.host}/api/v1/live`;
}

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
      return typeof frame.protocol === "number" && isId(maxSubscriptions) ? { type: "ready", protocol: frame.protocol, maxSubscriptions } : null;
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
