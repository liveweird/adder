import type { RoomResponse, RoomWorkflow } from "../api/rooms";
import type { ThreadResponse } from "../api/threads";
import { canManageRoom } from "./roomAccess";

/** Why the caller cannot start a thread here, or null when they can: UX only, the server guards (403 for a non-member, 409 `room-archived`). */
export type ThreadStartBlock = "archived" | "notMember";

/**
 * Speech is for members only: an ADMIN who is not a member reads the room but joins through the Members page before posting (`role`
 * is null for them), and an archived room is read-only. The archived notice wins, since joining would not help.
 */
export function threadStartBlock(room: Pick<RoomResponse, "role" | "archived">): ThreadStartBlock | null {
  if (room.archived) return "archived";
  return room.role === null ? "notMember" : null;
}

/** A status of the room's workflow, as `GET /rooms/{id}/workflow` lists it. */
type WorkflowStatus = RoomWorkflow["statuses"][number];

/**
 * D1: closing, reopening and CLOSED-to-CLOSED moves, and passing the thread on, are for a STEWARD: the thread's owner while a member, the room's
 * OWNER or an ADMIN. UX only — the server decides (`ThreadStatusRules.kt`, `requireThreadSteward`).
 */
export function isThreadSteward({
  room,
  thread,
  userId,
  admin,
}: {
  room: Pick<RoomResponse, "role">;
  thread: Pick<ThreadResponse, "ownerId">;
  userId: number | null;
  admin: boolean;
}): boolean {
  return canManageRoom(room, admin) || (room.role !== null && userId !== null && thread.ownerId === userId);
}

/** Why a status cannot be picked: the room is archived (every content write is refused), or the move needs a steward. */
export type StatusMoveBlock = "archived" | "needsSteward";

/**
 * Whether the caller may move the thread to `target`. A move between two OPEN statuses is open to every reader; one that enters or leaves a
 * CLOSED status (closing, reopening, CLOSED to CLOSED) needs a steward. The current status is never blocked (picking it is a no-op).
 */
export function statusMoveBlock(
  room: Pick<RoomResponse, "archived">,
  thread: Pick<ThreadResponse, "closed" | "status">,
  steward: boolean,
  target: Pick<WorkflowStatus, "key" | "category">,
): StatusMoveBlock | null {
  if (target.key === thread.status) return null;
  if (room.archived) return "archived";
  return !steward && (thread.closed || target.category === "CLOSED") ? "needsSteward" : null;
}

/** Why the details and the owner cannot be changed now (both refuse an archived room and a closed thread: 409), or null. The archived notice wins. */
export type ThreadEditBlock = "archived" | "closed";

export function threadEditBlock(room: Pick<RoomResponse, "archived">, thread: Pick<ThreadResponse, "closed">): ThreadEditBlock | null {
  if (room.archived) return "archived";
  return thread.closed ? "closed" : null;
}
