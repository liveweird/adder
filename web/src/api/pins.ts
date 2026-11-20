// Pins API — a room's pinned threads and messages (`GET /api/v1/rooms/{id}/pins`) and the two idempotent sets (`PUT .../threads/{number}/pin`,
// `PUT .../messages/{messageId}/pin`, body `{pinned}`, 204). A room holds at most 50 pins, so one page of 50 is the whole set. Any member pins
// and unpins; the 51st pin is a 409 `pin-limit`. Thin wrappers: transport in ./http, types from ./schema.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

type RoomPinPage = paths["/api/v1/rooms/{id}/pins"]["get"]["responses"]["200"]["content"]["application/json"];
/** One pin: of a thread (`kind` `THREAD`, `message` null) or of a message (`kind` `MESSAGE`, with the message as it reads now). */
export type RoomPin = RoomPinPage["items"][number];

/** The server's cap on a room's pins; the page asked for is exactly this big, so it is the whole set. */
const PIN_LIMIT = 50;

/** The whole set, newest pin first (the server's default order). */
export async function listRoomPins(roomId: number): Promise<RoomPinPage> {
  const params = buildQuery({ page: 1, pageSize: PIN_LIMIT });
  return jsonRequest<RoomPinPage>(`/api/v1/rooms/${roomId}/pins?${params}`);
}

type PinBody = paths["/api/v1/rooms/{id}/threads/{number}/pin"]["put"]["requestBody"]["content"]["application/json"];
const pinBody = (pinned: boolean) => JSON.stringify({ pinned } satisfies PinBody);

/** A member of a live room; setting the state a thread already has is a 204 that writes nothing. */
export async function setThreadPin(roomId: number, threadNumber: number, pinned: boolean): Promise<void> {
  return voidRequest(`/api/v1/rooms/${roomId}/threads/${threadNumber}/pin`, { method: "PUT", body: pinBody(pinned) });
}

export async function setMessagePin(roomId: number, messageId: number, pinned: boolean): Promise<void> {
  return voidRequest(`/api/v1/rooms/${roomId}/messages/${messageId}/pin`, { method: "PUT", body: pinBody(pinned) });
}
