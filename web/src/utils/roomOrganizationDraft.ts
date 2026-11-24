import type { RoomOrganization, RoomOrganizationUpdate } from "../api/roomOrganization";

export type RoomOrganizationDraft = RoomOrganizationUpdate;
export type RoomOrganizationDestination = {
  groupId: string | null;
  position: { kind: "before" | "after"; roomId: number } | { kind: "end" };
};
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
  const before = draft.groups[delta === -1 ? to : to + 1];
  return moveOrganizationGroupTo(draft, groupId, before?.id ?? null);
}

export function moveOrganizationGroupTo(
  draft: RoomOrganizationDraft,
  groupId: string,
  beforeGroupId: string | null,
): RoomOrganizationDraft {
  if (groupId === beforeGroupId) return draft;
  const from = draft.groups.findIndex(({ id }) => id === groupId);
  const before = beforeGroupId === null ? draft.groups.length : draft.groups.findIndex(({ id }) => id === beforeGroupId);
  if (from < 0 || before < 0) return draft;
  const to = before > from ? before - 1 : before;
  return to === from ? draft : { ...draft, groups: moved(draft.groups, from, to) };
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
  return placeOrganizationRoom(draft, roomId, {
    groupId,
    position: { kind: delta === -1 ? "before" : "after", roomId: neighbor },
  }, peers);
}

export function moveRoomToGroup(draft: RoomOrganizationDraft, roomId: number, groupId: string | null): RoomOrganizationDraft {
  const placement = draft.rooms.find((room) => room.roomId === roomId);
  if ((placement?.groupId ?? null) === groupId) return draft;
  return placeOrganizationRoom(draft, roomId, { groupId, position: { kind: "end" } }, []);
}

export function placeOrganizationRoom(
  draft: RoomOrganizationDraft,
  roomId: number,
  { groupId, position }: RoomOrganizationDestination,
  visibleUngroupedIds: readonly number[],
): RoomOrganizationDraft {
  if (groupId !== null && !draft.groups.some(({ id }) => id === groupId)) return draft;
  if (position.kind !== "end" && position.roomId === roomId) return draft;

  const placed = new Map(draft.rooms.map((room) => [room.roomId, room.groupId]));
  const explicit = draft.rooms.filter((room) => room.groupId === groupId).map((room) => room.roomId);
  // The server lists stored placements first, followed by visible rooms with automatic order.
  const implicit = groupId === null ? visibleUngroupedIds.filter((id) => !placed.has(id)) : [];
  const current = [...explicit, ...implicit];
  const peers = current.filter((id) => id !== roomId);
  const anchorIndex = position.kind === "end" ? peers.length : peers.indexOf(position.roomId);
  if (anchorIndex < 0) return draft;
  const index = position.kind === "after" ? anchorIndex + 1 : anchorIndex;
  if (current[index] === roomId) return draft;

  const prefix = peers.slice(0, index).filter((id) => !placed.has(id));
  const additional = prefix.length + Number(!placed.has(roomId));
  if (draft.rooms.length + additional > MAX_ROOM_PLACEMENTS) return draft;

  const rooms = draft.rooms.filter((room) => room.roomId !== roomId);
  // Keep every stored placement, including rooms that the editor has yet to load.
  const next = rooms.findIndex((room) => room.roomId === peers[index]);
  rooms.splice(next < 0 ? rooms.length : next, 0, ...prefix.map((id) => ({ roomId: id, groupId })), { roomId, groupId });
  return { ...draft, rooms };
}

export function deleteOrganizationGroup(draft: RoomOrganizationDraft, groupId: string): RoomOrganizationDraft {
  const keptRooms = draft.rooms.filter((room) => room.groupId !== groupId);
  const releasedRooms = draft.rooms.filter((room) => room.groupId === groupId).map((room) => ({ ...room, groupId: null }));
  return {
    groups: draft.groups.filter(({ id }) => id !== groupId),
    rooms: [...keptRooms, ...releasedRooms],
  };
}
