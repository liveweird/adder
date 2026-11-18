import type { RoomResponse } from "../api/rooms";

/**
 * Whether the session manages the room (its details, lifecycle and, later, members and workflow): the room's OWNER or an ADMIN.
 * UX only — the server's guards decide; a plain member just sees the controls disabled or absent.
 */
export function canManageRoom(room: Pick<RoomResponse, "role">, admin: boolean): boolean {
  return admin || room.role === "OWNER";
}
