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

/** What the caller may do to one message: edit it, and remove it — as its author ("delete") or as a room OWNER or an ADMIN ("moderate"). */
export type MessageActions = { edit: boolean; remove: "delete" | "moderate" | null };

const NO_ACTIONS: MessageActions = { edit: false, remove: null };

/**
 * D2: the author edits and deletes their own message while a member; a room OWNER or an ADMIN may delete, never edit, someone else's.
 * A tombstone, a closed thread and an archived room (all of which refuse every message write) offer nothing. UX only; the server guards.
 */
export function messageActions({
  room,
  thread,
  message,
  userId,
  admin,
}: {
  room: Pick<RoomResponse, "role" | "archived">;
  thread: Pick<ThreadResponse, "closed">;
  message: Pick<MessageResponse, "authorId" | "deleted">;
  userId: number | null;
  admin: boolean;
}): MessageActions {
  if (message.deleted || room.archived || thread.closed) return NO_ACTIONS;
  if (room.role !== null && message.authorId === userId) return { edit: true, remove: "delete" };
  return canManageRoom(room, admin) ? { edit: false, remove: "moderate" } : NO_ACTIONS;
}
