import type { MessageResponse } from "../api/messages";
import type { RoomResponse } from "../api/rooms";
import type { ThreadResponse } from "../api/threads";
import { canManageRoom } from "./roomAccess";

/**
 * Why the caller cannot post in a thread, or null when they can: UX only, the server guards (403 for a non-member, 409 `room-archived`
 * and `thread-closed`). The order is the order of what could change: an archived room first (nothing helps there), then a closed thread (a
 * steward reopens it), then membership (an ADMIN who is not a member joins through the Members page).
 */
export type MessageComposeBlock = "archived" | "closed" | "notMember";

export function messageComposeBlock(room: Pick<RoomResponse, "role" | "archived">, thread: Pick<ThreadResponse, "closed">): MessageComposeBlock | null {
  if (room.archived) return "archived";
  if (thread.closed) return "closed";
  return room.role === null ? "notMember" : null;
}

/**
 * What the caller may do to one message: edit it, and remove it — as its author ("delete") or as a room OWNER or an ADMIN ("moderate") — and
 * pin or unpin it (`pin`), react to it (`react`) and quote it in a reply (`quote`, 1.3); the last three absent read as false. The first two are the
 * message's own writes; `pin` is the room's; `react` and `quote` are speech.
 */
export type MessageActions = { edit: boolean; remove: "delete" | "moderate" | null; pin?: boolean; react?: boolean; quote?: boolean };

type OwnWrites = Pick<MessageActions, "edit" | "remove">;
const NO_ACTIONS: OwnWrites = { edit: false, remove: null };

type MessageActionsInput = {
  room: Pick<RoomResponse, "role" | "archived">;
  thread: Pick<ThreadResponse, "closed">;
  message: Pick<MessageResponse, "authorId" | "deleted">;
  userId: number | null;
  admin: boolean;
};

/** 0.6 D4: any member pins and unpins a live message, in a closed thread too (a pin is the room's); a tombstone and an archived room refuse. UX only. */
export function messageActions(args: MessageActionsInput): MessageActions {
  const { room, message } = args;
  const speech = canReact(args);
  return { ...ownWrites(args), pin: room.role !== null && !room.archived && !message.deleted, react: speech, quote: speech };
}

/**
 * 0.6 A24: a reaction is speech: members only, refused in a closed thread and an archived room, and a tombstone has none. UX only; the server
 * guards. A quote (1.3) is the same speech (a post), so `quote` is true exactly when `react` is.
 */
function canReact({ room, thread, message }: MessageActionsInput): boolean {
  return room.role !== null && !room.archived && !thread.closed && !message.deleted;
}

/**
 * D2: the author edits and deletes their own message while a member; a room OWNER or an ADMIN may delete, never edit, someone else's.
 * A tombstone, a closed thread and an archived room (all of which refuse every message write) offer nothing. UX only; the server guards.
 */
function ownWrites({ room, thread, message, userId, admin }: MessageActionsInput): OwnWrites {
  if (message.deleted || room.archived || thread.closed) return NO_ACTIONS;
  if (room.role !== null && message.authorId === userId) return { edit: true, remove: "delete" };
  return canManageRoom(room, admin) ? { edit: false, remove: "moderate" } : NO_ACTIONS;
}
