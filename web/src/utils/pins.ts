import type { TFunction } from "i18next";
import type { QueryClient } from "@tanstack/react-query";
import type { RoomResponse } from "../api/rooms";
import { PROBLEM_TYPES, isProblem } from "./problems";
import { refreshQueriesAfterMutation } from "./queryRefresh";
import { roomKeys } from "./roomKeys";
import { saveErrorMessage } from "./saveError";

/** 0.6 D4: any member of a live room pins and unpins; an ADMIN who is not a member reads the pins but cannot change them. UX only, the server guards. */
export function canPin(room: Pick<RoomResponse, "role" | "archived">): boolean {
  return room.role !== null && !room.archived;
}

/**
 * The failure vocabulary of a pin write. The two 409s are told apart by their problem type: the room's 51st pin (`pin-limit`: unpin one first)
 * and an archived room; any other conflict is the generic one. A refusal (403, 404, 409) makes the caller re-read the room (`isAccessRefusal`).
 */
export function pinErrorMessage(err: unknown, t: TFunction): string {
  const conflict = isProblem(err, PROBLEM_TYPES.pinLimit)
    ? "threads.pin.error.limit"
    : isProblem(err, PROBLEM_TYPES.roomArchived)
      ? "threads.pin.error.archived"
      : "threads.pin.error.conflict";
  return saveErrorMessage(err, t, {
    forbidden: "threads.pin.error.forbidden",
    notFound: "threads.pin.error.gone",
    conflict,
    tooManyRequests: "threads.pin.error.tooManyRequests",
    failedStatus: "common.error.actionFailedStatus",
    failed: "common.error.actionFailed",
  });
}

/** After the caller's own pin write: only the room's pins are re-read (a pin changes no thread, message or count). */
export function refreshAfterPinWrite(queryClient: QueryClient, roomId: number): Promise<void> {
  return refreshQueriesAfterMutation(queryClient, roomKeys.pins(roomId));
}
