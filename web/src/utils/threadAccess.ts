import type { RoomResponse } from "../api/rooms";

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
