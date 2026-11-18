import { describe, expect, test } from "vitest";
import { newRoomPath, parseRoomId, roomMembersPath, roomPath, roomSettingsPath, roomWorkflowPath, roomsPath } from "./roomLinks";

describe("roomLinks", () => {
  test("builds the list, create, room, members, workflow and settings paths", () => {
    expect(roomsPath).toBe("/rooms");
    expect(newRoomPath).toBe("/rooms/new");
    expect(roomPath(7)).toBe("/rooms/7");
    expect(roomMembersPath(7)).toBe("/rooms/7/members");
    expect(roomWorkflowPath(7)).toBe("/rooms/7/workflow");
    expect(roomSettingsPath(7)).toBe("/rooms/7/settings");
  });

  test("parseRoomId accepts a plain positive int32 and nothing else", () => {
    expect(parseRoomId("12")).toBe(12);
    expect(parseRoomId("2147483647")).toBe(2147483647);
    for (const bad of [undefined, "", "0", "-1", "1e3", "12abc", " 5", "1.5", "2147483648"]) expect(parseRoomId(bad)).toBeNull();
  });
});
