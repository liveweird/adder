import { jsonRequest } from "./http";
import type { components } from "./schema";

export type RoomOrganization = components["schemas"]["RoomOrganization"];
export type RoomOrganizationUpdate = components["schemas"]["RoomOrganizationUpdate"];

export function getRoomOrganization(): Promise<RoomOrganization> {
  return jsonRequest<RoomOrganization>("/api/v1/room-organization");
}

export function replaceRoomOrganization(body: RoomOrganizationUpdate, expectedRevision: number): Promise<RoomOrganization> {
  return jsonRequest<RoomOrganization>("/api/v1/room-organization", {
    method: "PUT",
    headers: { "X-Expected-Revision": String(expectedRevision) },
    body: JSON.stringify(body),
  });
}
