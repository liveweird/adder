import type { TFunction } from "i18next";
import type { QueryClient } from "@tanstack/react-query";
import { PIN_LIMIT } from "../api/pins";
import type { RoomResponse } from "../api/rooms";
import { PROBLEM_TYPES, isProblem } from "./problems";
import { refreshQueriesAfterMutation } from "./queryRefresh";
import { roomKeys } from "./roomKeys";
import { isAccessRefusal } from "./roomAccess";
import { saveErrorMessage } from "./saveError";

/** 0.6 D4: any member of a live room pins and unpins; an ADMIN who is not a member reads the pins but cannot change them. UX only, the server guards. */
export function canPin(room: Pick<RoomResponse, "role" | "archived">): boolean {
  return room.role !== null && !room.archived;
}

/**
 * The failure vocabulary of a pin write. The two 409s are told apart by their problem type: the room's 51st pin (`pin-limit`: unpin one first)
 * and an archived room; any other conflict is the generic one. The limit in the words is `PIN_LIMIT`. A `room-busy` 429 has its own words
 * (`saveErrorMessage`). `refreshAfterPinFailure` says what the caller re-reads.
 */
export function pinErrorMessage(err: unknown, t: TFunction): string {
  if (isProblem(err, PROBLEM_TYPES.pinLimit)) return t("threads.pin.error.limit", { count: PIN_LIMIT });
  return saveErrorMessage(err, t, {
    forbidden: "threads.pin.error.forbidden",
    notFound: "threads.pin.error.gone",
    conflict: isProblem(err, PROBLEM_TYPES.roomArchived) ? "threads.pin.error.archived" : "threads.pin.error.conflict",
    tooManyRequests: "threads.pin.error.tooManyRequests",
    failedStatus: "common.error.actionFailedStatus",
    failed: "common.error.actionFailed",
  });
}

/** After the caller's own pin write: only the room's pins are re-read (a pin changes no thread, message or count). */
export function refreshAfterPinWrite(queryClient: QueryClient, roomId: number): Promise<void> {
  return refreshQueriesAfterMutation(queryClient, roomKeys.pins(roomId));
}

/**
 * After a FAILED pin write. `pin-limit` is a 409 that says nothing about the caller's standing, so only the pins are re-read (the set that
 * is full may have moved); any other refusal (403, 404, 409: demoted, removed, the room gone or archived) re-reads the whole `["rooms"]` prefix,
 * so the room's role and controls follow the truth (`isAccessRefusal`). Anything else (a 429, a network failure) changed nothing.
 */
export async function refreshAfterPinFailure(queryClient: QueryClient, roomId: number, err: unknown): Promise<void> {
  if (isProblem(err, PROBLEM_TYPES.pinLimit)) await refreshAfterPinWrite(queryClient, roomId);
  else if (isAccessRefusal(err)) await refreshQueriesAfterMutation(queryClient, ["rooms"]);
}
