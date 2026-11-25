import { describe, expect, test } from "vitest";
import {
  deleteOrganizationGroup,
  moveOrganizationGroup,
  moveOrganizationGroupTo,
  moveOrganizationRoom,
  moveRoomToGroup,
  organizationDraft,
  placeOrganizationRoom,
} from "./roomOrganizationDraft";

const source = {
  revision: 4,
  groups: [{ id: "g-1", name: "Operations" }, { id: "g-2", name: "Projects" }],
  rooms: [{ roomId: 10, groupId: "g-1" }, { roomId: 11, groupId: "g-1" }, { roomId: 12, groupId: null }],
};

describe("room organization draft", () => {
  test("copies saved placements without pinning rooms merely because they were loaded", () => {
    expect(organizationDraft(source)).toEqual({
      groups: source.groups,
      rooms: source.rooms,
    });
  });

  test("moves groups and rooms only inside their current list", () => {
    const draft = organizationDraft(source);
    expect(moveOrganizationGroup(draft, "g-2", -1).groups.map(({ id }) => id)).toEqual(["g-2", "g-1"]);
    expect(moveOrganizationRoom(draft, 11, -1).rooms.map(({ roomId }) => roomId)).toEqual([11, 10, 12]);
    expect(moveOrganizationRoom(draft, 10, -1)).toBe(draft);
  });

  test("moving and deleting groups keeps every room in the organization", () => {
    const moved = moveRoomToGroup(organizationDraft(source), 12, "g-2");
    expect(moved.rooms).toEqual([{ roomId: 10, groupId: "g-1" }, { roomId: 11, groupId: "g-1" }, { roomId: 12, groupId: "g-2" }]);

    const deleted = deleteOrganizationGroup(moved, "g-1");
    expect(deleted.groups).toEqual([{ id: "g-2", name: "Projects" }]);
    expect(deleted.rooms).toEqual([{ roomId: 12, groupId: "g-2" }, { roomId: 10, groupId: null }, { roomId: 11, groupId: null }]);
  });

  test("does not create a placement beyond the server limit", () => {
    const full = {
      groups: source.groups,
      rooms: Array.from({ length: 2_000 }, (_, index) => ({ roomId: index + 1, groupId: null })),
    };

    expect(moveRoomToGroup(full, 2_001, "g-1")).toBe(full);
    expect(moveOrganizationRoom(full, 2_001, -1, [2_000, 2_001])).toBe(full);
  });

  test("materializes implicit rooms only when the user moves them", () => {
    const draft = { groups: source.groups, rooms: [{ roomId: 10, groupId: null }] };

    expect(moveOrganizationRoom(draft, 13, -1, [10, 13]).rooms).toEqual([
      { roomId: 13, groupId: null },
      { roomId: 10, groupId: null },
    ]);
    expect(moveRoomToGroup(draft, 13, "g-1").rooms).toEqual([
      { roomId: 10, groupId: null },
      { roomId: 13, groupId: "g-1" },
    ]);
  });

  test("uses the last placement slot without pinning later rooms", () => {
    const almostFull = { groups: [], rooms: Array.from({ length: 1_999 }, (_, index) => ({ roomId: index + 1, groupId: null })) };
    const result = moveOrganizationRoom(almostFull, 2_000, -1, [1_999, 2_000, 2_001]);

    expect(result.rooms).toHaveLength(2_000);
    expect(result.rooms.slice(-2).map(({ roomId }) => roomId)).toEqual([2_000, 1_999]);
  });

  test("moves a group before another group or to the end", () => {
    const draft = organizationDraft(source);
    const moved = moveOrganizationGroupTo(draft, "g-2", "g-1");
    expect(moved.groups.map(({ id }) => id)).toEqual(["g-2", "g-1"]);
    expect(moveOrganizationGroupTo(moved, "g-2", null)).toEqual(draft);
    expect(moveOrganizationGroupTo(draft, "missing", null)).toBe(draft);
    expect(moveOrganizationGroupTo(draft, "g-1", "missing")).toBe(draft);
    expect(moveOrganizationGroupTo(draft, "g-1", "g-1")).toBe(draft);
    expect(moveOrganizationGroupTo(draft, "g-1", "g-2")).toBe(draft);
    expect(moveOrganizationGroupTo(draft, "g-2", null)).toBe(draft);
  });

  test("cross-group anchors and append retain every stored room", () => {
    const draft = organizationDraft(source);
    const before = placeOrganizationRoom(draft, 12, { groupId: "g-1", position: { kind: "before", roomId: 11 } }, []);
    expect(before.rooms).toEqual([
      { roomId: 10, groupId: "g-1" }, { roomId: 12, groupId: "g-1" }, { roomId: 11, groupId: "g-1" },
    ]);
    const end = placeOrganizationRoom(before, 12, { groupId: "g-1", position: { kind: "end" } }, []);
    expect(end.rooms.map(({ roomId }) => roomId)).toEqual([10, 11, 12]);
    const outside = placeOrganizationRoom(end, 10, { groupId: "g-2", position: { kind: "end" } }, []);
    expect(outside.rooms).toEqual([
      { roomId: 11, groupId: "g-1" }, { roomId: 12, groupId: "g-1" }, { roomId: 10, groupId: "g-2" },
    ]);
    expect(placeOrganizationRoom(outside, 10, { groupId: "typo", position: { kind: "end" } }, [])).toBe(outside);
    expect(placeOrganizationRoom(outside, 10, { groupId: "g-1", position: { kind: "before", roomId: 20 } }, [20])).toBe(outside);
    expect(placeOrganizationRoom(outside, 10, { groupId: "g-2", position: { kind: "after", roomId: 10 } }, [])).toBe(outside);
  });

  test("pins only the implicit prefix required by the chosen placement", () => {
    const draft = organizationDraft(source);
    const visible = [12, 13, 14, 15];
    const before = placeOrganizationRoom(draft, 11, { groupId: null, position: { kind: "before", roomId: 14 } }, visible);
    expect(before.rooms.filter(({ groupId }) => groupId === null).map(({ roomId }) => roomId)).toEqual([12, 13, 11]);
    const after = placeOrganizationRoom(draft, 11, { groupId: null, position: { kind: "after", roomId: 14 } }, visible);
    expect(after.rooms.filter(({ groupId }) => groupId === null).map(({ roomId }) => roomId)).toEqual([12, 13, 14, 11]);
    const end = placeOrganizationRoom(draft, 11, { groupId: null, position: { kind: "end" } }, visible);
    expect(end.rooms.filter(({ groupId }) => groupId === null).map(({ roomId }) => roomId)).toEqual([12, 13, 14, 15, 11]);
    expect(placeOrganizationRoom(draft, 13, { groupId: null, position: { kind: "before", roomId: 14 } }, visible)).toBe(draft);
    expect(placeOrganizationRoom(draft, 15, { groupId: null, position: { kind: "end" } }, visible)).toBe(draft);
  });

  test("rejects the whole drop when its implicit prefix would exceed capacity", () => {
    const draft = {
      groups: source.groups,
      rooms: Array.from({ length: 1_999 }, (_, index) => ({ roomId: index + 1, groupId: "g-1" })),
    };
    const visible = [2_000, 2_001, 2_002];
    expect(placeOrganizationRoom(draft, 1, { groupId: null, position: { kind: "end" } }, visible)).toBe(draft);
    const before = placeOrganizationRoom(draft, 1, { groupId: null, position: { kind: "before", roomId: 2_001 } }, visible);
    expect(before.rooms).toHaveLength(2_000);
    expect(before.rooms.slice(-2)).toEqual([{ roomId: 2_000, groupId: null }, { roomId: 1, groupId: null }]);
  });
});
