import { ApiError } from "../api/http";
import type { RoomResponse } from "../api/rooms";

/**
 * Whether the session manages the room (its details, lifecycle and, later, members and workflow): the room's OWNER or an ADMIN.
 * UX only — the server's guards decide; a plain member just sees the controls disabled or absent.
 */
export function canManageRoom(room: Pick<RoomResponse, "role">, admin: boolean): boolean {
  return admin || room.role === "OWNER";
}

/**
 * Whether a failed member write says the caller's standing in the room is not what the page assumed: a 403 (demoted), a 404 (removed,
 * or the room is gone) or a 409 (the roster moved). The page then re-reads the room, so its role and controls follow the truth.
 */
export function isAccessRefusal(err: unknown): boolean {
  return err instanceof ApiError && (err.status === 403 || err.status === 404 || err.status === 409);
}
