import type { RoomOrganization, RoomOrganizationUpdate } from "../api/roomOrganization";

export type RoomOrganizationDraft = RoomOrganizationUpdate;
export const MAX_ROOM_PLACEMENTS = 2_000;

export function organizationDraft(source: RoomOrganization): RoomOrganizationDraft {
  return {
    groups: source.groups.map((group) => ({ ...group })),
    rooms: source.rooms.map((room) => ({ ...room })),
  };
}

function moved<T>(items: readonly T[], from: number, to: number): T[] {
  const copy = [...items];
  const [item] = copy.splice(from, 1);
  copy.splice(to, 0, item);
  return copy;
}

export function moveOrganizationGroup(draft: RoomOrganizationDraft, groupId: string, delta: -1 | 1): RoomOrganizationDraft {
  const from = draft.groups.findIndex(({ id }) => id === groupId);
  const to = from + delta;
  if (from < 0 || to < 0 || to >= draft.groups.length) return draft;
  return { ...draft, groups: moved(draft.groups, from, to) };
}

export function moveOrganizationRoom(
  draft: RoomOrganizationDraft,
  roomId: number,
  delta: -1 | 1,
  visibleRoomIds?: readonly number[],
): RoomOrganizationDraft {
  const existing = draft.rooms.find((room) => room.roomId === roomId);
  const groupId = existing?.groupId ?? null;
  const peers = visibleRoomIds ?? draft.rooms.filter((room) => room.groupId === groupId).map((room) => room.roomId);
  const index = peers.indexOf(roomId);
  const neighbor = peers[index + delta];
  if (index < 0 || neighbor === undefined) return draft;
  const placedIds = new Set(draft.rooms.map((room) => room.roomId));
  // Materialize only the implicit prefix needed to keep this move ahead of the remaining activity-ordered rooms.
  const missingVisible = peers.slice(0, Math.max(index, index + delta) + 1).filter((id) => !placedIds.has(id));
  if (draft.rooms.length + missingVisible.length > MAX_ROOM_PLACEMENTS) return draft;
  const rooms = missingVisible.length === 0
    ? draft.rooms
    : [...draft.rooms, ...missingVisible.map((id) => ({ roomId: id, groupId }))];
  const from = rooms.findIndex((room) => room.roomId === roomId);
  const to = rooms.findIndex((room) => room.roomId === neighbor);
  return { ...draft, rooms: moved(rooms, from, to) };
}

export function moveRoomToGroup(draft: RoomOrganizationDraft, roomId: number, groupId: string | null): RoomOrganizationDraft {
  const placement = draft.rooms.find((room) => room.roomId === roomId);
  if (!placement) {
    if (groupId === null || draft.rooms.length >= MAX_ROOM_PLACEMENTS) return draft;
    return { ...draft, rooms: [...draft.rooms, { roomId, groupId }] };
  }
  if (placement.groupId === groupId) return draft;
  return {
    ...draft,
    rooms: [...draft.rooms.filter((room) => room.roomId !== roomId), { roomId, groupId }],
  };
}

export function deleteOrganizationGroup(draft: RoomOrganizationDraft, groupId: string): RoomOrganizationDraft {
  const keptRooms = draft.rooms.filter((room) => room.groupId !== groupId);
  const releasedRooms = draft.rooms.filter((room) => room.groupId === groupId).map((room) => ({ ...room, groupId: null }));
  return {
    groups: draft.groups.filter(({ id }) => id !== groupId),
    rooms: [...keptRooms, ...releasedRooms],
  };
}
