import { describe, expect, test } from "vitest";
import {
  deleteOrganizationGroup,
  moveOrganizationGroup,
  moveOrganizationRoom,
  moveRoomToGroup,
  organizationDraft,
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
});
